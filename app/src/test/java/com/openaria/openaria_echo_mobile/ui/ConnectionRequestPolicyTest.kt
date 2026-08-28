package com.openaria.openaria_echo_mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionRequestPolicyTest {
    @Test
    fun `healthy reconciliation admits at most two status reads in sixty seconds`() {
        val gate = ReconciliationGate(ConnectionRequestPolicy.HEALTHY_RECONCILIATION_INTERVAL_MS)
        val admitted = (0L until 60_000L step 1_000L).filter { nowMs ->
            gate.tryAcquire(nowMs)
        }

        assertEquals(listOf(0L, 30_000L), admitted)
    }

    @Test
    fun `fallback delay doubles to a bounded maximum`() {
        val delays = buildList {
            var delayMs = ConnectionRequestPolicy.FALLBACK_INITIAL_DELAY_MS
            repeat(6) {
                add(delayMs)
                delayMs = ConnectionRequestPolicy.nextFallbackDelay(delayMs)
            }
        }

        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `late response is rejected after generation or revision changes`() {
        val baseline = AuthorityRevision("epoch-a", 7L)

        assertTrue(
            ConnectionRequestPolicy.canApplyResponse(
                requestGeneration = 3L,
                currentGeneration = 3L,
                requestBaseline = baseline,
                currentRevision = baseline,
            ),
        )
        assertFalse(
            ConnectionRequestPolicy.canApplyResponse(
                requestGeneration = 3L,
                currentGeneration = 4L,
                requestBaseline = baseline,
                currentRevision = baseline,
            ),
        )
        assertFalse(
            ConnectionRequestPolicy.canApplyResponse(
                requestGeneration = 3L,
                currentGeneration = 3L,
                requestBaseline = baseline,
                currentRevision = AuthorityRevision("epoch-a", 8L),
            ),
        )
    }

    @Test
    fun `forced fallback remains single gate event and resets healthy interval`() {
        val gate = ReconciliationGate(ConnectionRequestPolicy.HEALTHY_RECONCILIATION_INTERVAL_MS)

        assertTrue(gate.tryAcquire(0L))
        assertTrue(gate.tryAcquire(2_000L, force = true))
        assertFalse(gate.tryAcquire(30_000L))
        assertTrue(gate.tryAcquire(32_000L))
    }
}
