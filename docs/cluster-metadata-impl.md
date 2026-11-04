# Metadata AA → CarLife: implementation and verification in machine

2026-10-11. Exploration: [cluster-metadata-recon.md](cluster-metadata-recon.md). Not included.

## What's done
- Dismantling ch9/ch10 - `aa/AaMeta.kt`. ch9: MediaPlaybackStatus 0x8001, Metadata 0x8003. ch10: START/STOP cluster, NavigationStatus 0x8003, legacy 0x8004/0x8005, new 0x8006 (first step: maneuver, road, with an empty road - text cue) and 0x8007 (step_distance, time to step, distance to target). Called from `AapSession.dispatch` via `AaMetaSink`which implements `AaBridgeCore`.
- Decisions about what to send is made by `MetaRelay` (the same file, pure JVM-logic). Works under the monitor `AaBridgeCore`.
  - Music. MEDIA_INFO goes away when changing the track or cover, repeat the same data does not go. All 9 Fields required Always written. `source` = `<media_source>#<номер трека>`With the new cover of the same track, it does not change. MEDIA_PROGRESS_BAR: Not more than once a second, then back up. **Units: progressBar in ms, duration in seconds**How to `MusicInfoSender` For the regular channel: y Vezel no `isSecondVehi`. If progress on head unit will be in 1000 more times, put `MetaRelay.PROGRESS_UNIT = 1`.
  - Navigation. Send messages only after SUBSCRIBE_START moduleID=0. ACTIVE or the first manoeuvre sends MODULE_STATUS NAVI=1. NAVI_NEXT_TURN_INFO: action=1 for initial display; action=2 for updates at most once per second; manoeuvre changes are not throttled. Do not send without distance data. Fix totalDistance at the first distance for that manoeuvre. INACTIVE, UNAVAILABLE, or cluster STOP sends action=3, nextTurn=24, roadName “目的地” (destination), then NAVI=0. Send action=3 only after a prior display; INACTIVE at connection time must not create a false arrival.
  - One AA-session takes one source of events. As soon as 0x8006/0x8007 came, legacy 0x8004/0x8005 is ignored, one line goes to the log. The maneuver key is nextTurn + road.name + exit number. The text of cue is shown only on the screen, the key is not included.
  - The maneuver or distance without a route starts it implicitly: the phone may not send status. The exception is the tail in 5 after the end of the route: such events are ignored, NAVI does not rise again.
  - The MODULE_STATUS NAVI is common on the route and the TTS. NAVI=0 only leaves when it is not kept by either the route or the TTS.
  - MODULE_STATUS MUSIC. While the sound goes on USBThe logic is the same: MEDIA INIT/PAUSE and `isMediaInited`. If the sound is not USB (BT or removed), MUSIC 1/0 Set as player ch9. Response to MODULE_CONTROL - `bridge.musicActive`.
  - On the cold CarLife START (and after reconnecting USB) known track, progress and maneuvers are sent again.
- Sending to CarLife - `CarLifeSession` (`sendMediaInfo` / `sendMediaProgress` / `sendNaviTurn`), through a separate thread `cl-meta`, order is maintained. Cover PNG Transcode `MetaImages.albumJpeg`: JPEG ≤256×256 q90…10, then 160×160The Limit 30720 B; result is cached by bytes. Arrows draw `MetaImages.turnIcon(nextTurn)`: PNG 128×128white with dark outline, cache on nextTurn. The billers - `Msgs.mediaInfo/mediaProgress/naviNextTurn` (`Protocol.kt`).
- The 6 NAVI_NEXT_TURN_INFO field switches to "For → Developers Metadata on head unit":
  - "6 Field: Icon" - bytes turnIconData, as in 8.6.7;
  - "6 Field: time" - int32 time in seconds before the maneuver, as in apollo-DuerOS and CarProjection;
  - by default both are included (BOTH) under the number 6 They leave and varint timeand bytes icons. Parser waiting for another wire-typeTaking too much in. unknown fields. Library head unit apollo Read through `MergePartialFromCodedStream`.
  - There are also switches "Music", "Navigation" and "Progress in seconds": MEDIA_PROGRESS_BAR in seconds instead of ms. All settings are read when you connect USB. Current values are written in the header of the sent log.
- Signatures in the log have been corrected. `AapMessages.messageName/describe` choose names and schemes by channel: ch9 MEDIA_PLAYBACK_*, ch10 INSTRUMENT_CLUSTER_* / NAVIGATION_*, nested schemes NavigationState/CurrentPosition. Media channels, as before, MEDIA_*. Binary fields are displayed as `<N Б PNG>`not a dump on 400 KB.
- What you can see in the log:
  - `AA RX MEDIA_PLAYBACK(9)/NAVIGATION(10) <имя> | {поля}` - all ch9/ch10. Player Status, NavigationStateposition and distance (about once a second) go only to the file, the rest and UI.
  - `AA META …` is the total.
  - `META …` - relay solutions: delayed, subscription, route started/completed, maneuver that is gone.
  - `TX CMD 0x00010035 MEDIA_INFO {...albumArt=<N Б JPEG>...}`, `MEDIA_PROGRESS_BAR`, `NAVI_NEXT_TURN_INFO {... time|turnIconData=…}` - progress and action=2 Only in the file.
  - `BRIDGE CarLife MODULE_STATUS NAVI/MUSIC=…`.

