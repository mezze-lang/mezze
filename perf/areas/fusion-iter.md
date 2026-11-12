<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Fusion and pipelines

The largest category on this branch. Per-operation status for `iter` lives in
[datastructures/iter.md](../datastructures/iter.md); this page is the machinery.

**Floor**: a fused `iter::fold` over a range is **65 ps/element**.

## Landed

| | |
|---|---|
| `size` was not a chain sink | **1503x** on map, 36x on filter |
| a native arith body is an inline op | **460x** on any pipeline behind a HOF |
| monomorphise the local lambda nobody could reach | **314x** |
| enumerate fuses and its record disappears | **223x** |
| a pipeline chosen by an `if` now fuses | **214x** |
| `.map(f).fold(acc + x * k)` ran UNFUSED | **145.9x** |
| a map stage that is an EXPRESSION | **121.60x** |
| `iter::count` — a constant declared with no users | **76.98x** |
| an enumerate consumed by its own fold | **61x** |
| a fold over a chain is two folds, no new stage | **46.72x** |
| a provably dead spread is dropped | ~40x |
| a folder that reads a field of its element refused the WHOLE chain | **37.22x** |
| delete the callback instead of fusing the chain (bytes fold) | **37x** |
| a drop on a range is the range starting later | 658 -> 84 ps/elem |
| two operations are two maps, so FLP vectorises them | **5.58x** |
| the vector loops learn the extra map stages | 4.14x |
| a second and third `map` stage fuse | 1.33x, 1.52x |
| a fold whose folder DOES something keeps the fused loop | mandelbrot 2.50x |
| fusion no longer stops at a `let`, or at one inside a helper | |
| a dot-chained pipeline fuses; one split across a call fuses | |
| fusion was making set and deque SEVEN TIMES slower | guarded |

## Open

| row | ps/elem | vs floor |
|---|---|---|
| `zip+fold` | 73 284 | **1 120x** |
| `flat_map+fold` | 41 713 | **637x** |
| `filter+to_seq` | 1 719 | 26x — writing 5M longs, work not a cliff |

`zip` needs two cursors in one loop; a stage sees one element and a zip has two
sources. `flat_map`'s rewrite (`fold(src, init, {acc,x} -> fold(f(x), acc, g))`)
is the chain trick again, except it needs a lambda MINTED rather than existing
nodes reshuffled.

Two rules this surface produced: **a tier that is faster than one of the paths
it sits between is a regression if it is reached before the other** (preferring
the tree over `MapInline` cost 3.3x by leaving the vectorised loop), and a
hand-written ceiling probe can be a FLOOR — the sound per-site handler cache
beat its own probe 1.61x to 1.47x.
