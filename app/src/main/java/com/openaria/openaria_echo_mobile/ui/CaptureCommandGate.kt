package com.openaria.openaria_echo_mobile.ui

import java.util.concurrent.atomic.AtomicBoolean

internal class CaptureCommandGate {
    private val commandInFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = commandInFlight.compareAndSet(false, true)

    fun release() {
        commandInFlight.set(false)
    }
}
