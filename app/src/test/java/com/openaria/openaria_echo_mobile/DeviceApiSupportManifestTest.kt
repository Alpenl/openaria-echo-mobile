package com.openaria.openaria_echo_mobile

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceApiSupportManifestTest {
    @Test
    fun `mobile support manifest pins v4 and fails closed`() {
        val manifest = File("src/main/assets/device-api-support.json").readText()

        assertContains(manifest, "\"consumer\": \"openaria-echo-mobile\"")
        assertContains(manifest, "\"basePath\": \"/api/v4\"")
        assertContains(manifest, "\"supportedMajors\": [4]")
        assertContains(manifest, "\"unknownMajor\": \"fail_closed\"")
        assertContains(manifest, "f1185da08f50857d1f231701d14dfc42ab5cf3f6abce65d5d6d5c90510a52210")
        assertContains(manifest, "\"bytes\": 120760")
        assertContains(manifest, "\"status\": \"present\"")
        assertContains(manifest, "\"enabled\": true")
        assertContains(manifest, "\"scope\": \"supported_v4_operations_only\"")
        assertContains(manifest, "verified body identity token indexing")
        assertContains(manifest, "EndpointPolicy-guarded platform cleartext for dynamic local origins")
        assertContains(manifest, "Android NSD discovery for _ylx-capture._tcp")
        assertContains(manifest, "foreground resume authoritative reconciliation")
        assertContains(manifest, "camera focus read/write")
        assertContains(manifest, "preview 503 camera_not_connected/preview_unavailable mapping")
        assertContains(manifest, "stale preview frame warning overlay")
        assertContains(manifest, "artifact HEAD metadata validation")
        assertContains(manifest, "artifact Range download resume")
        assertContains(manifest, "artifact foreground notification updates")
        assertContains(manifest, "live IMU preview overlay")
        assertContains(manifest, "camera-connected capture admission gating")
        assertContains(manifest, "recording-safe system back behavior")
        assertContains(manifest, "session ledger local filters all/available/unsuccessful")
        assertContains(manifest, "safe-swap event authority_epoch/source_revision projection")
        assertContains(manifest, "safe-swap stop request with reason=safe_swap")
        assertContains(manifest, "safe-swap stale authority rejection")
        assertContains(manifest, "calibration capture admission gating")
        assertContains(manifest, "calibration capture start mode")
        assertContains(manifest, "authoritative /network desired/observed/saved/verified status")
        assertContains(manifest, "one-use credential_ref exchange")
        assertContains(manifest, "idempotent network apply transactions")
        assertContains(manifest, "hotspot network apply transactions")
        assertContains(manifest, "Ethernet DHCP network apply transactions")
        assertContains(manifest, "Ethernet static IPv4 network apply transactions")
        assertContains(manifest, "network transaction retry")
        assertContains(manifest, "network client profile forget")
        assertContains(manifest, "network SSE Last-Event-ID replay")
        assertContains(manifest, "Rescue AP transaction projection")
        assertContains(manifest, "MockWebServer Device API integration tests")
        assertContains(manifest, "\"failClosed\": []")
    }

    @Test
    fun `authoritative openapi source matches the pinned device contract`() {
        val openApi = File("../openapi/ylx-device-v4.openapi.yaml")
        val bytes = openApi.readBytes()

        assertTrue(openApi.isFile)
        assertEquals(120760, bytes.size)
        assertEquals(
            "f1185da08f50857d1f231701d14dfc42ab5cf3f6abce65d5d6d5c90510a52210",
            sha256(bytes),
        )
        assertContains(openApi.readText(), "version: 4.0.0")
        assertContains(openApi.readText(), "/api/v4")
        assertContains(openApi.readText(), "/network/apply")
        assertContains(openApi.readText(), "NetworkTransaction")
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
