package cn.workbuddy.ldyb5app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * v0.3.62：音量反馈悬浮条。
 *
 * 本机固件（携住定制 LDY-B5 / Android 8.1）没有系统音量面板（VolumeUI 被砍），
 * 任何 App 按音量键都无屏幕反馈。本类在小智界面按下音量键（或点界面内音量±按钮）
 * 时弹出一个仿系统的左侧音量竖条（喇叭图标 + 竖向填充），约 1.5s 后淡出。
 *
 * 图标用 Canvas 自绘（不依赖 emoji 字体，本机 emoji 渲染异常），保证清晰完整。
 * 复用 IslandOverlay 的 WindowManager 机制：
 *   · TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE
 *     → 浮在最上层、完全不抢下层触摸/通知栏
 *   · 由 ScreenUI 在音量键 / 音量按钮时触发，单例复用、按键重置淡出计时
 */
public final class VolBarOverlay {

    private static final String TAG = "L5CLIENT";
    private static final int TYPE_OVERLAY = 2038;          // TYPE_APPLICATION_OVERLAY (API26+)
    private static final int HIDE_MS = 1500;               // 显示后多久淡出

    private static WindowManager wm;
    private static Context ctx;
    private static View view;
    private static WindowManager.LayoutParams lp;
    private static VolIconView icon;
    private static View fill;
    private static int trackH;                             // 轨道像素高（构建时算好）

    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final Runnable hideR = new Runnable() {
        public void run() {
            if (view != null && view.getVisibility() == View.VISIBLE) {
                view.animate().alpha(0f).setDuration(280)
                        .withEndAction(new Runnable() {
                            public void run() {
                                if (view != null) view.setVisibility(View.GONE);
                            }
                        });
            }
        }
    };

    private VolBarOverlay() {
    }

    private static long lastAdjMs = 0;

    /**
     * 调整媒体音量并显示音量条。
     * 同一物理按键可能被 Activity(onKeyDown) 与 MediaSession(onVolumeKeyEvent) 两条路径重复派发，
     * 用 200ms 时间窗去重，保证一次按键只变一档、音量条只弹一次。
     */
    public static void adjustAndShow(Context c, int dir) {
        long now = System.currentTimeMillis();
        if (now - lastAdjMs >= 200) {
            try {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
            } catch (Throwable t) {
                Log.w(TAG, "vol adjust failed: " + t);
            }
            lastAdjMs = now;
        }
        show(c);
    }

    private static int dp(int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /** 触发显示：读取当前媒体音量并刷新竖条，重置淡出计时。 */
    public static void show(Context c) {
        try {
            if (ctx == null) ctx = c.getApplicationContext();
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) max = 1;
            if (view == null) build();
            if (view == null) return;

            boolean mute = cur == 0;
            icon.setMute(mute);
            int fh = (int) (trackH * cur / (float) max);
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            if (flp.height != fh) {
                flp.height = fh;
                fill.setLayoutParams(flp);
            }

            view.setAlpha(1f);
            view.setVisibility(View.VISIBLE);
            try {
                wm.updateViewLayout(view, lp);
            } catch (Throwable ignored) {
            }
            h.removeCallbacks(hideR);
            h.postDelayed(hideR, HIDE_MS);
            Log.i(TAG, "volbar show cur=" + cur + "/" + max);
        } catch (Throwable t) {
            Log.w(TAG, "volbar show failed: " + t);
        }
    }

    private static void build() {
        try {
            if (wm == null) wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            int type = Build.VERSION.SDK_INT >= 26
                    ? TYPE_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

            // 整体卡片（圆角半透明深色背景，确保包住全部内容）
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(dp(14), dp(16), dp(14), dp(16));
            GradientDrawable card = new GradientDrawable();
            card.setColor(0xCC000000);
            card.setCornerRadius(dp(18));
            root.setBackground(card);

            // 喇叭图标（Canvas 自绘，固定 38dp 见方，居中）
            icon = new VolIconView(ctx);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(38), dp(38));
            icon.setLayoutParams(ilp);
            root.addView(icon);

            // 竖条轨道
            trackH = dp(180);
            FrameLayout track = new FrameLayout(ctx);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(12), trackH);
            tlp.topMargin = dp(12);
            track.setLayoutParams(tlp);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x55FFFFFF);
            trackBg.setCornerRadius(dp(6));
            track.setBackground(trackBg);

            // 填充（底部对齐，高度按音量比例）
            fill = new View(ctx);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setColor(0xFFFFFFFF);
            fillBg.setCornerRadius(dp(6));
            fill.setBackground(fillBg);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(12), 0);
            flp.gravity = Gravity.BOTTOM;
            fill.setLayoutParams(flp);
            track.addView(fill);

            root.addView(track);

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            lp.x = dp(28);   // 距屏幕左边一点，仿系统左侧竖条
            wm.addView(root, lp);
            view = root;
            Log.i(TAG, "volbar overlay attached");
        } catch (Throwable t) {
            Log.w(TAG, "volbar attach failed: " + t);
            view = null;
        }
    }

    /** 自绘喇叭图标：白色喇叭，静音时叠加红色斜杠。不依赖 emoji 字体。 */
    private static class VolIconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean mute;

        VolIconView(Context c) {
            super(c);
        }

        void setMute(boolean m) {
            if (mute != m) {
                mute = m;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float s = h / 38f;                       // 以 38dp 为基准缩放
            p.reset();
            p.setAntiAlias(true);

            // 白色喇叭：音箱矩形 + 朝右声波三角
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.FILL);
            float bx = cx - 11 * s, bw = 8 * s;
            float by = cy - 6 * s, bh = 12 * s;
            cv.drawRect(bx, by, bx + bw, by + bh, p);
            Path horn = new Path();
            horn.moveTo(bx + bw, cy - 5 * s);
            horn.lineTo(bx + bw + 10 * s, cy - 12 * s);
            horn.lineTo(bx + bw + 10 * s, cy + 12 * s);
            horn.lineTo(bx + bw, cy + 5 * s);
            horn.close();
            cv.drawPath(horn, p);

            if (mute) {
                // 静音斜杠（红）
                p.setColor(0xFFFF4444);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3.2f * s);
                p.setStrokeCap(Paint.Cap.ROUND);
                cv.drawLine(bx - 4 * s, cy - 11 * s, bx + bw + 12 * s, cy + 11 * s, p);
            }
        }
    }
}
