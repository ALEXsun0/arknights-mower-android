package com.aliothmoon.maameow.mower

/** Space Mower clicks across frames; MAA and manual gestures retain their own timing. */
internal object MowerTap {
    private const val HOLD_MS = 120L
    private const val RELEASE_MS = 120L

    fun perform(down: () -> Unit, up: () -> Unit, cancel: () -> Unit,
                sleep: (Long) -> Unit = { Thread.sleep(it) }) {
        try {
            down()
            sleep(HOLD_MS)
            up()
        } finally {
            cancel()
        }
        // Keep an observable release between consecutive taps, even with interval=0.
        sleep(RELEASE_MS)
    }
}
