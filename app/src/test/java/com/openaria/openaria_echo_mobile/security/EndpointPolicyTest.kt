package com.openaria.openaria_echo_mobile.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EndpointPolicyTest {
    @Test
    fun `allows https origins for any host`() {
        val decision = EndpointPolicy.validate("https://rp-ylx.local")

        val allowed = assertIs<EndpointPolicy.Decision.Allowed>(decision)
        assertEquals("https://rp-ylx.local", allowed.target.origin.toString())
        assertEquals(false, allowed.target.cleartext)
    }

    @Test
    fun `allows cleartext only for local body addresses`() {
        val privateAp = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("http://10.42.0.1:8080"),
        )
        val privateLan = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("http://192.168.7.24"),
        )
        val localMdns = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("http://rp-ylx.local"),
        )

        assertEquals(true, privateAp.target.cleartext)
        assertEquals(true, privateLan.target.cleartext)
        assertEquals(true, localMdns.target.cleartext)
    }

    @Test
    fun `rejects public cleartext http`() {
        val decision = EndpointPolicy.validate("http://example.com")

        val rejected = assertIs<EndpointPolicy.Decision.Rejected>(decision)
        assertEquals(EndpointPolicy.RejectReason.PUBLIC_CLEARTEXT_HTTP, rejected.reason)
    }

    @Test
    fun `rejects credentials path query and fragment`() {
        assertEquals(
            EndpointPolicy.RejectReason.CREDENTIALS_IN_URI,
            assertIs<EndpointPolicy.Decision.Rejected>(
                EndpointPolicy.validate("https://token@rp-ylx.local"),
            ).reason,
        )
        assertEquals(
            EndpointPolicy.RejectReason.PATH_QUERY_OR_FRAGMENT,
            assertIs<EndpointPolicy.Decision.Rejected>(
                EndpointPolicy.validate("https://rp-ylx.local/api/v4/device"),
            ).reason,
        )
        assertEquals(
            EndpointPolicy.RejectReason.PATH_QUERY_OR_FRAGMENT,
            assertIs<EndpointPolicy.Decision.Rejected>(
                EndpointPolicy.validate("https://rp-ylx.local?token=secret"),
            ).reason,
        )
        assertEquals(
            EndpointPolicy.RejectReason.PATH_QUERY_OR_FRAGMENT,
            assertIs<EndpointPolicy.Decision.Rejected>(
                EndpointPolicy.validate("https://rp-ylx.local#token"),
            ).reason,
        )
    }

    @Test
    fun `enforces private ipv4 ranges precisely`() {
        assertIs<EndpointPolicy.Decision.Allowed>(EndpointPolicy.validate("http://172.16.0.1"))
        assertIs<EndpointPolicy.Decision.Allowed>(EndpointPolicy.validate("http://172.31.255.254"))

        val publicNeighbor = assertIs<EndpointPolicy.Decision.Rejected>(
            EndpointPolicy.validate("http://172.32.0.1"),
        )
        assertEquals(EndpointPolicy.RejectReason.PUBLIC_CLEARTEXT_HTTP, publicNeighbor.reason)
    }

    @Test
    fun `allows only local ipv6 cleartext origins`() {
        val loopback = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("http://[::1]:8080"),
        )
        val uniqueLocal = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("http://[fd00::1]"),
        )
        val linkLocal = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("http://[fe80::1]"),
        )
        val publicIpv6 = assertIs<EndpointPolicy.Decision.Rejected>(
            EndpointPolicy.validate("http://[2001:4860:4860::8888]"),
        )

        assertEquals(true, loopback.target.cleartext)
        assertEquals(true, uniqueLocal.target.cleartext)
        assertEquals(true, linkLocal.target.cleartext)
        assertEquals(EndpointPolicy.RejectReason.PUBLIC_CLEARTEXT_HTTP, publicIpv6.reason)
    }

    @Test
    fun `preserves an IPv6 link local interface scope without resolving that interface`() {
        val scoped = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("http://[FE80::1%WiFiA]:8080"),
        )

        assertEquals("http://[fe80::1%WiFiA]:8080", scoped.target.origin.toString())
        assertEquals("[fe80::1%WiFiA]", scoped.target.origin.toURL().host)
        assertEquals(true, scoped.target.cleartext)
    }

    @Test
    fun `canonicalizes host case IDN and default ports in origin identity`() {
        val http = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("http://RP-YLX.LOCAL:80/"),
        )
        val https = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("https://B\u00dcCHER.example:443"),
        )
        val nonDefault = assertIs<EndpointPolicy.Decision.Allowed>(
            EndpointPolicy.validate("https://RP-YLX.LOCAL:8443"),
        )

        assertEquals("http://rp-ylx.local", http.target.origin.toString())
        assertEquals("https://xn--bcher-kva.example", https.target.origin.toString())
        assertEquals("https://rp-ylx.local:8443", nonDefault.target.origin.toString())
    }
}
