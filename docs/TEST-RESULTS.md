# Test results

Evidence is kept in four separate classes. Nothing in one class implies another.

## CarPlay integration, 2026-10-22

The sections below this one retain the earlier Android Auto and Java diagnostic results. The new
CarPlay receiver adapts DiPlay commit `143f4674b60b564a16baae5110b3b90fb7277631`; it does not
inherit DiPlay's physical test results. Setup, identity provenance and limitations are in
[CARPLAY.md](CARPLAY.md) and [CARPLAY-NOTICES.md](CARPLAY-NOTICES.md).

| Check | Current evidence |
|---|---|
| Full JVM regression run | 86 suites, 485 tests: 482 passed, 3 existing opt-in skips, 0 failures/errors. Includes the existing Android Auto and Java CarPlay tests |
| Debug and release lint | 0 errors, 38 warnings each. API and permission checks remain fatal |
| Debug and signed release APKs | R8 builds pass; version `0.6.0-carplay1`, versionCode 7, minSdk 16, targetSdk 36, ARMv7/ARM64/x86/x86_64 native shims |
| Synthetic AirPlay peer | SRP pair-setup, stored controller pair-verify, encrypted control, synthetic P-256 MFiSAP signature, /info and authenticated SETUP/RECORD pass over real loopback sockets. Unknown/altered keys and unauthenticated stream setup are rejected |
| Stream/media boundaries | Annex B input, malformed avcC rejection, authenticated packets, audio formats including advertised compatibility, touch release, bounded queues, first-frame reconnect and cross-session output ownership regressions pass |
| Transport and cleanup | USBMUX/Lockdown framing and pairing, conservative API 16 descriptor mapping, NCM, saved pair records, cancellation, intentional Bluetooth handoff close and complete-boundary inbound iAP EOF regressions pass |
| Credential/diagnostic boundaries | Credential message bodies are redacted before parsing/raw previews; shared-reference plist graphs do not expand into logs; runtime identity is never replaced by test-generated material |
| Scoped certificate parsing | BC 1.86 empty-issuer Lockdown profile concession is thread-local and restores the previous effective policy, including nested/error cases. Wrong peer public keys remain rejected; another thread keeps strict parsing |
| API 16 shrunk release local check | Selected accessory identity loads, signs and generates MFiSAP response; pairing keys, ChaCha/Ed25519, binary protocol and software Opus checks pass on Dalvik x86 |
| API 16 production USB TLS engine, synthetic peer | Exact packaged release completes TLS 1.2 / ECDHE-RSA-AES128-GCM-SHA256 with mutually pinned synthetic identities, application bytes in both directions and direct socket closure. No USB or iPhone is involved |
| API 16 setup/lifecycle | Activity resumes; UI hierarchy contains controls within 1024x600 bounds. Wireless without a paired phone explains the requirement. USB consent reaches the wait state; Disconnect returns to idle, re-enables connection buttons and removes the projection service, with no AndroidRuntime crash |
| Physical iPhone/radio | **NEEDS VERIFICATION**: no physical iPhone, Bluetooth adapter or USB/NCM device was available for this integration run |

The selected public-preview identity is packaged only through the ignored local asset directory.
It is an authentication input, not proof of Apple certification or acceptance. Synthetic TLS,
pairing and media peers are likewise not iPhones. Emulator x86 execution does not prove sustained
ARMv7 codec speed, audio routing, camera/radio USB behavior or operation within 256 MiB total RAM.

The runtime test caught and corrected issues that the host JVM did not reproduce: reflective
provider classes removed by R8, Dalvik constructor-time random seeding, provider-specific default
entropy lookup, and Android 4.1's legacy RSA certificate algorithm names. The final local provider
canonicalizes only SHA-256/384/512 RSA PKCS#1 signature OIDs with valid absent/DER-NULL parameters;
BC algorithm constraints and the paired public-key pin stay active. The diagnostic's RSA alias
selection also handles the older ECDHE_RSA key-manager request.

