package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DiagnosticFilesTest {
    private val now = LocalDateTime.of(2026, 9, 13, 18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun frame(root: File, time: Long, folder: String = "20260913-18", bytes: Int = 10): File =
        File(root, "$folder/${time * 1000000}.jpg").apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes)); setLastModified(now) }

    @Test fun timeWindowKeepsTracebackAndFiltersOutsideRecords() {
        val text = "2026-09-13 18:00:00 INFO old\nold detail\n2026-09-13 18:21:00 ERROR failure\nTraceback\n  detail\n2026-09-13 18:29:00 INFO done\n"
        assertEquals("2026-09-13 18:21:00 ERROR failure\nTraceback\n  detail\n", DiagnosticFiles.window(text, now - 10 * 60000, now - 2 * 60000))
    }
    @Test fun usesCaptureTimeAndIncludesBothTimeBoundaries() {
        val root = Files.createTempDirectory("frames").toFile()
        try {
            val start = frame(root, now - 60000); val end = frame(root, now)
            frame(root, now - 60001); frame(root, now + 1)
            File(root, "conf.jpg").writeText("not a screenshot")
            val result = DiagnosticFiles.select(root, now - 60000, now, "")
            assertEquals(listOf(start, end), result.frames.map { it.file })
            assertEquals(20, result.bytes)
        } finally { root.deleteRecursively() }
    }
    @Test fun neverFollowsExternalFileOrDirectoryLinks() {
        val root = Files.createTempDirectory("frames").toFile()
        val external = Files.createTempDirectory("private").toFile()
        try {
            val outside = frame(external, now)
            Files.createSymbolicLink(File(root, "linked").toPath(), outside.parentFile.toPath())
            Files.createSymbolicLink(File(root, outside.name).toPath(), outside.toPath())
            assertEquals(0, DiagnosticFiles.select(root, now - 60000, now, "").available)
        } finally { root.deleteRecursively(); external.deleteRecursively() }
    }
    @Test fun compactModePrioritizesOrderAndIncidentFramesAndReportsOmissions() {
        val root = Files.createTempDirectory("frames").toFile()
        try {
            val order = frame(root, now - 600000, "run_order")
            val incident = frame(root, now - 540000)
            frame(root, now)
            val result = DiagnosticFiles.select(root, now - 3600000, now, "2026-09-13 18:21:00 INFO 检测到漏单\n", budget = 20)
            assertEquals(setOf(order, incident), result.frames.map { it.file }.toSet())
            assertEquals(1, result.omitted)
            assertEquals(20, result.bytes)
        } finally { root.deleteRecursively() }
    }
    @Test fun fullModeIncludesAllExistingFramesWithoutChangingFiles() {
        val root = Files.createTempDirectory("frames").toFile()
        try {
            val files = (0..600).map { frame(root, now - it * 1000) }
            val result = DiagnosticFiles.select(root, now - 3600000, now, "", Long.MAX_VALUE, Int.MAX_VALUE)
            assertEquals(601, result.frames.size)
            assertEquals(0, result.omitted)
            assertTrue(files.all { it.exists() && it.length() == 10L })
        } finally { root.deleteRecursively() }
    }
    @Test fun absentOrCleanedFramesAreExplicitlyReported() {
        val root = Files.createTempDirectory("frames").toFile()
        try {
            val present = frame(root, now); val missing = "run_order/${(now - 1000) * 1000000}.jpg"
            val log = "save_screenshot: ${present.relativeTo(root).invariantSeparatorsPath}\nsave_screenshot: $missing\nsave_screenshot: $missing"
            val result = DiagnosticFiles.select(root, now - 60000, now, log)
            assertEquals(listOf(missing), result.missingReferences)
        } finally { root.deleteRecursively() }
    }
    @Test fun oldImportantFramesAreNotIncludedOutsideSelectedTime() {
        val root = Files.createTempDirectory("frames").toFile()
        try {
            frame(root, now - 25 * 3600000, "run_order")
            assertEquals(0, DiagnosticFiles.select(root, now - 24 * 3600000, now, "").available)
        } finally { root.deleteRecursively() }
    }
    @Test fun oversizedFrameDoesNotPreventSmallerFramesBeingExported() {
        val root = Files.createTempDirectory("frames").toFile()
        try {
            frame(root, now - 1000, "run_order", 30)
            val small = frame(root, now, bytes = 5)
            val result = DiagnosticFiles.select(root, now - 60000, now, "", budget = 10)
            assertEquals(listOf(small), result.frames.map { it.file })
            assertEquals(1, result.omitted)
        } finally { root.deleteRecursively() }
    }
}
