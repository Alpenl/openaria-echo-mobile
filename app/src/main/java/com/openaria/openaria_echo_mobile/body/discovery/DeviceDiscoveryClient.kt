package com.openaria.openaria_echo_mobile.body.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import kotlin.math.absoluteValue

@Suppress("DEPRECATION")
class DeviceDiscoveryClient internal constructor(
    private val backend: DiscoveryNsdBackend,
    private val scheduler: DiscoveryScheduler,
) {
    constructor(context: Context) : this(
        backend = AndroidDiscoveryNsdBackend(context.applicationContext),
        scheduler = HandlerDiscoveryScheduler(Handler(Looper.getMainLooper())),
    )

    private val lock = Any()
    private val resolutionQueue = ArrayDeque<ResolutionRequest>()
    private var generationCounter = 0L
    private var currentSession: DiscoverySession? = null
    private var activeResolution: ResolutionLease? = null

    fun start(onStateChanged: (DiscoveryState) -> Unit) {
        stop()
        val generation = synchronized(lock) {
            generationCounter += 1L
            DiscoverySession(
                generation = generationCounter,
                onStateChanged = onStateChanged,
            ).also { currentSession = it }.generation
        }
        publish(generation, DiscoveryState.Scanning(emptyList(), null))

        val callbacks = callbacksFor(generation)
        val handle = try {
            backend.start(callbacks)
        } catch (exception: DiscoveryBackendException) {
            failStart(generation, exception.errorCode)
            return
        } catch (_: SecurityException) {
            failStart(generation, ERROR_PERMISSION)
            return
        } catch (_: RuntimeException) {
            failStart(generation, ERROR_RUNTIME)
            return
        }

        val shouldStopStaleHandle = synchronized(lock) {
            val session = currentSession
            if (session?.generation == generation && session.discoveryActive) {
                session.discoveryHandle = handle
                false
            } else {
                true
            }
        }
        if (shouldStopStaleHandle) stopBackend(handle)

        scheduler.executeDelayed(SCAN_WARNING_DELAY_MILLIS) {
            val state = synchronized(lock) {
                val session = currentSession
                if (
                    session?.generation != generation ||
                    !session.discoveryActive ||
                    session.bodiesByService.values.any(DiscoveredBody::isOnline)
                ) {
                    null
                } else {
                    session.scanWarningCode = ERROR_SCAN_TIMEOUT
                    session.scanningState()
                }
            }
            state?.let { publish(generation, it) }
        }
    }

    fun stop() {
        val work = synchronized(lock) {
            val session = currentSession ?: return@synchronized null
            currentSession = null
            generationCounter += 1L
            resolutionQueue.removeAll { it.generation == session.generation }
            StopWork(
                discoveryHandle = session.discoveryHandle,
                resolutionLease = activeResolution?.takeIf {
                    it.request.generation == session.generation
                },
            )
        }
        work?.discoveryHandle?.let(::stopBackend)
        work?.resolutionLease?.let(::requestResolutionStop)
    }

    private fun callbacksFor(generation: Long): DiscoveryNsdCallbacks {
        return object : DiscoveryNsdCallbacks {
            override fun onDiscoveryStarted(serviceType: String) {
                currentScanningState(generation)?.let { publish(generation, it) }
            }

            override fun onServiceFound(service: DiscoveryService) {
                if (!service.matchesSupportedType()) return
                val shouldPump = synchronized(lock) {
                    val session = currentSession
                    if (session?.generation != generation || !session.discoveryActive) {
                        return@synchronized false
                    }
                    session.scanWarningCode = null
                    val key = service.instanceKey()
                    val existing = session.services[key]
                    if (existing != null) {
                        return@synchronized false
                    }
                    session.nextServiceRevision += 1L
                    val revision = session.nextServiceRevision
                    session.services[key] = ServiceRecord(service, revision)
                    resolutionQueue.removeAll {
                        it.generation == generation && it.serviceKey == key
                    }
                    resolutionQueue += ResolutionRequest(
                        generation = generation,
                        serviceKey = key,
                        serviceRevision = revision,
                        service = service,
                        attempt = 1,
                    )
                    true
                }
                if (shouldPump) pumpResolutionQueue()
            }

            override fun onServiceLost(service: DiscoveryService) {
                val outcome = synchronized(lock) {
                    val session = currentSession
                    if (session?.generation != generation || !session.discoveryActive) {
                        return@synchronized null
                    }
                    val key = service.instanceKey()
                    val record = session.services[key] ?: return@synchronized null
                    if (record.service.nativeHandle !== service.nativeHandle) {
                        return@synchronized null
                    }
                    session.services.remove(key)
                    session.bodiesByService[key]?.let { body ->
                        session.bodiesByService[key] = body.copy(isOnline = false)
                    }
                    session.resolutionWarnings.remove(key)
                    resolutionQueue.removeAll {
                        it.generation == generation && it.serviceKey == key
                    }
                    LostServiceOutcome(
                        state = session.scanningState(),
                        resolutionLease = activeResolution?.takeIf {
                            it.request.generation == generation &&
                                it.request.serviceKey == key &&
                                it.request.serviceRevision == record.revision
                        },
                    )
                }
                outcome?.state?.let { publish(generation, it) }
                outcome?.resolutionLease?.let(::requestResolutionStop)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                val outcome = synchronized(lock) {
                    val session = currentSession
                    if (session?.generation != generation || !session.discoveryActive) {
                        return@synchronized null
                    }
                    session.discoveryActive = false
                    resolutionQueue.removeAll { it.generation == generation }
                    StopDiscoveryOutcome(
                        state = DiscoveryState.Idle(session.bodies()),
                        resolutionLease = activeResolution?.takeIf {
                            it.request.generation == generation
                        },
                    )
                }
                outcome?.state?.let { publish(generation, it) }
                outcome?.resolutionLease?.let(::requestResolutionStop)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                failStart(generation, errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                val state = synchronized(lock) {
                    val session = currentSession
                    if (session?.generation != generation) null else {
                        DiscoveryState.Failed(session.bodies(), errorCode)
                    }
                }
                state?.let { publish(generation, it) }
            }
        }
    }

    private fun failStart(generation: Long, errorCode: Int) {
        val outcome = synchronized(lock) {
            val session = currentSession
            if (session?.generation != generation) null else {
                session.discoveryActive = false
                resolutionQueue.removeAll { it.generation == generation }
                StopDiscoveryOutcome(
                    state = DiscoveryState.Failed(session.bodies(), errorCode),
                    resolutionLease = activeResolution?.takeIf {
                        it.request.generation == generation
                    },
                )
            }
        }
        outcome?.state?.let { publish(generation, it) }
        outcome?.resolutionLease?.let(::requestResolutionStop)
    }

    private fun pumpResolutionQueue() {
        val lease = synchronized(lock) {
            if (activeResolution != null) return@synchronized null
            var next: ResolutionRequest? = null
            while (resolutionQueue.isNotEmpty() && next == null) {
                val candidate = resolutionQueue.removeFirst()
                if (candidate.isCurrent()) next = candidate
            }
            next?.let(::ResolutionLease)?.also { activeResolution = it }
        } ?: return
        val request = lease.request

        val resolutionHandle = try {
            backend.resolve(
                request.service,
                object : DiscoveryResolveCallbacks {
                    override fun onResolveFailed(service: DiscoveryService, errorCode: Int) {
                        finishResolutionFailure(lease, errorCode)
                    }

                    override fun onServiceResolved(service: ResolvedDiscoveryService) {
                        finishResolutionSuccess(lease, service)
                    }

                    override fun onResolutionStopped(service: DiscoveryService) {
                        releaseResolutionLease(lease)
                    }

                    override fun onStopResolutionFailed(service: DiscoveryService, errorCode: Int) {
                        finishResolutionStopFailure(lease, errorCode)
                    }
                },
            )
        } catch (_: SecurityException) {
            finishResolutionFailure(lease, ERROR_PERMISSION)
            return
        } catch (_: RuntimeException) {
            finishResolutionFailure(lease, ERROR_RUNTIME)
            return
        }

        val stillCurrent = synchronized(lock) {
            if (activeResolution !== lease) {
                null
            } else {
                lease.handle = resolutionHandle
                request.isCurrent()
            }
        }
        when (stillCurrent) {
            true -> scheduler.executeDelayed(RESOLUTION_TIMEOUT_MILLIS) {
                requestResolutionStop(lease, ERROR_RESOLUTION_TIMEOUT)
            }
            false -> requestResolutionStop(lease)
            null -> Unit
        }
    }

    private fun finishResolutionSuccess(
        lease: ResolutionLease,
        resolved: ResolvedDiscoveryService,
    ) {
        val request = lease.request
        val state = synchronized(lock) {
            if (activeResolution !== lease) return@synchronized null
            activeResolution = null
            val session = currentSession
            val record = session?.services?.get(request.serviceKey)
            if (
                session?.generation != request.generation ||
                !session.discoveryActive ||
                record?.revision != request.serviceRevision
            ) {
                null
            } else {
                val body = resolved.toDiscoveredBody(request.service.serviceName)
                if (body == null) {
                    session.resolutionWarnings[request.serviceKey] = ERROR_NO_ADDRESS
                } else {
                    session.resolutionWarnings.remove(request.serviceKey)
                    session.bodiesByService[request.serviceKey] = body.copy(isOnline = true)
                }
                session.scanningState()
            }
        }
        state?.let { publish(request.generation, it) }
        pumpResolutionQueue()
    }

    private fun finishResolutionFailure(lease: ResolutionLease, errorCode: Int) {
        val request = lease.request
        var retry = false
        val state = synchronized(lock) {
            if (activeResolution !== lease) return@synchronized null
            activeResolution = null
            val session = currentSession
            val record = session?.services?.get(request.serviceKey)
            val stillCurrent = session?.generation == request.generation &&
                session.discoveryActive &&
                record?.revision == request.serviceRevision
            retry = stillCurrent &&
                request.attempt < MAX_RESOLUTION_ATTEMPTS &&
                errorCode.isRetryableResolutionFailure()
            if (stillCurrent && !retry) {
                session.resolutionWarnings[request.serviceKey] = errorCode
                session.scanningState()
            } else {
                null
            }
        }
        state?.let { publish(request.generation, it) }
        if (retry) {
            scheduler.executeDelayed(request.retryDelayMillis()) {
                val queued = synchronized(lock) {
                    if (!request.isCurrent()) {
                        false
                    } else {
                        resolutionQueue += request.copy(attempt = request.attempt + 1)
                        true
                    }
                }
                if (queued) pumpResolutionQueue()
            }
        }
        pumpResolutionQueue()
    }

    private fun requestResolutionStop(
        lease: ResolutionLease,
        warningCode: Int? = null,
    ) {
        val work = synchronized(lock) {
            if (activeResolution !== lease) return@synchronized null
            lease.stopRequested = true
            val request = lease.request
            val session = currentSession
            val state = if (warningCode != null && request.isCurrent()) {
                session?.resolutionWarnings?.set(request.serviceKey, warningCode)
                session?.scanningState()
            } else {
                null
            }
            val scheduleRelease = !lease.releaseScheduled
            lease.releaseScheduled = true
            ResolutionStopWork(
                state = state,
                handle = lease.handle?.takeIf { !lease.stopAttempted }
                    ?.also { lease.stopAttempted = true },
                scheduleRelease = scheduleRelease,
            )
        } ?: return
        work.state?.let { publish(lease.request.generation, it) }
        work.handle?.let { handle ->
            try {
                backend.cancelResolution(handle)
            } catch (_: RuntimeException) {
                // The bounded lease release below still lets later requests progress.
            }
        }
        if (work.scheduleRelease) {
            scheduler.executeDelayed(RESOLUTION_CANCEL_GRACE_MILLIS) {
                releaseResolutionLease(lease)
            }
        }
    }

    private fun finishResolutionStopFailure(lease: ResolutionLease, errorCode: Int) {
        val state = synchronized(lock) {
            if (activeResolution !== lease) return@synchronized null
            val request = lease.request
            val session = currentSession
            if (!request.isCurrent()) {
                null
            } else {
                session?.resolutionWarnings?.putIfAbsent(request.serviceKey, errorCode)
                session?.scanningState()
            }
        }
        state?.let { publish(lease.request.generation, it) }
    }

    private fun releaseResolutionLease(lease: ResolutionLease) {
        val released = synchronized(lock) {
            if (activeResolution !== lease) {
                false
            } else {
                activeResolution = null
                true
            }
        }
        if (released) pumpResolutionQueue()
    }

    private fun ResolutionRequest.isCurrent(): Boolean {
        val session = currentSession
        val record = session?.services?.get(serviceKey)
        return session?.generation == generation &&
            session.discoveryActive &&
            record?.revision == serviceRevision
    }

    private fun currentScanningState(generation: Long): DiscoveryState.Scanning? {
        return synchronized(lock) {
            val session = currentSession
            if (session?.generation != generation || !session.discoveryActive) null else session.scanningState()
        }
    }

    private fun publish(generation: Long, state: DiscoveryState) {
        scheduler.execute {
            val delivery = synchronized(lock) {
                val session = currentSession?.takeIf { it.generation == generation }
                    ?: return@synchronized null
                val currentState = when (state) {
                    is DiscoveryState.Scanning -> {
                        if (!session.discoveryActive) return@synchronized null
                        session.scanningState()
                    }
                    is DiscoveryState.Idle -> {
                        if (session.discoveryActive) return@synchronized null
                        DiscoveryState.Idle(session.bodies())
                    }
                    is DiscoveryState.Failed -> state
                }
                session.onStateChanged to currentState
            }
            delivery?.first?.invoke(delivery.second)
        }
    }

    private fun stopBackend(handle: Any) {
        try {
            backend.stop(handle)
        } catch (_: RuntimeException) {
            // A late platform stop callback belongs to the invalidated generation.
        }
    }

    private fun ResolvedDiscoveryService.toDiscoveredBody(fallbackServiceName: String): DiscoveredBody? {
        if (port !in 1..65535) return null
        val candidates = buildList {
            addresses.forEach { address ->
                val host = address.endpointHost()
                if (host.isEmpty()) return@forEach
                val hostForUri = if (address is Inet6Address) "[$host]" else host
                add(
                    EndpointCandidate(
                        host = host,
                        origin = "http://$hostForUri:$port",
                        priority = when {
                            address is Inet4Address -> 0
                            address is Inet6Address && !address.isLinkLocalAddress -> 1
                            address is Inet6Address -> 2
                            else -> 3
                        },
                    ),
                )
            }
            hostname
                ?.trim()
                ?.trimEnd('.')
                ?.takeIf(String::isNotEmpty)
                ?.let { host -> add(EndpointCandidate(host, "http://$host:$port", 3)) }
        }
            .mapNotNull { candidate ->
                when (val decision = EndpointPolicy.validate(candidate.origin)) {
                    is EndpointPolicy.Decision.Allowed -> candidate.copy(origin = decision.target.origin.toString())
                    is EndpointPolicy.Decision.Rejected -> null
                }
            }
            .distinctBy(EndpointCandidate::origin)
            .sortedWith(compareBy<EndpointCandidate> { it.priority }.thenBy { it.origin })
        val primary = candidates.firstOrNull() ?: return null
        return DiscoveredBody(
            serviceName = serviceName.ifBlank { fallbackServiceName },
            host = primary.host,
            port = port,
            origin = primary.origin,
            origins = candidates.map(EndpointCandidate::origin),
        )
    }

    private fun DiscoveryService.matchesSupportedType(): Boolean {
        return serviceType.trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)
    }

    private fun InetAddress.endpointHost(): String {
        val platformHost = hostAddress?.trim().orEmpty()
        if (this !is Inet6Address || scopeId <= 0) return platformHost
        return platformHost.substringBefore("%") + "%$scopeId"
    }

    private fun DiscoveryService.instanceKey(): String {
        return serviceName.lowercase(Locale.US) + "\u0000" + serviceType.trimEnd('.').lowercase(Locale.US)
    }

    private fun Int.isRetryableResolutionFailure(): Boolean {
        return this == NsdManager.FAILURE_INTERNAL_ERROR ||
            this == NsdManager.FAILURE_ALREADY_ACTIVE ||
            this == NsdManager.FAILURE_MAX_LIMIT ||
            this == ERROR_RUNTIME
    }

    private fun ResolutionRequest.retryDelayMillis(): Long {
        val jitter = serviceKey.hashCode().toLong().absoluteValue % RETRY_JITTER_MILLIS
        return RETRY_BASE_DELAY_MILLIS + jitter
    }

    private data class DiscoverySession(
        val generation: Long,
        val onStateChanged: (DiscoveryState) -> Unit,
        val services: LinkedHashMap<String, ServiceRecord> = linkedMapOf(),
        val bodiesByService: LinkedHashMap<String, DiscoveredBody> = linkedMapOf(),
        val resolutionWarnings: LinkedHashMap<String, Int> = linkedMapOf(),
        var discoveryHandle: Any? = null,
        var discoveryActive: Boolean = true,
        var nextServiceRevision: Long = 0L,
        var scanWarningCode: Int? = null,
    ) {
        fun bodies(): List<DiscoveredBody> = bodiesByService.values.sortedWith(
            compareByDescending<DiscoveredBody> { it.isOnline }
                .thenBy { it.serviceName.lowercase(Locale.US) },
        )

        fun scanningState(): DiscoveryState.Scanning = DiscoveryState.Scanning(
            bodies = bodies(),
            warningCode = resolutionWarnings.entries
                .sortedBy { it.key }
                .firstOrNull()
                ?.value
                ?: scanWarningCode,
        )
    }

    private data class ServiceRecord(
        val service: DiscoveryService,
        val revision: Long,
    )

    private data class ResolutionRequest(
        val generation: Long,
        val serviceKey: String,
        val serviceRevision: Long,
        val service: DiscoveryService,
        val attempt: Int,
    )

    private class ResolutionLease(val request: ResolutionRequest) {
        var handle: Any? = null
        var stopRequested: Boolean = false
        var stopAttempted: Boolean = false
        var releaseScheduled: Boolean = false
    }

    private data class StopWork(
        val discoveryHandle: Any?,
        val resolutionLease: ResolutionLease?,
    )

    private data class LostServiceOutcome(
        val state: DiscoveryState.Scanning,
        val resolutionLease: ResolutionLease?,
    )

    private data class StopDiscoveryOutcome(
        val state: DiscoveryState,
        val resolutionLease: ResolutionLease?,
    )

    private data class ResolutionStopWork(
        val state: DiscoveryState.Scanning?,
        val handle: Any?,
        val scheduleRelease: Boolean,
    )

    private data class EndpointCandidate(
        val host: String,
        val origin: String,
        val priority: Int,
    )

    companion object {
        const val SERVICE_TYPE = "_ylx-capture._tcp."
        const val ERROR_RUNTIME = -1
        const val ERROR_NO_ADDRESS = -2
        const val ERROR_SCAN_TIMEOUT = -3
        const val ERROR_PERMISSION = -4
        const val ERROR_RESOLUTION_TIMEOUT = -5
        const val ERROR_MULTICAST_UNAVAILABLE = -6

        private const val MAX_RESOLUTION_ATTEMPTS = 2
        private const val RETRY_BASE_DELAY_MILLIS = 150L
        private const val RETRY_JITTER_MILLIS = 100L
        private const val SCAN_WARNING_DELAY_MILLIS = 15_000L
        private const val RESOLUTION_TIMEOUT_MILLIS = 10_000L
        private const val RESOLUTION_CANCEL_GRACE_MILLIS = 500L
    }
}

