<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Function calls

**Floor**: a counted loop is 211 ps/iter (`coresurface:1`).

**Where it stands**: the four ways to call something are at parity, which is the
whole point of this surface.

| spelling | ps/iter |
|---|---|
| direct call to a top-level fn | 625 |
| call a local closure | 630 |
| ability method, concrete receiver | 628 |
| ability method through a `with` constraint | 622 |

`bench/cases/ability_dispatch` reports a 5x spread here (plain 12-17 ns against
ability 2-3 ns). **That is an artifact** — one run per row and integer-nanosecond
division — and it is retracted in PERF-LOG. `coresurface` rows 6-12 are the
measured replacement.

## Landed

| | |
|---|---|
| a call to a local helper goes direct | **7.3x** |
| the trampoline asked for each parameter BY NAME per call | **8.73x** |
| the indirect-call bypass was refusing a lazy stub | **8.6x** closure, 2.7x `seq::fold` |
| a native HOF's callback never bypassed, same cause | **4.83x** `bytes::map` |
| "your own HOF costs 111x" | split into 1.9x call + 57x dictionaries |
| a function reference IS its specialisation | **8.2x** |
| specialise a HOF on a NAMED callback, not only a literal | **9.5x** |
| a HOF with two function arguments (from the trampoline fix) | **4.19x** |

## Open

- **A HOF with two POLYMORPHIC callbacks costs 11.6x** (10 279 ps against 886).
  With one callback the HOF is specialised and inlined to nothing — every call
  counter reads zero. With two, three million generic record-ABI indirect calls
  appear. Five hypotheses ruled out (call count, operand position,
  content-identity, bimorphic site, a HOF copy each) and it is MODULE-SENSITIVE:
  adding an unrelated declaration took it to 2 102. Mono resolves both refs
  `inst=null`.
- **A constrained call inside a do-block loop is 4.8x the same call at top
  level** (3 004 ps against 622). The remainder is a non-inlined call through
  the trampoline; the 39x that used to sit on top of it was the by-name lookup.
