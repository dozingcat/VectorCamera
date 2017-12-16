package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF

/**
 * Created by brian on 12/16/17.
 */
interface Effect {
    fun createBitmap(camAllocation: CameraImage): Bitmap

    fun createPaintFn(camAllocation: CameraImage): (RectF) -> Paint? {
        return {null}
    }
}