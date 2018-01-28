package com.dozingcatsoftware.boojiecam

import java.io.File

object ProcessVideoTask {

    data class Params(val videoReader: VideoReader, val mediaLibrary: PhotoLibrary,
                      val videoId: String)

    data class Progress(val mediaType: MediaType, val fractionDone: Double)

    data class Result(val status: ResultStatus, val outputFile: File?)

    enum class MediaType {
        VIDEO, AUDIO
    }

    enum class ResultStatus {
        SUCCEEDED, FAILED, CANCELLED
    }
}
