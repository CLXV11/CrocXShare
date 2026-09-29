package com.crocxshare.app.discovery

enum class DiscoveryMethod { WIFI_DIRECT, LAN_NSD, QR_MANUAL }

enum class DeviceState { AVAILABLE, INVITING, CONNECTED, FAILED }

/** A device that was genuinely observed on the local network. Never fabricated. */
data class DiscoveredDevice(
    val id: String,
    val name: String,
    val model: String,
    val method: DiscoveryMethod,
    val address: String,
    val port: Int? = null,
    val state: DeviceState = DeviceState.AVAILABLE
)
