package cn.workbuddy.ldyb5app;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话状态机 + 上行调度。
 *
 * v1.2.4 核心改造 —— 会话期开麦（connect-on-talk）：
 *
 *   实测结论（2026-10-03）：R1 的 4 麦 HAL 是独占资源，且原厂云知声引擎
 *   （com.phicomm.speaker.device，经 uni4micHalJNI 直开设备、绕过 AudioFlinger）
 *   靠它常驻做 KWS。v1.2.3 的常开 AudioRecord 会把原厂 KWS 永久挤瞎
 *   → 「小讯小讯」喊破喉咙也没反应；我们一死，它 1 秒内满血复活。
 *
 *   因此：
 *   - IDLE 期完全不碰 AudioRecord，麦归原厂（唤醒照常工作）；
 *   - 总线唤醒事件到达后才开麦 —— 与原厂自己的 ASR 录音竞争（它的云端
 *     已死，实测 ~1s 内报 609 放手），这里以 250ms 快速重试接管设备；
 *   - 会话结束（ws 关闭 / 无 TTS 超时）release 麦，还给原厂 KWS。
 *
 * 状态流转：
 *   IDLE ──bus wakeup──▶ CONNECTING ──hello ok──▶ LISTENING
 *     ▲                     │    │                  │ ▲
 *     │   15s 无 TTS / ws 关闭   │             TTS start │ TTS stop
 *     │                     │    ▼                  ▼ │
 *     └─────────────────────┴──────────────── TTS_PLAYING
 *
 * 上行要点：
 *  - 麦克风从唤醒瞬间才开始录，开麦到握手完成之间的音频先进 pending
 *    缓冲（上限 15s），握手完成后一次性补发 —— 替代 v1.2.3 的 500ms
 *    preroll 机制（常开麦时代才有"唤醒词前回溯"，按需开麦不需要）；
 *  - 音频帧裸 opus 上行，零帧头（C3 的 ASR 单字 bug 根因）；
 *  - TTS 播放期间暂停上行（防自环），barge-in 时补发 preroll。
 */
