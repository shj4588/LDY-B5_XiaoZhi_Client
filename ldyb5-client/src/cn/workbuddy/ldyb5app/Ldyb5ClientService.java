package cn.workbuddy.ldyb5app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R1 小智客户端前台服务 —— 组件装配与生命周期。
 *
 * v1.2.3 核心改造（ANR 根治，见 DIAG-v1.2.2.md）：
 *   v1.2.2 的 onCreate 在 main 线程同步做完全部装配，其中
 *   TtsPlayer.start() → AudioTrack.getMinBufferSize() 是同步 binder 调用，
 *   mediaserver 音频 HAL 死锁时永不返回 → main 卡 20s → ANR → kill → 循环。
 *
 *   现在：
 *   1. onCreate（main 线程）只做 startForeground + 派发工作线程，必在 5s 内返回；
 *   2. 全部装配在 "R1Pipeline" 工作线程进行；
 *   3. 音频初始化（TTS/mic）包 2.5s 看门狗，超时降级不拖垮服务；
 *   4. 降级后按 5s/15s/60s/300s 退避重试，成功即运行期补挂；
 *   5. 心跳日志每 10s 打活体状态，方便验证"服务到底活了多久"。
 *
 * v1.2.4 核心改造（会话期开麦，见 SessionController 类注释）：
 *   实测证明 4 麦 HAL 独占 + 原厂云知声 KWS 靠它活着。v1.2.3 常开
 *   AudioRecord 会把「小讯小讯」挤瞎。现在启动路径完全不碰麦克风，
 *   bus 唤醒事件到达后由 SessionController 开麦，会话结束归还。
 *
 * 装配顺序（工作线程内）：
 *   1. OpusCodec（纯 JNI，无 binder）
 *   2. WakeupBridge（msgcenter 总线注册，与音频无关）
 *   3. TtsPlayer（音频，看门狗保护，失败降级为 null）
 *   4. XiaoZhiWs + OtaClient + SessionController
 *   5. AudioCapture —— 只创建不启动（麦是会话期资源）
 *
 * KWS：v1.2.3 起默认关闭（WAKEUP_MODE = MODE_BUS_ONLY）。
 *   原因：sherpa-jni(按 ORT 1.28.2 编译) 与 ORT 1.17.1 的 OrtApi 结构体
 *   ABI 错配 → newFromAsset+260 处 SIGSEGV，try/catch 拦不住，
 *   只要它在启动路径上进程必死（v1.2.2 实测三次 pid 连崩）。
 *   修好成套产物后再切回 MODE_HYBRID。
 */
public class Ldyb5ClientService extends Service {
// v1.3.33 ★ 去掉 final：Janus 顶替 Pandora 时，manifest 里原厂 Application 是
//   com.phicomm.speaker.device.ExampleApp、主 Service 是
//   ...ui.service.WindowsService —— 后者用 `extends Ldyb5ClientService` 一行复用全部逻辑。
//   Java 不允许继承 final 类，所以这里必须放开。
//   （此前是在 dex 二进制里改 ACC_FINAL 位，现在源头放开，不必再打那个补丁。）

    private static final String TAG = "L5CLIENT";
    private static final int NOTI_ID = 0x5231; // 'R1'

    /**
     * 唤醒方案开关。
     *
     * MODE_BUS_ONLY    —— 只用原厂云知声唤醒（听到「小讯小讯」唤醒小智）。
     *                     不加载 sherpa/ORT，彻底避开 KWS native 崩溃。
     * MODE_KWS_ONLY    —— 只用自建 sherpa-onnx KWS（听到「小智小智」唤醒）。
     * MODE_HYBRID      —— 两者并存。
     *
     * ⚠️ v1.2.3 默认 MODE_BUS_ONLY：KWS 的 OrtApi ABI 错配未修（DIAG §2），
     * 切回 HYBRID 前必须先解决 sherpa-jni 与 ORT 的成套产物问题。
     */
    private static final int MODE_BUS_ONLY = 1;
    private static final int MODE_KWS_ONLY = 2;
    private static final int MODE_HYBRID = 3;

    // v1.3.0：HYBRID —— 自建 KWS 为主，总线唤醒兜底。
    // 注意：麦克风常驻后原厂引擎断粮，总线事件实际不会再来；
    // 但保留注册无害，且 KWS 初始化失败时（麦保持 on-demand）bus 仍兜底。
    private static final int WAKEUP_MODE = MODE_HYBRID;

    /** 音频初始化看门狗超时（ms）：HAL 死锁时 binder 可能永不返回 */
    private static final long AUDIO_WATCHDOG_MS = 2500;

    /** 音频降级后的重试退避序列（ms） */
    private static final long[] RETRY_BACKOFF_MS = {5000, 15000, 60000, 300000};

    private AudioCapture mic;
    private OpusCodec opus;
    private volatile TtsPlayer player;   // v1.2.3: 可为 null（降级），重试成功后补挂
    private KwsEngine kws;
    /**
     * v0.3.3：服务器设置（模式/地址/token）变更后的热重连。
     * SettingsUI 保存/切换时触发 —— 不再要求重启服务。
     */
    public static volatile Runnable onServerSettingsChanged;
    /**
     * v0.3.53：设置页「保存灵敏度并重启小智」的完成回调。
     * 服务重新启动、管线装配且 KWS 引擎 init 返回后触发一次，UI 据此提示
     * 「重启完成」；restartNotifyPending 保证开机自启 / 外部拉起不会误弹。
     */
    public static volatile Runnable onRestartReady;
    public static volatile boolean restartNotifyPending = false;
    /** v1.3.30：唤醒处理专用线程。KWS 的 onKeyword 原来在采集线程内同步执行，
     *  ack 播放（AudioTrack write）/startSession 一旦阻塞就把采集+KWS 整条链拖死
     *  （15:12:48 实锤）。现在唤醒处理 post 到这里，采集线程只喂 KWS。 */
    private Handler kwsWakeHandler;
    private WakeupBridge wakeup;
    private XiaoZhiWs ws;
    private SessionController session;
    private OtaClient ota;

    /** 幂等保护：onCreate 只允许派发一次管线线程 */
    private volatile boolean pipelineStarted = false;
    /** onStartCommand 幂等 */
    private volatile boolean started = false;

