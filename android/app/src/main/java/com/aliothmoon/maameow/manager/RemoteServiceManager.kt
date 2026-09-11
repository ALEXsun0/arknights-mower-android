package com.aliothmoon.maameow.manager

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.aliothmoon.maameow.BuildConfig
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.mower.BackgroundGameService
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Shizuku supplies only the privileged background display process. */
object RemoteServiceManager {
    private lateinit var args: Shizuku.UserServiceArgs
    @Volatile private var service: RemoteService? = null
    private var pending: CompletableDeferred<RemoteService>? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service = RemoteService.Stub.asInterface(binder)
            binder.linkToDeath({ service = null }, 0)
            pending?.complete(service!!)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    fun initialize(context: Context) {
        args = Shizuku.UserServiceArgs(ComponentName(context.packageName, BackgroundGameService::class.java.name))
            .daemon(false).processNameSuffix("background").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    }
    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
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
        service = null; pending = null
        Shizuku.unbindUserService(args, connection, true)
    }
    fun getInstanceOrNull(): RemoteService? = service?.takeIf { it.asBinder().isBinderAlive }
    suspend fun getInstance(): RemoteService = withContext(Dispatchers.Main) {
        getInstanceOrNull()?.let { return@withContext it }
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "请先启动并授权 Shizuku" }
        val deferred = pending?.takeIf { !it.isCompleted } ?: CompletableDeferred<RemoteService>().also {
            pending = it; Shizuku.bindUserService(args, connection)
        }
        try { withTimeout(20000) { deferred.await() } }
        catch (e: Exception) { pending = null; throw e }
    }
}
