package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test

class WebUiLoadRecoveryTest {
    private val url = "http://127.0.0.1:1234/"

    @Test fun failedInitialRequestRetriesEvenWhenServiceUrlDoesNotChange() {
        val state = WebUiLoadRecovery(retryDelaysMs = listOf(100L), loadTimeoutMs = 500L)
        val first = state.updateTarget(url) as WebUiLoadRecovery.Command.Load
        val retry = state.mainFrameFailed(url, "connection refused") as WebUiLoadRecovery.Command.RetryAfter
        assertEquals(first.requestId, retry.requestId)
        assertEquals(100L, retry.delayMs)
        assertSame(WebUiLoadRecovery.Command.None, state.updateTarget(url))

        val second = state.runRetry(retry.requestId) as WebUiLoadRecovery.Command.Load
        assertTrue(second.requestId > first.requestId)
        assertEquals(url, second.url)
        assertSame(WebUiLoadRecovery.Command.Loaded, state.pageFinished(url))
        assertSame(WebUiLoadRecovery.Command.None, state.updateTarget(url))
    }

    @Test fun staleTimeoutAndRetryCallbacksCannotReplaceSuccessfulPage() {
        val state = WebUiLoadRecovery(retryDelaysMs = listOf(100L))
        val first = state.updateTarget(url) as WebUiLoadRecovery.Command.Load
        val retry = state.timedOut(first.requestId) as WebUiLoadRecovery.Command.RetryAfter
        val second = state.runRetry(retry.requestId) as WebUiLoadRecovery.Command.Load
        assertSame(WebUiLoadRecovery.Command.Loaded, state.pageFinished(url))
        assertSame(WebUiLoadRecovery.Command.None, state.timedOut(second.requestId))
        assertSame(WebUiLoadRecovery.Command.None, state.runRetry(first.requestId))
    }

    @Test fun repeatedFailuresExposeManualRetryAndManualActionRestartsBudget() {
        val state = WebUiLoadRecovery(retryDelaysMs = listOf(10L))
        val first = state.updateTarget(url) as WebUiLoadRecovery.Command.Load
        val scheduled = state.mainFrameFailed(url, "first") as WebUiLoadRecovery.Command.RetryAfter
        assertEquals(first.requestId, scheduled.requestId)
        state.runRetry(scheduled.requestId) as WebUiLoadRecovery.Command.Load
        val manual = state.mainFrameFailed(url, "second") as WebUiLoadRecovery.Command.ManualRetry
        assertEquals("second", manual.reason)
        assertSame(WebUiLoadRecovery.Command.None, state.updateTarget(url))
        assertEquals(url, (state.manualRetry() as WebUiLoadRecovery.Command.Load).url)
    }

    @Test fun targetRemovalInvalidatesPendingRecoveryAndLoadsBlankPage() {
        val state = WebUiLoadRecovery(retryDelaysMs = listOf(10L))
        state.updateTarget(url) as WebUiLoadRecovery.Command.Load
        val retry = state.mainFrameFailed(url, "offline") as WebUiLoadRecovery.Command.RetryAfter
        assertSame(WebUiLoadRecovery.Command.Blank, state.updateTarget(null))
        assertSame(WebUiLoadRecovery.Command.None, state.runRetry(retry.requestId))
        assertSame(WebUiLoadRecovery.Command.None, state.pageFinished(url))
    }

    @Test fun rendererLossRecoversEvenAfterPageWasLoaded() {
        val state = WebUiLoadRecovery(retryDelaysMs = listOf(25L))
        state.updateTarget(url)
        state.pageFinished(url)
        val retry = state.rendererGone("renderer exited") as WebUiLoadRecovery.Command.RetryAfter
        assertEquals(25L, retry.delayMs)
        assertEquals(url, (state.runRetry(retry.requestId) as WebUiLoadRecovery.Command.Load).url)
    }
}
