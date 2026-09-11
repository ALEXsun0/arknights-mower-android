package com.aliothmoon.maameow.mower

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnlockPreparationTest {
    @Test fun recordingCanConnectWithoutStartingMower() = runBlocking {
        val result = prepareUnlockBackend(false, false, false,
            stopTasks = { error("Python must not be required") }, connect = { "backend" })
        assertEquals("backend", result)
    }
    @Test fun runningTasksStopBeforeRecordingBackendIsUsed() = runBlocking {
        val calls = mutableListOf<String>()
        prepareUnlockBackend(true, true, false,
            stopTasks = { calls += "stop" }, connect = { calls += "connect" })
        assertEquals(listOf("stop", "connect"), calls)
    }
    @Test fun transitionsDoNotStartRecordingOrStopUnreadyPython() {
        for ((active, ready, stopping) in listOf(Triple(true, false, false), Triple(true, true, true))) {
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    prepareUnlockBackend(active, ready, stopping,
                        stopTasks = { error("must not stop") }, connect = { fail("must not connect") })
                }
            }
        }
    }
    @Test fun taskStopFailurePreventsRecording() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                prepareUnlockBackend(true, true, false,
                    stopTasks = { error("stop failed") }, connect = { fail("must not connect") })
            }
        }
    }
}
