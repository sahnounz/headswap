# Headswap: Android Auto on CarLife head units

Headswap is an Android app that runs Android Auto on head units that only support Baidu CarLife 2.0 over USB, commonly found in Chinese-market cars. The phone connects to Android Auto's head unit server at `127.0.0.1:5277`, acts as a head unit, and presents itself to the car as a CarLife phone over USB (AOA). Touch input, audio, and microphone data are relayed between the two protocols. No dongle is required. Verified on a 2024 Honda Vezel. Unofficial; not affiliated with Google, Baidu, or Honda. GPL-3.0.

![Android Auto on the factory Honda Vezel head unit](docs/screenshot.jpg)

## Purpose

Many Chinese-market cars have factory head units with Baidu CarLife but no Android Auto. Instead of using a dongle or replacing the head unit, Headswap runs on the phone itself and bridges Android Auto's video and controls to the CarLife protocol understood by the car.

## How it works

The phone runs both Android Auto and Headswap. Headswap connects to Android Auto's local head unit server, then relays the session to the car through USB AOA using CarLife 2.0.

- It connects as a head unit over TCP, negotiates the protocol version and TLS, discovers services, and receives H.264 video at 1280×720.
- It acts as a CarLife phone for the car, enters USB accessory mode (AOA), completes the CarLife 2.0 handshake, and sends video through the VIDEO channel.
- In transcoding mode, video is re-encoded and the last frame is repeated every 100 ms to prevent head units from dropping USB on a static screen. Since version 3.0, direct video passthrough is the default; see [ADR 0018](docs/adr/0018-passthrough-default-3-0.md).
- Touch coordinates from the head unit are mapped to the Android Auto video frame and forwarded as touch events.
- Android Auto audio and the car microphone are carried over CarLife USB channels. See the call limitation below.
- The user manually enables Android Auto's head unit server in developer settings. Headswap opens those settings but does not use an accessibility service to start the server. The server is single-use, so Headswap avoids stopping it or unnecessarily closing the Android Auto session.

## Compatibility

Reported compatible factory head units, all for the Chinese market:

| Vehicle | Baidu CarLife |
| --- | --- |
| Dongfeng Peugeot 5008 2024 | Not specified |
| Honda Breeze, second generation | Not specified |
| Honda CR-V, sixth generation, before facelift | Not specified |
| Honda CR-V 2026 1.5T | 2.0 |
| Honda CR-V 2026 hybrid, with limitations | Not specified |
| Honda Vezel 2022–2024 (GAC Honda) | 2.0 |
| Hyundai Elantra, seventh generation, before facelift | Not specified |
| Hyundai ix35 2021 | Not specified |
| Jetta VS5/VS7, Baidu CarLife only; J-Link is unsupported | 2.0 |
| Kia K3 2023 | Not specified |
| Kia Sportage Ace | Not specified |
| Mazda CX-5 2025–2026 | 2.0 |
| Mazda CX-50 | Not specified |
| Mitsubishi Outlander, fourth generation, 2022 | Not specified |
| Skoda Kamiq GT 2021 | Not specified |
| Skoda Octavia Pro | 2.0 |
| Toyota Corolla Cross 2024–2025 | 2.0 |
| Toyota Levin | Not specified |
| Volkswagen Golf 8 Pro 2021 | Not specified |
| Volkswagen ID.Unyx | Not specified |
| Volkswagen Lamando L | Not specified |
| Volkswagen Sagitar 2021–2022, before facelift | 2.0 |
| Volkswagen T-Roc facelift 2026 | Not specified |
| Volkswagen Tharu XR 2025–2026 | 2.1 |
| Volkswagen Tiguan L Pro / Teramont Pro | Not specified |

