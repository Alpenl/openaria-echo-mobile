package com.openaria.openaria_echo_mobile.ui

import com.openaria.openaria_echo_mobile.R
import com.openaria.openaria_echo_mobile.body.api.CameraConnectionStatus
import com.openaria.openaria_echo_mobile.body.api.CaptureStatusSnapshot
import com.openaria.openaria_echo_mobile.body.api.DeviceConnection
import com.openaria.openaria_echo_mobile.body.api.DeviceDescriptor
import com.openaria.openaria_echo_mobile.body.api.DeviceRuntime
import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureCommandGateTest {
    @Test
    fun `double click submits exactly one capture POST`() {
        val gate = CaptureCommandGate()
        var postCount = 0

        repeat(2) {
            if (gate.tryAcquire()) postCount += 1
        }

        assertEquals(1, postCount)
        gate.release()
        assertTrue(gate.tryAcquire(), "a completed command must allow a new explicit operation")
    }

    @Test
    fun `stream state shows connecting and reconnecting without blocking restored snapshots`() {
        val connection = connectedBody()
        val idle = captureStatus("idle")
        val recording = captureStatus("recording")

        assertEquals(R.string.capture_stream_connecting, captureStreamStatusLabel(EventStreamHealth.Starting))
        assertEquals(R.string.capture_stream_reconnecting, captureStreamStatusLabel(EventStreamHealth.Degraded))
        assertNull(captureStreamStatusLabel(EventStreamHealth.Healthy))
        listOf(EventStreamHealth.Starting, EventStreamHealth.Degraded).forEach {
            assertTrue(canStartCapture(connection, idle, captureCommandRunning = false))
            assertTrue(canStopCapture(connection, recording, captureCommandRunning = false))
        }
        assertFalse(canStartCapture(connection, idle, captureCommandRunning = true))
    }

    private fun connectedBody(): DeviceConnection {
        val target = (EndpointPolicy.validate("http://127.0.0.1:8080") as EndpointPolicy.Decision.Allowed).target
        val runtime = runtime()
        return DeviceConnection(
            target = target,
            descriptor = DeviceDescriptor(
                deviceId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                deviceLabel = "YLX-00ABCDEF",
                hardwareFingerprint = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                packageVersion = "0.1.8",
                commit = "77f24f3777777777777777777777777777777777",
                buildId = "test",
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
                runtime = runtime,
            ),
            bearerToken = null,
        )
    }

    private fun captureStatus(deviceState: String): CaptureStatusSnapshot {
        return CaptureStatusSnapshot(
            authorityEpoch = "e989c6e5-14cc-4faa-9715-5abdb6b0355d",
            sourceRevision = 7L,
            deviceState = deviceState,
            hasActiveRecording = deviceState == "recording",
            runtime = runtime(),
        )
    }

    private fun runtime(): DeviceRuntime {
        return DeviceRuntime(
            observedAt = "2026-08-31T10:00:00Z",
            connectionMethod = "wifi_client",
            temperatureCelsius = 45.0,
            camera = CameraConnectionStatus("connected"),
        )
    }
}
