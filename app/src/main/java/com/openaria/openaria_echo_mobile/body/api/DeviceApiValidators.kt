package com.openaria.openaria_echo_mobile.body.api

import java.time.OffsetDateTime

object DeviceApiValidators {
    fun validateDeviceDescriptor(value: Map<String, Any?>): Validation<DeviceDescriptor> {
        value.exactKeys(
            "schema",
            "device",
            "hardware_fingerprint",
            "api_version",
            "build",
            "security_profile",
            "capabilities",
            "storage",
            "runtime",
        )?.let { return it.invalid() }
        value.constString("schema", "ylx.device.v4")?.let { return it.invalid() }
        value.constString("api_version", "4.0")?.let { return it.invalid() }

        val device = value.objectAt("device") ?: return "device must be an object".invalid()
        device.exactKeys("device_id", "device_label")?.let { return "device.$it".invalid() }
        val deviceId = device.stringAt("device_id") ?: return "device.device_id is required".invalid()
        val deviceLabel = device.stringAt("device_label") ?: return "device.device_label is required".invalid()
        if (!isUuidV4(deviceId)) return "device.device_id must be a UUID v4".invalid()
        if (!deviceLabel.matches(Regex("^YLX-[0-9A-F]{8}$"))) {
            return "device.device_label must match YLX-<8 uppercase hex>".invalid()
        }

        val hardwareFingerprint = value.stringAt("hardware_fingerprint")
            ?: return "hardware_fingerprint is required".invalid()
        if (!hardwareFingerprint.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
            return "hardware_fingerprint must be sha256:<64 lowercase hex>".invalid()
        }

        val build = value.objectAt("build") ?: return "build must be an object".invalid()
        build.exactKeys("package_version", "commit", "build_id")?.let { return it.invalid() }
        val packageVersion = build.stringAt("package_version")
            ?: return "build.package_version is required".invalid()
        val commit = build.stringAt("commit") ?: return "build.commit is required".invalid()
        val buildId = build.stringAt("build_id") ?: return "build.build_id is required".invalid()
        if (packageVersion.length !in 1..64) {
            return "build.package_version must contain 1..64 characters".invalid()
        }
        if (!commit.matches(Regex("^[0-9a-f]{40,64}$"))) return "build.commit must be a hex Git commit".invalid()
        if (buildId.length !in 1..128) return "build.build_id must contain 1..128 characters".invalid()

        val securityProfile = value.stringAt("security_profile")
            ?: return "security_profile is required".invalid()
        if (securityProfile !in setOf("customer", "lab")) {
            return "security_profile must be customer or lab".invalid()
        }

        val capabilities = value.objectAt("capabilities") ?: return "capabilities must be an object".invalid()
        capabilities.onlyKnownKeys(
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
        )
            ?.let { return it.invalid() }
        val booleanCapabilities = listOf(
            "capture",
            "preview",
            "range_download",
            "network_mutation",
            "session_list",
            "session_detail",
            "artifact_download",
            "capture_status",
            "session_deletion",
        )
        booleanCapabilities.forEach { name ->
            if (name in capabilities && capabilities[name] !is Boolean) {
                return "capabilities.$name must be boolean".invalid()
            }
        }
        val capture = capabilities.booleanAt("capture") ?: false
        val preview = capabilities.booleanAt("preview") ?: false
        val rangeDownload = capabilities.booleanAt("range_download") ?: false
        val networkMutation = capabilities.booleanAt("network_mutation") ?: false
        val sessionList = capabilities.booleanAt("session_list") ?: false
        val sessionDetail = capabilities.booleanAt("session_detail") ?: false
        val artifactDownload = capabilities.booleanAt("artifact_download") ?: false
        val captureStatus = capabilities.booleanAt("capture_status") ?: false
        val sessionDeletion = capabilities.booleanAt("session_deletion") ?: false
        val calibrationCapture = if ("calibration_capture" in capabilities) {
            validateCalibrationCaptureCapability(
                capabilities.objectAt("calibration_capture")
                    ?: return "capabilities.calibration_capture must be an object".invalid(),
            ).valueOrReturn { return "capabilities.calibration_capture.$it".invalid() }
        } else {
            CalibrationCaptureCapability(
                supported = false,
                enabled = false,
                disabledReason = "capture_source_unsupported",
                requiredVideoLayout = "split-eyes",
            )
        }
        val storage = value.objectAt("storage") ?: return "storage must be an object".invalid()
        storage.exactKeys("volume_id", "total_bytes", "available_bytes", "writable")
            ?.let { return it.invalid() }
        val volumeId = storage.stringAt("volume_id") ?: return "storage.volume_id is required".invalid()
        if (!isUuidV4(volumeId)) return "storage.volume_id must be a UUID v4".invalid()
        val totalBytes = storage.longAt("total_bytes") ?: return "storage.total_bytes must be integer".invalid()
        val availableBytes = storage.longAt("available_bytes")
            ?: return "storage.available_bytes must be integer".invalid()
        val writable = storage.booleanAt("writable") ?: return "storage.writable must be boolean".invalid()
        if (totalBytes < 0 || availableBytes < 0) return "storage byte counts must be non-negative".invalid()

        val runtime = validateRuntime(value.objectAt("runtime") ?: return "runtime must be an object".invalid())
            .valueOrReturn { return it.invalid() }

        return Validation.Valid(
            DeviceDescriptor(
                deviceId = deviceId,
                deviceLabel = deviceLabel,
                hardwareFingerprint = hardwareFingerprint,
                packageVersion = packageVersion,
                commit = commit,
                buildId = buildId,
                securityProfile = securityProfile,
                captureCapable = capture,
                previewCapable = preview,
                rangeDownloadCapable = rangeDownload,
                networkMutationCapable = networkMutation,
                sessionListCapable = sessionList,
                sessionDetailCapable = sessionDetail,
                artifactDownloadCapable = artifactDownload,
                captureStatusCapable = captureStatus,
                sessionDeletionCapable = sessionDeletion,
                calibrationCapture = calibrationCapture,
                volumeId = volumeId,
                totalBytes = totalBytes,
                availableBytes = availableBytes,
                writable = writable,
                runtime = runtime,
            ),
        )
    }

    fun validateCaptureStatusSnapshot(value: Map<String, Any?>): Validation<CaptureStatusSnapshot> {
        value.exactKeys("schema", "authority_epoch", "source_revision", "snapshot")?.let { return it.invalid() }
        value.constString("schema", "ylx.capture-status.v4")?.let { return it.invalid() }
        val epoch = value.stringAt("authority_epoch") ?: return "authority_epoch is required".invalid()
        if (!isUuidV4(epoch)) return "authority_epoch must be a UUID v4".invalid()
        val revision = value.longAt("source_revision") ?: return "source_revision must be integer".invalid()
        if (revision < 0L) return "source_revision must be non-negative".invalid()

        val snapshot = value.objectAt("snapshot") ?: return "snapshot must be an object".invalid()
        snapshot.exactKeys("schema", "device_state", "active_recording", "retained_unsuccessful", "runtime")
            ?.let { return it.invalid() }
        snapshot.constString("schema", "ylx.capture-snapshot-event.v4")?.let { return it.invalid() }
        val deviceState = snapshot.stringAt("device_state") ?: return "snapshot.device_state is required".invalid()
        val validStates = setOf("idle", "recording", "finalizing", "encoding", "verifying", "blocked")
        if (deviceState !in validStates) return "snapshot.device_state is not in the v4 enum".invalid()
        val activeRecording = snapshot["active_recording"]
        val retainedUnsuccessful = snapshot["retained_unsuccessful"]
        if (deviceState in setOf("recording", "finalizing", "encoding", "verifying") &&
            activeRecording !is Map<*, *>
        ) {
            return "active device_state requires active_recording".invalid()
        }
        if (deviceState !in setOf("recording", "finalizing", "encoding", "verifying") &&
            activeRecording != null
        ) {
            return "inactive device_state requires active_recording to be null".invalid()
        }
        if (deviceState == "blocked" && retainedUnsuccessful != null) {
            return "blocked device_state requires retained_unsuccessful to be null".invalid()
        }
        val runtime = validateRuntime(snapshot.objectAt("runtime") ?: return "snapshot.runtime must be an object".invalid())
            .valueOrReturn { return it.invalid() }

        return Validation.Valid(
            CaptureStatusSnapshot(
                authorityEpoch = epoch,
                sourceRevision = revision,
                deviceState = deviceState,
                hasActiveRecording = activeRecording is Map<*, *>,
                runtime = runtime,
            ),
        )
    }

    fun validateCaptureEvent(value: Map<String, Any?>): Validation<CaptureEventPayload> {
        value.exactKeys(
            "schema",
            "sse_delivery_id",
            "authority_epoch",
            "source_revision",
            "type",
            "occurred_at",
            "session_id",
            "data",
        )?.let { return it.invalid() }
        value.constString("schema", "ylx.capture-event.v4")?.let { return it.invalid() }

        val deliveryId = value.stringAt("sse_delivery_id") ?: return "sse_delivery_id is required".invalid()
        if (!deliveryId.matches(Regex("^[0-9]+$"))) return "sse_delivery_id must be decimal digits".invalid()
        val epoch = value.stringAt("authority_epoch") ?: return "authority_epoch is required".invalid()
        if (!isUuidV4(epoch)) return "authority_epoch must be a UUID v4".invalid()
        val revision = value.longAt("source_revision") ?: return "source_revision must be integer".invalid()
        if (revision < 0L) return "source_revision must be non-negative".invalid()
        val type = value.stringAt("type") ?: return "type is required".invalid()
        if (type !in setOf("snapshot", "state", "progress", "diagnostic", "safe_swap")) {
            return "type is not in the v4 enum".invalid()
        }
        val occurredAt = value.stringAt("occurred_at") ?: return "occurred_at is required".invalid()
        if (occurredAt.isBlank()) return "occurred_at must be non-empty".invalid()
        val sessionId = value["session_id"]
        if (sessionId != null && (sessionId !is String || !isUuidV7(sessionId))) {
            return "session_id must be null or UUID v7".invalid()
        }
        val data = value.objectAt("data") ?: return "data must be an object".invalid()

        val snapshot = when (type) {
            "snapshot" -> {
                validateCaptureStatusSnapshot(
                    mapOf(
                        "schema" to "ylx.capture-status.v4",
                        "authority_epoch" to epoch,
                        "source_revision" to revision,
                        "snapshot" to data,
                    ),
                ).valueOrReturn { return "data.$it".invalid() }
            }
            else -> null
        }

        val safeSwapReceipt = when (type) {
            "safe_swap" -> validateSafeSwapReceiptResource(
                mapOf(
                    "schema" to "ylx.safe-swap-receipt-resource.v3",
                    "receipt" to data,
                ),
            ).valueOrReturn { return "data.$it".invalid() }.copy(
                authorityEpoch = epoch,
                sourceRevision = revision,
            )
            else -> null
        }

        when (type) {
            "state" -> validateCaptureStateEventData(data).valueOrReturn { return "data.$it".invalid() }
            "progress" -> validateCaptureProgressEventData(data).valueOrReturn { return "data.$it".invalid() }
            "diagnostic" -> validateCaptureDiagnosticEventData(data).valueOrReturn { return "data.$it".invalid() }
        }

        if (type in setOf("state", "progress", "safe_swap") && sessionId == null) {
            return "$type event requires session_id".invalid()
        }
        if (type == "safe_swap" && sessionId != safeSwapReceipt?.sessionId) {
            return "safe_swap session_id must match receipt.session_id".invalid()
        }
        if (type == "snapshot") {
            val dataDeviceState = data.stringAt("device_state")
            val retainedUnsuccessful = data["retained_unsuccessful"]
            if (dataDeviceState !in setOf("idle", "blocked") && sessionId == null) {
                return "active snapshot event requires session_id".invalid()
            }
            if (dataDeviceState == "idle" && retainedUnsuccessful != null && sessionId == null) {
                return "retained unsuccessful snapshot event requires session_id".invalid()
            }
        }

        return Validation.Valid(
            CaptureEventPayload(
                sseDeliveryId = deliveryId,
                authorityEpoch = epoch,
                sourceRevision = revision,
                type = type,
                occurredAt = occurredAt,
                sessionId = sessionId,
                snapshot = snapshot,
                safeSwapReceipt = safeSwapReceipt,
            ),
        )
    }

    fun validateSessionList(
        value: Map<String, Any?>,
        requestIdentity: SessionListRequestIdentity = SessionListRequestIdentity(
            limit = 200,
            cursor = null,
            takeId = null,
        ),
    ): Validation<SessionListPage> {
        if (requestIdentity.limit !in 1..200) return "request limit must be in 1..200".invalid()
        if (requestIdentity.cursor != null && requestIdentity.cursor.isBlank()) {
            return "request cursor must be null or non-empty".invalid()
        }
        if (requestIdentity.takeId != null && !isUuidV7(requestIdentity.takeId)) {
            return "request take_id must be null or UUID v7".invalid()
        }
        return when (value["schema"]) {
            "ylx.session-list.v2" -> validateSessionListV2(value, requestIdentity)
            "ylx.session-list.v3" -> validateSessionListV3(value, requestIdentity)
            else -> "schema must be ylx.session-list.v2 or ylx.session-list.v3".invalid()
        }
    }

