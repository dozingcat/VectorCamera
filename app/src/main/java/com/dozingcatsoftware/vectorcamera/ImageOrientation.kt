package com.dozingcatsoftware.vectorcamera

class ImageOrientation(
        val xFlipped: Boolean, val yFlipped: Boolean, val portrait: Boolean = false) {

    fun withPortrait(newPortrait: Boolean) = ImageOrientation(xFlipped, yFlipped, newPortrait)

    companion object {
        val NORMAL = ImageOrientation(false, false)
    }
}
