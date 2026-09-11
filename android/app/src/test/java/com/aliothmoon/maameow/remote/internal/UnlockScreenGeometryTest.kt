package com.aliothmoon.maameow.remote.internal

import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.EV_SYN
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.SYN_REPORT
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.EV_ABS
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.ABS_MT_TRACKING_ID
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.EV_KEY
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.BTN_TOUCH
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.ABS_X
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.ABS_MT_POSITION_X
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.ABS_Y
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.ABS_MT_POSITION_Y
import com.aliothmoon.maameow.remote.internal.TouchStreamParser.Companion.SYN_MT_REPORT
import org.junit.Assert.*
import org.junit.Test

class UnlockScreenGeometryTest {
    private val landscape = ScreenGeometry(2400, 1080, 1)
    private val portrait = ScreenGeometry(1080, 2400, 0)

    @Test
    fun recordingSamplesFirstRealContactForAllTouchProtocols() {
        for (protocol in listOf("A", "B", "single")) {
            var display = landscape
            val geometry = RecordingScreenGeometry()
            var samples = 0
            val parser = TouchStreamParser { samples++; geometry.onStrokeStart(display) }
            parser.onEvent(EV_SYN, SYN_REPORT, 0, 0)
            assertNull(geometry.screen)
            // Keyguard rotates after wake, before the user's first touch.
            display = portrait
            if (protocol == "B") parser.onEvent(EV_ABS, ABS_MT_TRACKING_ID, 1, 10)
            if (protocol == "single") parser.onEvent(EV_KEY, BTN_TOUCH, 1, 10)
            parser.onEvent(EV_ABS, if (protocol == "single") ABS_X else ABS_MT_POSITION_X, 200, 10)
            parser.onEvent(EV_ABS, if (protocol == "single") ABS_Y else ABS_MT_POSITION_Y, 300, 10)
            if (protocol == "A") parser.onEvent(EV_SYN, SYN_MT_REPORT, 0, 10)
            parser.onEvent(EV_SYN, SYN_REPORT, 0, 10)
            // Returning to the landscape app must not change recorded geometry.
            display = landscape
            parser.finish(100)
            assertEquals(1, samples)
            assertEquals(portrait, geometry.screen)
            assertFalse(geometry.changed)
            assertEquals(TouchPoint(200, 300, 10), parser.strokes.single().points.single())
        }
    }

    @Test
    fun recordingRejectsMixedDirectionsEvenWhenDirectionChangesBack() {
        val geometry = RecordingScreenGeometry()
        geometry.onStrokeStart(portrait)
        geometry.onStrokeStart(landscape)
        geometry.onStrokeStart(portrait)
        assertTrue(geometry.changed)
        assertEquals(portrait, geometry.screen)
    }

    @Test
    fun replayWaitsForDelayedLockScreenRotation() {
        var time = 0L
        val screen = awaitStableScreenGeometry(
            read = { if (time < 1_000L) landscape else portrait },
            now = { time }, sleep = { time += it },
        )
        assertEquals(portrait, screen)
        assertEquals(1_400L, time)
    }

    @Test
    fun replayRequiresStableDimensionsAsWellAsRotation() {
        var time = 0L
        val screen = awaitStableScreenGeometry(
            read = { if (time < 1_100L) portrait.copy(width = 1440, height = 3200) else portrait },
            now = { time }, sleep = { time += it },
        )
        assertEquals(portrait, screen)
        assertEquals(1_500L, time)
    }

    @Test
    fun replayGivesUpWhenGeometryNeverSettles() {
        var time = 0L
        val screen = awaitStableScreenGeometry(
            read = { if (time % 200L == 0L) landscape else portrait },
            now = { time }, sleep = { time += it },
        )
        assertNull(screen)
        assertEquals(3_000L, time)
    }
}
