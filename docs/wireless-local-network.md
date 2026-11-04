# targetSdk 37 and ACCESS_LOCAL_NETWORK: Emulator Check Android 17

Date: 2026-10-14. Build: debug, branch `claude/wireless-e1-transport`, compileSdk/targetSdk 37. Emulator AVD `A17` (API 37). Yegor's solution: go to 37 to E2.

## What's in the code

- `build.gradle.kts`: compileSdk 37, targetSdk 37 (from stash `d24587f3`, ibid debug command `aa_probe`, `aa/AaProbe.kt`).
- Manifesto: `android.permission.ACCESS_LOCAL_NETWORK`.
- Debug commands in `MainActivity` (`--es cmd …`): `net_probe` (`link/diag/NetProbe.kt`) broadcasts the UDP “CarlifeHost” beacon on 7999 per IPv4 interface, supports unicast through `--es to <ip>`, and runs the UDP 8999 responder; all events go to `WL`. `net_perm` requests the system permission.
- debug tcp6: `--es debug_tcp6_bind 0.0.0.0` does not only listen to loopback; the resolution state is written in `WL link begin`.
- onboarding and user permission request: E4 (together with `NEARBY_WIFI_DEVICES` and BT).

## What the platform says about permission

`pm list permissions`: `ACCESS_LOCAL_NETWORK`, `protectionLevel:dangerous`, group `NEARBY_DEVICES`. System dialogue when requested is common to the group: "Allow Headswap to find, connect to, and determine the relative position of nearby devices?? After "Allow» `granted=true, USER_SET`.

## Results

Communication with the PC via NAT emulator (`adb emu redir`): The PC is visible to the phone as 10.0.2.2, i.e. as a local area network node rather than loopback.

| Check | Without resolution | With resolution |
|---|---|---|
| Lighthouse UDP 7999: broadcast 10.0.2.255 (eth0, wlan0) and unicast 10.0.2.2 | `sendto failed: EPERM` on each shipment | Sent, PC accepted 8 from 8 |
| Responder UDP 8999: no datagram from the | PC came (0 from 8), no errors | 8 from 8, JSON ready response reached the | PC
| Incoming TCP on six ports (bind 0.0.0.0; head unit = pc-headunit `--transport tcp6`) | Bind succeeds and the PC appears connected through emulator NAT, but the app receives no accept and no error | 6/6 connections in about three seconds via 10.0.2.2, handshake, and video at about 30 fps |
| tcp6 on 127.0.0.1 via `adb forward` | 6/6, video 30 fps | - |
| AA-127.0.0.1:5277 | server is working (verified by 2026-10-12, `aa_probe`) | - |

## Conclusions for E2 and E6

1. Loopback does not require resolution: USB- mode (AA on 5277) and debug-stand via `adb forward` work on 37 without it.
2. Wireless branches without permission do not work at all: a beacon and the answer to 8999 Falling with EPERM, incoming TCP from head unit silently does not reach. The incoming refusal is not visible to the application, so the diagnosis is obliged to write a state of resolution (already in `WL link begin`) and show a hint if for N with none accept at `ACCESS_LOCAL_NETWORK=denied`.
3. The permission is requested by a single “Devices Nearby” group dialog along with `NEARBY_WIFI_DEVICES` and BT: in onboarding E4 one step.
4. On Android 16 and below (phones of most testers, Redmi Egor) the resolution is not valid: only the behavior of Android 17 is checked.

## What not checked

Real Wi-Fi (hotspot, P2P-interface), real device on Android 17, behavior when revoking permission in the middle of a session.
