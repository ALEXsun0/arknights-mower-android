package com.aliothmoon.maameow.mower

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.aliothmoon.maameow.MaaCoreCallback
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.domain.service.MaaResourceLoader
import com.aliothmoon.maameow.domain.service.ResourceInitService
import com.aliothmoon.maameow.manager.RemoteServiceManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.ArrayDeque
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
    private val events = ArrayDeque<JSONObject>()
    private var sequence = 0L
    private val callback = object : MaaCoreCallback.Stub() {
        override fun onCallback(msg: Int, json: String?) {
            synchronized(events) {
                events.add(JSONObject().put("id", ++sequence).put("msg", msg).put("details", json ?: "{}"))
                while (events.size > 4096) events.removeFirst()
            }
        }
    }

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

    private fun remote(): RemoteService = RemoteServiceManager.getInstanceOrNull()
        ?: error("请在引擎设置中授权并连接 Shizuku 或 Root")

    private fun ensurePrepared(): RemoteService {
        val service = remote()
        check(prepared && service.asBinder() == serviceBinder) { "引擎连接已重建，请重新连接后台游戏" }
        return service
    }

    private fun idle(service: RemoteService) {
        check(!service.maaCoreService.Running()) { "MAA 正在执行，mower 触控已暂停" }
    }

    private fun prepare(p: JSONObject): JSONObject {
        val requested = p.optString("package", packageName)
        require(requested in setOf("com.hypergryph.arknights", "com.hypergryph.arknights.bilibili")) { "暂支持官服和 B 服" }
        val service = remote()
        if (prepared && service.asBinder() == serviceBinder && requested == packageName) return status()
        idle(service)
        prepared = false
        runBlocking {
            get<ResourceInitService>(ResourceInitService::class.java).checkAndInit()
            get<MaaResourceLoader>(MaaResourceLoader::class.java).ensureLoaded(if (requested.endsWith("bilibili")) "Bilibili" else "Official").getOrThrow()
        }
        val current = remote()
        check(current.setVirtualDisplayMode(2))
        current.setVirtualDisplayResolution(1920, 1080, 320)
        displayId = current.startVirtualDisplay()
        check(displayId > 0) { "无法创建后台游戏显示器" }
        val maa = current.maaCoreService
        check(maa.CreateInstance(callback)) { "无法创建 MAA 实例" }
        // MAA's Android native controller is selected by the touch option "Android".
        check(maa.SetInstanceOption(2, "Android"))
        val config = JSONObject().put("library_path", "libbridge.so")
            .put("screen_resolution", JSONObject().put("width", 1920).put("height", 1080))
            .put("display_id", displayId).put("force_stop", false)
        check(maa.AsyncConnect("", "Android", config.toString(), true) > 0 && maa.Connected()) { "MAA 后台连接失败" }
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
            .put("maa_version", runCatching { s?.maaCoreService?.GetVersion() }.getOrNull() ?: "unavailable")
            .put("running", runCatching { s?.maaCoreService?.Running() }.getOrNull() ?: false)
    }

    private fun point(a: JSONArray): Pair<Int, Int> {
        require(a.length() == 2)
        val x = a.getInt(0); val y = a.getInt(1)
        require(x in 0..1919 && y in 0..1079) { "Touch outside game display" }
        return x to y
    }

    @Synchronized private fun dispatch(method: String, p: JSONObject): Any {
        if (method == "status") return status()
        if (method == "prepare") return prepare(p)
        val s = ensurePrepared()
        val maa = s.maaCoreService
        return when (method) {
            "screenshot" -> {
                val fd = s.mowerFrame() ?: error("后台画面尚未就绪")
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            "game_status" -> JSONObject().put("alive", s.isAppAlive(packageName) == 1)
                .put("on_display", s.isAppAlive(packageName) == 1 && s.isAppOnVirtualDisplay(packageName))
            "launch", "exit_game" -> { idle(s); check(s.mowerGame(packageName, method == "launch")); true }
            "key" -> { idle(s); check(s.mowerKey(p.getInt("code"))); true }
            "text" -> { idle(s); check(s.mowerText(p.getString("text"))); true }
            "tap" -> {
                idle(s)
                val (x, y) = point(JSONArray(listOf(p.getInt("x"), p.getInt("y"))))
                try { s.touchDown(x, y, 0); Thread.sleep(45); s.touchUp(x, y, 0) }
                finally { s.touchCancel() }
                true
            }
            "swipe" -> {
                idle(s)
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
            "maa_reset" -> { idle(s); check(maa.Stop()); synchronized(events) { events.clear(); sequence } }
            "maa_connected" -> maa.Connected()
            "maa_option" -> { idle(s); val key = p.getInt("key"); if (key == 2) true else maa.SetInstanceOption(key, p.getString("value")) }
            "maa_append" -> { idle(s); val id = maa.AppendTask(p.getString("type"), p.getJSONObject("params").toString()); check(id > 0); id }
            "maa_params" -> maa.SetTaskParams(p.getInt("id"), p.getJSONObject("params").toString())
            "maa_start" -> { idle(s); check(maa.Start()); true }
            "maa_running" -> maa.Running()
            "maa_stop" -> maa.Stop()
            "maa_tasks" -> JSONArray(maa.GetTasksList().toList())
            "maa_events" -> synchronized(events) {
                val after = p.getLong("after")
                check(events.isEmpty() || after >= events.first().getLong("id") - 1) { "MAA callback buffer overflow; stop and reconnect" }
                JSONObject().put("events", JSONArray(events.filter { it.getLong("id") > after }))
            }
            else -> error("Unknown bridge operation")
        }
    }

    override fun close() {
        closed = true
        listener.close()
        runCatching { RemoteServiceManager.getInstanceOrNull()?.maaCoreService?.Stop() }
    }
}
