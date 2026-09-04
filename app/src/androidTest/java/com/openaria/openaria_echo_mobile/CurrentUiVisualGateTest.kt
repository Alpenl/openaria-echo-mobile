package com.openaria.openaria_echo_mobile

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
        val evidenceNonce = requiredEvidenceNonce(instrumentationArguments)
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
        compose
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
                useUnmergedTree = true,
            )
            .assertCountEquals(0)
        assertRectInside("top status", topStatus.boundsInRoot, safeBounds)

        val density = activity.resources.displayMetrics.density
        val minimumTouchTargetPx = 48f * density
        val v3Controls = V3_CONTROL_RESOURCES.map { resourceId -> activity.localizedString(resourceId) }.associateWith { label ->
            val bounds = compose
                .onNodeWithContentDescription(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            assertRectInside("V3 control $label", bounds, safeBounds)
            assertTrue(
                "V3 control $label is narrower than 48dp: $bounds",
                bounds.width + GEOMETRY_TOLERANCE_PX >= minimumTouchTargetPx,
            )
            assertTrue(
                "V3 control $label is shorter than 48dp: $bounds",
                bounds.height + GEOMETRY_TOLERANCE_PX >= minimumTouchTargetPx,
            )
            bounds
        }
        v3Controls.forEach { (label, bounds) ->
            assertNoPositiveOverlap("top status", topStatus.boundsInRoot, label, bounds)
        }
        val controlEntries = v3Controls.entries.toList()
        controlEntries.indices.forEach { leftIndex ->
            ((leftIndex + 1) until controlEntries.size).forEach { rightIndex ->
                val left = controlEntries[leftIndex]
                val right = controlEntries[rightIndex]
                assertNoPositiveOverlap(left.key, left.value, right.key, right.value)
            }
        }
        assertTrue(
            "Top status and settings control must share the V3 top bar region.",
            v3Controls.getValue(activity.localizedString(R.string.v3_settings)).bottom <=
                topStatus.boundsInRoot.bottom + minimumTouchTargetPx,
        )

        val previewStatusBody = compose
            .onNodeWithText(activity.localizedString(R.string.v3_preview_disconnected_body), useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertTextHasNoVisualOverflow("preview status body", previewStatusBody)
        assertRectInside("preview status body", previewStatusBody.boundsInRoot, safeBounds)
        assertNoPositiveOverlap("top status", topStatus.boundsInRoot, "preview status body", previewStatusBody.boundsInRoot)
        v3Controls.forEach { (label, bounds) ->
            assertNoPositiveOverlap("preview status body", previewStatusBody.boundsInRoot, label, bounds)
        }
        compose
            .onAllNodesWithText(activity.localizedString(R.string.preview_contract_note), useUnmergedTree = true)
            .assertCountEquals(0)

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
            .put("evidenceNonce", evidenceNonce)
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
            .put("previewStatusBodyBoundsPx", rectJson(previewStatusBody.boundsInRoot))
            .put(
                "v3ControlBoundsPx",
                JSONObject().apply {
                    v3Controls.forEach { (label, bounds) -> put(label, rectJson(bounds)) }
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
        exportEvidenceToShell(
            uiAutomation = instrumentation.uiAutomation,
            profile = profile,
            evidenceNonce = evidenceNonce,
            screenshotFile = screenshotFile,
            geometryFile = geometryFile,
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

    private fun Context.localizedString(@StringRes resourceId: Int): String {
        val localizedConfiguration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag("zh-CN"))
        }
        return createConfigurationContext(localizedConfiguration).getString(resourceId)
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

    private fun assertTextHasNoVisualOverflow(name: String, node: SemanticsNode) {
        val layoutResults = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("$name must expose its real text layout result.", action?.invoke(layoutResults) == true)
        assertEquals("$name must produce exactly one text layout result.", 1, layoutResults.size)
        assertTrue("$name is clipped or ellipsized.", !layoutResults.single().hasVisualOverflow)
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

    private fun exportEvidenceToShell(
        uiAutomation: UiAutomation,
        profile: VisualProfile,
        evidenceNonce: String,
        screenshotFile: File,
        geometryFile: File,
    ) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Current UI evidence export requires Android 12 (API 31) or newer."
        }
        val remotePrefix = "$SHELL_EVIDENCE_ROOT/${profile.value}-$evidenceNonce"
        val rootReady = executeShellScript(
            uiAutomation,
            """
            set -eu
            mkdir -p '$SHELL_EVIDENCE_ROOT'
            test -d '$SHELL_EVIDENCE_ROOT'
            printf ready
            """.trimIndent(),
        )
        assertShellResult("prepare the shell-owned evidence directory", rootReady, expectedStdout = "ready")
        publishFileToShell(uiAutomation, screenshotFile, "$remotePrefix.png")
        publishFileToShell(uiAutomation, geometryFile, "$remotePrefix.json")
    }

    private fun publishFileToShell(
        uiAutomation: UiAutomation,
        source: File,
        destination: String,
    ) {
        val temporaryDestination = "$destination.tmp"
        val pathReady = executeShellScript(
            uiAutomation,
            """
            set -eu
            rm -f '$temporaryDestination'
            test ! -e '$destination'
            printf ready
            """.trimIndent(),
        )
        assertShellResult("prepare the exact current-run evidence path", pathReady, expectedStdout = "ready")

        val descriptors = uiAutomation.executeShellCommandRw("dd of=$temporaryDestination bs=65536")
        assertEquals("A writable shell command must expose stdout and stdin descriptors.", 2, descriptors.size)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { destinationStream ->
            FileInputStream(source).use { sourceStream ->
                sourceStream.copyTo(destinationStream)
            }
            destinationStream.flush()
        }
        val commandOutput = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { output ->
            output.readBytes().toString(Charsets.UTF_8).trim()
        }
        assertEquals("Streaming evidence to shell storage must not emit command errors.", "", commandOutput)

        val expectedSha256 = sha256(source)
        val stagedSha256 = shellSha256(uiAutomation, temporaryDestination)
        assertEquals("Shell staging bytes must match the exact instrumentation evidence.", expectedSha256, stagedSha256)
        val published = executeShellScript(
            uiAutomation,
            """
            set -eu
            mv '$temporaryDestination' '$destination'
            sha256sum '$destination'
            """.trimIndent(),
        )
        assertTrue("Publishing shell evidence must not emit stderr: ${published.stderr}", published.stderr.isBlank())
        assertEquals(
            "Published shell evidence must retain the exact instrumentation digest.",
            expectedSha256,
            published.stdout.substringBefore(' ').trim(),
        )
    }

    private fun shellSha256(uiAutomation: UiAutomation, path: String): String {
        val result = executeShellScript(uiAutomation, "set -eu\nsha256sum '$path'")
        assertTrue("Hashing shell evidence must not emit stderr: ${result.stderr}", result.stderr.isBlank())
        val digest = result.stdout.substringBefore(' ').trim()
        assertTrue(
            "Shell evidence SHA-256 must be lowercase hexadecimal; got ${result.stdout}",
            SHA256_REGEX.matches(digest),
        )
        return digest
    }

    private fun executeShellScript(uiAutomation: UiAutomation, script: String): ShellResult {
        val descriptors = uiAutomation.executeShellCommandRwe("/system/bin/sh")
        assertEquals("A shell script must expose stdout, stdin, and stderr descriptors.", 3, descriptors.size)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { input ->
            input.write((script + "\n").toByteArray(Charsets.UTF_8))
            input.flush()
        }
        val stdout = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { output ->
            output.readBytes().toString(Charsets.UTF_8).trim()
        }
        val stderr = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).use { error ->
            error.readBytes().toString(Charsets.UTF_8).trim()
        }
        return ShellResult(stdout = stdout, stderr = stderr)
    }

    private fun assertShellResult(action: String, result: ShellResult, expectedStdout: String) {
        assertEquals("Shell must $action without stderr.", "", result.stderr)
        assertEquals("Shell must $action and emit its completion marker.", expectedStdout, result.stdout)
    }

    private fun runnerExpectation(arguments: Bundle): RunnerExpectation = RunnerExpectation(
        windowWidthPx = requiredIntegerArgument(arguments, EXPECTED_WINDOW_WIDTH_ARGUMENT, minimum = 1),
        windowHeightPx = requiredIntegerArgument(arguments, EXPECTED_WINDOW_HEIGHT_ARGUMENT, minimum = 1),
        densityDpi = requiredIntegerArgument(arguments, EXPECTED_DENSITY_ARGUMENT, minimum = 1),
        rotation = requiredIntegerArgument(arguments, EXPECTED_ROTATION_ARGUMENT, minimum = 0).also { rotation ->
            require(rotation in 0..3) { "The expected display rotation must be in 0..3; got $rotation." }
        },
    )

    private fun requiredEvidenceNonce(arguments: Bundle): String {
        val nonce = requireNotNull(arguments.getString(EVIDENCE_NONCE_ARGUMENT)) {
            "The profile runner must supply $EVIDENCE_NONCE_ARGUMENT."
        }
        require(EVIDENCE_NONCE_REGEX.matches(nonce)) {
            "$EVIDENCE_NONCE_ARGUMENT must be exactly 32 lowercase hexadecimal characters."
        }
        return nonce
    }

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

    private data class ShellResult(
        val stdout: String,
        val stderr: String,
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
        LANDSCAPE_THREE_BUTTON(
            "landscape_three_button",
            VisualLayout.LANDSCAPE,
            NAVIGATION_MODE_THREE_BUTTON,
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
        const val EVIDENCE_NONCE_ARGUMENT = "evidenceNonce"
        const val EVIDENCE_DIRECTORY_NAME = "openaria-current-ui"
        const val SHELL_EVIDENCE_ROOT = "/data/local/tmp/openaria-current-ui"
        const val NAVIGATION_MODE_THREE_BUTTON = 0
        const val NAVIGATION_MODE_GESTURAL = 2
        const val SCREENSHOT_SAMPLE_GRID = 32
        const val GEOMETRY_TOLERANCE_PX = 2f
        const val WINDOW_FOCUS_TIMEOUT_MILLIS = 10_000L
        const val PNG_QUALITY = 100
        val EVIDENCE_NONCE_REGEX = Regex("^[0-9a-f]{32}$")
        val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        val V3_CONTROL_RESOURCES = listOf(
            R.string.v3_settings,
            R.string.discovery_start,
        )
    }
}
