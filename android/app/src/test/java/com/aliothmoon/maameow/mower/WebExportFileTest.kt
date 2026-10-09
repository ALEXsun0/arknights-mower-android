package com.aliothmoon.maameow.mower

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class WebExportFileTest {
    @Test fun binaryExportsRetainBytesAndRemoveTemporaryFiles() {
        val directory = Files.createTempDirectory("web-export-test").toFile()
        try {
            val bytes = ByteArray(WebExportFile.CHUNK_BYTES + 17) { (it % 256).toByte() }
            for ((name, mime) in listOf("plan.json" to "application/json", "排班.jpg" to "image/jpeg", "mower-config.zip" to "application/zip", "仓库.png" to "image/png")) {
                val export = WebExportFile(directory, name, mime, bytes.size.toLong())
                export.use {
                    bytes.asList().chunked(WebExportFile.CHUNK_BYTES).forEach {
                        export.append(Base64.getEncoder().encodeToString(it.toByteArray()))
                    }
                    export.finish()
                    val result = ByteArrayOutputStream()
                    export.copyTo(result) { false }
                    assertArrayEquals(bytes, result.toByteArray())
                }
                assertFalse(export.file.exists())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun incompleteAndCancelledExportsNeverComplete() {
        val directory = Files.createTempDirectory("web-export-test").toFile()
        try {
            WebExportFile(directory, "plan.json", "application/json", 3).use { export ->
                export.append("AQI=")
                assertThrows(IllegalStateException::class.java) { export.finish() }
                assertThrows(IllegalStateException::class.java) { export.copyTo(ByteArrayOutputStream()) { false } }
                export.append("Aw==")
                export.finish()
                assertThrows(IllegalStateException::class.java) { export.copyTo(ByteArrayOutputStream()) { true } }
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidMetadataAndOversizedChunksAreRejected() {
        val directory = Files.createTempDirectory("web-export-test").toFile()
        try {
            for (name in listOf("../conf.yml", "a\\b.zip", "bad\nname", "", ".", "..")) {
                assertThrows(IllegalArgumentException::class.java) { WebExportFile(directory, name, "application/zip", 0) }
            }
            for (size in listOf(-1L, WebExportFile.MAX_BYTES + 1)) {
                assertThrows(IllegalArgumentException::class.java) { WebExportFile(directory, "export.zip", "application/zip", size) }
            }
            assertThrows(IllegalArgumentException::class.java) { WebExportFile(directory, "export.zip", "invalid", 0) }
            WebExportFile(directory, "export.zip", "application/zip", 1).use { export ->
                assertThrows(IllegalArgumentException::class.java) { export.append("A".repeat(70 * 1024)) }
                assertThrows(IllegalArgumentException::class.java) { export.append("AQI=") }
                assertThrows(IllegalArgumentException::class.java) { export.append("????") }
            }
        } finally { directory.deleteRecursively() }
    }
}
