package com.aliothmoon.maameow.mower

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** A user stop or failed cleanup cancels the one requested update restart. */
internal class RuntimeRestart {
    private var pending = false

    @Synchronized fun request(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    @Synchronized fun cancel() { pending = false }

    @Synchronized fun complete(cleaned: Boolean, start: () -> Unit) {
        val restart = pending && cleaned
        pending = false
        if (restart) start()
    }

    companion object {
        internal fun directory(data: File): File {
            val key = MessageDigest.getInstance("SHA-256").digest("/mower".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(20)
            return File(data, "tmp/updates/$key/android-service")
        }

        fun acceptRequest(data: File, job: String, target: String) {
            require(job.matches(Regex("[a-f0-9]{32}"))) { "Mower 重启作业标识无效" }
            val jobDir = File(directory(data), job)
            val request = File(jobDir, "restart-request")
            check(request.readText() == target) { "重启请求与待启动版本不符" }
            check(request.renameTo(File(jobDir, "restart-accepted"))) { "重启请求已撤回或无法确认" }
        }

        fun cancelResume(data: File) {
            val directory = directory(data)
            val journal = File(directory, "service-restart.json")
            if (!journal.isFile) return
            val saved = JSONObject(journal.readText())
            if (saved.optString("status") != "running") return
            val temporary = File(directory, "service-restart-cancelled.native.new")
            temporary.writeText(saved.getString("id"))
            check(temporary.renameTo(File(directory, "service-restart-cancelled"))) { "无法取消任务续接" }
        }
    }
}
