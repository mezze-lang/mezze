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
