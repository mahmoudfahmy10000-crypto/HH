# Samsung Universal Remote · Bluewave

Version 1.1.0 feature branch adds authorized Remote VPN access and an Arabic/English smart keyboard. See [AU7000_GUIDE.md](AU7000_GUIDE.md) for required always-on home VPN infrastructure, protocol evidence, limitations and the physical-TV test matrix. AU7000 hardware results are not fabricated.

A native Kotlin / Jetpack Compose Android remote. AMOLED surfaces, blue accents, accessible labeled controls, saved televisions, streaming shortcuts, swipe navigation, and real Consumer IR transmission. Android 8.0+; suitable for Redmi Note 14 when its firmware exposes the required hardware.

## Download and install using only your Android phone

1. Open **https://github.com/mahmoudfahmy10000-crypto/HH/actions** in Chrome and sign in to GitHub. Use **Desktop site** if artifacts are hidden in the mobile layout.
2. Select **Build Android APK**, then the latest successful green run on `feature/au7000-button-power-audit` for version 1.2.0. For a fresh build, use **Run workflow → feature/au7000-button-power-audit → Run workflow**.
3. Under **Artifacts**, tap **SamsungUniversalRemote-APK**. Download the ZIP and extract it in Files. The installable file inside is **app-debug.apk**.
4. Tap the APK. If Android asks, allow **Install unknown apps** for the browser or Files app you used, then tap **Install**. You can turn that permission back off afterward.
5. Open **Samsung Universal Remote**. No Android Studio, PC, subscription, Samsung account, or app login is needed.

Artifacts expire after 30 days; rerun the workflow for a new download. The APK is a real debug-signed installable build, not a Play Store release. GitHub generates a debug key on each clean runner; later builds may require uninstalling an earlier build (which removes saved pairings). Production distribution should use a private, persistent release signing key stored in GitHub secrets; no private key is committed here.

## Connect over Wi-Fi

- Turn the TV on, connect phone and TV to the same LAN, then open **Devices → Discover TVs**. Only RFC1918 private and IPv4 link-local TV addresses are accepted. SSDP discovery verifies candidates with Samsung's port 8001 device API. If discovery is blocked, enter the TV's IPv4 address from its network settings.
- Default: TLS WebSocket on port **8002**, `/api/v2/channels/samsung.remote.control`. Samsung TVs frequently have self-signed certificates. Check the TV IP on a trusted network, explicitly approve the displayed SHA-256 certificate, then choose **Allow** on the TV. Certificate changes require fresh approval. This is certificate pinning after first-use consent, not public-CA validation.
- Legacy port **8001** is an explicit unencrypted option for compatible models. Pairing tokens and commands are exposed to the LAN in that mode; use only on a trusted network. No automatic TLS downgrade. TLS and legacy credentials now use separate storage namespaces; upgrading from 1.0.0 requires approving TV pairing again.
- Only the TV's `ms.channel.connect` event marks the connection authorized and saves it. Tokens are encrypted using AES-GCM and an Android Keystore key. Backups are disabled. **Forget** removes saved credentials and the certificate pin.
- Last authorized TV reconnects on app startup; transient failures retry three times with backoff. Pairing has a 35-second timeout. Denied pairing requires TV permission changes and manual retry. The socket pauses and drafts are cleared while the app is not resumed. Foreground use is intended; there is no persistent background service.

Supported TVs expose Samsung's Tizen WebSocket remote API, typically 2016 onward. Older encrypted/PIN protocols, hotel restrictions, and models without this API are unsupported. A TV unavailable on both remote ports cannot be controlled through Wi-Fi by this application. Guest Wi-Fi isolation and firewalls can block local access. No physical Samsung TV was available in the cloud build environment; on-device interoperability must be checked with your TV.

## Infrared

**Settings** queries `ConsumerIrManager.hasIrEmitter()`. Hardware varies across Redmi Note 14 regional variants. If no emitter is exposed by Android, IR is explicitly unavailable; it is never simulated.

Select Samsung32 first (38 kHz, 4.5 ms header, `E0 E0 command complement`, MSB first). An alternate NEC 9 ms header profile is available for compatible legacy receivers. **Test IR** asks for confirmation before sending one volume-up key. Point the phone's emitter at the TV. IR works without Wi-Fi/internet. The Android transmit call confirms submission to the emitter, not that the TV responded. IR Power is a toggle; Settings uses the Samsung menu code.

