package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MaaNavigationTest {
    @Test fun splitTaskLayoutsLoadNavigationAlongsideExistingHotfixes() {
        for (directory in listOf("resource/tasks", "cache/resource/tasks")) {
            val component = Files.createTempDirectory("maa-navigation-split").toFile()
            try {
                File(component, directory).mkdirs()
                val hotfix = File(component, "cache/resource/tasks/hotfix/ocr_replace.json")
                hotfix.parentFile!!.mkdirs(); hotfix.writeText("{\"hotfix\":{}}")
                val legacy = File(component, "cache/resource/tasks.json")
                legacy.writeText("legacy")
                val target = File(component, "cache/resource/tasks/tasks.json")
                assertTrue(MaaNavigation.load(component, JSONObject("{\"YW-8\":{}}"), false) { path ->
                    assertEquals(File(component, "cache").path, path)
                    assertTrue(JSONObject(target.readText()).has("YW-8"))
                    true
                })
                assertEquals("legacy", legacy.readText())
                assertEquals("{\"hotfix\":{}}", hotfix.readText())
                assertEquals(setOf("tasks.json", "hotfix"), target.parentFile!!.list()!!.toSet())
            } finally { component.deleteRecursively() }
        }
    }

    @Test fun malformedDefinitionsNeverReplaceThePreviousNavigation() {
        val component = Files.createTempDirectory("maa-navigation-invalid").toFile()
        try {
            val target = File(component, "cache/resource/tasks.json")
            target.parentFile!!.mkdirs(); target.writeText("old")
            for (invalid in listOf("null", "1", "[]", "\"text\"")) {
                try {
                    MaaNavigation.load(component, JSONObject("{\"YW-8\":$invalid}"), false) { fail("must not load"); true }
                    fail("must reject malformed task")
                } catch (_: IllegalArgumentException) { }
                assertEquals("old", target.readText())
            }
        } finally { component.deleteRecursively() }
    }

    @Test fun failedLoadRestoresExactPreviousBytesForBothLayouts() {
        for (split in listOf(false, true)) {
            val component = Files.createTempDirectory("maa-navigation-rollback").toFile()
            try {
                if (split) File(component, "resource/tasks").mkdirs()
                val target = File(component, if (split) "cache/resource/tasks/tasks.json" else "cache/resource/tasks.json")
                val previous = "{\n  \"OLD\": {}\n}\n".toByteArray()
                target.parentFile!!.mkdirs(); target.writeBytes(previous)
                try {
                    MaaNavigation.load(component, JSONObject("{\"YW-8\":{}}"), false) {
                        assertTrue(JSONObject(target.readText()).has("YW-8"))
                        false
                    }
                    fail("must reject")
                } catch (error: IllegalStateException) {
                    assertTrue(error.message!!.contains("活动导航加载失败"))
                }
                assertArrayEquals(previous, target.readBytes())
                assertEquals(listOf("tasks.json"), target.parentFile!!.list()!!.toList())
            } finally { component.deleteRecursively() }
        }
    }

    @Test fun loadExceptionRemovesNewNavigationAndPreservesTheOriginalError() {
        val component = Files.createTempDirectory("maa-navigation-exception").toFile()
        val failure = IllegalStateException("native load failed")
        try {
            try {
                MaaNavigation.load(component, JSONObject("{\"YW-8\":{}}"), false) { throw failure }
                fail("must reject")
            } catch (error: IllegalStateException) { assertSame(failure, error) }
            assertEquals(emptyList<String>(), File(component, "cache/resource").list()!!.toList())
        } finally { component.deleteRecursively() }
    }

    @Test fun oversizedPreviousNavigationIsPreservedWithoutLoading() {
        val component = Files.createTempDirectory("maa-navigation-size").toFile()
        try {
            val target = File(component, "cache/resource/tasks.json")
            target.parentFile!!.mkdirs()
            java.io.RandomAccessFile(target, "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
            try {
                MaaNavigation.load(component, JSONObject("{\"YW-8\":{}}"), false) { fail("must not load"); true }
                fail("must reject oversized cache")
            } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("超过大小限制")) }
            assertEquals(4L * 1024 * 1024 + 1, target.length())
            assertEquals(listOf("tasks.json"), target.parentFile!!.list()!!.toList())
        } finally { component.deleteRecursively() }
    }

    @Test fun installsBeforeLoadingTheActiveComponentCache() {
        val component = Files.createTempDirectory("maa-navigation").toFile()
        try {
            val previous = File(component, "cache/resource/tasks.json")
            previous.parentFile!!.mkdirs(); previous.writeText("old")
            var loaded = false
            assertTrue(MaaNavigation.load(component, JSONObject("{\"YW-8\":{\"algorithm\":\"JustReturn\"}}"), false) { path ->
                assertEquals(File(component, "cache").path, path)
                assertTrue(JSONObject(previous.readText()).has("YW-8"))
                loaded = true
                true
            })
            assertTrue(loaded)
            assertEquals(listOf("tasks.json"), previous.parentFile!!.list()!!.toList())
        } finally { component.deleteRecursively() }
    }

    @Test fun runningCoreAndEmptyNavigationNeverTouchTheCache() {
        val component = Files.createTempDirectory("maa-navigation").toFile()
        try {
            val previous = File(component, "cache/resource/tasks.json")
            previous.parentFile!!.mkdirs(); previous.writeText("old")
            for ((tasks, running) in listOf(JSONObject("{\"YW-8\":{}}") to true, JSONObject() to false)) {
                try {
                    MaaNavigation.load(component, tasks, running) { fail("must not load"); true }
                    fail("must reject")
                } catch (_: IllegalStateException) {
                    assertTrue(running)
                } catch (_: IllegalArgumentException) {
                    assertEquals(0, tasks.length())
                }
                assertEquals("old", previous.readText())
            }
        } finally { component.deleteRecursively() }
    }

    @Test fun rejectedLoadDoesNotReportSuccess() {
        val component = Files.createTempDirectory("maa-navigation").toFile()
        try {
            try {
                MaaNavigation.load(component, JSONObject("{\"YW-8\":{}}"), false) { false }
                fail("must reject")
            } catch (error: IllegalStateException) {
                assertTrue(error.message!!.contains("活动导航加载失败"))
            }
        } finally { component.deleteRecursively() }
    }
}
