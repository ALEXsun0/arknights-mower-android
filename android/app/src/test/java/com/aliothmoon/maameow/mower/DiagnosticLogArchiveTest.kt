package com.aliothmoon.maameow.mower

import android.app.Application
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DiagnosticLogArchiveTest {
    private lateinit var context: Context

    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.filesDir.deleteRecursively(); context.filesDir.mkdirs()
        context.cacheDir.deleteRecursively(); context.cacheDir.mkdirs()
    }

    private fun saved(name: String, text: String) = File(context.filesDir, name).apply {
        parentFile!!.mkdirs(); writeText(text)
    }

    @Test fun archiveIncludesEntireLargeLogsAndAllRotationsOutsideScreenshotWindow() {
        val large = "2026-01-01 00:00:00 old record\n".repeat(160000)
        assertTrue(large.toByteArray().size > 4 * 1024 * 1024)
        saved("python.log", large + "token=private_token\n")
        saved("mower-data/log/runtime.log", large)
        for (day in 1..6) saved("mower-data/log/runtime.log.2026-01-0$day", "rotation $day\n")
        saved("startup-check.txt", "startup\n")
        saved("network-events.log", "network history\n")
        saved("mower-data/config/conf.yml", "not a log\n")
        val plan = MowerDiagnostics.prepare(context, 1000, 2000, false, false)
        saved("mower-data/log/runtime.log.2026-01-07", "saved after preview\n")

        val started = System.nanoTime()
        val archive = MowerDiagnostics.build(context, plan) { _, _, _ -> }
        println("完整日志导出：输入 ${large.toByteArray().size * 2L} 字节，打包 ${(System.nanoTime() - started) / 1000000} 毫秒，ZIP ${archive.length()} 字节")

        ZipFile(archive).use { zip ->
            fun content(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
            assertEquals(large + "token=[redacted]\n", content("runtime.log"))
            assertEquals(large, content("mower/runtime.log"))
            for (day in 1..6) assertEquals("rotation $day\n", content("mower/runtime.log.2026-01-0$day"))
            assertEquals("saved after preview\n", content("mower/runtime.log.2026-01-07"))
            assertEquals("startup\n", content("startup-check.txt"))
            assertEquals("network history\n", content("network-events.log"))
            assertNull(zip.getEntry("android.log"))
            assertFalse(zip.entries().asSequence().any { it.name.contains("conf.yml") })
            val manifest = JSONObject(content("manifest.json"))
            assertEquals("complete_saved_files", manifest.getString("log_scope"))
            assertEquals(11, manifest.getJSONArray("logs").length())
        }
    }

    @Test fun sourceSnapshotExcludesLaterAppendsAndKeepsSplitTokenRedacted() {
        val file = saved("python.log", "begin\ntoken:\n\nsecret_value\nend\n")
        val source = DiagnosticLogArchive.Source(file, file.length())
        file.appendText("added later\n")
        val output = ByteArrayOutputStream()

        DiagnosticLogArchive.write(source, output, MowerDiagnostics::redact) { }

        assertEquals("begin\ntoken:\n\n[redacted]\nend\n", output.toString("UTF-8"))
    }

    @Test fun screenshotWindowStillExcludesImagesOutsideSelectedRange() {
        val captured = 1767225600000L
        saved("python.log", "older log stays included\n")
        val inside = "${captured * 1000000}.jpg"
        val outside = "${(captured + 60000) * 1000000}.jpg"
        saved("mower-data/screenshot/$inside", "selected image")
        saved("mower-data/screenshot/$outside", "outside image")
        val plan = MowerDiagnostics.prepare(context, captured - 1000, captured + 1000, true, false)

        val archive = MowerDiagnostics.build(context, plan) { _, _, _ -> }

        ZipFile(archive).use { zip ->
            assertEquals("older log stays included\n", zip.getInputStream(zip.getEntry("runtime.log")).bufferedReader().use { it.readText() })
            assertNotNull(zip.getEntry("screenshots/$inside"))
            assertNull(zip.getEntry("screenshots/$outside"))
            assertEquals(1, zip.entries().asSequence().count { it.name.startsWith("screenshots/") })
        }
    }

    @Test fun logsOnlyExportDoesNotDependOnScreenshotTimeRange() {
        saved("python.log", "complete saved log\n")
        val plan = MowerDiagnostics.prepare(context, 2000, 1000, false, false)

        val archive = MowerDiagnostics.build(context, plan) { _, _, _ -> }

        ZipFile(archive).use { zip ->
            assertEquals("complete saved log\n", zip.getInputStream(zip.getEntry("runtime.log")).bufferedReader().use { it.readText() })
        }
        try {
            MowerDiagnostics.prepare(context, 2000, 1000, true, false)
            fail("Screenshot selection still requires a valid range")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("开始时间"))
        }
    }

    @Test fun truncationFailsExportAndRemovesPartialArchive() {
        val file = saved("python.log", "required record\n")
        val plan = MowerDiagnostics.prepare(context, 1000, 2000, false, false)
        try {
            MowerDiagnostics.build(context, plan) { _, _, name -> if (name == "runtime.log") file.writeText("") }
            fail("A truncated log must not produce a successful export")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("日志在导出期间"))
        }
        assertTrue(File(context.cacheDir, "diagnostics").listFiles().orEmpty().isEmpty())
    }

    @Test fun fileSelectionDoesNotFollowLinksOrCollectConfiguration() {
        val target = saved("private.txt", "private\n")
        Files.createSymbolicLink(File(context.filesDir, "python.log").toPath(), target.toPath())
        saved("mower-data/config/conf.yml", "private config\n")

        assertTrue(DiagnosticLogArchive.snapshot(context.filesDir, File(context.filesDir, "absent-background.log")).isEmpty())
    }
}
