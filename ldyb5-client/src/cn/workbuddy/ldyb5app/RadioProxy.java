package cn.workbuddy.ldyb5app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * 电台直播流本地缓冲代理（v0.2.2 新增）。
 *
 * 为什么需要：直播 CDN 按「实时速率」供数据（64k 台恒定 8192B/s，零余量），
 * 且会周期性整体断流。Web 播放器开播前预缓冲 10~30s 所以不卡；
 * Android MediaPlayer 起播水位只有几秒，缓冲一耗尽就欠载 —— 实测
 * 「播 5s 停 10s 再继续」的循环。
 *
 * 做法：本机 127.0.0.1 起一个小 HTTP 服务，后台 okhttp 拉上游直播流灌进
 * 1MB 环形缓冲，预填 128KB（≈16s@64kbps）后才开始给 MediaPlayer 供数。
 * 上游断流/超时自动重连（最多 8 次），MediaPlayer 侧无感，靠预缓冲扛过缺口。
 *
 * 用法：
 *   RadioProxy p = new RadioProxy();
 *   String local = p.open(realUrl);      // 返回 http://127.0.0.1:port/stream
 *   mediaPlayer.setDataSource(local);
 *   ... 用完 p.close()（releaseMp 里统一放）
 */
public class RadioProxy {

    private static final String TAG = "RadioProxy";

    /** 环形缓冲 1MB（64kbps 下可攒 128s） */
    private static final int RING_CAP = 1 << 20;
    /** 起播预缓冲：64KB ≈ 8s@64k。CDN 对新连接有 ~78KB 初始突发，
     *  实测 2~3s 即可攒够，体感接近「点了就响」；之后环形缓冲继续攒余量 */
    private static final int PREFILL = 64 * 1024;
    /** 预填充最长等待（上游太慢也别让人等太久） */
    private static final long PREPARE_TIMEOUT_MS = 20000;
    /** 上游断流后重连次数上限 */
    private static final int MAX_RECONNECT = 8;
    /** 主动重连周期：CDN 对新连接有 ~100KB 初始突发（头 5s 约 21KB/s），
     *  随后稳态只有 ~8.2KB/s，贴着 64kbps 实时线（8KB/s），余量 3% —— 攒不住缓冲。
     *  定期主动断开重连吃突发，每周期净赚 ~60KB，环形缓冲持续变厚。
     *  仅在缓冲有存粮时才切，MediaPlayer 无感。代价：直播时间轴向前跳几秒，电台可接受 */
    private static final long RECYCLE_MS = 45000;

    private final Object lock = new Object();
    private final byte[] ring = new byte[RING_CAP];
    private int head = 0;   // 写位置
    private int tail = 0;   // 读位置
    private int count = 0;  // 环内字节数
    private boolean ready = false;   // 预填完成，可以起播
    private boolean eof = false;     // 上游彻底放弃（重连耗尽）
    private volatile boolean stopped = false;

    private ServerSocket server;
    private Socket client;
    private okhttp3.Call call;
    private Thread fetchThread;
    private Thread serveThread;

    /** 建本地监听并启动拉流/供数线程；失败返回 null（调用方回退直连）。 */
    public synchronized String open(String url) {
        try {
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            Log.w(TAG, "radio proxy bind failed", e);
            SessionLog.i("radio proxy bind failed: " + e);
            return null;
        }
        int port = server.getLocalPort();
        fetchThread = new Thread(fetchRun(url), "radio-fetch");
        fetchThread.start();
        serveThread = new Thread(serveRun(), "radio-serve");
        serveThread.start();
        String m = "radio proxy on 127.0.0.1:" + port + " <- " + url;
        Log.i(TAG, m);
        SessionLog.i(m);
        return "http://127.0.0.1:" + port + "/stream";
    }

