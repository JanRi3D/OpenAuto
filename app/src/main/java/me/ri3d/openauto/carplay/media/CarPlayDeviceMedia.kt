package me.ri3d.openauto.carplay.media

import android.content.Context
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MicrophoneUplink
import com.shilapi.xcertplay.media.SoftwareOpusEncoder
import me.ri3d.openauto.Perms
import me.ri3d.openauto.aa.Media
import me.ri3d.openauto.media.VideoDecoder
import me.ri3d.openauto.settings.Prefs
import java.io.Closeable

/** Device endpoints for one CarPlay attempt; the manager owns this adapter until disconnect. */
class CarPlayDeviceMedia(ctx: Context, private val prefs: Prefs) : MediaSink, Closeable {
    private val context = ctx.applicationContext
    /** Video state. Audio has its own lock, so a decoder restart never holds up sound. */
    private val lock = Any()
    /** Outputs and microphone. Never held while an old owner's thread is joined. */
    private val audioLock = Any()
    @Volatile private var closed = false
    @Volatile var diagnostic: (String)->Unit = {}
    @Volatile var onFirstFrame: ()->Unit = {}
    val video = VideoDecoder()
    /** Pure Java encoder initialization is checked before advertising Opus microphone support. */
    val microphoneOpusSupported: Boolean = SoftwareOpusEncoder(32000).let { val ready=it.available; it.close(); ready }
    val bufferedAudioSupported = false
    private var width=800
    private var height=480
    private var fps=30
    private var videoConfiguration: AvcFraming.Configuration? = null
    private var videoCodec = VideoCodec.H264
    @Volatile private var recovery: (() -> Unit)? = null
    @Volatile private var videoDiagnostic: ((String)->Unit)? = null
    /** Set on the UI thread, sent by the monitor thread: the request is a network write. */
    @Volatile private var keyframeWanted = false
    private val frameGeneration=FrameGenerationTracker()
    private val outputs=LinkedHashMap<AudioStreamId,CarPlayAudioOutput>()
    private var microphone: MicrophoneUplink? = null
    private var microphoneId: AudioStreamId? = null
    private val monitor: Thread

