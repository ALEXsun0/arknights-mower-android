package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundledMaaPreparationTest {
    private fun fixture(block: (File) -> Unit) {
        val data = Files.createTempDirectory("maa-native").toFile()
        try {
            File(data, "maa").mkdirs()
            File(data, "maa/config.json").writeText("user settings")
            File(data, "maa-bundled-pending").writeText("28:100")
            archive(data, listOf(".mower-android.json", "resource/nested/a.txt", "resource/nested/b.txt"))
            block(data)
        } finally { data.deleteRecursively() }
    }

    private fun archive(data: File, names: List<String>) {
        val archive = File(data, "maa-component.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in names) { zip.putNextEntry(ZipEntry(name)); zip.write(name.toByteArray()); zip.closeEntry() }
        }
        File(data, "maa-component.sha256").writeText(MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) })
    }

    @Test fun preparesOnceAndLeavesActiveConfigurationForPythonTransaction() = fixture { data ->
        val progress = mutableListOf<Pair<String, Int>>()
        assertTrue(BundledMaaPreparation.prepare(data) { stage, percent -> progress += stage to percent })
        assertEquals("user settings", File(data, "maa/config.json").readText())
        assertTrue(File(data, "maa-bundled-pending").exists())
        assertTrue(File(data, "maa-bundled-unpacked/resource/nested/b.txt").isFile)
        assertEquals("MAA 资源解压完成" to 100, progress.last())
        assertFalse(BundledMaaPreparation.prepare(data) { _, _ -> fail("repeated extraction") })
    }

    @Test fun interruptedExtractionIsRetriedAndNeverMarkedComplete() = fixture { data ->
        try {
            BundledMaaPreparation.prepare(data) { stage, percent ->
                if (stage == "解压 MAA 资源" && percent > 0) throw InterruptedException()
            }
            fail("expected interruption")
        } catch (_: InterruptedException) { }
        assertFalse(File(data, "maa-bundled-unpacked.json").exists())
        assertEquals("user settings", File(data, "maa/config.json").readText())
        assertTrue(BundledMaaPreparation.prepare(data) { _, _ -> })
    }

    @Test fun rejectsInvalidPathsBeforeWritingEntries() = fixture { data ->
        archive(data, listOf(".mower-android.json", "../escape.txt"))
        try { BundledMaaPreparation.prepare(data) { _, _ -> }; fail("expected invalid path") }
        catch (_: IllegalStateException) { }
        assertFalse(File(data, "escape.txt").exists())
        assertFalse(File(data, "maa-bundled-unpacked/.mower-android.json").exists())
        assertFalse(File(data, "maa-bundled-unpacked.json").exists())
    }

    @Test fun corruptedArchiveKeepsActiveResources() = fixture { data ->
        File(data, "maa-component.sha256").writeText("0".repeat(64))
        try { BundledMaaPreparation.prepare(data) { _, _ -> }; fail("expected checksum failure") }
        catch (_: IllegalStateException) { }
        assertEquals("user settings", File(data, "maa/config.json").readText())
        assertFalse(File(data, "maa-bundled-unpacked.json").exists())
    }
}
