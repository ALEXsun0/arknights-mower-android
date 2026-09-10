package com.aliothmoon.maameow.mower

import android.os.ParcelFileDescriptor
import com.aliothmoon.maameow.maa.AsstApiCallback
import com.aliothmoon.maameow.maa.MaaCoreLibrary
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipInputStream

/** Official Android component loader; versions are isolated until service restart. */
class AndroidMaaCore {
    private val root = File("/data/local/tmp/mower-android-core")
    private var installed: File? = null
    private var loaded: String? = null
    private var api: MaaCoreLibrary? = null
    private var instance: Pointer? = null
    private val events = ArrayDeque<JSONObject>()
    private var sequence = 0L
    private val callback = AsstApiCallback { msg, detail, _ ->
        synchronized(events) {
            events.add(JSONObject().put("id", ++sequence).put("msg", msg).put("details", detail ?: "{}"))
            while (events.size > 4096) events.removeFirst()
        }
    }
    @Synchronized fun install(fd: ParcelFileDescriptor, hash: String): Boolean {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        check(loaded == null || loaded == hash) { "MAA 已更新，请停止并重新启动服务" }
        root.mkdirs()
        val target = File(root, hash)
        if (File(target, ".ready").isFile) { installed = target; fd.close(); return true }
        val zip = File(root, "$hash.zip")
        val digest = MessageDigest.getInstance("SHA-256")
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input -> zip.outputStream().use { output ->
            val buffer = ByteArray(262144)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); output.write(buffer, 0, n) }
        } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "MAA 组件校验失败" }
        val stage = File(root, "$hash.install"); stage.deleteRecursively(); stage.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val dest = File(stage, entry.name)
                check(dest.canonicalPath.startsWith(stage.canonicalPath + "/"))
                if (entry.isDirectory) dest.mkdirs() else {
                    dest.parentFile?.mkdirs(); dest.outputStream().use { input.copyTo(it) }
                }
            }
        }
        check(File(stage, "libMaaCore.so").isFile && File(stage, "libMaaAndroidNativeControlUnit.so").isFile)
        File(stage, ".ready").writeText(hash); check(stage.renameTo(target)); zip.delete()
        installed = target
        return true
    }
    private fun core(): MaaCoreLibrary {
        api?.let { return it }
        val path = installed ?: error("尚未安装 MAA 组件")
        // libc++ is already loaded by our NDK-built display bridge.
        for (name in listOf("libonnxruntime.so", "libopencv_world4.so", "libMaaUtils.so", "libfastdeploy_ppocr.so", "libMaaAndroidNativeControlUnit.so")) {
            if (File(path, name).exists()) System.load(File(path, name).path)
        }
        System.setProperty("jna.tmpdir", File(root, "jna").apply { mkdirs() }.path)
        val core = Native.load(File(path, "libMaaCore.so").path, MaaCoreLibrary::class.java)
        val user = File(root, "user").apply { mkdirs() }
        check(core.AsstSetUserDir(user.path)) { "MAA 工作目录初始化失败" }
        check(core.AsstLoadResource(path.path)) { "MAA 资源加载失败，请检查 Android OCR 模型" }
        if (File(path, "cache/resource").isDirectory) check(core.AsstLoadResource(File(path, "cache").path))
        api = core; loaded = path.name
        return core
    }
    @Synchronized fun call(request: JSONObject, display: Int): Any {
        val method = request.getString("method")
        val p = request.optJSONObject("params") ?: JSONObject()
        if (method == "maa_status") return JSONObject().put("version", api?.AsstGetVersion() ?: "unloaded")
            .put("running", instance?.let { api?.AsstRunning(it) } ?: false)
        val core = core()
        if (method == "maa_prepare") {
            if (instance == null) instance = core.AsstCreateEx(callback, null) ?: error("MAA 实例创建失败")
            check(core.AsstSetInstanceOption(instance, 2, "Android"))
            val settings = JSONObject().put("library_path", p.getString("bridge_library"))
                .put("screen_resolution", JSONObject().put("width", 1920).put("height", 1080))
                .put("display_id", display).put("force_stop", false)
            check(core.AsstAsyncConnect(instance, "", "Android", settings.toString(), 1) > 0 && core.AsstConnected(instance)) { "MAA Android 控制器连接失败" }
            return core.AsstGetVersion()
        }
        val ptr = instance ?: error("MAA 尚未连接")
        return when (method) {
            "maa_reset" -> { check(!core.AsstRunning(ptr)); check(core.AsstStop(ptr)); synchronized(events) { events.clear(); sequence } }
            "maa_connected" -> core.AsstConnected(ptr)
            "maa_option" -> if (p.getInt("key") == 2) true else core.AsstSetInstanceOption(ptr, p.getInt("key"), p.getString("value"))
            "maa_append" -> { check(!core.AsstRunning(ptr)); val id = core.AsstAppendTask(ptr, p.getString("type"), p.getJSONObject("params").toString()); check(id > 0); id }
            "maa_params" -> core.AsstSetTaskParams(ptr, p.getInt("id"), p.getJSONObject("params").toString()).toInt() != 0
            "maa_start" -> core.AsstStart(ptr)
            "maa_running" -> core.AsstRunning(ptr)
            "maa_stop" -> core.AsstStop(ptr)
            "maa_tasks" -> { val ids = IntArray(256); val n = core.AsstGetTasksList(ptr, ids, ids.size.toLong()).toInt(); JSONArray(ids.take(n.coerceIn(0, ids.size))) }
            "maa_events" -> synchronized(events) {
                val after = p.getLong("after")
                check(events.isEmpty() || after >= events.first().getLong("id") - 1) { "MAA 回调缓冲区溢出，请停止任务" }
                JSONObject().put("events", JSONArray(events.filter { it.getLong("id") > after }))
            }
            else -> error("Unknown MAA operation")
        }
    }
    fun stop() { instance?.let { api?.AsstStop(it) } }
    fun running() = instance?.let { api?.AsstRunning(it) } ?: false
}
