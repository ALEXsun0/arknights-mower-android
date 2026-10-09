package com.aliothmoon.maameow.mower

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.DocumentsContract
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A transferred message port gives only the trusted top-level WebUI access to file export. */
internal class WebFileExport(private val activity: Activity) : AutoCloseable {
    private val thread = HandlerThread("mower-file-export").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var port: WebMessagePort? = null
    @Volatile private var closed = false
    private var pending: WebExportFile? = null
    private var selecting = false
    private var finishRequest = 0L

    fun attach(view: WebView, endpoint: String?) {
        val url = Uri.parse(view.url ?: return)
        val target = Uri.parse(endpoint ?: return)
        if (url.scheme != "http" || url.host != "127.0.0.1" || url.port != target.port) return
        detach()
        val channel = view.createWebMessageChannel()
        val current = channel[0]
        port = current
        current.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(source: WebMessagePort, message: WebMessage) {
                if (closed || source !== port) return
                var requestId = 0L
                try {
                    val raw = message.data ?: error("导出请求为空")
                    require(raw.length <= 70 * 1024) { "导出请求过大" }
                    val data = JSONObject(raw)
                    requestId = data.optLong("id")
                    when (data.getString("action")) {
                        "begin" -> {
                            check(pending == null) { "请先完成或取消当前文件导出" }
                            pending = WebExportFile(File(activity.cacheDir, "web-exports"), data.getString("name"), data.getString("mime"), data.getLong("size"))
                            respond(source, requestId)
                        }
                        "append" -> {
                            check(!selecting) { "正在选择导出位置" }
                            checkNotNull(pending).append(data.getString("data"))
                            respond(source, requestId)
                        }
                        "finish" -> {
                            val file = checkNotNull(pending)
                            file.finish()
                            selecting = true
                            finishRequest = requestId
                            val finishingId = requestId
                            activity.runOnUiThread {
                                if (closed || source !== port) return@runOnUiThread
                                try {
                                    activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                                        .addCategory(Intent.CATEGORY_OPENABLE).setType(file.mime)
                                        .putExtra(Intent.EXTRA_TITLE, file.name), REQUEST_CODE)
                                } catch (failure: Exception) {
                                    worker.post { fail(source, finishingId, failure) }
                                }
                            }
                        }
                        "abort" -> if (!selecting) discard()
                        else -> error("不支持的导出请求")
                    }
                } catch (failure: Exception) { fail(source, requestId, failure) }
            }
        }, worker)
        val marker = UUID.randomUUID().toString()
        val script = activity.assets.open("web-file-export.js").bufferedReader().use { it.readText() }
        view.evaluateJavascript(script + "\ninstallMowerFileExport(${JSONObject.quote(marker)});", {
            if (!closed && current === port) view.postWebMessage(WebMessage(marker, arrayOf(channel[1])),
                Uri.parse("http://127.0.0.1:${target.port}"))
        })
    }

    fun download(view: WebView, url: String, name: String, mime: String) {
        view.evaluateJavascript("window.__mowerFileExport && window.__mowerFileExport.save(${JSONObject.quote(url)}, ${JSONObject.quote(name)}, ${JSONObject.quote(mime)})", null)
    }

    fun result(resultCode: Int, uri: Uri?) {
        worker.post {
            val current = port ?: return@post
            val file = pending ?: return@post
            val requestId = finishRequest
            try {
                if (resultCode == Activity.RESULT_OK) {
                    checkNotNull(uri) { "系统未返回保存位置，请重试" }
                    activity.contentResolver.openOutputStream(uri, "wt").use { output ->
                        checkNotNull(output) { "无法打开所选保存位置" }
                        file.copyTo(output) { closed }
                    }
                    activity.runOnUiThread { if (!closed) Toast.makeText(activity, "已保存 ${file.name}", Toast.LENGTH_LONG).show() }
                }
                respond(current, requestId)
            } catch (failure: Exception) {
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    runCatching { DocumentsContract.deleteDocument(activity.contentResolver, uri) }
                }
                fail(current, requestId, failure)
            }
            finally { discard() }
        }
    }

    private fun respond(source: WebMessagePort, requestId: Long, error: String? = null) {
        val reply = JSONObject().apply {
            put("id", requestId)
            if (error != null) put("error", error)
        }.toString()
        activity.runOnUiThread { if (!closed && source === port) source.postMessage(WebMessage(reply)) }
    }

    private fun fail(source: WebMessagePort, requestId: Long, failure: Exception) {
        discard()
        respond(source, requestId, failure.message ?: "文件保存失败，请重试")
    }

    private fun discard() {
        pending?.close()
        pending = null
        selecting = false
        finishRequest = 0L
    }

    fun detach() {
        port?.close()
        port = null
        worker.post { discard() }
    }

    override fun close() {
        closed = true
        detach()
        thread.quitSafely()
    }

    companion object { const val REQUEST_CODE = 7003 }
}
