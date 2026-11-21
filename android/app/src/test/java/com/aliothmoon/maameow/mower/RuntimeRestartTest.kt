package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeRestartTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun restartsOnlyOnceAfterCleanup() {
        val restart = RuntimeRestart()
        var starts = 0
        assertTrue(restart.request())
        assertFalse(restart.request())
        assertEquals(0, starts)
        restart.complete(true) { starts++ }
        restart.complete(true) { starts++ }
        assertEquals(1, starts)
    }

    @Test fun userStopCancelsRestartDuringCleanup() {
        val restart = RuntimeRestart()
        restart.request()
        restart.cancel()
        restart.complete(true) { fail("User stop must prevent restart") }
    }

    @Test fun failedCleanupDoesNotRestartOrRetry() {
        val restart = RuntimeRestart()
        restart.request()
        restart.complete(false) { fail("Old runtime has not been released") }
        restart.complete(true) { fail("Failed restart must not be retried") }
        assertTrue(restart.request())
    }

    @Test fun acceptsOnlyTheMatchingOutstandingRequest() {
        val data = temporary.newFolder()
        val job = "a".repeat(32)
        val target = "b".repeat(64)
        val dir = File(RuntimeRestart.directory(data), job).apply { mkdirs() }
        val request = File(dir, "restart-request").apply { writeText(target) }
        val accepted = File(dir, "restart-accepted")
        assertTrue(runCatching { RuntimeRestart.acceptRequest(data, job, "c".repeat(64)) }.isFailure)
        assertTrue(request.exists())
        RuntimeRestart.acceptRequest(data, job, target)
        assertEquals(target, accepted.readText())
        assertFalse(request.exists())
        assertTrue(runCatching { RuntimeRestart.acceptRequest(data, job, target) }.isFailure)
    }

    @Test fun withdrawnRequestCannotTriggerALateRestart() {
        val data = temporary.newFolder()
        val job = "d".repeat(32)
        val target = "e".repeat(64)
        val dir = File(RuntimeRestart.directory(data), job).apply { mkdirs() }
        File(dir, "restart-request").apply { writeText(target); delete() }
        assertTrue(runCatching { RuntimeRestart.acceptRequest(data, job, target) }.isFailure)
        assertFalse(File(dir, "restart-accepted").exists())
    }

    @Test fun userStopPersistsCancellationForThePendingJobOnly() {
        val data = temporary.newFolder()
        val directory = RuntimeRestart.directory(data).apply { mkdirs() }
        assertEquals(File(data, "tmp/updates/4e21008231518eabb448/android-service"), directory)
        val journal = File(directory, "service-restart.json")
        journal.writeText("""{"id":"current-job","status":"running","resume_mower":true}""")
        RuntimeRestart.cancelResume(data)
        assertEquals("current-job", File(directory, "service-restart-cancelled").readText())
        assertTrue(journal.readText().contains("\"resume_mower\":true"))
        journal.writeText("""{"id":"completed-job","status":"succeeded"}""")
        RuntimeRestart.cancelResume(data)
        assertEquals("current-job", File(directory, "service-restart-cancelled").readText())
    }
}