internal interface DiscoveryNsdBackend {
    fun start(callbacks: DiscoveryNsdCallbacks): Any

    fun stop(handle: Any)

    fun resolve(service: DiscoveryService, callbacks: DiscoveryResolveCallbacks): Any

    fun cancelResolution(handle: Any): Boolean
}

internal class DiscoveryBackendException(
    val errorCode: Int,
    cause: Throwable? = null,
) : RuntimeException(cause)

internal interface DiscoveryNsdCallbacks {
    fun onDiscoveryStarted(serviceType: String)

    fun onServiceFound(service: DiscoveryService)

    fun onServiceLost(service: DiscoveryService)

    fun onDiscoveryStopped(serviceType: String)

    fun onStartDiscoveryFailed(serviceType: String, errorCode: Int)

    fun onStopDiscoveryFailed(serviceType: String, errorCode: Int)
}

internal interface DiscoveryResolveCallbacks {
    fun onResolveFailed(service: DiscoveryService, errorCode: Int)

    fun onServiceResolved(service: ResolvedDiscoveryService)

    fun onResolutionStopped(service: DiscoveryService)

    fun onStopResolutionFailed(service: DiscoveryService, errorCode: Int)
}

internal interface DiscoveryScheduler {
    fun execute(task: () -> Unit)