## Controls and limitations

Version 1.2.0 adds separate power intents, central app launch with native/deep-link metadata, Exit/colors, and Settings → Remote Diagnostics / Power Diagnostics. See [the complete button audit, fixes and physical test procedure](BUTTON_AUDIT.md). No physical AU7000 results are claimed.


Power toggle, Wake-on-LAN, volume/channel, mute, Home, Back, menu/settings, source, D-pad/OK, digits, play/pause, fast-forward/rewind are implemented. Model support for individual keys varies. A WebSocket `send` only confirms that a message was queued; the TV protocol does not acknowledge execution of individual keys. UI feedback states this explicitly.

- **Power ON:** configure and opt into network wake in **Settings → Power Diagnostics**, using the active TV-interface MAC after enabling supported TV network standby / Power On With Mobile. Alternatively confirm physical standby for a single local IR toggle. Wake requests never claim the TV actually turned on. **Power OFF** uses an authorized discrete Wi-Fi key or physically confirmed-on IR; no automatic toggle fallback.
- **Swipe pad:** translates swipes to actual direction-key requests. Native mouse-pointer control is not claimed because it is not consistently supported by this API.
- **Apps:** requests `ed.installedApp.get`, launches available app IDs with `ed.apps.launch`. YouTube, Yango Play, WATCH IT, Shahid, MBC, Netflix and Prime Video are matched against the TV's actual app list, with no invented regional IDs. When an app is missing or the API is unavailable, feedback recommends **Home and navigation**. Use **Apps** to pin installed apps or supply a known Samsung app ID manually. A launch request never claims that playback or launch succeeded. IR has no direct app launching.

## Build and checks

Java **17 JDK**, Android SDK platform **35**, Gradle **8.10.2** (wrapper with distribution SHA-256), Android Gradle Plugin **8.7.3**, Kotlin **2.0.21**. Dependencies use Google Maven and Maven Central with TLS verification.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions installs Java and the SDK, builds with the wrapper, runs unit tests/lint, verifies the APK signature/permissions/16 KB alignment/integrity, and uploads the APK plus its verification evidence as **SamsungUniversalRemote-APK**. It runs on main pushes and manual dispatch. Unit tests validate IR wire encoding, complementary bits and durations, IPv4 inputs, Wake-on-LAN packets, Samsung JSON payloads, and local WebSocket authorization / denial / app enumeration / key transmission. These tests are protocol checks, not physical TV certification.

## Privacy and permissions

No ads, analytics, account, cloud backend, or telemetry. Network traffic goes directly to the private LAN IP you select or local SSDP/Wake-on-LAN broadcasts. Permissions: internet/local networking, network transport/VPN state, Wi-Fi multicast and Consumer IR transmission. Android View haptics do not require VIBRATE. No location, contacts, microphone, camera, storage, or notification permission. Local cleartext access is necessary for SSDP device-info HTTP and optional legacy WebSocket; secure remote mode remains the default. Use trusted LANs only.

Independent project; not affiliated with Samsung or any streaming provider. Brand names identify supported shortcuts only. No official provider logos or copyrighted brand assets are bundled.

## Security audit and verification

See [SECURITY_AUDIT.md](SECURITY_AUDIT.md) for the 1.0.1 audit, limitations, retained local-TV protocol risks and dependency advisory evidence. The artifact includes `SHA256SUMS`, `verification.json`, `signature.txt`, alignment evidence and the report, all generated for the APK inside that same archive. Run `ANDROID_HOME=/path/to/sdk python3 tools/verify_apk.py` after `./gradlew assembleDebug testDebugUnitTest lintDebug` to reproduce the binary checks.

A debug certificate is self-signed and does not establish a verified publisher or Play Protect reputation. If Play Protect blocks the APK, stop installation; do not disable or override Play Protect. A successful build, signature verification or clean advisory lookup is not Google approval. Android 16 behavior still needs testing on real hardware.

The Keyboard button supports manual input and compatible foreground IME events. Voice input is supplied by Gboard rather than an app microphone permission. Remote VPN requires a user-authorized home VPN gateway; the app never exposes TV ports publicly. See the AU7000 guide before using either feature.
