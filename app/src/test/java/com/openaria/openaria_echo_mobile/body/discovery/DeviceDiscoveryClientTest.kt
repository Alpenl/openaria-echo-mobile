package com.openaria.openaria_echo_mobile.body.discovery

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceDiscoveryClientTest {
    @Test
    fun `late callbacks from an old scan cannot change the new scan`() {
        val backend = FakeDiscoveryBackend()
        val scheduler = FakeDiscoveryScheduler()
        val client = DeviceDiscoveryClient(backend, scheduler)
        val oldStates = mutableListOf<DiscoveryState>()
        val newStates = mutableListOf<DiscoveryState>()

        client.start(oldStates::add)
        val oldScan = backend.latestScan
        oldScan.onServiceFound(service("old-body", "old"))
        client.start(newStates::add)
        val oldStatesAtGenerationChange = oldStates.toList()
        val newScan = backend.latestScan
        newScan.onServiceFound(service("new-body", "new"))

        backend.succeedNext(resolved("old-body", "192.168.1.10"))
        oldScan.onDiscoveryStopped(DeviceDiscoveryClient.SERVICE_TYPE)
        backend.succeedNext(resolved("new-body", "192.168.1.20"))

        assertEquals(oldStatesAtGenerationChange, oldStates)
        assertEquals(listOf("new-body"), assertIs<DiscoveryState.Scanning>(newStates.last()).bodies.map { it.serviceName })
    }

    @Test
    fun `resolver queue handles fifty simultaneous services one at a time`() {
        val backend = FakeDiscoveryBackend()
        val client = DeviceDiscoveryClient(backend, FakeDiscoveryScheduler())
        val states = mutableListOf<DiscoveryState>()
        client.start(states::add)

        repeat(50) { index -> backend.latestScan.onServiceFound(service("body-$index", index)) }
        repeat(50) { index ->
            assertEquals(1, backend.activeResolutionCount)
            backend.succeedNext(resolved("body-$index", "192.168.1.${index + 1}"))
        }

        val bodies = assertIs<DiscoveryState.Scanning>(states.last()).bodies
        assertEquals(50, bodies.size)
        assertEquals(1, backend.maxActiveResolutionCount)
        assertEquals(50, backend.resolveCalls.size)
    }

    @Test
    fun `duplicate found with a different native object is merged`() {
        val backend = FakeDiscoveryBackend()
        val client = DeviceDiscoveryClient(backend, FakeDiscoveryScheduler())
        val states = mutableListOf<DiscoveryState>()
        client.start(states::add)
        val first = service("body-a", Any())

        backend.latestScan.onServiceFound(first)
        backend.latestScan.onServiceFound(service("body-a", Any()))
        assertEquals(1, backend.resolveCalls.size)
        backend.succeedNext(resolved("body-a", "192.168.1.10"))

        val body = assertIs<DiscoveryState.Scanning>(states.last()).bodies.single()
        assertEquals("http://192.168.1.10:8080", body.origin)
        assertTrue(body.isOnline)
    }

    @Test
    fun `lost marks a body offline and reappear replaces endpoints`() {
        val backend = FakeDiscoveryBackend()
        val client = DeviceDiscoveryClient(backend, FakeDiscoveryScheduler())
        val states = mutableListOf<DiscoveryState>()
        client.start(states::add)
        val firstHandle = Any()
        val first = service("body-a", firstHandle)

        backend.latestScan.onServiceFound(first)
        backend.succeedNext(resolved("body-a", "192.168.1.10"))
        backend.latestScan.onServiceLost(first)
        val offline = assertIs<DiscoveryState.Scanning>(states.last()).bodies.single()
        assertEquals("http://192.168.1.10:8080", offline.origin)
        assertEquals(false, offline.isOnline)

        val second = service("body-a", Any())
        backend.latestScan.onServiceFound(second)
        backend.latestScan.onServiceLost(service("body-a", firstHandle))

        backend.succeedNext(resolved("body-a", "192.168.1.11"))

        val body = assertIs<DiscoveryState.Scanning>(states.last()).bodies.single()
        assertEquals("http://192.168.1.11:8080", body.origin)
        assertEquals(listOf("http://192.168.1.11:8080"), body.origins)
        assertTrue(body.isOnline)
    }

    @Test
    fun `temporary resolver failure retries after other queued services`() {
        val backend = FakeDiscoveryBackend()
        val scheduler = FakeDiscoveryScheduler()
        val client = DeviceDiscoveryClient(backend, scheduler)
        val states = mutableListOf<DiscoveryState>()
        client.start(states::add)
        backend.latestScan.onServiceFound(service("body-a", "a"))
        backend.latestScan.onServiceFound(service("body-b", "b"))

        backend.failNext(3)
        assertEquals("body-b", backend.activeServiceName)
        scheduler.advanceBy(500L)
        backend.succeedNext(resolved("body-b", "192.168.1.12"))
        assertEquals("body-a", backend.activeServiceName)
        backend.succeedNext(resolved("body-a", "192.168.1.11"))

        assertEquals(listOf("body-a", "body-b", "body-a"), backend.resolveCalls.map { it.serviceName })
        assertEquals(setOf("body-a", "body-b"), assertIs<DiscoveryState.Scanning>(states.last()).bodies.map { it.serviceName }.toSet())
    }

    @Test
    fun `resolved service preserves scoped IPv6 and all deterministic candidates`() {
        val backend = FakeDiscoveryBackend()
        val client = DeviceDiscoveryClient(backend, FakeDiscoveryScheduler())
        val states = mutableListOf<DiscoveryState>()
        client.start(states::add)
        backend.latestScan.onServiceFound(service("body-a", "a"))
        val scopedLinkLocal = Inet6Address.getByAddress(null, LINK_LOCAL_BYTES, 7)

        backend.succeedNext(
            ResolvedDiscoveryService(
                serviceName = "body-a",
                port = 8080,
                addresses = listOf(scopedLinkLocal, InetAddress.getByName("192.168.1.11")),
                hostname = "BODY-A.LOCAL.",
            ),
        )

        val body = assertIs<DiscoveryState.Scanning>(states.last()).bodies.single()
        assertEquals("http://192.168.1.11:8080", body.origin)
        assertEquals(3, body.origins.size)
        assertTrue(body.origins[1].startsWith("http://[fe80:"))
        assertTrue(body.origins[1].contains("%7]:8080"))
        assertEquals("http://body-a.local:8080", body.origins[2])
    }

    @Test
    fun `late resolution cannot return a stopped scan to scanning`() {
        val backend = FakeDiscoveryBackend()
        val client = DeviceDiscoveryClient(backend, FakeDiscoveryScheduler())
        val states = mutableListOf<DiscoveryState>()
        client.start(states::add)
        backend.latestScan.onServiceFound(service("body-a", "a"))

        backend.latestScan.onDiscoveryStopped(DeviceDiscoveryClient.SERVICE_TYPE)
        backend.succeedNext(resolved("body-a", "192.168.1.11"))

        val idle = assertIs<DiscoveryState.Idle>(states.last())
        assertTrue(idle.bodies.isEmpty())
    }

    @Test
    fun `permission failure and empty scan timeout are explicit states`() {
        val deniedBackend = FakeDiscoveryBackend().apply {
            startFailure = SecurityException("nearby devices permission denied")
        }
        val deniedStates = mutableListOf<DiscoveryState>()
        DeviceDiscoveryClient(deniedBackend, FakeDiscoveryScheduler()).start(deniedStates::add)

        assertEquals(
            DeviceDiscoveryClient.ERROR_PERMISSION,
            assertIs<DiscoveryState.Failed>(deniedStates.last()).errorCode,
        )

        val scheduler = FakeDiscoveryScheduler()
        val timeoutStates = mutableListOf<DiscoveryState>()
        DeviceDiscoveryClient(FakeDiscoveryBackend(), scheduler).start(timeoutStates::add)
        scheduler.advanceBy(15_000L)

        assertEquals(
            DeviceDiscoveryClient.ERROR_SCAN_TIMEOUT,
            assertIs<DiscoveryState.Scanning>(timeoutStates.last()).warningCode,
        )
    }

    @Test
    fun `a device found after scan timeout clears the stale scan warning`() {
        val backend = FakeDiscoveryBackend()
        val scheduler = FakeDiscoveryScheduler()
        val states = mutableListOf<DiscoveryState>()
        val client = DeviceDiscoveryClient(backend, scheduler)
        client.start(states::add)
        scheduler.advanceBy(15_000L)

        backend.latestScan.onServiceFound(service("body-a", Any()))
        backend.succeedNext(resolved("body-a", "192.168.1.11"))

        val state = assertIs<DiscoveryState.Scanning>(states.last())
        assertEquals(null, state.warningCode)
        assertEquals(listOf("body-a"), state.bodies.map { it.serviceName })
    }

    @Test
    fun `permanent resolver failure is visible and does not block the queue`() {
        val backend = FakeDiscoveryBackend()
        val client = DeviceDiscoveryClient(backend, FakeDiscoveryScheduler())
        val states = mutableListOf<DiscoveryState>()
        client.start(states::add)
        backend.latestScan.onServiceFound(service("body-a", "a"))
        backend.latestScan.onServiceFound(service("body-b", "b"))

        backend.failNext(6)
        backend.succeedNext(resolved("body-b", "192.168.1.12"))

        val state = assertIs<DiscoveryState.Scanning>(states.last())
        assertEquals(6, state.warningCode)
        assertEquals(listOf("body-b"), state.bodies.map { it.serviceName })
    }

    @Test
    fun `new generation advances when old resolver cancellation callback never arrives`() {
        val backend = FakeDiscoveryBackend().apply {
            cancellationSupported = true
            deliverCancellationCallback = false
        }
        val scheduler = FakeDiscoveryScheduler()
        val client = DeviceDiscoveryClient(backend, scheduler)
        val newStates = mutableListOf<DiscoveryState>()
        client.start { }
        backend.latestScan.onServiceFound(service("old-body", "old"))

        client.start(newStates::add)
        backend.latestScan.onServiceFound(service("new-body", "new"))
        assertEquals(listOf("old-body"), backend.resolveCalls.map { it.serviceName })

        scheduler.advanceBy(1_000L)
        assertEquals("new-body", backend.activeServiceName)
        backend.succeedNext(resolved("new-body", "192.168.1.20"))

        assertEquals(1, backend.maxActiveResolutionCount)
        assertEquals(listOf("new-body"), assertIs<DiscoveryState.Scanning>(newStates.last()).bodies.map { it.serviceName })
    }

    @Test
    fun `resolver timeout cancels its lease and advances the queue`() {
        val backend = FakeDiscoveryBackend().apply {
            cancellationSupported = true
            deliverCancellationCallback = false
        }
        val scheduler = FakeDiscoveryScheduler()
        val client = DeviceDiscoveryClient(backend, scheduler)
        val states = mutableListOf<DiscoveryState>()
        client.start(states::add)
        backend.latestScan.onServiceFound(service("body-a", Any()))
        backend.latestScan.onServiceFound(service("body-b", Any()))

        scheduler.advanceBy(10_500L)

        assertEquals("body-b", backend.activeServiceName)
        assertEquals(
            DeviceDiscoveryClient.ERROR_RESOLUTION_TIMEOUT,
            assertIs<DiscoveryState.Scanning>(states.last()).warningCode,
        )
        backend.succeedNext(resolved("body-b", "192.168.1.12"))
        assertEquals(1, backend.maxActiveResolutionCount)
    }

    @Test
    fun `permission loss while starting a resolver is explicit and is not retried`() {
        val backend = FakeDiscoveryBackend().apply {
            resolutionStartFailure = SecurityException("nearby devices permission revoked")
        }
        val scheduler = FakeDiscoveryScheduler()
        val states = mutableListOf<DiscoveryState>()
        val client = DeviceDiscoveryClient(backend, scheduler)
        client.start(states::add)

        backend.latestScan.onServiceFound(service("body-a", Any()))
        scheduler.advanceBy(1_000L)

        assertEquals(1, backend.resolveCalls.size)
        assertEquals(
            DeviceDiscoveryClient.ERROR_PERMISSION,
            assertIs<DiscoveryState.Scanning>(states.last()).warningCode,
        )
    }

    @Test
    fun `losing the failed service clears its resolver warning`() {
        val backend = FakeDiscoveryBackend()
        val states = mutableListOf<DiscoveryState>()
        val client = DeviceDiscoveryClient(backend, FakeDiscoveryScheduler())
        client.start(states::add)
        val failed = service("body-a", Any())

        backend.latestScan.onServiceFound(failed)
        backend.failNext(6)
        assertEquals(6, assertIs<DiscoveryState.Scanning>(states.last()).warningCode)

        backend.latestScan.onServiceLost(failed)
        assertEquals(null, assertIs<DiscoveryState.Scanning>(states.last()).warningCode)
    }

    private fun service(name: String, handle: Any): DiscoveryService {
        return DiscoveryService(
            serviceName = name,
            serviceType = DeviceDiscoveryClient.SERVICE_TYPE,
            nativeHandle = handle,
        )
    }

    private fun resolved(name: String, address: String): ResolvedDiscoveryService {
        return ResolvedDiscoveryService(
            serviceName = name,
            port = 8080,
            addresses = listOf(InetAddress.getByName(address)),
            hostname = null,
        )
    }

    private class FakeDiscoveryBackend : DiscoveryNsdBackend {
        val scans = mutableListOf<DiscoveryNsdCallbacks>()
        val resolveCalls = mutableListOf<DiscoveryService>()
        private val pending = ArrayDeque<PendingResolution>()
        var activeResolutionCount = 0
            private set
        var maxActiveResolutionCount = 0
            private set
        var startFailure: RuntimeException? = null
        var resolutionStartFailure: RuntimeException? = null
        var cancellationSupported = false
        var deliverCancellationCallback = true

        val latestScan: DiscoveryNsdCallbacks
            get() = scans.last()

        val activeServiceName: String?
            get() = pending.firstOrNull()?.service?.serviceName

        override fun start(callbacks: DiscoveryNsdCallbacks): Any {
            startFailure?.let { throw it }
            scans += callbacks
            callbacks.onDiscoveryStarted(DeviceDiscoveryClient.SERVICE_TYPE)
            return callbacks
        }

        override fun stop(handle: Any) = Unit

        override fun resolve(service: DiscoveryService, callbacks: DiscoveryResolveCallbacks): Any {
            resolveCalls += service
            resolutionStartFailure?.let { throw it }
            val handle = Any()
            pending += PendingResolution(handle, service, callbacks)
            activeResolutionCount += 1
            maxActiveResolutionCount = maxOf(maxActiveResolutionCount, activeResolutionCount)
            return handle
        }

        override fun cancelResolution(handle: Any): Boolean {
            if (!cancellationSupported) return false
            val resolution = pending.firstOrNull { it.handle === handle } ?: return true
            pending.remove(resolution)
            activeResolutionCount -= 1
            if (deliverCancellationCallback) {
                resolution.callbacks.onResolutionStopped(resolution.service)
            }
            return true
        }

        fun succeedNext(resolved: ResolvedDiscoveryService) {
            val resolution = pending.removeFirst()
            activeResolutionCount -= 1
            resolution.callbacks.onServiceResolved(resolved)
        }

        fun failNext(errorCode: Int) {
            val resolution = pending.removeFirst()
            activeResolutionCount -= 1
            resolution.callbacks.onResolveFailed(resolution.service, errorCode)
        }

        private data class PendingResolution(
            val handle: Any,
            val service: DiscoveryService,
            val callbacks: DiscoveryResolveCallbacks,
        )
    }

    private class FakeDiscoveryScheduler : DiscoveryScheduler {
        private val delayed = mutableListOf<ScheduledTask>()
        private var nowMillis = 0L

        override fun execute(task: () -> Unit) = task()

        override fun executeDelayed(delayMillis: Long, task: () -> Unit) {
            delayed += ScheduledTask(nowMillis + delayMillis, task)
        }

        fun advanceBy(durationMillis: Long) {
            val target = nowMillis + durationMillis
            while (true) {
                val next = delayed.filter { it.dueMillis <= target }.minByOrNull { it.dueMillis } ?: break
                delayed.remove(next)
                nowMillis = next.dueMillis
                next.task()
            }
            nowMillis = target
        }

        private data class ScheduledTask(val dueMillis: Long, val task: () -> Unit)
    }

    private companion object {
        val LINK_LOCAL_BYTES = byteArrayOf(
            0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1,
        )
    }
}
