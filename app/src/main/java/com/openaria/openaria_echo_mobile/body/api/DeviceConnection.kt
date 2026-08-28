package com.openaria.openaria_echo_mobile.body.api

import com.openaria.openaria_echo_mobile.security.EndpointPolicy

data class DeviceConnection(
    val target: EndpointPolicy.BodyTarget,
    val descriptor: DeviceDescriptor,
    val bearerToken: String?,
) {
    val origin: String = target.origin.toString()
}
