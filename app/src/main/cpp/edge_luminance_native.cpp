#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>

#define LOG_TAG "EdgeLuminanceNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// YUV to RGB conversion using ITU-R BT.601 standard
inline uint32_t yuvToRgb(int y, int u, int v) {
    const float yValue = static_cast<float>(y);
    const float uValue = static_cast<float>(u - 128);
    const float vValue = static_cast<float>(v - 128);

    int red = static_cast<int>(yValue + 1.370705f * vValue);
    int green = static_cast<int>(yValue - 0.698001f * vValue - 0.337633f * uValue);
    int blue = static_cast<int>(yValue + 1.732446f * uValue);

    // Clamp values to 0-255 range
    red = std::max(0, std::min(255, red));
    green = std::max(0, std::min(255, green));
    blue = std::max(0, std::min(255, blue));

    // Return ARGB format (0xFF for alpha, then RGB)
    return (0xFF << 24) | (red << 16) | (green << 8) | blue;
}

// Fast edge detection using Laplacian operator
inline int calculateEdgeStrength(
    const uint8_t* yData, 
    int x, int y, 
    int width, int height, 
    int multiplier
) {
    if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) {
        return 0;
    }

    const int pixelIndex = y * width + x;
    const int center = yData[pixelIndex];
    
    // Get surrounding 8 pixels for Laplacian operator
    const int surroundingSum = 
        yData[(y - 1) * width + (x - 1)] +
        yData[(y - 1) * width + x] +
        yData[(y - 1) * width + (x + 1)] +
        yData[y * width + (x - 1)] +
        yData[y * width + (x + 1)] +
        yData[(y + 1) * width + (x - 1)] +
        yData[(y + 1) * width + x] +
        yData[(y + 1) * width + (x + 1)];

    const int edge = 8 * center - surroundingSum;
    return std::max(0, std::min(255, multiplier * edge));
}

// Native implementation of processRows
extern "C" JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_EdgeLuminanceEffectKotlin_processRowsNative(
    JNIEnv* env,
    jobject /* this */,
    jint startY,
    jint endY,
    jint width,
    jint height,
    jint multiplier,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint uvWidth,
    jintArray pixels
) {
    // Get direct access to byte arrays
    jbyte* yBytes = env->GetByteArrayElements(yData, nullptr);
    jbyte* uBytes = env->GetByteArrayElements(uData, nullptr);
    jbyte* vBytes = env->GetByteArrayElements(vData, nullptr);
    jint* pixelArray = env->GetIntArrayElements(pixels, nullptr);

    if (!yBytes || !uBytes || !vBytes || !pixelArray) {
        LOGI("Failed to get array elements");
        return;
    }

    // Cast to unsigned for easier arithmetic
    const uint8_t* yPtr = reinterpret_cast<const uint8_t*>(yBytes);
    const uint8_t* uPtr = reinterpret_cast<const uint8_t*>(uBytes);
    const uint8_t* vPtr = reinterpret_cast<const uint8_t*>(vBytes);

    // Process each row in the assigned range
    for (int y = startY; y < endY; y++) {
        for (int x = 0; x < width; x++) {
            const int pixelIndex = y * width + x;

            // Calculate edge strength
            const int edgeStrength = calculateEdgeStrength(
                yPtr, x, y, width, height, multiplier);

            // Get U and V values (subsampled)
            const int uvX = x / 2;
            const int uvY = y / 2;
            const int uvIndex = uvY * uvWidth + uvX;
            const int u = uPtr[uvIndex];
            const int v = vPtr[uvIndex];

            // Convert YUV to RGB using edge strength as Y value
            pixelArray[pixelIndex] = static_cast<jint>(yuvToRgb(edgeStrength, u, v));
        }
    }

    // Release array elements
    env->ReleaseByteArrayElements(yData, yBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(uData, uBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(vData, vBytes, JNI_ABORT);
    env->ReleaseIntArrayElements(pixels, pixelArray, 0);
}

// Function to check if native implementation is available
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_EdgeLuminanceEffectKotlin_isNativeAvailable(
    JNIEnv* env,
    jobject /* this */
) {
    return JNI_TRUE;
} 