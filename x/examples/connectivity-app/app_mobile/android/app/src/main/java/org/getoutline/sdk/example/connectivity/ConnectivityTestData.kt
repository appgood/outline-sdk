package org.getoutline.sdk.example.connectivity

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ConnectivityTestRequest(
    val accessKey: String,
    val domain: String,
    val resolvers: List<String>,
    val protocols: ConnectivityTestProtocolConfig
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ConnectivityTestProtocolConfig(
    val tcp: Boolean,
    val udp: Boolean
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ConnectivityTestResult(
    val proxy: String,
    val resolver: String,
    val proto: String,
    val prefix: String,
    val time: String,
    val durationMs: Long,
    val error: ConnectivityTestError?
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ConnectivityTestError(
    val operation: String,
    val posixError: String,
    val msg: String
)