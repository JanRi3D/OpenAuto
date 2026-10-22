// Adapted from DiPlay 143f4674b60b564a16baae5110b3b90fb7277631 (GPL-3.0). See THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay.transport


import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.cert.CertificateException

import java.io.ByteArrayInputStream

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509TrustManager

/**
 * Builds an unstarted client TLS engine for the dedicated USB Lockdown channel.
 *
 * The peer public key is pinned to the physical USB Lockdown pair record.
 * It must not be reused for internet or general-purpose TLS connections.
 */
object LockdownTlsEngineFactory {
    @Throws(GeneralSecurityException::class)
    fun create(pairRecord: LockdownPairRecord): SSLEngine =
        LockdownCertificateCompatibility.withPinnedProfile { createCompatible(pairRecord) }

    private fun createCompatible(pairRecord: LockdownPairRecord): SSLEngine {
        val password = charArrayOf('l', 'o', 'c', 'k', 'd', 'o', 'w', 'n')
        // Lockdown presents the root identity from the pair record for both the session and
        // service TLS channels. HostCertificate is part of pairing, not this TLS identity.
        val privateKeyPem = pairRecord.rootPrivateKeyPem
        val certificatePem = pairRecord.rootCertificatePem
        var privateKeyDer: ByteArray? = null
        try {
            privateKeyDer = decodePkcs8Pem(privateKeyPem)
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyDer))
            val certificate = LockdownTlsProvider.normalize(CertificateFactory.getInstance("X.509", bcProvider)
                    .generateCertificate(ByteArrayInputStream(certificatePem)) as X509Certificate)
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setKeyEntry(KEY_ALIAS, privateKey, password, arrayOf(certificate))
            }
            val keyManagers = KeyManagerFactory.getInstance("X.509", jsseProvider).apply {
                init(keyStore, password)
            }.keyManagers
            val context = SSLContext.getInstance("TLS", jsseProvider).apply {
                // Use the platform's seeded entropy source explicitly; API16 has no BC "DEFAULT"
                // SecureRandom service and source shrinking intentionally omits that provider family.
                init(keyManagers, arrayOf(PairedDeviceTrustManager(pairRecord.deviceCertificatePem)), SecureRandom())
            }
            return context.createSSLEngine(PEER_HOST, PEER_PORT).apply {
                useClientMode = true

            }
        } finally {
            password.fill('\u0000')
            privateKeyPem.fill(0)
            certificatePem.fill(0)
            privateKeyDer?.fill(0)
        }
    }

    private fun decodePkcs8Pem(pem: ByteArray): ByteArray {
        val begin = pem.indexOf(BEGIN_PRIVATE_KEY)
        val end = pem.indexOf(END_PRIVATE_KEY, begin + BEGIN_PRIVATE_KEY.size)
        if (begin < 0 || end < 0) throw GeneralSecurityException("Invalid PKCS#8 private key PEM")
        val encoded = pem.copyOfRange(begin + BEGIN_PRIVATE_KEY.size, end)
        return try {
            Base64Compat.decodeMime(encoded)
        } catch (error: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid PKCS#8 private key PEM", error)
        } finally {
            encoded.fill(0)
        }
    }

    private fun ByteArray.indexOf(needle: ByteArray, startIndex: Int = 0): Int {
        if (needle.isEmpty()) return startIndex.coerceIn(0, size)
        for (offset in startIndex.coerceAtLeast(0)..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[offset + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return offset
        }
        return -1
    }

    /** Pin the public key captured during the physical USB Lockdown pairing transaction. */
    internal class PairedDeviceTrustManager(certificatePem: ByteArray) : X509TrustManager {
        private val expectedPublicKey: ByteArray = LockdownCertificateCompatibility.withPinnedProfile {
            try {
                val certificate = LockdownTlsProvider.normalize(CertificateFactory.getInstance("X.509", bcProvider)
                    .generateCertificate(ByteArrayInputStream(certificatePem)) as X509Certificate)
                certificate.publicKey.encoded
            } finally { certificatePem.fill(0) }
        }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("USB Lockdown is a client-only TLS channel")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty() || !MessageDigest.isEqual(expectedPublicKey, chain[0].publicKey.encoded))
                throw CertificateException("USB Lockdown peer key does not match the paired iPhone")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val bcProvider by lazy { BouncyCastleProvider() }
    private val jsseProvider by lazy { LockdownTlsProvider.create(bcProvider) }
    private const val KEY_ALIAS = "lockdown-host"
    private const val PEER_HOST = "Device"
    private const val PEER_PORT = 0
    private val BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----".toByteArray(Charsets.US_ASCII)
    private val END_PRIVATE_KEY = "-----END PRIVATE KEY-----".toByteArray(Charsets.US_ASCII)
}
