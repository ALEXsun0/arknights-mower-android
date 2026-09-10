package com.aliothmoon.maameow.mower

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.manager.RemoteServiceManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Private, authenticated, single-writer bridge to the embedded engine. */
class MowerBridge(private val context: Context, private val token: String) : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    @Volatile private var closed = false
    private var prepared = false
    private var packageName = "com.hypergryph.arknights"
    private var displayId = -1
    private var serviceBinder: android.os.IBinder? = null
    fun start() = thread(name = "mower-bridge") {
        while (!closed) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            socket.use {
                it.soTimeout = 10000
                val response = try {
                    val input = DataInputStream(it.getInputStream())
                    val size = input.readInt()
                    require(size in 1..1048576) { "Request too large" }
                    val bytes = ByteArray(size).also(input::readFully)
                    val request = JSONObject(String(bytes, Charsets.UTF_8))
                    check(MessageDigest.isEqual(token.toByteArray(), request.optString("token").toByteArray())) { "Unauthorized" }
                    JSONObject().put("ok", true).put("result", dispatch(request.getString("method"), request.optJSONObject("params") ?: JSONObject()))
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
                }
                runCatching {
                    val data = response.toString().toByteArray()
                    DataOutputStream(it.getOutputStream()).apply { writeInt(data.size); write(data); flush() }
                }
            }
        }
    }

    fun openGame() {
        dispatch("prepare", JSONObject())
        dispatch("launch", JSONObject())
    }

    private fun remote(): RemoteService = RemoteServiceManager.getInstanceOrNull()
        ?: error("请在手机上启动并授权 Shizuku，然后重启 Mower 服务")

    private fun ensurePrepared(): RemoteService {
        val service = remote()
        check(prepared && service.asBinder() == serviceBinder) { "引擎连接已重建，请重新连接后台游戏" }
        return service
    }

    private fun prepare(p: JSONObject): JSONObject {
        val requested = p.optString("package", packageName)
        require(requested in setOf("com.hypergryph.arknights", "com.hypergryph.arknights.bilibili")) { "暂支持官服和 B 服" }
        val current = runBlocking { RemoteServiceManager.getInstance() }
        if (prepared && current.asBinder() == serviceBinder && requested == packageName) return status()
        prepared = false
        check(current.setVirtualDisplayMode(2))
        current.setVirtualDisplayResolution(1920, 1080, 320)
        displayId = current.startVirtualDisplay()
        check(displayId > 0) { "无法创建后台游戏显示器" }
        val component = java.io.File(context.filesDir, "mower-data/maa-component.zip")
        val hash = java.io.File(context.filesDir, "mower-data/maa-component.sha256").readText().trim()
        check(current.installCore(ParcelFileDescriptor.open(component, ParcelFileDescriptor.MODE_READ_ONLY), hash))
        val connected = JSONObject(current.maaRpc(JSONObject().put("method", "maa_prepare")
            .put("params", JSONObject().put("bridge_library", java.io.File(context.applicationInfo.nativeLibraryDir, "libbridge.so").path)).toString()))
        check(connected.getBoolean("ok")) { connected.optString("error") }
        packageName = requested
        serviceBinder = current.asBinder()
        prepared = true
        return status()
    }

    private fun status(): JSONObject {
        val s = RemoteServiceManager.getInstanceOrNull()
        return JSONObject().put("protocol", 1).put("connected", s != null)
            .put("prepared", prepared && s?.asBinder() == serviceBinder)
            .put("display_id", displayId).put("resolution", JSONArray(listOf(1920, 1080)))
            .put("backend", "background-display")
            .put("android_version", android.os.Build.VERSION.RELEASE)
            .put("maa_version", runCatching { JSONObject(s!!.maaRpc("{\"method\":\"maa_status\"}")).getJSONObject("result").getString("version") }.getOrDefault("unloaded"))
    }

    private fun point(a: JSONArray): Pair<Int, Int> {
        require(a.length() == 2)
        val x = a.getInt(0); val y = a.getInt(1)
        require(x in 0..1919 && y in 0..1079) { "Touch outside game display" }
        return x to y
    }

    @Synchronized private fun dispatch(method: String, p: JSONObject): Any {
        if (method in setOf("tap", "swipe", "key", "text", "maa_start")) check(!MowerService.manual) { "游戏画面正在手动操作，请先返回 WebUI" }
        check(!closed) { "Mower 服务正在停止" }
        if (method == "status") return status()
        if (method.startsWith("maa_")) {
            val response = JSONObject(ensurePrepared().maaRpc(JSONObject().put("method", method).put("params", p).toString()))
            check(response.getBoolean("ok")) { response.optString("error") }
            return response.get("result")
        }
        if (method == "prepare") return prepare(p)
        val s = ensurePrepared()
        return when (method) {
            "screenshot" -> {
                val fd = s.mowerFrame() ?: error("后台画面尚未就绪")
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            "game_status" -> JSONObject().put("alive", s.isAppAlive(packageName) == 1)
                .put("on_display", s.isAppAlive(packageName) == 1 && s.isAppOnVirtualDisplay(packageName))
            "launch", "exit_game" -> { check(s.mowerGame(packageName, method == "launch")); true }
            "key" -> { check(s.mowerKey(p.getInt("code"))); true }
            "text" -> { check(s.mowerText(p.getString("text"))); true }
            "tap" -> {
                val (x, y) = point(JSONArray(listOf(p.getInt("x"), p.getInt("y"))))
                try { s.touchDown(x, y, 0); Thread.sleep(45); s.touchUp(x, y, 0) }
                finally { s.touchCancel() }
                true
            }
            "swipe" -> {
                val raw = p.getJSONArray("points"); val durations = p.getJSONArray("durations")
                require(raw.length() in 2..64 && durations.length() == raw.length() - 1)
                val points = (0 until raw.length()).map { point(raw.getJSONArray(it)) }
                val times = (0 until durations.length()).map { durations.getInt(it).also { d -> require(d in 1..10000) } }
                require(times.sum() <= 30000)
                val wait = p.optInt("up_wait", 0).also { require(it in 0..3000) }
                try {
                    s.touchDown(points.first().first, points.first().second, 0)
                    for (i in times.indices) {
                        val (x, y) = points[i]; val (ex, ey) = points[i+1]
                        val steps = maxOf(1, times[i] / 16)
                        for (n in 1..steps) {
                            Thread.sleep((times[i] / steps).toLong())
                            s.touchMove(x + (ex-x)*n/steps, y + (ey-y)*n/steps, 0)
                        }
                    }
                    Thread.sleep(wait.toLong())
                    s.touchUp(points.last().first, points.last().second, 0)
                } finally { s.touchCancel() }
                true
            }
            else -> error("Unknown bridge operation")
        }
    }

    @Synchronized override fun close() {
        closed = true
        listener.close()
        runCatching { RemoteServiceManager.getInstanceOrNull()?.let { it.stopVirtualDisplay() } }
    }
}
