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
}