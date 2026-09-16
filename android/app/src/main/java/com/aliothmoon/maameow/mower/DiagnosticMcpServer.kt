package com.aliothmoon.maameow.mower

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Stateless Streamable HTTP MCP. Only reachable locally or through an authorized ADB forward. */
internal class DiagnosticMcpServer(
    private val token: String,
    private val dispatch: (JSONObject) -> JSONObject?,
) : AutoCloseable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port get() = listener.localPort
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(2))
    @Volatile private var closed = false
    private var acceptThread: Thread? = null
    @Synchronized
    fun start() {
        check(!closed) { "Diagnostic server is closed" }
        if (acceptThread != null) return
        acceptThread = Thread({
            while (!closed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                clients.add(socket)
                try { workers.execute { try { serve(socket) } finally { clients.remove(socket); socket.close() } } }
                catch (_: Exception) { clients.remove(socket); socket.close() }
            }
        }, "mower-diagnostic-mcp").apply { isDaemon = true }
        acceptThread!!.start()
    }
    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var headerBytes = 0
            fun line(): String {
                val result = StringBuilder()
                while (true) {
                    check(System.nanoTime() < deadline && ++headerBytes <= 8192)
                    val b = input.read(); check(b >= 0)
                    if (b == 10) return result.toString().removeSuffix("\r")
                    check(b == 13 || b in 32..126); result.append(b.toChar())
                }
            }
            val request = line().split(' ')
            if (request.size != 3 || request[1] != "/mcp" || request[2] != "HTTP/1.1") { reply(socket, 404); return }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val value = line(); if (value.isEmpty()) break
                val separator = value.indexOf(':'); check(separator > 0)
                val key = value.substring(0, separator).lowercase()
                check(!headers.containsKey(key)); headers[key] = value.substring(separator + 1).trim()
            }
            if (!Regex("(?:127\\.0\\.0\\.1|localhost)(?::[0-9]{1,5})?").matches(headers["host"].orEmpty())) { reply(socket, 403); return }
            if (headers.containsKey("origin")) { reply(socket, 403); return }
            if (!MessageDigest.isEqual(("Bearer $token").toByteArray(), headers["authorization"].orEmpty().toByteArray())) {
                reply(socket, 401); return
            }
            if (request[0] != "POST") { reply(socket, 405); return }
            if (headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
                reply(socket, 400); return
            }
            val version = headers["mcp-protocol-version"]
            if (version != null && version !in DiagnosticMcpProtocol.versions) { reply(socket, 400); return }
            val size = headers["content-length"]?.toIntOrNull()
            if (size == null || size !in 1..16384) { reply(socket, 413); return }
            val bytes = ByteArray(size); var count = 0
            while (count < size) {
                check(System.nanoTime() < deadline)
                val read = input.read(bytes, count, size - count); check(read > 0); count += read
            }
            val body = try { JSONObject(String(bytes, Charsets.UTF_8)) }
                catch (_: Exception) { reply(socket, 200, DiagnosticMcpProtocol.error(JSONObject.NULL, -32700, "Parse error")); return }
            val response = dispatch(body)
            reply(socket, if (response == null) 202 else 200, response)
        } catch (_: Exception) { runCatching { reply(socket, 400) } }
    }
    private fun reply(socket: Socket, status: Int, value: JSONObject? = null) {
        val data = value?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val reason = when (status) { 200 -> "OK"; 202 -> "Accepted"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 413 -> "Content Too Large"; else -> "Bad Request" }
        val extra = if (status == 405) "Allow: POST\r\n" else ""
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
            write(data); flush()
        }
    }
    @Synchronized override fun close() {
        closed = true
        listener.close()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        // Linux may defer releasing the listening fd until blocked accept() exits.
        // Also let a socket accepted concurrently reach the rejected-worker cleanup.
        if (Thread.currentThread() != acceptThread) acceptThread?.join(1000)
    }
}

internal class DiagnosticMcpProtocol(private val backend: DiagnosticMcpBackend) {
    companion object {
        val versions = setOf("2025-11-25", "2025-06-18", "2025-03-26")
        fun error(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
    }
    fun handle(request: JSONObject): JSONObject? {
        val id = request.opt("id") ?: JSONObject.NULL
        if (request.optString("jsonrpc") != "2.0" || request.opt("method") !is String ||
            (request.has("id") && id !is String && id !is Number)) return error(JSONObject.NULL, -32600, "Invalid Request")
        if (!request.has("id")) return null // Notifications never invoke tools.
        if (request.has("params") && request.opt("params") !is JSONObject) return error(id, -32602, "Invalid params")
        val params = request.optJSONObject("params") ?: JSONObject()
        val result = when (request.getString("method")) {
            "initialize" -> JSONObject().put("protocolVersion", params.optString("protocolVersion").takeIf { it in versions } ?: "2025-11-25")
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "mower-android-diagnostics").put("version", "1.0.0"))
                .put("instructions", "Read-only Android diagnostics. Times are Unix milliseconds; logs are device local time. Saved game images only; no device control.")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", backend.tools())
            "tools/call" -> {
                if (params.opt("name") !is String || (params.has("arguments") && params.opt("arguments") !is JSONObject)) return error(id, -32602, "Invalid tool arguments")
                try { backend.call(params.getString("name"), params.optJSONObject("arguments") ?: JSONObject()) }
                catch (_: Exception) { DiagnosticMcpBackend.text("读取失败：文件可能已清理，或参数无效。请重新列出文件并检查时间范围。", true) }
            }
            else -> return error(id, -32601, "Method not found")
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }
}

internal interface DiagnosticMcpBackend {
    fun tools(): org.json.JSONArray
    fun call(name: String, args: JSONObject): JSONObject
    companion object {
        fun text(text: String, error: Boolean = false) = JSONObject().put("isError", error)
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    }
}
