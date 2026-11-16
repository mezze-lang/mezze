# Continuations and swappable async

**Status 2026-11-16: the design is settled and the mechanisms are measured.
Nothing here waits on a number.** What remains is implementation, listed under
*Phases*. The measurement record that produced this design is kept verbatim
below, including the framings that were tried and rejected.

The author's requirement, which drove every choice: *"i really want userland
async for sure"*, generators optional, no new keywords, no required annotations,
and **no runtime errors — silent behaviour is fine**.

---

## The design

**The runtime owns suspension and task state. A performer owns scheduling policy.**
Userland never holds a continuation.

### The whole surface

    cont.park  { on: ParkOn, set: WaitSet } -> ParkOutcome
    cont.abort { val = v }                  -> 'b            ## DIVERGENT

`cont` is a runtime-bound name in a PERFORMER OP BODY, exactly as `scope` is —
referencing it anywhere else is V0702, which is already `scope`'s behaviour
(verified). **It is not a parameter and not a receiver**: a Mezze parameter
record describes the CALL, and `Async.await { p = promise }` supplies no
continuation.

Note the kind of impl this is. The three are not the same, and conflating them
cost this document a round:

| impl kind | `it` | how it reaches its own data |
|---|---|---|
| **effect** (performer) — `impl Async for LoomAsync` | **none exists** | bare performer params and `scope` — and now `cont` |
| **ability** — `impl Eq for Int` | the receiver; type from the ability's op | `it` |
| **inherent** — `impl Box where` | the receiver; nothing declares it | `it`, which must be declared (BUG 47) |

`cont` belongs to the first row, which has no `it` at all.

    type ParkOn = |
      OnPromises   { ids: [PromiseId] },           ## registered — O(1) wake
      OnPredicates { fs: [{} -> Bool <...>] },     ## polled — the general escape hatch
    |

    type ParkOutcome = |
      ParkReady { which: Int },
      ParkCancelled { reason: CancelReason },
    |

    newtype PromiseId = Int                        ## `type PromiseId = Int` is V0713
    newtype TaskId = Int
    newtype WaitSetId = Int

The registered/polled split is a PERFORMANCE distinction, not a typing one: an
event loop wants an O(1) wake by registration, while an opaque predicate forces
the machine to poll, which is what the blocking native already does today.

**No deadline constructor.** A deadline would have the RUNTIME read a clock,
bypassing the `Time` effect and silently breaking the deterministic test
scheduler this design exists to enable. Deadlines are predicates that perform
`Time`, and effectful predicates already ship — today's `park` takes
`{ predicate: {} -> Bool <...> }`.

### Implicit resume

A performer op body that falls off the end with a value RESUMES. That is
measured, and it is also the fast path: every `resume` mention in the stdlib is
a COMMENT saying the bare value is used instead because it is ~2.4x cheaper, and
there are ZERO bare `resume {` calls in stdlib code. So the 99% handler is
untouched:

    impl FileRead for LocalFs where {
      read_text = { path } -> __native_fs_read_text { path = path },
    }

No `cont`, no suspension, today's direct call, today's cost.

### `drive` — the third impl-only field

Control returns to the PERFORM BOUNDARY when a task parks, not to user code:
`perform EventLoop {} in body {}` must not evaluate to "suspended", or every
`perform` in the language would have to return a variant. So the performer
supplies a hook the runtime calls while the body is parked.

`initialize_scope` and `finalize_scope` are the precedent — `async.mz` calls them
*"runtime plumbing — not declared in the ability but expected by the effect
driver"*. `drive` joins them.

**It is a SELECTOR, not a bookkeeping loop:**

    drive = { runnable } -> runnable.first {}                 ## FIFO, fair
    drive = { runnable } -> runnable.last {}                  ## LIFO, cache-friendly
    drive = { runnable } -> runnable.min_by { f = { t } -> t.deadline }
    drive = { runnable } -> runnable.at { i = scope.rng.next_below { … } }  ## deterministic replay

**`runnable` MUST be an opaque handle with O(1) `first`/`last`, never a
materialized list** — otherwise FIFO costs O(N) per switch and O(N²) per round,
which dwarfs every number in the measurement record below.

### A scheduler, whole

    impl Async for EventLoop where {
      initialize_scope = {} -> { … },
      finalize_scope   = {} -> … ,

      drive = { runnable } -> runnable.first {},

      await = { p } -> match p.state {} is
        PromiseSettled { val } -> val                          ## implicit resume
        PromisePending {} -> match cont.park { on = OnPromises { ids = [p.id {}] },
                                               set = WaitDefault {} } is
          ParkReady {} -> p.settled_val {}
          ParkCancelled { reason } -> Err { error = AsyncCancelled { reason = reason } },
    }

No `waiting` table, no resumer closures, no `finalize_scope` discipline to
remember.

### The machine

Per task, owned by the runtime:

    Running          — executing now
    Parked(on, e)    — suspended on a condition, epoch e
    Runnable(v)      — condition satisfied, carrying what to deliver
    Done(v) | Cancelled(r)

