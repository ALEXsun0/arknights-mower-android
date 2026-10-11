package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MaaNavigationTest {
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
