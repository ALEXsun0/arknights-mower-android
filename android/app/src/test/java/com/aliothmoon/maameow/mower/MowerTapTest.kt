package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test

class MowerTapTest {
    @Test fun consecutiveClicksHaveBothAHoldAndAReleaseWindow() {
        var now = 0L
        val events = mutableListOf<Pair<String, Long>>()
        repeat(3) {
            MowerTap.perform(
                { events += "down" to now }, { events += "up" to now },
                { events += "cancel" to now }, { now += it },
            )
        }
        val down = events.filter { it.first == "down" }.map { it.second }
        val up = events.filter { it.first == "up" }.map { it.second }
        assertEquals(3, down.size)
        for (i in down.indices) assertTrue(up[i] - down[i] >= 100)
        for (i in 1 until down.size) assertTrue(down[i] - up[i - 1] >= 100)
        assertTrue(now <= 1000) // No long press or multi-second blocking per click.
    }

    @Test fun interruptedHoldCancelsTouchWithoutReplaying() {
        val events = mutableListOf<String>()
        try {
            MowerTap.perform(
                { events += "down" }, { events += "up" }, { events += "cancel" },
                { throw InterruptedException("stopped") },
            )
            fail("Interruption must propagate")
        } catch (_: InterruptedException) {
            assertEquals(listOf("down", "cancel"), events)
        }
    }
}
