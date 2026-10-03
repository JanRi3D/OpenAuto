# Design notes

Constraints and decisions behind the implementation. Statements are marked by how they are known:
**fact** (checked on a device, an emulator or in source), **observed** (behaviour of
reverse-engineered projects or of a real phone, not documented by Google), **assumption** (still to
be confirmed on hardware).

## 1. Protocol and authentication

- **Observed.** Google does not document the head-unit protocol. The wire format used here (frame
  header, channel ids, message ids, protobuf field numbers, startup order) is collected in
  [protocol-reference.md](protocol-reference.md) from aasdk, openauto, WirelessAndroidAutoDongle,
  aa-proxy-rs and headunit, each item with its source, plus what a real phone was seen to do (§14).
- **Observed.** The session is TLS inside the framing: the head unit is the TLS client, the phone the
  server; the head unit presents a client certificate and does not verify the phone. Handshake
  records travel in plain `SSL_HANDSHAKE` messages; afterwards each frame payload is one TLS record.
- **Fact.** A real phone negotiated TLS 1.2 with `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`.
- **Fact, with a caveat about provenance.** The phone accepts only client certificates issued by
  Google's "Google Automotive Link" CA. The one publicly available pair is embedded in aasdk
  (`src/Messenger/Cryptor.cpp`): subject *JVC Kenwood*, valid 2014 to 2045. aasdk does not say where
  it comes from; every open-source receiver uses it. It is kept as a replaceable asset
  (`app/src/main/assets/tls/`, original PEM in `docs/reference/`). Its legal status is not something
  this project can settle.

## 2. TLS on Android 4.1

- **Fact.** `SSLEngine` on Android 4.1–4.3 is the Harmony engine and speaks TLS 1.0 only.
  `SSLSocket` can do TLS 1.2, but its handshake parses the peer certificate with a strict DER parser
  that rejects the BER-style dates in these certificates. Neither can be used.
- **Decision.** TLS runs on BouncyCastle's non-blocking client (pure Java, identical on every API
  level and on the JVM). Under Dalvik that is too slow for video, so Android 4.1–5.1 use the
  device's own OpenSSL 1.0.1 through a small JNI shim after it has passed a self-test. Details and
  measurements: [DEPENDENCIES.md](DEPENDENCIES.md).

## 3. Platform APIs

- **Fact.** Available on API 16 and used: USB host (`bulkTransfer` without offset, 16 KiB per
  transfer), `MediaCodec` with input/output buffer arrays and Surface output, `MediaCodecList`,
  `AudioTrack`, `AudioRecord`, Bluetooth RFCOMM server sockets.
- **Fact.** Avoided: AndroidX (needs minSdk 21+), vector drawables, font resources, `TextClock`,
  `MediaCodec.Callback`, `VideoCapabilities`. The UI is plain activities, XML layouts, shape
  drawables, a Canvas-drawn icon view and four Barlow TTFs loaded from assets.
- **Fact.** Lint's `NewApi` check is fatal in the build; every newer API sits behind a version check.

## 4. USB

- **Fact.** The radio must expose USB host mode to Android (`FEATURE_USB_HOST`). Some radios route
  the USB port to a separate controller that Android never sees; Diagnostics shows which it is.
- **Observed.** Accessory negotiation: `GET_PROTOCOL` (51) returns 1 or 2, six `SEND_STRING` (52)
  requests, `START` (53); the phone re-enumerates as 18D1:2D00/2D01 with two bulk endpoints.
- **Fact.** Radios have USB devices of their own (the test radio has a DAB tuner), so only devices
  with an Android-style interface are treated as phones.
- **Fact.** After the switch to accessory mode Android itself asks whether to open the app for the
  device (manifest device filter) and grants permission with the answer.

## 5. Wireless

- **Fact.** By IP address: the phone's "Start head unit server" developer setting listens on TCP
  5277 and the head unit connects to it. Both only need to share a network.
- **Observed.** Automatic: the phone connects to the head unit over Bluetooth RFCOMM (UUID
  `4de17a00-52cb-11e6-bdf4-0800200c9a66`), receives Wi-Fi credentials and the head unit's address,
  joins that network and opens the session on TCP 5288. Open-source dongles also advertise a headset
  profile "to trick the phone into recognizing a wireless Android Auto head unit" (aa-proxy-rs).
- **Fact.** An app cannot register a headset profile with public APIs, and Android 4.1 has no public
  hotspot API (the hidden `setWifiApEnabled` works by reflection on many devices before Android 8).
  Many radios also keep Bluetooth in a module that Android apps cannot reach. The app reports each
  missing piece instead of pretending.
- **Assumption.** A phone starts wireless Android Auto towards a device without a headset profile.

## 6. Self Mode

- **Fact.** Android Auto and the receiver on one device over loopback TCP 5277. It needs the Android
  Auto app, hence Android 9+, and is unavailable on older radios.

## 7. Video

- **Fact.** API 16 cannot ask a decoder for its limits, so the Resolution setting configures a real
  decoder at each size and disables the ones it rejects.
- **Fact.** A device may list its software decoder first (the test radio does); decoders are chosen
  by name, hardware first.
- **Observed.** A real phone sends one keyframe per stream and only predicted frames after it. A
  coded frame must therefore never be dropped. Frames are acknowledged when the decoder has taken
  them, which makes the phone follow the decoder's pace (`max_unacked`); after a late start or an
  overflow everything up to the next keyframe is discarded and the phone is asked to restart the
  stream through a video focus change (`VideoQueue`).
- **Observed.** With video margins the phone lays out for the screen's shape and leaves the rest of
  the frame black. Three ways to crop the margins exist and none is honoured everywhere, so the
  method is a setting: a TextureView transform, the codec's scaling mode, or cropping fields written
  into the stream's SPS (measurements in [TEST-RESULTS.md](TEST-RESULTS.md) §3a, §3b, §4b, §4c).

## 8. Memory

- **Fact.** Queues are bounded and pooled: video 6 units, audio 16 chunks of 4 KiB. Decoder buffers
  belong to the codec. On the API 16 emulator the process uses about 16 MiB while projecting.

## 9. Build

- **Fact.** AGP 9.3.3, Gradle 9.5.0 and JDK 21 build a `minSdkVersion 16` APK; Java 11 source level
  with D8 desugaring, no Kotlin standard library. R8 shrinks debug and release, because BouncyCastle
  alone exceeds the single-dex method limit and API 16 has no native multidex.
- **Fact.** NDK 23.2 is the last NDK that targets API 16; it builds the JNI shim for four ABIs.
