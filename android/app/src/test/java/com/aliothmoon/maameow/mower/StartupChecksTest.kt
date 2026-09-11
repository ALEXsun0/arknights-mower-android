package com.aliothmoon.maameow.mower

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupChecksTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun runtime(): File = temporary.newFolder().also { root ->
        StartupChecks.runtimeFiles.forEach { name ->
            File(root, name).apply {
                parentFile!!.mkdirs(); writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 1)); setExecutable(true)
            }
        }
        File(root, ".mower-runtime").writeText("valid-version")
    }

    @Test fun matchingMarkerDoesNotHideMissingOrEmptyFiles() {
        val root = runtime()
        assertTrue(StartupChecks.incompleteRuntime(root).isEmpty())
        File(root, "mower/CHANGELOG.md").delete()
        File(root, "mower/ui/dist/index.html").writeText("")
        assertEquals(setOf("mower/CHANGELOG.md", "mower/ui/dist/index.html"), StartupChecks.incompleteRuntime(root).toSet())
    }

    @Test fun brokenPythonLinkAndInvalidExecutableAreDetected() {
        val root = runtime()
        val python = File(root, "usr/local/bin/python")
        python.delete()
        Files.createSymbolicLink(python.toPath(), File("missing-python").toPath())
        File(root, "usr/bin/env").writeText("broken")
        assertTrue(StartupChecks.incompleteRuntime(root).containsAll(listOf("usr/local/bin/python", "usr/bin/env")))
    }

    @Test fun normalChecksDoNotModifyInstalledFiles() {
        val root = runtime()
        val marker = File(root, ".mower-runtime")
        val before = marker.lastModified()
        repeat(2) { assertTrue(StartupChecks.incompleteRuntime(root).isEmpty()) }
        assertEquals(before, marker.lastModified())
        assertEquals("valid-version", marker.readText())
    }

    @Test fun nativeMissingLibraryGivesActionableError() {
        val failure = assertThrows(IllegalStateException::class.java) { StartupChecks.nativeRuntime(temporary.newFolder()) }
        assertTrue(failure.message!!.contains("覆盖安装 APK"))
    }

    @Test fun storageRequiresExtraSpaceOnlyForInstallation() {
        StartupChecks.storage(100L * 1024 * 1024, false)
        assertThrows(IllegalStateException::class.java) { StartupChecks.storage(100L * 1024 * 1024, true) }
        StartupChecks.storage(1536L * 1024 * 1024, true)
        assertThrows(IllegalStateException::class.java) { StartupChecks.storage(0, false) }
    }

    @Test fun progressCountsMixedReadsOnceAndNeverClaimsCompletionEarly() {
        val reported = mutableListOf<Int>()
        InstallProgressInput(ByteArrayInputStream(ByteArray(10)), 10) { reported += it }.use {
            assertEquals(0, it.read())
            assertEquals(4, it.read(ByteArray(4)))
            assertEquals(5, it.read(ByteArray(5), 0, 5))
            assertEquals(-1, it.read())
        }
        assertEquals(listOf(10, 50, 99), reported)
    }

    @Test fun unknownLengthDoesNotInventAPercentage() {
        val reported = mutableListOf<Int>()
        InstallProgressInput(ByteArrayInputStream(ByteArray(10)), 0) { reported += it }.use { it.readBytes() }
        assertEquals(listOf(0), reported)
    }

    @Test fun failureHintsOnlyUseThisStartAndNeverExposeLogCredentials() {
        val log = temporary.newFile()
        log.writeText("ModuleNotFoundError: old error\n")
        val offset = log.length()
        log.appendText("new normal start\n")
        assertNull(StartupChecks.pythonFailure(log, offset))
        log.appendText("Address already in use token=private-example\n")
        val hint = StartupChecks.pythonFailure(log, offset)!!
        assertTrue(hint.contains("端口"))
        assertFalse(hint.contains("private-example"))
    }

    @Test fun successfulReplacementLeavesUserDataAlone() {
        val parent = temporary.newFolder()
        val root = File(parent, "rootfs").apply { mkdir(); File(this, "old").writeText("old") }
        val staged = File(parent, "stage").apply { mkdir(); File(this, "new").writeText("new") }
        val data = File(parent, "mower-data").apply { mkdir(); File(this, "conf").writeText("user data") }
        StartupChecks.activateRuntime(staged, root, File(parent, "backup"))
        assertEquals("new", File(root, "new").readText())
        assertEquals("user data", File(data, "conf").readText())
        assertFalse(File(root, "old").exists())
    }

    @Test fun failedReplacementRestoresOldEnvironment() {
        val parent = temporary.newFolder()
        val root = File(parent, "rootfs").apply { mkdir(); File(this, "old").writeText("old") }
        assertThrows(IllegalStateException::class.java) {
            StartupChecks.activateRuntime(File(parent, "missing-stage"), root, File(parent, "backup"))
        }
        assertEquals("old", File(root, "old").readText())
    }
}
