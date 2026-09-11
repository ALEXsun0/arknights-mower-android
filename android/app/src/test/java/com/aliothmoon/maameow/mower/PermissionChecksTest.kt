package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test

class PermissionChecksTest {
    private val ready = PermissionSnapshot(false, true, true, false, false, true, true, true)
    @Test fun optionalFeaturesDoNotBlockStartup() {
        val issues = ready.copy(notifications = false, exactAlarm = false, batteryUnrestricted = false).issues
        assertEquals(3, issues.size)
        assertTrue(issues.none { it.required })
        assertTrue(ready.issues.isEmpty())
    }
    @Test fun overlayIsOnlyNeededForEnabledScreenSaver() {
        assertTrue(ready.issues.isEmpty())
        assertEquals(PermissionAction.OVERLAY, ready.copy(screenSaver = true).issues.single().action)
    }
    @Test fun missingBackendAndMissingGrantHaveDifferentActions() {
        assertEquals(PermissionAction.START_BACKEND, ready.copy(backendRunning = false).issues.single().action)
        val grant = ready.copy(backendGranted = false).issues.single()
        assertTrue(grant.required)
        assertEquals(PermissionAction.AUTHORIZE_BACKEND, grant.action)
    }
    @Test fun rootBackendDoesNotRequireShizukuOrClaimPriorRootGrant() {
        val root = ready.copy(root = true, backendRunning = false, backendGranted = false)
        assertTrue(root.issues.isEmpty())
        assertTrue(root.summary.contains("启动时验证"))
    }
    @Test fun permissionBoardShowsGrantedAndOptionalUnrequestedPermissions() {
        val entries = ready.entries
        assertEquals(5, entries.size)
        assertTrue(entries.first().granted)
        assertFalse(entries.single { it.action == PermissionAction.OVERLAY }.granted)
        assertTrue(entries.single { it.action == PermissionAction.OVERLAY }.state.contains("启用屏保时需要"))
        assertTrue(ready.copy(overlay = true).entries.single { it.action == PermissionAction.OVERLAY }.granted)
    }

    @Test fun liveNotificationsOnlyAppearOnSupportedSystems() {
        assertTrue(ready.entries.none { it.action == PermissionAction.PROMOTED_NOTIFICATIONS })
        val entry = ready.copy(promotedNotifications = false).entries.last()
        assertEquals(PermissionAction.PROMOTED_NOTIFICATIONS, entry.action)
        assertFalse(entry.granted)
    }

}
