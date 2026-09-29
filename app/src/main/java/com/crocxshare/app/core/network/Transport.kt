package com.crocxshare.app.core.network

import com.crocxshare.app.core.protocol.FrameReader
import com.crocxshare.app.core.protocol.FrameWriter
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Abstraction so the engine can run over TCP+TLS in production or a plain loopback in tests/benchmarks. */
interface Transport : Closeable {
    val reader: FrameReader
    val writer: FrameWriter
    val peerName: String
    fun sendError(message: String)
}

/** TCP transport with TLS 1.3 and certificate fingerprint pinning. */
class TcpTransport private constructor(
    private val socket: Socket,
    override val reader: FrameReader,
    override val writer: FrameWriter,
    override val peerName: String
) : Transport {

    override fun sendError(message: String) {
        try {
            writer.write(com.crocxshare.app.core.protocol.FrameCode.ERROR, message.toByteArray(Charsets.UTF_8))
            writer.flush()
        } catch (ignored: Exception) {}
    }

    override fun close() {
        try { socket.close() } catch (ignored: Exception) {}
    }

    companion object {
        const val DEFAULT_PORT = 47563
        const val CONNECT_TIMEOUT_MS = 10_000
        const val IO_TIMEOUT_MS = 60_000 // refreshed by keepalives during idle

        fun openServer(port: Int = 0): ServerSocket =
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }

        fun accept(server: ServerSocket, ssl: SSLContext, name: String): Transport {
            val raw = server.accept()
            return wrap(raw, ssl, name, serverSide = true)
        }

        fun connect(host: String, port: Int, ssl: SSLContext, name: String): Transport {
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            return wrap(raw, ssl, name, serverSide = false)
        }

        private fun wrap(raw: Socket, ssl: SSLContext, name: String, serverSide: Boolean): Transport {
            raw.tcpNoDelay = true
            raw.soTimeout = IO_TIMEOUT_MS
            raw.keepAlive = true
            val host = raw.inetAddress?.hostAddress ?: ""
            val tls = ssl.socketFactory.createSocket(raw, host, raw.port, true) as SSLSocket
            tls.tcpNoDelay = true
            tls.soTimeout = IO_TIMEOUT_MS
            tls.keepAlive = true
            // Eager handshake so certificate fingerprint errors surface at connect time.
            tls.startHandshake()
            val input = tls.getInputStream()
            val output = tls.getOutputStream()
            return TcpTransport(tls, FrameReader(input), FrameWriter(output), name)
        }
    }
}

/** Plain loopback transport for unit tests and the internal benchmark. No TLS. */
class LoopbackTransport private constructor(
    private val socket: Socket,
    override val reader: FrameReader,
    override val writer: FrameWriter
) : Transport {
    override val peerName: String get() = "loopback"

    override fun sendError(message: String) {
        try { writer.write(15, message.toByteArray(Charsets.UTF_8)); writer.flush() } catch (ignored: Exception) {}
    }

    override fun close() { try { socket.close() } catch (ignored: Exception) {} }

    companion object {
        fun server(port: Int = 0): ServerSocket = ServerSocket(port)
        fun accept(server: ServerSocket): Transport {
            val s = server.accept()
            return LoopbackTransport(s, FrameReader(s.getInputStream()), FrameWriter(s.getOutputStream()))
        }
        fun connect(port: Int): Transport {
            val s = Socket("127.0.0.1", port)
            return LoopbackTransport(s, FrameReader(s.getInputStream()), FrameWriter(s.getOutputStream()))
        }
    }
}
