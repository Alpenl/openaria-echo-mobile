package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONObject

class DevelopmentPlanContractIdentityTest {
    @Test
    fun `development plan contract identity matches the structured support manifest`() {
        val plan = File("../docs/DEVELOPMENT_PLAN.md").readText()
        val identityPattern = Regex(
            """移动端已从 `([^`]+)` 接入当前 Device API v4 OpenAPI，路径为 `([^`]+)`，SHA-256 为 `([0-9a-f]{64})`，大小为 ([0-9]+) 字节，`info.version` 为 `([^`]+)`。""",
        )
        val identities = identityPattern.findAll(plan).toList()
        assertEquals(1, identities.size, "DEVELOPMENT_PLAN must contain exactly one structured v4 identity")
        val documented = identities.single().groupValues

        val manifest = JSONObject(File("src/main/assets/device-api-support.json").readText())
        val openApi = manifest.getJSONObject("deviceApi").getJSONObject("openApi")

        assertEquals(openApi.getString("source"), documented[1])
        assertEquals(openApi.getString("path"), documented[2])
        assertEquals(openApi.getString("sha256"), documented[3])
        assertEquals(openApi.getInt("bytes"), documented[4].toInt())
        assertEquals(openApi.getString("infoVersion"), documented[5])
    }
}
