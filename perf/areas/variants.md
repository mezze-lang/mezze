<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Variants

**Where it stands**: a variant that is constructed and immediately consumed is
not built, and the ones that survive are 48 bytes instead of 64.

## Landed

| | |
|---|---|
| a variant built as its own match's scrutinee is not built | **7.9x** |
| binding a variant to a name before matching it | **5.9x** and every allocation |
| an `if` between the construction and the match | **2.8x** and every allocation |
| `MezzeVariant` is 48 bytes instead of 64 | 19% less on single-field variants |

The three elision rows are one family: the allocation survives exactly as long
as something stands between the constructor and the `match`. A name costs 5.9x,
a branch costs 2.8x, and neither is visible in the source.

## Open

- **Variant tag dispatch under AOT** has its own machinery (`just aot-parity`)
  and no perf row: nothing compares a tag dispatch on the JVM against the
  native image.
- Multi-field variants, nested payloads and variants carrying records have no
  rows; only the single-field case was measured for the 48-byte change.
- `Option`'s allocation is covered under
  [pattern-matching](pattern-matching.md), where the elision lives.
