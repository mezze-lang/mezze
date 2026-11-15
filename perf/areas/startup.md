<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Startup and the native image

**Measured, documented, not optimised** — the numbers exist so that a claim about
startup can be checked.

| | |
|---|---|
| jar, launcher defaults | 820 ms |
| native binary | 78 ms, +-4% stable, 4.1x the jar in total time |
| native with `-O2` | **19 ms**, peak ahead of the jar |

"Why can't we have both" — `--native -O2` already is both.

## Open

- **168 ms of independent class init located and not landed**, for want of a
  quiet box to measure the parallelisation on.
- The jar floor is ~550 ms and CDS is load-bearing: `MEZZE_JAVA_OPTS` REPLACES
  the launcher's defaults, so setting it alone silently drops CDS, the vector
  module and the GC choice. Every measurement on this branch passes the full
  default list explicitly.
- Compile and typecheck time have no rows here at all.
