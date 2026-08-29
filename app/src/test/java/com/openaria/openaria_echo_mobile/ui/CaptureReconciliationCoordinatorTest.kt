package com.openaria.openaria_echo_mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureReconciliationCoordinatorTest {
    @Test
    fun `forced refresh queued behind blocked request supersedes old response and starts fresh request`() {
        val coordinator = coordinator(connectionGeneration = 7L)
        val blocked = assertNotNull(coordinator.begin(nowMs = 0L, force = false))

        assertFalse(blocked.forced)
        assertNull(coordinator.begin(nowMs = 1_000L, force = true))
        assertNull(coordinator.begin(nowMs = 1_001L, force = true))
        assertEquals(
            CaptureReconciliationResponseDisposition.SUPERSEDED,
            coordinator.complete(blocked),
        )

        val fresh = assertNotNull(coordinator.begin(nowMs = 1_002L, force = false))
        assertTrue(fresh.forced)
        assertEquals(7L, fresh.connectionGeneration)
        assertEquals(
            CaptureReconciliationResponseDisposition.CURRENT,
            coordinator.complete(fresh),
        )
        assertNull(coordinator.begin(nowMs = 1_003L, force = false))
    }

    @Test
    fun `cancellation retains queued forced refresh for the next coordinator tick`() {
        val coordinator = coordinator(connectionGeneration = 4L)
        val blocked = assertNotNull(coordinator.begin(nowMs = 0L, force = false))

        assertNull(coordinator.begin(nowMs = 500L, force = true))
        coordinator.cancel(blocked)

        val fresh = assertNotNull(coordinator.begin(nowMs = 501L, force = false))
        assertTrue(fresh.forced)
    }

    @Test
    fun `new connection generation has no pending refresh from old generation`() {
        val oldCoordinator = coordinator(connectionGeneration = 2L)
        val blocked = assertNotNull(oldCoordinator.begin(nowMs = 0L, force = false))
        assertNull(oldCoordinator.begin(nowMs = 100L, force = true))

        val newCoordinator = coordinator(connectionGeneration = 3L)
        val current = assertNotNull(newCoordinator.begin(nowMs = 100L, force = false))

        assertFalse(current.forced)
        assertEquals(3L, current.connectionGeneration)
        assertEquals(
            CaptureReconciliationResponseDisposition.SUPERSEDED,
            oldCoordinator.complete(blocked),
        )
    }

    private fun coordinator(connectionGeneration: Long): CaptureReconciliationCoordinator {
        return CaptureReconciliationCoordinator(
            connectionGeneration = connectionGeneration,
            gate = ReconciliationGate(ConnectionRequestPolicy.HEALTHY_RECONCILIATION_INTERVAL_MS),
        )
    }
}
