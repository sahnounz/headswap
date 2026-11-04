# 0017. Head unit limitations: registry of issues the app cannot fix

- Status: accepted (registry remains open for additions)
- Date: 2026-10-19 (PR #40 and #41 closed)
- Sources: PR #40 and #41 (both h177, closed as not solvable in the app), cases h177, h100, h291

## Context

Some reports reflect head unit limitations rather than Headswap defects. Record them here to avoid repeating
unsuccessful investigations. Details remain in the individual hNN case records.

## Decision

Treat the following as head unit limitations:

- Mazda G6S sends no touch input while moving (h177); LifeAuto behaves the same. Treating clicks as taps (PR #40)
  and removing modules 8/9 (PR #41) did not help.
- Portrait HSAE units render CarLife in a 16:9 window; portrait output is unavailable. Recommend R720.
- Honda e:NS1 has no music (h100), although Baidu plays it. Closed unresolved; further work needs a Baidu log.
- Tharu XR CNS3 does not display navigation on the instrument cluster without a head unit subscription.
- Honda Vezel 20832125, Yegor's unit, does not show album art or song titles on the instrument cluster.

## Alternatives

- Application workarounds for h177 were tested in PR #40 and #41 and did not help.

## Consequences

- Offer appropriate guidance, such as R720 instead of portrait or using a cable, rather than opening the same work again.
- Revisit a limitation when new evidence becomes available, such as a Baidu log for h100 or a cluster-data subscription.
