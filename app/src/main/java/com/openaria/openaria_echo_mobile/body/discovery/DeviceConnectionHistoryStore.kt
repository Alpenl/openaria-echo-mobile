package com.openaria.openaria_echo_mobile.body.discovery

import android.content.Context
import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import java.util.Base64

data class DeviceHistoryEntry(
    val origin: String,
    val deviceLabel: String,
    val lastConnectedAtMillis: Long,
)

class DeviceConnectionHistoryStore(
    context: Context,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<DeviceHistoryEntry> {
        return DeviceConnectionHistoryCodec.decode(preferences.getString(KEY_ENTRIES, "").orEmpty())
    }

    fun record(origin: String, deviceLabel: String) {
        val decision = EndpointPolicy.validate(origin)
        if (decision !is EndpointPolicy.Decision.Allowed) {
            return
        }
        val normalizedOrigin = decision.target.origin.toString()
        val entry = DeviceHistoryEntry(
            origin = normalizedOrigin,
            deviceLabel = deviceLabel.ifBlank { normalizedOrigin },
            lastConnectedAtMillis = clockMillis(),
        )
        val entries = (listOf(entry) + load().filterNot { it.origin == normalizedOrigin })
            .take(MAX_ENTRIES)
        preferences.edit()
            .putString(KEY_ENTRIES, DeviceConnectionHistoryCodec.encode(entries))
            .apply()
    }

    private companion object {
        const val PREFERENCES = "openaria_echo_body_history"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 5
    }
}

internal object DeviceConnectionHistoryCodec {
    fun encode(entries: List<DeviceHistoryEntry>): String {
        return entries.joinToString("\n") { entry ->
            listOf(
                encodePart(entry.origin),
                encodePart(entry.deviceLabel),
                entry.lastConnectedAtMillis.toString(),
            ).joinToString("\t")
        }
    }

    fun decode(value: String): List<DeviceHistoryEntry> {
        if (value.isBlank()) {
            return emptyList()
        }
        return value.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size != 3) {
                    return@mapNotNull null
                }
                val origin = decodePart(parts[0]) ?: return@mapNotNull null
                val label = decodePart(parts[1]) ?: return@mapNotNull null
                val lastConnectedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
                if (origin.isBlank() || label.isBlank() || lastConnectedAt <= 0L) {
                    return@mapNotNull null
                }
                DeviceHistoryEntry(origin, label, lastConnectedAt)
            }
            .distinctBy { it.origin }
            .toList()
    }

    private fun encodePart(value: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    private fun decodePart(value: String): String? {
        return try {
            Base64.getUrlDecoder().decode(value).decodeToString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
