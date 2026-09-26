# `comptime` — compile-time evaluation

Reference for anyone working on compile-time evaluation. Describes what EXISTS
on branch `comptime-impl`, not what is planned.

**Line numbers drift.** The symbol name in the same sentence is the durable key.

**Measured facts are marked MEASURED.** Everything else is from reading.

---

## 1. What it is

```mezze
use std::comptime::{ Comptime, ComptimeEval }
use std::effects::noop::{ noop }

let config = {} -> Comptime.comptime { eval = {} ->
    perform LocalDisk in read_text_or { path = "app.config", def = "{}" }
}

# A computation that needs no effects still says so — `<*>` has a lower bound.
let table = {} -> Comptime.comptime { eval = {} -> do
    noop {}
    build_lookup_table {}
}

let main = {} -> perform ComptimeEval in ... table {} ...
```

The thunk runs while the program is being compiled; the call site is replaced by
the value it produced. `table` costs nothing at run time.

`comptime` is an **effect operation**, not a keyword and not a function:

```mezze
effect Comptime where {
  comptime: { eval: {} -> 'a <*> } with Embeddable 'a -> 'a <Comptime>
}
impl Comptime for ComptimeEval where {
  comptime = { eval } -> resume { val = __native_comptime_eval { eval = eval } }
}
```

Three consequences, each load-bearing:

**`<Comptime>` propagates.** A function that folds at compile time says so in its
signature, and so does everything that calls it, out to `main` — where the
handler must be installed or the program does not type-check. That is the
disclosure (§12): nobody's build-time code runs without the person building
having written the line that permits it.

**It must be an operation rather than a thin wrapper function.** The result shape
is stamped per CALL SITE, and a wrapper would have one shared node for every
caller.

**It must live inside a function.** `let table = Comptime.comptime {…}` is
rejected by V0502 — the top level is for definitions — so the row always has an
arrow to sit on.

The handler is compiler plumbing, not something anyone writes. Reaching it at run
time means reduction failed, and it says so rather than quietly recomputing (§9).

Recognised **structurally**: the effect's content hash plus the operation name
(`EFFECT_COMPTIME`), never a spelling — the `to_dbg`→`InspectIR` pattern.

---

## 2. The three rules the signature carries

| requirement | enforced by | error |
|---|---|---|
| the result is embeddable data | `Embeddable 'a` obligation | ordinary `V04xx`, with field paths |
| nothing escapes the thunk | unification on the residual row | ordinary `V05xx` |
| something was performed | the `<*>` check in `unifyFunctions` | effect-row mismatch showing `<*>` on the expected side |

The first of those needed a fix before it worked at all. A `with` clause on an
effect OPERATION was silently discarded — `effectOperationParser` reads the whole
signature as a `ParsedFunctionType`, which does carry `abilityConstraints`, then
cherry-picked param/return/effects and dropped the rest, and `ParsedEffectOp` had
no field to hold them. So `Embeddable` parsed and ceased to exist, and a function
result type-checked clean. Ability METHODS had always done this correctly; the two
forms simply disagreed. Nothing in the constraint checker needed changing: a call
site emits `AbilityRequired` from the callee type's constraints, and an empty list
there just means no demand.

`<*>` is **required**, and it has a lower bound as well as an upper one:

| thunk | |
|---|---|
| `{} -> 2 + 2` | **rejected** — performs nothing |
| `{} -> do noop {} ; 2 + 2` | accepted |
| `{} -> perform Console in do println {…} ; 2 + 2` | accepted |
| `{} -> read_int {…}` with `<Mut>` escaping | **rejected** — an effect escapes |

The upper bound (*nothing escapes*) falls out of ordinary unification: an
undischarged effect stays in the residual row and fails to match.

The lower bound (*something was performed*) does **not**, and the reason is
worth knowing. `isPure1`/`isPure2` in `unifyFunctions` classify a row by its
effects, row var and instances — **ignoring `performed`** — so a `<*>` row counts
as pure and both sides take the `isPure1 && isPure2 -> Substitution.empty` arm,
which checks nothing. A check was added there.

