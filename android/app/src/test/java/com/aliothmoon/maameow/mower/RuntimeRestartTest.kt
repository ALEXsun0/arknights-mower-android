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
