# CarPlay test build

The CarPlay tile opens a standalone receiver in the existing OpenAuto app. The implementation retains minSdk 16 and ARMv7 support. The UI and Android Auto implementation remain platform Java; selected DiPlay protocol sources use Kotlin with Bouncy Castle. No AndroidX/Compose runtime was added.

## Install and connect

Install `dist/openauto-0.6.0-carplay1-release.apk` on the radio, then open **CarPlay**. The existing General setting can switch the small launcher tile between CarPlay and Self Mode.

For wireless:

1. Pair the iPhone with the radio in Android's Bluetooth settings.
2. Open CarPlay and select the paired iPhone. Grant Bluetooth and microphone permissions if requested.
3. Enter the Wi-Fi name and password. With **Start radio hotspot** off, connect the radio and iPhone to that network first. With it on, the app starts a radio hotspot and supplies its credentials over the Bluetooth bootstrap. The latter depends on the radio exposing a working hotspot API.
4. Tap **Wireless**, unlock the iPhone and accept its CarPlay/pairing prompts.

For USB:

1. Plug the unlocked iPhone into a USB host port Android can see.
2. Tap **USB**, accept Android's local VPN consent, approve USB access again if the iPhone changes USB mode, and accept the iPhone's Trust/CarPlay prompts.
3. Keep the iPhone connected while the control and network links initialize.

The VPN carries the iPhone's USB IPv6 link; it does not use an authentication server. Android 5+ can scope that VPN to OpenAuto. Android 4.1–4.4 lacks that API, so only the link-local IPv6 prefix is routed, across app UIDs. USB configuration changes on those versions are deliberately rejected when the framework/kernel do not expose the actual endpoint configuration; a successful raw vendor request is not sufficient.

Back or the close button disconnects. Leaving the activity can keep audio playing; returning through the notification restores projection. **Connection log** shows bounded local diagnostics. **Receiver check** tests the local identity files, cryptography, protocol codecs, software Opus and USB TLS availability. Passing those checks is not an iPhone connection result.

## Requirements and current boundaries

- Wireless requires an Android-visible Bluetooth adapter, a real adapter address, a paired iPhone and a shared IPv4 Wi-Fi interface. Some radios keep Bluetooth in a separate MCU that an app cannot access; modern Android can also withhold its Bluetooth MAC. Missing capabilities are reported explicitly.
- USB requires the radio/phone USB configuration, USBMUX/Lockdown and NCM/VPN paths to work together. The API 16 descriptor fallback is conservative; physical verification is required for each radio.
- Video starts with H.264 at 30 fps and a conservative display size based on the actual screen aspect ratio. HEVC, auxiliary/cluster output, manufacturer vehicle features and buffered TCP audio are not advertised by the product configuration.
- PCM, AAC-LC and software Opus are supported by the media adapters. Siri/call microphone encoding uses a software Opus fallback; its sustained ARMv7 CPU cost and physical audio routing need radio testing. Echo cancellation is not implemented.
- The standalone experimental identity is the same one DiPlay intentionally bundles in its public preview. It is not a new Apple-provisioned identity for OpenAuto; iPhone acceptance is not guaranteed. See [notices](CARPLAY-NOTICES.md).

## Build reproducibly

The default source build contains no accessory identity. Tests generate synthetic identities and never substitute them for runtime authentication.

To reproduce the public-preview identity experiment locally:

```powershell
pwsh -File tools/build-carplay.ps1 -UseDiPlayPreviewIdentity -Variant Release
```

The helper downloads DiPlay 0.2.21's public APK, verifies its published SHA-256, reads only the two runtime assets into the ignored `build/carplay-runtime/` directory, sets `OPENAUTO_CARPLAY_ASSETS` for the build and runs tests/lint/assembly. The APK includes the selected identity; its private key remains extractable from that APK. The Android application-signing keystore is separate and is never included.

To use a separately supplied matching accessory identity:

```text
your-private-assets/
  carplay-mfi/
    identity.pk8
    certificate.p7b
```

```powershell
pwsh -File tools/build-carplay.ps1 -RuntimeAssetsDir 'C:\path\to\your-private-assets' -Variant Release
```

Both files are validated for bounds and key/certificate consistency when installed into app-private storage. Never commit either private runtime material or release-signing credentials. Release assembly uses the repository's existing local signing configuration; debug assembly uses Android's debug signer.

The app retains its installed accessory identity across updates. To test a different packaged identity,
clear OpenAuto's app data before first launch of that build; this also removes saved settings and
pairings.

## Physical verification still needed

Record radio model/API/firmware, phone/iOS, connection path and selected identity provenance. Verify rendered CarPlay, touch edges and release events, music/navigation mixing, Siri microphone uplink, calls, Home/return, interruption recovery, disconnect/reconnect and USB mode changes. Measure CPU, memory, audio underruns and frame rate while video and microphone encoding run together.

The signed test APK is about 1.57 MB. Local validation passed 482 tests (3 opt-in skips), debug and
release lint, and the Android 4.1 receiver checks including a full synthetic production TLS
exchange. Exact hashes and proof boundaries are recorded in [TEST-RESULTS.md](TEST-RESULTS.md).
Physical iPhone/radio projection remains unverified.
