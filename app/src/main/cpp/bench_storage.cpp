// Storage benchmark on the app's private storage (UFS 3.1 on Pixel 9).
// Sequential write (with fsync), sequential read and random 4 KiB reads. Reads use
// O_DIRECT where the filesystem supports it so the page cache does not inflate results;
// the result reports whether direct I/O was actually used.
#include "common.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <random>
#include <sys/stat.h>
#include <unistd.h>

namespace pf {

std::string runStorageBench(const std::string& dir, int fileMb) {
    if (fileMb < 16) fileMb = 16;
    if (fileMb > 2048) fileMb = 2048;
    const std::string path = dir + "/pf_storage_bench.bin";
    const size_t chunk = 1 << 20;  // 1 MiB
    const int64_t total = static_cast<int64_t>(fileMb) * chunk;

    void* buf = nullptr;
    if (posix_memalign(&buf, 4096, chunk) != 0) {
        Json e; e.beginObject().kv("error", "allocation failed").endObject(); return e.str();
    }
    auto* bytes = static_cast<uint8_t*>(buf);
    std::mt19937 rng(7);
    for (size_t i = 0; i < chunk; ++i) bytes[i] = static_cast<uint8_t>(rng());

    Json j;
    j.beginObject().kv("fileMb", static_cast<int32_t>(fileMb));

    // --- Sequential write + fsync.
    int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) {
        free(buf);
        j.kv("error", std::string("open failed: ") + strerror(errno)).endObject();
        return j.str();
    }
    int64_t t0 = nowNs();
    int64_t written = 0;
    while (written < total && !gCancel.load()) {
        ssize_t w = write(fd, bytes, chunk);
        if (w <= 0) break;
        written += w;
    }
    fsync(fd);
    close(fd);
    const double writeMBs = (static_cast<double>(written) / (1 << 20)) /
                            (static_cast<double>(nowNs() - t0) / 1e9);
    j.kv("seqWriteMBs", writeMBs);

    // --- Sequential read (direct I/O when possible).
    bool direct = true;
    fd = open(path.c_str(), O_RDONLY | O_DIRECT | O_CLOEXEC);
    if (fd < 0) {
        direct = false;
        fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    }
    double readMBs = 0;
    if (fd >= 0) {
        t0 = nowNs();
        int64_t readTotal = 0;
        while (!gCancel.load()) {
            ssize_t r = read(fd, bytes, chunk);
            if (r <= 0) break;
            readTotal += r;
        }
        readMBs = (static_cast<double>(readTotal) / (1 << 20)) /
                  (static_cast<double>(nowNs() - t0) / 1e9);

        // --- Random 4 KiB reads for ~2 seconds.
        const int64_t blocks = written / 4096;
        std::uniform_int_distribution<int64_t> dist(0, blocks > 0 ? blocks - 1 : 0);
        int64_t ops = 0;
        t0 = nowNs();
        while (nowNs() - t0 < 2'000'000'000LL && !gCancel.load()) {
            off_t off = static_cast<off_t>(dist(rng)) * 4096;
            if (pread(fd, bytes, 4096, off) != 4096) break;
            ++ops;
        }
        const double secs = static_cast<double>(nowNs() - t0) / 1e9;
        j.kv("rand4kReadIops", ops / secs);
        close(fd);
    }
    j.kv("seqReadMBs", readMBs).kv("directIo", direct);

    unlink(path.c_str());
    free(buf);
    j.kv("cancelled", gCancel.load()).endObject();
    return j.str();
}

}  // namespace pf
