package cn.workbuddy.ldyb5app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * v1.3.7：麦克风配置探针矩阵。
 *
 * 背景：2026-10-04 噪声案 —— USC（小讯）经 AudioFlinger 开 16k/2ch 输入流
 * 拿到干净语音，我们 app 用 VOICE_RECOGNITION/stereo 开流拿到悬空底噪
 * （RMS 500 直线、0x00+0xff 占 51%）。两方表面参数相同（16k/2ch），
 * 差异必在 AudioSource -> HAL 输入路由映射（或并发开流互踩）。
 *
 * 本探针把候选配置逐一实测，每个配置：开 AudioRecord -> 采 4s -> 关闭
 * -> 落盘 files/micprobe_<tag>.wav + RMS/peak/静音占比直接进日志
 * （不拉文件即可判读）。
 *
 * 触发：files/micprobe flag 文件存在 -> 服务启动后跑探针，替代 KWS 装配，
 * 结束后保持 bus-only（不占麦，把输入让给 USC）。
 *
 * 健壮性：blocking read 卡死（HAL 坏流）由独立 worker 线程 + join 超时兜底，
 * 超时即跳过该配置（泄漏的 worker 线程无害，进程不挂）。
 */
public final class MicProbe {
    private static final String TAG = "L5CLIENT";
    private static final int RATE = 16000;
    private static final int SECONDS = 4;
    private static final int JOIN_MS = SECONDS * 1000 + 3000;
    /** 静音判定阈值（悬空底噪 |v| 几乎全 < 100） */
    private static final int SILENCE_TH = 100;

    private MicProbe() {}

    /** 单配置结果 */
    private static class Result {
        boolean opened;
        boolean incomplete;   // 4s 没采满 = read 停滞
        int readErrs;
        int bytes;
        double rms;
        int peak;
        double silencePct;
    }

    /** 服务侧入口：起探针线程，立即返回 */
    public static void runAsync(final File dir) {
        Thread t = new Thread(new Runnable() {
            public void run() { MicProbe.run(dir); }
        }, "micprobe");
        t.setDaemon(true);
        t.start();
    }

    private static void run(final File dir) {
        Object[][] cfgs = new Object[][] {
            {"def_mono",  Integer.valueOf(MediaRecorder.AudioSource.DEFAULT),
                          Integer.valueOf(AudioFormat.CHANNEL_IN_MONO)},
            {"mic_mono",  Integer.valueOf(MediaRecorder.AudioSource.MIC),
                          Integer.valueOf(AudioFormat.CHANNEL_IN_MONO)},
            {"mic_st",    Integer.valueOf(MediaRecorder.AudioSource.MIC),
                          Integer.valueOf(AudioFormat.CHANNEL_IN_STEREO)},
            {"vrec_mono", Integer.valueOf(MediaRecorder.AudioSource.VOICE_RECOGNITION),
                          Integer.valueOf(AudioFormat.CHANNEL_IN_MONO)},
            {"vrec_st",   Integer.valueOf(MediaRecorder.AudioSource.VOICE_RECOGNITION),
                          Integer.valueOf(AudioFormat.CHANNEL_IN_STEREO)},
            {"cam_st",    Integer.valueOf(MediaRecorder.AudioSource.CAMCORDER),
                          Integer.valueOf(AudioFormat.CHANNEL_IN_STEREO)},
            {"vcomm_st",  Integer.valueOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
                          Integer.valueOf(AudioFormat.CHANNEL_IN_STEREO)},
        };
        SessionLog.i("micprobe: start, " + cfgs.length + " configs x " + SECONDS + "s");
        Log.i(TAG, "micprobe: start");

        for (int i = 0; i < cfgs.length; i++) {
            final String tag = (String) cfgs[i][0];
            final int src = ((Integer) cfgs[i][1]).intValue();
            final int ch = ((Integer) cfgs[i][2]).intValue();

            final Result[] out = new Result[1];
            Thread w = new Thread(new Runnable() {
                public void run() { out[0] = probeOne(dir, tag, src, ch); }
            }, "micprobe-" + tag);
            w.setDaemon(true);
            w.start();
            try { w.join(JOIN_MS); } catch (InterruptedException e) { /* ignore */ }

            Result r = out[0];
            if (r == null) {
                // worker 卡在 blocking read，join 超时
                SessionLog.i("micprobe[" + tag + "] TIMEOUT (worker blocked) -> skip");
                Log.w(TAG, "micprobe[" + tag + "] TIMEOUT");
            } else if (!r.opened) {
                SessionLog.i("micprobe[" + tag + "] OPEN FAILED (busy/invalid) errs=" + r.readErrs);
                Log.w(TAG, "micprobe[" + tag + "] OPEN FAILED");
            } else if (r.incomplete) {
                SessionLog.i("micprobe[" + tag + "] INCOMPLETE " + r.bytes + "B errs=" + r.readErrs
                        + " rms=" + fmt(r.rms) + " peak=" + r.peak + " silence%=" + fmt(r.silencePct));
            } else {
                SessionLog.i("micprobe[" + tag + "] rms=" + fmt(r.rms) + " peak=" + r.peak
                        + " silence%=" + fmt(r.silencePct) + " bytes=" + r.bytes);
                Log.i(TAG, "micprobe[" + tag + "] rms=" + fmt(r.rms) + " peak=" + r.peak
                        + " silence%=" + fmt(r.silencePct));
            }
            sleep(800); // 让 AudioFlinger 完全释放上一条流
        }
        SessionLog.i("micprobe: done -> staying bus-only (mic left to unisound)");
        Log.i(TAG, "micprobe: done");
    }

