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

### DECIDED — a PERFORMER performs `Time`; the obligation surfaces at the ENTRY

Four positions in one conversation, three of them mine and wrong. Recorded in
full because the reasoning is the useful part.

1. *Keep `Time` out of the declaration so a performer can bundle and hide it.*
2. *The abstraction should declare `<Time>` on its waiting ops* — then narrowed
   to the deadline ops only, since `await` waits on an EVENT.
3. *The clock is a performer PARAM* — because "you cannot exempt `LoomAsync`'s
   callers while obliging a test scheduler's".
4. **FINAL, and the author's position throughout: the PERFORMER simply performs
   `Time`. `effect Async` declares nothing.**

**Position 3 died on a capability I had not checked.** A performer CAN perform an
effect its op declaration never mentions, and an outer performer handles it:

    effect Jobs where { go: { n: Int } -> Int <Jobs>, }          ## no Ticker

    impl Jobs for TimeHungry where {
      go = { n } -> n + Ticker.tick_ms {},                        ## performs Ticker anyway
    }

    pub let main = {} -> perform Console, FixedTicker { at = 500 }, TimeHungry {} in
      Jobs.go { n = 7 }
    → got 507

So the obligation IS per-performer. Measured properties:

| | |
|---|---|
| `effect Async`'s rows | **unchanged** |
| intermediate annotations | **unchanged** — `let middle: { n: Int } -> Int <Jobs>` still checks |
| `LoomAsync` callers | **no obligation**, it performs no `Time` |
| `FakeAsync` callers | must install a `Time` performer |
| forgetting it | **COMPILE ERROR**: *"Entry 'main' performs Ticker, but nothing discharges it. The entry has no enclosing handler, so it leaks at runtime"* |

    ## production
    perform Console, LoomAsync in program {}

    ## deterministic test — FakeAsync performs Time, MockTime answers it
    perform Console, MockTime { clock = clock }, FakeAsync {} in program {}

No param plumbing, no row changes, no annotation churn, and the missing-clock
case is caught at compile time. Phase A stays the cheap non-breaking phase.

**The honest caveat:** the obligation is INVISIBLE IN INTERMEDIATE SIGNATURES. A
library function typed `<Async>` does not reveal that it will need a clock under
some performers — you find out when assembling the entry. That is the known
widen-and-leak shape; here it costs ergonomics, not soundness, because the entry
check catches it.

### MEASURED END TO END: what swapping a performer actually buys

One program, run twice, changing only the performer list. This is the clearest
statement of why the design is worth building, and both halves are measured.

    let fetch = { url, ms } -> do
      sleep { ms }
      "$(url) replied"

    let main = {} -> do
      let clock = test_clock_at { i = epoch {} }
      perform Console, LoomAsync, MockTime { clock = clock } in do
        let fast = Async.spawn { f = {} -> fetch { url = "eu", ms = 10 } }
        let slow = Async.spawn { f = {} -> fetch { url = "us", ms = 500 } }
        match [fast, slow].race {} is
          Ok { val } -> println { msg = val }
          Err { error } -> println { msg = "both failed: $(error.to_dbg {})" }

(Checked and run. Note what the surface already permits: comma-separated
performers in ONE `perform`, field punning in `sleep { ms }`, `#` as well as
`##` comments, and `[fast, slow].race {}` dispatching a `{ ps: [Promise 'a] }`
function method-style on its first parameter.)

With `SystemTime`: prints `eu replied`, and does NOT wait for the slow task.
With only the Time performer swapped to `MockTime`:

    eu replied
    virtual clock now: 510ms

both sleeps virtual, 510 ms of simulated time in ~0 ms real.

**And 510 is the finding.** Under real concurrency the answer should be
`max(10, 500) = 500` — the sleeps OVERLAP. It is 510 because `MockTime.sleep`
ADVANCES a shared clock instead of waiting, so concurrent sleeps ACCUMULATE.

| swap | available | what it gives |
|---|---|---|
| `SystemTime` -> `MockTime` | **today** | virtual time: fast tests, no real waiting |
| `LoomAsync` -> a cont scheduler | **needs this design** | determinism, AND correct virtual-time semantics |

