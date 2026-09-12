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
    private val settings = AndroidSystemSettings(context)
    private val audioLedger = context.getSharedPreferences("game-audio-recovery", 0)
    private var prepared = false
    private var expectedGameRunning = false
    private val gameRecovery = GameRecoveryPolicy()
    private var nextGameProbe = 0L
    private var recoveredBinder: android.os.IBinder? = null
    private var systemError: String? = null

    private fun system(s: RemoteService, action: String, p: JSONObject = JSONObject()): Any {
        val response = JSONObject(s.systemRpc(p.put("action", action).toString()))
        check(response.getBoolean("ok")) { response.optString("error") }
        return response.get("result")
    }

    // Record the previous mode BEFORE muting. Failed cleanup remains retryable after a crash.
    private fun restoreAudio(s: RemoteService) {
        for ((pkg, value) in audioLedger.all) {
            val current = (system(s, "audio_get", JSONObject().put("package", pkg)) as JSONObject).getString("mode")
            if (current == "ignore") {
                val restored = system(s, "audio_set", JSONObject().put("package", pkg).put("mode", value)) as JSONObject
                check(restored.getString("mode") == value) { "游戏声音恢复未生效，可在设置页重试" }
            }
            // If someone changed this operation themselves, preserve their newer choice.
            check(audioLedger.edit().remove(pkg).commit()) { "声音恢复记录保存失败" }
        }
    }

    private fun applyAudio(s: RemoteService) {
        if (!settings.enabled("mute_game") || ((MowerService.manual || MowerService.previewing) && settings.enabled("preview_sound"))) {
            restoreAudio(s); return
        }
        if (s.isAppAlive(packageName) != 1) { restoreAudio(s); return }
        if (!audioLedger.contains(packageName)) {
            val mode = (system(s, "audio_get", JSONObject().put("package", packageName)) as JSONObject).getString("mode")
            if (mode == "ignore") return // Already muted by the owner; we must not undo it.
            check(audioLedger.edit().putString(packageName, mode).commit()) { "无法保存声音恢复记录" }
        }
        val applied = system(s, "audio_set", JSONObject().put("package", packageName).put("mode", "ignore")) as JSONObject
        check(applied.getString("mode") == "ignore") { "游戏静音未生效" }
    }

    private fun wake(s: RemoteService, dismiss: Boolean): JSONObject {
        MowerService.monitor?.beforeWake()
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (!power.isInteractive) system(s, "wake")
        for (i in 0 until 20) { if (power.isInteractive) break; Thread.sleep(100) }
        check(power.isInteractive) { "系统未唤醒屏幕" }
        if (dismiss && keyguard.isKeyguardLocked) {
            val result = UnlockSettings(context).unlock(s)
            AndroidSystemSettings.lastAction = UnlockSettings.resultText(result)
            check(result == com.aliothmoon.maameow.constant.WakeUnlockResult.OK) { AndroidSystemSettings.lastAction }
        }
        AndroidSystemSettings.lastAction = if (keyguard.isKeyguardLocked) {
            if (keyguard.isKeyguardSecure) "屏幕已唤醒；安全锁屏需要在手机上认证" else "屏幕已唤醒；滑动锁屏仍未解除"
        } else "屏幕已唤醒，当前无锁屏"
        return settings.deviceState()
    }

    @Synchronized fun maintainSystem() {
        if (closed) return
        val s = RemoteServiceManager.getInstanceOrNull()
        if (s == null) { if (prepared) systemError = "Shizuku 已断开，请恢复授权后点击重新连接"; return }
        try {
            if (recoveredBinder != s.asBinder()) { restoreAudio(s); recoveredBinder = s.asBinder() }
            if (prepared && s.asBinder() == serviceBinder) {
                if (settings.enabled("recover_game") && !MowerService.manual && s.isAppAlive(packageName) == 1 && !s.isAppOnVirtualDisplay(packageName)) {
                    check(s.mowerGame(packageName, true)) { "游戏返回后台显示器失败" }
                    AndroidSystemSettings.lastAction = "已将游戏移回后台显示器"
                }
                applyAudio(s)
            }
            systemError = null
        } catch (e: Exception) { systemError = e.message }
    }

    @Synchronized fun systemSettingsState(): JSONObject = settingsState()
    fun saveSystemSettings(request: JSONObject): JSONObject = dispatch("system_settings_save", request) as JSONObject
    fun performSystemAction(action: String): JSONObject = dispatch("system_action", JSONObject().put("action", action)) as JSONObject

    private fun settingsState(): JSONObject = settings.snapshot().put("device", settings.deviceState())
        .put("engine", status()).put("audio_restore_pending", audioLedger.all.isNotEmpty())
        .put("error", systemError ?: JSONObject.NULL)
        .put("audio_mode", runCatching {
            (system(remote(), "audio_get", JSONObject().put("package", packageName)) as JSONObject).getString("mode")
        }.getOrDefault("unknown"))

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
        restoreAudio(current)
        recoveredBinder = current.asBinder()
        system(current, "display_options", JSONObject().put("fullscreen", settings.enabled("force_fullscreen")))
        check(current.setVirtualDisplayMode(2))
        if (settings.enabled("resolution_720p")) current.setVirtualDisplayResolution(1280, 720, 160)
        else current.setVirtualDisplayResolution(1920, 1080, 320)
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

    @Synchronized fun gameState(): JSONObject {
        val s = remote()
        return (system(s, "game_status", JSONObject().put("package", packageName).put("monitor", settings.enabled("fps_monitor"))) as JSONObject).put("expected_running", expectedGameRunning)
    }
    @Synchronized fun sleepPhone(): Int = system(remote(), "sleep") as Int

    /** Only the task's next capture may recover an intentional solver exit.
     * The periodic monitor must respect idle exits and never launch from idle. */
    @Synchronized fun recoverTaskGame(captureDemand: Boolean = false): Boolean {
        if (!GameRecoveryPolicy.eligible(settings.enabled("keep_game_alive"),
                !closed && !MowerService.stopping && MowerService.active,
                MowerService.manual || MowerService.unlocking, expectedGameRunning, captureDemand)) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (captureDemand && now < nextGameProbe) return false
        val s = ensurePrepared()
        val alive = s.isAppAlive(packageName) == 1
        if (alive && s.isAppOnVirtualDisplay(packageName)) { nextGameProbe = now + 5000; return false }
        if (alive && !settings.enabled("recover_game")) return false
        // Do not interrupt a live MAA controller and accidentally report its task as completed.
        val maa = JSONObject(s.maaRpc("{\"method\":\"maa_status\"}")).getJSONObject("result")
        check(!maa.optBoolean("running")) { "MAA 执行期间游戏退出，请停止任务后重新启动" }
        if (!captureDemand && JSONObject(NativeRuntimeClient.call("/status")).optString("status") != "working") return false
        // If recovery fails, the monitor must still know a working task needs the game.
        expectedGameRunning = true
        check(gameRecovery.acquire(now)) { "游戏短时间内反复退出，已限制自动恢复，请查看诊断日志" }
        if (settings.enabled("wake_on_launch")) wake(s, settings.enabled("dismiss_keyguard"))
        check(s.mowerGame(packageName, true)) { "后台游戏自动恢复失败" }
        expectedGameRunning = true
        nextGameProbe = android.os.SystemClock.elapsedRealtime() + 5000
        applyAudio(s)
        val message = "任务需要游戏画面，已自动恢复后台游戏（10 分钟最多 3 次）"
        AndroidSystemSettings.lastAction = message
        android.util.Log.i("Mower", message)
        runCatching { java.io.File(context.filesDir, "python.log").appendText("\n${java.time.Instant.now()} $message\n") }
        return true
    }

    private fun status(): JSONObject {
        val s = RemoteServiceManager.getInstanceOrNull()
        val maa = runCatching { JSONObject(s!!.maaRpc("{\"method\":\"maa_status\"}")).getJSONObject("result") }.getOrNull()
        return JSONObject().put("protocol", 1).put("connected", s != null)
            .put("prepared", prepared && s?.asBinder() == serviceBinder)
            .put("display_id", displayId).put("resolution", JSONArray(listOf(1920, 1080)))
            .put("backend", "background-display")
            .put("apk_version", com.aliothmoon.maameow.BuildConfig.VERSION_NAME).put("apk_code", com.aliothmoon.maameow.BuildConfig.VERSION_CODE)
            .put("debug", com.aliothmoon.maameow.BuildConfig.DEBUG)
            .put("android_version", android.os.Build.VERSION.RELEASE)
            .put("maa_version", maa?.optString("version", "unloaded") ?: "unloaded")
            .put("maa_component_hash", maa?.optString("component_hash", "") ?: "")
    }

    private fun point(a: JSONArray): Pair<Int, Int> {
        require(a.length() == 2)
        val x = a.getInt(0); val y = a.getInt(1)
        require(x in 0..1919 && y in 0..1079) { "Touch outside game display" }
        return x to y
    }

    @Synchronized private fun dispatch(method: String, p: JSONObject): Any {
        if (method in setOf("tap", "swipe", "key", "text", "maa_start")) check(!MowerService.manual && !MowerService.unlocking) { "游戏画面正在手动操作，请先返回 WebUI" }
        check(!closed) { "Mower 服务正在停止" }
        if (method == "status") return status()
        if (method == "apk_info" || method == "apk_install") {
            val id = p.getString("id")
            val result = AndroidAppUpdate.inspect(context, id)
            if (method == "apk_install") {
                val activity = AndroidSystemSettings.foreground ?: error("请先把手机上的 Mower 打开到前台，再安装更新")
                result.put("message", activity.installUpdate(AndroidAppUpdate.file(context, id)))
            }
            return result
        }
        if (method == "system_settings") return settingsState()
        if (method == "system_settings_save") {
            settings.save(p)
            systemError = null
            try {
                RemoteServiceManager.getInstanceOrNull()?.let {
                    system(it, "display_options", JSONObject().put("fullscreen", settings.enabled("force_fullscreen")))
                    if (prepared && it.asBinder() == serviceBinder) applyAudio(it)
                    else if (!settings.enabled("mute_game")) restoreAudio(it)
                } ?: run { systemError = "已保存；Shizuku 连接恢复后应用游戏设置" }
            } catch (e: Exception) { systemError = "已保存，但应用失败：${e.message}" }
            return settingsState()
        }
        if (method == "system_action") {
            when (p.getString("action")) {
                "reconnect" -> { prepare(JSONObject()); maintainSystem() }
                "restore_audio" -> {
                    settings.save(settings.snapshot().put("settings", settings.values().put("mute_game", false)))
                    restoreAudio(remote())
                    systemError = null
                    AndroidSystemSettings.lastAction = "已关闭自动静音，并恢复本应用修改的游戏声音权限"
                }
                "test_wake" -> wake(remote(), settings.enabled("dismiss_keyguard"))
                "battery_settings", "app_settings", "shizuku" -> {
                    val activity = AndroidSystemSettings.foreground ?: error("请先将手机上的 Mower 打开到前台，再点击此按钮")
                    AndroidSystemSettings.lastAction = "已请求在手机上打开系统设置"
                    activity.openSystemSettings(p.getString("action"))
                }
                else -> error("不支持的系统操作")
            }
            return settingsState()
        }
        if (method.startsWith("maa_")) {
            if (method == "maa_start") {
                if (settings.enabled("wake_on_launch")) wake(ensurePrepared(), settings.enabled("dismiss_keyguard"))
                applyAudio(ensurePrepared())
            }
            val response = JSONObject(ensurePrepared().maaRpc(JSONObject().put("method", method).put("params", p).toString()))
            check(response.getBoolean("ok")) { response.optString("error") }
            return response.get("result")
        }
        if (method == "python_update_available") {
            val version = p.getString("version")
            require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            MowerNotifications.event(context, "MAA Python 兼容接口 $version 可更新，请打开 WebUI 软件更新中的兼容接口更新。", false)
            return true
        }
        if (method == "prepare") return prepare(p)
        val s = ensurePrepared()
        return when (method) {
            "screenshot" -> {
                if (p.optBoolean("require_game")) recoverTaskGame(captureDemand = true)
                val fd = s.mowerFrame() ?: error("后台画面尚未就绪")
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            "game_status" -> JSONObject().put("alive", s.isAppAlive(packageName) == 1)
                .put("on_display", s.isAppAlive(packageName) == 1 && s.isAppOnVirtualDisplay(packageName))
            "launch", "exit_game" -> {
                if (method == "launch" && settings.enabled("wake_on_launch")) wake(s, settings.enabled("dismiss_keyguard"))
                check(s.mowerGame(packageName, method == "launch")) { "游戏启动或关闭失败" }
                expectedGameRunning = method == "launch"
                nextGameProbe = 0L
                if (method == "launch") applyAudio(s) else restoreAudio(s)
                true
            }
            "key" -> { check(s.mowerKey(p.getInt("code"))); true }
            "text" -> { check(s.mowerText(p.getString("text"))); true }
            "tap" -> {
                val (x, y) = point(JSONArray(listOf(p.getInt("x"), p.getInt("y"))))
                MowerTap.perform(
                    down = { s.touchDown(x, y, 0) },
                    up = { s.touchUp(x, y, 0) },
                    cancel = { s.touchCancel() },
                )
                true
            }
            "swipe" -> {
                val raw = p.getJSONArray("points"); val durations = p.getJSONArray("durations")
                require(raw.length() in 2..64 && durations.length() == raw.length() - 1)
                val path = GameSwipePath((0 until raw.length()).map {
                    val pair = raw.getJSONArray(it)
                    require(pair.length() == 2)
                    pair.getInt(0) to pair.getInt(1)
                })
                val points = path.points
                val times = (0 until durations.length()).map { durations.getInt(it).also { d -> require(d in 1..10000) } }
                require(times.sum() <= 30000)
                val wait = p.optInt("up_wait", 0).also { require(it in 0..3000) }
                try {
                    s.touchDown(points.first().first, points.first().second, 0)
                    for (i in times.indices) {
                        val steps = maxOf(1, times[i] / 16)
                        for (n in 1..steps) {
                            Thread.sleep((times[i] / steps).toLong())
                            val (x, y) = path.at(i, n, steps)
                            s.touchMove(x, y, 0)
                        }
                    }
                    Thread.sleep(wait.toLong())
                    val (endX, endY) = path.end
                    s.touchUp(endX, endY, 0)
                } finally { s.touchCancel() }
                true
            }
            else -> error("Unknown bridge operation")
        }
    }

    @Synchronized override fun close() {
        closed = true
        listener.close()
        runCatching { RemoteServiceManager.getInstanceOrNull()?.let { restoreAudio(it) } }
            .onFailure { AndroidSystemSettings.lastAction = "声音恢复待重试，请重新启动服务后检查后台与系统设置" }
        runCatching { RemoteServiceManager.getInstanceOrNull()?.let { it.stopVirtualDisplay() } }
    }
}
