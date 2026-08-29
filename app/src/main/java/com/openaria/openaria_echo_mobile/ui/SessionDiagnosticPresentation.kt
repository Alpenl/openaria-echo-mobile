package com.openaria.openaria_echo_mobile.ui

import com.openaria.openaria_echo_mobile.body.api.GatewayVerificationDiagnostic
import com.openaria.openaria_echo_mobile.body.api.GatewayVerificationDiagnosticCode
import com.openaria.openaria_echo_mobile.body.api.RetainedQuarantineDiagnostic
import com.openaria.openaria_echo_mobile.body.api.SessionDiscoveryDiagnostic
import com.openaria.openaria_echo_mobile.body.api.SessionLedgerFailure
import com.openaria.openaria_echo_mobile.body.api.SessionListPage
import com.openaria.openaria_echo_mobile.body.api.SessionListResult
import com.openaria.openaria_echo_mobile.body.api.SessionProtocolFailureReason
import com.openaria.openaria_echo_mobile.body.api.SessionSummary
import com.openaria.openaria_echo_mobile.body.api.toRetainedQuarantineDiagnostic
import com.openaria.openaria_echo_mobile.body.api.toSessionListReadModel

internal enum class SessionDiagnosticKind {
    QUARANTINE,
    GATEWAY_VERIFICATION,
    LEDGER_FAILURE,
    SESSION_MANIFEST,
    UNSUCCESSFUL_OUTCOME,
}

internal enum class SessionDiagnosticReason {
    QUARANTINE_MANIFEST_UNREADABLE,
    QUARANTINE_UNSUPPORTED_SCHEMA,
    QUARANTINE_MANIFEST_INVALID,
    QUARANTINE_MANIFEST_NOT_SEALED,
    QUARANTINE_UNKNOWN,
    VERIFICATION_ARTIFACT_DIGEST_MISMATCH,
    VERIFICATION_ARTIFACT_INVALID,
    VERIFICATION_MANIFEST_INVALID,
    VERIFICATION_FAILED,
    VERIFICATION_LEGACY,
    LEDGER_AUTHENTICATION_REQUIRED,
    LEDGER_FORBIDDEN,
    LEDGER_HTTP_FAILURE,
    LEDGER_INVALID_REQUEST,
    LEDGER_INVALID_RESPONSE,
    LEDGER_NETWORK_FAILURE,
    LEDGER_UNEXPECTED_TRANSPORT_FAILURE,
    PROTOCOL_REQUEST_IDENTITY_MISMATCH,
    PROTOCOL_CATALOG_RECOVERY_REPEATED,
    PROTOCOL_CURSOR_DID_NOT_ADVANCE,
    PROTOCOL_DUPLICATE_IDENTITY,
    PROTOCOL_NEWEST_FIRST_BOUNDARY_INVERTED,
    MANIFEST_NOT_FOUND,
    MANIFEST_AUTHENTICATION_REQUIRED,
    MANIFEST_FORBIDDEN,
    MANIFEST_HTTP_FAILURE,
    MANIFEST_INVALID_REQUEST,
    MANIFEST_INVALID_RESPONSE,
    MANIFEST_NETWORK_FAILURE,
    OUTCOME_NOT_FOUND,
    OUTCOME_AUTHENTICATION_REQUIRED,
    OUTCOME_FORBIDDEN,
    OUTCOME_HTTP_FAILURE,
    OUTCOME_INVALID_REQUEST,
    OUTCOME_INVALID_RESPONSE,
    OUTCOME_NETWORK_FAILURE,
}

internal data class SessionDiagnosticPresentation(
    val stableKey: String,
    val kind: SessionDiagnosticKind,
    val reason: SessionDiagnosticReason,
    val code: String? = null,
    val summary: String? = null,
    val rawDetail: String? = null,
    val observedAt: String? = null,
    val verifiedAt: String? = null,
    val quarantineId: String? = null,
    val sessionId: String? = null,
    val catalogRevision: String? = null,
    val httpStatusCode: Int? = null,
    val actor: String? = null,
    val validatorName: String? = null,
    val validatorVersion: String? = null,
    val validatorBuildSha256: String? = null,
    val manifestSha256: String? = null,
)

/**
 * UI transport states intentionally retain raw detail as diagnostic data only.
 * Their primary text is selected from [SessionDiagnosticReason] below.
 */
internal sealed interface SessionManifestMessage {
    data object Loading : SessionManifestMessage
    data object NotFound : SessionManifestMessage
    data object AuthRequired : SessionManifestMessage
    data object Forbidden : SessionManifestMessage
    data class InvalidRequest(val detail: String) : SessionManifestMessage
    data class InvalidResponse(val detail: String) : SessionManifestMessage
    data class NetworkFailure(val detail: String) : SessionManifestMessage
    data class HttpFailure(val statusCode: Int) : SessionManifestMessage
}

