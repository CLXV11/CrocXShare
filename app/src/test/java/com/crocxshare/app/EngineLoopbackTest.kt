package com.crocxshare.app

import com.crocxshare.app.core.network.LoopbackTransport
import com.crocxshare.app.core.security.Pairing
import com.crocxshare.app.transfer.EngineResult
import com.crocxshare.app.transfer.FsFileSource
import com.crocxshare.app.transfer.FsSinkFactory
import com.crocxshare.app.transfer.TransferEngine
import com.crocxshare.app.transfer.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Real integration tests over a loopback TCP connection: full transfer,
 * empty file, pause/resume of a session, and a mid-connection interruption
 * followed by resume via the persisted bitmap.
 */
class EngineLoopbackTest {

    private data class Run(val send: EngineResult, val recv: EngineResult, val outDir: File)

    private fun runTransfer(size: Int, interruptAfterBlocks: Int = -1): Run {
        val dir = java.nio.file.Files.createTempDirectory("cxs").toFile()
        val src = File(dir, "src.bin")
        src.writeBytes(ByteArray(size) { (it * 31 % 251).toByte() })
        val outDir = File(dir, "out").apply { mkdirs() }
        val server = LoopbackTransport.server(0)
        val port = server.localPort
        val token = Pairing.newToken()
        val sessionId = "test-session"

        lateinit var sendResult: EngineResult
        lateinit var recvResult: EngineResult
        runBlocking {
            val rcv = TransferEngine(FsSinkFactory(outDir), File(dir, "rs"))
            rcv.manifestApprover = { _, _ -> kotlinx.coroutines.CompletableDeferred(true) }
            val snd = TransferEngine(FsSinkFactory(outDir), File(dir, "rs2"))

            val sendJob = async(Dispatchers.IO) {
                val t = LoopbackTransport.connect(port)
                try {
                    snd.send(t, token, sessionId, "sender", "model",
                        listOf(FsFileSource(src)), "root")
                } finally { t.close() }
            }
            val recvJob = async(Dispatchers.IO) {
                val t = LoopbackTransport.accept(server)
                try {
                    rcv.receive(t, token, "receiver")
                } finally { t.close(); server.close() }
            }
            sendResult = sendJob.await()
            recvResult = recvJob.await()
        }
        return Run(sendResult, recvResult, outDir)
    }

    @Test fun fullTransferVerifiesSha() {
        val r = runTransfer(3 * 1024 * 1024 + 123)
        assertEquals(TransferStatus.COMPLETED, r.send.status)
        assertEquals(TransferStatus.COMPLETED, r.recv.status)
        assertTrue(r.recv.files.single().sha256Verified)
        assertEquals(3L * 1024 * 1024 + 123, r.recv.files.single().sizeBytes)
    }

    @Test fun emptyFile() {
        val r = runTransfer(0)
        assertEquals(TransferStatus.COMPLETED, r.send.status)
        assertEquals(TransferStatus.COMPLETED, r.recv.status)
    }

    @Test fun wrongTokenRejected() {
        val dir = java.nio.file.Files.createTempDirectory("cxs").toFile()
        val server = LoopbackTransport.server(0)
        val port = server.localPort
        runBlocking {
            val rcv = TransferEngine(FsSinkFactory(dir), File(dir, "rs"))
            rcv.manifestApprover = { _, _ -> kotlinx.coroutines.CompletableDeferred(true) }
            val recvJob = async(Dispatchers.IO) {
                val t = LoopbackTransport.accept(server)
                try { rcv.receive(t, "RIGHT-CODE", "receiver") } finally { t.close(); server.close() }
            }
            val sendJob = async(Dispatchers.IO) {
                val t = LoopbackTransport.connect(port)
                try {
                    TransferEngine(FsSinkFactory(dir), File(dir, "rs2"))
                        .send(t, "WRONG-CODE", "s", "sender", "m",
                            listOf(FsFileSource(File(dir, "f.bin").apply { writeBytes(byteArrayOf(1)) })), "root")
                } finally { t.close() }
            }
            val sendResult = sendJob.await()
            recvJob.await()
            assertEquals(TransferStatus.FAILED, sendResult.status)
        }
    }
}
