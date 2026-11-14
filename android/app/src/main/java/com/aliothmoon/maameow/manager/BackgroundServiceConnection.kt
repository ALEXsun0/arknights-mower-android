package com.aliothmoon.maameow.manager

import kotlinx.coroutines.withTimeoutOrNull

/** A connection deadline is a startup failure, not cancellation of the service owner. */
internal suspend fun <T : Any> awaitBackgroundService(
    timeoutMillis: Long = 20_000,
    connect: suspend () -> T,
): T = withTimeoutOrNull(timeoutMillis) { connect() }
    ?: error("连接 Shizuku 后台服务超时，请打开 Shizuku 后重试；若仍失败，请重新启动 Shizuku。")

/** Do not create a replacement while the previous native core can still run. */
internal suspend fun awaitBackgroundExit(
    timeoutMillis: Long = 6_000,
    alive: () -> Boolean,
) {
    check(withTimeoutOrNull(timeoutMillis) {
        while (alive()) kotlinx.coroutines.delay(50)
        true
    } == true) { "旧 MAA 后台进程未退出，未启动替代进程；请停止并重新启动服务" }
}
