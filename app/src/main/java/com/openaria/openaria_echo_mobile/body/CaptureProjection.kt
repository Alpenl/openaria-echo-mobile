package com.openaria.openaria_echo_mobile.body

import com.openaria.openaria_echo_mobile.body.api.CaptureRevisionRelation
import com.openaria.openaria_echo_mobile.body.api.CaptureStatusSnapshot
import com.openaria.openaria_echo_mobile.body.api.CaptureStreamEvent

object CaptureProjection {
    fun markCommandSubmitting(
        state: CaptureProjectionState,
        kind: CaptureCommandKind,
        idempotencyKey: String,
    ): CaptureProjectionState {
        return state.copy(
            pendingCommand = CapturePendingCommand(kind, idempotencyKey),
        )
    }

    fun applyHttpSnapshot(
        state: CaptureProjectionState,
        snapshot: CaptureStatusSnapshot,
    ): CaptureProjectionResult {
        val current = state.snapshot
        if (current != null &&
            current.authorityEpoch == snapshot.authorityEpoch &&
            snapshot.sourceRevision < current.sourceRevision
        ) {
            return CaptureProjectionResult(state = state, accepted = false, stale = true)
        }

        val epochChanged = current != null && current.authorityEpoch != snapshot.authorityEpoch
        val nextState = state.copy(
            snapshot = snapshot,
            pendingCommand = if (epochChanged) {
                null
            } else {
                settlePendingCommand(state.pendingCommand, snapshot)
            },
            lastAuthorityEpoch = snapshot.authorityEpoch,
            lastSourceRevision = snapshot.sourceRevision,
        )
        return CaptureProjectionResult(
            state = nextState,
            accepted = true,
            clearedEpochBoundState = epochChanged,
        )
    }

    fun applyStreamEvent(
        state: CaptureProjectionState,
        event: CaptureStreamEvent,
    ): CaptureProjectionResult {
        val relation = revisionRelation(
            previousAuthorityEpoch = state.lastAuthorityEpoch,
            previousSourceRevision = state.lastSourceRevision,
            nextAuthorityEpoch = event.authorityEpoch,
            nextSourceRevision = event.sourceRevision,
        )
        val stateWithEventId = state.copy(lastEventId = event.sseDeliveryId)

        return when (relation) {
            CaptureRevisionRelation.Stale -> CaptureProjectionResult(
                state = stateWithEventId,
                accepted = false,
                stale = true,
            )
            CaptureRevisionRelation.Gap -> CaptureProjectionResult(
                state = stateWithEventId,
                accepted = false,
                requiresCaptureReconciliation = true,
            )
            CaptureRevisionRelation.NewEpoch -> CaptureProjectionResult(
                state = stateWithEventId.copy(
                    snapshot = null,
                    pendingCommand = null,
                    lastAuthorityEpoch = event.authorityEpoch,
                    lastSourceRevision = event.sourceRevision,
                ),
                accepted = false,
                requiresCaptureReconciliation = true,
                clearedEpochBoundState = true,
            )
            CaptureRevisionRelation.Initial,
            CaptureRevisionRelation.Next,
            -> applySequentialStreamEvent(stateWithEventId, event)
        }
    }

    private fun applySequentialStreamEvent(
        state: CaptureProjectionState,
        event: CaptureStreamEvent,
    ): CaptureProjectionResult {
        val advancedState = state.copy(
            lastAuthorityEpoch = event.authorityEpoch,
            lastSourceRevision = event.sourceRevision,
        )
        return when {
            event.snapshot != null -> {
                val snapshotResult = applyHttpSnapshot(advancedState, event.snapshot)
                snapshotResult.copy(state = snapshotResult.state.copy(lastEventId = event.sseDeliveryId))
            }
            else -> CaptureProjectionResult(
                state = advancedState,
                accepted = true,
                requiresCaptureReconciliation = true,
            )
        }
    }

    private fun revisionRelation(
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

    private fun settlePendingCommand(
        command: CapturePendingCommand?,
        snapshot: CaptureStatusSnapshot,
    ): CapturePendingCommand? {
        command ?: return null
        return when (command.kind) {
            CaptureCommandKind.START -> if (snapshot.hasActiveRecording) null else command
            CaptureCommandKind.STOP -> if (snapshot.deviceState != "recording") null else command
        }
    }
}

data class CaptureProjectionState(
    val snapshot: CaptureStatusSnapshot? = null,
    val pendingCommand: CapturePendingCommand? = null,
    val lastEventId: String? = null,
    val lastAuthorityEpoch: String? = snapshot?.authorityEpoch,
    val lastSourceRevision: Long? = snapshot?.sourceRevision,
)

data class CaptureProjectionResult(
    val state: CaptureProjectionState,
    val accepted: Boolean,
    val stale: Boolean = false,
    val requiresCaptureReconciliation: Boolean = false,
    val clearedEpochBoundState: Boolean = false,
)

data class CapturePendingCommand(
    val kind: CaptureCommandKind,
    val idempotencyKey: String,
)

enum class CaptureCommandKind {
    START,
    STOP,
}
