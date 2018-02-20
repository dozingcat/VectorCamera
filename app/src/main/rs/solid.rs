#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.vectorcamera.effect)
#pragma rs_fp_relaxed

// May be YUV data (the U and V planes will be ignored), or just the Y plane.
rs_allocation yuvInput;
// Element.RGBA_8888, created from packed RGBA byte array.
rs_allocation colorMap;

uchar4 RS_KERNEL computeColor(uint32_t x, uint32_t y) {
    int index = rsGetElementAt_uchar(yuvInput, x, y);
    return rsGetElementAt_uchar4(colorMap, index);
}
