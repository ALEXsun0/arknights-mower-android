package com.aliothmoon.maameow.domain.models

import org.json.JSONArray
import org.json.JSONObject

/** Meow-compatible recording format, kept entirely in the native host. */
data class UnlockGesture(
    val version: Int = VERSION,
    val screenWidth: Int,
    val screenHeight: Int,
    val rotation: Int,
    val steps: List<UnlockStep>,
) {
    fun toJson(): JSONObject = JSONObject().put("version", version)
        .put("screenWidth", screenWidth).put("screenHeight", screenHeight).put("rotation", rotation)
        .put("steps", JSONArray().also { array -> steps.forEach { step ->
            val value = JSONObject().put("delayBeforeMs", step.delayBeforeMs)
            when (step) {
                is UnlockStep.Tap -> value.put("type", "tap").put("x", step.x).put("y", step.y)
                is UnlockStep.LongPress -> value.put("type", "long_press").put("x", step.x).put("y", step.y).put("holdMs", step.holdMs)
                is UnlockStep.Swipe -> value.put("type", "swipe").put("points", JSONArray().also { points ->
                    step.points.forEach { p -> points.put(JSONObject().put("x", p.x).put("y", p.y).put("tMs", p.tMs)) }
                })
            }
            array.put(value)
        } })

    companion object {
        const val VERSION = 1
        fun parseOrNull(json: String, onDrop: (String) -> Unit = {}): UnlockGesture? {
            if (json.isBlank()) return null
            return runCatching {
                require(json.length <= 1_000_000)
                val root = JSONObject(json)
                require(root.getInt("version") == VERSION)
                val width = root.getInt("screenWidth"); val height = root.getInt("screenHeight")
                val rotation = root.getInt("rotation")
                require(width in 1..16384 && height in 1..16384 && rotation in 0..3)
                val items = root.getJSONArray("steps")
                require(items.length() in 1..64)
                fun point(x: Int, y: Int) { require(x in 0 until width && y in 0 until height) }
                var duration = 0L
                val steps = (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    val delay = item.optInt("delayBeforeMs", 0)
                    require(delay in 0..1500)
                    duration += delay
                    when (item.getString("type")) {
                        "tap", "long_press" -> {
                            val x = item.getInt("x"); val y = item.getInt("y"); point(x, y)
                            if (item.getString("type") == "tap") {
                                duration += 60; UnlockStep.Tap(x, y, delay)
                            } else {
                                val hold = item.getInt("holdMs"); require(hold in 0..90_000)
                                duration += hold; UnlockStep.LongPress(x, y, hold, delay)
                            }
                        }
                        "swipe" -> {
                            val values = item.getJSONArray("points"); require(values.length() in 1..400)
                            var previous = 0
                            val points = (0 until values.length()).map { j ->
                                val p = values.getJSONObject(j)
                                val x = p.getInt("x"); val y = p.getInt("y"); point(x, y)
                                val t = p.getInt("tMs"); require(t in previous..90_000); previous = t
                                GesturePoint(x, y, t)
                            }
                            duration += points.last().tMs
                            UnlockStep.Swipe(points, delay)
                        }
                        else -> error("Unknown step")
                    }
                }
                require(duration <= 90_000)
                UnlockGesture(screenWidth = width, screenHeight = height, rotation = rotation, steps = steps)
            }.getOrElse { onDrop("Invalid unlock recording"); null }
        }
    }
}

data class GesturePoint(val x: Int, val y: Int, val tMs: Int)

sealed interface UnlockStep {
    val delayBeforeMs: Int
    data class Tap(val x: Int, val y: Int, override val delayBeforeMs: Int = 0) : UnlockStep
    data class LongPress(val x: Int, val y: Int, val holdMs: Int, override val delayBeforeMs: Int = 0) : UnlockStep
    data class Swipe(val points: List<GesturePoint>, override val delayBeforeMs: Int = 0) : UnlockStep
}
