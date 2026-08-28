package com.openaria.openaria_echo_mobile.body.discovery

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
}
