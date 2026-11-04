# 0018. Since 3.0, video defaults to passthrough and Auto frame rate

- Status: accepted
- Date: 2026-11-01
- Sources: PR #129, supersedes 0004, `aa/AaSettings.kt` (`transcode`, `migrateTo30`), `aa/FpsSetting.kt`, `aa/CarPreset.kt`, `ui/Upgrade30Notice.kt`

## Context

Passthrough with locally generated IDR frames (0005) and SPS cropping (#120) works on Vezel, VW, Tucson/Mobis,
Corolla Cross, and Mazda G6S. Transcoding heats the phone, consumes battery, and adds about 60 ms of latency.

## Decision

Make passthrough the default: `transcode = false`, `FpsSetting.DEFAULT = AUTO`. All car presets also use
passthrough and Auto, retaining their resolutions. Elantra 7 (CN7) is an exception because passthrough flickers (h638):
its preset uses 1280×480 transcoding at 15 fps. Without a preset, `huHyundaiCn7` forces transcoding automatically,
as for Pioneer RM56 and Mobis CCNC.

`AaPrefs.migrateTo30()` migrates existing users once. Both MainActivity and BridgeService call it to cover USB startup.
A one-time passthrough notice explains how to re-enable transcoding if needed.

## Alternatives

- Only changing the default would leave transcoding enabled for users who previously changed the switch, so this was rejected.

## Consequences

- AA controls the frame rate in passthrough; bitrate cannot be adjusted (0008).
- Migration overwrites the previous frame rate selection with Auto. Validate the new defaults on CR-V
  (h72: artifacts on Auto) and Outlander (h10, h41: worked at 15 fps).
