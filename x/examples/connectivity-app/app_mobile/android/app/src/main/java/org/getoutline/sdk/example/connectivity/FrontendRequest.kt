package org.getoutline.sdk.example.connectivity

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class FrontendRequest(
    val resourceName: String,
    val parameters: String
)
