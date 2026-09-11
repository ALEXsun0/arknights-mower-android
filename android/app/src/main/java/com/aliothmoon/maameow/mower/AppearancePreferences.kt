package com.aliothmoon.maameow.mower

import android.content.Context
import java.io.File

internal object AppearancePreferences {
    @Synchronized fun sync(context: Context) {
        val dark = context.getSharedPreferences("appearance", 0).getBoolean("dark", false)
        val target = File(context.filesDir, "mower-data/native-appearance.json")
        val content = if (dark) "{\"theme\":\"dark\"}" else "{\"theme\":\"light\"}"
        if (target.isFile && target.readText() == content) return
        target.parentFile!!.mkdirs()
        val staged = File(target.parentFile, "native-appearance.json.new")
        staged.writeText(content)
        check(staged.renameTo(target)) { "无法保存界面外观" }
    }
}
