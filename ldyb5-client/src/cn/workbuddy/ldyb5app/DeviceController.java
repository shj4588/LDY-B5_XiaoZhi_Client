package cn.workbuddy.ldyb5app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v0.3.50：MCP 设备 / 程序控制（屏幕常亮、亮度、退出小智、回到桌面）。
 *
 * <p>屏幕常亮走的是 <b>Android 系统自身的功能</b>，不自己造常亮服务：
 * <ul>
 *   <li>{@code Settings.Global.STAY_ON_WHILE_PLUGGED_IN = 7}
 *       —— 系统「充电时保持唤醒」（开发选项 / {@code svc power stayon true}）；
 *       带屏音箱常插电，等价于永不休眠。</li>
 *   <li>{@code Settings.System.SCREEN_OFF_TIMEOUT = Integer.MAX_VALUE}
 *       —— 系统休眠超时，约 24.8 天，未插电时也生效。</li>
 *   <li>额外持有 {@code PowerManager.WakeLock(SCREEN_BRIGHT_WAKE_LOCK)} 兜底：
 *       即使无 root / 系统设置被别的 App 改回去，只要小智进程在，屏幕也不会灭。</li>
 * </ul>
 * 关闭时把两项系统设置<b>精确恢复成开启前的值</b>（保存在 SP），不硬编码 5 分钟。
 *
 * <p>亮度写 {@code Settings.System.SCREEN_BRIGHTNESS}（0~255），先关自动亮度；
 * 同样优先系统 Java API，权限不足时退回 root shell 的 {@code settings put}。
 * 回桌面启动系统默认 HOME；退出小智复用 SettingsUI 的「完全关闭」流程（停服务→杀进程）。
 */
public class DeviceController {

    private static final String TAG = "L5CLIENT-DEV";
    private static final String SP = "ldyb5_device";

    /** ≈24.8 天，AOSP 里等价于「永不休眠」；不能写成 -1（会被当非法值忽略）。 */
    private static final int NEVER_TIMEOUT = Integer.MAX_VALUE;
    /** BatteryManager BATTERY_PLUGGED_AC|USB|WIRELESS */
    private static final int STAY_ON_ALL = 7;
    /** 最低亮度，避免写 0 直接黑屏看不见（约 8%） */
    private static final int MIN_BRIGHTNESS = 20;
    private static final int MAX_BRIGHTNESS = 255;

    private static Context appCtx;
    private static Handler mainHandler;
    private static PowerManager.WakeLock keepLock;
    private static volatile boolean stayOn = false;

    private static final Set<String> TOOLS = new HashSet<String>(Arrays.asList(
            "self_screen_stay_on", "self_screen_stay_off",
            "self_screen_brightness", "self_device_status",
            "self_exit_app", "self_go_home"));

    public static boolean isDeviceTool(String name) {
        return name != null && TOOLS.contains(name);
    }

    public static synchronized void init(Context ctx) {
        if (ctx == null) return;
        appCtx = ctx.getApplicationContext();
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
    }

    /** MCP tools/call 统一入口；query/pct/on 都由 XiaoZhiWs 从 arguments 里取出。 */
    public static String handle(String tool, String query, String pct, String on) {
        if (appCtx == null) init(KuwoController.appCtx);
        if (appCtx == null) return "设备上下文未就绪";
        try {
            if ("self_screen_stay_on".equals(tool)) return setStayOn(true);
            if ("self_screen_stay_off".equals(tool)) return setStayOn(false);
            if ("self_screen_brightness".equals(tool)) return brightness(query, pct);
            if ("self_device_status".equals(tool)) return status();
            if ("self_go_home".equals(tool)) return goHome();
            if ("self_exit_app".equals(tool)) return exitApp();
        } catch (Throwable t) {
            Log.w(TAG, "handle " + tool + " failed", t);
            return "设备控制失败：" + t.getMessage();
        }
        return "未知工具：" + tool;
    }

    // ---------------------------------------------------------------- 屏幕常亮

