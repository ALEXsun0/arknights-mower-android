package com.aliothmoon.maameow.mower

import com.aliothmoon.maameow.maa.MaaCoreLibrary
import com.sun.jna.Pointer
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidMaaCoreNavigationTest {
    @Test fun rpcReloadsBothLayoutsBeforeStartingTasksInTheSameHost() {
        for (split in listOf(false, true)) checkNavigationCycle(split)
    }

    private fun checkNavigationCycle(split: Boolean) {
        val component = Files.createTempDirectory("maa-rpc-navigation").toFile()
        if (split) File(component, "resource/tasks").mkdirs()
        val target = File(component, if (split) "cache/resource/tasks/tasks.json" else "cache/resource/tasks.json")
        val events = mutableListOf<String>()
        var running = false
        var stage = "YW-8"
        val native = Proxy.newProxyInstance(MaaCoreLibrary::class.java.classLoader, arrayOf(MaaCoreLibrary::class.java)) { _, method, args ->
            when (method.name) {
                "AsstRunning" -> running
                "AsstGetVersion" -> "test-core"
                "AsstLoadResource" -> {
                    assertFalse(running)
                    assertEquals(File(component, "cache").path, args!![0])
                    assertTrue(JSONObject(target.readText()).has(stage))
                    events.add("load")
                    true
                }
                "AsstAppendTask" -> {
                    assertEquals(stage, JSONObject(args!![2] as String).getString("stage"))
                    events.add("append"); 4
                }
                "AsstStart" -> { events.add("start"); running = true; true }
                "AsstStop" -> { events.add("stop"); running = false; true }
                else -> error("unexpected native call ${method.name}")
            }
        } as MaaCoreLibrary
        val host = AndroidMaaCore()
        val hash = "a".repeat(64)
        for ((name, value) in listOf("installed" to component, "loaded" to hash, "api" to native, "instance" to Pointer(1))) {
            AndroidMaaCore::class.java.getDeclaredField(name).apply { isAccessible = true }.set(host, value)
        }
        try {
            for (next in listOf("YW-8", "YW-9")) {
                stage = next
                val navigation = JSONObject().put("method", "maa_navigation")
                    .put("params", JSONObject().put("tasks", JSONObject().put(stage, JSONObject())))
                assertEquals(true, host.call(navigation, 0))
                assertEquals(4, host.call(JSONObject().put("method", "maa_append")
                    .put("params", JSONObject().put("type", "Fight").put("params", JSONObject().put("stage", stage))), 0))
                assertEquals(true, host.call(JSONObject().put("method", "maa_start"), 0))
                val previous = target.readBytes()
                try {
                    host.call(JSONObject().put("method", "maa_navigation")
                        .put("params", JSONObject().put("tasks", JSONObject("{\"BLOCKED\":{}}"))), 0)
                    fail("must reject navigation while running")
                } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("MAA 正在运行")) }
                assertArrayEquals(previous, target.readBytes())
                assertEquals(true, host.call(JSONObject().put("method", "maa_stop"), 0))
                val status = host.call(JSONObject().put("method", "maa_status"), 0) as JSONObject
                assertEquals(hash, status.getString("component_hash"))
            }
            assertEquals(listOf("load", "append", "start", "stop", "load", "append", "start", "stop"), events)
        } finally { component.deleteRecursively() }
    }

    @Test fun rejectedSubmissionIdentifiesStageWithoutReportingCredentials() {
        val native = Proxy.newProxyInstance(MaaCoreLibrary::class.java.classLoader, arrayOf(MaaCoreLibrary::class.java)) { _, method, _ ->
            when (method.name) {
                "AsstRunning" -> false
                "AsstAppendTask" -> 0
                else -> error("unexpected native call ${method.name}")
            }
        } as MaaCoreLibrary
        val host = AndroidMaaCore()
        for ((name, value) in listOf("api" to native, "instance" to Pointer(1))) {
            AndroidMaaCore::class.java.getDeclaredField(name).apply { isAccessible = true }.set(host, value)
        }
        try {
            host.call(JSONObject().put("method", "maa_append").put("params", JSONObject().put("type", "Fight")
                .put("params", JSONObject().put("stage", "YW-8").put("penguin_id", "private-id"))), 0)
            fail("must reject")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("YW-8"))
            assertFalse(error.message!!.contains("private-id"))
        }
    }
}
