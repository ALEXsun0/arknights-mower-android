package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MowerLogFilesTest {
    @Test fun collectsOnlyBoundedRecentMowerLogs() {
        val root = Files.createTempDirectory("mower-logs").toFile()
        try {
            (1..6).forEach { day ->
                File(root, "runtime.log.2026-09-0$day").apply { writeText("log"); setLastModified(day * 1000L) }
            }
            File(root, "runtime.log").apply { writeText("current"); setLastModified(9000) }
            File(root, "conf.yml").writeText("excluded")
            File(root, "runtime.log.secret").writeText("excluded")
            assertEquals(listOf("runtime.log", "runtime.log.2026-09-06", "runtime.log.2026-09-05", "runtime.log.2026-09-04"), MowerLogFiles.recent(root).map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun truncationPreservesCompleteUtf8LinesAndMarksOmittedHistory() {
        val file = Files.createTempFile("mower-log", ".log").toFile()
        try {
            val last = "Android 设备连接失败\nTraceback: bridge disconnected\n"
            file.writeText("旧日志".repeat(100) + "\n" + last)
            assertEquals("[仅显示日志末尾]\n" + last, MowerLogFiles.tail(file, last.toByteArray().size + 4))
            assertEquals(file.readText(), MowerLogFiles.tail(file, 8192))
        } finally { file.delete() }
    }
}
