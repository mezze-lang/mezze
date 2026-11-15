<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Polyglot

**Measured 2026-11-12, and the headline is that a gap closed.** The bench did not
build until its `mezze-js` dependency was placed in `.deps/mezze/mezze_js-0.5.1/`
and `mez sync` fetched the two GraalJS maven coordinates.

## One crossing (`polyglot_interop`, 4M iterations, best of 5)

| row | 2026-09-22 | now |
|---|---|---|
| control (no guest) | 252 ns | **16 ns** |
| read (matched spelling) | 424 ns | **165 ns** |
| read (dot-chain) | 509 ns | **161 ns** |
| **chain − matched** | **+85 ns** | **−4 ns** |
| index | 544 ns | **173 ns** |
| invoke | 855 ns | **262 ns** |
| guest init (first eval) | | 8 ms |
| eval of the same source | | 11 us/iter |

**The dot-chain penalty is gone.** It is recorded elsewhere as "the polyglot
dot-chain costs 187 ns more than the matched spelling", and it was the
motivation for a numbered task series; the two spellings are now identical to
within noise. The second step of that chain is an ability method on `Result`,
and an ability call now costs what a direct call costs — so this is almost
certainly the dispatch and monomorphisation work on this branch, collected
rather than aimed.

A crossing itself is ~149 ns net of the control, and `invoke` ~246 ns.

## FINE CROSSING IS THE TRAP (`polyglot_array_read`, 2 000 reps)

| n | per-element | whole-array | |
|---|---|---|---|
| 1 000 FOREIGN | 77 us | 12 us | **6.4x** |
| 1 000 NUMERIC (`at`+`int`) | 142 us | `to_ints` bulk 22 us | **6.5x** |

Reading a guest array one element at a time costs ~6.5x reading it in bulk, at
both shapes and every size tested. The bulk entry points exist; the cost is
paid by anyone who does not know to use them.

`polyglot_seq_crossing` pushes a Mezze seq INTO the guest via source text:
9 us/crossing at n=100, **48 us/crossing at n=1 000** — roughly 48 ns an
element, which is what building JS source text costs.

## Open

- Nothing is optimised here yet; these are baselines. The 6.5x fine-vs-bulk
  ratio is the obvious target, and the honest fix may be a diagnostic that tells
  the user to cross in bulk rather than a faster per-element path.
- `polyglot_seq_crossing` goes "via source", which is the slowest possible
  route. Whether a direct value handoff exists and what it costs is unmeasured.
- Python (`mezze-python` is a sibling package) has no bench at all.
- Three polyglot cases need `.deps` wiring by hand to build, so none of them
  runs in any automated sweep.
