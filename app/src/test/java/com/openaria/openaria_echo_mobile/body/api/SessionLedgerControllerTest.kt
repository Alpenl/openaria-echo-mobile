package com.openaria.openaria_echo_mobile.body.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionLedgerControllerTest {
    @Test
    fun `load more catalog change performs a fresh third request without cancelling its worker`() =
        runBlocking {
            val transport = ScriptedTransport(
                results =
                    listOf(
                        pageResult(REVISION_A, CURSOR, takeId = TAKE_ID),
                        SessionListResult.CatalogChanged(REVISION_B),
                        pageResult(REVISION_B, nextCursor = null, takeId = TAKE_ID),
                    ),
            )
            val controller = controller(transport)

            controller.refresh("device", SessionFilterIntent.Exact(TAKE_ID))
            controller.awaitIdle()
            controller.loadMore("device")
            controller.awaitIdle()

            assertEquals(3, transport.requests.size)
            assertRequest(transport.requests[0], cursor = null, revision = null, takeId = TAKE_ID)
            assertRequest(transport.requests[1], cursor = CURSOR, revision = REVISION_A, takeId = TAKE_ID)
            assertRequest(transport.requests[2], cursor = null, revision = null, takeId = TAKE_ID)
            assertFalse(transport.cancellations[1].cancelled)
            assertEquals(REVISION_B, controller.state.page?.catalogRevision)
            assertNull(controller.state.failure)
        }

    @Test
    fun `ordinary refresh inherits the desired take filter until explicitly cleared`() =
        runBlocking {
            val transport = ScriptedTransport(
                results =
                    listOf(
                        pageResult(REVISION_A, nextCursor = null, takeId = TAKE_ID),
                        pageResult(REVISION_B, nextCursor = null, takeId = TAKE_ID),
                        pageResult(REVISION_C, nextCursor = null, takeId = null),
                    ),
            )
            val controller = controller(transport)

            controller.refresh("device", SessionFilterIntent.Exact(TAKE_ID))
            controller.awaitIdle()
            controller.refresh("device", SessionFilterIntent.InheritCurrentFilter)
            controller.awaitIdle()
            controller.refresh("device", SessionFilterIntent.ClearFilter)
            controller.awaitIdle()

            assertEquals(listOf(TAKE_ID, TAKE_ID, null), transport.requests.map { it.takeId })
            assertNull(controller.currentTakeId)
            assertEquals(REVISION_C, controller.state.page?.catalogRevision)
        }

    @Test
    fun `ordinary refresh interrupts append and inherits its take filter`() =
        runBlocking {
            val appendStarted = CompletableDeferred<Unit>()
            val requests = mutableListOf<SessionLedgerRequest>()
            val cancellations = mutableListOf<FakeCancellation>()
            val controller =
                SessionLedgerController<String, FakeCancellation>(
                    scope = this,
                    cancellationFactory = { FakeCancellation().also(cancellations::add) },
                    cancelTransport = FakeCancellation::cancel,
                    transport = { _, request, _ ->
                        requests += request
                        when (requests.size) {
                            1 -> pageResult(REVISION_A, CURSOR, takeId = TAKE_ID)
                            2 -> {
                                appendStarted.complete(Unit)
                                awaitCancellation()
                            }
                            else -> pageResult(REVISION_B, nextCursor = null, takeId = TAKE_ID)
                        }
                    },
                )

            controller.refresh("device", SessionFilterIntent.Exact(TAKE_ID))
            controller.awaitIdle()
            controller.loadMore("device")
            appendStarted.await()
            controller.refresh("device", SessionFilterIntent.InheritCurrentFilter)
            controller.awaitIdle()

            assertEquals(3, requests.size)
            assertRequest(requests[1], cursor = CURSOR, revision = REVISION_A, takeId = TAKE_ID)
            assertRequest(requests[2], cursor = null, revision = null, takeId = TAKE_ID)
            assertTrue(cancellations[1].cancelled)
            assertEquals(REVISION_B, controller.state.page?.catalogRevision)
        }

    @Test
    fun `late response from superseded filter cannot apply`() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val requests = mutableListOf<SessionLedgerRequest>()
            val cancellations = mutableListOf<FakeCancellation>()
            val controller =
                SessionLedgerController<String, FakeCancellation>(
                    scope = this,
                    cancellationFactory = { FakeCancellation().also(cancellations::add) },
                    cancelTransport = FakeCancellation::cancel,
                    transport = { _, request, _ ->
                        requests += request
                        if (requests.size == 1) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            pageResult(REVISION_A, nextCursor = null, takeId = TAKE_ID_A)
                        } else {
                            pageResult(REVISION_B, nextCursor = null, takeId = TAKE_ID_B)
                        }
                    },
                )

            controller.refresh("device", SessionFilterIntent.Exact(TAKE_ID_A))
            firstStarted.await()
            controller.refresh("device", SessionFilterIntent.Exact(TAKE_ID_B))
            assertFalse(cancellations.single().cancelled)
            releaseFirst.complete(Unit)
            controller.awaitIdle()

            assertEquals(listOf(TAKE_ID_A, TAKE_ID_B), requests.map { it.takeId })
            assertTrue(cancellations.none { it.cancelled })
            assertEquals(REVISION_B, controller.state.page?.catalogRevision)
            assertEquals(TAKE_ID_B, controller.currentTakeId)
        }

    @Test
    fun `latest queued refresh drains after superseded transport throws`() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val targets = mutableListOf<String>()
            val requests = mutableListOf<SessionLedgerRequest>()
            val controller =
                SessionLedgerController<String, FakeCancellation>(
                    scope = this,
                    cancellationFactory = ::FakeCancellation,
                    cancelTransport = FakeCancellation::cancel,
                    transport = { target, request, _ ->
                        targets += target
                        requests += request
                        if (requests.size == 1) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            error("superseded transport failure")
                        }
                        pageResult(
                            revision = REVISION_C,
                            nextCursor = null,
                            takeId = TAKE_ID_C,
                            limit = LATEST_LIMIT,
                        )
                    },
                )

            controller.refresh(
                target = "first-device",
                filterIntent = SessionFilterIntent.Exact(TAKE_ID_A),
                limit = 41,
            )
            firstStarted.await()
            controller.refresh(
                target = "superseded-device",
                filterIntent = SessionFilterIntent.Exact(TAKE_ID_B),
                limit = 57,
            )
            controller.refresh(
                target = "latest-device",
                filterIntent = SessionFilterIntent.Exact(TAKE_ID_C),
                limit = LATEST_LIMIT,
            )
            releaseFirst.complete(Unit)
            controller.awaitIdle()

            assertEquals(listOf("first-device", "latest-device"), targets)
            assertEquals(2, requests.size)
            assertRequest(requests[0], cursor = null, revision = null, takeId = TAKE_ID_A)
            assertEquals(41, requests[0].limit)
            assertRequest(requests[1], cursor = null, revision = null, takeId = TAKE_ID_C)
            assertEquals(LATEST_LIMIT, requests[1].limit)
            assertEquals(TAKE_ID_C, controller.currentTakeId)
            assertEquals(REVISION_C, controller.state.page?.catalogRevision)
            assertNull(controller.state.failure)
            assertFalse(controller.state.isRefreshing)
            assertFalse(controller.state.isLoadingMore)
        }

    @Test
    fun `repeated catalog change is a stable typed protocol failure`() =
        runBlocking {
            val transport = ScriptedTransport(
                results =
                    listOf(
                        pageResult(REVISION_A, CURSOR, takeId = TAKE_ID),
                        SessionListResult.CatalogChanged(REVISION_B),
                        SessionListResult.CatalogChanged(REVISION_C),
                    ),
            )
            val controller = controller(transport)

            controller.refresh("device", SessionFilterIntent.Exact(TAKE_ID))
            controller.awaitIdle()
            controller.loadMore("device")
            controller.awaitIdle()

            val failure = assertIs<SessionLedgerFailure.Protocol>(controller.state.failure)
            assertEquals(SessionProtocolFailureReason.CATALOG_RECOVERY_REPEATED, failure.reason)
            assertEquals(REVISION_C, failure.catalogRevision)
            assertNull(failure.diagnosticDetail)
            assertEquals(3, transport.requests.size)
        }

    @Test
    fun `refresh transport exception becomes sanitized failure and clears spinner`() =
        runBlocking {
            val controller =
                SessionLedgerController<String, FakeCancellation>(
                    scope = this,
                    cancellationFactory = ::FakeCancellation,
                    cancelTransport = FakeCancellation::cancel,
                    transport = { _, _, _ ->
                        error("sensitive refresh transport implementation detail")
                    },
                )

            controller.refresh("device")
            controller.awaitIdle()

            assertFalse(controller.state.isRefreshing)
            assertFalse(controller.state.isLoadingMore)
            assertNull(controller.state.page)
            val failure = assertIs<SessionLedgerFailure.UnexpectedTransport>(
                controller.state.failure,
            )
            assertFalse(failure.toString().contains("sensitive refresh"))
        }

    @Test
    fun `append transport exception preserves page and clears spinner without crashing scope`() =
        runBlocking {
            var requestCount = 0
            val controller =
                SessionLedgerController<String, FakeCancellation>(
                    scope = this,
                    cancellationFactory = ::FakeCancellation,
                    cancelTransport = FakeCancellation::cancel,
                    transport = { _, _, _ ->
                        requestCount += 1
                        if (requestCount == 1) {
                            pageResult(REVISION_A, CURSOR, takeId = TAKE_ID)
                        } else {
                            error("sensitive append transport implementation detail")
                        }
                    },
                )

            controller.refresh("device", SessionFilterIntent.Exact(TAKE_ID))
            controller.awaitIdle()
            val pageBeforeAppend = controller.state.page

            controller.loadMore("device")
            controller.awaitIdle()

            assertEquals(2, requestCount)
            assertFalse(controller.state.isRefreshing)
            assertFalse(controller.state.isLoadingMore)
            assertEquals(pageBeforeAppend, controller.state.page)
            val failure = assertIs<SessionLedgerFailure.UnexpectedTransport>(
                controller.state.failure,
            )
            assertFalse(failure.toString().contains("sensitive append"))
        }

    private fun CoroutineScope.controller(
        transport: ScriptedTransport,
    ): SessionLedgerController<String, FakeCancellation> {
        return SessionLedgerController(
            scope = this,
            cancellationFactory = transport::newCancellation,
            cancelTransport = FakeCancellation::cancel,
            transport = transport::execute,
        )
    }

    private class ScriptedTransport(
        results: List<SessionListResult>,
    ) {
        val requests = mutableListOf<SessionLedgerRequest>()
        val cancellations = mutableListOf<FakeCancellation>()
        private val remaining = ArrayDeque(results)

        fun newCancellation(): FakeCancellation {
            return FakeCancellation().also(cancellations::add)
        }

        suspend fun execute(
            target: String,
            request: SessionLedgerRequest,
            cancellation: FakeCancellation,
        ): SessionListResult {
            assertEquals("device", target)
            assertFalse(cancellation.cancelled)
            requests += request
            return remaining.removeFirst()
        }
    }

    private class FakeCancellation {
        var cancelled = false
            private set

        fun cancel() {
            cancelled = true
        }
    }

    private companion object {
        const val CURSOR = "opaque-page-2"
        const val REVISION_A =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val REVISION_B =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val REVISION_C =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val TAKE_ID = "01991b70-7c88-7456-9234-123456789abc"
        const val TAKE_ID_A = "01991b70-7c88-7456-9234-123456789abc"
        const val TAKE_ID_B = "01991b70-7c88-7567-9234-123456789abc"
        const val TAKE_ID_C = "01991b70-7c88-7678-9234-123456789abc"
        const val LATEST_LIMIT = 73
    }
}

private fun pageResult(
    revision: String,
    nextCursor: String?,
    takeId: String?,
    limit: Int = 50,
): SessionListResult.Page {
    return SessionListResult.Page(
        SessionListPage(
            contract = SessionListContract.V3,
            catalogRevision = revision,
            items = emptyList(),
            diagnostics = emptyList(),
            nextCursor = nextCursor,
            requestIdentity = SessionListRequestIdentity(limit = limit, cursor = null, takeId = takeId),
        ),
    )
}

private fun assertRequest(
    request: SessionLedgerRequest,
    cursor: String?,
    revision: String?,
    takeId: String?,
) {
    assertEquals(cursor, request.cursor)
    assertEquals(revision, request.catalogRevision)
    assertEquals(takeId, request.takeId)
}
