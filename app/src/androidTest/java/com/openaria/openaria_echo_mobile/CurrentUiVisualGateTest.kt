package com.openaria.openaria_echo_mobile

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class CurrentUiVisualGateTest {
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
                        PACKAGE_NAME,
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
    fun currentApkKeepsSemanticsAndGeometryInsideTheRealSystemSafeArea() {
        val instrumentationArguments = InstrumentationRegistry.getArguments()
        val profileName = instrumentationArguments.getString(PROFILE_ARGUMENT)
        assumeTrue("The profile matrix supplies $PROFILE_ARGUMENT.", !profileName.isNullOrBlank())
        val profile = VisualProfile.from(profileName!!)
        val expectation = runnerExpectation(instrumentationArguments)
        profile.assertRunnerExpectation(expectation)

        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = WINDOW_FOCUS_TIMEOUT_MILLIS) {
            compose.activity.window.decorView.hasWindowFocus()
        }

        val activity = compose.activity
        assertEquals("The current APK target package must own the Activity.", PACKAGE_NAME, activity.packageName)
        assertEquals(
            "The current APK target window must belong to MainActivity.",
            MainActivity::class.java.name,
            activity.componentName.className,
        )
        assertTrue("The current APK target window must be focused before evidence capture.", activity.window.decorView.hasWindowFocus())
        val configuration = activity.resources.configuration
        val decorView = activity.window.decorView
        val windowInsets = requireNotNull(ViewCompat.getRootWindowInsets(decorView)) {
            "Root WindowInsets must be available for the current APK window."
        }
        val windowBounds = activity.windowManager.currentWindowMetrics.bounds
        val safeInsets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val safeBounds = Rect(
            left = safeInsets.left.toFloat(),
            top = safeInsets.top.toFloat(),
            right = (windowBounds.width() - safeInsets.right).toFloat(),
            bottom = (windowBounds.height() - safeInsets.bottom).toFloat(),
        )
        val rootBounds = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val navigationMode = navigationInteractionMode(activity)

        assertProfileEnvironment(
            profile = profile,
            expectation = expectation,
            configuration = configuration,
            windowWidthPx = windowBounds.width(),
            windowHeightPx = windowBounds.height(),
            densityDpi = activity.resources.displayMetrics.densityDpi,
            displayRotation = requireNotNull(activity.display) { "The target Activity must be attached to a display." }.rotation,
            navigationMode = navigationMode,
            cutout = windowInsets.displayCutout,
            cutoutInsets = cutoutInsets,
        )
        assertTrue("Window safe area must have positive size: $safeBounds", safeBounds.width > 0f && safeBounds.height > 0f)
        assertTrue("Compose root must have positive size: $rootBounds", rootBounds.width > 0f && rootBounds.height > 0f)
        assertTrue(
            "Compose root width ${rootBounds.width} must match window width ${windowBounds.width()}.",
            abs(rootBounds.width - windowBounds.width()) <= GEOMETRY_TOLERANCE_PX,
        )
        assertTrue(
            "Compose root height ${rootBounds.height} must match window height ${windowBounds.height()}.",
            abs(rootBounds.height - windowBounds.height()) <= GEOMETRY_TOLERANCE_PX,
        )

        val topStatus = compose
            .onNodeWithContentDescription("Open Aria Echo, 未连接机身", useUnmergedTree = true)
            .fetchSemanticsNode()
        val tabMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val tabs = compose.onAllNodes(tabMatcher, useUnmergedTree = true).fetchSemanticsNodes()

        assertEquals("The current UI must expose exactly four semantic primary-navigation tabs.", 4, tabs.size)
        assertEquals(
            "Exactly one primary-navigation tab must be selected.",
            1,
            tabs.count { it.config[SemanticsProperties.Selected] },
        )
        assertRectInside("top status", topStatus.boundsInRoot, safeBounds)

        val density = activity.resources.displayMetrics.density
        val minimumTouchTargetPx = 48f * density
        tabs.forEachIndexed { index, tab ->
            val bounds = tab.boundsInRoot
            assertRectInside("navigation tab $index", bounds, safeBounds)
            assertTrue(
                "Navigation tab $index is narrower than 48dp: $bounds",
                bounds.width + GEOMETRY_TOLERANCE_PX >= minimumTouchTargetPx,
            )
            assertTrue(
                "Navigation tab $index is shorter than 48dp: $bounds",
                bounds.height + GEOMETRY_TOLERANCE_PX >= minimumTouchTargetPx,
            )
            assertNoPositiveOverlap("top status", topStatus.boundsInRoot, "navigation tab $index", bounds)
        }
        tabs.indices.forEach { leftIndex ->
            ((leftIndex + 1) until tabs.size).forEach { rightIndex ->
                assertNoPositiveOverlap(
                    "navigation tab $leftIndex",
                    tabs[leftIndex].boundsInRoot,
                    "navigation tab $rightIndex",
                    tabs[rightIndex].boundsInRoot,
                )
            }
        }

        val tabWidths = tabs.map { it.boundsInRoot.width }
        val tabTops = tabs.map { it.boundsInRoot.top }
        val tabBottoms = tabs.map { it.boundsInRoot.bottom }
        assertTrue(
            "Navigation tabs must keep a stable width without clipping: $tabWidths",
            tabWidths.max() - tabWidths.min() <= GEOMETRY_TOLERANCE_PX,
        )
        assertTrue(
            "Navigation tabs must share a stable top edge: $tabTops",
            tabTops.max() - tabTops.min() <= GEOMETRY_TOLERANCE_PX,
        )
        assertTrue(
            "Navigation tabs must share a stable bottom edge: $tabBottoms",
            tabBottoms.max() - tabBottoms.min() <= GEOMETRY_TOLERANCE_PX,
        )
        assertTrue(
            "Top content and bottom navigation must leave a non-overlapping content region.",
            topStatus.boundsInRoot.bottom <= tabTops.min() + GEOMETRY_TOLERANCE_PX,
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "The current APK target window must still be focused immediately before screenshot capture.",
            activity.window.decorView.hasWindowFocus(),
        )
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("UiAutomation must capture the current APK window.", screenshot)
        screenshot!!
        assertTrue(
            "The current APK target window must remain focused through screenshot capture.",
            activity.window.decorView.hasWindowFocus(),
        )
        assertEquals(windowBounds.width(), screenshot.width)
        assertEquals(windowBounds.height(), screenshot.height)
        assertScreenshotHasRenderedContent(screenshot)

        val evidenceDirectory = File(activity.cacheDir, EVIDENCE_DIRECTORY_NAME)
        assertTrue(
            "The current-run evidence directory must be created by the target app.",
            evidenceDirectory.mkdirs() || evidenceDirectory.isDirectory,
        )
        val screenshotFile = File(evidenceDirectory, "${profile.value}.png")
        val screenshotWritten = FileOutputStream(screenshotFile).use { output ->
            screenshot.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output).also {
                output.flush()
                output.fd.sync()
            }
        }
        assertTrue("The exact validated Bitmap must be persisted as PNG.", screenshotWritten)
        assertTrue("The exact validated PNG must be non-empty.", screenshotFile.length() > 0L)

        val persistedScreenshot = BitmapFactory.decodeFile(screenshotFile.absolutePath)
        assertNotNull("The exact PNG selected for upload must decode.", persistedScreenshot)
        persistedScreenshot!!
        assertBitmapsHaveIdenticalPixels(screenshot, persistedScreenshot)
        assertScreenshotHasRenderedContent(persistedScreenshot)
        val screenshotSha256 = sha256(screenshotFile)

        val evidence = JSONObject()
            .put("schema", "openaria.echo.mobile.current-ui-evidence.v1")
            .put("profile", profile.value)
            .put("targetPackage", activity.packageName)
            .put("targetActivity", activity.componentName.className)
            .put("targetWindowFocused", activity.window.decorView.hasWindowFocus())
            .put("orientation", configuration.orientation)
            .put("screenWidthDp", configuration.screenWidthDp)
            .put("screenHeightDp", configuration.screenHeightDp)
            .put("densityDpi", activity.resources.displayMetrics.densityDpi)
            .put("displayRotation", activity.display?.rotation)
            .put("navigationInteractionMode", navigationMode)
            .put("windowWidthPx", windowBounds.width())
            .put("windowHeightPx", windowBounds.height())
            .put("expectedWindowWidthPx", expectation.windowWidthPx)
            .put("expectedWindowHeightPx", expectation.windowHeightPx)
            .put("expectedDensityDpi", expectation.densityDpi)
            .put("expectedRotation", expectation.rotation)
            .put("safeInsetsPx", insetsJson(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom))
            .put("safeBoundsPx", rectJson(safeBounds))
            .put("cutoutInsetsPx", insetsJson(cutoutInsets.left, cutoutInsets.top, cutoutInsets.right, cutoutInsets.bottom))
            .put("cutoutBoundsCount", windowInsets.displayCutout?.boundingRects?.size ?: 0)
            .put("topStatusBoundsPx", rectJson(topStatus.boundsInRoot))
            .put(
                "navigationTabBoundsPx",
                JSONArray().apply {
                    tabs.forEach { put(rectJson(it.boundsInRoot)) }
                },
            )
            .put("sampledScreenshotColors", sampledColorCount(screenshot))
            .put("screenshotPngSha256", screenshotSha256)

        val geometryFile = File(evidenceDirectory, "${profile.value}.json")
        geometryFile.writeText(evidence.toString(), Charsets.UTF_8)
        assertTrue("The current-run geometry evidence must be non-empty.", geometryFile.length() > 0L)
        assertEquals(
            "The persisted geometry must identify the exact profile that produced it.",
            profile.value,
            JSONObject(geometryFile.readText(Charsets.UTF_8)).getString("profile"),
        )
        println("OPENARIA_CURRENT_UI_GEOMETRY=$evidence")
    }

    private fun assertProfileEnvironment(
        profile: VisualProfile,
        expectation: RunnerExpectation,
        configuration: Configuration,
        windowWidthPx: Int,
        windowHeightPx: Int,
        densityDpi: Int,
        displayRotation: Int,
        navigationMode: Int,
        cutout: androidx.core.view.DisplayCutoutCompat?,
        cutoutInsets: androidx.core.graphics.Insets,
    ) {
        assertEquals(
            "Profile ${profile.value} did not activate the exact requested window width.",
            expectation.windowWidthPx,
            windowWidthPx,
        )
        assertEquals(
            "Profile ${profile.value} did not activate the exact requested window height.",
            expectation.windowHeightPx,
            windowHeightPx,
        )
        assertEquals(
            "Profile ${profile.value} did not activate the exact requested density.",
            expectation.densityDpi,
            densityDpi,
        )
        assertEquals(
            "Profile ${profile.value} did not activate the exact requested display rotation.",
            expectation.rotation,
            displayRotation,
        )
        assertEquals(
            "Profile ${profile.value} did not activate the requested Android navigation mode.",
            profile.navigationMode,
            navigationMode,
        )
        if (!profile.expectsCutout) {
            assertTrue(
                "Profile ${profile.value} must explicitly have no display cutout.",
                cutout == null || cutout.boundingRects.isEmpty(),
            )
            assertTrue(
                "Profile ${profile.value} must have zero cutout insets: $cutoutInsets",
                cutoutInsets.left == 0 &&
                    cutoutInsets.top == 0 &&
                    cutoutInsets.right == 0 &&
                    cutoutInsets.bottom == 0,
            )
        }
        when (profile.layout) {
            VisualLayout.SMALL_PORTRAIT -> {
                assertEquals(Configuration.ORIENTATION_PORTRAIT, configuration.orientation)
                assertTrue(
                    "Small-screen profile must be at most 400dp wide; got ${configuration.screenWidthDp}dp.",
                    configuration.screenWidthDp <= 400,
                )
                assertTrue(
                    "Small-screen profile must be at most 700dp tall; got ${configuration.screenHeightDp}dp.",
                    configuration.screenHeightDp <= 700,
                )
            }
            VisualLayout.LANDSCAPE -> {
                assertEquals(Configuration.ORIENTATION_LANDSCAPE, configuration.orientation)
                assertTrue(configuration.screenWidthDp > configuration.screenHeightDp)
                assertTrue(
                    "Landscape profile must exercise a short viewport; got ${configuration.screenHeightDp}dp.",
                    configuration.screenHeightDp <= 400,
                )
            }
            VisualLayout.CUTOUT_PORTRAIT -> {
                assertEquals(Configuration.ORIENTATION_PORTRAIT, configuration.orientation)
                assertNotNull("Cutout profile must expose a real DisplayCutoutCompat.", cutout)
                assertTrue(
                    "Cutout profile must expose at least one cutout bounding rectangle.",
                    cutout?.boundingRects?.isNotEmpty() == true,
                )
                assertTrue(
                    "Cutout profile must contribute a non-zero unsafe inset: $cutoutInsets",
                    cutoutInsets.left > 0 || cutoutInsets.top > 0 || cutoutInsets.right > 0 || cutoutInsets.bottom > 0,
                )
            }
        }
    }

    private fun navigationInteractionMode(context: Context): Int {
        val resourceId = context.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        assertTrue("Android config_navBarInteractionMode must be available.", resourceId != 0)
        return context.resources.getInteger(resourceId)
    }

    private fun assertRectInside(name: String, actual: Rect, safe: Rect) {
        assertTrue("$name has an empty or invalid bound: $actual", actual.width > 0f && actual.height > 0f)
        assertTrue("$name crosses the safe left edge: actual=$actual safe=$safe", actual.left + GEOMETRY_TOLERANCE_PX >= safe.left)
        assertTrue("$name crosses the safe top edge: actual=$actual safe=$safe", actual.top + GEOMETRY_TOLERANCE_PX >= safe.top)
        assertTrue("$name crosses the safe right edge: actual=$actual safe=$safe", actual.right <= safe.right + GEOMETRY_TOLERANCE_PX)
        assertTrue("$name crosses the safe bottom edge: actual=$actual safe=$safe", actual.bottom <= safe.bottom + GEOMETRY_TOLERANCE_PX)
    }

    private fun assertNoPositiveOverlap(leftName: String, left: Rect, rightName: String, right: Rect) {
        val overlapWidth = max(0f, min(left.right, right.right) - max(left.left, right.left))
        val overlapHeight = max(0f, min(left.bottom, right.bottom) - max(left.top, right.top))
        assertTrue(
            "$leftName overlaps $rightName by ${overlapWidth * overlapHeight}px^2: left=$left right=$right",
            overlapWidth * overlapHeight <= GEOMETRY_TOLERANCE_PX,
        )
    }

    private fun assertScreenshotHasRenderedContent(screenshot: Bitmap) {
        val colorCount = sampledColorCount(screenshot)
        var minimumLuma = 255
        var maximumLuma = 0
        var opaqueSamples = 0
        var samples = 0
        val xStep = max(1, screenshot.width / SCREENSHOT_SAMPLE_GRID)
        val yStep = max(1, screenshot.height / SCREENSHOT_SAMPLE_GRID)
        for (y in 0 until screenshot.height step yStep) {
            for (x in 0 until screenshot.width step xStep) {
                val pixel = screenshot.getPixel(x, y)
                val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                minimumLuma = min(minimumLuma, luma)
                maximumLuma = max(maximumLuma, luma)
                if (Color.alpha(pixel) > 0) opaqueSamples += 1
                samples += 1
            }
        }
        assertTrue("Current APK screenshot looks blank; sampled only $colorCount colors.", colorCount >= 8)
        assertTrue("Current APK screenshot has insufficient luminance range: $minimumLuma..$maximumLuma", maximumLuma - minimumLuma >= 24)
        assertTrue("Current APK screenshot is unexpectedly transparent.", opaqueSamples * 100 >= samples * 95)
    }

    private fun sampledColorCount(screenshot: Bitmap): Int {
        val colors = mutableSetOf<Int>()
        val xStep = max(1, screenshot.width / SCREENSHOT_SAMPLE_GRID)
        val yStep = max(1, screenshot.height / SCREENSHOT_SAMPLE_GRID)
        for (y in 0 until screenshot.height step yStep) {
            for (x in 0 until screenshot.width step xStep) {
                colors += screenshot.getPixel(x, y)
            }
        }
        return colors.size
    }

    private fun assertBitmapsHaveIdenticalPixels(expected: Bitmap, actual: Bitmap) {
        assertEquals("The persisted PNG width must match the validated Bitmap.", expected.width, actual.width)
        assertEquals("The persisted PNG height must match the validated Bitmap.", expected.height, actual.height)
        val expectedRow = IntArray(expected.width)
        val actualRow = IntArray(actual.width)
        for (y in 0 until expected.height) {
            expected.getPixels(expectedRow, 0, expected.width, 0, y, expected.width, 1)
            actual.getPixels(actualRow, 0, actual.width, 0, y, actual.width, 1)
            assertTrue(
                "The persisted PNG pixels differ from the validated Bitmap at row $y.",
                expectedRow.contentEquals(actualRow),
            )
        }
    }

    private fun sha256(file: File): String = MessageDigest
        .getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun runnerExpectation(arguments: Bundle): RunnerExpectation = RunnerExpectation(
        windowWidthPx = requiredIntegerArgument(arguments, EXPECTED_WINDOW_WIDTH_ARGUMENT, minimum = 1),
        windowHeightPx = requiredIntegerArgument(arguments, EXPECTED_WINDOW_HEIGHT_ARGUMENT, minimum = 1),
        densityDpi = requiredIntegerArgument(arguments, EXPECTED_DENSITY_ARGUMENT, minimum = 1),
        rotation = requiredIntegerArgument(arguments, EXPECTED_ROTATION_ARGUMENT, minimum = 0).also { rotation ->
            require(rotation in 0..3) { "The expected display rotation must be in 0..3; got $rotation." }
        },
    )

    private fun requiredIntegerArgument(arguments: Bundle, name: String, minimum: Int): Int {
        val raw = requireNotNull(arguments.getString(name)) { "The profile runner must supply $name." }
        val value = requireNotNull(raw.toIntOrNull()) { "$name must be an integer; got $raw." }
        require(value >= minimum) { "$name must be at least $minimum; got $value." }
        return value
    }

    private fun rectJson(rect: Rect): JSONObject = JSONObject()
        .put("left", rect.left.toDouble())
        .put("top", rect.top.toDouble())
        .put("right", rect.right.toDouble())
        .put("bottom", rect.bottom.toDouble())

    private fun insetsJson(left: Int, top: Int, right: Int, bottom: Int): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)

    private data class RunnerExpectation(
        val windowWidthPx: Int,
        val windowHeightPx: Int,
        val densityDpi: Int,
        val rotation: Int,
    )

    private enum class VisualLayout {
        SMALL_PORTRAIT,
        LANDSCAPE,
        CUTOUT_PORTRAIT,
    }

    private enum class VisualProfile(
        val value: String,
        val layout: VisualLayout,
        val navigationMode: Int,
        val expectedWindowWidthPx: Int?,
        val expectedWindowHeightPx: Int?,
        val expectedDensityDpi: Int?,
        val expectedRotation: Int,
        val expectsCutout: Boolean,
    ) {
        SMALL_GESTURE(
            "small_gesture",
            VisualLayout.SMALL_PORTRAIT,
            NAVIGATION_MODE_GESTURAL,
            720,
            1280,
            320,
            0,
            false,
        ),
        SMALL_THREE_BUTTON(
            "small_three_button",
            VisualLayout.SMALL_PORTRAIT,
            NAVIGATION_MODE_THREE_BUTTON,
            720,
            1280,
            320,
            0,
            false,
        ),
        LANDSCAPE_GESTURE(
            "landscape_gesture",
            VisualLayout.LANDSCAPE,
            NAVIGATION_MODE_GESTURAL,
            1280,
            720,
            320,
            1,
            false,
        ),
        CUTOUT_THREE_BUTTON(
            "cutout_three_button",
            VisualLayout.CUTOUT_PORTRAIT,
            NAVIGATION_MODE_THREE_BUTTON,
            null,
            null,
            null,
            0,
            true,
        ),
        ;

        fun assertRunnerExpectation(expectation: RunnerExpectation) {
            assertEquals(
                "The runner rotation must be fixed by profile $value.",
                expectedRotation,
                expectation.rotation,
            )
            expectedWindowWidthPx?.let { expected ->
                assertEquals("The runner width must be fixed by profile $value.", expected, expectation.windowWidthPx)
            }
            expectedWindowHeightPx?.let { expected ->
                assertEquals("The runner height must be fixed by profile $value.", expected, expectation.windowHeightPx)
            }
            expectedDensityDpi?.let { expected ->
                assertEquals("The runner density must be fixed by profile $value.", expected, expectation.densityDpi)
            }
        }

        companion object {
            fun from(value: String): VisualProfile = entries.singleOrNull { it.value == value }
                ?: error("Unsupported visual profile: $value")
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.openaria.openaria_echo_mobile"
        const val PROFILE_ARGUMENT = "visualProfile"
        const val EXPECTED_WINDOW_WIDTH_ARGUMENT = "expectedWindowWidthPx"
        const val EXPECTED_WINDOW_HEIGHT_ARGUMENT = "expectedWindowHeightPx"
        const val EXPECTED_DENSITY_ARGUMENT = "expectedDensityDpi"
        const val EXPECTED_ROTATION_ARGUMENT = "expectedRotation"
        const val EVIDENCE_DIRECTORY_NAME = "openaria-current-ui"
        const val NAVIGATION_MODE_THREE_BUTTON = 0
        const val NAVIGATION_MODE_GESTURAL = 2
        const val SCREENSHOT_SAMPLE_GRID = 32
        const val GEOMETRY_TOLERANCE_PX = 2f
        const val WINDOW_FOCUS_TIMEOUT_MILLIS = 10_000L
        const val PNG_QUALITY = 100
    }
}
