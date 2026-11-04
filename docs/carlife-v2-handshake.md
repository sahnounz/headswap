# CarLife 2.0: MD sequence <-> HU

Description of how the application (role) MDThe phone is talking to head unit (role) HU) by protocol CarLife 2.0. Implemented in `android/` (`CarLifeSession.kt`, `Handshake.kt`, `Protocol.kt`). Based on observations of real head unit Honda Vezel 2024 and open sources listed in [carlife-protocol.md](carlife-protocol.md). Framing Not changed with the version 1.0: external `[chan u32 BE][8+len u32 BE]`, CMD 8 byte `[len u16][reserved u16=0][type u32]`, video 12 byte `[len u32][time u32][type u32]`.

Monitoring of head unit Honda Vezel: she sends `HU_PROTOCOL_VERSION {2.0}` Twice, then `CARLIFE_DATA_SUBSCRIBE`and silent, until MD He didn’t send anything but MATCH. `HU_AUTHEN_REQUEST` This head unit does not send, channel encryption is not (in `HU_FEATURE_CONFIG_RESPONSE` Key `CONTENT_ENCRYPTION` is not announced).

VW Tharu XR 2026 sends `HU_AUTHEN_REQUEST` and cuts messages differently: header inner (8 byte) and the body goes separate external frame (`00 00 00 01 00 00 00 08` + `00 04 00 64 00 01 80 01`, then `00 00 00 01 00 00 00 04` + `08 02 10 01`). `InnerAssembler` Holds frameequal to one header C declared len > 0and writes the following frame The same channel. declared len (cmd-Channel: header 8 byte, data-channels: 12; declared len > 1 MB for cmd and > `MAX_OUTER_LEN` for data not held). Messages in one frame They pass without change.

## Sequence

```
HU -> MD  0x18001 HU_PROTOCOL_VERSION {major=2 minor=0 } (re-ignored, second MATCH not sent)
MD -> HU  0x10002 PROTOCOL_VERSION_MATCH_STATUS {1}           \
MD -> HU 0x1001B FOREGROUND (empty) | one pack, directly,
MD -> HU 0x10018 SCREEN_ON (empty if screen is on) | expect nothing from head unit
MD -> HU  0x10004 MD_INFO CarlifeDeviceInfo                    /
MD -> HU 0x10026 MODULE_STATUS, via 500 ms (44 byte, cnt=7)
HU -> MD  0x18043 CARLIFE_DATA_SUBSCRIBE  `08 01 12 02 08 00`
MD -> HU  0x10044 CARLIFE_DATA_SUBSCRIBE_DONE  `08 01 12 04 08 00 10 01`
HU -> MD 0x18003 HU_INFO (save and pledge, no reply)
HU -> MD  0x18027 STATISTIC_INFO
MD -> HU  0x1004B MD_AUTHEN_RESULT {true}   `08 01`
MD -> HU 0x10051 MD_FEATURE_CONFIG_REQUEST (empty)
MD -> HU 0x10031 CAR_DATA_SUBSCRIBE (empty)
HU -> MD  0x18048 HU_AUTHEN_REQUEST {randomValue}  (Honda does not send; VW Tharu Slip
MD -> HU  0x10049 MD_AUTHEN_RESPONSE {encryptValue } (immediately in response: RSA-Signature with private key Baidu, Base64; branch "2.0;" - md5-code, see below)
HU -> MD 0x1804A HU_AUTHEN_RESULT (log)
HU -> MD 0x18052 HU_FEATURE_CONFIG_RESPONSE (log of all keys; CONTENT_ENCRYPTION=1 -> warning only)
HU -> MD 0x18045/0x18046 SUBSCRIBE_START/STOP (log, data not sent)
HU -> MD  0x18007 VIDEO_ENCODER_INIT {w,h,fps}
MD -> HU  0x10008 VIDEO_ENCODER_INIT_DONE {w,h,fps } (size) encoder by steps of width head unit)
MD -> HU  0x1001B FOREGROUND
HU -> MD 0x18009 VIDEO_ENCODER_START -> only here starts encoder
MD -> HU  VIDEO [len][time=unix-sec][0x20001] + H.264 Annex-B; keepalive with simple: 0x20001 len=0
```

Up to START over the VIDEO channel MD is silent (no 0x20002). The order after MATCH inside the starter pack and the response to STATISTIC_INFO is sewn into `Handshake.startBatch`/`Handshake.onStatisticInfo` and tested.

## Codes

