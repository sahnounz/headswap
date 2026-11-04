# Headswap architecture

A map for anyone opening the repository for the first time: where code lives, how data flows, and which invariants must hold.
Design rationale is in [the ADRs](adr/README.md). Protocol references are `docs/carlife-protocol.md`,
`docs/carlife-v2-handshake.md`, and `docs/aa-headunit.md`.

Code references use class and function names rather than line numbers, which quickly become stale. Search with `rg` or an LSP.
`$P` means `android/app/src/main/java/com/yegorkapomidorka/headswap`.

## Purpose

The phone bridges the Baidu CarLife protocol used by the head unit and Android Auto running on that same phone.
The car connects through USB AOA or Wi-Fi. Headswap connects to Android Auto through TCP at `127.0.0.1:5277`.

To the head unit, Headswap is a Baidu CarLife mobile device (MD). To Android Auto, it is a “Desktop Head Unit”.
The user manually enables Android Auto's head unit server in developer settings (ADR 0001, 0002).

The head unit speaks CarLife rather than Android Auto, so Android Auto features require equivalent CarLife messages.
Examples include map day/night mode (CarLife has CHANGE_MD_THEME but no headlight sensor), gear and reverse,
speed, and other vehicle events. We do not blindly request CarLife vehicle data (ADR 0010).
Check for a corresponding CarLife message before promising an Android Auto feature.

## Stack and build

- One Gradle module, `:app`, under `android/`; minSdk 29, target 37, JDK 17.
- `MainActivity : android.app.Activity`; its screens are built programmatically (`ui/Kit.kt`, `ui/Screen.kt`).
  The Desktop launcher uses AndroidX Compose. Strings live in `res/values` (English) and `res/values-ru`; update both together.
- Variants: `debug`, `release`, and `smoke` (a debug-based build type with the `.smoke` suffix, installed alongside release).
  There are no product flavors. `-Pwlbeta=false` disables wireless.
- AA certificates (`assets/aa/{hur,jvc}`) are not tracked. `syncAaCerts` copies them from the main checkout; release builds fail if they are absent.
  Release signing settings: `~/.android-keys/headswap-release.properties`.
- Android tests: `cd android; bash gradlew testDebugUnitTest` (JUnit4, `android/app/src/test/...`).
  Python tests: `pc-headunit/tests/` and `python -X utf8 -m unittest tools/test_logdiag.py`.

## Packages

| Location | Contents |
|---|---|
| `$P/` | CarLife and service code. `BridgeService` orchestrates sessions; `CarLifeSession` handles one head unit session. `Handshake` and `HuAuth` implement the handshake and HU_AUTHEN. `Protocol.kt` contains `Ch`, `Msg`, `Wire`, and `FrameParser`; `Proto.kt` implements protobuf. `VideoSource` provides placeholder video until AA arrives. Also `AppLog` and `CrashLog`. |
| `$P/aa/` | Android Auto and the bridge core: `AapSession` (TCP/TLS to 5277), `AapMessages` (SDR/channels), `AaBridgeCore` (routing and head unit flags), `AaConnector` (reconnection), and `BridgeHost` (retains AA between CarLife sessions). Video: `AaTranscoder`, `GlRelay`, `FeedQueue`, `IdrSplice`, `IdrIntra`, `RefDecoder`, `SpsCrop`, `VideoMode`, `CarPreset`, `BitratePolicy`, `IdrBitrateCeiling`, `ThermalThrottle`. Audio: `AaAudio`, `MusicResampler`, `Equalizer`, `TtsPreroll`. Microphone: `AaMic`, `PhoneMicCapture`. Metadata: `AaMeta`/`MetaRelay`. Touch: `TouchPacer`, `TouchDecimator`. Settings: `AaSettings`/`AaPrefs`. Diagnostics: `AaServerCheck`, `AaProbe`. |
| `$P/link/` | Head unit transport: `CarTransport`, `TransportCaps`, `StreamTransport` (USB/debug TCP, one stream), and `MultiSocketTransport` (wireless, six sockets). `bt/`: RFCOMM `carlife` and Wi-Fi Direct (`CarlifeBtProbe`, `P2pJoin`). `car/`: selected car, ACL auto-start, and car Wi-Fi (`CarProfile`, `CarAclReceiver`, `CarNetwork`, `CarLogic`). `wifi/`: `HotspotBeacon` (UDP 7999) and `HotspotDetect`. `diag/WLog`: wireless logs. |
| `$P/ui/` | Screens (`HomeScreen`, `OnboardingScreen`, `SettingsScreen`, `DevScreen`, `DiagScreen`, `CarChooser`), pure logic (`HeadswapState`/`HomeLogic`, `Readiness`), and ZIP log export (`LogShare`). |
| `$P/diag/` | `LogDiag`, the Kotlin port of `tools/logdiag.py`, using the same rules. |
| `$P/update/` | `UpdateLogic` (pure logic for GitHub releases/latest) and `Updater` (download, SHA-256, PackageInstaller). ADR 0012. |
| `src/debug`, `src/release` | `DebugTransport.kt`: TCP to the head unit emulator in debug; a stub in release. |
| `pc-headunit/` | Python emulators: `hu.py` emulates an AA head unit and generates golden test data; `carlife_hu.py` emulates a CarLife head unit for testing the bridge. |
| `tools/` | `logdiag.py`/`logdiag_rules.json` diagnose disconnects; `logdigest.py` summarizes logs. |

