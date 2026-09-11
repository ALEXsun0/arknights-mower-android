package com.aliothmoon.maameow.mower

import android.app.ActivityManager
import android.content.Context
import android.os.Build

internal object ProcessExitDiagnostics {
    fun report(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "此系统不提供历史进程退出原因。"
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 12)
                .filter { it.processName == context.packageName }
                .joinToString("\n") {
                    "${java.time.Instant.ofEpochMilli(it.timestamp)} reason=${it.reason} status=${it.status} ${it.description.orEmpty()}"
                }.ifEmpty { "没有应用进程退出记录。" }
        }.getOrDefault("无法读取系统进程退出记录。")
    }
}
