package com.openaria.openaria_echo_mobile.body.api

import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.MessageDigest

class DeviceHttpClient {
    fun getCaptureStatus(connection: DeviceConnection): CaptureStatusResult {
        val http = try {
            (connection.target.origin.resolve("/api/v4/capture/status").toURL().openConnection() as HttpURLConnection)
                .applyJsonRequest(connection)
        } catch (exception: IOException) {
            return CaptureStatusResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> parseCaptureStatus(http.inputStream.readBytes().decodeToString())
                HttpURLConnection.HTTP_UNAUTHORIZED -> CaptureStatusResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> CaptureStatusResult.Forbidden
                else -> CaptureStatusResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            CaptureStatusResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun readCaptureEvents(
        connection: DeviceConnection,
        lastEventId: String? = null,
        lastAuthorityEpoch: String? = null,
        lastSourceRevision: Long? = null,
        maxEvents: Int = 8,
    ): CaptureEventsResult {
        val eventLimit = maxEvents.coerceIn(1, 64)
        if (lastEventId != null && !lastEventId.matches(Regex("^[0-9]+$"))) {
            return CaptureEventsResult.InvalidRequest("Last-Event-ID must be decimal digits")
        }
        if (lastAuthorityEpoch != null && !isUuidV4(lastAuthorityEpoch)) {
            return CaptureEventsResult.InvalidRequest("lastAuthorityEpoch must be UUID v4")
        }
        if (lastSourceRevision != null && lastSourceRevision < 0L) {
            return CaptureEventsResult.InvalidRequest("lastSourceRevision must be non-negative")
        }

        val http = try {
            (connection.target.origin.resolve("/api/v4/capture/events").toURL().openConnection() as HttpURLConnection)
                .applyEventStreamRequest(connection, lastEventId)
        } catch (exception: IOException) {
            return CaptureEventsResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val contentType = http.contentType.orEmpty().substringBefore(";").trim().lowercase()
                    if (contentType != "text/event-stream") {
                        CaptureEventsResult.InvalidResponse("expected text/event-stream, got ${http.contentType.orEmpty()}")
                    } else {
                        http.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            parseCaptureEventStream(
                                reader = reader,
                                initialAuthorityEpoch = lastAuthorityEpoch,
                                initialSourceRevision = lastSourceRevision,
                                maxEvents = eventLimit,
                            )
                        }
                    }
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> CaptureEventsResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> CaptureEventsResult.Forbidden
                else -> CaptureEventsResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            CaptureEventsResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun getPreviewJpeg(connection: DeviceConnection, fps: Int = 2): PreviewResult {
        val clampedFps = fps.coerceAtLeast(1)
        val http = try {
            val path = "/api/v4/preview?fps=$clampedFps"
            (connection.target.origin.resolve(path).toURL().openConnection() as HttpURLConnection)
                .applyPreviewRequest(connection)
        } catch (exception: IOException) {
            return PreviewResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val contentType = http.contentType.orEmpty().substringBefore(";").trim().lowercase()
                    if (contentType != "image/jpeg") {
                        return PreviewResult.InvalidResponse("expected image/jpeg, got ${http.contentType.orEmpty()}")
                    }
                    val bytes = http.inputStream.readBytes()
                    if (!looksLikeJpeg(bytes)) {
                        return PreviewResult.InvalidResponse("response is not a JPEG frame")
                    }
                    PreviewResult.Frame(bytes)
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> PreviewResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> PreviewResult.Forbidden
                HttpURLConnection.HTTP_CONFLICT -> PreviewResult.Unavailable
                HttpURLConnection.HTTP_UNAVAILABLE -> parsePreviewUnavailable(http)
                else -> PreviewResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            PreviewResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun startCapture(connection: DeviceConnection, idempotencyKey: String): CaptureCommandResult {
        return postCaptureCommand(
            connection = connection,
            path = "/api/v4/capture/start",
            idempotencyKey = idempotencyKey,
            body = buildCaptureStartBody("production"),
        )
    }

    fun startCalibrationCapture(connection: DeviceConnection, idempotencyKey: String): CaptureCommandResult {
        return postCaptureCommand(
            connection = connection,
            path = "/api/v4/capture/start",
            idempotencyKey = idempotencyKey,
            body = buildCaptureStartBody("calibration"),
        )
    }

    fun stopCapture(connection: DeviceConnection, idempotencyKey: String): CaptureCommandResult {
        return stopCaptureWithReason(
            connection = connection,
            idempotencyKey = idempotencyKey,
            reason = "user",
        )
    }

    fun stopCaptureForSafeSwap(connection: DeviceConnection, idempotencyKey: String): CaptureCommandResult {
        return stopCaptureWithReason(
            connection = connection,
            idempotencyKey = idempotencyKey,
            reason = "safe_swap",
        )
    }

    private fun stopCaptureWithReason(
        connection: DeviceConnection,
        idempotencyKey: String,
        reason: String,
    ): CaptureCommandResult {
        return postCaptureCommand(
            connection = connection,
            path = "/api/v4/capture/stop",
            idempotencyKey = idempotencyKey,
            body = buildCaptureStopBody(reason),
        )
    }

    fun listSessions(
        connection: DeviceConnection,
        limit: Int = 50,
        cursor: String? = null,
        takeId: String? = null,
    ): SessionListResult {
        val clampedLimit = limit.coerceIn(1, 200)
        if (cursor != null && cursor.isBlank()) {
            return SessionListResult.InvalidRequest
        }
        if (takeId != null && !isUuidV7(takeId)) {
            return SessionListResult.InvalidRequest
        }
        val http = try {
            val query = buildListSessionsQuery(
                limit = clampedLimit,
                cursor = cursor,
                takeId = takeId,
            )
            val path = "/api/v4/sessions?$query"
            (connection.target.origin.resolve(path).toURL().openConnection() as HttpURLConnection)
                .applyJsonRequest(connection)
        } catch (exception: IOException) {
            return SessionListResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> parseSessionList(http.inputStream.readBytes().decodeToString())
                HttpURLConnection.HTTP_BAD_REQUEST -> SessionListResult.InvalidRequest
                HttpURLConnection.HTTP_UNAUTHORIZED -> SessionListResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> SessionListResult.Forbidden
                else -> SessionListResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            SessionListResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun getSafeSwapReceipt(connection: DeviceConnection): SafeSwapResult {
        val http = try {
            (connection.target.origin.resolve("/api/v4/capture/safe-swap").toURL().openConnection() as HttpURLConnection)
                .applyJsonRequest(connection)
        } catch (exception: IOException) {
            return SafeSwapResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> parseSafeSwapReceipt(http.inputStream.readBytes().decodeToString())
                HttpURLConnection.HTTP_UNAUTHORIZED -> SafeSwapResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> SafeSwapResult.Forbidden
                HttpURLConnection.HTTP_NOT_FOUND -> SafeSwapResult.NotFound
                else -> SafeSwapResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            SafeSwapResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun getSessionManifest(connection: DeviceConnection, sessionId: String): SessionManifestResult {
        if (!isUuidV7(sessionId)) {
            return SessionManifestResult.InvalidRequest("invalid session_id")
        }
        val http = try {
            (connection.target.origin.resolve("/api/v4/sessions/$sessionId").toURL().openConnection() as HttpURLConnection)
                .applyJsonRequest(connection)
        } catch (exception: IOException) {
            return SessionManifestResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> parseSessionManifest(http.inputStream.readBytes().decodeToString())
                HttpURLConnection.HTTP_UNAUTHORIZED -> SessionManifestResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> SessionManifestResult.Forbidden
                HttpURLConnection.HTTP_NOT_FOUND -> SessionManifestResult.NotFound
                else -> SessionManifestResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            SessionManifestResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun getRetainedUnsuccessfulOutcome(
        connection: DeviceConnection,
        sessionId: String,
    ): RetainedUnsuccessfulOutcomeResult {
        if (!isUuidV7(sessionId)) {
            return RetainedUnsuccessfulOutcomeResult.InvalidRequest("invalid session_id")
        }
        val http = try {
            (connection.target.origin.resolve("/api/v4/sessions/$sessionId/unsuccessful-outcome")
                .toURL()
                .openConnection() as HttpURLConnection)
                .applyJsonRequest(connection)
        } catch (exception: IOException) {
            return RetainedUnsuccessfulOutcomeResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> parseRetainedUnsuccessfulOutcome(http.inputStream.readBytes().decodeToString())
                HttpURLConnection.HTTP_UNAUTHORIZED -> RetainedUnsuccessfulOutcomeResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> RetainedUnsuccessfulOutcomeResult.Forbidden
                HttpURLConnection.HTTP_NOT_FOUND -> RetainedUnsuccessfulOutcomeResult.NotFound
                else -> RetainedUnsuccessfulOutcomeResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            RetainedUnsuccessfulOutcomeResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun downloadSessionArtifact(
        connection: DeviceConnection,
        sessionId: String,
        artifact: ArtifactDescriptor,
        output: OutputStream,
        resumeFromBytes: Long = 0L,
        shouldCancel: () -> Boolean = { false },
        onBytesWritten: (Long) -> Unit = {},
    ): ArtifactDownloadResult {
        if (!isUuidV7(sessionId)) {
            return ArtifactDownloadResult.InvalidRequest("invalid session_id")
        }
        if (resumeFromBytes < 0L || resumeFromBytes >= artifact.bytes) {
            return ArtifactDownloadResult.InvalidRequest("invalid resume offset")
        }
        val http = try {
            (connection.target.origin.resolve("/api/v4/sessions/$sessionId/artifacts/${artifact.artifactId}")
                .toURL()
                .openConnection() as HttpURLConnection)
                .applyArtifactRequest(
                    connection = connection,
                    resumeFromBytes = resumeFromBytes.takeIf { it > 0L },
                )
        } catch (exception: IOException) {
            return ArtifactDownloadResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    if (resumeFromBytes > 0L) {
                        ArtifactDownloadResult.InvalidResponse("expected HTTP 206 for resumed artifact download")
                    } else {
                        streamAndVerifyArtifact(
                            http = http,
                            artifact = artifact,
                            output = output,
                            resumeFromBytes = 0L,
                            shouldCancel = shouldCancel,
                            onBytesWritten = onBytesWritten,
                        )
                    }
                }
                HttpURLConnection.HTTP_PARTIAL -> streamAndVerifyArtifact(
                    http = http,
                    artifact = artifact,
                    output = output,
                    resumeFromBytes = resumeFromBytes,
                    shouldCancel = shouldCancel,
                    onBytesWritten = onBytesWritten,
                )
                HttpURLConnection.HTTP_UNAUTHORIZED -> ArtifactDownloadResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> ArtifactDownloadResult.Forbidden
                HttpURLConnection.HTTP_NOT_FOUND -> ArtifactDownloadResult.NotFound
                HttpURLConnection.HTTP_CONFLICT -> ArtifactDownloadResult.SessionNotVerified
                423 -> ArtifactDownloadResult.CaptureBusy
                416 -> ArtifactDownloadResult.RangeNotSatisfiable
                else -> ArtifactDownloadResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            ArtifactDownloadResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun headSessionArtifact(
        connection: DeviceConnection,
        sessionId: String,
        artifact: ArtifactDescriptor,
    ): ArtifactHeadResult {
        if (!isUuidV7(sessionId)) {
            return ArtifactHeadResult.InvalidRequest("invalid session_id")
        }
        val http = try {
            (connection.target.origin.resolve("/api/v4/sessions/$sessionId/artifacts/${artifact.artifactId}")
                .toURL()
                .openConnection() as HttpURLConnection)
                .applyArtifactHeadRequest(connection)
        } catch (exception: IOException) {
            return ArtifactHeadResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val headerError = validateArtifactRepresentationHeaders(
                        http = http,
                        artifact = artifact,
                        expectedContentLength = artifact.bytes,
                        resumeFromBytes = 0L,
                    )
                    if (headerError == null) {
                        ArtifactHeadResult.Verified
                    } else {
                        ArtifactHeadResult.InvalidResponse(headerError)
                    }
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> ArtifactHeadResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> ArtifactHeadResult.Forbidden
                HttpURLConnection.HTTP_NOT_FOUND -> ArtifactHeadResult.NotFound
                HttpURLConnection.HTTP_CONFLICT -> ArtifactHeadResult.SessionNotVerified
                423 -> ArtifactHeadResult.CaptureBusy
                else -> ArtifactHeadResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            ArtifactHeadResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun getCameraFocus(connection: DeviceConnection): CameraFocusResult {
        val http = try {
            (connection.target.origin.resolve("/api/v4/camera/focus").toURL().openConnection() as HttpURLConnection)
                .applyJsonRequest(connection)
        } catch (exception: IOException) {
            return CameraFocusResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> parseCameraFocus(http.inputStream.readBytes().decodeToString())
                HttpURLConnection.HTTP_UNAUTHORIZED -> CameraFocusResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> CameraFocusResult.Forbidden
                HttpURLConnection.HTTP_NOT_FOUND -> CameraFocusResult.Unsupported
                else -> CameraFocusResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            CameraFocusResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun setCameraFocus(
        connection: DeviceConnection,
        idempotencyKey: String,
        value: Long?,
        autoEnabled: Boolean?,
    ): CameraFocusResult {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            return CameraFocusResult.InvalidRequest("invalid Idempotency-Key")
        }
        if (value == null && autoEnabled == null) {
            return CameraFocusResult.InvalidRequest("value or auto_enabled is required")
        }
        if (value != null && value < 0L) {
            return CameraFocusResult.InvalidRequest("value must be non-negative")
        }
        val body = buildCameraFocusBody(value, autoEnabled)
        val http = try {
            (connection.target.origin.resolve("/api/v4/camera/focus").toURL().openConnection() as HttpURLConnection)
                .applyCaptureCommandRequest(connection, idempotencyKey)
        } catch (exception: IOException) {
            return CameraFocusResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            http.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> parseCameraFocus(http.inputStream.readBytes().decodeToString())
                HttpURLConnection.HTTP_BAD_REQUEST -> CameraFocusResult.InvalidRequest("bad request")
                HttpURLConnection.HTTP_UNAUTHORIZED -> CameraFocusResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> CameraFocusResult.Forbidden
                HttpURLConnection.HTTP_NOT_FOUND -> CameraFocusResult.Unsupported
                HttpURLConnection.HTTP_CONFLICT -> CameraFocusResult.Conflict
                422 -> CameraFocusResult.InvalidFocus
                else -> CameraFocusResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            CameraFocusResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun getNetworkStatus(connection: DeviceConnection): NetworkStatusResult {
        val http = try {
            (connection.target.origin.resolve("/api/v4/network").toURL().openConnection() as HttpURLConnection)
                .applyJsonRequest(connection)
        } catch (exception: IOException) {
            return NetworkStatusResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> parseNetworkStatus(http.inputStream.readBytes().decodeToString())
                HttpURLConnection.HTTP_UNAUTHORIZED -> NetworkStatusResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> NetworkStatusResult.Forbidden
                HttpURLConnection.HTTP_UNAVAILABLE -> NetworkStatusResult.Unavailable
                else -> NetworkStatusResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            NetworkStatusResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun scanNetworks(connection: DeviceConnection): NetworkScanResult {
        val http = try {
            (connection.target.origin.resolve("/api/v4/network/scan").toURL().openConnection() as HttpURLConnection)
                .applyJsonRequest(connection)
        } catch (exception: IOException) {
            return NetworkScanResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> parseNetworkScan(http.inputStream.readBytes().decodeToString())
                HttpURLConnection.HTTP_UNAUTHORIZED -> NetworkScanResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> NetworkScanResult.Forbidden
                HttpURLConnection.HTTP_UNAVAILABLE -> NetworkScanResult.Unavailable
                else -> NetworkScanResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            NetworkScanResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun createNetworkCredentialReference(
        connection: DeviceConnection,
        passphrase: String,
    ): NetworkCredentialResult {
        if (passphrase.length !in 8..63) {
            return NetworkCredentialResult.InvalidRequest("passphrase must be 8..63 characters")
        }
        val http = try {
            (connection.target.origin.resolve("/api/v4/network/credentials").toURL().openConnection() as HttpURLConnection)
                .applyNetworkPostRequest(connection, idempotencyKey = null)
        } catch (exception: IOException) {
            return NetworkCredentialResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            val body = """{"schema":"ylx.network-credential-request.v1","passphrase":${jsonString(passphrase)}}"""
            http.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_CREATED -> parseNetworkCredentialReceipt(
                    http.inputStream.readBytes().decodeToString(),
                )
                HttpURLConnection.HTTP_BAD_REQUEST -> NetworkCredentialResult.InvalidRequest("bad request")
                HttpURLConnection.HTTP_UNAUTHORIZED -> NetworkCredentialResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> NetworkCredentialResult.Forbidden
                HttpURLConnection.HTTP_UNAVAILABLE -> NetworkCredentialResult.MutationUnavailable
                else -> NetworkCredentialResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            NetworkCredentialResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    fun applyWifiClientNetwork(
        connection: DeviceConnection,
        idempotencyKey: String,
        ssid: String,
        security: String,
        credentialRef: String?,
    ): NetworkMutationResult {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            return NetworkMutationResult.InvalidRequest("invalid Idempotency-Key")
        }
        if (!isValidSsid(ssid)) {
            return NetworkMutationResult.InvalidRequest("invalid SSID")
        }
        if (!isNetworkWifiSecurity(security)) {
            return NetworkMutationResult.InvalidRequest("invalid security")
        }
        if (security == "open" && credentialRef != null) {
            return NetworkMutationResult.InvalidRequest("open network must not include credential_ref")
        }
        if (security != "open" && !isValidCredentialRef(credentialRef)) {
            return NetworkMutationResult.InvalidRequest("protected network requires credential_ref")
        }
        val credentialField = if (credentialRef == null) {
            ""
        } else {
            ""","credential_ref":${jsonString(credentialRef)}"""
        }
        return postNetworkMutation(
            connection = connection,
            path = "/api/v4/network/apply",
            idempotencyKey = idempotencyKey,
            body = """{"schema":"ylx.network-apply-request.v1","desired":{"mode":"wifi-client","wifi_client":{"ssid":${jsonString(ssid)},"security":${jsonString(security)}$credentialField},"ethernet":null}}""",
        )
    }

    fun applyHotspotNetwork(
        connection: DeviceConnection,
        idempotencyKey: String,
    ): NetworkMutationResult {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            return NetworkMutationResult.InvalidRequest("invalid Idempotency-Key")
        }
        return postNetworkMutation(
            connection = connection,
            path = "/api/v4/network/apply",
            idempotencyKey = idempotencyKey,
            body = """{"schema":"ylx.network-apply-request.v1","desired":{"mode":"hotspot","wifi_client":null,"ethernet":null}}""",
        )
    }

    fun applyEthernetDhcpNetwork(
        connection: DeviceConnection,
        idempotencyKey: String,
    ): NetworkMutationResult {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            return NetworkMutationResult.InvalidRequest("invalid Idempotency-Key")
        }
        return postNetworkMutation(
            connection = connection,
            path = "/api/v4/network/apply",
            idempotencyKey = idempotencyKey,
            body = """{"schema":"ylx.network-apply-request.v1","desired":{"mode":"ethernet-dhcp","wifi_client":null,"ethernet":{"addressing":"dhcp","static_ipv4":null}}}""",
        )
    }

    fun applyEthernetStaticNetwork(
        connection: DeviceConnection,
        idempotencyKey: String,
        address: String,
        prefixLength: Int,
        gateway: String?,
        dns: List<String>,
    ): NetworkMutationResult {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            return NetworkMutationResult.InvalidRequest("invalid Idempotency-Key")
        }
        if (!isIpv4(address)) {
            return NetworkMutationResult.InvalidRequest("invalid static IPv4 address")
        }
        if (prefixLength !in 1..32) {
            return NetworkMutationResult.InvalidRequest("invalid static IPv4 prefix length")
        }
        if (gateway != null && !isIpv4(gateway)) {
            return NetworkMutationResult.InvalidRequest("invalid static IPv4 gateway")
        }
        if (dns.size > 3 || dns.distinct().size != dns.size || dns.any { !isIpv4(it) }) {
            return NetworkMutationResult.InvalidRequest("invalid static IPv4 DNS list")
        }
        return postNetworkMutation(
            connection = connection,
            path = "/api/v4/network/apply",
            idempotencyKey = idempotencyKey,
            body = buildEthernetStaticApplyBody(
                address = address,
                prefixLength = prefixLength,
                gateway = gateway,
                dns = dns,
            ),
        )
    }

    fun retryNetworkTransaction(
        connection: DeviceConnection,
        idempotencyKey: String,
        transactionId: String,
    ): NetworkMutationResult {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            return NetworkMutationResult.InvalidRequest("invalid Idempotency-Key")
        }
        if (!isUuidV7(transactionId)) {
            return NetworkMutationResult.InvalidRequest("invalid transaction_id")
        }
        return postNetworkMutation(
            connection = connection,
            path = "/api/v4/network/retry",
            idempotencyKey = idempotencyKey,
            body = """{"schema":"ylx.network-retry-request.v1","transaction_id":${jsonString(transactionId)}}""",
        )
    }

    fun forgetNetworkClientProfile(
        connection: DeviceConnection,
        idempotencyKey: String,
    ): NetworkMutationResult {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            return NetworkMutationResult.InvalidRequest("invalid Idempotency-Key")
        }
        return postNetworkMutation(
            connection = connection,
            path = "/api/v4/network/forget",
            idempotencyKey = idempotencyKey,
            body = """{"schema":"ylx.network-forget-request.v1"}""",
        )
    }

    fun readNetworkEvents(
        connection: DeviceConnection,
        lastEventId: String? = null,
        lastAuthorityEpoch: String? = null,
        lastSourceRevision: Long? = null,
        maxEvents: Int = 8,
    ): NetworkEventsResult {
        val eventLimit = maxEvents.coerceIn(1, 64)
        if (lastEventId != null && !lastEventId.matches(Regex("^[0-9]+$"))) {
            return NetworkEventsResult.InvalidRequest("Last-Event-ID must be decimal digits")
        }
        if (lastAuthorityEpoch != null && !isUuidV4(lastAuthorityEpoch)) {
            return NetworkEventsResult.InvalidRequest("lastAuthorityEpoch must be UUID v4")
        }
        if (lastSourceRevision != null && lastSourceRevision < 0L) {
            return NetworkEventsResult.InvalidRequest("lastSourceRevision must be non-negative")
        }
        val http = try {
            (connection.target.origin.resolve("/api/v4/network/events").toURL().openConnection() as HttpURLConnection)
                .applyEventStreamRequest(connection, lastEventId)
        } catch (exception: IOException) {
            return NetworkEventsResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val contentType = http.contentType.orEmpty().substringBefore(";").trim().lowercase()
                    if (contentType != "text/event-stream") {
                        NetworkEventsResult.InvalidResponse("expected text/event-stream, got ${http.contentType.orEmpty()}")
                    } else {
                        http.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            parseNetworkEventStream(
                                reader = reader,
                                initialAuthorityEpoch = lastAuthorityEpoch,
                                initialSourceRevision = lastSourceRevision,
                                maxEvents = eventLimit,
                            )
                        }
                    }
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> NetworkEventsResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> NetworkEventsResult.Forbidden
                else -> NetworkEventsResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            NetworkEventsResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    private fun postCaptureCommand(
        connection: DeviceConnection,
        path: String,
        idempotencyKey: String,
        body: String,
    ): CaptureCommandResult {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            return CaptureCommandResult.InvalidRequest("invalid Idempotency-Key")
        }
        val http = try {
            (connection.target.origin.resolve(path).toURL().openConnection() as HttpURLConnection)
                .applyCaptureCommandRequest(connection, idempotencyKey)
        } catch (exception: IOException) {
            return CaptureCommandResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            http.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_ACCEPTED -> when (val parsed = parseCaptureStatus(http.inputStream.readBytes().decodeToString())) {
                    is CaptureStatusResult.Snapshot -> CaptureCommandResult.Accepted(parsed.value)
                    CaptureStatusResult.AuthenticationRequired -> CaptureCommandResult.AuthenticationRequired
                    CaptureStatusResult.Forbidden -> CaptureCommandResult.Forbidden
                    is CaptureStatusResult.HttpFailure -> CaptureCommandResult.HttpFailure(parsed.statusCode)
                    is CaptureStatusResult.InvalidResponse -> CaptureCommandResult.InvalidResponse(parsed.message)
                    is CaptureStatusResult.NetworkFailure -> CaptureCommandResult.NetworkFailure(parsed.message)
                }
                HttpURLConnection.HTTP_NO_CONTENT -> CaptureCommandResult.NoActiveSession
                HttpURLConnection.HTTP_BAD_REQUEST -> CaptureCommandResult.InvalidRequest("bad request")
                HttpURLConnection.HTTP_UNAUTHORIZED -> CaptureCommandResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> CaptureCommandResult.Forbidden
                HttpURLConnection.HTTP_CONFLICT -> CaptureCommandResult.Conflict
                422 -> CaptureCommandResult.Unprocessable
                else -> CaptureCommandResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            CaptureCommandResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    private fun parseCaptureStatus(body: String): CaptureStatusResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return CaptureStatusResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateCaptureStatusSnapshot(root)) {
            is Validation.Valid -> CaptureStatusResult.Snapshot(validation.value)
            is Validation.Invalid -> CaptureStatusResult.InvalidResponse(validation.message)
        }
    }

    private fun parsePreviewUnavailable(http: HttpURLConnection): PreviewResult {
        val headerCode = http.getHeaderField("YLX-Error-Code")?.trim()?.takeIf { it.isNotEmpty() }
        val code = headerCode ?: when (val parsed = parseApiErrorCode(http.errorStream?.readBytes()?.decodeToString().orEmpty())) {
            is Validation.Valid -> parsed.value
            is Validation.Invalid -> return PreviewResult.InvalidResponse(parsed.message)
        }
        return when (code) {
            "camera_not_connected" -> PreviewResult.CameraNotConnected
            "preview_unavailable" -> PreviewResult.NoFrame
            else -> PreviewResult.InvalidResponse("unsupported preview error code: $code")
        }
    }

    private fun parseApiErrorCode(body: String): Validation<String> {
        if (body.isBlank()) {
            return Validation.Invalid("preview 503 response is missing ylx.api-error.v2 body")
        }
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return Validation.Invalid(json.message)
        }
        return when (val validation = DeviceApiValidators.validateErrorResponse(root)) {
            is Validation.Valid -> Validation.Valid(validation.value.code)
            is Validation.Invalid -> Validation.Invalid(validation.message)
        }
    }

    private fun parseSessionList(body: String): SessionListResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return SessionListResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateSessionList(root)) {
            is Validation.Valid -> SessionListResult.Page(validation.value)
            is Validation.Invalid -> SessionListResult.InvalidResponse(validation.message)
        }
    }

    private fun parseSafeSwapReceipt(body: String): SafeSwapResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return SafeSwapResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateSafeSwapReceiptResource(root)) {
            is Validation.Valid -> SafeSwapResult.Receipt(validation.value)
            is Validation.Invalid -> SafeSwapResult.InvalidResponse(validation.message)
        }
    }

    private fun parseSessionManifest(body: String): SessionManifestResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return SessionManifestResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateDeviceSessionManifest(root)) {
            is Validation.Valid -> SessionManifestResult.Manifest(validation.value)
            is Validation.Invalid -> SessionManifestResult.InvalidResponse(validation.message)
        }
    }

    private fun parseRetainedUnsuccessfulOutcome(body: String): RetainedUnsuccessfulOutcomeResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return RetainedUnsuccessfulOutcomeResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateRetainedUnsuccessfulSessionResource(root)) {
            is Validation.Valid -> RetainedUnsuccessfulOutcomeResult.Outcome(validation.value)
            is Validation.Invalid -> RetainedUnsuccessfulOutcomeResult.InvalidResponse(validation.message)
        }
    }

    private fun parseCameraFocus(body: String): CameraFocusResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return CameraFocusResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateCameraFocusStatus(root)) {
            is Validation.Valid -> CameraFocusResult.Status(validation.value)
            is Validation.Invalid -> CameraFocusResult.InvalidResponse(validation.message)
        }
    }

    private fun parseNetworkStatus(body: String): NetworkStatusResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return NetworkStatusResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateNetworkStatus(root)) {
            is Validation.Valid -> NetworkStatusResult.Status(validation.value)
            is Validation.Invalid -> NetworkStatusResult.InvalidResponse(validation.message)
        }
    }

    private fun parseNetworkScan(body: String): NetworkScanResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return NetworkScanResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateNetworkScan(root)) {
            is Validation.Valid -> NetworkScanResult.Scan(validation.value)
            is Validation.Invalid -> NetworkScanResult.InvalidResponse(validation.message)
        }
    }

    private fun parseNetworkCredentialReceipt(body: String): NetworkCredentialResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return NetworkCredentialResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateNetworkCredentialReceipt(root)) {
            is Validation.Valid -> NetworkCredentialResult.Receipt(validation.value)
            is Validation.Invalid -> NetworkCredentialResult.InvalidResponse(validation.message)
        }
    }

    private fun parseNetworkTransactionReceipt(body: String): NetworkMutationResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return NetworkMutationResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateNetworkTransactionReceipt(root)) {
            is Validation.Valid -> NetworkMutationResult.Accepted(validation.value)
            is Validation.Invalid -> NetworkMutationResult.InvalidResponse(validation.message)
        }
    }

