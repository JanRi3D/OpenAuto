# CarPlay component notices

OpenAuto's CarPlay protocol and transport components adapt [DiPlay](https://github.com/shihabal3amri/DiPlay) and its upstream [xcertplay](https://github.com/shilapi/xcertplay), under GPL version 3. The reference snapshot is DiPlay commit `143f4674b60b564a16baae5110b3b90fb7277631`. The complete license is retained in [licenses/carplay-GPL-3.0.txt](licenses/carplay-GPL-3.0.txt). Adapted source remains under `com.shilapi.xcertplay`; original source comments and component attribution are retained. OpenAuto's adapters are under `me.ri3d.openauto.carplay`.

Upstream credits LIVI and Showcase for protocol research. OpenAuto uses the selected receiver components; it does not import DiPlay's BYD dashboard, HUD artwork, vehicle integrations or Compose UI.

The pure Java Concentus Opus codec retains its BSD license and copyright notices in [the vendored license](../app/src/main/java/org/concentus/LICENSE) and source headers. It is used for microphones and Opus audio on platforms without a suitable hardware codec. Bouncy Castle remains the project's existing crypto dependency; its upstream license applies.

The standalone experimental build can contain the same accessory identity intentionally bundled in DiPlay 0.2.21's public APK. DiPlay describes it as recovered from public Carlinkit firmware, rather than a newly provisioned identity for that project. These runtime assets are separate from the source-code license. They are not committed to this repository. Anyone receiving an APK containing them can extract them. Their acceptance by a particular iPhone/iOS is a physical test result; this build is not Apple-certified.

The reference APK used for the local experiment is `DiPlay-0.2.21.apk`, SHA-256 `916c0708beb1022d4ea9678ff6a46dfb6d234f707837a2cbd80d615e8e2bb2e3`, matching the GitHub release asset digest. The build helper verifies that exact digest before reading the two runtime entries. Private-key bytes and Wi-Fi credentials must never be added to reports or tracked source.

Source builds omit runtime accessory assets unless `OPENAUTO_CARPLAY_ASSETS` is set. See [CARPLAY.md](CARPLAY.md) for the local build and device-test procedure.
