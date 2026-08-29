package com.openaria.openaria_echo_mobile.ui

import com.openaria.openaria_echo_mobile.body.api.CalibrationCaptureCapability
import com.openaria.openaria_echo_mobile.body.api.DeviceAdmissionCandidate
import com.openaria.openaria_echo_mobile.body.api.DeviceConnection
import com.openaria.openaria_echo_mobile.body.api.DeviceDescriptor
import com.openaria.openaria_echo_mobile.body.api.DeviceRuntime
import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionAdmissionFenceTest {
    @Test
    fun `only the current generation with the same origin and token may publish`() {
        val fence = ConnectionAdmissionFence()
        val first = fence.begin(
            listOf(DeviceAdmissionCandidate(ORIGIN, "  first-token  ")),
        )

        assertTrue(fence.canPublish(first, connection(ORIGIN, "first-token")))
        assertFalse(fence.canPublish(first, connection(ORIGIN, "other-token")))

        val second = fence.begin(
            listOf(DeviceAdmissionCandidate(ORIGIN, "second-token")),
        )

        assertFalse(fence.canPublish(first, connection(ORIGIN, "first-token")))
        assertTrue(fence.canPublish(second, connection(ORIGIN, "second-token")))
        assertFalse(fence.canPublish(second, connection(OTHER_ORIGIN, "second-token")))

        fence.cancel(second)
        assertFalse(fence.canPublish(second, connection(ORIGIN, "second-token")))
    }

    @Test
    fun `component disposal invalidates the active generation`() {
        val fence = ConnectionAdmissionFence()
        val attempt = fence.begin(listOf(DeviceAdmissionCandidate(ORIGIN, null)))

        fence.cancelCurrent()

        assertFalse(fence.isCurrent(attempt))
    }

    @Test
    fun `explicit backup credential is bound only to the challenged origin`() {
        val candidates = buildAdmissionCandidates(
            origins = listOf(ORIGIN, OTHER_ORIGIN),
            primaryOrigin = ORIGIN,
            authorizationOrigin = OTHER_ORIGIN,
            typedToken = "  backup-token  ",
            storedTokenForOrigin = { error("stored tokens are not read during an explicit retry") },
        )

        assertEquals(null, candidates[0].normalizedBearerToken)
        assertEquals("backup-token", candidates[1].normalizedBearerToken)
    }

    @Test
    fun `stale authorization origin cannot receive or redirect a credential`() {
        val candidates = buildAdmissionCandidates(
            origins = listOf(ORIGIN, OTHER_ORIGIN),
            primaryOrigin = ORIGIN,
            authorizationOrigin = "http://127.0.0.1:8082",
            typedToken = "stale-token",
            storedTokenForOrigin = { error("stored tokens are not read during an explicit retry") },
        )

        assertEquals(listOf(null, null), candidates.map { it.normalizedBearerToken })
    }

    @Test
    fun `changing challenged origin clears token entered for the previous target`() {
        val backupTokenAfterPrimaryChallenge = typedTokenAfterAuthorizationTargetChange(
            currentAuthorizationOrigin = OTHER_ORIGIN,
            nextAuthorizationOrigin = ORIGIN,
            typedToken = "backup-token",
        )
        val tokenForSameTarget = typedTokenAfterAuthorizationTargetChange(
            currentAuthorizationOrigin = OTHER_ORIGIN,
            nextAuthorizationOrigin = "  $OTHER_ORIGIN  ",
            typedToken = "backup-token",
        )

        assertEquals("", backupTokenAfterPrimaryChallenge)
        assertEquals("backup-token", tokenForSameTarget)
    }

    private fun connection(origin: String, token: String?): DeviceConnection {
        val target = assertIs<EndpointPolicy.Decision.Allowed>(EndpointPolicy.validate(origin)).target
        return DeviceConnection(
            target = target,
            descriptor = DeviceDescriptor(
                deviceId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                deviceLabel = "YLX-A13F",
                hardwareFingerprint = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                packageVersion = "0.5.2",
                commit = "77f24f3777777777777777777777777777777777",
                buildId = "rdk-x5-20260828",
                securityProfile = "customer",
                captureCapable = true,
                previewCapable = true,
                rangeDownloadCapable = true,
                networkMutationCapable = true,
                sessionListCapable = true,
                sessionDetailCapable = true,
                artifactDownloadCapable = true,
                captureStatusCapable = true,
                sessionDeletionCapable = false,
                calibrationCapture = CalibrationCaptureCapability(
                    supported = true,
                    enabled = true,
                    disabledReason = null,
                    requiredVideoLayout = "split-eyes",
                ),
                volumeId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                totalBytes = 1024L,
                availableBytes = 512L,
                writable = true,
                runtime = DeviceRuntime(
                    observedAt = "2026-08-28T04:00:00Z",
                    connectionMethod = "wifi_ap",
                    temperatureCelsius = 48.2,
                ),
            ),
            bearerToken = token,
        )
    }

    private companion object {
        const val ORIGIN = "http://127.0.0.1:8080"
        const val OTHER_ORIGIN = "http://127.0.0.1:8081"
    }
}
