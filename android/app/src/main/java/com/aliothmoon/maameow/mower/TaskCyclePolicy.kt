package com.aliothmoon.maameow.mower

/** Only a completed work cycle enters idle cleanup. User stops and cold starts do not. */
class TaskCyclePolicy {
    private var previous = "stopped"
    private var originallyOn = false
    private var beforeTaskScreenOn: Boolean? = null
    data class Result(val started: Boolean, val completed: Boolean, val maySleep: Boolean)
    @Synchronized fun beginRun(screenOn: Boolean) {
        previous = "stopped"
        beforeTaskScreenOn = screenOn
    }
    @Synchronized fun endRun() {
        previous = "stopped"
        beforeTaskScreenOn = null
    }
    @Synchronized fun beforeTaskAction(screenOn: Boolean) {
        if (previous != "working" && beforeTaskScreenOn == null) beforeTaskScreenOn = screenOn
    }
    @Synchronized fun update(state: String, screenOn: Boolean, manual: Boolean, preserveScreen: Boolean): Result {
        val started = state == "working" && previous != "working"
        if (started) {
            originallyOn = beforeTaskScreenOn ?: screenOn
            beforeTaskScreenOn = null
        }
        val completed = previous == "working" && state == "sleeping" && !manual
        if (previous == "working" && state != "working") beforeTaskScreenOn = null
        previous = state
        return Result(started, completed, completed && (!preserveScreen || !originallyOn))
    }
}
