package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class DiscoveryTransportUiContractTest {
    @Test
    fun `verified admission records device identity in connection history`() {
        val source = echoAppSource()

        assertContains(source, "historyStore.record(")
        assertContains(source, "deviceId = connection.descriptor.deviceId")
    }

    @Test
    fun `workspace admission requires and consumes an initial capture snapshot`() {
        val source = echoAppSource()

        assertContains(source, "DeviceAdmissionClient()")
        assertContains(source, "candidates = attempt.candidates")
        assertContains(source, "isAttemptCurrent = { admissionFence.isCurrent(attempt) }")
        assertContains(source, "cancellation = transportCancellation")
        assertContains(source, "admittedCaptureStatus = admission.initialCaptureStatus")
        assertContains(source, "CaptureProjection.applyHttpSnapshot(")
        assertContains(source, "captureReconciliationGate.recordAuthoritativeSnapshot")
        assertContains(
            source,
            "val usingAdmissionSnapshot = skipInitialReconciliationGeneration == generation",
        )
        assertContains(source, "if (!usingAdmissionSnapshot)")
        assertContains(source, "admissionFence.canPublish(attempt, verifiedAdmission.connection)")
    }

    @Test
    fun `offline discovery entries are labelled and cannot be selected`() {
        val source = echoAppSource()

        assertContains(source, "enabled = body.isOnline")
        assertContains(source, "if (!body.isOnline)")
        assertContains(source, "R.string.discovery_body_offline")
        assertContains(strings("values"), "name=\"discovery_body_offline\"")
        assertContains(strings("values-en"), "name=\"discovery_body_offline\"")
    }

    @Test
    fun `probe redirect is shown as a stable protocol error`() {
        val source = echoAppSource()

        assertContains(source, "message.errorCode == DeviceHttpFailure.CODE_PROTOCOL_REDIRECT")
        assertContains(source, "R.string.probe_protocol_redirect")
        assertContains(source, "errorCode = result.errorCode")
        assertContains(strings("values"), "name=\"probe_protocol_redirect\"")
        assertContains(strings("values-en"), "name=\"probe_protocol_redirect\"")
    }

    private fun echoAppSource(): String {
        return File("src/main/java/com/openaria/openaria_echo_mobile/ui/EchoApp.kt").readText()
    }

    private fun strings(directory: String): String {
        return File("src/main/res/$directory/strings.xml").readText()
    }
}
