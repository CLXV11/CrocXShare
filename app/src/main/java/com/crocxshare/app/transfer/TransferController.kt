package com.crocxshare.app.transfer

import android.content.Context
import android.content.Intent
import com.crocxshare.app.core.network.TcpTransport
import com.crocxshare.app.core.network.Transport
import com.crocxshare.app.core.protocol.Checksums
import com.crocxshare.app.core.security.Pairing
import com.crocxshare.app.core.security.Tls
import com.crocxshare.app.data.HistoryStore
import com.crocxshare.app.data.SettingsStore
import com.crocxshare.app.service.TransferService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket

/**
 * Owns the lifecycle of send/receive sessions, exposes a single [state] flow
 * for the UI and notification, and never invents numbers — every value comes
 * from the engine's real byte counters.
 */
class TransferController(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val history: HistoryStore,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    val state = MutableStateFlow(TransferUiState())

    /** Rolling window of recent throughputs (bytes/sec) for the live graph. */
    val speedHistory = MutableStateFlow<List<Double>>(emptyList())
    val lastResult = MutableStateFlow<EngineResult?>(null)

    private val control = TransferControl()
    private var currentTransport: Transport? = null
    private var serverSocket: ServerSocket? = null
    private var receiving = false
    private var token = ""
    private var sessionId = ""
    private var identity: Tls.SessionIdentity? = null
    private var currentPayload: Pairing.Payload? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var activeMetrics: TransferMetrics? = null
    private var samplerJob: Job? = null
    private var lastSend: Triple<Pairing.Payload, List<FileSource>, String>? = null
    @Volatile private var autoRetryAttempts = 0

    /** Fired when a receive session goes live (used to advertise via NSD). */
    var onSessionStarted: ((Pairing.Payload) -> Unit)? = null
    var onSessionStopped: (() -> Unit)? = null

    private val resumeDir = java.io.File(appContext.filesDir, "resume")

    init {
        samplerJob = appScope.launch {
            while (isActive) {
                delay(500)
                val m = activeMetrics ?: continue
                val s = state.value
                if (s.active && s.status == TransferStatus.TRANSFERRING) {
                    val w = m.sampleWindowSpeed()
                    state.update {
                        it.copy(
                            currentSpeedBps = w,
                            avgSpeedBps = m.averageSpeed(),
                            etaMs = m.etaMs(it.totalBytes)
                        )
                    }
                    speedHistory.update { (it + w).takeLast(40) }
                } else if (!s.active) {
                    speedHistory.value = emptyList()
                }
            }
        }
    }

    // ------------------------------------------------------------------ SEND

    fun sendToDevice(payload: Pairing.Payload, sources: List<FileSource>, rootName: String) {
        control.reset()
        autoRetryAttempts = 0
        doSend(payload, sources, rootName)
    }

    private fun doSend(payload: Pairing.Payload, sources: List<FileSource>, rootName: String) {
        lastSend = Triple(payload, sources, rootName)
        val engine = newEngine()
        val total = sources.sumOf { it.sizeBytes }
        state.value = TransferUiState(
            active = true, direction = TransferDirection.SEND,
            status = TransferStatus.NEGOTIATING, peerName = payload.deviceName,
            method = methodOf(payload.method),
            files = sources.mapIndexed { i, s ->
                FileProgress(i, s.displayName, s.sizeBytes, 0, TransferStatus.QUEUED)
            },
            totalBytes = total, startedAtMs = System.currentTimeMillis()
        )
        startService()
        appScope.launch {
            var transport: Transport? = null
            try {
                val fp = Checksums.fromHex(payload.certSha256)
                    ?: throw java.io.IOException("bad certificate fingerprint in pairing data")
                val ssl = Tls.clientContext(fp)
                transport = withContext(Dispatchers.IO) {
                    TcpTransport.connect(payload.ip, payload.port, ssl, payload.deviceName)
                }
                currentTransport = transport
                activeMetrics = engine.metrics
                wireEngine(engine)
                val result = engine.send(
                    transport, payload.token, payload.sessionId,
                    settings.deviceName, Pairing.deviceModel(), sources, rootName
                )
                if (result.status == TransferStatus.FAILED && autoRetryAttempts < 3) {
                    autoRetryAttempts++
                    state.update { it.copy(message = it.message + " — auto retry " + autoRetryAttempts + "/3 in 2s") }
                    kotlinx.coroutines.delay(2000)
                    doSend(payload, sources, rootName)
                    return@launch
                }
                autoRetryAttempts = 0
                finishResult(result, TransferDirection.SEND, payload.deviceName, total)
            } catch (e: Exception) {
                finishResult(
                    EngineResult(TransferStatus.FAILED, e.message ?: "connection failed",
                        engine.metrics.bytesTransferred, engine.metrics.elapsedMs(), emptyList()),
                    TransferDirection.SEND, payload.deviceName, total
                )
            } finally {
                currentTransport = null
                withContext(Dispatchers.IO) { transport?.close() }
            }
        }
    }

    /** Reconnect after a failure using the same payload — receiver bitmap drives resume. */
    fun resumeLastSend() {
        val (payload, sources, root) = lastSend ?: return
        sendToDevice(payload, sources, root)
    }

    // --------------------------------------------------------------- RECEIVE

    /** Opens a TLS listener and returns the pairing payload to display as QR/code. */
    fun startReceiving(): Pairing.Payload? {
        if (receiving) return currentPayload
        val id = Tls.createIdentity(settings.deviceName)
        identity = id
        token = Pairing.newToken()
        sessionId = Pairing.newSessionId()
        val server = try {
            TcpTransport.openServer(0) // ephemeral port
        } catch (e: Exception) {
            return null
        }
        serverSocket = server
        val ip = NetworkInfo.getLocalIpAddress() ?: run {
            server.close(); return null
        }
        val payload = Pairing.Payload(
            version = 1, ip = ip, port = server.localPort, token = token,
            certSha256 = Checksums.hex(id.sha256Fingerprint), sessionId = sessionId,
            deviceName = settings.deviceName, deviceModel = Pairing.deviceModel(),
            method = "LAN"
        )
        currentPayload = payload
        onSessionStarted?.invoke(payload)
        receiving = true
        startService()
        state.update { it.copy(active = false, status = TransferStatus.QUEUED, message = "") }
        appScope.launch { acceptLoop(server) }
        return payload
    }

    fun stopReceiving() {
        receiving = false
        onSessionStopped?.invoke()
        approvalDeferred?.complete(false)
        approvalDeferred = null
        appScope.launch {
            withContext(Dispatchers.IO) {
                try { serverSocket?.close() } catch (ignored: Exception) {}
            }
            serverSocket = null
            currentPayload = null
        }
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (receiving) {
            val transport = try {
                withContext(Dispatchers.IO) {
                    TcpTransport.accept(server, Tls.serverContext(identity!!), settings.deviceName)
                }
            } catch (e: Exception) {
                if (receiving) { delay(500); continue } else break
            }
            currentTransport = transport
            val engine = newEngine()
            activeMetrics = engine.metrics
            engine.manifestApprover = { senderName, manifest ->
                val d = CompletableDeferred<Boolean>()
                approvalDeferred = d
                state.value = TransferUiState(
                    active = true, direction = TransferDirection.RECEIVE,
                    status = TransferStatus.WAITING_ACCEPT, peerName = senderName,
                    files = manifest.files.mapIndexed { i, f ->
                        FileProgress(i, f.relPath.substringAfterLast('/'), f.size, 0, TransferStatus.QUEUED)
                    },
                    totalBytes = manifest.totalSize, startedAtMs = System.currentTimeMillis(),
                    incomingManifest = IncomingManifest(
                        senderName = senderName,
                        fileCount = manifest.fileCount,
                        totalBytes = manifest.totalSize,
                        fileNames = manifest.files.take(5).map { it.relPath },
                        requiresFreeBytes = manifest.totalSize
                    ),
                    incomingFrom = senderName
                )
                startService()
                d
            }
            wireEngine(engine)
            val result = engine.receive(transport, token, settings.deviceName)
            approvalDeferred = null
            currentTransport = null
            finishResult(result, TransferDirection.RECEIVE, state.value.peerName, state.value.totalBytes)
            withContext(Dispatchers.IO) { transport.close() }
            if (!receiving) break
            state.value = TransferUiState()
        }
    }

    /** UI callback for the accept/reject dialog. Never auto-accepts. */
    fun approveIncoming(accept: Boolean) {
        approvalDeferred?.complete(accept)
        approvalDeferred = null
    }

    // -------------------------------------------------------------- CONTROLS

    fun pause() {
        control.pause()
        state.update { if (it.active) it.copy(status = TransferStatus.PAUSED, canResume = true, canPause = false) else it }
    }

    fun resumeTransfer() {
        control.resume()
        state.update { if (it.active) it.copy(status = TransferStatus.TRANSFERRING, canResume = false, canPause = true) else it }
    }

    fun cancel() {
        control.cancel()
        val t = currentTransport
        appScope.launch { withContext(Dispatchers.IO) { t?.close() } }
        state.update { if (it.active) it.copy(status = TransferStatus.CANCELLED, message = "cancelling…") else it }
    }

    val isPaused get() = control.pauseRequested

    // ---------------------------------------------------------------- HELPERS

    private fun newEngine(): TransferEngine {
        val sinkFactory = settings.receiveDirUri?.let { uri ->
            SafSinkFactory(appContext, android.net.Uri.parse(uri), autoRename = true)
        } ?: FsSinkFactory(java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "CrocXShare"), autoRename = true)
        val boost = settings.boostMode
        return TransferEngine(
            sinkFactory = sinkFactory,
            resumeDir = resumeDir,
            autoCleanTemp = settings.autoCleanTemp,
            chunkSize = if (boost) 512 * 1024 else settings.chunkBytes(),
            ackWindow = if (boost) 8 else 4
        )
    }

    private fun wireEngine(engine: TransferEngine) {
        engine.attachControl(control)
        val fileDone = LongArray(64)
        engine.progressListener = { p ->
            if (p.fileIndex in fileDone.indices) fileDone[p.fileIndex] = p.fileBytesDone
            state.update { s ->
                s.copy(
                    status = if (s.status == TransferStatus.NEGOTIATING || s.status == TransferStatus.WAITING_ACCEPT)
                        TransferStatus.TRANSFERRING else s.status,
                    transferredBytes = fileDone.sum(),
                    files = s.files.mapIndexed { i, f ->
                        if (i == p.fileIndex) f.copy(transferredBytes = p.fileBytesDone, status = p.status) else f
                    },
                    canPause = true
                )
            }
        }
    }

    private fun finishResult(result: EngineResult, direction: TransferDirection, peerName: String, totalBytes: Long) {
        if (result.status == TransferStatus.COMPLETED) {
            try {
                val v = appContext.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(160, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") v.vibrate(160)
                }
            } catch (e: Exception) {}
        }
        lastResult.value = result
        state.update {
            it.copy(
                status = result.status,
                message = result.message,
                transferredBytes = result.bytesTransferred,
                avgSpeedBps = if (result.durationMs > 0) result.bytesTransferred * 1000.0 / result.durationMs else 0.0,
                active = result.status == TransferStatus.PAUSED,
                canPause = false, canResume = false
            )
        }
        val name = result.files.firstOrNull()?.name
            ?: state.value.files.firstOrNull()?.name ?: "transfer"
        history.add(
            HistoryStore.TransferRecord(
                id = System.currentTimeMillis(),
                fileName = if (result.files.size > 1) "$name (+${result.files.size - 1} more)" else name,
                sizeBytes = totalBytes,
                direction = direction.name,
                peerName = peerName,
                timestamp = System.currentTimeMillis(),
                durationMs = result.durationMs,
                avgSpeedBps = if (result.durationMs > 0) result.bytesTransferred * 1000.0 / result.durationMs else 0.0,
                result = result.status.name
            )
        )
    }

    private fun methodOf(m: String) = when (m) {
        "WIFI_DIRECT" -> ConnectionMethod.WIFI_DIRECT
        "HOTSPOT" -> ConnectionMethod.HOTSPOT
        "WEB" -> ConnectionMethod.WEB
        else -> ConnectionMethod.LAN
    }

    private fun startService() {
        try {
            appContext.startForegroundService(Intent(appContext, TransferService::class.java))
        } catch (e: Exception) {}
    }
}

object NetworkInfo {
    /** Best-effort local IPv4 for wlan0/p2p interfaces. */
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (iface in interfaces.toList()) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("p2p") &&
                    !name.startsWith("ap") && !name.startsWith("swlan")) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (ip != null) {
                            if (name.startsWith("wlan") || name.startsWith("p2p")) return ip
                            fallback = fallback ?: ip
                        }
                    }
                }
            }
            fallback
        } catch (e: Exception) {
            null
        }
    }
}
