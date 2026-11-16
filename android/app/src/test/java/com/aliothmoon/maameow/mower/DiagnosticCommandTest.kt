package com.aliothmoon.maameow.mower

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticCommandTest {
    @Test fun capturesOutputAndRemovesTemporaryFile() {
        val directory = Files.createTempDirectory("diagnostic-command-test").toFile()
        try {
            val result = DiagnosticCommand.collect(listOf("sh", "-c", "printf 'log output'"), directory)
            assertEquals("log output", result)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun hangingCommandCannotBlockExportIndefinitely() {
        val directory = Files.createTempDirectory("diagnostic-command-test").toFile()
        try {
            val started = System.nanoTime()
            val result = DiagnosticCommand.collect(listOf("sh", "-c", "exec sleep 30"), directory, 100)
            assertTrue(result.contains("采集超时"))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingCommandBecomesDiagnosticText() {
        val directory = Files.createTempDirectory("diagnostic-command-test").toFile()
        try {
            val result = DiagnosticCommand.collect(listOf("/missing/mower-logcat"), directory)
            assertTrue(result.contains("系统日志不可读"))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedCommandRetainsErrorOutput() {
        val directory = Files.createTempDirectory("diagnostic-command-test").toFile()
        try {
            val result = DiagnosticCommand.collect(listOf("sh", "-c", "printf 'denied' >&2; exit 1"), directory)
            assertTrue(result.contains("退出码：1"))
            assertTrue(result.contains("denied"))
        } finally { directory.deleteRecursively() }
    }
}
