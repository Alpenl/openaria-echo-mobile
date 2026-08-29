package com.openaria.openaria_echo_mobile.security

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

object EndpointPolicy {
    /** Returns the origin identity used for requests and credential binding. */
    fun canonicalOrigin(raw: String): String? {
        return (validate(raw) as? Decision.Allowed)?.target?.origin?.toString()
    }

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
        if (!uri.userInfo.isNullOrBlank() || uri.rawAuthority.orEmpty().contains("@")) {
            return Decision.Rejected(RejectReason.CREDENTIALS_IN_URI)
        }

        val authority = parseAuthority(uri)
            ?: return Decision.Rejected(RejectReason.MISSING_HOST)
        val host = authority.host.trim().takeIf { it.isNotEmpty() }
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
        val normalizedPort = when {
            scheme == "http" && authority.port == 80 -> -1
            scheme == "https" && authority.port == 443 -> -1
            else -> authority.port
        }
        val normalized = try {
            URI(scheme, null, canonicalHost, normalizedPort, null, null, null)
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
            val scopeDelimiter = unbracketed.indexOf('%')
            if (scopeDelimiter == -1) return unbracketed.lowercase(Locale.US)
            val address = unbracketed.substring(0, scopeDelimiter).lowercase(Locale.US)
            val scope = unbracketed.substring(scopeDelimiter + 1).takeIf(String::isNotEmpty)
                ?: return null
            if (scope.any { !it.isLetterOrDigit() }) return null
            return "$address%$scope"
        }
        return try {
            IDN.toASCII(unbracketed).lowercase(Locale.US)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseAuthority(uri: URI): Authority? {
        uri.host?.let { host ->
            if (uri.port != -1 && uri.port !in 1..65535) return null
            return Authority(host, uri.port)
        }
        val rawAuthority = uri.rawAuthority?.takeIf { it.isNotEmpty() } ?: return null
        if (rawAuthority.startsWith("[") || rawAuthority.contains("@")) return null
        val delimiter = rawAuthority.lastIndexOf(':')
        if (delimiter == -1) return Authority(rawAuthority, -1)
        if (rawAuthority.indexOf(':') != delimiter) return null
        val port = rawAuthority.substring(delimiter + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Authority(rawAuthority.substring(0, delimiter), port)
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
                InetAddress.getByName(host.substringBefore("%"))
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

    private data class Authority(
        val host: String,
        val port: Int,
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
