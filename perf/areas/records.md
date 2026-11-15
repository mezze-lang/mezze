<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Records

**Where it stands**: a record that does not escape is not built at all, and the
ones that do are smaller than they were.

## Landed

| | |
|---|---|
| a record that is only a parameter's fields is not built | **12.3x** |
| a record that never escapes its binding is not built | **7x** |
| fold the desugared operator before judging a binding | 3.1x on a record helper |
| a record must not survive lowering into an arithmetic call site | |
| single-field record stores Int and Float UNBOXED | 19% less allocation |
| two-field | 24%, predicted to within 1% |
| arity 3 and 4 | 18% each |

`recordcost` reports a positional record at **221 ps/iter** against a 211 ps
counted loop — records are free at the point of use.

## Open

- **`map::to_seq` builds a `{k, v}` record per entry**: 20 872 ps an entry,
  48.9x the map's own floor. The unboxing work above says that should be
  cheaper, so this is the first place to look for whether it reaches natives.
- `recordcost` prints ONE row with no argument — the same all-rows gap
  `asyncsurface`, `collfold` and `str_bench` have. Arity 5+, nested records and
  record UPDATE (`{ r | x = 1 }`) have no rows.
- Nothing measures allocation directly. The rule in use is to **count GC pauses,
  not nanoseconds** (`Pause Young` lines), and the count includes the build.
