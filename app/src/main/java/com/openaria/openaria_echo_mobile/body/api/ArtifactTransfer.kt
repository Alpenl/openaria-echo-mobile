package com.openaria.openaria_echo_mobile.body.api

object ArtifactTransfer {
    fun planPreparation(
        artifact: ArtifactDescriptor,
        local: ArtifactLocalFiles,
    ): ArtifactPreparationPlan {
        if (local.target.exists) {
            if (local.target.bytes == artifact.bytes && local.target.sha256 == artifact.sha256) {
                return ArtifactPreparationPlan.AlreadySaved
            }
            return ArtifactPreparationPlan.Download(
                deleteTargetBeforeDownload = true,
                deletePartialBeforeDownload = local.partial.exists,
            )
        }

        return ArtifactPreparationPlan.Download(
            deleteTargetBeforeDownload = false,
            deletePartialBeforeDownload = local.partial.exists,
        )
    }

    fun planCompletion(
        artifact: ArtifactDescriptor,
        partialBytes: Long,
        partialSha256: String?,
        downloadResult: ArtifactDownloadResult,
    ): ArtifactCompletionPlan {
        return when (downloadResult) {
            is ArtifactDownloadResult.Downloaded -> {
                when {
                    partialBytes != artifact.bytes -> ArtifactCompletionPlan.DeletePartialAndReject(
                        ArtifactDownloadResult.InvalidResponse(
                            "expected ${artifact.bytes} bytes, wrote $partialBytes",
                        ),
                    )
                    partialSha256 != artifact.sha256 -> ArtifactCompletionPlan.DeletePartialAndReject(
                        ArtifactDownloadResult.IntegrityFailure(partialSha256.orEmpty()),
                    )
                    else -> ArtifactCompletionPlan.Publish
                }
            }
            else -> ArtifactCompletionPlan.DeletePartialAndReject(downloadResult)
        }
    }

    fun headToDownloadResult(head: ArtifactHeadResult): ArtifactDownloadResult {
        return when (head) {
            ArtifactHeadResult.AuthenticationRequired -> ArtifactDownloadResult.AuthenticationRequired
            ArtifactHeadResult.CaptureBusy -> ArtifactDownloadResult.CaptureBusy
            ArtifactHeadResult.Forbidden -> ArtifactDownloadResult.Forbidden
            is ArtifactHeadResult.HttpFailure -> ArtifactDownloadResult.HttpFailure(head.statusCode)
            is ArtifactHeadResult.InvalidRequest -> ArtifactDownloadResult.InvalidRequest(head.message)
            is ArtifactHeadResult.InvalidResponse -> ArtifactDownloadResult.InvalidResponse(head.message)
            is ArtifactHeadResult.NetworkFailure -> ArtifactDownloadResult.NetworkFailure(head.message)
            ArtifactHeadResult.NotFound -> ArtifactDownloadResult.NotFound
            ArtifactHeadResult.SessionNotVerified -> ArtifactDownloadResult.SessionNotVerified
            ArtifactHeadResult.Verified -> error("verified HEAD must not be mapped to a rejected download")
        }
    }

}

data class ArtifactLocalFiles(
    val target: ArtifactLocalFile,
    val partial: ArtifactLocalFile,
)

data class ArtifactLocalFile(
    val exists: Boolean,
    val bytes: Long = 0L,
    val sha256: String? = null,
)

sealed interface ArtifactPreparationPlan {
    data object AlreadySaved : ArtifactPreparationPlan
    data class Download(
        val deleteTargetBeforeDownload: Boolean,
        val deletePartialBeforeDownload: Boolean,
    ) : ArtifactPreparationPlan
}

sealed interface ArtifactCompletionPlan {
    data object Publish : ArtifactCompletionPlan
    data class DeletePartialAndReject(val reason: ArtifactDownloadResult) : ArtifactCompletionPlan
}
