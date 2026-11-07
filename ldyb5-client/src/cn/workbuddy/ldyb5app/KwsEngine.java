package cn.workbuddy.ldyb5app;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

/**
 * sherpa-onnx 流式 KWS 封装（官方 Java API 版）。
 *
 * ============ ⚠️ 关键陷阱：路径必须是 assets 相对路径 ============
 *
 * 官方 Java API 只有一个公开构造：
 *     public KeywordSpotter(AssetManager, KeywordSpotterConfig)
 * 它内部调 native 的 newFromAsset(AssetManager, Config)。
 * （另一个 newFromFile(Config) 是 private 的，Java 层够不到。）
 *
 * newFromAsset 会把 config 里的路径当作 **AssetManager 内部的相对路径**
 * 去 open()。如果传绝对路径 /data/data/.../files/kws/xxx.onnx，
 * AssetManager.open() 必然失败，native 侧拿到无效 handle，
 * 后续解引用 null+0xc → SIGSEGV（崩溃栈全在 libsherpa-onnx-jni.so 内）。
 *
 * 所以：模型**不需要**释放到 filesDir，直接用 "kws/xxx.onnx" 即可。
 * （这也是 v1.1.x 崩溃的真正原因，与 ORT 版本无关。）
 *
 * 模型（assets/kws，ModelScope pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01）：
 *   encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx  (4.8MB)
 *   decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx  (181KB)
 *   joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx   (65KB)
 *   tokens.txt / keywords.txt
 *
 * 原生库（v1.3.0）：libsherpa-onnx-jni.so v1.13.4 static-link 版
 * （ORT 静态链入、DT_HASH 在、minSdk 21、无 __register_atfork）——
 * ABI 配对问题根治：不再依赖任何 libonnxruntime.so。
 *
 * 唤醒词（keywords.txt，带调拼音 token 格式）：
 *   x iǎo zh ì x iǎo zh ì @小智小智
 *   x iǎo x ùn x iǎo x ùn @小讯小讯
 *
 * 设计：KWS 持续运行（不随会话暂停），以支持 TTS 播放中的 barge-in。
 */
public final class KwsEngine {

    private static final String TAG = "L5CLIENT";

    public interface OnKeyword {
        void onKeyword(String keyword);
    }

    /** assets 下的相对目录（AssetManager 用）。v1.3.0：int8 量化版（APK 体积减半，CPU 上更快） */
    private static final String ASSET_DIR = "kws";

    private static final String ENCODER = ASSET_DIR + "/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx";
    private static final String DECODER = ASSET_DIR + "/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx";
    private static final String JOINER  = ASSET_DIR + "/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx";
    private static final String TOKENS   = ASSET_DIR + "/tokens.txt";
    /** v0.3.0：默认关键词表（自定义唤醒词走 AppSettings，失败回退到它） */
    private static final String KEYWORDS = ASSET_DIR + "/keywords.txt";

    // ================== v0.3.0：总开关 ==================
    /** 设置里「启用小智」关闭时置 true：feed() 直接返回，一个核都不烧。
     *  电台/酷我播放不受影响（MusicPlayer/Kuwo 在同进程但独立）。 */
    public static volatile boolean disabled = false;

    // ================== v1.3.25：KWS 独立灵敏度旋钮（方案 A）==================
    //
    // 为什么必须**独立于** AudioCapture.uplinkGain（那个是给上行/ASR 调的）：
    //
    //   KWS(zipformer+fbank) 与 ASR(服务器侧) 的最佳工作点根本不同——
    //     · ASR 侧自带能量归一化，uplinkGain=16 已足够，再大只会削顶伤识别；
    //     · KWS **不做归一化**，直接吃绝对幅度。远场轻声时我们实测
    //       rms ≈ 13 / 32768 = **-68 dBFS**，乘 16 后仍只有 **-44 dBFS**；
    //       而模型训练集里的正常说话在 -30 ~ -20 dBFS。整整差了 20dB 以上，
    //       语音的 log-mel 能量整体偏低 → 落进不了模型的判别区间 → 命中概率塌掉。
    //
    //   → 这就是「原厂小讯放很远轻轻说都能唤醒、小智却要贴近说」的机理。
    //     它**不是 SNR 问题**（放大对 SNR 无损无益），而是**特征幅度落在感受野之外**。
    //     所以：只在 KWS 入口前单独再放大，不污染上行，就不会伤 ASR。
    //
    //   ⚠️ 千万不要把这份增益加到 AudioCapture.uplinkGain 上 —— 那会同时抬高
    //      送给服务器的音频，削顶后 STT 立刻变差（v1.3.22 已验证过 16 是上行甜点）。

    /** KWS 专用前置增益（1..64，**默认 1 = 不放大**）。files/kwsgain<N> 覆盖。
     *  ⚠️ v1.3.27 离线复算实测：放大是**负优化** —— 8 段真实喊话里 gain 0.5/1.0 命中 2 段，
     *  2.0/4.0/8.0 只命中 1 段。别再往上调。 */
    public static volatile float gain = 1.0f;

