package com.openaria.openaria_echo_mobile.body.discovery

import android.content.Context
import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import java.util.Base64

data class DeviceHistoryEntry(
    val origin: String,
    val deviceLabel: String,
    val lastConnectedAtMillis: Long,
    val deviceId: String? = null,
)

class DeviceConnectionHistoryStore(
    context: Context,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<DeviceHistoryEntry> {
        return DeviceConnectionHistoryCodec.decode(preferences.getString(KEY_ENTRIES, "").orEmpty())
    }

    fun record(origin: String, deviceLabel: String, deviceId: String? = null) {
        val decision = EndpointPolicy.validate(origin)
        if (decision !is EndpointPolicy.Decision.Allowed) {
            return
        }
        val normalizedDeviceId = normalizeDeviceId(deviceId)
        if (!deviceId.isNullOrBlank() && normalizedDeviceId == null) {
            return
        }
        val normalizedOrigin = decision.target.origin.toString()
        val entry = DeviceHistoryEntry(
            origin = normalizedOrigin,
            deviceLabel = deviceLabel.ifBlank { normalizedOrigin },
            lastConnectedAtMillis = clockMillis(),
            deviceId = normalizedDeviceId,
        )
        val entries = DeviceConnectionHistoryCodec.upsert(load(), entry, MAX_ENTRIES)
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
                entry.deviceId.orEmpty(),
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
                if (parts.size !in 3..4) return@mapNotNull null
                val origin = decodePart(parts[0]) ?: return@mapNotNull null
                val label = decodePart(parts[1]) ?: return@mapNotNull null
                val encodedDeviceId = parts.getOrNull(2).orEmpty().takeIf { parts.size == 4 }
                val deviceId = when {
                    encodedDeviceId == null || encodedDeviceId.isBlank() -> null
                    else -> normalizeDeviceId(encodedDeviceId) ?: return@mapNotNull null
                }
                val lastConnectedAt = parts.last().toLongOrNull() ?: return@mapNotNull null
                if (origin.isBlank() || label.isBlank() || lastConnectedAt <= 0L) {
                    return@mapNotNull null
                }
                DeviceHistoryEntry(origin, label, lastConnectedAt, deviceId)
            }
            .let(::deduplicate)
    }

    fun upsert(
        entries: List<DeviceHistoryEntry>,
        entry: DeviceHistoryEntry,
        maxEntries: Int,
    ): List<DeviceHistoryEntry> {
        return deduplicate(
            sequenceOf(entry) + entries.asSequence().filterNot { existing ->
                existing.origin == entry.origin ||
                    entry.deviceId != null && existing.deviceId == entry.deviceId
            },
        ).take(maxEntries.coerceAtLeast(0))
    }

    private fun deduplicate(entries: Sequence<DeviceHistoryEntry>): List<DeviceHistoryEntry> {
        val origins = mutableSetOf<String>()
        val deviceIds = mutableSetOf<String>()
        return entries.filter { entry ->
            if (entry.origin in origins || entry.deviceId?.let(deviceIds::contains) == true) {
                false
            } else {
                origins += entry.origin
                entry.deviceId?.let(deviceIds::add)
                true
            }
        }.toList()
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

private val DEVICE_ID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)

private fun normalizeDeviceId(deviceId: String?): String? {
    return deviceId
        ?.trim()
        ?.lowercase()
        ?.takeIf(DEVICE_ID_PATTERN::matches)
}
