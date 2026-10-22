# AU7000 full control audit and repair — 1.2.0

Audit date: 2026-10-22. Existing application ID: `com.bluewave.remote`, versionCode **4**.
Source baseline: `5bd7df381de5f95cd30b49ff81ce69ac0f8c3d8b`.
Branch: `feature/au7000-button-power-audit`. The commit containing this report is the audited revision.

**No Samsung AU7000 or Redmi Note 14 was accessible in this environment. Every physical TV
result below is NOT TESTED.** Automated tests verify client behavior against local mock
Samsung endpoints and fixtures, not the TV's implementation, app availability, or panel state.
This report supersedes power, shortcuts, diagnostics and control inventory in the older guides.

## Findings, fixes and regression coverage

| Button / area | Problem | Fix | Automated test | Physical TV status |
| --- | --- | --- | --- | --- |
| Power ON | Wake used a MAC without an explicit standby configuration gate; toggle was the principal power control | Separate ON intent; explicit confirmation; confirmed-standby local IR option, otherwise opted-in network wake with valid unicast MAC; no blind toggle | `ControlAuditTest.powerNeverBlindlyTogglesForAnOnOrOffIntent`, MAC tests | NOT TESTED |
| Power OFF | Toggle cannot express OFF safely | Authorized WebSocket `KEY_POWEROFF`; local single IR toggle only after user confirms TV ON; no automatic toggle fallback | Power planner, dispatcher and all-key mock tests | NOT TESTED; firmware may ignore discrete OFF |
| Power Toggle | Repeated taps can reverse state | Explicit dialog, one key, shared five-second guard; reconnect never replays keys | `powerGateBlocksDoubleTapsAndReverseIntentForFiveSeconds`, mock duplicate checks | NOT TESTED |
| Power status | Connectivity is insufficient evidence of display state | Connected / Standby Suspected / Offline / Unknown, each with evidence and limits; ordinary disconnect means Unknown | `disconnectIsNeverProofOfPowerOff` | NOT TESTED |
| All regular keys | Validation and delivery feedback were spread across handlers | One catalog and dispatcher; actual queue/driver result, no execution acknowledgement claim; unsupported IR rejected before emission | `everyControlSerializesExactlyOnceAndUsesOnlySelectedTransport`, mock every-key test | NOT TESTED |
| Exit / colors | Missing UI controls | Add Exit and red/green/yellow/blue, with community Samsung32 wire codes | IR fixture and every-key tests | NOT TESTED |
| Guide / Info | Missing UI and no sufficiently established IR code in reviewed fixture | Add Wi-Fi keys; explicit Unsupported in IR rather than guessed code | Dispatcher rejection and all-key tests | NOT TESTED |
| Settings | IR is a Menu alias, not a dedicated verified Settings command | Preserve existing behavior and disclose alias; no claim of universal dedicated Settings support | Every-key / IR test | NOT TESTED |
| YouTube / Shahid / Yango Play | Broad substring matching could select a different app; forced DEEP_LINK lost TV metadata | Central exact aliases including Arabic; actual returned IDs; reject ambiguity; retain `app_type` / launch action | `AppLauncherTest` aliases, ambiguity, missing IDs; mock native/deep launch | NOT TESTED; regional installation required |
| Netflix / WATCH IT / MBC / Prime Video | Same launcher defects; MBC must not be silently substituted with Shahid | Same central launcher, separate aliases; user-managed ID/type override | All-brand launcher tests | NOT TESTED |
| Custom shortcut save | Reusing an ID toggled it off instead of updating configuration | Save replaces that ID's configuration; Pin/Unpin remains a separate toggle | Code inspection; no automated Compose interaction test | NOT TESTED |
| Installed apps | No response timeout or launch-type display | Eight-second explicit refresh timeout; ID/type displayed; unknown type labelled; explicit Home fallback | Mock app-list parsing, metadata tests; timeout scheduling inspected | NOT TESTED |
| Keyboard | No diagnostic history for text / Enter / Backspace | Add redacted delivery records; retain field-focus and resend safeguards; no draft content stored in diagnostics | Existing IME fixtures/mock tests; all-key Enter/Backspace tests | NOT TESTED |
| Swipe pad | Could be interpreted as native pointer support | Remains one direction key per gesture; no native pointer or screen-control claim | Key serialization/dispatch tested; gesture UI not instrumented | NOT TESTED |
| Diagnostics | No per-control manual outcome history | Settings → Remote Diagnostics and Power Diagnostics; one-test confirmation, no sweep; separate delivery and verification, manual Yes/No | Pure control/power/transport regression tests; UI/state orchestration inspected | NOT TESTED |

