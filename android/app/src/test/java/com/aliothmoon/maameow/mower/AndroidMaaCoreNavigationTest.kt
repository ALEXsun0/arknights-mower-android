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
    @Test fun rpcLoadsTheInstalledNavigationBeforeSubmittingAStage() {
        val component = Files.createTempDirectory("maa-rpc-navigation").toFile()
        val events = mutableListOf<String>()
        val native = Proxy.newProxyInstance(MaaCoreLibrary::class.java.classLoader, arrayOf(MaaCoreLibrary::class.java)) { _, method, args ->
            when (method.name) {
                "AsstRunning" -> false
                "AsstLoadResource" -> {
                    assertEquals(File(component, "cache").path, args!![0])
                    assertTrue(JSONObject(File(component, "cache/resource/tasks.json").readText()).has("YW-8"))
                    events.add("load")
                    true
                }
                "AsstAppendTask" -> { events.add("append"); 4 }
                else -> error("unexpected native call ${method.name}")
            }
        } as MaaCoreLibrary
        val host = AndroidMaaCore()
        for ((name, value) in listOf("installed" to component, "api" to native, "instance" to Pointer(1))) {
            AndroidMaaCore::class.java.getDeclaredField(name).apply { isAccessible = true }.set(host, value)
        }
        try {
            assertEquals(true, host.call(JSONObject().put("method", "maa_navigation")
                .put("params", JSONObject().put("tasks", JSONObject("{\"YW-8\":{}}"))), 0))
            assertEquals(4, host.call(JSONObject().put("method", "maa_append")
                .put("params", JSONObject().put("type", "Fight").put("params", JSONObject().put("stage", "YW-8"))), 0))
            assertEquals(listOf("load", "append"), events)
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
