package com.crocxshare.app

import android.app.Application
import android.net.Uri
import com.crocxshare.app.data.HistoryStore
import com.crocxshare.app.data.SettingsStore
import com.crocxshare.app.discovery.NsdDiscovery
import com.crocxshare.app.discovery.WifiDirectDiscovery
import com.crocxshare.app.core.security.Pairing
import com.crocxshare.app.transfer.TransferController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CrocXApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings by lazy { SettingsStore(app) }
    val history by lazy { HistoryStore(app) }
    val transfer by lazy { TransferController(app, settings, history) }

    /** Wi-Fi Direct (P2P) discovery. */
    val wifiDirect by lazy { WifiDirectDiscovery(app) }

    /** Folder (SAF tree) picked for zipping and sending; bump counter to notify UI. */
    @Volatile var pendingFolderTreeUri: Uri? = null
    val folderPickCount = MutableStateFlow(0)

    /** Screen requested via launcher shortcut. */
    @Volatile var initialScreen: String? = null

    /** Text shared into the app from other apps (ACTION_SEND), waiting to be sent. */
    @Volatile var shareText: String? = null

    /** LAN service discovery, wired to receive sessions for automatic advertising. */
    val nsd by lazy { NsdDiscovery(app, appScope, settings.deviceName, Pairing.deviceModel()) }

    /** Files picked in SendScreen, waiting for a target device. */
    val pendingSourcesFlow = MutableStateFlow<List<Uri>>(emptyList())

    /** MIME filter selected by the SendScreen chips. */
    var pendingMime: Array<String> = arrayOf("*/*")

    /** Filesystem paths (extracted app APKs) picked in AppsScreen. */
    val pendingFs = MutableStateFlow<List<String>>(emptyList())

    init {
        // While a receive session is live, advertise it on the LAN so senders can see it.
        transfer.onSessionStarted = { payload -> nsd.advertise(payload.port) }
        transfer.onSessionStopped = { nsd.stopAdvertising() }
    }
}
