package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test

class MowerSwipeTest {
    private val path = GameSwipePath(listOf(600 to 500, 100 to 500))

    @Test fun capturesBeforeReleaseAndAccountsForCaptureTimeInHold() {
        val events = mutableListOf<String>()
        var time = 0L
        val frame = MowerSwipe.perform(
            path, listOf(16), 400,
            down = { _, _ -> events += "down" }, move = { _, _ -> events += "move" },
            up = { _, _ -> events += "up" }, cancel = { events += "cancel" },
            capture = { events += "capture"; time += 150; "held frame" },
            sleep = { events += "wait:$it"; time += it }, clock = { time },
        )
        assertEquals("held frame", frame)
        assertEquals(listOf("down", "wait:16", "move", "wait:100", "capture", "wait:150", "up", "cancel"), events)
        assertEquals(416L, time)
    }

    @Test fun captureFailureCancelsTouchWithoutReplayingOrReleasingNormally() {
        val events = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            MowerSwipe.perform(
                path, listOf(16), 400,
                down = { _, _ -> events += "down" }, move = { _, _ -> events += "move" },
                up = { _, _ -> events += "up" }, cancel = { events += "cancel" },
                capture = { events += "capture"; error("capture failed") }, sleep = {},
            )
        }
        assertEquals(listOf("down", "move", "capture", "cancel"), events)
    }

    @Test fun ordinarySwipeKeepsItsOriginalHoldAndReturnValue() {
        val events = mutableListOf<String>()
        val frame = MowerSwipe.perform(
            path, listOf(16), 400,
            down = { _, _ -> events += "down" }, move = { _, _ -> events += "move" },
            up = { _, _ -> events += "up" }, cancel = { events += "cancel" },
            sleep = { events += "wait:$it" },
        )
        assertNull(frame)
        assertEquals(listOf("down", "wait:16", "move", "wait:400", "up", "cancel"), events)
    }

    @Test fun failedTouchDownStillCancelsThePointer() {
        var cancelled = false
        assertThrows(IllegalStateException::class.java) {
            MowerSwipe.perform(
                path, listOf(16), 400, down = { _, _ -> error("input failed") },
                move = { _, _ -> fail() }, up = { _, _ -> fail() },
                cancel = { cancelled = true }, sleep = {},
            )
        }
        assertTrue(cancelled)
    }
}