So MockTime with real threads is fast but NOT deterministic — the threads still
race and the clock accumulates. A single-threaded scheduler that advances the
clock TO THE EARLIEST DEADLINE from `drive` is what makes concurrent sleeps
overlap properly and the outcome reproducible. That is the
`drive`-advances-the-clock refinement above, now with a measured reason to want
it rather than an argued one.

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
`perform CoopAsync {} in body {}` must not evaluate to "suspended", or every
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

    impl Async for CoopAsync where {
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

## Execution risks — what would surprise you, measured where countable

Written before starting, because two of these are bigger than the design
narrative implies.

### 1. PARKING IS NOT ASYNC-ONLY, and the machine cannot live in the scheduler's scope

`sleep` performs **`<Time>`**, not `<Async>`:

    pub let sleep: { ms: Int } -> {} <Time> = { ms } ->
      Time.sleep { d = duration_millis { n = ms } }

So a **Time performer parks a task that the Async machine owns.** `cont` is
available in ANY performer op body, so any effect can park. That breaks the
convenient story that the machine belongs to the scheduler's scope: it has to be
reachable from every performer, which means it belongs to the CONTEXT (or the
fiber), with scopes holding only the set of task ids suspended inside them.

This is the sharpest architectural gap in the design and the thing most likely
to force a rewrite mid-implementation. Settle it first.

### 2. C4 IS ~290 NATIVE METHODS — but concentrated in NINE files

Counted authoritatively from the Java side: **327 methods take a
`MezzeFunction` parameter across 32 files**; minus the 37 in
`MezzeBytecodeRootNode` (the interpreter, not natives) that is **~290 native
methods receiving a guest callback.** An earlier stdlib-grep proxy said 154 and
was wrong low; "map and fold" was wrong by two orders.

The distribution is what makes it tractable:

      57  SeqNatives        26  DequeNatives       16  IterNatives
      43  ListNatives       25  SortedSetNatives   11  NonEmptyListNatives
      31  MapNatives        24  SortedMapNatives
      26  SetNatives

~260 of them sit in NINE collection files with THE SAME SHAPE — a loop calling a
callback per element. So C4 is not 290 bespoke rewrites: it is one shared
resumable-iteration helper applied across nine files, plus a decision (resumable
or fail-loudly) for the long tail.

### 2b. ONE EFFECT PER PERFORMER NAME — but a scheduler CAN still bundle a clock

Measured: `impl Ticker for FakeWorld` together with `impl Jobs for FakeWorld` is
**V0701 "Duplicate effect handler declaration: performer 'FakeWorld' is already
defined"**, with or without the scope hooks duplicated. A performer name
implements exactly one effect.

I first concluded from this that a test setup must be TWO performs and that a
fake scheduler could not bundle a clock. **Wrong — the author corrected it, and
it is measured.** A handler body is ordinary code, so it can INSTALL a performer:

    impl Clock for FakeClock { base: Int } where {
      initialize_scope = {} -> {},
      finalize_scope = {} -> {},
      now_ms = {} -> base,
    }

    impl Jobs for FakeAsync where {
      initialize_scope = {} -> { clock = 4200 },
      finalize_scope = {} -> {},
      run_job = { n } -> perform FakeClock { base = scope.clock } in do
        let t = Clock.now_ms {}
        n + t,
    }

    pub let main = {} -> perform Console in perform FakeAsync {} in do
      let r = Jobs.run_job { n = 8 }           ## never installs a Clock
      println { msg = "job + clock = $(r.to_str {})" }
    → job + clock = 4208

So the user writes ONE perform, `perform FakeAsync {} in program {}`. And because
**`drive` is what RUNS the tasks**, wrapping task execution in the bundled clock
covers every `Time` the tasks perform — including the root body, provided the
root body is itself a task.

**The caveat the probe exposes:** `initialize_scope` runs PER `perform`, so a
clock installed inside a handler gets a fresh scope on every invocation. The
mutable clock state must therefore live in the SCHEDULER's scope and be passed
in as a performer param — a ref, not a value, for a clock that advances.

**Hiding versus exposing is a PER-IMPL choice, and all three postures are valid:**

**And the stdlib ALREADY HAS ALL OF THIS** — an earlier draft here invented a
`Clock` effect with an `advance_to` OP, which was wrong twice: the effect is
`Time`, and the control surface is a plain function over a VALUE, not an op.

    effect Time where {
      now: {} -> Instant <Time>,
      monotonic: {} -> Duration <Time>,
      sleep: { d: Duration } -> {} <Time>
    }

    impl Time for SystemTime where { … }
    impl Time for MockTime { clock: TestClock } where { … }
    impl Time for FrozenTime { fixed: Instant } where { … }

    pub newtype TestClock = __NativeTestClock
    pub let test_clock_at:      { i: Instant } -> TestClock
    pub let test_clock_now:     { c: TestClock } -> Instant
    pub let test_clock_advance: { c: TestClock, d: Duration } -> {}

`datetime.mz` even documents the intent: *"Use with MockTime in
std::effects::time to run tests where sleep advances the clock."* So the control
surface is an ordinary mutable value, which also dissolves the V0701 worry — it
never needed to be a second effect.

| posture | how | the test gets |
|---|---|---|
| **hide** | `FakeAsync` makes a `TestClock` in its own scope and installs `MockTime { clock = scope.clock }` around task execution | determinism with no knobs; cannot be wired wrong |
| **expose** | the TEST makes the `TestClock`, passes it to `MockTime`, and calls `test_clock_advance` on it directly | full control: jump time mid-scenario |
| **neither** | `LoomAsync` / `SystemTime` | real time from natives |

Both coexist over the SAME `Async` effect and a program switches at one `perform`
site. Which is the concrete argument for keeping the effect declaration clean:
**if `Time` were declared in `effect Async`, the hidden variant would be
impossible** — every program would have to install a clock, including the ones on
`LoomAsync` where it means nothing.

**And `drive` advancing the clock on idle is just a function call**, not an
effect op:

    drive = { runnable } -> match runnable.first_or_none {} is
      Some { t } -> t
      None {} -> do
        test_clock_advance { c = scope.clock, d = scope.until_earliest_deadline {} }
        runnable.first {}

**CAN A USERLAND SCHEDULER BYPASS `Time` ALTOGETHER? NO — measured.** This is
what makes the determinism story real rather than conventional. The native that
`SystemTime` itself uses is unreachable from user code:

    let sneaky_now = {} -> __native_instant_now {}
    → error[V0702]: Undefined variable or function: __native_instant_now

Natives are stdlib-only, so every clock read in userland goes through `Time`.
Therefore **a performer whose row is `<Async>` alone provably does not read a
clock**, and a test installing `MockTime` controls all of its time. A
third-party scheduler cannot quietly consult the wall clock.

The honest caveat: bypass is not impossible, it is VISIBLE. A scheduler could
read a clock through `Polyglot` (`Date.now()` in JS) or through the filesystem —
but each of those is itself an effect and appears in the row. There is no
invisible route, which is the property that matters.

The general rule: a performer needing another effect either bundles and
DISCHARGES it (caller sees nothing) or DECLARES it in the row (callers are
answerable). Performing without doing either is a performer widening past its
declaration, which is the known leak case.

**And the deadlock rule still refines**: idle means nothing runnable AND no timer
to advance, not simply an empty ready set — the standard
deterministic-simulation trick, which works only because deadlines go through
the `Time` effect rather than a runtime clock (see ParkOn above).

### 3. There are EXISTING parkers to migrate, and they are not all `await`

`semaphore.acquire`, `channel.recv`, `park`, `park_until`, `park_any`, STM
retry, `workflow.retry`, `http.retry`. Nine call sites of the park natives
across the stdlib. Semaphores and channels are also the vindication of keeping
NAMED WAIT SETS: each wants its own wakeup order.

### 4. BDSL CONTINUATIONS UNDER NATIVE-IMAGE — VERIFIED 2026-11-18

`mez build --native` through the native CLI (`:cli:nativeCompile`, after BUG 73's two
`@TruffleBoundary` fixes) of a yield loop, the bench-shape program (BUG 69/70/74 shape) and
`bench/cases/contloop`: all nine park kernels run as native binaries with correct checksums
(spawn+await, yield_now, park, fold/iter/list with awaiting callbacks, fan-out). The fast path
in native sits near the JVM numbers at 2M elements (control 1050 vs ~850 ps, seq fold eff 14.4 vs
~10 ns, iter pure 32 vs 33 ns). The paragraph below is the pre-verification note, kept for the record.

### 4-old. BDSL CONTINUATIONS UNDER NATIVE-IMAGE ARE UNVERIFIED

The AOT test in the measurement record below is **Loom's**, not BDSL's. The
BDSL prototype and the five suites added this session all run on the JVM. Since
AOT was billed as the top risk and then cleared, note precisely what was cleared:
Loom. `ParkOutcome` is a variant, so `just aot-parity` and
[[project_aot_variant_tag_dispatch]] apply to it. **Verify a BDSL yield under
native-image before relying on it.**

### 5. `drive` sits in the EFFECT DRIVER's hot path

Not just the parking path. A new branch in the driver's loop touches every
effect op, which measured 16-41 ns. Take a before/after on the effect-op
microbenchmark, not only on park/resume.

### 6. C1 changes an IR node's shape

Adding the effect row to `CallDirectIR` changes content hashes and therefore
declaration identity and cache validity ([[project_decl_identity_resolution_aware]]).
Expect churn. And `EmitFileSizeRatchetTest` trips on `TypedAstBuilder.kt` /
`TypedASTCompiler.kt` growth — it fired twice in one session already.

### 7. Three places where a park is impossible and must fail LOUDLY

- the **module top-level body**, which is not BDSL-compiled (`__entry__` still
  uses the AST path), so a top-level `perform` cannot park; **moot in a build
  (verified 2026-11-17): a bare top-level expression is already V0710** — "wrap it
  in a function and use that as the entry point" — so no program the CLI accepts
  can reach this. The test harness evaluates top-level expressions, and there the
  boundary runs through BDSL (every scheduler test is written that way);
- **comptime**, where `perform ComptimeEvalCodebase` runs at compile time;
- **across a polyglot boundary**, where a Mezze callback invoked from JS or
  Python sits under foreign frames that cannot be suspended.

### 8. `scoped` is unexamined

Structured concurrency takes `{ sc: Scope 'a }` and brackets child tasks. How it
interacts with the machine's states — particularly `Finalizing` — has not been
worked through.

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

### Phase A — make the ops interceptable. Stdlib only. **LANDED 2026-11-17.**

`await`, `cancel`, `yield_now`, `park`, `park_until` are ops of `effect Async`;
the plain names are forwarders (`await = { p } -> Async.await { p = p }`), so the
8 stdlib callers and 89 test files are unchanged. The four shipped performers
body each op with the native the plain `let` used to call. Measured: a userland
performer written with NO natives (`spawn = { f } -> promise_of { val = f {} }`,
`await` reading `inspect`) intercepts `await` — `AsyncOpsPromotedTest`; a
performer omitting an op is V0404 naming it — `AsyncOpsPromotedSpecTest`. Gates:
typechecker green, interpreter 8185/0.

**Found on the way, fixed as BUG 62:** every impl body (performer, ability,
inherent) bypassed the #109 stdlib-only native gate, so user code could call
`__native_instant_now` from inside a performer whose row said `<Async>` alone —
which would have voided the determinism argument in *CAN A USERLAND SCHEDULER
BYPASS `Time`*. The gate now reaches impl bodies. One test that ASSERTED the
hole (`InherentImplTest`, "inherent impl with native function call in body") now
asserts the refusal.


Promote `await`, `yield_now`, `park`, `park_until`, `cancel` to ops of `Async`.
**The source makes the case:** `async.mz:342` declares `spawn` as the SOLE op,
and `await` (56), `cancel` (89), `yield_now` (126), `park` (129),
`park_until` (144) are plain `pub let`s straight to natives. That is why the four
existing performers — `LoomAsync`, `LoomAsyncWith`, `OSThreadAsync`,
`OSThreadAsyncWith` — differ ONLY in `spawn`: it is the only thing they are
allowed to differ in.

No runtime work, no continuation, reversible, independent of B and C.

### Phase B — handler evidence travels with the call.

**PLAN, written 2026-11-18 after D1–D4 and C4, before any code.** What the runtime
established and what it measured decide the shape:

- A task today runs under the chain of the boundary that DRIVES it (`TaskMachine`,
  stated limitation). Correct when the task was spawned in that boundary's body under
  the same handlers — every test so far — and wrong the moment a task spawned under a
  nested non-cont `perform` is driven after that perform exited, or when a Loom-on-top
  performer resumes a task on another carrier.
- Swapping the thread's chain per task switch is NOT the fix. Measured facts:
  `installHandlerChainEntries` deliberately does not invalidate the per-effect
  assumptions (an inherited chain is not a registration) and relies on
  `noteEffectStateThread()` dropping `singleThreaded`, so `PerformD` falls to
  `doCachedShared` and re-resolves per call. On ONE carrier a per-switch swap would
  leave `doCachedSingleThreaded`'s cached handler stale — a silently wrong handler —
  unless every switch invalidates, which is the PIC churn ("churnedTier") the design
  warned of in risk 5.
- Therefore: the evidence has to be a VALUE a task carries, and dispatch caches have to
  key on it. The precedent is already in the tree: `HandlerBoundFunction` (S-20) binds
  a chain into a function value and re-installs it per call. Phase B generalises that
  to the task: `Task.chain` captured at spawn (`snapshotHandlerChainEntries`), the
  machine makes it the CURRENT chain for the duration of a run — but as a per-context
  "current task chain" pointer that `PerformD` reads through its node, with a cache
  keyed on chain identity (one `Assumption` per chain object, invalidated only when
  that chain itself is pushed to / popped), not on the thread's EffectState.
- Cost model to hold: a pure program with no tasks keeps `doCachedSingleThreaded`
  exactly as today (the pointer is null, the path is untouched); a program with tasks
  pays one field read per perform op on the task path. Measure on `effectsurface`
  kernel 3 before and after; the number that must not move is kernel 1.
- Order: (1) `Task.chain` + machine installs it as the current-task chain around
  `run` and `resume`; (2) `PerformD` resolves through the current-task chain when one
  is set, assumption per chain; (3) tests: a task spawned under a nested `perform` and
  driven after it exits dispatches against its spawn-time handler; two tasks under
  different inner performers interleave and each sees its own; (4) the Loom-on-top
  performer: `spawn` hands a machine task to a virtual-thread carrier, `await` parks —
  the first performer that needs Phase B to be correct, and the measurement of
  "BDSL cont is the core, Loom is an impl on top".

**Steps (1) and (2) LANDED 2026-11-18.** Verified the break first: a task spawned under
`perform B in …` and driven after that perform exited answered `A` — the driving
boundary's innermost handler — not `B` (`[A, A]`). Now `Task.chain` is the snapshot at
spawn (the body task's at boundary entry) and the machine installs it as the thread's
CURRENT TASK CHAIN around every run, resume and predicate evaluation
(`HandlerChainRegistry.installTaskChain` / `restoreTaskChain`). Resolution
(`peekHandlerForEffect`) walks the entries pushed SINCE the task chain was installed —
a `perform` inside the task body — and then the task's chain; snapshots compose the
same way, so a task spawned from inside a task inherits the effective chain. The memo
keys on the task chain's identity. Installing a task chain drops the single-threaded
assumption once — the same event as a second thread acquiring handlers — so the cached
dispatch tier falls to the guarded `doCachedShared`, which re-resolves through the task
chain; a program without tasks never installs one and keeps `doCachedSingleThreaded`
untouched — measured on `effectsurface`, two interleaved rounds: control 218/220 → 225/232 ps, one perform op 898/818 → 896/851 ps; noise. Asserted:
`[B, A]`; two tasks under different inner performers interleave and each sees its own.
Step (3) is these tests; step (4), the Loom-on-top performer, is next.

What the design said before the plan, kept:


Pass `resume`/`abort` through the handler call instead of a `@TruffleBoundary`
ThreadLocal. ~41 -> ~16 ns, and it kills the silent cross-thread wrong-handler
read (a wrong VALUE, never an exception).

**Promoted from optimisation to prerequisite by measurement:** a BDSL
continuation IS thread-portable (`BdslContinuationCrossThreadTest`: suspended on
one executor, resumed on another, correct result), so a layered executor can
parallelise — but the handler chain is per-thread, so the evidence must travel
with the continuation rather than be read off whichever thread resumes it.

### Phase C — composable suspension. FOUR parts; three are PROVEN, the gating one is not built.

**C1 — carry "can this call suspend" on the call IR. LANDED 2026-11-17.**
`maySuspend: Boolean = false` on `CallDirectIR`, `CallLocalIR`, `CallIndirectIR`,
`CallIndirectScalarIR`, `DictMethodCallIR`, set by `TypedASTCompiler` from the
callee's solved row (`SuspendSites.kt`: named effect or open tail → `true`;
`<*>`-only → `false`; a wrapper whose target row is not in hand → `true`,
conservatively). Defaulted, so serialized IR read back unchanged — the churn
risk 6 predicted did not materialise; only the file-size ratchet fired, and the
rule moved to its own file. Asserted on the IR in `CallMaySuspendIRTest`:
pure `false`, effectful `true`, `<*>` `false`, callback parameter `true`.
Gates: typechecker green, interpreter 8185/0.

What it said before it landed, kept for the reasoning: `CallDirectIR` carries `targetHash`, `args`, `isTailCall`,
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

**C2 + C3 + the first `cont.park` — LANDED 2026-11-17 as one vertical slice, in
real compiled Mezze.** `ContParkPropagationTest`: a performer op body calls
`cont.park`, the op body's root suspends, `PerformD` yields the task's
continuation, `inner = {} -> Wait.wait {} + 1` and `outer = {} -> inner {} + 100`
both suspend and both tails run — 108 — and two parks in one body resume in
order. Pieces, each where the design said:

- `cont` is a runtime-bound name in performer op bodies, like `scope`
  (`EffectBindingKind.CONT`, bound by `EffectHandlerBindings.extendWithCont`;
  V0702 anywhere else — checked). Its types `ParkOn`, `WaitSet`, `ParkOutcome`
  and the three `newtype` ids live in `std::effects::async`, referenced by
  identity so any module's performer sees them.
- `cont.park` returns a `ParkRequest`; the lowering treats it as a suspension.
  Its call site is flagged by a KEYWORD rule (`cont.park` suspends by
  definition) — the open row bound on its type did not survive into the typed
  AST the compiler reads, which is a loose end worth closing, not a blocker.
- Propagation: `emitWithSuspendPropagation` wraps every `maySuspend` non-tail
  call and every perform op in `local = call; if IsSuspension(local) then
  Yield(local) else local`. Two traps, both measured: the inliner splices the SAME
  node object into other roots, so a mark that outlived one wrap made the second
  emission skip it and a continuation reached `+ 1` as a value; and a mark kept on
  the SHARED compiler at all raced between threads compiling lambdas lazily under
  `LoomAsync` (BUG 66, one lambda in four failed to compile). There is no mark now:
  the gate and the node's body are two methods.
- The boundary: a perform over a `cont`-capable performer (syntactic check of
  its op bodies, `TypedHandlerRef.contCapable`) compiles its body as its OWN
  root — a BDSL yield suspends a root, and the boundary must be the frame that
  catches it — called under `DriveParked`, the stack driver from the prototype,
  INSIDE the handler pushes so a resumed op body dispatches against this
  perform's chain. A performer that never says `cont` pays nothing (asserted).
- Policy is absent on purpose: `DriveParked` answers every park at once with
  `ParkReady { which = 0 }`. The scheduler (`drive`, wait sets, the task machine
  in the CONTEXT per risk 1) replaces that one line.
- The typechecker side is a spec, `ContBindingSpecTest`: `cont` is V0702 outside
  a performer op body (an ability impl included); `cont.park` is a `ParkOutcome`
  whose match must cover `ParkCancelled` (V0601); `cont.abort`'s payload is
  checked against the perform's type exactly like the plain `abort`. Writing it
  found **BUG 64**: `cont.park { on = … }` with `set` omitted type-checked,
  because a performer op body dropped every solver failure that mentioned a type
  variable — `let x: Int = c.f { nope = 1 }` compiled inside any performer. Fixed
  and specced (`PerformerBodySolveErrorsSpecTest`); the stdlib's 957 impl ops
  all still check under the honest rule.

**D1 — the task machine, `drive`, and a scheduler written in Mezze. LANDED
2026-11-17.** `CoopAsyncSchedulerTest`, all on ONE thread, interleavings asserted
as strings: `CoopAsync` (stdlib, FIFO) runs two yielding workers `a1 b1 a2 b2 a3
b3`; a userland performer with `drive = { runnable } -> runnable.last {}` — no
native of its own — runs the same program `b1 b2 b3 a1 a2 a3`; `await` parks and
resumes with the value through a nested await (42); `park { predicate }` wakes on
another task's progress; nothing runnable + nothing that can wake is a DEADLOCK
error naming the tasks and what they wait on, and an EMPTY wait set is one.

- `TaskMachine` lives on the `MezzeContext` (risk 1 settled as the design said:
  any performer may park, so the machine is reachable from all of them). A task's
  suspension is a STACK of continuations (C3); resume = `leaf.continueWith(outcome)`
  then hand values outward until a frame suspends again or the task is done.
  Delivery is never re-entrant (open rule 1): a run returns when the task next
  suspends; the loop is flat.
- `drive` is the third impl-only field (`ScopedEffectConstants.DRIVE`; typed
  `{ runnable: RunQueue } -> Task`, `DriveFieldSpecTest`), a SELECTOR over an
  opaque O(1) view (`RunQueue.first/last/len/at`). No `drive` = FIFO. A selector
  that returns anything but a runnable task is an error.
- L1 mechanism in `std::effects::async`: `spawn_task` (the ONE native a scheduler
  needs), `promise_id`, `settled_result`, the `RunQueue`/`Task` handles. The
  `CoopAsync` performer is otherwise plain Mezze: `await` = `cont.park` on
  `OnPromises`, `yield_now` = park on `[{} -> True]`, `park` = park on the
  predicate.
- Wakes are a scan of parked tasks after every run (registered O(1) wake is an
  optimisation over this, same semantics). A promise settling on another thread
  (Loom-backed) is waited for; only "no external promise pending" is a deadlock.

**Risk 5 measured** (`bench/scripts/ab.sh one effectsurface 1 3 4`, same build,
`MEZZE_SUSPEND_PROPAGATION=0` as the run-time kill switch, two interleaved
rounds): the per-perform-op wrapper costs nothing visible — op kernel 902/1019 ps
with it vs 975/1038 without, control 229–267 vs 220, explicit resume noisier both
ways. The check is one type test on a local; the JIT folds it.

**D2 — boundaries own their tasks; scope teardown. LANDED 2026-11-17.** The
machine now runs the WHOLE boundary lifetime: `DriveParked` receives the body's
thunk, the machine pushes a `Boundary`, calls the body as its task, drives, tears
down, pops. A boundary has to exist before the body runs because `spawn` happens
inside it, before any park — the first cut (drive after the body suspended) could
not know who owned a task. Settled by code, three of the open rules:

- *Nested performers* (rule 2): an inner boundary drives ONLY the tasks spawned
  under it; an outer task is never run or cancelled by an inner teardown
  (asserted: `u, mid, t`).
- *Teardown* (rule 3): when the body is done, leftover runnable tasks finish
  (`end, late, out` — a never-awaited task still completes before the perform
  returns), tasks still parked are delivered `ParkCancelled { reason =
  CancelScopeTeardown }` and finish (`end, cancelled`), and a task that keeps
  re-parking during teardown is an error after 8 rounds — the bound the rule
  asked for. `finalize_scope` runs after, outside the boundary, as before.
- *Auto-registration* (rule 4) is answered by ownership: a task belongs to the
  innermost boundary at its spawn; there is nothing to register.

**D3 — cancellation reaches a park; deadlines are `Time` predicates. LANDED
2026-11-17.** `Async.cancel` on a machine task's promise wakes the task, if parked,
with `ParkCancelled { reason }` (the promise's cancel hook; the performer's arm
decides what cancellation means — asserted `cancel-sent, woke`). `CoopAsync`'s
`park_until` parks on TWO predicates, the caller's and `{} -> after { now, deadline }`,
so the performer performs `Time` — and the DECIDED obligation surfaces exactly as
written above, verified on the CLI: `perform Console, CoopAsync in …` as an entry
is **V0501 "Entry 'main' performs Time, but nothing discharges it"**, `perform
Console, SystemTime, CoopAsync` is clean, and `LoomAsync` carries no obligation.
With `MockTime` the whole run is deterministic: a task advances the clock, the
waiter times out (`advanced, timed-out=False`). Rule 5 (stale resumers) is moot in
this machine: a task has exactly one leaf continuation and the machine holds it.

**D4 — a park that cannot reach its boundary is an ERROR, twice over. LANDED
2026-11-17.** Measured first: `seq::for_each { f = { x } -> yield_now {} }` under
`CoopAsync` printed `before 1, before 2, done` — the native received the callback's
continuation as a value and the rest of the callback was silently lost (risk 7's
family; the C4 gap). Two detectors, front door and backstop:

- **V0513 at compile time** (`ParkThroughNativeSpecTest`), decided from TYPES, not
  names: the callee is a native wrapper (its lowered body is a `CallNativeIR`); it
  RUNS the callback during the call — the callback parameter's row variable is the
  callee's own (`for_each: { …, f: … <ρ> } -> {} <ρ>`; `spawn_task: { f: … <ρ> } ->
  Promise 'a` does not share it, so a deferred callback is never flagged); and the
  callback's body performs an effect this compile unit installs a cont-capable
  performer for. Under `LoomAsync` the same program compiles. The callback's effects
  are read from its BODY — a lambda literal's row lives on the enclosing function,
  its own `FunctionType` carries none. Scope: the primary module's own call sites
  against its own performers (the compiler instance is shared across modules and
  carries imports as `additionalModules` under file paths).
- **The runtime backstop**: the machine counts park requests created against parks
  absorbed at a boundary and reports any gap after every run, naming the cause and
  the fix (`CoopAsyncSchedulerTest`, reaching the native through a function value
  the static rule cannot see). `cont.park` with no boundary at all — top level,
  comptime, a native or polyglot frame — is an error at the park.

Refined the same day after a CLI probe of the three common spellings:

- **Op-granular.** The first cut flagged `seq::map { f = { x } -> Async.spawn … }`
  — `spawn` is an op of `Async`, but `CoopAsync`'s `spawn` never parks. The rule now
  carries the performer's `contOps` (the ops whose bodies mention `cont` — the only
  places a park can originate, since `cont` is V0702 anywhere else) and the
  callback's reach at `(effect, op)` granularity: a `TypedPerformOp` names its op, a
  forwarder (`await { p }` = `Async.await { p }`) is looked into through its lowered
  `PerformOpIR`, any other effectful call counts as "some op of that effect".