Final artifact: `dist/openauto-0.6.0-carplay1-release.apk`, 1,570,028 bytes. `apksigner verify`
passes v1 and v2 signatures; the signer matches the existing 0.5.0 release. There is one
`classes.dex`; minSdk 16 and all four existing ABI shims are retained. The packaging helper was
executed successfully with `-UseDiPlayPreviewIdentity -Variant Release`.

SHA-256:

```text
fc714f4f6b13f172049edbe4758b1422b1e6d88d787eb5a8aa612410287bf285
```

The installed packaged release reported:

```text
Platform API 16
PASS: Accessory identity
PASS: AirPlay encryption and pairing keys
PASS: Binary protocol
PASS: Software microphone codec
LOCAL SYNTHETIC ONLY: TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256; mutual pinned identities; application bytes both ways; direct close passed
PASS: USB TLS loopback (synthetic)
CHECK COMPLETE — local checks only; connect an iPhone for projection verification
```

The emulator's GPU screenshot capture returned black pixels even for Android's home screen.
Setup/lifecycle evidence above comes from actual UI hierarchy, button interactions, activity and
service state, and logcat; this run does not claim a visual screenshot review.

Build command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:OPENAUTO_CARPLAY_ASSETS=Join-Path (Get-Location) 'build\carplay-runtime'
.\gradlew.bat --offline :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

The old default-skip set is unchanged: `FakePhoneServerTest.serve`, `RealPhoneProbeTest.probe`,
and `H264Test.rewriteFile`. Test XML is under `app/build/test-results/testDebugUnitTest`; lint
reports are under `app/build/reports`. The reproducible packaging helper is
`tools/build-carplay.ps1`.

## 1. Build and static checks

| Check | Result | Command |
|---|---|---|
| Debug APK, minSdk 16, AGP 9.3.3 / Gradle 9.5.0 / JBR 21 | builds; `aapt2 dump badging` shows `minSdkVersion:'16'` | `gradlew :app:assembleDebug` |
| Android Lint (NewApi, MissingPermission fatal) | 0 errors; 16 warnings, the same as before the CarPlay work. The transport check's deliberate calls (the adapter address, RFCOMM sockets, `cancelDiscovery` below API 31, the USB answer receiver below API 33) carry narrow suppressions with the reason next to them | `gradlew :app:lintDebug` |
| R8 shrinking, debug and release | debug `classes.dex` 1.45 MiB, debug APK 0.95 MiB, unsigned release APK 0.73 MiB; JNI class and method names kept | `gradlew :app:assembleRelease` |
| Native shim `libsystls.so` (NDK 23.2.8568313, CMake 3.22.1) | builds for armeabi-v7a (8.1 KiB), arm64-v8a (12.3 KiB), x86 (9.4 KiB), x86_64 (11.9 KiB) | part of `assembleDebug` |
| CarPlay debug screens are debug-only (2026-10-13, with the transport check) | debug: `MfiProbeActivity` and `TransportCheckActivity` in the manifest with their own launcher entries, plus `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion` 18 for the report file; 101 `carplay` classes in `classes.dex` (38 of them the transport check, lambdas and inner classes included), which holds 10,852 method references (`method_ids_size`, the count the single-dex limit of 65,536 applies to; 10,040 before the CarPlay work) and 1,517 classes (1,406 before), 1,680,632 B (1,541,644 B before); debug APK 1,072,068 B (1,004,513 B before), packaged from scratch. Release: no `carplay` class, no debug entry, no storage permission, 5,046 method references and 986,992 B `classes.dex` (986,972 B before), because nothing outside the debug screens calls the CarPlay code yet | `aapt2 dump xmltree`, `aapt2 dump badging`, `dexdump -f` |

## 2. Unit and integration tests (JVM, `gradlew :app:testDebugUnitTest`)

