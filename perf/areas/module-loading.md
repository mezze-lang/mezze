<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Module loading and imports

**Yes, modules load dynamically** — by content hash, through
`HashModuleLoaderNode`, and with one cost deliberately deferred to first use.
None of it is measured.

## What the loader does

- A module is resolved by content HASH, not by path: `ModuleResolver` walks
  `src/`, `lib/`, then `.deps/mezze/<pkg>-*/src/`. Identity is structure plus
  resolved targets, so the same source under two names is one declaration.
- Import failures are COLLECTED rather than thrown, so a build reports all of
  them at once.
- `defineHandler` populates `MezzeContext.handlersByHash` at load time, storing
  a `PerformerInfo` rather than a built record.
- **The inflation of that record is lazy, and the comment says why: it "defers
  ~55 ms of BDSL compilation per performer to the first `perform Handler in …`
  call."**

## The gap

That ~55 ms is the only number anyone has for this surface, it lives in a code
comment, and it is a LATENCY CLIFF nobody has measured: the first `perform` of
each performer in a program pays it. For a program using ten performers that is
a half-second spread across the first use of each.

Unmeasured entirely:

- time to load one module, and how it scales with module count;
- the first-`perform` stall per performer (the ~55 ms above), and whether it is
  still 55 ms after this branch's work;
- incremental rebuild — what a one-line edit costs;
- `.deps` resolution and `mez sync`;
- the precompiled-stdlib path (`PrecompiledStdlib.preloweredFunctions`) against
  lowering the stdlib from source, which differ enough that a typechecker test
  CANNOT see a fusion defect a `mez build` shows.

Startup as a whole is measured — 820 ms jar, 78 ms native, 19 ms with `-O2` —
see [startup](startup.md). Module loading is a component of that figure which
has never been separated out.
