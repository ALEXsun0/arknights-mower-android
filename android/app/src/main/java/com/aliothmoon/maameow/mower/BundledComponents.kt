package com.aliothmoon.maameow.mower

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Apply APK defaults once per installation, then allow independent hot updates. */
internal class BundledComponents(private val files: File, private val generation: String) {
    fun refreshMower() = read(File(files, "bundled-mower-apk")) != generation
    fun mowerActivated() = write(File(files, "bundled-mower-apk"), generation)

    fun prepareMaa(open: (String) -> InputStream): Boolean {
        val data = File(files, "mower-data").apply { mkdirs() }
        val marker = File(data, "bundled-maa-apk")
        val component = File(data, "maa-component.zip")
        val hash = File(data, "maa-component.sha256")
        if (read(marker) == generation && component.length() > 0 &&
            read(hash).matches(Regex("[a-f0-9]{64}")) && File(data, "maa-component-format").isFile) return false
        val stage = File(data, "maa-bundled-stage").apply { deleteRecursively(); mkdirs() }
        try {
            val expected = open("maa-component.sha256").bufferedReader().use { it.readText().trim() }
            check(expected.matches(Regex("[a-f0-9]{64}"))) { "内置 MAA 校验信息无效" }
            val digest = MessageDigest.getInstance("SHA-256")
            val archive = File(stage, "component.zip")
            open("maa-component.zip").use { input -> archive.outputStream().use { output ->
                val buffer = ByteArray(262144)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            check(archive.length() > 0 && digest.digest().joinToString("") { "%02x".format(it) } == expected) {
                "内置 MAA 组件校验失败，原组件已保留"
            }
            // Until the generation is committed, interrupted copies are retried.
            check(archive.renameTo(component)) { "无法切换内置 MAA 组件" }
            write(hash, expected)
            write(File(data, "maa-bundled-pending"), generation)
            write(File(data, "maa-component-format"), "ncnn-v1")
            write(marker, generation)
            return true
        } finally { stage.deleteRecursively() }
    }

    private fun read(file: File) = runCatching { file.readText().trim() }.getOrDefault("")
    private fun write(file: File, value: String) {
        val stage = File(file.parentFile, file.name + ".new")
        stage.writeText(value)
        check(stage.renameTo(file)) { "无法保存内置组件安装状态" }
    }
}
