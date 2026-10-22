# Samsung Universal Remote 1.0.1 — security and Android audit

> For version 1.2.0, [BUTTON_AUDIT.md](BUTTON_AUDIT.md) supersedes power, app-launch, diagnostics and test details. Older results below are retained as historical evidence.

> Historical baseline report. For version 1.1.0, [AU7000_GUIDE.md](AU7000_GUIDE.md) supersedes the feature, permission and test summaries below: ACCESS_NETWORK_STATE is now used for VPN/lifecycle monitoring, and new VPN/keyboard tests are included. The dependency graph is unchanged.

Audit date: 2026-10-22. Baseline source: `60dbb3e1292fc37d09e7832b7f0970f579b5c234`.
The audited revision is the commit containing this report. Package: `com.bluewave.remote`;
versionCode 2, versionName 1.0.1. This is a standalone **debug APK**, not a production release.

## Findings and changes

| Area | Finding | Resolution / retained limitation |
| --- | --- | --- |
| Permissions | Network-state, Wi-Fi-state and VIBRATE were declared but not needed by the implemented APIs | Removed all three. View-based haptic feedback remains. |
| Dependencies | Unused Compose `ui-tooling-preview` was included in the application | Removed it. No runtime dependency identified as malicious or affected by the reviewed-advisory lookup was found; required AndroidX, Kotlin, OkHttp and Okio remain. |
| Pairing credentials | Secure and legacy modes used the same IP-indexed credential slot | TLS and plaintext tokens now have separate namespaces. A TLS credential is never automatically reused on the plaintext endpoint. Existing 1.0.0 pairings require fresh TV approval; certificate pins and saved TV entries remain. Forget removes old and new credential slots. |
| Redirects | HTTP/WebSocket clients used default redirect handling | Disabled HTTP and TLS redirects to prevent a TV response from redirecting discovery requests or pairing credentials to another destination. |
| Network scope | Manual IP entry admitted public IPv4 addresses | Production connection, manual entry, discovery and saved-TV flows now require canonical RFC1918 private or IPv4 link-local addresses. Public-IP, hostname, loopback and ambiguous leading-zero input is rejected. Unit tests inject a loopback-only destination predicate solely for their local test servers. |
| Input handling | Device metadata had no application size bound | Discovery JSON is limited to 256 KiB before decoding; WebSocket text above 256 Ki characters is rejected after delivery by OkHttp. This is not a pre-allocation bound on the WebSocket frame decoder. |
| Backups | `allowBackup=false` lacked explicit Android 12+ data extraction rules | Added cloud-backup and device-transfer exclusions for all supported data domains and disabled legacy full backup. Keystore token encryption remains. No OEM transfer behavior was physically tested. |
| CI supply chain | Actions were referenced by mutable major tags | Pinned all four used actions to the commits resolved from their official tags during this audit. Gradle distribution and downloaded Android CLI tools retain SHA-256 verification. |
| APK evidence | Old artifact contained only the APK | CI now fails on invalid signature, unexpected permissions, ZIP corruption, split APK output, missing ARM64 native library, or failed 16 KiB ELF/ZIP alignment. It bundles public certificate and hash evidence with the exact APK it checked. |

## Permissions and exposed components

Final packaged platform permissions are only:

- `INTERNET`: local Samsung HTTP/WebSocket, SSDP and Wake-on-LAN sockets.
- `CHANGE_WIFI_MULTICAST_STATE`: Android Wi-Fi multicast lock for SSDP discovery.
- `TRANSMIT_IR`: `ConsumerIrManager` transmission; hardware is optional.

All are normal permissions, not runtime dangerous permission prompts. AndroidX also merges
`com.bluewave.remote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level permission
for its receiver safety machinery. It is not access to another application's data.

No location, nearby-device, microphone, camera, contacts, phone, SMS, storage, notification,
package-installation, all-packages visibility, accessibility, overlay, boot or foreground-service
permission is requested. The exported launcher activity is required to open the app; it has
no custom deep links or command-receiving extras. The merged AndroidX initializer provider
is not exported. AndroidX's profile-install receiver is exported but guarded by the system
signature/privileged `android.permission.DUMP`; it is not an unprotected remote-control service.
The app does not itself request DUMP.

## Source and behavior review

Reviewed every application Kotlin file, test file, manifest/resource file, dependency/build
configuration, wrapper configuration and workflow in the project. No ads, analytics SDK,
cloud backend, user-account collection, embedded application credentials, shell execution,
dynamic DEX loading, WebView, APK installation routine, downloaded executable code, or hidden
background remote-control service was found in the application source. Review and matching
known advisories are not proof that all code and transitive binaries are free of defects.

Real Consumer IR emission remains 38 kHz with Samsung32 and alternate NEC-header timings;
there is no simulated IR success. Wi-Fi pairing still waits for Samsung's authorization event.
YouTube, Shahid, MBC, Yango Play and other shortcuts use IDs returned by the TV or supplied
explicitly by the user. No phone streaming app, provider account, web-player login, or
provider API credential is required. Unknown/unsupported apps use the Home/navigation fallback.
No unit test can certify regional TV app availability or physical IR behavior.

## Retained protocol risks

- TLS 8002 is the default. Samsung's self-signed certificates require explicit first-use
  fingerprint approval, followed by an exact SHA-256 leaf-certificate pin. Unknown and changed
  pins fail the handshake until approved. No trust-all manager or silent TLS downgrade exists.
  This is **not** public-CA identity validation; the user must independently check the TV/IP on
  a trusted LAN. The custom manager deliberately uses the approved certificate pin instead of
  public-chain/hostname/expiry validation for TV certificates. Do not approve unknown peers.
- The TLS hostname verifier confines the handshake to the selected IP while the custom trust
  manager enforces that TV's pin. Platform TLS/cipher policies remain enabled.
- Samsung metadata HTTP and the explicitly selected legacy WebSocket on 8001 require
  `usesCleartextTraffic=true`. Android's static domain XML cannot express arbitrary discovered
  private IPv4 ranges. Destination checks and disabled redirects constrain application flows.
  Legacy mode still exposes its **own** tokens/commands to the LAN; use a trusted home network.
  Removing all cleartext support would break compatible TV discovery/legacy models.
- Pairing tokens are AES-GCM encrypted using an Android Keystore AES key. IPs, names, app IDs
  and certificate fingerprints are ordinary private app preferences. Tokens are required in
  Samsung's connection query string but no HTTP logging interceptor or credential logger is used.
- Commands, app launch, IR and wake packets report requests/transmission, not confirmed TV
  execution. Automatic reconnection is bounded after transient failures.

## Dependency review

`audit/runtime-dependencies.json` records **88** resolved runtime module versions.
`audit/test-dependencies.json` records **92** resolved unit-test-runtime modules (including the
runtime graph). `audit/dependency-advisories.json` records exact-version queries for the union
of **92** modules against GitHub Global Security Advisories' reviewed advisory database.
At the recorded query time: **0 matching reviewed advisories, 0 query errors**.

This lookup is time-sensitive and limited to the database's package/version coverage. It is
not an antivirus/malware analysis, an exhaustive unreviewed-advisory search, or an audit of all
compiler/Gradle/JDK implementation dependencies. Test-only JUnit, JSON and MockWebServer are
not packaged in the APK. Production runtime dependencies use Google Maven/Maven Central;
there are no arbitrary local runtime JARs, embedded SDK downloads or HTTP Maven repositories.
No unsafe dependency was established that would justify removing the TV networking stack.

Reproduce inventories from the repository root (with the documented Java/SDK environment):

```sh
./gradlew -I tools/audit-inventory.gradle :app:auditRuntimeInventory
./gradlew -I tools/audit-inventory.gradle :app:auditRuntimeInventory \
  -PauditConfiguration=debugUnitTestRuntimeClasspath -PauditOutput=test-dependencies.json
