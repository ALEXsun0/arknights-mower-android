package com.aliothmoon.maameow.mower

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.io.File
import java.net.InetAddress

/** Linux's resolver must follow Android's active Wi-Fi/VPN DNS, not a baked-in public DNS. */
internal class RuntimeNetwork(context: Context, private val root: File) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    @Volatile private var closed = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (network == connectivity.activeNetwork) runCatching { update(properties) }
        }
    }

    fun start(): String {
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
