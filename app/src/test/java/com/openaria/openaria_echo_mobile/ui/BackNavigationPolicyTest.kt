package com.openaria.openaria_echo_mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BackNavigationPolicyTest {
    private val base = BackNavigationState(
        selectedTabIsViewfinder = true,
        recording = false,
        connected = true,
    )

    @Test
    fun `back closes layers in strict priority order`() {
        assertEquals(
            BackNavigationAction.DISMISS_CONFIRMATION,
            BackNavigationPolicy.decide(
                base.copy(
                    confirmationVisible = true,
                    sessionDetailVisible = true,
                    sessionOutcomeVisible = true,
                    temporaryPanelVisible = true,
                    selectedTabIsViewfinder = false,
                    recording = true,
                ),
            ),
        )
        assertEquals(
            BackNavigationAction.CLOSE_SESSION_DETAIL,
            BackNavigationPolicy.decide(
                base.copy(sessionDetailVisible = true, sessionOutcomeVisible = true, temporaryPanelVisible = true),
            ),
        )
        assertEquals(
            BackNavigationAction.CLOSE_SESSION_OUTCOME,
            BackNavigationPolicy.decide(base.copy(sessionOutcomeVisible = true, temporaryPanelVisible = true)),
        )
        assertEquals(
            BackNavigationAction.CLOSE_TEMPORARY_PANEL,
            BackNavigationPolicy.decide(base.copy(temporaryPanelVisible = true, selectedTabIsViewfinder = false)),
        )
        assertEquals(
            BackNavigationAction.RETURN_TO_VIEWFINDER,
            BackNavigationPolicy.decide(base.copy(selectedTabIsViewfinder = false, recording = true)),
        )
    }

    @Test
    fun `recording asks before background and never maps to a capture command`() {
        assertEquals(
            BackNavigationAction.REQUEST_RECORDING_BACKGROUND_CONFIRMATION,
            BackNavigationPolicy.decide(base.copy(recording = true)),
        )
        assertEquals(
            BackNavigationAction.MOVE_TASK_TO_BACKGROUND,
            BackNavigationPolicy.decide(base),
        )
    }

    @Test
    fun `unconnected viewfinder delegates to the system default exit`() {
        assertEquals(
            BackNavigationAction.DEFAULT_SYSTEM_EXIT,
            BackNavigationPolicy.decide(base.copy(connected = false)),
        )
    }
}
