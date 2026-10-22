package me.ri3d.openauto.carplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import me.ri3d.openauto.media.AudioOutput
import org.concentus.OpusDecoder
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One stream's bounded jitter queue and legacy synchronous decoder, played through Android Auto's PCM output. */
internal class CarPlayAudioOutput(val format: AudioFormat, private val report: (String)->Unit) : Closeable {
    private data class Packet(val sequence: Int, val sample: Int, val body: ByteArray, val arrival: Long)
    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<Packet>(12)
    // Pauses a track that ran dry until its buffer is full again and pads short sounds with silence
    // (Android 4.1+ AudioFlinger plays a dry track packet by packet).
    private val output = AudioOutput("carplay-${format.audioType}")
    @Volatile var released = false
        private set
    private var codec: MediaCodec? = null
    private var inputs = emptyArray<ByteBuffer>()
    private var outputs = emptyArray<ByteBuffer>()
    private val info = MediaCodec.BufferInfo()
    private val clock = RtpSampleClock(format.rtpClockRate)
    private var opus: OpusDecoder? = null
    private val thread = Thread({ loop() }, "carplay-audio-${format.audioType}").apply { isDaemon=true; start() }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!running.get()) return
        require(rtp.size <= 16384) { "audio RTP packet too large" }
        val body = AudioFrames.rtpPayload(rtp)
        val sequence = ((rtp[2].toInt() and 255) shl 8) or (rtp[3].toInt() and 255)
        val packet = Packet(sequence,sample,body,System.nanoTime())
        // Drop the oldest queued sound after a Wi-Fi gap rather than increasing latency without bound.
        if (!queue.offer(packet)) { queue.poll(); queue.offer(packet) }
    }

    @Suppress("DEPRECATION")
    private fun loop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            require(format.channels in 1..2 && format.sampleRate in 8000..96000)
            when (format.codec) {
                AudioCodecKind.AAC_LC -> {
                    val csd = AudioFrames.aacConfig(format.sampleRate,format.channels)
                    val media = MediaFormat.createAudioFormat("audio/mp4a-latm",format.sampleRate,format.channels)
                    media.setByteBuffer("csd-0",ByteBuffer.wrap(csd))
                    media.setInteger("aac-profile",2)
                    media.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
                    val decoder = MediaCodec.createDecoderByType("audio/mp4a-latm")
                    codec = decoder
                    decoder.configure(media,null,null,0); decoder.start(); inputs=decoder.inputBuffers; outputs=decoder.outputBuffers
                }
                AudioCodecKind.OPUS -> {
                    require(format.sampleRate == 48000) { "Opus must decode at 48 kHz" }
                    opus = OpusDecoder(48000,format.channels)
                    open(48000,format.channels)
                }
                AudioCodecKind.LPCM -> open(format.sampleRate,format.channels)
            }
            var lastSequence: Int? = null
            val pending = ArrayList<Packet>(12)
            while (running.get()) {
                val incoming = queue.poll(if (pending.isEmpty()) 10 else 2,TimeUnit.MILLISECONDS)
                if (incoming != null) pending.add(incoming)
                while (pending.size < 12) pending.add(queue.poll() ?: break)
                if (pending.isEmpty()) { drain(); continue }
                // Brief bounded reorder window; AudioTrack supplies playout pacing thereafter.
                val oldest = pending.minOf { it.arrival }
                if (pending.size < 3 && System.nanoTime() - oldest < 8_000_000L) { drain(); continue }
                val previous = lastSequence
                val first = if (previous == null) pending[0].sequence else (previous+1) and 65535
                pending.sortBy { ((it.sequence-first+32768) and 65535)-32768 }
                val packet = pending.removeAt(0)
                if (System.nanoTime()-packet.arrival > 250_000_000L) continue
                if (previous != null) {
                    val delta = ((packet.sequence-previous+32768) and 65535)-32768
                    if (delta <= 0) continue // duplicate or late packet
                    if (delta > 1 && format.codec == AudioCodecKind.OPUS && delta <= 4) {
                        repeat(delta-1) { concealOpus() }
                    }
                }
                lastSequence = packet.sequence
                handle(packet)
                drain()
            }
        } catch (_: InterruptedException) {
        } catch (error: Exception) {
            if (running.get()) report("Audio ${format.audioType}: stopped (${error.javaClass.simpleName})")
        } catch (_: AssertionError) {
            if (running.get()) report("Audio ${format.audioType}: software decoder rejected packet")
        } finally {
            running.set(false)
            queue.clear()
            runCatching { codec?.stop() }; runCatching { codec?.release() }; codec=null
            runCatching { output.close() }
            released=true
        }
    }

    private fun handle(packet: Packet) {
        when (format.codec) {
            AudioCodecKind.LPCM -> write(AudioFrames.pcmLittleEndian(packet.body,format.channels))
            AudioCodecKind.OPUS -> decodeOpus(packet.body)
            AudioCodecKind.AAC_LC -> {
                val decoder = codec ?: return
                repeat(20) {
                    if (!running.get()) return
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = inputs[index]
                        require(packet.body.size <= input.capacity()) { "AAC input buffer too small" }
                        input.clear(); input.put(packet.body)
                        decoder.queueInputBuffer(index,0,packet.body.size,clock.micros(packet.sample),0)
                        return
                    }
                    drain()
                }
                report("Audio ${format.audioType}: decoder stalled; dropping access unit")
            }
        }
    }

    private fun decodeOpus(body: ByteArray?) {
        val samples = ShortArray(5760*format.channels)
        val count = opus!!.decode(body,0,body?.size ?: 0,samples,0,if(body==null) 960 else 5760,false)
        val pcm = ByteArray(count*format.channels*2)
        for (i in 0 until count*format.channels) { pcm[i*2]=samples[i].toByte(); pcm[i*2+1]=(samples[i].toInt() shr 8).toByte() }
        write(pcm)
    }
    private fun concealOpus() { decodeOpus(null) }

    @Suppress("DEPRECATION")
    private fun drain() {
        val decoder = codec ?: return
        while (running.get()) {
            when (val index = decoder.dequeueOutputBuffer(info,0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val decoded = decoder.outputFormat
                    open(decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE),decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> outputs=decoder.outputBuffers
                else -> {
                    if (index < 0) return
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = outputs[index].duplicate()
                            require(info.offset >= 0 && info.size <= buffer.capacity()-info.offset)
                            buffer.position(info.offset); buffer.limit(info.offset+info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes); write(bytes)
                        }
                    } finally { decoder.releaseOutputBuffer(index,false) }
                }
            }
        }
    }

    private fun open(rate: Int, channels: Int) {
        require(channels in 1..2 && rate in 8000..96000)
        check(output.open(rate,channels)) { "PCM output unsupported" }
    }

    /** Never blocks: the output's own thread writes to the track, and a full queue drops its oldest sound. */
    private fun write(bytes: ByteArray) = output.write(bytes,0,bytes.size)

    override fun close() {
        running.set(false)
        thread.interrupt()
        if (Thread.currentThread() !== thread) runCatching { thread.join(1000) }
    }
}
