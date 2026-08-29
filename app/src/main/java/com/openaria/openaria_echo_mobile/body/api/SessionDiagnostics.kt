package com.openaria.openaria_echo_mobile.body.api

/**
 * A retained read-model record for a discovery result that could not be
 * promoted to a downloadable [SessionSummary]. The wire contract calls the
 * human-readable field `message`; the read model calls it `summary` so all
 * diagnostic surfaces use the same terminology without changing the wire
 * shape.
 */
data class RetainedQuarantineDiagnostic(
    val quarantineId: String,
    val code: String,
    val summary: String,
    val observedAt: String,
) {
    /** The original wire name, retained for callers that need it. */
    val message: String
        get() = summary
}

/**
 * Stable read model for a session-list page.
 *
 * Quarantine records are intentionally a separate collection. They have no
 * session identity and therefore cannot be downloaded, selected, or rendered
 * as a [SessionSummary]. Gateway verification diagnostics remain nested on
 * each downloadable session and are not flattened into this collection.
 */
data class SessionListReadModel(
    val downloadableSessions: List<SessionSummary>,
    val quarantineDiagnostics: List<RetainedQuarantineDiagnostic>,
) {
    companion object {
        fun from(page: SessionListPage): SessionListReadModel {
            return SessionListReadModel(
                downloadableSessions = page.items.toList(),
                quarantineDiagnostics = page.diagnostics
                    .map(SessionDiscoveryDiagnostic::toRetainedQuarantineDiagnostic)
                    .toList(),
            )
        }
    }
}

fun SessionListPage.toSessionListReadModel(): SessionListReadModel = SessionListReadModel.from(this)

fun SessionDiscoveryDiagnostic.toRetainedQuarantineDiagnostic(): RetainedQuarantineDiagnostic {
    return RetainedQuarantineDiagnostic(
        quarantineId = quarantineId,
        code = code,
        summary = message,
        observedAt = observedAt,
    )
}
