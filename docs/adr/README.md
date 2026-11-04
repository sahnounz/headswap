# Architecture decision records (ADRs)

Record decisions that code alone cannot explain: why an approach was chosen, what was tried, and why alternatives
were rejected. Code shows how; an ADR preserves why. Use one record per decision, usually 20–40 lines.

## When to write one

Write a record when someone might ask “why?” in six months and the code would not answer:

- Choosing between approaches, such as transcoding versus passthrough or P2P versus a hotspot.
- Rejecting an option after a vehicle test, especially if someone might try it again.
- Introducing an invariant that is easy to break, such as preserving server 5277 or sending START only after RSP.
- Adding a workaround for a class of head units that changes shared behaviour.

A one-off fix, rename, or text edit does not need an ADR. Individual head unit cases belong in the private hNN case memory.

## Authorship and changes

Claude writes the ADR in the PR that makes the decision; Yegor reviews and corrects it. The rationale comes from
the session discussion, PR description, or vehicle logs. If it is unknown, write `Why: ? (ask Yegor)` instead of inventing it.

If a decision changes, retain the old record, mark it “Superseded by NNNN”, and link to it from the new record.

## Format

Use sequential filenames `NNNN-short-latin-title.md`. Start from [0000-template.md](0000-template.md).

## Index

| No. | Decision | Status |
|---|---|---|
| 0001 | [Client of Android Auto's head unit server on 127.0.0.1:5277](0001-aa-client-of-hu-server-5277.md) | Accepted |
| 0002 | [No accessibility service; manual server startup](0002-no-accessibility-service.md) | Accepted |
| 0003 | [Single-use server 5277 and complete handshake probes](0003-server-5277-one-shot.md) | Accepted |
| 0004 | [Transcoding at 1280×720 / 15 fps as the original default](0004-video-transcode-default.md) | Superseded by 0018 |
| 0005 | [Locally generated IDR frames in passthrough](0005-own-idr-in-passthrough.md) | Accepted |
| 0006 | [Head unit workarounds selected by channel/model flags](0006-per-hu-workarounds.md) | Accepted |
| 0007 | [Empty VIDEO_HEARTBEAT every 100 ms on PAUSE](0007-video-heartbeat-on-pause.md) | Accepted |
| 0008 | [Channel-specific bitrate ceilings based on IDR size](0008-idr-size-bitrate-ceiling.md) | Accepted |
| 0009 | [Separate USB headers and body chunks up to 16 KiB](0009-usb-write-chunking-16k.md) | Accepted |
| 0010 | [Baidu CarLife 8.6.7 as the behavioural reference](0010-baidu-carlife-reference.md) | Accepted |
| 0011 | [Dedicated decoder input thread and FeedQueue](0011-decoder-feed-thread.md) | Accepted |
| 0012 | [Automatic updates through GitHub Releases](0012-updates-via-github-releases.md) | Accepted |
| 0013 | [Separate legacy ADB APK using com.baidu.carlife](0013-legacy-adb-separate-apk.md) | Accepted |
| 0014 | [Wireless transport, Wi-Fi Direct, and network beacons](0014-wireless-transport.md) | Accepted |
| 0015 | [AA head unit identity remains Google / Desktop Head Unit](0015-aa-sees-desktop-head-unit.md) | Accepted |
| 0016 | [Offline application-log diagnostics and stable log text](0016-diagnostics-from-app-log.md) | Accepted |
| 0017 | [Registry of head unit limitations](0017-hu-limits-wont-fix.md) | Accepted |
| 0018 | [Passthrough and Auto frame rate by default since 3.0](0018-passthrough-default-3-0.md) | Accepted |
