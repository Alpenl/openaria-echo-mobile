package com.openaria.openaria_echo_mobile.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openaria.openaria_echo_mobile.AppUpdateManager
import com.openaria.openaria_echo_mobile.R
import com.openaria.openaria_echo_mobile.body.CaptureCommandKind
import com.openaria.openaria_echo_mobile.body.CaptureProjection
import com.openaria.openaria_echo_mobile.body.CaptureProjectionState
import com.openaria.openaria_echo_mobile.body.api.ArtifactDescriptor
import com.openaria.openaria_echo_mobile.body.api.ArtifactDownloadResult
import com.openaria.openaria_echo_mobile.body.api.ArtifactDownloadStore
import com.openaria.openaria_echo_mobile.body.api.ArtifactFileResult
import com.openaria.openaria_echo_mobile.body.api.CaptureCommandResult
import com.openaria.openaria_echo_mobile.body.api.CaptureEventsResult
import com.openaria.openaria_echo_mobile.body.api.CaptureStatusResult
import com.openaria.openaria_echo_mobile.body.api.CaptureStatusSnapshot
import com.openaria.openaria_echo_mobile.body.api.CalibrationCaptureCapability
import com.openaria.openaria_echo_mobile.body.api.CameraFocusResult
import com.openaria.openaria_echo_mobile.body.api.CameraFocusStatus
import com.openaria.openaria_echo_mobile.body.api.DeviceConnection
import com.openaria.openaria_echo_mobile.body.api.DeviceHttpClient
import com.openaria.openaria_echo_mobile.body.api.DeviceSessionManifest
import com.openaria.openaria_echo_mobile.body.api.DeviceProbeClient
import com.openaria.openaria_echo_mobile.body.api.DeviceRuntime
import com.openaria.openaria_echo_mobile.body.api.NetworkDesiredState
import com.openaria.openaria_echo_mobile.body.api.NetworkInterfaceRuntime
import com.openaria.openaria_echo_mobile.body.api.NetworkCredentialResult
import com.openaria.openaria_echo_mobile.body.api.NetworkEventsResult
import com.openaria.openaria_echo_mobile.body.api.NetworkMutationResult
import com.openaria.openaria_echo_mobile.body.api.NetworkObservedState
import com.openaria.openaria_echo_mobile.body.api.NetworkScanEntry
import com.openaria.openaria_echo_mobile.body.api.NetworkScanResult
import com.openaria.openaria_echo_mobile.body.api.NetworkScanSnapshot
import com.openaria.openaria_echo_mobile.body.api.NetworkStatus
import com.openaria.openaria_echo_mobile.body.api.NetworkStatusResult
import com.openaria.openaria_echo_mobile.body.api.NetworkStreamEvent
import com.openaria.openaria_echo_mobile.body.api.NetworkTransaction
import com.openaria.openaria_echo_mobile.body.api.NetworkTransactionReceipt
import com.openaria.openaria_echo_mobile.body.api.PreviewResult
import com.openaria.openaria_echo_mobile.body.api.ProbeResult
import com.openaria.openaria_echo_mobile.body.api.RetainedUnsuccessfulOutcome
import com.openaria.openaria_echo_mobile.body.api.RetainedUnsuccessfulOutcomeResult
import com.openaria.openaria_echo_mobile.body.api.SafeSwapReceiptSummary
import com.openaria.openaria_echo_mobile.body.api.SafeSwapResult
import com.openaria.openaria_echo_mobile.body.api.SessionListPage
import com.openaria.openaria_echo_mobile.body.api.SessionListResult
import com.openaria.openaria_echo_mobile.body.api.SessionManifestResult
import com.openaria.openaria_echo_mobile.body.api.SessionSummary
import com.openaria.openaria_echo_mobile.body.discovery.DeviceDiscoveryClient
import com.openaria.openaria_echo_mobile.body.discovery.DeviceConnectionHistoryStore
import com.openaria.openaria_echo_mobile.body.discovery.DeviceHistoryEntry
import com.openaria.openaria_echo_mobile.body.discovery.DiscoveredBody
import com.openaria.openaria_echo_mobile.body.discovery.DiscoveryState
import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import com.openaria.openaria_echo_mobile.security.SecureTokenStore
import com.openaria.openaria_echo_mobile.ui.theme.EchoColors
import com.openaria.openaria_echo_mobile.ui.theme.EchoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun EchoApp(
    localeTag: String,
    updateState: AppUpdateManager.State,
    onLocaleChange: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    var selectedTabName by rememberSaveable { mutableStateOf(EchoTab.VIEWFINDER.name) }
    var bodyConnection by remember { mutableStateOf<DeviceConnection?>(null) }
    var captureProjection by remember { mutableStateOf(CaptureProjectionState()) }
    var captureStatus by remember { mutableStateOf<CaptureStatusSnapshot?>(null) }
    var captureMessage by remember { mutableStateOf<CaptureStatusMessage?>(null) }
    var captureCommandMessage by remember { mutableStateOf<CaptureCommandMessage?>(null) }
    var captureCommandRunning by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewMessage by remember { mutableStateOf<PreviewMessage?>(null) }
    var previewModeName by rememberSaveable { mutableStateOf(PreviewMode.BOTH.name) }
    var showPreviewGrid by rememberSaveable { mutableStateOf(true) }
    var showPreviewImuOverlay by rememberSaveable { mutableStateOf(false) }
    var sessionPage by remember { mutableStateOf<SessionListPage?>(null) }
    var sessionMessage by remember { mutableStateOf<SessionMessage?>(null) }
    var sessionLoadingMore by remember { mutableStateOf(false) }
    var sessionManifest by remember { mutableStateOf<DeviceSessionManifest?>(null) }
    var sessionManifestMessage by remember { mutableStateOf<SessionManifestMessage?>(null) }
    var sessionManifestLoading by remember { mutableStateOf(false) }
    var unsuccessfulOutcome by remember { mutableStateOf<RetainedUnsuccessfulOutcome?>(null) }
    var unsuccessfulOutcomeSessionId by remember { mutableStateOf<String?>(null) }
    var unsuccessfulOutcomeMessage by remember { mutableStateOf<UnsuccessfulOutcomeMessage?>(null) }
    var unsuccessfulOutcomeLoadingId by remember { mutableStateOf<String?>(null) }
    var artifactDownloadMessage by remember { mutableStateOf<ArtifactDownloadMessage?>(null) }
    var artifactDownloadingId by remember { mutableStateOf<String?>(null) }
    var cancelArtifactDownload by remember { mutableStateOf<(() -> Unit)?>(null) }
    var safeSwapReceipt by remember { mutableStateOf<SafeSwapReceiptSummary?>(null) }
    var safeSwapMessage by remember { mutableStateOf<SafeSwapMessage?>(null) }
    var cameraFocus by remember { mutableStateOf<CameraFocusStatus?>(null) }
    var cameraFocusMessage by remember { mutableStateOf<CameraFocusMessage?>(null) }
    var cameraFocusCommandRunning by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deviceClient = remember { DeviceHttpClient() }
    val artifactStore = remember(context) { ArtifactDownloadStore(context) }
    val scope = rememberCoroutineScope()
    val selectedTab = EchoTab.valueOf(selectedTabName)
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
    val bottomNavigationReserve = safeDrawing.calculateBottomPadding() + 86.dp
    var foregroundResumeTick by remember { mutableStateOf(0L) }

    fun applyCaptureProjectionState(nextState: CaptureProjectionState) {
        captureProjection = nextState
        captureStatus = nextState.snapshot
        safeSwapReceipt = nextState.safeSwapReceipt
    }

    suspend fun refreshSessionLedger(activeConnection: DeviceConnection) {
        val result = withContext(Dispatchers.IO) {
            deviceClient.listSessions(activeConnection, limit = 50)
        }
        when (result) {
            is SessionListResult.Page -> {
                sessionPage = mergeSessionRefresh(sessionPage, result.value)
                sessionMessage = null
            }
            SessionListResult.AuthenticationRequired -> sessionMessage = SessionMessage.AuthRequired
            SessionListResult.Forbidden -> sessionMessage = SessionMessage.Forbidden
            is SessionListResult.HttpFailure -> sessionMessage = SessionMessage.HttpFailure(result.statusCode)
            SessionListResult.InvalidRequest -> sessionMessage = SessionMessage.InvalidRequest
            is SessionListResult.InvalidResponse -> sessionMessage = SessionMessage.InvalidResponse(result.message)
            is SessionListResult.NetworkFailure -> sessionMessage = SessionMessage.NetworkFailure(result.message)
        }
    }

    DisposableEffect(lifecycleOwner, bodyConnection?.origin) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && bodyConnection != null) {
                foregroundResumeTick += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = bodyConnection != null) {
        when {
            selectedTab != EchoTab.VIEWFINDER -> selectedTabName = EchoTab.VIEWFINDER.name
            captureStatus?.deviceState == "recording" -> {
                captureCommandMessage = CaptureCommandMessage.RecordingContinuesOnBack
            }
            else -> context.findActivity()?.moveTaskToBack(true)
        }
    }

    fun startCaptureWithMode(calibration: Boolean) {
        val activeConnection = bodyConnection
        if (activeConnection != null && !captureCommandRunning) {
            captureCommandRunning = true
            captureCommandMessage = if (calibration) {
                CaptureCommandMessage.RunningCalibrationStart
            } else {
                CaptureCommandMessage.RunningStart
            }
            val idempotencyKey = UUID.randomUUID().toString()
            applyCaptureProjectionState(
                CaptureProjection.markCommandSubmitting(
                    state = captureProjection,
                    kind = CaptureCommandKind.START,
                    idempotencyKey = idempotencyKey,
                ),
            )
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (calibration) {
                        deviceClient.startCalibrationCapture(activeConnection, idempotencyKey)
                    } else {
                        deviceClient.startCapture(activeConnection, idempotencyKey)
                    }
                }
                captureCommandRunning = false
                captureCommandMessage = captureCommandMessageFor(result)
                if (result is CaptureCommandResult.Accepted) {
                    val projected = CaptureProjection.applyHttpSnapshot(captureProjection, result.value)
                    applyCaptureProjectionState(projected.state)
                }
                if (result is CaptureCommandResult.Accepted || result is CaptureCommandResult.NoActiveSession) {
                    refreshSessionLedger(activeConnection)
                }
            }
        }
    }

    val startCapture: () -> Unit = {
        startCaptureWithMode(calibration = false)
    }

    val startCalibrationCapture: () -> Unit = {
        startCaptureWithMode(calibration = true)
    }

    val stopCapture: () -> Unit = {
        val activeConnection = bodyConnection
        if (activeConnection != null && !captureCommandRunning) {
            captureCommandRunning = true
            captureCommandMessage = CaptureCommandMessage.RunningStop
            val idempotencyKey = UUID.randomUUID().toString()
            applyCaptureProjectionState(
                CaptureProjection.markCommandSubmitting(
                    state = captureProjection,
                    kind = CaptureCommandKind.STOP,
                    idempotencyKey = idempotencyKey,
                ),
            )
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    deviceClient.stopCapture(activeConnection, idempotencyKey)
                }
                captureCommandRunning = false
                captureCommandMessage = captureCommandMessageFor(result)
                if (result is CaptureCommandResult.Accepted) {
                    val projected = CaptureProjection.applyHttpSnapshot(captureProjection, result.value)
                    applyCaptureProjectionState(projected.state)
                }
            }
        }
    }

    val requestSafeSwap: () -> Unit = {
        val activeConnection = bodyConnection
        if (activeConnection != null && !captureCommandRunning && captureStatus?.deviceState == "recording") {
            captureCommandRunning = true
            captureCommandMessage = CaptureCommandMessage.RunningSafeSwapStop
            safeSwapMessage = SafeSwapMessage.WaitingForReceipt
            val idempotencyKey = UUID.randomUUID().toString()
            applyCaptureProjectionState(
                CaptureProjection.markCommandSubmitting(
                    state = captureProjection,
                    kind = CaptureCommandKind.STOP,
                    idempotencyKey = idempotencyKey,
                ),
            )
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    deviceClient.stopCaptureForSafeSwap(activeConnection, idempotencyKey)
                }
                captureCommandRunning = false
                captureCommandMessage = captureCommandMessageFor(result)
                if (result is CaptureCommandResult.Accepted) {
                    val projected = CaptureProjection.applyHttpSnapshot(captureProjection, result.value)
                    applyCaptureProjectionState(projected.state)
                    when (val safeSwapResult = withContext(Dispatchers.IO) { deviceClient.getSafeSwapReceipt(activeConnection) }) {
                        is SafeSwapResult.Receipt -> {
                            applyCaptureProjectionState(
                                CaptureProjection.applySafeSwapReceipt(projected.state, safeSwapResult.value),
                            )
                            safeSwapMessage = null
                        }
                        SafeSwapResult.AuthenticationRequired -> safeSwapMessage = SafeSwapMessage.AuthRequired
                        SafeSwapResult.Forbidden -> safeSwapMessage = SafeSwapMessage.Forbidden
                        is SafeSwapResult.HttpFailure -> safeSwapMessage = SafeSwapMessage.HttpFailure(safeSwapResult.statusCode)
                        is SafeSwapResult.InvalidResponse -> safeSwapMessage = SafeSwapMessage.InvalidResponse(safeSwapResult.message)
                        is SafeSwapResult.NetworkFailure -> safeSwapMessage = SafeSwapMessage.NetworkFailure(safeSwapResult.message)
                        SafeSwapResult.NotFound -> {
                            applyCaptureProjectionState(CaptureProjection.clearSafeSwapReceipt(captureProjection))
                            safeSwapMessage = SafeSwapMessage.WaitingForReceipt
                        }
                    }
                } else {
                    safeSwapMessage = null
                }
                if (result is CaptureCommandResult.Accepted || result is CaptureCommandResult.NoActiveSession) {
                    refreshSessionLedger(activeConnection)
                }
            }
        }
    }

    val loadSessionManifest: (SessionSummary) -> Unit = { summary ->
        val activeConnection = bodyConnection
        if (activeConnection != null && !sessionManifestLoading) {
            sessionManifestLoading = true
            sessionManifest = null
            sessionManifestMessage = SessionManifestMessage.Loading
            artifactDownloadMessage = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    deviceClient.getSessionManifest(activeConnection, summary.sessionId)
                }
                sessionManifestLoading = false
                when (result) {
                    is SessionManifestResult.Manifest -> {
                        sessionManifest = result.value
                        sessionManifestMessage = null
                    }
                    SessionManifestResult.AuthenticationRequired -> sessionManifestMessage = SessionManifestMessage.AuthRequired
                    SessionManifestResult.Forbidden -> sessionManifestMessage = SessionManifestMessage.Forbidden
                    is SessionManifestResult.HttpFailure -> sessionManifestMessage = SessionManifestMessage.HttpFailure(result.statusCode)
                    is SessionManifestResult.InvalidRequest -> sessionManifestMessage = SessionManifestMessage.InvalidRequest(result.message)
                    is SessionManifestResult.InvalidResponse -> sessionManifestMessage = SessionManifestMessage.InvalidResponse(result.message)
                    is SessionManifestResult.NetworkFailure -> sessionManifestMessage = SessionManifestMessage.NetworkFailure(result.message)
                    SessionManifestResult.NotFound -> sessionManifestMessage = SessionManifestMessage.NotFound
                }
            }
        }
    }

    val loadUnsuccessfulOutcome: (SessionSummary) -> Unit = { summary ->
        val activeConnection = bodyConnection
        if (activeConnection != null && unsuccessfulOutcomeLoadingId == null) {
            unsuccessfulOutcomeSessionId = summary.sessionId
            unsuccessfulOutcome = null
            unsuccessfulOutcomeMessage = UnsuccessfulOutcomeMessage.Loading
            unsuccessfulOutcomeLoadingId = summary.sessionId
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    deviceClient.getRetainedUnsuccessfulOutcome(activeConnection, summary.sessionId)
                }
                unsuccessfulOutcomeLoadingId = null
                when (result) {
                    is RetainedUnsuccessfulOutcomeResult.Outcome -> {
                        unsuccessfulOutcome = result.value
                        unsuccessfulOutcomeMessage = null
                    }
                    RetainedUnsuccessfulOutcomeResult.AuthenticationRequired -> {
                        unsuccessfulOutcomeMessage = UnsuccessfulOutcomeMessage.AuthRequired
                    }
                    RetainedUnsuccessfulOutcomeResult.Forbidden -> {
                        unsuccessfulOutcomeMessage = UnsuccessfulOutcomeMessage.Forbidden
                    }
                    is RetainedUnsuccessfulOutcomeResult.HttpFailure -> {
                        unsuccessfulOutcomeMessage = UnsuccessfulOutcomeMessage.HttpFailure(result.statusCode)
                    }
                    is RetainedUnsuccessfulOutcomeResult.InvalidRequest -> {
                        unsuccessfulOutcomeMessage = UnsuccessfulOutcomeMessage.InvalidRequest(result.message)
                    }
                    is RetainedUnsuccessfulOutcomeResult.InvalidResponse -> {
                        unsuccessfulOutcomeMessage = UnsuccessfulOutcomeMessage.InvalidResponse(result.message)
                    }
                    is RetainedUnsuccessfulOutcomeResult.NetworkFailure -> {
                        unsuccessfulOutcomeMessage = UnsuccessfulOutcomeMessage.NetworkFailure(result.message)
                    }
                    RetainedUnsuccessfulOutcomeResult.NotFound -> {
                        unsuccessfulOutcomeMessage = UnsuccessfulOutcomeMessage.NotFound
                    }
                }
            }
        }
    }

    val loadMoreSessions: () -> Unit = {
        val activeConnection = bodyConnection
        val currentPage = sessionPage
        val cursor = currentPage?.nextCursor
        if (activeConnection != null && currentPage != null && cursor != null && !sessionLoadingMore) {
            sessionLoadingMore = true
            sessionMessage = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    deviceClient.listSessions(
                        connection = activeConnection,
                        limit = 50,
                        cursor = cursor,
                    )
                }
                sessionLoadingMore = false
                when (result) {
                    is SessionListResult.Page -> {
                        sessionPage = appendSessionPage(currentPage, result.value)
                        sessionMessage = null
                    }
                    else -> sessionMessage = sessionMessageFor(result)
                }
            }
        }
    }

    val downloadArtifact: (ArtifactDescriptor) -> Unit = { artifact ->
        val activeConnection = bodyConnection
        val activeManifest = sessionManifest
        if (activeConnection != null && activeManifest != null && artifactDownloadingId == null) {
            val cancelFlag = AtomicBoolean(false)
            artifactDownloadingId = artifact.artifactId
            cancelArtifactDownload = { cancelFlag.set(true) }
            artifactDownloadMessage = ArtifactDownloadMessage.Running(artifact.role)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    artifactStore.download(
                        client = deviceClient,
                        connection = activeConnection,
                        sessionId = activeManifest.sessionId,
                        artifact = artifact,
                        shouldCancel = { cancelFlag.get() },
                    )
                }
                artifactDownloadingId = null
                cancelArtifactDownload = null
                artifactDownloadMessage = artifactDownloadMessageFor(artifact.role, result)
            }
        }
    }

    val setCameraFocus: (Long?, Boolean?) -> Unit = { value, autoEnabled ->
        val activeConnection = bodyConnection
        if (activeConnection != null && !cameraFocusCommandRunning) {
            cameraFocusCommandRunning = true
            cameraFocusMessage = CameraFocusMessage.Running
            val idempotencyKey = UUID.randomUUID().toString()
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    deviceClient.setCameraFocus(
                        connection = activeConnection,
                        idempotencyKey = idempotencyKey,
                        value = value,
                        autoEnabled = autoEnabled,
                    )
                }
                cameraFocusCommandRunning = false
                when (result) {
                    is CameraFocusResult.Status -> {
                        cameraFocus = result.value
                        cameraFocusMessage = CameraFocusMessage.Updated
                    }
                    CameraFocusResult.AuthenticationRequired -> cameraFocusMessage = CameraFocusMessage.AuthRequired
                    CameraFocusResult.Conflict -> cameraFocusMessage = CameraFocusMessage.Conflict
                    CameraFocusResult.Forbidden -> cameraFocusMessage = CameraFocusMessage.Forbidden
                    is CameraFocusResult.HttpFailure -> cameraFocusMessage = CameraFocusMessage.HttpFailure(result.statusCode)
                    CameraFocusResult.InvalidFocus -> cameraFocusMessage = CameraFocusMessage.InvalidFocus
                    is CameraFocusResult.InvalidRequest -> cameraFocusMessage = CameraFocusMessage.InvalidRequest(result.message)
                    is CameraFocusResult.InvalidResponse -> cameraFocusMessage = CameraFocusMessage.InvalidResponse(result.message)
                    is CameraFocusResult.NetworkFailure -> cameraFocusMessage = CameraFocusMessage.NetworkFailure(result.message)
                    CameraFocusResult.Unsupported -> cameraFocusMessage = CameraFocusMessage.Unsupported
                }
            }
        }
    }

    LaunchedEffect(bodyConnection, foregroundResumeTick) {
        val activeConnection = bodyConnection ?: return@LaunchedEffect
        if (foregroundResumeTick == 0L) {
            return@LaunchedEffect
        }

        when (val statusResult = withContext(Dispatchers.IO) { deviceClient.getCaptureStatus(activeConnection) }) {
            is CaptureStatusResult.Snapshot -> {
                val projected = CaptureProjection.applyHttpSnapshot(captureProjection, statusResult.value)
                applyCaptureProjectionState(projected.state)
                captureMessage = null
            }
            CaptureStatusResult.AuthenticationRequired -> captureMessage = CaptureStatusMessage.AuthRequired
            CaptureStatusResult.Forbidden -> captureMessage = CaptureStatusMessage.Forbidden
            is CaptureStatusResult.HttpFailure -> captureMessage = CaptureStatusMessage.HttpFailure(statusResult.statusCode)
            is CaptureStatusResult.InvalidResponse -> captureMessage = CaptureStatusMessage.InvalidResponse(statusResult.message)
            is CaptureStatusResult.NetworkFailure -> captureMessage = CaptureStatusMessage.NetworkFailure(statusResult.message)
        }

        refreshSessionLedger(activeConnection)

        when (val safeSwapResult = withContext(Dispatchers.IO) { deviceClient.getSafeSwapReceipt(activeConnection) }) {
            is SafeSwapResult.Receipt -> {
                applyCaptureProjectionState(
                    CaptureProjection.applySafeSwapReceipt(captureProjection, safeSwapResult.value),
                )
                safeSwapMessage = null
            }
            SafeSwapResult.AuthenticationRequired -> safeSwapMessage = SafeSwapMessage.AuthRequired
            SafeSwapResult.Forbidden -> safeSwapMessage = SafeSwapMessage.Forbidden
            is SafeSwapResult.HttpFailure -> safeSwapMessage = SafeSwapMessage.HttpFailure(safeSwapResult.statusCode)
            is SafeSwapResult.InvalidResponse -> safeSwapMessage = SafeSwapMessage.InvalidResponse(safeSwapResult.message)
            is SafeSwapResult.NetworkFailure -> safeSwapMessage = SafeSwapMessage.NetworkFailure(safeSwapResult.message)
            SafeSwapResult.NotFound -> {
                applyCaptureProjectionState(CaptureProjection.clearSafeSwapReceipt(captureProjection))
                safeSwapMessage = SafeSwapMessage.NotFound
            }
        }

        when (val focusResult = withContext(Dispatchers.IO) { deviceClient.getCameraFocus(activeConnection) }) {
            is CameraFocusResult.Status -> {
                cameraFocus = focusResult.value
                if (cameraFocusMessage !in setOf(CameraFocusMessage.Running, CameraFocusMessage.Updated)) {
                    cameraFocusMessage = null
                }
            }
            CameraFocusResult.AuthenticationRequired -> cameraFocusMessage = CameraFocusMessage.AuthRequired
            CameraFocusResult.Conflict -> cameraFocusMessage = CameraFocusMessage.Conflict
            CameraFocusResult.Forbidden -> cameraFocusMessage = CameraFocusMessage.Forbidden
            is CameraFocusResult.HttpFailure -> cameraFocusMessage = CameraFocusMessage.HttpFailure(focusResult.statusCode)
            CameraFocusResult.InvalidFocus -> cameraFocusMessage = CameraFocusMessage.InvalidFocus
            is CameraFocusResult.InvalidRequest -> cameraFocusMessage = CameraFocusMessage.InvalidRequest(focusResult.message)
            is CameraFocusResult.InvalidResponse -> cameraFocusMessage = CameraFocusMessage.InvalidResponse(focusResult.message)
            is CameraFocusResult.NetworkFailure -> cameraFocusMessage = CameraFocusMessage.NetworkFailure(focusResult.message)
            CameraFocusResult.Unsupported -> cameraFocusMessage = CameraFocusMessage.Unsupported
        }
    }

    LaunchedEffect(bodyConnection) {
        val activeConnection = bodyConnection
        captureProjection = CaptureProjectionState()
        captureStatus = null
        captureMessage = null
        captureCommandMessage = null
        captureCommandRunning = false
        previewBitmap = null
        previewMessage = if (activeConnection == null) null else PreviewMessage.Waiting
        if (activeConnection == null) {
            return@LaunchedEffect
        }

        while (isActive) {
            val statusResult = withContext(Dispatchers.IO) {
                deviceClient.getCaptureStatus(activeConnection)
            }
            when (statusResult) {
                is CaptureStatusResult.Snapshot -> {
                    val projected = CaptureProjection.applyHttpSnapshot(captureProjection, statusResult.value)
                    applyCaptureProjectionState(projected.state)
                    captureMessage = null
                }
                CaptureStatusResult.AuthenticationRequired -> captureMessage = CaptureStatusMessage.AuthRequired
                CaptureStatusResult.Forbidden -> captureMessage = CaptureStatusMessage.Forbidden
                is CaptureStatusResult.HttpFailure -> captureMessage = CaptureStatusMessage.HttpFailure(statusResult.statusCode)
                is CaptureStatusResult.InvalidResponse -> captureMessage = CaptureStatusMessage.InvalidResponse(statusResult.message)
                is CaptureStatusResult.NetworkFailure -> captureMessage = CaptureStatusMessage.NetworkFailure(statusResult.message)
            }

            if (activeConnection.descriptor.previewCapable) {
                val previewResult = withContext(Dispatchers.IO) {
                    deviceClient.getPreviewJpeg(activeConnection, fps = 2)
                }
                when (previewResult) {
                    is PreviewResult.Frame -> {
                        val decoded = withContext(Dispatchers.IO) {
                            decodePreviewFrame(previewResult.bytes)
                        }
                        previewMessage = if (decoded == null) {
                            previewBitmap = null
                            PreviewMessage.DecodeFailed
                        } else {
                            previewBitmap = decoded
                            PreviewMessage.Live
                        }
                    }
                    PreviewResult.AuthenticationRequired -> previewMessage = PreviewMessage.AuthRequired
                    PreviewResult.Forbidden -> previewMessage = PreviewMessage.Forbidden
                    PreviewResult.CameraNotConnected -> {
                        previewBitmap = null
                        previewMessage = PreviewMessage.CameraNotConnected
                    }
                    is PreviewResult.HttpFailure -> previewMessage = PreviewMessage.HttpFailure(previewResult.statusCode)
                    is PreviewResult.InvalidResponse -> previewMessage = PreviewMessage.InvalidResponse(previewResult.message)
                    is PreviewResult.NetworkFailure -> previewMessage = PreviewMessage.NetworkFailure(previewResult.message)
                    PreviewResult.NoFrame -> previewMessage = PreviewMessage.NoFrame
                    PreviewResult.Unavailable -> previewMessage = PreviewMessage.Unavailable
                }
            } else {
                previewBitmap = null
                previewMessage = PreviewMessage.Unavailable
            }

            delay(1_000L)
        }
    }

    LaunchedEffect(bodyConnection) {
        val activeConnection = bodyConnection ?: return@LaunchedEffect

        while (isActive) {
            val eventResult = withContext(Dispatchers.IO) {
                deviceClient.readCaptureEvents(
                    connection = activeConnection,
                    lastEventId = captureProjection.lastEventId,
                    lastAuthorityEpoch = captureProjection.lastAuthorityEpoch,
                    lastSourceRevision = captureProjection.lastSourceRevision,
                    maxEvents = 8,
                )
            }

            when (eventResult) {
                is CaptureEventsResult.Batch -> {
                    var needsCaptureReconciliation = false
                    var needsSafeSwapReconciliation = false
                    eventResult.events.forEach { event ->
                        val projected = CaptureProjection.applyStreamEvent(captureProjection, event)
                        applyCaptureProjectionState(projected.state)
                        if (projected.clearedEpochBoundState) {
                            captureCommandRunning = false
                            captureCommandMessage = null
                        }
                        if (projected.accepted) {
                            captureMessage = null
                            if (event.safeSwapReceipt != null) {
                                safeSwapMessage = null
                            }
                        }
                        needsCaptureReconciliation = needsCaptureReconciliation ||
                            projected.requiresCaptureReconciliation
                        needsSafeSwapReconciliation = needsSafeSwapReconciliation ||
                            projected.requiresSafeSwapReconciliation
                    }

                    if (needsCaptureReconciliation) {
                        when (val statusResult = withContext(Dispatchers.IO) { deviceClient.getCaptureStatus(activeConnection) }) {
                            is CaptureStatusResult.Snapshot -> {
                                val projected = CaptureProjection.applyHttpSnapshot(captureProjection, statusResult.value)
                                applyCaptureProjectionState(projected.state)
                                captureMessage = null
                            }
                            CaptureStatusResult.AuthenticationRequired -> captureMessage = CaptureStatusMessage.AuthRequired
                            CaptureStatusResult.Forbidden -> captureMessage = CaptureStatusMessage.Forbidden
                            is CaptureStatusResult.HttpFailure -> captureMessage = CaptureStatusMessage.HttpFailure(statusResult.statusCode)
                            is CaptureStatusResult.InvalidResponse -> captureMessage = CaptureStatusMessage.InvalidResponse(statusResult.message)
                            is CaptureStatusResult.NetworkFailure -> captureMessage = CaptureStatusMessage.NetworkFailure(statusResult.message)
                        }
                    }

                    if (needsSafeSwapReconciliation) {
                        when (val safeSwapResult = withContext(Dispatchers.IO) { deviceClient.getSafeSwapReceipt(activeConnection) }) {
                            is SafeSwapResult.Receipt -> {
                                applyCaptureProjectionState(
                                    CaptureProjection.applySafeSwapReceipt(captureProjection, safeSwapResult.value),
                                )
                                safeSwapMessage = null
                            }
                            SafeSwapResult.AuthenticationRequired -> safeSwapMessage = SafeSwapMessage.AuthRequired
                            SafeSwapResult.Forbidden -> safeSwapMessage = SafeSwapMessage.Forbidden
                            is SafeSwapResult.HttpFailure -> safeSwapMessage = SafeSwapMessage.HttpFailure(safeSwapResult.statusCode)
                            is SafeSwapResult.InvalidResponse -> safeSwapMessage = SafeSwapMessage.InvalidResponse(safeSwapResult.message)
                            is SafeSwapResult.NetworkFailure -> safeSwapMessage = SafeSwapMessage.NetworkFailure(safeSwapResult.message)
                            SafeSwapResult.NotFound -> {
                                applyCaptureProjectionState(CaptureProjection.clearSafeSwapReceipt(captureProjection))
                                safeSwapMessage = SafeSwapMessage.NotFound
                            }
                        }
                    }

                    delay(if (eventResult.events.isEmpty()) 1_000L else 250L)
                }
                CaptureEventsResult.AuthenticationRequired -> {
                    captureMessage = CaptureStatusMessage.AuthRequired
                    delay(2_000L)
                }
                CaptureEventsResult.Forbidden -> {
                    captureMessage = CaptureStatusMessage.Forbidden
                    delay(2_000L)
                }
                is CaptureEventsResult.HttpFailure -> {
                    captureMessage = CaptureStatusMessage.HttpFailure(eventResult.statusCode)
                    delay(2_000L)
                }
                is CaptureEventsResult.InvalidRequest -> {
                    captureMessage = CaptureStatusMessage.InvalidResponse(eventResult.message)
                    delay(2_000L)
                }
                is CaptureEventsResult.InvalidResponse -> {
                    captureMessage = CaptureStatusMessage.InvalidResponse(eventResult.message)
                    delay(2_000L)
                }
                is CaptureEventsResult.NetworkFailure -> {
                    captureMessage = CaptureStatusMessage.NetworkFailure(eventResult.message)
                    delay(2_000L)
                }
            }
        }
    }

    LaunchedEffect(bodyConnection) {
        val activeConnection = bodyConnection
        sessionPage = null
        sessionMessage = null
        sessionLoadingMore = false
        sessionManifest = null
        sessionManifestMessage = null
        sessionManifestLoading = false
        unsuccessfulOutcome = null
        unsuccessfulOutcomeSessionId = null
        unsuccessfulOutcomeMessage = null
        unsuccessfulOutcomeLoadingId = null
        artifactDownloadMessage = null
        artifactDownloadingId = null
        cancelArtifactDownload = null
        safeSwapReceipt = null
        safeSwapMessage = null
        cameraFocus = null
        cameraFocusMessage = null
        cameraFocusCommandRunning = false
        if (activeConnection == null) {
            return@LaunchedEffect
        }

        while (isActive) {
            refreshSessionLedger(activeConnection)
            val safeSwapResult = withContext(Dispatchers.IO) {
                deviceClient.getSafeSwapReceipt(activeConnection)
            }
            when (safeSwapResult) {
                is SafeSwapResult.Receipt -> {
                    applyCaptureProjectionState(
                        CaptureProjection.applySafeSwapReceipt(captureProjection, safeSwapResult.value),
                    )
                    safeSwapMessage = null
                }
                SafeSwapResult.AuthenticationRequired -> safeSwapMessage = SafeSwapMessage.AuthRequired
                SafeSwapResult.Forbidden -> safeSwapMessage = SafeSwapMessage.Forbidden
                is SafeSwapResult.HttpFailure -> safeSwapMessage = SafeSwapMessage.HttpFailure(safeSwapResult.statusCode)
                is SafeSwapResult.InvalidResponse -> safeSwapMessage = SafeSwapMessage.InvalidResponse(safeSwapResult.message)
                is SafeSwapResult.NetworkFailure -> safeSwapMessage = SafeSwapMessage.NetworkFailure(safeSwapResult.message)
                SafeSwapResult.NotFound -> {
                    applyCaptureProjectionState(CaptureProjection.clearSafeSwapReceipt(captureProjection))
                    safeSwapMessage = SafeSwapMessage.NotFound
                }
            }
            val focusResult = withContext(Dispatchers.IO) {
                deviceClient.getCameraFocus(activeConnection)
            }
            when (focusResult) {
                is CameraFocusResult.Status -> {
                    cameraFocus = focusResult.value
                    if (cameraFocusMessage !in setOf(CameraFocusMessage.Running, CameraFocusMessage.Updated)) {
                        cameraFocusMessage = null
                    }
                }
                CameraFocusResult.AuthenticationRequired -> cameraFocusMessage = CameraFocusMessage.AuthRequired
                CameraFocusResult.Conflict -> cameraFocusMessage = CameraFocusMessage.Conflict
                CameraFocusResult.Forbidden -> cameraFocusMessage = CameraFocusMessage.Forbidden
                is CameraFocusResult.HttpFailure -> cameraFocusMessage = CameraFocusMessage.HttpFailure(focusResult.statusCode)
                CameraFocusResult.InvalidFocus -> cameraFocusMessage = CameraFocusMessage.InvalidFocus
                is CameraFocusResult.InvalidRequest -> cameraFocusMessage = CameraFocusMessage.InvalidRequest(focusResult.message)
                is CameraFocusResult.InvalidResponse -> cameraFocusMessage = CameraFocusMessage.InvalidResponse(focusResult.message)
                is CameraFocusResult.NetworkFailure -> cameraFocusMessage = CameraFocusMessage.NetworkFailure(focusResult.message)
                CameraFocusResult.Unsupported -> cameraFocusMessage = CameraFocusMessage.Unsupported
            }
            delay(5_000L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EchoColors.Void),
    ) {
        ApertureBackdrop(selectedTab)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 12.dp,
                    top = safeDrawing.calculateTopPadding() + 8.dp,
                    end = 12.dp,
                    bottom = bottomNavigationReserve,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TopStatus(bodyConnection)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (selectedTab) {
                    EchoTab.VIEWFINDER -> ViewfinderScreen(
                        bodyConnection = bodyConnection,
                        captureStatus = captureStatus,
                        captureMessage = captureMessage,
                        captureCommandMessage = captureCommandMessage,
                        captureCommandRunning = captureCommandRunning,
                        previewBitmap = previewBitmap,
                        previewMessage = previewMessage,
                        previewMode = PreviewMode.valueOf(previewModeName),
                        showGrid = showPreviewGrid,
                        showImuOverlay = showPreviewImuOverlay,
                        onStartCapture = startCapture,
                        onStopCapture = stopCapture,
                        onPreviewModeChange = { previewModeName = it.name },
                        onShowGridChange = { showPreviewGrid = it },
                        onShowImuOverlayChange = { showPreviewImuOverlay = it },
                        onConnected = { bodyConnection = it },
                    )
                    EchoTab.SESSIONS -> SessionsScreen(
                        bodyConnection = bodyConnection,
                        sessionPage = sessionPage,
                        sessionMessage = sessionMessage,
                        sessionLoadingMore = sessionLoadingMore,
                        sessionManifest = sessionManifest,
                        sessionManifestMessage = sessionManifestMessage,
                        sessionManifestLoading = sessionManifestLoading,
                        unsuccessfulOutcome = unsuccessfulOutcome,
                        unsuccessfulOutcomeSessionId = unsuccessfulOutcomeSessionId,
                        unsuccessfulOutcomeMessage = unsuccessfulOutcomeMessage,
                        unsuccessfulOutcomeLoadingId = unsuccessfulOutcomeLoadingId,
                        artifactDownloadMessage = artifactDownloadMessage,
                        artifactDownloadingId = artifactDownloadingId,
                        onCancelDownload = { cancelArtifactDownload?.invoke() },
                        onLoadUnsuccessfulOutcome = loadUnsuccessfulOutcome,
                        onLoadMoreSessions = loadMoreSessions,
                        onLoadManifest = loadSessionManifest,
                        onDownloadArtifact = downloadArtifact,
                    )
                    EchoTab.BODY -> BodyScreen(
                        bodyConnection = bodyConnection,
                        captureStatus = captureStatus,
                        captureMessage = captureMessage,
                        captureCommandMessage = captureCommandMessage,
                        safeSwapReceipt = safeSwapReceipt,
                        safeSwapMessage = safeSwapMessage,
                        cameraFocus = cameraFocus,
                        cameraFocusMessage = cameraFocusMessage,
                        captureCommandRunning = captureCommandRunning,
                        cameraFocusCommandRunning = cameraFocusCommandRunning,
                        onStartCalibrationCapture = startCalibrationCapture,
                        onRequestSafeSwap = requestSafeSwap,
                        onSetCameraFocus = setCameraFocus,
                        localeTag = localeTag,
                        updateState = updateState,
                        onDisconnect = { bodyConnection = null },
                        onLocaleChange = onLocaleChange,
                        onCheckUpdate = onCheckUpdate,
                        onInstallUpdate = onInstallUpdate,
                    )
                    EchoTab.NETWORK -> NetworkScreen(
                        bodyConnection = bodyConnection,
                        captureStatus = captureStatus,
                        foregroundResumeTick = foregroundResumeTick,
                    )
                }
            }
        }
        BottomNavigation(
            selectedTab = selectedTab,
            onSelect = { selectedTabName = it.name },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun TopStatus(bodyConnection: DeviceConnection?) {
    val bodyLabel = bodyConnection?.descriptor?.deviceLabel ?: stringResource(R.string.screen_title)
    val status = if (bodyConnection == null) {
        stringResource(R.string.status_no_body)
    } else {
        stringResource(R.string.verified_connection)
    }
    val description = "$bodyLabel, $status"
    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = description
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 46.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(EchoColors.InkMuted)
            Spacer(Modifier.width(10.dp))
            EchoText(
                value = stringResource(R.string.screen_title),
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    letterSpacing = 0.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusChip(status, if (bodyConnection == null) EchoColors.InkMuted else EchoColors.Permit)
        }
    }
}

