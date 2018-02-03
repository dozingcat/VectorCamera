package com.dozingcatsoftware.boojiecam

import android.os.AsyncTask
import android.util.Log
import java.io.File

/**
 * Created by brian on 1/25/18.
 */
class CreateWebmAsyncTask(
        val progressHandler: (ProcessVideoTask.Progress) -> Unit,
        val completionHandler: (ProcessVideoTask.Result) -> Unit) :
        AsyncTask<ProcessVideoTask.Params, ProcessVideoTask.Progress, ProcessVideoTask.Result>() {

    override fun onProgressUpdate(vararg values: ProcessVideoTask.Progress?) {
        progressHandler(values[0]!!)
    }

    override fun onPostExecute(result: ProcessVideoTask.Result?) {
        completionHandler(result!!)
    }

    override fun doInBackground(vararg params: ProcessVideoTask.Params?): ProcessVideoTask.Result {
        var finalOutputFile: File? = null
        try {
            val videoReader = params[0]!!.videoReader
            val mediaLibrary = params[0]!!.mediaLibrary
            val videoId = params[0]!!.videoId

            val tempVideoOnlyFile = mediaLibrary.tempFileWithName(videoId + ".webm.noaudio")
            tempVideoOnlyFile.delete()
            tempVideoOnlyFile.parentFile.mkdirs()
            Log.i(TAG, "Writing to ${tempVideoOnlyFile.path}")

            val encoder = WebMEncoder(videoReader, tempVideoOnlyFile.path)
            encoder.startEncoding()
            for (frameIndex in 0 until videoReader.numberOfFrames()) {
                if (this.isCancelled()) {
                    throw InterruptedException()
                }
                encoder.encodeFrame(frameIndex)
                Log.i(TAG, "Encoded frame ${frameIndex}")
                publishProgress(ProcessVideoTask.Progress(
                        ProcessVideoTask.MediaType.VIDEO,
                        frameIndex.toDouble() / videoReader.numberOfFrames()))
            }
            encoder.finishEncoding()
            Log.i(TAG, "Finished encoding video")

            var fileToMove: File

            val audioFile = mediaLibrary.rawAudioFileForItemId(videoId)
            if (audioFile.exists()) {
                val audioFileSize = audioFile.length()
                val tempCombinedFile = mediaLibrary.tempFileWithName(videoId + ".webm.combined")
                tempCombinedFile.delete()
                Log.i(TAG, "Adding audio, saving to ${tempCombinedFile.path}")

                // TODO: Allow cancelling this operation.
                CombineAudioVideo.insertAudioIntoWebm(
                        audioFile.path, tempVideoOnlyFile.path, tempCombinedFile.path,
                        {bytesRead ->
                            Log.i(TAG, "Read ${bytesRead} audio bytes")
                            publishProgress(ProcessVideoTask.Progress(
                                    ProcessVideoTask.MediaType.AUDIO,
                                    bytesRead.toDouble() / audioFileSize))
                        })

                tempVideoOnlyFile.delete()
                fileToMove = tempCombinedFile
            }
            else {
                fileToMove = tempVideoOnlyFile
            }
            finalOutputFile = mediaLibrary.videoFileForItemId(videoId)
            finalOutputFile.parentFile.mkdirs()
            fileToMove.renameTo(finalOutputFile)
            Log.i(TAG, "Wrote to ${finalOutputFile.path}")
        }
        catch (iex: InterruptedException) {
            return ProcessVideoTask.Result(ProcessVideoTask.ResultStatus.CANCELLED, null)
        }
        catch (ex: Exception) {
            Log.w(TAG, "WebM encoding failed", ex)
            return ProcessVideoTask.Result(ProcessVideoTask.ResultStatus.FAILED, null)
        }
        return ProcessVideoTask.Result(ProcessVideoTask.ResultStatus.SUCCEEDED, finalOutputFile)
    }

    companion object {
        const val TAG = "CreateWebmAsyncTask"
    }
}