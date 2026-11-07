package cn.workbuddy.ldyb5app;

/**
 * ldyb5：屏幕 UI 状态总线 —— SessionController / XiaoZhiWs / MusicPlayer
 * 往这里写，ScreenUI 每 500ms 轮询读。全静态、volatile，零线程纠缠。
 */
public final class ScreenBus {

    private ScreenBus() {
    }

    /** 会话状态中文短语：待唤醒 / 连接中 / 聆听中 / 思考中 / 说话中
     *
     *  v0.3.25：初值由「待唤醒」改为「唤醒引擎启动中」—— 服务起来时 KWS 引擎
     *  还在异步初始化（sherpa-onnx + ORT），麦也是 on-demand 的，这段时间喊
     *  唤醒词**必然无效**。先显示「待唤醒」会让用户以为喊了没反应是故障
     *  （用户 2026-10-25 反馈：「app 刚启动的时候喊唤醒词是无效的」）。
     *  KWS 就绪且麦常驻后由 SessionController 切回「待唤醒」。
     */
    public static volatile String state = "唤醒引擎启动中";

    /** 最近一轮用户说的话（STT），null=还没有 */
    public static volatile String stt = null;

    /** 最近一轮小智的回复（llm 消息或 TTS sentence_start 文本） */
    public static volatile String llm = null;

    /** 音乐库就绪标志（索引拉到后置位，UI 列表才显示） */
    public static volatile boolean musicReady = false;

    /** UI 轮询 tick 计数（MusicPlayer 状态变化时 +1，UI 见到变化才刷新列表） */
    public static volatile int musicTick = 0;

    public static void setState(String s) {
        state = s;
    }

    public static void bumpMusic() {
        musicTick++;
    }
}
