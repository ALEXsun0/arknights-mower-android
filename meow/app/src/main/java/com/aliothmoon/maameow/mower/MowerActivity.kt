package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.aliothmoon.maameow.MainActivity
import com.aliothmoon.maameow.MaaApplication
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.domain.models.RemoteBackend
import kotlinx.coroutines.*
import org.koin.java.KoinJavaComponent.get

class MowerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var web: WebView
    private var loaded: String? = null
    private val update = object : Runnable {
        override fun run() {
            status.text = MowerService.message
            val url = MowerService.url
            if (url != null && url != loaded) { loaded = url; web.loadUrl(url) }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this)
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { action() }; bar.addView(this, LinearLayout.LayoutParams(0, 52, 1f))
        }
        button("启动") {
            if (!MowerService.active) AlertDialog.Builder(this).setTitle("后台游戏授权方式")
                .setItems(arrayOf("Shizuku", "Root")) { _, which -> scope.launch {
                    (application as MaaApplication).awaitReady()
                    get<AppSettingsManager>(AppSettingsManager::class.java).setStartupBackend(if (which == 0) RemoteBackend.SHIZUKU else RemoteBackend.ROOT)
                    startForegroundService(Intent(this@MowerActivity, MowerService::class.java))
                } }.show()
        }
        button("停止") { stopService(Intent(this, MowerService::class.java)) }
        button("引擎") { startActivity(Intent(this, MainActivity::class.java)) }
        button("日志") {
            val text = java.io.File(filesDir, "python.log").takeIf { it.exists() }?.readText()?.takeLast(14000) ?: MowerService.message
            AlertDialog.Builder(this).setTitle("运行日志").setMessage(text).setPositiveButton("关闭", null).show()
        }
        layout.addView(bar)
        status = TextView(this).apply { text = "Mower Android · 首次启动将初始化本地 Python 环境"; setPadding(12, 6, 12, 6) }
        layout.addView(status)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    return uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != android.net.Uri.parse(MowerService.url).port
                }
            }
        }
        layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        handler.post(update)
    }

    override fun onDestroy() {
        handler.removeCallbacks(update); scope.cancel(); web.destroy()
        super.onDestroy()
    }
}
