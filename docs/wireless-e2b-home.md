# E2B: branch B (hotspot phone + beacon) - home checks

## Code

- `link/wifi/HotspotBeacon.kt`: broadcast a “CarlifeHost” beacon on UDP 7999 every two seconds on each IPv4 interface, excluding rmnet/ccmni/lo/p2p/tunnels. Every twelve seconds, unicast to each address in the hotspot subnet, excluding wlan0. A UDP 8999 responder returns readiness JSON for datagrams containing `carlifehost`. Stop at 6/6 connections.
- `Tcp6Listener(peerOk)`: 6 ports on the 0.0.0.0, only addresses from subnets where the beacon goes (`why=subnet` in WL otherwise) are accepted.
- Entrance: debug `--ez debug_wifi_b true` (BridgeService), waiting for head unit 10 minutes. hotspot includes person.
- `pc-headunit`: `--transport tcp6 --discover --probe-8999` - catches a beacon on 7999, takes the sender address, connects 6 socket.

## Emulator A17 (2026-10-14)

NAT emulator: the beacon reached PC unicast-swipe on 10.0.2.2, sample 8999 with answer, 6/6 for 75 ms with 10.0.2.2, video 30 fps.

## Live hotspot, Redmi (2026-10-14, Android 16, HyperOS 3, Assembly 1.1.12-wl3)

hotspot Redmi, PC (Windows, Wi-Fi) client, `carlife_hu.py --transport tcp6 --discover --probe-8999 --touch-test`.

- hotspot interface on Xiaomi: `wlan2`, subnet /24 (10.83.148.0/24), phone .164, PC .100. Lighthouse went to broadcast and swap, `failed=0`.
- PC caught the first beacon via 1 with after start script, sample 8999 got a response.
- 6/6 socket from the PC address (lighthouse subnet), handshake 2.0 with authorization, video AA R1080 using Wi-Fi: ~22 fps First 10 c, next 30 fps; musicMEDIA) and track metadata have arrived; touch Got it.
- `ACCESS_LOCAL_NETWORK` on Android 16 is not valid (`n/a(api 36)`).
- The Windows: firewall input UDP 7999 for python passed without separate actions (depending on the network profile).

## Not verified

Current head unit (flashing behavior, standby, reconnection), long session and heating, Wi-Fi break on the go, 2,4 vs 5 GHz, auto-off hotspot HyperOS.