    private fun validateSessionListV2(
        value: Map<String, Any?>,
        requestIdentity: SessionListRequestIdentity,
    ): Validation<SessionListPage> {
        value.exactKeys("schema", "items", "diagnostics", "next_cursor")?.let { return it.invalid() }
        if (requestIdentity.cursor != null) {
            return "ylx.session-list.v2 cannot satisfy a cursor request".invalid()
        }
        return validateSessionListContents(
            value = value,
            contract = SessionListContract.V2,
            catalogRevision = null,
            allowNextCursor = false,
            requestIdentity = requestIdentity,
        )
    }

    private fun validateSessionListV3(
        value: Map<String, Any?>,
        requestIdentity: SessionListRequestIdentity,
    ): Validation<SessionListPage> {
        value.exactKeys("schema", "catalog_revision", "items", "diagnostics", "next_cursor")
            ?.let { return it.invalid() }
        val catalogRevision = value.stringAt("catalog_revision")
            ?: return "catalog_revision is required".invalid()
        if (!isCatalogRevision(catalogRevision)) {
            return "catalog_revision must be sha256:<64 lowercase hex>".invalid()
        }
        return validateSessionListContents(
            value = value,
            contract = SessionListContract.V3,
            catalogRevision = catalogRevision,
            allowNextCursor = true,
            requestIdentity = requestIdentity,
        )
    }

    private fun validateSessionListContents(
        value: Map<String, Any?>,
        contract: SessionListContract,
        catalogRevision: String?,
        allowNextCursor: Boolean,
        requestIdentity: SessionListRequestIdentity,
    ): Validation<SessionListPage> {
        val rawItems = value["items"] as? List<*> ?: return "items must be an array".invalid()
        val rawDiagnostics = value["diagnostics"] as? List<*> ?: return "diagnostics must be an array".invalid()
        if (rawItems.size + rawDiagnostics.size > requestIdentity.limit) {
            return "items and diagnostics exceed the request limit".invalid()
        }
        val cursor = value["next_cursor"]
        if (cursor != null && (cursor !is String || cursor.isBlank())) {
            return "next_cursor must be null or a non-empty string".invalid()
        }
        val nextCursor = cursor
        if (contract == SessionListContract.V3 &&
            requestIdentity.cursor != null &&
            nextCursor == requestIdentity.cursor
        ) {
            return "next_cursor must advance beyond the request cursor".invalid()
        }

        val items = rawItems.mapIndexed { index, item ->
            val summary = item as? Map<*, *> ?: return "items[$index] must be an object".invalid()
            @Suppress("UNCHECKED_CAST")
            validateSessionSummary(
                value = summary as Map<String, Any?>,
                contract = contract,
                requestedTakeId = requestIdentity.takeId,
            )
                .valueOrReturn { return "items[$index].$it".invalid() }
        }
        if (items.map { it.sessionId }.toSet().size != items.size) {
            return "items must not repeat session_id".invalid()
        }
        if (!items.zipWithNext().all { (newer, older) -> isStrictlyNewer(newer, older) }) {
            return "items must be ordered newest-first by started_at and session_id".invalid()
        }
        val diagnostics = rawDiagnostics.mapIndexed { index, diagnostic ->
            val entry = diagnostic as? Map<*, *> ?: return "diagnostics[$index] must be an object".invalid()
            @Suppress("UNCHECKED_CAST")
            validateSessionDiscoveryDiagnostic(entry as Map<String, Any?>)
                .valueOrReturn { return "diagnostics[$index].$it".invalid() }
        }
        if (diagnostics.map { it.quarantineId }.toSet().size != diagnostics.size) {
            return "diagnostics must not repeat quarantine_id".invalid()
        }
        return Validation.Valid(
            SessionListPage(
                contract = contract,
                catalogRevision = catalogRevision,
                items = items,
                diagnostics = diagnostics,
                // v2 predates catalog-bound cursors. Accept its wire field for compatibility,
                // but never expose it as a requestable continuation.
                nextCursor = nextCursor.takeIf { allowNextCursor },
                requestIdentity = requestIdentity,
            ),
        )
    }

    fun validateCatalogChangedError(value: Map<String, Any?>): Validation<CatalogChangedError> {
        value.exactKeys("schema", "error")?.let { return it.invalid() }
        value.constString("schema", "ylx.api-error.v2")?.let { return it.invalid() }
        val error = value.objectAt("error") ?: return "error must be an object".invalid()
        error.exactKeys("code", "message", "request_id", "retryable", "details")
            ?.let { return "error.$it".invalid() }
        error.constString("code", "catalog_changed")?.let { return "error.$it".invalid() }
        val message = error.stringAt("message") ?: return "error.message is required".invalid()
        if (message.isBlank() || message.length > 1024) {
            return "error.message must contain 1..1024 characters".invalid()
        }
        val requestId = error.stringAt("request_id") ?: return "error.request_id is required".invalid()
        if (!isUuid(requestId)) return "error.request_id must be a UUID".invalid()
        if (error["retryable"] != true) return "error.retryable must be true".invalid()
        val details = error.objectAt("details") ?: return "error.details must be an object".invalid()
        details.exactKeys("catalog_revision")?.let { return "error.details.$it".invalid() }
        val catalogRevision = details.stringAt("catalog_revision")
            ?: return "error.details.catalog_revision is required".invalid()
        if (!isCatalogRevision(catalogRevision)) {
            return "error.details.catalog_revision must be sha256:<64 lowercase hex>".invalid()
        }
        return Validation.Valid(CatalogChangedError(catalogRevision))
    }

    fun validateErrorResponse(value: Map<String, Any?>): Validation<ApiError> {
        value.exactKeys("schema", "error")?.let { return it.invalid() }
        value.constString("schema", "ylx.api-error.v2")?.let { return it.invalid() }
        val error = value.objectAt("error") ?: return "error must be an object".invalid()
        val allowed = setOf("code", "message", "request_id", "retryable", "details")
        error.keys.firstOrNull { it !in allowed }?.let { return "error has unknown key $it".invalid() }
        val code = error.stringAt("code") ?: return "error.code is required".invalid()
        val message = error.stringAt("message") ?: return "error.message is required".invalid()
        val requestId = error.stringAt("request_id") ?: return "error.request_id is required".invalid()
        val retryable = error.booleanAt("retryable") ?: return "error.retryable must be boolean".invalid()
        if (!code.matches(Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$"))) {
            return "error.code is not a stable v4 error code".invalid()
        }
        if (message.isBlank()) return "error.message must be non-empty".invalid()
        if (!isUuid(requestId)) return "error.request_id must be a UUID".invalid()
        return Validation.Valid(ApiError(code, message, requestId, retryable))
    }

    fun validateSafeSwapReceiptResource(value: Map<String, Any?>): Validation<SafeSwapReceiptSummary> {
        value.exactKeys("schema", "receipt")?.let { return it.invalid() }
        value.constString("schema", "ylx.safe-swap-receipt-resource.v3")?.let { return it.invalid() }
        val receipt = value.objectAt("receipt") ?: return "receipt must be an object".invalid()
        receipt.exactKeys(
            "schema",
            "session_id",
            "volume_id",
            "generation_id",
            "manifest_id",
            "manifest_sha256",
            "sealed_at",
            "released_at",
            "release_state",
            "open_handle_count",
        )?.let { return "receipt.$it".invalid() }
        receipt.constString("schema", "ylx.safe-swap-receipt.v3")?.let { return "receipt.$it".invalid() }
        val sessionId = receipt.stringAt("session_id") ?: return "receipt.session_id is required".invalid()
        val volumeId = receipt.stringAt("volume_id") ?: return "receipt.volume_id is required".invalid()
        val generationId = receipt.stringAt("generation_id") ?: return "receipt.generation_id is required".invalid()
        val manifestId = receipt.stringAt("manifest_id") ?: return "receipt.manifest_id is required".invalid()
        val manifestSha256 = receipt.stringAt("manifest_sha256") ?: return "receipt.manifest_sha256 is required".invalid()
        val sealedAt = receipt.stringAt("sealed_at") ?: return "receipt.sealed_at is required".invalid()
        val releasedAt = receipt.stringAt("released_at") ?: return "receipt.released_at is required".invalid()
        val releaseState = receipt.stringAt("release_state") ?: return "receipt.release_state is required".invalid()
        val openHandleCount = receipt.longAt("open_handle_count")
            ?: return "receipt.open_handle_count must be 0".invalid()
        if (!isUuidV7(sessionId)) return "receipt.session_id must be a UUID v7".invalid()
        if (!isUuidV4(volumeId)) return "receipt.volume_id must be a UUID v4".invalid()
        if (!isUuidV4(generationId)) return "receipt.generation_id must be a UUID v4".invalid()
        if (!isUuidV7(manifestId)) return "receipt.manifest_id must be a UUID v7".invalid()
        if (!isSha256(manifestSha256)) return "receipt.manifest_sha256 must be SHA-256".invalid()
        if (sealedAt.isBlank() || releasedAt.isBlank()) return "receipt timestamps must be non-empty".invalid()
        if (releaseState !in setOf("unmounted", "device-released")) {
            return "receipt.release_state is not in the v3 enum".invalid()
        }
        if (openHandleCount != 0L) {
            return "receipt.open_handle_count must be 0".invalid()
        }
        return Validation.Valid(
            SafeSwapReceiptSummary(
                sessionId = sessionId,
                volumeId = volumeId,
                generationId = generationId,
                manifestId = manifestId,
                manifestSha256 = manifestSha256,
                sealedAt = sealedAt,
                releasedAt = releasedAt,
                releaseState = releaseState,
                openHandleCount = openHandleCount,
            ),
        )
    }

    fun validateCameraFocusStatus(value: Map<String, Any?>): Validation<CameraFocusStatus> {
        value.exactKeys(
            "schema",
            "value",
            "minimum",
            "maximum",
            "step",
            "default",
            "auto_supported",
            "auto_enabled",
        )?.let { return it.invalid() }
        value.constString("schema", "ylx.camera-focus.v1")?.let { return it.invalid() }
        val focusValue = value.longAt("value") ?: return "value must be integer".invalid()
        val minimum = value.longAt("minimum") ?: return "minimum must be integer".invalid()
        val maximum = value.longAt("maximum") ?: return "maximum must be integer".invalid()
        val step = value.longAt("step") ?: return "step must be integer".invalid()
        val default = value.longAt("default") ?: return "default must be integer".invalid()
        val autoSupported = value.booleanAt("auto_supported") ?: return "auto_supported must be boolean".invalid()
        val autoEnabled = value["auto_enabled"]
        if (autoEnabled != null && autoEnabled !is Boolean) {
            return "auto_enabled must be boolean or null".invalid()
        }
        if (minimum < 0L || maximum < 0L || focusValue < 0L || default < 0L) {
            return "focus values must be non-negative".invalid()
        }
        if (step < 1L) return "step must be positive".invalid()
        if (maximum < minimum) return "maximum must be >= minimum".invalid()
        if (focusValue !in minimum..maximum) return "value must be inside minimum..maximum".invalid()
        if (default !in minimum..maximum) return "default must be inside minimum..maximum".invalid()
        if (!autoSupported && autoEnabled != null) {
            return "auto_enabled must be null when auto is unsupported".invalid()
        }
        return Validation.Valid(
            CameraFocusStatus(
                value = focusValue,
                minimum = minimum,
                maximum = maximum,
                step = step,
                default = default,
                autoSupported = autoSupported,
                autoEnabled = autoEnabled,
            ),
        )
    }

    fun validateDeviceSessionManifest(value: Map<String, Any?>): Validation<DeviceSessionManifest> {
        value.exactKeys(
            "schema",
            "manifest_id",
            "sealed",
            "sealed_at",
            "session_id",
            "volume_id",
            "capture_mode",
            "display_name",
            "device",
            "time",
            "take",
            "camera",
            "video",
            "imu",
            "frames",
            "audio",
            "logs",
            "integrity",
        )?.let { return it.invalid() }
        value.constString("schema", "ylx.device-session.v2")?.let { return it.invalid() }
        if (value["sealed"] != true) return "sealed must be true".invalid()
        val manifestId = value.stringAt("manifest_id") ?: return "manifest_id is required".invalid()
        val sessionId = value.stringAt("session_id") ?: return "session_id is required".invalid()
        val volumeId = value.stringAt("volume_id") ?: return "volume_id is required".invalid()
        if (!isUuidV7(manifestId)) return "manifest_id must be a UUID v7".invalid()
        if (!isUuidV7(sessionId)) return "session_id must be a UUID v7".invalid()
        if (!isUuidV4(volumeId)) return "volume_id must be a UUID v4".invalid()
        val sealedAt = value.stringAt("sealed_at") ?: return "sealed_at is required".invalid()
        val displayName = value.stringAt("display_name") ?: return "display_name is required".invalid()
        val captureMode = value.stringAt("capture_mode") ?: return "capture_mode is required".invalid()
        if (sealedAt.isBlank()) return "sealed_at must be non-empty".invalid()
        if (displayName.isBlank()) return "display_name must be non-empty".invalid()
        if (captureMode !in setOf("production", "calibration")) return "capture_mode is not in the v2 enum".invalid()

        val artifacts = collectSessionArtifacts(value).valueOrReturn { return it.invalid() }
        val duplicates = artifacts.groupBy { it.artifactId }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) return "duplicate artifact_id ${duplicates.first()}".invalid()

        return Validation.Valid(
            DeviceSessionManifest(
                manifestId = manifestId,
                sessionId = sessionId,
                displayName = displayName,
                sealedAt = sealedAt,
                captureMode = captureMode,
                artifacts = artifacts,
            ),
        )
    }

