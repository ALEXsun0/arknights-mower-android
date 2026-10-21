package com.aliothmoon.maameow.manager
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
class RootLaunchCommandTest {
    private fun launch(supported: Boolean) {
        val directory = Files.createTempDirectory("root-command").toFile()
        try {
            val marker = directory.resolve("entered")
            directory.resolve("nsenter").apply {
                writeText("""#!/bin/sh
if [ "${'$'}5" = true ]; then exit ${if (supported) 0 else 1}; fi
printf entered > '${marker.path}'
shift 4
exec "${'$'}@"
""")
                setExecutable(true)
            }
            val command = "exec printf '%s' 'phone path with spaces and '\"'\"'quote'"
            val process = ProcessBuilder("sh", "-c", rootMountCommand(command)).redirectErrorStream(true).apply {
                environment()["PATH"] = directory.path + ":" + System.getenv("PATH")
            }.start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
                assertEquals("phone path with spaces and 'quote", process.inputStream.bufferedReader().readText())
                assertEquals(supported, marker.exists())
            } finally { process.destroyForcibly() }
        } finally { directory.deleteRecursively() }
    }
    @Test fun rootEntersInitMountNamespaceWhenAvailable() = launch(true)
    @Test fun unsupportedNsenterKeepsOriginalLaunchCommand() = launch(false)
}
