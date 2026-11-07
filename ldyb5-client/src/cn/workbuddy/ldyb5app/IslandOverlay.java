package cn.workbuddy.ldyb5app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * v0.3.9：灵动岛全局悬浮窗 —— 挂在前台服务上的系统级悬浮小机器人。
 *
 * · 覆盖任何界面（小智 app / 桌面 / 其他 app 之上），默认顶部居中
 * · FLAG_NOT_TOUCHABLE：完全不接收触摸，不影响下层操作（连点进设置等）
 * · 小智连接期间（连接/聆听/说话）常驻，断开（待唤醒）消失
 * · 心情同步状态/回复内容；眨眼 + 摇头晃脑
 * · 由 Ldyb5ClientService 持有生命周期：onCreate 挂载，onDestroy 摘除
 * · 需 SYSTEM_ALERT_WINDOW（targetSdk 22 → 安装时自动授予）
 *
 * v0.3.10：
 * · 大小可调（AppSettings.islandSize，48~192dp），setSizeDp 即时生效
 * · 预览模式：设置页打开期间强制显示（哪怕待唤醒），方便拖动大小时间时观察
 *
 * v1.3.51：
 * · 透明度可调（AppSettings.islandAlpha，0.2~1.0），setAlpha 即时生效
 * · 位置可拖动：仅预览模式（设置页打开时）接收触摸 —— 平时保持
 *   NOT_TOUCHABLE，绝不抢下层的点击/通知栏下拉；拖完位置存
 *   AppSettings.islandX/Y（px），重启后保持
 */
public final class IslandOverlay {

    private static final String TAG = "L5CLIENT";
    // TYPE_APPLICATION_OVERLAY = 2038（API26+）；老系统用 TYPE_PHONE
    private static final int TYPE_OVERLAY = 2038;
    private static final String PREVIEW_STATE = "预览";

    private static RobotIsland view;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static Context ctx;
    private static String lastState = null;
    private static String lastLlm = null;
    private static boolean lastOn = false;
    private static boolean preview = false;   // v0.3.10：设置页预览中

    /** v1.3.51：拖动态 —— 按下时的触点与窗口原点 */
    private static float dragTouchX, dragTouchY;
    private static int dragOrigX, dragOrigY;
    private static boolean dragging;

    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final Runnable poll = new Runnable() {
        public void run() {
            tick();
            h.postDelayed(this, 500L);
        }
    };

    private IslandOverlay() {
    }

