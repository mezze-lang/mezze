# `comptime` — compile-time evaluation

Reference for anyone working on compile-time evaluation. Describes what EXISTS
on branch `comptime-impl`, not what is planned.

**Line numbers drift.** The symbol name in the same sentence is the durable key.

**Measured facts are marked MEASURED.** Everything else is from reading.

---

## 1. What it is

```mezze
use std::comptime::{ comptime }

use std::effects::noop::{ noop }

let config = comptime { eval = {} ->
    perform RealFileSystem in read_file { path = "app.config" }
}

# A computation that needs no effects still says so — `<*>` has a lower bound.
let table = comptime { eval = {} -> do
    noop {}
    build_lookup_table {}
}
```

The thunk runs while the program is being compiled; the call site is replaced by
the value it produced. `table` costs nothing at run time.

`comptime` is a **declaration**, not a keyword:

```mezze
pub let comptime: { eval: {} -> 'a <*> } with Embeddable 'a -> 'a = { eval } -> eval {}
```

That matters for two reasons. The `with Embeddable 'a` constraint is only
expressible on a signature — a keyword would have to hardcode it in the
typechecker and would lose the ability system's error messages. And the body
`eval {}` is a **run-time fallback with the same meaning**: a call that is not
reduced still computes the right answer, just not for free. That is what makes
every part of this feature safe to degrade.

The compiler recognises it by **content hash** (`FUNCTION_COMPTIME`), never by
name — the `to_dbg`→`InspectIR` pattern.

---

## 2. The three rules the signature carries

| requirement | enforced by | error |
|---|---|---|
| the result is embeddable data | `Embeddable 'a` obligation | ordinary `V04xx`, with field paths |
| nothing escapes the thunk | unification on the residual row | ordinary `V05xx` |
| something was performed | the `<*>` check in `unifyFunctions` | effect-row mismatch showing `<*>` on the expected side |

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
let scale = { n } -> comptime { eval = {} -> n * 2 }   # rejected: `n` is run-time
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
`comptime { eval = {} -> Some { val = 7 } }` has a variant type that stays open
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
   │  a call carrying a shape IS a site → evaluate thunk
   │  → reify(value, PrintType) → splice
   ▼
emit
```

**The shape rides on the call node, not in a side table.** That is what makes
the feature work anywhere but in-process. A span-keyed map is compile-local: it
works for a test that type-checks and runs in one JVM, and silently does nothing
the moment the IR crosses a process boundary — which is every path a user
actually builds with. `CallDirectIR.comptimeShape` is `@Serializable`, so it
survives the wire, the jar and the image heap, and a site identifies itself
without needing `comptime`'s hash.

The call is also emitted straight at the site rather than falling through the
normal call-lowering path. Everything downstream is otherwise entitled to
rewrite it — monomorphization redirects it to a specialized hash, and the
caller-side inliner splices `eval {}` in and deletes the site outright — and a
rewritten site is one the reduction pass can no longer see.

**Evaluation belongs to code generation, not to type checking.** Type checking
needs the block's TYPE, never its VALUE. So an editor never executes user code,
cannot be hung by a runaway thunk, and does not run a dependency's code while
you type. This mirrors Rust, where rust-analyzer expands proc macros but leaves
real const evaluation to rustc.

The cost, accepted: **`check` is strictly weaker than `build`.** A comptime that
crashes or never terminates passes the first and fails the second.

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
comptime { eval = {} -> perform RealFileSystem in read_file { path = "app.config" } }
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

## 9. Eight things that were wrong, each found by running it

Every one produced a **wrong answer**, not a failure. Worth keeping because each
is a trap the next person can fall into.

1. **The call was inlined away.** `{ eval } -> eval {}` is a textbook trivial
   wrapper, so `TrivialWrapperInliner` deleted it. Nothing failed — every
   program still gave the right answer — comptime just silently stopped
   happening. `inline()` now takes a `doNotInline` set.
2. **The callee's hash is not `comptime`'s.** Monomorphization redirects a
   specialized call, so hash-matching finds nothing in lowered IR. The call is
   now emitted stamped, at the site, before any rewrite can reach it.
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

Plus two smaller: the evaluator must save/restore `MezzeLanguage`'s **static**
last-result store, since this evaluation is nested inside the compilation that
reads it; and three unrelated classes in this repo are called `MezzeBytes`, of
which the `bytes::*` natives build the one nested inside `BytesNatives` — not
the `datastructures` one the name suggests.

---

## 10. How the suite guards

**MEASURED: 70 tests across 9 suites**, all green, plus the full `:interpreter`
(6359) and `:typechecker` suites.

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
| rules | `typechecker/.../comptime/ComptimeRules.kt`, `TypedASTCompiler.checkComptimeCallSite` |
| reserved name | `typechecker/.../validation/DuplicateDeclarationValidator.kt` |
| IR traversal | `ast/.../comptime/ComptimeIr.kt` |
| reduction | `ast/.../comptime/ComptimeReduction.kt` |
| SPI | `ast/.../comptime/ComptimeEvaluator.kt` |
| evaluator | `interpreter/.../comptime/TruffleComptimeEvaluator.kt` |
| reifier | `interpreter/.../comptime/ComptimeReifier.kt` |
| hashes | `parser/.../WellKnownHashGenerator.kt` |

`ComptimeIr` is deliberately the **one** traversal for finding comptime in IR —
rule 1 and the reduction pass share it rather than drifting apart, which is a
failure mode this codebase has several instances of, including one that
corrupted a wire format.

---

## 12. Disclosure: `<*>` on the result

`comptime` returns `'a <*>`, and that is not decoration — calling `eval`
propagates its `performed` marker, so it is the row the body actually has.

