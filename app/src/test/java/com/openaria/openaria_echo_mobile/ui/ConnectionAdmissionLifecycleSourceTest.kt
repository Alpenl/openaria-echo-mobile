package com.openaria.openaria_echo_mobile.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ConnectionAdmissionLifecycleSourceTest {
    @Test
    fun `connection panel owns and cancels both admission job and blocking transport`() {
        val source = echoAppSource()
        val panel = source.substringAfter("private fun ConnectionPanel")
            .substringBefore("private fun NearbyBodiesBlock")

        assertContains(panel, "scope.launch(start = CoroutineStart.LAZY)")
        assertContains(panel, "transportCancellation.cancel()")
        assertContains(panel, "admissionJob.cancel()")
        assertContains(panel, "currentCancelProbe?.invoke()")
    }

    @Test
    fun `connection panel sanitizes exceptions and only current attempt clears state`() {
        val source = echoAppSource()
        val panel = source.substringAfter("private fun ConnectionPanel")
            .substringBefore("private fun NearbyBodiesBlock")

        assertContains(panel, "catch (exception: CancellationException)")
        assertContains(panel, "throw exception")
        assertContains(panel, "catch (_: Exception)")
        assertContains(panel, "connection verification failed")
        assertContains(panel, "if (admissionFence.cancel(attempt))")
        assertFalse(panel.contains("exception.message"))
    }

    @Test
    fun `identity change uses a stable localized primary message`() {
        val source = echoAppSource()
        val panel = source.substringAfter("private fun ConnectionPanel")
            .substringBefore("private fun NearbyBodiesBlock")
        val messageRenderer = source.substringAfter("private fun ProbeMessageText")
            .substringBefore("private fun CaptureStatusMessageBlock")
        val mapper = source.substringAfter("private fun probeMessageFor")
            .substringBefore("private fun DiscoveryState.bodies")

        assertContains(panel, "probeMessage = ProbeMessage.IdentityChanged")
        assertFalse(panel.contains("connection identity changed during admission"))
        assertContains(messageRenderer, "ProbeMessage.IdentityChanged")
        assertContains(messageRenderer, "R.string.probe_identity_changed")
        assertContains(mapper, "ProbeMessage.IdentityChanged")
    }

    private fun echoAppSource(): String {
        return File("src/main/java/com/openaria/openaria_echo_mobile/ui/EchoApp.kt").readText()
    }
}
