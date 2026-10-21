package com.aliothmoon.maameow.remote.internal
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VirtualDisplayFallbackTest {
    @Test fun fullPermissionSucceedsWithoutFallback() {
        val calls = mutableListOf<Int>()
        val result = VirtualDisplayManager.createWithFallback(listOf(100, 200)) { calls += it; "display" }
        assertEquals(0 to "display", result); assertEquals(listOf(100), calls)
    }
    @Test fun deniedTrustedDisplayRetriesWithReducedFlags() {
        val calls = mutableListOf<Int>()
        val result = VirtualDisplayManager.createWithFallback(listOf(100, 200)) {
            calls += it
            if (it == 100) throw SecurityException("ADD_TRUSTED_DISPLAY denied")
            "display"
        }
        assertEquals(1 to "display", result); assertEquals(listOf(100, 200), calls)
    }
    @Test fun nonPermissionFailureDoesNotRetry() {
        val failure = IllegalStateException("service unavailable"); var calls = 0
        val caught = runCatching { VirtualDisplayManager.createWithFallback(listOf(100, 200)) { calls++; throw failure } }.exceptionOrNull()
        assertSame(failure, caught); assertEquals(1, calls)
    }
    @Test fun finalPermissionFailureIsReported() {
        val failure = SecurityException("still denied"); var calls = 0
        val caught = runCatching { VirtualDisplayManager.createWithFallback(listOf(100, 200)) { calls++; throw failure } }.exceptionOrNull()
        assertSame(failure, caught); assertEquals(2, calls)
    }
    @Test fun sdkTiersPreserveFocusAndScreenIsolationOnlyWhenPermitted() {
        assertArrayEquals(intArrayOf(), VirtualDisplayManager.flagTiers(28).single())
        assertArrayEquals(intArrayOf(1024, 2048, 4096, 8192), VirtualDisplayManager.flagTiers(33)[0])
        assertArrayEquals(intArrayOf(1024, 2048, 4096, 8192, 16384, 32768, 65536), VirtualDisplayManager.flagTiers(34)[0])
        assertArrayEquals(intArrayOf(8192), VirtualDisplayManager.flagTiers(36)[1])
    }
}
