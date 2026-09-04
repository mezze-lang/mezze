# Async and Parallel Backends via Polyglot

**Audience**: authors building custom `Async` or `Parallel` effect impls that
dispatch to foreign schedulers — Kotlin coroutines, Rayon-shape pools,
Vert.x event loops, CompletableFuture-based executors, etc.

**Prerequisites**: familiarity with Mezze's effect system
(`docs/effect-handlers/01-design-principles.md`), polyglot integration
(`01-overview.md`), and the `promise_from` construction primitive
(`stdlib/std/effects/async.mz`).

---

## The problem

Mezze ships two production Async backends (LoomAsync, OSThreadAsync) and
five Parallel backends (ForkJoinParallel + variants, VirtualParallel +
variants, DeterministicSequential). All of them dispatch task bodies to
threads that the Mezze runtime creates and controls, so effect-handler
chain propagation happens transparently inside the native primitives.

Third-party backends — the ones you'd write to plug in Kotlin coroutines,
a Rayon-shape work-stealer, or a Vert.x event loop — face a different
constraint. They dispatch task bodies to threads that Mezze did **not**
create. On those threads, the parent's effect-handler chain is not
installed. If the task body raises an effect operation (`Exception.throw`,
`Log.log`, `Random.random_int`, etc.), the runtime walks an empty
handler chain and either raises `NoHandlerException` or, worse, silently
no-ops depending on the op's dispatch path.

Two extension tiers exist, matching the two levels of complexity you're
willing to take on.

## The two-tier extension model

### Tier 1 — pure Mezze via `promise_from` + polyglot

Suitable when task bodies are **pure computations** — they compute a
value and return it, without raising Mezze effect ops. Works with zero
Java code. Uses:

- `promise_from { setup = { complete, fail, on_cancel } -> ... }` from
  `std::effects::async` to construct a `Promise 'a` and register
  completion / cancel routing.
- `jvm::call { ... }` (or similar polyglot invocations) to launch on the
  foreign scheduler.

### Tier 2 — Java plugin JAR

Required when task bodies **may raise effect operations**. The plugin
JAR contains a small Java class that:

1. Snapshots the parent's effect-handler chain at spawn site.
2. Launches the task on the foreign scheduler.
3. Inside the foreign task body, installs the captured chain on the
   foreign thread before running the Mezze body.
4. Propagates the result (or unhandled op / exception) back through a
   `MezzePromise`.

Uses the runtime SPI documented in §"Java SPI reference" below.

---

## Tier 1 worked example: Kotlin coroutine backend (pure-computation bodies)

**Mezze.toml** — declare the polyglot dependency:

```toml
[polyglot.jvm]
classpath = [
  "path/to/kotlinx-coroutines-core-1.7.3.jar",
  "path/to/kotlin-stdlib-1.9.0.jar",
]
```

**Mezze source** — the backend as an Async impl:

```mezze
use std::effects::async::{
  Async, promise_from, AsyncError, CancelReason,
  AsyncFailed
}
use std::polyglot::jvm

## Kotlin's Dispatchers.Default (a cached lazy handle at module load).
let default_dispatcher = jvm::static {
  class = "kotlinx.coroutines.Dispatchers",
  method = "getDefault",
}

impl Async for KotlinAsync where {
  initialize_scope = {} -> jvm::call {
    class = "kotlinx.coroutines.CoroutineScopeKt",
    method = "CoroutineScope",
    args = [default_dispatcher],
  },

  finalize_scope = {} -> jvm::call {
    receiver = scope,
    method = "cancel",
    args = [],
  },

  spawn = { f } -> promise_from { setup = { complete, fail, on_cancel } -> do
    ## Launch a coroutine that runs `f`, then invokes complete/fail.
    let job = jvm::call {
      receiver = scope,
      method = "launch",
      args = [{} -> do
        match f {} is
          Ok { val } -> complete { val = val }
          Err { error } -> fail { err = error }
      }],
    }
    ## Route Mezze cancel back to the coroutine job.
    on_cancel { hook = { reason } -> do
      jvm::call { receiver = job, method = "cancel", args = [] }
      {}
    }
  },
}
```

**Usage**:

```mezze
perform KotlinAsync in do
  let p = Async.spawn { f = {} -> compute_something {} }
  await { p = p }
```

This works today (with the `std::polyglot::jvm` module built out) for any
task body `f` whose implementation does not raise effect operations. If
`compute_something` uses `Exception.throw`, `Log.log`, or any other
effect op, the coroutine thread has no handler chain installed and the
op will fail. Use Tier 2 for that case.