| Test | What it proves | Result |
|---|---|---|
| ProtoCodecTest (5) | varint/string/message/fixed64 encoding against protobuf reference bytes; truncated and oversized input rejected | pass |
| FrameCodecTest (5) | VERSION_REQUEST equals the reference bytes `00 03 00 06 00 01 00 01 00 01`; round trips at any chunking; 16 KiB split; per-frame encryption; orphan/oversized/interleaved frames rejected | pass |
| TlsEngineTest (2) | TLS 1.2 handshake with the head-unit client certificate against a JSSE server; records both ways | pass |
| FakePhoneTest (4) | full startup to PROJECTING, media/audio acks, mic data, binding, sensors, ping, touch, shutdown both ways, version mismatch, truncated frame | pass |
| WifiExchangeTest (3) | RFCOMM credential exchange bytes and error handling | pass |
| WirelessTriggerTest (4) | automatic wireless trigger without Bluetooth: "any paired phone" tries every bonded phone in order and nothing else, a chosen device is the only target; failed attempts never end the loop, a phone paired while waiting is tried in the next round, a phone that took the credentials ends its round, the loop stops once the phone is on Wi-Fi and wakes from its 20 s pause when stopped | pass |
| HotspotAddressTest (3) | the phone is sent the hotspot's address: with Wi-Fi client and hotspot running together the station on wlan0 loses to wlan1/ap0, wlan0 counts when it is the only access point, public, IPv6 and non-Wi-Fi addresses never count | pass |
| ResampleTest (2) | 48 kHz to 16 kHz microphone fallback | pass |
| VideoGeometryTest (3) | margins for the screen's shape (incl. the 1920x720 radio), the video view never leaves the screen, touch in content coordinates | pass |
| UsbKindsTest (3) | the radio's DAB tuner, hubs, storage, HID and audio devices are not phones; MTP, PTP, ADB, tethering and accessory mode are | pass |
| VideoQueueTest (5) | a frame is acknowledged when the decoder takes it, once per message; a decoder that starts late skips to the next keyframe and asks for one (once, not per frame); an overflow gives up everything until the next keyframe instead of dropping one frame | pass |
| H264Test (5) | size and cropping read from a real phone's SPS and an x264 SPS with VUI and emulation prevention; margins written as cropping without disturbing the rest; inexact margins refused; keyframe detection | pass |
| LocalMfiAuthenticatorTest (16) | CarPlay accessory identity, synthetic keys from the host JCA: a PKCS#8 P-256 key with a DER certificate, and with a real one-certificate PKCS#7 envelope (the host's own PKCS#7 parser reads it), whose bytes come back unchanged as independent copies within the output limit; explicitly encoded P-256 accepted; P-384, secp256k1 (named and explicit), brainpoolP256r1, RSA, a key of another certificate, empty, two-certificate and non-SignedData envelopes, missing, empty, truncated, oversized (16 KiB + 1), trailing-data and encrypted files each refused with their own reason; only 32-byte digests signed (0, 20, 31, 33, 64 refused); signatures verify with the host's `NONEwithECDSA` and not with `SHA256withECDSA` (no second hash); RFC 6979 A.2.5 known answers; altered digest, key or signature fail; reload and 8 threads give identical signatures | pass |
| CsmMessageTest (8) | iAP2 control session framing against hand-written bytes: 0x4040, a 16-bit length counting the 6-byte header, message ID, parameters whose 16-bit length counts their 4-byte header; parameter order and repeated IDs kept; group parameters nest; the 65,535-byte maximum both ways; wrong start bytes, lengths below the headers, parameters running past the message and leftover bytes refused; the reader reassembles every split point, byte-by-byte and random chunks, and fails at the first wrong byte | pass |
| ControlSessionTest (19) | identification and authentication against a SIMULATED phone (scripted in the test, own byte parser, signatures checked with the host JCA against the key in the certificate it received) and a synthetic identity: identification then authentication and the reverse (provisional) both complete only on this session's IdentificationAccepted plus AuthenticationSucceeded, and the first exchange is recorded; a sent signature is never success; concatenated messages, every split point of every message, byte-by-byte and random chunks; malformed framing; eleven out-of-order or unsolicited verdicts; wrong challenge lengths or parameters; AuthenticationFailed final and never retried; a rejected identification corrected at once; rejections naming nothing, an unsent parameter or a field without a correction end the session; twelve corrections compared by content (reordered repeats, parameters or group parts, unrelated changes, partial corrections refused; changed, dropped or group-part corrections sent); rejected too often; timeouts (also after a signature or a correction without a verdict); link loss, also when complete and while sending; a failure keeps its first reason and clears the progress. Every acceptance is SIMULATED | pass |
| Iap2LinkLayerTest (15) | iAP2 link against packets written and checked by hand: the SYN byte for byte; thirteen SYN+ACKs refused field by field, each as invalid or as unsupported (version, empty or broken session tables, renamed or re-versioned or unproposed sessions, no window, packets too short for data, ZeroACK, zero timeout, acknowledgement timeout not shorter, broken payload); the phone's limits taken beyond the proposal (window 200 capped at 127, 1024-byte packets both ways, acknowledging at the smaller window, zero retransmissions); a 500-byte message split into ten 64-byte packets within a window of two; the message limit apart from the packet limit; the bounded send queue; in-order delivery with cumulative and piggybacked acknowledgements; duplicates and gaps; incoming EAK counted, no EAK ever sent; damaged headers, payloads, lengths and garbage; every split point of a packet stream; go-back retransmission and its limit; 600 packets each way across several wraparounds; detection (echo in pieces, repeats, another answer skipped until the retries run out, no answer, a phone SYN during detection); SYN retries, a lost final ACK, a different SYN+ACK, simultaneous SYN, a resynchronization request and the new link after it; RST, SLP, transport loss, close, a failing transport; data from an earlier link and for another session | pass |
| Iap2ConnectionTest (10) | the Bluetooth profile end to end (detection, link, control session) against a SIMULATED phone whose link and control session are written in the test, over a seeded wire that loses (15 %), repeats (10 %), damages (10 %) and splits packets: a certificate response over several 64-byte packets arrives intact; 20 seeds in both orders complete identification and authentication; the phone's own parser checks every identification field and that nothing else (no CarPlay, External Accessory, HID, USB) is advertised; a rejected configured Name comes back as "OpenAuto" with everything else unchanged and is accepted; rejected mandatory fields (transport component, model, language, message list, a default name) end the session with the field named and close the link; a reset clears the session and earlier or unsolicited verdicts on the new link end it; a silenced link, withheld verdicts, a disconnect halfway through the certificate, a failing transport and a refused authentication each end cleanly. Every acceptance is SIMULATED | pass |
| Iap2StreamTest (5) | the connection over real sockets (TCP loopback standing in for RFCOMM), real threads and the system clock, against the same SIMULATED phone pumped on its own thread: the handshake completes and a stop from another thread ends the link with RST, which the phone receives; completes over a wire that loses, repeats, damages and splits the phone's packets (10 % each) with real retransmission timing; the time limit ends a synchronized link with RST within the limit; the phone closing its socket ends the link as DISCONNECTED with "end of stream" recorded; two messages sent after the handshake are counted and kept with their IDs, unanswered. Completion is latched for the report, since the session clears its state once the link is gone. Every acceptance is SIMULATED | pass |
| BluetoothProfileTest (3) | the profile's IdentificationInformation byte for byte against a hand-written vector; bounds on text (empty, zero character, 63-byte limit counted in UTF-8) and on the Bluetooth address (length, all zero, Android's placeholder); only a configured Name is correctable, each of the twelve other fields fails with its reason, also when rejected together with the Name | pass |
| ReviewRegressionTest (5) | regressions from an independent review: P-256 parameters with cofactor 2 refused for the key, the certificate and both (loaded before the fix); named, explicit and explicit-without-cofactor P-256 still load; a failed session of an earlier link no longer closes the next link during detection, during synchronization, or after a reset (each fails with the fix reverted) | pass |
| IdentificationCorrectionRegressionTest (4) | regression from the same review: groups `(0,"a"),(1,"b")` and `(0,"a, 1=b")` compare as different (they collided as text); order ignored but a repeated part or parameter counted; through the whole rejection path a correction of a rejected Name that also rewrites the unrejected group 17 is refused, the same correction with group 17 only reordered is sent. Synthetic payloads; the Bluetooth profile itself only ever changes the Name | pass |
| ProbeRunnerTest (4, debug unit tests in `src/testDebug`) | the probe's file operations across activity instances, with a real worker thread: runs and a delete of three instances never overlap; a cancelled run (Back, recreation) has returned before the next operation starts; an operation cancelled before it starts never runs; a result whose instance is gone is dropped while a live one is delivered | pass |
| ProbeRecordTest (8, `src/testDebug`) | the probe's saved run record: empty, a malformed Unicode escape, `digest=00`, a digest with one hex digit too many, each of the six fields missing, seven bad process IDs, a non-hex letter, an Arabic-Indic digit, hashes and signatures one byte short or long are each reported as unusable with the reason (the probe then skips the restart check and runs fresh); a valid record, upper or lower case, reloads and all four checks pass; a well-formed record with one changed signature is a failed check, not a skip; a missing fixture and a cancellation still propagate | pass |
| RfcommCheckTest (11, `src/testDebug`) | the RFCOMM check against a fake endpoint that blocks like Android's sockets until closed: an echo split over two reads, an echo after other bytes, other bytes then end of stream (no echo), the 64-byte read cap, a refused connection sends nothing, a SecurityException is an observation; the time limit closes a blocked connect and a blocked read within the limit (the socket observation stays "connected"); Cancel from another thread closes a blocked connect at once and adds no time-limit note; Cancel before the run never opens. A stop at the limit carries the note that it is no evidence against CarPlay | pass |
| HandshakeCheckTest (4, `src/testDebug`) | the debug handshake check's report: against the loopback phone, eight lines in order (socket, link DOWN at the time limit, the negotiated values, link counters, the session with "identification ACCEPTED, authentication SUCCEEDED" and the exchange the phone started, the SYNTHETIC verdict marked as not iPhone acceptance, no messages after the handshake, the time-limit note); a refused connection starts no link and writes nothing; Cancel closes a blocked connect, and a connected peer that never echoes is ended by Cancel within the run with only detection bytes written and no time-limit note; Cancel before the run never opens | pass |
| TransportReportTest (4, `src/testDebug`) | the Bluetooth summary names the first of: no adapter exposed (with "does not prove the device has no Bluetooth hardware"), permission denied, disabled (with the state), adapter address unavailable (none, the 02:00:00:00:00:00 placeholder, not an address), address available | pass |
| UsbDescriptorsTest (5, `src/testDebug`) | raw descriptors: device, configuration, interface, three endpoints, a class-specific and an interface-association descriptor read field by field; a zero length stops parsing instead of looping; a descriptor running past the end and one shorter than its type are shown as bytes, never read past; high-bandwidth endpoint sizes | pass |
| RawEcdsaSignatureTest (4) | fixed DER vectors: sign padding, short integers, the 72-byte maximum; negative, zero, ≥ n and needlessly padded integers, wrong and indefinite lengths, trailing data and wrong structure refused; raw values of 0 or ≥ n and lengths other than 64 refused | pass |

