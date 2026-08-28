package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

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
}
