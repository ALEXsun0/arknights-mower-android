package com.aliothmoon.maameow.mower

import java.io.File
import java.io.RandomAccessFile

internal object MowerLogFiles {
    fun all(directory: File): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.canonicalFile.parentFile == directory.canonicalFile &&
            (it.name == "runtime.log" || Regex("runtime\\.log\\.\\d{4}-\\d{2}-\\d{2}(?:_\\d{2})?").matches(it.name)) }
        .sortedByDescending { it.lastModified() }

    fun recent(directory: File): List<File> = all(directory).take(4)

    fun tail(file: File, limit: Int): String = RandomAccessFile(file, "r").use {
        val offset = maxOf(0, it.length() - limit)
        it.seek(offset)
        val bytes = ByteArray((it.length() - offset).toInt())
        it.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        if (offset > 0) "[仅显示日志末尾]\n" + text.substringAfter('\n', "") else text
    }
}