## Tests
`AaMetaTest`, 19 tests (including mixed stream legacy + new events, text change cue without changing maneuver, tail after INACTIVE):
- parsing ch9 and ch10 (both event variants, fallback on cue, bit payload);
- Mapping maneuvers (new and legacy);
- names and schemes of the log on the channel;
- Relay: postponed until START, ms and throttling, rewinding, source, music subscription, navigation subscription, stream action 1/2/3 C total/remain/time, false INACTIVE, legacy, general NAVI UW TTS and route, reshipment after new CarLife-sessions, MUSIC As of no USB-sound;
- Builders MEDIA_INFO (all required-fields) and NAVI_NEXT_TURN_INFO (wire-type Fields 6 in the four modes).

Assembly: `gradlew testDebugUnitTest assembleDebug` green, 251 JVM-test, 0 Fallen. `MetaImages` (Bitmap/Canvas) JVM-tests not covered: type of arrows and size JPEG check only on the phone. If there is an exception, it is caught and written in log line `метаданные: MEDIA_INFO|NAVI_NEXT_TURN_INFO` with the train, the message does not go away, the session lives.

## Checklist in the car (Vezel)
Before the trip: "For developers" → "Metadata on head unit", all inclusive (by default). After the trip save log (Share log).

Music:
1. [ ] Include track in YouTube Music or Spotify via AA. On head unit the Widget or Screen CarLife-music you can see the name, performer, album. In the log is `TX CMD … MEDIA_INFO`.
2. [ ] Cover on head unit. In the log `метаданные: обложка <N Б PNG> -> <M Б JPEG>`, M < 30720.
3. [ ] Progress moves and matches with the phone. If it is in 1000 times more (or stands at the end), turn on "Progress in seconds", reconnect USB and check again.
4. [ ] Next track: title and cover change, progress from scratch. Rewind: progress jumps at once.
5. [ ] instrument cluster: shows whether the track (and with progress of li).
6. [ ] Pause and play: head unit does not lose sound as before (MUSIC is still on MEDIA INIT/PAUSE).

Navigation (Google Maps, route on 2–3 turn):
7. [ ] There is a log `SUBSCRIBE_START moduleID=[0]` and `META подписка навигации: START`.
8. [ ] What events send phone: in log ch10 `NAVIGATION_STATE`/`NAVIGATION_CURRENT_POSITION` (new) or `NAVIGATION_TURN_EVENT`/`NAVIGATION_DISTANCE_EVENT` (legacy). Write down which. **Fork:**
   - with active route on ch10 only `NAVIGATION_STATUS`/`INSTRUMENT_CLUSTER_*` and no 0x8004–0x8007 → phone data does not send, switching the field 6 will not help. Write down and steps 9–18 do not spend;
   - data on ch10 is, and `TX … NAVI_NEXT_TURN_INFO` no → watch lines `META` (subscription, "tail", "legacy ignore", "without distance").
9. [ ] Route start: `MODULE_STATUS NAVI=1 (маршрут)`, then `NAVI_NEXT_TURN_INFO action=1`.
10. [ ] There is a turn arrow on the head unit or instrument cluster, the name of the road, the distance decreases (meters).
11. [ ] The arrow corresponds to the maneuver: right, left, ring, turn. If the arrow is wrong, write nextTurn from the log and what was on the road.
12. [ ] Turn change: new arrow and road at once (without delay per second).
13. [ ] End of route or reset: `action=3`, then `NAVI=0`, clues with instrument cluster gone.
14. [ ] Voice prompts (TTS) during the route play as before, NAVI does not blink (in the log there is no NAVI=0 in the middle of the route).

6 field. Only if there is `TX … NAVI_NEXT_TURN_INFO` in the log, and at step 10 nothing appeared or head unit swears:
15. [ ] Leave only "icon" (time off) → reconnect USB → route. Write down: whether there is an arrow and distance.
16. [ ] Leave only "time" → same. Write down.
17. [ ] Both off → same. Write.
18. [ ] Result: what is the field option 6 The ( BOTH / ICON / TIME / NONE) and paints head unit your arrow on nextTurn or our picture.

If something breaks a session (head unit rips USB after MEDIA_INFO or NEXT_TURN), turn off "Music" or "Navigation" and send the log.

## Open-ended issues
- Trimming `song` to 32 bytes (Baidu makes it with 48 kHz support) is not repeated: we send the whole. If head unit cuts or fails on long Cyrillic names, add trimming along the UTF-8. border
- `mode`, `playlistNum`, `songId` are fixed: 0, 0, "-1" (as in Baidu without data).
- AA manoeuvring icons (IMAGE mode) not requested: SDR not touched, NAVIGATION remains ENUM.
