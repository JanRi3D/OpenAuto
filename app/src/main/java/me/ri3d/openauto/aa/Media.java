package me.ri3d.openauto.aa;

/**
 * What the protocol layer needs from the device: a video sink, PCM sinks and a microphone source.
 * Implemented with MediaCodec/AudioTrack/AudioRecord on Android and with stubs in JVM tests.
 * All calls arrive on the session's reader thread and must not block for long.
 */
public interface Media {
    interface VideoOut {
        /** How a sink that queues its input reports back to the video channel; called from any thread. */
        interface Feedback {
            /** One unit handed over by write() has been taken by the decoder, or discarded: acknowledge it. */
            void consumed();
            /** Decoding cannot continue without a new keyframe. */
            void needKeyframe();
        }

        /** Prepare for a stream of the given size. False makes the head unit answer SETUP with FAIL. */
        boolean open(int width, int height, int fps);
        /** One H.264 access unit (Annex B); {@code ptsUs} is the phone's timestamp, 0 when absent. */
        void write(byte[] data, int off, int len, long ptsUs);
        void close();
        /**
         * A sink that returns true calls {@link Feedback#consumed} exactly once per write() itself,
         * when the unit has really been consumed. Otherwise the channel acknowledges right after write().
         */
        default boolean feedback(Feedback f) {
            return false;
        }
    }

    interface AudioOut {
        boolean open(int sampleRate, int channels);
        void write(byte[] data, int off, int len);
        void stop();
        void close();
        /** 1.0 normal, lower while guidance or an assistant has focus. */
        void setDucked(boolean ducked);
    }

    interface MicIn {
        interface Listener {
            void onMicData(byte[] pcm, int len, long timestampUs);
        }
        boolean open(int sampleRate, Listener listener);
        void close();
    }

    VideoOut video();
    AudioOut audio(int channelId);
    MicIn mic();
}