It has to live in unification rather than at the comptime call site, because an
annotation is **adopted into** the inferred row, not checked against it: after
solving, a thunk written `{} -> 2 + 2` in a `<*>` slot *has* `performed = true`,
inherited from the annotation. Inspecting the row afterwards can therefore never
report a violation — tried, and reverted.

`performed` **propagates through a call**, which is what makes the `noop` helper
work: the `perform` is inside `noop`, not in the thunk.

---

## 3. The two rules it cannot

Both decided at **type-check** time, which is what keeps the check/build gap to
crashes and non-termination only.

| # | rule | code | why |
|---|---|---|---|
| 1 | `comptime` is applied, never referenced | `V0801` | the mechanism replaces the *call site*; a reference denotes nothing |
| 2 | the `eval` thunk is closed | `V0802` | `comptime` runs before run-time values exist |

Rule 2 is the likely mistake:

```mezze
let scale = { n } -> Comptime.comptime { eval = {} -> n * 2 }   # rejected: `n` is run-time
```

It is structural, not a scope analysis — `TypedLambda` already records what it
closes over, so "closed" is `captures.isEmpty()`.

**Rule 1 cannot be checked during expression lowering.** Rejecting a comptime
hash in `compileVar` over-fires, because the callee of a *legitimate* call
reaches it too; marking legitimate callees by node identity also fails, since
the node arriving there is a different object. Only the lowered IR distinguishes
the two positionally — a value use leaves a bare `ReadGlobal*ByHash`, an
application consumes that read as a call's callee.

**`V0803` is reserved and unused.** It first rejected a comptime whose result
type was still open, which turned working programs into compile errors:
`Comptime.comptime { eval = {} -> Some { val = 7 } }` has a variant type that stays open
until a later `match` closes it. An unresolved shape means *cannot fold*, not
*wrong* — the call stays and evaluates at run time. It was then re-taken for the
`<*>` lower bound, and released again when that check moved into unification and
reported as an ordinary effect-row mismatch. Codes are never reused, so it
stays retired.

---

## 4. `Embeddable`

A marker ability — no operations. Its existence *is* the content: permission to
write a value of this type into the compiled artifact.

The rule is **no impl = not embeddable**. There is no opacity test and no list
of native types anywhere.

