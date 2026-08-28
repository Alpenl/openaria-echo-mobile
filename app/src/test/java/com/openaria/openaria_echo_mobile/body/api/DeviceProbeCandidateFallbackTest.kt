package com.openaria.openaria_echo_mobile.body.api

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class DeviceProbeCandidateFallbackTest {
    @Test
    fun `probe falls back to the next origin after a connection failure`() {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(deviceDescriptorJson()),
            )
            val unavailableOrigin = unusedLoopbackOrigin()

            val result = DeviceProbeClient().probe(
                origins = listOf(unavailableOrigin, server.origin()),
                bearerToken = "session-token",
            )

            val verified = assertIs<ProbeResult.Verified>(result)
            assertEquals(server.origin(), verified.connection.origin)
            assertEquals(null, server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `candidate resolver loads credentials independently for each exact origin`() {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(deviceDescriptorJson()),
            )
            val unavailableOrigin = unusedLoopbackOrigin()
            val requestedOrigins = mutableListOf<String>()

            val result = DeviceProbeClient().probe(
                origins = listOf(unavailableOrigin, server.origin()),
            ) { origin ->
                requestedOrigins += origin
                if (origin == server.origin()) "candidate-token" else "primary-token"
            }

            assertIs<ProbeResult.Verified>(result)
            assertEquals(listOf(unavailableOrigin, server.origin()), requestedOrigins)
            assertEquals("Bearer candidate-token", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `probe does not fall back after an authenticated origin responds`() {
        val first = MockWebServer()
        val second = MockWebServer()
        try {
            first.start()
            second.start()
            first.enqueue(MockResponse().setResponseCode(401))
            second.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(deviceDescriptorJson()),
            )

            val result = DeviceProbeClient().probe(
                origins = listOf(first.origin(), second.origin()),
                bearerToken = "session-token",
            )

            assertIs<ProbeResult.AuthenticationRequired>(result)
            assertEquals(1, first.requestCount)
            assertEquals(0, second.requestCount)
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    private fun unusedLoopbackOrigin(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    private fun MockWebServer.origin(): String = url("/").toString().removeSuffix("/")

    private fun deviceDescriptorJson(): String {
        return requireNotNull(
            javaClass.classLoader?.getResource("device-api/v4/valid/device-raw-live-imu.json"),
        ).readText()
    }
}
