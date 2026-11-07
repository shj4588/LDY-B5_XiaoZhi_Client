package cn.workbuddy.ldyb5app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v1.2.10：抗淹没会话日志（落盘到 app 内部存储）。
 *
 * 为什么存在：原厂 uni4mic 在 USC 录音期间每秒 100+ 行刷爆 logcat，
 * 256KB 环形缓冲只留最后 ~20 秒 —— 会话期间的关键日志（connecting/
 * hello/服务器回应）在事后抓取时已被挤出，当天全部诊断被活埋。
 *
 * v1.2.9 曾写外部存储（getExternalFilesDir），实测该 ROM 上目录根本
 * 建不出来（FUSE 异常，20:46 验证），v1.2.10 改内部存储 getFilesDir()
 * —— 必定可写、无权限要求；用 run-as 读取（debug 包可用，无需 root，
 * 20:46 已验证 run-as 在本机可用）：
 *
 *   adb shell run-as cn.workbuddy.ldyb5app cat files/ldyb5app.log
 *
 * 256KB 轮转成 .old（run-as cat 一秒内可拉完）。写失败只向 logcat
 * 报一次，之后静默，绝不影响主流程。
 */
final class SessionLog {

    private static final Object LOCK = new Object();
    private static volatile File file;
    private static volatile boolean failureLogged = false;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /** 在 Service.onCreate 调一次 */
    static void init(Context ctx) {
        try {
            file = new File(ctx.getFilesDir(), "ldyb5app.log");
        } catch (Throwable t) {
            file = null;
        }
    }

    static void i(String msg) {
        write(msg);
    }

    private static void write(String msg) {
        File f = file;
        if (f == null) {
            return;
        }
        try {
            synchronized (LOCK) {
                if (f.exists() && f.length() > 256L * 1024L) {
                    File old = new File(f.getParentFile(), "ldyb5app.log.old");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(old);
                }
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write((TS.format(new Date()) + " " + msg + "\n").getBytes("UTF-8"));
                fos.close();
            }
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                Log.w("L5CLIENT", "SessionLog write failed (logged once): " + t);
            }
        }
    }

    private SessionLog() {
    }
}
