# Dependencies

Everything else is the Android platform API (minSdk 16) and the Java standard library. No AndroidX, no
Kotlin. One small piece of native code (below) that bundles no third-party library.

## Native shim over the device's own OpenSSL (`app/src/main/cpp/systls.c`, about 300 lines of C)

- **Why it is necessary.** On the real head unit (Cortex-A7, Android 4.2.2, Dalvik) the protocol
  reader thread saturated a core and video arrived at 14 fps (docs/TEST-RESULTS.md §4b). That thread's
  only heavy work is TLS record decryption, done by BouncyCastle in pure Java. On the API 16 emulator
  the same Java engine encrypts 2.6 MB/s where the system's OpenSSL does 43 MB/s.
- **Why the platform APIs are insufficient.** The Java TLS APIs of Android 4.x cannot be used at all
  (see the BouncyCastle section), and `javax.crypto` has no AES-GCM there. The system does contain a
  complete native TLS 1.2 implementation, OpenSSL 1.0.1, in `libssl.so` / `libcrypto.so`; it just has
  no usable Java face. The shim opens those libraries with `dlopen`, resolves 34 functions by name and
  runs the TLS client over two memory BIOs.
- **What is and is not shipped.** No cryptographic code is added to the APK: the crypto is the
  device's. The shim is 8 to 12 KiB per ABI (armeabi-v7a, arm64-v8a, x86, x86_64).
- **Compatibility and safety net.** Used only on Android 4.1 to 5.1 (API 16–22), where the system
  library is OpenSSL 1.0.1 and apps may open it; Android 6 switched to BoringSSL and Android 7 closed
  these libraries to apps, and there ART runs the Java engine fast enough. `libssl.so` is not a public
  NDK API, so nothing is assumed: before first use the engine must pass a loopback self-test (complete
  ECDHE-RSA handshake with the shipped certificate plus a record round trip). A missing symbol, a
  failed self-test, a failed handshake with a phone, or a self-test that never returned (flag kept on
  disk, so a crash inside a vendor's library cannot repeat) each leave BouncyCastle in charge.
  Settings › Diagnostics › "Encryption speed" shows which engine is active.
- **Build dependency.** NDK 23.2.8568313 (the last NDK that still targets API 16) and CMake 3.22.1,
  both from the Android SDK manager; pinned in `app/build.gradle.kts`.
- **Security note.** OpenSSL 1.0.1c is old. It is the library the device's own TLS already uses, the
  link is to the user's own phone over USB or a private network, and the head unit side of this
  protocol does not authenticate the phone in any implementation.
- **Alternative rejected.** Bundling mbedTLS (Apache-2.0): the same on every device and also fast on
  Android 6+, but roughly 150–250 KiB per ABI and several megabytes of vendored source. Worth doing
  only if a device turns up whose system library fails the self-test and whose Java engine is too slow.

## BouncyCastle TLS (`org.bouncycastle:bctls-jdk15to18`, `bcprov-jdk15to18`, transitively `bcutil-jdk15to18`), 1.86

- **Why it is necessary.** The head-unit protocol carries TLS 1.2 records inside its own frames; the
  receiver must act as a TLS client with a client certificate and move records in and out as byte
  arrays (protocol-reference §1.5, §9).
- **Why the platform is insufficient (measured, not assumed).**
  - Android 4.1's `SSLEngine` is the Harmony implementation and speaks TLS 1.0 only.
  - Android 4.1's `SSLSocket` (OpenSSL-backed) can do TLS 1.2, and a loopback-socket adapter was built
    and worked on the JVM. On the API 16 emulator it failed twice for reasons that cannot be fixed from
    app code: `getInputStream()` starts the handshake by itself, and the native handshake callback
    parses the *peer's* certificate with the strict Harmony DER parser, which rejects BER-style
    `UTCTime` values (the head-unit certificate itself uses `-0700` offsets; whether the phone's does
    is unknown) and threw a `RuntimeException` inside the handshake thread.
  - BouncyCastle's non-blocking `TlsClientProtocol` is pure Java, parses certificates itself, and makes
    the record-to-frame mapping deterministic (`offerInput`/`readInput`, `writeApplicationData`/`readOutput`).
- **API 16 compatibility.** The `jdk15to18` artifacts are built for Java 5–8 bytecode and use no
  Java 8 library APIs that need desugaring. Only the lightweight API is used; the JCE provider is
  never registered. Verified by running the full session on the API 16 x86 emulator.
- **License and provenance.** Bouncy Castle License (MIT-style), Legion of the Bouncy Castle Inc.,
  published on Maven Central under `org.bouncycastle`; versions pinned in `gradle/libs.versions.toml`.
- **APK size and memory impact.** Jars: bcprov 7.0 MiB, bctls 0.8 MiB, bcutil 0.8 MiB. After R8
  shrinking (enabled for debug and release) the whole app's `classes.dex` is 1.46 MiB and the debug
  APK is 0.94 MiB (0.47 MiB before BouncyCastle). Shrinking is mandatory for minSdk 16: unshrunk
  BouncyCastle exceeds the single-dex method limit and API 16 has no native multidex. Heap cost is the
  TLS session state plus the loaded classes, a few hundred KiB; measured process numbers belong in
  docs/TEST-RESULTS.md as they are collected.
- **Alternative rejected.** Keeping the platform `SSLSocket` adapter as default with BouncyCastle as a
  fallback would have doubled the TLS code paths for a path known to crash on the primary target.
- **Role since the native shim exists.** BouncyCastle is the engine on Android 6 and newer, on the JVM
  (all tests), and the fallback on older devices whose system library cannot be used.

## Test-only

- JUnit 4.13.2 (EPL 1.0): unit and integration tests on the JVM.

## Assets (not code)

- Barlow and Barlow Semi Condensed (SIL Open Font License 1.1), four TTF files, ~430 KiB, loaded once.
- Head-unit TLS certificate and private key from aasdk (provenance: docs/DESIGN.md §1).
