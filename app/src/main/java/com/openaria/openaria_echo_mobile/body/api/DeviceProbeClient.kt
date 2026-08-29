package com.openaria.openaria_echo_mobile.body.api

import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import java.io.IOException
import java.net.HttpURLConnection

class DeviceProbeClient {
    fun probe(origins: List<String>, bearerToken: String?): ProbeResult {
        val primaryOrigin = origins.firstOrNull()?.trim()
        return probe(origins) { origin -> bearerToken.takeIf { origin == primaryOrigin } }
    }

    /**
     * Tries one NSD service's candidate origins in order while resolving
     * credentials independently for each exact origin. A token saved for an
     * IPv4 origin must never be copied to a hostname or IPv6 candidate merely
     * because mDNS advertised them in the same response.
     */
    fun probe(origins: List<String>, bearerTokenForOrigin: (String) -> String?): ProbeResult {
        val candidates = origins
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (candidates.isEmpty()) {
            return probe("", bearerTokenForOrigin(""))
        }

        var lastNetworkFailure: ProbeResult.NetworkFailure? = null
        for (origin in candidates) {
            when (val result = probe(origin, bearerTokenForOrigin(origin))) {
                is ProbeResult.NetworkFailure -> lastNetworkFailure = result
                else -> return result
            }
        }
        return requireNotNull(lastNetworkFailure)
    }

    fun probe(origin: String, bearerToken: String?): ProbeResult {
        return probeWithCancellation(origin, bearerToken, cancellation = null)
    }

    internal fun probe(
        origin: String,
        bearerToken: String?,
        cancellation: DeviceAdmissionCancellation,
    ): ProbeResult {
        return probeWithCancellation(origin, bearerToken, cancellation)
    }

    private fun probeWithCancellation(
        origin: String,
        bearerToken: String?,
        cancellation: DeviceAdmissionCancellation?,
    ): ProbeResult {
        val endpointDecision = EndpointPolicy.validate(origin)
        if (endpointDecision is EndpointPolicy.Decision.Rejected) {
            return ProbeResult.RejectedEndpoint(endpointDecision.reason)
        }
        val target = (endpointDecision as EndpointPolicy.Decision.Allowed).target
        val url = target.origin.resolve("/api/v4/device").toURL()

        val connection = try {
            (url.openConnection() as HttpURLConnection).lockToDeviceOrigin().apply {
                connectTimeout = 5_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                if (!bearerToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
                }
            }
        } catch (exception: IOException) {
            return ProbeResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        }
        if (cancellation != null && !cancellation.register(connection)) {
            return ProbeResult.NetworkFailure("admission cancelled")
        }

        return try {
            val status = connection.responseCode
            when (status) {
                HttpURLConnection.HTTP_OK -> parseDeviceDescriptor(
                    target = target,
                    bearerToken = bearerToken,
                    body = connection.inputStream.readBytes().decodeToString(),
                )
                // Keep the canonical endpoint identity with the challenge. A
                // multi-address service may fail on one candidate and require
                // credentials on another; callers must authorize that exact
                // origin instead of guessing from the primary address.
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    ProbeResult.AuthenticationRequired(target.origin.toString())
                }
                HttpURLConnection.HTTP_FORBIDDEN -> ProbeResult.Forbidden
                else -> ProbeResult.HttpFailure(connection.toDeviceHttpFailure(status))
            }
        } catch (exception: IOException) {
            ProbeResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } catch (exception: RuntimeException) {
            // A concurrent cancellation can surface as a JDK NPE while the
            // connection is being disconnected; keep admission failure typed.
            if (cancellation?.isCancelled() == true) {
                ProbeResult.NetworkFailure("admission cancelled")
            } else {
                throw exception
            }
        } finally {
            cancellation?.clear(connection)
            runCatching { connection.disconnect() }
        }
    }

    private fun parseDeviceDescriptor(
        target: EndpointPolicy.BodyTarget,
        bearerToken: String?,
        body: String,
    ): ProbeResult {
        val root = when (val json = DeviceJsonPayload.parseObject(body)) {
            is DeviceJsonPayload.Result.Parsed -> json.value
            is DeviceJsonPayload.Result.Invalid -> return ProbeResult.InvalidResponse(json.message)
        }
        return when (val validation = DeviceApiValidators.validateDeviceDescriptor(root)) {
            is Validation.Valid -> ProbeResult.Verified(
                DeviceConnection(
                    target = target,
                    descriptor = validation.value,
                    bearerToken = bearerToken?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
            is Validation.Invalid -> ProbeResult.InvalidResponse(validation.message)
        }
    }
}

sealed interface ProbeResult {
    data class Verified(val connection: DeviceConnection) : ProbeResult
    data class AuthenticationRequired(val origin: String) : ProbeResult
    data object Forbidden : ProbeResult
    data class RejectedEndpoint(val reason: EndpointPolicy.RejectReason) : ProbeResult
    data class InvalidResponse(val message: String) : ProbeResult
    data class NetworkFailure(val message: String) : ProbeResult
    data class HttpFailure(
        override val failure: DeviceHttpFailure,
    ) : ProbeResult, DeviceHttpFailureResult {
        constructor(statusCode: Int) : this(DeviceHttpFailure(statusCode))
    }
}
