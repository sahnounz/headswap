# Android Auto: head unit side (HU) for carlife-aa

Created by Open Sourceheadunit-revived, aasdk), documentation Android for Cars and observations on a real phone. The first part (sections 1-9) written before implementation, so many statements are marked **[D]**; sections 10-11 Describe what was done in `android/`.

Markings. **[P]** = confirmed (read in code/doc, given path:line or URL). **[D]** = guess/conclusion, code or dock is not explicitly stated.

Shortcuts:
- `HUR` = [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived), `app/src/main/java/com/andrerinas/openheadunit`
- `HUR-PROTO` = ibid., `app/src/main/proto`
- `AASDK` = [opencardev/aasdk](https://github.com/opencardev/aasdk) (continued) [f1xpl/aasdk](https://github.com/f1xpl/aasdk))

Version caveat: headunit-revived (README; `HUR/connection/self/SelfLauncherManager.kt:122`) distinguishes AA <17.4 from AA >=17.4. This app was tested on AA 17.x, using the newer branch. The older branch is LEGACY and starts Gearhead through an intent rather than server 5277. “Start head unit server” exists independently of that split (DHU documentation, §1.3), so 5277 is our primary path. **[D]** Equivalent behaviour on older versions such as 15.2 is unconfirmed; evidence covers only 16.4+ and 17.xx (§9).

---

## 1. How headunit-revived makes self mode

### 1.1 Two Branches

Branch selection: `HUR/connection/self/SelfLauncherManager.kt:122-137`. According to AA (`getPackageInfo("com.google.android.projection.gearhead").versionName`, parsing major.minor, `SelfLauncherManager.kt:58-71`) **[P]**:

| AA | Branch | Who's who connectit |
|---|---|---|
| >= 17.4 | `SelfLauncherV17_4`: HU-The app itself opens TCP on `127.0.0.1:5277` (`HUR/connection/self/launchers/SelfLauncherV17_4.kt:21`) | HU = TCP-customer, AA "head unit server" = TCP-server **[P]** |
| < 17.4  | `SelfLauncherLegacy` + 2 fallback: the App Raises TCP-server on the port 5288 and sends AA Intent to Gearhead He himself connected to 127.0.0.1:5288 (`SelfLauncherManager.kt:129-137`) | HU = TCP-server, AA = Customer **[P]** |

Confirmation that 5277 Available in older versions (in Android 10 + AA "17.xx" in the discussion, where the authors advise exactly the server as bypassing the lock of the int AA 16.4+): https://github.com/andreknieriem/open-headunit/discussions/533 **[P]** (ibid.: "Both methods were confirmed working").

### 1.2 Branch 5277 (recommended for carlife-aa)

- User Manually: AA -> Settings -> 10 taps on "Version" -> menu "three points" -> "Start head unit server". There is a permanent notification (foreground service AASources: README headunit-revived (section "3. Headunit Server"), wiki https://github.com/andreknieriem/open-headunit/wiki/Troubleshooting, DHU-Doki https://developer.android.com/training/cars/testing/dhu **[P]**.
- The default port is 5277, confirmed by DHU-doc (`adb forward tcp:5277 tcp:5277`) and code `HUR/automation/AutomationCommandPolicy.kt:53-54` (`HEADUNIT_SERVER_PORT = 5277`) **[P]**.
- No Intent/Component Gearhead no projection is required to start: the application does `Socket.connect(127.0.0.1:5277, timeout 5000)` (`HUR/connection/projection/SocketProjectionConnection.kt:174-177`), loopback not connected to the network)`LoopbackBindPolicy.kt:19-33`, `SocketProjectionConnection.kt:75`) **[P]**. Need only `INTERNET` (`AndroidManifest.xml:66`).
- Server AA does not speak AAP "It's a proxy, no. AAP-server: accepts socketPassing it on to his car service using binder and waits without a timeout. Accept-cycle sequential: while one compound hangs, all following are in backlogThey are not closed or maintained.”`HUR/connection/UnresponsivePeerPolicy.kt:6-11`) **[P]**. Consequences: (a) one connection per server life, you can't "try and close"; (b) if the last session ended without a clean closure, the server stalls (`HUR/connection/TeardownGuard.kt:6`, `HUR/connection/wifi/LinkLossTeardownPolicy.kt:20`and treated only Stop/Start server, and sometimes Force stop AA (`HUR/aap/AapTransport.kt:1041-1050`) **[P]**.
- The server is attached to loopback (README: "or on the same device (Self-Mode)); and is also achievable by Wi-Fi IP Phones for external head unit (README, wiki). **[P]**
- Run the server programmatically from a third-party application headunit-revived Can't: On the branch 5277 the app just shows a toast "run Headunit Server in Developer Settings" and opens `com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity` (`SelfLauncherV17_4.kt:17-25`, `SelfLauncherManager.kt:73-94`) **[P]**. Auto-run through the accessibility service in our application has been and is removed (see paragraph).10The server includes the user.

### 1.3 Branch LEGACY (AA < 17.4, we do not use it)

Steps (`HUR/connection/self/launchers/SelfLauncherLegacy.kt:52-75`) **[P]**:
1. Lift `WirelessServer`: `ServerSocket` to `0.0.0.0:5288`, reuseAddress, 3 attempts bind by 700 ms (`HUR/connection/wifi/server/WirelessServer.kt:100-114`). NSD-registration `_aawireless._tcp` in self mode is not needed (`SelfLauncherLegacy.kt:52-63`).
2. Waiting for an active network (up to 1 (c) (`SelfLauncherLegacy.kt:60-64`). On head unit Without a network, it creates a fictitious VPN-UI`HUR/../github/.../DummyVpnService.kt:12-47`: Address 10.0.0.2/24, route 0.0.0.0/0) to `ConnectivityManager.activeNetwork != null` **[P]**. On a regular phone with Wi-Fi/Mobile networks are not needed. **[D]**.
3. Run Gearhead activity with explicit intent (`SelfLauncherLegacy.kt:29-43`):
   - Component: `com.google.android.projection.gearhead` package, `com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity` class, `FLAG_ACTIVITY_NEW_TASK` flag;
   - extras: `PARAM_HOST_ADDRESS="127.0.0.1"` (String), `PARAM_SERVICE_PORT=5288` (int), `PARAM_SERVICE_WIFI_NETWORK` = `android.net.Network` (active network, or fake from `Parcel` C netId 0, `SelfLauncherServices.kt:19-32`), `wifi_info` = reflective `WifiInfo` C SSID `"Headunit-Fake-Wifi"` (`SelfLauncherServices.kt:35-48`).
4. Gearhead Connects itself to 127.0.0.1:5288; `WirelessServer` Gives Accepted socket in `CommManager.connect(socket)` (`WirelessServer.kt:129-146`). Measured: Gearhead He spends 15-20 from (to UNISOC), application deadline 30 seconds (`SelfLaunchTimeoutPolicy.kt:34`) **[P]**.
5. Fallback 1 (only API >= 30): broadcast in `com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver`, action `...wirelessstartup.START`, extras `ip_address`, `projection_port=5288`, `PARAM_SERVICE_WIFI_NETWORK`, `wifi_info` (`SelfLauncherBroadcast.kt:24-31`). Fallback 2: broadcast in `com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver`, action `com.google.android.projection.gearhead.START_WIRELESS_PROJECTION`, extra `DEVICE_ADDRESS`=MAC (`SelfLauncherBTDiscovery.kt:29-37`) **[P]**.
6. It is necessary that AA was a system/updated system application (`FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP`), otherwise the application swears (`HUR/connection/self/SelfLaunchResolveHelper.kt:49-57, 83-105`), see also https://github.com/andreknieriem/open-headunit/issues/994 (CN HyperOS, AA not systemic) **[P]**. On Android 10 and below launch is blocked in AA >= 16.4 (README, section Known Issues).
7. Pre-check resolution: `com.google.android.projection.gearhead.companion.RequestManifestPermissionsActivity` (`HUR/connection/self/AAPermissionTrampolineActivity.kt:48-52`) **[P]**.

README directly says: "AA 17.4+ breaks almost all third-party triggers, including Self-Mode" (README headunit-revived, block NOTE) **[P]**. Question "is breaking LEGACY on 15.2/Android 16" separately: **[D]**at / Android 16 background start of someone else 's activity (BAL) cut harder; headunit-revived for this hangs `SYSTEM_ALERT_WINDOW` (`AndroidManifest.xml:63`) and the application starts from foreground servicebut on Android 16 This is not verified in the sources.

### 1.4 Do I need developer mode and "head unit server"?

- For the 5277 branch: yes, mandatory (manual inclusion, item 1.2) **[P]**.
- For LEGACY: no, developer mode No need for (integrated) `WirelessStartupActivity`) **[P]**but breaks down on >= 17.4 and on Android <= 10 at AA >= 16.4 **[P]**.

