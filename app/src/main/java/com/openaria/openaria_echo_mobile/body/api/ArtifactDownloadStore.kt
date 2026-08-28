package com.openaria.openaria_echo_mobile.body.api

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class ArtifactDownloadStore(context: Context) {
    private val appContext = context.applicationContext
    private val notifier = ArtifactDownloadNotifier(appContext)

    fun download(
        client: DeviceHttpClient,
        connection: DeviceConnection,
        sessionId: String,
        artifact: ArtifactDescriptor,
        shouldCancel: () -> Boolean = { false },
    ): ArtifactFileResult {
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")
        if (!directory.exists() && !directory.mkdirs()) {
            return ArtifactFileResult.Failed("could not create download directory")
        }

        val target = File(directory, artifact.safeFilename())
        val partial = File(directory, "${target.name}.part")
        val preparation = ArtifactTransfer.planPreparation(
            artifact = artifact,
            local = ArtifactLocalFiles(
                target = target.toLocalFile(hashWhenCompleteBytes = artifact.bytes),
                partial = partial.toLocalFile(),
            ),
        )
        if (preparation is ArtifactPreparationPlan.AlreadySaved) {
            notifier.saved(artifact, target.absolutePath)
            return ArtifactFileResult.Saved(target.absolutePath, artifact.bytes, artifact.mediaType)
        }
        val downloadPlan = preparation as ArtifactPreparationPlan.Download

        if (downloadPlan.deleteTargetBeforeDownload && target.exists() && !target.delete()) {
            notifier.failed(artifact)
            return ArtifactFileResult.Failed("could not replace existing artifact")
        }
        if (downloadPlan.deletePartialBeforeDownload && partial.exists() && !partial.delete()) {
            notifier.failed(artifact)
            return ArtifactFileResult.Failed("could not remove stale partial download")
        }

        when (val head = client.headSessionArtifact(connection, sessionId, artifact)) {
            ArtifactHeadResult.Verified -> Unit
            else -> {
                notifier.failed(artifact)
                return ArtifactFileResult.DownloadRejected(ArtifactTransfer.headToDownloadResult(head))
            }
        }

        notifier.running(artifact, downloadPlan.resumeFromBytes)
        val result = try {
            FileOutputStream(partial, downloadPlan.resumeFromBytes > 0L).use { output ->
                client.downloadSessionArtifact(
                    connection = connection,
                    sessionId = sessionId,
                    artifact = artifact,
                    output = output,
                    resumeFromBytes = downloadPlan.resumeFromBytes,
                    shouldCancel = shouldCancel,
                    onBytesWritten = { bytes -> notifier.running(artifact, bytes) },
                )
            }
        } catch (exception: Exception) {
            ArtifactDownloadResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }

        return when (
            val completion = ArtifactTransfer.planCompletion(
                artifact = artifact,
                partialBytes = if (partial.exists()) partial.length() else 0L,
                partialSha256 = if (partial.exists() && partial.length() == artifact.bytes) partial.sha256() else null,
                downloadResult = result,
            )
        ) {
            ArtifactCompletionPlan.Publish -> {
                if (target.exists() && !target.delete()) {
                    partial.delete()
                    notifier.failed(artifact)
                    return ArtifactFileResult.Failed("could not replace existing artifact")
                }
                if (!partial.renameTo(target)) {
                    partial.delete()
                    notifier.failed(artifact)
                    return ArtifactFileResult.Failed("could not finalize artifact")
                }
                notifier.saved(artifact, target.absolutePath)
                ArtifactFileResult.Saved(target.absolutePath, artifact.bytes, artifact.mediaType)
            }
            is ArtifactCompletionPlan.KeepPartialAndReject -> {
                if (completion.reason is ArtifactDownloadResult.Cancelled) {
                    notifier.cancelled(artifact)
                } else {
                    notifier.failed(artifact)
                }
                ArtifactFileResult.DownloadRejected(completion.reason)
            }
            is ArtifactCompletionPlan.DeletePartialAndReject -> {
                partial.delete()
                notifier.failed(artifact)
                ArtifactFileResult.DownloadRejected(completion.reason)
            }
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(this).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.toLocalFile(hashWhenCompleteBytes: Long? = null): ArtifactLocalFile {
        if (!exists()) {
            return ArtifactLocalFile(exists = false)
        }
        val bytes = length()
        return ArtifactLocalFile(
            exists = true,
            bytes = bytes,
            sha256 = if (hashWhenCompleteBytes == bytes) sha256() else null,
        )
    }

    private fun ArtifactDescriptor.safeFilename(): String {
        val safeRole = role.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$safeRole-${artifactId.take(12)}.${extension()}"
    }

    private fun ArtifactDescriptor.extension(): String {
        return when (mediaType) {
            "video/mp4" -> "mp4"
            "video/x-motion-jpeg" -> "mjpeg"
            "audio/wav" -> "wav"
            "application/x-ndjson" -> "ndjson"
            else -> "bin"
        }
    }
}

sealed interface ArtifactFileResult {
    data class Saved(val path: String, val bytes: Long, val mediaType: String) : ArtifactFileResult
    data class DownloadRejected(val reason: ArtifactDownloadResult) : ArtifactFileResult
    data class Failed(val message: String) : ArtifactFileResult
}
