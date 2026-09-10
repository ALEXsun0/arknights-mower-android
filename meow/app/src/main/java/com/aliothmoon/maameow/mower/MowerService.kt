package com.aliothmoon.maameow.mower

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.system.Os
import com.aliothmoon.maameow.MaaApplication
import com.aliothmoon.maameow.manager.RemoteServiceManager
import kotlinx.coroutines.*
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipInputStream

/** Foreground owner of Python, bridge, wake lock and their cleanup. */
class MowerService : Service() {
    companion object {
        @Volatile var active = false
        @Volatile var message = "尚未启动"
        @Volatile var url: String? = null
        fun secret(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: MowerBridge? = null
    private var python: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("mower-runtime", "Mower 运行服务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MowerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MowerService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(7001, Notification.Builder(this, "mower-runtime").setContentTitle("Mower Android")
            .setContentText("Python 与后台游戏服务运行中").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "停止", stop).build()).setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        active = true
        scope.launch {
            try {
                (application as MaaApplication).awaitReady()
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mower:runtime").also { it.acquire() }
                message = "正在初始化 Python 环境，首次启动需要解压"
                installRuntime()
                ensureActive()
                RemoteServiceManager.bind()
                val bridgeToken = secret()
                val webToken = secret()
                val localBridge = MowerBridge(this@MowerService, bridgeToken)
                bridge = localBridge
                localBridge.start()
                val webPort = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
                val root = File(filesDir, "rootfs")
                val data = File(filesDir, "mower-data").apply { mkdirs() }
                val native = File(applicationInfo.nativeLibraryDir)
                val exec = File(filesDir, "exec").apply { mkdirs() }
                val talloc = File(exec, "libtalloc.so.2")
                if (!talloc.exists()) Os.symlink(File(native, "libtalloc.so").path, talloc.path)
                val args = listOf(File(native, "libproot.so").path, "--kill-on-exit", "-0", "-r", root.path,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${data.path}:/mower-data", "-w", "/mower",
                    "/usr/bin/env", "-i", "HOME=/mower-data", "PATH=/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8",
                    "MOWER_ANDROID=1", "MOWER_DATA_DIR=/mower-data", "MOWER_BRIDGE_PORT=${localBridge.port}",
                    "MOWER_BRIDGE_TOKEN=$bridgeToken", "MOWER_WEB_TOKEN=$webToken", "MOWER_WEB_PORT=$webPort",
                    "OPENBLAS_NUM_THREADS=2", "OMP_NUM_THREADS=2", "PYTHONUNBUFFERED=1", "/usr/local/bin/python", "-m", "mower_android.launcher")
                val builder = ProcessBuilder(args).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(File(filesDir, "python.log")))
                builder.environment().apply {
                    put("LD_LIBRARY_PATH", "${native.path}:${exec.path}")
                    put("PROOT_TMP_DIR", cacheDir.path)
                    put("PROOT_LOADER", File(native, "libproot-loader.so").path)
                }
                python = builder.start()
                message = "Python 正在启动"
                repeat(120) {
                    if (python?.isAlive != true) error("Python 已退出，请查看诊断日志")
                    val ready = runCatching {
                        (java.net.URL("http://127.0.0.1:$webPort/").openConnection() as java.net.HttpURLConnection).run {
                            connectTimeout = 400; readTimeout = 400
                            try { responseCode == 200 } finally { disconnect() }
                        }
                    }.getOrDefault(false)
                    if (ready) {
                        url = "http://127.0.0.1:$webPort/?token=$webToken"
                        message = "Mower 已运行；可在 WebUI 配置和启动调度"
                        // Keep credentials only in app-private storage, for diagnostics on debug builds.
                        File(filesDir, "runtime-session.json").writeText(org.json.JSONObject()
                            .put("port", localBridge.port).put("token", bridgeToken).put("web_url", url).toString())
                        val result = python!!.waitFor()
                        if (active) error("Python 进程退出：$result")
                        return@launch
                    }
                    delay(500)
                }
                error("Python 启动超时，请查看诊断日志")
            } catch (e: Exception) {
                message = e.message ?: "启动失败"
                File(filesDir, "python.log").appendText("\n${e.stackTraceToString()}\n")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun installRuntime() {
        val root = File(filesDir, "rootfs")
        val stamp = assets.open("python-runtime.sha256").bufferedReader().use { it.readText().trim() }
        val marker = File(root, ".mower-runtime")
        if (marker.exists() && marker.readText() == stamp) return
        val archive = File(cacheDir, "python-runtime.zip")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        assets.open("python-runtime.zip").use { input -> archive.outputStream().use { output ->
            val buffer = ByteArray(262144)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); output.write(buffer, 0, n) }
        } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == stamp) { "Python 运行包校验失败" }
        val temp = File(filesDir, "rootfs-install")
        temp.deleteRecursively(); temp.mkdirs()
        // Symlinks are restored LAST from a dedicated manifest, never traversed while extracting.
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val dest = File(temp, entry.name)
                check(dest.canonicalPath.startsWith(temp.canonicalPath + "/")) { "Invalid runtime path" }
                if (entry.isDirectory) dest.mkdirs() else {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { zip.copyTo(it) }
                    Os.chmod(dest.path, 493)
                }
            }
        }
        val linksFile = File(temp, ".symlinks.json")
        val links = org.json.JSONObject(linksFile.readText())
        for (name in links.keys()) {
            val dest = File(temp, name)
            check(dest.canonicalPath.startsWith(temp.canonicalPath + "/"))
            dest.parentFile?.mkdirs()
            Os.symlink(links.getString(name), dest.path)
        }
        linksFile.delete()
        File(temp, ".mower-runtime").writeText(stamp)
        root.deleteRecursively()
        check(temp.renameTo(root))
        archive.delete()
    }

    override fun onDestroy() {
        active = false; url = null
        python?.destroy()
        bridge?.close()
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        File(filesDir, "runtime-session.json").delete()
        super.onDestroy()
    }
}
