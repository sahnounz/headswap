# 0001. The bridge is a client of Android Auto's head unit server on 127.0.0.1:5277

- Status: accepted
- Date: 2026-10-06
- Sources: `docs/aa-headunit.md` §1.1–1.4, README.md, commit 4fbea1e “Headswap 1.0”, `aa/AapSession.kt`

## Context

We need an Android Auto stream on the phone without a dongle or a second device. Alternative ways of starting AA
internally, such as an intent to `WirelessStartupActivity` or a TCP server on port 5288, fail on AA 17.4+ and Android 16.

## Decision

Headswap acts as an AA head unit: it connects as a TCP client to Android Auto's own head unit server on
`127.0.0.1:5277`, then completes version negotiation, TLS, service discovery, channel setup, and H.264 video
(`aa/AapSession.kt:53`; default port at `:30`). To the car, the same phone remains a CarLife device over AOA or wireless.
The user manually enables AA's head unit server in developer settings (0002).

## Alternatives

- LEGACY (AA <17.4): our own TCP server on 5288 and an explicit Gearhead intent. This fails on AA 17.4+ and
  Android 16, as documented in `docs/aa-headunit.md` §1.3–1.4, and is not used.

## Consequences

- The bridge depends on AA continuing to expose its head unit server on loopback. A change can break the entire bridge.
- Headswap cannot work without that server, which explains the server check screen and onboarding guidance (0003).
- The server is single-use and fragile; follow 0003.
- AA sees us as “Google / Desktop Head Unit” (0015).
