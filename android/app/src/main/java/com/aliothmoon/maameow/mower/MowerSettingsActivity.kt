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
    private val switches = linkedMapOf<String, Switch>()
    private var updating = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AndroidSystemSettings(this)
        MowerStyle.dark = getSharedPreferences("appearance", 0).getBoolean("dark", false)
        chrome()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = surface(MowerStyle.paper, 0) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(12)) }
        heading.addView(action("返回") { finish() })
        heading.addView(label("软件设置", 23f, bold = true).apply { setPadding(dp(20), 0, 0, 0) })
        root.addView(heading)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(24)) }
        status = label("设置直接保存在手机，修改后自动生效。", 13f, MowerStyle.muted).apply { setPadding(dp(8), 0, dp(8), dp(16)) }
        val utilities = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        utilities.addView(action("局域网连接") { showNetwork() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(12) })
        utilities.addView(action("诊断日志") { showLogs() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(utilities, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        content.addView(status)
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
            Triple("wake_on_launch", "启动游戏或 MAA 时唤醒手机", "启动前检查并唤醒屏幕；通常可直接熄屏运行。"),
            Triple("dismiss_keyguard", "自动解除滑动锁屏", "仅适用于无密码锁屏。PIN、图案和指纹锁屏仍由系统认证。"),
            Triple("keep_screen_on", "查看 Mower 时保持亮屏", "仅在本应用前台生效，切换到其他应用后按系统设置熄屏。")
        ))
        group("后台游戏", listOf(
            Triple("mute_game", "自动静音游戏", "关闭明日方舟声音，不改变手机媒体音量；关闭后恢复原声音权限。"),
            Triple("preview_sound", "手动操作时恢复声音", "打开游戏画面时恢复声音，返回后继续按静音设置运行。"),
            Triple("force_fullscreen", "后台游戏强制全屏", "在下一次启动游戏时应用，识别画面保持 1920 × 1080。"),
            Triple("recover_game", "自动移回后台", "每 5 秒检查游戏位置，意外回到主屏时尝试移回后台。")
        ))
        group("后台保活", listOf(
            Triple("keep_cpu_awake", "服务运行期间保持 CPU 唤醒", "帮助熄屏后继续调度；关闭可降低耗电，但系统休眠可能使任务延迟。")
        ))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(title: String, run: () -> Unit) { actions.addView(action(title, run = run), LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) }) }
        button("电池优化设置") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        button("应用与通知设置") { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        button("打开 Shizuku") {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) open(intent) else status.text = "请先安装 Shizuku。"
        }
        button("测试唤醒与锁屏状态") { engineAction("test_wake") }
        button("关闭静音并恢复声音") { engineAction("restore_audio") }
        button("APK 版本与更新") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ALEXsun0/arknights-mower-android/releases/latest")))
        }
        button("恢复内置 Mower") {
            val file = java.io.File(filesDir, "mower-data/mower-programs/active.json")
            status.text = if (!file.exists() || file.delete()) "已恢复内置 Mower，停止并重新启动服务后生效。" else "恢复失败，请重试。"
        }
        button("恢复内置 MAA Python 接口") {
            val file = java.io.File(filesDir, "mower-data/maa-python/active.json")
            status.text = if (!file.exists() || file.delete()) "已恢复内置接口，下一次创建 MAA 实例时生效。" else "恢复失败，请重试。"
        }
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
                else -> true
            }
        }
        updating = false
        if (settings.enabled("keep_screen_on")) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            finally { busy = false; refresh() }
        }
    }

    private fun open(intent: Intent) { runCatching { startActivity(intent) }.onFailure { status.text = "此设备未提供对应的系统入口。" } }
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

    override fun onResume() { super.onResume(); if (::settings.isInitialized) refresh() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
