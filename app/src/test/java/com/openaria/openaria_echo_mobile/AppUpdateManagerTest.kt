package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertEquals(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            manifest.signingCertificateSha256,
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
    fun `update manifest binds APK URL to this repository and offered version tag`() {
        val wrongRepository = assertFailsWithMessage {
            AppUpdateManager.Manifest.fromJson(
                JSONObject(
                    updateManifestJson(
                        apkUrl = "https://github.com/example/fork/releases/download/v0.2.0/app.apk",
                    ),
                ),
            )
        }
        val wrongTag = assertFailsWithMessage {
            AppUpdateManager.Manifest.fromJson(
                JSONObject(
                    updateManifestJson(
                        apkUrl = "https://github.com/Alpenl/openaria-echo-mobile/releases/download/v9.9.9/app.apk",
                    ),
                ),
            )
        }
        val nestedAssetPath = assertFailsWithMessage {
            AppUpdateManager.Manifest.fromJson(
                JSONObject(
                    updateManifestJson(
                        apkUrl = "https://github.com/Alpenl/openaria-echo-mobile/releases/download/v0.2.0/subdir/app.apk",
                    ),
                ),
            )
        }

        assertContains(wrongRepository, "repository")
        assertContains(wrongTag, "version tag")
        assertContains(nestedAssetPath, "one APK release asset")
    }

    @Test
    fun `update manifest rejects unsupported schema`() {
        val message = assertFailsWithMessage {
            AppUpdateManager.Manifest.fromJson(
                JSONObject(
                    updateManifestJson().replace(
                        "openaria.echo.mobile.android-update.v1",
                        "openaria.echo.mobile.android-update.v2",
                    ),
                ),
            )
        }

        assertContains(message, "unsupported")
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

    @Test
    fun `manifest request failures never report the installed app as current`() {
        val installed = installedIdentity()

        listOf(404, 429, 500).forEach { status ->
            val message = assertFailsWithMessage {
                AppUpdateManager.evaluateManifestResponse(status, "", installed)
            }
            assertContains(message, "HTTP $status")
        }

        val malformed = assertFailsWithMessage {
            AppUpdateManager.evaluateManifestResponse(200, "not-json", installed)
        }
        assertTrue(malformed.isNotBlank())
    }

    @Test
    fun `valid manifest response reports only a newer release as available`() {
        val state = AppUpdateManager.evaluateManifestResponse(
            200,
            updateManifestJson(),
            installedIdentity(),
        )

        assertEquals(AppUpdateManager.Phase.AVAILABLE, state.phase)
        assertEquals(12L, state.manifest?.versionCode)
    }

    @Test
    fun `same or older valid release is current but inconsistent same code fails closed`() {
        val installed = installedIdentity()
        val same = AppUpdateManager.evaluateManifestResponse(
            200,
            updateManifestJson(version = "0.1.2", versionCode = 5),
            installed,
        )
        val older = AppUpdateManager.evaluateManifestResponse(
            200,
            updateManifestJson(version = "0.1.1", versionCode = 4),
            installed,
        )

        assertEquals(AppUpdateManager.Phase.CURRENT, same.phase)
        assertEquals(AppUpdateManager.Phase.CURRENT, older.phase)
        val message = assertFailsWithMessage {
            AppUpdateManager.evaluateManifestResponse(
                200,
                updateManifestJson(version = "0.1.3", versionCode = 5),
                installed,
            )
        }
        assertContains(message, "version name")
    }

    @Test
    fun `asset HTTP errors fail before any downloaded bytes are accepted`() {
        listOf(404, 416, 500).forEach { status ->
            val message = assertFailsWithMessage {
                AppUpdateManager.requireSuccessfulAssetResponse(status)
            }
            assertContains(message, "HTTP $status")
        }
        AppUpdateManager.requireSuccessfulAssetResponse(200)
    }

    @Test
    fun `update manifest requires a release signing certificate digest`() {
        val message = assertFailsWithMessage {
            AppUpdateManager.Manifest.fromJson(
                JSONObject(updateManifestJson(signingCertificateSha256 = "")),
            )
        }

        assertContains(message, "signingCertificateSha256")
    }

    @Test
    fun `downloaded update must match manifest app identity and installed signer`() {
        val manifest = AppUpdateManager.Manifest.fromJson(JSONObject(updateManifestJson()))
        val installed = installedIdentity()
        val candidate = AppUpdateManager.ApkIdentity(
            "com.openaria.openaria_echo_mobile",
            "0.2.0",
            12,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )

        AppUpdateManager.verifyCandidateIdentity(manifest, installed, candidate)

        val wrongPackage = assertFailsWithMessage {
            AppUpdateManager.verifyCandidateIdentity(
                manifest,
                installed,
                AppUpdateManager.ApkIdentity(
                    "example.injected",
                    candidate.versionName,
                    candidate.versionCode,
                    candidate.signingCertificateSha256,
                ),
            )
        }
        assertContains(wrongPackage, "package")

        val wrongVersion = assertFailsWithMessage {
            AppUpdateManager.verifyCandidateIdentity(
                manifest,
                installed,
                AppUpdateManager.ApkIdentity(
                    candidate.packageName,
                    "0.2.1",
                    candidate.versionCode,
                    candidate.signingCertificateSha256,
                ),
            )
        }
        assertContains(wrongVersion, "version name")

        val wrongCode = assertFailsWithMessage {
            AppUpdateManager.verifyCandidateIdentity(
                manifest,
                installed,
                AppUpdateManager.ApkIdentity(
                    candidate.packageName,
                    candidate.versionName,
                    13,
                    candidate.signingCertificateSha256,
                ),
            )
        }
        assertContains(wrongCode, "version code")

        val wrongSigner = assertFailsWithMessage {
            AppUpdateManager.verifyCandidateIdentity(
                manifest,
                installed,
                AppUpdateManager.ApkIdentity(
                    candidate.packageName,
                    candidate.versionName,
                    candidate.versionCode,
                    "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                ),
            )
        }
        assertContains(wrongSigner, "signing certificate")
    }

    @Test
    fun `downloaded update bytes must match the manifest before APK inspection`() {
        val file = File.createTempFile("openaria-update", ".apk")
        try {
            file.writeText("abc")
            val manifest = AppUpdateManager.Manifest.fromJson(
                JSONObject(
                    updateManifestJson(
                        apkBytes = 3,
                        apkSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    ),
                ),
            )

            AppUpdateManager.verifyDownloadedFile(file, manifest.apk)

            file.appendText("tampered")
            val message = assertFailsWithMessage {
                AppUpdateManager.verifyDownloadedFile(file, manifest.apk)
            }
            assertContains(message, "size")

            file.writeText("abd")
            val digestMessage = assertFailsWithMessage {
                AppUpdateManager.verifyDownloadedFile(file, manifest.apk)
            }
            assertContains(digestMessage, "SHA-256")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `process update operation admits only one check or download`() {
        val operation = AppUpdateManager.ProcessOperation(
            AppUpdateManager.State.idle(5, "0.1.2"),
        )

        assertTrue(operation.beginCheck())
        assertFalse(operation.beginCheck())
        val manifest = AppUpdateManager.Manifest.fromJson(JSONObject(updateManifestJson()))
        operation.complete(AppUpdateManager.State.available(5, "0.1.2", manifest))
        assertNotNull(operation.beginDownload())
        assertNull(operation.beginDownload())
    }

    @Test
    fun `verified APK is rechecked single flight before installer handoff and remains retryable`() {
        val manifest = AppUpdateManager.Manifest.fromJson(JSONObject(updateManifestJson()))
        val operation = AppUpdateManager.ProcessOperation(
            AppUpdateManager.State.available(5, "0.1.2", manifest),
        )

        assertNotNull(operation.beginDownload())
        operation.complete(AppUpdateManager.State.verifying(5, "0.1.2", manifest))
        assertEquals(AppUpdateManager.Phase.VERIFYING, operation.state().phase)
        operation.complete(AppUpdateManager.State.readyToInstall(5, "0.1.2", manifest))

        assertNotNull(operation.beginReadyVerification())
        assertNull(operation.beginReadyVerification())
        operation.complete(AppUpdateManager.State.readyToInstall(5, "0.1.2", manifest))
        assertTrue(operation.beginInstallHandoff())
        assertFalse(operation.beginInstallHandoff())

        operation.complete(AppUpdateManager.State.readyToInstall(5, "0.1.2", manifest))
        assertTrue(operation.state().canInstall())
    }

    @Test
    fun `activity recreation replaces the stale update listener`() {
        val activeListener = AppUpdateManager.ActiveListener()
        var oldActivityUpdates = 0
        var recreatedActivityUpdates = 0
        val oldListener = AppUpdateManager.Listener { oldActivityUpdates += 1 }
        val recreatedListener = AppUpdateManager.Listener { recreatedActivityUpdates += 1 }
        activeListener.attach(oldListener)
        activeListener.attach(recreatedListener)

        activeListener.deliver(AppUpdateManager.State.idle(5, "0.1.2"))

        assertEquals(0, oldActivityUpdates)
        assertEquals(1, recreatedActivityUpdates)
    }

    @Test
    fun `explicit retry clears the previous offered release before checking again`() {
        val manifest = AppUpdateManager.Manifest.fromJson(JSONObject(updateManifestJson()))
        val operation = AppUpdateManager.ProcessOperation(
            AppUpdateManager.State.readyToInstall(5, "0.1.2", manifest),
        )

        assertTrue(operation.beginCheck())
        assertEquals(AppUpdateManager.Phase.CHECKING, operation.state().phase)
        assertNull(operation.state().manifest)
        assertEquals(0, operation.state().downloadedBytes)
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
        version: String = "0.2.0",
        versionCode: Long = 12,
        apkUrl: String = "https://github.com/Alpenl/openaria-echo-mobile/releases/download/v$version/app.apk",
        apkSha256: String = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        apkBytes: Long = 1024,
        signingCertificateSha256: String = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
    ): String {
        return """
            {
              "schema": "openaria.echo.mobile.android-update.v1",
              "version": "$version",
              "versionCode": $versionCode,
              "packageName": "com.openaria.openaria_echo_mobile",
              "signingCertificateSha256": "$signingCertificateSha256",
              "pubDate": "2026-08-28T00:00:00Z",
              "notes": "release candidate",
              "android": {
                "apk": {
                  "url": "$apkUrl",
                  "sha256": "$apkSha256",
                  "bytes": $apkBytes
                }
              }
            }
        """.trimIndent()
    }

    private fun installedIdentity() = AppUpdateManager.ApkIdentity(
        "com.openaria.openaria_echo_mobile",
        "0.1.2",
        5,
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    )
}
