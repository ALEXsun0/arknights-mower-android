package com.aliothmoon.maameow.mower

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.constant.WakeUnlockResult
import com.aliothmoon.maameow.domain.models.UnlockGesture
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never enter Mower configuration, exports, logs or HTTP responses. */
class UnlockSettings(context: Context) {
    private val prefs = context.getSharedPreferences("android-unlock", 0)
    private val directory = File(context.noBackupFilesDir, "unlock")
    val kind: String get() = prefs.getString("kind", "swipe") ?: "swipe"
    fun setKind(value: String) {
        require(value in setOf("swipe", "gesture", "pin"))
        check(prefs.edit().putString("kind", value).commit())
    }
    private fun file(name: String) = AtomicFile(File(directory, "$name.bin"))
    private fun key(): SecretKey = synchronized(UnlockSettings::class.java) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        directory.mkdirs()
        val target = file(name)
        val output = target.startWrite()
        try { output.write(data); target.finishWrite(output) }
        catch (e: Exception) { target.failWrite(output); throw e }
    }
    private fun read(name: String): String {
        if (!file(name).baseFile.exists()) return ""
        val data = file(name).readFully()
        require(data.size in 29..1_000_100) { "解锁记录损坏，请重新设置" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
    fun savePin(value: String) {
        require(value.length in 4..16 && value.all { it in '0'..'9' }) { "请输入 4–16 位数字 PIN" }
        write("pin", value); setKind("pin")
    }
    fun saveGesture(json: String): UnlockGesture {
        val value = UnlockGesture.parseOrNull(json) ?: error("录制内容无效，请重新录制")
        write("gesture", value.toJson().toString()); setKind("gesture")
        return value
    }
    fun gesture(): UnlockGesture? = UnlockGesture.parseOrNull(read("gesture"))
    fun hasPin() = file("pin").baseFile.isFile
    fun clearGesture() { file("gesture").delete(); if (kind == "gesture") setKind("swipe") }
    fun clearPin() { file("pin").delete(); if (kind == "pin") setKind("swipe") }
    fun unlock(service: RemoteService, test: Boolean = false): Int {
        val selected = kind
        val credential = when (selected) { "gesture" -> read("gesture"); "pin" -> read("pin"); else -> "" }
        if (selected == "gesture" && credential.isBlank()) return WakeUnlockResult.GESTURE_EMPTY
        if (selected == "pin" && credential.isBlank()) return WakeUnlockResult.CREDENTIAL_REQUIRED
        return service.unlockPhone(selected, credential, test)
    }
    companion object {
        private const val KEY_ALIAS = "mower-unlock-v1"
        fun resultText(code: Int) = when (code) {
            WakeUnlockResult.OK -> "手机已唤醒并解锁"
            WakeUnlockResult.WAKE_FAILED -> "屏幕未能唤醒，请检查 Shizuku 与系统限制"
            WakeUnlockResult.CREDENTIAL_REQUIRED -> "请先在手机设置解锁 PIN 或录制操作"
            WakeUnlockResult.CREDENTIAL_REJECTED -> "解锁未成功，已停止尝试；请手动解锁并检查记录"
            WakeUnlockResult.NO_KEYGUARD -> "手机未设置锁屏，无需录制或测试解锁"
            WakeUnlockResult.UNSUPPORTED -> "此系统不支持该锁屏操作"
            WakeUnlockResult.LOCK_FAILED -> "未能锁屏，请在手机检查锁屏设置"
            WakeUnlockResult.GESTURE_EMPTY -> "尚无有效解锁录制"
            WakeUnlockResult.GESTURE_SCREEN_MISMATCH -> "屏幕方向或分辨率已变化，请重新录制"
            WakeUnlockResult.RECORD_NO_DEVICE -> "无法读取触摸屏，当前设备或 Shizuku 模式不支持录制"
            WakeUnlockResult.RECORD_TIMEOUT -> "录制超时，原记录已保留"
            WakeUnlockResult.RECORD_CANCELLED -> "已取消录制，原记录已保留"
            WakeUnlockResult.RECORD_NO_TOUCH -> "未录到触摸操作，请使用点击或滑动解锁"
            else -> "后台连接中断，请重新连接后再试"
        }
    }
}
