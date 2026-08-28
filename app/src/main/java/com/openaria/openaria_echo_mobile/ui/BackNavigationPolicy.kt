package com.openaria.openaria_echo_mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

internal data class BackNavigationState(
    val confirmationVisible: Boolean = false,
    val sessionDetailVisible: Boolean = false,
    val sessionOutcomeVisible: Boolean = false,
    val temporaryPanelVisible: Boolean = false,
    val selectedTabIsViewfinder: Boolean,
    val recording: Boolean,
    val connected: Boolean,
)

internal enum class BackNavigationAction {
    DISMISS_CONFIRMATION,
    CLOSE_SESSION_DETAIL,
    CLOSE_SESSION_OUTCOME,
    CLOSE_TEMPORARY_PANEL,
    RETURN_TO_VIEWFINDER,
    REQUEST_RECORDING_BACKGROUND_CONFIRMATION,
    MOVE_TASK_TO_BACKGROUND,
    DEFAULT_SYSTEM_EXIT,
}

internal object BackNavigationPolicy {
    fun decide(state: BackNavigationState): BackNavigationAction {
        return when {
            state.confirmationVisible -> BackNavigationAction.DISMISS_CONFIRMATION
            state.sessionDetailVisible -> BackNavigationAction.CLOSE_SESSION_DETAIL
            state.sessionOutcomeVisible -> BackNavigationAction.CLOSE_SESSION_OUTCOME
            state.temporaryPanelVisible -> BackNavigationAction.CLOSE_TEMPORARY_PANEL
            !state.selectedTabIsViewfinder -> BackNavigationAction.RETURN_TO_VIEWFINDER
            state.recording -> BackNavigationAction.REQUEST_RECORDING_BACKGROUND_CONFIRMATION
            state.connected -> BackNavigationAction.MOVE_TASK_TO_BACKGROUND
            else -> BackNavigationAction.DEFAULT_SYSTEM_EXIT
        }
    }
}

@Composable
internal fun BackNavigationHandler(
    state: BackNavigationState,
    onAction: (BackNavigationAction) -> Unit,
) {
    val action = BackNavigationPolicy.decide(state)
    BackHandler(enabled = action != BackNavigationAction.DEFAULT_SYSTEM_EXIT) {
        onAction(action)
    }
}
