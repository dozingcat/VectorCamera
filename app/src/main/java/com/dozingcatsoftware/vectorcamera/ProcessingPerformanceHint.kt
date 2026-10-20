package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import com.dozingcatsoftware.vectorcamera.effect.Effect

/**
 * Tells the system how long each frame of image processing takes, so that it raises CPU clocks
 * to meet the target instead of treating the bursty camera workload as light. Without this the
 * cores tend to stay near their minimum frequency and effects run several times slower than the
 * hardware allows.
 *
 * Uses the Android 12+ performance hint API (ADPF); on older versions every method is a no-op.
 * Create and use an instance on the processing thread itself: the calling thread is added to the
 * session along with the native worker pool threads.
 */
class ProcessingPerformanceHint(context: Context) {
    private val manager: PerformanceHintManager? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                context.getSystemService(PerformanceHintManager::class.java)
            else null

    init {
        if (manager == null) {
            Log.w(TAG, "PerformanceHintManager not available (SDK ${Build.VERSION.SDK_INT})")
        }
    }
    private var session: PerformanceHintManager.Session? = null
    private var sessionThreadIds = IntArray(0)
    private var frameStartNanos = 0L

    /** Call at the start of each frame's processing. */
    fun beginFrame() {
        if (manager == null) return
        updateThreads()
        frameStartNanos = System.nanoTime()
    }

    /** Call when the frame's processing is done. */
    fun endFrame() {
        val s = session ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            s.reportActualWorkDuration(System.nanoTime() - frameStartNanos)
        }
    }

    /** Call when the processing thread exits. */
    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            session?.close()
        }
        session = null
        sessionThreadIds = IntArray(0)
    }

    // Native workers are created lazily, so the thread set can grow; rebuild the session when it
    // does. A session is attempted once per thread set: the API exists on every Android 12+
    // device, but createHintSession returns null when the power HAL doesn't implement hint
    // sessions (e.g. Pixel 3a), and retrying every frame would only repeat the warning.
    private fun updateThreads() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager == null) return
        val threadIds = intArrayOf(Process.myTid()) + Effect.workerThreadIds()
        if (threadIds.contentEquals(sessionThreadIds)) return
        sessionThreadIds = threadIds
        try {
            val existing = session
            if (existing != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                existing.setThreads(threadIds)
                Log.i(TAG, "Updated hint session threads to ${threadIds.toList()}")
            } else {
                existing?.close()
                session = manager.createHintSession(threadIds, TARGET_FRAME_NANOS)
                if (session == null) {
                    Log.w(TAG, "Performance hint session unavailable; the power HAL doesn't support it")
                } else {
                    Log.i(TAG, "Created hint session for threads ${threadIds.toList()}")
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to update performance hint session: ${ex.message}")
        }
    }

    companion object {
        const val TAG = "PerformanceHint"
        // One display frame at 60 Hz. The system scales clocks so that reported durations
        // approach this, so a target shorter than the camera's frame interval keeps headroom.
        const val TARGET_FRAME_NANOS = 16_600_000L
    }
}
