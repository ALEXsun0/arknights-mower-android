package com.aliothmoon.maameow.mower

import android.app.Application
import android.os.PowerManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MowerRuntimeMonitorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val power get() = shadowOf(context.getSystemService(PowerManager::class.java))
    private lateinit var bridge: MowerBridge
    private lateinit var monitor: MowerRuntimeMonitor
    private var state = "stopped"
    private var sleeps = 0

    @Before fun prepare() {
        context.getSharedPreferences("android-system", 0).edit().clear()
            .putBoolean("sleep_when_idle", true).putBoolean("preserve_screen_on", true)
            .putBoolean("disconnect_stop", false).apply()
        bridge = MowerBridge(context, "test-token")
        monitor = MowerRuntimeMonitor(context,
            readStatus = { JSONObject().put("status", state) },
            sleepPhone = { sleeps++; 0 })
        MowerService.engine = bridge
        MowerService.monitor = monitor
        MowerService.url = "http://127.0.0.1:58000/?token=test-token"
        MowerService.manual = false
        MowerService.unlocking = false
    }

    @After fun cleanUp() {
        bridge.close()
        MowerService.engine = null
        MowerService.monitor = null
        MowerService.url = null
        MowerService.manual = false
        MowerService.unlocking = false
    }

    private fun dispatch(method: String, params: JSONObject = JSONObject()) =
        MowerBridge::class.java.getDeclaredMethod("dispatch", String::class.java, JSONObject::class.java)
            .apply { isAccessible = true }.invoke(bridge, method, params)

    private fun tick(status: String) { state = status; monitor.tick() }

    @Test fun initiallyLitPhoneIsPreservedEvenIfFirstWorkingPollIsAfterScreenTimeout() {
        power.setIsInteractive(true)
        assertEquals(true, dispatch("task_screen_begin"))
        power.setIsInteractive(false)
        tick("stopped")
        tick("working")
        tick("sleeping")
        assertEquals(0, sleeps)
    }

    @Test fun initiallyOffPhoneReturnsToSleepAfterAutomaticWake() {
        power.setIsInteractive(false)
        dispatch("task_screen_begin")
        power.setIsInteractive(true)
        tick("working")
        tick("sleeping")
        tick("sleeping")
        assertEquals(1, sleeps)
    }

    @Test fun taskDemandCapturesNextCycleEvenWhenAutomaticWakeIsDisabled() {
        for ((index, method) in listOf("screenshot", "launch", "maa_start").withIndex()) {
            power.setIsInteractive(false)
            dispatch("task_screen_begin")
            tick("working")
            tick("sleeping")
            assertEquals(index + 1, sleeps)
            power.setIsInteractive(true)
            // Stop at the external engine boundary; screen capture runs before it.
            val failure = runCatching { dispatch(method, JSONObject().put("require_game", true)) }.exceptionOrNull()
            assertTrue(failure is java.lang.reflect.InvocationTargetException)
            power.setIsInteractive(false)
            tick("working")
            tick("sleeping")
            assertEquals(index + 1, sleeps)
        }
    }

    @Test fun previewLaunchDoesNotSeedTheNextTaskSnapshot() {
        power.setIsInteractive(true)
        runCatching { dispatch("launch", JSONObject().put("task", false)) }
        power.setIsInteractive(false)
        tick("working")
        tick("sleeping")
        assertEquals(1, sleeps)
    }

    @Test fun stoppedRunCannotTriggerCompletionAndNextRunUsesNewSnapshot() {
        power.setIsInteractive(false)
        dispatch("task_screen_begin")
        tick("working")
        dispatch("task_screen_end")
        tick("sleeping")
        assertEquals(0, sleeps)
        power.setIsInteractive(true)
        dispatch("task_screen_begin")
        power.setIsInteractive(false)
        tick("working")
        tick("sleeping")
        assertEquals(0, sleeps)
    }

    @Test fun disabledSleepSettingSuppressesLocking() {
        context.getSharedPreferences("android-system", 0).edit().putBoolean("sleep_when_idle", false).apply()
        power.setIsInteractive(false)
        dispatch("task_screen_begin")
        tick("working")
        tick("sleeping")
        assertEquals(0, sleeps)
    }
}
