# pc-headunit

A minimal Android Auto head unit emulator for PC debugging. It connects to the phone's AA head unit server
(TCP 5277), completes Version → TLS → Auth → Service Discovery, saves H.264 video to `out/video.h264`,
and logs the exchange to the console and `out/session.log`. It requires neither a second phone acting as a head unit
nor CarLife, so it can test the AA side independently of Headswap.

See the [protocol reference](../docs/aa-headunit.md).

## Dependencies

- Python 3.10+
- `pip install -r requirements.txt` (`protobuf` 5.x; generated `proto/*_pb2.py` files require runtime >=5.27.2).
- Tests require `pytest`. Stream validation with `ffprobe` is skipped if `ffmpeg`/`ffprobe` is absent from PATH.
- To regenerate `proto/*_pb2.py`, which is unnecessary for normal use: `pip install grpcio-tools==1.67.1`, then `python proto/gen.py`.

## Certificates

Like a real head unit, the emulator must present a client certificate signed by Google Automotive Link.
Certificates are not tracked (`certs/` is ignored). Place PEM files in either directory:

```text
pc-headunit/certs/jvc/cert.pem   # JVC Kenwood, from aasdk: src/Messenger/Cryptor.cpp, cert/headunit.crt and headunit.key
pc-headunit/certs/jvc/key.pem
pc-headunit/certs/hur/cert.pem   # From headunit-revived: app/src/main/res/raw/cert and privkey
pc-headunit/certs/hur/key.pem
```

Sources: [opencardev/aasdk](https://github.com/opencardev/aasdk), a continuation of
[f1xpl/aasdk](https://github.com/f1xpl/aasdk), and
[andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived).
Select a set with `--cert jvc|hur` (default `jvc`). Tests require both sets.

## Run the AA emulator

```sh
adb forward tcp:5277 tcp:5277
python hu.py --duration 30 --cert jvc
```

First, on the phone: Android Auto → Settings → tap Version ten times → three-dot menu → Start head unit server.
The server accepts one connection per launch and can hang after an abrupt disconnect. Stop the script with Ctrl+C
or `--duration` so it closes the session cleanly.

Useful flags: `--check` (local certificate/TLS check without connecting), `--touch-test` (tap the centre ten seconds
after the first frame), `--resolution`, `--fps`, `--moto`, `--full-audio`, and `--minimal`.
Run `python hu.py --help` for the full list.

## CarLife head unit emulator (`carlife_hu.py`)

Uses only the standard library. By default (`--transport stream`), it is a TCP server. Headswap connects from the phone
over one connection through `adb reverse tcp:7240 tcp:7240`. Messages include the outer channel header.
Logs and video go to `out/carlife_hu.log` and `out/carlife_video.h264`.

```sh
adb reverse tcp:7240 tcp:7240
python carlife_hu.py --duration 120
```

## Six-socket mode (wireless format)

Wireless CarLife uses one TCP socket per channel, with no outer header. The phone listens; the emulator connects
as a client. Ports: CMD 7240, VIDEO 8240, MEDIA 9240, TTS 9241, VR 9242, TOUCH 9340.
Forward the phone's ports with `adb forward`; the default host is therefore 127.0.0.1:

```sh
adb forward tcp:7240 tcp:7240
adb forward tcp:8240 tcp:8240
adb forward tcp:9240 tcp:9240
adb forward tcp:9241 tcp:9241
adb forward tcp:9242 tcp:9242
adb forward tcp:9340 tcp:9340
adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ez debug_tcp6 true
python carlife_hu.py --transport tcp6 --touch-test
```

Start the bridge first so it opens the ports, then start the emulator. Session behaviour is unchanged:
HU_PROTOCOL_VERSION, an initialization burst after MD_INFO, authentication, a heartbeat every second,
a tap fifteen seconds after the first frame, and a final summary. `--extra-data-socket` adds the Data channel
on port 9440; the phone accepts that connection and ignores it.

To test channel loss, close one socket and wait for the bridge to close the others:

```sh
python carlife_hu.py --transport tcp6 --kill-chan VIDEO --kill-after 10
```

`--kill-chan` accepts CMD, VIDEO, MEDIA, TTS, VR, or TOUCH. `--kill-after` is measured from the first video frame.
The result is either `kill: VIDEO at 12.3 s, bridge closed others after 0.4 s` or
`bridge did NOT close others within 10 s`. The latter means the bridge retained some channels after losing another.

## Pause cycles

Test a head unit pausing and resuming video. On START, the bridge resets the decoder and waits for a new IDR.
This works with both transports:

```sh
python carlife_hu.py --pause-cycles 20 --pause-every 10 --pause-for 3 --duration 300
```

- `--pause-cycles N`: default 0, disabled.
- `--pause-every S`: default 10 seconds, from the first video frame to the first PAUSE, then from each START to the next PAUSE.
- `--pause-for S`: default 3 seconds between PAUSE and START.
- `--duration` must cover `N * (every + for)` or the session ends before all cycles complete.

The emulator sends `VIDEO_ENCODER_PAUSE` (0x0001800A, empty body as with START), waits, sends `VIDEO_ENCODER_START`,
and measures time from START to the first video frame and first key frame (NAL type 5 in the Annex-B buffer).
It counts frames received during PAUSE separately; the bridge should not send any. The first such frame is logged
with its delay from PAUSE. A few milliseconds can mean a frame was already in flight; tens or hundreds suggest
the bridge did not stop.

```text
pause #1: paused_frames=0, start->first_frame=120 ms, start->idr=480 ms
pause cycles: 20, worst start->idr 910 ms, frames during pause total 0
```

`none` means no frame or IDR arrived between START and the next PAUSE or session end. The summary reports
`(no IDR in K cycles)`. A cycle interrupted during PAUSE is excluded from the count and logged as `session ended during pause`.

## Branch B: phone hotspot

The phone provides a hotspot; the PC joins as a head unit client. No manual phone IP is required. The emulator listens
for the UDP beacon (`CarlifeHost` every two seconds), uses the first datagram's sender as the host, and continues in six-socket mode.

1. Enable the phone hotspot and connect the PC to it.
2. Start branch B on the phone:

   ```sh
   adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ez debug_wifi_b true
   ```

3. On the PC:

   ```sh
   python carlife_hu.py --transport tcp6 --discover --probe-8999 --touch-test
   ```

On Windows, allow Python to receive UDP 7999 on private networks in the firewall. If the PC has multiple network
adapters, select the hotspot adapter with `--bind-ip <PC-IP-on-hotspot>`.

Flags: `--discover` (requires `--transport tcp6`), `--beacon-port` (default 7999), `--bind-ip` (default 0.0.0.0),
`--discover-timeout` (default 60 seconds; exits with `no beacon within 60 s` if none arrives), and `--probe-8999`
(sends one UDP datagram to the phone's port 8999 after discovery; waits up to two seconds for an optional reply).
The summary includes `discovered_host`, beacon count, and probe result.

## Tests

```sh
python -m pytest tests -q
```

Tests are self-contained. A scripted phone in the test communicates through `socket.socketpair()`.
No physical phone or port 5277 is needed.
