package com.openaria.openaria_echo_mobile.body.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet6Address

@Suppress("DEPRECATION")
class DeviceDiscoveryClient(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discovered = linkedMapOf<String, DiscoveredBody>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var onStateChanged: ((DiscoveryState) -> Unit)? = null

    fun start(onStateChanged: (DiscoveryState) -> Unit) {
        stop()
        this.onStateChanged = onStateChanged
        discovered.clear()
        publish(DiscoveryState.Scanning(emptyList(), null))
        val nextListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                publish(DiscoveryState.Scanning(discovered.values.toList(), null))
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)) {
                    return
                }
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val removedKeys = discovered
                    .filterValues { it.serviceName == serviceInfo.serviceName }
                    .keys
                    .toList()
                removedKeys.forEach { discovered.remove(it) }
                publish(DiscoveryState.Scanning(discovered.values.toList(), null))
            }

            override fun onDiscoveryStopped(serviceType: String) {
                publish(DiscoveryState.Idle(discovered.values.toList()))
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stop()
                publish(DiscoveryState.Failed(discovered.values.toList(), errorCode))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                publish(DiscoveryState.Failed(discovered.values.toList(), errorCode))
            }
        }
        listener = nextListener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, nextListener)
        } catch (_: RuntimeException) {
            listener = null
            publish(DiscoveryState.Failed(emptyList(), ERROR_RUNTIME))
        }
    }

    fun stop() {
        val activeListener = listener ?: return
        listener = null
        try {
            nsdManager.stopServiceDiscovery(activeListener)
        } catch (_: RuntimeException) {
            publish(DiscoveryState.Idle(discovered.values.toList()))
        }
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        publish(DiscoveryState.Scanning(discovered.values.toList(), errorCode))
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val body = serviceInfo.toDiscoveredBody() ?: return
                        discovered[body.origin] = body
                        publish(DiscoveryState.Scanning(discovered.values.toList(), null))
                    }
                },
            )
        } catch (_: RuntimeException) {
            publish(DiscoveryState.Scanning(discovered.values.toList(), ERROR_RUNTIME))
        }
    }

    private fun NsdServiceInfo.toDiscoveredBody(): DiscoveredBody? {
        val hostAddress = host?.hostAddress?.substringBefore("%") ?: return null
        if (port !in 1..65535) return null
        val hostForUri = if (host is Inet6Address) "[$hostAddress]" else hostAddress
        return DiscoveredBody(
            serviceName = serviceName,
            host = hostAddress,
            port = port,
            origin = "http://$hostForUri:$port",
        )
    }

    private fun publish(state: DiscoveryState) {
        mainHandler.post {
            onStateChanged?.invoke(state)
        }
    }

    companion object {
        const val SERVICE_TYPE = "_ylx-capture._tcp."
        const val ERROR_RUNTIME = -1
    }
}

data class DiscoveredBody(
    val serviceName: String,
    val host: String,
    val port: Int,
    val origin: String,
)

sealed interface DiscoveryState {
    data class Idle(val bodies: List<DiscoveredBody>) : DiscoveryState
    data class Scanning(val bodies: List<DiscoveredBody>, val warningCode: Int?) : DiscoveryState
    data class Failed(val bodies: List<DiscoveredBody>, val errorCode: Int) : DiscoveryState
}
