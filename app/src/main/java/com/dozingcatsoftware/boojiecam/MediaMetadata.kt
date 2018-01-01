package com.dozingcatsoftware.boojiecam

import com.dozingcatsoftware.boojiecam.effect.EffectMetadata

enum class MediaType {IMAGE, VIDEO}

data class MediaMetadata(val mediaType: MediaType, val effectMetadata: EffectMetadata,
                         val width: Int, val height: Int,
                         val orientation: ImageOrientation, val timestamp: Long,
                         val frameTimestamps: List<Long> = listOf()) {

    fun toJson(): Map<String, Any> {
        val effectInfo = mapOf(
                "name" to effectMetadata.name,
                "params" to effectMetadata.parameters
        )
        return mapOf(
                "type" to mediaType.name.toLowerCase(),
                "width" to width,
                "height" to height,
                "xFlipped" to orientation.isXFlipped(),
                "yFlipped" to orientation.isYFlipped(),
                "timestamp" to timestamp,
                "frameTimestamps" to frameTimestamps,
                "effect" to effectInfo)
    }

    companion object {
        fun fromJson(json: Map<String, Any>): MediaMetadata {
            val effectDict = json["effect"] as Map<String, Any>
            val frameTimestamps = json.getOrElse("frameTimestamps", {listOf<Long>()})
            return MediaMetadata(
                    MediaType.valueOf((json["type"] as String).toUpperCase()),
                    EffectMetadata(
                            effectDict["name"] as String,
                            effectDict["params"] as Map<String, Any>),
                    (json["width"] as Number).toInt(),
                    (json["height"] as Number).toInt(),
                    ImageOrientation.withXYFlipped(
                            json["xFlipped"] as Boolean, json["yFlipped"] as Boolean),
                    json["timestamp"] as Long,
                    frameTimestamps as List<Long>)
        }
    }
}
