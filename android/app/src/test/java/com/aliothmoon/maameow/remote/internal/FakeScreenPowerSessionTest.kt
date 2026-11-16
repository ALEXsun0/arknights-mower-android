package com.aliothmoon.maameow.remote.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeScreenPowerSessionTest {
    private class Device {
        var interactive = true
        var canWake = true
        var canTurnOff = true
        var canRestore = true
        var keptAwake = false
        val events = mutableListOf<String>()
        val session = FakeScreenPowerSession(
            isInteractive = { interactive },
            wake = { events.add("wake"); interactive = canWake; canWake },
            keepAwake = { keptAwake = it; events.add("keep:$it") },
            displayPower = { events.add("panel:$it"); if (it) canRestore else canTurnOff },
        )
    }

    @Test fun panelOffKeepsTheSystemInteractive() {
        val device = Device()
        assertTrue(device.session.setScreenOn(false))
        assertEquals(listOf("keep:true", "panel:false"), device.events)
        assertTrue(device.interactive)
        assertTrue(device.keptAwake)
    }

    @Test fun sleepingPhoneWakesBeforePanelPowerChanges() {
        val device = Device().apply { interactive = false }
        assertTrue(device.session.setScreenOn(false))
        assertEquals(listOf("wake", "keep:true", "panel:false"), device.events)
        assertTrue(device.interactive)
    }

    @Test fun failedWakeDoesNotSwitchOffThePanel() {
        val device = Device().apply { interactive = false; canWake = false }
        assertFalse(device.session.setScreenOn(false))
        assertEquals(listOf("wake"), device.events)
        assertFalse(device.keptAwake)
    }

    @Test fun restorationPrecedesReleasingActivity() {
        val device = Device()
        device.session.setScreenOn(false)
        device.events.clear()
        assertTrue(device.session.setScreenOn(true))
        assertEquals(listOf("panel:true", "keep:false"), device.events)
        assertFalse(device.keptAwake)
    }

    @Test fun failedRestoreRetainsActivityUntilExplicitRetrySucceeds() {
        val device = Device()
        device.session.setScreenOn(false)
        device.events.clear()
        device.canRestore = false
        assertFalse(device.session.setScreenOn(true))
        assertEquals(listOf("panel:true"), device.events)
        assertTrue(device.keptAwake)
        device.canRestore = true
        assertTrue(device.session.setScreenOn(true))
        assertFalse(device.keptAwake)
    }

    @Test fun partialPanelOffFailureRestoresAllPanels() {
        val device = Device().apply { canTurnOff = false }
        assertFalse(device.session.setScreenOn(false))
        assertEquals(listOf("keep:true", "panel:false", "panel:true", "keep:false"), device.events)
        assertFalse(device.keptAwake)
    }

    @Test fun failedPanelRollbackRetainsActivityForRestoration() {
        val device = Device().apply { canTurnOff = false; canRestore = false }
        assertFalse(device.session.setScreenOn(false))
        assertEquals(listOf("keep:true", "panel:false", "panel:true"), device.events)
        assertTrue(device.keptAwake)
    }
}