Per scope: `Active -> Finalizing -> Closed`.

**Epochs are load-bearing.** Each park bumps one, so a wake aimed at a stale
epoch is a defined no-op AND COUNTABLE — the one case where silence would
otherwise hide a bug.

**Visible in Mezze:** `ParkOn`, `ParkOutcome`, `WaitSet`, and an opaque `Task`
handle. **Internal:** task records, the ready set, promise->task indices, epochs
— so they can be laid out properly (intrusive records, an array ring, the waiter
stored ON the promise rather than in a side table).

### What this costs and buys

Both earlier holes are STRUCTURALLY ABSENT rather than patched:

| | |
|---|---|
| the untypeable `waiting` table | gone — `Cont (Result 'a AsyncError)` differs per task, but userland holds none |
| `resume`'s return type | gone — there is no userland `resume`; the only remaining method is divergent, where a free `'b` is sound |
| resume twice | **impossible**, not a silenced no-op |
| stale resumer dropped | impossible — no resumers in userland |
| resume after `finalize_scope` | impossible — the machine cancels parked tasks before finalize runs |
| cleanup never running | the machine cancels; a performer cannot forget |

**The only reported error is deadlock** — no runnable task, body parked, nothing
pending. A genuine program fault, and it must be loud.

### What it gives up

| | |
|---|---|
| generators | **nothing lost** — `iter` already is the lazy structure |
| direct handoff / continuation-stealing | **dropped deliberately.** Its real use is fine-grained fork-join, which Mezze answers with `par_map` and native parallelism, not userland schedulers |
| per-primitive wakeup order | **kept**, via named wait sets — without them you cannot write a fair mutex or a LIFO channel, a more ordinary want than continuation-stealing |

### Where this sits in the literature

**Delimited, one-shot, SECOND-CLASS continuations with userland scheduling
policy.** The nearest prior art is **Rust's `Future` / `Waker` / executor split**
— a runtime-owned suspension protocol with a userland executor choosing what to
poll; `cont.park` is registering a Waker and `drive` is the poll loop. The
difference is in our favour: Rust builds its state machine at COMPILE time,
which is why `async fn` colours every function, while we capture a real stack and
an ordinary function parks with only its effect row changing.

Also on the map: **Effekt** (second-class capabilities — closest on that axis,
and the source of the claim that second-class continuations cannot express
userland schedulers, which is only true if the SCHEDULER must hold them);
**OCaml 5** (delimited, one-shot, but first-class and escapable — what we gave
up); **Kotlin** coroutines (`suspend` + CPS + userland `Dispatchers`, compile-time
machine again); **Go** (runtime-owned `gopark`/`goready`, no user-visible
continuations — we are Go plus swappable policy); and **scheduler activations**
(Anderson et al. 1992), the original "kernel owns the mechanism, user owns the
policy" argument, which is exactly this division.

## Open rules to settle before implementation

Five things the design does not pin down. None is a hole in the mechanism; each
is a rule the runtime must commit to.

**`drive` must not be re-entered.** Inside `drive` you deliver to a task; that
task may suspend again. If the runtime calls `drive` recursively, the stack grows
per switch and the flat-trampoline property measured in `Reactor` (max depth 5
across 11 switches) is lost. The rule: a delivery from `drive` returns when the
task next suspends, and `drive` reports progress — never re-entry.

**Nested performers that both declare `drive`.** `perform A in perform B in body`
— when an op of B suspends, whose `drive` runs? Presumably B's, but the outer one
still needs a turn eventually, and "the performer whose op suspended" has to be
stated rather than assumed.

**Suspension during `finalize_scope`.** Finalize delivers `DeliverCancelled` to
parked tasks; a task's cleanup may itself `await` and park again, DURING
finalization. Needs either a prohibition or a quiescence loop with a bound.

**Which scope auto-registers.** `cont.suspend {}` registers with "its enclosing
scope" for liveness. With nested scopes, which one — and what happens when an
outer one finalizes first?

**Stale resumers after re-parking.** A task parks, is delivered to, and parks
again — the second park yields a NEW continuation. A scheduler still holding the
first resumer gets a silent no-op, so a dropped wake looks like nothing
happening. This is the one place totality HIDES a bug rather than taming one, and
the one place a lint earns its keep.

## Rejected framings — things tried and dropped

Kept because each cost real time and each would otherwise be re-proposed.

| framing | why it died |
|---|---|
| `cont` confined to its own `perform` block | a scheduler's queue MUST outlive the task whose continuation it holds, so confinement forbids the one thing the language most wants. Measured via the flat-scheduler probe. |
| "region-scoped is just delimited" | orthogonal axes. OCaml 5 is delimited AND escapable; Effekt is delimited and second-class and consequently cannot express userland schedulers. |
| Loom vs BDSL as competing mechanisms | a category error — the author: *"it is not that bdsl continuation and loom are competitors."* BDSL is HOW a perform suspends; Loom is WHO runs what. The real axis is blocking vs suspending. |
| `k` as a parameter in the op's record | a parameter record describes the CALL, and the call supplies no continuation. |
| a `suspends` keyword on the op declaration | refused: no new keywords. Unnecessary anyway — the check is 38 ps, so every non-empty-row call can carry it. |
| `Step 'a = Done | Suspended` returned by resume | only needed when resume returns the block's value; with totality it returns `'a` instantiated to `{}`. |
| five separate forms (`cont.resume`/`abort`/`suspend` plus bare `resume`/`abort`) | two mechanisms for one job. The author: *"either abort park and resume exists as keyword functions or cont is the keyword record... only one of these can happen."* |
| a linear `Cont` type | needs annotations; the total no-op gives the same guarantee for free. |
| "Phase C is just re-emit the yield" | wrong. Composition needs every possibly-suspending call site to propagate — the CPS taint Path D was believed to dodge. |
| `invalidate_all` at `finalize_scope` | prevents an unsafe resume but leaves every parked task's cleanup UNRUN. Must wake them with `raise` instead. |

