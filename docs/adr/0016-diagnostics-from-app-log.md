# 0016. Diagnose from application logs; log format is a contract

- Status: accepted
- Date: 2026-10-22 (PR #99, #102), started 2026-10-14 (PR #7)
- Sources: PR #7, #99, #102, #65 (external, closed), `tools/logdiag.py`, `tools/logdiag_rules.json`, `tools/logdigest.py`, `diag/LogDiag.kt`, `android/app/build.gradle.kts:77-96`

## Context

There are hundreds of connection support requests, and users cannot capture `adb logcat`. The practical source
is the log written by the app and exported by the user as an archive.

## Decision

- `tools/logdiag.py` applies `tools/logdiag_rules.json` and returns one to three causes, evidence, advice, and
  a follow-up question. `logdigest.py` summarizes the trip.
- Kotlin's `diag/LogDiag.kt` reads the same rules. The build copies them to `assets/logdiag_rules.json`
  (`build.gradle.kts:77-96`); no duplicate is tracked. Analysis runs offline and logs stay on the phone (PR #102).
- Python and Kotlin produced identical top-three results across 340 logs. `fixed_in` checks the app version.
- Log lines are a contract: rules depend on their markers. Do not rename or collapse those lines without updating
  `logdiag_rules.json` and the tests.

## Alternatives

- Asking users for `adb logcat`: rejected because it does not work in practice.
- `LogThrottle` from external PR #65: do not adopt it while the analyzers count individual media lines;
  collapsing lines changes those counts.

## Consequences

- Changes to log text, especially session, EIO, PAUSE, and probe lines, must pass `tools/test_logdiag.py` and parity checks.
- Head unit server probe messages must not match LogDiag session markers (addressed in commit 7e8e44c; 0003).
- Reconsider line collapsing only if both analyzers stop counting media lines individually; update both together.
