package com.aliothmoon.maameow.mower

/** Main-thread refresh loop. A hidden activity must never keep posting window updates. */
internal class VisibleUiRefresh(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val refresh: () -> Unit,
    private val intervalMs: Long = 1000,
) {
    private var current: Runnable? = null

    fun start() {
        if (current != null) return
        val callback = object : Runnable {
            override fun run() {
                if (current !== this) return
                refresh()
                if (current === this) schedule(this, intervalMs)
            }
        }
        current = callback
        schedule(callback, 0)
    }

    fun stop() {
        val callback = current ?: return
        current = null
        cancel(callback)
    }
}
