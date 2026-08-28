package com.openaria.openaria_echo_mobile.body.api

import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import java.io.IOException
import java.net.HttpURLConnection

class DeviceProbeClient {
    fun probe(origin: String, bearerToken: String?): ProbeResult {
        val endpointDecision = EndpointPolicy.validate(origin)
        if (endpointDecision is EndpointPolicy.Decision.Rejected) {
            return ProbeResult.RejectedEndpoint(endpointDecision.reason)
        }
        val target = (endpointDecision as EndpointPolicy.Decision.Allowed).target
        val url = target.origin.resolve("/api/v4/device").toURL()

        val connection = try {
            (url.openConnection() as HttpURLConnection).apply {
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

        return try {
            val status = connection.responseCode
            when (status) {
                HttpURLConnection.HTTP_OK -> parseDeviceDescriptor(
                    target = target,
                    bearerToken = bearerToken,
                    body = connection.inputStream.readBytes().decodeToString(),
                )
                HttpURLConnection.HTTP_UNAUTHORIZED -> ProbeResult.AuthenticationRequired
                HttpURLConnection.HTTP_FORBIDDEN -> ProbeResult.Forbidden
                else -> ProbeResult.HttpFailure(status)
            }
        } catch (exception: IOException) {
            ProbeResult.NetworkFailure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            connection.disconnect()
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
    data object AuthenticationRequired : ProbeResult
    data object Forbidden : ProbeResult
    data class RejectedEndpoint(val reason: EndpointPolicy.RejectReason) : ProbeResult
    data class InvalidResponse(val message: String) : ProbeResult
    data class NetworkFailure(val message: String) : ProbeResult
    data class HttpFailure(val statusCode: Int) : ProbeResult
}
