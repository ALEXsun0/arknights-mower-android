package com.aliothmoon.maameow.mower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPowerSessionTest {
    private class Fixture {
        val queued = ArrayDeque<() -> Unit>()
        val powers = mutableListOf<Boolean>()
        val failures = mutableListOf<String>()
        var restored = true
        var switchedOff = true
        val session = ScreenPowerSession(
            execute = { queued.addLast(it) },
            setPower = { on -> powers += on; if (on) restored else switchedOff },
            reportFailure = failures::add,
        )
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst()() }
    }

    @Test fun automaticChecksDoNotUndoManualScreenOff() {
        val f = Fixture()
        f.session.turnOff(manual = true)
        repeat(5) {
            assertFalse(f.session.restore(automatic = true))
            f.session.turnOff()
        }
        f.drain()
        assertEquals(listOf(false), f.powers)
        assertTrue(f.session.manuallyOff)
        f.session.restore()
        f.drain()
        assertEquals(listOf(false, true), f.powers)
        assertFalse(f.session.manuallyOff)
    }

    @Test fun restoreBeforePendingScreenOffPreventsLateBlackScreen() {
        val f = Fixture()
        f.session.turnOff(manual = true)
        var completed = false
        f.session.restore { completed = true }
        f.drain()
        assertTrue(f.powers.isEmpty())
        assertTrue(completed)
        assertFalse(f.session.manuallyOff)
    }

    @Test fun cleanupRestoresPowerBeforeRemovingOverlayAndDoesNotRepeat() {
        val f = Fixture()
        f.session.turnOff()
        f.drain()
        f.session.restore { assertEquals(listOf(false, true), f.powers) }
        f.session.restore()
        f.drain()
        assertEquals(listOf(false, true), f.powers)
    }

    @Test fun failedScreenOffCompensatesPossiblePartialSuccess() {
        val f = Fixture()
        f.switchedOff = false
        var failed = false
        f.session.turnOff(manual = true) { failed = true }
        f.drain()
        assertEquals(listOf(false, true), f.powers)
        assertTrue(failed)
        assertFalse(f.session.manuallyOff)
    }

    @Test fun failedRestorationRetainsPendingCompensation() {
        val f = Fixture()
        f.session.turnOff(manual = true)
        f.drain()
        f.restored = false
        f.session.restore()
        f.drain()
        assertEquals(1, f.failures.size)
        f.restored = true
        f.session.restore()
        f.drain()
        assertEquals(listOf(false, true, true), f.powers)
    }

    @Test fun idleChecksDoNotEnqueuePowerOperations() {
        val f = Fixture()
        repeat(100) { assertTrue(f.session.restore(automatic = true)) }
        assertTrue(f.queued.isEmpty())
        assertTrue(f.powers.isEmpty())
    }

    @Test fun manualRequestAfterAutomaticCleanupStillTurnsScreenOff() {
        val f = Fixture()
        f.session.turnOff()
        f.session.restore(automatic = true)
        f.session.turnOff(manual = true)
        f.drain()
        assertEquals(listOf(false), f.powers)
        assertTrue(f.session.manuallyOff)
        f.session.restore()
        f.drain()
        assertEquals(listOf(false, true), f.powers)
    }
}
