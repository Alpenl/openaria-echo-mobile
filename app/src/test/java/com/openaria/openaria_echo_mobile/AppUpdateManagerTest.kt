package com.openaria.openaria_echo_mobile

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.json.JSONObject

class AppUpdateManagerTest {
    @Test
    fun `update manifest parses signed release apk metadata`() {
        val manifest = AppUpdateManager.Manifest.fromJson(JSONObject(updateManifestJson()))

        assertEquals("0.2.0", manifest.version)
        assertEquals(12L, manifest.versionCode)
        assertEquals("com.openaria.openaria_echo_mobile", manifest.packageName)
        assertEquals("https", manifest.apk.url.protocol)
        assertEquals(1024L, manifest.apk.bytes)
        assertEquals(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            manifest.apk.sha256,
        )
    }

    @Test
    fun `update manifest rejects non HTTPS apk URLs before download`() {
        val exception = assertFailsWithMessage {
            AppUpdateManager.Manifest.fromJson(
                JSONObject(updateManifestJson(apkUrl = "http://example.com/app.apk")),
            )
        }

        assertContains(exception, "HTTPS")
    }

    @Test
    fun `update state only enables install when a newer manifest is available`() {
        val idle = AppUpdateManager.State.idle(5, "0.1.2")
        val manifest = AppUpdateManager.Manifest.fromJson(JSONObject(updateManifestJson()))
        val available = AppUpdateManager.State.available(5, "0.1.2", manifest)

        assertTrue(idle.canCheck())
        assertFalse(idle.canInstall())
        assertTrue(available.canCheck())
        assertTrue(available.canInstall())
        assertNotNull(available.manifest)
    }

    private fun assertFailsWithMessage(block: () -> Unit): String {
        try {
            block()
        } catch (exception: Exception) {
            return exception.message.orEmpty()
        }
        fail("expected update manifest validation to fail")
    }

    private fun updateManifestJson(
        apkUrl: String = "https://github.com/Alpenl/openaria-echo-mobile/releases/download/v0.2.0/app.apk",
    ): String {
        return """
            {
              "schema": "openaria.echo.mobile.android-update.v1",
              "version": "0.2.0",
              "versionCode": 12,
              "packageName": "com.openaria.openaria_echo_mobile",
              "pubDate": "2026-08-28T00:00:00Z",
              "notes": "release candidate",
              "android": {
                "apk": {
                  "url": "$apkUrl",
                  "sha256": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "bytes": 1024
                }
              }
            }
        """.trimIndent()
    }
}
