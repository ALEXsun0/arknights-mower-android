package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import com.aliothmoon.maameow.mower.MowerStyle.action
import com.aliothmoon.maameow.mower.MowerStyle.dp
import com.aliothmoon.maameow.mower.MowerStyle.label
import org.json.JSONObject

internal fun Activity.showDiagnosticMcp() {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
    box.addView(label("通过 ADB 转发，让电脑上的 MCP 客户端读取 Android 状态、日志和已保存的游戏截图。无需启动 Mower 服务。", 14f))
    box.addView(label("默认关闭；每次开启使用临时密钥，30 分钟后自动关闭。截图可能包含游戏账号信息。", 12f, MowerStyle.muted).apply { setPadding(0, dp(12), 0, dp(12)) })
    val status = label("", 13f).apply { setTextIsSelectable(true) }
    box.addView(status)
    val toggle = action("") {}
    val copy = action("查看连接信息") {
        DiagnosticMcpService.session?.let { session ->
            val config = JSONObject().put("mcpServers", JSONObject().put("mower-android", JSONObject()
                .put("url", "http://127.0.0.1:18765/mcp")
                .put("headers", JSONObject().put("Authorization", "Bearer ${session.token}")))).toString(2)
            val text = "adb -s <手机设备序列号> forward tcp:18765 tcp:${session.port}\n\n$config"
            val information = label(text, 12f).apply { setTextIsSelectable(true); setPadding(dp(20), dp(12), dp(20), dp(12)) }
            val themed = android.view.ContextThemeWrapper(this,
                if (MowerStyle.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
            AlertDialog.Builder(themed).setTitle("本次 MCP 连接")
                .setView(android.widget.ScrollView(this).apply { addView(information) })
                .setPositiveButton("复制连接信息") { _, _ ->
                    (getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mower MCP 临时连接", text))
                    android.widget.Toast.makeText(this, "已复制 ADB 命令和 MCP 配置", android.widget.Toast.LENGTH_SHORT).show()
                }.setNegativeButton("返回", null).show()
        }
    }
    box.addView(toggle, LinearLayout.LayoutParams(-1, dp(48)))
    box.addView(copy, LinearLayout.LayoutParams(-1, dp(48)))
    val handler = Handler(Looper.getMainLooper())
    val refresh = object : Runnable {
        override fun run() {
            val session = DiagnosticMcpService.session
            val message = if (session == null) DiagnosticMcpService.failure ?: "当前未开启" else "已开启 · 手机本机端口 ${session.port}\n连接信息仅本次会话有效；电脑通过 ADB 转发访问。"
            // Do not redraw a hidden or unchanged dialog; background rendering must stay idle.
            if (window.decorView.windowVisibility == android.view.View.VISIBLE) {
                if (status.text.toString() != message) status.text = message
                val title = if (session == null) "开启 30 分钟诊断会话" else "关闭诊断会话"
                if (toggle.text.toString() != title) toggle.text = title
                if (copy.isEnabled != (session != null)) copy.isEnabled = session != null
            }
            handler.postDelayed(this, 2000)
        }
    }
    toggle.setOnClickListener {
        if (DiagnosticMcpService.session == null) startForegroundService(Intent(this, DiagnosticMcpService::class.java).setAction("start"))
        else stopService(Intent(this, DiagnosticMcpService::class.java))
    }
    val themed = android.view.ContextThemeWrapper(this,
        if (MowerStyle.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
    AlertDialog.Builder(themed).setTitle("MCP 诊断连接").setView(box).setPositiveButton("返回", null).create().apply {
        setOnDismissListener { handler.removeCallbacks(refresh) }; show()
    }
    refresh.run()
}
