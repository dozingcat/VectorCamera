package com.dozingcatsoftware.boojiecam

import android.renderscript.Allocation

data class CameraAllocation(val allocation: Allocation, val orientation: ImageOrientation,
                            val status: CameraStatus, val timestamp: Long) {
}
