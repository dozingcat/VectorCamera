#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.util)
#pragma rs_fp_relaxed

rs_allocation yInputAlloc;
rs_allocation uInputAlloc;
rs_allocation vInputAlloc;

/**
 * Converts Y/U/V data in separate allocations to RGBA. The U and V allocations should be half
 * the height and width of the Y allocation.
 */
uchar4 RS_KERNEL convertToRgba(uint32_t x, uint32_t y) {
    uchar yy = rsGetElementAt_uchar(yInputAlloc, x, y);
    uchar uu = rsGetElementAt_uchar(uInputAlloc, x / 2, y / 2);
    uchar vv = rsGetElementAt_uchar(vInputAlloc, x / 2, y / 2);
    return rsYuvToRGBA_uchar4(yy, uu, vv);
}
