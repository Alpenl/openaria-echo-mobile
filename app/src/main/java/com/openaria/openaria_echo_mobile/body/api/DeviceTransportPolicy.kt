package com.openaria.openaria_echo_mobile.body.api

import java.net.HttpURLConnection
import java.net.URI

internal fun HttpURLConnection.lockToDeviceOrigin(): HttpURLConnection {
    instanceFollowRedirects = false
    return this
}

interface DeviceHttpFailureResult {
    val failure: DeviceHttpFailure

    val statusCode: Int
        get() = failure.statusCode

    val errorCode: String
        get() = failure.errorCode

    val locationSummary: String?
        get() = failure.locationSummary
}

data class DeviceHttpFailure(
    val statusCode: Int,
    val errorCode: String = if (statusCode in 300..399) {
        CODE_PROTOCOL_REDIRECT
    } else {
        CODE_HTTP_STATUS
    },
    val locationSummary: String? = null,
) {
    companion object {
        const val CODE_PROTOCOL_REDIRECT = "device_protocol_redirect"
        const val CODE_HTTP_STATUS = "device_http_status"
    }
}

internal fun HttpURLConnection.toDeviceHttpFailure(statusCode: Int): DeviceHttpFailure {
    return DeviceHttpFailure(
        statusCode = statusCode,
        locationSummary = if (statusCode in 300..399) {
            getHeaderField("Location")?.toSafeLocationSummary()
        } else {
            null
        },
    )
}

private fun String.toSafeLocationSummary(): String? {
    if (length > MAX_LOCATION_HEADER_CHARS) return "oversized"
    val value = trim()
    if (value.isEmpty()) return null
    val location = try {
        URI(value)
    } catch (_: IllegalArgumentException) {
        return "malformed"
    }
    return when {
        location.isAbsolute -> when (location.scheme?.lowercase()) {
            "http" -> "absolute:http"
            "https" -> "absolute:https"
            else -> "absolute:other"
        }
        value.startsWith("//") -> "network-path"
        else -> "relative"
    }
}

private const val MAX_LOCATION_HEADER_CHARS = 2_048
