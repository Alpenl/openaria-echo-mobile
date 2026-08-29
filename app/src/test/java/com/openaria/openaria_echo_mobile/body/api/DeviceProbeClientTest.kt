package com.openaria.openaria_echo_mobile.body.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class DeviceProbeClientTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `verifies Device API v4 descriptor through api device endpoint`() {
        var requestedPath: String? = null
        var authorization: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.respond(200, validDescriptorJson())
        }

        val result = DeviceProbeClient().probe(origin, "  local-token  ")

        if (result is ProbeResult.InvalidResponse) {
            fail("Invalid probe response: ${result.message}")
        }
        val verified = assertIs<ProbeResult.Verified>(result)
        assertEquals("/api/v4/device", requestedPath)
        assertEquals("Bearer local-token", authorization)
        assertEquals(origin, verified.connection.origin)
        assertEquals("local-token", verified.connection.bearerToken)
        assertEquals("YLX-00ABCDEF", verified.connection.descriptor.deviceLabel)
    }

    @Test
    fun `maps unauthorized probe response to authentication required`() {
        val origin = startServer { exchange ->
            exchange.respond(401, """{"schema":"ylx.api-error.v2"}""")
        }

        val result = DeviceProbeClient().probe(origin, null)

        assertIs<ProbeResult.AuthenticationRequired>(result)
    }

    @Test
    fun `rejects public cleartext origin before opening a network connection`() {
        val result = DeviceProbeClient().probe("http://example.com", null)

        assertIs<ProbeResult.RejectedEndpoint>(result)
    }

    @Test
    fun `multi-address fallback resolves bearer independently for each exact origin`() {
        var authorization: String? = null
        val reachableOrigin = startServer { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.respond(200, validDescriptorJson())
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        val unreachableOrigin = "http://127.0.0.1:$closedPort"
        val credentialLookups = mutableListOf<String>()

        val result = DeviceProbeClient().probe(listOf(unreachableOrigin, reachableOrigin)) { origin ->
            credentialLookups += origin
            if (origin == reachableOrigin) "reachable-token" else "must-not-migrate"
        }

        assertIs<ProbeResult.Verified>(result)
        assertEquals(listOf(unreachableOrigin, reachableOrigin), credentialLookups)
        assertEquals("Bearer reachable-token", authorization)
    }

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val nextServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        nextServer.createContext("/") { exchange ->
            handler(exchange)
        }
        nextServer.start()
        server = nextServer
        val port = nextServer.address.port
        return "http://127.0.0.1:$port"
    }

    private fun HttpExchange.respond(statusCode: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(statusCode, bytes.size.toLong())
        responseBody.use { output -> output.write(bytes) }
    }

    private fun validDescriptorJson(): String {
        return """
            {
              "schema": "ylx.device.v4",
              "device": {
                "device_id": "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                "device_label": "YLX-00ABCDEF"
              },
              "hardware_fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "api_version": "4.0",
              "build": {
                "package_version": "0.5.2",
                "commit": "77f24f3777777777777777777777777777777777777",
                "build_id": "rdk-x5-20260828"
              },
              "security_profile": "customer",
              "capabilities": {
                "capture": true,
                "preview": true,
                "range_download": true,
                "network_mutation": true,
                "session_list": true,
                "session_detail": true,
                "artifact_download": true,
                "capture_status": true,
                "session_deletion": false,
                "calibration_capture": {
                  "supported": true,
                  "enabled": false,
                  "disabled_reason": "storage_unavailable",
                  "required_video_layout": "split-eyes"
                }
              },
              "storage": {
                "volume_id": "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                "total_bytes": 1024,
                "available_bytes": 512,
                "writable": false
              },
              "runtime": {
                "observed_at": "2026-08-28T04:00:00Z",
                "connection_method": "wifi_ap",
                "temperature_celsius": 48.2,
                "network": {
                  "ap": {
                    "state": "active",
                    "interface": "uap0",
                    "addresses": ["10.42.0.1/24"],
                    "peer_or_ssid": "YLX-A13F"
                  },
                  "wifi_client": {
                    "state": "active",
                    "interface": "wlan0",
                    "addresses": ["192.168.110.36/24"],
                    "peer_or_ssid": "Studio"
                  },
                  "wired": {
                    "state": "disconnected",
                    "interface": null,
                    "addresses": [],
                    "peer_or_ssid": null
                  },
                  "default_route": "wifi_client"
                },
                "live_imu": null,
                "camera": {
                  "schema": "ylx.camera-connection.v1",
                  "state": "connected"
                },
                "camera_focus": null
              }
            }
        """.trimIndent()
    }
}
