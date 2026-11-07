package cn.workbuddy.ldyb5app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * v0.3.66：全局音量键感知（补足 VOLUME_CHANGED_ACTION 广播的盲区）。
 *
 * 固件无系统音量面板，我们用 VOLUME_CHANGED_ACTION 广播弹音量条，已覆盖「界面内外音量变化时」。
 * 但音量已是 0 再按减键时，系统不改音量、也不发广播，条不弹（用户诉求：按了就该显示）。
 *
 * AccessibilityService.onKeyEvent 在系统处理音量键「之前」就能拿到本次按键（无论音量是否变化），
 * 据此直接 show 音量条。我们不消费该事件（return false），系统仍照常调音量并广播，故不会双调。
 *
 * 配置见 res/xml/accessibility_service_config.xml（typeAllMask + flagRequestFilterKeyEvents）。
 * 需用户在「设置 - 无障碍」中开启本服务；未开启时本服务不生效，退回广播方案（维持现状）。
 */
public final class VolKeyAccessibilityService extends AccessibilityService {
    private static final String TAG = "L5CLIENT";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "volkey a11y service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        // 无需处理具体可访问性事件，仅用 onKeyEvent 感知音量键
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_VOLUME_UP
                    || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            try {
                // 只显示、不调音量：系统随后照常 adjustStreamVolume（变化时会再走广播），避免双调。
                VolBarOverlay.show(this);
            } catch (Throwable t) {
                Log.w(TAG, "volkey a11y show failed: " + t);
            }
        }
        return false;   // 不消费，系统继续处理音量键
    }
}
