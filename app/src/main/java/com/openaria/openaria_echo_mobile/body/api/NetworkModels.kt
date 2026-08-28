package com.openaria.openaria_echo_mobile.body.api

data class CalibrationCaptureCapability(
    val supported: Boolean,
    val enabled: Boolean,
    val disabledReason: String?,
    val requiredVideoLayout: String,
)

data class CameraConnectionStatus(
    val state: String,
)

data class NetworkStatus(
    val authorityEpoch: String,
    val sourceRevision: Long,
    val observedAt: String,
    val saved: Boolean,
    val verified: Boolean,
    val desired: NetworkDesiredState,
    val observed: NetworkObservedState,
    val transaction: NetworkTransactionWindow,
    val mutationCapability: NetworkMutationCapability,
    val concurrencyCapability: NetworkConcurrencyCapability,
)

data class NetworkDesiredState(
    val mode: String,
    val wifiClient: NetworkDesiredWifiClient?,
    val ethernet: NetworkDesiredEthernet?,
)

data class NetworkDesiredWifiClient(
    val ssid: String,
    val security: String,
    val credentialState: String,
)

data class NetworkDesiredEthernet(
    val addressing: String,
    val staticIpv4: NetworkStaticIpv4?,
)

data class NetworkStaticIpv4(
    val address: String,
    val prefixLength: Long,
    val gateway: String?,
    val dns: List<String>,
)

data class NetworkObservedState(
    val ap: NetworkInterfaceRuntime,
    val wifiClient: NetworkInterfaceRuntime,
    val wired: NetworkInterfaceRuntime,
    val defaultRoute: String,
    val mdns: NetworkMdnsStatus,
    val devices: List<NetworkDeviceStatus>,
)

data class NetworkMdnsStatus(
    val hostname: String,
    val service: String,
    val aliases: List<String>,
    val port: Long,
)

data class NetworkDeviceStatus(
    val interfaceName: String,
    val type: String,
    val state: String,
)

data class NetworkMutationCapability(
    val enabled: Boolean,
    val disabledReason: String?,
    val operations: List<String>,
    val idempotencyKeyRequired: Boolean,
    val secretHandling: String,
    val activeStatePolicy: String,
)

data class NetworkConcurrencyCapability(
    val rescueApRequired: Boolean,
    val samePhyApSta: String,
    val exclusiveClientFailureTimeoutSeconds: Long,
    val maxManagedInterfaces: Long,
    val maxApInterfaces: Long,
)

data class NetworkTransactionWindow(
    val current: NetworkTransaction?,
    val latest: NetworkTransaction?,
)

data class NetworkTransaction(
    val authorityEpoch: String,
    val sourceRevision: Long,
    val transactionId: String,
    val operation: String,
    val status: String,
    val stage: String,
    val desired: NetworkDesiredState,
    val acceptedAt: String,
    val updatedAt: String,
    val deadline: NetworkDeviceDeadline?,
    val recoveryAction: String,
    val rescue: NetworkRescueState,
    val error: NetworkTransactionError?,
)

data class NetworkDeviceDeadline(
    val deadlineNs: Long,
    val remainingSeconds: Double,
)

data class NetworkRescueState(
    val apValidated: Boolean,
    val fallbackMode: String,
    val failureTriggerSeconds: Long,
)

data class NetworkTransactionError(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

data class NetworkScanSnapshot(
    val authorityEpoch: String,
    val sourceRevision: Long,
    val scannedAt: String,
    val networks: List<NetworkScanEntry>,
)

data class NetworkScanEntry(
    val ssid: String?,
    val hidden: Boolean,
    val security: String,
    val signalDbm: Long,
    val credentialRequired: Boolean,
) {
    val displayName: String
        get() = ssid ?: "<hidden>"
}

data class NetworkCredentialReceipt(
    val credentialRef: String,
    val issuedAt: String,
    val expiresAt: String,
    val ttlSeconds: Long,
    val singleUse: Boolean,
)

data class NetworkTransactionReceipt(
    val acceptedAt: String,
    val transaction: NetworkTransaction,
)

data class NetworkEventPayload(
    val sseDeliveryId: String,
    val authorityEpoch: String,
    val sourceRevision: Long,
    val occurredAt: String,
    val type: String,
    val transactionId: String?,
    val status: NetworkStatus?,
    val transaction: NetworkTransaction?,
)