    private int audioRetryCount = 0;
    /** 上一次 TTS 初始化尝试是否已结束（false = 还有线程卡在 binder 里） */
    private final AtomicBoolean ttsAttemptDone = new AtomicBoolean(true);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // v0.3.65：全局音量条触发（固件无系统音量面板）。
    // 用 VOLUME_CHANGED_ACTION 广播：系统在任何界面（小智内/外）按音量键改了媒体音量都会发此广播，
    // 我们据此弹出与「小智头像」同款的全局悬浮窗音量条。比 MediaSession.onVolumeKeyEvent 可靠——
    // 本机固件不会把音量键派发给 MediaSession（实测回调不触发），但系统改音量时广播必到。
    private BroadcastReceiver volBr;

    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "=========== Ldyb5ClientService onCreate (mode=" + WAKEUP_MODE + ") ===========");
        // v1.2.9：落盘日志初始化（logcat 会被原厂录音刷屏活埋，关键事件必须有第二份记录）
        SessionLog.init(this);
        // v0.3.9：灵动岛全局悬浮窗（服务生命周期内常驻，覆盖任何界面）
        IslandOverlay.start(this);
        String v;
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            v = "?";
        }
        SessionLog.i("=== Ldyb5ClientService onCreate v" + v + " (mode=" + WAKEUP_MODE + ") ===");

        // ===== main 线程只做这一件事（ANR 判定只看主线程）=====
        startForegroundSafely();
        Log.i(TAG, "onCreate: foreground up, spawning pipeline thread");

        // v1.3.8：adb 调试唤醒 —— `am broadcast -a cn.workbuddy.ldyb5app.DEBUG_WAKE`
        // 直接触发一轮会话，不走总线/KWS，用于开流时序的精确控制实验。
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                Log.i(TAG, "DEBUG_WAKE broadcast received");
                SessionLog.i("debug wakeup via adb broadcast");
                if (session != null) {
                    session.debugWakeup();
                } else {
                    SessionLog.i("debug wakeup ignored: session not ready");
                }
            }
        }, new IntentFilter("cn.workbuddy.ldyb5app.DEBUG_WAKE"));

        // ===== 其余装配全部丢给工作线程 =====
        if (!pipelineStarted) {
            pipelineStarted = true;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        buildPipeline();
                    } catch (Throwable tr) {
                        Log.e(TAG, "!!! pipeline assembly FAILED", tr);
                    }
                }
            }, "R1Pipeline").start();
        }

        startLivenessTick();
        registerVolumeReceiver();
    }

    // ================= 管线装配（工作线程） =================

    private void buildPipeline() {
        // v0.3.52：先应用设置页的「唤醒灵敏度」档位，再解析 files/kwsthr|kwsscore
        // 调试 flag —— 保证 flag 文件仍能覆盖 UI 设置（与既有调试旋钮的优先级一致）。
        // threshold/score 只在 KwsEngine.init() 里读，所以必须在 startKwsAsync() 之前。
        AppSettings.applyKwsSensitivity(this);

        // ---- 1) opus 编解码（纯 JNI，无 binder，不可能卡）----
        opus = new OpusCodec();
        boolean encOk = opus.openEncoder();
        boolean decOk = opus.openDecoder();
        Log.i(TAG, "opus enc=" + encOk + " dec=" + decOk);

        // ---- 2) 唤醒引擎：总线监听（msgcenter，与音频 HAL 无关）----
        if (WAKEUP_MODE != MODE_KWS_ONLY) {
            wakeup = new WakeupBridge(this, new WakeupBridge.OnWakeup() {
                public void onWakeup(final String word) {
                    if (session != null) {
                        session.onWakeup(word);
                    }
                }
            });
            boolean wOk;
            try {
                wOk = wakeup.register();
            } catch (Throwable t) {
                Log.e(TAG, "WakeupBridge.register threw", t);
                wOk = false;
            }
            Log.i(TAG, "WakeupBridge register=" + wOk);
        }

        // ---- 3) TTS（看门狗保护；失败降级 player=null）----
        ensureTts();

        // ---- 4) WS / OTA / 会话控制器 ----
        ws = new XiaoZhiWs(new XiaoZhiWs.Listener() {
            public void onConnected() {
                if (session != null) session.onConnected();
            }

            public void onHandshakeDone() {
                if (session != null) session.onHandshakeDone();
            }

            public void onTtsStart() {
                if (session != null) session.onTtsStart();
            }

            public void onTtsAudio(byte[] opusFrame) {
                if (session != null) session.onTtsAudio(opusFrame);
            }

            public void onTtsStop(boolean aborted) {
                if (session != null) session.onTtsStop(aborted);
            }

            public void onStt(String text) {
                if (session != null) session.onStt(text);
            }

            public void onClosed(String reason) {
                if (session != null) session.onClosed(reason);
            }

            public void onError(String msg) {
                if (session != null) session.onError(msg);
            }
        });

        ota = new OtaClient(this, new OtaClient.Callback() {
            public void onToken(String token) {
                Log.i(TAG, "got token from OTA: " + (token == null ? "null" :
                        token.substring(0, Math.min(12, token.length())) + "..."));
                ws.setToken(token);
            }

            public void onWsUrl(String url) {
                // v1.2.5：OTA 下发的 ws 地址（覆盖默认官方地址，NAS 自建服务器用）
                if (ws != null) {
                    ws.setUrl(url);
                }
            }

            public void onFail(String msg) {
                Log.w(TAG, "OTA failed: " + msg + " (will try ws without token)");
                SessionLog.i("OTA failed: " + msg + " -> ws without token");
            }

            public void onDone() {
                // v0.3.3：启动路径无需主动 connect（唤醒时按需连），仅记日志
                SessionLog.i("OTA initial fetch done");
            }
        });
        // v1.2.7：ws 握手补 Device-Id/Client-Id 头（与 OTA 请求头同源）。
        // 官方服务器靠这两个头把连接路由到已绑定设备会话，缺了则 hello 无回应。
        ws.setDeviceId(ota.getDeviceId());
        ws.setClientId(ota.getClientId());
        // v0.3.1：设置里手动填的 token —— 启动即挂上（OTA 全挂也有 token 可用）；
        // OTA 下发回调里 effToken() 会保证手动 token 不被覆盖
        String customToken = AppSettings.tokenCustom(this);
        if (customToken != null && customToken.length() > 0) {
            ws.setToken(customToken);
            Log.i(TAG, "token: use custom from settings");
            SessionLog.i("token: use custom from settings");
        }
        ota.fetch();

        // v0.3.3：注册服务器热重连钩子（SettingsUI 保存/切换时调用）
        onServerSettingsChanged = new Runnable() {
            public void run() {
                reconnectServer();
            }
        };

        // v1.3.10~1.3.13：音频配置开关。**默认 = 已验证可用的组合**（开箱即用，无需 flag）：
        //   VOICE_COMMUNICATION + AEC 关 + gain16 + sessBind 关
        // files/aec_on    -> 打开系统 AEC（v1.3.9 行为，会连人声一起压，一般别开）
        // files/src_vrec  -> 采集源退回 VOICE_RECOGNITION（v1.2.x 老源 = 悬空噪，别开）
        // files/sess_bind -> 同 session 挂常驻静音播放流，逼 HAL 进全双工语音用例
        // files/gain1|gain4|gain8|gain16|gain32 -> 覆盖上行软件增益（命中第一个为准，gain1 最优先）
        // files/kwsgain<N>|kwsthr<N>|kwsscore<N> -> KWS 独立灵敏度旋钮（v1.3.25，见下方解析处注释）
        // 依据：v1.3.9 AEC 把底噪压没的同时也压没了人声；v1.3.10 关 AEC 后悬空噪根治
        // （rms 500->13）但电平仅满量程 2%；v1.3.11 软件增益 x16 把上行抬进 ASR 可用区；
        // v1.3.12 实测服务器 STT/TTS 全通（3 轮连续对话 + 天气工具调用）-> 故 gain16 定为默认。
        try {
            AudioCapture.aecEnabled = new java.io.File(getFilesDir(), "aec_on").exists();
            AudioCapture.useVoiceRec = new java.io.File(getFilesDir(), "src_vrec").exists();
            AudioCapture.sessionBind = new java.io.File(getFilesDir(), "sess_bind").exists();
            // v1.3.14：取证落盘默认关（免每帧 clone + 会话末主线程写 ~2MB）
            SessionController.dumpEnabled = new java.io.File(getFilesDir(), "uplink_dump").exists();
            SessionController.probeEnabled = new java.io.File(getFilesDir(), "stereo_probe").exists();
            int g = 16;          // v1.3.13：默认增益（无需任何 flag）
            int gainFlag = 0;    // 命中的覆盖值（0 = 未覆盖，用默认 16）
            int[] cand = {1, 32, 16, 8, 4};   // gain1 最优先 = 显式关增益
            for (int gi = 0; gi < cand.length; gi++) {
                if (new java.io.File(getFilesDir(), "gain" + cand[gi]).exists()) {
                    g = cand[gi];
                    gainFlag = cand[gi];
                    break;
                }
            }
            AudioCapture.uplinkGain = g;
            // v1.3.23：files/src_uni4mic -> 走原厂优声四麦阵列 HAL（远场收音）。
            // 用户实测「要离很近才能听到」= 我们拿的是裸 ADC 电平（满量程 2%），
            // 软件增益只放大信号不改善 SNR，远场救不回来。原厂 HAL 是唯一带
            // 波束成形/降噪/AGC 的通路。失败自动回落 AudioRecord（见 AudioCapture）。
            // ldyb5：四麦 HAL **默认开** —— MicProbe 已实证本机 HAL 完整可用
            // （board=UNI_4MIC_HAL_ANDROID_V1.1，init(1)/openAudioIn(2)/readData 全通，
            // 补丁库随 APK 捆绑，Android 8.1 linker namespace 已绕过）。
            // files/src_uni4mic_off -> 强制退回 AudioRecord（A/B 用）。
            AudioCapture.useUni4mic = !new java.io.File(getFilesDir(), "src_uni4mic_off").exists();
            // v1.3.28：files/src_bridge -> 走 MicBridge（player 内 Cap4mic 四麦 HAL 采集，
            // LocalSocket "ld5micbridge" 喂 16k mono 裸流）。D9 实测该通路 SNR 优于 A 通路
            // 且 HAL 自带高通（底噪干净）；桥模式下本进程不再开 AudioRecord（card1 让给 HAL）。
            AudioCapture.useBridge = new java.io.File(getFilesDir(), "src_bridge").exists();
            // v1.3.35：files/hal_ch0 -> 2ch 下混只取第 0 声道（默认取平均）。
            // 用于 A/B 判定四麦 HAL 的两声道到底是「双拾取」还是「处理后 + 回声参考」；
            // 若平均后出现 TTS 自触发/回声，切到它。
            AudioCapture.halTakeCh0 = new java.io.File(getFilesDir(), "hal_ch0").exists();
            String cfg = "audio cfg: src=" + (AudioCapture.useBridge ? "MICBRIDGE"
                    : (AudioCapture.useUni4mic ? "UNI4MIC-HAL"
                    : (AudioCapture.useVoiceRec ? "VOICE_RECOGNITION" : "VOICE_COMMUNICATION")))
                    + " aec=" + AudioCapture.aecEnabled
                    + " gain=" + AudioCapture.uplinkGain
                    + " sessBind=" + AudioCapture.sessionBind
                    + " (flags: src_bridge=" + AudioCapture.useBridge
                    + " src_uni4mic_off=" + !AudioCapture.useUni4mic
                    + " src_vrec=" + AudioCapture.useVoiceRec
                    + " aec_on=" + AudioCapture.aecEnabled
                    + " sess_bind=" + AudioCapture.sessionBind
                    + " hal_ch0=" + AudioCapture.halTakeCh0
                    + " gainFlag=" + (gainFlag == 0 ? "none(default16)" : String.valueOf(gainFlag))
                    + ")";
            Log.i(TAG, cfg);
            SessionLog.i(cfg);
        } catch (Throwable t) {
            Log.w(TAG, "audio cfg flag read failed", t);
        }

        mic = new AudioCapture();   // 先创建不启动，让 session 拿到非空引用
        session = new SessionController(this, mic, opus, player, null, ws);

        // ---- v0.3.0：设置中心启动应用 ----
        // 总开关关闭 → KWS 直接停喂（不烧那个核），会话也不会被唤醒拉起
        KwsEngine.disabled = !AppSettings.voiceEnabled(this);
        if (KwsEngine.disabled) {
            Log.i(TAG, "settings: voice disabled -> KWS feed paused");
            SessionLog.i("settings: voice disabled -> KWS feed paused");
        }
        Log.i(TAG, "settings: cue=" + AppSettings.cueOn(this)
                + " yield=" + AppSettings.yieldMusic(this)
                + " wakeScreen=" + AppSettings.wakeScreen(this)
                + " idle60=" + AppSettings.idleDisconnect60(this)
                + " voice=" + AppSettings.voiceEnabled(this)
                + " wakeWord=" + AppSettings.wakeWord(this)
                + " otaUrl=" + (AppSettings.otaUrl(this).length() > 0
                    ? "custom" : "default"));

        // ---- v1.3.15：延长连续对话 + 断开前提醒 ----
        // files/idle<N>        -> 续听窗口 N 秒（默认 90，范围 20~600）。用户停顿多久会话才收。
        // files/idle_alert_off -> 关闭本地「即将断开」提示音（服务器提醒不受影响）。
        // 语义：续听窗口 = 本地兜底时长；官方服务器在 listening 空闲 ~70s 会下发 TTS
        // 提醒、~80s 关会话，通常比本地兜底先到 —— 用户听到的就是官方的提醒语音。
        // 本地提示音只在服务器始终沉默时兜底（窗口末端提前 15s）。
        try {
            long idleMs = 90000L;
            String idleSrc = "default(90s)";
            java.io.File[] files = getFilesDir().listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    String nm = f.getName();
                    if (nm.startsWith("idle") && nm.length() > 4
                            && Character.isDigit(nm.charAt(4))) {
                        try {
                            int sec = Integer.parseInt(nm.substring(4));
                            if (sec >= 20 && sec <= 600) {
                                idleMs = sec * 1000L;
                                idleSrc = "files/" + nm;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            // v0.3.21：唤醒词打断（本地 KWS 常驻，播放中喊「小智小智」强制打断）。
            // 开启 = KWS 在 TTS 播放期不暂停（pauseInTts=false），命中唤醒词即由
            // handleWakeup 的 TTS_PLAYING 分支强制停 TTS + 干净重听；不再走服务端
            // realtime barge-in（R1 硬件 AEC 不完美、公网 aec 字段又会连不上）。
            KwsEngine.pauseInTts = !AppSettings.kwsInterrupt(this);
            // v1.3.18：files/prewake<N> -> 唤醒前环形缓冲帧数（60ms/帧）。
            // v1.3.24：默认 34 = 2.04s（原 20 = 1.2s 会啃掉唤醒词的第一个字，
            // 详见 SessionController.prewakeFrames 注释）。越大越不容易丢「唤醒词
            // 紧跟的话」，但首轮上行 backlog 也越大（服务器被甩得越远）。范围 2..60。
            String preSrc = "default(" + SessionController.getPrewakeFrames() + ")";
            if (files != null) {
                for (java.io.File f : files) {
                    String nm = f.getName();
                    if (nm.startsWith("prewake") && nm.length() > 7) {
                        try {
                            int n = Integer.parseInt(nm.substring(7));
                            if (n >= 2 && n <= 60) {
                                SessionController.setPrewakeFrames(n);
                                preSrc = "files/" + nm;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            session.setListenIdleMs(idleMs);
            // 本地兜底断开与续听窗口同值；服务器先关时可无缝接管
            ws.setIdleCloseDelayMs(idleMs);
            // v1.3.19：files/wakeprobe<N> -> LISTENING 态唤醒后的「响应探测」窗口秒数
            // （v1.3.24 默认收到 3s —— 健康会话 wake->STT 实测仅 1618ms）。
            // 服务器在这个窗口内既不发 STT 也不发 TTS、且影子里确实有人声
            // → 判定会话已死，重启会话并回放这段音频。范围 2..20。
            String probeSrc = "default(" + SessionController.getWakeProbeSec() + ")";
            if (files != null) {
                for (java.io.File f : files) {
                    String nm = f.getName();
                    if (nm.startsWith("wakeprobe") && nm.length() > 9) {
                        try {
                            int s = Integer.parseInt(nm.substring(9));
                            if (s >= 2 && s <= 20) {
                                SessionController.setWakeProbeSec(s);
                                probeSrc = "files/" + nm;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            // v1.3.20：files/wakeack_off -> 关掉唤醒应答音（默认开）。
            // 应答音 = 唤醒后立刻一声「叮」，让用户知道设备真的醒了 —— 此前唤醒后
            // 全程静默直到服务器开口，用户无从判断、等几十秒才说话，那时会话已被
            // 25s 无 TTS 看门狗收走（2026-10-04 17:27 实测「唤醒没回复」的真身）。
            SessionController.wakeAckEnabled =
                    !new java.io.File(getFilesDir(), "wakeack_off").exists();
            // v1.3.60：本地唤醒后交接拾音（唤醒词不上传）模式。
            // 优先读设置页开关；遗留 files/detect_on 也可开启（向后兼容，语义不变：
            // 开启后唤醒词语音不上传、不发 detect、无问候 TTS，唤醒后稍顿即可开口）。
            SessionController.serverListenMode =
                    AppSettings.serverListen(this)
                    || new java.io.File(getFilesDir(), "detect_on").exists();
            // v1.3.22：files/ackdrop<N> -> 唤醒应答音回声丢弃窗口毫秒数（默认 0 = 不丢）。
            // 0 才是正解（硬丢会剪断连读时间线，见 SessionController.wakeAckEchoDropMs
            // 注释里 17:54:49 的实证）；留这个旋钮只为万一仍出现「唤醒后凭空多一轮」
            // 时能不改代码把窗口加回去。
            String ackDropSrc = "default(0)";
            if (files != null) {
                for (java.io.File f : files) {
                    String nm = f.getName();
                    if (nm.startsWith("ackdrop") && nm.length() > 7) {
                        try {
                            long ms = Long.parseLong(nm.substring(7));
                            if (ms >= 0 && ms <= 600) {
                                SessionController.setWakeAckEchoDropMs(ms);
                                ackDropSrc = "files/" + nm;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            // v1.3.23：files/farewell<N> -> 「服务器空闲告别」的静默阈值秒数（默认 35s）。
            // 服务器主动开口且上次人声已过去 N 秒 -> 判为告别语，播完即关会话回 IDLE
            // （否则会在一条服务器已经作废的 ws 上白挂 listenIdle 秒，用户此时唤醒
            //  就会「丢字 + 等 8s」——2026-10-04 18:19 实测）。范围 10..120。
            String fareSrc = "default(35)";
            if (files != null) {
                for (java.io.File f : files) {
                    String nm = f.getName();
                    if (nm.startsWith("farewell") && nm.length() > 8) {
                        try {
                            int s = Integer.parseInt(nm.substring(8));
                            if (s >= 10 && s <= 120) {
                                SessionController.setFarewellIdleSec(s);
                                fareSrc = "files/" + nm;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            // v1.3.25（方案 A）：KWS 灵敏度三旋钮。**只影响自建 KWS，不碰上行/ASR**。
            //   files/kwsgain<N>  -> KWS 前置增益 N（1..64，默认 4）
            //   files/kwsthr<N>   -> 命中阈值 N/100（1..100，默认 15 → 0.15）
            //   files/kwsscore<N> -> 关键词加权 N/10（1..100，默认 20 → 2.0）
            // 机理见 KwsEngine.gain 注释：KWS 不做能量归一化，远场轻声 rms≈-68dBFS
            // 落在模型感受野之外；uplinkGain 只管 ASR、不能乱动，所以这里单独放大。
            String kgSrc = "default(" + (int) KwsEngine.gain + ")";
            String ktSrc = "default(" + Math.round(KwsEngine.threshold * 100) + ")";
            String ksSrc = "default(" + Math.round(KwsEngine.score * 10) + ")";
            String kpSrc = "default(" + KwsEngine.maxActivePaths + ")";
            String kbSrc = "default(" + KwsEngine.numTrailingBlanks + ")";
            // v1.3.26：files/kwsdump -> 把喂给 KWS 的每一帧音频落盘（离线复算模型打分）。
            KwsEngine.dumpEnabled = new java.io.File(getFilesDir(), "kwsdump").exists();
            if (files != null) {
                for (java.io.File f : files) {
                    String nm = f.getName();
                    try {
                        if (nm.startsWith("kwsgain") && nm.length() > 7) {
                            int v = Integer.parseInt(nm.substring(7));
                            KwsEngine.setGain(v);
                            kgSrc = "files/" + nm;
                        } else if (nm.startsWith("kwsthr") && nm.length() > 6) {
                            int v = Integer.parseInt(nm.substring(6));
                            KwsEngine.setThreshold(v / 100.0f);
                            ktSrc = "files/" + nm;
                        } else if (nm.startsWith("kwsscore") && nm.length() > 8) {
                            int v = Integer.parseInt(nm.substring(8));
                            KwsEngine.setScore(v / 10.0f);
                            ksSrc = "files/" + nm;
                        } else if (nm.startsWith("kwspaths") && nm.length() > 8) {
                            int v = Integer.parseInt(nm.substring(8));
                            KwsEngine.setMaxActivePaths(v);
                            kpSrc = "files/" + nm;
                        } else if (nm.startsWith("kwsblank") && nm.length() > 8) {
                            int v = Integer.parseInt(nm.substring(8));
                            KwsEngine.setNumTrailingBlanks(v);
                            kbSrc = "files/" + nm;
                        } else if (nm.startsWith("kwsthreads") && nm.length() > 10) {
                            // v1.3.36：ORT 会话线程数（1..4，默认 1）。默认降到 1 是因为
                            // ORT intra-op 线程池每帧 Run 完会自旋等下一帧 —— 实测 3 个
                            // R1KwsInit 线程合计 145.7%，而真实算力只有 20~40%。
                            int v = Integer.parseInt(nm.substring(10));
                            if (v >= 1 && v <= 4) {
                                KwsEngine.numThreads = v;
                                ktSrc = ktSrc + "+threads=files/" + nm;
                            }
                        } else if (nm.startsWith("uninap") && nm.length() > 6) {
                            // v1.3.36：uni 读线程空返回退避毫秒（0..50，默认 4，0=旧行为）。
                            int v = Integer.parseInt(nm.substring(6));
                            if (v >= 0 && v <= 50) {
                                AudioCapture.uniIdleBackoffMs = v;
                            }
                        } else if (nm.startsWith("unipace") && nm.length() > 7) {
                            // v1.3.37：uni 读线程主动配速（B/s，0=关，默认 64000）。
                            // readData 凑满才返回、等待期在用户态自旋 → 读前按生产者
                            // 速率睡够，把自旋变成真 sleep。
                            int v = Integer.parseInt(nm.substring(7));
                            if (v >= 0 && v <= 512000) {
                                AudioCapture.uniPaceRate = v;
                            }
                        } else if (nm.equals("kwsgateoff")) {
                            // v1.3.39：完全关掉静音门控（退回「每帧都喂模型」）
                            KwsEngine.gateEnabled = false;
                        } else if (nm.equals("kwssessoff")) {
                            // v1.3.45：关掉**所有** KWS 暂停（TTS 期也照喂）—— 最保守
                            KwsEngine.pauseInTts = false;
                            KwsEngine.pauseInSession = false;
                        } else if (nm.equals("kwssessall")) {
                            // v1.3.45：A/B —— 恢复 v1.3.44 的「会话全程暂停」（默认关）
                            KwsEngine.pauseInSession = true;
                            KwsEngine.pauseInTts = true;
                        } else if (nm.equals("kwssesstts")) {
                            // v1.3.45：显式回到默认「只 TTS 期暂停」
                            KwsEngine.pauseInTts = true;
                            KwsEngine.pauseInSession = false;
                        } else if (nm.equals("kwssessforce")) {
                            // v1.3.44 遗产：等价 kwssessall
                            KwsEngine.pauseInSession = true;
                        } else if (nm.startsWith("kwsgatemargin") && nm.length() > 13) {
                            // v1.3.39：静音门控触发余量（dB，1..30，默认 6）——开门余量别名
                            int v = Integer.parseInt(nm.substring(13));
                            if (v >= 1 && v <= 30) {
                                KwsEngine.gateOpenDb = v;
                                KwsEngine.gateMarginDb = v;
                            }
                        } else if (nm.startsWith("kwsgateopen") && nm.length() > 11) {
                            // v1.3.39：开门余量 dB（1..30，默认 6）
                            int v = Integer.parseInt(nm.substring(11));
                            if (v >= 1 && v <= 30) {
                                KwsEngine.gateOpenDb = v;
                            }
                        } else if (nm.startsWith("kwsgateclose") && nm.length() > 12) {
                            // v1.3.39：关门余量 dB（0..30，默认 2）——与开门余量拉开 = 滞回
                            int v = Integer.parseInt(nm.substring(12));
                            if (v >= 0 && v <= 30) {
                                KwsEngine.gateCloseDb = v;
                            }
                        } else if (nm.startsWith("kwsgatehold") && nm.length() > 11) {
                            // v1.3.39：连续安静多少帧后关门（1..200，默认 12 帧=720ms）
                            int v = Integer.parseInt(nm.substring(11));
                            if (v >= 1 && v <= 200) {
                                KwsEngine.gateHoldFrames = v;
                            }
                        } else if (nm.startsWith("kwsgatepre") && nm.length() > 10) {
                            // v1.3.43：开门回灌前置帧数（0..20，默认 12 帧=720ms）
                            int v = Integer.parseInt(nm.substring(10));
                            if (v >= 0 && v <= 20) {
                                KwsEngine.gatePreFrames = v;
                            }
                        } else if (nm.equals("kwsttspause")) {
                            // v1.3.46：回退 —— TTS 播放期暂停 KWS（v1.3.45 行为，省 CPU
                            // 但失去「喊唤醒词打断」能力）
                            KwsEngine.pauseInTts = true;
                        } else if (nm.equals("kwsttson")) {
                            // v1.3.46：默认 —— TTS 期保持 KWS 运行（可用唤醒词打断）
                            KwsEngine.pauseInTts = false;
                        } else if (nm.startsWith("kwsgatesustain") && nm.length() > 14) {
                            // v1.3.46：持续开门多少帧后底噪转慢跟踪（1..600，默认 50 帧=3s）。
                            // 调小 = 更快掐掉持续噪声（更省 CPU，但长句人声可能被切）；
                            // 调大 = 更宽容（噪声下 CPU 更高）。
                            int v = Integer.parseInt(nm.substring(14));
                            if (v >= 1 && v <= 600) {
                                KwsEngine.gateSustainFrames = v;
                            }
                        } else if (nm.equals("echogateoff")) {
                            // v1.3.47：关掉上行回声闸门（回退到「时间到了就放行」）。
                            // 调试/对照用 —— 关掉会立刻复现自环，勿常态开。
                            SessionController.echoGateEnabled = false;
                        } else if (nm.equals("halfduplex")) {
                            // v1.3.50：**严格静音** —— TTS 结束后也不恢复上行，必须再喊一次
                            // 唤醒词。默认**关**（TTS 后自动续听，用户可直接接话）。
                            SessionController.halfDuplex = true;
                        } else if (nm.equals("halfduplex_off")) {
                            // 关掉严格静音 = 恢复 v1.3.48/v1.3.50 默认行为（TTS 后自动续听）。
                            SessionController.halfDuplex = false;
                        } else if (nm.startsWith("echorms") && nm.length() > 7) {
                            // v1.3.47：回声电平门限（300..20000，默认 8000）
                            int v = Integer.parseInt(nm.substring(7));
                            if (v >= 300 && v <= 20000) {
                                SessionController.echoRms = v;
                            }
                        } else if (nm.startsWith("echoframes") && nm.length() > 10) {
                            // v1.3.47：静默确认帧数（1..40，默认 8 帧=480ms）
                            int v = Integer.parseInt(nm.substring(10));
                            if (v >= 1 && v <= 40) {
                                SessionController.echoClearFrames = v;
                            }
                        } else if (nm.startsWith("echomaxms") && nm.length() > 9) {
                            // v1.3.47：闸门最长压住时长（200..8000，默认 4000ms）
                            int v = Integer.parseInt(nm.substring(9));
                            if (v >= 200 && v <= 8000) {
                                SessionController.echoGateMaxMs = v;
                            }
                        } else if (nm.startsWith("echoholdms") && nm.length() > 10) {
                            // v1.3.48：闸门**最小强制压制期**（0..8000，默认 1200ms）。
                            // TTS stop 后先硬压这么久，挡掉"播放期假安静"骗开闸门
                            // （见 SessionController.echoGateHoldMs 注释）。0 = 关闭。
                            int v = Integer.parseInt(nm.substring(10));
                            if (v >= 0 && v <= 8000) {
                                SessionController.echoGateHoldMs = v;
                            }
                        } else if (nm.startsWith("ttshold") && nm.length() > 7) {
                            // v1.3.48：TTS 数据发完后续写静音的时长（0..15000，默认
                            // 4000ms）。保持播放流活跃 → 钉住 HAL 回声抑制。
                            // 0 = 关（回退 v1.3.47）。见 TtsPlayer.holdSilenceMs 注释。
                            int v = Integer.parseInt(nm.substring(7));
                            if (v >= 0 && v <= 15000) {
                                TtsPlayer.holdSilenceMs = v;
                            }
                        } else if (nm.startsWith("tsbuf") && nm.length() > 5) {
                            // ldyb5：TTS 起播水位（0..2000ms），v0.3.58 起默认
                            // 600（TtsPlayer.startWatermarkMs）。官方服务器走公网，
                            // 偶发 405~536ms 迟到批次，600 实测能盖住且真实播放
                            // 全部 mid=0；NAS 局域网抖动小可用 tsbuf180~250。
                            // v0.3.56：0 = 完全不做软件预缓冲（第一帧到达即写
                            // AudioTrack），实验用；AudioTrack 自身 1s 硬件缓冲
                            // 仍在，系统层无法归零。startWatermarkMs 是静态字段，
                            // 同进程内删 flag 重启服务不会复位，实验后要么显式放
                            // tsbuf600 回写、要么杀进程重启（进程重启即取默认 600）。
                            int v = Integer.parseInt(nm.substring(5));
                            if (v >= 0 && v <= 2000) {
                                TtsPlayer.startWatermarkMs = v;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            String lc = "conv cfg [v0.3.37]: listenIdle=" + (idleMs / 1000) + "s (" + idleSrc
                    + ") farewell=" + SessionController.getFarewellIdleSec() + "s ("
                    + fareSrc + ")"
                    + " kwsInterrupt=" + AppSettings.kwsInterrupt(this)
                    + " prewake=" + SessionController.getPrewakeFrames() + "f ("
                    + preSrc + ")"
                    + " wakeProbe=" + SessionController.getWakeProbeSec() + "s ("
                    + probeSrc + ")"
                    + " wakeAck=" + SessionController.wakeAckEnabled
                    + " ackDrop=" + SessionController.getWakeAckEchoDropMs() + "ms ("
                    + ackDropSrc + ")"
                    + " srvListen=" + SessionController.serverListenMode
                    + " kwsGain=" + KwsEngine.gain + " (" + kgSrc + ")"
                    + " kwsThr=" + KwsEngine.threshold + " (" + ktSrc + ")"
                    + " kwsScore=" + KwsEngine.score + " (" + ksSrc + ")"
                    + " kwsPaths=" + KwsEngine.maxActivePaths + " (" + kpSrc + ")"
                    + " kwsBlank=" + KwsEngine.numTrailingBlanks + " (" + kbSrc + ")"
                    + " kwsThreads=" + KwsEngine.numThreads
                    + " uniNap=" + AudioCapture.uniIdleBackoffMs + "ms"
                    + " uniPace=" + AudioCapture.uniPaceRate + "B/s"
                    + " kwsGate=" + (KwsEngine.gateEnabled
                        ? ("open" + KwsEngine.gateOpenDb + "dB/close" + KwsEngine.gateCloseDb
                           + "dB/hold" + KwsEngine.gateHoldFrames
                           + "f/pre" + KwsEngine.gatePreFrames + "f"
                           + "/sus" + KwsEngine.gateSustainFrames + "f")
                        : "off")
                    + " kwsSess=" + (KwsEngine.pauseInSession ? "all"
                            : (KwsEngine.pauseInTts ? "tts" : "none"))
                    + " kwsDump=" + KwsEngine.dumpEnabled
                    + " echoGate=" + (SessionController.echoGateEnabled
                        ? (SessionController.echoRms + "/" + SessionController.echoClearFrames
                           + "f/hold" + SessionController.echoGateHoldMs
                           + "ms/max" + SessionController.echoGateMaxMs + "ms")
                        : "off")
                    + " ttsHold=" + TtsPlayer.holdSilenceMs + "ms"
                    + " halfDuplex=" + SessionController.halfDuplex
                    + " (flags: idle_alert_off="
                    + new java.io.File(getFilesDir(), "idle_alert_off").exists()
                    + " wakeack_off="
                    + new java.io.File(getFilesDir(), "wakeack_off").exists()
                    + " detect_on="
                    + new java.io.File(getFilesDir(), "detect_on").exists()
                    + " bargein_off="
                    + new java.io.File(getFilesDir(), "bargein_off").exists()
                    + " echogate_off="
                    + new java.io.File(getFilesDir(), "echogateoff").exists() + ")";
            Log.i(TAG, lc);
            SessionLog.i(lc);
        } catch (Throwable t) {
            Log.w(TAG, "conv cfg flag read failed", t);
        }

        // ---- 5) 麦克风：v1.2.4 起不再常开！----
        // 实测（2026-10-03）：4 麦 HAL 独占，我们常开 AudioRecord 会把原厂
        // 云知声 KWS 挤瞎（「小讯小讯」无反应）；我们一放手它 1s 内满血复活。
        // 麦是会话期资源：bus 唤醒事件 -> SessionController 快速重试开麦 ->
        // 会话结束 release 还麦。IDLE 期设备归属原厂。
        mic.setFrameListener(session);
        Log.i(TAG, "mic DEFERRED (session-on-demand; unisound KWS owns idle mic)");

        Log.i(TAG, "=========== Ldyb5ClientService ready (mic=ondemand"
                + " tts=" + (player != null) + " kws=off bus=" + (wakeup != null)
                + " opus=" + (encOk && decOk) + ") ===========");

        // ---- ldyb5：本地电台播放器（radio.json 导入）+ 屏幕 UI ----
        if (MusicPlayer.I == null) {
            MusicPlayer mp = new MusicPlayer(this);
            mp.loadRadiosAsync();   // 后台加载电台 JSON，成功后 ScreenBus.musicReady=true
            Log.i(TAG, "radio player created, loading radio.json");
        }
        // ---- ldyb5：酷我车机版语音桥（状态接收 + MCP self_kuwo_*）----
        KuwoController.init(this);
        // ---- ldyb5 v0.3.47：电视直播语音控制（MCP self_tv_*，纯 HTTP 读缓存）----
        TvController.init(this);
        // ---- ldyb5 v0.3.50：设备 / 程序控制（MCP self_screen_* / self_exit_app / self_go_home）----
        DeviceController.init(this);
        if (!new java.io.File(getFilesDir(), "screenui_off").exists()) {
            try {
                java.lang.Process p = Runtime.getRuntime().exec(
                        new String[]{"am", "start", "-n",
                                "cn.workbuddy.ldyb5app/.ScreenUI"});
                p.waitFor();
                Log.i(TAG, "ScreenUI launched");
            } catch (Throwable t) {
                Log.w(TAG, "ScreenUI launch failed", t);
            }
        }

        // ---- 6) 降级重试（v1.2.4 起只管 TTS；mic 由 SessionController 自管）----
        if (player == null) {
            scheduleAudioRetry();
        }

        // v1.3.0：成套产物（sherpa-onnx v1.13.4 static-link，ORT 已链入 jni.so）
        // + int8 模型就位，KWS 恢复启用。初始化成功后自动把麦切成常驻。
        // v1.3.4：文件开关 A/B —— files/alwayson_off 存在则跳过 KWS+常驻麦，
        // 退回 v1.2.17 按需模式（bus 唤醒）。用于隔离「常驻抢麦 -> 音频垃圾」假设。
        // v1.3.7：探针矩阵 —— files/micprobe 存在则跑麦克风配置探针（替代 KWS），
        // 用于定位「我们开流拿底噪、USC 开流干净」的 AudioSource 路由差异。
        if (micProbeRequested()) {
            Log.i(TAG, "micprobe flag file detected -> running mic config probe matrix");
            SessionLog.i("micprobe flag file detected -> running mic config probe matrix");
            MicProbe.runAsync(getFilesDir());
            notifyRestartReady();
        } else if (WAKEUP_MODE != MODE_BUS_ONLY && !alwaysonDisabled()) {
            // 走 KWS 时由 startKwsAsync() 在引擎 init 返回后再通知（那才是灵敏度真正生效点）
            startKwsAsync();
        } else if (alwaysonDisabled()) {
            Log.i(TAG, "always-on/KWS DISABLED by flag file (alwayson_off) -> bus-only, mic on-demand");
            SessionLog.i("always-on/KWS disabled by flag file -> bus-only, mic on-demand");
            notifyRestartReady();
        } else {
            notifyRestartReady();
        }
    }

    /**
     * v0.3.53：一次性消费「服务已重启完成」通知。回调统一 post 到主线程，
     * 由设置页弹 toast；pending 标志保证只弹一次、且只在设置页主动重启时弹。
     */
    private void notifyRestartReady() {
        if (!restartNotifyPending) {
            return;
        }
        restartNotifyPending = false;
        final Runnable r = onRestartReady;
        if (r == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** v1.3.4：A/B 开关 —— files/alwayson_off 存在 = 常驻麦/KWS 禁用 */
    private boolean alwaysonDisabled() {
        try {
            return new java.io.File(getFilesDir(), "alwayson_off").exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.3.7：files/micprobe 存在 = 启动时跑麦克风配置探针矩阵 */
    private boolean micProbeRequested() {
        try {
            return new java.io.File(getFilesDir(), "micprobe").exists();
        } catch (Throwable t) {
            return false;
        }
    }

    // ================= 服务器热重连（v0.3.3） =================

    /**
     * 设置里改了服务器（模式/地址/token）后热重连，不再要求重启服务：
     *   1. 收掉当前连接（ws.close -> onClosed -> enterIdle 归还麦等资源）；
     *   2. 手动 token 先挂上；
     *   3. 新建 OtaClient（构造时按最新设置解析 OTA 地址）重打 OTA，
     *      onDone（新地址/token 就绪）后再 connect，避免拿旧地址抢跑。
     */
    private void reconnectServer() {
        new Thread(new Runnable() {
            public void run() {
                Log.i(TAG, "server settings changed -> hot reconnect");
                SessionLog.i("server settings changed -> hot reconnect");
                ws.close("server-switch");
                String ct = AppSettings.tokenCustom(Ldyb5ClientService.this);
                if (ct != null && ct.length() > 0) {
                    ws.setToken(ct);
                }
                ota = new OtaClient(Ldyb5ClientService.this, new OtaClient.Callback() {
                    public void onToken(String token) {
                        ws.setToken(token);
                    }

                    public void onWsUrl(String url) {
                        ws.setUrl(url);
                    }

                    public void onFail(String msg) {
                        Log.w(TAG, "OTA failed (hot switch): " + msg);
                        SessionLog.i("OTA failed (hot switch): " + msg);
                    }

                    public void onDone() {
                        mainHandler.post(new Runnable() {
                            public void run() {
                                Log.i(TAG, "hot switch: reconnecting ws");
                                SessionLog.i("hot switch: reconnecting ws");
                                ws.connect();
                            }
                        });
                    }
                });
                ota.fetch();
            }
        }, "srv-switch").start();
    }

    // ================= 音频看门狗 =================

    /**
     * TTS 初始化，带看门狗。
     * 超时后放弃等待（泄漏的 daemon 线程留在 binder 里，无害），
     * player 保持 null —— 若泄漏线程最终完成（HAL 恢复），会自行补挂到 session。
     */
    private void ensureTts() {
        if (!ttsAttemptDone.compareAndSet(true, false)) {
            Log.w(TAG, "tts init still in flight, skip");
            return;
        }
        final AtomicBoolean ok = new AtomicBoolean(false);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    TtsPlayer p = new TtsPlayer();
                    p.start();
                    if (p.isStarted()) {
                        player = p;
                        ok.set(true);
                        // 迟到补挂：session 可能已建好（重试场景）
                        if (session != null) {
                            session.setPlayer(p);
                        }
                    }
                } catch (Throwable tr) {
                    Log.e(TAG, "TtsPlayer.start threw", tr);
                } finally {
                    ttsAttemptDone.set(true);
                }
            }
        }, "tts-init-wd");
        t.setDaemon(true);
        t.start();

        long t0 = SystemClock.elapsedRealtime();
        // Thread.join(long) 返回 void（没有 boolean 版本），超时要靠 isAlive() 判断
        try {
            t.join(AUDIO_WATCHDOG_MS);
        } catch (InterruptedException ignored) {
        }
        if (t.isAlive()) {   // join 超时 = 还卡着
            Log.e(TAG, "tts-init TIMEOUT after " + AUDIO_WATCHDOG_MS
                    + "ms (audio HAL wedged) -> DEGRADED: no TTS yet");
            probeAudioSystem("tts-timeout");
            return;
        }
        Log.i(TAG, "tts init ok=" + ok.get() + " (" + (SystemClock.elapsedRealtime() - t0) + "ms)");
    }

    /**
     * HAL 卡死时取证据：getMinBufferSize 现在到底还卡不卡。
     * 打出数值 = HAL 已恢复；再超时 = 仍死锁（可重启 mediaserver 验证）。
     */
    private void probeAudioSystem(final String why) {
        final AtomicBoolean done = new AtomicBoolean(false);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    int out = AudioTrack.getMinBufferSize(16000,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    Log.i(TAG, "probe[" + why + "]: getMinBufferSize(out)=" + out);
                    int in = AudioRecord.getMinBufferSize(16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    Log.i(TAG, "probe[" + why + "]: getMinBufferSize(in)=" + in);
                } catch (Throwable tr) {
                    Log.e(TAG, "probe[" + why + "] threw", tr);
                } finally {
                    done.set(true);
                }
            }
        }, "audio-probe");
        t.setDaemon(true);
        t.start();
        try {
            t.join(2000);
        } catch (InterruptedException ignored) {
        }
        if (t.isAlive()) {
            Log.e(TAG, "probe[" + why + "] TIMEOUT: audio HAL still wedged");
        }
    }

    // ================= 降级重试 =================

    private void scheduleAudioRetry() {
        long delay = RETRY_BACKOFF_MS[Math.min(audioRetryCount, RETRY_BACKOFF_MS.length - 1)];
        audioRetryCount++;
        Log.i(TAG, "audio retry #" + audioRetryCount + " scheduled in " + delay + "ms");
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                // 看门狗 join 会阻塞最长 2.5s —— 不能在 main 线程跑，丢工作线程
                new Thread(new Runnable() {
                    public void run() {
                        boolean stillDegraded = false;

                        if (player == null) {
                            if (ttsAttemptDone.get()) {
                                Log.i(TAG, "retry: re-trying TTS init");
                                ensureTts();
                                if (player == null) stillDegraded = true;
                            } else {
                                stillDegraded = true;   // 上次尝试还卡在 binder 里
                            }
                        }
                        // v1.2.4：mic 不再参与启动重试 —— 它是会话期资源，
                        // 由 SessionController 在每次唤醒时自行开麦/重试/归还。

                        if (stillDegraded) {
                            scheduleAudioRetry();
                        } else {
                            Log.i(TAG, "audio retry: RECOVERED (tts=" + (player != null) + ")");
                        }
                    }
                }, "audio-retry").start();
            }
        }, delay);
    }

    // ================= 心跳 =================

    /** v1.3.30：采集线程卡死判定阈值（正常 60ms 一帧，20s 无帧 = 必死） */
    private static final long WD_STALL_MS = 20000L;

    /** 每 10s 打一行活体状态。连续出现 = 服务活着（v1.2.2 连第一行都到不了） */
    private void startLivenessTick() {
        mainHandler.postDelayed(new Runnable() {
            private int n = 0;
            public void run() {
                n++;
                Log.i(TAG, "alive " + (n * 10) + "s (tts=" + (player != null)
                        + " mic=" + (mic != null && mic.isRunning())
                        + " sess=" + (session != null ? session.getState().name() : "-")
                        + " bus=" + (wakeup != null) + ")");
                // v1.3.30：采集线程卡死看门狗。mic 在跑但 20s 无新帧 = 线程卡死
                // （进程活着、设备全聋，lmkd 不杀、sticky 不重启 —— 15:12:48 实锤
                // 卡了 13 分钟无人救）。处决进程让 sticky restart 拉起自愈。
                if (mic != null && mic.isRunning()) {
                    long stall = SystemClock.elapsedRealtime() - AudioCapture.lastLoopFrameMs;
                    if (stall > WD_STALL_MS) {
                        String msg = "WD: capture thread stalled " + (stall / 1000)
                                + "s (isRunning=true) -> kill self, sticky restart takes over";
                        Log.e(TAG, msg);
                        SessionLog.i(msg);
                        Process.killProcess(Process.myPid());
                        return;
                    }
                }
                mainHandler.postDelayed(this, 10000);
            }
        }, 10000);
    }

    // ================= KWS（v1.3.0 恢复启用） =================

    /**
     * 异步初始化 KWS。
     * v1.3.0：原生库换成 sherpa-onnx v1.13.4 static-link 版（ORT 静态链入 jni.so，
     * DT_HASH/minSdk21 体检通过），v1.1.x 的 assets 路径陷阱也早已修复
     * （KwsEngine 用 assets 相对路径）—— ABI 配对与路径两颗雷均拆除。
     *
     * 初始化成功 → setKws + 麦克风切常驻（独立唤醒的前提：IDLE 期我们持麦，
     * 自建 KWS 才有音频可听；代价是原厂引擎断粮，bus 唤醒随之失效）。
     * 初始化失败 → setKws(null)，麦保持 on-demand，bus 唤醒兜底（v1.2.4 行为）。
     */
    private void startKwsAsync() {
        final Handler h = new Handler(Looper.getMainLooper());
        // v0.3.25：KWS 初始化超时兜底 —— 若 native 层 kws.init() 卡死（永不正常
        // 返回），界面会永远停在「唤醒引擎启动中」，用户以为应用坏了。
        // 超时后按「引擎不可用」处理：界面回「待唤醒」（实际靠 bus 唤醒兜底）。
        // v0.3.25：init 是否已返回（超时兜底用）。用数组是因为要跨线程共享。
        final boolean[] initReturned = new boolean[]{false};
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                // v0.3.25：只在 init **还没返回**时才算超时（init 成功但慢于
                // 20s 的情况不能误杀 —— 那种情况 initReturned 已为 true）。
                if (!initReturned[0]) {
                    initReturned[0] = true;
                    Log.w(TAG, "kws init timeout (20s) -> treat engine as unavailable");
                    SessionLog.i("kws init timeout -> engine unavailable");
                    if (session != null) {
                        session.setKws(null);
                    }
                    // v0.3.53：init 卡死也别让设置页永远停在「正在重启」
                    notifyRestartReady();
                }
            }
        }, 20000L);
        // v1.3.30：唤醒处理专用 HandlerThread（单线程串行，保持唤醒语义有序）
        HandlerThread wakeThread = new HandlerThread("kws-wake");
        wakeThread.start();
        kwsWakeHandler = new Handler(wakeThread.getLooper());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final long t0 = SystemClock.elapsedRealtime();
                boolean ok = false;
                try {
                    kws = new KwsEngine(Ldyb5ClientService.this, new KwsEngine.OnKeyword() {
                        public void onKeyword(final String kw) {
                            // v1.3.30：post 到 kws-wake 线程 —— 采集线程只喂 KWS。
                            // handleWakeup（ack 播放 + startSession）卡死也不再拖垮
                            // 采集链；即便卡死，看门狗也会把进程拉回来。
                            // 线程安全性：handleWakeup 本就被 bus/main 多线程调用，
                            // preWake 生产/消费均已 synchronized。
                            kwsWakeHandler.post(new Runnable() {
                                public void run() {
                                    if (session != null) {
                                        session.onKeyword(kw);
                                    }
                                }
                            });
                        }
                    });
                    ok = kws.init();
                } catch (Throwable e) {
                    Log.e(TAG, "kws init threw (java-level)", e);
                }
                final boolean okF = ok;
                Log.i(TAG, "kws init=" + okF + " (" + (SystemClock.elapsedRealtime() - t0) + "ms)");
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        // v0.3.25：init 已正常返回 → 撤销超时兜底
                        initReturned[0] = true;
                        if (session != null) {
                            session.setKws(okF ? kws : null);
                            if (okF) {
                                // v1.3.0：KWS 就绪 → 麦常驻，IDLE 期喂 KWS（独立唤醒）
                                session.startAlwaysOnMic();
                            }
                        }
                        Log.i(TAG, "kws wired to session: " + okF
                                + (okF ? " (mic -> always-on)" : " (mic stays on-demand, bus fallback)"));
                        // v0.3.53：新 KeywordSpotter 已按新 threshold/score 建好并挂到会话上
                        notifyRestartReady();
                    }
                });
            }
        }, "R1KwsInit");
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // v0.3.47：本地调试入口 —— 便于用 adb 直接验证电视指令，不必真的说话：
        //   adb shell am startservice -n cn.workbuddy.ldyb5app/.Ldyb5ClientService \
        //       --es tv_tool self_tv_play --es tv_query CCTV5
        // 结果写 logcat（TAG L5CLIENT-TVDBG）。正常启动不带这两个 extra，零影响。
        if (intent != null) {
            final String tool = intent.getStringExtra("tv_tool");
            if (tool != null && tool.startsWith("self_tv_")) {
                final String q = intent.getStringExtra("tv_query");
                new Thread(new Runnable() {
                    public void run() {
                        TvController.init(getApplicationContext());
                        String r = TvController.handle(tool, q == null ? "" : q);
                        Log.i("L5CLIENT-TVDBG", tool + "(" + q + ") -> " + r);
                        SessionLog.i("tv debug -> " + r);
                    }
                }, "ldyb5-tv-debug").start();
            }
            // v0.3.50：本地调试入口 —— 设备控制类工具，结果写 logcat（TAG L5CLIENT-DEVDBG）
            //   adb shell am startservice -n cn.workbuddy.ldyb5app/.Ldyb5ClientService \
            //       --es dev_tool self_screen_stay_on
            final String devTool = intent.getStringExtra("dev_tool");
            if (devTool != null && DeviceController.isDeviceTool(devTool)) {
                final String dq = intent.getStringExtra("dev_query");
                final String dp = intent.getStringExtra("dev_pct");
                final String don = intent.getStringExtra("dev_on");
                new Thread(new Runnable() {
                    public void run() {
                        DeviceController.init(getApplicationContext());
                        String r = DeviceController.handle(devTool,
                                dq == null ? "" : dq,
                                dp == null ? "" : dp,
                                don == null ? "true" : don);
                        Log.i("L5CLIENT-DEVDBG", devTool + "(" + dq + "/" + dp + ") -> " + r);
                        SessionLog.i("dev debug -> " + r);
                    }
                }, "ldyb5-dev-debug").start();
            }
            // v0.3.52：TTS 卡顿诊断探针 —— 用静音 PCM 模拟一轮服务器 TTS 流，
            // 走完整播放链路但不出声，可重复量测句中补静音。结果看
            // logcat / ldyb5app.log 的 "TTS drained (... mid=... tail=...)"。
            //   adb shell am startservice -n cn.workbuddy.ldyb5app/.Ldyb5ClientService \
            //       --es tts_sim 60 --es sim_jitter 200
            final String sim = intent.getStringExtra("tts_sim");
            if (sim != null) {
                try {
                    int n = Math.max(1, Math.min(300, Integer.parseInt(sim)));
                    int j = 0;
                    String js = intent.getStringExtra("sim_jitter");
                    if (js != null) {
                        j = Math.max(0, Math.min(1000, Integer.parseInt(js)));
                    }
                    final int frames = n;
                    final int jitter = j;
                    if (session != null) {
                        session.debugTtsSim(frames, jitter);
                        Log.i("L5CLIENT-TTSDBG", "tts_sim frames=" + frames
                                + " jitter=" + jitter + "ms");
                        SessionLog.i("tts_sim frames=" + frames
                                + " jitter=" + jitter + "ms");
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // 被系统 kill 后 START_STICKY 会重启服务，此时 onCreate 已重建管线，
        // 这里只需确保前台通知还在，不要重复初始化。
        if (started) {
            Log.i(TAG, "onStartCommand: already started, idempotent no-op");
            return START_STICKY;
        }
        started = true;
        Log.i(TAG, "onStartCommand: first start");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Ldyb5ClientService onDestroy");
        // v0.3.9：先摘悬浮窗再拆音频，防退出后图标残留
        IslandOverlay.stop();
        unregisterVolumeReceiver();
        mainHandler.removeCallbacksAndMessages(null);
        if (mic != null) mic.stop();
        if (session != null) session.release(); // v1.2.15：解除媒体流静音兜底
        if (kws != null) kws.release();
        if (wakeup != null) wakeup.unregister();
        if (opus != null) {
            opus.closeEncoder();
            opus.closeDecoder();
        }
        if (player != null) player.stop();
        if (ws != null) ws.close("service-destroy");
        super.onDestroy();
    }

    // v0.3.65：全局音量条触发（固件无系统音量面板）。
    // 监听 VOLUME_CHANGED_ACTION：系统在任何界面（小智内/外）按音量键改了媒体音量都会发此广播，
    // 据此弹出与「小智头像」同款的全局悬浮窗音量条。MediaSession.onVolumeKeyEvent 在本机固件不触发，
    // 故改用广播——系统改音量时广播必到，且天然覆盖小智界面内外。
    private void registerVolumeReceiver() {
        try {
            volBr = new BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    // 编译桩 android.jar(API21) 缺 EXTRA_VOLUME_STREAM_TYPE / ACTION_VOLUME_CHANGED 符号，
                    // 用其稳定字面值（各 API 级一致）绕过；运行时 API27 的广播即用这些 key。
                    int stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                    // 仅对媒体音量（小智/电视直播等走 STREAM_MUSIC）弹条，避免无关流误触
                    if (stream == AudioManager.STREAM_MUSIC) {
                        VolBarOverlay.show(Ldyb5ClientService.this);
                    }
                }
            };
            IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            registerReceiver(volBr, f);
            Log.i(TAG, "volume receiver attached (global volbar via VOLUME_CHANGED_ACTION)");
        } catch (Throwable t) {
            Log.w(TAG, "volume receiver failed: " + t);
        }
    }

    private void unregisterVolumeReceiver() {
        if (volBr != null) {
            try {
                unregisterReceiver(volBr);
            } catch (Throwable ignored) {
            }
            volBr = null;
        }
    }

    /**
     * 前台通知。Android 5.1 用 startForeground，低版本 Notification 用旧构造方式
     * （勿用 NotificationChannel，那是 API 26+）。
     */
    @SuppressWarnings("deprecation")
    private void startForegroundSafely() {
        try {
            Intent i = new Intent(this, Ldyb5ClientService.class);
            // FLAG_IMMUTABLE 是 API23+ 常量（值为 0x04000000）；
            // API16 stub 编译期不可见，这里直接用字面量，运行期在 R1(API22) 上等同于 0。
            int flag = PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000;
            PendingIntent pi = PendingIntent.getService(this, 0, i, flag);

            Notification n = new Notification.Builder(this)
                    .setContentTitle("小智")
                    .setContentText("语音助手运行中（说「小讯小讯」唤醒）")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            startForeground(NOTI_ID, n);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
        }
    }
}
