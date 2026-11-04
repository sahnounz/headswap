# Accumulation of image delay on head unit with his IDR (PR #115)

Branch `claude/idr-latency-research` on `claude/idr-splice`. Symptom: Honda Vezel, 1920x1080 passthrough, its IDR times in 2 s.
Response to touch for 37 minutes of travel grows to 5–6 s, towards the end on a static screen near 2 s. After rearward transmission
(head unit resets decoder) again instantly. touch, AA and USB response checked by log and normal. So the delay accumulates
inside head unit, between reception and display.

## Main

**Your IDR does not replace the frame AA in the stream to the head unit, but is added to it.** `aaFrame()` first sends the frame AA
(`passthroughFrame`) and then calls `spliceRequest(fromFrame = true)`. The picture for IDR is `sp.seq`, just now
frame. head unit gets P(n) and immediately after it IDR(n) with the same picture. Both frame head unit decodes and shows.
The `IdrSpliceBridgeTest.motionHoldsNextFrameUntilOwnIdrAndShiftsIt` test fixes this: 7 frame AA is converted to 8 frame to head unit.
In the background, on static, IDR from a picture that went away a long time ago, is also an extra frame.

In H.264 you can not mark frame as "do not show". HEVC has `pic_output_flag`, H.264 does not. Therefore, each of our IDR is an extra derived frame.
13 IDR for 30 c give 0,43 extra frame per second. Flow to head unit is 30,4 frame/c, although AA sends 30.

If head unit shows frame with constant frequency 30 fps and does not pass them, the extra frame remains in the queue forever.
This is what happens when frame goes by the display clock or PTS is counted by the frame: counter mark VIDEO_DATA in our seconds,
and head unit is nothing more to lean on. Growth ≈ 14 ms delay per second stream, where AA sends full 30 fps. 5 s
run for about 6 minutes of card movement. The queue leaves only where AA sends less than 30 fps, that is, on static.
This coincides with "to the end on static ~ 2 c". reset decoder after the back gear clears the queue, and this coincides with
"after the back again instantly." USB and our turn at the same time clean: so you can see in the log.

## Hypotheses of decreasing probability

### 1. Extra frame for each of its IDR+ display on head unit with constant frequency (high)
- Code: see above. `spliceRequest` after `passthroughFrame`, `IdrSplicer.commit` - "our IDR went to head unit instead of the latter
  pictures", but by this time the picture itself has already gone P-frame.
- Quantitatively converges with the symptom: the order of seconds in minutes, growth on a moving screen, decline on static,
  reset rear gear.
- Check: **"key frame tumbler instead of frame AA"** (`SpliceTuning.replace`). While AA sling frame, P(n) adheres
  along with the following (reserve as now, 100–150 ms), and instead of it goes IDR(n). frame to head unit the same,
  how much from AA. On static (frame after a pause ≥ 200 ms) all the same as before, IDR is going in the background: on static
  extra frame is harmless, the queue leaves there. Before IDR (> 250 ms, > 560 KB, head unit did not accept) - P(n) leaves first,
  Then held.
- Every thirty seconds, log AA-frame and generated-IDR counts, including replaced AA frames and extra IDRs,
  extra IDRs per session, and estimated current/maximum delay for a 30-fps head unit without dropped frames. The model ([GuQueueModel])
  considers what would be the delay in head unit with a constant display frequency. It coincides with the measurement on the screen - head unit is arranged like this.

### 2. decoder head unit at the limit of 1080p, heavy I-frame does not have time to disperse (medium)
- Our IDR 140–180 KB CAVLC I16x16 at QP 18–20 is a 5–20 regular P-frame AA bit by bit. CAVLC-
  decoder hardware depends on the number of bits, so such frame can take several frame intervals.
- By itself, a single braking delay does not sting. Copy only if the stock of decoder on 30 fps is less than
  "IDR cost" for 2 c. Then the queue grows on each IDR, and on static (small P-frame, stock more) goes.
  This picture also coincides with the symptom, so the hypothesis is the second.
- Check: **"Lightweight key frame"** (120 KB target, QP ≥ 24): assembly faster, decoding easier. If
  "instead of frame AA" did not help, and the "easy" helped - it's a matter of capacity decoder.
- Signal from head unit: Java-head unit CarLife at the backlog of decoder itself reduces fps and relay `VIDEO_ENCODER_FRAME_RATE_CHANGE`
  (`DecodeUtil.java:336-346,636-666`, docs/carlife-protocol.md §5.3). In passthrough we ignore it. Search in the log
  `FRAME_RATE_CHANGE fps=` - if Vezel sends it, this is direct evidence of lag.

