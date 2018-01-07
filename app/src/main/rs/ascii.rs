#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam)
#pragma rs_fp_relaxed

// YUV allocation from camera input. Either this or individual planes should be set, not both.
rs_allocation yuvInput;
// Individual Y/U/V planes.
rs_allocation yInput;
rs_allocation uInput;
rs_allocation vInput;
rs_allocation characterBitmapInput;
rs_allocation imageOutput;
int inputImageWidth;
int inputImageHeight;
int outputImageWidth;
int outputImageHeight;
int numCharRows;
int numCharColumns;
int characterPixelWidth;
int characterPixelHeight;
int numCharacters;
bool flipHorizontal;
bool flipVertical;

uchar4 RS_KERNEL computeBlockAverages(uint32_t x, uint32_t y) {
    int inputPixelsPerCol = inputImageWidth / numCharColumns;
    int inputPixelsPerRow = inputImageHeight / numCharRows;

    uint32_t xmin = x * inputPixelsPerCol;
    uint32_t xmax = (x + 1) * inputPixelsPerCol;
    uint32_t ymin = y * inputPixelsPerRow;
    uint32_t ymax = (y + 1) * inputPixelsPerRow;

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

    uint32_t numPixels = inputPixelsPerCol * inputPixelsPerRow;
    uchar4 ret;
    ret.r = redTotal / numPixels;
    ret.g = greenTotal / numPixels;
    ret.b = blueTotal / numPixels;
    ret.a = brightnessTotal / numPixels;

    int xOutMin = x * characterPixelWidth;
    int yOutMin = y * characterPixelHeight;
    uint32_t pixelIndex = (uint32_t) (ret.a / 256.0 * numCharacters);
    uint32_t xoff = pixelIndex * characterPixelWidth;
    for (uint32_t dy = 0; dy < characterPixelHeight; dy++) {
        uint32_t ysrc = flipVertical ? characterPixelHeight - 1 - dy : dy;
        for (uint32_t dx = 0; dx < characterPixelWidth; dx++) {
            uint32_t xsrc = xoff + (flipHorizontal ? characterPixelWidth - 1 - dx : dx);
            uchar4 pixel = rsGetElementAt_uchar4(characterBitmapInput, xsrc, ysrc);
            rsSetElementAt_uchar4(imageOutput, pixel, xOutMin + dx, yOutMin + dy);
        }
    }

    return ret;
}

uchar4 RS_KERNEL computeBlockAverages_planar(uint32_t x, uint32_t y) {
    int inputPixelsPerCol = inputImageWidth / numCharColumns;
    int inputPixelsPerRow = inputImageHeight / numCharRows;

    uint32_t xmin = x * inputPixelsPerCol;
    uint32_t xmax = (x + 1) * inputPixelsPerCol;
    uint32_t ymin = y * inputPixelsPerRow;
    uint32_t ymax = (y + 1) * inputPixelsPerRow;

    uint32_t redTotal = 0;
    uint32_t greenTotal = 0;
    uint32_t blueTotal = 0;
    uint32_t brightnessTotal = 0;

    for (uint32_t yy = ymin; yy < ymax; yy++) {
        for (uint32_t xx = xmin; xx < xmax; xx++) {
            uchar yuv_y = rsGetElementAt_uchar(yInput, xx, yy);
            uchar yuv_u = rsGetElementAt_uchar(uInput, xx / 2, yy / 2);
            uchar yuv_v = rsGetElementAt_uchar(vInput, xx / 2, yy / 2);
            uchar4 rgba = rsYuvToRGBA_uchar4(yuv_y, yuv_u, yuv_v);
            redTotal += rgba.r;
            greenTotal += rgba.g;
            blueTotal += rgba.b;
            brightnessTotal += yuv_y;
        }
    }

    uint32_t numPixels = inputPixelsPerCol * inputPixelsPerRow;
    uchar4 ret;
    ret.r = redTotal / numPixels;
    ret.g = greenTotal / numPixels;
    ret.b = blueTotal / numPixels;
    ret.a = brightnessTotal / numPixels;

    int xOutMin = x * characterPixelWidth;
    int yOutMin = y * characterPixelHeight;
    uint32_t pixelIndex = (uint32_t) (ret.a / 256.0 * numCharacters);
    uint32_t xoff = pixelIndex * characterPixelWidth;
    for (uint32_t dy = 0; dy < characterPixelHeight; dy++) {
        uint32_t ysrc = flipVertical ? characterPixelHeight - 1 - dy : dy;
        for (uint32_t dx = 0; dx < characterPixelWidth; dx++) {
            uint32_t xsrc = xoff + (flipHorizontal ? characterPixelWidth - 1 - dx : dx);
            uchar4 pixel = rsGetElementAt_uchar4(characterBitmapInput, xsrc, ysrc);
            rsSetElementAt_uchar4(imageOutput, pixel, xOutMin + dx, yOutMin + dy);
        }
    }

    return ret;
}
