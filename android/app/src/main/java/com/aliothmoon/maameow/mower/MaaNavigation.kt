package com.aliothmoon.maameow.mower

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Install navigation in the component actually loaded by the Android host. */
internal object MaaNavigation {
    fun load(component: File, tasks: JSONObject, running: Boolean, loadResource: (String) -> Boolean): Boolean {
        check(!running) { "MAA 正在运行，不能更新活动导航" }
        require(tasks.length() > 0) { "MAA 活动导航必须是非空 JSON 对象" }
        require(tasks.keys().asSequence().all { tasks.opt(it) is JSONObject }) { "MAA 活动导航的任务定义必须是 JSON 对象" }
        val cache = File(component, "cache")
        val resource = if (File(component, "resource/tasks").isDirectory || File(cache, "resource/tasks").isDirectory) {
            File(cache, "resource/tasks")
        } else File(cache, "resource")
        check(resource.isDirectory || resource.mkdirs()) { "MAA 活动导航缓存目录创建失败" }
        val target = File(resource, "tasks.json")
        check(target.length() <= 4 * 1024 * 1024) { "MAA 活动导航缓存超过大小限制" }
        val previous = if (target.exists()) target.readBytes() else null
        replace(target, tasks.toString().toByteArray(Charsets.UTF_8))
        try {
            check(loadResource(cache.path)) { "MAA 活动导航加载失败，请检查核心及资源版本" }
        } catch (error: Exception) {
            try {
                if (previous == null) Files.deleteIfExists(target.toPath()) else replace(target, previous)
            } catch (rollback: Exception) { error.addSuppressed(rollback) }
            throw error
        }
        return true
    }

    private fun replace(target: File, content: ByteArray) {
        val resource = target.parentFile!!
        val temporary = File.createTempFile("tasks-", ".json", resource)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
