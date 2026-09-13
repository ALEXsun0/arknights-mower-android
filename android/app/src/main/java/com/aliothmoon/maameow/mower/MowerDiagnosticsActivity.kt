package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.aliothmoon.maameow.mower.MowerStyle.action
import com.aliothmoon.maameow.mower.MowerStyle.chrome
import com.aliothmoon.maameow.mower.MowerStyle.dp
import com.aliothmoon.maameow.mower.MowerStyle.label
import com.aliothmoon.maameow.mower.MowerStyle.surface
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Native, service-independent export; footer stays reachable in either orientation. */
class MowerDiagnosticsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scanLock = Mutex()
    private var previewJob: Job? = null
    private var plan: MowerDiagnostics.Plan? = null
    private var archive: File? = null
    private var until = System.currentTimeMillis()
    private var since = until - 2 * 3600000
    private var working = false
    private lateinit var fromButton: Button
    private lateinit var toButton: Button
    private lateinit var include: CheckBox
    private lateinit var compact: CheckBox
    private lateinit var summary: TextView
    private lateinit var state: TextView
    private lateinit var progress: ProgressBar
    private lateinit var shareButton: Button
    private lateinit var saveButton: Button
    private lateinit var controls: LinearLayout
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private val dialogContext get() = android.view.ContextThemeWrapper(this,
        if (MowerStyle.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MowerStyle.dark = getSharedPreferences("appearance", 0).getBoolean("dark", false)
        chrome()
        since = savedInstanceState?.getLong("since") ?: since
        until = savedInstanceState?.getLong("until") ?: until
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = surface(MowerStyle.paper, 0) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(action("返回") { finish() })
        header.addView(label("诊断日志", 20f, bold = true), LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(16) })
        root.addView(header)
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        controls.addView(label("选择导出时段", 18f, bold = true))
        controls.addView(label("时间按手机本地时区显示，日志与截图使用相同范围。", 12f, MowerStyle.muted))
        val presets = LinearLayout(this)
        listOf("30 分钟" to 0.5, "2 小时" to 2.0, "24 小时" to 24.0).forEach { (text, hours) ->
            presets.addView(action(text) {
                until = System.currentTimeMillis(); since = until - (hours * 3600000).toLong(); updateDates(); preview()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(8), dp(2), dp(8)) })
        }
        controls.addView(presets)
        fromButton = action("") { chooseTime(true) }; toButton = action("") { chooseTime(false) }
        controls.addView(fromButton, LinearLayout.LayoutParams(-1, dp(48)))
        controls.addView(toButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        updateDates()
        include = CheckBox(this).apply { text = "包含所选时段截图"; setTextColor(MowerStyle.ink); minHeight = dp(48); isChecked = savedInstanceState?.getBoolean("images") ?: true }
        compact = CheckBox(this).apply { text = "仅导出关键截图（最多 128 MiB）"; setTextColor(MowerStyle.ink); minHeight = dp(48); isChecked = savedInstanceState?.getBoolean("compact") ?: false }
        controls.addView(include); controls.addView(compact)
        controls.addView(label("关键模式优先订单与异常附近画面，其余间隔采样。不勾选则导出时段内全部现存截图。", 12f, MowerStyle.muted))
        controls.addView(label("截图保存设置：${ScreenshotPreferences(this).hours()} 小时。此处不会修改清理时间；已删除或未保存的画面无法补回。", 12f, MowerStyle.muted).apply { setPadding(0, dp(8), 0, dp(8)) })
        summary = label("正在读取文件信息…", 14f).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); background = surface(MowerStyle.control) }
        controls.addView(summary)
        controls.addView(action("刷新文件预览") { preview() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val details = label("", 12f, MowerStyle.muted).apply { setTextIsSelectable(true) }
        controls.addView(action("查看最近日志与存储") {
            scope.launch {
                details.text = "正在读取…"
                details.text = withContext(Dispatchers.IO) {
                    val file = File(filesDir, "mower-data/log/runtime.log")
                    MowerDiagnostics.redact(StorageDiagnostics.report(filesDir, cacheDir) + "\n最近日志\n" +
                        if (file.isFile) MowerLogFiles.tail(file, 24000) else "暂无日志")
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        controls.addView(details)
        controls.addView(action("清理日志与诊断包") { showCleanup() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(ScrollView(this).apply { addView(controls) }, LinearLayout.LayoutParams(-1, 0, 1f))
        state = label("不需要启动 Mower 服务或排班。", 12f, MowerStyle.muted)
        root.addView(state)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        val footer = LinearLayout(this)
        shareButton = action("生成并分享", true) { generate(false) }
        saveButton = action("保存到文件") { generate(true) }
        footer.addView(shareButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        footer.addView(saveButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
        root.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = MowerStyle.safeInsets(insets)
            root.setPadding(safe.left + dp(16), safe.top + dp(8), safe.right + dp(16), safe.bottom)
            insets
        }
        include.setOnCheckedChangeListener { _, _ -> compact.isEnabled = include.isChecked; preview() }
        compact.setOnCheckedChangeListener { _, _ -> preview() }
        compact.isEnabled = include.isChecked
        setContentView(root); root.requestApplyInsets(); preview()
    }

    private fun updateDates() { fromButton.text = "开始：${dateFormat.format(since)}"; toButton.text = "结束：${dateFormat.format(until)}" }
    private fun chooseTime(start: Boolean) {
        if (working) return
        val value = Calendar.getInstance().apply { timeInMillis = if (start) since else until }
        DatePickerDialog(dialogContext, { _, year, month, day ->
            TimePickerDialog(dialogContext, { _, hour, minute ->
                value.set(year, month, day, hour, minute, 0); value.set(Calendar.MILLISECOND, 0)
                if (start) since = value.timeInMillis else until = value.timeInMillis
                updateDates(); preview()
            }, value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE), true).show()
        }, value.get(Calendar.YEAR), value.get(Calendar.MONTH), value.get(Calendar.DAY_OF_MONTH)).show()
    }
    private fun preview() {
        if (working) return
        previewJob?.cancel(); plan = null; archive = null; buttons(false)
        summary.text = "正在统计截图数量和占用…"
        val start = since; val end = until; val images = include.isChecked; val small = compact.isChecked
        previewJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { scanLock.withLock { ensureActive(); MowerDiagnostics.prepare(this@MowerDiagnosticsActivity, start, end, images, small) } }
                plan = result
                summary.text = if (images) "时段内可用 ${result.images.available} 张 · ${mib(result.images.availableBytes)} MiB\n本次包含 ${result.images.frames.size} 张 · 截图 ${mib(result.images.bytes)} MiB\n预计诊断包不超过约 ${mib(result.estimatedBytes + 2 * 1024 * 1024)} MiB\n省略 ${result.images.omitted} 张；日志引用但未找到 ${result.images.missingReferences.size} 张" else "仅导出日志，不包含截图。\n预计诊断包不超过约 ${mib(result.estimatedBytes + 2 * 1024 * 1024)} MiB"
                state.text = "预览完成；日志会脱敏，原始截图会随包导出。"
                buttons(true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { summary.text = e.message ?: "预览失败，请重试" }
        }
    }
    private fun buttons(enabled: Boolean) { shareButton.isEnabled = enabled; saveButton.isEnabled = enabled }
    private fun enableControls(enabled: Boolean) {
        fun visit(view: android.view.View) {
            view.isEnabled = enabled
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(controls); compact.isEnabled = enabled && include.isChecked
    }
    private fun generate(save: Boolean) {
        val selected = plan ?: return
        if (working) return
        working = true; enableControls(false); buttons(false); progress.visibility = android.view.View.VISIBLE
        scope.launch {
            try {
                val ctx = coroutineContext
                val file = archive?.takeIf { it.isFile } ?: withContext(Dispatchers.IO) {
                    var last = 0L
                    MowerDiagnostics.build(this@MowerDiagnosticsActivity, selected) { done, total, name ->
                        ctx.ensureActive()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - last >= 150 || done >= total) {
                            last = now
                            runOnUiThread { progress.progress = (100L * done / total.coerceAtLeast(1)).toInt(); state.text = "正在打包 $done / $total · $name" }
                        }
                    }
                }
                archive = file; progress.progress = 100; state.text = "诊断包已生成 · ${mib(file.length())} MiB"
                if (save) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, file.name), 71)
                else startActivity(Intent.createChooser(MowerDiagnostics.share(this@MowerDiagnosticsActivity, file), "分享诊断日志与截图"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.text = e.message ?: "导出失败，请重试" }
            finally { working = false; enableControls(true); buttons(plan != null) }
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 71 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return; val file = archive ?: return
        working = true; buttons(false); enableControls(false)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt").use { output ->
                        checkNotNull(output) { "无法打开目标文件" }; file.inputStream().use { it.copyTo(output) }
                    }
                }
                state.text = "已保存诊断包，可从文件管理器传输。"
            } catch (e: Exception) { state.text = "保存失败：${e.message}" }
            finally { working = false; buttons(plan != null); enableControls(true) }
        }
    }
    private fun showCleanup() {
        if (working) return
        scope.launch {
            val selected = withContext(Dispatchers.IO) { DiagnosticCleanup.candidates(filesDir, cacheDir) }
            if (isFinishing || isDestroyed) return@launch
            val choices = booleanArrayOf(true, true)
            AlertDialog.Builder(dialogContext)
                .setTitle("选择要清理的内容")
                .setMultiChoiceItems(arrayOf("运行日志（全部时段） · ${mib(selected.logBytes)} MiB", "已生成诊断包 · ${mib(selected.exportBytes)} MiB"), choices) { _, i, checked -> choices[i] = checked }
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步") { _, _ ->
                    if (choices.none { it }) return@setPositiveButton
                    AlertDialog.Builder(dialogContext)
                        .setTitle("确认清理所选内容？")
                        .setMessage("此操作无法撤销。截图原文件、排班和配置均保留；诊断包中的截图副本随包删除。运行中的日志会清空后继续记录。系统日志不受影响。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清理") { _, _ ->
                            if (!working) {
                                previewJob?.cancel(); working = true; buttons(false); enableControls(false)
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) { DiagnosticCleanup.clear(filesDir, cacheDir, choices[0], choices[1]) }
                                        state.text = "已清理 ${result.cleared} 个文件" + if (result.failures.isEmpty()) "" else "；未成功：${result.failures.joinToString()}"
                                        plan = null; archive = null; summary.text = "日志内容已变化，请刷新文件预览。"
                                    } catch (e: Exception) { state.text = "清理未完成：${e.message}" }
                                    finally { working = false; enableControls(true) }
                                }
                            }
                        }.show()
                }.show()
        }
    }
    private fun mib(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1048576.0)
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putLong("since", since); outState.putLong("until", until)
        outState.putBoolean("images", include.isChecked); outState.putBoolean("compact", compact.isChecked)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
