package com.openaria.openaria_echo_mobile.ui

import com.openaria.openaria_echo_mobile.body.api.SessionDiscoveryDiagnostic
import com.openaria.openaria_echo_mobile.body.api.SessionListPage

internal data class SessionDiagnosticPresentation(
    val code: String,
    val message: String,
    val observedAt: String,
    val quarantineId: String,
)

internal fun SessionDiscoveryDiagnostic.toReadOnlyPresentation(): SessionDiagnosticPresentation {
    return SessionDiagnosticPresentation(
        code = code,
        message = message,
        observedAt = observedAt,
        quarantineId = quarantineId,
    )
}

internal fun SessionListPage.readOnlyDiagnosticPresentations(): List<SessionDiagnosticPresentation> {
    return diagnostics.map { it.toReadOnlyPresentation() }
}
