# Open bugs — concurrency, context isolation, and effect propagation

Opened 2026-10-17 while chasing a `with_timeout` flake. That chase produced
three landed fixes and six findings that are still open; this file is the six.

**Read the case, never the prose — this file included.** Every entry below says
how to reproduce it and what evidence backs it. Two of them are marked
UNPROVEN on purpose: the structure is wrong but nobody has produced a failing
run. Run the repro before acting on any of these.

Landed already, for context (do not re-investigate):
- `inspect` reported `Completed { val = {} }` for a task suspended on an
  unhandled op — `with_timeout` believed it. Fixed; flake went 3/400 → 0/400.
- `with_timeout` / `scope_join_until` built their deadline from a spawned
  sleeper task; they use a deadline-aware `park_until` now.
- Static state crossing Contexts: `PromiseNatives`' three capability
  CallTargets (a hard crash), `SEMAPHORE_CACHE` (a process-global
  `max_concurrent`), the HTTP server's sockets and worker threads, the
  in-memory filesystem, the HTTP mock table, the draw log, inner polyglot
  contexts, seeded generators, script cursors.

Gates for every change here: `:typechecker:test` + `:ast:test`, then
`:interpreter:test` + `:langservice:test` + `:cli:test` + `:parser:test`,
SEPARATELY, `--no-daemon`.

---

## C1 — FIXED: a dropped effect now FAILS the run it happened in

**The effect still does not happen — that part is by design and cannot change
without breaking propagation (see option (1) below). What is fixed is that a
program which drops one no longer answers as if it had not.**

Option (1) as written — fail the promise instead of completing it — is NOT
available: every `MezzePromise` transition CASes from `PENDING_SENTINEL`, so a
COMPLETED promise cannot be moved to FAILED without rebuilding the outcome state
machine, and completing-with-the-op-stashed is exactly how `await` propagates a
spawned task's effect to its parent. That is a feature, not the bug.

**Where the drop actually becomes observable was measured, not guessed.** A probe
counted suspended children at every seam across the whole interpreter suite:

- at SCOPE close (`PromiseTracker.clear`): **1 hit in 6841 tests.** So that is
  not where drops happen — the common shape is a bare `Async.spawn` under a
  `perform`, which no scope tracks. Failing the scope would have been the wrong
  design, and the probe said so before any semantics changed.
- at CONTEXT close (`SuspendedOps`): also 1, the test that asserts the report.

