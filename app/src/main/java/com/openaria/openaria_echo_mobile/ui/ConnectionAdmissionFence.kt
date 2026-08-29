package com.openaria.openaria_echo_mobile.ui

import com.openaria.openaria_echo_mobile.body.api.DeviceAdmissionCandidate
import com.openaria.openaria_echo_mobile.body.api.DeviceConnection
import java.util.concurrent.atomic.AtomicLong

internal data class ConnectionAdmissionAttempt(
    val generation: Long,
    val candidates: List<DeviceAdmissionCandidate>,
)

internal class ConnectionAdmissionFence {
    private val currentGeneration = AtomicLong(0L)

    fun begin(candidates: List<DeviceAdmissionCandidate>): ConnectionAdmissionAttempt {
        return ConnectionAdmissionAttempt(
            generation = currentGeneration.incrementAndGet(),
            candidates = candidates.toList(),
        )
    }

    fun cancel(attempt: ConnectionAdmissionAttempt): Boolean {
        return currentGeneration.compareAndSet(attempt.generation, attempt.generation + 1L)
    }

    fun cancelCurrent() {
        currentGeneration.incrementAndGet()
    }

    fun isCurrent(attempt: ConnectionAdmissionAttempt): Boolean {
        return currentGeneration.get() == attempt.generation
    }

    fun canPublish(
        attempt: ConnectionAdmissionAttempt,
        connection: DeviceConnection,
    ): Boolean {
        return isCurrent(attempt) && attempt.candidates.any { it.matches(connection) }
    }
}

internal fun buildAdmissionCandidates(
    origins: List<String>,
    primaryOrigin: String,
    authorizationOrigin: String?,
    typedToken: String,
    storedTokenForOrigin: (String) -> String?,
): List<DeviceAdmissionCandidate> {
    val token = typedToken.trim()
    val typedTokenTarget = (authorizationOrigin ?: primaryOrigin).trim()
    return origins.map { candidateOrigin ->
        DeviceAdmissionCandidate(
            origin = candidateOrigin,
            bearerToken = when {
                token.isEmpty() -> storedTokenForOrigin(candidateOrigin)
                candidateOrigin.trim() == typedTokenTarget -> token
                else -> null
            },
        )
    }
}

internal fun typedTokenAfterAuthorizationTargetChange(
    currentAuthorizationOrigin: String?,
    nextAuthorizationOrigin: String,
    typedToken: String,
): String {
    return if (currentAuthorizationOrigin?.trim() == nextAuthorizationOrigin.trim()) {
        typedToken
    } else {
        ""
    }
}
