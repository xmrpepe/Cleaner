// JNI entry points for dev.pixelforge.core.nativebridge.NativeLab.
#include <jni.h>
#include <string>

#include "common.h"

namespace {

jstring toJ(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

std::string fromJ(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

#define PF_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_dev_pixelforge_core_nativebridge_NativeLab_##name

PF_JNI(void, nativeSetCancelled)(JNIEnv*, jobject, jboolean cancelled) {
    pf::gCancel.store(cancelled == JNI_TRUE);
}

PF_JNI(jstring, nativeCpuBench)(JNIEnv* env, jobject, jint kind, jint threads, jint durationMs, jlong cpuMask) {
    return toJ(env, pf::runCpuBench(kind, threads, durationMs, static_cast<uint64_t>(cpuMask)));
}

PF_JNI(jstring, nativeMemBandwidth)(JNIEnv* env, jobject, jlong bufferBytes, jint durationMs) {
    return toJ(env, pf::runMemBandwidth(bufferBytes, durationMs));
}

PF_JNI(jstring, nativeMemLatency)(JNIEnv* env, jobject, jlong maxBytes) {
    return toJ(env, pf::runMemLatency(maxBytes));
}

PF_JNI(jstring, nativeStorageBench)(JNIEnv* env, jobject, jstring dir, jint fileMb) {
    return toJ(env, pf::runStorageBench(fromJ(env, dir), fileMb));
}

PF_JNI(jstring, nativeVulkanProbe)(JNIEnv* env, jobject) {
    return toJ(env, pf::vulkanProbe());
}

PF_JNI(jstring, nativeVulkanCompute)(JNIEnv* env, jobject, jint durationMs) {
    return toJ(env, pf::vulkanComputeBench(durationMs));
}

PF_JNI(jstring, nativeVulkanFill)(JNIEnv* env, jobject, jint durationMs, jint width, jint height) {
    return toJ(env, pf::vulkanFillBench(durationMs, width, height));
}
