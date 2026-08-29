package com.openaria.openaria_echo_mobile.ui

import com.openaria.openaria_echo_mobile.body.api.SessionDiscoveryDiagnostic
import com.openaria.openaria_echo_mobile.body.api.SessionListContract
import com.openaria.openaria_echo_mobile.body.api.SessionListPage
import com.openaria.openaria_echo_mobile.body.api.SessionListRequestIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionDiagnosticPresentationTest {
    @Test
    fun `quarantine diagnostics remain read-only presentations outside session items`() {
        val page = SessionListPage(
            contract = SessionListContract.V3,
            catalogRevision =
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            items = emptyList(),
            diagnostics = listOf(
                SessionDiscoveryDiagnostic(
                    quarantineId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                    code = "manifest_invalid",
                    observedAt = "2026-08-28T04:00:00Z",
                    message = "closed schema violation",
                ),
            ),
            nextCursor = null,
            requestIdentity = SessionListRequestIdentity(limit = 1, cursor = null, takeId = null),
        )

        val presentation = page.readOnlyDiagnosticPresentations().single()

        assertEquals(emptyList(), page.items)
        assertEquals("manifest_invalid", presentation.code)
        assertEquals("closed schema violation", presentation.message)
        assertEquals("2026-08-28T04:00:00Z", presentation.observedAt)
        assertEquals("56005c52-31f1-4dac-91cd-d8eafd737d1c", presentation.quarantineId)
    }
}