    fun validateRetainedUnsuccessfulSessionResource(
        value: Map<String, Any?>,
    ): Validation<RetainedUnsuccessfulOutcome> {
        value.exactKeys("schema", "authority_epoch", "source_revision", "outcome")?.let { return it.invalid() }
        value.constString("schema", "ylx.retained-unsuccessful-session-resource.v2")?.let { return it.invalid() }
        val epoch = value.stringAt("authority_epoch") ?: return "authority_epoch is required".invalid()
        if (!isUuidV4(epoch)) return "authority_epoch must be a UUID v4".invalid()
        val revision = value.longAt("source_revision") ?: return "source_revision must be integer".invalid()
        if (revision < 0L) return "source_revision must be non-negative".invalid()
        val outcome = value.objectAt("outcome") ?: return "outcome must be an object".invalid()
        val generationId = outcome.stringAt("generation_id") ?: return "outcome.generation_id is required".invalid()
        if (!isUuidV4(generationId)) return "outcome.generation_id must be a UUID v4".invalid()
        val recordingState = outcome.objectAt("recording_state")
            ?: return "outcome.recording_state must be an object".invalid()
        val state = recordingState.stringAt("state") ?: return "outcome.recording_state.state is required".invalid()
        if (state !in setOf("recoverable", "failed", "abandoned")) {
            return "outcome.recording_state.state must be an unsuccessful terminal state".invalid()
        }
        recordingState.stringAt("authority_epoch")?.let { stateEpoch ->
            if (stateEpoch != epoch) {
                return "outcome.recording_state.authority_epoch must match authority_epoch".invalid()
            }
        }
        recordingState.longAt("state_revision")?.let { stateRevision ->
            if (stateRevision != revision) {
                return "outcome.recording_state.state_revision must match source_revision".invalid()
            }
        }

        return Validation.Valid(
            RetainedUnsuccessfulOutcome(
                authorityEpoch = epoch,
                sourceRevision = revision,
                generationId = generationId,
                state = state,
            ),
        )
    }

    fun validateNetworkStatus(value: Map<String, Any?>): Validation<NetworkStatus> {
        value.exactKeys(
            "schema",
            "authority_epoch",
            "source_revision",
            "observed_at",
            "saved",
            "verified",
            "desired",
            "observed",
            "transaction",
            "mutation_capability",
            "concurrency_capability",
        )?.let { return it.invalid() }
        value.constString("schema", "ylx.network-status.v1")?.let { return it.invalid() }
        val epoch = value.stringAt("authority_epoch") ?: return "authority_epoch is required".invalid()
        if (!isUuidV4(epoch)) return "authority_epoch must be a UUID v4".invalid()
        val revision = value.longAt("source_revision") ?: return "source_revision must be integer".invalid()
        if (revision < 0L) return "source_revision must be non-negative".invalid()
        val observedAt = value.stringAt("observed_at") ?: return "observed_at is required".invalid()
        if (observedAt.isBlank()) return "observed_at must be non-empty".invalid()
        val saved = value.booleanAt("saved") ?: return "saved must be boolean".invalid()
        val verified = value.booleanAt("verified") ?: return "verified must be boolean".invalid()
        if (verified && !saved) return "verified=true requires saved=true".invalid()
        val desired = validateNetworkDesiredState(
            value.objectAt("desired") ?: return "desired must be an object".invalid(),
        ).valueOrReturn { return "desired.$it".invalid() }
        val observed = validateNetworkObservedState(
            value.objectAt("observed") ?: return "observed must be an object".invalid(),
        ).valueOrReturn { return "observed.$it".invalid() }
        val transaction = validateNetworkTransactionWindow(
            value.objectAt("transaction") ?: return "transaction must be an object".invalid(),
        ).valueOrReturn { return "transaction.$it".invalid() }
        val mutationCapability = validateNetworkMutationCapability(
            value.objectAt("mutation_capability") ?: return "mutation_capability must be an object".invalid(),
        ).valueOrReturn { return "mutation_capability.$it".invalid() }
        val concurrencyCapability = validateNetworkConcurrencyCapability(
            value.objectAt("concurrency_capability") ?: return "concurrency_capability must be an object".invalid(),
        ).valueOrReturn { return "concurrency_capability.$it".invalid() }

        return Validation.Valid(
            NetworkStatus(
                authorityEpoch = epoch,
                sourceRevision = revision,
                observedAt = observedAt,
                saved = saved,
                verified = verified,
                desired = desired,
                observed = observed,
                transaction = transaction,
                mutationCapability = mutationCapability,
                concurrencyCapability = concurrencyCapability,
            ),
        )
    }

    fun validateNetworkScan(value: Map<String, Any?>): Validation<NetworkScanSnapshot> {
        value.exactKeys("schema", "authority_epoch", "source_revision", "scanned_at", "networks")
            ?.let { return it.invalid() }
        value.constString("schema", "ylx.network-scan.v1")?.let { return it.invalid() }
        val epoch = value.stringAt("authority_epoch") ?: return "authority_epoch is required".invalid()
        if (!isUuidV4(epoch)) return "authority_epoch must be a UUID v4".invalid()
        val revision = value.longAt("source_revision") ?: return "source_revision must be integer".invalid()
        if (revision < 0L) return "source_revision must be non-negative".invalid()
        val scannedAt = value.stringAt("scanned_at") ?: return "scanned_at is required".invalid()
        if (scannedAt.isBlank()) return "scanned_at must be non-empty".invalid()
        val rawNetworks = value["networks"] as? List<*> ?: return "networks must be an array".invalid()
        if (rawNetworks.size > 256) return "networks must contain at most 256 entries".invalid()
        val networks = rawNetworks.mapIndexed { index, entry ->
            val network = entry as? Map<*, *> ?: return "networks[$index] must be an object".invalid()
            @Suppress("UNCHECKED_CAST")
            validateNetworkScanEntry(network as Map<String, Any?>)
                .valueOrReturn { return "networks[$index].$it".invalid() }
        }
        return Validation.Valid(NetworkScanSnapshot(epoch, revision, scannedAt, networks))
    }

    fun validateNetworkCredentialReceipt(value: Map<String, Any?>): Validation<NetworkCredentialReceipt> {
        value.exactKeys("schema", "credential_ref", "issued_at", "expires_at", "ttl_seconds", "single_use")
            ?.let { return it.invalid() }
        value.constString("schema", "ylx.network-credential-receipt.v1")?.let { return it.invalid() }
        val credentialRef = value.stringAt("credential_ref") ?: return "credential_ref is required".invalid()
        if (!isCredentialRef(credentialRef)) return "credential_ref is invalid".invalid()
        val issuedAt = value.stringAt("issued_at") ?: return "issued_at is required".invalid()
        val expiresAt = value.stringAt("expires_at") ?: return "expires_at is required".invalid()
        if (issuedAt.isBlank() || expiresAt.isBlank()) return "credential timestamps must be non-empty".invalid()
        val ttlSeconds = value.longAt("ttl_seconds") ?: return "ttl_seconds must be integer".invalid()
        if (ttlSeconds !in 1L..120L) return "ttl_seconds must be in 1..120".invalid()
        if (value["single_use"] != true) return "single_use must be true".invalid()
        return Validation.Valid(NetworkCredentialReceipt(credentialRef, issuedAt, expiresAt, ttlSeconds, true))
    }

    fun validateNetworkTransactionReceipt(value: Map<String, Any?>): Validation<NetworkTransactionReceipt> {
        value.exactKeys("schema", "accepted_at", "transaction")?.let { return it.invalid() }
        value.constString("schema", "ylx.network-transaction-receipt.v1")?.let { return it.invalid() }
        val acceptedAt = value.stringAt("accepted_at") ?: return "accepted_at is required".invalid()
        if (acceptedAt.isBlank()) return "accepted_at must be non-empty".invalid()
        val transaction = validateNetworkTransaction(
            value.objectAt("transaction") ?: return "transaction must be an object".invalid(),
        ).valueOrReturn { return "transaction.$it".invalid() }
        return Validation.Valid(NetworkTransactionReceipt(acceptedAt, transaction))
    }

    fun validateNetworkEvent(value: Map<String, Any?>): Validation<NetworkEventPayload> {
        value.exactKeys(
            "schema",
            "sse_delivery_id",
            "authority_epoch",
            "source_revision",
            "occurred_at",
            "type",
            "transaction_id",
            "data",
        )?.let { return it.invalid() }
        value.constString("schema", "ylx.network-event.v1")?.let { return it.invalid() }
        val deliveryId = value.stringAt("sse_delivery_id") ?: return "sse_delivery_id is required".invalid()
        if (!deliveryId.matches(Regex("^[0-9]+$"))) return "sse_delivery_id must be decimal digits".invalid()
        val epoch = value.stringAt("authority_epoch") ?: return "authority_epoch is required".invalid()
        if (!isUuidV4(epoch)) return "authority_epoch must be a UUID v4".invalid()
        val revision = value.longAt("source_revision") ?: return "source_revision must be integer".invalid()
        if (revision < 0L) return "source_revision must be non-negative".invalid()
        val occurredAt = value.stringAt("occurred_at") ?: return "occurred_at is required".invalid()
        if (occurredAt.isBlank()) return "occurred_at must be non-empty".invalid()
        val type = value.stringAt("type") ?: return "type is required".invalid()
        if (type !in setOf("snapshot", "transaction")) return "type is not in the v1 enum".invalid()
        val rawTransactionId = value["transaction_id"]
        if (rawTransactionId != null && (rawTransactionId !is String || !isUuidV7(rawTransactionId))) {
            return "transaction_id must be null or UUID v7".invalid()
        }
        val data = value.objectAt("data") ?: return "data must be an object".invalid()
        val snapshot = if (type == "snapshot") {
            if (rawTransactionId != null) return "snapshot event requires transaction_id=null".invalid()
            validateNetworkStatus(data).valueOrReturn { return "data.$it".invalid() }
        } else {
            null
        }
        val transaction = if (type == "transaction") {
            val transactionId = rawTransactionId ?: return "transaction event requires transaction_id".invalid()
            validateNetworkTransaction(data).valueOrReturn { return "data.$it".invalid() }.also {
                if (it.transactionId != transactionId) {
                    return "transaction_id must match data.transaction_id".invalid()
                }
            }
        } else {
            null
        }
        return Validation.Valid(
            NetworkEventPayload(
                sseDeliveryId = deliveryId,
                authorityEpoch = epoch,
                sourceRevision = revision,
                occurredAt = occurredAt,
                type = type,
                transactionId = rawTransactionId,
                status = snapshot,
                transaction = transaction,
            ),
        )
    }

    private fun validateCalibrationCaptureCapability(
        value: Map<String, Any?>,
    ): Validation<CalibrationCaptureCapability> {
        value.exactKeys("supported", "enabled", "disabled_reason", "required_video_layout")
            ?.let { return it.invalid() }
        val supported = value.booleanAt("supported") ?: return "supported must be boolean".invalid()
        val enabled = value.booleanAt("enabled") ?: return "enabled must be boolean".invalid()
        val disabledReason = value["disabled_reason"]
        if (disabledReason != null && disabledReason !is String) {
            return "disabled_reason must be string or null".invalid()
        }
        val reason = disabledReason
        val validReasons = setOf(
            "capture_source_unsupported",
            "storage_unavailable",
            "hardware_unavailable",
            "maintenance_or_capture_busy",
        )
        if (reason != null && reason !in validReasons) return "disabled_reason is not in the v4 enum".invalid()
        if (enabled && !supported) return "enabled=true requires supported=true".invalid()
        if (enabled && reason != null) return "enabled=true requires disabled_reason=null".invalid()
        if (!enabled && reason == null) return "enabled=false requires disabled_reason".invalid()
        if (value["required_video_layout"] != "split-eyes") {
            return "required_video_layout must be split-eyes".invalid()
        }
        return Validation.Valid(CalibrationCaptureCapability(supported, enabled, reason, "split-eyes"))
    }

