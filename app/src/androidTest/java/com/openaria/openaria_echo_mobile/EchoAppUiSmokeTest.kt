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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.openaria.openaria_echo_mobile.ui.EchoApp
import com.openaria.openaria_echo_mobile.ui.theme.EchoTheme
import java.util.Locale
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

    private data class RenderCase(
        val localeTag: String,
        val widthDp: Int,
        val heightDp: Int,
        val fontScale: Float,
        val connectionTitle: String,
    )
}