The core files `AaBridge.kt`, `CarLifeSession.kt`, and `BridgeService.kt` are large. Read the relevant functions rather than the entire files.

## Data flow

**1. Start.** USB: the head unit switches the phone to Baidu/CarLife accessory mode → `USB_ACCESSORY_ATTACHED` in `MainActivity` →
`Actions.scan` → permission → `BridgeService.onStartCommand` → `command()` → `startSession(StreamTransport.fromPfd(...))`.
Wireless: a button (`ACTION_WIFI_B`) or the selected car's ACL event (`CarAclReceiver` → `ACTION_WIFI_B_AUTO`) → `startWifiB` → `startTcp6`.
USB takes priority. If USB disconnects while the car remains nearby over Bluetooth, switch to wireless after three seconds.

**2. Wireless transport.** The phone listens on six ports: CMD 7240, VIDEO 8240, MEDIA 9240, TTS 9241, VR 9242, TOUCH 9340.
The head unit connects as a client. Three paths establish a shared network:
(a) Bluetooth RFCOMM `carlife` and joining the head unit's P2P group (Mazda G6S); (b) the phone hotspot and `HotspotBeacon`;
(c) car Wi-Fi, where the phone joins the head unit's network (`CarNetwork`, VW). ADR 0014.

**3. CarLife session** (`CarLifeSession.onCmd`):
HU_PROTOCOL_VERSION → initial batch (`Handshake.startBatch`) → HU_INFO/STATISTIC_INFO (head unit detection) →
HU_AUTHEN/`HuAuth` → FEATURE_CONFIG (AUDIO_TRANSMISSION_MODE=1 means Bluetooth audio, with no USB audio) →
VIDEO_ENCODER_INIT (use the selected video mode's size; only remember the head unit's size) → START
(start automatically after 1.5 seconds if absent) → PAUSE/START/RESET during the session → MD_EXIT.

**4. Android Auto** (`BridgeService.createHost` → `AaConnector` → `AapSession`): TCP 127.0.0.1:5277 → VERSION → TLS
(jvc/hur certificates) → AUTH_COMPLETE → SDR. `head_unit_make/model` always identifies “Google / Desktop Head Unit”;
the selected car brand goes only in `make` (ADR 0015). Channels:
CONTROL0, SENSOR1, VIDEO2, INPUT3, SPEECH4, SYSTEM5, MEDIA6, MIC7, BT8, MEDIA_PLAYBACK9, NAVIGATION10.
Shutdown always follows ByeBye → shutdownOutput → read through EOF → close.

**5. Video** (`AaBridgeCore.onFrame` → `aaFrame`):

- *Transcoding* (manually enabled, ADR 0004): `feedTranscoder` → `AaTranscoder` (decoder → `GlRelay` → encoder,
  repeat the frame every 100 ms; `aa-trans-feed` thread via `FeedQueue`, ADR 0011) → `onEncoderFrame` → head unit.
- *Passthrough* (default since 3.0, ADR 0018): `passthroughFrame`. `SpsCrop` removes margins by editing the SPS.
  Inject a locally generated IDR every two seconds (`IdrSplice`, ADR 0005); the developer option `AaPrefs.ownIdr` disables this.
- Sending: `CarLifeSession.sendVideo` → `AnnexB.fixSps` → `sendVideoData`. Queue overflow (>30 frames or about 500 ms)
  drops frames until the next IDR.
- Four places choose passthrough or transcoding: `AaPrefs.transcode`, `VideoGeometry.effective()`,
  `BridgeService.startSession` (force transcoding if the mode cannot be cropped), and head unit runtime flags (Pioneer, Mobis).

