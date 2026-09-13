package com.aliothmoon.maameow.mower

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledComponentsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "bundled maa".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun asset(name: String) = ByteArrayInputStream(if (name.endsWith(".zip")) bytes else hash.toByteArray())

    @Test fun mowerRefreshesOncePerApkInstallationEvenWithTheSameVersionCode() {
        val root = temporary.newFolder()
        val first = BundledComponents(root, "26:100")
        assertTrue(first.refreshMower()); first.mowerActivated(); assertFalse(first.refreshMower())
        assertTrue(BundledComponents(root, "26:200").refreshMower())
    }

    @Test fun normalRestartPreservesHotUpdatesButNextApkReplacesThem() {
        val root = temporary.newFolder()
        val first = BundledComponents(root, "26:100")
        assertTrue(first.prepareMaa(::asset))
        val component = File(root, "mower-data/maa-component.zip")
        component.writeText("hot update")
        assertFalse(first.prepareMaa { error("must not read assets on normal restart") })
        assertEquals("hot update", component.readText())
        assertTrue(BundledComponents(root, "27:200").prepareMaa(::asset))
        assertArrayEquals(bytes, component.readBytes())
        assertEquals("27:200", File(root, "mower-data/maa-bundled-pending").readText())
    }

    @Test fun failedAssetValidationKeepsTheActiveCoreAndRetries() {
        val root = temporary.newFolder()
        BundledComponents(root, "old").prepareMaa(::asset)
        val next = BundledComponents(root, "new")
        assertThrows(IllegalStateException::class.java) {
            next.prepareMaa { ByteArrayInputStream(if (it.endsWith(".zip")) "bad".toByteArray() else hash.toByteArray()) }
        }
        assertArrayEquals(bytes, File(root, "mower-data/maa-component.zip").readBytes())
        assertEquals("old", File(root, "mower-data/bundled-maa-apk").readText())
        assertTrue(next.prepareMaa(::asset))
    }

    @Test fun interruptedSwitchWithoutGenerationCommitIsRepaired() {
        val root = temporary.newFolder()
        BundledComponents(root, "old").prepareMaa(::asset)
        File(root, "mower-data/maa-component.zip").writeText("partial replacement")
        assertTrue(BundledComponents(root, "new").prepareMaa(::asset))
        assertArrayEquals(bytes, File(root, "mower-data/maa-component.zip").readBytes())
    }

    @Test fun missingComponentIsRepairedWithoutTouchingConfiguration() {
        val root = temporary.newFolder()
        val bundle = BundledComponents(root, "current")
        bundle.prepareMaa(::asset)
        val conf = File(root, "mower-data/conf.json").apply { writeText("user plan") }
        File(root, "mower-data/maa-component.sha256").delete()
        assertTrue(bundle.prepareMaa(::asset))
        assertEquals("user plan", conf.readText())
    }
}
