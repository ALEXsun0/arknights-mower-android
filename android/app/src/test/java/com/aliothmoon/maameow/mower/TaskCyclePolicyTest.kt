package com.aliothmoon.maameow.mower
import org.junit.Assert.*
import org.junit.Test
class TaskCyclePolicyTest {
    @Test fun coldIdleDoesNotTriggerCleanup() { assertFalse(TaskCyclePolicy().update("sleeping", false, false, true).completed) }
    @Test fun completionRestoresOriginallySleepingPhone() {
        val p = TaskCyclePolicy(); p.update("working", false, false, true)
        assertTrue(p.update("sleeping", true, false, true).maySleep)
        assertFalse(p.update("sleeping", true, false, true).completed)
    }
    @Test fun originallyLitScreenIsPreserved() {
        val p = TaskCyclePolicy(); p.update("working", true, false, true)
        val result = p.update("sleeping", true, false, true)
        assertTrue(result.completed); assertFalse(result.maySleep)
    }
    @Test fun manualStopNeverTriggersSleepOrGameClose() {
        val p = TaskCyclePolicy(); p.update("working", false, false, true)
        assertFalse(p.update("stopped", false, false, true).completed)
    }
    @Test fun manualInteractionSuppressesCompletionActions() {
        val p = TaskCyclePolicy(); p.update("working", false, false, true)
        assertFalse(p.update("sleeping", false, true, false).completed)
    }
}
