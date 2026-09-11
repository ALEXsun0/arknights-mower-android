package com.aliothmoon.maameow.remote.internal

/** Called under the parser lock; never sample the app orientation before a real contact. */
internal class RecordingScreenGeometry {
    var screen: ScreenGeometry? = null
        private set
    var changed = false
        private set

    fun onStrokeStart(current: ScreenGeometry) {
        if (screen == null) screen = current
        else if (screen != current) changed = true
    }
}

/** Give keyguard time to appear, then require a stable size and rotation within a bounded wait. */
internal fun awaitStableScreenGeometry(
    read: () -> ScreenGeometry,
    now: () -> Long,
    sleep: (Long) -> Unit,
): ScreenGeometry? {
    val deadline = now() + 3_000L
    sleep(800L)
    var candidate = read()
    var since = now()
    while (now() < deadline) {
        sleep(100L)
        val current = read()
        val at = now()
        if (at >= deadline) return null
        if (current != candidate) {
            candidate = current
            since = at
        } else if (at - since >= 400L) {
            return current
        }
    }
    return null
}
