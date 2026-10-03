# Test results

Evidence is kept in four separate classes. Nothing in one class implies another.

## 1. Build and static checks

| Check | Result | Command |
|---|---|---|
| Debug APK, minSdk 16, AGP 9.3.3 / Gradle 9.5.0 / JBR 21 | builds; `aapt2 dump badging` shows `minSdkVersion:'16'` | `gradlew :app:assembleDebug` |
| Android Lint (NewApi, MissingPermission fatal) | 0 errors | `gradlew :app:lintDebug` |
| R8 shrinking, debug and release | debug `classes.dex` 1.45 MiB, debug APK 0.95 MiB, unsigned release APK 0.73 MiB; JNI class and method names kept | `gradlew :app:assembleRelease` |
| Native shim `libsystls.so` (NDK 23.2.8568313, CMake 3.22.1) | builds for armeabi-v7a (8.1 KiB), arm64-v8a (12.3 KiB), x86 (9.4 KiB), x86_64 (11.9 KiB) | part of `assembleDebug` |

## 2. Unit and integration tests (JVM, `gradlew :app:testDebugUnitTest`)

| Test | What it proves | Result |
|---|---|---|
| ProtoCodecTest (5) | varint/string/message/fixed64 encoding against protobuf reference bytes; truncated and oversized input rejected | pass |
| FrameCodecTest (5) | VERSION_REQUEST equals the reference bytes `00 03 00 06 00 01 00 01 00 01`; round trips at any chunking; 16 KiB split; per-frame encryption; orphan/oversized/interleaved frames rejected | pass |
| TlsEngineTest (2) | TLS 1.2 handshake with the head-unit client certificate against a JSSE server; records both ways | pass |
| FakePhoneTest (4) | full startup to PROJECTING, media/audio acks, mic data, binding, sensors, ping, touch, shutdown both ways, version mismatch, truncated frame | pass |
| WifiExchangeTest (3) | RFCOMM credential exchange bytes and error handling | pass |
| ResampleTest (2) | 48 kHz to 16 kHz microphone fallback | pass |
| VideoGeometryTest (3) | margins for the screen's shape (incl. the 1920x720 radio), the video view never leaves the screen, touch in content coordinates | pass |
| UsbKindsTest (3) | the radio's DAB tuner, hubs, storage, HID and audio devices are not phones; MTP, PTP, ADB, tethering and accessory mode are | pass |
| VideoQueueTest (5) | a frame is acknowledged when the decoder takes it, once per message; a decoder that starts late skips to the next keyframe and asks for one (once, not per frame); an overflow gives up everything until the next keyframe instead of dropping one frame | pass |
| H264Test (5) | size and cropping read from a real phone's SPS and an x264 SPS with VUI and emulation prevention; margins written as cropping without disturbing the rest; inexact margins refused; keyframe detection | pass |

42 tests in total, 3 of them opt-in (fake phone server, real phone probe, SPS rewrite of a file for
ffprobe) and skipped by default.

The fake phone is an independent re-implementation of the phone's half (own frame parser, JSSE
TLS). It validates this receiver's behaviour and encodings, not Google's app.

## 3. Emulator results (API 16, x86, 1024x600, 160 dpi, 340 MiB RAM minimum the emulator allows)

Head unit = this app on the emulator; phone = fake phone on the host, reached at 10.0.2.2:5277
(manual TCP mode), streaming a 6 s 800x480 H.264 baseline loop made with ffmpeg.

| Scenario | Result |
|---|---|
| Install and launch | OK; launcher, settings, diagnostics render |
| Layout at 800x480 (`am display-size 800x480`, compact dimension set) | launcher and settings fit, nothing clipped |
| Version exchange, TLS 1.2 handshake (TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256), discovery, 7 channel opens | OK |
| Sensors (driving status, night mode), input binding | OK |
| Video: decoder start, output format reported, frames rendered | OK; 64 s run: 1915 access units in (17.4 MiB), 1914 rendered, 29.7 fps average, 0 dropped, queue never backed up; app CPU ~9% (x86 software decoder, not representative of a radio) |
| Touch mapping | tap 512,300 on 1024x600 arrived as 400,240 in 800x480 |
| Microphone | emulator has no 16 kHz capture (`getMinBufferSize = -2`), fallback rates also fail; FAIL reported to the phone, session continues |
| Close button | phone received shutdown request; state IDLE |
| Surface destroyed (HOME) and recreated | phone received UNFOCUSED then FOCUSED; decoder stopped and restarted |
| 3 consecutive stop/start cycles | each reached PROJECTING and returned to IDLE |
| Process memory (dumpsys meminfo, PSS) | idle 7.0 MiB (Dalvik 1.9, native 2.0); projecting 16.0 MiB (Dalvik 4.3, native 2.0, unknown 2.3); after 3 cycles 16.4 MiB; 25 threads |
| Phone drops mid-session (fake phone closes the socket after 20 s) | "RECONNECTING: connection closed by the phone", retry after 2 s, READY, PROJECTING; projection screen stayed in front; second session ran 64 s at 29.7 fps |
| Repeated microphone activation | not testable: emulator has no capture device at any tried rate |
| Audio interruption and recovery | not tested on emulator (fake phone sends one PCM packet; duck/focus logic exercised in FakePhoneTest only) |

