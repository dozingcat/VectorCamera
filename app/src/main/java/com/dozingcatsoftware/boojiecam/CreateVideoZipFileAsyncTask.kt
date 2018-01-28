package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.os.AsyncTask
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Created by brian on 1/26/18.
 */
class CreateVideoZipFileAsyncTask(
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
        var tmpZipFile: File? = null
        var zipFile: File? = null
        try {
            val videoReader = params[0]!!.videoReader
            val mediaLibrary = params[0]!!.mediaLibrary
            val videoId = params[0]!!.videoId
            val videoWidth = videoReader.videoWidth()
            val videoHeight = videoReader.videoHeight()


            val zipFile = mediaLibrary.videoFramesArchiveForItemId(videoId)
            tmpZipFile = File(zipFile.parentFile, zipFile.name + ".tmp")
            val out = ZipOutputStream(FileOutputStream(tmpZipFile))
            var frameIndex = 0
            while (frameIndex in 0 until videoReader.numberOfFrames() - 1) {
                val filename = String.format("/%s/%05d.png", videoId, frameIndex)
                out.putNextEntry(ZipEntry(filename))

                val pb = videoReader.bitmapForFrame(frameIndex)
                // 0 is "quality" argument, ignored since PNG is lossless.
                pb.renderBitmap(videoWidth, videoHeight).compress(Bitmap.CompressFormat.PNG, 0, out)

                out.closeEntry()
                if (this.isCancelled()) {
                    throw InterruptedException()
                }
                val fractionDone = (1 + frameIndex).toDouble() / videoReader.numberOfFrames()
                publishProgress(
                        ProcessVideoTask.Progress(ProcessVideoTask.MediaType.VIDEO, fractionDone))
            }

            out.close()
            tmpZipFile.renameTo(zipFile)
        }
        catch (iex: InterruptedException) {
            return ProcessVideoTask.Result(ProcessVideoTask.ResultStatus.CANCELLED, null)
        }
        catch (ex: Exception) {
            Log.w(TAG, "WebM encoding failed", ex)
            return ProcessVideoTask.Result(ProcessVideoTask.ResultStatus.FAILED, null)
        }
        finally {
            tmpZipFile?.delete()
        }

        return return ProcessVideoTask.Result(ProcessVideoTask.ResultStatus.SUCCEEDED, zipFile)
    }

    companion object {
        val TAG = "CreateVideoZipFile"
    }
}