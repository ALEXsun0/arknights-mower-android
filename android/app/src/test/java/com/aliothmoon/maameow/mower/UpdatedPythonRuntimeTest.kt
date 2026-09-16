package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class UpdatedPythonRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ident = "a".repeat(64)
    private val prior = "b".repeat(64)
    private fun manifest(format: Int) = JSONObject().put("kind", "mower-android").put("format", format)
        .put("platform", "android").put("arch", "arm64").put("runtime_api", 1).put("python", "3.12")
    private fun program(data: File, id: String = ident, meta: JSONObject = manifest(1)): File =
        File(data, "mower-programs/$id").apply {
            File(this, "mower").mkdirs(); File(this, "mower/server.py").writeText("pass")
            File(this, "mower-android.json").writeText(meta.toString())
        }
    private fun state(data: File, value: JSONObject) {
        File(data, "mower-programs").mkdirs()
        File(data, "mower-programs/active.json").writeText(value.toString())
    }
    private fun current(data: File) = JSONObject(File(data, "mower-programs/active.json").readText())
    private fun archive(entries: Map<String, ByteArray>): File = temporary.newFile().also { file ->
        ZipOutputStream(XZOutputStream(file.outputStream(), LZMA2Options(1))).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("expected rejection") } catch (_: IllegalStateException) {} }

    /** Opt-in: the official format-2 artifact exercises the real archive/link layout. */
    @Test fun realPublishedRuntimeInstallsCachesAndRollsBack() {
        val archivePath = System.getenv("MOWER_TEST_FULL_UPDATE")
        assumeTrue("Set MOWER_TEST_FULL_UPDATE to test a real Android update", !archivePath.isNullOrBlank())
        val published = File(archivePath!!)
        assertTrue("Missing integration update: $published", published.isFile)
        val data = temporary.newFolder(); val bundled = temporary.newFolder()
        val directory = File(data, "mower-programs/$ident").apply { mkdirs() }
        ZipFile(published).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val destination = File(directory, entry.name)
                check(destination.canonicalPath.startsWith(directory.canonicalPath + "/"))
                if (entry.isDirectory) destination.mkdirs() else {
                    destination.parentFile!!.mkdirs()
                    zip.getInputStream(entry).use { source -> destination.outputStream().use { source.copyTo(it) } }
                }
            }
        }
        val manifest = JSONObject(File(directory, "mower-android.json").readText())
        assertEquals(2, manifest.getInt("format"))
        val apk = manifest.optInt("min_apk", 29)
        state(data, JSONObject().put("id", ident).put("pending", true).put("apk_generation", "29:integration"))
        val manager = UpdatedPythonRuntime(data, bundled, "29:integration")
        val selected = manager.select(apk)
        assertEquals(ident, selected.id)
        assertEquals(File(directory, "runtime-root"), selected.root)
        assertTrue(current(data).getBoolean("booting"))
        val marker = File(selected.root, ".mower-updated-runtime")
        assertEquals(manifest.getJSONObject("runtime").getString("sha256"), marker.readText())
        val installedAt = marker.lastModified()
        // Simulate readiness; removing only this test's archive proves caching avoids extraction.
        state(data, JSONObject().put("id", ident).put("apk_generation", "29:integration"))
        assertTrue(File(directory, "python-runtime.zip.xz").delete())
        assertEquals(selected, manager.select(apk))
        assertEquals(installedAt, marker.lastModified())
        // A pending launch never reached readiness: select the previously working program.
        program(data, prior)
        state(data, JSONObject().put("id", ident).put("previous", prior).put("booting", true)
            .put("pending", true).put("apk_generation", "29:integration"))
        val restored = manager.select(apk)
        assertEquals(prior, restored.id)
        assertEquals(bundled, restored.root)
        assertTrue(current(data).getBoolean("rollback"))
    }

    @Test fun pendingSelectionMarksBootBeforeReturningAndFailedLaunchRollsBack() {
        val data = temporary.newFolder(); val bundled = temporary.newFolder()
        program(data); program(data, prior)
        state(data, JSONObject().put("id", ident).put("previous", prior).put("pending", true).put("apk_generation", "29:1"))
        val manager = UpdatedPythonRuntime(data, bundled, "29:1")
        assertEquals(ident, manager.select(29).id)
        assertTrue(current(data).getBoolean("booting"))
        assertEquals(prior, manager.select(29).id)
        assertTrue(current(data).getBoolean("rollback"))
    }
    @Test fun apkReplacementUsesBundledAndRetainsWorkingUpdateForRollback() {
        val data = temporary.newFolder(); val bundled = temporary.newFolder(); program(data)
        state(data, JSONObject().put("id", ident).put("apk_generation", "28:1"))
        val selected = UpdatedPythonRuntime(data, bundled, "29:1").select(29)
        assertNull(selected.id); assertEquals(bundled, selected.root)
        assertEquals(ident, current(data).getString("previous"))
        assertTrue(current(data).getBoolean("booting"))
    }
    @Test fun invalidRuntimeFailsBeforeLaunchThenRestoresPrevious() {
        val data = temporary.newFolder(); val bundled = temporary.newFolder()
        program(data, ident, manifest(2).put("runtime", JSONObject().put("file", "../bad")))
        program(data, prior)
        state(data, JSONObject().put("id", ident).put("previous", prior).put("pending", true).put("apk_generation", "29:1"))
        val manager = UpdatedPythonRuntime(data, bundled, "29:1")
        rejected { manager.select(29) }
        assertTrue(current(data).getBoolean("booting"))
        assertEquals(prior, manager.select(29).id)
    }
    @Test fun extractsRootfsAndUsesCachedValidatedRuntime() = extractsVersion("3.12")
    @Test fun upgradedPythonInterpreterIsSelectedAndValidated() = extractsVersion("3.13")
    private fun extractsVersion(python: String) {
        val data = temporary.newFolder(); val bundled = temporary.newFolder()
        val entries = StartupChecks.runtimeFiles.take(4).map { it.replace("3.12", python) }.associateWith { byteArrayOf(127, 69, 76, 70, 1) }.toMutableMap()
        entries[".symlinks.json"] = "{}".toByteArray()
        val packed = archive(entries)
        val hash = MessageDigest.getInstance("SHA-256").digest(packed.readBytes()).joinToString("") { "%02x".format(it) }
        val meta = manifest(2).put("python", python).put("min_apk", 29).put("runtime", JSONObject().put("file", "python-runtime.zip.xz")
            .put("sha256", hash).put("unpacked_size", entries.values.sumOf { it.size }))
        val folder = program(data, ident, meta); packed.copyTo(File(folder, "python-runtime.zip.xz"))
        state(data, JSONObject().put("id", ident).put("apk_generation", "29:1"))
        val manager = UpdatedPythonRuntime(data, bundled, "29:1")
        val selected = manager.select(29)
        assertEquals(File(folder, "runtime-root"), selected.root)
        assertTrue(File(selected.root, ".mower-updated-runtime").isFile)
        assertTrue(File(selected.root, "mower").isDirectory)
        File(folder, "python-runtime.zip.xz").delete()
        assertEquals(selected, manager.select(29))
    }
    @Test fun rejectsTraversalOversizedPayloadAndHostFiles() {
        for (entries in listOf(mapOf("../outside" to byteArrayOf(1)), mapOf("mower/mower_android/x" to byteArrayOf(1)))) {
            rejected { UpdatedPythonRuntime.extract(archive(entries), temporary.newFolder(), 1024) }
        }
        rejected { UpdatedPythonRuntime.extract(archive(mapOf("file" to ByteArray(100))), temporary.newFolder(), 10) }
    }
    @Test fun rejectsDeclaredSizeMismatch() {
        rejected { UpdatedPythonRuntime.extract(archive(mapOf(".symlinks.json" to "{}".toByteArray())), temporary.newFolder(), 3) }
    }
    @Test fun restoresAbsoluteGuestLinksInsideRootAndRejectsEscapes() {
        val root = temporary.newFolder()
        UpdatedPythonRuntime.extract(archive(mapOf("usr/bin/a" to byteArrayOf(1),
            ".symlinks.json" to "{\"bin\":\"/usr/bin\"}".toByteArray())), root, 19)
        assertEquals(File(root, "usr/bin/a").canonicalFile, File(root, "bin/a").canonicalFile)
        for (links in listOf("{\"bad\":\"../outside\"}", "{\"usr\":\"/elsewhere\"}")) {
            rejected { UpdatedPythonRuntime.extract(archive(mapOf("usr/bin/a" to byteArrayOf(1),
                ".symlinks.json" to links.toByteArray())), temporary.newFolder(), 1L + links.toByteArray().size) }
        }
        assertTrue(Files.isSymbolicLink(File(root, "bin").toPath()))
    }
}