    /** 挂载悬浮窗（幂等；失败只警告不崩服务） */
    public static void start(Context c) {
        if (view != null) {
            return;
        }
        try {
            ctx = c.getApplicationContext();
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            view = new RobotIsland(ctx);
            int type = android.os.Build.VERSION.SDK_INT >= 26
                    ? TYPE_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            int size = dp(AppSettings.islandSize(ctx));
            lp = new WindowManager.LayoutParams(
                    size, size, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            // v1.3.51：位置可拖动 → 改用 TOP|START + 绝对坐标（原来是 TOP|CENTER）
            lp.gravity = Gravity.TOP | Gravity.START;
            applyStoredPos();
            view.setAlpha(AppSettings.islandAlpha(ctx));   // v1.3.51：透明度
            view.setOnTouchListener(touchListener);        // v1.3.51：拖动（仅预览期收得到）
            wm.addView(view, lp);
            lastState = null;
            lastLlm = null;
            h.post(poll);
            Log.i(TAG, "island overlay attached size=" + AppSettings.islandSize(ctx)
                    + "dp alpha=" + AppSettings.islandAlpha(ctx)
                    + " pos=(" + lp.x + "," + lp.y + ")");
        } catch (Throwable t) {
            Log.w(TAG, "island overlay attach failed: " + t);
            view = null;
        }
    }

    /**
     * v0.3.46：立刻刷新一次显隐（并重置轮询节奏）。
     * 静音/恢复要求图标当场消失或出现，不能等那 500ms 轮询。
     */
    public static void refreshNow() {
        h.removeCallbacks(poll);
        tick();
        h.postDelayed(poll, 500L);
    }

    /** 摘除悬浮窗 */
    public static void stop() {
        h.removeCallbacks(poll);
        try {
            if (view != null && wm != null) {
                wm.removeView(view);
            }
        } catch (Throwable ignore) {
        }
        view = null;
        wm = null;
        lp = null;
        preview = false;
    }

    /**
     * v0.3.10：调整图标大小（dp）—— 设置页拖动条实时调用。
     * 改 LayoutParams + updateViewLayout，RobotIsland.onDraw 按新尺寸重绘。
     * v1.3.51：用户没拖过位置时顺手重新居中（大小变了，老中点会偏）。
     */
    public static void setSizeDp(int dp) {
        if (view == null || wm == null || lp == null) {
            return;
        }
        try {
            boolean wasAuto = AppSettings.islandX(ctx) < 0;
            lp.width = dp(dp);
            lp.height = dp(dp);
            if (wasAuto) {
                centerX(lp);
            } else {
                clampPos(lp);
            }
            wm.updateViewLayout(view, lp);
        } catch (Throwable t) {
            Log.w(TAG, "island resize failed: " + t);
        }
    }

    /**
     * v1.3.51：调整图标透明度（0.2~1.0）—— 设置页拖动条实时调用。
     */
    public static void setAlpha(float a) {
        if (view == null) {
            return;
        }
        try {
            view.setAlpha(Math.max(0.2f, Math.min(1f, a)));
        } catch (Throwable t) {
            Log.w(TAG, "island alpha failed: " + t);
        }
    }

    /** v1.3.51：恢复默认位置（顶部居中）—— 设置页「重置位置」按钮 */
    public static void resetPosition() {
        if (view == null || wm == null || lp == null || ctx == null) {
            return;
        }
        try {
            AppSettings.clearIslandPos(ctx);
            centerX(lp);
            lp.y = dp(4);
            wm.updateViewLayout(view, lp);
            Log.i(TAG, "island pos reset -> top-center");
        } catch (Throwable t) {
            Log.w(TAG, "island pos reset failed: " + t);
        }
    }

    /** 当前窗口坐标（调试/日志用） */
    public static String posInfo() {
        return (lp == null) ? "na" : ("(" + lp.x + "," + lp.y + ")");
    }

    /**
     * v0.3.10：预览开关 —— 设置页打开时置 true：强制显示图标（哪怕待唤醒），
     * 关闭设置页置 false：恢复按 ScreenBus 状态自动显隐。
     * v1.3.51：预览同时切换可触摸性 —— 只有预览期（设置页里）才收拖动，
     * 平时 NOT_TOUCHABLE，绝不抢下层的点击和通知栏下拉。
     */
    public static void setPreview(boolean on) {
        if (preview == on) {
            return;
        }
        preview = on;
        if (view != null && wm != null && lp != null) {
            try {
                lp.flags = on
                        ? (lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        : (lp.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                wm.updateViewLayout(view, lp);
            } catch (Throwable t) {
                Log.w(TAG, "island touchable toggle failed: " + t);
            }
        }
        lastState = null;   // 迫使下一 tick 重新 apply
        h.post(poll);
    }

    /** v1.3.51：拖动支持 —— 仅预览期能收到事件（见 setPreview） */
    private static final View.OnTouchListener touchListener =
            new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent e) {
            if (wm == null || lp == null) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragTouchX = e.getRawX();
                    dragTouchY = e.getRawY();
                    dragOrigX = lp.x;
                    dragOrigY = lp.y;
                    dragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        return false;
                    }
                    lp.x = (int) (dragOrigX + (e.getRawX() - dragTouchX));
                    lp.y = (int) (dragOrigY + (e.getRawY() - dragTouchY));
                    clampPos(lp);
                    try {
                        wm.updateViewLayout(view, lp);
                    } catch (Throwable t) {
                        Log.w(TAG, "island drag update failed: " + t);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        dragging = false;
                        AppSettings.setIslandPos(ctx, lp.x, lp.y);
                        Log.i(TAG, "island dragged to (" + lp.x + "," + lp.y + ")");
                    }
                    return true;
                default:
                    return false;
            }
        }
    };

    /** 状态变化才 apply，500ms 轮询足够（ScreenBus 是 volatile 直读） */
    private static void tick() {
        if (view == null || ctx == null) {
            return;
        }
        String s = preview ? PREVIEW_STATE
                : (ScreenBus.state == null ? "" : ScreenBus.state);
        String llm = preview ? null : ScreenBus.llm;   // 预览固定「注视」表情
        boolean on = AppSettings.islandOn(ctx);
        if (!s.equals(lastState) || !String.valueOf(llm).equals(String.valueOf(lastLlm))
                || on != lastOn) {
            lastState = s;
            lastLlm = llm;
            lastOn = on;
            view.apply(s, llm, on);
        }
    }

    // ---------------- v1.3.51：位置工具 ----------------

    /** 应用存储的位置；未设置（-1）= 默认顶部居中 */
    private static void applyStoredPos() {
        int sx = AppSettings.islandX(ctx);
        int sy = AppSettings.islandY(ctx);
        if (sx < 0 || sy < 0) {
            centerX(lp);
            lp.y = dp(4);
        } else {
            lp.x = sx;
            lp.y = sy;
            clampPos(lp);
        }
    }

    /** 水平居中（用当前 lp.width） */
    private static void centerX(WindowManager.LayoutParams p) {
        p.x = (screenW() - p.width) / 2;
    }

    /** 夹在屏幕内（允许伸进状态栏区，但不整个跑出屏幕） */
    private static void clampPos(WindowManager.LayoutParams p) {
        int sw = screenW();
        int sh = screenH();
        if (p.x < -p.width / 2) {
            p.x = -p.width / 2;
        }
        if (p.x > sw - p.width / 2) {
            p.x = sw - p.width / 2;
        }
        if (p.y < -p.height / 2) {
            p.y = -p.height / 2;
        }
        if (p.y > sh - p.height / 2) {
            p.y = sh - p.height / 2;
        }
    }

    private static int screenW() {
        return ctx.getResources().getDisplayMetrics().widthPixels;
    }

    private static int screenH() {
        return ctx.getResources().getDisplayMetrics().heightPixels;
    }

    private static int dp(double v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5);
    }
}
