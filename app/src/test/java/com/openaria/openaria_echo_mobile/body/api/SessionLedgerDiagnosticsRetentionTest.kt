package com.openaria.openaria_echo_mobile.body.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionLedgerDiagnosticsRetentionTest {
    @Test
    fun `pagination keeps diagnostic payloads separate from downloadable sessions`() {
        val repository = SessionLedgerRepository()
        val refresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                refresh,
                SessionListResult.Page(page(sessionNumber = 1, nextCursor = "cursor-2")),
            ),
        )

        val append = requireNotNull(repository.beginLoadMore())
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                append,
                SessionListResult.Page(
                    page(
                        sessionNumber = 2,
                        nextCursor = null,
                        requestCursor = append.cursor,
                    ),
                ),
            ),
        )

        val readModel = requireNotNull(repository.page).toSessionListReadModel()
        assertEquals(
            listOf(sessionId(1), sessionId(2)),
            readModel.downloadableSessions.map { it.sessionId },
        )
        assertEquals(
            listOf("manifest_invalid #1", "unsupported_schema #2"),
            readModel.quarantineDiagnostics.map { "${it.code} ${it.summary}" },
        )
        assertEquals(
            listOf("2026-08-28T04:00:01Z", "2026-08-28T04:00:02Z"),
            readModel.quarantineDiagnostics.map { it.observedAt },
        )
        assertEquals(
            listOf(quarantineId(1), quarantineId(2)),
            readModel.quarantineDiagnostics.map { it.quarantineId },
        )
    }

    @Test
    fun `duplicate quarantine identity fails without replacing the retained record`() {
        val repository = SessionLedgerRepository()
        val refresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                refresh,
                SessionListResult.Page(page(sessionNumber = 1, nextCursor = "cursor-2")),
            ),
        )

        val append = requireNotNull(repository.beginLoadMore())
        val duplicate = page(
            sessionNumber = 2,
            nextCursor = null,
            requestCursor = append.cursor,
            diagnosticNumber = 1,
        )
        val result = repository.complete(append, SessionListResult.Page(duplicate))

        val failure = assertIs<SessionLedgerApplyResult.Failed>(result)
        assertEquals(
            SessionProtocolFailureReason.DUPLICATE_IDENTITY,
            assertIs<SessionLedgerFailure.Protocol>(failure.failure).reason,
        )
        val readModel = requireNotNull(repository.page).toSessionListReadModel()
        assertEquals(listOf(sessionId(1)), readModel.downloadableSessions.map { it.sessionId })
        assertEquals(
            listOf("manifest_invalid #1"),
            readModel.quarantineDiagnostics.map { "${it.code} ${it.summary}" },
        )
    }

    private fun page(
        sessionNumber: Int,
        nextCursor: String?,
        requestCursor: String? = null,
        diagnosticNumber: Int = sessionNumber,
    ): SessionListPage {
        return SessionListPage(
            contract = SessionListContract.V3,
            catalogRevision = REVISION,
            items = listOf(summary(sessionNumber)),
            diagnostics = listOf(
                SessionDiscoveryDiagnostic(
                    quarantineId = quarantineId(diagnosticNumber),
                    code = if (diagnosticNumber == 1) "manifest_invalid" else "unsupported_schema",
                    observedAt = "2026-08-28T04:00:0${diagnosticNumber}Z",
                    message = "#${diagnosticNumber}",
                ),
            ),
            nextCursor = nextCursor,
            requestIdentity = SessionListRequestIdentity(
                limit = 50,
                cursor = requestCursor,
                takeId = TAKE_ID,
            ),
        )
    }

    private fun summary(number: Int): SessionSummary {
        val minute = (10 - number).toString().padStart(2, '0')
        return SessionSummary(
            sessionId = sessionId(number),
            producerOutcome = "sealed",
            takeId = TAKE_ID,
            displayName = "session $number",
            deviceLabel = "YLX-00ABCDEF",
            startedAt = "2026-08-28T04:$minute:00Z",
            endedAt = "2026-08-28T04:$minute:10Z",
            durationSeconds = 10.0,
            totalBytes = 1024,
            verification = null,
        )
    }

    private fun sessionId(number: Int): String {
        return "01991b70-7c88-7123-9234-${number.toString().padStart(12, '0')}"
    }

    private fun quarantineId(number: Int): String {
        return "56005c52-31f1-4dac-91cd-${number.toString().padStart(12, '0')}"
    }

    private companion object {
        const val REVISION =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TAKE_ID = "01991b70-7c88-7456-9234-123456789abc"
    }
}
