package com.openaria.openaria_echo_mobile

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.openaria.openaria_echo_mobile.ui.BackNavigationAction
import com.openaria.openaria_echo_mobile.ui.BackNavigationHandler
import com.openaria.openaria_echo_mobile.ui.BackNavigationState
import com.openaria.openaria_echo_mobile.ui.ConfirmationBlock
import com.openaria.openaria_echo_mobile.ui.FrameToolToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccessibilityNavigationComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun systemBackDispatchesTheRecordingConfirmationAction() {
        var observed: BackNavigationAction? = null
        compose.setContent {
            BackNavigationHandler(
                state = BackNavigationState(
                    selectedTabIsViewfinder = true,
                    recording = true,
                    connected = true,
                ),
                onAction = { observed = it },
            )
        }

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()

        assertEquals(BackNavigationAction.REQUEST_RECORDING_BACKGROUND_CONFIRMATION, observed)
    }

    @Test
    fun confirmationIsADismissibleNamedPaneWithHeadingFocusTarget() {
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) {
                ConfirmationBlock(
                    title = "Confirm disconnect",
                    body = "The device keeps recording.",
                    confirmLabel = "Disconnect",
                    onCancel = { visible = false },
                    onConfirm = {},
                )
            }
        }

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Confirm disconnect"),
        ).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()

        assertFalse(visible)
    }

    @Test
    fun frameToolIsAFortyEightDpSwitchWithToggleState() {
        var selected by mutableStateOf(false)
        compose.setContent {
            FrameToolToggle(
                label = "Focus peaking",
                selected = selected,
                onClick = { selected = !selected },
            )
        }

        val tool = compose.onNodeWithContentDescription("Focus peaking")
        tool.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
        )
        tool.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off),
        )
        tool.assertIsOff()
        tool.assertWidthIsAtLeast(48.dp)
        tool.assertHeightIsAtLeast(48.dp)

        tool.performClick()
        tool.assertIsOn()
    }

    @Test
    fun recordingConfirmationOnlyInvokesBackgroundHandoff() {
        var dismissed = false
        var backgroundHandoffs = 0
        var stopCalls = 0
        var disconnectCalls = 0
        compose.setContent {
            ConfirmationBlock(
                title = "Recording continues",
                body = "No stop or disconnect command is sent.",
                confirmLabel = "Keep recording",
                onCancel = { dismissed = true },
                onConfirm = { backgroundHandoffs += 1 },
            )
        }

        compose.onNodeWithContentDescription("Keep recording").performClick()

        assertEquals(1, backgroundHandoffs)
        assertEquals(0, stopCalls)
        assertEquals(0, disconnectCalls)
        assertFalse(dismissed)
        assertTrue(backgroundHandoffs > stopCalls + disconnectCalls)
    }
}