174 tests: 171 pass, 3 are opt-in and skipped by default (`FakePhoneServerTest.serve`,
`RealPhoneProbeTest.probe`, `H264Test.rewriteFile`), 0 fail; 125 of them are the CarPlay tests
(36 of those debug unit tests in `src/testDebug`), the other 49 are the Android Auto tests. This project's AGP creates no release unit-test task.
Each of these deliberate breakages of the CarPlay code made at least one test fail: in the loader and
signer a second hash, no curve comparison, no key match, several certificates allowed, no size limit,
a loose digest length, a shared certificate array, lenient DER, zero values allowed, the cofactor not
checked; in the control session a sent signature counted as success, a challenge or
AuthenticationSucceeded accepted at any time, a retry after AuthenticationFailed, no timeout restart
on progress, handshake messages passed on after completion, a complete session surviving link loss,
one identification attempt too many, unknown messages ignored, link errors swallowed, no start-byte
check, no reader reset, parameters shorter than their header, a retry after a rejection naming
nothing or an unsent parameter, progress kept after a failure, a later failure replacing the first,
repeated parameters or group parts compared in order, groups compared as joined text, unrelated or
partial corrections allowed; in the link no header or payload checksum check, any sequence number
accepted, no retransmission, acknowledgements compared without wraparound, no splitting, the old
one-message-per-packet limit, any SYN+ACK accepted, the phone's window or packet length treated as a
ceiling under the proposal, ZeroACK not recognised, a renamed session accepted, incoming packets
limited to the proposal, acknowledgements ignoring the windows, an uncapped send window, detection
skipped or any answer taken as the echo, a resynchronization request treated as an error,
simultaneous SYN without RST, an EAK list taken as data, data from an earlier link accepted, a
repeated SYN+ACK not acknowledged, endless retransmission, every packet acknowledged at once, no send
window, an unbounded queue; in the connection the old session reused, a failed session keeping its
link, and the last link's failed session closing a new one; in the profile every field correctable,
a CarPlay parameter advertised, text without its terminating zero, an all-zero address accepted; in
the probe runner a result delivered to a destroyed instance, an operation run after its cancellation;
in the probe record a lenient length, the record read outside the recovery boundary, other scripts'
digits taken as hex, a broad catch around the comparison; in the transport check a stop that does not
close the socket, a check that opens after Cancel, the time-limit note after Cancel, the echo looked
for only at the start, a descriptor read past its own length; in the stream a stop that sends no
RST, completion read from the session after its link is gone, a stop that also closes the socket
reported as the peer hanging up.

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
| Audio stalls (`FAKE_PHONE_AUDIO_STALL=400`: media audio stops for 400 ms every 5 s) | Before: a track that ran dry played each packet on arrival. AudioFlinger counted 52 underrun cycles in 40 s when the phone sent the held-back audio afterwards (`FAKE_PHONE_AUDIO_BURST=1`, all within the first 25 s) and 556 when it skipped it. Now a track that runs dry is paused until its 500 ms buffer is full again: 0-9 and 64-96 cycles (one refill every second stall, as each skip takes 400 ms out of the buffer). API 36 AVD: 0 underruns. A single 20 ms packet now plays; before, it waited for a full buffer and never did |

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