- **The method-chain spelling** (`ps.map { f = { x } -> await … }`) is not seen by
  V0513 — an inherent op sits between the user and the native — and the native
  stores the continuation IN ITS RESULT, so the first thing to break was a raw
  `ClassCastException: ContinuationResult cannot be cast to Long` reaching the user.
  The machine now diagnoses a host exception raised during a run with an
  unabsorbed park as the dropped park (the exception kept as cause). Seeing through
  inherent wrappers statically is the obvious next step for V0513.
- **`mez check` does not lower** ("Checking needs types, not IR"), so V0513 shows in
  `build`/`dev` only. Making it a `check` error needs the native-wrapper fact at the
  type level (a flag on the export entry, set when the stdlib compiles) — a decision
  for the author, since `check`'s no-lowering rule is deliberate.

BUG 65 (FIXED the same day): the async helpers themselves looped with native HOFs
over `await`/`cancel` — `race`, `all`, `try_all`, `try_any`, the bounded drain. They
go through `each_parking`/`map_parking` (plain recursion) now; `all`/`race` are
asserted under `CoopAsync`.

Stated limitations, each a later slice's subject: tasks run under the DRIVING
boundary's handler chain (Phase B lifts it); a `WaitNamed` set is accepted and
ignored; `sleep` under `CoopAsync` is still the Time performer's blocking sleep —
a cooperative `Time` performer that parks on the clock is the natural next
performer, and the first one written against `cont` outside `Async`.
Measured gotchas: `Mut` is hermetic and cannot share a row with `Async`, so the
tests log through Console; the test harness evaluates the module's top-level
expression and never calls `main`.

