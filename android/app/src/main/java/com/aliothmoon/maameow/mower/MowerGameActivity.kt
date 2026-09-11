package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.*
import android.widget.*
import com.aliothmoon.maameow.ITouchEventCallback
import com.aliothmoon.maameow.manager.RemoteServiceManager
import com.aliothmoon.maameow.mower.MowerStyle.action
import com.aliothmoon.maameow.mower.MowerStyle.chrome
import com.aliothmoon.maameow.mower.MowerStyle.dp
import com.aliothmoon.maameow.mower.MowerStyle.label
import com.aliothmoon.maameow.mower.MowerStyle.surface
import kotlinx.coroutines.*

/** Observation never interrupts scheduling. Manual input requires an explicit pause. */
class MowerGameActivity : Activity(), SurfaceHolder.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var preview: SurfaceView
    private lateinit var touches: TouchLayer
    private lateinit var status: TextView
    private lateinit var bar: LinearLayout
    private lateinit var manualButton: Button
    private var ready = false
    private var manual = false
    private var targetWidth = 1920
    private var targetHeight = 1080
    private val callback = object : ITouchEventCallback.Stub() {
        override fun onCallback(x: Int, y: Int, type: Int, contact: Int) {
            runOnUiThread {
                if (!::touches.isInitialized || !ready) return@runOnUiThread
                if (type == MotionEvent.ACTION_CANCEL) touches.points.clear()
                else if (type == MotionEvent.ACTION_UP || type == MotionEvent.ACTION_POINTER_UP) touches.points.remove(contact)
                else touches.points[contact] = x.toFloat() / targetWidth to y.toFloat() / targetHeight
                touches.invalidate()
            }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); chrome()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = surface(MowerStyle.paper, 0); setPadding(dp(16), dp(6), dp(16), dp(6)) }
        bar.addView(action("‹ 返回 WebUI") { finish() }, LinearLayout.LayoutParams(-2, dp(44)))
        manualButton = action("手动操作") {
            if (!ready) return@action
            if (manual) { manual = false; MowerService.manual = false; manualButton.text = "手动操作"; updateStatus(); return@action }
            manualButton.isEnabled = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        NativeRuntimeClient.stopTasks()
                        RemoteServiceManager.getInstanceOrNull()?.maaRpc("{\"method\":\"maa_stop\"}")
                    }
                    manual = true; MowerService.manual = true; manualButton.text = "结束手动操作"; updateStatus()
                } catch (e: Exception) { status.text = e.message }
                finally { manualButton.isEnabled = true }
            }
        }
        bar.addView(manualButton, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) })
        bar.addView(action("游戏返回") { if (manual) scope.launch(Dispatchers.IO) { runCatching { RemoteServiceManager.getInstanceOrNull()?.mowerKey(4) } } }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) })
        bar.addView(action("小窗") { enterPip() }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) })
        status = label("正在连接后台画面…", 12f, MowerStyle.muted).apply { setPadding(dp(12), 0, 0, 0) }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f)); layout.addView(bar)
        preview = SurfaceView(this).apply { holder.addCallback(this@MowerGameActivity) }
        touches = TouchLayer()
        val frame = FrameLayout(this)
        frame.addView(preview, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        frame.addView(touches, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        frame.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val scale = minOf((right - left) / 1920f, (bottom - top) / 1080f)
            val width = (1920 * scale).toInt(); val height = (1080 * scale).toInt()
            if (width > 0 && height > 0 && (preview.width != width || preview.height != height)) {
                preview.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
                touches.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }
        layout.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.setOnApplyWindowInsetsListener { _, insets ->
            val safe = MowerStyle.safeInsets(insets)
            bar.setPadding(safe.left + dp(16), safe.top + dp(6), safe.right + dp(16), dp(6))
            layout.setPadding(0, 0, 0, if (isInPictureInPictureMode) 0 else insets.systemWindowInsetBottom); insets
        }
        setContentView(layout); layout.requestApplyInsets()
        preview.setOnTouchListener { view, event ->
            val service = RemoteServiceManager.getInstanceOrNull()
            if (!ready || !manual || service == null || isInPictureInPictureMode) return@setOnTouchListener true
            fun point(index: Int) = ((event.getX(index) * 1920f / view.width).toInt().coerceIn(0, 1919)) to
                ((event.getY(index) * 1080f / view.height).toInt().coerceIn(0, 1079))
            runCatching {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> { val i = event.actionIndex; val (x, y) = point(i); service.touchDown(x, y, event.getPointerId(i)) }
                    MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) { val (x, y) = point(i); service.touchMove(x, y, event.getPointerId(i)) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { val i = event.actionIndex; val (x, y) = point(i); service.touchUp(x, y, event.getPointerId(i)) }
                    MotionEvent.ACTION_CANCEL -> service.touchCancel()
                }
            }.onFailure { ready = false; status.text = "输入连接中断，请返回后重新连接" }
            true
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(MowerService.url != null) { "请先启动 Mower 服务" }
                    val engine = MowerService.engine ?: error("后台服务未启动")
                    engine.openGame()
                    val game = engine.gameState(); targetWidth = game.getInt("width"); targetHeight = game.getInt("height")
                }
                ready = true; MowerService.previewing = true; attachSurface(); updateStatus(); updatePip()
            } catch (e: Exception) { status.text = e.message }
            while (isActive) {
                val settings = AndroidSystemSettings(this@MowerGameActivity)
                if (settings.enabled("keep_screen_on")) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                touches.visibility = if (settings.enabled("show_touch") && !isInPictureInPictureMode) View.VISIBLE else View.GONE
                if (ready) updateStatus()
                delay(1000)
            }
        }
    }
    private fun updateStatus() {
        val fps = MowerService.monitor?.gameFps ?: -1f
        status.text = (if (manual) "调度已暂停；返回后手动启动" else "仅查看 · 调度继续运行") + if (fps >= 0) " · ${fps.toInt()} FPS" else ""
    }
    private fun supportedPip() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private fun params(): PictureInPictureParams = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).apply {
        if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(ready && AndroidSystemSettings(this@MowerGameActivity).enabled("auto_pip"))
    }.build()
    private fun updatePip() { if (supportedPip()) runCatching { setPictureInPictureParams(params()) } }
    private fun enterPip() { if (ready && supportedPip()) runCatching { enterPictureInPictureMode(params()) }.onFailure { status.text = "系统未允许画中画，请检查应用权限" } }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT < 31 && AndroidSystemSettings(this).enabled("auto_pip")) enterPip() }
    override fun onPictureInPictureModeChanged(inPip: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(inPip, config); bar.visibility = if (inPip) View.GONE else View.VISIBLE
        if (inPip) MowerService.previewing = true
        window.decorView.requestApplyInsets()
    }
    private fun attachSurface() {
        if (!ready || !preview.holder.surface.isValid) return
        RemoteServiceManager.getInstanceOrNull()?.let { it.setMonitorSurface(preview.holder.surface); it.setTouchMonitor(callback) }
    }
    override fun surfaceCreated(holder: SurfaceHolder) = attachSurface()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = attachSurface()
    override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { RemoteServiceManager.getInstanceOrNull()?.let { it.touchCancel(); it.setMonitorSurface(null); it.setTouchMonitor(null) } } }
    override fun onResume() { super.onResume(); if (ready) { MowerService.previewing = true; attachSurface(); updatePip() } }
    override fun onStop() { if (!isInPictureInPictureMode) MowerService.previewing = false; super.onStop() }
    override fun onDestroy() { if (manual) MowerService.manual = false; MowerService.previewing = false; scope.cancel(); super.onDestroy() }
    private inner class TouchLayer : View(this@MowerGameActivity) {
        val points = linkedMapOf<Int, Pair<Float, Float>>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99ffca28.toInt() }
        override fun onDraw(canvas: Canvas) { points.values.forEach { (x, y) -> canvas.drawCircle(x * width, y * height, dp(10).toFloat(), paint) } }
    }
}
