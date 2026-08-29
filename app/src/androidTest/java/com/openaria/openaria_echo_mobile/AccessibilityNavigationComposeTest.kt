package com.openaria.openaria_echo_mobile

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import com.openaria.openaria_echo_mobile.ui.BackNavigationAction
import com.openaria.openaria_echo_mobile.ui.BackNavigationHandler
import com.openaria.openaria_echo_mobile.ui.BackNavigationState
import com.openaria.openaria_echo_mobile.ui.ConfirmationBlock
import com.openaria.openaria_echo_mobile.ui.FrameToolToggle
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
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
        lateinit var backDispatcher: OnBackPressedDispatcher
        compose.setContent {
            backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current)
                .onBackPressedDispatcher
            BackNavigationHandler(
                state = BackNavigationState(
                    selectedTabIsViewfinder = true,
                    recording = true,
                    connected = true,
                ),
                onAction = { observed = it },
            )
        }

        compose.runOnIdle { backDispatcher.onBackPressed() }

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

        val pane = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Confirm disconnect"),
        )
        val heading = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        pane.assertIsDisplayed()
        heading.assertIsDisplayed()
        compose.waitForIdle()
        heading.assertIsFocused()

        onView(DialogWindowProviderMatcher).perform(DispatchDialogBack)
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

    private object DispatchDialogBack : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()

        override fun getDescription(): String = "dispatch back through the owning ComponentDialog"

        override fun perform(uiController: UiController, view: View) {
            val window = (view as DialogWindowProvider).window
            val owner = requireNotNull(window.callback as? OnBackPressedDispatcherOwner) {
                "Compose Dialog must expose its ComponentDialog back dispatcher"
            }
            owner.onBackPressedDispatcher.onBackPressed()
            uiController.loopMainThreadUntilIdle()
        }
    }

    private object DialogWindowProviderMatcher : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("a Compose DialogWindowProvider")
        }

        override fun matchesSafely(view: View): Boolean = view is DialogWindowProvider
    }
}
