#!/usr/bin/env python3
"""Regenerate the pages under `perf/` that can be derived from source.

    scripts/perf-status.py

WHY GENERATED. The per-operation checklists are 1 433 lines of state across 79
stdlib modules, and a hand-maintained list of what has been optimised is
precisely the thing that cannot say what it is MISSING: `OP_COUNT_ALL` was
declared in `NativeHofNames` and referenced by nothing, so `iter::count` ran 88x
`iter::sum` for doing less work and nobody knew. So the operation lists come
from the stdlib's own `pub let` declarations, the benched/not split comes from
the bench sources, and only the NUMBERS are curated — in `perf/coverage.tsv`,
one line per measurement.

THE SCOPE WAS WRONG ONCE, which is why groups are derived from the module PATH.
The first version globbed `stdlib/std/collections/*` and reported 589 operations
at 21% coverage. `str` is not in that directory; neither is `mutation` with its
123 operations, `datetime` with 66, or any effect module. The real denominator is
1 433 and the real coverage is a third of what was claimed.

WHAT IT CANNOT SEE:
  - an operation reached through an ABILITY call (`Mut.set {}` rather than
    `mutation::set`), which is how some measured mut work is invisible here;
  - an operation exercised indirectly — the `sum(0..10M)` row calls
    `iter::fold`, so `iter::sum` counts as unmeasured, which is the correct
    answer to the question this asks.

The area pages under `perf/areas/` are PROSE and are not generated.
"""
import glob
import os
import re
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# A module's group comes from its PATH, so a new stdlib module lands somewhere
# by construction rather than by being remembered.
GROUPS = [
    ("collections/", "datastructures", "Data structures"),
    ("effects/", "effects", "Effects"),
    ("codec/", "codecs", "Codecs"),
    ("math/", "numbers", "Numbers and math"),
    ("abilities/", "meta", "Abilities, meta and tooling"),
    ("polyglot/", "meta", "Abilities, meta and tooling"),
]
ROOT_GROUP = {
    "str": ("datastructures", "Data structures"),
    "char": ("datastructures", "Data structures"),
    "int": ("numbers", "Numbers and math"),
    "float": ("numbers", "Numbers and math"),
    "nat": ("numbers", "Numbers and math"),
    "frac": ("numbers", "Numbers and math"),
    "bool": ("numbers", "Numbers and math"),
    "option": ("core", "Core types"),
    "result": ("core", "Core types"),
    "datetime": ("core", "Core types"),
    "fs": ("meta", "Abilities, meta and tooling"),
}
DEFAULT_GROUP = ("meta", "Abilities, meta and tooling")

# The cheapest operation each structure has, used as that structure's own floor.
# A cost is reported against this and never against a counted loop — that is how
# `asyncsurface` came to report "3 314 172/100 floor" for creating a thread.
FLOOR_OP = {
    "seq": "size", "list": "size", "map": "size", "set": "size",
    "deque": "size", "bytes": "size", "iter": "fold",
    "sorted_map": "size", "sorted_set": "size",
    "nonemptylist": "size", "buffer": "size", "str": "size",
}


def modules():
    out = []
    for path in sorted(glob.glob(os.path.join(ROOT, "stdlib/std/**/*.mz"),
                                 recursive=True)):
        rel = path.split("/stdlib/std/")[1]
        name = os.path.basename(rel)[:-3]
        if name.startswith("_"):
            continue
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        ops = sorted(set(re.findall(r"^pub let ([a-z_][a-z0-9_]*)", src, re.M)))
        group = None
        for prefix, gdir, gname in GROUPS:
            if rel.startswith(prefix):
                group = (gdir, gname)
                break
        if group is None:
            group = ROOT_GROUP.get(name, DEFAULT_GROUP)
        out.append({"rel": rel, "name": name, "slug": rel[:-3].replace("/", "-"),
                    "ops": ops, "gdir": group[0], "gname": group[1]})
    return out


def benched():
    out = defaultdict(set)
    for path in glob.glob(os.path.join(ROOT, "bench/cases/*/src/*.mz")):
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        case = path.split("/bench/cases/")[1].split("/")[0]
        for mod, op in re.findall(r"\b([a-z_][a-z0-9_]*)::([a-z_][a-z0-9_]*)", src):
            out[(mod, op)].add(case)
    return out


