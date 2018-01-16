package com.dozingcatsoftware.boojiecam

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.RandomAccessFile

/**
 * Created by brian on 1/15/18.
 */
class AudioPlayer(val audioFile: RandomAccessFile) {
    val sampleRate = 44100
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val channelMask = AudioFormat.CHANNEL_OUT_MONO
    val audioTrack: AudioTrack
    val audioBuffer = ByteArray(2 * sampleRate)
    val totalBytes = audioFile.length()

    var isPlaying = false
    var framesWritten = 0

    init {
        audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(audioBuffer.size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
    }

    fun startFromMillisOffset(millis: Long) {
        if (millis < 0) {
            throw IllegalArgumentException("Invalid negative millis: ${millis}")
        }
        if (isPlaying) {
            stop()
        }
        // Each second takes (2 * sampleRate) bytes. (2 because of 16 bit samples).
        val byteOffset = 2 * (sampleRate * millis / 1000.0).toLong()
        if (byteOffset < totalBytes) {
            framesWritten = 0
            audioFile.seek(byteOffset)
            fillBuffer()
            audioTrack.play()
        }
    }

    private fun fillBuffer() {
        Log.i(TAG, "fillBuffer start")
        val filePosition = audioFile.filePointer
        val bytesToRead = Math.min(audioBuffer.size.toLong(), totalBytes - filePosition)
        if (bytesToRead <= 0) {
            stop()
            return
        }
        // This isn't in a separate thread, so we do a non-blocking write to the AudioTrack. This
        // will fill the buffer to its limit, which will usually not be all of the bytes read from
        // the audio file. So we need to move the file pointer back to the end of the data that
        // was queued for playback.
        audioFile.readFully(audioBuffer, 0, bytesToRead.toInt())
        val bytesWritten = audioTrack.write(
                audioBuffer, 0, bytesToRead.toInt(), AudioTrack.WRITE_NON_BLOCKING)
        framesWritten += bytesWritten / 2
        // Make sure the file offset is even for 16-bit samples (probably not necessary).
        val newFilePosition = filePosition + (bytesWritten and 0xfffffff8.toInt())
        audioFile.seek(newFilePosition)
        // Set a notification marker before we hit the end of the buffer. The limit should be far
        // enough from the end so that we have time to read from the audio file and refill it, but
        // not so small that we do many more reads than we have to. 22050 frames from the end
        // means that the notification will fire half a second before the buffer runs out, which
        // should be plenty of time to read the next chunk.
        audioTrack.notificationMarkerPosition = framesWritten - 22050
        audioTrack.setPlaybackPositionUpdateListener(
                object: AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack) {
                        Log.i(TAG, "onMarkerReached")
                        fillBuffer()
                    }
                    override fun onPeriodicNotification(track: AudioTrack) {}
                })
        Log.i(TAG, "fillBuffer end, wrote ${bytesWritten} bytes")
    }

    fun stop() {
        Log.i(TAG, "Stopping audio")
        isPlaying = false
        audioTrack.pause()
        audioTrack.flush()
    }

    companion object {
        val TAG = "AudioPlayer"
    }
}