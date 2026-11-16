# Single-shot continuations and swappable async

Designed 2026-11-12/13 while auditing the effect system. **The verdict is
smaller than the design: do Phase A and B, leave C until something concrete
demands it.** Phases A and B are each worth doing on their own merits; C is a
capability nobody has asked for yet.

**Read the measurement, never the prose — this file included.** Every number
below says how it was produced. Two claims are marked UNMEASURED on purpose.

Gates for every change here: `compileStdlib` first, then `:typechecker:test`,
then `:interpreter:test`, SEPARATELY. `~/.mezze` is shared, so a concurrent
session will produce 120s `ModuleResolver.prefetch` timeouts that are not yours
— run the suspect alone before believing a failure.

## Why this is not urgent: Loom already does the common case

    perform PooledHttpClient in perform LoomAsync in do
      let p1 = spawn { f = {} -> get { url = "a" } }
      let p2 = spawn { f = {} -> get { url = "b" } }
      combine { a = await { p = p1 }, b = await { p = p2 } }

Each task is a virtual thread; the blocking call inside unmounts it. Real
concurrent IO with ordinary blocking code — no reactor, no io_uring, no
continuations. `spawn`'s `<...>` on the callback carries the task's effects to
the spawn site, so a handler installed outside covers them.

A reactor (single-threaded event loop, tasks must never block) only beats that
at ~1M+ connections, for batched syscall submission, for extreme tail latency,
or for **deterministic scheduling in tests** — the last being the only one
appealing here, and not pressing.

## Phase A — promote the scheduling points to ops

**Stdlib only. No runtime work. This is the whole unlock for realistic
swappable async, and it is cheap.**

