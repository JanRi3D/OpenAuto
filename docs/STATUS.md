# Feature status

Legend: **done** = implemented and verified as stated in Evidence; **built** = implemented, not yet
verified against a real phone; **partial**; **blocked** (reason); **planned**.

"Fake phone" = `FakePhone` in `app/src/test`, a scripted peer written independently of the production
framing code with a JSSE TLS server. It proves this side of the protocol, not Google's behaviour.

| Area | Status | Evidence |
|---|---|---|
| Type B launcher UI | done | Screenshot on API 16 emulator 1024x600 matches reference layout |
| Settings (sidebar + panel, 6 categories + Diagnostics) | done | API 16 emulator |
| Diagnostics screen (+ connection log) | done | Values read from platform APIs on API 16 emulator |
| Decoder capability probe (gates resolution choices) | done | Emulator software decoder accepts 480p/720p/1080p |
| Protocol framing + protobuf codec | done | JVM tests: round trips, splitting, encryption per frame, malformed/truncated rejection |
| TLS 1.2 client with client certificate (BouncyCastle) | done vs real phone | Real phone accepted the certificate (ECDHE-RSA-AES128-GCM) from the API 16 emulator; JVM interop test vs JSSE |
| Fast TLS through the device's own OpenSSL (Android 4.1–5.1, JNI shim, self-test, automatic fallback to BouncyCastle) | done vs fake phone on emulator; on the radio with a real phone the reader thread fell from 24% to 4% CPU at ten times the data | API 16 emulator: OpenSSL 1.0.1c, ECDHE-RSA-AES256-GCM-SHA384, 30 fps; 43 MB/s vs 2.6 MB/s for the Java engine (TEST-RESULTS §3a, §4c) |
| Control channel (version, auth, discovery, ping, audio/navigation focus, shutdown both ways) | done vs fake phone | JVM FakePhoneTest; API 16 emulator session log |
| Manual TCP transport (phone head-unit server, port 5277) | done vs real phone | API 16 emulator projected a Samsung SM-S948B over the LAN (TEST-RESULTS §4a) |
| USB AOA transport (host role, permission, AOA strings, re-enumeration, detach) | works on the radio on the second attempt; first-attempt permission wait fixed, not yet re-run | TEST-RESULTS §4c. No USB host on the emulator |
| USB phone detection (only devices with an Android-style interface are offered; a radio's own USB devices are ignored) | done (unit test with the radio's descriptors) | The radio's internal DAB tuner (16c0:05dc) was offered as a phone before; `UsbKindsTest` |
| Video (MediaCodec to Surface, SPS/PPS as codec config) | done vs real phone | API 16 emulator: 980 of 982 real Android Auto units rendered at 30.1 fps, 0 dropped |
| Video flow control and keyframe recovery (acknowledge on decode, never drop a coded frame, ask for a keyframe after a late start or overflow) | done vs fake phone; not yet run against a real phone | A real radio showed heavy artifacts and audio-only sessions without it (TEST-RESULTS §4c); `VideoQueueTest`, emulator §3b |
| Hardware decoder chosen by name, software as fallback or by setting | done on the radio | `OMX.mtk.video.decoder.avc` in use (TEST-RESULTS §4c); the radio lists its software decoder first |
| Touch input (single pointer, content coordinates, sent off the UI thread) | done vs real phone | Android 16 emulator: tap opened the phone's app grid; mapping with margins measured (protocol-reference §14.15) |
| Media / speech / system audio (AudioTrack) | media done vs real phone | 48 kHz stereo music played on the emulator, 0 chunks dropped; guidance/assistant audio not exercised |
| Microphone (channel always declared; AudioRecord 16 kHz, resampled when needed) | declared: done vs real phone; capture: built | The phone drops a head unit without a mic channel; capture itself not exercised (no assistant use during tests) |
| Clean stop from the head unit (shutdown request/response) | done vs real phone | Back key: SHUTDOWN_REQUEST answered, state IDLE |
| Aspect-correct video on any landscape screen (video margins; cropped by a TextureView transform, by the codec's scaling mode, or through the stream's SPS; chosen by "Video output") | TextureView: done on emulators and on the radio (right shape, but 16.6 fps there). SurfaceView + scaling mode: done on the Android 16 emulator, expected on the radio, not yet run there | No single method is honoured everywhere (TEST-RESULTS §3a, §3b, §4b, §4c) |
| Navigation status channel (next turn: status, road, maneuver, Maps' PNG arrow, distance), passed on as the sticky broadcast `me.ri3d.openauto.NAV` for OpenDashboard | done vs real phone (stationary) | Samsung SM-S948B over Wi-Fi on the API 16 emulator: channel opened, status, turn with a 256 x 256 PNG arrow and a distance event arrived, no other message types; countdown while driving not yet seen. JVM FakePhoneTest. Layouts from aasdk/openauto (openDsh fork) |
| Android Auto's Exit entry (native video focus request) | done vs real phone | Returns to the launcher, session kept, tile returns to projection |
| Edge-to-edge insets and immersive projection on modern Android | done | Android 16 emulator screenshots |
| System bars setting (keep the notch clear, status bar only, full screen, normal) on every screen | done on Android 16 and 4.1 emulators; the Android 4.4–10 path is unverified | Android 4.1–4.3 can only hide the status bar |
| Minimize button on the start screen (minimize, end the session and close, or hidden) | done | Android 16 and 4.1 emulators |
| Reconnect after unexpected drop (USB/TCP: 3 attempts, 2/4/6 s; wireless: keep hotspot, wait again) | done vs fake phone (TCP) | Emulator: drop -> RECONNECTING -> PROJECTING within ~4 s, screen stays up |
| Memory pressure logging (onTrimMemory) | built | |
| Automatic wireless (BT RFCOMM server + credential exchange + hotspot + TCP 5288) | built, unverified | JVM test of the RFCOMM exchange; emulator has no Bluetooth; HSP trigger impossible from an app (see DESIGN §5) |
| Self Mode (loopback 5277 on Android 9+) | built | Needs a device with the Android Auto app |
| Real phone acceptance, manual Wi-Fi mode | done on emulator | 30.1 fps, audio, pings, clean stop; four defects found and fixed |
| Real phone acceptance on a physical radio | two runs done; speed, picture shape and wired mode confirmed; fixes for video artifacts, audio-only sessions and the USB permission wait not yet confirmed there | TEST-RESULTS §4b, §4c. Automatic wireless and the microphone are still unexercised there |
| Phone calls, steering-wheel keys, boot autostart | not planned | |
