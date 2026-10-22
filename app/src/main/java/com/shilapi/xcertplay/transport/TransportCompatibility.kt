// Adapted from DiPlay 143f4674b60b564a16baae5110b3b90fb7277631 (GPL-3.0). See THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay.transport

/** Base64 supplied by the already bundled lightweight BC runtime, including on API 16. */
internal object Base64Compat {
    fun decode(value: String): ByteArray = try {
        org.bouncycastle.util.encoders.Base64.decode(value)
    } catch (error: Exception) { throw IllegalArgumentException("Invalid base64", error) }
    fun decodeMime(value: ByteArray): ByteArray = decode(value.toString(Charsets.US_ASCII))
    fun encode(value: ByteArray): String = org.bouncycastle.util.encoders.Base64.toBase64String(value)
    fun encodeLines(value: ByteArray, width: Int, separator: String): String =
        encode(value).chunked(width).joinToString(separator)
}

/** Suppression was introduced on Android API 19; preserve it where the runtime supports it. */
internal fun Throwable.addTransportSuppressed(other: Throwable) {
    if (other === this) return
    try {
        Throwable::class.java.getMethod("addSuppressed", Throwable::class.java).invoke(this, other)
    } catch (_: Exception) { /* The primary failure remains authoritative on older runtimes. */ }
}

data class UsbDeviceId(val vendorId: Int, val productId: Int)
