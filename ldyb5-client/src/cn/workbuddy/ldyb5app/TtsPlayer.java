package cn.workbuddy.ldyb5app;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * TTS 播放：AudioTrack + jitter buffer。
 *
 * 关键修复（吸取 C3 主线死锁教训）：
 *   C3 的 jitter buffer 死锁根因是「可播判定 dlAvail()>=6600 放在 fifoPush 之前」，
 *   导致单帧 FIFO 永远无法触发 startDlPlay。
 *   这里严格保证：**先 push 入队，再判定能否启动播放**。
 *
 * 另一处 C3 踩过的坑：服务器 1.0x 实时节流会在长合成时出现 150~350ms 供给间隙。
 *   对策：缓冲水位设 400ms，低于水位不启动播放；播放中若欠载则输出静音填充而非卡死。
 */
public final class TtsPlayer {

    private static final String TAG = "L5CLIENT";

    /**
     * 启动播放所需的最小缓冲（毫秒）— 抵抗服务器供给抖动。
     * v1.3.14：400 -> 250。服务器按 1.0x 实时供给，凑够水位才出声 = 直接首字延迟。
     * v1.3.18：250 -> 180。v1.3.16 已把欠载处理改成「先短等新帧(100ms)、真见底才补
     * 静音」的软件水位模型，不再依赖大水位抗抖 → 水位可以压到 180ms，首声更快。
     * ldyb5：官方服务器走公网，实测帧到达 maxGap=240ms（LAN NAS 无此问题），
     * 180ms 水位频繁见底补静音 = 听感卡顿。默认先提到 400；files/tsbuf<N> 可调。
     * v0.3.56：允许 0 = 完全不做软件预缓冲（实验用，第一帧到达即写 AudioTrack）。
     * v0.3.58：默认 400 -> 600 定档。v0.3.55 起的逐帧埋点显示真实服务端每
     * ~340ms 推一批帧、偶发 405~536ms 迟到批次；400 水位会在句中补 60ms
     * 静音（用户可听出「嘉兴」「好」字后微顿），600 能盖住且 20:00~20:21
     * 的 16 条真实播放（含 745/558 帧长回复）全部 mid=0。探针里首字
     * 363->545ms 的 +180ms 只在匀速喂帧下成立；真实批量推帧时首字
     * 282~432ms，与 400 水位同量级，几乎没有额外开口代价。
     * 注意：静态字段不随服务重启复位；同进程内若用 flag 实验过其它值，
     * 需显式放 tsbuf600 或杀进程重启才回到默认 600。
     */
    public static volatile int startWatermarkMs = 600;

    /**
     * 完全空闲多久后判定 TTS 流结束（毫秒）。
     * v1.3.14：1500 -> 600。此值直接决定「TTS 播完后多久服务器才允许重新听到我们」
     * （SessionController 的 resume 依赖 isDrained()）。600ms 仍 > 服务器最大供给
     * 间隙 350ms，安全；但把多轮续听的死区从 ~1.65s 压到 ~0.75s。
     */
    private static final int DRAIN_TIMEOUT_MS = 600;

    /**
     * v1.3.48 ★★ 回声自环根除尝试：TTS 数据发完后**继续以实时节奏往 AudioTrack 写静音**
     * 这么久（毫秒），让"播放流"保持活跃。
     *
     * 实机取证（2026-10-05 21:34~21:36，jp2 v1.3.47，日志 diag/jp2log_now.txt）：
     *   TTS 播放期          mic 电平  77~430    （HAL 回声抑制在线 → 干净）
     *   TTS 停止后 0.7~4.7s mic 电平 479~1387   （抑制失效 → TTS 回声漏进麦克风）
     *   → 上行把这些回声送回服务器 → STT 逐字复现上一轮 TTS 原文 → 无限自环
     *     （STT「听得到听得到，我一直都在呢。」== 5.1s 前我们刚播的那句）
     *
     * 推论：R1 的音频链路只在**存在活跃播放流**时才启用回声抑制。TTS 一停，
     * 播放流 underrun，抑制随即退出，此前被压住的喇叭残余立刻出现在麦克风上。
     * 对策：让播放流别停 —— 数据发完后继续写静音，把抑制"钉"住。
     * 续写的是**纯数字静音**，不含任何声学参考，因此只压喇叭回声、不压用户说话。
     *
     * 0 = 关闭（回退 v1.3.47 行为）。files/ttshold<N> 覆盖（0..15000）。
     */
    public static volatile int holdSilenceMs = 4000;

