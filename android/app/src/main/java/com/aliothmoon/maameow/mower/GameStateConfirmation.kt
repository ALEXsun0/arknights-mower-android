package com.aliothmoon.maameow.mower

import android.os.SystemClock

/** Confirm asynchronous ActivityManager transitions by their final state. */
internal object GameStateConfirmation {
    const val LAUNCH_TIMEOUT_MS = 10_000L
    const val EXIT_TIMEOUT_MS = 5_000L
    const val INTERVAL_MS = 250L

    fun await(
        timeoutMs: Long,
        intervalMs: Long = INTERVAL_MS,
        now: () -> Long = SystemClock::elapsedRealtime,
        sleep: (Long) -> Unit = Thread::sleep,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            if (condition()) return true
            sleep(intervalMs)
        }
        return condition()
    }
}
