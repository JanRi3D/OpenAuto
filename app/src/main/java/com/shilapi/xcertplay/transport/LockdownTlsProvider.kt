package com.shilapi.xcertplay.transport

import java.math.BigInteger
import java.security.Principal

import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.jcajce.util.JcaJceHelper
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.bouncycastle.tls.CertificateType
import org.bouncycastle.tls.crypto.TlsCertificate
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCertificate
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCrypto
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider

/**
 * API16's globally installed legacy BC calls SHA-256/RSA "SHA256WithRSAEncryption". Modern BC
 * certificate parsing consults that global alias even when the crypto provider is explicit,
 * while modern JSSE constraint checks expect SHA256withRSA. Canonicalize only strong PKCS#1
 * signature OIDs at this local TLS certificate seam. Encoded bytes, verification, key strength,
 * all BC algorithm constraints and the mandatory paired-device public-key pin remain intact.
 */
internal object LockdownTlsProvider {
    fun create(provider: Provider): BouncyCastleJsseProvider =
        BouncyCastleJsseProvider(false, CryptoProvider(provider))

    fun normalize(certificate: X509Certificate): X509Certificate =
        if (certificate is CanonicalCertificate) certificate else CanonicalCertificate(certificate)

    internal fun signatureName(oid: String, original: String, parameters: ByteArray?): String {
        val canonical = when (oid) {
            "1.2.840.113549.1.1.11" -> "SHA256withRSA"
            "1.2.840.113549.1.1.12" -> "SHA384withRSA"
            "1.2.840.113549.1.1.13" -> "SHA512withRSA"
            else -> return original
        }
        require(parameters == null || parameters.contentEquals(byteArrayOf(5, 0))) {
            "Invalid RSA PKCS#1 certificate signature parameters"
        }
        return canonical
    }

    private class CryptoProvider(provider: Provider) : JcaTlsCryptoProvider() {
        init { setProvider(provider) }
        override fun create(keyRandom: SecureRandom, nonceRandom: SecureRandom): JcaTlsCrypto =
            Crypto(helper, altHelper, keyRandom, nonceRandom)
    }

    private class Crypto(helper: JcaJceHelper, alternate: JcaJceHelper?, keyRandom: SecureRandom, nonceRandom: SecureRandom) :
        JcaTlsCrypto(helper, alternate, keyRandom, nonceRandom) {
        override fun createCertificate(encoding: ByteArray): TlsCertificate = createCertificate(CertificateType.X509, encoding)
        override fun createCertificate(type: Short, encoding: ByteArray): TlsCertificate {
            if (type != CertificateType.X509) return super.createCertificate(type, encoding)
            return LockdownCertificateCompatibility.withPinnedProfile {
                val parsed = JcaTlsCertificate.parseCertificate(getHelper(), encoding)
                JcaTlsCertificate(this, normalize(parsed))
            }
        }
    }

    /** Delegation preserves certificate semantics; only a verified signature OID's display name changes. */
    internal class CanonicalCertificate(private val certificate: X509Certificate) : X509Certificate() {
        override fun getSigAlgName(): String = signatureName(certificate.sigAlgOID, certificate.sigAlgName, certificate.sigAlgParams)
        override fun getSigAlgOID(): String = certificate.sigAlgOID
        override fun getSigAlgParams(): ByteArray? = certificate.sigAlgParams
        override fun checkValidity() = certificate.checkValidity()
        override fun checkValidity(date: Date) = certificate.checkValidity(date)
        override fun getVersion(): Int = certificate.version
        override fun getSerialNumber(): BigInteger = certificate.serialNumber
        @Suppress("DEPRECATION") override fun getIssuerDN(): Principal = certificate.issuerDN
        @Suppress("DEPRECATION") override fun getSubjectDN(): Principal = certificate.subjectDN
        override fun getIssuerX500Principal(): X500Principal = certificate.issuerX500Principal
        override fun getSubjectX500Principal(): X500Principal = certificate.subjectX500Principal
        override fun getNotBefore(): Date = certificate.notBefore
        override fun getNotAfter(): Date = certificate.notAfter
        override fun getTBSCertificate(): ByteArray = certificate.tbsCertificate
        override fun getSignature(): ByteArray = certificate.signature
        override fun getIssuerUniqueID(): BooleanArray? = certificate.issuerUniqueID
        override fun getSubjectUniqueID(): BooleanArray? = certificate.subjectUniqueID
        override fun getKeyUsage(): BooleanArray? = certificate.keyUsage
        override fun getBasicConstraints(): Int = certificate.basicConstraints
        override fun getEncoded(): ByteArray = certificate.encoded
        override fun verify(key: PublicKey) = certificate.verify(key)
        override fun verify(key: PublicKey, provider: String) = certificate.verify(key, provider)
        override fun toString(): String = "Lockdown X.509 certificate (identity redacted)"
        override fun getPublicKey(): PublicKey = certificate.publicKey
        override fun hasUnsupportedCriticalExtension(): Boolean = certificate.hasUnsupportedCriticalExtension()
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = certificate.criticalExtensionOIDs
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = certificate.nonCriticalExtensionOIDs
        override fun getExtensionValue(oid: String): ByteArray? = certificate.getExtensionValue(oid)
        override fun getExtendedKeyUsage(): MutableList<String>? = certificate.extendedKeyUsage
        override fun getSubjectAlternativeNames(): MutableCollection<MutableList<*>?>? = certificate.subjectAlternativeNames
        override fun getIssuerAlternativeNames(): MutableCollection<MutableList<*>?>? = certificate.issuerAlternativeNames
    }
}
