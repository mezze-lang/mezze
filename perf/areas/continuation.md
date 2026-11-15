<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Continuations

**Where it stands**: resuming is cheap, aborting is not, and the suspend path has
never been measured on its own.

## Landed

| | |
|---|---|
| `resume { val = X }` costs what X costs | **3.7x**, and it no longer allocates or calls |
| the trampoline's per-call parameter lookup (every continuation crosses it) | **8.73x** |

## Open

- **The abort unwind.** `abort/iter` is 86 050 ps against a 233 ps floor. The
  throw is already stackless, so the cost is the churned perform tier plus the
  unwind itself; it is diffuse rather than a cliff.
- **`MezzeSuspend` / Path E has no bench row at all.** The lazy-until-discharged
  path, `force`, and re-suspension are unmeasured. `UnwrapSuspendedInBlock`
  shows up in the abort profile at 67 samples, which is the only number anyone
  has for it.
- Fibre spawn and the handler-chain snapshot are measured under
  [loom-async-parallel](loom-async-parallel.md), not here.
