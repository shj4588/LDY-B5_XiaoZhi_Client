package cn.workbuddy.ldyb5app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ldyb5 v0.3.0：设置中心存储层（SharedPreferences，与 OtaClient 同用 "ldyb5app"）。
 *
 * 所有开关都有保守默认值；改了「唤醒词」「服务器地址」需要重启服务才生效
 * （KWS 引擎与 OTA 在服务启动时初始化），其余开关即时生效。
 */
public final class AppSettings {

    public static final String PREF = "ldyb5app";

    /** 可选唤醒词（sherpa keywords 预设文件见 assets/kws/keywords_*.txt） */
    public static final String[] WAKE_WORDS = {
            "小智小智", "小讯小讯", "你好小智", "天猫精灵"
    };

    private static final String KEY_CUE = "cue_on";               // 提示音
    private static final String KEY_YIELD = "yield_music";        // 音乐为小智让路
    private static final String KEY_KWS_INTERRUPT = "kws_interrupt";   // v0.3.21：唤醒词打断（KWS 播放期常驻，命中即强制打断）
    private static final String KEY_KWS_SENS = "kws_sens";             // v0.3.52：KWS 灵敏度档位 0..4

    /**
     * v0.3.52：设置页「唤醒灵敏度」5 档。索引越大越灵敏（越容易喊醒，也越容易误唤醒）。
     * threshold 越小越容易命中；score 是 sherpa-onnx 关键词加权分（离线复算证实是
     * 灵敏度第一杠杆，见 KwsEngine.score 注释）。档 2 = 出厂默认组合 thr=0.05/score=6.0，
     * 即离线复算「召回 4 段、误报 0」的最优组合。
     */
    public static final String[] KWS_SENS_NAMES = {
            "迟钝（抗误唤醒）", "偏低", "标准", "灵敏", "很灵敏（易误唤醒）"
    };
    public static final float[] KWS_SENS_THR = {0.25f, 0.15f, 0.05f, 0.03f, 0.02f};
    public static final float[] KWS_SENS_SCORE = {2.0f, 4.0f, 6.0f, 8.0f, 10.0f};
    public static final int KWS_SENS_DEFAULT = 2;
    private static final String KEY_WAKE_SCREEN = "wake_screen";  // 唤醒亮屏
    private static final String KEY_VOICE_ON = "voice_on";        // 小智总开关
    private static final String KEY_IDLE60 = "idle60";            // 60s 空闲断开
    private static final String KEY_WAKE_WORD = "wake_word";      // 自定义唤醒词
    private static final String KEY_OTA_URL = "ota_url_custom";   // 自定义服务器地址
    private static final String KEY_TOKEN_CUSTOM = "token_custom"; // 手动填的连接 token
    private static final String KEY_SRV_MODE = "srv_mode";        // 服务器选择
    private static final String KEY_WX_CITY = "wx_city";          // 天气手动位置（空=IP 定位）
    private static final String KEY_ROOT_ON = "root_on";          // root 功能开关
    // v0.3.6：时钟区显示自定义
    private static final String KEY_TIME_SIZE = "clock_time_size";   // 时间字号 sp（0=自动）
    private static final String KEY_DATE_SIZE = "clock_date_size";   // 日期/天气字号 sp
    private static final String KEY_TIME_COLOR = "clock_time_color"; // 时间颜色 #RRGGBB
    private static final String KEY_DATE_COLOR = "clock_date_color"; // 日期/天气颜色
    private static final String KEY_CLOCK_BG = "clock_bg_color";     // 时钟区背景色
    private static final String KEY_BODY_BG = "body_bg_color";       // v0.3.9：主界面底色
    private static final String KEY_ISLAND_ON = "island_on";         // v0.3.9：灵动岛开关
    private static final String KEY_ISLAND_SIZE = "island_size";     // v0.3.10：灵动岛大小 dp
    private static final String KEY_ISLAND_ALPHA = "island_alpha";   // v1.3.51：灵动岛透明度
    private static final String KEY_ISLAND_X = "island_x";           // v1.3.51：悬浮窗位置 px（-1=默认顶部居中）
    private static final String KEY_ISLAND_Y = "island_y";
    private static final String KEY_STATS_ON = "stats_on";           // 设备状态行开关
    private static final String KEY_SRV_LISTEN = "srv_listen";       // v1.3.60：服务器接管拾音（唤醒词不上报）

