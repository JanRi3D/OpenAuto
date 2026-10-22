// Adapted from DiPlay 143f4674b60b564a16baae5110b3b90fb7277631 (GPL-3.0).
package com.shilapi.xcertplay.media

import java.io.Closeable

/** Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay microphone uplink. */
internal interface MicrophoneOpusEncoder : Closeable {
    /** Short implementation name for diagnostic reports. */
    val implementation: String

    val available: Boolean

    /** Encodes one 20 ms little-endian 16-bit PCM frame and returns the Opus access units it produced. */
    fun encode(pcm: ByteArray): List<ByteArray>
}

internal object MicrophoneOpusEncoders {
    /** API 16 always uses the bundled software encoder; no modern platform encoder is loaded. */
    fun create(
        bitrate: Int,
        software: (Int) -> MicrophoneOpusEncoder = { SoftwareOpusEncoder(it) },
    ): MicrophoneOpusEncoder? {
        val softwareEncoder = software(bitrate)
        if (softwareEncoder.available) return softwareEncoder
        softwareEncoder.close()
        return null
    }
}