python3 tools/check-dependency-advisories.py
```

The last command needs authorized GitHub API access via `gh`. It never prints authentication
values. GitHub Actions itself has only repository `contents: read` permission.

## Android 16 compatibility

- minSdk 26; compileSdk and targetSdk 35 (Android 15), AGP 8.7.3, Kotlin 2.0.21,
  Gradle wrapper 8.10.2, build-tools 35.0.0, Java 17. These are the tested pinned build versions,
  not a claim that all tools/dependencies are the latest available releases.
- Android 16 supports apps targeting API 35; the target was not artificially lowered to avoid
  security checks. No Android 16-only API is required. This is not a targetSdk 36 migration or
  certification of current or future Google Play publishing eligibility.
- All four packaged `libandroidx.graphics.path.so` ABIs, including ARM64, have PT_LOAD alignment
  of at least 16384 bytes. The rebuilt APK passes `zipalign -c -P 16 -v 4`. This addresses static
  16 KiB page-size packaging requirements; actual Redmi Android 16 execution remains untested.
- No phone, Android 16 emulator, Samsung TV, or physical IR transmitter was available for this
  audit. Local-network permission behavior under future target/API/OEM changes, edge-to-edge
  layout, pairing, actual app launch and IR response still require device testing.

## Build, signature and integrity results

Executed `./gradlew assembleDebug testDebugUnitTest lintDebug` successfully:

- **18 tests passed**, 0 failures, 0 errors, 0 skipped: 8 protocol, 5 certificate/address-policy,
  5 local WebSocket authorization/denial/token-isolation/redirect/destination tests.
- Android Lint: **0 errors, 2 warnings**. `CustomX509TrustManager` is retained and documented
  for explicit local-TV certificate pinning, with rejection/approval tests. `ModifierParameter`
  is a Compose parameter-order style warning. Neither warning was disabled to obtain a pass.
- `apksigner verify --verbose --print-certs --min-sdk-version 26`: v2 and v3 signatures verify.
  v1 is unnecessary for minSdk 26; v4/external `.idsig` is disabled. The result is one standalone
  installable debug APK, not an unsigned file, App Bundle or required set of splits.
- All ZIP CRCs, 16 KiB ZIP alignment, native ELF alignment, package metadata and packaged
  permission allowlist checks pass in `tools/verify_apk.py`.

The artifact's **`SHA256SUMS` and `verification.json` are authoritative for that exact APK**.
`signature.txt` includes its public signer certificate fingerprints and signing algorithms.
These files are generated again inside CI; a CI APK may have a different hash and signer from
this machine's local APK. A clean runner creates its own standard Android debug keystore.
Private signing keys and keystore files are not committed or uploaded. A persistent private
release key would be required for stable publisher-controlled production updates.

## Play Protect conclusion and limitations

The phone's original blocked APK and its exact Play Protect diagnostic were not supplied for
binary comparison. This audit covers the checked-out source and newly rebuilt artifacts; it
does not establish that a separately downloaded original file was identical to them.

A standard Android debug certificate is self-signed and is not a verified publisher identity.
A sideloaded debug APK may be described as coming from an unknown developer. That warning
alone does not prove malware, and a valid signature does not prove harmlessness or establish
why Google's classifier blocked the particular file. Changing certificate names, permissions,
or hashes does not establish Play Protect reputation or approval.

**No Play Protect bypass, disabling, warning override, forced installation, security-check
suppression or claim of Google approval was performed or added.** If Google blocks the new
artifact, stop installation and use Google's official developer review/appeal process if
appropriate; the checks above are evidence, not a reason to override the protection.
