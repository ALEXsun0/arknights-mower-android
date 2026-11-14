package com.aliothmoon.maameow.mower

import android.app.Application
import android.os.Binder
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.manager.RemoteServiceManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Proxy
import java.net.Socket
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VdmSettingsAndBridgeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test @Config(sdk = [33]) fun unsupportedSettingIsRejectedWithoutChangingSavedChoices() {
        val prefs = context.getSharedPreferences("android-system", 0)
        prefs.edit().clear().putBoolean("mute_game", true).commit()
        val settings = AndroidSystemSettings(context)
        assertFalse(settings.enabled("use_vdm"))
        val request = settings.snapshot()
        request.getJSONObject("settings").put("use_vdm", true)
        assertTrue(runCatching { settings.save(request) }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(settings.enabled("use_vdm"))
        assertTrue(settings.enabled("mute_game"))
        assertEquals(0, settings.snapshot().getInt("revision"))
    }

    @Test fun nativeSettingSelectsVdmOnlyAfterBridgeAuthentication() {
        val settings = AndroidSystemSettings(context)
        val prefs = context.getSharedPreferences("android-system", 0)
        prefs.edit().clear().commit()
        val request = settings.snapshot()
        request.getJSONObject("settings").put("use_vdm", true)
        settings.save(request)
        assertTrue(AndroidSystemSettings(context).enabled("use_vdm"))
        verifyAuthenticatedPrepare(3)
        settings.save(settings.snapshot().apply { getJSONObject("settings").put("use_vdm", false) })
        verifyAuthenticatedPrepare(2)
    }

    @Test @Config(sdk = [33]) fun oldAndroidUsesLegacyAndRejectsUnsupportedSavedVdmBeforeDeviceCalls() {
        val prefs = context.getSharedPreferences("android-system", 0)
        prefs.edit().clear().commit()
        verifyAuthenticatedPrepare(2)
        prefs.edit().putBoolean("use_vdm", true).commit()
        verifyAuthenticatedPrepare(null)
    }

    private fun verifyAuthenticatedPrepare(expectedMode: Int?) {
        val modes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val component = java.io.File(context.filesDir, "mower-data/maa-component.sha256")
        component.parentFile!!.mkdirs(); component.writeText("a".repeat(64))
        val binder = Binder()
        val remote = Proxy.newProxyInstance(RemoteService::class.java.classLoader,
            arrayOf(RemoteService::class.java)) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "maaRpc" -> "{\"ok\":true,\"result\":{\"component_hash\":\"\"}}"
                "setVirtualDisplayMode" -> { modes += args!![0] as Int; false }
                "stopVirtualDisplay" -> null
                else -> error("Unexpected backend operation: ${method.name}")
            }
        } as RemoteService
        val service = RemoteServiceManager::class.java.getDeclaredField("service").apply { isAccessible = true }
        val previous = service.get(RemoteServiceManager)
        service.set(RemoteServiceManager, remote)
        val bridge = MowerBridge(context, "current-session-token")
        bridge.start()
        fun call(token: String?): JSONObject = Socket("127.0.0.1", bridge.port).use { socket ->
            socket.soTimeout = 5000
            val request = JSONObject().put("method", "prepare").put("params", JSONObject())
            if (token != null) request.put("token", token)
            val bytes = request.toString().toByteArray()
            DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
            val input = DataInputStream(socket.getInputStream())
            val result = ByteArray(input.readInt()).also(input::readFully)
            JSONObject(String(result))
        }
        try {
            assertFalse(call(null).getBoolean("ok"))
            assertFalse(call("wrong-token").getBoolean("ok"))
            assertTrue(modes.isEmpty())
            assertFalse(call("current-session-token").getBoolean("ok"))
            assertEquals(expectedMode?.let { listOf(it) } ?: emptyList<Int>(), modes)
        } finally {
            service.set(RemoteServiceManager, null)
            bridge.close()
            service.set(RemoteServiceManager, previous)
        }
    }
}
