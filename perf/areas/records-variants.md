<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Records, variants and allocation

**Where it stands**: records are free when they do not escape, and the ones that
do are smaller than they were.

## Landed

| | |
|---|---|
| a record that is only a parameter's fields is not built | **12.3x** |
| a variant built as its own match's scrutinee is not built | **7.9x** |
| a record that never escapes its binding is not built | **7x** |
| binding a variant to a name before matching it | **5.9x** and every allocation |
| an `if` between construction and match | **2.8x** and every allocation |
| enable boxing elimination | 3.8x on any loop carrying a `let` |
| a `let` in a loop body cost 3x — and it was not the binding | |
| single-field record stores Int and Float UNBOXED | 19% less allocation |
| two-field | 24%, predicted to within 1% |
| arity 3 and 4 | 18% each |
| `MezzeVariant` is 48 bytes instead of 64 | 19% less on single-field variants |
| every `get` in the language allocated an Option | **1.93x** on map, ~2x on seq/list/bytes |

Measured from the other side later: `map::get_or` (7 658 ps) is CHEAPER than
`map::get` (9 529), which is the Option allocation showing up as a plain
operation that never builds one.

## Open

- `map::to_seq` builds a `{k, v}` record per entry: 20 872 ps/entry, 48.9x the
  map's own floor. The record work above says this should be cheaper than it is.
- Nothing measures allocation directly. The rule in use is to **count GC pauses,
  not nanoseconds** (`Pause Young` lines), and the count includes the build.