No existing button with an intentionally empty click handler was found. Model-unsupported keys
can still be ignored by the real TV; client queue success is never presented as that key working.
No production release key/configuration exists; only the requested debug build is produced.

## Complete command inventory

All regular `RemoteControls` KeyButtons, D-pad IconButtons, text buttons and gestures call
`RemoteModel.send` → `CommandDispatcher.send` → either `TvConnection.send` / `Protocol.key`
or `ConsumerIrManager.transmit(38000, Protocol.irPattern(...))`. Power keys are intercepted by
`requestPower` → confirmation → `PowerPlanner` → shared dispatch/wake. Diagnostics uses the
same path after a per-test dialog. There is no separate simulated transport.

For **every row** below, error handling is: unknown catalog key → Unsupported; absent IR or
missing IR mapping → Unsupported with no transmission; unsupported carrier/driver exception →
Failed; non-foreground/unauthorized/ineligible network or socket rejection → Failed; actual
queue/driver acceptance → Command Sent + Requires Manual Verification. TV `ms.error` creates
an uncorrelated error entry; the API does not identify which recent key executed. IR frame data
below is command byte in `E0 E0 byte complement`, MSB first. Header profiles: 4.5 ms or legacy
9 ms, both 38 kHz. Community mappings are not AU7000 certification.

`C` = `ControlAuditTest` every-control serialization and exclusive transport tests;
`W` = `TvConnectionTest` every-catalog-key traversal of a real local MockWebServer WebSocket;
`I` = safe frame/complement tests, with independent reference samples for power/basic/exit/colors.

