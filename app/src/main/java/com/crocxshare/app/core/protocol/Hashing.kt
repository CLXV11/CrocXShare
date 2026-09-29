package com.crocxshare.app.core.protocol

import java.security.MessageDigest
import java.util.zip.CRC32

/** Streaming SHA-256 — never loads the whole file into memory. */
class StreamingSha256 {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var frozen = false

    fun update(buf: ByteArray, off: Int, len: Int) {
        check(!frozen)
        digest.update(buf, off, len)
    }

    /** Returns a copy of the current digest state without finalizing it (safe for resume). */
    fun currentValue(): ByteArray = digest.clone()!!.let { it as MessageDigest }.digest()

    fun value(): ByteArray {
        frozen = true
        return digest.digest()
    }
}

object Checksums {
    fun crc32(buf: ByteArray, off: Int, len: Int): Long {
        val c = CRC32()
        c.update(buf, off, len)
        return c.value
    }

    fun sha256(buf: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(buf)

    fun hex(data: ByteArray): String =
        data.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
