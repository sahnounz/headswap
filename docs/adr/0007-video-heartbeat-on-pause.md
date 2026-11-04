# 0007. VIDEO_HEARTBEAT on PAUSE: empty message times in 100 ms

- Status: adopted
- Date: 2026-10-14 (approximately, PR #2, #18), improvements to 2026-10-30 (PR #123)
- Sources: PR #2, #18, #37, #67, #68, #69, #72, #96, #123, cases h93, h222, h224, h425, h625, h211, `CarLifeSession.kt:1259-1303`, `:1463`

## Context

head unit closes USB via 3,6–6 with after `VIDEO_ENCODER_PAUSE`: VW 20082120/22/34 (h93, h222, h224), CNS2 Delphi (h425),
Skoda 21290002 (h625), VW BT 1420 using wireless connection, Desay G6S using wireless connection (h211). Baidu During the pause sends `VIDEO_HEARTBEAT`.

## Decision

On PAUSE immediately flies `VIDEO_HEARTBEAT` (0x20002, length 0) and then once in 100 ms (`PAUSE_HEARTBEAT_MS`,
`CarLifeSession.kt:1463`) - how `Recorder.sendEmptyPacketBackground` UW Baidu. After the pause `IDLE_HEARTBEAT_MS = 1 с` without
frame sends the same heartbeat (Delphi, Skoda). Desay G6S by USB - former empty `VIDEO_DATA` times in 2 s, without heartbeat.
VW BT 1420 by wireless connection additionally gets START with IDR encoder without waiting for IDR from AA. Included for each head unit
separately (0006), see `pauseHeartbeatHu` (`CarLifeSession.kt:1270`).

## Alternatives

- Empty `VIDEO_DATA` instead of heartbeat - BT 1420 head unit broke session through 0,2–0,4 s after START.
- Global keepalive - broke Honda (0006).
- heartbeat self-taught behavior head unit - delayed after 3.0.0 (fork of mhzsajan, see 0006).

## Consequences

- The new head unit with a break through 3–6 after PAUSE is added to the `pauseHeartbeatHu` via channel rather than generic inclusion.
- The 100 ms interval is taken from Baidu (0010).
