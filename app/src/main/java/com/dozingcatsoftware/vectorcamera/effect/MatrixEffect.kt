package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.util.Size
import com.dozingcatsoftware.util.reuseOrCreate2dAllocation
import com.dozingcatsoftware.vectorcamera.CameraImage
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt

class Raindrop(val x: Int, val y: Int, val startTimestamp: Long) {
    var length = -1L
}

class MatrixEffect(val rs: RenderScript, numPreferredCharColumns: Int): Effect {

    private val textParams = TextParams(numPreferredCharColumns, 10, 1.8)
    private val raindrops = HashSet<Raindrop>()
    private var raindropLifetimeMillis = 5000L
    private var raindropMillisPerMovement = 500L
    private var newRaindropProbPerFrame = 0.05
    private var maxRaindropLength = 10
    // Every frame, this fraction of characters will change.
    private var charChangeProbPerFrame = 1.0 / 300

    private var characterTemplateAllocation: Allocation? = null
    private var characterIndexAllocation: Allocation? = null
    private var characterColorAllocation: Allocation? = null
    private var averageBrightnessAllocation: Allocation? = null
    private var bitmapOutputAllocation: Allocation? = null
    private val script = ScriptC_ascii(rs)

    override fun effectName() = EFFECT_NAME

    private fun updateGridCharacters(metrics: TextMetrics, rand: Random) {
        val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
        val prevCharAlloc = characterIndexAllocation
        characterIndexAllocation = reuseOrCreate2dAllocation(
                characterIndexAllocation, rs, Element::U32,
                metrics.numCharacterColumns, metrics.numCharacterRows)
        if (prevCharAlloc == characterIndexAllocation) {
            // Change characters.
            val newChar = IntArray(1)
            val numChanged = (numCells * charChangeProbPerFrame).toInt()
            for (i in 0 until numChanged) {
                newChar[0] = rand.nextInt(NUM_MATRIX_CHARS)
                val offset = rand.nextInt(numCells)
                characterIndexAllocation!!.copy1DRangeFrom(offset, 1, newChar)
            }
        }
        else {
            // Initialize with random characters.
            val indices = IntArray(numCells)
            for (i in 0 until numCells) {
                indices[i] = rand.nextInt(NUM_MATRIX_CHARS)
            }
            characterIndexAllocation!!.copyFrom(indices)
        }
    }

    private fun maxRaindrops(metrics: TextMetrics): Int {
        return Math.min(10, metrics.numCharacterColumns / 10)
    }

    private fun updateGridColors(
            ci: CameraImage, metrics: TextMetrics, blockAverages: ByteArray, rand: Random) {
        val prevColorAlloc = characterColorAllocation
        characterColorAllocation = reuseOrCreate2dAllocation(
                characterColorAllocation, rs, Element::RGBA_8888,
                metrics.numCharacterColumns, metrics.numCharacterRows)
        if (prevColorAlloc != characterColorAllocation) {
            raindrops.clear()
        }
        val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
        val ca = ByteArray(4 * numCells)
        for (i in 0 until numCells) {
            // A component of blockAverages is brightness, map to green.
            ca[4*i] = 0
            ca[4*i + 1] = blockAverages[4*i + 3]
            ca[4*i + 2] = 0
            ca[4*i + 3] = 0xff.toByte()
        }
        var rit = raindrops.iterator()
        while (rit.hasNext()) {
            val drop = rit.next()
            val diff = ci.timestamp - drop.startTimestamp
            if (diff < 0 || diff > raindropLifetimeMillis) {
                rit.remove()
            }
        }
        if (raindrops.size < maxRaindrops(metrics) && rand.nextDouble() < newRaindropProbPerFrame) {
            raindrops.add(Raindrop(
                    rand.nextInt(metrics.numCharacterColumns),
                    rand.nextInt(metrics.numCharacterRows),
                    ci.timestamp))
        }
        val maxDist = raindropLifetimeMillis / raindropMillisPerMovement
        for (drop in raindrops) {
            val dir = if (ci.orientation.yFlipped) -1 else 1
            val ticks = (ci.timestamp - drop.startTimestamp) / raindropMillisPerMovement
            // If the raindrop is extending into a new cell, change that cell's character.
            val length = min(maxRaindropLength.toLong(), ticks)
            val growing = (length > drop.length)
            drop.length = length
            for (dy in 0..length) {
                val y = drop.y + (dy * dir).toInt()
                if (y < 0 || y >= metrics.numCharacterRows) {
                    break
                }
                if (growing && (dy == length)) {
                    val newChar = intArrayOf(rand.nextInt(NUM_MATRIX_CHARS))
                    characterIndexAllocation!!.copy2DRangeFrom(drop.x, y, 1, 1, newChar)
                }
                val fraction = 1.0 - (length - dy).toDouble() / maxDist
                val brightness = (255 * fraction).roundToInt().toByte()
                val baseOffset = 4 * (y * metrics.numCharacterColumns + drop.x)
                ca[baseOffset] = if (dy == length) brightness else 0
                ca[baseOffset + 1] = brightness
                ca[baseOffset + 2] = if (dy == length) brightness else 0
                ca[baseOffset + 3] = 0xff.toByte()
            }
        }
        characterColorAllocation!!.copyFrom(ca)
    }

