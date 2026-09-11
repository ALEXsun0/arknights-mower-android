package com.aliothmoon.maameow.mower

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

class WebConnectionConfigTest {
    @Test fun automaticDefaultsAllocateAnAvailablePort() {
        val config = WebConnectionConfig.parse("", "")
        assertEquals(0, config.port)
        assertEquals("", config.token)
        assertTrue(config.availablePort(false) in 1024..65535)
    }

    @Test fun fixedValuesArePreservedIncludingMixedCaseTokens() {
        val token = "Example_Custom-Token123"
        val config = WebConnectionConfig.parse(" 58000 ", token)
        assertEquals(58000, config.port)
        assertEquals(token, config.token)
        assertEquals(65535, WebConnectionConfig.parse("65535", "").port)
    }

    @Test fun invalidPortsDoNotSilentlyFallBackToAutomatic() {
        for (port in listOf("0", "1023", "65536", "-1", "1e4", "999999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { WebConnectionConfig.parse(port, "") }
        }
    }

    @Test fun customTokensHaveNoMinimumOrMaximumLength() {
        for (token in listOf("a", "short", "a".repeat(129), "a".repeat(4096))) {
            assertEquals(token, WebConnectionConfig.parse("58000", token).token)
        }
    }

    @Test fun invalidTokensNeverAppearInValidationErrors() {
        for (token in listOf("a&extra=x", "a\n", "a b")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { WebConnectionConfig.parse("", token) }
            assertFalse(failure.message.orEmpty().contains(token))
        }
    }

    @Test fun occupiedFixedPortIsRejectedAndReleasedPortCanBeReused() {
        val occupied = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val config = WebConnectionConfig.parse(occupied.localPort.toString(), "")
        occupied.use {
            assertThrows(IllegalStateException::class.java) { config.availablePort(false) }
            assertThrows(IllegalStateException::class.java) { config.availablePort(true) }
        }
        assertEquals(config.port, config.availablePort(false))
    }
}