What C2 said before it landed, kept for the reasoning:
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

**C4 — MEASURED 2026-11-17: the alternative (BDSL twins) loses; resumable natives it
is.** The tempting shortcut — give every callback-taking native a Mezze/BDSL twin and
select it at the call site when the callback may park — was measured on `fold` over
2M elements with the stdlib's own unchecked-access loop (`fold_go`), two interleaved
rounds, ps/element: pure callback native 409/517 vs twin 10,147/51,835 (the fused
path, never redirected, so irrelevant); **effectful callback native 12,142/20,800 vs
twin 26,554/28,336 — 1.4–2.2x slower.** A guest call per element is the floor either
way, and the recursive BDSL loop adds its own argument plumbing on top. Not parity,
so not acceptable: the only shape that keeps the native loop at native speed is the
one below — one `instanceof` per callback result on the fast path, heap state only
when a callback actually suspends, the machine driving the native's resumption like
any other frame. The earlier shortcut considered and dropped the same day: a nested
drive inside the callback (pins the native's Java frame; re-enters `drive`).

**C4 — LANDED 2026-11-17 for the seq family, in the shape the design said.** A
native loop that gets a suspension back from a callback returns a `NativeSuspension
{ inner, resume }`: `inner` is what the callback handed back, `resume(v)` re-enters
the SAME loop at the next element with the callback's eventual value (fold, map,
for_each, filter, any/all/find — the predicate scan is one shared loop; `none` maps
over the result). The task machine treats a `NativeSuspension` as one more frame
kind in a task's stack beside `ContinuationResult`. The BDSL site around a native
call gets the existing propagation wrapper when the argument record carries an
effectful function (`CallNativeIR.maySuspend`, set ONCE in the stdlib wrapper for
every caller; a pure callback costs nothing). Nothing is allocated unless a callback
actually suspends; the fast path of a resumable native is one `instanceof` per
callback result.

`ResumableNatives.NAMES` (ast) is the one list two readers share — the interpreter
(handlers written in the resumable shape; `ResumableNativesRegistryTest`) and the
typechecker (V0513 lets a parking callback through to these). `scan` is not on it and
fails loudly if its folder parks.

