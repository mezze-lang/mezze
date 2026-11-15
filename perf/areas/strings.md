<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Strings

## Landed

| | |
|---|---|
| `split` stopped compiling a regex per call | **1.88x**, with fourteen untouched rows as the control |
| interpolation budgeted 16 characters a slot | a two-character result asked for seventeen |
| interpolation now allocates exactly what the concat it desugars to allocates | |
| case conversion stops reading the host's LOCALE | correctness: `to_upper` and HTTP header lookup were machine-dependent |

## Measured and left alone

An ASCII fast path for case conversion: **tried, measured, reverted.** The gap
is vectorisation, not the branch. `str::create` is 7.9 ns + 33.7 ps/char and
`to_upper`'s 23x is the vectorised copy.

## Open

`bench/cases/str_bench` prints ONE row with no argument (`str_size`), the same
all-rows gap `asyncsurface`, `collfold` and `recordcost` have. The string
surface therefore has almost no coverage: nothing measures `concat`, `slice`,
`index_of`, `replace`, `trim`, `pad`, or the codec paths.
