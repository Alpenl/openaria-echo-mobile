package com.openaria.openaria_echo_mobile.body.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArtifactTransferTest {
    @Test
    fun `uses existing verified target without network download`() {
        val artifact = artifact(bytes = 5)

        val plan = ArtifactTransfer.planPreparation(
            artifact = artifact,
            local = ArtifactLocalFiles(
                target = ArtifactLocalFile(exists = true, bytes = 5, sha256 = artifact.sha256),
                partial = ArtifactLocalFile(exists = false),
            ),
        )

        assertIs<ArtifactPreparationPlan.AlreadySaved>(plan)
    }

    @Test
    fun `deletes prior partial and starts a new download attempt`() {
        val artifact = artifact(bytes = 10)

        val plan = ArtifactTransfer.planPreparation(
            artifact = artifact,
            local = ArtifactLocalFiles(
                target = ArtifactLocalFile(exists = false),
                partial = ArtifactLocalFile(exists = true, bytes = 4),
            ),
        )

        val download = assertIs<ArtifactPreparationPlan.Download>(plan)
        assertEquals(true, download.deletePartialBeforeDownload)
    }

    @Test
    fun `deletes stale oversize partial before restarting download`() {
        val artifact = artifact(bytes = 10)

        val plan = ArtifactTransfer.planPreparation(
            artifact = artifact,
            local = ArtifactLocalFiles(
                target = ArtifactLocalFile(exists = false),
                partial = ArtifactLocalFile(exists = true, bytes = 12),
            ),
        )

        val download = assertIs<ArtifactPreparationPlan.Download>(plan)
        assertEquals(true, download.deletePartialBeforeDownload)
    }

    @Test
    fun `replaces corrupt target and deletes any prior partial`() {
        val artifact = artifact(bytes = 10)

        val plan = ArtifactTransfer.planPreparation(
            artifact = artifact,
            local = ArtifactLocalFiles(
                target = ArtifactLocalFile(exists = true, bytes = 10, sha256 = "bad"),
                partial = ArtifactLocalFile(exists = true, bytes = 6),
            ),
        )

        val download = assertIs<ArtifactPreparationPlan.Download>(plan)
        assertEquals(true, download.deleteTargetBeforeDownload)
        assertEquals(true, download.deletePartialBeforeDownload)
    }

    @Test
    fun `publishes only a complete partial with matching SHA-256`() {
        val artifact = artifact(bytes = 5)

        val plan = ArtifactTransfer.planCompletion(
            artifact = artifact,
            partialBytes = 5,
            partialSha256 = artifact.sha256,
            downloadResult = ArtifactDownloadResult.Downloaded(5),
        )

        assertIs<ArtifactCompletionPlan.Publish>(plan)
    }

    @Test
    fun `deletes partial file when user cancels download`() {
        val artifact = artifact(bytes = 10)

        val plan = ArtifactTransfer.planCompletion(
            artifact = artifact,
            partialBytes = 4,
            partialSha256 = null,
            downloadResult = ArtifactDownloadResult.Cancelled(4),
        )

        val rejected = assertIs<ArtifactCompletionPlan.DeletePartialAndReject>(plan)
        assertIs<ArtifactDownloadResult.Cancelled>(rejected.reason)
    }

    @Test
    fun `deletes partial file after network failure`() {
        val artifact = artifact(bytes = 10)

        val plan = ArtifactTransfer.planCompletion(
            artifact = artifact,
            partialBytes = 4,
            partialSha256 = null,
            downloadResult = ArtifactDownloadResult.NetworkFailure("timeout"),
        )

        val rejected = assertIs<ArtifactCompletionPlan.DeletePartialAndReject>(plan)
        assertIs<ArtifactDownloadResult.NetworkFailure>(rejected.reason)
    }

    @Test
    fun `deletes partial file when SHA-256 verification fails`() {
        val artifact = artifact(bytes = 5)

        val plan = ArtifactTransfer.planCompletion(
            artifact = artifact,
            partialBytes = 5,
            partialSha256 = "bad",
            downloadResult = ArtifactDownloadResult.Downloaded(5),
        )

        val rejected = assertIs<ArtifactCompletionPlan.DeletePartialAndReject>(plan)
        assertIs<ArtifactDownloadResult.IntegrityFailure>(rejected.reason)
    }

    @Test
    fun `deletes partial file when range is not satisfiable`() {
        val artifact = artifact(bytes = 10)

        val plan = ArtifactTransfer.planCompletion(
            artifact = artifact,
            partialBytes = 4,
            partialSha256 = null,
            downloadResult = ArtifactDownloadResult.RangeNotSatisfiable,
        )

        val rejected = assertIs<ArtifactCompletionPlan.DeletePartialAndReject>(plan)
        assertEquals(ArtifactDownloadResult.RangeNotSatisfiable, rejected.reason)
    }

    @Test
    fun `maps verified HEAD defensively as impossible to reject`() {
        val failure = runCatching {
            ArtifactTransfer.headToDownloadResult(ArtifactHeadResult.Verified)
        }

        assertTrue(failure.isFailure)
    }

    private fun artifact(bytes: Long): ArtifactDescriptor {
        return ArtifactDescriptor(
            artifactId = SHA,
            role = "frames.index",
            path = "frames.ndjson",
            mediaType = "application/x-ndjson",
            bytes = bytes,
            sha256 = SHA,
        )
    }

    private companion object {
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
