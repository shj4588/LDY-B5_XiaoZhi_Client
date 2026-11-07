package cn.workbuddy.ldyb5app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

/**
 * v0.2.5：酷我音乐车机版（cn.kuwo.kwmusiccar）语音接口桥 —— 全接口版。
 *
 * 协议逆向自 APK 的 cn.kuwo.kwmusiccar.KwCarPlay / KwMediaReceiver /
 * CarPlayBroadcastReceiver，manifest check_key=false → 无需 kuwo_key 鉴权
 * （但 handleCarPlay 分支要求 extras 非空，统一补 kuwo_key 占位）。
 *
 * 出站（我们→酷我）：
 *   SEARCH_MUSIC      extras name/singer/album/music_lrc/music_theme/source
 *   PLAY_MUSIC        extras rid(long)/name/singer/artistid/mvQuality/hasMv
 *   vip.PLAY_ALL_MUSIC extras music_all_list_key(JSON)/music_index_key/replace_play_list
 *   PLAY_ALBUM        data URI: kwplay://quku?qukuItemType=album|Songlist|radio|list&id=&name=&digest=
 *   RANDOM_PLAY_MUSIC 随机播放
 *   OPEN_FAVORITE_MUSIC 打开"我喜欢"列表并播放
 *   MEDIA_BUTTON      EXTRA=MEDIA_PLAY/PAUSE(空操作)/PLAY_PAUSE/NEXT/PRE/MUTE/
 *                     MEDIA_ONE(单曲循环)/MEDIA_ORDER(顺序)/MEDIA_CIRCLE(列表循环)/MEDIA_RANDOM(随机)
 *                     + Hardware_MediaButton=true（免鉴权分支）
 *   SET_FAVORITE_MUSIC 收藏当前歌
 *   COLLECT_MUSIC      COLLECT(bool) 收藏/取消收藏
 *   EXITAPP            退出酷我
 *   KWPLAY_SETTING     setting_strKey/setting_value（如 music_quality_when_play 0标准1高品2无损）
 *
 * 入站（酷我→我们）：
 *   PLAYER_STATUS      PLAYERSTATUS=ordinal(0INIT 1PLAYING 2BUFFERING 3PAUSE 4STOP)
 *                      + play_music_name/artist/album
 *   PLAY_END           ENDTYPE
 *   ACTION_PLAYING_PAY_SONG  FLAG_PAY_SONG_TYPE + play_music_*（VIP 付费拦截）
 *   PLAY_CLIENT_MUSICS play_client_musics_search_json（搜索结果 JSON）
 *
 * 小智经 MCP（self_kuwo_*）调用；与本地电台 MusicPlayer 互斥（播一边停另一边）。
 */
public class KuwoController {

    private static final String TAG = "KuwoController";
    private static final String PKG = "cn.kuwo.kwmusiccar";
    private static final String ACT = "cn.kuwo.kwmusicauto.action.";
    private static final String A_SEARCH = ACT + "SEARCH_MUSIC";
    private static final String A_MEDIA = ACT + "MEDIA_BUTTON";
    private static final String A_STARTAPP = ACT + "STARTAPP";
    private static final String A_STATUS = ACT + "PLAYER_STATUS";
    private static final String A_PLAY_END = ACT + "PLAY_END";
    private static final String A_PAY_SONG = ACT + "ACTION_PLAYING_PAY_SONG";
    private static final String A_CAND = ACT + "PLAY_CLIENT_MUSICS";
    private static final String A_FAV = ACT + "SET_FAVORITE_MUSIC";
    private static final String A_COLLECT = ACT + "COLLECT_MUSIC";
    private static final String A_EXIT = ACT + "EXITAPP";
    private static final String A_SETTING = ACT + "KWPLAY_SETTING";
    private static final String A_RANDOM = ACT + "RANDOM_PLAY_MUSIC";
    private static final String A_OPEN_FAV = ACT + "OPEN_FAVORITE_MUSIC";
    private static final String A_PLAY_MUSIC = ACT + "PLAY_MUSIC";
    private static final String A_PLAY_ALL = ACT + "vip.PLAY_ALL_MUSIC";
    private static final String A_PLAY_ALBUM = ACT + "PLAY_ALBUM";

    /** 酷我上报的当前歌曲（null=未知/没播） */
    public static volatile String title = null;
    public static volatile String artist = null;
    public static volatile String album = null;
    /**
     * 酷我上报的 PLAYERSTATUS。
     *
     * v0.3.26：按**设备实测**修正语义（旧的注释「2=BUFFERING」是错的）：
     *   1 = PLAYING（真在出声）
     *   2 = 播放中的过渡/进度态（**实际仍在播放**，且在曲内会持续很久，
     *       实测单首曲目里 2 连续出现 10+ 秒；伴随 PlayStateNotify
     *       notifyStart status=PLAYING）
     *   3 = PAUSE（真暂停，伴随 notifyPause status=PAUSE）
     *   0 = 切歌/停止过渡  4 = 已停止  -1 = 未知
     * 故 isPlaying() 认 1 和 2，只有 3 才是暂停。
     */
    public static volatile int status = -1;
    /** 当前歌被 VIP 付费拦截（true=需要会员/单曲付费） */
    public static volatile boolean vipLocked = false;
    /** 最近一次搜索的候选结果 JSON（酷我回传），null=无 */
    public static volatile String lastCandidates = null;

    /** v0.3.12：互斥暂停守卫截止时刻（uptimeMillis）—— 我们为电台开播而主动
     *  pause 酷我后，这么久内酷我若还报 PLAYING（暂停指令在途），不能据此停电台 */
    private static volatile long pauseGuardUntil = 0L;

    /** v0.3.12：播类指令验证窗口（ms）—— 酷我搜索+起播实测 ~1.5s，留足余量 */
    private static final long PLAY_VERIFY_MS = 6000;
    /** v0.3.12：旧「发后验证+失败重试」用的落地时间（ms），现流程已不用但仍保留常量 */
    private static final long PLAY_RETRY_SETTLE_MS = 2500;
    /** v0.3.12：互斥暂停守卫窗口（ms） */
    private static final long PAUSE_GUARD_MS = 3000;