    /** 停止一切（拉流线程、供数线程、连接、监听）。幂等。 */
    public void close() {
        stopped = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        try {
            if (client != null) client.close();
        } catch (IOException ignored) {
        }
        okhttp3.Call c = call;
        if (c != null) {
            try {
                c.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    // ============================ 拉流（上游 -> 环） ============================

    private Runnable fetchRun(final String url) {
        return new Runnable() {
            public void run() {
                okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build();
                for (int attempt = 0; attempt < MAX_RECONNECT && !stopped; attempt++) {
                    try {
                        okhttp3.Request req = new okhttp3.Request.Builder()
                                .url(url)
                                .header("User-Agent", "Ldyb5Client/1.0")
                                .header("Icy-MetaData", "0")
                                .build();
                        okhttp3.Call fc = c.newCall(req);
                        call = fc;
                        okhttp3.Response r = fc.execute();
                        if (r.body() == null) {
                            throw new IOException("empty body, code=" + r.code());
                        }
                        if (!r.isSuccessful()) {
                            throw new IOException("http " + r.code());
                        }
                        InputStream in = r.body().byteStream();
                        try {
                            byte[] tmp = new byte[16 * 1024];
                            int n;
                            long got = 0, t0 = System.currentTimeMillis();
                            long startedAt = System.currentTimeMillis();
                            boolean fed = false;
                            while (!stopped && (n = in.read(tmp)) > 0) {
                                ringPut(tmp, n);
                                fed = true;
                                got += n;
                                long dt = System.currentTimeMillis() - t0;
                                if (dt >= 5000) {
                                    // 设备侧拉流速率诊断：64k 台应 ≥8KB/s（含突发余量）
                                    String m = "radio fetch rate: "
                                            + (got * 1000L / dt / 1024) + " KB/s, buffered=" + count();
                                    Log.i(TAG, m);
                                    SessionLog.i(m);
                                    got = 0;
                                    t0 = System.currentTimeMillis();
                                }
                                // 拿到过数据 = 连接可用，重连预算重置
                                if (fed && attempt > 0) {
                                    attempt = 0;
                                }
                                // 有存粮时主动重连吃新连接的初始突发
                                if (System.currentTimeMillis() - startedAt >= RECYCLE_MS
                                        && count() >= PREFILL) {
                                    Log.i(TAG, "radio proactive recycle (burst harvest)");
                                    break;
                                }
                            }
                        } finally {
                            try {
                                in.close();
                            } catch (IOException ignored) {
                            }
                        }
                        if (stopped) {
                            return;
                        }
                        Log.w(TAG, "radio upstream ended, reconnect " + (attempt + 1));
                        SessionLog.i("radio upstream ended, reconnect " + (attempt + 1));
                    } catch (Throwable t) {
                        if (stopped) {
                            return;
                        }
                        Log.w(TAG, "radio fetch error (attempt " + (attempt + 1) + "): " + t);
                        SessionLog.i("radio fetch error: " + t);
                    }
                    // 重连间隔 1s（call.cancel() 会让 okhttp 读抛 IOException 走到这）
                    synchronized (lock) {
                        if (!stopped && !ready) {
                            try {
                                lock.wait(1000);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                }
                synchronized (lock) {
                    eof = true;
                    lock.notifyAll();
                }
            }
        };
    }

    // ============================ 供数（环 -> MediaPlayer） ============================

    private Runnable serveRun() {
        return new Runnable() {
            public void run() {
                try {
                    while (!stopped) {
                        Socket cl = server.accept();
                        client = cl;
                        serveOne(cl);
                        client = null;
                        if (stopped) {
                            return;
                        }
                        // MediaPlayer 探测性重连会再 accept；正常播放中不会走到这
                    }
                } catch (Throwable t) {
                    if (!stopped) {
                        Log.w(TAG, "radio serve ended: " + t);
                    }
                }
            }
        };
    }

    private void serveOne(Socket cl) throws IOException {
        // 丢弃请求头（含可能的 Range / Icy-MetaData 行）
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(cl.getInputStream(), "US-ASCII"));
            String line;
            while ((line = br.readLine()) != null && line.length() > 0) {
                // discard
            }
        } catch (Throwable ignored) {
        }
        OutputStream os = cl.getOutputStream();
        os.write(("HTTP/1.0 200 OK\r\n"
                + "Content-Type: audio/mpeg\r\n"
                + "Connection: close\r\n"
                + "\r\n").getBytes("US-ASCII"));
        os.flush();

        // 等预缓冲（或超时/上游放弃后有多少放多少）
        long t0 = System.currentTimeMillis();
        synchronized (lock) {
            while (!stopped && !ready && !eof
                    && System.currentTimeMillis() - t0 < PREPARE_TIMEOUT_MS) {
                try {
                    lock.wait(500);
                } catch (InterruptedException ignored) {
                }
            }
            ready = true; // 超时兜底：有多少放多少，别一直黑屏
            lock.notifyAll();
        }
        Log.i(TAG, "radio serve start, buffered=" + count());

        byte[] tmp = new byte[16 * 1024];
        while (!stopped) {
            int n = ringGet(tmp, 0, tmp.length);
            if (n < 0) {
                break; // 上游彻底结束
            }
            os.write(tmp, 0, n);
            os.flush();
        }
        try {
            cl.close();
        } catch (IOException ignored) {
        }
    }

    // ============================ 环形缓冲 ============================

    /** 灌数据；环满丢最旧（保直播新鲜度） */
    private void ringPut(byte[] b, int len) {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            int overflow = len - (RING_CAP - count);
            for (int i = 0; i < overflow; i++) {
                tail = (tail + 1) % RING_CAP;
                count--;
            }
            for (int i = 0; i < len; i++) {
                ring[head] = b[i];
                head = (head + 1) % RING_CAP;
            }
            count += len;
            if (!ready && count >= PREFILL) {
                ready = true;
                Log.i(TAG, "radio prefill done: " + count + " bytes");
            }
            lock.notifyAll();
        }
    }

    /** 取数据；空且未结束则阻塞等待，结束返回 -1 */
    private int ringGet(byte[] out, int off, int len) {
        synchronized (lock) {
            while (count == 0 && !eof && !stopped) {
                try {
                    lock.wait(1000);
                } catch (InterruptedException ignored) {
                }
            }
            if (count == 0) {
                return -1;
            }
            int n = Math.min(len, count);
            for (int i = 0; i < n; i++) {
                out[off + i] = ring[tail];
                tail = (tail + 1) % RING_CAP;
            }
            count -= n;
            lock.notifyAll();
            return n;
        }
    }

    private int count() {
        synchronized (lock) {
            return count;
        }
    }
}
