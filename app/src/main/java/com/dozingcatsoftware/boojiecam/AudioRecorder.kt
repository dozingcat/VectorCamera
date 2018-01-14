package com.dozingcatsoftware.boojiecam

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Created by brian on 1/10/18.
 */
class AudioRecorder(val videoId: String, val outputStream: OutputStream,
                    val timeFn: (() -> Long) = System::currentTimeMillis) {
    // MediaRecorder looks like it should work but doesn't. There's no Vorbis encoding support,
    // or even PCM/WAV. 3gpp might work but is low quality and we'd have to convert it in order
    // to export to WebM.

    lateinit var audioRecorder: AudioRecord
    var recordingThread: Thread? = null
    var lock = ReentrantLock()
    val audioSampleSize = 44100


    fun start() {
        recordingThread = Thread(this::threadEntry)
        recordingThread!!.start()
    }

    fun stop() {
        lock.withLock {
            recordingThread = null
        }
    }

    fun isStopped(): Boolean {
        lock.withLock {
            return recordingThread == null
        }
    }

    private fun threadEntry() {
        val bufferSize = maxOf(audioSampleSize, AudioRecord.getMinBufferSize(
                audioSampleSize, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_8BIT))
        audioRecorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                audioSampleSize,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_8BIT,
                bufferSize)
        Log.i(TAG, "Starting recording")
        val buffer = ByteArray(audioSampleSize)
        try {
            while (!isStopped()) {
                val numBytes = audioRecorder.read(buffer, 0, buffer.size)
                // It would be better to put the data on a queue rather than doing a blocking write.
                Log.i(TAG, "Got ${numBytes} audio bytes")
                outputStream.write(buffer, 0, numBytes)
            }
        }
        finally {
            audioRecorder.stop()
            audioRecorder.release()
            outputStream.close()
        }
    }

    companion object {
        val TAG = "AudioRecorder"
    }
}