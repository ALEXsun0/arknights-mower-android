package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import com.aliothmoon.maameow.manager.RemoteServiceManager
import com.aliothmoon.maameow.mower.MowerStyle.action
import com.aliothmoon.maameow.mower.MowerStyle.chrome
import com.aliothmoon.maameow.mower.MowerStyle.dp
import com.aliothmoon.maameow.mower.MowerStyle.label
import com.aliothmoon.maameow.mower.MowerStyle.surface
import kotlinx.coroutines.*
import org.json.JSONObject

class UnlockSettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: UnlockSettings
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private val actions = mutableListOf<Button>()
    @Volatile private var recording = false
    private var busy = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); chrome(); store = UnlockSettings(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = surface(MowerStyle.paper, 0) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        top.addView(action("返回") { finish() })
        top.addView(label("自动解锁", 23f, bold = true).apply { setPadding(dp(20), 0, 0, 0) })
        root.addView(top)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        summary = label("正在读取设置…", 16f)
        status = label("在手机上设置。PIN 和录制操作加密保存，不随 Mower 配置导出。", 13f, MowerStyle.muted)
        content.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        fun button(title: String, block: () -> Unit) {
            val view = action(title, run = block); actions += view
            content.addView(view, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        }
        button("选择解锁方式") {
            val values = listOf("swipe", "gesture", "pin")
            AlertDialog.Builder(this).setTitle("解锁方式")
                .setSingleChoiceItems(arrayOf("滑动（无密码）", "手动录制", "数字 PIN"), values.indexOf(store.kind)) { dialog, index ->
                    store.setKind(values[index]); dialog.dismiss(); refresh()
                }.setNegativeButton("取消", null).show()
        }
        button("录制解锁操作") {
            AlertDialog.Builder(this).setTitle("录制解锁操作")
                .setMessage("将暂停 Mower 任务并锁屏，然后自动亮屏。请在 90 秒内用点击或滑动手动解锁；解锁后自动结束录制。请勿使用指纹或人脸。新录制成功后才替换原记录。")
                .setPositiveButton("开始录制") { _, _ -> startRecording() }.setNegativeButton("取消", null).show()
        }
        button("设置 PIN") { editPin() }
        button("测试当前解锁方式") {
            AlertDialog.Builder(this).setTitle("锁屏并测试一次")
                .setMessage("将暂停 Mower 任务，锁屏后按当前方式尝试一次解锁。失败后停止自动尝试，请手动解锁。")
                .setPositiveButton("开始测试") { _, _ -> execute {
                    MowerService.unlocking = true
                    try { UnlockSettings.resultText(store.unlock(prepareService(), test = true)) }
                    finally { MowerService.unlocking = false }
                } }.setNegativeButton("取消", null).show()
        }
        button("清除录制") { execute { store.clearGesture(); "已清除录制" } }
        button("清除 PIN") { execute { store.clearPin(); "已清除 PIN" } }
        content.addView(action("取消当前录制") {
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { RemoteServiceManager.getInstanceOrNull()?.cancelUnlockRecording() } }
                recording = false; MowerService.unlocking = false; status.text = "已取消录制，原记录已保留"; refresh()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = MowerStyle.safeInsets(insets)
            root.setPadding(safe.left + dp(24), safe.top + dp(16), safe.right + dp(24), safe.bottom + dp(20)); insets
        }
        setContentView(root); root.requestApplyInsets(); refresh()
        scope.launch {
            while (isActive) {
                delay(500)
                if (busy) continue
                val result = withContext(Dispatchers.IO) { runCatching {
                    RemoteServiceManager.getInstanceOrNull()?.pollUnlockRecording()?.let(::JSONObject)
                }.getOrNull() }
                if (result == null) {
                    if (recording) { recording = false; MowerService.unlocking = false; status.text = "录制连接中断，原记录已保留"; refresh() }
                    continue
                }
                when (result.optString("status")) {
                    "RECORDING" -> { if (!recording) { recording = true; refresh() }; status.text = "正在录制，请在锁屏界面手动解锁…" }
                    "DONE" -> {
                        recording = false; MowerService.unlocking = false
                        execute {
                            val gesture = store.saveGesture(result.getJSONObject("gesture").toString())
                            "录制成功，共 ${gesture.steps.size} 步。可测试后启用自动解锁。"
                        }
                    }
                    "FAILED" -> { recording = false; MowerService.unlocking = false; status.text = UnlockSettings.resultText(result.optInt("errorCode")); refresh() }
                }
            }
        }
    }

    private fun startRecording() = execute {
        MowerService.unlocking = true
        try { prepareService().startUnlockRecording(); recording = true; "正在准备锁屏录制…" }
        catch (e: Exception) { MowerService.unlocking = false; throw e }
    }
    private suspend fun prepareService() = prepareUnlockBackend(
        active = MowerService.active,
        ready = MowerService.url != null,
        stopping = MowerService.stopping,
        stopTasks = { NativeRuntimeClient.stopTasks() },
        connect = {
            check(RemoteServiceManager.requestPermission()) { "请先启动并授权 Shizuku，或在软件设置中配置 Root / Sui 后端" }
            RemoteServiceManager.getInstance()
        },
    )
    private fun refresh() {
        actions.forEach { it.isEnabled = !busy && !recording }
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching {
                val kind = mapOf("swipe" to "滑动（无密码）", "gesture" to "手动录制", "pin" to "数字 PIN")[store.kind]
                val gesture = store.gesture()
                "当前方式：$kind\n录制：${gesture?.let { "${it.steps.size} 步 · ${it.screenWidth} × ${it.screenHeight}" } ?: "尚未录制"}　PIN：${if (store.hasPin()) "已设置" else "未设置"}"
            }.getOrElse { "设置读取失败，请重新设置解锁方式" } }
            summary.text = info
        }
    }
    private fun editPin() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "输入 4–16 位数字 PIN"; setPadding(dp(24), dp(12), dp(24), dp(12))
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val dialog = AlertDialog.Builder(this).setTitle("设置解锁 PIN").setView(field)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnDismissListener { field.text.clear(); window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        dialog.setOnShowListener { dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = field.text.toString()
            if (value.length !in 4..16 || value.any { it !in '0'..'9' }) { field.error = "请输入 4–16 位数字"; return@setOnClickListener }
            dialog.dismiss(); execute { store.savePin(value); "PIN 已加密保存" }
        } }
        dialog.show()
    }
    private fun execute(work: suspend () -> String) {
        if (busy) return
        busy = true; refresh()
        scope.launch {
            try { status.text = withContext(Dispatchers.IO) { work() } }
            catch (e: Exception) { status.text = "操作未完成：${e.message ?: "请检查后台连接"}" }
            finally { busy = false; refresh() }
        }
    }
    override fun onDestroy() {
        if (isFinishing && recording) {
            kotlin.concurrent.thread { runCatching { RemoteServiceManager.getInstanceOrNull()?.cancelUnlockRecording() } }
            MowerService.unlocking = false
        }
        scope.cancel(); super.onDestroy()
    }
}
