package com.openaria.openaria_echo_mobile.body.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class SessionLedgerRepositoryTest {
    @Test
    fun `appends v3 page only on the same revision cursor and take chain`() {
        val repository = SessionLedgerRepository()
        val refresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(refresh, SessionListResult.Page(page(REVISION_A, "cursor-2", 1))),
        )

        val append = requireNotNull(repository.beginLoadMore())
        assertEquals("cursor-2", append.cursor)
        assertEquals(REVISION_A, append.catalogRevision)
        assertEquals(TAKE_ID, append.takeId)
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                append,
                SessionListResult.Page(page(REVISION_A, null, 2, requestCursor = append.cursor)),
            ),
        )

        assertEquals(listOf(sessionId(1), sessionId(2)), repository.page?.items?.map { it.sessionId })
        assertEquals(2, repository.page?.diagnosticsCount)
        assertNull(repository.page?.nextCursor)
        assertFalse(repository.isLoadingMore)
    }

    @Test
    fun `catalog revision mismatch discards accumulated chain and requires no-cursor refresh`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())

        val outcome = repository.complete(
            append,
            SessionListResult.Page(page(REVISION_B, null, 2, requestCursor = append.cursor)),
        )

        val refreshRequired = assertIs<SessionLedgerApplyResult.RefreshRequired>(outcome)
        assertEquals(TAKE_ID, refreshRequired.takeId)
        assertEquals(50, refreshRequired.limit)
        assertEquals(
            SessionLedgerRefreshReason.CATALOG_RECOVERY_REQUIRED,
            refreshRequired.reason,
        )
        assertNull(repository.page)
        val refresh = requireNotNull(
            repository.beginRefresh(
                takeId = refreshRequired.takeId,
                limit = refreshRequired.limit,
                catalogRecovery = true,
            ),
        )
        assertNull(refresh.cursor)
        assertNull(refresh.catalogRevision)
        assertEquals(TAKE_ID, refresh.takeId)
    }

    @Test
    fun `catalog changed response discards accumulated chain and requires no-cursor refresh`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())

        val outcome = repository.complete(append, SessionListResult.CatalogChanged(REVISION_B))

        val refreshRequired = assertIs<SessionLedgerApplyResult.RefreshRequired>(outcome)
        assertEquals(TAKE_ID, refreshRequired.takeId)
        assertEquals(
            SessionLedgerRefreshReason.CATALOG_RECOVERY_REQUIRED,
            refreshRequired.reason,
        )
        assertNull(repository.page)
        val recovery = requireNotNull(
            repository.beginRefresh(
                takeId = refreshRequired.takeId,
                limit = refreshRequired.limit,
                catalogRecovery = true,
            ),
        )
        assertNull(recovery.cursor)
        assertEquals(TAKE_ID, recovery.takeId)
    }

    @Test
    fun `repeated catalog changed on fresh request fails instead of retrying forever`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())
        val refreshRequired = assertIs<SessionLedgerApplyResult.RefreshRequired>(
            repository.complete(append, SessionListResult.CatalogChanged(REVISION_B)),
        )
        val recovery = requireNotNull(
            repository.beginRefresh(
                takeId = refreshRequired.takeId,
                limit = refreshRequired.limit,
                catalogRecovery = true,
            ),
        )

        val outcome = repository.complete(recovery, SessionListResult.CatalogChanged(REVISION_B))

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        val failure = assertIs<SessionLedgerFailure.Protocol>(failed.failure)
        assertEquals(SessionProtocolFailureReason.CATALOG_RECOVERY_REPEATED, failure.reason)
        assertEquals(REVISION_B, failure.catalogRevision)
        assertNull(failure.diagnosticDetail)
        assertNull(repository.page)
        assertFalse(repository.isRefreshing)
    }

    @Test
    fun `queued ordinary refresh cannot hide repeated catalog change during recovery`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())
        val refreshRequired = assertIs<SessionLedgerApplyResult.RefreshRequired>(
            repository.complete(append, SessionListResult.CatalogChanged(REVISION_B)),
        )
        val recovery = requireNotNull(
            repository.beginRefresh(
                takeId = refreshRequired.takeId,
                limit = refreshRequired.limit,
                catalogRecovery = true,
            ),
        )
        assertNull(repository.beginRefresh(takeId = TAKE_ID))

        val failed = assertIs<SessionLedgerApplyResult.Failed>(
            repository.complete(recovery, SessionListResult.CatalogChanged(REVISION_B)),
        )

        val failure = assertIs<SessionLedgerFailure.Protocol>(failed.failure)
        assertEquals(SessionProtocolFailureReason.CATALOG_RECOVERY_REPEATED, failure.reason)
        assertEquals(REVISION_B, failure.catalogRevision)
        assertFalse(repository.isRefreshing)
        assertNull(repository.page)
    }

    @Test
    fun `ordinary refresh replaces a previously loaded tail`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                append,
                SessionListResult.Page(page(REVISION_A, null, 2, requestCursor = append.cursor)),
            ),
        )
        assertEquals(2, repository.page?.items?.size)

        val refresh = requireNotNull(repository.beginRefresh())
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                refresh,
                SessionListResult.Page(page(REVISION_B, "new-cursor", 3, requestTakeId = null)),
            ),
        )

        assertEquals(listOf(sessionId(3)), repository.page?.items?.map { it.sessionId })
        assertEquals(1, repository.page?.diagnosticsCount)
        assertEquals("new-cursor", repository.page?.nextCursor)
    }

    @Test
    fun `late load-more response cannot overwrite a newer refresh`() {
        val repository = repositoryWithFirstPage()
        val staleAppend = requireNotNull(repository.beginLoadMore())
        val newerRefresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID_B))
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                newerRefresh,
                SessionListResult.Page(
                    page(REVISION_B, "revision-b-cursor", 3, requestTakeId = TAKE_ID_B),
                ),
            ),
        )

        val staleOutcome = repository.complete(
            staleAppend,
            SessionListResult.Page(page(REVISION_A, null, 2)),
        )

        assertIs<SessionLedgerApplyResult.Ignored>(staleOutcome)
        assertEquals(REVISION_B, repository.page?.catalogRevision)
        assertEquals(listOf(sessionId(3)), repository.page?.items?.map { it.sessionId })
        assertEquals("revision-b-cursor", repository.page?.nextCursor)
        assertEquals(TAKE_ID_B, requireNotNull(repository.beginLoadMore()).takeId)
    }

    @Test
    fun `logical lifecycle cancellation releases refresh and fences its late response`() {
        val repository = SessionLedgerRepository()
        val staleRefresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))

        repository.cancelInFlight()

        assertFalse(repository.isRefreshing)
        val resumedRefresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID_B))
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                resumedRefresh,
                SessionListResult.Page(
                    page(REVISION_B, "revision-b-cursor", 3, requestTakeId = TAKE_ID_B),
                ),
            ),
        )
        val lateOutcome = repository.complete(
            staleRefresh,
            SessionListResult.Page(page(REVISION_A, "stale-cursor", 1)),
        )

        assertIs<SessionLedgerApplyResult.Ignored>(lateOutcome)
        assertEquals(REVISION_B, repository.page?.catalogRevision)
        assertEquals(listOf(sessionId(3)), repository.page?.items?.map { it.sessionId })
        assertEquals(TAKE_ID_B, requireNotNull(repository.beginLoadMore()).takeId)
    }

    @Test
    fun `logical lifecycle cancellation preserves committed page while fencing load more`() {
        val repository = repositoryWithFirstPage()
        val staleAppend = requireNotNull(repository.beginLoadMore())

        repository.cancelInFlight()

        assertFalse(repository.isLoadingMore)
        assertEquals(REVISION_A, repository.page?.catalogRevision)
        assertEquals(listOf(sessionId(1)), repository.page?.items?.map { it.sessionId })
        val replacement = requireNotNull(repository.beginRefresh(takeId = TAKE_ID_B))
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                replacement,
                SessionListResult.Page(page(REVISION_B, null, 3, requestTakeId = TAKE_ID_B)),
            ),
        )
        assertIs<SessionLedgerApplyResult.Ignored>(
            repository.complete(
                staleAppend,
                SessionListResult.Page(page(REVISION_A, null, 2)),
            ),
        )
        assertEquals(REVISION_B, repository.page?.catalogRevision)
        assertEquals(listOf(sessionId(3)), repository.page?.items?.map { it.sessionId })
    }

    @Test
    fun `newer refresh intent is queued and the old response is never final`() {
        val repository = repositoryWithFirstPage()
        val staleRefresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))

        assertNull(repository.beginRefresh(takeId = TAKE_ID_B))
        val outcome = repository.complete(
            staleRefresh,
            SessionListResult.Page(page(REVISION_B, null, 2)),
        )

        val refreshRequired = assertIs<SessionLedgerApplyResult.RefreshRequired>(outcome)
        assertEquals(TAKE_ID_B, refreshRequired.takeId)
        assertEquals(50, refreshRequired.limit)
        assertEquals(SessionLedgerRefreshReason.REQUESTED, refreshRequired.reason)
        assertEquals(REVISION_A, repository.page?.catalogRevision)
        assertEquals(listOf(sessionId(1)), repository.page?.items?.map { it.sessionId })

        val newestRefresh = requireNotNull(
            repository.beginRefresh(
                takeId = refreshRequired.takeId,
                limit = refreshRequired.limit,
                catalogRecovery = false,
            ),
        )
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                newestRefresh,
                SessionListResult.Page(page(REVISION_B, null, 3, requestTakeId = TAKE_ID_B)),
            ),
        )
        assertEquals(REVISION_B, repository.page?.catalogRevision)
        assertEquals(listOf(sessionId(3)), repository.page?.items?.map { it.sessionId })
    }

    @Test
    fun `cancelling active refresh hands the latest queued intent back to the caller`() {
        val repository = repositoryWithFirstPage()
        val staleRefresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))

        assertNull(repository.beginRefresh(takeId = TAKE_ID_B, limit = 37))
        assertNull(repository.beginRefresh(takeId = TAKE_ID_C, limit = 73))

        val rerun = assertIs<SessionLedgerApplyResult.RefreshRequired>(
            repository.cancel(staleRefresh),
        )

        assertEquals(TAKE_ID_C, rerun.takeId)
        assertEquals(73, rerun.limit)
        assertEquals(SessionLedgerRefreshReason.REQUESTED, rerun.reason)
        assertFalse(repository.isRefreshing)
        assertEquals(REVISION_A, repository.page?.catalogRevision)
    }

    @Test
    fun `latest queued identity cannot downgrade a forced catalog recovery`() {
        val repository = repositoryWithFirstPage()
        val staleRefresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))

        assertNull(
            repository.beginRefresh(
                takeId = TAKE_ID_B,
                limit = 37,
                catalogRecovery = true,
            ),
        )
        assertNull(repository.beginRefresh(takeId = TAKE_ID_C, limit = 73))

        val rerun = assertIs<SessionLedgerApplyResult.RefreshRequired>(
            repository.cancel(staleRefresh),
        )

        assertEquals(TAKE_ID_C, rerun.takeId)
        assertEquals(73, rerun.limit)
        assertEquals(SessionLedgerRefreshReason.CATALOG_RECOVERY_REQUIRED, rerun.reason)
    }

    @Test
    fun `cross-page duplicate session is rejected without overwriting the accumulated page`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())

        val outcome = repository.complete(
            append,
            SessionListResult.Page(
                page(REVISION_A, null, 1, requestCursor = append.cursor),
            ),
        )

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        assertEquals(
            SessionProtocolFailureReason.DUPLICATE_IDENTITY,
            assertIs<SessionLedgerFailure.Protocol>(failed.failure).reason,
        )
        assertEquals(listOf(sessionId(1)), repository.page?.items?.map { it.sessionId })
        assertEquals(listOf(quarantineId(1)), repository.page?.diagnostics?.map { it.quarantineId })
        assertEquals("cursor-2", repository.page?.nextCursor)
    }

    @Test
    fun `cross-page newest-first boundary inversion is rejected without mutation`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())

        val outcome = repository.complete(
            append,
            SessionListResult.Page(
                page(REVISION_A, null, 0, requestCursor = append.cursor),
            ),
        )

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        assertEquals(
            SessionProtocolFailureReason.NEWEST_FIRST_BOUNDARY_INVERTED,
            assertIs<SessionLedgerFailure.Protocol>(failed.failure).reason,
        )
        assertEquals(listOf(sessionId(1)), repository.page?.items?.map { it.sessionId })
        assertEquals(listOf(quarantineId(1)), repository.page?.diagnostics?.map { it.quarantineId })
    }

    @Test
    fun `refresh rejects a typed page that exceeds its request limit`() {
        val repository = SessionLedgerRepository()
        val refresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID, limit = 1))
        val oversized = page(REVISION_A, null, 1).copy(
            diagnostics = listOf(diagnostic(1)),
            requestIdentity = SessionListRequestIdentity(
                limit = 1,
                cursor = null,
                takeId = TAKE_ID,
            ),
        )

        val outcome = repository.complete(refresh, SessionListResult.Page(oversized))

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        val failure = assertIs<SessionLedgerFailure.Protocol>(failed.failure)
        assertEquals(SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH, failure.reason)
        assertNull(repository.page)
    }

    @Test
    fun `refresh rejects a typed page containing an item from another take`() {
        val repository = SessionLedgerRepository()
        val refresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))
        val wrongTake = page(REVISION_A, null, 1).copy(
            items = listOf(summary(1, takeId = TAKE_ID_B)),
        )

        val outcome = repository.complete(refresh, SessionListResult.Page(wrongTake))

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        val failure = assertIs<SessionLedgerFailure.Protocol>(failed.failure)
        assertEquals(SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH, failure.reason)
        assertNull(repository.page)
    }

    @Test
    fun `refresh rejects duplicate session identities before publishing a page`() {
        val repository = SessionLedgerRepository()
        val refresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))
        val duplicate = page(REVISION_A, null, 1).copy(
            items = listOf(summary(1, TAKE_ID), summary(1, TAKE_ID)),
        )

        val outcome = repository.complete(refresh, SessionListResult.Page(duplicate))

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        val failure = assertIs<SessionLedgerFailure.Protocol>(failed.failure)
        assertEquals(SessionProtocolFailureReason.DUPLICATE_IDENTITY, failure.reason)
        assertNull(repository.page)
    }

    @Test
    fun `refresh rejects a typed page that is not newest first`() {
        val repository = SessionLedgerRepository()
        val refresh = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))
        val inverted = page(REVISION_A, null, 1).copy(
            items = listOf(summary(2, TAKE_ID), summary(1, TAKE_ID)),
        )

        val outcome = repository.complete(refresh, SessionListResult.Page(inverted))

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        val failure = assertIs<SessionLedgerFailure.Protocol>(failed.failure)
        assertEquals(SessionProtocolFailureReason.NEWEST_FIRST_BOUNDARY_INVERTED, failure.reason)
        assertNull(repository.page)
    }

    @Test
    fun `append fails closed when a typed boundary timestamp is malformed`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())
        val malformed = page(REVISION_A, null, 2, requestCursor = append.cursor).copy(
            items = listOf(summary(2, TAKE_ID).copy(startedAt = "not-a-date")),
        )

        val outcome = repository.complete(append, SessionListResult.Page(malformed))

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        val failure = assertIs<SessionLedgerFailure.Protocol>(failed.failure)
        assertEquals(SessionProtocolFailureReason.NEWEST_FIRST_BOUNDARY_INVERTED, failure.reason)
        assertEquals(listOf(sessionId(1)), repository.page?.items?.map { it.sessionId })
        assertEquals("cursor-2", repository.page?.nextCursor)
    }

    @Test
    fun `non-advancing opaque cursor is rejected without mutation`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())

        val outcome = repository.complete(
            append,
            SessionListResult.Page(
                page(
                    revision = REVISION_A,
                    nextCursor = append.cursor,
                    sessionNumber = 2,
                    requestCursor = append.cursor,
                ),
            ),
        )

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        assertEquals(
            SessionProtocolFailureReason.CURSOR_DID_NOT_ADVANCE,
            assertIs<SessionLedgerFailure.Protocol>(failed.failure).reason,
        )
        assertEquals(listOf(sessionId(1)), repository.page?.items?.map { it.sessionId })
        assertEquals("cursor-2", repository.page?.nextCursor)
        assertEquals("cursor-2", requireNotNull(repository.beginLoadMore()).cursor)
    }

    @Test
    fun `previously consumed opaque cursor cannot re-enter the chain`() {
        val repository = repositoryWithFirstPage()
        val firstAppend = requireNotNull(repository.beginLoadMore())
        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(
                firstAppend,
                SessionListResult.Page(
                    page(
                        revision = REVISION_A,
                        nextCursor = "cursor-3",
                        sessionNumber = 2,
                        requestCursor = firstAppend.cursor,
                    ),
                ),
            ),
        )
        val secondAppend = requireNotNull(repository.beginLoadMore())

        val outcome = repository.complete(
            secondAppend,
            SessionListResult.Page(
                page(
                    revision = REVISION_A,
                    nextCursor = "cursor-2",
                    sessionNumber = 3,
                    requestCursor = secondAppend.cursor,
                ),
            ),
        )

        val failed = assertIs<SessionLedgerApplyResult.Failed>(outcome)
        assertEquals(
            SessionProtocolFailureReason.CURSOR_DID_NOT_ADVANCE,
            assertIs<SessionLedgerFailure.Protocol>(failed.failure).reason,
        )
        assertEquals(listOf(sessionId(1), sessionId(2)), repository.page?.items?.map { it.sessionId })
        assertEquals("cursor-3", repository.page?.nextCursor)
    }

    @Test
    fun `page hide cancellation releases load more and fences its response`() {
        val repository = repositoryWithFirstPage()
        val hiddenPageRequest = requireNotNull(repository.beginLoadMore())

        repository.cancelLoadMore()

        assertFalse(repository.isLoadingMore)
        assertEquals(REVISION_A, repository.page?.catalogRevision)
        assertIs<SessionLedgerApplyResult.Ignored>(
            repository.complete(
                hiddenPageRequest,
                SessionListResult.Page(
                    page(REVISION_A, null, 2, requestCursor = hiddenPageRequest.cursor),
                ),
            ),
        )
        assertEquals(listOf(sessionId(1)), repository.page?.items?.map { it.sessionId })
        assertEquals("cursor-2", requireNotNull(repository.beginLoadMore()).cursor)
    }

    @Test
    fun `v2 page is normalized to one page even if a caller constructs a cursor`() {
        val repository = SessionLedgerRepository()
        val refresh = requireNotNull(repository.beginRefresh())
        val legacy = page(REVISION_A, "legacy-cursor", 1, requestTakeId = null).copy(
            contract = SessionListContract.V2,
            catalogRevision = null,
        )

        assertIs<SessionLedgerApplyResult.Applied>(
            repository.complete(refresh, SessionListResult.Page(legacy)),
        )

        assertNull(repository.page?.nextCursor)
        assertNull(repository.beginLoadMore())
    }

    @Test
    fun `cancelling current request releases single flight without changing the page`() {
        val repository = repositoryWithFirstPage()
        val append = requireNotNull(repository.beginLoadMore())

        repository.cancel(append)

        assertFalse(repository.isLoadingMore)
        assertEquals(REVISION_A, repository.page?.catalogRevision)
        val replacementAppend = requireNotNull(repository.beginLoadMore())
        val refresh = requireNotNull(repository.beginRefresh())
        repository.cancel(replacementAppend)
        repository.cancel(refresh)
        assertFalse(repository.isRefreshing)
    }

    private fun repositoryWithFirstPage(): SessionLedgerRepository {
        return SessionLedgerRepository().also { repository ->
            val request = requireNotNull(repository.beginRefresh(takeId = TAKE_ID))
            assertIs<SessionLedgerApplyResult.Applied>(
                repository.complete(request, SessionListResult.Page(page(REVISION_A, "cursor-2", 1))),
            )
        }
    }

    private fun page(
        revision: String,
        nextCursor: String?,
        sessionNumber: Int,
        requestCursor: String? = null,
        requestTakeId: String? = TAKE_ID,
    ): SessionListPage {
        return SessionListPage(
            contract = SessionListContract.V3,
            catalogRevision = revision,
            items = listOf(summary(sessionNumber, takeId = requestTakeId ?: TAKE_ID)),
            diagnostics = listOf(diagnostic(sessionNumber)),
            nextCursor = nextCursor,
            requestIdentity = SessionListRequestIdentity(
                limit = 50,
                cursor = requestCursor,
                takeId = requestTakeId,
            ),
        )
    }

    private fun summary(number: Int, takeId: String): SessionSummary {
        val minute = (10 - number).toString().padStart(2, '0')
        return SessionSummary(
            sessionId = sessionId(number),
            producerOutcome = "sealed",
            takeId = takeId,
            displayName = "session $number",
            deviceLabel = "YLX-00ABCDEF",
            startedAt = "2026-08-28T04:$minute:00Z",
            endedAt = "2026-08-28T04:$minute:10Z",
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
                verifiedAt = "2026-08-28T04:$minute:11Z",
                verdict = GatewayVerificationVerdict.USABLE,
                diagnostics = emptyList(),
            ),
        )
    }

    private fun diagnostic(number: Int): SessionDiscoveryDiagnostic {
        return SessionDiscoveryDiagnostic(
            quarantineId = quarantineId(number),
            code = "manifest_invalid",
            observedAt = "2026-08-28T04:00:00Z",
            message = "manifest $number is invalid",
        )
    }

    private fun sessionId(number: Int): String {
        return "01991b70-7c88-7123-9234-${number.toString().padStart(12, '0')}"
    }

    private fun quarantineId(number: Int): String {
        return "56005c52-31f1-4dac-91cd-${number.toString().padStart(12, '0')}"
    }

    private companion object {
        const val REVISION_A =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val REVISION_B =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TAKE_ID = "01991b70-7c88-7456-9234-123456789abc"
        const val TAKE_ID_B = "01991b70-7c88-7567-9234-123456789abc"
        const val TAKE_ID_C = "01991b70-7c88-7678-9234-123456789abc"
    }
}
