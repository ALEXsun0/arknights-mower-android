package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** Only known app-owned log files and completed diagnostic copies are eligible. */
internal object DiagnosticCleanup {
    data class Candidates(val logs: List<File>, val exports: List<File>) {
        val logBytes get() = logs.sumOf { it.length() }
        val exportBytes get() = exports.sumOf { it.length() }
    }
    data class Result(val cleared: Int, val failures: List<String>)
    private fun regular(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    fun candidates(files: File, cache: File): Candidates {
        val logs = MowerLogFiles.all(File(files, "mower-data/log")) +
            listOf("python.log", "network-events.log", "startup-check.txt").map { File(files, it) }
        val directory = File(cache, "diagnostics")
        val exports = directory.listFiles().orEmpty().filter {
            regular(it) && it.canonicalFile.parentFile == directory.canonicalFile &&
                Regex("mower-diagnostics(?:-\\d+-[a-f0-9]{8})?\\.zip").matches(it.name)
        }
        return Candidates(logs.filter(::regular), exports)
    }
    fun clear(files: File, cache: File, logs: Boolean, exports: Boolean): Result {
        val selected = candidates(files, cache); val failures = mutableListOf<String>(); var cleared = 0
        if (logs) selected.logs.forEach { file ->
            try {
                // Preserve the inode: the Python logger may already hold an O_APPEND descriptor.
                FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS).use { }
                cleared++
            } catch (e: Exception) { failures += file.name }
        }
        if (exports) selected.exports.forEach { file ->
            if (file.delete()) cleared++ else failures += file.name
        }
        return Result(cleared, failures)
    }
}
