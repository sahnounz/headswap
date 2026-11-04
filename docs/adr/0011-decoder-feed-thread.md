# 0011. frame in decoder encoder provides a separate thread (FeedQueue)

- Status: adopted
- Date: 2026-10-24
- Sources: PR #106, #65 (closed), # 63, #55, `aa/FeedQueue.kt:98`, `aa/AaTranscoder.kt:24-37`, `:70`, `:168`

## Context

In the transcoder, there was a hang chain: encoder does not drain the output, Surface gets up, GL-gets up, decoder does not
gives way, `dequeueInputBuffer(15 мс)` waiting under the bridge monitor, encoder still not draining. Added
`createDecoder` (50–740 ms) under the same monitor and lock inversion.

## Decision

`feedFrame`, `feedConfig`, `resetDecoder` only put work in `FeedQueue`; decoder works with thread `aa-trans-feed`
(`AaTranscoder.kt:168`). The `out.*` callbacks are called out of `lock`. When overflowing frame is lost, queue closed to IDR,
The bridge gets one. `onNeedAaKeyFrame`. Depth of queue 16 frame (`FeedQueue.DEFAULT_MAX_FRAMES`, ~0,5 at 30 fps):
decoder is created by 80–300 ms (on 60 fps it's up to 18 frame) and these frame should wait for it, not knock out IDR-request to AA.
Rollback - `tuning="nofeedthread"` (`AaTranscoder.kt:37`, `:70`), left for one release.

## Alternatives

- External PR #65 (closed) 29.10): feed frame out of the lock in one piece. Not taken, made in their own way (PR #106).
- PR #63 (AlohaMoscowThe author closed himself. 17.10 And at the same time opened instead of him65.
- Limit 32 frame Egor 23.10 in favor 16I. Distinction: description PR #106 " The World4 frame> ; on 4 frame
  a pack of 10 frame overflowed the queue during `createDecoder` (`transcode_test` burst 23.10), so in the code 16.

## Consequences

- When editing the transcoder, do not call decoder from the caller stream and do not hold `lock` when calling `out.*`.
- The `nofeedthread` flag is left as a rollback to one release, then it can be removed.
- Loss of frame when overflowing = extra IDR from AA; on 60 fps queue in 16 frame shorter than 0,5 s.
