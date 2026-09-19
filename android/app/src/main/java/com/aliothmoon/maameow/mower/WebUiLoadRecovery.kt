package com.aliothmoon.maameow.mower

/**
 * State machine for loading the local WebUI.
 *
 * A URL is only considered loaded after WebView reports a successful main-frame
 * completion. Failed requests remain retryable even when the service URL itself
 * has not changed.
 */
internal class WebUiLoadRecovery(
    private val retryDelaysMs: List<Long> = listOf(1_000L, 2_000L, 5_000L),
    private val loadTimeoutMs: Long = 15_000L,
) {
    sealed interface Command {
        data object None : Command
        data object Blank : Command
        data object Loaded : Command
        data class Load(val url: String, val requestId: Long, val timeoutMs: Long) : Command
        data class RetryAfter(val requestId: Long, val delayMs: Long, val reason: String) : Command
        data class ManualRetry(val reason: String) : Command
    }

    private var targetUrl: String? = null
    private var loadedUrl: String? = null
    private var activeRequestId: Long? = null
    private var pendingRetryId: Long? = null
    private var nextRequestId = 0L
    private var retryCount = 0
    private var waitingForManualRetry = false

    fun updateTarget(url: String?): Command {
        if (url == targetUrl) {
            return if (url != null && loadedUrl != url && activeRequestId == null &&
                pendingRetryId == null && !waitingForManualRetry
            ) beginLoad(url) else Command.None
        }
        targetUrl = url
        loadedUrl = null
        activeRequestId = null
        pendingRetryId = null
        retryCount = 0
        waitingForManualRetry = false
        nextRequestId++ // invalidate callbacks belonging to the previous target
        return if (url == null) Command.Blank else beginLoad(url)
    }

    fun pageFinished(url: String?): Command {
        if (url == null || url != targetUrl || activeRequestId == null) return Command.None
        loadedUrl = url
        activeRequestId = null
        pendingRetryId = null
        retryCount = 0
        waitingForManualRetry = false
        return Command.Loaded
    }

    fun mainFrameFailed(url: String?, reason: String): Command {
        if (url != null && url != targetUrl) return Command.None
        if (activeRequestId == null) return Command.None
        return fail(reason)
    }

    fun timedOut(requestId: Long): Command {
        if (activeRequestId != requestId) return Command.None
        return fail("加载超时")
    }

    fun rendererGone(reason: String): Command {
        if (targetUrl == null) return Command.None
        loadedUrl = null
        activeRequestId = nextRequestId
        return fail(reason)
    }

    fun runRetry(requestId: Long): Command {
        if (pendingRetryId != requestId) return Command.None
        pendingRetryId = null
        val url = targetUrl ?: return Command.None
        return beginLoad(url)
    }

    fun manualRetry(): Command {
        val url = targetUrl ?: return Command.None
        retryCount = 0
        waitingForManualRetry = false
        pendingRetryId = null
        activeRequestId = null
        return beginLoad(url)
    }

    private fun beginLoad(url: String): Command.Load {
        val requestId = ++nextRequestId
        activeRequestId = requestId
        return Command.Load(url, requestId, loadTimeoutMs)
    }

    private fun fail(reason: String): Command {
        val failedRequest = activeRequestId ?: return Command.None
        activeRequestId = null
        loadedUrl = null
        if (retryCount < retryDelaysMs.size) {
            val delay = retryDelaysMs[retryCount++]
            pendingRetryId = failedRequest
            return Command.RetryAfter(failedRequest, delay, reason)
        }
        pendingRetryId = null
        waitingForManualRetry = true
        return Command.ManualRetry(reason)
    }
}
