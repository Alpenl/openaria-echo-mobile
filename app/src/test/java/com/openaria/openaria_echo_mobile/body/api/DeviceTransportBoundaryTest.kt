package com.openaria.openaria_echo_mobile.body.api

import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class DeviceTransportBoundaryTest {
    @Test
    fun `probe rejects redirect without contacting its target`() {
        withRedirectServers { source, target ->
            source.enqueue(redirect(target, 302, "/probe-target"))

            val result = DeviceProbeClient().probe(source.origin(), BEARER_TOKEN)

            assertProtocolRedirect(result, 302, "absolute:http")
            assertEquals("Bearer $BEARER_TOKEN", source.takeRequest().getHeader("Authorization"))
            assertNoRequest(target)
        }
    }

    @Test
    fun `JSON and SSE requests reject redirects without contacting their targets`() {
        withRedirectServers { source, target ->
            source.enqueue(redirect(target, 307, "/json-target"))
            source.enqueue(redirect(target, 302, "/sse-target"))
            val connection = connection(source.origin(), BEARER_TOKEN)

            val json = DeviceHttpClient().getCaptureStatus(connection)
            val sse = DeviceHttpClient().readCaptureEvents(connection)

            assertProtocolRedirect(json, 307, "absolute:http")
            assertProtocolRedirect(sse, 302, "absolute:http")
            assertEquals("Bearer $BEARER_TOKEN", source.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer $BEARER_TOKEN", source.takeRequest().getHeader("Authorization"))
            assertNoRequest(target)
        }
    }

    @Test
    fun `artifact GET and HEAD reject redirects without contacting their targets`() {
        withRedirectServers { source, target ->
            source.enqueue(redirect(target, 302, "/artifact-get-target"))
            source.enqueue(redirect(target, 301, "/artifact-head-target"))
            val connection = connection(source.origin(), BEARER_TOKEN)
            val artifact = artifactDescriptor()

            val download = DeviceHttpClient().downloadSessionArtifact(
                connection = connection,
                sessionId = SESSION_ID,
                artifact = artifact,
                output = ByteArrayOutputStream(),
            )
            val head = DeviceHttpClient().headSessionArtifact(
                connection = connection,
                sessionId = SESSION_ID,
                artifact = artifact,
            )

            assertProtocolRedirect(download, 302, "absolute:http")
            assertProtocolRedirect(head, 301, "absolute:http")
            assertEquals("Bearer $BEARER_TOKEN", source.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer $BEARER_TOKEN", source.takeRequest().getHeader("Authorization"))
            assertNoRequest(target)
        }
    }

    @Test
    fun `relative redirect is rejected at the original origin`() {
        val source = MockWebServer()
        try {
            source.start()
            source.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/api/v4/device/elsewhere"),
            )

            val result = DeviceProbeClient().probe(source.origin(), BEARER_TOKEN)

            assertProtocolRedirect(result, 302, "relative")
            assertEquals(1, source.requestCount)
        } finally {
            source.shutdown()
        }
    }

    @Test
    fun `redirect metadata never retains credentials path query or oversized location`() {
        val source = MockWebServer()
        try {
            source.start()
            source.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://user:secret@example.com/private?token=secret#fragment"),
            )
            source.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", "https://example.com/${"x".repeat(4_096)}"),
            )
            source.enqueue(MockResponse().setResponseCode(308))

            val withCredentials = DeviceProbeClient().probe(source.origin(), BEARER_TOKEN)
            val oversized = DeviceProbeClient().probe(source.origin(), BEARER_TOKEN)
            val absent = DeviceProbeClient().probe(source.origin(), BEARER_TOKEN)

            assertProtocolRedirect(withCredentials, 302, "absolute:https")
            assertProtocolRedirect(oversized, 307, "oversized")
            assertProtocolRedirect(absent, 308, null)
            listOf(withCredentials, oversized, absent)
                .map { assertIs<ProbeResult.HttpFailure>(it).locationSummary.orEmpty() }
                .forEach { summary ->
                    assertEquals(false, summary.contains("secret"))
                    assertEquals(false, summary.contains("token"))
                    assertEquals(false, summary.contains("private"))
                }
        } finally {
            source.shutdown()
        }
    }

    private fun assertProtocolRedirect(
        result: Any,
        statusCode: Int,
        locationSummary: String?,
    ) {
        val failure = assertIs<DeviceHttpFailureResult>(result)
        assertEquals(statusCode, failure.statusCode)
        assertEquals(DeviceHttpFailure.CODE_PROTOCOL_REDIRECT, failure.errorCode)
        assertEquals(locationSummary, failure.locationSummary)
    }

    private fun withRedirectServers(block: (MockWebServer, MockWebServer) -> Unit) {
        val source = MockWebServer()
        val target = MockWebServer()
        try {
            source.start()
            target.start()
            block(source, target)
        } finally {
            source.shutdown()
            target.shutdown()
        }
    }

    private fun redirect(target: MockWebServer, status: Int, targetPath: String): MockResponse {
        return MockResponse()
            .setResponseCode(status)
            .setHeader("Location", target.url(targetPath))
    }

    private fun assertNoRequest(server: MockWebServer) {
        assertEquals(0, server.requestCount)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    private fun MockWebServer.origin(): String = url("/").toString().removeSuffix("/")

    private fun connection(origin: String, bearerToken: String?): DeviceConnection {
        val target = assertIs<EndpointPolicy.Decision.Allowed>(EndpointPolicy.validate(origin)).target
        return DeviceConnection(target = target, descriptor = descriptor(), bearerToken = bearerToken)
    }

    private fun descriptor(): DeviceDescriptor {
        return DeviceDescriptor(
            deviceId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
            deviceLabel = "rp-ylx-a13f",
            hardwareFingerprint = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            packageVersion = "0.5.2",
            commit = "77f24f3777777777777777777777777777777777",
            buildId = "rdk-x5-20260828",
            securityProfile = "customer",
            captureCapable = true,
            previewCapable = true,
            rangeDownloadCapable = true,
            networkMutationCapable = true,
            volumeId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
            totalBytes = 1024L,
            availableBytes = 512L,
            writable = true,
            runtime = DeviceRuntime(
                observedAt = "2026-08-28T04:00:00Z",
                connectionMethod = "wifi_ap",
                temperatureCelsius = 48.2,
            ),
        )
    }

    private fun artifactDescriptor(): ArtifactDescriptor {
        return ArtifactDescriptor(
            artifactId = ONE_BYTE_SHA256,
            role = "frames.index",
            path = "frames.ndjson",
            mediaType = "application/x-ndjson",
            bytes = 1L,
            sha256 = ONE_BYTE_SHA256,
        )
    }

    private companion object {
        const val BEARER_TOKEN = "session-token"
        const val SESSION_ID = "01991b70-7c88-7123-9234-123456789abc"
        const val ONE_BYTE_SHA256 = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
    }
}
