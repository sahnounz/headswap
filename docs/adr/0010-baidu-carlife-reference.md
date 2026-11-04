# 0010. The standard of behavior is Baidu CarLife 8.6.7; the difference from it is considered a mistake

- Status: adopted
- Date: 2026-10-26
- Sources: PR #5, #18, #68, #83, #119, reverse `libencryption.so` and `com.baidu.carlife.*`, `CarLifeSession.kt:392`, `:603`, `:628`, `Handshake.kt:128`

## Context

head unit has been tested by manufacturers only against this Baidu CarLife. Any difference in our behavior (order
messages, size write, pause) head unit can be interpreted as a failure and terminate the session. Protocol specification is not enough.

## Decision

If head unit behaves strangely, first look at what makes Baidu 8.6.7 (reverse), and repeat it. So done:

- Authorization 2.0: `md5hex("CARLIFE" + brand + model + sdk + дата)` (PR #5); Mobis CCNC the " sends1the " Untitled "2.0;», Baidu
  And we are silent (`CarLifeSession.kt:392`).
- heartbeat on PAUSE (0007);
- rejoin Wi-Fi Direct When a group breaks up by sample `WifiDirectDiscoverManager` (PR #68, 0014);
- recording AOA One write and MIC/WAKEUP How `VrManager` (Mobis, PR #119, 0009);
- `CAR_DATA_START` only in response to RSP and only for announced modules. Blind START (even one OIL) hangs video
  Vezel; confirmed in two branches of 25–26.10, fuel with instrument cluster closed. In `main` message is not sent
  (There are only `CAR_DATA_SUBSCRIBE`, `Handshake.kt:128`).

## Alternatives

- Blind `CAR_DATA_START` - Hanging videos, rejected.
- The protocol's own interpretation, where Baidu behaves differently, is rejected as a rule of thumb.

## Consequences

- Before a new bypass, look for an analogue in the decompilation of Baidu; without it, editing is risky.
- The reverse does not contain everything: where Baidu is silent, guesses need to be checked on the machine.
- The 8.6.7 version is the same as the `versionName` legacy-APK (0013).
