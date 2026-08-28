package com.openaria.openaria_echo_mobile.body.discovery

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceConnectionHistoryStoreTest {
    @Test
    fun `history codec round trips verified origins without plain text separators`() {
        val entries = listOf(
            DeviceHistoryEntry(
                origin = "http://10.42.0.1:8080",
                deviceLabel = "YLX-1234ABCD",
                lastConnectedAtMillis = 1_800_000_000_000L,
                deviceId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
            ),
            DeviceHistoryEntry(
                origin = "https://rp-ylx.local",
                deviceLabel = "YLX-5678ABCD",
                lastConnectedAtMillis = 1_800_000_000_100L,
            ),
        )

        val encoded = DeviceConnectionHistoryCodec.encode(entries)

        assertEquals(entries, DeviceConnectionHistoryCodec.decode(encoded))
    }

    @Test
    fun `history codec ignores malformed or duplicate rows`() {
        val valid = DeviceHistoryEntry(
            origin = "https://rp-ylx.local",
            deviceLabel = "YLX-5678ABCD",
            lastConnectedAtMillis = 1_800_000_000_100L,
        )
        val encoded = listOf(
            "bad-row",
            DeviceConnectionHistoryCodec.encode(listOf(valid)),
            DeviceConnectionHistoryCodec.encode(listOf(valid.copy(deviceLabel = "YLX-00000000"))),
        ).joinToString("\n")

        assertEquals(listOf(valid), DeviceConnectionHistoryCodec.decode(encoded))
    }

    @Test
    fun `history codec reads legacy rows without device identity`() {
        val legacy = listOf(
            encodePart("http://10.42.0.1:8080"),
            encodePart("YLX-LEGACY"),
            "1800000000000",
        ).joinToString("\t")

        assertEquals(
            listOf(
                DeviceHistoryEntry(
                    origin = "http://10.42.0.1:8080",
                    deviceLabel = "YLX-LEGACY",
                    lastConnectedAtMillis = 1_800_000_000_000L,
                    deviceId = null,
                ),
            ),
            DeviceConnectionHistoryCodec.decode(legacy),
        )
    }

    @Test
    fun `verified device identity updates its origin instead of duplicating history`() {
        val deviceId = "56005c52-31f1-4dac-91cd-d8eafd737d1c"
        val old = DeviceHistoryEntry(
            origin = "http://10.42.0.1:8080",
            deviceLabel = "YLX-1234ABCD",
            lastConnectedAtMillis = 1_800_000_000_000L,
            deviceId = deviceId,
        )
        val moved = old.copy(
            origin = "http://192.168.1.20:8080",
            lastConnectedAtMillis = 1_800_000_000_100L,
        )

        assertEquals(listOf(moved), DeviceConnectionHistoryCodec.upsert(listOf(old), moved, 5))
    }

    @Test
    fun `legacy history remains and is replaced when the verified origin matches`() {
        val legacy = DeviceHistoryEntry(
            origin = "http://10.42.0.1:8080",
            deviceLabel = "YLX-LEGACY",
            lastConnectedAtMillis = 1_800_000_000_000L,
        )
        val verified = legacy.copy(
            deviceLabel = "YLX-1234ABCD",
            lastConnectedAtMillis = 1_800_000_000_100L,
            deviceId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
        )

        assertEquals(listOf(verified), DeviceConnectionHistoryCodec.upsert(listOf(legacy), verified, 5))
    }

    private fun encodePart(value: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    }
}
