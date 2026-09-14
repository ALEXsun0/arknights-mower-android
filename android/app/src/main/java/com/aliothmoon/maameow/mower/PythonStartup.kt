package com.aliothmoon.maameow.mower

import java.io.File
import org.json.JSONObject

internal data class PythonStartupProgress(val stage: String, val percent: Int) {
    val description: String get() = stage + if (percent >= 0) " $percent%" else ""

    companion object {
        fun read(file: File): PythonStartupProgress? = runCatching {
            if (!file.isFile || file.length() !in 1..4096) return null
            val data = JSONObject(file.readText())
            val stage = data.getString("stage")
            val percent = data.getInt("percent")
            if (stage.length !in 1..80 || percent !in -1..100) return null
            PythonStartupProgress(stage, percent)
        }.getOrNull()
    }
}

/** Give slow installation time while progress advances, but never wait forever. */
internal class PythonStartup(private val started: Long) {
    private var advanced = started
    var progress: PythonStartupProgress? = null
        private set
    private val seen = mutableSetOf<PythonStartupProgress>()

    fun observe(value: PythonStartupProgress?, now: Long): Boolean {
        if (value == null || value == progress || !seen.add(value)) return false
        progress = value
        advanced = now
        return true
    }

    fun expired(now: Long) = now - advanced >= 60_000 || now - started >= 600_000
    fun failure(now: Long): String = if (now - started >= 600_000)
        "Python 初始化超过 10 分钟（${progress?.description ?: "等待启动器"}），请查看诊断日志"
    else "${progress?.description ?: "Python 启动器"} 超过 60 秒未推进，请查看诊断日志"
}