@Composable
private fun ViewfinderScreen(
    bodyConnection: DeviceConnection?,
    captureStatus: CaptureStatusSnapshot?,
    captureMessage: CaptureStatusMessage?,
    captureCommandMessage: CaptureCommandMessage?,
    captureCommandRunning: Boolean,
    previewBitmap: ImageBitmap?,
    previewMessage: PreviewMessage?,
    previewMode: PreviewMode,
    showGrid: Boolean,
    showImuOverlay: Boolean,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onPreviewModeChange: (PreviewMode) -> Unit,
    onShowGridChange: (Boolean) -> Unit,
    onShowImuOverlayChange: (Boolean) -> Unit,
    onConnected: (DeviceConnection) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreviewFrame(
            bodyConnection = bodyConnection,
            captureStatus = captureStatus,
            captureCommandRunning = captureCommandRunning,
            previewBitmap = previewBitmap,
            previewMessage = previewMessage,
            previewMode = previewMode,
            showGrid = showGrid,
            showImuOverlay = showImuOverlay,
            onStartCapture = onStartCapture,
            onStopCapture = onStopCapture,
            onPreviewModeChange = onPreviewModeChange,
            onShowGridChange = onShowGridChange,
            onShowImuOverlayChange = onShowImuOverlayChange,
        )
        CaptureStatusPanel(
            bodyConnection = bodyConnection,
            captureStatus = captureStatus,
            captureMessage = captureMessage,
            captureCommandMessage = captureCommandMessage,
        )
        if (bodyConnection == null) {
            ConnectionPanel(onConnected)
        }
        ContractGate()
    }
}

@Composable
private fun PreviewFrame(
    bodyConnection: DeviceConnection?,
    captureStatus: CaptureStatusSnapshot?,
    captureCommandRunning: Boolean,
    previewBitmap: ImageBitmap?,
    previewMessage: PreviewMessage?,
    previewMode: PreviewMode,
    showGrid: Boolean,
    showImuOverlay: Boolean,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onPreviewModeChange: (PreviewMode) -> Unit,
    onShowGridChange: (Boolean) -> Unit,
    onShowImuOverlayChange: (Boolean) -> Unit,
) {
    val canStart = bodyConnection != null &&
        !captureCommandRunning &&
        bodyConnection.descriptor.captureCapable &&
        isCameraConnected(bodyConnection, captureStatus) &&
        bodyConnection.descriptor.writable &&
        captureStatus?.deviceState == "idle"
    val canStop = bodyConnection != null &&
        !captureCommandRunning &&
        captureStatus?.deviceState == "recording"
    val showPreviewStatusOverlay = previewBitmap == null || previewMessage != PreviewMessage.Live
    val liveImuQuality = captureStatus?.runtime?.liveImuQuality ?: bodyConnection?.descriptor?.runtime?.liveImuQuality
    val canShowImuOverlay = liveImuQuality != null

    LaunchedEffect(liveImuQuality, showImuOverlay) {
        if (liveImuQuality == null && showImuOverlay) {
            onShowImuOverlayChange(false)
        }
    }

    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .height(286.dp),
        background = Color.Black.copy(alpha = 0.64f),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (previewBitmap != null) {
                PreviewImage(previewBitmap, previewMode)
            }
            if (showGrid) {
                PreviewGrid()
            }
            if (showImuOverlay && liveImuQuality != null) {
                ImuOverlay(
                    quality = liveImuQuality,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 58.dp, end = 10.dp),
                )
            }
            if (showPreviewStatusOverlay) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = if (previewBitmap == null) 0f else 0.58f))
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusChip(
                        previewStatusLabel(bodyConnection, previewMessage),
                        previewStatusColor(bodyConnection, previewMessage),
                    )
                    EchoText(
                        value = previewStatusBody(bodyConnection, previewMessage),
                        color = EchoColors.InkSecondary,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    )
                    EchoText(
                        value = stringResource(R.string.preview_contract_note),
                        color = EchoColors.InkMuted,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusChip(stringResource(R.string.preview_live), EchoColors.Permit)
                    captureStatus?.let {
                        StatusChip(deviceStateLabel(it.deviceState), deviceStateColor(it.deviceState))
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TinyToggle(
                    label = stringResource(R.string.view_both),
                    selected = previewMode == PreviewMode.BOTH,
                    onClick = { onPreviewModeChange(PreviewMode.BOTH) },
                )
                TinyToggle(
                    label = stringResource(R.string.view_left),
                    selected = previewMode == PreviewMode.LEFT,
                    onClick = { onPreviewModeChange(PreviewMode.LEFT) },
                )
                TinyToggle(
                    label = stringResource(R.string.view_right),
                    selected = previewMode == PreviewMode.RIGHT,
                    onClick = { onPreviewModeChange(PreviewMode.RIGHT) },
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FrameToolToggle(
                    label = stringResource(R.string.grid),
                    selected = showGrid,
                    onClick = { onShowGridChange(!showGrid) },
                )
                ToolStatus(stringResource(R.string.focus_peaking), false)
                if (canShowImuOverlay) {
                    FrameToolToggle(
                        label = stringResource(R.string.imu_overlay),
                        selected = showImuOverlay,
                        onClick = { onShowImuOverlayChange(!showImuOverlay) },
                    )
                } else {
                    ToolStatus(
                        label = stringResource(R.string.imu_overlay),
                        available = false,
                        unavailableReason = stringResource(R.string.imu_overlay_no_sample),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    label = stringResource(R.string.start_recording),
                    enabled = canStart,
                    disabledReason = startDisabledReason(bodyConnection, captureStatus, captureCommandRunning),
                    onClick = onStartCapture,
                    modifier = Modifier.size(width = 144.dp, height = 58.dp),
                )
                ActionButton(
                    label = stringResource(R.string.stop_recording),
                    enabled = canStop,
                    disabledReason = stopDisabledReason(bodyConnection, captureStatus, captureCommandRunning),
                    onClick = onStopCapture,
                    modifier = Modifier.size(width = 118.dp, height = 58.dp),
                )
            }
        }
    }
}