    /** 服务器两档：官方 / 自定义（KEY_OTA_URL），默认官方 */
    public static final int SRV_OFFICIAL = 0;
    public static final int SRV_CUSTOM = 1;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------- 读 ----------------

    public static boolean cueOn(Context c) {
        return sp(c).getBoolean(KEY_CUE, true);
    }

    public static boolean yieldMusic(Context c) {
        return sp(c).getBoolean(KEY_YIELD, true);
    }

    /**
     * v0.3.21：唤醒词打断。开启 = KWS 在 TTS 播放期不暂停（pauseInTts=false），
     * 命中「小智小智」即由 handleWakeup 的 TTS_PLAYING 分支强制停 TTS + 干净重听。
     * 默认开：本地 KWS 完成，不依赖服务端 AEC（R1 硬件 AEC 不完美、公网 hello 又
     * 拒 aec 字段），比 realtime barge-in 可靠。
     */
    public static boolean kwsInterrupt(Context c) {
        return sp(c).getBoolean(KEY_KWS_INTERRUPT, true);
    }

    /** v0.3.52：KWS 灵敏度档位（0..4，默认 2=标准） */
    public static int kwsSensitivity(Context c) {
        int v = sp(c).getInt(KEY_KWS_SENS, KWS_SENS_DEFAULT);
        return Math.max(0, Math.min(KWS_SENS_NAMES.length - 1, v));
    }

    public static void setKwsSensitivity(Context c, int level) {
        int v = Math.max(0, Math.min(KWS_SENS_NAMES.length - 1, level));
        sp(c).edit().putInt(KEY_KWS_SENS, v).apply();
    }

    /**
     * v0.3.52：把设置页档位应用到 KwsEngine 的静态参数。
     * 注意 threshold / score 只在 KwsEngine.init() 构造 KeywordSpotter 时读取，
     * 因此运行中调用只对「下一次引擎初始化」生效 —— 设置页会提示/触发重启服务。
     * 调用时机必须在 files/kwsthr<N> / kwsscore<N> 解析之前，好让调试 flag 仍能覆盖。
     */
    public static void applyKwsSensitivity(Context c) {
        int v = kwsSensitivity(c);
        KwsEngine.setThreshold(KWS_SENS_THR[v]);
        KwsEngine.setScore(KWS_SENS_SCORE[v]);
    }

    public static boolean wakeScreen(Context c) {
        return sp(c).getBoolean(KEY_WAKE_SCREEN, true);
    }

    public static boolean voiceEnabled(Context c) {
        return sp(c).getBoolean(KEY_VOICE_ON, true);
    }

    public static boolean idleDisconnect60(Context c) {
        return sp(c).getBoolean(KEY_IDLE60, true);
    }

    public static String wakeWord(Context c) {
        return sp(c).getString(KEY_WAKE_WORD, WAKE_WORDS[0]);
    }

    /** 服务器选择（SRV_OFFICIAL / SRV_CUSTOM），默认官方 */
    public static int srvMode(Context c) {
        return sp(c).getInt(KEY_SRV_MODE, SRV_OFFICIAL);
    }

    public static String otaUrl(Context c) {
        return sp(c).getString(KEY_OTA_URL, "");
    }

    /** 手动填的连接 token；空串 = 用 OTA 自动下发 */
    public static String tokenCustom(Context c) {
        return sp(c).getString(KEY_TOKEN_CUSTOM, "");
    }

    /** 天气手动位置（城市名）；空串 = 自动 IP 定位 */
    public static String wxCity(Context c) {
        return sp(c).getString(KEY_WX_CITY, "");
    }

    public static void setWxCity(Context c, String city) {
        sp(c).edit().putString(KEY_WX_CITY,
                city == null ? "" : city.trim()).apply();
    }

