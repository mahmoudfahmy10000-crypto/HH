# Physical device acceptance checklist (not executed in the cloud)

- Install the APK on the Redmi Note 14 and check layout, scrolling, navigation labels and haptics.
- Deny TV pairing: no connected state, credentials not saved. Then allow pairing: actual channel authorization event is required.
- Reopen app: encrypted saved token reconnects. Forget TV: credentials and certificate pin disappear.
- Wrong IP, powered-off TV, guest Wi-Fi: useful errors and bounded retries, never fake success.
- Change TV certificate: explicit new trust decision; cancel must stop connection.
- Try volume, D-pad, Home, source, digits and playback; inspect actual TV response.
- Test Wake-on-LAN with real TV MAC and network standby enabled; confirm that send feedback alone does not claim TV power-on.
- Refresh app list, launch an installed app, pin/remove shortcut. Missing Yango/WATCH IT/Shahid should offer Home navigation rather than claim launch.
- IR: check actual emitter detection, both header profiles, volume test and power toggle with Wi-Fi disabled. Phone without emitter must not report IR success.
- Rotate phone, increase font size, disconnect Wi-Fi mid-session, and restart TV.

Release signing, physical device acceptance and model-specific certification remain necessary before Play Store distribution.