| Control / UI component | Handler / mapping | Transport / IR byte | Expected TV action (conditional) | Coverage |
| --- | --- | --- | --- | --- |
| Remote Toggle button / diagnostics | `send(KEY_POWER)` → power planner | Wi-Fi / IR `0x40` | Toggle actual power once after confirmation | C, W, I |
| Exit text button | `send(KEY_EXIT)` | Wi-Fi / IR `0xB4` | Exit | C, W, I |
| R color button | `send(KEY_RED)` | Wi-Fi / IR `0x36` | Red | C, W, I |
| G color button | `send(KEY_GREEN)` | Wi-Fi / IR `0x28` | Green | C, W, I |
| Y color button | `send(KEY_YELLOW)` | Wi-Fi / IR `0xA8` | Yellow | C, W, I |
| B color button | `send(KEY_BLUE)` | Wi-Fi / IR `0x68` | Blue | C, W, I |
| Volume + KeyButton | `send(KEY_VOLUP)` | Wi-Fi / IR `0xE0` | Volup | C, W, I |
| Volume − KeyButton | `send(KEY_VOLDOWN)` | Wi-Fi / IR `0xD0` | Voldown | C, W, I |
| Channel + KeyButton | `send(KEY_CHUP)` | Wi-Fi / IR `0x48` | Chup | C, W, I |
| Channel − KeyButton | `send(KEY_CHDOWN)` | Wi-Fi / IR `0x08` | Chdown | C, W, I |
| Mute KeyButton | `send(KEY_MUTE)` | Wi-Fi / IR `0xF0` | Mute | C, W, I |
| Home KeyButton / explicit app fallback | `send(KEY_HOME)` | Wi-Fi / IR `0x9E` | Open TV Home | C, W, I |
| Back KeyButton | `send(KEY_RETURN)` | Wi-Fi / IR `0x1A` | Return/back | C, W, I |
| Menu KeyButton | `send(KEY_MENU)` | Wi-Fi / IR `0x58` | Menu | C, W, I |
| Settings KeyButton | `send(KEY_SETTINGS)` | Wi-Fi / IR `0x58` | Settings via Wi-Fi; Menu alias over IR | C, W, I |
| Diagnostics Tools test (no normal remote button) | `send(KEY_TOOLS)` | Wi-Fi / IR `0xD2` | Tools | C, W, I |
| Source KeyButton | `send(KEY_SOURCE)` | Wi-Fi / IR `0x80` | Open source/input selector | C, W, I |
| D-pad / upward swipe | `send(KEY_UP)` | Wi-Fi / IR `0x06` | Up | C, W, I |
| D-pad / downward swipe | `send(KEY_DOWN)` | Wi-Fi / IR `0x86` | Down | C, W, I |
| D-pad / left swipe | `send(KEY_LEFT)` | Wi-Fi / IR `0xA6` | Left | C, W, I |
| D-pad / right swipe | `send(KEY_RIGHT)` | Wi-Fi / IR `0x46` | Right | C, W, I |
| D-pad OK / keyboard Enter | `send(KEY_ENTER)` | Wi-Fi / IR `0x16` | Confirm focused selection | C, W, I |
| Play KeyButton | `send(KEY_PLAY)` | Wi-Fi / IR `0xE2` | Play | C, W, I |
| Pause KeyButton | `send(KEY_PAUSE)` | Wi-Fi / IR `0x52` | Pause | C, W, I |
| Fast Forward KeyButton | `send(KEY_FF)` | Wi-Fi / IR `0x12` | Ff | C, W, I |
| Rewind KeyButton | `send(KEY_REWIND)` | Wi-Fi / IR `0xA2` | Rewind | C, W, I |
| Keypad 0 KeyButton | `send(KEY_0)` | Wi-Fi / IR `0x88` | Digit 0 | C, W, I |
| Keypad 1 KeyButton | `send(KEY_1)` | Wi-Fi / IR `0x20` | Digit 1 | C, W, I |
| Keypad 2 KeyButton | `send(KEY_2)` | Wi-Fi / IR `0xA0` | Digit 2 | C, W, I |
| Keypad 3 KeyButton | `send(KEY_3)` | Wi-Fi / IR `0x60` | Digit 3 | C, W, I |
| Keypad 4 KeyButton | `send(KEY_4)` | Wi-Fi / IR `0x10` | Digit 4 | C, W, I |
| Keypad 5 KeyButton | `send(KEY_5)` | Wi-Fi / IR `0x90` | Digit 5 | C, W, I |
| Keypad 6 KeyButton | `send(KEY_6)` | Wi-Fi / IR `0x50` | Digit 6 | C, W, I |
| Keypad 7 KeyButton | `send(KEY_7)` | Wi-Fi / IR `0x30` | Digit 7 | C, W, I |
| Keypad 8 KeyButton | `send(KEY_8)` | Wi-Fi / IR `0xB0` | Digit 8 | C, W, I |
| Keypad 9 KeyButton | `send(KEY_9)` | Wi-Fi / IR `0x70` | Digit 9 | C, W, I |
| ON button / diagnostics | `requestPower(ON)` | Conditional confirmed-standby IR / opted-in UDP wake; never blind KEY_POWERON | Wake attempt; endpoint/panel not assumed ON | C, W (serialization only), planner |
| OFF button / diagnostics | `requestPower(OFF)` → `KEY_POWEROFF` or consented IR toggle | Authorized Wi-Fi / local IR only | Request standby, not a measured OFF state | C, W, planner |
| Guide button | `send(KEY_GUIDE)` | Wi-Fi; IR Unsupported | Open guide if supported | C, W |
| Info button | `send(KEY_INFO)` | Wi-Fi; IR Unsupported | Show information if supported | C, W |
| Keyboard Backspace / diagnostic test | `keyboardBackspace` / `send(KEY_BACKSPACE)` | Wi-Fi; IR Unsupported | Remove character in focused TV field if supported | C, W, IME tests |
| Diagnostic Power ON key | `send(KEY_POWERON)` → `requestPower(ON)` | Planner; generic serializer supports key but production ON does not blindly send it | Safe wake plan rather than unavailable standby WebSocket | C, W, planner |

## Every remaining UI control, shortcut and gesture

