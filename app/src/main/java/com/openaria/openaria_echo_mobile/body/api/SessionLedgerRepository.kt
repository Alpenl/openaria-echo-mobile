package com.openaria.openaria_echo_mobile.body.api

import java.time.OffsetDateTime

class SessionLedgerRepository {
    private var nextRequestId = 0L
    private var chainGeneration = 0L
    private var activeRefresh: SessionLedgerRequest? = null
    private var activeAppend: SessionLedgerRequest? = null
    private var pendingRefresh: PendingSessionRefresh? = null
    private var takeId: String? = null
    private val consumedCursors = mutableSetOf<String>()

    var page: SessionListPage? = null
        private set

    val isRefreshing: Boolean
        get() = activeRefresh != null

    val isLoadingMore: Boolean
        get() = activeAppend != null

    fun reset() {
        chainGeneration += 1L
        activeRefresh = null
        activeAppend = null
        pendingRefresh = null
        takeId = null
        consumedCursors.clear()
        page = null
    }

    fun beginRefresh(
        takeId: String? = null,
        limit: Int = 50,
        catalogRecovery: Boolean = false,
    ): SessionLedgerRequest? {
        require(limit in 1..200) { "limit must be in 1..200" }
        if (activeRefresh != null) {
            val pendingCatalogRecovery = catalogRecovery || pendingRefresh?.catalogRecovery == true
            pendingRefresh = PendingSessionRefresh(
                takeId = takeId,
                limit = limit,
                catalogRecovery = pendingCatalogRecovery,
            )
            return null
        }

        chainGeneration += 1L
        activeAppend = null
        pendingRefresh = null
        return newRequest(
            kind = SessionLedgerRequestKind.REFRESH,
            limit = limit,
            cursor = null,
            catalogRevision = null,
            takeId = takeId,
            catalogRecovery = catalogRecovery,
        ).also { activeRefresh = it }
    }

    fun beginLoadMore(): SessionLedgerRequest? {
        if (activeRefresh != null || activeAppend != null) return null
        val current = page ?: return null
        if (current.contract != SessionListContract.V3) return null
        val catalogRevision = current.catalogRevision ?: return null
        val cursor = current.nextCursor ?: return null

        return newRequest(
            kind = SessionLedgerRequestKind.APPEND,
            limit = current.requestIdentity.limit,
            cursor = cursor,
            catalogRevision = catalogRevision,
            takeId = takeId,
            catalogRecovery = false,
        ).also { activeAppend = it }
    }

    fun complete(
        request: SessionLedgerRequest,
        result: SessionListResult,
    ): SessionLedgerApplyResult {
        return when (request.kind) {
            SessionLedgerRequestKind.REFRESH -> completeRefresh(request, result)
            SessionLedgerRequestKind.APPEND -> completeAppend(request, result)
        }
    }

    /** Releases [request] and hands any coalesced refresh intent back to the controller. */
    fun cancel(request: SessionLedgerRequest): SessionLedgerApplyResult {
        return when (request.kind) {
            SessionLedgerRequestKind.REFRESH -> {
                if (activeRefresh === request) {
                    activeRefresh = null
                    takePendingRefresh() ?: SessionLedgerApplyResult.Ignored
                } else {
                    SessionLedgerApplyResult.Ignored
                }
            }
            SessionLedgerRequestKind.APPEND -> {
                if (activeAppend === request) activeAppend = null
                SessionLedgerApplyResult.Ignored
            }
        }
    }

    fun cancelInFlight() {
        if (activeRefresh == null && activeAppend == null) return
        chainGeneration += 1L
        activeRefresh = null
        activeAppend = null
        pendingRefresh = null
    }

    fun cancelLoadMore() {
        if (activeAppend == null) return
        chainGeneration += 1L
        activeAppend = null
    }

    private fun completeRefresh(
        request: SessionLedgerRequest,
        result: SessionListResult,
    ): SessionLedgerApplyResult {
        if (activeRefresh !== request || request.chainGeneration != chainGeneration) {
            return SessionLedgerApplyResult.Ignored
        }
        activeRefresh = null
        if (request.catalogRecovery && result is SessionListResult.CatalogChanged) {
            discardChain(request.takeId)
            return SessionLedgerApplyResult.Failed(
                SessionLedgerFailure.Protocol(
                    reason = SessionProtocolFailureReason.CATALOG_RECOVERY_REPEATED,
                    catalogRevision = result.catalogRevision,
                ),
            )
        }
        takePendingRefresh()?.let { return it }

        return when (result) {
            is SessionListResult.Page -> {
                val pageFailure = pageInvariantFailure(result.value, request)
                if (pageFailure != null) {
                    return SessionLedgerApplyResult.Failed(
                        SessionLedgerFailure.Protocol(
                            pageFailure,
                        ),
                    )
                }
                page = normalizeFirstPage(result.value)
                takeId = request.takeId
                consumedCursors.clear()
                SessionLedgerApplyResult.Applied
            }
            is SessionListResult.CatalogChanged -> {
                discardChain(request.takeId)
                SessionLedgerApplyResult.RefreshRequired(
                    takeId = request.takeId,
                    limit = request.limit,
                    reason = SessionLedgerRefreshReason.CATALOG_RECOVERY_REQUIRED,
                )
            }
            else -> SessionLedgerApplyResult.Failed(SessionLedgerFailure.Transport(result))
        }
    }

