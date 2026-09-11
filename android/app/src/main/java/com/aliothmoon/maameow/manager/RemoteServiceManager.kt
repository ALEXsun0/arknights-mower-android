package com.aliothmoon.maameow.manager

import android.content.Context
import android.content.pm.PackageManager
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.mower.BackgroundGameService
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Shizuku supplies only the privileged background display process. */
object RemoteServiceManager {
    private lateinit var appContext: Context
    @Volatile private var service: RemoteService? = null
    private val connecting = kotlinx.coroutines.sync.Mutex()
    fun initialize(context: Context) {
        appContext = context.applicationContext
        runCatching { rikka.sui.Sui.init(context.packageName) }
    }
    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        if (com.aliothmoon.maameow.mower.AndroidSystemSettings(appContext).enabled("root_backend")) return@withContext true
        if (!Shizuku.pingBinder()) return@withContext false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return@withContext true
        suspendCancellableCoroutine { continuation ->
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            listener = Shizuku.OnRequestPermissionResultListener { code, result ->
                if (code == 7001) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    if (continuation.isActive) continuation.resume(result == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            Shizuku.requestPermission(7001)
        }
    }
    fun unbind() {
        val old = service; service = null
        runCatching { old?.destroy() }
    }
    fun getInstanceOrNull(): RemoteService? = service?.takeIf { it.asBinder().isBinderAlive }
    suspend fun getInstance(): RemoteService = withContext(Dispatchers.IO) {
        connecting.lock()
        try {
            getInstanceOrNull()?.let { return@withContext it }
            val root = com.aliothmoon.maameow.mower.AndroidSystemSettings(appContext).enabled("root_backend")
            if (!root) check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "请先启动并授权 Shizuku" }
            val registry = com.aliothmoon.maameow.root.RootServiceBootstrapRegistry
            val token = com.aliothmoon.maameow.mower.MowerService.secret()
            val deferred = registry.register(token)
            val launcher = java.io.File(appContext.applicationInfo.nativeLibraryDir, "liblauncher.so")
            fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
            val arguments = listOf(launcher.path,
                "--apk=${appContext.applicationInfo.sourceDir}",
                "--process-name=${appContext.packageName}:background",
                "--debug-name=${appContext.packageName}:background",
                "--starter-class=com.aliothmoon.maameow.root.RemoteServiceStarter",
                "--token=$token", "--package=${appContext.packageName}",
                "--class=${BackgroundGameService::class.java.name}", "--uid=${android.os.Process.myUid()}",
                "--log-file=/data/local/tmp/mower-background-launch.log") +
                if (android.os.Build.VERSION.SDK_INT >= 34) listOf("--keep-root") else emptyList()
            val command = "exec " + arguments.joinToString(" ", transform = ::quote) + " </dev/null >/dev/null 2>&1"
            var remoteProcess: moe.shizuku.server.IRemoteProcess? = null
            var rootProcess: Process? = null
            try {
                check(launcher.isFile) { "缺少后台启动器，请重新安装 APK" }
                if (root) rootProcess = ProcessBuilder("su", "-c", command).start()
                else remoteProcess = moe.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder())
                    .newProcess(arrayOf("sh", "-c", command), null, null)
                val binder = awaitBackgroundService {
                    while (!deferred.isCompleted) {
                        check(if (root) rootProcess!!.isAlive else remoteProcess!!.alive()) { "后台进程启动失败，请查看诊断日志" }
                        delay(100)
                    }
                    deferred.await()
                }
                val connected = RemoteService.Stub.asInterface(binder)
                binder.linkToDeath({ if (service?.asBinder() === binder) service = null }, 0)
                service = connected
                connected
            } catch (failure: Exception) {
                // Never log the command line: it contains the single-use bootstrap token.
                runCatching { remoteProcess?.destroy() }; runCatching { rootProcess?.destroy() }
                throw failure
            } finally { registry.unregister(token) }
        } finally { connecting.unlock() }
    }
}
