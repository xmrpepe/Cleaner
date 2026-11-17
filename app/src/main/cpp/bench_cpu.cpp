// CPU benchmarks: integer mixing, FP32 matrix multiply and branch-heavy sorting.
// Each kernel runs for a fixed wall-clock window and reports throughput so results are
// comparable across runs regardless of clock behaviour (which is what we want to observe).
#include "common.h"

#include <algorithm>
#include <cmath>
#include <sched.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace pf {

std::atomic<bool> gCancel{false};

bool pinCurrentThread(uint64_t mask) {
    if (mask == 0) return true;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64; ++i) {
        if (mask & (1ULL << i)) CPU_SET(i, &set);
    }
    return sched_setaffinity(0, sizeof(set), &set) == 0;
}

namespace {

constexpr int kKindFloat = 1;
constexpr int kKindSort = 2;

// --- Integer: 64-bit hash mixing (multiply/xor/rotate), 4 independent lanes for ILP.
// One "op" = one mixed 64-bit word.
double integerKernel(int64_t deadline, uint64_t seed) {
    uint64_t a = seed | 1, b = seed * 3 + 7, c = seed ^ 0x9E3779B97F4A7C15ULL, d = ~seed;
    int64_t ops = 0;
    while (true) {
        for (int i = 0; i < 4096; ++i) {
            a = (a ^ (a >> 31)) * 0x7FB5D329728EA185ULL; a = (a << 27) | (a >> 37);
            b = (b ^ (b >> 29)) * 0x81DADEF4BC2DD44DULL; b = (b << 23) | (b >> 41);
            c = (c ^ (c >> 33)) * 0xD6E8FEB86659FD93ULL; c = (c << 19) | (c >> 45);
            d = (d ^ (d >> 27)) * 0x94D049BB133111EBULL; d = (d << 17) | (d >> 47);
        }
        ops += 4096 * 4;
        if (nowNs() >= deadline || gCancel.load(std::memory_order_relaxed)) break;
    }
    doNotOptimize(a ^ b ^ c ^ d);
    return static_cast<double>(ops);
}

// --- Float: blocked 64x64 SGEMM. One "op" = one floating-point operation (FMA = 2).
double floatKernel(int64_t deadline, uint64_t seed) {
    constexpr int N = 64;
    alignas(64) static thread_local float A[N * N], B[N * N], C[N * N];
    for (int i = 0; i < N * N; ++i) {
        A[i] = static_cast<float>((i * 7 + seed) % 13) * 0.01f;
        B[i] = static_cast<float>((i * 3 + seed) % 11) * 0.02f;
        C[i] = 0.f;
    }
    int64_t flops = 0;
    while (true) {
        for (int i = 0; i < N; ++i) {
            for (int k = 0; k < N; ++k) {
                const float aik = A[i * N + k];
                float* __restrict crow = &C[i * N];
                const float* __restrict brow = &B[k * N];
                for (int j = 0; j < N; ++j) crow[j] += aik * brow[j];
            }
        }
        flops += 2LL * N * N * N;
        // Keep values bounded.
        C[0] *= 0.5f;
        if (nowNs() >= deadline || gCancel.load(std::memory_order_relaxed)) break;
    }
    doNotOptimize(C[N * N - 1]);
    return static_cast<double>(flops);
}

// --- Sort: unpredictable-branch workload (std::sort of 16K pseudo-random ints).
// One "op" = one element sorted.
double sortKernel(int64_t deadline, uint64_t seed) {
    constexpr int N = 16384;
    std::vector<uint32_t> v(N);
    uint64_t x = seed | 1;
    int64_t elements = 0;
    while (true) {
        for (int i = 0; i < N; ++i) {
            x ^= x << 13; x ^= x >> 7; x ^= x << 17;
            v[i] = static_cast<uint32_t>(x);
        }
        std::sort(v.begin(), v.end());
        elements += N;
        if (nowNs() >= deadline || gCancel.load(std::memory_order_relaxed)) break;
    }
    doNotOptimize(v[N / 2]);
    return static_cast<double>(elements);
}

double runKernel(int kind, int64_t deadline, uint64_t seed) {
    switch (kind) {
        case kKindFloat: return floatKernel(deadline, seed);
        case kKindSort: return sortKernel(deadline, seed);
        default: return integerKernel(deadline, seed);
    }
}

}  // namespace

std::string runCpuBench(int kind, int threads, int durationMs, uint64_t cpuMask) {
    if (threads < 1) threads = 1;
    if (threads > 64) threads = 64;
    std::vector<double> results(threads, 0.0);
    std::vector<int> pinned(threads, 0);
    std::vector<int> cpuSeen(threads, -1);

    const int64_t start = nowNs();
    const int64_t deadline = start + static_cast<int64_t>(durationMs) * 1000000LL;

    auto worker = [&](int idx) {
        pinned[idx] = pinCurrentThread(cpuMask) ? 1 : 0;
        results[idx] = runKernel(kind, deadline, 0x1234567ULL + idx * 7919ULL);
        cpuSeen[idx] = sched_getcpu();
    };

    if (threads == 1) {
        // Run on the calling thread to avoid creation overhead for single-thread tests.
        worker(0);
    } else {
        std::vector<std::thread> pool;
        pool.reserve(threads);
        for (int i = 0; i < threads; ++i) pool.emplace_back(worker, i);
        for (auto& t : pool) t.join();
    }
    const double seconds = static_cast<double>(nowNs() - start) / 1e9;

    double total = 0;
    for (double r : results) total += r;

    Json j;
    j.beginObject()
            .kv("kind", static_cast<int32_t>(kind))
            .kv("threads", static_cast<int32_t>(threads))
            .kv("seconds", seconds)
            .kv("opsPerSecond", total / seconds)
            .kv("cancelled", gCancel.load());
    j.key("perThread").beginArray();
    for (int i = 0; i < threads; ++i) {
        j.beginObject()
                .kv("opsPerSecond", results[i] / seconds)
                .kv("pinned", pinned[i] == 1)
                .kv("lastCpu", static_cast<int32_t>(cpuSeen[i]))
                .endObject();
    }
    j.endArray().endObject();
    return j.str();
}

}  // namespace pf
