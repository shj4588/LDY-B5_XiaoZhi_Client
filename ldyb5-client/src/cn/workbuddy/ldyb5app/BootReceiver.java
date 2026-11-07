package cn.workbuddy.ldyb5app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启：BOOT_COMPLETED 后拉起前台服务。
 *
 * 注意 R1 ROM 可能对自启有额外管控（systemtool/launcher 白名单），
 * 若不自启，可在设备上执行：
 *   adb shell am startservice cn.workbuddy.ldyb5app/.Ldyb5ClientService
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "L5CLIENT";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "BootReceiver: " + action);
        try {
            Intent svc = new Intent(ctx, Ldyb5ClientService.class);
            // R1 是 Android 5.1 (API22)，无 startForegroundService（API26+）。
            // 直接 startService；服务内部会调 startForeground 提升优先级。
            ctx.startService(svc);
        } catch (Throwable t) {
            Log.e(TAG, "start Ldyb5ClientService failed", t);
        }
    }
}
