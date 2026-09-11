package com.aliothmoon.maameow.mower

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import rikka.shizuku.Shizuku

internal enum class PermissionAction { START_BACKEND, AUTHORIZE_BACKEND, OVERLAY, NOTIFICATIONS, EXACT_ALARM, BATTERY, PROMOTED_NOTIFICATIONS }
internal data class PermissionIssue(val description: String, val action: PermissionAction, val required: Boolean = false)

internal data class PermissionEntry(val title: String, val state: String, val action: PermissionAction?, val granted: Boolean)

internal data class PermissionSnapshot(
    val root: Boolean, val backendRunning: Boolean, val backendGranted: Boolean,
    val screenSaver: Boolean, val overlay: Boolean, val notifications: Boolean,
    val exactAlarm: Boolean, val batteryUnrestricted: Boolean,
    val promotedNotifications: Boolean? = null,
) {
    val entries: List<PermissionEntry> get() = buildList {
        add(if (root) PermissionEntry("后台服务 · 启动必需", "Root 授权将在启动时验证", null, false)
            else PermissionEntry("后台服务 · 启动必需", if (!backendRunning) "Shizuku / Sui 未运行" else if (!backendGranted) "尚未授权" else "已授权",
                if (!backendRunning) PermissionAction.START_BACKEND else PermissionAction.AUTHORIZE_BACKEND, backendRunning && backendGranted))
        add(PermissionEntry("悬浮窗 · 屏保使用", if (overlay) "已允许" else if (screenSaver) "未允许，屏保无法显示" else "未允许，启用屏保时需要", PermissionAction.OVERLAY, overlay))
        add(PermissionEntry("运行通知", if (notifications) "已允许" else "已关闭", PermissionAction.NOTIFICATIONS, notifications))
        add(PermissionEntry("精确唤醒", if (exactAlarm) "已允许或系统无需单独授权" else "尚未允许，定时唤醒可能延迟", PermissionAction.EXACT_ALARM, exactAlarm))
        add(PermissionEntry("电池优化豁免", if (batteryUnrestricted) "已豁免" else "未豁免，后台运行可能受限", PermissionAction.BATTERY, batteryUnrestricted))
        promotedNotifications?.let { add(PermissionEntry("实时通知", if (it) "已允许" else "未允许，仍可使用普通通知", PermissionAction.PROMOTED_NOTIFICATIONS, it)) }
    }
    val issues: List<PermissionIssue> get() = buildList {
        if (!root) {
            if (!backendRunning) add(PermissionIssue("Shizuku / Sui 未运行，后台服务无法启动", PermissionAction.START_BACKEND, true))
            else if (!backendGranted) add(PermissionIssue("尚未授权后台服务", PermissionAction.AUTHORIZE_BACKEND, true))
        }
        if (screenSaver && !overlay) add(PermissionIssue("屏保已开启，但缺少悬浮窗权限", PermissionAction.OVERLAY))
        if (!notifications) add(PermissionIssue("通知已关闭，无法显示运行提醒", PermissionAction.NOTIFICATIONS))
        if (!exactAlarm) add(PermissionIssue("精确唤醒未授权，定时唤醒可能延迟", PermissionAction.EXACT_ALARM))
        if (!batteryUnrestricted) add(PermissionIssue("电池优化未豁免，后台运行可能受限", PermissionAction.BATTERY))
    }
    val summary: String get() {
        val missing = issues
        val backend = if (root) "Root 授权将在启动时验证" else "后台授权就绪"
        return if (missing.isEmpty()) backend else missing.joinToString("；") { it.description }
    }
}

internal object PermissionChecks {
    fun batteryIntent(context: Context): Intent {
        val uri = Uri.parse("package:${context.packageName}")
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) return details
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
        return if (request.resolveActivity(context.packageManager) != null) request else details
    }

    fun inspect(context: Context): PermissionSnapshot {
        val settings = AndroidSystemSettings(context)
        val root = settings.enabled("root_backend")
        val alive = !root && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        return PermissionSnapshot(root, alive,
            alive && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false),
            settings.enabled("screen_saver"), Settings.canDrawOverlays(context),
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            if (Build.VERSION.SDK_INT >= 36) context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() else null)
    }

    fun intent(context: Context, action: PermissionAction): Intent? {
        val uri = Uri.parse("package:${context.packageName}")
        return when (action) {
            PermissionAction.START_BACKEND -> context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            PermissionAction.AUTHORIZE_BACKEND -> null // The app requests Shizuku's own authorization dialog.
            PermissionAction.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
            PermissionAction.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            PermissionAction.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri) else null
            PermissionAction.BATTERY -> batteryIntent(context)
            PermissionAction.PROMOTED_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 36) Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) else null
        }
    }
}
