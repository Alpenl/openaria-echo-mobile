package com.openaria.openaria_echo_mobile.body.api

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveDeviceApiIntegrationTest {
    @Test
    fun `production clients connect to a live Lab v4 device`() {
        val origin = System.getenv("OPENARIA_LIVE_DEVICE_ORIGIN")?.trim().orEmpty()
        assumeTrue("OPENARIA_LIVE_DEVICE_ORIGIN is not configured", origin.isNotEmpty())

        val anonymousProbe = DeviceProbeClient().probe(origin, bearerToken = null)
        val anonymous = assertIs<ProbeResult.Verified>(anonymousProbe).connection
        assertEquals("lab", anonymous.descriptor.securityProfile)
        assertTrue(anonymous.descriptor.networkMutationCapable)
        assertNull(anonymous.bearerToken)
        assertIs<CaptureStatusResult.Snapshot>(DeviceHttpClient().getCaptureStatus(anonymous))
        assertIs<SessionListResult.Page>(DeviceHttpClient().listSessions(anonymous, limit = 50))

        val liveBearerToken = System.getenv("OPENARIA_LIVE_DEVICE_TOKEN")?.trim().orEmpty()
        if (liveBearerToken.isNotEmpty()) {
            val storedTokenProbe = DeviceProbeClient().probe(origin, bearerToken = liveBearerToken)
            val storedTokenConnection = assertIs<ProbeResult.Verified>(storedTokenProbe).connection
            assertEquals(anonymous.descriptor.deviceId, storedTokenConnection.descriptor.deviceId)
        }
    }
}
