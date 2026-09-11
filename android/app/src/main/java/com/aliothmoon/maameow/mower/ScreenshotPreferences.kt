package com.aliothmoon.maameow.mower

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Native ownership survives desktop backup imports and Mower hot updates. */
class ScreenshotPreferences(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "mower-data/native-screenshot.json"))
    fun hours(): Double = runCatching {
        val value = JSONObject(file.openRead().bufferedReader().use { it.readText() }).getDouble("hours")
        require(value.isFinite() && value >= 0)
        value
    }.getOrDefault(0.0)

    fun save(hours: Double) {
        require(hours.isFinite() && hours >= 0) { "请输入大于或等于 0 的小时数，可填小数" }
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("hours", hours).toString().toByteArray())
            file.finishWrite(output)
        } catch (failure: Exception) { file.failWrite(output); throw failure }
    }
}