    private static final int SAMPLE_RATE = AudioCapture.SAMPLE_RATE;

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<byte[]>();
    private final Object lock = new Object();

    private AudioTrack track;
    private Thread player;
    private volatile boolean running;
    private volatile boolean playing;   // 是否已越过水位启动
    private long lastPushTime;
    /** v1.3.14：本轮首个 TTS 帧到达时刻（毫秒），用于量「首帧->出声」的缓冲等待 */
    private long firstPushMs;

    // ================= v1.3.16：卡顿取证埋点 =================
    /** 本轮实际写入 AudioTrack 的帧数 */
    private long frameCount;
    /** 本轮因欠载补写的静音总时长（毫秒） */
    private long silenceMs;
    /**
     * v0.3.52：把 silenceMs 拆开。历史日志里 48 句有 47 句 silenceMs=240~300ms，
     * 但 maxGapMs 中位只有 175ms —— 说明大部分是「句尾等 drained 的 600ms 空窗」
     * 里补的数字静音（听感上只是句子后的留白），不是句中卡顿。
     * 判据：距最后一帧 <300ms 补的算句中（真卡顿），否则算句尾。
     */
    private long midSilenceMs;
    private long tailSilenceMs;
    /** 上一帧写入时刻，用于算相邻帧到达间隔 */
    private long lastFrameMs;
    /** 本轮相邻两帧的最大到达间隔（毫秒）—— 直接反映服务器供给抖动幅度 */
    private long maxGapMs;
    /** v0.3.56：最大间隙发生在「距起播」第几毫秒。gaps= 只留最早 8 条，
     *  长回复里最大的那次间隙常被挤掉，这个字段保证一定能对到字。 */
    private long maxGapAtMs;

    /**
     * v0.3.55 ★ 句中卡顿取证修正（用户实测「卡在『嘉兴』两个字」）。
     *
     * 旧判据在补静音当场用「距最后一帧 &lt;300ms」区分 mid / tail，但公网服务器
     * 一旦出现 503ms 的供给间隙，间隙里补的静音 now-lastPushTime 必然 &gt;300ms，
     * 会被错判成 tail —— 于是日志 mid=0 看起来「播放没断」，实际句中已经断了。
     *
     * 改为**延迟归类**：补的静音先挂 pendingSilenceMs，之后若又来了新帧，说明
     * 它发生在句中 -> mid；直到 drained 都没新帧 -> tail。判据与间隙长度无关。
     */
    private volatile long pendingSilenceMs;
    /** v0.3.55：帧到达间隔 &gt;250ms 的事件，"距起播ms:间隔ms"，最多 8 条 */
    private volatile String gapEvents = "";
    private int gapEventCount;
    /** v0.3.55：补静音事件，"距起播ms:距末帧ms"，最多 8 条 */
    private volatile String fillEvents = "";
    private int fillEventCount;

    /**
     * v1.3.16：纯软件「音频轨水位」模型。
     *   buffered = writtenMs - (now - playStartMs)
     * 只有在 buffered 见底时才允许补静音 —— 否则空等（AudioTrack 自己的缓冲
     * 会把服务器抖动吸收掉）。用它替代「队列一空就写静音」的老逻辑。
     */
    private long playStartMs;
    /** 本轮已写入 AudioTrack 的音频总时长（毫秒，含补的静音） */
    private long writtenMs;
    /** 音频轨里始终保留的最小余量（毫秒）—— 留一点避免写到 0 造成爆音 */
    private static final long MIN_RESERVE_MS = 60L;

