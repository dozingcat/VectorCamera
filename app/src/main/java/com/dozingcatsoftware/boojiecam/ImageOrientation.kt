package com.dozingcatsoftware.boojiecam

enum class ImageOrientation {
    NORMAL,
    ROTATED_180,
    ;

    fun isXFlipped(): Boolean {
        return this == ROTATED_180
    }

    fun isYFlipped(): Boolean {
        return this == ROTATED_180
    }

    companion object {
        fun withXYFlipped(xFlipped: Boolean, yFlipped: Boolean): ImageOrientation {
            if (xFlipped != yFlipped) {
                throw IllegalArgumentException("Only 0 and 180 degree rotations supported")
            }
            return if (xFlipped) ImageOrientation.ROTATED_180 else ImageOrientation.NORMAL
        }
    }
}