---

# Evidence — the measurement record

Everything above rests on these. Read them before re-litigating any choice.

## THE TRUNK, RE-CUT 2026-11-16 AFTER MEASUREMENT

Every mechanism below was tested before being planned. Nine tests across five
suites in `interpreter/src/test/java/com/mezze/interpreter/bytecode/`.

### Phase A — make the ops interceptable. Stdlib only.

Promote `await`, `yield_now`, `park`, `park_until`, `cancel` to ops of `Async`.
**The source makes the case:** `async.mz:342` declares `spawn` as the SOLE op,
and `await` (56), `cancel` (89), `yield_now` (126), `park` (129),
`park_until` (144) are plain `pub let`s straight to natives. That is why the four
existing performers — `LoomAsync`, `LoomAsyncWith`, `OSThreadAsync`,
`OSThreadAsyncWith` — differ ONLY in `spawn`: it is the only thing they are
allowed to differ in.

No runtime work, no continuation, reversible, independent of B and C.

### Phase B — handler evidence travels with the call.

Pass `resume`/`abort` through the handler call instead of a `@TruffleBoundary`
ThreadLocal. ~41 -> ~16 ns, and it kills the silent cross-thread wrong-handler
read (a wrong VALUE, never an exception).

**Promoted from optimisation to prerequisite by measurement:** a BDSL
continuation IS thread-portable (`BdslContinuationCrossThreadTest`: suspended on
one executor, resumed on another, correct result), so a layered executor can
parallelise — but the handler chain is per-thread, so the evidence must travel
with the continuation rather than be read off whichever thread resumes it.

### Phase C — composable suspension. FOUR parts; three are PROVEN, the gating one is not built.

**C1 — carry "can this call suspend" on the call IR. NOT PRESENT TODAY, and it
gates the rest.** `CallDirectIR` carries `targetHash`, `args`, `isTailCall`,
`typeTag`, `isScalarABI`, `comptimeShape` — and **no effect information at all**.
Neither does `CallLocalIR`, `CallIndirectIR` or the rest. So the repeated claim
in this document that "the effect row picks the sites, pure calls stay free" is
NOT supported by the IR as it stands: at lowering time a call site does not know
whether its callee can suspend. The row exists in the solved type and is dropped
before the IR.

Two precedents in the same file say how to fix it:

- `HandleIR.staticallyFullyDischarges` — a boolean on an effect node, "set by the
  typechecker/compiler". Exactly this shape.
- `CallDirectIR.comptimeShape` carries its reason: on the NODE, not a side table,
  because "the reduction pass runs in the RUNTIME, which for `mez dev` / `exec`
  is a different process and receives nothing but serialized IR. A side table
  keyed by source span works in-process and **silently does nothing** across that
  boundary — which is every path a user actually builds with."

So C1 is one flag per call node, set from the solved effect row, carried on the
node. Until it exists, C2's check would have to be emitted at EVERY call site,
which is the version whose cost nobody has measured.

**C2 — propagate the suspension at those call sites. PROVEN.**
`BdslSuspendPropagationTest`: with the caller yielding the callee's
`ContinuationResult` onward, `outer(x) = inner(x) + 100` over
`inner(x) = (yield "op") + 1` resumes to **142 — both tails run.** Built from
EXISTING builder vocabulary (`yield` accepts any value), so no framework change
and no new operation was needed to prove the semantics. The real emit adds the
conditional, which is what C1 feeds.

**C3 — a stack-walking driver. PROVEN, and the flat one is demonstrably
insufficient.** Nested suspension is a STACK: a continuation whose yielded value
is itself a continuation. The driver descends on a nested continuation and feeds
completed values back outward; 25 lines. The same test pins why `YieldDriver`'s
flat loop cannot do it — it hands the INNER CONTINUATION to the dispatcher as
though it were an op record.

