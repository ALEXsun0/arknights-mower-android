package com.aliothmoon.maameow.mower

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject

/** Expand APK resources before starting Python; activation keeps its existing backup transaction. */
internal object BundledMaaPreparation {
    fun prepare(data: File, progress: (String, Int) -> Unit): Boolean {
        val pending = File(data, "maa-bundled-pending")
        if (!pending.isFile) return false
        val generation = pending.readText().trim()
        if (File(data, "maa/.apk-bundle-generation").let { it.isFile && it.readText() == generation }) return false
        val archive = File(data, "maa-component.zip")
        val expected = File(data, "maa-component.sha256").readText().trim()
        check(expected.matches(Regex("[a-f0-9]{64}"))) { "MAA 组件校验信息无效" }
        val stage = File(data, "maa-bundled-unpacked")
        val receipt = File(data, "maa-bundled-unpacked.json")
        val metadata = runCatching { JSONObject(receipt.readText()) }.getOrNull()
        if (metadata?.optString("generation") == generation && metadata.optString("sha256") == expected &&
            File(stage, ".mower-android.json").isFile) return false
        receipt.delete()
        progress("校验 MAA 资源", 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var consumed = 0L
        archive.inputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                consumed += count
                progress("校验 MAA 资源", (consumed * 100 / archive.length().coerceAtLeast(1)).toInt())
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "MAA 组件校验失败，原组件已保留" }
        progress("清理未完成的 MAA 资源", 0)
        check(stage.deleteRecursively() && stage.mkdirs()) { "无法准备 MAA 解压目录" }
        val root = stage.canonicalPath + "/"
        ZipFile(archive).use { zip ->
            val entries = zip.entries().toList()
            val total = entries.sumOf { maxOf(it.size, 0) + 1 }.coerceAtLeast(1)
            // Validate every name before any output. ZipFile is never asked to create links.
            for (entry in entries) {
                val name = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                check(name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && '\u0000' !in name &&
                    name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "MAA 资源包含非法路径" }
                check(File(stage, name).canonicalPath.startsWith(root)) { "MAA 资源路径超出解压目录" }
            }
            consumed = 0
            val directories = mutableSetOf(stage)
            progress("解压 MAA 资源", 0)
            for (entry in entries) {
                val target = File(stage, entry.name)
                val directory = if (entry.isDirectory) target else target.parentFile!!
                if (directories.add(directory)) check(directory.isDirectory || directory.mkdirs()) { "无法创建 MAA 资源目录" }
                if (!entry.isDirectory) zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        consumed += count
                        progress("解压 MAA 资源", (consumed * 100 / total).toInt().coerceAtMost(99))
                    }
                } }
                consumed++
                progress("解压 MAA 资源", (consumed * 100 / total).toInt().coerceAtMost(99))
            }
        }
        check(File(stage, ".mower-android.json").isFile) { "MAA 资源缺少版本清单" }
        val temp = File(data, "maa-bundled-unpacked.new")
        temp.writeText(JSONObject().put("generation", generation).put("sha256", expected).toString())
        check(temp.renameTo(receipt)) { "无法保存 MAA 资源解压状态" }
        progress("MAA 资源解压完成", 100)
        return true
    }
}
