package com.aliothmoon.maameow.remote.internal

import org.junit.Assert.*
import org.junit.Test

class DisplayKeepAliveTargetsTest {
    @Test fun closingPhysicalScreenSaverKeepsVirtualActivity() {
        val targets = DisplayKeepAliveTargets()
        targets.virtual(21)
        targets.physical(0)
        assertEquals(listOf(0, 21), targets.snapshot(33))
        targets.physical(-1)
        assertEquals(listOf(21), targets.snapshot(33))
    }
    @Test fun stoppingVirtualDisplayDoesNotLosePhysicalScreenRecovery() {
        val targets = DisplayKeepAliveTargets()
        targets.physical(0)
        targets.virtual(21)
        targets.virtual(-1)
        assertEquals(listOf(0), targets.snapshot(33))
        targets.clear()
        assertTrue(targets.snapshot(33).isEmpty())
    }
    @Test fun oldApiCannotKeepMainScreenAwakeForVirtualDisplay() {
        val targets = DisplayKeepAliveTargets()
        targets.virtual(21)
        for (sdk in 27..32) assertTrue(targets.snapshot(sdk).isEmpty())
        targets.physical(0)
        assertEquals(listOf(0), targets.snapshot(30))
    }
    @Test fun displayReplacementDropsOldTarget() {
        val targets = DisplayKeepAliveTargets()
        targets.virtual(21)
        targets.virtual(22)
        assertEquals(listOf(22), targets.snapshot(33))
    }
}
