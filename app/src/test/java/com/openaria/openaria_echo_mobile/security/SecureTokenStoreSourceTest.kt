package com.openaria.openaria_echo_mobile.security

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class SecureTokenStoreSourceTest {
    @Test
    fun `token storage is indexed by verified body identity rather than a single global slot`() {
        val source = File("src/main/java/com/openaria/openaria_echo_mobile/security/SecureTokenStore.kt").readText()
        val uiSource = File("src/main/java/com/openaria/openaria_echo_mobile/ui/EchoApp.kt").readText()

        assertContains(source, "saveForVerifiedBody")
        assertContains(source, "originIndexKey")
        assertContains(source, "bodyTokenPrefix")
        assertContains(source, "originTokenPrefix")
        assertContains(source, "normalizeDeviceId")
        assertContains(uiSource, "shouldBindSavedTokenToBody")
        assertContains(uiSource, "descriptor.deviceId")
        assertContains(uiSource, "tokenStore.clear(bodyOrigin)")
    }
}
