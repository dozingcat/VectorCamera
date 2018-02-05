package com.dozingcatsoftware.boojiecam

import com.dozingcatsoftware.boojiecam.effect.EffectMetadata

enum class MediaType {IMAGE, VIDEO}

data class MediaMetadata(val mediaType: MediaType, val effectMetadata: EffectMetadata,
                         val width: Int, val height: Int,
                         val orientation: ImageOrientation, val timestamp: Long,
                         val frameTimestamps: List<Long> = listOf(),
                         val audioStartTimestamp: Long = 0,
                         val exportedEffectMetadata: Map<String, EffectMetadata> = mapOf()) {

    fun toJson(): Map<String, Any> {
        val exportedEffectDict =
                exportedEffectMetadata?.mapValues({entry -> entry.value.toJson()}) ?: mapOf()
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
                "exportedEffects" to exportedEffectDict)
    }

    fun withEffectMetadata(em: EffectMetadata): MediaMetadata {
        return MediaMetadata(
                mediaType, em, width, height, orientation,
                timestamp, frameTimestamps, audioStartTimestamp, exportedEffectMetadata)
    }

    fun withExportedEffectMetadata(em: EffectMetadata, exportType: String): MediaMetadata {
        val newExportedEffects = HashMap(exportedEffectMetadata)
        newExportedEffects[exportType] = em
        return MediaMetadata(
                mediaType, effectMetadata, width, height, orientation,
                timestamp, frameTimestamps, audioStartTimestamp, newExportedEffects)
    }

    companion object {
        fun fromJson(json: Map<String, Any>): MediaMetadata {
            val effectDict = json["effect"] as Map<String, Any>
            val exportedEffectDict = json["exportedEffects"] as Map<String, Any>?
            val exportedEffectMetadata = exportedEffectDict?.mapValues(
                    {entry -> EffectMetadata.fromJson(entry.value as Map<String, Any>)}) ?: mapOf()
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
                    exportedEffectMetadata)
        }
    }
}
