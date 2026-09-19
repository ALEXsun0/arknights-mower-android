package com.aliothmoon.maameow.mower

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.Surface
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.bridge.NativeBridgeLib
import com.aliothmoon.maameow.maa.InputControlUtils
import com.aliothmoon.maameow.remote.internal.VirtualDisplayManager
import com.aliothmoon.maameow.third.Workarounds
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Shizuku owns the display and official Android MAA core; Python owns scheduling. */
class BackgroundGameService : RemoteService.Stub() {
    companion object { @JvmStatic var current: BackgroundGameService? = null; private set }
    private val maa = AndroidMaaCore()
    private val audio = GameAudioSession({ audioModes(it).packageMode }, { pkg, mode -> setAudioMode(pkg, mode) })
    init {
        if (Process.myUid() == 0 && android.os.Build.VERSION.SDK_INT < 34) {
            // Use the shell identity expected by Android's display attribution checks.
            android.system.Os.setgid(2000)
            android.system.Os.setuid(2000)
        }
        Workarounds.apply(); com.aliothmoon.maameow.remote.internal.PowerController.destroy(); current = this
    }
    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        val identity = android.os.Binder.clearCallingIdentity()
        try { return super.onTransact(code, data, reply, flags) }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }
    override fun installCore(zip: ParcelFileDescriptor, hash: String) = maa.install(zip, hash)
    override fun maaRpc(request: String): String = try {
        org.json.JSONObject().put("ok", true).put("result", maa.call(org.json.JSONObject(request), VirtualDisplayManager.getDisplayId())).toString()
    } catch (e: Exception) { org.json.JSONObject().put("ok", false).put("error", e.message).toString() }

    private var width = 1920
    private var height = 1080
    /** Fixed operations only: never expose an arbitrary shell command to the WebUI. */
    @Synchronized override fun systemRpc(request: String): String = try {
        val p = org.json.JSONObject(request)
        val result: Any = when (p.getString("action")) {
            "wake" -> { check(com.aliothmoon.maameow.remote.internal.WakeUnlockController.wakeScreen()) { "无法唤醒屏幕" }; true }
            "sleep" -> com.aliothmoon.maameow.remote.internal.WakeUnlockController.lockAndSleep()
            "screen_power" -> {
                val power = com.aliothmoon.maameow.remote.internal.PowerController
                val on = p.getBoolean("on")
                val success = power.setDisplayPower(on)
                if (on || !success) power.stopUserActivityKeepAlive()
                else power.startUserActivityKeepAlive(0)
                success
            }
            "game_status" -> {
                val pkg = p.getString("package"); require(allowed(pkg))
                val alive = isAppAlive(pkg) == 1
                val onDisplay = VirtualDisplayManager.getDisplayId() > 0 && alive && isAppOnVirtualDisplay(pkg)
                if (p.optBoolean("monitor") && onDisplay) com.aliothmoon.maameow.remote.internal.GameFpsMonitor.ensureStarted(pkg)
                else com.aliothmoon.maameow.remote.internal.GameFpsMonitor.stop()
                org.json.JSONObject().put("alive", alive).put("on_display", onDisplay)
                    .put("display_id", VirtualDisplayManager.getDisplayId())
                    .put("display_valid", VirtualDisplayManager.isDisplayValid())
                    .put("display_state", VirtualDisplayManager.getDisplayState())
                    .put("frame_count", NativeBridgeLib.getFrameCount())
                    .put("fps", com.aliothmoon.maameow.remote.internal.GameFpsMonitor.currentFps())
                    .put("width", width).put("height", height).put("maa_running", maa.running())
            }
            "dismiss_keyguard" -> { check(command("/system/bin/wm", "dismiss-keyguard").first == 0); true }
            "audio_get", "audio_set" -> {
                val pkg = p.getString("package"); require(allowed(pkg))
                if (p.getString("action") == "audio_set") audio.set(pkg, p.getString("mode"))
                val modes = audioModes(pkg)
                org.json.JSONObject().put("mode", modes.packageMode)
                    .put("uid_mode", modes.uidMode ?: org.json.JSONObject.NULL).put("blocked", modes.blocked)
            }
            "audio_repair" -> {
                val (code, output) = command("/system/bin/pm", "list", "packages", "--user", "0")
                check(code == 0) { "无法查询已安装的明日方舟" }
                val packages = output.lineSequence().map { it.trim().removePrefix("package:") }.filter { allowed(it) }.toList()
                check(packages.isNotEmpty()) { "未找到已安装的明日方舟" }
                for (pkg in packages) {
                    repairGameAudio({ audioModes(pkg) }) { mode, uid ->
                        if (uid) setAudioMode(pkg, mode, uid = true) else audio.set(pkg, mode)
                    }
                }
                true
            }
            else -> error("不支持的系统操作")
        }
        org.json.JSONObject().put("ok", true).put("result", result).toString()
    } catch (e: Exception) { org.json.JSONObject().put("ok", false).put("error", e.message).toString() }

    private fun display(): Int = VirtualDisplayManager.getDisplayId().also { check(it > 0) { "后台显示器尚未启动" } }
    private fun allowed(pkg: String) = pkg in setOf("com.hypergryph.arknights", "com.hypergryph.arknights.bilibili")
    private fun command(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }
    private fun audioCommand(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("系统声音权限操作超时")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0 && !output.contains("Error", ignoreCase = true)) { "系统声音权限操作失败：$output" }
        return output
    }
    private fun audioModes(pkg: String) = GameAudioModes.parse(
        audioCommand("/system/bin/cmd", "appops", "get", "--user", "0", pkg, "PLAY_AUDIO")
    )
    private fun setAudioMode(pkg: String, mode: String, uid: Boolean = false) {
        require(allowed(pkg))
        require(mode in setOf("allow", "ignore", "deny", "default", "foreground"))
        val args = mutableListOf("/system/bin/cmd", "appops", "set", "--user", "0")
        if (uid) args.add("--uid")
        args.addAll(listOf(pkg, "PLAY_AUDIO", mode))
        audioCommand(*args.toTypedArray())
    }
    @Synchronized override fun unlockPhone(kind: String, credential: String, test: Boolean): Int {
        val controller = com.aliothmoon.maameow.remote.internal.WakeUnlockController
        return when (kind) {
            "gesture" -> if (test) controller.testUnlockGesture(credential) else controller.unlockWithGesture(credential)
            "pin", "swipe" -> if (test) controller.testUnlock(if (kind == "pin") credential else "") else controller.unlock(if (kind == "pin") credential else "")
            else -> com.aliothmoon.maameow.constant.WakeUnlockResult.UNSUPPORTED
        }
    }
    override fun startUnlockRecording() = com.aliothmoon.maameow.remote.internal.GestureRecorder.start(90_000)
    override fun pollUnlockRecording() = com.aliothmoon.maameow.remote.internal.GestureRecorder.poll()
    override fun cancelUnlockRecording() = com.aliothmoon.maameow.remote.internal.GestureRecorder.cancel()

    @Synchronized override fun destroy() {
        // 应用被强退时也从独立后台进程恢复，不依赖主进程的 onDestroy。
        runCatching { audio.restoreAll() }.onFailure { android.util.Log.e("Mower", "退出前恢复游戏声音失败", it) }
        runCatching { cancelUnlockRecording() }
        runCatching { com.aliothmoon.maameow.remote.internal.PowerController.destroy() }
        runCatching { stopVirtualDisplay() }
        exitProcess(0)
    }
    override fun setVirtualDisplayMode(mode: Int) = mode == 2
    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) {
        require(width == 1920 && height == 1080); this.width = width; this.height = height; VirtualDisplayManager.setResolution(width, height, dpi)
    }
    override fun startVirtualDisplay() = VirtualDisplayManager.start()
    @Synchronized override fun stopVirtualDisplay() {
        try { audio.restoreAll() }
        finally {
            com.aliothmoon.maameow.remote.internal.GameFpsMonitor.stop()
            maa.stop()
            InputControlUtils.cancel(VirtualDisplayManager.getDisplayId())
            VirtualDisplayManager.stop()
        }
    }
    override fun setMonitorSurface(surface: Surface?) {
        VirtualDisplayManager.setMonitorSurface(surface); NativeBridgeLib.setPreviewSurface(surface)
    }
    override fun touchDown(x: Int, y: Int, contact: Int) { check(InputControlUtils.down(x * width / 1920, y * height / 1080, contact, display())) }
    override fun touchMove(x: Int, y: Int, contact: Int) { check(InputControlUtils.move(x * width / 1920, y * height / 1080, contact, display())) }
    override fun touchUp(x: Int, y: Int, contact: Int) { check(InputControlUtils.up(x * width / 1920, y * height / 1080, contact, display())) }
    override fun setTouchMonitor(callback: com.aliothmoon.maameow.ITouchEventCallback?) { InputControlUtils.setTouchCallback(callback) }
    override fun touchCancel() { InputControlUtils.cancel(display()) }
    override fun mowerFrame(): ParcelFileDescriptor? {
        val captured = NativeBridgeLib.getFrameBufferBitmap() ?: return null
        val bitmap = if (captured.width == 1920 && captured.height == 1080) captured else Bitmap.createScaledBitmap(captured, 1920, 1080, true).also { captured.recycle() }
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "background-frame") {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        return pipe[0]
    }
    override fun mowerKey(code: Int): Boolean {
        require(code in 0..288)
        return command("/system/bin/input", "-d", display().toString(), "keyevent", code.toString()).first == 0
    }
    override fun mowerText(text: String): Boolean {
        require(text.length <= 256 && text.all { it.code in 32..126 })
        return command("/system/bin/input", "-d", display().toString(), "text", text.replace(" ", "%s")).first == 0
    }
    override fun mowerGame(packageName: String, launch: Boolean): Boolean {
        require(allowed(packageName))
        if (!launch) {
            val (code, output) = command("/system/bin/am", "force-stop", packageName)
            val stopped = GameStateConfirmation.await(GameStateConfirmation.EXIT_TIMEOUT_MS) {
                isAppAlive(packageName) == 0
            }
            if (!stopped) android.util.Log.w("Mower", "游戏关闭确认超时：am=$code ${output.trim()}")
            return stopped
        }
        val id = display()
        check(VirtualDisplayManager.isDisplayValid()) { "后台显示器已失效，请重新启动服务" }
        check(VirtualDisplayManager.getDisplayState() == android.view.Display.STATE_ON) {
            "后台显示器处于休眠状态，请先唤醒手机后再打开游戏"
        }
        val activities = gameActivities(packageName)
        if (activities.any { it.displayId == id && it.resumed }) return true
        // Move an existing task first: relaunching alone may leave it on the main display.
        if (isAppAlive(packageName) == 1) {
            val existing = activities.firstOrNull { it.present && it.rootTaskId != null && it.displayId != id }
            if (existing != null) {
                command("/system/bin/am", "display", "move-stack", existing.rootTaskId.toString(), id.toString())
                if (GameStateConfirmation.await(1_500L) { gameResumedOnDisplay(packageName, id) }) return true
            }
        }
        val resolved = command("/system/bin/cmd", "package", "resolve-activity", "--brief", packageName).second
            .lineSequence().map { it.trim() }.lastOrNull { it.startsWith("$packageName/") } ?: return false
        val args = mutableListOf("/system/bin/am", "start", "--display", display().toString())
        args.addAll(listOf("-f", "0x10800000", "-n", resolved))
        val (code, output) = command(*args.toTypedArray())
        val resumed = GameStateConfirmation.await(GameStateConfirmation.LAUNCH_TIMEOUT_MS) {
            gameResumedOnDisplay(packageName, id)
        }
        if (!resumed) android.util.Log.w("Mower", "游戏启动确认超时：am=$code ${output.trim()}")
        return resumed
    }
    override fun isAppAlive(packageName: String): Int {
        require(allowed(packageName)); return if (command("/system/bin/pidof", packageName).first == 0) 1 else 0
    }
    override fun isAppOnVirtualDisplay(packageName: String): Boolean {
        require(allowed(packageName))
        val id = VirtualDisplayManager.getDisplayId()
        return id > 0 && VirtualDisplayManager.isDisplayValid() &&
            gameActivities(packageName).any { it.displayId == id && it.present }
    }
    private fun gameActivities(packageName: String): List<GameActivityState> =
        GameActivityState.parse(command("/system/bin/dumpsys", "activity", "activities").second, packageName)
    private fun gameResumedOnDisplay(packageName: String, displayId: Int): Boolean =
        gameActivities(packageName).any { it.displayId == displayId && it.resumed }
}
