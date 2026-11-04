# Baidu CarLife protocol (wired Android/AOA)

The specification is based on open source CarLife (list below) and by observation of the real head unit. First part (sections) 1-10) displayed by reading the code of open repositories (protocol version 1.0); what exactly does head unit C CarLife 2.0, see [carlife-v2-handshake.md](carlife-v2-handshake.md).

## 0. How to read

Statements tagged:

- **[K]** confirmed by code (link path:line nearby).
- **[D]** conjecture, inference from indirect features or external knowledge; no direct confirmation in the code.

Shortcuts (public repositories on GitHub):

| Alias | Path |
|---|---|
| `HUJ` | [milesking/apollo-DuerOS](https://github.com/milesking/apollo-DuerOS), `CarLife-Android-Vehicle/src/com/baidu/carlifevehicle` (HU-application Baidu, Java, role HU) |
| `HUC` | ibid., `CarLife-Vehicle-Lib/LibSource` (HU-library C++) |
| `PB` | `HUC/protobuf/proto` (all `.proto`, proto2) |
| `DOC` | Same repository, `CarLife-Vehicle-Lib/CarLifeVehicleLib动态链接库使用说明.docx` (API documentation) |
| `MDL` | [Doris-duo/CarLife-MD-Lib](https://github.com/Doris-duo/CarLife-MD-Lib), `LibSource` |
| `CP` | [aa112901/CarProjection](https://github.com/aa112901/CarProjection), `app/src/main/java/com/projection/car` (MD-client without root, powered by AOA) |

Sources and what they really mean:

- `HUJ` and `HUC`: implementation side of **HU**. This is the protocol version of 1.0 (`PROTOCOL_VERSION_MAJOR=1`, `MINOR=0`, `HUJ/CommonParams.java:79-82`), the era of 2015-2017.
- `MDL`the Port Authority `HUC` Under MD. Difference from `HUC` only in four added data sending functions MD→HU by Channels video/media/vr/tts (`MDL/core/CTranRecvPackageProcess.cpp:3184-3279`In addition to announcements in `MDL/include/CConnectManager.h` and `CTranRecvPackageProcess.h`). Sending functions CMD-Messages from MD there is no. As a reference MD-Library logic is useless. [K]
- `CP`: the only working open implementation of MD by AOA. It is superficial: responds to 5 messages, ignores the rest. [K] (`CP/MsgProcess.java:444-575`)
- `icar`: contains only `README.md` and images, with no protocol source. Its README describes CarLife 8.4.2 and using the official phone app: MultiService provides CarLife state and touch events, Shizuku supplies system permissions, and audio is synchronized over Bluetooth to the head unit. Thus icar wraps the original Baidu app rather than implementing the protocol. It adds no protocol specification, but indirectly supports Bluetooth audio on real head units (§7.4). [K] README of [yenole/icar](https://github.com/yenole/icar).
- Android Auto (aasdk, headunit-revived) does not apply to CarLife, see [aa-headunit.md](aa-headunit.md).

Applied messages are encoded everywhere by Protocol Buffers **proto2** (in `.proto` almost all fields of `required`).

---

## 1. AOA (USB Accessory)

Role of HU = USB host (in our test - PC). Phone (MD) is translated into accessory mode.

### 1.1 Identification Rows [K]

`HUJ/connect/AOAHostSetup.java:71-76`:

| index (wIndex) | Field | Value |
|---|---|---|
| 0 | manufacturer | `Baidu` |
| 1 | model | `CarLife` |
| 2 | description | `Baidu CarLife` |
| 3 | version | `1.0.0` |
| 4 | URI | `http://carlife.baidu.com/` |
| 5 | serial | `0720SerialNo.` |

The lines are sent as `String.getBytes()`, **without finishing NUL** (`AOAHostSetup.java:511-538`). Length = wLength. [C]

MD-Client CarProjection on accessory filter `manufacturer="Baidu" model="CarLife" version="1.0.0"` (`CP/../res/xml/accessory_filter.xml:4-7`Connected to `AndroidManifest.xml` via `USB_ACCESSORY_ATTACHED`). [K] So, our MD-The application must declare the same filter (Only better. manufacturer+modelto not depend on the version). What lines send real head unit Hondaunknown [D]It is logical that the same, once CarProjection Works with real head unit.

### 1.2 Query Order HU [C]

All queries go like vendor control transfer on EP0, timeout 0 (infinite) (`AOAHostSetup.java:425-439`, `TIME_OUT=0` on :64).

| Step | Request | bmRequestType | bRequest | wValue | wIndex | Data | Source |
|---|---|---|---|---|---|---|---|
| 1 | GET_PROTOCOL | `0xC0` (IN\|VENDOR) | 51 | 0 | 0 | read 2 byte, version LE: `buf[1]<<8 \| buf[0]`; Acceptable 1 or 2Otherwise, refusal | :495-509, :66 |
| 2 | SEND_IDENT x6 | `0x40` (OUT\|VENDOR) | 52 | 0 | 0..5 | line (table 1.1) | :511-538, :67 |
| 3 | START_ACCESSORY | `0x40` | 53 | 0 | 0 | none | :540-547, :68 |

Other:

- `AOA_AUDIO_SUPPORT = 58` has been announced (:69), but **is not called anywhere**. HID-requests (54-57) are not available. Sound by AOA-audio is not. [C]
- Before that HU Skips devices: Apple (VID `0x05AC`) and devices with a single interface mass storage (class 8, subclass 6, protocol 80) (`AOAHostSetup.java:203-219`, `HUJ/util/CarlifeUtil.java:54-58,390-407`). [K]
- If the device is already in accessory mode (see 1.3), the steps of 1-3 are omitted (`AOAHostSetup.java:249-296`).
- After `START_ACCESSORY`, the phone is re-enumerated. HU scans the list of USB every second (`AOAConnectManager.java:39,225-229`), maximum 10 switching attempts and 5 empty scans after switching (`AOAHostSetup.java:103-104,146-158,256-262`). [K]

### 1.3 VID/PID after switching [C]

`AOAHostSetup.java:47-55,441-472`: HU The device in accessory modeif VID = `0x18D1` and PID one of the `0x2D00` (accessory), `0x2D01` (accessory+adb), `0x2D02` (audio), `0x2D03` (audio+adb), `0x2D04` (accessory+audio), `0x2D05` (accessory+audio+adb). Constant `PID_ACCESSORY = 0x2D05` declared, but not used in logic.

In our test, we expect `18D1:2D00` or `18D1:2D01` (on the phone with ADB included). [D] according to the AOA specification, not by code.

### 1.4 Interface, Endpoints, Buffers

- HU takes interface `getInterface(0)`, `claimInterface(force=true)` (`AOAHostSetup.java:239-241`). [K]
- Endpoints **by index**: `getEndpoint(0)` = IN, `getEndpoint(1)` = OUT (:62-63,272-273). Address constants `0x81` / `0x02` announced (:60-61) but not used. [K] For pyusb Find the endpoints in the direction (`usb.util.endpoint_direction`Not by index. [D]
- All broadcasts bulk. Piece Size **16 KiB**: `AOA_MAX_BUFFER_BYTES = 16*1024` (:98); and reading, and writing longer 16 KIB cut to pieces 16 KiB (:327-350, :378-397). [K]
- HU writes the eight-byte channel header (§2) as a **separate bulk-transfer**, followed by separate body transfers (`bulkTransferOut(head,8,msg,len)`: two consecutive writes, :413-423). Reads follow the same order: exactly eight bytes, then the body (`AOAConnectManager.java:266-290`). [K]
- Maximum of one channel message: `AOA_MAX_BYTES = 64 MiB`, minimum 8 (`AOAConnectManager.java:45,277`). [C]
- [D] Why it matters: y Android-Reading Devices accessory fd Returns boundary data USB-transfer; remainder not included in the buffer transfer Lost (core) `f_accessory`, `BULK_BUFFER_SIZE=16384`; in open source CarLife This is not external knowledge.) MD-Client CarProjection Makes `read(8)` then `read(msgLen)` (`CP/MsgProcess.java:447-455`) and works only if HU Sends header The body is separate transfer. Therefore, fake HU **Immanuel** Sly 8-Byte external header Separate bulk-the body followed by a separate recording (up to 16384 bytes per record). For MD-applications: read in a circle to the desired number of bytes.

---

## 2. Multiplexing channels by one bulk- pair

### 2.1 External header (USB/AOA only) [C]

Each message of any channel in both directions is preceded by 8 bytes, big-endian:

```
 0       4       8
 +-------+-------+----------------------------+
 | chan  | len   |  inner message (len bytes) |
 | u32BE | u32BE |                            |
 +-------+-------+----------------------------+
```

- `chan`: id channel (table 2.2). HU only accepts 1..6 (`AOAConnectManager.java:277`).
- `len`: length of "internal message" (internal header + data), without these 8 bytes. HU considers `lenMsgHead + lenMsgData` (`AOAConnectManager.java:493-495`).
- Evidence: assembly on the HU side of `AOAConnectManager.java:341-364,493,505`; parsing on the HU side :266-314; on the MD (CarProjection) side of `Utils.java:19-22`, `MsgProcess.java:198-201,448-452,612-617`: `head[3]=channel`, `intToBytes2(len, head, 4)`.

### 2.2 Channel Identifiers [C]

`HUJ/CommonParams.java:450-455`, `HUC/include/CTranRecvPackageProcess.h:50-57`:

| id | Name | Internal header | Who writes |
|---|---|---|---|
| 1 | CMD | 8 byte | both |
| 2 | VIDEO | 12 byte | MD→HU |
| 3 | MEDIA (music) | 12 byte | MD→HU |
| 4 | TTS (navigational clues) | 12 byte | MD→HU |
| 5 | VR (voice) | 12 byte | MD→HU: sound responses; HU→MD: microphone |
| 6 | TOUCH/CTRL | 8 byte | HU→MD (except the rare `UI_ACTION_SOUND`) |

### 2.3 Interior header [C]

CMD and TOUCH (8 bytes) channel (`HUJ/connect/CarlifeCmdMessage.java:29-99`, `HUC/core/CTranRecvPackageProcess.cpp:348-420,1591-1621`):

```
0       2       4               8
+-------+-------+---------------+---------------+
| len   | resv  | service type  | data (len B)  |
| u16BE | u16BE | u32BE         |               |
+-------+-------+---------------+---------------+
```

`len` - length protobuf-Data (maximum) 32 KiB: `MSG_CMD_MAX_DATA_LEN`, `CommonParams.java:63`; buffer C++ 40 KiB, `CTranRecvPackageProcess.h:30`). `resv` always 0 (`CarlifeCmdMessage.java:36`; in C++ bytes are not initialized, `new u8[]`in practice 0) [K/D].

VIDEO/MEDIA/TTS/VR channels (12 bytes):

```
0       4       8       12
+-------+-------+-------+----------------+
| len   | time  | type  | data (len B)   |
| u32BE | u32BE | u32BE |                |
+-------+-------+-------+----------------+
```

`len` is payload length excluding the twelve-byte header; `time` is the timestamp (§5). Confirmed in HU (`CTranRecvPackageProcess.cpp:348-420`, `HUJ/util/DecodeUtil.java:296-320`, `PackageHeadAnalyseModule.java:75-97`) and MD (`CP/Utils.java:144-166`).

Total on wire for channel VIDEO: `[00 00 00 02][outerLen u32]` + `[dataLen u32][ts u32][00 02 00 01]` + H.264. For channel CMD: `[00 00 00 01][outerLen u32]` + `[dataLen u16][00 00][type u32]` + protobuf.

### 2.4 Difference from TCP/Wi-Fi-mode [C]

- In TCP-mode **external header there is no**. Each channel is a separate TCP- connection, the flow of internal messages goes as it is.
- Ports`HUJ/CommonParams.java:425-437`, `HUC/include/CConnectManager.h:23-44`): MD Listening 7240 (CMD), 8240 (VIDEO), 9240 (MEDIA), 9241 (TTS), 9242 (VR), 9340 (TOUCH). For Wi-Fi HU connected by the client to these phone ports. For ADB-Mode C++-Library HU Makes `adb forward tcp:7200 tcp:7240` etc., connecting to local 7200, 8200, 9200, 9201, 9202, 9300.
- B **AOA-Mode** Java-HU It raises local TCP-Servers on 127.0.0.1:7200/8200/9200/9201/9202/9300 (`AOAConnectManager.java:133-157`), is connected to them as a client with the same ports (`ConnectManager.java:122-131,298-351`) and "pull" bytes between these socket and USBAdding/Removing External header. Internal messages are identical. TCP-mode. For us it is only important: on USB There is an external headerEverything inside is like TCP. We don't need ports.
- "Connection completed" HU = Raised 6 socket (`TOTAL_SOCKET=6`, `ConnectManager.java:87,469-475`); thereafter HU immediately sends `HU_PROTOCOL_VERSION`. Between opening accessory and connection socket HU sleeping 1 seconds (`AOAHostSetup.java:286-291`). [K]

---

## 3. CMD- channel messages (id channel 1)

Direction is encoded in the message code: bit `0x8000` in the low word means HU→MD; without it, MD→HU. Exception: `GEAR_INFO` (0x00010029) is HU→MD with that bit clear. [K] See `HUC/include/CTranRecvPackageProcess.h:63-165` and `HUJ/CommonParams.java:89-234`.

The column "proto" is given by `PB/*.proto`; "empty" = `len=0`, no data.

| Code | Name | proto | E.g. | Note |
|---|---|---|---|---|
| 0x00018001 | HU_PROTOCOL_VERSION | CarlifeProtocolVersion{majorVersion,minorVersion} | HU→MD | HU sends `{1,0}` The First Generation`CarlifeProtocolVersionInfoManager.java:51-57,79-92`) |
| 0x00010002 | PROTOCOL_VERSION_MATCH_STATUS | CarlifeProtocolVersionMatchStatus{matchStatus} | MD→HU | 1=match, 2=not match (`CommonParams.java:82-83`) |
| 0x00018003 | HU_INFO | CarlifeDeviceInfo | HU →MD | fields Build.* HU (`CarlifeDeviceInfoManager.java:51-94`) |
| 0x00010004 | MD_INFO | CarlifeDeviceInfo | MD →HU | HU only saves; checks `sdk_int >= 21` for AOA (`CarlifeActivity.java:502-518`) |
| 0x00018005 | HU_BT_OOB_INFO (C++: HU_BT_PAIR_INFO) | CarlifeBTPairInfo | HU→MD | |
| 0x00010006 | MD_BT_OOB_INFO | CarlifeBTPairInfo | MD→HU | |
| 0x00018007 | VIDEO_ENCODER_INIT | CarlifeVideoEncoderInfo{width,height,frameRate} | HU→MD | |
| 0x00010008 | VIDEO_ENCODER_INIT_DONE | CarlifeVideoEncoderInfo | MD → HU | real dimensions encoder |
| 0x00018009 | VIDEO_ENCODER_START | empty | HU→MD | |
| 0x0001800A | VIDEO_ENCODER_PAUSE | empty | HU→MD | HU Sling in `onPause` (`CarlifeActivity.java:285`) |
| 0x0001800B | VIDEO_ENCODER_RESET | empty | HU→MD | in Java-HU not used |
| 0x0001800C | VIDEO_ENCODER_FRAME_RATE_CHANGE | CarlifeVideoFrameRate{frameRate} | HU→MD | Adaptive fps (`DecodeUtil.java:336-345,655-667`) |
| 0x0001000D | VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE | CarlifeVideoFrameRate | MD→HU | Java-HU does not process |
| 0x0001800E | PAUSE_MEDIA (obsolete) | empty | HU→MD | |
| 0x0001800F | CAR_VELOCITY | CarlifeCarSpeed | HU→MD | |
| 0x00018010 | CAR_GPS | CarlifeCarGps | HU→MD | |
| 0x00018011 | CAR_GYROSCOPE | CarlifeGyroscope | HU→MD | |
| 0x00018012 | CAR_ACCELERATION | CarlifeAcceleration | HU→MD | |
| 0x00018013 | CAR_OIL | CarlifeOil | HU→MD | |
| 0x00010014 | TELE_STATE_CHANGE_INCOMING | empty | MD→HU | |
| 0x00010015 | TELE_STATE_CHANGE_OUTGOING | empty | MD→HU | |
| 0x00010016 | TELE_STATE_CHANGE_IDLE | empty | MD→HU | |
| 0x00010017 | TELE_STATE_CHANGE_INCALLING | empty | MD→HU | |
| 0x00010018 | SCREEN_ON | empty | MD→HU | |
| 0x00010019 | SCREEN_OFF | empty | MD→HU | |
| 0x0001001A | SCREEN_USERPRESENT | empty | MD→HU | |
| 0x0001001B | FOREGROUND | empty | MD→HU | CarProjection sheet immediately after INIT_DONE (`MsgProcess.java:636-646`) |
| 0x0001001C | BACKGROUND | empty | MD→HU | HU Shows the screen-stub (`CarlifeActivity.java:530-536`) |
| 0x0001801D | LAUNCH_MODE_NORMAL | empty | HU→MD | |
| 0x0001801E | LAUNCH_MODE_PHONE | empty | HU→MD | |
| 0x0001801F | LAUNCH_MODE_MAP | empty | HU→MD | |
| 0x00018020 | LAUNCH_MODE_MUSIC | empty | HU→MD | |
| 0x00010021 | GO_TO_DESKTOP | empty | MD→HU | |
| 0x00010022 | MIC_RECORD_WAKEUP_START | empty | MD→HU | |
| 0x00010023 | MIC_RECORD_END | empty | MD→HU | |
| 0x00010024 | MIC_RECORD_RECOG_START | empty | MD→HU | |
| 0x00018025 | GO_TO_FOREGROUND | empty | HU→MD | `CarlifeUtil.java:295-301` |
| 0x00010026 | MODULE_STATUS | CarlifeModuleStatusList{cnt, repeated CarlifeModuleStatus{moduleID,statusID}} | MD→HU | See chap. 4.9 |
| 0x00018027 | STATISTIC_INFO | CarlifeStatisticsInfo | HU→MD | |
| 0x00018028 | MODULE_CONTROL | CarlifeModuleStatus{moduleID,statusID} | HU→MD | |
| 0x00010029 | GEAR_INFO | CarlifeGearInfo{gear} | HU→MD | only C++ |
| 0x00010030 | NAVI_NEXT_TURN_INFO | CarlifeNaviNextTurnInfo | MD→HU | |
| 0x00010031 | CAR_DATA_SUBSCRIBE (Java: _REQ) | CarlifeVehicleInfoList | MD→HU | |
| 0x00010032 (C++) / 0x00018032 (Java) | CAR_DATA_SUBSCRIBE_DONE / _RSP | CarlifeVehicleInfoList | HU→MD | **Difference of codes** between `HUC/.../CTranRecvPackageProcess.h:125` and `HUJ/CommonParams.java:210`; [K] both values are in the code, which of the two is waiting for the real MDunknown |
| 0x00010033 | CAR_DATA_START_REQ (C++: SUBSCRIBE_START) | CarlifeVehicleInfoList (Java parsit as single CarlifeVehicleInfo) | MD → HU | |
| 0x00010034 | CAR_DATA_STOP_REQ (C++: SUBSCRIBE_STOP) | same | MD→HU | |
| 0x00010035 | MEDIA_INFO | CarlifeMediaInfo | MD→HU | |
| 0x00010036 | MEDIA_PROGRESS_BAR | CarlifeMediaProgressBar{progressBar} | MD→HU | |
| 0x00010037 | CONNECT_EXCEPTION | CarlifeConnectException{exceptionType} | MD→HU | |
| 0x00010038 | REQUEST_GO_TO_FOREGROUND | empty | MD→HU | HU meets `GO_TO_FOREGROUND` |
| 0x00010039 | UI_ACTION_SOUND | empty | MD→HU | |
| 0x00010040 | BT_HFP_REQUEST | CarlifeBTHfpRequest | MD→HU | |
| 0x00018041 | BT_HFP_INDICATION | CarlifeBTHfpIndication | HU→MD | |
| 0x00018042 | BT_HFP_CONNECTION | CarlifeBTHfpConnection | HU→MD | |
| 0x00018043 | CARLIFE_DATA_SUBSCRIBE | CarlifeSubscribeMobileCarLifeInfoList | HU → MD | only C++ |
| 0x00010044 | CARLIFE_DATA_SUBSCRIBE_DONE | the same | MD→HU | only C++ |
| 0x00018045 | CARLIFE_DATA_SUBSCRIBE_START | the same | HU→MD | only C++ |
| 0x00018046 | CARLIFE_DATA_SUBSCRIBE_STOP | the same | HU→MD | only C++ |
| 0x00010047 | NAVI_ASSITANTGUIDE_INFO | CarlifeNaviAssitantGuideInfo | MD → HU | only C++ |
| 0x00018048 | HU_AUTHEN_REQUEST | CarlifeAuthenRequest{randomValue:string} | HU→MD | See chap. 4.3 |
| 0x00010049 | MD_AUTHEN_RESPONSE | CarlifeAuthenResponse{encryptValue:string} | MD→HU | |
| 0x0001804A | HU_AUTHEN_RESULT | CarlifeAuthenResult{authenResult:bool} | HU→MD | |
| 0x0001004B | MD_AUTHEN_RESULT | CarlifeAuthenResult{authenResult:bool} | MD→HU | C++ comments incorrectly say 0x0001804B; code and Java use 0x0001004B. DOC changelog: corrected the MSG_CMD_MD_AUTHEN_RESULT message definition. |
| 0x0001004C | GO_TO_FOREGROUND_RESPONSE | empty | MD→HU | MD He goes for 5 He did not go to the fore (DOC) |
| 0x0001004D | START_BT_AUTOPAIR_REQUEST | CarlifeBTStartPairReq | MD→HU | |
| 0x0001804E | BT_HFP_RESPONSE | CarlifeBTHfpResponse | HU→MD | |
| 0x0001004F | BT_HFP_STATUS_REQUEST | CarlifeBTHfpStatusRequest{type} | MD→HU | mainly microphone status request head unit (DOC) |
| 0x00018050 | BT_HFP_STATUS_RESPONSE | CarlifeBTHfpStatusResponse | HU→MD | |
| 0x00010051 | MD_FEATURE_CONFIG_REQUEST | empty | MD→HU | |
| 0x00018052 | HU_FEATURE_CONFIG_RESPONSE | CarlifeFeatureConfigList{cnt, repeated {key:string, value:int32}} | HU→MD | |
| 0x00018053 | BT_START_IDENTIFY_REQ | CarlifeBTStartIdentifyReq | HU→MD | |
| 0x00010054 | BT_IDENTIFY_RESULT_IND | CarlifeBTIdentifyResultInd | MD→HU | |
| 0x00018055 | ERROR_CODE | CarlifeErrorCode{errorCode:string} | HU→MD | |
| 0x00018056 | VIDEO_ENCODER_JPEG | empty | HU→MD | "swap JPEG instead of H.264" (DOC) |
| 0x00010057 | VIDEO_ENCODER_JPEG_ACK | empty | MD→HU | |
| 0x00010058 | BT_HFP_CALL_STATUS_COVER | CarlifeBTHfpCallStatusCover{state,phoneNum,name} | MD→HU | only Java, in C++ «deprecated» |
| 0x00010059 | MD_EXIT | empty | MD→HU | HU considers the connection severed (`CarlifeActivity.java:855-857`) |
| 0x0001006A | MD_RSA_PUBLIC_KEY_REQUEST | empty | MD→HU | only Java, §8 |
| 0x0001806B | HU_RSA_PUBLIC_KEY_RESPONSE | CarlifeHuRsaPublicKeyResponse{rsaPublicKey:string} | HU→MD | |
| 0x0001006C | MD_AES_KEY_SEND_REQUEST | CarlifeMdAesKeyRequest{aesKey:string} | MD→HU | |
| 0x0001806D | HU_AES_REC_RESPONSE | empty | HU→MD | |
| 0x0001006E | MD_ENCRYPT_READY | empty | MD→HU | |

Table sources: names and codes `HUJ/CommonParams.java:89-234`, C++-enumeration `HUC/include/CTranRecvPackageProcess.h:63-165`Directions to send-Functions `HUC/core/CTranRecvPackageProcess.cpp:1630-3131` and by reception handlers `:420-878,971-1590`. Files `.proto` for Java-only Messages (`HuRsaPublicKeyResponse`, `MdAesKeyRequest`, `BTHfpCallStatusCover`) lie only as generated Java (apollo-DuerOS, `CarLife-Android-Vehicle/src/com/baidu/carlife/protobuf/*.java`, descriptors inside). Their schemes: `rsaPublicKey = 1 (required string)`, `aesKey = 1 (required string)`, `state = 1 (int32), phoneNum = 2 (string), name = 3 (string)` (all required). [K]

Key bodies for minimum (all fields varint/line proto2):

- `CarlifeProtocolVersion`: `08 <major> 10 <minor>`for { 1,0}: `08 01 10 00`.
- `CarlifeProtocolVersionMatchStatus`: `08 <status>`; match = `08 01`.
- `CarlifeVideoEncoderInfo`: `08 <width> 10 <height> 18 <frameRate>` (varint). 1280x720, fps 0: `08 80 0A 10 D0 05 18 00`.
- `CarlifeAuthenResult`: `08 01` (true).
- `CarlifeTouchAction`: `08 <action> 10 <x> 18 <y>`.
- `CarlifeCarHardKeyCode`: `08 <keycode>`.
- `CarlifeMusicInit` / `CarlifeTTSInit`: `08 <sampleRate> 10 <channelConfig> 18 <sampleFormat>`.
- `CarlifeStatisticsInfo`: cuid(1,string), versionName(2,string), versionCode(3,int32), channel(4,string), connectCount(5,int32), connectSuccessCount(6,int32), connectTime(7,int32), crashLog(8,optional string).
- `CarlifeDeviceInfo` (23 Fields, all optional): os(1), board(2), bootloader(3), brand(4), cpu_abi(5), cpu_abi2(6), device(7), display(8), fingerprint(9), hardware(10), host(11), cid(12), manufacturer(13), model(14), product(15), serial(16), codename(17), incremental(18), release(19), sdk(20, string), sdk_int(21, int32), token(22), btaddress(23).
- Examples in full: see § 9.

---

## 4. Handshake by steps

The sequence is taken from Java-HU (`CarlifeActivity.java`), C++-tester (`HUC/CarLifeLibTest.cpp`) and MD-client CarProjection. What makes a real MD Baidu, there are no sources; the behavior is only fixed by its messages that HU. processes

### 4.1 Starting Point

1. HU Makes AOA-Switching ( § 1), opens 6 Local socket, sleeping 1 c, connects them ( § 2.4). [K]
2. HU **First** sends `HU_PROTOCOL_VERSION`. Even C++-tester for all scripts first sends it: "it is necessary to send MSG_CMD_HU_PROTOCOL_VERSION (0x00018001) message» (`HUC/CarLifeLibTest.cpp:253-293`). [K] Condition in Java: for connection type AOA (`!= USBMUXD`) (`CarlifeActivity.java:341-346`).

### 4.2 Protocol Version

- HU→MD `HU_PROTOCOL_VERSION {major=1, minor=0}`.
- MD→HU `PROTOCOL_VERSION_MATCH_STATUS {matchStatus}`: 1 = match, 2 = mismatch. CarProjection always replies 1 (`MsgProcess.java:466-473`). [K]
- If HU did not get 1: severs connection (`CarlifeActivity.java:465-470`). [C]
- `MD_PROTOCOL_VERSION` messages in code **net**: version MD HU does not receive. [C]

### 4.3 Authentication (AUTHEN)

What is in the sources:

- **API HU**: `cmdHuAuthenRequest(randomValue)` → 0x00018048; `cmdHuAuthenResult(bool)` → 0x0001804A; reception 0x00010049 and 0x0001004B (`HUC/core/CTranRecvPackageProcess.cpp:2542-2610`, `HUC/libwrapper/CCarLifeLib.cpp:530-546`). [K]
- **Intended scheme** (DOC, Command channel): HU generates a random string in `HU_AUTHEN_REQUEST`; MD encrypts it with the **private key** and returns it in `MD_AUTHEN_RESPONSE.encryptValue`; HU verifies it using the public key and sends `HU_AUTHEN_RESULT`; MD sends `MD_AUTHEN_RESULT`. After successful mutual authentication, MD requests HU features with `MD_FEATURE_CONFIG_REQUEST`. The DOC says the phone sends this message after both sides pass authentication to request head unit customization features. The code does not show the algorithm, key format, or string encoding. [Code-confirmed from DOC; implementation details inferred]
- **Java-HU Baidu (`HUJ`Authentication does not work.**: `HU_AUTHEN_REQUEST`, `MD_AUTHEN_RESPONSE`, `HU_AUTHEN_RESULT`, `MD_AUTHEN_RESULT` meet only in `CommonParams.java` (grep using `HUJ`). No sending, no processing. [K]
- **C++-tester**: `cmdHuAuthenRequest(&authenRequest)` call commented, `randomValue = "CarLife_1.0.0"` (`HUC/CarLifeLibTest.cpp:130,264-266`); reply handler only prints `encryptValue` (:738-741). [C]
- **CarProjection (MD)**: `HU_AUTHEN_REQUEST` It is not used at all (in `switch` using CMD-Types are not like this case, `MsgProcess.java:465-545`), `MD_AUTHEN_RESPONSE` not moving. The only thing that is: immediately after `STATISTIC_INFO` MD Sends `MD_AUTHEN_RESULT {result=true}` (`MsgProcess.java:524-541`; its local `.proto` the Field `result`, number 1, type bool, wire-compatible with `authenResult`, `CarProjection/app/src/main/proto/CarlifeAuthenResultProto.proto`). This gives a working connection with real head unitaccording to the project's purpose. [About the code, D about "working"]
- **icar**: no source.
- **Baidu CarLife 8.6.7 (MD)** The signature is (`com.baidu.carlife.authorization.PrivateKeyManager.encryptContentProcess`, key `PRIVATE_KEY` sewn into the application): `randomValue` Passed through `versionVerify` (Suffix after the first one) `_` compared to the app version "8.6.7» component by component: older or other length -> `<строка без суффикса>_00.00.00`, otherwise the string as it is), the result is encrypted **Private Key** (`RSA/ECB/PKCS1Padding`, `Cipher.ENCRYPT_MODE`) and leaves Base64 NO_WRAP in `MD_AUTHEN_RESPONSE.encryptValue`. For `"txGaIEnzYVCaFOEArS9O_0"` Signed `"txGaIEnzYVCaFOEArS9O_00.00.00"`If: `randomValue.split(";")` gives >= 2 Parts and First `"2.0"`It uses native `EncryptionUtils.getVerifyCode(ctx, brand + model + sdk + <вторая часть>)` (brand/model/sdk from MD_INFO, `CarlifeDeviceInfoManager.java:174`). In `libencryption.so` she checks the signature APK C `APP_SIGNATURE_CARLIFE` (With a stranger) nulland returns `md5hex("CARLIFE" + str)` the " Long Line "`MD5_HEAD = "CARLIFE"`, class `MD5` from `md5.cpp`); `getVerifyResult(info, code)` for head unit The same md5 and `strcmp`. We have - `HuAuth.verifyCode`. [K by Disassembly]
- For our implementation: immediately answer `HU_AUTHEN_REQUEST` with a Baidu-key signature (`HuAuth` in `Handshake.kt`), matching Baidu. Send `MD_AUTHEN_RESULT{true}` after `STATISTIC_INFO` regardless. If HU sends no `HU_AUTHEN_REQUEST` (Honda Vezel 2024), only that result is sent and encryption stays disabled. VW Tharu XR 2026 sends the request (`randomValue="txGaIEnzYVCaFOEArS9O_0"`), with inner header and body in separate outer frames; `InnerAssembler` in `Protocol.kt` reassembles them. See §10.

### 4.4 Device statistics and information

After `MATCH_STATUS = 1`, HU does in the following order (`CarlifeActivity.java:439-457`):

1. Starts the heart rate monitor (4.8);
2. `STATISTIC_INFO` (HU→MD) (`CarlifeUtil.java:303-326`);
3. `HU_INFO` (HU→MD) (`CarlifeDeviceInfoManager.java:108-121`);
4. `VIDEO_ENCODER_INIT` (HU→MD) (see para. 4.5), **except** In the event that u HU included `CONTENT_ENCRYPTION` and `AES_ENCRYPT_AS_BEGINE=false`: then HU waiting `MD_ENCRYPT_READY` (§8);
5. starts decoder, player PCM, touch;
6. `MODULE_CONTROL {moduleID=5 (CONNECT), statusID=2 (AOA)}` (`ConnectManager.java:659-671`, `ModuleStatusModel.java:43`).

MD sends `MD_INFO` on its own initiative; CarProjection sends it in reply to `HU_INFO` (`MsgProcess.java:474-495`). HU only stores `MD_INFO` and checks `sdk_int >= LOLLIPOP`; otherwise it displays “AOA not supported” after two seconds (`CarlifeActivity.java:502-518`). Therefore set `sdk_int >= 21` in `MD_INFO`. [K]

### 4.5 Videos: INIT / INIT_DONE / START

- HU→MD `VIDEO_ENCODER_INIT {width, height, frameRate}`. Java-HU: `width/height` = The size of your SurfaceView in pixels, `frameRate = 0` («if 0, means no limit to frame rate», `CarlifeUtil.java:233-248`; challenge `CarlifeActivity.java:447`). C++-Tester: 768x480@30 (`CarLifeLibTest.cpp:120`). [K]
- MD→HU `VIDEO_ENCODER_INIT_DONE {width, height, frameRate}`: **real** The size with which MD It will code. HU Uses **these** Importance of initialization MediaCodec and how "phone container" for recalculation of the coordinates of touches "`CarlifeActivity.java:472-494`). That is MD has the right to respond with other permissions (for example, tailored by the ratio of parties). CarProjection Echo of the sent `msgdata` (`MsgProcess.java:515`formed before that. `builder` is not used). [K]
- HU after INIT_DONE: `initDecoder(surface, w, h)`; sends `VIDEO_ENCODER_START`; sends `LAUNCH_MODE_*` (`CarlifeActivity.java:486-497`). [K]
- MD after `VIDEO_ENCODER_START` begins to send video. CarProjection on `START` first sends `MEDIA_INIT` (48000/2/16), then runs MediaProjection+MediaCodec (`MsgProcess.java:651-670`). [C]
- In `onResume` HU, the `VIDEO_ENCODER_START` jet without the preceding `INIT` (`CarlifeActivity.java:231-233`); in `onPause` `VIDEO_ENCODER_PAUSE` (:285). MD must experience repeated `START`/`PAUSE`. [K]

### 4.6 Foreground / background

- MD→HU `FOREGROUND` (empty): HUif decoder Ready, through 2 the Video Show (`CarlifeActivity.java:537-542`). CarProjection sends it right after `INIT_DONE` (`MsgProcess.java:636-646`).
- MD→HU `BACKGROUND`: HU Shows stub (`CarlifeActivity.java:530-536`).
- decoder It is considered ready after withdrawal. 16 frame (`DecodeUtil.java:555,616`, `mOutputCount >= 16`), after which HU shows the projection screen. [K]

### 4.7 Feature config

- MD→HU `MD_FEATURE_CONFIG_REQUEST` (empty) → HU→MD `HU_FEATURE_CONFIG_RESPONSE {cnt, [key,value]...}` (`HUJ/logic/FeatureConfigManager.java:87-138`). If u HU There is no record of him. **no reply** (`getFeatureConfigCount() > 0` at /94). [K]
- Java-HU keys (`FeatureConfigManager.java:117-137`; names in `CarlifeConfUtil.java:166-188`): `VOICE_WAKEUP`, `BLUETOOTH_AUTO_PAIR`, `BLUETOOTH_INTERNAL_UI`, `FOCUS_UI`, `VOICE_MIC` (0 = car microphone, 1 = phone microphone, 2 = unavailable), `MEDIA_SAMPLE_RATE` (0 = original, 1 = 48 kHz), `AUDIO_TRANSMISSION_MODE`, `CONTENT_ENCRYPTION`, `ENGINE_TYPE`, `INPUT_DISABLE`. Values come from encrypted `assets/bdcf`, which was not read. [Keys confirmed; values inferred]
- CarProjection `MD_FEATURE_CONFIG_REQUEST` not send (not in the code), that is, it works with settings HU by default. [K]
- Real MD usually sends this request after successful authentication (DOC). [K by DOC]

### 4.8 Heartbeat

- Separated CMD-heartbeat No. "Heartbeat" lives on the channel VIDEO: MD sends a video message; message with `len = 0` HU He believes heartbeat (`DecodeUtil.java:320-323`: «empty package, used for heart beat), his type `MSG_VIDEO_HEARTBEAT = 0x00020002` (`CommonParams.java:198`, processing `HUC/core/CTranRecvPackageProcess.cpp:746`). [K]
- HU Consider the challenges of reading video-socket (`numOfHeartBeat`, `ConnectManager.java:521-528`once a second; for AOA (not Wi-Fi/tether/usbmuxd) rips the connection after 6 consecutive ticks without reading (timer starts through 5 c, then 1 c; `ConnectManager.java:585-633`That is, approximately 11 After the last video reading. [K]
- Total MD should be sent to VIDEO at least once in ~ 5 with message: frame or empty `VIDEO_HEARTBEAT`. CarProjection specifically repeats frame (`KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100000 us`, `MediaCodecTool.java:74`), and heartbeat does not send. The period of the present MD unknown. [D]
- Break: HU at `MD_EXIT` and at any read/write error (`ConnectClient.setIsConnected(false)`).

### 4.9 Modules (MODULE_STATUS)

MD→HU `MODULE_STATUS {cnt, [moduleID,statusID]...}` reports module state changes. IDs (`HUJ/model/ModuleStatusModel.java:20-48`): PHONE=1 (0 idle, 1 incoming, 2 outgoing), NAVI=2 (0 idle, 1 start, 2 stop), MUSIC=3 (0 idle, 1 running), VR=4 (0 idle, 1 running), CONNECT=5 (HU→MD, iOS connection type only; Java-HU sends `2` for AOA), MIC=6 (0 car microphone, 1 phone microphone, 2 unsupported). HU stores these states and reacts with a call placeholder or voice UI. A minimal MD may omit `MODULE_STATUS`. [K]

### 4.10 Others

- `STATISTIC_INFO.cuid` - ID head unit; CarProjection shows it to the user as "VIS ID" (`MsgProcess.java:526-537`).
- `HU_LOG = 0x7FFF0001` exists only locally inside Java-HU (`CommonParams.java:89`), does not walk on wire.

---

## 5. Video channel (id 2)

### 5.1 Format frame [C]

Message MD→HU: `[outer: 00 00 00 02, len]` + `[dataLen u32][timestamp u32][00 02 00 01]` + data.

- Type: `MSG_VIDEO_DATA = 0x00020001`; heartbeat `0x00020002` with `dataLen = 0`.
- Data: One frame coded **H.264, Annex-B, 4 byte start codes** `00 00 00 01`. HU configures `MediaCodec` on `video/avc` (`DecodeUtil.java:195-197`) and at the same time parsit flow itself.
- Maximum frame: Java-HU `1920*1080*3/2 = 3 110 400` byte (`DecodeUtil.java:26,304-306`); buffer C++ 600 KiB (`CTranRecvPackageProcess.h:33`).
- `timestamp`: Java-HU reads it and **ignores** (`DecodeUtil.java:310-314`). CarProjection puts `(int)System.currentTimeMillis()` (`Utils.java:149,161`); in C++ MD-Lib is also just a parameter of `timeStamp`. [K]
- Encryption (if enabled): only the data block is encrypted, `dataLen` in header = ciphertext size (`DecodeUtil.java:354-362`).

### 5.2 SPS/PPS and IDR [C]

Requirements of Java-HU (`DecodeUtil.java:485-520`):

- First adopted frame Must start with SPS (NAL type 7) is checked `(buf[4] & 0x1F) == 7`. HU looking for the following two start codes `00 00 00 01` (`findFlag2`, :465-485), that is **SPS and PPS at the beginning of the first frame Concurrent**and remembers this prefix as `mSpsPps`. If the first frame It does not begin with SPSIt is silently thrown away.)`byteCount == 0`, return).
- If SPS+PPS is not followed by IDR, IDR will follow later.
- After the internal reset decoder HU Adds saved `mSpsPps` C framewhich begins **directly from IDR** (`(buf[4] & 0x1F) == 5`, `isIFrame`, :455-463; :375-398); frameThey are not “naked.” IDR"In a state of reset are discarded.
- CarProjection He behaves like this: SPS+PPS (BUFFER_FLAG_CODEC_CONFIG) Sticks **Only for the first keyframe**, next keyframe They go without SPS/PPS (`MediaCodecTool.java:97-124`), `I_FRAME_INTERVAL = 1` (c) (:73). Recommendation: repeat this method (first frame = SPS+PPS+IDR in one message; the following IDR naked). If sent SPS/PPS with each IDR, in a state of reset exactly such frame Not recognized as IDR And it will be thrown away until the next naked one. IDR. [K by code HU; Conclusion on Risk D]

### 5.3 Profile, Resolution, fps

- In HU code **net** restrictions on profile/level/ resolution; `MediaFormat` is created only with width/height of INIT_DONE, csd is not transmitted (SPS is taken from the stream) (`DecodeUtil.java:195-203`). [K]
- CarProjection Encode `COLOR_FormatSurface`Profile not specified (commented) `profile=1`, `level=256`; :77-78), bitrate Default 3 000 000, fps 30 (`MainActivity.java:176-177`; in `MediaCodecTool.java:70-72` Arguments in the code are confused places, in fact bit rate the " Getframe» = 3 000 000, frame rate «bit» = 30). Original permission 1280x720 (`MsgProcess.java:106-107`). [K]
- Recommendations [D]: Baseline/Main/High 4:2:0, without B-frame (Changan-branch HU puts `disreorder=1`, `DecodeUtil.java:25,198-202`, so reorder in decoder by default included), multiple sizes 16, 720p@30.
- Management fps: HU Reduces the target fps from 30 to 3 and sends `VIDEO_ENCODER_FRAME_RATE_CHANGE` (`DecodeUtil.java:336-346,636-666`); MD It should change frequency. encoder and (by protocol) respond `FRAME_RATE_CHANGE_DONE`. CarProjection It does not handle either. [K]

---

## 6. Touch and control (channel TOUCH, id 6)

Internal header 8 byte, as in CMD. Direction HU→MD. [K]

### 6.1 Main mode (MSG_TOUCH_ACTION = 0x00068001)

`CarlifeTouchAction {action:int32 = 1, x:int32 = 2, y:int32 = 3, optional pointerx:int32 = 4, optional pointery:int32 = 5}` (`PB/CarlifeTouchActionProto.proto`; fields 4, 5 are in `re/carlife-8.6.7/resources/CarlifeTouchActionProto.proto`).

- `action`Code: `MotionEvent`: 0 = DOWN, 1 = UP, 2 = MOVE (HU He writes `event.getAction()`, `HUJ/touch/TouchListenerManager.java:207-231`; MD-Side `CP/Utils.java:24-26`, `MsgProcess.java:242-310`). Multitouch in the protocol is (up to two fingers), see § 6.1.1. [K]
- `x`, `y`: **pixels in the co-ordinate system of the encoded video**Recalculated from the coordinates on the screen head unit: `x*phoneW/carW`, `y*phoneH/carH`where `phoneW/H` = Size of `VIDEO_ENCODER_INIT_DONE`, `carW/H` = dimensions SurfaceView head unit (`TouchListenerManager.java:211-212`, :488-494 `CarlifeActivity`). Start coordinates upper left corner. [K]
- The mode is enabled with the `TRANSPARENT_SEND_TOUCH_EVENT` flag from the head config unit: `true` → this mode (`CarlifeActivity.java:453-454`, `TouchListenerManager.java:60-73,88-101`). What mode does the real Honda have, is unknown. [D]
- CarProjection in MD-The party handles **only** `MSG_TOUCH_ACTION` and `MSG_TOUCH_CAR_HARD_KEY_CODE` (`MsgProcess.java:546-575`). Other types ignore. So, for our MD These two are the ones that we have, and the other two are the ones that we have. [K]

#### 6.1.1 Multitouch (second finger)

Phone side processing standard: Baidu CarLife 8.6.7, `CarlifeTouchManager.onActionEvent` (`re/carlife-8.6.7/sources/com/baidu/carlife/core/screen/touch/CarlifeTouchManager.java:640-662`). [C]

- `action` follows `MotionEvent`: `action & 255` is the code (0 DOWN, 1 UP, 2 MOVE, 5 POINTER_DOWN, 6 POINTER_UP); `action >> 8` is the pointer index. Second-pointer down/up uses `261` (5|1<<8) / `262` (6|1<<8). `6` lifts pointer index 0. Baidu handles at most two pointers and discards `action > 261/262`. [K]
- `x, y` identifies pointer 0; `pointerx, pointery` identifies pointer 1, including MOVE. `pointerx == 0 && pointery == 0` means no second pointer. On POINTER_DOWN, Baidu puts the second pointer at (x, y); on POINTER_UP, it uses that pointer’s last MOVE coordinates. [K]
- After POINTER_UP with 0 index the remaining finger comes in x, y; in Headswap its pointer_id for AA does not change (1). [D]
- Lee Slashes head unit The second finger depends on head unit; Headswap Writing in Log `TOUCH action=… пальцев=… тело=… байт` and one line `== МУЛЬТИТАЧ`. If with a pinch in the log only action 0/1/2 and the body before 8 byte, head unit The second finger doesn't. [D]

### 6.2 "Hard" mode (if `TRANSPARENT_SEND_TOUCH_EVENT=false`)

HU parses the `GestureDetector` touch and sling, all with the `CarlifeTouchSinglePoint {x=1, y=2, optional pointerx=3, optional pointery=4}` body in the same pixels (`TouchListenerManager.java:233-355`, `GestureListenerImp.java:36-161`):

| Code | Name | When |
|---|---|---|
| 0x0006800A | TOUCH_ACTION_BEGIN | `onDown` |
| 0x00068002 | TOUCH_ACTION_DOWN | start scroll (or in `onDown` if `SEND_ACTION_DOWN`) |
| 0x00068004 | TOUCH_ACTION_MOVE | `onScroll` |
| 0x00068003 | TOUCH_ACTION_UP | fling / timeout 150 ms after last MOVE / long press |
| 0x00068005 | TOUCH_SINGLE_CLICK | `onSingleTapUp` |
| 0x00068006 | TOUCH_DOUBLE_CLICK | double pressing |
| 0x00068007 | TOUCH_LONG_PRESS | `onShowPress` |
| 0x0006800B | TOUCH_ACTION_POINTER_DOWN | second finger lowered; Baidu: as action 261 without pointer-fields |
| 0x0006800C | TOUCH_ACTION_POINTER_UP | raised index finger 0; Baidu: as action 6 |
| 0x0006800D | TOUCH_ACTION_OTHERPOINTER_UP | raised second finger; Baidu: as action 262 |

Codes 0x6800B-D and pointer-Fields `CarlifeTouchSinglePoint {x=1, y=2, pointerx=3, pointery=4}` Known only by phone Baidu 8.6.7 (`CarlifeTouchManager.java:519-561`); which head unit They are sent, unknown. [D]

There's more `CarlifeTouchEvent`, `TouchFling`, `TouchScroll`, `TouchEventDevice/AllDevice` in `PB/` (for raw evdev-events and gestures); Java-HU and C++-API HU not used. [K]

### 6.3 Hardware Button

`MSG_TOUCH_CAR_HARD_KEY_CODE = 0x00068008`, the body `CarlifeCarHardKeyCode {keycode = 1}` (`TouchListenerManager.java:399-415`Codes keycode (`HUJ/CommonParams.java:244-292`): HOME=1, PHONE_CALL=2, PHONE_END=3, PHONE_END_MUTE=4, HFP=5, SELECTOR_NEXT=6, SELECTOR_PREVIOUS=7, SETTING=8, MEDIA=9, RADIO=0x0A, NAVI=0x0B, SRC=0x0C, MODE=0x0D, BACK=0x0E, SEEK_SUB=0x0F, SEEK_ADD=0x10, VOLUME_SUB=0x11, VOLUME_ADD=0x12, MUTE=0x13, OK=0x14, MOVE_LEFT=0x15, MOVE_RIGHT=0x16, MOVE_UP=0x17, MOVE_DOWN=0x18, MOVE_UP_LEFT=0x19, MOVE_UP_RIGHT=0x1A, MOVE_DOWN_LEFT=0x1B, MOVE_DOWN_RIGHT=0x1C, TEL=0x1D, MAIN=0x1E, MEDIA_START=0x1F, MEDIA_STOP=0x20, VR_START=0x21, VR_STOP=0x22, NUMBER_0..9 = 0x23..0x2C, STAR=0x2D, POUND=0x2E, DEL=0x2F, CLEAR=0x30, ADD=0x31. CarProjection reacts to SEEK_SUB/SEEK_ADD (previous/next track)`MsgProcess.java:549-564`). Names in C++-Transfers go up to 0x1E (`CTranRecvPackageProcess.h:208-237`). [K]

`MSG_TOUCH_UI_ACTION_SOUND = 0x00060009` (MD→HUThe sound of pressing is defined in both codes; HU-Library on the Channel CTRL Nothing is accepted (`CTranRecvPackageProcess.cpp:293-345` has no branch CTRL_CHANNELThis is a dead code. [K]

---

## 7. Audio

### 7.1 Channels

| Channel | id | Purpose | Source → Receiver |
|---|---|---|---|
| MEDIA | 3 | Music | MD→HU |
| TTS | 4 | voice navigation prompts | MD→HU |
| VR | 5 | Voice Assistant: Answers MD→HU, microphone HU→MD | Both |

Format of all three: **uncompressed PCM**, without codec. There is no AAC/Opus in the protocol. [K] (`HUJ/audioplayer/audiotrackmanager/AudioTrackManagerSingle.java:84-134` creates `AudioTrack` on `ENCODING_PCM_8BIT/16BIT`; `MEDIA_DATA` is written in `AudioTrack` as it is).

### 7.2 Init-

Initiator: **MD** (HU itself does not ask for anything). Before the data stream MD, the `*_INIT` with `CarlifeMusicInit` (media/VR) or `CarlifeTTSInit` (tts), the fields are the same:

`{sampleRate:int32 = 1, channelConfig:int32 = 2, sampleFormat:int32 = 3}`where `channelConfig` = **Number of channels** (1 = Mono, 2 = stereo; any other is interpreted as stereo), `sampleFormat` = **bit depth** (8 → PCM 8 Beat, all the rest → 16 bit) (`AudioTrackManagerSingle.java:113-125`). [K]

Defaults HU In case of error: music 44100/2/16, TTS 16000/1/16 (`PackageHeadAnalyseModule.java:208,256`). CarProjection sends `MEDIA_INIT` 48000/2/16 (`MsgProcess.java:654-658`) and data: internal sound capture (`AudioPlaybackCapture` or REMOTE_SUBMIX) 48000 Hz, stereo, PCM16in portions of 2560 byte (`MsgProcess.java:345-372,386-397`). [K]

Codes:

| Channel | INIT | DATA | Other |
|---|---|---|---|
| MEDIA | 0x00030001 | 0x00030006 | STOP 0x30002, PAUSE 0x30003, RESUME_PLAY 0x30004, SEEK_TO 0x30005 |
| TTS | 0x00040001 | 0x00040003 | END/STOP 0x40002 |
| VR (MD→HU) | 0x00050002 (INIT) | 0x00050003 (DATA) | STOP 0x50004, INTERRUPT 0x50006 (only Java) |
| VR (HU→MD) | | 0x00058001 MIC DATA | |

(`CommonParams.java:200-224`; C++ `CTranRecvPackageProcess.h:169-196`; in Java `MSG_VR_DATA = 0x00058001` It is a microphone, in C++ the same constant named `MSG_VR_MIC_DATA`, and `MSG_VR_DATA = 0x00050003` The sound of an answer.) [K]

Limits: music up to 100 KiB per message (`PCMPlayerUtils.java:103`, `CTranRecvPackageProcess.h:36`), TTS/VR up to 50 KiB (`:39,42`). Header size is twelve bytes, as in §2.3. [K]

### 7.3 Microphone (VR record)

- HU→MD the Channel VR (id 5), type `0x00058001`, header 12 byte, `timestamp = 0` (`HUJ/logic/voice/PcmSender.java:188-217`; C++ `sendMicData`, `CTranRecvPackageProcess.cpp:2865-2886`). Data: PCM16 mono **16 kHz** (`logic/voice/PcmRecorder.java:29-35,56`), optionally compressed to 8 KHz by simple thinning (`PcmSender.java:151-181`). Portion to 320 elements of the queue. [K]
- When HU Microphones: by status MD. `MIC_RECORD_WAKEUP_START` (0x00010022), `MIC_RECORD_RECOG_START` (0x00010024), `MIC_RECORD_END` (0x00010023) and `MODULE_STATUS` Module VR (`logic/voice/VoiceManager.java:84-120,160-190`). Microphone head unit It is generally used if `VOICE_MIC = 0`. [K]

### 7.4 Sound Transfer Mode

Config HU `AUDIO_TRANSMISSION_MODE` (`FeatureConfigManager.java:129-130`) and `PCMPlayerUtils.isBTTransmissionMode()`If the sound goes on Bluetooth (A2DP), HU Reset PCMwho came to USB, `if (!PCMPlayerUtils.isBTTransmissionMode()) mQueue.add(...)` (`MediaReceiveProcessor.java:126,148,171`). Values are not decrypted. [K by flag; value D]

---

## 8. Encryption AES / RSA [C]

Files: `HUJ/encryption/{EncryptConfig,EncryptSetupManager,RSAManager,AESManager}.java`.

- **Not necessarily in Java-HU by default.** Included only on initiative MD: he sends `MD_RSA_PUBLIC_KEY_REQUEST` (0x0001006A). Without it `isEncryptEnable()` = false (`EncryptConfig.java:22-24`: `DEBUG_ENABLE = true`, `AES_ENCRYPT_AS_BEGINE = false`; `EncryptSetupManager.java:87-97`). CarProjection encryption is not used. [K]
- Exception: if the HU config contains `CONTENT_ENCRYPTION = 1`, HU **not the** `VIDEO_ENCODER_INIT` after `MATCH_STATUS` and waiting for `MD_ENCRYPT_READY` (`CarlifeActivity.java:445-449,902-905`). Then MD is required to initiate RSA/AES. [K]
- If you put `AES_ENCRYPT_AS_BEGINE = true` (test mode), both sides encrypt everything from the very beginning with the key `"1234567890123456"` (`EncryptSetupManager.java:37,87-97`, `AESManager.java:32-40`). [K]

Sequence (CMD-channel):

1. MD→HU `MD_RSA_PUBLIC_KEY_REQUEST` (empty).
2. HU→MD `HU_RSA_PUBLIC_KEY_RESPONSE {rsaPublicKey}`: RSA-2048The key is created at startup. HU (`RSAManager.java:122-134`), in the form Base64 (NO_WRAP) from `PublicKey.getEncoded()`I mean, DER X.509 SubjectPublicKeyInfo (`:133-134`). (Parser `getmPubkey` in HU For some reason he uses `PKCS8EncodedKeySpec`but it is not called.)
3. MD→HU `MD_AES_KEY_SEND_REQUEST {aesKey}`: 16-symbolic key (ASCII), encrypted RSA `RSA/ECB/PKCS1Padding` Public Key HU, then Base64 (NO_WRAP) (`RSAManager.java:80-120`, `EncryptConfig.java:29`; `EncryptSetupManager.java:67-85`). HU Decoding and **Encryption immediately.**Before sending a response)`CarlifeActivity.java:890-901`).
4. HU→MD `HU_AES_REC_RESPONSE` (empty; body zero, therefore not encrypted).
5. MD→HU `MD_ENCRYPT_READY` (empty). HU reacts by sending `VIDEO_ENCODER_INIT` (`CarlifeActivity.java:902-905`).

After inclusion:

- Encrypted **body of any message with length > 0** On all channels and in both directions: CMD and TOUCH (`ConnectSocket.java:140-152,258-266`), VIDEO (`DecodeUtil.java:354-362`), MEDIA/TTS/VR Data and init (`MediaReceiveProcessor.java:114`, `NaviReceiveProcessor.java:101`, `VRReceiveProcessor.java:108`, `PackageHeadAnalyseModule.java:185,233`), microphone (`PcmSender.java:203-212`). header Open.
- Algorithm: `Cipher.getInstance("AES")` on Android = **AES/ECB/PKCS5Padding**, key 16 bytes = bytes of string `aesKey` (`AESManager.java:56-63`). [K, value "AES» Default = ECB/PKCS5 This is D from Knowledge. JCE]
- In header, the field `len` = ciphertext length (`ConnectSocket.java:146`).

---

## 9. Minimum path "MD shows 720p video and takes touch"

Prerequisites: Both ends use external header from § 2.1, 8-Byte header the Canal de France **Separate** bulk-transferThen the body ( § 1.4). Encryption and authentication are not used.

### 9.1 Sequence

| # | E.g. | Channel | Message | Body |
|---|---|---|---|---|
| 0 | HU | USB | AOA: GET_PROTOCOL, 6x SEND_IDENT, START_ACCESSORY, wait `18D1:2D0x`, claim interface 0 | see § 1 |
| 1 | HU→MD | CMD | `HU_PROTOCOL_VERSION` 0x00018001 | `08 01 10 00` |
| 2 | MD→HU | CMD | `PROTOCOL_VERSION_MATCH_STATUS` 0x00010002 | `08 01` |
| 3 | HU→MD | CMD | `STATISTIC_INFO` 0x00018027 | cuid etc (MD can ignore) |
| 4 | HU→MD | CMD | `HU_INFO` 0x00018003 | CarlifeDeviceInfo |
| 5 | MD→HU | CMD | `MD_INFO` 0x00010004 | CarlifeDeviceInfo with `os="Android"`, `sdk_int >= 21` |
| 6 | MD→HU | CMD | `MD_AUTHEN_RESULT` 0x0001004B | `08 01` (as modeled by CarProjection, after step 3) |
| 7 | HU→MD | CMD | `VIDEO_ENCODER_INIT` 0x00018007 | `08 80 0A 10 D0 05 18 00` (1280x720, fps 0) |
| 8 | MD→HU | CMD | `VIDEO_ENCODER_INIT_DONE` 0x00010008 | `08 80 0A 10 D0 05 18 1E` (1280x720, 30) |
| 9 | MD→HU | CMD | `FOREGROUND` 0 x0001001B | empty |
| 10 | HU→MD | CMD | `VIDEO_ENCODER_START` 0x00018009 | empty |
| 11 | HU→MD | CMD | `LAUNCH_MODE_NORMAL` 0x0001801D | empty (optional) |
| 12 | HU→MD | CMD | `MODULE_CONTROL` 0x00018028 (CONNECT=2) | `08 05 10 02` |
| 13 | MD→HU | VIDEO | `VIDEO_DATA` 0x00020001The First Time frame SPS+PPS+IDR, next P-frame/Nude IDR; heartbeat `0x00020002` (len 0) in a simple | H.264 Annex-B |
| 14 | HU→MD | TOUCH | `TOUCH_ACTION` 0x00068001 (DOWN=0, MOVE=2, UP=1) | `08 act 10 x 18 y` |
| 15 | HU→MD | TOUCH | `TOUCH_CAR_HARD_KEY_CODE` 0x00068008 (optional) | `08 key` |

Optional for minimum video+touch: MEDIA/TTS/VR, MODULE_STATUS, FEATURE_CONFIG, FRAME_RATE_CHANGE (but it is desirable to accept and not fall).

### 9.2 Bytes on wire (examples)

`HU_PROTOCOL_VERSION`:
```
outer: 00 00 00 01  00 00 00 0C
inner: 00 04 00 00  00 01 80 01  08 01 10 00
```
`MATCH_STATUS`:
```
outer: 00 00 00 01  00 00 00 0A
inner: 00 02 00 00  00 01 00 02  08 01
```
`VIDEO_ENCODER_INIT` 1280x720, fps 0:
```
outer: 00 00 00 01  00 00 00 10
inner: 00 08 00 00  00 01 80 07  08 80 0A 10 D0 05 18 00
```
`VIDEO_ENCODER_START`:
```
outer: 00 00 00 01  00 00 00 08
inner: 00 00 00 00  00 01 80 09
```
`TOUCH_ACTION` DOWN (640,360):
```
outer: 00 00 00 06  00 00 00 10
inner: 00 08 00 00  00 06 80 01  08 00 10 80 05 18 E8 02
```
`VIDEO_DATA` (n byte H.264):
```
outer: 00 00 00 02  <u32: n+12>
inner: < u32 n> < u32 ts> 00 02 00 01 < n byte Annex-B>
```

### 9.3 What to implement

**Fake HU (Python, pyusb + libusb/WinUSB)**:

- Switching to AOA and opening the 0 interface; endpoints find in direction.
- Record: `write(outer8)` by separate transfer, then `write(body)` pieces ≤ 16384.
- Reading: Big Buffer (16384), accumulate bytes in the stream, parse by external header `chan/len`Then on the inside. header Channel ( § 2.3). Do not rely on borders transfer when parsing.
- States: CONNECTED → send VERSION → wait for MATCH (stop unless 1) → STATISTIC, HU_INFO, INIT(1280x720,0) → wait for INIT_DONE → START → receive/decode video with ffplay/PyAV → send TOUCH_ACTION.
- MD liveness timeout: no video messages for more than six seconds means disconnect, matching a real HU.
- Accept and log all unknown types without falling.

**MD-Application (Android)**:

- `uses-feature android.hardware.usb.accessory`, accessory filter `manufacturer="Baidu" model="CarLife"`, `USB_ACCESSORY_ATTACHED`; `UsbManager.openAccessory()` → `FileInputStream/FileOutputStream` (as modeled on `CP/MainActivity.java:238-262`).
- Read stream with external header, `chan` manager, on CMD internal 8 byte parsing.
- Reply at steps 2, 5, 6, 8, and 9. On `START`, start the encoder (MediaCodec, Surface, H.264) and send frames over VIDEO; send a frame or heartbeat at least once every five seconds.
- touch: of `TOUCH_ACTION` (x, y in frame INIT_DONE pixels) form events for Android Auto (or for the host showing AA). What exactly to substitute in `INIT_DONE` in size, decide when integrating AA.
- Record in one stream only: header + body in a row, without interrupting messages from different channels.

---

## 10. Open questions and risks

1. **AUTHEN on others head unit.** In open source, neither HU Baidu (Java), nor MD (CarProjection) do not verify the signature. Scheme of DOC (random line, signature on side) MD) without a third-party key is not played. Verified head unit Honda Vezel 2024 `HU_AUTHEN_REQUEST` He does not go and accept the “false” `MD_AUTHEN_RESULT{true}`; others head unit They may behave differently. [D]
2. **The real version of the protocol head unit.** Source versions 1.0 (2015-2017); `icar` Refers to CarLife 8.4.2. In new versions, other fields are possible, mandatory encryption (`CONTENT_ENCRYPTION`), additional messages. We do not know what will announce head unit in `HU_FEATURE_CONFIG_RESPONSE`. [D]
3. **Lines AOA on head unit.** Filter in our application `Baidu/CarLife`; if head unit sends other lines (OEM-The application will not receive `USB_ACCESSORY_ATTACHED`I need a dump. [D]
4. **NUL-Terminator AOA-lines.** Baidu slet without NUL (§1.1). What really does head unit-host and how Android processing, depends on the core; for PC-Test Copy Baidu. If the phone does not accept, try with NUL. [D]
5. **Boundaries transfer.** Critically send header Separate transferThe body in one transfer ≤ 16 KiB ( § 1.4). For head unit As a host to check what is real head unit It does the same thing (by code). Baidu does). For our MD read stream, do not rely on one `read` = One message. [D. External knowledge of the nucleus]
6. **Windows/WinUSB/libusb.** For PC-Test phone in normal mode should be tied to WinUSB (ZadigAfter the change of 18D1:2D0x appears new device, driver WinUSB must be re-linked; `claimInterface(0)`. There is no information in open sources. [D]
7. **Size frame from MD.** Real HU Baidu takes frame from ~ 3.1 MB; hardware decoder head unit Honda Maybe more strictly. profile/level and number reorder-frame. Start with Baseline, 720p, without B-frame, fps 30, bitrate 3-4 Mbit/s [D]
8. **SPS/PPS with each IDR Or just the first one. frame.** Code Java-HU Waiting for the first frame C SPS+PPS and “naked” IDR after reset (§5.2). In real HU Honda behavior may be different. Make a setting. [D]
9. **Heartbeat.** Real MDprobably sends `VIDEO_HEARTBEAT` periodically (period unknown). If the picture is static, and encoder does not repeat frameWe have to send heartbeat or forced frame. [D]
10. **Difference of codes `CAR_DATA_SUBSCRIBE_DONE` (0x00010032 in C++ vs 0x00018032 in Java)** and body type u `CAR_DATA_START` (list vs single). Affects only car data (speed/GPSNot video or not. touch. [K, both values are in the code]
11. **touch-Mode head unit.** Mode `TRANSPARENT_SEND_TOUCH_EVENT` (one `TOUCH_ACTION` against a set of gesture messages) is defined by the config head unit; need to take both. Multitouch: up to two fingers through `action>>8` and pointerx/pointery (§6.1.1Does he send it specifically? head unitIt can only be seen from the log. [K/D]
12. **Sound.** If head unit in BT-audio mode (`AUDIO_TRANSMISSION_MODE`), PCM by USB is ignored, the sound will have to be led by A2DP. [K by flag HU, D by value ]
13. **`MD_INFO.sdk_int`.** At `sdk_int < 21` Java-HU the “ ShowAOA not supported ( § 4.4) to put the real SDK phone. [K]
14. **`icar` and `CarLife-MD-Lib`.** For MD-This is not the source of truth.`icar` without code, `MDL` without MD-command). Single sample MD-Logic is `CarProjection`And he is cut off (no feature config, no heartbeat, no FRAME_RATE_CHANGEno encryption, no multimedia metadata). [K]