Asserted (`CoopAsyncSchedulerTest`): `await` inside `map` over promises (the spelling
that reached the user as a `ClassCastException` before); two tasks yielding inside
`map` interleave element by element — `a1 b10 a2 b20 a3 b30` — each loop resuming
at its own index; fold/filter/any/find/for_each with an `await` in the callback.
Still refused/diagnosed: `flat_map` and every native not on the list.

**Fast path measured** (2M elements, `MEZZE_RESUMABLE_NATIVES=0` as the run-time
kill switch, three interleaved rounds, ps/element): fold pure 515/564 after vs
409–717 before; fold effectful 16.9k/22.5k vs 12.1k–26.5k; map pure ON 26.1k/24.2k/
22.2k vs OFF 24.3k/25.8k/17.0k; map effectful ON 36.6k/51.2k/36.9k vs OFF 41.0k/
39.5k/36.0k. The per-result `instanceof` is inside the noise everywhere — "costs
nothing structural" held up in the real natives, not only the prototype.

**C4 for `iter` — LANDED 2026-11-18, by routing, not by rewriting the walker.** The
iter consumers are PUSH-based (`walkElements` with nested consumer lambdas; the walk's
state is the Java stack), which cannot resume; only the pull-based `IterCursor` keeps
the chain's state on the heap. Measured (`range.map.fold`, 2M, three rounds): pure
callback 404–552 ps either way (the fused path); effectful callback **cursor +15–20%**
over the push walk (11.9–16.0k vs 10.4–13.5k ps). So the cursor is used ONLY when
something in the call may suspend, decided at run time from a fact stamped at compile
time: `FunctionIR.maySuspend` (a perform op, a C1-flagged call, a native with an
effectful callback, or a nested lambda that may) is stamped onto the bytecode root and
read through trampolines and lazy stubs by `SuspendFacts.mayFunctionSuspend` — one
boolean per native call, not per element. `IterNatives.resumable(it, fn)` = the
consumer's callback or any callback in the chain's descriptor may suspend; the three
BDSL iter ops' cached specialisations carry the same guard, so a parking callback
falls to the uncached path and the cursor. Every cursor stage keeps its own pending
step (`next` may return a suspension; `resume(v)` completes the step): map, filter,
take_while, drop_while, flat_map, unfold with their callbacks; take, drop, enumerate,
chain, zip pass suspensions through. Consumers are sinks over one `pull` driver:
fold, reduce, for_each, count, any/all/find, to_seq, to_list, first, last. `sum` and
`count_all` stay push-only (long-typed op; a parking chain under them is the runtime
detector's). Asserted (`CoopAsyncSchedulerTest`): awaits in map/filter/take_while/
flat_map stages under fold/to_seq/count/find; two tasks yielding inside
`iter::for_each` interleave `a1 b1 a2 b2`. After routing: pure 457–508 ps unchanged,
effectful auto-routed at 11.7–22.6k.

**C4 for the rest of `seq` and for `list` — LANDED 2026-11-18 on one shared driver.**
`ResumableLoops.run(start, n, body)`: an indexed native loop whose state is a small
`Body` object built once per call; a suspension at element `i` becomes a
`NativeSuspension` whose resume delivers the callback's value to `accept(i, v)` and
continues at `i + 1`. seq in place: `find_index`, `try_map`, `reduce`, `count`,
`partition`, `take_while`, `drop_while`, `flat_map`, `scan`, `group_by`, `none`. list:
the array-based loops (`map`, `filter`, `partition`, `for_each`, `flat_map`) in place;
the CONS WALKS (`fold_left`/`fold_right`/`reduce`, `find`/`find_index`/`any`/`all`) stay
as they are for pure callbacks and fold over the array only when
`SuspendFacts.mayFunctionSuspend(fn)` — the list walk is the measured cost
(`project_list_walk_is_the_cost`), so it is not paid twice. Still outside C4, and
therefore still refused by V0513 / diagnosed at run time: the key/comparator natives
(`sort_by`, `sort_by_ord`, `min_by`, `max_by`, `dedup`, `unique`, `group_by_with_evidence`,
`last_index_of`) and the concurrency natives. `ResumableNatives.NAMES` lists what is in.
Asserted: awaits inside every ported native under `CoopAsync`, one program.

Two findings on the way. BUG 67 (OPEN, unrelated) — `to_dbg` on a sorted seq of
promises is an internal compiler error (#394 family). And V0513's wrapper test looked
one hop deep: `seq::min_by` is `min_by` → `min_by_with_cmp` → native, and the method
spelling `xs.sort_by { … }` is inherent op → `seq::sort_by` → native. **Closed the same
day:** the test follows direct-call hops (three is as deep as the stdlib goes), and a
row-less callee — an inherent op resolved to its hash carries no row — is decided from
registration instead of its type: the chain's native runs its callbacks unless it is
one of the DEFERRING natives (`ResumableNatives.DEFERRING`, the spawn family). Both
spellings are now V0513 at build; the runtime diagnosis keeps the function-value route.

**THE CARRIER MACHINE — REDESIGNED AND LANDED 2026-11-19. A suspended task is a parked virtual
thread, not a chain of BDSL continuations.** Why: the BDSL design's park floor was boxing
elimination — `long` slots across continuation frames made the interpreter's compiled helpers
not-entrant on every resume (386 ns a yield with BE on, 98 off; BE is a 3.8x win on loops and stays).
And every "a park cannot get through X" case (comparator natives, Java callbacks, polyglot frames,
the V0513 rule, the resumable-native registry, the propagation wrappers, BUG 76's tier-1 miscompile,
BUG 75's re-parse trigger) existed only because a continuation has to be CAPTURED through every frame.
Now nothing is captured: each task runs on a CARRIER (a pooled virtual thread entered through
`Env.newTruffleThreadBuilder`), exactly one carrier holds the BATON at a time, and `cont.park` is a
blocking call on the carrier — it hands the baton to the next runnable task and waits. The thread that
entered the perform parks once, until the boundary finishes; the carrier giving up the baton runs the
scheduler step itself (pick → `drive` → hand over, or wake parked, or tear down / deadlock), so every
handoff is virtual-to-virtual. Sequential semantics are unchanged (never two tasks at once, `drive`
stays deterministic, the per-task handler chain AND the performer scope variable travel with the task).
`TaskMachine.java` is the whole mechanism (~600 lines); `ContBinding.park` blocks; the propagation
wrappers (`MEZZE_SUSPEND_PROPAGATION=1`), the resumable natives (`MEZZE_RESUMABLE_NATIVES=1`) and
V0513 (`MEZZE_V0513=1`) are opt-in relics kept for the record.

What it took to get right, each found by a failing test: (1) context close waits for every polyglot
thread, so the carrier pool lives only while a boundary is open and is released when the last one
closes (plus `disposeContext` → `shutdownTaskMachine` as the backstop); (2) a boundary that FAILS
(deadlock, teardown exhaustion) with tasks still parked must unwind those carriers or the close hangs
— they are woken one at a time with an ABANDON marker that throws `BoundaryAbandoned` out of the park;
(3) `wakeParked` runs BEFORE `drive` picks, so a task that just yielded is back in the ready set the
scheduler sees (the LIFO-drive test caught the order); (4) the pool is LIFO so an ending carrier runs
the next fresh task itself — no thread switch; (5) a handoff through park/unpark alone costs ~400 ns
and, measured with JFR on contloop k2, 45% of the kernel sat in `ForkJoinPool.deactivate`/
`runWorker` — the baton is now SPUN on for up to 5 us (`Thread.onSpinWait`, both carriers stay
mounted) before a real park; off when the virtual-thread scheduler has one worker
(`MEZZE_COOP_SPIN_NANOS` overrides); computed per machine instance, not at image build time.

Measured (bench/cases/contloop, 2M elements, dev launcher, JVM; ns per element; BDSL design in
brackets): yield_now 44 [386]; park on `{} -> True` 70 [~400]; spawn+await 842 [906]; seq fold with
await 862, iter map→fold with await 890, list fold with await 884 [the resumable-native routes, 10–23 us
before tuning]; fan-out 216 [192]; LoomAsync spawn+await 8548 for reference. Spin off: spawn+await
2147. Fast path (contsurface, 20M, main vs branch): control 709/688, iter pure 1977/1333, 1124/1120,
seq fold eff 10030/9450, iter eff 20151/20264 — the dynamic tax is gone with the wrappers, and the
pure iter chain is FASTER than main because the resumable-route guards left the fused op. Tests:
277 across 40 async/continuation/scoped classes green; the two "a park inside a comparator is a loud
error" tests became "it just parks and the sort completes"; the V0513 spec became four acceptances;
`IterRouteDecisionTest` asserts NO cursor route even inside a boundary. Gates: typechecker 5502/5502,
interpreter 8253/8253 (incl. testTiming), cli 357/357. NATIVE IMAGE VERIFIED: `:cli:nativeCompile` builds, a
`mez build --native` of contloop runs every park shape — 200k elements: yield_now 113 ns, spawn+await
1151, fan-out 418, seq fold with await 1213 (no JIT warm-up curve to hide behind; the spin bound is
computed at run time, not image build time).

**Foreign threads and host waits, 2026-11-19 (second pass).** Two more "regardless of handler"
cases, each from a failing test in `ForeignThreadParkTest`: (1) a thread the machine does not drive — a
LoomAsync task, an HTTP handler, a polyglot callback — calls `cont.park` (or `spawn_task`) under a
cont-capable perform: it finds its boundary through ITS OWN handler chain (the handler record the perform
pushed is, by identity, the record in every chain snapshot beneath it; `openByHandler`), posts the park
or the spawn to the boundary's inbox and blocks on its own thread; the scheduler step drains the inbox,
evaluates the foreign park's wait set like any other and delivers the outcome by unparking that thread.
(2) a HOST-LEVEL wait on a carrier — a LoomAsync `await`, `sleep`, `park`/`park_until` backoff — used
to block the thread that holds the baton, so every other task of the boundary stalled behind it (seen
as a hang: a body awaiting a Loom promise whose Loom task was parked on the body's own boundary).
`MezzePromise.await`, the timed await, `sleep` and the backoff loops now ask `TaskMachine.onCarrier()`
and, on a carrier, park the TASK on the condition (`javaWait(BooleanSupplier)`) — the step re-checks it
like a predicate, the other tasks run, and the deadlock verdict treats it as external. Promise
bookkeeping moved from identity maps onto the promise (`machineId`, `machineOwned`).
Measured after this pass (contloop 2M, JVM, ns/elem): yield_now 39, park-True 73, spawn+await 773,
folds with await 771–792, fan-out 178, LoomAsync spawn+await 8531. Gates: typechecker 5502/5502,
interpreter 8257/8257, cli 357/357; `ForeignThreadParkTest` 4/4 (a Loom task parking on an outer
cont-capable performer, several at once while the body runs, a Loom task awaiting a Coop promise, a Loom
task spawning a cooperative task on the outer boundary).

**Idle hook, 2026-11-19 (third pass).** An idle boundary — every task parked on something only the
outside world settles — used to poll every 50 us. Now the idling carrier registers itself on each
external promise it waits on (`MezzePromise.machineWaiter`, woken by the same `unparkWaiter` as the
ordinary waiter), a host-level promise await parks the task on the PROMISE itself
(`awaitPromiseOnCarrier`), a time-based host wait carries its deadline (`Deadline`) so the idle park
lasts until the nearest one, a foreign post to the inbox unparks the idler, and a 1 ms fallback remains
for opaque host waits. Register-then-re-check keeps a settlement racing the registration from being
missed (both stores volatile). MultiTier re-measured with BUG 76 moot: 12/12 correct with it on, peak
equal (spawn+await 777 vs 771, yield 41 vs 39), first timed run of yield_now SLOWER with it on
(288 vs 51 ns at 200k) — the default stays `engine.MultiTier=false`. Native image re-verified after the
second pass (yield_now 89 ns, spawn+await 954, fan-out 673, seq fold with await 1030 at 200k).

**Relics removed, 2026-11-19 (fourth pass).** With the carrier machine nothing yields, so the
capture-era machinery was dead by construction and is gone: `NativeSuspension`, `SuspendFacts`, the
resumable natives (`ResumableLoops` is now `CallbackLoops`, a plain callback loop; the iter cursor
routes, sinks and stage-target guards; `ListNatives.foldResumable`; the seq natives' `chk` branches),
`OrderCallbacks` (an order callback that parks now just parks), the `AsObject`/`IsSuspension` BDSL
ops and the compiler's propagation gate, the registry's park-scope counters, and the typechecker's
V0513 ledger with its `ResumableNatives` name list; the relic-only tests went with them
(`ResumableNativeHofTest`, `ResumableNativesRegistryTest`, `IterRouteDecisionTest`). Kept: the C1
`maySuspend` IR flag and the root stamps (cheap metadata, no runtime consumer), the public
`iter::cursor` surface (its internal pending/resume plumbing is dead and awaits a rewrite), the
`V0513` error-code constant, and `ParkThroughNativeSpecTest` as a set of acceptances.
Fast path after the removal (contsurface 20M, quiet, best of 3 × 3 cells, main / pre-removal / post):
list fold pure 1470 / 1564–1667 / 1466–1592, iter eff 21081–21782 / 20429–22754 / 21286–21855, list fold
eff 12612–12890 / 12128–12467 / 12064–12323 — the removal is neutral; the ~6% list-fold-pure gap to main
PREDATES it (present at f1651c6cb) and is owed its own A/B against main's `foldTwoArg`. Gates:
typechecker 5502/5502, interpreter 8252/8252 + `ForeignThreadParkTest` 5/5 (its new race test needed a
cooperative op in the body: a `perform` whose body performs nothing the handler fires on is rejected).

**CORRECTION, 2026-11-19 — a like-for-like row the earlier table lacked.** The "folds with await
10–23 us" figure quoted above for the OLD design was its first, untuned, n=1000 measurement; the
tuned old design (78bac71bf, rebuilt in a scratch worktree, same bench binaries, 2M elements,
interleaved) measures: seq fold with await 410 ns, iter map→fold with await 381, list fold with await
376, spawn+await 844, yield_now 376, fan-out 197. Against the carrier machine's 793 / 804 / 790 / 788 /
40 / 173. So: yield_now 9.5x better, spawn+await and fan-out at parity or better, and the THREE FOLD
SHAPES 2x WORSE. The cause is structural: under the old design a park inside a native's callback
captured only the callback frame (the native held its loop on the heap), while a park out of a deep
Mezze recursion captured the whole chain — so folds were cheap and `go_spawn` dear; under the carrier
machine every park costs the same two handoffs (~300 ns each) whatever the depth. The fix in flight:
when a task awaits exactly ONE promise, that promise belongs to a task that has not started, and that
task is the only runnable one, run it INLINE on the awaiting carrier — zero handoffs, and the
scheduler's observable order is unchanged because a sole runnable is the only thing `drive` could
pick. See the next block for the result.

**The inline child, 2026-11-19 (fifth pass) — the fold shapes beat the old design 2x and spawn+await 4x.**
`TaskMachine.park` checks one precondition before parking: the request waits on exactly ONE promise,
that promise is settled by a task of this boundary that has not started (`MezzePromise.machineTask`),
that task is the ONLY runnable one, and the inbox is empty. Then the awaiting carrier runs the task
itself (`runInline`), nested on its own stack, settles the promise and continues — no handoff. Nothing
observable changes: the awaiter could not have run before a task it awaits, and a sole runnable is the
only pick `drive` could make. A nested park inside the inlined task uses the shared carrier's thread
(its `turn` flag is per task), a nested spawn+await nests again, a failure fails the promise, and an
abandoned boundary unwinds the pair in order. `InlineChildTest` pins those four. Measured (2M, JVM, ns;
old tuned design / carrier before / carrier now): spawn+await 844 / 788 / 203; seq fold with await
410 / 793 / 203; iter map→fold with await 381 / 804 / 215; list fold with await 376 / 790 / 210;
yield_now 376 / 40 / 40; park-True ~400 / 73 / 75; fan-out 197 / 173 / 176.

**The carrier budget — built, measured, PULLED, 2026-11-19.** A sixth pass added `carriers = {} -> N`
as a fourth impl-only field so an `Async` performer could run its tasks on N carriers at once
(`ParallelAsync`; a user performer with `carriers = {} -> 2`; four 80 ms sleeps in 81 ms vs 321; gates
green; native verified). It is pulled the same day on the user's call, following Eio: fibers in a domain
are cooperative and single-threaded, and parallel work goes through a separate capability
(`Domain_manager`). Mezze keeps the same line — `Async` performers schedule and wait, `LoomAsync` runs a
thread per task, and data parallelism is the `Parallel` effect — so parallel execution has one home,
not two. The code is in commit `0775877cb` if the line ever moves.

**Five ops, 2026-11-19 (Koka's lesson: few typed primitives).** `yield_now` is no longer an op: it is
`Async.park { predicate = {} -> True }`, which every performer answers on its next turn — a scheduler
re-queues the task behind the others (it costs a predicate evaluation, ~75 ns against 40 for the
machine's `Immediately`, which stays available to a performer's own ops), a thread-per-task performer
returns at once. A custom performer now implements `spawn`, `await`, `cancel`, `park`, `park_until` and
the two scope hooks. `park_until` stays an op so the performer, not the caller, performs `Time` — Koka's
`async` effect owns `wait`/`timeout` the same way; ZIO and Eio put the clock in the type or pass it as a
value instead, and that trade was weighed and declined (see the `Time` discussion above).

**Per-operation costs, 2026-11-19 (eighth pass) — from two JFR profiles of the working side.** What a
park spent, measured: `ThreadLocal.get` 14–20% (the handler-chain state read once per install, the
scope variable, the current task); `inspect` rebuilding its variant tag by NAME and its record shape by
field-name LIST on every call (~30 ns each); `promise_id` registering an id in a map that `forget` then
emptied; a one-element id list per await; a `yield_now` that parked into the parked list only to be pulled
out by the next step after evaluating `{} -> True` through a trampoline; a Truffle boundary on the machine
accessor; a cancel-hook lambda per spawn. Fixes: a carrier reads its effect state ONCE and the machine
passes it to every install/restore (overloads on `HandlerChainRegistry`); the current task lives on the
carrier (one thread-local read, no set/remove per task); cached tags and shapes in the promise natives;
`PromiseId` IS the promise (an opaque `__NativePromiseRef`: no map, no `forget`, BUG 77 moot by
construction); a machine-recognised READY predicate (`ContBinding.readyPredicate`, handed out by
`__native_ready_predicate`) so `yield_now` is detected by identity in `park` and re-queued behind the
others without touching the parked list or calling anything — a thread-per-task performer answers it with
`Thread.yield`; the accessor's hot path is one volatile read; cancellation reaches the machine through
the task the promise already carries. Measured (2M, ns/elem, before → after): yield_now 77 → 54,
spawn+await 192 → 140, seq fold with await 210 → 152, iter map→fold 222 → 165, list fold 208 → 165,
fan-out 179 → 142; park on a real predicate 75 → 75 (its cost is the predicate call). Still above the
40 ns the six-op `Immediately` path had: the remaining yield cost is the `__native_ready_predicate` call
and the one-element predicate list the `park` op builds — a module-level `ready` value (`let ready_pred = __native_ready_predicate {}`, fetched once when
the module loads — the first top-level native value in the stdlib; snapshot, stdlib and comptime tests
green) removes the call: yield_now 54 → 50 ns. The last 10 ns to the old `Immediately` path are the
one-element predicate list the `park` op builds and the forwarder call; a single-predicate `ParkOn`
constructor would take them and widens the performer-facing type — a trade left to the user.

**Event-driven waits and a verdict that never guesses, 2026-11-19 (tenth pass).** The eight flaws of the
honest assessment, taken in order. (2 and the one it hid) The stdlib's channel, mutex, semaphore, latch,
wait group and STM retry all waited through `park { predicate }`, which had two costs the carrier design
inherited: a scheduler step re-evaluated EVERY parked predicate (linear in parked waiters), and a waiter
whose producer was a foreign thread looked like a deadlock. Now each primitive owns a `WaitQueue`
signalled by its natives on every state change, from any thread, and the stdlib parks on
`wait_on { on, test }` — a predicate FUNCTION (so every performer's `park` accepts it, and a
thread-per-task performer just calls it) that names its queue through a shared root the machine
recognises. The machine registers the parked task with the queue, evaluates `test` only when the queue
is signalled, and is woken from the idle park by the signal. A wake is O(1) per waiter and never a poll.
A `park` whose predicate already holds returns without a dispatch. The deadlock VERDICT is now
principled: the only error is an EMPTY wait set, which is provably unsatisfiable; promise waits,
`wait_on` waits and deadlines are woken by their event; a plain predicate over state the machine cannot
see is re-evaluated with exponential backoff (50 us → 20 ms) and, after a grace period (10 s,
`-Dmezze.coop.graceMillis`), ONE diagnostic names the parked tasks on stderr — a stuck program is
visible, never guessed at. (4) The baton spin is adaptive per carrier: a spin that caught the handoff
doubles the budget, one that ended in a real park halves it, so a long wait stops burning CPU after a
few rounds. (5) A timed host-level await carries its promise AND deadline (`PromiseOrDeadline`): woken
by the settlement or at the deadline; no opaque host wait remains in the stdlib. (8) BUG 78's cause is
Truffle 25.0.2's virtual-thread mount hook recursing through MethodHandle customisation
(`InternalError: StackOverflowError` on a ForkJoinPool worker, 2 in 33 probe runs); customisation off
(`-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1`, in every default JVM option list) removes
the recursion: 0 in 60, no measurable cost. (7) The reactor surface exists and is tested: `promise_from`
hands `complete`/`fail`/`on_cancel` to foreign code, a foreign thread completing the promise wakes the
idle cooperative awaiter at once. What is still missing for a Java library's OWN threads is a bridge
that hands a Mezze function to Java as a functional interface; today a package wraps the Java future on
a Mezze-side thread. io_uring itself: Netty 4.2 (2025-04) made it a first-class transport, Linux only
(kernel ≥ 5.14, x86-64/aarch64/riscv64, memlock), falling back to epoll/NIO through the same API, about
two megabytes of jars (common 728 KB, transport 557 KB, buffer ~340 KB, unix-common 46 KB, plus the
io_uring classes and native jars); and OpenJDK's Loom repository opened an `io_uring` branch (2026-02)
for the virtual-thread I/O poller itself, which would reach every blocking read under LoomAsync and
under our carriers with no Mezze change. So io_uring is NOT built in: the JDK poller is the built-in
path, a Netty-backed performer is a package. (1, 3) Measured below.

Measured: (3) channel ping-pong between two long-lived tasks, bounded channels of one, 1M round trips —
2.1 us per round trip (two messages) under CoopAsync, 62 us under LoomAsync; the first wait-queue build
read 3.6 us because the FIRST adaptive-spin policy (halve on every real park) collapsed to zero in this
shape — each turn's real work outlasted a halved budget — and every handoff fell through to the pool
(JFR: 41% in `ForkJoinPool.deactivate`/`runWorker`); the policy now adapts to the OBSERVED wait (grow
towards a wait short enough to have been caught, shrink only on a long one) and the round trip is back
where the polled design was, now event-driven. (1) 300,000 tasks spawned and parked in under six seconds
including JVM start, all completed; the heap-per-parked-task number is still owed because the probe's
gate used `Time.sleep`, which turned out to BLOCK its carrier (see the eleventh pass). Kernels unchanged:
yield_now 53, park-True 55, spawn+await 145, fold with await 153, fan-out 140–178 (noisy). Gates:
typechecker 5502/5502, interpreter 8259/8259 (incl. testTiming), cli 357/357.

**Blocking natives park the task, 2026-11-19 (eleventh pass).** The memory probe's gate task slept
four seconds with `Time.sleep` and the whole machine stalled: `SystemTime`'s sleep is `DateTimeNatives
.sleep`, a `Thread.sleep` on the carrier, not the async module's sleep that already parked. The same
held for every blocking entry of the synchronisation natives — `mutex::lock`, `semaphore::acquire`,
`latch::await`, `waitgroup::wait` and their timed forms, the receive-any backoff and the STM retry
backoff: correct under a thread per task, a stall under a carrier. Each now asks `TaskMachine
.onCarrier()` and, on a carrier, parks the TASK: a sleep through `parkNanosCooperatively` (a deadline
wait), a primitive through `waitOnQueue(queue, test, deadline)`, which registers with the primitive's
WaitQueue and re-checks on its signal or at the deadline — the same event-driven path the stdlib's
`wait_on` uses, reached from Java. Rule for anyone adding a native: a wait that can block must offer the
carrier path; the carrier is the one thread a program cannot afford to lose. What still blocks a carrier
by design: a raw blocking call into a Java library through polyglot, data-parallel joins
(`Parallel`, CPU-bound and short), and a file-watch poll; these are documented as the cost of calling
foreign code from a cooperative task, as in Eio and Go.

**Twelfth pass, 2026-11-20 — scheduling in time proportional to what happened.** With sleeps parking,
the memory probe's hundred thousand tasks exposed that a scheduler step was linear in the parked count:
every step re-evaluated every parked plain predicate, and the parked list was removed from by index
shift. Now each wait is indexed by what will wake it: a `WaitQueue`'s signal and a promise's settlement
push the task onto the boundary's `signalled` queue (the promise carries its own task-waiter queue, so
`OnPromises` and the host-level await are no longer polled), deadlines sit in a heap, and only PLAIN
predicates are polled — when idle, and otherwise on an amortised schedule (no sooner than 100 us and
ten times the last scan's own duration, so polling never exceeds a tenth of the machine's time). The
parked list is swap-removed by index. A registration race came with this and is fixed: a task
re-checks once AFTER registering, because an event that lands between the pre-check and the
registration is never re-signalled (found as a hang in the full gate). The wait group's one-shot latch
became a monitor on the count (a wait after a re-add returned early; a pre-existing flake). Carriers
are plain virtual threads that enter the context themselves: building them through the polyglot thread
builder walked every live thread of the context (`checkAllThreadAccesses`, 72% of a spawn profile), so
the n-th carrier cost n steps. Measured: spawn+park of 100k tasks 191 s → 81 s (still superlinear:
100 us/task at 10k, 800 at 100k — profiled next); heap per parked task 3.6–5.3 KB (17 MB baseline, 70
MB at 10k, 376 MB at 100k; a parked task is a virtual thread's stack chunk plus the Mezze frames —
a million parked tasks is 4–5 GB, the price Loom and Eio pay too; a task that has not started is ~100
bytes); GC is not the spawn cost (1.5 s of pauses in 191 s under Serial; G1 slower). Kernels: yield 69
(50 before the indexes — the step now polls three empty indexes), park-True 54, spawn+await 141, fold
152, fan-out 200; ping-pong 2.2–2.4 us. Gates: typechecker 5502/5502, interpreter 8260/8260 (incl.
testTiming), cli 357/357.

**The spawn-at-scale knee is Truffle's thread registry, 2026-11-20.** Spawning and parking N tasks is
superlinear: 10k in ~1 s, 100k in 81 s (191 s before raw carriers), 300k not finished in 10 minutes.
GC is not it (1.5 s of pauses in 191 s). Two profiles, one per way of making a carrier, name the same
thing: `PolyglotContextImpl` keeps a `WeakHashMap` of every live thread and WALKS IT on every new
thread — `checkAllThreadAccesses` when a polyglot thread is built, `collectDeadThreads` (a `Thread
.isAlive()` per entry) when any other thread first enters the context (`enterThreadChanged`,
gated only by "new thread" and "not a polyglot thread"). The n-th carrier therefore costs n steps
however it is made, and N parked tasks cost N²/2 steps to create. A parked task is a live thread by
construction, so the machine cannot avoid the count; a task that completes without parking reuses a
pooled carrier and never pays it; `LoomAsync` pays it on every spawn, since every Loom task is a new
polyglot thread (measured: 10k Loom tasks spawned in 1.0 s, 30k in 5.8 s — the same superlinear curve). The JDK's own virtual threads do not have this cost; it is
Truffle's per-context thread bookkeeping, worth an upstream issue (amortise the walk by map size, or
keep a live-count). Documented knee: tens of thousands of SIMULTANEOUSLY parked tasks spawn in
seconds; a hundred thousand in about a minute and a half; plan a connection-per-task server's ramp-up
accordingly, exactly as one would under LoomAsync today.

**Native image re-verified after passes seven to thirteen, 2026-11-20.** `:cli:nativeCompile` builds; a
`mez build --native` of the park kernels, the ping-pong and the idle probe all run: yield_now 128 ns,
park-True 110, spawn+await 268, seq fold with await 285, fan-out 331 (200k elements), ping-pong 3.0 us
per round trip, the idle probe's 200 host-level awaits of a 2 ms sleep in 431 ms. Native runs the same
code paths as the JIT at two to three times the cost per park, as before. The editor suites
(`:langservice:test` 86, `:lsp:test` 135) ran on this branch for the first time: one failure each, both
inherited: the branch never touched either test, and main's single commit since the fork (06f364604)
fixed both tests itself — a `fail` op annotated `-> 'a` where the body returns the error type, and an
alias-vs-newtype example; both classes pass on main. Merging main into this branch resolves them; it
is the user's call, like everything that touches main.

**The guest callback bridge, 2026-11-20 (user decisions: no uniform function API; each provider
exposes what its interop has).** A Mezze function crosses to a guest BY REFERENCE as an interop
executable (`GuestCallable`): the guest holds it and sends `execute`; each positional argument arrives
in Mezze as a FOREIGN of that language (decoded with `.int{}`/`.str{}` like any other foreign — the
runtime never guesses a type it cannot see), the result crosses back as a value. Arity is the GUEST's
contract: a fixed-arity callable rejects a wrong count with an interop `ArityException` raised to the
guest (Java's future fails, JS's `catch` sees it, Python gets a TypeError caught into the errored
foreign), never a Mezze runtime error. The effect rule is the TYPE, not a checker: the function's row
is EMPTY in every signature, so a callback that performs `Console` is a type error at the call site;
completing a promise (`promise_from`'s `complete`, pure) is the model use.

One core mechanism, four `Polyglot` ops (`box_fn0/1/2`, variadic `box_fn`), one native
(`__native_polyglot_box_fn { f, lang, arity }`), and per-provider surfaces shaped by the interop:
`std::polyglot::jvm` has `from_fn0/from_fn/from_fn2` ("becomes whichever functional interface the Java
parameter asks for", host access already permits guest implementations via `allowAllAccess`); `mezze-js`
has variadic `js_fn` plus `js_fn1/js_fn2`; `mezze-python` has `py_fn` (positional only — the interop
call carries no kwargs) plus `py_fn1/py_fn2`; `mezze-wasm` gets nothing of the kind — functions enter
Wasm as IMPORTS at instantiation, numbers only. Verified in-tree (`JvmCallbackBridgeTest`, 7): Supplier
on a ForkJoin thread, Function/BiFunction/Comparator with foreign arguments, Java's callback thread
completing a promise awaited under CoopAsync (the reactor shape, no Mezze-side thread), wrong arity as
Java's error, effectful callback rejected by the typechecker.

Pitfall for providers: GraalJS has no event loop — a `then` callback is a JOB run when the stack
empties at the end of the call that resolved the promise, so a JS reactor pumps by calling into JS;
Python's `asyncio` loop runs only where someone runs it. A Java thread's FIRST callback enters the
runtime once (the thread-registry walk above), so callback pools are free and thread-per-event is not.

Still owed on the carrier design: the handoff cost when the inline child does NOT apply (two carriers
waiting on each other, ~300 ns each; the fan-out kernel is that shape at 176 ns per element); a foreign-thread
park (a Mezze callback on a Loom/HTTP thread calling `cont.park` is refused with a clear message —
an inbox so it can park the boundary is the next step); a multi-carrier budget for `parallel`-style
performers (the single-active discipline is a property of CoopAsync's `drive`, not of the machine);
the idle hook (an `OnPromises` wait on an external promise polls at 50 us).

**MEASURED AND FIXED, 2026-11-18 (bench/cases/contsurface + contloop, main vs branch dev launchers,
slope method, one kernel per process).** Before the fixes: no tax where nothing parks, but iter chains
+30% even PURE (a recursive dynamic guard made the root bail out of PE), effectful seq/list callbacks
+10–18%, and EVERY CoopAsync kernel shape but one crashed (BUG 69/70: the perform thunk did not
capture the enclosing function's locals; BUG 74: a tail call out of a resumed root escaped). The
built app also hid every real error behind a ClassCastException (BUG 75, error-time re-parse) and,
once visible, failed 60–80% of fan-out runs under the JIT (BUG 76: first-tier compiled yield-bearing
root takes the wrong branch; mitigated with `engine.MultiTier=false` — warm-up cost, peak unchanged;
upstream repro owed). After: all nine park shapes run; fast path at main parity on every kernel
(control 692 vs 697, seq fold eff 9811 vs 9882, list fold eff 12764 vs 12714, iter pure 20463 vs
22398, iter eff 21968 vs 20294); the tax is DYNAMIC — `SuspendFacts.parkPossible()` is true only
inside a cont-capable boundary, so programs that never install one run the exact pre-continuation
code. Park path (CoopAsync, per element, n=1000): spawn+await ~12 us, yield_now ~11 us, park ~11 us,
fold-with-await ~10–23 us, fan-out ~12–17 us — unoptimised, interpreter-dominated at that size.
The test harness's `run()` is interpreter-only; `ParkUnderJitTest` adds JIT coverage, and BUG 76 was
only ever visible in the built app. Still open from this pass: parser reproducibility on re-parse
(BUG 75 proper), the BUG 76 upstream repro, Loom lost wake-up (BUG 72) and main's native blocklist
(BUG 73) in verification, and BDSL yield under native image once BUG 73 is fixed.

**Error quality pass, 2026-11-18.** Three things a user would have met: (1) a parking
comparator or key function (`sort_by`, `min_by`, `dedup`, …) reached through a function
value surfaced as the generic dropped-park text — now every order/identity callback
site (`ScalarCall.callCmp`/`callBool`, `cmpInt`, the key calls, `ClosureHashing`)
checks its result and names the native and the fix at once (`OrderCallbacks`), and the
machine's generic diagnosis defers to that named error; (2) deadlock messages named
`Task#3` — they now name the spawn site (`the task spawned at app.mz:12`); (3) the
generic diagnosis still said "C4 is not built" — rewritten to the current set. Chasing
(1) exposed BUG 68: C1's `maySuspend` was dropped by both scalar-ABI call constructors
(`ParamScalarizer`, the compiler's scalar `emitCallIndirect`), so a park inside
`if cmp { … }` through a scalar indirect call was lost with no native involved. Fixed
and asserted. Still owed from the same pass: V0501 should name the performer that
brings `Time` (`CoopAsync`'s `park_until` deadline).

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
