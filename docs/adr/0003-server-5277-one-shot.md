# 0003. Server 5277 is single-use: check it with a complete handshake and close cleanly

- Status: accepted (home-screen probing under validation in beta 3.0)
- Date: 2026-10-24
- Sources: PR #108, #118, `aa/AaServerCheck.kt:38-45`, `aa/AaProbe.kt:11`, `aa/AapSession.kt:53-55`

## Context

AA's head unit server stops after a session closes and needs a manual restart. A bare `connect`/`close` on 5277
also breaks it: TCP connects, but no `VERSION_RESPONSE` arrives (24 October). A short handshake → SDR → ByeBye probe
was harmful: the next connection received `ECONNREFUSED` for 27 seconds. With the accessibility service removed (0002),
there is no automatic restart.

## Decision

- Always close cleanly: ByeBye → `shutdownOutput` → read through EOF → `close` (`AapSession.kt:53`).
- Remove automatic probing at startup; retain the developer-screen probe (PR #108), using `AaProbe` with ByeBye.
- Replace the home screen's assumed “Ready to drive” flag with a real probe when the screen opens (PR #118).
  Establish an AA session through channel setup, hold it for `HOLD_MS = 1000`, then close with ByeBye
  (`AaServerCheck.kt:141`). Closing exactly at SDR hangs the server (`AaServerCheck.kt:42`).
  Dima proposed not waiting for video: retain NATIVE focus and close one second after AuthComplete. Three of three trials passed.
- The bridge waits for a running probe to finish before connecting (`AWAIT_MS`).

## Alternatives

- Bare TCP connect as a health check: rejected after reproducing server failure on 24 October.
- SDR + ByeBye: reproduced a hang with no `VERSION_RESPONSE` for more than 85 seconds.

## Consequences

- Every new probe or diagnostic connection must complete the handshake and close cleanly.
- The SDR + ByeBye probe noted on 30 October is obsolete. A full short session with a one-second hold replaced it;
  six consecutive runs passed, and it is included in beta 3.0. Final confirmation depends on beta testing.
  If opening the home screen is followed by missing AA VERSION_RESPONSE messages, investigate this probe first.
