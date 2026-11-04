# 0006. Bypasses under head unit are included with the flag by channel/model, not globally

- Status: adopted
- Date: 2026-10-14 (PR #18, #10), before 2026-10-30 (PR #113)
- Sources: PR #10, #18, #56, #72, #89, #113, cases h93, `aa/AaBridge.kt:192-214`, `:412-424`, `:642-710`

## Context

The 1.1.6 was a global keepalive on PAUSE. Editing under Mazda and VW broke Honda (h93). Different head unit sever communication over different
You can try it out only on a live car.

## Decision

Each bypass is a separate flag in `AaBridge`: `huDesayG6S`, `huVw20082120`, `huVwBt1420`, `huVwCns2Delphi`,
`huHyundaiCn7`, `huMazda20622111`, `huMobisNx4c` (`AaBridge.kt:192-214`). head unit Identified by
`HU_INFO` (manufacturer/model/product) or `STATISTIC_INFO.channel` (`:642-710`). Flags are reset at each
new session until head unit is re-introduced (`:412-424`). The bypass is valid only on its head unit, in test builds too.

## Alternatives

- Global keepalive (1.1.6) - error, spread over head unit in PR #18.
- Self-learning `PauseKeepalive` - delayed after 3.0.0. Fork github.com/mhzsajan/headswap (reliability-fork based on
  2.5.0, assemblies of `2.5.0-reliability5`), file `PauseKeepalive.kt`. Idea: channel that broke down on PAUSE via ≥ 3,5 with
  with empty VIDEO_DATA, is remembered by the channel / transport pair, and then heartbeat goes immediately. Would replace manual
  flags h93, h222, h224, h425, h211. Exceptions: "Stop" user, heartbeat already sent, Desay G6S by USB.
  Delayed to avoid mixing with straight passthrough (SPS-crop, its IDR) in 3.0: otherwise do not understand what worked and what
  broken. Take a separate test assembly.

## Consequences

- Binding for VW had to change. At first, the bypass was included by name BT head unit («VW BT 4370», «VW BT 9491), but the name of each
  cars, and for each new owner had to add a line. PR #113 Switched to firmware channel
  (20082138) with the exception of "VW BT 1420", which bypass harms, and where the channel is common with other machines - by a couple
  "channel + name" (`AaBridge.kt:682-690`, `VW_ID_IDR_LIMIT_HUS`).
- All Honda logs behave the same - their fix on the entire brand. VW and Mazda are all different - their fix on a model or channel.
- New bypass add with log when enabled and reset at new session; do not change the behavior of the rest head unit.
