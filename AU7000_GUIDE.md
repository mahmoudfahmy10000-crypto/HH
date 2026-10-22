# AU7000 remote access and smart keyboard — version 1.1.0

> For version 1.2.0, [BUTTON_AUDIT.md](BUTTON_AUDIT.md) supersedes power, app-launch, diagnostics and test details. Older results below are retained as historical evidence.

Target: Samsung AU7000 (2021 Tizen), Redmi Note 14. Package remains `com.bluewave.remote`.
This is a native Android debug APK. No screen mirroring, live-TV streaming, video capture,
new microphone permission, cloud account collection, or public TV-port exposure was added.
This guide describes implemented client behavior and **unverified physical-TV capabilities**.

## What works in the implemented client

- Existing Samsung WebSocket pairing/token encryption/certificate pinning, IR profiles,
  navigation, volume, power, playback and TV app shortcuts remain.
- Local SSDP discovery starts automatically when the active app has a local Wi-Fi/Ethernet
  transport and no selected TV. Manual discovery and IP entry remain available.
- Local LAN and Remote VPN profiles, transport diagnostics and stored TV profile selection.
  Remote mode requires Android to report an active VPN and requires TLS on TV TCP 8002.
- A dedicated Keyboard button, transient Arabic/English Unicode draft, Android/Gboard input,
  explicit Send, Enter, TV Backspace, Clear draft and Close controls.
- Automatic phone-keyboard activation on compatible `ms.remote.imeStart` events while resumed;
  manual opening remains available when TV apps emit no IME events. Auto activation can be
  disabled in Settings; it never disables manual mode.
- IME update/done/end handling, optional explicitly adopted TV-reported text, bounded text size,
  explicit re-send consent to avoid accidental duplicate insertion, foreground-only connections
  and bounded reconnection delays (2, 4, 8 seconds; three consecutive retries).

Compilation and mock protocol tests do not establish that these features have executed on a
physical AU7000. Physical test outcomes below are intentionally **not tested**.

## Remote-access architecture: authenticated VPN plus Samsung TLS

```text
Redmi app -> Android VPN app -> authenticated encrypted VPN tunnel
          -> always-on home subnet router -> AU7000 private IP, TLS TCP 8002
```

The APK does not implement or provision a VPN server, relay, port-forwarding service, or
SmartThings cloud integration. Samsung's local WebSocket endpoint is not a public internet
API. SmartThings capability access varies by device/API; this app does not assume that
SmartThings offers D-pad keys, arbitrary app launching or the Samsung text protocol.

**External prerequisite:** one authorized home router, NAS, Raspberry Pi or computer must stay
online and provide VPN routing to the TV. It needs home power, home internet, LAN access to the
TV, authenticated phone access, and a return path (usually subnet-router SNAT). If your router
cannot do this and you have only the phone, additional home hardware/service is required;
Android-only changes cannot remove that requirement. No actual home VPN was available to
validate during this cloud task.

### Example setup: your own Tailscale subnet router

1. Give the AU7000 a stable DHCP reservation, for example `192.168.1.20`. Record its private IP
   and MAC from your own router/TV. Do not use a public address or the gateway's overlay IP as
   the TV address.
