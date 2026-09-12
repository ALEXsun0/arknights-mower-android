package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test

class GameRecoveryPolicyTest {
    @Test fun idleExitWaitsForNextActualTaskCapture() {
        assertFalse(GameRecoveryPolicy.eligible(true, true, false, false, false))
        assertTrue(GameRecoveryPolicy.eligible(true, true, false, false, true))
        assertTrue(GameRecoveryPolicy.eligible(true, true, false, true, false))
    }
    @Test fun disabledStoppedAndManualStatesNeverRecover() {
        for (capture in listOf(true, false)) {
            assertFalse(GameRecoveryPolicy.eligible(false, true, false, true, capture))
            assertFalse(GameRecoveryPolicy.eligible(true, false, false, true, capture))
            assertFalse(GameRecoveryPolicy.eligible(true, true, true, true, capture))
        }
    }
    @Test fun captureAndMonitorShareCooldown() {
        val policy = GameRecoveryPolicy()
        assertTrue(policy.acquire(0))
        assertFalse(policy.acquire(5000))
        assertFalse(policy.acquire(29999))
        assertTrue(policy.acquire(30000))
    }
    @Test fun stableFramesMustNotResetRollingBudget() {
        val policy = GameRecoveryPolicy()
        assertTrue(policy.acquire(0))
        assertTrue(policy.acquire(30000))
        assertTrue(policy.acquire(60000))
        assertFalse(policy.acquire(90000))
        assertFalse(policy.acquire(599999))
        assertTrue(policy.acquire(600000))
        assertFalse(policy.acquire(600001))
    }
}
