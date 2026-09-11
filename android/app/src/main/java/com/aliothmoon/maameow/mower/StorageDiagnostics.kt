package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/** On-demand metadata only; never follow symlinks or read configuration contents. */
internal object StorageDiagnostics {
    private fun size(file: File): String = runCatching {
        val bytes = Files.walk(file.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .mapToLong { Files.size(it) }.sum()
        }
        String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
    }.getOrDefault("无法完整读取")

    fun report(files: File, cache: File): String = buildString {
        appendLine("存储占用（文件大小，不含 APK 与系统分配开销）")
        files.listFiles().orEmpty().sortedBy { it.name }.forEach {
            appendLine("${it.name}: ${size(it)}")
        }
        appendLine("cache: ${size(cache)}")
        appendLine("Mower 数据明细")
        File(files, "mower-data").listFiles().orEmpty().sortedBy { it.name }.forEach {
            appendLine("mower-data/${it.name}: ${size(it)}")
        }
    }
}