So nothing in the suite depended on a silent drop, which made the fix safe:
`SuspendedOps.close` now LATCHES its report (per thread — a context is closed by
the thread that owns it, so the writer and the embedder's reader are the same
thread and two tests sharing an Engine cannot see each other's drops), and
`MezzeTestHelper.run` raises on it. `runAllowingDroppedEffects` is the opt-out,
and `DroppedEffectReportTest` is its only caller.

**That matters because the harness was the whole remaining hole.** The types
already reject the shape everywhere that ships — a named entry performing an
undischarged effect is refused, and `mez build`/`dev` take their entry from
`Mezze.toml` (option 4, resolved). A bare top-level expression under a test
harness was the one place left, and it is how the `with_timeout` flake was built.

**It found one on the first run.** `TransitiveScopeCancelDeepTest` carries a
comment promising "5s guard so a broken cancel wave doesn't hang" — and ran
`perform LoomAsync in main {}` with `SystemTime` imported but never performed.
Its guard's `sleep` had no handler, so the sleeper suspended, its effect was
dropped, and `timed_out` could never flip: the guard did not exist. A broken
cancel wave would have hung to the 15s `@Timeout` rather than reporting
"escaped". Same shape as the flake — a sleeper that never slept. Fixed by
performing `SystemTime`.

6841 tests, 1 failure, and the failure was a real defect.

**A correction to an earlier claim.** The commit that landed the `inspect` fix
said it took the flake 3/400 → 0/400. That measurement was taken with a check
that treated BOTH a stashed `OpCall` and a `MezzeSuspend` result as unfinished.
It was then narrowed to the op alone — because eight `PromiseFromTest` tests
appeared to break, which turned out to be the `PromiseNatives` static CallTarget
leak and nothing to do with this — and the narrowed version does NOT catch the
case the measurement was about. Measured afterwards: a spawned `sleep` with no
`Time` performer completes with `resultClass=MezzeSuspend` and `op=null`. The
broader check is restored, and with the CallTarget leak fixed the whole class
subset passes (610 tests). The original flake was already gone either way,
because `with_timeout` no longer spawns a sleeper at all.

A task that performs an operation no handler can discharge does not fail. It is
marked COMPLETED with the op stashed on the promise
(`runPromiseTaskDirect` → `setUnhandledOpCall` + `promise.complete(null)`), and
the op is re-dispatched by `await` — by `await` and nothing else. Nobody awaits,
nobody ever performs it. The effect is silently gone.

**A SECOND correction: the types are not silent, and "absorbed at the spawn" was
wrong.** The `<...>` on the callback's arrow carries its effects to the spawn
site already. A function annotated `<Async>` alone whose body spawns `sleep`
is a T0210 naming `<Time>`, and a named entry is rejected with "Entry 'main'
performs Time". Both were verified — AND verified to behave identically with
`spawn` declared `-> Promise 'a <Async, ...>`, which is why that change was
made, measured to be a no-op, and reverted rather than shipped on a premise
that did not hold.

What is genuinely unchecked is a **bare top-level expression**: no annotation to
contradict, no entry check to answer to. So

```
perform LoomAsync in do            # a top-level expression, no entry
  let p = Async.spawn { f = {} -> sleep { ms = 1000 } }
  7                                 # never awaited
```

typechecks, runs, and sleeps for nothing. That is the test harness's own shape
and `mez exec`-style snippets; `mez dev` and `mez build` always have an entry
and are covered. It is how the `with_timeout` flake was built.

**Evidence:** measured. A probe over 400 runs printed
`sleeper=Completed { val = {} }` in 295 of them for a task whose entire body is
`sleep { ms = 1000 }`. Only the await paths read `getUnhandledOpCall`
(`AsyncNatives` lines ~1293, ~1335, ~1499) — grep it.

**Options:**
1. Fail the promise instead of completing it, and let `await` reconstruct the
   op from the failure. Changes what `await` returns for the propagation case,
   which is a real feature (a spawned task CAN perform the parent's effects).
2. Keep the stash, and complain when a context disposes with ops nobody
   collected. Catches the drop without touching propagation semantics.
3. ~~Stop absorbing at the spawn~~ — there is no absorption to stop. Tried,
   measured as a no-op, reverted.
4. ~~Check a bare top-level expression's residual effects the way a named entry's
   are checked~~ — **RESOLVED 2026-10-17, no code needed. Owner's decision,
   verified.**

   A bare top-level expression is not a program shape `mez build` or `mez dev`
   accept AT ALL. Both take their entry from `Mezze.toml` (`[[app]] entry =
   "main"`), and a bare expression is refused before effects are even considered:

   ```
   error[V0710]: A bare expression isn't valid at the top level of a program —
   the top level takes declarations (`let`, `type`, `effect`, `ability`, `impl`,
   `use`). This expression has nothing to run it and won't execute. Wrap it in a
   function and use that as the entry point, e.g. `let main = {} -> ...`.
     = hint: move it into `let main = {} -> ...` (or another function)
   ```

   So the unchecked residual row exists only where bare expressions are allowed:
   the TEST HARNESS (`MezzeTestHelper.run`), and a future REPL. There is nothing
   to tighten in the shipping paths. When a REPL lands, what it does with an
   undischarged row is a REPL policy decision, to be made then.

   Measured, not assumed: `mez init` + an `app.mz` holding exactly the sleeping
   snippet above, built with `mez build` — rejected as shown.
   about what a top-level expression means, not an async bug.

(2) is done: `AsyncNatives.SuspendedOps` counts suspended completions per
context against the forces that claim them, and reports the difference on
stderr when the context closes, naming the effects by hash. It does not
foreclose (1) or (3). `DroppedEffectReportTest` covers both directions — a
spawn nobody awaits is reported, an awaited one is not.

Why by hash and not by name: that is what a `MezzeSuspend` carries, and a name
would need a hash→effect lookup the runtime does not have at that point.

---

## C2 — FIXED: `CURRENT_SCOPE` kept a guest scope on the thread after dispose

`AsyncNatives.resetPerThreadScope()` is called from `disposeContext` beside
`resetPerThreadStmState`.

Narrower than first written, and the first version of this entry overstated it:
every `set` of `CURRENT_SCOPE` IS restored in a `finally`, so the ordinary
paths — a body that raises included — already balance. What the reset covers is
the path that does not run finally blocks: a thread killed or unwound by an
Error, where the ThreadLocal pins a `MezzeScope` and its child promises on a
thread that outlives the context.

Not partial, as a first pass assumed. The hook is `disposeThread`, which
Truffle calls for EVERY thread that entered the context, each on that thread —
so every slot is cleared. A `disposeContext` hook could only have cleared the
closing thread's, which is what made the limitation look inherent.

---

## C3 — FIXED as a leak: user CallTargets no longer survive context dispose

`disposeContext` clears `importedCallTargetCache` and `userStubCache`. Those
hold USER CallTargets, compiled against the IR of the Context being disposed,
and keeping them retains that whole AST. Keyed by content hash, so growth is
bounded by distinct FUNCTIONS rather than runs — which bounds nothing on a
long-lived Engine whose code changes: a `mez dev` session reloading on each save
mints fresh hashes every time and nothing dropped the old ones.

`stdlibCallTargetCache` deliberately stays. Those come from the precompiled
snapshot, are identical in every Context, and are the expensive ones to rebuild;
dropping them would make every new Context pay stdlib warm-up to fix a leak they
are not part of.

The CORRECTNESS half below is a separate claim and remains unproven. Note the
suite already works around it with `mezze.test.isolation`, and production runs
one Context per Engine, so there is nothing to cross there.

### The correctness claim — CLOSED 2026-10-17, resolved by construction

The claim's premise is no longer true. It said those caches are "cleared only by
`invalidateDebugShapeCaches` … never in `disposeContext`". `disposeContext` now
clears `importedCallTargetCache` and `userStubCache` — the two that held USER
CallTargets — as part of the leak fix above, so a target compiled against a dead
Context's IR cannot be handed to the next Context on that Engine.

The one survivor, `stdlibCallTargetCache`, cannot carry anything
Context-specific: it is populated ONLY from
`StdlibRuntimeData.getPreloweredFunctions()` and from snapshot IR bytes, and the
eager-compile path explicitly skips `userHashes`
(`MezzeLanguage` ~line 959). Stdlib targets are identical in every Context,
which is why they are kept.

No failing case was ever produced for this — which is what the ticket demanded
before any fix — and the single failure that looked like it (8 `PromiseFromTest`
failures, 2026-10-17) was the `PromiseNatives` static CallTargets, fixed
separately.

**What would reopen it:** a USER CallTarget reachable from `MezzeLanguage` after
`disposeContext`, or anything putting a user hash into `stdlibCallTargetCache`.

### The original claim, for the record — structure only

`stdlibCallTargetCache`, `importedCallTargetCache` and `userStubCache` live on
`MezzeLanguage` (one per sharing layer) and are cleared only by
`invalidateDebugShapeCaches` on a debug-epoch change — never in
`disposeContext`. So a CallTarget compiled against one Context's IR stays in the
cache and can be handed to the next Context on the same Engine. That is the
shape of the shared-engine stub bug (`project_shared_engine_test_isolation_flake`,
"LazyStubRootNode … cannot inflate: no MezzeFunction registered for hash").

**What is NOT evidence for it:** the 8 `PromiseFromTest` failures chased on
2026-10-17 looked exactly like this and were something else entirely — the
`PromiseNatives` static CallTargets, now fixed. After that fix the whole class
subset passes. So the structure is suspicious and the one failure anybody had is
accounted for elsewhere.

**Before fixing:** produce a failing case. Clearing those caches per dispose
costs stdlib warm-up on every Context, which the test suite pays 1500 times, so
do not pay it for a hypothesis.

---

## C4 — FIXED: `pub use` of an ability did not carry its blanket impl

A consumer that does not NAME the re-exported item gets the binding and not the
instance. `http.mz` has `pub use std::codec::query::{ ToQuery }`, and

```
use std::effects::http::{ request }
request { url = "…" }.with_query { params = { q = "x" } }
```

fails with "`ToQuery` isn't imported here" until the consumer writes
`use std::effects::http::{ request, ToQuery }`. `ImportResolver`'s re-export
branch loops `for (item in path.items)` and only carries
`abilityImplsByHash` for items the consumer listed, whereas a DIRECT import of
anything from a module carries that module's own impls wholesale.

**Evidence:** reproduced 2026-10-17; the `pub use` was added for exactly this
and did not help. The error now suggests the import, which is a workaround.

**Fixed:** `ImportResolver.reexportedAbilityImpls` walks a module's `pub use`
declarations, transitively, and merges the source modules' `abilityImplsByHash`
alongside the module's own. A module that writes `pub use` is asking for its
source's surface to be part of its own, and the instances are part of that
surface.

Callers stopped working around it: `HttpStageDTest` imports `request` alone
again, and the router tests no longer import `FromQuery`.

It does widen what an import brings into scope, which is the thing to watch —
but only for modules whose author opted in by writing `pub use`.

**The shape this does NOT cover, and the hint is for:** a module that plain-
`use`s an ability and exposes a function constrained on it. Its consumers get
the function and not the instance, because nothing was re-exported. That is
`from_json_result`'s shape, and `UnimportedAbilityHintTest` now builds it out of
three modules rather than relying on the `ToQuery` case this fix removed.

---

## C5 — `just native-test` is red. Partly my fault, now partly fixed.

**Two corrections to the first version of this entry, both of which I got
wrong by reasoning from logs instead of measuring.**

1. "Running the in-tree binary resolves provider jars onto the classpath" —
   NO. The two binaries are byte-identical (`cmp` says so; `installNative`
   copies one to the other), and dumping the actual classpath with the new
   `MEZZE_DEBUG_NATIVE_CP=1` shows no python/wasm/js/icu/jline jar on it in
   either case. The 9-vs-5 feature count I built the theory on was a red
   herring, and a build with 5 features still failed with 372 violations.
2. "Not because of anything web" — it WAS, mostly. Four methods added on
   2026-10-17 were reachable for runtime compilation and dragged ~300
   blocklisted JDK methods in with them: `AsyncNatives.suspendedOps`,
   `AsyncNatives.effectIds`, `HttpServerNatives.dispatch` and
   `HttpServerNatives.workerLoop`, plus the per-context accessors added in the
   leak sweep (`RandomNatives.state` surfaced through the native-smoke
   fixture's `SeededRandom`). The control that misled me — the no-server
   fixture failing with zero `io.helidon` in any trace — proved nothing: the
   native handler table makes `workerLoop` reachable whether the app serves or
   not.

   All are `@TruffleBoundary` now, and `MezzeContext.nativeState` carries the
   rule: call it only from a boundary, because `ConcurrentHashMap.get` is
   blocklisted. `just native-server-test` builds and runs again.

**What is left is genuinely pre-existing, and now actually diagnosed.** 302
violations remain for the native-smoke fixture, and every trace goes through
one of two places:

- `MezzeBytecodeRootNodeGen$InstructionImpl.getArguments`, reached from Truffle's
  own `Instruction.toString` → `formatInstruction` → `Formatter.format` →
  `String.format` → **`Preconditions.outOfBoundsMessage`**. Read that chain
  backwards and the real cause is ours: runtime-compiled code does an index
  operation whose bounds-failure path calls `String.format`, and once `Formatter`
  is reachable the analysis must assume the `%s` could be any object with a
  `toString` — the Bytecode DSL's `Instruction` among them. The Bytecode DSL is
  not the problem and annotating generated code is not the fix.
- `EmbeddedExec.executeWithTruffle$lambda$24`.

**There is no shortcut.** GraalVM 25.0.3 has no `-H:` option to ignore or
whitelist blocklist violations — checked `--expert-options-all`; the only
Truffle options are host-inlining and trusted-cast knobs. The lever is
reachability and nothing else.

**So the work is:** keep `Formatter`/`String.format` off the runtime-compiled
surface by routing bounds failures through a `@TruffleBoundary` helper instead of
letting `Preconditions` format them inline. That is per-index-site interpreter
work — the "interpreter-level work tightens dispatch" that `NativeBuildPath`'s
own comments on #184/#401 anticipate — and it is too large to bolt onto a
session about something else. `-H:+PrintRuntimeCompilationCallTree` is the tool
for finding the sites.

**Diagnosis order that works** (the one above does not): dump the classpath with
`MEZZE_DEBUG_NATIVE_CP=1`, then group the violation traces by the `com.mezze.`
frame they pass through — `grep -E "Blocklisted method" -A 6 log | grep -oE
"com\.mezze\.[A-Za-z0-9_.$]+" | sort | uniq -c`. That names the culprits in one
pass, and distinguishes yours from the pre-existing set immediately.

---

## C6 — FIXED: `with_timeout` answers a `Result`, and the three outcomes are distinct

**This entry was STALE — the code had already been changed and the doc had not.**
`with_timeout` is `{ ms: Int, p: Promise 'a } -> Result 'a AsyncError <Async, ...>`:
the deadline is `AsyncTimedOut { ms }` and a promise that failed or was cancelled
surfaces its OWN error. `Option` was what collapsed the two into one `None` and
sent the caller back to `inspect` — a second observation of a promise that had
already answered.

**What was genuinely missing was the test.** Only the success case was covered,
and the entire point of the change is the two unhappy cases being DIFFERENT, so
a test asserting merely "it was an `Err`" would pass with the distinction
collapsed again. `WithTimeoutDistinguishesOutcomesTest` names each of the four
outcomes separately:

- a value in time -> `Ok`
- a deadline elapsing -> `AsyncTimedOut { ms }`, carrying the ms it was given,
  because a caller that retries needs to know which deadline it was
- a cancellation before the deadline -> `AsyncCancelled { reason }`, carrying
  the caller's own reason, NOT a timeout
- a failure before the deadline -> the promise's own `AsyncFailed { msg }`

Verified by perturbation: collapsing the `Failed` arm back into
`AsyncTimedOut` breaks exactly the cancelled and failed cases and nothing else.
