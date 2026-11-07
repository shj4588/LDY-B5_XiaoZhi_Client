package cn.workbuddy.ldyb5app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import android.os.SystemClock;
import android.view.View;

/**
 * v0.3.9：灵动岛小智机器人 —— 小智聆听/说话/连接时屏幕顶部中央的小图标。
 *
 * 全程序化绘制（无图片资源），「一个会动的小图标」：
 *   · v0.3.10：透明底无外框（按反馈去掉方形圆角底），只画机器人本身；
 *     白脸加一圈细深色描边，保证浅色主界面背景下也看得见
 *   · 机器人脸：白圆头 + 天线小球；会眨的眼睛；摇头晃脑（正弦摆动 + 轻颠）
 *   · 心情同步小智状态/回复内容：happy(开心) / smile(微笑) / sad(难过) /
 *     listen(注视) / think(思考)；嘴巴为静态弧线，不做开合动画
 * 空闲（待唤醒）自动隐藏；可在设置里整体关闭、拖动调大小。
 */
public class RobotIsland extends View {

    private static final long FRAME_MS = 33; // ≈30fps
    private static final int C_FACE = 0xFFFFFFFF;
    private static final int C_EYE = 0xFF16222C;
    private static final int C_BALL = 0xFFF7CF4E;
    private static final int C_TEAR = 0xFF6EC6FF;

    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();

    private String state = "";
    private String mood = "listen"; // happy/smile/sad/listen/think
    private boolean enabled = true;
    private boolean visible = false;
    private float appear = 0f;