    /** v1.3.48：静音续播截止时刻（System.currentTimeMillis；0 = 非续播态） */
    private volatile long holdUntilMs = 0L;

    public void start() {
        if (running) {
            return;
        }
        // v1.2.3 关键修复：不再调用 AudioTrack.getMinBufferSize()。
        // 它是同步 binder 调用（AudioSystem::getOutput → AudioFlinger 全局锁），
        // mediaserver 音频 HAL 死锁时永不返回 —— v1.2.2 的 ANR 根因，
        // 当时 main 线程就卡死在这里（见 DIAG-v1.2.2.md §1）。
        // minBuf 只是建议值：R1 实机健康时返回的正是 4096，直接给固定值，
        // write() 会自动分块，行为无差别，却彻底绕开这条 binder 调用。
        final int minBuf = 4096;
        // v0.3.52：512ms -> 1s。实测空闲时四麦 HAL 原生线程 + L5MicReader 常驻
        // 吃掉 120% CPU（4 核 A7），播放线程偶发被调度延迟；512ms 硬件缓冲被耗尽
        // 就是「说话偶尔卡顿一下」的听感来源。1s 缓冲吃掉这段抖动，起播延迟不变，
        // 打断时 flush 反而清得更干净。bufBytes=32768B @16k/mono/16bit。
        int bufBytes = Math.max(minBuf * 8, SAMPLE_RATE * 2); // 至少 1s
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufBytes, AudioTrack.MODE_STREAM);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack init failed");
            track.release();
            track = null;
            return;
        }
        running = true;
        track.play();
        player = new Thread(new Runnable() {
            public void run() {
                // v0.3.52 ★ 卡顿根因修复：音频**输出**线程必须比常驻采集线程优先。
                // AudioCapture 的 L5MicReader 被设成 Thread.MAX_PRIORITY，四麦 HAL
                // 的 mic-acquire 原生线程空闲就占 ~0.5 核，两者合计 120% CPU；
                // 本线程此前只是 NORM_PRIORITY，CPU 饱和时 track.write() 被推迟，
                // AudioTrack 硬件缓冲见底 -> 可听的断流/吞字。URGENT_AUDIO 是
                // Android 给音频输出线程的标准优先级（nice -19），高于 Java
                // MAX_PRIORITY 映射的 nice 值。只改调度优先级，不动任何时序逻辑。
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                } catch (Throwable ignored) {
                }
                loop();
            }
        }, "R1TtsPlayer");
        player.setPriority(Thread.MAX_PRIORITY);
        player.start();
        Log.i(TAG, "TtsPlayer started, minBuf=" + minBuf + "(fixed) buf=" + bufBytes
                + " prio=URGENT_AUDIO");
    }

    /** 是否已成功启动（AudioTrack 就绪且播放线程在跑） */
    public boolean isStarted() {
        return running;
    }

    /** 送入一包已解码的 PCM */
    public void push(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        if (firstPushMs == 0L) {
            firstPushMs = System.currentTimeMillis();
        }
        queue.offer(pcm);
        synchronized (lock) {
            lastPushTime = System.currentTimeMillis();
            lock.notifyAll();
        }
    }

    /** TTS 开始：清空历史残留 */
    public void onTtsStart() {
        queue.clear();
        synchronized (lock) {
            playing = false;
        }
        firstPushMs = 0L;   // v1.3.14：新一轮重新计首帧时刻
        holdUntilMs = 0L;   // v1.3.48：新一轮 TTS 取消上一轮的静音续播
        resetStats();       // v1.3.16：统计按「句」重置
        Log.i(TAG, "TTS start, buffer cleared");
    }

    /**
     * v1.3.16：本句是否还在出声（含启动前的水位填充期）。
     *
     * 用途：SessionController 收到唤醒词时要区分两种情况 ——
     *   true  = TTS 真的在播 → 正常 barge-in（清队列、转 LISTENING）；
     *   false = 状态机还挂在 TTS_PLAYING 但早就不出声了（服务器发完告别语
     *           却永不下发 tts stop）→ 这是僵尸会话，必须换新会话重来。
     */
    public boolean isPlaying() {
        // v1.3.48：静音续播期（holdUntilMs != 0）对外**不算"在出声"** —— 它只是
        // 用静音把播放流钉住以维持 HAL 回声抑制；语义上此时喇叭是无声的。
        return (playing && holdUntilMs == 0L) || !queue.isEmpty();
    }

    /** TTS 结束：仅标记，剩余音频继续播完（drain） */
    public void onTtsEnd() {
        Log.i(TAG, "TTS end signalled, queued=" + queue.size());
    }

    /**
     * v1.3.49：**真·打断** —— 清空软件队列 **并冲掉 AudioTrack 硬件缓冲**。
     *
     * 为什么必须 flush：`onTtsStart()` 只清 `queue`，而 AudioTrack 里还压着最多
     * 512ms（bufBytes=16384B @16k/mono）已经写进去的 TTS。唤醒词打断之后我们
     * 立刻恢复上行，这 512ms 尾巴会**紧接着**被麦克风录进去（R1 无 AEC）——
     * 正是"自己回自己"的入口。pause→flush→play 让喇叭当场归零。
     */
    public void abort() {
        queue.clear();
        synchronized (lock) {
            playing = false;
            holdUntilMs = 0L;   // 打断也撤销静音续播
            lock.notifyAll();
        }
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.play();
            } catch (Throwable e) {
                Log.w(TAG, "AudioTrack flush failed", e);
            }
        }
        resetStats();
        Log.i(TAG, "TTS aborted (queue cleared + AudioTrack flushed)");
        SessionLog.i("TTS aborted (queue + AudioTrack flushed)");
    }

    /**
     * v1.3.12：本地播放是否已排空（队列空 且 未在播放）。
     *
     * 用途：TTS 结束后服务器要重新 listen（多轮对话），但我们得等本地喇叭
     * 把缓冲里的 TTS 播完才恢复上行 —— 否则自己 TTS 的尾巴会被服务器 VAD
     * 当作用户插话，触发自环（C3 主线踩过的坑）。
     */
    public boolean isDrained() {
        return !playing && queue.isEmpty();
    }

    private void loop() {
        while (running) {
            // --- 第一段：等待越过水位后启动播放 ---
            if (!playing) {
                // v0.3.56：wm=0（完全不缓冲）时 bufferedMs() 恒 >= 0，若不加
                // queue.isEmpty() 守卫，空队列也会立刻 playing=true，紧接着
                // poll() 拿到 null、被当成 drained，统计被切碎。加上之后语义是
                // 「第一帧到达就起播」；wm>0 时该条件不改变原有行为。
                if (bufferedMs() < startWatermarkMs || queue.isEmpty()) {
                    if (!waitForData(200)) {
                        continue;
                    }
                    continue;
                }
                playing = true;
                playStartMs = System.currentTimeMillis();
                writtenMs = 0L;
                long waited = (firstPushMs == 0L) ? -1L
                        : (System.currentTimeMillis() - firstPushMs);
                Log.i(TAG, "playback started, buffered=" + bufferedMs()
                        + "ms, waited=" + waited + "ms, wm=" + startWatermarkMs + "ms");
                SessionLog.i("lat: tts-first-frame->play-start = " + waited + "ms");
            }

            byte[] chunk = queue.poll();
            if (chunk == null) {
                long now = System.currentTimeMillis();
                // 播放中欠载。
                if (playing && holdUntilMs == 0L
                        && now - lastPushTime > DRAIN_TIMEOUT_MS) {
                    // v1.3.48：TTS 数据真的发完了。
                    // v0.3.55：直到 drained 都没再来帧 -> 挂起的静音是句尾留白
                    if (pendingSilenceMs > 0L) {
                        tailSilenceMs += pendingSilenceMs;
                        pendingSilenceMs = 0L;
                    }
                    Log.i(TAG, "TTS drained" + stats());
                    SessionLog.i("tts play summary" + stats());
                    resetStats();
                    if (holdSilenceMs > 0) {
                        // v1.3.48：转入"静音续播" —— 播放流不停，HAL 回声抑制不掉线
                        holdUntilMs = now + holdSilenceMs;
                        Log.i(TAG, "TTS hold-silence " + holdSilenceMs + "ms");
                        SessionLog.i("tts hold-silence start " + holdSilenceMs
                                + "ms (playback stream kept active -> HAL echo-suppression on)");
                    } else {
                        playing = false;
                    }
                }
                if (playing && holdUntilMs != 0L && now >= holdUntilMs) {
                    holdUntilMs = 0L;
                    playing = false;
                    Log.i(TAG, "TTS hold-silence end -> real silence");
                    SessionLog.i("tts hold-silence end -> real silence");
                }
                if (!playing) {
                    // 还没越过水位 / 续播已结束 → 什么都不写，空等新帧
                    waitForData(200);
                    continue;
                }
                // ===== v1.3.16 卡顿根因修复 =====
                // 旧实现：队列一空就 writeSilence(60)，而且循环里没有任何节流 ——
                // 服务器 150~350ms 的供给抖动会让它在一个瞬间连写十几次静音，
                // 把 AudioTrack 的 512ms 缓冲整个灌成静音；随后到达的真音频只能
                // 排在静音后面播 → 听感就是「卡顿 + 吞字」。
                //
                // 现在改用一个纯软件的「音频轨水位」模型来判断能不能什么都不做：
                //     buffered = 已写入音频轨的毫秒数 - 播放已进行的毫秒数
                // buffered > 0 表示轨里还压着这么多音频，此刻完全可以空等；
                // 只有 buffered 见底（真的快饿死）才补静音。
                // v1.3.48：静音续播期（holdUntilMs != 0）同样走这条水位线 —— 于是
                // 静音是以**实时速率**持续写入的，播放流整体保持"正在播"。
                long buffered = writtenMs - (now - playStartMs);
                if (buffered > MIN_RESERVE_MS) {
                    // 轨里还有余量 → 等新帧（push() 会 notifyAll 立刻唤醒），不写静音
                    waitForData(Math.min(200L, buffered - MIN_RESERVE_MS));
                    continue;
                }
                int fill = 60;
                long sinceLast = now - lastPushTime;
                // v0.3.55：不再当场用 300ms 阈值判 mid/tail（>300ms 的供给间隙会被
                // 错判成句尾留白）。先挂起，等下一帧到达或 drained 再归类。
                pendingSilenceMs += fill;
                silenceMs += fill;
                if (fillEventCount < 8) {
                    fillEvents = fillEvents + (fillEventCount > 0 ? "," : "")
                            + (now - playStartMs) + ":" + sinceLast;
                    fillEventCount++;
                }
                writtenMs += fill;
                writeSilence(fill);
                continue;
            }
            long nowMs = System.currentTimeMillis();
            if (lastFrameMs != 0L) {
                long gap = nowMs - lastFrameMs;
                if (gap > maxGapMs) {
                    maxGapMs = gap;
                    maxGapAtMs = nowMs - playStartMs;
                }
                // v0.3.55：记录长间隙发生的「距起播时刻」——能把卡顿直接对到
                // 句子里的第几个字（例如「嘉兴」≈ 起播后 400~800ms）。
                if (gap > 250L && gapEventCount < 8) {
                    gapEvents = gapEvents + (gapEventCount > 0 ? "," : "")
                            + (nowMs - playStartMs) + ":" + gap;
                    gapEventCount++;
                }
            }
            // v0.3.55：补静音之后又收到真帧 -> 那段静音发生在句中
            if (pendingSilenceMs > 0L) {
                midSilenceMs += pendingSilenceMs;
                pendingSilenceMs = 0L;
            }
            lastFrameMs = nowMs;
            frameCount++;
            writtenMs += chunk.length * 1000L / (SAMPLE_RATE * 2);
            try {
                track.write(chunk, 0, chunk.length);
            } catch (Throwable t) {
                Log.e(TAG, "AudioTrack.write threw", t);
            }
        }
        Log.i(TAG, "TtsPlayer loop exit");
    }

    /** v1.3.16 / v0.3.52 / v0.3.55：本句播放统计（判卡顿看 mid 与 gaps，tail 不算） */
    private String stats() {
        String s = " (frames=" + frameCount + " silenceMs=" + silenceMs
                + " mid=" + midSilenceMs + " tail=" + tailSilenceMs
                + " maxGapMs=" + maxGapMs + " wm=" + startWatermarkMs;
        if (maxGapMs > 0L) {
            s += " maxGapAtMs=" + maxGapAtMs;
        }
        if (gapEvents.length() > 0) {
            s += " gaps=[" + gapEvents + "]";
        }
        if (fillEvents.length() > 0) {
            s += " fills=[" + fillEvents + "]";
        }
        return s + ")";
    }

    private void resetStats() {
        frameCount = 0;
        silenceMs = 0;
        midSilenceMs = 0;
        tailSilenceMs = 0;
        lastFrameMs = 0L;
        maxGapMs = 0;
        maxGapAtMs = 0L;
        pendingSilenceMs = 0L;
        gapEvents = "";
        gapEventCount = 0;
        fillEvents = "";
        fillEventCount = 0;
    }

    private void writeSilence(int ms) {
        if (track == null) {
            return;
        }
        int bytes = SAMPLE_RATE * 2 * ms / 1000;
        byte[] z = new byte[bytes];
        try {
            track.write(z, 0, bytes);
        } catch (Throwable ignored) {
        }
    }

    private int bufferedMs() {
        int bytes = 0;
        for (byte[] b : queue) {
            bytes += b.length;
        }
        return bytes * 1000 / (SAMPLE_RATE * 2);
    }

    private boolean waitForData(long ms) {
        synchronized (lock) {
            try {
                lock.wait(ms);
            } catch (InterruptedException ignored) {
            }
        }
        return true;
    }

    /**
     * v0.3.52 调试探针：用数字静音按 1.0x 实时节奏喂 frames 帧（60ms/帧），
     * 走完整真实播放链路（起播水位 -> 欠载补静音 -> drained -> hold-silence），
     * 用于 PC 侧可重复量测「补了多少静音」，不必真的说话、也不会出声。
     * jitterMs > 0 时每 10 帧额外停这么久，模拟公网供给抖动。
     *
     * 用法：adb shell am startservice -n cn.workbuddy.ldyb5app/.Ldyb5ClientService
     *        --es tts_sim 60 --es sim_jitter 200
     * 结果看 logcat / ldyb5app.log 里的 "TTS drained (... mid=... tail=...)"。
     */
    public void simulate(final int frames, final int jitterMs) {
        new Thread(new Runnable() {
            public void run() {
                if (!running) {
                    return;
                }
                onTtsStart();
                final int frameBytes = SAMPLE_RATE * 2 * 60 / 1000;
                long t0 = System.currentTimeMillis();
                for (int i = 0; i < frames && running; i++) {
                    push(new byte[frameBytes]);
                    int nap = 60;
                    if (jitterMs > 0 && i > 0 && i % 10 == 0) {
                        nap += jitterMs;
                    }
                    try {
                        Thread.sleep(nap);
                    } catch (InterruptedException ignored) {
                    }
                }
                String m = "TTS sim fed frames=" + frames + " jitter=" + jitterMs
                        + "ms wall=" + (System.currentTimeMillis() - t0)
                        + "ms wm=" + startWatermarkMs;
                Log.i(TAG, m);
                SessionLog.i(m);
            }
        }, "ldyb5-tts-sim").start();
    }

    public void stop() {
        running = false;
        holdUntilMs = 0L;   // v1.3.48
        synchronized (lock) {
            lock.notifyAll();
        }
        if (player != null) {
            try {
                player.join(500);
            } catch (InterruptedException ignored) {
            }
            player = null;
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
            track = null;
        }
        queue.clear();
        Log.i(TAG, "TtsPlayer stopped");
    }
}
