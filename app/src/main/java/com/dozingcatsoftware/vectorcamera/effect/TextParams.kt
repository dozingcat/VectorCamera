package com.dozingcatsoftware.vectorcamera.effect

import android.util.Size
import com.dozingcatsoftware.util.scaleToTargetSize
import com.dozingcatsoftware.vectorcamera.CameraImage
import kotlin.math.roundToInt

class TextMetrics(val isPortrait: Boolean, val outputSize: Size, val charPixelSize: Size,
                  val numCharacterRows: Int, val numCharacterColumns: Int)

class TextParams(private val numPreferredCharColumns: Int,
                 private val minCharWidth: Int,
                 private val charHeightOverWidth: Double) {

    fun getTextMetrics(cameraImage: CameraImage, maxOutputSize: Size): TextMetrics {
        val isPortrait = cameraImage.orientation.portrait
        // In portrait mode the output size is still wide, because it will get rotated for display,
        // but the number of character rows and columns changes. The characters are inserted
        // sideways into the output image so that they will appear correctly after rotation.
        // Using numPreferredCharColumns results in different text sizes for portrait vs landscape.
        // Maybe scale it by the aspect ratio?
        val targetOutputSize = scaleToTargetSize(cameraImage.size(), maxOutputSize)
        val numPixelsForColumns =
                if (isPortrait) targetOutputSize.height else targetOutputSize.width
        val numCharColumns = Math.min(numPreferredCharColumns, numPixelsForColumns / minCharWidth)
        val charPixelWidth = numPixelsForColumns / numCharColumns
        val charPixelHeight = (charPixelWidth * charHeightOverWidth).roundToInt()
        val numPixelsForRows = if (isPortrait) targetOutputSize.width else targetOutputSize.height
        val numCharRows = numPixelsForRows / charPixelHeight
        // Actual output size may be smaller due to not being an exact multiple of the char size.
        val actualOutputSize =
                if (isPortrait) Size(numCharRows * charPixelHeight, numCharColumns * charPixelWidth)
                else Size(numCharColumns * charPixelWidth, numCharRows * charPixelHeight)
        return TextMetrics(isPortrait, actualOutputSize, Size(charPixelWidth, charPixelHeight),
                numCharRows, numCharColumns)
    }

}