package com.openaria.openaria_echo_mobile.ui

internal data class CaptureReconciliationRequest(
    val id: Long,
    val connectionGeneration: Long,
    val forced: Boolean,
)

internal enum class CaptureReconciliationResponseDisposition {
    CURRENT,
    SUPERSEDED,
    IGNORED,
}

internal class CaptureReconciliationCoordinator(
    private val connectionGeneration: Long,
    private val gate: ReconciliationGate,
) {
    private var nextRequestId = 0L
    private var inFlight: CaptureReconciliationRequest? = null
    private var pendingForcedRefresh = false

    fun begin(nowMs: Long, force: Boolean): CaptureReconciliationRequest? {
        if (inFlight != null) {
            if (force) pendingForcedRefresh = true
            return null
        }

        val effectiveForce = force || pendingForcedRefresh
        if (!gate.tryAcquire(nowMs, effectiveForce)) return null

        pendingForcedRefresh = false
        val request = CaptureReconciliationRequest(
            id = ++nextRequestId,
            connectionGeneration = connectionGeneration,
            forced = effectiveForce,
        )
        inFlight = request
        return request
    }

    fun complete(request: CaptureReconciliationRequest): CaptureReconciliationResponseDisposition {
        if (inFlight != request) return CaptureReconciliationResponseDisposition.IGNORED
        inFlight = null
        return if (pendingForcedRefresh) {
            CaptureReconciliationResponseDisposition.SUPERSEDED
        } else {
            CaptureReconciliationResponseDisposition.CURRENT
        }
    }

    fun cancel(request: CaptureReconciliationRequest) {
        if (inFlight == request) inFlight = null
    }
}
