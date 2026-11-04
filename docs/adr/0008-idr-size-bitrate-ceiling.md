# 0008. Channel-specific bitrate ceilings based on IDR size

- Status: accepted
- Date: 2026-10-17 (PR #56), updated through 2026-10-30 (PR #113, #119)
- Sources: PR #56, #66, #72, #89, #113, #119, cases h196/h242, `CarLifeSession.kt:912-930`, `:1452`, `aa/IdrBitrateCeiling.kt`, `aa/AaBridge.kt:682-690`, `:2658`

## Context

VW ID head units (h196 and others) disconnect USB after IDR frames around 600 KiB or larger.
Hyundai Tucson Mobis CCNC (h242) behaves similarly above 600 KB. IDR size grows as the map moves,
so a safe bitrate cannot be selected reliably in advance.

## Decision

Use feedback from IDR size after sending the frame, only on listed head units:

- VW ID: start at 2 Mbit/s. Above 450 KB (`VW_20082138_IDR_LIMIT`, `CarLifeSession.kt:1452`), multiply the ceiling
  by 0.75, no lower than 1.5 Mbit/s and no more often than once per second. Enable on channel 20082138,
  except VW BT 1420, which works at 8 Mbit/s; on 20082128 plus VW BT 9491 (`VW_ID_IDR_LIMIT_HUS`);
  and with the ID. UNYX preset.
- Mobis NX4C: above 300 KB, multiply by 0.7. After five consecutive IDR frames below 150 KB,
  multiply by 1.15 (`IdrBitrateCeiling.kt`).
- Mazda 20622111: use a fixed 1.5 Mbit/s ceiling (`MAZDA_20622111_CEILING_BPS`) to reduce IDR size.

## Alternatives

- VW Bluetooth-name allowlist, originally in PR #56/#66: did not scale; replaced by channel matching in #113 (0006).
- One fixed bitrate for all head units: rejected because behaviour differs. Keep fixed values where sufficient, as on Mazda 20622111.

## Consequences

- The 450/300 KB thresholds and multipliers come from specific head unit logs. New head units require IDR-size logs.
  Automatic adjustment or a developer setting is a future option (`docs/plans/adaptive-idr-ceiling.md`).
- Passthrough bitrate is not adjustable (0004), so these ceilings do not apply. Its protection is to skip locally generated
  IDR frames larger than 560 KB (0005).
- Add a new affected VW by channel plus name rather than name alone.
