#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam.effect)
#pragma rs_fp_relaxed

// YUV allocation from camera input. Either this or individual planes should be set,
// as determined by the hasSingleYuvAllocation flag.
bool hasSingleYuvAllocation;
rs_allocation yuvInput;
rs_allocation yInput;
rs_allocation uInput;
rs_allocation vInput;

rs_allocation characterBitmapInput;
rs_allocation imageOutput;
int inputImageWidth;
int inputImageHeight;
int numCharRows;
int numCharColumns;
int characterPixelWidth;
int characterPixelHeight;
int numCharacters;
bool flipHorizontal;
bool flipVertical;

// 0=monochrome, 1=primary, 2=full
int colorMode;
// In primary color mode, if a color component is at least this fraction of the maximum color
// component, it will be enabled.
static float PRIMARY_COLOR_RATIO = 0.75f;


// Returns (red, green, blue, brightness) averages for the input pixel block.
static uchar4 computeBlockAverageColor(uint32_t x, uint32_t y) {
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

    if (hasSingleYuvAllocation) {
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
    }
    else {
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
    }

    uint32_t numPixels = inputPixelsPerCol * inputPixelsPerRow;
    uchar4 averageColor;
    averageColor.r = redTotal / numPixels;
    averageColor.g = greenTotal / numPixels;
    averageColor.b = blueTotal / numPixels;
    averageColor.a = brightnessTotal / numPixels;
    return averageColor;
}

// Returns the color to use to draw the character for this block. Ignored for mode=0,
// because that uses a single text color.
static uchar4 textColorForBlockAverage(uchar4 averageColor) {
    uchar4 textColor = averageColor;  // for mode 2
    if (colorMode == 1) {
        uchar maxTotalColor = max(max(averageColor.r, averageColor.g), averageColor.b);
        float threshold = maxTotalColor * PRIMARY_COLOR_RATIO;
        textColor.r = (averageColor.r >= threshold) ? 255 : 0;
        textColor.g = (averageColor.g >= threshold) ? 255 : 0;
        textColor.b = (averageColor.b >= threshold) ? 255 : 0;
    }
    return textColor;
}

// This kernel is called for each rectangle of the input image that will have a character drawn into
// it, not once for each pixel. The output allocation contains the average color for the rectangle.
void RS_KERNEL writeCharacterToBitmap(uint32_t x, uint32_t y) {
    uchar4 averageColor = computeBlockAverageColor(x, y);
    uchar4 textColor = textColorForBlockAverage(averageColor);

    uint32_t xOutMin = x * characterPixelWidth;
    uint32_t yOutMin = y * characterPixelHeight;
    uint32_t pixelIndex = (uint32_t) (averageColor.a / 256.0 * numCharacters);
    uint32_t xoff = pixelIndex * characterPixelWidth;
    for (uint32_t dy = 0; dy < characterPixelHeight; dy++) {
        uint32_t ysrc = flipVertical ? characterPixelHeight - 1 - dy : dy;
        for (uint32_t dx = 0; dx < characterPixelWidth; dx++) {
            uint32_t xsrc = xoff + (flipHorizontal ? characterPixelWidth - 1 - dx : dx);
            uchar4 pixel = rsGetElementAt_uchar4(characterBitmapInput, xsrc, ysrc);
            if (colorMode != 0 && (pixel.r > 0 || pixel.g > 0 || pixel.b > 0)) {
                pixel = textColor;
            }
            rsSetElementAt_uchar4(imageOutput, pixel, xOutMin + dx, yOutMin + dy);
        }
    }
}

// Returns the text color to use in the RGB components, and the average brightness in alpha.
// This is used when rendering to HTML or text, so setting brightness lets the caller determine
// which character to draw.
uchar4 RS_KERNEL computeCharacterInfoForBlock(uint32_t x, uint32_t y) {
    uchar4 averageColor = computeBlockAverageColor(x, y);
    uchar4 textColor = textColorForBlockAverage(averageColor);
    textColor.a = averageColor.a;
    return textColor;
}
