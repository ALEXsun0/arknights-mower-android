package com.aliothmoon.maameow.manager

import kotlinx.coroutines.withTimeoutOrNull

/** A connection deadline is a startup failure, not cancellation of the service owner. */
internal suspend fun <T : Any> awaitBackgroundService(
    timeoutMillis: Long = 20_000,
    connect: suspend () -> T,
): T = withTimeoutOrNull(timeoutMillis) { connect() }
    ?: error("连接 Shizuku 后台服务超时，请打开 Shizuku 后重试；若仍失败，请重新启动 Shizuku。")
