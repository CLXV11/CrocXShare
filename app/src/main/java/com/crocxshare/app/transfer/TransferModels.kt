package com.crocxshare.app.transfer

enum class TransferStatus { QUEUED, NEGOTIATING, TRANSFERRING, VERIFYING, FINALIZING, PAUSED, COMPLETED, FAILED, CANCELLED, REJECTED, WAITING_ACCEPT }

enum class TransferDirection { SEND, RECEIVE }

enum class ConnectionMethod { WIFI_DIRECT, LAN, HOTSPOT, WEB, UNKNOWN }

data class FileProgress(
    val index: Int,
    val name: String,
    val sizeBytes: Long,
    val transferredBytes: Long,
    val status: TransferStatus,
    val sha256Verified: Boolean = false
)

data class TransferUiState(
    val active: Boolean = false,
    val direction: TransferDirection? = null,
    val status: TransferStatus = TransferStatus.QUEUED,
    val peerName: String = "",
    val method: ConnectionMethod = ConnectionMethod.UNKNOWN,
    val files: List<FileProgress> = emptyList(),
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val currentSpeedBps: Double = 0.0,
    val avgSpeedBps: Double = 0.0,
    val startedAtMs: Long = 0L,
    val etaMs: Long = -1L,
    val message: String = "",
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val incomingManifest: IncomingManifest? = null,
    val incomingFrom: String = ""
)

/** Shown to the receiver for explicit approval before any byte is written. */
data class IncomingManifest(
    val senderName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val fileNames: List<String>,
    val requiresFreeBytes: Long
)

/** Live metrics computed from real byte counters — never simulated. */
class TransferMetrics {
    @Volatile var bytesTransferred: Long = 0L
        private set

    @Volatile private var windowBytes: Long = 0L
    @Volatile private var windowStartNanos: Long = 0L

    private val startNanos = System.nanoTime()

    fun addBytes(n: Long) {
        bytesTransferred += n
        windowBytes += n
    }

    /** Bytes/sec over the last sampling window. Call ~2x/sec from the UI collector. */
    fun sampleWindowSpeed(): Double {
        val now = System.nanoTime()
        val elapsed = now - windowStartNanos
        val wb = windowBytes
        windowBytes = 0L
        windowStartNanos = now
        if (elapsed <= 0) return 0.0
        return wb / (elapsed / 1_000_000_000.0)
    }

    fun averageSpeed(): Double {
        val elapsed = System.nanoTime() - startNanos
        if (elapsed <= 0) return 0.0
        return bytesTransferred / (elapsed / 1_000_000_000.0)
    }

    fun elapsedMs(): Long = (System.nanoTime() - startNanos) / 1_000_000

    fun etaMs(totalBytes: Long): Long {
        val avg = averageSpeed()
        if (avg <= 0.0) return -1L
        val remaining = (totalBytes - bytesTransferred).coerceAtLeast(0)
        return (remaining / avg * 1000).toLong()
    }
}
