# tools/: log analysis

- To analyze a user log, first run `python -X utf8 tools/logdiag.py <zip|log>` for likely causes and advice, then `logdigest.py` for a summary.
- `logdiag_rules.json` is shared with the app: Gradle's `copyLogdiagRules` copies it into assets, and `diag/LogDiag.kt` reads the same rules.
  A new cause requires a JSON rule and a case in `logdiag_cases/` based on a real log. Python and Kotlin must produce the same result
  (`tools/test_logdiag.py`, `LogDiagTest.kt`).
- Markers are regular expressions matching raw log lines. The `AppLog`/`WLog` line format is a contract (ADR 0016).
- Do not commit user logs: this repository is public.
