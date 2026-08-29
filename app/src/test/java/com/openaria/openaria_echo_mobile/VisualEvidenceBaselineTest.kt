package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualEvidenceBaselineTest {
    @Test
    fun `current APK visual gate renders and archives every required system profile`() {
        val instrumentation = File(
            "src/androidTest/java/com/openaria/openaria_echo_mobile/CurrentUiVisualGateTest.kt",
        )
        val landscapeThreeButtonTest = File(
            "src/androidTest/java/com/openaria/openaria_echo_mobile/LandscapeThreeButtonSafeAreaTest.kt",
        )
        val runner = File("../scripts/android-current-ui-gate.sh")
        val runnerBehaviorTests = File("../scripts/test_android_current_ui_gate.py")
        val ci = File("../.github/workflows/mobile-ci.yml").readText()
        val release = File("../.github/workflows/mobile-release.yml").readText()

        assertTrue(instrumentation.isFile, "Current APK visual instrumentation gate must be versioned.")
        assertTrue(landscapeThreeButtonTest.isFile, "Landscape three-button safe-area instrumentation must be versioned.")
        assertTrue(runner.isFile, "Current APK visual profile runner must be versioned.")
        assertTrue(runnerBehaviorTests.isFile, "Current APK visual runner behavior tests must be versioned.")

        val testSource = instrumentation.readText()
        val landscapeThreeButtonTestSource = landscapeThreeButtonTest.readText()
        val runnerSource = runner.readText()
        val runnerBehaviorTestSource = runnerBehaviorTests.readText()
        listOf(
            "small_gesture",
            "small_three_button",
            "landscape_gesture",
            "landscape_three_button",
            "cutout_three_button",
        ).forEach { profile ->
            assertContains(testSource, profile)
            assertContains(runnerSource, profile)
            assertContains(runnerBehaviorTestSource, profile)
        }
        assertContains(testSource, "createAndroidComposeRule<MainActivity>")
        assertContains(testSource, "WindowInsetsCompat.Type.displayCutout()")
        assertContains(testSource, "config_navBarInteractionMode")
        assertContains(testSource, "assertRectInside")
        assertContains(testSource, "assertNoPositiveOverlap")
        assertContains(testSource, "assertTextHasNoVisualOverflow")
        assertContains(testSource, "previewStatusBodyBoundsPx")
        assertContains(testSource, "previewControlBoundsPx")
        assertContains(testSource, "PREVIEW_CONTROL_RESOURCES")
        assertContains(testSource, "takeScreenshot()")
        assertContains(testSource, "Bitmap.CompressFormat.PNG")
        assertContains(testSource, "BitmapFactory.decodeFile")
        assertContains(testSource, "assertBitmapsHaveIdenticalPixels")
        assertContains(testSource, "screenshotPngSha256")
        assertContains(testSource, "/data/local/tmp/openaria-current-ui")
        assertContains(testSource, "executeShellCommandRw")
        assertContains(testSource, "executeShellCommandRwe(\"/system/bin/sh\")")
        assertContains(testSource, "dd of=")
        assertContains(testSource, "evidenceNonce")
        assertContains(testSource, "hasWindowFocus()")
        assertContains(testSource, "expectedWindowWidthPx")
        assertContains(testSource, "must explicitly have no display cutout")
        assertContains(landscapeThreeButtonTestSource, "WindowInsetsCompat.Type.displayCutout()")
        assertContains(landscapeThreeButtonTestSource, "PREVIEW_CONTROL_RESOURCES")
        assertContains(landscapeThreeButtonTestSource, "assertRectInside")
        assertFalse(testSource.contains("screencap -p"), "An unvalidated second screencap must never be uploaded.")
        assertContains(runnerSource, "\"\$adb_bin\" pull")
        assertFalse(runnerSource.contains("exec-out run-as"), "AGP uninstalls the app before host-side evidence export.")
        assertContains(runnerSource, "/data/local/tmp/openaria-current-ui")
        assertContains(runnerSource, "wm user-rotation lock")
        assertContains(runnerSource, "wm fixed-to-user-rotation enabled")
        assertContains(runnerSource, "android.testInstrumentationRunnerArguments.visualProfile")
        assertContains(runnerSource, "android.testInstrumentationRunnerArguments.evidenceNonce")
        assertContains(runnerSource, "android.testInstrumentationRunnerArguments.expectedDensityDpi")
        assertContains(runnerSource, "validate_gradle_results")
        assertContains(runnerSource, "wait_for_state_convergence")
        assertContains(runnerSource, "capture_profile_evidence")
        assertContains(runnerSource, "verify_pulled_evidence")
        assertContains(runnerSource, "pulled PNG does not match the Bitmap hash")
        assertContains(runnerSource, "snapshot_initial_state")
        assertContains(runnerSource, "restore_initial_state")
        assertContains(runnerSource, "trap cleanup_on_exit EXIT")
        assertContains(runnerSource, "original_exit_status")
        assertFalse(runnerSource.contains("sleep 3"), "Profile setup must use bounded state convergence, not a fixed delay.")

        listOf(ci, release).forEach { workflow ->
            assertContains(workflow, "bash scripts/android-current-ui-gate.sh android-current-ui-evidence")
            assertContains(workflow, "python3 -m unittest scripts/test_android_current_ui_gate.py")
            assertContains(workflow, "Upload current Android UI evidence")
            assertContains(workflow, "android-current-ui-evidence/**")
            assertFalse(
                workflow.contains("dogfood-output"),
                "Historical dogfood files must not satisfy the current APK visual gate.",
            )
        }

        val releaseJob = release.substringAfter("\n  release:").substringBefore("\n  android_in_app_upgrade:")
        assertContains(
            releaseJob,
            "needs: [android]",
            message = "Release publication must remain blocked on the Android job that runs every current UI gate.",
        )
    }
}
