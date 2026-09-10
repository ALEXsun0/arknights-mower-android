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
        val resolved = command("/system/bin/cmd", "package", "resolve-activity", "--brief", packageName).second
            .lineSequence().map { it.trim() }.lastOrNull { it.startsWith("$packageName/") } ?: return false
        val (code, output) = command("/system/bin/am", "start", "--display", display().toString(), "-n", resolved)
        return code == 0 && !output.contains("Error:")
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
