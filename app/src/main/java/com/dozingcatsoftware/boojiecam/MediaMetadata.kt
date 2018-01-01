package com.dozingcatsoftware.boojiecam

import com.dozingcatsoftware.boojiecam.effect.EffectMetadata

enum class MediaType {
    IMAGE, VIDEO;

    companion object {
        fun forType(type: String): MediaType {
            return MediaType.valueOf(type.toUpperCase())
        }
    }
}

data class MediaMetadata(val mediaType: MediaType, val effectMetadata: EffectMetadata,
                         val width: Int, val height: Int,
                         val orientation: ImageOrientation, val timestamp: Long,
                         val frameTimstamps: List<Long> = listOf())