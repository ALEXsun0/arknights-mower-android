package com.aliothmoon.maameow.mower

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class WebConnectionPreferencesTest {
    private class Store {
        val disk = mutableMapOf<String, Any>()
        var fail = false
        fun open(): SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean", "getString" -> disk[args!![0]] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, Any>()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)) { proxy, edit, values ->
                        when (edit.name) {
                            "putBoolean", "putString" -> { pending[values!![0] as String] = values[1]; proxy }
                            "commit" -> if (fail) false else { disk.putAll(pending); true }
                            else -> error("Unexpected editor operation: ${edit.name}")
                        }
                    }
                }
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun customValuesSurviveReopeningTheStore() {
        val store = Store()
        val value = SavedWebConnection(true, "58000", "Example_Custom-Token123")
        WebConnectionPreferences.save(store.open(), value)
        assertEquals(value, WebConnectionPreferences.read(store.open()))
    }

    @Test fun shortCustomTokenReplacesGeneratedValuesAndSurvivesStartup() {
        val store = Store()
        WebConnectionPreferences.resolve(store.open()) { "Example_AutomaticToken123" }
        val value = SavedWebConnection(true, "58000", "a")
        WebConnectionPreferences.save(store.open(), value)
        assertEquals(value, WebConnectionPreferences.read(store.open()))
        assertEquals(value, WebConnectionPreferences.resolve(store.open()) { error("must reuse custom token") })
    }

    @Test fun generatedConnectionIsPersistedAndReusedAfterRestart() {
        val store = Store()
        var generations = 0
        val first = WebConnectionPreferences.resolve(store.open()) { generations++; "Example_AutomaticToken123" }
        assertTrue(first.port.toInt() in 1024..65535)
        assertEquals(first, WebConnectionPreferences.resolve(store.open()) { error("must reuse saved token") })
        assertEquals(1, generations)
    }

    @Test fun failedCommitIsReportedWithoutExposingTheToken() {
        val store = Store().apply { fail = true }
        val secret = "Example_SecretToken123"
        val error = assertThrows(IllegalStateException::class.java) {
            WebConnectionPreferences.save(store.open(), SavedWebConnection(true, "58000", secret))
        }
        assertFalse(error.message.orEmpty().contains(secret))
        assertEquals("", WebConnectionPreferences.read(store.open()).token)
    }
}
