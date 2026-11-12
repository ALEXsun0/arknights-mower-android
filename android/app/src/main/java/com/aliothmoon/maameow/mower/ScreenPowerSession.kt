package com.aliothmoon.maameow.mower

/** 所有屏幕电源操作按请求顺序执行，恢复请求会取消尚未执行的关屏。 */
internal class ScreenPowerSession(
    private val execute: (() -> Unit) -> Unit,
    private val setPower: (Boolean) -> Boolean,
    private val reportFailure: (String) -> Unit,
) {
    @Volatile var manuallyOff = false
        private set
    private var generation = 0
    private var requestedOff = false
    // 串行执行器更新补偿状态；关屏调用失败也可能已经影响部分物理屏幕。
    @Volatile private var restorePending = false

    @Synchronized fun turnOff(manual: Boolean = false, onFailure: () -> Unit = {}) {
        if (manuallyOff) return
        manuallyOff = manual
        requestedOff = true
        val ticket = ++generation
        execute {
            if (synchronized(this) { ticket != generation }) return@execute
            restorePending = true
            if (!runCatching { setPower(false) }.getOrDefault(false)) {
                onFailure()
                restorePower()
                synchronized(this) { if (ticket == generation) manuallyOff = false }
            }
        }
    }

    @Synchronized fun restore(automatic: Boolean = false, after: () -> Unit = {}): Boolean {
        if (automatic && manuallyOff) return false
        manuallyOff = false
        generation++
        val needsRestore = requestedOff || restorePending
        requestedOff = false
        if (!needsRestore) {
            after()
            return true
        }
        execute {
            restorePower()
            after()
        }
        return true
    }

    private fun restorePower() {
        if (!restorePending) return
        if (runCatching { setPower(true) }.getOrDefault(false)) restorePending = false
        else reportFailure("屏幕恢复未成功，请短按电源键，未亮屏时稍等再按一次；重新连接后台时会再次恢复")
    }
}
