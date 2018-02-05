#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.util)
#pragma rs_fp_relaxed

rs_allocation yuvInputAlloc;
rs_allocation uOutputAlloc;
rs_allocation vOutputAlloc;

/**
 * Splits a YUV input allocation into separate Y, U, and V allocations.
 * The U and V allocations are half the dimensions of Y.
 */
uchar RS_KERNEL flattenYuv(uint32_t x, uint32_t y) {
  if ((x % 2 == 0) && (y % 2 == 0)) {
    rsSetElementAt_uchar(uOutputAlloc, rsGetElementAtYuv_uchar_U(yuvInputAlloc, x, y), x/2, y/2);
    rsSetElementAt_uchar(vOutputAlloc, rsGetElementAtYuv_uchar_V(yuvInputAlloc, x, y), x/2, y/2);
  }
  return rsGetElementAtYuv_uchar_Y(yuvInputAlloc, x, y);
}
