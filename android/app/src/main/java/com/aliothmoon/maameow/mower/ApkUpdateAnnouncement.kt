package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.view.ContextThemeWrapper
import android.widget.ScrollView
import android.widget.TextView
import com.aliothmoon.maameow.BuildConfig

/** The installed APK owns its announcement; reading it never requires the runtime or network. */
internal class ApkUpdateAnnouncement(
    private val activity: Activity,
    private val versionCode: Int = BuildConfig.VERSION_CODE,
    private val versionName: String = BuildConfig.VERSION_NAME,
    private val readNotes: () -> String = {
        activity.assets.open("apk-release-notes.md").bufferedReader().use { it.readText() }
    },
) {
    private val preferences = activity.getSharedPreferences("apk-announcement", 0)
    private var attempted = false
    private var dialog: AlertDialog? = null
    val isShowing: Boolean get() = dialog?.isShowing == true

    fun showIfNeeded(): Boolean {
        if (isShowing) return true
        if (attempted || preferences.getInt("read_version_code", 0) >= versionCode) return false
        if (activity.isFinishing || activity.isDestroyed) return false
        attempted = true
        return show(automatic = true)
    }

    fun showManually() { if (!isShowing) show(automatic = false) }

    private fun show(automatic: Boolean): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        var readable = true
        val notes = try {
            readNotes().also { check(it.isNotBlank()) }
        } catch (failure: Exception) {
            android.util.Log.w("Mower", "读取 APK 更新日志失败", failure)
            readable = false
            if (automatic) return false
            "暂时无法读取此 APK 的更新日志。"
        }
        val themed = ContextThemeWrapper(activity,
            if (MowerStyle.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(themed).apply {
            this.text = notes.lineSequence().joinToString("\n") { line ->
                line.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^[-*] "), "• ")
                    .replace(Regex("`([^`]+)`"), "$1").replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            }
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(padding, padding / 2, padding, padding)
        }
        dialog = AlertDialog.Builder(themed)
            .setTitle("APK $versionName 更新日志")
            .setView(ScrollView(themed).apply { addView(text) })
            .setPositiveButton("知道了") { _, _ ->
                if (readable) {
                    val read = preferences.getInt("read_version_code", 0)
                    preferences.edit().putInt("read_version_code", maxOf(read, versionCode)).commit()
                }
            }
            .create().also { current ->
                current.setOnDismissListener { if (dialog === current) dialog = null }
                current.show()
            }
        return true
    }

    fun close() { dialog?.dismiss(); dialog = null }
}

internal fun Activity.showApkUpdateAnnouncement() {
    ApkUpdateAnnouncement(this).showManually()
}
