<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Fusion — the machinery

Per-operation status for `iter` is in
[datastructures/iter.md](../datastructures/iter.md); the abstraction itself is
[iter](iter.md). This page is the canonicalizer and the emitted loop.

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

## Two rules this surface produced

- **A tier that is faster than ONE of the paths it sits between is a regression
  if it is reached before the other.** Preferring the expression tree over
  `MapInline` cost 3.3x by leaving the vectorised loop.
- **A hand-written ceiling probe can be a FLOOR.** The sound per-site handler
  cache beat its own probe, 1.61x against 1.47x, because the probe kept a
  boundary the real specialization removes.

## Open

- `zip` **1 120x** (needs two cursors in one loop) and `flat_map` **637x**
  (needs a lambda minted, not nodes reshuffled).
- **Bytes reaches exactly one fused shape.** The emitter's own comment says it
  "refuses any OTHER bytes shape", which is why `bytes::map` runs natively at
  4 484 ps a byte against `bytes::fold`'s 148.
- **`isEffectSafe` is a stub returning `true`** — see
  [iter-with-effects](iter-with-effects.md).
- `FUSED_EMITS` records nothing for an `iter` chain, and a typechecker lowering
  test lowers the stdlib FROM SOURCE where it fuses either way. Neither can tell
  a fused chain from a native one, so the only honest evidence is a real
  `mez build` A/B.
