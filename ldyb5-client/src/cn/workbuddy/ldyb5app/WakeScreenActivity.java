package cn.workbuddy.ldyb5app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * v0.3.33：透明临时 Activity，唯一用途是把熄屏的屏幕点亮。
 *
 * 为什么不直接用 WAKE_LOCK 点亮：
 *   old wakeScreen() 用 SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP，
 *   但很多厂商 ROM 会屏蔽非系统应用的唤醒（用户实测「息屏后再喊小智屏幕不亮」）。
 *
 * 这里改走 Activity 启动路径：FLAG_TURN_SCREEN_ON 是系统级点亮，不受厂商
 * wake-lock 屏蔽策略影响；FLAG_SHOW_WHEN_LOCKED 保证锁屏之下也能启动它。
 * 主题设成全透明（Theme.Translucent），onCreate 里立刻 finish —— 不显示任何
 * 可见内容，不会把小智界面顶到锁屏之上，用户看到的就是「锁屏亮了」。
 *
 * v0.3.34 修正：不能再 onCreate 里立刻 finish()。窗口必须被 WindowManager
 * 真正添加后，FLAG_TURN_SCREEN_ON 的点亮请求才会生效；立即 finish 会让窗口随
 * 销毁被撤销，表现为「说已点亮但屏幕不亮」（用户 2026-10-26 实测）。这里延迟
 * 一小段再退出，期间 FLAG_KEEP_SCREEN_ON 兜底防止刚亮就灭。
 */
public class WakeScreenActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // v0.3.34：等窗口真正上屏、屏幕点亮后再退出。800ms 足够 WM 完成添加，
        // 退出后由系统正常息屏计时接管（用户只要求点亮，不要求常亮）。
        // v0.3.57：FLAG_TURN_SCREEN_ON 点亮时会把背光拉到系统缓存值（实测最亮），
        // 用户之前设定的亮度会被盖掉 —— 这正是「调完亮度没反应、退出小智后才生效」
        // 的一类触发点。点亮后重新套用 settings 里的亮度。
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    DeviceController.reapplyBrightness(WakeScreenActivity.this);
                } catch (Throwable ignored) {
                }
            }
        }, 300);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 800);
    }
}
