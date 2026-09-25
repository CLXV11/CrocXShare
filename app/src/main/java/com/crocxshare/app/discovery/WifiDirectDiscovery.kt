package com.crocxshare.app.discovery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Real Wi-Fi Direct (P2P) wrapper: peer discovery, invitation, and group-info
 * so the receiver can publish its group-owner address for the transfer socket.
 * Where Wi-Fi Direct is unavailable (emulators, some OEM builds) the UI falls
 * back to LAN NSD or QR/manual pairing — never a fake device list.
 */
@SuppressLint("MissingPermission") // callers gate with DiscoveryPermissions.requiredForDiscovery()
class WifiDirectDiscovery(private val context: Context) {

    sealed class Event {
        data class PeersChanged(val devices: List<DiscoveredDevice>) : Event()
        data class Connected(val group: WifiP2pGroup?) : Event()
        data class Disconnected(val reason: String) : Event()
        data class InviteResult(val success: Boolean, val message: String) : Event()
        data class WifiP2pEnabled(val enabled: Boolean) : Event()
    }

    private val manager = context.applicationContext.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    private val _peers = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val peers: StateFlow<List<DiscoveredDevice>> = _peers

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _events.tryEmit(Event.WifiP2pEnabled(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED))
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    }
                    if (info != null && info.isGroupOwner) _events.tryEmit(Event.Connected(info))
                    else _events.tryEmit(Event.Disconnected("p2p link down"))
                }
            }
        }
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private fun requestPeers() {
        val ch = channel ?: return
        try {
            manager.requestPeers(ch) { peerList ->
                _peers.value = peerList.deviceList.map { d ->
                    DiscoveredDevice(
                        id = d.deviceAddress,
                        name = d.deviceName?.take(48) ?: "P2P device",
                        model = "",
                        method = DiscoveryMethod.WIFI_DIRECT,
                        address = d.deviceAddress,
                        state = when (d.status) {
                            WifiP2pDevice.CONNECTED -> DeviceState.CONNECTED
                            WifiP2pDevice.INVITED -> DeviceState.INVITING
                            WifiP2pDevice.FAILED -> DeviceState.FAILED
                            else -> DeviceState.AVAILABLE
                        }
                    )
                }
            }
        } catch (e: Exception) {}
    }

    fun start() {
        if (channel != null) return
        channel = try {
            manager.initialize(context, context.mainLooper) { _events.tryEmit(Event.Disconnected("p2p channel lost")) }
        } catch (e: Exception) { null }
        try { context.registerReceiver(receiver, intentFilter) } catch (e: Exception) {}
    }

    fun stop() {
        stopDiscovery()
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        channel = null
    }

    fun startDiscovery() {
        val ch = channel ?: return
        try {
            manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    _events.tryEmit(Event.Disconnected("discovery failed (code $reason)"))
                }
            })
        } catch (e: Exception) {}
    }

    fun stopDiscovery() {
        val ch = channel ?: return
        try {
            manager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (e: Exception) {}
    }

    fun invite(device: DiscoveredDevice) {
        val ch = channel ?: return
        val config = WifiP2pConfig.Builder()
            .setDeviceAddress(android.net.MacAddress.fromString(device.address))
            .build()
        try {
            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _events.tryEmit(Event.InviteResult(true, "invitation sent"))
                }
                override fun onFailure(reason: Int) {
                    _events.tryEmit(Event.InviteResult(false, "invite failed (code $reason)"))
                }
            })
        } catch (e: Exception) {
            _events.tryEmit(Event.InviteResult(false, e.message ?: "invite failed"))
        }
    }

    fun requestGroupInfo(onGroup: (WifiP2pGroup?) -> Unit) {
        val ch = channel ?: run { onGroup(null); return }
        try {
            manager.requestGroupInfo(ch) { group -> onGroup(group) }
        } catch (e: Exception) { onGroup(null) }
    }

    fun disconnect() {
        val ch = channel ?: return
        try {
            manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (e: Exception) {}
    }
}
