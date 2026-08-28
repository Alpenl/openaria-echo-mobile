package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class VisualEvidenceBaselineTest {
    @Test
    fun `dogfood report and screenshots remain available for visual regression review`() {
        val report = File("../dogfood-output/report.md")
        val screenshots = listOf(
            "initial.png",
            "issue-003-result.png",
            "issue-004-nav-shift-comparison.png",
            "issue-007-font-overlap-annotated.png",
            "issue-007-net-overlap-annotated.png",
            "issue-008-font-scale.png",
        ).map { File("../dogfood-output/screenshots/$it") }

        assertTrue(report.isFile && report.length() > 4_000L, "dogfood visual report is missing or too small")
        screenshots.forEach { screenshot ->
            assertTrue(
                screenshot.isFile && screenshot.length() > 100_000L,
                "visual baseline screenshot is missing or empty: ${screenshot.path}",
            )
        }

        val body = report.readText()
        assertContains(body, "顶部/底部导航")
        assertContains(body, "摄像头开孔")
        assertContains(body, "底部导航相差")
        assertContains(body, "默认界面中英混排")
        assertContains(body, "放大字体")
        assertContains(body, "无障碍")
    }
}
