package com.aliothmoon.maameow.mower

/** A shared budget for capture and monitoring, independent of wall-clock changes. */
class GameRecoveryPolicy(private val windowMs: Long = 600_000, private val cooldownMs: Long = 30_000,
                         private val limit: Int = 3) {
    companion object {
        fun eligible(enabled: Boolean, active: Boolean, manual: Boolean, expected: Boolean, captureDemand: Boolean) =
            enabled && active && !manual && (expected || captureDemand)
    }
    private val attempts = java.util.ArrayDeque<Long>()
    fun acquire(now: Long): Boolean {
        while (attempts.isNotEmpty() && now - attempts.first >= windowMs) attempts.removeFirst()
        if (attempts.size >= limit || (attempts.isNotEmpty() && now - attempts.last < cooldownMs)) return false
        attempts.addLast(now)
        return true
    }
}
