#!/usr/bin/env python3
"""Which collection operations has anyone ever MEASURED?

    scripts/collection-coverage.py            the matrix
    scripts/collection-coverage.py --missing  and the names

THE DENOMINATOR IS THE STDLIB'S OWN `pub let` LIST, deliberately, because a
hand-kept list of "what we have optimised" cannot tell you what it is missing.
That is not a hypothetical: `OP_COUNT_ALL` was declared in `NativeHofNames` and
referenced by nothing, so `iter::count` was the one iter sink that never fused
and ran 88x `iter::sum` for doing less work. Nothing said so, because no row
asked.

The numerator is every `module::op` call site under `bench/cases`, which is a
proxy for "a bench exercises it" and an honest one: if no bench names the
operation, no measurement of it exists.

A row being counted is NOT the same as the operation being fast. It means there
is a number. The band an operation should sit in is its own structure's floor —
`seq::size` for seq, `deque::size` for deque — and PERF-LOG records those.

WHAT IT CANNOT SEE: an operation exercised indirectly. The `sum(0..10M)` row in
`iter_perf_compare` calls `iter::fold`, so `iter::sum` counts as unbenchmarked,
which is the correct answer to the question asked here — nobody has measured
`iter::sum` itself.

AND THE INVERSE, WHICH HID A 10x. A call site in a bench is not a row measuring
it. `seqsurface`'s own harness calls `seq::sort` to take the median of its
passes, so `seq::sort` counted as benched from the day the harness was written
while NO row had ever timed it — and it was routing through `sort_by`, paying a
guest lambda per comparison. Adding that row moved `numbered` from 98 to 99 and
left `benched` at 196, which is the discrepancy as a number: this script counts
whether an operation is NAMED under bench/cases, and a harness names the
operations it uses on itself. For "is there a number for it", read the
`numbered` count and perf/coverage.tsv, not this one.
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODS = ["seq", "list", "map", "set", "deque", "bytes", "iter",
        "sorted_map", "sorted_set", "nonemptylist", "buffer"]

declared = {}
for m in MODS:
    path = os.path.join(ROOT, "stdlib/std/collections/%s.mz" % m)
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    ops = set(re.findall(r"^pub let ([a-z_][a-z0-9_]*)", src, re.M))
    declared[m] = ops

benched = {m: set() for m in MODS}
for path in glob.glob(os.path.join(ROOT, "bench/cases/*/src/*.mz")):
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    for m in MODS:
        for op in re.findall(r"\b%s::([a-z_][a-z0-9_]*)" % m, src):
            benched[m].add(op)

rows = []
for m in MODS:
    d, b = declared[m], benched[m] & declared[m]
    rows.append((m, len(d), len(b), sorted(d - b)))

rows.sort(key=lambda r: -(r[1] - r[2]))
tot_d = sum(r[1] for r in rows)
tot_b = sum(r[2] for r in rows)
print("%-14s %6s %8s %7s" % ("module", "ops", "benched", "cover"))
for m, nd, nb, missing in rows:
    print("%-14s %6d %8d %6d%%" % (m, nd, nb, round(100.0 * nb / nd)))
print("%-14s %6d %8d %6d%%" % ("TOTAL", tot_d, tot_b, round(100.0 * tot_b / tot_d)))

if "--missing" in sys.argv:
    print()
    for m, nd, nb, missing in rows:
        print("## %s — %d unbenchmarked" % (m, len(missing)))
        print("   " + ", ".join(missing))