**C4 — callback-taking natives hold loop state on the HEAP. PROVEN.**
`ResumableNativeHofTest`: a `map` keeping `(xs, out, i)` in a record parks on
every one of three elements and still produces `[11, 21, 31]` in order, re-entering
the loop at `i + 1` each time. **And the fast path costs nothing structural** —
when the callback never suspends, no state object is ever allocated; the only
overhead is one type test per element.

### MEASURED: the suspension check costs 38 PICOSECONDS per call site

I had said this could not be measured until C1 exists. **Wrong — C1 decides HOW
MANY sites carry a check, not what ONE costs**, and `CheckSuspendedPassThrough`
already exists with exactly the right cost shape (one `instanceof` on the call's
result, value passed through). Two roots identical but for the check, arms
INTERLEAVED in one process (`SuspendCheckCostTest`):

    plain   ps/call: 7368 7146 6984  (min 6984)
    checked ps/call: 7022 7070 7061  (min 7022)
    delta per call site: 38 ps (0.04 ns)

The ranges OVERLAP. The check is free, and because it is free the site count
stops mattering — so C1's value is correctness and clarity, not performance.
A loose 20 ns guard is asserted so that if the check ever starts defeating
inlining or boxing elimination, the plan gets re-costed instead of silently
rotting.

### MULTI-SHOT: free for guest code INCLUDING composition, not free through a native

`MultiShotMatrixTest`, three shapes, because "multi-shot" is not one property:

| shape | result |
|---|---|
| one root resumed repeatedly | **free** — 1->2, 2->3, 42->43, then 1->2 again. No state carried between shots |
| a COMPOSED (nested) continuation resumed repeatedly | **free** — 41 gives 142, 141 gives 242, 41 gives 142 again. Multi-shot SURVIVES composition |
| resumed through a resumable native | **SHARED STATE.** The guest tail is multi-shot (10->11, 20->21) but both shots write the SAME output slot in the SAME array, so the second overwrites the first |

So: fully multi-shot for guest code, and multi-shot through a native HOF is sound
only if its heap loop state is COPIED per shot. That is a bounded, known fix on
the C4 shape — and one more reason the one-shot default is right, with multi-shot
as an opt-in.

### THE HEADLINE PERFORMANCE NUMBERS — BDSL park/resume, measured at last

The ~100 ns in this document was always LOOM's. The BDSL mechanism had never been
timed. Four arms interleaved in one process (`SuspendResumeCostTest`):

| arm | cost |
|---|---|
| plain call, no yield | **6.62 ns** |
| park + resume (one full cycle) | **21.37 ns** |
| + a ONE-SHOT GUARD (wrapper + used flag) | **21.55 ns** — delta **179 ps**, free |
| composed 2-level, via the stack driver | **46.18 ns** (~24.8 ns per extra level) |

Suspend overhead over a plain call: **14.75 ns**.

**BDSL is ~5x cheaper than Loom for a shallow park** (21 ns against ~100 ns), and
that is the common case — one effectful frame between the `await` and the handler.

**But the slope is the opposite way round, and this matters.** Each propagated
level costs another yield+resume (~25 ns), whereas Loom pays ~100 ns once and
then ~0.6 ns/frame:

    nested effectful frames | BDSL propagate | Loom freeze
       1                    |     21.4 ns    |   100.6 ns   BDSL
       2                    |     46.2 ns    |   101.2 ns   BDSL
       4                    |     95.8 ns    |   102.4 ns   BDSL (tie)
       6                    |    145.4 ns    |   103.6 ns   LOOM
      12                    |    294.2 ns    |   107.2 ns   LOOM

Crossover at about **four nested effectful frames**. So a BDSL core is the right
default and deep effectful call chains are its weak spot — which is a third,
independent reason to keep a Loom performer rather than delete it, and a reason
to keep propagation depth short.

### CORRECTIONS to how this document framed depth, leaks and scopes

**1. Loom and BDSL are NOT competitors, and the table above implied they are.**
The author: *"it is not that bdsl continuation and loom are competitors. loom
scheduler is used for async and parallel and that's it."* Correct, and it is the
layering this document already agreed. The real axis is **blocking vs
suspending**: today's `LoomAsync` BLOCKS a virtual thread on `await`, captures
nothing and pays NO depth cost — it pays a thread per task instead. A
`cont`-based scheduler suspends and pays per level. Read the depth table as that
trade, not as a choice of mechanism.

**2. What depth MEANS: non-tail effectful frames, not nesting.** Depth is the
frames between the park site and the handler that STILL HAVE WORK LEFT:

    let a = {} -> b {} + 1    ## work after the call -> must suspend
    let b = {} -> c {} + 1    ## same
    let c = {} -> Async.await { p = p } + 1

**A TAIL call needs no propagation**, and `CallDirectIR` ALREADY CARRIES
`isTailCall`: with nothing after the call the callee's continuation IS the
caller's, so no extra yield. The cost is therefore the count of NON-TAIL
effectful frames, which in idiomatic code is far smaller than the call depth.
That is the main mitigation and it is free.

