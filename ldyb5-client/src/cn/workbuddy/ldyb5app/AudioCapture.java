package cn.workbuddy.ldyb5app;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.SystemClock;
import android.util.Log;

/**
 * 音频采集：v1.3.9 起 16kHz / 16bit / mono；v1.3.10 起采集源与 AEC 都改为
 * 文件开关（默认 VOICE_COMMUNICATION + AEC 关，见 aecEnabled / useVoiceRec）；
 * v1.3.11 再加两根杠杆：sessionBind（同 session 常驻静音播放流 -> 逼 HAL 进全双工
 * 语音用例）与 uplinkGain（上行软件增益）。KWS / opus 上行协议不变（hello 16k/1ch/60ms）。
 *
 * v1.3.9 依据（2026-10-04）：逆向「一键安装Unisound_v1.6.13」里那个能在同一台
 * R1 上正常收音的社区版小智 App（com.huihongcloud.xiaozhi v1.8），反汇编实证其
 * 录音 = AudioRecord(VOICE_COMMUNICATION, 16000, mono, PCM16, 960) +
 * AcousticEchoCanceler.create(session).setEnabled(true)，且该 App 无任何 R1
 * 特化代码、禁用 Pandora 也正常。→ 判定 R1 的 AK7755 HAL 只在「通话/语音」
 * 用例下把 ADC 接到真实麦阵，其它 source（MIC / VOICE_RECOGNITION）停在悬空
 * 输入（正是我们量到的 0x00/0xff 底噪、rms≈500 平线、peak 恒 0x0D0D 画像）。
 *
 * 历史（v1.3.4/1.3.5 排案）：mono 与 stereo 两种声道都拿到过悬空底噪，
 * 说明差异不在声道数；VOICE_COMMUNICATION 是唯一从未干净测过的变量。
 *
 * 设计要点：
 * 1. 单一 AudioRecord 被 KWS 与上行编码链路共享（避免四麦 HAL 二次打开）。
 * 2. 环形预缓冲 500ms：唤醒瞬间把「唤醒词之前」的音频一并上行。
 * 3. 读取线程 MAX_PRIORITY，避免欠载。
 * 4. onFrame 给出 mono（消费用）与 HAL 原始帧（取证 dump 用）；mono 缓冲是
 *    复用的，接收方如需持有必须自行拷贝。
 */
public final class AudioCapture {

    private static final String TAG = "L5CLIENT";

    public static final int SAMPLE_RATE = 16000;
    /**
     * HAL 打开的声道数。v1.3.9：2 -> 1。
     * 依据 = 参考 App（com.huihongcloud.xiaozhi）用 mono 开流即可正常收音；
     * 同时让 hello 声明的 channels（XiaoZhiWs 引用本常量）与实际发送的
     * mono opus 帧一致（此前声明 2 却发 mono，属协议内部不一致）。
     */
    public static final int CHANNELS = 1;
    public static final int BYTES_PER_SAMPLE = 2;

    /** 60ms @ 16kHz（单声道样点数） */
    public static final int FRAME_SAMPLES = 960;
    /** 下混 mono 帧字节数（KWS / opus / 上行协议用） */
    public static final int FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE; // 1920
    /** HAL 原始帧字节数（mono 时 == FRAME_BYTES） */
    public static final int STEREO_FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE * CHANNELS; // 1920

    /**
     * v1.3.35 ★★ 四麦 HAL 的**真实输出声道数** = 2（与协议/hello 的 CHANNELS 解耦）。
     *
     * 三重实证：
     *   ① HAL 启动日志我们进程自己打的：`ch_num=2`；`openAudioIn(2)` 的入参就是声道数；
     *   ② `uni_4mic_test -cap x.pcm -c 2 -t 5` → 320,000 B / 5s = **64,000 B/s** = 16k×2ch×2B；
     *      HAL 自 dump 的 `waking_file_out.wav` 同规格（见 diag/pandora/VERDICT-uni4mic-dump-activation.md）；
     *   ③ 反向校验我们的消费速率：2,371,200 B / 74 s = **32,046 B/s，正好是 HAL 产量的一半**。
     *
     * 为什么是「四麦直采后仍无法唤醒」的**根因**：
     *   v1.3.9~v1.3.34 这里按 mono 读 → 每秒只排空一半 → HAL 的 Record ring
     *   （initRingBuf: Record, size=2457600）持续积压 →
     *   `2457600/2 ÷ 32768 ≈ 38 s` 开始溢出（**实测 open 后 34 s 起，80 次/秒**）；
     *   更致命的是：交错的 L/R 被当 mono 解读 = **音频被拉伸 2 倍（低一个八度）**，
     *   服务器 ASR 与本地 KWS（zipformer）**双双失聪** —— 模型再灵敏也没用。
     *
     * ⚠️ 不要改 CHANNELS：XiaoZhiWs 的 hello 与上行 opus 帧都引用它，必须保持 1。
     */
    private static final int RAW_CHANNELS = 2;
    /** 采集累积一整帧所需的原始字节数（RAW_CHANNELS=2 时 = 3840 = 60ms） */
    private static final int RAW_FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE * RAW_CHANNELS; // 3840

    /**
     * v1.3.35：2ch 下混策略。默认**取平均**（沿用 downmix 原有写法）。
     * `touch files/hal_ch0` → 只取第 0 声道（不做平均），用于 A/B 判定
     * HAL 两声道到底是「双拾取」还是「处理后 + 回声参考」。
     * 若平均引入了回声（TTS 自触发），就切这个开关。
     */
    public static volatile boolean halTakeCh0 = false;
    /** HAL 打开用的 channel mask（跟随 CHANNELS） */
    private static final int CHANNEL_CFG = (CHANNELS == 1)
            ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;

    /** 环形预缓冲时长（毫秒），唤醒前的音频回溯窗口 */
    private static final int PREROLL_MS = 500;
    private static final int PREROLL_FRAMES = PREROLL_MS / 60 + 2;

    public interface FrameListener {
        /**
         * 每产出 60ms 一帧回调。
         * @param mono   mono 单声道帧，固定 FRAME_BYTES（复用缓冲，勿持有引用）
         * @param len    mono 帧长度（恒为 FRAME_BYTES）
         * @param stereo HAL 原始帧（mono 时与 mono 等长，每帧新数组）
         */
        void onFrame(byte[] mono, int len, byte[] stereo);
    }

    /**
     * v1.3.10：AEC 开关（默认关）。
     * v1.3.9 实测（2026-10-04 15:35）：VOICE_COMMUNICATION + 系统 AEC 把底噪
     * rms≈500 平线压到 13（悬空噪消灭 ✓），但人声一起没了——说话段 rms 仅
     * 23~41、peak 从 661 递减到 103（AEC 收敛指纹），服务器 25s 无 TTS。
     * 参考 App 之所以没事：它的 AudioTrack 与 AudioRecord 共用同一 session，
     * AEC 拿得到正确回声参考；我们只抄了 AEC、没抄共 session → AEC 把近端
     * 人声误判为回声消掉。→ 默认关，`touch files/aec_on` 可显式打开做 A/B。
     */
    public static volatile boolean aecEnabled = false;

    /**
     * v0.3.19：TTS 播放期回声诊断标志。由 SessionController 在 TTS 起停时置位，
     * telemetry() 据此打印上行 RMS，确证硬件 AEC 是否消掉小智自己的 TTS 回声。
     */
    public static volatile boolean ttsActive = false;

