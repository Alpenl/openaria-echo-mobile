package com.openaria.openaria_echo_mobile.security

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

object EndpointPolicy {
    fun validate(raw: String): Decision {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Decision.Rejected(RejectReason.EMPTY)
        }

        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return Decision.Rejected(RejectReason.INVALID_URI)
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
            ?: return Decision.Rejected(RejectReason.UNSUPPORTED_SCHEME)
        if (scheme != "https" && scheme != "http") {
            return Decision.Rejected(RejectReason.UNSUPPORTED_SCHEME)
        }
        if (!uri.userInfo.isNullOrBlank()) {
            return Decision.Rejected(RejectReason.CREDENTIALS_IN_URI)
        }

        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Decision.Rejected(RejectReason.MISSING_HOST)
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) {
            return Decision.Rejected(RejectReason.PATH_QUERY_OR_FRAGMENT)
        }
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") {
            return Decision.Rejected(RejectReason.PATH_QUERY_OR_FRAGMENT)
        }

        val canonicalHost = canonicalHost(host)
            ?: return Decision.Rejected(RejectReason.MISSING_HOST)
        val normalized = try {
            URI(scheme, null, canonicalHost, uri.port, null, null, null)
        } catch (_: URISyntaxException) {
            return Decision.Rejected(RejectReason.INVALID_URI)
        }

        if (scheme == "https") {
            return Decision.Allowed(BodyTarget(normalized, cleartext = false))
        }
        if (allowsLocalHttp(canonicalHost)) {
            return Decision.Allowed(BodyTarget(normalized, cleartext = true))
        }
        return Decision.Rejected(RejectReason.PUBLIC_CLEARTEXT_HTTP)
    }

    private fun canonicalHost(host: String): String? {
        val unbracketed = host.removePrefix("[").removeSuffix("]")
        if (unbracketed.contains(":")) {
            return unbracketed.lowercase(Locale.US)
        }
        return try {
            IDN.toASCII(unbracketed).lowercase(Locale.US)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun allowsLocalHttp(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) {
            return true
        }
        val ipv4 = parseIpv4(host)
        if (ipv4 != null) {
            val a = ipv4[0]
            val b = ipv4[1]
            return a == 10 ||
                a == 127 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)
        }
        if (host.contains(":")) {
            val address = try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                return false
            }
            val bytes = address.address
            if (bytes.size == 16) {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                return address.isLoopbackAddress ||
                    (first and 0xfe) == 0xfc ||
                    (first == 0xfe && (second and 0xc0) == 0x80)
            }
        }
        return false
    }

    private fun parseIpv4(host: String): IntArray? {
        val pieces = host.split(".")
        if (pieces.size != 4) {
            return null
        }
        val result = IntArray(4)
        for ((index, piece) in pieces.withIndex()) {
            if (piece.isEmpty() || piece.length > 3 || piece.any { !it.isDigit() }) {
                return null
            }
            val value = piece.toIntOrNull() ?: return null
            if (value !in 0..255) {
                return null
            }
            result[index] = value
        }
        return result
    }

    data class BodyTarget(
        val origin: URI,
        val cleartext: Boolean,
    )

    sealed interface Decision {
        data class Allowed(val target: BodyTarget) : Decision
        data class Rejected(val reason: RejectReason) : Decision
    }

    enum class RejectReason {
        EMPTY,
        UNSUPPORTED_SCHEME,
        MISSING_HOST,
        CREDENTIALS_IN_URI,
        PATH_QUERY_OR_FRAGMENT,
        PUBLIC_CLEARTEXT_HTTP,
        INVALID_URI,
    }
}