    fun executeDelayed(delayMillis: Long, task: () -> Unit)
}

internal data class DiscoveryService(
    val serviceName: String,
    val serviceType: String,
    val nativeHandle: Any,
)

internal data class ResolvedDiscoveryService(
    val serviceName: String,
    val port: Int,
    val addresses: List<InetAddress>,
    val hostname: String?,
)

private class HandlerDiscoveryScheduler(private val handler: Handler) : DiscoveryScheduler {
    override fun execute(task: () -> Unit) {
        handler.post(task)
    }

    override fun executeDelayed(delayMillis: Long, task: () -> Unit) {
        handler.postDelayed(task, delayMillis)
    }
}

@Suppress("DEPRECATION")
private class AndroidDiscoveryNsdBackend(context: Context) : DiscoveryNsdBackend {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override fun start(callbacks: DiscoveryNsdCallbacks): Any {
        val multicastLock = if (requiresLegacyMulticastLock()) acquireMulticastLock() else null
        lateinit var handle: AndroidDiscoveryHandle
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                callbacks.onDiscoveryStarted(serviceType)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                callbacks.onServiceFound(serviceInfo.toDiscoveryService())
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                callbacks.onServiceLost(serviceInfo.toDiscoveryService())
            }

            override fun onDiscoveryStopped(serviceType: String) {
                handle.releaseMulticastLock()
                callbacks.onDiscoveryStopped(serviceType)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                handle.releaseMulticastLock()
                callbacks.onStartDiscoveryFailed(serviceType, errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                callbacks.onStopDiscoveryFailed(serviceType, errorCode)
            }
        }
        handle = AndroidDiscoveryHandle(listener, multicastLock)
        try {
            nsdManager.discoverServices(
                DeviceDiscoveryClient.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        } catch (exception: RuntimeException) {
            handle.releaseMulticastLock()
            throw exception
        }
        return handle
    }

