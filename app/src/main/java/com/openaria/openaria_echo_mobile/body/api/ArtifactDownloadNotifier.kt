package com.openaria.openaria_echo_mobile.body.api

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openaria.openaria_echo_mobile.R
import kotlin.math.absoluteValue

class ArtifactDownloadNotifier(private val context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    fun running(artifact: ArtifactDescriptor, downloadedBytes: Long) {
        notify(
            artifact = artifact,
            text = appContext.getString(R.string.artifact_download_running, artifact.role),
            ongoing = true,
            downloadedBytes = downloadedBytes,
        )
    }

    fun saved(artifact: ArtifactDescriptor, path: String) {
        notify(
            artifact = artifact,
            text = appContext.getString(R.string.artifact_download_saved, artifact.role, artifact.bytes, path),
            ongoing = false,
            downloadedBytes = artifact.bytes,
        )
    }

    fun cancelled(artifact: ArtifactDescriptor) {
        notify(
            artifact = artifact,
            text = appContext.getString(R.string.artifact_download_cancelled, artifact.role),
            ongoing = false,
            downloadedBytes = null,
        )
    }

    fun failed(artifact: ArtifactDescriptor) {
        notify(
            artifact = artifact,
            text = appContext.getString(R.string.artifact_notification_failed, artifact.role),
            ongoing = false,
            downloadedBytes = null,
        )
    }

    @SuppressLint("MissingPermission")
    private fun notify(
        artifact: ArtifactDescriptor,
        text: String,
        ongoing: Boolean,
        downloadedBytes: Long?,
    ) {
        if (!canPostNotifications()) {
            return
        }
        ensureChannel()
        val progressBytes = downloadedBytes?.coerceIn(0L, artifact.bytes)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(appContext.getString(R.string.download_artifact))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (progressBytes == null) {
            builder.setProgress(0, 0, ongoing)
        } else {
            builder.setProgress(artifact.bytes.toInt().coerceAtLeast(1), progressBytes.toInt(), false)
        }

        try {
            notificationManager.notify(notificationId(artifact), builder.build())
        } catch (_: SecurityException) {
            // Notification permission can change between the check and notify().
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.artifact_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun notificationId(artifact: ArtifactDescriptor): Int {
        return ("${artifact.role}:${artifact.artifactId}").hashCode().absoluteValue.coerceAtLeast(1)
    }

    private companion object {
        const val CHANNEL_ID = "artifact_downloads"
    }
}
