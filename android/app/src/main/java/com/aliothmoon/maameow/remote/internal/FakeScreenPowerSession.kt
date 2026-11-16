package com.aliothmoon.maameow.remote.internal

/** Keep Android interactive while changing only the physical panel's power. */
internal class FakeScreenPowerSession(
    private val isInteractive: () -> Boolean,
    private val wake: () -> Boolean,
    private val keepAwake: (Boolean) -> Unit,
    private val displayPower: (Boolean) -> Boolean,
) {
    @Synchronized fun setScreenOn(on: Boolean): Boolean {
        if (on) {
            val restored = displayPower(true)
            if (restored) keepAwake(false)
            return restored
        }
        if (!isInteractive() && !wake()) return false
        try {
            keepAwake(true)
        } catch (failure: Exception) {
            try { keepAwake(false) } catch (restoreFailure: Exception) { failure.addSuppressed(restoreFailure) }
            throw failure
        }
        if (displayPower(false)) return true
        // A multi-panel operation can fail after switching off one panel.
        if (displayPower(true)) keepAwake(false)
        return false
    }
}