    /**
     * v1.3.10：采集源开关（默认 false = VOICE_COMMUNICATION）。
     * `touch files/src_vrec` → VOICE_RECOGNITION（v1.2.x 老源，当年 rms≈500 悬空噪）。
     * 用于二分「VComm 是否接通真实麦阵」与「AEC 是否压制」两个变量。
     */
    public static volatile boolean useVoiceRec = false;

    /**
     * v1.3.11 引入，**v1.3.13 起默认 16**（开箱即用，无需任何 flag）。
     * 由 Ldyb5ClientService 从 files/gain1|gain4|gain8|gain16|gain32 覆盖读入
     * （命中第一个为准，gain1 优先级最高 = 显式关）。
     * 依据：v1.3.10 实测 VComm 已接通真实麦阵（rms 500 悬空噪 -> 13）但电平极低
     * ——说话 rms 仅 66、全程 peak<=603（满量程 2%），约低 30dB；v1.3.12 实测
     * gain16 后服务器 STT/TTS 全通（3 轮连续对话 + 天气工具调用 + 上下文继承）。
     * 施加**在遥测之前**，故 micTelem 显示的就是**实际上行电平**（已含增益），无需换算。
     */
    public static volatile int uplinkGain = 16;

    /**
     * v1.3.11：会话绑定（默认关）。files/sess_bind 存在 = 在**同一个 audio session** 上
     * 再挂一条常驻静音播放流（AudioTrack，永不 idle）。
     *
     * 依据 = 逆向「能正常收音的社区版小智」§2.2：它的 AudioTrack 用
     * `record.getAudioSessionId()` 构造（播放/录音共 session），且该播放流常驻不停。
     * Android HAL 只有看到「同一 session 同时有播放 + 采集」才把链路归入**全双工通话/语音**
     * 用例 —— 此时才会打开完整语音处理链（含上行 AGC），把远场麦阵电平抬起来。
     * 我们此前只抄了 source(VComm)+AEC，唯独没抄这层共 session，于是 HAL 按「纯录音」
     * 低速通路处理 -> 电平 2%。
     */
    public static volatile boolean sessionBind = false;

    /**
     * v1.3.23：**原厂四麦阵列 HAL**（默认关，`touch files/src_uni4mic` 打开）。
     *
     * 用户 2026-10-04 18:19 反馈：「收音灵敏度好像有点低，要离很近才能听到，
     * 原来的小讯是放很远轻轻说都能唤醒的」。
     *
     * 根因：我们拿到的是**裸 ADC 电平**（原厂反汇编实测 fallback AudioRecord
     * 用 AudioSource=0/DEFAULT；我们的 VOICE_COMMUNICATION 实测说话 rms 仅 66、
     * peak ≤603 = 满量程 2%），远场再掉 15~20dB 就沉进底噪了 —— 软件增益（×16）
     * 是对**已经采到的信号**等比放大，SNR 不变，救不了远场。
     *
     * 真正的远场通路是设备里那套优声四麦 HAL：
     *   /system/lib/libUni4micHalJNI.so + libuni4michal.so + libUniMicArray.so
     *   （原厂 `com.phicomm.speaker.device` 就是用它开麦的，
     *     见 `Lcom/unisound/sdk/x;` 反汇编：init(1)/openAudioIn(2)/startRecorder/
     *     readData(handle,buf,2400)）。
     * 走它就等于拿到**原厂那套波束成形 + 降噪 + AGC 的输出** —— 也就是「小讯」
     * 听到的那一路音频。
     *
     * 失败（so 加载不了 / init 或 openAudioIn 返回负值）自动回落 AudioRecord，
     * 不影响现有链路。
     */
    public static volatile boolean useUni4mic = false;

    /**
     * v1.3.28：MicBridge 模式（默认关，`touch files/src_bridge` 打开）。
     *
     * 依据 = 2026-10-05 D9 实测：Cap4mic（player 进程，platform 域）经原厂四麦
     * HAL openAudioIn 采到的信号，室内人声 SNR≈11.9dB，raw 电平即可被本地 ASR
     * 完整转写；且 HAL 自带高通+归一化（底噪 rms 5.5 vs uni_4mic 直采 19-24、
     * 0-300Hz 隆隆被滤掉）。本进程是 untrusted_app 域摸不到 /dev/snd（v1.3.23
     * uni4mic 直连失败根因），故借 player 内 Janus 注入的 Cap4mic 服务端
     * （LocalServerSocket，abstract "ld5micbridge"）转发 16k mono s16le 裸流。
     *
     * 桥模式下**不再创建 AudioRecord**（card1 由 HAL 独占，两边一起开必互相抢）。
     * 增益沿用 uplinkGain（D 通路 raw 语音 19-33 LSB，gain16 后 300-530 ≈ A 通路
     * 现状；远场可用 files/gain32|gain64 再抬）。
     * 服务端断线自动重连（1s 退避）；服务端不在时无音频（micTelem 静默 = 可见）。
     */
    public static volatile boolean useBridge = false;

    /** v1.3.29：与 player Cap4mic 服务端约定的 TCP 环回端口（v6.1 服务端同端口） */
    private static final int BRIDGE_PORT = 5013;

    /** 原厂读取块大小：s=1200 样点 ×2 字节 = 2400B = 75ms @16k mono PCM16 */
    private static final int UNI_CHUNK = 2400;

    /** 原厂 openAudioIn 的入参（反汇编 `x.l = 2`） */
    private static final int UNI_OPEN_MODE = 2;
    /** 原厂 initHal 的入参（反汇编 `x.k = 1`） */
    private static final int UNI_BOARD_TYPE = 1;

    /**
     * v1.3.36 ★★：uni 读线程「空/短读」退避毫秒（**默认 4**，`files/uninap<N>`，0..50，0=关）。
     *
     * 根因（2026-10-05 19:44 /proc/PID/task/tid/stat 差分，30s 窗口）：
     *
     *     L5MicReader   98.5%   ← 整整一个核，而它只负责「每 37.5ms 取 2400B」
     *     进程合计      294.7%（同时 idle 20s 只涨 4 jiffies ⇒ 4 核全满，系统被拖死）
     *
     * `readData(handle, buf, 2400)` **没凑满 2400B 就立刻返回**（短读/返 0），旧代码
     * `if (n <= 0) continue;` 于是变成纯自旋 —— 而音频依旧是实时的（micTelem 的 t=
     * 与墙钟 1:1），所以这个 bug 一直没被察觉，只表现为「小智一开就把 CPU 吃光」。
     *
     * 退避 4ms 后轮询频率仍有 ~250 次/秒，而需要 26.7 次/秒 × 2400B，余量 10 倍。
     * 4ms 的最坏极性抖动远小于一帧 60ms，不影响上行/唤醒。
     */
    public static volatile int uniIdleBackoffMs = 4;

    /**
     * v1.3.37 ★★：uni 读线程**主动配速**（B/s，**默认 64000**；0 = 关）。
     *
     * `uniIO` 实测（v1.3.36，`nap=4ms`）：`reads=128 idle=0 bytes=307200 -> 61440B/s`
     * ⇒ `readData` **从不返回 0/短读**，它「凑满 2400B 才返回」，等待期间在**用户态轮询**
     * —— 这才是 `L5MicReader` 98.6%（整整一核）的真身（退避分支一次都没进过）。
     *
     * 修法不是再退避，而是**读前先按生产者速率睡够**：2ch/16k/s16 = 64000 B/s，
     * 每 2400B 大约每 37.5ms 到齐。按墙钟算「此刻应该已到多少字节」，没到就 sleep
     * 到它到 —— 自旋变真睡。若我们落后于墙钟（due < 已消费），waitMs 为负 → 不睡、
     * 立即读去追赶，所以**不会越配越慢**。
     *
     * 上限 40ms：万一 HAL 真实速率高于配置（HAL 若在丢块，我们看到的就是偏低值），
     * 也不会把自己卡死（最多每块多睡 40ms，仍驱动得动）。
     * `files/unipace<N>` 改速率，`files/unipace0` 关。
     */
    public static volatile int uniPaceRate = 64000;

