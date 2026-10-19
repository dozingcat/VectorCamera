// JNI entry points for StippleEffect. The kernels are in stipple_effect.h so they can be
// compiled and tested on the host (app/src/test/cpp/stipple_test.cpp); compiling with
// -DVC_NO_JNI leaves out the JNI code.

#include <cstdint>
#include <vector>

#include "stipple_effect.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "StippleNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Generates a progressive Poisson disk point set. Returns a float array of (x, y, rank)
 * triples, or null on failure. See stipple_effect.h for details.
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_StippleEffect_00024Companion_generatePoissonPointsNative(
    JNIEnv* env,
    jobject thiz,
    jint width,
    jint height,
    jfloat minSpacing,
    jint numLayers,
    jint seed
) {
    std::vector<Stipple::Point> points;
    try {
        points = Stipple::generateProgressivePoissonPoints(
            width, height, minSpacing, numLayers, static_cast<uint32_t>(seed));
    } catch (const std::exception& e) {
        LOGE("Exception generating points: %s", e.what());
        return nullptr;
    }
    const jsize numFloats = static_cast<jsize>(points.size() * 3);
    jfloatArray result = env->NewFloatArray(numFloats);
    if (result == nullptr) {
        return nullptr;
    }
    static_assert(sizeof(Stipple::Point) == 3 * sizeof(float), "Point must be 3 packed floats");
    env->SetFloatArrayRegion(result, 0, numFloats, reinterpret_cast<const jfloat*>(points.data()));
    return result;
}

/**
 * Renders the stipple image into `bitmap` (RGBA_8888, width x height). `points` is the array
 * returned by generatePoissonPointsNative. Returns false without touching the bitmap if it
 * can't be used directly, in which case Kotlin falls back to its own rendering.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_StippleEffect_00024Companion_renderStippleNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint width,
    jint height,
    jfloatArray points,
    jfloat dotRadius,
    jfloat dotSizeVariation,
    jfloat gamma,
    jint toneCellSize,
    jint backgroundColor,
    jint dotColor,
    jboolean colorFromImage,
    jfloat dotLuminanceScale,
    jfloat chromaBoost,
    jboolean invertTone,
    jobject bitmap,
    jint numThreads
) {
    vc::LockedBitmap locked(env, bitmap, width, height);
    if (!locked.ok()) {
        return JNI_FALSE;
    }
    jbyte* yPtr = env->GetByteArrayElements(yData, nullptr);
    jbyte* uPtr = env->GetByteArrayElements(uData, nullptr);
    jbyte* vPtr = env->GetByteArrayElements(vData, nullptr);
    jfloat* pointsPtr = env->GetFloatArrayElements(points, nullptr);

    bool ok = yPtr && uPtr && vPtr && pointsPtr;
    if (ok) {
        Stipple::RenderParams params;
        params.dotRadius = dotRadius;
        params.dotSizeVariation = dotSizeVariation;
        params.gamma = gamma;
        params.toneCellSize = toneCellSize;
        params.backgroundColor = static_cast<uint32_t>(backgroundColor);
        params.dotColor = static_cast<uint32_t>(dotColor);
        params.colorFromImage = colorFromImage == JNI_TRUE;
        params.dotLuminanceScale = dotLuminanceScale;
        params.chromaBoost = chromaBoost;
        params.invertTone = invertTone == JNI_TRUE;
        const int numPoints = env->GetArrayLength(points) / 3;
        try {
            Stipple::renderStipple(
                reinterpret_cast<const uint8_t*>(yPtr),
                reinterpret_cast<const uint8_t*>(uPtr),
                reinterpret_cast<const uint8_t*>(vPtr),
                width, height,
                reinterpret_cast<const Stipple::Point*>(pointsPtr), numPoints,
                params, locked.pixels(), locked.stride(),
                std::max(1, static_cast<int>(numThreads)));
        } catch (const std::exception& e) {
            LOGE("Exception rendering stipple: %s", e.what());
            ok = false;
        }
    } else {
        LOGE("Failed to get native arrays");
    }

    if (yPtr) env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
    if (uPtr) env->ReleaseByteArrayElements(uData, uPtr, JNI_ABORT);
    if (vPtr) env->ReleaseByteArrayElements(vData, vPtr, JNI_ABORT);
    if (pointsPtr) env->ReleaseFloatArrayElements(points, pointsPtr, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
#endif  // VC_NO_JNI