    /** root 功能开关（root 读酷我收藏库等），默认开 */
    public static boolean rootEnabled(Context c) {
        return sp(c).getBoolean(KEY_ROOT_ON, true);
    }

    // v0.3.6：时钟区显示自定义 ----------------

    /** 时间字号 sp；0 = 自动（约 1/4 屏宽）。v0.3.9：钳制 16~160（160≈1/2 屏宽） */
    public static float timeSize(Context c) {
        float v = sp(c).getFloat(KEY_TIME_SIZE, 0f);
        if (v <= 0f) {
            return 0f;
        }
        return Math.max(16f, Math.min(160f, v));
    }

    /** 日期/天气字号 sp。v0.3.9：钳制 10~36 */
    public static float dateSize(Context c) {
        float v = sp(c).getFloat(KEY_DATE_SIZE, 18f);
        if (v <= 0f) {
            return 18f;
        }
        return Math.max(10f, Math.min(36f, v));
    }

    public static String timeColor(Context c) {
        return sp(c).getString(KEY_TIME_COLOR, "");
    }

    public static String dateColor(Context c) {
        return sp(c).getString(KEY_DATE_COLOR, "");
    }

    /** 时钟区背景色；空 = 透明 */
    public static String clockBg(Context c) {
        return sp(c).getString(KEY_CLOCK_BG, "");
    }

    /** v0.3.9：主界面底色；空 = 默认青色 */
    public static String bodyBg(Context c) {
        return sp(c).getString(KEY_BODY_BG, "");
    }

    public static void setBodyBg(Context c, String v) {
        sp(c).edit().putString(KEY_BODY_BG, v == null ? "" : v.trim()).apply();
    }

    /** v0.3.9：灵动岛（小智机器人浮条）开关，默认开 */
    public static boolean islandOn(Context c) {
        return sp(c).getBoolean(KEY_ISLAND_ON, true);
    }