    private fun completeAppend(
        request: SessionLedgerRequest,
        result: SessionListResult,
    ): SessionLedgerApplyResult {
        if (activeAppend !== request || request.chainGeneration != chainGeneration) {
            return SessionLedgerApplyResult.Ignored
        }
        activeAppend = null

        val current = page
        if (current == null ||
            current.contract != SessionListContract.V3 ||
            current.catalogRevision != request.catalogRevision ||
            current.nextCursor != request.cursor ||
            takeId != request.takeId
        ) {
            return SessionLedgerApplyResult.Ignored
        }

        return when (result) {
            is SessionListResult.Page -> {
                val next = result.value
                val pageFailure = pageInvariantFailure(next, request)
                if (!next.matches(request)) {
                    SessionLedgerApplyResult.Failed(
                        SessionLedgerFailure.Protocol(
                            SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH,
                        ),
                    )
                } else if (next.contract != SessionListContract.V3 ||
                    next.catalogRevision != request.catalogRevision
                ) {
                    discardChain(request.takeId)
                    SessionLedgerApplyResult.RefreshRequired(
                        takeId = request.takeId,
                        limit = request.limit,
                        reason = SessionLedgerRefreshReason.CATALOG_RECOVERY_REQUIRED,
                    )
                } else if (pageFailure != null) {
                    SessionLedgerApplyResult.Failed(
                        SessionLedgerFailure.Protocol(
                            pageFailure,
                        ),
                    )
                } else if (next.nextCursor != null &&
                    (next.nextCursor == request.cursor || next.nextCursor in consumedCursors)
                ) {
                    SessionLedgerApplyResult.Failed(
                        SessionLedgerFailure.Protocol(
                            SessionProtocolFailureReason.CURSOR_DID_NOT_ADVANCE,
                        ),
                    )
                } else if (hasCrossPageDuplicate(current, next)) {
                    SessionLedgerApplyResult.Failed(
                        SessionLedgerFailure.Protocol(
                            SessionProtocolFailureReason.DUPLICATE_IDENTITY,
                        ),
                    )
                } else if (hasBoundaryInversion(current, next)) {
                    SessionLedgerApplyResult.Failed(
                        SessionLedgerFailure.Protocol(
                            SessionProtocolFailureReason.NEWEST_FIRST_BOUNDARY_INVERTED,
                        ),
                    )
                } else {
                    request.cursor?.let(consumedCursors::add)
                    page = appendSameRevision(current, next)
                    SessionLedgerApplyResult.Applied
                }
            }
            is SessionListResult.CatalogChanged -> {
                discardChain(request.takeId)
                SessionLedgerApplyResult.RefreshRequired(
                    takeId = request.takeId,
                    limit = request.limit,
                    reason = SessionLedgerRefreshReason.CATALOG_RECOVERY_REQUIRED,
                )
            }
            else -> SessionLedgerApplyResult.Failed(SessionLedgerFailure.Transport(result))
        }
    }

    private fun normalizeFirstPage(value: SessionListPage): SessionListPage {
        return if (value.contract == SessionListContract.V2) {
            value.copy(catalogRevision = null, nextCursor = null)
        } else {
            value
        }
    }

    /**
     * DeviceHttpClient validates decoded JSON pages, but callers can also supply a typed page
     * directly. Keep that boundary defensive so an invalid transport cannot publish or merge data.
     */
    private fun pageInvariantFailure(
        value: SessionListPage,
        request: SessionLedgerRequest,
    ): SessionProtocolFailureReason? {
        if (!value.matches(request)) return SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH
        if (value.items.size + value.diagnostics.size > request.limit) {
            return SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH
        }
        if (request.takeId != null && value.items.any { it.takeId != request.takeId }) {
            return SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH
        }
        if (value.nextCursor?.isBlank() == true) {
            return SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH
        }
        if (value.contract == SessionListContract.V2 && value.catalogRevision != null) {
            return SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH
        }
        if (value.contract == SessionListContract.V3 && value.catalogRevision.isNullOrBlank()) {
            return SessionProtocolFailureReason.REQUEST_IDENTITY_MISMATCH
        }
        if (value.items.map { it.sessionId }.toSet().size != value.items.size ||
            value.diagnostics.map { it.quarantineId }.toSet().size != value.diagnostics.size
        ) {
            return SessionProtocolFailureReason.DUPLICATE_IDENTITY
        }
        if (value.items.any { parseStartedAt(it.startedAt) == null } ||
            !value.items.zipWithNext().all { (newer, older) -> isStrictlyNewer(newer, older) }
        ) {
            return SessionProtocolFailureReason.NEWEST_FIRST_BOUNDARY_INVERTED
        }
        return null
    }