    override fun stop(handle: Any) {
        val discoveryHandle = handle as AndroidDiscoveryHandle
        try {
            nsdManager.stopServiceDiscovery(discoveryHandle.listener)
        } finally {
            discoveryHandle.releaseMulticastLock()
        }
    }

    @SuppressLint("NewApi")
    override fun resolve(service: DiscoveryService, callbacks: DiscoveryResolveCallbacks): Any {
        val serviceInfo = service.nativeHandle as NsdServiceInfo
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                callbacks.onResolveFailed(service, errorCode)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val addresses = if (supportsNsdExtension7()) {
                    serviceInfo.hostAddresses.ifEmpty { listOfNotNull(serviceInfo.host) }
                } else {
                    listOfNotNull(serviceInfo.host)
                }
                val hostname = if (supportsNsdHostname()) serviceInfo.hostname else null
                callbacks.onServiceResolved(
                    ResolvedDiscoveryService(
                        serviceName = serviceInfo.serviceName.orEmpty(),
                        port = serviceInfo.port,
                        addresses = addresses,
                        hostname = hostname,
                    ),
                )
            }

            override fun onResolutionStopped(serviceInfo: NsdServiceInfo) {
                callbacks.onResolutionStopped(service)
            }

            override fun onStopResolutionFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                callbacks.onStopResolutionFailed(service, errorCode)
            }
        }
        nsdManager.resolveService(
            serviceInfo,
            listener,
        )
        return listener
    }

    @SuppressLint("NewApi")
    override fun cancelResolution(handle: Any): Boolean {
        if (!supportsNsdExtension7()) return false
        val listener = handle as? NsdManager.ResolveListener ?: return false
        return try {
            nsdManager.stopServiceResolution(listener)
            true
        } catch (exception: SecurityException) {
            throw exception
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock {
        val manager = wifiManager ?: throw DiscoveryBackendException(
            DeviceDiscoveryClient.ERROR_MULTICAST_UNAVAILABLE,
        )
        return try {
            manager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (exception: SecurityException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw DiscoveryBackendException(
                DeviceDiscoveryClient.ERROR_MULTICAST_UNAVAILABLE,
                exception,
            )
        }
    }

    private fun NsdServiceInfo.toDiscoveryService(): DiscoveryService {
        return DiscoveryService(
            serviceName = serviceName.orEmpty(),
            serviceType = serviceType.orEmpty(),
            nativeHandle = this,
        )
    }

    private companion object {
        const val MULTICAST_LOCK_TAG = "openaria-echo-mobile:device-discovery"
    }
}

private class AndroidDiscoveryHandle(
    val listener: NsdManager.DiscoveryListener,
    private val multicastLock: WifiManager.MulticastLock?,
) {
    @Synchronized
    fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) multicastLock.release()
    }
}

@SuppressLint("NewApi")
private fun tiramisuExtensionAtLeast(version: Int): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= version
}

private fun supportsNsdExtension7(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        tiramisuExtensionAtLeast(7)
}

private fun supportsNsdHostname(): Boolean {
    return Build.VERSION.SDK_INT >= 36 || tiramisuExtensionAtLeast(17)
}

private fun requiresLegacyMulticastLock(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !supportsNsdExtension7()
}

data class DiscoveredBody(
    val serviceName: String,
    val host: String,
    val port: Int,
    val origin: String,
    val origins: List<String> = listOf(origin),
    val isOnline: Boolean = true,
)

sealed interface DiscoveryState {
    data class Idle(val bodies: List<DiscoveredBody>) : DiscoveryState
    data class Scanning(val bodies: List<DiscoveredBody>, val warningCode: Int?) : DiscoveryState
    data class Failed(val bodies: List<DiscoveredBody>, val errorCode: Int) : DiscoveryState
}
