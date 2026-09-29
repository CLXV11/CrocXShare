package com.crocxshare.app.core.security

import java.security.SecureRandom

/**
 * Short pairing code for manual entry (when QR scanning is impossible).
 * Layout: 8-byte session token (recoverable via [Pairing.tokenFromBytes]) +
 * 104-bit truncated certificate fingerprint, Crockford base32, grouped for
 * readability (~34 chars). QR payloads carry the full 256-bit fingerprint;
 * the code trades fingerprint entropy (still ~104 bits against a live
 * ephemeral certificate) for typability.
 */
object PairingCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    private fun toBase32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer ushr bits) and 31])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    private fun fromBase32(s: String): ByteArray? {
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in s.uppercase()) {
            if (ch == '-') continue
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** token (8 bytes) + cert fp (first 13 bytes) = 21 bytes → ~34 base32 chars. */
    fun encode(token: String, certSha256Hex: String): String {
        val tokenBytes = Pairing.tokenToBytes(token) ?: return ""
        val fpBytes = com.crocxshare.app.core.protocol.Checksums.fromHex(certSha256Hex)
            ?: return ""
        if (fpBytes.size != 32) return ""
        val packed = tokenBytes + fpBytes.copyOfRange(0, 13)
        return toBase32(packed).chunked(4).joinToString("-")
    }

    data class Decoded(val tokenBytes: ByteArray, val certFingerprintPrefix: ByteArray)

    fun decode(code: String): Decoded? {
        val bytes = fromBase32(code) ?: return null
        if (bytes.size != 21) return null
        return Decoded(bytes.copyOfRange(0, 8), bytes.copyOfRange(8, 21))
    }

    /** The receiver's pairing token, recovered from a valid short code. */
    fun tokenFor(code: String): String? {
        val d = decode(code) ?: return null
        return Pairing.tokenFromBytes(d.tokenBytes)
    }
}
