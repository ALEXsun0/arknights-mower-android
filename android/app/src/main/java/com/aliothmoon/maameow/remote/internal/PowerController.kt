package com.aliothmoon.maameow.remote.internal

import android.os.Build
import com.aliothmoon.maameow.constant.AndroidVersions
import com.aliothmoon.maameow.constant.DefaultDisplayConfig
import com.aliothmoon.maameow.third.Ln
import com.aliothmoon.maameow.third.wrappers.DisplayControl
import com.aliothmoon.maameow.third.wrappers.ServiceManager
import com.aliothmoon.maameow.third.wrappers.SurfaceControl
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object PowerController {
    private const val TAG = "PowerController"
    private const val USER_ACTIVITY_INTERVAL_MS = 4_000L
    private val file = File("/data/local/tmp/mower_power_off_flag")

    private val keepAliveTargets = DisplayKeepAliveTargets()
    private val activityWorker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "display-user-activity").apply { isDaemon = true }
    }
    private var activityTask: ScheduledFuture<*>? = null

    var flag: Boolean
        get() = runCatching { file.exists() }.getOrDefault(false)
        set(value) {
            runCatching {
                if (value) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                } else {
                    file.delete()
                }
            }
        }

    fun setDisplayPower(on: Boolean): Boolean {
        if (!on) flag = true
        val restored = setDisplayPowerInternal(on)
        if (on && restored) flag = false
        return restored
    }

    private fun setDisplayPowerInternal(on: Boolean): Boolean {
        var applyToMultiPhysicalDisplays =
            Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10

        if (applyToMultiPhysicalDisplays
            && Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14 && Build.BRAND.equals(
                "honor",
                ignoreCase = true
            )
            && SurfaceControl.hasGetBuildInDisplayMethod()
        ) {
            applyToMultiPhysicalDisplays = false
        }

        val mode: Int =
            if (on) SurfaceControl.POWER_MODE_NORMAL else SurfaceControl.POWER_MODE_OFF
        if (applyToMultiPhysicalDisplays) {
            val useDisplayControl =
                Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14 && !SurfaceControl.hasGetPhysicalDisplayIdsMethod()

            val physicalDisplayIds =
                if (useDisplayControl) DisplayControl.getPhysicalDisplayIds() else SurfaceControl.getPhysicalDisplayIds()
            if (physicalDisplayIds == null) {
                Ln.e("Could not get physical display ids")
                return false
            }

            var allOk = true
            for (physicalDisplayId in physicalDisplayIds) {
                val binder = if (useDisplayControl) DisplayControl.getPhysicalDisplayToken(
                    physicalDisplayId
                ) else SurfaceControl.getPhysicalDisplayToken(physicalDisplayId)
                allOk = allOk and SurfaceControl.setDisplayPowerMode(binder, mode)
            }
            return allOk
        }

        val d = SurfaceControl.getBuiltInDisplay()
        if (d == null) {
            Ln.e("Could not get built-in display")
            return false
        }
        return SurfaceControl.setDisplayPowerMode(d, mode)
    }

    @Synchronized fun startUserActivityKeepAlive(displayId: Int) {
        keepAliveTargets.physical(displayId)
        updateActivityTask()
    }

    @Synchronized fun stopUserActivityKeepAlive() {
        keepAliveTargets.physical(DefaultDisplayConfig.DISPLAY_NONE)
        updateActivityTask()
    }

    @Synchronized fun startVirtualDisplayKeepAlive(displayId: Int) {
        require(displayId > 0)
        keepAliveTargets.virtual(displayId)
        updateActivityTask()
    }

    @Synchronized fun stopVirtualDisplayKeepAlive() {
        keepAliveTargets.virtual(DefaultDisplayConfig.DISPLAY_NONE)
        updateActivityTask()
    }

    private fun updateActivityTask() {
        val sdk = Build.VERSION.SDK_INT
        val targets = keepAliveTargets.snapshot(sdk)
        Ln.i("$TAG: userActivity targets=$targets")
        if (targets.isEmpty()) {
            activityTask?.cancel(false)
            activityTask = null
        } else if (activityTask == null) {
            activityTask = activityWorker.scheduleWithFixedDelay({
                for (id in keepAliveTargets.snapshot(sdk)) {
                    runCatching { ServiceManager.getPowerManager().userActivity(id) }
                        .onFailure { Ln.e("$TAG: display $id userActivity failed", it) }
                }
            }, 0, USER_ACTIVITY_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized fun destroy() {
        keepAliveTargets.clear()
        updateActivityTask()
        if (flag) {
            Ln.i("$TAG: Emergency recovering screen power...")
            runCatching {
                setDisplayPower(true)
            }.onFailure {
                Ln.e("$TAG: Failed to recover screen power: ${it.message}")
            }
        }
    }
}
