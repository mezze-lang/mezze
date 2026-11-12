<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Iter — the lazy abstraction

Per-operation costs: [datastructures/iter.md](../datastructures/iter.md). The
canonicalizer: [fusion](fusion.md). Effectful callbacks:
[iter-with-effects](iter-with-effects.md).

**Floor**: a fused `iter::fold` over a range is **65 ps/element** — the cheapest
per-element cost in the language, cheaper than any materialised collection's
walk.

## Where it stands

16 of 31 operations have a bench row, 14 have a number. Everything measured
sits between 1.0x and 10x of the floor except three rows:

| | ps/elem | vs floor |
|---|---|---|
| `fold` / `range` | 65 | 1.0x |
| `reduce` | 89 | 1.4x |
| `count` | 120 | 1.8x |
| `filter` / `map` | 131 / 133 | 2.0x |
| `any` / `find` / `all` | 418-442 | 6.4-6.8x |
| `chain` | 554 | 8.5x |
| `enumerate` | 654 | 10.1x |
| `to_seq` | 1 719 | 26.4x |
| `flat_map` | 41 713 | **641.7x** |
| `zip` | 73 284 | **1127.4x** |

`to_seq` writes 5M longs into a growing builder, so 26x is work. The other two
are features that have not been built.

## Open

- **The cursors are unmeasured**: `cursor` and `cursor_next` are the PULL view
  over an iter — a second mechanism to the push walk, mutable position, not
  thread-safe — and no row touches either. They are also what a real `zip`
  would be built on.
- `unfold` is called in two benches with NO number. A previous session measured
  its ceiling at 6.5x where it had looked like 700x.
- `sum`, `take`, `take_while`, `drop`, `drop_while`, `for_each`, `first`,
  `last`, `once`, `empty`, `from_list`, `range_step`, `to_list` — 15 operations
  with no row. **`iter::sum` is unmeasured because the bench row called `sum`
  actually calls `iter::fold`.**
- Converting between iter and every other collection (`from_seq`, `from_list`,
  `to_seq`, `to_list`) is the boundary users cross most and only `to_seq` has a
  number.