    private fun validateRuntime(value: Map<String, Any?>): Validation<DeviceRuntime> {
        value.exactKeys(
            "observed_at",
            "connection_method",
            "temperature_celsius",
            "network",
            "live_imu",
            "camera",
            "camera_focus",
        )?.let { return it.invalid() }
        val observedAt = value.stringAt("observed_at") ?: return "runtime.observed_at is required".invalid()
        val method = value.stringAt("connection_method") ?: return "runtime.connection_method is required".invalid()
        val temperature = value.numberAt("temperature_celsius")
            ?: return "runtime.temperature_celsius must be number".invalid()
        if (parseDateTime(observedAt) == null) {
            return "runtime.observed_at must be an RFC 3339 date-time".invalid()
        }
        if (method !in setOf("wifi_ap", "wifi_client", "ethernet_direct", "ethernet_lan", "offline")) {
            return "runtime.connection_method is not in the v4 enum".invalid()
        }
        if (temperature !in -40.0..125.0) {
            return "runtime.temperature_celsius must be in -40..125".invalid()
        }
        val network = validateNetworkRuntimeStatus(
            value.objectAt("network") ?: return "runtime.network must be an object".invalid(),
        ).valueOrReturn { return "runtime.network.$it".invalid() }
        if (!value.containsKey("live_imu")) return "runtime.live_imu is required".invalid()
        val liveImuQuality = validateLiveImuObservation(value["live_imu"])
            .valueOrReturn { return "runtime.live_imu.$it".invalid() }
        val camera = validateCameraConnectionStatus(
            value.objectAt("camera") ?: return "runtime.camera must be an object".invalid(),
        ).valueOrReturn { return "runtime.camera.$it".invalid() }
        if (!value.containsKey("camera_focus")) return "runtime.camera_focus is required".invalid()
        if (value["camera_focus"] != null) {
            val cameraFocus = value.objectAt("camera_focus")
                ?: return "runtime.camera_focus must be an object or null".invalid()
            validateCameraFocusStatus(cameraFocus)
                .valueOrReturn { return "runtime.camera_focus.$it".invalid() }
        }
        return Validation.Valid(DeviceRuntime(observedAt, method, temperature, network, liveImuQuality, camera))
    }

    private fun validateCameraConnectionStatus(value: Map<String, Any?>): Validation<CameraConnectionStatus> {
        value.exactKeys("schema", "state")?.let { return it.invalid() }
        value.constString("schema", "ylx.camera-connection.v1")?.let { return it.invalid() }
        val state = value.stringAt("state") ?: return "state is required".invalid()
        if (state !in setOf("connected", "disconnected")) {
            return "state is not in the v1 enum".invalid()
        }
        return Validation.Valid(CameraConnectionStatus(state))
    }