| Screen / component | Handler / command | Transport / expected behavior | Errors / unsupported behavior | Coverage |
| --- | --- | --- | --- | --- |
| Remote / Apps / Devices / Settings bottom tabs | local `page` state | No packet; changes visible page | Local Compose navigation | Source review, compilation; no device UI test |
| Wi-Fi / Infrared selector | `mode(false/true)` | Switch transport; keyboard suppressed for IR | Hardware missing shown, no simulated IR | Dispatcher transport + network policy tests; UI review |
| Local LAN / Remote VPN selector | `remoteMode(false/true)` | Restrict connection policy; remote requires VPN+TLS | VPN presence alone does not prove route | `AccessPolicyTest` |
| Number keypad / Swipe pad toggles | local `numbers` / `touch` | Expand/hide panel only | No packet on toggle | UI source review |
| Swipe gesture | `detectDragGestures`, release threshold 40 px → UP/DOWN/LEFT/RIGHT | One direction key on release, dominant axis | Small/cancelled gestures send none; not native pointer | Direction protocol tests; no gesture instrumentation |
| Streaming grid: YouTube, Shahid, Yango Play, Netflix, WATCH IT, MBC, Prime Video | `launch(TvApp("",brand))` → `AppLauncher.resolve` | Wi-Fi `ed.apps.launch` actual TV/custom ID + DEEP_LINK/NATIVE_LAUNCH | No ID, ambiguity, IR, disconnected, API error → explicit feedback and manual Home option | `AppLauncherTest`, socket launch-type test |
| Other TV-installed app buttons | `launch(app)` | Same single launcher; current returned ID/type | Availability/launch not confirmed | App-list + launcher tests |
| Refresh installed apps | `refreshApps` → `ed.installedApp.get` | Actual returned array/nested array only; eight-second timeout | Empty list is an actual empty response; no response is failure/unsupported possibility | Socket app-list test; scheduler inspection |
| Pin / Unpin installed app | `toggleShortcut` | Local preferences, no TV command | Same-ID toggle, removes exactly that pinned ID | Source review; persistence not device tested |
| Custom name / ID / launch-type switch / Save | local fields + `saveShortcut` | Local ID/type configuration, replaces same ID; explicit override | Reject blank/control-character/oversized IDs; configuration is not installation proof | Resolver validation/override tests; save reviewed |
| Remove shortcut | `toggleShortcut` | Local preferences only | Removes matching ID | Source review |
| Open Home fallback | `send(KEY_HOME)` | Explicit single Home key; user navigates | No automatic macro, no app-launched claim | C, W |
| Keyboard button | `openKeyboard` | Opens phone keyboard panel after authorized network check | IR/no authorized channel rejected; app focus is not invented | IME + access policy tests, source review |
| Draft text field / Gboard | `editDraft` | Phone draft only; Unicode, multiline, languages via chosen IME | Maximum bounded length; no automatic typing while editing | IME text tests |
| TV-field confirmation checkbox | `confirmField` | Local consent gate | Required before sending, no inferred app field | Source review / keyboard safeguards |
| Use TV-reported text | `adoptTvText` | Explicit copy of a received supported IME event into phone draft | Not screen scraping; unsupported apps may send no event | IME event parsing tests |
| Resend confirmation checkbox | `confirmResend` | Local consent, no packet | Avoid accidental duplicate appending/replacement | Source review; no Compose test |
| Keyboard Send | `sendDraft` → `sendText`, Samsung base64 text protocol | Wi-Fi only; one explicit text send; no credentials/draft logged | Empty, duplicate-without-consent, unfocused, background, disconnected rejected | `ImeProtocolTest`, `ImeWebSocketTest` |
| Keyboard Enter / Search / Done | `keyboardEnter` → SendInputEnd + `KEY_ENTER` | Wi-Fi; two distinct completion protocol messages, no automatic retry | Reports partial queue failure; physical submit unconfirmed | IME sequence + C/W Enter tests |
| Keyboard TV Backspace | `keyboardBackspace` → `KEY_BACKSPACE` | Wi-Fi focused field | App may ignore it; separate from draft deletion | IME / C / W |
| Clear phone draft | `clearDraft` | Local draft only, does not clear TV | No false TV-clear claim | Source review |
| Keyboard Close / dismiss | `closeKeyboard` | Clears session, optional input-end notification | No text resend; background clears drafts | Existing IME tests + lifecycle review |
| Discover TVs | `scan` / SSDP + Samsung device HTTP API | LAN discovery, candidate verified; multicast lock | Disabled during scan; not available as remote subnet scan; failure/empty handled | Discovery/policy source review; no LAN hardware |
| Discovered / saved TV Connect | `connect(tv)` | Selected target pairing/TLS, saved credentials, bounded reconnect | Only private IPv4; no public forwarding; timeout/denial not success | `TvConnectionTest`, `SecurityTest`, `AccessPolicyTest` |
| Manual IP / MAC / TLS switch / Connect | `manual` → `connect` | Explicit private IPv4; remote forces TLS | Input validation, legacy plaintext opt-in only; no silent downgrade | IP/security/policy tests |
| Forget TV | `forget` → store removal | Local token/pin/wake-setting removal, disconnect when selected | No remote device deletion claim | Source review; Android Keystore not hardware tested |
| Disconnect | `disconnect` | Close socket, cancel retry/timeout, clear keyboard | Power becomes Unknown, not OFF | Socket disconnect + power observation tests |
| Trust and pair / Cancel certificate dialog | `trust(true/false)` | Persist explicitly approved SHA-256 pin or disconnect | Unknown/changed pins stop handshake, no trust-all fallback | `SecurityTest` pin tests |
| Automatic keyboard switch | `automaticKeyboard` | Persist event-driven keyboard preference | Requires foreground/authenticated compatible IME event; manual button retained | IME/policy tests |
| IR profile switch | `profile` | Select header only, no transmission | 38 kHz range checked when actual transmission attempted | Protocol header/frame tests |
| Test IR volume + | `mode(true)` + `requestTest(KEY_VOLUP)` | Confirmation then actual local driver transmission | Absent emitter, wrong carrier, remote context rejected | C transport and fixture tests; UI reviewed |
| Settings → Remote / Power Diagnostics | local section state | Dedicated diagnostic view; no packet merely on opening | Diagnostic view buttons return to Settings sections | Source review and compilation |
| Check network, pairing, IR, app-list API | `diagnosticChecks` | Read current network/auth/IR; request app list when authorized | No disruptive commands; API response not panel/app launch confirmation | Mock authorization/app list; source review |
| Diagnostic control tests | `requestTest` → confirm → `send` | Same registry/dispatcher as remote; one command per consent | Cancel sends nothing; power uses dedicated dialog | C/W/planner tests; dialog reviewed |
| Diagnostic streaming tests | `requestTest(app:brand)` → confirm → `launch` | Same central app launcher, no automatic Home fallback | Unsupported/missing ID visible | All-brand launcher tests; confirmation reviewed |
| Diagnostic keyboard test | `requestTest(keyboard)` → confirm → check/open panel | Authorized socket check; user focuses TV field and explicitly sends | Does not invent a text-field acknowledgement or type test text automatically | Existing IME tests + source review |
| Diagnostic Yes / No | `markDiagnostic(sequence,response)` | In-memory user observation; labelled user-reported, not machine verified | Delivery status retained independently; capped 60 records, no tokens/text | Source review; no physical results recorded here |
| Power wake MAC / standby checkbox / Save | `savePowerSettings` | Per-TV opt-in and active-interface unicast MAC; no wake until explicit ON | Reject zero/multicast/broadcast/invalid MAC; saving is not capability proof | MAC/planner tests; persistence reviewed |
| Power ON / OFF / Toggle confirmation / Cancel | `confirmPower` / `dismissPower` | Preview exact plan; physical-state checkbox for local IR, one request | Five-second shared guard; no blind/automatic fallback/replay | Power planner/gate tests; dialog reviewed |
| Voice | Gboard voice typing only | External keyboard, explicit Send afterward | No microphone permission or native TV voice control implemented | Manifest verifier, UI review |
| Macros / quick-control widgets | Not implemented, no handler or component | None | No advertised background macros/widgets | Source inventory |

