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
import com.aliothmoon.maameow.mower.MowerStyle.keepScreenOn
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
    private lateinit var installBar: ProgressBar
    private lateinit var permissionSummary: TextView
    private lateinit var permissionAction: Button
    private var permissionsCheckedAt = 0L
    private var permissionJob: Job? = null
    private var uiResumed = false
    private var requestingStart = false
    private var appliedTheme = false
    private lateinit var dot: TextView
    private lateinit var toggle: Button
    private lateinit var web: WebView
    private lateinit var landing: View
    private var loaded: String? = null
    private var fileSelection: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val refreshLoop = VisibleUiRefresh(
        schedule = { callback, delay -> handler.postDelayed(callback, delay) },
        cancel = { handler.removeCallbacks(it) },
        refresh = ::refreshUi,
    )
    private fun refreshUi() {
        keepScreenOn(AndroidSystemSettings(this).enabled("keep_screen_on"))
        if (status.text.toString() != MowerService.message) status.text = MowerService.message
        if (android.os.SystemClock.elapsedRealtime() - permissionsCheckedAt >= 5000) refreshPermissions()
        val installation = MowerService.installProgress
        installBar.visibility = if (installation == null) View.GONE else View.VISIBLE
        if (installation != null) {
            installBar.progress = installation.percent
            installBar.contentDescription = "${installation.stage} ${installation.percent}%"
            installBar.progressTintList = android.content.res.ColorStateList.valueOf(MowerStyle.green)
            installBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(MowerStyle.border)
        }
        val url = MowerService.url
        toggle.isEnabled = !MowerService.stopping && !requestingStart
        val toggleLabel = if (MowerService.active) "停止服务" else "启动服务"
        if (toggle.text.toString() != toggleLabel) toggle.text = toggleLabel
        dot.setTextColor(if (url != null) MowerStyle.green else MowerStyle.muted)
        if (url != null && url != loaded) { loaded = url; web.loadUrl(url) }
        if (url == null && loaded != null) { loaded = null; web.loadUrl("about:blank") }
        landing.visibility = if (url == null) View.VISIBLE else View.GONE
        web.visibility = if (url == null) View.GONE else View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MowerStyle.dark = getSharedPreferences("appearance", 0).getBoolean("dark", false)
        appliedTheme = MowerStyle.dark
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
            if (MowerService.stopping) return@add
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
        status = label("尚未启动", 12f, MowerStyle.muted).apply { setPadding(dp(8), 0, dp(8), 0); maxLines = 3 }
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(label("本机运行  ·  无需电脑常驻", 11f, MowerStyle.muted))
        layout.addView(statusRow)
        installBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false; visibility = View.GONE
        }
        layout.addView(installBar, LinearLayout.LayoutParams(-1, dp(6)).apply { bottomMargin = dp(8) })
        val permissionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        permissionSummary = label("正在自动检查权限…", 11f, MowerStyle.muted).apply { maxLines = 3 }
        permissionRow.addView(permissionSummary, LinearLayout.LayoutParams(0, -2, 1f))
        permissionAction = action("权限与后台运行") { startActivity(Intent(this, MowerSettingsActivity::class.java)) }
        permissionRow.addView(permissionAction, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(12) })
        layout.addView(permissionRow)
        val content = FrameLayout(this).apply {
            background = surface(MowerStyle.paper, 10); clipToOutline = true; elevation = dp(1).toFloat()
        }
        web = object : WebView(this) {
            override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
                val connection = super.onCreateInputConnection(outAttrs)
                outAttrs.imeOptions = outAttrs.imeOptions or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                return connection
            }
        }.apply {
            setBackgroundColor(MowerStyle.paper)
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
            val safe = MowerStyle.safeInsets(insets)
            val left = safe.left
            val right = safe.right
            layout.setPadding(0, safe.top + dp(8), 0, insets.systemWindowInsetBottom)
            barScroll.setPadding(left + dp(16), 0, right + dp(16), 0)
            barScroll.clipToPadding = false
            statusRow.setPadding(left + dp(18), dp(8), right + dp(16), dp(8))
            permissionRow.setPadding(left + dp(18), 0, right + dp(16), dp(6))
            (installBar.layoutParams as LinearLayout.LayoutParams).apply {
                if (marginStart != left + dp(18) || marginEnd != right + dp(16)) {
                    marginStart = left + dp(18); marginEnd = right + dp(16)
                    installBar.layoutParams = this
                }
            }
            insets
        }
        setContentView(layout); layout.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume(); uiResumed = true; AndroidSystemSettings.foreground = this; refreshPermissions()
        val dark = getSharedPreferences("appearance", 0).getBoolean("dark", false)
        MowerStyle.dark = dark; MowerStyle.applyTheme(layout); chrome(); web.setBackgroundColor(MowerStyle.paper)
        if (dark != appliedTheme && loaded != null) web.reload()
        appliedTheme = dark
        web.onResume(); web.resumeTimers(); refreshLoop.start()
    }
    override fun onPause() {
        uiResumed = false
        refreshLoop.stop()
        web.onPause(); web.pauseTimers()
        android.util.Log.i("Mower", "主页进入后台，已暂停界面刷新和 WebView 计时器")
        if (AndroidSystemSettings.foreground === this) AndroidSystemSettings.foreground = null
        super.onPause()
    }

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
            "battery_settings" -> PermissionChecks.batteryIntent(this)
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
        // Disabling a launcher alias can dismiss its task on vendor ROMs even
        // with DONT_KILL_APP. Never change it during startup or a running service.
        if (MowerService.active || MowerService.stopping) return
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
            addView(label("在手机上运行 Mower", 26f, bold = true))
            addView(label("后台运行明日方舟，使用原版 WebUI 管理基建和 MAA。", 14f, MowerStyle.muted).apply {
                setPadding(0, dp(8), 0, dp(24))
            })
            addView(label("跑单设置提示：启用葛朗台跑单时，请在 WebUI 设置中将「葛朗台缓冲时间」调至至少 15 秒；低帧率设备建议 30 秒。", 13f, MowerStyle.green, true).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, 0, 0, dp(20))
            })
            val steps = LinearLayout(this@MowerActivity)
            listOf("01" to ("后台授权" to "未 Root 手机需启动并授权 Shizuku。Root 手机可在软件设置选择 Root 后端，也可使用已配置的 Sui。"),
                "02" to ("启动服务与游戏" to "点击上方启动服务，再打开游戏画面，登录明日方舟。"),
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
            addView(label("arknights-mower-android · 首次启动会解压运行环境，进度显示在上方。项目链接可在软件设置的「关于软件」查看；局域网端口和 Token 也在软件设置中管理。", 11f, MowerStyle.muted).apply {
                setPadding(0, dp(22), 0, 0)
            })
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun startRuntime() {
        if (requestingStart) return
        requestingStart = true
        scope.launch {
            try {
                if (com.aliothmoon.maameow.manager.RemoteServiceManager.requestPermission()) {
                    startForegroundService(Intent(this@MowerActivity, MowerService::class.java))
                } else showPermissionIssues()
            } catch (failure: Exception) {
                ensureActive()
                MowerService.message = "后台授权检查失败，请确认 Shizuku / Sui 的运行状态后重试"
                showPermissionIssues()
            } finally { requestingStart = false; refreshPermissions() }
        }
    }

    private fun refreshPermissions() {
        if (!::permissionSummary.isInitialized || !uiResumed || permissionJob?.isCompleted == false) return
        permissionsCheckedAt = android.os.SystemClock.elapsedRealtime()
        permissionJob = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PermissionChecks.inspect(this@MowerActivity) } }
            if (!uiResumed) return@launch
            result.onSuccess { snapshot ->
                if (permissionSummary.text.toString() != snapshot.summary) permissionSummary.text = snapshot.summary
                permissionAction.visibility = if (snapshot.issues.isEmpty()) View.GONE else View.VISIBLE
            }.onFailure {
                permissionSummary.text = "权限状态读取失败，稍后自动重试"
                permissionAction.visibility = View.VISIBLE
            }
        }
    }

    private fun showPermissionIssues() = scope.launch {
        val snapshot = withContext(Dispatchers.IO) { runCatching { PermissionChecks.inspect(this@MowerActivity) } }.getOrElse {
            refreshPermissions(); return@launch
        }
        if (!uiResumed) return@launch
        val issues = snapshot.issues
        if (issues.isEmpty()) { refreshPermissions(); return@launch }
        AlertDialog.Builder(this@MowerActivity).setTitle("自动权限检查 · 点击可直接处理")
            .setItems(issues.map { (if (it.required) "启动必需：" else "功能提醒：") + it.description }.toTypedArray()) { _, index ->
                val issue = issues[index]
                if (issue.action == PermissionAction.AUTHORIZE_BACKEND) {
                    scope.launch {
                        try { com.aliothmoon.maameow.manager.RemoteServiceManager.requestPermission() }
                        catch (failure: Exception) { ensureActive() }
                        finally { refreshPermissions() }
                    }
                } else {
                    val intent = PermissionChecks.intent(this@MowerActivity, issue.action)
                    if (intent == null) AlertDialog.Builder(this@MowerActivity).setTitle("后台服务未就绪")
                        .setMessage("未找到 Shizuku 应用。请先安装并启动 Shizuku；使用 Sui 的设备请从其管理入口启动服务。")
                        .setPositiveButton("关闭", null).show()
                    else runCatching { startActivity(intent) }.onFailure {
                        AlertDialog.Builder(this@MowerActivity).setMessage("系统未提供此权限的快捷页面，请从应用系统设置处理。")
                            .setPositiveButton("打开应用系统设置") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
                            .setNegativeButton("关闭", null).show()
                    }
                }
            }.setNegativeButton("关闭", null).show()
    }

    override fun onDestroy() {
        refreshLoop.stop(); scope.cancel(); web.destroy(); super.onDestroy()
    }
}
