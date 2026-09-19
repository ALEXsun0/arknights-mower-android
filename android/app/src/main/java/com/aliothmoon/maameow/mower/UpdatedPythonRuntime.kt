package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream

/** Select before launching Python so even a broken interpreter can be rolled back. */
internal class UpdatedPythonRuntime(private val data: File, private val bundled: File, private val generation: String) {
    data class Selection(val id: String?, val program: File, val root: File)
    private val programs = File(data, "mower-programs").apply { mkdirs() }
    private fun valid(value: String?) = value != null && value.matches(Regex("[a-f0-9]{64}"))
    private fun id(state: JSONObject, key: String) = state.optString(key).takeIf(::valid)
    private fun save(state: JSONObject) {
        if (!state.has("apk_generation")) state.put("apk_generation", generation)
        val temporary = File(programs, "active.new")
        temporary.writeText(state.toString())
        check(temporary.renameTo(File(programs, "active.json"))) { "无法保存 Mower 更新状态" }
    }

    fun select(apkCode: Int, progress: (String, Int) -> Unit = { _, _ -> }): Selection {
        var state = runCatching { JSONObject(File(programs, "active.json").readText()) }.getOrElse { JSONObject() }
        if (state.optString("apk_generation") != generation) {
            val previous = id(state, if (state.optBoolean("pending") || state.optBoolean("booting")) "previous" else "id")
            state = JSONObject().put("id", JSONObject.NULL).put("previous", previous ?: JSONObject.NULL)
                .put("pending", true).put("apk_generation", generation)
            save(state)
        }
        if (state.optBoolean("booting")) {
            state = JSONObject().put("id", id(state, "previous") ?: JSONObject.NULL).put("rollback", true)
            save(state)
        }
        if (state.optBoolean("pending")) { state.put("booting", true); save(state) }
        val active = id(state, "id")
        val directory = active?.let { File(programs, it) }
        if (directory == null || !File(directory, "mower/server.py").isFile) return Selection(null, File(bundled, "mower"), bundled)
        check(directory.canonicalFile.parentFile == programs.canonicalFile && !Files.isSymbolicLink(directory.toPath())) { "Mower 更新目录无效" }
        val manifestFile = File(directory, "mower-android.json")
        check(manifestFile.length() in 1..65536) { "Mower 更新清单无效" }
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.optString("kind") == "mower-android" && manifest.optString("arch") == "arm64" &&
            manifest.optString("platform") == "android" && manifest.optInt("runtime_api") == 1 &&
            manifest.optInt("min_apk", 0) <= apkCode) { "请先更新 APK 以运行此 Mower 更新包" }
        val python = manifest.getString("python")
        val root = when (manifest.getInt("format")) {
            1 -> { check(python == "3.12") { "此更新包需要新的 Python 环境" }; bundled }
            2 -> {
                check(python.matches(Regex("3\\.[0-9]+"))) { "Python 版本无效" }
                install(directory, manifest.getJSONObject("runtime"), python, progress)
            }
            else -> error("Mower 更新格式不兼容")
        }
        return Selection(active, File(directory, "mower"), root)
    }

    private fun install(directory: File, metadata: JSONObject, python: String, progress: (String, Int) -> Unit): File {
        check(metadata.getString("file") == "python-runtime.zip.xz") { "Python 运行包路径无效" }
        val expected = metadata.getString("sha256")
        check(expected.matches(Regex("[a-f0-9]{64}"))) { "Python 运行包校验信息无效" }
        val limit = metadata.getLong("unpacked_size")
        check(limit in 1..(2L * 1024 * 1024 * 1024)) { "Python 运行环境大小无效" }
        val root = File(directory, "runtime-root")
        if (runCatching { File(root, ".mower-updated-runtime").readText() == expected && complete(root, python) }.getOrDefault(false)) return root
        val stage = File(directory, "runtime-install")
        stage.deleteRecursively()
        check(directory.usableSpace >= limit + 64L * 1024 * 1024) { "解压 Python 更新环境空间不足，请清理手机存储" }
        val archive = File(directory, "python-runtime.zip.xz")
        val digest = MessageDigest.getInstance("SHA-256")
        InstallProgressInput(archive.inputStream().buffered(), archive.length()) { progress("校验 Python 更新环境", it) }.use { input ->
            val buffer = ByteArray(262144)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Python 更新运行包校验失败" }
        check(stage.mkdirs()) { "无法创建 Python 更新环境目录" }
        try {
            extract(archive, stage, limit, progress)
            File(stage, "mower").mkdirs()
            File(stage, "mower-data").mkdirs()
            check(complete(stage, python)) { "Python 更新环境关键文件不完整" }
            File(stage, ".mower-updated-runtime").writeText(expected)
            check(root.deleteRecursively() && stage.renameTo(root)) { "无法切换 Python 更新环境" }
            progress("Python 更新环境准备完成", 100)
            return root
        } finally { stage.deleteRecursively() }
    }

    companion object {
        private fun path(root: File, name: String): File {
            check(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name &&
                name.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Python 运行包路径无效" }
            val file = File(root, name)
            check(file.canonicalPath.startsWith(root.canonicalPath + "/")) { "Python 运行包路径越界" }
            return file
        }
        private fun complete(root: File, python: String): Boolean = listOf(
            "usr/local/bin/python", "usr/bin/env", "lib/ld-linux-aarch64.so.1",
            "usr/local/lib/libpython$python.so.1.0",
        ).all {
            val file = File(root, it)
            file.isFile && file.length() > 0 && file.canRead() && file.canExecute() &&
                file.inputStream().use { stream -> byteArrayOf(127, 69, 76, 70).all { byte -> stream.read() == byte.toInt() } }
        }
        internal fun extract(archive: File, root: File, limit: Long, progress: (String, Int) -> Unit = { _, _ -> }) {
            val names = mutableSetOf<String>()
            var bytes = 0L
            ZipInputStream(XZInputStream(InstallProgressInput(archive.inputStream().buffered(), archive.length()) {
                progress("解压 Python 更新环境", it)
            }, 65536)).use { zip ->
                val buffer = ByteArray(262144)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.trimEnd('/')
                    check(names.add(name) && names.size <= 100000 && name != ".mower-updated-runtime") { "Python 运行包重复文件或文件数量超限" }
                    check(!name.startsWith("mower/") && !name.startsWith("mower-data/") &&
                        (name !in setOf("mower", "mower-data") || entry.isDirectory)) { "Python 运行包不能携带宿主或用户数据" }
                    val file = path(root, entry.name)
                    if (entry.isDirectory) check(file.mkdirs() || file.isDirectory) else {
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { output ->
                            while (true) {
                                val count = zip.read(buffer); if (count < 0) break
                                bytes += count
                                check(bytes <= limit) { "Python 运行包解压大小超限" }
                                output.write(buffer, 0, count)
                            }
                        }
                        check(file.setExecutable(true, false)) { "无法设置 Python 文件执行权限" }
                    }
                }
            }
            check(bytes == limit) { "Python 运行包解压大小与清单不一致" }
            val manifest = File(root, ".symlinks.json")
            check(manifest.length() in 1..(8L * 1024 * 1024)) { "Python 符号链接清单无效" }
            val links = JSONObject(manifest.readText())
            check(links.length() <= 100000) { "Python 符号链接数量超限" }
            // Validate all paths before creating any links; links may not parent other entries.
            val linkNames = links.keys().asSequence().toSet()
            val parents = (names + linkNames).flatMap { name ->
                name.indices.filter { name[it] == '/' }.map { name.substring(0, it) }
            }.toSet()
            val entries = linkNames.map { name ->
                check(name !in names && name != "mower" && !name.startsWith("mower/") && name != "mower-data" && !name.startsWith("mower-data/")) { "Python 符号链接覆盖文件" }
                val destination = path(root, name)
                check(name !in parents) { "Python 符号链接不可包含其他文件" }
                val target = links.getString(name)
                check(target.isNotEmpty() && '\\' !in target) { "Python 符号链接目标无效" }
                val resolved = if (target.startsWith('/')) File(root, target.trimStart('/')).toPath().normalize()
                    else destination.parentFile!!.toPath().resolve(target).normalize()
                check(resolved.startsWith(root.toPath()) && resolved != root.toPath()) { "Python 符号链接目标越界" }
                destination to destination.parentFile!!.toPath().relativize(resolved)
            }.toList()
            entries.forEach { (destination, target) ->
                destination.parentFile!!.mkdirs()
                Files.createSymbolicLink(destination.toPath(), target)
            }
            manifest.delete()
        }
    }
}
