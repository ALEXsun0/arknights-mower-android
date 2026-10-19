package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import com.aliothmoon.maameow.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject

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
        .setMessage("正在通过 Mower 网络配置检查宿主版本…").setNegativeButton("关闭", null)
        .setNeutralButton("查看发行页") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ALEXsun0/arknights-mower-android/releases/latest"))) }
        .setPositiveButton("下载并安装", null).create()
    dialog.setOnDismissListener { scope.cancel() }; dialog.show()
    val install = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    install.isEnabled = false
    scope.launch {
        try {
            val checked = withContext(Dispatchers.IO) { NativeRuntimeClient.apkUpdate("check") }
            val apk = checked.getJSONObject("apk")
            val installed = JSONObject(assets.open("host-build.json").bufferedReader().use { it.readText() })
            if (!apkHostUpdateAvailable(installed.getString("host_sha256"), BuildConfig.VERSION_CODE, apk)) {
                dialog.setMessage("当前宿主无需更新。仅刷新内置组件的 Release 不提示升级 APK；请在 WebUI 更新 Mower、MAA 或 Python 接口。")
                return@launch
            }
            dialog.setMessage("有宿主更新：${apk.getString("version")}。下载沿用 Mower 的网络代理和 GitHub 下载代理，完成校验后打开系统安装器，保留配置。")
            install.isEnabled = true
            var downloaded: java.io.File? = null
            install.setOnClickListener {
                install.isEnabled = false
                scope.launch {
                    try {
                        if (downloaded == null) {
                            var job = withContext(Dispatchers.IO) { NativeRuntimeClient.apkUpdate("download", checked.getString("check_id")) }
                            val jobId = job.getString("id")
                            withTimeout(31 * 60 * 1000L) {
                                while (job.getString("status") == "running") {
                                    dialog.setMessage("正在下载并校验 APK：${job.optInt("progress")}%")
                                    delay(1000)
                                    job = withContext(Dispatchers.IO) { NativeRuntimeClient.apkUpdate() }
                                    check(job.optString("id") == jobId) { "下载记录已改变，请重新检查更新" }
                                }
                            }
                            check(job.getString("status") == "succeeded") { job.optString("message", "APK 下载失败，请重新检查") }
                            val id = job.getString("sha256")
                            downloaded = AndroidAppUpdate.downloadedFile(this@showApkReleaseInfo, id)
                        }
                        val file = checkNotNull(downloaded)
                        withContext(Dispatchers.IO) {
                            val result = AndroidAppUpdate.inspectFile(this@showApkReleaseInfo, file, file.nameWithoutExtension)
                            check(result.getLong("version_code") == apk.getLong("version_code") &&
                                result.getString("version") == apk.getString("version")) { "APK 版本与发行清单不一致" }
                        }
                        dialog.setMessage(installMowerApk(file))
                        install.text = "安装已下载 APK"
                        install.isEnabled = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        dialog.setMessage(failure.message ?: "APK 更新失败，请重新检查")
                        install.isEnabled = downloaded != null
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { dialog.setMessage(failure.message ?: "检查失败，请先启动 Mower 服务再重试") }
    }
}
