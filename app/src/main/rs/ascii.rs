#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.vectorcamera.effect)
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
int32_t inputImageWidth;
int32_t inputImageHeight;
int32_t numCharRows;
int32_t numCharColumns;
int32_t characterPixelWidth;
int32_t characterPixelHeight;
int32_t numCharacters;
bool flipHorizontal;
bool flipVertical;
bool portrait;

// 0=monochrome, 1=primary, 2=full
int32_t colorMode;
// In primary color mode, if a color component is at least this fraction of the maximum color
// component, it will be enabled.
static float PRIMARY_COLOR_RATIO = 0.875f;

// In portrait mode, the image input and output is still landscape, but it will be rotated for
// display so we need to write the text sideways. (x, y) coords in portrait mode:
// ===================
// |(2,0)|(2,1)|(2,2)|
// |(1,0)|(1,1)|(1,2)|
// |(0,0)|(0,1)|(0,2)|
// ===================


// Returns (red, green, blue, brightness) averages for the input pixel block.
static uchar4 computeBlockAverageColor(uint32_t x, uint32_t y) {
    uint32_t inputPixelsPerCol, inputPixelsPerRow;
    uint32_t xmin, xmax, ymin, ymax;
    if (portrait) {
        inputPixelsPerCol = inputImageHeight / numCharColumns;
        inputPixelsPerRow = inputImageWidth / numCharRows;
        xmin = y * inputPixelsPerRow;
        xmax = xmin + inputPixelsPerRow;
        ymin = (numCharColumns - 1 - x) * inputPixelsPerCol;
        ymax = ymin + inputPixelsPerCol;
    }
    else {
        inputPixelsPerCol = inputImageWidth / numCharColumns;
        inputPixelsPerRow = inputImageHeight / numCharRows;
        xmin = x * inputPixelsPerCol;
        xmax = xmin + inputPixelsPerCol;
        ymin = y * inputPixelsPerRow;
        ymax = ymin + inputPixelsPerRow;
    }

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

// Writes pixels for a character to the output image allocation.
static void writeCharacterPixels(
        uint32_t x, uint32_t y, uint32_t pixelIndex, bool useTextColor, uchar4 textColor) {
    uint32_t xoff = pixelIndex * characterPixelWidth;

    if (portrait) {
        uint32_t xOutMin = y * characterPixelHeight;
        uint32_t yOutMin = (numCharColumns - 1 - x) * characterPixelWidth;
        uint32_t xOutCount = characterPixelHeight;
        uint32_t yOutCount = characterPixelWidth;

        for (uint32_t dy = 0; dy < yOutCount; dy++) {
            uint32_t xsrc = xoff + (flipVertical ? dy:  characterPixelWidth - 1 - dy);
            for (uint32_t dx = 0; dx < xOutCount; dx++) {
                uint32_t ysrc = (flipHorizontal ? characterPixelHeight - 1 - dx : dx);
                // If `useTextColor` is true, replace any non-black source color with `textColor`.
                uchar4 s = rsGetElementAt_uchar4(characterBitmapInput, xsrc, ysrc);
                uchar4 pixel = useTextColor && (s.r > 0 || s.g > 0 || s.b > 0) ? textColor: s;
                rsSetElementAt_uchar4(imageOutput, pixel, xOutMin + dx, yOutMin + dy);
            }
        }
    }
    else {
        uint32_t xOutMin = x * characterPixelWidth;
        uint32_t yOutMin = y * characterPixelHeight;
        uint32_t xOutCount = characterPixelWidth;
        uint32_t yOutCount = characterPixelHeight;

        for (uint32_t dy = 0; dy < yOutCount; dy++) {
            uint32_t ysrc = flipVertical ? characterPixelHeight - 1 - dy : dy;
            for (uint32_t dx = 0; dx < xOutCount; dx++) {
                uint32_t xsrc = xoff + (flipHorizontal ? characterPixelWidth - 1 - dx : dx);
                uchar4 s = rsGetElementAt_uchar4(characterBitmapInput, xsrc, ysrc);
                uchar4 pixel = useTextColor && (s.r > 0 || s.g > 0 || s.b > 0) ? textColor: s;
                rsSetElementAt_uchar4(imageOutput, pixel, xOutMin + dx, yOutMin + dy);
            }
        }
    }
}

// This kernel is called for each rectangle of the input image that will have a character drawn into
// it, not once for each pixel. The output allocation contains the average color for the rectangle.
void RS_KERNEL writeCharacterToBitmap(uint32_t x, uint32_t y) {
    uchar4 averageColor = computeBlockAverageColor(x, y);
    uchar4 textColor = textColorForBlockAverage(averageColor);
    uint32_t pixelIndex = (uint32_t) (averageColor.a / 256.0 * numCharacters);
    writeCharacterPixels(x, y, pixelIndex, colorMode != 0, textColor);
}

// Accepts two input allocations containing the index of the character to draw
// (from characterBitmapInput) and the color of the character.
void RS_KERNEL writeCharacterToBitmapWithColor(
        uint32_t pixelIndex, uchar4 color, uint32_t x, uint32_t y) {
    writeCharacterPixels(x, y, pixelIndex, true, color);
}

// Returns the text color to use in the RGB components, and the average brightness in alpha.
// This is used when rendering to HTML or text, so setting brightness lets the caller determine
// which character to draw.
uchar4 RS_KERNEL computeCharacterInfoForBlock(uint32_t x, uint32_t y) {
    uint32_t actualX = flipHorizontal ? numCharColumns - 1 - x : x;
    uint32_t actualY = flipVertical ? numCharRows - 1 - y : y;
    uchar4 averageColor = computeBlockAverageColor(actualX, actualY);
    uchar4 textColor = textColorForBlockAverage(averageColor);
    textColor.a = averageColor.a;
    return textColor;
}

// Returns just the average brightness for a block. Somewhat ineffecient because
// `computeBlockAverageColor` also computes the average R/G/B components.
uchar RS_KERNEL computeBrightnessForBlock(uint32_t x, uint32_t y) {
    uint32_t actualX = flipHorizontal ? numCharColumns - 1 - x : x;
    uint32_t actualY = flipVertical ? numCharRows - 1 - y : y;
    uchar4 averageColor = computeBlockAverageColor(actualX, actualY);
    return averageColor.a;
}
