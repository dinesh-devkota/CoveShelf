package com.dineshdevkota.cinevault.core

import org.junit.Assert.*
import org.junit.Test

class NasAddressTest {
    @Test fun acceptsPastedNetworkAddressesWithoutTreatingTheShareAsTheHost() {
        for (input in listOf("nas.local", " nas.local ", "SMB://nas.local/Media/Movies", "\\\\nas.local\\Media\\Movies")) {
            assertEquals(NasAddress("nas.local", null), NasAddress.parse(input))
        }
        assertEquals("192.168.1.20", NasAddress.parse("smb://192.168.1.20/media").host)
    }

    @Test fun retainsEmbeddedPortAndLetsTheExplicitPortOverrideIt() {
        assertEquals(NasAddress("nas.local", 1445), NasAddress.parse("smb://nas.local:1445/media"))
        assertEquals(NasAddress("nas.local", 2445), NasAddress.parse("nas.local:1445", 2445))
        assertEquals("nas.local:2445", NasAddress.parse("nas.local", 2445).authority)
    }

    @Test fun ipv6UsesAnUnbracketedConnectionHostAndBracketedDisplayAuthority() {
        for (input in listOf("2001:db8::1", "[2001:db8::1]", "smb://[2001:db8::1]/media")) {
            assertEquals(NasAddress("2001:db8::1", null), NasAddress.parse(input))
        }
        assertEquals("[2001:db8::1]:1445", NasAddress.parse("smb://[2001:db8::1]:1445/media").authority)
        assertEquals("::ffff:192.0.2.1", NasAddress.parse("::ffff:192.0.2.1").host)
        assertEquals("fe80::1%wlan0", NasAddress.parse("smb://[fe80::1%25wlan0]").host)
    }

    @Test fun rejectsInvalidOrCredentialBearingAddressesWithoutEchoingThem() {
        for (input in listOf("", "bad host", "https://nas.local", "smb://user:private-password@nas.local/media",
            "nas.local?secret=value", "nas.local#fragment", "[nas.local]", "2001:::1", "1::2::3",
            "[::1", "::gggg", ":1::", "1::2:", "192.0.2.1::", "999.1.1.1", "nas..local")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { NasAddress.parse(input) }
            assertEquals("Enter a valid NAS hostname or IP address. Enter credentials in the sign-in fields.", failure.message)
        }
    }

    @Test fun rejectsInvalidPortsInsteadOfSilentlyConnectingOnTheDefaultPort() {
        for (input in listOf("nas:", "nas:0", "nas:65536", "nas:-1", "nas:abc", "[::1]:999999999999")) {
            assertEquals("Port must be a number from 1 to 65535.",
                assertThrows(IllegalArgumentException::class.java) { NasAddress.parse(input) }.message)
        }
        for (port in listOf(-1, 0, 65536)) {
            assertThrows(IllegalArgumentException::class.java) { NasAddress.parse("nas", port) }
        }
    }
}
