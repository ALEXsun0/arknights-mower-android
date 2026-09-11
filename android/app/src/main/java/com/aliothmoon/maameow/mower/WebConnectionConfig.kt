package com.aliothmoon.maameow.mower

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Only the WebUI connection is configurable; the native bridge remains private. */
class WebConnectionConfig private constructor(val port: Int, val token: String) {
    companion object {
        fun parse(portText: String, tokenText: String): WebConnectionConfig {
            val number = portText.trim()
            val port = if (number.isEmpty()) 0 else {
                require(number.all { it in '0'..'9' }) { "端口须为 1024–65535，或留空自动分配" }
                number.toIntOrNull()?.takeIf { it in 1024..65535 }
                    ?: throw IllegalArgumentException("端口须为 1024–65535，或留空自动分配")
            }
            require(tokenText.isEmpty() || Regex("[A-Za-z0-9_-]+").matches(tokenText)) {
                "Token 支持字母、数字、下划线或短横线，或留空自动生成"
            }
            return WebConnectionConfig(port, tokenText)
        }
    }

    fun availablePort(lan: Boolean): Int {
        // The phone WebView also uses loopback. Some hosts allow wildcard and
        // specific-address listeners to overlap, so verify both in fixed mode.
        if (lan && port != 0) availablePort(false)
        try {
            return ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(if (lan) "0.0.0.0" else "127.0.0.1"), port))
                socket.localPort
            }
        } catch (failure: java.io.IOException) {
            throw IllegalStateException(if (port == 0) "无法分配 WebUI 端口，请稍后重试"
                else "WebUI 端口 $port 不可用，请在局域网连接中修改后重试", failure)
        }
    }
}