**And ABORT costs nothing at any depth** — it does not park, it throws and
unwinds, capturing nothing. Measured escaping a native `map` callback correctly.
Only PARKING pays.

**3. Making a native resumable costs 383 ps/element — MEASURED.**
`ResumableNativeCostTest`, same data and callback, arms interleaved:

    plain     ps/element: 7680 7856 7893 (min 7680)
    resumable ps/element: 8077 8063 8151 (min 8063)
    delta per element: 383 ps (0.383 ns)

~5% on a per-element cost already dominated by the guest call, and only for
callback-taking HOFs. Vectorised paths are unaffected — they fold the operation
into a kernel and have no guest callback to park in. No state object is allocated
unless something actually parks.

**4. "Dropped continuations are a new leak shape" was OVERSTATED.** A
continuation is an ordinary heap object; it leaks exactly as much as any object a
scheduler forgets. The resource APIs bound it — a scope holding parked
continuations can invalidate them at `finalize_scope` — and
`AsyncNatives.SuspendedOps` already reports suspended ops when a context closes.
Not a special leak class.

**5. "Resume after scope finalize is detected, not prevented" was the WRONG
PHRASE.** With scope-driven invalidation the unsafe ACT is prevented: finalize
kills the scope's parked continuations, so a later resume hits a dead handle and
fails loudly rather than touching a closed pool. What is not prevented is the
PROGRAM containing the mistake, because that needed the confinement rule dropped
so a scheduler's queue could outlive its tasks. It is **prevented at runtime, not
statically.**

### ONE-SHOT ONLY: possible, nearly free, and it DELETES the native hazard

Enforcement is a handle wrapping the `ContinuationResult` with a `used` flag;
`resume` checks and flips it. **Measured at 179 ps** — free.

And refusing multi-shot removes the C4 problem entirely: the resumable native's
shared heap loop state can only be written twice if a continuation is resumed
twice, so one-shot makes "copy the loop state per shot" unnecessary rather than
merely deferred. That turns the one real multi-shot defect into a non-issue by
construction.

Multi-shot remains available as a later opt-in (the framework gives it for free
for guest code, including across composition), and would then owe the
copy-per-shot work on natives.

### Everything measurable is now measured

Nothing in this plan is waiting on a number. What remains is implementation: C1
(the effect row reaching the call IR), then wiring C2/C3/C4 — all three of which
have working reference implementations in the test suite.

## THE MECHANISM IS TRUFFLE'S, NOT LOOM'S — and it is already wired up

Asked whether GraalVM/Truffle has its own performant continuation abstraction.
**It does, we enabled it, and we already built an effect path on it.** Truffle
Bytecode DSL `enableYield = true` is set on `MezzeBytecodeRootNode`; a yield
makes the root's `callTarget.call` return a `ContinuationResult`, whose
`getResult()` is the yielded value and `continueWith(v)` resumes.
`BdslContinuationPrototypeTest` **passes on today's build** (2 tests, 0 failures,
per the JUnit XML — note the `-i` log prints no `PASSED` lines for this module,
so count the XML, not the log).

Against Loom, measured and documented, it wins on every axis that was a worry:

| | Loom | Truffle BDSL |
|---|---|---|
| native-image | works, needs `--add-exports java.base/jdk.internal.vm` (nothing in the build has it; D7 wanted exports removed) | **clean** — ordinary Truffle objects, no flag |
| shots | one | **multi-shot for free** (same `ContinuationResult`, different values) |
| compiler | an intrinsic the JIT knows | **PE-friendly framework primitive** |
| frames | freeze/thaw of Java frames | framework materialises, no manual capture |
| the handle | a `Continuation` | `getContinuationCallTarget()` — **a VALUE** |

So Phase C is not "build a continuation mechanism". It is **re-emit the yield at
perform sites and give the driver a real resume** — far smaller than this
document's Path C estimate, which assumed a CPS-at-IR transform.

**Why it is not live.** D1 lowered `PerformOpIR` to `beginYield / MakeRecord /
endYield`; **D4 replaced that with a direct `PerformD` op**, so a
`ContinuationResult` no longer flows. `beginYield`/`endYield` appear NOWHERE in
production today — only in a doc comment — and `YieldDispatch.driveWithChainDispatch`
says so itself: *"walks a ContinuationResult chain if one flows out (which no
longer happens after D4 ... but kept for the D2 test harness)"*. `YieldDriver` is
a proven mechanism sitting unwired. Compare
[[project_iter_count_all_declared_with_no_users]] and the dead `KnownMutOps`
layer: this is the third time a capability has been found declared-but-unreached.

**A VOID MEASUREMENT, recorded so nobody repeats it.** I benchmarked one effect
op at `MEZZE_BDSL=1 MEZZE_BDSL_YIELD=1` against the default expecting a Path
A/D comparison, and got 22 ns vs 21 ns. **It compares nothing.**
`MEZZE_BDSL_YIELD` survives only in a TEST COMMENT — production reads no such
variable — and the emit it used to gate is gone. Both arms were the same code.
The real question (what a live yield emit costs per perform) is UNMEASURED.

