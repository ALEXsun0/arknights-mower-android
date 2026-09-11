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
    private var fileSelection: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val update = object : Runnable {
        override fun run() {
            if (AndroidSystemSettings(this@MowerActivity).enabled("keep_screen_on")) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            setPadding(0, dp(10), 0, dp(12))
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
        add("软件设置") { startActivity(Intent(this, MowerSettingsActivity::class.java)) }
        toggle = add("启动服务", true) {
            if (MowerService.active) stopService(Intent(this, MowerService::class.java)) else startRuntime()
        }
        val barScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true }
        barScroll.addView(bar, android.view.ViewGroup.LayoutParams(-1, -2))
        layout.addView(barScroll)
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
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                    fileSelection?.onReceiveValue(null); fileSelection = callback
                    return try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7002)
                        true
                    } catch (_: Exception) { fileSelection?.onReceiveValue(null); fileSelection = null; true }
                }
            }
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
        layout.setOnApplyWindowInsetsListener { _, insets ->
            val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
            val left = maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0)
            val right = maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0)
            layout.setPadding(0, maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0) + dp(8), 0, insets.systemWindowInsetBottom)
            barScroll.setPadding(left + dp(16), 0, right + dp(16), 0)
            barScroll.clipToPadding = false
            statusRow.setPadding(left + dp(18), dp(8), right + dp(16), dp(8))
            insets
        }
        setContentView(layout); layout.requestApplyInsets(); handler.post(update)
    }

    override fun onResume() { super.onResume(); AndroidSystemSettings.foreground = this }
    override fun onPause() { if (AndroidSystemSettings.foreground === this) AndroidSystemSettings.foreground = null; super.onPause() }

    fun installUpdate(file: java.io.File): String {
        if (!packageManager.canRequestPackageInstalls()) {
            runOnUiThread { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName"))) }
            return "请在手机上允许安装应用，再返回此页点击安装"
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.updates", file)
        runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        return "已在手机上打开系统安装器，请在手机确认；安装后重新启动服务"
    }

    fun openSystemSettings(action: String) {
        val target = when (action) {
            "shizuku" -> packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") ?: error("请先安装 Shizuku")
            "battery_settings" -> Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "app_settings" -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
            else -> error("不支持的系统设置")
        }
        check(target.resolveActivity(packageManager) != null) { "此系统没有提供该设置入口" }
        runOnUiThread { runCatching { startActivity(target) }.onFailure { AndroidSystemSettings.lastAction = "系统设置打开失败：${it.message}" } }
    }

    @Deprecated("Legacy Activity result API for minimum SDK compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7002) {
            fileSelection?.onReceiveValue(if (resultCode == RESULT_OK) data?.data?.let { arrayOf(it) } else null)
            fileSelection = null
        }
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

    override fun onDestroy() {
        handler.removeCallbacks(update); scope.cancel(); web.destroy(); super.onDestroy()
    }
}