def measured():
    out = defaultdict(list)
    with open(os.path.join(ROOT, "perf/coverage.tsv"), encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            f = line.split("\t")
            if len(f) < 7:
                continue
            out[(f[0], f[1])].append(
                {"ps": int(f[2]), "n": f[3], "per": f[4], "bench": f[5], "date": f[6]})
    return out


def fmt(n):
    return "{:,}".format(n).replace(",", " ")


def page(mod, ben, mea):
    m, ops = mod["name"], mod["ops"]
    floor = None
    fop = FLOOR_OP.get(m)
    if fop and mea.get((m, fop)):
        floor = mea[(m, fop)][-1]["ps"]

    n_ben = sum(1 for op in ops if ben.get((m, op)))
    n_mea = sum(1 for op in ops if mea.get((m, op)))
    L = ["<!-- GENERATED by scripts/perf-status.py — do not edit.",
         "     Operations from stdlib/std/%s, benched/not from bench/cases/*," % mod["rel"],
         "     numbers from perf/coverage.tsv. -->", "",
         "# %s" % m, "",
         "`std::%s` · %d operations · %d with a bench row · %d with a number%s"
         % (mod["rel"][:-3].replace("/", "::"), len(ops), n_ben, n_mea,
            (" · floor `%s::%s` = %s ps" % (m, fop, fmt(floor))) if floor else "")]
    if not ops:
        L += ["", "No `pub let` operations: this module declares abilities or",
              "types, so its costs live in the impls that satisfy them.", ""]
        return "\n".join(L), (n_ben, n_mea)

    L += ["", "| op | ps/op | elems | ps/elem | vs floor | bench |",
          "|---|---|---|---|---|---|"]
    for op in ops:
        rec, cases = mea.get((m, op)), ben.get((m, op))
        if rec:
            r = rec[-1]
            # PER ELEMENT, because a whole-collection cost against a
            # point-operation floor is the `asyncsurface` mistake in miniature.
            elems = max(1, int(r["per"]))
            per_elem = r["ps"] // elems
            ratio = ("%.1fx" % (per_elem / float(floor))) if floor else "—"
            L.append("| `%s` | %s | %s | %s | %s | %s |"
                     % (op, fmt(r["ps"]), elems if elems > 1 else "",
                        fmt(per_elem) if elems > 1 else "", ratio, r["bench"]))
        elif cases:
            L.append("| `%s` | — | | | | called in %s, no number |"
                     % (op, ", ".join(sorted(cases))))
        else:
            L.append("| `%s` | **UNMEASURED** | | | | |" % op)

    missing = [op for op in ops if not ben.get((m, op))]
    L += ["", "## Unmeasured (%d of %d)" % (len(missing), len(ops)), "",
          (", ".join("`%s`" % o for o in missing) if missing else "none"), ""]
    if mod["gdir"] == "effects":
        L += ["Operations reached through an ABILITY call (`Mut.set {}` rather",
              "than `%s::set`) are NOT counted here — the scan looks for" % m,
              "`module::op`. Some measured mut work is invisible for that reason;",
              "`perf/areas/` carries what is known.", ""]
    return "\n".join(L), (n_ben, n_mea)


AREAS = [
    ("function-calls", "four call spellings at parity, 617-630 ps; one open cliff"),
    ("dispatch-abilities", "an ability call costs a direct call; dictionaries were the cost"),
    ("pattern-matching", "matching is AT the floor; constructing the scrutinee was the cost"),
    ("records", "unboxed at arity 1-4; free when they do not escape"),
    ("variants", "64 to 48 bytes; elided unless something stands before the match"),
    ("fusion", "the canonicalizer: 1503x down to 5.58x, three rows open"),
    ("iter", "the lazy abstraction, 65 ps/elem floor; cursors unmeasured"),
    ("iter-with-effects", "**UNMEASURED**, and `isEffectSafe` is a stub returning true"),
    ("effects", "install 2.73x this branch; `abort` is the largest number left"),
    ("continuation", "`resume` costs what its value costs; MezzeSuspend has no row"),
    ("loom-async-parallel", "JDK-bound: Loom's floor is 5.3 us, Mezze is 1.9x it"),
    ("polyglot", "a crossing is ~149 ns; the dot-chain penalty CLOSED; fine crossing 6.5x"),
    ("module-loading", "dynamic, by hash; ~55 ms per performer DEFERRED and unmeasured"),
    ("comptime-macros", "macros are real and now FREE: 2.2 ms a call to 0.01 ms, 5.8 s fixed to nothing"),
    ("strings", "split 1.88x, interpolation exact; case conversion is vectorisation"),
    ("startup", "820 ms jar, 78 ms native, 19 ms with -O2; 168 ms located, not landed"),
]

NOT_A_SURFACE = [
    ("failure paths", "`Result` propagation, exception cost, `try`/`catch` shapes"),
    ("STM", "transactions, retry, contention"),
    ("IO and filesystem", "seven effects, 34 operations, memfs against real"),
    ("HTTP and db", "Helidon, pooling, JDBC; one locale fix is the only touch"),
    ("arithmetic", "Int is a JVM long and overflow RAISES — the check is unpriced"),
    ("Char and Str", "a `Char` is a one-character `Str` at runtime"),
    ("property / sampling", "`std::property`'s parameterized performers"),
    ("scopes / cancellation", "structured concurrency teardown"),
    ("typecheck and compile time", "no rows on this branch"),
    ("LSP latency", "hover, completion, diagnostics"),
    ("native build", "image build time and binary size"),
]

ORDER = ["datastructures", "core", "numbers", "effects", "codecs", "meta"]


def main():
    mods, ben, mea = modules(), benched(), measured()
    by_group = defaultdict(list)
    labels = {}
    for mod in mods:
        body, (nb, nm) = page(mod, ben, mea)
        d = os.path.join(ROOT, "perf", mod["gdir"])
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, mod["slug"] + ".md"), "w", encoding="utf-8") as fh:
            fh.write(body)
        by_group[mod["gdir"]].append((mod, nb, nm))
        labels[mod["gdir"]] = mod["gname"]

    H = ["<!-- GENERATED by scripts/perf-status.py — do not edit. -->", "",
         "# Perf status", "",
         "Every module of the stdlib, with every operation marked measured or",
         "not. These pages are generated: the operations come from the stdlib's",
         "own `pub let` declarations and the benched/not split from the bench",
         "sources, so they cannot drift from the code. `perf/coverage.tsv` holds",
         "the numbers and is the only file to edit by hand.", "",
         "The area pages under `perf/areas/` are prose. PERF-LOG.md is the",
         "narrative: what was tried, what the cause turned out to be, and what",
         "was reverted.", ""]

    gtot = []
    for gdir in ORDER:
        entries = by_group.get(gdir)
        if not entries:
            continue
        entries.sort(key=lambda r: -len(r[0]["ops"]))
        o = sum(len(m["ops"]) for m, _, _ in entries)
        b = sum(nb for _, nb, _ in entries)
        n = sum(nm for _, _, nm in entries)
        gtot.append((labels[gdir], o, b, n))
        H += ["## %s" % labels[gdir], "",
              "| module | ops | benched | numbered | cover |",
              "|---|---|---|---|---|"]
        for m, nb, nm in entries:
            H.append("| [%s](%s/%s.md) | %d | %d | %d | %s |"
                     % (m["name"], gdir, m["slug"], len(m["ops"]), nb, nm,
                        ("%d%%" % round(100.0 * nb / len(m["ops"]))) if m["ops"] else "—"))
        H += ["| **subtotal** | **%d** | **%d** | **%d** | **%d%%** |"
              % (o, b, n, round(100.0 * b / o) if o else 0), ""]

    to, tb, tn = (sum(g[1] for g in gtot), sum(g[2] for g in gtot),
                  sum(g[3] for g in gtot))
    H += ["## Whole stdlib", "", "| group | ops | benched | numbered | cover |",
          "|---|---|---|---|---|"]
    for label, o, b, n in sorted(gtot, key=lambda g: -g[1]):
        H.append("| %s | %d | %d | %d | %d%% |" % (label, o, b, n, round(100.0 * b / o)))
    H.append("| **TOTAL** | **%d** | **%d** | **%d** | **%d%%** |"
             % (to, tb, tn, round(100.0 * tb / to)))
    H += ["",
          "**Effects reads 0% and is not zero.** An effect operation is invoked",
          "through an ability call (`Console.println {}`, `Mut.set {}`) or a",
          "`perform X in` block, and this scan counts `module::op` only. What IS",
          "known about that surface lives in [areas/effects.md](areas/effects.md)",
          "— install 2.73x, `abort` at 86 050 ps — and 93 `mutation` operations",
          "were measured onto the scalar ABI via `mutabi` and `mut_loop`. The 0%",
          "means no row NAMES an effect operation, which is still a real gap: it",
          "is why `iter`-with-effects and the async primitives have no numbers.",
          ""]

    H += ["", "## Areas", "", "| area | status |", "|---|---|"]
    for f, desc in AREAS:
        H.append("| [%s](areas/%s.md) | %s |" % (f, f, desc))

    H += ["", "## Not yet a surface", "",
          "No page, because no measurement exists. Each is a place where a first",
          "row has historically been worth 50x — `bytes::to_hex` was 56.7x and",
          "was \"found by the type's first benchmark\".", "", "| | |", "|---|---|"]
    for what, desc in NOT_A_SURFACE:
        H.append("| %s | %s |" % (what, desc))

    H += ["", "## Regenerating", "", "```",
          "scripts/perf-status.py            # these pages",
          "scripts/collection-coverage.py    # the collections number alone",
          "```", ""]
    with open(os.path.join(ROOT, "perf/README.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(H))
    print("%d module pages in %d groups" % (len(mods), len(gtot)))
    print("stdlib: %d ops, %d benched (%d%%), %d numbered"
          % (to, tb, round(100.0 * tb / to), tn))


if __name__ == "__main__":
    main()
