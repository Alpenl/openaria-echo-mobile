package com.openaria.openaria_echo_mobile.body.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceApiValidatorsTest {
    @Test
    fun `accepts valid DeviceDescriptor v4 shape`() {
        val result = DeviceApiValidators.validateDeviceDescriptor(validDeviceDescriptor())

        val valid = assertIs<Validation.Valid<DeviceDescriptor>>(result)
        assertEquals("YLX-00ABCDEF", valid.value.deviceLabel)
        assertEquals("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", valid.value.hardwareFingerprint)
        assertEquals("0.5.2", valid.value.packageVersion)
        assertEquals("77f24f3777777777777777777777777777777777", valid.value.commit)
        assertEquals("rdk-x5-20260828", valid.value.buildId)
        assertEquals("customer", valid.value.securityProfile)
        assertEquals(true, valid.value.captureCapable)
        assertEquals(true, valid.value.rangeDownloadCapable)
        assertEquals(true, valid.value.networkMutationCapable)
        assertEquals(true, valid.value.sessionListCapable)
        assertEquals(true, valid.value.sessionDetailCapable)
        assertEquals(true, valid.value.artifactDownloadCapable)
        assertEquals(true, valid.value.captureStatusCapable)
        assertEquals(false, valid.value.sessionDeletionCapable)
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
    fun `accepts complete Conductor DeviceDescriptor capabilities`() {
        val result = DeviceApiValidators.validateDeviceDescriptor(
            fixtureMap("device-full-capabilities.json"),
        )

        val descriptor = assertIs<Validation.Valid<DeviceDescriptor>>(result).value
        assertEquals(true, descriptor.rangeDownloadCapable)
        assertEquals(true, descriptor.sessionListCapable)
        assertEquals(true, descriptor.sessionDetailCapable)
        assertEquals(true, descriptor.artifactDownloadCapable)
        assertEquals(true, descriptor.captureStatusCapable)
        assertEquals(false, descriptor.sessionDeletionCapable)
    }

    @Test
    fun `pins the complete v4 capability key set and constant values in the fixture`() {
        val descriptor = fixtureMap("device-full-capabilities.json")
        @Suppress("UNCHECKED_CAST")
        val capabilities = descriptor.getValue("capabilities") as Map<String, Any?>

        assertEquals(
            setOf(
                "capture",
                "preview",
                "range_download",
                "network_mutation",
                "session_list",
                "session_detail",
                "artifact_download",
                "capture_status",
                "session_deletion",
                "calibration_capture",
            ),
            capabilities.keys,
        )
        assertEquals(true, capabilities["range_download"])
        assertEquals(true, capabilities["session_list"])
        assertEquals(true, capabilities["session_detail"])
        assertEquals(true, capabilities["artifact_download"])
        assertEquals(true, capabilities["capture_status"])
        assertEquals(false, capabilities["session_deletion"])
    }

    @Test
    fun `rejects unknown DeviceIdentity properties because the object is closed`() {
        val descriptor = validDeviceDescriptor()
        @Suppress("UNCHECKED_CAST")
        val device = descriptor.getValue("device") as Map<String, Any?>

        val result = DeviceApiValidators.validateDeviceDescriptor(
            descriptor + ("device" to (device + ("serial_number" to "must-not-pass"))),
        )

        assertEquals(
            "device.unknown key serial_number",
            assertIs<Validation.Invalid>(result).message,
        )
    }

    @Test
    fun `rejects DeviceIdentity labels outside the uppercase YLX format`() {
        val descriptor = validDeviceDescriptor()
        @Suppress("UNCHECKED_CAST")
        val device = descriptor.getValue("device") as Map<String, Any?>
        val invalidLabels = listOf(
            "rp-ylx-a13f",
            "YLX-00abcdef",
            "YLX-ABCDEF0",
            "YLX-ABCDEFGH",
        )

        invalidLabels.forEach { label ->
            val result = DeviceApiValidators.validateDeviceDescriptor(
                descriptor + ("device" to (device + ("device_label" to label))),
            )

            assertEquals(
                "device.device_label must match YLX-<8 uppercase hex>",
                assertIs<Validation.Invalid>(result).message,
            )
        }
    }

    @Test
    fun `lab profile accepts either advertised network mutation capability`() {
        val descriptor = validDeviceDescriptor()
        @Suppress("UNCHECKED_CAST")
        val capabilities = descriptor.getValue("capabilities") as Map<String, Any?>

        listOf(false, true).forEach { networkMutation ->
            val result = DeviceApiValidators.validateDeviceDescriptor(
                descriptor +
                    ("security_profile" to "lab") +
                    ("capabilities" to (capabilities + ("network_mutation" to networkMutation))),
            )

            val projected = assertIs<Validation.Valid<DeviceDescriptor>>(result).value
            assertEquals("lab", projected.securityProfile)
            assertEquals(networkMutation, projected.networkMutationCapable)
        }
    }

    @Test
    fun `enforces DeviceDescriptor build string boundaries`() {
        val descriptor = validDeviceDescriptor()
        @Suppress("UNCHECKED_CAST")
        val build = descriptor.getValue("build") as Map<String, Any?>

        listOf(
            build + ("package_version" to "p".repeat(64)) + ("build_id" to "b".repeat(128)),
            build + ("package_version" to "v"),
            build + ("build_id" to "i"),
        ).forEach { boundaryBuild ->
            assertIs<Validation.Valid<DeviceDescriptor>>(
                DeviceApiValidators.validateDeviceDescriptor(
                    descriptor + ("build" to boundaryBuild),
                ),
            )
        }

        val tooLongPackage = DeviceApiValidators.validateDeviceDescriptor(
            descriptor + ("build" to (build + ("package_version" to "p".repeat(65)))),
        )
        val emptyPackage = DeviceApiValidators.validateDeviceDescriptor(
            descriptor + ("build" to (build + ("package_version" to ""))),
        )
        val tooLongBuildId = DeviceApiValidators.validateDeviceDescriptor(
            descriptor + ("build" to (build + ("build_id" to "b".repeat(129)))),
        )
        val emptyBuildId = DeviceApiValidators.validateDeviceDescriptor(
            descriptor + ("build" to (build + ("build_id" to ""))),
        )

        assertEquals(
            "build.package_version must contain 1..64 characters",
            assertIs<Validation.Invalid>(tooLongPackage).message,
        )
        assertEquals(
            "build.package_version must contain 1..64 characters",
            assertIs<Validation.Invalid>(emptyPackage).message,
        )
        assertEquals(
            "build.build_id must contain 1..128 characters",
            assertIs<Validation.Invalid>(tooLongBuildId).message,
        )
        assertEquals(
            "build.build_id must contain 1..128 characters",
            assertIs<Validation.Invalid>(emptyBuildId).message,
        )
    }

    @Test
    fun `enforces runtime date-time and temperature boundaries`() {
        val descriptor = validDeviceDescriptor()
        @Suppress("UNCHECKED_CAST")
        val runtime = descriptor.getValue("runtime") as Map<String, Any?>

        listOf(-40.0, 125.0).forEach { temperature ->
            assertIs<Validation.Valid<DeviceDescriptor>>(
                DeviceApiValidators.validateDeviceDescriptor(
                    descriptor + ("runtime" to (runtime + ("temperature_celsius" to temperature))),
                ),
            )
        }

        val invalidTimestamp = DeviceApiValidators.validateDeviceDescriptor(
            descriptor + ("runtime" to (runtime + ("observed_at" to "2026-08-28 04:00:00"))),
        )
        assertEquals(
            "runtime.observed_at must be an RFC 3339 date-time",
            assertIs<Validation.Invalid>(invalidTimestamp).message,
        )
        listOf(-40.1, 125.1).forEach { temperature ->
            val result = DeviceApiValidators.validateDeviceDescriptor(
                descriptor + ("runtime" to (runtime + ("temperature_celsius" to temperature))),
            )
            assertEquals(
                "runtime.temperature_celsius must be in -40..125",
                assertIs<Validation.Invalid>(result).message,
            )
        }
    }

    @Test
    fun `rejects legacy five-key capabilities without fallback`() {
        val result = DeviceApiValidators.validateDeviceDescriptor(
            fixtureMap("device-legacy-five-capabilities.json", "invalid"),
        )

        assertEquals(
            "missing required key session_list",
            assertIs<Validation.Invalid>(result).message,
        )
    }

    @Test
    fun `rejects missing wrong-type and unknown capability fields`() {
        val descriptor = validDeviceDescriptor()
        @Suppress("UNCHECKED_CAST")
        val capabilities = descriptor.getValue("capabilities") as Map<String, Any?>
        val cases = listOf(
            capabilities - "capture_status" to "missing required key capture_status",
            capabilities + ("session_list" to "true") to "capabilities.session_list must be boolean",
            capabilities + ("future_capability" to true) to "unknown key future_capability",
        )

        cases.forEach { (candidateCapabilities, expectedMessage) ->
            val result = DeviceApiValidators.validateDeviceDescriptor(
                descriptor + ("capabilities" to candidateCapabilities),
            )

            assertEquals(expectedMessage, assertIs<Validation.Invalid>(result).message)
        }
    }

    @Test
    fun `rejects capability constants that contradict Device API v4`() {
        val descriptor = validDeviceDescriptor()
        @Suppress("UNCHECKED_CAST")
        val capabilities = descriptor.getValue("capabilities") as Map<String, Any?>
        val contradictions = mapOf(
            "range_download" to false,
            "session_list" to false,
            "session_detail" to false,
            "artifact_download" to false,
            "capture_status" to false,
            "session_deletion" to true,
        )

        contradictions.forEach { (name, value) ->
            val result = DeviceApiValidators.validateDeviceDescriptor(
                descriptor + ("capabilities" to (capabilities + (name to value))),
            )
            val expected = if (name == "session_deletion") {
                "capabilities.session_deletion must be false"
            } else {
                "capabilities.$name must be true"
            }

            assertEquals(expected, assertIs<Validation.Invalid>(result).message)
        }
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
        assertEquals(SessionListContract.V2, valid.value.contract)
        assertEquals(null, valid.value.catalogRevision)
        assertEquals(0, valid.value.items.size)
        assertEquals(0, valid.value.diagnosticsCount)
        assertEquals(null, valid.value.nextCursor)
    }

    @Test
    fun `v2 session list ignores its legacy continuation cursor`() {
        val result = DeviceApiValidators.validateSessionList(validSessionList())

        val valid = assertIs<Validation.Valid<SessionListPage>>(result)
        assertEquals(SessionListContract.V2, valid.value.contract)
        assertEquals(1, valid.value.items.size)
        assertEquals(1, valid.value.diagnosticsCount)
        assertEquals(null, valid.value.nextCursor)
        assertEquals("test take", valid.value.items.single().displayName)
        assertEquals("sealed", valid.value.items.single().producerOutcome)
        assertEquals("usable", valid.value.items.single().verificationVerdict)
    }

    @Test
    fun `accepts v3 session list with catalog-bound opaque cursor`() {
        val result = DeviceApiValidators.validateSessionList(fixtureMap("session-list-v3.json"))

        val valid = assertIs<Validation.Valid<SessionListPage>>(result).value
        assertEquals(SessionListContract.V3, valid.contract)
        assertEquals(
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            valid.catalogRevision,
        )
        assertEquals("opaque-page-2", valid.nextCursor)
    }

    @Test
    fun `accepts authoritative v3 unusable verification diagnostic objects`() {
        val result = DeviceApiValidators.validateSessionList(
            fixtureMap("session-list-v3-unusable.json"),
        )

        val valid = assertIs<Validation.Valid<SessionListPage>>(result).value
        assertEquals(SessionListContract.V3, valid.contract)
        val session = valid.items.single()
        assertEquals("unusable", session.verificationVerdict)
        assertEquals("unusable test take", session.displayName)
        val verification = requireNotNull(session.verification)
        assertEquals(GatewayVerificationVerdict.UNUSABLE, verification.verdict)
        assertEquals("gateway", verification.actor)
        assertEquals(
            "openaria-conductor-device-session-v2-integrity",
            verification.validator.name,
        )
        assertEquals("1", verification.validator.version)
        assertEquals(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            verification.validator.buildSha256,
        )
        assertEquals(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            verification.manifestSha256,
        )
        assertEquals("2026-08-28T04:00:12Z", verification.verifiedAt)
        val diagnostic = assertIs<GatewayVerificationDiagnostic.Current>(
            verification.diagnostics.single(),
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

    @Test
    fun `preserves authoritative quarantine diagnostics without promoting sessions`() {
        val result = DeviceApiValidators.validateSessionList(
            value = fixtureMap("session-list-v3-quarantine.json"),
            requestIdentity = SessionListRequestIdentity(limit = 1, cursor = null, takeId = null),
        )

        val page = assertIs<Validation.Valid<SessionListPage>>(result).value
        assertEquals(emptyList(), page.items)
        val diagnostic = page.diagnostics.single()
        assertEquals("56005c52-31f1-4dac-91cd-d8eafd737d1c", diagnostic.quarantineId)
        assertEquals("manifest_invalid", diagnostic.code)
        assertEquals("2026-08-28T04:00:00Z", diagnostic.observedAt)
        assertEquals(
            "manifest does not satisfy the closed Device Session contract",
            diagnostic.message,
        )
    }

    @Test
    fun `rejects malformed or repeated closed quarantine diagnostics`() {
        val diagnostic = validSessionDiscoveryDiagnostic()
        val malformed = listOf(
            (diagnostic + ("path" to "/secret/session")) to
                "diagnostics[0].unknown key path",
            (diagnostic - "message") to
                "diagnostics[0].missing required key message",
            (diagnostic + ("code" to 42L)) to
                "diagnostics[0].code is required",
            (diagnostic + ("observed_at" to "not-a-date")) to
                "diagnostics[0].observed_at must be an RFC 3339 date-time",
        )
        malformed.forEach { (candidate, expectedMessage) ->
            val result = DeviceApiValidators.validateSessionList(
                validV3SessionList(items = emptyList(), diagnostics = listOf(candidate)),
            )

            assertEquals(expectedMessage, assertIs<Validation.Invalid>(result).message)
        }

        val repeated = DeviceApiValidators.validateSessionList(
            validV3SessionList(
                items = emptyList(),
                diagnostics = listOf(diagnostic, diagnostic),
            ),
        )
        assertEquals(
            "diagnostics must not repeat quarantine_id",
            assertIs<Validation.Invalid>(repeated).message,
        )
    }

    @Test
    fun `session page validation is bound to exact request identity and ordering`() {
        val defaultItem = validSessionSummary()
        val combinedOverLimit = DeviceApiValidators.validateSessionList(
            value = validV3SessionList(
                items = listOf(defaultItem),
                diagnostics = listOf(validSessionDiscoveryDiagnostic()),
            ),
            requestIdentity = SessionListRequestIdentity(limit = 1, cursor = null, takeId = null),
        )
        val wrongTake = DeviceApiValidators.validateSessionList(
            value = validV3SessionList(items = listOf(defaultItem)),
            requestIdentity = SessionListRequestIdentity(
                limit = 1,
                cursor = null,
                takeId = "01991b70-7c88-7567-9234-123456789abc",
            ),
        )
        val duplicate = DeviceApiValidators.validateSessionList(
            value = validV3SessionList(items = listOf(defaultItem, defaultItem)),
            requestIdentity = SessionListRequestIdentity(limit = 2, cursor = null, takeId = null),
        )
        val older = validSessionSummary(
            sessionId = "01991b6f-7c88-7123-9234-123456789abc",
            startedAt = "2026-08-28T03:00:00Z",
        )
        val newer = validSessionSummary(
            sessionId = "01991b71-7c88-7123-9234-123456789abc",
            startedAt = "2026-08-28T05:00:00Z",
        )
        val inverted = DeviceApiValidators.validateSessionList(
            value = validV3SessionList(items = listOf(older, newer)),
            requestIdentity = SessionListRequestIdentity(limit = 2, cursor = null, takeId = null),
        )
        val legacyCursor = DeviceApiValidators.validateSessionList(
            value = validSessionList(items = emptyList()),
            requestIdentity = SessionListRequestIdentity(
                limit = 1,
                cursor = "opaque-v4-cursor",
                takeId = null,
            ),
        )
        val cursorBound = DeviceApiValidators.validateSessionList(
            value = fixtureMap("session-list-v3.json"),
            requestIdentity = SessionListRequestIdentity(
                limit = 1,
                cursor = "opaque-request-cursor",
                takeId = null,
            ),
        )
        val nonAdvancingCursor = DeviceApiValidators.validateSessionList(
            value = fixtureMap("session-list-v3.json"),
            requestIdentity = SessionListRequestIdentity(
                limit = 1,
                cursor = "opaque-page-2",
                takeId = null,
            ),
        )

        assertEquals(
            "items and diagnostics exceed the request limit",
            assertIs<Validation.Invalid>(combinedOverLimit).message,
        )
        assertEquals(
            "items[0].take_id does not match the request filter",
            assertIs<Validation.Invalid>(wrongTake).message,
        )
        assertEquals(
            "items must not repeat session_id",
            assertIs<Validation.Invalid>(duplicate).message,
        )
        assertEquals(
            "items must be ordered newest-first by started_at and session_id",
            assertIs<Validation.Invalid>(inverted).message,
        )
        assertEquals(
            "ylx.session-list.v2 cannot satisfy a cursor request",
            assertIs<Validation.Invalid>(legacyCursor).message,
        )
        assertEquals(
            SessionListRequestIdentity(1, "opaque-request-cursor", null),
            assertIs<Validation.Valid<SessionListPage>>(cursorBound).value.requestIdentity,
        )
        assertEquals(
            "next_cursor must advance beyond the request cursor",
            assertIs<Validation.Invalid>(nonAdvancingCursor).message,
        )
    }

    @Test
    fun `verification diagnostics obey the tagged v2 and v3 wire contracts`() {
        val legacyString = DeviceApiValidators.validateSessionList(
            validSessionList(
                items = listOf(
                    validSessionSummary(
                        verdict = "unusable",
                        diagnostics = listOf("artifact content digest mismatch"),
                    ),
                ),
            ),
        )
        val legacyObject = DeviceApiValidators.validateSessionList(
            validSessionList(
                items = listOf(
                    validSessionSummary(
                        verdict = "unusable",
                        diagnostics = listOf(
                            mapOf(
                                "code" to "artifact_digest_mismatch",
                                "summary" to "artifact content digest mismatch",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val currentUsableEmpty = DeviceApiValidators.validateSessionList(
            validV3SessionList(items = listOf(validSessionSummary())),
        )

        val legacyPage = assertIs<Validation.Valid<SessionListPage>>(legacyString).value
        val legacyDiagnostic = assertIs<GatewayVerificationDiagnostic.Legacy>(
            requireNotNull(legacyPage.items.single().verification).diagnostics.single(),
        )
        assertEquals("artifact content digest mismatch", legacyDiagnostic.summary)
        assertEquals(
            "items[0].verification.diagnostics entries must be strings containing 1..512 characters",
            assertIs<Validation.Invalid>(legacyObject).message,
        )
        assertEquals(
            "usable",
            assertIs<Validation.Valid<SessionListPage>>(currentUsableEmpty)
                .value.items.single().verificationVerdict,
        )
    }

    @Test
    fun `rejects unknown missing extra and wrong-type v3 verification diagnostics`() {
        val cases = listOf(
            listOf<Any?>(
                mapOf(
                    "code" to "unknown_failure",
                    "summary" to "unknown failure",
                ),
            ) to "items[0].verification.diagnostics[0].code is not in the v3 enum",
            listOf<Any?>(
                mapOf("code" to "artifact_digest_mismatch"),
            ) to "items[0].verification.diagnostics[0].missing required key summary",
            listOf<Any?>(
                mapOf(
                    "code" to "artifact_digest_mismatch",
                    "summary" to "digest mismatch",
                    "detail" to "must stay closed",
                ),
            ) to "items[0].verification.diagnostics[0].unknown key detail",
            listOf<Any?>("legacy string") to
                "items[0].verification.diagnostics[0] must be an object",
            listOf<Any?>(
                mapOf(
                    "code" to "artifact_digest_mismatch",
                    "summary" to 42L,
                ),
            ) to "items[0].verification.diagnostics[0].summary must be a string",
        )

        cases.forEach { (diagnostics, expectedMessage) ->
            val result = DeviceApiValidators.validateSessionList(
                validV3SessionList(
                    items = listOf(
                        validSessionSummary(
                            verdict = "unusable",
                            diagnostics = diagnostics,
                        ),
                    ),
                ),
            )

            assertEquals(expectedMessage, assertIs<Validation.Invalid>(result).message)
        }
    }

    @Test
    fun `rejects legacy strings and extra fields in v3 diagnostic fixtures`() {
        val legacyString = DeviceApiValidators.validateSessionList(
            fixtureMap("session-list-v3-unusable-string-diagnostic.json", "invalid"),
        )
        val extraField = DeviceApiValidators.validateSessionList(
            fixtureMap("session-list-v3-unusable-extra-diagnostic-key.json", "invalid"),
        )

        assertEquals(
            "items[0].verification.diagnostics[0] must be an object",
            assertIs<Validation.Invalid>(legacyString).message,
        )
        assertEquals(
            "items[0].verification.diagnostics[0].unknown key detail",
            assertIs<Validation.Invalid>(extraField).message,
        )
    }

    @Test
    fun `enforces authoritative session presentation and verification metadata bounds`() {
        val longDisplayName = validSessionSummary().toMutableMap().apply {
            this["display_name"] = "x".repeat(161)
        }
        val invalidValidatorName = validSessionSummary().withVerificationMutation { verification ->
            val validator = verification.getValue("validator").objectCopy()
            validator["name"] = "x".repeat(129)
            verification["validator"] = validator
        }
        val invalidValidatorVersion = validSessionSummary().withVerificationMutation { verification ->
            val validator = verification.getValue("validator").objectCopy()
            validator["version"] = "x".repeat(65)
            verification["validator"] = validator
        }
        val invalidVerifiedAt = validSessionSummary().withVerificationMutation { verification ->
            verification["verified_at"] = "not-a-date"
        }

        val cases = listOf(
            longDisplayName to "items[0].display_name must contain 1..160 characters",
            invalidValidatorName to
                "items[0].verification.validator.name must contain 1..128 characters",
            invalidValidatorVersion to
                "items[0].verification.validator.version must contain 1..64 characters",
            invalidVerifiedAt to
                "items[0].verification.verified_at must be an RFC 3339 date-time",
        )
        cases.forEach { (summary, expectedMessage) ->
            val result = DeviceApiValidators.validateSessionList(
                validV3SessionList(items = listOf(summary)),
            )

            assertEquals(expectedMessage, assertIs<Validation.Invalid>(result).message)
        }
    }

    @Test
    fun `rejects v3 session list unknown keys and malformed catalog revisions`() {
        val unknownKey = DeviceApiValidators.validateSessionList(
            fixtureMap("session-list-v3-unknown-key.json", "invalid"),
        )
        val malformedRevision = DeviceApiValidators.validateSessionList(
            fixtureMap("session-list-v3-invalid-revision.json", "invalid"),
        )

        assertEquals("unknown key total", assertIs<Validation.Invalid>(unknownKey).message)
        assertEquals(
            "catalog_revision must be sha256:<64 lowercase hex>",
            assertIs<Validation.Invalid>(malformedRevision).message,
        )
    }

    @Test
    fun `rejects unknown session list discriminator`() {
        val result = DeviceApiValidators.validateSessionList(
            mapOf(
                "schema" to "ylx.session-list.v4",
                "catalog_revision" to
                    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "items" to emptyList<Any>(),
                "diagnostics" to emptyList<Any>(),
                "next_cursor" to null,
            ),
        )

        assertEquals(
            "schema must be ylx.session-list.v2 or ylx.session-list.v3",
            assertIs<Validation.Invalid>(result).message,
        )
    }

    @Test
    fun `accepts only the closed catalog changed error extension`() {
        val valid = DeviceApiValidators.validateCatalogChangedError(fixtureMap("catalog-changed.json"))
        val extraDetail = DeviceApiValidators.validateCatalogChangedError(
            fixtureMap("catalog-changed-extra-detail.json", "invalid"),
        )
        val nonRetryableRoot = fixtureMap("catalog-changed.json")
        @Suppress("UNCHECKED_CAST")
        val nonRetryableError = nonRetryableRoot.getValue("error") as Map<String, Any?>
        val nonRetryable = DeviceApiValidators.validateCatalogChangedError(
            nonRetryableRoot + ("error" to (nonRetryableError + ("retryable" to false))),
        )

        assertEquals(
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            assertIs<Validation.Valid<CatalogChangedError>>(valid).value.catalogRevision,
        )
        assertEquals(
            "error.details.unknown key previous_catalog_revision",
            assertIs<Validation.Invalid>(extraDetail).message,
        )
        assertEquals("error.retryable must be true", assertIs<Validation.Invalid>(nonRetryable).message)
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
    fun `accepts current split-eye recorded-audio manifest and collects segment artifacts`() {
        val result = DeviceApiValidators.validateDeviceSessionManifest(currentDeviceSessionManifest())

        val valid = assertIs<Validation.Valid<DeviceSessionManifest>>(result)
        assertEquals("test take", valid.value.displayName)
        assertEquals(
            listOf("imu.samples", "frames.index", "video.left", "video.right", "audio.wav"),
            valid.value.artifacts.map { it.role },
        )
    }

    @Test
    fun `rejects device session manifest artifact unsafe path`() {
        val manifest = currentDeviceSessionManifest()
        val imu = manifest.getValue("imu").objectCopy()
        imu["artifact"] = validArtifact("imu.samples", "../imu.ndjson", "application/x-ndjson", "a")
        manifest["imu"] = imu

        val result = DeviceApiValidators.validateDeviceSessionManifest(manifest)

        val invalid = assertIs<Validation.Invalid>(result)
        assertEquals("imu.artifact.path is not a safe relative artifact path", invalid.message)
    }

    @Test
    fun `accepts explicit not-recorded audio without inventing an artifact`() {
        val manifest = currentDeviceSessionManifest()
        manifest["audio"] = mapOf(
            "state" to "not_recorded",
            "requested_mode" to "disabled",
            "resolved_mode" to "disabled",
            "reason" to "user_disabled",
        )

        val result = DeviceApiValidators.validateDeviceSessionManifest(manifest)

        val valid = assertIs<Validation.Valid<DeviceSessionManifest>>(result)
        assertEquals(
            listOf("imu.samples", "frames.index", "video.left", "video.right"),
            valid.value.artifacts.map { it.role },
        )
    }

    @Test
    fun `rejects legacy raw side-by-side video in the current v4 manifest path`() {
        val manifest = currentDeviceSessionManifest()
        manifest["video"] = mapOf(
            "layout" to "raw-side-by-side",
            "codec" to "mjpeg",
            "continuous" to true,
            "artifact" to validArtifact(
                "video.raw-side-by-side",
                "video/raw.mjpeg",
                "video/x-motion-jpeg",
                "6",
            ),
        )

        val result = DeviceApiValidators.validateDeviceSessionManifest(manifest)

        assertEquals(
            "video.layout must be split-eyes",
            assertIs<Validation.Invalid>(result).message,
        )
    }

    @Test
    fun `rejects a single MP4 artifact instead of split-eye segment pairs`() {
        val manifest = currentDeviceSessionManifest()
        manifest["video"] = mapOf(
            "layout" to "split-eyes",
            "codec" to "h264",
            "container" to "mp4",
            "artifact" to validArtifact("video.left", "video/only.mp4", "video/mp4", "6"),
        )

        val result = DeviceApiValidators.validateDeviceSessionManifest(manifest)

        assertEquals(
            "video.missing required key segments",
            assertIs<Validation.Invalid>(result).message,
        )
    }

    @Test
    fun `rejects implicit empty audio state`() {
        val manifest = currentDeviceSessionManifest()
        manifest["audio"] = emptyMap<String, Any?>()

        val result = DeviceApiValidators.validateDeviceSessionManifest(manifest)

        assertEquals(
            "audio.state is required",
            assertIs<Validation.Invalid>(result).message,
        )
    }

    @Test
    fun `rejects a single WAV artifact instead of recorded audio segments`() {
        val manifest = currentDeviceSessionManifest()
        manifest["audio"] = mapOf(
            "state" to "recorded",
            "requested_mode" to "enabled",
            "resolved_mode" to "enabled",
            "codec" to "pcm_s16le",
            "container" to "wav",
            "sample_format" to "S16_LE",
            "sample_rate" to 48_000L,
            "channels" to 2L,
            "sample_count" to 480_000L,
            "sync" to mapOf(
                "time_base" to "host_monotonic",
                "start_time_seconds" to 0L,
                "end_time_seconds" to 10L,
                "video_time_reference" to "session_time_seconds",
            ),
            "artifact" to validArtifact("audio.wav", "audio/audio.wav", "audio/wav", "6"),
        )

        val result = DeviceApiValidators.validateDeviceSessionManifest(manifest)

        assertEquals(
            "audio.missing required key segments",
            assertIs<Validation.Invalid>(result).message,
        )
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
                "device_label" to "YLX-00ABCDEF",
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
                "session_list" to true,
                "session_detail" to true,
                "artifact_download" to true,
                "capture_status" to true,
                "session_deletion" to false,
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

    private fun validV3SessionList(
        items: List<Map<String, Any?>>,
        diagnostics: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?> {
        return mapOf(
            "schema" to "ylx.session-list.v3",
            "catalog_revision" to
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "items" to items,
            "diagnostics" to diagnostics,
            "next_cursor" to null,
        )
    }

    private fun validSessionSummary(
        verdict: String = "usable",
        diagnostics: List<Any?> = emptyList(),
        sessionId: String = "01991b70-7c88-7123-9234-123456789abc",
        takeId: String = "01991b70-7c88-7456-9234-123456789abc",
        startedAt: String = "2026-08-28T04:00:00Z",
    ): Map<String, Any?> {
        return mapOf(
            "session_id" to sessionId,
            "producer_outcome" to "sealed",
            "take_id" to takeId,
            "take_sequence" to 1L,
            "continuation_of" to null,
            "display_name" to "test take",
            "device" to mapOf(
                "device_id" to "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                "device_label" to "YLX-00ABCDEF",
            ),
            "started_at" to startedAt,
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
                "verdict" to verdict,
                "diagnostics" to diagnostics,
            ),
        )
    }

    private fun validSessionDiscoveryDiagnostic(): Map<String, Any?> {
        return mapOf(
            "quarantine_id" to "56005c52-31f1-4dac-91cd-d8eafd737d1c",
            "code" to "manifest_invalid",
            "observed_at" to "2026-08-28T04:00:00Z",
            "message" to "closed schema violation",
        )
    }

    private fun Map<String, Any?>.withVerificationMutation(
        mutate: (MutableMap<String, Any?>) -> Unit,
    ): Map<String, Any?> {
        val summary = toMutableMap()
        val verification = summary.getValue("verification").objectCopy()
        mutate(verification)
        summary["verification"] = verification
        return summary
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.objectCopy(): MutableMap<String, Any?> {
        return (this as Map<String, Any?>).toMutableMap()
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

    private fun currentDeviceSessionManifest(): MutableMap<String, Any?> {
        return fixtureMap("session-manifest-v2-recorded.json").toMutableMap()
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

    private fun validArtifact(
        role: String,
        path: String,
        mediaType: String,
        hex: String,
    ): Map<String, Any?> {
        val digest = hex.repeat(64)
        return mapOf(
            "artifact_id" to digest,
            "role" to role,
            "path" to path,
            "media_type" to mediaType,
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

    private fun fixtureMap(name: String, category: String = "valid"): Map<String, Any?> {
        val body = requireNotNull(
            javaClass.classLoader?.getResource("device-api/v4/$category/$name"),
        ) { "missing fixture $name" }.readText()
        return when (val result = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> result.value
            is DeviceJsonPayload.Result.Invalid -> error(result.message)
        }
    }
}