### What the naming discipline gives you

The setup record's three capabilities (`complete`, `fail`, `on_cancel`)
are all discovered by their field name. The naming in `promise_from` is
scoped: every constant traces back to a field in the setup record. Same
naming discipline applies to userland impls — use the same field names
Mezze uses (`complete`, `fail`, `on_cancel`) at your setup call sites
because that's what `promise_from` looks for. Custom capability names
would require a fork of `promise_from`.

See `interpreter/src/main/java/com/mezze/interpreter/nodes/builtin/effects/PromiseNatives.java`
for the naming discipline that underlies the setup capability record.

---

## Tier 2 worked example: Kotlin coroutine backend (effect-carrying bodies)

When task bodies may raise effect operations, the foreign task must
install the parent's handler chain before running the body. This
requires a small Java plugin JAR that uses the Mezze runtime SPI.

### Java side — `KotlinAsyncNatives.java`

```java
package com.example.mezze.kotlin;

import com.mezze.interpreter.nodes.builtin.effects.AsyncNatives.MezzePromise;
import com.mezze.interpreter.nodes.builtin.effects.AsyncNatives.PromiseTracker;
import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import kotlinx.coroutines.*;

public final class KotlinAsyncNatives {

    /** Scope handle: pairs a CoroutineScope with a PromiseTracker. */
    public static final class KotlinScope {
        final CoroutineScope scope;
        final PromiseTracker tracker = new PromiseTracker();
        KotlinScope() {
            this.scope = CoroutineScopeKt.CoroutineScope(
                SupervisorKt.SupervisorJob(null)
                    .plus(Dispatchers.getDefault())
            );
        }
    }

    @TruffleBoundary
    public static KotlinScope kotlinInitScope(MezzeRecord args) {
        return new KotlinScope();
    }

    @TruffleBoundary
    public static Object kotlinFinalizeScope(MezzeRecord args) {
        KotlinScope ks = (KotlinScope) args.getField("tracker");
        ks.tracker.clear();
        JobKt.cancel(ks.scope.getCoroutineContext(), null);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static MezzePromise kotlinSpawn(
        MezzeRecord args,
        IndirectCallNode callNode,
        MezzeNode node
    ) {
        MezzeFunction f = (MezzeFunction) args.getField("f");
        KotlinScope ks = (KotlinScope) args.getField("tracker");

        // Snapshot the parent's effect handler chain HERE, on the parent
        // thread — before any coroutine has run.
        HandlerChainEntry[] handlerChain = EffectFiber.snapshotHandlerChainEntries();

        MezzePromise promise = new MezzePromise(null, null);

        // Launch on the Kotlin coroutine scope. The runner installs the
        // captured chain on the coroutine's thread before running the
        // Mezze body.
        Job job = BuildersKt.launch(
            ks.scope,
            EmptyCoroutineContext.INSTANCE,
            CoroutineStart.DEFAULT,
            (scope, continuation) -> {
                // runPromiseTask is the shared runtime SPI: it installs
                // the chain, runs the body, catches any unhandled op,
                // routes errors, marks the promise done/failed. This is
                // the primitive that makes effect-carrying bodies work.
                AsyncNatives.runPromiseTask(handlerChain, promise, () -> {
                    Object[] closure = f.getClosureValues();
                    Object[] fullArgs = new Object[closure.length + 1];
                    System.arraycopy(closure, 0, fullArgs, 0, closure.length);
                    fullArgs[closure.length] = MezzeRecord.empty();
                    return callNode.call(f.getCallTarget(), fullArgs);
                });
                return Unit.INSTANCE;
            }
        );

        // Route Mezze cancel to the coroutine's job.
        promise.setCancelHook(reason -> job.cancel(
            new CancellationException("Mezze cancel: " + reason)
        ));

        ks.tracker.track(promise);
        return promise;
    }
}
```

### Native registration

The plugin JAR needs to register its natives with `NativeDispatch`. This
extension mechanism is described in `06-cli-changes.md` under "Plugin
JAR discovery." For a manual registration during development:

```java
NativeDispatch.HANDLERS.put("__native_async_init_scope_kotlin",
    (r, ctx) -> KotlinAsyncNatives.kotlinInitScope(r));
NativeDispatch.HANDLERS.put("__native_async_finalize_scope_kotlin",
    (r, ctx) -> KotlinAsyncNatives.kotlinFinalizeScope(r));
NativeDispatch.HANDLERS_WITH_NODE.put("__native_async_spawn_kotlin",
    (r, ctx, node) -> KotlinAsyncNatives.kotlinSpawn(r, callNode(ctx), node));
```

