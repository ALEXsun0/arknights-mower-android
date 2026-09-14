package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test

class GameAudioSessionTest {
    @Test fun restoresBothGamesAfterOwnerDisappearsAndRepeatedMute() {
        val modes = mutableMapOf("official" to "allow", "bilibili" to "default")
        val session = GameAudioSession({ modes.getValue(it) }, { pkg, value -> modes[pkg] = value })
        repeat(3) { session.set("official", "ignore") }
        session.set("bilibili", "ignore")
        session.restoreAll()
        assertEquals(mapOf("official" to "allow", "bilibili" to "default"), modes)
        session.restoreAll()
        assertEquals("allow", modes["official"])
    }

    @Test fun automaticCleanupPreservesPreexistingAndExternallyChangedModes() {
        val modes = mutableMapOf("existing" to "ignore", "changed" to "allow")
        val session = GameAudioSession({ modes.getValue(it) }, { pkg, value -> modes[pkg] = value })
        session.set("existing", "ignore")
        session.set("changed", "ignore")
        modes["changed"] = "deny"
        session.restoreAll()
        assertEquals(mapOf("existing" to "ignore", "changed" to "deny"), modes)
    }

    @Test fun failedRestoreDoesNotLoseOriginalOrSkipOtherGame() {
        val modes = mutableMapOf("first" to "allow", "second" to "foreground")
        var fail = false
        val session = GameAudioSession({ modes.getValue(it) }, { pkg, value ->
            if (fail && pkg == "first") error("appops unavailable")
            modes[pkg] = value
        })
        modes.keys.toList().forEach { session.set(it, "ignore") }
        fail = true
        assertThrows(IllegalStateException::class.java) { session.restoreAll() }
        assertEquals("foreground", modes["second"])
        fail = false
        session.restoreAll()
        assertEquals("allow", modes["first"])
    }

    @Test fun partialMuteFailureCanStillRestore() {
        var mode = "allow"
        var fail = true
        val session = GameAudioSession({ mode }, { _, value ->
            mode = value
            if (fail) error("verification failed")
        })
        assertThrows(IllegalStateException::class.java) { session.set("game", "ignore") }
        fail = false
        session.restoreAll()
        assertEquals("allow", mode)
    }

    @Test fun explicitRestoreEndsRemoteOwnership() {
        var mode = "allow"
        val session = GameAudioSession({ mode }, { _, value -> mode = value })
        session.set("game", "ignore")
        session.set("game", "default")
        mode = "ignore" // 用户随后重新设置；退出不能再用旧记录覆盖。
        session.restoreAll()
        assertEquals("ignore", mode)
    }

    @Test fun rejectedRestoreRetainsRecoveryRecord() {
        var mode = "allow"
        var reject = false
        val session = GameAudioSession({ mode }, { _, value -> if (!reject) mode = value })
        session.set("game", "ignore")
        reject = true
        assertThrows(IllegalStateException::class.java) { session.restoreAll() }
        reject = false
        session.restoreAll()
        assertEquals("allow", mode)
    }

    @Test fun parsesUidAndPackageIndependently() {
        val modes = GameAudioModes.parse("Uid mode: PLAY_AUDIO: ignore\nPLAY_AUDIO: allow; time=+3m ago; rejectTime=+1s ago")
        assertEquals("allow", modes.packageMode)
        assertEquals("ignore", modes.uidMode)
        assertTrue(modes.blocked)
        assertEquals(GameAudioModes("default", "deny"), GameAudioModes.parse("Uid mode: PLAY_AUDIO: deny"))
        assertEquals(GameAudioModes("default", null), GameAudioModes.parse("No operations."))
        assertThrows(IllegalStateException::class.java) { GameAudioModes.parse("permission denied") }
    }

    @Test fun manualRepairWorksWithoutLedgerAndOnlyClearsBlockingAudioModes() {
        for (pkg in listOf("allow", "default", "ignore", "deny", "foreground")) {
            for (uid in listOf(null, "allow", "default", "ignore", "deny", "foreground")) {
                var modes = GameAudioModes(pkg, uid)
                val writes = mutableListOf<Boolean>()
                repairGameAudio({ modes }) { mode, isUid ->
                    assertEquals("default", mode)
                    writes.add(isUid)
                    modes = if (isUid) modes.copy(uidMode = mode) else modes.copy(packageMode = mode)
                }
                assertFalse(modes.blocked)
                assertEquals(uid in setOf("ignore", "deny"), true in writes)
                assertEquals(pkg in setOf("ignore", "deny"), false in writes)
            }
        }
    }

    @Test fun manualRepairNeverReportsSuccessIfSystemRejectedChange() {
        assertThrows(IllegalStateException::class.java) {
            repairGameAudio({ GameAudioModes("ignore", null) }) { _, _ -> }
        }
        assertThrows(IllegalStateException::class.java) {
            repairGameAudio({ GameAudioModes("allow", "deny") }) { _, _ -> }
        }
    }
}