## Power behavior and physical verification procedure

**ON while in standby:** select a TV profile first. If a local IR emitter is available,
physically check standby, open ON, check the explicit standby confirmation, point at the TV,
and send once. This sends the established Samsung toggle, **not an invented discrete IR ON**.
If no IR physical-state consent is provided, Wi-Fi ON requires explicit saved network-wake opt-in
and a valid active-interface MAC. Configure the TV's network standby / Power On with Mobile if
available (often General → Network → Expert Settings; wording/availability varies by firmware).
UDP magic packets work only if the interface, standby firmware, router/AP and LAN permit them.
Wired WoL and wireless WoWLAN are not interchangeable guarantees. Some APs suppress broadcasts;
Wi-Fi standby can disable reception. Fully unplugged TVs cannot be woken by IR or network.

The app does **not** assume the standby WebSocket is reachable. Wake sends magic packets
(a three-packet identical UDP burst, never toggle keys); it does not claim ON. Use Devices →
Connect afterward to probe authorization, then observe the panel separately. A channel response
proves only that the control endpoint is awake. If already authorized, ON sends no power key unless the user explicitly confirms physical standby for a local IR wake.
No blind wake/toggle retry, periodic probing service, or automatic app launch is introduced.

**OFF while ON:** explicit confirmation sends one `KEY_POWEROFF` on the authorized WebSocket.
If unsupported by that firmware, check the actual TV and use the explicitly selected toggle
only after observing its state; no automatic fallback is sent. Without a channel, local IR OFF
is available only when the user confirms the TV is physically ON. Recent successfully queued
OFF produces **Standby Suspected**, not confirmed OFF. A subsequent disconnect preserves that
hint briefly; unrelated disconnections yield Unknown. An exhausted reconnect/authorization
probe yields Offline (endpoint unreachable), never a proof of OFF. Successful authorization
sets Connected; it does not measure the panel. User Yes/No is explicitly recorded as user evidence.

