package cn.workbuddy.ldyb5app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * v0.3.47：电视直播（com.github.mytv.android / MyTV 2.2.0.219）语音控制。
 *
 * <p>设计前提（2026-11-06 逆向 + 实测，见《电视直播mytv-android_接口逆向报告.md》）：
 * <ul>
 *   <li>MyTV 自带 HTTP 服务 127.0.0.1:10591，由 MainActivity.onResume 启动、进程被回收即消失。
 *       看电视时 App 在前台服务必然在；没看电视时「先打开、等就绪、再换台」本身就会预热好。</li>
 *   <li>频道表 = {@code cache/iptv_source_cache/iptv_source_<hash>.json}，
 *       hash 是 IptvSource 10 字段的 Kotlin data class hashCode 的无符号 16 进制。</li>
 *   <li><b>只按默认源建表</b>：默认源由 /api/configs 的 iptvSourceCurrentIdx 决定。
 *       4 个源有 35 个跨源重名台，混表会把「浙江卫视」播成网络广播音频。</li>
 *   <li>切台用 {@code mytv://play?url=...&name=...} deeplink，不校验源归属，
 *       所以拼 URL 前必须保证它来自当前源缓存（本类天然满足）。</li>
 *   <li>冷启动直接发 deeplink 会被「首次启动，选择第一个频道」覆盖，
 *       因此先 launch + 等 HTTP 就绪 + 稍等，再发 deeplink。</li>
 *   <li>deeplink 不会更新 MyTV 的 iptvChannelLastPlay，顺序切台的游标必须自己记。</li>
 *   <li><b>台号口径 = 电视直播里按源顺序自动排的 1-based 序号</b>，不是本类自定义的编号：
 *       MyTV 数字选台 {@code fv0.m} 用 {@code qq0.b.i()} 把频道分组按「分组顺序 + 组内顺序」
 *       展开成一张扁平表，再取 {@code list[n-1]}；本类读的是同一份缓存 JSON，用完全相同的
 *       顺序展开并令 {@code no = 展开下标 + 1}。因此「第 5 台」在电视和小智是同一个台。
 *       界面右上角角标不是台号（源没写 tvg-chno 时退化成分组编号），见报告第十节。</li>
 * </ul>
 *
 * <p>所有网络调用都在 XiaoZhiWs 起的后台线程里跑，不阻塞 WebSocket 读线程。
 */
public final class TvController {

    private static final String TAG = "L5CLIENT-TV";
    private static final String PKG = "com.github.mytv.android";
    private static final String ACT = PKG + "/top.yogiczy.mytv.tv.MainActivity";
    private static final String BASE = "http://127.0.0.1:10591";
    private static final String CACHE_DIR =
            "/data/user/0/com.github.mytv.android/cache/iptv_source_cache";

    /** 频道表有效期：期间内只要默认源没变就复用；idx 每次都会查，用户在电视上手换源也能跟上 */
    private static final long TABLE_TTL_MS = 5 * 60 * 1000L;

    private TvController() {
    }

    private static volatile Context appCtx;

    /** 当前默认源的频道表（1-based 台号 == 数组下标 + 1） */
    private static final List<Chan> channels = new ArrayList<Chan>();
    private static volatile String sourceName = null;
    private static volatile int sourceIdx = -1;
    private static volatile long tableAt = 0L;
    private static volatile int currentIndex = -1;      // 0-based；-1 = 未知
    private static volatile long lastLaunchMs = 0L;     // 最近一次主动拉起 MyTV 的时刻
    private static volatile boolean userClosed = false; // 「关闭电视」后置位，下次打开才清
    private static volatile long lastActiveMs = 0L;     // 最近一次确认电视在用
    private static final Random RND = new Random();

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        Log.i(TAG, "TvController init");
    }

    /** MCP tools/call 的统一入口：同步执行，调用方必须放在后台线程。 */
    public static String handle(String tool, String query) {
        try {
            if (appCtx == null) return "电视控制未就绪（服务未启动）";
            String q = query == null ? "" : query.trim();
            if ("self_tv_open".equals(tool)) return open();
            if ("self_tv_close".equals(tool)) return close();
            if ("self_tv_play".equals(tool)) return play(q);
            if ("self_tv_next".equals(tool)) return step(1);
            if ("self_tv_prev".equals(tool)) return step(-1);
            if ("self_tv_random".equals(tool)) return random();
            if ("self_tv_status".equals(tool)) return status();
            if ("self_tv_list".equals(tool)) return list(q);
            if ("self_tv_source".equals(tool)) return source(q);
            return "未知电视指令：" + tool;
        } catch (Throwable t) {
            Log.w(TAG, "handle " + tool + " threw", t);
            return "电视控制出错：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    // ---------------------------------------------------------------- 指令

    /** 只打开电视直播（不指定频道）；顺带预热 HTTP 并预建频道表。 */
    private static String open() {
        boolean cold = ensureReady(true);
        boolean ok = ensureTable(false);
        Log.i(TAG, "open cold=" + cold + " table=" + ok);
        SessionLog.i("tv open: " + sourceName + " (" + channels.size() + " ch)");
        if (!ok) return "电视直播已打开，但暂时没能读到频道列表，可以直接点播台名试试";
        return "电视直播已打开，默认源「" + sourceName + "」，共 " + channels.size()
                + " 个频道" + (currentIndex >= 0
                ? "，当前第 " + (currentIndex + 1) + " 台「" + channels.get(currentIndex).name + "」"
                : "");
    }

    /** 关闭电视直播：停掉进程（有 root 时）并回到小智界面。 */
    private static String close() {
        boolean stopped = forceStop();
        try {
            Intent i = new Intent(appCtx, ScreenUI.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            appCtx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "back to ScreenUI failed", t);
        }
        // 表留在内存里（下次打开更快），但游标标脏，重新打开时按 lastPlay 校正
        currentIndex = -1;
        userClosed = true;
        Log.i(TAG, "close forceStop=" + stopped);
        SessionLog.i("tv close (forceStop=" + stopped + ")");
        return (stopped && !tvAlive()) ? "电视直播已关闭"
                : "已退出电视直播界面，回到小智了";
    }

    /** 点播：台名（含别名/归一化）或频道号（1-based，支持中文数字与「第N台」）。 */
    private static String play(String q) {
        if (q.length() == 0) return open();   // 「看电视」「打开电视直播」误选到 play 时也不出错
        if (!ensureTable(true)) return "电视直播没打开，也没能自动拉起，请先说「打开电视」";
        Chan c;
        int no = parseChannelNo(q);
        if (no > 0) {
            if (no > channels.size()) {
                return "默认源「" + sourceName + "」只有 " + channels.size()
                        + " 个频道，没有第 " + no + " 台";
            }
            c = channels.get(no - 1);
        } else {
            c = find(q);
            if (c == null) {
                return "默认源「" + sourceName + "」里没找到「" + q
                        + "」，可以报台号（比如「5台」）或让我列出频道";
            }
        }
        return doPlay(c, no > 0 ? "频道号" : "台名");
    }

    /** 上一个 / 下一个台：只在当前默认源的列表里顺序移动，到头循环。 */
    private static String step(int delta) {
        if (!ensureTable(true)) return "电视直播没打开，也没能自动拉起，请先说「打开电视」";
        int n = channels.size();
        if (n == 0) return "频道列表是空的";
        int base = currentIndex;
        if (base < 0) base = 0;
        int idx = (base + delta + n) % n;
        Chan c = channels.get(idx);
        String r = doPlay(c, delta > 0 ? "下个台" : "上个台");
        if (r.startsWith("已切到")) {
            return (delta > 0 ? "下一个台：" : "上一个台：") + describe(c);
        }
        return r;
    }

    /** 随机播个台：同样只在默认源里随机，且尽量避开当前台。 */
    private static String random() {
        if (!ensureTable(true)) return "电视直播没打开，也没能自动拉起，请先说「打开电视」";
        int n = channels.size();
        if (n == 0) return "频道列表是空的";
        int idx = RND.nextInt(n);
        if (n > 1 && idx == currentIndex) idx = (idx + 1) % n;
        Chan c = channels.get(idx);
        String r = doPlay(c, "随机");
        if (r.startsWith("已切到")) return "随机播放" + describe(c);
        return r;
    }

    private static String status() {
        boolean alive = tvAlive();
        if (alive) ensureTable(false);
        if (channels.isEmpty()) {
            return alive ? "电视直播已打开，频道列表还没加载，说「打开电视」或点播一个台即可"
                    : "电视直播现在没打开（说「打开电视」可以打开）";
        }
        String cur = currentIndex >= 0 && currentIndex < channels.size()
                ? "，当前第 " + (currentIndex + 1) + " 台「" + channels.get(currentIndex).name + "」" : "";
        return "电视直播" + (alive ? "运行中" : "未打开")
                + "，默认源「" + sourceName + "」（第 " + (sourceIdx + 1)
                + " 个订阅源），共 " + channels.size() + " 个频道" + cur;
    }

    /** 列出频道（可带关键词过滤），回答「有哪些台」时用。 */
    private static String list(String q) {
        boolean alive = tvAlive();
        if (alive) ensureTable(false);
        if (channels.isEmpty()) return "频道列表暂时读不到，先说「打开电视」";
        String key = norm(q);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int from = 0;
        // 「从第N台开始列」：给个简单的翻页能力
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(q);
        if (m.find() && ("列".equals(q.substring(0, Math.min(1, q.length()))) || q.contains("从"))) {
            from = Math.max(0, Integer.parseInt(m.group(1)) - 1);
        }
        sb.append("默认源「").append(sourceName).append("」共 ")
          .append(channels.size()).append(" 个频道：");
        for (int i = from; i < channels.size() && shown < 20; i++) {
            Chan c = channels.get(i);
            if (key.length() >= 2 && !norm(c.name).contains(key)
                    && !norm(c.standardName).contains(key)) continue;
            sb.append(shown == 0 ? "" : "、").append(i + 1).append(" ").append(c.name);
            shown++;
        }
        if (shown == 0) sb.append("没有匹配的频道");
        if (from + shown < channels.size()) sb.append(" …（后面还有）");
        return sb.toString();
    }

    /** 明确要求换源时才调用：POST /api/configs 改默认源 + 重启 MyTV 让界面与频道表一致。 */
    private static String source(String q) {
        if (q.length() == 0) return status();
        // ensureReady 返回的是「这次是不是冷启动」，不是成功与否，别拿它当失败判断
        ensureReady(true);
        if (!httpAlive()) return "电视直播没能拉起，无法换源";
        JSONObject cfg;
        try {
            cfg = new JSONObject(http("GET", "/api/configs", null));
        } catch (Throwable t) {
            return "读取订阅源列表失败：" + t.getMessage();
        }
        JSONArray arr = cfg.optJSONObject("iptvSourceList") == null ? null
                : cfg.optJSONObject("iptvSourceList").optJSONArray("value");
        if (arr == null || arr.length() == 0) return "订阅源列表是空的";
        int target = -1;
        int no = parseChannelNo(q);
        if (no > 0 && no <= arr.length()) target = no - 1;
        if (target < 0) {
            String nq = norm(q);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String nm = norm(o.optString("name", ""));
                if (nm.equals(nq) || (!nq.isEmpty() && (nm.contains(nq) || nq.contains(nm)))) {
                    target = i;
                    break;
                }
            }
        }
        if (target < 0) {
            StringBuilder sb = new StringBuilder("没有叫「" + q + "」的订阅源，当前有 " + arr.length() + " 个：");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                sb.append(i == 0 ? "" : "、").append(i + 1).append(" ")
                  .append(o == null ? "?" : o.optString("name", "?"));
            }
            return sb.toString();
        }
        JSONObject tgt = arr.optJSONObject(target);
        String nm = tgt == null ? ("源" + (target + 1)) : tgt.optString("name", "源" + (target + 1));
        try {
            String body = new JSONObject().put("iptvSourceCurrentIdx", target).toString();
            String r = http("POST", "/api/configs", body);
            Log.i(TAG, "switch source idx=" + target + " -> " + r);
        } catch (Throwable t) {
            return "切换订阅源失败：" + t.getMessage();
        }
        // POST 只写盘；MyTV 内存里的当前源不会立刻变，重启一次让它按新 idx 加载，
        // 这样界面频道号、频道列表、deeplink 归属三者才一致。
        forceStop();
        launchApp();
        ensureReady(true);
        sleep(1400);
        tableAt = 0;
        sourceIdx = -1;
        currentIndex = -1;
        if (!ensureTable(false)) return "已切到订阅源「" + nm + "」，但频道列表暂时没读到";
        Chan c = channels.isEmpty() ? null : channels.get(0);
        if (c == null) return "已切到订阅源「" + nm + "」，但它没有频道";
        String r = doPlay(c, "换源");
        if (!r.startsWith("已切到")) return "已切到订阅源「" + nm + "」，但自动播放失败";
        return "已切换到订阅源「" + nm + "」，" + describe(c);
    }

    private static String describe(Chan c) {
        return "第 " + c.no + " 台「" + c.name + "」";
    }

    // ---------------------------------------------------------------- 播放

    /** 发 deeplink。URL 一定来自当前源缓存，天然满足「源归属校验」。 */
    private static String doPlay(Chan c, String via) {
        // 无线路的频道仍占台号，但不能发空 deeplink
        if (c.url == null || c.url.length() == 0) {
            return "第 " + c.no + " 台「" + c.name + "」在当前源里没有可用播放线路";
        }
        // 主路径：换台总线。与界面点台标写同一份完整频道对象，另注入按源顺序自动排的
        // 台号，让界面角标直接显示 05 而不是分组编号 02。
        if (postPlay(c)) {
            currentIndex = c.no - 1;
            String m = "tv play (POST/" + via + ") " + describe(c) + " channelNumber=" + c.no;
            Log.i(TAG, m);
            SessionLog.i(m);
            return "已切到" + describe(c) + "（默认源「" + sourceName + "」）";
        }
        // 降级：deeplink。完全不需要 10591。实测 deeplink 会让 MyTV 的
        // MainActivity 走 onDestroy 顺带停掉 HTTP 服务，所以这里绝不为了
        // 「读数据」去探活/补服务，只保证 App 已启动且在前台。
        boolean cold = ensureAppReady();
        StringBuilder u = new StringBuilder("mytv://play?url=")
                .append(Uri.encode(c.url))
                .append("&name=").append(Uri.encode(c.name));
        if (notEmpty(c.ua)) u.append("&ua=").append(Uri.encode(c.ua));
        if (notEmpty(c.referer)) u.append("&referer=").append(Uri.encode(c.referer));
        if (notEmpty(c.origin)) u.append("&origin=").append(Uri.encode(c.origin));
        if (notEmpty(c.cookie)) u.append("&cookie=").append(Uri.encode(c.cookie));
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u.toString()));
            i.setComponent(new ComponentName(PKG, "top.yogiczy.mytv.tv.MainActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appCtx.startActivity(i);
            currentIndex = c.no - 1;
            String m = "tv play (" + via + ") " + describe(c) + " cold=" + cold;
            Log.i(TAG, m);
            SessionLog.i(m);
            return "已切到" + describe(c) + "（默认源「" + sourceName + "」）";
        } catch (Throwable t) {
            Log.w(TAG, "deeplink failed", t);
            return "切台失败：" + t.getMessage();
        }
    }

    /**
     * 换台总线：把缓存里的完整频道对象写进 iptvChannelLastPlay，再重启 PlayerActivity。
     *
     * <p>与界面点台标写的是同一份真实 vp0（ViewModel 会按当前源认领它），所以列表高亮、
     * 播放状态、角标都与手动点台一致，不会出现 deeplink 临时对象的「00 + 选中态脱节」。
     * 附带 {@code channelNumber = c.no}（按源顺序自动排的台号），让角标显示 05。
     * hash 必须保持缓存原值 —— {@code vp0.equals()} 只比 hash，改了 MyTV 就认不出频道。
     *
     * <p>只 POST 不会切台（SP 无监听器），必须重启播放器；重启不会停掉 10591。
     * 看电视时 HTTP 常驻，直接 POST 即可；没看电视时先预热（用户已确认「先打开再换台」）。
     * 冷启动存在「首次选台覆盖 lastPlay」的竞态，所以每次 POST 后用 hash 回读校验，
     * 未被认领就重写重试（最多三次），三次仍失败才降级 deeplink。
     */
    private static boolean postPlay(Chan c) {
        if (appCtx == null || c.rawJson == null || c.rawJson.length() == 0) return false;
        if (!AppSettings.rootEnabled(appCtx)) return false;      // 重启播放器需要 root
        try {
            boolean cold = ensureReady(true);
            if (!httpAlive()) return false;
            // 冷启动时 MyTV 的「首次启动，选择第一个频道」会用内存状态回写 lastPlay，
            // 把刚 POST 进去的台覆盖掉（实测偶发失败）。等这段初始化结束再写。
            if (cold) sleep(1800);
            JSONObject ch = new JSONObject(c.rawJson);
            ch.put("channelNumber", c.no);
            String body = new JSONObject().put("iptvChannelLastPlay", ch).toString();
            int wantHash = ch.optInt("hash", Integer.MIN_VALUE);
            for (int attempt = 1; attempt <= 3; attempt++) {
                String r = http("POST", "/api/configs", body);
                Log.i(TAG, "post lastPlay " + describe(c) + " #" + attempt + " -> " + r);
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "am start -n " + PKG + "/top.yogiczy.mytv.tv.PlayerActivity"});
                p.waitFor();
                sleep(900);
                // 校验 MyTV 是否真的认领了目标台：用 hash（台名可能被标准化）。
                // 冷启动竞态下没认领就重写一次，最多三次，仍失败则降级 deeplink。
                JSONObject cfg = new JSONObject(http("GET", "/api/configs", null));
                JSONObject lp = cfg.optJSONObject("iptvChannelLastPlay");
                if (lp != null && lp.optInt("hash", Integer.MIN_VALUE) == wantHash) {
                    Log.i(TAG, "postPlay adopted " + describe(c) + " at attempt " + attempt);
                    return true;
                }
                Log.w(TAG, "postPlay not adopted at attempt " + attempt + ", retry");
                sleep(700);
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "postPlay failed, fallback to deeplink", t);
            return false;
        }
    }

    private static boolean launchApp() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(PKG, "top.yogiczy.mytv.tv.MainActivity"));
            // 与 `am start -n <MainActivity>` 等价；不要加 REORDER_TO_FRONT，
            // 那种启动方式在 MyTV 已运行时只把 task 提前，不触发 onResume。
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appCtx.startActivity(i);
            lastLaunchMs = SystemClock.uptimeMillis();
            lastActiveMs = lastLaunchMs;
            userClosed = false;
            Log.i(TAG, "launch MainActivity (warmup)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "launch MainActivity failed", t);
            return false;
        }
    }

    /**
     * MyTV 是否正在运行（含 :player 子进程）。
     * Android 5.1 上 getRunningAppProcesses() 和普通 ps 都看不到别的 uid 的进程，
     * 所以优先用 root 执行 ps；root 不可用时退回本地状态 + HTTP 探活兜底。
     * 注意：deeplink 切台会让 MainActivity 销毁并停掉 10591，不能只靠 HTTP 判断。
     */
    private static boolean tvAlive() {
        // 小智明确关过电视后，直到下次打开/切台才认「开着」——
        // force-stop 未必立刻清掉所有进程，进程存在不等于用户还在看电视。
        if (userClosed) return false;
        if (appCtx != null && AppSettings.rootEnabled(appCtx)) {
            java.io.BufferedReader r = null;
            try {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"su", "-c", "ps | grep " + PKG});
                r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    // `ps | grep <pkg>` 里 grep 进程自己的命令行也含包名，必须排除
                    if (line.contains(PKG) && !line.contains("grep ")) {
                        r.close();
                        p.destroy();
                        return true;
                    }
                }
                r.close();
                p.destroy();
                return false;                       // root 下 ps 没看到 = 确实没开
            } catch (Throwable t) {
                Log.w(TAG, "tvAlive(root ps) failed", t);
            } finally {
                if (r != null) {
                    try { r.close(); } catch (Throwable ignored) {}
                }
            }
        }
        if (userClosed) return false;
        return httpAlive() || (SystemClock.uptimeMillis() - lastActiveMs < 180000L);
    }

    /**
     * 切台前置：把 MyTV 拉到前台。返回 true = 这次是冷启动，
     * 要等它自己「选择第一个频道」结束再发 deeplink（否则会被覆盖）。
     */
    private static boolean ensureAppReady() {
        boolean running = tvAlive();
        launchApp();
        if (running) return false;
        // 冷启动：HTTP 服务由 MainActivity.onResume 起，用它当「界面已就绪」信号
        for (int i = 0; i < 12; i++) {
            sleep(400);
            if (httpAlive()) break;
        }
        sleep(700);
        Log.i(TAG, "cold launch settled");
        return true;
    }

    /**
     * 直接补起 MyTV 的 HttpServerService。
     * 该服务 android:exported=false，普通应用/普通 am 起不了；设备 root 下
     * `su -c am startservice` 可以（实测 2026-11-06：起完 10591 立即可用，
     * 且不会打断正在播放的画面）。这比 force-stop 重启 App 温和得多。
     */
    private static boolean startHttpService() {
        if (appCtx == null || !AppSettings.rootEnabled(appCtx)) return false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "am startservice -n " + PKG
                            + "/top.yogiczy.mytv.tv.HttpServerService"});
            p.waitFor();
            Log.i(TAG, "start HttpServerService via root");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "start HttpServerService failed", t);
            return false;
        }
    }

    /**
     * 确保 MyTV 的 10591 可用。返回 true 表示这次是冷拉起（要给「首次启动选第一个
     * 频道」留时间，否则 deeplink 会被它覆盖）。
     *
     * 实测坑：MyTV 进程还在但服务被回收/没起来时，`am start MainActivity`
     * 只输出 "its current task has been brought to the front"，**不会**重新
     * 触发 onResume，服务依旧没有 —— 所以必须有第二级补救。
     */
    private static boolean ensureReady(boolean allowLaunch) {
        if (httpAlive()) return false;
        if (!allowLaunch) return false;
        launchApp();                                   // 1) 冷启动 / 把界面带到前台
        for (int i = 0; i < 6; i++) {                  // 冷启动 onResume 自起服务，最多等 2.4s
            sleep(400);
            if (httpAlive()) {
                Log.i(TAG, "http ready (cold launch) " + ((i + 1) * 400) + "ms");
                return true;
            }
        }
        if (!startHttpService()) {                     // 2) root 直接补起未导出的服务
            forceStop();                               // 3) 无 root：只能强杀后冷启动
            launchApp();
        }
        for (int i = 0; i < 14; i++) {
            sleep(400);
            if (httpAlive()) {
                Log.i(TAG, "http ready (service restart) " + ((i + 1) * 400) + "ms");
                return false;                          // 播放没被打断，不算冷启动
            }
        }
        Log.w(TAG, "http still down after warmup");
        return true;
    }

    private static boolean httpAlive() {
        try {
            http("GET", "/api/about", null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean forceStop() {
        if (appCtx == null) return false;
        try {
            if (AppSettings.rootEnabled(appCtx)) {
                Process p = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "am force-stop " + PKG});
                p.waitFor();
                Log.i(TAG, "force-stop MyTV (root)");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "force-stop failed", t);
        }
        return false;
    }

    // ---------------------------------------------------------------- 频道表

    /** 每次先查 idx（用户在电视上手换源也要跟上），idx 变化或表过期才重读大缓存。 */
    private static synchronized boolean ensureTable(boolean allowLaunch) {
        long now = SystemClock.uptimeMillis();
        boolean fresh = !channels.isEmpty() && (now - tableAt) < TABLE_TTL_MS;
        // 服务掉了但表还新（deeplink 切台会顺带停掉 10591）：直接用缓存，
        // 绝不为了读数据去补服务/重启 App 打断正在播放的画面。
        if (fresh && !httpAlive()) return true;
        ensureReady(allowLaunch);
        JSONObject cfg;
        try {
            cfg = new JSONObject(http("GET", "/api/configs", null));
        } catch (Throwable t) {
            Log.w(TAG, "configs failed: " + t);
            return !channels.isEmpty();     // 服务挂了就复用内存里的表
        }
        int idx = cfg.optInt("iptvSourceCurrentIdx", 0);
        boolean idxChanged = idx != sourceIdx;   // 用户在电视上手动换了源也要跟上
        if (!channels.isEmpty() && !idxChanged && (now - tableAt) < TABLE_TTL_MS) {
            return true;
        }
        JSONArray arr = cfg.optJSONObject("iptvSourceList") == null ? null
                : cfg.optJSONObject("iptvSourceList").optJSONArray("value");
        if (arr == null || idx < 0 || idx >= arr.length()) return !channels.isEmpty();
        JSONObject src = arr.optJSONObject(idx);
        if (src == null) return !channels.isEmpty();
        String file = CACHE_DIR + "/iptv_source_" + cacheHash(src) + ".json";
        String raw;
        try {
            raw = http("GET", "/api/file/content?path=" + file, null);
        } catch (Throwable t) {
            Log.w(TAG, "cache read failed " + file + " : " + t);
            return !channels.isEmpty();
        }
        List<Chan> list = parseChannels(raw);
        if (list.isEmpty()) return !channels.isEmpty();
        synchronized (channels) {
            channels.clear();
            channels.addAll(list);
        }
        sourceName = src.optString("name", "默认源");
        sourceIdx = idx;
        tableAt = now;
        // 游标校正：服务刚起来 / 换过源时按 MyTV 记录的在播频道对齐
        if (idxChanged || currentIndex < 0 || currentIndex >= list.size()) {
            currentIndex = indexOfLastPlay(list, cfg.optJSONObject("iptvChannelLastPlay"));
        }
        Log.i(TAG, "table built: src=" + sourceName + " idx=" + idx
                + " ch=" + list.size() + " file=" + file + " cur=" + currentIndex);
        return true;
    }

    private static int indexOfLastPlay(List<Chan> list, JSONObject lp) {
        if (lp == null) return 0;
        String name = lp.optString("name", "");
        String url = "";
        JSONArray lines = lp.optJSONObject("lineList") == null ? null
                : lp.optJSONObject("lineList").optJSONArray("value");
        if (lines != null && lines.length() > 0) {
            JSONObject l = lines.optJSONObject(0);
            if (l != null) url = l.optString("url", "");
        }
        String nn = norm(name);
        for (int i = 0; i < list.size(); i++) {
            Chan c = list.get(i);
            if (nn.length() > 0 && (nn.equals(norm(c.name)) || nn.equals(norm(c.standardName)))) return i;
            if (url.length() > 0 && url.equals(c.url)) return i;
        }
        return 0;
    }

    private static List<Chan> parseChannels(String raw) {
        List<Chan> out = new ArrayList<Chan>();
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray groups = root.optJSONArray("value");
            if (groups == null) return out;
            for (int g = 0; g < groups.length(); g++) {
                JSONObject grp = groups.optJSONObject(g);
                if (grp == null || grp.optJSONObject("channelList") == null) continue;
                JSONArray ch = grp.optJSONObject("channelList").optJSONArray("value");
                if (ch == null) continue;
                for (int i = 0; i < ch.length(); i++) {
                    JSONObject o = ch.optJSONObject(i);
                    if (o == null) continue;
                    // 台号必须严格按源顺序排：即使某条频道暂时没有播放线路也要占住位置，
                    // 否则后面所有台号都会前移，和电视里自动排出来的序号对不上。
                    Chan c = new Chan();
                    c.no = out.size() + 1;
                    c.name = o.optString("name", "频道" + c.no);
                    c.standardName = o.optString("standardName", c.name);
                    JSONObject ll = o.optJSONObject("lineList");
                    JSONArray lines = ll == null ? null : ll.optJSONArray("value");
                    JSONObject l = (lines == null || lines.length() == 0)
                            ? null : lines.optJSONObject(0);
                    c.url = l == null ? "" : l.optString("url", "");
                    c.ua = optStr(l, "httpUserAgent");
                    c.referer = optStr(l, "httpReferrer");
                    c.origin = optStr(l, "httpOrigin");
                    c.cookie = optStr(l, "httpCookie");
                    c.rawJson = o.toString();      // 保留完整对象（含 hash/index/lineList）
                    out.add(c);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse cache failed", t);
        }
        return out;
    }

    /** IptvSource.hashCode()：与 MyTV 的 Kotlin data class 生成规则一致（10 字段，int 溢出回绕）。
     *  实测 4 个源（含远程源）100% 命中缓存文件名；注意不能用 Math.abs 处理负数。 */
    private static String cacheHash(JSONObject o) {
        int h = ((strHash(optStr(o, "name")) * 31 + strHash(optStr(o, "url"))) * 31
                + o.optInt("sourceType", 0)) * 31;
        String[] mid = {"mac", "userName", "password", "format", "transformJs", "httpUserAgent"};
        for (String k : mid) h = (h + strHash(optStr(o, k))) * 31;
        h = h + strHash(optStr(o, "httpProxy"));
        return Long.toHexString(((long) h) & 0xFFFFFFFFL);
    }

    private static int strHash(String s) {
        return s == null ? 0 : s.hashCode();
    }

    private static String optStr(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        Object v = o.opt(k);
        return v == null ? null : String.valueOf(v);
    }

    // ---------------------------------------------------------------- 台名 / 台号匹配

    /** 台号：纯数字 / 中文数字 / 「第N台」「N频道」「N号」等。含字母的（CCTV5）不当台号。 */
    private static int parseChannelNo(String q) {
        String s = q.trim().replaceAll("\\s+", "").replace("第", "")
                .replace("个台", "").replace("台", "").replace("频道", "")
                .replace("号", "").replace("套", "");
        if (s.length() == 0) return -1;
        if (s.matches("[0-9]+")) return toInt(s);
        // 「切换到 5」「看 29」这类前缀
        for (String p : new String[]{"切换到", "切到", "转到", "换到", "播放", "看", "打开"}) {
            if (s.startsWith(p)) {
                String rest = s.substring(p.length()).replace("个源", "").replace("个", "");
                if (rest.matches("[0-9]+")) return toInt(rest);
            }
        }
        // 「第2个源」「2号源」：中间夹字也认，只要只剩一个数字
        String digits = s.replaceAll("[^0-9]", "");
        String rest = s.replaceAll("[0-9]", "");
        if (digits.length() > 0 && rest.length() <= 2
                && (rest.contains("个") || rest.contains("源") || rest.isEmpty())) {
            return toInt(digits);
        }
        if (isChineseNumber(s)) return chineseNumber(s);
        return -1;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isChineseNumber(String s) {
        for (int i = 0; i < s.length(); i++) {
            if ("零一二三四五六七八九十两".indexOf(s.charAt(i)) < 0) return false;
        }
        return s.length() > 0;
    }

    private static int chineseNumber(String s) {
        int total = 0, section = 0, num = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = "零一二三四五六七八九".indexOf(c);
            if (d >= 0) { num = d; continue; }
            if (c == '两') { num = 2; continue; }
            if (c == '十') {
                section += (num == 0 ? 1 : num) * 10;
                num = 0;
            } else return -1;
        }
        total = section + num;
        return total > 0 ? total : -1;
    }

    /** 归一化：全角转半角、去空白与分隔符、小写、中文数字台号映射、央视别名。 */
    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);   // 全角 → 半角
            if (Character.isWhitespace(c) || c == 0x3000) continue;
            if (c == '-' || c == '_' || c == '.' || c == '·' || c == ' ') continue;
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            b.append(c);
        }
        return b.toString();
    }

    /** 额外别名：中央五套 / 央视一套 / CCTV5体育 → cctv5 */
    private static List<String> aliases(String q) {
        List<String> out = new ArrayList<String>();
        String n = norm(q);
        if (n.length() > 0) out.add(n);
        // 口语前缀：「看CCTV5」「播放浙江卫视」「切换到中央一套」「打开电视看…」
        String[] verbs = {"打开电视看", "打开电视", "切换到", "切到", "转到", "换到",
                "调到", "我想看", "我要看", "播放", "播", "看", "电视", "来个"};
        for (String v : verbs) {
            if (n.startsWith(v) && n.length() > v.length()) {
                String stripped = n.substring(v.length());
                if (!out.contains(stripped)) out.add(stripped);
                java.util.regex.Matcher mm = java.util.regex.Pattern
                        .compile("^(cctv[0-9]+)").matcher(stripped);
                if (mm.find() && !out.contains(mm.group(1))) out.add(mm.group(1));
            }
        }
        String[] heads = {"中央", "央视"};
        List<String> base = new ArrayList<String>(out);
        for (String cand : base) {
            for (String h : heads) {
                if (cand.startsWith(h)) {
                    String rest = cand.substring(h.length())
                            .replace("综合", "").replace("体育", "").replace("财经", "")
                            .replace("频道", "").replace("台", "").replace("套", "");
                    int v = rest.matches("[0-9]+") ? toInt(rest)
                            : (isChineseNumber(rest) ? chineseNumber(rest) : -1);
                    if (v > 0 && !out.contains("cctv" + v)) out.add("cctv" + v);
                }
            }
            // CCTV-5 体育 / cctv1综合：截掉中文后缀，保留 cctvN
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(cctv[0-9]+)").matcher(cand);
            if (m.find() && !out.contains(m.group(1))) out.add(m.group(1));
        }
        return out;
    }

    /** 精确 → 前缀 → 包含，三级匹配；精确命中立即返回，避免 CCTV-1 误配 CCTV-10。 */
    private static Chan find(String q) {
        List<String> keys = aliases(q);
        if (keys.isEmpty()) return null;
        synchronized (channels) {
            // 1) 精确
            for (String k : keys) {
                for (Chan c : channels) {
                    if (k.equals(norm(c.name)) || k.equals(norm(c.standardName))) return c;
                }
            }
            // 2) 频道名以关键词开头（cctv5 → cctv5体育 / CCTV-5）
            Chan best = null;
            int bestLen = Integer.MAX_VALUE;
            for (String k : keys) {
                if (k.length() < 2) continue;
                for (Chan c : channels) {
                    String nn = norm(c.standardName);
                    if (nn.startsWith(k) && nn.length() < bestLen) { best = c; bestLen = nn.length(); }
                    String cn = norm(c.name);
                    if (cn.startsWith(k) && cn.length() < bestLen) { best = c; bestLen = cn.length(); }
                }
            }
            if (best != null) return best;
            // 3) 包含（「浙江」→ 浙江卫视），同样取最短名字
            best = null;
            bestLen = Integer.MAX_VALUE;
            for (String k : keys) {
                if (k.length() < 2) continue;
                for (Chan c : channels) {
                    String nn = norm(c.name);
                    if (nn.contains(k) && nn.length() < bestLen) { best = c; bestLen = nn.length(); }
                }
            }
            return best;
        }
    }

    // ---------------------------------------------------------------- HTTP / 工具

    private static String http(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path)
                .openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(path.equals("/api/about") ? 800 : 2000);
        c.setReadTimeout(path.equals("/api/about") ? 800 : 4000);
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " " + path);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && s.length() > 0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static final class Chan {
        int no;                 // 台号 = 按源顺序自动排的 1-based 序号
        String name;
        String standardName;
        String url;
        String ua;
        String referer;
        String origin;
        String cookie;
        String rawJson;         // 缓存里的完整频道对象，供 POST 换台总线复用
    }
}
