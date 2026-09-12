package com.aliothmoon.maameow.mower

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.net.InetAddress

/** Linux's resolver must follow Android's active Wi-Fi/VPN DNS, not a baked-in public DNS. */
internal class RuntimeNetwork(private val context: Context, private val root: File) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    @Volatile private var closed = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { event("默认网络可用 id=$network") }
        override fun onLost(network: Network) { event("默认网络断开 id=$network") }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            event("网络能力 id=$network ${NetworkDiagnostics.describe(capabilities)}")
        }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            event("Mower 网络访问受限 id=$network blocked=$blocked（不代表游戏 UID 的状态）")
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (network == connectivity.activeNetwork) runCatching { update(properties) }
                .onFailure { event("Python DNS 同步失败：${it.javaClass.simpleName}") }
        }
    }

    @Synchronized private fun event(message: String) {
        if (!closed) NetworkDiagnostics.record(context, message)
    }

    fun start(): String {
        event("服务启动 ${NetworkDiagnostics.snapshot(context)}")
        val properties = connectivity.activeNetwork?.let(connectivity::getLinkProperties)
        if (properties != null) update(properties)
        connectivity.registerDefaultNetworkCallback(callback)
        registered = true
        return if (properties == null) "当前无外部网络；本机 WebUI 仍可启动"
            else "Python DNS 已按当前系统网络检查；网络切换时自动同步"
    }

    @Synchronized private fun update(properties: LinkProperties) {
        if (closed) return
        val content = resolverConfig(properties.dnsServers) ?: return
        val target = File(root, "etc/resolv.conf")
        if (target.isFile && target.readText() == content) return
        val temporary = File(target.parentFile, "resolv.conf.new")
        temporary.writeText(content)
        check(temporary.renameTo(target)) { "无法同步 Python DNS 设置" }
    }

    @Synchronized override fun close() {
        event("停止网络监测（Mower 服务关闭）")
        closed = true
        if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
    }
}

internal fun resolverConfig(addresses: List<InetAddress>): String? {
    val usable = addresses.filterNot { it.isAnyLocalAddress || it.isLinkLocalAddress || it.isMulticastAddress }
        .mapNotNull { it.hostAddress }.distinct().take(3)
    return if (usable.isEmpty()) null else usable.joinToString("\n") { "nameserver $it" } + "\noptions timeout:2 attempts:2\n"
}
