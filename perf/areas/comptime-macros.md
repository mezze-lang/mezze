<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Comptime and macros

**Yes, Mezze has macros, and they are merged.** `name!( … )` on main since
`b0a5aa3e9`, `std::macro` with 6 operations, `std::syntax` as the value layer,
`std::comptime` and `std::codebase` beside them. `MacroExpansionTest` is 21
tests and passes.

What a macro is: an ordinary pure function typed `Macro`, given the TEXT between
its parentheses, returning CODE as `std::syntax` values — most easily written as
a quote, `` `(~a + ~b) ``. It runs when its caller is compiled, in the editor
too, and `MacroExpander` runs it from `ModuleResolver.stampGroup` **before
identities are taken**, so a declaration's hash covers the expansion.

## Compile-time cost: it collapsed

`bench/scripts/macros.sh` measures what `mez check` pays for N `name!( … )`
calls against the same code written by hand, as a SLOPE and a FIXED cost rather
than one diluted total.

| | 2026-10-22, first reading | 2026-11-12 |
|---|---|---|
| per call | **2.2 ms** | **0.01 ms** |
| fixed | **5.8 s** | **−1 ms** |

| calls | with macros | written by hand |
|---|---|---|
| 10 | 418 ms | 419 ms |
| 200 | 425 ms | 464 ms |
| 1 000 | 449 ms | 445 ms |

**A thousand macro calls are indistinguishable from writing the code out.** The
5.8 s fixed cost was described as "most of it loading `std::syntax`'s fully
expanded types and starting the interpreter"; whatever now serves those types —
the precompiled stdlib is the likely answer — removed it entirely. The −1 ms is
the macro column coming out faster than the hand column at N=10, which is noise
and the honest way to report "no measurable fixed cost".

This was not aimed at. It is the second thing on this branch found to have
fixed itself as a side effect of other work, the first being the polyglot
dot-chain penalty.

## What is NOT measured

- **Expansion is cached** in an in-process LRU keyed on (macro decl hash, input
  text), with the input passed at a fixed origin so spans relocate on decode and
  moving a call reuses the entry. The cache HIT is what the 0.01 ms slope
  measures; a cold miss per distinct input is not separated out.
- **Editor latency.** A macro runs while you type, and nothing measures the
  incremental path — only `mez check` on a cold project.
- `std::syntax` (29 operations), `std::codebase` (2) and `std::comptime` (1)
  have no per-operation rows: `tokens`, `parse_expr`, `parse_decls` and the
  quote encoder/decoder are unmeasured individually.
- **`SyntaxRoundTripTest` covers 86 files and 2 542 declarations** for
  correctness, so the encode/decode pair is well tested and entirely unpriced.
- Codebase queries are compile-time only and have no cost rows.
