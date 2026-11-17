// Memory benchmarks: streaming bandwidth (read / write / copy) and dependent-load latency
// across working-set sizes, which exposes the L1 / L2 / L3 (SLC) / DRAM hierarchy.
#include "common.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <random>
#include <vector>

namespace pf {

namespace {

void* alignedAlloc(size_t bytes) {
    void* p = nullptr;
    if (posix_memalign(&p, 4096, bytes) != 0) return nullptr;
    return p;
}

}  // namespace

std::string runMemBandwidth(int64_t bufferBytes, int durationMs) {
    if (bufferBytes < (1 << 20)) bufferBytes = 1 << 20;
    const size_t n = static_cast<size_t>(bufferBytes);
    auto* src = static_cast<uint8_t*>(alignedAlloc(n));
    auto* dst = static_cast<uint8_t*>(alignedAlloc(n));
    if (!src || !dst) {
        free(src);
        free(dst);
        Json e;
        e.beginObject().kv("error", "allocation failed").endObject();
        return e.str();
    }
    memset(src, 0x5A, n);
    memset(dst, 0xA5, n);

    const int64_t sliceNs = static_cast<int64_t>(durationMs) * 1000000LL / 3;

    // Copy (memcpy): counts bytes read + bytes written.
    int64_t bytes = 0;
    int64_t t0 = nowNs();
    while (nowNs() - t0 < sliceNs && !gCancel.load()) {
        memcpy(dst, src, n);
        doNotOptimize(dst[n - 1]);
        bytes += static_cast<int64_t>(n) * 2;
    }
    double copyGBs = static_cast<double>(bytes) / (static_cast<double>(nowNs() - t0));

    // Read: sum 64-bit words.
    bytes = 0;
    uint64_t acc = 0;
    t0 = nowNs();
    const auto* s64 = reinterpret_cast<const uint64_t*>(src);
    const size_t words = n / 8;
    while (nowNs() - t0 < sliceNs && !gCancel.load()) {
        uint64_t a0 = 0, a1 = 0, a2 = 0, a3 = 0;
        for (size_t i = 0; i + 3 < words; i += 4) {
            a0 += s64[i]; a1 += s64[i + 1]; a2 += s64[i + 2]; a3 += s64[i + 3];
        }
        acc += a0 ^ a1 ^ a2 ^ a3;
        bytes += static_cast<int64_t>(n);
    }
    doNotOptimize(acc);
    double readGBs = static_cast<double>(bytes) / (static_cast<double>(nowNs() - t0));

    // Write: memset.
    bytes = 0;
    t0 = nowNs();
    int fill = 0;
    while (nowNs() - t0 < sliceNs && !gCancel.load()) {
        memset(dst, fill++ & 0xFF, n);
        doNotOptimize(dst[0]);
        bytes += static_cast<int64_t>(n);
    }
    double writeGBs = static_cast<double>(bytes) / (static_cast<double>(nowNs() - t0));

    free(src);
    free(dst);

    Json j;
    j.beginObject()
            .kv("bufferBytes", static_cast<int64_t>(n))
            .kv("copyGBs", copyGBs)
            .kv("readGBs", readGBs)
            .kv("writeGBs", writeGBs)
            .kv("cancelled", gCancel.load())
            .endObject();
    return j.str();
}

std::string runMemLatency(int64_t maxBytes) {
    if (maxBytes < (64 << 10)) maxBytes = 64 << 10;
    constexpr size_t kLine = 64;  // bytes per node: one cache line
    struct Node { Node* next; uint8_t pad[kLine - sizeof(Node*)]; };

    Json j;
    j.beginObject().key("points").beginArray();

    std::mt19937_64 rng(42);
    for (int64_t size = 8 << 10; size <= maxBytes && !gCancel.load(); size *= 2) {
        const size_t count = static_cast<size_t>(size) / kLine;
        auto* nodes = static_cast<Node*>(alignedAlloc(count * sizeof(Node)));
        if (!nodes) break;
        // Build a random single cycle (Sattolo's algorithm) to defeat prefetchers.
        std::vector<uint32_t> order(count);
        for (size_t i = 0; i < count; ++i) order[i] = static_cast<uint32_t>(i);
        for (size_t i = count - 1; i > 0; --i) {
            std::uniform_int_distribution<size_t> dist(0, i - 1);
            std::swap(order[i], order[dist(rng)]);
        }
        for (size_t i = 0; i < count; ++i) {
            nodes[order[i]].next = &nodes[order[(i + 1) % count]];
        }
        // Warm up, then time a fixed number of dependent loads.
        Node* p = &nodes[0];
        for (size_t i = 0; i < count; ++i) p = p->next;
        const int64_t loads = std::max<int64_t>(2'000'000, static_cast<int64_t>(count) * 4);
        const int64_t t0 = nowNs();
        for (int64_t i = 0; i < loads; i += 8) {
            p = p->next; p = p->next; p = p->next; p = p->next;
            p = p->next; p = p->next; p = p->next; p = p->next;
        }
        const double ns = static_cast<double>(nowNs() - t0) / static_cast<double>(loads);
        doNotOptimize(p);
        free(nodes);
        j.beginObject().kv("bytes", size).kv("ns", ns).endObject();
    }
    j.endArray().kv("cancelled", gCancel.load()).endObject();
    return j.str();
}

}  // namespace pf
