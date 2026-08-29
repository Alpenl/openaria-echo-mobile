package com.openaria.openaria_echo_mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.openaria.openaria_echo_mobile.ui.SessionDiagnosticContent
import com.openaria.openaria_echo_mobile.ui.SessionDiagnosticKind
import com.openaria.openaria_echo_mobile.ui.SessionDiagnosticPresentation
import com.openaria.openaria_echo_mobile.ui.SessionDiagnosticReason
import org.junit.Rule
import org.junit.Test

class SessionDiagnosticComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rawLegacyDetailAppearsOnlyAfterExplicitExpansion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rawDetail = "validator process exited with status 7"
        val presentation = SessionDiagnosticPresentation(
            stableKey = "verification:test:0:legacy",
            kind = SessionDiagnosticKind.GATEWAY_VERIFICATION,
            reason = SessionDiagnosticReason.VERIFICATION_LEGACY,
            code = "legacy_v2_verification_diagnostic",
            rawDetail = rawDetail,
            sessionId = "01991b70-7c88-7123-9234-123456789abc",
        )

        compose.setContent {
            SessionDiagnosticContent(presentation)
        }

        compose.onNodeWithText(
            context.getString(R.string.session_reason_verification_legacy),
        ).assertIsDisplayed()
        compose.onAllNodesWithText(
            context.getString(R.string.session_diagnostic_raw_detail, rawDetail),
        ).assertCountEquals(0)

        compose.onNodeWithText(
            context.getString(R.string.session_diagnostic_show_details),
        ).performClick()

        compose.onNodeWithText(
            context.getString(R.string.session_diagnostic_raw_detail, rawDetail),
        ).assertIsDisplayed()
    }
}
