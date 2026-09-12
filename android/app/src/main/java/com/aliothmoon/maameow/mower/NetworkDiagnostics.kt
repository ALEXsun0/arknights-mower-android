package com.aliothmoon.maameow.mower

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import java.io.File
import java.time.OffsetDateTime

/** Event-only, bounded history. No SSIDs, addresses, requests or credentials. */
internal object NetworkDiagnostics {
    private const val LIMIT = 32 * 1024
    private fun file(context: Context) = File(context.filesDir, "network-events.log")
    private var lastEvent: String? = null

    fun describe(value: NetworkCapabilities): String {
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "移动网络",
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
            NetworkCapabilities.TRANSPORT_ETHERNET to "以太网",
        ).filter { value.hasTransport(it.first) }.joinToString("+") { it.second }
        return "transport=$transports internet=${value.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "portal=${value.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}"
    }

    fun snapshot(context: Context): String = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        "default=${network ?: "none"} ${capabilities?.let(::describe) ?: "无可用网络"} " +
            "interactive=${power.isInteractive} idle=${power.isDeviceIdleMode} " +
            "powerSave=${power.isPowerSaveMode} dataSaver=${manager.restrictBackgroundStatus}"
    }.getOrElse { "系统网络状态不可读：${it.javaClass.simpleName}" }

    @Synchronized fun record(context: Context, message: String) {
        if (message == lastEvent) return
        lastEvent = message
        runCatching {
            val target = file(context)
            if (target.length() > LIMIT) {
                target.writeText(target.readText().takeLast(LIMIT / 2).substringAfter('\n'))
            }
            target.appendText("${OffsetDateTime.now()} $message\n")
        }
    }

    @Synchronized fun report(context: Context): String =
        "导出时：${snapshot(context)}\n仅记录系统默认网络及 Mower 状态，不能证明游戏连接或游戏的后台网络权限。\n\n" +
            runCatching { file(context).takeIf { it.isFile }?.readText().orEmpty() }
                .getOrDefault("网络历史不可读")
}
