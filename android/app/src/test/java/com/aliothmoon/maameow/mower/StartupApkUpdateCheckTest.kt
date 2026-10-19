package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StartupApkUpdateCheckTest {
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stopChecks() { scopes.forEach { it.cancel() } }

    private inner class Check {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        val result = CompletableDeferred<JSONObject>()
        var requests = 0
        val shown = mutableListOf<String>()
        val check = StartupApkUpdateCheck(scope, { "a".repeat(64) }, 30,
            showUpdate = { shown += it },
            fetch = { requests++; result.await() })
    }
    private fun release(hash: String = "b".repeat(64), code: Int = 31): JSONObject = JSONObject().put("apk",
        JSONObject().put("version", "0.3.1").put("host_sha256", hash).put("host_version_code", code))

    @Test fun waitsForVisibleReadyServiceWithoutStartingAnotherCheck() {
        val h = Check()
        h.check.refresh(foreground = false, serviceReady = true)
        h.check.refresh(foreground = true, serviceReady = false)
        assertEquals(0, h.requests)
        h.check.refresh(foreground = true, serviceReady = true)
        repeat(20) { h.check.refresh(foreground = true, serviceReady = true) }
        assertEquals(1, h.requests)
        assertTrue(h.shown.isEmpty())
        h.result.complete(release())
        assertEquals(listOf("0.3.1"), h.shown)
        repeat(5) {
            h.check.refresh(foreground = false, serviceReady = false)
            h.check.refresh(foreground = true, serviceReady = true)
        }
        assertEquals(1, h.requests)
        assertEquals(1, h.shown.size)
    }

    @Test fun bundledComponentReleaseDoesNotPromptEvenWithANewerApkVersion() {
        val h = Check()
        h.result.complete(release(hash = "a".repeat(64), code = 50))
        h.check.refresh(foreground = true, serviceReady = true)
        assertEquals(1, h.requests)
        assertTrue(h.shown.isEmpty())
    }

    @Test fun differentHostWithOlderOrMissingVersionDoesNotPrompt() {
        for (metadata in listOf(release(code = 29), release(code = 30), release(hash = ""), JSONObject())) {
            val h = Check()
            h.result.complete(metadata)
            h.check.refresh(foreground = true, serviceReady = true)
            assertTrue(h.shown.isEmpty())
        }
    }

    @Test fun pendingUpdateWaitsForForegroundAndReadyServiceThenPromptsOnce() {
        val h = Check()
        h.check.refresh(foreground = true, serviceReady = true)
        h.check.refresh(foreground = false, serviceReady = true)
        h.result.complete(release())
        assertTrue(h.shown.isEmpty())
        h.check.refresh(foreground = true, serviceReady = false)
        assertTrue(h.shown.isEmpty())
        h.check.refresh(foreground = true, serviceReady = true)
        h.check.refresh(foreground = true, serviceReady = true)
        assertEquals(listOf("0.3.1"), h.shown)
        assertEquals(1, h.requests)
    }

    @Test fun failedCheckStaysSilentWithoutForegroundOrServiceRestartRetries() {
        val h = Check()
        h.check.refresh(foreground = true, serviceReady = true)
        h.result.completeExceptionally(IllegalStateException("offline"))
        repeat(20) {
            h.check.refresh(foreground = false, serviceReady = false)
            h.check.refresh(foreground = true, serviceReady = true)
        }
        assertEquals(1, h.requests)
        assertTrue(h.shown.isEmpty())
    }

    @Test fun destroyedActivityScopeCannotPromptFromALateResponse() {
        val h = Check()
        h.check.refresh(foreground = true, serviceReady = true)
        h.scope.cancel()
        h.result.complete(release())
        h.check.refresh(foreground = true, serviceReady = true)
        assertTrue(h.shown.isEmpty())
    }

    @Test fun promptOnlyOpensUpdateFlowOnUserConfirmation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var opened = 0
        activity.showMowerApkUpdatePrompt("0.3.1") { opened++ }
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.contains("0.3.1"))
        assertEquals(0, opened)
        assertNull(shadowOf(activity).nextStartedActivity)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, opened)
    }

    @Test fun laterDismissesWithoutOpeningUpdateOrInstaller() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var opened = false
        activity.showMowerApkUpdatePrompt("0.3.1") { opened = true }
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(opened)
        assertFalse(dialog.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
