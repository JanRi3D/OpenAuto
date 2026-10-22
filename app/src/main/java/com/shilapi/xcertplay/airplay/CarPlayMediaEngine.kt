package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Identity of one CarPlay audio stream: the stream type plus its CarPlay audio type. */
data class AudioStreamId(val type: Int, val audioType: String)

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    /** A frame with the iPhone's frame time and its arrival time (System.nanoTime); see [ScreenStream.Listener]. */
    fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
        onVideoFrame(type, naluBytes)
    fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {}
    fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(id: AudioStreamId) {}
    fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(id: AudioStreamId) {}
    fun onIapMessage(bytes: ByteArray) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Telephony and speech
 * streams can additionally return a PCM microphone uplink through the sink.
 */
/**
 * Delivers a screen stream's data to [sink] only while [isCurrent]: a replaced stream's thread may still
 * be delivering when its successor starts. The check runs before each delivery, so a callback already
 * past it can still hand over one item; the successor's configuration and keyframe follow it. The iPhone
 * connects after SETUP returns the port, which is after the stream is registered as current.
 */
internal fun currentScreenListener(type: Int, sink: MediaSink, isCurrent: () -> Boolean) = object : ScreenStream.Listener {
    override fun onCodec(codec: VideoCodec) { if (isCurrent()) sink.onVideoCodec(type, codec) }
    override fun onConfig(codecData: ByteArray) { if (isCurrent()) sink.onVideoConfig(type, codecData) }
    override fun onFrame(naluBytes: ByteArray) { if (isCurrent()) sink.onVideoFrame(type, naluBytes) }
    override fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (isCurrent()) sink.onVideoFrame(type, naluBytes, senderNanos, arrivalNanos)
    }
}