### 1.5 Restrictions Android 14+/16 (on the side of our application)

- Foreground service: headunit-revived Holds `AapService` C `foregroundServiceType="connectedDevice|mediaPlayback|microphone"` (`AndroidManifest.xml:251-255`), in runtime He says `CONNECTED_DEVICE | MEDIA_PLAYBACK` (`HUR/app/ForegroundServiceTypePolicy.kt:28-33`), the microphone is added only when the grip is actually opened: with Android 14 mic this while-in-use type, from the background the start is rejected even when issued RECORD_AUDIO (`ForegroundServiceTypePolicy.kt:9-19`) **[P]**.
- For `connectedDevice` You need at least one of: `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`, `TRANSMIT_IR`, runtime `BLUETOOTH_*`/`UWB_RANGING`, or `UsbManager.requestPermission()`; plus `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` (https://developer.android.com/develop/background-work/services/fgs/service-types) **[P]**. Suitable for us `CHANGE_NETWORK_STATE` (normal-permission). For `mediaPlayback` similar `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. C Android 15 cannot run mediaPlayback/dataSync FGS from `BOOT_COMPLETED` (ibid.) **[P]**.
- Mic-FGS and `RECORD_AUDIO`: only if we send audio from the microphone head unit in AA (see paragraph 6) **[D]**: for the first version of "video + touch" is not necessary.
- Local Network Permission (`ACCESS_LOCAL_NETWORK`): mandatory for applications with targetSdk 37 (Android 17) for access to LAN (https://developer.android.com/privacy-and-security/local-network-permission) **[P]**; what loopback 127.0.0.1 excluded, not directly found **[D]** (Probably no problem; for Android 16 at targetSdk < 37 enforcement None).
- Permits that headunit-revived declares in the manifesto and which relate to us: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `RECORD_AUDIO`/`FOREGROUND_SERVICE_MICROPHONE` (if mic)`AndroidManifest.xml:55-88`). Package visibility: `<queries><package android:name="com.google.android.projection.gearhead"/>` (`AndroidManifest.xml:14`) is needed for `getPackageInfo` using Gearhead (Definition of version) AA) **[P]**.

---

## 2. Transport and frame AAP

### 2.1 frame (frame)

Outgoing and incoming format on TCP-socket **[P]**:

```
byte 0     channel id
byte 1 flags (bit field)
bytes 2-3 body length frame, big-endian uint16 (without header)
[bytes 4-7 only at FIRST-fragment of NOT-bulk message: total message size, big-endian uint32]
body payload (after handshake: one TLS-record)
```

Sources: `HUR/aap/AapMessage.kt:106` (HEADER_SIZE=4), `HUR/aap/AapMessageIncoming.kt:11-31` (decode: chan=buf[0], flags=buf[1], enc_len = BE16 buf[2..3]), `HUR/aap/AapReadSingleMessage.kt:63-83` (for `flags==0x09` Dochit 4 byte total), `AASDK/src/Messenger/FrameSize.cpp:35-46,73-75` (SHORT = 2 byte, EXTENDED = 6 byte: 2 frame + 4 total).

Flags (`AASDK/include/aasdk/Messenger/FrameType.hpp`, `EncryptionType.hpp`, `MessageType.hpp`; `HUR/aap/AapMessageFraming.kt:47-67`):

| bit | value |
|---|---|
| 0x01 | FIRST (frame opens message) |
| 0x02 | LAST (frame completes message) |
| 0x04 | CONTROL (Common message type/control on non-control channel) |
| 0x08 | ENCRYPTED (body in TLS) |

Typical values: `0x03` = plain bulk (The version, SSL-Handshake, auth); `0x0b` = encrypted bulk (Ordinary Message) `0x0f` = encrypted + control-flag (control-types 1..26 The non-control The channels, `AapMessage.kt:112-118`); Incoming fragments: `0x09` first, `0x08` middle, `0x0a` last (Encrypted, `AapMessageFraming.kt:9-14`) **[P]**.

Outgoing messages HU in our scenarios are always small, no need to fragment **[D]** (headunit-revived It does not: `AapTransport.kt:669-689` Encrypt and send one frame). Incoming videos are fragmented by 16 KB ( Bank`AapMessageFraming.kt:33-35`) **[P]**.

The "length" field of the encrypted frame = Length TLS-record (after wrap). For plain-frame = `размер payload + 2` (type), see. `Messages.createRawMessage` (`HUR/aap/protocol/messages/Messages.kt:20-32`: `[chan][flags][len=size+2 BE][type BE16][data]`) **[P]**.

Inside the body (after decoding): `[type BE16][protobuf]`; for media DATA: `[type=0][timestamp u64 BE µs][данные]` (`AudioMediaPayload.kt:5-28`, video: `VideoFragmentAssembler.kt:221-225`: offset 10 for DATA C timestamp, offset 2 for CODEC_CONFIG) **[P]**.

