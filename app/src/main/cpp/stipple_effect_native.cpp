#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <vector>

#include "stipple_effect.h"

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
 * Renders the stipple image into outputPixels (ARGB). `points` is the array returned by
 * generatePoissonPointsNative.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_StippleEffect_00024Companion_renderStippleNative(
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
    jintArray outputPixels
) {
    if (env->GetArrayLength(outputPixels) < width * height) {
        LOGE("Output array too small");
        return JNI_FALSE;
    }
    jbyte* yPtr = env->GetByteArrayElements(yData, nullptr);
    jbyte* uPtr = env->GetByteArrayElements(uData, nullptr);
    jbyte* vPtr = env->GetByteArrayElements(vData, nullptr);
    jfloat* pointsPtr = env->GetFloatArrayElements(points, nullptr);
    jint* outPtr = env->GetIntArrayElements(outputPixels, nullptr);

    bool ok = yPtr && uPtr && vPtr && pointsPtr && outPtr;
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
                params, reinterpret_cast<uint32_t*>(outPtr));
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
    if (outPtr) env->ReleaseIntArrayElements(outputPixels, outPtr, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
