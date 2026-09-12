package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import com.aliothmoon.maameow.mower.MowerStyle.action
import com.aliothmoon.maameow.mower.MowerStyle.chrome
import com.aliothmoon.maameow.mower.MowerStyle.keepScreenOn
import com.aliothmoon.maameow.mower.MowerStyle.dp
import com.aliothmoon.maameow.mower.MowerStyle.label
import com.aliothmoon.maameow.mower.MowerStyle.surface
import kotlinx.coroutines.*
import org.json.JSONObject

/** Device preferences belong to the Android app and work before Python starts. */
class MowerSettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: AndroidSystemSettings
    private lateinit var status: TextView
    private lateinit var idleButton: Button
    private var idleAction = "idle"
    private val idleValues = listOf("idle", "home", "exit")
    private val idleLabels = arrayOf("无操作", "返回游戏首页", "退出游戏")
    private val switches = linkedMapOf<String, Switch>()
    private var updating = false
    private var permissionJob: Job? = null
    private var uiResumed = false
    private var busy = false
    private lateinit var appearanceButton: Button
    private lateinit var permissionCard: LinearLayout
    private val permissionRows = mutableListOf<Pair<TextView, Button>>()
    private val permissionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val permissionPoll = object : Runnable {
        override fun run() { refreshPermissions(); permissionHandler.postDelayed(this, 5000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AndroidSystemSettings(this)
        MowerStyle.dark = getSharedPreferences("appearance", 0).getBoolean("dark", false)
        chrome()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = surface(MowerStyle.paper, 0) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(12)) }
        heading.addView(action("返回") { finish() })
        heading.addView(label("软件设置", 20f, bold = true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(action("关于软件") { showMowerAbout() })
        root.addView(heading)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(24)) }
        status = label("设置直接保存在手机，修改后自动生效。", 13f, MowerStyle.muted).apply { setPadding(dp(8), 0, dp(8), dp(16)) }
        val utilities = ResponsiveRow(this).apply { gravity = Gravity.CENTER_VERTICAL }
        utilities.addView(action("局域网连接") { showNetwork() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(12) })
        utilities.addView(action("诊断日志") { showLogs() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(12) })
        utilities.addView(action("截图保存时间") { showScreenshotRetention() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(utilities, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        content.addView(status)
        permissionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = surface(MowerStyle.control, 12)
            setPadding(dp(20), dp(16), dp(20), dp(12))
            addView(label("权限与后台运行", 18f, bold = true))
            addView(label("自动检测授权状态，返回本页后自动更新。屏保使用悬浮窗权限。", 12f, MowerStyle.muted).apply { setPadding(0, dp(6), 0, dp(8)) })
        }
        content.addView(permissionCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        appearanceButton = action("显示模式：${if (MowerStyle.dark) "暗色" else "亮色"}") {
            AlertDialog.Builder(this).setTitle("显示模式")
                .setSingleChoiceItems(arrayOf("亮色", "暗色"), if (MowerStyle.dark) 1 else 0) { dialog, index ->
                    dialog.dismiss()
                    execute {
                        check(getSharedPreferences("appearance", 0).edit().putBoolean("dark", index == 1).commit()) { "外观保存失败" }
                        AppearancePreferences.sync(this)
                        runOnUiThread {
                            MowerStyle.dark = index == 1
                            appearanceButton.text = "显示模式：${if (MowerStyle.dark) "暗色" else "亮色"}"
                            MowerStyle.applyTheme(window.decorView); chrome()
                        }
                        if (MowerService.url != null) NativeRuntimeClient.saveTheme(if (index == 1) "dark" else "light")
                        "外观已保存；返回主页后同步 WebUI。"
                    }
                }.setNegativeButton("取消", null).show()
        }
        content.addView(appearanceButton, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
        idleButton = action("任务结束后（启动服务后修改）") {
            AlertDialog.Builder(this).setTitle("任务结束后")
                .setSingleChoiceItems(idleLabels, idleValues.indexOf(idleAction)) { dialog, index ->
                    dialog.dismiss()
                    execute {
                        NativeRuntimeClient.saveIdleAction(idleValues[index])
                        "已保存 Mower 任务收尾设置，下次任务结束时按原调度逻辑执行。"
                    }
                }.setNegativeButton("取消", null).show()
        }
        content.addView(idleButton, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })
        content.addView(label("与 Mower 原配置同步；退出游戏仍遵从原有等待时间判断。", 12f, MowerStyle.muted), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        content.addView(action("解锁方式、操作录制与测试") { startActivity(Intent(this, UnlockSettingsActivity::class.java)) }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(16) })
        fun group(title: String, rows: List<Triple<String, String, String>>) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = surface(MowerStyle.control, 12)
                setPadding(dp(20), dp(16), dp(20), dp(8))
            }
            card.addView(label(title, 18f, bold = true))
            rows.forEach { (key, title, description) ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, dp(14)); minimumHeight = dp(72) }
                val words = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(24), 0)
                    addView(label(title, 16f))
                    addView(label(description, 12f, MowerStyle.muted).apply { setPadding(0, dp(5), 0, 0) })
                }
                row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
                val toggle = Switch(this).apply {
                    contentDescription = title; minimumHeight = dp(48); minimumWidth = dp(56)
                    thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(MowerStyle.green, MowerStyle.muted))
                    setOnCheckedChangeListener { _, value -> if (!updating) save(key, value) }
                }
                switches[key] = toggle
                row.addView(toggle)
                row.setOnClickListener { if (toggle.isEnabled) toggle.isChecked = !toggle.isChecked }
                card.addView(row)
            }
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
        group("屏幕与唤醒", listOf(
            Triple("wake_on_launch", "启动游戏或 MAA 时唤醒手机", "启动前检查并唤醒屏幕；部分手机息屏会限制游戏渲染。"),
            Triple("dismiss_keyguard", "自动解锁手机", "按本机设置使用滑动、录制操作或 PIN；失败后停止尝试。"),
            Triple("keep_screen_on", "查看 Mower 时保持亮屏", "仅在本应用前台生效，切换到其他应用后按系统设置熄屏。")
        ))
        group("后台游戏", listOf(
            Triple("mute_game", "自动静音游戏", "关闭明日方舟声音，不改变手机媒体音量；关闭后恢复原声音权限。"),
            Triple("preview_sound", "查看游戏时恢复声音", "打开游戏画面时恢复声音，返回后继续按静音设置运行。"),
            Triple("force_fullscreen", "后台游戏强制全屏", "在下一次启动游戏时应用，识别画面保持 1920 × 1080。"),
            Triple("recover_game", "自动移回后台", "每 5 秒检查游戏位置，意外回到主屏时尝试移回后台。")
        ))
        group("预览与监测", listOf(
            Triple("external_device_alerts", "通过 Mower 发送设备异常通知", "复用 Mower 已配置的邮件通知与等级；未启用邮件时不会发送。"),
            Triple("auto_pip", "离开游戏画面时进入画中画", "在系统小窗继续查看后台游戏；小窗内不转发触摸。"),
            Triple("show_touch", "显示触控位置", "在游戏画面显示手动与自动操作的触点。"),
            Triple("resolution_720p", "后台游戏使用 720p", "下次连接生效；Mower 截图转换为 1080p。默认 1080p 识别更清晰。"),
            Triple("fps_monitor", "监测后台游戏帧率", "优先使用系统帧率回调，旧系统使用合成帧计数。"),
            Triple("low_fps_alert", "低帧率提醒", "持续低帧率时每轮提醒一次，静止画面不按掉线处理。"),
            Triple("disconnect_stop", "游戏无法恢复时停止任务", "工作期间优先按保活设置恢复游戏；恢复失败或后台连接断开时停止 Mower 与 MAA 并通知。")
        ))
        group("任务与熄屏", listOf(
            Triple("sleep_when_idle", "本轮任务完成后锁屏休眠", "按 Mower 的工作与等待状态判断，不另建一套排班。"),
            Triple("preserve_screen_on", "保留原本亮着的屏幕", "本轮开始时已亮屏，则结束后不自动锁屏。"),
            Triple("screen_saver", "任务期间显示黑色屏保", "离开应用后显示，长按退出；需要系统悬浮窗权限。"),
            Triple("hardware_screen_off", "屏保期间关闭物理屏幕", "保持后台游戏运行；长按屏幕、打开本应用或通知栏可恢复。")
        ))
        group("后台保活", listOf(
            Triple("keep_game_alive", "任务期间自动恢复游戏", "游戏退出后按需拉起，10 分钟最多 3 次；空闲主动退出、停止任务和手动操作期间不拉起。不能解除系统息屏限帧。"),
            Triple("keep_cpu_awake", "服务运行期间保持 CPU 唤醒", "帮助熄屏后继续调度；关闭可降低耗电，但系统休眠可能使任务延迟。"),
            Triple("restart_on_boot", "手机重启后恢复服务", "仍需后台权限可用；只恢复服务，任务由 Mower 自身配置决定。"),
            Triple("root_backend", "直接使用 Root 启动后台", "适用于已 Root 手机；下次启动服务生效，会请求 su 授权。默认使用 Shizuku / Sui。")
        ))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(title: String, run: () -> Unit) { actions.addView(action(title, run = run), LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) }) }
        button("恢复屏幕显示") { execute { MowerScreenSaver.hide(); "已请求恢复屏幕显示" } }
        button("重新连接后台服务") { engineAction("reconnect") }
        button("关闭静音并恢复声音") { engineAction("restore_audio") }
        content.addView(actions)
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = MowerStyle.safeInsets(insets)
            root.setPadding(safe.left + dp(20),
                safe.top + dp(8),
                safe.right + dp(20), insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root); root.requestApplyInsets(); refresh()
    }

    private fun refresh() {
        updating = true
        switches.forEach { (key, view) ->
            view.isChecked = settings.enabled(key)
            view.isEnabled = !busy && when (key) {
                "dismiss_keyguard" -> settings.enabled("wake_on_launch")
                "preview_sound" -> settings.enabled("mute_game")
                "hardware_screen_off" -> settings.enabled("screen_saver")
                "preserve_screen_on" -> settings.enabled("sleep_when_idle")
                "low_fps_alert" -> settings.enabled("fps_monitor")
                else -> true
            }
        }
        updating = false
        keepScreenOn(settings.enabled("keep_screen_on"))
    }

    private fun save(key: String, value: Boolean) {
        if (busy) return
        val snapshot = settings.snapshot()
        snapshot.getJSONObject("settings").put(key, value)
        if (key == "wake_on_launch" && !value) snapshot.getJSONObject("settings").put("dismiss_keyguard", false)
        execute {
            val engine = MowerService.engine
            if (engine == null) { settings.save(snapshot); "已保存；启动服务后应用。" }
            else { val state = engine.saveSystemSettings(snapshot); if (state.isNull("error")) "已保存并应用。" else state.getString("error") }
        }
    }

    private fun engineAction(action: String) = execute {
        val engine = MowerService.engine ?: error("请先返回首页并启动服务；设置已保存在手机。")
        engine.performSystemAction(action)
        AndroidSystemSettings.lastAction
    }

    private fun execute(work: () -> String) {
        if (busy) return
        busy = true; refresh()
        scope.launch {
            try { status.text = withContext(Dispatchers.IO) { work() } }
            catch (error: Exception) { status.text = error.message ?: "操作未完成，请重试。" }
            finally { busy = false; refresh(); refreshIdleAction() }
        }
    }

    private fun open(intent: Intent) { runCatching { startActivity(intent) }.onFailure { status.text = "此设备未提供对应的系统入口。" } }
    private fun showScreenshotRetention() {
        val preferences = ScreenshotPreferences(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(12)) }
        panel.addView(label("默认 0 小时，不保存 Mower 截图；支持小数。", 14f))
        val value = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(); contentDescription = "截图保存小时数"
            setText(java.math.BigDecimal.valueOf(preferences.hours()).stripTrailingZeros().toPlainString())
        }
        panel.addView(value)
        val hint = label("", 13f, MowerStyle.muted).apply { setPadding(0, dp(12), 0, 0) }
        panel.addView(hint)
        fun updateHint() {
            val hours = value.text.toString().toDoubleOrNull()
            hint.text = when {
                hours == null || !hours.isFinite() || hours < 0 -> "请输入大于或等于 0 的小时数，可填小数。"
                hours == 0.0 -> "已关闭截图保存，实时预览仍可用。后续调试、跑单等截图不会保存，排查问题时可能缺少截图记录。设为正数可恢复保存。"
                else -> "启用截图保存会持续写入手机存储，频繁写入可能加速闪存磨损；保留时间越长，占用空间越多。建议仅在排查问题时临时启用，用完恢复为 0。"
            }
        }
        value.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateHint() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        updateHint()
        val dialog = AlertDialog.Builder(this).setTitle("截图保存时间（小时）")
            .setView(panel).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val hours = value.text.toString().toDoubleOrNull()
            if (hours == null || !hours.isFinite() || hours < 0) { updateHint(); return@setOnClickListener }
            dialog.dismiss()
            execute {
                preferences.save(hours)
                if (MowerService.url == null) "截图设置已保存，下次启动服务时生效。"
                else runCatching { NativeRuntimeClient.saveScreenshotHours(hours); "截图设置已保存并应用。" }
                    .getOrDefault("截图设置已保存，当前服务未响应，重启服务后生效。")
            }
        } }
        dialog.show()
    }
    private fun showNetwork() {
        val prefs = getSharedPreferences("network", 0)
        val saved = WebConnectionPreferences.read(prefs)
        val dialogContext = android.view.ContextThemeWrapper(this,
            if (MowerStyle.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val enabled = Switch(dialogContext).apply {
            text = "允许局域网访问"; isChecked = saved.lan
        }
        panel.addView(enabled)
        panel.addView(label("固定端口", 14f).apply { setPadding(0, dp(14), 0, 0) })
        val port = EditText(dialogContext).apply {
            hint = "留空生成并保存；1024–65535"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(saved.port)
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            contentDescription = "WebUI 固定端口"
        }
        panel.addView(port)
        panel.addView(label("访问 Token", 14f).apply { setPadding(0, dp(12), 0, 0) })
        val token = EditText(dialogContext).apply {
            hint = "留空将在下次启动生成并保存"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            setText(saved.token)
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            contentDescription = "WebUI 访问 Token"
        }
        panel.addView(token)
        panel.addView(label("Token 长度不限，支持字母、数字、下划线和短横线。保存后停止并重新启动服务生效。", 12f, MowerStyle.muted))
        val error = label("", 12f, MowerStyle.muted)
        panel.addView(error)
        val local = MowerService.url
        val addresses = if (local != null && MowerService.lanEnabled) {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).flatMap { java.util.Collections.list(it.inetAddresses) }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                .map { local.replace("127.0.0.1", it.hostAddress!!) }
        } else emptyList()
        val content = if (addresses.isEmpty()) "服务启用局域网访问后，这里会显示连接地址。" else addresses.joinToString("\n\n")
        panel.addView(label("当前运行地址（未重启时可能与已保存值不同）", 14f).apply { setPadding(0, dp(12), 0, 0) })
        panel.addView(label(content, 12f).apply { setPadding(0, dp(16), 0, dp(12)); setTextIsSelectable(true) })
        panel.addView(label("在同一网络的浏览器打开完整地址。地址含访问令牌，仅分享给可信设备；自动生成的端口和令牌也会保存，重启后继续使用。", 12f, MowerStyle.muted))
        val builder = AlertDialog.Builder(dialogContext).setTitle("连接手机 WebUI")
            .setView(ScrollView(this).apply { addView(panel) }).setPositiveButton("保存", null).setNegativeButton("取消", null)
        if (addresses.isNotEmpty()) builder.setNeutralButton("复制当前地址") { _, _ ->
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Mower WebUI", addresses.first()))
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val connection = WebConnectionConfig.parse(port.text.toString(), token.text.toString())
                    val requested = SavedWebConnection(enabled.isChecked, if (connection.port == 0) "" else connection.port.toString(), connection.token)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { WebConnectionPreferences.save(prefs, requested) }
                            status.text = if (MowerService.active) "连接设置已保存；当前服务仍使用旧地址，停止并重新启动服务后生效。" else "连接设置已保存，下次启动服务时使用。"
                            dialog.dismiss()
                        } catch (failure: Exception) {
                            error.text = failure.message ?: "连接设置保存失败"
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                } catch (failure: IllegalArgumentException) { error.text = failure.message }
            }
        }
        dialog.show()
    }

    private fun exportLogs() {
        execute { val intent = MowerDiagnostics.share(this); runOnUiThread { startActivity(Intent.createChooser(intent, "导出诊断日志")) }; "已生成诊断日志" }
    }

    private fun showLogs() = scope.launch {
        val storage = withContext(Dispatchers.IO) { StorageDiagnostics.report(filesDir, cacheDir) }
        val log = java.io.File(filesDir, "python.log")
        val checks = java.io.File(filesDir, "startup-check.txt").takeIf { it.isFile }?.readText().orEmpty()
        val mowerLog = java.io.File(filesDir, "mower-data/log/runtime.log")
        val mowerText = if (mowerLog.isFile) runCatching { MowerLogFiles.tail(mowerLog, 24000) }.getOrDefault("日志暂不可读") else "暂无 Mower 文件日志"
        val text = storage + "\nMower 日志\n" + mowerText + "\n\n启动检查\n" + checks + "\n" + ProcessExitDiagnostics.report(this@MowerSettingsActivity) + "\n\nPython 控制台\n" + if (log.exists()) java.io.RandomAccessFile(log, "r").use {
            it.seek(maxOf(0, it.length() - 14000)); val bytes = ByteArray((it.length() - it.filePointer).toInt()); it.readFully(bytes); String(bytes)
        } else MowerService.message
        val content = label(MowerDiagnostics.redact(text), 12f).apply { setPadding(dp(20), dp(12), dp(20), dp(12)); setTextIsSelectable(true) }
        val dialogContext = android.view.ContextThemeWrapper(this@MowerSettingsActivity,
            if (MowerStyle.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
        AlertDialog.Builder(dialogContext).setTitle("运行日志").setView(ScrollView(dialogContext).apply { addView(content) })
            .setNeutralButton("导出并分享") { _, _ -> exportLogs() }
            .setPositiveButton("关闭", null).show()
    }

    private fun refreshIdleAction() {
        if (!::idleButton.isInitialized) return
        idleButton.isEnabled = false
        if (MowerService.url == null) { idleButton.text = "任务结束后（启动服务后修改）"; return }
        scope.launch {
            try {
                idleAction = withContext(Dispatchers.IO) { NativeRuntimeClient.idleAction() }
                idleButton.text = "任务结束后：${idleLabels[idleValues.indexOf(idleAction)]}"
                idleButton.isEnabled = !busy
            } catch (_: Exception) { idleButton.text = "任务结束后（Mower 暂未响应）" }
        }
    }
    private fun refreshPermissions() {
        if (!::permissionCard.isInitialized || !uiResumed || permissionJob?.isCompleted == false) return
        permissionJob = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PermissionChecks.inspect(this@MowerSettingsActivity).entries } }
            if (!uiResumed) return@launch
            result.onSuccess { renderPermissions(it) }.onFailure {
                (permissionCard.getChildAt(1) as TextView).text = "权限状态暂无法读取，正在自动重试。"
                permissionRows.forEach { (text, button) -> text.text = "权限状态暂无法读取，正在自动重试"; button.isEnabled = false }
            }
        }
    }

    private fun renderPermissions(entries: List<PermissionEntry>) {
        (permissionCard.getChildAt(1) as TextView).text = "已自动检测 · 返回本页后自动更新。屏保使用悬浮窗权限。"
        if (permissionRows.size != entries.size) {
            while (permissionCard.childCount > 2) permissionCard.removeViewAt(2)
            permissionRows.clear()
            entries.forEach { _ ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
                val text = label("正在检测…", 14f).apply { setPadding(0, 0, dp(16), 0) }
                val button = action("去授权") {}
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button, LinearLayout.LayoutParams(dp(100), dp(48)))
                permissionCard.addView(row); permissionRows += text to button
            }
        }
        entries.zip(permissionRows).forEach { (entry, views) ->
            val (text, button) = views
            text.text = "${entry.title}\n${entry.state}"
            button.text = if (entry.granted) "已获得" else if (entry.action == null) "启动时检查" else "去处理"
            button.isEnabled = !entry.granted && entry.action != null
            button.setOnClickListener {
                if (entry.action == PermissionAction.AUTHORIZE_BACKEND) scope.launch {
                    try { com.aliothmoon.maameow.manager.RemoteServiceManager.requestPermission() }
                    catch (failure: Exception) { status.text = failure.message ?: "后台授权未完成" }
                    finally { refreshPermissions() }
                } else entry.action?.let { action ->
                    val intent = PermissionChecks.intent(this, action)
                    if (intent == null) status.text = "系统未提供此授权入口；请确认已安装 Shizuku。" else open(intent)
                }
            }
        }
    }
    override fun onResume() { super.onResume(); uiResumed = true; if (::settings.isInitialized) { refresh(); refreshIdleAction(); permissionHandler.removeCallbacks(permissionPoll); permissionPoll.run() } }
    override fun onPause() { uiResumed = false; permissionHandler.removeCallbacks(permissionPoll); super.onPause() }
    override fun onDestroy() { permissionHandler.removeCallbacks(permissionPoll); scope.cancel(); super.onDestroy() }
}