internal sealed interface UnsuccessfulOutcomeMessage {
    data object Loading : UnsuccessfulOutcomeMessage
    data object NotFound : UnsuccessfulOutcomeMessage
    data object AuthRequired : UnsuccessfulOutcomeMessage
    data object Forbidden : UnsuccessfulOutcomeMessage
    data class InvalidRequest(val detail: String) : UnsuccessfulOutcomeMessage
    data class InvalidResponse(val detail: String) : UnsuccessfulOutcomeMessage
    data class NetworkFailure(val detail: String) : UnsuccessfulOutcomeMessage
    data class HttpFailure(val statusCode: Int) : UnsuccessfulOutcomeMessage
}

internal fun SessionDiscoveryDiagnostic.toReadOnlyPresentation(): SessionDiagnosticPresentation {
    return toRetainedQuarantineDiagnostic().toReadOnlyPresentation()
}

internal fun RetainedQuarantineDiagnostic.toReadOnlyPresentation(): SessionDiagnosticPresentation {
    return SessionDiagnosticPresentation(
        stableKey = "quarantine:$quarantineId",
        kind = SessionDiagnosticKind.QUARANTINE,
        reason = when (code) {
            "manifest_unreadable" -> SessionDiagnosticReason.QUARANTINE_MANIFEST_UNREADABLE
            "unsupported_schema" -> SessionDiagnosticReason.QUARANTINE_UNSUPPORTED_SCHEMA
            "manifest_invalid" -> SessionDiagnosticReason.QUARANTINE_MANIFEST_INVALID
            "manifest_not_sealed" -> SessionDiagnosticReason.QUARANTINE_MANIFEST_NOT_SEALED
            else -> SessionDiagnosticReason.QUARANTINE_UNKNOWN
        },
        code = code,
        summary = summary,
        observedAt = observedAt,
        quarantineId = quarantineId,
    )
}

internal fun SessionSummary.verificationDiagnosticPresentations(): List<SessionDiagnosticPresentation> {
    val gatewayVerification = verification ?: return emptyList()
    return gatewayVerification.diagnostics.mapIndexed { index, diagnostic ->
        val payload = when (diagnostic) {
            is GatewayVerificationDiagnostic.Current -> DiagnosticPayload(
                reason = diagnostic.code.toPresentationReason(),
                code = diagnostic.code.wireValue,
                summary = diagnostic.summary,
            )
            is GatewayVerificationDiagnostic.Legacy -> DiagnosticPayload(
                reason = SessionDiagnosticReason.VERIFICATION_LEGACY,
                code = "legacy_v2_verification_diagnostic",
                rawDetail = diagnostic.summary,
            )
        }
        SessionDiagnosticPresentation(
            stableKey = "verification:$sessionId:$index:${payload.code}",
            kind = SessionDiagnosticKind.GATEWAY_VERIFICATION,
            reason = payload.reason,
            code = payload.code,
            summary = payload.summary,
            rawDetail = payload.rawDetail,
            verifiedAt = gatewayVerification.verifiedAt,
            sessionId = sessionId,
            actor = gatewayVerification.actor,
            validatorName = gatewayVerification.validator.name,
            validatorVersion = gatewayVerification.validator.version,
            validatorBuildSha256 = gatewayVerification.validator.buildSha256,
            manifestSha256 = gatewayVerification.manifestSha256,
        )
    }
}

internal fun SessionListPage.readOnlyDiagnosticPresentations(): List<SessionDiagnosticPresentation> {
    return toSessionListReadModel().quarantineDiagnostics.map { it.toReadOnlyPresentation() }
}

internal fun SessionLedgerFailure.toReadOnlyPresentation(): SessionDiagnosticPresentation? {
    return when (this) {
        is SessionLedgerFailure.Protocol -> SessionDiagnosticPresentation(
            stableKey = "ledger:protocol:${reason.name}",
            kind = SessionDiagnosticKind.LEDGER_FAILURE,
            reason = reason.toPresentationReason(),
            code = reason.wireCode,
            rawDetail = diagnosticDetail,
            catalogRevision = catalogRevision,
        )
        is SessionLedgerFailure.Transport -> result.toReadOnlyPresentation()
        SessionLedgerFailure.UnexpectedTransport -> ledgerFailurePresentation(
            reason = SessionDiagnosticReason.LEDGER_UNEXPECTED_TRANSPORT_FAILURE,
            code = "unexpected_transport_failure",
        )
    }
}