| | |
|---|---|
| primitives | built-in |
| records, variants | derived structurally, **on demand** (the `CodecGenerator` pattern, not `Eq`'s eager one) |
| containers | conditional on the element |
| **newtypes** | **opt in** — `impl Embeddable for Meters where { }` |
| functions, `__Native*` | no impl, ever |

The newtype row is load-bearing and deliberately differs from `Eq`. Newtypes are
absolutely opaque (#401), so deriving would pierce that wall and bake a hidden
representation into a shipped artifact — `newtype Password = Str` must not
become embeddable by accident. It is also what makes `Promise`, `MutInt` and
`Foreign` fail without being named: nobody wrote their impl.

---

## 5. Pipeline

```
type check ─────────────────────────────────► mez check / LSP stop here
   │  stamps each comptime call node with its result PrintType
   │  (computed pre-erasure — afterwards nothing can tell Nat from Int)
   ▼
lower to FunctionIR                     ── the shape is serializable, so it
   ▼                                       travels with the program
ComptimeReduction.reduceOrThrow  ◄── evaluator SPI ──► :interpreter (Truffle)
   │  a PerformOpIR flagged isComptime IS a site → evaluate thunk
   │  → reify(value, PrintType) → splice
   │  then: any site still present is a BUILD ERROR
   ▼
emit
```

**Both flags ride on the node, not in a side table.** `PerformOpIR` carries
`isComptime` and `comptimeShape`, and `FunctionIR` is `@Serializable`, so they
survive the wire, the jar and the image heap. A span-keyed map was tried and is
the wrong answer: it works for a test that type-checks and runs in one JVM, and
silently does nothing the moment IR crosses a process boundary — which is every
path a user actually builds with.

**Recognition is `isComptime`, NOT "carries a shape".** A thunk whose body always
raises — `compile_error` — has an unconstrained result type and therefore no
shape, and it is exactly the case that MUST still be evaluated, since the failure
is the output. Keying recognition on the shape made it invisible to the reduction
pass and to the did-everything-fold check alike.

**Nothing survives the pass.** `reduce` scans afterwards for remaining sites and
reports each with `file:line:col`. Folding at build IS the feature, so a surviving
site is a compiler failure — and it belongs at build, with a location, not as a
program that crashes when someone runs it.

**Evaluation belongs to code generation, not to type checking.** Type checking
needs the block's TYPE, never its VALUE. So an editor never executes user code,
cannot be hung by a runaway thunk, and does not run a dependency's code while
you type. This mirrors Rust, where rust-analyzer expands proc macros but leaves
real const evaluation to rustc.

The cost, accepted: **`check` is strictly weaker than `build`.** A comptime that
crashes or never terminates passes the first and fails the second.

**There is no termination budget, by decision.** An infinite comptime hangs the
build exactly as any infinite loop does; that is the author's code doing what it
says, and not ours to cut short. What would be wrong is the silence, since a
stalled build looks identical to a slow one — so a block still running after 30s
reports itself by `file:line:col`, and again every 30s, while it runs. Reporting
only on completion would never fire for the case that matters most.

**No fixpoint.** The value spliced in is a literal whose type the solver already
assigned, so nothing new enters the program and nothing needs re-checking.
(Macros would need both. This does not.)

### Where reduction actually runs

Two places, for one reason: a program is compiled once but an artifact is run
many times.

| path | folds in | when |
| --- | --- | --- |
| `mez build`, `mez build --native` | the **CLI** | once, at build |
| `mez dev`, `mez exec` | the **runtime** | once per compile |
| the WASM playground | the **runtime** | once per run |
| `mez check`, the LSP | nowhere | never |

`mez dev` hands the runtime pre-lowered IR and never looks at it again, so the
runtime is the only place it *can* happen — and once per compile is exactly
right there. A built artifact is different: reducing at start-up would quietly
redefine "compile time" as "every launch", repeating the work on each run and
re-reading the world each time instead of baking it in. So `BuildCommand` folds
before anything is packed, and the runtime's pass then finds no sites.

The CLI keeps `:interpreter` off its compile class path on purpose — it names
interpreter classes only as strings, and keeping Truffle out of its own process
is what keeps `mez` starting quickly. `ComptimeHost` therefore loads the
evaluator from `mezze-runtime.jar` by name, through a child loader that
delegates to the CLI's own. Two details are load-bearing: Truffle discovers
languages through the **context class loader**, so that has to be set for the
duration or the engine comes up with no languages at all; and
`engine.Compilation` is an optimizing-runtime option that makes `build()` throw
on the CLI's fallback runtime, so the engine is built tolerantly.

Absent a runtime jar, nothing folds and the IR comes back untouched — the right
answer for a slim install, and the same mechanism that keeps an editor from
executing user code.

---

## 6. Reification

`reify(value, PrintType) -> NodeIR` is the sibling of
`InspectNatives.render(value, PrintType) -> String`, and exists for the same
reason its header already states: the runtime value alone cannot say whether a
`Long` is an `Int` or a `Nat`, or which newtype erased to it.

The rule is not "can it be written as a literal" but **"can it be rebuilt by
pure IR"** — a larger, better-defined set. The IR has no Map or Set literal
node, so those become a constructor call to the same `from_seq` native the
stdlib uses.

Covered: scalars, `Nat`, `Frac`, newtypes, records, variants, `Seq`, `List`,
`Set`, `Deque`, `NonEmptyList`, `Map`, recursive shapes.

Also covered: `Bytes` (through the same `from_seq` native), and the sorted
containers. `__native_sorted_set_from_seq { xs, cmp }` used to need a
comparator, and a comparator is a function — the one thing reification cannot
build. `ElementComparator.of(null)` already meant natural ordering, so the
comparator is now genuinely optional and a sorted container reifies by relying
on its element's natural order.

`render` is total (it prints `<function>`); `reify` cannot be. Reaching a
function means `Embeddable` let something through, so it reports as an internal
failure with the value path (`result.items[3].callback`).

---

## 7. Effects

Handlers at compile time are the **ordinary** handlers. There is no
compile-time handler set, because the evaluator *is* the interpreter.

```mezze
Comptime.comptime { eval = {} -> perform LocalDisk in read_text_or { path = "app.config", def = "{}" } }
```

Which handler you perform is part of the block's content hash, so determinism is
the author's choice, not compiler policy. That also makes the old plan's
effect-kind → cache-behaviour table unnecessary.

Failure taxonomy — it follows from Mezze's failure model, not comptime's:

| | |
|---|---|
| `Err { … }` | **data**. Embedded; the build succeeds |
| `compile_error { msg }` | build fails with that message at the call site |
| a raise in the body | build fails, `V0804`, with guest frames |
| unhandled effect | impossible — rejected before evaluation |
| non-termination | the author's business (`timeout` on the build) |

`compile_error` exists precisely because a failure is usually a *value*.

---

## 8. Caching

Within one compilation, results memoise on the thunk's **content hash** — two
blocks with identical bodies are the same function and evaluate once.

Delivery is already free: after reduction the IR holds a literal, so what lands
in `blobs.sqlite` (or a native image's heap) is *already evaluated* and the
runtime never sees a comptime node.

**The cache does not cross builds**, deliberately. A thunk's hash covers its
*code*, not the world it read: `perform RealFS in read_file { path = "c.json" }`
has the same hash after the file changes. Doing it properly needs the executor
to record what the handlers actually touched and key on
`(thunkHash, observedDependencyHashes)`. A safe intermediate step is to persist
**pure** thunks only — a pure result can never go stale.

---

## 9. Twelve things that were wrong, each found by running it

Every one produced a **wrong answer**, not a failure. Worth keeping because each
is a trap the next person can fall into.

1. **The call was inlined away.** `{ eval } -> eval {}` is a textbook trivial
   wrapper, so `TrivialWrapperInliner` deleted it. Nothing failed — every
   program still gave the right answer — comptime just silently stopped
   happening. `inline()` grew a `doNotInline` set for it — since removed, because
   a perform is not wrapper-shaped and the hazard no longer exists.
2. **The callee's hash is not `comptime`'s.** Monomorphization redirects a
   specialized call, so hash-matching found nothing in lowered IR. Also moot in
   the effect form: `PerformOpIR` carries the effect hash, and mono redirects
   callees rather than performs.
3. **Scalar ABI passes the thunk positionally.** One parameter means no record
   is built, so looking for an `eval` field found nothing.
4. **The entry path ran the enclosing module.** Passing `(functions, entryHash)`
   makes the runtime execute `__entry__` for its side effect and then look the
   entry up in globals — where a lambda is never registered. Every comptime in a
   module folded to the same value. The thunk is renamed to `__entry__` instead.
5. **A shared Truffle engine corrupts results.** One engine means one
   `MezzeLanguage`, whose CallTarget caches outlive any Context — the staleness
   `TEST_ISOLATION` exists for. Each evaluation now gets its own engine, closed
   after (an engine retains every CallTarget it creates).
6. **Performer records were not passed.** A thunk performing a user-defined
   effect failed with "Handler not found" — a handler record is *built* from
   those records.

7. **A span-keyed side table is invisible across a process.** It worked in
   every in-process test and did nothing on the path every real build uses.
   Worse, the in-lambda case — which is what an entry point looks like — never
   stamped at all, because the site had been inlined away before stamping ran.
   The shape now lives on the node.
8. **Reduction recursed forever.** Evaluating a thunk runs the program again,
   and that program still contains the call being evaluated — so the pass fired
   again, and again. The symptom was a stack overflow wrapped in two hundred
   nested "comptime evaluation failed" messages. `Comptime.isEvaluating()` is
   the guard; skipping reduction in the throwaway nested run costs nothing.

9. **The handler quietly recomputed at run time.** `resume { val = eval {} }`
   meant "did not fold at build" was indistinguishable from "did": the answer was
   still right and every test passed. That one line hid the next three. Replaced
   with a native that raises, except while another comptime is being evaluated —
   reduction is suppressed there on purpose, so the fallback is legitimate in that
   one place. Turning it loud exposed 10 broken sites at once.
10. **The IR traversal had holes.** `mapChildren` was a `when` with `else -> node`,
   so any NodeIR variant lacking an arm silently hid sites inside it. Two were
   hiding real ones: `HandleIR` (a comptime under its own handler) and
   `FusedChainIR` (a comptime feeding a seq chain, which `ChainCanonicalizer`
   creates after lowering). The `else` is gone and all 54 variants are named, so a
   new node type is a COMPILE ERROR here rather than another invisible site.
11. **`Err` could not fold.** `Result` has two parameters, so `Err { error = … }`
   leaves the `Ok` arm's type unknown — and the groundness check demanded EVERY
   arm be ground, rejecting the shape over an arm that value never uses. Variants
   are now checked per-arm, which is safe because an insufficient shape is a loud
   build failure.
12. **A thunk that always raises could never run.** `compile_error` leaves the
   result type unconstrained, so there is no shape — and recognition was keyed on
   having one, making exactly the case that most needs evaluating invisible. Hence
   `isComptime` as a separate flag.

Plus three smaller: the evaluator must save/restore `MezzeLanguage`'s **static**
last-result store, since this evaluation is nested inside the compilation that
reads it; three unrelated classes in this repo are called `MezzeBytes`, of which
the `bytes::*` natives build the one nested inside `BytesNatives` — not the
`datastructures` one the name suggests; and a comptime in a TOP-LEVEL BARE
EXPRESSION never solves its type, a shape real `mez` rejects anyway (V0710) but
which `MezzeTestHelper.run` allows, so three tests were exercising a path no
program can reach.

---

## 10. How the suite guards

**MEASURED: 71 tests across 9 suites**, all green, plus the full `:parser`,
`:ast`, `:typechecker`, `:cli` and `:interpreter` suites.

And they are now real evidence: with the run-time fallback gone, a test that
passes is a block that actually folded. Before, a silently-unfolded site still
produced the right answer, which is how four of the twelve bugs above survived.

The one that matters most is `ComptimeActuallyFoldsTest`. Every other test
asserts the program's *answer* — and a folded program and an unfolded one give
the same answer, by design, because of the `eval {}` fallback. That safety
property makes answer-asserting tests blind to whether folding happened at all.
An **evaluation counter** is the only thing that separates them, and it is also
how the "type checking never executes user code" guarantee is held.

| suite | what |
|---|---|
| `EmbeddableAbilityTest` | the derivation rule, incl. recursive rejection and newtype opt-in |
| `ComptimeDeclarationTest` | signature, effect slot, `Embeddable` through comptime |
| `ComptimeRulesTest` | every rule, asserting the message the author sees |
| `ComptimeReductionTest` | folding, per shape |
| `ComptimeShapeCoverageTest` | round-trip per value shape, incl. `Nat` vs `Int` |
| `ComptimeEffectsTest` | discharged bodies, user performers, escape rejection |
| `ComptimeFailureTest` | `compile_error`, crash, `Err`-is-data |
| `ComptimeToolingTest` | check/LSP evaluate nothing; check is weaker than build |
| `ComptimeDeploymentPathTest` | folding on the pre-lowered-IR path, top-level and in a lambda |

```
devenv shell -- ./gradlew :interpreter:test --no-daemon --tests '*Comptime*' -i --rerun-tasks
devenv shell -- just test-one interpreter
```

---

## 11. Code map

| area | file |
|---|---|
| declaration | `stdlib/std/comptime.mz` |
| ability | `stdlib/std/abilities/embed.mz` |
| derivation rule | `typechecker/.../derive/DerivabilityRules.kt` |
| rules | `typechecker/.../comptime/ComptimeRules.kt`, `TypedASTCompiler.checkComptimeSite` |
| handler body | `NativeDispatch.__native_comptime_eval` — the one decision, in one place |
| IR traversal | `ast/.../comptime/ComptimeIr.kt` |
| reduction | `ast/.../comptime/ComptimeReduction.kt` |
| SPI | `ast/.../comptime/ComptimeEvaluator.kt` |
| evaluator | `interpreter/.../comptime/TruffleComptimeEvaluator.kt` |
| reifier | `interpreter/.../comptime/ComptimeReifier.kt` |
| hashes | `parser/.../WellKnownHashGenerator.kt` |

`ComptimeIr` is deliberately the **one** traversal for finding comptime in IR,
and its `when` over `NodeIR` is **exhaustive on purpose** — no `else`. Two node
types silently hid sites before that (§9.10), and a missing arm is now a compile
error rather than a feature that quietly stops happening.

---

## 12. Disclosure: `<Comptime>` in the row

A dependency can run code during your build, and until this existed you could not
tell. Demonstrated, not theorised: a library-shaped comptime read a canary file
and POSTed it to a listener during `mez build` — exit 0, no warning, and the
consumer's type was a plain `Str`. That is `build.rs`, arrived at by the same
route.

`Comptime.comptime` carries `<Comptime>`, so:

- a function that folds at compile time says so in its signature
- so does everything that calls it, out to `main`
- `main` must install `ComptimeEval` or the program does not type-check

The consent is therefore checked at **type-check time, which completes before
reduction runs** — so nobody's build-time code executes without the person
building having written the line that permits it. And the disclosure lands at the
USE site, not only in `main`: reach for `lib::make_table {}` and its type says
`<Comptime>`.

This is the mechanism the language already uses at run time — effects propagate in
the row, the handler is the capability grant — applied to compile-time work. No
lockfile scanning, no denied-native list, no publish step, no capability tiering,
and no "libraries may not use comptime" rule. Libraries stay plain source.

### What it does not say

That compile-time code runs here, not *what it did*. Reading your disk and
computing a lookup table both show `<Comptime>`. It converts silent to visible —
the npm-install-scripts lesson — and is not a capability system.

### The related `*` marker

Separate and worth not confusing with the above. `*` means "some effect was
performed and handled in here"; it is a row MEMBER and composes, so `<Log, *>`
reads "Log escapes, and something else was handled inside". It also cannot be
annotated away (T0210) — that was a laundering hole, since the name-based
annotation checks compare effect SETS and both sides read empty, so `{} -> Int`
over a body that discharged an effect used to pass silently. An operator dropped
it too, because `+` is an ability op and the merge there unioned names while
forgetting the flag.

`*` is NOT a comptime marker and should not be sold as one: it fires for a
mutable accumulator just as readily.

---

## 13. Known holes

- **No cross-build cache.** A thunk's hash covers its code, not the world it
  read, so results cannot be reused between builds. Pure thunks could be
  persisted today — the same pure/effectful line as everywhere else.
- **No cross-build cache** (§8). Needs observed-dependency tracking.
- **Evaluation is single-threaded**, pending task #1 — it installs handlers on
  the running thread, and three of four assumption-guarded handler caches still
  cache thread-local chain state on a shared node.
- **A fresh engine per evaluation** (§9.5). Shareable once dropping the
  Language's CallTarget caches is exposed.
- **Supply chain**: a dependency's comptime runs on your machine at build time —
  Rust's `build.rs` problem. Bounded by evaluation being build-only, and now
  *disclosed* rather than silent (below), but not eliminated.
- **Debugger/coverage**: a breakpoint inside a comptime thunk can never hit and
  should report as unbound; comptime bodies show 0% coverage forever. Neither is
  wired up.
- **The `<*>`-slot rejection message is the generic argument mismatch.** It shows
  `<*>` on the expected side and its absence on the actual, so it is legible —
  *expected `{ eval: {} -> 'a <*> } -> 'a` but got `{ eval: {} -> 'b } -> 'c`* —
  but it does not say "discharge `NoOp`". A targeted branch in
  `ErrorMessageBuilder` was written and removed: the failure does not reach that
  formatter, so the branch was unreachable.
