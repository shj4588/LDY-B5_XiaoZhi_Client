package com.unisound.jni;

/**
 * v1.3.23：原厂「优声四麦阵列 HAL」的 JNI 门面 —— **逐字节复刻**自设备上的
 * 原厂应用 `com.phicomm.speaker.device`（/system/app/Unisound/Unisound.apk，
 * 版本 V3.2.5.2-，android 逆向所得）。
 *
 * 为什么要在我们包里重建这个类：
 *   JNI 的符号名是 `Java_com_unisound_jni_Uni4micHalJNI_<method>`，
 *   绑死「包名 + 类名 + 方法名 + 签名」。所以只要我们的类同名同签名，
 *   `System.loadLibrary("Uni4micHalJNI")` 就能直接加载 /system/lib 里那个
 *   原厂 so，拿到**与原厂完全相同的四麦波束成形/降噪/AGC 输出**。
 *
 * 原厂实际用法（反汇编 `Lcom/unisound/sdk/x;` 还原，见 R1/patchwork/uni4mic/）：
 *   <clinit>      : System.loadLibrary("Uni4micHalJNI")
 *   getInstance() : 单例
 *   init(1)       : initHal(1)  -> 0 = OK，置 b=true
 *   set4MicDebugMode(1)
 *   close4MicAlgorithm(0)      -> 0 = **保留**四麦算法（不是关掉）
 *   set4MicWakeUpStatus(0)
 *   openAudioIn(2)             -> 返回 long 句柄（<0 = 失败）
 *   startRecorder(handle)
 *   循环 readData(handle, buf, buf.length)   buf = 2400 字节（原厂 s=1200 样点 ×2
 *                                            = 75ms @16k mono PCM16）
 *   stopRecorder(handle); closeAudioIn(handle); release()
 *
 * 失败一律返回负值/0，调用方必须能降级（AudioCapture 失败即回落 AudioRecord）。
 */
public class Uni4micHalJNI {

    /** 原厂单例（字段名 a / b 也照抄，防原生侧按名字取字段） */
    private static Uni4micHalJNI a;
    private boolean b;

    static {
        System.loadLibrary("Uni4micHalJNI");
    }

    private Uni4micHalJNI() {
    }

    public static Uni4micHalJNI getInstance() {
        if (a == null) {
            a = new Uni4micHalJNI();
        }
        return a;
    }

    /** 幂等：已 init 过直接返回 0 */
    public int init(int boardType) {
        if (b) {
            return 0;
        }
        int r = initHal(boardType);
        if (r == 0) {
            b = true;
        }
        return r;
    }

    public int release() {
        int r = -1;
        if (b) {
            r = releaseHal();
            if (r == 0) {
                b = false;
            }
        }
        return r;
    }

    private native int initHal(int boardType);

    private native int releaseHal();

    public native long openAudioIn(int mode);

    public native int closeAudioIn(long handle);

    public native int startRecorder(long handle);

    public native int stopRecorder(long handle);

    public native int readData(long handle, byte[] buf, int len);

    public native int close4MicAlgorithm(int flag);

    public native String get4MicBoardVersion();

    public native int get4MicDoaResult();

    public native int get4MicOneShotReady();

    public native int set4MicDebugMode(int mode);

    public native int set4MicDelayTime(int ms);

    public native int set4MicOneShotReady(int v);

    public native int set4MicOneShotStartLen(int v);

    public native int set4MicUtteranceTimeLen(int v);

    public native int set4MicWakeUpStatus(int v);

    public native int set4MicWakeupStartLen(int v);
}
