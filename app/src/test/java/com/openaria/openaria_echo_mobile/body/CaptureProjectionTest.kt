package com.openaria.openaria_echo_mobile.body

import com.openaria.openaria_echo_mobile.body.api.CaptureRevisionRelation
import com.openaria.openaria_echo_mobile.body.api.CaptureStatusSnapshot
import com.openaria.openaria_echo_mobile.body.api.CaptureStreamEvent
import com.openaria.openaria_echo_mobile.body.api.DeviceRuntime
import com.openaria.openaria_echo_mobile.body.api.SafeSwapReceiptSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CaptureProjectionTest {
    @Test
    fun `accepts first HTTP snapshot as authority baseline`() {
        val snapshot = snapshot(revision = 7, deviceState = "idle")

        val result = CaptureProjection.applyHttpSnapshot(CaptureProjectionState(), snapshot)

        assertTrue(result.accepted)
        assertSame(snapshot, result.state.snapshot)
        assertEquals(EPOCH_A, result.state.lastAuthorityEpoch)
        assertEquals(7L, result.state.lastSourceRevision)
    }

    @Test
    fun `drops older HTTP snapshot from same authority epoch`() {
        val current = CaptureProjectionState(snapshot = snapshot(revision = 7, deviceState = "recording"))

        val result = CaptureProjection.applyHttpSnapshot(current, snapshot(revision = 6, deviceState = "idle"))

        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals("recording", result.state.snapshot?.deviceState)
        assertEquals(7L, result.state.lastSourceRevision)
    }

    @Test
    fun `applies next snapshot event without HTTP reconciliation`() {
        val current = CaptureProjectionState(snapshot = snapshot(revision = 7, deviceState = "idle"))
        val event = event(
            deliveryId = "1042",
            revision = 8,
            type = "snapshot",
            snapshot = snapshot(revision = 8, deviceState = "recording", hasActiveRecording = true),
        )

        val result = CaptureProjection.applyStreamEvent(current, event)

        assertTrue(result.accepted)
        assertFalse(result.requiresCaptureReconciliation)
        assertEquals("1042", result.state.lastEventId)
        assertEquals("recording", result.state.snapshot?.deviceState)
    }

    @Test
    fun `marks revision gap for HTTP reconciliation without changing snapshot`() {
        val current = CaptureProjectionState(snapshot = snapshot(revision = 7, deviceState = "idle"))
        val event = event(
            deliveryId = "1042",
            revision = 9,
            type = "state",
            sessionId = SESSION_ID,
        )

        val result = CaptureProjection.applyStreamEvent(current, event)

        assertFalse(result.accepted)
        assertTrue(result.requiresCaptureReconciliation)
        assertEquals("idle", result.state.snapshot?.deviceState)
        assertEquals("1042", result.state.lastEventId)
    }

    @Test
    fun `drops stale event from same authority epoch`() {
        val current = CaptureProjectionState(snapshot = snapshot(revision = 7, deviceState = "recording"))
        val event = event(
            deliveryId = "1042",
            revision = 7,
            type = "snapshot",
            snapshot = snapshot(revision = 7, deviceState = "idle"),
        )

        val result = CaptureProjection.applyStreamEvent(current, event)

        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertFalse(result.requiresCaptureReconciliation)
        assertEquals("recording", result.state.snapshot?.deviceState)
        assertEquals("1042", result.state.lastEventId)
    }

    @Test
    fun `clears receipt and pending command on new authority epoch`() {
        val current = CaptureProjectionState(
            snapshot = snapshot(revision = 7, deviceState = "recording", hasActiveRecording = true),
            safeSwapReceipt = safeSwapReceipt(),
            pendingCommand = CapturePendingCommand(CaptureCommandKind.STOP, "stop-1"),
        )
        val event = event(
            deliveryId = "1042",
            epoch = EPOCH_B,
            revision = 1,
            type = "snapshot",
            snapshot = snapshot(epoch = EPOCH_B, revision = 1, deviceState = "idle"),
        )

        val result = CaptureProjection.applyStreamEvent(current, event)

        assertFalse(result.accepted)
        assertTrue(result.requiresCaptureReconciliation)
        assertTrue(result.requiresSafeSwapReconciliation)
        assertTrue(result.clearedEpochBoundState)
        assertNull(result.state.snapshot)
        assertNull(result.state.safeSwapReceipt)
        assertNull(result.state.pendingCommand)
        assertEquals(EPOCH_B, result.state.lastAuthorityEpoch)
        assertEquals(1L, result.state.lastSourceRevision)
    }

    @Test
    fun `state event requires HTTP reconciliation because it is not a full snapshot`() {
        val current = CaptureProjectionState(snapshot = snapshot(revision = 7, deviceState = "idle"))
        val event = event(
            deliveryId = "1042",
            revision = 8,
            type = "state",
            sessionId = SESSION_ID,
        )

        val result = CaptureProjection.applyStreamEvent(current, event)

        assertTrue(result.accepted)
        assertTrue(result.requiresCaptureReconciliation)
        assertEquals("idle", result.state.snapshot?.deviceState)
        assertEquals(8L, result.state.lastSourceRevision)
    }

    @Test
    fun `safe swap event stores typed receipt without changing capture snapshot`() {
        val current = CaptureProjectionState(snapshot = snapshot(revision = 7, deviceState = "idle"))
        val receipt = safeSwapReceipt()
        val event = event(
            deliveryId = "1042",
            revision = 8,
            type = "safe_swap",
            sessionId = SESSION_ID,
            safeSwapReceipt = receipt,
        )

        val result = CaptureProjection.applyStreamEvent(current, event)

        assertTrue(result.accepted)
        assertFalse(result.requiresCaptureReconciliation)
        assertFalse(result.requiresSafeSwapReconciliation)
        assertSame(receipt, result.state.safeSwapReceipt)
        assertEquals("idle", result.state.snapshot?.deviceState)
    }

    @Test
    fun `rejects safe swap receipt from older authority epoch`() {
        val current = CaptureProjectionState(
            snapshot = snapshot(epoch = EPOCH_A, revision = 7, deviceState = "idle"),
            safeSwapReceipt = safeSwapReceipt().copy(authorityEpoch = EPOCH_A, sourceRevision = 7),
        )
        val staleReceipt = safeSwapReceipt().copy(authorityEpoch = EPOCH_B, sourceRevision = 8)

        val result = CaptureProjection.applySafeSwapReceipt(current, staleReceipt)

        assertNull(result.safeSwapReceipt)
    }

    @Test
    fun `start command stays pending until authoritative snapshot has active recording`() {
        val submitting = CaptureProjection.markCommandSubmitting(
            CaptureProjectionState(snapshot = snapshot(revision = 7, deviceState = "idle")),
            CaptureCommandKind.START,
            "start-1",
        )

        val stillIdle = CaptureProjection.applyHttpSnapshot(
            submitting,
            snapshot(revision = 8, deviceState = "idle"),
        )
        val recording = CaptureProjection.applyHttpSnapshot(
            stillIdle.state,
            snapshot(revision = 9, deviceState = "recording", hasActiveRecording = true),
        )

        assertEquals(CaptureCommandKind.START, stillIdle.state.pendingCommand?.kind)
        assertNull(recording.state.pendingCommand)
    }

    @Test
    fun `stop command settles when authoritative snapshot leaves recording`() {
        val submitting = CaptureProjection.markCommandSubmitting(
            CaptureProjectionState(snapshot = snapshot(revision = 7, deviceState = "recording", hasActiveRecording = true)),
            CaptureCommandKind.STOP,
            "stop-1",
        )

        val finalizing = CaptureProjection.applyHttpSnapshot(
            submitting,
            snapshot(revision = 8, deviceState = "finalizing", hasActiveRecording = true),
        )

        assertNull(finalizing.state.pendingCommand)
    }

    private fun event(
        deliveryId: String,
        epoch: String = EPOCH_A,
        revision: Long,
        type: String,
        sessionId: String? = null,
        snapshot: CaptureStatusSnapshot? = null,
        safeSwapReceipt: SafeSwapReceiptSummary? = null,
    ): CaptureStreamEvent {
        return CaptureStreamEvent(
            sseDeliveryId = deliveryId,
            authorityEpoch = epoch,
            sourceRevision = revision,
            type = type,
            occurredAt = "2026-08-28T04:00:01Z",
            sessionId = sessionId,
            revisionRelation = CaptureRevisionRelation.Initial,
            snapshot = snapshot,
            safeSwapReceipt = safeSwapReceipt,
        )
    }

    private fun snapshot(
        epoch: String = EPOCH_A,
        revision: Long,
        deviceState: String,
        hasActiveRecording: Boolean = false,
    ): CaptureStatusSnapshot {
        return CaptureStatusSnapshot(
            authorityEpoch = epoch,
            sourceRevision = revision,
            deviceState = deviceState,
            hasActiveRecording = hasActiveRecording,
            runtime = DeviceRuntime(
                observedAt = "2026-08-28T04:00:00Z",
                connectionMethod = "wifi_ap",
                temperatureCelsius = 48.2,
            ),
        )
    }

    private fun safeSwapReceipt(): SafeSwapReceiptSummary {
        return SafeSwapReceiptSummary(
            sessionId = SESSION_ID,
            volumeId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
            generationId = EPOCH_A,
            manifestId = "01991b70-7c88-7456-9234-123456789abc",
            manifestSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            sealedAt = "2026-08-28T04:00:10Z",
            releasedAt = "2026-08-28T04:00:11Z",
            releaseState = "device-released",
        )
    }

    private companion object {
        const val EPOCH_A = "e989c6e5-14cc-4faa-9715-5abdb6b0355d"
        const val EPOCH_B = "2f3e4d5c-1111-4222-8333-123456789abc"
        const val SESSION_ID = "01991b70-7c88-7123-9234-123456789abc"
    }
}
