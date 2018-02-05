#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam.effect)
#pragma rs_fp_relaxed

// Element.RGBA_8888, created from packed RGBA byte array.
rs_allocation gColorMap;

uchar4 RS_KERNEL applyColorMap(uchar value, uint32_t x, uint32_t y) {
    return rsGetElementAt_uchar4(gColorMap, value);
}
