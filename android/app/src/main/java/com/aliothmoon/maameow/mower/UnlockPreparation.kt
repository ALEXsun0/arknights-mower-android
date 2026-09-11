package com.aliothmoon.maameow.mower

/** Recording needs the privileged backend, not the Python scheduler. */
internal suspend fun <T> prepareUnlockBackend(
    active: Boolean,
    ready: Boolean,
    stopping: Boolean,
    stopTasks: () -> Unit,
    connect: suspend () -> T,
): T {
    check(!stopping && (!active || ready)) { "Mower 正在启动或停止，请等待服务就绪后再录制或测试" }
    if (ready) stopTasks()
    return connect()
}
