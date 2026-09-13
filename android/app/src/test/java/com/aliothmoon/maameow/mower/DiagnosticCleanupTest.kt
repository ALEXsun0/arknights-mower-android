package com.aliothmoon.maameow.mower

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticCleanupTest {
    @Test fun clearsLogsWithoutBreakingAnOpenAppendWriterOrTouchingUserData() {
        val root = Files.createTempDirectory("diagnostic-clean").toFile()
        try {
            val files = File(root, "files").apply { mkdirs() }; val cache = File(root, "cache")
            val log = File(files, "python.log").apply { writeText("old") }
            val config = File(files, "mower-data/config/conf.yml").apply { parentFile.mkdirs(); writeText("preserve") }
            val image = File(files, "mower-data/screenshot/run_order/1.jpg").apply { parentFile.mkdirs(); writeText("preserve") }
            FileOutputStream(log, true).use { writer ->
                assertEquals(1, DiagnosticCleanup.clear(files, cache, true, false).cleared)
                writer.write("new".toByteArray()); writer.flush()
            }
            assertEquals("new", log.readText())
            assertEquals("preserve", config.readText()); assertEquals("preserve", image.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun exportCleanupOnlyDeletesCompletedOwnedArchives() {
        val root = Files.createTempDirectory("diagnostic-clean").toFile()
        try {
            val files = File(root, "files").apply { mkdirs() }; val cache = File(root, "cache")
            val log = File(files, "python.log").apply { writeText("keep") }
            val dir = File(cache, "diagnostics").apply { mkdirs() }
            val completed = File(dir, "mower-diagnostics-1789296619138-5e63d56f.zip").apply { writeText("export") }
            val partial = File(dir, "mower-diagnostics-1789296619138-5e63d56f.zip.part").apply { writeText("partial") }
            val other = File(dir, "user.zip").apply { writeText("keep") }
            val result = DiagnosticCleanup.clear(files, cache, false, true)
            assertEquals(1, result.cleared); assertFalse(completed.exists())
            assertTrue(partial.exists()); assertTrue(other.exists()); assertEquals("keep", log.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun doesNotTruncateSymlinkTargets() {
        val root = Files.createTempDirectory("diagnostic-clean").toFile()
        try {
            val files = File(root, "files").apply { mkdirs() }
            val private = File(root, "private").apply { writeText("keep") }
            Files.createSymbolicLink(File(files, "python.log").toPath(), private.toPath())
            assertEquals(0, DiagnosticCleanup.clear(files, File(root, "cache"), true, true).cleared)
            assertEquals("keep", private.readText())
        } finally { root.deleteRecursively() }
    }
}
