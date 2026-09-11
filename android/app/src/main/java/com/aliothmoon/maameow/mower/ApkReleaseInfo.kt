package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import com.aliothmoon.maameow.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal fun apkHostUpdateAvailable(installedHash: String, installedCode: Int, release: JSONObject): Boolean =
    release.optString("host_sha256").matches(Regex("[a-f0-9]{64}")) &&
        release.optString("host_sha256") != installedHash && release.optInt("host_version_code") > installedCode

fun Activity.showMowerAbout() {
    val projectUrl = "https://github.com/ALEXsun0/arknights-mower-android"
    val themed = android.view.ContextThemeWrapper(this,
        if (MowerStyle.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
    AlertDialog.Builder(themed).setTitle("关于软件")
        .setMessage("Mower Android\n版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n\n在 Android 上运行 Mower 与后台明日方舟。\n\nGitHub 项目\n$projectUrl")
        .setNegativeButton("关闭", null)
        .setNeutralButton("GitHub 项目") { _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(projectUrl)))
            } catch (_: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, "未找到浏览器，请手动打开：$projectUrl", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        .setPositiveButton("检查 APK 更新") { _, _ -> showApkReleaseInfo() }
        .show()
}

fun Activity.showApkReleaseInfo() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val dialog = AlertDialog.Builder(this).setTitle("APK ${BuildConfig.VERSION_NAME}")
        .setMessage("正在检查宿主版本…").setNegativeButton("关闭", null)
        .setPositiveButton("查看发行页") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ALEXsun0/arknights-mower-android/releases/latest"))) }.create()
    dialog.setOnDismissListener { scope.cancel() }; dialog.show()
    scope.launch {
        try {
            val message = withContext(Dispatchers.IO) {
                fun fetch(url: String): ByteArray {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10000; connection.readTimeout = 30000
                    try {
                        check(connection.responseCode == 200) { "GitHub 暂未响应，请稍后重试" }
                        return connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break
                                check(output.size() + n <= 1024 * 1024) { "发行信息过大" }; output.write(buffer, 0, n) }
                            output.toByteArray()
                        }
                    } finally { connection.disconnect() }
                }
                val latest = JSONObject(String(fetch("https://api.github.com/repos/ALEXsun0/arknights-mower-android/releases/latest")))
                val list = latest.getJSONArray("assets")
                val asset = (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("name") == "android-release.json" }
                val url = asset.getString("browser_download_url")
                check(url.startsWith("https://github.com/ALEXsun0/arknights-mower-android/releases/download/"))
                val bytes = fetch(url)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(asset.getString("digest") == "sha256:$hash") { "发行信息校验失败" }
                val apk = JSONObject(String(bytes)).getJSONObject("apk")
                val installed = JSONObject(assets.open("host-build.json").bufferedReader().use { it.readText() })
                if (apkHostUpdateAvailable(installed.getString("host_sha256"), BuildConfig.VERSION_CODE, apk)) {
                    "有宿主更新：${apk.getString("version")}。可从发行页下载安装 APK，保留配置。"
                } else "当前宿主无需更新。仅刷新内置组件的 Release 不提示升级 APK；请在 WebUI 更新 Mower、MAA 或 Python 接口。"
            }
            dialog.setMessage(message)
        } catch (e: Exception) { dialog.setMessage(e.message ?: "检查失败，请稍后重试") }
    }
}
