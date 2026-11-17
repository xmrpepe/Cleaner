// PixelForge native layer — shared helpers.
#pragma once

#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <string>

#define PF_TAG "PixelForgeNative"
#define PF_LOGI(...) __android_log_print(ANDROID_LOG_INFO, PF_TAG, __VA_ARGS__)
#define PF_LOGW(...) __android_log_print(ANDROID_LOG_WARN, PF_TAG, __VA_ARGS__)
#define PF_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PF_TAG, __VA_ARGS__)

namespace pf {

// Global cancellation flag. Every long-running benchmark polls it so the UI (or the
// thermal guard) can stop work immediately.
extern std::atomic<bool> gCancel;

inline int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Prevents the optimiser from discarding benchmark results.
template <typename T>
inline void doNotOptimize(T const& value) {
    asm volatile("" : : "r,m"(value) : "memory");
}

// Pins the calling thread to the CPUs in |mask| (bit i == cpu i). 0 = no pinning.
// Returns true when pinning succeeded. Android may restrict the allowed CPU set for
// background apps (cpusets), in which case the kernel rejects the request.
bool pinCurrentThread(uint64_t mask);

// Minimal JSON writer used for structured results returned to Kotlin.
class Json {
public:
    Json& beginObject() { sep(); out_ += '{'; first_ = true; return *this; }
    Json& endObject() { out_ += '}'; first_ = false; return *this; }
    Json& beginArray() { sep(); out_ += '['; first_ = true; return *this; }
    Json& endArray() { out_ += ']'; first_ = false; return *this; }
    Json& key(const char* k) { sep(); str(k); out_ += ':'; first_ = true; return *this; }
    Json& value(const std::string& s) { sep(); str(s.c_str()); return *this; }
    Json& value(const char* s) { sep(); str(s); return *this; }
    Json& value(bool b) { sep(); out_ += b ? "true" : "false"; return *this; }
    Json& value(int64_t v) { sep(); out_ += std::to_string(v); return *this; }
    Json& value(uint64_t v) { sep(); out_ += std::to_string(v); return *this; }
    Json& value(uint32_t v) { sep(); out_ += std::to_string(v); return *this; }
    Json& value(int32_t v) { sep(); out_ += std::to_string(v); return *this; }
    Json& value(double v) { sep(); out_ += std::to_string(v); return *this; }
    template <typename T> Json& kv(const char* k, T v) { key(k); return value(v); }
    const std::string& str() const { return out_; }

private:
    void sep() {
        if (!first_ && !out_.empty()) {
            char c = out_.back();
            if (c != '{' && c != '[' && c != ':') out_ += ',';
        }
        first_ = false;
    }
    void str(const char* s) {
        out_ += '"';
        for (; *s; ++s) {
            unsigned char c = static_cast<unsigned char>(*s);
            switch (c) {
                case '"': out_ += "\\\""; break;
                case '\\': out_ += "\\\\"; break;
                case '\n': out_ += "\\n"; break;
                case '\r': out_ += "\\r"; break;
                case '\t': out_ += "\\t"; break;
                default:
                    if (c < 0x20) {
                        char buf[8];
                        snprintf(buf, sizeof(buf), "\\u%04x", c);
                        out_ += buf;
                    } else {
                        out_ += static_cast<char>(c);
                    }
            }
        }
        out_ += '"';
    }
    std::string out_;
    bool first_ = true;
};

// Benchmarks (implemented in bench_*.cpp / vulkan_lab.cpp).
std::string runCpuBench(int kind, int threads, int durationMs, uint64_t cpuMask);
std::string runMemBandwidth(int64_t bufferBytes, int durationMs);
std::string runMemLatency(int64_t maxBytes);
std::string runStorageBench(const std::string& dir, int fileMb);
std::string vulkanProbe();
std::string vulkanComputeBench(int durationMs);
std::string vulkanFillBench(int durationMs, int width, int height);

}  // namespace pf
