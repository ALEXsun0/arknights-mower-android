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
        val cache = File(component, "cache")
        val resource = File(cache, "resource")
        check(resource.isDirectory || resource.mkdirs()) { "MAA 活动导航缓存目录创建失败" }
        val target = File(resource, "tasks.json")
        val temporary = File.createTempFile("tasks-", ".json", resource)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(tasks.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        check(loadResource(cache.path)) { "MAA 活动导航加载失败，请检查核心及资源版本" }
        return true
    }
}
