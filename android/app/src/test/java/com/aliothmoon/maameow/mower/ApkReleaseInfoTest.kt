package com.aliothmoon.maameow.mower
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ApkReleaseInfoTest {
    private fun release(hash: String, code: Int) = JSONObject().put("host_sha256", hash).put("host_version_code", code)
    @Test fun resourceOnlyReleaseDoesNotPromptForApk() {
        assertFalse(apkHostUpdateAvailable("a".repeat(64), 15, release("a".repeat(64), 20)))
    }
    @Test fun newerHostPromptsEvenIfLatestReleaseOnlyRefreshesResources() {
        assertTrue(apkHostUpdateAvailable("a".repeat(64), 15, release("b".repeat(64), 16)))
    }
    @Test fun olderOrUnknownHostDoesNotPrompt() {
        assertFalse(apkHostUpdateAvailable("a".repeat(64), 20, release("b".repeat(64), 16)))
        assertFalse(apkHostUpdateAvailable("a".repeat(64), 15, JSONObject()))
    }
}
