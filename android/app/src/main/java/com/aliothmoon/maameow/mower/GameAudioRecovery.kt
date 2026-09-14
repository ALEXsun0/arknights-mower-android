package com.aliothmoon.maameow.mower

import android.content.Context
import com.aliothmoon.maameow.RemoteService
import org.json.JSONObject

/** 持久记录用于后台进程也异常退出后的恢复；正常死亡清理由后台进程执行。 */
class GameAudioRecovery(context: Context) {
    private val ledger = context.getSharedPreferences("game-audio-recovery", 0)
    val pending get() = ledger.all.isNotEmpty()

    private fun call(service: RemoteService, action: String, pkg: String, mode: String? = null): JSONObject {
        val request = JSONObject().put("action", action).put("package", pkg)
        mode?.let { request.put("mode", it) }
        val response = JSONObject(service.systemRpc(request.toString()))
        check(response.getBoolean("ok")) { response.optString("error") }
        return response.getJSONObject("result")
    }

    fun restore(service: RemoteService) {
        var failure: Exception? = null
        for ((pkg, value) in ledger.all) {
            try {
                val current = call(service, "audio_get", pkg).getString("mode")
                if (current == "ignore") {
                    val restored = call(service, "audio_set", pkg, value as String)
                    check(restored.getString("mode") == value) { "游戏声音恢复未生效，可在设置页重试" }
                }
                check(ledger.edit().remove(pkg).commit()) { "声音恢复记录保存失败" }
            } catch (error: Exception) { failure = error }
        }
        failure?.let { throw it }
    }

    fun mute(service: RemoteService, pkg: String) {
        if (!ledger.contains(pkg)) {
            val mode = call(service, "audio_get", pkg).getString("mode")
            if (mode == "ignore") return
            check(ledger.edit().putString(pkg, mode).commit()) { "无法保存声音恢复记录" }
        }
        check(call(service, "audio_set", pkg, "ignore").getString("mode") == "ignore") { "游戏静音未生效" }
    }

    fun repair(service: RemoteService) {
        // 用户明确点击恢复：即使历史记录丢失，也检查两个游戏包的包级和 UID 级权限。
        // 不依赖旧记录恢复成功；旧记录本身可能就是残留的 deny/ignore。
        val response = JSONObject(service.systemRpc(JSONObject().put("action", "audio_repair").toString()))
        check(response.getBoolean("ok")) { response.optString("error") }
        check(ledger.edit().clear().commit()) { "声音恢复记录保存失败" }
    }
}