@Composable
private fun ImuOverlay(
    quality: String,
    modifier: Modifier = Modifier,
) {
    val accent = imuQualityColor(quality)
    Panel(
        modifier = modifier,
        background = Color.Black.copy(alpha = 0.58f),
        border = accent.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip(stringResource(R.string.imu_overlay), accent)
            EchoText(
                value = stringResource(R.string.imu_overlay_quality, imuQualityLabel(quality)),
                color = EchoColors.InkSecondary,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
            )
            EchoText(
                value = stringResource(R.string.imu_overlay_source),
                color = EchoColors.InkMuted,
                style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
            )
        }
    }
}

@Composable
private fun CaptureStatusPanel(
    bodyConnection: DeviceConnection?,
    captureStatus: CaptureStatusSnapshot?,
    captureMessage: CaptureStatusMessage?,
    captureCommandMessage: CaptureCommandMessage?,
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(stringResource(R.string.capture_status_title))
            when {
                captureCommandMessage != null -> CaptureCommandMessageBlock(captureCommandMessage)
                bodyConnection == null -> InfoBlock(
                    title = stringResource(R.string.status_no_body),
                    body = stringResource(R.string.body_not_ready),
                    accent = EchoColors.InkMuted,
                )
                captureStatus != null -> {
                    StatusChipGroup {
                        StatusChip(deviceStateLabel(captureStatus.deviceState), deviceStateColor(captureStatus.deviceState))
                        StatusChip(
                            stringResource(R.string.source_revision, captureStatus.sourceRevision),
                            EchoColors.InkSecondary,
                        )
                    }
                    InfoBlock(
                        title = stringResource(R.string.runtime_status),
                        body = runtimeLabel(captureStatus.runtime.observedAt, captureStatus.runtime.connectionMethod, captureStatus.runtime.temperatureCelsius),
                        accent = EchoColors.Live,
                    )
                }
                captureMessage != null -> CaptureStatusMessageBlock(captureMessage)
                else -> InfoBlock(
                    title = stringResource(R.string.capture_status_title),
                    body = stringResource(R.string.capture_polling),
                    accent = EchoColors.Live,
                )
            }
        }
    }
}

