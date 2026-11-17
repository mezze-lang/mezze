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

### 4. BDSL CONTINUATIONS UNDER NATIVE-IMAGE ARE UNVERIFIED

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
  Yield(local) else local`. One trap, measured: the inliner splices the SAME
  node object into other roots, so a mark that outlived one wrap made the second
  emission skip it and a continuation reached `+ 1` as a value.
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
2026-11-17.** `EventLoopSchedulerTest`, all on ONE thread, interleavings asserted
as strings: `EventLoop` (stdlib, FIFO) runs two yielding workers `a1 b1 a2 b2 a3
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
  `EventLoop` performer is otherwise plain Mezze: `await` = `cont.park` on
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
decides what cancellation means — asserted `cancel-sent, woke`). `EventLoop`'s
`park_until` parks on TWO predicates, the caller's and `{} -> after { now, deadline }`,
so the performer performs `Time` — and the DECIDED obligation surfaces exactly as
written above, verified on the CLI: `perform Console, EventLoop in …` as an entry
is **V0501 "Entry 'main' performs Time, but nothing discharges it"**, `perform
Console, SystemTime, EventLoop` is clean, and `LoomAsync` carries no obligation.
With `MockTime` the whole run is deterministic: a task advances the clock, the
waiter times out (`advanced, timed-out=False`). Rule 5 (stale resumers) is moot in
this machine: a task has exactly one leaf continuation and the machine holds it.

**D4 — a park that cannot reach its boundary is an ERROR, twice over. LANDED
2026-11-17.** Measured first: `seq::for_each { f = { x } -> yield_now {} }` under
`EventLoop` printed `before 1, before 2, done` — the native received the callback's
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
  the fix (`EventLoopSchedulerTest`, reaching the native through a function value
  the static rule cannot see). `cont.park` with no boundary at all — top level,
  comptime, a native or polyglot frame — is an error at the park.

Exposed by the rule, and the stdlib's to fix before `EventLoop` is general: the
async helpers themselves loop with native HOFs over `Async` callbacks —
`seq::map { f = { x } -> await { p = x } }` (`await_all`, `join`), `seq::for_each`
with `cancel` (`race`), `seq::any`/`find` over `is_done` predicates. Under
`LoomAsync` they are fine; under `EventLoop` each would park through a native and
hit the backstop. Rewrite them as Mezze loops (`iter`/recursion), which also makes
them suspendable for free.

Stated limitations, each a later slice's subject: tasks run under the DRIVING
boundary's handler chain (Phase B lifts it); a `WaitNamed` set is accepted and
ignored; `sleep` under `EventLoop` is still the Time performer's blocking sleep —
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