    private fun postNetworkMutation(
        connection: DeviceConnection,
        path: String,
        idempotencyKey: String,
        body: String,
    ): NetworkMutationResult {
        val http = try {
            (connection.target.origin.resolve(path).toURL().openConnection() as HttpURLConnection)
                .applyNetworkPostRequest(connection, idempotencyKey)
        } catch (exception: IOException) {
            return NetworkMutationResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return try {
            http.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            when (val status = http.responseCode) {
                HttpURLConnection.HTTP_ACCEPTED -> parseNetworkTransactionReceipt(
                    http.inputStream.readBytes().decodeToString(),
                )
                HttpURLConnection.HTTP_BAD_REQUEST -> NetworkMutationResult.InvalidRequest("bad request")
                HttpURLConnection.HTTP_UNAUTHORIZED -> NetworkMutationResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> NetworkMutationResult.Forbidden
                HttpURLConnection.HTTP_NOT_FOUND -> NetworkMutationResult.NotFound
                HttpURLConnection.HTTP_CONFLICT -> NetworkMutationResult.IdempotencyConflict
                422 -> NetworkMutationResult.InvalidDesiredState
                HttpURLConnection.HTTP_UNAVAILABLE -> NetworkMutationResult.MutationUnavailable
                else -> NetworkMutationResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            NetworkMutationResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            http.disconnect()
        }
    }

    private fun HttpURLConnection.applyJsonRequest(connection: DeviceConnection): HttpURLConnection {
        connectTimeout = 5_000
        readTimeout = 8_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
        setBearerToken(connection)
        return this
    }

    private fun HttpURLConnection.applyNetworkPostRequest(
        connection: DeviceConnection,
        idempotencyKey: String?,
    ): HttpURLConnection {
        connectTimeout = 5_000
        readTimeout = 8_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json")
        if (idempotencyKey != null) {
            setRequestProperty("Idempotency-Key", idempotencyKey)
        }
        setBearerToken(connection)
        return this
    }

    private fun HttpURLConnection.applyEventStreamRequest(
        connection: DeviceConnection,
        lastEventId: String?,
    ): HttpURLConnection {
        connectTimeout = 5_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("Accept", "text/event-stream")
        setRequestProperty("Cache-Control", "no-cache")
        if (lastEventId != null) {
            setRequestProperty("Last-Event-ID", lastEventId)
        }
        setBearerToken(connection)
        return this
    }

    private fun HttpURLConnection.applyPreviewRequest(connection: DeviceConnection): HttpURLConnection {
        connectTimeout = 5_000
        readTimeout = 5_000
        requestMethod = "GET"
        setRequestProperty("Accept", "image/jpeg")
        setRequestProperty("Cache-Control", "no-store")
        setBearerToken(connection)
        return this
    }

    private fun HttpURLConnection.applyCaptureCommandRequest(
        connection: DeviceConnection,
        idempotencyKey: String,
    ): HttpURLConnection {
        connectTimeout = 5_000
        readTimeout = 8_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Idempotency-Key", idempotencyKey)
        setBearerToken(connection)
        return this
    }

    private fun HttpURLConnection.applyArtifactRequest(
        connection: DeviceConnection,
        resumeFromBytes: Long? = null,
    ): HttpURLConnection {
        connectTimeout = 5_000
        readTimeout = 30_000
        requestMethod = "GET"
        setRequestProperty("Accept", "*/*")
        if (resumeFromBytes != null) {
            setRequestProperty("Range", "bytes=$resumeFromBytes-")
            setRequestProperty("If-Range", "\"${url.path.substringAfterLast("/")}\"")
        }
        setBearerToken(connection)
        return this
    }

    private fun HttpURLConnection.applyArtifactHeadRequest(connection: DeviceConnection): HttpURLConnection {
        connectTimeout = 5_000
        readTimeout = 8_000
        requestMethod = "HEAD"
        setRequestProperty("Accept", "*/*")
        setBearerToken(connection)
        return this
    }

    private fun HttpURLConnection.setBearerToken(connection: DeviceConnection) {
        if (!connection.bearerToken.isNullOrBlank()) {
            setRequestProperty("Authorization", "Bearer ${connection.bearerToken.trim()}")
        }
    }

    private fun looksLikeJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xFF.toByte() &&
            bytes[bytes.lastIndex] == 0xD9.toByte()
    }

    private fun isValidIdempotencyKey(value: String): Boolean {
        return value.length in 1..128 && value.all { it.code in 0x21..0x7e }
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

    private fun isValidSsid(value: String): Boolean {
        return value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 32
    }

    private fun isNetworkWifiSecurity(value: String): Boolean {
        return value in setOf("open", "wpa2-personal", "wpa3-personal", "wpa2-wpa3-personal")
    }

    private fun isValidCredentialRef(value: String?): Boolean {
        return value != null &&
            value.length in 1..128 &&
            value.matches(Regex("^cred-[A-Za-z0-9_.:-]+$"))
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it.isDigit() } &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun jsonString(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        builder.append("\\u")
                        builder.append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(character)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    private fun buildCameraFocusBody(value: Long?, autoEnabled: Boolean?): String {
        val fields = mutableListOf("\"schema\":\"ylx.camera-focus-set.v1\"")
        if (value != null) {
            fields += "\"value\":$value"
        }
        if (autoEnabled != null) {
            fields += "\"auto_enabled\":$autoEnabled"
        }
        return fields.joinToString(prefix = "{", postfix = "}")
    }

    private fun buildCaptureStartBody(mode: String): String {
        return """{"schema":"ylx.capture-start.v2","mode":${jsonString(mode)},"take":{"kind":"new"}}"""
    }

    private fun buildCaptureStopBody(reason: String): String {
        return """{"schema":"ylx.capture-stop.v2","reason":${jsonString(reason)}}"""
    }

    private fun buildEthernetStaticApplyBody(
        address: String,
        prefixLength: Int,
        gateway: String?,
        dns: List<String>,
    ): String {
        val gatewayJson = gateway?.let(::jsonString) ?: "null"
        val dnsJson = dns.joinToString(separator = ",", prefix = "[", postfix = "]") { jsonString(it) }
        return """{"schema":"ylx.network-apply-request.v1","desired":{"mode":"ethernet-static","wifi_client":null,"ethernet":{"addressing":"static","static_ipv4":{"address":${jsonString(address)},"prefix_length":$prefixLength,"gateway":$gatewayJson,"dns":$dnsJson}}}}"""
    }

    private fun buildListSessionsQuery(
        limit: Int,
        cursor: String?,
        takeId: String?,
    ): String {
        val fields = mutableListOf("limit=$limit")
        if (cursor != null) {
            fields += "cursor=${urlEncode(cursor)}"
        }
        if (takeId != null) {
            fields += "take_id=$takeId"
        }
        return fields.joinToString("&")
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun parseCaptureEventStream(
        reader: java.io.BufferedReader,
        initialAuthorityEpoch: String?,
        initialSourceRevision: Long?,
        maxEvents: Int,
    ): CaptureEventsResult {
        val events = mutableListOf<CaptureStreamEvent>()
        var lastProcessedEventId: String? = null
        var currentAuthorityEpoch = initialAuthorityEpoch
        var currentSourceRevision = initialSourceRevision
        var sseId: String? = null
        var eventName: String? = null
        val dataLines = mutableListOf<String>()

        fun clearPendingEvent() {
            sseId = null
            eventName = null
            dataLines.clear()
        }

        fun dispatchPendingEvent(): CaptureEventsResult? {
            if (sseId == null && eventName == null && dataLines.isEmpty()) {
                clearPendingEvent()
                return null
            }
            if (eventName == "heartbeat" && sseId == null && dataLines.isEmpty()) {
                clearPendingEvent()
                return null
            }
            val id = sseId ?: return CaptureEventsResult.InvalidResponse("SSE event id is required")
            val name = eventName ?: return CaptureEventsResult.InvalidResponse("SSE event name is required")
            if (dataLines.isEmpty()) {
                return CaptureEventsResult.InvalidResponse("SSE event data is required")
            }
            val rawData = dataLines.joinToString("\n")
            val root = when (val json = DeviceJsonPayload.parseObject(rawData)) {
                is DeviceJsonPayload.Result.Parsed -> json.value
                is DeviceJsonPayload.Result.Invalid -> {
                    return CaptureEventsResult.InvalidResponse("SSE data.${json.message}")
                }
            }
            val payload = when (val validation = DeviceApiValidators.validateCaptureEvent(root)) {
                is Validation.Valid -> validation.value
                is Validation.Invalid -> return CaptureEventsResult.InvalidResponse("SSE data.${validation.message}")
            }
            if (payload.sseDeliveryId != id) {
                return CaptureEventsResult.InvalidResponse("SSE id must match CaptureEvent.sse_delivery_id")
            }
            if (payload.type != name) {
                return CaptureEventsResult.InvalidResponse("SSE event name must match CaptureEvent.type")
            }

            val relation = captureRevisionRelation(
                previousAuthorityEpoch = currentAuthorityEpoch,
                previousSourceRevision = currentSourceRevision,
                nextAuthorityEpoch = payload.authorityEpoch,
                nextSourceRevision = payload.sourceRevision,
            )
            events += CaptureStreamEvent(
                sseDeliveryId = payload.sseDeliveryId,
                authorityEpoch = payload.authorityEpoch,
                sourceRevision = payload.sourceRevision,
                type = payload.type,
                occurredAt = payload.occurredAt,
                sessionId = payload.sessionId,
                revisionRelation = relation,
                snapshot = payload.snapshot,
                safeSwapReceipt = payload.safeSwapReceipt,
            )
            lastProcessedEventId = id
            currentAuthorityEpoch = payload.authorityEpoch
            currentSourceRevision = payload.sourceRevision
            clearPendingEvent()
            return if (events.size >= maxEvents) {
                CaptureEventsResult.Batch(events, lastProcessedEventId)
            } else {
                null
            }
        }

        while (events.size < maxEvents) {
            val line = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                break
            }
            if (line == null) {
                dispatchPendingEvent()?.let { return it }
                break
            }
            if (line.isEmpty()) {
                dispatchPendingEvent()?.let { return it }
                continue
            }
            if (line.startsWith(":")) {
                continue
            }

            val delimiter = line.indexOf(':')
            val field = if (delimiter == -1) line else line.substring(0, delimiter)
            var value = if (delimiter == -1) "" else line.substring(delimiter + 1)
            if (value.startsWith(" ")) {
                value = value.substring(1)
            }
            when (field) {
                "id" -> sseId = value
                "event" -> eventName = value
                "data" -> dataLines += value
                "retry" -> Unit
            }
        }

        return CaptureEventsResult.Batch(events, lastProcessedEventId)
    }

    private fun parseNetworkEventStream(
        reader: java.io.BufferedReader,
        initialAuthorityEpoch: String?,
        initialSourceRevision: Long?,
        maxEvents: Int,
    ): NetworkEventsResult {
        val events = mutableListOf<NetworkStreamEvent>()
        var lastProcessedEventId: String? = null
        var currentAuthorityEpoch = initialAuthorityEpoch
        var currentSourceRevision = initialSourceRevision
        var sseId: String? = null
        var eventName: String? = null
        val dataLines = mutableListOf<String>()

        fun clearPendingEvent() {
            sseId = null
            eventName = null
            dataLines.clear()
        }

        fun dispatchPendingEvent(): NetworkEventsResult? {
            if (sseId == null && eventName == null && dataLines.isEmpty()) {
                clearPendingEvent()
                return null
            }
            val id = sseId ?: return NetworkEventsResult.InvalidResponse("SSE event id is required")
            val name = eventName ?: return NetworkEventsResult.InvalidResponse("SSE event name is required")
            if (dataLines.isEmpty()) {
                return NetworkEventsResult.InvalidResponse("SSE event data is required")
            }
            val rawData = dataLines.joinToString("\n")
            val root = when (val json = DeviceJsonPayload.parseObject(rawData)) {
                is DeviceJsonPayload.Result.Parsed -> json.value
                is DeviceJsonPayload.Result.Invalid -> {
                    return NetworkEventsResult.InvalidResponse("SSE data.${json.message}")
                }
            }
            val payload = when (val validation = DeviceApiValidators.validateNetworkEvent(root)) {
                is Validation.Valid -> validation.value
                is Validation.Invalid -> return NetworkEventsResult.InvalidResponse("SSE data.${validation.message}")
            }
            if (payload.sseDeliveryId != id) {
                return NetworkEventsResult.InvalidResponse("SSE id must match NetworkEvent.sse_delivery_id")
            }
            if (payload.type != name) {
                return NetworkEventsResult.InvalidResponse("SSE event name must match NetworkEvent.type")
            }

            val relation = networkRevisionRelation(
                previousAuthorityEpoch = currentAuthorityEpoch,
                previousSourceRevision = currentSourceRevision,
                nextAuthorityEpoch = payload.authorityEpoch,
                nextSourceRevision = payload.sourceRevision,
            )
            events += NetworkStreamEvent(
                sseDeliveryId = payload.sseDeliveryId,
                authorityEpoch = payload.authorityEpoch,
                sourceRevision = payload.sourceRevision,
                occurredAt = payload.occurredAt,
                type = payload.type,
                transactionId = payload.transactionId,
                revisionRelation = relation,
                status = payload.status,
                transaction = payload.transaction,
            )
            lastProcessedEventId = id
            currentAuthorityEpoch = payload.authorityEpoch
            currentSourceRevision = payload.sourceRevision
            clearPendingEvent()
            return if (events.size >= maxEvents) {
                NetworkEventsResult.Batch(events, lastProcessedEventId)
            } else {
                null
            }
        }

        while (events.size < maxEvents) {
            val line = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                break
            }
            if (line == null) {
                dispatchPendingEvent()?.let { return it }
                break
            }
            if (line.isEmpty()) {
                dispatchPendingEvent()?.let { return it }
                continue
            }
            if (line.startsWith(":")) {
                continue
            }

            val delimiter = line.indexOf(':')
            val field = if (delimiter == -1) line else line.substring(0, delimiter)
            var value = if (delimiter == -1) "" else line.substring(delimiter + 1)
            if (value.startsWith(" ")) {
                value = value.substring(1)
            }
            when (field) {
                "id" -> sseId = value
                "event" -> eventName = value
                "data" -> dataLines += value
                "retry" -> Unit
            }
        }

        return NetworkEventsResult.Batch(events, lastProcessedEventId)
    }

    private fun captureRevisionRelation(
        previousAuthorityEpoch: String?,
        previousSourceRevision: Long?,
        nextAuthorityEpoch: String,
        nextSourceRevision: Long,
    ): CaptureRevisionRelation {
        if (previousAuthorityEpoch == null || previousSourceRevision == null) {
            return CaptureRevisionRelation.Initial
        }
        if (previousAuthorityEpoch != nextAuthorityEpoch) {
            return CaptureRevisionRelation.NewEpoch
        }
        return when {
            nextSourceRevision == previousSourceRevision + 1L -> CaptureRevisionRelation.Next
            nextSourceRevision <= previousSourceRevision -> CaptureRevisionRelation.Stale
            else -> CaptureRevisionRelation.Gap
        }
    }

    private fun networkRevisionRelation(
        previousAuthorityEpoch: String?,
        previousSourceRevision: Long?,
        nextAuthorityEpoch: String,
        nextSourceRevision: Long,
    ): NetworkRevisionRelation {
        if (previousAuthorityEpoch == null || previousSourceRevision == null) {
            return NetworkRevisionRelation.Initial
        }
        if (previousAuthorityEpoch != nextAuthorityEpoch) {
            return NetworkRevisionRelation.NewEpoch
        }
        return when {
            nextSourceRevision == previousSourceRevision + 1L -> NetworkRevisionRelation.Next
            nextSourceRevision <= previousSourceRevision -> NetworkRevisionRelation.Stale
            else -> NetworkRevisionRelation.Gap
        }
    }

    private fun streamAndVerifyArtifact(
        http: HttpURLConnection,
        artifact: ArtifactDescriptor,
        output: OutputStream,
        resumeFromBytes: Long,
        shouldCancel: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
    ): ArtifactDownloadResult {
        val expectedContentLength = artifact.bytes - resumeFromBytes
        validateArtifactRepresentationHeaders(
            http = http,
            artifact = artifact,
            expectedContentLength = expectedContentLength,
            resumeFromBytes = resumeFromBytes,
        )?.let { return ArtifactDownloadResult.InvalidResponse(it) }
        if (shouldCancel()) {
            return ArtifactDownloadResult.Cancelled(0L)
        }

        val digest = if (resumeFromBytes == 0L) MessageDigest.getInstance("SHA-256") else null
        var written = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        http.inputStream.use { input ->
            while (true) {
                if (shouldCancel()) {
                    return ArtifactDownloadResult.Cancelled(written)
                }
                val read = input.read(buffer)
                if (read == -1) break
                digest?.update(buffer, 0, read)
                output.write(buffer, 0, read)
                written += read
                onBytesWritten(resumeFromBytes + written)
            }
        }
        output.flush()
        if (written != expectedContentLength) {
            return ArtifactDownloadResult.InvalidResponse("expected $expectedContentLength bytes, wrote $written")
        }
        if (digest != null) {
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != artifact.sha256) {
                return ArtifactDownloadResult.IntegrityFailure(actualSha256)
            }
        }
        return ArtifactDownloadResult.Downloaded(written)
    }

    private fun validateArtifactRepresentationHeaders(
        http: HttpURLConnection,
        artifact: ArtifactDescriptor,
        expectedContentLength: Long,
        resumeFromBytes: Long,
    ): String? {
        val contentType = http.contentType.orEmpty().substringBefore(";").trim().lowercase()
        if (contentType != artifact.mediaType.lowercase()) {
            return "expected ${artifact.mediaType}, got ${http.contentType.orEmpty()}"
        }
        val expectedEtag = "\"${artifact.artifactId}\""
        val etag = http.getHeaderField("ETag")
        if (etag != expectedEtag) {
            return "expected ETag $expectedEtag, got ${etag.orEmpty()}"
        }
        val acceptRanges = http.getHeaderField("Accept-Ranges").orEmpty().trim().lowercase()
        if (acceptRanges != "bytes") {
            return "expected Accept-Ranges bytes, got $acceptRanges"
        }
        val contentLength = http.getHeaderFieldLong("Content-Length", -1L)
        if (contentLength != expectedContentLength) {
            return "expected $expectedContentLength bytes, got $contentLength"
        }
        if (resumeFromBytes > 0L) {
            val expectedContentRange = "bytes $resumeFromBytes-${artifact.bytes - 1}/${artifact.bytes}"
            val contentRange = http.getHeaderField("Content-Range").orEmpty()
            if (contentRange != expectedContentRange) {
                return "expected Content-Range $expectedContentRange, got $contentRange"
            }
        }
        return null
    }
}

sealed interface CaptureStatusResult {
    data class Snapshot(val value: CaptureStatusSnapshot) : CaptureStatusResult
    data object AuthenticationRequired : CaptureStatusResult
    data object Forbidden : CaptureStatusResult
    data class InvalidResponse(val message: String) : CaptureStatusResult
    data class NetworkFailure(val message: String) : CaptureStatusResult
    data class HttpFailure(val statusCode: Int) : CaptureStatusResult
}

sealed interface CaptureEventsResult {
    data class Batch(val events: List<CaptureStreamEvent>, val lastEventId: String?) : CaptureEventsResult
    data object AuthenticationRequired : CaptureEventsResult
    data object Forbidden : CaptureEventsResult
    data class InvalidRequest(val message: String) : CaptureEventsResult
    data class InvalidResponse(val message: String) : CaptureEventsResult
    data class NetworkFailure(val message: String) : CaptureEventsResult
    data class HttpFailure(val statusCode: Int) : CaptureEventsResult
}

data class CaptureStreamEvent(
    val sseDeliveryId: String,
    val authorityEpoch: String,
    val sourceRevision: Long,
    val type: String,
    val occurredAt: String,
    val sessionId: String?,
    val revisionRelation: CaptureRevisionRelation,
    val snapshot: CaptureStatusSnapshot?,
    val safeSwapReceipt: SafeSwapReceiptSummary?,
) {
    val requiresHttpReconciliation: Boolean
        get() = revisionRelation in setOf(
            CaptureRevisionRelation.Gap,
            CaptureRevisionRelation.Stale,
            CaptureRevisionRelation.NewEpoch,
        )
}

enum class CaptureRevisionRelation {
    Initial,
    Next,
    Gap,
    Stale,
    NewEpoch,
}

sealed interface PreviewResult {
    data class Frame(val bytes: ByteArray) : PreviewResult
    data object AuthenticationRequired : PreviewResult
    data object Forbidden : PreviewResult
    data object CameraNotConnected : PreviewResult
    data object Unavailable : PreviewResult
    data object NoFrame : PreviewResult
    data class InvalidResponse(val message: String) : PreviewResult
    data class NetworkFailure(val message: String) : PreviewResult
    data class HttpFailure(val statusCode: Int) : PreviewResult
}

sealed interface CaptureCommandResult {
    data class Accepted(val value: CaptureStatusSnapshot) : CaptureCommandResult
    data object NoActiveSession : CaptureCommandResult
    data object AuthenticationRequired : CaptureCommandResult
    data object Forbidden : CaptureCommandResult
    data object Conflict : CaptureCommandResult
    data object Unprocessable : CaptureCommandResult
    data class InvalidRequest(val message: String) : CaptureCommandResult
    data class InvalidResponse(val message: String) : CaptureCommandResult
    data class NetworkFailure(val message: String) : CaptureCommandResult
    data class HttpFailure(val statusCode: Int) : CaptureCommandResult
}

sealed interface SessionListResult {
    data class Page(val value: SessionListPage) : SessionListResult
    data object InvalidRequest : SessionListResult
    data object AuthenticationRequired : SessionListResult
    data object Forbidden : SessionListResult
    data class InvalidResponse(val message: String) : SessionListResult
    data class NetworkFailure(val message: String) : SessionListResult
    data class HttpFailure(val statusCode: Int) : SessionListResult
}

sealed interface SafeSwapResult {
    data class Receipt(val value: SafeSwapReceiptSummary) : SafeSwapResult
    data object NotFound : SafeSwapResult
    data object AuthenticationRequired : SafeSwapResult
    data object Forbidden : SafeSwapResult
    data class InvalidResponse(val message: String) : SafeSwapResult
    data class NetworkFailure(val message: String) : SafeSwapResult
    data class HttpFailure(val statusCode: Int) : SafeSwapResult
}

sealed interface SessionManifestResult {
    data class Manifest(val value: DeviceSessionManifest) : SessionManifestResult
    data object NotFound : SessionManifestResult
    data object AuthenticationRequired : SessionManifestResult
    data object Forbidden : SessionManifestResult
    data class InvalidRequest(val message: String) : SessionManifestResult
    data class InvalidResponse(val message: String) : SessionManifestResult
    data class NetworkFailure(val message: String) : SessionManifestResult
    data class HttpFailure(val statusCode: Int) : SessionManifestResult
}

sealed interface RetainedUnsuccessfulOutcomeResult {
    data class Outcome(val value: RetainedUnsuccessfulOutcome) : RetainedUnsuccessfulOutcomeResult
    data object NotFound : RetainedUnsuccessfulOutcomeResult
    data object AuthenticationRequired : RetainedUnsuccessfulOutcomeResult
    data object Forbidden : RetainedUnsuccessfulOutcomeResult
    data class InvalidRequest(val message: String) : RetainedUnsuccessfulOutcomeResult
    data class InvalidResponse(val message: String) : RetainedUnsuccessfulOutcomeResult
    data class NetworkFailure(val message: String) : RetainedUnsuccessfulOutcomeResult
    data class HttpFailure(val statusCode: Int) : RetainedUnsuccessfulOutcomeResult
}

sealed interface ArtifactDownloadResult {
    data class Downloaded(val bytes: Long) : ArtifactDownloadResult
    data class Cancelled(val bytes: Long) : ArtifactDownloadResult
    data object AuthenticationRequired : ArtifactDownloadResult
    data object Forbidden : ArtifactDownloadResult
    data object NotFound : ArtifactDownloadResult
    data object SessionNotVerified : ArtifactDownloadResult
    data object CaptureBusy : ArtifactDownloadResult
    data object RangeNotSatisfiable : ArtifactDownloadResult
    data class InvalidRequest(val message: String) : ArtifactDownloadResult
    data class InvalidResponse(val message: String) : ArtifactDownloadResult
    data class IntegrityFailure(val actualSha256: String) : ArtifactDownloadResult
    data class NetworkFailure(val message: String) : ArtifactDownloadResult
    data class HttpFailure(val statusCode: Int) : ArtifactDownloadResult
}

sealed interface ArtifactHeadResult {
    data object Verified : ArtifactHeadResult
    data object AuthenticationRequired : ArtifactHeadResult
    data object Forbidden : ArtifactHeadResult
    data object NotFound : ArtifactHeadResult
    data object SessionNotVerified : ArtifactHeadResult
    data object CaptureBusy : ArtifactHeadResult
    data class InvalidRequest(val message: String) : ArtifactHeadResult
    data class InvalidResponse(val message: String) : ArtifactHeadResult
    data class NetworkFailure(val message: String) : ArtifactHeadResult
    data class HttpFailure(val statusCode: Int) : ArtifactHeadResult
}

sealed interface CameraFocusResult {
    data class Status(val value: CameraFocusStatus) : CameraFocusResult
    data object Unsupported : CameraFocusResult
    data object AuthenticationRequired : CameraFocusResult
    data object Forbidden : CameraFocusResult
    data object Conflict : CameraFocusResult
    data object InvalidFocus : CameraFocusResult
    data class InvalidRequest(val message: String) : CameraFocusResult
    data class InvalidResponse(val message: String) : CameraFocusResult
    data class NetworkFailure(val message: String) : CameraFocusResult
    data class HttpFailure(val statusCode: Int) : CameraFocusResult
}

sealed interface NetworkStatusResult {
    data class Status(val value: NetworkStatus) : NetworkStatusResult
    data object AuthenticationRequired : NetworkStatusResult
    data object Forbidden : NetworkStatusResult
    data object Unavailable : NetworkStatusResult
    data class InvalidResponse(val message: String) : NetworkStatusResult
    data class NetworkFailure(val message: String) : NetworkStatusResult
    data class HttpFailure(val statusCode: Int) : NetworkStatusResult
}

sealed interface NetworkScanResult {
    data class Scan(val value: NetworkScanSnapshot) : NetworkScanResult
    data object AuthenticationRequired : NetworkScanResult
    data object Forbidden : NetworkScanResult
    data object Unavailable : NetworkScanResult
    data class InvalidResponse(val message: String) : NetworkScanResult
    data class NetworkFailure(val message: String) : NetworkScanResult
    data class HttpFailure(val statusCode: Int) : NetworkScanResult
}

sealed interface NetworkCredentialResult {
    data class Receipt(val value: NetworkCredentialReceipt) : NetworkCredentialResult
    data object AuthenticationRequired : NetworkCredentialResult
    data object Forbidden : NetworkCredentialResult
    data object MutationUnavailable : NetworkCredentialResult
    data class InvalidRequest(val message: String) : NetworkCredentialResult
    data class InvalidResponse(val message: String) : NetworkCredentialResult
    data class NetworkFailure(val message: String) : NetworkCredentialResult
    data class HttpFailure(val statusCode: Int) : NetworkCredentialResult
}

sealed interface NetworkMutationResult {
    data class Accepted(val value: NetworkTransactionReceipt) : NetworkMutationResult
    data object AuthenticationRequired : NetworkMutationResult
    data object Forbidden : NetworkMutationResult
    data object MutationUnavailable : NetworkMutationResult
    data object IdempotencyConflict : NetworkMutationResult
    data object InvalidDesiredState : NetworkMutationResult
    data object NotFound : NetworkMutationResult
    data class InvalidRequest(val message: String) : NetworkMutationResult
    data class InvalidResponse(val message: String) : NetworkMutationResult
    data class NetworkFailure(val message: String) : NetworkMutationResult
    data class HttpFailure(val statusCode: Int) : NetworkMutationResult
}

sealed interface NetworkEventsResult {
    data class Batch(val events: List<NetworkStreamEvent>, val lastEventId: String?) : NetworkEventsResult
    data object AuthenticationRequired : NetworkEventsResult
    data object Forbidden : NetworkEventsResult
    data class InvalidRequest(val message: String) : NetworkEventsResult
    data class InvalidResponse(val message: String) : NetworkEventsResult
    data class NetworkFailure(val message: String) : NetworkEventsResult
    data class HttpFailure(val statusCode: Int) : NetworkEventsResult
}

data class NetworkStreamEvent(
    val sseDeliveryId: String,
    val authorityEpoch: String,
    val sourceRevision: Long,
    val occurredAt: String,
    val type: String,
    val transactionId: String?,
    val revisionRelation: NetworkRevisionRelation,
    val status: NetworkStatus?,
    val transaction: NetworkTransaction?,
) {
    val requiresHttpReconciliation: Boolean
        get() = revisionRelation in setOf(
            NetworkRevisionRelation.Gap,
            NetworkRevisionRelation.Stale,
            NetworkRevisionRelation.NewEpoch,
        )
}

enum class NetworkRevisionRelation {
    Initial,
    Next,
    Gap,
    Stale,
    NewEpoch,
}
