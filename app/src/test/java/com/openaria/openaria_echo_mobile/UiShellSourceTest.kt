package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiShellSourceTest {
    @Test
    fun `app shell uses cutout aware safe drawing and fixed bottom navigation`() {
        val source = echoAppSource()

        assertContains(source, "WindowInsets.safeDrawing.asPaddingValues()")
        assertContains(source, "bottomNavigationReserve = safeDrawing.calculateBottomPadding() + 86.dp")
        assertFalse(
            source.contains("WindowInsets.systemBars.asPaddingValues()"),
            "Top layout must use safeDrawing so display cutouts and waterfall insets are included.",
        )
        assertFalse(
            source.contains("bottom = 96.dp"),
            "Content bottom padding must account for navigation bar insets instead of using a fixed reserve.",
        )
        assertFalse(
            source.contains(".height(46.dp)"),
            "The top status bar must be allowed to grow for larger fonts instead of clipping at a fixed height.",
        )
        assertContains(source, ".align(Alignment.BottomCenter)")
        assertContains(source, ".navigationBarsPadding()")
    }

    @Test
    fun `dynamic status messages use polite accessibility live regions`() {
        val source = echoAppSource()

        assertContains(source, "import androidx.compose.ui.semantics.LiveRegionMode")
        assertContains(source, "import androidx.compose.ui.semantics.liveRegion")
        assertContains(source, "liveRegion = LiveRegionMode.Polite")
        assertContains(source, "liveRegionMode = LiveRegionMode.Polite")
        assertContains(source, "contentDescription = label")
        assertContains(source, "stateDescription = state")
    }

    @Test
    fun `interactive controls keep at least forty eight dp touch targets`() {
        val source = echoAppSource()

        assertContains(source, "height(48.dp)")
        assertContains(source, ".defaultMinSize(minHeight = 48.dp)")
        assertFalse(
            source.contains(".defaultMinSize(minHeight = 32.dp)"),
            "Interactive tiny toggles must not regress below the Android 48dp touch target.",
        )
        assertFalse(
            source.contains(".height(44.dp)"),
            "Segmented controls must not regress below the Android 48dp touch target.",
        )
    }

    @Test
    fun `status chips wrap instead of overflowing on narrow or large font layouts`() {
        val source = echoAppSource()

        assertContains(source, "import androidx.compose.foundation.layout.FlowRow")
        assertContains(source, "private fun StatusChipGroup")
        assertContains(source, "verticalArrangement = Arrangement.spacedBy(8.dp)")
        assertContains(source, "StatusChipGroup {")
    }

    @Test
    fun `dangerous body and token actions require explicit confirmation`() {
        val source = echoAppSource()

        assertContains(source, "var confirmTokenClear by rememberSaveable")
        assertContains(source, "token_clear_confirm_title")
        assertContains(source, "token_clear_confirm_action")
        assertContains(source, "var confirmDisconnect by rememberSaveable")
        assertContains(source, "confirmationVisible = confirmTokenClear")
        assertContains(source, "confirmationVisible = confirmDisconnect")
        assertContains(source, "Dialog(")
        assertContains(source, "onDismissRequest = onCancel")
        assertContains(source, "disconnect_confirm_title")
        assertContains(source, "disconnect_confirm_action")
        assertFalse(
            source.contains("onClick = onDisconnect"),
            "Disconnect must not be wired directly to the button without confirmation.",
        )
    }

    @Test
    fun `action buttons cannot silently fall back to no-op click handlers`() {
        val source = echoAppSource()

        assertFalse(
            source.contains("onClick: () -> Unit = {}"),
            "ActionButton must require an explicit click handler so production UI cannot grow no-op controls silently.",
        )
        assertFalse(
            source.contains("onClick = { }") || source.contains("onClick = {}"),
            "Visible controls must not use empty click handlers.",
        )
    }

    @Test
    fun `app update buttons expose localized disabled reasons`() {
        val source = echoAppSource()

        assertContains(source, "disabledReason = stringResource(R.string.update_check_disabled_busy)")
        assertContains(source, "disabledReason = updateInstallDisabledReason(state)")
        assertContains(source, "private fun updateInstallDisabledReason")
        assertContains(source, "update_install_disabled_no_update")
        assertContains(source, "update_install_disabled_busy")
    }

    @Test
    fun `accepted stop capture immediately refreshes the session ledger`() {
        val source = echoAppSource()

        assertContains(source, "suspend fun refreshSessionLedger")
        assertContains(
            source,
            "result is CaptureCommandResult.Accepted || result is CaptureCommandResult.NoActiveSession",
        )
        assertContains(source, "refreshSessionLedger(activeConnection, generation)")
    }

    @Test
    fun `network screen uses real v4 endpoints instead of contract-missing placeholders`() {
        val uiSource = echoAppSource()
        val clientSource = File("src/main/java/com/openaria/openaria_echo_mobile/body/api/DeviceHttpClient.kt").readText()

        assertContains(uiSource, "NetworkAuthorityBlock")
        assertContains(uiSource, "NetworkMutationPanel")
        assertContains(uiSource, "confirmForget")
        assertContains(uiSource, "network_forget_confirm_title")
        assertContains(uiSource, "selectedNetworkMode")
        assertContains(uiSource, "network_mode_hotspot")
        assertContains(uiSource, "network_mode_ethernet_dhcp")
        assertContains(uiSource, "network_mode_ethernet_static")
        assertContains(uiSource, "network_static_address_label")
        assertContains(uiSource, "val captureIdle = captureState == \"idle\"")
        assertContains(uiSource, "network_disabled_capture_status_missing")
        assertFalse(
            uiSource.contains("MutationResultPending") || uiSource.contains("pendingNetworkMutationMessage"),
            "A disconnected request must be a terminal client error, not a pending mutation state.",
        )
        assertFalse(
            uiSource.contains("network_recovery_reconnect_target_lan") ||
                uiSource.contains("network_recovery_reconnect_rescue_ap"),
            "Wire recovery values must not become reconnect instructions in the product UI.",
        )
        assertContains(uiSource, "NetworkMessage.NetworkFailure(result.message)")
        assertContains(uiSource, "NetworkMessage.NetworkFailure(eventResult.message)")
        assertContains(uiSource, "var passphrase by remember(connectionGeneration)")
        assertFalse(
            uiSource.contains("var passphrase by rememberSaveable"),
            "Wi-Fi passphrase must not be stored in saved instance state.",
        )
        assertContains(clientSource, "/api/v4/network")
        assertContains(clientSource, "/api/v4/network/scan")
        assertContains(clientSource, "/api/v4/network/credentials")
        assertContains(clientSource, "/api/v4/network/apply")
        assertContains(clientSource, "/api/v4/network/retry")
        assertContains(clientSource, "/api/v4/network/forget")
        assertContains(clientSource, "/api/v4/network/events")
        assertContains(clientSource, "credential_ref")
        assertContains(clientSource, "applyHotspotNetwork")
        assertContains(clientSource, "applyEthernetDhcpNetwork")
        assertContains(clientSource, "applyEthernetStaticNetwork")
        assertFalse(
            uiSource.contains("status_contract_missing") && uiSource.contains("network_no_fake"),
            "Network tab must not regress to the old contract-missing placeholder.",
        )
    }

    @Test
    fun `body screen gates real calibration capture by descriptor capability`() {
        val uiSource = echoAppSource()
        val clientSource = File("src/main/java/com/openaria/openaria_echo_mobile/body/api/DeviceHttpClient.kt").readText()

        assertContains(uiSource, "startCalibrationCapture")
        assertContains(uiSource, "isCameraConnected(bodyConnection, captureStatus)")
        assertContains(uiSource, "calibrationCapture.enabled")
        assertContains(uiSource, "calibrationStartDisabledReason")
        assertContains(uiSource, "capture_disabled_camera")
        assertContains(clientSource, "startCalibrationCapture")
        assertContains(clientSource, "\"mode\":${'$'}{jsonString(mode)}")
        assertContains(clientSource, "calibration")
    }

    @Test
    fun `current product surface excludes retired removable storage workflow`() {
        val uiSource = echoAppSource()
        val resources = listOf(
            File("src/main/res/values/strings.xml"),
            File("src/main/res/values-en/strings.xml"),
        ).joinToString("\n") { it.readText() }

        assertFalse(uiSource.contains("SafeSwap") || uiSource.contains("safeSwap") || uiSource.contains("safe_swap"))
        assertFalse(resources.contains("safe_swap") || resources.contains("safe-swap") || resources.contains("安全换盘"))
        assertContains(uiSource, "deviceClient.stopCapture(activeConnection, idempotencyKey)")
        assertContains(uiSource, "refreshSessionLedger(activeConnection, generation)")
    }

    @Test
    fun `connection coordinator budgets requests and stops hidden preview`() {
        val uiSource = echoAppSource()

        assertContains(uiSource, "LifecycleEventObserver")
        assertContains(uiSource, "Lifecycle.Event.ON_RESUME")
        assertContains(uiSource, "Lifecycle.Event.ON_PAUSE")
        assertContains(uiSource, "appInForeground")
        assertContains(uiSource, "connectionGeneration")
        assertContains(uiSource, "captureStatusRequestGeneration")
        assertContains(uiSource, "ReconciliationGate(ConnectionRequestPolicy.HEALTHY_RECONCILIATION_INTERVAL_MS)")
        assertContains(uiSource, "selectedTab != EchoTab.VIEWFINDER")
        assertContains(uiSource, "ConnectionRequestPolicy.PREVIEW_INTERVAL_MS")
        assertContains(uiSource, "ConnectionRequestPolicy.canApplyResponse")
        assertFalse(uiSource.contains("foregroundResumeTick"))
        assertFalse(uiSource.contains("delay(5_000L)"))
    }

    @Test
    fun `preview keeps stale frames from being presented as live`() {
        val uiSource = echoAppSource()
        val clientSource = File("src/main/java/com/openaria/openaria_echo_mobile/body/api/DeviceHttpClient.kt").readText()

        assertContains(clientSource, "YLX-Error-Code")
        assertContains(clientSource, "validateErrorResponse")
        assertContains(clientSource, "PreviewResult.CameraNotConnected")
        assertContains(uiSource, "PreviewMessage.CameraNotConnected")
        assertContains(uiSource, "previewFrame = null")
        assertContains(uiSource, "showPreviewStatusOverlay = previewFrame == null || previewMessage != PreviewMessage.Live")
        assertContains(uiSource, "preview_camera_not_connected_body")
    }

    @Test
    fun `preview exposes real imu overlay only when runtime quality exists`() {
        val uiSource = echoAppSource()

        assertContains(uiSource, "var showPreviewImuOverlay by rememberSaveable")
        assertContains(uiSource, "captureStatus?.runtime?.liveImuQuality ?: bodyConnection?.descriptor?.runtime?.liveImuQuality")
        assertContains(uiSource, "canShowImuOverlay = liveImuQuality != null")
        assertContains(uiSource, "ImuOverlay(")
        assertContains(uiSource, "quality = liveImuQuality")
        assertContains(uiSource, "imu_overlay_quality")
        assertContains(uiSource, "imu_overlay_no_sample")
        assertContains(uiSource, "onShowImuOverlayChange(false)")
        assertContains(uiSource, "label = stringResource(R.string.imu_overlay)")
        assertContains(uiSource, "selected = showImuOverlay")
        assertContains(uiSource, "label = stringResource(R.string.focus_peaking)")
        assertContains(uiSource, "selected = showFocusPeaking")
    }

    @Test
    fun `focus peaking uses a bounded conflated generation fenced pipeline`() {
        val uiSource = echoAppSource()
        val focusSource = File("src/main/java/com/openaria/openaria_echo_mobile/ui/FocusPeaking.kt").readText()

        assertContains(uiSource, "Channel<PreviewFrameWork>(Channel.CONFLATED)")
        assertContains(uiSource, "Dispatchers.Default.limitedParallelism(1)")
        assertContains(uiSource, "previewFrameGate.shouldPublish(work.ticket)")
        assertContains(uiSource, "EchoColors.Peak.toArgb()")
        assertContains(uiSource, "previewFrame = previewFrame?.copy(focusMask = null)")
        assertContains(focusSource, "FOCUS_PROCESSING_PIXEL_BUDGET = 512 * 1024")
        assertContains(focusSource, "PREVIEW_JPEG_BYTE_LIMIT = 8 * 1024 * 1024")
        assertContains(focusSource, "inJustDecodeBounds = true")
        assertContains(focusSource, "horizontal + vertical >= threshold")
        assertTrue(
            uiSource.indexOf("PreviewImage(previewFrame.image, previewMode)") <
                uiSource.indexOf("FocusPeakOverlay(previewFrame.focusMask, previewMode)"),
        )
        assertTrue(
            uiSource.indexOf("FocusPeakOverlay(previewFrame.focusMask, previewMode)") <
                uiSource.indexOf("if (showGrid)"),
        )
        assertFalse(uiSource.contains("ToolStatus(stringResource(R.string.focus_peaking), false)"))
    }

    @Test
    fun `back and accessibility semantics use ordered policy and native roles`() {
        val uiSource = echoAppSource()
        val backSource = File("src/main/java/com/openaria/openaria_echo_mobile/ui/BackNavigationPolicy.kt").readText()

        assertContains(backSource, "state.confirmationVisible -> BackNavigationAction.DISMISS_CONFIRMATION")
        assertContains(backSource, "state.sessionDetailVisible -> BackNavigationAction.CLOSE_SESSION_DETAIL")
        assertContains(backSource, "state.sessionOutcomeVisible -> BackNavigationAction.CLOSE_SESSION_OUTCOME")
        assertContains(backSource, "state.temporaryPanelVisible -> BackNavigationAction.CLOSE_TEMPORARY_PANEL")
        assertContains(backSource, "!state.selectedTabIsViewfinder -> BackNavigationAction.RETURN_TO_VIEWFINDER")
        assertContains(backSource, "state.recording -> BackNavigationAction.REQUEST_RECORDING_BACKGROUND_CONFIRMATION")
        assertContains(backSource, "else -> BackNavigationAction.DEFAULT_SYSTEM_EXIT")
        assertContains(uiSource, "detailGeneration != sessionDetailGeneration")
        assertContains(uiSource, "outcomeGeneration != sessionOutcomeGeneration")
        assertContains(uiSource, "role = Role.Switch")
        assertContains(uiSource, ".selectableGroup()")
        assertContains(uiSource, "role = Role.RadioButton")
        assertContains(uiSource, "paneTitle = title")
        assertContains(uiSource, ".semantics { heading() }")
        assertContains(uiSource, "contentDescription = label")
        assertContains(uiSource, "context.findActivity()?.moveTaskToBack(true)")
    }

    @Test
    fun `session ledger has local all available unsuccessful filters`() {
        val uiSource = echoAppSource()

        assertContains(uiSource, "var sessionFilter by rememberSaveable(bodyConnection?.origin)")
        assertContains(uiSource, "SESSION_FILTER_ALL")
        assertContains(uiSource, "SESSION_FILTER_AVAILABLE")
        assertContains(uiSource, "SESSION_FILTER_UNSUCCESSFUL")
        assertContains(uiSource, "sessions_filter_all")
        assertContains(uiSource, "sessions_filter_available")
        assertContains(uiSource, "sessions_filter_unsuccessful")
        assertContains(uiSource, "visibleSessionItems = page.items.filter { sessionMatchesFilter(it, sessionFilter) }")
        assertContains(uiSource, "summary.verificationVerdict == \"usable\"")
        assertContains(uiSource, "summary.producerOutcome != \"sealed\" || summary.verificationVerdict == \"unusable\"")
        assertContains(uiSource, "sessions_filter_empty_title")
    }

    @Test
    fun `connection panel uses real nsd discovery for ylx capture service`() {
        val uiSource = echoAppSource()
        val discoverySource =
            File("src/main/java/com/openaria/openaria_echo_mobile/body/discovery/DeviceDiscoveryClient.kt").readText()

        assertContains(uiSource, "NearbyBodiesBlock")
        assertContains(uiSource, "DeviceDiscoveryClient")
        assertContains(discoverySource, "NsdManager")
        assertContains(discoverySource, "_ylx-capture._tcp.")
        assertContains(discoverySource, "discoverServices")
        assertContains(discoverySource, "resolveService")
        assertFalse(
            uiSource.contains("R.string.scan_unavailable"),
            "Connection panel must not render the old mDNS-unavailable placeholder.",
        )
    }

    private fun echoAppSource(): String {
        return File("src/main/java/com/openaria/openaria_echo_mobile/ui/EchoApp.kt").readText()
    }
}
