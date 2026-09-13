package com.aliothmoon.maameow.mower

import android.app.*
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** A temporary native diagnostic session; never starts Python, Shizuku, or a game task. */
class DiagnosticMcpService : Service() {
    companion object {
        internal data class Session(val port: Int, val token: String)
        @Volatile internal var session: Session? = null
            private set
        @Volatile internal var failure: String? = null
            private set
    }
    private var server: DiagnosticMcpServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("mower-diagnostics", "临时诊断连接", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 7010, Intent(this, MowerDiagnosticsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 7011, Intent(this, DiagnosticMcpService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(7010, Notification.Builder(this, "mower-diagnostics").setContentTitle("MCP 诊断会话已开启")
            .setContentText("只读访问日志与已保存截图 · 30 分钟后关闭").setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "关闭诊断连接", stop).build()).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != "start") { stopSelf(); return START_NOT_STICKY }
        if (server == null) {
            failure = null
            try {
                val token = MowerService.secret()
                val protocol = DiagnosticMcpProtocol(AndroidDiagnosticTools(applicationContext))
                val listener = DiagnosticMcpServer(token, protocol::handle)
                server = listener; listener.start(); session = Session(listener.port, token)
                handler.postDelayed(expire, 30 * 60 * 1000L)
            } catch (_: Exception) { failure = "无法开启本机诊断端口，请重新尝试。"; stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        session = null
        handler.removeCallbacks(expire)
        server?.close(); server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