**6. Audio.** AA MEDIA(6) → CarLife MEDIA(3); AA SPEECH(4) → CarLife TTS(4). SYSTEM(5) is not forwarded.
Music is 48 kHz stereo (`MusicResampler` converts 44.1/32/24/22 kHz); TTS is 16 kHz mono.
Follow Baidu's ordering: MODULE_STATUS NAVI=1 before TTS; delay TTS_STOP by 1.5 seconds.

**7. Microphone.** The head unit sends VR_MIC_DATA (PCM16, 16 kHz) → `AaMicRouter` → `AapSession.sendMicPcm`.
Modes are car/phone/auto. In auto, switch to the phone microphone after two seconds without data from the head unit.

**8. Touch.** `CarLifeSession.onTouch` → `AaBridgeCore.onCarlifeTouch` → `TouchDecimator` → `dispatchTouch` →
`VideoGeometry.mapTouch` → `AapSession.sendTouch`. Steering wheel buttons use `onCarlifeHardKey`.

**9. Navigation and instrument cluster.** AA NAVIGATION(10) → `MetaRelay` → `sendNaviTurn` (NAVI_NEXT_TURN_INFO),
only after the head unit subscribes. `HondaClusterRoad` shortens road names to fit Honda's instrument cluster.

**10. Media metadata.** AA MEDIA_PLAYBACK(9) → `MetaRelay` → `sendMediaInfo` (JPEG album art below 30 KB).

**11. End.** USB disconnect/EOF → `BridgeService.onSessionClosed`. `BridgeHost` retains AA for eight seconds
and passes it to the next CarLife session.

## Head unit workarounds

Enable a workaround only on the affected head unit, behind a flag (ADR 0006). The brand-wide exception from `CLAUDE.md`
applies fixes to all models and channels of a brand; Honda is detected by channel prefixes 2077/2083.

`AaBridgeCore.onHuInfo` examines manufacturer/model/product/board from HU_INFO. `onHuStatistic` examines
`STATISTIC_INFO.channel`, which effectively identifies the head unit firmware. Flags are `@Volatile var hu*` properties
in `AaBridgeCore`; channel constants are in its companion object.

| Flag | Head unit | Behaviour |
|---|---|---|
| `huDesayG6S` | Mazda CX-5 Desay G6S, ch 20622116 | No route NAVI=1; empty VIDEO_DATA on PAUSE over USB, heartbeat over wireless. |
| `huHonda` | ch 2077xxxx/2083xxxx | Fit roadName to the cluster, wait for subscription, re-arm NAVI. |
| `huVw20082120` | ch 20082120/22/34/14, 21290002 | Heartbeat every 100 ms on PAUSE; the name is historical (ADR 0007). |
| `huIdleVideoHeartbeat` | 20082114, 21290002 | Heartbeat even without PAUSE after one second without frames. |
| `huVwCns2Delphi` | 20082114 | Speech over VR, touch pacing, custom call buttons. |
| `huVwBt1420` | VW BT 1420 | Wireless heartbeat on PAUSE and warm resume. |
| `huVw20082138` | VW ID: 20082138, 20082128 plus VW BT 9491, ID. UNYX preset | IDR-based bitrate ceiling (ADR 0008). |
| `huHyundaiCn7` | product `wp_cn7cpe_cn` | 32 kHz music. |
| `huPioneerRm56` | Pioneer RM56E8B | Transcoding, 800 kbit/s, ≤10 fps, 22 kHz music. |
| `huMazda20622111` | ch 20622111 | VIDEO_DATA ≤15000 bytes, 1.5 Mbit/s, album art ≤12 KB (ADR 0009). |
| `huMobisNx4c` | board/model CCNC, ch 20022188 | Baidu mode: single-write AOA, 20 fps, dedicated IDR ceiling. |

To add a head unit:

1. Add its channel/name constant to the `AaBridgeCore` companion object.
2. Add an `@Volatile var huXxx` property with `private set` beside the existing flags.
3. **Reset it in `attach()`.** `AaBridgeCore` outlives a CarLife session during the eight-second grace period;
   without a reset, the flag leaks into the next head unit session.
4. Set it in `onHuInfo`/`onHuStatistic`.
5. Implement the behaviour in `CarLifeSession` (limits, chunking, heartbeat) or the core.
6. Add a test in `aa/AaBridgeTest.kt`.
7. Optionally add a `CarPreset` and English/Russian strings.