### LOOM STAYS — the two live on different layers

Asked whether Loom can remain one way to do async if the mechanism is BDSL.
**Yes, and they are complementary rather than competing.** BDSL yield is HOW a
perform site suspends a guest computation; Loom virtual threads are WHO runs
what, on which threads. A Loom performer needs no continuation at all: `spawn`
starts a virtual thread and `await` blocks on it.

Four performers of `Async` already exist and none would change:
`LoomAsync`, `LoomAsyncWith { max_concurrent }`, `OSThreadAsync`,
`OSThreadAsyncWith { pool_size }` (`stdlib/std/effects/async.mz`). They differ
ONLY in `spawn` — which is Phase A's case made by the source itself: line 342
declares `spawn` as the sole op, while `await` (56), `cancel` (89),
`yield_now` (126), `park` (129) and `park_until` (144) are plain `pub let`s
straight to natives, so **not even `LoomAsync` can intercept them today.**

| | Loom performer | reactor (BDSL cont) |
|---|---|---|
| mechanism | blocking on virtual threads | yield / `continueWith` |
| **crosses native frames** | **YES** (freezes Java frames) | **NO** (ContinuationRootNode only) |
| parallelism | real, multi-core | single-threaded unless resumed cross-thread |
| determinism | no | yes — the point for test schedulers |
| touches `cont` | never | yes |

So Loom is not the legacy path: it is the one that works EVERYWHERE, including
through a native HOF, and a reactor is the cheap deterministic one with a
constraint. Both belong.

**The compatibility guarantee:** the capability tax is only paid by a block whose
handler mentions `cont` (a syntactic check). A Loom performer never does, so
today's async stays exactly as fast as today's async.

**Two hazards of mixing, neither yet addressed:**

1. A BLOCKING Loom `await` inside a reactor task blocks the reactor's driver
   thread — the classic event-loop sin. A lint should catch a blocking performer
   reached from inside a `cont`-using handler.
2. **Resuming a `ContinuationResult` on a DIFFERENT thread** is what a
   work-stealing scheduler needs, and whether that is sound is UNKNOWN. It
   requires Phase B first (handler evidence passed, not read from a per-thread
   chain) and then a measurement. Do not assume it works.

## A YIELD SUSPENDS EXACTLY ONE ROOT — the inhibitor, and it is not about natives

Asked for callbacks to work with PROPER continuations rather than leaning on the
Java-throw path. The decisive experiment —
`BdslYieldComposesAcrossRootsTest` — says composition does NOT hold today:

    outer: fn(x) -> inner(x) + 100
    inner: fn(x) -> (yield "op:park") + 1

    UnsupportedSpecializationException: Unexpected values provided for
    AddLong_Node: [ContinuationResult...]

The inner root yielded; `CallDirect1` returned its `ContinuationResult` as an
ordinary value; the outer root's `AddLong` received a continuation as a number.
**The outer root did not suspend, and it IS a continuation root** (`enableYield
= true` is on every BDSL root). So being a `ContinuationRootNode` is NECESSARY
BUT NOT SUFFICIENT — the problem is the CALL SITE, not the frame type.

Which means the native-HOF limit recorded below is not a native-code problem at
all. It is the same defect wearing a different hat: **any** intermediate frame
that does not cooperate receives the continuation as data. A Java `map` loop and
a BDSL caller fail identically.

**CORRECTION, and it reverses what this document said one commit ago.** I wrote
that "Phase C is not 'build a continuation mechanism', it is re-emit the yield at
perform sites" and that this was far smaller than the Path C estimate. **That was
wrong.** I read D0's single-root success as composability. Composition requires
every call site that might suspend to propagate the suspension, which is the CPS
taint that Path C's "CPS-at-IR transform" meant and that Path D was believed to
avoid. Path D avoids the WORKER-THREAD half, not the taint.

### What could solve it

**1. Propagate at the call site (mandatory).** A call that might suspend lowers
to: call; if the result is a `ContinuationResult`, yield it onward. Resuming
walks inward-out: resume the inner with the op's answer, then resume the outer
with the inner's result.

Concretely, the missing piece is an operation around every suspendable call:

    b.beginAddLong();
      b.beginPropagateSuspend();              // <-- does not exist
        b.beginCallDirect1(innerTarget, false);
          b.emitLoadArgument(0);
        b.endCallDirect1();
      b.endPropagateSuspend();
      b.emitLoadConstant(100L);
    b.endAddLong();

meaning: if the operand is a `ContinuationResult`, yield it onward so this frame
suspends too; otherwise pass the value through. `CallDirect1` cannot do this
itself — its body is `return callNode.call(arg)`, so it cannot distinguish "my
callee suspended" from "my callee returned a value", and the outer's `+ 100` is
lost with nothing remembering it must run later.

