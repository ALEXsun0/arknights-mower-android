package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CoreBackupCleanupTest {
    @Test fun removesOnlyObsoleteComponentVersionsAndPreservesUserData() {
        val root = Files.createTempDirectory("mower-core-cleanup").toFile()
        try {
            val active = "a".repeat(64)
            val old = "b".repeat(64)
            for (name in listOf(active, old, "user", "jna", "other")) {
                File(root, name).mkdirs(); File(root, "$name/data").writeText("keep")
            }
            File(root, "$old.zip").writeText("old archive")
            File(root, "$old.install").mkdirs()
            assertTrue(CoreBackupCleanup.retire(root, active))
            assertFalse(File(root, old).exists())
            assertFalse(File(root, "$old.zip").exists())
            assertFalse(File(root, "$old.install").exists())
            for (name in listOf(active, "user", "jna", "other")) assertEquals("keep", File(root, "$name/data").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun neverFollowsLinksWhileRemovingOldVersions() {
        val root = Files.createTempDirectory("mower-core-links").toFile()
        val outside = Files.createTempDirectory("mower-user-data").toFile()
        try {
            File(outside, "keep").writeText("keep")
            Files.createSymbolicLink(File(root, "b".repeat(64)).toPath(), outside.toPath())
            assertTrue(CoreBackupCleanup.retire(root, "a".repeat(64)))
            assertEquals("keep", File(outside, "keep").readText())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
}
