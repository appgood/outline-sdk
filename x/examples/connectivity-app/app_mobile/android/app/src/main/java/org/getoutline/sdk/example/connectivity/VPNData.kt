package org.getoutline.sdk.example.connectivity

import kotlinx.serialization.Serializable

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