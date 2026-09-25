package com.crocxshare.app.core.security

import com.crocxshare.app.core.protocol.Checksums
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Per-session TLS identity: a fresh self-signed EC certificate for every transfer
 * session. Peers authenticate by pinning the SHA-256 fingerprint of the exact
 * certificate, distributed via the QR payload (full 256-bit) or the short pairing
 * code (104-bit prefix).
 *
 * Uses Android built-in JCA (standard "EC" algorithm, no external provider
 * registration) — BouncyCastle is used only for X.509 encoding.
 */
object Tls {

    class SessionIdentity(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
        val sha256Fingerprint: ByteArray
    )

    fun createIdentity(cn: String): SessionIdentity {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(256, SecureRandom())
        }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=" + cn.replace(",", "") + ", O=CrocXShare")
        val cert = JcaX509v3CertificateBuilder(
            name,
            BigInteger(160, SecureRandom()),
            Date(now - 60_000),
            Date(now + 24L * 3600 * 1000),
            name,
            kp.public
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
        val x509 = JcaX509CertificateConverter().getCertificate(cert)
        val fp = Checksums.sha256(x509.encoded)
        return SessionIdentity(kp, x509, fp)
    }

    fun serverContext(identity: SessionIdentity): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry("cxs", identity.keyPair.private, null, arrayOf(identity.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        return SSLContext.getInstance("TLSv1.3").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
    }

    fun clientContext(expectedFingerprint: ByteArray): SSLContext {
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw java.security.cert.CertificateException("client auth not used")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("empty chain")
                val peer = chain[0]
                if (peer.notBefore.time > System.currentTimeMillis() ||
                    peer.notAfter.time < System.currentTimeMillis()) {
                    throw java.security.cert.CertificateException("certificate expired")
                }
                val actual = Checksums.sha256(peer.encoded)
                if (expectedFingerprint.isEmpty() ||
                    actual.size < expectedFingerprint.size ||
                    !actual.copyOfRange(0, expectedFingerprint.size)
                        .contentEquals(expectedFingerprint)) {
                    throw java.security.cert.CertificateException(
                        "certificate fingerprint mismatch (possible man-in-the-middle)"
                    )
                }
            }
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf<TrustManager>(tm), SecureRandom())
        }
    }
}
