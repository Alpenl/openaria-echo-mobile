package com.openaria.openaria_echo_mobile.body.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** Owns session-list intent coalescing and transport effects on one serial UI dispatcher. */
class SessionLedgerController<Target, TransportCancellation>(
    private val scope: CoroutineScope,
    private val cancellationFactory: () -> TransportCancellation,
    private val cancelTransport: (TransportCancellation) -> Unit,
    private val transport:
        suspend (Target, SessionLedgerRequest, TransportCancellation) -> SessionListResult,
    private val onStateChanged: (SessionLedgerControllerState) -> Unit = {},
    private val repository: SessionLedgerRepository = SessionLedgerRepository(),
) {
    private var operationGeneration = 0L
    private var desiredQuery = DesiredSessionQuery(takeId = null, limit = DEFAULT_LIMIT)
    private var pendingRefresh: PendingSessionLedgerRefresh<Target>? = null
    private var pendingLoadMore: PendingSessionLedgerLoadMore<Target>? = null
    private var workerJob: Job? = null
    private var activeOperation: ActiveSessionLedgerOperation<TransportCancellation>? = null
    private var failure: SessionLedgerFailure? = null

    var state: SessionLedgerControllerState = snapshot()
        private set

    val currentTakeId: String?
        get() = desiredQuery.takeId

    fun refresh(
        target: Target,
        filterIntent: SessionFilterIntent = SessionFilterIntent.InheritCurrentFilter,
        limit: Int = DEFAULT_LIMIT,
    ) {
        require(limit in 1..200) { "limit must be in 1..200" }
        desiredQuery = DesiredSessionQuery(resolveTakeId(filterIntent), limit)
        failure = null
        if (repository.isRefreshing) {
            operationGeneration += 1L
            pendingRefresh = PendingSessionLedgerRefresh(
                target = target,
                query = desiredQuery,
                catalogRecovery = false,
            )
            publish()
            return
        }
        if (pendingRefresh != null) {
            pendingRefresh = PendingSessionLedgerRefresh(target, desiredQuery, catalogRecovery = false)
            publish()
            return
        }
        if (activeOperation?.kind == SessionLedgerRequestKind.APPEND || repository.isLoadingMore) {
            operationGeneration += 1L
            cancelActiveWorker()
            repository.cancelLoadMore()
        }
        pendingLoadMore = null
        pendingRefresh = PendingSessionLedgerRefresh(target, desiredQuery, catalogRecovery = false)
        publish()
        ensureWorker()
    }

    fun loadMore(target: Target) {
        if (pendingRefresh != null || repository.isRefreshing || pendingLoadMore != null ||
            repository.isLoadingMore
        ) {
            return
        }
        if (repository.page?.nextCursor == null) return
        pendingLoadMore = PendingSessionLedgerLoadMore(target)
        failure = null
        publish()
        ensureWorker()
    }

    fun cancelLoadMore() {
        val activeAppend = activeOperation?.kind == SessionLedgerRequestKind.APPEND
        if (pendingLoadMore == null && !activeAppend && !repository.isLoadingMore) return
        pendingLoadMore = null
        if (activeAppend) {
            operationGeneration += 1L
            cancelActiveWorker()
        }
        repository.cancelLoadMore()
        publish()
    }

    fun cancelInFlight() {
        operationGeneration += 1L
        pendingRefresh = null
        pendingLoadMore = null
        cancelActiveWorker()
        repository.cancelInFlight()
        publish()
    }

    fun reset() {
        operationGeneration += 1L
        pendingRefresh = null
        pendingLoadMore = null
        cancelActiveWorker()
        repository.reset()
        desiredQuery = DesiredSessionQuery(takeId = null, limit = DEFAULT_LIMIT)
        failure = null
        publish()
    }

    suspend fun awaitIdle() {
        while (true) {
            val observed = workerJob ?: return
            observed.join()
            if (workerJob == null) return
        }
    }

    private fun resolveTakeId(intent: SessionFilterIntent): String? {
        return when (intent) {
            SessionFilterIntent.InheritCurrentFilter -> desiredQuery.takeId
            SessionFilterIntent.ClearFilter -> null
            is SessionFilterIntent.Exact -> intent.takeId
        }
    }

    private fun cancelActiveWorker() {
        activeOperation?.let { cancelTransport(it.cancellation) }
        activeOperation = null
        workerJob?.cancel()
        workerJob = null
    }

    private fun ensureWorker() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            val runningJob = currentCoroutineContext()[Job]
            try {
                drainEffects()
            } finally {
                if (workerJob === runningJob) {
                    workerJob = null
                    if (pendingRefresh != null || pendingLoadMore != null) ensureWorker()
                }
            }
        }
    }

    private suspend fun drainEffects() {
        while (true) {
            val refresh = pendingRefresh
            if (refresh != null) {
                pendingRefresh = null
                executeRefresh(refresh)
                continue
            }
            val loadMore = pendingLoadMore
            if (loadMore != null) {
                pendingLoadMore = null
                executeLoadMore(loadMore)
                continue
            }
            return
        }
    }

    private suspend fun executeRefresh(effect: PendingSessionLedgerRefresh<Target>) {
        val request = repository.beginRefresh(
            takeId = effect.query.takeId,
            limit = effect.query.limit,
            catalogRecovery = effect.catalogRecovery,
        ) ?: return
        publish()
        executeRequest(effect.target, request)
    }

    private suspend fun executeLoadMore(effect: PendingSessionLedgerLoadMore<Target>) {
        val request = repository.beginLoadMore() ?: run {
            publish()
            return
        }
        publish()
        executeRequest(effect.target, request)
    }

    private suspend fun executeRequest(
        target: Target,
        request: SessionLedgerRequest,
    ) {
        val requestGeneration = operationGeneration
        val cancellation = cancellationFactory()
        val operation = ActiveSessionLedgerOperation(request.kind, cancellation)
        activeOperation = operation
        val result = try {
            transport(target, request, cancellation)
        } catch (exception: CancellationException) {
            repository.cancel(request)
            throw exception
        } catch (_: Throwable) {
            cancelTransport(cancellation)
            repository.cancel(request)
            if (requestGeneration == operationGeneration) {
                failure = SessionLedgerFailure.UnexpectedTransport
                publish()
            }
            return
        } finally {
            if (activeOperation === operation) activeOperation = null
        }

        if (requestGeneration != operationGeneration) {
            repository.cancel(request)
            publish()
            return
        }
        applyResult(target, repository.complete(request, result))
    }

    private fun applyResult(
        target: Target,
        result: SessionLedgerApplyResult,
    ) {
        when (result) {
            SessionLedgerApplyResult.Applied -> failure = null
            SessionLedgerApplyResult.Ignored -> Unit
            is SessionLedgerApplyResult.Failed -> failure = result.failure
            is SessionLedgerApplyResult.RefreshRequired -> {
                desiredQuery = DesiredSessionQuery(result.takeId, result.limit)
                pendingRefresh = PendingSessionLedgerRefresh(
                    target = target,
                    query = desiredQuery,
                    catalogRecovery =
                        result.reason == SessionLedgerRefreshReason.CATALOG_RECOVERY_REQUIRED,
                )
                failure = null
            }
        }
        publish()
    }

    private fun publish() {
        state = snapshot()
        onStateChanged(state)
    }

    private fun snapshot(): SessionLedgerControllerState {
        return SessionLedgerControllerState(
            page = repository.page,
            isRefreshing = pendingRefresh != null || repository.isRefreshing,
            isLoadingMore = pendingLoadMore != null || repository.isLoadingMore,
            failure = failure,
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}

sealed interface SessionFilterIntent {
    data object InheritCurrentFilter : SessionFilterIntent
    data object ClearFilter : SessionFilterIntent
    data class Exact(val takeId: String) : SessionFilterIntent
}

data class SessionLedgerControllerState(
    val page: SessionListPage?,
    val isRefreshing: Boolean,
    val isLoadingMore: Boolean,
    val failure: SessionLedgerFailure?,
)

private data class DesiredSessionQuery(
    val takeId: String?,
    val limit: Int,
)

private data class PendingSessionLedgerRefresh<Target>(
    val target: Target,
    val query: DesiredSessionQuery,
    val catalogRecovery: Boolean,
)

private data class PendingSessionLedgerLoadMore<Target>(
    val target: Target,
)

private data class ActiveSessionLedgerOperation<TransportCancellation>(
    val kind: SessionLedgerRequestKind,
    val cancellation: TransportCancellation,
)
