package com.aliothmoon.maameow.mower

/** Execute one swipe, optionally observing its held endpoint before releasing it. */
internal object MowerSwipe {
    fun perform(
        path: GameSwipePath,
        durations: List<Int>,
        upWait: Int,
        down: (Int, Int) -> Unit,
        move: (Int, Int) -> Unit,
        up: (Int, Int) -> Unit,
        cancel: () -> Unit,
        capture: (() -> String)? = null,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): String? {
        try {
            down(path.points.first().first, path.points.first().second)
            for (i in durations.indices) {
                val steps = maxOf(1, durations[i] / 16)
                for (n in 1..steps) {
                    sleep((durations[i] / steps).toLong())
                    val (x, y) = path.at(i, n, steps)
                    move(x, y)
                }
            }
            val frame = if (capture == null) {
                sleep(upWait.toLong())
                null
            } else {
                val started = clock()
                sleep(minOf(100L, upWait.toLong()))
                val observed = capture()
                val remaining = upWait - (clock() - started)
                if (remaining > 0) sleep(remaining)
                observed
            }
            val (x, y) = path.end
            up(x, y)
            return frame
        } finally {
            cancel()
        }
    }
}
