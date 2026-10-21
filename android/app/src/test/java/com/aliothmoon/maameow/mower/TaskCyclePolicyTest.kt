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
    @Test fun startupSnapshotSurvivesInitializationAndDelayedPolling() {
        val p = TaskCyclePolicy()
        p.beginRun(true)
        repeat(3) { p.update("stopped", false, false, true) }
        p.beforeTaskAction(false)
        p.update("working", false, false, true)
        assertFalse(p.update("sleeping", false, false, true).maySleep)
    }
    @Test fun autoWakeDoesNotReplaceOriginallyOffScreen() {
        val p = TaskCyclePolicy()
        p.beginRun(false)
        p.beforeTaskAction(true)
        p.update("working", true, false, true)
        assertTrue(p.update("sleeping", true, false, true).maySleep)
    }
    @Test fun successiveCyclesCaptureTheirOwnScreenBeforeGameActions() {
        val p = TaskCyclePolicy()
        p.beginRun(false)
        p.update("working", true, false, true)
        assertTrue(p.update("sleeping", true, false, true).maySleep)
        p.beforeTaskAction(true)
        p.beforeTaskAction(false)
        p.update("sleeping", false, false, true)
        p.update("working", false, false, true)
        assertFalse(p.update("sleeping", false, false, true).maySleep)
        p.beforeTaskAction(false)
        p.update("working", true, false, true)
        assertTrue(p.update("sleeping", true, false, true).maySleep)
    }
    @Test fun stoppingAndRestartingDiscardPreviousSnapshot() {
        val p = TaskCyclePolicy()
        p.beginRun(false)
        p.endRun()
        p.beginRun(true)
        p.update("working", false, false, true)
        assertFalse(p.update("sleeping", false, false, true).maySleep)
    }
    @Test fun disablingPreservationStillAllowsSleep() {
        val p = TaskCyclePolicy()
        p.beginRun(true)
        p.update("working", true, false, false)
        assertTrue(p.update("sleeping", true, false, false).maySleep)
    }
}
