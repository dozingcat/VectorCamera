// On-device benchmark for contour_effect_native.cpp, run as a standalone arm64 executable so the
// native code can be measured without the app. Each frame mimics ContourEffect.createBitmap:
// allocate a zeroed pixel array, run the effect, then allocate a "bitmap" and copy the pixels
// into it. Reports averages over the last `frames` after a warmup, plus which core each thread
// last ran on.
//
// Build and run (from the repository root):
//   NDK=~/Library/Android/sdk/ndk/29.0.13599879/toolchains/llvm/prebuilt/darwin-x86_64
//   $NDK/bin/clang++ --target=aarch64-linux-android33 -std=c++17 -O3 -ffast-math -funroll-loops \
//       -static-libstdc++ -landroid -DVC_NO_JNI app/src/test/cpp/contour_device_bench.cpp \
//       -o /tmp/contour_bench
//   adb push /tmp/contour_bench /data/local/tmp/ && adb shell /data/local/tmp/contour_bench 1920 1080 2 all
//
// Usage: contour_bench <width> <height> <threads> <affinity: all|big|mid|little|bigmid>
//                      [frames=20] [warmup=20] [pace_ms=15] [adpf=0]
// Affinity is applied to the process before any work, so the pool workers inherit it. With
// adpf=1 a performance hint session (Android 12+ ADPF) covering every thread is opened after the
// warmup, with a 16.6 ms target, and the actual frame duration is reported each frame.

#define _GNU_SOURCE 1
#include "../../main/cpp/contour_effect_native.cpp"

#include <android/performance_hint.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <sched.h>
#include <string>
#include <unistd.h>

