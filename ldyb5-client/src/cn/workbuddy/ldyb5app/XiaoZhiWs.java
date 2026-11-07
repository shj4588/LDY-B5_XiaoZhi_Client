package cn.workbuddy.ldyb5app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 小智官方服务器 WebSocket 客户端（按需连接模式）。
 *
 * 关键决策（吸取 C3 主线经验）：
 * 1. 按需连接：空闲时零连接，避免官方服务器 70s/80s 空闲踢出。
 * 2. 上行裸 opus，绝不加 [0x01,0x01] 之类帧头（C3 的 ASR 单字 bug 根因）。
 * 3. TTS stop 后延迟 10s 主动 close（与 S2 official 固件行为一致）。
 */
public final class XiaoZhiWs {

    private static final String TAG = "L5CLIENT";

    public interface Listener {
        void onConnected();

        void onHandshakeDone();

        void onTtsStart();

        void onTtsAudio(byte[] opusFrame);

        void onTtsStop(boolean aborted);

        void onStt(String text);

        void onClosed(String reason);

        void onError(String msg);
    }

    /** 官方服务器（同 C3 固件）。v1.2.5 起可被 OTA 下发的 websocket.url 覆盖 */
    private static final String WS_URL = "wss://api.tenclass.net/xiaozhi/v1/";
    private volatile String wsUrl = WS_URL;

    /**
     * TTS 结束后多久主动断开（毫秒）—— 本地**兜底**值，可被 flag 覆盖。
     * v1.3.12：10s -> 15s。
     * v1.3.15：15s -> 120s（「延长连续对话」）。官方设备并不在本地做空闲计时，
     * 而是由服务器在 listening 空闲 ~70s 下发 TTS 提醒、~80s 关闭会话。
     * 我们把本地兜底放到 120s（远大于服务器 80s），让服务器先接管会话生命周期：
     * 用户听到的是官方的「要断开了」语音提醒，而不是被我们冷不丁断线。
     */
    private static final long IDLE_CLOSE_DELAY_MS = 120_000L;
    /** v1.3.15：运行期覆盖（来自 files/idle<N>），markIdle 时取此值 */
    private volatile long idleCloseDelayMs = IDLE_CLOSE_DELAY_MS;

    /** 重连退避上限 */
    private static final long BACKOFF_MAX_MS = 30_000L;

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private OkHttpClient client;
    // ⚠️ v1.2.8 血泪坑：okhttp 的 newWebSocket() 返回值 / onOpen(webSocket) 参数
    // 必须显式赋给本字段！v1.2.2~v1.2.7 里 ws 永远是 null → sendText/sendAudio
    // 全部静默丢弃 → hello 从未真正发出 → 服务器「沉默」被误判成协议问题一整天。
    // volatile：onOpen 在 okhttp 线程赋值，sendAudio 在录音线程读取。
    private volatile WebSocket ws;
    private final AtomicBoolean open = new AtomicBoolean(false);
    /** v1.2.9：每条连接只记前 2 个二进制帧（排查「服务器用二进制回 hello」） */
    private int binLogged = 0;
    // v1.2.13：上行音频帧计数。NAS 实测握手通了但 25s 无 STT —— 服务器到底
    // 收没收到我们的音频，客户端先自证。volatile ++ 非原子，诊断用途足够
    private volatile int uplinkSent;
    private volatile int uplinkFail;

    private String token;              // Authorization: Bearer <token>
    // v0.3.24：服务器在 hello 响应里分配的 session_id。官方固件每条消息都带它，
    // abort（中断 TTS 播报）尤其需要 —— 服务端据此知道停哪一轮。
    private volatile String sessionId = "";
    // v1.2.7：官方服务器靠这两个头把 ws 连接路由到设备会话。
    // 缺失时升级握手照常成功（token 只管鉴权），但 hello 发出后
    // 服务器无法把连接关联到已绑定设备 → 永不回应 hello（v1.2.6 实测 15s 静默）。
    private volatile String deviceId;
    private volatile String clientId;
    private long reconnectBackoff = 1000L;
    private volatile boolean wantConnected;
    private int idleCloseSeq = 0;

    /**
     * v1.3.19：连接代数。**每次 close() / connect() 自增**，每个 WebSocketListener
     * 在建立时捕获当时的代数；回调进来先比对，代际不符 = 陈旧 socket 的迟到回调，
     * 一律丢弃。
     *
     * 修的是什么（实测 2026-10-04 17:10 连续三次「硬重启」零回复）：
     * hardRestart() → close()（把 ws 置空、open 置 false）→ okhttp 的 onClosed
     * 是**异步**的，等它回到主线程时，enterIdle 已经把新会话 connect() 起来了；
     * 此时旧 socket 的 onClosed 仍会执行 `open.set(false); ws = null;` —— 把
     * **新** socket 的字段砸掉，并因为 wantConnected 已是 true 而再触发一次
     * scheduleReconnect → 新会话被自己人拆掉，服务器侧永远等不到音频。
     * 有了代数之后，旧 socket 的一切回调都失效；close() 由自己投递 onClosed，
     * 时序完全可控。
     */
    private volatile int connGen = 0;

    private final Runnable idleCloseTask = new Runnable() {
        public void run() {
            Log.i(TAG, "idle timeout reached, closing ws (on-demand mode)");
            close("idle-timeout");
        }
    };

