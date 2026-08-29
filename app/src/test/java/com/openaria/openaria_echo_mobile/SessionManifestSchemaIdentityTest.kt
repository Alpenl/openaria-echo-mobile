package com.openaria.openaria_echo_mobile

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.json.JSONObject

class SessionManifestSchemaIdentityTest {
    @Test
    fun `current session manifest schema matches the structured support identity`() {
        val schemaFile = File("../schemas/ylx-device-session-v2.schema.json")
        val schemaBytes = schemaFile.readBytes()
        val support = JSONObject(File("src/main/assets/device-api-support.json").readText())
            .getJSONObject("deviceApi")
            .getJSONObject("sessionManifestSchema")

        assertEquals("schemas/ylx-device-session-v2.schema.json", support.getString("path"))
        assertEquals("urn:ylx:schema:device-session:v2", support.getString("id"))
        assertEquals(14_034, support.getInt("bytes"))
        assertEquals(
            "7de77a092152cb68d57fc9e46dcc3024fe521dbcf5961999cf0ac887186a59c8",
            support.getString("sha256"),
        )
        assertEquals(support.getInt("bytes"), schemaBytes.size)
        assertEquals(support.getString("sha256"), schemaBytes.sha256())

        assertContains(
            File("../openapi/ylx-device-v4.openapi.yaml").readText(),
            "\$ref: \"../schemas/ylx-device-session-v2.schema.json\"",
        )
    }

    private fun ByteArray.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
