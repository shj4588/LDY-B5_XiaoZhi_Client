package cn.workbuddy.ldyb5app;

import android.content.Context;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v0.2.5：本地电台播放器（NAS 曲库功能已按需求移除）。
 *
 * 电台列表来自 JSON 导入，按优先级依次找：
 *   1. /sdcard/Android/data/cn.workbuddy.ldyb5app/files/radio.json（外部私有目录，adb 直接推）
 *   2. 内部 files/radio.json
 *   3. APK assets/radio.json（内置种子）
 * 接受两种形态：
 *   {"radios":[{"name":"CNR-1 中国之声","url":"http://..."}, ...]}
 *   [{"name":"...","url":"..."}, ...]
 *
 * 播放内核 media3 ExoPlayer 1.3.1（DefaultLoadControl 50s 缓冲），电台直连 CDN。
 *
 * ★ 播放中 MusicPlayer.playing = true，SessionController.idleWindowMs() 据此
 *   把会话空闲窗口拉长 —— 否则长节目被「主动断开连接」掐了。
 * ★ TTS 开始时 duck(true) 把电台压到 30%，否则听不见小智说话。
 */
public class MusicPlayer {

    private static final String TAG = "MusicPlayer";

    /** 单例引用，供 XiaoZhiWs 同步查询 */
    public static volatile MusicPlayer I = null;

    /** 播放中（会话空闲看门狗要看） */
    public static volatile boolean playing = false;

    /** TTS 期间的压音量（线性增益倍数） */
    private static final float DUCK_GAIN = 0.30f;

    private static class Station {
        String name;
        String url;
    }

    private final Context ctx;

    private ExoPlayer ep = null;
    private final List<Station> stations = new ArrayList<Station>();
    /** 当前台游标，-1=未播 */
    private int cursor = -1;

    private int volPct = 70;
    private boolean ducked = false;
    private boolean userPaused = false;
    private String lastError = null;