namespace {

using Clock = std::chrono::steady_clock;

double millisSince(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

long maxFreqKhz(int cpu) {
    char path[128];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE* f = fopen(path, "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

long curFreqKhz(int cpu) {
    char path[128];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", cpu);
    FILE* f = fopen(path, "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

// Classifies cores by max frequency: "big" is the top frequency, "little" the bottom, "mid" the rest.
void applyAffinity(const std::string& which) {
    if (which == "all") return;
    int n = sysconf(_SC_NPROCESSORS_CONF);
    long lo = 1L << 40, hi = 0;
    for (int i = 0; i < n; i++) {
        long f = maxFreqKhz(i);
        if (f <= 0) continue;
        lo = std::min(lo, f);
        hi = std::max(hi, f);
    }
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < n; i++) {
        long f = maxFreqKhz(i);
        bool isBig = f == hi, isLittle = f == lo, isMid = !isBig && !isLittle;
        if ((which == "big" && isBig) || (which == "little" && isLittle) ||
            (which == "mid" && isMid) || (which == "bigmid" && !isLittle)) {
            CPU_SET(i, &set);
        }
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        perror("sched_setaffinity");
    }
}

std::vector<int32_t> threadIds() {
    std::vector<int32_t> tids;
    if (DIR* dir = opendir("/proc/self/task")) {
        while (dirent* e = readdir(dir)) {
            if (e->d_name[0] != '.') tids.push_back(atoi(e->d_name));
        }
        closedir(dir);
    }
    return tids;
}

// Last CPU each thread of this process ran on, from /proc/self/task/*/stat field 39.
void printThreadCpus() {
    DIR* dir = opendir("/proc/self/task");
    if (!dir) return;
    printf("  threads:");
    while (dirent* e = readdir(dir)) {
        if (e->d_name[0] == '.') continue;
        char path[256], comm[64] = "?";
        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", e->d_name);
        if (FILE* f = fopen(path, "r")) {
            if (fgets(comm, sizeof(comm), f)) comm[strcspn(comm, "\n")] = 0;
            fclose(f);
        }
        snprintf(path, sizeof(path), "/proc/self/task/%s/stat", e->d_name);
        int cpu = -1;
        if (FILE* f = fopen(path, "r")) {
            char buf[1024];
            if (fgets(buf, sizeof(buf), f)) {
                // Skip past the parenthesized comm, then field 39 is the 37th after it.
                char* p = strrchr(buf, ')');
                int field = 2;
                for (char* tok = strtok(p + 1, " "); tok; tok = strtok(nullptr, " ")) {
                    field++;
                    if (field == 39) { cpu = atoi(tok); break; }
                }
            }
            fclose(f);
        }
        printf(" %s@cpu%d", comm, cpu);
    }
    closedir(dir);
    printf("\n");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 5) {
        fprintf(stderr, "usage: %s width height threads affinity [frames] [warmup] [pace_ms]\n", argv[0]);
        return 2;
    }
    const int width = atoi(argv[1]), height = atoi(argv[2]), threads = atoi(argv[3]);
    const std::string affinity = argv[4];
    const int frames = argc > 5 ? atoi(argv[5]) : 20;
    const int warmup = argc > 6 ? atoi(argv[6]) : 20;
    const int paceMs = argc > 7 ? atoi(argv[7]) : 15;
    const bool useAdpf = argc > 8 && atoi(argv[8]) != 0;
    APerformanceHintSession* hintSession = nullptr;
    const int64_t targetNanos = 16600000;
    applyAffinity(affinity);

    const size_t numPixels = static_cast<size_t>(width) * height;
    std::vector<uint8_t> y(numPixels);
    for (size_t i = 0; i < numPixels; i++) y[i] = (i * 7 + (i / width) * 3) & 0xFF;
    const int levels = 8;
    std::vector<int32_t> fill(levels, 0xFF112233);

    double processTotal = 0, bitmapTotal = 0, allocTotal = 0;
    volatile int32_t sink = 0;
    for (int frame = 0; frame < warmup + frames; frame++) {
        if (useAdpf && frame == warmup) {
            // All threads exist by now; put the caller and the pool workers in one session.
            APerformanceHintManager* manager = APerformanceHint_getManager();
            std::vector<int32_t> tids = threadIds();
            hintSession = manager ? APerformanceHint_createSession(manager, tids.data(), tids.size(), targetNanos) : nullptr;
            if (!hintSession) fprintf(stderr, "ADPF session unavailable (manager=%p, %zu threads)\n", manager, tids.size());
        }
        auto t0 = Clock::now();
        // Zeroed allocation, like a Kotlin IntArray.
        int32_t* pixels = static_cast<int32_t*>(calloc(numPixels, sizeof(int32_t)));
        double allocMs = millisSince(t0);
        auto t1 = Clock::now();
        contour::processImage(y.data(), width, height, levels, 2, 2, fill.data(), 0xFF000000,
                              pixels, width, threads);
        double processMs = millisSince(t1);
        auto t2 = Clock::now();
        // Bitmap.createBitmap + setPixels: a second buffer and a full copy.
        int32_t* bitmap = static_cast<int32_t*>(malloc(numPixels * sizeof(int32_t)));
        memcpy(bitmap, pixels, numPixels * sizeof(int32_t));
        sink += bitmap[numPixels / 2];
        double bitmapMs = millisSince(t2);
        free(bitmap);
        free(pixels);
        if (hintSession) {
            APerformanceHint_reportActualWorkDuration(
                    hintSession, static_cast<int64_t>(millisSince(t0) * 1e6));
        }
        if (frame >= warmup) {
            allocTotal += allocMs;
            processTotal += processMs;
            bitmapTotal += bitmapMs;
        }
        if (paceMs > 0) usleep(paceMs * 1000);
    }
    printf("%dx%d threads=%d affinity=%-6s adpf=%d alloc=%.1fms process=%.1fms bitmap=%.1fms  caller@cpu%d (%ld MHz)\n",
           width, height, threads, affinity.c_str(), hintSession != nullptr, allocTotal / frames, processTotal / frames,
           bitmapTotal / frames, sched_getcpu(), curFreqKhz(sched_getcpu()) / 1000);
    printThreadCpus();
    if (hintSession) APerformanceHint_closeSession(hintSession);
    return sink == 12345 ? 1 : 0;
}
