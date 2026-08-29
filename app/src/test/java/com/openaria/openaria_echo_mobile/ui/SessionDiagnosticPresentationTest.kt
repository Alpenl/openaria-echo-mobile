package com.openaria.openaria_echo_mobile.ui

import com.openaria.openaria_echo_mobile.body.api.DeviceHttpFailure
import com.openaria.openaria_echo_mobile.body.api.GatewayValidatorIdentity
import com.openaria.openaria_echo_mobile.body.api.GatewayVerification
import com.openaria.openaria_echo_mobile.body.api.GatewayVerificationDiagnostic
import com.openaria.openaria_echo_mobile.body.api.GatewayVerificationDiagnosticCode
import com.openaria.openaria_echo_mobile.body.api.GatewayVerificationVerdict
import com.openaria.openaria_echo_mobile.body.api.SessionDiscoveryDiagnostic
import com.openaria.openaria_echo_mobile.body.api.SessionLedgerFailure
import com.openaria.openaria_echo_mobile.body.api.SessionListContract
import com.openaria.openaria_echo_mobile.body.api.SessionListPage
import com.openaria.openaria_echo_mobile.body.api.SessionListRequestIdentity
import com.openaria.openaria_echo_mobile.body.api.SessionListResult
import com.openaria.openaria_echo_mobile.body.api.SessionProtocolFailureReason
import com.openaria.openaria_echo_mobile.body.api.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionDiagnosticPresentationTest {
    @Test
    fun `quarantine diagnostics remain read-only presentations outside session items`() {
        val page = SessionListPage(
            contract = SessionListContract.V3,
            catalogRevision =
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            items = emptyList(),
            diagnostics = listOf(
                SessionDiscoveryDiagnostic(
                    quarantineId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                    code = "manifest_invalid",
                    observedAt = "2026-08-28T04:00:00Z",
                    message = "closed schema violation",
                ),
            ),
            nextCursor = null,
            requestIdentity = SessionListRequestIdentity(limit = 1, cursor = null, takeId = null),
        )

        val presentation = page.readOnlyDiagnosticPresentations().single()

        assertEquals(emptyList(), page.items)
        assertEquals(SessionDiagnosticKind.QUARANTINE, presentation.kind)
        assertEquals(SessionDiagnosticReason.QUARANTINE_MANIFEST_INVALID, presentation.reason)
        assertEquals("manifest_invalid", presentation.code)
        assertEquals("closed schema violation", presentation.summary)
        assertEquals(null, presentation.rawDetail)
        assertEquals("2026-08-28T04:00:00Z", presentation.observedAt)
        assertEquals("56005c52-31f1-4dac-91cd-d8eafd737d1c", presentation.quarantineId)
        assertEquals(null, presentation.sessionId)
    }

    @Test
    fun `v3 verification diagnostics stay attached to their sealed session`() {
        val session = sessionSummary(
            diagnostics = listOf(
                GatewayVerificationDiagnostic.Current(
                    code = GatewayVerificationDiagnosticCode.ARTIFACT_DIGEST_MISMATCH,
                    summary = "artifact content SHA-256 does not match the manifest",
                ),
            ),
        )

        val presentation = session.verificationDiagnosticPresentations().single()

        assertEquals(SessionDiagnosticKind.GATEWAY_VERIFICATION, presentation.kind)
        assertEquals(
            SessionDiagnosticReason.VERIFICATION_ARTIFACT_DIGEST_MISMATCH,
            presentation.reason,
        )
        assertEquals("artifact_digest_mismatch", presentation.code)
        assertEquals(
            "artifact content SHA-256 does not match the manifest",
            presentation.summary,
        )
        assertEquals(session.sessionId, presentation.sessionId)
        assertEquals(null, presentation.quarantineId)
        assertEquals(null, presentation.rawDetail)
    }

    @Test
    fun `v2 verification strings remain expandable legacy detail`() {
        val session = sessionSummary(
            diagnostics = listOf(
                GatewayVerificationDiagnostic.Legacy("validator process exited with status 7"),
            ),
        )

        val presentation = session.verificationDiagnosticPresentations().single()

        assertEquals(SessionDiagnosticReason.VERIFICATION_LEGACY, presentation.reason)
        assertEquals("legacy_v2_verification_diagnostic", presentation.code)
        assertEquals(null, presentation.summary)
        assertEquals("validator process exited with status 7", presentation.rawDetail)
    }

    @Test
    fun `typed protocol failure maps to stable reason with separate diagnostic detail`() {
        val failure = SessionLedgerFailure.Protocol(
            reason = SessionProtocolFailureReason.CATALOG_RECOVERY_REPEATED,
            diagnosticDetail = "catalog changed twice while loading revision",
            catalogRevision = "sha256:${"c".repeat(64)}",
        )

        val presentation = requireNotNull(failure.toReadOnlyPresentation())

        assertEquals(SessionDiagnosticKind.LEDGER_FAILURE, presentation.kind)
        assertEquals(
            SessionDiagnosticReason.PROTOCOL_CATALOG_RECOVERY_REPEATED,
            presentation.reason,
        )
        assertEquals("catalog_recovery_repeated", presentation.code)
        assertEquals(
            "catalog changed twice while loading revision",
            presentation.rawDetail,
        )
        assertEquals("sha256:${"c".repeat(64)}", presentation.catalogRevision)
    }

    @Test
    fun `transport detail remains diagnostic instead of primary presentation reason`() {
        val failure = SessionLedgerFailure.Transport(
            SessionListResult.InvalidResponse("items[0].verification has unknown key stack"),
        )

        val presentation = requireNotNull(failure.toReadOnlyPresentation())

        assertEquals(SessionDiagnosticReason.LEDGER_INVALID_RESPONSE, presentation.reason)
        assertEquals("invalid_response", presentation.code)
        assertEquals(
            "items[0].verification has unknown key stack",
            presentation.rawDetail,
        )
        assertEquals(null, presentation.summary)
    }

    @Test
    fun `http transport preserves typed code and safe redirect detail`() {
        val failure = SessionLedgerFailure.Transport(
            SessionListResult.HttpFailure(
                DeviceHttpFailure(
                    statusCode = 307,
                    errorCode = DeviceHttpFailure.CODE_PROTOCOL_REDIRECT,
                    locationSummary = "absolute:https",
                ),
            ),
        )

        val presentation = requireNotNull(failure.toReadOnlyPresentation())

        assertEquals(SessionDiagnosticReason.LEDGER_HTTP_FAILURE, presentation.reason)
        assertEquals(DeviceHttpFailure.CODE_PROTOCOL_REDIRECT, presentation.code)
        assertEquals(307, presentation.httpStatusCode)
        assertEquals("absolute:https", presentation.rawDetail)
    }

    @Test
    fun `unexpected transport failure is stable and contains no exception detail`() {
        val presentation = requireNotNull(
            SessionLedgerFailure.UnexpectedTransport.toReadOnlyPresentation(),
        )

        assertEquals(
            SessionDiagnosticReason.LEDGER_UNEXPECTED_TRANSPORT_FAILURE,
            presentation.reason,
        )
        assertEquals("unexpected_transport_failure", presentation.code)
        assertEquals(null, presentation.rawDetail)
        assertEquals(null, presentation.summary)
    }

    @Test
    fun `manifest transport detail is expandable and primary reason is stable`() {
        val presentation = requireNotNull(
            SessionManifestMessage.InvalidResponse("unexpected parser detail").toReadOnlyPresentation(),
        )

        assertEquals(SessionDiagnosticKind.SESSION_MANIFEST, presentation.kind)
        assertEquals(SessionDiagnosticReason.MANIFEST_INVALID_RESPONSE, presentation.reason)
        assertEquals("invalid_response", presentation.code)
        assertEquals("unexpected parser detail", presentation.rawDetail)
    }

    @Test
    fun `unsuccessful outcome transport detail is expandable and primary reason is stable`() {
        val presentation = requireNotNull(
            UnsuccessfulOutcomeMessage.NetworkFailure("socket reset by peer").toReadOnlyPresentation(),
        )

        assertEquals(SessionDiagnosticKind.UNSUCCESSFUL_OUTCOME, presentation.kind)
        assertEquals(SessionDiagnosticReason.OUTCOME_NETWORK_FAILURE, presentation.reason)
        assertEquals("network_failure", presentation.code)
        assertEquals("socket reset by peer", presentation.rawDetail)
    }

    @Test
    fun `loading messages do not create a failure diagnostic`() {
        assertEquals(null, SessionManifestMessage.Loading.toReadOnlyPresentation())
        assertEquals(null, UnsuccessfulOutcomeMessage.Loading.toReadOnlyPresentation())
    }

    private fun sessionSummary(
        diagnostics: List<GatewayVerificationDiagnostic>,
    ): SessionSummary {
        return SessionSummary(
            sessionId = "01991b70-7c88-7123-9234-123456789abc",
            producerOutcome = "sealed",
            takeId = "01991b70-7c88-7456-9234-123456789abc",
            displayName = "verification test take",
            deviceLabel = "YLX-00ABCDEF",
            startedAt = "2026-08-28T04:00:00Z",
            endedAt = "2026-08-28T04:00:10Z",
            durationSeconds = 10.0,
            totalBytes = 2048,
            verification = GatewayVerification(
                actor = "gateway",
                validator = GatewayValidatorIdentity(
                    name = "test-validator",
                    version = "1",
                    buildSha256 = "a".repeat(64),
                ),
                manifestSha256 = "b".repeat(64),
                verifiedAt = "2026-08-28T04:00:12Z",
                verdict = GatewayVerificationVerdict.UNUSABLE,
                diagnostics = diagnostics,
            ),
        )
    }
}
