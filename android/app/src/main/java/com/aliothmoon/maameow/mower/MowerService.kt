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
import org.tukaani.xz.XZInputStream

/** Foreground owner of Python, bridge, wake lock and their cleanup. */
class MowerService : Service() {
    companion object {
        @Volatile var active = false
        @Volatile var stopping = false
        @Volatile var cpuAwake = false
        @Volatile var lanEnabled = false
        @Volatile var manual = false
        @Volatile var previewing = false
        @Volatile var unlocking = false
        @Volatile var engine: MowerBridge? = null
        @Volatile var monitor: MowerRuntimeMonitor? = null
        @Volatile var message = "尚未启动"
        @Volatile internal var installProgress: RuntimeInstallProgress? = null
        @Volatile var url: String? = null
        fun secret(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runtimeJob: Job? = null
    private var maintenanceJob: Job? = null
    private var ownsRuntime = false
    private var bridge: MowerBridge? = null
    private var python: Process? = null
    private var runtimeNetwork: RuntimeNetwork? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var failed = false
    private var stage = "环境检查"
    private val startupReport = mutableListOf<String>()
    private fun startupStage(value: String) {
        stage = value
        message = value
        startupReport += value
    }
    private fun saveStartupReport(result: String) {
        runCatching { File(filesDir, "startup-check.txt").writeText(
            "${java.time.Instant.now()}\n" + startupReport.joinToString("\n") + "\n$result\n") }
    }
    private val settingListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "keep_cpu_awake") updateWakeLock()
    }
    @Synchronized private fun updateWakeLock() {
        val enabled = active && AndroidSystemSettings(this).enabled("keep_cpu_awake")
        if (enabled) {
            if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mower:runtime")
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } else if (wakeLock?.isHeld == true) wakeLock?.release()
        cpuAwake = wakeLock?.isHeld == true
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("android-system", 0).registerOnSharedPreferenceChangeListener(settingListener)
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
        if (stopping) { stopSelf(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        active = true
        installProgress = null
        getSharedPreferences("runtime-wake", 0).edit().putBoolean("requested", true).apply()
        ownsRuntime = true
        runtimeJob = scope.launch {
            try {
                (application as MaaApplication).awaitReady()
                updateWakeLock()
                startupStage("正在检查授权、存储与端口")
                check(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "当前设备不支持 ARM64 运行环境" }
                val permissions = PermissionChecks.inspect(this@MowerService)
                startupReport += permissions.summary
                permissions.issues.firstOrNull { it.required }?.let { error("${it.description}，可在主页直接处理") }
                val dataDirectory = File(filesDir, "mower-data").apply { mkdirs() }
                check(dataDirectory.isDirectory && dataDirectory.canWrite() && cacheDir.canWrite()) { "应用数据目录不可写，请检查手机存储" }
                AppearancePreferences.sync(this@MowerService)
                StartupChecks.storage(filesDir.usableSpace, false)
                StartupChecks.nativeRuntime(File(applicationInfo.nativeLibraryDir))
                val network = getSharedPreferences("network", 0)
                val webConnection = WebConnectionConfig.parse(network.getString("port", "") ?: "", network.getString("token", "") ?: "")
                lanEnabled = network.getBoolean("lan", false)
                webConnection.availablePort(lanEnabled)
                startupStage("正在检查 Python 运行环境")
                installRuntime()
                startupStage("正在检查 Python 网络设置")
                runtimeNetwork = RuntimeNetwork(this@MowerService, File(filesDir, "rootfs"))
                startupReport += runtimeNetwork!!.start()
                startupStage("正在准备 MAA 组件")
                val maaData = File(filesDir, "mower-data").apply { mkdirs() }
                val componentFormat = File(maaData, "maa-component-format")
                if (!File(maaData, "maa-component.zip").exists() || !componentFormat.exists()) {
                    for (name in listOf("maa-component.zip", "maa-component.sha256")) assets.open(name).use { input -> File(maaData, name).outputStream().use { input.copyTo(it) } }
                    File(maaData, "maa/.mower-android.json").delete()
                    componentFormat.writeText("ncnn-v1")
                }
                check(File(maaData, "maa-component.zip").length() > 0 &&
                    runCatching { File(maaData, "maa-component.sha256").readText().trim().matches(Regex("[a-f0-9]{64}")) }.getOrDefault(false)) {
                    "MAA 组件或校验文件不完整，请查看诊断日志"
                }
                ensureActive()
                // Recheck after the potentially long installation; another app may have bound it.
                val savedConnection = WebConnectionPreferences.resolve(network, ::secret)
                lanEnabled = savedConnection.lan
                val webPort = savedConnection.port.toInt()
                startupStage("正在连接后台服务（最长 20 秒）")
                RemoteServiceManager.getInstance()
                val bridgeToken = secret()
                val webToken = savedConnection.token
                val localBridge = MowerBridge(this@MowerService, bridgeToken)
                bridge = localBridge
                engine = localBridge
                localBridge.start()
                val runtimeMonitor = MowerRuntimeMonitor(this@MowerService)
                monitor = runtimeMonitor
                maintenanceJob = scope.launch { while (isActive) {
                    try { runInterruptible { localBridge.maintainSystem(); runtimeMonitor.tick() } }
                    catch (failure: Exception) { ensureActive(); AndroidSystemSettings.lastAction = "后台监测暂不可用，请检查服务连接" }
                    delay(5000)
                } }
                val root = File(filesDir, "rootfs")
                val data = File(filesDir, "mower-data").apply { mkdirs() }
                File(data, "runtime-python.pid").delete()
                val native = File(applicationInfo.nativeLibraryDir)
                val exec = File(filesDir, "exec").apply { mkdirs() }
                val talloc = File(exec, "libtalloc.so.2")
                talloc.delete()
                Os.symlink(File(native, "libtalloc.so").path, talloc.path)
                val args = listOf(File(native, "libproot.so").path, "--kill-on-exit", "-0", "-r", root.path,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${data.path}:/mower-data", "-w", "/mower",
                    "/usr/bin/env", "-i", "HOME=/mower-data", "PATH=/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8", "TZ=${java.util.TimeZone.getDefault().id}",
                    "MOWER_ANDROID=1", "MOWER_APK_CODE=${com.aliothmoon.maameow.BuildConfig.VERSION_CODE}", "MOWER_WEB_BIND=${if (lanEnabled) "0.0.0.0" else "127.0.0.1"}", "MOWER_DATA_DIR=/mower-data", "MOWER_BRIDGE_PORT=${localBridge.port}",
                    "MOWER_BRIDGE_TOKEN=$bridgeToken", "MOWER_WEB_TOKEN=$webToken", "MOWER_WEB_PORT=$webPort",
                    "OPENBLAS_NUM_THREADS=2", "OMP_NUM_THREADS=2", "PYTHONUNBUFFERED=1", "/usr/local/bin/python", "-m", "mower_android.launcher")
                val builder = ProcessBuilder(args).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(File(filesDir, "python.log")))
                builder.environment().apply {
                    put("LD_LIBRARY_PATH", "${native.path}:${exec.path}")
                    put("PROOT_TMP_DIR", cacheDir.path)
                    put("PROOT_LOADER", File(native, "libproot-loader.so").path)
                }
                val pythonLog = File(filesDir, "python.log")
                val logOffset = pythonLog.length()
                startupStage("Python 正在启动")
                python = builder.start()
                repeat(120) {
                    if (python?.isAlive != true) error(StartupChecks.pythonFailure(pythonLog, logOffset) ?: "Python 已退出，请查看诊断日志")
                    val ready = runCatching {
                        (java.net.URL("http://127.0.0.1:$webPort/software-update/info").openConnection() as java.net.HttpURLConnection).run {
                            setRequestProperty("token", webToken)
                            connectTimeout = 400; readTimeout = 400
                            try { responseCode == 200 && org.json.JSONObject(inputStream.bufferedReader().use { it.readText() }).optString("platform") == "android" }
                            finally { disconnect() }
                        }
                    }.getOrDefault(false)
                    if (ready) {
                        val verifiedRuntime = StartupChecks.commitRuntime(root)
                        scope.launch {
                            runCatching { StartupChecks.cleanupRuntime(root, verifiedRuntime) }.onFailure {
                                android.util.Log.w("Mower", "旧运行环境暂未清理，下次启动后重试", it)
                            }
                        }
                        url = "http://127.0.0.1:$webPort/?token=$webToken"
                        message = "Mower 已运行；可在 WebUI 配置和启动调度"
                        saveStartupReport("启动成功；WebUI 就绪")
                        stage = "运行中"
                        // Keep credentials only in app-private storage, for diagnostics on debug builds.
                        File(filesDir, "runtime-session.json").writeText(org.json.JSONObject()
                            .put("port", localBridge.port).put("token", bridgeToken).put("web_url", url).toString())
                        val result = runInterruptible { python!!.waitFor() }
                        ensureActive()
                        if (active) error("Python 进程退出：$result")
                        return@launch
                    }
                    delay(500)
                }
                error(StartupChecks.pythonFailure(pythonLog, logOffset) ?: "Python 启动超时，请查看诊断日志")
            } catch (e: Exception) {
                ensureActive()
                failed = true
                installProgress = null
                message = "$stage：${e.message ?: "启动失败"}"
                saveStartupReport(message)
                runCatching { File(filesDir, "python.log").appendText("\n${e.stackTraceToString()}\n") }
                MowerNotifications.event(this@MowerService, message, true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun installRuntime() {
        val root = File(filesDir, "rootfs")
        val stamp = assets.open("python-runtime.sha256").bufferedReader().use { it.readText().trim() }
        val marker = File(root, ".mower-runtime")
        val backup = File(filesDir, "rootfs-backup")
        if (StartupChecks.recoverRuntime(root, backup)) startupReport += "上次环境未能启动，已回退至原运行环境；安装新版 APK 后重新尝试更新"
        if (!root.exists() && backup.exists()) check(backup.renameTo(root)) { "恢复上次运行环境失败，请检查存储" }
        val missing = StartupChecks.incompleteRuntime(root)
        val rejected = File(filesDir, "rootfs-rejected-version")
        if (missing.isEmpty() && (runCatching { marker.readText() == stamp }.getOrDefault(false) ||
                runCatching { rejected.readText() == stamp }.getOrDefault(false))) return
        if (missing.isNotEmpty() && marker.exists()) startupReport += "运行环境不完整，自动修复：${missing.joinToString()}"
        scope.ensureActive()
        File(filesDir, "rootfs-install").deleteRecursively()
        File(cacheDir, "python-runtime.zip.xz").delete()
        StartupChecks.storage(filesDir.usableSpace, true)
        startupStage(if (marker.exists()) "正在更新或修复 Python 环境" else "正在安装 Python 环境")
        fun progress(label: String, percent: Int) {
            installProgress = RuntimeInstallProgress(label, percent)
            message = "$label $percent%"
        }
        progress("校验运行包", 0)
        val archive = File(cacheDir, "python-runtime.zip.xz")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val archiveSize = assets.openFd("python-runtime.zip.xz").use { it.length }
        assets.open("python-runtime.zip.xz").use { raw -> InstallProgressInput(raw, archiveSize) { progress("校验运行包", it) }.use { input -> archive.outputStream().use { output ->
            val buffer = ByteArray(262144)
            while (true) { scope.ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); output.write(buffer, 0, n) }
        } } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == stamp) { "Python 运行包校验失败" }
        val temp = File(filesDir, "rootfs-install")
        temp.deleteRecursively(); temp.mkdirs()
        // Symlinks are restored LAST from a dedicated manifest, never traversed while extracting.
        progress("解压运行环境", 0)
        ZipInputStream(XZInputStream(InstallProgressInput(archive.inputStream().buffered(), archive.length()) {
            progress("解压运行环境", it)
        }, 65536)).use { zip ->
            val buffer = ByteArray(262144)
            while (true) {
                scope.ensureActive()
                val entry = zip.nextEntry ?: break
                val dest = File(temp, entry.name)
                check(dest.canonicalPath.startsWith(temp.canonicalPath + "/")) { "Invalid runtime path" }
                if (entry.isDirectory) dest.mkdirs() else {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output ->
                        while (true) {
                            scope.ensureActive()
                            val n = zip.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                    Os.chmod(dest.path, 493)
                }
            }
        }
        val linksFile = File(temp, ".symlinks.json")
        progress("完成环境安装", 99)
        val links = org.json.JSONObject(linksFile.readText())
        for (name in links.keys()) {
            scope.ensureActive()
            val dest = File(temp, name)
            check(dest.canonicalPath.startsWith(temp.canonicalPath + "/"))
            dest.parentFile?.mkdirs()
            Os.symlink(links.getString(name), dest.path)
        }
        scope.ensureActive()
        linksFile.delete()
        check(StartupChecks.incompleteRuntime(temp).isEmpty()) { "解压后的关键文件不完整，请重试或覆盖安装 APK" }
        File(temp, ".mower-runtime").writeText(stamp)
        StartupChecks.activateRuntime(temp, root, backup)
        archive.delete()
        progress("环境安装完成", 100)
        installProgress = null
    }

    override fun onDestroy() {
        getSharedPreferences("android-system", 0).unregisterOnSharedPreferenceChangeListener(settingListener)
        if (!ownsRuntime) { scope.cancel(); super.onDestroy(); return }
        stopping = true
        getSharedPreferences("runtime-wake", 0).edit().putBoolean("requested", false).apply()
        MowerWakeReceiver.cancel(this); MowerScreenSaver.hide(); monitor = null
        url = null; engine = null; lanEnabled = false; manual = false; unlocking = false; previewing = false
        installProgress = null
        if (!failed) message = "正在停止服务…"
        scope.cancel()
        File(filesDir, "runtime-session.json").delete()
        kotlin.concurrent.thread(name = "mower-cleanup") {
            try {
                // Finish cancelled startup before releasing its files, process or Binder.
                runBlocking { runtimeJob?.join(); maintenanceJob?.join() }
                runtimeNetwork?.close()
                val pidFile = File(filesDir, "mower-data/runtime-python.pid")
                val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
                fun ownsPython(): Boolean = pid != null && runCatching {
                    File("/proc/$pid/status").readLines().any {
                        it.startsWith("Uid:") && it.split(Regex("\\s+"))[1].toInt() == android.os.Process.myUid()
                    } && File("/proc/$pid/comm").readText().trim().startsWith("python")
                }.getOrDefault(false)
                if (ownsPython()) {
                    android.os.Process.sendSignal(pid!!, 15)
                    Thread.sleep(700)
                    if (ownsPython()) android.os.Process.sendSignal(pid, 9)
                }
                python?.let {
                    it.destroy()
                    if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
                }
                pidFile.delete()
                bridge?.close()
                runCatching { runBlocking(Dispatchers.Main) { RemoteServiceManager.unbind() } }
            } finally {
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                cpuAwake = false
                if (!failed) message = "已停止"
                active = false
                stopping = false
            }
        }
        super.onDestroy()
    }
}
