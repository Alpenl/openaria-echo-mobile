package com.openaria.openaria_echo_mobile

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceApiSupportManifestTest {
    @Test
    fun `mobile support manifest pins v4 and fails closed`() {
        val manifest = File("src/main/assets/device-api-support.json").readText()

        assertContains(manifest, "\"consumer\": \"openaria-echo-mobile\"")
        assertContains(manifest, "\"basePath\": \"/api/v4\"")
        assertContains(manifest, "\"supportedMajors\": [4]")
        assertContains(manifest, "\"unknownMajor\": \"fail_closed\"")
        assertContains(manifest, "b6f3c677c038e55c03581c587973811b0aa2dc91cfb8b602a95128fbac225827")
        assertContains(manifest, "\"bytes\": 124739")
        assertContains(manifest, "\"status\": \"present\"")
        assertContains(manifest, "\"enabled\": true")
        assertContains(manifest, "\"scope\": \"supported_v4_operations_only\"")
        assertContains(manifest, "verified body identity token indexing")
        assertContains(manifest, "EndpointPolicy-guarded platform cleartext for dynamic local origins")
        assertContains(manifest, "Android NSD discovery for _ylx-capture._tcp")
        assertContains(manifest, "initial /capture/status snapshot admission before workspace entry")
        assertContains(manifest, "job-owned admission cancellation with active transport disconnect")
        assertContains(manifest, "exact candidate-origin authentication retry binding")
        assertContains(manifest, "foreground lifecycle request cancellation")
        assertContains(manifest, "connection-generation late response rejection")
        assertContains(
            manifest,
            "closed DeviceIdentity, bounded build metadata, runtime date-time and temperature range, and lab-profile descriptor admission",
        )
        assertContains(
            manifest,
            "closed ten-key v4 capability admission with required read, download, and status support",
        )
        assertContains(manifest, "session deletion capability required false with no deletion UI")
        assertContains(manifest, "healthy SSE reconciliation budget of at most two status reads per minute")
        assertContains(manifest, "foreground viewfinder-only single-flight preview reads")
        assertContains(manifest, "camera focus read/write")
        assertContains(manifest, "preview 503 camera_not_connected/preview_unavailable mapping")
        assertContains(manifest, "stale preview frame warning overlay")
        assertContains(manifest, "artifact HEAD metadata validation")
        assertContains(manifest, "artifact descriptor-bound Range transport")
        assertContains(manifest, "artifact cancel, failure, and .part cleanup")
        assertContains(manifest, "artifact foreground notification updates")
        assertContains(manifest, "live IMU preview overlay")
        assertContains(manifest, "camera-connected capture admission gating")
        assertContains(manifest, "layered recording-safe system back confirmation behavior")
        assertContains(manifest, "tagged ylx.session-list.v2 and ylx.session-list.v3 decoding")
        assertContains(manifest, "v2 session lists constrained to first-page compatibility")
        assertContains(
            manifest,
            "limit/cursor/take_id-bound session page validation with capacity, uniqueness, and newest-first checks",
        )
        assertContains(
            manifest,
            "catalog_revision-bound v3 pagination with cursor progress, cross-page duplicate, and boundary rejection",
        )
        assertContains(
            manifest,
            "strict catalog_changed reset to a fresh no-cursor session read preserving take_id",
        )
        assertContains(
            manifest,
            "pending-dirty session refresh with lifecycle and hidden-page active transport cancellation",
        )
        assertContains(
            manifest,
            "typed quarantine diagnostics preserved across pagination in read-only UI without session promotion",
        )
        assertContains(manifest, "session ledger local filters all/available/unsuccessful")
        assertContains(
            manifest,
            "ylx.session-list.v2 is read-only first-page compatibility; its continuation cursor is never followed",
        )
        val implemented = manifest.substringAfter("\"implemented\": [").substringBefore("],")
        assertFalse(implemented.contains("safe-swap") || implemented.contains("safe_swap"))
        assertContains(manifest, "safe-swap wire parsing is frozen compatibility code")
        assertContains(manifest, "no production projection state or effect")
        assertContains(manifest, "UI, controller, polling, documentation claim, or acceptance path")
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
        assertContains(
            manifest,
            "current ylx.device-session.v2 rejects raw-side-by-side and single-file video or audio media profiles",
        )
    }

    @Test
    fun `authoritative openapi source matches the pinned device contract`() {
        val openApi = File("../openapi/ylx-device-v4.openapi.yaml")
        val bytes = openApi.readBytes()

        assertTrue(openApi.isFile)
        assertEquals(124739, bytes.size)
        assertEquals(
            "b6f3c677c038e55c03581c587973811b0aa2dc91cfb8b602a95128fbac225827",
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
