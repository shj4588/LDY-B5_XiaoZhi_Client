package cn.workbuddy.ldyb5app;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 天气获取 —— IP 定位（ip-api.com）或手动城市（open-meteo geocoding）
 * + open-meteo 当前天气与多日预报（均免 key）。
 * last / city / forecast 供 ScreenUI 轮询直接读；30 分钟刷新，失败保留旧值。
 * Android 8.1 默认允许 cleartext，ip-api 免费层只有 http，正好可用。
 */
public class WeatherClient {

    private static final String TAG = "WeatherClient";
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final int FORECAST_DAYS = 5;

    /** 当前天气短串，如「阴 20°」；未就绪为 "" */
    public static volatile String last = "";
    /** 当前位置名（自动定位的城市名或手动填的城市名） */
    public static volatile String city = "";
    /** 未来几天预报，每项如「10/26 周一 多云 16~22°」 */
    public static volatile String[] forecast = new String[0];

    private static volatile boolean started = false;
    private static Context appCtx;
    /** 设置里改了位置 → 置脏，后台循环立即重取 */
    private static volatile boolean dirty = false;
    // 手动城市 → 坐标缓存（换城市才重新 geocode）
    private static String geoCity = "";
    private static double geoLat = 999, geoLon = 999;

    private static final SimpleDateFormat DAY_FMT =
            new SimpleDateFormat("M/d EEE", Locale.CHINA);

    public static synchronized void start(Context c) {
        if (started) {
            return;
        }
        started = true;
        appCtx = c.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    String[] r = fetch();
                    if (r != null) {
                        last = r[0];
                        city = r[1];
                        forecast = parseForecast(r[2]);
                    }
                    dirty = false;
                    // 30 分钟一轮；reload() 置脏后 2s 内重取
                    for (int i = 0; i < 900 && !dirty; i++) {
                        try {
                            Thread.sleep(dirty ? 100L : 2000L);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        }, "WeatherClient");
        t.setDaemon(true);
        t.start();
    }

    /** 设置里改了位置后调用，立刻重取 */
    public static void reload() {
        dirty = true;
    }

    /** @return {当前天气, 城市名, forecast原始串}；失败 null */
    private static String[] fetch() {
        try {
            double lat, lon;
            String manual = appCtx == null ? "" : AppSettings.wxCity(appCtx);
            if (manual.length() > 0) {
                if (!manual.equals(geoCity)) {
                    JSONObject g = getJson(
                            "https://geocoding-api.open-meteo.com/v1/search?name="
                                    + java.net.URLEncoder.encode(manual, "UTF-8")
                                    + "&count=1&language=zh&format=json");
                    if (g == null) {
                        return null;
                    }
                    org.json.JSONArray res = g.optJSONArray("results");
                    if (res == null || res.length() == 0) {
                        Log.w(TAG, "geocode no result for " + manual);
                        geoLat = 999;
                        return null;
                    }
                    JSONObject o = res.getJSONObject(0);
                    geoLat = o.optDouble("latitude", 999);
                    geoLon = o.optDouble("longitude", 999);
                    String nm = o.optString("name", manual);
                    geoCity = manual;
                    if (Math.abs(geoLat) > 90) {
                        return null;
                    }
                    city = nm;
                }
                lat = geoLat;
                lon = geoLon;
                if (city.length() == 0) {
                    city = manual;
                }
            } else {
                geoCity = "";
                JSONObject loc = getJson(
                        "http://ip-api.com/json/?fields=status,city,lat,lon");
                if (loc == null || !"success".equals(loc.optString("status"))) {
                    return null;
                }
                lat = loc.optDouble("lat", 999);
                lon = loc.optDouble("lon", 999);
                if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                    return null;
                }
                city = loc.optString("city", "");
            }

            JSONObject wx = getJson(
                    "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                            + "&longitude=" + lon
                            + "&current=temperature_2m,weather_code&timezone=auto"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                            + "&forecast_days=" + FORECAST_DAYS);
            if (wx == null) {
                return null;
            }
            JSONObject cur = wx.optJSONObject("current");
            if (cur == null) {
                return null;
            }
            int code = cur.optInt("weather_code", -1);
            double temp = cur.optDouble("temperature_2m", 999);
            if (code < 0 || temp > 90) {
                return null;
            }
            return new String[]{codeText(code) + " " + (int) Math.round(temp) + "°",
                    city, wx.optJSONObject("daily") == null
                            ? "" : wx.getJSONObject("daily").toString()};
        } catch (Throwable t) {
            Log.w(TAG, "weather fetch failed: " + t);
            return null;
        }
    }

    /** daily JSON → "10/26 周一 多云 16~22°" 数组 */
    private static String[] parseForecast(String dailyJson) {
        if (dailyJson == null || dailyJson.length() == 0) {
            return new String[0];
        }
        try {
            JSONObject d = new JSONObject(dailyJson);
            org.json.JSONArray dates = d.getJSONArray("time");
            org.json.JSONArray codes = d.getJSONArray("weather_code");
            org.json.JSONArray tmax = d.getJSONArray("temperature_2m_max");
            org.json.JSONArray tmin = d.getJSONArray("temperature_2m_min");
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < dates.length() && i < FORECAST_DAYS; i++) {
                try {
                    Date day = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .parse(dates.getString(i));
                    String label = i == 0 ? "今天"
                            : DAY_FMT.format(day);
                    out.add(label + "  " + codeText(codes.getInt(i)) + "  "
                            + (int) Math.round(tmin.getDouble(i)) + "~"
                            + (int) Math.round(tmax.getDouble(i)) + "°");
                } catch (Throwable ignore) {
                }
            }
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            Log.w(TAG, "parseForecast failed: " + t);
            return new String[0];
        }
    }

    /** WMO weather code → 中文短描述 */
    private static String codeText(int c) {
        if (c == 0) return "晴";
        if (c == 1) return "基本晴";
        if (c == 2) return "多云";
        if (c == 3) return "阴";
        if (c == 45 || c == 48) return "雾";
        if (c >= 51 && c <= 57) return "毛毛雨";
        if (c >= 61 && c <= 67) return "雨";
        if (c >= 71 && c <= 77) return "雪";
        if (c >= 80 && c <= 82) return "阵雨";
        if (c == 85 || c == 86) return "阵雪";
        if (c == 95) return "雷雨";
        if (c >= 96) return "雷雨冰雹";
        return "未知";
    }

    private static JSONObject getJson(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "ldyb5-client/0.3.6");
            int rc = c.getResponseCode();
            if (rc != 200) {
                Log.w(TAG, "HTTP " + rc + " for " + url);
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            return new JSONObject(bo.toString("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "getJson failed: " + t);
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
