package com.aliothmoon.maameow.mower

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import com.aliothmoon.maameow.mower.MowerStyle.action
import com.aliothmoon.maameow.mower.MowerStyle.chrome
import com.aliothmoon.maameow.mower.MowerStyle.dp
import com.aliothmoon.maameow.mower.MowerStyle.label
import com.aliothmoon.maameow.mower.MowerStyle.surface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.*
import com.aliothmoon.maameow.manager.RemoteServiceManager
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

/** Manual login view. Opening it first stops the scheduler and MAA. */
class MowerGameActivity : Activity(), SurfaceHolder.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private var ready = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        chrome()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = surface(MowerStyle.paper, 0)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        bar.addView(action("‹  返回 WebUI") { finish() }, LinearLayout.LayoutParams(-2, dp(44)))
        bar.addView(action("游戏返回键") {
            if (ready) scope.launch(Dispatchers.IO) { runCatching { RemoteServiceManager.getInstanceOrNull()?.mowerKey(4) } }
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) })
        status = label("正在暂停调度并连接后台游戏…", 12f, MowerStyle.muted).apply { setPadding(dp(16), 0, 0, 0) }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f)); layout.addView(bar)
        preview = SurfaceView(this).apply { holder.addCallback(this@MowerGameActivity) }
        val frame = FrameLayout(this)
        frame.addView(preview, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        frame.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val scale = minOf((right - left) / 1920f, (bottom - top) / 1080f)
            val width = (1920 * scale).toInt(); val height = (1080 * scale).toInt()
            if (width > 0 && height > 0 && (preview.width != width || preview.height != height)) {
                preview.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }
        layout.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.setOnApplyWindowInsetsListener { _, insets ->
            val safe = MowerStyle.safeInsets(insets)
            bar.setPadding(safe.left + dp(16), insets.systemWindowInsetTop + dp(6),
                safe.right + dp(16), dp(6))
            layout.setPadding(0, 0, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(layout); layout.requestApplyInsets()
        scope.launch {
            while (isActive) {
                if (AndroidSystemSettings(this@MowerGameActivity).enabled("keep_screen_on")) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                delay(1000)
            }
        }
        preview.setOnTouchListener { view, event ->
            val service = RemoteServiceManager.getInstanceOrNull()
            if (!ready || service == null) return@setOnTouchListener true
            val x = (event.x * 1920f / view.width).toInt().coerceIn(0, 1919)
            val y = (event.y * 1080f / view.height).toInt().coerceIn(0, 1079)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> service.touchDown(x, y, 0)
                MotionEvent.ACTION_MOVE -> service.touchMove(x, y, 0)
                MotionEvent.ACTION_UP -> service.touchUp(x, y, 0)
                MotionEvent.ACTION_CANCEL -> service.touchCancel()
            }
            true
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val url = MowerService.url ?: error("请先启动 Mower")
                    val uri = android.net.Uri.parse(url)
                    val connection = URL("http://127.0.0.1:${uri.port}/stop").openConnection() as HttpURLConnection
                    connection.setRequestProperty("token", uri.getQueryParameter("token"))
                    connection.connectTimeout = 5000; connection.readTimeout = 20000
                    try { check(connection.inputStream.bufferedReader().use { it.readText().trim() } == "true") { "调度尚未停止，请稍后重试" } }
                    finally { connection.disconnect() }
                    RemoteServiceManager.getInstanceOrNull()?.maaRpc("{\"method\":\"maa_stop\"}")
                    MowerService.manual = true
                    (MowerService.engine ?: error("引擎服务未启动")).openGame()
                }
                ready = true
                attachSurface()
                status.text = "调度已暂停，可登录或操作游戏；返回 WebUI 后手动启动调度"
            } catch (e: Exception) { status.text = e.message }
        }
    }

    private fun attachSurface() {
        if (ready && preview.holder.surface.isValid) RemoteServiceManager.getInstanceOrNull()?.setMonitorSurface(preview.holder.surface)
    }
    override fun surfaceCreated(holder: SurfaceHolder) = attachSurface()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = attachSurface()
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        RemoteServiceManager.getInstanceOrNull()?.let { it.touchCancel(); it.setMonitorSurface(null) }
    }
    override fun onDestroy() { MowerService.manual = false; scope.cancel(); super.onDestroy() }
}
