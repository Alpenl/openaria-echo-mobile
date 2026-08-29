package com.openaria.openaria_echo_mobile.body.api

import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DeviceAdmissionCandidate(
    val origin: String,
    val bearerToken: String?,
) {
    val normalizedOrigin: String = origin.trim()
    val normalizedBearerToken: String? = bearerToken?.trim()?.takeIf(String::isNotEmpty)

    internal fun matches(connection: DeviceConnection): Boolean {
        val decision = EndpointPolicy.validate(normalizedOrigin)
        val expectedOrigin = (decision as? EndpointPolicy.Decision.Allowed)?.target?.origin?.toString()
            ?: return false
        return connection.origin == expectedOrigin &&
            connection.bearerToken == normalizedBearerToken
    }
}

data class VerifiedDeviceAdmission(
    val connection: DeviceConnection,
    val initialCaptureStatus: CaptureStatusSnapshot,
)

class DeviceAdmissionClient internal constructor(
    private val probeDevice: (String, String?, DeviceAdmissionCancellation) -> ProbeResult,
    private val loadCaptureStatus: (DeviceConnection, DeviceAdmissionCancellation) -> CaptureStatusResult,
) {
    constructor() : this(
        probeDevice = { origin, bearerToken, cancellation ->
            DeviceProbeClient().probe(origin, bearerToken, cancellation)
        },
        loadCaptureStatus = { connection, cancellation ->
            DeviceHttpClient().getCaptureStatus(connection, cancellation)
        },
    )

    internal constructor(
        probeDevice: (String, String?) -> ProbeResult,
        loadCaptureStatus: (DeviceConnection) -> CaptureStatusResult,
    ) : this(
        probeDevice = { origin, bearerToken, _ -> probeDevice(origin, bearerToken) },
        loadCaptureStatus = { connection, _ -> loadCaptureStatus(connection) },
    )

    /**
     * Admits a candidate only after both authoritative v4 resources succeed.
     * The caller-owned fence is checked before, between, and after network calls.
     */
    fun admit(
        candidates: List<DeviceAdmissionCandidate>,
        isAttemptCurrent: () -> Boolean,
        cancellation: DeviceAdmissionCancellation = DeviceAdmissionCancellation(),
    ): DeviceAdmissionResult {
        val normalizedCandidates = candidates
            .map { DeviceAdmissionCandidate(it.normalizedOrigin, it.normalizedBearerToken) }
            .distinctBy(DeviceAdmissionCandidate::normalizedOrigin)
            .ifEmpty { listOf(DeviceAdmissionCandidate("", null)) }

        var lastNetworkFailure: DeviceAdmissionResult.NetworkFailure? = null
        for (candidate in normalizedCandidates) {
            if (!isAttemptCurrent() || cancellation.isCancelled()) return DeviceAdmissionResult.Cancelled

            val probe = probeDevice(candidate.normalizedOrigin, candidate.normalizedBearerToken, cancellation)
            if (!isAttemptCurrent() || cancellation.isCancelled()) return DeviceAdmissionResult.Cancelled

            when (probe) {
                ProbeResult.AuthenticationRequired -> {
                    return DeviceAdmissionResult.AuthenticationRequired(candidate.normalizedOrigin)
                }
                ProbeResult.Forbidden -> return DeviceAdmissionResult.Forbidden(candidate.normalizedOrigin)
                is ProbeResult.HttpFailure -> return DeviceAdmissionResult.HttpFailure(probe.failure)
                is ProbeResult.InvalidResponse -> return DeviceAdmissionResult.InvalidResponse(probe.message)
                is ProbeResult.RejectedEndpoint -> return DeviceAdmissionResult.RejectedEndpoint(probe.reason)
                is ProbeResult.NetworkFailure -> {
                    lastNetworkFailure = DeviceAdmissionResult.NetworkFailure(probe.message)
                    continue
                }
                is ProbeResult.Verified -> {
                    if (!candidate.matches(probe.connection)) {
                        return DeviceAdmissionResult.InvalidResponse(
                            "connection identity changed during admission",
                        )
                    }
                    if (!isAttemptCurrent() || cancellation.isCancelled()) {
                        return DeviceAdmissionResult.Cancelled
                    }

                    val status = loadCaptureStatus(probe.connection, cancellation)
                    if (!isAttemptCurrent() || cancellation.isCancelled()) {
                        return DeviceAdmissionResult.Cancelled
                    }

                    when (status) {
                        CaptureStatusResult.AuthenticationRequired -> {
                            return DeviceAdmissionResult.AuthenticationRequired(candidate.normalizedOrigin)
                        }
                        CaptureStatusResult.Forbidden -> {
                            return DeviceAdmissionResult.Forbidden(candidate.normalizedOrigin)
                        }
                        is CaptureStatusResult.HttpFailure -> {
                            return DeviceAdmissionResult.HttpFailure(status.failure)
                        }
                        is CaptureStatusResult.InvalidResponse -> {
                            return DeviceAdmissionResult.InvalidResponse(status.message)
                        }
                        is CaptureStatusResult.NetworkFailure -> {
                            lastNetworkFailure = DeviceAdmissionResult.NetworkFailure(status.message)
                            continue
                        }
                        is CaptureStatusResult.Snapshot -> {
                            return DeviceAdmissionResult.Verified(
                                VerifiedDeviceAdmission(
                                    connection = probe.connection,
                                    initialCaptureStatus = status.value,
                                ),
                            )
                        }
                    }
                }
            }
        }
        return lastNetworkFailure ?: DeviceAdmissionResult.NetworkFailure("no candidate origin responded")
    }
}

sealed interface DeviceAdmissionResult {
    data class Verified(val admission: VerifiedDeviceAdmission) : DeviceAdmissionResult
    data class AuthenticationRequired(val origin: String) : DeviceAdmissionResult
    data class Forbidden(val origin: String) : DeviceAdmissionResult
    data object Cancelled : DeviceAdmissionResult
    data class RejectedEndpoint(val reason: EndpointPolicy.RejectReason) : DeviceAdmissionResult
    data class InvalidResponse(val message: String) : DeviceAdmissionResult
    data class NetworkFailure(val message: String) : DeviceAdmissionResult
    data class HttpFailure(
        override val failure: DeviceHttpFailure,
    ) : DeviceAdmissionResult, DeviceHttpFailureResult
}

/** Owns the one blocking HTTP request that may be active during an admission attempt. */
class DeviceAdmissionCancellation {
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)

    internal fun register(connection: HttpURLConnection): Boolean {
        if (cancelled.get()) {
            connection.disconnect()
            return false
        }
        check(activeConnection.compareAndSet(null, connection)) {
            "an admission attempt cannot run concurrent HTTP requests"
        }
        if (cancelled.get() && activeConnection.compareAndSet(connection, null)) {
            connection.disconnect()
            return false
        }
        return !cancelled.get()
    }

    internal fun clear(connection: HttpURLConnection) {
        activeConnection.compareAndSet(connection, null)
    }

    fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    fun isCancelled(): Boolean = cancelled.get()
}