### 3c. Synthetic MFi probe (2026-10-12, debug APK SHA-256 `2ba759c9…a6cd50`)

Debug builds have a second launcher entry, "Synthetic MFi probe". It makes throwaway P-256 identities
in `files/carplay-auth-probe`, writes each as a DER certificate and as a one-certificate PKCS#7
envelope, and runs them through the production loader and signer. All of it is synthetic: it shows
the code runs on that runtime, not that an iPhone accepts any identity.

| Runtime | Result |
|---|---|
| API 16 emulator, x86, Dalvik 1.6.0, `generic_x86/google_sdk_x86/generic_x86:4.1.2/MASTER/eng.wdu.20191213.040452:eng/test-keys` | 33 of 33 checks pass in 311 ms: both formats load, certificate bytes unchanged, copies independent, output limit enforced, signatures verify (BouncyCastle verifier with the generator's public key) and fail for an altered digest, digests of 0/20/31/33/64 bytes refused, RFC 6979 known answer matches, five invalid identities refused with the expected reason. Signing 2.2 ms (DER) and 3.2 ms (PKCS#7), mean of 20 |
| Same, "Restart process" | the process was killed and reopened by an alarm (pid 2091 to 2206); both identities reloaded from disk with unchanged certificates and the same deterministic signatures; 37 of 37 |
| Same, "Run again" and "Delete fixtures" | reload in the same process reported as such (37 of 37); deletion leaves `files/` empty. The run record holds certificate hashes, one digest and its signatures, no key material |
| Android 16 emulator, x86_64, `google/sdk_gphone64_x86_64/emu64xa:16/BE4B.251210.005/14574095:user/release-keys` | 33 of 33; signing 10–12 ms (debug build on ART). "Restart process": the system blocks the alarm's activity start (background activity launch); reopened from the launcher, the restart check passes (pid 2974 to 4787, 37 of 37) |
| API 16 emulator, same image, debug APK SHA-256 `6e98b186…5a102f54` (adds the iAP2 control session) | 38 of 38 checks in 879 ms. New section: identification, certificate and signature sent with the phone's messages fed byte by byte, the signature verifies against the generated key, not ready on the signature alone, ready only after the SIMULATED phone's AuthenticationSucceeded, and a SIMULATED AuthenticationFailed ends the session. The phone is scripted inside the probe: a test result, not an iPhone. Signing 7.8–8.1 ms on this cold-booted emulator (2.2–3.2 ms in the first run) |
| API 16 emulator, same image, debug APK SHA-256 `9bcc4364…4eacb332` (adds the iAP2 link layer) | 46 of 46 checks in 367 ms. New section, link and control session against a phone link scripted inside the probe: synchronized on the phone's 48-byte packets and session 9; the certificate response arrived intact over 10 packets; the signature verifies; not ready on the signature alone, ready only after the SIMULATED AuthenticationSucceeded; sequence numbers wrapped past 255 on both sides; three damaged copies and three repeats of phone packets dropped (`corrupt 3, duplicate 3`); RST from the phone ends link and session. A test result, not an iPhone |
| API 16 emulator, same image, debug APK SHA-256 `afe5b19c…0d37527c` (2026-10-13, adds the Bluetooth profile) | 50 of 50 checks in 335 ms. Bluetooth profile against a phone link scripted inside the probe: detection bytes first and the SYN only after their echo; synchronized on the phone's 48-byte packets, window 2, control session 10; the identification carries exactly parameters 0-9, 12, 13 and 17; a rejected configured Name came back as "OpenAuto" with every other parameter unchanged; the certificate response arrived intact over 10 packets; the signature verifies; not identified and authenticated on the signature alone, complete only after the SIMULATED verdicts; sequence numbers wrapped on both sides; three damaged copies and three repeats dropped; RST ends link and session; a rejected BluetoothTransportComponent ends the session with that field named and closes the link. A test result: not iPhone acceptance, and no CarPlay |
| API 16 emulator, same image, debug APK SHA-256 `a1a64b8b…0f81f982` (2026-10-13, probe operations owned and serialized) | 50 of 50 checks. Back right after launch, then reopening (3 times): the first instance's run was cancelled by its owner and stopped at its next step, then the reopened instance's run started and passed. Recreation during a run (`am display-size`, twice): the new instance's run was queued behind the old one, which stopped after its cancellation before the new one started; the new run passed. Two instances alive in one task: the top one's run was queued behind the bottom one's; the top one deleted the fixtures (the directory was gone) while the other stayed alive; after Back the remaining instance regenerated the fixtures and passed. Order and timing from logcat tag `MfiProbeRunner` |
| Android 16 emulator, x86_64 (2026-10-13), two probe instances in freeform windows | A run record left at 0 bytes by an earlier session (the emulator was stopped right after a run, before the write reached the disk) made every run fail at the restart check, and the fixtures were never regenerated. The record is now written to a temporary file, flushed and renamed, and an unreadable record is reported as "restart check skipped" before a fresh run: with the 0-byte record still present the next run passed 50 of 50 and wrote a 639-byte record. Then three times: "Run again" tapped in one instance and "Delete fixtures" in the other while that run was going; the delete was queued behind the run, started only after it finished, and the run passed (54, 50, 50 checks). The emulator's freeform settings were set back afterwards |
| API 16 emulator, debug APK SHA-256 `569d5b04…83cf4dbc` (final for this round) | normal run 54 of 54 (restart check against the earlier process); Back right after launch: the run was cancelled and stopped, then the reopened instance's run passed 50 of 50; "Restart process" queued like any operation, the new process (pid 2221) passed its restart check, 54 of 54 |
| API 16 emulator, debug APK SHA-256 `331687fc…6c9000fb` (2026-10-13, record recovery) | restart check against the record the new writer left: 54 of 54. Record replaced by an edited copy before each start: `digest=00` → "saved record unusable (digest has 2 hex digits, expected 64): restart check skipped, a fresh run follows", 50 of 50; one hex digit too many → "65 hex digits", 50 of 50; a malformed Unicode escape → "not a properties file: Invalid Unicode sequence", 50 of 50; empty → "no pid", 50 of 50. One signature changed by one hex digit (well-formed) → `SYNTHETIC FAIL der: same deterministic signature as the earlier run`, 54 checks, 1 failed; the next run passed 54 of 54 against the record that run rewrote |
| ARMv7 | not run: the `Adayo_ARM` AVD (armeabi-v7a, API 17) does not start, 32-bit ARM is not supported by the QEMU2 emulator. Needs the physical radio |

### 3d. Transport check (2026-10-13, debug APK SHA-256 `331687fc…6c9000fb`)

Debug builds have a third launcher entry, "Transport check". It reports what Android exposes for the
transports CarPlay could use and saves that as a text file (also written to logcat, tag
`TransportCheck`). Passive until a button is pressed; USB access is asked for one selected device,
which is opened only to read its raw descriptors; an RFCOMM check runs only for a selected paired
phone and sends only the six detection bytes. Emulator results show the code paths, not the radio.

| Runtime | Observed |
|---|---|
| API 16 emulator, x86 | SDK_INT 16, ABI x86, kernel 3.10.0+. Bluetooth feature declared, but `getDefaultAdapter()` returns nothing: "no adapter exposed ... does not prove the device has no Bluetooth hardware". BLUETOOTH and BLUETOOTH_ADMIN granted at install. USB host not declared, 0 devices. `WRITE_EXTERNAL_STORAGE` granted at install, but this AVD has no SD card (`hw.sdCard=no`), so the report went to `files/transport-check/` and was read back with `run-as`; 23 lines in logcat |
| Android 16 emulator, x86_64 | SDK_INT 36. Permission dialog answered "Don't allow": recorded as `BLUETOOTH_CONNECT denied BLUETOOTH_ADVERTISE denied`, status "permission denied", name and address withheld (SecurityException), paired devices not listed. Answered "Allow": status "on, adapter address unavailable: 02:00:00:00:00:00 is the placeholder ...", paired devices none. Bluetooth switched off (`cmd bluetooth_manager disable`), Refresh: "Bluetooth disabled (off)", none listed. BLUETOOTH_SCAN reported as not declared. USB host not declared, 0 devices. Report saved to `/storage/emulated/0/Android/data/me.ri3d.openauto/files/transport-check/` and pulled with adb (1,722 B) |

Not exercised on any device yet: reading USB descriptors (neither AVD has USB host or a device),
the RFCOMM connect, wait, Cancel and time limit with Android's real sockets (no paired phone on
the emulators; the unit tests use a fake endpoint), an adapter address that Android hands out
(needs Android 5.1 or older with Bluetooth), and the external report folder on Android 4.1–4.3.

