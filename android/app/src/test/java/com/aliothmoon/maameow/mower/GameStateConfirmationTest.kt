package com.aliothmoon.maameow.mower

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateConfirmationTest {
    @Test fun launchCanSucceedAfterTheOldTwoSecondWindow() {
        var elapsed = 0L
        val confirmed = GameStateConfirmation.await(
            timeoutMs = GameStateConfirmation.LAUNCH_TIMEOUT_MS,
            now = { elapsed },
            sleep = { elapsed += it },
        ) { elapsed >= 3_000L }

        assertTrue(confirmed)
    }

    @Test fun finalProbeAtDeadlineCanConfirmCompletedOperation() {
        var elapsed = 0L
        val confirmed = GameStateConfirmation.await(
            timeoutMs = 500L,
            intervalMs = 250L,
            now = { elapsed },
            sleep = { elapsed += it },
        ) { elapsed >= 500L }

        assertTrue(confirmed)
    }

    @Test fun unchangedStateStillTimesOut() {
        var elapsed = 0L
        val confirmed = GameStateConfirmation.await(
            timeoutMs = 500L,
            intervalMs = 250L,
            now = { elapsed },
            sleep = { elapsed += it },
        ) { false }

        assertFalse(confirmed)
        assertTrue(elapsed >= 500L)
    }
}
