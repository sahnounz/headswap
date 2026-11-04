# 0013. Legacy ADB-mode - separate APK with com.baidu.carlife package

- Status: Accepted (`main` is not included, lives in the `claude/carlife-adb-release` branch)
- Date: 2026-10-19 (decision on separate APK), 2026-10-30 (branchs not removed)
- Sources: case h60, branches `claude/carlife-adb-release`, `claude/carlife-adb-diag` (`origin`), `link/CarTransport.kt`

## Context

Old head unit (h60, Subaru) launch CarLife themselves: `am start com.baidu.carlife/.CarlifeActivity`, launch `bdim.jar` and search
`com.baidu.carlife` package. Ordinary Headswap (`com.yegorkapomidorka.headswap`) for such head unit is invisible.

## Decision

Separate assembly with `applicationId = com.baidu.carlife`, `versionName = 8.6.7`, `versionCode = 259` (as in this CarLife:
head unit can watch the package version). Assembled by `gradlew assembleDebug -PcarlifePackage` (in the branch: `android/app/build.gradle.kts:15`).
Communication with head unit to ADB - via `MultiSocketTransport` to `TransportCaps.TCP_ADB` (branch, `CarTransport.kt:23`; in `main` such
no constant, there are only `USB_AOA`, `TCP_DEBUG`, `TCP6`. Checking for updates is disabled (0012).
Egor 30.10: branches `claude/carlife-adb-release` and `claude/carlife-adb-diag` not to remove, support continues.

## Alternatives

- Mode switch in normal Headswap - not working: head unit is looking for a package with a specific name.
- stub-APK with the flag in the main application (19.10) - rejected to avoid mixing legacy and prod.

## Consequences

- Two lines of application: common code edits (`aa/`, `CarLifeSession`) will have to be pulled into the branch manually.
- The branch lives separately and does not merge into `main` - for the same reason: legacy and prod do not mix.