    /**
     * v0.3.42：冷启动拉起酷我后，延迟这么久发一次暂停，静默掉酷我必然自播的「上次曲目」。
     * 取值需大于酷我自播的落地时间（实测约数百毫秒~1s），确保暂停能命中。
     */
    private static final long COLD_PAUSE_MS = 1000;
    /**
     * v0.3.42：冷启动后，真正播放指令延迟这么久再发。必须 > COLD_PAUSE_MS，
     * 保证「先暂停掉自播、再发正确指令」，指令不会被自己刚发的暂停误杀。
     */
    private static final long COLD_PLAY_DELAY_MS = 1100;

    /**
     * v0.3.27：整单替换用的「已洗好列表」缓存（JSON 字符串）。
     *
     * **为什么必须缓存**（用户实测「随机播放第一首播几秒就跳下一首」）：
     * 旧实现 `randomPlay(verified=true)` 在验证失败时调 `randomPlayViaDb()`
     * **重新洗牌 + 再整单替换一次** → 正在播（或刚起播）的第一首被新列表顶掉，
     * 换成新洗牌的首曲 —— 用户听起来就是「第一首播几秒就跳下一首」。
     * 设备日志实证（13:47:55 / 13:54:52 两次）：
     *   55.854 洗牌 → 56.106 发整单替换 → 02.109 NOT verified -> retry
     *   → 02.974 **再洗牌** → 03.371 **第二次整单替换** → 03.625 才真的起播
     * 现在：验证失败只**重发同一份列表**（resendLastPlayAll），绝不重新洗牌。
     */
    private static volatile String lastPlayAllJson = null;

    /** 应用上下文（init 后可用；XiaoZhiWs 等无 Context 的类经此取系统服务） */
    public static Context appCtx = null;

    /**
     * v0.3.32：酷我是否已启动（进程在跑）。这是一个**极简布尔**——
     * 用户 2026-10-26 明确要求「只看酷我有没有启动，没启动就拉起，启动了就直接用
     * 普通接口控制，不用看实际播放状态」。所以我们**不再维护播放/暂停等真实状态**，
     * 只跟踪「酷我起没起」：
     *   · 我们主动 launchNoAuto()/runWhenReady() 拉起 → 置 true；
     *   · 收到任意酷我广播（证明它确实在跑）→ 置 true；
     *   · exitApp() 或本服务冷启动 → 置 false。
     * 控制指令（playPause / next / 搜歌 / 整单替换…）一律直接发，至于到底播没播、
     * 暂停没暂停，由用户自己看屏幕判断，小智不再替用户「智能判断」而误判。
     */
    private static volatile boolean kuwoStarted = false;
    /** v0.3.44：最近一次主动拉起酷我的时刻（uptimeMillis），用于「刚拉起、进程
     *  还没建起来」的短暂信任窗口，避免误判为未启动。 */
    private static volatile long lastLaunchMs = 0;

    /**
     * v0.3.39：歌手搜索模式标记。非 null 时表示刚刚发起了「搜歌手」广播，
     * A_CAND 回传候选后要按歌手过滤+去重再整单播放。普通搜歌不受影响。
     */
    private static volatile String pendingArtist = null;

    /** v0.3.32：外部（XiaoZhiWs）查询酷我是否已启动 */
    public static boolean isStarted() {
        return kuwoStarted;
    }

    /**
     * v0.3.44：真正探测酷我进程是否存活。
     *
     * 不再只信 {@link #kuwoStarted} 标志 —— 酷我被系统/用户杀掉后该标志仍可能为
     * true（只在 exitApp/服务冷启时清），会让「说在放实际没打开」的误报发生。
     *
     * 探测方式：遍历 /proc/<pid>/cmdline 找 cn.kuwo.kwmusiccar（跨 UID，不依赖
     * ActivityManager.getRunningAppProcesses 在 Android 10+ 只返回本进程的限制）。
     * /proc 在车机默认对所有应用可读，故 Android 8~13 都可靠。若 /proc 被隐藏，
     * 则退回到「最近 4s 内我们刚拉起过」的短期信任窗口。
     */
    public static boolean isReallyStarted() {
        if (procAlive()) {
            return true;
        }
        return (android.os.SystemClock.uptimeMillis() - lastLaunchMs) < 4000;
    }

    /** 遍历 /proc 进程目录，找 cmdline == cn.kuwo.kwmusiccar 或其后缀进程 */
    private static boolean procAlive() {
        if (appCtx == null) {
            return false;
        }
        try {
            java.io.File proc = new java.io.File("/proc");
            java.io.File[] pids = proc.listFiles();
            if (pids == null) {
                return false;
            }
            for (java.io.File p : pids) {
                if (!p.isDirectory()) {
                    continue;
                }
                String nm = p.getName();
                boolean digit = nm.length() > 0;
                for (int i = 0; i < nm.length(); i++) {
                    if (!Character.isDigit(nm.charAt(i))) {
                        digit = false;
                        break;
                    }
                }
                if (!digit) {
                    continue;
                }
                java.io.File cmd = new java.io.File(p, "cmdline");
                if (!cmd.exists()) {
                    continue;
                }
                byte[] buf = new byte[256];
                int n;
                try (java.io.FileInputStream fis = new java.io.FileInputStream(cmd)) {
                    n = fis.read(buf);
                }
                if (n <= 0) {
                    continue;
                }
                String s = new String(buf, 0, n, "UTF-8");
                int z = s.indexOf(0);
                if (z >= 0) {
                    s = s.substring(0, z);
                }
                if (s.equals(PKG) || s.startsWith(PKG + ":")) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "proc scan failed", t);
        }
        return false;
    }

