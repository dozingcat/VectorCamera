package com.dozingcatsoftware.boojiecam

enum class CameraStatus {
    CLOSED,
    OPENING,
    OPENED,
    CAPTURE_READY,
    CAPTURING_PREVIEW,
    CAPTURING_PHOTO,
    CAPTURING_VIDEO,
    STOPPING_CAPTURE,
    CLOSING,
    ERROR,
    ;

    fun isCapturing(): Boolean {
        return this == CAPTURING_PREVIEW || this == CAPTURING_PHOTO ||
                this == CAPTURING_VIDEO
    }

    fun isSavingImage(): Boolean {
        return this == CAPTURING_PHOTO || this == CAPTURING_VIDEO
    }
}