    /** 单配置采集，绝不允许抛异常/永久阻塞（调用方有 join 兜底） */
    private static Result probeOne(File dir, String tag, int source, int channelCfg) {
        Result r = new Result();
        int chCount = (channelCfg == AudioFormat.CHANNEL_IN_MONO) ? 1 : 2;
        AudioRecord rec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(RATE, channelCfg, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                Log.w(TAG, "micprobe[" + tag + "] minBufferSize=" + minBuf);
                return r;
            }
            rec = new AudioRecord(source, RATE, channelCfg,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "micprobe[" + tag + "] state=" + rec.getState() + " (not INITIALIZED)");
                return r;
            }
            r.opened = true;
            rec.startRecording();

            int frameBytes = 2 * chCount;
            final byte[] buf = new byte[RATE * frameBytes * SECONDS];
            long deadline = SystemClock.elapsedRealtime() + (SECONDS + 2) * 1000L;
            int total = 0;
            while (total < buf.length && SystemClock.elapsedRealtime() < deadline) {
                int n = rec.read(buf, total, buf.length - total);
                if (n <= 0) {
                    r.readErrs++;
                    if (r.readErrs > 100) break;
                } else {
                    total += n;
                }
            }
            r.bytes = total;
            r.incomplete = (total < buf.length);
            if (total <= 0) return r;

            // 统计：rms / peak / 静音占比（16bit LE）
            long sum2 = 0L;
            int peak = 0, silent = 0, n = total >> 1;
            for (int i = 0; i + 1 < total; i += 2) {
                int v = (short) (((buf[i + 1] & 0xff) << 8) | (buf[i] & 0xff));
                int a = v < 0 ? -v : v;
                sum2 += (long) v * v;
                if (a > peak) peak = a;
                if (a < SILENCE_TH) silent++;
            }
            r.rms = n > 0 ? Math.sqrt((double) sum2 / n) : 0d;
            r.peak = peak;
            r.silencePct = n > 0 ? 100.0 * silent / n : 0d;

            int dataLen = total & ~1; // 对齐 16bit 样本
            writeWav(new File(dir, "micprobe_" + tag + ".wav"), buf, dataLen, chCount);
        } catch (Throwable t) {
            Log.w(TAG, "micprobe[" + tag + "] exception: " + t);
            SessionLog.i("micprobe[" + tag + "] exception: " + t);
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignore) {}
                try { rec.release(); } catch (Throwable ignore) {}
            }
        }
        return r;
    }

    /** 最小 WAV (RIFF/PCM16) 写盘 */
    private static void writeWav(File f, byte[] pcm, int dataLen, int channels) throws IOException {
        int byteRate = RATE * channels * 2;
        FileOutputStream fo = new FileOutputStream(f);
        try {
            byte[] h = new byte[44];
            h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
            int riff = 36 + dataLen;
            h[4] = (byte) riff; h[5] = (byte) (riff >> 8); h[6] = (byte) (riff >> 16); h[7] = (byte) (riff >> 24);
            h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
            h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
            h[16] = 16; h[17] = 0; h[18] = 0; h[19] = 0;
            h[20] = 1; h[21] = 0;               // PCM
            h[22] = (byte) channels; h[23] = 0;
            h[24] = (byte) RATE; h[25] = (byte) (RATE >> 8); h[26] = (byte) (RATE >> 16); h[27] = (byte) (RATE >> 24);
            h[28] = (byte) byteRate; h[29] = (byte) (byteRate >> 8); h[30] = (byte) (byteRate >> 16); h[31] = (byte) (byteRate >> 24);
            h[32] = (byte) (channels * 2); h[33] = 0;
            h[34] = 16; h[35] = 0;              // bits
            h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
            h[40] = (byte) dataLen; h[41] = (byte) (dataLen >> 8); h[42] = (byte) (dataLen >> 16); h[43] = (byte) (dataLen >> 24);
            fo.write(h);
            fo.write(pcm, 0, dataLen);
        } finally {
            try { fo.close(); } catch (IOException ignore) {}
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { /* ignore */ }
    }

    private static String fmt(double d) {
        if (d >= 1000) return String.valueOf((long) d);
        return String.valueOf((long) (d * 10) / 10.0);
    }
}
