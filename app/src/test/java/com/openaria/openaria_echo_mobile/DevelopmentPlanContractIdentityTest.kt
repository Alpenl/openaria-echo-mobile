package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONObject

class DevelopmentPlanContractIdentityTest {
    @Test
    fun `development plan contract identity matches the structured support manifest`() {
        val plan = File("../docs/DEVELOPMENT_PLAN.md").readText()
        val manifest = JSONObject(File("src/main/assets/device-api-support.json").readText())
        val openApi = manifest.getJSONObject("deviceApi").getJSONObject("openApi")

        val identityPattern = Regex(
            """移动端已从 `([^`]+)` 接入当前 Device API v4 OpenAPI，路径为 `([^`]+)`，SHA-256 为 `([0-9a-f]{64})`，大小为 ([0-9]+) 字节，`info.version` 为 `([^`]+)`。""",
        )
        val identities = identityPattern.findAll(plan).toList()
        assertEquals(1, identities.size, "DEVELOPMENT_PLAN must contain exactly one structured v4 identity")
        val documented = identities.single().groupValues

        assertEquals(openApi.getString("source"), documented[1])
        assertEquals(openApi.getString("path"), documented[2])
        assertEquals(openApi.getString("sha256"), documented[3])
        assertEquals(openApi.getInt("bytes"), documented[4].toInt())
        assertEquals(openApi.getString("infoVersion"), documented[5])

        val documentedHashes = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])""")
            .findAll(plan)
            .map { it.value.lowercase() }
            .toList()
        assertEquals(
            listOf(openApi.getString("sha256")),
            documentedHashes,
            "DEVELOPMENT_PLAN must contain only the current OpenAPI SHA-256 pin",
        )

        val documentedBytePins = Regex("""(?i)(?<![0-9])([0-9]+)\s*(?:bytes|字节)(?!\w)""")
            .findAll(plan)
            .map { it.groupValues[1].toInt() }
            .toList()
        assertEquals(
            listOf(openApi.getInt("bytes")),
            documentedBytePins,
            "DEVELOPMENT_PLAN must contain only the current OpenAPI byte pin",
        )
    }
}
