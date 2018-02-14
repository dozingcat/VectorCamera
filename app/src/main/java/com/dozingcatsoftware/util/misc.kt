package com.dozingcatsoftware.util

import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream

inline fun toUInt(b: Byte): Int {
    return b.toInt() and 0xff
}

inline fun addAlpha(color: Int): Int {
    return 0xff000000.toInt() or color
}

fun intFromArgbList(a: List<Int>): Int {
    if (a.size == 4) {
        return (a[0] shl 24) or (a[1] shl 16) or (a[2] shl 8) or a[3]
    }
    if (a.size == 3) {
        return (255 shl 24) or (a[0] shl 16) or (a[1] shl 8) or a[2]
    }
    throw IllegalArgumentException("List must have 3 or 4 items")
}

fun readBytesIntoBuffer(input: InputStream, bytesToRead: Int, buffer: ByteArray, offset: Int = 0) {
    var totalBytesRead = 0
    while (totalBytesRead < bytesToRead) {
        totalBytesRead += input.read(buffer, offset + totalBytesRead, bytesToRead - totalBytesRead)
    }
}

/**
 * Creates a temporary file in `tmpDir`, opens a stream to it, and passes the stream to `block`.
 * After `block` returns, renames the file to `target`.
 */
fun writeFileAtomicallyUsingTempDir(target: File, tmpDir: File, block: (FileOutputStream) -> Unit) {
    tmpDir.mkdirs()
    val tmpFile = createTempFile(directory=tmpDir)
    try {
        FileOutputStream(tmpFile).use(block)
        val result = tmpFile.renameTo(target)
        if (!result) {
            throw FileNotFoundException("Failed to rename temp file")
        }
    }
    finally {
        tmpFile.delete()
    }
}
