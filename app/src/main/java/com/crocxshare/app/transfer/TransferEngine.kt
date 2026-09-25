package com.crocxshare.app.transfer

import com.crocxshare.app.core.network.Transport
import com.crocxshare.app.core.protocol.BlockBitmap
import com.crocxshare.app.core.protocol.Chunker
import com.crocxshare.app.core.protocol.Checksums
import com.crocxshare.app.core.protocol.FrameCode
import com.crocxshare.app.core.protocol.FrameReader
import com.crocxshare.app.core.protocol.ManifestCodec
import com.crocxshare.app.core.protocol.ProtocolException
import com.crocxshare.app.core.protocol.ResumeState
import com.crocxshare.app.core.protocol.ResumeStore
import com.crocxshare.app.core.protocol.StreamingSha256
import com.crocxshare.app.core.protocol.TransferManifest
import com.crocxshare.app.core.protocol.expectFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.CRC32

/** Cooperative pause/cancel signalling shared by engine and controller. */
class TransferControl {
    @Volatile var pauseRequested = false
        private set
    @Volatile var cancelRequested = false
        private set

    private val gate = Object()

    fun pause() = synchronized(gate) { pauseRequested = true }
    fun resume() = synchronized(gate) { pauseRequested = false; gate.notifyAll() }
    fun cancel() = synchronized(gate) { cancelRequested = true; pauseRequested = false; gate.notifyAll() }
    fun reset() = synchronized(gate) { pauseRequested = false; cancelRequested = false }

    /** Blocks while paused. Throws TransferCancelled if cancelled. */
    fun awaitRunning() {
        synchronized(gate) {
            while (pauseRequested && !cancelRequested) {
                try { gate.wait(1000) } catch (ignored: InterruptedException) {}
            }
        }
        if (cancelRequested) throw TransferCancelled()
    }

    class TransferCancelled : Exception("transfer cancelled")
}

data class EngineFileResult(
    val name: String,
    val sizeBytes: Long,
    val status: TransferStatus,
    val sha256Verified: Boolean,
    val detail: String = ""
)

data class EngineResult(
    val status: TransferStatus,
    val message: String,
    val bytesTransferred: Long,
    val durationMs: Long,
    val files: List<EngineFileResult>
)

/**
 * Streaming transfer engine over a [Transport]. Protocol:
 *
 *   HELLO (session token) -> MANIFEST -> per-file blocks (CHUNK frames,
 *   per-block CRC32) -> END_FILE (whole-file SHA-256) -> END_TRANSFER.
 *
 * Resume: receiver persists a block bitmap (+ temp-file reference) after every
 * block. On reconnect for the same session it advertises the bitmap; the sender
 * skips completed blocks and seeds its file digest over the prefix it skips.
 * Final files are produced only after SHA-256 verification, via atomic rename.
 */