### 3. The head jitter buffer unit grows from packs after accumulator (medium-low)
- While IDR is going, frame AA adhere to 100–150 ms (up to 250), then leave with a pack of 3–5 pieces with IDR in front.
  Adaptive buffer, which is only growing, would grow to the length of the largest pause, that is, ~ 0,25 c, not 5 c. By itself
  If the buffer grows with each pause without a recession, the growth is unlimited.
- The check is indirect: with "instead of frame AA" the delay remains. If it still accumulates, but "easy"
  (shorter maintenance) helps partially - look here. In the log: `frame AA ждали IDR ср … мс, макс … мс, в пачке до …`.
- You can't do without an attachment: P(n+1) AA refers to a picture of n. IDR not from the last picture - and next
  P-frame AA is decoded from another support, the picture floats. You can only shorten the build.

### 4. SPS/PPS through time (low)
- SPS/PPS has the same byte per byte as AA (after the same crop). Normal decoder on matching SPS does not
  reinitializes. If head unit on each SPS Makes flush/reconfigure, it is rather reset It would be a delay than saving up.
  It would be harmful if head unit at SPS created a new decoder, and the old one left to live. This is unlikely.
- Check: **"SPS/PPS in its key frame: Always / Never"**. "Never" still glues SPS/PPS when head unit
  without IDR (recovery after loss of frame). Attention: Honda after reset is waiting for SPS in frame (h295). With "Never"
  Recovery after the camera may not work - check back.

### 5. DPB "bumping" / POC after gluing (low)
- SPS AA 720p from pc-headunit: Baseline, `pic_order_cnt_type = 2`, VUI with `bitstream_restriction`,
  `max_num_reorder_frames = 0`, `max_dec_frame_buffering = 1`. With POC type 2 the order of output is equal to the order
  decoding, and decoder can output directly. Our IDR comes with the same SPS/PPS: POC type it and AA the same.
  `frame_num` after gluing is rewritten correctly (verified by ffmpeg, IdrSpliceTest).
- Even without `bitstream_restriction`, the delay due to DPB is limited by its size: no more than 16 frame, 0,53 with
  (at 1080p at 4.x - 4 frame). On each IDR (`no_output_of_prior_pics_flag = 0`), the DPB is displayed as a whole.
  5 is not like that.
- New in log for each CODEC_CONFIG: `BRIDGE SPS AA: profile … level …, POC type …, max_num_ref_frames …, VUI: …`
  (with hex). If 1080p on Vezel does not have `bitstream_restriction` or `reorder > 0`, enable **“SPS without
  rearrangement"** (`SpsVui.lowDelay`: adds or rules VUI, `max_num_reorder_frames = 0`,
  `max_dec_frame_buffering = max(1, max_num_ref_frames)`).

### 6. Multiple slices in IDR (low)
- Slides are a common means of H.264, hardware decoder understand them. The number of slides does not affect the output of frame, only on
  The cost is a bit higher (a little higher).
- Check: "Slices: 1/2/4/8". Assembly with 1 slide goes in one thread, approximately ×4 on time, and almost certainly
  will hide in the ceiling of the 250 ms. It is reasonable to try 2.

### 7. Time tag VIDEO_DATA in seconds (low)
- Java-head unit reads it and ignores it. CarProjection sends `(int) System.currentTimeMillis()`. If Honda on it something
  makes (for example, synchronizes the display), 30 frame with one label per second - a strange input.
- Check: **“VIDEO_DATA label in milliseconds”**. Works without its IDR.

### What else did you notice?
- On static, IDR is repeated once in 2 with the same picture: this is also an extra frame, but on static, the queue goes.
- The first IDR session and h529- are one-time sessions, they do not affect the accumulation.
- `AnnexB.fixSps` (level > 4.1 → level by size) changes MaxDpbFrames. This is only the upper limit, p. 5.

## How to measure delay on head unit without its logs

1. **Tag in frame + phone clock** (tumbler "Tag time in frame"). In the upper right corner of its IDR is drawn
   time of the phone "ss.ss" (seconds modulo 1000, honeycomb) on a black plate aligned on macroblocks. P-frame AA
   refer to this picture, so the label holds until AA redraws the angle, and is replaced by the following IDR.
   In the section for developers under the tumblers - large clock phone in the same format. Shoot on video with one frame
   screen head unit and phone. When on head unit the mark changed to X, and the phone shows Y, delay ≈ Y - X - (assembly, in log:
   `BRIDGE метка X в своём IDR #N ушла в head unit через … мс после снимка`). Accuracy - frame video. Shoot at the beginning,
   through 10, 20, 30 min and immediately after rear gear.
