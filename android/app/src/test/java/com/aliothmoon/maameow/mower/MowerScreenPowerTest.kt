package com.aliothmoon.maameow.mower

import android.app.Application
import android.os.Binder
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.manager.RemoteServiceManager
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MowerScreenPowerTest {
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun awaitPower() {
        val worker = MowerScreenSaver::class.java.getDeclaredField("power").apply { isAccessible = true }
            .get(MowerScreenSaver) as ExecutorService
        worker.submit {}.get(2, TimeUnit.SECONDS)
    }

    @Test fun homeButtonKeepsScreenOffThroughMonitorChecksAndResumeRestoresIt() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("android-system", 0).edit().clear()
            .putBoolean("sleep_when_idle", false).putBoolean("disconnect_stop", false).apply()
        val powers = mutableListOf<Boolean>()
        val binder = Binder()
        val remote = Proxy.newProxyInstance(RemoteService::class.java.classLoader,
            arrayOf(RemoteService::class.java)) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "systemRpc" -> {
                    val request = JSONObject(args!![0] as String)
                    val result: Any = when (request.getString("action")) {
                        "screen_power" -> { powers += request.getBoolean("on"); true }
                        "game_status" -> JSONObject().put("alive", false)
                        else -> error("Unexpected system action: ${request.getString("action")}")
                    }
                    JSONObject().put("ok", true).put("result", result).toString()
                }
                else -> error("Unexpected backend call: ${method.name}")
            }
        } as RemoteService
        val service = RemoteServiceManager::class.java.getDeclaredField("service").apply { isAccessible = true }
        val previousService = service.get(RemoteServiceManager)
        service.set(RemoteServiceManager, remote)
        MowerService.active = false
        MowerService.stopping = false
        MowerService.url = null
        val home = Robolectric.buildActivity(MowerActivity::class.java).create()
        val bridge = MowerBridge(context, "test-token")
        try {
            val button = buttons(home.get().window.decorView).single { it.text == "一键息屏" }
            assertFalse(button.isEnabled)
            MowerService.active = true
            MowerService.url = "http://127.0.0.1:58000/"
            MowerService.engine = bridge
            val refresh = MowerActivity::class.java.getDeclaredMethod("refreshUi").apply { isAccessible = true }
            refresh.invoke(home.get())
            assertTrue(button.isEnabled)
            button.performClick()
            awaitPower()
            assertEquals(listOf(false), powers)

            var state = "working"
            val monitor = MowerRuntimeMonitor(context, readStatus = { JSONObject().put("status", state) })
            repeat(3) { monitor.tick() }
            state = "stopped"
            monitor.tick()
            awaitPower()
            assertEquals(listOf(false), powers)

            MowerVisibility.onActivityResumed(home.get())
            awaitPower()
            assertEquals(listOf(false, true), powers)
            MowerService.stopping = true
            refresh.invoke(home.get())
            assertFalse(button.isEnabled)
        } finally {
            MowerScreenSaver.hide()
            awaitPower()
            bridge.close()
            home.destroy()
            service.set(RemoteServiceManager, previousService)
            MowerService.active = false
            MowerService.stopping = false
            MowerService.url = null
            MowerService.engine = null
            MowerService.manual = false
            MowerService.unlocking = false
            MowerVisibility.onActivityPaused(home.get())
        }
    }
}
