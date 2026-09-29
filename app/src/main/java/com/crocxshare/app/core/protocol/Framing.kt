package com.crocxshare.app.core.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed binary framing: [1 byte code][4 byte big-endian length][payload].
 * No JSON, no Base64 — raw binary for control frames and chunk payloads.
 */
class FrameWriter(private val out: OutputStream) {
    private val header = ByteArray(5)

    @Synchronized
    fun write(code: Int, payload: ByteArray = ByteArray(0), off: Int = 0, len: Int = payload.size) {
        if (len < 0 || len > FrameCode.MAX_FRAME) throw ProtocolException("frame too large: $len")
        header[0] = code.toByte()
        header[1] = (len ushr 24).toByte()
        header[2] = (len ushr 16).toByte()
        header[3] = (len ushr 8).toByte()
        header[4] = len.toByte()
        out.write(header)
        if (len > 0) out.write(payload, off, len)
    }

    @Synchronized fun flush() = out.flush()
}

class FrameReader(private val input: InputStream) {
    private val header = ByteArray(5)

    /** Read one frame. Blocks until a full frame arrives or the stream ends. */
    fun read(): Pair<Int, ByteArray> {
        readFully(header, 0, header.size)
        val code = header[0].toInt() and 0xFF
        val len = ((header[1].toInt() and 0xFF) shl 24) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 8) or
            (header[4].toInt() and 0xFF)
        if (len < 0 || len > FrameCode.MAX_FRAME) throw ProtocolException("bad frame length $len")
        val payload = ByteArray(len)
        readFully(payload, 0, len)
        return code to payload
    }

    /** Stream a chunk payload directly into [buf]. Returns bytes read or throws on truncation. */
    fun readChunkInto(buf: ByteArray, off: Int, len: Int) {
        readFully(buf, off, len)
    }

    private fun readFully(buf: ByteArray, off: Int, len: Int) {
        var p = off
        val end = off + len
        while (p < end) {
            val n = input.read(buf, p, end - p)
            if (n < 0) throw EOFException("connection closed mid-frame")
            p += n
        }
    }
}

/** Frame [code] read from [reader], validating it against [expected]. */
fun expectFrame(reader: FrameReader, expected: Int, what: String): ByteArray {
    val (code, payload) = reader.read()
    if (code == FrameCode.ERROR) {
        val msg = payload.toString(Charsets.UTF_8)
        throw IOException("remote error: $msg")
    }
    if (code != expected) throw ProtocolException("expected $what but got frame $code")
    return payload
}