2. **Tap on video.** Slow shooting (240 fps), finger and screen in frame. Touch-to-reaction time minus 0,15–0,5 s
   answer AA (it's in the log). No assembly.
3. **Model in log** (`модель head unit 30 fps …`Compared to p. 1. If they grow together - hypothesis 1.
4. **head unit:** `FRAME_RATE_CHANGE` signals (2); delay increase after rearward transmission from scratch: according to the 1 hypothesis approximately
   1 with per 70 with card movement.

## Travel plan

Tablets are read when connected to head unit. Changel - reconnect USB, the log will appear
`BRIDGE задержка head unit, варианты: …`. Galka «Own» key frame Once in 2 c" is included in all trips.

| Trip | What to include | What does the result of | mean
|---|---|---|
| 1The first ~ 15 min | only "Time stamp in frame» | Control: measure the growth of delay by label (p. 1), check with the model in the log |
| 1, balance (reconnect USB) | «key frame instead of frame AA» + label | Not enough - hypothesis 1, ready. Check back: recovery for ~ 2 C |
| 2If it accumulates | «instead frame AA+ "Easy key frame» + label | It helped - decoder head unit at the limit (p. 2): leave easy, pick a goal |
| 3If it still accumulates | in turn: "SPS/PPS Always ” / “SPS without reordering" (if in log) SPS 1080p without restriction) / "Tag VIDEO_DATA in ms | We are looking for one toggle per connection |

What to send after the trip: log in whole (lines) `BRIDGE поток в head unit`, `BRIDGE SPS`, `BRIDGE свой IDR за 30 с`,
`BRIDGE метка`, `FRAME_RATE_CHANGE`) and video with tag and clock.

## If "instead of frame AA" does not help: other ways

- **IDR only when suspected on reset.** Signals: touch after pause ≥ 4 s (was in h295, deleted), `MODULE_CONTROL MUSIC=0`
  on Civic at rear, `PAUSE/START` change. The recovery then is "the first touch after the camera" rather than 2 with always.
  That's worse than it is now, so plan B.
- **Rather, but not less often than necessary:** period by screen movement (on static once in 2 with - cheap, in motion less often).
  Egor is against, left for the extreme.
- **GDR / recovery point SEI.** For this P-frame AA would have to be recoded with intra-The stripe is already
  Transcoder. Its P-frame with intra-band and P_Skip - again extra frame (small, but p. 1). Besides head unit after reset,
  IDR (h295: Honda silently waiting for key frame). Do not try until you exclude the item. 1–2.
- **To compensate for the extra frame by ejection of another** simply can not: all P-frame AA reference, ejection breaks the following.
  But its IDR is a gluing point: P(n+1) refers only to the picture of n, and IDR(n) can stand instead of several frame AA.
  This **"frame AA ejection before its key frame"** (`SpliceTuning.drop`, 0/1/2, idrtest9): in motion drop frame
  before the picture IDR do not go away, IDR stands instead of drop + 1 frame. Emission 1 times in 2 s - stream 29,5 frame/s, queue
  head unit with constant frequency melts ~ 15 ms/s and at the same time eats the difference of hours head unit and phone number (29,97 Against 30 - ~1 C
  for 15 min). Price - a scrolling leap on frame times in 2 c. Failed (statics, AA fell silent > 200 ms, IDR did not have time) -
  thrown away leave as they were. In the log `выброшено frame AA … выброс отменён …` in line `BRIDGE поток в head unit`.

## What's in the code

- `aa/LatencyLab.kt`: `SpliceTuning`/`SpliceSps` (variants), `GuQueueModel` (model of the head unit queue), `SpsVui` (assembly)
  VUI and `max_num_reorder_frames = 0`), `FrameStamp` (label 5x7).
- `aa/AaBridge.kt`: `spliceReplaceNow`, `spliceReplaced`, SPS modes in `spliceSend`, `LatencyStats`, `logSps`,
  tag in `spliceRun`, VUI in `spsCrop`.
- `aa/IdrSplice.kt`: `IdrBuilder` adopts `minQp`/`startQp`.
- `CarLifeSession.kt`: label VIDEO_DATA in ms by `tuning.tsMs`.
- `aa/AaSettings.kt`, `ui/DevScreen.kt`, `BridgeService.kt`: tumblers, watches, log options at the start of the session.
- Tests: `LatencyLabTest`, `IdrSpliceTuningTest` (`android/app/src/test/.../aa/LatencyLabTest.kt`).