@Composable
private fun ConnectionPanel(onConnected: (DeviceConnection) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bodyOrigin by rememberSaveable { mutableStateOf("") }
    var bearerToken by remember { mutableStateOf("") }
    var endpointMessage by remember { mutableStateOf<EndpointMessage?>(null) }
    var probeMessage by remember { mutableStateOf<ProbeMessage?>(null) }
    var tokenMessage by remember { mutableStateOf<TokenMessage?>(null) }
    var confirmTokenClear by rememberSaveable { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var cancelProbe by remember { mutableStateOf<(() -> Unit)?>(null) }
    val probeClient = remember { DeviceProbeClient() }
    val discoveryClient = remember(context) { DeviceDiscoveryClient(context) }
    var discoveryState by remember { mutableStateOf<DiscoveryState>(DiscoveryState.Idle(emptyList())) }
    val tokenStore = remember(context) { SecureTokenStore(context) }
    val historyStore = remember(context) { DeviceConnectionHistoryStore(context) }
    var historyEntries by remember { mutableStateOf(historyStore.load()) }
    val hasStoredToken = tokenStore.hasTokenFor(bodyOrigin)

    BackHandler(enabled = confirmTokenClear) {
        confirmTokenClear = false
    }

    DisposableEffect(discoveryClient) {
        onDispose {
            discoveryClient.stop()
        }
    }

    Panel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.connection_title))
            NearbyBodiesBlock(
                discoveryState = discoveryState,
                onToggleDiscovery = {
                    if (discoveryState is DiscoveryState.Scanning) {
                        val bodies = discoveryState.bodies()
                        discoveryClient.stop()
                        discoveryState = DiscoveryState.Idle(bodies)
                    } else {
                        discoveryClient.start { nextState -> discoveryState = nextState }
                    }
                },
                onSelect = { body ->
                    bodyOrigin = body.origin
                    endpointMessage = endpointMessageFor(EndpointPolicy.validate(body.origin))
                    probeMessage = null
                    tokenMessage = null
                    confirmTokenClear = false
                    discoveryClient.stop()
                    discoveryState = DiscoveryState.Idle(discoveryState.bodies())
                },
            )
            ConnectionHistoryBlock(
                entries = historyEntries,
                onSelect = { entry ->
                    bodyOrigin = entry.origin
                    endpointMessage = endpointMessageFor(EndpointPolicy.validate(entry.origin))
                    probeMessage = null
                    tokenMessage = null
                    confirmTokenClear = false
                },
            )
            SectionLabel(stringResource(R.string.manual_connection))
            EchoText(
                value = stringResource(R.string.manual_origin_hint),
                color = EchoColors.InkMuted,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            )
            EchoText(
                value = stringResource(R.string.manual_origin_label),
                color = EchoColors.InkSecondary,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            )
            InputField(
                value = bodyOrigin,
                onValueChange = {
                    bodyOrigin = it
                    tokenMessage = null
                    confirmTokenClear = false
                },
                label = stringResource(R.string.manual_origin_label),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = stringResource(R.string.check_address),
                    enabled = !probing,
                    onClick = { endpointMessage = endpointMessageFor(EndpointPolicy.validate(bodyOrigin)) },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = if (probing) stringResource(R.string.cancel_connection) else stringResource(R.string.connect_body),
                    enabled = true,
                    onClick = {
                        if (probing) {
                            cancelProbe?.invoke()
                            cancelProbe = null
                            probing = false
                            probeMessage = ProbeMessage.Cancelled
                        } else {
                            endpointMessage = endpointMessageFor(EndpointPolicy.validate(bodyOrigin))
                            probeMessage = ProbeMessage.Running
                            probing = true
                            val cancelFlag = AtomicBoolean(false)
                            cancelProbe = { cancelFlag.set(true) }
                            val origin = bodyOrigin
                            val typedToken = bearerToken.trim()
                            val savedToken = if (typedToken.isBlank()) tokenStore.load(origin).orEmpty() else ""
                            val token = typedToken.ifBlank { savedToken }
                            val shouldBindSavedTokenToBody = typedToken.isBlank() && savedToken.isNotBlank()
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    probeClient.probe(origin, token.ifBlank { null })
                                }
                                if (cancelFlag.get()) {
                                    return@launch
                                }
                                probing = false
                                cancelProbe = null
                                probeMessage = probeMessageFor(result)
                                if (result is ProbeResult.Verified) {
                                    historyStore.record(
                                        origin = result.connection.origin,
                                        deviceLabel = result.connection.descriptor.deviceLabel,
                                    )
                                    if (shouldBindSavedTokenToBody) {
                                        tokenMessage = when (
                                            tokenStore.saveForVerifiedBody(
                                                origin = result.connection.origin,
                                                deviceId = result.connection.descriptor.deviceId,
                                                token = savedToken,
                                            )
                                        ) {
                                            SecureTokenStore.StoreResult.Saved -> TokenMessage.Saved
                                            is SecureTokenStore.StoreResult.Failed -> TokenMessage.Failed
                                        }
                                    }
                                    historyEntries = historyStore.load()
                                    bearerToken = ""
                                    onConnected(result.connection)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            endpointMessage?.let { message ->
                EndpointMessageText(message)
            }
            probeMessage?.let { message ->
                ProbeMessageText(message)
            }
            InfoBlock(
                title = stringResource(R.string.access_token),
                body = if (hasStoredToken) {
                    stringResource(R.string.token_saved_for_origin)
                } else {
                    stringResource(R.string.token_not_saved)
                },
                accent = if (hasStoredToken) EchoColors.Permit else EchoColors.Caution,
            )
            InputField(
                value = bearerToken,
                onValueChange = { bearerToken = it },
                label = stringResource(R.string.access_token_session_only),
                secret = true,
            )
            EchoText(
                value = stringResource(R.string.access_token_hint),
                color = EchoColors.InkMuted,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = stringResource(R.string.token_save),
                    enabled = bearerToken.isNotBlank() && !probing,
                    disabledReason = stringResource(R.string.token_save_disabled),
                    onClick = {
                        tokenMessage = when (val result = tokenStore.save(bodyOrigin, bearerToken)) {
                            SecureTokenStore.StoreResult.Saved -> {
                                bearerToken = ""
                                TokenMessage.Saved
                            }
                            is SecureTokenStore.StoreResult.Failed -> TokenMessage.Failed
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = stringResource(R.string.token_clear),
                    enabled = hasStoredToken && !probing,
                    disabledReason = stringResource(R.string.token_not_saved),
                    onClick = { confirmTokenClear = true },
                    modifier = Modifier.weight(1f),
                )
            }
            if (confirmTokenClear) {
                ConfirmationBlock(
                    title = stringResource(R.string.token_clear_confirm_title),
                    body = stringResource(R.string.token_clear_confirm_body),
                    confirmLabel = stringResource(R.string.token_clear_confirm_action),
                    onCancel = { confirmTokenClear = false },
                    onConfirm = {
                        tokenStore.clear(bodyOrigin)
                        tokenMessage = TokenMessage.Cleared
                        confirmTokenClear = false
                    },
                )
            }
            tokenMessage?.let { message ->
                TokenMessageText(message)
            }
            EchoText(
                value = stringResource(R.string.verified_connection_required),
                color = EchoColors.InkMuted,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            )
        }
    }
}

@Composable
private fun NearbyBodiesBlock(
    discoveryState: DiscoveryState,
    onToggleDiscovery: () -> Unit,
    onSelect: (DiscoveredBody) -> Unit,
) {
    val bodies = discoveryState.bodies()
    val scanning = discoveryState is DiscoveryState.Scanning
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoBlock(
            title = stringResource(R.string.nearby_bodies),
            body = when (discoveryState) {
                is DiscoveryState.Failed -> stringResource(
                    R.string.discovery_failed,
                    discoveryState.errorCode,
                    DeviceDiscoveryClient.SERVICE_TYPE,
                )
                is DiscoveryState.Idle -> if (bodies.isEmpty()) {
                    stringResource(R.string.discovery_idle_empty, DeviceDiscoveryClient.SERVICE_TYPE)
                } else {
                    stringResource(R.string.discovery_idle_found, bodies.size)
                }
                is DiscoveryState.Scanning -> {
                    val warning = discoveryState.warningCode
                    if (warning == null) {
                        stringResource(R.string.discovery_scanning, DeviceDiscoveryClient.SERVICE_TYPE, bodies.size)
                    } else {
                        stringResource(R.string.discovery_scanning_warning, bodies.size, warning)
                    }
                }
            },
            accent = when (discoveryState) {
                is DiscoveryState.Failed -> EchoColors.Caution
                is DiscoveryState.Scanning -> EchoColors.Live
                is DiscoveryState.Idle -> if (bodies.isEmpty()) EchoColors.InkMuted else EchoColors.Permit
            },
            liveRegionMode = LiveRegionMode.Polite,
        )
        ActionButton(
            label = if (scanning) {
                stringResource(R.string.discovery_stop)
            } else {
                stringResource(R.string.discovery_start)
            },
            enabled = true,
            onClick = onToggleDiscovery,
            modifier = Modifier.fillMaxWidth(),
        )
        bodies.forEach { body ->
            Panel(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onSelect(body) },
                background = EchoColors.Glass,
                border = EchoColors.Hair,
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EchoText(
                        value = body.serviceName,
                        color = EchoColors.Ink,
                        style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    EchoText(
                        value = stringResource(R.string.discovery_body_origin, body.origin),
                        color = EchoColors.InkSecondary,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                    )
                    EchoText(
                        value = stringResource(R.string.discovery_body_host, body.host, body.port),
                        color = EchoColors.InkMuted,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionHistoryBlock(
    entries: List<DeviceHistoryEntry>,
    onSelect: (DeviceHistoryEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.connection_history))
        if (entries.isEmpty()) {
            InfoBlock(
                title = stringResource(R.string.connection_history),
                body = stringResource(R.string.connection_history_empty),
                accent = EchoColors.InkMuted,
            )
        } else {
            entries.forEach { entry ->
                Panel(
                    modifier = Modifier.fillMaxWidth(),
                    background = EchoColors.Glass,
                    border = EchoColors.Hair,
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            EchoText(
                                value = entry.deviceLabel,
                                color = EchoColors.Ink,
                                style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            EchoText(
                                value = entry.origin,
                                color = EchoColors.InkMuted,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ActionButton(
                            label = stringResource(R.string.connection_history_use),
                            enabled = true,
                            onClick = { onSelect(entry) },
                            modifier = Modifier.size(width = 72.dp, height = 48.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContractGate() {
    Panel(
        modifier = Modifier.fillMaxWidth(),
        background = EchoColors.Caution.copy(alpha = 0.10f),
        border = EchoColors.Caution.copy(alpha = 0.48f),
    ) {
        InfoBlock(
            title = stringResource(R.string.contract_gate_title),
            body = stringResource(R.string.contract_gate_body),
            accent = EchoColors.Caution,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SessionsScreen(
    bodyConnection: DeviceConnection?,
    sessionPage: SessionListPage?,
    sessionMessage: SessionMessage?,
    sessionLoadingMore: Boolean,
    sessionManifest: DeviceSessionManifest?,
    sessionManifestMessage: SessionManifestMessage?,
    sessionManifestLoading: Boolean,
    unsuccessfulOutcome: RetainedUnsuccessfulOutcome?,
    unsuccessfulOutcomeSessionId: String?,
    unsuccessfulOutcomeMessage: UnsuccessfulOutcomeMessage?,
    unsuccessfulOutcomeLoadingId: String?,
    artifactDownloadMessage: ArtifactDownloadMessage?,
    artifactDownloadingId: String?,
    onCancelDownload: () -> Unit,
    onLoadUnsuccessfulOutcome: (SessionSummary) -> Unit,
    onLoadMoreSessions: () -> Unit,
    onLoadManifest: (SessionSummary) -> Unit,
    onDownloadArtifact: (ArtifactDescriptor) -> Unit,
) {
    var sessionFilter by rememberSaveable(bodyConnection?.origin) { mutableStateOf(SESSION_FILTER_ALL) }
    val sessionFilterOptions = listOf(
        SESSION_FILTER_ALL to stringResource(R.string.sessions_filter_all),
        SESSION_FILTER_AVAILABLE to stringResource(R.string.sessions_filter_available),
        SESSION_FILTER_UNSUCCESSFUL to stringResource(R.string.sessions_filter_unsuccessful),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            InfoBlock(
                title = stringResource(R.string.nav_sessions),
                body = when {
                    bodyConnection == null -> stringResource(R.string.sessions_empty)
                    sessionPage == null && sessionMessage == null -> stringResource(R.string.sessions_loading)
                    sessionPage?.items?.isEmpty() == true -> stringResource(R.string.sessions_empty_connected)
                    else -> stringResource(R.string.sessions_no_fake)
                },
                accent = if (bodyConnection == null) EchoColors.InkMuted else EchoColors.Permit,
                modifier = Modifier.padding(12.dp),
            )
        }
        sessionMessage?.let { SessionMessageBlock(it) }
        sessionPage?.let { page ->
            if (page.diagnosticsCount > 0) {
                Panel(
                    modifier = Modifier.fillMaxWidth(),
                    background = EchoColors.Caution.copy(alpha = 0.10f),
                    border = EchoColors.Caution.copy(alpha = 0.48f),
                ) {
                    InfoBlock(
                        title = stringResource(R.string.diagnostics),
                        body = stringResource(R.string.session_diagnostics_count, page.diagnosticsCount),
                        accent = EchoColors.Caution,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (page.items.isNotEmpty()) {
                Panel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabel(stringResource(R.string.sessions_filter_title))
                        SegmentedControl(
                            options = sessionFilterOptions,
                            selected = sessionFilter,
                            onSelected = { sessionFilter = it },
                        )
                    }
                }
            }
            val visibleSessionItems = page.items.filter { sessionMatchesFilter(it, sessionFilter) }
            if (page.items.isNotEmpty() && visibleSessionItems.isEmpty()) {
                Panel(modifier = Modifier.fillMaxWidth()) {
                    InfoBlock(
                        title = stringResource(R.string.sessions_filter_empty_title),
                        body = stringResource(R.string.sessions_filter_empty_body),
                        accent = EchoColors.InkMuted,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            visibleSessionItems.forEach { summary ->
                SessionSummaryCard(
                    summary = summary,
                    isManifestVisible = sessionManifest?.sessionId == summary.sessionId,
                    isLoadingManifest = sessionManifestLoading,
                    unsuccessfulOutcome = unsuccessfulOutcome?.takeIf { unsuccessfulOutcomeSessionId == summary.sessionId },
                    unsuccessfulOutcomeMessage = unsuccessfulOutcomeMessage.takeIf {
                        unsuccessfulOutcomeSessionId == summary.sessionId
                    },
                    isLoadingUnsuccessfulOutcome = unsuccessfulOutcomeLoadingId == summary.sessionId,
                    artifactDownloadingId = artifactDownloadingId,
                    onCancelDownload = onCancelDownload,
                    onLoadManifest = { onLoadManifest(summary) },
                    onLoadUnsuccessfulOutcome = { onLoadUnsuccessfulOutcome(summary) },
                    onDownloadArtifact = onDownloadArtifact,
                    manifest = sessionManifest?.takeIf { it.sessionId == summary.sessionId },
                )
            }
            if (page.nextCursor != null) {
                ActionButton(
                    label = if (sessionLoadingMore) {
                        stringResource(R.string.sessions_loading_more)
                    } else {
                        stringResource(R.string.sessions_load_more)
                    },
                    enabled = !sessionLoadingMore,
                    modifier = Modifier.fillMaxWidth(),
                    disabledReason = stringResource(R.string.sessions_loading_more),
                    onClick = onLoadMoreSessions,
                )
            }
        }
        sessionManifestMessage?.let { SessionManifestMessageBlock(it) }
        artifactDownloadMessage?.let { ArtifactDownloadMessageBlock(it) }
    }
}

@Composable
private fun NetworkScreen(
    bodyConnection: DeviceConnection?,
    captureStatus: CaptureStatusSnapshot?,
    foregroundResumeTick: Long,
) {
    val scope = rememberCoroutineScope()
    val deviceClient = remember { DeviceHttpClient() }
    var networkStatus by remember(bodyConnection?.origin) { mutableStateOf<NetworkStatus?>(null) }
    var networkMessage by remember(bodyConnection?.origin) { mutableStateOf<NetworkMessage?>(null) }
    var lastNetworkEventId by remember(bodyConnection?.origin) { mutableStateOf<String?>(null) }
    var scanSnapshot by remember(bodyConnection?.origin) { mutableStateOf<NetworkScanSnapshot?>(null) }
    var selectedNetwork by remember(bodyConnection?.origin) { mutableStateOf<NetworkScanEntry?>(null) }
    var selectedNetworkMode by rememberSaveable(bodyConnection?.origin) { mutableStateOf("wifi-client") }
    var manualSsid by rememberSaveable(bodyConnection?.origin) { mutableStateOf("") }
    var selectedSecurity by rememberSaveable(bodyConnection?.origin) { mutableStateOf("wpa2-personal") }
    var passphrase by remember(bodyConnection?.origin) { mutableStateOf("") }
    var staticAddress by rememberSaveable(bodyConnection?.origin) { mutableStateOf("") }
    var staticPrefixLength by rememberSaveable(bodyConnection?.origin) { mutableStateOf("24") }
    var staticGateway by rememberSaveable(bodyConnection?.origin) { mutableStateOf("") }
    var staticDns by rememberSaveable(bodyConnection?.origin) { mutableStateOf("") }
    var networkCommandRunning by remember(bodyConnection?.origin) { mutableStateOf(false) }
    var confirmForget by rememberSaveable(bodyConnection?.origin) { mutableStateOf(false) }

    suspend fun reconcileNetworkStatus(activeConnection: DeviceConnection) {
        when (val result = withContext(Dispatchers.IO) { deviceClient.getNetworkStatus(activeConnection) }) {
            NetworkStatusResult.AuthenticationRequired -> networkMessage = NetworkMessage.AuthRequired
            NetworkStatusResult.Forbidden -> networkMessage = NetworkMessage.Forbidden
            is NetworkStatusResult.HttpFailure -> networkMessage = NetworkMessage.HttpFailure(result.statusCode)
            is NetworkStatusResult.InvalidResponse -> networkMessage = NetworkMessage.InvalidResponse(result.message)
            is NetworkStatusResult.NetworkFailure -> {
                networkMessage = pendingNetworkMutationMessage(networkStatus, result.message)
            }
            is NetworkStatusResult.Status -> {
                networkStatus = result.value
                networkMessage = null
            }
            NetworkStatusResult.Unavailable -> networkMessage = NetworkMessage.StatusUnavailable
        }
    }

    LaunchedEffect(bodyConnection) {
        val activeConnection = bodyConnection
        networkStatus = null
        networkMessage = null
        lastNetworkEventId = null
        scanSnapshot = null
        selectedNetwork = null
        selectedNetworkMode = "wifi-client"
        passphrase = ""
        staticAddress = ""
        staticPrefixLength = "24"
        staticGateway = ""
        staticDns = ""
        confirmForget = false
        networkCommandRunning = false
        if (activeConnection == null) {
            return@LaunchedEffect
        }

        networkMessage = NetworkMessage.StatusLoading
        while (isActive) {
            reconcileNetworkStatus(activeConnection)
            delay(5_000L)
        }
    }

    LaunchedEffect(bodyConnection, foregroundResumeTick) {
        val activeConnection = bodyConnection ?: return@LaunchedEffect
        if (foregroundResumeTick == 0L) {
            return@LaunchedEffect
        }
        networkMessage = NetworkMessage.StatusLoading
        reconcileNetworkStatus(activeConnection)
    }

    LaunchedEffect(bodyConnection) {
        val activeConnection = bodyConnection ?: return@LaunchedEffect
        while (isActive) {
            val currentStatus = networkStatus
            val eventResult = withContext(Dispatchers.IO) {
                deviceClient.readNetworkEvents(
                    connection = activeConnection,
                    lastEventId = lastNetworkEventId,
                    lastAuthorityEpoch = currentStatus?.authorityEpoch,
                    lastSourceRevision = currentStatus?.sourceRevision,
                    maxEvents = 8,
                )
            }
            when (eventResult) {
                is NetworkEventsResult.Batch -> {
                    var needsReconciliation = false
                    eventResult.events.forEach { event ->
                        lastNetworkEventId = event.sseDeliveryId
                        val previousStatus = networkStatus
                        networkStatus = applyNetworkStreamEvent(previousStatus, event)
                        needsReconciliation = needsReconciliation ||
                            event.requiresHttpReconciliation ||
                            (previousStatus == null && event.transaction != null)
                    }
                    if (needsReconciliation) {
                        reconcileNetworkStatus(activeConnection)
                    } else if (eventResult.events.isNotEmpty()) {
                        networkMessage = null
                    }
                    delay(if (eventResult.events.isEmpty()) 1_000L else 250L)
                }
                NetworkEventsResult.AuthenticationRequired -> {
                    networkMessage = NetworkMessage.AuthRequired
                    delay(2_000L)
                }
                NetworkEventsResult.Forbidden -> {
                    networkMessage = NetworkMessage.Forbidden
                    delay(2_000L)
                }
                is NetworkEventsResult.HttpFailure -> {
                    networkMessage = NetworkMessage.HttpFailure(eventResult.statusCode)
                    delay(2_000L)
                }
                is NetworkEventsResult.InvalidRequest -> {
                    networkMessage = NetworkMessage.InvalidResponse(eventResult.message)
                    delay(2_000L)
                }
                is NetworkEventsResult.InvalidResponse -> {
                    networkMessage = NetworkMessage.InvalidResponse(eventResult.message)
                    delay(2_000L)
                }
                is NetworkEventsResult.NetworkFailure -> {
                    networkMessage = pendingNetworkMutationMessage(networkStatus, eventResult.message)
                    delay(2_000L)
                }
            }
        }
    }

    val mutationEnabledByDescriptor = bodyConnection?.descriptor?.networkMutationCapable == true
    val mutationEnabledByStatus = networkStatus?.mutationCapability?.enabled == true
    val captureState = captureStatus?.deviceState
    val captureIdle = captureState == "idle"
    val canMutate = bodyConnection != null &&
        mutationEnabledByDescriptor &&
        mutationEnabledByStatus &&
        captureIdle &&
        !networkCommandRunning
    val effectiveSecurity = selectedNetwork?.security ?: selectedSecurity
    val effectiveSsid = (selectedNetwork?.ssid ?: manualSsid).trim()
    val selectedNeedsCredential = effectiveSecurity != "open"
    val staticPrefixValue = staticPrefixLength.trim().toIntOrNull()
    val staticGatewayValue = staticGateway.trim().ifBlank { null }
    val staticDnsList = parseNetworkStaticDnsInput(staticDns)
    val canApplyWifi = canMutate &&
        selectedNetworkMode == "wifi-client" &&
        effectiveSsid.isNotBlank() &&
        effectiveSsid.toByteArray(Charsets.UTF_8).size <= 32 &&
        (!selectedNeedsCredential || passphrase.length in 8..63)
    val canApplyHotspot = canMutate && selectedNetworkMode == "hotspot"
    val canApplyEthernetDhcp = canMutate && selectedNetworkMode == "ethernet-dhcp"
    val canApplyEthernetStatic = canMutate &&
        selectedNetworkMode == "ethernet-static" &&
        isIpv4Address(staticAddress.trim()) &&
        staticPrefixValue?.let { it in 1..32 } == true &&
        (staticGatewayValue == null || isIpv4Address(staticGatewayValue)) &&
        isNetworkStaticDnsListValid(staticDnsList)
    val retryTransaction = networkStatus?.transaction?.latest?.takeIf { it.status in setOf("rescued", "failed") }
    val canRetry = canMutate && retryTransaction != null
    val canForget = canMutate && networkStatus?.saved == true

    fun runScan() {
        val activeConnection = bodyConnection ?: return
        if (networkCommandRunning) return
        networkCommandRunning = true
        networkMessage = NetworkMessage.ScanRunning
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { deviceClient.scanNetworks(activeConnection) }) {
                NetworkScanResult.AuthenticationRequired -> networkMessage = NetworkMessage.AuthRequired
                NetworkScanResult.Forbidden -> networkMessage = NetworkMessage.Forbidden
                is NetworkScanResult.HttpFailure -> networkMessage = NetworkMessage.HttpFailure(result.statusCode)
                is NetworkScanResult.InvalidResponse -> networkMessage = NetworkMessage.InvalidResponse(result.message)
                is NetworkScanResult.NetworkFailure -> networkMessage = NetworkMessage.NetworkFailure(result.message)
                is NetworkScanResult.Scan -> {
                    scanSnapshot = result.value
                    networkMessage = NetworkMessage.ScanLoaded(result.value.networks.size)
                }
                NetworkScanResult.Unavailable -> networkMessage = NetworkMessage.ScanUnavailable
            }
            networkCommandRunning = false
        }
    }

    suspend fun finishNetworkMutation(
        activeConnection: DeviceConnection,
        result: NetworkMutationResult,
        afterAccepted: () -> Unit = {},
    ) {
        val accepted = result as? NetworkMutationResult.Accepted
        if (accepted != null) {
            networkStatus = applyNetworkTransaction(networkStatus, accepted.value.transaction)
            networkMessage = NetworkMessage.MutationAccepted(accepted.value.transaction.transactionId)
            afterAccepted()
            reconcileNetworkStatus(activeConnection)
            if (networkMessage == null) {
                networkMessage = NetworkMessage.MutationAccepted(accepted.value.transaction.transactionId)
            }
        } else {
            handleNetworkMutationResult(
                result = result,
                onAccepted = {},
                onMessage = { networkMessage = it },
            )
        }
    }

    fun runApplyWifi() {
        val activeConnection = bodyConnection ?: return
        if (!canApplyWifi) return
        networkCommandRunning = true
        networkMessage = NetworkMessage.MutationRunning
        scope.launch {
            val credentialRef = if (selectedNeedsCredential) {
                when (val credentialResult = withContext(Dispatchers.IO) {
                    deviceClient.createNetworkCredentialReference(activeConnection, passphrase)
                }) {
                    NetworkCredentialResult.AuthenticationRequired -> {
                        networkMessage = NetworkMessage.AuthRequired
                        networkCommandRunning = false
                        return@launch
                    }
                    NetworkCredentialResult.Forbidden -> {
                        networkMessage = NetworkMessage.Forbidden
                        networkCommandRunning = false
                        return@launch
                    }
                    is NetworkCredentialResult.HttpFailure -> {
                        networkMessage = NetworkMessage.HttpFailure(credentialResult.statusCode)
                        networkCommandRunning = false
                        return@launch
                    }
                    is NetworkCredentialResult.InvalidRequest -> {
                        networkMessage = NetworkMessage.InvalidRequest(credentialResult.message)
                        networkCommandRunning = false
                        return@launch
                    }
                    is NetworkCredentialResult.InvalidResponse -> {
                        networkMessage = NetworkMessage.InvalidResponse(credentialResult.message)
                        networkCommandRunning = false
                        return@launch
                    }
                    NetworkCredentialResult.MutationUnavailable -> {
                        networkMessage = NetworkMessage.MutationUnavailable
                        networkCommandRunning = false
                        return@launch
                    }
                    is NetworkCredentialResult.NetworkFailure -> {
                        networkMessage = NetworkMessage.NetworkFailure(credentialResult.message)
                        networkCommandRunning = false
                        return@launch
                    }
                    is NetworkCredentialResult.Receipt -> credentialResult.value.credentialRef
                }
            } else {
                null
            }
            val mutationResult = withContext(Dispatchers.IO) {
                deviceClient.applyWifiClientNetwork(
                    connection = activeConnection,
                    idempotencyKey = UUID.randomUUID().toString(),
                    ssid = effectiveSsid,
                    security = effectiveSecurity,
                    credentialRef = credentialRef,
                )
            }
            finishNetworkMutation(activeConnection, mutationResult) {
                passphrase = ""
            }
            networkCommandRunning = false
        }
    }

    fun runApplyHotspot() {
        val activeConnection = bodyConnection ?: return
        if (!canApplyHotspot) return
        networkCommandRunning = true
        networkMessage = NetworkMessage.MutationRunning
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                deviceClient.applyHotspotNetwork(
                    connection = activeConnection,
                    idempotencyKey = UUID.randomUUID().toString(),
                )
            }
            finishNetworkMutation(activeConnection, result) {
                selectedNetwork = null
                passphrase = ""
            }
            networkCommandRunning = false
        }
    }

    fun runApplyEthernetDhcp() {
        val activeConnection = bodyConnection ?: return
        if (!canApplyEthernetDhcp) return
        networkCommandRunning = true
        networkMessage = NetworkMessage.MutationRunning
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                deviceClient.applyEthernetDhcpNetwork(
                    connection = activeConnection,
                    idempotencyKey = UUID.randomUUID().toString(),
                )
            }
            finishNetworkMutation(activeConnection, result)
            networkCommandRunning = false
        }
    }

    fun runApplyEthernetStatic() {
        val activeConnection = bodyConnection ?: return
        val prefix = staticPrefixValue ?: return
        if (!canApplyEthernetStatic) return
        networkCommandRunning = true
        networkMessage = NetworkMessage.MutationRunning
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                deviceClient.applyEthernetStaticNetwork(
                    connection = activeConnection,
                    idempotencyKey = UUID.randomUUID().toString(),
                    address = staticAddress.trim(),
                    prefixLength = prefix,
                    gateway = staticGatewayValue,
                    dns = staticDnsList,
                )
            }
            finishNetworkMutation(activeConnection, result)
            networkCommandRunning = false
        }
    }

    fun runRetry() {
        val activeConnection = bodyConnection ?: return
        val transaction = retryTransaction ?: return
        if (!canRetry) return
        networkCommandRunning = true
        networkMessage = NetworkMessage.MutationRunning
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                deviceClient.retryNetworkTransaction(
                    connection = activeConnection,
                    idempotencyKey = UUID.randomUUID().toString(),
                    transactionId = transaction.transactionId,
                )
            }
            finishNetworkMutation(activeConnection, result)
            networkCommandRunning = false
        }
    }

    fun runForget() {
        val activeConnection = bodyConnection ?: return
        if (!canForget) return
        networkCommandRunning = true
        networkMessage = NetworkMessage.MutationRunning
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                deviceClient.forgetNetworkClientProfile(
                    connection = activeConnection,
                    idempotencyKey = UUID.randomUUID().toString(),
                )
            }
            finishNetworkMutation(activeConnection, result) {
                confirmForget = false
                selectedNetwork = null
                manualSsid = ""
                passphrase = ""
            }
            networkCommandRunning = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            InfoBlock(
                title = stringResource(R.string.nav_network),
                body = if (bodyConnection == null) {
                    stringResource(R.string.network_connect_first)
                } else {
                    stringResource(R.string.network_contract_scope_body)
                },
                accent = if (bodyConnection == null) EchoColors.Caution else EchoColors.Live,
                modifier = Modifier.padding(12.dp),
                liveRegionMode = LiveRegionMode.Polite,
            )
        }
        if (bodyConnection != null) {
            Panel(modifier = Modifier.fillMaxWidth()) {
                NetworkRuntimeBlock(
                    runtime = bodyConnection.descriptor.runtime,
                    modifier = Modifier.padding(12.dp),
                )
            }
            NetworkAuthorityBlock(networkStatus, networkMessage)
            NetworkMutationPanel(
                networkStatus = networkStatus,
                scanSnapshot = scanSnapshot,
                selectedNetwork = selectedNetwork,
                selectedNetworkMode = selectedNetworkMode,
                manualSsid = manualSsid,
                selectedSecurity = selectedSecurity,
                passphrase = passphrase,
                staticAddress = staticAddress,
                staticPrefixLength = staticPrefixLength,
                staticGateway = staticGateway,
                staticDns = staticDns,
                commandRunning = networkCommandRunning,
                canApplyWifi = canApplyWifi,
                canApplyHotspot = canApplyHotspot,
                canApplyEthernetDhcp = canApplyEthernetDhcp,
                canApplyEthernetStatic = canApplyEthernetStatic,
                canRetry = canRetry,
                canForget = canForget,
                captureState = captureState,
                mutationEnabledByDescriptor = mutationEnabledByDescriptor,
                onScan = ::runScan,
                onModeChange = { selectedNetworkMode = it },
                onSelectNetwork = { entry ->
                    selectedNetworkMode = "wifi-client"
                    selectedNetwork = entry
                    selectedSecurity = entry.security
                    entry.ssid?.let { manualSsid = it }
                    passphrase = ""
                },
                onManualSsidChange = {
                    manualSsid = it
                    selectedNetwork = null
                },
                onSecurityChange = {
                    selectedSecurity = it
                    selectedNetwork = null
                },
                onPassphraseChange = { passphrase = it },
                onStaticAddressChange = { staticAddress = it },
                onStaticPrefixLengthChange = { staticPrefixLength = it },
                onStaticGatewayChange = { staticGateway = it },
                onStaticDnsChange = { staticDns = it },
                onApplyWifi = ::runApplyWifi,
                onApplyHotspot = ::runApplyHotspot,
                onApplyEthernetDhcp = ::runApplyEthernetDhcp,
                onApplyEthernetStatic = ::runApplyEthernetStatic,
                onRetry = ::runRetry,
                onRequestForget = { confirmForget = true },
            )
            if (confirmForget) {
                ConfirmationBlock(
                    title = stringResource(R.string.network_forget_confirm_title),
                    body = stringResource(R.string.network_forget_confirm_body),
                    confirmLabel = stringResource(R.string.network_forget_confirm_action),
                    onCancel = { confirmForget = false },
                    onConfirm = ::runForget,
                )
            }
        }
    }
}

@Composable
private fun NetworkRuntimeBlock(
    runtime: DeviceRuntime,
    modifier: Modifier = Modifier,
) {
    val lines = listOf(
        runtimeLabel(
            runtime.observedAt,
            runtime.connectionMethod,
            runtime.temperatureCelsius,
        ),
        stringResource(R.string.network_default_route, networkRouteLabel(runtime.network.defaultRoute)),
        networkInterfaceLine(stringResource(R.string.network_interface_ap), runtime.network.ap),
        networkInterfaceLine(stringResource(R.string.network_interface_wifi_client), runtime.network.wifiClient),
        networkInterfaceLine(stringResource(R.string.network_interface_wired), runtime.network.wired),
        runtime.liveImuQuality?.let { quality ->
            stringResource(R.string.runtime_imu_quality, imuQualityLabel(quality))
        } ?: stringResource(R.string.runtime_imu_unavailable),
    )
    InfoBlock(
        title = stringResource(R.string.network_observed_runtime),
        body = lines.joinToString("\n"),
        accent = EchoColors.Live,
        modifier = modifier,
    )
}

@Composable
private fun NetworkAuthorityBlock(
    networkStatus: NetworkStatus?,
    networkMessage: NetworkMessage?,
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(stringResource(R.string.network_authority_status))
            if (networkStatus == null) {
                InfoBlock(
                    title = stringResource(R.string.network_authority_status),
                    body = networkMessageBody(networkMessage ?: NetworkMessage.StatusLoading),
                    accent = networkMessageColor(networkMessage ?: NetworkMessage.StatusLoading),
                    liveRegionMode = LiveRegionMode.Polite,
                )
                return@Column
            }
            StatusChipGroup {
                StatusChip(
                    stringResource(
                        R.string.network_saved_verified,
                        booleanLabel(networkStatus.saved),
                        booleanLabel(networkStatus.verified),
                    ),
                    if (networkStatus.verified) EchoColors.Permit else EchoColors.Caution,
                )
                StatusChip(
                    stringResource(R.string.source_revision, networkStatus.sourceRevision),
                    EchoColors.Live,
                )
            }
            InfoBlock(
                title = stringResource(R.string.network_desired_state),
                body = networkDesiredStateText(networkStatus.desired),
                accent = EchoColors.InkSecondary,
            )
            InfoBlock(
                title = stringResource(R.string.network_observed_authority),
                body = networkObservedStateText(networkStatus.observed),
                accent = EchoColors.Live,
            )
            InfoBlock(
                title = stringResource(R.string.network_mdns),
                body = stringResource(
                    R.string.network_mdns_value,
                    networkStatus.observed.mdns.hostname,
                    networkStatus.observed.mdns.service,
                    networkStatus.observed.mdns.aliases.joinToString(", ").ifBlank { stringResource(R.string.value_none) },
                    networkStatus.observed.mdns.port,
                ),
                accent = EchoColors.Permit,
            )
            InfoBlock(
                title = stringResource(R.string.network_mutation_capability),
                body = networkMutationCapabilityText(networkStatus),
                accent = if (networkStatus.mutationCapability.enabled) EchoColors.Permit else EchoColors.Caution,
            )
            NetworkTransactionBlock(
                title = stringResource(R.string.network_current_transaction),
                transaction = networkStatus.transaction.current,
            )
            NetworkTransactionBlock(
                title = stringResource(R.string.network_latest_transaction),
                transaction = networkStatus.transaction.latest,
            )
            networkMessage?.let {
                NetworkMessageBlock(it)
            }
        }
    }
}

@Composable
private fun NetworkMutationPanel(
    networkStatus: NetworkStatus?,
    scanSnapshot: NetworkScanSnapshot?,
    selectedNetwork: NetworkScanEntry?,
    selectedNetworkMode: String,
    manualSsid: String,
    selectedSecurity: String,
    passphrase: String,
    staticAddress: String,
    staticPrefixLength: String,
    staticGateway: String,
    staticDns: String,
    commandRunning: Boolean,
    canApplyWifi: Boolean,
    canApplyHotspot: Boolean,
    canApplyEthernetDhcp: Boolean,
    canApplyEthernetStatic: Boolean,
    canRetry: Boolean,
    canForget: Boolean,
    captureState: String?,
    mutationEnabledByDescriptor: Boolean,
    onScan: () -> Unit,
    onModeChange: (String) -> Unit,
    onSelectNetwork: (NetworkScanEntry) -> Unit,
    onManualSsidChange: (String) -> Unit,
    onSecurityChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onStaticAddressChange: (String) -> Unit,
    onStaticPrefixLengthChange: (String) -> Unit,
    onStaticGatewayChange: (String) -> Unit,
    onStaticDnsChange: (String) -> Unit,
    onApplyWifi: () -> Unit,
    onApplyHotspot: () -> Unit,
    onApplyEthernetDhcp: () -> Unit,
    onApplyEthernetStatic: () -> Unit,
    onRetry: () -> Unit,
    onRequestForget: () -> Unit,
) {
    val mutationReason = networkMutationDisabledReason(
        networkStatus = networkStatus,
        captureState = captureState,
        mutationEnabledByDescriptor = mutationEnabledByDescriptor,
        commandRunning = commandRunning,
    )
    val canApplySelectedMode = when (selectedNetworkMode) {
        "wifi-client" -> canApplyWifi
        "hotspot" -> canApplyHotspot
        "ethernet-dhcp" -> canApplyEthernetDhcp
        "ethernet-static" -> canApplyEthernetStatic
        else -> false
    }
    Panel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(stringResource(R.string.network_mutation_panel))
            EchoText(
                value = stringResource(R.string.network_mutation_hint),
                color = EchoColors.InkMuted,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            )
            SegmentedControl(
                options = listOf(
                    "wifi-client" to stringResource(R.string.network_mode_wifi_client),
                    "hotspot" to stringResource(R.string.network_mode_hotspot),
                    "ethernet-dhcp" to stringResource(R.string.network_mode_ethernet_dhcp),
                    "ethernet-static" to stringResource(R.string.network_mode_ethernet_static),
                ),
                selected = selectedNetworkMode,
                onSelected = onModeChange,
            )
            when (selectedNetworkMode) {
                "wifi-client" -> {
                    ActionButton(
                        label = if (commandRunning) {
                            stringResource(R.string.network_command_running)
                        } else {
                            stringResource(R.string.network_scan)
                        },
                        enabled = !commandRunning && networkStatus != null,
                        disabledReason = mutationReason,
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NetworkScanBlock(
                        scanSnapshot = scanSnapshot,
                        selectedNetwork = selectedNetwork,
                        onSelectNetwork = onSelectNetwork,
                    )
                    SectionLabel(stringResource(R.string.network_wifi_target))
                    EchoText(
                        value = stringResource(R.string.network_ssid_label),
                        color = EchoColors.InkSecondary,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                    InputField(
                        value = manualSsid,
                        onValueChange = onManualSsidChange,
                        label = stringResource(R.string.network_ssid_label),
                    )
                    SegmentedControl(
                        options = listOf(
                            "open" to stringResource(R.string.network_security_open),
                            "wpa2-personal" to stringResource(R.string.network_security_wpa2),
                            "wpa3-personal" to stringResource(R.string.network_security_wpa3),
                            "wpa2-wpa3-personal" to stringResource(R.string.network_security_wpa2_wpa3),
                        ),
                        selected = selectedNetwork?.security ?: selectedSecurity,
                        onSelected = onSecurityChange,
                    )
                    if ((selectedNetwork?.security ?: selectedSecurity) != "open") {
                        EchoText(
                            value = stringResource(R.string.network_passphrase_label),
                            color = EchoColors.InkSecondary,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        )
                        InputField(
                            value = passphrase,
                            onValueChange = onPassphraseChange,
                            label = stringResource(R.string.network_passphrase_label),
                            secret = true,
                        )
                        EchoText(
                            value = stringResource(R.string.network_passphrase_hint),
                            color = EchoColors.InkMuted,
                            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                        )
                    }
                    ActionButton(
                        label = stringResource(R.string.network_apply_wifi),
                        enabled = canApplyWifi,
                        disabledReason = networkApplyDisabledReason(
                            baseReason = mutationReason,
                            mode = selectedNetworkMode,
                            manualSsid = manualSsid,
                            selectedNetwork = selectedNetwork,
                            security = selectedNetwork?.security ?: selectedSecurity,
                            passphrase = passphrase,
                            staticAddress = staticAddress,
                            staticPrefixLength = staticPrefixLength,
                            staticGateway = staticGateway,
                            staticDns = staticDns,
                        ),
                        onClick = onApplyWifi,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                "hotspot" -> {
                    InfoBlock(
                        title = stringResource(R.string.network_hotspot_target),
                        body = stringResource(R.string.network_hotspot_hint),
                        accent = EchoColors.Caution,
                    )
                    ActionButton(
                        label = stringResource(R.string.network_apply_hotspot),
                        enabled = canApplyHotspot,
                        disabledReason = mutationReason ?: stringResource(R.string.network_ready_to_apply),
                        onClick = onApplyHotspot,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                "ethernet-dhcp" -> {
                    InfoBlock(
                        title = stringResource(R.string.network_ethernet_target),
                        body = stringResource(R.string.network_ethernet_dhcp_hint),
                        accent = EchoColors.Live,
                    )
                    ActionButton(
                        label = stringResource(R.string.network_apply_ethernet_dhcp),
                        enabled = canApplyEthernetDhcp,
                        disabledReason = mutationReason ?: stringResource(R.string.network_ready_to_apply),
                        onClick = onApplyEthernetDhcp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                "ethernet-static" -> {
                    SectionLabel(stringResource(R.string.network_ethernet_static_target))
                    EchoText(
                        value = stringResource(R.string.network_static_address_label),
                        color = EchoColors.InkSecondary,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                    InputField(
                        value = staticAddress,
                        onValueChange = onStaticAddressChange,
                        label = stringResource(R.string.network_static_address_label),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            EchoText(
                                value = stringResource(R.string.network_static_prefix_label),
                                color = EchoColors.InkSecondary,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            )
                            InputField(
                                value = staticPrefixLength,
                                onValueChange = onStaticPrefixLengthChange,
                                label = stringResource(R.string.network_static_prefix_label),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            EchoText(
                                value = stringResource(R.string.network_static_gateway_label),
                                color = EchoColors.InkSecondary,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            )
                            InputField(
                                value = staticGateway,
                                onValueChange = onStaticGatewayChange,
                                label = stringResource(R.string.network_static_gateway_label),
                            )
                        }
                    }
                    EchoText(
                        value = stringResource(R.string.network_static_dns_label),
                        color = EchoColors.InkSecondary,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                    InputField(
                        value = staticDns,
                        onValueChange = onStaticDnsChange,
                        label = stringResource(R.string.network_static_dns_label),
                    )
                    EchoText(
                        value = stringResource(R.string.network_static_dns_hint),
                        color = EchoColors.InkMuted,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    )
                    ActionButton(
                        label = stringResource(R.string.network_apply_ethernet_static),
                        enabled = canApplyEthernetStatic,
                        disabledReason = networkApplyDisabledReason(
                            baseReason = mutationReason,
                            mode = selectedNetworkMode,
                            manualSsid = manualSsid,
                            selectedNetwork = selectedNetwork,
                            security = selectedNetwork?.security ?: selectedSecurity,
                            passphrase = passphrase,
                            staticAddress = staticAddress,
                            staticPrefixLength = staticPrefixLength,
                            staticGateway = staticGateway,
                            staticDns = staticDns,
                        ),
                        onClick = onApplyEthernetStatic,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    InfoBlock(
                        title = stringResource(R.string.network_mutation_panel),
                        body = stringResource(R.string.network_invalid_desired_state),
                        accent = EchoColors.Caution,
                    )
                }
            }
            InfoBlock(
                title = stringResource(R.string.network_apply_readiness),
                body = networkApplyDisabledReason(
                    baseReason = mutationReason,
                    mode = selectedNetworkMode,
                    manualSsid = manualSsid,
                    selectedNetwork = selectedNetwork,
                    security = selectedNetwork?.security ?: selectedSecurity,
                    passphrase = passphrase,
                    staticAddress = staticAddress,
                    staticPrefixLength = staticPrefixLength,
                    staticGateway = staticGateway,
                    staticDns = staticDns,
                ),
                accent = if (canApplySelectedMode) EchoColors.Permit else EchoColors.Caution,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = stringResource(R.string.network_retry),
                    enabled = canRetry,
                    disabledReason = mutationReason ?: stringResource(R.string.network_retry_disabled),
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = stringResource(R.string.network_forget),
                    enabled = canForget,
                    disabledReason = mutationReason ?: stringResource(R.string.network_forget_disabled),
                    onClick = onRequestForget,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NetworkScanBlock(
    scanSnapshot: NetworkScanSnapshot?,
    selectedNetwork: NetworkScanEntry?,
    onSelectNetwork: (NetworkScanEntry) -> Unit,
) {
    if (scanSnapshot == null) {
        InfoBlock(
            title = stringResource(R.string.network_scan_results),
            body = stringResource(R.string.network_scan_empty),
            accent = EchoColors.InkMuted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoBlock(
            title = stringResource(R.string.network_scan_results),
            body = stringResource(
                R.string.network_scan_summary,
                scanSnapshot.networks.size,
                scanSnapshot.scannedAt,
                scanSnapshot.sourceRevision,
            ),
            accent = EchoColors.Live,
            liveRegionMode = LiveRegionMode.Polite,
        )
        scanSnapshot.networks.forEach { entry ->
            NetworkScanEntryCard(
                entry = entry,
                selected = entry == selectedNetwork,
                onClick = { onSelectNetwork(entry) },
            )
        }
    }
}

@Composable
private fun NetworkScanEntryCard(
    entry: NetworkScanEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val title = entry.ssid ?: stringResource(R.string.network_hidden_ssid)
    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        background = if (selected) EchoColors.Sunken else EchoColors.Glass,
        border = if (selected) EchoColors.Live else EchoColors.Hair,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EchoText(
                    value = title,
                    color = EchoColors.Ink,
                    style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EchoText(
                    value = stringResource(
                        R.string.network_scan_entry_meta,
                        networkSecurityLabel(entry.security),
                        entry.signalDbm,
                        booleanLabel(entry.credentialRequired),
                    ),
                    color = EchoColors.InkMuted,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                )
            }
            StatusChip(
                if (selected) stringResource(R.string.network_selected) else stringResource(R.string.network_select),
                if (selected) EchoColors.Live else EchoColors.InkMuted,
            )
        }
    }
}

@Composable
private fun NetworkTransactionBlock(
    title: String,
    transaction: NetworkTransaction?,
) {
    if (transaction == null) {
        InfoBlock(
            title = title,
            body = stringResource(R.string.network_transaction_none),
            accent = EchoColors.InkMuted,
        )
        return
    }
    val body = listOf(
        stringResource(
            R.string.network_transaction_status,
            networkOperationLabel(transaction.operation),
            networkTransactionStatusLabel(transaction.status),
            networkTransactionStageLabel(transaction.stage),
        ),
        stringResource(R.string.network_transaction_id, transaction.transactionId),
        stringResource(R.string.network_transaction_desired, networkDesiredStateText(transaction.desired)),
        stringResource(R.string.source_revision, transaction.sourceRevision),
        stringResource(R.string.network_recovery_action, networkRecoveryActionLabel(transaction.recoveryAction)),
        stringResource(
            R.string.network_rescue_status,
            booleanLabel(transaction.rescue.apValidated),
            transaction.rescue.failureTriggerSeconds,
        ),
        transaction.deadline?.let {
            stringResource(R.string.network_deadline, it.remainingSeconds)
        },
        transaction.error?.let {
            stringResource(R.string.network_transaction_error, it.code, it.message, booleanLabel(it.retryable))
        },
    ).filterNotNull().joinToString("\n")
    InfoBlock(
        title = title,
        body = body,
        accent = networkTransactionColor(transaction.status),
        liveRegionMode = LiveRegionMode.Polite,
    )
}

@Composable
private fun NetworkMessageBlock(message: NetworkMessage) {
    InfoBlock(
        title = stringResource(R.string.network_message_title),
        body = networkMessageBody(message),
        accent = networkMessageColor(message),
        liveRegionMode = LiveRegionMode.Polite,
    )
}

@Composable
private fun networkInterfaceLine(
    label: String,
    status: NetworkInterfaceRuntime,
): String {
    val interfaceName = status.interfaceName ?: stringResource(R.string.value_none)
    val addresses = status.addresses.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ?: stringResource(R.string.network_addresses_none)
    val peerOrSsid = status.peerOrSsid ?: stringResource(R.string.network_peer_none)
    return stringResource(
        R.string.network_interface_line,
        label,
        networkStateLabel(status.state),
        interfaceName,
        addresses,
        peerOrSsid,
    )
}

@Composable
private fun networkRouteLabel(value: String): String {
    return when (value) {
        "wifi_client" -> stringResource(R.string.network_route_wifi_client)
        "wired" -> stringResource(R.string.network_route_wired)
        "none" -> stringResource(R.string.network_route_none)
        else -> value
    }
}

@Composable
private fun networkStateLabel(value: String): String {
    return when (value) {
        "disabled" -> stringResource(R.string.network_state_disabled)
        "disconnected" -> stringResource(R.string.network_state_disconnected)
        "starting" -> stringResource(R.string.network_state_starting)
        "connecting" -> stringResource(R.string.network_state_connecting)
        "connected" -> stringResource(R.string.network_state_connected)
        "active" -> stringResource(R.string.network_state_active)
        "degraded" -> stringResource(R.string.network_state_degraded)
        "failed" -> stringResource(R.string.network_state_failed)
        "unavailable" -> stringResource(R.string.network_state_unavailable)
        else -> value
    }
}

@Composable
private fun booleanLabel(value: Boolean): String {
    return stringResource(if (value) R.string.value_yes else R.string.value_no)
}

@Composable
private fun networkDesiredStateText(desired: NetworkDesiredState): String {
    return when (desired.mode) {
        "hotspot" -> stringResource(R.string.network_desired_hotspot)
        "wifi-client" -> {
            val wifi = desired.wifiClient
            if (wifi == null) {
                stringResource(R.string.network_desired_invalid)
            } else {
                stringResource(
                    R.string.network_desired_wifi,
                    wifi.ssid,
                    networkSecurityLabel(wifi.security),
                    networkCredentialStateLabel(wifi.credentialState),
                )
            }
        }
        "ethernet-dhcp" -> stringResource(R.string.network_desired_ethernet_dhcp)
        "ethernet-static" -> {
            val ipv4 = desired.ethernet?.staticIpv4
            if (ipv4 == null) {
                stringResource(R.string.network_desired_invalid)
            } else {
                stringResource(
                    R.string.network_desired_ethernet_static,
                    ipv4.address,
                    ipv4.prefixLength,
                    ipv4.gateway ?: stringResource(R.string.value_none),
                    ipv4.dns.joinToString(", ").ifBlank { stringResource(R.string.value_none) },
                )
            }
        }
        else -> desired.mode
    }
}

@Composable
private fun networkObservedStateText(observed: NetworkObservedState): String {
    return listOf(
        stringResource(R.string.network_default_route, networkRouteLabel(observed.defaultRoute)),
        networkInterfaceLine(stringResource(R.string.network_interface_ap), observed.ap),
        networkInterfaceLine(stringResource(R.string.network_interface_wifi_client), observed.wifiClient),
        networkInterfaceLine(stringResource(R.string.network_interface_wired), observed.wired),
        stringResource(R.string.network_devices_count, observed.devices.size),
    ).joinToString("\n")
}

@Composable
private fun networkMutationCapabilityText(status: NetworkStatus): String {
    val capability = status.mutationCapability
    val disabledReason = capability.disabledReason?.let { networkMutationDisabledReasonLabel(it) }
        ?: stringResource(R.string.value_none)
    return stringResource(
        R.string.network_mutation_capability_value,
        booleanLabel(capability.enabled),
        disabledReason,
        capability.operations.joinToString(", "),
        networkConcurrencyLabel(status.concurrencyCapability.samePhyApSta),
        status.concurrencyCapability.exclusiveClientFailureTimeoutSeconds,
    )
}

@Composable
private fun networkMutationDisabledReason(
    networkStatus: NetworkStatus?,
    captureState: String?,
    mutationEnabledByDescriptor: Boolean,
    commandRunning: Boolean,
): String? {
    return when {
        commandRunning -> stringResource(R.string.network_disabled_command_running)
        networkStatus == null -> stringResource(R.string.network_disabled_status_missing)
        !mutationEnabledByDescriptor -> stringResource(R.string.network_disabled_descriptor)
        captureState == null -> stringResource(R.string.network_disabled_capture_status_missing)
        captureState != "idle" -> stringResource(R.string.network_disabled_capture_active)
        !networkStatus.mutationCapability.enabled -> {
            networkStatus.mutationCapability.disabledReason?.let { networkMutationDisabledReasonLabel(it) }
                ?: stringResource(R.string.network_disabled_capability)
        }
        else -> null
    }
}

@Composable
private fun networkApplyDisabledReason(
    baseReason: String?,
    mode: String,
    manualSsid: String,
    selectedNetwork: NetworkScanEntry?,
    security: String,
    passphrase: String,
    staticAddress: String,
    staticPrefixLength: String,
    staticGateway: String,
    staticDns: String,
): String {
    if (baseReason != null) return baseReason
    return when (mode) {
        "wifi-client" -> {
            val effectiveSsid = (selectedNetwork?.ssid ?: manualSsid).trim()
            when {
                effectiveSsid.isBlank() -> stringResource(R.string.network_disabled_ssid)
                effectiveSsid.toByteArray(Charsets.UTF_8).size > 32 -> stringResource(R.string.network_disabled_ssid_bytes)
                security != "open" && passphrase.length !in 8..63 -> stringResource(R.string.network_disabled_passphrase)
                else -> stringResource(R.string.network_ready_to_apply)
            }
        }
        "hotspot" -> stringResource(R.string.network_ready_to_apply_hotspot)
        "ethernet-dhcp" -> stringResource(R.string.network_ready_to_apply_ethernet_dhcp)
        "ethernet-static" -> {
            val address = staticAddress.trim()
            val prefix = staticPrefixLength.trim().toIntOrNull()
            val gateway = staticGateway.trim().ifBlank { null }
            val dns = parseNetworkStaticDnsInput(staticDns)
            when {
                address.isBlank() -> stringResource(R.string.network_disabled_static_address)
                !isIpv4Address(address) -> stringResource(R.string.network_disabled_static_address)
                prefix == null || prefix !in 1..32 -> stringResource(R.string.network_disabled_static_prefix)
                gateway != null && !isIpv4Address(gateway) -> stringResource(R.string.network_disabled_static_gateway)
                dns.size > 3 -> stringResource(R.string.network_disabled_static_dns_count)
                dns.distinct().size != dns.size -> stringResource(R.string.network_disabled_static_dns_duplicate)
                dns.any { !isIpv4Address(it) } -> stringResource(R.string.network_disabled_static_dns)
                else -> stringResource(R.string.network_ready_to_apply_ethernet_static)
            }
        }
        else -> stringResource(R.string.network_invalid_desired_state)
    }
}

private fun parseNetworkStaticDnsInput(value: String): List<String> {
    return value
        .split(",", " ", "\n", "\t", ";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun isNetworkStaticDnsListValid(values: List<String>): Boolean {
    return values.size <= 3 &&
        values.distinct().size == values.size &&
        values.all(::isIpv4Address)
}

private fun isIpv4Address(value: String): Boolean {
    val parts = value.split(".")
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all { it.isDigit() } &&
            part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

private fun applyNetworkStreamEvent(
    current: NetworkStatus?,
    event: NetworkStreamEvent,
): NetworkStatus? {
    if (event.status != null) return event.status
    val transaction = event.transaction ?: return current
    return applyNetworkTransaction(current, transaction)
}

private fun applyNetworkTransaction(
    current: NetworkStatus?,
    transaction: NetworkTransaction,
): NetworkStatus? {
    if (current == null) return null
    val nextWindow = if (transaction.status in setOf("accepted", "running")) {
        current.transaction.copy(current = transaction, latest = transaction)
    } else {
        current.transaction.copy(current = null, latest = transaction)
    }
    return current.copy(
        authorityEpoch = transaction.authorityEpoch,
        sourceRevision = transaction.sourceRevision,
        transaction = nextWindow,
    )
}

private fun pendingNetworkMutationMessage(
    current: NetworkStatus?,
    detail: String,
): NetworkMessage {
    val transaction = current?.transaction?.current
    return if (transaction != null && transaction.status in setOf("accepted", "running")) {
        NetworkMessage.MutationResultPending(
            recoveryAction = transaction.recoveryAction,
            detail = detail,
        )
    } else {
        NetworkMessage.NetworkFailure(detail)
    }
}

private fun handleNetworkMutationResult(
    result: NetworkMutationResult,
    onAccepted: (NetworkTransactionReceipt) -> Unit,
    onMessage: (NetworkMessage) -> Unit,
) {
    when (result) {
        is NetworkMutationResult.Accepted -> onAccepted(result.value)
        NetworkMutationResult.AuthenticationRequired -> onMessage(NetworkMessage.AuthRequired)
        NetworkMutationResult.Forbidden -> onMessage(NetworkMessage.Forbidden)
        is NetworkMutationResult.HttpFailure -> onMessage(NetworkMessage.HttpFailure(result.statusCode))
        NetworkMutationResult.IdempotencyConflict -> onMessage(NetworkMessage.IdempotencyConflict)
        NetworkMutationResult.InvalidDesiredState -> onMessage(NetworkMessage.InvalidDesiredState)
        is NetworkMutationResult.InvalidRequest -> onMessage(NetworkMessage.InvalidRequest(result.message))
        is NetworkMutationResult.InvalidResponse -> onMessage(NetworkMessage.InvalidResponse(result.message))
        NetworkMutationResult.MutationUnavailable -> onMessage(NetworkMessage.MutationUnavailable)
        is NetworkMutationResult.NetworkFailure -> onMessage(NetworkMessage.NetworkFailure(result.message))
        NetworkMutationResult.NotFound -> onMessage(NetworkMessage.TransactionNotFound)
    }
}

@Composable
private fun networkMessageBody(message: NetworkMessage): String {
    return when (message) {
        NetworkMessage.StatusLoading -> stringResource(R.string.network_status_loading)
        NetworkMessage.StatusUnavailable -> stringResource(R.string.network_status_unavailable)
        NetworkMessage.ScanRunning -> stringResource(R.string.network_scan_running)
        is NetworkMessage.ScanLoaded -> stringResource(R.string.network_scan_loaded, message.count)
        NetworkMessage.ScanUnavailable -> stringResource(R.string.network_scan_unavailable)
        NetworkMessage.MutationRunning -> stringResource(R.string.network_mutation_running)
        is NetworkMessage.MutationAccepted -> stringResource(R.string.network_mutation_accepted, message.transactionId)
        is NetworkMessage.MutationResultPending -> stringResource(
            R.string.network_mutation_result_pending,
            networkRecoveryActionLabel(message.recoveryAction),
            message.detail,
        )
        NetworkMessage.MutationUnavailable -> stringResource(R.string.network_mutation_unavailable)
        NetworkMessage.IdempotencyConflict -> stringResource(R.string.network_idempotency_conflict)
        NetworkMessage.InvalidDesiredState -> stringResource(R.string.network_invalid_desired_state)
        NetworkMessage.TransactionNotFound -> stringResource(R.string.network_transaction_not_found)
        NetworkMessage.AuthRequired -> stringResource(R.string.network_auth_required)
        NetworkMessage.Forbidden -> stringResource(R.string.network_forbidden)
        is NetworkMessage.InvalidRequest -> stringResource(R.string.network_invalid_request, message.detail)
        is NetworkMessage.InvalidResponse -> stringResource(R.string.network_invalid_response, message.detail)
        is NetworkMessage.NetworkFailure -> stringResource(R.string.network_network_failure, message.detail)
        is NetworkMessage.HttpFailure -> stringResource(R.string.network_http_failure, message.statusCode)
    }
}

private fun networkMessageColor(message: NetworkMessage): Color {
    return when (message) {
        is NetworkMessage.ScanLoaded,
        is NetworkMessage.MutationAccepted,
        -> EchoColors.Permit
        NetworkMessage.StatusLoading,
        NetworkMessage.ScanRunning,
        NetworkMessage.MutationRunning,
        -> EchoColors.Live
        is NetworkMessage.MutationResultPending -> EchoColors.Caution
        else -> EchoColors.Caution
    }
}

@Composable
private fun networkSecurityLabel(value: String): String {
    return when (value) {
        "open" -> stringResource(R.string.network_security_open)
        "wpa2-personal" -> stringResource(R.string.network_security_wpa2)
        "wpa3-personal" -> stringResource(R.string.network_security_wpa3)
        "wpa2-wpa3-personal" -> stringResource(R.string.network_security_wpa2_wpa3)
        else -> value
    }
}

@Composable
private fun networkCredentialStateLabel(value: String): String {
    return when (value) {
        "absent" -> stringResource(R.string.network_credential_absent)
        "pending_input" -> stringResource(R.string.network_credential_pending)
        "stored" -> stringResource(R.string.network_credential_stored)
        else -> value
    }
}

@Composable
private fun networkMutationDisabledReasonLabel(value: String): String {
    return when (value) {
        "not_enabled" -> stringResource(R.string.network_mutation_reason_not_enabled)
        "auth_profile_unavailable" -> stringResource(R.string.network_mutation_reason_auth_profile_unavailable)
        "controller_unavailable" -> stringResource(R.string.network_mutation_reason_controller_unavailable)
        "network_manager_unavailable" -> stringResource(R.string.network_mutation_reason_network_manager_unavailable)
        "rescue_ap_not_validated" -> stringResource(R.string.network_mutation_reason_rescue_ap_not_validated)
        "capture_active" -> stringResource(R.string.network_mutation_reason_capture_active)
        "recovery_required" -> stringResource(R.string.network_mutation_reason_recovery_required)
        "maintenance_window_closed" -> stringResource(R.string.network_mutation_reason_maintenance_window_closed)
        "unsupported_concurrency" -> stringResource(R.string.network_mutation_reason_unsupported_concurrency)
        else -> value
    }
}

@Composable
private fun networkConcurrencyLabel(value: String): String {
    return when (value) {
        "supported" -> stringResource(R.string.network_concurrency_supported)
        "unsupported" -> stringResource(R.string.network_concurrency_unsupported)
        "unverified" -> stringResource(R.string.network_concurrency_unverified)
        else -> value
    }
}

@Composable
private fun networkOperationLabel(value: String): String {
    return when (value) {
        "apply" -> stringResource(R.string.network_operation_apply)
        "retry" -> stringResource(R.string.network_operation_retry)
        "forget" -> stringResource(R.string.network_operation_forget)
        else -> value
    }
}

@Composable
private fun networkTransactionStatusLabel(value: String): String {
    return when (value) {
        "accepted" -> stringResource(R.string.network_transaction_accepted)
        "running" -> stringResource(R.string.network_transaction_running)
        "committed" -> stringResource(R.string.network_transaction_committed)
        "rescued" -> stringResource(R.string.network_transaction_rescued)
        "failed" -> stringResource(R.string.network_transaction_failed)
        else -> value
    }
}

@Composable
private fun networkTransactionStageLabel(value: String): String {
    return when (value) {
        "accepted" -> stringResource(R.string.network_stage_accepted)
        "prepared" -> stringResource(R.string.network_stage_prepared)
        "ap_ready" -> stringResource(R.string.network_stage_ap_ready)
        "activating" -> stringResource(R.string.network_stage_activating)
        "verifying" -> stringResource(R.string.network_stage_verifying)
        "committed" -> stringResource(R.string.network_stage_committed)
        "falling_back" -> stringResource(R.string.network_stage_falling_back)
        "rescued" -> stringResource(R.string.network_stage_rescued)
        "failed" -> stringResource(R.string.network_stage_failed)
        "forgetting" -> stringResource(R.string.network_stage_forgetting)
        "forgotten" -> stringResource(R.string.network_stage_forgotten)
        else -> value
    }
}

@Composable
private fun networkRecoveryActionLabel(value: String): String {
    return when (value) {
        "await_device" -> stringResource(R.string.network_recovery_await_device)
        "reconnect_target_lan" -> stringResource(R.string.network_recovery_reconnect_target_lan)
        "reconnect_rescue_ap" -> stringResource(R.string.network_recovery_reconnect_rescue_ap)
        "retry" -> stringResource(R.string.network_recovery_retry)
        "service_required" -> stringResource(R.string.network_recovery_service_required)
        "none" -> stringResource(R.string.network_recovery_none)
        else -> value
    }
}

private fun networkTransactionColor(status: String): Color {
    return when (status) {
        "accepted",
        "running",
        -> EchoColors.Live
        "committed" -> EchoColors.Permit
        "rescued",
        "failed",
        -> EchoColors.Caution
        else -> EchoColors.InkMuted
    }
}

@Composable
private fun imuQualityLabel(value: String): String {
    return when (value) {
        "insufficient" -> stringResource(R.string.imu_quality_insufficient)
        "degraded" -> stringResource(R.string.imu_quality_degraded)
        "good" -> stringResource(R.string.imu_quality_good)
        else -> value
    }
}

private fun imuQualityColor(value: String): Color {
    return when (value) {
        "good" -> EchoColors.Permit
        "degraded",
        "insufficient",
        -> EchoColors.Caution
        else -> EchoColors.InkMuted
    }
}

@Composable
private fun BodyScreen(
    bodyConnection: DeviceConnection?,
    captureStatus: CaptureStatusSnapshot?,
    captureMessage: CaptureStatusMessage?,
    captureCommandMessage: CaptureCommandMessage?,
    safeSwapReceipt: SafeSwapReceiptSummary?,
    safeSwapMessage: SafeSwapMessage?,
    cameraFocus: CameraFocusStatus?,
    cameraFocusMessage: CameraFocusMessage?,
    captureCommandRunning: Boolean,
    cameraFocusCommandRunning: Boolean,
    onStartCalibrationCapture: () -> Unit,
    onRequestSafeSwap: () -> Unit,
    onSetCameraFocus: (Long?, Boolean?) -> Unit,
    localeTag: String,
    updateState: AppUpdateManager.State,
    onDisconnect: () -> Unit,
    onLocaleChange: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var confirmSafeSwap by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = confirmDisconnect || confirmSafeSwap) {
        confirmDisconnect = false
        confirmSafeSwap = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel(stringResource(R.string.body_decisions))
                InfoBlock(
                    title = bodyConnection?.descriptor?.deviceLabel ?: stringResource(R.string.status_no_body),
                    body = if (bodyConnection == null) {
                        stringResource(R.string.body_not_ready)
                    } else {
                        stringResource(R.string.body_connected)
                    },
                    accent = if (bodyConnection == null) EchoColors.InkMuted else EchoColors.Permit,
                )
                if (bodyConnection != null) {
                    InfoBlock(
                        title = stringResource(R.string.body_origin),
                        body = bodyConnection.origin,
                        accent = EchoColors.InkSecondary,
                    )
                    InfoBlock(
                        title = stringResource(R.string.device_identity),
                        body = stringResource(
                            R.string.device_identity_body,
                            bodyConnection.descriptor.deviceId,
                            bodyConnection.descriptor.hardwareFingerprint,
                        ),
                        accent = EchoColors.Live,
                    )
                    InfoBlock(
                        title = stringResource(R.string.device_build),
                        body = stringResource(
                            R.string.device_build_body,
                            bodyConnection.descriptor.packageVersion,
                            bodyConnection.descriptor.commit.take(12),
                            bodyConnection.descriptor.buildId,
                        ),
                        accent = EchoColors.InkSecondary,
                    )
                    InfoBlock(
                        title = stringResource(R.string.security_profile),
                        body = bodyConnection.descriptor.securityProfile,
                        accent = EchoColors.InkSecondary,
                    )
                    InfoBlock(
                        title = stringResource(R.string.capabilities_status),
                        body = stringResource(
                            R.string.capabilities_value,
                            capabilityLabel(bodyConnection.descriptor.captureCapable),
                            capabilityLabel(bodyConnection.descriptor.previewCapable),
                            capabilityLabel(bodyConnection.descriptor.rangeDownloadCapable),
                            capabilityLabel(bodyConnection.descriptor.networkMutationCapable),
                        ),
                        accent = if (bodyConnection.descriptor.networkMutationCapable) EchoColors.Caution else EchoColors.Permit,
                    )
                    InfoBlock(
                        title = stringResource(R.string.camera_connection),
                        body = cameraConnectionLabel(bodyConnection.descriptor.runtime.camera.state),
                        accent = if (bodyConnection.descriptor.runtime.camera.state == "connected") {
                            EchoColors.Permit
                        } else {
                            EchoColors.Caution
                        },
                    )
                    InfoBlock(
                        title = stringResource(R.string.calibration_capture),
                        body = calibrationCaptureText(bodyConnection.descriptor.calibrationCapture),
                        accent = if (bodyConnection.descriptor.calibrationCapture.enabled) EchoColors.Permit else EchoColors.Caution,
                    )
                    ActionButton(
                        label = stringResource(R.string.calibration_start),
                        enabled = !captureCommandRunning &&
                            bodyConnection.descriptor.calibrationCapture.enabled &&
                            isCameraConnected(bodyConnection, captureStatus) &&
                            bodyConnection.descriptor.writable &&
                            captureStatus?.deviceState == "idle",
                        disabledReason = calibrationStartDisabledReason(
                            bodyConnection = bodyConnection,
                            captureStatus = captureStatus,
                            captureCommandRunning = captureCommandRunning,
                        ),
                        onClick = onStartCalibrationCapture,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    InfoBlock(
                        title = stringResource(R.string.storage_status),
                        body = stringResource(
                            R.string.storage_bytes,
                            bodyConnection.descriptor.availableBytes,
                            bodyConnection.descriptor.totalBytes,
                        ),
                        accent = if (bodyConnection.descriptor.writable) EchoColors.Permit else EchoColors.Caution,
                    )
                    captureStatus?.let { snapshot ->
                        InfoBlock(
                            title = stringResource(R.string.capture_status_title),
                            body = "${deviceStateLabel(snapshot.deviceState)} · ${stringResource(R.string.source_revision, snapshot.sourceRevision)}",
                            accent = deviceStateColor(snapshot.deviceState),
                        )
                    }
                    if (captureStatus == null && captureMessage != null) {
                        CaptureStatusMessageBlock(captureMessage)
                    }
                    captureCommandMessage?.let { CaptureCommandMessageBlock(it) }
                    ActionButton(
                        label = stringResource(R.string.safe_swap_request),
                        enabled = !captureCommandRunning && captureStatus?.deviceState == "recording",
                        disabledReason = safeSwapRequestDisabledReason(
                            bodyConnection = bodyConnection,
                            captureStatus = captureStatus,
                            captureCommandRunning = captureCommandRunning,
                        ),
                        onClick = { confirmSafeSwap = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (confirmSafeSwap) {
                        ConfirmationBlock(
                            title = stringResource(R.string.safe_swap_confirm_title),
                            body = stringResource(R.string.safe_swap_confirm_body),
                            confirmLabel = stringResource(R.string.safe_swap_confirm_action),
                            onCancel = { confirmSafeSwap = false },
                            onConfirm = {
                                confirmSafeSwap = false
                                onRequestSafeSwap()
                            },
                        )
                    }
                    SafeSwapBlock(
                        receipt = safeSwapReceipt,
                        message = safeSwapMessage,
                    )
                    CameraFocusCard(
                        focus = cameraFocus,
                        message = cameraFocusMessage,
                        commandRunning = cameraFocusCommandRunning,
                        onSetFocus = onSetCameraFocus,
                    )
                    ActionButton(
                        label = stringResource(R.string.disconnect_body),
                        enabled = true,
                        onClick = { confirmDisconnect = true },
                    )
                    if (confirmDisconnect) {
                        ConfirmationBlock(
                            title = stringResource(R.string.disconnect_confirm_title),
                            body = stringResource(R.string.disconnect_confirm_body),
                            confirmLabel = stringResource(R.string.disconnect_confirm_action),
                            onCancel = { confirmDisconnect = false },
                            onConfirm = {
                                confirmDisconnect = false
                                onDisconnect()
                            },
                        )
                    }
                }
            }
        }
        LanguageCard(localeTag, onLocaleChange)
        UpdateCard(updateState, onCheckUpdate, onInstallUpdate)
    }
}

@Composable
private fun SessionSummaryCard(
    summary: SessionSummary,
    isManifestVisible: Boolean,
    isLoadingManifest: Boolean,
    unsuccessfulOutcome: RetainedUnsuccessfulOutcome?,
    unsuccessfulOutcomeMessage: UnsuccessfulOutcomeMessage?,
    isLoadingUnsuccessfulOutcome: Boolean,
    artifactDownloadingId: String?,
    onCancelDownload: () -> Unit,
    onLoadManifest: () -> Unit,
    onLoadUnsuccessfulOutcome: () -> Unit,
    onDownloadArtifact: (ArtifactDescriptor) -> Unit,
    manifest: DeviceSessionManifest?,
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EchoText(
                    value = summary.displayName,
                    color = EchoColors.Ink,
                    style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(
                    stringResource(
                        R.string.session_producer_outcome,
                        producerOutcomeLabel(summary.producerOutcome),
                    ),
                    EchoColors.Permit,
                )
            }
            EchoText(
                value = summary.sessionId,
                color = EchoColors.InkMuted,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusChipGroup {
                StatusChip(summary.deviceLabel, EchoColors.Live)
                StatusChip(
                    summary.verificationVerdict?.let { verdict ->
                        stringResource(R.string.session_gateway_verdict, gatewayVerdictLabel(verdict))
                    } ?: stringResource(R.string.session_no_verification),
                    if (summary.verificationVerdict == "usable") EchoColors.Permit else EchoColors.Caution,
                )
                StatusChip(stringResource(R.string.session_duration, summary.durationSeconds), EchoColors.InkSecondary)
            }
            EchoText(
                value = stringResource(R.string.session_total_bytes, summary.totalBytes),
                color = EchoColors.InkSecondary,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            )
            EchoText(
                value = "${summary.startedAt} → ${summary.endedAt}",
                color = EchoColors.InkMuted,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = stringResource(R.string.load_artifacts),
                    enabled = !isLoadingManifest,
                    disabledReason = stringResource(R.string.session_manifest_loading),
                    onClick = onLoadManifest,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = if (isLoadingUnsuccessfulOutcome) {
                        stringResource(R.string.unsuccessful_outcome_loading)
                    } else {
                        stringResource(R.string.unsuccessful_outcome_load)
                    },
                    enabled = !isLoadingUnsuccessfulOutcome,
                    disabledReason = stringResource(R.string.unsuccessful_outcome_loading),
                    onClick = onLoadUnsuccessfulOutcome,
                    modifier = Modifier.weight(1f),
                )
            }
            if (unsuccessfulOutcome != null || unsuccessfulOutcomeMessage != null) {
                SectionLabel(stringResource(R.string.unsuccessful_outcome_title))
                UnsuccessfulOutcomeBlock(
                    outcome = unsuccessfulOutcome,
                    message = unsuccessfulOutcomeMessage,
                )
            }
            if (isManifestVisible && manifest != null) {
                SectionLabel(stringResource(R.string.artifacts))
                if (manifest.artifacts.isEmpty()) {
                    EchoText(
                        value = stringResource(R.string.artifacts_empty),
                        color = EchoColors.InkMuted,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    )
                }
                manifest.artifacts.forEach { artifact ->
                    ArtifactRow(
                        artifact = artifact,
                        canDownload = summary.verificationVerdict == "usable" && artifactDownloadingId == null,
                        isDownloading = artifactDownloadingId == artifact.artifactId,
                        onDownload = { onDownloadArtifact(artifact) },
                        onCancel = onCancelDownload,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactRow(
    artifact: ArtifactDescriptor,
    canDownload: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            EchoText(
                value = artifact.role,
                color = EchoColors.Ink,
                style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EchoText(
                value = stringResource(R.string.artifact_meta, artifact.mediaType, artifact.bytes),
                color = EchoColors.InkMuted,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ActionButton(
            label = if (isDownloading) stringResource(R.string.cancel_artifact_download) else stringResource(R.string.download_artifact),
            enabled = isDownloading || canDownload,
            disabledReason = stringResource(R.string.artifact_download_disabled_verification),
            onClick = if (isDownloading) onCancel else onDownload,
            modifier = Modifier.size(width = 86.dp, height = 48.dp),
        )
    }
}

private const val SESSION_FILTER_ALL = "all"
private const val SESSION_FILTER_AVAILABLE = "available"
private const val SESSION_FILTER_UNSUCCESSFUL = "unsuccessful"

private fun appendSessionPage(
    current: SessionListPage,
    next: SessionListPage,
): SessionListPage {
    val bySessionId = LinkedHashMap<String, SessionSummary>()
    (current.items + next.items).forEach { summary ->
        bySessionId[summary.sessionId] = summary
    }
    return SessionListPage(
        items = bySessionId.values.toList(),
        diagnosticsCount = current.diagnosticsCount + next.diagnosticsCount,
        nextCursor = next.nextCursor,
    )
}

private fun sessionMatchesFilter(summary: SessionSummary, filter: String): Boolean {
    return when (filter) {
        SESSION_FILTER_AVAILABLE -> summary.verificationVerdict == "usable"
        SESSION_FILTER_UNSUCCESSFUL -> summary.producerOutcome != "sealed" || summary.verificationVerdict == "unusable"
        else -> true
    }
}

private fun mergeSessionRefresh(
    current: SessionListPage?,
    refreshed: SessionListPage,
): SessionListPage {
    current ?: return refreshed
    val bySessionId = LinkedHashMap<String, SessionSummary>()
    (refreshed.items + current.items).forEach { summary ->
        bySessionId[summary.sessionId] = summary
    }
    val shouldPreserveLoadedTail = current.items.size > refreshed.items.size
    return refreshed.copy(
        items = bySessionId.values.toList(),
        diagnosticsCount = maxOf(current.diagnosticsCount, refreshed.diagnosticsCount),
        nextCursor = if (shouldPreserveLoadedTail) current.nextCursor else refreshed.nextCursor,
    )
}

private fun sessionMessageFor(result: SessionListResult): SessionMessage? {
    return when (result) {
        is SessionListResult.Page -> null
        SessionListResult.AuthenticationRequired -> SessionMessage.AuthRequired
        SessionListResult.Forbidden -> SessionMessage.Forbidden
        is SessionListResult.HttpFailure -> SessionMessage.HttpFailure(result.statusCode)
        SessionListResult.InvalidRequest -> SessionMessage.InvalidRequest
        is SessionListResult.InvalidResponse -> SessionMessage.InvalidResponse(result.message)
        is SessionListResult.NetworkFailure -> SessionMessage.NetworkFailure(result.message)
    }
}

@Composable
private fun UnsuccessfulOutcomeBlock(
    outcome: RetainedUnsuccessfulOutcome?,
    message: UnsuccessfulOutcomeMessage?,
) {
    val (body, accent) = when {
        outcome != null -> stringResource(
            R.string.unsuccessful_outcome_body,
            unsuccessfulOutcomeStateLabel(outcome.state),
            outcome.sourceRevision,
            outcome.authorityEpoch,
            outcome.generationId,
        ) to EchoColors.Caution
        message != null -> unsuccessfulOutcomeMessageBody(message) to when (message) {
            UnsuccessfulOutcomeMessage.NotFound -> EchoColors.InkMuted
            UnsuccessfulOutcomeMessage.Loading -> EchoColors.Live
            else -> EchoColors.Caution
        }
        else -> return
    }
    InfoBlock(
        title = stringResource(R.string.unsuccessful_outcome_title),
        body = body,
        accent = accent,
        liveRegionMode = LiveRegionMode.Polite,
    )
}

@Composable
private fun unsuccessfulOutcomeMessageBody(message: UnsuccessfulOutcomeMessage): String {
    return when (message) {
        UnsuccessfulOutcomeMessage.Loading -> stringResource(R.string.unsuccessful_outcome_loading_body)
        UnsuccessfulOutcomeMessage.NotFound -> stringResource(R.string.unsuccessful_outcome_not_found)
        UnsuccessfulOutcomeMessage.AuthRequired -> stringResource(R.string.unsuccessful_outcome_auth_required)
        UnsuccessfulOutcomeMessage.Forbidden -> stringResource(R.string.unsuccessful_outcome_forbidden)
        is UnsuccessfulOutcomeMessage.HttpFailure -> {
            stringResource(R.string.unsuccessful_outcome_http_failure, message.statusCode)
        }
        is UnsuccessfulOutcomeMessage.InvalidRequest -> {
            stringResource(R.string.unsuccessful_outcome_invalid_request, message.detail)
        }
        is UnsuccessfulOutcomeMessage.InvalidResponse -> {
            stringResource(R.string.unsuccessful_outcome_invalid_response, message.detail)
        }
        is UnsuccessfulOutcomeMessage.NetworkFailure -> {
            stringResource(R.string.unsuccessful_outcome_network_failure, message.detail)
        }
    }
}

@Composable
private fun unsuccessfulOutcomeStateLabel(value: String): String {
    return when (value) {
        "recoverable" -> stringResource(R.string.unsuccessful_outcome_state_recoverable)
        "failed" -> stringResource(R.string.unsuccessful_outcome_state_failed)
        "abandoned" -> stringResource(R.string.unsuccessful_outcome_state_abandoned)
        else -> value
    }
}

@Composable
private fun producerOutcomeLabel(value: String): String {
    return when (value) {
        "sealed" -> stringResource(R.string.session_producer_outcome_sealed)
        else -> value
    }
}

@Composable
private fun gatewayVerdictLabel(value: String): String {
    return when (value) {
        "usable" -> stringResource(R.string.session_gateway_verdict_usable)
        "unusable" -> stringResource(R.string.session_gateway_verdict_unusable)
        else -> value
    }
}

@Composable
private fun SessionMessageBlock(message: SessionMessage) {
    val body = when (message) {
        SessionMessage.AuthRequired -> stringResource(R.string.session_auth_required)
        SessionMessage.Forbidden -> stringResource(R.string.session_forbidden)
        is SessionMessage.HttpFailure -> stringResource(R.string.session_http_failure, message.statusCode)
        SessionMessage.InvalidRequest -> stringResource(R.string.session_invalid_request)
        is SessionMessage.InvalidResponse -> stringResource(R.string.session_invalid_response, message.detail)
        is SessionMessage.NetworkFailure -> stringResource(R.string.session_network_failure, message.detail)
    }
    Panel(
        modifier = Modifier.fillMaxWidth(),
        background = EchoColors.Caution.copy(alpha = 0.10f),
        border = EchoColors.Caution.copy(alpha = 0.48f),
    ) {
        InfoBlock(
            title = stringResource(R.string.nav_sessions),
            body = body,
            accent = EchoColors.Caution,
            modifier = Modifier.padding(12.dp),
            liveRegionMode = LiveRegionMode.Polite,
        )
    }
}

@Composable
private fun SessionManifestMessageBlock(message: SessionManifestMessage) {
    val (body, accent) = when (message) {
        SessionManifestMessage.Loading -> stringResource(R.string.session_manifest_loading) to EchoColors.Live
        SessionManifestMessage.AuthRequired -> stringResource(R.string.session_manifest_auth_required) to EchoColors.Caution
        SessionManifestMessage.Forbidden -> stringResource(R.string.session_manifest_forbidden) to EchoColors.Caution
        is SessionManifestMessage.HttpFailure -> {
            stringResource(R.string.session_manifest_http_failure, message.statusCode) to EchoColors.Caution
        }
        is SessionManifestMessage.InvalidRequest -> {
            stringResource(R.string.session_manifest_invalid_request, message.detail) to EchoColors.Caution
        }
        is SessionManifestMessage.InvalidResponse -> {
            stringResource(R.string.session_manifest_invalid_response, message.detail) to EchoColors.Caution
        }
        is SessionManifestMessage.NetworkFailure -> {
            stringResource(R.string.session_manifest_network_failure, message.detail) to EchoColors.Caution
        }
        SessionManifestMessage.NotFound -> stringResource(R.string.session_manifest_not_found) to EchoColors.Caution
    }
    Panel(
        modifier = Modifier.fillMaxWidth(),
        background = accent.copy(alpha = 0.10f),
        border = accent.copy(alpha = 0.48f),
    ) {
        InfoBlock(
            title = stringResource(R.string.artifacts),
            body = body,
            accent = accent,
            modifier = Modifier.padding(12.dp),
            liveRegionMode = LiveRegionMode.Polite,
        )
    }
}

@Composable
private fun ArtifactDownloadMessageBlock(message: ArtifactDownloadMessage) {
    val context = LocalContext.current
    val (body, accent) = when (message) {
        is ArtifactDownloadMessage.Running -> {
            stringResource(R.string.artifact_download_running, message.role) to EchoColors.Live
        }
        is ArtifactDownloadMessage.Cancelled -> {
            stringResource(R.string.artifact_download_cancelled, message.role) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.Saved -> {
            stringResource(R.string.artifact_download_saved, message.role, message.bytes, message.path) to EchoColors.Permit
        }
        is ArtifactDownloadMessage.AuthRequired -> {
            stringResource(R.string.artifact_download_auth_required, message.role) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.Forbidden -> {
            stringResource(R.string.artifact_download_forbidden, message.role) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.NotFound -> {
            stringResource(R.string.artifact_download_not_found, message.role) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.SessionNotVerified -> {
            stringResource(R.string.artifact_download_not_verified, message.role) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.CaptureBusy -> {
            stringResource(R.string.artifact_download_busy, message.role) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.RangeNotSatisfiable -> {
            stringResource(R.string.artifact_download_range, message.role) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.InvalidRequest -> {
            stringResource(R.string.artifact_download_invalid_request, message.detail) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.InvalidResponse -> {
            stringResource(R.string.artifact_download_invalid_response, message.detail) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.IntegrityFailure -> {
            stringResource(R.string.artifact_download_integrity, message.actualSha256) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.NetworkFailure -> {
            stringResource(R.string.artifact_download_network_failure, message.role, message.detail) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.HttpFailure -> {
            stringResource(R.string.artifact_download_http_failure, message.role, message.statusCode) to EchoColors.Caution
        }
        is ArtifactDownloadMessage.StoreFailed -> {
            stringResource(R.string.artifact_download_store_failed, message.role, message.detail) to EchoColors.Caution
        }
    }
    Panel(
        modifier = Modifier.fillMaxWidth(),
        background = accent.copy(alpha = 0.10f),
        border = accent.copy(alpha = 0.48f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoBlock(
                title = stringResource(R.string.download_artifact),
                body = body,
                accent = accent,
                liveRegionMode = LiveRegionMode.Polite,
            )
            if (message is ArtifactDownloadMessage.Saved && message.path.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        label = stringResource(R.string.artifact_open),
                        enabled = true,
                        onClick = { openArtifact(context, message.path, message.mediaType) },
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(
                        label = stringResource(R.string.artifact_share),
                        enabled = true,
                        onClick = { shareArtifact(context, message.path, message.mediaType) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun artifactDownloadMessageFor(role: String, result: ArtifactFileResult): ArtifactDownloadMessage {
    return when (result) {
        is ArtifactFileResult.Saved -> ArtifactDownloadMessage.Saved(role, result.path, result.bytes, result.mediaType)
        is ArtifactFileResult.Failed -> ArtifactDownloadMessage.StoreFailed(role, result.message)
        is ArtifactFileResult.DownloadRejected -> artifactDownloadMessageFor(role, result.reason)
    }
}

private fun artifactDownloadMessageFor(role: String, result: ArtifactDownloadResult): ArtifactDownloadMessage {
    return when (result) {
        ArtifactDownloadResult.AuthenticationRequired -> ArtifactDownloadMessage.AuthRequired(role)
        ArtifactDownloadResult.CaptureBusy -> ArtifactDownloadMessage.CaptureBusy(role)
        is ArtifactDownloadResult.Cancelled -> ArtifactDownloadMessage.Cancelled(role)
        ArtifactDownloadResult.Forbidden -> ArtifactDownloadMessage.Forbidden(role)
        is ArtifactDownloadResult.HttpFailure -> ArtifactDownloadMessage.HttpFailure(role, result.statusCode)
        is ArtifactDownloadResult.IntegrityFailure -> ArtifactDownloadMessage.IntegrityFailure(result.actualSha256)
        is ArtifactDownloadResult.InvalidRequest -> ArtifactDownloadMessage.InvalidRequest(result.message)
        is ArtifactDownloadResult.InvalidResponse -> ArtifactDownloadMessage.InvalidResponse(result.message)
        is ArtifactDownloadResult.NetworkFailure -> ArtifactDownloadMessage.NetworkFailure(role, result.message)
        ArtifactDownloadResult.NotFound -> ArtifactDownloadMessage.NotFound(role)
        ArtifactDownloadResult.RangeNotSatisfiable -> ArtifactDownloadMessage.RangeNotSatisfiable(role)
        ArtifactDownloadResult.SessionNotVerified -> ArtifactDownloadMessage.SessionNotVerified(role)
        is ArtifactDownloadResult.Downloaded -> ArtifactDownloadMessage.Saved(
            role = role,
            path = "",
            bytes = result.bytes,
            mediaType = "application/octet-stream",
        )
    }
}

@Composable
private fun SafeSwapBlock(
    receipt: SafeSwapReceiptSummary?,
    message: SafeSwapMessage?,
) {
    when {
        receipt != null -> InfoBlock(
            title = stringResource(R.string.safe_swap_title),
            body = listOf(
                stringResource(R.string.safe_swap_ready),
                stringResource(R.string.safe_swap_release_state, receipt.releaseState),
                stringResource(R.string.safe_swap_open_handles, receipt.openHandleCount),
                if (receipt.authorityEpoch != null && receipt.sourceRevision != null) {
                    stringResource(R.string.safe_swap_authority, receipt.authorityEpoch, receipt.sourceRevision)
                } else {
                    null
                },
                stringResource(R.string.safe_swap_manifest, receipt.manifestSha256),
            ).filterNotNull().joinToString("\n"),
            accent = EchoColors.Permit,
            liveRegionMode = LiveRegionMode.Polite,
        )
        message != null -> InfoBlock(
            title = stringResource(R.string.safe_swap_title),
            body = safeSwapMessageBody(message),
            accent = if (message == SafeSwapMessage.NotFound) EchoColors.InkMuted else EchoColors.Caution,
            liveRegionMode = LiveRegionMode.Polite,
        )
    }
}

@Composable
private fun safeSwapMessageBody(message: SafeSwapMessage): String {
    return when (message) {
        SafeSwapMessage.WaitingForReceipt -> stringResource(R.string.safe_swap_waiting_receipt)
        SafeSwapMessage.AuthRequired -> stringResource(R.string.safe_swap_auth_required)
        SafeSwapMessage.Forbidden -> stringResource(R.string.safe_swap_forbidden)
        is SafeSwapMessage.HttpFailure -> stringResource(R.string.safe_swap_http_failure, message.statusCode)
        is SafeSwapMessage.InvalidResponse -> stringResource(R.string.safe_swap_invalid_response, message.detail)
        is SafeSwapMessage.NetworkFailure -> stringResource(R.string.safe_swap_network_failure, message.detail)
        SafeSwapMessage.NotFound -> stringResource(R.string.safe_swap_not_found)
    }
}

@Composable
private fun CameraFocusCard(
    focus: CameraFocusStatus?,
    message: CameraFocusMessage?,
    commandRunning: Boolean,
    onSetFocus: (Long?, Boolean?) -> Unit,
) {
    var focusInput by rememberSaveable(focus?.minimum, focus?.maximum) {
        mutableStateOf(focus?.value?.toString().orEmpty())
    }
    InfoBlock(
        title = stringResource(R.string.camera_focus_title),
        body = when {
            focus != null -> stringResource(
                R.string.camera_focus_value,
                focus.value,
                focus.minimum,
                focus.maximum,
                focus.step,
                focus.default,
            )
            message != null -> cameraFocusMessageBody(message)
            else -> stringResource(R.string.camera_focus_disabled)
        },
        accent = when {
            focus != null -> EchoColors.Live
            message == CameraFocusMessage.Unsupported -> EchoColors.InkMuted
            else -> EchoColors.Caution
        },
        liveRegionMode = if (message != null) LiveRegionMode.Polite else null,
    )
    if (focus != null) {
        EchoText(
            value = when (focus.autoEnabled) {
                true -> stringResource(R.string.camera_focus_auto_on)
                false -> stringResource(R.string.camera_focus_auto_off)
                null -> stringResource(R.string.camera_focus_auto_unsupported)
            },
            color = EchoColors.InkSecondary,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        )
        InputField(
            value = focusInput,
            onValueChange = { value -> focusInput = value.filter { it.isDigit() }.take(6) },
            label = stringResource(R.string.camera_focus_input),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val requestedValue = focusInput.toLongOrNull()
            ActionButton(
                label = stringResource(R.string.camera_focus_apply),
                enabled = !commandRunning && requestedValue != null && requestedValue in focus.minimum..focus.maximum,
                disabledReason = stringResource(R.string.camera_focus_disabled),
                onClick = { onSetFocus(requestedValue, false) },
                modifier = Modifier.weight(1f),
            )
            if (focus.autoSupported) {
                val nextAuto = focus.autoEnabled != true
                ActionButton(
                    label = stringResource(
                        if (nextAuto) R.string.camera_focus_auto_enable else R.string.camera_focus_auto_disable,
                    ),
                    enabled = !commandRunning,
                    disabledReason = stringResource(R.string.camera_focus_running),
                    onClick = { onSetFocus(null, nextAuto) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    message?.let {
        EchoText(
            value = cameraFocusMessageBody(it),
            color = if (it in setOf(CameraFocusMessage.Updated, CameraFocusMessage.Running)) EchoColors.Permit else EchoColors.Caution,
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        )
    }
}

@Composable
private fun cameraFocusMessageBody(message: CameraFocusMessage): String {
    return when (message) {
        CameraFocusMessage.Running -> stringResource(R.string.camera_focus_running)
        CameraFocusMessage.Updated -> stringResource(R.string.camera_focus_updated)
        CameraFocusMessage.Unsupported -> stringResource(R.string.camera_focus_unsupported)
        CameraFocusMessage.AuthRequired -> stringResource(R.string.camera_focus_auth_required)
        CameraFocusMessage.Forbidden -> stringResource(R.string.camera_focus_forbidden)
        CameraFocusMessage.Conflict -> stringResource(R.string.camera_focus_conflict)
        CameraFocusMessage.InvalidFocus -> stringResource(R.string.camera_focus_invalid_focus)
        is CameraFocusMessage.HttpFailure -> stringResource(R.string.camera_focus_http_failure, message.statusCode)
        is CameraFocusMessage.InvalidRequest -> stringResource(R.string.camera_focus_invalid_request, message.detail)
        is CameraFocusMessage.InvalidResponse -> stringResource(R.string.camera_focus_invalid_response, message.detail)
        is CameraFocusMessage.NetworkFailure -> stringResource(R.string.camera_focus_network_failure, message.detail)
    }
}

@Composable
private fun LanguageCard(localeTag: String, onLocaleChange: (String) -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(stringResource(R.string.language))
            SegmentedControl(
                options = listOf(
                    "zh-CN" to stringResource(R.string.language_zh),
                    "en" to stringResource(R.string.language_en),
                ),
                selected = localeTag,
                onSelected = onLocaleChange,
            )
        }
    }
}

@Composable
private fun UpdateCard(
    state: AppUpdateManager.State,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    value = stringResource(R.string.update_title),
                    modifier = Modifier.weight(1f),
                )
                EchoText(
                    value = updateVersionLabel(state),
                    color = if (state.phase == AppUpdateManager.Phase.AVAILABLE) EchoColors.Live else EchoColors.InkMuted,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProgressBar(progress = updateProgress(state))
            EchoText(
                value = stringResource(updatePhaseString(state.phase)),
                color = updatePhaseColor(state.phase),
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            )
            if (state.phase == AppUpdateManager.Phase.FAILED) {
                EchoText(
                    value = stringResource(R.string.update_failed_diagnostics),
                    color = EchoColors.Caution,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = stringResource(R.string.update_check),
                    enabled = state.canCheck(),
                    disabledReason = stringResource(R.string.update_check_disabled_busy),
                    onClick = onCheckUpdate,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = stringResource(R.string.update_install),
                    enabled = state.canInstall(),
                    disabledReason = updateInstallDisabledReason(state),
                    onClick = onInstallUpdate,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomNavigation(
    selectedTab: EchoTab,
    onSelect: (EchoTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationDescription = stringResource(R.string.bottom_nav_description)
    Panel(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .semantics { contentDescription = navigationDescription },
        background = EchoColors.GlassStrong,
        radius = 8.dp,
    ) {
        Row(Modifier.fillMaxSize()) {
            EchoTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val tabStateDescription = stringResource(
                    if (selected) R.string.nav_selected else R.string.nav_not_selected,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(tab) },
                        )
                        .semantics {
                            stateDescription = tabStateDescription
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EchoText(
                            value = stringResource(tab.label),
                            color = if (selected) EchoColors.Ink else EchoColors.InkMuted,
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(5.dp))
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (selected) EchoColors.Record else Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    background: Color = EchoColors.Glass,
    border: Color = EchoColors.Hair,
    radius: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(radius)),
    ) {
        content()
    }
}

@Composable
private fun SectionLabel(value: String, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    EchoText(
        value = value.uppercase(locale),
        modifier = modifier,
        color = EchoColors.InkMuted,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun InfoBlock(
    title: String,
    body: String,
    accent: Color,
    modifier: Modifier = Modifier,
    liveRegionMode: LiveRegionMode? = null,
) {
    val liveRegionModifier = if (liveRegionMode == null) {
        Modifier
    } else {
        Modifier.semantics { liveRegion = liveRegionMode }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(liveRegionModifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(accent, modifier = Modifier.padding(top = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EchoText(
                value = title,
                color = EchoColors.Ink,
                style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
            )
            EchoText(
                value = body,
                color = EchoColors.InkSecondary,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            )
        }
    }
}

@Composable
private fun ConfirmationBlock(
    title: String,
    body: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Panel(
        modifier = Modifier.fillMaxWidth(),
        background = EchoColors.Caution.copy(alpha = 0.10f),
        border = EchoColors.Caution.copy(alpha = 0.48f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoBlock(
                title = title,
                body = body,
                accent = EchoColors.Caution,
                liveRegionMode = LiveRegionMode.Polite,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = stringResource(R.string.confirm_cancel),
                    enabled = true,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = confirmLabel,
                    enabled = true,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    secret: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(
            color = EchoColors.Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            letterSpacing = 0.sp,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(EchoColors.Sunken)
            .border(1.dp, EchoColors.Hair, RoundedCornerShape(8.dp))
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 15.dp),
    )
}

@Composable
private fun ProbeMessageText(message: ProbeMessage) {
    val (title, body, accent) = when (message) {
        ProbeMessage.Running -> Triple(
            stringResource(R.string.manual_connection),
            stringResource(R.string.probe_running),
            EchoColors.Live,
        )
        ProbeMessage.Cancelled -> Triple(
            stringResource(R.string.manual_connection),
            stringResource(R.string.probe_cancelled),
            EchoColors.Caution,
        )
        ProbeMessage.AuthRequired -> Triple(
            stringResource(R.string.access_token),
            stringResource(R.string.probe_auth_required),
            EchoColors.Caution,
        )
        ProbeMessage.Forbidden -> Triple(
            stringResource(R.string.access_token),
            stringResource(R.string.probe_forbidden),
            EchoColors.Caution,
        )
        is ProbeMessage.InvalidResponse -> Triple(
            stringResource(R.string.status_contract_missing),
            stringResource(R.string.probe_invalid_response, message.detail),
            EchoColors.Caution,
        )
        is ProbeMessage.NetworkFailure -> Triple(
            stringResource(R.string.manual_connection),
            stringResource(R.string.probe_network_failure, message.detail),
            EchoColors.Caution,
        )
        is ProbeMessage.HttpFailure -> Triple(
            stringResource(R.string.manual_connection),
            stringResource(R.string.probe_http_failure, message.statusCode),
            EchoColors.Caution,
        )
        is ProbeMessage.RejectedEndpoint -> Triple(
            stringResource(R.string.manual_connection),
            stringResource(R.string.endpoint_rejected, stringResource(message.reasonString)),
            EchoColors.Caution,
        )
        is ProbeMessage.Verified -> Triple(
            stringResource(R.string.verified_connection),
            message.deviceLabel,
            EchoColors.Permit,
        )
    }
    InfoBlock(
        title = title,
        body = body,
        accent = accent,
        liveRegionMode = LiveRegionMode.Polite,
    )
}

@Composable
private fun CaptureStatusMessageBlock(message: CaptureStatusMessage) {
    val (body, accent) = when (message) {
        CaptureStatusMessage.AuthRequired -> stringResource(R.string.capture_auth_required) to EchoColors.Caution
        CaptureStatusMessage.Forbidden -> stringResource(R.string.capture_forbidden) to EchoColors.Caution
        is CaptureStatusMessage.HttpFailure -> {
            stringResource(R.string.capture_http_failure, message.statusCode) to EchoColors.Caution
        }
        is CaptureStatusMessage.InvalidResponse -> {
            stringResource(R.string.capture_invalid_response, message.detail) to EchoColors.Caution
        }
        is CaptureStatusMessage.NetworkFailure -> {
            stringResource(R.string.capture_network_failure, message.detail) to EchoColors.Caution
        }
    }
    InfoBlock(
        title = stringResource(R.string.capture_status_title),
        body = body,
        accent = accent,
        liveRegionMode = LiveRegionMode.Polite,
    )
}

@Composable
private fun CaptureCommandMessageBlock(message: CaptureCommandMessage) {
    val (body, accent) = when (message) {
        CaptureCommandMessage.RunningStart -> stringResource(R.string.capture_command_running_start) to EchoColors.Live
        CaptureCommandMessage.RunningCalibrationStart -> {
            stringResource(R.string.capture_command_running_calibration_start) to EchoColors.Live
        }
        CaptureCommandMessage.RunningStop -> stringResource(R.string.capture_command_running_stop) to EchoColors.Live
        CaptureCommandMessage.RunningSafeSwapStop -> {
            stringResource(R.string.capture_command_running_safe_swap_stop) to EchoColors.Live
        }
        CaptureCommandMessage.Accepted -> stringResource(R.string.capture_command_accepted) to EchoColors.Permit
        CaptureCommandMessage.RecordingContinuesOnBack -> {
            stringResource(R.string.capture_command_recording_continues_on_back) to EchoColors.Caution
        }
        CaptureCommandMessage.NoActiveSession -> stringResource(R.string.capture_command_no_active_session) to EchoColors.Permit
        CaptureCommandMessage.AuthRequired -> stringResource(R.string.capture_command_auth_required) to EchoColors.Caution
        CaptureCommandMessage.Forbidden -> stringResource(R.string.capture_command_forbidden) to EchoColors.Caution
        CaptureCommandMessage.Conflict -> stringResource(R.string.capture_command_conflict) to EchoColors.Caution
        CaptureCommandMessage.Unprocessable -> stringResource(R.string.capture_command_unprocessable) to EchoColors.Caution
        is CaptureCommandMessage.InvalidRequest -> {
            stringResource(R.string.capture_command_invalid_request, message.detail) to EchoColors.Caution
        }
        is CaptureCommandMessage.InvalidResponse -> {
            stringResource(R.string.capture_command_invalid_response, message.detail) to EchoColors.Caution
        }
        is CaptureCommandMessage.NetworkFailure -> {
            stringResource(R.string.capture_command_network_failure, message.detail) to EchoColors.Caution
        }
        is CaptureCommandMessage.HttpFailure -> {
            stringResource(R.string.capture_command_http_failure, message.statusCode) to EchoColors.Caution
        }
    }
    InfoBlock(
        title = stringResource(R.string.capture_status_title),
        body = body,
        accent = accent,
        liveRegionMode = LiveRegionMode.Polite,
    )
}

@Composable
private fun startDisabledReason(
    bodyConnection: DeviceConnection?,
    captureStatus: CaptureStatusSnapshot?,
    captureCommandRunning: Boolean,
): String {
    return when {
        captureCommandRunning -> stringResource(R.string.capture_disabled_command_running)
        bodyConnection == null -> stringResource(R.string.capture_disabled_no_connection)
        !bodyConnection.descriptor.captureCapable -> stringResource(R.string.capture_disabled_not_capable)
        !isCameraConnected(bodyConnection, captureStatus) -> stringResource(R.string.capture_disabled_camera)
        !bodyConnection.descriptor.writable -> stringResource(R.string.capture_disabled_storage)
        captureStatus?.deviceState != "idle" -> stringResource(R.string.capture_disabled_not_idle)
        else -> stringResource(R.string.shutter_disabled)
    }
}

@Composable
private fun stopDisabledReason(
    bodyConnection: DeviceConnection?,
    captureStatus: CaptureStatusSnapshot?,
    captureCommandRunning: Boolean,
): String {
    return when {
        captureCommandRunning -> stringResource(R.string.capture_disabled_command_running)
        bodyConnection == null -> stringResource(R.string.capture_disabled_no_connection)
        captureStatus?.deviceState != "recording" -> stringResource(R.string.capture_disabled_not_recording)
        else -> stringResource(R.string.capture_disabled_not_recording)
    }
}

@Composable
private fun calibrationStartDisabledReason(
    bodyConnection: DeviceConnection?,
    captureStatus: CaptureStatusSnapshot?,
    captureCommandRunning: Boolean,
): String {
    val capability = bodyConnection?.descriptor?.calibrationCapture
    return when {
        captureCommandRunning -> stringResource(R.string.capture_disabled_command_running)
        bodyConnection == null -> stringResource(R.string.capture_disabled_no_connection)
        capability == null || !capability.supported -> stringResource(R.string.calibration_disabled_not_supported)
        !capability.enabled -> capability.disabledReason?.let { calibrationDisabledReasonLabel(it) }
            ?: stringResource(R.string.calibration_disabled_not_enabled)
        !isCameraConnected(bodyConnection, captureStatus) -> stringResource(R.string.capture_disabled_camera)
        !bodyConnection.descriptor.writable -> stringResource(R.string.capture_disabled_storage)
        captureStatus?.deviceState != "idle" -> stringResource(R.string.capture_disabled_not_idle)
        else -> stringResource(R.string.calibration_ready)
    }
}

private fun isCameraConnected(
    bodyConnection: DeviceConnection,
    captureStatus: CaptureStatusSnapshot?,
): Boolean {
    return (captureStatus?.runtime?.camera?.state ?: bodyConnection.descriptor.runtime.camera.state) == "connected"
}

@Composable
private fun safeSwapRequestDisabledReason(
    bodyConnection: DeviceConnection?,
    captureStatus: CaptureStatusSnapshot?,
    captureCommandRunning: Boolean,
): String {
    return when {
        captureCommandRunning -> stringResource(R.string.capture_disabled_command_running)
        bodyConnection == null -> stringResource(R.string.capture_disabled_no_connection)
        captureStatus?.deviceState != "recording" -> stringResource(R.string.safe_swap_disabled_not_recording)
        else -> stringResource(R.string.safe_swap_request_ready)
    }
}

private fun captureCommandMessageFor(result: CaptureCommandResult): CaptureCommandMessage {
    return when (result) {
        is CaptureCommandResult.Accepted -> CaptureCommandMessage.Accepted
        CaptureCommandResult.AuthenticationRequired -> CaptureCommandMessage.AuthRequired
        CaptureCommandResult.Conflict -> CaptureCommandMessage.Conflict
        CaptureCommandResult.Forbidden -> CaptureCommandMessage.Forbidden
        is CaptureCommandResult.HttpFailure -> CaptureCommandMessage.HttpFailure(result.statusCode)
        is CaptureCommandResult.InvalidRequest -> CaptureCommandMessage.InvalidRequest(result.message)
        is CaptureCommandResult.InvalidResponse -> CaptureCommandMessage.InvalidResponse(result.message)
        is CaptureCommandResult.NetworkFailure -> CaptureCommandMessage.NetworkFailure(result.message)
        CaptureCommandResult.NoActiveSession -> CaptureCommandMessage.NoActiveSession
        CaptureCommandResult.Unprocessable -> CaptureCommandMessage.Unprocessable
    }
}

@Composable
private fun previewStatusLabel(bodyConnection: DeviceConnection?, message: PreviewMessage?): String {
    return when {
        bodyConnection == null -> stringResource(R.string.preview_waiting)
        message == PreviewMessage.Live -> stringResource(R.string.preview_live)
        message == PreviewMessage.CameraNotConnected -> stringResource(R.string.preview_camera_not_connected)
        message == PreviewMessage.NoFrame -> stringResource(R.string.preview_no_frame_label)
        message == PreviewMessage.Unavailable -> stringResource(R.string.preview_unavailable_label)
        message != null -> stringResource(R.string.preview_error_label)
        else -> stringResource(R.string.preview_waiting)
    }
}

private fun previewStatusColor(bodyConnection: DeviceConnection?, message: PreviewMessage?): Color {
    return when {
        bodyConnection == null -> EchoColors.Caution
        message == PreviewMessage.Live -> EchoColors.Permit
        message == PreviewMessage.Waiting || message == null -> EchoColors.Live
        else -> EchoColors.Caution
    }
}

@Composable
private fun previewStatusBody(bodyConnection: DeviceConnection?, message: PreviewMessage?): String {
    return when {
        bodyConnection == null -> stringResource(R.string.preview_no_fake)
        message == null || message == PreviewMessage.Waiting -> stringResource(R.string.capture_polling)
        message == PreviewMessage.Live -> stringResource(R.string.preview_live)
        message == PreviewMessage.AuthRequired -> stringResource(R.string.preview_auth_required)
        message == PreviewMessage.Forbidden -> stringResource(R.string.preview_forbidden)
        message == PreviewMessage.CameraNotConnected -> stringResource(R.string.preview_camera_not_connected_body)
        message == PreviewMessage.DecodeFailed -> stringResource(R.string.preview_decode_failed)
        message == PreviewMessage.NoFrame -> stringResource(R.string.preview_no_frame)
        message == PreviewMessage.Unavailable -> stringResource(R.string.preview_unavailable)
        message is PreviewMessage.HttpFailure -> stringResource(R.string.preview_http_failure, message.statusCode)
        message is PreviewMessage.InvalidResponse -> stringResource(R.string.preview_invalid_response, message.detail)
        message is PreviewMessage.NetworkFailure -> stringResource(R.string.preview_network_failure, message.detail)
        else -> stringResource(R.string.preview_waiting)
    }
}

@Composable
private fun deviceStateLabel(deviceState: String): String {
    return stringResource(deviceStateString(deviceState))
}

@StringRes
private fun deviceStateString(deviceState: String): Int {
    return when (deviceState) {
        "idle" -> R.string.device_state_idle
        "recording" -> R.string.device_state_recording
        "finalizing" -> R.string.device_state_finalizing
        "encoding" -> R.string.device_state_encoding
        "verifying" -> R.string.device_state_verifying
        "blocked" -> R.string.device_state_blocked
        else -> R.string.device_state_unknown
    }
}

private fun deviceStateColor(deviceState: String): Color {
    return when (deviceState) {
        "idle" -> EchoColors.Permit
        "recording" -> EchoColors.Record
        "finalizing",
        "encoding",
        "verifying",
        -> EchoColors.Live
        "blocked" -> EchoColors.Caution
        else -> EchoColors.InkMuted
    }
}

@Composable
private fun runtimeLabel(observedAt: String, connectionMethod: String, temperatureCelsius: Double): String {
    return stringResource(R.string.runtime_observed, observedAt, connectionMethod, temperatureCelsius)
}

@Composable
private fun capabilityLabel(enabled: Boolean): String {
    return stringResource(if (enabled) R.string.capability_enabled else R.string.capability_disabled)
}

@Composable
private fun cameraConnectionLabel(value: String): String {
    return when (value) {
        "connected" -> stringResource(R.string.camera_connected)
        "disconnected" -> stringResource(R.string.camera_disconnected)
        else -> value
    }
}

@Composable
private fun calibrationCaptureText(capability: CalibrationCaptureCapability): String {
    val reason = capability.disabledReason?.let { calibrationDisabledReasonLabel(it) }
        ?: stringResource(R.string.value_none)
    return stringResource(
        R.string.calibration_capture_value,
        capabilityLabel(capability.supported),
        capabilityLabel(capability.enabled),
        reason,
        capability.requiredVideoLayout,
    )
}

@Composable
private fun calibrationDisabledReasonLabel(value: String): String {
    return when (value) {
        "capture_source_unsupported" -> stringResource(R.string.calibration_reason_capture_source_unsupported)
        "storage_unavailable" -> stringResource(R.string.calibration_reason_storage_unavailable)
        "hardware_unavailable" -> stringResource(R.string.calibration_reason_hardware_unavailable)
        "maintenance_or_capture_busy" -> stringResource(R.string.calibration_reason_maintenance_or_capture_busy)
        else -> value
    }
}

@Composable
private fun PreviewImage(image: ImageBitmap, mode: PreviewMode) {
    val previewDescription = stringResource(R.string.preview_frame_content)
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = previewDescription },
    ) {
        val halfWidth = (image.width / 2).coerceAtLeast(1)
        val useHalf = image.width >= 2 && mode != PreviewMode.BOTH
        val srcWidth = if (useHalf) halfWidth else image.width
        val srcOffsetX = when {
            !useHalf -> 0
            mode == PreviewMode.RIGHT -> image.width - halfWidth
            else -> 0
        }
        drawImage(
            image = image,
            srcOffset = IntOffset(srcOffsetX, 0),
            srcSize = IntSize(srcWidth, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(
                width = size.width.toInt().coerceAtLeast(1),
                height = size.height.toInt().coerceAtLeast(1),
            ),
        )
    }
}

@Composable
private fun PreviewGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(width = 1.dp.toPx())
        drawLine(
            color = EchoColors.HairStrong,
            start = Offset(size.width / 3f, 0f),
            end = Offset(size.width / 3f, size.height),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = EchoColors.HairStrong,
            start = Offset(size.width * 2f / 3f, 0f),
            end = Offset(size.width * 2f / 3f, size.height),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = EchoColors.HairStrong,
            start = Offset(0f, size.height / 3f),
            end = Offset(size.width, size.height / 3f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = EchoColors.HairStrong,
            start = Offset(0f, size.height * 2f / 3f),
            end = Offset(size.width, size.height * 2f / 3f),
            strokeWidth = stroke.width,
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    disabledReason: String? = null,
    onClick: () -> Unit,
) {
    val foreground = if (enabled) EchoColors.Void else EchoColors.InkMuted
    val background = if (enabled) EchoColors.Ink else Color.Transparent
    val border = if (enabled) EchoColors.Ink else EchoColors.HairStrong
    val semanticModifier = if (enabled) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.semantics {
            role = Role.Button
            disabled()
            if (!disabledReason.isNullOrBlank()) {
                stateDescription = disabledReason
            }
        }
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .then(semanticModifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        EchoText(
            value = label,
            color = foreground,
            style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FrameToolToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val state = if (selected) stringResource(R.string.tool_on) else stringResource(R.string.tool_off)
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) EchoColors.Sunken else EchoColors.GlassStrong)
            .border(1.dp, if (selected) EchoColors.Live else EchoColors.Hair, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        EchoText(
            value = label.take(3),
            color = if (selected) EchoColors.Live else EchoColors.InkMuted,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
        )
    }
}

@Composable
private fun ToolStatus(
    label: String,
    available: Boolean,
    unavailableReason: String? = null,
) {
    val stateDescription = if (available) {
        stringResource(R.string.tool_available)
    } else {
        unavailableReason ?: stringResource(R.string.tool_unwired)
    }
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(EchoColors.GlassStrong)
            .border(1.dp, EchoColors.Hair, RoundedCornerShape(8.dp))
            .semantics {
                contentDescription = label
                this.stateDescription = stateDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        EchoText(
            value = label.take(3),
            color = if (available) EchoColors.Live else EchoColors.InkMuted,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
        )
    }
}

@Composable
private fun TinyToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val state = if (selected) {
        stringResource(R.string.nav_selected)
    } else {
        stringResource(R.string.nav_not_selected)
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) EchoColors.Sunken else Color.Transparent)
            .border(1.dp, if (selected) EchoColors.Live else EchoColors.Hair, RoundedCornerShape(6.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                stateDescription = state
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        EchoText(
            value = label,
            color = if (selected) EchoColors.Ink else EchoColors.InkMuted,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
        )
    }
}

@Composable
private fun SegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, EchoColors.Hair, RoundedCornerShape(8.dp)),
    ) {
        options.forEach { (value, label) ->
            val isSelected = selected == value
            val state = if (isSelected) {
                stringResource(R.string.nav_selected)
            } else {
                stringResource(R.string.nav_not_selected)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) EchoColors.Sunken else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelected(value) },
                    )
                    .semantics {
                        contentDescription = label
                        stateDescription = state
                    },
                contentAlignment = Alignment.Center,
            ) {
                EchoText(
                    value = label,
                    color = if (isSelected) EchoColors.Ink else EchoColors.InkMuted,
                    style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color, Modifier.size(6.dp))
        EchoText(
            value = label,
            color = color,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusChipGroup(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(EchoColors.Sunken),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(EchoColors.Live),
        )
    }
}

@Composable
private fun EndpointMessageText(message: EndpointMessage) {
    val color = if (message.ok) EchoColors.Permit else EchoColors.Caution
    val body = when (message) {
        EndpointMessage.HttpsOk -> stringResource(R.string.endpoint_ok_https)
        EndpointMessage.LocalHttpOk -> stringResource(R.string.endpoint_ok_local_http)
        is EndpointMessage.Rejected -> stringResource(
            R.string.endpoint_rejected,
            stringResource(message.reasonString),
        )
    }
    InfoBlock(
        title = stringResource(R.string.manual_connection),
        body = body,
        accent = color,
        liveRegionMode = LiveRegionMode.Polite,
    )
}

@Composable
private fun TokenMessageText(message: TokenMessage) {
    val (body, accent) = when (message) {
        TokenMessage.Saved -> stringResource(R.string.token_save_success) to EchoColors.Permit
        TokenMessage.Cleared -> stringResource(R.string.token_clear_success) to EchoColors.Permit
        TokenMessage.Failed -> stringResource(R.string.token_save_failed) to EchoColors.Caution
    }
    InfoBlock(
        title = stringResource(R.string.access_token),
        body = body,
        accent = accent,
        liveRegionMode = LiveRegionMode.Polite,
    )
}

private fun endpointMessageFor(decision: EndpointPolicy.Decision): EndpointMessage {
    return when (decision) {
        is EndpointPolicy.Decision.Allowed -> {
            if (decision.target.cleartext) EndpointMessage.LocalHttpOk else EndpointMessage.HttpsOk
        }
        is EndpointPolicy.Decision.Rejected -> EndpointMessage.Rejected(decision.reason.toStringResource())
    }
}

private fun probeMessageFor(result: ProbeResult): ProbeMessage {
    return when (result) {
        ProbeResult.AuthenticationRequired -> ProbeMessage.AuthRequired
        ProbeResult.Forbidden -> ProbeMessage.Forbidden
        is ProbeResult.HttpFailure -> ProbeMessage.HttpFailure(result.statusCode)
        is ProbeResult.InvalidResponse -> ProbeMessage.InvalidResponse(result.message)
        is ProbeResult.NetworkFailure -> ProbeMessage.NetworkFailure(result.message)
        is ProbeResult.RejectedEndpoint -> ProbeMessage.RejectedEndpoint(result.reason.toStringResource())
        is ProbeResult.Verified -> ProbeMessage.Verified(result.connection.descriptor.deviceLabel)
    }
}

private fun DiscoveryState.bodies(): List<DiscoveredBody> {
    return when (this) {
        is DiscoveryState.Failed -> bodies
        is DiscoveryState.Idle -> bodies
        is DiscoveryState.Scanning -> bodies
    }
}

@StringRes
private fun EndpointPolicy.RejectReason.toStringResource(): Int {
    return when (this) {
        EndpointPolicy.RejectReason.EMPTY -> R.string.endpoint_reject_empty
        EndpointPolicy.RejectReason.UNSUPPORTED_SCHEME -> R.string.endpoint_reject_scheme
        EndpointPolicy.RejectReason.MISSING_HOST -> R.string.endpoint_reject_host
        EndpointPolicy.RejectReason.CREDENTIALS_IN_URI -> R.string.endpoint_reject_credentials
        EndpointPolicy.RejectReason.PATH_QUERY_OR_FRAGMENT -> R.string.endpoint_reject_path
        EndpointPolicy.RejectReason.PUBLIC_CLEARTEXT_HTTP -> R.string.endpoint_reject_public_http
        EndpointPolicy.RejectReason.INVALID_URI -> R.string.endpoint_reject_invalid
    }
}

private sealed interface EndpointMessage {
    val ok: Boolean

    data object HttpsOk : EndpointMessage {
        override val ok = true
    }

    data object LocalHttpOk : EndpointMessage {
        override val ok = true
    }

    data class Rejected(@param:StringRes val reasonString: Int) : EndpointMessage {
        override val ok = false
    }
}

private sealed interface TokenMessage {
    data object Saved : TokenMessage
    data object Cleared : TokenMessage
    data object Failed : TokenMessage
}

private sealed interface ProbeMessage {
    data object Running : ProbeMessage
    data object Cancelled : ProbeMessage
    data object AuthRequired : ProbeMessage
    data object Forbidden : ProbeMessage
    data class RejectedEndpoint(@param:StringRes val reasonString: Int) : ProbeMessage
    data class InvalidResponse(val detail: String) : ProbeMessage
    data class NetworkFailure(val detail: String) : ProbeMessage
    data class HttpFailure(val statusCode: Int) : ProbeMessage
    data class Verified(val deviceLabel: String) : ProbeMessage
}

private sealed interface CaptureStatusMessage {
    data object AuthRequired : CaptureStatusMessage
    data object Forbidden : CaptureStatusMessage
    data class InvalidResponse(val detail: String) : CaptureStatusMessage
    data class NetworkFailure(val detail: String) : CaptureStatusMessage
    data class HttpFailure(val statusCode: Int) : CaptureStatusMessage
}

private sealed interface CaptureCommandMessage {
    data object RunningStart : CaptureCommandMessage
    data object RunningCalibrationStart : CaptureCommandMessage
    data object RunningStop : CaptureCommandMessage
    data object RunningSafeSwapStop : CaptureCommandMessage
    data object Accepted : CaptureCommandMessage
    data object RecordingContinuesOnBack : CaptureCommandMessage
    data object NoActiveSession : CaptureCommandMessage
    data object AuthRequired : CaptureCommandMessage
    data object Forbidden : CaptureCommandMessage
    data object Conflict : CaptureCommandMessage
    data object Unprocessable : CaptureCommandMessage
    data class InvalidRequest(val detail: String) : CaptureCommandMessage
    data class InvalidResponse(val detail: String) : CaptureCommandMessage
    data class NetworkFailure(val detail: String) : CaptureCommandMessage
    data class HttpFailure(val statusCode: Int) : CaptureCommandMessage
}

private sealed interface PreviewMessage {
    data object Waiting : PreviewMessage
    data object Live : PreviewMessage
    data object AuthRequired : PreviewMessage
    data object Forbidden : PreviewMessage
    data object CameraNotConnected : PreviewMessage
    data object DecodeFailed : PreviewMessage
    data object Unavailable : PreviewMessage
    data object NoFrame : PreviewMessage
    data class InvalidResponse(val detail: String) : PreviewMessage
    data class NetworkFailure(val detail: String) : PreviewMessage
    data class HttpFailure(val statusCode: Int) : PreviewMessage
}

private sealed interface SessionMessage {
    data object AuthRequired : SessionMessage
    data object Forbidden : SessionMessage
    data object InvalidRequest : SessionMessage
    data class InvalidResponse(val detail: String) : SessionMessage
    data class NetworkFailure(val detail: String) : SessionMessage
    data class HttpFailure(val statusCode: Int) : SessionMessage
}

private sealed interface SessionManifestMessage {
    data object Loading : SessionManifestMessage
    data object NotFound : SessionManifestMessage
    data object AuthRequired : SessionManifestMessage
    data object Forbidden : SessionManifestMessage
    data class InvalidRequest(val detail: String) : SessionManifestMessage
    data class InvalidResponse(val detail: String) : SessionManifestMessage
    data class NetworkFailure(val detail: String) : SessionManifestMessage
    data class HttpFailure(val statusCode: Int) : SessionManifestMessage
}

private sealed interface UnsuccessfulOutcomeMessage {
    data object Loading : UnsuccessfulOutcomeMessage
    data object NotFound : UnsuccessfulOutcomeMessage
    data object AuthRequired : UnsuccessfulOutcomeMessage
    data object Forbidden : UnsuccessfulOutcomeMessage
    data class InvalidRequest(val detail: String) : UnsuccessfulOutcomeMessage
    data class InvalidResponse(val detail: String) : UnsuccessfulOutcomeMessage
    data class NetworkFailure(val detail: String) : UnsuccessfulOutcomeMessage
    data class HttpFailure(val statusCode: Int) : UnsuccessfulOutcomeMessage
}

private sealed interface ArtifactDownloadMessage {
    data class Running(val role: String) : ArtifactDownloadMessage
    data class Cancelled(val role: String) : ArtifactDownloadMessage
    data class Saved(val role: String, val path: String, val bytes: Long, val mediaType: String) : ArtifactDownloadMessage
    data class AuthRequired(val role: String) : ArtifactDownloadMessage
    data class Forbidden(val role: String) : ArtifactDownloadMessage
    data class NotFound(val role: String) : ArtifactDownloadMessage
    data class SessionNotVerified(val role: String) : ArtifactDownloadMessage
    data class CaptureBusy(val role: String) : ArtifactDownloadMessage
    data class RangeNotSatisfiable(val role: String) : ArtifactDownloadMessage
    data class InvalidRequest(val detail: String) : ArtifactDownloadMessage
    data class InvalidResponse(val detail: String) : ArtifactDownloadMessage
    data class IntegrityFailure(val actualSha256: String) : ArtifactDownloadMessage
    data class NetworkFailure(val role: String, val detail: String) : ArtifactDownloadMessage
    data class HttpFailure(val role: String, val statusCode: Int) : ArtifactDownloadMessage
    data class StoreFailed(val role: String, val detail: String) : ArtifactDownloadMessage
}

private sealed interface SafeSwapMessage {
    data object WaitingForReceipt : SafeSwapMessage
    data object NotFound : SafeSwapMessage
    data object AuthRequired : SafeSwapMessage
    data object Forbidden : SafeSwapMessage
    data class InvalidResponse(val detail: String) : SafeSwapMessage
    data class NetworkFailure(val detail: String) : SafeSwapMessage
    data class HttpFailure(val statusCode: Int) : SafeSwapMessage
}

private sealed interface CameraFocusMessage {
    data object Running : CameraFocusMessage
    data object Updated : CameraFocusMessage
    data object Unsupported : CameraFocusMessage
    data object AuthRequired : CameraFocusMessage
    data object Forbidden : CameraFocusMessage
    data object Conflict : CameraFocusMessage
    data object InvalidFocus : CameraFocusMessage
    data class InvalidRequest(val detail: String) : CameraFocusMessage
    data class InvalidResponse(val detail: String) : CameraFocusMessage
    data class NetworkFailure(val detail: String) : CameraFocusMessage
    data class HttpFailure(val statusCode: Int) : CameraFocusMessage
}

private sealed interface NetworkMessage {
    data object StatusLoading : NetworkMessage
    data object StatusUnavailable : NetworkMessage
    data object ScanRunning : NetworkMessage
    data class ScanLoaded(val count: Int) : NetworkMessage
    data object ScanUnavailable : NetworkMessage
    data object MutationRunning : NetworkMessage
    data class MutationAccepted(val transactionId: String) : NetworkMessage
    data class MutationResultPending(val recoveryAction: String, val detail: String) : NetworkMessage
    data object MutationUnavailable : NetworkMessage
    data object IdempotencyConflict : NetworkMessage
    data object InvalidDesiredState : NetworkMessage
    data object TransactionNotFound : NetworkMessage
    data object AuthRequired : NetworkMessage
    data object Forbidden : NetworkMessage
    data class InvalidRequest(val detail: String) : NetworkMessage
    data class InvalidResponse(val detail: String) : NetworkMessage
    data class NetworkFailure(val detail: String) : NetworkMessage
    data class HttpFailure(val statusCode: Int) : NetworkMessage
}

private fun decodePreviewFrame(bytes: ByteArray): ImageBitmap? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return bitmap.asImageBitmap()
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun openArtifact(context: Context, path: String, mediaType: String) {
    val file = File(path)
    if (!file.isFile) {
        showToast(context, R.string.artifact_open_failed)
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(artifactUri(context, file), mediaType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.artifact_open)).withTaskFlag(context))
    } catch (_: ActivityNotFoundException) {
        showToast(context, R.string.artifact_open_failed)
    } catch (_: IllegalArgumentException) {
        showToast(context, R.string.artifact_open_failed)
    }
}

private fun shareArtifact(context: Context, path: String, mediaType: String) {
    val file = File(path)
    if (!file.isFile) {
        showToast(context, R.string.artifact_share_failed)
        return
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mediaType)
        .putExtra(Intent.EXTRA_STREAM, artifactUri(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.artifact_share)).withTaskFlag(context))
    } catch (_: ActivityNotFoundException) {
        showToast(context, R.string.artifact_share_failed)
    } catch (_: IllegalArgumentException) {
        showToast(context, R.string.artifact_share_failed)
    }
}

private fun artifactUri(context: Context, file: File) = FileProvider.getUriForFile(
    context,
    "${context.packageName}.fileprovider",
    file,
)

private fun Intent.withTaskFlag(context: Context): Intent {
    return if (context.findActivity() == null) {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        this
    }
}

private fun showToast(context: Context, @StringRes message: Int) {
    Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
}

@StringRes
private fun updatePhaseString(phase: AppUpdateManager.Phase): Int {
    return when (phase) {
        AppUpdateManager.Phase.IDLE -> R.string.update_idle
        AppUpdateManager.Phase.CHECKING -> R.string.update_checking
        AppUpdateManager.Phase.CURRENT -> R.string.update_current
        AppUpdateManager.Phase.AVAILABLE -> R.string.update_available
        AppUpdateManager.Phase.DOWNLOADING -> R.string.update_downloading
        AppUpdateManager.Phase.INSTALLING -> R.string.update_installing
        AppUpdateManager.Phase.FAILED -> R.string.update_failed
    }
}

private fun updatePhaseColor(phase: AppUpdateManager.Phase): Color {
    return when (phase) {
        AppUpdateManager.Phase.AVAILABLE,
        AppUpdateManager.Phase.DOWNLOADING,
        AppUpdateManager.Phase.INSTALLING,
        -> EchoColors.Live
        AppUpdateManager.Phase.FAILED -> EchoColors.Caution
        else -> EchoColors.InkSecondary
    }
}

private fun updateProgress(state: AppUpdateManager.State): Float {
    if (state.totalBytes <= 0L) {
        return if (state.phase == AppUpdateManager.Phase.CURRENT) 1f else 0f
    }
    return state.downloadedBytes.toFloat() / state.totalBytes.toFloat()
}

@Composable
private fun updateInstallDisabledReason(state: AppUpdateManager.State): String {
    return when (state.phase) {
        AppUpdateManager.Phase.CHECKING,
        AppUpdateManager.Phase.DOWNLOADING,
        AppUpdateManager.Phase.INSTALLING,
        -> stringResource(R.string.update_install_disabled_busy)
        else -> stringResource(R.string.update_install_disabled_no_update)
    }
}

private fun updateVersionLabel(state: AppUpdateManager.State): String {
    val manifest = state.manifest
    return if (manifest != null) {
        "${manifest.version}+${manifest.versionCode}"
    } else {
        "${state.currentVersionName}+${state.currentBuildNumber}"
    }
}

@Composable
private fun ApertureBackdrop(tab: EchoTab) {
    val accent = when (tab) {
        EchoTab.VIEWFINDER -> EchoColors.Live
        EchoTab.SESSIONS -> EchoColors.Permit
        EchoTab.BODY -> EchoColors.Peak
        EchoTab.NETWORK -> EchoColors.Caution
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(EchoColors.Void)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.16f), EchoColors.Deck.copy(alpha = 0.92f), EchoColors.Void),
                center = Offset(size.width * 0.52f, size.height * 0.28f),
                radius = size.maxDimension * 0.82f,
            ),
            size = size,
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, EchoColors.Void.copy(alpha = 0.80f)),
                startY = size.height * 0.58f,
                endY = size.height,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
        )
    }
}

private enum class EchoTab(@param:StringRes val label: Int) {
    VIEWFINDER(R.string.nav_viewfinder),
    SESSIONS(R.string.nav_sessions),
    BODY(R.string.nav_body),
    NETWORK(R.string.nav_network),
}

private enum class PreviewMode {
    BOTH,
    LEFT,
    RIGHT,
}