Not measurable on the emulator: hardware decoder behaviour (the emulator decoder is software),
audio audibility, CPU load representative of a radio, USB host, Bluetooth.

### 3a. After the first run on the radio (2026-10-03): 8:3 screen and native TLS

Same API 16 emulator started with `-skin 1280x480` (8:3, the radio's shape), fake phone streaming an
800x480 test pattern whose content area is 800x300 with a border and a square in it. Screenshots taken
on the host (`adb emu screenrecord screenshot`); `screencap` does not capture video layers there.

| Scenario | Result |
|---|---|
| Video output "Compatible" (default): TextureView with a crop transform, margins 0x180 | content area fills 1280x480 exactly, square stays square, 29.8 fps, 0 dropped |
| Touch in that mode | taps at 640,240 / 1279,479 / 320,120 arrived as 400,150 / 799,299 / 200,75 |
| Screen fit "Bars" (no margins) | whole 800x480 frame centred at 1:1, bars left and right, 30.0 fps |
| Video output "Direct": SurfaceView + `VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING` | **ignored by this emulator**: the whole frame is stretched over the screen (bars top and bottom, picture squeezed). Android 4.1 applies that crop only in a hardware composer, not when it composites with the GPU. This is why "Direct" is not the default |
| A SurfaceView smaller than the screen (first attempt at "Bars") | black screen although SurfaceFlinger listed the layer as visible and frames were rendered; not explained; that combination is no longer used by default |
| Microphone open requested by the phone | `AudioRecord.startRecording()` never returned on the emulator and froze the whole session (it ran on the protocol reader thread). Capture is now opened on its own thread; the session continued at 30 fps |
| System OpenSSL self-test at first connection | passes: "OpenSSL 1.0.1c 10 May 2012" |
| Session through the system library | TLSv1.2 ECDHE-RSA-AES256-GCM-SHA384 with the fake phone (JSSE), 29.8 fps |
| Encryption speed (Settings › Diagnostics) | system OpenSSL 43.0 MB/s (8 KiB records, encrypt and decrypt); Java engine AES-GCM 2.59 MB/s, ChaCha20-Poly1305 4.75 MB/s (encrypt only). x86 host CPU: the ratio matters, not the numbers |
| Self-test flagged as crashed (`native_tls_probing` left set) | "system OpenSSL not used", session runs on the Java engine at 30.0 fps |

Android 16 emulator (2992x1344, cutout), same build: TextureView 2829x1344, margins 0x100, 30.2 fps,
tap at 1500,700 arrived as 379,198; the system library is not tried there (Java engine, 0xCCA8).

### 3b. After the second run on the radio (2026-10-03): no dropped frames, keyframe recovery, crop modes

Fake phone now honours `max_unacked` and streams a 60 s clip with a single keyframe, like the real
phone. API 16 emulator 1280x480 unless stated.

| Scenario | Result |
|---|---|
| Flow control | `max_unacked 1` declared; 300 of 301 units rendered at 29.8 fps, 0 discarded. In a 346-frame session on the Android 16 emulator the fake phone had to wait for an acknowledgement 82 times and still delivered 29.9 fps |
| Stream starts before the decoder has a Surface (happened by itself after a cold boot) | 6 units discarded, "asking the phone to restart the stream with a keyframe", phone restarts, 300 frames at 29.9 fps, clean picture |
| HOME, then back to the projection | UNFOCUSED, FOCUSED, stream restarts from its keyframe, clean picture (screenshot) |
| Back key | shutdown request, IDLE |
| Video output Auto, software decoder | TextureView (as in §3a), correct picture |
| Video output Direct (SurfaceView, scaling-mode crop) | Android 16 emulator: correct picture and touch (379,198), 29.9 fps. API 16 emulator: stretched (§3a) |
| Video output Direct 2 (SurfaceView, margins written into the SPS) | Decoders accept it: API 16 reports `crop-top=90, crop-bottom=389`, ffmpeg decodes the rewritten streams as 800x300 and 800x380. But API 16 shows a software decoder's output uncropped, and the Android 16 emulator's decoder applies the size without the top offset (picture shifted down by the margin). Hence not the default |
| SurfaceView smaller than the screen (Bars with Direct) | still black on the API 16 emulator, unexplained (§3a); Auto does not use it there |

## 4. Physical radio and phone results

### 4a. Real phone, head unit side still emulated (2026-10-03)

Phone: Samsung SM-S948B running the Android Auto app with "Start head unit server" enabled, reached
over the LAN on TCP 5277 (the manual Wi-Fi mode). Head unit side: (1) the production `Session` run
from the build PC by `RealPhoneProbeTest` with full message tracing, (2) the app on the API 16
emulator. No physical radio was involved.

| Scenario | Result |
|---|---|
| Version exchange | phone answers protocol 1.7, status MATCH, to the 1.1 request |
| TLS 1.2 with the shipped head-unit certificate | accepted; TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 |
| Service discovery, 7 channel opens, sensor start (driving status, night), input binding, video setup/focus/start | OK |
| Video content | a captured frame decodes to the genuine Android Auto dashboard at 800x480, H.264 Baseline level 3.1 |
| App on API 16 emulator, 30 fps selected | 982 units in, 980 rendered, 30.1 fps average, 0 dropped, ~7% CPU, PSS ~15.5 MiB |
| App on API 16 emulator, 60 fps selected | 2279 units in, 2278 rendered, 52.7 fps average, 0 dropped, ~9% CPU |
| Media audio | 48 kHz stereo PCM received and played (5.9 MiB in 30 s, 0 chunks dropped) |
| Pings | 8 of 8 answered once the request carries a timestamp |
| Stop from the head unit | SHUTDOWN_REQUEST answered by SHUTDOWN_RESPONSE; state IDLE |
| Touch | tap sent while projecting; the phone-side effect was not observed (nobody watching the phone) |

Defects found and fixed in this round, each now covered by a test:

1. The phone interleaves channels: audio frames arrive between the fragments of a large video
   message. The assembler followed aasdk and rejected that ("frame for channel 4 while assembling
   channel 3"). It now reassembles per channel (`interleavedChannelsAreReassembledIndependently`).
2. The phone ignores a ping without a timestamp; the session then killed itself after 15 s. Pings now
   carry a timestamp and liveness is judged by any received data.
3. The frame-rate enum is the reverse of aasdk's naming: value 1 gives ~60 fps, value 2 gives 30 fps.
4. A phone that accepts the TCP connection but never answers left the app waiting forever. Each
   startup phase now times out after 10 s with an actionable message (`silentPhoneTimesOutWithAnActionableReason`).

Observed but not explained: after one aborted attempt (a raw probe that closed right after the
version exchange) the phone's server accepted connections but stayed silent for several minutes; and
one attempt was reset by the phone right after the channel opens. Neither has recurred since.

Second round, same phone, head unit side on an Android 16 emulator (2992x1344, camera cutout,
gesture navigation), after the app crashed on touch and looked wrongly sized on a phone:

| Scenario | Before | After |
|---|---|---|
| Touching the projection | `NetworkOnMainThreadException` (touch written to the socket on the UI thread; Android 4.1 tolerated it) | all UI-originated sends go through a sender thread; taps and swipes work |
| Picture shape on a 20:9 screen | 800x480 stretched to full screen | margins 0x100 negotiated, content fills the screen undistorted, 30.2 fps |
| Touch position with margins | not applicable | tap on the app-grid button opened the app grid on the phone |
| Launcher and settings | ran under the navigation bar and the camera cutout | padded by system-bar and cutout insets |
| Projection chrome | close button covered the phone's status icons; navigation bar visible | immersive; nothing of ours over the video |
| Microphone permission not granted (Android 6+) | no mic channel declared, phone closed the connection after discovery | channel always declared; permission requested when projection opens |
| Android Auto's own Exit entry | ignored (always answered "focused") | returns to the launcher, session kept; tile shows "Connected, tap to return" |
| Back key or gesture | closed the screen but left the session running | disconnects (or goes to the phone if set so in Settings › Input) |

The same build on the API 16 emulator (1024x600): margins 0x12, 30.1 fps, taps, Back to idle.

### 4b. Physical radio, first run (2026-10-03)

Device, from `tools/collect-radio-logs.ps1` output: ADAYO AC822X, AutoChips AC8317 (4x Cortex-A7,
1.0 GHz, armeabi-v7a), Android 4.2.2 (API 17), 829 MiB RAM, 1920x720 at 240 dpi with an 88 px status
bar. H.264 decoders in the order the device lists them: `OMX.google.h264.decoder` (software),
`OMX.mtk.video.decoder.avc`, `OMX.mtkwfd.video.decoder.avc`. Build tested: commit d103e61.

The app installed, connected to the real phone and projected. Three defects showed up in use and
are visible in the logs:

| Symptom | Observed in the logs | Cause | Change |
|---|---|---|---|
| Very laggy | 1503 video units in, 1500 rendered, **14.4 fps average**, 0 dropped, decoder queue empty; thread `aa-reader` at 24% of four cores (one core saturated), decoder thread idle | Frames arrive late, they are not decoded late: the reader thread is CPU-bound. It does the TLS record decryption in pure Java under Dalvik. Inferred, not yet measured on the radio; the emulator shows a 17x gap between the two engines | TLS through the device's own OpenSSL (§3a); hardware decoder selected by name |
| USB shows a device although no phone is connected | USB device 16c0:05dc "DAB USB Dongle", class ff, interface ff/ff/ff | Every non-hub device counted as a phone | Only devices with an MTP, PTP, ADB or tethering interface, or already in accessory mode, count |
| Picture stretched, black bars at top and bottom | SurfaceView layer at (0,-215) size 1920x1152; hardware composer entry with source crop 0,0,800,480 and frame 0,-215,1920,937; `screencap` looks correct, the panel does not | The oversized, partly off-screen video layer is squeezed onto the screen by this composer instead of being clipped | The video view never leaves the screen; the crop is done by a TextureView transform (§3a) |

### 4c. Physical radio, second run (2026-10-03, build 01d54ba, phone on USB in accessory mode)

| Checked | Result |
|---|---|
| Lag | Fixed at the source: units now arrive at 30 per second (554 in 18.5 s, up to 462 KiB/s) and `aa-reader` uses 4% where it used 24% for a tenth of the data. Consistent with the system-OpenSSL engine being in use; its log line is outside the captured window |
| Picture shape | Fills 1920x720 undistorted (screenshots) |
| Wired mode | Works: phone re-enumerated as 18d1:2d00, session ran. First use of USB on a real device |
| Hardware decoder | `OMX.mtk.video.decoder.avc` is used and decodes |

Three new defects showed up in use and are visible in the logs:

| Symptom | Observed | Cause | Change |
|---|---|---|---|
| Heavy picture artifacts | `in 5916 units, dropped 2607, rendered 3300 (16.6 fps avg)`: 30 units/s in, 16.6 frames/s out, 13 units/s dropped; UI thread at 15% of four cores, decoder thread idle; screenshot full of decode errors | The TextureView path renders only 16.6 fps on this device (MTK decoder + Mali-400), and the decoder queue dropped the surplus coded frames. The phone sends one keyframe per stream (verified in a capture: 1 IDR, 2320 P), so every dropped frame damages all later pictures | No coded frame is dropped any more: frames are acknowledged when the decoder takes them, which makes the phone follow the decoder's pace (`max_unacked` = 1); an overflow or a late start discards up to the next keyframe and asks the phone for one. Video output "Auto" uses the SurfaceView path on old Android with a hardware decoder |
| Sound but no picture on USB | not in the captured window | Same root: when the stream starts before the decoder has a Surface, the only keyframe goes by unseen. Likely on USB, where Android's dialogs and the relaunch of the launcher delay the Surface | Same change; reproduced and fixed on the emulator (§3b) |
| First USB attempt waits for USB permission; closing and trying again works | permission for the accessory device is present afterwards | After the switch to accessory mode Android asks "open with this app?" itself (manifest device filter) and grants the permission with the answer; the app asked a second time and waited for a broadcast that did not come | The permission state is polled; the app asks only when no system dialog has taken the window focus. Not testable on an emulator (no USB host) |

**Not yet run on the radio:** all changes in this section. The SurfaceView path with the scaling-mode
crop ("Direct") in particular is expected, not known, to be right on this radio's hardware composer;
if the picture is squeezed there, "Direct 2" and "Compatible" are the alternatives (README, Settings).

Still unexercised on the radio: Bluetooth/hotspot (automatic wireless), the microphone.
