package com.aliothmoon.maameow.mower

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import org.json.JSONObject

/** Android settings live outside imported desktop Mower configuration. */
class AndroidSystemSettings(private val context: Context) {
    companion object {
        val defaults = linkedMapOf("mute_game" to false, "preview_sound" to true,
            "force_fullscreen" to false, "recover_game" to true, "wake_on_launch" to false,
            "dismiss_keyguard" to false, "keep_cpu_awake" to true, "keep_screen_on" to false,
            "auto_pip" to false, "show_touch" to false, "resolution_720p" to false,
            "external_device_alerts" to false, "fps_monitor" to true, "low_fps_alert" to true, "disconnect_stop" to true,
            "sleep_when_idle" to false, "preserve_screen_on" to true,
            "screen_saver" to false, "hardware_screen_off" to false, "restart_on_boot" to false, "root_backend" to false)
        @Volatile var lastAction = "尚未执行系统操作"
        @Volatile var foreground: MowerActivity? = null
    }
    private val prefs = context.getSharedPreferences("android-system", 0)
    fun enabled(key: String) = prefs.getBoolean(key, defaults.getValue(key))
    fun values() = JSONObject().also { out -> defaults.forEach { (key, _) -> out.put(key, enabled(key)) } }
    fun snapshot() = JSONObject().put("settings", values()).put("revision", prefs.getInt("revision", 0))
    fun save(request: JSONObject) {
        require(request.keys().asSequence().toSet() == setOf("settings", "revision")) { "设置请求格式错误" }
        check(request.getInt("revision") == prefs.getInt("revision", 0)) { "设置已由其他页面修改，请刷新后重试" }
        val values = request.getJSONObject("settings")
        require(values.keys().asSequence().toSet() == defaults.keys) { "设置项不完整或不受支持" }
        defaults.keys.forEach { require(values.get(it) is Boolean) { "设置项必须为布尔值" } }
        require(!values.getBoolean("dismiss_keyguard") || values.getBoolean("wake_on_launch")) { "自动解锁需要开启自动唤醒" }
        val edit = prefs.edit()
        defaults.keys.forEach { edit.putBoolean(it, values.getBoolean(it)) }
        check(edit.putInt("revision", prefs.getInt("revision", 0) + 1).commit()) { "设置保存失败" }
    }
    fun deviceState(): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val lock = context.getSystemService(KeyguardManager::class.java)
        return JSONObject().put("screen_on", power.isInteractive).put("locked", lock.isKeyguardLocked)
            .put("secure_lock", lock.isKeyguardSecure).put("battery_unrestricted", power.isIgnoringBatteryOptimizations(context.packageName))
            .put("cpu_awake", MowerService.cpuAwake).put("phone_foreground", foreground != null)
            .put("last_action", lastAction).put("version", com.aliothmoon.maameow.BuildConfig.VERSION_NAME)
    }
}
