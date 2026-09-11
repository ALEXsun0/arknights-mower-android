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
}
