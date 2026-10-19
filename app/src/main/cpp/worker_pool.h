// Persistent worker threads and dynamic row-chunk scheduling for native effects.
#ifndef VECTORCAMERA_WORKER_POOL_H
#define VECTORCAMERA_WORKER_POOL_H

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#ifdef __ANDROID__
#include <pthread.h>
#include <unistd.h>
#endif

namespace vc {

// Persistent worker threads shared by all frames. Threads that are created and destroyed within
// a frame never build up scheduler load history and tend to be placed on slow cores; long-lived
// workers that process every frame get migrated to fast cores. Jobs run one at a time.
class WorkerPool {
public:
    static WorkerPool& instance() {
        static WorkerPool pool;
        return pool;
    }

    // Kernel thread IDs of the workers created so far, for performance hint sessions.
    std::vector<int32_t> threadIds() {
        std::lock_guard<std::mutex> lock(mutex_);
        return threadIds_;
    }

    // Runs fn(i) for i in [0, count): indices below count-1 on persistent workers, and the last
    // on the calling thread so its core stays busy. Returns when all have finished.
    void run(int count, const std::function<void(int)>& fn) {
        std::lock_guard<std::mutex> jobLock(jobMutex_);
        const int workersNeeded = count - 1;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            while (numWorkers_ < workersNeeded) {
                const int index = numWorkers_++;
                const int startGeneration = generation_;
                std::thread([this, index, startGeneration] {
                    workerLoop(index, startGeneration);
                }).detach();
            }
            task_ = &fn;
            activeWorkers_ = workersNeeded;
            pending_ = workersNeeded;
            generation_++;
        }
        cv_.notify_all();
        fn(count - 1);
        std::unique_lock<std::mutex> lock(mutex_);
        doneCv_.wait(lock, [&] { return pending_ == 0; });
        task_ = nullptr;
    }

private:
    void workerLoop(int index, int seenGeneration) {
#ifdef __ANDROID__
        char name[16];
        snprintf(name, sizeof(name), "EffectWorker%d", index);
        pthread_setname_np(pthread_self(), name);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            threadIds_.push_back(gettid());
        }
#endif
        while (true) {
            const std::function<void(int)>* task;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                cv_.wait(lock, [&] { return generation_ != seenGeneration; });
                seenGeneration = generation_;
                if (index >= activeWorkers_) {
                    continue;  // This job doesn't need this many workers.
                }
                task = task_;
            }
            (*task)(index);
            std::lock_guard<std::mutex> lock(mutex_);
            if (--pending_ == 0) {
                doneCv_.notify_one();
            }
        }
    }

    std::mutex jobMutex_;
    std::mutex mutex_;
    std::condition_variable cv_;
    std::condition_variable doneCv_;
    const std::function<void(int)>* task_ = nullptr;
    std::vector<int32_t> threadIds_;
    int numWorkers_ = 0;
    int activeWorkers_ = 0;
    int pending_ = 0;
    int generation_ = 0;
};

// Rows per work chunk for parallelRows. Chunks are handed out dynamically, so fast cores take
// more of them and slow cores fewer; every thread finishes within about one chunk of the others
// regardless of the device's core mix.
constexpr int kDefaultChunkRows = 32;

// Runs fn() on `numThreads` threads: the worker pool plus the calling thread.
template <typename Fn>
void runOnThreads(int numThreads, Fn fn) {
    if (numThreads <= 1) {
        fn();
        return;
    }
    WorkerPool::instance().run(numThreads, [&](int) { fn(); });
}

// Calls fn(startRow, endRow) for every chunk of `chunkRows` rows in [0, totalRows), spreading
// the chunks over `numThreads` threads (the worker pool plus the caller). Chunks are claimed
// from a shared counter, so fn must not depend on which thread runs a chunk or in what order,
// and chunks must not write to each other's rows. fn is called on worker threads, so
// per-thread scratch should be `static thread_local`.
template <typename Fn>
void parallelRows(int numThreads, int totalRows, int chunkRows, Fn fn) {
    const int numChunks = (totalRows + chunkRows - 1) / chunkRows;
    numThreads = std::max(1, std::min(numThreads, numChunks));
    std::atomic<int> nextChunk{0};
    runOnThreads(numThreads, [&] {
        for (int c = nextChunk.fetch_add(1, std::memory_order_relaxed); c < numChunks;
             c = nextChunk.fetch_add(1, std::memory_order_relaxed)) {
            fn(c * chunkRows, std::min(totalRows, (c + 1) * chunkRows));
        }
    });
}

template <typename Fn>
void parallelRows(int numThreads, int totalRows, Fn fn) {
    parallelRows(numThreads, totalRows, kDefaultChunkRows, fn);
}

}  // namespace vc

#endif  // VECTORCAMERA_WORKER_POOL_H
