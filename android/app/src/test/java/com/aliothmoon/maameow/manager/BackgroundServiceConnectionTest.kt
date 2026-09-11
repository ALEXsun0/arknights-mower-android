package com.aliothmoon.maameow.manager

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundServiceConnectionTest {
    @Test fun connectionCompletes() = runBlocking {
        assertEquals("connected", awaitBackgroundService { "connected" })
    }

    @Test fun timeoutIsReportedAndRetryCanConnect() = runBlocking {
        val failure = runCatching {
            awaitBackgroundService(10) { awaitCancellation() }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Shizuku"))
        assertTrue(currentCoroutineContext().isActive)
        assertEquals("retried", awaitBackgroundService { "retried" })
    }

    @Test fun ownerCancellationIsNotConvertedToStartupFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var caught: Throwable? = null
        val job = launch {
            try {
                awaitBackgroundService {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (failure: Throwable) { caught = failure }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(caught is CancellationException)
    }

    @Test fun bindingFailureIsPreserved() = runBlocking {
        val failure = IllegalStateException("binding failed")
        val caught = runCatching {
            awaitBackgroundService { throw failure }
        }.exceptionOrNull()
        assertTrue(caught is IllegalStateException)
        assertEquals(failure.message, caught?.message)
    }
}
