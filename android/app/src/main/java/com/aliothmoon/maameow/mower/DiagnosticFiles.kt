package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Metadata-only selection; never changes screenshot retention or follows links. */
internal object DiagnosticFiles {
    const val IMAGE_BUDGET = 128L * 1024 * 1024
    data class Frame(val file: File, val name: String, val captured: Long, val bytes: Long)
    data class Selection(val frames: List<Frame>, val available: Int, val omitted: Int,
                         val missingReferences: List<String>, val availableBytes: Long = 0) {
        val bytes get() = frames.sumOf { it.bytes }
    }
    private val logDate = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private fun logTime(line: String): Long? = logDate.find(line)?.value?.let {
        runCatching { LocalDateTime.parse(it, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
    fun window(text: String, since: Long, until: Long): String = buildString {
        var include = false
        text.lineSequence().forEach { line ->
            logTime(line)?.let { include = it in since..until }
            if (include) appendLine(line)
        }
    }
    fun incidents(text: String): List<Long> = text.lineSequence()
        .filter { it.contains("ERROR") || it.contains("WARNING") || it.contains("检测到漏单") }
        .mapNotNull { logTime(it) }.distinct().toList()

    private fun captured(file: File): Long? {
        val stem = file.nameWithoutExtension
        if (stem.length in 18..19) return stem.toLongOrNull()?.div(1_000_000)
        if (stem.length == 14) return runCatching {
            LocalDateTime.parse(stem, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
        return null
    }
    fun select(root: File, since: Long, until: Long, logs: String,
               budget: Long = IMAGE_BUDGET, maximum: Int = 512): Selection {
        val frames = mutableListOf<Frame>()
        val existing = mutableSetOf<String>()
        if (Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(root.toPath(), 2).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
                    val file = path.toFile()
                    if (file.extension.lowercase() !in setOf("jpg", "jpeg", "png", "webp")) return@forEach
                    val time = captured(file) ?: return@forEach
                    val size = runCatching { Files.size(path) }.getOrDefault(0)
                    val name = root.toPath().relativize(path).toString().replace('\\', '/')
                    if (size > 0) existing += name
                    if (size > 0 && time in since..until) frames += Frame(file, name, time, size)
                }
            }
        }
        val events = incidents(logs)
        val ordered = frames.sortedBy { it.captured }
        val important = ordered.filter { it.name.startsWith("run_order/") || events.any { t -> abs(it.captured - t) <= 120_000 } }
            .sortedWith(compareBy<Frame> { !it.name.startsWith("run_order/") }
                .thenBy { frame -> events.minOfOrNull { abs(frame.captured - it) } ?: 0 }
                .thenByDescending { it.captured })
        // Spread remaining frames over the time range rather than exporting only its tail.
        val sampled = mutableListOf<Frame>()
        // Breadth-first intervals keep a useful overview even under a small byte budget.
        val ranges = java.util.ArrayDeque<Pair<Int, Int>>()
        if (ordered.isNotEmpty()) ranges.add(0 to ordered.lastIndex)
        while (ranges.isNotEmpty()) {
            val (a, b) = ranges.removeFirst(); val mid = (a + b) / 2
            sampled += ordered[mid]
            if (a < mid) ranges.add(a to mid - 1)
            if (mid < b) ranges.add(mid + 1 to b)
        }
        var remaining = budget
        val selected = (important + sampled).distinctBy { it.name }.filter {
            if (remaining >= it.bytes) { remaining -= it.bytes; true } else false
        }.take(maximum).sortedBy { it.captured }
        val references = Regex("save_screenshot: ([A-Za-z0-9_/-]+\\.(?:jpg|jpeg|png|webp))")
            .findAll(logs).map { it.groupValues[1] }.distinct().filter { it !in existing }.toList()
        return Selection(selected, frames.size, frames.size - selected.size, references, frames.sumOf { it.bytes })
    }
}