internal fun SessionManifestMessage.toReadOnlyPresentation(): SessionDiagnosticPresentation? {
    return when (this) {
        SessionManifestMessage.Loading -> null
        SessionManifestMessage.NotFound -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.SESSION_MANIFEST,
            reason = SessionDiagnosticReason.MANIFEST_NOT_FOUND,
            code = "manifest_not_found",
        )
        SessionManifestMessage.AuthRequired -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.SESSION_MANIFEST,
            reason = SessionDiagnosticReason.MANIFEST_AUTHENTICATION_REQUIRED,
            code = "authentication_required",
        )
        SessionManifestMessage.Forbidden -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.SESSION_MANIFEST,
            reason = SessionDiagnosticReason.MANIFEST_FORBIDDEN,
            code = "forbidden",
        )
        is SessionManifestMessage.HttpFailure -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.SESSION_MANIFEST,
            reason = SessionDiagnosticReason.MANIFEST_HTTP_FAILURE,
            code = "http_failure",
            httpStatusCode = statusCode,
        )
        is SessionManifestMessage.InvalidRequest -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.SESSION_MANIFEST,
            reason = SessionDiagnosticReason.MANIFEST_INVALID_REQUEST,
            code = "invalid_request",
            rawDetail = detail,
        )
        is SessionManifestMessage.InvalidResponse -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.SESSION_MANIFEST,
            reason = SessionDiagnosticReason.MANIFEST_INVALID_RESPONSE,
            code = "invalid_response",
            rawDetail = detail,
        )
        is SessionManifestMessage.NetworkFailure -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.SESSION_MANIFEST,
            reason = SessionDiagnosticReason.MANIFEST_NETWORK_FAILURE,
            code = "network_failure",
            rawDetail = detail,
        )
    }
}

internal fun UnsuccessfulOutcomeMessage.toReadOnlyPresentation(): SessionDiagnosticPresentation? {
    return when (this) {
        UnsuccessfulOutcomeMessage.Loading -> null
        UnsuccessfulOutcomeMessage.NotFound -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.UNSUCCESSFUL_OUTCOME,
            reason = SessionDiagnosticReason.OUTCOME_NOT_FOUND,
            code = "outcome_not_found",
        )
        UnsuccessfulOutcomeMessage.AuthRequired -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.UNSUCCESSFUL_OUTCOME,
            reason = SessionDiagnosticReason.OUTCOME_AUTHENTICATION_REQUIRED,
            code = "authentication_required",
        )
        UnsuccessfulOutcomeMessage.Forbidden -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.UNSUCCESSFUL_OUTCOME,
            reason = SessionDiagnosticReason.OUTCOME_FORBIDDEN,
            code = "forbidden",
        )
        is UnsuccessfulOutcomeMessage.HttpFailure -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.UNSUCCESSFUL_OUTCOME,
            reason = SessionDiagnosticReason.OUTCOME_HTTP_FAILURE,
            code = "http_failure",
            httpStatusCode = statusCode,
        )
        is UnsuccessfulOutcomeMessage.InvalidRequest -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.UNSUCCESSFUL_OUTCOME,
            reason = SessionDiagnosticReason.OUTCOME_INVALID_REQUEST,
            code = "invalid_request",
            rawDetail = detail,
        )
        is UnsuccessfulOutcomeMessage.InvalidResponse -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.UNSUCCESSFUL_OUTCOME,
            reason = SessionDiagnosticReason.OUTCOME_INVALID_RESPONSE,
            code = "invalid_response",
            rawDetail = detail,
        )
        is UnsuccessfulOutcomeMessage.NetworkFailure -> sessionTransportPresentation(
            kind = SessionDiagnosticKind.UNSUCCESSFUL_OUTCOME,
            reason = SessionDiagnosticReason.OUTCOME_NETWORK_FAILURE,
            code = "network_failure",
            rawDetail = detail,
        )
    }
}

private data class DiagnosticPayload(
    val reason: SessionDiagnosticReason,
    val code: String,
    val summary: String? = null,
    val rawDetail: String? = null,
)

private fun GatewayVerificationDiagnosticCode.toPresentationReason(): SessionDiagnosticReason {
    return when (this) {
        GatewayVerificationDiagnosticCode.ARTIFACT_DIGEST_MISMATCH -> {
            SessionDiagnosticReason.VERIFICATION_ARTIFACT_DIGEST_MISMATCH
        }
        GatewayVerificationDiagnosticCode.ARTIFACT_INVALID -> {
            SessionDiagnosticReason.VERIFICATION_ARTIFACT_INVALID
        }
        GatewayVerificationDiagnosticCode.MANIFEST_INVALID -> {
            SessionDiagnosticReason.VERIFICATION_MANIFEST_INVALID
        }
        GatewayVerificationDiagnosticCode.VERIFICATION_FAILED -> {
            SessionDiagnosticReason.VERIFICATION_FAILED
        }
    }
}

