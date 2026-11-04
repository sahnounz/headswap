# 0009. USB writes: separate header, body chunks up to 16 KiB, and head unit exceptions

- Status: accepted
- Date: 2026-10-20 (PR #89), 2026-10-30 (PR #119)
- Sources: PR #89, #119, cases h238/h242/h359, `Protocol.kt:249`, `:300-321`, `link/StreamTransport.kt:25`, `:150`, `CarLifeSession.kt:746-752`, `:1150`

## Context

Some head units stop reading USB after a packet larger than about 16 KiB. On Mazda 20622111,
VIDEO_DATA sizes of 16518 and 19104 bytes stall reads; 16163 bytes works. Mobis CCNC instead expects
the entire body in one write, matching Baidu's implementation.

## Decision

- Default: write the eight-byte outer header separately, then body chunks no larger than `Wire.CHUNK = 16384`
  (`Protocol.kt:249`, `writeSequence`, used by `StreamTransport.kt:150`). This matches Baidu's head unit writes
  and the expectations of `f_accessory` (comment at `Protocol.kt:300`; `docs/carlife-protocol.md` §1.4).
- Mazda 20622111: split VIDEO_DATA above 15000 bytes into consecutive packets
  (`MAZDA_20622111_MAX_VIDEO_PACKET`, `AaBridge.kt:2645`). Limit MEDIA_INFO album art to 12000 bytes.
  `send()` rejects and logs every oversized packet (`CarLifeSession.kt:1150`).
- Mobis CCNC: two writes, one for the header and one for the complete inner message
  (`Wire.writeWhole`, `setWholeWrites`, `CarLifeSession.kt:603`).

## Alternatives

- Splitting USB writes alone is insufficient for Mazda; the VIDEO_DATA message itself must be split.
  The threshold lies between 16163 and 16518 bytes, suggesting 16 KiB including headers.
  The 15000-byte value is a round number with margin; PR #89 and h359 logs give no further rationale.

## Consequences

- Mazda splitting assumes the head unit reassembles chunks before decoding; that assumption is recorded in code.
- Every new packet sent to Mazda 20622111 must fit the limit or `send()` drops it with a log entry.
- `Protocol.kt` explains 16384 only by reference to the specification; PR #89 does not otherwise justify the value.
