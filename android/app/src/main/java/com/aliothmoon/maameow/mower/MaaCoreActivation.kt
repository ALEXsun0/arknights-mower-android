package com.aliothmoon.maameow.mower

/** Replace only the idle native background process; the Python service stays online. */
internal object MaaCoreActivation {
    fun activate(
        expected: String,
        installed: String,
        loaded: String,
        ready: Boolean,
        running: Boolean,
        blocked: Boolean,
        replace: () -> String,
    ): Boolean {
        require(expected.matches(Regex("[a-f0-9]{64}")) && expected == installed) { "MAA 安装版本已改变，请重新检查更新" }
        if (loaded == expected) return ready
        // An unused core is loaded by the next prepare, without creating a game display here.
        if (loaded.isEmpty()) return false
        if (running || blocked) return false
        check(replace() == expected) { "MAA 新核心尚未加载，请重新连接后台游戏" }
        return true
    }
}
