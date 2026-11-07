package cn.workbuddy.ldyb5app;

import android.util.Log;

/**
 * Opus 编解码 JNI 封装。
 *
 * 上行：16kHz / mono / 60ms 帧 → CBR 24kbps（与官方客户端一致）
 * 下行：服务器下发裸 opus 包，按 16kHz mono 解码
 *
 * 说明：native 层需链接 libopus，so 放 jniLibs/armeabi-v7a/libopus.so。
 */
public final class OpusCodec {

    private static final String TAG = "L5CLIENT";

    static {
        try {
            System.loadLibrary("ld5opus");
        } catch (Throwable t) {
            Log.e(TAG, "loadLibrary ld5opus failed", t);
        }
    }

    // ---- 上行编码器 ----
    private long encPtr;

    public synchronized boolean openEncoder() {
        if (encPtr != 0) {
            return true;
        }
        encPtr = nativeEncoderCreate(AudioCapture.SAMPLE_RATE, 24000, 60);
        Log.i(TAG, "opus encoder = " + encPtr);
        return encPtr != 0;
    }

    /**
     * 编码一帧 60ms PCM（1920 字节）→ opus 包。
     * @return opus 字节数组，失败返回 null
     */
    public synchronized byte[] encode(byte[] pcm, int len) {
        if (encPtr == 0) {
            return null;
        }
        return nativeEncode(encPtr, pcm, len);
    }

    public synchronized void closeEncoder() {
        if (encPtr != 0) {
            nativeEncoderDestroy(encPtr);
            encPtr = 0;
        }
    }

    // ---- 下行解码器 ----
    private long decPtr;

    public synchronized boolean openDecoder() {
        if (decPtr != 0) {
            return true;
        }
        decPtr = nativeDecoderCreate(AudioCapture.SAMPLE_RATE, 1);
        Log.i(TAG, "opus decoder = " + decPtr);
        return decPtr != 0;
    }

    /**
     * 解码一个 opus 包 → PCM（16bit LE）。
     */
    public synchronized byte[] decode(byte[] opus, int len) {
        if (decPtr == 0) {
            return null;
        }
        return nativeDecode(decPtr, opus, len);
    }

    public synchronized void closeDecoder() {
        if (decPtr != 0) {
            nativeDecoderDestroy(decPtr);
            decPtr = 0;
        }
    }

    // ---- native ----
    private static native long nativeEncoderCreate(int sampleRate, int bitrate, int frameMs);

    private static native byte[] nativeEncode(long ptr, byte[] pcm, int len);

    private static native void nativeEncoderDestroy(long ptr);

    private static native long nativeDecoderCreate(int sampleRate, int channels);

    private static native byte[] nativeDecode(long ptr, byte[] opus, int len);

    private static native void nativeDecoderDestroy(long ptr);
}
