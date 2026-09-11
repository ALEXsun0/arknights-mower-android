package com.aliothmoon.maameow.mower

import android.content.Context
import android.content.pm.PackageManager
import com.aliothmoon.maameow.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object AndroidAppUpdate {
    fun file(context: Context, id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "安装包编号无效" }
        return File(context.filesDir, "mower-data/android-updates/$id.apk").also { check(it.isFile) { "请先上传 APK" } }
    }
    @Suppress("DEPRECATION") fun inspect(context: Context, id: String): JSONObject {
        val file = file(context, id)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(262144); while (true) {
            val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
        } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == id) { "APK 校验失败" }
        val manager = context.packageManager
        val incoming = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) ?: error("无法识别 APK")
        check(incoming.packageName == context.packageName) { "请选择 Mower Android 安装包" }
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val actual = incoming.signatures?.map { it.toCharsString() }?.toSet()
        check(!actual.isNullOrEmpty() && actual == installed.signatures?.map { it.toCharsString() }?.toSet()) { "APK 签名与当前安装不一致" }
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) incoming.longVersionCode else incoming.versionCode.toLong()
        check(code >= BuildConfig.VERSION_CODE) { "Android 安装包版本低于当前版本" }
        val name = incoming.versionName ?: ""
        check(!Regex("(?i)(dev|snapshot|nightly)").containsMatchIn(name)) { "Android 仅允许正式版和公测版更新" }
        return JSONObject().put("id", id).put("kind", "apk").put("version", name).put("version_code", code)
    }
}
