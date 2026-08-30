package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class ArtifactNotificationSourceTest {
    @Test
    fun `artifact downloads keep notification permission channel and lifecycle hooks`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val notifier = File(
            "src/main/java/com/openaria/openaria_echo_mobile/body/api/ArtifactDownloadNotifier.kt",
        ).readText()
        val store = File(
            "src/main/java/com/openaria/openaria_echo_mobile/body/api/ArtifactDownloadStore.kt",
        ).readText()

        assertContains(manifest, "android.permission.POST_NOTIFICATIONS")
        assertContains(notifier, "NotificationChannel")
        assertContains(notifier, "NotificationCompat.Builder")
        assertContains(notifier, "artifact_notification_channel_name")
        assertContains(notifier, "artifact_notification_failed")
        assertContains(store, "notifier?.running")
        assertContains(store, "notifier?.saved")
        assertContains(store, "notifier?.cancelled")
        assertContains(store, "notifier?.failed")
        assertContains(store, "onBytesWritten = { bytes -> notifier?.running(artifact, bytes) }")
    }
}
