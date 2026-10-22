package com.shilapi.xcertplay.transport

import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.junit.Assert.*
import org.junit.Test

class LockdownTlsIdentityTest {
    @Test fun acceptsOnlyThePairedDevicePublicKey() {
        val trust = LockdownTlsEngineFactory.PairedDeviceTrustManager(record.deviceCertificatePem)
        trust.checkServerTrusted(arrayOf(certificate(record.deviceCertificatePem)), "RSA")
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(certificate(record.rootCertificatePem)), "RSA")
        }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(), "RSA") }
    }

    @Test fun createsApi16CompatibleTlsWithoutInstallingGlobalProviders() {
        val providers = Security.getProviders().map { it.name }
        val engine = LockdownTlsEngineFactory.create(record)
        assertTrue(engine.useClientMode)
        assertTrue(engine.supportedProtocols.contains("TLSv1.2"))
        assertEquals(providers, Security.getProviders().map { it.name })
    }

    private fun certificate(bytes: ByteArray): X509Certificate = LockdownCertificateCompatibility.withPinnedProfile {
        CertificateFactory.getInstance("X.509", org.bouncycastle.jce.provider.BouncyCastleProvider())
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    companion object {
        private val record by lazy {
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pkcs1 = SubjectPublicKeyInfo.getInstance(key.public.encoded).parsePublicKey().encoded
            val pem = "-----BEGIN RSA PUBLIC KEY-----\n${Base64Compat.encode(pkcs1)}\n-----END RSA PUBLIC KEY-----\n"
            LockdownPairRecordGenerator.generate(pem.toByteArray(Charsets.US_ASCII), "00:11:22:33:44:55", "test-host", "test-buid")
        }
    }
}
