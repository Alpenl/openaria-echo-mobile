package com.openaria.openaria_echo_mobile.body.api

import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceHttpClientTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `reads authoritative capture status snapshot`() {
        var requestedPath: String? = null
        var accept: String? = null
        var authorization: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            accept = exchange.requestHeaders.getFirst("Accept")
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.respondJson(200, idleCaptureSnapshotJson())
        }

        val result = DeviceHttpClient().getCaptureStatus(connection(origin, "session-token"))

        val snapshot = assertIs<CaptureStatusResult.Snapshot>(result).value
        assertEquals("/api/v4/capture/status", requestedPath)
        assertEquals("application/json", accept)
        assertEquals("Bearer session-token", authorization)
        assertEquals("idle", snapshot.deviceState)
        assertEquals(7L, snapshot.sourceRevision)
    }

    @Test
    fun `reads capture SSE snapshot with last event id and next revision`() {
        var requestedPath: String? = null
        var accept: String? = null
        var cacheControl: String? = null
        var lastEventId: String? = null
        var authorization: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            accept = exchange.requestHeaders.getFirst("Accept")
            cacheControl = exchange.requestHeaders.getFirst("Cache-Control")
            lastEventId = exchange.requestHeaders.getFirst("Last-Event-ID")
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.respondBytes(
                200,
                "text/event-stream; charset=utf-8",
                sse(
                    ": heartbeat",
                    "id: 1042\n" +
                        "event: snapshot\n" +
                        "data: ${captureEventJson("1042", "snapshot", 8, null, idleCaptureSnapshotDataJson())}",
                ),
            )
        }

        val result = DeviceHttpClient().readCaptureEvents(
            connection = connection(origin, "session-token"),
            lastEventId = "1041",
            lastAuthorityEpoch = "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
            lastSourceRevision = 7,
        )

        val batch = assertIs<CaptureEventsResult.Batch>(result)
        val event = batch.events.single()
        assertEquals("/api/v4/capture/events", requestedPath)
        assertEquals("text/event-stream", accept)
        assertEquals("no-cache", cacheControl)
        assertEquals("1041", lastEventId)
        assertEquals("Bearer session-token", authorization)
        assertEquals("1042", batch.lastEventId)
        assertEquals(CaptureRevisionRelation.Next, event.revisionRelation)
        assertEquals(false, event.requiresHttpReconciliation)
        assertEquals("idle", event.snapshot?.deviceState)
    }

    @Test
    fun `classifies capture SSE clean EOF without events as unavailable`() {
        val origin = startServer { exchange ->
            exchange.respondBytes(200, "text/event-stream", ByteArray(0))
        }

        val result = DeviceHttpClient().readCaptureEvents(connection(origin))

        assertIs<CaptureEventsResult.NoEvents>(result)
    }

    @Test
    fun `marks capture SSE source revision gap for HTTP reconciliation`() {
        val origin = startServer { exchange ->
            exchange.respondBytes(
                200,
                "text/event-stream",
                sse(
                    "id: 1042\n" +
                        "event: state\n" +
                        "data: ${captureEventJson("1042", "state", 42, "01991b70-7c88-7123-9234-123456789abc", captureStateEventDataJson())}",
                ),
            )
        }

        val result = DeviceHttpClient().readCaptureEvents(
            connection = connection(origin),
            lastAuthorityEpoch = "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
            lastSourceRevision = 40,
        )

        val event = assertIs<CaptureEventsResult.Batch>(result).events.single()
        assertEquals(CaptureRevisionRelation.Gap, event.revisionRelation)
        assertEquals(true, event.requiresHttpReconciliation)
        assertEquals("state", event.type)
    }

    @Test
    fun `rejects capture SSE event when id does not match payload delivery id`() {
        val origin = startServer { exchange ->
            exchange.respondBytes(
                200,
                "text/event-stream",
                sse(
                    "id: 1042\n" +
                        "event: state\n" +
                        "data: ${captureEventJson("1043", "state", 42, "01991b70-7c88-7123-9234-123456789abc", captureStateEventDataJson())}",
                ),
            )
        }

        val result = DeviceHttpClient().readCaptureEvents(connection(origin))

        val invalid = assertIs<CaptureEventsResult.InvalidResponse>(result)
        assertEquals("SSE id must match CaptureEvent.sse_delivery_id", invalid.message)
    }

    @Test
    fun `reads authoritative network status with bearer token`() {
        var requestedPath: String? = null
        var accept: String? = null
        var authorization: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            accept = exchange.requestHeaders.getFirst("Accept")
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.respondJson(200, fixtureText("network-status.json"))
        }

        val result = DeviceHttpClient().getNetworkStatus(connection(origin, "session-token"))

        val status = assertIs<NetworkStatusResult.Status>(result).value
        assertEquals("/api/v4/network", requestedPath)
        assertEquals("application/json", accept)
        assertEquals("Bearer session-token", authorization)
        assertEquals("_ylx-capture._tcp", status.observed.mdns.service)
        assertEquals(true, status.mutationCapability.enabled)
    }

    @Test
    fun `scans nearby networks without mutation side effects`() {
        var requestedPath: String? = null
        var accept: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            accept = exchange.requestHeaders.getFirst("Accept")
            exchange.respondJson(200, fixtureText("network-scan.json"))
        }

        val result = DeviceHttpClient().scanNetworks(connection(origin))

        val scan = assertIs<NetworkScanResult.Scan>(result).value
        assertEquals("/api/v4/network/scan", requestedPath)
        assertEquals("application/json", accept)
        assertEquals("摄影棚-5G", scan.networks[1].ssid)
        assertEquals(true, scan.networks[1].credentialRequired)
    }

    @Test
    fun `exchanges passphrase for opaque network credential reference`() {
        var requestedPath: String? = null
        var requestBody: String? = null
        var contentType: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(201, fixtureText("network-credential-receipt.json"))
        }

        val result = DeviceHttpClient().createNetworkCredentialReference(
            connection = connection(origin, "session-token"),
            passphrase = "correct-passphrase",
        )

        val receipt = assertIs<NetworkCredentialResult.Receipt>(result).value
        assertEquals("/api/v4/network/credentials", requestedPath)
        assertEquals("application/json", contentType)
        assertEquals(
            """{"schema":"ylx.network-credential-request.v1","passphrase":"correct-passphrase"}""",
            requestBody,
        )
        assertEquals("cred-0198d29f-ephemeral-001", receipt.credentialRef)
    }

    @Test
    fun `applies protected Wi-Fi using credential reference only`() {
        var requestedPath: String? = null
        var idempotencyKey: String? = null
        var requestBody: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key")
            requestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(202, fixtureText("network-transaction-accepted.json"))
        }

        val result = DeviceHttpClient().applyWifiClientNetwork(
            connection = connection(origin, "session-token"),
            idempotencyKey = "network-command-1",
            ssid = "摄影棚-5G",
            security = "wpa2-wpa3-personal",
            credentialRef = "cred-0198d29f-ephemeral-001",
        )

        assertIs<NetworkMutationResult.Accepted>(result)
        assertEquals("/api/v4/network/apply", requestedPath)
        assertEquals("network-command-1", idempotencyKey)
        assertEquals(
            """{"schema":"ylx.network-apply-request.v1","desired":{"mode":"wifi-client","wifi_client":{"ssid":"摄影棚-5G","security":"wpa2-wpa3-personal","credential_ref":"cred-0198d29f-ephemeral-001"},"ethernet":null}}""",
            requestBody,
        )
        assertFalse(requestBody.orEmpty().contains("passphrase"))
        assertFalse(requestBody.orEmpty().contains("password"))
    }

    @Test
    fun `applies hotspot desired state`() {
        var requestedPath: String? = null
        var idempotencyKey: String? = null
        var requestBody: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key")
            requestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(202, fixtureText("network-transaction-accepted.json"))
        }

        val result = DeviceHttpClient().applyHotspotNetwork(
            connection = connection(origin, "session-token"),
            idempotencyKey = "network-hotspot-1",
        )

        assertIs<NetworkMutationResult.Accepted>(result)
        assertEquals("/api/v4/network/apply", requestedPath)
        assertEquals("network-hotspot-1", idempotencyKey)
        assertEquals(
            """{"schema":"ylx.network-apply-request.v1","desired":{"mode":"hotspot","wifi_client":null,"ethernet":null}}""",
            requestBody,
        )
    }

    @Test
    fun `applies ethernet DHCP desired state`() {
        var requestedPath: String? = null
        var requestBody: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            requestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(202, fixtureText("network-transaction-accepted.json"))
        }

        val result = DeviceHttpClient().applyEthernetDhcpNetwork(
            connection = connection(origin, "session-token"),
            idempotencyKey = "network-ethernet-dhcp-1",
        )

        assertIs<NetworkMutationResult.Accepted>(result)
        assertEquals("/api/v4/network/apply", requestedPath)
        assertEquals(
            """{"schema":"ylx.network-apply-request.v1","desired":{"mode":"ethernet-dhcp","wifi_client":null,"ethernet":{"addressing":"dhcp","static_ipv4":null}}}""",
            requestBody,
        )
    }

    @Test
    fun `applies ethernet static desired state`() {
        var requestedPath: String? = null
        var requestBody: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            requestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(202, fixtureText("network-transaction-accepted.json"))
        }

        val result = DeviceHttpClient().applyEthernetStaticNetwork(
            connection = connection(origin, "session-token"),
            idempotencyKey = "network-ethernet-static-1",
            address = "192.168.50.42",
            prefixLength = 24,
            gateway = "192.168.50.1",
            dns = listOf("192.168.50.1", "1.1.1.1"),
        )

        assertIs<NetworkMutationResult.Accepted>(result)
        assertEquals("/api/v4/network/apply", requestedPath)
        assertEquals(
            """{"schema":"ylx.network-apply-request.v1","desired":{"mode":"ethernet-static","wifi_client":null,"ethernet":{"addressing":"static","static_ipv4":{"address":"192.168.50.42","prefix_length":24,"gateway":"192.168.50.1","dns":["192.168.50.1","1.1.1.1"]}}}}""",
            requestBody,
        )
    }

    @Test
    fun `streams network events with replay identity and source revision reconciliation`() {
        var requestedPath: String? = null
        var accept: String? = null
        var lastEventId: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            accept = exchange.requestHeaders.getFirst("Accept")
            lastEventId = exchange.requestHeaders.getFirst("Last-Event-ID")
            exchange.respondBytes(
                200,
                "text/event-stream",
                sse(
                    "id: 208\n" +
                        "event: transaction\n" +
                        "data: ${compactFixtureJson("network-event-transaction.json")}",
                ),
            )
        }

        val result = DeviceHttpClient().readNetworkEvents(
            connection = connection(origin, "session-token"),
            lastEventId = "207",
            lastAuthorityEpoch = "4fa85f64-5717-4562-b3fc-2c963f66afa6",
            lastSourceRevision = 11,
        )

        val batch = assertIs<NetworkEventsResult.Batch>(result)
        val event = batch.events.single()
        assertEquals("/api/v4/network/events", requestedPath)
        assertEquals("text/event-stream", accept)
        assertEquals("207", lastEventId)
        assertEquals("208", batch.lastEventId)
        assertEquals("committed", event.transaction?.status)
        assertEquals(false, event.requiresHttpReconciliation)
    }

    @Test
    fun `classifies network SSE heartbeat EOF without events as unavailable`() {
        val origin = startServer { exchange ->
            exchange.respondBytes(
                200,
                "text/event-stream",
                sse(": heartbeat"),
            )
        }

        val result = DeviceHttpClient().readNetworkEvents(connection(origin))

        assertIs<NetworkEventsResult.NoEvents>(result)
    }

    @Test
    fun `retries and forgets network transactions with idempotency keys`() {
        val requests = mutableListOf<Pair<String, String>>()
        val origin = startServer { exchange ->
            requests += exchange.requestURI.path to exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(202, fixtureText("network-transaction-accepted.json"))
        }
        val client = DeviceHttpClient()
        val connection = connection(origin, "session-token")

        val retry = client.retryNetworkTransaction(
            connection = connection,
            idempotencyKey = "network-retry-1",
            transactionId = "0198d2a0-41a0-7b7a-a751-0e86a39d4db1",
        )
        val forget = client.forgetNetworkClientProfile(
            connection = connection,
            idempotencyKey = "network-forget-1",
        )

        assertIs<NetworkMutationResult.Accepted>(retry)
        assertIs<NetworkMutationResult.Accepted>(forget)
        assertEquals("/api/v4/network/retry", requests[0].first)
        assertEquals(
            """{"schema":"ylx.network-retry-request.v1","transaction_id":"0198d2a0-41a0-7b7a-a751-0e86a39d4db1"}""",
            requests[0].second,
        )
        assertEquals("/api/v4/network/forget", requests[1].first)
        assertEquals("""{"schema":"ylx.network-forget-request.v1"}""", requests[1].second)
    }

    @Test
    fun `reads newest JPEG preview frame without requesting multipart stream`() {
        var requestedPath: String? = null
        var query: String? = null
        var accept: String? = null
        var cacheControl: String? = null
        val frame = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            query = exchange.requestURI.rawQuery
            accept = exchange.requestHeaders.getFirst("Accept")
            cacheControl = exchange.requestHeaders.getFirst("Cache-Control")
            exchange.respondBytes(200, "image/jpeg", frame)
        }

        val result = DeviceHttpClient().getPreviewJpeg(connection(origin), fps = 3)

        val preview = assertIs<PreviewResult.Frame>(result)
        assertEquals("/api/v4/preview", requestedPath)
        assertEquals("fps=3", query)
        assertEquals("image/jpeg", accept)
        assertEquals("no-store", cacheControl)
        assertContentEquals(frame, preview.bytes)
    }

    @Test
    fun `maps no preview frame response to no frame state`() {
        val origin = startServer { exchange ->
            exchange.respondJson(
                503,
                apiErrorJson(code = "preview_unavailable", message = "no preview frame yet", retryable = true),
            )
        }

        val result = DeviceHttpClient().getPreviewJpeg(connection(origin))

        assertIs<PreviewResult.NoFrame>(result)
    }

    @Test
    fun `maps camera not connected preview response separately`() {
        val origin = startServer { exchange ->
            exchange.responseHeaders.set("YLX-Error-Code", "camera_not_connected")
            exchange.respondJson(
                503,
                apiErrorJson(code = "camera_not_connected", message = "camera cable is not present", retryable = true),
            )
        }

        val result = DeviceHttpClient().getPreviewJpeg(connection(origin))

        assertIs<PreviewResult.CameraNotConnected>(result)
    }

    @Test
    fun `rejects malformed preview 503 problem without supported error code`() {
        val origin = startServer { exchange ->
            exchange.respondJson(503, """{"schema":"ylx.api-error.v2"}""")
        }

        val result = DeviceHttpClient().getPreviewJpeg(connection(origin))

        assertIs<PreviewResult.InvalidResponse>(result)
    }

    @Test
    fun `rejects non JPEG preview response even with HTTP 200`() {
        val origin = startServer { exchange ->
            exchange.respondBytes(200, "application/json", "{}".toByteArray(Charsets.UTF_8))
        }

        val result = DeviceHttpClient().getPreviewJpeg(connection(origin))

        assertIs<PreviewResult.InvalidResponse>(result)
    }

    @Test
    fun `starts production capture with idempotency key and new take body`() {
        var method: String? = null
        var requestedPath: String? = null
        var idempotencyKey: String? = null
        var authorization: String? = null
        var requestBody: String? = null
        val origin = startServer { exchange ->
            method = exchange.requestMethod
            requestedPath = exchange.requestURI.path
            idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key")
            authorization = exchange.requestHeaders.getFirst("Authorization")
            requestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(202, recordingCaptureSnapshotJson())
        }

        val result = DeviceHttpClient().startCapture(connection(origin, "session-token"), "start-1")

        val accepted = assertIs<CaptureCommandResult.Accepted>(result)
        assertEquals("POST", method)
        assertEquals("/api/v4/capture/start", requestedPath)
        assertEquals("start-1", idempotencyKey)
        assertEquals("Bearer session-token", authorization)
        assertEquals("""{"schema":"ylx.capture-start.v2","mode":"production","take":{"kind":"new"}}""", requestBody)
        assertEquals("recording", accepted.value.deviceState)
    }

    @Test
    fun `starts calibration capture with explicit calibration mode`() {
        var requestedPath: String? = null
        var idempotencyKey: String? = null
        var requestBody: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key")
            requestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(202, idleCaptureSnapshotJson())
        }

        val result = DeviceHttpClient().startCalibrationCapture(connection(origin), "calibration-command-1")

        assertIs<CaptureCommandResult.Accepted>(result)
        assertEquals("/api/v4/capture/start", requestedPath)
        assertEquals("calibration-command-1", idempotencyKey)
        assertEquals("""{"schema":"ylx.capture-start.v2","mode":"calibration","take":{"kind":"new"}}""", requestBody)
    }

    @Test
    fun `stops capture with user reason and maps no active session to no op`() {
        var requestedPath: String? = null
        var requestBody: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            requestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.respondBytes(204, "application/json", ByteArray(0))
        }

        val result = DeviceHttpClient().stopCapture(connection(origin), "stop-1")

        assertIs<CaptureCommandResult.NoActiveSession>(result)
        assertEquals("/api/v4/capture/stop", requestedPath)
        assertEquals("""{"schema":"ylx.capture-stop.v2","reason":"user"}""", requestBody)
    }

    @Test
    fun `requests safe swap by stopping capture with safe swap reason`() {
        var requestedPath: String? = null
        var idempotencyKey: String? = null
        var requestBody: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key")
            requestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(202, idleCaptureSnapshotJson())
        }

        val result = DeviceHttpClient().stopCaptureForSafeSwap(connection(origin), "safe-swap-1")

        assertIs<CaptureCommandResult.Accepted>(result)
        assertEquals("/api/v4/capture/stop", requestedPath)
        assertEquals("safe-swap-1", idempotencyKey)
        assertEquals("""{"schema":"ylx.capture-stop.v2","reason":"safe_swap"}""", requestBody)
    }

    @Test
    fun `rejects invalid idempotency key before sending capture command`() {
        val origin = startServer { exchange ->
            exchange.respondJson(500, """{"schema":"ylx.api-error.v2"}""")
        }

        val result = DeviceHttpClient().startCapture(connection(origin), "has spaces")

        assertIs<CaptureCommandResult.InvalidRequest>(result)
    }

    @Test
    fun `lists sealed sessions without fabricating diagnostic rows`() {
        var requestedPath: String? = null
        var query: String? = null
        var accept: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            query = exchange.requestURI.rawQuery
            accept = exchange.requestHeaders.getFirst("Accept")
            exchange.respondJson(200, sessionListJson())
        }

        val result = DeviceHttpClient().listSessions(connection(origin), limit = 12)

        val page = assertIs<SessionListResult.Page>(result).value
        assertEquals("/api/v4/sessions", requestedPath)
        assertEquals("limit=12", query)
        assertEquals("application/json", accept)
        assertEquals(SessionListContract.V3, page.contract)
        assertEquals(
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            page.catalogRevision,
        )
        assertEquals(1, page.items.size)
        assertEquals(1, page.diagnosticsCount)
        assertEquals("manifest_invalid", page.diagnostics.single().code)
        assertEquals("closed schema violation", page.diagnostics.single().message)
        assertEquals("01991b70-7c88-7456-9234-123456789abc", page.items.single().takeId)
        assertEquals(SessionListRequestIdentity(12, null, null), page.requestIdentity)
        assertEquals("test take", page.items.single().displayName)
        assertEquals("usable", page.items.single().verificationVerdict)
    }

    @Test
    fun `session catalog can outlive the ordinary JSON request timeout`() {
        val origin = startServer { exchange ->
            Thread.sleep(150)
            exchange.respondJson(200, sessionListJson())
        }
        val client = DeviceHttpClient(
            jsonReadTimeoutMillis = 50,
            sessionCatalogReadTimeoutMillis = 500,
        )

        val result = client.listSessions(connection(origin), limit = 12)

        assertIs<SessionListResult.Page>(result)
    }

    @Test
    fun `lists sessions with opaque cursor and take filter`() {
        var query: String? = null
        val origin = startServer { exchange ->
            query = exchange.requestURI.rawQuery
            exchange.respondJson(200, sessionListJson())
        }

        val result = DeviceHttpClient().listSessions(
            connection = connection(origin),
            limit = 12,
            cursor = "page 1/2",
            takeId = "01991b70-7c88-7456-9234-123456789abc",
        )

        val page = assertIs<SessionListResult.Page>(result).value
        assertEquals(
            SessionListRequestIdentity(
                limit = 12,
                cursor = "page 1/2",
                takeId = "01991b70-7c88-7456-9234-123456789abc",
            ),
            page.requestIdentity,
        )
        assertEquals(
            "limit=12&cursor=page+1%2F2&take_id=01991b70-7c88-7456-9234-123456789abc",
            query,
        )
    }

    @Test
    fun `fails closed when a session page exceeds its exact requested limit`() {
        val origin = startServer { exchange ->
            exchange.respondJson(200, sessionListJson())
        }

        val result = DeviceHttpClient().listSessions(connection(origin), limit = 1)

        val invalid = assertIs<SessionListResult.InvalidResponse>(result)
        assertEquals("items and diagnostics exceed the request limit", invalid.message)
    }

    @Test
    fun `fails closed when a session violates the exact take filter`() {
        val origin = startServer { exchange ->
            exchange.respondJson(200, sessionListJson())
        }

        val result = DeviceHttpClient().listSessions(
            connection = connection(origin),
            takeId = "01991b70-7c88-7567-9234-123456789abc",
        )

        val invalid = assertIs<SessionListResult.InvalidResponse>(result)
        assertEquals("items[0].take_id does not match the request filter", invalid.message)
    }

    @Test
    fun `cancels a blocked session list transport`() {
        val requestArrived = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        val origin = startServer { exchange ->
            requestArrived.countDown()
            releaseHandler.await(5, TimeUnit.SECONDS)
            runCatching { exchange.respondJson(200, sessionListJson()) }
        }
        val executor = Executors.newSingleThreadExecutor()
        val cancellation = DeviceAdmissionCancellation()

        try {
            val future = executor.submit<SessionListResult> {
                DeviceHttpClient().listSessions(
                    connection = connection(origin),
                    cancellation = cancellation,
                )
            }
            assertTrue(requestArrived.await(2, TimeUnit.SECONDS), "session request did not reach the server")

            cancellation.cancel()

            assertIs<SessionListResult.NetworkFailure>(future.get(2, TimeUnit.SECONDS))
        } finally {
            releaseHandler.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejects blank sessions cursor before sending request`() {
        val origin = startServer { exchange ->
            exchange.respondJson(500, """{"schema":"ylx.api-error.v2"}""")
        }

        val result = DeviceHttpClient().listSessions(connection(origin), cursor = "")

        assertIs<SessionListResult.InvalidRequest>(result)
    }

    @Test
    fun `accepts v2 session list only as a first page`() {
        val origin = startServer { exchange ->
            exchange.respondJson(200, fixtureText("session-list-v2.json"))
        }

        val page = assertIs<SessionListResult.Page>(
            DeviceHttpClient().listSessions(connection(origin)),
        ).value

        assertEquals(SessionListContract.V2, page.contract)
        assertEquals(null, page.catalogRevision)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun `decodes strict catalog changed response for session cursor reset`() {
        val origin = startServer { exchange ->
            exchange.respondJson(409, fixtureText("catalog-changed.json"))
        }

        val changed = assertIs<SessionListResult.CatalogChanged>(
            DeviceHttpClient().listSessions(connection(origin), cursor = "opaque-page-2"),
        )

        assertEquals(
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            changed.catalogRevision,
        )
    }

    @Test
    fun `rejects malformed catalog changed response`() {
        val origin = startServer { exchange ->
            exchange.respondJson(409, "{not-json")
        }

        val result = DeviceHttpClient().listSessions(connection(origin), cursor = "opaque-page-2")

        assertIs<SessionListResult.InvalidResponse>(result)
    }

    @Test
    fun `reads current safe swap receipt without caching`() {
        var requestedPath: String? = null
        var accept: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            accept = exchange.requestHeaders.getFirst("Accept")
            exchange.respondJson(200, safeSwapReceiptJson())
        }

        val result = DeviceHttpClient().getSafeSwapReceipt(connection(origin))

        val receipt = assertIs<SafeSwapResult.Receipt>(result).value
        assertEquals("/api/v4/capture/safe-swap", requestedPath)
        assertEquals("application/json", accept)
        assertEquals("device-released", receipt.releaseState)
        assertEquals("01991b70-7c88-7123-9234-123456789abc", receipt.sessionId)
    }

    @Test
    fun `maps missing safe swap receipt to not found`() {
        val origin = startServer { exchange ->
            exchange.respondJson(404, """{"schema":"ylx.api-error.v2"}""")
        }

        val result = DeviceHttpClient().getSafeSwapReceipt(connection(origin))

        assertIs<SafeSwapResult.NotFound>(result)
    }

    @Test
    fun `reads session manifest and extracts downloadable artifacts`() {
        var requestedPath: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            exchange.respondJson(200, deviceSessionManifestJson())
        }

        val result = DeviceHttpClient().getSessionManifest(
            connection(origin),
            "01991b70-7c88-7123-9234-123456789abc",
        )

        val manifest = assertIs<SessionManifestResult.Manifest>(result).value
        assertEquals("/api/v4/sessions/01991b70-7c88-7123-9234-123456789abc", requestedPath)
        assertEquals("test take", manifest.displayName)
        assertEquals(
            listOf("imu.samples", "frames.index", "video.left", "video.right", "audio.wav"),
            manifest.artifacts.map { it.role },
        )
    }

    @Test
    fun `rejects legacy raw side-by-side manifest returned by current v4 session endpoint`() {
        val origin = startServer { exchange ->
            exchange.respondJson(200, rawSideBySideSessionManifestJson())
        }

        val result = DeviceHttpClient().getSessionManifest(
            connection(origin),
            "01991b70-7c88-7123-9234-123456789abc",
        )

        assertEquals(
            "video.layout must be split-eyes",
            assertIs<SessionManifestResult.InvalidResponse>(result).message,
        )
    }

    @Test
    fun `reads retained unsuccessful session outcome without inferring recovery`() {
        var requestedPath: String? = null
        var accept: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            accept = exchange.requestHeaders.getFirst("Accept")
            exchange.respondJson(200, retainedUnsuccessfulOutcomeJson())
        }

        val result = DeviceHttpClient().getRetainedUnsuccessfulOutcome(
            connection(origin),
            "01991b70-7c88-7123-9234-123456789abc",
        )

        val outcome = assertIs<RetainedUnsuccessfulOutcomeResult.Outcome>(result).value
        assertEquals("/api/v4/sessions/01991b70-7c88-7123-9234-123456789abc/unsuccessful-outcome", requestedPath)
        assertEquals("application/json", accept)
        assertEquals("failed", outcome.state)
        assertEquals(42L, outcome.sourceRevision)
    }

    @Test
    fun `maps missing retained unsuccessful outcome to not found`() {
        val origin = startServer { exchange ->
            exchange.respondJson(404, """{"schema":"ylx.api-error.v2"}""")
        }

        val result = DeviceHttpClient().getRetainedUnsuccessfulOutcome(
            connection(origin),
            "01991b70-7c88-7123-9234-123456789abc",
        )

        assertIs<RetainedUnsuccessfulOutcomeResult.NotFound>(result)
    }

    @Test
    fun `rejects invalid retained unsuccessful outcome session id before sending request`() {
        val origin = startServer { exchange ->
            exchange.respondJson(500, """{"schema":"ylx.api-error.v2"}""")
        }

        val result = DeviceHttpClient().getRetainedUnsuccessfulOutcome(connection(origin), "not-a-session")

        assertIs<RetainedUnsuccessfulOutcomeResult.InvalidRequest>(result)
    }

    @Test
    fun `reads camera focus status`() {
        var requestedPath: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            exchange.respondJson(200, cameraFocusJson())
        }

        val result = DeviceHttpClient().getCameraFocus(connection(origin))

        val status = assertIs<CameraFocusResult.Status>(result).value
        assertEquals("/api/v4/camera/focus", requestedPath)
        assertEquals(120L, status.value)
        assertEquals(false, status.autoEnabled)
    }

    @Test
    fun `sets camera focus with idempotency key`() {
        var requestedPath: String? = null
        var idempotencyKey: String? = null
        var body: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key")
            body = exchange.requestBody.readBytes().decodeToString()
            exchange.respondJson(200, cameraFocusJson(value = 135))
        }

        val result = DeviceHttpClient().setCameraFocus(
            connection = connection(origin),
            idempotencyKey = "focus-1",
            value = 135,
            autoEnabled = false,
        )

        val status = assertIs<CameraFocusResult.Status>(result).value
        assertEquals("/api/v4/camera/focus", requestedPath)
        assertEquals("focus-1", idempotencyKey)
        assertEquals(
            """{"schema":"ylx.camera-focus-set.v1","value":135,"auto_enabled":false}""",
            assertNotNull(body).replace(" ", ""),
        )
        assertEquals(135L, status.value)
    }

    @Test
    fun `maps unsupported camera focus response`() {
        val origin = startServer { exchange ->
            exchange.respondJson(404, """{"schema":"ylx.api-error.v2"}""")
        }

        val result = DeviceHttpClient().getCameraFocus(connection(origin))

        assertIs<CameraFocusResult.Unsupported>(result)
    }

    @Test
    fun `downloads artifact only when representation matches manifest descriptor`() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val descriptor = artifactDescriptor(payload)
        var requestedPath: String? = null
        val origin = startServer { exchange ->
            requestedPath = exchange.requestURI.path
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("ETag", "\"${descriptor.artifactId}\"")
            exchange.respondBytes(200, descriptor.mediaType, payload)
        }
        val output = ByteArrayOutputStream()

        val result = DeviceHttpClient().downloadSessionArtifact(
            connection(origin),
            "01991b70-7c88-7123-9234-123456789abc",
            descriptor,
            output,
        )

        val downloaded = assertIs<ArtifactDownloadResult.Downloaded>(result)
        assertEquals("/api/v4/sessions/01991b70-7c88-7123-9234-123456789abc/artifacts/${descriptor.artifactId}", requestedPath)
        assertEquals(5L, downloaded.bytes)
        assertContentEquals(payload, output.toByteArray())
    }

    @Test
    fun `reads artifact metadata with HEAD before resumable download`() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val descriptor = artifactDescriptor(payload)
        var requestedMethod: String? = null
        var requestedPath: String? = null
        val origin = startServer { exchange ->
            requestedMethod = exchange.requestMethod
            requestedPath = exchange.requestURI.path
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("Content-Length", payload.size.toString())
            exchange.responseHeaders.set("Content-Type", descriptor.mediaType)
            exchange.responseHeaders.set("ETag", "\"${descriptor.artifactId}\"")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        val result = DeviceHttpClient().headSessionArtifact(
            connection(origin),
            "01991b70-7c88-7123-9234-123456789abc",
            descriptor,
        )

        assertIs<ArtifactHeadResult.Verified>(result)
        assertEquals("HEAD", requestedMethod)
        assertEquals("/api/v4/sessions/01991b70-7c88-7123-9234-123456789abc/artifacts/${descriptor.artifactId}", requestedPath)
    }

    @Test
    fun `resumes artifact download with single byte range and if range etag`() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val descriptor = artifactDescriptor(payload)
        var requestedRange: String? = null
        var requestedIfRange: String? = null
        val origin = startServer { exchange ->
            requestedRange = exchange.requestHeaders.getFirst("Range")
            requestedIfRange = exchange.requestHeaders.getFirst("If-Range")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("Content-Range", "bytes 3-4/5")
            exchange.responseHeaders.set("ETag", "\"${descriptor.artifactId}\"")
            exchange.respondBytes(206, descriptor.mediaType, "lo".toByteArray(Charsets.UTF_8))
        }
        val output = ByteArrayOutputStream()

        val result = DeviceHttpClient().downloadSessionArtifact(
            connection(origin),
            "01991b70-7c88-7123-9234-123456789abc",
            descriptor,
            output,
            resumeFromBytes = 3,
        )

        val downloaded = assertIs<ArtifactDownloadResult.Downloaded>(result)
        assertEquals("bytes=3-", requestedRange)
        assertEquals("\"${descriptor.artifactId}\"", requestedIfRange)
        assertEquals(2L, downloaded.bytes)
        assertContentEquals("lo".toByteArray(Charsets.UTF_8), output.toByteArray())
    }

    @Test
    fun `cancels artifact download without claiming saved bytes`() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val descriptor = artifactDescriptor(payload)
        val origin = startServer { exchange ->
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("ETag", "\"${descriptor.artifactId}\"")
            exchange.respondBytes(200, descriptor.mediaType, payload)
        }
        val output = ByteArrayOutputStream()

        val result = DeviceHttpClient().downloadSessionArtifact(
            connection(origin),
            "01991b70-7c88-7123-9234-123456789abc",
            descriptor,
            output,
            shouldCancel = { true },
        )

        val cancelled = assertIs<ArtifactDownloadResult.Cancelled>(result)
        assertEquals(0L, cancelled.bytes)
        assertContentEquals(ByteArray(0), output.toByteArray())
    }

    @Test
    fun `rejects artifact download when bytes fail SHA validation`() {
        val descriptor = artifactDescriptor("hello".toByteArray(Charsets.UTF_8))
        val origin = startServer { exchange ->
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("ETag", "\"${descriptor.artifactId}\"")
            exchange.respondBytes(200, descriptor.mediaType, "HELLO".toByteArray(Charsets.UTF_8))
        }

        val result = DeviceHttpClient().downloadSessionArtifact(
            connection(origin),
            "01991b70-7c88-7123-9234-123456789abc",
            descriptor,
            ByteArrayOutputStream(),
        )

        assertIs<ArtifactDownloadResult.IntegrityFailure>(result)
    }

    private fun connection(origin: String, bearerToken: String? = null): DeviceConnection {
        val target = assertIs<EndpointPolicy.Decision.Allowed>(EndpointPolicy.validate(origin)).target
        return DeviceConnection(
            target = target,
            descriptor = DeviceDescriptor(
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
                networkMutationCapable = false,
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
            ),
            bearerToken = bearerToken,
        )
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

    private fun HttpExchange.respondJson(statusCode: Int, body: String) {
        respondBytes(statusCode, "application/json", body.toByteArray(Charsets.UTF_8))
    }

    private fun HttpExchange.respondBytes(statusCode: Int, contentType: String, body: ByteArray) {
        responseHeaders.set("Content-Type", contentType)
        responseHeaders.set("Content-Length", body.size.toString())
        sendResponseHeaders(statusCode, body.size.toLong())
        responseBody.use { output -> output.write(body) }
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

    private fun idleCaptureSnapshotJson(): String {
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
                  "network": ${networkRuntimeJson()},
                  "live_imu": null,
                  "camera": {
                    "schema": "ylx.camera-connection.v1",
                    "state": "connected"
                  },
                  "camera_focus": null
                }
              }
            }
        """.trimIndent()
    }

    private fun recordingCaptureSnapshotJson(): String {
        return """
            {
              "schema": "ylx.capture-status.v4",
              "authority_epoch": "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
              "source_revision": 8,
              "snapshot": {
                "schema": "ylx.capture-snapshot-event.v4",
                "device_state": "recording",
                "active_recording": {
                  "generation_id": "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
                  "recording_state": {
                    "state": "recording"
                  }
                },
                "retained_unsuccessful": null,
                "runtime": {
                  "observed_at": "2026-08-28T04:00:10Z",
                  "connection_method": "wifi_ap",
                  "temperature_celsius": 48.8,
                  "network": ${networkRuntimeJson()},
                  "live_imu": null,
                  "camera": {
                    "schema": "ylx.camera-connection.v1",
                    "state": "connected"
                  },
                  "camera_focus": null
                }
              }
            }
        """.trimIndent()
    }

    private fun sse(vararg events: String): ByteArray {
        return events.joinToString(separator = "\n\n", postfix = "\n\n")
            .toByteArray(Charsets.UTF_8)
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
        return """{"schema":"ylx.capture-event.v4","sse_delivery_id":"$deliveryId","authority_epoch":"e989c6e5-14cc-4faa-9715-5abdb6b0355d","source_revision":$revision,"type":"$type","occurred_at":"2026-08-28T04:00:01Z","session_id":$encodedSessionId,"data":$dataJson}"""
    }

    private fun idleCaptureSnapshotDataJson(): String {
        return """{"schema":"ylx.capture-snapshot-event.v4","device_state":"idle","active_recording":null,"retained_unsuccessful":null,"runtime":{"observed_at":"2026-08-28T04:00:00Z","connection_method":"wifi_ap","temperature_celsius":48.2,"network":${networkRuntimeJson()},"live_imu":null,"camera":{"schema":"ylx.camera-connection.v1","state":"connected"},"camera_focus":null}}"""
    }

    private fun captureStateEventDataJson(): String {
        return """{"schema":"ylx.capture-state-event.v2","state":"recording","volume_id":"56005c52-31f1-4dac-91cd-d8eafd737d1c","generation_id":"e989c6e5-14cc-4faa-9715-5abdb6b0355d"}"""
    }

    private fun networkRuntimeJson(): String {
        return """{"ap":{"state":"active","interface":"uap0","addresses":["10.42.0.1/24"],"peer_or_ssid":"YLX-A13F"},"wifi_client":{"state":"active","interface":"wlan0","addresses":["192.168.110.36/24"],"peer_or_ssid":"Studio"},"wired":{"state":"disconnected","interface":null,"addresses":[],"peer_or_ssid":null},"default_route":"wifi_client"}"""
    }

    private fun sessionListJson(): String {
        return """
            {
              "schema": "ylx.session-list.v3",
              "catalog_revision": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "items": [
                {
                  "session_id": "01991b70-7c88-7123-9234-123456789abc",
                  "producer_outcome": "sealed",
                  "take_id": "01991b70-7c88-7456-9234-123456789abc",
                  "take_sequence": 1,
                  "continuation_of": null,
                  "display_name": "test take",
                  "device": {
                    "device_id": "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                    "device_label": "YLX-00ABCDEF"
                  },
                  "started_at": "2026-08-28T04:00:00Z",
                  "ended_at": "2026-08-28T04:00:10Z",
                  "duration_seconds": 10.0,
                  "total_bytes": 2048,
                  "verification": {
                    "actor": "gateway",
                    "validator": {
                      "name": "rp-ylx-validator",
                      "version": "0.5.2",
                      "build_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    },
                    "manifest_sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "verified_at": "2026-08-28T04:00:12Z",
                    "verdict": "usable",
                    "diagnostics": []
                  }
                }
              ],
              "diagnostics": [
                {
                  "quarantine_id": "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                  "code": "manifest_invalid",
                  "observed_at": "2026-08-28T04:00:00Z",
                  "message": "closed schema violation"
                }
              ],
              "next_cursor": null
            }
        """.trimIndent()
    }

    private fun safeSwapReceiptJson(): String {
        return """
            {
              "schema": "ylx.safe-swap-receipt-resource.v3",
              "receipt": {
                "schema": "ylx.safe-swap-receipt.v3",
                "session_id": "01991b70-7c88-7123-9234-123456789abc",
                "volume_id": "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                "generation_id": "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
                "manifest_id": "01991b70-7c88-7456-9234-123456789abc",
                "manifest_sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "sealed_at": "2026-08-28T04:00:10Z",
                "released_at": "2026-08-28T04:00:11Z",
                "release_state": "device-released",
                "open_handle_count": 0
              }
            }
        """.trimIndent()
    }

    private fun deviceSessionManifestJson(): String {
        return fixtureText("session-manifest-v2-recorded.json")
    }

    private fun rawSideBySideSessionManifestJson(): String {
        return """
            {
              "schema": "ylx.device-session.v2",
              "manifest_id": "01991b70-7c88-7456-9234-123456789abc",
              "sealed": true,
              "sealed_at": "2026-08-28T04:00:10Z",
              "session_id": "01991b70-7c88-7123-9234-123456789abc",
              "volume_id": "56005c52-31f1-4dac-91cd-d8eafd737d1c",
              "capture_mode": "production",
              "display_name": "test take",
              "device": {},
              "time": {},
              "take": {},
              "camera": {},
              "video": {
                "layout": "raw-side-by-side",
                "codec": "mjpeg",
                "continuous": true,
                "artifact": {
                  "artifact_id": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "role": "video.raw-side-by-side",
                  "path": "video.mjpeg",
                  "media_type": "video/x-motion-jpeg",
                  "bytes": 128,
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                }
              },
              "imu": {
                "artifact": {
                  "artifact_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "role": "imu.samples",
                  "path": "imu.ndjson",
                  "media_type": "application/x-ndjson",
                  "bytes": 128,
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                "sample_count": 24,
                "units": "raw_int16",
                "coordinate_frame": "raw_device_axes"
              },
              "frames": {
                "artifact": {
                  "artifact_id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "role": "frames.index",
                  "path": "frames.ndjson",
                  "media_type": "application/x-ndjson",
                  "bytes": 128,
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                },
                "count": 12
              },
              "audio": {},
              "logs": [],
              "integrity": {}
            }
        """.trimIndent()
    }

    private fun retainedUnsuccessfulOutcomeJson(): String {
        return """
            {
              "schema": "ylx.retained-unsuccessful-session-resource.v2",
              "authority_epoch": "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
              "source_revision": 42,
              "outcome": {
                "generation_id": "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
                "recording_state": {
                  "state": "failed",
                  "authority_epoch": "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
                  "state_revision": 42
                }
              }
            }
        """.trimIndent()
    }

    private fun cameraFocusJson(value: Int = 120): String {
        return """
            {
              "schema": "ylx.camera-focus.v1",
              "value": $value,
              "minimum": 0,
              "maximum": 255,
              "step": 5,
              "default": 60,
              "auto_supported": true,
              "auto_enabled": false
            }
        """.trimIndent()
    }

    private fun apiErrorJson(code: String, message: String, retryable: Boolean): String {
        return """
            {
              "schema": "ylx.api-error.v2",
              "error": {
                "code": "$code",
                "message": "$message",
                "request_id": "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
                "retryable": $retryable
              }
            }
        """.trimIndent()
    }
}
