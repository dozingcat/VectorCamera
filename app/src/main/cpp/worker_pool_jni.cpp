#include <jni.h>

#include "worker_pool.h"

// Returns the kernel thread IDs of the persistent native worker threads created so far, so the
// app can include them in a performance hint session alongside the image processing thread.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_Effect_00024Companion_nativeWorkerThreadIds(
    JNIEnv* env, jobject thiz) {
    std::vector<int32_t> ids = vc::WorkerPool::instance().threadIds();
    jintArray result = env->NewIntArray(ids.size());
    if (result != nullptr && !ids.empty()) {
        env->SetIntArrayRegion(result, 0, ids.size(), ids.data());
    }
    return result;
}