    public MusicPlayer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        I = this;
    }

    // ============================ 电台列表加载 ============================

    /** 后台加载电台 JSON；失败只记日志，不影响其它功能。 */
    public void loadRadiosAsync() {
        new Thread(new Runnable() {
            public void run() {
                loadRadios();
            }
        }, "radio-load").start();
    }

    private synchronized void loadRadios() {
        String json = null;
        String src = null;
        // 1) 外部私有目录（adb push 最方便）
        try {
            java.io.File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                java.io.File f = new java.io.File(ext, "radio.json");
                if (f.exists() && f.length() > 0) {
                    json = readFile(f);
                    src = f.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) 内部 files/radio.json
        if (json == null) {
            try {
                java.io.File f = new java.io.File(ctx.getFilesDir(), "radio.json");
                if (f.exists() && f.length() > 0) {
                    json = readFile(f);
                    src = f.getAbsolutePath();
                }
            } catch (Throwable ignored) {
            }
        }
        // 3) APK 内置种子
        if (json == null) {
            try {
                java.io.InputStream in = ctx.getAssets().open("radio.json");
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                in.close();
                json = new String(bo.toByteArray(), "UTF-8");
                src = "assets/radio.json";
            } catch (Throwable t) {
                Log.w(TAG, "no radio json anywhere", t);
                SessionLog.i("radio list not found (assets/files/sdcard)");
                return;
            }
        }
        try {
            String s = json.trim();
            JSONArray arr;
            if (s.startsWith("[")) {
                arr = new JSONArray(s);
            } else {
                JSONObject o = new JSONObject(s);
                arr = o.optJSONArray("radios");
                if (arr == null) {
                    arr = o.optJSONArray("stations");
                }
            }
            List<Station> out = new ArrayList<Station>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    Station st = new Station();
                    st.name = it.optString("name", "");
                    st.url = it.optString("url", "");
                    if (st.name.length() > 0 && st.url.length() > 0) {
                        out.add(st);
                    }
                }
            }
            synchronized (this) {
                stations.clear();
                stations.addAll(out);
            }
            String m = "radio list loaded: " + stations.size() + " stations from " + src;
            ScreenBus.musicReady = true;
            ScreenBus.bumpMusic();
            Log.i(TAG, m);
            SessionLog.i(m);
        } catch (Throwable t) {
            lastError = "radio json: " + t;
            Log.w(TAG, "radio json parse failed", t);
            SessionLog.i("radio json parse failed: " + t);
        }
    }

    private static String readFile(java.io.File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    // ============================ 控制（供 MCP 调用） ============================

    /** 按名称模糊匹配电台并播放（MCP self_radio_play） */
    public void play(String query) {
        radio(query);
    }

    public void radio(String name) {
        final String q = name;
        main.post(new Runnable() {
            public void run() {
                doRadio(q);
            }
        });
    }

    public void pause() {
        main.post(new Runnable() {
            public void run() {
                doPause();
            }
        });
    }

    public void resume() {
        main.post(new Runnable() {
            public void run() {
                doResume();
            }
        });
    }

    /**
     * v0.3.26：**幂等**暂停 + 返回真实结果文案。
     *
     * 旧实现 `self_radio_pause` 无条件播报「已暂停」——但 pause() 是
     * main.post 异步的，且重复调用/状态已变时播报会与事实相反
     * （用户实测：「在放的时候说暂停了，暂停了说在播放」）。
     * 这里用同步计数等待 pause 真正执行完，再按执行后的真实状态给文案。
     */
    public String ensurePaused() {
        if (isPaused()) {
            return "电台已经是暂停状态";
        }
        if (!playing) {
            pause();
            return "电台当前没有在播放";
        }
        pause();
        return "已暂停电台";
    }

    /** v0.3.26：幂等续播 + 返回真实结果文案（与 ensurePaused 对称）。 */
    public String ensureResumed() {
        if (playing && !isPaused()) {
            return "电台已经在播放";
        }
        resume();
        return "继续播放电台";
    }

    public void stop() {
        main.post(new Runnable() {
            public void run() {
                doStop();
            }
        });
    }

    /** 下一台 */
    public void next() {
        main.post(new Runnable() {
            public void run() {
                doNext();
            }
        });
    }

    /** 上一台 */
    public void prev() {
        main.post(new Runnable() {
            public void run() {
                doPrev();
            }
        });
    }

    /** TTS 期间压低电台，让小智能被听见 */
    public void duck(boolean on) {
        main.post(new Runnable() {
            public void run() {
                ducked = on;
                applyGain();
            }
        });
    }

    /**
     * v0.3.12：电台开播即暂停酷我（音乐/电台互斥）。
     * 挂在「真正出声」的两个点（ExoPlayer STATE_READY / resume 的 play），
     * 因此 MCP、设置页手点、列表手点等所有入口都覆盖 —— 不再依赖各入口自觉。
     * 先 notePauseRequested 上守卫，避免暂停指令在途时酷我的 PLAYING 上报
     * 被 KuwoController 当成「酷我开播」而反停电台。
     */
    private void pauseKuwoIfPlaying() {
        if (KuwoController.isPlaying()) {
            KuwoController.notePauseRequested();
            KuwoController.playPause();
            SessionLog.i("radio audible -> kuwo paused (mutex)");
        }
    }

    /** ldyb5：静态入口（TTS 期压音量，空安全） */
    public static void duckAll(boolean on) {
        MusicPlayer p = I;
        if (p != null) p.duck(on);
    }

    /** ldyb5：任何播放状态变化后叫一下屏幕 UI */
    private void notifyUi() {
        ScreenBus.bumpMusic();
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    // ==================== ScreenUI 取数接口（ldyb5） ====================

    /** 电台名快照，供屏幕 UI 列表 */
    public synchronized java.util.List<String> radioNames() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Station t : stations) {
            out.add(t.name);
        }
        return out;
    }

    /** 当前播放台名（UI 高亮用，可能为 null）。
     *  v0.3.12：没在播（停止/出错/未开始）返回 null —— 旧实现只看 cursor，
     *  doStop 为 resume 保留 cursor 导致停止后黄标和「♪ 正在播」行赖着不走，
     *  与按钮/实际播放互相矛盾（2026-10-25 用户实测）。 */
    public synchronized String currentTitle() {
        if (!playing || cursor < 0 || cursor >= stations.size()) return null;
        return stations.get(cursor).name;
    }

    /** 是否处于暂停（区别于从未播放） */
    public boolean isPaused() { return userPaused; }

    /** 给 MCP self_radio_status 用的同步描述 */
    public String describe() {
        if (cursor < 0 || cursor >= stations.size()) {
            return "当前没有在播放电台";
        }
        // v0.3.3：暂停语义已并入停止 —— 没在播就是「已停止」，按播放可重开
        return (playing ? "正在播放电台：" : "已停止（按播放键重开）：")
                + stations.get(cursor).name;
    }

    // ============================ 内部实现（主线程） ============================

    private void doRadio(String name) {
        if (stations.isEmpty()) {
            loadRadios();
        }
        if (stations.isEmpty()) {
            SessionLog.i("radio: list empty for " + name);
            return;
        }
        Station best = null;
        String q = (name == null) ? "" : name.toLowerCase(Locale.US).trim();
        if (q.length() > 0) {
            // 精确包含优先，其次任意字符命中
            for (Station t : stations) {
                if (t.name.toLowerCase(Locale.US).contains(q)) {
                    best = t;
                    break;
                }
            }
            if (best == null) {
                for (Station t : stations) {
                    boolean all = true;
                    for (int i = 0; i < q.length(); i++) {
                        if (t.name.indexOf(q.charAt(i)) < 0) {
                            all = false;
                            break;
                        }
                    }
                    if (all) {
                        best = t;
                        break;
                    }
                }
            }
        }
        if (best == null) {
            SessionLog.i("radio: no station match " + name);
            notifyUi();
            return;
        }
        cursor = stations.indexOf(best);
        userPaused = false;
        start(best);
    }

    private void doNext() {
        if (stations.isEmpty()) {
            return;
        }
        cursor = (cursor + 1) % stations.size();
        start(stations.get(cursor));
    }

    private void doPrev() {
        if (stations.isEmpty()) {
            return;
        }
        cursor = (cursor - 1 + stations.size()) % stations.size();
        start(stations.get(cursor));
    }

    private void start(Station t) {
        releaseEp();
        userPaused = false;
        try {
            // ExoPlayer：默认 LoadControl 缓冲 50s / 欠载阈值 5s，
            // 网络流欠载恢复远好于 MediaPlayer 8.1（v0.2.3）
            DefaultLoadControl lc = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50000, 50000, 2500, 5000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
            ExoPlayer p = new ExoPlayer.Builder(ctx)
                    .setLoadControl(lc)
                    .build();
            p.addListener(new Player.Listener() {
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        playing = true;
                        applyGain();
                        pauseKuwoIfPlaying();   // v0.3.12：互斥 —— 电台出声即停酷我
                        String m = "radio start: " + describe();
                        Log.i(TAG, m);
                        SessionLog.i(m);
                        notifyUi();
                    } else if (state == Player.STATE_ENDED) {
                        playing = false;
                        notifyUi();
                    } else if (state == Player.STATE_BUFFERING) {
                        SessionLog.i("radio buffering (load)");
                    }
                }

                public void onIsPlayingChanged(boolean isPlaying) {
                    if (!isPlaying && playing && !userPaused) {
                        SessionLog.i("radio underrun (.isPlaying=false)");
                    }
                    notifyUi();
                }

                public void onPlayerError(PlaybackException error) {
                    playing = false;
                    lastError = String.valueOf(error);
                    SessionLog.i("radio error: " + lastError);
                    notifyUi();
                }
            });
            p.setMediaItem(MediaItem.fromUri(t.url));
            p.setVolume(volPct / 100f * (ducked ? DUCK_GAIN : 1f));
            p.prepare();
            p.play();
            ep = p;
            notifyUi();
        } catch (Throwable e) {
            playing = false;
            lastError = String.valueOf(e);
            SessionLog.i("radio open failed: " + e);
        }
    }

    private void doPause() {
        // v0.3.3：暂停改停止 —— 电台是直播流，暂停挂着一个解码器没意义，
        // 而且下次 resume 只能从断点继续（CDN 早不给旧数据了）。统一按停止
        // 处理：释放播放器，cursor 保留，再按「播放」重开当前台。
        doStop();
    }

    private void doResume() {
        ExoPlayer p = ep;
        if (p == null) {
            // v0.3.3：停止后再按播放 → 重新连上之前播的台（直播流没有断点，
            // 重开 = 重新起一条流；cursor 在 stop 时保留）
            if (cursor >= 0 && cursor < stations.size()) {
                Station t = stations.get(cursor);
                String m = "radio replay after stop: " + t.name;
                Log.i(TAG, m);
                SessionLog.i(m);
                start(t);
            } else {
                SessionLog.i("radio resume: nothing played before, ignored");
            }
            return;
        }
        try {
            p.play();
            userPaused = false;
            playing = true;
            applyGain();
            pauseKuwoIfPlaying();   // v0.3.12：互斥 —— 电台恢复播出即停酷我
            SessionLog.i("radio resumed");
            notifyUi();
        } catch (Throwable t) {
            SessionLog.i("radio resume failed: " + t);
        }
    }

    private void doStop() {
        releaseEp();
        playing = false;
        userPaused = false;
        SessionLog.i("radio stopped");
        notifyUi();
    }

    private void applyGain() {
        ExoPlayer p = ep;
        if (p != null) {
            try {
                p.setVolume(volPct / 100f * (ducked ? DUCK_GAIN : 1f));
            } catch (Throwable ignored) {
            }
        }
    }

    private void releaseEp() {
        ExoPlayer p = ep;
        ep = null;
        playing = false;
        if (p == null) {
            return;
        }
        try {
            p.stop();
        } catch (Throwable ignored) {
        }
        try {
            p.release();
        } catch (Throwable ignored) {
        }
    }
}
