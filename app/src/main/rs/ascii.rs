#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam)
#pragma rs_fp_relaxed

rs_allocation yuvInput;
int imageWidth;
int imageHeight;
int numCharRows;
int numCharColumns;

uchar4 RS_KERNEL computeBlockAverages(uint32_t x, uint32_t y) {
    int pixelsPerCol = imageWidth / numCharColumns;
    int pixelsPerRow = imageHeight / numCharRows;

    uint32_t xmin = x * pixelsPerCol;
    uint32_t xmax = (x + 1) * pixelsPerCol;
    uint32_t ymin = y * pixelsPerRow;
    uint32_t ymax = (y + 1) * pixelsPerRow;

    uint32_t redTotal = 0;
    uint32_t greenTotal = 0;
    uint32_t blueTotal = 0;
    uint32_t brightnessTotal = 0;

    for (uint32_t yy = ymin; yy < ymax; yy++) {
        for (uint32_t xx = xmin; xx < xmax; xx++) {
            uchar yuv_y = rsGetElementAtYuv_uchar_Y(yuvInput, xx, yy);
            uchar yuv_u = rsGetElementAtYuv_uchar_U(yuvInput, xx, yy);
            uchar yuv_v = rsGetElementAtYuv_uchar_V(yuvInput, xx, yy);
            uchar4 rgba = rsYuvToRGBA_uchar4(yuv_y, yuv_u, yuv_v);
            redTotal += rgba.r;
            greenTotal += rgba.g;
            blueTotal += rgba.b;
            brightnessTotal += yuv_y;
        }
    }

    uint32_t numPixels = pixelsPerCol * pixelsPerRow;
    uchar4 ret;
    ret.r = redTotal / numPixels;
    ret.g = greenTotal / numPixels;
    ret.b = blueTotal / numPixels;
    ret.a = brightnessTotal / numPixels;
    return ret;
}
