package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.util.*

class LifeBitmapGenerator() {

    internal var thread: Thread? = null

    fun start(callback: (Bitmap) -> Unit) {
        if (thread == null) {
            thread = Thread({runInThread(callback)})
            thread!!.start()
        }
    }

    fun pause() {
        thread = null
    }

    private fun runInThread(callback: (Bitmap) -> Unit) {
        val bitmap1 = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        val canvas1 = Canvas(bitmap1)
        val rand = Random()
        val paint = Paint()

        val boardHeight = 80
        val boardWidth = 80
        val cellHeight = 10f
        val cellWidth = 10f
        var board = BooleanArray(boardHeight * boardWidth, {rand.nextBoolean()})
        var nextBoard = BooleanArray(boardHeight * boardWidth)


        while (true) {
            if (Thread.currentThread() != thread) break
            /*
            val x = Math.abs(rand.nextInt() % 700).toFloat()
            val y = Math.abs(rand.nextInt() % 700).toFloat()
            val isRed = rand.nextBoolean()
            paint.color = (if (isRed) 0xffff0000 else 0xff0000ff).toInt()
            canvas1.drawRect(x, y, x + 100, y + 100, paint)
            */
            computeNextState(board, nextBoard, boardWidth, boardHeight, rand)
            var tmp = board
            board = nextBoard
            nextBoard = tmp

            var index = 0
            var h = 0
            while (h < boardHeight) {
                var w = 0
                val y = h * cellHeight
                while (w < boardWidth) {
                    paint.color = (if (board[index]) 0xffff0000 else 0xff0000ff).toInt()
                    val x = w * cellWidth
                    canvas1.drawRect(x, y, x + cellWidth, y + cellHeight, paint)
                    w++
                    index++
                }
                h++
            }


            callback(bitmap1)
            Thread.sleep(15)
        }
    }

    companion object {
        fun computeNextState(board: BooleanArray, next: BooleanArray, width: Int, height: Int, rand: Random) {
            var index = 0
            var h = 0
            while (h < height) {
                val topEdge = (h == 0)
                val bottomEdge = (h == height - 1)
                var w = 0
                while (w < width) {
                    val leftEdge = (w == 0)
                    val rightEdge = (w == width - 1)

                    var count = 0
                    if (!topEdge) {
                        val upIndex = index - width
                        if (!leftEdge && board[upIndex - 1]) count++
                        if (board[upIndex]) count++
                        if (!rightEdge && board[upIndex + 1]) count++
                    }
                    if (!leftEdge && board[index - 1]) count++
                    if (!rightEdge && board[index + 1]) count++
                    if (!bottomEdge) {
                        val downIndex = index + width
                        if (!leftEdge && board[downIndex - 1]) count++
                        if (board[downIndex]) count++
                        if (!rightEdge && board[downIndex + 1]) count++
                    }
                    next[index] = board[index]
                    if (rand.nextDouble() < 0.1) {
                        next[index] = (count == 3 || (count == 2 && board[index]))
                    }

                    w++
                    index++
                }
                h++
            }
        }
    }
}
