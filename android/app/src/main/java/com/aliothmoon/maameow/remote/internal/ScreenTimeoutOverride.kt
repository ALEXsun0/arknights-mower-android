package com.aliothmoon.maameow.remote.internal

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Persist the original timeout before temporarily disabling automatic sleep. */
internal class ScreenTimeoutOverride(
    private val recoveryFile: File,
    private val currentUser: () -> Int,
    private val read: (Int) -> String?,
    private val write: (Int, String?) -> Unit,
) {
    companion object { const val NEVER = "2147483647" }
    private data class Saved(val user: Int, val timeout: String?)

    private fun readTimeout(user: Int): String? = read(user).also { value ->
        check(value == null || value.toLongOrNull() != null) { "无法读取自动熄屏时间" }
    }

    private fun load(): Saved? {
        if (!recoveryFile.exists()) return null
        val lines = recoveryFile.readLines()
        check(lines.size == 2) { "自动熄屏恢复记录损坏" }
        val user = lines[0].toIntOrNull()
        check(user != null && user >= 0) { "自动熄屏恢复记录的用户无效" }
        val timeout = lines[1].takeUnless { it == "null" }
        check(timeout == null || timeout.toLongOrNull() != null) { "自动熄屏恢复记录的超时无效" }
        return Saved(user, timeout)
    }

    @Synchronized fun acquire() {
        val user = currentUser()
        require(user >= 0)
        var saved = load()
        if (saved != null && saved.user != user) { restore(); saved = null }
        val current = readTimeout(user)
        if (saved == null || current != NEVER) {
            val original = current
            val temp = File.createTempFile(recoveryFile.name, ".tmp", recoveryFile.parentFile)
            try {
                FileOutputStream(temp).use { output ->
                    output.write("$user\n${original ?: "null"}\n".toByteArray())
                    output.fd.sync()
                }
                Files.move(temp.toPath(), recoveryFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        write(user, NEVER)
        check(readTimeout(user) == NEVER) { "无法暂停自动熄屏" }
    }

    @Synchronized fun restore() {
        val saved = load() ?: return
        // Preserve a newer value chosen outside this session.
        if (readTimeout(saved.user) == NEVER) {
            write(saved.user, saved.timeout)
            check(readTimeout(saved.user) == saved.timeout) { "无法恢复原自动熄屏时间" }
        }
        check(recoveryFile.delete()) { "无法清理自动熄屏恢复记录" }
    }
}
