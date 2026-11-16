package com.aliothmoon.maameow.remote.internal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScreenTimeoutOverrideTest {
    @get:Rule val folder = TemporaryFolder()
    private val values = mutableMapOf<Int, String?>()
    private var user = 0
    private var rejectWrite = false
    private val journal: File get() = File(folder.root, "screen-timeout")
    private fun timeout() = ScreenTimeoutOverride(journal, { user }, { values[it] }, { id, value ->
        check(journal.exists())
        if (!rejectWrite) values[id] = value
    })

    @Test fun repeatedEntryPreservesOriginalAndRestartRestoresIt() {
        values[0] = "30000"
        val timeout = timeout()
        timeout.acquire()
        timeout.acquire()
        assertEquals(ScreenTimeoutOverride.NEVER, values[0])
        assertEquals(listOf("0", "30000"), journal.readLines())
        timeout().restore()
        assertEquals("30000", values[0])
        assertFalse(journal.exists())
    }

    @Test fun missingSettingAndExistingMaximumArePreserved() {
        for (original in listOf(null, ScreenTimeoutOverride.NEVER)) {
            values[0] = original
            val timeout = timeout()
            timeout.acquire()
            timeout.restore()
            assertEquals(original, values[0])
            assertFalse(journal.exists())
        }
    }

    @Test fun externalUserChoiceIsNotOverwrittenOnExit() {
        values[0] = "30000"
        val timeout = timeout()
        timeout.acquire()
        values[0] = "60000"
        timeout.restore()
        assertEquals("60000", values[0])
        assertFalse(journal.exists())
    }

    @Test fun reentryRetainsANewerExternalTimeoutChoice() {
        values[0] = "30000"
        val timeout = timeout()
        timeout.acquire()
        values[0] = "60000"
        timeout.acquire()
        timeout.restore()
        assertEquals("60000", values[0])
    }

    @Test fun unreadableCurrentSettingRetainsRecoveryRecord() {
        values[0] = "30000"
        val timeout = timeout()
        timeout.acquire()
        values[0] = "Permission denied"
        assertTrue(runCatching { timeout.restore() }.isFailure)
        assertTrue(journal.exists())
        assertEquals(listOf("0", "30000"), journal.readLines())
    }

    @Test fun userSwitchRestoresTheOwningUserBeforeOverridingTheNext() {
        values[0] = "30000"
        values[10] = "60000"
        val timeout = timeout()
        timeout.acquire()
        user = 10
        timeout.acquire()
        assertEquals("30000", values[0])
        assertEquals(ScreenTimeoutOverride.NEVER, values[10])
        user = 0
        timeout.restore()
        assertEquals("60000", values[10])
    }

    @Test fun rejectedRestoreRetainsTheJournalForExplicitRetry() {
        values[0] = "30000"
        val timeout = timeout()
        timeout.acquire()
        rejectWrite = true
        assertTrue(runCatching { timeout.restore() }.isFailure)
        assertTrue(journal.exists())
        rejectWrite = false
        timeout().restore()
        assertEquals("30000", values[0])
    }

    @Test fun corruptedJournalBlocksNewOverrides() {
        values[0] = "30000"
        journal.writeText("invalid")
        assertTrue(runCatching { timeout().acquire() }.isFailure)
        assertEquals("30000", values[0])
        assertEquals("invalid", journal.readText())
    }

    @Test fun unavailableSettingsCannotReplaceTheSavedOriginal() {
        values[0] = "Permission denied"
        assertTrue(runCatching { timeout().acquire() }.isFailure)
        assertEquals("Permission denied", values[0])
        assertFalse(journal.exists())
    }

    @Test fun powerSessionDisablesTimeoutBeforePanelOffAndRestoresItOnExit() {
        values[0] = "30000"
        val timeout = timeout()
        val panels = mutableListOf<Boolean>()
        val session = FakeScreenPowerSession({ true }, { error("already awake") },
            { if (it) timeout.acquire() else timeout.restore() },
            { on ->
                assertEquals(ScreenTimeoutOverride.NEVER, values[0])
                panels.add(on)
                true
            })
        assertTrue(session.setScreenOn(false))
        assertTrue(session.setScreenOn(true))
        assertEquals(listOf(false, true), panels)
        assertEquals("30000", values[0])
        assertFalse(journal.exists())
    }

    @Test fun rejectedTimeoutOverrideDoesNotTurnOffThePanel() {
        values[0] = "30000"
        rejectWrite = true
        val timeout = timeout()
        var panelChanged = false
        val session = FakeScreenPowerSession({ true }, { true },
            { if (it) timeout.acquire() else timeout.restore() },
            { panelChanged = true; true })
        assertTrue(runCatching { session.setScreenOn(false) }.isFailure)
        assertFalse(panelChanged)
        assertEquals("30000", values[0])
        assertFalse(journal.exists())
    }
}