It matters because `*` **cannot be annotated away**. The marker is a flag beside
the effects set, so the name-based annotation checks compared both sides as the
empty set and agreed; `{} -> Int` over a body that discharged an effect passed
silently. It is now T0210. So:

- a library that runs a comptime block carries `<*>` into its own signature,
  visibly, in the type a consumer reads
- a library that *handles* it still shows `<*>` — discharging cannot launder it
- a library that does neither is genuinely pure

That is the same mechanism the language already uses at run time (effects
propagate in the row, handlers are the capability grant), applied to compile-time
work. No lockfile scanning, no denied-native list, no publish step, no
capability tiering, and no positional "libraries may not use comptime" rule.

`*` is a row MEMBER and composes: `<Log, *>` reads "Log escapes, and something
else was handled in here".

Two consequences worth knowing:

- **A top-level `let x = comptime {…}` is exempt from V0502.** The rule rejects a
  top-level binding whose RHS performs, because the module top level runs no
  handler — but a comptime binding is replaced by a literal *before* module load,
  so the premise is false. The exemption keys on the resolved callee hash, and
  only the performed-only row qualifies.
- **A top-level binding calling any other `<*>` function is now rejected**, and
  correctly: it really does run handled effects at module load. That was
  previously invisible.

What `<*>` does NOT say is *what* was handled — reading a file and using a
mutable accumulator both show `*`. It converts silent to visible, which is the
npm-install-scripts lesson; it is not a capability system.

---

## 13. Known holes

- **A library's comptime produces a BROKEN ARTIFACT.** `mez build` succeeds with
  no warning and every run of the jar dies with *"comptime thunk … is not among
  the compiled functions"*. Three seams each look fine locally: `DepPrecompiler`
  lowers and canonicalizes a dep but never reduces; `BuildCommand` reduces over
  `loweredFunctions`, which is user code only; the runtime then tries to fold a
  site whose thunk `FunctionIR` was never handed to it. Reproduced with a
  `path-dependencies` library — see the worklist task.
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
- **The `<*>` rejection message is the generic argument mismatch.** It does show
  `<*>` on the expected side and its absence on the actual, so it is legible —
  *expected `{ eval: {} -> 'a <*> } -> 'a` but got `{ eval: {} -> 'b } -> 'c`* —
  but it does not say "discharge `NoOp`". A targeted branch in
  `ErrorMessageBuilder` was written and removed: the failure does not reach that
  formatter, so the branch was unreachable.
