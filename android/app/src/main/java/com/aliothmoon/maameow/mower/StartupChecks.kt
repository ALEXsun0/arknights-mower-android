package com.aliothmoon.maameow.mower

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/** Small reads only: normal starts must not hash or rewrite the entire environment. */
internal object StartupChecks {
    val runtimeFiles = listOf(
        "usr/local/bin/python", "usr/bin/env", "lib/ld-linux-aarch64.so.1",
        "usr/local/lib/libpython3.12.so.1.0", "mower/mower_android/launcher.py",
        "mower/server.py", "mower/arknights_mower/__init__.py",
        "mower/ui/dist/index.html", "mower/CHANGELOG.md",
    )
    private val executables = runtimeFiles.take(3)

    fun incompleteRuntime(root: File): List<String> = runtimeFiles.filter { name ->
        val file = File(root, name)
        !file.isFile || file.length() == 0L || !file.canRead() ||
            (name in executables && (!file.canExecute() || !isElf(file)))
    }

    private fun isElf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46).all { input.read() == it.toInt() }
        }
    }.getOrDefault(false)

    fun nativeRuntime(directory: File) {
        for (name in listOf("libproot.so", "libproot-loader.so", "liblauncher.so", "libtalloc.so")) {
            val file = File(directory, name)
            check(file.canRead() && file.canExecute() && isElf(file)) {
                "Android 启动组件不完整，请覆盖安装 APK（$name）"
            }
        }
    }

    fun storage(available: Long, installing: Boolean) {
        // Enough for the 624 MB expanded image, archive and initial MAA extraction.
        // A replacement keeps the previous environment until the new one is ready.
        val required = if (installing) 1536L * 1024 * 1024 else 64L * 1024 * 1024
        check(available >= required) {
            if (installing) "解压运行环境至少需要 1.5 GiB 可用空间，请清理手机存储后重试"
            else "手机可用空间不足 64 MiB，请清理存储后重试"
        }
    }

    fun pythonFailure(log: File, offset: Long): String? {
        val tail = runCatching {
            RandomAccessFile(log, "r").use {
                it.seek(maxOf(offset.coerceAtMost(it.length()), it.length() - 32 * 1024))
                val bytes = ByteArray((it.length() - it.filePointer).toInt())
                it.readFully(bytes); String(bytes)
            }
        }.getOrDefault("")
        return when {
            "No space left on device" in tail -> "存储空间不足，请清理手机存储后重试"
            "Address already in use" in tail -> "WebUI 端口被占用，请在局域网设置修改端口后重试"
            "MAA 组件校验失败" in tail -> "MAA 组件校验失败，请查看诊断日志并重新导入官方组件包"
            "ModuleNotFoundError" in tail || "ImportError" in tail -> "Python 依赖加载失败，请在诊断日志查看缺失模块"
            "Permission denied" in tail -> "运行环境访问被拒绝，请在诊断日志查看系统限制"
            else -> null
        }
    }

    @Synchronized
    fun recoverRuntime(root: File, backup: File): Boolean {
        val pending = File(root.parentFile, "rootfs-pending")
        if (!pending.exists() || !backup.exists()) return false
        val rejected = File(root.parentFile, "rootfs-rejected")
        check(!rejected.exists()) { "上次失败环境尚未清理，请先启动原环境或清理存储后重试" }
        val stamp = pending.readText()
        if (root.exists()) check(root.renameTo(rejected)) { "无法暂存失败环境，回退备份已保留" }
        check(backup.renameTo(root)) { "无法恢复原环境，回退备份已保留" }
        File(root.parentFile, "rootfs-rejected-version").writeText(stamp)
        pending.delete()
        return true
    }

    @Synchronized
    fun commitRuntime(root: File): String {
        val stamp = File(root, ".mower-runtime").readText()
        val pending = File(root.parentFile, "rootfs-pending")
        check(!pending.exists() || pending.readText() == stamp) { "运行环境版本已改变，保留备份" }
        check(!pending.exists() || pending.delete()) { "无法提交运行环境验证状态，备份已保留" }
        return stamp
    }

    @Synchronized
    fun cleanupRuntime(root: File, stamp: String) {
        if (File(root.parentFile, "rootfs-pending").exists() || File(root, ".mower-runtime").readText() != stamp) return
        for (name in listOf("rootfs-backup", "rootfs-rejected")) {
            check(File(root.parentFile, name).deleteRecursively()) { "旧运行环境暂无法清理，下次服务启动后重试" }
        }
    }

    @Synchronized
    fun activateRuntime(staged: File, root: File, backup: File) {
        check(backup.deleteRecursively()) { "无法清理旧运行环境，请检查存储权限" }
        val pending = File(root.parentFile, "rootfs-pending")
        pending.writeText(File(staged, ".mower-runtime").takeIf { it.exists() }?.readText().orEmpty())
        if (root.exists() && !root.renameTo(backup)) {
            pending.delete()
            error("无法暂存旧运行环境，请重试")
        }
        if (!staged.renameTo(root)) {
            val restored = !backup.exists() || backup.renameTo(root)
            if (restored) pending.delete()
            error(if (restored) "无法安装新运行环境，原环境已保留，请检查存储"
                else "无法安装新运行环境，原环境备份已保留，请重新启动服务恢复")
        }
    }
}

internal data class RuntimeInstallProgress(val stage: String, val percent: Int)

/** Percentage of compressed bytes consumed; capped until extraction and commit finish. */
internal class InstallProgressInput(input: InputStream, private val total: Long, private val update: (Int) -> Unit) : FilterInputStream(input) {
    private var consumed = 0L
    private var last = -1
    private fun advance(count: Int) {
        if (count > 0) consumed += count
        val percent = if (total > 0) (consumed * 100 / total).coerceIn(0, 99).toInt() else 0
        if (percent != last) { last = percent; update(percent) }
    }
    override fun read(): Int = `in`.read().also { advance(if (it < 0) 0 else 1) }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also(::advance)
}