    /** 配速状态（只在读线程上读写） */
    private long uniPaceStartMs;
    private long uniPaceBytes;

    /**
     * v1.3.41：配速的**相位余量**（字节）。读到数据前多等这么多，
     * 保证进 readData 时缓冲里确实已攒够 —— 生产端有抖动也不会再触发内部自旋。
     * 2400B(一 chunk) 的一半 ≈ 18.75ms，实测足够吸收抖动，且不改变平均消费速率
     * （余量是恒定相位偏移，不会累积成吞吐差）。
     */
    private static final int PACE_MARGIN_BYTES = 1200;

    /** v1.3.36：uni 读 IO 统计（每 5s 落一行），用于验证 readData 到底是阻塞还是轮询 */
    private long uniReads;
    private long uniIdleReads;
    private long uniBytes;
    private long lastUniStatMs;

    private com.unisound.jni.Uni4micHalJNI uniHal;
    private long uniHandle = -1L;

    /**
     * v1.3.33 ★：四麦句柄的真身是**不透明指针**，不是状态码。
     *
     * 原厂 `com/unisound/common/e.smali:396-410` 实证 —— 拿到句柄后**没有任何判断**，
     * 直接 `startRecorder(handle)`，只用它的返回值判成败：
     *     invoke-virtual {v0, v4}, ...->openAudioIn(I)J
     *     move-result-wide v0
     *     iput-wide v0, p0, ...->p:J              # 直接存，不检查
     *     invoke-virtual {v0, v2, v3}, ...->startRecorder(J)I
     *     const-string v3, "openIn uni4micHalJNI status = "   # 只看 startRecorder 的 status
     *
     * 实机上 `openAudioIn(2)` 返回 0xFFFFFFFFB7D3E438 —— 低 32 位是合法堆指针
     * （0xB7D3E438 是典型 ARM32 mmap 区），高 32 位 0xFFFFFFFF 是符号扩展。
     * 也就是**有符号看恒为负**。v1.3.23~v1.3.32 写的 `if (uniHandle < 0)` 因此
     * 把每一次成功的打开都误判为失败 → 四麦从来没真正启用过，白回落 AudioRecord。
     *
     * 正确判据：唯一"没打开"的标志 = 我们自己的哨兵 -1。
     * （句柄 0/负都被视作已打开；真的失败会在 startRecorder 返回值上暴露。）
     */
    private boolean uniOpen() {
        return uniHandle != -1L;
    }

    // v1.3.28：MicBridge（player Cap4mic -> LocalSocket 裸流）
    private java.net.Socket bridgeSock; // v1.3.29：LocalSocket -> TCP（SELinux 拒跨域 unix socket）
    private java.io.InputStream bridgeIn;
    private int bridgeFails;   // 重连失败计数（日志节流）
    private final byte[] bridgeSkipBuf = new byte[8192]; // v1.3.32：丢积压的临时缓冲
    private long lastBridgeDropLogMs; // v1.3.32：丢帧日志节流

    private AudioRecord record;
    private AcousticEchoCanceler aec;   // v1.3.10：仅当 aecEnabled 时才创建
    private AudioTrack keepAlive;       // v1.3.11：session 绑定用的常驻静音播放流
    private Thread keepAliveThread;
    private Thread reader;
    private volatile boolean running;

    /**
     * v1.2.4：生命周期代号。stop() 会递增。
     * 用途：start() 里的 startRecording() 可能卡死（HAL 忙/死锁）；
     * 若卡死期间发生 stop()（会话被看门狗收掉），迟到的 start 绝不能
     * 复活成无人管辖的僵尸录音。start 在卡死苏醒后检查代号，不一致就自我了断。
     */
    private volatile int generation = 0;

    private FrameListener listener;

    /** 环形预缓冲：保存最近 PREROLL_FRAMES 帧（mono，用于上行补发） */
    private final byte[][] preroll = new byte[PREROLL_FRAMES][];
    private int prerollIdx;
    private int prerollCount;

    private final Object lock = new Object();
    private int minBufSize;

    // ---- v1.3.8 噪声遥测：逐秒 RMS/peak 滚动窗口 ----
    // 悬空噪签名 = rms 平坦 250~900 且 30s 窗口 peak<5000（语音必有 >5k 峰，
    // 静音 rms<250）。目的：判定流什么时候噪/什么时候有干净窗口（09:36/12:50
    // KWS 曾在「噪期」认出唤醒词 -> 流存在间歇性干净窗口，需数据定位规律）。
    private static final int TELEM_WIN = 30;       // 30 个 1s 块
    private static final long TELEM_LOG_MS = 5000; // 每 5s 落一行摘要
    private long telemSumsq;
    private int telemSecPeak;
    private int telemSamples;
    private int telemFrames;
    private final double[] telemRms = new double[TELEM_WIN];
    private final int[] telemPeak = new int[TELEM_WIN];
    private int telemIdx;
    private int telemCount;
    private long telemStartMs;
    private long lastTelemLogMs;
    private long lastSigLogMs;

    /** v1.3.30：采集线程看门狗锚点 —— loop 每产出一帧刷新一次。
     *  服务层心跳发现 mic.isRunning() 但锚点停摆 > 20s，即判定采集线程卡死
     *  （15:12:48 实锤：KWS 唤醒回调在采集线程内播 ack 阻塞 → 全链死、设备全聋，
     *  进程没死 lmkd 不救、sticky 不重启，只能处决自愈）。static：服务层免持实例可读。
     *  v1.3.31：必须在类加载时初始化为当前时刻 —— 否则 start() 置 isRunning=true
     *  到 loop 首帧之间，看门狗会拿 0 当锚点算出开机时长的 stall 直接误杀
     *  （15:44:21 实锤 stalled 4472s）。 */
    public static volatile long lastLoopFrameMs = SystemClock.elapsedRealtime();

    /** v1.3.31：桥积压丢弃阈值。正常实时流 producer(32KB/s) 与消费同步，
     *  超前量恒 ≈0；producer 停供（player CPU 饥饿/HAL ring 积压）后重连，
     *  客户端会以远超实时的速率排陈旧音频 —— 若照单全收，音频延迟数秒，
     *  TTS 播放声错过 TTS_PLAYING 抑制窗口被当作用户语音上行 → 自我对话回环
     *  （15:46:31 重连 → 15:47 回音谷 实锤）。超前 > 1.5s 的块整块丢弃。 */
    private static final int BRIDGE_AHEAD_DROP_MS = 1500;

    /** v1.3.32：桥端到端延迟上限（ms）。桥是 TCP 累积型 —— 消费端跟不上（CPU
     *  打满时 ldyb5client 处理一帧 >75ms）时数据堆在内核缓冲里，延迟**无上限增长**
     *  （实测「回的是十几秒前、甚至一分钟前小智自己说的话」）。读前用
     *  available() 探知积压量，超过本阈值就把旧数据丢掉、只留最新这一段 ——
     *  补上 AudioRecord 环形缓冲天然具备、而 TCP 字节流缺失的「丢弃语义」
     *  （A 路慢就丢帧、始终实时）。
     *  注：v1.3.31 的「超前墙钟」gap 检测对「匀速慢消费」天然失明 ——
     *  audioPos 与墙钟同速、gap≈0，只有 available() 能看到 socket 里的真积压。 */
    private static final int BRIDGE_LAG_MAX_MS = 200;