    init {
        video.preferSoftware=prefs.decoder()=="sw"
        video.feedback(object : Media.VideoOut.Feedback {
            override fun consumed() {}
            override fun needKeyframe() { if (!closed) runCatching { recovery?.invoke() } }
        })
        monitor=Thread({
            var lastError: String?=null
            try {
                while (!closed) {
                    synchronized(lock) {
                        if (!closed && frameGeneration.consume(video.framesRendered)) {
                            report("first frame rendered")
                            runCatching { videoDiagnostic?.invoke("first frame rendered") }
                            runCatching { onFirstFrame() }
                        }
                    }
                    if (keyframeWanted) { keyframeWanted=false; runCatching { recovery?.invoke() } }
                    val error=video.error()
                    if (error != null && error != lastError) { report(error); runCatching { recovery?.invoke() } }
                    lastError=error
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) { }
        },"carplay-video-monitor").apply { isDaemon=true; start() }
    }

    fun setSurface(surface: Surface?) = synchronized(lock) {
        if (closed) return@synchronized
        video.setSurface(surface,VideoDecoder.CROP_NONE)
        if (surface != null && videoConfiguration != null) {
            writeConfig()
            keyframeWanted=true
        }
    }

    fun setDisplay(width: Int,height: Int,fps: Int) = synchronized(lock) {
        require(width in 16..1920 && height in 16..1080 && fps in 1..60)
        if (closed || (this.width==width && this.height==height && this.fps==fps)) return@synchronized
        this.width=width; this.height=height; this.fps=fps
        if (videoConfiguration != null) {
            if (restartVideo()) runCatching { recovery?.invoke() }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) = synchronized(lock) {
        if (!closed && type==110) {
            videoCodec=codec
            if (codec != VideoCodec.H264) { frameGeneration.end(); videoConfiguration=null; video.close(); report("Unsupported CarPlay video codec") }
        }
    }
    override fun setVideoRecoveryHandler(type: Int,handler: ()->Unit) { if (type==110 && !closed) recovery=handler }
    override fun setVideoDiagnosticHandler(type: Int,handler: (String)->Unit) { if (type==110 && !closed) videoDiagnostic=handler }

    override fun onVideoConfig(type: Int,codecData: ByteArray) = synchronized(lock) {
        if (closed || type!=110 || videoCodec!=VideoCodec.H264) return@synchronized
        try {
            val configuration=AvcFraming.parseConfiguration(codecData)
            videoConfiguration=configuration
            restartVideo()
        } catch (_: IllegalArgumentException) {
            frameGeneration.end()
            videoConfiguration=null
            video.close()
            report("Rejected malformed H.264 configuration")
            runCatching { recovery?.invoke() }
        }
    }

    override fun onVideoFrame(type: Int,naluBytes: ByteArray) = onVideoFrame(type,naluBytes,0,0)
    override fun onVideoFrame(type: Int,naluBytes: ByteArray,senderNanos: Long,arrivalNanos: Long) = synchronized(lock) {
        if (closed || type!=110) return@synchronized
        if (videoConfiguration==null) return@synchronized
        try {
            // ScreenStream already converted negotiated wire NAL lengths to Annex B.
            val frame=AvcFraming.sinkAnnexB(naluBytes)
            video.write(frame,0,frame.size,if(senderNanos>0) senderNanos/1000 else 0)
        } catch (_: IllegalArgumentException) {
            report("Rejected malformed H.264 access unit")
            runCatching { recovery?.invoke() }
        }
    }
    private fun writeConfig() { videoConfiguration?.annexB?.let { video.write(it,0,it.size,0) } }

    /** Called under lock: old codec output is drained/stopped before taking the next baseline. */
    private fun restartVideo(): Boolean {
        frameGeneration.end()
        video.close()
        val baseline=video.framesRendered
        if (!video.open(width,height,fps)) {
            report(video.error() ?: "video decoder unavailable")
            return false
        }
        frameGeneration.begin(baseline)
        writeConfig()
        return true
    }

    override fun onScreenStreamActive(type: Int,active: Boolean) = synchronized(lock) {
        if (!closed && type==110) {
            frameGeneration.end()
            videoConfiguration=null
            if (!active) { recovery=null; videoDiagnostic=null }
            video.close()
        }
    }

    override fun onAudioStarted(id: AudioStreamId,format: AudioFormat,firstSample: Int) {
        if (closed) return
        if (format.channels !in 1..2 || format.sampleRate !in 8000..96000 || format.rtpClockRate !in 8000..96000 || format.payloadType !in 0..127) {
            report("Rejected unsupported audio format")
            return
        }
        val previous=synchronized(audioLock) { outputs.remove(id) }
        previous?.close() // joins its thread: outside the lock, so the other streams keep playing
        synchronized(audioLock) {
            if (closed) return
            if (previous != null && !previous.released) {
                outputs[id]=previous
                report("Audio replacement waits for previous device owner")
                return
            }
            val enabled=if (format.audioType=="media" || format.audioType=="default" || format.audioType=="compatibility") prefs.audioMedia() else prefs.audioSpeech()
            if (!enabled || outputs.size>=4) return
            outputs[id]=CarPlayAudioOutput(format,::report)
        }
    }

    override fun onAudioRtp(id: AudioStreamId,format: AudioFormat,rtp: ByteArray,sample: Int) {
        if (closed) return
        val output=synchronized(audioLock) { outputs[id] } ?: return
        if (format!=output.format) { report("Rejected changed audio format without SETUP"); return }
        try { output.submit(rtp,sample) } catch (_: IllegalArgumentException) { report("Rejected malformed audio RTP packet") }
    }

    override fun onAudioStopped(id: AudioStreamId) {
        val output=synchronized(audioLock) { outputs[id] }
        output?.close()
        val microphoneStream=synchronized(audioLock) {
            if (output != null && output.released && outputs[id]===output) outputs.remove(id)
            microphoneId==id
        }
        if (microphoneStream) stopMicrophone()
    }

    override fun onMicrophoneStarted(id: AudioStreamId,config: MicrophoneConfig) {
        if (closed || !prefs.mic() || !Perms.hasMic(context)) { report("Microphone disabled or permission missing"); return }
        if (!stopMicrophone()) { report("Microphone replacement waits for previous device owner"); return }
        synchronized(audioLock) {
            if (closed) return
            try {
                val next=MicrophoneUplink(config,::report)
                next.start()
                microphone=next
                microphoneId=id
            } catch (_: IllegalArgumentException) { report("Unsupported microphone format") }
        }
    }
    override fun onMicrophoneStopped(id: AudioStreamId) { if (synchronized(audioLock) { microphoneId==id }) stopMicrophone() }
    /** Closes the microphone outside the lock; false while its recorder is still held. */
    private fun stopMicrophone(): Boolean {
        val current=synchronized(audioLock) { microphone } ?: return true
        current.close()
        synchronized(audioLock) {
            if (!current.released) return false
            if (microphone===current) { microphone=null; microphoneId=null }
        }
        return true
    }

    private fun report(message: String) { if (!closed) runCatching { diagnostic(message) } }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed=true
            frameGeneration.end()
            recovery=null; videoDiagnostic=null
            monitor.interrupt()
            videoConfiguration=null
            video.close()
            video.setSurface(null,VideoDecoder.CROP_NONE)
        }
        // closed is set first: no output or microphone is added after this snapshot.
        val audio=synchronized(audioLock) { outputs.values.toList().also { outputs.clear() } }
        audio.forEach { it.close() }
        stopMicrophone()
        if (Thread.currentThread() !== monitor) runCatching { monitor.join(1000) }
    }
}