    public XiaoZhiWs(Listener l) {
        this.listener = l;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /** v1.2.7：设备标识（MAC），必须与 xiaozhi.me 绑定时的一致 */
    public void setDeviceId(String id) {
        this.deviceId = id;
    }

    /** v1.2.7：客户端 UUID，与 OTA 请求头保持同一个 */
    public void setClientId(String id) {
        this.clientId = id;
    }

    /** v1.2.5：OTA 下发的 ws 地址（下次 connect 生效） */
    public void setUrl(String url) {
        if (url != null && url.length() > 0) {
            this.wsUrl = url;
            Log.i(TAG, "ws url set from OTA: " + url);
        }
    }

    public boolean isOpen() {
        return open.get();
    }

    /** v1.3.15：覆盖空闲断开时长（服务层按 files/idle<N> 传入），markIdle 时生效 */
    public void setIdleCloseDelayMs(long ms) {
        if (ms > 0) {
            this.idleCloseDelayMs = ms;
            Log.i(TAG, "idle close delay = " + ms + "ms");
        }
    }

    // ---------------- 连接管理 ----------------

    public void connect() {
        wantConnected = true;
        if (open.get() || ws != null) {
            Log.i(TAG, "connect() ignored, already connecting/open");
            return;
        }
        main.removeCallbacks(idleCloseTask);

        final Request.Builder rb = new Request.Builder()
                .url(wsUrl)
                .addHeader("User-Agent", "Ldyb5Client/1.0")
                .addHeader("Protocol-Version", "1");
        if (token != null && token.length() > 0) {
            rb.addHeader("Authorization", "Bearer " + token);
        }
        // v1.2.7：补齐 ESP32 固件同款握手头 —— 官方服务器以此路由设备会话，
        // 缺了任何一组，升级握手成功但 hello 永远无人回应。
        if (deviceId != null && deviceId.length() > 0) {
            rb.addHeader("Device-Id", deviceId);
        }
        if (clientId != null && clientId.length() > 0) {
            rb.addHeader("Client-Id", clientId);
        }

        final int gen = ++connGen;   // v1.3.19：本条连接的代数，回调进来先校验
        Log.i(TAG, "connecting " + wsUrl);
        SessionLog.i("connecting " + wsUrl);
        getClient().newWebSocket(rb.build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (gen != connGen) {
                    // v1.3.19：陈旧连接迟到的 onOpen —— 直接关掉，绝不污染 ws 字段
                    try {
                        webSocket.close(1000, "stale");
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                ws = webSocket;   // v1.2.8：先持有 socket，否则 sendHello/sendAudio 全部落空
                open.set(true);
                reconnectBackoff = 1000L;
                binLogged = 0;
                uplinkSent = 0;
                uplinkFail = 0;
                Log.i(TAG, "ws open");
                SessionLog.i("ws open");
                sendHello();
                main.post(new Runnable() {
                    public void run() {
                        listener.onConnected();
                    }
                });
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (gen != connGen) {
                    return;   // v1.3.19：陈旧连接的行一律丢弃
                }
                handleText(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (gen != connGen) {
                    return;   // v1.3.19：陈旧连接的下行音频一律丢弃
                }
                // 服务器下发的音频：裸 opus。v1.2.9：只记前 2 帧防刷屏 ——
                // 文本帧有 <- 日志，二进制帧此前完全隐身，无法排除服务器用
                // 二进制回 hello 的可能
                if (binLogged < 2) {
                    binLogged++;
                    Log.i(TAG, "<- bin#" + binLogged + " " + bytes.size() + "B");
                    SessionLog.i("<- bin#" + binLogged + " " + bytes.size() + "B");
                }
                final byte[] data = bytes.toByteArray();
                main.post(new Runnable() {
                    public void run() {
                        listener.onTtsAudio(data);
                    }
                });
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (gen != connGen) {
                    return;   // v1.3.19：陈旧连接的失败回调 —— 不能掐掉新会话
                }
                open.set(false);
                ws = null;
                SessionLog.i("uplink summary: sent=" + uplinkSent + " fail=" + uplinkFail);
                String code = (response != null) ? ("http=" + response.code()) : "";
                Log.e(TAG, "ws failure " + code + " : " + t);
                SessionLog.i("ws failure " + code + " : " + t);
                main.post(new Runnable() {
                    public void run() {
                        listener.onError("ws failure " + code + " " + t.getMessage());
                        if (wantConnected) {
                            scheduleReconnect();
                        } else {
                            listener.onClosed("failure");
                        }
                    }
                });
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (gen != connGen) {
                    return;   // v1.3.19：陈旧连接的关闭回调 —— close() 已自己投递过 onClosed
                }
                open.set(false);
                ws = null;
                SessionLog.i("uplink summary: sent=" + uplinkSent + " fail=" + uplinkFail);
                Log.i(TAG, "ws closed code=" + code + " reason=" + reason);
                SessionLog.i("ws closed code=" + code + " reason=" + reason);
                final String r = "code=" + code + " " + reason;
                main.post(new Runnable() {
                    public void run() {
                        if (wantConnected) {
                            listener.onClosed(r + " (will reconnect)");
                            scheduleReconnect();
                        } else {
                            listener.onClosed(r);
                        }
                    }
                });
            }
        });
    }

    private void scheduleReconnect() {
        long delay = reconnectBackoff;
        reconnectBackoff = Math.min(BACKOFF_MAX_MS, reconnectBackoff * 2);
        Log.i(TAG, "reconnect in " + delay + "ms");
        main.postDelayed(new Runnable() {
            public void run() {
                if (wantConnected && !open.get()) {
                    connect();
                }
            }
        }, delay);
    }

    /**
     * 主动断开（进入 IDLE）。
     *
     * ⚠️ v1.2.5 关键修复：socket 从未建立时（如连接被服务器 401 拒绝后
     * 看门狗前来关闭），okhttp 不会产生任何回调 —— 之前的实现直接返回，
     * SessionController.enterIdle() 永远不执行 → 会话卡死 CONNECTING、
     * 麦克风永不归还 → 原厂 KWS 永久致聋（v1.2.4 实机「喊小讯就死」根因）。
     * 这里必须手动补发 onClosed 让状态机收尾。
     */
    public void close(String reason) {
        wantConnected = false;
        // v1.3.19：代数自增 —— 现存 socket 的一切回调（onOpen/onMessage/onClosed/
        // onFailure）从此全部失效；onClosed 改由我们自己投递，不再依赖 okhttp 的
        // 异步回调，彻底消除「旧 socket 迟到回调砸掉新会话」的竞态（详见 connGen）。
        connGen++;
        main.removeCallbacks(idleCloseTask);
        WebSocket w = ws;
        ws = null;
        open.set(false);
        final String r = (w == null) ? (reason + " (socket never opened)") : reason;
        if (w != null) {
            try {
                w.close(1000, reason);
            } catch (Throwable ignored) {
            }
        }
        main.post(new Runnable() {
            public void run() {
                listener.onClosed(r);
            }
        });
    }

    /** 标记「TTS 已结束」，启动空闲断开计时（时长见 idleCloseDelayMs） */
    public void markIdle() {
        main.removeCallbacks(idleCloseTask);
        main.postDelayed(idleCloseTask, idleCloseDelayMs);
    }

    // ---------------- 发送 ----------------

    private void sendHello() {
        try {
            JSONObject audio = new JSONObject();
            audio.put("format", "opus");
            audio.put("sample_rate", AudioCapture.SAMPLE_RATE);
            audio.put("channels", AudioCapture.CHANNELS);
            audio.put("frame_duration", 60);

            JSONObject o = new JSONObject();
            o.put("type", "hello");
            o.put("version", 1);
            // v1.2.9：与 ESP32 固件 hello 逐字段对齐 —— 官方对真 hello 仍静默时，
            // 唯一内容差异就是缺 features（mcp 能力声明）
            // v0.3.13 / v0.3.19 两次实证：hello 里带 features.aec=true 会让官方
            // 服务器（api.tenclass.net）对该 hello 静默不应 → 连不上。
            // 逆向 huihong 版小智（2026-10-25）确认：它的 hello 里**完全没有 aec
            // 字段**（仅可选 mcp），barge-in 靠的是 listen start 的 mode:"realtime"
            // （XiaoZhi.e() 在 autoMode+realtimeMode 时发）+ 客户端侧 WebRTC AEC。
            // 故这里**永不发 aec**：连官方/NAS 都不发。实时打断只靠 listen 的
            // mode:"realtime"（v0.3.15 已加）。不发 aec:true 时官方服务端会自己
            // 跑服务端 AEC 消上行 TTS 回声，反而比硬件 AEC 更干净。
            JSONObject features = new JSONObject();
            features.put("mcp", true);
            o.put("features", features);
            o.put("transport", "websocket");
            o.put("audio_params", audio);
            // v1.2.8：如实记录 —— 之前 sendText 静默失败后这里照样打 "hello sent"，
            // 把「从没发出过」伪装成「已发送」，误导了 v1.2.2~v1.2.7 的全部诊断。
            if (sendText(o.toString())) {
                Log.i(TAG, "hello sent: " + o);
                SessionLog.i("hello sent: " + o);
            } else {
                Log.e(TAG, "hello NOT sent (socket missing): " + o);
                SessionLog.i("hello NOT sent (socket missing)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "sendHello threw", t);
        }
    }

    /** 发送裸 opus 帧（零帧头）。v1.2.13：逐帧计数落盘（1-3 全记，后每 50 帧记一次） */
    public boolean sendAudio(byte[] opusFrame) {
        WebSocket w = ws;
        if (w == null || !open.get()) {
            uplinkFail++;
            if (uplinkFail <= 3 || uplinkFail % 50 == 0) {
                SessionLog.i("uplink FAIL x" + uplinkFail + " (socket missing/closed)");
            }
            return false;
        }
        boolean ok = w.send(ByteString.of(opusFrame));
        if (ok) {
            uplinkSent++;
            if (uplinkSent <= 3 || uplinkSent % 50 == 0) {
                SessionLog.i("uplink #" + uplinkSent + " (" + opusFrame.length + "B opus)");
            }
        } else {
            uplinkFail++;
            if (uplinkFail <= 3 || uplinkFail % 50 == 0) {
                SessionLog.i("uplink FAIL x" + uplinkFail + " (send() rejected)");
            }
        }
        return ok;
    }

    public void sendStopListening() {
        sendText("{\"type\":\"listen\",\"state\":\"stop\"}");
    }

    /**
     * v0.3.21：listen start 统一 AutoStop 模式。
     * 实时打断改由本地 KWS 承担（播放中喊「小智小智」→ handleWakeup 的
     * TTS_PLAYING 分支强制停 TTS + 干净重听），不再发 realtime —— realtime 依赖
     * 服务端 AEC，而 R1 硬件 AEC 不完美、公网 hello 又拒 aec 字段，实测
     * 「打断生效但不传话」。AutoStop 下服务器在 TTS 期间不跑 VAD，靠客户端
     * KWS 命中唤醒词来打断，完全不依赖回声消除。
     */
    public void sendStartListening() {
        // v0.3.21：统一 AutoStop 模式。实时打断改由本地 KWS 承担（播放中喊
        // 「小智小智」→ handleWakeup 的 TTS_PLAYING 分支强制停 TTS + 重听），
        // 不再发 realtime —— realtime 依赖服务端 AEC，而 R1 硬件 AEC 不完美、
        // 公网 hello 又拒 aec 字段，realtime 路径实测「打断生效但不传话」。
        sendText("{\"type\":\"listen\",\"state\":\"start\",\"mode\":\"auto\"}");
        SessionLog.i("listen start mode=auto");
    }

    /**
     * v0.3.24：通知服务端**中止当前 TTS 播报**（官方协议的 abort 消息）。
     *
     * 官方 ESP32 固件 main/protocols/protocol.cc:58-65 SendAbortSpeaking()：
     *   {"session_id":"..","type":"abort","reason":"wake_word_detected"}
     *
     * 官方在「播放中命中唤醒词」时的完整动作序列
     * （main/application.cc:913-929 HandleWakeWordDetectedEvent）：
     *   ① SendAbortSpeaking()  ← **通知服务端停播**（我们此前完全缺失！）
     *   ② 清空待发音频队列（避免把残留发给服务器）
     *   ③ 状态 → Listening → StartListeningAudio() → SendStartListening()
     *
     * 缺了 ① 的后果（2026-10-25 用户实测）：
     *   · 服务端继续生成并下发 TTS → 尾帧又灌进播放器 →「打断后播报停不下来」；
     *   · 服务端仍处在上一轮 TTS 轮次里 → 我们后续的上行音频它不当作新一轮
     *     处理 →「再说话小智没反应、也没有上行语音」。
     * 官方固件的协议文档（docs/websocket.md）也明确列了这条：
     *   "Abort 终止当前说话(TTS 播放)或语音通道"。
     *
     * @param reason 官方只用 "wake_word_detected"；留空则不带该字段
     */
    public void sendAbortSpeaking(String reason) {
        try {
            JSONObject o = new JSONObject();
            // 官方每条消息都带 session_id（服务器在 hello 响应里分配）。
            // 我们此前整条链路都不带，能工作说明服务端不强制；但 abort 关系到
            // 「停哪一轮 TTS」，带上更保险。
            if (sessionId != null && sessionId.length() > 0) {
                o.put("session_id", sessionId);
            }
            o.put("type", "abort");
            if (reason != null && reason.length() > 0) {
                o.put("reason", reason);
            }
            sendText(o.toString());
            SessionLog.i("abort sent (reason=" + reason + ")");
        } catch (Throwable t) {
            Log.e(TAG, "sendAbortSpeaking threw", t);
        }
    }

    /**
     * v1.3.21：上报唤醒词 —— 官方 `Protocol::SendWakeWordDetected()` 的同名动作
     * （xiaozhi-esp32/main/protocols/protocol.cc:67）：
     *   {"session_id":"..","type":"listen","state":"detect","text":"<唤醒词>"}
     * 我们整条链路都没有 session_id（hello 也没带），故省略；服务端
     * `listenMessageHandler` 只读 state / text，缺 session_id 不影响。
     * 服务端收到后会走「唤醒词分支」：命中 wakeup_words 就回一句问候。
     */
    public void sendWakeWordDetected(String wakeWord) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "listen");
            o.put("state", "detect");
            o.put("text", wakeWord);
            sendText(o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "sendWakeWordDetected threw", t);
        }
    }

    // ---------------- v1.2.13：MCP 最小应答 ----------------

    private void sendMcpInitializeResult(int id) {
        try {
            JSONObject info = new JSONObject();
            info.put("name", "r1-client");
            info.put("version", "1.3.6");
            JSONObject result = new JSONObject();
            result.put("protocolVersion", "2024-11-05");
            result.put("capabilities", new JSONObject());
            result.put("serverInfo", info);
            sendMcpResult(id, result);
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpInitializeResult threw", t);
        }
    }

    private void sendMcpEmptyToolsResult(int id) {
        try {
            JSONObject result = new JSONObject();
            result.put("tools", new org.json.JSONArray());
            sendMcpResult(id, result);
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpEmptyToolsResult threw", t);
        }
    }

    /**
     * ldyb5：向服务器声明 self.music.* 工具（音乐库=NAS 8010 index.json）。
     * 小智 LLM 会按 description 语义调用。
     */
    private void sendMcpToolsResult(int id) {
        try {
            org.json.JSONArray tools = new org.json.JSONArray();
            String[][] defs = {
                    {"self_radio_play", "播放本地电台（蜻蜓FM直播流列表）。参数 query：电台名、关键词，或按列表序号点播（如「第3个」「第三个」）", "query"},
                    {"self_radio_pause", "暂停当前电台", null},
                    {"self_radio_resume", "继续播放已暂停的电台", null},
                    {"self_radio_stop", "停止电台播放", null},
                    {"self_radio_next", "切换到下一个电台", null},
                    {"self_radio_prev", "切换到上一个电台", null},
                    {"self_radio_status", "查询当前电台播放状态", null},
                    {"self_radio_list", "报出本地电台列表里有哪些台", null},
                    {"self_radio_reload", "重新加载电台列表 JSON（用户更新了电台文件后用）", null},
                    {"self_volume", "查询或设置媒体音量。参数 pct：0-100，留空=只查询当前音量", "?pct"},
                    {"self_kuwo_play", "用设备上的酷我音乐App搜索并播放。参数 query：歌名，或「歌手名+的歌/的专辑」（如「周杰伦的歌」）。⚠️ 用户说「听某某的歌/专辑」想听某歌手的作品集时，query 必须带「的歌」后缀（如 query=周杰伦的歌），这样会走歌手模式（过滤合作曲+去重，体验同歌手页）；若只传歌手名（如 query=周杰伦）会被当歌名搜出同名多版本+合作曲。注意这是搜索播放，不是收藏列表。用户说「播放音乐」「放首歌」「随便听听」等未指定歌名/歌手的意图时改用 self_kuwo_random", "query"},
                    {"self_kuwo_pause", "暂停酷我音乐App的播放", null},
                    {"self_kuwo_resume", "继续酷我音乐App的播放", null},
                    {"self_kuwo_next", "酷我音乐App切下一首", null},
                    {"self_kuwo_prev", "酷我音乐App切上一首", null},
                    {"self_kuwo_status", "查询酷我音乐App的播放状态（含是否被VIP付费拦截）", null},
                    {"self_kuwo_mute", "酷我音乐App静音/取消静音切换", null},
                    {"self_kuwo_mode", "设置酷我播放模式。参数 mode：one(单曲循环)/order(顺序)/circle(列表循环)/random(随机)", "mode"},
                    {"self_kuwo_random", "酷我播放音乐（用户未指定具体歌名时；已暂停则续播前一首，已在播放则继续，不会杀掉当前歌曲）。用户说「播放音乐」「放首歌」「随便听听」等未指定歌名的意图时用这个", null},
                    {"self_kuwo_favorites", "播放用户在酷我的「我喜欢」收藏列表（用户说「播放我的收藏」「放收藏的歌」「播放我喜欢听」）。如果用户还说了序号（如「第一首」「第3首」），必须把序号原话通过 query 参数传进来，例如 query=第一首、query=第3首；不带序号就不传 query（整份随机播放）。注意这不是搜歌工具，「收藏」不是歌名", "?query"},
                    {"self_kuwo_lrc", "用酷我按歌词搜歌并播放。参数 query：一句歌词，如「穿越时间的钟」", "query"},
                    {"self_kuwo_theme", "用酷我按主题搜内容并播放。参数 query：主题词，如「睡前故事」「开车」「白噪音」", "query"},
                    {"self_kuwo_favorite", "把酷我当前正在播放的歌加入「我喜欢」", null},
                    {"self_kuwo_collect", "收藏/取消收藏酷我当前播放的歌。参数 on：true=收藏 false=取消", "on"},
                    {"self_kuwo_exit", "彻底退出酷我音乐App", null},
                    {"self_kuwo_quality", "设置酷我播放音质。参数 level：0=标准 1=高品 2=无损", "level"},
                    {"self_kuwo_candidates", "查询酷我最近一次搜索返回的候选歌曲列表（歌名-歌手）", null},
                    {"self_screen_lock", "锁定设备屏幕并熄灭显示屏。用户说「锁屏」「熄屏」「把屏幕关了」「关闭屏幕」时用这个工具", null},
                    {"self_screen_unlock", "点亮/唤醒屏幕（仅点亮，不解锁、不显示额外界面）。用户说打开屏幕、点亮屏幕、亮屏、开屏、让屏幕亮起来时用这个工具", null},
                    // v0.3.47：电视直播（MyTV）语音控制 —— 默认源单表、台名/台号点播、顺序/随机切台
                    {"self_tv_open", "打开设备上的「电视直播」App（只打开、不指定频道时用它）。用户说「打开电视」「看电视」「打开电视直播」「我要看电视」时用这个；打开后停在上次或第一个频道。若同时说了具体频道名/频道号，改用 self_tv_play", null},
                    {"self_tv_close", "关闭电视直播并回到小智界面。用户说「关掉电视」「关闭电视直播」「不看电视了」「退出电视」时用这个", null},
                    {"self_tv_play", "点播电视频道。参数 query：频道名（如「CCTV5」「浙江卫视」「中央一套」）或频道号（如「5」「第5台」「29频道」）。⚠️ 用户没有明确要求更换订阅源时，一律只在当前默认订阅源里找频道，不要用其他源的同名频道（同名台可能是广播音频）。用户说「看XX台」「换到XX」「播XX频道」「调到N台」「打开电视看XX」时用这个", "query"},
                    {"self_tv_next", "在当前默认源的频道列表里按顺序切到下一个台（到末尾回到第一个）。用户说「下个台」「下一个频道」「下一台」「换个台」时用这个", null},
                    {"self_tv_prev", "在当前默认源的频道列表里切到上一个台。用户说「上个台」「上一个频道」「回上一个台」时用这个", null},
                    {"self_tv_random", "在当前默认源里随机播一个频道。用户说「随便播个台」「随机换个台」「随便看个频道」时用这个", null},
                    {"self_tv_status", "查询电视直播当前状态：正在用的默认订阅源、频道总数、当前频道号与名称", null},
                    {"self_tv_list", "列出电视直播当前默认源的频道（带频道号）。参数 query 可填关键词过滤，留空则列前 20 个", "?query"},
                    {"self_tv_source", "⚠️ 仅在用户明确要求更换订阅源时才能用（如「换个源」「用网络收音机源」「切到第2个源」）。参数 query：订阅源名称或序号。用户没有明确说要换源时绝对不要调用，默认源由电视 App 自己的设置决定", "query"},
                    // v0.3.50：设备 / 程序控制
                    {"self_screen_stay_on", "开启屏幕常亮：让屏幕一直亮着、不再自动息屏，直到用户要求关闭。用户说「开启屏幕常亮」「不要息屏」「别熄屏」「屏幕别关」「一直亮着」「保持亮屏」「不要让屏幕灭」「不要锁屏」时用这个。注意「打开屏幕/亮屏」只是点亮一下，不是常亮，那种用 self_screen_unlock", null},
                    {"self_screen_stay_off", "关闭屏幕常亮，恢复设备原来的自动息屏时间。用户说「关闭屏幕常亮」「可以息屏了」「恢复自动息屏」「别一直亮着」「正常息屏吧」时用这个", null},
                    {"self_screen_brightness", "调节屏幕亮度，也能查询当前亮度。参数 query：口语或数值，如「亮一点」「暗一点」「调到50%」「百分之三十」「最亮」「最暗」「80」；不传参数则报当前亮度。用户说「调亮」「太暗了」「把屏幕调暗点」「亮度调到一半」「屏幕太亮」「最亮」时用这个", "?query"},
                    {"self_go_home", "回到设备桌面（系统默认桌面/主页）。用户说「回到桌面」「回桌面」「回主页」「打开桌面」「显示桌面」时用这个。只是切到桌面，不会关闭小智", null},
                    {"self_exit_app", "彻底退出小智：停止语音服务、麦克风监听、悬浮图标和音乐播放，并完全关闭小智 App；重新点开 App 或重启设备才能再用。用户说「你下班吧」「我不要你了小智」「退出小智」「关闭小智」「小智别听了」「小智下线」「小智关机」「不用你了」时用这个。⚠️ 只说「关闭屏幕/锁屏」时用 self_screen_lock，不要退出", null},
                    {"self_device_status", "查询设备当前状态：屏幕常亮是否开启、息屏超时、屏幕亮度百分比、充电保持唤醒开关", null},
            };
            for (String[] d : defs) {
                JSONObject t = new JSONObject();
                t.put("name", d[0]);
                t.put("description", d[1]);
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                JSONObject props = new JSONObject();
                if (d[2] != null) {
                    boolean req = !d[2].startsWith("?");
                    String p = req ? d[2] : d[2].substring(1);
                    JSONObject q = new JSONObject();
                    q.put("type", "string");
                    q.put("description", "参数值");
                    props.put(p, q);
                    if (req) {
                        org.json.JSONArray req2 = new org.json.JSONArray();
                        req2.put(p);
                        schema.put("required", req2);
                    }
                }
                schema.put("properties", props);
                t.put("inputSchema", schema);
                tools.put(t);
            }
            JSONObject result = new JSONObject();
            result.put("tools", tools);
            Log.i(TAG, "mcp tools/list -> " + tools.length() + " tools (含 self_tv_* / self_screen_* / self_exit_app)");
            sendMcpResult(id, result);
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpToolsResult threw", t);
            sendMcpEmptyToolsResult(id);
        }
    }

    /**
     * v0.3.9：锁屏 —— root 模拟 power 键（keyevent 26）。
     * 仅在屏幕点亮时发（熄屏态再发会反而点亮）；需设置里 Root 功能开启。
     */
    private static String lockScreen() {
        android.content.Context ctx = KuwoController.appCtx;
        if (ctx == null) {
            return "设备上下文未就绪";
        }
        if (!AppSettings.rootEnabled(ctx)) {
            return "锁屏需要 Root 功能，请在小智设置里打开";
        }
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    ctx.getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                return "屏幕已经是熄灭状态";
            }
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "input keyevent 26"});
            p.waitFor();
            return "好的，已锁屏";
        } catch (Throwable t) {
            return "锁屏失败：" + t.getMessage();
        }
    }

    /**
     * v0.3.33：点亮屏幕（语音「打开屏幕/亮屏」）。不需要 root，走 SessionController
     * 的 WakeScreenActivity（FLAG_TURN_SCREEN_ON 系统级点亮，绕开厂商 wake-lock 屏蔽）。
     */
    private static String unlockScreen() {
        android.content.Context ctx = KuwoController.appCtx;
        if (ctx == null) {
            return "设备上下文未就绪";
        }
        SessionController.wakeScreenNow(ctx);
        return "已点亮屏幕";
    }

    /** ldyb5：处理 tools/call，覆盖 self_radio_*（本地电台）与 self_kuwo_*（酷我全接口） */
    private void handleMcpToolCall(int id, JSONObject params) {        String name = params.optString("name");
        JSONObject args = params.optJSONObject("arguments");
        final String query = args == null ? "" : args.optString("query", "");
        final String modeArg = args == null ? "" : args.optString("mode", "");
        final String onArg = args == null ? "" : args.optString("on", "true");
        final String levelArg = args == null ? "" : args.optString("level", "");
        final String pctArg = args == null ? "" : args.optString("pct", "");
        // v0.3.47：电视控制要拉起 App / 探活 HTTP / 读缓存，最长可达数秒，
        // 必须放到后台线程，绝不能阻塞 WebSocket 读线程（否则 TTS 音频会卡住）。
        if (name.startsWith("self_tv_")) {
            final int tid = id;
            final String tn = name;
            final String tq = query;
            new Thread(new Runnable() {
                public void run() {
                    String r;
                    try {
                        r = TvController.handle(tn, tq);
                    } catch (Throwable t) {
                        r = "电视控制出错：" + t.getMessage();
                    }
                    sendToolResult(tid, tn, tq, r);
                }
            }, "ldyb5-tv").start();
            return;
        }
        // v0.3.50：设备控制要读写系统设置 / 起 Activity，也放后台线程，避免阻塞 WS。
        if (DeviceController.isDeviceTool(name)) {
            final int tid = id;
            final String tn = name;
            final String tq = query;
            final String tp = pctArg;
            final String to = onArg;
            new Thread(new Runnable() {
                public void run() {
                    String r;
                    try {
                        r = DeviceController.handle(tn, tq, tp, to);
                    } catch (Throwable t) {
                        r = "设备控制出错：" + t.getMessage();
                    }
                    sendToolResult(tid, tn, tq, r);
                }
            }, "ldyb5-dev").start();
            return;
        }
        MusicPlayer mp = MusicPlayer.I;
        boolean isKuwo = name.startsWith("self_kuwo_");
        String text;
        if (mp == null && name.startsWith("self_radio_")) {
            text = "电台播放器未就绪";
        } else if ("self_radio_play".equals(name)) {
            // 两个音源互斥：电台开播前暂停酷我
            if (KuwoController.isPlaying()) {
                KuwoController.playPause();
            }
            // v0.3.0：序号选台 ——「第3个」「第三个电台」按列表顺序点播
            int ord = parseOrdinal(query);
            if (ord > 0) {
                java.util.List<String> ns = mp.radioNames();
                if (ord <= ns.size()) {
                    String nm = ns.get(ord - 1);
                    mp.radio(nm);
                    text = "好的，播放第 " + ord + " 个电台「" + nm + "」";
                } else {
                    text = "电台列表只有 " + ns.size() + " 个台，没有第 " + ord + " 个";
                }
            } else {
                mp.play(query);
                text = "好的，为你播放电台「" + query + "」";
            }
        } else if ("self_radio_pause".equals(name)) {
            // v0.3.26：幂等暂停 + 按真实状态播报（旧实现无条件说「已暂停」）。
            text = mp.ensurePaused();
        } else if ("self_radio_resume".equals(name)) {
            // v0.3.26：幂等续播 + 按真实状态播报。
            text = mp.ensureResumed();
        } else if ("self_radio_stop".equals(name)) {
            mp.stop();
            text = "已停止播放";
        } else if ("self_radio_next".equals(name)) {
            mp.next();
            text = "切换电台";
        } else if ("self_radio_prev".equals(name)) {
            mp.prev();
            text = "切换电台";
        } else if ("self_radio_status".equals(name)) {
            text = mp.describe();
        } else if ("self_radio_list".equals(name)) {
            java.util.List<String> ns = mp.radioNames();
            if (ns.isEmpty()) {
                text = "电台列表为空，可以推 radio.json 导入";
            } else {
                StringBuilder sb = new StringBuilder("共 ").append(ns.size()).append(" 个电台：");
                for (int i = 0; i < ns.size() && i < 15; i++) {
                    sb.append(i == 0 ? "" : "、").append(ns.get(i));
                }
                if (ns.size() > 15) {
                    sb.append(" 等");
                }
                text = sb.toString();
            }
        } else if ("self_radio_reload".equals(name)) {
            mp.loadRadiosAsync();
            text = "已重新加载电台列表";
        } else if ("self_volume".equals(name)) {
            android.media.AudioManager am = (android.media.AudioManager)
                    KuwoController.appCtx.getSystemService(android.content.Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            if (pctArg.trim().length() == 0) {
                text = "当前音量 " + (cur * 100 / max) + "%（" + cur + "/" + max + "）";
            } else {
                try {
                    int pct = Math.max(0, Math.min(100, Integer.parseInt(pctArg.trim())));
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            Math.round(max * pct / 100f), 0);
                    text = "音量已设为 " + pct + "%";
                } catch (Throwable t) {
                    text = "音量参数无效：" + pctArg;
                }
            }
        } else if (mp == null && isKuwo) {
            text = "酷我控制未就绪";
        } else if ("self_kuwo_play".equals(name)) {
            // v0.3.31：收藏意图兜底 —— 「播放收藏的第一首」「播放我喜欢的歌」常被 LLM
            // 选成 self_kuwo_play 并搜歌名「收藏的第一首」（2026-10-26 实测）。query 含
            // 「收藏/我喜欢/喜欢听/红心」等词时改走收藏播放，绝不把词当歌名去搜。
            if (isFavoriteQuery(query)) {
                int idx = parseOrdinal(query);   // 0=未指定；第N首→N
                int n;
                String extra;
                if (idx > 0) {
                    // 指定「第N首」：不打乱，按列表顺序从该首开始
                    n = KuwoController.playFavorite(idx - 1);
                    extra = "第 " + idx + " 首，";
                } else {
                    // v0.3.35：未指定序号 → 随机打乱播放收藏
                    n = KuwoController.playFavorite();
                    extra = "随机";
                }
                text = n > 0
                        ? ("好的，" + extra + "播放你的收藏列表，共 " + n + " 首")
                        : "酷我收藏列表已打开，但自动播放失败，请在屏幕上点播";
            // v0.3.12：通用意图兜底 —— 「播放音乐」「放首歌」被 LLM 当成搜名为
            // 「音乐」的歌（2026-10-25 实测日志 SEARCH_MUSIC name=音乐）。
            // query 是这类泛词时改走随机播放，绝不去搜「音乐」二字。
            } else if (isGenericMusicQuery(query)) {
                // v0.3.32：泛词直接随机播，不再等「真出声」再回报（状态由用户自己看屏幕判断）。
                // v0.3.44：randomPlay 现在会真正探测酷我是否启动并返回如实回话，
                // 不再无条件报「正在播放」。
                mp.stop();
                text = KuwoController.randomPlay(true);
            // v0.3.39：歌手意图 —— 「听周杰伦的歌」→ 搜歌手→过滤合作曲+去重→整单播，
            // 模拟歌手页干净体验，避免 SEARCH_MUSIC 把歌手名当歌名搜出同名多版本+合作曲。
            } else if (isArtistQuery(query)) {
                String artist = extractArtist(query);
                mp.stop();
                KuwoController.searchArtistAndPlay(artist);
                text = "好的，用酷我为你播放「" + artist + "」的歌曲";
            } else {
                // 两个音源互斥：酷我开播前停掉本地电台
                mp.stop();
                KuwoController.searchAndPlay(query, "", true);
                text = "好的，用酷我为你播放「" + query + "」";
            }
        } else if ("self_kuwo_pause".equals(name)) {
            // v0.3.26：幂等暂停 + 按真实状态播报（旧实现无条件发切换并说
            // 「已暂停」，导致实际在播放时播报与事实相反）。
            text = KuwoController.ensurePaused();
        } else if ("self_kuwo_resume".equals(name)) {
            // v0.3.26：幂等续播 + 按真实状态播报。
            text = KuwoController.ensureResumed();
        } else if ("self_kuwo_next".equals(name)) {
            KuwoController.next();
            text = "切到下一首";
        } else if ("self_kuwo_prev".equals(name)) {
            KuwoController.prev();
            text = "回到上一首";
        } else if ("self_kuwo_status".equals(name)) {
            text = KuwoController.describe();
        } else if ("self_kuwo_mute".equals(name)) {
            KuwoController.mute();
            text = "已切换酷我静音";
        } else if ("self_kuwo_mode".equals(name)) {
            if (KuwoController.setMode(modeArg)) {
                text = "已切换播放模式";
            } else {
                text = "不认识的播放模式：" + modeArg;
            }
        } else if ("self_kuwo_random".equals(name)) {
            // v0.3.32：先停电台，直接随机播；播放类指令内部走 runWhenReady 拉起酷我。
            // v0.3.44：randomPlay 会真正探测酷我是否启动并返回如实回话。
            mp.stop();
            text = KuwoController.randomPlay(true);
        } else if ("self_kuwo_favorites".equals(name)) {
            mp.stop();
            // v0.3.37：支持「播放收藏的第N首」。
            // 此前这个工具**没有参数**、handler 无条件走随机洗牌，而工具描述里又写着
            // 「播放收藏的第一首/第几首用这个」→ LLM 说「播第N首」时必然选中它，
            // 结果整份被打乱（用户实测「顺序比之前更乱」）。现在带序号就按界面顺序
            // 从该首起播，不带序号才随机。
            int fidx = parseOrdinal(query);
            int n;
            if (fidx > 0) {
                n = KuwoController.playFavorite(fidx - 1);
                text = n > 0
                        ? ("好的，播放你的收藏列表第 " + fidx + " 首，共 " + n + " 首")
                        : "酷我收藏列表已打开，但自动播放失败，请在屏幕上点播";
            } else {
                n = KuwoController.playFavorite();
                text = n > 0 ? ("好的，随机播放你的收藏列表，共 " + n + " 首")
                        : "酷我收藏列表已打开，但自动播放失败，请在屏幕上点播";
            }
        } else if ("self_kuwo_lrc".equals(name)) {
            mp.stop();
            KuwoController.searchByLyric(query, true);
            text = "好的，按歌词用酷我搜「" + query + "」";
        } else if ("self_kuwo_theme".equals(name)) {
            mp.stop();
            KuwoController.searchByTheme(query, true);
            text = "好的，用酷我找「" + query + "」相关内容";
        } else if ("self_kuwo_favorite".equals(name)) {
            KuwoController.favorite();
            text = "已把当前歌加入我喜欢";
        } else if ("self_kuwo_collect".equals(name)) {
            boolean on = !"false".equalsIgnoreCase(onArg) && !"0".equals(onArg)
                    && !"取消".equals(onArg);
            KuwoController.collect(on);
            text = on ? "已收藏当前歌" : "已取消收藏";
        } else if ("self_kuwo_exit".equals(name)) {
            KuwoController.exitApp();
            text = "酷我音乐已退出";
        } else if ("self_kuwo_candidates".equals(name)) {
            String c = KuwoController.lastCandidates;
            if (c == null || c.length() == 0) {
                text = "还没有搜索结果回传";
            } else {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(c);
                    StringBuilder sb = new StringBuilder("搜到 ").append(arr.length()).append(" 个候选：");
                    for (int i = 0; i < arr.length() && i < 5; i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        sb.append(i == 0 ? "" : "、")
                          .append(o.optString("name", "?"))
                          .append("-").append(o.optString("singer", "?"));
                    }
                    text = sb.toString();
                } catch (Throwable t) {
                    text = "搜索结果解析失败";
                }
            }
        } else if ("self_kuwo_quality".equals(name)) {
            try {
                int lv = Integer.parseInt(levelArg.trim());
                KuwoController.setQuality(lv);
                text = "已切换音质为" + (lv == 2 ? "无损" : lv == 1 ? "高品" : "标准");
            } catch (Throwable t) {
                text = "音质参数无效：" + levelArg;
            }
        } else if ("self_screen_lock".equals(name)) {
            // v0.3.9：锁屏熄屏 —— root input keyevent 26（power 键，亮屏态按下即熄屏）
            text = lockScreen();
        } else if ("self_screen_unlock".equals(name)) {
            // v0.3.33：点亮屏幕（语音「打开屏幕/亮屏」），不需要 root
            text = unlockScreen();
        } else {
            text = "未知工具：" + name;
        }
        sendToolResult(id, name, query, text);
    }

    /** v0.3.29：构造并回送 MCP 工具结果（抽出来，便于播类指令在后台线程等真实
     *  起播后再回报，而不必阻塞 WebSocket 读线程）。 */
    private void sendToolResult(int id, String name, String query, String text) {
        try {
            JSONObject content = new JSONObject();
            org.json.JSONArray arr = new org.json.JSONArray();
            JSONObject item = new JSONObject();
            item.put("type", "text");
            item.put("text", text);
            arr.put(item);
            content.put("content", arr);
            sendMcpResult(id, content);
        } catch (Throwable t) {
            Log.e(TAG, "toolCall result threw", t);
        }
        Log.i(TAG, "mcp tool " + name + "(" + query + ") -> " + text);
        SessionLog.i("mcp tool " + name + " -> " + text);
        ScreenBus.bumpMusic();
    }

    /** v0.3.12：「播放音乐」类通用意图词表 —— 命中即走随机播放，
     *  不当成歌名去搜（否则会搜出一首名叫《音乐》的歌，2026-10-25 实测）。 */
    private static final java.util.Set<String> GENERIC_MUSIC_WORDS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "音乐", "歌曲", "歌", "一首歌", "几首歌",
                    "播放音乐", "播放歌曲", "放首歌", "放歌", "来首歌", "来点音乐",
                    "放点音乐", "点歌", "随便", "随便听听", "随便放", "随便来首"));

    private static boolean isGenericMusicQuery(String q) {
        if (q == null) {
            return true;   // 没给 query = 没指定歌
        }
        String s = q.trim().replaceAll("[\\s，。！？!?,.]", "");
        return s.length() == 0 || GENERIC_MUSIC_WORDS.contains(s);
    }

    /**
     * v0.3.31：query 是否其实是「播放收藏」意图而非搜歌名。
     * 「播放收藏的第一首」会被 LLM 错选 self_kuwo_play，这里把含「收藏/我喜欢/
     * 喜欢听/红心/我的歌单/我收藏」等词的 query 拦下来改走 playFavorite()。
     */
    private static boolean isFavoriteQuery(String q) {
        if (q == null) {
            return false;
        }
        String s = q.trim().replaceAll("[\\s，。！？!?,.]", "");
        return s.contains("收藏") || s.contains("我喜欢") || s.contains("喜欢听")
                || s.contains("红心") || s.contains("我的歌单") || s.contains("我收藏");
    }

    /**
     * v0.3.39：query 是否其实是「听某人的歌/专辑」—— 即歌手意图而非歌名搜索。
     * 命中尾部「的歌/的歌曲/的专辑/的曲」即视为歌手意图（如「周杰伦的歌」）。
     * 注意顺序：收藏意图(isFavoriteQuery)在 handler 里先于本判断，互不冲突。
     */
    private static boolean isArtistQuery(String q) {
        if (q == null) {
            return false;
        }
        String s = q.trim().replaceAll("[\\s，。！？!?,.、]", "");
        return s.endsWith("的歌") || s.endsWith("的歌曲")
                || s.endsWith("的专辑") || s.endsWith("的曲");
    }

    /** v0.3.39：从「周杰伦的歌」这类 query 取出歌手名「周杰伦」。 */
    private static String extractArtist(String q) {
        if (q == null) {
            return "";
        }
        String s = q.trim().replaceAll("[\\s，。！？!?,.、]", "");
        if (s.endsWith("的歌曲") || s.endsWith("的专辑")) {
            return s.substring(0, s.length() - 3);
        }
        if (s.endsWith("的歌") || s.endsWith("的曲")) {
            return s.substring(0, s.length() - 2);
        }
        return s;
    }

    /**
     * v0.3.0：解析口令里的序号 ——「第3个」「第三个」「第10个电台」→ 3/3/10。
     * 支持阿拉伯数字与中文（一二三四五六七八九十两）。0 = 没有序号。
     */
    private static int parseOrdinal(String q) {
        if (q == null || q.length() == 0) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*([0-9一二三四五六七八九十两]+)\\s*(?:个|台|频道|路|首)?")
                .matcher(q);
        if (!m.find()) {
            return 0;
        }
        String s = m.group(1);
        if (s.matches("[0-9]+")) {
            try {
                return Integer.parseInt(s);
            } catch (Throwable t) {
                return 0;
            }
        }
        if ("两".equals(s)) {
            return 2;
        }
        if ("十".equals(s)) {
            return 10;
        }
        final String digits = "零一二三四五六七八九";
        int shi = s.indexOf('十');
        if (shi < 0) {
            int d = digits.indexOf(s.charAt(0));
            return d > 0 ? d : 0;
        }
        String a = s.substring(0, shi);
        String b = s.substring(shi + 1);
        int n = (a.length() == 0 ? 1 : Math.max(digits.indexOf(a.charAt(0)), 1)) * 10;
        if (b.length() > 0) {
            n += Math.max(digits.indexOf(b.charAt(0)), 0);
        }
        return n;
    }

    private void sendMcpResult(int id, JSONObject result) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("jsonrpc", "2.0");
            payload.put("id", id);
            payload.put("result", result);
            JSONObject o = new JSONObject();
            o.put("type", "mcp");
            o.put("payload", payload);
            if (sendText(o.toString())) {
                SessionLog.i("-> mcp result id=" + id);
            }
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpResult threw", t);
        }
    }

    /** @return 是否真的发出去了（v1.2.8：socket 为 null 时返回 false，不再静默） */
    private boolean sendText(String s) {
        WebSocket w = ws;
        if (w == null) {
            Log.w(TAG, "sendText but ws null: " + s);
            return false;
        }
        return w.send(s);
    }

    // ---------------- 接收 ----------------

    private void handleText(String text) {
        Log.i(TAG, "<- " + text);
        SessionLog.i("<- " + text);
        try {
            JSONObject o = new JSONObject(text);
            String type = o.optString("type");
            if ("tts".equals(type)) {
                String state = o.optString("state");
                if ("start".equals(state)) {
                    main.post(new Runnable() {
                        public void run() {
                            main.removeCallbacks(idleCloseTask);
                            listener.onTtsStart();
                        }
                    });
                // v1.2.16：服务器下发 "abort"（正常播完是 "stop"）。两者都只是「TTS
                // 播完了」—— 本地停播 + 走标准 onTtsStop 收尾（压上行、重发 listen
                // start、落回声闸门、续听）。真正的「播放中打断」由本地 KWS 命中唤醒词
                // 经 handleWakeup 的 TTS_PLAYING 分支处理，不经过服务器 abort。
                } else if ("stop".equals(state)) {
                    main.post(new Runnable() {
                        public void run() {
                            listener.onTtsStop(false);
                            markIdle();
                        }
                    });
                } else if ("abort".equals(state)) {
                    main.post(new Runnable() {
                        public void run() {
                            listener.onTtsStop(true);
                            markIdle();
                        }
                    });
                } else if ("sentence_start".equals(state)) {
                    // ldyb5：把 TTS 句子文本推给屏幕 UI（LLM 回复字幕）
                    final String sst = o.optString("text");
                    if (sst != null && sst.length() > 0) {
                        main.post(new Runnable() {
                            public void run() {
                                String prev = ScreenBus.llm;
                                ScreenBus.llm = (prev == null || prev.length() > 60)
                                        ? sst : prev + " " + sst;
                                if (ScreenBus.llm.length() > 120) {
                                    ScreenBus.llm = ScreenBus.llm
                                            .substring(ScreenBus.llm.length() - 120);
                                }
                            }
                        });
                    }
                }
            } else if ("stt".equals(type)) {
                final String t = o.optString("text");
                main.post(new Runnable() {
                    public void run() {
                        ScreenBus.stt = t;   // ldyb5：屏幕字幕
                        ScreenBus.llm = "";  // 新一轮，清上一轮回复
                        listener.onStt(t);
                    }
                });
            } else if ("hello".equals(type)) {
                final String sid = o.optString("session_id");
                // v0.3.24：记下 session_id，abort 消息要带（官方协议如此）。
                if (sid != null && sid.length() > 0) {
                    sessionId = sid;
                }
                Log.i(TAG, "handshake done, session_id=" + sid);
                main.post(new Runnable() {
                    public void run() {
                        listener.onHandshakeDone();
                    }
                });
            } else if ("mcp".equals(type)) {
                // v1.2.13：hello 声明 features.mcp=true 后，NAS 实测立刻发来
                // MCP initialize。不应答 = 服务器眼里一台哑 MCP 设备。
                // 最小实现：initialize 报真名 + tools/list 报空表，只声明不提供
                JSONObject payload = o.optJSONObject("payload");
                if (payload != null && payload.optInt("id", -1) >= 0) {
                    int id = payload.optInt("id");
                    String method = payload.optString("method");
                    if ("initialize".equals(method)) {
                        sendMcpInitializeResult(id);
                    } else if ("tools/list".equals(method)) {
                        sendMcpToolsResult(id);
                    } else if ("tools/call".equals(method)) {
                        handleMcpToolCall(id, payload.optJSONObject("params"));
                    }
                }
            } else if ("llm".equals(type)) {
                // ldyb5：LLM 情绪/文本提示 → 屏幕 UI
                final String lt = o.optString("text");
                if (lt != null && lt.length() > 0) {
                    main.post(new Runnable() {
                        public void run() {
                            ScreenBus.llm = lt;
                        }
                    });
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "handleText parse error", t);
        }
    }

    // ---------------- OkHttp 客户端 ----------------

    private OkHttpClient getClient() {
        if (client == null) {
            OkHttpClient.Builder b = new OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)  // ws 长读
                    .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true);
            try {
                // R1 系统根证书可能过期（2017 ROM），信任所有证书以兼容官方 TLS。
                // 仅用于本设备本地部署，不做通用分发。
                // v1.2.9 修复：之前写的是 sslSocketFactory(f, (X509TrustManager) null)
                // —— okhttp 直接 NPE，trust-all 从来没生效过（每次 connect 都 W 一次，
                // 碰巧官方证书链有效所以连接一直能建立）。现在正确传 tm。
                X509TrustManager tm = new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
                SSLSocketFactory f = ctx.getSocketFactory();
                b.sslSocketFactory(f, tm);
                b.hostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                        return true;
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "trust-all ssl setup failed", t);
            }
            client = b.build();
        }
        return client;
    }
}