The [project website](https://yegorkapomidorka.com/headswap/) maintains the current compatibility list. Reports for other vehicles are welcome through the Telegram and 4PDA links below. Minimum Android version: 10 (API 29).

## Installation

1. Download the APK from the [project website](https://yegorkapomidorka.com/headswap/).
2. Enable Android Auto developer mode: open its settings and tap **Version** ten times. From the three-dot menu, select **Start head unit server**. This step is manual; the **Open Android Auto** button in Headswap opens the settings.
3. Connect the phone to the head unit over USB.

Versions newer than 1.1.10 can find updates in the [upstream GitHub releases](https://github.com/YegorkaPomidorka/headswap/releases) and install them over the current version while retaining settings. Disable this in **Settings → Updates**. Android asks for permission to install apps from Headswap on the first update.

## Building from source

JDK 17 is required.

```sh
cd android
./gradlew assembleDebug
```

Release signing settings are read from `~/.android-keys/headswap-release.properties`: `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Without this file, only debug builds are available.

### Head unit certificates

Certificates are not included in this repository. Android Auto accepts head unit certificates signed by Google Automotive Link. The public certificates and keys used by open-source implementations are available in:

- [opencardev/aasdk](https://github.com/opencardev/aasdk), a continuation of [f1xpl/aasdk](https://github.com/f1xpl/aasdk): the JVC Kenwood certificate in `src/Messenger/Cryptor.cpp` (lines 253–299), also in `cert/headunit.crt` and `cert/headunit.key`.
- [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived): `app/src/main/res/raw/cert` and `privkey`.

Place the PEM files at:

```text
android/app/src/main/assets/aa/jvc/cert.pem
android/app/src/main/assets/aa/jvc/key.pem
```

And/or, for the headunit-revived set:

```text
android/app/src/main/assets/aa/hur/cert.pem
android/app/src/main/assets/aa/hur/key.pem
```

For the PC emulator, use `pc-headunit/certs/jvc/` and `pc-headunit/certs/hur/`. Files matching `*.pem` and `*.key` are ignored by Git.

## Repository layout

- `android/`: the Kotlin app, package `com.yegorkapomidorka.headswap`. Includes the bridge (`BridgeService`, `CarLifeSession`, `CarLifeState`, `AppLog`), Android Auto client (`aa/`), and interface (`ui/`).
- `pc-headunit/`: Python head unit emulators for PC debugging. See its [README](pc-headunit/README.md).
- `docs/`: [architecture](docs/ARCHITECTURE.md), [architecture decisions](docs/adr/README.md), and protocol documentation: [Android Auto](docs/aa-headunit.md), [CarLife over AOA](docs/carlife-protocol.md), and the [CarLife 2.0 handshake](docs/carlife-v2-handshake.md).
- `tools/`: log diagnostics and development utilities. See its [README](tools/README.md).

## Limitations

- Calls use Bluetooth HFP; they are not carried over USB.
- Android Auto's head unit server is single-use. After a session disconnects, it may need to be enabled manually again.
- HyperOS blocks `adb input`, so some debugging techniques are unavailable.
- CarLife 2.0 support implements the features required by tested head units. Head units requiring unsupported authentication or encryption will not work.
- Android Auto may change the behaviour of its undocumented developer head unit server at any time.

## Links

- [Website and APK](https://yegorkapomidorka.com/headswap/)
- [Upstream GitHub releases](https://github.com/YegorkaPomidorka/headswap/releases), also used for in-app updates
- [Telegram](https://t.me/headswap)
- [4PDA discussion](https://4pda.to/forum/index.php?showtopic=1127415)

## Disclaimer

This is an unofficial project, unaffiliated with Google, Baidu, and Honda. Android Auto, CarLife, and Honda are trademarks of their respective owners. Use at your own risk. Do not interact with the app while driving.

## License

GNU GPL v3.0; see [LICENSE](LICENSE). © 2026 Yegor (yegorkapomidorka).

Exception: `pc-headunit/proto/*.proto` is based on protocol definitions from [headunit-revived](https://github.com/andreknieriem/headunit-revived) (AGPL-3.0). Everything else is GPL-3.0.