class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
) : AirPlayMediaHandler, Closeable {
    private val engineClosed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val knownSessions = Collections.newSetFromMap(ConcurrentHashMap<AirPlaySession, Boolean>())
    private data class OutputKey(val type: Int, val audioType: String? = null)
    private class OutputOwner(val key: StreamKey, val output: OutputKey) {
        var installing = true
        var cancelled = false
        var deliveries = 0
        var cleanupStarted = false
        @Volatile var microphoneConfigured = false
        @Volatile var resource: Closeable? = null
        val deliveringThreads = HashMap<Thread, Int>()
    }
    private val outputLock = Object()
    private val outputOwners = HashMap<OutputKey, OutputOwner>()

    /** A transition reserves the device output while the former renderer is retired. */
    private fun claimOutput(key: StreamKey, output: OutputKey): OutputOwner? {
        val next = OutputOwner(key, output)
        val previous = synchronized(outputLock) {
            if (engineClosed.get() || key.session.isClosed) return null
            val current = outputOwners[output]
            // A sink callback may reenter setup. It cannot wait for its own delivery to finish.
            if (current?.installing == true || current?.deliveringThreads?.containsKey(Thread.currentThread()) == true) return null
            outputOwners[output] = next
            current
        }
        if (previous != null) {
            if (!drainOutput(previous)) {
                synchronized(outputLock) { if (outputOwners[output] === next) outputOwners[output] = previous }
                return null
            }
            try { retireOutputResources(previous.key, notify = true, microphoneConfigured = previous.microphoneConfigured, expectedResource = previous.resource) }
            catch (failure: Throwable) { synchronized(outputLock) { if (outputOwners[output] === next) outputOwners.remove(output) }; throw failure }
        }
        return next
    }

    private fun drainOutput(owner: OutputOwner): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        synchronized(outputLock) {
            if (owner.deliveringThreads.containsKey(Thread.currentThread())) return false
            while (owner.deliveries != 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try { outputLock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt()) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
        }
        return true
    }

    private fun ownsOutput(owner: OutputOwner): Boolean = synchronized(outputLock) {
        outputOwners[owner.output] === owner && !owner.installing && !owner.cancelled &&
            !engineClosed.get() && !owner.key.session.isClosed
    }

    private fun publishOutput(owner: OutputOwner): Boolean = synchronized(outputLock) {
        if (outputOwners[owner.output] !== owner || owner.cancelled || engineClosed.get() || owner.key.session.isClosed) false
        else { owner.installing = false; true }
    }

    private fun deliverOutput(owner: OutputOwner, callback: () -> Unit) {
        val thread = Thread.currentThread()
        synchronized(outputLock) {
            if (!ownsOutput(owner)) return
            owner.deliveries++
            owner.deliveringThreads[thread] = (owner.deliveringThreads[thread] ?: 0) + 1
        }
        try { callback() } finally {
            val finish = synchronized(outputLock) {
                owner.deliveries--
                val count = (owner.deliveringThreads[thread] ?: 1) - 1
                if (count == 0) owner.deliveringThreads.remove(thread) else owner.deliveringThreads[thread] = count
                outputLock.notifyAll()
                owner.deliveries == 0 && owner.cancelled && owner.installing
            }
            if (finish) finishRetirement(owner)
        }
    }

    private fun retireOutputResources(key: StreamKey, notify: Boolean, microphoneConfigured: Boolean = false,
        expectedResource: Closeable? = null) {
        if (expectedResource == null) streams.remove(key)?.let(::safeClose) else {
            safeClose(expectedResource)
            val registered = streams[key]
            if (registered != null && registered !== expectedResource) return
            streams.remove(key, expectedResource)
        }
        audioMeta.remove(key)
        audioCaptures.remove(key)?.let(::safeClose)
        val stoppedMicrophone = synchronized(pendingMicrophone) { pendingMicrophone.remove(key) != null }
        if (isScreenStreamType(key.type)) {
            if (key.type == STREAM_TYPE_MAIN_SCREEN) key.session.retireMainScreenSession()
            if (notify) {
                sink.setVideoRecoveryHandler(key.type) {}
                sink.setVideoDiagnosticHandler(key.type) {}
                sink.onScreenStreamActive(key.type, false)
            }
        } else if (notify) {
            val id = AudioStreamId(key.type, key.audioType)
            if (stoppedMicrophone || microphoneConfigured) sink.onMicrophoneStopped(id)
            sink.onAudioStopped(id)
        }
    }

    private fun retireOutput(key: StreamKey) {
        val output = OutputKey(key.type, if (isScreenStreamType(key.type)) null else key.audioType)
        val owner = synchronized(outputLock) {
            outputOwners[output]?.takeIf { it.key == key }?.also { it.cancelled = true }
        }
        if (owner == null) {
            val unclaimed = synchronized(outputLock) { !outputOwners.containsKey(output) }
            retireOutputResources(key, notify = unclaimed && streams.containsKey(key))
            return
        }
        // The installer keeps its reservation until retirement/setup has finished.
        if (owner.installing) { retireOutputResources(key, notify = false, expectedResource = owner.resource); return }
        synchronized(outputLock) { owner.installing = true }
        if (!drainOutput(owner)) { retireOutputResources(key, notify = false, expectedResource = owner.resource); return }
        finishRetirement(owner)
    }

    private fun finishRetirement(owner: OutputOwner) {
        synchronized(outputLock) {
            if (owner.cleanupStarted || outputOwners[owner.output] !== owner) return
            owner.cleanupStarted = true
        }
        try { retireOutputResources(owner.key, notify = true, microphoneConfigured = owner.microphoneConfigured, expectedResource = owner.resource) }
        finally { synchronized(outputLock) { if (outputOwners[owner.output] === owner) outputOwners.remove(owner.output) } }
    }

    private fun abandonOutput(owner: OutputOwner) {
        val current = synchronized(outputLock) {
            if (outputOwners[owner.output] !== owner) false else { owner.cancelled = true; owner.installing = true; true }
        }
        if (current && drainOutput(owner)) finishRetirement(owner)
        else retireOutputResources(owner.key, notify = false, expectedResource = owner.resource)
    }

    private fun acceptSession(session: AirPlaySession): Boolean = synchronized(lifecycleLock) {
        if (engineClosed.get() || session.isClosed) false else { knownSessions.add(session); true }
    }
    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
        val audioType: String = "",
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    ) {
        val position = FeedbackPositionMonitor(format.rtpClockRate)
    }

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    private val pendingMicrophone = ConcurrentHashMap<StreamKey, MicrophoneConfig>()
    private val audioCaptures = ConcurrentHashMap<StreamKey, AudioPacketCapture>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    private val videoSettingsChannels = ConcurrentHashMap<AirPlaySession, VideoSettingsChannel>()
    private val bufferedStreams = ConcurrentHashMap<AirPlaySession, BufferedStream>()
    private val microphoneSchedulerLazy = lazy {
        synchronized(lifecycleLock) {
            check(!engineClosed.get()) { "CarPlay media engine closed" }
            ScheduledThreadPoolExecutor(1) { task -> Thread(task, "carplay-mic-start").apply { isDaemon = true } }.apply {
                // API 16 has no removeOnCancelPolicy; cancelled tasks expire after 500 ms.
                setKeepAliveTime(MICROPHONE_SCHEDULER_IDLE_SECONDS, TimeUnit.SECONDS)
                allowCoreThreadTimeOut(true)
            }
        }
    }
    private val microphoneScheduler by microphoneSchedulerLazy

    private class BufferedStream(val stream: BufferedAudioStream, val connectionId: Any?)

    // The sink has one type-103 renderer, even while old control sessions remain connected.
    // Never hold this lock when closing/controlling a stream: sink callbacks can reenter us.
    private val bufferedOwnershipLock = Any()
    private var bufferedOwner: AirPlaySession? = null
    private var bufferedTransition: BufferedTransition? = null
    private val retiredBufferedSessions = Collections.newSetFromMap(WeakHashMap<AirPlaySession, Boolean>())
    private class BufferedTransition(val session: AirPlaySession, val candidate: BufferedStream?) {
        var cancelled = false // guarded by bufferedOwnershipLock
        var retiring: BufferedStream? = null
    }

    private fun mayInstallBufferedLocked(session: AirPlaySession): Boolean {
        // A reentrant sink callback cannot wait for itself. Preserve its retirement barrier
        // until cleanup completes; the volatile flag requires no reverse stream-lock order.
        bufferedTransition?.takeIf { it.candidate == null && it.retiring?.stream?.outputCleanupComplete == true }
            ?.let { bufferedTransition = null }
        return !engineClosed.get() && !session.isClosed && session !in retiredBufferedSessions && bufferedTransition == null
    }
    @Volatile private var nextRemoteControlStreamId = FIRST_REMOTE_CONTROL_STREAM_ID
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        synchronized(lifecycleLock) { iapTunnelHandler = if (engineClosed.get()) null else handler }
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        if (!acceptSession(session)) return null
        val key = outputKey(session, stream) ?: return null
        val streamKey = StreamKey(session, type)
        val owner = claimOutput(streamKey, OutputKey(type)) ?: return null
        try {
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        val screen = ScreenStream(key, session::logDebug, session.localAddress, session.remoteAddress)
        owner.resource = screen
        sink.setVideoDiagnosticHandler(type) {
            val message = it
            deliverOutput(owner) {
                if (message == "first frame rendered") session.videoFrameRendered()
                session.logDebug("Video: $message")
            }
        }
        sink.setVideoRecoveryHandler(type) {
            deliverOutput(owner) {
                // The cluster asks for a keyframe of its own screen; the plain command is the main screen's.
                val command = if (type == STREAM_TYPE_ALT_SCREEN) {
                    mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID))
                } else {
                    mapOf("type" to "forceKeyFrame")
                }
                val sent = session.sendCommand(command)
                session.logDebug("Video recovery: requested keyframe sent=$sent")
            }
        }
        val port = screen.listen(
            object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) = deliverOutput(owner) { sink.onVideoCodec(type, codec) }
                override fun onConfig(codecData: ByteArray) = deliverOutput(owner) { sink.onVideoConfig(type, codecData) }
                override fun onFrame(naluBytes: ByteArray) = deliverOutput(owner) { sink.onVideoFrame(type, naluBytes) }
                override fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
                    deliverOutput(owner) { sink.onVideoFrame(type, naluBytes, senderNanos, arrivalNanos) }
                override fun onClosed(cause: Throwable?) {
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    if (ownsOutput(owner)) {
                        retireOutput(streamKey)
                        session.close()
                    }
                }
            },
        )
        streams.put(streamKey, screen)?.close()
        if (!publishOutput(owner)) { abandonOutput(owner); return null }
        deliverOutput(owner) {
            if (type == STREAM_TYPE_MAIN_SCREEN) session.activateMainScreenSession()
            sink.onScreenStreamActive(type, true)
        }
        return port
        } catch (error: Throwable) { abandonOutput(owner); throw error }
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        if (!acceptSession(session)) return null
        val audioTypeValue = stream["audioType"]
        if (audioTypeValue != null && audioTypeValue !is String) return null
        val audioType = (audioTypeValue as? String)?.lowercase(java.util.Locale.US) ?: "default"
        if (audioType !in setOf("media", "default", "alert", "telephony", "speechrecognition", "compatibility")) return null
        // Concurrent streams may share a type (e.g. music, guidance and Siri can all arrive as
        // type 100); only the same (type, audioType) pair replaces a previous stream.
        val streamKey = StreamKey(session, type, audioType)
        val streamId = AudioStreamId(type, audioType)
        val key = outputKey(session, stream) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val format = try {
            AudioStreamCodec.fromFormatBits(formatBits, type, audioType)
        } catch (_: IllegalArgumentException) {
            // Only this stream is declined; screen and the other streams in the same SETUP go ahead.
            session.logDebug("Audio: declined type=$type audioType=$audioType formatBits=0x${java.lang.Long.toHexString(formatBits)}")
            return null
        }
        val owner = claimOutput(streamKey, OutputKey(type, audioType)) ?: return null
        try {
        Log.i(
            TAG,
            "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "formatBits=0x${java.lang.Long.toHexString((stream["audioFormat"] as? Number)?.toLong() ?: 0L)} " +
                "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        val microphone = microphoneConfig(session, type, stream, format)
        owner.microphoneConfigured = microphone != null
        if (microphone != null) synchronized(pendingMicrophone) { pendingMicrophone[streamKey] = microphone }
        if (type == STREAM_TYPE_MAIN_AUDIO && audioType != "media") {
            session.logDebug(
                    "Audio: setup type=$type audioType=$audioType inputPresent=${stream.containsKey("input")} " +
                    "micPort=${if ((stream["dataPort"] as? Number)?.toInt()?.let { it > 0 } == true) "yes" else "no"} " +
                    "microphone=${if (microphone != null) "on" else "off"} fieldCount=${stream.size}",
            )
        }

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        if (capture != null) audioCaptures[streamKey] = capture
        val clockProbe = RtpClockProbe()
        val microphoneStart = microphone?.let { config -> MicrophoneStart { deliverOutput(owner) {
            synchronized(pendingMicrophone) {
                if (!engineClosed.get() && !session.isClosed && pendingMicrophone[streamKey] === config) sink.onMicrophoneStarted(streamId, config)
            }
        } } }
        val audio = AudioStream(key, type, session::logDebug, session.localAddress, session.remoteAddress)
        owner.resource = audio
        val (dataPort, controlPort) = audio.listen(
            object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    deliverOutput(owner) {
                    meta.firstSample = firstSample
                    meta.originNs = System.nanoTime()
                    sink.onAudioStarted(streamId, format, firstSample)
                    microphoneStart?.onDownlink()
                    }
                }

                override fun onRtp(rtp: ByteArray, sample: Int) {
                    deliverOutput(owner) {
                    meta.position.observeRtp(sample)
                    clockProbe.observe(sample, System.nanoTime())?.let { hz ->
                        session.logDebug(
                            "Audio: rtp clock type=$type audioType=$audioType " +
                                "audioFormat=0x${java.lang.Long.toHexString((stream["audioFormat"] as? Number)?.toLong() ?: 0L)} " +
                                "measuredHz=$hz microphoneClockHz=${microphone?.rtpClockRate ?: "none"}",
                        )
                    }
                    sink.onAudioRtp(streamId, format, rtp, sample)
                    }
                }

                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) {
                    deliverOutput(owner) { capture?.record(wire, rtp, sample, error) }
                }
            },
        )
        streams[streamKey] = audio
        audioMeta[streamKey] = meta
        if (!publishOutput(owner)) { abandonOutput(owner); return null }
        if (microphoneStart != null && MicrophoneStart.startsWithoutDownlink(audioType)) {
            microphoneStart.scheduleSilentStart(microphoneScheduler, MicrophoneStart.SILENT_START_MILLIS) { startNow ->
                // Atomic with TEARDOWN, which removes the pending entry before it stops the microphone.
                var started = false
                synchronized(pendingMicrophone) {
                    val pending = pendingMicrophone[streamKey]
                    if (pending === microphone) started = startNow()
                }
                if (started) {
                    session.logDebug(
                        "Audio: microphone started without downlink audio type=$type audioType=$audioType " +
                            "afterMs=${MicrophoneStart.SILENT_START_MILLIS}",
                    )
                }
            }
        }
        return linkedMapOf(
            "type" to type,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        )
        } catch (error: Throwable) { abandonOutput(owner); throw error }
    }

    override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        if (!acceptSession(session)) return null
        if (!synchronized(bufferedOwnershipLock) { mayInstallBufferedLocked(session) }) return null
        val type = BufferedAudioStream.STREAM_TYPE
        val streamKey = StreamKey(session, type, "media")
        val key = stream["shk"] as? ByteArray
        if (key == null || key.size != 32) {
            Log.w(TAG, "buffered audio SETUP without a 32-byte shk; declined")
            return null
        }
        val format = AudioStreamCodec.fromFormatBits((stream["audioFormat"] as? Number)?.toLong() ?: 0L, type, "media")
        if (format.codec != AudioCodecKind.AAC_LC) {
            Log.w(TAG, "buffered audio format is not AAC-LC; declined")
            return null
        }
        if (stream["ct"]?.let { it !is Number || it.toInt() != 4 } == true ||
            stream["spf"]?.let { it !is Number || it.toInt() != 1024 } == true) {
            Log.w(TAG, "buffered audio requires AAC and 1024 samples per frame; declined")
            return null
        }
        val peer = session.remoteAddress ?: return null
        val buffered = try {
            BufferedAudioStream(key, format, sink, session::logDebug, expectedPeer = peer)
        } catch (error: Exception) {
            Log.w(TAG, "buffered audio listener failed", error)
            return null
        }
        // Validate before retiring a healthy owner. Detach its registry entries first, but
        // keep a transition barrier until close has finished its final sink callback.
        val candidate = BufferedStream(buffered, stream["streamConnectionID"])
        val transition = BufferedTransition(session, candidate)
        var previous: BufferedStream? = null
        val accepted = synchronized(bufferedOwnershipLock) {
            if (!mayInstallBufferedLocked(session)) false else {
                bufferedTransition = transition
                bufferedOwner?.let { oldSession ->
                    previous = detachBufferedLocked(oldSession)
                    if (oldSession !== session) retiredBufferedSessions.add(oldSession)
                }
                transition.retiring = previous
                true
            }
        }
        if (!accepted) {
            buffered.close()
            return null
        }
        val retired = runCatching { previous?.stream?.close() }.isSuccess &&
            previous?.stream?.outputCleanupComplete != false
        val installed = synchronized(bufferedOwnershipLock) {
            val usable = retired && bufferedTransition === transition && !transition.cancelled &&
                !engineClosed.get() && !session.isClosed && session !in retiredBufferedSessions
            if (usable) {
                streams[streamKey] = buffered
                bufferedStreams[session] = candidate
                bufferedOwner = session
            }
            if (bufferedTransition === transition) {
                bufferedTransition = if (retired) null else BufferedTransition(session, null).also { it.retiring = previous }
            }
            usable
        }
        if (!installed) {
            buffered.close()
            return null
        }
        buffered.start()
        if (bufferedStreams[session] !== candidate) return null
        Log.i(TAG, "buffered audio stream rate=${format.sampleRate} port=${buffered.port}")
        return linkedMapOf(
            "type" to type,
            "dataPort" to buffered.port,
            "audioBufferSize" to BufferedAudioStream.AUDIO_BUFFER_BYTES,
        )
    }

    override fun onBufferedAudioControl(
        session: AirPlaySession,
        method: String,
        body: Map<String, Any?>,
    ): Map<String, Any?>? {
        val buffered = synchronized(bufferedOwnershipLock) { bufferedStreams[session]?.stream } ?: return null
        val rtpTime = (body["rtpTime"] as? Number)?.toLong()
        val rate = when ((body["rate"] as? Number)?.toDouble()) {
            0.0 -> 0
            1.0 -> 1
            else -> null
        }
        return when (method) {
            "SETRATE", "SETRATEANCHORTIME" -> {
                if (rate == null || rate !in 0..1) null else buffered.setRate(rtpTime, rate, session.syncedNtp())
            }
            "GETANCHOR" -> buffered.anchor()
            "FLUSHBUFFERED" -> {
                (body["flushUntilTS"] as? Number)?.toLong()?.let { buffered.flush(it) }
                null
            }
            else -> null
        }
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        if (!acceptSession(session)) return null
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (session.videoInCar) videoDataStream(session, uuid, stream)?.let { return it }
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            bindAddress = session.localAddress
                ?: when (session.remoteAddress) {
                    is Inet6Address -> InetAddress.getByName("::")
                    is Inet4Address -> InetAddress.getByName("0.0.0.0")
                    else -> InetAddress.getByName("0.0.0.0")
                },
            expectedPeer = session.remoteAddress,
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${session.localAddress?.hostAddress ?: "wildcard"} port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        val streamKey = StreamKey(session, STREAM_TYPE_DATA)
        streams.put(streamKey, if (handler != null) bridge else tunnel)?.close()
        if (engineClosed.get() || session.isClosed) { onTeardown(session, STREAM_TYPE_DATA); return null }
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    /**
     * Video in car: the settings channel gets its own encrypted socket; the remote control sessions that
     * carry playback have none (controlType 1), their messages arrive as commands with X-Apple-StreamID.
     */
    private fun videoDataStream(session: AirPlaySession, uuid: String, stream: Map<String, Any?>): Map<String, Any?>? {
        if (uuid in VideoInCar.REMOTE_CONTROL_UUIDS && (stream["controlType"] as? Number)?.toInt() == 1) {
            val streamId = nextRemoteControlStreamId++
            session.logTrace("video remote-control stream accepted uuid=$uuid streamID=$streamId")
            return linkedMapOf("type" to STREAM_TYPE_DATA, "streamID" to streamId)
        }
        if (uuid != VideoInCar.SETTINGS_CHANNEL_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(
            shared, "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32,
        )
        val channel = VideoSettingsChannel(key(DATASTREAM_OUTPUT_KEY), key(DATASTREAM_INPUT_KEY), { session.logDebug(it) }, session.remoteAddress)
        val port = channel.listen(session.localAddress ?: InetAddress.getByName("::"))
        videoSettingsChannels.put(session, channel)?.close()
        if (engineClosed.get() || session.isClosed) { videoSettingsChannels.remove(session, channel); channel.close(); return null }
        session.logTrace("video settings stream listening port=$port")
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to VIDEO_SETTINGS_STREAM_ID, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        val pending = pendingIapTunnels.remove(session) ?: return
        if (engineClosed.get() || session.isClosed) { pending.bridge.close(); return }
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        if (engineClosed.get() || session.isClosed) return null
        val active = audioMeta.entries.filter { it.key.session === session }.map { it.value }
        val buffered = bufferedStreams[session]
        if (active.isEmpty() && buffered == null) return null
        val streams = active.map { meta ->
            // sampleTime counts on the stream's RTP clock. Counted at the 48 kHz Opus decode rate,
            // a 16 kHz call stream ran 3x ahead of the audio that had arrived, 14.5 s ahead after
            // 20 s, in a call whose far end the driver could not understand (#473).
            val clockRate = meta.format.rtpClockRate
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to clockRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * clockRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
                meta.position.onFeedback(meta.type, entry["streamConnectionID"], sampleTime, nowNs)
                    ?.let(session::logDebug)
            }
            entry
        } + listOfNotNull(buffered?.let { it.stream.feedback(session.syncedNtp(), it.connectionId) })
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        if (type == STREAM_TYPE_DATA) clearPendingIapTunnel(session)
        if (type == BufferedAudioStream.STREAM_TYPE) {
            retireBuffered(session)
            return
        }
        // TEARDOWN carries only the stream type; release every audioType variant of it.
        val outputKeys = synchronized(outputLock) { outputOwners.values.filter { it.key.session === session && it.key.type == type }.map { it.key } }
        val tornDown = (streams.keys.filter { it.session === session && it.type == type } + outputKeys).distinct()
        tornDown.forEach { key ->
            if (isScreenStreamType(type) || type in 100..102) retireOutput(key)
            else { streams.remove(key)?.let(::safeClose); audioCaptures.remove(key)?.let(::safeClose) }
        }
        if (isScreenStreamType(type) && tornDown.isEmpty() && synchronized(outputLock) { !outputOwners.containsKey(OutputKey(type)) })
            sink.onScreenStreamActive(type, false)
    }

    override fun onSessionClosed(session: AirPlaySession) {
        knownSessions.remove(session)
        clearPendingIapTunnel(session)
        retireBuffered(session)
        videoSettingsChannels.remove(session)?.close()
        val sessionStreams = streams.keys.filter {
            it.session === session && it.type != BufferedAudioStream.STREAM_TYPE
        }
        val ownerKeys = synchronized(outputLock) { outputOwners.values.filter { it.key.session === session }.map { it.key } }
        (sessionStreams + ownerKeys + audioMeta.keys.filter { it.session === session }).map { it.type }.distinct().forEach { onTeardown(session, it) }
        audioCaptures.keys.filter { it.session === session }.forEach { audioCaptures.remove(it)?.close() }
    }

    /** Closes one attempt's media without instantiating an unused microphone scheduler. */
    override fun close() {
        val snapshot: List<AirPlaySession>
        val scheduler: ScheduledThreadPoolExecutor?
        synchronized(lifecycleLock) {
            if (!engineClosed.compareAndSet(false, true)) return
            iapTunnelHandler = null
            snapshot = (knownSessions.toList() + streams.keys.map { it.session } +
                audioMeta.keys.map { it.session } + pendingMicrophone.keys.map { it.session } +
                pendingIapTunnels.keys + videoSettingsChannels.keys + bufferedStreams.keys).distinct()
            scheduler = if (microphoneSchedulerLazy.isInitialized()) microphoneSchedulerLazy.value else null
        }
        // Cancellation and sink callbacks may reenter the engine; never hold lifecycleLock here.
        scheduler?.shutdownNow()
        snapshot.forEach { session ->
            try { onSessionClosed(session) } catch (error: Exception) { Log.w(TAG, "Media cleanup callback failed", error) }
        }
        // A failing sink callback must not prevent closing the underlying sockets.
        streams.entries.toList().forEach { if (streams.remove(it.key, it.value)) safeClose(it.value) }
        pendingIapTunnels.entries.toList().forEach { if (pendingIapTunnels.remove(it.key, it.value)) safeClose(it.value.bridge) }
        videoSettingsChannels.entries.toList().forEach { if (videoSettingsChannels.remove(it.key, it.value)) safeClose(it.value) }
        bufferedStreams.entries.toList().forEach { if (bufferedStreams.remove(it.key, it.value)) safeClose(it.value.stream) }
        audioCaptures.entries.toList().forEach { if (audioCaptures.remove(it.key, it.value)) safeClose(it.value) }
        audioMeta.clear()
        synchronized(pendingMicrophone) { pendingMicrophone.clear() }
        knownSessions.clear()
    }

    /** Removes ownership before a close that may block on delivery or invoke the sink. */
    private fun detachBufferedLocked(session: AirPlaySession): BufferedStream? {
        val previous = bufferedStreams.remove(session)
        previous?.let { streams.remove(StreamKey(session, BufferedAudioStream.STREAM_TYPE, "media"), it.stream) }
        if (bufferedOwner === session) bufferedOwner = null
        return previous
    }

    private fun retireBuffered(session: AirPlaySession) {
        var pending: BufferedStream? = null
        var retirement: BufferedTransition? = null
        val previous = synchronized(bufferedOwnershipLock) {
            bufferedTransition?.takeIf { it.session === session }?.let {
                it.cancelled = true
                pending = it.candidate
                // The SETUP thread still owns the barrier while it closes the earlier owner.
            }
            detachBufferedLocked(session).also {
                if (it != null && bufferedTransition == null) {
                    retirement = BufferedTransition(session, null).also { barrier -> barrier.retiring = it }
                    bufferedTransition = retirement
                }
            }
        }
        try {
            pending?.stream?.close()
            previous?.stream?.close()
        } finally {
            synchronized(bufferedOwnershipLock) {
                if (retirement != null && bufferedTransition === retirement &&
                    previous?.stream?.outputCleanupComplete == true) bufferedTransition = null
            }
        }
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (!microphoneEnabled || type != STREAM_TYPE_MAIN_AUDIO) return null
        if (!wantsMicrophone(format.audioType, stream)) return null
        val port = (stream["dataPort"] as? Number)?.toInt() ?: return null
        if (port !in 1..65535) return null
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / format.sampleRate).toInt().coerceIn(5, 60)
        } else {
            20
        }
        val opusBitrate = when {
            formatBits and OPUS_48K != 0L -> 96_000
            formatBits and OPUS_24K != 0L -> 64_000
            else -> 48_000
        }
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
            opusClockRate = MicrophoneConfig.opusClockRate(formatBits),
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    internal companion object {
        /**
         * Calls and Siri always record. App audio ("default", e.g. a WhatsApp voice note) also carries
         * guidance prompts, so it records only when the iPhone asks for input: an explicit `input` flag,
         * or, without one, the uplink port the iPhone sends for a recording stream.
         */
        fun wantsMicrophone(audioType: String, stream: Map<String, Any?>): Boolean = when (audioType) {
            "telephony", "speechrecognition" -> true
            "default", "compatibility" -> when (val input = stream["input"]) {
                is Boolean -> input
                is Number -> input.toInt() != 0
                else -> (stream["dataPort"] as? Number)?.toInt()?.let { it in 1..65535 } == true
            }
            else -> false
        }

        private const val TAG = "xcertplay-usb"
        private const val MICROPHONE_SCHEDULER_IDLE_SECONDS = 5L
        private const val STREAM_TYPE_MAIN_SCREEN = 110
        private const val STREAM_TYPE_ALT_SCREEN = 111
        private const val STREAM_TYPE_MAIN_AUDIO = 100
        private const val STREAM_TYPE_DATA = 130
        private const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        private const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        private const val VIDEO_SETTINGS_STREAM_ID = 2L
        private const val FIRST_REMOTE_CONTROL_STREAM_ID = 3L
        private const val OPUS_24K = 0x20000000L
        private const val OPUS_48K = 0x40000000L
    }
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> unsignedLong(value).toString()
    is Int -> (value.toLong() and 0xffffffffL).toString()
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let { unsignedLong(it).toString() }
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) unsignedLong(value) else value
    is Int -> if (value < 0) BigInteger.valueOf(value.toLong() and 0xffffffffL) else value
    else -> value ?: 0L
}

private fun unsignedLong(value: Long): BigInteger =
    if (value >= 0) BigInteger.valueOf(value) else BigInteger.valueOf(value and Long.MAX_VALUE).setBit(63)