    /** 关键词命中阈值（越小越灵敏；**默认 0.05**）。files/kwsthr<N>（N=阈值×100）。
     *  ⚠️ 与 score 是**协同**关系：score 低时调它完全无效；score=6 时 0.15→0.05 才解锁增益。 */
    public static volatile float threshold = 0.05f;

    /** 关键词加权分（**默认 6.0**）。files/kwsscore<N>（N=分数×10）。
     *  ⭐ v1.3.27 实测这是**灵敏度第一杠杆**：`score` 2.0→6.0 让真实唤回从 2 段涨到 4 段。 */
    public static volatile float score = 6.0f;

    // ================== v1.3.27：离线复算把三件事钉死（据 kwsdump + sherpa-onnx）==================
    //
    //   ① **增益是负优化**：gain 0.5/1.0 命中 2 段，2.0/4.0/8.0 只 1 段 → 默认改回 1。
    //      （v1.3.25「放大恢复感受野」、v1.3.26「LayerNorm 免疫/SNR 是瓶颈」均已被实测推翻；
    //        顺带证明连 SNR=−1.8dB 的干净语音模型都能认，能量根本不是瓶颈。）
    //   ② **阈值必须配 score 用**：score=2 时 thr 0.25↔0.05 毫无差别；
    //      score=6 时 thr 0.15→0.05 才解锁（2 段 → 4 段）。
    //   ③ **「你好小智」是误报源**：它比「小智小智」短、更易碰瓷 —— 纯底噪 228s 误报 3 次
    //      全来自它，且会把用户喊的「小智小智」误匹配成它（6 段"命中"里 5 段是假的）。
    //      已从 keywords.txt 删除。
    //   最终组合（thr=0.05 / score=6.0 / paths=8 + 去「你好小智」）：**召回 2 段 → 4 段，误报 0**。

    /** 解码时保留的活跃路径数（**默认 8**）。增大 = 弱信号下正确路径不易被剪掉，召回↑、CPU↑。
     *  实测 4→8 有增益；16 会引入误报，不要再大。files/kwspaths<N> */
    public static volatile int maxActivePaths = 8;

    /**
     * v1.3.36 ★★：ORT 会话线程数（**默认 1**，`files/kwsthreads<N>`，1..4）。
     *
     * 为什么从 2 降到 1 —— 2026-10-05 19:44 逐线程取证（/proc/PID/task/tid/stat 差分）：
     *
     *     L5MicReader   98.5%   <- readData 空转自旋（见 AudioCapture.uniIdleBackoffMs）
     *     R1KwsInit     57.4%   +-- 三个同名线程 = 2 个 ORT intra-op worker + 真 init 线程
     *     R1KwsInit     52.7%   |
     *     R1KwsInit     35.6%   +-- 合计 145.7%
     *     mic-acquire   48.8%   <- 四麦 HAL 自己的 mic_array 线程（pthread 继承创建者 comm）
     *     -------------------------------------
     *     进程合计 294.7%（全机 idle 20s 只涨 4 jiffies = 4 核 100% 打满）
     *
     * 真算力远小于 145.7%：ORT 的 intra-op 线程池**做完一次 Run 后自旋等下一个任务**
     * （spin-then-futex，默认 spin 计数不低），我们每 60ms 才喂一帧，其余时间全在自旋。
     * ORT 在 `intra_op_num_threads == 1` 时**改用顺序执行器、不建线程池** ⇒ 自旋整体消失，
     * 剩下的就是这份模型真实的推理开销（A7 上 x86 之外的量级，约 single-core 的 20~40%）。
     * 代价：推理从「2 线程并行」变成「1 线程串行」，单帧延迟略升（zipformer-KWS 单帧
     * 本来就只有几毫秒~十几毫秒，60ms 帧周期绰绰有余）。
     */
    public static volatile int numThreads = 1;

    /** 命中判定容忍的尾随 blank 帧数（默认 2）。增大 = 词尾稍模糊也认，召回↑、误唤醒↑。files/kwsblank<N> */
    public static volatile int numTrailingBlanks = 2;

    /**
     * v1.3.26：把喂进 KWS 的每一帧 PCM 原样落盘（files/kwsdump 打开）。
     * 用途 = 真·终极诊断：把远场喊话的音频拉回 PC，用 sherpa-onnx 离线复算
     * 「模型到底给这段音频打了多少分」—— 直接分辨「模型看不见信号」还是
     * 「看见了但阈值卡住」。文件上限 20MB（≈10 分钟 @16k mono）后自动停写。
     */
    public static volatile boolean dumpEnabled = false;

    /** 软限幅拐点：|v|<=KNEE 完全线性（无损），超过后 tanh 平滑压向 ±1，避免硬削顶的谐波污染。 */
    private static final float KNEE = 0.8f;

    /** 增益范围校验（服务层解析文件后调用）。 */
    public static void setGain(float g) {
        if (g >= 1f && g <= 64f) {
            gain = g;
        }
    }

    /** 阈值范围校验：0.01 ~ 1.00 */
    public static void setThreshold(float t) {
        if (t >= 0.01f && t <= 1.0f) {
            threshold = t;
        }
    }