    /** 开启/关闭常亮；on=true 时先记下原值，on=false 时按原值恢复。 */
    private static String setStayOn(boolean on) {
        SharedPreferences sp = appCtx.getSharedPreferences(SP, 0);
        int origTimeout = readInt(Settings.System.SCREEN_OFF_TIMEOUT, 300000, false);
        int origStay = readInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0, true);

        if (on) {
            if (!sp.getBoolean("saved", false)) {
                sp.edit()
                        .putInt("timeout", origTimeout)
                        .putInt("stayon", origStay)
                        .putBoolean("saved", true)
                        .apply();
                Log.i(TAG, "saved original timeout=" + origTimeout + " stayon=" + origStay);
            }
            boolean ok = writeSystem(NEVER_TIMEOUT, STAY_ON_ALL);
            boolean lock = acquireLock();
            stayOn = true;
            String how = ok ? "系统设置" : "系统 WakeLock";
            if (ok && lock) how = "系统设置 + WakeLock";
            return "好的，已开启屏幕常亮（" + how + "），屏幕不会自动熄灭了";
        }

        releaseLock();
        stayOn = false;
        if (sp.getBoolean("saved", false)) {
            int t = sp.getInt("timeout", 300000);
            int s = sp.getInt("stayon", 0);
            boolean ok = writeSystem(t, s);
            sp.edit().remove("saved").apply();
            return ok ? "已关闭屏幕常亮，恢复原来的自动息屏（" + (t / 1000) + " 秒）"
                    : "已释放常亮锁，系统设置恢复失败（可能需要 Root）";
        }
        return "已关闭屏幕常亮，恢复自动息屏";
    }

    /**
     * 写两项系统设置：优先 Android 系统 Java API（Settings.*，最直接）；
     * 普通第三方 App 没有 WRITE_SETTINGS，会抛 SecurityException，退回 root shell
     * 的 {@code settings put} / {@code svc power stayon}（同样是系统自带功能）。
     * 最后回读校验，避免「命令执行了但值没变」被当成成功。
     */
    private static boolean writeSystem(int timeout, int stay) {
        boolean applied = false;
        try {
            android.content.ContentResolver cr = appCtx.getContentResolver();
            Settings.Global.putInt(cr, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, stay);
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, timeout);
            applied = readInt(Settings.System.SCREEN_OFF_TIMEOUT, -1, false) == timeout
                    && readInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, -1, true) == stay;
        } catch (Throwable ignore) {
            // 预期：无 WRITE_SETTINGS 权限
        }
        if (applied) return true;
        if (!AppSettings.rootEnabled(appCtx)) return false;

        String cmd = (timeout == NEVER_TIMEOUT ? "svc power stayon true; " : "")
                + "settings put global stay_on_while_plugged_in " + stay + "; "
                + "settings put system screen_off_timeout " + timeout;
        String out = shell(cmd);
        Log.i(TAG, "settings shell -> " + out);
        return out != null
                && readInt(Settings.System.SCREEN_OFF_TIMEOUT, -1, false) == timeout
                && readInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, -1, true) == stay;
    }

    private static boolean acquireLock() {
        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            if (keepLock == null) {
                keepLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                        "ldyb5:StayOn");
                keepLock.setReferenceCounted(false);
            }
            if (!keepLock.isHeld()) keepLock.acquire();
            return keepLock.isHeld();
        } catch (Throwable t) {
            Log.w(TAG, "acquire wake lock failed", t);
            return false;
        }
    }

    private static void releaseLock() {
        try {
            if (keepLock != null && keepLock.isHeld()) keepLock.release();
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------- 亮度

    /**
     * 亮度：支持「50」「50%」「百分之三十」「亮一点」「暗一点」「最亮」「最暗」，
     * 空参数则查询当前亮度。映射 0~100% → 20~255（系统原始值）。
     */
    private static String brightness(String query, String pct) {
        String s = firstNonEmpty(query, pct).trim();
        int curRaw = readInt(Settings.System.SCREEN_BRIGHTNESS, MAX_BRIGHTNESS, false);
        int curPct = Math.round((curRaw - MIN_BRIGHTNESS) * 100f / (MAX_BRIGHTNESS - MIN_BRIGHTNESS));
        curPct = Math.max(0, Math.min(100, curPct));

        if (s.length() == 0) {
            return "当前屏幕亮度 " + curPct + "%（系统值 " + curRaw + "/255）";
        }
        String n = s.replaceAll("[\\s，。！？!?,.、％%percent]", "").toLowerCase();
        int target;
        if (n.contains("最亮") || n.contains("最高") || n.contains("最大") || n.contains("全亮")) {
            target = 100;
        } else if (n.contains("最暗") || n.contains("最低") || n.contains("最小")) {
            target = 0;
        } else if (n.contains("亮一点") || n.contains("亮些") || n.contains("调亮")
                || n.contains("太暗") || n.contains("亮一点") || n.contains("调高")
                || n.contains("亮一档")) {
            target = Math.min(100, curPct + 20);
        } else if (n.contains("暗一点") || n.contains("暗些") || n.contains("调暗")
                || n.contains("太亮") || n.contains("调低") || n.contains("暗一档")) {
            target = Math.max(0, curPct - 20);
        } else {
            int v = parsePercent(n);
            if (v < 0) return "没听懂亮度要求，可以说「调到 50%」「亮一点」「最亮」";
            target = Math.max(0, Math.min(100, v));
        }
        int raw = MIN_BRIGHTNESS + Math.round((MAX_BRIGHTNESS - MIN_BRIGHTNESS) * target / 100f);
        boolean wasOff = !isScreenOn();
        boolean ok = writeBrightness(raw);
        if (!ok) return "亮度设置失败，需要 Root 功能（可在小智设置里打开）";
        String detail = "（系统值 " + raw + "/255）";
        // v0.3.57：目标本来就等于当前值时不能再说「已调到」——设备本来就是 255
        // 时反复喊「调到最大」会被误判成工具失效。
        if (raw == curRaw) {
            return "当前已经是 " + target + "%" + detail + "，没有变化"
                    + (target >= 100 ? "，已经是最大亮度了" : "") + (wasOff ? "；已点亮屏幕" : "");
        }
        return "屏幕亮度已调到 " + target + "%" + detail + (wasOff ? "，已点亮屏幕" : "");
    }

    /** 先关自动亮度再写目标值；同样优先系统 API，失败退 root shell。 */
    private static boolean writeBrightness(int raw) {
        boolean applied = false;
        try {
            android.content.ContentResolver cr = appCtx.getContentResolver();
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, raw);
            applied = readInt(Settings.System.SCREEN_BRIGHTNESS, -1, false) == raw;
        } catch (Throwable ignore) {
        }
        if (!applied) {
            if (!AppSettings.rootEnabled(appCtx)) return false;
            String out = shell("settings put system screen_brightness_mode 0; "
                    + "settings put system screen_brightness " + raw);
            Log.i(TAG, "brightness shell -> " + out);
            applied = out != null
                    && readInt(Settings.System.SCREEN_BRIGHTNESS, -1, false) == raw;
            if (!applied) return false;
        }
        // v0.3.57：息屏时背光节点恒为 0，只写 settings 用户看不到任何变化。
        // 先点亮，唤醒动作会把亮度拉到 255，所以点亮后必须重写一次 settings。
        if (!isScreenOn()) {
            shell("input keyevent 224");           // KEYCODE_WAKEUP
            try {
                Thread.sleep(350L);
            } catch (InterruptedException ignored) {
            }
            shell("settings put system screen_brightness " + raw);
        }
        // v0.3.57 ★：直接把目标值写进背光节点，并延迟补写两次。
        // Android 5.1 上 settings 变化不一定立即重算 mBrightness，实测出现过
        // 「写了 208 但屏幕仍是 255，直到 app 退出 / 显示状态变化才生效」；
        // 写 sysfs 让背光当场生效，补写抵消之后唤醒点亮/窗口变化把亮度拉回。
        writeBacklightAsync(raw, 3);
        return true;
    }

    private static boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isScreenOn();
        } catch (Throwable t) {
            return true;   // 判断不了就按亮屏处理，不要误触发点亮
        }
    }

    /** 唤醒点亮后重新套用当前 settings 亮度（WakeScreenActivity 调用）。 */
    public static void reapplyBrightness(android.content.Context ctx) {
        if (ctx == null) return;
        if (appCtx == null) init(ctx.getApplicationContext());
        if (appCtx == null) return;
        int raw = readInt(Settings.System.SCREEN_BRIGHTNESS, -1, false);
        if (raw > 0) writeBacklightAsync(raw, 2);
    }

    /** 后台线程写背光节点：立即一次 + 每 600ms 补写，共 repeats 次。 */
    private static void writeBacklightAsync(final int raw, final int repeats) {
        if (!AppSettings.rootEnabled(appCtx)) return;
        final String cmd = backlightWriteCmd(raw);
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < repeats; i++) {
                    if (i > 0) {
                        try {
                            Thread.sleep(600L);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    shell(cmd);
                }
            }
        }, "ldyb5-backlight").start();
    }

    /** 兼容标准 backlight 类与 MTK leds 类；按 max_brightness 缩放到节点值域。 */
    private static String backlightWriteCmd(int raw) {
        return "for d in /sys/class/backlight/* /sys/class/leds/lcd-backlight; do "
                + "f=\"$d/brightness\"; [ -w \"$f\" ] || continue; "
                + "m=$(cat \"$d/max_brightness\" 2>/dev/null); [ -z \"$m\" ] && m=255; "
                + "v=$(( " + raw + " * $m / 255 )); [ \"$v\" -lt 1 ] && v=1; "
                + "echo \"$v\" > \"$f\"; done";
    }

    /** 读实际背光节点值（诊断用；读不到返回 -1）。 */
    private static int readBacklight() {
        String out = shell("cat /sys/class/leds/lcd-backlight/brightness 2>/dev/null");
        if (out == null) return -1;
        try {
            return Integer.parseInt(out.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 解析阿拉伯数字与简单中文数字（三十 / 五十 / 二十五 / 一百）。 */
    private static int parsePercent(String n) {
        String digits = n.replaceAll("[^0-9]", "");
        if (digits.length() > 0) {
            try {
                return Integer.parseInt(digits);
            } catch (Throwable ignore) {
            }
        }
        // 「百分之三十」里的「百」是百分号语义，不是数值 100，必须先剥掉，
        // 否则会被算成 100+30=130（v0.3.50 实测踩到，钳到 100% 变成最亮）。
        String work = n.replace("百分之", "").replace("百分", "").replace("之", "");
        String cn = work.replaceAll("[^零一二两三四五六七八九十百]", "");
        if (cn.length() == 0) return -1;
        int[] val = {0, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9};
        String map = "零一二两三四五六七八九";
        int total = 0, section = 0, num = 0;
        for (int i = 0; i < cn.length(); i++) {
            char c = cn.charAt(i);
            int idx = map.indexOf(c);
            if (idx >= 0) {
                num = val[idx];
            } else if (c == '十') {
                section += (num == 0 ? 1 : num) * 10;
                num = 0;
            } else if (c == '百') {
                section += (num == 0 ? 1 : num) * 100;
                num = 0;
            }
        }
        total += section + num;
        return total;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.trim().length() > 0) return a;
        return b == null ? "" : b;
    }

    // ---------------------------------------------------------------- 状态 / 桌面 / 退出

    private static String status() {
        int timeout = readInt(Settings.System.SCREEN_OFF_TIMEOUT, 300000, false);
        int stay = readInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0, true);
        int raw = readInt(Settings.System.SCREEN_BRIGHTNESS, MAX_BRIGHTNESS, false);
        int pct = Math.max(0, Math.min(100,
                Math.round((raw - MIN_BRIGHTNESS) * 100f / (MAX_BRIGHTNESS - MIN_BRIGHTNESS))));
        boolean lockHeld = keepLock != null && keepLock.isHeld();
        boolean always = stayOn || lockHeld || timeout == NEVER_TIMEOUT || stay != 0;
        // v0.3.57：把「设置值」和「实际背光」分开报，低亮度/息屏导致
        // 「写了 settings 但屏幕没变」时一眼就能分辨，不用再猜。
        int node = readBacklight();
        return "屏幕常亮" + (always ? "开启" : "关闭") + "，亮度 " + pct + "%（系统值 "
                + raw + "/255，背光节点 " + (node < 0 ? "不可读" : String.valueOf(node)) + "），"
                + "屏幕=" + (isScreenOn() ? "亮" : "灭") + "，"
                + "息屏超时 " + (timeout == NEVER_TIMEOUT ? "永不" : (timeout / 1000) + " 秒")
                + "，充电保持唤醒=" + (stay != 0 ? "开" : "关")
                + "，常亮锁=" + (keepLock != null && keepLock.isHeld() ? "持有" : "无");
    }

    /** 回到系统默认桌面。小智自己也声明了 HOME，解析结果若是自己要跳过。 */
    private static String goHome() {
        PackageManager pm = appCtx.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        String self = appCtx.getPackageName();
        String pkg = null, cls = null;
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && !self.equals(def.activityInfo.packageName)) {
            pkg = def.activityInfo.packageName;
            cls = def.activityInfo.name;
        } else {
            List<ResolveInfo> homes = pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo ri : homes) {
                if (ri.activityInfo != null && !self.equals(ri.activityInfo.packageName)) {
                    pkg = ri.activityInfo.packageName;
                    cls = ri.activityInfo.name;
                    break;
                }
            }
        }
        if (pkg == null) return "没有找到其他桌面应用";
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            appCtx.startActivity(i);
            return "已回到桌面";
        } catch (Throwable t) {
            return "回桌面失败：" + t.getMessage();
        }
    }

    /**
     * 退出小智：与 SettingsUI「⏻ 退出小智（完全关闭）」同一套流程。
     * 先回 MCP 结果，再播本地退出提示音，2s 后停服务（释放麦克风/KWS/WS）→ 杀进程。
     * 停服务会关闭 WebSocket，所以服务端的道别 TTS 来不及播，用本地提示音代替。
     */
    private static String exitApp() {
        // TTS 会在 stopService -> Ldyb5ClientService.onDestroy() 里随 player.stop() 一起停。
        try {
            if (MusicPlayer.I != null) MusicPlayer.I.stop();
        } catch (Throwable ignore) {
        }
        try {
            SessionController.playExitCue();
        } catch (Throwable ignore) {
        }
        final Context c = appCtx;
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                try {
                    IslandOverlay.stop();
                } catch (Throwable ignore) {
                }
                try {
                    ScreenBus.state = "已退出";
                } catch (Throwable ignore) {
                }
                try {
                    c.stopService(new Intent(c, Ldyb5ClientService.class));
                } catch (Throwable ignore) {
                }
                mainHandler.postDelayed(new Runnable() {
                    public void run() {
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }, 400L);
            }
        }, 2000L);
        return "好的，小智下班了，再见";
    }

    // ---------------------------------------------------------------- 工具

    private static int readInt(String key, int def, boolean global) {
        try {
            android.content.ContentResolver cr = appCtx.getContentResolver();
            return global ? Settings.Global.getInt(cr, key, def)
                    : Settings.System.getInt(cr, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 执行 root shell；返回 null 表示失败（无 root / 命令异常）。 */
    private static String shell(String cmd) {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            Log.w(TAG, "shell failed: " + cmd, t);
            return null;
        }
    }
}
