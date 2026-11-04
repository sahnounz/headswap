# 0014. Wireless CarLife: CarTransport, Wi-Fi Direct and Beacon Network Machine

- Status: adopted
- Date: 2026-10-15 (2.0.0), Refinements to 2026-10-30
- Sources: PR #24, #30, #32, #38, #48, #59, #68, #72, #117, `docs/wireless-carlife.md`, `link/CarTransport.kt:7-26`, `link/bt/P2pJoin.kt`, `link/wifi/HotspotBeacon.kt`, `link/wifi/HotspotDetect.kt`

## Context

CarLife by wireless connection is arranged differently. Mazda G6S - Bluetooth RFCOMM plus Wi-Fi Direct. VW BT 1420 network itself
machine, the phone connects to it and send UDP-beacon (port 7999). The Honda Vezel 2024 has no wireless (`docs/wireless-carlife.md`).
The protocol (`CarLifeSession`) should not know how the channels lie on the wire.

## Decision

- `CarTransport` with `TransportCaps`: `usb-aoa`, `tcp-debug` (one stream with external header), `tcp6` (6 socket Baidu without
  External headerAnalysis of options A and B taken from LifeAuto Lite 0.1.6 (PR #30).
- Branch A (Mazda) phone - RFCOMM-server `carlife`, in P2P-Group head unit the Client (`P2pJoin.kt`); `MD_WIFI_IP` responds to
  `IP_REQUEST` (`BtFrames.kt:16-17`). When the group breaks up and is alive BT Go rejoin (PR #48, #68(General logic).
- VW BT 1420: Machine network and UDP-beacon (`HotspotBeacon.kt`).
- hotspot The bridge does not lift. `HotspotDetect` (PR #117It only tells you if it’s included.
- `targetSdk = 37` and `ACCESS_LOCAL_NETWORK` (`build.gradle.kts:20`, `AndroidManifest.xml:26`): without permission incoming TCP
  LAN silently cuts, loopback (AA 5277) does not require permission.

## Alternatives

- LocalOnlyHotspot for treatment of silent 5277: Egor 24.10 decided to fix USB-side (0002).
- PR #38 (targetSdk 37) transferred to # 59.
- 15 with pause before the request "Wi-Fi machine" at each start - removed if the machine was in communication less than a minute ago
  (PR #72, #79: Wire Crossing → wireless connection was ~ 35 (c) Mazda works through Wi-Fi DirectShe doesn't have a car, so...
  The request is completely missed.

## Consequences

- Transport is a separate interface: new `TransportCaps`s are added without extending the `CarLifeSession`.
- Each `targetSdk` change requires you to check your local area network rights, otherwise the wireless will silently stop working.
- Breaks by wireless connection disassemble by log diagnosis (0016): part is caused by VPN and Wi-Fi hotspot on the phone (h328, h307).