2. Configure Tailscale on your authorized always-on home device using the official
   [subnet-router instructions](https://tailscale.com/kb/1019/subnets). Enable IP forwarding,
   advertise the TV's actual home subnet, approve that route in your own Tailscale admin
   console, and retain subnet-route SNAT unless you deliberately configure return routes.
   Preserve any existing home routes and security policy. A supported router/NAS plugin may
   provide the same configuration without a PC.
3. Restrict VPN grants/ACLs to the phone/user you authorize and the TV's private IP, TCP 8002.
   UDP 9 to that TV IP is optional for best-effort unicast Wake-on-LAN. Do not grant unrelated
   peers unrestricted home-network access. Keep VPN peer authentication and device approval on.
4. Install the official Tailscale Android app on Redmi, authenticate to your own tailnet,
   approve its Android VPN connection and accept the advertised subnet route. The VPN app,
   not this remote, holds its VPN credentials. No token is pasted into this remote APK.
5. First pair this remote on home Wi-Fi in **Local LAN** mode. Check the TV IP, approve its TLS
   fingerprint and choose Allow on the actual TV. Save the TV before leaving home. If a later
   TV authorization prompt is necessary, someone must approve it at home.
6. On mobile data or another Wi-Fi, connect your authorized VPN. In this remote choose
   **Remote VPN** and connect to the same saved private TV IP. Remote mode forces TLS 8002 and
   uses the same encrypted TLS pairing token and certificate pin. A discovered local TV can
   be selected first, then switched to Remote VPN. Discovery multicast normally does not cross
   the VPN; use saved/manual IP there.
7. Verify a benign command while physically observing the TV or with an authorized person at
   home. Only Samsung's authorization event marks the app connected. A detected VPN or queued
   command alone does not establish route health or TV execution.

A properly configured WireGuard server on an authorized home router is another option. Use
[WireGuard's official setup documentation](https://www.wireguard.com/quickstart/), unique peer
keys, an authenticated encrypted phone peer, a route/AllowedIPs covering the TV, return routing
or SNAT, and firewall restrictions equivalent to the above. Only the VPN endpoint should be
reachable externally; **never forward/publicly expose TV ports 8001 or 8002**. This task does
not create accounts, install home infrastructure, or change your router/VPN policy.

### Detection, limitations and power

- `ACCESS_NETWORK_STATE` is now intentionally required to observe default-network transport,
  VPN loss and reconnection. No Wi-Fi SSID/location permission is requested. Wi-Fi alone cannot
  prove you are on your home network; the UI says **home network not verified**.
- Android reporting a VPN cannot prove its peer authentication, subnet routes, ACLs, split-tunnel
  inclusion or gateway security. These are external operator prerequisites. If Android does not
  report VPN for the app's default network, remote mode blocks commands rather than guessing.
- Remote mode stops on missing VPN or network, refuses plaintext, and does not fall back to
  public IPs. TLS pinning still rejects unknown/changed TV certificates. Turning VPN off does
  not silently switch a Remote VPN profile into local legacy mode.
- Avoid overlapping home/visited private subnets or configure an appropriate host route through
  your VPN. Wrong routes, home gateway downtime, TV authorization, TV firewall restrictions,
  TV standby and VPN DNS/route configuration cannot be solved solely by the APK.
- Power toggle/off works only if the TV accepts the connected remote key. Local Wake sends a
  broadcast; Remote VPN Wake sends best-effort unicast UDP magic packets to the private TV IP.
  A sleeping TV may not answer ARP or accept unicast wake. If that fails, use an authenticated
  wake feature on your always-on home router/gateway. This app does not provide that gateway
  feature, guarantee wake-up, or claim wake acknowledgement. IR always needs physical proximity.
- Moving the app out of the resumed state closes its socket and keyboard, clears the draft,
  cancels discovery/retries, and reconnects when resumed if allowed. This deliberately avoids
  a hidden persistent remote-control/background keyboard service. Network changes also clear
  transient keyboard text and re-evaluate policy before reconnection or commands.

## Samsung text protocol and evidence

The independently implemented message shapes follow the public SamsungTVWS protocol reference,
not an official Samsung compatibility certification:

- [SamsungTVWS remote protocol, pinned reference commit](https://github.com/xchwarze/samsung-tv-ws-api/blob/e48d6377faede37db1f034d726a079b9d8034fac/samsungtvws/remote.py)
- [Event names at that commit](https://github.com/xchwarze/samsung-tv-ws-api/blob/e48d6377faede37db1f034d726a079b9d8034fac/samsungtvws/event.py)

No code/package from that project is bundled as a dependency. Protocol facts used here:

```json
{"method":"ms.remote.control","params":{"Cmd":"<Base64 of UTF-8 bytes>","DataOfCmd":"base64","TypeOfRemote":"SendInputString"}}
{"method":"ms.remote.control","params":{"TypeOfRemote":"SendInputEnd"}}
```

For compatibility, before the first text message per session, the app queues
`ms.channel.emit` / `custom.remote.textReceived` to `broadcast`, as used by the reference.
That is an event on the authenticated TV WebSocket channel, not an internet/UDP broadcast.

- `ms.remote.imeStart`: recognizes a TV IME session and may open the phone editor only while
  resumed and automatic mode is enabled (an already-open manual editor remains usable).
- `ms.remote.imeUpdate`: optionally offers TV text for **explicit adoption** without overwriting
  the local draft or echoing it back. The conservative parser handles string `data.text` or
  `data.value`; it Base64-decodes only when `data.encoding` explicitly says `base64`. Unknown
  schemas are ignored. These update fields and this event's emission are **not AU7000-validated**.
- `ms.remote.imeDone` / `ms.remote.imeEnd`: close the phone editor and discard transient text;
  they do not fabricate confirmation that earlier text reached the intended visible field.
- The cited reference explicitly recognizes Start/End; Update/Done handling is defensive support
  for firmware/app variants, not evidence that AU7000 emits them. There are no captured AU7000
  IME payloads in this repository. Test messages are synthetic fixtures only.

### Using the keyboard safely

Select a TV search/text field, then open **Keyboard** manually if automatic activation does
not happen. Confirm the intended TV field in the panel, compose with Gboard (Arabic, English,
mixed text, numbers, symbols and voice dictation), then press **Send**. Gboard owns language
selection, microphone access and voice processing; this APK requests no microphone permission.
Availability/privacy of Gboard voice typing depends on its own permissions/settings/service.

Automatic mode means **activation**, not uncontrolled live keystroke transmission. There is
no universal confirmed append/replace/cursor model across Tizen apps. Text is sent explicitly;
a second Send requires explicit consent because it may append or replace. Gboard backspace
edits the phone draft. **TV Backspace** queues `KEY_BACKSPACE` as a best-effort key; AU7000/app
support is unverified. **Clear draft** clears the phone only; empty text is not falsely used
as a TV erase command. **Enter** queues SendInputEnd then KEY_ENTER. **Close** requests
SendInputEnd for an active/confirmed session, without pretending it submitted a TV search.
For an app that ignores input/deletion, use the existing D-pad with its on-screen keyboard.

No automatic retries replay text. The draft is never saved in preferences/files or included in
diagnostics. It is cleared on backgrounding, mode/TV changes, disconnection or IME completion.
The editor requests Android's secure-window flag. Known password hints suppress its automatic
editor and content, but schemas vary: do not enter passwords or payment information here.
Server error messages are generic rather than echoing possible user text/credentials.

## Physical AU7000 acceptance matrix — no invented results

Every cell below is **Not tested (physical TV unavailable)**. Complete it for your exact AU7000
firmware, app versions, Redmi Android build, Gboard version, and Local LAN/Remote VPN profile.
Record Pass / Fail / Unsupported / Not tested separately.

| TV application | Automatic activation | Manual Arabic/English/mixed Send | Enter | TV Backspace | TV-to-phone text update |
| --- | --- | --- | --- | --- | --- |
| YouTube search | Not tested | Not tested | Not tested | Not tested | Not tested |
| Shahid search | Not tested | Not tested | Not tested | Not tested | Not tested |
| Yango Play search | Not tested | Not tested | Not tested | Not tested | Not tested |
| Samsung Internet benign text field | Not tested | Not tested | Not tested | Not tested | Not tested |
| Samsung native search | Not tested | Not tested | Not tested | Not tested | Not tested |

Procedure for each app:

1. Confirm existing Home/D-pad/volume and the app shortcut still work; unsupported launch IDs
   must offer Home/navigation. Enter that app's search field using the TV or D-pad.
2. With automatic mode enabled and this app resumed, record whether the phone editor opens.
   If it does not, record automatic activation as unsupported/unobserved and try manual mode.
3. Use a harmless fixture such as `مرحبا Samsung 123 🎬`; confirm the TV field and Send once.
   Observe actual visible TV text, Arabic shaping and symbol/number integrity. Do not equate
   "queued" feedback with successful entry. Record whether full text inserts or replaces.
4. Use Enter and TV Backspace separately; observe the TV's actual result. Also test Gboard draft
   editing, Clear draft (TV must remain unchanged), cancellation and optional TV text adoption.
5. Background the app before focusing a TV field. The phone keyboard must not unexpectedly
   open; returning must not resurrect a private draft or replay sent text. Disable automatic
   mode, then verify manual opening still works.
6. Repeat with an authorized VPN over mobile data/another Wi-Fi. Disconnect VPN mid-session:
   commands must stop, typed text must not replay, and reconnection must remain bounded.
7. Test denied TV pairing, wrong IP, gateway offline, TV asleep, changed certificate, IR without
   Wi-Fi, and app/TV restarts. Test standby wake separately; record unicast wake failure honestly.

Do not capture or publish pairing tokens, VPN keys, passwords or private IME payloads as evidence.
A sanitized event name/schema description is sufficient to diagnose unsupported session formats.

## Build, tests and signing

Use the existing Java 17 / SDK 35 toolchain and Gradle wrapper:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
ANDROID_HOME=/path/to/android-sdk python3 tools/verify_apk.py
```

`app/build/outputs/apk/debug/app-debug.apk` is the built APK.
`app/build/outputs/delivery/app-debug.apk` is the verified delivery copy with `SHA256SUMS`,
`verification.json`, signature/alignment evidence, this guide and the baseline audit report.
The verifier checks package/version metadata, v2/v3 signatures, ZIP integrity, all native ELF
16 KiB alignment and ZIP 16 KiB alignment, and the explicit packaged permission allowlist.

The current local build passed **35 tests**, 0 failures, 0 errors, 0 skipped. Android Lint completed
with 0 errors and 2 retained baseline warnings (Compose parameter ordering and the documented
local-TV certificate trust manager). Existing tests plus Unicode, IME session/privacy, VPN policy
and real local mock-WebSocket tests run in CI. They do not certify physical AU7000 behavior. No new runtime dependencies were added;
the previous reviewed-advisory module inventory remains the same. `ACCESS_NETWORK_STATE` is the
only new permission relative to the 1.0.1 audit and has the transport/lifecycle purpose above.

The existing local debug keystore is reused; compare its public fingerprint in generated
`signature.txt`/`verification.json` with earlier local builds. GitHub-hosted clean runners still
create independent debug keys, so their APKs are not guaranteed to upgrade earlier installations
in place. No legitimate release signing key/configuration was provided or found; no fake
release signed with the debug key is produced. Private signing material is never committed or
uploaded. Valid signing does not bypass Play Protect or establish Google approval.

Work is on `feature/au7000-vpn-smart-keyboard`; main is not merged automatically. The workflow
now builds pushes to main and feature branches, and supports manual dispatch with branch
selection. Download **SamsungUniversalRemote-APK** from the successful feature-branch run.
