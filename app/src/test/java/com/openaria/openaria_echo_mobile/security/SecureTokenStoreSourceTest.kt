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
        assertContains(uiSource, "if (savedToken.isNotBlank())")
        assertContains(uiSource, "tokenStore.saveForVerifiedBody(")
        assertContains(uiSource, "origin = connection.origin")
        assertContains(uiSource, "deviceId = connection.descriptor.deviceId")
        assertContains(uiSource, "tokenStore.clear(credentialOrigin)")
    }
}
