package com.crocxshare.app.core.protocol

/** Binary frame codes for the CrocXShare streaming protocol (version 1). */
object FrameCode {
    const val HELLO = 1
    const val HELLO_ACK = 2
    const val MANIFEST = 3
    const val MANIFEST_ACK = 4
    const val BLOCK_BEGIN = 5
    const val CHUNK = 6
    const val BLOCK_END = 7
    const val BLOCK_ACK = 8
    const val BLOCK_NACK = 9
    const val END_FILE = 10
    const val END_TRANSFER = 11
    const val PAUSE = 12
    const val CANCEL = 13
    const val KEEPALIVE = 14
    const val ERROR = 15

    const val PROTOCOL_VERSION = 1
    const val MAGIC = 0x435831 // "CX1"

    // Largest accepted control/chunk frame. Default chunk is 256 KiB; this cap
    // exists purely to reject corrupt/malicious length fields.
    const val MAX_FRAME = 16 * 1024 * 1024
}

class ProtocolException(message: String) : Exception(message)
