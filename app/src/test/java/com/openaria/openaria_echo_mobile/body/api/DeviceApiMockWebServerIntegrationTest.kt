package com.openaria.openaria_echo_mobile.body.api

import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class DeviceApiMockWebServerIntegrationTest {
    @Test
    fun `MockWebServer verifies probe request contract and bearer token placement`() {
        withMockWebServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(deviceDescriptorJson()),
            )

            val result = DeviceProbeClient().probe(server.origin(), "  session-token  ")

            val request = server.takeRecordedRequest()
            val verified = assertIs<ProbeResult.Verified>(result)
            assertEquals("GET", request.method)
            assertEquals("/api/v4/device", request.path)
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals("Bearer session-token", request.getHeader("Authorization"))
            assertNull(request.requestUrl?.query)
            assertEquals(server.origin(), verified.connection.origin)
            assertEquals("session-token", verified.connection.bearerToken)
            assertEquals(true, verified.connection.descriptor.networkMutationCapable)
        }
    }

    @Test
    fun `MockWebServer verifies capture SSE replay headers and event reconciliation`() {
        withMockWebServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                    .setBody(
                        sse(
                            ": heartbeat",
                            "id: 1042\n" +
                                "event: snapshot\n" +
                                "data: ${captureEventJson("1042", "snapshot", 8, null, captureSnapshotDataJson())}",
                        ),
                    ),
            )

            val result = DeviceHttpClient().readCaptureEvents(
                connection = connection(server, bearerToken = "session-token"),
                lastEventId = "1041",
                lastAuthorityEpoch = AUTHORITY_EPOCH,
                lastSourceRevision = 7,
            )

            val request = server.takeRecordedRequest()
            val batch = assertIs<CaptureEventsResult.Batch>(result)
            val event = batch.events.single()
            assertEquals("GET", request.method)
            assertEquals("/api/v4/capture/events", request.path)
            assertEquals("text/event-stream", request.getHeader("Accept"))
            assertEquals("no-cache", request.getHeader("Cache-Control"))
            assertEquals("1041", request.getHeader("Last-Event-ID"))
            assertEquals("Bearer session-token", request.getHeader("Authorization"))
            assertEquals("1042", batch.lastEventId)
            assertEquals(CaptureRevisionRelation.Next, event.revisionRelation)
            assertEquals(false, event.requiresHttpReconciliation)
            assertEquals("idle", event.snapshot?.deviceState)
        }
    }

    @Test
    fun `MockWebServer verifies network status scan credential apply and SSE contracts`() {
        withMockWebServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("network-status.json")))
            server.enqueue(jsonResponse(200, fixtureText("network-scan.json")))
            server.enqueue(jsonResponse(201, fixtureText("network-credential-receipt.json")))
            server.enqueue(jsonResponse(202, fixtureText("network-transaction-accepted.json")))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                    .setBody(
                        sse(
                            "id: 208\n" +
                                "event: transaction\n" +
                                "data: ${compactFixtureJson("network-event-transaction.json")}",
                        ),
                    ),
            )
            val connection = connection(server, bearerToken = "session-token")
            val client = DeviceHttpClient()

            val status = client.getNetworkStatus(connection)
            val scan = client.scanNetworks(connection)
            val credential = client.createNetworkCredentialReference(connection, "correct-passphrase")
            val apply = client.applyWifiClientNetwork(
                connection = connection,
                idempotencyKey = "network-command-1",
                ssid = "摄影棚-5G",
                security = "wpa2-wpa3-personal",
                credentialRef = "cred-0198d29f-ephemeral-001",
            )
            val events = client.readNetworkEvents(
                connection = connection,
                lastEventId = "207",
                lastAuthorityEpoch = "4fa85f64-5717-4562-b3fc-2c963f66afa6",
                lastSourceRevision = 11,
            )

            assertIs<NetworkStatusResult.Status>(status)
            assertIs<NetworkScanResult.Scan>(scan)
            assertIs<NetworkCredentialResult.Receipt>(credential)
            assertIs<NetworkMutationResult.Accepted>(apply)
            assertIs<NetworkEventsResult.Batch>(events)

            assertEquals("/api/v4/network", server.takeRecordedRequest().path)
            assertEquals("/api/v4/network/scan", server.takeRecordedRequest().path)
            val credentialRequest = server.takeRecordedRequest()
            assertEquals("/api/v4/network/credentials", credentialRequest.path)
            assertEquals(
                """{"schema":"ylx.network-credential-request.v1","passphrase":"correct-passphrase"}""",
                credentialRequest.body.readUtf8(),
            )
            val applyRequest = server.takeRecordedRequest()
            assertEquals("/api/v4/network/apply", applyRequest.path)
            assertEquals("network-command-1", applyRequest.getHeader("Idempotency-Key"))
            val applyBody = applyRequest.body.readUtf8()
            assertFalse(applyBody.contains("passphrase"))
            assertFalse(applyBody.contains("password"))
            assertEquals(true, applyBody.contains("credential_ref"))
            val sseRequest = server.takeRecordedRequest()
            assertEquals("/api/v4/network/events", sseRequest.path)
            assertEquals("207", sseRequest.getHeader("Last-Event-ID"))
            assertEquals("text/event-stream", sseRequest.getHeader("Accept"))
        }
    }

    @Test
    fun `v2 session cursor remains first-page-only and sends no continuation request`() {
        withMockWebServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("session-list-v2.json")))
            val repository = SessionLedgerRepository()
            val request = requireNotNull(repository.beginRefresh())

            val result = DeviceHttpClient().listSessions(connection(server))
            val applied = repository.complete(request, result)

            assertIs<SessionLedgerApplyResult.Applied>(applied)
            assertNull(repository.beginLoadMore())
            assertEquals(1, server.requestCount)
            assertEquals("/api/v4/sessions?limit=50", server.takeRecordedRequest().path)
        }
    }

    @Test
    fun `MockWebServer decodes authoritative v3 unusable verification diagnostics`() {
        withMockWebServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("session-list-v3-unusable.json")))

            val result = DeviceHttpClient().listSessions(connection(server))

            val request = server.takeRecordedRequest()
            val page = assertIs<SessionListResult.Page>(result).value
            assertEquals("/api/v4/sessions?limit=50", request.path)
            assertEquals(SessionListContract.V3, page.contract)
            val session = page.items.single()
            assertEquals("unusable", session.verificationVerdict)
            assertEquals("unusable test take", session.displayName)
            val diagnostic = assertIs<GatewayVerificationDiagnostic.Current>(
                requireNotNull(session.verification).diagnostics.single(),
            )
            assertEquals(
                GatewayVerificationDiagnosticCode.ARTIFACT_DIGEST_MISMATCH,
                diagnostic.code,
            )
            assertEquals(
                "artifact content SHA-256 does not match the manifest",
                diagnostic.summary,
            )
        }
    }

    @Test
    fun `MockWebServer preserves quarantine diagnostics outside downloadable sessions`() {
        withMockWebServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("session-list-v3-quarantine.json")))

            val result = DeviceHttpClient().listSessions(connection(server), limit = 1)

            val request = server.takeRecordedRequest()
            val page = assertIs<SessionListResult.Page>(result).value
            assertEquals("/api/v4/sessions?limit=1", request.path)
            assertEquals(emptyList(), page.items)
            val diagnostic = page.diagnostics.single()
            assertEquals("manifest_invalid", diagnostic.code)
            assertEquals("2026-08-28T04:00:00Z", diagnostic.observedAt)
            assertEquals("56005c52-31f1-4dac-91cd-d8eafd737d1c", diagnostic.quarantineId)
        }
    }

    @Test
    fun `controller catalog recovery sends the original take filter without a cursor`() {
        withMockWebServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("session-list-v3.json")))
            server.enqueue(jsonResponse(409, fixtureText("catalog-changed.json")))
            server.enqueue(jsonResponse(200, fixtureText("session-list-v3-unusable.json")))
            val client = DeviceHttpClient()
            val connection = connection(server)
            runBlocking {
                val controller =
                    SessionLedgerController<DeviceConnection, DeviceAdmissionCancellation>(
                        scope = this,
                        cancellationFactory = ::DeviceAdmissionCancellation,
                        cancelTransport = DeviceAdmissionCancellation::cancel,
                        transport = { target, request, cancellation ->
                            client.listSessions(
                                connection = target,
                                limit = request.limit,
                                cursor = request.cursor,
                                takeId = request.takeId,
                                cancellation = cancellation,
                            )
                        },
                    )

                controller.refresh(connection, SessionFilterIntent.Exact(TAKE_ID))
                controller.awaitIdle()
                controller.loadMore(connection)
                controller.awaitIdle()

                assertEquals(TAKE_ID, controller.currentTakeId)
                assertNull(controller.state.failure)
            }
            assertEquals(
                "/api/v4/sessions?limit=50&take_id=$TAKE_ID",
                server.takeRecordedRequest().path,
            )
            assertEquals(
                "/api/v4/sessions?limit=50&cursor=opaque-page-2&take_id=$TAKE_ID",
                server.takeRecordedRequest().path,
            )
            assertEquals(
                "/api/v4/sessions?limit=50&take_id=$TAKE_ID",
                server.takeRecordedRequest().path,
            )
        }
    }

    @Test
    fun `direct filtered refresh and 409 recovery send take id without a stale cursor`() {
        withMockWebServer { server ->
            server.enqueue(jsonResponse(200, fixtureText("session-list-v3.json")))
            server.enqueue(jsonResponse(409, fixtureText("catalog-changed.json")))
            server.enqueue(jsonResponse(200, fixtureText("session-list-v3-unusable.json")))
            val connection = connection(server)
            val client = DeviceHttpClient()
            val repository = SessionLedgerRepository()

            val initial = requireNotNull(repository.beginRefresh(takeId = TAKE_ID, limit = 7))
            assertIs<SessionLedgerApplyResult.Applied>(
                repository.complete(initial, client.listSessions(connection, initial)),
            )
            assertEquals("opaque-page-2", repository.page?.nextCursor)

            val refresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID, limit = 7))
            assertNull(refresh.cursor)
            assertNull(refresh.catalogRevision)
            val refreshRequired = assertIs<SessionLedgerApplyResult.RefreshRequired>(
                repository.complete(refresh, client.listSessions(connection, refresh)),
            )
            assertEquals(TAKE_ID, refreshRequired.takeId)
            assertNull(repository.page)

            val recovery = requireNotNull(
                repository.beginRefresh(
                    takeId = refreshRequired.takeId,
                    limit = refreshRequired.limit,
                    catalogRecovery = true,
                ),
            )
            assertEquals(TAKE_ID, recovery.takeId)
            assertNull(recovery.cursor)
            assertNull(recovery.catalogRevision)
            assertIs<SessionLedgerApplyResult.Applied>(
                repository.complete(recovery, client.listSessions(connection, recovery)),
            )

            repeat(3) {
                assertEquals(
                    "/api/v4/sessions?limit=7&take_id=$TAKE_ID",
                    server.takeRecordedRequest().path,
                )
            }
        }
    }

    @Test
    fun `MockWebServer verifies artifact HEAD metadata and resumed Range download`() {
        withMockWebServer { server ->
            val payload = "hello".toByteArray(Charsets.UTF_8)
            val descriptor = artifactDescriptor(payload)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", descriptor.mediaType)
                    .setHeader("Content-Length", payload.size.toString())
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("ETag", "\"${descriptor.artifactId}\""),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", descriptor.mediaType)
                    .setHeader("Content-Length", "3")
                    .setHeader("Content-Range", "bytes 2-4/5")
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("ETag", "\"${descriptor.artifactId}\"")
                    .setBody("llo"),
            )
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Long>()

            val head = DeviceHttpClient().headSessionArtifact(
                connection = connection(server),
                sessionId = SESSION_ID,
                artifact = descriptor,
            )
            val download = DeviceHttpClient().downloadSessionArtifact(
                connection = connection(server),
                sessionId = SESSION_ID,
                artifact = descriptor,
                output = output,
                resumeFromBytes = 2,
                onBytesWritten = { bytes -> progress += bytes },
            )

            val headRequest = server.takeRecordedRequest()
            val rangeRequest = server.takeRecordedRequest()
            val downloaded = assertIs<ArtifactDownloadResult.Downloaded>(download)
            assertIs<ArtifactHeadResult.Verified>(head)
            assertEquals("HEAD", headRequest.method)
            assertEquals("/api/v4/sessions/$SESSION_ID/artifacts/${descriptor.artifactId}", headRequest.path)
            assertEquals("*/*", headRequest.getHeader("Accept"))
            assertEquals("GET", rangeRequest.method)
            assertEquals("/api/v4/sessions/$SESSION_ID/artifacts/${descriptor.artifactId}", rangeRequest.path)
            assertEquals("bytes=2-", rangeRequest.getHeader("Range"))
            assertEquals("\"${descriptor.artifactId}\"", rangeRequest.getHeader("If-Range"))
            assertEquals(3L, downloaded.bytes)
            assertEquals(listOf(5L), progress)
            assertContentEquals("llo".toByteArray(Charsets.UTF_8), output.toByteArray())
        }
    }

    private fun withMockWebServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private fun MockWebServer.origin(): String {
        return url("/").toString().removeSuffix("/")
    }

    private fun MockWebServer.takeRecordedRequest(): RecordedRequest {
        return takeRequest(2, TimeUnit.SECONDS) ?: error("expected a Device API request")
    }

    private fun connection(server: MockWebServer, bearerToken: String? = null): DeviceConnection {
        val target = assertIs<EndpointPolicy.Decision.Allowed>(EndpointPolicy.validate(server.origin())).target
        return DeviceConnection(
            target = target,
            descriptor = descriptor(),
            bearerToken = bearerToken,
        )
    }

    private fun DeviceHttpClient.listSessions(
        connection: DeviceConnection,
        request: SessionLedgerRequest,
    ): SessionListResult {
        return listSessions(
            connection = connection,
            limit = request.limit,
            cursor = request.cursor,
            takeId = request.takeId,
        )
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
            sessionListCapable = true,
            sessionDetailCapable = true,
            artifactDownloadCapable = true,
            captureStatusCapable = true,
            sessionDeletionCapable = false,
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

    private fun artifactDescriptor(payload: ByteArray): ArtifactDescriptor {
        val sha256 = when (payload.decodeToString()) {
            "hello" -> "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            else -> error("unexpected payload")
        }
        return ArtifactDescriptor(
            artifactId = sha256,
            role = "frames.index",
            path = "frames.ndjson",
            mediaType = "application/x-ndjson",
            bytes = payload.size.toLong(),
            sha256 = sha256,
        )
    }

    private fun sse(vararg events: String): String {
        return events.joinToString(separator = "\n\n", postfix = "\n\n")
    }

    private fun jsonResponse(statusCode: Int, body: String): MockResponse {
        return MockResponse()
            .setResponseCode(statusCode)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private fun fixtureText(name: String): String {
        return requireNotNull(
            javaClass.classLoader?.getResource("device-api/v4/valid/$name"),
        ) { "missing fixture $name" }.readText()
    }

    private fun compactFixtureJson(name: String): String {
        return fixtureText(name).lineSequence().joinToString("") { it.trim() }
    }

    private fun captureEventJson(
        deliveryId: String,
        type: String,
        revision: Int,
        sessionId: String?,
        dataJson: String,
    ): String {
        val encodedSessionId = sessionId?.let { "\"$it\"" } ?: "null"
        return """{"schema":"ylx.capture-event.v4","sse_delivery_id":"$deliveryId","authority_epoch":"$AUTHORITY_EPOCH","source_revision":$revision,"type":"$type","occurred_at":"2026-08-28T04:00:01Z","session_id":$encodedSessionId,"data":$dataJson}"""
    }

    private fun captureSnapshotDataJson(): String {
        return """{"schema":"ylx.capture-snapshot-event.v4","device_state":"idle","active_recording":null,"retained_unsuccessful":null,"runtime":{"observed_at":"2026-08-28T04:00:00Z","connection_method":"wifi_ap","temperature_celsius":48.2,"network":${networkRuntimeJson()},"live_imu":null,"camera":{"schema":"ylx.camera-connection.v1","state":"connected"},"camera_focus":null}}"""
    }

    private fun deviceDescriptorJson(): String {
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
                "commit": "77f24f3777777777777777777777777777777777",
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
                "network": ${networkRuntimeJson()},
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

    private fun networkRuntimeJson(): String {
        return """{"ap":{"state":"active","interface":"uap0","addresses":["10.42.0.1/24"],"peer_or_ssid":"YLX-A13F"},"wifi_client":{"state":"active","interface":"wlan0","addresses":["192.168.110.36/24"],"peer_or_ssid":"Studio"},"wired":{"state":"disconnected","interface":null,"addresses":[],"peer_or_ssid":null},"default_route":"wifi_client"}"""
    }

    private companion object {
        const val AUTHORITY_EPOCH = "e989c6e5-14cc-4faa-9715-5abdb6b0355d"
        const val SESSION_ID = "01991b70-7c88-7123-9234-123456789abc"
        const val TAKE_ID = "01991b70-7c88-7456-9234-123456789abc"
    }
}
