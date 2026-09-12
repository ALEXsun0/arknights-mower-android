package com.aliothmoon.maameow.mower

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MowerDiagnostics {
    fun redact(value: String) = value
        .replace(Regex("(?i)(token[=\\s:\"']+)[a-z0-9._-]+"), "$1[redacted]")
        .replace(Regex("\\b[a-f0-9]{64}\\b"), "[redacted]")
    fun share(context: Context): Intent {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        // Keep one bounded export. Configuration and unlock credentials are never collected.
        val target = File(directory, "mower-diagnostics.zip")
        ZipOutputStream(target.outputStream()).use { output ->
            fun entry(name: String, value: String) {
                output.putNextEntry(ZipEntry(name)); output.write(redact(value).toByteArray()); output.closeEntry()
            }
            entry("device.txt", "APK ${com.aliothmoon.maameow.BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n${MowerService.message}\n")
            val checks = File(context.filesDir, "startup-check.txt")
            if (checks.isFile) entry("startup-check.txt", checks.readText())
            entry("process-exits.txt", ProcessExitDiagnostics.report(context))
            entry("storage.txt", StorageDiagnostics.report(context.filesDir, context.cacheDir))
            entry("network.txt", NetworkDiagnostics.report(context))
            val log = File(context.filesDir, "python.log")
            if (log.isFile) RandomAccessFile(log, "r").use {
                it.seek(maxOf(0, it.length() - 2 * 1024 * 1024))
                val data = ByteArray((it.length() - it.filePointer).toInt()); it.readFully(data)
                entry("runtime.log", String(data))
            }
            // Mower's rotating file logger contains earlier device failures which
            // may already have scrolled out of Python's console tail.
            MowerLogFiles.recent(File(context.filesDir, "mower-data/log")).forEach { file ->
                entry("mower/${file.name}", MowerLogFiles.tail(file, 512 * 1024))
            }
            val startup = File("/data/local/tmp/mower-background-launch.log")
            if (startup.canRead()) entry("background.log", startup.readText().takeLast(128 * 1024))
            val process = ProcessBuilder("/system/bin/logcat", "-d", "-t", "500", "--pid=${android.os.Process.myPid()}").start()
            entry("android.log", process.inputStream.bufferedReader().use { it.readText() }); process.destroy()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", target)
        return Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = android.content.ClipData.newUri(context.contentResolver, "Mower 诊断日志", uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
