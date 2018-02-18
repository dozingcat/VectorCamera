package com.dozingcatsoftware.vectorcamera

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

    val audioSampleSize = 44100
    // Failsafe to make sure we don't keep recording indefinitely.
    val recordingLimitMillis = 120_000L
    // There seems to be a "pop" in the initial input from AudioRecord. Skip this number of bytes
    // (50 ms) after starting the recording to avoid it.
    val skipInitialBytes = 4410

    lateinit var audioRecorder: AudioRecord
    var recordingThread: Thread? = null
    var lock = ReentrantLock()
    var recordingStartTimestamp = 0L


    fun start() {
        recordingThread = Thread(this::threadEntry)
        recordingThread!!.start()
    }

    fun stop() {
        lock.withLock {
            recordingThread = null
        }
    }

    fun shouldStopRecording(): Boolean {
        lock.withLock {
            return recordingThread == null ||
                    timeFn() - recordingStartTimestamp > recordingLimitMillis;
        }
    }

    private fun threadEntry() {
        val bufferSize = maxOf(audioSampleSize, AudioRecord.getMinBufferSize(
                audioSampleSize, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        audioRecorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                audioSampleSize,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize)
        val readBuffer = ByteArray(bufferSize / 2)
        Log.i(TAG, "Starting recording with bufferSize=${bufferSize}")
        recordingStartTimestamp = timeFn()
        audioRecorder.startRecording()
        var firstRead = true
        try {
            audioRecorder.read(readBuffer, 0, skipInitialBytes)
            while (!shouldStopRecording()) {
                val numBytes = audioRecorder.read(readBuffer, 0, readBuffer.size)
                // Grab the timestamp of when we started recording audio, which is the time after
                // the first read minus the duration of the data.
                if (firstRead) {
                    val durationMillis = 1000L * numBytes / (2 * audioSampleSize)
                    recordingStartTimestamp = timeFn() - durationMillis
                    firstRead = false
                }
                Log.i(TAG, "Got ${numBytes} audio bytes")
                outputStream.write(readBuffer, 0, numBytes)
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