    /** v1.3.32：本端内核接收缓冲上限（≈2s @16k mono）。持续占满 -> TCP rwnd
     *  收缩 -> 反压经 loopback 传到 player 侧 Cap4mic 的 write -> HAL read 饿死
     *  -> 由 HAL ring 溢出丢弃。即「上游丢弃」替代「下游无限积压」。
     *  需与 Cap4mic 侧的小 SO_SNDBUF 配套（否则数据改堆在 sender 的 send buffer）。 */
    private static final int BRIDGE_RCVBUF = 64 * 1024;

    private void telemetryReset() {
        telemSumsq = 0;
        telemSecPeak = 0;
        telemSamples = 0;
        telemFrames = 0;
        telemIdx = 0;
        telemCount = 0;
        telemStartMs = SystemClock.elapsedRealtime();
        lastTelemLogMs = 0;
        lastSigLogMs = 0;
    }

    /** 每产出 1 帧调一次（60ms / 960 样本，开销可忽略） */
    private void telemetry(byte[] mono) {
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            int v = (short) ((mono[2 * i] & 0xff) | (mono[2 * i + 1] << 8));
            int a = v < 0 ? -v : v;
            telemSumsq += (long) v * v;
            if (a > telemSecPeak) telemSecPeak = a;
        }
        telemSamples += FRAME_SAMPLES;
        telemFrames++;
        if (telemFrames < 16) {
            return; // 未满 1s（16*60ms=960ms）
        }
        double rms = Math.sqrt((double) telemSumsq / telemSamples);
        telemRms[telemIdx] = rms;
        telemPeak[telemIdx] = telemSecPeak;
        telemIdx = (telemIdx + 1) % TELEM_WIN;
        if (telemCount < TELEM_WIN) {
            telemCount++;
        }
        int curPeak = telemSecPeak;   // v0.3.19：本秒 peak（重置前留存，供 ECHO 诊断）
        telemFrames = 0;
        telemSumsq = 0;
        telemSamples = 0;
        telemSecPeak = 0;

