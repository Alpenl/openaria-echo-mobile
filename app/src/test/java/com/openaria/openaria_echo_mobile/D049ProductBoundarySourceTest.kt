package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class D049ProductBoundarySourceTest {
    @Test
    fun `current capture projection has no safe swap product state or effect`() {
        val projection = File(
            "src/main/java/com/openaria/openaria_echo_mobile/body/CaptureProjection.kt",
        ).readText()

        assertFalse(projection.contains("SafeSwap"))
        assertFalse(projection.contains("safeSwap"))
        assertFalse(projection.contains("safe_swap"))
        assertFalse(projection.contains("requiresSafeSwapReconciliation"))
    }

    @Test
    fun `session child transport details stay in expandable diagnostics`() {
        val source = File(
            "src/main/java/com/openaria/openaria_echo_mobile/ui/EchoApp.kt",
        ).readText()

        assertContains(source, "message.toReadOnlyPresentation()?.let { presentation ->")
        assertContains(source, "SessionDiagnosticPanel(presentation)")
        assertFalse(source.contains("session_manifest_invalid_response, message.detail"))
        assertFalse(source.contains("session_manifest_network_failure, message.detail"))
        assertFalse(source.contains("unsuccessful_outcome_invalid_response, message.detail"))
        assertFalse(source.contains("unsuccessful_outcome_network_failure, message.detail"))
        assertFalse(source.contains("unsuccessfulOutcomeMessageBody(message)"))
    }

    @Test
    fun `network disconnect is terminal and explicit retry starts a new operation`() {
        val source = File(
            "src/main/java/com/openaria/openaria_echo_mobile/ui/EchoApp.kt",
        ).readText()
        val retryBlock = source.substringAfter("fun runRetry()").substringBefore("fun runForget()")
        val chinese = File("src/main/res/values/strings.xml").readText()
        val english = File("src/main/res/values-en/strings.xml").readText()

        assertFalse(source.contains("MutationResultPending"))
        assertFalse(source.contains("pendingNetworkMutationMessage"))
        assertContains(source, "NetworkMessage.NetworkFailure(result.message)")
        assertContains(source, "NetworkMessage.NetworkFailure(eventResult.message)")
        assertContains(retryBlock, "idempotencyKey = UUID.randomUUID().toString()")
        assertEquals(1, retryBlock.windowed("retryNetworkTransaction".length).count { it == "retryNetworkTransaction" })
        assertFalse(chinese.contains("network_mutation_result_pending") || chinese.contains("恢复连接后"))
        assertFalse(english.contains("network_mutation_result_pending") || english.contains("After reconnection"))
        assertFalse(chinese.contains("Rescue AP 对账结果"))
        assertFalse(english.contains("reconciled through /network or Rescue AP"))
        assertFalse(chinese.contains("network_recovery_reconnect_target_lan"))
        assertFalse(chinese.contains("network_recovery_reconnect_rescue_ap"))
        assertFalse(english.contains("network_recovery_reconnect_target_lan"))
        assertFalse(english.contains("network_recovery_reconnect_rescue_ap"))
        assertContains(chinese, "network_recovery_connection_changed")
        assertContains(english, "network_recovery_connection_changed")
        assertContains(chinese, "结果未知时不会自动恢复或重放")
        assertContains(english, "an unknown result will not be recovered or replayed automatically")
    }

    @Test
    fun `artifact retry never reuses a partial from an earlier attempt`() {
        val transfer = File(
            "src/main/java/com/openaria/openaria_echo_mobile/body/api/ArtifactTransfer.kt",
        ).readText()
        val store = File(
            "src/main/java/com/openaria/openaria_echo_mobile/body/api/ArtifactDownloadStore.kt",
        ).readText()
        val manifest = File("src/main/assets/device-api-support.json").readText()
        val plan = File("../docs/DEVELOPMENT_PLAN.md").readText()

        assertFalse(transfer.contains("resumeFromBytes"))
        assertFalse(transfer.contains("KeepPartialAndReject"))
        assertContains(store, "FileOutputStream(partial, false)")
        assertContains(store, "resumeFromBytes = 0L")
        assertFalse(store.contains("downloadPlan.resumeFromBytes"))
        assertFalse(store.contains("KeepPartialAndReject"))
        assertContains(manifest, "artifact cancel, failure, and .part cleanup")
        assertFalse(manifest.contains("artifact Range download resume"))
        assertFalse(manifest.contains("artifact cancel and .part preservation"))
        assertFalse(plan.contains("暂停后续传"))
        assertFalse(plan.contains("下载失败保留临时进度"))
    }
}
