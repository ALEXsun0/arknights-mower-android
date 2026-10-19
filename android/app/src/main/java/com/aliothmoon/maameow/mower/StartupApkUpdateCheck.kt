package com.aliothmoon.maameow.mower

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One background check per home activity; only the visible, ready page may prompt. */
internal class StartupApkUpdateCheck(
    private val scope: CoroutineScope,
    private val installedHash: () -> String,
    private val installedCode: Int,
    private val showUpdate: (String) -> Unit,
    private val fetch: suspend () -> JSONObject = {
        withContext(Dispatchers.IO) { NativeRuntimeClient.apkUpdate("check") }
    },
) {
    private var checked = false
    private var visibleAndReady = false
    private var pendingVersion: String? = null

    fun refresh(foreground: Boolean, serviceReady: Boolean) {
        visibleAndReady = foreground && serviceReady
        if (!visibleAndReady) return
        showPending()
        if (checked) return
        checked = true
        scope.launch {
            try {
                val apk = fetch().getJSONObject("apk")
                if (apkHostUpdateAvailable(installedHash(), installedCode, apk)) {
                    pendingVersion = apk.getString("version")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed startup check stays silent; manual checking remains available.
                return@launch
            }
            showPending()
        }
    }

    private fun showPending() {
        if (!visibleAndReady || !scope.isActive) return
        val version = pendingVersion ?: return
        pendingVersion = null
        showUpdate(version)
    }
}
