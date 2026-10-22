// Protocol contract adapted from DiPlay/xcertplay, GPL-3.0. See docs/CARPLAY-NOTICES.md.
package com.shilapi.xcertplay.mfi

enum class MfiCertificateType { MFI, BAA }

class BaaCertificatePair(leaf: ByteArray, intermediate: ByteArray) {
    val leaf = leaf.copyOf()
    val intermediate = intermediate.copyOf()
    init { require(leaf.isNotEmpty() && intermediate.isNotEmpty()) }
}

interface MfiAuthenticator {
    val certificateType: MfiCertificateType get() = MfiCertificateType.MFI
    fun protocolMajor(): Int
    fun readCertificate(maximumOutputLength: Int = 16 * 1024): ByteArray
    fun signChallenge(challenge: ByteArray): ByteArray
    fun baaCertificates(): BaaCertificatePair = throw MfiInvalidDataException("BAA identity unavailable")
}

open class MfiException(message: String, cause: Throwable? = null) : Exception(message, cause)
class MfiInvalidDataException(message: String, cause: Throwable? = null) : MfiException(message, cause)
class MfiAuthenticationFailedException(val errorCode: Int?, cause: Throwable? = null) :
    MfiException("Accessory authentication failed", cause)
