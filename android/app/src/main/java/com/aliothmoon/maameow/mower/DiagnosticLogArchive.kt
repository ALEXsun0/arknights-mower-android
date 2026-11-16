package com.aliothmoon.maameow.mower

import java.io.File
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption

internal object DiagnosticLogArchive {
    data class Source(val file: File, val bytes: Long)

    fun snapshot(files: File, background: File = File("/data/local/tmp/mower-background-launch.log")): Map<String, Source> {
        val sources = linkedMapOf<String, Source>()
        fun add(name: String, file: File) {
            if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.canRead()) {
                sources[name] = Source(file, file.length())
            }
        }
        add("runtime.log", File(files, "python.log"))
        MowerLogFiles.all(File(files, "mower-data/log")).forEach { add("mower/${it.name}", it) }
        add("startup-check.txt", File(files, "startup-check.txt"))
        add("network-events.log", File(files, "network-events.log"))
        add("background.log", background)
        return sources
    }

    private class SnapshotInput(private val input: InputStream, var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            return input.read().also { if (it >= 0) remaining-- }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            return input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                .also { if (it > 0) remaining -= it }
        }
        override fun close() = input.close()
    }

    private val pendingToken = Regex("(?i)token[=\\s:\"']*$")

    fun write(source: Source, output: OutputStream, redact: (String) -> String, progress: () -> Unit) {
        val input = SnapshotInput(Files.newInputStream(source.file.toPath(), LinkOption.NOFOLLOW_LINKS), source.bytes)
        val bufferedOutput = BufferedOutputStream(output, 64 * 1024)
        input.reader(Charsets.UTF_8).buffered(64 * 1024).use { reader ->
            var tokenContinues = false
            while (true) {
                progress()
                val line = reader.readLine() ?: break
                val text = if (tokenContinues) "token=$line" else line
                val sanitized = redact(text).let { if (tokenContinues) it.removePrefix("token=") else it }
                tokenContinues = pendingToken.containsMatchIn(text)
                bufferedOutput.write(sanitized.toByteArray(Charsets.UTF_8))
                bufferedOutput.write('\n'.code)
            }
            check(input.remaining == 0L) { "日志在导出期间被清空或缩短，请刷新后重新导出" }
            bufferedOutput.flush()
        }
    }
}
