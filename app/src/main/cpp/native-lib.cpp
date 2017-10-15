#include <jni.h>
#include <string>

extern "C"
JNIEXPORT jstring
JNICALL
Java_com_dozingcatsoftware_boojiecam_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

static inline int32_t toUInt(jbyte jb) {
    return jb & 0xff;
}

extern "C"
JNIEXPORT void
JNICALL
Java_com_dozingcatsoftware_boojiecam_EdgeImageProcessor_computeEdgesNative(
        JNIEnv *env, jobject thiz,
        jbyteArray jbright, jint width, jint height, jint minRow, jint maxRow, jint rowStride,
        jintArray jcolorTable, jintArray joutput) {
    jbyte *bright = env->GetByteArrayElements(jbright, 0);
    jint *colorTable = env->GetIntArrayElements(jcolorTable, 0);
    jint *output = env->GetIntArrayElements(joutput, 0);

    int32_t multiplier = std::min(4, std::max(2, width / 480));
    for (int32_t y = minRow; y < maxRow; y++) {
        if (y == 0 || y == height - 1) {
            int32_t minOffset = y * width;
            int32_t maxOffset = minOffset + width;
            for (int32_t i = minOffset; i < maxOffset; i++) {
                output[i] = colorTable[0];
            }
        }
        else {
            int32_t minIndex = y * rowStride + 1;
            int32_t maxIndex = minIndex + width - 2;
            int32_t pixOffset = y * width;
            output[pixOffset++] = colorTable[0];
            for (int32_t index = minIndex; index < maxIndex; index++) {
                int32_t up = index - rowStride;
                int32_t down = index + rowStride;
                int32_t edgeStrength = 8 * toUInt(bright[index]) - (
                        toUInt(bright[up-1]) + toUInt(bright[up]) + toUInt(bright[up+1]) +
                        toUInt(bright[index-1]) + toUInt(bright[index+1]) +
                        toUInt(bright[down-1]) + toUInt(bright[down]) + toUInt(bright[down+1]));
                int32_t b = std::min(255, std::max(0, multiplier * edgeStrength));
                // int32_t b = toUInt(bright[index]);
                output[pixOffset++] = colorTable[b];
            }
            output[pixOffset++] = colorTable[0];
        }
    }

    env->ReleaseByteArrayElements(jbright, bright, 0);
    env->ReleaseIntArrayElements(jcolorTable, colorTable, 0);
    env->ReleaseIntArrayElements(joutput, output, 0);
}