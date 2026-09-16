package com.aliothmoon.maameow.mower

import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticMcpTest {
    private val calls = AtomicInteger()
    private fun protocol() = DiagnosticMcpProtocol(object : DiagnosticMcpBackend {
        override fun tools() = JSONArray().put(JSONObject().put("name", "android_status"))
        override fun call(name: String, args: JSONObject): JSONObject { calls.incrementAndGet(); return DiagnosticMcpBackend.text("ok") }
    })
    private fun request(method: String) = JSONObject().put("jsonrpc", "2.0").put("id", 4).put("method", method)
    private fun http(server: DiagnosticMcpServer, body: String = request("ping").toString(), token: String = "secret",
                     extra: String = "", method: String = "POST", host: String = "127.0.0.1", size: Int = body.toByteArray().size): Pair<Int, String> {
        return Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("$method /mcp HTTP/1.1\r\nHost: $host\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: $size\r\n$extra\r\n$body").toByteArray())
            val reply = socket.getInputStream().bufferedReader().readText()
            reply.substringBefore("\r\n").split(' ')[1].toInt() to reply.substringAfter("\r\n\r\n")
        }
    }
    @Test fun negotiatesProtocolAndListsTools() {
        val p = protocol()
        val reply = p.handle(request("initialize").put("params", JSONObject().put("protocolVersion", "2025-06-18")))!!
        assertEquals("2025-06-18", reply.getJSONObject("result").getString("protocolVersion"))
        assertEquals(1, p.handle(request("tools/list"))!!.getJSONObject("result").getJSONArray("tools").length())
        assertEquals(0, calls.get())
    }
    @Test fun notificationsNeverExecuteTools() {
        val event = request("tools/call").put("params", JSONObject().put("name", "android_status"))
        event.remove("id")
        assertNull(protocol().handle(event)); assertEquals(0, calls.get())
    }
    @Test fun rejectsInvalidParametersAndUnknownMethods() {
        val p = protocol()
        assertEquals(-32602, p.handle(request("tools/call").put("params", JSONArray()))!!.getJSONObject("error").getInt("code"))
        assertEquals(-32601, p.handle(request("execute_shell"))!!.getJSONObject("error").getInt("code"))
        assertEquals(-32600, p.handle(request("ping").put("id", JSONObject.NULL))!!.getJSONObject("error").getInt("code"))
    }
    @Test fun httpSupportsRequestsAndNotifications() {
        DiagnosticMcpServer("secret", protocol()::handle).use { server ->
            server.start()
            val (status, response) = http(server)
            assertEquals(200, status); assertEquals(4, JSONObject(response).getInt("id"))
            assertEquals(202, http(server, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").first)
            assertEquals(405, http(server, method = "GET").first)
        }
    }
    @Test fun authAndBrowserRequestsCannotReachTools() {
        DiagnosticMcpServer("secret", protocol()::handle).use { server ->
            server.start()
            val body = request("tools/call").put("params", JSONObject().put("name", "android_status")).toString()
            assertEquals(401, http(server, body, token = "wrong").first)
            assertEquals(403, http(server, body, extra = "Origin: http://localhost\r\n").first)
            assertEquals(403, http(server, body, host = "untrusted.example").first)
            assertEquals(0, calls.get())
        }
    }
    @Test fun malformedAndOversizedRequestsAreBounded() {
        DiagnosticMcpServer("secret", protocol()::handle).use { server ->
            server.start()
            assertEquals(413, http(server, size = 20000).first)
            assertEquals(400, http(server, extra = "Transfer-Encoding: chunked\r\n").first)
            assertEquals(400, http(server, extra = "Content-Length: 1\r\n").first)
            assertEquals(400, http(server, extra = "MCP-Protocol-Version: future\r\n").first)
            assertEquals(-32700, JSONObject(http(server, "not json").second).getJSONObject("error").getInt("code"))
        }
    }
    @Test fun stopClosesListeningPortAndExistingClients() {
        repeat(25) {
            DiagnosticMcpServer("secret", protocol()::handle).use { server ->
                server.start()
                val port = server.port
                Socket("127.0.0.1", port).use { client ->
                    // Complete one request to ensure the accept loop has started.
                    assertEquals(200, http(server).first)
                    server.close()
                    assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
                    client.soTimeout = 1000
                    assertEquals(-1, client.getInputStream().read())
                }
            }
        }
    }
}
