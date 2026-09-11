package com.aliothmoon.maameow.mower

/** Only a completed work cycle enters idle cleanup. User stops and cold starts do not. */
class TaskCyclePolicy {
    private var previous = "stopped"
    private var originallyOn = false
    data class Result(val started: Boolean, val completed: Boolean, val maySleep: Boolean)
    fun update(state: String, screenOn: Boolean, manual: Boolean, preserveScreen: Boolean): Result {
        val started = state == "working" && previous != "working"
        if (started) originallyOn = screenOn
        val completed = previous == "working" && state == "sleeping" && !manual
        previous = state
        return Result(started, completed, completed && (!preserveScreen || !originallyOn))
    }
}
