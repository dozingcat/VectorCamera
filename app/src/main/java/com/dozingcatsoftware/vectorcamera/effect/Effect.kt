package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.dozingcatsoftware.vectorcamera.CameraImage

data class EffectMetadata(val name: String, val parameters: Map<String, Any>) {
    fun toJson() = mapOf("name" to name, "params" to parameters)

    companion object {
        fun fromJson(json: Map<String, Any>?): EffectMetadata {
            if (json == null) {
                return EffectMetadata("", mapOf())
            }
            val params = json.getOrElse("params", {mapOf<String, Any>()}) as Map<String, Any>
            return EffectMetadata(json.getOrElse("name", {""}) as String, params)
        }
    }
}

interface Effect {
    fun createBitmap(cameraImage: CameraImage): Bitmap

    fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {}

    fun effectName(): String

    fun effectParameters(): Map<String, Any> = mapOf()

    fun effectMetadata(): EffectMetadata = EffectMetadata(effectName(), effectParameters())
}