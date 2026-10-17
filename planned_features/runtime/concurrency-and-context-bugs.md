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

Nothing in the types warns: `Async.spawn`'s callback row is `{} -> 'a <...>`,
absorbed at the spawn, so

```
perform LoomAsync in do            # no Time performer anywhere
  let p = Async.spawn { f = {} -> sleep { ms = 1000 } }
  …                                 # never awaited
```

typechecks, runs, and sleeps for nothing. This is exactly how the `with_timeout`
flake was built, and fixing `inspect` only stopped `is_done` from lying about it.

**Evidence:** measured. A probe over 400 runs printed
`sleeper=Completed { val = {} }` in 295 of them for a task whose entire body is
`sleep { ms = 1000 }`. Only the await paths read `getUnhandledOpCall`
(`AsyncNatives` lines ~1293, ~1335, ~1499) — grep it.

**Options, none chosen:**
1. Fail the promise instead of completing it, and let `await` reconstruct the
   op from the failure. Changes what `await` returns for the propagation case,
   which is a real feature (a spawned task CAN perform the parent's effects).
2. Keep the stash, and complain when a context disposes with ops nobody
   collected. Catches the leak without touching propagation semantics; needs a
   per-context register of suspended promises.
3. Stop absorbing at the spawn, so the row surfaces on the caller. Most honest,
   biggest blast radius — `effect_absorption` is load-bearing elsewhere.

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

## C4 — `pub use` of an ability does not carry its blanket impl

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

**Fix shape:** a module's `pub use`d impls should travel with it the way its own
do. Watch coherence — this widens what an import brings into scope.

---

## C5 — `just native-test` is red, and not because of anything web

Running `cli/build/native/nativeCompile/mez` from inside the repo resolves the
whole gradle runtime set onto the user-app native classpath: the build log shows
9 user-specific features including python, wasm, icu and jline, against 5 for an
installed mez. native-image then reports ~270 Truffle compilation-blocklist
violations before reaching anything else.

**Evidence:** reproduced twice, with the no-server fixture as a control (253
violations, zero `io.helidon` in any trace). The same fixtures build clean under
`~/.mezze/bin/mez` after `./gradlew :cli:installNative`, which is why
`native-server-test` uses that deliberately.

**Diagnosis order that worked:** compare `N user-specific feature(s)` between a
failing and a known-good build log FIRST. 9-vs-5 named the cause before any flag
was touched.

---

## C6 — `with_timeout`'s `None` conflates two different answers

`None` means either "the deadline elapsed" or "the promise settled as a failure
or a cancellation". A caller that wants to distinguish them has to `inspect`
the promise itself, which is a second observation of a thing that already
answered. Documented in `async.mz`; splitting it changes the signature, so it
wants a decision rather than a patch.
