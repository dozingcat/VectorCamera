package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF

data class ProcessedBitmap(
        val sourceImage: CameraImage,
        val bitmap: Bitmap,
        val backgroundPaintFn: (RectF) -> Paint?)
