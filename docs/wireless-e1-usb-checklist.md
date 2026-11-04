# E1: regression of USB to Vezel after CarTransport

Branch `claude/wireless-e1-transport`. In the same branch targetSdk Raised to 37: the session checks it also (to Android 16 behavior should not change). USB now goes through `link/StreamTransport` (Reading and writing code is transferred from `CarLifeSession` nothing should change on the wire. One session in the car, about 10 minutes.

## Assembly

Test release- Assembly with a temporary version (for example, `1.1.9-e1test1`, versionCode do not touch or +1 locally), revision of the version in commit is not going. Certificates AA in `android/app/src/main/assets/aa/` (in git they are not). Put on top of the release.

## In the car

1. Phone unlocked, cable in USB head unit. Wait for AA on screen head unit.
2. AA is visible, the picture does not twitch. touch: scroll through the list, open the map, click the button.
3. Sound: turn on music (MEDIA), run navigation with voice prompt (TTS).
4. Microphone: Voice button on the steering wheel, say command, AA should recognize.
5. Grace: pull the cable, on the phone's screen status "USB lost, AA alive N with" with a countdown; insert the cable during 8 s. AA must return without restart (in the log "USB returned to grace- window").
6. The second break: pull out the cable and wait for more 8 s. The status should not hang on "live 0 s", after inserting the cable session rises again.
7. Screen Headswap on the phone: visually as before (status card, reconnect indicator in step 5).
8. Send report by button (zip with logs).

## What to see in the log (check with `logs/car-20261011-meta/headswap-20261011-161228.log`)

- New line `сессия: транспорт=usb-aoa (<аксессуар>)` immediately after the video mode description.
- New lines `WL link …` and `WL hs …` (structural phase log). There should be no other new lines.
- `видео: отправлено frame=… сброшено=… очередь=…`tempo frame and `сброшено` (no more than 1 per session) as in the benchmark.
- The “ Linebitrate encoder» 0, `отброшено encoder/гейт` 0/0.
- The AA is of the same order (4,7–7,8 MiB/s).
- Session summary: accepted byte, touch, frame; the reason for closing when pulling the cable as before: "accessory disabled (broadcast)" or "EOF reading (head unit disabled)".
- `python tools/logdigest.py <лог>` passes without errors, the output is comparable to the standard.

## If something is wrong

Log from the report and step of the check-list, where it was distributed. Rollback: put the release of 1.1.9 from the site.
