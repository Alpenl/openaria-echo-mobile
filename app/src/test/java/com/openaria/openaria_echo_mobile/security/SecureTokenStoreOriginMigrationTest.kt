package com.openaria.openaria_echo_mobile.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecureTokenStoreOriginMigrationTest {
    @Test
    fun `canonical origins expose only their legacy explicit default port alias`() {
        assertEquals("http://body.local:80", legacyExplicitDefaultPortOrigin("http://body.local"))
        assertEquals("https://body.local:443", legacyExplicitDefaultPortOrigin("https://body.local"))
        assertEquals("http://[fe80::1]:80", legacyExplicitDefaultPortOrigin("http://[fe80::1]"))
        assertNull(legacyExplicitDefaultPortOrigin("http://body.local:8080"))
        assertNull(legacyExplicitDefaultPortOrigin("ftp://body.local"))
        assertNull(legacyExplicitDefaultPortOrigin("not an origin"))
    }
}
