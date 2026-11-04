# tools

## logdiag.py: likely causes and suggested actions

Accepts a user log and returns one to three likely causes with log evidence, advice, and a follow-up question.
It builds on `logdigest.py`, uses only the Python 3 standard library, and runs the same way on Windows and Linux.

```sh
python -X utf8 tools/logdiag.py <archive.zip|directory|file.log> [--json] [--all]
```

- `--all` includes all matching rules rather than the top three. `--json` returns structured facts and causes.
- Rules are data in `logdiag_rules.json`. `markers` contains regexes over raw logs; matches within two seconds
  count as one event. Conditions in `all`/`any`/`none` use `[fact, operator, value]`. Operators:
  `== != >= <= > < ~` (regex), `<v`/`>=v` (versions).
- Facts include `m.<marker>` event counts and digest values: `n_sessions`, `frames_max`, `aa_video_max`
  (AA video, excluding placeholder frames), `eio_sessions`, `eio_short` (USB failures within 15 seconds),
  `no_gms`, `pause_eio` (EIO three to ten seconds after VIDEO_ENCODER_PAUSE), `version`, `sdk`, `hu`,
  `channel`, `requested`, and `ep_<disconnect-type>`. Missing data (`None`) is not zero.
- `score` is rule confidence; `boost` increases it; `suppresses` removes more general findings.
  `fixed_in` suppresses a rule when the user's version includes the fix; otherwise it adds upgrade advice.
  `category: note` places information below the causes, such as an old version or a working session.
- A new cause needs a JSON rule and a marker based on a real case. Verify with
  `python -X utf8 -m unittest tools/test_logdiag.py`.

## logdigest.py: trip-log summary

Reduces a Headswap log archive to 40–80 lines: environment, sessions, disconnects, frame rate, errors,
AUTOSRV, audio/microphone, navigation, and touch. Uses only the Python 3 standard library.

```sh
python -X utf8 tools/logdigest.py <archive.zip|directory|file.log> [--json]
```

Accepted input:

- An app-generated Share report/Save archive ZIP containing `device.txt` and `logs/headswap-*.log`
  (`probe-*.log` in older versions), truncated to the last 4 MiB per log.
- A directory of complete logs captured with adb, searched recursively; `logcat-*.txt` is included.
- A single `.log` file.

`--json` returns the same data structure used by the text renderer.

Sections and their sources:

1. **Environment:** `device.txt`; identify the head unit only from `HU_INFO`. The accessory line always identifies
   Baidu CarLife rather than the head unit.
2. **Sessions:** from `== USB_ACCESSORY_ATTACHED` or the session-start marker to the session summary.
   Duration and reason come from that summary. `~` before the start time means the session began before the log
   was truncated and its start is estimated. “Log ended” means no summary was found. The AA column uses the main
   AA session, choosing the summary with the most data; parentheses count AA sessions. The second is often
   the short “bridge stopped” placeholder session.
3. **Disconnects:** EOF, EIO/ENODEV, BYEBYE_REQUEST, USB_ACCESSORY_DETACHED, peer closed, and logcat
   POWER_DISCONNECTED (ignition). Events within fifteen seconds form one episode. The no-GMS flag means EOF
   within five seconds after MEDIA_START, not MEDIA_PLAYBACK, with config=0 and zero video frames.
4. **Video:** bridge-video and sent-video counters; discard intervals outside three to fifteen seconds.
5. **Errors:** `ERROR` or `Exception` lines, with numeric values normalized.
6. **AUTOSRV:** automatic-server events.
7. **Audio/microphone:** MIC_REQUEST on NAVIGATION(10) is not a microphone event.
8. **Navigation:** 0x18043/0x18045 and NAVI_NEXT_TURN_INFO.
9. **Touch:** input and summary counters.

Existing raw logs and parser markers contain Russian text. Their exact spellings are intentionally retained
in source and fixtures because they are part of the diagnostic format. Search the relevant time windows from
the digest rather than reading the full log.

Test: `python -X utf8 -m unittest tools/test_logdigest.py`.

## idr_splice_check.py: locally generated IDR frames against an AA recording

Validates the same operation as the app's “Own key frame every two seconds” setting (`aa/IdrSplice.kt`)
against recorded Android Auto H.264, for example a `pc-headunit` capture. It checks stream suitability
(CAVLC, `max_num_ref_frames = 1`), generated IDR size (560000-byte limit), and bit-for-bit image equality
after splicing and when decoding starts at the generated IDR.

Requires numpy and ffmpeg. It validates only lossless IDR generation. Above the size limit, the app uses
compressed IDR frames (`aa/IdrIntra.kt`), covered by JVM golden tests cross-checked against ffmpeg.

```sh
python -X utf8 tools/idr_splice_check.py <capture.h264> [--every 60] [--pcm-only]
```
