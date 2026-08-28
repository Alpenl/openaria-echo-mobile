package com.openaria.openaria_echo_mobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceParityTest {
    @Test
    fun `english strings match default chinese string keys`() {
        val defaultKeys = stringKeys(File("src/main/res/values/strings.xml"))
        val englishKeys = stringKeys(File("src/main/res/values-en/strings.xml"))

        assertEquals(defaultKeys, englishKeys)
    }

    @Test
    fun `default strings are simplified chinese first install resources`() {
        val defaultValues = stringValues(File("src/main/res/values/strings.xml"))

        assertEquals("Open Aria Echo", defaultValues.getValue("app_name"))
        assertEquals("取景", defaultValues.getValue("nav_viewfinder"))
        assertEquals("会话", defaultValues.getValue("nav_sessions"))
        assertEquals("机身", defaultValues.getValue("nav_body"))
        assertEquals("网络", defaultValues.getValue("nav_network"))
        assertTrue(defaultValues.getValue("status_no_body").contains("未连接"))
    }

    @Test
    fun `production UI sources do not reintroduce legacy prototype visible strings`() {
        val uiSources = File("src/main/java/com/openaria/openaria_echo_mobile/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val forbidden = listOf(
            "\"Mount\"",
            "\"Retry\"",
            "\"Probe\"",
            "\"Edit\"",
            "\"Join\"",
            "\"FREE\"",
            "\"TEMP\"",
            "\"Copy URL\"",
            "connection refused",
            "10.42.0.1:8080 · API v4 · pkg",
        )
        val hits = forbidden.filter { uiSources.contains(it) }

        assertTrue(hits.isEmpty(), "Legacy prototype UI strings remain: ${hits.joinToString()}")
    }

    private fun stringKeys(file: File): Set<String> = stringValues(file).keys

    private fun stringValues(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                put(name, node.textContent)
            }
        }
    }
}
