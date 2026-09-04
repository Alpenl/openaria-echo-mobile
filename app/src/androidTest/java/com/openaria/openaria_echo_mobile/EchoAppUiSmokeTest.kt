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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.openaria.openaria_echo_mobile.body.api.CameraConnectionStatus
import com.openaria.openaria_echo_mobile.body.api.CaptureStatusSnapshot
import com.openaria.openaria_echo_mobile.body.api.DeviceConnection
import com.openaria.openaria_echo_mobile.body.api.DeviceDescriptor
import com.openaria.openaria_echo_mobile.body.api.DeviceRuntime
import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import com.openaria.openaria_echo_mobile.ui.EchoApp
import com.openaria.openaria_echo_mobile.ui.EventStreamHealth
import com.openaria.openaria_echo_mobile.ui.PreviewMessage
import com.openaria.openaria_echo_mobile.ui.PreviewMode
import com.openaria.openaria_echo_mobile.ui.ViewfinderScreen
import com.openaria.openaria_echo_mobile.ui.theme.EchoTheme
import java.util.Locale
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
    fun chineseShellShowsV3CameraLayoutAndNoLegacyEnglishControls() {
        renderEchoApp("zh-CN")

        compose.onNodeWithContentDescription("Open Aria Echo, 未连接机身").assertIsDisplayed()
        compose.onNodeWithContentDescription("设置").assertIsDisplayed()
        compose.onNodeWithText("连接前不显示预览、状态和会话。").assertIsDisplayed()
        compose.onAllNodesWithText("附近机身").assertCountEquals(2)
        compose.onNodeWithText("连接机身").performScrollTo().assertIsDisplayed()

        listOf("Mount", "Retry", "Probe", "Edit", "Join", "Copy URL").forEach { legacyText ->
            compose.onAllNodesWithText(legacyText).assertCountEquals(0)
        }
    }

    @Test
    fun englishShellUsesEnglishV3Resources() {
        renderEchoApp("en")

        compose.onNodeWithContentDescription("Open Aria Echo, No body connected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Preview, status, and sessions stay hidden until a body is connected.").assertIsDisplayed()
        compose.onAllNodesWithText("Nearby bodies").assertCountEquals(2)
        compose.onNodeWithText("CONNECT BODY").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun responsiveShellKeepsV3PrimaryControlsVisibleAcrossSmallWideAndLargeFontCases() {
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
                compose.onNodeWithContentDescription("Open Aria Echo, 未连接机身").assertIsDisplayed()
                compose.onNodeWithContentDescription("设置").assertIsDisplayed()
                compose.onNodeWithText("连接前不显示预览、状态和会话。").assertIsDisplayed()
            } else {
                compose.onNodeWithContentDescription("Open Aria Echo, No body connected").assertIsDisplayed()
                compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
                compose.onNodeWithText("Preview, status, and sessions stay hidden until a body is connected.").assertIsDisplayed()
            }
            listOf("Mount", "Retry", "Probe", "Edit", "Join", "Copy URL").forEach { legacyText ->
                compose.onAllNodesWithText(legacyText).assertCountEquals(0)
            }
        }
    }

    @Test
    fun previewStatusBodyAndSettingsControlKeepIndependentUnclippedLayoutOnNarrowPortrait() {
        renderEchoApp(localeTag = "zh-CN", widthDp = 360, heightDp = 740)

        assertV3PreviewStatusAndControlsAreIndependent()
    }

    @Test
    fun previewStatusBodyAndSettingsControlStayVisibleOnCompactLandscape() {
        renderEchoApp(localeTag = "zh-CN", widthDp = 600, heightDp = 360)

        assertV3PreviewStatusAndControlsAreIndependent()
    }

    @Test
    fun settingsOverlayOpensFromTheV3TopBar() {
        renderEchoApp(localeTag = "zh-CN", widthDp = 700, heightDp = 900)

        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText("机身").assertIsDisplayed()
        compose.onNodeWithText("网络").assertIsDisplayed()
        compose.onNodeWithText("诊断与契约").assertIsDisplayed()
    }

    @Test
    fun connectedViewfinderKeepsCaptureActionsReachableOn360dpLandscapeAndLargeFont() {
        val cases = listOf(
            ConnectedRenderCase(widthDp = 360, heightDp = 740, fontScale = 1.0f),
            ConnectedRenderCase(widthDp = 600, heightDp = 360, fontScale = 1.0f),
            ConnectedRenderCase(widthDp = 393, heightDp = 780, fontScale = 1.5f),
        )
        val renderCase = mutableStateOf(cases.first())
        renderConnectedViewfinder(renderCase)

        cases.forEach { case ->
            compose.runOnIdle { renderCase.value = case }
            compose.waitForIdle()

            val root = Rect(0f, 0f, case.widthDp.toFloat(), case.heightDp.toFloat())
            listOf("开始录制", "停止").forEach { label ->
                val action = compose
                    .onNodeWithContentDescription(label, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp)
                    .assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode()
                assertRectInside("$label at $case", action.boundsInRoot, root)
            }
            compose
                .onNodeWithContentDescription("开始录制", useUnmergedTree = true)
                .assertIsEnabled()
        }
    }

    @Test
    fun connectedCaptureStreamShowsStartingThenReconnectWhileSnapshotCommandsStayEnabled() {
        val renderCase = mutableStateOf(
            ConnectedRenderCase(
                widthDp = 360,
                heightDp = 740,
                fontScale = 1.0f,
                streamHealth = EventStreamHealth.Starting,
            ),
        )
        renderConnectedViewfinder(renderCase)

        compose.onNodeWithText("连接中").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("开始录制", useUnmergedTree = true).assertIsEnabled()

        compose.runOnIdle {
            renderCase.value = renderCase.value.copy(streamHealth = EventStreamHealth.Degraded)
        }

        compose.onNodeWithText("重连中").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("开始录制", useUnmergedTree = true).assertIsEnabled()
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

    private fun renderConnectedViewfinder(renderCaseState: MutableState<ConnectedRenderCase>) {
        compose.setContent {
            val renderCase = renderCaseState.value
            val localizedContext = InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .localized("zh-CN")
            val configuration = Configuration(localizedContext.resources.configuration).apply {
                screenWidthDp = renderCase.widthDp
                screenHeightDp = renderCase.heightDp
                orientation = if (renderCase.widthDp > renderCase.heightDp) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                }
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = 1.0f, fontScale = renderCase.fontScale),
            ) {
                ResponsiveHost(renderCase.widthDp, renderCase.heightDp) {
                    EchoTheme {
                        ViewfinderScreen(
                            bodyConnection = connectedBody(),
                            captureStatus = idleCaptureStatus(),
                            captureStreamHealth = renderCase.streamHealth,
                            captureMessage = null,
                            captureCommandMessage = null,
                            captureCommandRunning = false,
                            previewFrame = null,
                            previewMessage = PreviewMessage.Waiting,
                            previewMode = PreviewMode.BOTH,
                            showGrid = true,
                            showFocusPeaking = false,
                            showImuOverlay = false,
                            onStartCapture = {},
                            onStopCapture = {},
                            onPreviewModeChange = {},
                            onShowGridChange = {},
                            onShowFocusPeakingChange = {},
                            onShowImuOverlayChange = {},
                            onConnected = {},
                        )
                    }
                }
            }
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

    private fun assertV3PreviewStatusAndControlsAreIndependent() {
        val body = compose
            .onNodeWithText(PREVIEW_STATUS_BODY, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertTextHasNoVisualOverflow("preview status body", body)

        val controls = V3_CONTROL_LABELS.associateWith { label ->
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

    private fun assertRectInside(name: String, child: Rect, parent: Rect) {
        assertTrue(
            "$name must remain inside the responsive host: child=$child parent=$parent",
            child.left >= parent.left && child.top >= parent.top &&
                child.right <= parent.right && child.bottom <= parent.bottom,
        )
    }

    private fun connectedBody(): DeviceConnection {
        val target = (EndpointPolicy.validate("http://127.0.0.1:8080") as EndpointPolicy.Decision.Allowed).target
        return DeviceConnection(
            target = target,
            descriptor = DeviceDescriptor(
                deviceId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                deviceLabel = "YLX-00ABCDEF",
                hardwareFingerprint = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                packageVersion = "0.1.8",
                commit = "77f24f3777777777777777777777777777777777",
                buildId = "ui-test",
                securityProfile = "customer",
                captureCapable = true,
                previewCapable = true,
                rangeDownloadCapable = true,
                networkMutationCapable = false,
                sessionListCapable = true,
                sessionDetailCapable = true,
                artifactDownloadCapable = true,
                captureStatusCapable = true,
                sessionDeletionCapable = false,
                volumeId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                totalBytes = 1_024L,
                availableBytes = 512L,
                writable = true,
                runtime = connectedRuntime(),
            ),
            bearerToken = null,
        )
    }

    private fun idleCaptureStatus(): CaptureStatusSnapshot {
        return CaptureStatusSnapshot(
            authorityEpoch = "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
            sourceRevision = 7L,
            deviceState = "idle",
            hasActiveRecording = false,
            runtime = connectedRuntime(),
        )
    }

    private fun connectedRuntime(): DeviceRuntime {
        return DeviceRuntime(
            observedAt = "2026-08-31T10:00:00Z",
            connectionMethod = "wifi_client",
            temperatureCelsius = 45.0,
            camera = CameraConnectionStatus("connected"),
        )
    }

    private data class RenderCase(
        val localeTag: String,
        val widthDp: Int,
        val heightDp: Int,
        val fontScale: Float,
        val connectionTitle: String,
    )

    private data class ConnectedRenderCase(
        val widthDp: Int,
        val heightDp: Int,
        val fontScale: Float,
        val streamHealth: EventStreamHealth = EventStreamHealth.Healthy,
    )

    private companion object {
        const val PREVIEW_STATUS_BODY = "连接前不显示预览、状态和会话。"
        const val PREVIEW_CONTRACT_NOTE = "预览将使用 Device API v4 JPEG 最新帧；断流、相机未接入和鉴权失败会分开显示。"
        const val GEOMETRY_TOLERANCE_PX = 1f
        val V3_CONTROL_LABELS = listOf("设置")
    }
}
