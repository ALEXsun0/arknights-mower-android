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
    init {
        if (Process.myUid() == 0) {
            // Use the shell identity expected by Android's display attribution checks.
            android.system.Os.setgid(2000)
            android.system.Os.setuid(2000)
        }
        Workarounds.apply(); current = this
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

    private var fullscreen = false
    /** Fixed operations only: never expose an arbitrary shell command to the WebUI. */
    @Synchronized override fun systemRpc(request: String): String = try {
        val p = org.json.JSONObject(request)
        val result: Any = when (p.getString("action")) {
            "display_options" -> { fullscreen = p.getBoolean("fullscreen"); true }
            "wake" -> { check(command("/system/bin/input", "-d", "0", "keyevent", "224").first == 0); true }
            "dismiss_keyguard" -> { check(command("/system/bin/wm", "dismiss-keyguard").first == 0); true }
            "audio_get", "audio_set" -> {
                val pkg = p.getString("package"); require(allowed(pkg))
                if (p.getString("action") == "audio_set") {
                    val mode = p.getString("mode")
                    require(mode in setOf("allow", "ignore", "deny", "default", "foreground"))
                    val changed = command("/system/bin/cmd", "appops", "set", "--user", "0", pkg, "PLAY_AUDIO", mode)
                    check(changed.first == 0 && !changed.second.contains("Error")) { "系统拒绝修改游戏声音权限" }
                }
                val (code, output) = command("/system/bin/cmd", "appops", "get", "--user", "0", pkg, "PLAY_AUDIO")
                check(code == 0) { "无法读取游戏声音权限" }
                val mode = if (output.contains("No operations")) "default" else
                    Regex("PLAY_AUDIO: (allow|ignore|deny|default|foreground)").find(output)?.groupValues?.get(1)
                        ?: error("无法识别系统声音权限状态")
                org.json.JSONObject().put("mode", mode)
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
    override fun destroy() { runCatching { stopVirtualDisplay() }; exitProcess(0) }
    override fun setVirtualDisplayMode(mode: Int) = mode == 2
    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) {
        require(width == 1920 && height == 1080); VirtualDisplayManager.setResolution(width, height, dpi)
    }
    override fun startVirtualDisplay() = VirtualDisplayManager.start()
    override fun stopVirtualDisplay() { maa.stop(); InputControlUtils.cancel(VirtualDisplayManager.getDisplayId()); VirtualDisplayManager.stop() }
    override fun setMonitorSurface(surface: Surface?) {
        VirtualDisplayManager.setMonitorSurface(surface); NativeBridgeLib.setPreviewSurface(surface)
    }
    override fun touchDown(x: Int, y: Int, contact: Int) { check(InputControlUtils.down(x, y, contact, display())) }
    override fun touchMove(x: Int, y: Int, contact: Int) { check(InputControlUtils.move(x, y, contact, display())) }
    override fun touchUp(x: Int, y: Int, contact: Int) { check(InputControlUtils.up(x, y, contact, display())) }
    override fun touchCancel() { InputControlUtils.cancel(display()) }
    override fun mowerFrame(): ParcelFileDescriptor? {
        val bitmap = NativeBridgeLib.getFrameBufferBitmap() ?: return null
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
        if (!launch) return command("/system/bin/am", "force-stop", packageName).first == 0
        if (isAppOnVirtualDisplay(packageName)) return true
        // Move an existing task first: relaunching alone may leave it on the main display.
        if (isAppAlive(packageName) == 1) {
            var task: String? = null
            for (line in command("/system/bin/dumpsys", "activity", "activities").second.lineSequence()) {
                Regex("\\* Task\\{[^#]+#(\\d+)").find(line)?.let { match ->
                    task = Regex("rootTaskId=(\\d+)").find(line)?.groupValues?.get(1) ?: match.groupValues[1]
                }
                if (line.contains("ActivityRecord{") && line.contains("$packageName/") && task != null) {
                    command("/system/bin/am", "display", "move-stack", task!!, display().toString())
                    Thread.sleep(250)
                    if (isAppOnVirtualDisplay(packageName)) return true
                    break
                }
            }
        }
        val resolved = command("/system/bin/cmd", "package", "resolve-activity", "--brief", packageName).second
            .lineSequence().map { it.trim() }.lastOrNull { it.startsWith("$packageName/") } ?: return false
        val args = mutableListOf("/system/bin/am", "start", "--display", display().toString())
        if (fullscreen) args.addAll(listOf("--windowingMode", "1"))
        args.addAll(listOf("-f", "0x10800000", "-n", resolved))
        val (code, output) = command(*args.toTypedArray())
        if (code != 0 || output.contains("Error:")) return false
        repeat(8) { if (isAppOnVirtualDisplay(packageName)) return true; Thread.sleep(250) }
        return false
    }
    override fun isAppAlive(packageName: String): Int {
        require(allowed(packageName)); return if (command("/system/bin/pidof", packageName).first == 0) 1 else 0
    }
    override fun isAppOnVirtualDisplay(packageName: String): Boolean {
        require(allowed(packageName))
        val id = display(); val dump = command("/system/bin/dumpsys", "activity", "activities").second
        var current = -1
        for (line in dump.lineSequence()) {
            Regex("Display #(\\d+)").find(line)?.let { current = it.groupValues[1].toInt() }
            if (current == id && line.contains("ActivityRecord{") && line.contains("$packageName/")) return true
        }
        return false
    }
}
