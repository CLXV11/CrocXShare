package com.crocxshare.app.core.security

import android.os.Build
import org.json.JSONObject
import java.security.SecureRandom
import android.util.Base64

/**
 * Pairing payloads encode the receiver's endpoint so the sender can connect:
 * ip, port, one-time session token, pinned TLS certificate fingerprint, and
 * device identity. Encoded as cxspair:// URLs / QR content.
 */
object Pairing {
    private val random = SecureRandom()
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Human-friendly short pairing token (8 chars, no ambiguous chars). */
    fun newToken(): String = tokenFromBytes(ByteArray(8).also(random::nextBytes))

    fun tokenFromBytes(bytes: ByteArray): String {
        val sb = StringBuilder(9)
        for (i in bytes.indices) {
            sb.append(ALPHABET[(bytes[i].toInt() and 0xFF) % ALPHABET.length])
            if (i == 4) sb.append('-')
        }
        return sb.toString()
    }

    /** Inverse of [tokenFromBytes]; returns null if the token is malformed. */
    fun tokenToBytes(token: String): ByteArray? {
        val clean = token.replace("-", "").uppercase()
        if (clean.length != 8) return null
        val out = ByteArray(8)
        for (i in 0 until 8) {
            val v = ALPHABET.indexOf(clean[i])
            if (v < 0) return null
            out[i] = v.toByte()
        }
        return out
    }

    fun newSessionId(): String =
        (1..4).joinToString("") { "%04x".format(random.nextInt(0x10000)) }

    data class Payload(
        val version: Int,
        val ip: String,
        val port: Int,
        val token: String,
        val certSha256: String, // hex (full 32 bytes from QR, or prefix from short code)
        val sessionId: String,
        val deviceName: String,
        val deviceModel: String,
        val method: String // HOTSPOT | WIFI_DIRECT | LAN
    )

    fun encodePayload(p: Payload): String {
        val j = JSONObject().apply {
            put("v", p.version)
            put("ip", p.ip)
            put("port", p.port)
            put("token", p.token)
            put("cert", p.certSha256)
            put("sid", p.sessionId)
            put("name", p.deviceName)
            put("model", p.deviceModel)
            put("method", p.method)
        }
        val b64 = Base64.encodeToString(j.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        return "cxspair://v1/$b64"
    }

    fun decodePayload(raw: String): Payload? {
        return try {
            val b64 = raw.removePrefix("cxspair://v1/")
            if (b64 == raw) return null
            val j = JSONObject(String(Base64.decode(b64, Base64.URL_SAFE), Charsets.UTF_8))
            val version = j.getInt("v")
            if (version != 1) return null
            val ip = j.getString("ip")
            if (!ip.matches(Regex("^[0-9a-fA-F:.]+$"))) return null
            Payload(
                version = version,
                ip = ip,
                port = j.getInt("port"),
                token = j.getString("token"),
                certSha256 = j.getString("cert"),
                sessionId = j.getString("sid"),
                deviceName = j.optString("name", "Device").take(64),
                deviceModel = j.optString("model", Build.MODEL ?: "Unknown").take(64),
                method = j.optString("method", "LAN")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun deviceModel(): String = try {
        (Build.MANUFACTURER + " " + Build.MODEL).trim().take(64)
    } catch (e: Exception) {
        "Unknown"
    }
}