private fun SessionListResult.toReadOnlyPresentation(): SessionDiagnosticPresentation? {
    return when (this) {
        is SessionListResult.Page -> return null
        is SessionListResult.CatalogChanged -> SessionDiagnosticPresentation(
            stableKey = "ledger:protocol:catalog_changed",
            kind = SessionDiagnosticKind.LEDGER_FAILURE,
            reason = SessionDiagnosticReason.PROTOCOL_CATALOG_RECOVERY_REPEATED,
            code = "catalog_changed",
            catalogRevision = catalogRevision,
        )
        SessionListResult.AuthenticationRequired -> ledgerFailurePresentation(
            reason = SessionDiagnosticReason.LEDGER_AUTHENTICATION_REQUIRED,
            code = "authentication_required",
        )
        SessionListResult.Forbidden -> ledgerFailurePresentation(
            reason = SessionDiagnosticReason.LEDGER_FORBIDDEN,
            code = "forbidden",
        )
        is SessionListResult.HttpFailure -> ledgerFailurePresentation(
            reason = SessionDiagnosticReason.LEDGER_HTTP_FAILURE,
            code = errorCode,
            rawDetail = locationSummary,
            httpStatusCode = statusCode,
        )
        SessionListResult.InvalidRequest -> ledgerFailurePresentation(
            reason = SessionDiagnosticReason.LEDGER_INVALID_REQUEST,
            code = "invalid_request",
        )
        is SessionListResult.InvalidResponse -> ledgerFailurePresentation(
            reason = SessionDiagnosticReason.LEDGER_INVALID_RESPONSE,
            code = "invalid_response",
            rawDetail = message,
        )
        is SessionListResult.NetworkFailure -> ledgerFailurePresentation(
            reason = SessionDiagnosticReason.LEDGER_NETWORK_FAILURE,
            code = "network_failure",
            rawDetail = message,
        )
    }
}

private fun ledgerFailurePresentation(
    reason: SessionDiagnosticReason,
    code: String,
    rawDetail: String? = null,
    httpStatusCode: Int? = null,
): SessionDiagnosticPresentation {
    return SessionDiagnosticPresentation(
        stableKey = "ledger:transport:$code:${httpStatusCode ?: "none"}",
        kind = SessionDiagnosticKind.LEDGER_FAILURE,
        reason = reason,
        code = code,
        rawDetail = rawDetail,
        httpStatusCode = httpStatusCode,
    )
}

private fun sessionTransportPresentation(
    kind: SessionDiagnosticKind,
    reason: SessionDiagnosticReason,
    code: String,
    rawDetail: String? = null,
    httpStatusCode: Int? = null,
): SessionDiagnosticPresentation {
    return SessionDiagnosticPresentation(
        stableKey = "${kind.name.lowercase()}:$code:${httpStatusCode ?: "none"}",
        kind = kind,
        reason = reason,
        code = code,
        rawDetail = rawDetail,
        httpStatusCode = httpStatusCode,
    )
}

private val SessionProtocolFailureReason.wireCode: String
    get() = when (this) {
        SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH -> "request_identity_mismatch"
        SessionProtocolFailureReason.CATALOG_RECOVERY_REPEATED -> "catalog_recovery_repeated"
        SessionProtocolFailureReason.CURSOR_DID_NOT_ADVANCE -> "cursor_did_not_advance"
        SessionProtocolFailureReason.DUPLICATE_IDENTITY -> "duplicate_identity"
        SessionProtocolFailureReason.NEWEST_FIRST_BOUNDARY_INVERTED -> "newest_first_boundary_inverted"
    }

private fun SessionProtocolFailureReason.toPresentationReason(): SessionDiagnosticReason {
    return when (this) {
        SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH -> {
            SessionDiagnosticReason.PROTOCOL_REQUEST_IDENTITY_MISMATCH
        }
        SessionProtocolFailureReason.CATALOG_RECOVERY_REPEATED -> {
            SessionDiagnosticReason.PROTOCOL_CATALOG_RECOVERY_REPEATED
        }
        SessionProtocolFailureReason.CURSOR_DID_NOT_ADVANCE -> {
            SessionDiagnosticReason.PROTOCOL_CURSOR_DID_NOT_ADVANCE
        }
        SessionProtocolFailureReason.DUPLICATE_IDENTITY -> {
            SessionDiagnosticReason.PROTOCOL_DUPLICATE_IDENTITY
        }
        SessionProtocolFailureReason.NEWEST_FIRST_BOUNDARY_INVERTED -> {
            SessionDiagnosticReason.PROTOCOL_NEWEST_FIRST_BOUNDARY_INVERTED
        }
    }
}
