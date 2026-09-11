package com.aliothmoon.maameow.mower

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/** The native shell uses the same authenticated API as its WebView. */
object NativeRuntimeClient {
    fun call(path: String, event: String? = null): String = request(path, event?.let {
        require(path == "/android/runtime-event" && it in setOf("game_exit", "backend_exit", "low_fps"))
        org.json.JSONObject().put("event", it)
    })
    fun idleAction(): String {
        val config = org.json.JSONObject(request("/conf"))
        return when { config.optBoolean("return_home_when_idle") -> "home"; config.optBoolean("exit_game_when_idle") -> "exit"; else -> "idle" }
    }
    fun saveIdleAction(action: String) {
        require(action in setOf("idle", "home", "exit"))
        request("/conf", org.json.JSONObject().put("return_home_when_idle", action == "home")
            .put("exit_game_when_idle", action == "exit").put("close_simulator_when_idle", false))
    }
    fun saveScreenshotHours(hours: Double) {
        require(hours.isFinite() && hours >= 0)
        request("/conf", org.json.JSONObject().put("screenshot", hours))
    }
    fun saveTheme(theme: String) {
        require(theme in setOf("light", "dark"))
        request("/conf", org.json.JSONObject().put("theme", theme))
    }
    private fun request(path: String, payload: org.json.JSONObject? = null): String {
        require(path in setOf("/status", "/stop", "/log", "/android/runtime-event", "/conf"))
        val uri = Uri.parse(MowerService.url ?: error("请先启动 Mower 服务"))
        val connection = URL("http://127.0.0.1:${uri.port}$path").openConnection() as HttpURLConnection
        connection.setRequestProperty("token", uri.getQueryParameter("token"))
        connection.connectTimeout = 1000; connection.readTimeout = 20000
        try {
            if (payload != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            }
            check(connection.responseCode == 200) { "Mower 暂时未响应" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    fun stopTasks() { check(call("/stop").trim() == "true") { "任务尚未停止，请稍后重试" } }
}
