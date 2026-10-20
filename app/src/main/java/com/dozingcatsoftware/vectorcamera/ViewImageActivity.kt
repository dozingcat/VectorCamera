package com.dozingcatsoftware.vectorcamera

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler

import androidx.core.content.FileProvider
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.dozingcatsoftware.vectorcamera.effect.AsciiEffect
import com.dozingcatsoftware.vectorcamera.effect.CustomEffect
import com.dozingcatsoftware.vectorcamera.effect.Effect
import com.dozingcatsoftware.vectorcamera.effect.EffectContext
import com.dozingcatsoftware.vectorcamera.effect.EffectInfo
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry
import com.dozingcatsoftware.vectorcamera.effect.customColorMapEffect
import com.dozingcatsoftware.vectorcamera.effect.customContourEffect
import com.dozingcatsoftware.vectorcamera.effect.customPaletteEffect
import com.dozingcatsoftware.vectorcamera.effect.customPermuteEffect
import com.dozingcatsoftware.util.getLandscapeDisplaySize
import com.dozingcatsoftware.util.grantUriPermissionForIntent
import com.dozingcatsoftware.vectorcamera.databinding.ViewImageBinding
import java.io.File


class ViewImageActivity : AppCompatActivity() {
    private lateinit var binding: ViewImageBinding

    private lateinit var photoLibrary: PhotoLibrary

    private lateinit var imageId: String
    private var inEffectSelectionMode = false
    private var thumbnailRenderer: StaticThumbnailRenderer? = null
    // The image's pixels, held while a custom effect editor is open so that each change
    // doesn't reread them from disk.
    private var cameraImageForEditing: CameraImage? = null
    private val effectRegistry = EffectRegistry()
    private val preferences = VCPreferences(this)
    private val handler = Handler()

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewImageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        photoLibrary = PhotoLibrary.defaultLibrary(this)

        binding.switchEffectButton.setOnClickListener(this::toggleEffectSelectionMode)
        binding.editEffectButton.setOnClickListener(this::showCustomEffectEditor)
        binding.shareButton.setOnClickListener(this::shareImage)
        binding.deleteButton.setOnClickListener(this::deleteImage)
        binding.editSchemeView.activity = this
        binding.editSchemeView.changeCallback = this::handleCustomColorSchemeChanged
        binding.editSchemeView.doneCallback = this::handleCustomEffectEditorDone
        binding.editPermuteView.changeCallback = this::handleCustomPermuteSchemeChanged
        binding.editPermuteView.doneCallback = this::handleCustomEffectEditorDone
        binding.editPaletteView.activity = this
        binding.editPaletteView.changeCallback = this::handleCustomPaletteSchemeChanged
        binding.editPaletteView.doneCallback = this::handleCustomEffectEditorDone
        binding.editContourView.activity = this
        binding.editContourView.changeCallback = this::handleCustomContourSchemeChanged
        binding.editContourView.doneCallback = this::handleCustomEffectEditorDone
        binding.effectPickerView.setEffects(effectRegistry.effectInfos)
        binding.effectPickerView.onEffectSelected = this::handleEffectSelected
        binding.effectPickerView.onThumbnailNeeded = {info ->
            thumbnailRenderer?.requestThumbnail(info.id)
        }

