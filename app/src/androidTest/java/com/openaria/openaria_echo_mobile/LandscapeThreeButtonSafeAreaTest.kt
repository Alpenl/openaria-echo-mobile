package com.openaria.openaria_echo_mobile

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class LandscapeThreeButtonSafeAreaTest {
    @get:Rule(order = 0)
    val prepareTargetApp = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.targetContext
                    .getSharedPreferences("openaria_echo_locale", Context.MODE_PRIVATE)
                    .edit()
                    .putString("locale_tag", "zh-CN")
                    .commit()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    instrumentation.uiAutomation.grantRuntimePermission(
                        instrumentation.targetContext.packageName,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                }
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun rootContentStaysInsideLandscapeThreeButtonSystemSafeArea() {
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = WINDOW_FOCUS_TIMEOUT_MILLIS) {
            compose.activity.window.decorView.hasWindowFocus()
        }

        val activity = compose.activity
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
        val decorView = activity.window.decorView
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(decorView)) {
            "Root WindowInsets must be available for the landscape Activity."
        }.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        assertTrue("Three-button landscape must expose a right system inset: $insets", insets.right > 0)
        val safeBounds = Rect(
            left = insets.left.toFloat(),
            top = insets.top.toFloat(),
            right = (decorView.width - insets.right).toFloat(),
            bottom = (decorView.height - insets.bottom).toFloat(),
        )

        val topStatus = compose
            .onNodeWithContentDescription("Open Aria Echo, 未连接机身", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertRectInside("top status", topStatus.boundsInRoot, safeBounds)
        val safeNodes = mutableListOf("top status" to topStatus.boundsInRoot)

        val tabs = compose
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
                useUnmergedTree = true,
            )
            .fetchSemanticsNodes()
        assertEquals(4, tabs.size)
        tabs.forEachIndexed { index, tab ->
            assertRectInside("navigation tab $index", tab.boundsInRoot, safeBounds)
            safeNodes += "navigation tab $index" to tab.boundsInRoot
        }

        PREVIEW_CONTROL_RESOURCES.forEach { resourceId ->
            val label = activity.localizedString(resourceId)
            val controlInteraction = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            val control = controlInteraction.fetchSemanticsNode()
            assertRectInside("preview control $label", control.boundsInRoot, safeBounds)
            controlInteraction.assertIsDisplayed()
            safeNodes += "preview control $label" to control.boundsInRoot
        }
        safeNodes.indices.forEach { leftIndex ->
            ((leftIndex + 1) until safeNodes.size).forEach { rightIndex ->
                val left = safeNodes[leftIndex]
                val right = safeNodes[rightIndex]
                assertNoPositiveOverlap(left.first, left.second, right.first, right.second)
            }
        }
    }

    private fun Context.localizedString(@StringRes resourceId: Int): String {
        val localizedConfiguration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag("zh-CN"))
        }
        return createConfigurationContext(localizedConfiguration).getString(resourceId)
    }

    private fun assertRectInside(name: String, actual: Rect, safe: Rect) {
        assertTrue("$name has an empty or invalid bound: $actual", actual.width > 0f && actual.height > 0f)
        assertTrue("$name crosses the safe left edge: actual=$actual safe=$safe", actual.left >= safe.left - TOLERANCE_PX)
        assertTrue("$name crosses the safe top edge: actual=$actual safe=$safe", actual.top >= safe.top - TOLERANCE_PX)
        assertTrue("$name crosses the safe right edge: actual=$actual safe=$safe", actual.right <= safe.right + TOLERANCE_PX)
        assertTrue("$name crosses the safe bottom edge: actual=$actual safe=$safe", actual.bottom <= safe.bottom + TOLERANCE_PX)
    }

    private fun assertNoPositiveOverlap(leftName: String, left: Rect, rightName: String, right: Rect) {
        val overlapWidth = max(0f, min(left.right, right.right) - max(left.left, right.left))
        val overlapHeight = max(0f, min(left.bottom, right.bottom) - max(left.top, right.top))
        assertTrue(
            "$leftName overlaps $rightName by ${overlapWidth * overlapHeight}px^2: left=$left right=$right",
            overlapWidth * overlapHeight <= TOLERANCE_PX,
        )
    }

    private companion object {
        const val WINDOW_FOCUS_TIMEOUT_MILLIS = 10_000L
        const val TOLERANCE_PX = 2f
        val PREVIEW_CONTROL_RESOURCES = listOf(
            R.string.view_both,
            R.string.view_left,
            R.string.view_right,
            R.string.grid,
            R.string.focus_peaking,
            R.string.imu_overlay,
            R.string.start_recording,
            R.string.stop_recording,
        )
    }
}
