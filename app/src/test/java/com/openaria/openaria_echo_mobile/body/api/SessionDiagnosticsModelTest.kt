package com.openaria.openaria_echo_mobile.body.api

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionDiagnosticsModelTest {
    @Test
    fun `read model preserves quarantine identity fields and maps wire message to summary`() {
        val diagnostic = SessionDiscoveryDiagnostic(
            quarantineId = QUARANTINE_ID,
            code = "manifest_invalid",
            observedAt = OBSERVED_AT,
            message = "closed schema violation",
        )
        val page = page(items = emptyList(), diagnostics = listOf(diagnostic))

        val readModel = page.toSessionListReadModel()

        assertEquals(emptyList(), readModel.downloadableSessions)
        assertEquals(1, readModel.quarantineDiagnostics.size)
        val retained = readModel.quarantineDiagnostics.single()
        assertEquals(QUARANTINE_ID, retained.quarantineId)
        assertEquals("manifest_invalid", retained.code)
        assertEquals("closed schema violation", retained.summary)
        assertEquals("closed schema violation", retained.message)
        assertEquals(OBSERVED_AT, retained.observedAt)
    }

    @Test
    fun `gateway diagnostics remain nested on downloadable sessions`() {
        val session = sessionSummary(
            number = 1,
            verification = GatewayVerification(
                actor = "gateway",
                validator = GatewayValidatorIdentity(
                    name = "validator",
                    version = "1",
                    buildSha256 = "a".repeat(64),
                ),
                manifestSha256 = "b".repeat(64),
                verifiedAt = OBSERVED_AT,
                verdict = GatewayVerificationVerdict.UNUSABLE,
                diagnostics = listOf(
                    GatewayVerificationDiagnostic.Current(
                        code = GatewayVerificationDiagnosticCode.MANIFEST_INVALID,
                        summary = "session manifest failed verification",
                    ),
                ),
            ),
        )
        val page = page(
            items = listOf(session),
            diagnostics = listOf(
                SessionDiscoveryDiagnostic(
                    quarantineId = QUARANTINE_ID,
                    code = "unsupported_schema",
                    observedAt = OBSERVED_AT,
                    message = "candidate schema is not supported",
                ),
            ),
        )

        val readModel = SessionListReadModel.from(page)

        assertEquals(listOf(session.sessionId), readModel.downloadableSessions.map { it.sessionId })
        assertEquals(
            listOf("session manifest failed verification"),
            readModel.downloadableSessions.single().verification?.diagnostics
                ?.map { it.summary },
        )
        assertEquals(listOf("unsupported_schema"), readModel.quarantineDiagnostics.map { it.code })
    }

    private fun page(
        items: List<SessionSummary>,
        diagnostics: List<SessionDiscoveryDiagnostic>,
    ): SessionListPage {
        return SessionListPage(
            contract = SessionListContract.V3,
            catalogRevision = REVISION,
            items = items,
            diagnostics = diagnostics,
            nextCursor = null,
            requestIdentity = SessionListRequestIdentity(limit = 50, cursor = null, takeId = null),
        )
    }

    private fun sessionSummary(
        number: Int,
        verification: GatewayVerification?,
    ): SessionSummary {
        return SessionSummary(
            sessionId = "01991b70-7c88-7123-9234-${number.toString().padStart(12, '0')}",
            producerOutcome = "sealed",
            takeId = "01991b70-7c88-7456-9234-123456789abc",
            displayName = "session $number",
            deviceLabel = "YLX-00ABCDEF",
            startedAt = "2026-08-28T04:0$number:00Z",
            endedAt = "2026-08-28T04:0$number:10Z",
            durationSeconds = 10.0,
            totalBytes = 2048,
            verification = verification,
        )
    }

    private companion object {
        const val REVISION =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val QUARANTINE_ID = "56005c52-31f1-4dac-91cd-d8eafd737d1c"
        const val OBSERVED_AT = "2026-08-28T04:00:00Z"
    }
}