**Outside home:** IR cannot reach the TV. An authenticated phone VPN and an always-on authorized
home router/NAS/computer VPN gateway/subnet router are needed for the private TV subnet. Pair at
home, retain certificate pinning, route TCP 8002 through the VPN. Never publish 8001/8002 on the
internet. OFF needs an authorized routed connection. ON uses best-effort unicast wake to the TV
IP; ordinary L3 VPNs do not forward LAN broadcast and sleeping-device ARP entries can expire.
A compatible home gateway wake facility/ARP configuration may be needed; this app does not
provide a gateway agent or claim that VPN presence proves routing. If that path fails, the
UI reports an unconfirmed wake request; configure the gateway separately. No SmartThings
power/keyboard capability is assumed.

For actual AU7000 testing, run each diagnostic **individually**, observe the TV, and mark Yes/No.
Test OFF with TV visibly ON, ON after standby, IR with Wi-Fi disabled, app shortcuts with apps
actually installed in that region, keyboard in YouTube/Shahid/Yango real text fields, denied
pairing, router interruption, and VPN away from home. Also test unplugged-TV behavior. Record
firmware, connection type, MAC interface, phone regional hardware and IR profile alongside
results. None of those hardware steps has been carried out in the cloud.

## Protocol evidence and test boundaries