    private fun updateCharTemplateBitmap(charPixelSize: Size) {
        val charBitmapWidth = charPixelSize.width * NUM_MATRIX_CHARS
        val origTemplateAlloc = characterTemplateAllocation
        characterTemplateAllocation = reuseOrCreate2dAllocation(characterTemplateAllocation,
                rs, Element::RGBA_8888, charBitmapWidth, charPixelSize.height)
        // Only recreate character bitmap if the dimensions changed.
        if (characterTemplateAllocation != origTemplateAlloc) {
            val paint = Paint()
            paint.textSize = charPixelSize.height * 5f / 6
            paint.color = Color.argb(255, 0, 255, 0)

            val charBitmap = Bitmap.createBitmap(
                    charBitmapWidth, charPixelSize.height, Bitmap.Config.ARGB_8888)
            val charBitmapCanvas = Canvas(charBitmap)
            charBitmapCanvas.drawColor(Color.argb(255, 0, 0, 0))
            val normalChars = MATRIX_NORMAL_CHARS
            val reversedChars = MATRIX_REVERSED_CHARS
            val numNormalChars = normalChars.length
            for (i in 0 until numNormalChars) {
                charBitmapCanvas.drawText(normalChars[i].toString(),
                        (i * charPixelSize.width).toFloat(), charPixelSize.height - 1f, paint)
            }
            // Reversed characters are drawn at negative X positions so that after the scale
            // transform they'll be in the right place.
            charBitmapCanvas.scale(-1f, 1f)
            for (i in 0 until reversedChars.length) {
                charBitmapCanvas.drawText(reversedChars[i].toString(),
                        -((normalChars.length + i + 1) * charPixelSize.width).toFloat(),
                        charPixelSize.height - 1f, paint)
            }
            characterTemplateAllocation!!.copyFrom(charBitmap)
        }
    }

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val rand = Random(cameraImage.timestamp)
        val metrics = textParams.getTextMetrics(cameraImage, cameraImage.displaySize)
        val resultBitmap = Bitmap.createBitmap(
                metrics.outputSize.width, metrics.outputSize.height, Bitmap.Config.ARGB_8888)
        val cps = metrics.charPixelSize

        updateCharTemplateBitmap(cps)

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
        script._numCharacters = NUM_MATRIX_CHARS
        // Only flip on the final output, otherwise we'll double flip and end up with no change.
        script._flipHorizontal = false
        script._flipVertical = false
        script._portrait = metrics.isPortrait
        script._colorMode = 0
        if (cameraImage.planarYuvAllocations != null) {
            script._hasSingleYuvAllocation = false
            script._yInput = cameraImage.planarYuvAllocations.y
            script._uInput = cameraImage.planarYuvAllocations.u
            script._vInput = cameraImage.planarYuvAllocations.v
        }
        else {
            script._hasSingleYuvAllocation = true
            script._yuvInput = cameraImage.singleYuvAllocation
        }

        averageBrightnessAllocation = reuseOrCreate2dAllocation(averageBrightnessAllocation,
                rs, Element::RGBA_8888, metrics.numCharacterColumns, metrics.numCharacterRows)
        script.forEach_computeCharacterInfoForBlock(averageBrightnessAllocation)
        val blockAverages = ByteArray(averageBrightnessAllocation!!.bytesSize)
        averageBrightnessAllocation!!.copyTo(blockAverages)

        updateGridCharacters(metrics, rand)
        updateGridColors(cameraImage, metrics, blockAverages, rand)

        script._flipHorizontal = cameraImage.orientation.xFlipped
        script._flipVertical = cameraImage.orientation.yFlipped
        script.forEach_writeCharacterToBitmapWithColor(
                characterIndexAllocation, characterColorAllocation)
        bitmapOutputAllocation!!.copyTo(resultBitmap)
        return resultBitmap
    }

    companion object {
        const val EFFECT_NAME = "matrix"
        const val DEFAULT_CHARACTER_COLUMNS = 120

        // Hirigana and half-width katakana characters, and (reversed) English letters and numbers.
        const val MATRIX_NORMAL_CHARS =
                "ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕ" +
                "ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
        const val MATRIX_REVERSED_CHARS = "QWERTYUIOPASDFGHJKLZXCVBNM1234567890"
        const val NUM_MATRIX_CHARS = MATRIX_NORMAL_CHARS.length + MATRIX_REVERSED_CHARS.length

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): MatrixEffect {
            val numCharColumns = params.getOrElse("numColumns", {DEFAULT_CHARACTER_COLUMNS}) as Int
            return MatrixEffect(rs, numCharColumns)
        }
    }
}