package me.ri3d.openauto.media;

import android.content.Context;

import me.ri3d.openauto.aa.Media;
import me.ri3d.openauto.aa.Wire;

/** The device-side media endpoints for one session. */
public final class AndroidMedia implements Media {
    public final VideoDecoder video = new VideoDecoder();
    public final AudioOutput mediaAudio = new AudioOutput("media");
    public final AudioOutput speechAudio = new AudioOutput("speech");
    public final AudioOutput systemAudio = new AudioOutput("system");
    public final MicInput mic;

    public AndroidMedia(Context ctx) {
        mic = new MicInput(ctx);
    }

    @Override
    public VideoOut video() {
        return video;
    }

    @Override
    public AudioOut audio(int channelId) {
        switch (channelId) {
            case Wire.CH_MEDIA_AUDIO: return mediaAudio;
            case Wire.CH_SPEECH_AUDIO: return speechAudio;
            default: return systemAudio;
        }
    }

    @Override
    public MicIn mic() {
        return mic;
    }

    public void release() {
        video.close();
        mediaAudio.close();
        speechAudio.close();
        systemAudio.close();
        mic.close();
    }
}