**CORRECTION to an earlier claim here:** I wrote that `YieldDriver.drive` was
already built for the chain. Not quite — its loop is
`while (cur instanceof ContinuationResult cr) { ...; cur = cr.continueWith(answer); }`,
which handles ONE root yielding REPEATEDLY (a sequence). A nested suspend is a
STACK: a continuation whose yielded value is itself a continuation. The driver
needs an explicit stack, not a flat loop. Smaller than writing it from scratch,
but not free as implied.

*The cost is a branch per call site — and the EFFECT ROW says which sites need
it.* A call whose row is pure cannot suspend and needs no check. That is static,
sound, and already in the type system, so the tax lands only on effectful calls.
**This is the next thing to measure, and it is the number that decides Phase C.**

**2. Callback-taking natives must become resumable.** Loop state has to live on
the heap, not in Java locals, so the native can return a continuation that
resumes the loop at index `i` with the accumulated output. Per-element fast-path
cost: one `instanceof`. Worth checking first: the natives that take GUEST
callbacks are already scalar per-element — a vectorised path folds the operation
into a kernel and has no guest frame to park in — so this likely costs no
vectorisation. See [[project_flp_only_pays_when_it_vectorises]].

**3. Re-expressing those natives as BDSL loops does NOT help on its own.** The
measurement is explicit: a BDSL caller fails exactly as a Java caller does. (3)
is only useful combined with (1).

### THE ARCHITECTURE: BDSL cont is the CORE, Loom is an IMPL ON TOP

The author, cutting through a comparison table this document should never have
drawn: *"my thing was having something core BDSL cont and on top loom can be the
impl for async using it."*

**That is the right layering and the table was a category error** — it compared a
primitive against something that would be BUILT ON that primitive. Correctly:

| layer | what it is |
|---|---|
| core | BDSL continuation — suspend a Mezze computation, get a handle |
| on top | schedulers: a virtual-thread executor, an OS-thread pool, a single-threaded loop, a deterministic test scheduler |

And this layering makes Loom CHEAPER than treating it as a rival mechanism. If
BDSL does the suspending, Loom is only a thread pool:
`Executors.newVirtualThreadPerTaskExecutor()` — **public API**. No
`jdk.internal.vm.Continuation`, no `--add-exports`, which **deletes the D7
tension this document listed as a risk**. The six Loom measurements recorded
above are then a sanity check on switch costs, not a decision input.

**MEASURED, and it is the precondition for the whole layering:** a BDSL
continuation is THREAD-PORTABLE. `BdslContinuationCrossThreadTest` suspends on
one single-thread executor and resumes on another:

    [xthread] suspended on A, yielded=op:park
    [xthread] resumed on B, result=42

So a layered executor CAN provide real parallelism by resuming continuations on
different carrier threads. (Phase B is still required before relying on it: the
handler chain is per-thread, so evidence must travel with the continuation rather
than be read off the resuming thread.)

### Where the architecture stands

| | |
|---|---|
| BDSL cont exists, multi-shot, native-image clean | **yes**, `BdslContinuationPrototypeTest` |
| thread-portable, so executors can parallelise | **yes**, `BdslContinuationCrossThreadTest` |
| composes across call frames | **NO** — the one blocker, `BdslYieldComposesAcrossRootsTest` |
| callback-taking natives resumable | **no** — need heap loop state |
| cost of the fix | **UNMEASURED** — a branch per effectful call site |

Everything except composition is settled. Composition is the whole job.

### What this does to the Loom comparison — SUPERSEDED by the layering above

Loom composes for FREE: it freezes the whole Java stack, so `Cycle` suspends
through 200 frames, natives included, with no taint anywhere. That is why Loom
async works today with none of this machinery. Sharpened:

| | Loom | BDSL cont |
|---|---|---|
| composes across frames | **free** | needs a check at every suspendable call site |
| crosses native frames | **yes** | no, unless the native is made resumable |
| switch cost | ~100 ns measured | unmeasured with a live emit |
| native-image | needs `--add-exports` | **clean** |
| shots | one | **multi** |

So Loom is the stronger candidate for the PRIMARY async path, and a BDSL-cont
scheduler is the deterministic/testable option — the reverse of the emphasis this
document had before the measurement.

### EFFECTFUL CALLBACKS SURVIVE — measured, and my blanket warning was too broad

Challenged on the claim that a native frame in the chain is a problem: do
effectful callbacks (and exceptions, which use abort) still work? **Measured on
both sides. Three of the four shapes need no suspension at all and keep working;
exactly one breaks.**

At the language level, on today's build (`scratchpad/effcb`):

    1. perform inside native map : [10, 20, 30, 40, 50]
    2. perform inside native fold: 150
    3. abort through native map  : [-1]
    4. perform nested 2 natives  : [20, 40, 60, 80, 100]

A perform inside a native HOF's callback works, nested through TWO native frames
works, and an **abort fired inside a native callback escapes the native frame**
and ends the perform block with its payload. Why: a handled perform is a PIC'd
direct call and abort is a Java throw — neither needs a suspendable stack. (The
typechecker also pinned the abort payload to the block's type, refusing `Int`
where the block was `[Int]`: T0201, correct.)

