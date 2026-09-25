package com.crocxshare.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * LAN discovery via Android NSD (Network Service Discovery, DNS-SD multicast).
 * Used when both devices are on the same Wi-Fi. Only exposes name/model/host/
 * port — pairing credentials still come from the QR/short code flow, so a
 * discovered device can never push files without approval.
 */
class NsdDiscovery(
    context: Context,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val deviceModel: String
) {
    companion object {
        const val SERVICE_TYPE = "_crocxshare._tcp."
        private const val NAME_PREFIX = "CXS|"
    }

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolving = HashSet<String>()

    fun advertise(port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = NAME_PREFIX + deviceName + "|" + deviceModel
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (e: Exception) {}
    }

    fun stopAdvertising() {
        registration?.let { try { nsd.unregisterService(it) } catch (e: Exception) {} }
        registration = null
    }

    fun startDiscovery() {
        stopDiscovery()
        val seen = HashMap<String, DiscoveredDevice>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val raw = serviceInfo.serviceName ?: return
                if (!raw.startsWith(NAME_PREFIX)) return
                val id = serviceInfo.serviceType + raw
                if (!resolving.add(id)) return
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            resolving.remove(id)
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            resolving.remove(id)
                            val host = info.host?.hostAddress ?: return
                            val parts = (info.serviceName ?: "").removePrefix(NAME_PREFIX).split('|')
                            seen[id] = DiscoveredDevice(
                                id = id,
                                name = parts.getOrNull(0) ?: "Device",
                                model = parts.getOrNull(1) ?: "",
                                method = DiscoveryMethod.LAN_NSD,
                                address = host,
                                port = info.port
                            )
                            _devices.value = seen.values.toList()
                        }
                    })
                } catch (e: Exception) { resolving.remove(id) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val id = serviceInfo.serviceType + (serviceInfo.serviceName ?: "")
                seen.remove(id)
                _devices.value = seen.values.toList()
            }
        }
        discovery = listener
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) } catch (e: Exception) {}
    }

    fun stopDiscovery() {
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (e: Exception) {} }
        discovery = null
        _devices.value = emptyList()
    }
}

object DiscoveryPermissions {
    /** Runtime permissions needed for discovery on this Android version. */
    fun requiredForDiscovery(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun requiredForReceiveQr(): Array<String> = emptyArray()
}
