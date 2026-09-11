package com.aliothmoon.maameow.mower

import android.content.Context
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
    @Volatile private var dismissed = false
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var hardwarePending = false
    fun resetCycle() { dismissed = false }
    fun show(context: Context, hardware: Boolean) {
        if (dismissed || view != null) return
        check(Settings.canDrawOverlays(context)) { "请先授予悬浮窗权限" }
        val ticket = generation.get()
        main.post {
            if (ticket != generation.get() || view != null || dismissed || MowerVisibility.visible) return@post
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
            val shift = object : Runnable {
                override fun run() {
                    if (view !== root) return
                    hint.translationX = ((Math.random() - 0.5) * root.width * 0.4).toFloat()
                    hint.translationY = ((Math.random() - 0.5) * root.height * 0.4).toFloat()
                    main.postDelayed(this, 60_000)
                }
            }
            main.post(shift)
            if (hardware) power.execute {
                if (view === root && ticket == generation.get()) {
                    hardwarePending = true
                    if (!displayPower(false)) AndroidSystemSettings.lastAction = "物理熄屏未成功，保留黑色屏保"
                }
            }
        }
    }
    private fun displayPower(on: Boolean): Boolean = runCatching {
        val reply = RemoteServiceManager.getInstanceOrNull()?.systemRpc(
            org.json.JSONObject().put("action", "screen_power").put("on", on).toString()) ?: return false
        val result = org.json.JSONObject(reply)
        result.optBoolean("ok") && result.optBoolean("result")
    }.getOrDefault(false)

    fun hide() {
        generation.incrementAndGet()
        val old = view
        if (old == null && !hardwarePending) return
        // Serial power operations ensure a late power-off is followed by restoration.
        power.execute {
            if (hardwarePending) {
                if (displayPower(true)) hardwarePending = false
                else AndroidSystemSettings.lastAction = "屏幕恢复未成功，请按电源键；重新连接后台时会再次恢复"
            }
            main.post {
                if (old != null && view === old) { runCatching { manager?.removeView(old) }; view = null; manager = null }
            }
        }
    }
}
