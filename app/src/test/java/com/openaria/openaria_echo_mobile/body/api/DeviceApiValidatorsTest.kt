package com.openaria.openaria_echo_mobile.body.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceApiValidatorsTest {
    @Test
    fun `accepts valid DeviceDescriptor v4 shape`() {
        val result = DeviceApiValidators.validateDeviceDescriptor(validDeviceDescriptor())

        val valid = assertIs<Validation.Valid<DeviceDescriptor>>(result)
        assertEquals("rp-ylx-a13f", valid.value.deviceLabel)
        assertEquals("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", valid.value.hardwareFingerprint)
        assertEquals("0.5.2", valid.value.packageVersion)
        assertEquals("77f24f3777777777777777777777777777777777", valid.value.commit)
        assertEquals("rdk-x5-20260828", valid.value.buildId)
        assertEquals("customer", valid.value.securityProfile)
        assertEquals(true, valid.value.captureCapable)
        assertEquals(true, valid.value.rangeDownloadCapable)
        assertEquals(true, valid.value.networkMutationCapable)
        assertEquals(true, valid.value.calibrationCapture.supported)
        assertEquals(false, valid.value.calibrationCapture.enabled)
        assertEquals(false, valid.value.writable)
        assertEquals("wifi_client", valid.value.runtime.network.defaultRoute)
        assertEquals("active", valid.value.runtime.network.wifiClient.state)
        assertEquals("connected", valid.value.runtime.camera.state)
    }

    @Test
    fun `accepts central DeviceDescriptor fixture with camera and calibration capability`() {
        val result = DeviceApiValidators.validateDeviceDescriptor(fixtureMap("device-null-live-imu.json"))

        val valid = assertIs<Validation.Valid<DeviceDescriptor>>(result).value
        assertEquals("YLX-30D5872D", valid.deviceLabel)
        assertEquals("ethernet_lan", valid.runtime.connectionMethod)
        assertEquals("disconnected", valid.runtime.camera.state)
        assertEquals(true, valid.calibrationCapture.supported)
        assertEquals("hardware_unavailable", valid.calibrationCapture.disabledReason)
    }

    @Test
    fun `rejects unknown major schema in DeviceDescriptor`() {
        val result = DeviceApiValidators.validateDeviceDescriptor(
            validDeviceDescriptor() + ("schema" to "ylx.device.v5"),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("schema must be ylx.device.v4", invalid.message)
    }

    @Test
    fun `rejects unknown root properties because v4 schemas are closed`() {
        val result = DeviceApiValidators.validateDeviceDescriptor(
            validDeviceDescriptor() + ("optimistic_recording" to true),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("unknown key optimistic_recording", invalid.message)
    }

    @Test
    fun `accepts valid active capture snapshot`() {
        val result = DeviceApiValidators.validateCaptureStatusSnapshot(
            validCaptureSnapshot("recording", activeRecording = mapOf("session_id" to "placeholder")),
        )

        val valid = assertIs<Validation.Valid<CaptureStatusSnapshot>>(result)
        assertEquals("recording", valid.value.deviceState)
        assertEquals(true, valid.value.hasActiveRecording)
        assertEquals(42L, valid.value.sourceRevision)
    }

    @Test
    fun `rejects local active recording for idle capture state`() {
        val result = DeviceApiValidators.validateCaptureStatusSnapshot(
            validCaptureSnapshot("idle", activeRecording = mapOf("session_id" to "placeholder")),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("inactive device_state requires active_recording to be null", invalid.message)
    }

    @Test
    fun `rejects missing active recording for recording state`() {
        val result = DeviceApiValidators.validateCaptureStatusSnapshot(
            validCaptureSnapshot("recording", activeRecording = null),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("active device_state requires active_recording", invalid.message)
    }

    @Test
    fun `accepts live IMU quality from runtime snapshot`() {
        val result = DeviceApiValidators.validateCaptureStatusSnapshot(
            validCaptureSnapshot(
                deviceState = "idle",
                activeRecording = null,
                runtime = runtime(liveImu = liveImuObservation()),
            ),
        )

        val valid = assertIs<Validation.Valid<CaptureStatusSnapshot>>(result)
        assertEquals("good", valid.value.runtime.liveImuQuality)
    }

    @Test
    fun `rejects active network interface without an address`() {
        val runtime = runtime(
            network = networkRuntime(
                wifiClient = networkInterface(
                    state = "active",
                    interfaceName = "wlan0",
                    addresses = emptyList(),
                    peerOrSsid = "Studio",
                ),
            ),
        )
        val result = DeviceApiValidators.validateDeviceDescriptor(
            validDeviceDescriptor() + ("runtime" to runtime),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("runtime.network.wifi_client.active state requires at least one address", invalid.message)
    }

    @Test
    fun `accepts snapshot capture event and preserves source revision identity`() {
        val result = DeviceApiValidators.validateCaptureEvent(
            validCaptureEvent(
                type = "snapshot",
                data = validCaptureSnapshotData("idle", activeRecording = null),
                sessionId = null,
            ),
        )

        val valid = assertIs<Validation.Valid<CaptureEventPayload>>(result)
        assertEquals("1042", valid.value.sseDeliveryId)
        assertEquals(43L, valid.value.sourceRevision)
        assertEquals("idle", valid.value.snapshot?.deviceState)
    }

    @Test
    fun `safe swap capture event binds receipt to wrapper authority and revision`() {
        val result = DeviceApiValidators.validateCaptureEvent(
            validCaptureEvent(
                type = "safe_swap",
                data = validSafeSwapReceiptData(),
                sessionId = "01991b70-7c88-7123-9234-123456789abc",
            ),
        )

        val valid = assertIs<Validation.Valid<CaptureEventPayload>>(result).value
        assertEquals("e989c6e5-14cc-4faa-9715-5abdb6b0355d", valid.safeSwapReceipt?.authorityEpoch)
        assertEquals(43L, valid.safeSwapReceipt?.sourceRevision)
        assertEquals(0L, valid.safeSwapReceipt?.openHandleCount)
    }

    @Test
    fun `rejects state capture event without session identity`() {
        val result = DeviceApiValidators.validateCaptureEvent(
            validCaptureEvent(
                type = "state",
                data = mapOf(
                    "schema" to "ylx.capture-state-event.v2",
                    "state" to "recording",
                    "volume_id" to "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                    "generation_id" to "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
                ),
                sessionId = null,
            ),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("state event requires session_id", invalid.message)
    }

    @Test
    fun `rejects safe swap capture event when receipt session differs`() {
        val result = DeviceApiValidators.validateCaptureEvent(
            validCaptureEvent(
                type = "safe_swap",
                data = validSafeSwapReceiptData(),
                sessionId = "01991b70-7c88-7456-9234-123456789abc",
            ),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("safe_swap session_id must match receipt.session_id", invalid.message)
    }

    @Test
    fun `accepts empty session list contract shape`() {
        val result = DeviceApiValidators.validateSessionList(
            mapOf(
                "schema" to "ylx.session-list.v2",
                "items" to emptyList<Any>(),
                "diagnostics" to emptyList<Any>(),
                "next_cursor" to null,
            ),
        )

        val valid = assertIs<Validation.Valid<SessionListPage>>(result)
        assertEquals(0, valid.value.items.size)
        assertEquals(0, valid.value.diagnosticsCount)
        assertEquals(null, valid.value.nextCursor)
    }

    @Test
    fun `accepts sealed session summaries and diagnostics without promoting diagnostics to items`() {
        val result = DeviceApiValidators.validateSessionList(validSessionList())

        val valid = assertIs<Validation.Valid<SessionListPage>>(result)
        assertEquals(1, valid.value.items.size)
        assertEquals(1, valid.value.diagnosticsCount)
        assertEquals("cursor-2", valid.value.nextCursor)
        assertEquals("test take", valid.value.items.single().displayName)
        assertEquals("sealed", valid.value.items.single().producerOutcome)
        assertEquals("usable", valid.value.items.single().verificationVerdict)
    }

    @Test
    fun `rejects session summary with unknown root key`() {
        val invalidItem = validSessionSummary() + ("download_url" to "https://example.invalid")
        val list = validSessionList(items = listOf(invalidItem))

        val result = DeviceApiValidators.validateSessionList(list)

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("items[0].unknown key download_url", invalid.message)
    }

    @Test
    fun `accepts v2 error envelope used by v4 endpoints`() {
        val result = DeviceApiValidators.validateErrorResponse(
            mapOf(
                "schema" to "ylx.api-error.v2",
                "error" to mapOf(
                    "code" to "preview_unavailable",
                    "message" to "camera is warming up",
                    "request_id" to "8c3edbe1-25a4-4b85-a7e5-cd117e17f6bc",
                    "retryable" to true,
                ),
            ),
        )

        val valid = assertIs<Validation.Valid<ApiError>>(result)
        assertEquals("preview_unavailable", valid.value.code)
        assertEquals(true, valid.value.retryable)
    }

    @Test
    fun `accepts authoritative network status fixture with mdns and mutation capability`() {
        val result = DeviceApiValidators.validateNetworkStatus(fixtureMap("network-status.json"))

        val valid = assertIs<Validation.Valid<NetworkStatus>>(result).value
        assertEquals("wifi-client", valid.desired.mode)
        assertEquals("studio-wifi", valid.desired.wifiClient?.ssid)
        assertEquals("_ylx-capture._tcp", valid.observed.mdns.service)
        assertEquals(true, valid.mutationCapability.enabled)
        assertEquals(listOf("apply", "retry", "forget"), valid.mutationCapability.operations)
        assertEquals("committed", valid.transaction.latest?.status)
        assertEquals("reconnect_target_lan", valid.transaction.latest?.recoveryAction)
    }

    @Test
    fun `accepts disabled network status with typed mutation reason`() {
        val result = DeviceApiValidators.validateNetworkStatus(fixtureMap("network-status-disabled.json"))

        val valid = assertIs<Validation.Valid<NetworkStatus>>(result).value
        assertEquals(false, valid.mutationCapability.enabled)
        assertEquals("controller_unavailable", valid.mutationCapability.disabledReason)
    }

    @Test
    fun `accepts network scan fixture including hidden network`() {
        val result = DeviceApiValidators.validateNetworkScan(fixtureMap("network-scan.json"))

        val valid = assertIs<Validation.Valid<NetworkScanSnapshot>>(result).value
        assertEquals(4, valid.networks.size)
        assertEquals("摄影棚-5G", valid.networks[1].ssid)
        assertEquals(true, valid.networks.last().hidden)
        assertEquals(null, valid.networks.last().ssid)
    }

    @Test
    fun `accepts credential receipt without exposing a passphrase`() {
        val result = DeviceApiValidators.validateNetworkCredentialReceipt(
            fixtureMap("network-credential-receipt.json"),
        )

        val valid = assertIs<Validation.Valid<NetworkCredentialReceipt>>(result).value
        assertEquals("cred-0198d29f-ephemeral-001", valid.credentialRef)
        assertEquals(120L, valid.ttlSeconds)
        assertEquals(true, valid.singleUse)
    }

    @Test
    fun `accepts network transaction and SSE event fixtures`() {
        val transactionResult = DeviceApiValidators.validateNetworkTransactionReceipt(
            fixtureMap("network-transaction-accepted.json"),
        )
        val snapshotEventResult = DeviceApiValidators.validateNetworkEvent(
            fixtureMap("network-event-snapshot.json"),
        )
        val transactionEventResult = DeviceApiValidators.validateNetworkEvent(
            fixtureMap("network-event-transaction.json"),
        )

        val transaction = assertIs<Validation.Valid<NetworkTransactionReceipt>>(transactionResult).value
        assertEquals("accepted", transaction.transaction.status)
        assertEquals("await_device", transaction.transaction.recoveryAction)
        val snapshotEvent = assertIs<Validation.Valid<NetworkEventPayload>>(snapshotEventResult).value
        assertEquals("snapshot", snapshotEvent.type)
        assertEquals("ylx.network-status.v1", snapshotEvent.status?.let { "ylx.network-status.v1" })
        val transactionEvent = assertIs<Validation.Valid<NetworkEventPayload>>(transactionEventResult).value
        assertEquals("transaction", transactionEvent.type)
        assertEquals("committed", transactionEvent.transaction?.status)
    }

    @Test
    fun `rejects network status that echoes credential material`() {
        val status = fixtureMap("network-status.json").toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val desired = (status["desired"] as Map<String, Any?>).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val wifi = (desired["wifi_client"] as Map<String, Any?>).toMutableMap()
        wifi["passphrase"] = "must-not-echo"
        desired["wifi_client"] = wifi
        status["desired"] = desired

        val result = DeviceApiValidators.validateNetworkStatus(status)

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("desired.wifi_client.unknown key passphrase", invalid.message)
    }

    @Test
    fun `accepts safe swap v3 receipt with zero open handles`() {
        val result = DeviceApiValidators.validateSafeSwapReceiptResource(validSafeSwapReceipt())

        val valid = assertIs<Validation.Valid<SafeSwapReceiptSummary>>(result)
        assertEquals("device-released", valid.value.releaseState)
        assertEquals("01991b70-7c88-7123-9234-123456789abc", valid.value.sessionId)
    }

    @Test
    fun `rejects safe swap receipt with open handles`() {
        @Suppress("UNCHECKED_CAST")
        val receipt = validSafeSwapReceipt()["receipt"] as Map<String, Any?>
        val result = DeviceApiValidators.validateSafeSwapReceiptResource(
            validSafeSwapReceipt() + ("receipt" to (receipt + ("open_handle_count" to 1L))),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("receipt.open_handle_count must be 0", invalid.message)
    }

    @Test
    fun `accepts device session manifest and collects artifact descriptors`() {
        val result = DeviceApiValidators.validateDeviceSessionManifest(validDeviceSessionManifest())

        val valid = assertIs<Validation.Valid<DeviceSessionManifest>>(result)
        assertEquals("test take", valid.value.displayName)
        assertEquals(3, valid.value.artifacts.size)
        assertEquals(listOf("imu.samples", "frames.index", "video.raw-side-by-side"), valid.value.artifacts.map { it.role })
    }

    @Test
    fun `rejects device session manifest artifact unsafe path`() {
        val imu = validArtifact("imu.samples", "../imu.ndjson", "a")
        val result = DeviceApiValidators.validateDeviceSessionManifest(
            validDeviceSessionManifest(imuArtifact = imu),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("imu.artifact.path is not a safe relative artifact path", invalid.message)
    }

    @Test
    fun `accepts retained unsuccessful outcome with terminal state`() {
        val result = DeviceApiValidators.validateRetainedUnsuccessfulSessionResource(
            validRetainedUnsuccessfulOutcome(),
        )

        val valid = assertIs<Validation.Valid<RetainedUnsuccessfulOutcome>>(result)
        assertEquals("failed", valid.value.state)
        assertEquals(42L, valid.value.sourceRevision)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `rejects retained unsuccessful outcome when recording revision differs`() {
        val outcome = validRetainedUnsuccessfulOutcome().getValue("outcome") as Map<String, Any?>
        val recordingState = outcome.getValue("recording_state") as Map<String, Any?>
        val result = DeviceApiValidators.validateRetainedUnsuccessfulSessionResource(
            validRetainedUnsuccessfulOutcome() + (
                "outcome" to outcome + (
                    "recording_state" to recordingState + ("state_revision" to 41L)
                    )
                ),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("outcome.recording_state.state_revision must match source_revision", invalid.message)
    }

    @Test
    fun `accepts camera focus status with bounded manual value`() {
        val result = DeviceApiValidators.validateCameraFocusStatus(validCameraFocusStatus())

        val valid = assertIs<Validation.Valid<CameraFocusStatus>>(result)
        assertEquals(120L, valid.value.value)
        assertEquals(true, valid.value.autoSupported)
        assertEquals(false, valid.value.autoEnabled)
    }

    @Test
    fun `rejects camera focus auto state when auto is unsupported`() {
        val result = DeviceApiValidators.validateCameraFocusStatus(
            validCameraFocusStatus() + ("auto_supported" to false),
        )

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("auto_enabled must be null when auto is unsupported", invalid.message)
    }

    private fun validCaptureSnapshot(
        deviceState: String,
        activeRecording: Map<String, Any?>?,
        runtime: Map<String, Any?> = runtime(),
    ): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.capture-status.v4",
            "authority_epoch" to "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
            "source_revision" to 42L,
            "snapshot" to validCaptureSnapshotData(deviceState, activeRecording, runtime),
        )
    }

    private fun validCaptureSnapshotData(
        deviceState: String,
        activeRecording: Map<String, Any?>?,
        runtime: Map<String, Any?> = runtime(),
    ): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.capture-snapshot-event.v4",
            "device_state" to deviceState,
            "active_recording" to activeRecording,
            "retained_unsuccessful" to null,
            "runtime" to runtime,
        )
    }

    private fun validDeviceDescriptor(): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.device.v4",
            "device" to mapOf(
                "device_id" to "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                "device_label" to "rp-ylx-a13f",
            ),
            "hardware_fingerprint" to "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "api_version" to "4.0",
            "build" to mapOf(
                "package_version" to "0.5.2",
                "commit" to "77f24f3777777777777777777777777777777777",
                "build_id" to "rdk-x5-20260828",
            ),
            "security_profile" to "customer",
            "capabilities" to mapOf(
                "capture" to true,
                "preview" to true,
                "range_download" to true,
                "network_mutation" to true,
                "calibration_capture" to mapOf(
                    "supported" to true,
                    "enabled" to false,
                    "disabled_reason" to "storage_unavailable",
                    "required_video_layout" to "split-eyes",
                ),
            ),
            "storage" to mapOf(
                "volume_id" to "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                "total_bytes" to 1024L,
                "available_bytes" to 512L,
                "writable" to false,
            ),
            "runtime" to runtime(),
        )
    }

    private fun validCaptureEvent(
        type: String,
        data: Map<String, Any?>,
        sessionId: String? = "01991b70-7c88-7123-9234-123456789abc",
    ): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.capture-event.v4",
            "sse_delivery_id" to "1042",
            "authority_epoch" to "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
            "source_revision" to 43L,
            "type" to type,
            "occurred_at" to "2026-08-28T04:00:01Z",
            "session_id" to sessionId,
            "data" to data,
        )
    }

    private fun validSessionList(
        items: List<Map<String, Any?>> = listOf(validSessionSummary()),
    ): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.session-list.v2",
            "items" to items,
            "diagnostics" to listOf(
                mapOf(
                    "quarantine_id" to "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                    "code" to "manifest_invalid",
                    "observed_at" to "2026-08-28T04:00:00Z",
                    "message" to "closed schema violation",
                ),
            ),
            "next_cursor" to "cursor-2",
        )
    }

    private fun validSessionSummary(): Map<String, Any?> {
        return mapOf(
            "session_id" to "01991b70-7c88-7123-9234-123456789abc",
            "producer_outcome" to "sealed",
            "take_id" to "01991b70-7c88-7456-9234-123456789abc",
            "take_sequence" to 1L,
            "continuation_of" to null,
            "display_name" to "test take",
            "device" to mapOf(
                "device_id" to "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                "device_label" to "YLX-00ABCDEF",
            ),
            "started_at" to "2026-08-28T04:00:00Z",
            "ended_at" to "2026-08-28T04:00:10Z",
            "duration_seconds" to 10.0,
            "total_bytes" to 2048L,
            "verification" to mapOf(
                "actor" to "gateway",
                "validator" to mapOf(
                    "name" to "rp-ylx-validator",
                    "version" to "0.5.2",
                    "build_sha256" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
                "manifest_sha256" to "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "verified_at" to "2026-08-28T04:00:12Z",
                "verdict" to "usable",
                "diagnostics" to emptyList<String>(),
            ),
        )
    }

    private fun validSafeSwapReceipt(): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.safe-swap-receipt-resource.v3",
            "receipt" to validSafeSwapReceiptData(),
        )
    }

    private fun validSafeSwapReceiptData(): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.safe-swap-receipt.v3",
            "session_id" to "01991b70-7c88-7123-9234-123456789abc",
            "volume_id" to "56005c52-31f1-4dac-91cd-d8eafd737d1c",
            "generation_id" to "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
            "manifest_id" to "01991b70-7c88-7456-9234-123456789abc",
            "manifest_sha256" to "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            "sealed_at" to "2026-08-28T04:00:10Z",
            "released_at" to "2026-08-28T04:00:11Z",
            "release_state" to "device-released",
            "open_handle_count" to 0L,
        )
    }

    private fun validDeviceSessionManifest(
        imuArtifact: Map<String, Any?> = validArtifact("imu.samples", "imu.ndjson", "a"),
    ): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.device-session.v2",
            "manifest_id" to "01991b70-7c88-7456-9234-123456789abc",
            "sealed" to true,
            "sealed_at" to "2026-08-28T04:00:10Z",
            "session_id" to "01991b70-7c88-7123-9234-123456789abc",
            "volume_id" to "56005c52-31f1-4dac-91cd-d8eafd737d1c",
            "capture_mode" to "production",
            "display_name" to "test take",
            "device" to mapOf<String, Any?>(),
            "time" to mapOf<String, Any?>(),
            "take" to mapOf<String, Any?>(),
            "camera" to mapOf<String, Any?>(),
            "video" to mapOf(
                "layout" to "raw-side-by-side",
                "codec" to "mjpeg",
                "continuous" to true,
                "artifact" to validArtifact("video.raw-side-by-side", "video.mjpeg", "c"),
            ),
            "imu" to mapOf(
                "artifact" to imuArtifact,
                "sample_count" to 24L,
                "units" to "raw_int16",
                "coordinate_frame" to "raw_device_axes",
            ),
            "frames" to mapOf(
                "artifact" to validArtifact("frames.index", "frames.ndjson", "b"),
                "count" to 12L,
            ),
            "audio" to mapOf<String, Any?>(),
            "logs" to emptyList<Any>(),
            "integrity" to mapOf<String, Any?>(),
        )
    }

    private fun validRetainedUnsuccessfulOutcome(): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.retained-unsuccessful-session-resource.v2",
            "authority_epoch" to "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
            "source_revision" to 42L,
            "outcome" to mapOf(
                "generation_id" to "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
                "recording_state" to mapOf(
                    "state" to "failed",
                    "authority_epoch" to "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
                    "state_revision" to 42L,
                ),
            ),
        )
    }

    private fun validArtifact(role: String, path: String, hex: String): Map<String, Any?> {
        val digest = hex.repeat(64)
        return mapOf(
            "artifact_id" to digest,
            "role" to role,
            "path" to path,
            "media_type" to when {
                role.startsWith("video.") -> "video/x-motion-jpeg"
                else -> "application/x-ndjson"
            },
            "bytes" to 128L,
            "sha256" to digest,
        )
    }

    private fun validCameraFocusStatus(): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.camera-focus.v1",
            "value" to 120L,
            "minimum" to 0L,
            "maximum" to 255L,
            "step" to 5L,
            "default" to 60L,
            "auto_supported" to true,
            "auto_enabled" to false,
        )
    }

    private fun runtime(
        network: Map<String, Any?> = networkRuntime(),
        liveImu: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        return mapOf(
            "observed_at" to "2026-08-28T04:00:00Z",
            "connection_method" to "wifi_ap",
            "temperature_celsius" to 48.2,
            "network" to network,
            "live_imu" to liveImu,
            "camera" to mapOf(
                "schema" to "ylx.camera-connection.v1",
                "state" to "connected",
            ),
            "camera_focus" to null,
        )
    }

    private fun networkRuntime(
        ap: Map<String, Any?> = networkInterface(
            state = "active",
            interfaceName = "uap0",
            addresses = listOf("10.42.0.1/24"),
            peerOrSsid = "YLX-A13F",
        ),
        wifiClient: Map<String, Any?> = networkInterface(
            state = "active",
            interfaceName = "wlan0",
            addresses = listOf("192.168.110.36/24"),
            peerOrSsid = "Studio",
        ),
        wired: Map<String, Any?> = networkInterface(
            state = "disconnected",
            interfaceName = null,
            addresses = emptyList(),
            peerOrSsid = null,
        ),
        defaultRoute: String = "wifi_client",
    ): Map<String, Any?> {
        return mapOf(
            "ap" to ap,
            "wifi_client" to wifiClient,
            "wired" to wired,
            "default_route" to defaultRoute,
        )
    }

    private fun networkInterface(
        state: String,
        interfaceName: String?,
        addresses: List<String>,
        peerOrSsid: String?,
    ): Map<String, Any?> {
        return mapOf(
            "state" to state,
            "interface" to interfaceName,
            "addresses" to addresses,
            "peer_or_ssid" to peerOrSsid,
        )
    }

    private fun liveImuObservation(): Map<String, Any?> {
        return mapOf(
            "session_id" to "01991b70-7c88-7123-9234-123456789abc",
            "clock" to mapOf(
                "time_base" to "host_monotonic",
                "timestamp_ns" to 1_000_000L,
            ),
            "raw" to mapOf(
                "units" to "raw_int16",
                "accelerometer" to mapOf("x" to 1L, "y" to 2L, "z" to 3L),
                "gyroscope" to mapOf("x" to 4L, "y" to 5L, "z" to 6L),
            ),
            "sync" to mapOf("quality" to "good"),
        )
    }

    private fun fixtureMap(name: String): Map<String, Any?> {
        val body = requireNotNull(
            javaClass.classLoader?.getResource("device-api/v4/valid/$name"),
        ) { "missing fixture $name" }.readText()
        return when (val result = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> result.value
            is DeviceJsonPayload.Result.Invalid -> error(result.message)
        }
    }
}
