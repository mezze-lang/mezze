<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Dispatch and abilities

**Where it stands**: an ability call costs what a direct call costs (628 ps
against 625). Ability dispatch is not a cost in this language any more — the
cost was always DICTIONARY PASSING, and that is where the work went.

## Landed

| | |
|---|---|
| a chain of operators is monomorphised, no annotation needed | **249x** |
| monomorphise the local lambda nobody could reach | **314x** |
| dictionary passing was 47.5x of the 122x mandelbrot gap | verified against Scala/Kotlin |
| mono fills the residue no call site can supply | 2 833 -> 58 ns/px |
| a known associated output determines the operands | 4x on the un-annotated mandelbrot |
| a specialised recursive fn recursed into its generic self | **45x** |
| a function reference IS its specialisation, not a dictionary wrapper | **8.2x** |
| the dict wrapper HOP is the cost, not its machinery | |
| a record must not survive lowering into an arithmetic call site | |

## Open

- The `inst=null` family: mono declines to instantiate a reference whose site
  type still carries a variable. It is the cause behind BOTH open call cliffs
  (the two-polymorphic-callback 11.6x and the do-block dict call 4.8x).
- **Ruled out, recorded**: making the solver determine a `having out` from the
  impl when the operands are concrete. It FIRES (22 `NumMul Int Int -> Int` in
  one build) and moved neither cliff. Reverted.
