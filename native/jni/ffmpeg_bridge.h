#ifndef LIBREAMP_FFMPEG_BRIDGE_H
#define LIBREAMP_FFMPEG_BRIDGE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeOpen(
        JNIEnv *env, jobject thiz, jint fd, jstring displayName, jint targetSampleRate);

JNIEXPORT jlong JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeGetDurationUs(
        JNIEnv *env, jobject thiz, jlong handle);

JNIEXPORT jint JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeReadPcmChunk(
        JNIEnv *env, jobject thiz, jlong handle, jobject byteBuffer, jint capacity);

JNIEXPORT jboolean JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeSeekUs(
        JNIEnv *env, jobject thiz, jlong handle, jlong positionUs);

JNIEXPORT jobjectArray JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeGetTags(
        JNIEnv *env, jobject thiz, jlong handle);

JNIEXPORT jbyteArray JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeGetEmbeddedArt(
        JNIEnv *env, jobject thiz, jlong handle);

JNIEXPORT void JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeClose(
        JNIEnv *env, jobject thiz, jlong handle);

JNIEXPORT jstring JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeGetFfmpegConfig(
        JNIEnv *env, jobject thiz);

#ifdef __cplusplus
}
#endif

#endif // LIBREAMP_FFMPEG_BRIDGE_H
