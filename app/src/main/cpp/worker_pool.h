// Persistent worker threads for native effects. See WorkerPool below.
#ifndef VECTORCAMERA_WORKER_POOL_H
#define VECTORCAMERA_WORKER_POOL_H

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

namespace contour {

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

}  // namespace contour

#endif  // VECTORCAMERA_WORKER_POOL_H