Resolution presets are `VideoMode` enum values: R720 (default), R800, R1024, R1080, WIDE*, WIDE_2_1, WIDE_640, WIDE_976.
`CarPreset` applies transcode/videoMode/DPI/fps/musicRate together. **Enum names are persisted in preferences; do not rename them.**

## Logs

- `AppLog` writes to logcat tag `Headswap` and `headswap-<date>.log` in external files. `AppLog.msg` uses
  `RX|TX <CHANNEL> 0x… NAME len=…`. `WLog` uses `WL <phase> <event> k=v`. `CrashLog` records crashes and
  previous process exit reasons such as `low_memory`.
- `LogShare` exports a ZIP with `device.txt`, the last 4 MiB of up to five logs, and `wireless.log`.
  Full-log locations are documented in the upstream `headswap-logs` memory.
- Useful stable markers include `== USB_ACCESSORY_ATTACHED`, `== HU_INFO:`, `== STATISTIC_INFO:`,
  `VIDEO_ENCODER_PAUSE`, `BRIDGE IDR -> CarLife`, and EIO. Some existing runtime messages are Russian;
  preserve their exact spelling when searching. IOException text comes from `StreamTransport`.
- **Start log analysis with `tools/logdiag.py`**, then run `logdigest.py`. Rules are shared with the app
  (`tools/logdiag_rules.json` → assets through `copyLogdiagRules`). **Log text is a contract:** if it changes,
  update the JSON markers too (ADR 0016).

## Invariants

- **AA server 5277 is single-use.** An abrupt disconnect or a bare TCP connect/close can stop it; the app cannot restart it.
  Close through ByeBye. Start a new AA session only after the old one fully closes. Do not interrupt live AA to change mode/DPI;
  apply the new mode after the user restarts the server. Do not run `AaServerCheck`/`AaProbe` while the bridge is live. ADR 0003.
- **After losing a video frame, do not send P-frames.** Wait for an IDR; request one at most every 1.5 seconds.
- **All H.264 sent to the head unit goes through `AnnexB.fixSps`.** Head units reject Exynos SPS data otherwise.
- **Never send CAR_DATA_START blindly.** It is currently absent from the code. A blind START froze video on Vezel.
  Send it only after RSP and only for advertised modules (ADR 0010).
- **Head unit packets are ≤16 KiB** (`Wire.CHUNK`); some head units require smaller chunks (ADR 0009).
- **Reset all `hu*` flags in `attach()`.**
- **Only the main thread changes `BridgeService` state.** Other threads dispatch callbacks through `main.post`.
- Passthrough cropping requires SPS cropping (`spsCropable`); otherwise fall back to R720.
- Never log passwords, SSIDs, or keys.
- Experiment switches in the `AaBridgeCore` companion object: `PAUSE_KEEPALIVE_ENABLED`, `NAVI_STATUS_ENABLED`,
  `MODULE_MUSIC_ENABLED`, `ROUTE_NAVI_STATUS_ENABLED`, `AIR_WARM_RESUME_ENABLED`.

## Symptom-to-code guide

| Symptom | Where to look |
|---|---|
| EIO seconds after PAUSE | `CarLifeSession.pauseHeartbeat`, `huVw20082120`; ADR 0007. |
| EIO immediately after a large IDR | `IdrBitrateCeiling` and channel-specific limits; ADR 0008. |
| USB stalls after a large packet | `Wire.CHUNK`, `huMazda20622111`; ADR 0009. |
| Black screen after the reverse camera | Locally generated IDR (`IdrSplice`); ADR 0005. |
| Margins, stretched image, wrong size | `VideoMode`, `SpsCrop`, VIDEO_ENCODER_INIT in the log. |
| AA fails to connect, no VERSION_RESPONSE | Server 5277 stopped; ADR 0003 and the upstream `hu/` case memory. |
| Wireless disconnects | `MultiSocketTransport`, `P2pJoin`, `CarNetwork`, and `WL` lines; VPNs can break sockets. |
| Silent or muted audio | `AaAudio`, AUDIO_TRANSMISSION_MODE, TTS_STOP, and the head unit's selected audio source. |
| Missing instrument cluster guidance | Head unit subscription, `MetaRelay.sendNaviTurn`, `HondaClusterRoad`. |
| Inaccurate or jerky touch | `VideoGeometry.mapTouch`, `TouchDecimator`, `TouchPacer`. |

Vehicle-specific histories live in the upstream Claude case memory (`memory/hu/INDEX.md`), outside this public repository
because they contain user data.