Assembly: one blocking read the Message for socket (`AapRead.kt:190-213`Because the frames of TCP They do not read except in length. `readFully` The Longest (`AapReadSingleMessage.kt:99`) **[P]**.

### 2.2 Protocol Version

HU First Flight Version Request (`Messages.kt:8-9,18`): `00 03 00 06 00 01 00 01 00 02` (chan 0, flags 0x03, len 6, type 0x0001, major=1, minor=2) **[P]**. Up to 3 Attempts, the answer is waiting 2 On the other hand, the overall budget 10 seconds (`AapTransport.kt:958-1037`, `HANDSHAKE_TIMEOUT_MS=10_000` :1209) **[P]**.

Phone answer: `chan 0, flags 0x03, type 0x0002, major(2) minor(2) status(2)` + optionally `VersionResponseOptions` (ConnectionConfiguration C ping timeout etc.) (`HUR/aap/AapVersionNegotiation.kt:74-127`; `HUR-PROTO/control.proto:291-302`) **[P]**. Phone selects the minor itself; messages "1.6+" cannot be sent if less is selected (`AapVersionNegotiation.kt:87-88`) **[P]**. Which version chooses AA: **[D]** (in logs) headunit-revived " Typical "1.7"; not fixed in the code).

If 5277 did not receive a single byte for all time, then the server "hangs" at the last session (`AapTransport.kt:1039-1051`, `HandshakeFailure.PEER_SILENT`) **[P]**.

### 2.3 TLS floor frame

- HU = TLS-Client (`sslEngine.useClientMode = true`, `HUR/aap/AapSslContext.kt:157-158`) through `SSLEngine` (Conscrypt If available, the system JSSE; `createSslContext`, `AapSslContext.kt:393-413`), `createSSLEngine("android-auto", 5277)` **[P]**. Phone certificate verification is disabled (`NoCheckTrustManager`, `HUR/ssl/NoCheckTrustManager.kt:7-17`; `AapSslContext.kt:410`) **[P]**.
- Handshake: the Cycle `getHandshakeStatus()`. NEED_WRAP: `wrap(emptyArray())` -> Send as plain-frame `[chan 0][flags 0x03][len][type 0x0003 ENCAPSULATED_SSL][TLS bytes]` (`AapSslContext.kt:76-83`, `createRawMessage(0, 3, 3, …)`). NEED_UNWRAP: read plain-frame (header 6 byte, then payload; payloadLength = len-2), bytes to put in `unwrap` (`AapSslContext.kt:123-151`). One message AAP may contain an incomplete record, then accumulates to a sufficient size (`BUFFER_UNDERFLOW`, :62-68). Handshake limit 15 seconds (`SSL_HANDSHAKE_TIMEOUT_MS`, :391) **[P]**.
- After Handshake: `ssl.postHandshakeReset()` and HU sends plain-frame **Auth Complete**: `00 03 00 04 00 04 08 00` = chan 0, flags 0x03, len 4, type 0x0004, protobuf `AuthResponse{status=0 (OK)}` (`Messages.kt:11-13`, `AapTransport.kt:1062-1069`) **[P]**.
- After that, everything frame come with `ENCRYPTED(0x08)`The Body = Result `sslEngine.wrap` (one record to the message): `encrypt` in `AapSslContext.kt:349-369`, assembly header in `AapTransport.kt:669-689` (`data[0]`, `data[1]` from the original, length = size of the ciphertext). Reception: `sslEngine.unwrap` for each frame (`AapSslContext.kt:271-347`) **[P]**I. Session TLS It is used between connections (JSSE session cache, `AapSslContext.kt:411-412`) **[P]**.

### 2.4 Certificate/key HU

HU presents a client certificate: public certificate "Google Automotive Link" (JVC Kenwood), separate from the key, lying in open form in repositories:
- headunit-revived: `app/src/main/res/raw/cert` (PEM, subject: O="Google Automotive Link", term 2014-07-08 .. 2044-07-07) and `.../raw/privkey` (PKCS#8 RSA 2048). Loading: `HUR/ssl/SingleKeyKeyManager.kt:85-103` (`R.raw.cert`, `R.raw.privkey`; RSA `PKCS8EncodedKeySpec`), `KeyManager` for client alias: `chooseClientAlias`/`chooseEngineClientAlias` (:69-76) **[P]**.
- aasdk (opencardev/aasdk): `src/Messenger/Cryptor.cpp:253-299` (built-in PEM, "RSA PRIVATE KEY“) and `cert/headunit.crt`, `cert/headunit.key`. This is a different certificate (JVC Kenwood, serial 0x1b) vs headunit-revived (serial 0x7653); both are accepted AA. README `cert/README.md` in aasdk: "publicly available certificate used in Android Auto head units" **[P]**.
- You can take either of the two; take from `res/raw` headunit-revived because it is already tested on Android-client (JSSE/Conscrypt) **[D]**.
- License: headunit-revived AGPL; copying code into the project pulls AGPL. Certificate and key as data: public. The decision is under license by you.

---

## 3. Service Discovery

After Auth Complete Phone Sends `ServiceDiscoveryRequest` (control, type 0x0005; fields `phone_name`=4, `phone_brand`=5), HU Answers `ServiceDiscoveryResponse` (type 0x0006) (`HUR/aap/AapControl.kt:340-346`, `HUR-PROTO/control.proto:230-258`) **[P]**.

Channel Identifiers Choose Itself HU in `Service.id` (aasdk even calls them "dynamic", `AASDK/include/aasdk/Messenger/ChannelId.hpp:5-10`); specific numbering headunit-revived (`HUR/aap/protocol/Channel.kt:5-18`) works against AA **[P]**: CONTROL=0, SENSOR=1, VIDEO=2, INPUT=3, AUDIO_SPEECH(AU1)=4, AUDIO_SYSTEM(AU2)=5, AUDIO_MEDIA(AUD)=6, MIC=7, BLUETOOTH=8, MEDIA_PLAYBACK=9, NAVIGATION=10 (numbering aasdk It is not the standard.) **[P]**.

### 3.1 Root fields (headunit-revived default values)

(`HUR/aap/protocol/messages/ServiceDiscoveryResponse.kt:299-337`; `HUR/utils/Settings.kt:921-960` defaults) **[P]**

| field | value |
|---|---|
| make / model | `"Google"` / `"Desktop Head Unit"` |
| year | `"2025"` |
| vehicle_id | `"headlessunit-001"` (with `-moto` suffix for motorcycle, `HUR/aap/VehicleIdentityPolicy.kt:16-20`) |
| driver_position | `DRIVER_POSITION_LEFT` (0) |
| head_unit_make / model | `"Google"` / `"Desktop Head Unit"` |
| head_unit_software_build / version | `"1"` / `"0.1.0"` |
| can_play_native_media_during_vr | `false` |
| hide_projected_clock | false; session_configuration (bit 0x01 clock, 0x02 signal, 0x04 battery) |
| display_name | `"Open Headunit"` |
| headunit_info (17 field) | duplicates make/model/year/id/hu_make/hu_model/sw + `vehicle_type` (`ServiceDiscoveryResponse.kt:314-328`) |
| connection_configuration | optional: ping timeout 15000 ms, socket read timeout 15000 ms, 65536 buffers (`HUR/aap/ConnectionConfigPolicy.kt:14-40`) |

Important (**[P]**, `ServiceDiscoveryResponse.kt:212-223`, `VehicleTypePolicy.kt:1-34`): without a microphone service (`ID_MIC`), the phone aborts setup with “No audio/mic”, **except** when HU identifies as a motorcycle (`vehicle_type = 3`, `vehicle_id` plus `-moto`). In motorcycle mode the phone uses its own microphone. For the first carlife-aa implementation, either advertise `MOTORCYCLE` or advertise a microphone service at 16 kHz/16-bit/mono and provide real head unit microphone audio.

### 3.2 Services (minimum working set)

Confirmed by code headunit-revived (`ServiceDiscoveryResponse.kt`) **[P]**:

1. SENSOR (id 1), `sensor_source_service`: `DRIVING_STATUS` (13) is mandatory; `NIGHT` (10) (headunit-revived Always announce:44); `LOCATION` (1only when included GPS (:39). `makeSensorType` :457.
2. VIDEO (id 2), `media_sink_service` (:52-137):
   - `available_type = MEDIA_CODEC_VIDEO_H264_BP` (3), `audio_type = NONE (0)`, `available_while_in_call = true`;
   - `video_configs[0]`: `codec_resolution = _1280x720 (2)`, `frame_rate = _60 (1)` or `_30 (2)`, `density` (DPI), `margin_width`, `margin_height`, `pixel_aspect_ratio_e4` (10000 = square pixels), `video_codec_type = H264_BP (3)`.
   - Permits (`control.proto:82-93`): 1=800x480, 2=1280x720, 3=1920x1080, 4=2560x1440, 5=3840x2160, 6=720x1280, 7=1080x1920, 8=1440x2560, 9=2160x3840. frame = **1280x720** for "720p". DHU-Doc Confirms 480p/720p/1080p As supported (https://developer.android.com/training/cars/testing/dhu) **[P]**.
   - margins: the Screen Size head unit It does not affect the ratio of frame matches the screen. Zero margin = AA Paints in the Whole frame. B headunit-revived Calculated in `HUR/utils/HeadUnitScreenConfig.kt:620-631` and `ProjectionGeometryPolicy.kt:49-57` (`margin = max(0, (adjusted_video - panel)/scale)`; in the mode PAR margin=0) **[P]**. For 16:9 head unit (or frame 1280x720 without letterbox) Use margin=0/0, density 160..240 **[D]** (value density, in which the interface looks normal, pick up on the spot; headunit-revived Take the real `densityDpi` screen, `HeadUnitScreenConfig.kt:644-650`; README: "Google Maps portrait: DPI < 200").
3. INPUT (id 3), `input_source_service` (:141-161): `touchscreen = TouchConfig{width=1280, height=720}` uses the frame size, not physical screen size. `keycodes_supported` comes from `HUR/input/KeyCode`; include `touchpad` only for rotary input. Touch size is the negotiated frame width/height (`ServiceDiscoveryResponse.kt:144-147`) **[P]**.
4. AUDIO (`media_sink_service`, `available_type = MEDIA_CODEC_AUDIO_PCM (1)`, `audio_type`, `audio_configs`) (:163-211; config `HUR/aap/protocol/AudioConfigs.kt:49-72`) **[P]**:

   | channel | id | `audio_type` | format |
   |---|---|---|---|
   | SYSTEM (system sounds) | 5 | `SYSTEM (2)` | 16000 Hz, 16 bit, 1 channel |
   | SPEECH (guidance/assistant) | 4 | `SPEECH (1)` | 16000 Hz, 16 bit, 1 channel |
   | MEDIA | 6 | `MEDIA (3)` | 48000 Hz, 16 bit, 2 channel |

   - Channel SYSTEM always announced"keep connection alive", `ServiceDiscoveryResponse.kt:164`); config 16 kHz mono "required“Otherwise, the phone is empty. endpoint-Sheet and Paper "No audio/mic" (`AudioConfigs.kt:64-66`) **[P]**.
   - **Nuance self mode:** at loopback-Session (`commManager.isLoopbackSession`) headunit-revived **does not announce** SPEECH and MEDIA, only SYSTEM (`ServiceDiscoveryResponse.kt:179-211`, `AudioSinkAnnouncementPolicy.kt:90-91`), the reason in the repo is not recorded ("Why Self Mode drops them at all is not recorded ... and is not settled") **[P]**; in the discussion # 533 user confirms: the sound comes from the phone itself when turned off audio sink. **For carlife-aa This is critical**The Sound AAYou may need to take it another way or see if it will be. AA In general send media/speech on loopback with the announced services. **[D]**
   - The phone validator accepts audio configurations only at {16000, 48000} Hz, 16 bits, and one or two channels (comment in `ServiceDiscoveryResponse.kt:218-222`) **[P]**.
5. MIC (id 7), `media_source_service{type=PCM, audio_config{16000,16,1}}` (:224-236) only if HU actually records; otherwise, the motorcycle (see above) **[P]**.
6. Bluetooth (id 8): is announced only when MAC (`ServiceDiscoveryResponse.kt:246-260`) is specified; without it, AA leaves calls on the phone. For a minimum of **you do not need** **[P]** (comment :261-267).
7. MEDIA_PLAYBACK (id 9) and NAVIGATION (id 10) (`navigation_status_service{min_interval 1000, ImageCodesOnly}`) headunit-revived Always announce (:270-284). For the minimum session "video+touch" **[D]**: not required; not verified that AA It starts without them.
8. What is missing from the headunit-revived and do not need: radio, phone_status, vendor_extension, generic_notification, wifi_projection.

---

## 4. Video

Flow (all through VIDEO=2 channel) **[P]**:

1. Phone -> HU: `ChannelOpenRequest` (control type 0x0007, `priority`, `service_id`on the right channel. HU Answers `ChannelOpenResponse{status=STATUS_SUCCESS}` (type 0x0008the ) River **The same** channel; on the sensor channel additionally immediately sends `DrivingStatus UNRESTRICTED` (`HUR/aap/AapControl.kt:477-502`) **[P]**.
2. Phone -> HU: `MediaSetupRequest` (type 0x8000, field `type`=3=H264) (`media.proto` `MediaSetupRequest`, `AapControl.kt:39-41`). HU Answers `Config{status=STATUS_READY(2), max_unacked, configuration_indices=[0]}` type 0x8003 (`AapControl.kt:103-116`) **[P]**. `max_unacked` UW headunit-revived for video: 12 using Wi-Fi/socket, 16 using USB (6/8 for software-HEVC) (`AapControl.kt:136-156`for audio 30 (socket) **[P]**.
3. HU -> Phone: `VideoFocusNotification{mode=VIDEO_FOCUS_PROJECTED(1), unsolicited}` type 0x8008 (`HUR/aap/protocol/messages/VideoFocusEvent.kt:10-20`). headunit-revived Send it when you are ready. Surface/Activity (`gainVideoFocus` after Config: `AapControl.kt:118-120`; `AapProjectionActivity.kt:175,289,345,437,2043-2046`), `unsolicited=true` At the start/start, `false` when responding to a request **[P]**. Without this message AALogically, the video doesn’t start. **[D]** (There was no direct test.)
4. Phone -> HU: `Start{session_id, configuration_index=0}` (type 0x8001) (`AapControl.kt:94-101`, `media.proto Start`). HU Memorizes `session_id` the Channel (`AapTransport.setSessionId` :1161and uses it in all Ack **[P]**.
5. Phone -> HU: `CODEC_CONFIG` (type 0x0001): `[type 2B][SPS/PPS Annex-B]`, then `DATA` (type 0x0000): `[type 2B][timestamp u64 BE µs][Annex-B access unit]`. Start codes `00 00 01`/`00 00 00 01` (`HUR/aap/AapVideo.kt:219-232`). Displacement of payload 10 (DATA C timestamp) or 2 (CODEC_CONFIG/without timestamp) (`HUR/decoder/video/VideoFragmentAssembler.kt:221-225`). It is already ready Annex-BIt can be served in MediaCodec/in CarLife-encoder-channel without conversion **[P]** (if CarLife-The party is waiting Annex-B C SPS/PPS in IDR; otherwise add SPS/PPS before key frame **[D]**).
6. Fragmentation: DATA > ~16 KB comes as FIRST(0x09, +4 byte total size) + MIDDLE(0x08)* + LAST(0x0a); assembly by `flags` (`AapVideo.kt:282-358`, `VideoFragmentAssembler.kt:210-219`: FLAG_SINGLE=11, FIRST=9, MIDDLE=8, LAST=10). Maximum assembly buffer in headunit-revived: 131080*64 byte (`AapVideo.kt:20`) **[P]**.
7. Ack: for each video message received (DATA and CODEC_CONFIG; on implementation ack It is reported that those who `type` in the beginning = 0 or 1In other words, in the first paragraph or a whole statement, `AapTransport.kt:798-852`) HU sends `Media.Ack{session_id, ack=1}` type 0x8004 the Channel VIDEO (`HUR/aap/protocol/messages/MediaAck.kt:7-18`, `AapTransport.kt:1157-1159`). Ack Goes **after** processing (for the window to control the speed), and not when receiving (`AapTransport.kt:770-795` Comment) **[P]**. If not send ack, window `max_unacked` closes and the picture rises **[P]**.
8. Keyframe upon request: special message "request IDR" not in the code; headunit-revived Resend `VideoFocusNotification` (focus cycle: First NATIVE gain=false, then PROJECTED) (`AapTransport.kt:379-441`, `AapVideo.kt:97-109`) **[P]**. For us: if CarLife-decoder Lost frame, do the same **[D]** (Behavior AA 15.2 not verified).
9. Phone -> HU `VideoFocusRequestNotification{mode, reason}` (type 0x8007): `VIDEO_FOCUS_NATIVE` the " User clicked Exit/Home in AA"We need to show the native UI (`AapControl.kt:48-73`) **[P]**. Response to the native request is required for a correct exit: `NATIVE` HU must return native screen/close session **[D]**.
10. `Stop` (0x8002) on the video channel: background / transition; headunit-revived ignores it with its own focus-cycle (`AapControl.kt:158-178`) **[P]**.

---

## 5. touch

INPUT channel (id 3) **[P]**:

1. After ChannelOpen Phone Sends `KeyBindingRequest` (type 0x8002, list `keycodes`), HU Answers `BindingResponse{status=STATUS_SUCCESS}` type 0x8003 (`AapControl.kt:216-237`, `input.proto`) **[P]**. Without this input will not work **[D]**.
2. Events: type `0x8001` (`Input.MsgType.EVENT`), proto `InputReport` (`HUR-PROTO/input.proto:28-78`):
   - `timestamp` (uint64, **nanoseconds**): headunit-revived takes `SystemClock.elapsedRealtime()` (ms) * 1_000_000 (`HUR/aap/protocol/messages/TouchEvent.kt:48`);
   - `touch_event` (3 field) = `TouchEvent{ repeated Pointer pointer_data (1), uint32 action_index (2), PointerAction action (3) }`;
   - `Pointer{ x=1, y=2, pointer_id=3 }` (uint32);
   - `PointerAction`: DOWN=0, UP=1, MOVE=2, CANCEL=3, OUTSIDE=4, POINTER_DOWN=5, POINTER_UP=6 (`input.proto:30-38`). Matching with Android: `MotionEvent.ACTION_*` directly (`TouchEvent.kt:19-30`).
3. Coordinates: pixels **frame**announced in TouchConfig (I mean 1280x720 for 720p), from 0 to width/height Included; with `FILL` and zero margin: `x = rawX / surfaceW * videoW`, `y = rawY / surfaceH * videoH`, clamp `[0, videoW]`, `[0, videoH]` (`HUR/input/TouchCoordinateMapper.kt:24-72`; challenge `AapProjectionActivity.kt:2141-2199`). In mode margin Subtracted `margin` from the width (uiW = videoW - marginW) **[P]**.
4. Multitouch: in one message all active pointers; `action_index` = index of the pointer that caused POINTER_DOWN/UP (`AapProjectionActivity.kt:2199`) **[P]**. `pointer_id` It comes from `MotionEvent.getPointerId`.
5. For CarLife: UW CarLife Yours touch-Protocol (in [CarProjection](https://github.com/aa112901/CarProjection) and [apollo-DuerOS](https://github.com/milesking/apollo-DuerOS)); Gateway task: take coordinates in the screen system head unit, to regulate the 1280x720 and send DOWN/MOVE/UP in the same sequence. One finger goes with pointer id=0The Second Finger CarLife (`action>>8`, pointerx/pointery, see `carlife-protocol.md` §6.1.1The bridge translates into POINTER_DOWN/POINTER_UP with all active indicators, id The remaining finger does not change.`AaBridge.onCarlifeTouch`) **[D]**.

---

## 6. Audio

(`HUR/aap/AapAudio.kt`, `HUR/aap/AudioMediaPayload.kt`, `AapControl.kt`) **[P]**

- Each announced audio service (channels) 4, 5, 6) goes through the same chain as the video: ChannelOpen -> `MediaSetupRequest(type=PCM/AAC)` -> HU `Config{READY, max_unacked(30 по socket), [0]}` -> phone `Start{session_id, config_index}` -> stream `DATA` (`[type 0][ts u64 µs][PCM]`) + `Ack` for each `DATA` (on `CODEC_CONFIG` ack **no** It just goes for AAC; `AudioMediaPayload.kt:14-27`).
- Ack: `Media.Ack{session_id (из Start этого канала), ack=1}` the Channel (`AapTransport.kt:1157-1159`; call for audio `AapMessageHandlerType.kt:67-78`).
- Format PCM: See p.3.2: system/speech 16 kHz/16/mono, media 48 kHz/16/stereo (little-endian signedHow he gives AA: `AudioTrack` ENCODING_PCM_16BIT in `AudioTrackWrapper`; **[D]** about the order of the byte, but in code decoder PCM without reassembly).
- Declare AAC optional (`Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC`, on 2.4 GHz headunit-revived switches, `NarrowBandProfilePolicy`); for loopback PCM.
- Audio focus: Phone Sends `AudioFocusRequestNotification{request: GAIN=1, GAIN_TRANSIENT=2, GAIN_TRANSIENT_MAY_DUCK=3, RELEASE=4}` (control type 0x0012), HU answers immediately `AudioFocusNotification{focus_state}` (type 0x0013): GAIN->`STATE_GAIN(1)`, GAIN_TRANSIENT->`STATE_GAIN_TRANSIENT(2)`, MAY_DUCK->`STATE_GAIN_TRANSIENT_GUIDANCE_ONLY(7)`, RELEASE->`STATE_LOSS(3)` ("always-grant") (`AapControl.kt:395-436, 450-455`). After `MediaSetupRequest` the Audio Channel HU Sam Shel `AudioFocusNotification{STATE_GAIN, unsolicited=true}` (`AapControl.kt:123-131`) **[P]**.
- Navigation focus: `NavFocusRequestNotification` -> response `NavFocusNotification{NAV_FOCUS_PROJECTED (2)}` (`AapControl.kt:358-370`) **[P]**.
- Microphone (if announced): Phone send `MicrophoneRequest{open, max_unacked}` (0x8005the Channel ) MIC; HU Answers `MicrophoneResponse{status, session_id}` (0x8006) and sends `DATA` C PCM 16 kHz/16/mono, the phone sends `Ack` (`AapControl.kt:188-206`, `MicrophoneResponse.kt`) **[P]**.
- For the minimum "video +touch"It is permissible to declare only SYSTEM-sink (As he does headunit-revived in loopback) to accept it Config/Start and just merge/remove data, **without forgetting ack for each DATA**Otherwise, the phone will close the window. **[D]**.

---

## 7. Ping, keepalive, shutdown

**[P]**
- Ping initiates **Phone**: control type 0x000b `PingRequest{timestamp, bug_report?}`, about once every second for the lifetime of the session (`AapTransport.kt:214-216` commentary). HU Answers `PingResponse{timestamp}` (type 0x000cthe same channel (`AapControl.kt:348-356`: `timestamp = System.nanoTime()`) **[P]**.
- HU Yours Ping not sent (no code) **[P]**. Timeouts: phone can send desired ping timeout in `VersionResponseOptions` (`AapTransport.kt:1016-1020`); HU You can offer your `ServiceDiscoveryResponse.connection_configuration` (15 with default in headunit-revived) **[P]**.
- TCP: `keepAlive=true`, `tcpNoDelay=true`, soTimeout Reading 15 Waiting for the next header; when timeout session is considered lost (`AapReadSingleMessage.kt:28-30,41-44`; `SocketProjectionConnection.kt:273-293`) **[P]**It means that ping Phones need to come more often. 15 with (he comes ~ 1 (c)
- Shutdown HU -> phone: `ByeByeRequest{reason=USER_SELECTION(1)}` (control 0x000f), pause 150 ms, close socket (`AapTransport.kt:698-709`) **[P]**. Close socket Required: otherwise server 5277 He is “busy.”`TeardownGuard.kt:6`) **[P]**.
- Shutdown Phone -> HU: `ByeByeRequest` (reasonFrom the phone: HU Answers `ByeByeResponse` (0x0010) is waiting 500 MS and CLOSING (`AapControl.kt:372-382`) **[P]**. If he came `ByeByeResponse` (HU The session ends (`AapControl.kt:318-320`).
- "Magic garbage": 4+ byte 0xFF instead of header = net output from Wireless Helper (headunit-revived specifics, we don't need) (`AapReadSingleMessage.kt:56-61`).
- Errors frame: control type 0xFFFF `FRAMING_ERROR`, `0x00FF UNEXPECTED_MESSAGE` (`control.proto:37-38`) **[P]**.

---

## 8. Minimum sequence for "video 720p + touch"

Each arrow = one AAP-frame. "pl" = plain (flags 0x03), "enc" = flags 0x0b (0x0f for control-types on non-control channel). Channel numbers: headunit-revived.

Preconditions: developer mode and "Start head unit server" (AA 17.x) are included on the phone. Application with foreground service and resolutions from p.1.5.

0. TCP connect `127.0.0.1:5277` (timeout 5 s). Do not open the test connection and do not close it without a handshake.
1. HU -> AA (pl, ch 0): `VERSION_REQUEST` (1): `00 03 00 06 00 01 00 01 00 02`.
2. AA -> HU (pl, ch 0): `VERSION_RESPONSE` (2): major, minor, status (+options). Check `status == 0`.
3. TLS-Handshake: HU `wrap()` -> `ENCAPSULATED_SSL` (3) (pl), AA -> `ENCAPSULATED_SSL`, and so on to `FINISHED` (customer certificate from p.2.4, trust anyone).
4. HU -> AA (pl, ch 0): `AUTH_COMPLETE` (4): `00 03 00 04 00 04 08 00`.
5. AA -> HU (enc, ch 0): `SERVICE_DISCOVERY_REQUEST` (5).
6. HU -> AA (enc, ch 0): `SERVICE_DISCOVERY_RESPONSE` (6) with services: SENSOR(1: DRIVING_STATUS, NIGHT), VIDEO(2: H264_BP, 1280x720, fps 30 or 60, margins 0, density N), INPUT(3: touchscreen 1280x720, keycodes), AUDIO SYSTEM(5, PCM 16k/16/1+ if desired SPEECH(4)/MEDIA(6)); vehicle_type MOTORCYCLE or MIC(7).
7. For each service AA send `CHANNEL_OPEN_REQUEST` (7) to the appropriate channel; HU responds `CHANNEL_OPEN_RESPONSE` (8, STATUS_SUCCESS). On the channel 1 immediately after the response HU send `SENSOR_EVENT` (0x8003) with `DrivingStatus=UNRESTRICTED(0)`.
8. AA -> HU, ch 1: `SENSOR_START_REQUEST` (0x8001, sensor type) for each announced sensor; HU: `SENSOR_START_RESPONSE` (0x8002, STATUS_SUCCESS); for NIGHT optionally `SENSOR_EVENT{night=false}`.
9. AA -> HU, ch 3: `KEY_BINDING_REQUEST` (0x8002); HU: `BINDING_RESPONSE` (0x8003, STATUS_SUCCESS).
10. AA -> HU, ch 2: `MEDIA_SETUP` (0x8000, type=H264). HU -> AA: `MEDIA_CONFIG` (0x8003: READY, max_unacked=12, indices=[0]).
11. HU -> AA, ch 2: `VIDEO_FOCUS_NOTIFICATION` (0x8008: PROJECTED, unsolicited=true).
12. AA -> HU, ch 2: `MEDIA_START` (0x8001: session_id, config_index 0). Remember session_id.
13. AA -> HU, ch 2: `CODEC_CONFIG` (SPS/PPS) and flow `DATA` (Annex-Bwith fragmentation; HU Collects on flags 0x0b / 0x09,0x08*,0x0a. After processing each message HU sends `MEDIA_ACK` (0x8004: session_id, ack=1the ) River ch 2.
14. Audio (if announced): same steps 10 and 12 on 4/5/6, + `AUDIO_FOCUS_NOTIFICATION` (STATE_GAIN, unsolicited) channels after Setup; Ack for each DATA.
15. AA -> HU, ch 0: `PING_REQUEST` (~1/s) -> HU `PING_RESPONSE`. `NAV_FOCUS_REQUEST` -> `NAV_FOCUS_NOTIFICATION(PROJECTED)`. `AUDIO_FOCUS_REQUEST` -> `AUDIO_FOCUS_NOTIFICATION`. `VOICE_SESSION_NOTIFICATION` can be ignored.
16. touch: HU -> AA, ch 3: `INPUT_REPORT` (0x8001) with `TouchEvent` (DOWN/MOVE/UP, coordinates in pixels 1280x720).
17. Output: HU -> AA `BYEBYE_REQUEST` (15), 150 ms, close socket. AA -> HU `BYEBYE_REQUEST` -> answer `BYEBYE_RESPONSE` and close.

What **no** Confirmed as mandatory (check by experience): MEDIA_PLAYBACK(9) and NAVIGATION(10) services; NIGHT-sensor; availability SPEECH/MEDIA Audio in loopback **[D]**.

---

## 9. Risks, versions, alternatives

### 9.1 Status self mode / Server head unit in 2025-2026

- README headunit-revived: on AA >= 17.4 almost all third-party triggers are broken (Wireless Helper, Intent self mode); "Headunit ServerIt is the only solution left for Self-Mode" (README headunit-revived, NOTE; wiki https://github.com/andreknieriem/open-headunit/wiki/Wireless) **[P]**.
- For AA 16.4+ on Android <= 10 Intent startup is disabled Google; bypass: Headunit Server on 127.0.0.1confirmed by the employee."on Android 10 with Android Auto 17.xx" (https://github.com/andreknieriem/open-headunit/discussions/533) **[P]**.
- Public/recent complaints (September) 2026): #1022 "Self mode not responding on latest android auto" (requirement to " open and close headunit server in dev settings", repeated; closed indiscriminately) https://github.com/andreknieriem/open-headunit/issues/1022; #977 "refuses to work in Self mode" (comes to Welcome-screen and throws back; closed "Done/Testing") https://github.com/andreknieriem/open-headunit/issues/977; #698 "App is not working after android auto 17.4 release" (wontfix, head unit server It didn’t help either). https://github.com/andreknieriem/open-headunit/issues/698; #994 AA the Non - System Application CN HyperOS https://github.com/andreknieriem/open-headunit/issues/994; #929 AA He believes HU the Active Media Application self mode https://github.com/andreknieriem/open-headunit/issues/929 **[P]** (page retelling, details of comments via WebFetch (Incomplete).
- Direct reports about Android 16 + server 5277 found no **[P]** (no data, no health confirmation). Our application uses the branch 5277 and is verified on AA 17.x.
- Known server fragility: accepts exactly one connection, hangs when rudely severed (`UnresponsivePeerPolicy.kt`, `TeardownGuard.kt`); recovery: Stop/Start server, if necessary Force stop AA Reset the Phone (`AapTransport.kt:1043-1050`) **[P]**. It needs to be built into UX (a user's suggestion, backoff using 10/60/300 (c)
- Launching the server only with your hands (see. 1.2) **[P]**; automation (ADB wireless debugging, accessibility) is not investigated **[D]**.
- Google may remove the server or change framing at any time. The snapshot_version field in `VersionRequestOptions` (`control.proto:291-294`) and `FramingError` suggest protocol evolution; our HU should ignore unknown fields and messages **[D]**.
- Video in self mode: AA on the same phone will render frame 720p parallel to the main UI phone; load on SoC (encoder AA + our decoder/Repeater CarLife) **[D]**critical on weak phones; keyframe-Cycle `VideoFocus` It can give a glimmer.
- Sound: in loopback headunit-revived does not announce MEDIA/SPEECH (No reason is given) in533 Sound playing from the phone when off sink **[P]**. So, for output to head unit in CarLife Experiment needed: Announced media-sink Check to see if the flow has gone. **[D]**; alternative: capture phone system sound separately (`MediaProjection`/`AudioPlaybackCapture`) **[D]**.

### 9.2 What is not in the code or not confirmed

- AA behavior < 16.4 as "server": no data; only 16.4/17.x. confirmed **[D]**
- Need sensors NIGHT / MPB / NAV for start: not checked. **[D]**
- Accurate requirement to timestamp-Field input: nanosecond (ms*1e6without verification AA. **[P]** for the formula, **[D]** for criticality.
- License: headunit-revived AGPL (code can not be copied without licensing the project AGPL), aasdk GPLv3 (check `LICENSE` in the repository).
- The actual version of the protocol selected by AA (1.6/1.7?): not recorded in the document. **[D]**

### 9.3 Alternatives

1. **DHU-Style without Android-HU**: AA on the phone -> adb forward 5277 -> PCDHU) (https://developer.android.com/training/cars/testing/dhu). Does not apply to our case (need a phone, working and how HUand as a projection in head unit) **[P]**.
2. **Wireless AA (Wi-Fi Direct/hotspot, Native mode)**: HU-The app is real. wireless-HU (BT-handshake + Wi-Fi) (`HUR/connection/wifi/modes/nativeaa/`, README "2. Native Mode"). For one phone is not suitable (phone can not be HU for themselves on BT/Wi-Fi) **[D]**.
3. **Wireless Helper + IntentLEGACY)**Working on AA <= 17.3 (README) but requires AA as a system application and cut BAL on Android 16 **[P]** (README), **[D]** (Android 16).
4. **MediaProjection-Phone Screen Mirror** instead of AA-protocol: completely bypasses AAbut it's not Android Auto UI **[D]**.
5. **USB-mode AOA** (PC/second phone as HU): not suitable for "one phone" circuitry **[D]**.

---

## Application: Quick links to files

- Network/handshake: `HUR/aap/AapTransport.kt:933-1084` (handshake), `HUR/aap/AapSslContext.kt:24-173`, `HUR/aap/protocol/messages/Messages.kt`.
- Reception: `HUR/aap/AapReadSingleMessage.kt`, `HUR/aap/AapMessageIncoming.kt`, `HUR/aap/AapMessageHandlerType.kt`.
- Control/Media/Input/Sensor processors: `HUR/aap/AapControl.kt`.
- Service discovery: `HUR/aap/protocol/messages/ServiceDiscoveryResponse.kt`, `HUR/aap/protocol/AudioConfigs.kt`.
- Video: `HUR/aap/AapVideo.kt`, `HUR/decoder/video/VideoFragmentAssembler.kt`, `HUR/aap/protocol/messages/VideoFocusEvent.kt`, `MediaAck.kt`.
- touch: `HUR/aap/protocol/messages/TouchEvent.kt`, `HUR/input/TouchCoordinateMapper.kt`, `HUR/aap/AapProjectionActivity.kt:2141-2199`.
- Proto- Circuits (suitable for generation): `HUR-PROTO/{control,media,input,sensors,common}.proto`; alternative layout in `AASDK/protobuf/aap_protobuf/`.
- Self mode: `HUR/connection/self/*`, `HUR/connection/wifi/server/WirelessServer.kt`, `HUR/connection/UnresponsivePeerPolicy.kt`.
- Certificate: headunit-revived `app/src/main/res/raw/{cert,privkey}`, aasdk `cert/headunit.{crt,key}`.

---

## 10. Autostart server head unit (2026-10-07, removed 2026-10-24)

The old accessibility service (`autosrv/`, AccessibilityService) clicked AA menu items automatically. It was removed: the user now starts the head unit server (⋮ → Start head unit server), while the app only opens AA settings. The app also preserves a live AA session; video-mode/DPI changes apply after restarting the server. It is single-use and cannot be restarted automatically. The following describes the former service and AA 17.7 menu labels (`menu_action_developer_head_unit_network_server[_stop]`) **[P]**:

| | en | ru |
|---|---|---|
| the " Run " | `Start head unit server` | `Вкл. сервер головного устр-ва` (That's right, without the word "start") |
| The “Stop” | `Stop head unit server` | `Остановить сервер головного устройства` |
| Menu button ⋮ (contentDescription) | | `Ещё` (More; visible in `uiautomator dump`) |

Settings AA in 17.7 Open as `Material3SettingsActivity` (Redirected from `DefaultSettingsActivity`), at the tulbar `ImageView desc='Ещё' clickable=true` **[P]**.

Scenario of Service`ServerAutomation`, deleted; verified JVM-tests on a fake screen): the screen is turned on and without keyguard -> `startActivity(DefaultSettingsActivity, NEW_TASK|CLEAR_TASK)` -> "More" -> if there is a "Stop": click, wait, again "More", wait "Start" (before 5 try), click -> check (menu again, there should be "Stop") -> return to the application (`MainActivity` C `cmd=front`). Triggers`Trigger`: button / `--es cmd aa_server_restart [--ei delay_s N]`, USB_ATTACH (Before the first attempt) `AaConnector`, waiting until 8 c, then the connector as usual), AFTER_SESSION (AA-The session is over, USB Alive, waiting until 12 (c) RETRY (the last attempt was made to block, while the server is not). Blocked phone: on head unit stub “Unlock the phone.”

Enable this service: Settings -> Features. On this phone (HyperOS, Android 16) `adb shell settings put secure enabled_accessibility_services ...` and `pm grant ... WRITE_SECURE_SETTINGS` give `SecurityException: must have WRITE_SECURE_SETTINGS` (needs a tumbler)USB-debugging (security settings) » **[P]**; include hands. For sideload-Application item can be gray: Settings -> Applications -> Headswap -> ⋮ -> "Allow limited settings" **[D]**.

## 11. Microphone machine for AA (2026-10-07)

AA-Side (`AapSession`, `AaMicUplink`; format is checked with headunit-revived `MicSessionController`, `MicFlowControl`, `MicUplinkFrame`, `MicCaptureFormat`) **[P]**:
- `MicrophoneRequest{open=1, anc=2, ec=3, max_unacked=4}` (0x8005) on the channel MIC=7. Open: reply `MicrophoneResponse{status=0, session_id}` (0x8006), session_id C 1; refusal: status -7, session_id 0 (as before). Close: The answer is success with the former. session_id.
- DATA: Channel 7, type `0x0000`, the body `[timestamp u64 BE мкс][PCM16 LE mono 16 кГц]`, frame Encrypted bulk (flags 0x0b). frame using 2048 Samples = 4096 B = 128 ms; tail is discarded when closed, not supplemented with zeros. timestamp = moment of the first byte frame.
- Window: no more than `max_unacked` unconfirmed frame; `Ack{session_id=1, ack=2, receive_timestamp_ns=3}` on the channel 7 (count = number `receive_timestamp_ns`, if there is, otherwise `ack`). Over the window in line to 4 frame, then the session is interrupted (`MicrophoneResponse` with an error). Until the answer to Open data.

CarLife-side (protocol) CarLife 2.0Observations on head unit) **[P]**:
- start recording at head unit: CMD `MIC_RECORD_RECOG_START` 0x00010024 (empty body), then `MODULE_STATUS{cnt=1, VR(4)=1}`; stop: `MIC_RECORD_END` 0x00010023 and `MODULE_STATUS VR=0`. `MIC_RECORD_WAKEUP_START` 0x00010022 standard application of the sling when constantly listening to wake- words, we do not use.
- Data HU->MD: Channel VR=5, type `0x00058001` (`CommonParams.MSG_VR_DATA = 360449`), header 12 byte `[len][time][type]`, the body PCM16 mono 16 kHz, encryption is disabled. Standard application CarLife Throw the first 20 packages after the start of recording; we have default 0 (`AaMicRouter.HU_SKIP_PACKETS`), until the size of the package is known head unit Honda.
- head unit Honda in FEATURE_CONFIG Key `VOICE_MIC` It does not send (4 Key: BLUETOOTH_AUTO_PAIR, BLUETOOTH_INTERNAL_UI, FOCUS_UI, VOICE_WAKEUPby logs of the trip 2026-10-07) -> status MIC=0 (microphone head unit).

Routing`AaMicRouter`, mode in UI "Microphone: machine / phone / car": machine = only head unitPhone = only `AudioRecord` VOICE_RECOGNITION 16 kHz; car = head unitIf there is no data 2 c, `MIC_RECORD_END` and switching to the phone. Log with prefix `MIC` (open/close from AArequests to head unitbytes with head unit Once a second, fallback). Phone microphone requires RECORD_AUDIO; to work in the background service takes foreground-Type `connectedDevice|microphone`if the permit is granted.