    private fun validateNetworkDesiredState(value: Map<String, Any?>): Validation<NetworkDesiredState> {
        value.exactKeys("mode", "wifi_client", "ethernet")?.let { return it.invalid() }
        val mode = value.stringAt("mode") ?: return "mode is required".invalid()
        if (mode !in setOf("hotspot", "wifi-client", "ethernet-dhcp", "ethernet-static")) {
            return "mode is not in the v1 enum".invalid()
        }
        val wifiClient = when (val raw = value["wifi_client"]) {
            null -> null
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                validateNetworkDesiredWifiClient(raw as Map<String, Any?>)
                    .valueOrReturn { return "wifi_client.$it".invalid() }
            }
            else -> return "wifi_client must be object or null".invalid()
        }
        val ethernet = when (val raw = value["ethernet"]) {
            null -> null
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                validateNetworkDesiredEthernet(raw as Map<String, Any?>)
                    .valueOrReturn { return "ethernet.$it".invalid() }
            }
            else -> return "ethernet must be object or null".invalid()
        }
        when (mode) {
            "hotspot" -> {
                if (wifiClient != null) return "hotspot mode requires wifi_client=null".invalid()
                if (ethernet != null) return "hotspot mode requires ethernet=null".invalid()
            }
            "wifi-client" -> {
                if (wifiClient == null) return "wifi-client mode requires wifi_client".invalid()
                if (ethernet != null) return "wifi-client mode requires ethernet=null".invalid()
            }
            "ethernet-dhcp", "ethernet-static" -> {
                if (wifiClient != null) return "$mode mode requires wifi_client=null".invalid()
                if (ethernet == null) return "$mode mode requires ethernet".invalid()
                if (mode == "ethernet-dhcp" && ethernet.addressing != "dhcp") {
                    return "ethernet-dhcp mode requires addressing=dhcp".invalid()
                }
                if (mode == "ethernet-static" && ethernet.addressing != "static") {
                    return "ethernet-static mode requires addressing=static".invalid()
                }
            }
        }
        return Validation.Valid(NetworkDesiredState(mode, wifiClient, ethernet))
    }

    private fun validateNetworkDesiredWifiClient(value: Map<String, Any?>): Validation<NetworkDesiredWifiClient> {
        value.exactKeys("ssid", "security", "credential_state")?.let { return it.invalid() }
        val ssid = value.stringAt("ssid") ?: return "ssid is required".invalid()
        if (!isValidSsid(ssid)) return "ssid must be 1..32 UTF-8 bytes".invalid()
        val security = value.stringAt("security") ?: return "security is required".invalid()
        if (!isNetworkWifiSecurity(security)) return "security is not in the v1 enum".invalid()
        val credentialState = value.stringAt("credential_state") ?: return "credential_state is required".invalid()
        if (credentialState !in setOf("absent", "pending_input", "stored")) {
            return "credential_state is not in the v1 enum".invalid()
        }
        if (security == "open" && credentialState != "absent") {
            return "open wifi_client requires credential_state=absent".invalid()
        }
        return Validation.Valid(NetworkDesiredWifiClient(ssid, security, credentialState))
    }

    private fun validateNetworkDesiredEthernet(value: Map<String, Any?>): Validation<NetworkDesiredEthernet> {
        value.exactKeys("addressing", "static_ipv4")?.let { return it.invalid() }
        val addressing = value.stringAt("addressing") ?: return "addressing is required".invalid()
        if (addressing !in setOf("dhcp", "static")) return "addressing is not in the v1 enum".invalid()
        val staticIpv4 = when (val raw = value["static_ipv4"]) {
            null -> null
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                validateNetworkStaticIpv4(raw as Map<String, Any?>)
                    .valueOrReturn { return "static_ipv4.$it".invalid() }
            }
            else -> return "static_ipv4 must be object or null".invalid()
        }
        if (addressing == "dhcp" && staticIpv4 != null) return "dhcp addressing requires static_ipv4=null".invalid()
        if (addressing == "static" && staticIpv4 == null) return "static addressing requires static_ipv4".invalid()
        return Validation.Valid(NetworkDesiredEthernet(addressing, staticIpv4))
    }

    private fun validateNetworkStaticIpv4(value: Map<String, Any?>): Validation<NetworkStaticIpv4> {
        value.exactKeys("address", "prefix_length", "gateway", "dns")?.let { return it.invalid() }
        val address = value.stringAt("address") ?: return "address is required".invalid()
        if (!isIpv4(address)) return "address must be IPv4".invalid()
        val prefixLength = value.longAt("prefix_length") ?: return "prefix_length must be integer".invalid()
        if (prefixLength !in 1L..32L) return "prefix_length must be in 1..32".invalid()
        val gateway = value["gateway"]
        if (gateway != null && (gateway !is String || !isIpv4(gateway))) {
            return "gateway must be null or IPv4".invalid()
        }
        val dns = value["dns"] as? List<*> ?: return "dns must be an array".invalid()
        if (dns.size > 3) return "dns must contain at most 3 entries".invalid()
        val dnsValues = dns.mapIndexed { index, entry ->
            val server = entry as? String ?: return "dns[$index] must be string".invalid()
            if (!isIpv4(server)) return "dns[$index] must be IPv4".invalid()
            server
        }
        if (dnsValues.toSet().size != dnsValues.size) return "dns must be unique".invalid()
        return Validation.Valid(NetworkStaticIpv4(address, prefixLength, gateway, dnsValues))
    }

    private fun validateNetworkObservedState(value: Map<String, Any?>): Validation<NetworkObservedState> {
        value.exactKeys("ap", "wifi_client", "wired", "default_route", "mdns", "devices")
            ?.let { return it.invalid() }
        val ap = validateNetworkInterfaceStatus(
            value.objectAt("ap") ?: return "ap must be an object".invalid(),
        ).valueOrReturn { return "ap.$it".invalid() }
        val wifiClient = validateNetworkInterfaceStatus(
            value.objectAt("wifi_client") ?: return "wifi_client must be an object".invalid(),
        ).valueOrReturn { return "wifi_client.$it".invalid() }
        val wired = validateNetworkInterfaceStatus(
            value.objectAt("wired") ?: return "wired must be an object".invalid(),
        ).valueOrReturn { return "wired.$it".invalid() }
        val defaultRoute = value.stringAt("default_route") ?: return "default_route is required".invalid()
        if (defaultRoute !in setOf("wifi_client", "wired", "none")) {
            return "default_route is not in the v1 enum".invalid()
        }
        val mdns = validateNetworkMdnsStatus(
            value.objectAt("mdns") ?: return "mdns must be an object".invalid(),
        ).valueOrReturn { return "mdns.$it".invalid() }
        val rawDevices = value["devices"] as? List<*> ?: return "devices must be an array".invalid()
        if (rawDevices.size > 64) return "devices must contain at most 64 entries".invalid()
        val devices = rawDevices.mapIndexed { index, raw ->
            val device = raw as? Map<*, *> ?: return "devices[$index] must be an object".invalid()
            @Suppress("UNCHECKED_CAST")
            validateNetworkDeviceStatus(device as Map<String, Any?>)
                .valueOrReturn { return "devices[$index].$it".invalid() }
        }
        return Validation.Valid(NetworkObservedState(ap, wifiClient, wired, defaultRoute, mdns, devices))
    }

    private fun validateNetworkMdnsStatus(value: Map<String, Any?>): Validation<NetworkMdnsStatus> {
        value.exactKeys("hostname", "service", "aliases", "port")?.let { return it.invalid() }
        val hostname = value.stringAt("hostname") ?: return "hostname is required".invalid()
        if (!hostname.matches(Regex("^[A-Za-z0-9_.-]{1,122}\\.local$"))) {
            return "hostname must be a .local name".invalid()
        }
        val service = value.stringAt("service") ?: return "service is required".invalid()
        if (!isMdnsServiceName(service)) return "service is invalid".invalid()
        val aliases = value["aliases"] as? List<*> ?: return "aliases must be an array".invalid()
        if (aliases.size > 16) return "aliases must contain at most 16 entries".invalid()
        val aliasValues = aliases.mapIndexed { index, alias ->
            val serviceAlias = alias as? String ?: return "aliases[$index] must be string".invalid()
            if (!isMdnsServiceName(serviceAlias)) return "aliases[$index] is invalid".invalid()
            serviceAlias
        }
        if (aliasValues.toSet().size != aliasValues.size) return "aliases must be unique".invalid()
        val port = value.longAt("port") ?: return "port must be integer".invalid()
        if (port !in 1L..65535L) return "port must be in 1..65535".invalid()
        return Validation.Valid(NetworkMdnsStatus(hostname, service, aliasValues, port))
    }

    private fun validateNetworkDeviceStatus(value: Map<String, Any?>): Validation<NetworkDeviceStatus> {
        value.exactKeys("interface", "type", "state")?.let { return it.invalid() }
        val interfaceName = value.stringAt("interface") ?: return "interface is required".invalid()
        val type = value.stringAt("type") ?: return "type is required".invalid()
        val state = value.stringAt("state") ?: return "state is required".invalid()
        val pattern = Regex("^[A-Za-z0-9_.:-]{1,64}$")
        if (!interfaceName.matches(pattern)) return "interface is invalid".invalid()
        if (!type.matches(pattern)) return "type is invalid".invalid()
        if (!state.matches(pattern)) return "state is invalid".invalid()
        return Validation.Valid(NetworkDeviceStatus(interfaceName, type, state))
    }

    private fun validateNetworkMutationCapability(value: Map<String, Any?>): Validation<NetworkMutationCapability> {
        value.exactKeys(
            "enabled",
            "disabled_reason",
            "operations",
            "idempotency_key_required",
            "secret_handling",
            "active_state_policy",
        )?.let { return it.invalid() }
        val enabled = value.booleanAt("enabled") ?: return "enabled must be boolean".invalid()
        val disabledReason = value["disabled_reason"]
        if (disabledReason != null && disabledReason !is String) {
            return "disabled_reason must be string or null".invalid()
        }
        val reason = disabledReason
        if (reason != null && !isNetworkMutationDisabledReason(reason)) {
            return "disabled_reason is not in the v1 enum".invalid()
        }
        if (enabled && reason != null) return "enabled=true requires disabled_reason=null".invalid()
        if (!enabled && reason == null) return "enabled=false requires disabled_reason".invalid()
        val operations = value["operations"] as? List<*> ?: return "operations must be an array".invalid()
        if (operations != listOf("apply", "retry", "forget")) {
            return "operations must be [apply, retry, forget]".invalid()
        }
        if (value["idempotency_key_required"] != true) return "idempotency_key_required must be true".invalid()
        if (value["secret_handling"] != "opaque_credential_reference_only") {
            return "secret_handling must be opaque_credential_reference_only".invalid()
        }
        if (value["active_state_policy"] != "idle_only") {
            return "active_state_policy must be idle_only".invalid()
        }
        return Validation.Valid(
            NetworkMutationCapability(
                enabled = enabled,
                disabledReason = reason,
                operations = operations.filterIsInstance<String>(),
                idempotencyKeyRequired = true,
                secretHandling = "opaque_credential_reference_only",
                activeStatePolicy = "idle_only",
            ),
        )
    }

    private fun validateNetworkConcurrencyCapability(
        value: Map<String, Any?>,
    ): Validation<NetworkConcurrencyCapability> {
        value.exactKeys(
            "rescue_ap_required",
            "same_phy_ap_sta",
            "exclusive_client_failure_timeout_seconds",
            "max_managed_interfaces",
            "max_ap_interfaces",
        )?.let { return it.invalid() }
        if (value["rescue_ap_required"] != true) return "rescue_ap_required must be true".invalid()
        val samePhyApSta = value.stringAt("same_phy_ap_sta") ?: return "same_phy_ap_sta is required".invalid()
        if (samePhyApSta !in setOf("supported", "unsupported", "unverified")) {
            return "same_phy_ap_sta is not in the v1 enum".invalid()
        }
        val timeout = value.longAt("exclusive_client_failure_timeout_seconds")
            ?: return "exclusive_client_failure_timeout_seconds must be integer".invalid()
        if (timeout != 10L) return "exclusive_client_failure_timeout_seconds must be 10".invalid()
        val maxManaged = value.longAt("max_managed_interfaces")
            ?: return "max_managed_interfaces must be integer".invalid()
        val maxAp = value.longAt("max_ap_interfaces") ?: return "max_ap_interfaces must be integer".invalid()
        if (maxManaged !in 0L..8L) return "max_managed_interfaces must be in 0..8".invalid()
        if (maxAp !in 0L..8L) return "max_ap_interfaces must be in 0..8".invalid()
        return Validation.Valid(NetworkConcurrencyCapability(true, samePhyApSta, timeout, maxManaged, maxAp))
    }

    private fun validateNetworkTransactionWindow(value: Map<String, Any?>): Validation<NetworkTransactionWindow> {
        value.exactKeys("current", "latest")?.let { return it.invalid() }
        val current = networkTransactionOrNull(value["current"])
            .valueOrReturn { return "current.$it".invalid() }
        val latest = networkTransactionOrNull(value["latest"])
            .valueOrReturn { return "latest.$it".invalid() }
        return Validation.Valid(NetworkTransactionWindow(current, latest))
    }

    private fun networkTransactionOrNull(value: Any?): Validation<NetworkTransaction?> {
        return when (value) {
            null -> Validation.Valid(null)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                validateNetworkTransaction(value as Map<String, Any?>)
            }
            else -> "must be object or null".invalid()
        }
    }

    private fun validateNetworkTransaction(value: Map<String, Any?>): Validation<NetworkTransaction> {
        value.exactKeys(
            "schema",
            "authority_epoch",
            "source_revision",
            "transaction_id",
            "operation",
            "status",
            "stage",
            "desired",
            "accepted_at",
            "updated_at",
            "deadline",
            "recovery_action",
            "rescue",
            "error",
        )?.let { return it.invalid() }
        value.constString("schema", "ylx.network-transaction.v1")?.let { return it.invalid() }
        val epoch = value.stringAt("authority_epoch") ?: return "authority_epoch is required".invalid()
        if (!isUuidV4(epoch)) return "authority_epoch must be a UUID v4".invalid()
        val revision = value.longAt("source_revision") ?: return "source_revision must be integer".invalid()
        if (revision < 0L) return "source_revision must be non-negative".invalid()
        val transactionId = value.stringAt("transaction_id") ?: return "transaction_id is required".invalid()
        if (!isUuidV7(transactionId)) return "transaction_id must be UUID v7".invalid()
        val operation = value.stringAt("operation") ?: return "operation is required".invalid()
        if (operation !in setOf("apply", "retry", "forget")) return "operation is not in the v1 enum".invalid()
        val status = value.stringAt("status") ?: return "status is required".invalid()
        if (status !in setOf("accepted", "running", "committed", "rescued", "failed")) {
            return "status is not in the v1 enum".invalid()
        }
        val stage = value.stringAt("stage") ?: return "stage is required".invalid()
        val validStages = setOf(
            "accepted",
            "prepared",
            "ap_ready",
            "activating",
            "verifying",
            "committed",
            "falling_back",
            "rescued",
            "failed",
            "forgetting",
            "forgotten",
        )
        if (stage !in validStages) return "stage is not in the v1 enum".invalid()
        val desired = validateNetworkDesiredState(
            value.objectAt("desired") ?: return "desired must be an object".invalid(),
        ).valueOrReturn { return "desired.$it".invalid() }
        val acceptedAt = value.stringAt("accepted_at") ?: return "accepted_at is required".invalid()
        val updatedAt = value.stringAt("updated_at") ?: return "updated_at is required".invalid()
        if (acceptedAt.isBlank() || updatedAt.isBlank()) return "transaction timestamps must be non-empty".invalid()
        val deadline = when (val raw = value["deadline"]) {
            null -> null
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                validateNetworkDeviceDeadline(raw as Map<String, Any?>)
                    .valueOrReturn { return "deadline.$it".invalid() }
            }
            else -> return "deadline must be object or null".invalid()
        }
        val recoveryAction = value.stringAt("recovery_action") ?: return "recovery_action is required".invalid()
        if (recoveryAction !in setOf("await_device", "reconnect_target_lan", "reconnect_rescue_ap", "retry", "service_required", "none")) {
            return "recovery_action is not in the v1 enum".invalid()
        }
        val rescue = validateNetworkRescueState(
            value.objectAt("rescue") ?: return "rescue must be an object".invalid(),
        ).valueOrReturn { return "rescue.$it".invalid() }
        val error = when (val raw = value["error"]) {
            null -> null
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                validateNetworkTransactionError(raw as Map<String, Any?>)
                    .valueOrReturn { return "error.$it".invalid() }
            }
            else -> return "error must be object or null".invalid()
        }
        if (status in setOf("rescued", "failed") && error == null) {
            return "$status transaction requires error".invalid()
        }
        return Validation.Valid(
            NetworkTransaction(
                authorityEpoch = epoch,
                sourceRevision = revision,
                transactionId = transactionId,
                operation = operation,
                status = status,
                stage = stage,
                desired = desired,
                acceptedAt = acceptedAt,
                updatedAt = updatedAt,
                deadline = deadline,
                recoveryAction = recoveryAction,
                rescue = rescue,
                error = error,
            ),
        )
    }

    private fun validateNetworkDeviceDeadline(value: Map<String, Any?>): Validation<NetworkDeviceDeadline> {
        value.exactKeys("time_base", "deadline_ns", "remaining_seconds")?.let { return it.invalid() }
        value.constString("time_base", "device_monotonic")?.let { return it.invalid() }
        val deadlineNs = value.longAt("deadline_ns") ?: return "deadline_ns must be integer".invalid()
        if (deadlineNs < 0L) return "deadline_ns must be non-negative".invalid()
        val remainingSeconds = value.numberAt("remaining_seconds")
            ?: return "remaining_seconds must be number".invalid()
        if (remainingSeconds < 0.0 || remainingSeconds > 10.0) {
            return "remaining_seconds must be in 0..10".invalid()
        }
        return Validation.Valid(NetworkDeviceDeadline(deadlineNs, remainingSeconds))
    }

    private fun validateNetworkRescueState(value: Map<String, Any?>): Validation<NetworkRescueState> {
        value.exactKeys("ap_validated", "fallback_mode", "failure_trigger_seconds")?.let { return it.invalid() }
        val apValidated = value.booleanAt("ap_validated") ?: return "ap_validated must be boolean".invalid()
        if (value["fallback_mode"] != "hotspot") return "fallback_mode must be hotspot".invalid()
        if (value.longAt("failure_trigger_seconds") != 10L) {
            return "failure_trigger_seconds must be 10".invalid()
        }
        return Validation.Valid(NetworkRescueState(apValidated, "hotspot", 10L))
    }

    private fun validateNetworkTransactionError(value: Map<String, Any?>): Validation<NetworkTransactionError> {
        value.exactKeys("code", "message", "retryable")?.let { return it.invalid() }
        val code = value.stringAt("code") ?: return "code is required".invalid()
        if (code !in setOf(
                "rescue_ap_unavailable",
                "credential_rejected",
                "dhcp_timeout",
                "route_lost",
                "network_manager_unavailable",
                "concurrency_unsupported",
            )
        ) {
            return "code is not in the v1 enum".invalid()
        }
        val message = value.stringAt("message") ?: return "message is required".invalid()
        if (message.isBlank() || message.length > 512) return "message must be 1..512 chars".invalid()
        val retryable = value.booleanAt("retryable") ?: return "retryable must be boolean".invalid()
        return Validation.Valid(NetworkTransactionError(code, message, retryable))
    }

    private fun validateNetworkScanEntry(value: Map<String, Any?>): Validation<NetworkScanEntry> {
        value.exactKeys("ssid", "hidden", "security", "signal_dbm", "credential_required")
            ?.let { return it.invalid() }
        val hidden = value.booleanAt("hidden") ?: return "hidden must be boolean".invalid()
        val ssid = value["ssid"]
        if (hidden && ssid != null) return "hidden network requires ssid=null".invalid()
        if (!hidden && (ssid !is String || !isValidSsid(ssid))) {
            return "visible network requires ssid 1..32 UTF-8 bytes".invalid()
        }
        val security = value.stringAt("security") ?: return "security is required".invalid()
        if (!isNetworkWifiSecurity(security)) return "security is not in the v1 enum".invalid()
        val signalDbm = value.longAt("signal_dbm") ?: return "signal_dbm must be integer".invalid()
        if (signalDbm !in -127L..0L) return "signal_dbm must be in -127..0".invalid()
        val credentialRequired = value.booleanAt("credential_required")
            ?: return "credential_required must be boolean".invalid()
        if (security == "open" && credentialRequired) return "open network requires credential_required=false".invalid()
        if (security != "open" && !credentialRequired) {
            return "protected network requires credential_required=true".invalid()
        }
        return Validation.Valid(NetworkScanEntry(ssid as? String, hidden, security, signalDbm, credentialRequired))
    }

    private fun validateNetworkRuntimeStatus(value: Map<String, Any?>): Validation<NetworkRuntimeStatus> {
        value.exactKeys("ap", "wifi_client", "wired", "default_route")?.let { return it.invalid() }
        val ap = validateNetworkInterfaceStatus(
            value.objectAt("ap") ?: return "ap must be an object".invalid(),
        ).valueOrReturn { return "ap.$it".invalid() }
        val wifiClient = validateNetworkInterfaceStatus(
            value.objectAt("wifi_client") ?: return "wifi_client must be an object".invalid(),
        ).valueOrReturn { return "wifi_client.$it".invalid() }
        val wired = validateNetworkInterfaceStatus(
            value.objectAt("wired") ?: return "wired must be an object".invalid(),
        ).valueOrReturn { return "wired.$it".invalid() }
        val defaultRoute = value.stringAt("default_route") ?: return "default_route is required".invalid()
        if (defaultRoute !in setOf("wifi_client", "wired", "none")) {
            return "default_route is not in the v4 enum".invalid()
        }
        return Validation.Valid(
            NetworkRuntimeStatus(
                ap = ap,
                wifiClient = wifiClient,
                wired = wired,
                defaultRoute = defaultRoute,
            ),
        )
    }

    private fun validateNetworkInterfaceStatus(value: Map<String, Any?>): Validation<NetworkInterfaceRuntime> {
        value.exactKeys("state", "interface", "addresses", "peer_or_ssid")?.let { return it.invalid() }
        val state = value.stringAt("state") ?: return "state is required".invalid()
        if (state !in setOf("disabled", "disconnected", "starting", "connecting", "connected", "active", "degraded", "failed", "unavailable")) {
            return "state is not in the v4 enum".invalid()
        }
        val interfaceValue = value["interface"]
        if (interfaceValue != null && interfaceValue !is String) {
            return "interface must be null or string".invalid()
        }
        val interfaceName = interfaceValue
        if (interfaceName != null &&
            (
                interfaceName.isBlank() ||
                    interfaceName.length > 64 ||
                    !interfaceName.matches(Regex("^[A-Za-z0-9_.:-]+$"))
                )
        ) {
            return "interface is not a valid interface name".invalid()
        }
        val addresses = value["addresses"] as? List<*> ?: return "addresses must be an array".invalid()
        val addressStrings = addresses.mapIndexed { index, address ->
            val stringAddress = address as? String ?: return "addresses[$index] must be a string".invalid()
            if (stringAddress.isBlank() || stringAddress.length > 64) {
                return "addresses[$index] must be a non-empty string up to 64 chars".invalid()
            }
            stringAddress
        }
        if (addressStrings.toSet().size != addressStrings.size) {
            return "addresses must be unique".invalid()
        }
        val peerValue = value["peer_or_ssid"]
        if (peerValue != null && peerValue !is String) {
            return "peer_or_ssid must be null or string".invalid()
        }
        val peerOrSsid = peerValue
        if (peerOrSsid != null && (peerOrSsid.isBlank() || peerOrSsid.length > 128)) {
            return "peer_or_ssid must be a non-empty string up to 128 chars".invalid()
        }
        if (state in setOf("connected", "active", "degraded")) {
            if (interfaceName == null) return "active state requires interface".invalid()
            if (addressStrings.isEmpty()) return "active state requires at least one address".invalid()
        }
        return Validation.Valid(
            NetworkInterfaceRuntime(
                state = state,
                interfaceName = interfaceName,
                addresses = addressStrings,
                peerOrSsid = peerOrSsid,
            ),
        )
    }

    private fun validateLiveImuObservation(value: Any?): Validation<String?> {
        if (value == null) return Validation.Valid(null)
        val observation = value as? Map<*, *> ?: return "must be an object or null".invalid()
        @Suppress("UNCHECKED_CAST")
        val typedObservation = observation as Map<String, Any?>
        typedObservation.exactKeys("session_id", "clock", "raw", "sync")?.let { return it.invalid() }
        val sessionId = typedObservation.stringAt("session_id") ?: return "session_id is required".invalid()
        if (!isUuidV7(sessionId)) return "session_id must be a UUID v7".invalid()
        val clock = typedObservation.objectAt("clock") ?: return "clock must be an object".invalid()
        clock.exactKeys("time_base", "timestamp_ns")?.let { return "clock.$it".invalid() }
        clock.constString("time_base", "host_monotonic")?.let { return "clock.$it".invalid() }
        val timestampNs = clock.longAt("timestamp_ns") ?: return "clock.timestamp_ns must be integer".invalid()
        if (timestampNs < 0L) return "clock.timestamp_ns must be non-negative".invalid()
        val raw = typedObservation.objectAt("raw") ?: return "raw must be an object".invalid()
        raw.exactKeys("units", "accelerometer", "gyroscope")?.let { return "raw.$it".invalid() }
        raw.constString("units", "raw_int16")?.let { return "raw.$it".invalid() }
        validateRawInt16Vector(raw.objectAt("accelerometer") ?: return "raw.accelerometer must be an object".invalid())
            .valueOrReturn { return "raw.accelerometer.$it".invalid() }
        validateRawInt16Vector(raw.objectAt("gyroscope") ?: return "raw.gyroscope must be an object".invalid())
            .valueOrReturn { return "raw.gyroscope.$it".invalid() }
        val sync = typedObservation.objectAt("sync") ?: return "sync must be an object".invalid()
        sync.exactKeys("quality")?.let { return "sync.$it".invalid() }
        val quality = sync.stringAt("quality") ?: return "sync.quality is required".invalid()
        if (quality !in setOf("insufficient", "degraded", "good")) {
            return "sync.quality is not in the v4 enum".invalid()
        }
        return Validation.Valid(quality)
    }

    private fun validateRawInt16Vector(value: Map<String, Any?>): Validation<Unit> {
        value.exactKeys("x", "y", "z")?.let { return it.invalid() }
        listOf("x", "y", "z").forEach { axis ->
            val component = value.longAt(axis) ?: return "$axis must be integer".invalid()
            if (component !in -32768L..32767L) {
                return "$axis must fit int16".invalid()
            }
        }
        return Validation.Valid(Unit)
    }

    private fun validateCaptureStateEventData(value: Map<String, Any?>): Validation<Unit> {
        value.exactKeys("schema", "state", "volume_id", "generation_id")?.let { return it.invalid() }
        value.constString("schema", "ylx.capture-state-event.v2")?.let { return it.invalid() }
        val state = value.stringAt("state") ?: return "state is required".invalid()
        if (state !in setOf("recording", "finalizing", "encoding", "verifying", "recoverable", "failed", "abandoned")) {
            return "state is not in the v2 enum".invalid()
        }
        val volumeId = value.stringAt("volume_id") ?: return "volume_id is required".invalid()
        if (!isUuidV4(volumeId)) return "volume_id must be a UUID v4".invalid()
        val generationId = value.stringAt("generation_id") ?: return "generation_id is required".invalid()
        if (!isUuidV4(generationId)) return "generation_id must be a UUID v4".invalid()
        return Validation.Valid(Unit)
    }

    private fun validateCaptureProgressEventData(value: Map<String, Any?>): Validation<Unit> {
        value.exactKeys("schema", "phase", "elapsed_seconds", "completed_units", "total_units", "unit")
            ?.let { return it.invalid() }
        value.constString("schema", "ylx.capture-progress-event.v2")?.let { return it.invalid() }
        val phase = value.stringAt("phase") ?: return "phase is required".invalid()
        if (phase !in setOf("recording", "finalizing", "encoding", "verifying")) {
            return "phase is not in the v2 enum".invalid()
        }
        val elapsedSeconds = value.numberAt("elapsed_seconds") ?: return "elapsed_seconds must be number".invalid()
        if (elapsedSeconds < 0.0) return "elapsed_seconds must be non-negative".invalid()
        val completedUnits = value.longAt("completed_units") ?: return "completed_units must be integer".invalid()
        if (completedUnits < 0L) return "completed_units must be non-negative".invalid()
        val totalUnits = value["total_units"]
        if (totalUnits != null && value.longAt("total_units") == null) {
            return "total_units must be null or integer".invalid()
        }
        if (value.longAt("total_units")?.let { it < 0L } == true) {
            return "total_units must be non-negative".invalid()
        }
        val unit = value.stringAt("unit") ?: return "unit is required".invalid()
        if (unit !in setOf("frames", "bytes", "artifacts", "checks")) {
            return "unit is not in the v2 enum".invalid()
        }
        return Validation.Valid(Unit)
    }

    private fun validateCaptureDiagnosticEventData(value: Map<String, Any?>): Validation<Unit> {
        value.exactKeys("schema", "diagnostic")?.let { return it.invalid() }
        value.constString("schema", "ylx.capture-diagnostic-event.v2")?.let { return it.invalid() }
        if (value["diagnostic"] !is Map<*, *>) return "diagnostic must be an object".invalid()
        return Validation.Valid(Unit)
    }

    private fun validateSessionSummary(
        value: Map<String, Any?>,
        contract: SessionListContract,
        requestedTakeId: String?,
    ): Validation<SessionSummary> {
        value.exactKeys(
            "session_id",
            "producer_outcome",
            "take_id",
            "take_sequence",
            "continuation_of",
            "display_name",
            "device",
            "started_at",
            "ended_at",
            "duration_seconds",
            "total_bytes",
            "verification",
        )?.let { return it.invalid() }
        val producerOutcome = value.stringAt("producer_outcome") ?: return "producer_outcome is required".invalid()
        if (producerOutcome != "sealed") return "producer_outcome must be sealed".invalid()

        val sessionId = value.stringAt("session_id") ?: return "session_id is required".invalid()
        if (!isUuidV7(sessionId)) return "session_id must be a UUID v7".invalid()
        val takeId = value.stringAt("take_id") ?: return "take_id is required".invalid()
        if (!isUuidV7(takeId)) return "take_id must be a UUID v7".invalid()
        if (requestedTakeId != null && takeId != requestedTakeId) {
            return "take_id does not match the request filter".invalid()
        }
        val sequence = value.longAt("take_sequence") ?: return "take_sequence must be integer".invalid()
        if (sequence < 1L) return "take_sequence must be positive".invalid()
        val continuation = value["continuation_of"]
        if (continuation != null && (continuation !is String || !isUuidV7(continuation))) {
            return "continuation_of must be null or UUID v7".invalid()
        }
        val displayName = value.stringAt("display_name") ?: return "display_name is required".invalid()
        if (displayName.isBlank() || displayName.length > 160) {
            return "display_name must contain 1..160 characters".invalid()
        }
        val device = value.objectAt("device") ?: return "device must be an object".invalid()
        device.exactKeys("device_id", "device_label")?.let { return "device.$it".invalid() }
        val deviceId = device.stringAt("device_id") ?: return "device.device_id is required".invalid()
        if (!isUuidV4(deviceId)) return "device.device_id must be a UUID v4".invalid()
        val deviceLabel = device.stringAt("device_label") ?: return "device.device_label is required".invalid()
        if (!deviceLabel.matches(Regex("^YLX-[0-9A-F]{8}$"))) {
            return "device.device_label must match YLX-XXXXXXXX".invalid()
        }
        val startedAt = value.stringAt("started_at") ?: return "started_at is required".invalid()
        val endedAt = value.stringAt("ended_at") ?: return "ended_at is required".invalid()
        if (parseDateTime(startedAt) == null || parseDateTime(endedAt) == null) {
            return "session timestamps must be RFC 3339 date-times".invalid()
        }
        val duration = value.numberAt("duration_seconds") ?: return "duration_seconds must be number".invalid()
        if (duration < 0.0) return "duration_seconds must be non-negative".invalid()
        val totalBytes = value.longAt("total_bytes") ?: return "total_bytes must be integer".invalid()
        if (totalBytes < 0L) return "total_bytes must be non-negative".invalid()
        val verification = value["verification"]
        val gatewayVerification = when (verification) {
            null -> null
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                validateGatewayVerification(verification as Map<String, Any?>, contract)
                    .valueOrReturn { return "verification.$it".invalid() }
            }
            else -> return "verification must be null or object".invalid()
        }

        return Validation.Valid(
            SessionSummary(
                sessionId = sessionId,
                producerOutcome = producerOutcome,
                takeId = takeId,
                displayName = displayName,
                deviceLabel = deviceLabel,
                startedAt = startedAt,
                endedAt = endedAt,
                durationSeconds = duration,
                totalBytes = totalBytes,
                verification = gatewayVerification,
            ),
        )
    }

    private fun validateGatewayVerification(
        value: Map<String, Any?>,
        contract: SessionListContract,
    ): Validation<GatewayVerification> {
        value.exactKeys("actor", "validator", "manifest_sha256", "verified_at", "verdict", "diagnostics")
            ?.let { return it.invalid() }
        value.constString("actor", "gateway")?.let { return it.invalid() }
        val validator = value.objectAt("validator") ?: return "validator must be an object".invalid()
        validator.exactKeys("name", "version", "build_sha256")?.let { return "validator.$it".invalid() }
        val validatorName = validator.stringAt("name") ?: return "validator.name is required".invalid()
        val validatorVersion = validator.stringAt("version") ?: return "validator.version is required".invalid()
        val validatorBuild = validator.stringAt("build_sha256") ?: return "validator.build_sha256 is required".invalid()
        if (validatorName.isBlank() || validatorName.length > 128) {
            return "validator.name must contain 1..128 characters".invalid()
        }
        if (validatorVersion.isBlank() || validatorVersion.length > 64) {
            return "validator.version must contain 1..64 characters".invalid()
        }
        if (!isSha256(validatorBuild)) return "validator.build_sha256 must be SHA-256".invalid()
        val manifestSha256 = value.stringAt("manifest_sha256") ?: return "manifest_sha256 is required".invalid()
        if (!isSha256(manifestSha256)) return "manifest_sha256 must be SHA-256".invalid()
        val verifiedAt = value.stringAt("verified_at") ?: return "verified_at is required".invalid()
        if (parseDateTime(verifiedAt) == null) return "verified_at must be an RFC 3339 date-time".invalid()
        val verdict = value.stringAt("verdict") ?: return "verdict is required".invalid()
        if (verdict !in setOf("usable", "unusable")) return "verdict must be usable or unusable".invalid()
        val rawDiagnostics = value["diagnostics"] as? List<*> ?: return "diagnostics must be an array".invalid()
        if (verdict == "unusable" && rawDiagnostics.isEmpty()) {
            return "unusable verification requires diagnostics".invalid()
        }
        val diagnostics = when (contract) {
            SessionListContract.V2 -> {
                if (rawDiagnostics.any { it !is String || it.isBlank() || it.length > 512 }) {
                    return "diagnostics entries must be strings containing 1..512 characters".invalid()
                }
                rawDiagnostics.map { rawDiagnostic ->
                    GatewayVerificationDiagnostic.Legacy(rawDiagnostic as String)
                }
            }
            SessionListContract.V3 -> {
                rawDiagnostics.mapIndexed { index, rawDiagnostic ->
                    val diagnostic = rawDiagnostic as? Map<*, *>
                        ?: return "diagnostics[$index] must be an object".invalid()
                    @Suppress("UNCHECKED_CAST")
                    validateGatewayVerificationDiagnostic(diagnostic as Map<String, Any?>)
                        .valueOrReturn { return "diagnostics[$index].$it".invalid() }
                }
            }
        }
        return Validation.Valid(
            GatewayVerification(
                actor = "gateway",
                validator = GatewayValidatorIdentity(
                    name = validatorName,
                    version = validatorVersion,
                    buildSha256 = validatorBuild,
                ),
                manifestSha256 = manifestSha256,
                verifiedAt = verifiedAt,
                verdict = GatewayVerificationVerdict.fromWireValue(verdict),
                diagnostics = diagnostics,
            ),
        )
    }

    private fun validateGatewayVerificationDiagnostic(
        value: Map<String, Any?>,
    ): Validation<GatewayVerificationDiagnostic.Current> {
        value.exactKeys("code", "summary")?.let { return it.invalid() }
        val code = value["code"] as? String ?: return "code must be a string".invalid()
        if (code !in setOf("artifact_digest_mismatch", "artifact_invalid", "manifest_invalid", "verification_failed")) {
            return "code is not in the v3 enum".invalid()
        }
        val summary = value["summary"] as? String ?: return "summary must be a string".invalid()
        if (summary.isBlank() || summary.length > 512) {
            return "summary must contain 1..512 characters".invalid()
        }
        return Validation.Valid(
            GatewayVerificationDiagnostic.Current(
                code = GatewayVerificationDiagnosticCode.fromWireValue(code),
                summary = summary,
            ),
        )
    }

    private fun validateSessionDiscoveryDiagnostic(
        value: Map<String, Any?>,
    ): Validation<SessionDiscoveryDiagnostic> {
        value.exactKeys("quarantine_id", "code", "observed_at", "message")?.let { return it.invalid() }
        val quarantineId = value.stringAt("quarantine_id") ?: return "quarantine_id is required".invalid()
        if (!isUuidV4(quarantineId)) return "quarantine_id must be a UUID v4".invalid()
        val code = value.stringAt("code") ?: return "code is required".invalid()
        if (code !in setOf("manifest_unreadable", "unsupported_schema", "manifest_invalid", "manifest_not_sealed")) {
            return "code is not in the v2 enum".invalid()
        }
        val observedAt = value.stringAt("observed_at") ?: return "observed_at is required".invalid()
        val message = value.stringAt("message") ?: return "message is required".invalid()
        if (parseDateTime(observedAt) == null) return "observed_at must be an RFC 3339 date-time".invalid()
        if (message.isBlank() || message.length > 512) {
            return "message must contain 1..512 characters".invalid()
        }
        return Validation.Valid(
            SessionDiscoveryDiagnostic(
                quarantineId = quarantineId,
                code = code,
                observedAt = observedAt,
                message = message,
            ),
        )
    }

    private fun isStrictlyNewer(newer: SessionSummary, older: SessionSummary): Boolean {
        val newerStartedAt = requireNotNull(parseDateTime(newer.startedAt))
        val olderStartedAt = requireNotNull(parseDateTime(older.startedAt))
        val timeComparison = newerStartedAt.compareTo(olderStartedAt)
        return timeComparison > 0 || (timeComparison == 0 && newer.sessionId > older.sessionId)
    }

    private fun parseDateTime(value: String) = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: RuntimeException) {
        null
    }

    private fun collectSessionArtifacts(value: Map<String, Any?>): Validation<List<ArtifactDescriptor>> {
        val artifacts = mutableListOf<ArtifactDescriptor>()

        val imu = value.objectAt("imu") ?: return "imu must be an object".invalid()
        imu.exactKeys("artifact", "sample_count", "units", "coordinate_frame")
            ?.let { return "imu.$it".invalid() }
        val sampleCount = imu.longAt("sample_count") ?: return "imu.sample_count must be integer".invalid()
        if (sampleCount < 0L) return "imu.sample_count must be non-negative".invalid()
        if (imu["units"] != "raw_int16") return "imu.units must be raw_int16".invalid()
        if (imu["coordinate_frame"] != "raw_device_axes") {
            return "imu.coordinate_frame must be raw_device_axes".invalid()
        }
        artifacts += validateRequiredSessionArtifact(
            container = imu,
            key = "artifact",
            expectedRole = "imu.samples",
            expectedMediaType = "application/x-ndjson",
        ).valueOrReturn { return "imu.$it".invalid() }

        val frames = value.objectAt("frames") ?: return "frames must be an object".invalid()
        frames.exactKeys("artifact", "count")?.let { return "frames.$it".invalid() }
        val frameCount = frames.longAt("count") ?: return "frames.count must be integer".invalid()
        if (frameCount < 0L) return "frames.count must be non-negative".invalid()
        artifacts += validateRequiredSessionArtifact(
            container = frames,
            key = "artifact",
            expectedRole = "frames.index",
            expectedMediaType = "application/x-ndjson",
        ).valueOrReturn { return "frames.$it".invalid() }

        val video = value.objectAt("video") ?: return "video must be an object".invalid()
        artifacts += validateCurrentSessionVideo(video)
            .valueOrReturn { return "video.$it".invalid() }

        val audio = value.objectAt("audio") ?: return "audio must be an object".invalid()
        artifacts += validateCurrentSessionAudio(audio)
            .valueOrReturn { return "audio.$it".invalid() }

        val logs = value["logs"] as? List<*> ?: return "logs must be an array".invalid()
        logs.forEachIndexed { index, rawLog ->
            val log = rawLog as? Map<*, *> ?: return "logs[$index] must be an object".invalid()
            @Suppress("UNCHECKED_CAST")
            when (val result = validateArtifactDescriptor(log as Map<String, Any?>)) {
                is Validation.Valid -> {
                    if (!result.value.role.matches(Regex("^log(?:[._-][a-z0-9]+)+$"))) {
                        return "logs[$index].role is not a log artifact role".invalid()
                    }
                    artifacts.add(result.value)
                }
                is Validation.Invalid -> return "logs[$index].${result.message}".invalid()
            }
        }

        return Validation.Valid(artifacts)
    }

    private fun validateCurrentSessionVideo(
        value: Map<String, Any?>,
    ): Validation<List<ArtifactDescriptor>> {
        if (value["layout"] != "split-eyes") return "layout must be split-eyes".invalid()
        value.exactKeys("layout", "codec", "container", "segments")?.let { return it.invalid() }
        if (value["codec"] != "h264") return "codec must be h264".invalid()
        if (value["container"] != "mp4") return "container must be mp4".invalid()
        val segments = value["segments"] as? List<*> ?: return "segments must be an array".invalid()
        if (segments.isEmpty()) return "segments must contain at least one entry".invalid()
        if (segments.distinct().size != segments.size) return "segments must contain unique entries".invalid()

        val artifacts = mutableListOf<ArtifactDescriptor>()
        segments.forEachIndexed { index, rawSegment ->
            val untypedSegment = rawSegment as? Map<*, *>
                ?: return "segments[$index] must be an object".invalid()
            @Suppress("UNCHECKED_CAST")
            val segment = untypedSegment as Map<String, Any?>
            segment.exactKeys(
                "index",
                "start_frame",
                "end_frame",
                "start_time_seconds",
                "end_time_seconds",
                "artifacts",
            )?.let { return "segments[$index].$it".invalid() }
            val segmentIndex = segment.longAt("index")
                ?: return "segments[$index].index must be integer".invalid()
            val startFrame = segment.longAt("start_frame")
                ?: return "segments[$index].start_frame must be integer".invalid()
            val endFrame = segment.longAt("end_frame")
                ?: return "segments[$index].end_frame must be integer".invalid()
            val startTime = segment.numberAt("start_time_seconds")
                ?: return "segments[$index].start_time_seconds must be number".invalid()
            val endTime = segment.numberAt("end_time_seconds")
                ?: return "segments[$index].end_time_seconds must be number".invalid()
            if (segmentIndex < 0L) return "segments[$index].index must be non-negative".invalid()
            if (startFrame < 0L) return "segments[$index].start_frame must be non-negative".invalid()
            if (endFrame < 1L) return "segments[$index].end_frame must be positive".invalid()
            if (startTime < 0.0) return "segments[$index].start_time_seconds must be non-negative".invalid()
            if (endTime <= 0.0) return "segments[$index].end_time_seconds must be positive".invalid()
            val byEye = segment.objectAt("artifacts")
                ?: return "segments[$index].artifacts must be an object".invalid()
            byEye.exactKeys("left", "right")
                ?.let { return "segments[$index].artifacts.$it".invalid() }
            artifacts += validateRequiredSessionArtifact(
                container = byEye,
                key = "left",
                expectedRole = "video.left",
                expectedMediaType = "video/mp4",
            ).valueOrReturn { return "segments[$index].artifacts.$it".invalid() }
            artifacts += validateRequiredSessionArtifact(
                container = byEye,
                key = "right",
                expectedRole = "video.right",
                expectedMediaType = "video/mp4",
            ).valueOrReturn { return "segments[$index].artifacts.$it".invalid() }
        }
        return Validation.Valid(artifacts)
    }

    private fun validateCurrentSessionAudio(
        value: Map<String, Any?>,
    ): Validation<List<ArtifactDescriptor>> {
        val state = value.stringAt("state") ?: return "state is required".invalid()
        return when (state) {
            "not_recorded" -> {
                value.exactKeys("state", "requested_mode", "resolved_mode", "reason")
                    ?.let { return it.invalid() }
                if (value["requested_mode"] !in setOf("device_default", "disabled")) {
                    return "requested_mode must be device_default or disabled".invalid()
                }
                if (value["resolved_mode"] != "disabled") {
                    return "resolved_mode must be disabled".invalid()
                }
                if (value["reason"] !in setOf("user_disabled", "device_default_disabled")) {
                    return "reason is not in the not_recorded enum".invalid()
                }
                Validation.Valid(emptyList())
            }
            "recorded" -> validateRecordedSessionAudio(value)
            else -> "state must be recorded or not_recorded".invalid()
        }
    }

    private fun validateRecordedSessionAudio(
        value: Map<String, Any?>,
    ): Validation<List<ArtifactDescriptor>> {
        value.exactKeys(
            "state",
            "requested_mode",
            "resolved_mode",
            "codec",
            "container",
            "sample_format",
            "sample_rate",
            "channels",
            "sample_count",
            "sync",
            "segments",
        )?.let { return it.invalid() }
        if (value["requested_mode"] !in setOf("device_default", "enabled")) {
            return "requested_mode must be device_default or enabled".invalid()
        }
        if (value["resolved_mode"] != "enabled") return "resolved_mode must be enabled".invalid()
        if (value["codec"] != "pcm_s16le") return "codec must be pcm_s16le".invalid()
        if (value["container"] != "wav") return "container must be wav".invalid()
        if (value["sample_format"] != "S16_LE") return "sample_format must be S16_LE".invalid()
        val sampleRate = value.longAt("sample_rate") ?: return "sample_rate must be integer".invalid()
        val channels = value.longAt("channels") ?: return "channels must be integer".invalid()
        val sampleCount = value.longAt("sample_count") ?: return "sample_count must be integer".invalid()
        if (sampleRate !in 8_000L..384_000L) return "sample_rate must be in 8000..384000".invalid()
        if (channels !in 1L..8L) return "channels must be in 1..8".invalid()
        if (sampleCount < 1L) return "sample_count must be positive".invalid()

        val sync = value.objectAt("sync") ?: return "sync must be an object".invalid()
        sync.exactKeys("time_base", "start_time_seconds", "end_time_seconds", "video_time_reference")
            ?.let { return "sync.$it".invalid() }
        if (sync["time_base"] != "host_monotonic") return "sync.time_base must be host_monotonic".invalid()
        val startTime = sync.numberAt("start_time_seconds")
            ?: return "sync.start_time_seconds must be number".invalid()
        val endTime = sync.numberAt("end_time_seconds")
            ?: return "sync.end_time_seconds must be number".invalid()
        if (startTime < 0.0) return "sync.start_time_seconds must be non-negative".invalid()
        if (endTime <= 0.0) return "sync.end_time_seconds must be positive".invalid()
        if (sync["video_time_reference"] != "session_time_seconds") {
            return "sync.video_time_reference must be session_time_seconds".invalid()
        }

        val segments = value["segments"] as? List<*> ?: return "segments must be an array".invalid()
        if (segments.isEmpty()) return "segments must contain at least one entry".invalid()
        if (segments.distinct().size != segments.size) return "segments must contain unique entries".invalid()
        val artifacts = mutableListOf<ArtifactDescriptor>()
        segments.forEachIndexed { index, rawSegment ->
            val untypedSegment = rawSegment as? Map<*, *>
                ?: return "segments[$index] must be an object".invalid()
            @Suppress("UNCHECKED_CAST")
            val segment = untypedSegment as Map<String, Any?>
            segment.exactKeys(
                "index",
                "start_sample",
                "end_sample",
                "start_time_seconds",
                "end_time_seconds",
                "pcm_payload_bytes",
                "wav_header_bytes",
                "artifact",
            )?.let { return "segments[$index].$it".invalid() }
            val segmentIndex = segment.longAt("index")
                ?: return "segments[$index].index must be integer".invalid()
            val startSample = segment.longAt("start_sample")
                ?: return "segments[$index].start_sample must be integer".invalid()
            val endSample = segment.longAt("end_sample")
                ?: return "segments[$index].end_sample must be integer".invalid()
            val segmentStartTime = segment.numberAt("start_time_seconds")
                ?: return "segments[$index].start_time_seconds must be number".invalid()
            val segmentEndTime = segment.numberAt("end_time_seconds")
                ?: return "segments[$index].end_time_seconds must be number".invalid()
            val payloadBytes = segment.longAt("pcm_payload_bytes")
                ?: return "segments[$index].pcm_payload_bytes must be integer".invalid()
            val headerBytes = segment.longAt("wav_header_bytes")
                ?: return "segments[$index].wav_header_bytes must be integer".invalid()
            if (segmentIndex < 0L) return "segments[$index].index must be non-negative".invalid()
            if (startSample < 0L) return "segments[$index].start_sample must be non-negative".invalid()
            if (endSample < 1L) return "segments[$index].end_sample must be positive".invalid()
            if (segmentStartTime < 0.0) {
                return "segments[$index].start_time_seconds must be non-negative".invalid()
            }
            if (segmentEndTime <= 0.0) return "segments[$index].end_time_seconds must be positive".invalid()
            if (payloadBytes < 1L) return "segments[$index].pcm_payload_bytes must be positive".invalid()
            if (headerBytes !in 44L..65_536L) {
                return "segments[$index].wav_header_bytes must be in 44..65536".invalid()
            }
            artifacts += validateRequiredSessionArtifact(
                container = segment,
                key = "artifact",
                expectedRole = "audio.wav",
                expectedMediaType = "audio/wav",
            ).valueOrReturn { return "segments[$index].$it".invalid() }
        }
        return Validation.Valid(artifacts)
    }

    private fun validateRequiredSessionArtifact(
        container: Map<String, Any?>,
        key: String,
        expectedRole: String,
        expectedMediaType: String,
    ): Validation<ArtifactDescriptor> {
        val rawArtifact = container[key] as? Map<*, *> ?: return "$key must be an object".invalid()
        @Suppress("UNCHECKED_CAST")
        val artifact = validateArtifactDescriptor(rawArtifact as Map<String, Any?>)
            .valueOrReturn { return "$key.$it".invalid() }
        if (artifact.role != expectedRole) return "$key.role must be $expectedRole".invalid()
        if (artifact.mediaType != expectedMediaType) {
            return "$key.media_type must be $expectedMediaType".invalid()
        }
        return Validation.Valid(artifact)
    }

    private fun validateArtifactDescriptor(value: Map<String, Any?>): Validation<ArtifactDescriptor> {
        value.exactKeys("artifact_id", "role", "path", "media_type", "bytes", "sha256")?.let { return it.invalid() }
        val artifactId = value.stringAt("artifact_id") ?: return "artifact_id is required".invalid()
        val role = value.stringAt("role") ?: return "role is required".invalid()
        val path = value.stringAt("path") ?: return "path is required".invalid()
        val mediaType = value.stringAt("media_type") ?: return "media_type is required".invalid()
        val bytes = value.longAt("bytes") ?: return "bytes must be integer".invalid()
        val sha256 = value.stringAt("sha256") ?: return "sha256 is required".invalid()
        if (!isSha256(artifactId)) return "artifact_id must be SHA-256".invalid()
        if (!role.matches(Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$"))) {
            return "role is not a stable artifact role".invalid()
        }
        if (role.length > 96) return "role must contain at most 96 characters".invalid()
        if (!isRelativeArtifactPath(path)) return "path is not a safe relative artifact path".invalid()
        if (!mediaType.matches(Regex("^[a-z0-9!#\$&^_.+-]+/[a-z0-9!#\$&^_.+-]+$"))) {
            return "media_type is invalid".invalid()
        }
        if (bytes < 0L) return "bytes must be non-negative".invalid()
        if (!isSha256(sha256)) return "sha256 must be SHA-256".invalid()
        if (artifactId != sha256) return "artifact_id must equal sha256".invalid()
        return Validation.Valid(
            ArtifactDescriptor(
                artifactId = artifactId,
                role = role,
                path = path,
                mediaType = mediaType,
                bytes = bytes,
                sha256 = sha256,
            ),
        )
    }

    private fun Map<String, Any?>.exactKeys(vararg required: String): String? {
        val expected = required.toSet()
        val actual = keys
        val missing = expected - actual
        if (missing.isNotEmpty()) return "missing required key ${missing.first()}"
        val unknown = actual - expected
        if (unknown.isNotEmpty()) return "unknown key ${unknown.first()}"
        return null
    }

    private fun Map<String, Any?>.onlyKnownKeys(vararg known: String): String? {
        val unknown = keys - known.toSet()
        return unknown.firstOrNull()?.let { "unknown key $it" }
    }

    private fun Map<String, Any?>.constString(key: String, expected: String): String? {
        return if (this[key] == expected) null else "$key must be $expected"
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.objectAt(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    private fun Map<String, Any?>.stringAt(key: String): String? = this[key] as? String

    private fun Map<String, Any?>.booleanAt(key: String): Boolean? = this[key] as? Boolean

    private fun Map<String, Any?>.longAt(key: String): Long? = when (val value = this[key]) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun Map<String, Any?>.numberAt(key: String): Double? = when (val value = this[key]) {
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is Float -> value.toDouble()
        is Double -> value
        else -> null
    }

    private fun isUuidV4(value: String): Boolean {
        return value.matches(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"),
        )
    }

    private fun isUuidV7(value: String): Boolean {
        return value.matches(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"),
        )
    }

    private fun isCatalogRevision(value: String): Boolean {
        return value.matches(Regex("^sha256:[0-9a-f]{64}$"))
    }

    private fun isValidSsid(value: String): Boolean {
        return value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 32
    }

    private fun isNetworkWifiSecurity(value: String): Boolean {
        return value in setOf("open", "wpa2-personal", "wpa3-personal", "wpa2-wpa3-personal")
    }

    private fun isCredentialRef(value: String): Boolean {
        return value.length in 1..128 && value.matches(Regex("^cred-[A-Za-z0-9_.:-]+$"))
    }

    private fun isMdnsServiceName(value: String): Boolean {
        return value.matches(Regex("^[A-Za-z0-9_.-]{1,128}$"))
    }

    private fun isNetworkMutationDisabledReason(value: String): Boolean {
        return value in setOf(
            "not_enabled",
            "auth_profile_unavailable",
            "controller_unavailable",
            "network_manager_unavailable",
            "rescue_ap_not_validated",
            "capture_active",
            "recovery_required",
            "maintenance_window_closed",
            "unsupported_concurrency",
        )
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 &&
            parts.all { part ->
                part.isNotEmpty() &&
                    part.length <= 3 &&
                    part.all { it in '0'..'9' } &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
    }

    private fun isSha256(value: String): Boolean = value.matches(Regex("^[0-9a-f]{64}$"))

    private fun isRelativeArtifactPath(value: String): Boolean {
        return value.isNotBlank() &&
            value.length <= 1024 &&
            !value.startsWith("/") &&
            "\\" !in value &&
            value.split("/").none { it.isBlank() || it == "." || it == ".." } &&
            !Regex("[\\u0000-\\u001f\\u007f-\\u009f]").containsMatchIn(value) &&
            value != "manifest.json" &&
            value != "recording.json" &&
            !value.split("/").any { it.contains(".tmp") }
    }

    private fun isUuid(value: String): Boolean {
        return value.matches(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"),
        )
    }

    private inline fun <T> Validation<T>.valueOrReturn(onInvalid: (String) -> Nothing): T {
        return when (this) {
            is Validation.Valid -> value
            is Validation.Invalid -> onInvalid(message)
        }
    }

    private fun String.invalid(): Validation.Invalid = Validation.Invalid(this)
}

sealed interface Validation<out T> {
    data class Valid<T>(val value: T) : Validation<T>
    data class Invalid(val message: String) : Validation<Nothing>
}

data class DeviceDescriptor(
    val deviceId: String,
    val deviceLabel: String,
    val hardwareFingerprint: String,
    val packageVersion: String,
    val commit: String,
    val buildId: String,
    val securityProfile: String,
    val captureCapable: Boolean,
    val previewCapable: Boolean,
    val rangeDownloadCapable: Boolean,
    val networkMutationCapable: Boolean,
    val sessionListCapable: Boolean,
    val sessionDetailCapable: Boolean,
    val artifactDownloadCapable: Boolean,
    val captureStatusCapable: Boolean,
    val sessionDeletionCapable: Boolean,
    val calibrationCapture: CalibrationCaptureCapability = CalibrationCaptureCapability(
        supported = false,
        enabled = false,
        disabledReason = "capture_source_unsupported",
        requiredVideoLayout = "split-eyes",
    ),
    val volumeId: String,
    val totalBytes: Long,
    val availableBytes: Long,
    val writable: Boolean,
    val runtime: DeviceRuntime,
)

data class CaptureStatusSnapshot(
    val authorityEpoch: String,
    val sourceRevision: Long,
    val deviceState: String,
    val hasActiveRecording: Boolean,
    val runtime: DeviceRuntime,
)

data class CaptureEventPayload(
    val sseDeliveryId: String,
    val authorityEpoch: String,
    val sourceRevision: Long,
    val type: String,
    val occurredAt: String,
    val sessionId: String?,
    val snapshot: CaptureStatusSnapshot?,
    val safeSwapReceipt: SafeSwapReceiptSummary?,
)

data class SessionListPage(
    val contract: SessionListContract,
    val catalogRevision: String?,
    val items: List<SessionSummary>,
    val diagnostics: List<SessionDiscoveryDiagnostic>,
    val nextCursor: String?,
    val requestIdentity: SessionListRequestIdentity,
) {
    val diagnosticsCount: Int
        get() = diagnostics.size
}

data class SessionListRequestIdentity(
    val limit: Int,
    val cursor: String?,
    val takeId: String?,
)

enum class SessionListContract {
    V2,
    V3,
}

data class SessionSummary(
    val sessionId: String,
    val producerOutcome: String,
    val takeId: String,
    val displayName: String,
    val deviceLabel: String,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Double,
    val totalBytes: Long,
    val verification: GatewayVerification?,
) {
    val verificationVerdict: String?
        get() = verification?.verdict?.wireValue
}

data class GatewayVerification(
    val actor: String,
    val validator: GatewayValidatorIdentity,
    val manifestSha256: String,
    val verifiedAt: String,
    val verdict: GatewayVerificationVerdict,
    val diagnostics: List<GatewayVerificationDiagnostic>,
)

data class GatewayValidatorIdentity(
    val name: String,
    val version: String,
    val buildSha256: String,
)

enum class GatewayVerificationVerdict(val wireValue: String) {
    USABLE("usable"),
    UNUSABLE("unusable");

    companion object {
        fun fromWireValue(value: String): GatewayVerificationVerdict {
            return entries.single { it.wireValue == value }
        }
    }
}

sealed interface GatewayVerificationDiagnostic {
    val summary: String

    data class Legacy(
        override val summary: String,
    ) : GatewayVerificationDiagnostic

    data class Current(
        val code: GatewayVerificationDiagnosticCode,
        override val summary: String,
    ) : GatewayVerificationDiagnostic
}

enum class GatewayVerificationDiagnosticCode(val wireValue: String) {
    ARTIFACT_DIGEST_MISMATCH("artifact_digest_mismatch"),
    ARTIFACT_INVALID("artifact_invalid"),
    MANIFEST_INVALID("manifest_invalid"),
    VERIFICATION_FAILED("verification_failed");

    companion object {
        fun fromWireValue(value: String): GatewayVerificationDiagnosticCode {
            return entries.single { it.wireValue == value }
        }
    }
}

data class SessionDiscoveryDiagnostic(
    val quarantineId: String,
    val code: String,
    val observedAt: String,
    val message: String,
)

data class SafeSwapReceiptSummary(
    val sessionId: String,
    val volumeId: String,
    val generationId: String,
    val manifestId: String,
    val manifestSha256: String,
    val sealedAt: String,
    val releasedAt: String,
    val releaseState: String,
    val openHandleCount: Long = 0,
    val authorityEpoch: String? = null,
    val sourceRevision: Long? = null,
)

data class CameraFocusStatus(
    val value: Long,
    val minimum: Long,
    val maximum: Long,
    val step: Long,
    val default: Long,
    val autoSupported: Boolean,
    val autoEnabled: Boolean?,
)

data class DeviceSessionManifest(
    val manifestId: String,
    val sessionId: String,
    val displayName: String,
    val sealedAt: String,
    val captureMode: String,
    val artifacts: List<ArtifactDescriptor>,
)

data class ArtifactDescriptor(
    val artifactId: String,
    val role: String,
    val path: String,
    val mediaType: String,
    val bytes: Long,
    val sha256: String,
)

data class RetainedUnsuccessfulOutcome(
    val authorityEpoch: String,
    val sourceRevision: Long,
    val generationId: String,
    val state: String,
)

data class NetworkRuntimeStatus(
    val ap: NetworkInterfaceRuntime,
    val wifiClient: NetworkInterfaceRuntime,
    val wired: NetworkInterfaceRuntime,
    val defaultRoute: String,
) {
    companion object {
        fun unavailable(): NetworkRuntimeStatus {
            val unavailable = NetworkInterfaceRuntime(
                state = "unavailable",
                interfaceName = null,
                addresses = emptyList(),
                peerOrSsid = null,
            )
            return NetworkRuntimeStatus(
                ap = unavailable,
                wifiClient = unavailable,
                wired = unavailable,
                defaultRoute = "none",
            )
        }
    }
}

data class NetworkInterfaceRuntime(
    val state: String,
    val interfaceName: String?,
    val addresses: List<String>,
    val peerOrSsid: String?,
)

data class DeviceRuntime(
    val observedAt: String,
    val connectionMethod: String,
    val temperatureCelsius: Double,
    val network: NetworkRuntimeStatus = NetworkRuntimeStatus.unavailable(),
    val liveImuQuality: String? = null,
    val camera: CameraConnectionStatus = CameraConnectionStatus("disconnected"),
)

data class ApiError(
    val code: String,
    val message: String,
    val requestId: String,
    val retryable: Boolean,
)

data class CatalogChangedError(
    val catalogRevision: String,
)
