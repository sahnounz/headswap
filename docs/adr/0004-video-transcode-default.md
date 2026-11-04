# 0004. Default video: transcoding 1280x720 / 15 fps, passthrough as exception

- Status: replaced by 0018
- Date: 2026-10-20 (PR #91), clarifications 2026-10-28 (#116) and 2026-10-30 (#120)
- Sources: PR #91, #116, #120, case h319, `aa/AaSettings.kt:51`, `aa/VideoMode.kt:162`, `aa/FpsSetting.kt:15`, `aa/CarPreset.kt`

## Context

Without repeating frame head unit disconnects USB on a static picture. Many head unit (Honda Civic, Pioneer, Hyundai CN7Do not hold 1080p/30.
In the direct passthrough, Honda shows a black screen (0005) after the reversing camera.

## Decision

Core: transcode to 1280x720, frame repeats every 100 ms. Default values in code:
`transcode = true` (`AaSettings.kt:51`), `VideoMode.DEFAULT = R720` (`VideoMode.kt:162`), `FpsSetting.DEFAULT = FPS15`
(`FpsSetting.kt:15`). History:

1. PR #91 (20.10): transcoding and 15 fps by default for all machines and presets "My car"; fps 15 Instead of the car.
   The reason was h319: the reason was in the settings, not in the code.
2. PR #116 (28.10): when switched off transcoding - passthrough with his IDR (0005); forced transcoding
   by wireless connection removed.
3. PR #120 (30.10): SPS-crop in passthrough always included; in presets passthrough Now only at Corolla Cross
   (`CarPreset.kt:57`, `transcode = false`). SPS-crop confirmed on Vezel, VW BT 1420, Tucson/Mobis, Corolla Cross,
   Mazda G6S.

## Alternatives

- Forced transcoding using wireless connection - removed in # 116.
- 4:3 mode for h319 - did not need, enough settings.

## Consequences

- passthrough is enabled by preset or user and requires working its IDR.
- Presets should not duplicate the features that the bridge determines itself (CN7, VW ID).
- 15 fps selected because of the weak head unit: gave everyone so that they do not get overwhelmed. Price - strong head unit receive less than they can;
  first affected owners Honda CR-V. Treated by switching to 30 fps or "Auto" in the settings. If you complain about
  “tight video” will be a lot - candidate for the preset or flag head unit with 30 fps.

## What changes in 3.0

In the 3.0 beta, everyone is forced to switch to the passthrough: straight line, it becomes the default mode, and the frame frequency
it is not adjustable (it is specified by AA). transcoding is turned on manually; when you turn on fps, it is taken from the previous
the user's choice, and if there was no choice - 15. The problem of CR-V with 15 fps at the same time leaves itself.
When 3.0 comes out, mark this entry as "replaced" and start a new one about passthrough by default (why moved).
