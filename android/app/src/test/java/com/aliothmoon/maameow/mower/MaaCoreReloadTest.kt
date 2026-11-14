package com.aliothmoon.maameow.mower

import android.app.Application
import android.os.Binder
import android.os.ParcelFileDescriptor
import com.aliothmoon.maameow.RemoteService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.net.Socket
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MaaCoreReloadTest {
    private val oldHash = "a".repeat(64)
    private val newHash = "b".repeat(64)
    private val events = mutableListOf<String>()
    private lateinit var bridge: MowerBridge
    private lateinit var current: Backend
    private lateinit var hashFile: File
    private var retireFailure = false
    private var connectFailure = false
    private var shutdownDuringRetire = false
    private var serviceRestarts = 0
    private var backendCalls = 0

    private inner class Backend(val generation: Int) {
        val binder = Binder()
        var loaded = ""
        var running = false
        var gameAlive = false
        var installed = ""
        val remote = Proxy.newProxyInstance(RemoteService::class.java.classLoader,
            arrayOf(RemoteService::class.java)) { _, method, args ->
            backendCalls++
            when (method.name) {
                "asBinder" -> binder
                "maaRpc" -> {
                    val request = JSONObject(args!![0] as String)
                    val result: Any = when (request.getString("method")) {
                        "maa_status" -> JSONObject().put("component_hash", loaded).put("running", running)
                            .put("version", if (loaded.isEmpty()) "unloaded" else "v6.$generation")
                        "maa_prepare" -> { events += "load:$generation"; loaded = installed; "v6.$generation" }
                        "maa_reset" -> 0
                        else -> error("Unexpected MAA call: $request")
                    }
                    JSONObject().put("ok", true).put("result", result).toString()
                }
                "installCore" -> { (args!![0] as ParcelFileDescriptor).close(); installed = args[1] as String; true }
                "isAppAlive" -> if (gameAlive) 1 else 0
                "setVirtualDisplayMode" -> true
                "setVirtualDisplayResolution", "stopVirtualDisplay" -> null
                "startVirtualDisplay" -> { events += "display:$generation"; generation + 10 }
                "mowerGame" -> { events += "game:$generation"; gameAlive = true; true }
                else -> error("Unexpected backend call: ${method.name}")
            }
        } as RemoteService
    }

    @Before fun setup() {
        MowerService.stopping = false; MowerService.manual = false
        MowerService.previewing = false; MowerService.unlocking = false
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("android-system", 0).edit().clear().commit()
        context.getSharedPreferences("game-audio-recovery", 0).edit().clear().commit()
        hashFile = File(context.filesDir, "mower-data/maa-component.sha256")
        hashFile.parentFile!!.mkdirs(); hashFile.writeText(oldHash)
        File(hashFile.parentFile, "maa-component.zip").writeText("package fixture")
        current = Backend(1)
        bridge = MowerBridge(context, "session-token", updateRestart = { serviceRestarts++ },
            currentService = { current.remote }, connectService = {
                check(!connectFailure) { "connection failed" }; current.remote
            }, retireService = {
                assertSame(current.remote, it)
                events += "retire:${current.generation}"
                check(!retireFailure) { "old process still alive" }
                if (shutdownDuringRetire) MowerService.stopping = true
                current = Backend(current.generation + 1)
            })
        bridge.start()
    }

    @After fun cleanup() {
        bridge.close(); MowerService.stopping = false; MowerService.manual = false
        MowerService.previewing = false; MowerService.unlocking = false
    }

    private fun call(method: String, hash: String = newHash, token: String? = "session-token"): JSONObject =
        Socket("127.0.0.1", bridge.port).use { socket ->
            socket.soTimeout = 5000
            val request = JSONObject().put("method", method).put("params", JSONObject().put("component_hash", hash))
            if (token != null) request.put("token", token)
            val bytes = request.toString().toByteArray()
            DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
            val input = DataInputStream(socket.getInputStream())
            JSONObject(String(ByteArray(input.readInt()).also(input::readFully)))
        }

    private fun prepareOld() {
        val response = call("prepare")
        assertTrue(response.toString(), response.getBoolean("ok"))
        events.clear(); hashFile.writeText(newHash)
    }

    @Test fun updateReplacesNativeProcessAndReconnectsLiveGameWithoutRestartingService() {
        prepareOld(); current.gameAlive = true
        val port = bridge.port
        val result = call("maa_reload")
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertTrue(result.getJSONObject("result").getBoolean("activated"))
        assertEquals(listOf("retire:1", "display:2", "load:2", "game:2"), events)
        assertEquals(newHash, current.loaded)
        assertEquals(port, bridge.port); assertEquals(0, serviceRestarts)
        assertTrue(call("status").getJSONObject("result").getBoolean("prepared"))
        events.clear(); assertTrue(call("maa_reload").getBoolean("ok")); assertTrue(events.isEmpty())
    }

    @Test fun nextPrepareActivatesChangedCoreWithoutLaunchingAnIdleGame() {
        prepareOld()
        assertTrue(call("prepare").getBoolean("ok"))
        assertEquals(listOf("retire:1", "display:2", "load:2"), events)
        assertEquals(newHash, current.loaded)
        assertEquals(0, serviceRestarts)
    }

    @Test fun unusedCoreWaitsForNextConnection() {
        hashFile.writeText(newHash)
        val result = call("maa_reload")
        assertTrue(result.getBoolean("ok"))
        assertFalse(result.getJSONObject("result").getBoolean("activated"))
        assertTrue(events.isEmpty())
        assertTrue(call("prepare").getBoolean("ok")); assertEquals(newHash, current.loaded)
    }

    @Test fun unauthenticatedOrStaleRequestsCannotTouchBackend() {
        hashFile.writeText(newHash)
        assertFalse(call("maa_reload", token = null).getBoolean("ok"))
        assertFalse(call("maa_reload", token = "invalid").getBoolean("ok"))
        assertFalse(call("maa_reload", hash = oldHash).getBoolean("ok"))
        assertFalse(call("maa_reload", hash = "../bad").getBoolean("ok"))
        assertTrue(events.isEmpty()); assertEquals(1, current.generation)
        assertEquals(0, backendCalls)
    }

    @Test fun activeTaskAndManualUsePreventReplacement() {
        prepareOld()
        current.running = true
        assertFalse(call("maa_reload").getJSONObject("result").getBoolean("activated"))
        assertFalse(call("prepare").getBoolean("ok"))
        current.running = false
        for (flag in listOf("manual", "preview", "unlock", "stop")) {
            MowerService.manual = flag == "manual"; MowerService.previewing = flag == "preview"
            MowerService.unlocking = flag == "unlock"; MowerService.stopping = flag == "stop"
            assertFalse(call("maa_reload").getJSONObject("result").getBoolean("activated"))
        }
        assertTrue(events.isEmpty()); assertEquals(oldHash, current.loaded)
        MowerService.stopping = false
        assertTrue(call("prepare").getBoolean("ok"))
        assertEquals(newHash, current.loaded)
    }

    @Test fun failedExitNeverStartsReplacementAndKeepsBridgeOnline() {
        prepareOld(); retireFailure = true
        assertFalse(call("maa_reload").getBoolean("ok"))
        assertEquals(listOf("retire:1"), events)
        assertEquals(oldHash, current.loaded)
        assertFalse(call("status").getJSONObject("result").getBoolean("prepared"))
        assertEquals(0, serviceRestarts)
    }

    @Test fun failedReconnectReportsFailureAndExplicitPrepareCanRecover() {
        prepareOld(); connectFailure = true
        assertFalse(call("maa_reload").getBoolean("ok"))
        assertEquals(listOf("retire:1"), events)
        assertFalse(call("status").getJSONObject("result").getBoolean("prepared"))
        connectFailure = false
        assertTrue(call("prepare").getBoolean("ok")); assertEquals(newHash, current.loaded)
        assertEquals(0, serviceRestarts)
    }

    @Test fun stoppingDuringExitCancelsReplacement() {
        prepareOld(); shutdownDuringRetire = true
        assertFalse(call("maa_reload").getBoolean("ok"))
        assertEquals(listOf("retire:1"), events)
        assertEquals(0, serviceRestarts)
    }
}
