package com.aliothmoon.maameow.mower

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object DiagnosticCommand {
    fun collect(command: List<String>, directory: File, timeoutMillis: Long = 3000): String {
        val output = File.createTempFile("diagnostic-command-", ".log", directory)
        var process: Process? = null
        try {
            val child = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
            process = child
            val finished = child.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                child.destroyForcibly()
                child.waitFor(200, TimeUnit.MILLISECONDS)
            }
            val status = when {
                !finished -> "系统日志采集超时，已跳过剩余输出。\n"
                child.exitValue() != 0 -> "系统日志命令退出码：${child.exitValue()}\n"
                else -> ""
            }
            return status + MowerLogFiles.tail(output, 256 * 1024)
        } catch (failure: IOException) {
            return "系统日志不可读：${failure.message}"
        } catch (failure: SecurityException) {
            return "系统日志不可读：${failure.message}"
        } finally {
            process?.let { child ->
                if (child.isAlive) child.destroyForcibly()
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
                runCatching { child.outputStream.close() }
            }
            output.delete()
        }
    }
}
