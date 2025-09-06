package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import com.dozingcatsoftware.vectorcamera.CameraImage
import com.dozingcatsoftware.vectorcamera.ProcessedBitmap

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
    fun createBitmap(cameraImage: CameraImage): ProcessedBitmap

    fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {}

    fun effectName(): String

    fun effectParameters(): Map<String, Any> = mapOf()

    fun effectMetadata(): EffectMetadata = EffectMetadata(effectName(), effectParameters())

    companion object {
        // Maximum thread counts based on performance characteristics
        // Native code hits memory bandwidth limits quickly
        const val MAX_NATIVE_THREADS = 2
        // Kotlin code is more CPU-bound and benefits from more parallelism
        const val MAX_KOTLIN_THREADS = 4

        const val DISABLE_NATIVE_CODE = false
        var nativeLibraryLoaded = false

        fun loadNativeLibrary(): Boolean {
            if (DISABLE_NATIVE_CODE) {
                return false
            }
            if (nativeLibraryLoaded) {
                return true
            }
            try {
                System.loadLibrary("vectorcamera_native")
                nativeLibraryLoaded = true
                return true
            } catch (e: UnsatisfiedLinkError) {
                Log.w("Effect", "Failed to load native library, using Kotlin implementation: ${e.message}")
                return false
            }
        }
    }
}