    /** 服务启动时调用一次：注册酷我状态广播接收 */
    public static synchronized void init(Context ctx) {
        if (appCtx != null) {
            return;
        }
        appCtx = ctx.getApplicationContext();
        IntentFilter f = new IntentFilter();
        f.addAction(A_STATUS);
        f.addAction(A_PLAY_END);
        f.addAction(A_PAY_SONG);
        f.addAction(A_CAND);
        f.addAction(ACT + "PLAYER_MODE");
        try {
            appCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    // v0.3.32：收到任意酷我广播即证明进程在跑（用户手动打开也算启动）
                    kuwoStarted = true;
                    if (A_STATUS.equals(a)) {
                        status = i.getIntExtra("PLAYERSTATUS", -1);
                        title = i.getStringExtra("play_music_name");
                        artist = i.getStringExtra("play_music_artist");
                        album = i.getStringExtra("play_music_album");
                        if (status == 1) {
                            vipLocked = false;   // 正常开播了，清付费标记
                            // v0.3.12：酷我开播 → 停本地电台（互斥，任何入口生效：
                            // MCP 播音乐/随机/收藏、酷我自己的 UI、auto_play 续播）。
                            // 守卫窗口内忽略 —— 那是电台开播时我们主动 pause 酷我，
                            // 暂停指令在途，酷这可能还在报 PLAYING。
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now < pauseGuardUntil) {
                                Log.i(TAG, "kuwo PLAYING in pause-guard window, radio kept");
                            } else {
                                MusicPlayer rp = MusicPlayer.I;
                                if (rp != null) {
                                    rp.stop();
                                    SessionLog.i("kuwo playing -> radio stopped (mutex)");
                                }
                            }
                        }
                        Log.i(TAG, "status=" + status + " title=" + title
                                + " artist=" + artist);
                    } else if (A_PAY_SONG.equals(a)) {
                        vipLocked = true;
                        Log.i(TAG, "vip pay song: " + i.getStringExtra("play_music_name")
                                + " type=" + i.getIntExtra("FLAG_PAY_SONG_TYPE", -1));
                    } else if (A_CAND.equals(a)) {
                        lastCandidates = i.getStringExtra("play_client_musics_search_json");
                        Log.i(TAG, "candidates: "
                                + (lastCandidates == null ? "null" : lastCandidates.length() + " bytes"));
                        // v0.3.39：歌手搜索模式 → 收到候选即过滤去重并整单替换播放
                        if (pendingArtist != null) {
                            String artist = pendingArtist;
                            pendingArtist = null;
                            resolveArtistAndPlay(artist, lastCandidates);
                        }
                    } else if (A_PLAY_END.equals(a)) {
                        Log.i(TAG, "play end, type=" + i.getIntExtra("ENDTYPE", -1));
                    }
                    ScreenBus.bumpMusic();
                }
            }, f);
            Log.i(TAG, "receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "register failed", t);
        }
    }

    // ============================ 查询 ============================

    /**
     * 给 MCP self_kuwo_status 用的一句话描述。
     *
     * v0.3.32：不再判断酷我真实播放状态（用户要求「实际状态自己看屏幕判断」）。
     * 只把最近一次广播里拿到的曲目名/歌手回显出来；什么都不知道就说不知道。
     */
    public static String describe() {
        String who = (artist == null || artist.length() == 0) ? "" : (" - " + artist);
        if (vipLocked) {
            String t = (title == null || title.length() == 0) ? "当前歌曲" : title;
            return "酷我提示：「" + t + who + "」需要会员或付费才能完整播放，建议换一首";
        }
        if (title == null || title.length() == 0) {
            return "酷我当前没有已知播放曲目（状态以你看到的为准）";
        }
        return "酷我正在播放（以你看到的为准）：" + title + who;
    }

    /**
     * v0.3.32：供**内部音频互斥**（本地电台 / TTS）使用的最佳努力判断。
     * 仅看最近一次广播里的 status，不再做「存活探测误清 kuwoAlive」那套逻辑。
     * 注意：用户面的音乐控制指令（暂停/继续/状态播报）**不依赖**这两个方法，
     * 它们直接发指令 / 直接回话，状态由用户自己看屏幕判断。
     */
    public static boolean isPlaying() {
        return status == 1 || status == 2;
    }

    /** 同上：最近一次广播 status==3 即视为已暂停（仅供内部互斥用） */
    public static boolean isPaused() {
        return status == 3;
    }

    /** v0.3.12：互斥暂停守卫 —— 调用方（MusicPlayer 电台开播）准备 pause 酷我前调，
     *  之后 PAUSE_GUARD_MS 内酷我上报的 PLAYING 一律忽略（暂停指令在途）。 */
    public static void notePauseRequested() {
        pauseGuardUntil = android.os.SystemClock.uptimeMillis() + PAUSE_GUARD_MS;
    }

    // ============================ 控制 ============================

    /**
     * 搜歌并播放。name=歌名（或关键词），singer=歌手（可空）。
     * 车机逻辑：先匹配本地曲库，没有则联网搜索并自动播放第一个结果。
     * verified=true 时带「发后验证+失败重试」（v0.3.12）。
     */
    public static void searchAndPlay(String name, String singer) {
        searchAndPlay(name, singer, false);
    }

    public static void searchAndPlay(String name, String singer, boolean verified) {
        if (name == null) {
            name = "";
        }
        if (singer == null) {
            singer = "";
        }
        if (name.length() == 0 && singer.length() == 0) {
            return;
        }
        Intent i = new Intent(A_SEARCH);
        i.setPackage(PKG);
        if (name.length() > 0) {
            i.putExtra("name", name);
        }
        if (singer.length() > 0) {
            i.putExtra("singer", singer);
        }
        // handleCarPlay 要求 extras 非空：上面两个任一必非空
        // v0.3.42：播放类指令走 runWhenReady —— 暖态直接发（单播、零闪烁）；
        // 冷态先拉起酷我（酷我必自播「上次曲目」），等自播被静默暂停后再发真正指令，
        // 避免「先播一首、过几秒又跳下一首」的双播。
        runWhenReady(() -> send(i));
    }

    /**
     * v0.3.39：搜歌手并播放其独唱作品集（模拟「搜歌手→点进歌手页」的干净体验）。
     *
     * 通路：发 SEARCH_MUSIC 只填 singer（不填 name）→ 酷我回传候选列表
     * (A_CAND / play_client_musics_search_json)。该列表含同名多版本 + 合作曲，
     * 所以拿到后在这里：①剔除合作曲（singer 含 &）；②按歌名去重保留首条（通常原版）；
     * ③用 vip.PLAY_ALL_MUSIC 整单替换播放，覆盖酷我自动播放的首条合作曲。
     *
     * 注意：酷我 SEARCH_MUSIC 会「自动播放最匹配一首」，故首条合作曲会先响一声，
     * 我们收到 A_CAND 后立即整单覆盖。
     */
    public static void searchArtistAndPlay(String artist) {
        if (artist == null || artist.trim().length() == 0) {
            return;
        }
        artist = artist.trim();
        pendingArtist = artist;       // 等 A_CAND 回来再解析
        lastCandidates = null;
        Intent i = new Intent(A_SEARCH);
        i.setPackage(PKG);
        i.putExtra("singer", artist);  // 关键：只填歌手，不当歌名搜
        Log.i(TAG, "searchArtistAndPlay: singer=" + artist);
        // 冷态：拉起后先静默掉酷我自播，再发歌手搜索（SEARCH_MUSIC 会再自播一首，
        // 由 suppressJunkAfterSearch 在候选回来前静默掉），最后用 clean 列表整单替换。
        runWhenReady(() -> {
            send(i);
            suppressJunkAfterSearch();
        });
    }

    /**
     * v0.3.39：解析歌手搜索候选 → 过滤合作曲 + 按歌名去重 → 整单替换播放。
     * 候选 JSON 数组字段：rid/name/singer/album/artistid/mvQuality/hasMv。
     * 清洗规则：剔除 singer 含 & 的合唱曲；按 name 去重保留首条（通常原版）。
     */
    private static void resolveArtistAndPlay(String artist, String candidates) {
        if (candidates == null || candidates.trim().length() == 0) {
            Log.w(TAG, "resolveArtist: no candidates, leave auto-play");
            return;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray(candidates);
            org.json.JSONArray out = new org.json.JSONArray();
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            int droppedCollab = 0, droppedDup = 0;
            String a = artist.toLowerCase();
            for (int k = 0; k < arr.length(); k++) {
                org.json.JSONObject o = arr.getJSONObject(k);
                String singer = o.optString("singer", "");
                String name = o.optString("name", "");
                // 1) 剔除合作曲（歌手含 &）：含目标歌手但非独唱的都丢掉
                if (singer.contains("&")) {
                    droppedCollab++;
                    continue;
                }
                // 2) 只留确属该歌手独唱（歌手名命中，忽略大小写/空白）
                if (a.length() > 0 && !singer.toLowerCase().contains(a)) {
                    droppedCollab++;
                    continue;
                }
                // 3) 按歌名去重（保留首条=通常原版）
                if (seen.contains(name)) {
                    droppedDup++;
                    continue;
                }
                seen.add(name);
                org.json.JSONObject n = new org.json.JSONObject();
                n.put("rid", o.optLong("rid", 0));
                n.put("name", name);
                n.put("singer", singer);
                n.put("duration", 0);
                n.put("album", o.optString("album", ""));
                n.put("artistid", 0);
                n.put("mvQuality", o.optString("mvQuality", ""));
                n.put("hasMv", o.optBoolean("hasMv", false));
                out.put(n);
            }
            Log.i(TAG, "resolveArtist: kept=" + out.length()
                    + " droppedCollab=" + droppedCollab + " droppedDup=" + droppedDup);
            if (out.length() == 0) {
                Log.w(TAG, "resolveArtist: all filtered, leave auto-play");
                return;
            }
            playAll(out.toString(), 0);
        } catch (Throwable t) {
            Log.w(TAG, "resolveArtist failed", t);
        }
    }

    /** 歌词搜歌：给一句歌词，酷我联网搜对应歌曲并自动开播 */
    public static void searchByLyric(String lrc) {
        searchByLyric(lrc, false);
    }

    public static void searchByLyric(String lrc, boolean verified) {
        if (lrc == null || lrc.trim().length() == 0) {
            return;
        }
        Intent i = new Intent(A_SEARCH);
        i.setPackage(PKG);
        i.putExtra("music_lrc", lrc.trim());
        // v0.3.42：播放类指令走 runWhenReady（暖态直接发；冷态先静默酷我自播再发）。
        runWhenReady(() -> send(i));
    }

    /** 主题搜歌：给主题词（如「睡前故事」「开车」），走 SEARCH_ALL 联网搜自动开播 */
    public static void searchByTheme(String theme) {
        searchByTheme(theme, false);
    }

    public static void searchByTheme(String theme, boolean verified) {
        if (theme == null || theme.trim().length() == 0) {
            return;
        }
        Intent i = new Intent(A_SEARCH);
        i.setPackage(PKG);
        i.putExtra("music_theme", theme.trim());
        // v0.3.42：播放类指令走 runWhenReady（暖态直接发；冷态先静默酷我自播再发）。
        runWhenReady(() -> send(i));
    }

    /** 按 rid 精确点播（rid 来自搜索结果回传 lastCandidates） */
    public static void playRid(long rid, String name, String singer) {
        Intent i = new Intent(A_PLAY_MUSIC);
        i.setPackage(PKG);
        i.putExtra("rid", rid);
        if (name != null && name.length() > 0) {
            i.putExtra("name", name);
        }
        if (singer != null && singer.length() > 0) {
            i.putExtra("singer", singer);
        }
        send(i);
    }

    /**
     * 整单替换播放：json=[{"rid":..,"name":..,"singer":..,"duration":..,
     * "album":..,"artistid":..,"mvQuality":..,"hasMv":..}, ...]，index=起始曲序。
     */
    public static void playAll(String json, int index) {
        if (json == null || json.trim().length() == 0) {
            return;
        }
        // v0.3.27：记住这份列表 —— 验证失败重试时**原样重发**，绝不重新洗牌。
        lastPlayAllJson = json.trim();
        Intent i = new Intent(A_PLAY_ALL);
        i.setPackage(PKG);
        i.putExtra("music_all_list_key", json.trim());
        i.putExtra("music_index_key", index);
        i.putExtra("replace_play_list", true);
        // v0.3.42：播放类指令走 runWhenReady（warm 直接发；cold 先拉起+静默自播再发）。
        runWhenReady(() -> send(i));
    }

    /**
     * v0.3.27：重发**上一次**那份整单替换列表（不洗牌、不换歌）。
     * 验证超时时用它兜底，避免把已经在播的第一首顶掉。
     * @return 是否有可重发的列表
     */
    public static boolean resendLastPlayAll() {
        String j = lastPlayAllJson;
        if (j == null || j.length() == 0) {
            return false;
        }
        Intent i = new Intent(A_PLAY_ALL);
        i.setPackage(PKG);
        i.putExtra("music_all_list_key", j);
        i.putExtra("music_index_key", 0);
        i.putExtra("replace_play_list", true);
        send(i);
        Log.i(TAG, "resend last play-all list (no re-shuffle)");
        return true;
    }

    /**
     * v0.3.27：是否「正在起播」—— 包括切歌/缓冲过渡态。
     *
     * 酷我收到整单替换后要 4~6 秒才真正出声，期间 status=0（切歌过渡）或
     * 上一次的旧状态（4=已停止）。旧验证只认 1/2=播放中，于是**起播途中的
     * 4~6 秒被判成失败** → 触发重试 → 顶掉第一首。
     * 这里把 status=0（切歌中）也视为「起播进行中」，不判失败。
     */

    /**
     * 播专辑/歌单/电台。type = album | Songlist | radio | list；
     * id=条目 id，name=名称，digest=摘要（可空）。
     */
    public static void playAlbum(String type, String id, String name, String digest) {
        if (type == null || id == null || id.length() == 0) {
            return;
        }
        android.net.Uri.Builder b = new android.net.Uri.Builder()
                .scheme("kwplay").authority("quku")
                .appendQueryParameter("qukuItemType", type)
                .appendQueryParameter("id", id);
        if (name != null && name.length() > 0) {
            b.appendQueryParameter("name", name);
        }
        if (digest != null && digest.length() > 0) {
            b.appendQueryParameter("digest", digest);
        }
        Intent i = new Intent(A_PLAY_ALBUM);
        i.setPackage(PKG);
        i.setData(b.build());
        send(i);
    }

    /** 随机播放。酷我没给这个 action 广播入口（handleCarPlay 里的分支是死代码）。
     *  v0.3.13：改为 root 读本地曲库 → 洗牌 → 整单替换播放（第一首即随机）。
     *  旧实现（切 MEDIA_RANDOM 模式 + STARTAPP auto_play）实测无效：
     *  模式切换只影响后续切歌顺序，auto_play resumed 的还是当前列表
     *  下一首 —— 用户听到的就是「现有播放列表继续播」（2026-10-25 反馈）。
     *  root 失败退回旧行为（有播放历史就乱序续播，没有放酷我热歌电台）。
     *  verified=true 时带「发后验证+失败重试」（v0.3.12）。 */
    public static String randomPlay() {
        return randomPlay(false);
    }

    /**
     * v0.3.44：未指定具体内容（"播放音乐"/"随便听听"/"来点音乐"）时播放/续播酷我。
     *
     * 关键修复：先**真正探测**酷我进程是否存活（isReallyStarted，跨 UID /proc 扫描），
     * 不再只信 kuwoStarted 过期标志 —— 酷我被杀掉但标志仍为 true 时，旧代码走暖态分支
     * 什么都不做却回报「正在播放」，正是「说在放实际没打开」的根因。
     *
     * 行为：
     *  · 未真正启动 → 拉起酷我（自播「上次曲目」即满足需求），并等最多 ~3s 确认进程
     *    真起来；起不来就如实回报「请手动打开」。
     *  · 已在播放（status 1/2）→ 保持不动，绝不杀前一首。
     *  · 暂停/停止/过渡（status 3/4/0）→ 发播放键续播，不换歌。
     *  · 状态未知(-1，刚拉起正自播) → 不再发键（避免误暂停自播曲），如实说「正在打开」。
     *
     * @return 给小智的回话文案（反映真实结果，不再无条件报「正在播放」）。
     */
    public static String randomPlay(boolean verified) {
        boolean alive = isReallyStarted();
        if (!alive) {
            kuwoStarted = false;            // 修正过期标志
            launchNoAuto(false);           // 真正拉起（酷我自播「上次曲目」）
            // 给系统最多 ~3s 真正拉起进程，再做一次确认
            for (int k = 0; k < 6 && !isReallyStarted(); k++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
            alive = isReallyStarted();
        }
        if (!alive) {
            return "已尝试打开酷我，但似乎没能启动，请在屏幕上点一下酷我音乐App";
        }
        if (isPlaying()) {
            // 已经在放：保持不动，不杀前一首
            Log.i(TAG, "randomPlay: already playing, keep previous track (no kill)");
            return "好的，正在为你播放音乐";
        }
        if (status == 3 || status == 4 || status == 0) {
            // 暂停/停止/过渡：续播前一首，不换歌
            mediaButton("MEDIA_PLAY_PAUSE");
            Log.i(TAG, "randomPlay: resumed previous track (no kill)");
            return "好的，已为你继续播放音乐";
        }
        // status 未知(-1)：刚拉起酷我正自播，等它出声即可，不要再发键避免误暂停
        Log.i(TAG, "randomPlay: kuwo alive, status unknown, let auto-play proceed");
        return "好的，正在为你打开酷我播放音乐";
    }

    /** root 读酷我本地曲库（所有歌单去重）洗牌后整单播放；失败返回 false */
    private static boolean randomPlayViaDb() {
        if (appCtx == null || !AppSettings.rootEnabled(appCtx)) {
            return false;
        }
        java.io.File tmp = new java.io.File(appCtx.getFilesDir(), "kuwo_snapshot.db");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "cat /data/data/cn.kuwo.kwmusiccar/databases/kwmusiccar.db"});
            java.io.InputStream in = p.getInputStream();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
            }
            fo.close();
            in.close();
            p.waitFor();
            if (tmp.length() < 4096) {
                Log.w(TAG, "kuwo db snapshot too small: " + tmp.length());
                return false;
            }
            android.database.sqlite.SQLiteDatabase db =
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                            tmp.getAbsolutePath(), null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            java.util.List<org.json.JSONObject> songs =
                    new java.util.ArrayList<org.json.JSONObject>();
            java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
            android.database.Cursor m = db.rawQuery(
                    "select rid,name,artist,album,artistid,duration,hasmv,mvquality"
                            + " from kwhd_music where rid>0 order by id", null);
            while (m.moveToNext()) {
                long rid = m.getLong(0);
                if (!seen.add(rid)) {
                    continue;
                }
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("rid", rid);
                o.put("name", m.getString(1));
                o.put("singer", m.getString(2));
                o.put("album", m.getString(3));
                o.put("artistid", m.getLong(4));
                o.put("duration", m.getInt(5));
                o.put("hasMv", m.getInt(6) != 0);
                o.put("mvQuality", m.getString(7) == null ? "" : m.getString(7));
                songs.add(o);
            }
            m.close();
            db.close();
            if (songs.isEmpty()) {
                Log.w(TAG, "random: kuwo local library empty");
                return false;
            }
            java.util.Collections.shuffle(songs);
            org.json.JSONArray arr = new org.json.JSONArray(songs);
            Log.i(TAG, "random: " + songs.size() + " songs shuffled from kuwo db");
            playAll(arr.toString(), 0);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "randomPlayViaDb failed", t);
            return false;
        }
    }

    /** 打开"我喜欢"列表并播放。
     *  酷我的 OPEN_FAVORITE_MUSIC 广播只是跳到收藏列表界面（jumpToFavoriteList →
     *  showFragment），不自动开播，且没有任何入口能直接播本地收藏列表。
     *  所以走 root：读酷我 sqlite 快照 → 取「我喜欢听」全部歌 → 拼 JSON 走
     *  vip.PLAY_ALL_MUSIC（静态 manifest 接收器，可靠）整单替换播放。
     *  @return 播放的歌数；-1=root 失败（已退回跳收藏页，让用户手点） */
    /** v0.3.35：播放「我喜欢」收藏列表，默认**随机打乱**顺序（用户要每次顺序随机）。 */
    public static int playFavorite() {
        return playFavorite(0, true);
    }

    /**
     * v0.3.31：播放「我喜欢」收藏列表，指定起点序号（startIndex=0 表示第一首）。
     * 「播放收藏的第三首」这类意图 → startIndex=2。**不打乱**（要的是确定的一首）。
     */
    public static int playFavorite(int startIndex) {
        return playFavorite(startIndex, false);
    }

    /**
     * v0.3.35：核心入口。
     * @param startIndex 起点下标（仅在不打乱时生效）
     * @param shuffle    true=打乱随机播放（「播放我的收藏」）；false=按列表顺序（「第N首」）
     */
    public static int playFavorite(int startIndex, boolean shuffle) {
        int n = playFavoritesViaDb(startIndex, shuffle);
        if (n > 0) {
            return n;
        }
        // 兜底：老行为，跳收藏列表界面。播放类指令走 runWhenReady（cold 先静默自播再发）。
        Intent i = new Intent(A_OPEN_FAV);
        i.setPackage(PKG);
        runWhenReady(() -> send(i));
        return -1;
    }

    /** root 读酷我库快照并整单播放收藏；startIndex=起始曲目下标（仅不打乱时生效）。
     *  shuffle=true 时打乱顺序后再发（随机播放收藏）。失败返回 -1 */
    private static int playFavoritesViaDb(int startIndex, boolean shuffle) {
        // v0.3.6：设置里 root 开关关闭则直接走兜底（跳收藏页）
        if (appCtx == null || !AppSettings.rootEnabled(appCtx)) {
            return -1;
        }
        java.io.File tmp = new java.io.File(appCtx.getFilesDir(), "kuwo_snapshot.db");
        try {
            // 1) su cat 快照（首次会弹 magisk 授权框）
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "cat /data/data/cn.kuwo.kwmusiccar/databases/kwmusiccar.db"});
            java.io.InputStream in = p.getInputStream();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
            }
            fo.close();
            in.close();
            p.waitFor();
            if (tmp.length() < 4096) {
                Log.w(TAG, "kuwo db snapshot too small: " + tmp.length());
                return -1;
            }
            // 2) 应用内 sqlite 解析（Android 自带 SQLiteDatabase，无需设备上有 sqlite3）
            android.database.sqlite.SQLiteDatabase db =
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                            tmp.getAbsolutePath(), null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            // v0.3.36：只取**真正的收藏列表**，不再把所有叫「我喜欢听」的列表合并。
            // 实测库里有多份同名列表（2026-10-26 拉开 kvmusiccar.db 确认）：
            //   · id 11：'我喜欢听'，type=LIST_MY_FAVORITE，version=0，仅 3 首旧残留（MØ 的歌）
            //   · id 14：'我喜欢听'，type=LIST_MY_FAVORITE，version=6，356 首 = 界面真正的收藏
            // 旧实现把两者合并 → 比界面多出几首、且那几首旧残留排在**最前面**，于是
            // 「播放收藏的第N首」与界面顺序整体错位（用户实测：不是界面看到的顺序）。
            // 规则：在 type=LIST_MY_FAVORITE（或名字=我喜欢听）里取**歌曲数最多**的那份。
            long favListId = -1;
            android.database.Cursor c = db.rawQuery(
                    "select l.id from kwhd_list l join "
                            + "(select listid, count(*) c2 from kwhd_music"
                            + "   where rid>0 group by listid) m on m.listid=l.id "
                            + "where (l.type like '%MY_FAVORITE%' or l.name=?) "
                            + "order by m.c2 desc, l.id desc limit 1",
                    new String[]{"我喜欢听"});
            if (c.moveToNext()) {
                favListId = c.getLong(0);
            }
            c.close();
            if (favListId < 0) {
                db.close();
                Log.w(TAG, "no favorite list in kuwo db");
                return -1;
            }
            // 酷我按 id 升序 = 界面显示顺序存储（实测「最近播放」列表 id 最小的正是
            // 最新播放的那首），故这里 order by id 即与界面从上到下一致。
            org.json.JSONArray arr = new org.json.JSONArray();
            java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
            android.database.Cursor m = db.rawQuery(
                    "select rid,name,artist,album,artistid,duration,hasmv,mvquality"
                            + " from kwhd_music where listid=? and rid>0 order by id",
                    new String[]{String.valueOf(favListId)});
            while (m.moveToNext()) {
                long rid = m.getLong(0);
                if (!seen.add(rid)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("rid", rid);
                o.put("name", m.getString(1));
                o.put("singer", m.getString(2));
                o.put("album", m.getString(3));
                o.put("artistid", m.getLong(4));
                o.put("duration", m.getInt(5));
                o.put("hasMv", m.getInt(6) != 0);
                o.put("mvQuality", m.getString(7) == null ? "" : m.getString(7));
                arr.put(o);
            }
            m.close();
            db.close();
            Log.i(TAG, "favorites list id=" + favListId);
            if (arr.length() == 0) {
                Log.w(TAG, "favorite list empty");
                return -1;
            }
            // v0.3.35：随机播放收藏 —— 打乱整份列表后再发。
            // （酷我 PLAY_ALL 按数组顺序播放，打乱数组即打乱播放顺序；
            //  「第N首」等指定序号时不打乱，保证播的是确定那首。）
            if (shuffle) {
                java.util.List<JSONObject> lst = new java.util.ArrayList<JSONObject>();
                for (int k = 0; k < arr.length(); k++) {
                    lst.add(arr.getJSONObject(k));
                }
                java.util.Collections.shuffle(lst);
                org.json.JSONArray shuffled = new org.json.JSONArray();
                for (JSONObject o : lst) {
                    shuffled.put(o);
                }
                arr = shuffled;
                Log.i(TAG, "favorites: shuffled " + arr.length() + " songs");
            } else {
                Log.i(TAG, "favorites: " + arr.length() + " songs from kuwo db (in order)");
            }
            int start = shuffle ? 0
                    : (startIndex > 0 && startIndex < arr.length() ? startIndex : 0);
            // v0.3.37：不再依赖酷我是否识别 music_index_key —— 直接把起点之前的歌
            // **旋转到数组末尾**，让「要播的那首」成为数组第 0 个元素，index 固定传 0。
            //
            // 依据（2026-10-26 实机截屏核对）：数据库 order by id 的前 5 首
            //   Il aurait suffi / Dancin (Krono Remix) / Merry Christmas Mr. Lawrence /
            //   If You Lie Down With Me / I Don't Wanna Live Forever
            // 与酷我「我的收藏」界面上的 1~5 首**逐一对上** → 排序本身没错，
            // 错的是「从第N首起播」这一步。旋转列表后无论酷我认不认 index，
            // 都必然从目标那首开始播；且后续顺序仍是界面顺序（末尾循环回开头）。
            if (start > 0) {
                org.json.JSONArray rot = new org.json.JSONArray();
                for (int k = start; k < arr.length(); k++) {
                    rot.put(arr.getJSONObject(k));
                }
                for (int k = 0; k < start; k++) {
                    rot.put(arr.getJSONObject(k));
                }
                arr = rot;
                Log.i(TAG, "favorites: rotated to start at #" + (start + 1) + " -> "
                        + arr.getJSONObject(0).optString("name"));
            }
            playAll(arr.toString(), 0);
            return arr.length();
        } catch (Throwable t) {
            Log.w(TAG, "playFavoritesViaDb failed", t);
            return -1;
        }
    }

    /**
     * v0.3.42：冷启动拉起酷我。
     *
     * 实测结论：酷我无论收到 auto_play 为何值，只要被拉起（Activity 创建）就会自播
     * 「上次曲目」。所以不能靠 auto_play=false 来避免自播（v0.3.38 那版修复基于错误
     * 假设，双播其实从未修好，2026-10-28 复测证实）。
     * 做法：拉起后延迟 {@link #COLD_PAUSE_MS} 发一次 MEDIA_PLAY_PAUSE，把这首误播的
     * 歌静默掉；真正的播放指令由 {@link #runWhenReady} 在 {@link #COLD_PLAY_DELAY_MS}
     * 之后发出，这样只听到「正确的那首」，不会先播一首再跳下一首。
     */
    /** 冷启动拉起酷我（只拉起、不再做后台抢前台，v0.3.41 撤回）。
     *  suppress=true：指定内容的指令（搜歌/搜歌手/整单）—— 酷我必自播「上次曲目」，
     *    延迟发暂停把它静默掉，等 runWhenReady 发真正指令，避免双播。
     *  suppress=false：未指定内容的「播放音乐」—— 保留酷我自播的曲目，绝不杀前一首。 */
    private static void launchNoAuto() {
        launchNoAuto(true);
    }

    private static void launchNoAuto(boolean suppress) {
        Intent i = new Intent(A_STARTAPP);
        i.setPackage(PKG);
        i.putExtra("auto_play", false);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            appCtx.startActivity(i);
            kuwoStarted = true;   // v0.3.32：我们主动拉起了 → 标记已启动
            lastLaunchMs = android.os.SystemClock.uptimeMillis();  // v0.3.44：拉起时刻
            Log.i(TAG, "startapp (cold, suppress=" + suppress + ")");
            if (!suppress) {
                // 未指定内容：保留酷我自播的曲目，不静默、不覆盖
                return;
            }
            // 酷我必然自播，延迟暂停掉这首误播曲目，等 runWhenReady 发真正指令
            new Thread(() -> {
                try {
                    Thread.sleep(COLD_PAUSE_MS);
                } catch (InterruptedException ignored) {
                }
                if (!kuwoStarted) {
                    return;
                }
                mediaButton("MEDIA_PLAY_PAUSE");
                Log.i(TAG, "cold-launch auto-play suppressed");
            }).start();
        } catch (Throwable t) {
            Log.w(TAG, "startapp failed", t);
        }
    }

    /**
     * v0.3.42：在酷我已就绪的前提下执行真正的播放指令。
     * 暖态（酷我已在后台运行）：立即执行 —— 单播、零闪烁、不额外拉起。
     * 冷态（刚拉起）：等冷启动自播被静默暂停后（COLD_PLAY_DELAY_MS）再发指令，
     * 避免「指令比暂停先到 → 被自己刚发的暂停误杀」或「自播的歌还在放就被顶掉」。
     */
    private static void runWhenReady(Runnable r) {
        if (isReallyStarted()) {
            r.run();
            return;
        }
        kuwoStarted = false;            // v0.3.44：修正过期标志，确保真正冷拉起
        launchNoAuto();
        new Thread(() -> {
            // v0.3.44：先等酷我进程真正起来（最多 ~3s），避免广播在 Receiver 注册前
            // 被丢弃导致「说在放实际没打开」。之后再用 COLD_PLAY_DELAY_MS 让冷启自播
            // 先被静默暂停，再发真正指令（仍在暂停之后，不会被误杀）。
            for (int k = 0; k < 15 && !isReallyStarted(); k++) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
            try {
                Thread.sleep(COLD_PLAY_DELAY_MS);
            } catch (InterruptedException ignored) {
            }
            r.run();
        }).start();
    }

    /**
     * v0.3.42：歌手搜索模式下，SEARCH_MUSIC 会先自播一首（往往是合作曲/不相关），
     * 延迟 600ms 暂停掉它，等 A_CAND 回来后由 resolveArtistAndPlay 用整单替换播放
     * 干净列表。若候选已回传（说明已替换、正在播干净列表），则跳过暂停，避免误杀正确的歌。
     */
    private static void suppressJunkAfterSearch() {
        new Thread(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
            if (lastCandidates != null) {
                Log.i(TAG, "junk already replaced, skip suppress");
                return;
            }
            if (!kuwoStarted) {
                return;
            }
            mediaButton("MEDIA_PLAY_PAUSE");
            Log.i(TAG, "search junk auto-play suppressed");
        }).start();
    }

    /** v0.3.42：发 RANDOM_PLAY_MUSIC 广播（随机播放，自动起播） */
    private static void sendRandomPlay() {
        Intent i = new Intent(A_RANDOM);
        i.setPackage(PKG);
        send(i);
    }

    /** 播放/暂停切换（此版本 MEDIA_PAUSE 是空操作，统一走 PLAY_PAUSE） */
    public static void playPause() {
        mediaButton("MEDIA_PLAY_PAUSE");
    }

    /**
     * v0.3.32：暂停指令。用户要求「其他指令不需要状态判断」，所以这里**直接发**
     * MEDIA_PLAY_PAUSE（切换语义），不再判断当前是在放还是已暂停 —— 到底切到
     * 暂停还是恢复，由用户自己看屏幕。返回固定回话文案。
     */
    public static String ensurePaused() {
        playPause();
        return "已发送暂停指令";
    }

    /**
     * v0.3.32：继续播放指令。同样直接发 MEDIA_PLAY_PAUSE，不做状态判断。
     */
    public static String ensureResumed() {
        playPause();
        return "已发送继续播放指令";
    }

    /** 下一首 */
    public static void next() {
        mediaButton("MEDIA_NEXT");
    }

    /** 上一首 */
    public static void prev() {
        mediaButton("MEDIA_PRE");
    }

    /** 静音切换 */
    public static void mute() {
        mediaButton("MEDIA_MUTE");
    }

    /**
     * 播放模式：mode = one(单曲循环) | order(顺序) | circle(列表循环) | random(随机)。
     * @return 是否识别
     */
    public static boolean setMode(String mode) {
        if (mode == null) {
            return false;
        }
        String m = mode.trim().toLowerCase();
        String key;
        if (m.equals("one") || m.equals("单曲循环") || m.contains("单曲")) {
            key = "MEDIA_ONE";
        } else if (m.equals("order") || m.contains("顺序")) {
            key = "MEDIA_ORDER";
        } else if (m.equals("circle") || m.equals("loop") || m.contains("列表") || m.contains("循环")) {
            key = "MEDIA_CIRCLE";
        } else if (m.equals("random") || m.contains("随机")) {
            key = "MEDIA_RANDOM";
        } else {
            return false;
        }
        mediaButton(key);
        return true;
    }

    /** 收藏当前正在播放的歌 */
    public static void favorite() {
        Intent i = new Intent(A_FAV);
        i.setPackage(PKG);
        send(i);
    }

    /** 收藏(true)/取消收藏(false)当前歌 */
    public static void collect(boolean on) {
        Intent i = new Intent(A_COLLECT);
        i.setPackage(PKG);
        i.putExtra("COLLECT", on);
        send(i);
    }

    /** 彻底退出酷我 */
    public static void exitApp() {
        Intent i = new Intent(A_EXIT);
        i.setPackage(PKG);
        send(i);
        // v0.3.32：用户主动退出 → 标记酷我已不再启动（用户面指令不再依赖此状态，
        // 这里仅让内部互斥逻辑知道酷我已关）。
        kuwoStarted = false;
        status = -1;
        title = null;
        artist = null;
        album = null;
        vipLocked = false;
        lastPlayAllJson = null;
    }

    /**
     * 设置播放音质：0=标准 1=高品 2=无损（酷我 setting_strKey=music_quality_when_play）
     */
    public static void setQuality(int level) {
        Intent i = new Intent(A_SETTING);
        i.setPackage(PKG);
        i.putExtra("setting_strKey", "music_quality_when_play");
        i.putExtra("setting_value", level);
        send(i);
    }

    private static void mediaButton(String key) {
        Intent i = new Intent(A_MEDIA);
        i.setPackage(PKG);
        i.putExtra("EXTRA", key);
        i.putExtra("Hardware_MediaButton", true);
        send(i);
    }

    private static void send(Intent i) {
        if (appCtx == null) {
            Log.w(TAG, "not init, drop " + i.getAction());
            return;
        }
        if (i.getStringExtra("kuwo_key") == null
                && !"cn.kuwo.kwmusicauto.action.MEDIA_BUTTON".equals(i.getAction())) {
            // handleCarPlay 分支要求 extras 非空且有 key 字段（check_key=false 时任意值放行）
            i.putExtra("kuwo_key", "ldyb5");
        }
        try {
            appCtx.sendBroadcast(i);
            Log.i(TAG, "sent " + i.getAction() + " " + i.getExtras());
            SessionLog.i("kuwo sent " + i.getAction());
        } catch (Throwable t) {
            Log.w(TAG, "send failed", t);
        }
    }
}
