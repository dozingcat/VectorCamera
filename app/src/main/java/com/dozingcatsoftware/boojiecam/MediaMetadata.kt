package com.dozingcatsoftware.boojiecam

import com.dozingcatsoftware.boojiecam.effect.EffectMetadata

enum class MediaType {IMAGE, VIDEO}

data class MediaMetadata(val mediaType: MediaType, val effectMetadata: EffectMetadata,
                         val width: Int, val height: Int,
                         val orientation: ImageOrientation, val timestamp: Long,
                         val frameTimestamps: List<Long> = listOf(),
                         val audioStartTimestamp: Long = 0,
                         val exportedEffectMetadata: EffectMetadata? = null) {

    fun toJson(): Map<String, Any> {
        val exportedEffectDict = exportedEffectMetadata?.toJson() ?: mapOf()
        return mapOf(
                "type" to mediaType.name.toLowerCase(),
                "width" to width,
                "height" to height,
                "xFlipped" to orientation.isXFlipped(),
                "yFlipped" to orientation.isYFlipped(),
                "timestamp" to timestamp,
                "frameTimestamps" to frameTimestamps,
                "audioStartTimestamp" to audioStartTimestamp,
                "effect" to effectMetadata.toJson(),
                "exportedEffect" to exportedEffectDict)
    }

    fun withEffectMetadata(em: EffectMetadata): MediaMetadata {
        return MediaMetadata(
                mediaType, em, width, height, orientation,
                timestamp, frameTimestamps, audioStartTimestamp, exportedEffectMetadata)
    }

    fun withExportedEffectMetadata(em: EffectMetadata): MediaMetadata {
        return MediaMetadata(
                mediaType, em, width, height, orientation,
                timestamp, frameTimestamps, audioStartTimestamp, em)
    }

    companion object {
        fun fromJson(json: Map<String, Any>): MediaMetadata {
            val effectDict = json["effect"] as Map<String, Any>
            val exportedEffectDict = json["exportedEffect"] as Map<String, Any>?
            val frameTimestamps = json.getOrElse("frameTimestamps", {listOf<Long>()})
            val audioStartTimestamp = json.getOrDefault("audioStartTimestamp", 0) as Number
            return MediaMetadata(
                    MediaType.valueOf((json["type"] as String).toUpperCase()),
                    EffectMetadata.fromJson(effectDict),
                    (json["width"] as Number).toInt(),
                    (json["height"] as Number).toInt(),
                    ImageOrientation.withXYFlipped(
                            json["xFlipped"] as Boolean, json["yFlipped"] as Boolean),
                    json["timestamp"] as Long,
                    frameTimestamps as List<Long>,
                    audioStartTimestamp.toLong(),
                    EffectMetadata.fromJson(exportedEffectDict))
        }
    }
}
