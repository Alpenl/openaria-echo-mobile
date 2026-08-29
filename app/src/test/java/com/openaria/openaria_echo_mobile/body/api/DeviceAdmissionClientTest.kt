package com.openaria.openaria_echo_mobile.body.api

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class DeviceAdmissionClientTest {
    @Test
    fun `admission requires device descriptor and initial capture snapshot on one identity`() {
        withServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("device-full-capabilities.json")))
            server.enqueue(jsonResponse(200, idleCaptureStatusJson()))

            val result = DeviceAdmissionClient().admit(
                candidates = listOf(DeviceAdmissionCandidate(server.origin(), "  session-token  ")),
                isAttemptCurrent = { true },
            )

            val admission = assertIs<DeviceAdmissionResult.Verified>(result).admission
            val deviceRequest = server.takeRecordedRequest()
            val captureRequest = server.takeRecordedRequest()
            assertEquals("/api/v4/device", deviceRequest.path)
            assertEquals("/api/v4/capture/status", captureRequest.path)
            assertEquals("Bearer session-token", deviceRequest.getHeader("Authorization"))
            assertEquals("Bearer session-token", captureRequest.getHeader("Authorization"))
            assertEquals(server.origin(), admission.connection.origin)
            assertEquals("session-token", admission.connection.bearerToken)
            assertEquals(true, admission.connection.descriptor.sessionListCapable)
            assertEquals(true, admission.connection.descriptor.sessionDetailCapable)
            assertEquals(true, admission.connection.descriptor.artifactDownloadCapable)
            assertEquals(true, admission.connection.descriptor.captureStatusCapable)
            assertEquals(false, admission.connection.descriptor.sessionDeletionCapable)
            assertEquals("idle", admission.initialCaptureStatus.deviceState)
            assertEquals(7L, admission.initialCaptureStatus.sourceRevision)
        }
    }

    @Test
    fun `legacy five-key descriptor is rejected before capture status without fallback`() {
        withServer { server ->
            server.enqueue(
                jsonResponse(
                    200,
                    fixtureText("device-legacy-five-capabilities.json", "invalid"),
                ),
            )
            server.enqueue(jsonResponse(200, idleCaptureStatusJson()))

            val result = DeviceAdmissionClient().admit(
                candidates = listOf(DeviceAdmissionCandidate(server.origin(), null)),
                isAttemptCurrent = { true },
            )

            val invalid = assertIs<DeviceAdmissionResult.InvalidResponse>(result)
            assertEquals("missing required key session_list", invalid.message)
            assertEquals(1, server.requestCount)
            assertEquals("/api/v4/device", server.takeRecordedRequest().path)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `capture status authentication failures do not admit a workspace`() {
        listOf(
            401 to DeviceAdmissionResult.AuthenticationRequired::class,
            403 to DeviceAdmissionResult.Forbidden::class,
        ).forEach { (statusCode, expectedType) ->
            withServer { server ->
                server.enqueue(jsonResponse(200, fixtureText("device-null-live-imu.json")))
                server.enqueue(jsonResponse(statusCode, """{"schema":"ylx.api-error.v2"}"""))

                val result = DeviceAdmissionClient().admit(
                    listOf(DeviceAdmissionCandidate(server.origin(), "session-token")),
                    isAttemptCurrent = { true },
                )

                assertEquals(expectedType, result::class)
                val failureOrigin = when (result) {
                    is DeviceAdmissionResult.AuthenticationRequired -> result.origin
                    is DeviceAdmissionResult.Forbidden -> result.origin
                    else -> error("expected an admission authorization failure")
                }
                assertEquals(server.origin(), failureOrigin)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `invalid initial capture status does not admit a workspace`() {
        withServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("device-null-live-imu.json")))
            server.enqueue(jsonResponse(200, """{"schema":"ylx.capture-status.v4"}"""))

            val result = DeviceAdmissionClient().admit(
                listOf(DeviceAdmissionCandidate(server.origin(), null)),
                isAttemptCurrent = { true },
            )

            assertIs<DeviceAdmissionResult.InvalidResponse>(result)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `initial capture status network failure remains an admission failure`() {
        withServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("device-null-live-imu.json")))
            val client = DeviceAdmissionClient(
                probeDevice = { origin, token -> DeviceProbeClient().probe(origin, token) },
                loadCaptureStatus = {
                    CaptureStatusResult.NetworkFailure("capture status connection closed")
                },
            )

            val result = client.admit(
                listOf(DeviceAdmissionCandidate(server.origin(), null)),
                isAttemptCurrent = { true },
            )

            assertIs<DeviceAdmissionResult.NetworkFailure>(result)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `cancellation between descriptor and status prevents the second request`() {
        withServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("device-null-live-imu.json")))
            var fenceChecks = 0

            val result = DeviceAdmissionClient().admit(
                listOf(DeviceAdmissionCandidate(server.origin(), null)),
                isAttemptCurrent = { fenceChecks++ == 0 },
            )

            assertIs<DeviceAdmissionResult.Cancelled>(result)
            assertEquals(1, server.requestCount)
            assertEquals("/api/v4/device", server.takeRecordedRequest().path)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `cancellation after status discards the complete late response`() {
        withServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("device-null-live-imu.json")))
            server.enqueue(jsonResponse(200, idleCaptureStatusJson()))
            var fenceChecks = 0

            val result = DeviceAdmissionClient().admit(
                listOf(DeviceAdmissionCandidate(server.origin(), "session-token")),
                isAttemptCurrent = { fenceChecks++ < 3 },
            )

            assertIs<DeviceAdmissionResult.Cancelled>(result)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `network failure falls back by repeating the full transaction on the next origin`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val unreachable = "http://127.0.0.1:$closedPort"
        withServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("device-null-live-imu.json")))
            server.enqueue(jsonResponse(200, idleCaptureStatusJson()))

            val result = DeviceAdmissionClient().admit(
                listOf(
                    DeviceAdmissionCandidate(unreachable, "first-token"),
                    DeviceAdmissionCandidate(server.origin(), "second-token"),
                ),
                isAttemptCurrent = { true },
            )

            val admission = assertIs<DeviceAdmissionResult.Verified>(result).admission
            assertEquals(server.origin(), admission.connection.origin)
            assertEquals("second-token", admission.connection.bearerToken)
            assertEquals("Bearer second-token", server.takeRecordedRequest().getHeader("Authorization"))
            assertEquals("Bearer second-token", server.takeRecordedRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun `backup authentication challenge identifies exact origin and explicit retry succeeds`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val unreachable = "http://127.0.0.1:$closedPort"
        withServer { server ->
            server.enqueue(jsonResponse(401, """{"schema":"ylx.api-error.v2"}"""))

            val challenged = DeviceAdmissionClient().admit(
                candidates = listOf(
                    DeviceAdmissionCandidate(unreachable, null),
                    DeviceAdmissionCandidate("${server.origin()}/", null),
                ),
                isAttemptCurrent = { true },
            )

            assertEquals(
                server.origin(),
                assertIs<DeviceAdmissionResult.AuthenticationRequired>(challenged).origin,
            )
            assertNull(server.takeRecordedRequest().getHeader("Authorization"))

            server.enqueue(jsonResponse(200, fixtureText("device-full-capabilities.json")))
            server.enqueue(jsonResponse(200, idleCaptureStatusJson()))
            val retried = DeviceAdmissionClient().admit(
                candidates = listOf(
                    DeviceAdmissionCandidate(unreachable, null),
                    DeviceAdmissionCandidate(server.origin(), "backup-token"),
                ),
                isAttemptCurrent = { true },
            )

            val admission = assertIs<DeviceAdmissionResult.Verified>(retried).admission
            assertEquals(server.origin(), admission.connection.origin)
            assertEquals("backup-token", admission.connection.bearerToken)
            assertEquals("Bearer backup-token", server.takeRecordedRequest().getHeader("Authorization"))
            assertEquals("Bearer backup-token", server.takeRecordedRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun `cancelling a blocked request disconnects it and a fresh attempt can proceed`() {
        withServer { server ->
            val releaseBlockedResponse = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") == "Bearer old-token") {
                        releaseBlockedResponse.await(30, TimeUnit.SECONDS)
                    }
                    return when (request.path) {
                        "/api/v4/device" -> {
                            jsonResponse(200, fixtureText("device-full-capabilities.json"))
                        }
                        "/api/v4/capture/status" -> jsonResponse(200, idleCaptureStatusJson())
                        else -> jsonResponse(404, "{}")
                    }
                }
            }
            val cancellation = DeviceAdmissionCancellation()
            val oldAttemptCurrent = AtomicBoolean(true)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val blocked = executor.submit<DeviceAdmissionResult> {
                    DeviceAdmissionClient().admit(
                        candidates = listOf(DeviceAdmissionCandidate(server.origin(), "old-token")),
                        isAttemptCurrent = oldAttemptCurrent::get,
                        cancellation = cancellation,
                    )
                }
                assertEquals("/api/v4/device", server.takeRecordedRequest().path)

                oldAttemptCurrent.set(false)
                cancellation.cancel()

                val fresh = DeviceAdmissionClient().admit(
                    candidates = listOf(DeviceAdmissionCandidate(server.origin(), "fresh-token")),
                    isAttemptCurrent = { true },
                )

                assertIs<DeviceAdmissionResult.Verified>(fresh)
                assertIs<DeviceAdmissionResult.Cancelled>(blocked.get(2, TimeUnit.SECONDS))
                assertEquals("Bearer fresh-token", server.takeRecordedRequest().getHeader("Authorization"))
                assertEquals("Bearer fresh-token", server.takeRecordedRequest().getHeader("Authorization"))
            } finally {
                releaseBlockedResponse.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `cancelling blocked initial status disconnects the second admission request`() {
        withServer { server ->
            val releaseBlockedStatus = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/api/v4/device" -> {
                            jsonResponse(200, fixtureText("device-full-capabilities.json"))
                        }
                        "/api/v4/capture/status" -> {
                            releaseBlockedStatus.await(30, TimeUnit.SECONDS)
                            jsonResponse(200, idleCaptureStatusJson())
                        }
                        else -> jsonResponse(404, "{}")
                    }
                }
            }
            val cancellation = DeviceAdmissionCancellation()
            val attemptCurrent = AtomicBoolean(true)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val blocked = executor.submit<DeviceAdmissionResult> {
                    DeviceAdmissionClient().admit(
                        candidates = listOf(DeviceAdmissionCandidate(server.origin(), null)),
                        isAttemptCurrent = attemptCurrent::get,
                        cancellation = cancellation,
                    )
                }
                assertEquals("/api/v4/device", server.takeRecordedRequest().path)
                assertEquals("/api/v4/capture/status", server.takeRecordedRequest().path)

                attemptCurrent.set(false)
                cancellation.cancel()

                assertIs<DeviceAdmissionResult.Cancelled>(blocked.get(2, TimeUnit.SECONDS))
            } finally {
                releaseBlockedStatus.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        try {
            server.start()
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private fun MockWebServer.origin(): String = url("/").toString().removeSuffix("/")

    private fun MockWebServer.takeRecordedRequest(): RecordedRequest {
        return takeRequest(2, TimeUnit.SECONDS) ?: error("expected a Device API request")
    }

    private fun jsonResponse(statusCode: Int, body: String): MockResponse {
        return MockResponse()
            .setResponseCode(statusCode)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private fun fixtureText(name: String, category: String = "valid"): String {
        return requireNotNull(javaClass.classLoader?.getResource("device-api/v4/$category/$name")) {
            "missing fixture $name"
        }.readText()
    }

    private fun idleCaptureStatusJson(): String {
        return """
            {
              "schema": "ylx.capture-status.v4",
              "authority_epoch": "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
              "source_revision": 7,
              "snapshot": {
                "schema": "ylx.capture-snapshot-event.v4",
                "device_state": "idle",
                "active_recording": null,
                "retained_unsuccessful": null,
                "runtime": {
                  "observed_at": "2026-08-28T04:00:00Z",
                  "connection_method": "wifi_ap",
                  "temperature_celsius": 48.2,
                  "network": {
                    "ap": {"state":"active","interface":"uap0","addresses":["10.42.0.1/24"],"peer_or_ssid":"YLX-A13F"},
                    "wifi_client": {"state":"active","interface":"wlan0","addresses":["192.168.110.36/24"],"peer_or_ssid":"Studio"},
                    "wired": {"state":"disconnected","interface":null,"addresses":[],"peer_or_ssid":null},
                    "default_route": "wifi_client"
                  },
                  "live_imu": null,
                  "camera": {"schema":"ylx.camera-connection.v1","state":"connected"},
                  "camera_focus": null
                }
              }
            }
        """.trimIndent()
    }

}
