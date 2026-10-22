// Adapted from DiPlay 143f4674b60b564a16baae5110b3b90fb7277631 (GPL-3.0).
package com.shilapi.xcertplay.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean

/** One recorder/encoder/UDP owner. Closing stops AudioRecord to unblock a legacy blocking read. */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile var released = true
        private set
    private var thread: Thread? = null

    fun start(): Boolean {
        require(config.channels == 1 && config.sampleRate in 8000..48000 && config.port in 1..65535)
        require(config.frameMillis in 1..100 && config.frameBytes in 2..19200 && config.key.size == 32)
        require(config.codec == AudioCodecKind.LPCM || config.codec == AudioCodecKind.OPUS)
        require(config.codec != AudioCodecKind.OPUS || config.sampleRate == 48000)
        if (!running.compareAndSet(false,true)) return true
        released = false
        thread = Thread({ capture() }, "carplay-microphone").apply { isDaemon = true; start() }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun capture() {
        var record: AudioRecord? = null
        var udp: DatagramSocket? = null
        var encoder: MicrophoneOpusEncoder? = null
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            if (config.codec == AudioCodecKind.OPUS) {
                encoder = MicrophoneOpusEncoders.create((config.bitrate ?: 32000).coerceIn(6000,128000))
                    ?: throw IllegalStateException("software Opus encoder unavailable")
            }
            val minimum = AudioRecord.getMinBufferSize(config.sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "microphone sample rate unsupported" }
            val source = if (config.audioType == "telephony") MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            record = AudioRecord(source,config.sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum*2,config.frameBytes*4))
            check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone did not initialize" }
            udp = DatagramSocket()
            synchronized(lock) {
                if (!running.get()) return
                recorder = record
                socket = udp
            }
            record.startRecording()
            if (!running.get()) return
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone did not start" }
            onDiagnostic("Microphone: capture ${config.codec} ${config.sampleRate} Hz")
            val frame = ByteArray(config.frameBytes)
            val counters = MicrophoneCounters()
            var readRestarts = 0
            var emptyRestarts = 0
            var lastAudio = System.nanoTime()
            while (running.get()) {
                var filled = 0
                while (filled < frame.size && running.get()) {
                    val count = record.read(frame,filled,frame.size-filled)
                    if (!running.get()) break
                    if (count > 0) { filled += count; lastAudio = System.nanoTime(); continue }
                    // As in DiPlay: the first read after a radio enters call mode can fail, and a recorder
                    // with no input routed returns nothing. Restart it a few times instead of ending the call.
                    if (count < 0) {
                        check(readRestarts < MAX_RESTARTS && restart(record)) { "microphone read failed ($count)" }
                        readRestarts++
                        onDiagnostic("Microphone: restarted after a failed read")
                    } else if (emptyRestarts < MAX_RESTARTS && System.nanoTime() - lastAudio >= 1_000_000_000L) {
                        emptyRestarts++
                        lastAudio = System.nanoTime()
                        if (restart(record)) onDiagnostic("Microphone: restarted after a second without input")
                    } else {
                        Thread.sleep(5)
                    }
                }
                if (!running.get()) break
                val bodies = if (encoder != null) encoder.encode(frame) else listOf(MicrophonePacketizer.toWirePcm(frame))
                if (bodies.isEmpty()) {
                    // Capture time still passes when the software encoder rejects a damaged frame.
                    counters.timestamp += config.rtpSamplesPerPacket
                }
                for (body in bodies) {
                    if (!running.get()) break
                    val wire = MicrophonePacketizer.sealPacket(config.key,config.payloadType,counters,body,config.rtpSamplesPerPacket)
                    udp.send(DatagramPacket(wire,wire.size,config.host,config.port))
                }
            }
        } catch (error: Exception) {
            if (running.get()) onDiagnostic("Microphone: stopped (${error.javaClass.simpleName})")
        } finally {
            running.set(false)
            synchronized(lock) { recorder = null; socket = null }
            runCatching { record?.stop() }
            runCatching { record?.release() }
            udp?.close()
            encoder?.close()
            released = true
        }
    }

    private fun restart(record: AudioRecord): Boolean = try {
        runCatching { record.stop() }
        Thread.sleep(50) // close() interrupts this
        record.startRecording()
        record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    } catch (_: IllegalStateException) { false }

    override fun close() {
        running.set(false)
        synchronized(lock) {
            socket?.close()
            runCatching { recorder?.stop() }
        }
        thread?.interrupt()
        if (Thread.currentThread() !== thread) runCatching { thread?.join(1000) }
    }

    private companion object { const val MAX_RESTARTS = 3 }
}
