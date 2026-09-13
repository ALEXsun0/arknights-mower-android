package com.aliothmoon.maameow.mower

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.app.KeyguardManager
import android.util.Base64
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.json.JSONArray
import org.json.JSONObject

/** Fixed diagnostic sources only. Never crosses into settings, credentials, or unlock recordings. */
internal class AndroidDiagnosticTools(private val context: Context) : DiagnosticMcpBackend {
    override fun tools(): JSONArray {
        fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
        fun tool(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()) = JSONObject()
            .put("name", name).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false))
            .put("annotations", JSONObject().put("readOnlyHint", true).put("destructiveHint", false).put("idempotentHint", true).put("openWorldHint", false))
        fun window() = JSONObject().put("since", integer("开始时间，Unix 毫秒，默认最近两小时"))
            .put("until", integer("结束时间，Unix 毫秒，默认当前时间"))
        return JSONArray()
            .put(tool("android_status", "Android 版本、运行状态、屏幕/锁屏状态、权限、网络和最近进程退出原因。不会唤醒或操作手机。", JSONObject()))
            .put(tool("read_logs", "读取已脱敏日志；运行日志按时间筛选，最多读取最近四个文件各 128 KiB。startup 为最近一次启动检查，不按时间筛选。", window()
                .put("source", JSONObject().put("type", "string").put("enum", JSONArray(listOf("mower", "console", "network", "startup")))), listOf("source")))
            .put(tool("list_screenshots", "列出已保存游戏截图的 ID、时间和大小；每页最多 100 张。不会新截图或修改保留时间。offset 为本次时间范围内的偏移，清理后需重新列出。", window()
                .put("offset", integer("分页偏移，从 0 开始"))))
            .put(tool("read_screenshot", "按 list_screenshots 返回的 ID 读取一张已保存游戏截图，最多 8 MiB。", JSONObject()
                .put("id", JSONObject().put("type", "string")), listOf("id")))
    }
    override fun call(name: String, args: JSONObject): JSONObject {
        val allowed = when (name) {
            "android_status" -> emptySet()
            "read_logs" -> setOf("source", "since", "until")
            "list_screenshots" -> setOf("since", "until", "offset")
            "read_screenshot" -> setOf("id")
            else -> return DiagnosticMcpBackend.text("未知诊断工具", true)
        }
        require(args.keys().asSequence().all { it in allowed })
        return when (name) {
            "android_status" -> {
                val power = context.getSystemService(PowerManager::class.java)
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                val info = JSONObject().put("apk_version", com.aliothmoon.maameow.BuildConfig.VERSION_NAME)
                    .put("version_code", com.aliothmoon.maameow.BuildConfig.VERSION_CODE)
                    .put("last_update_time", context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
                    .put("time", System.currentTimeMillis()).put("timezone", java.util.TimeZone.getDefault().id)
                    .put("service_active", MowerService.active).put("service_message", MowerService.message)
                    .put("webui_ready", MowerService.url != null).put("backend_bridge_ready", MowerService.engine != null)
                    .put("observed_game_fps", MowerService.monitor?.gameFps ?: -1f).put("last_system_action", AndroidSystemSettings.lastAction)
                    .put("interactive", power.isInteractive).put("device_idle", power.isDeviceIdleMode)
                    .put("locked", keyguard.isKeyguardLocked).put("cpu_awake", MowerService.cpuAwake)
                    .put("free_bytes", context.filesDir.usableSpace).put("screenshot_retention_hours", ScreenshotPreferences(context).hours())
                    .put("permissions", JSONArray(PermissionChecks.inspect(context).entries.map { JSONObject().put("name", it.title).put("state", it.state).put("granted", it.granted) }))
                    .put("network", NetworkDiagnostics.report(context)).put("process_exits", ProcessExitDiagnostics.report(context))
                DiagnosticMcpBackend.text(MowerDiagnostics.redact(info.toString(2)))
            }
            "read_logs" -> {
                val (since, until) = window(args)
                val source = args.getString("source")
                val files = when (source) {
                    "mower" -> MowerLogFiles.recent(File(context.filesDir, "mower-data/log"))
                    "console" -> listOf(File(context.filesDir, "python.log"))
                    "network" -> listOf(File(context.filesDir, "network-events.log"))
                    "startup" -> listOf(File(context.filesDir, "startup-check.txt"))
                    else -> error("Unknown source")
                }
                val logs = JSONArray()
                files.filter { safeFile(it) }.forEach { file ->
                    val tail = MowerLogFiles.tail(file, 128 * 1024)
                    logs.put(JSONObject().put("name", file.name).put("tail_limited", file.length() > 128 * 1024)
                        .put("text", MowerDiagnostics.redact(when (source) {
                            "startup" -> tail
                            "network" -> tail.lineSequence().filter { line ->
                                runCatching { java.time.OffsetDateTime.parse(line.substringBefore(' ')).toInstant().toEpochMilli() in since..until }.getOrDefault(false)
                            }.joinToString("\n")
                            else -> DiagnosticFiles.window(tail, since, until)
                        })))
                }
                DiagnosticMcpBackend.text(JSONObject().put("since", since).put("until", until).put("files", logs).toString())
            }
            "list_screenshots" -> {
                val (since, until) = window(args)
                val offset = number(args, "offset", 0); require(offset in 0..Int.MAX_VALUE.toLong())
                val images = frames(since, until)
                val page = images.drop(offset.toInt()).take(100)
                DiagnosticMcpBackend.text(JSONObject().put("since", since).put("until", until).put("total", images.size)
                    .put("bytes", images.sumOf { it.bytes }).put("next_offset", if (offset + page.size < images.size) offset + page.size else JSONObject.NULL)
                    .put("screenshots", JSONArray(page.map { JSONObject().put("id", it.name).put("time", it.captured).put("bytes", it.bytes) })).toString())
            }
            else -> {
                val id = args.getString("id"); require(id.length <= 160)
                val frame = frames(0, Long.MAX_VALUE).firstOrNull { it.name == id } ?: error("Screenshot expired")
                check(safeFile(frame.file) && frame.bytes in 1..8L * 1024 * 1024)
                val bytes = Files.newInputStream(frame.file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        check(output.size() + count <= 8 * 1024 * 1024); output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val mime = when (frame.file.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
                JSONObject().put("content", JSONArray().put(JSONObject().put("type", "image").put("mimeType", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
            }
        }
    }
    private fun safeFile(file: File) = file.toPath().startsWith(context.filesDir.toPath()) &&
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
        generateSequence(file) { it.parentFile }.takeWhile { it != context.filesDir }.none { Files.isSymbolicLink(it.toPath()) }
    private fun frames(since: Long, until: Long) = DiagnosticFiles.select(File(context.filesDir, "mower-data/screenshot"), since, until, "", Long.MAX_VALUE, Int.MAX_VALUE).frames
    private fun number(args: JSONObject, key: String, fallback: Long): Long {
        if (!args.has(key)) return fallback
        val value = args.get(key); require(value is Int || value is Long)
        return (value as Number).toLong()
    }
    private fun window(args: JSONObject): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val until = number(args, "until", now)
        val since = number(args, "since", maxOf(0, until - 2 * 3600000))
        require(since >= 0 && since < until && until <= now + 60000)
        return since to until
    }
}
