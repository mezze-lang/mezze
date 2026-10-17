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

## C1 — PARTLY FIXED: a spawned task's unhandled effect is dropped unless somebody awaits

**The drop is now audible, and `inspect` no longer lies about it. The hole
itself is still open:** the effect still does not happen, and the types still
say nothing. Fixing that is options (1) or (3) below.

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
4. Check a bare top-level expression's residual effects the way a named entry's
   are checked. That is the actual remaining gap, and it is a language decision
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

## C3 — per-language CallTarget caches survive context dispose

**UNPROVEN — structure only.**

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
  own `Instruction.toString` → `Instruction.formatInstruction` → `Formatter`.
  That is the Bytecode DSL's DEBUG PRINTING being reachable for runtime
  compilation. It is generated code, so the fix is a reachability exclusion
  rather than an annotation.
- `EmbeddedExec.executeWithTruffle$lambda$24`.

**Diagnosis order that works** (the one above does not): dump the classpath with
`MEZZE_DEBUG_NATIVE_CP=1`, then group the violation traces by the `com.mezze.`
frame they pass through — `grep -E "Blocklisted method" -A 6 log | grep -oE
"com\.mezze\.[A-Za-z0-9_.$]+" | sort | uniq -c`. That names the culprits in one
pass, and distinguishes yours from the pre-existing set immediately.

---

## C6 — `with_timeout`'s `None` conflates two different answers

`None` means either "the deadline elapsed" or "the promise settled as a failure
or a cancellation". A caller that wants to distinguish them has to `inspect`
the promise itself, which is a second observation of a thing that already
answered. Documented in `async.mz`; splitting it changes the signature, so it
wants a decision rather than a patch.