    private fun appendSameRevision(
        current: SessionListPage,
        next: SessionListPage,
    ): SessionListPage {
        return current.copy(
            items = current.items + next.items,
            diagnostics = current.diagnostics + next.diagnostics,
            nextCursor = next.nextCursor,
            requestIdentity = next.requestIdentity,
        )
    }

    private fun hasCrossPageDuplicate(
        current: SessionListPage,
        next: SessionListPage,
    ): Boolean {
        val sessionIds = current.items.mapTo(mutableSetOf()) { it.sessionId }
        val quarantineIds = current.diagnostics.mapTo(mutableSetOf()) { it.quarantineId }
        return next.items.any { !sessionIds.add(it.sessionId) } ||
            next.diagnostics.any { !quarantineIds.add(it.quarantineId) }
    }

    private fun hasBoundaryInversion(
        current: SessionListPage,
        next: SessionListPage,
    ): Boolean {
        val accumulatedOldest = current.items.lastOrNull() ?: return false
        val nextNewest = next.items.firstOrNull() ?: return false
        val currentTime = parseStartedAt(accumulatedOldest.startedAt) ?: return true
        val nextTime = parseStartedAt(nextNewest.startedAt) ?: return true
        val timeComparison = currentTime.compareTo(nextTime)
        return timeComparison < 0 ||
            (timeComparison == 0 && accumulatedOldest.sessionId <= nextNewest.sessionId)
    }

    private fun isStrictlyNewer(newer: SessionSummary, older: SessionSummary): Boolean {
        val newerTime = parseStartedAt(newer.startedAt) ?: return false
        val olderTime = parseStartedAt(older.startedAt) ?: return false
        val timeComparison = newerTime.compareTo(olderTime)
        return timeComparison > 0 ||
            (timeComparison == 0 && newer.sessionId > older.sessionId)
    }

    private fun parseStartedAt(value: String) = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: RuntimeException) {
        null
    }

    private fun SessionListPage.matches(request: SessionLedgerRequest): Boolean {
        return requestIdentity == SessionListRequestIdentity(
            limit = request.limit,
            cursor = request.cursor,
            takeId = request.takeId,
        )
    }

    private fun discardChain(nextTakeId: String?) {
        chainGeneration += 1L
        activeRefresh = null
        activeAppend = null
        pendingRefresh = null
        takeId = nextTakeId
        consumedCursors.clear()
        page = null
    }

    private fun takePendingRefresh(): SessionLedgerApplyResult.RefreshRequired? {
        val pending = pendingRefresh ?: return null
        pendingRefresh = null
        return SessionLedgerApplyResult.RefreshRequired(
            takeId = pending.takeId,
            limit = pending.limit,
            reason = if (pending.catalogRecovery) {
                SessionLedgerRefreshReason.CATALOG_RECOVERY_REQUIRED
            } else {
                SessionLedgerRefreshReason.REQUESTED
            },
        )
    }

    private fun newRequest(
        kind: SessionLedgerRequestKind,
        limit: Int,
        cursor: String?,
        catalogRevision: String?,
        takeId: String?,
        catalogRecovery: Boolean,
    ): SessionLedgerRequest {
        nextRequestId += 1L
        return SessionLedgerRequest(
            requestId = nextRequestId,
            chainGeneration = chainGeneration,
            kind = kind,
            limit = limit,
            cursor = cursor,
            catalogRevision = catalogRevision,
            takeId = takeId,
            catalogRecovery = catalogRecovery,
        )
    }
}

class SessionLedgerRequest internal constructor(
    val requestId: Long,
    internal val chainGeneration: Long,
    val kind: SessionLedgerRequestKind,
    val limit: Int,
    val cursor: String?,
    val catalogRevision: String?,
    val takeId: String?,
    internal val catalogRecovery: Boolean,
)

enum class SessionLedgerRequestKind {
    REFRESH,
    APPEND,
}

sealed interface SessionLedgerApplyResult {
    data object Applied : SessionLedgerApplyResult
    data object Ignored : SessionLedgerApplyResult
    data class RefreshRequired(
        val takeId: String?,
        val limit: Int,
        val reason: SessionLedgerRefreshReason,
    ) : SessionLedgerApplyResult
    data class Failed(val failure: SessionLedgerFailure) : SessionLedgerApplyResult
}

enum class SessionLedgerRefreshReason {
    REQUESTED,
    CATALOG_RECOVERY_REQUIRED,
}

sealed interface SessionLedgerFailure {
    data class Transport(val result: SessionListResult) : SessionLedgerFailure

    data object UnexpectedTransport : SessionLedgerFailure

    data class Protocol(
        val reason: SessionProtocolFailureReason,
        val diagnosticDetail: String? = null,
        val catalogRevision: String? = null,
    ) : SessionLedgerFailure
}

enum class SessionProtocolFailureReason {
    REQUEST_IDENTITY_MISMATCH,
    CATALOG_RECOVERY_REPEATED,
    CURSOR_DID_NOT_ADVANCE,
    DUPLICATE_IDENTITY,
    NEWEST_FIRST_BOUNDARY_INVERTED,
}

private data class PendingSessionRefresh(
    val takeId: String?,
    val limit: Int,
    val catalogRecovery: Boolean,
)