`Async` has exactly ONE op today:

    effect Async where { spawn: { f: {} -> 'a <...> } -> Promise 'a <Async> }

Every other scheduling point is a plain `pub let` straight to a native, so **a
performer cannot intercept it**:

| point | where | today |
|---|---|---|
| `spawn` | `async.mz:342` | op ✅ |
| `await` | `async.mz:56` | `__native_promise_await_result` ❌ |
| `yield_now` | `async.mz:126` | `__native_async_yield_now` ❌ |
| `park` / `park_until` | `async.mz:129,144` | natives ❌ |
| `cancel` | `async.mz:89` | native ❌ |
| scope lifecycle | — | `initialize_scope`/`finalize_scope` ✅ |

Make those ops; keep the `pub let`s as one-line shims
(`pub let await = { p } -> Async.await { p = p }` — the shape
`pub let throw = { error } -> Exception.throw { error }` already uses).
`LoomAsync` implements them with today's natives, so it stays tail-resumptive,
same cost, **behaviourally identical** — which is the thing to test. The derived
combinators (`race`, `all`, `settle`, `par_map`, `try_all`, `try_any`,
`with_timeout`, `map_ok`, `try_await`, `go`, `run`) need no change at all.

Enables: userland `Async` performers with custom policy over Loom — fairness,
priority, concurrency limits, instrumentation, a mock async for tests. **No
capture needed**, because tasks still run on virtual threads and Loom unmounts
on block.

**The interface, not the continuation mechanism, is what blocks alternative
async today.** That is the main finding of the whole exercise.

`yield_now` is the clearest illustration of why these must be ops. Its native is
`Thread.yield()` — under Loom a *hint* to the JDK scheduler; under a reactor it
would have to mean "requeue me and run someone else". Same source, two
semantics, and only the handler can know which. `park` is worse: it is a
spin-park with exponential backoff, and its own comment says *"an explicit waker
would be cheaper for predicates that change rarely"* — a reactor would register
against a readiness event and never poll.

## Phase B — evidence passing: delete the ThreadLocal for `resume`/`abort`

Independent win; do it regardless of C.

`HandlerChainRegistry.pushEffectHandles(resume, abort)` is called by the
dispatcher IMMEDIATELY before invoking the op handler, and the body fetches them
back via `EffectBindingNode.lookupOnFiber`, which is `@TruffleBoundary` over a
ThreadLocal. Producer and consumer are adjacent; the thread-local exists only to
permit lookup from anywhere — which V0503 made impossible.

Pass the handles through the handler call (or into its frame) instead.

MEASURED payoff, built JAR, arms interleaved in one invocation, median of 9 runs:

| | ns/iter |
|---|---|
| bare loop, no effect | ~0–1 |
| effect op, bare-value handler | ~16 |
| effect op, explicit `resume { val = … }` | ~41 |

So ~41 → ~16 for explicit `resume`. Allocation is NOT the cause: 3–4 young
pauses at n=1e7 against a floor of 1, where the calibration in
`perf-baseline.md` puts one allocation per iteration at ~33.

Also fixes, from the code's own comments:
- the documented silent cross-thread hazard — *"a cached handler is one
  thread's, readable by all… two tasks silently read each other's handler: a
  wrong VALUE, never an exception"*
- `singleThreaded`'s permanent degradation — *"the assumption never comes
  back"*, so multithreaded programs run the re-resolving dispatch for the life
  of the process
- **Path C's blocker.** The CPS attempt died on *"the worker-thread approach
  can't share ThreadLocals with the driver"*. B is its prerequisite, so B keeps
  a Loom-free capture mechanism possible without committing to one.

Benchmark method, because the existing harness cannot answer this: time INSIDE
one warmed process (`Time.monotonic` around the loop) and take the slope across
two sizes. `EffectHandlerPerfTest` is end-to-end and startup-dominated — a 100k
arithmetic loop and a 500-op effect program both land at ~4.6–5.0 ms — and three
of its five cases no longer compile (two missing `*` on `outer`, one empty `do`
block). An attempt to measure per-op cost from the JUnit harness produced
NEGATIVE per-op costs and was discarded. `mez dev` is the interpreter: it
reports ~160–215 ns for the same op that a built JAR does in ~16.

## Phase C — capture. Design settled; build only on demand.

One form per operation. Bare `resume` AND bare `abort` both go away:

| want | how |
|---|---|
| tail resume | **return a value** — no keyword (already the language rule) |
| get the continuation | `let k = capture {}` |
| resume it | `k.resume { val = x }` |
| discard it, block yields `x` | `k.abort { val = x }` |

Why not "`resume` as a first-class value": mentioning `resume` meaning capture
while applying it meant resume **breaks referential transparency** —
`push_back { k = resume }` and `let k = resume; push_back { k = k }` must be the
same expression. `capture {}` is an ordinary call; `k` an ordinary value.

Matches **OCaml 5** (`continue k v` / `discontinue k exn`, first-class one-shot,
runtime error on second use). **Effekt** deliberately chose second-class
continuations and consequently cannot express userland schedulers — that is the
price of the keyword-only direction.

- Cost model: **does the handle escape the op body?** No → a local escape check
  compiles it to today's direct path, free. Yes → you are deferring, you pay.
  Same intuition as a non-escaping closure.
- One-shot needs a RUNTIME guard either way; static needs linear types. Putting
  the obligation on `Cont 'a` is what makes it forward-compatible with adding
  linearity later.
- Make `capture` an **op**, so a capturing handler shows up in the ROW rather
  than only in the syntax.
- `k.abort` is the cancellation story: deterministic unwind instead of a
  best-effort interrupt. `finalize_scope` already runs on the abort path
  (measured: `INIT / FINALIZE / -> 99`), so cancel → resume-with-abort → scope
  hooks run → resources released.

### Settle these before writing any C code

1. **Bare value after a `capture {}`.** OCaml's model makes the handler's return
   value the BLOCK's value once captured — so a bare value would mean the op's
   value normally and the block's value after a capture. A mode switch. Decide
   it explicitly rather than discovering it.
2. **Captured continuation vs scoped resources.** `HttpClient` holds its pool in
   `scope`. A continuation captured inside and resumed after `finalize_scope`
   would touch a closed pool. Options: scope outlives any capture / refuse
   capture in a scoped handler / fail loudly on a late resume.
3. **A dropped continuation is a new leak shape** — never resumed, never
   cancelled. Precedent for detecting it exists: `AsyncNatives.SuspendedOps`
   already reports unhandled suspended ops *"when a context closes holding
   one"*.

### Step 0 — MEASURED 2026-11-15. All three answered; two in our favour.

Probes: `scratchpad/loomprobe/{Probe,Cycle,Aot}.java`, GraalVM CE 25.0.2,
`--add-exports java.base/jdk.internal.vm=ALL-UNNAMED`.

**1. Does `jdk.internal.vm.Continuation` work under native-image? YES.** The
listed BIGGEST RISK, and it is not a risk. A native image built from a probe that
parks and resumes a continuation prints

    after park  : stage=1 done=false out=-1
    after resume: stage=2 done=true  out=42
    AOT: WORKS

so park, resume, the value flowing back and `isDone` all behave as on the JVM.
The export is still needed (nothing in the build has
`--add-exports java.base/jdk.internal.vm` today — only `jdk.internal.vm.ci` in a
bench script), so D7's wish to drop exports is the live tension, not capability.

**2. Capture cost by stack depth — MEASURED, and it is MOSTLY A FIXED COST.** A
full switch (freeze + thaw), min of 9, 3000 continuations per rep:

| depth | JVM | native-image |
|---|---|---|
| 1 | ~137 ns | 97 ns |
| 10 | 95 ns | 122 ns |
| 50 | 116 ns | 211 ns |
| 200 | 213 ns | 545 ns |

JVM: ~100 ns floor, ~0.6 ns/frame. AOT: ~90 ns floor, **~2.3 ns/frame** — cleanly
linear, about 4x the JVM's slope, which is what one would expect when the JIT
cannot optimise the freeze/thaw loop. Shallow switches are if anything FASTER
under AOT.

For scale, an effect op today is ~16 ns (bare-value handler) and ~41 ns (explicit
`resume`), so **a task switch costs roughly 6x an effect op** and stays in the
same order to depth ~50. That is fine for IO-bound work; deep stacks suspending
frequently are where it bites, and only on AOT.

**3. BINDING A CONTINUATION IS PASSIVE — and this is the finding that shapes the
syntax.** `Continuation.getCurrentContinuation(scope)` returns a handle, the body
runs straight past taking it, and finishes WITHOUT unmounting:

    handle obtained without yield : true
    body continued past the take  : true
    body finished (no unmount)    : true

And a frame that yields resumes where it left off and can still produce a value
(`stage=1 … stage=2, result=42`). So a handler may hold `cont`, carry on, and
either fall off (resume) or park it — **there is no mode switch**, and OCaml's
"the handler's value becomes the block's value once captured" does NOT have to be
imported along with the mechanism. OCaml reads that way because `capture` is a
CALL that unwinds; a BOUND name does not unwind.

That is what makes `cont` the right primitive rather than `capture {}`: three
runtime-bound names (`scope`, `resume`, `abort`) become two (`scope`, `cont`),
and the survivor is a value.

**4. Math inside a ROW-CARRYING function pays NOTHING.** The third listed
unknown. Two identical tail-recursive sums, one with no row and one declared
`-> Int <Noop>` and called inside a `perform` that installs a handler; neither
performs anything in the loop. One process, arms interleaved, n=2e7, three rounds:

    pure ps/iter: 298 264 232   sum=200000010000000
    row  ps/iter: 251 290 236   sum=200000010000000

Min 232 against 236 ps/iter, 1.7% apart with the ranges overlapping completely.
Identical checksums. A row is a type-level artifact; the arithmetic is the same
code. (`enableYield = true` on `MezzeBytecodeRootNode` costs nothing here.)

**5. BONUS, and it is the number that makes C affordable: THE TAX FOR MAKING
CAPTURE POSSIBLE IS ~8 ns PER `perform` BLOCK.** Today's dispatch is a direct
call with no continuation on the path at all, so `cont` requires the perform
block to run inside a `Continuation` — paid whether or not anybody captures.
Measured as a body run directly vs the same body inside `Continuation.run()`
that never yields:

| block work | direct | inside a Continuation | tax |
|---|---|---|---|
| 0 | 0 ns | 8 ns | **8** |
| 10 | 3 ns | 10 ns | **7** |
| 100 | 16 ns | 24 ns | **8** |
| 1000 | 159 ns | 183 ns | 24 |

**Per BLOCK, not per op** — a block containing a thousand ops pays it once, so it
is less than ONE effect op (~16 ns). Nesting does not compound: 1 to 8 nested
blocks all land in 43-87 ns.

So the "only wrap when the handler might capture" optimisation (statically
knowable — does any op body of the installed performer mention `cont`) is an
OPTION rather than a requirement. That was the thing most likely to make C cost
something for programs that never use it, and it does not.

**6. A FLAT USERLAND SCHEDULER WORKS — tested, not argued.** The author's
must-have ("I really want userland async for sure"; generators optional, "we have
iter"). Probed in the shape a Mezze `impl Async for Reactor` would compile to: a
shared run queue living outside any task, each task its own `Continuation` so
suspending one does not unwind the driver, `await` parking the task's
continuation in that queue, and a flat driver loop (pop, run, requeue if
`!isDone()`).

    trace   : A1 B1 C1 A2 B2 C2 A3 B3 Cdone Adone Bdone
    switches: 11
    max stack depth inside a task: 5
    INTERLEAVED: yes (round-robin, not run-to-completion)

Real round-robin, every task completed, and **max depth 5 is a maximum over all
11 switches** — the driver never nested. A trampoline, not recursion.

## THE RULE, SETTLED — and confinement is OUT

The scheduler above only works because the handle LEFT its own block: the queue
is the driver's and outlives every task whose continuation it holds. That is not
an artifact of the probe, it is structural — a scheduler's queue must outlive its
tasks. So "a `cont` may not leave its `perform` block" forbids precisely the
thing the language most wants, and it is REJECTED. (It had been the working
assumption for several rounds, on my argument, not the author's.)

What replaces it, both guards dynamic:

> A `cont` is resumable iff its computation is **suspended** — not completed, and
> not already resumed (one-shot) — **and** the scope it depends on is still alive.

The first half is coherence: a completed computation's frames already returned,
so there is nobody to return to twice. `isDone` is the flag. The second half is
the resource guard, enforced at `finalize_scope` rather than by the handle's
location, with precedent: `AsyncNatives.SuspendedOps` already reports suspended
ops "when a context closes holding one".

**What this costs, accepted deliberately:**

| | cost |
|---|---|
| resource safety | prevention -> DETECTION. A suspended task can outlive its scope; the best we get is a loud failure at a defined kill point. The weakest of this document's three options for the `HttpClient` pool. |
| leaks | bounded -> GC-bounded. An escaped continuation never resumed holds its frames and everything they reference; block exit no longer bounds it. |
| `finalize_scope` | needs a decision it did not need before: finalize with suspended continuations outstanding, or refuse, or kill them. |
| **Phase B** | becomes a HARD PREREQUISITE, not an optimisation. Handlers live on a per-thread stack; a continuation resumed later, possibly on another thread, after that chain has popped, must CARRY ITS HANDLER CONTEXT. That is B. |
| static story | gone for resource safety. The `runST` region trick needed confinement. One-shot keeps its linear-types path; scope safety is dynamic forever. |

Bought: userland async (the requirement), and generators/streams free with it.

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
