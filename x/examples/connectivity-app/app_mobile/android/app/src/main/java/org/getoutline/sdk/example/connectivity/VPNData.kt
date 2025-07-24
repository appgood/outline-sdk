package org.getoutline.sdk.example.connectivity

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class VPNDeviceRequest(
	val transportConfig: String
)

@Serializable
data class VPNDeviceResponse(
	val deviceId: String,
	val status: String
)

@Serializable
data class StopVPNDeviceRequest(
	val deviceId: String
)

@Serializable
data class VPNStatusResponse(
	val status: String
)

// 保持原有的代理相关类以便向后兼容
@Serializable
data class ProxyRequest(
	val transportConfig: String,
	val localAddress: String
)

@Serializable
data class ProxyResponse(
	val address: String,
	val host: String,
	val port: Int
) 