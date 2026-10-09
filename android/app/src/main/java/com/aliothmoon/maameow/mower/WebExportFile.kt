package com.aliothmoon.maameow.mower

import java.io.File
import java.io.OutputStream
import java.util.Base64

/** One bounded, sequential export; only a completed file can reach the system picker. */
internal class WebExportFile(directory: File, val name: String, val mime: String, val size: Long) : AutoCloseable {
    val file: File
    private val output: OutputStream
    private var received = 0L
    private var finished = false

    init {
        require(size in 0..MAX_BYTES) { "导出文件超过 512 MiB 限制" }
        require(name.isNotBlank() && name !in listOf(".", "..") && name.length <= 200 && name.none { it == '/' || it == '\\' || it.code < 32 }) { "导出文件名无效" }
        require(mime.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+"))) { "导出文件类型无效" }
        directory.mkdirs()
        file = File.createTempFile("web-export-", ".tmp", directory)
        output = try { file.outputStream() } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    fun append(encoded: String) {
        check(!finished) { "导出文件已结束" }
        require(encoded.length <= CHUNK_BYTES / 3 * 4) { "导出数据块过大" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(received + bytes.size <= size) { "导出数据超过声明大小" }
        output.write(bytes)
        received += bytes.size
    }

    fun finish() {
        check(!finished && received == size) { "导出数据不完整" }
        output.close()
        finished = true
    }

    fun copyTo(target: OutputStream, cancelled: () -> Boolean) {
        check(finished) { "导出数据尚未准备完成" }
        file.inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(!cancelled()) { "导出已取消" }
                val count = source.read(buffer)
                if (count < 0) break
                target.write(buffer, 0, count)
            }
        }
    }

    override fun close() {
        try { output.close() } finally { file.delete() }
    }

    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        const val CHUNK_BYTES = 48 * 1024
    }
}
