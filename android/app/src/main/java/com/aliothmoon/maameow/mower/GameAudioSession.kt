package com.aliothmoon.maameow.mower

/** 主进程死亡时，提权进程仍持有自己修改前的声音状态。 */
class GameAudioSession(
    private val read: (String) -> String,
    private val write: (String, String) -> Unit,
) {
    private val originals = linkedMapOf<String, String>()

    @Synchronized fun set(packageName: String, mode: String): String {
        if (mode == "ignore" && packageName !in originals) {
            val original = restoredGameAudioMode(read(packageName))
            if (original != "ignore") originals[packageName] = original
        }
        write(packageName, mode)
        val actual = read(packageName)
        check(actual == mode) { "游戏声音权限修改未生效" }
        if (mode != "ignore") originals.remove(packageName)
        return actual
    }

    @Synchronized fun restoreAll() {
        var failure: Exception? = null
        for ((pkg, original) in originals.toMap()) {
            try {
                // 外部的新设置不由自动清理覆盖；手动恢复另行处理残留静音。
                if (read(pkg) == "ignore") {
                    write(pkg, original)
                    check(read(pkg) == original) { "游戏声音恢复未生效：$pkg" }
                }
                originals.remove(pkg)
            } catch (error: Exception) {
                failure = error
            }
        }
        failure?.let { throw it }
    }
}

data class GameAudioModes(val packageMode: String, val uidMode: String?) {
    // AudioFlinger requires MODE_ALLOWED. Explicit MODE_DEFAULT is not the
    // operation's default (PLAY_AUDIO's unset mode is MODE_ALLOWED).
    val blocked get() = packageMode != "allow" || (uidMode != null && uidMode != "allow")

    companion object {
        fun parse(output: String): GameAudioModes {
            val modes = "(allow|ignore|deny|default|foreground)"
            val uid = Regex("(?m)^\\s*Uid mode:\\s*PLAY_AUDIO:\\s*$modes\\b")
                .find(output)?.groupValues?.get(1)
            val pkg = Regex("(?m)^\\s*PLAY_AUDIO:\\s*$modes\\b")
                .find(output)?.groupValues?.get(1)
            val unset = Regex("(?m)^\\s*Default mode:\\s*$modes\\b")
                .find(output)?.groupValues?.get(1)
            check(pkg != null || uid != null || output.contains("No operations")) {
                "无法识别系统声音权限状态"
            }
            return GameAudioModes(pkg ?: unset ?: "allow", uid)
        }
    }
}

// An earlier release wrote literal MODE_DEFAULT to PLAY_AUDIO. Treat that
// residue as the operation's actual default (allow), including v2 ledgers and
// values captured by the remote process; otherwise owner death re-applies mute.
fun restoredGameAudioMode(recorded: String): String {
    val mode = recorded.removePrefix("v2:")
    return if (mode == "default") "allow" else mode
}

/** 仅用于用户主动恢复；自动退出不能重置其他程序或用户设置的静音。 */
fun repairGameAudio(read: () -> GameAudioModes, write: (String, Boolean) -> Unit) {
    val before = read()
    if (before.uidMode != null && before.uidMode != "allow") write("allow", true)
    if (before.packageMode != "allow") write("allow", false)
    check(!read().blocked) { "游戏仍被系统声音权限静音" }
}
