package com.aliothmoon.maameow.mower

import android.content.SharedPreferences

internal data class SavedWebConnection(val lan: Boolean, val port: String, val token: String)

/** One store for the settings form and startup; commit failures never report success. */
internal object WebConnectionPreferences {
    @Synchronized fun read(prefs: SharedPreferences) = SavedWebConnection(
        prefs.getBoolean("lan", false), prefs.getString("port", "").orEmpty(), prefs.getString("token", "").orEmpty())

    @Synchronized fun save(prefs: SharedPreferences, value: SavedWebConnection) {
        val parsed = WebConnectionConfig.parse(value.port, value.token)
        val saved = value.copy(port = if (parsed.port == 0) "" else parsed.port.toString())
        check(prefs.edit().putBoolean("lan", saved.lan).putString("port", saved.port).putString("token", saved.token).commit()) {
            "连接设置保存失败，请检查手机存储后重试"
        }
        check(read(prefs) == saved) { "连接设置未能正确保存，请重试" }
    }

    @Synchronized fun resolve(prefs: SharedPreferences, tokenFactory: () -> String): SavedWebConnection {
        val saved = read(prefs)
        val parsed = WebConnectionConfig.parse(saved.port, saved.token)
        val port = parsed.availablePort(saved.lan)
        val resolved = saved.copy(port = port.toString(), token = saved.token.ifEmpty(tokenFactory))
        if (resolved != saved) save(prefs, resolved)
        return resolved
    }
}
