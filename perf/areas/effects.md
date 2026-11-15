<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Effects

**Floor**: a counted loop is 233 ps/iter (`effectsurface:1`).

| row | ps/iter | |
|---|---|---|
| op with the handler installed once | 1 165 | 5x floor |
| op AFTER churn | 1 888 | 8x |
| install per iteration | **20 217** | 87x — was 54 044 |
| install per iteration, parameterless performer | 20 213 | matches the other form |
| abort per iteration | **86 050** | 370x — OPEN |

## Landed

| | |
|---|---|
| `resume { val = X }` costs what X costs | **3.7x**, no allocation, no call |
| memoise the handler lookup | **2.53x** on every churned effect op |
| a quiet effect gets its free tier back | **3.32x** |
| drop a redundant `assumptions` clause | 2.6x after churn |
| a handler record is resolved once per SITE, not per install | **1.61x** |
| the assumption memo + a lock-free given-up return | 1.20x |
| the other early return in `invalidate()` goes lock-free too | **1.43x** |

Install is **2.73x** cumulative on this branch (54 044 -> 19 781 ps).

Two lessons live here rather than in the numbers: a sampling profile credited
`ConcurrentHashMap.get` 307 of 700 samples and removing it was worth 1.06x,
while the `synchronized` beside it was the real cost — **twice, in the same
direction**. And giving a hot stateless Bytecode-DSL operation one `@Cached`
field cost **3.8x**.

## Open

**`abort/iter` at 86 050 ps is the largest single number left in the language**
and has no cliff in it: the profile spreads across the churned perform tier
re-resolving per call, `ThreadLocal.get`, and the unwind. The exception itself
is nowhere — it is already stackless (#57).