| Code | Name | E.g. | Body |
|---|---|---|---|
| 0x18001 | HU_PROTOCOL_VERSION | HU->MD | `08 major 10 minor` (re-ignored) |
| 0x10002 | PROTOCOL_VERSION_MATCH_STATUS | MD->HU | `08 01` |
| 0x1001B | FOREGROUND | MD->HU | empty |
| 0x10018 | SCREEN_ON | MD->HU | empty |
| 0x10004 | MD_INFO | MD->HU | CarlifeDeviceInfo |
| 0x10026 | MODULE_STATUS | MD->HU | CarlifeModuleStatusList; via 500 ms after MATCH |
| 0x18043 | CARLIFE_DATA_SUBSCRIBE | HU->MD | CarlifeSubscribeMobileCarLifeInfoList |
| 0x10044 | CARLIFE_DATA_SUBSCRIBE_DONE | MD->HU | same, + supportFlag |
| 0x18045 / 0x18046 | SUBSCRIBE_START / STOP | HU->MD | list moduleID (we only have log) |
| 0x18003 | HU_INFO | HU->MD | CarlifeDeviceInfo (HU) |
| 0x18027 | STATISTIC_INFO | HU->MD | CarlifeStatisticsInfo |
| 0x1004B | MD_AUTHEN_RESULT | MD->HU | `08 01` |
| 0x10051 | MD_FEATURE_CONFIG_REQUEST | MD->HU | empty |
| 0x10031 | CAR_DATA_SUBSCRIBE | MD->HU | empty |
| 0x18048 | HU_AUTHEN_REQUEST | HU->MD | randomValue (string); Honda not fly, VW Tharu fly |
| 0x10049 | MD_AUTHEN_RESPONSE | MD->HU | encryptValue (string): Base64 Signatures `versionVerify(randomValue)` |
| 0x1804A | HU_AUTHEN_RESULT | HU->MD | authenResult (bool) |
| 0x18052 | HU_FEATURE_CONFIG_RESPONSE | HU->MD | CarlifeFeatureConfigList |
| 0x18007 | VIDEO_ENCODER_INIT | HU->MD | `{w,h,fps}` |
| 0x10008 | VIDEO_ENCODER_INIT_DONE | MD->HU | `{w,h,fps}` |
| 0x18009 | VIDEO_ENCODER_START | HU->MD | empty |
| 0x1800A / 0x1800B | PAUSE / RESET | HU->MD | empty |
| 0x1800C / 0x1000D | FRAME_RATE_CHANGE / _DONE | HU<->MD | `{fps}`, valid for 3..30 |
| 0x20001 | VIDEO_DATA / keepalive (len=0) | MD->HU | H.264 |
| 0x20002 | VIDEO_HEARTBEAT | MD->HU | no; only available with "mobile pausing", we do not use |
| 0x18056 / 0x10057 | VIDEO_ENCODER_JPEG / ACK | HU<->MD | ACK empty (we have ACK, we still send H.264) |
| 0x18079 / 0x10080 | REQUEST / RESPONSE_MD_THEME_STATE | HU<->MD | `{themeModes}`: 0 light, 1 dark, 2 car |

Other codes CMD-Channels: [carlife-protocol.md](carlife-protocol.md), §3.

## MD_INFO (CarlifeDeviceInfo)

Fields: os="Android", board, bootloader, brand, cpu_abi(2), device, display, fingerprint, hardware, host, cid=Build.ID, manufacturer, model, product, serial="unknown", codename, incremental, release=Build.VERSION.RELEASE, btaddress="unknown", carlifeversion (continuity in the annex), carlifeType=1.

SDK: for `SDK_INT >= 33` sends `sdk="29"`, `sdk_int=29`. On Android 16 / SDK 36 is this case. Otherwise the real values.

## MODULE_STATUS

For head unit major >= 2: `cnt=7`, couples (moduleID, statusID) = (1,0)(2,0)(3,0)(4,0)(6,0)(8,0)(9,0). Body 44 byte: `08 07 12 04 08 01 10 00 ...`. For major < 2 Only five pairs.

## Video Size

Sizes follow HU width thresholds rather than its exact request. For major 2 (not a new HU; no wide mode): width <800 → 768x432; <1024 → 848x480; <1280 → 1024x576; otherwise 1280x720. New HU means major 4 or 3.2: wide at w/h >=2.3 gives 1024x384 / 1280x480 / 1920x720; width >=1920 without wide gives 1920x1080. INIT_DONE uses INIT fps when within 1..60, otherwise 30. `force720` selects 1280x720. User/server resolution overrides are not reproduced.

## What is not implemented

- MD_AUTHEN_RESPONSE for the branch `randomValue = "2.0;<rnd>"` (Native `EncryptionUtils.getVerifyCode` UW Baidu): `encryptValue = md5hex("CARLIFE" + brand + model + sdk + <rnd>)`, line hex, brand/model/sdk from sent MD_INFO, `<rnd>` - second part `split(";")`.Slett head unit Honda CR-V 2026 (China `honda_3ya`, CarLife HU v2.3.4), `<rnd>` the View Date `2026-10-14-12-01-33`;unanswered head unit writes "device not authorized" and rips USB each ~ 40 c. Ordinary `randomValue` Signed as U Baidu 8.6.7See above.
- Channel encryption: Verified head unit He does not announce it. If `HU_FEATURE_CONFIG_RESPONSE` contains CONTENT_ENCRYPTION=1, a warning is written in the log; chain 0x1006A..0x1006E (RSA public key -> AES key -> ENCRYPT_READYdescribed in carlife-protocol.md, §8) Not done.
- Subscription data (turn-by-turn, music): DONE says supportFlag=true for moduleID 0 and 2, but no data is sent after SUBSCRIBE_START.
- JPEG- video mode is not supported, only send H.264.
