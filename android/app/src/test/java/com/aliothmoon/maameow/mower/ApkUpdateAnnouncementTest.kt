package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.aliothmoon.maameow.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ApkUpdateAnnouncementTest {
    private val controllers = mutableListOf<ApkUpdateAnnouncement>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<out Activity>>()
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("apk-announcement", 0)
    @Before fun clearReadState() { prefs.edit().clear().commit() }
    @After fun cleanup() {
        controllers.forEach { it.close() }
        activities.forEach { it.pause().stop().destroy() }
    }
    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().also { activities += it }.get()
    private fun announcement(code: Int = 30, notes: () -> String = { "## 宿主更新\n\n- 更新 **预览**，保留 `1920×1080`。" }) =
        ApkUpdateAnnouncement(activity(), code, "0.3.$code", notes).also { controllers += it }
    private fun confirm() {
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        else -> ""
    }

    @Test fun opensOfflineAndOnlyConfirmationPersistsReadVersion() {
        val announcement = announcement()
        assertTrue(announcement.showIfNeeded())
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val body = text(dialog.window!!.decorView)
        assertTrue(body.contains("APK 0.3.30 更新日志"))
        assertTrue(body.contains("宿主更新\n\n• 更新 预览，保留 1920×1080。"))
        assertFalse(body.contains("##"))
        assertEquals(0, prefs.getInt("read_version_code", 0))
        assertTrue(announcement.showIfNeeded())
        assertEquals(dialog, ShadowAlertDialog.getLatestAlertDialog())
        confirm()
        assertFalse(announcement.showIfNeeded())
        assertFalse(announcement().showIfNeeded())
        assertEquals(30, prefs.getInt("read_version_code", 0))
    }

    @Test fun nextApkPromptsButReinstallAndRollbackDoNot() {
        prefs.edit().putInt("read_version_code", 30).commit()
        assertFalse(announcement(30).showIfNeeded())
        assertFalse(announcement(29).showIfNeeded())
        assertTrue(announcement(31).showIfNeeded())
        confirm()
        assertEquals(31, prefs.getInt("read_version_code", 0))
    }

    @Test fun cancellingDoesNotLoseAnnouncementOrRepeatWithinSameHomeVisit() {
        val announcement = announcement()
        announcement.showIfNeeded()
        ShadowAlertDialog.getLatestAlertDialog().cancel()
        assertFalse(announcement.showIfNeeded())
        assertEquals(0, prefs.getInt("read_version_code", 0))
        assertTrue(announcement().showIfNeeded())
    }

    @Test fun activityDestructionBeforeConfirmationKeepsAnnouncementUnread() {
        val announcement = announcement()
        announcement.showIfNeeded()
        announcement.close()
        assertEquals(0, prefs.getInt("read_version_code", 0))
        assertTrue(announcement().showIfNeeded())
    }

    @Test fun manualReadingWorksAfterAcknowledgementAndDoesNotLowerReadVersion() {
        prefs.edit().putInt("read_version_code", 31).commit()
        val announcement = announcement(30)
        assertFalse(announcement.showIfNeeded())
        announcement.showManually()
        assertTrue(announcement.isShowing)
        confirm()
        assertEquals(31, prefs.getInt("read_version_code", 0))
    }

    @Test fun unreadableNotesDoNotCrashOrMarkReadAndDoNotPoll() {
        var reads = 0
        val announcement = announcement { reads++; throw java.io.IOException("missing") }
        repeat(3) { assertFalse(announcement.showIfNeeded()) }
        assertEquals(1, reads)
        assertEquals(0, prefs.getInt("read_version_code", 0))
        announcement.showManually()
        assertTrue(text(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).contains("暂时无法读取"))
        confirm()
        assertEquals(0, prefs.getInt("read_version_code", 0))
    }

    @Test fun emptyNotesAreNotAcknowledged() {
        assertFalse(announcement(notes = { " \n" }).showIfNeeded())
        assertEquals(0, prefs.getInt("read_version_code", 0))
    }

    @Test fun finishingActivityCannotOpenAnnouncement() {
        val activity = activity()
        activity.finish()
        assertFalse(ApkUpdateAnnouncement(activity).showIfNeeded())
    }

    @Test fun aboutPageOpensNotesBundledWithThisApk() {
        val activity = activity()
        activity.showMowerAbout()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        fun buttons(view: View): List<Button> = when (view) {
            is Button -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        buttons(dialog.window!!.decorView).single { it.text == "更新日志" }.performClick()
        val notes = ShadowAlertDialog.getLatestAlertDialog()
        assertFalse(dialog.isShowing)
        val content = text(notes.window!!.decorView)
        assertTrue(content.contains("APK ${BuildConfig.VERSION_NAME} 更新日志"))
        assertTrue(content.contains("宿主更新"))
        assertTrue(content.contains("MAA Python"))
        notes.dismiss()
    }

    @Test fun homePromptsBeforeServiceStartsAndResumeDoesNotDuplicateIt() {
        val home = Robolectric.buildActivity(MowerActivity::class.java).setup().also { activities += it }
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertTrue(text(dialog.window!!.decorView).contains("APK ${BuildConfig.VERSION_NAME} 更新日志"))
        assertFalse(MowerService.active)
        home.pause().resume()
        assertEquals(dialog, ShadowAlertDialog.getLatestAlertDialog())
        confirm()
        assertEquals(BuildConfig.VERSION_CODE, prefs.getInt("read_version_code", 0))
    }

    @Test fun homeDefersNewApkPromptUntilAnnouncementIsDismissed() {
        val home = Robolectric.buildActivity(MowerActivity::class.java).setup().also { activities += it }
        val announcement = ShadowAlertDialog.getLatestAlertDialog()
        val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var requests = 0
        val check = StartupApkUpdateCheck(checkScope, { "a".repeat(64) }, BuildConfig.VERSION_CODE,
            showUpdate = { home.get().showMowerApkUpdatePrompt(it) },
            fetch = {
                requests++
                JSONObject().put("apk", JSONObject().put("version", "next")
                    .put("host_sha256", "b".repeat(64))
                    .put("host_version_code", BuildConfig.VERSION_CODE + 1))
            })
        MowerActivity::class.java.getDeclaredField("apkUpdateCheck\$delegate").apply {
            isAccessible = true
            set(home.get(), lazyOf(check))
        }
        val refresh = MowerActivity::class.java.getDeclaredMethod("refreshUi").apply { isAccessible = true }
        val oldUrl = MowerService.url
        val oldStopping = MowerService.stopping
        try {
            MowerService.url = "http://127.0.0.1:12345"
            MowerService.stopping = false
            refresh.invoke(home.get())
            assertEquals(0, requests)
            assertEquals(announcement, ShadowAlertDialog.getLatestAlertDialog())
            confirm()
            refresh.invoke(home.get())
            assertEquals(1, requests)
            val update = ShadowAlertDialog.getLatestAlertDialog()
            assertFalse(announcement.isShowing)
            assertTrue(update.isShowing)
            assertTrue(text(update.window!!.decorView).contains("next"))
            refresh.invoke(home.get())
            assertEquals(1, requests)
            update.dismiss()
        } finally {
            MowerService.url = oldUrl
            MowerService.stopping = oldStopping
            checkScope.cancel()
        }
    }

}
