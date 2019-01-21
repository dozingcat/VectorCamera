#include <stdio.h>

#include "libvpx/vpx/vp8cx.h"
#include "libvpx/webmenc.h"

#include <jni.h>

// As of January 2016, the VP9 encoder is *very* slow, as in several seconds
// per frame on a Nexus 5x.
#define WG_USE_VP9 0

#if WG_USE_VP9
#define WG_CODEC_INTERFACE (&vpx_codec_vp9_cx_algo)
#define WG_FOURCC VP9_FOURCC
#else
#define WG_CODEC_INTERFACE (&vpx_codec_vp8_cx_algo)
#define WG_FOURCC VP8_FOURCC
#endif

// Based on vpxenc.c

typedef struct encoding_context {
    struct WebmOutputContext woc;
    vpx_codec_enc_cfg_t cfg;
    vpx_codec_ctx_t codec;
    FILE *output;
    vpx_image_t vpx_image;
    float frames_per_second;
    int *frame_durations;
    int frame_count;
    int timebase_unit_count;  // elapsed "timebase units" which VP8 uses internally, always 1/30 sec?
    float timebase_units_per_second;
    unsigned long deadline;
} encoding_context;

static encoding_context gContext = {0};


int start_encode(encoding_context *context, char *path, int width, int height, float fps, int *durations, int deadline) {
    if (0!=vpx_codec_enc_config_default(WG_CODEC_INTERFACE, &context->cfg, 0)) return 1000;
    context->cfg.g_w = width;
    context->cfg.g_h = height;
    context->deadline = deadline; // microseconds to spend encoding each frame
    context->timebase_units_per_second = 30.0f; // always 30?
    context->frames_per_second = fps;
    context->frame_durations = durations;

    FILE *outfile = fopen(path, "wb");
    if (!outfile) return 1001;
    context->woc.stream = outfile;

    if (!vpx_img_alloc(&context->vpx_image, VPX_IMG_FMT_I420, width, height, 1)) return 1002;

    if (0!=vpx_codec_enc_init(&context->codec, WG_CODEC_INTERFACE, &context->cfg, 0)) return 1003;

    struct VpxRational framerate = {fps, 1};
    write_webm_file_header(&context->woc, &context->cfg, STEREO_FORMAT_MONO, WG_FOURCC, &framerate);

    return 0;
}

int cleanup(encoding_context *context) {
    fclose(context->woc.stream);
    vpx_img_free(&context->vpx_image);
    if (0!=vpx_codec_destroy(&context->codec)) return 1099;
    if (context->frame_durations) {
        free(context->frame_durations);
    }
    return 0;
}

unsigned long next_frame_duration(encoding_context *context) {
    // compute total elapsed time after next frame and convert to timebase units
    // either using array of each frame's finishing time if available, or average FPS if not
    float next_elapsed_time = 0;
    if (context->frame_durations) {
        next_elapsed_time = context->frame_durations[context->frame_count] / 1000.0f;
    }
    else {
        next_elapsed_time = (context->frame_count + 1) / context->frames_per_second;
    }
    int next_elapsed_tb_units = (next_elapsed_time * context->timebase_units_per_second);
    int duration = next_elapsed_tb_units - context->timebase_unit_count;
    return (duration>=1) ? duration : 1;
}	

int write_webm_frame_data(encoding_context *context, vpx_image_t *image) {
    unsigned long duration = next_frame_duration(context);
    vpx_codec_err_t result = vpx_codec_encode(&context->codec, image, context->timebase_unit_count, duration, 0, context->deadline);

    context->frame_count += 1;
    context->timebase_unit_count += duration;

    if (result!=VPX_CODEC_OK) {
        cleanup(context);
        return 10000 + context->frame_count;
    }

    vpx_codec_iter_t iter = NULL;
    const vpx_codec_cx_pkt_t *pkt;
    while( (pkt = vpx_codec_get_cx_data(&context->codec, &iter)) ) {
        switch (pkt->kind) {
            case VPX_CODEC_CX_FRAME_PKT:
                write_webm_block(&context->woc, &context->cfg, pkt);
                break;
            default:
                break;
        }
    }
    return 0;
}

void argb_to_vpximage(jint *argb, vpx_image_t vpx_image, int width, int height) {
    unsigned char *yplane = vpx_image.planes[0];
    unsigned char *uplane = vpx_image.planes[1];
    unsigned char *vplane = vpx_image.planes[2];
    // Every pixel gets a Y value, U and V are 2x2 sampled, so only take every other row/column.
    int yoffset = 0;
    int uvoffset = 0;
    int x, y;
    for(y=0; y<height; y++) {
        for(x=0; x<width; x++) {
            int color = argb[yoffset];
            int r = (color>>16) & 0xff;
            int g = (color>>8) & 0xff;
            int b = (color) & 0xff;

            yplane[yoffset] = (unsigned char)((30*r + 59*g + 11*b)/100);
            yoffset++;

            if ((x%2==0) && (y%2==0)) {
                uplane[uvoffset] = (unsigned char)((-17*r - 33*g + 50*b + 12800)/100);
                vplane[uvoffset] = (unsigned char)((50*r - 42*g - 8*b + 12800)/100);
                uvoffset++;
            }
        }
    }
}

int encode_argb_frame(encoding_context *context, jint *argb) {
    argb_to_vpximage(argb, context->vpx_image, context->cfg.g_w, context->cfg.g_h);
    write_webm_frame_data(context, &context->vpx_image);
    return 0;
}


int finish_encode(encoding_context *context) {
    write_webm_file_footer(&context->woc);
    return cleanup(context);
}


// Java API

jint Java_com_dozingcatsoftware_vectorcamera_WebMEncoder_nativeStartEncoding(
        JNIEnv* env, jobject thiz, jstring javaPath, jint width, jint height, jfloat fps, jintArray jFrameDurations, jint deadline) {
    char *path = (char *)(*env)->GetStringUTFChars(env, javaPath, NULL);
    // copy frame durations to native C array
    jint *tempDurations = NULL;
    int *durations = NULL;
    if (jFrameDurations) {
        tempDurations = (*env)->GetIntArrayElements(env, jFrameDurations, 0);
        int size = (*env)->GetArrayLength(env, jFrameDurations);
        int i;
        durations = malloc(size * sizeof(int));
        for(i=0; i<size; i++) {
            durations[i] = tempDurations[i];
        }
    }

    int result = start_encode(&gContext, path, width, height, fps, durations, deadline);
    (*env)->ReleaseStringUTFChars(env, javaPath, path);
    (*env)->ReleaseIntArrayElements(env, jFrameDurations, tempDurations, 0);
    return result;
}

jint Java_com_dozingcatsoftware_vectorcamera_WebMEncoder_nativeEncodeFrame(JNIEnv* env, jobject thiz, jintArray javaARGB) {
    jint *argb = (*env)->GetIntArrayElements(env, javaARGB, 0);
    int result = encode_argb_frame(&gContext, argb);
    (*env)->ReleaseIntArrayElements(env, javaARGB, argb, 0);
    return result;
}

jint Java_com_dozingcatsoftware_vectorcamera_WebMEncoder_nativeFinishEncoding(JNIEnv* env, jobject thiz) {
    return finish_encode(&gContext);
}

jint Java_com_dozingcatsoftware_vectorcamera_WebMEncoder_nativeCancelEncoding(JNIEnv* env, jobject thiz) {
    return cleanup(&gContext);
}