    public static void setIslandOn(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ISLAND_ON, v).apply();
    }

    /** v0.3.10：灵动岛图标大小（dp），默认 96，钳制 48~192 */
    public static int islandSize(Context c) {
        return Math.max(48, Math.min(192, sp(c).getInt(KEY_ISLAND_SIZE, 96)));
    }

    public static void setIslandSize(Context c, int v) {
        sp(c).edit().putInt(KEY_ISLAND_SIZE, Math.max(48, Math.min(192, v))).apply();
    }

    /** v1.3.51：灵动岛图标透明度，默认 1.0（不透明），钳制 0.2~1.0 */
    public static float islandAlpha(Context c) {
        return Math.max(0.2f, Math.min(1f, sp(c).getFloat(KEY_ISLAND_ALPHA, 1f)));
    }

    public static void setIslandAlpha(Context c, float v) {
        sp(c).edit().putFloat(KEY_ISLAND_ALPHA,
                Math.max(0.2f, Math.min(1f, v))).apply();
    }

    /** v1.3.51：灵动岛悬浮窗位置（px，窗口左上角；-1 = 未设置 = 默认顶部居中） */
    public static int islandX(Context c) {
        return sp(c).getInt(KEY_ISLAND_X, -1);
    }

    public static int islandY(Context c) {
        return sp(c).getInt(KEY_ISLAND_Y, -1);
    }

    public static void setIslandPos(Context c, int x, int y) {
        sp(c).edit().putInt(KEY_ISLAND_X, x).putInt(KEY_ISLAND_Y, y).apply();
    }

    /** 清除自定义位置 → 恢复默认顶部居中 */
    public static void clearIslandPos(Context c) {
        sp(c).edit().remove(KEY_ISLAND_X).remove(KEY_ISLAND_Y).apply();
    }

    public static void setTimeSize(Context c, float v) {
        sp(c).edit().putFloat(KEY_TIME_SIZE, v).apply();
    }

    public static void setDateSize(Context c, float v) {
        sp(c).edit().putFloat(KEY_DATE_SIZE, v).apply();
    }

    public static void setTimeColor(Context c, String v) {
        sp(c).edit().putString(KEY_TIME_COLOR, v == null ? "" : v.trim()).apply();
    }

    public static void setDateColor(Context c, String v) {
        sp(c).edit().putString(KEY_DATE_COLOR, v == null ? "" : v.trim()).apply();
    }

    public static void setClockBg(Context c, String v) {
        sp(c).edit().putString(KEY_CLOCK_BG, v == null ? "" : v.trim()).apply();
    }

    /** 设备状态行（CPU/内存/温度）显示开关，默认开 */
    public static boolean statsOn(Context c) {
        return sp(c).getBoolean(KEY_STATS_ON, true);
    }

    public static void setStatsOn(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_STATS_ON, v).apply();
    }

    /**
     * v1.3.60：服务器接管拾音（唤醒词本地识别、语音不上报）。
     * 开启 = KWS 命中后只向服务端发 listen/detect 唤醒事件（不含唤醒词语音），
     * 并压住上行约 2 秒（等服务端脱离 just_woken_up 的耳聋窗口）后再开始上送指令；
     * 代价是唤醒后需稍作停顿再开口。关闭 = 沿用原行为：唤醒词+指令一起即时上送。
     * 即时生效（每次唤醒时读取），无需重启服务。
     */
    public static boolean serverListen(Context c) {
        return sp(c).getBoolean(KEY_SRV_LISTEN, false);
    }

    public static void setServerListen(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SRV_LISTEN, v).apply();
    }

    public static void setRootEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ROOT_ON, v).apply();
    }

    // ---------------- 写 ----------------

    public static void setCueOn(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_CUE, v).apply();
    }

    public static void setYieldMusic(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_YIELD, v).apply();
    }

    public static void setKwsInterrupt(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_KWS_INTERRUPT, v).apply();
    }

    public static void setWakeScreen(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_WAKE_SCREEN, v).apply();
    }

    public static void setVoiceEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_VOICE_ON, v).apply();
        // 即时生效：KWS 停喂（省掉那一个核），活动会话收掉
        KwsEngine.disabled = !v;
        SessionController sc = SessionController.I;
        if (sc != null) {
            sc.applyVoiceEnabled(v);
        }
    }

    public static void setIdleDisconnect60(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_IDLE60, v).apply();
    }

    /** @return 切换后的唤醒词 */
    public static String cycleWakeWord(Context c) {
        String cur = wakeWord(c);
        String next = WAKE_WORDS[0];
        for (int i = 0; i < WAKE_WORDS.length; i++) {
            if (WAKE_WORDS[i].equals(cur)) {
                next = WAKE_WORDS[(i + 1) % WAKE_WORDS.length];
                break;
            }
        }
        sp(c).edit().putString(KEY_WAKE_WORD, next).apply();
        return next;
    }

    /** v0.3.2：唤醒词支持任意输入（空 = 回默认「小智小智」） */
    public static void setWakeWord(Context c, String w) {
        String v = w == null ? "" : w.trim();
        if (v.length() == 0) {
            v = WAKE_WORDS[0];
        }
        sp(c).edit().putString(KEY_WAKE_WORD, v).apply();
    }

    public static void setSrvMode(Context c, int m) {
        sp(c).edit().putInt(KEY_SRV_MODE, m).apply();
    }

    public static void setOtaUrl(Context c, String url) {
        sp(c).edit().putString(KEY_OTA_URL,
                url == null ? "" : url.trim()).apply();
    }

    public static void setTokenCustom(Context c, String token) {
        sp(c).edit().putString(KEY_TOKEN_CUSTOM,
                token == null ? "" : token.trim()).apply();
    }

    /** 唤醒词对应的 keywords 资源文件（assets 相对路径） */
    public static String keywordsAsset(Context c) {
        String w = wakeWord(c);
        if ("小讯小讯".equals(w)) {
            return "kws/keywords_xiaoxun.txt";
        }
        if ("你好小智".equals(w)) {
            return "kws/keywords_nihao.txt";
        }
        if ("天猫精灵".equals(w)) {
            return "kws/keywords_tianmao.txt";
        }
        return "kws/keywords_xiaozhi.txt";
    }
}
