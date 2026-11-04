# 0002. No accessibility service: the user starts the head unit server manually

- Status: accepted
- Date: 2026-10-24 (2.6.0)
- Sources: PR #108, #109, #118, `docs/aa-headunit.md` §10 (automatic startup removed 2026-10-24)

## Context

The old autosrv accessibility service tapped AA's menu to stop/start the head unit server. Users had to unlock
the screen, allow restricted settings, and grant accessibility access. Because server 5277 is single-use (0003),
the service had to restart it after every session.

Distribution, rather than a technical limitation, drove the decision: users were reluctant to grant accessibility
access, which discouraged installation.

## Decision

Remove the service (PR #108). The manifest retains `BridgeService` (`AndroidManifest.xml:124`) but no accessibility service.
The user manually enables AA's head unit server. Changing video mode or DPI does not close the warm AA session.
Remove Stop from the home screen (PR #118); show existing users a one-time explanation after upgrading (PR #109).

## Alternatives

- An optional switch to restore the service: rejected on 26 October because `<service>` would remain in the manifest.
- A separate flavor APK containing the service: not implemented.
- A hotspot or LocalOnlyHotspot to recover a silent server 5277: on 24 October, Yegor chose to fix the USB side instead.

## Consequences

- If the server stops, the app can only report it; it cannot restart it (0003).
- Do not add features that require accessibility access again.
