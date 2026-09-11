package com.aliothmoon.maameow.mower

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class RuntimeNetworkTest {
    @Test fun usesNetworkDnsIncludingVpnInsteadOfFixedPublicServers() {
        val value = resolverConfig(listOf(InetAddress.getByName("10.0.0.2"), InetAddress.getByName("192.168.50.1")))!!
        assertTrue(value.startsWith("nameserver 10.0.0.2\nnameserver 192.168.50.1\n"))
        assertFalse(value.contains("223.5.5.5"))
    }
    @Test fun acceptsIpv6ButExcludesUnusableScopedLinkLocalAddresses() {
        val value = resolverConfig(listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("2001:db8::53")))!!
        assertFalse(value.contains("fe80"))
        assertTrue(value.contains("2001:db8"))
    }
    @Test fun missingNetworkPreservesExistingResolver() {
        assertNull(resolverConfig(emptyList()))
        assertNull(resolverConfig(listOf(InetAddress.getByName("0.0.0.0"))))
    }
    @Test fun respectsResolverLimitWithoutDuplicateServers() {
        val value = resolverConfig(listOf("1.1.1.1", "1.1.1.1", "8.8.8.8", "9.9.9.9", "4.4.4.4").map(InetAddress::getByName))!!
        assertEquals(3, value.lines().count { it.startsWith("nameserver") })
        assertFalse(value.contains("4.4.4.4"))
    }
}
