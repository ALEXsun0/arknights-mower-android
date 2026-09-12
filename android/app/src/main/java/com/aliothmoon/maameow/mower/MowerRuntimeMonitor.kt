package com.aliothmoon.maameow.mower

import android.content.Context
import android.os.PowerManager
import com.aliothmoon.maameow.domain.service.GameFpsAdvisor
import com.aliothmoon.maameow.manager.RemoteServiceManager
import org.json.JSONObject

/** Observes Mower's own working/sleeping state; it never creates a second task queue. */
class MowerRuntimeMonitor(private val context: Context) {
    private val settings = AndroidSystemSettings(context)
    private val cycles = TaskCyclePolicy()
    private val advisor = GameFpsAdvisor(windowSize = 3, maxIdleStreak = 1) // Samples every five seconds.
    private val preWakeScreenOn = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
    private var missingSamples = 0
    private var nextWake = ""
    private var lastNotification = ""
    @Volatile var gameFps = -1f; private set
    private fun externalAlert(event: String) {
        if (settings.enabled("external_device_alerts")) runCatching { NativeRuntimeClient.call("/android/runtime-event", event) }
            .onFailure { AndroidSystemSettings.lastAction = "设备提醒发送失败，详情请查看运行日志" }
    }
    fun beforeWake() {
        preWakeScreenOn.compareAndSet(null, context.getSystemService(PowerManager::class.java).isInteractive)
    }
    fun tick() {
        if (MowerService.url == null) return
        val state = JSONObject(NativeRuntimeClient.call("/status"))
        val name = state.optString("status", "stopped")
        val manual = MowerService.manual || MowerService.unlocking
        val screenOn = preWakeScreenOn.get() ?: context.getSystemService(PowerManager::class.java).isInteractive
        val cycle = cycles.update(name, screenOn, manual, settings.enabled("preserve_screen_on"))
        if (cycle.started) { advisor.reset(); MowerScreenSaver.resetCycle(); missingSamples = 0 }
        val engine = MowerService.engine ?: return
        val game = runCatching { engine.gameState() }.getOrNull()
        gameFps = game?.optDouble("fps", -1.0)?.toFloat() ?: -1f
        if (name == "working" && !manual) {
            val expected = game?.optBoolean("expected_running") == true
            val missing = expected && (game?.optBoolean("alive") != true)
            missingSamples = if (missing) missingSamples + 1 else 0
            val disconnected = RemoteServiceManager.getInstanceOrNull() == null
            if (missingSamples >= 2 && !disconnected) {
                // A solver may intentionally close the game after our first sample.
                val latest = engine.gameState()
                if (!latest.optBoolean("expected_running") || latest.optBoolean("alive")) missingSamples = 0
            }
            if (missingSamples >= 2 && !disconnected && settings.enabled("keep_game_alive")) {
                runCatching { engine.recoverTaskGame() }.onSuccess { recovered ->
                    if (recovered) missingSamples = 0
                }.onFailure {
                    AndroidSystemSettings.lastAction = it.message ?: "后台游戏自动恢复失败"
                }
            }
            if (settings.enabled("disconnect_stop") && (missingSamples >= 2 || disconnected)) {
                if (JSONObject(NativeRuntimeClient.call("/status")).optString("status") != "working") {
                    missingSamples = 0
                    return
                }
                NativeRuntimeClient.stopTasks()
                runCatching { RemoteServiceManager.getInstanceOrNull()?.maaRpc("{\"method\":\"maa_stop\"}") }
                MowerScreenSaver.hide()
                val message = if (disconnected) "Shizuku 连接中断，已停止任务，请恢复连接后手动启动。" else "游戏进程退出且未能恢复，已停止任务，请检查游戏后手动启动。"
                AndroidSystemSettings.lastAction = message
                MowerNotifications.event(context, message, true)
                externalAlert(if (disconnected) "backend_exit" else "game_exit")
                missingSamples = 0
                return
            }
            if (settings.enabled("low_fps_alert")) advisor.onSample(gameFps)?.let {
                val message = "后台游戏持续低帧率：中位数 ${it.medianFps} FPS（每 5 秒采样，本轮提醒）"
                android.util.Log.w("Mower", message)
                runCatching {
                    java.io.File(context.filesDir, "python.log").appendText("\n${java.time.Instant.now()} $message\n")
                }
                externalAlert("low_fps")
                MowerNotifications.event(context, "后台游戏持续低帧率（约 ${it.medianFps.toInt()} FPS），请检查省电设置、设备温度或尝试 720p。", false)
            }
            if (settings.enabled("screen_saver") && !MowerVisibility.visible && !MowerService.previewing) {
                runCatching { MowerScreenSaver.show(context, settings.enabled("hardware_screen_off")) }
                    .onFailure { AndroidSystemSettings.lastAction = it.message ?: "屏保未启动" }
            } else MowerScreenSaver.hide()
        } else MowerScreenSaver.hide()
        if (cycle.completed) {
            if (settings.enabled("sleep_when_idle") && cycle.maySleep) {
                val code = engine.sleepPhone()
                AndroidSystemSettings.lastAction = if (code == 0) "本轮任务完成，手机已锁屏休眠" else UnlockSettings.resultText(code)
            }
            preWakeScreenOn.set(null)
        }
        if (name == "stopped") preWakeScreenOn.set(null)
        val next = state.optString("next_task_time", "")
        if (name == "sleeping" && next != nextWake) {
            MowerWakeReceiver.schedule(context, state.optLong("remaining_seconds", 0)); nextWake = next
        } else if (name != "sleeping" && nextWake.isNotEmpty()) { MowerWakeReceiver.cancel(context); nextWake = "" }
        val text = when (name) { "working" -> "Mower 正在工作"; "sleeping" -> "等待下次任务 ${next.takeLast(8)}"; else -> "Mower 已就绪，调度未启动" } +
            if (gameFps >= 0) " · ${gameFps.toInt()} FPS" else ""
        if (text != lastNotification) { MowerNotifications.update(context, text); lastNotification = text }
    }
}
