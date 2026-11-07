/*
 * r1opus.c —— R1 小智客户端的 opus JNI 胶水层
 *
 * 与 src/cn/workbuddy/r1client/OpusCodec.java 中的 native 声明一一对应：
 *   nativeEncoderCreate(int sampleRate, int bitrate, int frameMs) -> long
 *   nativeEncode(long ptr, byte[] pcm, int len)                   -> byte[]
 *   nativeEncoderDestroy(long ptr)
 *   nativeDecoderCreate(int sampleRate, int channels)             -> long
 *   nativeDecode(long ptr, byte[] opus, int len)                  -> byte[]
 *   nativeDecoderDestroy(long ptr)
 *
 * 静态链接 opus（libopus.a），不依赖 liblog（用 __android_log_print 会多一个
 * 依赖；这里改用 write(2) 打到 stderr，Android 上同样进 logcat）。
 *
 * 帧长：opus 的 opus_encode 要求每包样点数必须是 2.5/5/10/20/40/60ms 之一。
 * 60ms@16kHz = 960 样点，合法。
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <opus.h>

#define R1LOG(...) do { \
    char _buf[512]; \
    int _n = snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    if (_n > 0) write(2, _buf, _n); \
} while (0)

#define MAX_OPUS_BYTES 2048

/* ============ 编码器 ============ */

JNIEXPORT jlong JNICALL
Java_cn_workbuddy_r1client_OpusCodec_nativeEncoderCreate(
        JNIEnv *env, jclass clazz, jint sampleRate, jint bitrate, jint frameMs)
{
    int err = OPUS_OK;
    OpusEncoder *enc = opus_encoder_create(sampleRate, 1, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == NULL) {
        R1LOG("[r1opus] encoder_create failed err=%d\n", err);
        return 0;
    }
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_DTX(0));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    opus_encoder_ctl(enc, OPUS_SET_LSB_DEPTH(16));
    /* VBR + 60ms：让 opus 自行选择最优码率，尖峰受 bitrate 上限约束 */
    opus_encoder_ctl(enc, OPUS_SET_EXPERT_FRAME_DURATION(OPUS_FRAMESIZE_60_MS));

    R1LOG("[r1opus] encoder created sr=%d br=%d fms=%d\n", sampleRate, bitrate, frameMs);
    return (jlong)(intptr_t)enc;
}

JNIEXPORT jbyteArray JNICALL
Java_cn_workbuddy_r1client_OpusCodec_nativeEncode(
        JNIEnv *env, jclass clazz, jlong ptr, jbyteArray pcm, jint len)
{
    OpusEncoder *enc = (OpusEncoder *)(intptr_t)ptr;
    if (enc == NULL || pcm == NULL || len <= 0) {
        return NULL;
    }
    jbyte *in = (*env)->GetByteArrayElements(env, pcm, NULL);
    if (in == NULL) {
        return NULL;
    }

    unsigned char out[MAX_OPUS_BYTES];
    /* 60ms@16k = 960 samples */
    int frameSamples = len / 2;
    int n = opus_encode(enc, (const opus_int16 *)in, frameSamples, out, sizeof(out));

    (*env)->ReleaseByteArrayElements(env, pcm, in, JNI_ABORT);

    if (n < 0) {
        R1LOG("[r1opus] encode failed n=%d\n", n);
        return NULL;
    }
    jbyteArray ret = (*env)->NewByteArray(env, n);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, n, (const jbyte *)out);
    }
    return ret;
}

JNIEXPORT void JNICALL
Java_cn_workbuddy_r1client_OpusCodec_nativeEncoderDestroy(
        JNIEnv *env, jclass clazz, jlong ptr)
{
    OpusEncoder *enc = (OpusEncoder *)(intptr_t)ptr;
    if (enc != NULL) {
        opus_encoder_destroy(enc);
    }
}

/* ============ 解码器 ============ */

JNIEXPORT jlong JNICALL
Java_cn_workbuddy_r1client_OpusCodec_nativeDecoderCreate(
        JNIEnv *env, jclass clazz, jint sampleRate, jint channels)
{
    int err = OPUS_OK;
    OpusDecoder *dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || dec == NULL) {
        R1LOG("[r1opus] decoder_create failed err=%d\n", err);
        return 0;
    }
    R1LOG("[r1opus] decoder created sr=%d ch=%d\n", sampleRate, channels);
    return (jlong)(intptr_t)dec;
}

JNIEXPORT jbyteArray JNICALL
Java_cn_workbuddy_r1client_OpusCodec_nativeDecode(
        JNIEnv *env, jclass clazz, jlong ptr, jbyteArray opus, jint len)
{
    OpusDecoder *dec = (OpusDecoder *)(intptr_t)ptr;
    if (dec == NULL || opus == NULL || len <= 0) {
        return NULL;
    }
    jbyte *in = (*env)->GetByteArrayElements(env, opus, NULL);
    if (in == NULL) {
        return NULL;
    }

    /* 最大解出 60ms@16k = 960 样点，给两倍余量 */
    opus_int16 out[1920 * 2];
    int n = opus_decode(dec, (const unsigned char *)in, len, out, 1920, 0);

    (*env)->ReleaseByteArrayElements(env, opus, in, JNI_ABORT);

    if (n < 0) {
        R1LOG("[r1opus] decode failed n=%d\n", n);
        return NULL;
    }
    int bytes = n * 2; /* mono int16 */
    jbyteArray ret = (*env)->NewByteArray(env, bytes);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, bytes, (const jbyte *)out);
    }
    return ret;
}

JNIEXPORT void JNICALL
Java_cn_workbuddy_r1client_OpusCodec_nativeDecoderDestroy(
        JNIEnv *env, jclass clazz, jlong ptr)
{
    OpusDecoder *dec = (OpusDecoder *)(intptr_t)ptr;
    if (dec != NULL) {
        opus_decoder_destroy(dec);
    }
}