- SamsungTVWS community reference pinned at
  [`e48d6377faede37db1f034d726a079b9d8034fac/remote.py`](https://github.com/xchwarze/samsung-tv-ws-api/blob/e48d6377faede37db1f034d726a079b9d8034fac/samsungtvws/remote.py):
  `ms.remote.control`, installed-app request, `ed.apps.launch`, and DEEP_LINK versus NATIVE_LAUNCH.
  The app-list fixture includes `app_type=2`; its sample YouTube ID is **not** hard-coded here
  as a universal regional ID. `app_type` 0/1 uses native, 2 uses deep link; unknown type is
  labelled and defaults to deep link, with an explicit custom override available.
- LIRC community reference pinned at
  [`e4a758048908b7e1a571dc2a89c409a33398f2f6/BN59-00865A.xml`](https://github.com/probonopd/lirc-remotes/blob/e4a758048908b7e1a571dc2a89c409a33398f2f6/samsung/BN59-00865A.xml):
  Samsung power/basic keys, Exit `B44B`, colors `36C9/28D7/A857/6897`. Duplicate Info labels
  in the source are ambiguous; they are not treated as verified Guide/Info IR codes.
  No discrete ON/OFF IR codes were established by this review.

These are community protocol/remote captures, not a Samsung AU7000 certification. Tests use a
real local OkHttp MockWebServer WebSocket, including authorization/denial/timeout events,
actual JSON framing for every catalog key and both app launch types, queue rejection before
and after disconnect, API error notification, and absence of extra key messages. Existing
TLS pinning, redirect, token-scope separation, IME lifecycle/encoding, reconnect-policy and
IP/network-policy tests remain. UI gestures, Android Keystore persistence, carrier hardware,
app-list timer orchestration and actual TV effects are source-reviewed, not instrumented.

## Security, SDK, signing and build evidence

The four required platform permissions remain INTERNET, ACCESS_NETWORK_STATE,
CHANGE_WIFI_MULTICAST_STATE and TRANSMIT_IR. AndroidX adds its signature-protected receiver
permission. No microphone, location, nearby-device, storage, accessibility, overlay, installer,
notification or foreground-service permission was added. No runtime dependency was added.
Resolved graphs remain 88 runtime / 92 test-inclusive modules; the refreshed reviewed GitHub
advisory lookup found **0 matches / 0 query errors**. This is not malware certification or a
complete vulnerability scan. Historical removal and credential fixes are in SECURITY_AUDIT.md.

Compile/target SDK 35, min SDK 26, JDK 17. Target 35 can install on Android 16; physical Android
16 compatibility, OEM restrictions and regional Consumer IR exposure are not proven by compilation.
No target-SDK downgrade or Play Protect bypass is implemented. The custom first-use certificate
pin trust manager and legacy opt-in local cleartext support retain the two documented lint
warnings; TLS is mandatory for remote VPN. No analytics, executable downloads, WebView, shell,
or hidden background control was found in application sources.

Validation: **48 unit tests, 0 failures/errors/skips**, `assembleDebug`, `testDebugUnitTest`,
`lintDebug` (0 errors, 2 documented warnings). Standalone artifact verification checks APK v2/v3
signature, ZIP CRC, manifest permission allowlist, ARM64 native-library and ZIP 16 KiB alignment.
Output: `app/build/outputs/apk/debug/app-debug.apk`; delivery copy and public evidence:
`app/build/outputs/delivery/`. CI uploads that directory as `SamsungUniversalRemote-APK`.
`verification.json`, `SHA256SUMS` and `signature.txt` describe the exact downloaded APK.

The workspace debug key is reused: public certificate SHA-256
`263d59c943da459eaec4c9f4be7c0a53733e539f3a4cf153107846d3c5fe7e02`.
A clean GitHub runner creates its own debug key, so compare **that artifact's** certificate
before expecting an in-place upgrade. No private signing key is committed. No valid persistent
release signing configuration is present; this is intentionally a standalone **debug APK**.
An Android debug signature verifies integrity and identity of that build; it does not establish
Google reputation or guarantee Play Protect approval. Play Protect checks remain in place.

## Modified files

`app/build.gradle.kts`; `MainActivity.kt`; `Protocol.kt`; `RemoteModel.kt`; `SecureStore.kt`;
`TvConnection.kt`; new `AppLauncher.kt`, `ControlEngine.kt`, `DiagnosticsUi.kt`;
`TvConnectionTest.kt`; new `AppLauncherTest.kt`, `ControlAuditTest.kt`;
`audit/dependency-advisories.json`; `tools/verify_apk.py`; `.gitignore`;
`README.md`; `AU7000_GUIDE.md`; `SECURITY_AUDIT.md`; this `BUTTON_AUDIT.md`.
Kotlin files above live under `app/src/main/java/com/bluewave/remote/` or
`app/src/test/java/com/bluewave/remote/` as appropriate. Dependency/build workflow settings
and the application ID are preserved except for the version increment. The existing workflow
already builds pushes to this feature branch, main, and manual runs; no workflow change was needed.
