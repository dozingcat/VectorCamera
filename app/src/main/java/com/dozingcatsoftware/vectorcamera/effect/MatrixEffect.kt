package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.util.reuseOrCreate2dAllocation
import com.dozingcatsoftware.vectorcamera.CameraImage

class MatrixEffect(val rs: RenderScript, numPreferredCharColumns: Int): Effect {

    private val textParams = TextParams(numPreferredCharColumns, 10, 1.8)
    private var characterTemplateAllocation: Allocation? = null
    private var characterIndexAllocation: Allocation? = null
    private var characterColorAllocation: Allocation? = null
    private var bitmapOutputAllocation: Allocation? = null
    private val script = ScriptC_ascii(rs)

    override fun effectName() = EFFECT_NAME

    private fun updateGridCharacters(metrics: TextMetrics) {
        val prevCharAlloc = characterIndexAllocation
        characterIndexAllocation = reuseOrCreate2dAllocation(
                characterIndexAllocation, rs, Element::U32,
                metrics.numCharacterColumns, metrics.numCharacterRows)
        if (prevCharAlloc == characterIndexAllocation) {
            // Maybe change a character.
        }
        else {
            // Initialize with random characters.
            val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
            val indices = IntArray(numCells)
            for (i in 0 until numCells) {
                indices[i] = i % NUM_MATRIX_CHARS
            }
            characterIndexAllocation!!.copyFrom(indices)
        }
    }

    private fun updateGridColors(metrics: TextMetrics) {
        characterColorAllocation = reuseOrCreate2dAllocation(
                characterColorAllocation, rs, Element::U8_4,
                metrics.numCharacterColumns, metrics.numCharacterRows)
        val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
        val ca = ByteArray(4 * numCells)
        for (i in 0 until numCells) {
            // ca[i] = (0xff00ff00).toInt()
            ca[4*i] = 0
            ca[4*i + 1] = 0xff.toByte()
            ca[4*i + 2] = 0
            ca[4*i + 3] = 0xff.toByte()
        }
        characterColorAllocation!!.copyFrom(ca)
    }

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val metrics = textParams.getTextMetrics(cameraImage, cameraImage.displaySize)
        // TODO: Reuse resultBitmap and charBitmap if possible.
        val resultBitmap = Bitmap.createBitmap(
                metrics.outputSize.width, metrics.outputSize.height, Bitmap.Config.ARGB_8888)
        val cps = metrics.charPixelSize
        // Create Bitmap and draw each character into it.
        // TODO: Reverse English characters.
        val pixelChars = MATRIX_CHARS + MATRIX_REVERSED_CHARS
        val paint = Paint()
        paint.textSize = cps.height * 5f / 6
        paint.color = Color.argb(255, 0, 255, 0)

        val charBitmap = Bitmap.createBitmap(
                cps.width * pixelChars.length, cps.height, Bitmap.Config.ARGB_8888)
        val charBitmapCanvas = Canvas(charBitmap)
        charBitmapCanvas.drawColor(Color.argb(255, 0, 0, 0))
        for (i in 0 until pixelChars.length) {
            charBitmapCanvas.drawText(pixelChars[i].toString(),
                    (i * cps.width).toFloat(), cps.height - 1f, paint)
        }
        characterTemplateAllocation = reuseOrCreate2dAllocation(characterTemplateAllocation,
                rs, Element::RGBA_8888,charBitmap.width, charBitmap.height)

        characterTemplateAllocation!!.copyFrom(charBitmap)

        updateGridCharacters(metrics)
        updateGridColors(metrics)

        bitmapOutputAllocation = reuseOrCreate2dAllocation(bitmapOutputAllocation,
                rs, Element::RGBA_8888, resultBitmap.width, resultBitmap.height)

        script._characterBitmapInput = characterTemplateAllocation
        script._imageOutput = bitmapOutputAllocation
        script._inputImageWidth = cameraImage.width()
        script._inputImageHeight = cameraImage.height()
        script._characterPixelWidth = cps.width
        script._characterPixelHeight = cps.height
        script._numCharColumns = metrics.numCharacterColumns
        script._numCharRows = metrics.numCharacterRows
        script._numCharacters = pixelChars.length
        script._flipHorizontal = cameraImage.orientation.xFlipped
        script._flipVertical = cameraImage.orientation.yFlipped
        script._portrait = metrics.isPortrait
        script._colorMode = 0

        script.forEach_writeCharacterToBitmapWithColor(
                characterIndexAllocation, characterColorAllocation)
        bitmapOutputAllocation!!.copyTo(resultBitmap)
        return resultBitmap
    }

    companion object {
        const val EFFECT_NAME = "matrix"
        const val DEFAULT_CHARACTER_COLUMNS = 120

        // Hirigana and half-width katakana characters, and (reversed) English letters and numbers.
        const val MATRIX_CHARS =
                "ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕ" +
                "ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
        const val MATRIX_REVERSED_CHARS = "QWERTYUIOPASDFGHJKLZXCVBNM1234567890"
        const val NUM_MATRIX_CHARS = MATRIX_CHARS.length + MATRIX_REVERSED_CHARS.length

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): MatrixEffect {
            val numCharColumns = params.getOrElse("numColumns", {DEFAULT_CHARACTER_COLUMNS}) as Int
            return MatrixEffect(rs, numCharColumns)
        }
    }
}