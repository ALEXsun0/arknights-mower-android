package com.aliothmoon.maameow.mower

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

object MowerDiagnostics {
    fun redact(value: String) = value
        .replace(Regex("(?i)(token[=\\s:\"']+)[a-z0-9._-]+"), "$1[redacted]")
        .replace(Regex("\\b[a-f0-9]{64}\\b"), "[redacted]")

    internal data class Plan(val since: Long, val until: Long, val retentionHours: Double,
        val logs: Map<String, String>, val images: DiagnosticFiles.Selection, val includeImages: Boolean, val compactImages: Boolean) {
        val estimatedBytes get() = images.bytes + logs.values.sumOf { it.toByteArray().size.toLong() }
    }

    internal fun prepare(context: Context, since: Long, until: Long, includeImages: Boolean, compactImages: Boolean): Plan {
        require(since < until) { "开始时间必须早于结束时间" }
        val logs = linkedMapOf<String, String>()
        fun collect(name: String, file: File, limit: Int) {
            if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val text = MowerLogFiles.tail(file, limit)
                val selected = DiagnosticFiles.window(text, since, until)
                logs[name] = redact((if (file.length() > limit) "[日志达到读取上限，较早内容可能未收入]\n" else "") + selected)
            }
        }
        collect("runtime.log", File(context.filesDir, "python.log"), 4 * 1024 * 1024)
        MowerLogFiles.recent(File(context.filesDir, "mower-data/log")).forEach {
            collect("mower/${it.name}", it, 2 * 1024 * 1024)
        }
        val images = if (includeImages) DiagnosticFiles.select(File(context.filesDir, "mower-data/screenshot"), since, until, logs.values.joinToString("\n"),
                budget = if (compactImages) DiagnosticFiles.IMAGE_BUDGET else Long.MAX_VALUE, maximum = if (compactImages) 512 else Int.MAX_VALUE)
            else DiagnosticFiles.Selection(emptyList(), 0, 0, emptyList())
        return Plan(since, until, ScreenshotPreferences(context).hours(), logs, images, includeImages, compactImages)
    }

    /** A failed or cancelled export never replaces a complete archive or removes source images. */
    internal fun build(context: Context, plan: Plan, progress: (Int, Int, String) -> Unit): File {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val target = File(directory, "mower-diagnostics-${plan.until}-${UUID.randomUUID().toString().take(8)}.zip")
        val temporary = File(directory, "${target.name}.part")
        val included = JSONArray(); val missing = JSONArray(plan.images.missingReferences)
        val total = plan.logs.size + plan.images.frames.size + 9
        check(directory.usableSpace > plan.estimatedBytes + 32L * 1024 * 1024) { "空间不足：需约 ${plan.estimatedBytes / 1048576} MiB 及 32 MiB 余量，请缩短时段或选关键截图" }
        var complete = 0
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                // Keep one compression level for the archive: Android Deflater can corrupt
                // the next entry when its level changes between image and text entries.
                output.setLevel(1)
                fun entry(name: String, value: String) {
                    progress(complete, total, name)
                    output.putNextEntry(ZipEntry(name)); output.write(redact(value).toByteArray()); output.closeEntry()
                    complete++
                }
                plan.logs.forEach { (name, text) -> entry(name, text) }
                entry("device.txt", "APK ${com.aliothmoon.maameow.BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n${MowerService.message}\n")
                val checks = File(context.filesDir, "startup-check.txt")
                entry("startup-check.txt", if (checks.isFile) MowerLogFiles.tail(checks, 64 * 1024) else "暂无启动检查")
                entry("process-exits.txt", ProcessExitDiagnostics.report(context))
                entry("storage.txt", StorageDiagnostics.report(context.filesDir, context.cacheDir))
                entry("network.txt", NetworkDiagnostics.report(context))
                val startup = File("/data/local/tmp/mower-background-launch.log")
                entry("background.log", if (startup.canRead()) MowerLogFiles.tail(startup, 128 * 1024) else "后台日志不可读")
                val process = ProcessBuilder("/system/bin/logcat", "-d", "-t", "500", "--pid=${android.os.Process.myPid()}").start()
                try { entry("android.log", process.inputStream.bufferedReader().use { it.readText() }) }
                finally { process.destroy() }
                plan.images.frames.forEach { frame ->
                    progress(complete, total, frame.name)
                    // Cleanup can remove a frame after preview; report that instead of losing the whole export.
                    val input = if (Files.isRegularFile(frame.file.toPath(), LinkOption.NOFOLLOW_LINKS))
                        runCatching { Files.newInputStream(frame.file.toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrNull() else null
                    if (input == null) missing.put(frame.name)
                    else input.use {
                        output.putNextEntry(ZipEntry("screenshots/${frame.name}"))
                        val buffer = ByteArray(64 * 1024); var copied = 0L
                        while (true) {
                            val count = it.read(buffer); if (count < 0) break
                            copied += count
                            check(copied <= frame.bytes) { "截图在导出期间发生变化，请重新导出" }
                            output.write(buffer, 0, count)
                            progress(complete, total, frame.name)
                        }
                        check(copied == frame.bytes) { "截图在导出期间发生变化，请重新导出" }
                        output.closeEntry()
                        included.put(JSONObject().put("path", "screenshots/${frame.name}").put("captured_at", Instant.ofEpochMilli(frame.captured).toString()).put("bytes", copied))
                    }
                    complete++
                }
                val manifest = JSONObject().put("format", 2).put("from", Instant.ofEpochMilli(plan.since).toString())
                    .put("until", Instant.ofEpochMilli(plan.until).toString()).put("screenshot_retention_hours", plan.retentionHours)
                    .put("screenshots_requested", plan.includeImages).put("available_screenshots", plan.images.available).put("available_screenshot_bytes", plan.images.availableBytes)
                    .put("omitted_by_limit", plan.images.omitted).put("compact_screenshots", plan.compactImages)
                    .put("missing_or_expired", missing).put("screenshots", included)
                entry("manifest.json", manifest.toString(2))
                entry("README.txt", "诊断时间：${Instant.ofEpochMilli(plan.since)} 至 ${Instant.ofEpochMilli(plan.until)}（UTC，日志为手机本地时间）\n" +
                    "截图保留设置：${plan.retentionHours} 小时；导出未修改清理规则。\n" +
                    "包含截图：${included.length()} 张；因关键截图模式上限省略 ${plan.images.omitted} 张；日志引用或预览后已缺失 ${missing.length()} 张。\n" +
                    (if (plan.compactImages) "关键截图模式：优先订单截图及错误前后两分钟，其余在时段内采样，上限 128 MiB / 512 张。\n" else "完整截图模式：收入所选时段内所有仍可读取的截图。\n") +
                    "详情见 manifest.json；是否勾选截图：${plan.includeImages}。\n" +
                    "设置为 0 时不保存新截图；已清理或未写盘的历史画面无法恢复。重要分类沿用 Mower 保留最近 100 张的规则。\n" +
                    "日志仅包含所选时段，控制台最多读取末尾 4 MiB，最近四个 Mower 日志各 2 MiB；超限会在文件头说明。\n" +
                    "不包含配置、Token 或解锁录制。系统诊断包含最近状态，可能覆盖其他时段。\n")
            }
            check(temporary.renameTo(target)) { "无法保存诊断包" }
            directory.listFiles().orEmpty().filter { it != target && it.name.startsWith("mower-diagnostics") && it.extension == "zip" && it.lastModified() < System.currentTimeMillis() - 86400000 }
                .forEach { it.delete() }
            progress(total, total, "已生成 ${included.length()} 张截图的诊断包")
            return target
        } finally { temporary.delete() }
    }

    fun share(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = android.content.ClipData.newUri(context.contentResolver, "Mower 诊断日志", uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
