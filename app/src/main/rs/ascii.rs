#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam)
#pragma rs_fp_relaxed

rs_allocation yuvInput;
rs_allocation characterBitmapInput;
rs_allocation imageOutput;
int imageWidth;
int imageHeight;
int numCharRows;
int numCharColumns;
int characterPixelWidth;
int characterPixelHeight;
int numCharacters;
bool flipHorizontal;
bool flipVertical;

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

    uint32_t pixelIndex = (uint32_t) (ret.a / 256.0 * numCharacters);
    uint32_t xoff = pixelIndex * pixelsPerCol;
    for (uint32_t dy = 0; dy < pixelsPerRow; dy++) {
        uint32_t ysrc = flipVertical ? pixelsPerRow - 1 - dy : dy;
        for (uint32_t dx = 0; dx < pixelsPerCol; dx++) {
            uint32_t xsrc = xoff + (flipHorizontal ? pixelsPerCol - 1 - dx : dx);
            uchar4 pixel = rsGetElementAt_uchar4(characterBitmapInput, xsrc, ysrc);
            rsSetElementAt_uchar4(imageOutput, pixel, xmin + dx, ymin + dy);
        }
    }

    return ret;
}