        // v0.3.19：TTS 播放期回声诊断 —— 打印这一秒上行 RMS/peak，确证硬件回声
        // 抑制（R1 HAL AEC）有没有把小智自己播放的 TTS 消掉。用户没说话时若
        // ECHO-DIAG rms 仍明显高（>>静默底噪 ~13），即回声没消干净，会被 VAD
        // 当人声触发自打断。
        if (ttsActive) {
            SessionLog.i("ECHO-DIAG tts-playing rms=" + (long) rms + " peak=" + curPeak);
            Log.i(TAG, "ECHO-DIAG tts-playing rms=" + (long) rms + " peak=" + curPeak);
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastTelemLogMs >= TELEM_LOG_MS) {
            lastTelemLogMs = now;
            StringBuilder sb = new StringBuilder("micTelem t=");
            sb.append((now - telemStartMs) / 1000).append("s rms[");
            for (int i = 0; i < telemCount; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append((long) telemRms[(telemIdx + TELEM_WIN - 1 - i) % TELEM_WIN]);
            }
            int wp = 0;
            for (int i = 0; i < telemCount; i++) {
                if (telemPeak[i] > wp) {
                    wp = telemPeak[i];
                }
            }
            sb.append("] peak=").append(wp);
            SessionLog.i(sb.toString());
            Log.i(TAG, sb.toString());
        }
        // 悬空噪签名：30s 全窗平坦
        if (telemCount >= TELEM_WIN && now - lastSigLogMs >= 30000) {
            boolean flat = true;
            for (int i = 0; i < TELEM_WIN; i++) {
                if (telemRms[i] < 250 || telemRms[i] > 900 || telemPeak[i] >= 5000) {
                    flat = false;
                    break;
                }
            }
            if (flat) {
                lastSigLogMs = now;
                SessionLog.i("NOISE-SIGNATURE t=" + ((now - telemStartMs) / 1000)
                        + "s (30s flat rms 250-900, peak<5000) -> stream is dangling-noise");
                Log.w(TAG, "NOISE-SIGNATURE confirmed (30s flat) -- dangling noise stream");
            }
        }
    }

    public AudioCapture() {
        for (int i = 0; i < PREROLL_FRAMES; i++) {
            preroll[i] = new byte[FRAME_BYTES];
        }
    }

    public void setFrameListener(FrameListener l) {
        this.listener = l;
    }

    public synchronized boolean start() {
        if (running) {
            return true;
        }
        final int myGen = generation;
        // v1.2.3 关键修复：不再调用 AudioRecord.getMinBufferSize()。
        // 同步 binder 调用，mediaserver HAL 死锁时永不返回（v1.2.2 ANR 链路）。
        minBufSize = 4096;
        // 给足余量：取 8 帧
        int bufBytes = Math.max(minBufSize * 4, STEREO_FRAME_BYTES * 8);
        // v1.3.10：source 可切换（files/src_vrec）；默认 VOICE_COMMUNICATION。
        final int src = useVoiceRec
                ? MediaRecorder.AudioSource.VOICE_RECOGNITION
                : MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        // v1.3.23：先试原厂四麦 HAL。成功 -> uniHandle != -1，完全跳过 AudioRecord
        // （HAL 是直开设备的，两个一起开必然互相抢）；失败 -> uniHandle 保持 -1，
        // 原样走下面的 AudioRecord 老路，行为与 v1.3.22 完全一致。
        // v1.3.28：先试 MicBridge（纯 socket connect，卡不住 binder）。
        // 连上 = 桥独占输入（player 进程持 HAL），完全跳过 AudioRecord / uni HAL；
        // 没连上 = 按原优先级继续（uni4mic -> AudioRecord），行为与 v1.3.27 一致。
        if (useBridge) {
            connectBridge();
            if (bridgeIn != null && myGen != generation) {
                Log.w(TAG, "start() vetoed (bridge): stop() raced during connect"
                        + " -> self-release (no zombie mic)");
                closeBridge();
                return false;
            }
        }
        if (bridgeIn == null && useUni4mic) {
            startUni4mic();
        }
        if (!uniOpen() && bridgeIn == null) {
        try {
            // v1.3.9：VOICE_RECOGNITION -> VOICE_COMMUNICATION（唯一从未干净测过的变量）。
            // 依据 = 逆向「一键安装Unisound_v1.6.13」里那个能在同一台 R1 上正常
            // 收音的社区版小智 App（com.huihongcloud.xiaozhi v1.8，反汇编实证）：
            //   AudioRecord(VOICE_COMMUNICATION, 16000, mono, PCM16, 960)
            //   + AcousticEchoCanceler.create(session).setEnabled(true)
            // 且该 App 无任何 R1 特化代码、禁用 Pandora 也正常 -> 证明 R1 的
            // AK7755 HAL 只在「通话/语音」用例下把 ADC 接到真实麦阵，其它 source
            // 停在悬空输入（正是我们量到的 0x00/0xff 底噪、rms≈500 平线画像）。
            record = new AudioRecord(src, SAMPLE_RATE,
                    CHANNEL_CFG, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord ctor threw", t);
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed state=" + record.getState()
                    + " (device busy? unisound may be holding the 4-mic HAL)");
            record.release();
            record = null;
            return false;
        }
        // v1.2.3：running 置位挪到 startRecording() 之后（防卡死期间误判已采集）。
        record.startRecording();
        // v1.2.4：卡死苏醒后查代号，被 stop() 抢先则自我了断（no zombie mic）。
        if (myGen != generation) {
            Log.w(TAG, "start() vetoed: stop() happened while startRecording was"
                    + " stuck -> self-release (no zombie mic)");
            try {
                record.stop();
            } catch (Throwable ignored) {
            }
            try {
                record.release();
            } catch (Throwable ignored) {
            }
            record = null;
            return false;
        }
        // v1.3.10：AEC 改为开关控制，默认关。
        // v1.3.9 实测：开着 AEC 时底噪没了、人声也没了（见 aecEnabled 字段注释）。
        if (!aecEnabled) {
            Log.i(TAG, "AEC disabled (v1.3.10 default; touch files/aec_on to enable)"
                    + " src=" + (useVoiceRec ? "VOICE_RECOGNITION" : "VOICE_COMMUNICATION"));
        } else {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                    if (aec != null) {
                        aec.setEnabled(true);
                        Log.i(TAG, "AEC enabled (session=" + record.getAudioSessionId()
                                + " state=" + aec.getEnabled() + ")");
                    } else {
                        Log.w(TAG, "AEC create returned null");
                    }
                } else {
                    Log.w(TAG, "AcousticEchoCanceler not available on this device");
                }
            } catch (Throwable t) {
                Log.w(TAG, "AEC setup threw", t);
                aec = null;
            }
        }
        } else if (bridgeIn == null) {
            // v1.3.23：四麦 HAL 已接管（无需 AudioRecord / AEC / session 绑定）
            if (myGen != generation) {
                Log.w(TAG, "start() vetoed (uni4mic): stop() raced during open"
                        + " -> self-release (no zombie mic)");
                stopUni4mic();
                return false;
            }
        }
        running = true;
        telemetryReset(); // v1.3.8：遥测随采集起点归零
        reader = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "L5MicReader");
        reader.setPriority(Thread.MAX_PRIORITY);
        reader.start();
        // v1.3.11：会话绑定 —— 同 session 挂常驻静音播放流（逼 HAL 进全双工语音用例）
        if (sessionBind) {
            startSessionKeepAlive();
        }
        Log.i(TAG, "AudioCapture started: " + SAMPLE_RATE + "Hz/out-mono rawCh="
                + (uniOpen() ? RAW_CHANNELS : CHANNELS)
                + (uniOpen() ? (halTakeCh0 ? " take=ch0" : " take=avg") : "")
                + (bridgeIn != null
                        ? " **SRC=MICBRIDGE** (tcp://127.0.0.1:" + BRIDGE_PORT + ")"
                        : (uniOpen()
                                ? " **SRC=UNI4MIC-HAL** (board=" + uniBoardVer + " handle=" + uniHandle + ")"
                                : " src=" + (useVoiceRec ? "VOICE_RECOGNITION" : "VOICE_COMMUNICATION")))
                + " aec=" + aecEnabled + " gain=" + uplinkGain + " sessBind=" + sessionBind
                + ", 60ms mono, minBuf=" + minBufSize + "(fixed)");
        SessionLog.i("AudioCapture started: src="
                + (bridgeIn != null ? "micbridge"
                        : (uniOpen() ? ("uni4mic-hal(board " + uniBoardVer + ")")
                                          : (useVoiceRec ? "VOICE_RECOGNITION" : "VOICE_COMMUNICATION")))
                + " aec=" + aecEnabled + " gain=" + uplinkGain + " sessBind=" + sessionBind);
        return true;
    }

    /** 原厂 HAL 报的板型版本（日志用） */
    private volatile String uniBoardVer = "?";

    /**
     * v1.3.23：打开原厂四麦阵列 HAL。全部步骤按 `Lcom/unisound/sdk/x;` 反汇编顺序，
     * 任一环节失败即回收并返回（uniHandle 保持 -1 -> 调用方回落 AudioRecord）。
     * 每一步都落日志：so 加载不了 / init 非 0 / openAudioIn 负值，用户一看就知道
     * 是「权限/占用」还是「接口猜错」。
     */
    private void startUni4mic() {
        try {
            uniHal = com.unisound.jni.Uni4micHalJNI.getInstance();
            // 先探活：这两个调用的返回值最能说明 HAL 是否真的可用
            int r = uniHal.init(UNI_BOARD_TYPE);
            Log.i(TAG, "uni4mic init(" + UNI_BOARD_TYPE + ") -> " + r);
            if (r != 0) {
                Log.w(TAG, "uni4mic init failed (" + r + ") -> fallback AudioRecord");
                uniHal = null;
                return;
            }
            try {
                uniBoardVer = uniHal.get4MicBoardVersion();
            } catch (Throwable ignored) {
            }
            // 原厂 x.<init> 的三个默认值：debug=1 / close4MicAlgorithm=0(保留算法)
            // / wakeUpStatus=0
            Log.i(TAG, "uni4mic board=" + uniBoardVer
                    + " dbg=" + uniHal.set4MicDebugMode(1)
                    + " closeAlgo=" + uniHal.close4MicAlgorithm(0)
                    + " wakeUp=" + uniHal.set4MicWakeUpStatus(0));
            uniHandle = uniHal.openAudioIn(UNI_OPEN_MODE);
            Log.i(TAG, "uni4mic openAudioIn(" + UNI_OPEN_MODE + ") -> handle=" + uniHandle
                    + " (0x" + Long.toHexString(uniHandle) + ")");
            // ★ v1.3.33 修正：句柄是不透明指针（高半字 0xFFFFFFFF 符号扩展），
            //   绝不能用 `if (uniHandle < 0)` 判失败 —— 见 uniOpen() 上方注释。
            //   只有 NULL 才算没打开；真失败会在 startRecorder 的返回值上暴露。
            if (uniHandle == 0L) {
                Log.w(TAG, "uni4mic openAudioIn returned NULL -> fallback AudioRecord");
                uniHal.release();
                uniHal = null;
                uniHandle = -1L;
                return;
            }
            int s = uniHal.startRecorder(uniHandle);
            Log.i(TAG, "uni4mic startRecorder -> " + s);
            if (s != 0) {
                Log.w(TAG, "uni4mic startRecorder failed (" + s + ") -> fallback");
                try {
                    uniHal.closeAudioIn(uniHandle);
                } catch (Throwable ignored) {
                }
                uniHal.release();
                uniHal = null;
                uniHandle = -1L;
            }
        } catch (Throwable t) {
            Log.e(TAG, "uni4mic start threw -> fallback AudioRecord", t);
            uniHandle = -1L;
            uniHal = null;
        }
    }

    /** v1.3.23：收尾原厂四麦 HAL（幂等） */
    private void stopUni4mic() {
        com.unisound.jni.Uni4micHalJNI h = uniHal;
        long hd = uniHandle;
        uniHandle = -1L;
        uniHal = null;
        if (h == null) {
            return;
        }
        try {
            if (hd != -1L) {
                Log.i(TAG, "uni4mic stopRecorder -> " + h.stopRecorder(hd)
                        + ", closeAudioIn -> " + h.closeAudioIn(hd));
            }
            Log.i(TAG, "uni4mic release -> " + h.release());
        } catch (Throwable t) {
            Log.w(TAG, "uni4mic stop threw", t);
        }
    }

    // ---------------- v1.3.28：MicBridge ----------------

    /**
     * 连接 player 内 Cap4mic 服务端（abstract socket，纯 connect，快速失败）。
     * 成功后服务端才会去开四麦 HAL；失败不抛（回落 uni4mic/AudioRecord 或
     * 由读线程持续重连）。
     */
    private void connectBridge() {
        try {
            // v1.3.29：LocalSocket(abstract) 被 SELinux Enforcing 拒（untrusted_app
            // 连 system_app 的 unix socket = EACCES），改走 TCP 环回 —— 跨域不受限。
            java.net.Socket s = new java.net.Socket();
            // v1.3.32：connect 前设接收缓冲（影响 SYN 窗口缩放的协商值），
            // connect 后再设一次（确保内核采纳）。限容 = 抑制积压上限。
            try { s.setReceiveBufferSize(BRIDGE_RCVBUF); } catch (Throwable ignore) { }
            s.connect(new java.net.InetSocketAddress("127.0.0.1", BRIDGE_PORT), 2000);
            try { s.setReceiveBufferSize(BRIDGE_RCVBUF); } catch (Throwable ignore) { }
            s.setSoTimeout(5000); // 服务端正常 75ms 一拍；5s 无数据 = 服务端僵死 -> 重连
            bridgeSock = s;
            bridgeIn = s.getInputStream();
            bridgeFails = 0;
            Log.i(TAG, "MicBridge connected @tcp://127.0.0.1:" + BRIDGE_PORT);
            SessionLog.i("MicBridge connected @tcp://127.0.0.1:" + BRIDGE_PORT);
        } catch (Throwable t) {
            if (bridgeFails == 0) {
                Log.w(TAG, "MicBridge connect failed (player srv flag off?): " + t);
                SessionLog.i("MicBridge connect failed: " + t);
            }
            closeBridge();
        }
    }

    /**
     * 桥读一拍。断线：关旧连接 -> 1s 退避 -> 重连一次，返回 0（循环继续，
     * stop 后 running=false 立即退出）。日志节流：首失败 + 每 30 次一条。
     */
    private int bridgeRead(byte[] buf) {
        java.io.InputStream in = bridgeIn;
        if (in == null) {
            if (!running) {
                return 0;
            }
            sleepQuiet(1000);
            if (running) {
                connectBridge();
            }
            return 0;
        }
        try {
            // v1.3.32：读侧丢帧追平 —— 把端到端延迟锁死在 BRIDGE_LAG_MAX_MS 以内。
            // available() = 内核接收缓冲里待读字节数（唯一的真实积压指标）。超过
            // 阈值的实时量说明在吃陈旧积压，整块丢弃旧的、只留最新一段。
            int lagMaxBytes = SAMPLE_RATE * 2 * BRIDGE_LAG_MAX_MS / 1000; // 16k*2B/ms
            int avail = in.available();
            if (avail > lagMaxBytes) {
                long toDrop = avail - lagMaxBytes;
                long dropped = 0L;
                while (dropped < toDrop) {
                    int want = (int) Math.min(bridgeSkipBuf.length, toDrop - dropped);
                    int k = in.read(bridgeSkipBuf, 0, want);
                    if (k <= 0) {
                        break;
                    }
                    dropped += k;
                }
                long nowLog = SystemClock.uptimeMillis();
                if (nowLog - lastBridgeDropLogMs > 3000L) {
                    lastBridgeDropLogMs = nowLog;
                    Log.w(TAG, "bridge lag " + (avail / 32) + "ms -> drop " + dropped
                            + "B (keep " + BRIDGE_LAG_MAX_MS + "ms)");
                    SessionLog.i("bridge lag " + (avail / 32) + "ms -> drop " + dropped + "B");
                }
            }
            int n = in.read(buf, 0, buf.length);
            if (n <= 0) {
                throw new java.io.IOException("bridge EOF (server closed)");
            }
            return n;
        } catch (Throwable t) {
            bridgeFails++;
            if (bridgeFails == 1 || bridgeFails % 30 == 0) {
                Log.w(TAG, "MicBridge read failed (fails=" + bridgeFails + ") -> reconnect: " + t);
                SessionLog.i("MicBridge read failed (fails=" + bridgeFails + ") -> reconnect");
            }
            closeBridge();
            return 0;
        }
    }

    /** 关桥（幂等）。close 会解除读线程阻塞的 read。 */
    private void closeBridge() {
        java.net.Socket s = bridgeSock;
        bridgeSock = null;
        bridgeIn = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {
        }
    }

    /**
     * v1.3.11：在录音 session 上挂一条常驻静音播放流（AudioTrack），永不 idle。
     * 目的只有一个：让 HAL 看到「本 session 全双工」-> 打开通话/语音用例的完整
     * 语音处理链（含上行 AGC）。这是参考 App 与我们唯一没抄过的差异点。
     * 失败不致命（仅记日志，采集照常）。
     */
    private void startSessionKeepAlive() {
        final int sid = record.getAudioSessionId();
        try {
            int buf = Math.max(SAMPLE_RATE * 2 / 20, 2048); // ~50ms
            keepAlive = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    buf, AudioTrack.MODE_STREAM, sid);
            if (keepAlive.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "session keep-alive AudioTrack init failed state="
                        + keepAlive.getState() + " session=" + sid);
                try {
                    keepAlive.release();
                } catch (Throwable ignored) {
                }
                keepAlive = null;
                return;
            }
            keepAlive.play();
            final AudioTrack t = keepAlive;
            keepAliveThread = new Thread(new Runnable() {
                public void run() {
                    byte[] z = new byte[SAMPLE_RATE * 2 / 50]; // 20ms 静音
                    while (running && keepAlive == t) {
                        try {
                            t.write(z, 0, z.length);
                        } catch (Throwable e) {
                            break;
                        }
                    }
                }
            }, "L5SessionKeepAlive");
            keepAliveThread.setDaemon(true);
            keepAliveThread.start();
            Log.i(TAG, "session-bind ON (session=" + sid
                    + ") keep-alive playback started -> force full-duplex voice use-case");
            SessionLog.i("session-bind ON (session=" + sid + ") keep-alive playback started");
        } catch (Throwable t) {
            Log.w(TAG, "session keep-alive setup threw", t);
            keepAlive = null;
        }
    }

    /** v1.3.11：停掉 session 绑定的常驻播放流（先于 AudioRecord 释放） */
    private void stopSessionKeepAlive() {
        AudioTrack t = keepAlive;
        keepAlive = null;           // 先置空，令 keep-alive 线程的 while 条件失效
        if (keepAliveThread != null) {
            try {
                keepAliveThread.join(300);
            } catch (InterruptedException ignored) {
            }
            keepAliveThread = null;
        }
        if (t != null) {
            try {
                t.stop();
            } catch (Throwable ignored) {
            }
            try {
                t.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 采集是否真正在跑（startRecording 已返回且读线程已起） */
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        // v1.3.23：读源二选一 —— 原厂四麦 HAL 或 AudioRecord。
        // v1.3.28：三选一 —— 优先 MicBridge（player Cap4mic 裸流，16k mono，
        // 无帧头，块长任意，进同一个累积器）。HAL 的块是 2400B(75ms)，
        // AudioRecord 我们按整帧 1920B 要；三者都进同一个帧对齐累积器。
        final boolean bridgeSrc = (bridgeIn != null);
        final long uniH = uniHandle;
        final com.unisound.jni.Uni4micHalJNI hal = uniHal;
        // v1.3.34 ★：句柄是**不透明指针**，有符号看恒为负（0xFFFFFFFFB7B99BE0）。
        // v1.3.23~v1.3.33 这里写的 `uniH >= 0` 因此恒为 false → 明明四麦已开
        // （SRC=UNI4MIC-HAL）却走 record.read()，而 uni 模式下 record==null
        // → NPE @ loop() → 读线程死 → 看门狗 21s 后自杀重启（实机 3 连死循环）。
        // 判据与 uniOpen() 保持一致：唯一"没打开"的标志 = 哨兵 -1。
        final boolean uni = (uniH != -1L && hal != null);
        lastLoopFrameMs = SystemClock.elapsedRealtime(); // v1.3.30：看门狗起点
        // v1.3.35 ★：累积目标必须随源而定 —— 四麦 HAL 是 2ch（3840B/帧），
        // 桥与 AudioRecord 是 mono（1920B/帧）。用错帧长会让一帧变成 120ms/30ms。
        final int rawFrameBytes = uni ? RAW_FRAME_BYTES : STEREO_FRAME_BYTES;
        byte[] acc = new byte[rawFrameBytes];
        int filled = 0;
        // v1.3.31：桥积压追赶检测状态（仅 bridgeSrc 用）
        long audioPosMs = 0L;   // 累计「收到的音频时长」
        long startWallMs = 0L;  // 首次读到的墙钟（audioPos 与墙钟的差 = 积压延迟）
        long lastBacklogLogMs = 0L;
        byte[] tmp = new byte[bridgeSrc ? 2400 : (uni ? UNI_CHUNK : STEREO_FRAME_BYTES)];
        byte[] monoOut = new byte[FRAME_BYTES];
        byte[] stereoOut = new byte[rawFrameBytes]; // v1.3.14：预分配，免每帧 new
        // v1.3.37：本次 loop 的耗时拆解（readData 内 vs 处理），每 5s 落一行
        long uniReadNs = 0L;
        long uniWorkNs = 0L;
        long uniDownNs = 0L;
        long uniTelNs = 0L;
        long uniFeedNs = 0L;
        long uniWallNs = System.nanoTime();
        uniPaceStartMs = 0L;
        uniPaceBytes = 0L;

        // 读取时做帧对齐：读源返回长度不保证是整帧的整数倍，
        // 必须自行累积到整 stereo 帧再下混分发。
        while (running) {
            int n;
            if (bridgeSrc) {
                // v1.3.28：桥断线在 bridgeRead 内部处理（关旧连接+1s 退避重连），
                // 返回 0 = 本轮无数据，继续 while(running)（stop 后立即退出）。
                n = bridgeRead(tmp);
                if (n <= 0) {
                    continue;
                }
                // v1.3.30：看门狗锚点 —— 线程只要还在读就刷新。放在 onFrame 之前，
                // 卡死若发生在 onFrame 内部（ack 播放阻塞），后续读不到 → 锚点停摆
                // → 被服务层看门狗捕获；同时覆盖积压丢弃期（防误杀）。
                lastLoopFrameMs = SystemClock.elapsedRealtime();
                // v1.3.31：积压追赶检测。audioPos = 累计收到音频时长；若超前墙钟
                // >1.5s = 正在排陈旧积压（producer 停供/重连后追赶）—— 整块丢弃：
                // 陈旧音频喂 KWS 会假唤醒、上行会自我对话回环（15:46:31 重连 →
                // 15:47 回音谷 实锤）。实时流下 audioPos 与墙钟同速，gap ≈0 不触发；
                // 丢弃期 gap 以 1:1 墙钟速率收敛。
                if (startWallMs == 0L) {
                    startWallMs = lastLoopFrameMs;
                }
                audioPosMs += n / 32;
                long gapMs = audioPosMs - (lastLoopFrameMs - startWallMs);
                if (gapMs > BRIDGE_AHEAD_DROP_MS) {
                    audioPosMs -= n / 32; // 该块丢弃，音频位置不前进（等墙钟追上）
                    long nowLog = SystemClock.uptimeMillis();
                    if (nowLog - lastBacklogLogMs > 5000L) {
                        lastBacklogLogMs = nowLog;
                        Log.w(TAG, "bridge backlog " + gapMs + "ms -> drop " + n + "B");
                        SessionLog.i("bridge backlog " + gapMs + "ms -> drop " + n + "B");
                    }
                    continue;
                }
            } else {
                try {
                    if (uni) {
                        // v1.3.37/41：读前按生产者速率**主动配速**。
                        // 「生产者此刻已产出多少」= (now-start)*rate，减去已消费 = 可读余量；
                        // 余量不够一个 chunk 就 sleep 到够 —— 这样每次进 readData 时数据
                        // 一定已就绪，它内部的自旋等待就消失了（实测 readMs 4814ms→<20ms）。
                        // 再留 PACE_MARGIN_BYTES 的相位余量，抗生产端抖动。
                        if (uniPaceRate > 0) {
                            long pnow = SystemClock.elapsedRealtime();
                            if (uniPaceStartMs == 0L) {
                                uniPaceStartMs = pnow;
                                uniPaceBytes = 0L;
                            }
                            long produced = (pnow - uniPaceStartMs) * (long) uniPaceRate / 1000L;
                            long avail = produced - uniPaceBytes;
                            long want = tmp.length + PACE_MARGIN_BYTES;
                            if (avail < want) {
                                long waitMs = (want - avail) * 1000L / uniPaceRate;
                                if (waitMs > 40L) {
                                    waitMs = 40L; // 上限：防 HAL 真实速率高于配置时卡死
                                }
                                if (waitMs > 0L) {
                                    try {
                                        Thread.sleep(waitMs);
                                    } catch (InterruptedException ignored) {
                                    }
                                }
                            }
                        }
                        long t0 = System.nanoTime();
                        n = hal.readData(uniH, tmp, tmp.length);
                        uniReadNs += System.nanoTime() - t0;
                        if (n > 0) {
                            uniPaceBytes += n;
                        }
                    } else {
                        n = record.read(tmp, 0, tmp.length);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, uni ? "uni4mic readData threw" : "AudioRecord.read threw", t);
                    break;
                }
            }
            if (n <= 0) {
                // v1.3.36 ★：readData 未凑满请求长度时**立即**返回（短读/返 0）。
                // 旧代码无条件 continue ⇒ 纯自旋，实测把本线程烧到 98.5%（整核）。
                // 这里补自适应退避；uni 之外的源（AudioRecord/bridge）是阻塞读，不动。
                if (uni) {
                    uniIdleReads++;
                    int nap = uniIdleBackoffMs;
                    if (nap > 0) {
                        try {
                            Thread.sleep(nap);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                continue;
            }
            if (uni) {
                uniReads++;
                uniBytes += n;
            }
            int off = 0;
            while (off < n) {
                int need = rawFrameBytes - filled;
                int take = Math.min(need, n - off);
                System.arraycopy(tmp, off, acc, filled, take);
                filled += take;
                off += take;
                if (filled == rawFrameBytes) {
                    long tWork = System.nanoTime();
                    long tA = tWork;
                    downmix(acc, monoOut, uni ? RAW_CHANNELS : CHANNELS);
                    if (uplinkGain > 1) {
                        applyGain(monoOut, uplinkGain); // v1.3.11：上行软件增益
                    }
                    long tB = System.nanoTime();
                    telemetry(monoOut); // v1.3.8：逐帧噪声遥测（测的是**实际上行**电平，已含增益）
                    long tC = System.nanoTime();
                    if (uni) {
                        uniDownNs += tB - tA;
                        uniTelNs += tC - tB;
                    }
                    // v1.3.30：必须在 onFrame（唤醒回调链）之前刷新锚点 ——
                    // 若卡死发生在 onFrame 内部（如 ack 播放阻塞），下一帧永远
                    // 不来，锚点停摆即可被服务层看门狗捕获。
                    lastLoopFrameMs = SystemClock.elapsedRealtime();
                    System.arraycopy(acc, 0, stereoOut, 0, rawFrameBytes);
                    pushPreroll(monoOut);
                    FrameListener l = listener;
                    if (l != null) {
                        try {
                            l.onFrame(monoOut, FRAME_BYTES, stereoOut);
                        } catch (Throwable t) {
                            Log.e(TAG, "frame listener threw", t);
                        }
                    }
                    if (uni) {
                        uniFeedNs += System.nanoTime() - tC;
                    }
                    // v1.3.36/37：uni 读 IO + 耗时拆解（每 5s）。
                    //   readMs  = 花在 readData **内部**的时间（自旋/等待）
                    //   workMs  = 花在我们自己处理（下混/增益/遥测/KWS feed）的时间
                    //   两者都是「CPU 时间」而非「墙钟时间」的代理：readMs 占比高
                    //   = HAL 在读里自旋（配速理应把它压下去）；workMs 高 = 我们自己的活重。
                    if (uni) {
                        uniWorkNs += System.nanoTime() - tWork;
                        long nowStat = SystemClock.uptimeMillis();
                        if (nowStat - lastUniStatMs >= 5000L) {
                            long nowNs = System.nanoTime();
                            double wallMs = (nowNs - uniWallNs) / 1e6;
                            double rMs = uniReadNs / 1e6;
                            double wMs = uniWorkNs / 1e6;
                            Log.i(TAG, "uniIO(5s): reads=" + uniReads + " idle=" + uniIdleReads
                                    + " bytes=" + uniBytes + " pace=" + uniPaceRate + "B/s"
                                    + " | readMs=" + Math.round(rMs) + "/" + Math.round(wallMs)
                                    + " workMs=" + Math.round(wMs)
                                    + " (r" + Math.round(rMs / wallMs * 100) + "%"
                                    + "/w" + Math.round(wMs / wallMs * 100) + "%)"
                                    + " | down=" + Math.round(uniDownNs / 1e6)
                                    + " tel=" + Math.round(uniTelNs / 1e6)
                                    + " feed=" + Math.round(uniFeedNs / 1e6));
                            lastUniStatMs = nowStat;
                            uniWallNs = nowNs;
                            uniReads = 0;
                            uniIdleReads = 0;
                            uniBytes = 0;
                            uniReadNs = 0L;
                            uniWorkNs = 0L;
                            uniDownNs = 0L;
                            uniTelNs = 0L;
                            uniFeedNs = 0L;
                        }
                    }
                    filled = 0;
                }
            }
        }
        Log.i(TAG, "AudioCapture loop exit");
    }

    /**
     * 交错原始帧 -> mono（16bit 小端）。
     * v1.3.35：去掉对全局 CHANNELS 的隐式依赖，把声道数**显式传进来**——
     * 四麦 HAL 是 2ch 而协议是 mono，两者从此解耦（旧代码在 CHANNELS=1 时
     * 走直拷分支，2ch 的 3840B 帧会被截成 1920B，等于又丢一半）。
     *
     * @param ch 1 = 直拷；2 = 交错 L/R（默认取平均；halTakeCh0 时只取第 0 声道）
     */
    private static void downmix(byte[] st, byte[] mono, int ch) {
        if (ch == 1) {
            System.arraycopy(st, 0, mono, 0, FRAME_BYTES);
            return;
        }
        if (halTakeCh0) {
            // A/B 用：只取第 0 声道（判断 HAL 两声道是否「处理后 + 回声参考」）
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                mono[2 * i] = st[4 * i];
                mono[2 * i + 1] = st[4 * i + 1];
            }
            return;
        }
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            int l = (short) ((st[4 * i] & 0xff) | (st[4 * i + 1] << 8));
            int r = (short) ((st[4 * i + 2] & 0xff) | (st[4 * i + 3] << 8));
            int m = (l + r) >> 1;
            mono[2 * i] = (byte) (m & 0xff);
            mono[2 * i + 1] = (byte) ((m >> 8) & 0xff);
        }
    }

    /** v1.3.11：就地线性增益 + 饱和钳位（int16）；用于「信号在、只是电平低」时的上行放大 */
    private static void applyGain(byte[] pcm, int g) {
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            int v = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            v *= g;
            if (v > 32767) {
                v = 32767;
            } else if (v < -32768) {
                v = -32768;
            }
            pcm[2 * i] = (byte) (v & 0xff);
            pcm[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
    }

    private void pushPreroll(byte[] frame) {
        synchronized (lock) {
            System.arraycopy(frame, 0, preroll[prerollIdx], 0, FRAME_BYTES);
            prerollIdx = (prerollIdx + 1) % PREROLL_FRAMES;
            if (prerollCount < PREROLL_FRAMES) {
                prerollCount++;
            }
        }
    }

    /**
     * 取出唤醒前的预缓冲音频（时间正序，mono）。用于唤醒后立即补发，
     * 避免服务器漏掉唤醒词尾音与命令词开头。
     */
    public byte[] drainPreroll() {
        synchronized (lock) {
            int count = prerollCount;
            if (count == 0) {
                return new byte[0];
            }
            byte[] out = new byte[count * FRAME_BYTES];
            int start = (prerollIdx - count + PREROLL_FRAMES) % PREROLL_FRAMES;
            for (int i = 0; i < count; i++) {
                System.arraycopy(preroll[(start + i) % PREROLL_FRAMES], 0, out, i * FRAME_BYTES, FRAME_BYTES);
            }
            prerollCount = 0;
            return out;
        }
    }

    public void stop() {
        generation++;   // v1.2.4：否决任何在途/卡死的 start()
        running = false;
        // v1.3.28：先关桥（解除读线程阻塞的 read），再走原收尾
        closeBridge();
        // v1.3.11：先停 session 绑定播放流（它握着一份输出），再放 AEC/录音
        stopSessionKeepAlive();
        // v1.3.23：原厂四麦 HAL 也握着输入设备，必须先还回去（否则 USC/原厂引擎
        // 抢不到麦 —— 与 v1.3.6 修过的 AudioRecord 泄漏同一类事故）
        stopUni4mic();
        // v1.3.9：先释放 AEC，再停/放 AudioRecord
        if (aec != null) {
            try {
                aec.setEnabled(false);
                aec.release();
            } catch (Throwable t) {
                Log.w(TAG, "AEC release threw", t);
            }
            aec = null;
        }
        if (record != null) {
            // v1.3.6 关键修复：先 record.stop() 再 join。读线程可能阻塞在
            // record.read()；record.stop() 会令 read 立即返回错误解除阻塞。
            // 旧顺序（先 join 后 stop）在读阻塞时必然 join 超时，随后的
            // release 可能失败且异常被吞 -> HAL 输入永远不归还（LED 常亮、
            // USC 引擎抢不到麦 = 「我们开过一次会话 USC 就永久聋」的根因）。
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (Throwable t) {
                Log.e(TAG, "AudioRecord.stop threw (pre-release)", t);
            }
            if (reader != null) {
                try {
                    reader.join(1000);
                } catch (InterruptedException ignored) {
                }
                if (reader.isAlive()) {
                    Log.e(TAG, "mic reader still alive after stop+join(1000) -- leaking?");
                }
                reader = null;
            }
            try {
                record.release();
            } catch (Throwable t) {
                Log.e(TAG, "AudioRecord.release FAILED -- HAL input leaked!", t);
            }
            record = null;
        }
        Log.i(TAG, "AudioCapture stopped");
    }
}