    /** 加权分范围校验：0.0 ~ 10.0 */
    public static void setScore(float s) {
        if (s >= 0f && s <= 10.0f) {
            score = s;
        }
    }

    /** v1.3.26：活跃路径数范围校验：1 ~ 32（默认 4） */
    public static void setMaxActivePaths(int n) {
        if (n >= 1 && n <= 32) {
            maxActivePaths = n;
        }
    }

    /** v1.3.26：尾随 blank 容忍范围校验：0 ~ 10（默认 2） */
    public static void setNumTrailingBlanks(int n) {
        if (n >= 0 && n <= 10) {
            numTrailingBlanks = n;
        }
    }

    private final Context ctx;
    private final OnKeyword cb;

    private KeywordSpotter spotter;
    private OnlineStream stream;

    /** 复用 float 缓冲，避免每帧分配（60ms@16k = 960 样点） */
    private final float[] fbuf = new float[AudioCapture.FRAME_SAMPLES];
    /** v1.3.39：preRoll 回灌用的第二块 float 缓冲（不能和 fbuf 抢） */
    private final float[] pbuf = new float[AudioCapture.FRAME_SAMPLES];

    public KwsEngine(Context ctx, OnKeyword cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    public boolean init() {
        if (spotter != null) {
            return true;
        }
        // 先确认 assets 真的在（给个明确日志，避免再次盲猜）
        try {
            String[] list = ctx.getAssets().list(ASSET_DIR);
            StringBuilder sb = new StringBuilder();
            for (String s : list) {
                sb.append(s).append(' ');
            }
            Log.i(TAG, "assets/" + ASSET_DIR + " = [" + sb.toString().trim() + "]");
            if (list == null || list.length == 0) {
                Log.e(TAG, "assets/" + ASSET_DIR + " is EMPTY!");
                return false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "list assets/" + ASSET_DIR + " failed", t);
        }

        try {
            OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
            transducer.setEncoder(ENCODER);
            transducer.setDecoder(DECODER);
            transducer.setJoiner(JOINER);

            OnlineModelConfig modelConfig = new OnlineModelConfig();
            modelConfig.setTransducer(transducer);
            modelConfig.setTokens(TOKENS);
            modelConfig.setNumThreads(numThreads); // v1.3.36：默认 1（消灭 ORT 线程池自旋）
            modelConfig.setProvider("cpu");
            modelConfig.setModelType("zipformer2");

            FeatureConfig featConfig = new FeatureConfig();
            featConfig.setSampleRate(AudioCapture.SAMPLE_RATE);
            featConfig.setFeatureDim(80);

            KeywordSpotterConfig cfg = new KeywordSpotterConfig();
            cfg.setFeatConfig(featConfig);
            cfg.setModelConfig(modelConfig);
            // v0.3.2：唤醒词选择 —— 预设走 keywords 资源文件；任意词由
            // PinyinKws 生成 token 内容，走 createStream(内容)（sherpa 的
            // 关键词内容接口，非空时覆盖词表文件）。
            String kwWord = AppSettings.wakeWord(ctx);
            String kwFile = KEYWORDS;
            boolean preset = "小智小智".equals(kwWord);
            if ("小讯小讯".equals(kwWord)) {
                kwFile = ASSET_DIR + "/keywords_xiaoxun.txt";
                preset = true;
            } else if ("你好小智".equals(kwWord)) {
                kwFile = ASSET_DIR + "/keywords_nihao.txt";
                preset = true;
            } else if ("天猫精灵".equals(kwWord)) {
                kwFile = ASSET_DIR + "/keywords_tianmao.txt";
                preset = true;
            }
            // v1.3.25：score / threshold 改为可配（默认 score=2.0 / threshold=0.15）
            cfg.setKeywordsScore(score);
            // 原 0.25 是 sherpa-onnx 中文 KWS 常用阈值；远场 + KWS 前置增益后
            // 信噪条件变了，默认放到 0.15（更灵敏）。过高漏唤醒、过低误唤醒。
            cfg.setKeywordsThreshold(threshold);
            // v1.3.26：改为可配（默认 4 / 2，见字段注释）
            cfg.setMaxActivePaths(maxActivePaths);
            cfg.setNumTrailingBlanks(numTrailingBlanks);

            cfg.setKeywordsFile(kwFile);
            try {
                // ⚠️ 路径全部为 assets 相对路径，构造走 newFromAsset
                spotter = new KeywordSpotter(ctx.getAssets(), cfg);
            } catch (Throwable t2) {
                Log.e(TAG, "keywords " + kwFile + " failed, fallback " + KEYWORDS, t2);
                SessionLog.i("keywords " + kwFile + " failed (" + t2 + "), fallback default");
                spotter = null;
                cfg.setKeywordsFile(KEYWORDS);
                spotter = new KeywordSpotter(ctx.getAssets(), cfg);
            }
            // ---- v0.3.2：任意唤醒词（内容接口）----
            String customLine = null;
            if (!preset) {
                customLine = PinyinKws.toKeywordLine(ctx, kwWord);
                if (customLine == null) {
                    String m = "唤醒词「" + kwWord + "」拼音转换失败，回退默认唤醒词";
                    Log.w(TAG, m);
                    SessionLog.i(m);
                }
            }
            if (customLine != null) {
                stream = spotter.createStream(customLine);
                if (stream == null) {
                    Log.e(TAG, "createStream(custom) null, fallback default keywords");
                    SessionLog.i("createStream(custom) null, fallback default");
                }
            }
            if (stream == null) {
                stream = spotter.createStream("");
            }
            String ready = "KwsEngine ready (assets mode) wakeWord=" + kwWord
                    + (customLine != null ? " [custom:" + customLine + "]" : " [file:" + kwFile + "]")
                    + " gain=" + gain
                    + " thr=" + threshold + " score=" + score
                    + " paths=" + maxActivePaths + " blank=" + numTrailingBlanks
                    + " threads=" + numThreads
                    + " dump=" + dumpEnabled;
            Log.i(TAG, ready);
            SessionLog.i(ready);
            // v1.3.26：开 dump（覆盖上一次的文件，每次 init 从零开始）
            if (dumpEnabled) {
                openDump();
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "KwsEngine init failed", t);
            spotter = null;
            stream = null;
            return false;
        }
    }

    /**
     * v1.3.39 ★★★：**静音门控（VAD gate）**，默认开。
     *
     * 为什么必须做 —— 2026-10-05 20:04 实测（v1.3.38 逐段计时，5s 窗口）：
     *
     *     readMs=9     ← readData 免费（配速后不再自旋）
     *     down=105ms   ← 下混+增益  2%
     *     tel=87ms     ← 逐帧遥测  1.7%
     *     feed=5032ms  ← onFrame→kws.feed = **100.6% 整整一个核**
     *     （80 帧/5s ⇒ **62.9ms CPU / 每 60ms 音频帧**，线程 99.7% 占满，
     *       即「刚好卡在实时边缘」——这就是「小智一开就把 CPU 吃光」的真身）
     *
     * 而且 `files/kwspaths` 2/4/8/16 四档实测 feed 4830~5012ms/5s **几乎无差别**
     * ⇒ 开销**不在** beam search，全在 zipformer **编码器前向**（与音频时长成正比）。
     * 结论：降路径没用，得**少喂**。
     *
     * 做法：每帧先算一个平方和（960 样点，实测量级 0.1ms，可忽略）当闸门；
     * 只有「明显高于本底噪声」的帧才送进模型。门开时先把 preRoll 里的前置帧
     * 回灌一遍，保证唤醒词首字不被切掉；关门要连续安静 gateHoldFrames 帧。
     * 稳态安静时省掉 ~95% 的编码器算力；有人说话时照常满速跑（唤醒不受影响）。
     *
     * 噪声底用**非对称**跟踪：向下快（0.7/0.3），向上极慢（0.999/0.001）——
     * 这样人声、电视不会把底噪抬上去把门焊死，而环境变吵时底噪仍能慢慢跟上。
     */
    public static volatile boolean gateEnabled = true;

    /** 触发余量（dB，默认 3）：帧 rms 高于「噪声底 × 10^(margin/20)」即认为有声。
     *  v1.3.43 与 gateOpenDb 同步 6 -> 3。 */
    public static volatile float gateMarginDb = 3f;

    /** v1.3.39：**开门**余量（dB，默认 3）—— 高于底噪这么多才开门。
     *
     *  v1.3.43 ★ 从 6 降到 3（**唤醒率修复**）：2026-10-05 20:38 实机取证
     *  （jp2 v1.3.42，用户报「叫不醒」）——
     *
     *      20:38:14.303 OPEN  rms=0.00515 floor=0.00254  阈值=floor x 1.995 = 0.00506
     *      20:38:26.829 OPEN  rms=0.01047 floor=0.00332  阈值=0.00662
     *      20:38:40.103 OPEN  rms=0.00610 floor=0.00285  阈值=0.00569
     *      三次门开窗口内 fed=13 / 2 / 13 帧（120~780ms），**没有一次 KWS HIT**
     *      对照 20:37:34.816 OPEN rms=0.01241（用户喊得更响）-> 2 秒内 HIT
     *
     *  即：6dB 的阈值恰好压在**远场唤醒词的能量区间**上。用户正常音量喊
     *  「小智小智」，远场 rms 峰值就在 0.005~0.010，而阈值是 floor x 2 ≈ 0.005~0.007
     *  ⇒ 唤醒词前半段顶不开门被 skip，只有喊得特别响才勉强开门 ⇒ 体感「叫不醒」。
     *  降到 3dB（floor x 1.41）后阈值 0.0035~0.0047，唤醒词一开口即可开门。 */
    public static volatile float gateOpenDb = 3f;

    /** v1.3.39：**关门**余量（dB，默认 1）—— 掉到底噪 +1dB 以下才考虑关门。
     *  与开门余量（3dB）拉开 2dB = **滞回**；实测环境噪声本身就在 ±2dB 内抖，
     *  单一阈值会每 0.5s 开合一次（每次开门 = 一次 12 帧回灌的 CPU 突发），
     *  滞回后开合次数降两个量级。v1.3.43 与开门余量同步 2 -> 1。 */
    public static volatile float gateCloseDb = 1f;

    /** 连续安静多少帧后关门（默认 40 帧 = 2.4s）。v1.3.43 从 12（720ms）提到 40：
     *  实测门开后 1.0~1.3s 就关，而「小智小智」词间有自然停顿（"小智" ‖ "小智"），
     *  720ms 扛不住这口气 ⇒ 词的后半截也被切。2.4s 覆盖整个短语 + 尾音。 */
    public static volatile int gateHoldFrames = 40;

    /** 开门时回灌的前置帧数（默认 12 帧 = 720ms）。v1.3.43 从 3（180ms）提到 12：
     *
     *  旧注释假设「回灌太贵，8 帧 = 0.5s 算力突发，回灌过长倒亏」—— 这个假设在
     *  v1.3.36~v1.3.41 把 CPU 从 294.7% 压到 55.4%、全机 idle 82% 之后**已经过时**。
     *  实测代价：12 帧 ≈ 0.74s CPU 突发，但 gateHoldFrames=40 后单次开门覆盖的帧数
     *  大增，摊薄后总账依然划算。
     *  ⚠️180ms 的回灌完全补不回被切掉的唤醒词开头（门开时 fed 只有 2~13 帧），
     *  KWS 收到的是「(...省略...)智小智」这种残缺短语 ⇒ 必然匹配失败。 */
    public static volatile int gatePreFrames = 12;

    /** 启动后先无条件放行多少帧（默认 30 帧 = 1.8s），让噪声底收敛。 */
    public static volatile int gateWarmupFrames = 30;

    // ------------- v1.3.46：「门焊死」修复 -------------

    /** v1.3.46：**连续开门**超过这么多帧后，判定「这不是人声」并让底噪转慢跟踪。
     *  默认 50 帧 = 3.0s。
     *
     *  根因（2026-10-05 21:1x 实机取证，jp2 v1.3.45）：
     *      kwsGate(5s): frames=80 fed=80 (100% fed)   <- 门全开、KWS 全速
     *      随后 am_anr launcher(Input dispatching timed out) +
     *           am_anr player(TIME_TICK broadcast) -> bg anr 被杀
     *           -> system_server 崩溃重启（kernel uptime 连续，但全 app
     *              BootReceiver 重跑、pid 29281 -> 4323）。
     *
     *  机理：开门期底噪被「冻结」（0.999/0.001），一旦持续噪声（环境噪声 /
     *  TTS 喇叭回声）把门顶开，floor 就永远追不上 rms ⇒ 门焊死 ⇒ 每 60ms 一帧
     *  全喂 zipformer → 单核 ~90% 常驻 → 系统调度崩溃。
     *
     *  依据：**人说话必有停顿**（换气 / 词间），连续 3s 不停顿的不是人声。
     *  故持续开门超时后底噪转为 0.99/0.01（时间常数 ~100 帧 = 6s），让 floor
     *  追上噪声电平，门自然关闭；而 1~2s 的唤醒词/短句不受影响。 */
    public static volatile int gateSustainFrames = 50;

    // ------------- v1.3.45：暂停策略（只 TTS 期 + 兜底超时） -------------

    /** v1.3.45：**只在 TTS 播放期暂停喂帧**（默认 true）。
     *
     *  TTS 播放时喇叭回声灌进 mic 会把门顶开、把 KWS 喂满 —— 纯白烧算力，
     *  还有「TTS 自己被识别成唤醒词」的自环风险。只暂停这一段，收益就是
     *  这部分算力，风险面最小（TTS 播放通常 2~15s）。
     *
     *  ⚠️ 为什么**不**在 LISTENING 期暂停：官方服务器会维持约 90s 的
     *  listening 空闲窗口，期间用户完全可能重新喊唤醒词。见 pauseInSession。 */
    /** v1.3.46：**TTS 播放期是否暂停喂帧**（默认 **false** = 保持运行）。
     *
     *  v1.3.45 曾默认 true（TTS 期暂停）以省 CPU + 防回声自环，但用户实测
     *  要求「它念回复时喊唤醒词也要能立刻打断」—— 唤醒词打断必须让 KWS 在
     *  TTS 期继续跑。故 v1.3.46 默认放开。
     *
     *  代价可控，因为 v1.3.46 同时修掉了「门焊死」：TTS 喇叭回声虽会顶开门，
     *  但持续 3s 后底噪转慢跟踪、门自动关闭（见 gateSustainFrames），
     *  所以 TTS 期 KWS 只在「有能量突变」的短窗口全速跑，不是整段 TTS。
     *
     *  回退：`files/kwsttspause` -> 恢复 v1.3.45 行为（TTS 期一律暂停）。 */
    public static volatile boolean pauseInTts = false;

    /** v1.3.45：**会话全程暂停**（默认 **false**，已废弃为 A/B 开关）。
     *
     *  v1.3.44 曾默认 true，酿成实机事故（2026-10-05 21:00）——
     *      20:59:58.866 kws PAUSE (state=CONNECTING)
     *      21:00:24.918 kws PAUSE (state=CONNECTING)   <- 此后 RESUME 再没出现
     *      21:02:03.299 TTS start (server idle-farewell，距上次 tts stop 68s)
     *  会话进 LISTENING 后一路挂着，kws RESUME 全程只在 20:58:28 出现过一次，
     *  之后设备对所有唤醒词**零响应**（用户体感「没反应了」）。
     *
     *  教训：状态机的「回 IDLE」出口不是唯一路径（服务器 farewell 前会话可
     *  长期停在 LISTENING）。把核心功能（唤醒）挂在一个可能永不触发的状态
     *  标志上 = 单点故障。故默认关闭，仅 files/kwssessall 可 A/B 打开。 */
    public static volatile boolean pauseInSession = false;

    /** 由 SessionController.setState() 维护：当前是否在会话中（非 IDLE）。 */
    public static volatile boolean sessionActive = false;

    /** v1.3.45：由 SessionController.setState() 维护：是否正在播放 TTS。 */
    public static volatile boolean ttsPlaying = false;

    /** v1.3.45：**暂停兜底超时**（ms，默认 30s）。
     *
     *  任何原因造成的「持续暂停」超过这个时长一律强制恢复 —— 宁可多烧
     *  一点 CPU，也绝不能让设备变哑。这是对 v1.3.44 事故的结构性防御：
     *  无论状态机怎么卡，KWS 最多停 30s。 */
    public static volatile long pauseMaxMs = 30000L;

    /** 会话/TTS 暂停被跳过的帧数（诊断用）。 */
    public static volatile long sessionSkipped = 0L;

    /** v1.3.45：上一次 feed 是否处于「暂停」态 —— 捕获恢复的翻转沿，
     *  在恢复的那一帧做门控重置。 */
    private boolean lastPaused = false;

    /** v1.3.45：本次暂停的起点（0 = 未暂停），配 pauseMaxMs 做兜底。 */
    private long pauseSinceMs = 0L;

    private static final int PRE_MAX = 20;   // v1.3.43: 8 -> 20（1.2s 环形缓冲，托住 12 帧回灌）
    private final byte[][] preRoll = new byte[PRE_MAX][];
    private int preRollHead;      // 下一个写入位置
    private int preRollLen;       // 已存帧数
    private float noiseFloor = -1f;
    private int quietRun;
    private int warmupLeft = -1;  // -1 = 尚未初始化
    private boolean gateOpen = true;
    /** v1.3.46：连续开门帧计数（关门 / 恢复复位时归零），配 gateSustainFrames。 */
    private int gateOpenRun;

    /** 供日志/取证：被门挡下的帧数 */
    public volatile long gateSkipped;
    /** 供日志/取证：门开关次数 */
    public volatile long gateOpens;

    // 门控窗口统计（每 5s 一行，看占空比）
    private long gateWinFrames, gateWinFed, gateWinSkip, gateWinOpens, gateWinMs;

    /**
     * 喂入一帧 16kHz 16bit mono PCM（长度应为 AudioCapture.FRAME_BYTES）。
     */
    public synchronized void feed(byte[] pcm, int len) {
        if (disabled || spotter == null || stream == null || pcm == null) {
            return;
        }
        int samples = len / 2;
        if (samples <= 0) {
            return;
        }
        if (samples > fbuf.length) {
            samples = fbuf.length;
        }
        // ---------------- v1.3.45：暂停判定（带兜底超时） ----------------
        // 触发条件二选一：① TTS 播放期（默认，防回声烧 CPU + 自环）
        //                ② 全会话期（pauseInSession，默认关，仅 A/B）
        boolean wantPause = (pauseInTts && ttsPlaying)
                || (pauseInSession && sessionActive);
        if (wantPause) {
            long nowMs = SystemClock.uptimeMillis();
            if (pauseSinceMs == 0L) {
                pauseSinceMs = nowMs;
            } else if (pauseMaxMs > 0 && nowMs - pauseSinceMs >= pauseMaxMs) {
                // 兜底：暂停太久（状态机可能卡死，如僵尸会话停在 LISTENING，
                // 或 TTS 状态没回退）-> 强制恢复。宁可多烧 CPU，也不能变哑。
                Log.i(TAG, "kws PAUSE TIMEOUT " + (nowMs - pauseSinceMs)
                        + "ms (state stuck?) -> force resume");
                pauseSinceMs = 0L;
                wantPause = false;
            }
        } else {
            pauseSinceMs = 0L;
        }
        if (wantPause != lastPaused) {
            lastPaused = wantPause;
            if (!wantPause) {
                // 刚恢复（TTS 播完 / 会话结束）：重置门控 —— 底噪按当前环境
                // 重新收敛，preRoll 清空，否则恢复瞬间会把残留帧回灌成误唤醒。
                preRollLen = 0;
                preRollHead = 0;
                warmupLeft = -1;
                gateOpen = true;
                quietRun = 0;
                gateOpenRun = 0;   // v1.3.46
            }
        }
        if (wantPause) {
            sessionSkipped++;
            return;
        }
        // v1.3.39：先用**整数累加**算帧能量（比 float 转换廉价得多），门控靠它。
        // 这样「被门挡下」的帧完全不进 float 转换，省掉那笔 2% 的固定开销。
        long acc = 0;
        for (int i = 0; i < samples; i++) {
            int lo = pcm[2 * i] & 0xFF;
            int hi = pcm[2 * i + 1];
            short s = (short) ((hi << 8) | lo);
            acc += (long) s * s;
        }
        float rms = (float) (Math.sqrt((double) acc / samples) / 32768.0);
        dumpFrame(pcm, len); // v1.3.26：KWS 输入取证（落的是原始帧，未加 gain，便于离线复算）

        // ---------------- v1.3.39：静音门控 ----------------
        if (gateEnabled) {
            if (warmupLeft < 0) {
                warmupLeft = gateWarmupFrames;
                noiseFloor = rms;
            } else if (warmupLeft > 0) {
                warmupLeft--; // 热身期：对称快跟踪，把底噪收敛到真实环境电平
                noiseFloor = noiseFloor * 0.8f + rms * 0.2f;
            } else if (!gateOpen) {
                // 关门期（安静）：快跟踪 —— 环境变吵时底噪能跟上，避免门被"焊死"
                noiseFloor = noiseFloor * 0.9f + rms * 0.1f;
            } else if (++gateOpenRun > gateSustainFrames && gateSustainFrames > 0) {
                // v1.3.46：持续开门超时 -> **不是人声，是持续噪声 / TTS 回声**。
                // 让底噪慢跟踪（时间常数 ~100 帧 = 6s），floor 追上噪声电平后
                // 门会自然关闭，算力回到静音档。修复「门焊死 -> system_server
                // 崩溃重启」的根因，见 gateSustainFrames 注释。
                noiseFloor = noiseFloor * 0.99f + rms * 0.01f;
            } else {
                // 开门期（可能有人说话）：**冻结**底噪，否则人声会把门自己抬关
                noiseFloor = noiseFloor * 0.999f + rms * 0.001f;
            }
            float openF = (float) Math.pow(10.0, gateOpenDb / 20.0);
            float closeF = (float) Math.pow(10.0, gateCloseDb / 20.0);
            if (warmupLeft > 0) {
                gateOpen = true;   // 热身期无条件放行
                quietRun = 0;
            } else if (gateOpen) {
                // 滞回：声级掉到 closeF 以下且持续 gateHoldFrames 帧才关门
                if (rms < noiseFloor * closeF) {
                    if (++quietRun >= gateHoldFrames) {
                        gateOpen = false;
                        quietRun = 0;
                        gateOpenRun = 0;   // v1.3.46：关门 -> 持续开门计数归零
                        Log.i(TAG, "kwsGate CLOSE (rms=" + rms + " floor=" + noiseFloor
                                + " px" + closeF + ")");
                    }
                } else {
                    quietRun = 0;
                }
            } else {
                // 关门期：越过 openF 才开门（比 closeF 高 ⇒ 有滞回，不抖）
                if (rms > noiseFloor * openF) {
                    gateOpen = true;
                    gateOpens++;
                    gateWinOpens++;
                    Log.i(TAG, "kwsGate OPEN (rms=" + rms + " floor=" + noiseFloor
                            + " px" + openF + ") replay=" + Math.min(preRollLen, gatePreFrames));
                }
            }
            if (!gateOpen) {
                // 关门期：只存 preRoll，不喂模型
                pushPreRoll(pcm, len);
                gateSkipped++;
                gateWinFrames++;
                gateWinSkip++;
                gateWinTick();   // v1.3.42：关门期也要滚窗口，否则计数无限累积
                return;
            }
            // 门开着：先把 preRoll 里的历史帧补喂回去（用 pbuf 转一次，别动 fbuf）
            if (preRollLen > 0) {
                int n = Math.min(preRollLen, gatePreFrames);
                int start = (preRollHead - n + PRE_MAX) % PRE_MAX;
                for (int i = 0; i < n; i++) {
                    byte[] f = preRoll[(start + i) % PRE_MAX];
                    if (f != null) {
                        int sn = Math.min(f.length / 2, pbuf.length);
                        toFloat(f, sn, pbuf);
                        acceptAndDecode(pbuf, sn);
                    }
                }
            }
            preRollLen = 0;
            preRollHead = 0;
        }
        // ---------------- 喂入当前帧 ----------------
        toFloat(pcm, samples, fbuf);
        acceptAndDecode(fbuf, samples);
        if (gateEnabled) {
            gateWinFrames++;
            gateWinFed++;
            gateWinTick();
        }
    }

    /**
     * v1.3.42：门控窗口统计的滚动出口。喂帧路径与关门跳过路径都必须调它
     * —— 之前只在喂帧路径内联实现，导致长期安静（一直走跳过路径）时
     * 窗口永不闭合、`frames=` 无限累积（实测出现过 frames=523 这种
     * 远超 5s*16.67 的荒谬值），日志口径失真会误导后续阈值调参。
     */
    private void gateWinTick() {
        long nowMs = SystemClock.uptimeMillis();
        if (gateWinMs == 0L) {
            gateWinMs = nowMs;
            return;
        }
        if (nowMs - gateWinMs < 5000L) {
            return;
        }
        gateWinMs = nowMs;
        Log.i(TAG, "kwsGate(5s): frames=" + gateWinFrames + " fed=" + gateWinFed
                + " skipped=" + gateWinSkip + " opens=" + gateWinOpens
                + " (" + (gateWinFrames > 0
                    ? Math.round(gateWinFed * 100.0 / gateWinFrames) : 0)
                + "% fed) open=" + gateOpen + " floor=" + noiseFloor);
        gateWinFrames = 0;
        gateWinFed = 0;
        gateWinSkip = 0;
        gateWinOpens = 0;
    }

    /** int16 LE → float [-1,1]，含 KWS 前置增益与软限幅（v1.3.25 语义不变） */
    private static void toFloat(byte[] pcm, int samples, float[] out) {
        final float g = gain;
        final float k = KNEE;
        final float span = 1.0f - k;
        final boolean amp = (g != 1.0f);
        for (int i = 0; i < samples; i++) {
            int lo = pcm[2 * i] & 0xFF;
            int hi = pcm[2 * i + 1];
            short s = (short) ((hi << 8) | lo);
            float v = s / 32768.0f;
            if (amp) {
                v *= g;
                if (v > k) {
                    v = k + span * (float) Math.tanh((v - k) / span);
                } else if (v < -k) {
                    v = -k - span * (float) Math.tanh((-v - k) / span);
                }
            }
            out[i] = v;
        }
    }

    /** preRoll 入环（存副本 —— 调用方的缓冲是复用的） */
    private void pushPreRoll(byte[] pcm, int len) {
        int n = Math.min(len, AudioCapture.FRAME_BYTES);
        byte[] slot = preRoll[preRollHead];
        if (slot == null || slot.length != n) {
            slot = new byte[n];
            preRoll[preRollHead] = slot;
        }
        System.arraycopy(pcm, 0, slot, 0, n);
        preRollHead = (preRollHead + 1) % PRE_MAX;
        if (preRollLen < PRE_MAX) {
            preRollLen++;
        }
    }

    /** 真正送模型：特征提取 + 解码（float 缓冲由调用方准备好） */
    private void acceptAndDecode(float[] buf, int samples) {
        if (samples <= 0) {
            return;
        }
        try {
            // 官方 Java API: acceptWaveform(float[] samples, int sampleRate)
            stream.acceptWaveform(buf, AudioCapture.SAMPLE_RATE);
            while (spotter.isReady(stream)) {
                spotter.decode(stream);
                KeywordSpotterResult res = spotter.getResult(stream);
                String kw = res.getKeyword();
                if (kw != null && kw.length() > 0) {
                    Log.i(TAG, "*** KWS HIT: " + kw);
                    if (cb != null) {
                        cb.onKeyword(kw);
                    }
                    spotter.reset(stream);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "kws feed/decode threw", t);
        }
    }

    /** 一次性把门控参数打出来（init 时调用） */
    public String gateInfo() {
        return "gate=" + gateEnabled + " openDb=" + gateOpenDb + " closeDb=" + gateCloseDb
                + " hold=" + gateHoldFrames + "f pre=" + gatePreFrames
                + "f warmup=" + gateWarmupFrames + "f";
    }

    // ---------------- v1.3.26：KWS 输入取证 ----------------

    /** dump 文件上限：20MB ≈ 10 分钟 @16k/mono/16bit。到顶即停写（保留最早那段，通常含第一次喊话）。 */
    private static final long DUMP_MAX_BYTES = 20L * 1024 * 1024;

    private java.io.FileOutputStream dumpOut;
    private long dumpBytes;

    /** 开 dump（覆盖旧文件）。由 init 在 dumpEnabled 时调用。 */
    private void openDump() {
        if (dumpOut != null) {
            return;
        }
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "kwsdump.pcm");
            dumpOut = new java.io.FileOutputStream(f, false);
            dumpBytes = 0;
            Log.i(TAG, "kwsdump open -> " + f.getAbsolutePath());
            SessionLog.i("kwsdump open (16k mono s16le raw)");
        } catch (Throwable t) {
            Log.w(TAG, "kwsdump open failed", t);
            dumpOut = null;
        }
    }

    private void dumpFrame(byte[] pcm, int len) {
        java.io.FileOutputStream o = dumpOut;
        if (o == null) {
            return;
        }
        try {
            if (dumpBytes < DUMP_MAX_BYTES) {
                o.write(pcm, 0, len);
                dumpBytes += len;
            } else {
                o.flush();
                o.close();
                dumpOut = null;
                Log.i(TAG, "kwsdump closed at limit bytes=" + dumpBytes);
                SessionLog.i("kwsdump closed at limit bytes=" + dumpBytes);
            }
        } catch (Throwable t) {
            dumpOut = null;
        }
    }

    private void closeDump() {
        java.io.FileOutputStream o = dumpOut;
        dumpOut = null;
        if (o != null) {
            try {
                o.flush();
            } catch (Throwable ignored) {
            }
            try {
                o.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void release() {
        closeDump(); // v1.3.26：收掉 KWS 输入取证文件
        try {
            if (stream != null) {
                stream.release();
                stream = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (spotter != null) {
                spotter.release();
                spotter = null;
            }
        } catch (Throwable ignored) {
        }
    }
}