The two "iAP2 handshake" buttons per paired phone (added 2026-10-22) run the Bluetooth profile's
link and control session over the connected or accepted socket with the SYNTHETIC identity the MFi
probe leaves in `files/carplay-auth-probe/pkcs7` (run that probe once first; without it the check
reports "not started"). The report lists the socket, how the link ended, the phone's SYN+ACK values,
link counters, how far the session got and which exchange the phone started, the verdict, any
messages the phone sent afterwards, and why the input ended. Exercised only in JVM tests against the
scripted phone; no emulator has a paired phone.

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

### 4d. Transport check on the radio

**Not run yet.** Steps: install the debug APK (`app/build/outputs/apk/debug/app-debug.apk`) over ADB;
open "Synthetic MFi probe" once (it leaves the synthetic identity the handshake needs); open
"Transport check" from the launcher; Save report (passive part). With the iPhone on USB: "Read
descriptors" for its row only, accept Android's dialog, Save report. With the iPhone paired in the
radio's Bluetooth settings (if Android lists it): "Connect (20 s)", then "Wait for it (60 s)", one
Cancel test, Save report. Then "iAP2 handshake: connect" and, if that socket never connects,
"iAP2 handshake: wait" (60 s each; the phone may show a prompt or nothing at all), Save report.
What the handshake report would settle: whether the phone echoes the detection bytes, whether it
answers the SYN and with which values, which exchange it starts first, and what it rejects in the
identification or the synthetic certificate. The expected end is a refused certificate; a verdict
line reporting acceptance would be unexpected and would need a second look before anything is
concluded. Pull `/sdcard/Android/data/me.ri3d.openauto/files/transport-check/` and
`adb logcat -d -s TransportCheck:I`. A timeout is no evidence against CarPlay; a
connected socket without the echo says nothing about iAP2 either.
