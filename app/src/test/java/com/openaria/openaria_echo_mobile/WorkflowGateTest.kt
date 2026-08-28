package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowGateTest {
    @Test
    fun `ci and release workflows run emulator UI tests instead of only compiling them`() {
        val ci = File("../.github/workflows/mobile-ci.yml").readText()
        val release = File("../.github/workflows/mobile-release.yml").readText()

        listOf(ci, release).forEach { workflow ->
            assertContains(workflow, "assembleDebugAndroidTest")
            assertContains(workflow, "Enable KVM for Android emulator")
            assertContains(workflow, "ReactiveCircus/android-emulator-runner@v2.38.0")
            assertContains(workflow, "api-level: 35")
            assertContains(workflow, "target: google_apis")
            assertContains(workflow, "arch: x86_64")
            assertContains(workflow, "profile: pixel_2")
            assertContains(workflow, "-gpu swiftshader")
            assertContains(workflow, "connectedDebugAndroidTest")
        }
    }

    @Test
    fun `gradle config does not keep the broken managed emulator task`() {
        val appBuild = File("build.gradle.kts").readText()

        assertFalse(
            appBuild.contains("managedDevices"),
            "AGP managed devices currently pass emulator -gpu auto-no-window, which emulator 37 rejects.",
        )
    }

    @Test
    fun `release safety gate keeps dynamic local cleartext behind endpoint policy`() {
        val appBuild = File("build.gradle.kts").readText()
        val networkSecurityConfig = File("src/main/res/xml/network_security_config.xml").readText()

        assertContains(networkSecurityConfig, "cleartextTrafficPermitted=\"true\"")
        assertContains(networkSecurityConfig, "EndpointPolicy")
        assertContains(appBuild, "networkSecurityConfig")
        assertContains(appBuild, "Unexpected production network entry points bypass EndpointPolicy review")
        assertContains(appBuild, "DeviceHttpClient.kt")
        assertContains(appBuild, "DeviceProbeClient.kt")
        assertContains(appBuild, "AppUpdateManager.java")
        assertContains(appBuild, "Update manifest URL must be HTTPS.")
        assertContains(appBuild, "android.apk.url must be an HTTPS URL")
    }

    @Test
    fun `Android release proves version signer and published update bytes`() {
        val release = File("../.github/workflows/mobile-release.yml").readText()
        val appBuild = File("build.gradle.kts").readText()

        assertContains(appBuild, "versionCode = 8")
        assertContains(appBuild, "versionName = \"0.1.5\"")
        assertContains(release, "expected_tag=\"v\${version_name}\"")
        assertContains(release, "versionCode must increase")
        assertContains(release, "apksigner verify --verbose --print-certs")
        assertContains(release, "apkanalyzer manifest application-id")
        assertContains(release, "apkanalyzer manifest version-name")
        assertContains(release, "apkanalyzer manifest version-code")
        assertContains(release, "signingCertificateSha256")
        assertContains(release, "ANDROID_RELEASE_CERT_SHA256")
        assertContains(release, "protected release certificate")
        assertFalse(
            release.contains("print-certs \"\$apk_file\" | tee"),
            "Certificate identity must be compared without copying its raw digest into Actions logs.",
        )
        assertContains(release, "Previous release signing certificate")
        assertContains(release, "Post-publish verification")
        assertContains(release, "curl --fail --location")
        assertContains(release, "cmp --silent")
        assertContains(release, "jarsigner -verify -verbose -certs")
        assertContains(release, "keytool -printcert -jarfile")
        assertContains(release, "jar verified.")
        assertContains(release, "aabSha256")
        assertContains(release, "aabBytes")
        assertTrue(
            "apkanalyzer manifest application-id".toRegex().findAll(release).count() >= 2,
            "APK package identity must be checked before and after publication.",
        )
        assertTrue(
            "apksigner verify --verbose --print-certs".toRegex().findAll(release).count() >= 3,
            "APK signature must be checked for candidate, previous release, and downloaded release.",
        )
        assertFalse(release.contains("macos-"))
        assertFalse(release.lowercase().contains("ios"))
    }

    @Test
    fun `published release upgrades the previous production baseline through its own updater`() {
        val ci = File("../.github/workflows/mobile-ci.yml").readText()
        val release = File("../.github/workflows/mobile-release.yml").readText()
        val acceptanceScript = File("../scripts/android-in-app-update-acceptance.py")

        assertTrue(acceptanceScript.isFile, "The real in-app upgrade acceptance script must be versioned.")
        val acceptance = acceptanceScript.readText()

        assertContains(release, "android_in_app_upgrade:")
        assertContains(release, "needs: [android, release]")
        assertContains(release, "previous_tag: \${{ steps.release_metadata.outputs.previous_tag }}")
        assertContains(release, "previous_version_name")
        assertContains(release, "previous_version_code")
        assertContains(release, "tag_name != \$candidate")
        assertContains(release, "Run previous production in-app upgrade")
        assertContains(release, "scripts/android-in-app-update-acceptance.py")
        listOf(ci, release).forEach { workflow ->
            assertContains(workflow, "python3 -m unittest scripts/test_android_in_app_update_acceptance.py")
        }
        assertContains(release, "Upload in-app upgrade evidence")
        val upgradeRunnerStep =
            release
                .substringAfter("      - name: Run previous production in-app upgrade")
                .substringBefore("      - name: Upload in-app upgrade evidence")
        assertTrue(
            upgradeRunnerStep.contains("script: >-"),
            "android-emulator-runner executes literal block lines separately; pass one folded shell command.",
        )
        assertFalse(
            upgradeRunnerStep.contains("script: |"),
            "A literal multi-line runner script drops the Python acceptance arguments.",
        )
        assertFalse(
            upgradeRunnerStep.contains("\\\n"),
            "Folded runner commands must not retain shell continuation lines.",
        )
        assertFalse(
            upgradeRunnerStep.contains("\n              --"),
            "More-indented argument lines remain newlines in a YAML folded scalar.",
        )
        assertContains(acceptance, "--baseline-tag")
        assertContains(acceptance, "--baseline-version-name")
        assertContains(acceptance, "--baseline-version-code")
        assertContains(acceptance, "releases/latest/download/android-update.json")
        assertContains(acceptance, "unknownSourcesPromptOpenedByBaselineApp")
        assertContains(acceptance, "Unknown Sources was already enabled")
        assertContains(acceptance, "unknownSourcesSwitchInitiallyOff")
        assertContains(acceptance, "unknownSourcesSwitchClicked")
        assertContains(acceptance, "baselineSupportsVerifiedRetry")
        assertContains(acceptance, "secondInstallTapAfterPermission")
        assertContains(acceptance, "installerHandoffObserved")
        assertContains(acceptance, "candidateDownloadedByOldApp")
        assertContains(acceptance, "certificateMatchesManifest")
        assertContains(acceptance, "manualCandidateDownload")
        assertFalse(
            acceptance.contains("BASELINE_TAG ="),
            "Acceptance must use the previous production Release, not a permanently fixed baseline.",
        )
        assertFalse(
            acceptance.contains("android.settings.MANAGE_UNKNOWN_APP_SOURCES"),
            "The baseline app must open Unknown Sources; the acceptance driver must only observe it.",
        )
        assertFalse(acceptance.contains("appops set"), "Unknown-sources permission must be granted through system UI.")
    }
}