class TransferEngine(
    private val sinkFactory: SinkFactory,
    private val resumeDir: java.io.File,
    private val autoCleanTemp: Boolean = true,
    private val chunkSize: Int = Chunker.DEFAULT_CHUNK_SIZE,
    private val blockSize: Long = Chunker.DEFAULT_BLOCK_SIZE,
    private val ackWindow: Int = 4
) {
    companion object {
        private const val MAX_FILES = 10_000
        private const val MAX_BLOCK_RETRIES = 3
        private const val TERMINAL = -1
        private const val KEEPALIVE_MS = 10_000L
    }

    val metrics = TransferMetrics()

    /** Receiver-side hook: invoked when a manifest arrives. Approve = write to disk. */
    var manifestApprover: ((senderName: String, manifest: TransferManifest) -> kotlinx.coroutines.CompletableDeferred<Boolean>)? = null

    var progressListener: ((EngineProgress) -> Unit)? = null

    data class EngineProgress(val fileIndex: Int, val fileBytesDone: Long, val fileSize: Long, val status: TransferStatus)

    private fun report(index: Int, done: Long, size: Long, status: TransferStatus) {
        progressListener?.invoke(EngineProgress(index, done.coerceAtMost(size), size, status))
    }

    @Volatile private var control: TransferControl = TransferControl()
    fun attachControl(c: TransferControl) { this.control = c }
    private fun controlPoint() = control.awaitRunning()

    // ------------------------------------------------------------- frame pump

    private class Entry(val code: Int, val payload: ByteArray)

    /**
     * Single reader pump: all inbound frames flow through one queue so the
     * write side never has to read the socket directly. KEEPALIVEs are consumed
     * here; socket idle timeouts are tolerated mid-session.
     */
    private inner class Pump(private val transport: Transport, scope: CoroutineScope) {
        val queue = LinkedBlockingQueue<Entry>(256)
        val job: Job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val (code, payload) = try {
                    transport.reader.read()
                } catch (t: SocketTimeoutException) {
                    continue // idle tick; keepalives keep the session warm
                } catch (e: Exception) {
                    queue.put(Entry(TERMINAL, (e.message ?: "connection closed").toByteArray(Charsets.UTF_8)))
                    return@launch
                }
                when (code) {
                    FrameCode.KEEPALIVE -> {}
                    else -> queue.put(Entry(code, payload))
                }
            }
        }

        /** Next session frame; throws on TERMINAL or cancellation. */
        fun next(): Entry {
            while (true) {
                controlPoint()
                val e = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: continue
                if (e.code == TERMINAL) throw EOFException(String(e.payload, Charsets.UTF_8))
                return e
            }
        }

        /** Drain CHUNK frames through BLOCK_END. */
        fun drainChunks() {
            while (true) {
                val e = next()
                if (e.code == FrameCode.BLOCK_END) return
                if (e.code != FrameCode.CHUNK) throw ProtocolException("expected chunk, got ${e.code}")
            }
        }

        /** Accumulate CHUNK payloads; returns data and the declared CRC from BLOCK_END. */
        fun collectBlock(expectedLen: Long): Pair<ByteArray, Long> {
            if (expectedLen < 0 || expectedLen > blockSize) throw ProtocolException("bad block length $expectedLen")
            val out = ByteArrayOutputStream(expectedLen.toInt())
            while (true) {
                val e = next()
                when (e.code) {
                    FrameCode.CHUNK -> {
                        out.write(e.payload)
                        if (out.size() > expectedLen) throw ProtocolException("block larger than declared")
                    }
                    FrameCode.BLOCK_END -> return out.toByteArray() to decodeLong(e.payload)
                    else -> throw ProtocolException("unexpected frame ${e.code} inside block")
                }
            }
        }
    }

    fun startKeepalive(scope: CoroutineScope, t: Transport): Job = scope.launch {
        while (isActive) {
            delay(KEEPALIVE_MS)
            try { t.writer.write(FrameCode.KEEPALIVE); t.writer.flush() } catch (e: Exception) { break }
        }
    }

    // ------------------------------------------------------------------ SEND

    suspend fun send(
        transport: Transport,
        token: String,
        sessionId: String,
        myName: String,
        myModel: String,
        sources: List<FileSource>,
        rootName: String
    ): EngineResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val results = ArrayList<EngineFileResult>(sources.size)
        var message = ""
        var status = TransferStatus.COMPLETED
        val pump = Pump(transport, this)
        startKeepalive(this, transport)
        try {
            if (sources.isEmpty()) throw ProtocolException("nothing to send")
            if (sources.size > MAX_FILES) throw ProtocolException("too many files (max $MAX_FILES)")

            handshakeSender(transport, token, sessionId, myName, myModel)

            val manifest = TransferManifest(
                rootName = rootName,
                files = sources.map { com.crocxshare.app.core.protocol.FileEntry(it.displayName, it.sizeBytes, 0L) }
            )
            transport.writer.write(FrameCode.MANIFEST, ManifestCodec.encode(manifest))
            transport.writer.flush()

            val resumeBitmaps = decodeManifestAck(expectPump(pump, FrameCode.MANIFEST_ACK, "manifest ack"), manifest.files.size)

            for ((i, src) in sources.withIndex()) {
                controlPoint()
                report(i, 0, src.sizeBytes, TransferStatus.TRANSFERRING)
                val r = sendFile(transport, pump, i, src, resumeBitmaps[i])
                results.add(r)
                if (r.status != TransferStatus.COMPLETED) {
                    status = TransferStatus.FAILED
                    message = r.detail
                    break
                }
            }
            if (status == TransferStatus.COMPLETED) {
                transport.writer.write(FrameCode.END_TRANSFER)
                transport.writer.flush()
            }
        } catch (e: TransferControl.TransferCancelled) {
            status = TransferStatus.CANCELLED
            message = "cancelled by user"
            try { transport.writer.write(FrameCode.CANCEL); transport.writer.flush() } catch (ignored: Exception) {}
        } catch (e: Exception) {
            status = TransferStatus.FAILED
            message = humanError(e)
            transport.sendError(message)
        } finally {
            pump.job.cancel()
        }
        EngineResult(status, message, metrics.bytesTransferred, System.currentTimeMillis() - started, results)
    }

    private fun expectPump(pump: Pump, expected: Int, what: String): ByteArray {
        val e = pump.next()
        if (e.code == FrameCode.ERROR) throw IOException("remote error: " + String(e.payload, Charsets.UTF_8))
        if (e.code != expected) throw ProtocolException("expected $what but got frame ${e.code}")
        return e.payload
    }

    private fun handshakeSender(t: Transport, token: String, sessionId: String, name: String, model: String) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(FrameCode.PROTOCOL_VERSION)
            out.writeUTF(token)
            out.writeUTF(sessionId)
            out.writeUTF(name)
            out.writeUTF(model)
        }
        t.writer.write(FrameCode.HELLO, bos.toByteArray())
        t.writer.flush()
        val p = expectFrame(t.reader, FrameCode.HELLO_ACK, "hello ack")
        val input = DataInputStream(ByteArrayInputStream(p))
        if (input.readInt() != FrameCode.PROTOCOL_VERSION) throw ProtocolException("version mismatch")
        if (!input.readBoolean()) throw IOException("receiver rejected session: " + input.readUTF())
    }

    private fun decodeManifestAck(payload: ByteArray, fileCount: Int): List<BlockBitmap?> {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val count = input.readInt()
        val accepted = BooleanArray(fileCount)
        val bitmaps: Array<BlockBitmap?> = arrayOfNulls(fileCount)
        repeat(count) {
            val idx = input.readInt()
            if (idx < 0 || idx >= fileCount) throw ProtocolException("bad manifest ack index")
            accepted[idx] = true
            if (input.readBoolean()) {
                val len = input.readInt()
                if (len < 0 || len > BlockBitmap.MAX_BLOCKS / 8 + 8) throw ProtocolException("bad bitmap length")
                val bytes = ByteArray(len)
                input.readFully(bytes)
                bitmaps[idx] = BlockBitmap.fromBytes(bytes.size * 8, bytes)
            }
        }
        if (accepted.any { !it }) throw IOException("receiver rejected the file set")
        return bitmaps.toList()
    }

    private fun sendFile(t: Transport, pump: Pump, index: Int, src: FileSource, resumeBitmap: BlockBitmap?): EngineFileResult {
        val size = src.sizeBytes
        val totalBlocks = Chunker.blockCountChecked(size, blockSize)
        val fileSha = StreamingSha256()

        // Seed the digest over the prefix the receiver already holds (resume).
        if (resumeBitmap != null) {
            val firstMissing = resumeBitmap.nextUnset(0)
            if (firstMissing > 0) seedDigest(src, size, firstMissing, fileSha)
        }

        val input = src.openSeekable() ?: src.openStream()
        input.use { stream ->
            var outstanding = 0
            var block = resumeBitmap?.nextUnset(0) ?: 0
            if (block < 0) block = totalBlocks // already complete
            var fileDone = (resumeBitmap?.receivedCount() ?: 0).toLong() * blockSize.coerceAtMost(size).let { minOf(it, size) }

            val buf = ByteArray(chunkSize)
            while (block < totalBlocks) {
                controlPoint()
                val pos = block.toLong() * blockSize
                val len = Chunker.blockLength(block.toLong(), size, blockSize)
                val crc = CRC32()
                writeFrame(t, FrameCode.BLOCK_BEGIN, encodeBlockRef(index, block))
                var remaining = len
                while (remaining > 0) {
                    controlPoint()
                    val want = minOf(chunkSize.toLong(), remaining).toInt()
                    val n = stream.read(buf, 0, want)
                    if (n < 0) throw EOFException("source file shrank during transfer: " + src.displayName)
                    crc.update(buf, 0, n)
                    fileSha.update(buf, 0, n)
                    writeFrame(t, FrameCode.CHUNK, buf, 0, n)
                    metrics.addBytes(n.toLong())
                    remaining -= n
                    fileDone += n
                }
                report(index, fileDone, size, TransferStatus.TRANSFERRING)
                writeFrame(t, FrameCode.BLOCK_END, encodeLong(crc.value))
                outstanding++

                // Flow control: hold at [ackWindow] unacknowledged blocks.
                while (outstanding >= ackWindow) {
                    outstanding += processSenderAck(t, pump, index, src, size, fileSha)
                }
                block++
            }
            while (outstanding > 0) {
                outstanding += processSenderAck(t, pump, index, src, size, fileSha)
            }
        }

        writeFrame(t, FrameCode.END_FILE, encodeEndFile(index, fileSha.value()))
        return EngineFileResult(src.displayName, size, TransferStatus.COMPLETED, sha256Verified = true)
    }

    /** Returns the change in outstanding count (NACK resend keeps the count). */
    private fun processSenderAck(t: Transport, pump: Pump, fileIndex: Int, src: FileSource, size: Long, fileSha: StreamingSha256): Int {
        val e = pump.next()
        when (e.code) {
            FrameCode.BLOCK_ACK -> {
                val (idx, _) = decodeBlockRef(e.payload)
                if (idx != fileIndex) throw ProtocolException("stale ack for file $idx")
                return -1
            }
            FrameCode.BLOCK_NACK -> {
                val (idx, blk) = decodeBlockRef(e.payload)
                if (idx != fileIndex) throw ProtocolException("stale nack for file $idx")
                resendBlock(t, fileIndex, blk.toInt(), src, size, fileSha)
                return 0
            }
            FrameCode.ERROR -> throw IOException("remote error: " + String(e.payload, Charsets.UTF_8))
            FrameCode.CANCEL -> throw TransferControl.TransferCancelled()
            else -> throw ProtocolException("unexpected frame ${e.code} while waiting for ack")
        }
    }

    private fun resendBlock(t: Transport, index: Int, block: Int, src: FileSource, size: Long, fileSha: StreamingSha256) {
        val pos = block.toLong() * blockSize
        val len = Chunker.blockLength(block.toLong(), size, blockSize)
        val input = src.openSeekable() ?: throw IOException("source not seekable; cannot resend block")
        input.use { stream ->
            val crc = CRC32()
            writeFrame(t, FrameCode.BLOCK_BEGIN, encodeBlockRef(index, block))
            val buf = ByteArray(chunkSize)
            var remaining = len
            while (remaining > 0) {
                val want = minOf(chunkSize.toLong(), remaining).toInt()
                val n = stream.read(buf, 0, want)
                if (n < 0) throw EOFException("source shrank during resend")
                crc.update(buf, 0, n)
                fileSha.update(buf, 0, n)
                writeFrame(t, FrameCode.CHUNK, buf, 0, n)
                metrics.addBytes(n.toLong())
                remaining -= n
            }
            writeFrame(t, FrameCode.BLOCK_END, encodeLong(crc.value))
        }
    }

    private fun seedDigest(src: FileSource, size: Long, upToBlockExclusive: Int, sha: StreamingSha256) {
        val input = src.openSeekable() ?: return
        input.use { stream ->
            val buf = ByteArray(chunkSize)
            for (block in 0 until upToBlockExclusive) {
                var remaining = Chunker.blockLength(block.toLong(), size, blockSize)
                while (remaining > 0) {
                    val want = minOf(chunkSize.toLong(), remaining).toInt()
                    val n = stream.read(buf, 0, want)
                    if (n < 0) return
                    sha.update(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }

    // --------------------------------------------------------------- RECEIVE

    suspend fun receive(
        transport: Transport,
        expectedToken: String,
        myName: String
    ): EngineResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val results = ArrayList<EngineFileResult>()
        var message = ""
        var status = TransferStatus.COMPLETED
        var sessionId = ""
        val sinks = ArrayList<FileSink?>()
        val committed = HashSet<Int>()
        val pump = Pump(transport, this)
        startKeepalive(this, transport)
        try {
            val hello = expectPump(pump, FrameCode.HELLO, "hello")
            val h = DataInputStream(ByteArrayInputStream(hello))
            if (h.readInt() != FrameCode.PROTOCOL_VERSION) throw ProtocolException("version mismatch")
            val token = h.readUTF()
            sessionId = h.readUTF().replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
            val senderName = h.readUTF().take(64)
            h.readUTF() // sender model
            if (!java.security.MessageDigest.isEqual(
                    token.toByteArray(Charsets.UTF_8), expectedToken.toByteArray(Charsets.UTF_8))) {
                writeHelloAck(transport, myName, false, "wrong pairing code")
                throw IOException("pairing code mismatch")
            }
            writeHelloAck(transport, myName, true, "")

            val manifest = ManifestCodec.decode(expectPump(pump, FrameCode.MANIFEST, "manifest"))
            val approver = manifestApprover ?: throw IOException("no approval handler installed")
            if (!approver(senderName, manifest).await()) {
                sendManifestAck(transport, manifest, emptyList())
                transport.writer.write(FrameCode.CANCEL)
                transport.writer.flush()
                return@withContext EngineResult(TransferStatus.REJECTED, "rejected by receiver",
                    0, System.currentTimeMillis() - started, emptyList())
            }

            ensureFreeSpace(manifest.totalSize)

            val bitmaps = ArrayList<BlockBitmap>(manifest.files.size)
            for ((i, f) in manifest.files.withIndex()) {
                val existing = ResumeStore.load(resumeDir, sessionId, i)
                val usable = existing != null && existing.fileSize == f.size &&
                    existing.bitmap.totalBlocks == Chunker.blockCountChecked(f.size, blockSize)
                if (usable) {
                    bitmaps.add(existing!!.bitmap)
                    val sink = try {
                        sinkFactory.resumeSink(f.relPath, i, f.size, existing.tempRef)
                    } catch (e: Exception) {
                        // Temp storage lost: start this file over.
                        bitmaps[i] = BlockBitmap(Chunker.blockCountChecked(f.size, blockSize))
                        sinkFactory.createSink(f.relPath, i, f.size)
                    }
                    sinks.add(sink)
                } else {
                    bitmaps.add(BlockBitmap(Chunker.blockCountChecked(f.size, blockSize)))
                    sinks.add(sinkFactory.createSink(f.relPath, i, f.size))
                }
            }
            sendManifestAck(transport, manifest, bitmaps)

            val fileShas = Array(manifest.files.size) { StreamingSha256() }
            // Seed digests over block prefixes already present from a previous session.
            for ((i, f) in manifest.files.withIndex()) {
                val firstMissing = bitmaps[i].nextUnset(0)
                if (firstMissing > 0) seedDigestFromSink(sinks[i]!!, f.size, firstMissing, fileShas[i])
            }

            var done = false
            while (!done) {
                controlPoint()
                val e = pump.next()
                when (e.code) {
                    FrameCode.BLOCK_BEGIN -> {
                        val (idx, blkL) = decodeBlockRef(e.payload)
                        val block = blkL.toInt()
                        if (idx < 0 || idx >= manifest.files.size) throw ProtocolException("bad file index $idx")
                        val f = manifest.files[idx]
                        val bitmap = bitmaps[idx]
                        if (block < 0 || block >= bitmap.totalBlocks) throw ProtocolException("bad block index $block")
                        if (bitmap.isSet(block)) {
                            pump.drainChunks() // replayed block we already have
                            transport.writer.write(FrameCode.BLOCK_ACK, encodeBlockRef(idx, block))
                            transport.writer.flush()
                            continue
                        }
                        val (data, declaredCrc) = pump.collectBlock(Chunker.blockLength(block.toLong(), f.size, blockSize))
                        if (Checksums.crc32(data, 0, data.size) != declaredCrc) {
                            transport.writer.write(FrameCode.BLOCK_NACK, encodeBlockRef(idx, block))
                            transport.writer.flush()
                            continue
                        }
                        sinks[idx]!!.writeAt(block.toLong() * blockSize, data, 0, data.size)
                        fileShas[idx].update(data, 0, data.size)
                        bitmap.set(block)
                        metrics.addBytes(data.size.toLong())
                        ResumeStore.save(resumeDir, ResumeState(
                            sessionId, idx, f.relPath, f.size, blockSize, sinks[idx]!!.tempRef(), bitmap))
                        report(idx, bitmap.receivedCount().toLong() * blockSize, f.size, TransferStatus.TRANSFERRING)
                        transport.writer.write(FrameCode.BLOCK_ACK, encodeBlockRef(idx, block))
                        transport.writer.flush()
                    }
                    FrameCode.END_FILE -> {
                        val input = DataInputStream(ByteArrayInputStream(e.payload))
                        val idx = input.readInt()
                        val sha = ByteArray(32).also { input.readFully(it) }
                        if (idx < 0 || idx >= manifest.files.size) throw ProtocolException("bad end_file index")
                        val f = manifest.files[idx]
                        if (!bitmaps[idx].isComplete()) throw ProtocolException("sender ended " + f.relPath + " before all blocks arrived")
                        if (!fileShas[idx].value().contentEquals(sha)) {
                            sinks[idx]?.discard()
                            ResumeStore.delete(resumeDir, sessionId, idx)
                            transport.sendError("integrity check failed for " + f.relPath)
                            throw IOException("SHA-256 mismatch for " + f.relPath)
                        }
                        val location = sinks[idx]?.commit()
                            ?: throw IOException("finalize failed for " + f.relPath)
                        committed.add(idx)
                        ResumeStore.delete(resumeDir, sessionId, idx)
                        results.add(EngineFileResult(f.relPath, f.size, TransferStatus.COMPLETED, sha256Verified = true, location))
                        report(idx, f.size, f.size, TransferStatus.COMPLETED)
                    }
                    FrameCode.END_TRANSFER -> done = true
                    FrameCode.PAUSE -> {}
                    FrameCode.CANCEL -> throw TransferControl.TransferCancelled()
                    else -> throw ProtocolException("unexpected frame ${e.code}")
                }
            }
            message = "received " + results.size + " file(s)"
        } catch (e: TransferControl.TransferCancelled) {
            status = TransferStatus.CANCELLED
            message = "cancelled"
            try { transport.writer.write(FrameCode.CANCEL); transport.writer.flush() } catch (ignored: Exception) {}
            discardUncommitted(sinks, committed)
        } catch (e: Exception) {
            status = TransferStatus.FAILED
            message = humanError(e)
            transport.sendError(message)
            discardUncommitted(sinks, committed)
        } finally {
            pump.job.cancel()
            if (status == TransferStatus.COMPLETED && autoCleanTemp) {
                resumeDir.listFiles()?.forEach { it.delete() }
            }
        }
        EngineResult(status, message, metrics.bytesTransferred, System.currentTimeMillis() - started, results)
    }

    private fun discardUncommitted(sinks: List<FileSink?>, committed: Set<Int>) {
        if (!autoCleanTemp) return
        for ((i, s) in sinks.withIndex()) {
            if (i !in committed) try { s?.discard() } catch (ignored: Exception) {}
        }
    }

    private fun seedDigestFromSink(sink: FileSink, size: Long, upToBlockExclusive: Int, sha: StreamingSha256) {
        val buf = ByteArray(chunkSize)
        for (block in 0 until upToBlockExclusive) {
            var remaining = Chunker.blockLength(block.toLong(), size, blockSize)
            var pos = block.toLong() * blockSize
            while (remaining > 0) {
                val want = minOf(chunkSize.toLong(), remaining).toInt()
                val n = sink.readAt(pos, buf, 0, want)
                if (n < 0) throw IOException("temp file unreadable during resume seeding")
                sha.update(buf, 0, n)
                pos += n
                remaining -= n
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun writeHelloAck(t: Transport, name: String, ok: Boolean, reason: String) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(FrameCode.PROTOCOL_VERSION)
            out.writeBoolean(ok)
            out.writeUTF(reason.take(120))
            out.writeUTF(name)
        }
        t.writer.write(FrameCode.HELLO_ACK, bos.toByteArray())
        t.writer.flush()
    }

    private fun sendManifestAck(t: Transport, m: TransferManifest, bitmaps: List<BlockBitmap?>) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(bitmaps.size)
            for ((i, b) in bitmaps.withIndex()) {
                out.writeInt(i)
                if (b == null) out.writeBoolean(false)
                else {
                    out.writeBoolean(true)
                    val bytes = b.copyBytes()
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
        }
        t.writer.write(FrameCode.MANIFEST_ACK, bos.toByteArray())
        t.writer.flush()
    }

    private fun ensureFreeSpace(needed: Long) {
        val free = resumeDir.usableSpace
        if (free in 1 until needed) throw IOException(
            "not enough storage: need " + com.crocxshare.app.data.HistoryStore.formatSize(needed) +
                ", have " + com.crocxshare.app.data.HistoryStore.formatSize(free))
    }

    private fun writeFrame(t: Transport, code: Int, payload: ByteArray = ByteArray(0), off: Int = 0, len: Int = payload.size) {
        t.writer.write(code, payload, off, len)
    }

    private fun encodeBlockRef(index: Int, block: Int): ByteArray {
        val bos = ByteArrayOutputStream(12)
        DataOutputStream(bos).use { it.writeInt(index); it.writeLong(block.toLong()) }
        return bos.toByteArray()
    }

    private fun decodeBlockRef(p: ByteArray): Pair<Int, Long> {
        val input = DataInputStream(ByteArrayInputStream(p))
        return input.readInt() to input.readLong()
    }

    private fun encodeLong(v: Long): ByteArray {
        val bos = ByteArrayOutputStream(8)
        DataOutputStream(bos).use { it.writeLong(v) }
        return bos.toByteArray()
    }

    private fun decodeLong(p: ByteArray): Long = DataInputStream(ByteArrayInputStream(p)).readLong()

    private fun encodeEndFile(index: Int, sha: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(36)
        DataOutputStream(bos).use { it.writeInt(index); it.write(sha) }
        return bos.toByteArray()
    }

    private fun humanError(e: Exception): String = when (e) {
        is SocketTimeoutException -> "connection timed out"
        is EOFException -> "connection lost — transfer can be resumed"
        is FileExistsException -> e.message ?: "file exists"
        is IOException -> e.message ?: "I/O error"
        else -> e.javaClass.simpleName + ": " + (e.message ?: "")
    }
}
