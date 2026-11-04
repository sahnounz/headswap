# 0005. The phone generates and sends its own IDR frames in passthrough

- Status: accepted
- Date: 2026-10-27 (PR #115, included in #116 on 28 October); updated 2026-10-30 (PR #122)
- Sources: PR #115, #116, #122, #82, cases h295/h529, `docs/research/idr-latency.md`, commit 3690d5c

## Context

Honda Vezel and Civic 11 head units (h295) silently reset their decoder after the reverse camera or a menu,
without sending PAUSE/START. In passthrough, AA supplies an IDR about once per minute, leaving the screen black
until the next one arrives.

## Decision

Decode AA's stream on the phone (`aa/RefDecoder.kt`). Every two seconds (`IDR_SPLICE_MS`, `BridgeService.kt:1060`),
generate an IDR from the latest image and send it to the head unit. H.264 decoders reconstruct identical images
bit for bit, so the phone's decoded image matches the head unit's reference image.

- **Lossless when it fits:** use I_PCM / I16x16 without residuals (`IdrSplice`), preserving the image bit for bit.
  If it exceeds 300 KB, use compressed I16x16 (`IdrIntra`, QP starting at 22 within 18–40, four slices in parallel).
  Do not send frames above `SPLICE_MAX_BYTES = 560_000`; the head unit buffer is 600 KiB.
- **Drop P + 1:** the generated IDR replaces AA frames rather than adding to the stream. Do not send the AA frame
  representing the same image, and also drop one preceding P-frame (`spliceDrop = 1`). Thus one IDR replaces
  two AA frames. Head units display frames at a fixed rate, so every extra frame permanently adds latency;
  without dropping frames, the delay grew to five or six seconds within a minute (`docs/research/idr-latency.md`).
  H.264 has no general “do not display this frame” operation, so dropping creates space for the IDR.
  The next AA P-frame references the IDR's image, preserving the stream.
- **Place the IDR immediately after its reference image:** otherwise subsequent AA P-frames are applied to
  the wrong image and corrupt moving content. While AA sends frames, hold them until the IDR is ready,
  at most 250 ms (`SPLICE_HOLD_MAX_MS`). If generation takes longer, release the AA frames and discard the IDR.
  On a static image, with no AA frames for at least 200 ms, generate in the background without holding frames;
  any arriving AA frame makes that generated IDR stale.
- Alternate whether SPS/PPS is included: Honda expects them after a decoder reset (h295), while the Java CarLife
  head unit accepts only a bare IDR.
- If the reference decoder drops frames, pause for five seconds and retry rather than disabling generation
  for the rest of the session (PR #122).
- The developer setting `AaPrefs.ownIdr`, enabled by default, can disable generation. Dima requested it because
  his VW retains its image after the camera, so generated IDRs only add traffic and load there.

## Alternatives

- Cycling VideoFocus NATIVE → PROJECTED on a tap (PR #82): removed by Yegor's decision; generated or AA IDRs
  restore the image. On Civic 2021, focus cycling disconnected USB within five seconds.
- Timer-based focus changes and “soft PROJECTED” (commit 3690d5c): did not help.
- Sending IDRs less often: rejected by Yegor. The period bounds the black screen after a silent decoder reset.
  The image now returns almost immediately, or within two seconds in the worst case. A longer wait looks broken
  to the user. Roughly 0.6 Mbit/s of extra traffic was considered acceptable.
- Experimental VUI changes, timestamps, slices, and a lightweight IDR: removed in #116.
- Adding IDRs without dropping AA frames (`spliceDrop = 0`, retained only for tests): accumulates latency.

## Consequences

- Passthrough retains a second decoder and adds CPU/GPU work, heat, and up to 250 ms of frame holding every two seconds.
- Generated IDRs added about 0.6 Mbit/s in a session reported by Yegor. This matters on head units with USB limits
  (VW ID, Mazda 20622111, Mobis; 0008/0009). If passthrough disconnects there, first inspect generated IDR sizes in the logs.
- Keep generated IDRs below 560 KB for the 600 KiB head unit buffer; oversized frames are skipped and the head unit
  waits for the next one.
