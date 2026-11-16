package com.aliothmoon.maameow.mower

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.aliothmoon.maameow.manager.RemoteServiceManager

/** Optional native overlay. Physical display power is restored before removing it. */
object MowerScreenSaver {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: FrameLayout? = null
    private val power = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "screen-power").apply { isDaemon = true } }
    private var manager: WindowManager? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var receiverContext: Context? = null
    @Volatile private var dismissed = false
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private val screenPower = ScreenPowerSession(
        execute = { power.execute(it) },
        setPower = ::displayPower,
        reportFailure = { AndroidSystemSettings.lastAction = it },
    )
    val manuallyOff: Boolean get() = screenPower.manuallyOff
    fun resetCycle() { dismissed = false }
    fun turnOffScreen(context: Context) {
        check(MowerService.active && !MowerService.stopping && MowerService.url != null) { "请先启动服务" }
        generation.incrementAndGet()
        watchSystemSleep(context.applicationContext)
        screenPower.turnOff(manual = true) {
            val message = "假熄屏未成功，已请求恢复屏幕及自动熄屏设置"
            AndroidSystemSettings.lastAction = message
            main.post { hide(); android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    fun show(context: Context, hardware: Boolean) {
        if (dismissed || view != null || screenPower.manuallyOff) return
        check(Settings.canDrawOverlays(context)) { "请先授予悬浮窗权限" }
        val ticket = generation.get()
        main.post {
            if (ticket != generation.get() || view != null || dismissed || MowerVisibility.visible || screenPower.manuallyOff) return@post
            val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK); isLongClickable = true }
            val hint = TextView(context).apply { text = "Mower 正在后台运行\n长按退出屏保"; setTextColor(0xff555555.toInt()); gravity = Gravity.CENTER; textSize = 14f }
            root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            root.setOnLongClickListener { dismissed = true; hide(); true }
            val wm = context.getSystemService(WindowManager::class.java)
            val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.OPAQUE)
            runCatching { wm.addView(root, params) }.onFailure { AndroidSystemSettings.lastAction = "屏保显示失败，请检查悬浮窗权限"; return@post }
            view = root; manager = wm
            if (hardware) runCatching { watchSystemSleep(context.applicationContext) }.onFailure {
                dismissed = true; hide()
                AndroidSystemSettings.lastAction = "无法监听系统休眠，已取消假熄屏"
                return@post
            }
            val shift = object : Runnable {
                override fun run() {
                    if (view !== root) return
                    hint.translationX = ((Math.random() - 0.5) * root.width * 0.4).toFloat()
                    hint.translationY = ((Math.random() - 0.5) * root.height * 0.4).toFloat()
                    main.postDelayed(this, 60_000)
                }
            }
            main.post(shift)
            if (hardware) screenPower.turnOff {
                AndroidSystemSettings.lastAction = "物理熄屏未成功，保留黑色屏保"
            }
        }
    }
    private fun watchSystemSleep(context: Context) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { dismissed = true; hide() }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver; receiverContext = context
    }
    private fun displayPower(on: Boolean): Boolean = runCatching {
        val reply = RemoteServiceManager.getInstanceOrNull()?.systemRpc(
            org.json.JSONObject().put("action", "screen_power").put("on", on).toString()) ?: return false
        val result = org.json.JSONObject(reply)
        result.optBoolean("ok") && result.optBoolean("result")
    }.getOrDefault(false)

    fun hide(automatic: Boolean = false) {
        val ticket = generation.incrementAndGet()
        val old = view
        screenPower.restore(automatic) {
            main.post {
                if (ticket == generation.get()) {
                    screenReceiver?.let { receiver -> runCatching { receiverContext?.unregisterReceiver(receiver) } }
                    screenReceiver = null; receiverContext = null
                }
                if (old != null && view === old) { runCatching { manager?.removeView(old) }; view = null; manager = null }
            }
        }
    }
}