At the mechanism level, `BdslYieldAcrossNativeFrameTest` (2 tests, passing) pins
the one that does break:

| shape | needs suspension? | works |
|---|---|---|
| handler resumes immediately (the common case) | no — direct call | **yes** |
| handler aborts | no — Java throw | **yes** |
| no effect in the callback | no | **yes** |
| handler **parks via `cont`** | YES | **NO** |

And the failure mode is the dangerous kind, which is why it must be loud: the
native loop **runs to completion** and each callback result comes back as a
`ContinuationResult` that a native HOF would store as the mapped element. Silent
garbage, not an exception. Resuming one resumes only the guest tail — the native
loop's state is already gone, so there is no way to re-enter the map.

**This is the measured case for keeping BOTH performers.** Loom freezes Java
frames, so it CAN park inside a native HOF's callback (`Cycle` suspends through
200 Java frames); BDSL cannot. So the split is not taste:

- `LoomAsync` — parks anywhere, including inside `map`'s callback. Real threads.
- a BDSL-cont performer — cheap, deterministic, single-threaded, and **cannot
  park inside a native callback**.

### The per-function-root caveat — THE top risk

A BDSL continuation suspends only frames whose root is a `ContinuationRootNode`.
Guest code is fine: BDSL is already the sole path for `FunctionIR`. But a
**native Java frame in the chain is not one** — a native HOF calling back into
guest code (`map`, `fold`, the `Seq` natives) cannot be suspended through. Under
[[feedback_no_fallbacks]] that must FAIL LOUDLY at the suspend attempt, never
silently degrade to Loom or to a wrong value.

### The original Step 0, as written before the measurements

1. Does `jdk.internal.vm.Continuation` work under **native-image**? Biggest
   risk, since we ship AOT. Needs `--add-exports java.base/jdk.internal.vm`,
   which D7 wanted removed.
2. What does a capture cost at stack depths 1 / 10 / 50? **UNMEASURED.**
3. Does math INSIDE a function whose row carries an effect pay anything today?
   **UNMEASURED** — pure loops are ~0–1 ns, effectful-function math is untested,
   and `enableYield = true` is already set on `MezzeBytecodeRootNode`.

## Constraints any future design has to respect

- **BDSL yield suspends ONE bytecode root** and cannot cross a Java frame. 30%
  of stdlib op bodies are `__native_*`, so BDSL alone cannot be the mechanism —
  it would work in some call chains and silently not in others. Its own note
  says it: *"all functions in the call chain that contain a yield must be
  BDSL-compiled. Non-BDSL fns in the chain force fallback to Loom."*
- **Loom crosses everything** (it parks the whole stack segment) but is
  one-shot, so it cannot do multi-shot.
- **Multi-shot** therefore needs BDSL (native wall, no fallback) or a CPS
  transform, and is semantically fraught besides: a frame copy forks LOCALS and
  SHARES the HEAP. `replay` is the pragmatic substitute and STM already uses it.
- Other Truffle languages do not have this problem. JS/Python generators and
  `async` suspend ONE LEXICAL frame — `yield` cannot appear in a nested call —
  so one-root yield suffices. Effects can `perform` at ANY depth. Ruby fibers
  use threads.
- **"multi-shot" in the Path D notes usually means DEFERRAL.**
  `Generator.AsStream` was described as multi-shot but *"captures resume inside
  an abort payload and calls it later"* — each `k` once. `generator.mz` no
  longer exists, so the one claimed multi-shot user is gone.
- Path D built the yield lowering (D1, `7ff6b114`) and reached 5302/5302, then
  **D4 replaced it with the direct `PerformD` op, and that is what ships**.
  `enableYield = true` and `YieldDriver.drive` remain as a hedge.

## Where the userland boundary falls

| layer | who | what |
|---|---|---|
| L0 natives | first-party only | task/thread start, syscalls (io_uring if wanted), and capture if C lands |
| L1 effect | first-party | `Async` with the scheduling points as **ops** |
| L2 | userland | any `Async` performer: policy, fairness, reactors, mocks, deterministic replay |

Swappable: scheduling policy, IO readiness source, carrier threads or none, and
the whole async shape (actors, CSP, structured-concurrency variants).

Not swappable: a **new kernel IO mechanism**. io_uring needs
`io_uring_setup`/`enter`, hence an L0 native, hence first-party. An io_uring
*provider* is stdlib work; schedulers over it are userland. Same boundary as
`Polyglot`/`Runtime`, which `mezze-python` and `mezze-js` already demonstrate
out-of-tree.

Ordinary effects are unaffected by all of this. `Log`, `Time`, `FileSystem`,
`HttpClient` handlers are bare-value — 70% bare value + 30% native = 99% of 957
impl ops, with 5 aborts in total. What C would add is a second *performer* of
the same effect for a reactor; the interfaces do not change, and user code with
`<FileSystem, Time, Log>` in its row runs unchanged under either.