public final class SessionController implements
        AudioCapture.FrameListener,
        XiaoZhiWs.Listener,
        KwsEngine.OnKeyword,
        WakeupBridge.OnWakeup {

    private static final String TAG = "L5CLIENT";

    /** v0.3.0：单例引用（设置界面/服务层即时控制用） */
    public static volatile SessionController I;

    public enum State {IDLE, CONNECTING, LISTENING, TTS_PLAYING}

    private final AudioCapture mic;
    private final OpusCodec opus;
    // v1.2.3：player 可为 null（音频 HAL 卡死时降级启动），
    // 后续重试成功后经 setPlayer() 补挂。所有解引用必须判空。
    private volatile TtsPlayer player;
    private volatile KwsEngine kws;
    private final XiaoZhiWs ws;

    private volatile State state = State.IDLE;

    /** v1.3.44/45：**状态写入的唯一入口** —— 切状态时顺带把「会话中 / TTS 播放中」
     *  两个标志同步给 KWS。
     *
     *  为什么 KWS 要看状态：TTS 播放期喇叭回声灌进 mic 会把门顶开、把 KWS 喂满，
     *  纯白烧算力（zipformer 开销 ~= 音频时长），还有「TTS 自己被识别成唤醒词」
     *  的自环风险；所以 TTS 期暂停喂帧。
     *
     *  ⚠️ v1.3.44 的教训：当时把「整个会话期」都算作暂停范围，默认开。实机事故
     *  2026-10-05 21:00 —— 会话进 LISTENING 后「回 IDLE」出口迟迟不触发，KWS
     *  永久停摆，设备彻底失聪。**凡是能让 KWS 无限期停摆的状态依赖都是单点故障**，
     *  所以 v1.3.45 把暂停缩到只 TTS 期，并在 KwsEngine 里加 pauseMaxMs 兜底超时。
     */
    private void setState(State s) {
        state = s;
        boolean active = (s != State.IDLE);
        KwsEngine.sessionActive = active;
        KwsEngine.ttsPlaying = (s == State.TTS_PLAYING);
        // ldyb5：推给屏幕 UI
        ScreenBus.setState(uiName(s));
        // v1.3.45：只在「TTS 暂停/恢复」真的翻转时记一行，便于取证
        //（门控/底噪的实际重置由 KwsEngine.feed() 捕获翻转沿后自己做）
        if (KwsEngine.pauseInTts && KwsEngine.ttsPlaying != ttsLatch) {
            ttsLatch = KwsEngine.ttsPlaying;
            SessionLog.i("kws " + (ttsLatch ? "PAUSE(tts)" : "RESUME(tts)")
                    + " (state=" + s + ")");
        }
    }

    /** ldyb5：状态中文名（屏幕 UI 用） */
    private String uiName(State s) {
        switch (s) {
            case CONNECTING: return "连接中…";
            case LISTENING: return "聆听中";
            case TTS_PLAYING: return "说话中";
            // v0.3.25：IDLE 且 KWS 还没就绪 → 明确告诉用户「引擎正在启动」，
            // 而不是显示「待唤醒」让人以为喊了没反应是故障。
            // kwsWakeReady = KWS 引擎就绪 **且** 麦已常驻（真能听见唤醒词了）。
            default: return userMuted ? "已静音"
                    : (kwsWakeReady ? "待唤醒" : "唤醒引擎启动中");
        }
    }

    /**
     * v0.3.25：刷新屏幕上的状态文字。
     * 只在 IDLE 态才有意义（其余状态的状态名与 KWS 就绪无关）。
     */
    private void refreshIdleStateText() {
        if (state == State.IDLE) {
            ScreenBus.setState(uiName(state));
        }
    }

    /** v1.3.45：TTS 暂停状态去重，避免状态抖动时刷屏。 */
    private boolean ttsLatch = false;

    /** TTS 播放中是否抑制上行（防自环） */
    private volatile boolean suppressUplink = false;

    /** v0.3.22：KWS 打断时刻（SystemClock.elapsedRealtime）。onTtsStop 收到
     *  tts stop 时若落在该时刻起 4s 内，即判定为「打断导致的服务器中止」，
     *  走最小收尾（保持上行敞开、不再二次重发 listen start），避免把刚喂进去
     *  的「小智小智 + 后文」冲掉。一轮新 TTS / 新会话开始时清零。 */
    private volatile long kwsInterruptMs = 0L;

    /** v0.3.24：被丢弃的下行 TTS 帧计数（诊断用）。 */
    private volatile int ttsDroppedFrames = 0;

    /** v0.3.25：自建 KWS 引擎是否已初始化成功（sherpa-onnx 就绪）。 */
    private volatile boolean kwsEngineReady = false;

    /** v0.3.25：**真正能唤醒**的时刻标志。
     *
     *  用户反馈：「app 刚启动的时候喊唤醒词是无效的，可能是 kws 引擎还没有启动」。
     *  实测确认：KWS 是**异步初始化**的（startKwsAsync → 后台线程跑
     *  kws.init()，要加载 sherpa-onnx + ORT，耗时可观），且**麦在 KWS 就绪前
     *  是 on-demand 的**（不喂 KWS）→ 这段时间喊唤醒词必然无响应。
     *  但屏幕状态初值写死「待唤醒」（ScreenBus.state），用户以为能唤醒，
     *  喊了没反应 → 误判为故障。
     *
     *  本标志的含义是「**现在喊唤醒词能生效**」：KWS 引擎就绪 **且** 麦已常驻
     *  （acquireMicLoop 里 tryStartMic 成功那刻）才算数 —— 光 init 成功但麦
     *  还没到手，KWS 依然无音频可听。
     */
    private volatile boolean kwsWakeReady = false;

    /** v0.3.24：发出 abort 的时刻。
     *
     *  官方固件 main/application.cc:549-551 的下行音频回调有状态守卫：
     *      if (GetDeviceState() == kDeviceStateSpeaking) { 入解码队列 }
     *  —— 只有「正在说话」才收下行的 TTS 音频。我们此前 onTtsAudio 无条件
     *  p.push，abort 之后服务端补发的尾帧会**重新把声音顶起来**（用户实测：
     *  「kws 打断似乎没有停止服务器播报」）。本时刻用于把「打断后那一轮」的
     *  尾帧也一并挡掉 —— 服务端在收到 abort 后可能还会补发最后几个音频帧
     *  （LLM 侧已经在生成、音频侧还在推），那几帧同样不该播。
     *
     *  置位：handleWakeup 的 TTS_PLAYING 打断分支（发 abort 的同一刻）。
     *  清零：新一轮 TTS 开始（onTtsStart）/ 新会话开始。
     */
    private volatile long ttsAbortSentMs = 0L;

    /** v0.3.24：abort 之后仍需丢弃尾帧的时间窗（毫秒）。
     *  取 1500ms：覆盖 abort → 服务端确认 → 尾帧到达的往返。 */
    private static final long TTS_ABORT_DROP_MS = 1500L;

    /** 唤醒来源去重窗口。
     *
     * 双模唤醒下同一次唤醒可能被两条路径同时捕获：
     *   - 总线事件（WakeupBridge）
     *   - 自建 KWS 误命中（喂进 KWS 的「小讯小讯」被识别成「小智小智」）
     * 这里用 800ms 窗口去抖，避免一次唤醒开两次会话。
     */
    private volatile long lastWakeupMs = 0L;
    private static final long WAKEUP_DEBOUNCE_MS = 800L;

    // ================= v1.3.14：端到端延迟埋点 =================
    /** 本轮唤醒时刻（elapsedRealtime），所有 lat 日志以它为基准 */
    private volatile long wakeMs = 0L;
    private volatile long latFirstUplinkMs = 0L;
    private volatile long latSttMs = 0L;
    private volatile long latFirstTtsMs = 0L;
    /** v1.3.18：上一轮 TTS 结束时刻 —— 量「多轮周转」（说完 -> 服务器开口） */
    private volatile long lastTtsStopMs = 0L;

    /**
     * v1.3.14：取证落盘开关（默认关）。
     * 每帧 clone（dump + probe 各一次）+ 会话结束在主线程写 ~2MB 到 flash，
     * 纯粹是诊断用途，日常运行不需要；改成 flag 门控、默认关闭。
     *   files/uplink_dump  -> 写 files/r1-uplink.wav
     *   files/stereo_probe -> 写 files/r1-probe.wav
     */
    public static volatile boolean dumpEnabled = false;
    public static volatile boolean probeEnabled = false;

    // ================= v1.2.4：会话期开麦参数 =================

    /** 开麦重试间隔：原厂 ASR 云端已死（609），实测 ~1s 内放手，250ms 足够密 */
    private static final long MIC_RETRY_INTERVAL_MS = 250L;

    /** 单次开麦看门狗：AudioRecord 的 openRecord binder 可能卡（设备忙/HAL 死） */
    private static final long MIC_ATTEMPT_WATCHDOG_MS = 1500L;

    /** 开麦总预算：覆盖原厂 ASR 放手窗口 + 充分余量 */
    private static final long MIC_DEADLINE_MS = 20000L;

    /**
     * 唤醒/说话后无 TTS 的收会话时限。
     * v1.2.9：15s → 25s —— 20:03 实测原厂 USC 云端没死，它会攥麦 ~16s 等自己
     * 的云端超时（"服务器已经超时，客户端两次请求间隔太长"）才放手。我们 20s
     * 抢麦预算没耗尽，15s 看门狗却先把会话杀了（差 1.1s 错过到手的麦）。
     * 不变式：会话寿命 ≥ 抢麦预算 + 余量，否则「麦快到手时会话先死」。
     */
    private static final long NO_TTS_TIMEOUT_MS = 25000L;

    // ================= v1.3.15：延长连续对话（断开前提醒已于 v0.3.10 移除） =================

    /**
     * TTS 结束后的「续听窗口」——保持 listening 等用户接着说多久（毫秒）。
     * v1.3.14 及以前这里复用 NO_TTS_TIMEOUT_MS(25s)、外加 XiaoZhiWs 的 15s 空闲
     * 断开（15s 先到），实测用户稍一停顿（思考/找词）会话就没了。
     * 现改为 90s，可被 files/idle<N> 覆盖（服务层调用 setListenIdleMs）。
     * 意图：让官方服务器在 listening 空闲 ~70s 下发的 TTS 提醒、~80s 的会话关闭
     * 先发生 —— 用户体感 = 官方设备的连续对话。
     */
    private volatile long listenIdleMs = 90000L;

    /** 当前看门狗时长（日志用；armNoTtsWatchdog 时更新） */
    private volatile long watchdogMs = NO_TTS_TIMEOUT_MS;

    /** v1.3.15：服务层按 flag 覆盖续听窗口 */
    public void setListenIdleMs(long ms) {
        if (ms > 0) {
            listenIdleMs = ms;
        }
    }

    /**
     * v0.3.3：续听窗口 = 「60 秒空闲自动断开」开 ? 60s : 90s（参照 r1-client
     * v1.3.63 的同名实现）。开启时窗口**本身就是断开定时器** —— 必须先于
     * 服务器 ~63s 的空闲告别语出手（2026-10-25 01:58 实测 NAS 63s/66s 开口
     * farewell），否则退出语音先播、断开就成了马后炮。旧实现把 60s 条件藏在
     * 90s 定时器里，永远轮不到先出手 —— 就是"60 秒播报退出语音后断开"的根因。
     * 顺手同步 listenIdleMs，让日志口径一致。
     */
    private long idleWindowMs() {
        boolean on = AppSettings.idleDisconnect60(appContext);
        long v = on ? 60000L : listenIdleMs;
        if (v != listenIdleMs) {
            listenIdleMs = v;
        }
        return v;
    }

    // ================= v1.3.20：唤醒应答音 + 会话人声取证 =================

    /**
     * 唤醒应答音总时长（毫秒）。
     * ⚠️ 必须 ≥ TtsPlayer.startWatermarkMs(v0.3.58 起默认 600)：TtsPlayer.loop() 第一段是
     * 「凑够水位才 playing=true」，推入的 PCM 若短于水位，播放线程会一直空等、
     * 提示音**永不出声**（队列里只有 100ms，bufferedMs 永远 < 180）。
     */
    private static final int WAKE_ACK_MS = 260;
    /**
     * 应答音回声丢弃窗口（毫秒）—— **v1.3.22 起默认 0 = 不丢**。
     *
     * v1.3.20 原设 320ms，理由是「R1 无 AEC，喇叭里的『叮』会被四麦拾回、
     * 可能被服务器 VAD 当成用户开口」。**实测证伪，而且它本身就是丢字元凶。**
     *
     * 2026-10-04 17:54:49 那次首次唤醒（IDLE + 应答音）：
     *   prewake tail queued: 20 frames (1200ms)   ← 「小智小智」在这 1.2s 里
     *   wake ack cue played (260ms), mic echo dropped 320ms
     *   ... 上行时间线 = [preWake 1.2s] ⊕ [被删掉的 320ms] ⊕ [之后的实时帧]
     * 而连读的用户此刻正在说内容 —— 硬丢把 [唤醒时刻, +320ms] 整段删掉再
     * 把前后**直接粘起来**，等于把用户开口的第一个词从时间轴上剪走：
     * 服务器 STT 出来是垃圾「小智小智千鸡地。」，
     * `TTS start (heard: voicedFrames=6)` 说明整轮只有 360ms 人声过关，
     * 模型只能回「蛤？"千鸡地"是什么意思呀」。—— 用户体感就是「少听了两个字」。
     *
     * 为什么现在根本不需要丢：v1.3.21 把 prewakeFrames 提到 20（1.2s），
     * **唤醒词音频必然完整排在这声「叮」前面**。回声再响也只是这一轮对话里的
     * 一段噪声，不可能成为「唯一人声」去凭空造出一轮假对话 —— 硬丢窗口当初
     * 成立的前提（preWake=6，唤醒词没进流）已经不存在了。
     *
     * 万一现场仍出现「唤醒后凭空多一轮」（例如总线唤醒时 preWake 没抓到
     * 唤醒词），用 files/ackdrop<N>（0..600ms）把窗口加回来即可，无需改代码。
     */
    private static volatile long wakeAckEchoDropMs = 0L;

    /** v1.3.22：服务层解析 files/ackdrop<N> 后调用（0 = 不丢） */
    public static void setWakeAckEchoDropMs(long ms) {
        if (ms >= 0 && ms <= 600) {
            wakeAckEchoDropMs = ms;
        }
    }

    public static long getWakeAckEchoDropMs() {
        return wakeAckEchoDropMs;
    }

    /** 回声丢弃截止时刻（elapsedRealtime）；0 = 不丢（判定恒假） */
    private volatile long micDropUntilMs = 0L;
    /** v1.3.20：唤醒应答音总开关（files/wakeack_off 关闭）。关掉时**连丢帧窗口也不开**。 */
    public static volatile boolean wakeAckEnabled = true;

    // ================= v1.3.60：本地唤醒后交接拾音（唤醒词不上传） =================

    /**
     * 本地唤醒后交接拾音（唤醒词不上传）模式总开关。
     *
     * 开启后，本地 KWS 命中唤醒词唤醒小智，**不把唤醒词本身的语音上传**给服务端；
     * 唤醒之后用户说的话（命中后采集到的实时音频）照常上行，由小智云端去听。
     *
     * 实现要点（为什么这样就不会出现问候 TTS、也不会裁多裁少）：
     *   · **不发 `listen/detect`**。问候 TTS（「嘿，你好呀」）与随之而来的 2 秒耳聋窗口，
     *     都只由 `listen/detect` 命中唤醒词触发（`receiveAudioHandle.py` 的
     *     `just_woken_up` → `asyncio.sleep(2)` 丢弃上行）。本模式完全绕过 detect，
     *     只走 `listen start` 开流，因此既无问候、也无耳聋窗，唤醒后**不必等问候播完**
     *     就能直接开口——这正是与「官方 detect 节奏」的本质区别。
     *   · **丢弃的是唤醒词那段缓冲（preWake），不是对实时流做定时压制**。
     *     preWake 是命中前滚动保存的最近 1.2s 环形缓冲，里面就是触发 KWS 的唤醒词。
     *     交接模式下 `startSession()` 不再把 preWake 倒入 pending，于是握手后补发的
     *     只有「命中 → 握手完成」之间采集到的实时帧（= 用户唤醒后说的话）。这是一次
     *     **结构性裁剪**（按缓冲身份裁，而非按时间窗裁），不会像固定延时压制那样
     *     把指令开头也误删、或漏删唤醒词尾巴。
     *
     * 代价：唤醒词不再上送，用户需在唤醒词之后**稍微停顿一下**再开口，让云端只听到指令。
     * 若连读「小智小智+指令」而毫无停顿，指令最开头可能随 preWake 一起被裁掉
     * （最坏情况丢 ~0.5s，通常是唤醒词尾部那点静音/气声，无害）。
     *
     * 仅作用于「IDLE 起播唤醒」这一条路径；对话中打断（TTS_PLAYING / LISTENING 分支）
     * 早已不上传唤醒词且可无缝续讲，不受影响。
     *
     * 默认关（关 = 沿用原行为：唤醒词+指令一并即时上送，无缝）；设置页「唤醒词不上传」
     * 开关控制，或遗留 `files/detect_on` 也可开启。
     */
    public static volatile boolean serverListenMode = false;

    /**
     * v1.3.60：单次唤醒的「交接模式」武装标志。仅在「IDLE 起来的全新会话」且
     * `serverListenMode` 开启时置位，`startSession()` 与 `onHandshakeDone()` 各消费一次即清空。
     * 用于把「本次唤醒是否走交接模式」锁定在唤醒瞬间，避免握手期间开关被改导致状态漂移。
     */
    private volatile boolean serverListenArmed = false;

    /**
     * 本会话上行音频的「最大逐帧 RMS」+「有人声的帧数」。
     *
     * 用途（2026-10-04 17:27 实测教训）：一次「唤醒后没回复」的现场，连接侧全绿
     * （handshake 395ms、44 帧补发、400+ 帧上行、fail=0），服务器却 25s 一声不吭。
     * 翻 micTelem 才发现整段会话的 rms 平在底噪 220~230 —— **麦克风根本没听到人说话**。
     * 有这两个数，下次看一行日志就能定性：
     *   maxRms≈230 / voicedFrames=0        → 客户端没问题，是没人说话（或麦没拾到）
     *   maxRms 数千 / voicedFrames 数十，服务器仍沉默 → 才轮到怪服务器
     */
    private volatile int sessionMaxRms = 0;
    private volatile int sessionVoicedFrames = 0;
    /** 「有人声」判定门限（逐帧 RMS）。实测：底噪 220~230，说话 330~1400 */
    private static final int VOICE_FLOOR_RMS = 400;

    // ============ v1.3.47：上行回声闸门（自环的根除性修复） ============

    /**
     * v1.3.47：**不再靠「时间到了」放行上行，改靠「麦克风真的安静了」。**
     *
     * 根因（2026-10-05 21:25 用户实测「小智又会听到自己说的了」）：
     *   TTS 播完 -> `isDrained()` 真 -> `resumeUplinkTask` 放行上行。
     *   但 `isDrained()` 只判「软件队列空 + 距最后一次 push 超过 600ms」，
     *   **完全不管 AudioTrack 的 512ms 硬件缓冲、HAL 采集链延迟与房间余响** ——
     *   实测放行那一刻喇叭仍在响，上行把自家的 TTS 原样送回服务器。
     *   逐字铁证（本轮日志）：
     *     TTS 原文「在啦在啦，我人就在这儿呢，跑不掉的。」
     *     -> 下一轮 STT「跑不掉的。」        （一字不差）
     *   随后服务器回复 -> TTS -> 再被录 -> 无限自环（一轮约 2.6s，
     *   STT 依次是「在呢。」「在哦？」「跑不掉的。」，全是它自己刚说过的话）。
     *
     * 本机**无 AEC**（`audio cfg: src=UNI4MIC-HAL aec=false`），喇叭把四麦打到削顶
     * （`micTelem peak=32432`，满幅 32768）。所以唯一可靠的判据是**电平**：
     *   真人声 rms 330~5391（本会话实测上限 5391）
     *   TTS 回声 rms ≈13807、峰值削顶
     * 取 ECHO_RMS(8000) 落在两者之间：高于它只可能是喇叭，不可能是用户说话。
     *
     * 代价（明确记录）：TTS 刚停的那 0.5~1s 里如果用户立刻接话，开头会被吃掉
     * （闸门要连续 8 帧安静才开）；上限 ECHO_GATE_MAX_MS 保证最坏也只压 2s。
     * 这是无 AEC 设备做半双工必须付的账，比自环好得多。
     */

    /** 关掉回声闸门（调试用：`files/echogateoff`）。 */
    public static volatile boolean echoGateEnabled = true;

    /** 判定「喇叭在响」的逐帧 RMS（0..32767）。落在真人声上限与 TTS 回声之间。 */
    public static volatile int echoRms = 8000;

    /** 连续这么多帧低于 echoRms 才认为喇叭真停了（帧长 60ms -> 8 帧 = 480ms）。 */
    public static volatile int echoClearFrames = 8;

    /**
     * v1.3.48 ★ 闸门**最小强制压制时长**（毫秒）：TTS stop 后，无论麦克风多安静，
     * 都必须先压满这么久才允许进入"安静确认"。
     *
     * 根因（2026-10-05 21:34~21:36 实机取证，jp2 v1.3.47，diag/jp2log_now.txt）：
     *   · TTS 播放期      mic 电平  77~430   —— HAL 回声抑制在线，**假安静**；
     *   · TTS 停止后 0.7~4.7s mic 电平 479~1387 —— 抑制退线，TTS 回声漏进麦克风。
     *   v1.3.47 的判据是「连续 8 帧 rms<8000」→ 在 TTS stop 后 0.5s 就满足（因为
     *   播放期本来就安静）→ 闸门 557ms 即 OPEN —— 而真正的回声还要 0.7s 才现身。
     *   于是上行恢复窗口 100% 覆盖回声窗口，回声被原样送回服务器：
     *       TTS「听得到听得到，我一直都在呢。」(21:35:24.5~27.4 播)
     *       → 21:35:28.056 上行恢复 → 28.1~32.1 送出回声
     *       → 21:35:32.578 STT「听得到听得到，我一直都在呢。」  ← 逐字一致
     *       → 服务器再回 → 再被录 → 无限自环（本轮实测连续 6 轮）。
     *
     * 现在：gheld < echoGateHoldMs 期间一律不放行（echoQuietRun 强制清零），
     * 压满之后才认"安静"。0 = 关闭（回退 v1.3.47 语义）。files/echoholdms<N> 覆盖。
     */
    public static volatile long echoGateHoldMs = 1200L;

    /**
     * 闸门最长压住多久（兜底：环境持续吵闹也不能把上行永久焊死）。
     * v1.3.48：2000 -> 4000。实测回声拖尾最长到 TTS stop + 4.7s，2s 上限会被
     * 拖尾后半段（831/479）穿透；4s 与 TtsPlayer 的静音续播窗（ttshold 默认
     * 4000ms）同长，两者同步收尾。
     */
    public static volatile long echoGateMaxMs = 4000L;

    // ============ v1.3.50：TTS 期禁 STT（KWS 独占入口）+ TTS 后自动续听 ============

    /**
     * ★★ 「TTS 期静音」的**加强版**开关（默认**关**）。用户 2026-10-05 要求的架构：
     *   「TTS 的时候应该禁用 STT，只开 KWS；KWS 只听有没有『小智小智』；
     *     听到唤醒词才打断小智，把唤醒词和新的内容发给小智。」
     *
     * 注意：**默认路径已经满足这个要求**，用不着本开关 ——
     *   · TTS 播放期：`onTtsStart` 里 `suppressUplink = !bargeInUplink || echoDisabled`
     *     = true（bargeInUplink 默认关）→ 上行关闭 = STT 天然禁用；
     *   · KWS 全程在跑（kwsSess=none）→ 唤醒词随时可命中；
     *   · 命中唤醒词 → `handleWakeup` 打断：`TtsPlayer.abort()`（连 AudioTrack 硬件
     *     缓冲一起 flush）+ 放行上行 + `flushPreroll()` 补发「唤醒词 + 后文」。
     *
     * 本开关（true）额外的行为 = **TTS 结束后也不恢复上行**，必须再喊一次唤醒词
     * 才能续问。v1.3.49 默认开这个，用户实测体感是「每句话都要加『小智小智』开头」
     * （2026-10-05 21:55 反馈）—— 因为把「TTS 期禁听」错误地延长到了整个续听窗口。
     *
     * false（默认）= TTS stop 后走 v1.3.48 的路径：
     *   `resumeUplinkTask` 等本地播放排空（0.25~0.9s）→ `echoGate` 放行（强制压制期
     *   `echoGateHoldMs` 之后才认「安静」）→ 自动恢复上行，用户可以直接接话。
     *   防自环靠 `TtsPlayer.holdSilenceMs`：TTS 数据发完继续按实时速率写数字静音，
     *   把播放流钉住 → HAL 回声抑制不掉线。实测（21:53 TTS stop）：hold-silence
     *   4s 内 mic 稳在 70~430，4s 之后回到底噪 ~70，**无回声残留**。
     *
     * files/halfduplex 打开，files/halfduplex_off 关闭（默认即关）。
     */
    public static volatile boolean halfDuplex = false;

    /** 打断 TTS 后，上行恢复前最多再等这么久（覆盖 preroll 补发本身的排空）。 */
    public static volatile long bargeInMuteMs = 0L;

    /** 闸门是否生效中（true = 本帧不上行）。 */
    private volatile boolean echoGateOn = false;
    /** 闸门落下时刻（elapsedRealtime）。 */
    private volatile long echoGateArmedMs = 0L;
    /** 闸门内连续「安静」帧计数。 */
    private int echoQuietRun = 0;
    /** v1.3.48：闸门内取证日志节流（每 500ms 一行，看 mic 在压制期到底多响）。 */
    private volatile long echoProbeLastMs = 0L;

    /** 落闸：TTS stop 后调用（播放期由 suppressUplink 挡，闸门守的是「尾巴」）。 */
    private void armEchoGate() {
        if (!echoGateEnabled) {
            return;
        }
        echoGateOn = true;
        echoGateArmedMs = android.os.SystemClock.elapsedRealtime();
        echoQuietRun = 0;
        echoProbeLastMs = echoGateArmedMs;
    }

    /** 收闸（带原因，便于取证）。 */
    private void releaseEchoGate(String why) {
        if (!echoGateOn) {
            return;
        }
        long held = android.os.SystemClock.elapsedRealtime() - echoGateArmedMs;
        echoGateOn = false;
        echoQuietRun = 0;
        String m = "echoGate OPEN (" + why + ", held " + held + "ms)";
        Log.i(TAG, m);
        SessionLog.i(m);
    }

    // ============ v1.3.23：服务器「空闲告别」识别（僵尸会话的根除性修复） ============

    /**
     * 最近一次**用户说话**的时刻（elapsedRealtime）。
     *
     * 统计口径刻意很窄：只算 `state == LISTENING && !suppressUplink && rms > 语音门限`
     * 的帧 —— 排除 IDLE 期、排除 TTS 播放期（喇叭回声）、排除 TTS 结束后的 400ms
     * 上行恢复窗口（喇叭尾巴）。也就是说它约等于「服务器真的听到了人说话」。
     *
     * 用途：服务器**主动开口**且距离上次人声已经很久 → 那几乎必然是它在念
     * 「空闲告别语」，而官方服务器念完告别就**不再理会这条会话**了。
     */
    private volatile long lastUserVoiceMs = 0L;

    /**
     * 「服务器在讲空闲告别语」的静默阈值（秒）。默认 35s。
     * 可用 files/farewell<N> 覆盖（范围 10..120）。
     */
    private static volatile int farewellIdleSec = 35;

    public static void setFarewellIdleSec(int s) {
        if (s >= 10 && s <= 120) {
            farewellIdleSec = s;
        }
    }

    public static int getFarewellIdleSec() {
        return farewellIdleSec;
    }

    /** 本轮 TTS 被判定为「服务器空闲告别」→ 播完即结束会话，不再续听。
     *  v1.3.51 起 idle-farewell 改为静默关闭（见 onTtsStart），此标记实际已不再置位，
     *  保留字段与 onTtsStop 分支作为其它路径的防御（tts-stale 等不走这里）。 */
    private volatile boolean serverFarewell = false;

    /** v1.3.51：服务器空闲告别静默期 —— 丢弃期间到达的 TTS 音频帧（不播、不入队） */
    private volatile boolean farewellMute = false;

    /**
     * 「本轮服务器是在回应用户」的标记：收到 STT 置位，收到 tts stop 清零。
     * 为什么要它：只靠「静默多久」会把**慢回复**误判成告别 —— STT 之后如果
     * LLM/工具调用拖了 40s 才出 TTS（本机实测 GLM 9s + TTS 4s，带工具会更久），
     * 静默时长同样 ≥35s。有 STT = 服务器确实在回应我们，就不是告别。
     */
    private volatile boolean sttThisTurn = false;

    /**
     * 2026-10-04 18:19:42 实测（用户：「说的是小智小智明天下雨吗」，STT 只出「下雨吗？」）：
     *
     *   18:18:19 IDLE 唤醒 -> 新会话；18:18:20 STT「智小智。」-> 18:18:23 TTS stop
     *   18:19:25 服务器**主动**下发 TTS：「那我先不打扰你啦，有空再来找我聊天喔。」
     *            ← 这是官方服务器的「空闲告别」。它念完这句，这条会话就作废了。
     *   18:19:42 用户唤醒（此刻下行已静默 13.2s）-> 我们判「会话还活着，继续用，探测 7s」
     *            -> 用户整句话喂进死 socket；7s 后探测到期才发现是僵尸
     *   18:19:49 硬重启 + 回放影子；18:19:50.982 STT「下雨吗？」← 影子从唤醒时刻才开始
     *            录，话的前半截早就发给死会话了 -> 只剩尾巴
     *   ⇒ 唤醒到出字 8.1s，且**丢字**。用户体感「还是出现丢字了」。
     *
     * 根因：客户端把「服务器已告别」的会话当成健康会话，继续挂着等 90s（listenIdle）。
     * 修法：**服务器主动开口且上次人声已过去 farewellIdleSec 秒 → 判为告别语，
     * 播完即关会话回 IDLE**。这样下一次唤醒走的是 IDLE 全新会话路径
     * （应答音 + 预缓冲 1.2s 补发 + 实时帧），既不静默也不丢字。
     */
    private final Runnable farewellEndTask = new Runnable() {
        private int polls = 0;

        public void run() {
            if (state != State.LISTENING || !farewellActive) {
                polls = 0;
                return;
            }
            TtsPlayer p = player;
            boolean stillPlaying = (p != null) && p.isPlaying() && !p.isDrained();
            if (stillPlaying && polls < 20) {   // 最多等 20*300ms = 6s
                polls++;
                main.postDelayed(this, 300L);
                return;
            }
            polls = 0;
            farewellActive = false;
            String m = "server farewell playback done -> closing session"
                    + " (server already ended it; next wake = fresh session)";
            Log.i(TAG, m);
            SessionLog.i(m);
            ws.close("farewell");
        }
    };

    /** farewellEndTask 是否已排队（enterIdle / barge-in 要撤） */
    private volatile boolean farewellActive = false;

    /** CONNECTING 期间 pending 缓冲上限（60ms/帧，250 帧 = 15s） */
    private static final int MAX_PENDING_FRAMES = 250;

    /** 麦克风操作串行化线程：开麦重试循环与 release 都在这条线上排队，杜绝 start/stop 交错 */
    private final Handler micHandler;

    /** CONNECTING 期间攒下的音频帧（握手完成即补发，详见类注释） */
    private final ArrayList<byte[]> pending = new ArrayList<byte[]>();

    // ================= v1.3.2：flush 匀速节流 =================

    /**
     * 补发中标志：true 时 LISTENING 期的实时帧也改道进 pending 排队，
     * 保证服务器收到的时间线严格单调（积压音频按 60ms/帧实时速率先发，
     * 排队帧跟在后面），绝不与实时流交错。
     */
    private volatile boolean flushing = false;

    // ================= v1.3.3：上行音频自证（WAV dump） =================

    /**
     * 背景：官方服务器对 v1.3.x 会话「控制面秒回、对话面零 ASR」。服务器侧
     * 无法取证，只能自证「我们发出去的音频内容本身是否完好」。
     * 本会话所有准备上行的 PCM 攒内存（800 帧 = 48s 封顶），会话结束落盘
     * files/r1-uplink.wav。拉回 PC 试听/看波形：
     *   有人声 = 客户端发送内容无损 → 问题在服务器侧；
     *   垃圾/静音 = 客户端音频路径问题。
     */
    private final java.util.ArrayList<byte[]> uplinkDump = new java.util.ArrayList<byte[]>();

    // ================= v1.3.5：原始 stereo 取证（r1-probe.wav） =================

    /**
     * HAL stereo 原始帧（每帧 3840B），上限 300 帧 = 18s，会话结束落盘
     * files/r1-probe.wav（2ch WAV）。用途：v1.3.5 改 stereo 打开后，若下混
     * (L+R)/2 仍异常，可直接判定 L/R 两路各自有没有信号（哪路死/哪路活）。
     */
    private final java.util.ArrayList<byte[]> stereoProbe = new java.util.ArrayList<byte[]>();

    /** v1.3.6：与 pending 平行的 stereo 帧队列（补发帧也要进 probe 取证） */
    private final java.util.ArrayList<byte[]> stereoPending = new java.util.ArrayList<byte[]>();

    private void probeStereo(byte[] st) {
        if (!probeEnabled) {
            return; // v1.3.14：取证默认关（免每帧 clone）
        }
        synchronized (stereoProbe) {
            if (stereoProbe.size() < 300) {
                stereoProbe.add(st.clone());
            }
        }
    }

    private void dumpUplinkPcm(byte[] f) {
        if (!dumpEnabled) {
            return; // v1.3.14：取证默认关（免每帧 clone）
        }
        synchronized (uplinkDump) {
            if (uplinkDump.size() < 800) {
                uplinkDump.add(f.clone()); // onFrame 的 data 是复用缓冲，必须拷贝
            }
        }
    }

    private void writeUplinkDump() {
        byte[] pcmAll;
        synchronized (uplinkDump) {
            if (uplinkDump.isEmpty()) {
                return;
            }
            int total = 0;
            for (byte[] f : uplinkDump) {
                total += f.length;
            }
            pcmAll = new byte[total];
            int off = 0;
            for (byte[] f : uplinkDump) {
                System.arraycopy(f, 0, pcmAll, off, f.length);
                off += f.length;
            }
            uplinkDump.clear();
        }
        final byte[] pcm = pcmAll;
        // v1.3.5：stereo probe 一并落盘（可能为空 -> 不写）
        byte[] probePcm;
        synchronized (stereoProbe) {
            if (stereoProbe.isEmpty()) {
                probePcm = null;
            } else {
                int total = 0;
                for (byte[] f : stereoProbe) {
                    total += f.length;
                }
                probePcm = new byte[total];
                int off = 0;
                for (byte[] f : stereoProbe) {
                    System.arraycopy(f, 0, probePcm, off, f.length);
                    off += f.length;
                }
                stereoProbe.clear();
            }
        }
        final byte[] probe = probePcm;
        Thread t = new Thread(new Runnable() {
            public void run() {
                writeWavFile("r1-uplink.wav", pcm, 1, "uplink dump written");
                if (probe != null) {
                    writeWavFile("r1-probe.wav", probe, 2, "stereo probe written");
                }
            }
        }, "uplink-dump");
        t.setDaemon(true);
        t.start();
    }

    /** v1.3.5：通用 WAV 落盘（16bit PCM，rate 固定 AudioCapture.SAMPLE_RATE） */
    private void writeWavFile(String name, byte[] pcm, int ch, String logPrefix) {
        java.io.FileOutputStream fo = null;
        try {
            java.io.File out = new java.io.File(appContext.getFilesDir(), name);
            fo = new java.io.FileOutputStream(out);
            int dataLen = pcm.length;
            int rate = AudioCapture.SAMPLE_RATE;
            java.io.ByteArrayOutputStream hdr =
                    new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream d = new java.io.DataOutputStream(hdr);
            d.writeBytes("RIFF");
            d.writeInt(Integer.reverseBytes(36 + dataLen));
            d.writeBytes("WAVE");
            d.writeBytes("fmt ");
            d.writeInt(Integer.reverseBytes(16));
            d.writeShort(Short.reverseBytes((short) 1));      // PCM
            d.writeShort(Short.reverseBytes((short) ch));
            d.writeInt(Integer.reverseBytes(rate));
            d.writeInt(Integer.reverseBytes(rate * 2 * ch));
            d.writeShort(Short.reverseBytes((short) (2 * ch)));
            d.writeShort(Short.reverseBytes((short) 16));
            d.writeBytes("data");
            d.writeInt(Integer.reverseBytes(dataLen));
            d.flush();
            fo.write(hdr.toByteArray());
            fo.write(pcm);
            fo.close();
            fo = null;
            int durSec = dataLen / (2 * rate * ch);
            String m = logPrefix + ": " + name
                    + " (" + durSec + "s pcm, " + ch + "ch)";
            Log.i(TAG, m);
            SessionLog.i(m);
        } catch (Throwable tr) {
            Log.e(TAG, "wav write failed: " + name, tr);
        } finally {
            if (fo != null) {
                try {
                    fo.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ================= v1.3.1：常驻麦守卫修复 + 唤醒词尾巴透传 =================

    /**
     * v1.3.0 踩坑：acquireMicLoop 的循环条件是 state != IDLE（on-demand 语义），
     * 而 startAlwaysOnMic 恰在 IDLE 期调用 → 循环体一次不进 → attempts=0 放弃。
     * 本标记由 startAlwaysOnMic 置位：常驻模式下 IDLE 也允许进抢麦循环。
     */
    private volatile boolean alwaysOnRequested = false;

    /**
     * 唤醒前的环形缓冲帧数（60ms/帧）。
     *
     * v1.3.18：曾 20 -> 6（1.2s -> 0.36s）治「首响漏延」——但**真正的根因是
     * pacedFlushLoop 的 1.0x 匀速**（输入=输出 → 队列永不排空、服务器被永久
     * 甩在 backlog 之后），v1.3.18 已用「自适应追赶（2~3 帧/60ms）」根治。
     * 缩到 6 帧只是当时的保险动作，却留下一个**严重副作用**（2026-10-04 用户实测）。
     *
     * v1.3.21：6 -> 20（0.36s -> 1.2s）。**唤醒词压根没送达服务器的根因**：
     * 只喊「小智小智」不带后文时，能上行的只有 preWake 这 0.36s ——
     *   ① 0.36s 只够装唤醒词的尾巴（KWS 命中在词尾），音频本身不足以被识别；
     *   ② 更致命的是服务端门槛：`core/providers/asr/base.py` 里
     *      `if len(pcm_bytes) > 1920 * 15` —— **不足 900ms 的音频直接不送 ASR**。
     *      0.36s + 静音永远凑不够 15 帧，服务器于是「什么话都没收到」→ 静默。
     * 官方固件的做法（`application.cc` ContinueWakeWordInvoke, Kconfig
     * SEND_WAKE_WORD_DATA 默认 y）正是把**唤醒词音频**当会话第一条消息发上去：
     * `CustomWakeWord` 缓存 `Initialize(16000 * 2)` = **2 秒**音频，检测到唤醒词后
     * 一次性补发 + 再发一条 `listen/detect`。我们取 1.2s：够装 4 音节唤醒词
     * （「小智小智」~0.9s）+ 余量，又不至于把首轮 backlog 推回 v1.3.18 之前。
     * ⚠️ 代价：首轮补发多 0.84s 音频，自适应追赶下多花 ~0.3s 排空。
     *
     * v1.3.24：20 -> 34（1.2s -> 2.04s）。**1.2s 仍然不够，仍在啃唤醒词的头**：
     * 2026-10-04 18:43/18:44 实测（用户：「说的是小智小智明天下雨吗」/「明天星期几？」，
     * STT 出来分别是「智小智。」「志小智，明天星期几？」—— 每次都少开头那一个字）：
     *   · 18:43:31.150 `prewake tail queued: 20 frames (1200ms)` → STT「智小智。」
     *   · 18:44:54.067 `wake probe armed, shadow pre-seeded 20 preWake frames`
     *     → 18:45:03 STT「志小智，明天星期几？」
     * micTelem 对齐后可见：说话段在 18:44:52.3~55.3（≈3s），而 KWS 命中在 **18:44:54.065**。
     * preWake 只回溯到 18:44:52.87 → **前 0.37s（正好是第一个「小」）没进缓冲**，
     * 上行时间线变成「智小智」⊕「，明天星期几？」→ ASR 就是「志小智，明天星期几？」。
     * 即：**唤醒词实际时长 ≈1.5s（语速慢时更长），1.2s 环形缓冲在 KWS 命中晚于词尾时
     * 必然啃掉词头**。官方缓存正是 **2s**（`Initialize(16000 * 2)`），我们 2.04s 对齐并略超。
     * ⚠️ 代价：首轮补发多 0.84s 音频，自适应追赶（2~3 帧/60ms）下多花 ~0.3s 排空。
     * 可用 files/prewake<N>（2..60）覆盖。
     */
    private static volatile int prewakeFrames = 34;

    /** v1.3.18：服务层解析 files/prewake<N> 后调用 */
    public static void setPrewakeFrames(int n) {
        if (n >= 2 && n <= 60) {
            prewakeFrames = n;
        }
    }

    /** 当前 prewake 帧数（供日志） */
    public static int getPrewakeFrames() {
        return prewakeFrames;
    }

    /**
     * IDLE 期（常驻麦）滚动缓冲：唤醒前最近 prewakeFrames 帧的音频。
     * 唤醒 -> startSession 时整体倒进 pending，与 CONNECTING 期帧一起在
     * 握手后补发 —— 实现「唤醒词+紧跟的话一起发给服务器」，不用等。
     */
    private final java.util.ArrayDeque<byte[]> preWake = new java.util.ArrayDeque<byte[]>();

    private final Handler main = new Handler(Looper.getMainLooper());

    // ================= v1.2.15：原厂 USC 错误提示静音 =================

    /**
     * 会话结束后的媒体流静音时长。
     * 实测（2026-10-03 20:45/20:52）：还麦后 ~5.6s，原厂 USC 引擎发现自己
     * 云端凭证被拒（authStatus status=230 Mcode 校验失败），离线 TTS 现说
     * 一句「网络连接失败，请检查」——走 STREAM_MUSIC，端点配置烘焙在 APK
     * 里且无音频文件可替换，修配置的路全堵死。
     * 客户端 band-aid：还麦瞬间把媒体流静音罩住，下次唤醒立即解除。
     * v1.2.16：20s → 45s —— 实测 USC 恢复期偶发二次播报（30s 左右才出现），
     * 20s 盖不住；唤醒即解除逻辑保证延长无副作用（下次对话立即恢复出声）。
     */
    private static final long USC_PROMPT_MUTE_MS = 45000L;

    private final Context appContext;

    /** 当前媒体流是否处于我们设置的静音状态（防重复 mute，AudioService 按次计数） */
    private boolean uscMuted = false;

    // ================= v1.2.16：TTS 播放中 barge-in（服务器 VAD 打断） =================

    /**
     * TTS 播放中是否继续上行音频，让服务器 VAD 检测人声并打断当前回复。
     *
     * v1.2.16 引入时默认 true。**v1.3.16 改为默认 false**：
     * R1 没有 AEC（实测开系统 AEC 会把近端人声一起压掉，所以是关的），
     * TTS 播放中上行 = 把我们自己喇叭里的 TTS 声原样喂回服务器 ——
     * 服务器 VAD 很容易据此误判「用户插话」→ 打断/重合成 → 听感就是
     * 「卡顿 + 丢字 + 话没说完就没声」。
     * 打断改由自建 KWS 唤醒词承担（TTS 中喊「小智小智」→ handleWakeup 走
     * barge-in 分支），既可靠又不会自环。
     * files/bargein_on 存在 → 恢复「直接说话即打断」的旧行为。
     */
    public static volatile boolean bargeInUplink = false;

    /** TTS 持续短于此值就被打断 → 判定为喇叭自环误触发 */
    private static final long ECHO_LOOP_MIN_TTS_MS = 2000L;

    /** 自环熔断标志：置位后本进程内 TTS 播放期停上行（回退 v1.2.15 行为） */
    private volatile boolean echoDisabled = false;

    /** 当前轮 TTS 开始时刻（算持续时间用） */
    private volatile long ttsStartElapsed = 0L;

    // ================= v1.3.16：僵尸会话自愈 =================

    /**
     * 最近一次收到**任何**服务器下行帧的时刻（elapsedRealtime）。
     * 用途：唤醒时判断手里这条会话是不是已经死了。
     */
    private volatile long lastDownlinkMs = 0L;

    /** 最近一次收到 TTS 音频帧的时刻（TTS 流静默看门狗用） */
    private volatile long lastTtsFrameMs = 0L;

    /**
     * TTS 流静默阈值：进入 TTS_PLAYING 后连续这么久既收不到 TTS 音频、
     * 也等不到 tts stop → 判定这路 TTS 流已经结束（哪怕服务器没说）。
     */
    private static final long TTS_STALE_MS = 15000L;

    /**
     * v1.3.17：判定「TTS 流还活着」的最近帧窗口。
     *
     * 唤醒打在 TTS_PLAYING 上时要二分：TTS 真在出声（barge-in）还是流已经断了
     * （服务器发完告别语却不下发 tts stop 的僵尸 TTS）？旧实现用
     * player.isPlaying() —— 但它在越过 250ms 水位之前恒为 false，TTS 刚起步就被
     * 唤醒会被误判成僵尸，白重启一次会话。改用「最近一帧 TTS 音频到达时刻」判活，
     * 与播放器的水位状态解耦。
     */
    private static final long TTS_ALIVE_MS = 2000L;

    /**
     * v1.3.17：LISTENING 态下「服务器仍在推 TTS 音频」的判据窗口。
     * 用途：barge-in 后服务器可能还在往下塞 TTS 帧（它没真的停）；此时麦克风
     * 拾到喇叭声可能再次触发 KWS 唤醒词，若照样硬重启就会来回抽搐。这个短窗口
     * 专门吞掉这类「TTS 尾巴误命中」——真正的用户唤醒，距离最后一帧 TTS 音频
     * 通常已过去数秒（实测 2.4~3.3s），不会被它误吞。
     */
    private static final long TTS_STREAM_QUIET_MS = 1000L;

    /** 置位后 enterIdle 收尾时不停在 IDLE，而是立刻开一条新会话 */
    private volatile boolean restartPending = false;

    /** v1.3.16：任何服务器下行都刷新「会话存活」时间戳 */
    private void touchDownlink() {
        lastDownlinkMs = android.os.SystemClock.elapsedRealtime();
    }

    // ================= v1.3.19：唤醒响应探测（僵尸会话快速自愈） =================

    /**
     * 背景（2026-10-04 17:10 实测 session log）：服务器把告别语讲完、**甚至发了
     * tts stop** 之后就静默了 —— 它那一侧的会话已经作废，但 ws 还开着、我们这边
     * 状态仍是 LISTENING、下行静默才 2s。「健康会话」与「已死会话」在本地**没有
     * 可靠的静态判据**：唯一判据是「服务器会不会对新语音作出反应」。
     *
     * 走过的两条弯路：
     *   v1.3.16 用「下行静默 > 75s」猜 → 太慢，用户要干等 80s；
     *   v1.3.17 改成「LISTENING 态唤醒一律硬重启」→ 太武断，每一次唤醒都把健康
     *   会话拆掉重建，多轮交互全部多背 5~7s，而且频繁重连本身会失败
     *   （17:10:38 / 17:10:59 / 17:11:19 连续三次重启零回复）。
     *
     * v1.3.19 的取舍：**默认会话是活的**。唤醒时不再拆会话，直接把上行放行、让它
     * 听到用户说话；同时装一个「响应探测」——把唤醒后真正上行出去（已发给这条
     * 会话）的音频在本机留一份影子，若 wakeProbeSec 秒内服务器**毫无反应**
     * （既无 STT 也无 TTS），才判定僵尸、硬重启，并把影子音频回放进新会话 ——
     * 用户说过的话不用重说。
     */
    private volatile boolean probeArmed = false;

    /** startSession 消费：本次启动是否把影子音频（而非 preWake）倒进 pending */
    private volatile boolean replayShadow = false;

    /** 探测窗口内实际送出的上行 PCM（60ms/帧） */
    private final java.util.ArrayList<byte[]> shadowBuf = new java.util.ArrayList<byte[]>();
    /** 影子缓冲上限（200 帧 = 12s，16k mono = 384KB） */
    private static final int SHADOW_MAX_FRAMES = 200;

    /** 影子缓冲里出现的最大样本绝对值 —— 判「这段里到底有没有人声」 */
    private volatile int shadowPeak = 0;

    /**
     * 「影子里确实有人声」的峰值门限（16bit 满量程 32767；本机实测上行已 ×16 增益，
     * 说话 peak ≈ 600~700、静默 ≈ 100 量级）。
     * 低于它说明这声唤醒是 TTS 尾巴/环境噪声的 KWS 误命中、或者用户根本没开口 ——
     * 那就不要为了它去重启会话。
     */
    private static final int PROBE_VOICE_PEAK = 260;

    /**
     * 探测窗口秒数（files/wakeprobe<N> 覆盖，2..20；默认 **3s**）。
     *
     * v1.3.19 定 7s，v1.3.24 收到 3s：健康会话对「唤醒词」的响应极快 —— 实测
     * 18:43:31.145 唤醒 → 18:43:32.760 STT，`wake->STT = 1618ms`。也就是说
     * 「服务器会回话」这件事 2s 内就能确认；7s 纯属空等（18:44:54 唤醒的那次
     * 白白等到 18:45:01，加上重连与回放，用户 `wake->STT = 8953ms`）。
     * 3s 给了近 2 倍余量，同时把僵尸会话的恢复时间从 ~9s 压到 ~4s。
     */
    private static volatile int wakeProbeSec = 3;

    public static void setWakeProbeSec(int s) {
        if (s >= 2 && s <= 20) {
            wakeProbeSec = s;
        }
    }

    public static int getWakeProbeSec() {
        return wakeProbeSec;
    }

    private void armWakeProbe() {
        clearShadow();
        // v1.3.23：影子先「预置」上唤醒前的 preWake 环形缓冲（默认 20 帧 = 1.2s）。
        // 原实现只从「唤醒时刻」开始录 —— 而连读时用户的前半句恰恰在唤醒**之前**
        // （KWS 要等「小智小智」念完才命中），于是 2026-10-04 18:19 那次回放出来的
        // 只有尾巴：说「小智小智明天下雨吗」，回放后 STT 只出「下雨吗？」。
        // 这里只**复制**不清空（探测若被解除，preWake 还要留给下一次 IDLE 唤醒用）。
        int seeded = 0;
        synchronized (preWake) {
            for (byte[] f : preWake) {
                shadowAdd(f, f.length);
                seeded++;
            }
        }
        probeArmed = true;
        main.removeCallbacks(wakeProbeTask);
        main.postDelayed(wakeProbeTask, wakeProbeSec * 1000L);
        if (seeded > 0) {
            String m = "wake probe armed (" + wakeProbeSec + "s), shadow pre-seeded with "
                    + seeded + " preWake frames (" + (seeded * 60) + "ms)";
            Log.i(TAG, m);
            SessionLog.i(m);
        }
    }

    private void disarmWakeProbe() {
        probeArmed = false;
        main.removeCallbacks(wakeProbeTask);
    }

    private void clearShadow() {
        synchronized (shadowBuf) {
            shadowBuf.clear();
        }
        shadowPeak = 0;
    }

    /** v1.3.20：一帧 16bit 小端 PCM 的均方根（0..32767）。960 样点/帧，代价可忽略。 */
    private static int frameRms(byte[] d, int len) {
        int n = len / 2;
        if (n <= 0) {
            return 0;
        }
        long acc = 0L;
        for (int i = 0; i + 1 < len; i += 2) {
            short v = (short) ((d[i] & 0xff) | (d[i + 1] << 8));
            acc += (long) v * v;
        }
        return (int) Math.sqrt((double) acc / n);
    }

    /** v1.3.20：本会话人声统计（一行，直接嵌进各种收尾/进展日志） */
    private String voiceStats() {
        return " maxRms=" + sessionMaxRms + " voicedFrames=" + sessionVoicedFrames;
    }

    /**
     * v0.3.0：唤醒亮屏；v0.3.33 改为透明临时 Activity 点亮（FLAG_TURN_SCREEN_ON）。
     * 旧实现用 SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP，但很多厂商 ROM
     * 会屏蔽非系统应用唤醒（用户实测「息屏后再喊小智屏幕不亮」）。改成启动一个
     * 全透明 Activity：FLAG_TURN_SCREEN_ON 走系统级点亮、不受厂商 wake-lock 策略影响，
     * 且透明不显示任何内容，不会把小智界面顶到锁屏之上（用户只要点亮）。
     */
    private void wakeScreen() {
        wakeScreenNow(appContext);
    }

    /** v0.3.33：唤醒亮屏 + 「打开屏幕」语音指令共用的入口。 */
    public static void wakeScreenNow(android.content.Context ctx) {
        if (ctx == null || !AppSettings.wakeScreen(ctx)) {
            return;
        }
        try {
            // v0.3.34：不再用 pm.isInteractive() 早退。部分 ROM 在 input keyevent 26
            // 关屏后 isInteractive() 仍报告 true（display off 但 device interactive），
            // 会误判「已经亮着」而跳过点亮（用户 2026-10-26 实测「打开屏幕没起效」）。
            // 透明 Activity 在屏已亮时启动也只是不可见地一闪，无副作用，故始终尝试。
            android.content.Intent i = new android.content.Intent(ctx, WakeScreenActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            Log.i(TAG, "wake screen via WakeScreenActivity");
        } catch (Throwable t) {
            Log.w(TAG, "wake screen failed", t);
        }
    }

    /**
     * v0.3.0：设置「启用小智」开关的即时应用。关闭时 KWS 停喂
     * （KwsEngine.disabled，省一个核），活动会话立即收掉；重新打开无副作用，
     * KWS 下一帧自动恢复。
     */
    public void applyVoiceEnabled(boolean on) {
        KwsEngine.disabled = !on;
        String m = "voice " + (on ? "enabled" : "disabled") + " (setting)";
        Log.i(TAG, m);
        SessionLog.i(m);
        if (!on) {
            // v0.3.45：关语音时把正在播的 TTS 也当场掐掉，别让"关了还在说话"
            TtsPlayer p = player;
            if (p != null) {
                p.abort();
            }
        }
        if (!on && state != State.IDLE) {
            try {
                ws.close("voice-disabled");
            } catch (Throwable t) {
                Log.w(TAG, "close on voice-disable threw", t);
                setState(State.IDLE);
            }
        }
        // v0.3.45：从设置页重新打开语音时，若麦在静音期间被释放过要重新拿回来
        if (on && !userMuted && !mic.isRunning()) {
            Log.i(TAG, "voice enabled (setting) -> mic re-acquiring for KWS duty");
            startAlwaysOnMic();
        }
    }

    // ---------------- v0.3.45：界面「静音」开关 ----------------

    /**
     * v0.3.45：屏幕上的「静音」开关（运行时态，不落盘，重启即恢复收音）。
     *
     * 与设置里的「启用小智」分工：设置项是长期策略（关掉后连按钮也恢复不了
     * 语音，需去设置里开）；本开关是临时闭嘴 —— 按下后当前会话与 TTS 立即
     * 停止、KWS 停识别、**麦克风真正释放**（不再收音），再按一次重新开麦。
     */
    public static volatile boolean userMuted = false;

    /** 当前是否「不收音」（界面按钮文案统一用它，含设置项的影响） */
    public static boolean isMutedNow(android.content.Context c) {
        return userMuted || !AppSettings.voiceEnabled(c);
    }

    public void setUserMuted(final boolean on) {
        userMuted = on;
        String m = "user mute = " + on;
        Log.i(TAG, m);
        SessionLog.i(m);
        if (on) {
            restartPending = false;          // 别让 close 顺手又开一条新会话
            TtsPlayer p = player;
            if (p != null) {
                p.abort();                   // 立即静音：清队列 + flush AudioTrack
            }
            if (state != State.IDLE) {
                try {
                    ws.close("user-mute");
                } catch (Throwable t) {
                    Log.w(TAG, "close on mute threw", t);
                    setState(State.IDLE);
                }
            }
            KwsEngine.disabled = true;       // 不再识别唤醒词
            KwsEngine.sessionActive = false;
            KwsEngine.ttsPlaying = false;
            alwaysOnRequested = false;       // 阻止 acquireMicLoop 继续重试开麦
            kwsWakeReady = false;
            micHandler.post(new Runnable() {
                public void run() {
                    try {
                        if (mic.isRunning()) {
                            mic.stop();
                            Log.i(TAG, "mic RELEASED (user mute) -> no more listening");
                            SessionLog.i("mic RELEASED (user mute)");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "mic.stop on mute threw", t);
                    }
                }
            });
            main.post(new Runnable() {
                public void run() {
                    if (state == State.IDLE) {
                        ScreenBus.setState("已静音");
                    }
                    // v0.3.46：小智图标同步消失（不等 500ms 轮询）
                    IslandOverlay.refreshNow();
                }
            });
        } else {
            KwsEngine.disabled = !AppSettings.voiceEnabled(appContext);
            if (AppSettings.voiceEnabled(appContext)) {
                Log.i(TAG, "user unmute -> mic re-acquiring for KWS duty");
                SessionLog.i("user unmute -> mic re-acquiring");
                startAlwaysOnMic();
            }
            main.post(new Runnable() {
                public void run() {
                    IslandOverlay.refreshNow();
                }
            });
        }
    }

    // ---------------- v0.3.0：音乐为小智让路 ----------------

    /** 让路期间记住酷我在放（TTS 结束后恢复） */
    private boolean yieldKuwoPlaying = false;

    private void musicYieldStart() {
        if (!AppSettings.yieldMusic(appContext)) {
            return;
        }
        if (!yieldKuwoPlaying && KuwoController.isPlaying()) {
            yieldKuwoPlaying = true;
            KuwoController.playPause();   // MEDIA_PLAY_PAUSE：播→停
            Log.i(TAG, "music yield: kuwo paused for TTS");
            SessionLog.i("music yield: kuwo paused for TTS");
        }
    }

    private void musicYieldEnd() {
        if (!yieldKuwoPlaying) {
            return;
        }
        yieldKuwoPlaying = false;
        // 800ms 后恢复：等酷我的 PLAYER_STATUS(暂停) 广播落地，避免 PLAY_PAUSE
        // 打在它还没处理完的上一个状态上。
        main.postDelayed(new Runnable() {
            public void run() {
                // v0.3.12：若用户在本轮会话里改听了电台（互斥已把酷我压掉），
                // 不要恢复酷我 —— 那会通过互斥钩子把电台又停掉，反用户意图。
                boolean radioOn = MusicPlayer.I != null && MusicPlayer.playing;
                if (!yieldKuwoPlaying && KuwoController.isPlaying() == false
                        && AppSettings.yieldMusic(appContext) && !radioOn) {
                    KuwoController.playPause();   // 停→播
                    Log.i(TAG, "music yield: kuwo resumed");
                    SessionLog.i("music yield: kuwo resumed");
                }
            }
        }, 800L);
    }

    /**
     * v1.3.20：播放唤醒应答音，并开一个回声丢弃窗口。
     * 只有「从 IDLE 起来的全新会话」才播 —— TTS 播放中的 barge-in、
     * 探测判僵尸后的硬重启（用户已经在说话了，影子音频正等着回放）都不该插一声「叮」。
     */
    private void playWakeAck() {
        if (!wakeAckEnabled || !AppSettings.cueOn(appContext)) {
            return;   // files/wakeack_off 或设置关：不出声，也就没有回声要丢
        }
        long now = android.os.SystemClock.elapsedRealtime();
        // v1.3.22：默认 0 —— 一帧都不丢（硬丢会剪断连读的时间线，见 wakeAckEchoDropMs 注释）
        micDropUntilMs = (wakeAckEchoDropMs > 0L) ? now + wakeAckEchoDropMs : 0L;
        TtsPlayer p = player;
        if (p == null) {
            // 降级中（音频 HAL 死过）：不出声，无回声，窗口也就没有意义
            Log.i(TAG, "wake ack skipped (no player), mic echo drop "
                    + wakeAckEchoDropMs + "ms");
            return;
        }
        p.push(buildWakeAckPcm());
        String m = "wake ack cue played (" + WAKE_ACK_MS + "ms), mic echo dropped "
                + wakeAckEchoDropMs + "ms";
        Log.i(TAG, m);
        SessionLog.i(m);
    }

    /**
     * 合成唤醒应答音 PCM（16kHz mono）：单声 1046Hz「叮」160ms + 静音补齐到
     * WAKE_ACK_MS。尾部静音既做 5ms 淡出后的保护余量，也保证长度越过
     * TtsPlayer 的启动水位（否则播放线程永远不启动，见 WAKE_ACK_MS 注释）。
     */
    private static byte[] buildWakeAckPcm() {
        int rate = AudioCapture.SAMPLE_RATE;
        int toneMs = 160;
        // v0.3.2：总长必须越过 TtsPlayer.startWatermarkMs（v0.3.58 起默认 600，
        // 即总长 660ms：160ms「叮」+ 500ms 静音补齐；静音只用于跨过水位，
        // 不拖慢用户听感）。旧值 260 < 水位，播放线程永远不启动，「叮」从未出过声。
        int totalMs = Math.max(WAKE_ACK_MS, TtsPlayer.startWatermarkMs + 60);
        int n = rate * toneMs / 1000;
        byte[] out = new byte[rate * totalMs / 1000 * 2];
        int idx = 0;
        int fade = rate / 200;   // 5ms
        for (int i = 0; i < n; i++) {
            double v = Math.sin(2.0 * Math.PI * 1046.0 * i / rate);
            double env = 1.0;
            if (i < fade) {
                env = (double) i / fade;
            } else if (i > n - fade) {
                env = (double) (n - i) / fade;
            }
            short s = (short) (v * env * 0.30 * 32767);
            out[idx++] = (byte) (s & 0xff);
            out[idx++] = (byte) ((s >> 8) & 0xff);
        }
        return out;   // 余下为 0（静音）
    }

    /** 影子捕获：探测窗口内真正送出的上行 PCM 留档 + 量峰值 */
    private void shadowAdd(byte[] data, int len) {
        int pk = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            short v = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
            int a = (v < 0) ? -v : v;
            if (a > pk) {
                pk = a;
            }
        }
        if (pk > shadowPeak) {
            shadowPeak = pk;
        }
        synchronized (shadowBuf) {
            if (shadowBuf.size() >= SHADOW_MAX_FRAMES) {
                return;
            }
            byte[] copy = new byte[len];
            System.arraycopy(data, 0, copy, 0, len);
            shadowBuf.add(copy);
        }
    }

    /**
     * 探测到期：服务器对这次唤醒没有任何反应。
     * 若影子里确实有人声 → 判僵尸会话，硬重启并回放；否则只是误命中/没人说话，
     * 保持会话不动。
     */
    private final Runnable wakeProbeTask = new Runnable() {
        public void run() {
            if (!probeArmed) {
                return;
            }
            probeArmed = false;
            if (state != State.LISTENING) {
                clearShadow();
                return;
            }
            int n;
            int pk = shadowPeak;
            synchronized (shadowBuf) {
                n = shadowBuf.size();
            }
            if (n == 0 || pk < PROBE_VOICE_PEAK) {
                String m = "wake probe: no speech in shadow (frames=" + n
                        + " peak=" + pk + ") -> session kept, no restart";
                Log.i(TAG, m);
                SessionLog.i(m);
                clearShadow();
                return;
            }
            String m = "wake probe: server silent " + wakeProbeSec
                    + "s after wake (frames=" + n + " peak=" + pk
                    + ") -> session likely dead, hard restart + replay";
            Log.i(TAG, m);
            SessionLog.i(m);
            replayShadow = true;   // 交给 startSession 回放到新会话
            hardRestart();
        }
    };

    /**
     * v1.3.0：麦克风常驻开关（KWS 独立唤醒的前提）。
     * true  = KWS 初始化成功后麦不再释放，IDLE 期持续喂自建 KWS（「成品」架构：
     *         随时可唤醒、TTS 播放中喊唤醒词即可打断）；原厂引擎永久断粮。
     * false = v1.2.4 行为：麦是会话期资源，IDLE 期还给原厂（bus 唤醒兜底）。
     * 实际由服务层在 KWS init 成功后才启用（startAlwaysOnMic），失败自动回退。
     */
    private static final boolean MIC_ALWAYS_ON = true;

    /** 45s 定时解除（唤醒早于定时器到达时会被 removeCallbacks 撤销） */
    private final Runnable uscUnmute = new Runnable() {
        public void run() {
            setMediaMute(false, "45s timer");
        }
    };

    /** 无 TTS 看门狗：CONNECTING/LISTENING 停留过久 → 收会话还麦 */
    private final Runnable noTtsWatchdog = new Runnable() {
        public void run() {
            State s = state;
            if (s == State.CONNECTING || s == State.LISTENING) {
                // v0.3.3：60s 开关已并入窗口时长（idleWindowMs()，定时器本身就
                // 是断开计时），这里不再做二次检查 —— 旧的「60s 条件藏在 90s
                // 定时器里」永远轮不到先出手，服务器 63~70s 的告别语总抢先。
                // ldyb5：会话期内有人声（含 STT 刚到、LLM 生成中）→ 按最后说话
                // 时刻续窗，不掐活会话。20:19 实测：STT 到达比 25s 看门狗晚
                // 10ms 被误杀，用户只能重新唤醒 = 体感「卡」。从未有人声才收。
                long lu = lastUserVoiceMs;
                if (lu != 0L) {
                    long sinceVoice = android.os.SystemClock.elapsedRealtime() - lu;
                    if (sinceVoice < watchdogMs) {
                        Log.i(TAG, "no-tts watchdog extended " + (watchdogMs - sinceVoice)
                                + "ms (user voice " + sinceVoice + "ms ago)");
                        SessionLog.i("no-tts watchdog extended (user voice "
                                + sinceVoice + "ms ago)");
                        main.postDelayed(this, watchdogMs - sinceVoice);
                        return;
                    }
                }
                String m = "no TTS within " + (watchdogMs / 1000)
                        + "s (state=" + s + ")" + voiceStats()
                        + " -> closing session, mic goes back to unisound";
                Log.i(TAG, m);
                SessionLog.i(m);
                // close 会触发 onClosed -> enterIdle（还麦）
                ws.close("no-tts-timeout");
            }
        }
    };

    public SessionController(Context appContext, AudioCapture mic, OpusCodec opus,
                             TtsPlayer player, KwsEngine kws, XiaoZhiWs ws) {
        this.appContext = appContext;
        this.mic = mic;
        this.opus = opus;
        this.player = player;
        this.kws = kws;
        this.ws = ws;
        I = this;   // v0.3.0：设置界面回调入口
        HandlerThread th = new HandlerThread("R1MicCtl");
        th.start();
        micHandler = new Handler(th.getLooper());
        // 防御性复位：若上一进程在静音期间崩溃，AudioService 的 mute 计数
        // 可能残留（5.x 不随客户端死亡清理），开机先清一次零。
        setMediaMute(false, "startup reset");
    }

    public State getState() {
        return state;
    }

    /**
     * 运行期挂载/摘除 KWS。
     * KWS 是异步初始化的（native 初始化可能崩，必须隔离），
     * 初始化成功后才通过这里接上；失败则传 null，走纯总线唤醒。
     */
    /**
     * v0.3.25：KWS 引擎是否已就绪（供服务层做初始化超时兜底判断）。
     * 注意区分两个标志：
     *   kwsEngineReady = sherpa 引擎 init 成功
     *   kwsWakeReady   = 引擎就绪 **且** 麦已常驻（真能听见唤醒词）
     */
    public boolean kwsEngineReadyForUi() {
        return kwsEngineReady;
    }

    public void setKws(KwsEngine k) {
        this.kws = k;
        // v0.3.25：KWS 引擎就绪。但**光引擎就绪还不算能唤醒** —— 麦是 on-demand
        // 的，要等 startAlwaysOnMic → acquireMicLoop 真正拿到麦，KWS 才有音频
        // 可听。故这里只记引擎状态，kwsWakeReady 等麦到手再置。
        kwsEngineReady = (k != null);
        Log.i(TAG, "setKws: " + (k == null ? "disabled" : "enabled")
                + (kwsEngineReady ? " (engine ready, waiting for mic)" : ""));
        if (k == null) {
            // v0.3.25：KWS 不可用 → 永远醒不了，界面别再显示「唤醒引擎启动中」
            // （会让人一直等一个永远不会到来的引擎），直接回退成「待唤醒」并
            // 靠 bus 唤醒兜底。
            kwsWakeReady = true;   // 引擎彻底不可用时不再显示「启动中」
            refreshIdleStateText();
        }
    }

    /**
     * v1.2.3：运行期补挂 TtsPlayer。
     * 音频 HAL 卡死时服务会以 player=null 降级启动（纯 ASR 也可用），
     * 退避重试成功后把新 player 接进来。
     */
    public void setPlayer(TtsPlayer p) {
        this.player = p;
        Log.i(TAG, "setPlayer: " + (p == null ? "null" : "attached"));
    }

    // ---------------- 唤醒（双源入口） ----------------

    /**
     * 源 A：自建 sherpa-onnx KWS 命中「小智小智」。
     * 语音「小智小智」时，这里会立刻触发，并补发麦克风预缓冲。
     */
    @Override
    public void onKeyword(String keyword) {
        handleWakeup("kws:" + keyword, true);
    }

    /**
     * 源 B：原厂云知声引擎唤醒后投递的总线事件（「小讯小讯」）。
     *
     * 这条路径的关键优势：唤醒判定完全由固件里的四麦引擎完成，
     * 唤醒率/抗噪远高于我们自己跑的软件 KWS，且零 CPU 开销。
     * v1.2.4 起这更是唯一可行的唤醒方式 —— 我们的麦在 IDLE 期
     * 必须让给原厂，自建 KWS 没有音频可听。
     */
    @Override
    public void onWakeup(String word) {
        handleWakeup("bus:" + word, true);
    }

    /**
     * v1.3.8：adb 调试唤醒 —— am broadcast 直接触发，不走总线/KWS。
     * 用于精确时序控制实验（如「USC 会话结束 N 秒后再开流」），
     * 解耦唤醒源与开流时机的耦合。
     */
    public void debugWakeup() {
        handleWakeup("debug:adb", true);
    }

    /**
     * 统一唤醒处理。
     * @param src      来源标记，仅用于日志
     * @param usePreroll barge-in 时是否补发预缓冲
     */
    private void handleWakeup(String src, boolean usePreroll) {
        // v0.3.0：设置里「启用小智」关闭 → 不响应任何唤醒（电台/酷我不受影响）
        if (!AppSettings.voiceEnabled(appContext)) {
            Log.i(TAG, "wakeup ignored (voice disabled by setting) src=" + src);
            return;
        }
        // v0.3.45：界面「静音」按下了 —— KWS 已停喂，但总线唤醒可能还在投递
        if (userMuted) {
            Log.i(TAG, "wakeup ignored (user muted) src=" + src);
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastWakeupMs < WAKEUP_DEBOUNCE_MS) {
            Log.i(TAG, "wakeup ignored (debounce) src=" + src + " state=" + state);
            return;
        }
        lastWakeupMs = now;
        wakeMs = now;               // v1.3.14：延迟埋点基准
        latFirstUplinkMs = 0L;
        latSttMs = 0L;
        latFirstTtsMs = 0L;
        wakeScreen();               // v0.3.0：唤醒亮屏（设置可关）
        Log.i(TAG, "wakeup src=" + src + " state=" + state);
        SessionLog.i("wakeup src=" + src + " state=" + state);

        // v1.2.15：唤醒即解除错误提示静音 —— 本轮会话要出声（TTS/回声提示），
        // 且 USC 的下一条错误提示要等这轮会话结束才会播。
        main.removeCallbacks(uscUnmute);
        if (uscMuted) {
            setMediaMute(false, "wakeup");
        }

        if (state == State.IDLE) {
            // v1.3.20：唤醒即出声 —— 先播本地应答音再开流。
            // 根因（2026-10-04 17:27 实测）：唤醒后设备全程静默直到服务器开口，
            // 用户无从判断「到底醒了没」，等了几十秒才说话，而那时 25s 无 TTS 看门狗
            // 早已把会话收掉 → 用户的话落在 IDLE 上，体感就是「唤醒没回复」。
            // 一声「叮」= 对讲机式的「你说」，用户听到就开口，整条时序就顺了。
            // 这声「叮」是本地 AudioTrack 蜂鸣、即时、非 TTS，两种模式都播，不引入任何等待。
            playWakeAck();
            // v1.3.60：本地唤醒后交接拾音（唤醒词不上传）模式 —— 仅「起播唤醒」这一条路径启用。
            // 只武装标志：本模式**不发 listen/detect**（那才会触发服务端问候 TTS + 2 秒耳聋窗），
            // 由 startSession() 跳过 preWake 补发、onHandshakeDone() 跳过 detect 上报来落实。
            // 对话中打断（TTS_PLAYING / LISTENING 分支）保持原样、早已不上传唤醒词且可无缝续讲，不受影响。
            // v1.3.61：唤醒时**实时**读设置（而非服务启动时缓存的 serverListenMode），
            // 否则设置页开关后不重启服务就一直不生效（实测：22:20 开了开关、22:13 起的
            // 服务仍跑着启动时缓存的 false → 唤醒词照传 → 问候照旧）。
            serverListenMode = AppSettings.serverListen(appContext);
            serverListenArmed = serverListenMode;
            startSession();
        } else if (state == State.TTS_PLAYING) {
            long sinceTts = android.os.SystemClock.elapsedRealtime() - lastTtsFrameMs;
            TtsPlayer p = player;
            // v1.3.17：判活改用「最近一帧 TTS 音频的到达时刻」而非 isPlaying()
            // —— isPlaying() 在越过 250ms 水位之前恒为 false，TTS 刚起步就被
            // 唤醒会被误判成僵尸 TTS，白重启一次会话。
            if (sinceTts < TTS_ALIVE_MS || (p != null && p.isPlaying())) {
                // 真·打断：TTS 流还活着（音频帧仍在到达，或播放器正在出声）——
                // 清掉本地剩余音频、转 LISTENING 接住用户，并重发 listen start
                // 让服务器切回 listening（打断本轮 TTS）。
                Log.i(TAG, "barge-in during TTS (playing, sinceTts=" + sinceTts + "ms)");
                SessionLog.i("barge-in during TTS (playing)");
                // ===== v0.3.24：先通知服务端停播（这一步此前完全缺失）=====
                // 官方固件 main/application.cc:913-929 在「播放中命中唤醒词」时
                // 的动作序列是：① SendAbortSpeaking() → ② 清待发队列 →
                // ③ 转 Listening → SendStartListening()。① 我们一直没有。
                // 后果：服务端不知道要停，继续生成并下发 TTS；尾帧又灌进播放器
                // →「打断后播报停不下来」，且服务端仍处在那轮 TTS 里，
                // 我们的上行音频它不当作新一轮处理 →「再说话没上行」。
                // 协议：{"session_id":"..","type":"abort","reason":"wake_word_detected"}
                // （官方 protocol.cc:58-65 SendAbortSpeaking）
                // v0.3.24：kwsInterruptMs 必须在发 abort **之前**置位 —— 服务端收到
                // abort 后会很快回一个 tts stop，那次 stop 要能命中「最小收尾」
                // （保持上行敞开、不二次重发 listen start），否则刚喂进去的
                // 「小智小智 + 后文」又被冲掉（就是「只打断、不传话」）。
                kwsInterruptMs = android.os.SystemClock.elapsedRealtime();
                ws.sendAbortSpeaking("wake_word_detected");
                ttsAbortSentMs = android.os.SystemClock.elapsedRealtime();
                if (p != null) {
                    // v1.3.49：真·打断 —— 清队列 **并 flush AudioTrack**。
                    // 只清队列的话，硬件缓冲里最多 512ms 的 TTS 会在打断之后紧接着
                    // 被麦克风录进上行（R1 无 AEC）—— 那正是自环的入口。
                    p.abort();
                }
                // v1.3.23：用户在这句里插话了 -> 撤销「告别即结束」的预定，会话继续
                main.removeCallbacks(farewellEndTask);
                serverFarewell = false;
                farewellActive = false;
                main.removeCallbacks(ttsStaleCheck);
                setState(State.LISTENING);
                ws.sendStartListening();
                // v0.3.22/v0.3.24：kwsInterruptMs 已在上面发 abort 之前置位
                // （服务端回 tts stop 时要走「最小收尾」，见那里的注释）。
                // v1.3.49：硬件尾巴已被 abort() 冲掉 → 立刻放行上行接住唤醒词与后文。
                // 下面的 flushPreroll() 会把「小智小智」本身也一并带给服务器。
                main.removeCallbacks(resumeUplinkTask);
                suppressUplink = false;
                echoGateOn = false;   // 打断路径不设电平闸门（尾巴已 flush）
                echoQuietRun = 0;
                armNoTtsWatchdog(idleWindowMs());
                if (usePreroll) {
                    flushPreroll();
                }
                String bm = "barge-in: uplink resumed at once (AudioTrack flushed + preroll sent)";
                Log.i(TAG, bm);
                SessionLog.i(bm);
            } else {
                // ===== v1.3.16：僵尸 TTS =====
                // 状态机还挂在 TTS_PLAYING，但播放器早就不出声了 —— 说明服务器
                // 已经把这轮 TTS 讲完却**没有下发 tts stop**（实测：发完
                // 「那我先休息一下好啦」告别语后就这么静默了）。
                // 旧代码在这里无条件 player.onTtsStart() + 转 LISTENING，
                // 等于继续用这条服务器早就不要了的死会话 → 用户怎么喊都没反应
                // （2026-10-04 16:36 实测：连喊两次「小智小智」全无响应）。
                // 现在：直接掐掉这条会话、开一条全新的。
                long idle = android.os.SystemClock.elapsedRealtime() - lastDownlinkMs;
                String m = "wake during stalled TTS (downlink idle=" + idle
                        + "ms) -> hard restart session";
                Log.i(TAG, m);
                SessionLog.i(m);
                hardRestart();
            }
        } else if (state == State.LISTENING) {
            // ===== v1.3.19：默认会话是活的 —— 接着说话，不再拆会话 =====
            // v1.3.17 在这里「无条件硬重启」，把健康会话也一起拆了：每一次唤醒都要
            // 重连（多背 5~7s），而且频繁重连本身会失败（实测 17:10:38 / 17:10:59 /
            // 17:11:19 连续三次重启零回复）—— 用户体感就是「唤醒概率失败」。
            // 现在：唤醒 = 「我要说话」，服务器本来就在 listening，把上行放行让它听到
            // 即可；只有服务器对这次唤醒**毫无反应**时，才由 wakeProbe 判僵尸并重启
            // （并把影子音频回放，用户不用重说）。
            long sinceTts = android.os.SystemClock.elapsedRealtime() - lastTtsFrameMs;
            TtsPlayer p = player;
            // v0.3.23：回声误命中判据由「最近 TTS 帧 <1s」改为「喇叭此刻是否还在出声」。
            // 旧判据的坑：TTS 自然结束后 onTtsStop 已把状态切到 LISTENING，但 AudioTrack
            // 硬件缓冲 + HAL 还有最多 ~1s 的尾巴在播；用户在这 1s 窗口内（或刚播完）喊
            // 「小智小智」想打断，被 sinceTts<1000ms 当成回声误命中直接 return —— 唤醒被吞、
            // 上行也不开 → 正是「只喊唤醒词、播报停不下来、再说话没上行」的体感。
            // 改成看喇叭是否真在出声：还在响（尾巴/播放中）→ 多半是回声，忽略；
            // 喇叭已静（用户等播报讲完才开口）→ 是真唤醒，放行上行接住用户。
            // 注：播放中真正的实时打断仍走 TTS_PLAYING 分支，不受此影响（A 路径不变）。
            if (p != null && p.isPlaying()) {
                Log.i(TAG, "wake ignored (speaker still playing, sinceTts=" + sinceTts + "ms)");
                return;
            }
            // 可能还压在「TTS 结束后的上行恢复窗口」里 —— 立刻放行，别吃掉用户开头的话
            main.removeCallbacks(resumeUplinkTask);
            suppressUplink = false;
            echoGateOn = false;   // v1.3.49：唤醒是明确的"我要说话"，撤掉电平闸门
            echoQuietRun = 0;
            // 用户开口了：续听窗口重新计时
            armNoTtsWatchdog(idleWindowMs());
            // v1.3.49：半双工下 TTS 之后这一整段都没上行过 —— 把「小智小智 + 紧随的
            // 后文」从 preroll 补发给服务器，否则服务器只能听到唤醒词之后的内容。
            if (usePreroll) {
                flushPreroll();
            }
            // 安全网：服务器若对这次唤醒毫无反应 → 判僵尸 → 重启 + 回放
            armWakeProbe();
            String m = "wake during LISTENING (downlink idle="
                    + (android.os.SystemClock.elapsedRealtime() - lastDownlinkMs)
                    + "ms, sinceTts=" + sinceTts + "ms) -> continue session, probe "
                    + wakeProbeSec + "s";
            Log.i(TAG, m);
            SessionLog.i(m);
        }
        // CONNECTING 期间的重复唤醒忽略
    }

    /**
     * v1.3.16：硬重启会话。
     *
     * 场景：服务器已经静默结束了这一轮（不发 tts stop、也不关 ws），
     * 我们手里这条连接对服务器而言已经无效 —— 唤醒打在它上面毫无反应。
     * 做法：标 restartPending 后主动 close，close 回调进入 enterIdle 收尾时
     * 消费该标记，直接开一条全新会话（不需要用户再喊第二遍）。
     */
    private void hardRestart() {
        restartPending = true;
        // v1.3.19：无论 socket 在不在，一律走 close() —— close() 会自增连接代数
        // （让旧 socket 的全部迟到回调失效），并**自己投递 onClosed**；enterIdle
        // 消费 restartPending 直接开一条新会话。旧代码在 socket 未建立时绕过
        // close() 直接 enterIdle，会漏掉代数自增，把新会话暴露在旧回调之下。
        ws.close("wake-restart");
    }

    /**
     * v1.3.0：麦克风常驻入口（服务层在 KWS init 成功后调用一次）。
     * 复用 acquireMicLoop 的重试逻辑；若开麦最终失败，设备保持原厂持麦
     * （bus 唤醒继续兜底），KWS 静默失效 —— 与 v1.2.4 行为等价，不劣化。
     */
    public void startAlwaysOnMic() {
        alwaysOnRequested = true; // v1.3.1：先置位，acquireMicLoop 的 IDLE 守卫放行
        micHandler.post(new Runnable() {
            public void run() {
                Log.i(TAG, "always-on mic: acquiring for KWS duty");
                acquireMicLoop();
            }
        });
    }

    // ---------------- 会话启动 / 收尾 ----------------


    /**
     * v1.2.4：会话开始 = 开麦 + 连 ws，两路并行。
     * ws 握手约 1-2s，通常麦在握手完成前就已到手。
     */
    private void startSession() {
        // v1.3.1：唤醒前的音频尾巴（含唤醒词+紧跟的话）先倒进 pending，
        // 与 CONNECTING 期帧一起在握手后补发 —— 服务器一次听到完整utterance。
        // v1.3.19：若本次启动是「响应探测判僵尸」触发的，改倒**影子音频** —— 那是
        // 用户在这声唤醒之后真正说过、并且已经发给那具僵尸会话的话；回放给新会话，
        // 用户不用重说。
        boolean useShadow = replayShadow;
        replayShadow = false;
        java.util.ArrayList<byte[]> tail = new java.util.ArrayList<byte[]>();
        if (useShadow) {
            synchronized (shadowBuf) {
                tail.addAll(shadowBuf);
                shadowBuf.clear();
            }
        } else if (serverListenArmed) {
            // v1.3.60：交接拾音模式 —— 唤醒词那段缓冲（preWake）直接丢弃，
            // 既不倒入 pending、也不上传。这样云端只听到「命中之后」说的话，
            // 唤醒词本身不上送；且因不发 detect，不会触发服务端问候 TTS / 耳聋窗。
            // 注意只清不倒：preWake 命中后仍在滚动，留待本次会话后续帧 / 下一轮唤醒。
            synchronized (preWake) {
                preWake.clear();
            }
        } else {
            synchronized (preWake) {
                tail.addAll(preWake);
                preWake.clear();
            }
        }
        shadowPeak = 0;
        if (!tail.isEmpty()) {
            // v0.3.12：移除「丢弃唤醒词」剪尾 —— 该功能要求唤醒词后必须停顿，
            // 连读命令会被剪掉（2026-10-25 实测），用户决定去掉。唤醒词连同
            // prewake 尾巴一起进服务器，ASR/LLM 自行处理前缀。
            synchronized (pending) {
                for (byte[] f : tail) {
                    if (pending.size() >= MAX_PENDING_FRAMES) {
                        break;
                    }
                    pending.add(f);
                }
            }
            String m = (useShadow ? "probe replay: " : "prewake tail queued: ")
                    + tail.size() + " frames (" + (tail.size() * 60) + "ms)";
            Log.i(TAG, m);
            SessionLog.i(m);
        }
        // v1.3.20：本会话人声取证归零
        sessionMaxRms = 0;
        sessionVoicedFrames = 0;
        farewellMute = false;   // v1.3.51：新会话解除告别静默
        // v1.3.47：新会话从干净状态开始（残留闸门会把开局的头几帧压掉）
        echoGateOn = false;
        echoQuietRun = 0;
        // v1.3.23：新会话的「上次人声」从现在算起 —— 刚开的会话绝不能被误判成告别
        lastUserVoiceMs = android.os.SystemClock.elapsedRealtime();
        // v1.3.24：告别判定的新基准也要归零，否则**新会话的第一条 TTS** 会拿着
        // 上一条会话留下的 lastTtsStopMs 算出「静默 5 分钟」→ 被当成告别语提前关掉。
        lastTtsStopMs = android.os.SystemClock.elapsedRealtime();
        kwsInterruptMs = 0L;   // v0.3.22：新会话开启 → 打断标记作废
        ttsAbortSentMs = 0L;  // v0.3.24：新会话开启 → abort 丢弃窗作废
        sttThisTurn = false;
        serverFarewell = false;
        main.removeCallbacks(farewellEndTask);
        farewellActive = false;
        setState(State.CONNECTING);
        synchronized (uplinkDump) {
            uplinkDump.clear(); // v1.3.3：dump 只覆盖本会话
        }
        Log.i(TAG, "session start -> CONNECTING (mic acquire + ws connect in parallel)");
        SessionLog.i("session start -> CONNECTING");
        micHandler.post(new Runnable() {
            public void run() {
                acquireMicLoop();
            }
        });
        ws.connect();
        armNoTtsWatchdog();
    }

    /**
     * 会话期开麦：快速重试直到拿到麦 / 超时 / 会话结束。
     *
     * 竞争背景：唤醒瞬间原厂自己也会开 ASR 录音（uni4micHalJNI 直开设备），
     * 我们的 AudioRecord 此刻会被挤开。实测它的云端 ASR ~1s 内报
     * 609 放手（云已死），所以重试很快就能接管。若原厂哪天云端复活，
     * 它会话期间我们也插不进来 —— 那是明确的"让原厂先说话"语义，可接受。
     */
    private void acquireMicLoop() {
        // v0.3.45：界面静音期间谁都不许把麦抢回来（含服务重启后的常驻入口）
        if (userMuted) {
            Log.i(TAG, "mic acquire skipped (user muted)");
            return;
        }
        if (mic.isRunning()) {
            Log.i(TAG, "mic already running, skip acquire");
            return;
        }
        // v1.3.1：常驻模式下 IDLE 也允许进循环（否则 attempts=0 直接放弃）
        final boolean persist = alwaysOnRequested;
        long deadline = android.os.SystemClock.elapsedRealtime() + MIC_DEADLINE_MS;
        int attempt = 0;
        while ((persist || state != State.IDLE)
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            attempt++;
            if (tryStartMic(MIC_ATTEMPT_WATCHDOG_MS)) {
                if (persist) {
                    Log.i(TAG, "always-on mic ACQUIRED (attempt=" + attempt
                            + ") -> KWS on duty, unisound starved");
                    SessionLog.i("always-on mic ACQUIRED (attempt=" + attempt + ")");
                    // v0.3.25：麦到手 → KWS 真正有音频可听 → **此刻起才叫能唤醒**。
                    // 界面从「唤醒引擎启动中」切回「待唤醒」。
                    if (!kwsWakeReady) {
                        kwsWakeReady = true;
                        Log.i(TAG, "kws wake-ready: mic acquired -> 待唤醒");
                        SessionLog.i("kws wake-ready (mic acquired)");
                        refreshIdleStateText();
                    }
                } else {
                    Log.i(TAG, "mic ACQUIRED (attempt=" + attempt
                            + ") -> unisound KWS paused, session owns the mic");
                    SessionLog.i("mic ACQUIRED (attempt=" + attempt + ")");
                }
                return;
            }
            if (!persist && state == State.IDLE) {
                break;
            }
            String m = "mic acquire attempt " + attempt
                    + " failed (device busy? unisound ASR holding) -> retry in "
                    + MIC_RETRY_INTERVAL_MS + "ms";
            Log.i(TAG, m);
            SessionLog.i(m);
            try {
                Thread.sleep(MIC_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        String g = "mic acquire gave up (state=" + state + " attempts=" + attempt
                + ") -- session continues without mic, no TTS watchdog will clean up";
        Log.w(TAG, g);
        SessionLog.i(g);
        // v1.3.1：常驻模式开麦失败不自弃 —— 30s 后再试一轮（USC 可能正在
        // 播报占着 input；等它放手）。KWS 无人喂期间 bus 唤醒兜底。
        if (persist) {
            micHandler.postDelayed(alwaysOnRetry, 30000L);
        }
    }

    /** 常驻麦自愈重试（gave-up 后 30s 再来一轮；mic 已活时 acquireMicLoop 直接跳过）。跑在 micHandler。 */
    private final Runnable alwaysOnRetry = new Runnable() {
        public void run() {
            if (alwaysOnRequested && !mic.isRunning()) {
                Log.i(TAG, "always-on mic retry");
                acquireMicLoop();
            }
        }
    };

    /**
     * 单次开麦尝试，带看门狗。
     * 卡死的尝试线程（binder 里）留在原地，无害 —— v1.2.3 已验证该模式。
     * AudioCapture.start() 内部有 generation 否决：stop() 发生在卡死期间时，
     * 迟到的 start 会自我了断，不会复活成僵尸录音。
     */
    private boolean tryStartMic(final long timeoutMs) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicBoolean ok = new AtomicBoolean(false);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    ok.set(mic.start());
                } catch (Throwable tr) {
                    Log.e(TAG, "mic.start threw", tr);
                } finally {
                    done.set(true);
                }
            }
        }, "mic-acquire");
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }
        if (t.isAlive()) {
            Log.e(TAG, "mic.start TIMEOUT after " + timeoutMs + "ms (binder stuck)");
            return false;
        }
        return ok.get();
    }

    /**
     * 回到 IDLE —— 会话收尾。
     * 关键动作：release 麦还给原厂 KWS（「小讯小讯」要靠它继续活着）。
     * 麦操作投到串行线程，与开麦循环天然排队，不会交错。
     */
    private void enterIdle() {
        main.removeCallbacks(noTtsWatchdog);
        main.removeCallbacks(ttsStaleCheck);   // v1.3.16：TTS 静默看门狗一并撤
        main.removeCallbacks(wakeProbeTask);   // v1.3.19：唤醒响应探测一并撤
        probeArmed = false;
        main.removeCallbacks(farewellEndTask); // v1.3.23：告别收尾一并撤
        serverFarewell = false;
        farewellActive = false;
        farewellMute = false;      // v1.3.51：会话结束，解除告别静默（防御性）
        boolean wasSession = (state != State.IDLE);
        if (wasSession) {
            // v1.3.20：一行定性「这次没回复到底怪谁」——
            // voicedFrames=0 / maxRms≈底噪 ⇒ 麦没听到人说话，客户端无过。
            String vs = "session end voice:" + voiceStats()
                    + " (voicedFrames=0 & maxRms 低 = 全程没人说话)";
            Log.i(TAG, vs);
            SessionLog.i(vs);
        }
        setState(State.IDLE);
        suppressUplink = false;
        clearPending();
        // v1.3.16：preWake 不再清空 —— 常驻麦下它现在是「一直滚动的最近 1.2s」，
        // 保留下来，唤醒触发硬重启时才能把「唤醒词 + 紧跟的话」带进新会话。
        // v1.3.0：麦常驻（KWS 唤醒岗）时会话结束不再还麦 —— IDLE 期继续喂 KWS。
        // 原厂引擎断粮，错误提示/总线唤醒均不会再出现；静音罩保留为防御逻辑。
        // v1.3.8：仅当 KWS 引擎真实在岗才持麦 —— bus-only / micprobe 模式下
        // kws==null，继续持麦 = 纯占坑（输入流被握住、小讯聋、且无人消费）。
        if (MIC_ALWAYS_ON && kws != null) {
            if (mic.isRunning() && !userMuted) {
                Log.i(TAG, "mic KEPT (always-on, KWS on duty)");
                SessionLog.i("mic KEPT (always-on, KWS on duty)");
            } else if (mic.isRunning()) {
                // v0.3.45：用户按了静音 —— 不还麦也不留，直接释放，真正停止收音
                micHandler.post(new Runnable() {
                    public void run() {
                        try {
                            mic.stop();
                            Log.i(TAG, "mic RELEASED (user mute) -> no more listening");
                            SessionLog.i("mic RELEASED (user mute)");
                        } catch (Throwable t) {
                            Log.e(TAG, "mic.stop on mute threw", t);
                        }
                    }
                });
            }
        } else if (mic.isRunning()) {
            micHandler.post(new Runnable() {
                public void run() {
                    try {
                        mic.stop();
                        Log.i(TAG, "mic RELEASED -> unisound KWS back on duty");
                        SessionLog.i("mic RELEASED -> unisound KWS back on duty");
                    } catch (Throwable t) {
                        Log.e(TAG, "mic.stop threw", t);
                        SessionLog.i("mic.stop threw: " + t);
                    }
                }
            });
        }
        if (wasSession) {
            Log.i(TAG, "back to IDLE");
            SessionLog.i("back to IDLE");
            musicYieldEnd();   // v0.3.0：会话收尾兜底——让路中的酷我恢复播放
            writeUplinkDump(); // v1.3.3：会话结束落盘本会话上行 PCM
            // v1.3.16：若是「唤醒触发的硬重启」，马上要开新会话，绝不能把媒体
            // 流静音罩上（否则新一轮 TTS 直接哑掉）—— 静音罩只给真收会话用。
            // ldyb5 (v0.2.9)：本机原厂 Unisound 已被本客户端替换，USC 错误提示
            // 根本不存在，R1 的「静音罩」只剩副作用 —— 关会话后把 STREAM_MUSIC
            // 全局压 0 达 45s，酷我/电台「还在播却没声」。故整个静音罩不再启用，
            // 仅保留 release() 兜底防历史遗留 mute 计数。
            if (!restartPending) {
                if (uscMuted) {
                    setMediaMute(false, "idle safety unmute");
                }
            }
        }

        // ===== v1.3.16：唤醒打在了僵尸会话上 → 不停 IDLE，立刻开一条新会话 =====
        if (restartPending) {
            restartPending = false;
            Log.i(TAG, "restart pending -> starting fresh session");
            SessionLog.i("restart pending -> starting fresh session");
            startSession();
        }
    }

    // ---------------- 上行 ----------------

    @Override
    public void onFrame(byte[] data, int len, byte[] stereo) {
        // KWS 永远要喂（即使 TTS 播放中，以支持 barge-in）
        // 若走「纯总线唤醒」模式，kws 为空实现，这里等同 no-op
        if (kws != null) {
            kws.feed(data, len);
        }

        // v1.3.20 / v1.3.22：唤醒应答音回声窗口。micDropUntilMs 默认 0
        // → 判定恒假、**一帧不丢**（硬丢会剪断连读的时间线，见 wakeAckEchoDropMs 注释）。
        if (micDropUntilMs != 0L
                && android.os.SystemClock.elapsedRealtime() < micDropUntilMs) {
            return;
        }

        // v1.3.20：本会话人声取证（放在丢帧判定之后，统计的是「真有机会上行」的帧）
        int rmsNow = (state == State.IDLE) ? 0 : frameRms(data, len);
        if (state != State.IDLE) {
            if (rmsNow > sessionMaxRms) {
                sessionMaxRms = rmsNow;
            }
            if (rmsNow > VOICE_FLOOR_RMS) {
                sessionVoicedFrames++;
                // v1.3.23：只把「LISTENING 且上行已放行」的人声当作「用户说话」——
                // 排除 TTS 播放回声与 TTS 尾巴的恢复窗口，避免告别判定被喇叭声骗过。
                if (state == State.LISTENING && !suppressUplink && !farewellActive) {
                    lastUserVoiceMs = android.os.SystemClock.elapsedRealtime();
                }
            }
        }

        // ===== v1.3.47：上行回声闸门 =====
        // 必须放在 suppressUplink 判定**之前**求值：否则被 suppress 短路 return 后，
        // 这里的「安静确认」永远跑不起来，闸门就再也没机会开。
        // 详见 echoGateEnabled 字段注释（自环根因 + 电平判据）。
        // v1.3.48：加"最小强制压制期"（echoGateHoldMs）—— 实测 TTS 播放期 mic 本来
        // 就被 HAL 压到 77~430（假安静），所以"安静确认"必须在压满之后才算数。
        if (echoGateOn) {
            long gnow = android.os.SystemClock.elapsedRealtime();
            long gheld = gnow - echoGateArmedMs;
            if (state == State.IDLE) {
                echoGateOn = false;   // 会话没了，闸门无意义
                echoQuietRun = 0;
            } else if (gheld < echoGateHoldMs) {
                // v1.3.48：强制压制期 —— 不管 mic 多安静都不做确认（播放期/刚停时
                // 的"安静"是 HAL 回声抑制造成的假象，不是喇叭真停了）。
                echoQuietRun = 0;
            } else if (rmsNow < echoRms) {
                if (++echoQuietRun >= echoClearFrames) {
                    releaseEchoGate("quiet " + echoQuietRun + " frames @held" + gheld + "ms");
                }
            } else {
                echoQuietRun = 0;     // 又听到喇叭 -> 重新确认
            }
            if (echoGateOn && gheld >= echoGateMaxMs) {
                releaseEchoGate("timeout " + echoGateMaxMs + "ms (mic still loud)");
            }
            if (echoGateOn) {
                // v1.3.48：闸门内取证 —— 每 500ms 记一次电平。判据：
                //   压制期全程 rms≈80~430 且松开后也安静 → HAL 抑制在线（ttshold 生效）
                //   压制期后段 rms 跳到 500~1500          → 抑制退线（自环燃料仍在）
                if (gnow - echoProbeLastMs >= 500L) {
                    echoProbeLastMs = gnow;
                    String pm = "echoGate probe rms=" + rmsNow + " held=" + gheld
                            + "ms quietRun=" + echoQuietRun;
                    Log.i(TAG, pm);
                    SessionLog.i(pm);
                }
                return;   // 闸门内：喇叭还在响，本帧不上行
            }
        }

        // v1.2.4：CONNECTING（开麦~握手完成）期间的音频先进 pending，
        // 握手完成后统一补发 —— 用户张口早于握手也不丢字
        if (state == State.CONNECTING) {
            stashPending(data, len, stereo);
            probeStereo(stereo); // v1.3.5：开局原始 stereo 取证
            return;
        }

        // v1.3.1 / v1.3.16：常驻麦下滚动缓冲最近 1.2s（含唤醒词本身 + 紧跟的话），
        // 唤醒 -> startSession 时倒进 pending，握手后一并补发 —— 「唤醒即说话」。
        // v1.3.16：IDLE 之外（LISTENING / TTS_PLAYING）也继续滚动 —— 唤醒若打在
        // 僵尸会话上触发硬重启，这段尾巴才有东西可带进新会话。
        if (alwaysOnRequested) {
            synchronized (preWake) {
                if (preWake.size() >= prewakeFrames) {
                    preWake.pollFirst(); // 环形：保尾巴丢旧头
                }
                byte[] copy = new byte[len];
                System.arraycopy(data, 0, copy, 0, len);
                preWake.addLast(copy);
            }
            if (state == State.IDLE) {
                return;   // IDLE 期只喂 KWS + 滚缓冲，不上行
            }
        }

        // v1.2.16 / v1.3.16：TTS 播放中放行上行（服务器 VAD 打断）由 bargeInUplink
        // 控制 —— 默认关（公网 api.tenclass.net 拒带 aec 的 hello + 上行自环风险）；
        // R1 硬件回声抑制仅在 TTS 播放期在线（TtsPlayer v1.3.48 实证 mic 干净），
        // 故开实时打断 + realtime 模式时硬件 AEC 路径可行；熔断
        // （echoDisabled）后同样回退抑制；其余非 LISTENING 状态照旧丢弃
        boolean bargeIn = (state == State.TTS_PLAYING)
                && bargeInUplink && !echoDisabled;
        if (suppressUplink || (state != State.LISTENING && !bargeIn)) {
            return;
        }
        // v1.3.2：补发进行中实时帧改道排队（服务器端时间线单调，防交错）
        if (flushing) {
            stashPending(data, len, stereo);
            return;
        }
        dumpUplinkPcm(data); // v1.3.3：自证 dump（只记真正上行的帧）
        if (probeArmed) {
            shadowAdd(data, len); // v1.3.19：探测窗口内的上行影子（僵尸重启后回放）
        }
        probeStereo(stereo); // v1.3.5：原始 stereo 取证（L/R 动死判定）
        byte[] pkt = opus.encode(data, len);
        if (pkt != null) {
            if (ws.sendAudio(pkt) && latFirstUplinkMs == 0L && wakeMs > 0L) {
                latFirstUplinkMs = android.os.SystemClock.elapsedRealtime();
                long d = latFirstUplinkMs - wakeMs;
                Log.i(TAG, "lat: wake->first-uplink = " + d + "ms");
                SessionLog.i("lat: wake->first-uplink = " + d + "ms");
            }
        }
    }

    private void stashPending(byte[] data, int len, byte[] stereo) {
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING_FRAMES) {
                return; // 满 15s：丢新帧（保留最初的话更有价值）
            }
            byte[] copy = new byte[len];
            System.arraycopy(data, 0, copy, 0, len);
            pending.add(copy);
            // v1.3.6：stereo 平行入队（同锁同步，保序；probe 取证用）
            stereoPending.add(stereo == null ? null : stereo.clone());
        }
    }

    /**
     * 握手完成：把 CONNECTING 期攒的音频补发上行。
     * v1.3.2 根因修复：突发灌入（71 帧 / 几十 ms）会把官方服务器的 opus
     * 解码/VAD 时序打坏 —— 实测 09:36/09:37/09:44 三连会话全沉默，且突发后
     * 的实时语音（用户连续说话）也无人识别 = 解码状态被污染。改为 60ms/帧
     * 实时速率匀速补发，与 ESP32 真实时流行为一致。
     */
    private void flushPending() {
        synchronized (pending) {
            if (pending.isEmpty()) {
                Log.i(TAG, "flush pending: empty (mic opened after handshake?)");
                SessionLog.i("flush pending: empty");
                return;
            }
        }
        flushing = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                pacedFlushLoop();
            }
        }, "pending-flush");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 补发循环：跑独立线程（绝不睡 WS 回调线程，否则下行全堵）。
     * 队列追平即放行实时上行；期间实时帧经 onFrame 的 flushing 分支排队，
     * 顺序天然 FIFO。若 TTS 先到（服务器响应快于积压排空），积压已是
     * 过时音频，直接丢弃。
     *
     * v1.3.18：由「严格 60ms/帧匀速」改为**自适应追赶** —— 旧的匀速恰等于麦克风
     * 产出速率，导致队列永不排空、服务器被永久甩在 backlog 之后（详见
     * prewakeFrames 字段注释）。现在按积压深度最多 2~3 帧/60ms。
     */
    private void pacedFlushLoop() {
        int sent = 0;
        int maxBacklog = 0;
        while (state == State.LISTENING) {
            // ===== v1.3.18：自适应追赶 =====
            // 旧实现 60ms/帧（1.0x）**严格等于**麦克风产出速率 —— 补发期间实时帧
            // 又在往 pending 里排（onFrame 的 flushing 分支），输入 = 输出 →
            // 队列永远排不空、flushing 永远不复位，服务器被永久甩在 backlog 之后。
            // 现在按积压深度一次多发几帧：2 帧/60ms=2x、3 帧/60ms=3x。单位仍是
            // 「帧/60ms」的温和倍速，与 v1.3.2 踩过的「71 帧几十 ms 突发」(~1000x)
            // 不是一个量级，服务器 jitter buffer 吸收得了。
            // 阈值要点：1x 是「输入=输出」的死区，积压会永远停在阈值上不动 ——
            // 所以 burst=2 的门槛必须压到很小（>2 帧），让队列被真正排空到 ~1 帧
            // （60ms）以内；burst=3 只在积压 >12 帧（>0.7s）时用来快速消肿。
            int burst = 1;
            synchronized (pending) {
                int n = pending.size();
                if (n > maxBacklog) {
                    maxBacklog = n;
                }
                if (n > 12) {
                    burst = 3;
                } else if (n > 2) {
                    burst = 2;
                }
            }
            boolean drainedAll = false;
            for (int i = 0; i < burst; i++) {
                byte[] f;
                byte[] st;
                synchronized (pending) {
                    f = pending.isEmpty() ? null : pending.remove(0);
                    st = stereoPending.isEmpty() ? null : stereoPending.remove(0);
                }
                if (f == null) {
                    drainedAll = true; // 队列追平
                    break;
                }
                dumpUplinkPcm(f); // v1.3.3：补发帧同样入 dump
                if (st != null) {
                    probeStereo(st); // v1.3.6：补发帧 stereo 也取证
                }
                byte[] pkt = opus.encode(f, f.length);
                if (pkt != null) {
                    ws.sendAudio(pkt);
                }
                sent++;
            }
            if (drainedAll) {
                break;
            }
            try {
                Thread.sleep(60L);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (state != State.LISTENING) {
            synchronized (pending) {
                pending.clear();
                stereoPending.clear(); // v1.3.6：平行队列同清
            }
        }
        // v1.3.18：原子清尾巴 + 复位 flushing。循环退出与 flushing=false 之间有
        // 一个窗口，onFrame 看到 flushing 仍为 true 会把新帧 stash 进来 —— 不复位
        // 就丢 60ms，或在 pending 里躺到下次会话。旧版因为队列永不排空、根本不会
        // 走到这里，所以没有这个问题；新版能真正排空，必须补上。
        synchronized (pending) {
            while (!pending.isEmpty()) {
                byte[] f = pending.remove(0);
                byte[] st = stereoPending.isEmpty() ? null : stereoPending.remove(0);
                dumpUplinkPcm(f);
                if (st != null) {
                    probeStereo(st);
                }
                byte[] pkt = opus.encode(f, f.length);
                if (pkt != null) {
                    ws.sendAudio(pkt);
                }
                sent++;
            }
            flushing = false;
        }
        String m = "paced flush done: sent " + sent + " frames ("
                + (sent * 60) + "ms audio), maxBacklog=" + maxBacklog
                + " frames (" + (maxBacklog * 60) + "ms backlog cap)";
        Log.i(TAG, m);
        SessionLog.i(m);
    }

    private void clearPending() {
        synchronized (pending) {
            pending.clear();
            stereoPending.clear(); // v1.3.6：平行队列同清
        }
    }

    /** barge-in 用：补发 TTS 抑制窗口内的预缓冲（v1.2.3 语义保留） */
    private void flushPreroll() {
        byte[] pre = mic.drainPreroll();
        if (pre.length == 0) {
            return;
        }
        int frames = pre.length / AudioCapture.FRAME_BYTES;
        Log.i(TAG, "flush preroll " + frames + " frames (" + (frames * 60) + "ms)");
        for (int i = 0; i < frames; i++) {
            byte[] f = new byte[AudioCapture.FRAME_BYTES];
            System.arraycopy(pre, i * AudioCapture.FRAME_BYTES, f, 0, AudioCapture.FRAME_BYTES);
            byte[] pkt = opus.encode(f, AudioCapture.FRAME_BYTES);
            if (pkt != null) {
                ws.sendAudio(pkt);
            }
        }
    }

    // ---------------- WS 回调 ----------------

    @Override
    public void onConnected() {
        Log.i(TAG, "ws connected");
    }

    @Override
    public void onHandshakeDone() {
        Log.i(TAG, "handshake done -> LISTENING");
        SessionLog.i("handshake done -> LISTENING");
        touchDownlink();   // v1.3.16：会话存活的第一个时间戳
        setState(State.LISTENING);
        suppressUplink = false;
        ws.sendStartListening();
        // v1.3.60：交接拾音模式下本案**不发 listen/detect** —— 唤醒词不上报，
        // 因此服务端不会进入 just_woken_up、不会回问候 TTS、也不会有 2 秒耳聋窗；
        // 用户唤醒后无需等问候播完即可开口。原 detect 上报分支已整体移除。
        serverListenArmed = false;
        // v1.3.14：延迟埋点 —— 唤醒 -> 握手完成（含 WS 建连 + hello 往返）
        if (wakeMs > 0L) {
            long d = android.os.SystemClock.elapsedRealtime() - wakeMs;
            Log.i(TAG, "lat: wake->handshake = " + d + "ms");
            SessionLog.i("lat: wake->handshake = " + d + "ms");
        }
        // 把开麦~握手之间的语音补上（此刻 hello 握手已完成，可以直接推流）。
        // 交接模式下 pending 里只有「命中→握手完成」期间的实时帧（唤醒词已在
        // startSession 里被裁掉），故补发的恰好是用户的指令，不含唤醒词。
        flushPending();
        armNoTtsWatchdog();
    }

    /**
     * v0.3.52 调试：模拟一轮 TTS 流（纯数字静音、无声），用于量测 jitter buffer
     * 是否在句中补静音。由 Ldyb5ClientService 的 tts_sim 入口调用，正常路径零影响。
     */
    public void debugTtsSim(int frames, int jitterMs) {
        if (player != null) {
            player.simulate(frames, jitterMs);
        } else {
            Log.w(TAG, "debugTtsSim: player is null");
        }
    }

    @Override
    public void onTtsStart() {
        Log.i(TAG, "TTS start");
        kwsInterruptMs = 0L;   // v0.3.22：新一轮 TTS 开启 → 打断标记作废，后续 tts stop 走标准收尾
        ttsAbortSentMs = 0L;  // v0.3.24：新一轮 TTS 开启 → abort 丢弃窗作废（否则会误丢本轮音频）
        ttsDroppedFrames = 0; // v0.3.24：新一轮 TTS → 丢弃计数归零
        AudioCapture.ttsActive = true;   // v0.3.19：开 TTS 期回声诊断
        SessionLog.i("TTS start (heard:" + voiceStats() + ")");   // v1.3.20：顺手记下本轮麦看到了什么
        main.removeCallbacks(noTtsWatchdog);
        disarmWakeProbe();                     // v1.3.19：服务器有反应了，探测解除
        clearShadow();
        touchDownlink();                       // v1.3.16
        long nowEl = android.os.SystemClock.elapsedRealtime();
        // v1.3.23：**空闲告别识别**。服务器不会无缘无故主动开口 —— 上一次人声
        // 已经过去 farewellIdleSec 秒它才说话，那几乎必然是它在念告别语，而官方
        // 服务器念完告别就不理会这条会话了（18:19 实测）。这里先标记，等
        // onTtsStop 播完再关会话（不让用户听半句被切断）。
        main.removeCallbacks(farewellEndTask);
        farewellActive = false;
        // v1.3.24：静默基准从「麦克风最后人声」换成「距上一次 tts stop 多久」——
        // **不再吃麦克风噪声**。v1.3.23 用 lastUserVoiceMs 判定实测失效：
        // 2026-10-04 18:44 那条「好像没人应我，那我先休息啦」本该判告别却没判 ——
        // micTelem 的 1 秒 RMS 全程只有 214~292（远低于门限 400），看着确实没人说话，
        // 但那是**秒级平均**；逐帧统计里偶发的单帧脉冲（房噪/风扇/喇叭残响）只要有一帧
        // 超过 400，就会把 lastUserVoiceMs 顶到「刚刚」→ quietMs 永远凑不满 35s。
        // 换成服务器自己的时序后与音频完全无关。
        // 真正的守护依旧是 sttThisTurn：STT 必然先于 TTS 到达（实测 18:43:32.762 STT
        // -> 18:43:32.776 tts start），所以「本轮没有 STT」等价于「服务器在自言自语」。
        long sinceTtsStop = (lastTtsStopMs > 0L) ? (nowEl - lastTtsStopMs) : -1L;
        if (!sttThisTurn && sinceTtsStop >= farewellIdleSec * 1000L) {
            // ===== v1.3.51：服务器空闲告别 —— 静默收会话，一帧都不播 =====
            // 2026-10-25 用户反馈：「60秒空闲自动断开又会播语音提示了」。
            // 机理（设备日志）：官方服务器在最后一次 tts stop 后 63~70s 自发
            // 一句告别（"那我先休息一下"类），客户端此前判为 serverFarewell 后
            // **照旧播放**再关会话 —— 这就是空闲断开时响的语音。
            // 「60s 空闲断开」的设计目的就是静默收场（v0.3.10 起本地提示音全删），
            // 服务器这句自言自语同样不该播：它不携带任何用户需要的信息
            // （没有 STT = 不是在回应用户），只会像bug一样响一声。
            // 判据可靠性：STT 必然先于 TTS 到达（18:43:32.762 STT -> .776 tts start），
            // 「本轮无 STT + 距上次 tts stop ≥35s」只能是服务器自言自语。
            String fm = "server idle-farewell MUTED (no stt this turn, "
                    + (sinceTtsStop / 1000) + "s after last tts stop) -> closing silently";
            Log.i(TAG, fm);
            SessionLog.i(fm);
            farewellMute = true;              // 丢弃随后到达的 TTS 音频帧
            if (player != null) {
                player.abort();               // 清掉可能已入队的音频（防污染下一轮）
            }
            ws.close("idle-farewell");
            return;                           // 不进 TTS_PLAYING、不播、不续听
        }
        serverFarewell = false;
        lastTtsFrameMs = nowEl;                // v1.3.16：TTS 静默看门狗起点
        main.removeCallbacks(ttsStaleCheck);
        main.postDelayed(ttsStaleCheck, 2000L);
        setState(State.TTS_PLAYING);
        ttsStartElapsed = nowEl;
        latFirstTtsMs = 0L;   // v1.3.14：新一轮 TTS 重新计首帧
        // v1.3.47：新一轮 TTS 开始 —— 放掉上一轮的回声闸门（播放期本就由
        // suppressUplink 挡着，闸门留到本轮的 tts stop 再落）。
        echoGateOn = false;
        echoQuietRun = 0;
        // v1.3.49：撤掉可能还在排队的"上行恢复"任务 —— TTS 期上行必须全程关闭，
        // 否则上一轮排下的 resumeUplinkTask 会在本轮 TTS 播放中途把上行打开。
        main.removeCallbacks(resumeUplinkTask);
        // v0.3.21：TTS 播放期一律压上行。实时打断改由本地 KWS 承担（播放中喊
        // 唤醒词→handleWakeup 的 TTS_PLAYING 分支强制停 TTS + 放行上行），
        // 不再需要播放期上行，也避免把喇叭回声喂回服务器（自环/自打断入口）。
        suppressUplink = true;
        if (player != null) {
            player.onTtsStart();
        } else {
            Log.w(TAG, "TTS start but player is null (degraded), skip");
        }
        // ldyb5：TTS 开口 → 压低音乐（屏幕 UI 点的歌），说完了再恢复
        MusicPlayer.duckAll(true);
        // v0.3.0：音乐为小智让路（设置开关）—— TTS 期暂停酷我，说完恢复
        musicYieldStart();
    }

    @Override
    public void onTtsAudio(byte[] opusFrame) {
        if (opusFrame == null || opusFrame.length == 0) {
            return;
        }
        // v1.3.51：空闲告别静默期 —— 帧直接丢弃，不解码不入队（socket 关闭前
        // 服务器可能还在吐几个尾帧；播了就是用户听到的那声提示）
        if (farewellMute) {
            return;
        }
        touchDownlink();     // v1.3.16
        lastTtsFrameMs = android.os.SystemClock.elapsedRealtime();  // v1.3.16：喂静默看门狗
        // v1.3.14：延迟埋点 —— STT 出字 -> 服务器首个 TTS 音频帧
        if (latFirstTtsMs == 0L) {
            latFirstTtsMs = android.os.SystemClock.elapsedRealtime();
            if (latSttMs > 0L) {
                long d = latFirstTtsMs - latSttMs;
                Log.i(TAG, "lat: STT->tts-frame1 = " + d + "ms");
                SessionLog.i("lat: STT->tts-frame1 = " + d + "ms");
            }
            // ===== v1.3.18：两个「总账」埋点，一眼看出首轮/周转各花多少 =====
            // ① 首轮总账：唤醒 -> 服务器第一个 TTS 音频帧（含建连 + 补发 + ASR + LLM + TTS）
            if (wakeMs > 0L) {
                long d = latFirstTtsMs - wakeMs;
                Log.i(TAG, "lat: wake->tts-frame1 = " + d + "ms (first-turn total)");
                SessionLog.i("lat: wake->tts-frame1 = " + d + "ms");
                wakeMs = 0L;   // 首轮账结清，避免后续轮次重复打「wake->*」
            }
            // ② 周转总账：上一轮 TTS 停 -> 本轮首个 TTS 音频帧（纯多轮周转）
            if (lastTtsStopMs > 0L) {
                long d = latFirstTtsMs - lastTtsStopMs;
                Log.i(TAG, "lat: tts-stop->tts-frame1 = " + d + "ms (turnaround)");
                SessionLog.i("lat: tts-stop->tts-frame1 = " + d + "ms");
            }
        }
        // ===== v0.3.24：复刻官方的下行音频状态守卫 =====
        // 官方固件 main/application.cc:549-551：
        //     protocol_->OnIncomingAudio([this](packet) {
        //         if (GetDeviceState() == kDeviceStateSpeaking) { 入解码队列 }
        //     });
        // —— 只有「正在说话」才收下行的 TTS 音频。我们此前**无条件 p.push**，
        // 于是打断之后服务端补发的尾帧会重新把声音顶起来（用户实测：
        // 「kws 打断似乎没有停止服务器播报」）。
        // 官方打断时把状态置为 Listening（application.cc:928 SetListeningMode），
        // 于是那一整轮残留音频自然被这道守卫吃掉 —— 我们照搬同一语义：
        // 不是 Speaking 态收到的 TTS 帧一律丢弃，且**不碰** touchDownlink /
        // lastTtsFrameMs（丢弃就是丢弃，不该被当成「下行还在活跃」）。
        if (state != State.TTS_PLAYING) {
            ttsDroppedFrames++;
            if (ttsDroppedFrames == 1 || ttsDroppedFrames % 20 == 0) {
                String dm = "tts frame dropped (state=" + state
                        + ", total=" + ttsDroppedFrames + ")";
                Log.w(TAG, dm);
                SessionLog.i(dm);
            }
            return;
        }
        // 双保险：打断后那一轮的尾帧即便赶在状态切换前到达，也不该播。
        long abortAge = (ttsAbortSentMs > 0L)
                ? (android.os.SystemClock.elapsedRealtime() - ttsAbortSentMs) : -1L;
        if (abortAge >= 0L && abortAge < TTS_ABORT_DROP_MS) {
            ttsDroppedFrames++;
            Log.w(TAG, "tts frame dropped (abort tail, age=" + abortAge + "ms)");
            return;
        }
        TtsPlayer p = player;
        if (p == null) {
            return; // 降级中：无播放器，丢弃 TTS 音频（ASR 链路不受影响）
        }
        byte[] pcm = opus.decode(opusFrame, opusFrame.length);
        if (pcm != null) {
            p.push(pcm);
        }
    }

    public void onTtsStop() {
        onTtsStop(false);
    }

    /**
     * TTS 结束（无论服务器下发 "stop" 还是 "abort"，统一走同一收尾流程）。
     * 真正的「播放中打断」由本地 KWS 命中唤醒词经 handleWakeup 的 TTS_PLAYING 分支
     * 处理（那里直接 abort 本地 TTS + 放行上行 + 补发 preroll），不经过这里。
     * 此处只负责：停本地播放、压上行、落回声闸门、重发 listen start 续听。
     */
    public void onTtsStop(boolean aborted) {
        Log.i(TAG, "TTS stop" + (aborted ? " (barge-in abort)" : ""));
        AudioCapture.ttsActive = false;  // v0.3.19：关 TTS 期回声诊断
        SessionLog.i("TTS stop" + (aborted ? " (barge-in abort)" : ""));
        MusicPlayer.duckAll(false);   // ldyb5：TTS 说完，音乐恢复原音量
        musicYieldEnd();              // v0.3.0：让路结束，恢复酷我
        main.removeCallbacks(ttsStaleCheck);   // v1.3.16：正常收到 stop，撤掉静默看门狗
        touchDownlink();                       // v1.3.16
        lastTtsStopMs = android.os.SystemClock.elapsedRealtime(); // v1.3.18：周转埋点基准
        sttThisTurn = false;   // v1.3.23：本轮结算完毕，下一轮重新判定
        if (player != null) {
            if (aborted) {
                player.abort();   // 立刻停播，冲掉 AudioTrack 尾巴，不留回声入口
            } else {
                player.onTtsEnd(); // 正常播完，队列自然排空
            }
        }

        // v0.3.22：KWS 打断的最小收尾 —— 这次 tts stop 是服务器对我们「提前发的
        // listen start」的回应（interrupt 后它中止了自己的 TTS）。handleWakeup 已经
        // 放开了上行、让服务器进入了聆听，这里若再走标准收尾（重压上行 250~900ms
        // + 重发 listen start 造出第二个 session），会把刚喂进去的「小智小智 + 后文」
        // 冲掉，表现正是「只打断、没上传」。故只做清理、不再二次收尾，上行保持敞开。
        long now = android.os.SystemClock.elapsedRealtime();
        if (kwsInterruptMs != 0L && (now - kwsInterruptMs) < 4000L) {
            kwsInterruptMs = 0L;
            Log.i(TAG, "TTS stop after KWS interrupt -> keep uplink open (no re-arm)");
            SessionLog.i("TTS stop after KWS interrupt -> keep uplink open (no re-arm)");
            armNoTtsWatchdog(idleWindowMs());
            return;
        }

        // v0.3.21：aborted 不再走特殊快速路径，统一按「TTS 结束」收尾
        // （压上行 + 落回声闸门 + 重发 listen start 续听）。真正的播放中打断由
        // 本地 KWS 命中唤醒词经 handleWakeup 处理，不经过此处。

        // v1.3.47：**落回声闸门**。上行何时恢复不再看时间（isDrained 判不到
        // AudioTrack 硬件缓冲 + HAL 采集延迟 + 房间余响），改看麦克风电平。
        // 必须放在下面 serverFarewell 早退分支之前，告别语同样要守。
        armEchoGate();
        // v1.2.16：回环熔断 —— TTS 开始 <2s 就被服务器打断，特征是喇叭里
        // 自己的 TTS 人声被 VAD 误判为用户插话 → 自打断 → 自言自语循环。
        // 置位 echoDisabled 后本进程内禁用 barge-in 上行（回退 v1.2.15 行为），
        // 重启服务重置。真插话一般发生在回复中后段（>2s），不受影响。
        // （barge-in 的 abort 不走这里：用户提前插话是正常行为，不能误判为回声环）
        if (bargeInUplink && !echoDisabled && ttsStartElapsed > 0L) {
            long dur = android.os.SystemClock.elapsedRealtime() - ttsStartElapsed;
            if (dur < ECHO_LOOP_MIN_TTS_MS) {
                echoDisabled = true;
                String m = "barge-in disabled: suspected echo-loop (tts lasted "
                        + dur + "ms)";
                Log.w(TAG, m);
                SessionLog.i(m);
            }
        }
        ttsStartElapsed = 0L;

        // ===== v1.3.23：这一轮是「服务器空闲告别」→ 播完就结束会话 =====
        // 官方服务器念完告别语就不再理会这条 ws（本地没有任何信号能看出来）。
        // 此前我们照旧「续听 listenIdleMs(90s)」，于是在这具尸体会话上再挂 30~50s；
        // 用户此时唤醒 -> 话喂进死 socket -> 探测 7s 才重启 -> 影子回放丢头 ->
        // 8s 延迟 + 丢字（2026-10-04 18:19 实测）。现在：播完即关，回 IDLE，
        // 下一次唤醒走全新会话（应答音 + 1.2s 预缓冲补发），既不静默也不丢字。
        if (serverFarewell) {
            serverFarewell = false;
            farewellActive = true;
            suppressUplink = true;   // 告别语还在喇叭里，别把回声喂回服务器
            setState(State.LISTENING); // 保持状态自洽（上行被 suppressUplink 挡住）
            main.removeCallbacks(noTtsWatchdog);
            main.removeCallbacks(farewellEndTask);
            main.postDelayed(farewellEndTask, 800L);
            String fm = "server farewell -> end session after playback"
                    + " (skip " + (listenIdleMs / 1000) + "s listen idle)";
            Log.i(TAG, fm);
            SessionLog.i(fm);
            return;
        }

        // ============ v1.3.12：TTS 结束后必须【重发 listen start】 ============
        // 根因（2026-10-04 15:57 用户实测「只听得见你好小智，后面的话没反应」）：
        // 小智服务器按「一条 listen start 服务一轮对话」工作。我们此前只在握手
        // 完成时发过一条，此后从不重发 —— 服务器第一轮（唤醒词）ASR 完就不再
        // 武装 VAD，用户后续所有语音全部石沉大海。
        // 依据 = 官方 ESP32 固件 application.cc（本机 _xz/xiaozhi-esp32）：
        //   L624-633   tts "stop" -> SetDeviceState(kDeviceStateListening)
        //   L1026-1044 进入 Listening 状态 -> StartListeningAudio()
        //   L1070-1078 StartListeningAudio() 第一件事 protocol_->SendStartListening()
        // 即：官方每进入一次「听」状态就重发一条 listen start（mode 随 AEC：
        // AEC 关 = auto，见 application.cc L1187-1188）。
        suppressUplink = true;   // 先压住上行（防自环），等本地播完再放
        setState(State.LISTENING);
        ws.sendStartListening();
        Log.i(TAG, "TTS stop -> re-arm listening (listen start re-sent)");
        SessionLog.i("TTS stop -> re-arm listening (listen start re-sent)");
        resumeWaitedMs = 0L;
        main.removeCallbacks(resumeUplinkTask);
        if (halfDuplex) {
            // v1.3.49「严格静音」：**不再自动恢复上行**。服务器收不到音频 = STT 天然禁用；
            // KWS 继续听唤醒词，命中后由 handleWakeup 打断 + 放行 + 补发 preroll。
            // v1.3.50：默认关（用户实测这么做等于「每句话都要喊唤醒词」）。
            String hm = "half-duplex(strict): uplink muted until wake word (STT disabled)";
            Log.i(TAG, hm);
            SessionLog.i(hm);
        } else {
            // v1.3.50（默认）：TTS 后自动恢复上行 —— 用户可直接接话，不用再喊唤醒词。
            // 放行时机由 resumeUplinkTask（等播放排空）+ echoGate（强制压制期后认「安静」）
            // 共同把关，防自环交给 TtsPlayer.holdSilenceMs 钉住 HAL 回声抑制。
            main.postDelayed(resumeUplinkTask, RESUME_POLL_MS);
            String rm = "TTS stop -> uplink auto-resume armed (no wake word needed)";
            Log.i(TAG, rm);
            SessionLog.i(rm);
        }
        // v0.3.10：60s 空闲窗口本身就是断开定时器（见 idleWindowMs 注释）——
        // 窗口末端不再播任何本地提示音，退出语音一律静默（v0.3.9 用户反馈：
        // 「退出提示音固定 60s 响、没绑定实际退出状态」）。提示音只在真正的
        // 唤醒（叮）/ 退出小智（叮-咚）时响，由 AppSettings.cueOn 统一控制。
        armNoTtsWatchdog(idleWindowMs());
    }

    /**
     * v1.3.12：恢复上行前至少压住这么久（覆盖 AudioTrack 缓冲里的 TTS 尾巴）。
     * v1.3.18：700 -> 400。这段窗口里用户的话是**被丢弃**的 —— 压 700ms 意味着
     * 用户「听到回复立刻接话」时开头 0.7s 被吃掉，多轮体感就是「要等一下才认」。
     * 真正的防自环靠 `isDrained()`（本地播放确实排空），min 只做下限兜底，
     * 400ms 足够覆盖 AudioTrack 尾巴，且显著缩短被吃掉的窗口。
     */
    private static final long RESUME_MIN_MS = 250L;
    /** v1.3.12：等本地播放排空的兜底上限。
     *  v1.3.46 ★ 3000 -> 900。实机取证（2026-10-05 21:08~21:10，jp2 v1.3.45）：
     *      21:08:58.601 TTS stop -> 21:09:01.618 uplink resumed (waited=3000ms)
     *      21:09:10.391 TTS stop -> 21:09:13.411 uplink resumed (waited=3000ms)
     *      21:09:53.282 TTS stop -> 21:09:56.300 uplink resumed (waited=3000ms)
     *  每次 TTS stop 都撞满 3000ms 上限（isDrained 迟迟不真，见下方注释），
     *  而这 3 秒里用户说的话**被整句丢弃** —— 用户「听到回复立刻接话」说
     *  「现在几点？」（约 1s）正好整句落在窗口内 ⇒ 服务器一个字都没收到 ⇒
     *  体感「它回一句就不理我了」。上限收到 900ms 后最坏窗口缩短 70%。 */
    private static final long RESUME_MAX_MS = 900L;
    private static final long RESUME_POLL_MS = 150L;

    private long resumeWaitedMs = 0L;

    /**
     * v1.3.12：TTS 结束后延迟恢复上行 —— 等本地播放队列排空（TtsPlayer.isDrained），
     * 至少 RESUME_MIN_MS、至多 RESUME_MAX_MS。期间服务器已回到 listening，
     * 但我们不送音频，避免把自己的 TTS 尾巴当作用户语音（自环）。
     */
    private final Runnable resumeUplinkTask = new Runnable() {
        public void run() {
            resumeWaitedMs += RESUME_POLL_MS;
            if (state != State.LISTENING) {
                // v1.3.46 ★ **死锁修复**。旧实现在这里直接 return 且不再排下一次：
                // 只要这次 run() 赶在状态切到 LISTENING 之前（或状态卡在
                // TTS_PLAYING / CONNECTING / 僵尸 LISTENING），`suppressUplink`
                // 就**永久卡在 true** —— 用户直接说话被 onFrame 丢弃，而唯一会
                // 无条件放行上行的是唤醒路径（handleWakeup 里 suppressUplink=false）
                // ⇒ 体感正是「回一句后彻底沉默，必须重新喊唤醒词才能继续」。
                // 现在：继续排到硬上限，超限则无条件放行（宁可放进一点 TTS 尾巴，
                // 也绝不能让上行死锁）。
                if (resumeWaitedMs >= RESUME_MAX_MS) {
                    suppressUplink = false;
                    Log.i(TAG, "uplink force-resumed (state=" + state
                            + " stuck, " + resumeWaitedMs + "ms)");
                    SessionLog.i("uplink force-resumed (state=" + state + " stuck)");
                    return;
                }
                main.postDelayed(this, RESUME_POLL_MS);
                return;
            }
            TtsPlayer p = player;
            boolean drained = (p == null) || p.isDrained();
            if (resumeWaitedMs < RESUME_MIN_MS
                    || (!drained && resumeWaitedMs < RESUME_MAX_MS)) {
                main.postDelayed(this, RESUME_POLL_MS);
                return;
            }
            suppressUplink = false;
            Log.i(TAG, "uplink resumed after TTS (waited=" + resumeWaitedMs
                    + "ms drained=" + drained + ")");
            SessionLog.i("uplink resumed after TTS (waited=" + resumeWaitedMs + "ms)");
        }
    };

    @Override
    public void onStt(String text) {
        Log.i(TAG, "STT: " + text);
        SessionLog.i("STT: " + text);
        disarmWakeProbe();   // v1.3.19：服务器听懂了 = 会话是活的，探测解除
        clearShadow();
        sttThisTurn = true;  // v1.3.23：有 STT = 服务器确实在回应，本轮不可能是「空闲告别」
        touchDownlink();   // v1.3.16
        // v1.3.14：延迟埋点 —— 唤醒 -> 服务器出 STT 文字（用户说完到出字）
        latSttMs = android.os.SystemClock.elapsedRealtime();
        if (wakeMs > 0L) {
            long d = latSttMs - wakeMs;
            Log.i(TAG, "lat: wake->STT = " + d + "ms");
            SessionLog.i("lat: wake->STT = " + d + "ms");
        }
    }

    @Override
    public void onClosed(String reason) {
        Log.i(TAG, "ws closed: " + reason);
        SessionLog.i("ws closed: " + reason);
        enterIdle();
    }

    @Override
    public void onError(String msg) {
        Log.e(TAG, "ws error: " + msg);
        SessionLog.i("ws error: " + msg);
    }

    // ---------------- 无 TTS 看门狗 ----------------

    private void armNoTtsWatchdog() {
        armNoTtsWatchdog(NO_TTS_TIMEOUT_MS);
    }

    /** v1.3.15：带时长的看门狗（TTS 后用 listenIdleMs，握手后仍用 25s） */
    private void armNoTtsWatchdog(long timeoutMs) {
        watchdogMs = timeoutMs;
        main.removeCallbacks(noTtsWatchdog);
        main.postDelayed(noTtsWatchdog, timeoutMs);
    }

    // ---------------- v1.3.16：TTS 流静默看门狗（僵尸会话根治） ----------------

    /**
     * 根因（2026-10-04 16:34 实测 session log）：
     * ```
     * 16:34:39.923 <- {"type":"tts","state":"start"...}
     * 16:34:41.224 <- {"type":"tts","state":"sentence_start","text":"欸，好像没人应我，我先休息一下…"}
     * （此后 2 分 22 秒再没有任何下行，既无 tts stop，也无 sentence_end）
     * ```
     * 服务器把这轮告别语讲完就静默了，**不下发 tts stop**。而 onTtsStart 里
     * removeCallbacks(noTtsWatchdog) 已经把唯一的计时器撤掉 → 状态机永久停在
     * TTS_PLAYING、本地空闲断开（120s）也武装不上 → 这条会话变成僵尸：
     * 唤醒要么做无意义的 barge-in、要么被当重复唤醒忽略（16:36 两次喊
     * 「小智小智」全无响应就是这个）。最后靠 socket 自己超时（16:37:01）才解脱。
     *
     * 这里补一个看门狗：只要 TTS_STALE_MS(15s) 内既没有新 TTS 音频、也没等到
     * tts stop，就判定这轮 TTS 已经结束。
     *
     * v1.3.19：判定之后**不再「补一次 onTtsStop 收成 LISTENING」**，而是直接关掉
     * 这条会话。理由：服务器 TTS 讲完却迟迟不发 tts stop = 它已经把会话收了
     * （实测告别语就是这种形态）。旧做法把它收尾成 LISTENING，等于留下一具
     * 「假装在听」的僵尸会话 —— 用户唤醒打在它上面毫无反应（2026-10-04 16:36
     * 连喊两次「小智小智」全无响应）。现在直接 close → IDLE，下一次唤醒干净地
     * 开新会话。
     */
    private final Runnable ttsStaleCheck = new Runnable() {
        public void run() {
            if (state != State.TTS_PLAYING) {
                return;
            }
            long idle = android.os.SystemClock.elapsedRealtime() - lastTtsFrameMs;
            if (idle > TTS_STALE_MS) {
                String m = "TTS stream stalled " + idle
                        + "ms (no audio, no tts stop) -> server likely ended session, closing";
                Log.i(TAG, m);
                SessionLog.i(m);
                ws.close("tts-stale");
                return;
            }
            main.postDelayed(this, 2000L);
        }
    };

    // ---------------- v1.3.15：断开前提醒（v0.3.10 已移除） ----------------

    /**
     * v0.3.10：续听窗口末端**不再播本地提示音**，窗口到期直接静默断开。
     * 历史（v1.3.15~v0.3.9）：窗口末端前 15s 播两声短音「即将断开」，作为服务器
     * 沉默时的兜底提醒。2026-10-25 用户反馈：这个声音固定在 60s 窗口边缘响起，
     * 而「60s 空闲断开」的设计目的就是不让小智播报退出语音 —— 本地再补一声反而
     * 违背初衷；且它不受「提示音」开关（cueOn）控制，关不掉。整个 idleAlertTask、
     * scheduleIdleAlert、playLocalCue、buildCuePcm 一并删除。提示音现在只在两个
     * 真正的节点响：唤醒（叮）与退出小智（叮-咚），统一由 AppSettings.cueOn 管。
     */

    /**
     * v0.3.9：退出提示音 —— 与唤醒提示音同一开关（AppSettings.cueOn）。
     * 退出小智时播放，下行双音（1175 → 880Hz）表示「再见」。
     * 复用 TtsPlayer 的 AudioTrack；播放总长同样要越过启动水位。
     */
    public static void playExitCue() {
        SessionController sc = I;
        if (sc == null || sc.player == null) {
            return;
        }
        if (!AppSettings.cueOn(sc.appContext)) {
            return;
        }
        sc.player.push(buildExitAckPcm());
        Log.i(TAG, "exit cue played");
        SessionLog.i("exit cue played");
    }

    /** 合成退出提示音 PCM：1175Hz 150ms + 静音 70ms + 880Hz 220ms + 静音补齐到水位+60 */
    private static byte[] buildExitAckPcm() {
        int rate = AudioCapture.SAMPLE_RATE;
        int[][] segs = {{1175, 150}, {0, 70}, {880, 220}};
        int total = 0;
        for (int[] s : segs) {
            total += rate * s[1] / 1000;
        }
        int totalMs = Math.max(total * 1000 / rate, TtsPlayer.startWatermarkMs + 60);
        byte[] out = new byte[rate * totalMs / 1000 * 2];
        int idx = 0;
        int fade = rate / 200; // 5ms
        for (int[] s : segs) {
            int n = rate * s[1] / 1000;
            for (int i = 0; i < n; i++) {
                double v = s[0] == 0 ? 0.0
                        : Math.sin(2.0 * Math.PI * s[0] * i / rate);
                double env = 1.0;
                if (i < fade) {
                    env = (double) i / fade;
                } else if (i > n - fade) {
                    env = (double) (n - i) / fade;
                }
                short sm = (short) (v * env * 0.30 * 32767);
                out[idx++] = (byte) (sm & 0xff);
                out[idx++] = (byte) ((sm >> 8) & 0xff);
            }
        }
        return out; // 余下为 0（静音）
    }

    // ---------------- v1.2.15：USC 错误提示静音 ----------------

    /**
     * 媒体流静音开关。setStreamMute 在 API 23 弃用，但设备停在 5.1.1(API 22)，
     * 这是该版本唯一不弹 UI 的静音 API（adjustStreamVolume ADJUST_MUTE 是 22 新增
     * 但在本机型 AudioService 上行为未验证，不做首选）。
     * AudioService 按「调用次数」计 mute：true 一次必须配 false 一次，
     * uscMuted 守卫保证配对，防计数紊乱把设备永久哑掉。
     */
    @SuppressWarnings("deprecation")
    private void setMediaMute(boolean mute, String why) {
        try {
            AudioManager am = (AudioManager)
                    appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            am.setStreamMute(AudioManager.STREAM_MUSIC, mute);
            uscMuted = mute;
            String m = "media mute=" + mute + " (" + why + ")";
            Log.i(TAG, m);
            SessionLog.i(m);
        } catch (Throwable t) {
            Log.e(TAG, "setMediaMute threw", t);
        }
    }

    /** 服务销毁兜底：绝不能让设备哑着进入下一个生命周期 */
    public void release() {
        main.removeCallbacks(uscUnmute);
        if (uscMuted) {
            setMediaMute(false, "service release");
        }
    }
}
