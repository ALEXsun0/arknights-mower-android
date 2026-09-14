package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PythonStartupTest {
    @Test fun advancingInstallationCanExceedTheOldOneMinuteLimit() {
        val startup = PythonStartup(0)
        assertTrue(startup.observe(PythonStartupProgress("解压 MAA 组件", 10), 40_000))
        assertTrue(startup.observe(PythonStartupProgress("解压 MAA 组件", 20), 80_000))
        assertFalse(startup.expired(100_000))
        assertTrue(startup.expired(140_000))
        assertTrue(startup.failure(140_000).contains("解压 MAA 组件 20%"))
    }

    @Test fun staleOrRepeatedProgressCannotKeepStartupAlive() {
        val startup = PythonStartup(0)
        val first = PythonStartupProgress("校验 MAA 组件", 0)
        startup.observe(first, 1_000)
        startup.observe(PythonStartupProgress("校验 MAA 组件", 5), 2_000)
        assertFalse(startup.observe(first, 59_000))
        assertFalse(startup.observe(null, 60_000))
        assertTrue(startup.expired(62_000))
    }

    @Test fun continuouslyChangingProgressStillHasAHardLimit() {
        val startup = PythonStartup(0)
        for (i in 1..12) startup.observe(PythonStartupProgress("解压 MAA 组件", i * 5), i * 50_000L)
        assertTrue(startup.expired(600_000))
        assertTrue(startup.failure(600_000).contains("10 分钟"))
    }

    @Test fun missingOrIncompleteStatusIsIgnored() {
        val dir = Files.createTempDirectory("python-startup").toFile()
        try {
            val file = java.io.File(dir, "runtime-startup.json")
            assertNull(PythonStartupProgress.read(file))
            file.writeText("{")
            assertNull(PythonStartupProgress.read(file))
            file.writeText("""{"stage":"解压 MAA 组件","percent":45}""")
            assertEquals(PythonStartupProgress("解压 MAA 组件", 45), PythonStartupProgress.read(file))
            file.writeText("""{"stage":"解压 MAA 组件","percent":101}""")
            assertNull(PythonStartupProgress.read(file))
        } finally { dir.deleteRecursively() }
    }
}
