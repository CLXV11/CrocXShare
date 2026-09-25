package com.crocxshare.app.benchmark

import android.content.Context
import com.crocxshare.app.core.network.LoopbackTransport
import com.crocxshare.app.core.security.Pairing
import com.crocxshare.app.transfer.EngineResult
import com.crocxshare.app.transfer.FsFileSink
import com.crocxshare.app.transfer.FsSinkFactory
import com.crocxshare.app.transfer.FsFileSource
import com.crocxshare.app.transfer.TransferControl
import com.crocxshare.app.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Internal developer benchmark. Generates real test data on disk and pushes it
 * through the full engine over a real loopback TCP connection (with the same
 * framing, CRC, ACK window and SHA-256 verification as production). Numbers
 * are measured, never hardcoded. Loopback isolates the engine from radio
 * variance — radio throughput depends on the actual link.
 */
object BenchmarkRunner {

    data class BenchResult(
        val sizeBytes: Long,
        val durationMs: Long,
        val throughputMBps: Double,
        val success: Boolean,
        val message: String,
        val shaOk: Boolean
    )

    suspend fun run(
        context: Context,
        sizeMb: Int,
        chunkKb: Int,
        iterations: Int = 1
    ): List<BenchResult> = withContext(Dispatchers.IO) {
        val results = ArrayList<BenchResult>()
        val workDir = File(context.cacheDir, "bench").apply { mkdirs() }
        for (iter in 1..iterations) {
            val src = File(workDir, "bench-$sizeMb-mb-$iter.bin")
            val outDir = File(workDir, "out-$iter").apply { mkdirs() }
            try {
                generateFile(src, sizeMb)
                val size = src.length()
                val server = LoopbackTransport.server(0)
                val port = server.localPort
                val token = Pairing.newToken()
                val sessionId = Pairing.newSessionId()

                val result: Pair<EngineResult, EngineResult> = coroutineScope {
                    val receiverEngine = TransferEngine(
                        sinkFactory = FsSinkFactory(outDir),
                        resumeDir = File(workDir, "resume"),
                        autoCleanTemp = true,
                        chunkSize = chunkKb * 1024
                    )
                    receiverEngine.manifestApprover = { _, _ ->
                        kotlinx.coroutines.CompletableDeferred(true)
                    }
                    val senderEngine = TransferEngine(
                        sinkFactory = FsSinkFactory(outDir),
                        resumeDir = File(workDir, "resume-s"),
                        autoCleanTemp = true,
                        chunkSize = chunkKb * 1024
                    )
                    val control = TransferControl()
                    receiverEngine.attachControl(control)
                    senderEngine.attachControl(control)

                    val sendJob = async(Dispatchers.IO) {
                        val t = LoopbackTransport.connect(port)
                        try { senderEngine.send(t, token, sessionId, "bench-sender", "bench",
                            listOf(FsFileSource(src)), "bench") }
                        finally { t.close() }
                    }
                    val recvJob = async(Dispatchers.IO) {
                        val t = LoopbackTransport.accept(server)
                        try { receiverEngine.receive(t, token, "bench-receiver") }
                        finally { t.close(); server.close() }
                    }
                    sendJob.await() to recvJob.await()
                }

                val send = result.first
                val recv = result.second
                val ok = send.status == com.crocxshare.app.transfer.TransferStatus.COMPLETED &&
                    recv.status == com.crocxshare.app.transfer.TransferStatus.COMPLETED
                val secs = send.durationMs / 1000.0
                results.add(BenchResult(
                    sizeBytes = size,
                    durationMs = send.durationMs,
                    throughputMBps = if (secs > 0) size / 1048576.0 / secs else 0.0,
                    success = ok,
                    message = if (ok) "ok" else "send=${send.status} recv=${recv.status} ${recv.message}",
                    shaOk = recv.files.firstOrNull()?.sha256Verified == true
                ))
            } catch (e: Exception) {
                results.add(BenchResult(0, 0, 0.0, false, e.message ?: "error", false))
            } finally {
                src.delete()
                outDir.deleteRecursively()
            }
        }
        results
    }

    /** Streaming generator — never holds the file in memory. */
    private fun generateFile(f: File, sizeMb: Int) {
        f.parentFile?.mkdirs()
        val buf = ByteArray(1024 * 1024)
        var seed = 0xC0C0
        f.outputStream().buffered(1024 * 1024).use { out ->
            var written = 0L
            val target = sizeMb.toLong() * 1024 * 1024
            while (written < target) {
                var i = 0
                while (i < buf.size) {
                    seed = seed * 6364136223846793005L.toInt() + 1442695040888963407L.toInt()
                    buf[i] = (seed ushr 16).toByte()
                    i++
                }
                val want = minOf(buf.size.toLong(), target - written).toInt()
                out.write(buf, 0, want)
                written += want
            }
        }
    }
}
