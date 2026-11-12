package com.aliothmoon.maameow.remote.internal

import org.junit.Assert.*
import org.junit.Test

class BackgroundDisplaySessionTest {
    private class Backend : DisplayBackend {
        var id = -1
        var starts = 0
        var stops = 0
        var failure: RuntimeException? = null
        var stopFailure: RuntimeException? = null
        var size: List<Int>? = null
        override fun start(): Int { starts++; failure?.let { throw it }; id = 7; return id }
        override fun stop() { stops++; stopFailure?.let { throw it }; id = -1 }
        override fun setResolution(width: Int, height: Int, dpi: Int) { size = listOf(width, height, dpi) }
        override fun displayId() = id
        override fun flagTier() = 1
        override fun isValid() = id > 0
        override fun state() = if (id > 0) 2 else 0
    }

    @Test fun defaultAndOldAndroidNeverInitializeVdm() {
        for (sdk in listOf(27, 28, 33, 34, 35)) {
            val legacy = Backend()
            val session = BackgroundDisplaySession(sdk, legacy) { error("VDM must remain lazy") }
            assertEquals(7, session.start())
            assertEquals(1, legacy.starts)
            assertEquals(7, session.displayId())
            if (sdk < 34) assertFalse(session.setMode(BackgroundDisplay.VDM))
            assertEquals(BackgroundDisplay.LEGACY, session.mode)
            session.stop()
            assertFalse(session.isValid())
        }
    }

    @Test fun explicitVdmOwnsDisplayIdentityResolutionAndCleanup() {
        val legacy = Backend()
        val vdm = Backend()
        val session = BackgroundDisplaySession(34, legacy) { vdm }
        assertTrue(session.setMode(BackgroundDisplay.VDM))
        session.setResolution(1920, 1080, 320)
        assertEquals(listOf(1920, 1080, 320), vdm.size)
        assertEquals(7, session.start())
        assertEquals(0, legacy.starts)
        assertEquals(7, session.displayId())
        assertEquals(1, session.flagTier())
        assertEquals(2, session.state())
        assertTrue(session.isValid())
        assertFalse(session.setMode(BackgroundDisplay.LEGACY))
        assertEquals(7, session.displayId())
        session.stop()
        assertEquals(1, vdm.stops)
        assertTrue(session.setMode(BackgroundDisplay.LEGACY))
        session.start()
        assertEquals(1, legacy.starts)
    }

    @Test fun failedVdmDoesNotStartLegacyAndCanBeExplicitlyDisabled() {
        val legacy = Backend()
        val vdm = Backend().apply { failure = SecurityException("permission denied") }
        val session = BackgroundDisplaySession(35, legacy) { vdm }
        assertTrue(session.setMode(BackgroundDisplay.VDM))
        assertTrue(runCatching { session.start() }.exceptionOrNull() is SecurityException)
        assertEquals(0, legacy.starts)
        assertEquals(-1, session.displayId())
        assertTrue(session.setMode(BackgroundDisplay.LEGACY))
        assertEquals(1, vdm.stops)
        assertEquals(7, session.start())
    }

    @Test fun malformedModeOrNonStandardCanvasDoesNotReachBackend() {
        val legacy = Backend()
        val session = BackgroundDisplaySession(34, legacy) { error("not selected") }
        assertFalse(session.setMode(0))
        assertFalse(session.setMode(4))
        assertTrue(runCatching { session.setResolution(1280, 720, 320) }.isFailure)
        assertNull(legacy.size)
        assertEquals(BackgroundDisplay.LEGACY, session.mode)
    }

    @Test fun failedCleanupPreventsCreatingAnotherBackend() {
        val legacy = Backend().apply { stopFailure = IllegalStateException("release failed") }
        var vdmCreated = false
        val session = BackgroundDisplaySession(34, legacy) { vdmCreated = true; Backend() }
        assertTrue(runCatching { session.setMode(BackgroundDisplay.VDM) }.isFailure)
        assertFalse(vdmCreated)
        assertEquals(BackgroundDisplay.LEGACY, session.mode)
    }
}