        // Back closes the effect picker or an open editor panel rather than leaving the activity.
        onBackPressedCallback = object: OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (inEffectSelectionMode) {
                    toggleEffectSelectionMode(null)
                }
                else if (visibleCustomEffectEditor() != null) {
                    hideCustomEffectEditor()
                }
            }
        }
        onBackPressedDispatcher.addCallback(onBackPressedCallback)

        imageId = intent.getStringExtra("imageId")!!
        loadImage()
    }

    override fun onDestroy() {
        thumbnailRenderer?.shutdown()
        thumbnailRenderer = null
        super.onDestroy()
    }

    private fun loadImage() {
        val metadata = photoLibrary.metadataForItemId(imageId)
        val effect = effectRegistry.effectForMetadata(metadata.effectMetadata)
        showImage(effect, metadata)
        updateEditButton()
    }

    // The image's effect as an editable custom effect, or null if it isn't one.
    private fun customEffectForImage(metadata: MediaMetadata): Effect? =
            effectRegistry.customEffectForMetadata(
                    metadata.effectId, metadata.effectMetadata, preferences.lookupFunction)

    private fun customEffectEditors() = listOf(
            binding.editSchemeView, binding.editPermuteView,
            binding.editPaletteView, binding.editContourView)

    private fun visibleCustomEffectEditor(): View? =
            customEffectEditors().firstOrNull {it.visibility == View.VISIBLE}

    private fun updateBackPressedCallback() {
        onBackPressedCallback.isEnabled =
                inEffectSelectionMode || visibleCustomEffectEditor() != null
    }

    // The edit button shows when the image uses a custom effect and its editor is closed.
    private fun updateEditButton() {
        val editable = customEffectForImage(photoLibrary.metadataForItemId(imageId)) != null
        binding.editEffectButton.visibility =
                if (editable && visibleCustomEffectEditor() == null) View.VISIBLE else View.GONE
    }

    // Opens the editor panel for `effect` if it's a custom effect; otherwise closes any panel.
    // Edits change this image's saved effect parameters, not the custom effect's preferences.
    private fun showCustomEffectEditor(effect: Effect) {
        hideCustomEffectEditor()
        val editor: View? = when (val scheme = (effect as? CustomEffect<*>)?.scheme) {
            is CustomColorScheme -> binding.editSchemeView.also {it.setScheme(scheme)}
            is CustomPermuteScheme -> binding.editPermuteView.also {it.setScheme(scheme)}
            is CustomPaletteScheme -> binding.editPaletteView.also {it.setScheme(scheme)}
            is CustomContourScheme -> binding.editContourView.also {it.setScheme(scheme)}
            else -> null
        }
        editor?.visibility = View.VISIBLE
        if (visibleCustomEffectEditor() != null) {
            cameraImageForEditing = createCameraImage(photoLibrary.metadataForItemId(imageId))
        }
        updateBackPressedCallback()
        updateEditButton()
    }

    // Opens the editor for the image's custom effect, from the edit button.
    private fun showCustomEffectEditor(view: View?) {
        val effect = customEffectForImage(photoLibrary.metadataForItemId(imageId)) ?: return
        showCustomEffectEditor(effect)
    }

    private fun hideCustomEffectEditor() {
        for (editor in customEffectEditors()) {
            editor.visibility = View.GONE
        }
        handleCustomEffectEditorDone()
    }

    private fun handleCustomEffectEditorDone() {
        cameraImageForEditing = null
        updateBackPressedCallback()
        updateEditButton()
    }

    private fun handleCustomColorSchemeChanged(cs: CustomColorScheme) {
        applyEditedEffect {id -> customColorMapEffect(cs, id, EffectContext.NORMAL)}
    }

    private fun handleCustomPermuteSchemeChanged(ps: CustomPermuteScheme) {
        applyEditedEffect {id -> customPermuteEffect(ps, id)}
    }

    private fun handleCustomPaletteSchemeChanged(ps: CustomPaletteScheme) {
        applyEditedEffect {id -> customPaletteEffect(ps, id)}
    }

    private fun handleCustomContourSchemeChanged(cs: CustomContourScheme) {
        applyEditedEffect {id -> customContourEffect(cs, id)}
    }

    // Re-renders the image with the edited effect and saves it to the image's metadata and
    // thumbnail, keeping the picker ID so the picker still highlights the custom effect.
    private fun applyEditedEffect(effectForId: (String) -> Effect) {
        val metadata = photoLibrary.metadataForItemId(imageId)
        val effectId = metadata.effectId ?: return
        val effect = effectForId(effectId)
        val newMetadata = metadata.withEffectMetadata(effect.effectMetadata(), effectId)
        val cameraImage = cameraImageForEditing ?: createCameraImage(newMetadata)
        val pb = effect.createBitmap(cameraImage)
        photoLibrary.writeMetadata(newMetadata, imageId)
        photoLibrary.writeThumbnail(pb, imageId)
        binding.overlayView.updateBitmap(pb)
    }

    private fun showEffectPicker() {
        val picker = binding.effectPickerView
        val metadata = photoLibrary.metadataForItemId(imageId)
        var renderer: StaticThumbnailRenderer? = null
        renderer = StaticThumbnailRenderer(
                effectRegistry, preferences.lookupFunction, createCameraImage(metadata),
                {picker.thumbnailSize},
                {id, bitmap ->
                    handler.post {
                        // Ignore thumbnails that arrive after the picker was closed.
                        if (renderer === thumbnailRenderer) {
                            picker.setThumbnail(id, bitmap)
                        }
                    }
                })
        thumbnailRenderer?.shutdown()
        thumbnailRenderer = renderer
        picker.clearThumbnails()
        picker.setSourceShape(metadata.width, metadata.height, metadata.orientation.portrait)
        picker.selectedEffectId = metadata.effectId
        picker.visibility = View.VISIBLE
        picker.scrollToEffect(metadata.effectId)
        binding.controlBar.visibility = View.GONE
        hideCustomEffectEditor()
    }

    private fun hideEffectPicker() {
        thumbnailRenderer?.shutdown()
        thumbnailRenderer = null
        binding.effectPickerView.visibility = View.GONE
        binding.effectPickerView.clearThumbnails()
        binding.controlBar.visibility = View.VISIBLE
    }

    private fun updateInEffectSelectionModeFlag(inMode: Boolean) {
        inEffectSelectionMode = inMode
        updateBackPressedCallback()
    }

    private fun toggleEffectSelectionMode(view: View?) {
        updateInEffectSelectionModeFlag(!inEffectSelectionMode)
        if (inEffectSelectionMode) {
            showEffectPicker()
        }
        else {
            hideEffectPicker()
        }
    }

    private fun createCameraImage(metadata: MediaMetadata): CameraImage {
        val yuvBytes = photoLibrary.rawImageFileInputStreamForItemId(imageId).readBytes()
        val imageData = ImageData.fromYuvBytes(
            yuvBytes, metadata.width, metadata.height
        )
        return CameraImage(imageData, metadata.orientation,
                CameraStatus.CAPTURING_PHOTO, metadata.timestamp, getLandscapeDisplaySize(this))
    }

    private fun createProcessedBitmap(effect: Effect, metadata: MediaMetadata): ProcessedBitmap {
        return effect.createBitmap(createCameraImage(metadata))
    }

    private fun showImage(effect: Effect, metadata: MediaMetadata) {
        val pb = createProcessedBitmap(effect, metadata)
        binding.overlayView.updateBitmap(pb)
    }

    private fun handleEffectSelected(info: EffectInfo) {
        val effect = effectRegistry.createEffect(info.id, preferences.lookupFunction)
        // Update metadata and thumbnail, *not* the full size image because that's slow.
        val newMetadata = photoLibrary.metadataForItemId(imageId)
                .withEffectMetadata(effect.effectMetadata(), info.id)
        val pb = createProcessedBitmap(effect, newMetadata)
        photoLibrary.writeMetadata(newMetadata, imageId)
        photoLibrary.writeThumbnail(pb, imageId)
        binding.overlayView.updateBitmap(pb)
        updateInEffectSelectionModeFlag(false)
        hideEffectPicker()
        // A newly chosen custom effect opens its editor, as in the camera activity.
        showCustomEffectEditor(effect)
    }

    private fun shareImage(view: View) {
        // Stop reading metadata so often?
        val metadata = photoLibrary.metadataForItemId(imageId)
        val effect = effectRegistry.effectForMetadata(metadata.effectMetadata)
        if (effect is AsciiEffect) {
            showAsciiTypeShareDialog(metadata, effect)
        }
        else {
            shareImage(metadata, effect)
        }
    }

    private fun shareFile(path: String, mimeType: String) {
        val fileUri = FileProvider.getUriForFile(this,
                BuildConfig.APPLICATION_ID + ".fileprovider", File(path))
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = mimeType
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri)
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "${Constants.APP_NAME} $imageId.png")
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val chooser = Intent.createChooser(shareIntent, getString(R.string.shareActionTitle))
        grantUriPermissionForIntent(this, fileUri, chooser)
        startActivity(chooser)
    }

    private fun shareImage(metadata: MediaMetadata, effect: Effect) {
        // This is moderately complicated. If the effect of the exported PNG file doesn't match the
        // current effect, we need to export again. Except that we could have just taken a picture
        // and the export could be in progress. There's no good way to detect that, so we check if
        // the metadata timestamp is later than 10 seconds ago and if so give it a chance to finish.
        // While the export is in progress we show a progress spinner, which isn't ideal but at
        // least lets the user know there's something happening.
        var waitDialog: ProgressDialog? = null
        var pngFile = photoLibrary.imageFileForItemId(imageId)
        var firstCheckTimestamp: Long? = null

        fun doShare() {
            waitDialog?.cancel()
            waitDialog = null
            shareFile(pngFile.path, "image/png")
        }

        fun showWaitDialog() {
            if (waitDialog == null) {
                waitDialog = ProgressDialog(this)
                waitDialog!!.isIndeterminate = true
                waitDialog!!.setMessage(getString(R.string.exportingImageMessage))
                waitDialog!!.setCancelable(false)
                Log.i(TAG, "Showing wait dialog")
                waitDialog!!.show()
            }
        }

        fun exportAndShare() {
            showWaitDialog()
            Thread {
                try {
                    val pb = createProcessedBitmap(effect, metadata)
                    photoLibrary.writePngImage(this, pb, imageId)
                    // Update metadata so we won't need to regenerate the image if we export again
                    // with the same effect.
                    val metadata = photoLibrary.metadataForItemId(imageId)
                    val newMetadata = metadata.withExportedEffectMetadata(
                        effect.effectMetadata(), "png")
                    photoLibrary.writeMetadata(newMetadata, imageId)
                    handler.post { doShare() }
                } catch (ex: Exception) {
                    handler.post {
                        Toast.makeText(this, "Error exporting image", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    waitDialog?.dismiss()
                }
            }.start()
        }

        fun shareIfExists() {
            if (pngFile.exists()) {
                val exportedEffect =
                        photoLibrary.metadataForItemId(imageId).exportedEffectMetadata["png"]
                if (effect.effectMetadata().equals(exportedEffect)) {
                    Log.i(TAG, "Exported effect matches")
                    doShare()
                }
                else {
                    Log.i(TAG, "Exported effect doesn't match, re-exporting")
                    exportAndShare()
                }
            }
            else {
                // No file at all, we could be waiting for the initial export.
                val now = System.currentTimeMillis()
                if (firstCheckTimestamp == null) {
                    firstCheckTimestamp = now
                }
                if ((now - metadata.timestamp < 10000) && (now - firstCheckTimestamp!! < 10000)) {
                    // Give the initial export a chance to finish.
                    // firstCheckTimestamp avoids waiting indefinitely if the timestamp is bogus.
                    Log.i(TAG, "Waiting for initial export")
                    showWaitDialog()
                    handler.postDelayed({shareIfExists()}, 1000L)
                }
                else {
                    Log.i(TAG, "Giving up on initial export")
                    // Probably not waiting on initial export, do it ourselves.
                    exportAndShare()
                }
            }
        }
        shareIfExists()
    }

    private fun shareText(metadata: MediaMetadata, effect: AsciiEffect) {
        val textFile = photoLibrary.tempFileWithName(imageId + ".txt")
        val inputImage = createCameraImage(metadata)
        photoLibrary.createTempFileOutputStream(textFile).use {
            effect.writeText(inputImage, it)
        }
        shareFile(textFile.path, "text/plain")
    }

    private fun shareHtml(metadata: MediaMetadata, effect: AsciiEffect) {
        val htmlFile = photoLibrary.tempFileWithName(imageId + ".html")
        val inputImage = createCameraImage(metadata)
        photoLibrary.createTempFileOutputStream(htmlFile).use {
            effect.writeHtml(inputImage, it)
        }
        shareFile(htmlFile.path, "text/html")
    }

    private fun showAsciiTypeShareDialog(metadata: MediaMetadata, effect: AsciiEffect) {
        val shareTypes = arrayOf("image", "html", "text")
        var selectedShareType = "image"
        val shareTypeLabels = arrayOf(
                getString(R.string.sharePictureImageOptionLabel),
                getString(R.string.sharePictureHtmlOptionLabel),
                getString(R.string.sharePictureTextOptionLabel))
        AlertDialog.Builder(this)
                .setTitle(R.string.sharePictureDialogTitle)
                .setSingleChoiceItems(shareTypeLabels, 0, {
                    _: DialogInterface, which: Int -> selectedShareType = shareTypes[which]
                })
                .setPositiveButton(R.string.shareDialogYesLabel, {_: DialogInterface, _: Int ->
                    when (selectedShareType) {
                        "image" -> shareImage(metadata, effect)
                        "html" -> shareHtml(metadata, effect)
                        "text" -> shareText(metadata, effect)
                    }
                })
                .setNegativeButton(R.string.shareDialogNoLabel, null)
                .show()
    }

    private fun deleteImage(view: View) {
        val deleteFn = { _: DialogInterface, _: Int ->
            photoLibrary.deleteItem(imageId)
            finish()
        }

        AlertDialog.Builder(this)
                .setCancelable(true)
                .setMessage("Are you sure you want to delete this picture?")
                .setPositiveButton("Delete", deleteFn)
                .setNegativeButton("Don't delete", null)
                .show()
    }

    companion object {
        const val TAG = "ViewImageActivity"

        fun startActivityWithImageId(parent: Activity, imageId: String): Intent {
            val intent = Intent(parent, ViewImageActivity::class.java)
            intent.putExtra("imageId", imageId)
            parent.startActivityForResult(intent, 0)
            return intent
        }
    }
}