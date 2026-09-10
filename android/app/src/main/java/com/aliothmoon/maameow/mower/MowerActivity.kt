package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.aliothmoon.maameow.MaaApplication
import com.aliothmoon.maameow.mower.MowerStyle.action
import com.aliothmoon.maameow.mower.MowerStyle.chrome
import com.aliothmoon.maameow.mower.MowerStyle.dp
import com.aliothmoon.maameow.mower.MowerStyle.label
import com.aliothmoon.maameow.mower.MowerStyle.surface
import com.aliothmoon.maameow.mower.MowerStyle.iconSurface
import kotlinx.coroutines.*

class MowerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var layout: LinearLayout
    private lateinit var status: TextView
    private lateinit var dot: TextView
    private lateinit var toggle: Button
    private lateinit var web: WebView
    private lateinit var landing: View
    private var loaded: String? = null
    private val update = object : Runnable {
        override fun run() {
            status.text = MowerService.message
            val url = MowerService.url
            toggle.text = if (MowerService.active) "停止服务" else "启动服务"
            dot.setTextColor(if (url != null) MowerStyle.green else MowerStyle.muted)
            if (url != null && url != loaded) { loaded = url; web.loadUrl(url) }
            if (url == null && loaded != null) { loaded = null; web.loadUrl("about:blank") }
            landing.visibility = if (url == null) View.VISIBLE else View.GONE
            web.visibility = if (url == null) View.GONE else View.VISIBLE
            if (url != null) web.evaluateJavascript("document.documentElement.dataset.mowerTheme || 'light'") { value ->
                val dark = value == "\"dark\""
                if (MowerStyle.dark != dark) {
                    MowerStyle.dark = dark; MowerStyle.applyTheme(layout); chrome(); updateLauncher()
                    getSharedPreferences("appearance", 0).edit().putBoolean("dark", dark).apply()
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MowerStyle.dark = getSharedPreferences("appearance", 0).getBoolean("dark", false)
        chrome(); updateLauncher()
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = surface(MowerStyle.paper, 0)
            setPadding(dp(20), dp(10), dp(20), dp(12))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ImageView(this).apply {
            setImageResource(com.aliothmoon.maameow.R.drawable.mower_logo)
            contentDescription = "Mower"
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = iconSurface()
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(label("Mower", 20f, bold = true))
            addView(label("ANDROID  /  后台基建助手", 10f, MowerStyle.muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fun add(value: String, primary: Boolean = false, block: () -> Unit): Button {
            val b = action(value, primary, block)
            bar.addView(b, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) })
            return b
        }
        add("游戏画面") { startActivity(Intent(this, MowerGameActivity::class.java)) }
        add("后台授权") {
            AlertDialog.Builder(this).setTitle("后台游戏权限")
                .setMessage("Shizuku 负责后台显示器授权；可使用无线调试或 Root 启动。MAA 任务与排班统一在 Mower WebUI 中设置。")
                .setPositiveButton("打开 Shizuku") { _, _ ->
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) startActivity(intent) else Toast.makeText(this, "请先安装并启动 Shizuku", Toast.LENGTH_LONG).show()
                }.setNegativeButton("关闭", null).show()
        }
        add("局域网") { showNetwork() }
        add("诊断日志") { showLogs() }
        toggle = add("启动服务", true) {
            if (MowerService.active) stopService(Intent(this, MowerService::class.java)) else startRuntime()
        }
        layout.addView(bar)
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(10), 0, dp(10))
        }
        dot = label("●", 10f, MowerStyle.muted)
        statusRow.addView(dot)
        status = label("尚未启动", 12f, MowerStyle.muted).apply { setPadding(dp(8), 0, 0, 0); maxLines = 1 }
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(label("本机运行  ·  无需电脑常驻", 11f, MowerStyle.muted))
        layout.addView(statusRow)
        val content = FrameLayout(this).apply {
            background = surface(MowerStyle.paper, 10); clipToOutline = true; elevation = dp(1).toFloat()
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    val endpoint = MowerService.url ?: return true
                    return uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != android.net.Uri.parse(endpoint).port
                }
            }
        }
        content.addView(web, FrameLayout.LayoutParams(-1, -1))
        landing = welcome()
        content.addView(landing, FrameLayout.LayoutParams(-1, -1))
        layout.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout); handler.post(update)
    }

    private fun updateLauncher() {
        val manager = packageManager
        for ((name, enabled) in listOf("LightLauncher" to !MowerStyle.dark, "DarkLauncher" to MowerStyle.dark).sortedByDescending { it.second }) {
            val component = android.content.ComponentName(this, "com.aliothmoon.maameow.mower.$name")
            val state = if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (manager.getComponentEnabledSetting(component) != state) manager.setComponentEnabledSetting(component, state, android.content.pm.PackageManager.DONT_KILL_APP)
        }
    }

    private fun welcome(): View = ScrollView(this).apply {
        isFillViewport = true
        addView(LinearLayout(this@MowerActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(36), dp(24), dp(36), dp(24))
            addView(label("你的罗德岛，随时就绪。", 26f, bold = true))
            addView(label("熟悉的 Mower，在安卓后台继续运转。", 14f, MowerStyle.muted).apply {
                setPadding(0, dp(8), 0, dp(24))
            })
            val steps = LinearLayout(this@MowerActivity)
            listOf("01" to ("启动服务" to "启动 Shizuku，完成后台游戏授权。"),
                "02" to ("连接游戏" to "打开游戏画面，登录你的明日方舟账号。"),
                "03" to ("安排基建" to "在 WebUI 编辑排班，再启动调度。")
            ).forEach { (number, copy) ->
                val card = LinearLayout(this@MowerActivity).apply {
                    orientation = LinearLayout.VERTICAL; background = surface(MowerStyle.control, 10)
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    addView(label(number, 13f, MowerStyle.green, true))
                    addView(label(copy.first, 16f, bold = true).apply { setPadding(0, dp(12), 0, dp(8)) })
                    addView(label(copy.second, 12f, MowerStyle.muted).apply { setLineSpacing(dp(3).toFloat(), 1f) })
                }
                steps.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(10) })
            }
            addView(steps, LinearLayout.LayoutParams(-1, -2))
            addView(label("首次启动需要解压运行环境，请稍候。进度会显示在上方。", 11f, MowerStyle.muted).apply {
                setPadding(0, dp(22), 0, 0)
            })
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun startRuntime() {
        scope.launch {
            if (com.aliothmoon.maameow.manager.RemoteServiceManager.requestPermission()) {
                startForegroundService(Intent(this@MowerActivity, MowerService::class.java))
            } else AlertDialog.Builder(this@MowerActivity).setTitle("请先启动 Shizuku")
                .setMessage("后台游戏需要 Shizuku 授权。可通过无线调试启动；Root 设备也可以在 Shizuku 中直接启动服务。")
                .setPositiveButton("打开 Shizuku") { _, _ ->
                    packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                }.setNegativeButton("关闭", null).show()
        }
    }

    private fun showNetwork() {
        val prefs = getSharedPreferences("network", 0)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        panel.addView(Switch(this).apply {
            text = "允许局域网访问"; isChecked = prefs.getBoolean("lan", false)
            setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean("lan", value).apply() }
        })
        panel.addView(label("更改开关后，停止并重新启动服务生效。", 12f, MowerStyle.muted))
        val local = MowerService.url
        val addresses = if (local != null && MowerService.lanEnabled) {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).flatMap { java.util.Collections.list(it.inetAddresses) }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                .map { local.replace("127.0.0.1", it.hostAddress!!) }
        } else emptyList()
        val content = if (addresses.isEmpty()) "服务启用局域网访问后，这里会显示连接地址。" else addresses.joinToString("\n\n")
        panel.addView(label(content, 12f).apply { setPadding(0, dp(16), 0, dp(12)); setTextIsSelectable(true) })
        panel.addView(label("在同一网络的浏览器打开完整地址。地址含访问令牌，仅分享给可信设备；重启后令牌会更换。", 12f, MowerStyle.muted))
        val dialog = AlertDialog.Builder(this).setTitle("连接手机 WebUI").setView(panel).setPositiveButton("完成", null)
        if (addresses.isNotEmpty()) dialog.setNeutralButton("复制地址") { _, _ ->
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Mower WebUI", addresses.first()))
        }
        dialog.show()
    }

    private fun showLogs() {
        val log = java.io.File(filesDir, "python.log")
        val text = if (log.exists()) java.io.RandomAccessFile(log, "r").use {
            it.seek(maxOf(0, it.length() - 14000)); val bytes = ByteArray((it.length() - it.filePointer).toInt()); it.readFully(bytes); String(bytes)
        } else MowerService.message
        val content = label(text, 12f).apply { setPadding(dp(20), dp(12), dp(20), dp(12)); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("运行日志").setView(ScrollView(this).apply { addView(content) }).setPositiveButton("关闭", null).show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(update); scope.cancel(); web.destroy(); super.onDestroy()
    }
}
