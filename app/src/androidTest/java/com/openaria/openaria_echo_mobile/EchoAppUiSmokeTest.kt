package com.openaria.openaria_echo_mobile

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.openaria.openaria_echo_mobile.ui.EchoApp
import com.openaria.openaria_echo_mobile.ui.theme.EchoTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EchoAppUiSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chineseShellShowsPrimaryNavigationAndNoLegacyEnglishControls() {
        renderEchoApp("zh-CN")

        compose.onNodeWithText("取景").assertIsDisplayed()
        compose.onNodeWithText("会话").assertIsDisplayed()
        compose.onNodeWithText("机身").assertIsDisplayed()
        compose.onNodeWithText("网络").assertIsDisplayed()
        compose.onNodeWithText("连接机身").performScrollTo().assertIsDisplayed()

        listOf("Mount", "Retry", "Probe", "Edit", "Join", "Copy URL").forEach { legacyText ->
            compose.onAllNodesWithText(legacyText).assertCountEquals(0)
        }
    }

    @Test
    fun englishShellUsesEnglishNavigationResources() {
        renderEchoApp("en")

        compose.onNodeWithText("Viewfinder").assertIsDisplayed()
        compose.onNodeWithText("Sessions").assertIsDisplayed()
        compose.onNodeWithText("Body").assertIsDisplayed()
        compose.onNodeWithText("Network").assertIsDisplayed()
        compose.onNodeWithText("CONNECT BODY").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun responsiveShellKeepsPrimaryControlsVisibleAcrossSmallWideAndLargeFontCases() {
        val renderCases = listOf(
            RenderCase(localeTag = "zh-CN", widthDp = 360, heightDp = 740, fontScale = 1.0f, connectionTitle = "连接机身"),
            RenderCase(localeTag = "zh-CN", widthDp = 393, heightDp = 780, fontScale = 1.5f, connectionTitle = "连接机身"),
            RenderCase(localeTag = "zh-CN", widthDp = 411, heightDp = 840, fontScale = 2.0f, connectionTitle = "连接机身"),
            RenderCase(localeTag = "en", widthDp = 600, heightDp = 360, fontScale = 1.3f, connectionTitle = "CONNECT BODY"),
        )
        val renderCaseState = mutableStateOf(renderCases.first())
        renderEchoApp(renderCaseState)

        renderCases.forEach { renderCase ->
            compose.runOnIdle {
                renderCaseState.value = renderCase
            }
            compose.waitForIdle()

            compose.onNodeWithText(renderCase.connectionTitle).performScrollTo().assertIsDisplayed()
            if (renderCase.localeTag.startsWith("zh")) {
                listOf("取景", "会话", "机身", "网络").forEach { label ->
                    compose.onNodeWithText(label).assertIsDisplayed()
                }
            } else {
                listOf("Viewfinder", "Sessions", "Body", "Network").forEach { label ->
                    compose.onNodeWithText(label).assertIsDisplayed()
                }
            }
            listOf("Mount", "Retry", "Probe", "Edit", "Join", "Copy URL").forEach { legacyText ->
                compose.onAllNodesWithText(legacyText).assertCountEquals(0)
            }
        }
    }

    @Test
    fun previewStatusBodyAndControlsKeepIndependentUnclippedLayoutOnNarrowPortrait() {
        renderEchoApp(localeTag = "zh-CN", widthDp = 360, heightDp = 740)

        assertPreviewStatusAndControlsAreIndependent()
    }

    @Test
    fun previewStatusBodyAndPrimaryCommandsStayVisibleOnCompactLandscape() {
        renderEchoApp(localeTag = "zh-CN", widthDp = 600, heightDp = 360)

        assertPreviewStatusAndControlsAreIndependent()
    }

    @Test
    fun widePortraitKeepsThePortraitPreviewToolRail() {
        renderEchoApp(localeTag = "zh-CN", widthDp = 700, heightDp = 900)

        val toolBounds = listOf("网格", "对焦峰值", "IMU 叠加").map { label ->
            compose
                .onNodeWithContentDescription(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        }
        assertTrue(
            "Wide portrait must keep preview tools in one vertical rail: $toolBounds",
            toolBounds.all { abs(it.left - toolBounds.first().left) <= GEOMETRY_TOLERANCE_PX },
        )
        assertTrue(
            "Wide portrait preview tools must keep their vertical order: $toolBounds",
            toolBounds.zipWithNext().all { (top, bottom) -> top.bottom < bottom.top },
        )
    }

    private fun renderEchoApp(renderCaseState: MutableState<RenderCase>) {
        compose.setContent {
            val renderCase = renderCaseState.value
            RenderEchoApp(
                localeTag = renderCase.localeTag,
                widthDp = renderCase.widthDp,
                heightDp = renderCase.heightDp,
                fontScale = renderCase.fontScale,
            )
        }
    }

    private fun renderEchoApp(
        localeTag: String,
        widthDp: Int? = null,
        heightDp: Int? = null,
        fontScale: Float = 1.0f,
    ) {
        compose.setContent {
            RenderEchoApp(
                localeTag = localeTag,
                widthDp = widthDp,
                heightDp = heightDp,
                fontScale = fontScale,
            )
        }
    }

    @Composable
    private fun RenderEchoApp(
        localeTag: String,
        widthDp: Int?,
        heightDp: Int?,
        fontScale: Float,
    ) {
        val localizedContext = InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .localized(localeTag)
        val configuration = Configuration(localizedContext.resources.configuration)
        if (widthDp != null && heightDp != null) {
            configuration.screenWidthDp = widthDp
            configuration.screenHeightDp = heightDp
            configuration.orientation = if (widthDp > heightDp) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
        }

        CompositionLocalProvider(
            LocalContext provides localizedContext,
            LocalConfiguration provides configuration,
            LocalDensity provides Density(density = 1.0f, fontScale = fontScale),
        ) {
            ResponsiveHost(widthDp = widthDp, heightDp = heightDp) {
                EchoTheme {
                    EchoApp(
                        localeTag = localeTag,
                        updateState = AppUpdateManager.State.idle(5, "0.1.2"),
                        onLocaleChange = {},
                        onCheckUpdate = {},
                        onInstallUpdate = {},
                    )
                }
            }
        }
    }

    @Composable
    private fun ResponsiveHost(
        widthDp: Int?,
        heightDp: Int?,
        content: @Composable () -> Unit,
    ) {
        if (widthDp != null && heightDp != null) {
            Box(modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                content()
            }
        } else {
            content()
        }
    }

    private fun Context.localized(localeTag: String): Context {
        val locale = Locale.forLanguageTag(localeTag)
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return createConfigurationContext(configuration)
    }

    private fun assertPreviewStatusAndControlsAreIndependent() {
        val body = compose
            .onNodeWithText(PREVIEW_STATUS_BODY, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertTextHasNoVisualOverflow("preview status body", body)

        val controls = PREVIEW_CONTROL_LABELS.associateWith { label ->
            compose
                .onNodeWithContentDescription(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        }
        controls.forEach { (label, bounds) ->
            assertNoPositiveOverlap("preview status body", body.boundsInRoot, label, bounds)
        }
        val entries = controls.entries.toList()
        entries.indices.forEach { leftIndex ->
            ((leftIndex + 1) until entries.size).forEach { rightIndex ->
                val left = entries[leftIndex]
                val right = entries[rightIndex]
                assertNoPositiveOverlap(left.key, left.value, right.key, right.value)
            }
        }

        compose.onAllNodesWithText(PREVIEW_CONTRACT_NOTE, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun assertTextHasNoVisualOverflow(name: String, node: SemanticsNode) {
        val layoutResults = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("$name must expose its real text layout result.", action?.invoke(layoutResults) == true)
        assertTrue("$name must produce exactly one text layout result.", layoutResults.size == 1)
        assertFalse("$name is clipped or ellipsized.", layoutResults.single().hasVisualOverflow)
    }

    private fun assertNoPositiveOverlap(leftName: String, left: Rect, rightName: String, right: Rect) {
        val overlapWidth = max(0f, min(left.right, right.right) - max(left.left, right.left))
        val overlapHeight = max(0f, min(left.bottom, right.bottom) - max(left.top, right.top))
        assertTrue(
            "$leftName overlaps $rightName by ${overlapWidth * overlapHeight}px^2: left=$left right=$right",
            overlapWidth * overlapHeight <= GEOMETRY_TOLERANCE_PX,
        )
    }

    private data class RenderCase(
        val localeTag: String,
        val widthDp: Int,
        val heightDp: Int,
        val fontScale: Float,
        val connectionTitle: String,
    )

    private companion object {
        const val PREVIEW_STATUS_BODY = "未连接机身时不显示伪造视频、ready 状态或录制指标。"
        const val PREVIEW_CONTRACT_NOTE = "预览将使用 Device API v4 JPEG 最新帧；断流、相机未接入和鉴权失败会分开显示。"
        const val GEOMETRY_TOLERANCE_PX = 1f
        val PREVIEW_CONTROL_LABELS = listOf(
            "双目",
            "左眼",
            "右眼",
            "网格",
            "对焦峰值",
            "IMU 叠加",
            "开始录制",
            "停止",
        )
    }
}
