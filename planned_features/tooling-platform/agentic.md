# The third primitive: speculation

`thesis.md` says two primitives — read and evaluate. That is right for a human
at a keyboard. It is **not sufficient** once the thing writing the code is an
agent, and the missing piece is fundamental rather than a feature.

## What changes

| | human editing | agent writing |
|---|---|---|
| edit size | one line, one file | hundreds of lines, many files, at once |
| edit shape | incremental, committed | **speculative** — may be abandoned wholesale |
| concurrency | one edit at a time | several variants explored in parallel |
| who reads errors | a person, one at a time, in a viewport | a program, all at once, as data |
| latency that matters | keystroke → highlight | changeset → **complete, actionable** verdict |
| what "done" means | it compiles and looks right | it compiles **and is verified** |

Every assumption in a conventional IDE stack is the left column. Incremental
compilation optimises for one character changing. LSP diagnostics are pushed
per-document because a human is looking at one document. `didChange` debouncing
exists to avoid outrunning a typist.

None of that is wrong, but none of it is what an agent needs.

## The primitive: check a codebase that does not exist yet

The operation an agent performs constantly is:

> Here are twelve modified files. Typecheck them **as a unit**, against the rest
> of the codebase, and tell me everything that is wrong — without changing
> anything. I may keep this, or throw it away and try differently.

Nothing in the tree does this today. That is the gap.

### Why Mezze should be unusually good at it

Content addressing makes speculation nearly free in principle. The store is
immutable blobs keyed by hash with indexes over them — *"Blobs are dumb storage
keyed by content hash. Everything else is an index"* — and unchanged
declarations keep their hashes, so a speculative changeset is a thin overlay
over a shared base, not a copy of the world. Hashes exclude formatting,
comments and metadata, so a reformatting agent produces a changeset that is
provably a no-op. That is the Unison property, and it is exactly what "try this
and maybe discard it" wants.

### Why it does not work today

Two process-global structures assume there is exactly one world:

```kotlin
// ModuleTypeChecker
private val typeCheckingStack = mutableSetOf<String>()          // cycle detection
private val globalCache = mutableMapOf<String, TypedModuleResult>()  // keyed by MODULE PATH
```

Both are synchronised, so they are thread-*safe*. They are not
world-*separable*:

- `globalCache` is keyed by module path. Two speculative variants both
  containing `app::handler` collide — the second reads the first's result.
- `typeCheckingStack` conflates "this **process** is checking X" with "this
  **request** is checking X". Two concurrent checks touching a shared module see
  each other mid-flight, and the structure exists to detect cycles, so
  interference there is a false cycle, not a slow path.
- `ModuleResolver` has the same shape (`moduleCache`, `contentHashCache`,
  `moduleLoadingLocks`), and the LSP already never invalidates the first two.

So the work is not "add a speculate API". It is **making the checker's world
explicit** — a context object those caches hang off, so a check can run against
a base plus an overlay. That is a refactor with a clear shape and no new
concepts, and it unlocks concurrency at the same time, since the reason two
checks cannot run today is precisely that they share one world.

## Diagnostics as data, not prose

`mez check --json` exists and emits `DiagnosticJson(severity, code, message)`.
That is the right foundation and does not go far enough for a consumer that is
a program:

- **Complete, not first-failure.** An agent wants every error in the changeset
  in one round trip. Today a parse error aborts (`aborting due to N previous
  parse errors`) before typechecking says anything, so the agent fixes syntax,
  re-runs, and only then learns about types. Each round trip is a generation.
- **Ranked by cause, not position.** Fifty errors from one bad signature should
  say so. A human infers this; a program cannot.
- **Machine-actionable.** Error codes exist and `--explain` has long-form docs —
  good. What is missing is the structured *fix*: which span, replaced by what.
  The LSP already computes code actions; those are the payload, and they exist
  today only over LSP, not in `--json`.
- **Deterministic.** Same input, same diagnostics, same order — an agent
  re-running a check must not see churn it did not cause.

## Verification is the feedback loop, not a nicety

The correctness documents in `../correctness/` argue *make correctness visible,
not provable* — show a human concrete behaviour and let them spot wrongness.
That framing assumes a human reader.

For an agent it inverts. An agent cannot "glance at a table and notice
something is off"; it needs a **machine-checkable verdict**. This changes the
relative priority of the two tools in that directory:

- **`mez sample`** — human-facing. A table to read.
- **property testing** — agent-facing. Boolean properties that pass or fail.

So the agentic era raises property testing from "sample's programmable
counterpart" to **the primary verification primitive** — and it is currently
blocked, because `ast_gen` needs comptime + reflection, and comptime appears
zero times outside `planned_features/macros/`. Worth knowing that the roadmap
item the agentic case most needs is the one with an unbuilt dependency.

The types help here in a way they do not for humans. Effect rows tell an agent
which of its own generated functions are pure and therefore safely runnable
without handlers — so a verification pass can sample the pure surface
unattended and refuse the rest, rather than guessing.

## What this adds to the core

`thesis.md`'s minimal core gains one item and sharpens another:

1. **A world/context object for the checker.** Caches keyed by
   `(world, module)`, not `module`. Enables speculation *and* concurrency; both
   are blocked by the same thing.
2. **Changeset-level check** — submit N files, get all diagnostics, keep or
   discard. The API over (1).
3. Read, evaluate — as before, now able to target a speculative world.
4. Diagnostics as a versioned data contract, with fixes, ranked and complete.

## The honest sequencing question

The signature index is still the cheapest useful thing and still unblocks
search. But if the agentic case is the one that matters, **the world/context
refactor is the higher-leverage item**, because nothing else can be made
concurrent or speculative until it lands, and retrofitting it after tools depend
on the current single-world behaviour is much worse than doing it first.

It is also the least glamorous item in any of these documents, which is usually
a sign it is the real one.
