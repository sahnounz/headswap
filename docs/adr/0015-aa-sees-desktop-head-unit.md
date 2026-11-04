# 0015. AA sees head unit as "Google / Desktop Head Unit"; the make of the machine is not part of the identity of head unit

- Status: adopted
- Date: 2026-10-16
- Sources: PR #39, #70, `aa/AapMessages.kt:331-335`, `:388-394`, test `AapProtocolTest.serviceDiscoveryResponseHeadUnitIdentityIgnoresCarMake` (`AapProtocolTest.kt:219`), `ui/HuApps.kt:13`

## Context

On the pc-headunit 16.10 with the selected brand Fermata and mirror disappeared from the grid AA. Without brand (`make = Google`) they are in place.
With someone else's brand AA ceases to consider head unit developer and does not show third-party video applications.

## Decision

In `ServiceDiscoveryResponse` field `head_unit_make`/`head_unit_model` always "Google" / "Desktop Head Unit"
(`AapMessages.kt:390`, `:393-394`); they are not changed by the brand or presets "My car" (PR #70). Selected by user
the mark only falls in the `make` field (default `Google`, `:335`), in `vehicleId` and in the exit tile signature in launcher AA.
The choice of the brand is left and signed in the settings ("Without a brand (video and YouTube via Fermata work)". The behavior is fixed by the test.

## Alternatives

- Present head unit real machine completely rejected: missing applications «dev-head unit» (Fermata, mirror), checked
  the pc-headunit.
- Remove the choice of the brand at all - not done: it was requested by the user. By default, there is no brand.

## Consequences

- The list of applications on dev-head unit (`ui/HuApps.kt`) depends on this identity: you can not change the fields of `head_unit_*` without
  AA. Live Inspection
- The `serviceDiscoveryResponseHeadUnitIdentityIgnoresCarMake` test should remain green.
- If the user chooses a brand, part of the video applications in AA will disappear - this is a known price, not a bug.
