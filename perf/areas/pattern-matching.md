<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Pattern matching

**Floor**: a counted loop is 211 ps/iter.

| row | ps/iter | |
|---|---|---|
| `match Some { val } is Some/None` | 214 | **at the floor** |
| a 3-constructor user variant, all arms present | 218 | **at the floor** |

Matching itself is not a cost in this language. Constructing the thing being
matched was, and that is where the work went.

## Landed

| | |
|---|---|
| every `get` in the language allocated an Option | **1.93x** on map |
| seq, list and bytes `get` stop allocating too | ~2x each |
| the same elision for deque's two reads | **2.50x** / **2.75x** |
| a variant built as its own scrutinee is not built | **7.9x** |

Measured later from the other side: `map::get_or` (7 658 ps) is CHEAPER than
`map::get` (9 529), which is the Option allocation appearing as a plain
operation that never builds one.

The elision is a FUSED pattern — `match <native get> is Some/None` is recognised
and lowered to a null test, so the Option never exists. It covers map, seq, list,
bytes and deque. It does NOT cover any other `Option`-returning operation.

## Open

- **`first`, `last`, `min`, `max`, `find`, `pop`** and every other
  `Option`-returning operation are outside the elision. Each is an allocation
  per call that the five covered ones no longer pay.
- **Seq patterns** (`[a, b, ..rest]`), **match guards** and **nested patterns**
  have no rows at all. The guard's `|`-scoping and the arm-widening rules have
  correctness notes but no cost numbers.
- **Str and Char patterns**: `Char` is a one-character `Str` at runtime, so
  matching one is a string comparison. Unmeasured.
- Exhaustiveness and decision-tree shape are not measured for wide variants —
  the 3-constructor row is the only data point, and it is at the floor.
