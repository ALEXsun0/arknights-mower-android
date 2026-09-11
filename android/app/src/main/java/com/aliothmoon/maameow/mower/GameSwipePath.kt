package com.aliothmoon.maameow.mower

/** Mower intentionally drags beyond an edge to scroll to the end of a list. */
internal class GameSwipePath(val points: List<Pair<Int, Int>>) {
    init {
        require(points.size in 2..64)
        val (x, y) = points.first()
        require(x in 0..1919 && y in 0..1079) { "滑动起点不在游戏画面内" }
    }

    fun at(segment: Int, step: Int, steps: Int): Pair<Int, Int> {
        require(steps > 0 && step in 0..steps)
        val (x, y) = points[segment]
        val (endX, endY) = points[segment + 1]
        // Clamp each sample, not just the endpoint: preserve speed, edge hold,
        // and the final no-inertia segment without injecting outside the display.
        fun coordinate(start: Int, end: Int, maximum: Long) =
            (start.toLong() + (end.toLong() - start) * step / steps).coerceIn(0, maximum).toInt()
        return coordinate(x, endX, 1919) to coordinate(y, endY, 1079)
    }

    val end get() = at(points.lastIndex - 1, 1, 1)
}
