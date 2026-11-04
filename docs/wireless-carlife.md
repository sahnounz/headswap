# Wireless CarLife on Honda Vezel 2024: tested, not working

Result: Wireless CarLife this one head unit No, there's only USB (AOA).

head unit staff: Honda Vezel 2024, China, CarLife 2.0, HU-application 1.1.0.

## How to set up wireless mode at the official MD

Source: decompiled Baidu CarLife MD 8.6.7, classes `com.baidu.carlife.core.connect.auto.*`.

- Phone keeps RFCOMM-server, the entry name in SDP is `carlife`. Listening to two UUID:
  - SPP `00001101-0000-1000-8000-00805F9B34FB`;
  - CarLife `a45bc7e5-bb50-4949-9de1-f78299cf6d78`.

  Connects to it head unit (`BluetoothClient`).
- frame BT-channel is arranged as cmd-channel USB. header 8 bytes: `len u16 BE`, `reserved u16`, `serviceType u32 BE`. Then `len` bytes protobuf (`BluetoothConnect.readData`).
- The first after accept writes the phone: `WIRLESS_INFO_REQUEST` 0x00100001. Next is the following exchange:

  | Message | Code |
  |---|---|
  | `INFO_RESPONSE` | 0x00108002 |
  | `TARGETINFO_REQUEST` | 0x00100004 |
  | `TARGETINFO_RESPONSE` | 0x00108005 |
  | `REQUEST_IP` | 0x00108006 |
  | `RESPONSE_IP` | 0x00100007 |
  | `MD_STATUS` | 0x00100008 |
  | `HU_STATUS` | 0x00108009 |

  Fields protobuf:
  - InfoResponse: `wirlessType=1`, `wifiFrequency=2`. Types 2, 3, 4 means wireless connection.
  - TargetInfo: `wifiDeviceName=1`, `targetInfo=2`, `scanRecord=3`, `p2pNetInfo=4`.

  After the exchange - Wi-Fi Direct and TCP-Channels, see. [carlife-protocol.md](carlife-protocol.md).
- UDP 7999 "CarlifeHost": his send phone when he hands out hotspot (`ConnectManager.sendHotspotUdp`). head unit listens to him.

## What have you tried, 2026-10-10

We did the diagnostic assembly of Headswap with passive test. She did not answer anything head unit, only listened:
- 4 RFCOMM-servers: SPP and CarLife, each in secure- and insecure-variant;
- SDP for paired devices;
- BT-events;
- Wi-Fi Direct (peers and services);
- Wi-Fi scan;
- UDP 7999.

The breakout branch was removed after the experiment. Code is restored according to this description.

Runs:
1. With USB-cable, the phone has long been paired. From head unit to HFP, A2DP and PBAP. only
2. Without cable. The pair was removed and re-created with a working sample, listened to 7 minutes.
   - No incoming RFCOMM.
   - Single RFCOMM from head unit by logs BT-steck - `UUID=0x111f`, HFP.
   - From head unit neither P2P-pier nor access point nor datagrams on UDP 7999.
3. The official Baidu CarLife on the phone was connected to head unit only by USB. Wireless attempts in the logic box is not. Logging Baidu is disabled.

SDP-Entries head unit («Honda HFT»): SPP, OPP, A2DP-sink, AVRCP, PANU, HFP, MAP-MNS. CarLife-UUID and AA-wireless `4de17a00-…` Not among them.

Run logs: `logs/car-wprobe/`.

## What to see on the head unit

- In CarLife on head unit: "Help" (right top), then Android or iPhone. In both sections, the first point is the type of connection, and there is only USB. There is no choice Wi-Fi.
- Item Wi-Fi Hotspot in settings head unit is not included without authorization in Honda Connect. In several countries Honda Connect does not work. Therefore, the scheme BT + Wi-FiAs in the regular wireless Android Auto, on this head unit Neither will it.

## Branch A in code (2026-10-16)

Made by parsing LifeAuto Lite 0.1.6, which connects to Mazda by wireless connection. Code: `link/bt/CarlifeBtProbe.kt` and `link/bt/P2pJoin.kt`.

1. While waiting for the car on Wi-Fi, listen to RFCOMM `carlife` (SPP and CarLife UUID). head unit connects itself.
2. `WIRELESS_INFO_REQUEST`, then `TARGET_INFO_REQUEST`.
3. Po `TARGET_INFO` The phone is entered by the client P2P-Group head unit: the First Battle ssid and password from `p2pNetInfo` (`WifiP2pConfig.Builder`with frequency, if any), otherwise looking for a feast with a name from the field 1 and connects PBC, `groupOwnerIntent=0`. Timeout 30 c.
4. When `p2p*` has IPv4 and head unit sent `IP_REQUEST`, `MD_WIFI_IP` 0x00100007 leaves.
5. head unit connects 6 TCP-channels (plus 9440) to `Tcp6Listener`. The subnet of the P2P-group is launched on a par with the subnets of the lighthouse.
6. Bluetooth-connection keep until the end of the waiting or session.
7. head unit Smooth P2P-the Group (Mazda: through ~ 40 After leaving the menu, log in. 2026-10-17) - the entrance is dropped entirely. When head unit Reconnect Bluetooth and send a new `TARGET_INFO`We re-enter. Previously, it was discarded as “I’m already entering”, and the connection did not return until “Stop” and a new start.

Unconfirmed: body `MD_WIFI_IP`. Now this is a line with IP in the field 1 protobuf (in) LifeAuto The body is assembled in the native part, Baidu I have not read this class. head unit after it does not connect TCP, looking at WL-Line Log `bt rx` after `tx WIFI_IP`: for unfamiliar messages is written `shape` (field numbers, numbers and lengths without contents).

Resolutions: `NEARBY_WIFI_DEVICES` (Android 13+) or `ACCESS_FINE_LOCATION` (below), are asked along with the rest by the button "Wait for the car according to Wi-Fi".