### Mezze side

```mezze
use std::effects::async::{ Async }

impl Async for KotlinAsync where {
  initialize_scope = {} -> __native_async_init_scope_kotlin {},
  finalize_scope   = {} -> __native_async_finalize_scope_kotlin { tracker = scope },
  spawn = { f } -> __native_async_spawn_kotlin { f = f, tracker = scope },
}
```

Three lines of Mezze. The heavy lifting is in Java, but Mezze users
compose freely with the resulting `KotlinAsync` impl.

---

## Java SPI reference

Available to plugin JAR authors:

| SPI point | Signature | Purpose |
|---|---|---|
| `EffectFiber.snapshotHandlerChainEntries()` | `HandlerChainEntry[]` | Capture the current thread's effect-handler chain. |
| `EffectFiber.installHandlerChainEntries(chain)` | `void` | Install a previously-snapshotted chain on the current thread. |
| `EffectFiber.clearHandlerChain()` | `void` | Clear the chain (call in a finally block after task body). |
| `AsyncNatives.runPromiseTask(chain, promise, body)` | `void` | Composed pattern: install chain, run body, catch unhandled ops, route errors, mark promise done. Preferred over calling install/clear manually. |
| `AsyncNatives.MezzePromise` | class | The Promise type all backends produce. Has `.complete(val)`, `.fail(err)`, `.cancel(reason)`, `.setCancelHook(consumer)`. |
| `AsyncNatives.PromiseTracker` | class | Tracks spawned promises within a scope. Backends use one per scope; `finalize_scope` drains it. |

The single-outcome CAS design (see #79) means `MezzePromise.complete/fail/cancel`
are winner-safe by construction — you don't need to guard against races
in your plugin. Losing writers return `false` with no side effect.

## Cancel semantics for third-party backends

`MezzePromise.setCancelHook(reason -> ...)` receives the `CancelReason`
variant that Mezze code passed to `Async.cancel`. Your hook fires when
the cancel CAS wins. Use the reason to distinguish cases:

- `UserRequested { msg }` — external application-level cancel.
- `Timeout { deadline_ms }` — a `with_timeout` fired.
- `ScopeTeardown` — the enclosing scope is being finalized.
- `SiblingFailed { at }` — another task in the same scope crashed.
- `LostRace` — a `race` decided against this promise.
- `ExternalInterrupt` — thread interrupt (SIGINT, kill, etc).
- `Custom { tag, detail }` — user-defined.

Hook errors are swallowed at the cancel site; your hook must not depend
on succeeding for cancel semantics to hold. Idempotent operations
(`job.cancel()`, `future.cancel(true)`, etc.) are the right shape.

## Forward reference: the future stdlib surface

When `std::interop::foreign` (see `03-stdlib-foreign.md`) is built out,
a pure-Mezze `capture_chain` primitive may land at
`std::interop::async::capture_chain` with the signature:

```mezze
pub let capture_chain
  : { f: {} -> 'a <...> } -> {} -> 'a <...>
```

That would let Tier 2 be entirely pure Mezze: register the returned
runner as the foreign scheduler's task body via polyglot. No Java plugin
needed.

Today, it stays Java-only because:

1. `std::interop` is designed but not implemented in stdlib.
2. Exposing chain snapshot/install as a Mezze primitive requires
   deciding a naming/module home that survives the eventual `std::interop`
   rollout.
3. Java-side SPI covers 100% of current userland backend use cases when
   combined with a small plugin JAR.

## Cross-references

- `01-overview.md` — polyglot architecture and layered wrapper libraries.
- `03-stdlib-foreign.md` — designed Mezze surface for foreign values.
- `05-runtime-implementation.md` — runtime side of polyglot dispatch.
- `06-cli-changes.md` — plugin JAR discovery and Mezze.toml classpath.
- `docs/effect-handlers/01-design-principles.md` — effect handler model
  that this doc extends across thread boundaries.
- `stdlib/std/effects/AUDIT.md` — effect audit; this doc closes the
  Async/Parallel userland extension gap.
- `stdlib/std/effects/async.mz` — `promise_from`, `promise_of`,
  `promise_failed`, `on_cancel`. #77, #80.
- `interpreter/src/main/java/com/mezze/interpreter/nodes/builtin/effects/PromiseNatives.java`
  — the naming discipline underlying setup capability records.
- `interpreter/src/main/java/com/mezze/interpreter/nodes/builtin/effects/AsyncNatives.java`
  — `MezzePromise`, `runPromiseTask`, `PromiseTracker`.
