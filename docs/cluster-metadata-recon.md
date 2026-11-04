# Music and navigation metadata AA → CarLife (instrument cluster head unit)

Recon 2026-10-11. Reason: CL2AA (ISO_QUARTZ, Toyota Levin) displays track with progress and step-by-step hints ("Proceed to …, 110 m" With the Arrow " instrument cluster via CarLife.
Paths: RE = `re/carlife-8.6.7/sources/com/baidu/carlife/`, AND = `android/app/src/main/java/com/yegorkapomidorka/headswap/`.

## Conclusion
- Music: no blockers. Phone AA already sends song/artist/album/ cover/progress on ch9, the bridge ignores them.
- Navigation: Vezel subscribes to moduleID=0 (SUBSCRIBE_START), we answer "I do not send data". Which nav-events send the phone is unknown (there is no trip with route in logs).

## CarLife, MD→HU, channel CMD (all fields) proto2 required)
| What | Code | Proto / Fields | Where is the flight Baidu | Terms and Conditions |
|---|---|---|---|---|
| MEDIA_INFO | 0x10035 | CarlifeMediaInfo: source1, song2, artist3, album4, albumArt5 (bytes), duration6, playlistNum7, songId8, mode9 | RE/core/base/subscribe/MusicInfoSender.java:160-262 | init no; music subscription is enabled by default (:43), turns off STOP moduleID=2 |
| MEDIA_PROGRESS_BAR | 0x10036 | CarlifeMediaProgressBar{progressBar=1} | MusicInfoSender.java:293-316 | Trottled 1 C |
| NAVI_NEXT_TURN_INFO | 0x10030 | CarlifeNaviNextTurnInfo: action1, nextTurn2, roadName3, totalDistance4, remainDistance5, field6 | RE/core/base/subscribe/RGEventHUDCollection.java:109-125 | need subscription moduleID=0 (SUBSCRIBE_START) |
| MODULE_STATUS NAVI | 0x10026 | {moduleID=2, statusID} | RGEventHUDCollection.java:183,194 | 1 start of navigation, 0 End |
| NAVI_ASSITANTGUIDE_INFO | 0x10047 | - | 8.6.7 | DONE supportFlag=false |

Details:
- The 6 NextTurn: field in 8.6.7 `bytes turnIconData`; in refs/apollo-DuerOS and refs/CarProjection, `int32 time`. Wire-type is different, what awaits Vezel is unknown.
- action: 1 = initial display, 2 = update, 3 = end (end: nextTurn=24, roadName “目的地” meaning destination, empty icon; :186-193).
- remainDistance in meters; totalDistance is the value recorded when changing the road (:261-269).
- No Icon Baidu turn update does not send (:245,277). Icon PNG < 30720 B, otherwise 160×160 (:157-173).
- duration in seconds (MusicInfoSender.java:184). No progressBar units confirmed.
- Cover JPEG < 30720 B (CarlifeUtil.java:398), otherwise 160×160.
- For channel 20832125 (Vezel), there are no special branches in 8.6.7.
- Subscription: HU→MD 0x18043 → DONE 0x10044 (CarlifeDataSubscribe.java:18-53) → START 0x18045 / STOP 0x18046. moduleID: 0 Navigation, 1 assistant guide, 2 Music.

## AA that comes
- SDR has already announced MEDIA_PLAYBACK id=9 and NAVIGATION id=10 (type=2 = ENUM/ImageCodesOnly, minimum_interval_ms=1000) - AND/aa/AapMessages.kt:340-341. There will be no maneuver pictures with ENUM.
- ch9: 0x8001 MediaPlaybackStatus{state1 (2=PLAYING), media_source2, playback_seconds3, shuffle4, repeat5, repeat_one6}; 0x8003 MediaPlaybackMetadata{song1, artist2, album3, album_art4, playlist5, duration_seconds6, rating7}. refs/aasdk/protobuf/aap_protobuf/service/mediaplayback/.
- ch10: 0x8001/0x8002 START/STOP; 0x8003 NavigationStatus (0 UNAVAILABLE, 1 ACTIVE, 2 INACTIVE, 3 REROUTING); 0x8004 NextTurnEvent and 0x8005 NextTurnDistanceEvent (deprecated); 0x8006 NavigationState (steps/maneuver/road/lanes/cue); 0x8007 NavigationCurrentPosition (step_distance, destination_distances, current_road). Analysis: refs/headunit-revived/.../aap/AapNavigation.kt:28-130.
- Existing logs show this with incorrect labels because AapMessages.kt:159-167,395-402 applies media names to all channels. On ch9, MEDIA_START 0x8001 is MediaPlaybackStatus and MEDIA_CONFIG 0x8003 {status=…, max_unacked=…, f4=PNG} is Metadata with 256×256 PNG album art of 90–127 KB. On ch10, {status=2} is NavigationStatus INACTIVE.

## Mapping AA → CarLife
Music: song/artist/album → the same; album_art PNG → JPEG < 30 KB; duration_seconds → duration; playback_seconds → progressBar (Inspection of units); media_source → source; state → MODULE_STATUS MUSIC 1/0; mode - Not clear.
Navigation: NavigationStatus ACTIVE/INACTIVE → MODULE_STATUS NAVI 1/0 + action 1..3; road name → roadName; distance meters → remainDistance; totalDistance Counting themselves; ETA There are no fields.
nextTurn (RE/core/base/subscribe/RouteGuideParams.java): 1 directly, 2 right-forward, 3 right, 4 right-back, 5 turn, 6 left-back, 7 left, 8 left-forward, 9 entry to the ring, 10 the exit from the ring, 11–22 congresses/forks, 23 start, 24 The goal, 29/30 ferry. Mapping AA by meaning: STRAIGHT→1, SLIGHT R/L→2/8, NORMAL R/L→3/7, SHARP→4/6, U_TURN→5, ROUNDABOUT_ENTER/EXIT→9/10, FORK/KEEP/MERGE/RAMP→11–22, DEPART→23, DESTINATION→24.

## Bridge Now and Embedded Points
- ch9/10 opens, data is ignored: AND/aa/AapSession.kt:608 (dispatch :590).
- CarLife: subscription is accepted, on START only log (CarLifeSession.kt:324-336); DONE supportFlag=true for 0 and 2 (Protocol.kt:552-562); MODULE_STATUS - Protocol.kt:529-545; MEDIA_INFO/PROGRESS/NEXT_TURN in name table only (Protocol.kt:136,142,143).
- Embed: parsing ch9/10 - AapSession.kt:608; interface CarlifeLink - AaBridge.kt:10-38; implementation in CarLifeSession.kt:578 via sendCmd (:689); subscription flags - CarLifeSession.kt:333-336; builders - in Protocol.kt:529-573; log schemas - Proto.kt:131-210. SDR do not touch.
- AaBridgeCore without Android (AaBridge.kt:49-52, JVM-tests) → PNG→JPEG in CarLifeSession or Android-helper. Do not conflict with MODULE_STATUS MUSIC using isMediaInited (CarLifeSession.kt:377-380).

## head unit Vezel (logs/car-20261007-4/files/probe-20261007-094730.log)
Protocol 2.0, channel 20832125, FEATURE_CONFIG 4 key (all 0), NAVI_TYPE no. Subscription moduleID=0 + START. moduleID=2 is not subscribed (sending does not interfere). Does instrument cluster - not understand.

## Unknown (checked in the car)
- Which nav-messages does the phone send (legacy 0x8004/5 or 0x8006/7) - a trip with a route and a complete log of ch10.
- The 6 field format (icon or time), whether the head unit draws an arrow on the nextTurn or on the icon; when the ENUM icons draw themselves (a set of arrows).
- Does Vezel display music without a subscription to moduleID=2; units of progressBar; allowable cover/icon sizes.
