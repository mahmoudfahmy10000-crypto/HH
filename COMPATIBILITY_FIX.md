# 1.2.1 — visible keyboard and app shortcut setup

The user reported that the Keyboard control was missing and app shortcuts did not work.
The previous remote used an icon-only Keyboard button below the playback controls in a long
scrollable page. That was a concrete discoverability defect. Version 1.2.1 adds an always-labelled
Keyboard action in the bottom navigation on every screen, retains a labelled text button on
Remote, and displays the version in the header. Opening the keyboard still needs an authorized
Wi-Fi connection; IR cannot transmit text. No automatic TV IME event is required to open it.

A second UI defect hid error messages above scrolled content, and no selected TV silently
rejected some app actions. The fix shows messages in an always-visible snackbar, including
repeated failed taps, and explicitly directs unpaired/disconnected users to Devices.

The precise physical app-launch failure is **not established**: no TV response, network mode,
or installed-app list from the user's TV has been supplied. Existing serialization matches the
pinned SamsungTVWS reference in BUTTON_AUDIT.md. The following feasible client fixes are included:

- Tapping a shortcut when no app metadata is loaded requests the TV app list, waits up to eight
  seconds, then resolves and queues at most one launch using the actual response. No guessed
  universal IDs are used. No list response or missing app produces an explicit error/fallback.
- Repeated taps while waiting do not add duplicate launches. A TV switch, disconnect, background
  suspension, transport change or timeout clears the pending intent. It is not replayed later.
- Apps lists each returned TV app with an **Assign** menu. Assign the actual regional app to
  YouTube, Shahid, Yango Play or another branded shortcut without manually copying IDs. It keeps
  the TV's launch-type metadata. Assignment saves configuration; it does not claim a launch.

Validation: assembleDebug, testDebugUnitTest and lintDebug; 50 unit tests (including two new
pending-intent regressions), zero failures/errors/skips, lint zero errors/two existing warnings.
The standalone APK verifier checks v2/v3 signature, permission allowlist, CRC and 16 KB alignment.
Application ID remains com.bluewave.remote; versionCode 5, versionName 1.2.1. No permission or
runtime dependency was added. Debug signing remains; no Play Protect bypass is introduced.

**No physical AU7000 or Redmi Note 14 was available.** Mock protocol tests do not demonstrate that
YouTube/Shahid/Yango opens or accepts text on this TV/firmware. To verify:

1. Confirm the header says 1.2.1 and the bottom bar visibly includes **Keyboard**.
2. Select Wi-Fi, connect/authorize the TV, and check whether Home/Volume work.
3. Select a TV search field, tap bottom-bar Keyboard, confirm the selected field, type a short
   non-sensitive word and press Send. Record the actual response, not just queue status.
4. Open Apps, refresh the actual installed-app list, launch a returned app directly, then Assign
   that app to its branded shortcut. If no list arrives or the direct launch fails, report the
   exact displayed message and TV firmware. Do not substitute a mock-test pass for this step.

See BUTTON_AUDIT.md for the historical full inventory and prior protocol/security analysis.