    private final Runnable loop = new Runnable() {
        public void run() {
            tick();
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    public RobotIsland(Context c) {
        super(c);
        setVisibility(GONE);
    }

    /**
     * 刷新状态。v0.3.9 修正：小智连接期间（连接/聆听/说话之间来回切）保持显示，
     * 不重复弹出；只有断开（待唤醒/已退出）才消失。心情仍随状态/回复内容更新。
     */
    public void apply(String st, String llm, boolean on) {
        enabled = on;
        String s = st == null ? "" : st;
        // v0.3.46：静音后图标必须消失 —— 「已静音」不是会话状态，别被当成活跃。
        // （只按状态判：设置页预览传的是「预览」，仍能正常看到图标）
        boolean active = on && !s.isEmpty() && !"待唤醒".equals(s) && !"已退出".equals(s)
                && !"已静音".equals(s);
        String m = moodFor(s, llm);
        if (state.equals(s) && visible == active && mood.equals(m)) {
            return;
        }
        boolean pop = active && !visible; // 仅 GONE→VISIBLE 时弹一次
        state = s;
        mood = m;
        visible = active;
        if (active) {
            if (pop) {
                appear = 0f;
            }
            setVisibility(VISIBLE);
        } else {
            setVisibility(GONE);
        }
    }

    /** 心情：连接=思考；聆听=注视；说话=按回复内容分 开心/难过/微笑 */
    private static String moodFor(String state, String llm) {
        if (state.contains("连接")) {
            return "think";
        }
        if (!state.contains("说话")) {
            return "listen";
        }
        String t = llm == null ? "" : llm;
        if (t.contains("😢") || t.contains("😭") || t.contains("难过")
                || t.contains("抱歉") || t.contains("对不起") || t.contains("哭")) {
            return "sad";
        }
        if (t.contains("😄") || t.contains("😀") || t.contains("哈哈")
                || t.contains("开心") || t.contains("好呀") || t.contains("棒")
                || t.contains("喜欢")) {
            return "happy";
        }
        return "smile";
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(loop);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(loop);
        super.onDetachedFromWindow();
    }

    private void tick() {
        if (visible && appear < 1f) {
            appear = Math.min(1f, appear + 0.14f);
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (!visible) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        float t = SystemClock.uptimeMillis() / 1000f;

        // 弹出动画：缩放 + 上移淡入
        float ease = 1f - (1f - appear) * (1f - appear);
        float sc = 0.72f + 0.28f * ease;
        float dy = (1f - ease) * h * 0.3f;
        int save = cv.save();
        cv.scale(sc, sc, w / 2f, h / 2f);
        cv.translate(0f, dy);

        // ---- v0.3.10：透明底、无外框（方形底已按反馈去掉），只画机器人 ----
        float base = Math.min(w, h);
        float cx = w / 2f;
        float cy = h * 0.54f;   // v0.3.10：透明底无外框，略下移给天线留位
        // ---- 机器人脸（v0.3.10：无外框，按视图铺满）----
        float hs = base * 0.60f; // 头宽基准
        float half = hs / 2f;
        cy += (float) (Math.sin(t * 2.2f) * dp(1.5)); // 轻颠
        // 摇头晃脑：绕底部根摆动
        float swayHz = "happy".equals(mood) ? 2.6f : 1.6f;
        float sway = (float) Math.sin(t * swayHz) * ("think".equals(mood) ? 0.05f : 0.08f);
        cv.save();
        cv.rotate(sway * 57.3f, cx, cy + hs * 0.72f);

        // 天线 + 弹跳小球
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(dp(2));
        pen.setColor(C_FACE);
        cv.drawLine(cx, cy - half, cx, cy - half - dp(5), pen);
        float ballY = cy - half - dp(5) - dp(3.5f)
                - Math.abs((float) Math.sin(t * 3.2f)) * dp(2.5f);
        pen.setStyle(Paint.Style.FILL);
        pen.setColor(C_BALL);
        cv.drawCircle(cx, ballY, dp(3), pen);

        // 头（白圆角方 + 细深色描边：透明底下保证浅色背景也看得见）
        pen.setColor(C_FACE);
        cv.drawRoundRect(cx - half, cy - half, cx + half, cy + half,
                dp(8), dp(8), pen);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(dp(1.4));
        pen.setColor(C_EYE);
        cv.drawRoundRect(cx - half, cy - half, cx + half, cy + half,
                dp(8), dp(8), pen);

        // 眼睛（每 ~3.4 秒眨一次；happy 时弯成 ^；sad 时半垂）
        float blinkPhase = (t / 3.4f) % 1f;
        boolean closed = blinkPhase > 0.97f;
        float eyeR = hs * 0.13f;
        float eyeDy = -hs * 0.1f;
        float eyeDx = hs * 0.25f;
        pen.setColor(C_EYE);
        pen.setStyle(Paint.Style.FILL);
        if (closed) {
            float eh = eyeR * 0.16f;
            cv.drawRoundRect(cx - eyeDx - eyeR, cy + eyeDy - eh,
                    cx - eyeDx + eyeR, cy + eyeDy + eh, eh, eh, pen);
            cv.drawRoundRect(cx + eyeDx - eyeR, cy + eyeDy - eh,
                    cx + eyeDx + eyeR, cy + eyeDy + eh, eh, eh, pen);
        } else if ("happy".equals(mood)) {
            // 弯月眼 ^_^
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(dp(2.2f));
            pen.setStrokeCap(Paint.Cap.ROUND);
            curve.rewind();
            curve.moveTo(cx - eyeDx - eyeR, cy + eyeDy + eyeR * 0.4f);
            curve.quadTo(cx - eyeDx, cy + eyeDy - eyeR * 0.8f,
                    cx - eyeDx + eyeR, cy + eyeDy + eyeR * 0.4f);
            cv.drawPath(curve, pen);
            curve.rewind();
            curve.moveTo(cx + eyeDx - eyeR, cy + eyeDy + eyeR * 0.4f);
            curve.quadTo(cx + eyeDx, cy + eyeDy - eyeR * 0.8f,
                    cx + eyeDx + eyeR, cy + eyeDy + eyeR * 0.4f);
            cv.drawPath(curve, pen);
            pen.setStyle(Paint.Style.FILL);
        } else {
            float lookY = "think".equals(mood) ? -eyeR * 0.45f : 0f;
            float lookX = (float) Math.sin(t * 0.9f) * eyeR * 0.25f;
            if ("sad".equals(mood)) {
                lookY = eyeR * 0.3f;
            }
            cv.drawCircle(cx - eyeDx + lookX, cy + eyeDy + lookY, eyeR, pen);
            cv.drawCircle(cx + eyeDx + lookX, cy + eyeDy + lookY, eyeR, pen);
            // 高光
            pen.setColor(C_FACE);
            float pr = eyeR * 0.32f;
            cv.drawCircle(cx - eyeDx + lookX - pr * 0.6f,
                    cy + eyeDy + lookY - pr * 0.6f, pr, pen);
            cv.drawCircle(cx + eyeDx + lookX - pr * 0.6f,
                    cy + eyeDy + lookY - pr * 0.6f, pr, pen);
            pen.setColor(C_EYE);
        }
        // sad 时眼睛上方加两道耷拉眉
        if ("sad".equals(mood) && !closed) {
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(dp(1.6f));
            pen.setStrokeCap(Paint.Cap.ROUND);
            curve.rewind();
            curve.moveTo(cx - eyeDx - eyeR, cy + eyeDy - eyeR * 1.6f);
            curve.quadTo(cx - eyeDx, cy + eyeDy - eyeR * 1.1f,
                    cx - eyeDx + eyeR, cy + eyeDy - eyeR * 1.7f);
            cv.drawPath(curve, pen);
            curve.rewind();
            curve.moveTo(cx + eyeDx - eyeR, cy + eyeDy - eyeR * 1.7f);
            curve.quadTo(cx + eyeDx, cy + eyeDy - eyeR * 1.1f,
                    cx + eyeDx + eyeR, cy + eyeDy - eyeR * 1.6f);
            cv.drawPath(curve, pen);
            pen.setStyle(Paint.Style.FILL);
        }

        // ---- 嘴巴：静态心情弧线（不做动画）----
        float my = cy + hs * 0.22f;
        float mw = hs * 0.34f;
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(dp(2.2f));
        pen.setStrokeCap(Paint.Cap.ROUND);
        curve.rewind();
        if ("happy".equals(mood) || "smile".equals(mood)) {
            // 大笑嘴（happy 用填充 D 形）
            if ("happy".equals(mood)) {
                pen.setStyle(Paint.Style.FILL);
                curve.moveTo(cx - mw / 2f, my);
                curve.quadTo(cx, my + hs * 0.22f, cx + mw / 2f, my);
                curve.close();
                pen.setColor(C_EYE);
                cv.drawPath(curve, pen);
            } else {
                curve.moveTo(cx - mw / 2f, my - hs * 0.02f);
                curve.quadTo(cx, my + hs * 0.12f, cx + mw / 2f, my - hs * 0.02f);
                cv.drawPath(curve, pen);
            }
        } else if ("sad".equals(mood)) {
            curve.moveTo(cx - mw / 2f, my + hs * 0.08f);
            curve.quadTo(cx, my - hs * 0.1f, cx + mw / 2f, my + hs * 0.08f);
            cv.drawPath(curve, pen);
            // 泪滴
            pen.setColor(C_TEAR);
            pen.setStyle(Paint.Style.FILL);
            float tr = eyeR * 0.4f;
            float ty = cy + eyeDy + eyeR * 1.4f
                    + (float) Math.sin(t * 3f) * dp(1.2f);
            cv.drawCircle(cx - eyeDx, ty, tr, pen);
            pen.setColor(C_EYE);
        } else {
            // listen / think：抿嘴小线（think 略歪）
            if ("think".equals(mood)) {
                cv.drawLine(cx - mw / 2f, my, cx + mw / 4f, my + hs * 0.02f, pen);
            } else {
                cv.drawLine(cx - mw / 2f, my, cx + mw / 2f, my, pen);
            }
        }
        cv.restore();
        cv.restoreToCount(save);
    }

    private int dp(double v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5);
    }
}
