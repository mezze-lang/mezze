# Performance log — feat/perf-obsession

A running record of what was measured, what was fixed, and what is still open.
Every number here was measured on this branch; nothing is estimated. When a
claim turns out stale, it gets struck through rather than deleted — a stale
claim with no test is how the 48× regression below survived for months.

**The goal.** No type annotation is ever needed, and performance does not depend
on having written one. The typechecker, lowering, and call construction do all
the work before execution: no object allocation on a hot path, no runtime
dispatch that could have been decided at compile time.

**Machine / toolchain.** GraalVM CE 25.0.2 (jvmci-b01), Truffle 25.0.3, Serial
GC only (CE ships no other), mez built from this worktree. Mezze programs are
run from the JAR `mez build` produces — never `mez dev`/`mez exec`, which are
the interpreter — at sizes large enough that the ~800ms JVM startup is not the
measurement.

**Java baseline on the same machine**, in-process and warmed, for reference:
`fib(30)` 1.06 ns/call, a 4-op `while` loop 1.06 ns/iter. A 1-op `while` loop
reports 0.05 ns/iter because the JIT folds it to a closed form — not a usable
baseline.

---

## THE LEDGER — every cell, four states, no blanks

The ranking instrument. Everything below this section is narrative and evidence;
this is the index, and it is what makes "is X done?" answerable without
recollection.

**Why it exists.** "Take the largest measured gap" only works once everything is
measured. Applied to a partial set it means "largest among the handful I happen
to have", and every new measurement reshuffles the queue — which is how a
session lands seventeen real commits and still cannot say what fraction of the
language is fast. The 1503x `size` win was sitting in an EXISTING bench that
nobody had run at a realistic N.

**Four states. `done` is not one of them.**

- **AT FLOOR** — with evidence and a ratchet test holding it there.
- **ABOVE FLOOR** — with the *named cause*, never a guess.
- **UNKNOWN** — never measured. A row, not an absence. This is the state that
  hides wins.
- **CLOSED** — cannot reach the floor; reason recorded so it is not
  re-investigated. A `perform` in a loop can never be on tier 1, because that
  tier runs the whole loop in Java and cannot call a guest handler. Also used
  for a row reached by a DIFFERENT route than the one predicted — `bytes` is
  at floor, and not by the fusion this ledger expected.

**A FIFTH STATE THE TABLE CANNOT SHOW, so it is written in the cell:
UNVERIFIED.** A row whose cause was attributed to a subsystem that has since
CHANGED is no longer a measurement, it is a memory. The four MONO rows below
were all attributed to mono's dict wrapper, and mono was fixed in three places
since they were taken — so they are marked, and re-measuring them outranks
acting on them. A stale ledger is worse than a short one: its whole job is
answering "is X done?" without recollection.

**Floors, defined up front so the work terminates.** Java-equivalent for
arithmetic and loops · 0.30 ns for anything statically resolvable · the
control's GC count for shapes that must not allocate · ~85 ps/elem where fusion
applies · raw array-write cost where materialising is unavoidable · otherwise
"floor unknown" plus the experiment that would settle it.

**A cell is AT FLOOR only if it is also BOX-FREE.** Unboxed is a column, not a
nice-to-have: a pass that emits `SlotKind.OBJECT` without looking up the type is
a defect, not a conservative default.

### AT FLOOR — verified on this branch, control ~230 ps/iter

| cell | before → after | holding test |
|---|---|---|
| record from a local helper | 5 207 → 221 | RecordScalarizationTest, LocalHelperDevirtualizationTest |
| record as loop state | 2 766 → 225 | ParamScalarizationTest |
| record spread, fully overridden | ~9 400 → 278 | ParamScalarizationTest |
| `let` in a loop body | 638 → 224 | LetCopyPropagationTest |
| variant constructed + matched | 1 751 → 222 | VariantMatchFoldTest |
| helper at module level | 671 → 229 | (native signature table) |
| operator chain, inferred | 161 285 → 647 | ChainedOpMonoTest |
| `seq::map` + size | 1503x | SizeChainSink{,Lowering}Test |
| `seq::filter` + size | 36x | SizeChainSink{,Lowering}Test |
| tail loops, 1-op / 4-op | 0.2 / 1.05 ns/iter (Java 1.06) | — |
| ability method, single impl | 0.30 ns | — |
| effect operation, marginal | ~0.38 ns | — |
| `seq::fold` / `sum` / `any` | 0.37 / 0.36 / 0.42 ns/elem | — |
| `bytes` fold | 41 076 → **257** ps/elem, FASTER than the fused seq fold | BytesSumPrimitiveTest |
| a pipeline step bound to a `let` | 64 672 → **206** ps/elem | LocalLambdaSpecializerTest, NamedPipelineStepFusionTest |
| a specialized recursive fn's self-call | 30 675 → **682** ps/iter | RecursiveSpecSelfCallTest |

### ABOVE FLOOR — cause named

| cell | gap | cause |
|---|---|---|
| ~~`bytes` fold~~ | **CLOSED — AT FLOOR at 257 ps/elem** | Two causes, neither of them fusion. (1) `BytesNatives.fold` was `@TruffleBoundary` and allocated FOUR objects per element → 41 076 → 9 275. (2) `BuiltinSumBytes` recognises a zero-init ADD folder instead of calling it → 257, below the fused seq fold. The predicted `byte[]` indexed walk WAS built and REVERTED: the generic fused loop is slower than the native fold, because a bare fold has nothing to eliminate |
| `set` / `deque` bare fold | deque **CLOSED at 1 670**; set **ABOVE FLOOR at 4 938 after three rounds** | Three rounds. First: `promoteOpaqueSource` materialised the collection into a Seq to fuse it; refusing that copy when no stages pay for it gave deque 71 508 → 14 041, set 188 633 → 19 826. Then `FoldToSumRewriter` replaced the summing fold with the collection's own `sum` — deque 9 638 → 1 670 (5.6x), set 17 376 → 7 447 (2.3x). Set's remainder is the WALK, not a callback (`set::sum` measured 7 270 BEFORE any of this). Third: that walk was BOXING. `LongHashSetStorage` already holds its elements in a `long[]`, but the iterator and `toArray()` both hand back `Object`, so the sum boxed 2M longs to add them — reading the array directly gives **7 447 → 4 938 (1.5x)**. What is LEFT is the table SCAN: ~2x capacity slack means the walk touches far more memory than the set holds, and 4 938 ps against a bandwidth estimate of ~1 600 says there is still ~3x in it. Floor unknown; the experiment is a scan over a dense array of the same element count |
| ~~`nel` fold~~ | **CLOSED — 8 858 → 1 206 ps/elem (7.3x)** | Not a fusion gap either. It was the one element-based collection with no `sum`, so `FoldToSumRewriter` had nothing to rewrite its fold to. Added `nel::sum`; lands on the deque row, which is what two array-backed collections walking without a callback cost |
| **`map` fold** | **33 593 → 15 142 ps/elem (2.2x)**; the remainder is the WALK | Three costs, one removed. `MezzeMap`'s iterator did `new Entry<>(keys[i], values[i])` PER ELEMENT and the three-argument callback read and boxed the keys even though a value sum never looks at them; `map::sum_values` walks the parallel arrays through `MapStorage.forEach` with neither. What is LEFT is ~15 ns to visit one entry of a HAMT with boxed values — about twice set's 7 270, which is what two scattered columns per element costs. Same conclusion as set: a LAYOUT problem, lever is primitive-specialised storage |
| **a pipeline whose steps are NAMED** | was **no fusion at all** | FIXED: `peelSource` walks an expression tree and had no case for a slot read, so a `let` turned the source Opaque. `ChainLetInliner` substitutes single-use chain bindings. `fusecross` k2, and k4 for the same thing inside a helper |
| ~~a stage from a CAPTURING local helper~~ | **CLOSED — 64 672 → 206 ps/elem (314x)** | And the capture was NEVER the trigger. A do-block `let` bound to a lambda GENERALIZES (#76), suppressed only for a non-ground capture (#15), so a named step is a local POLYMORPHIC function whose body compiles once with a dictionary. `Monomorphizer` cannot reach it — a local binding is not a decl. `LocalLambdaSpecializer` pins it in place, BEFORE the seed walk so the inner `map` gets seeded too |
| a callee whose body is an `if` over two pipelines | FIXED: **214x** (43 416 -> 203 ps/elem) | `ChainCanonicalizer.tryDistributeOverIf` pushes the operation into both branches, sound because they are MUTUALLY EXCLUSIVE — the IR duplicates, the work does not. `stages_cond` disappears from the compiled output entirely |
| a chain through 5 forwarding hops | NOT a gap | measured at the floor — the wrapper inliner collapses the forwarding chain before `MAX_PEEK_HOPS` binds |
| **in-place quicksort against functional quicksort** | **3.2x FASTER** (32 ms vs 104 ms at N=1e5); also 2x faster than `seq::sort`'s 65 ms | `bench/cases/qsort_{mut,pure,native}`. THE ANSWER to "is mut faster": in real algorithmic code, yes, clearly. The counter row below is a micro-benchmark where the functional form needs NO CALL and so fuses; it is call-vs-no-call, not mut-vs-immutable |
| **a mut loop against the same loop written immutably** | seq element write **2.7x FASTER** (15 811 vs 5 924 ps, 42 GCs vs 18); counter 2.6x slower (219 vs 566) — but see below | The `Mut` effect IS erased and in-place IS cheaper — confirmed where allocation dominates. The counter loses to the LOOP TIER, not to the op: `acc + 1` is an expression so `FusedLongTailLoop` takes the loop into Java, while a mut op is a STATEMENT so the branch is a `BlockIR` and both the fused tiers and direct-while require a bare `CallDirectIR`. Widening direct-while LANDED at 1.14-1.37x once measured at a size that reaches steady state — the first reading, at n=2e6, was a 1.6 ms run and said 1.34x WORSE |
| **unresolved `having out` dictionaries, on the cross-language mandelbrot kernel** | **47.5x** (2 833 vs 59.7 ns/px, slope at side 600→1200, identical checksum) | VERIFIES the hypothesis `mezze-benchmark/RESULTS.md` marked Unverified. Takes Mezze from **122x off Scala to ~2.6x** — the gap is not the JIT or Truffle, and the annotation proves the information is present and unused. Same root cause as the row below. `bench/cases/mandelannot` |
| **a polymorphic fn passed as a VALUE to a HOF** | **57x** (1 429 vs 80 873 ps/iter) against the same callback with its type written down; the indirect call alone is 1.9x | `x * 2 + 1` on an inferred parameter is `NumMul`/`NumAdd`, so the callback carries `__dict_mul`/`__dict_add`. Called DIRECTLY it is free (763 ps) because mono specialises the site and the dicts vanish; passed as a VALUE mono cannot reach it — a function argument is not a type argument — so a `dict_wrapper` builds an `Object[]` and a three-field record PER CALL, and `hasDictParams` excludes it from the bypass. Biggest single measured gap on this branch. Lever: specialise the HOF on the FUNCTION argument, which `LocalLambdaSpecializer` already does one level in for 314x |
| **a native on the LEGACY RECORD ABI** | **2.0-3.3x** a scalar-ABI call, measured per op on one kernel before and after | `TypedASTCompiler` emits `CallNativeIR(arg=MakeRecordIR(...))` and the handler looks each argument up by field name. 128 ops were declared against 1298 legacy handlers; map, seq, list, set, bytes, deque and now the whole MUT family ported (300 declared, counted from `native-signatures.tsv`; the 205 this row said before was two short of that file's 207). sorted-map, sorted-set, nel and every HOF remain — a HOF cannot be declared at all, because the scalar path does not hand the handler the `IndirectCallNode` it needs to call back into guest code. And a declared op is still not a FAST op: `CallNativeScalar` puts a `@TruffleBoundary` over a `@Variadic Object[]` on every one of them, which is a further 6.2x where it can be removed — one op so far, see the two entries on it. An earlier 13x figure here was withdrawn — see the experiment section |
| **two IR-identical loops on the SAME ABI** | **4.4x** (3 796 vs 870 ps), 13 GCs vs 3 | `map::size` against `str::size`: same lowered IR (1908 vs 1909 chars, same node sequence), same scalar ABI, and the gap SURVIVES replacing the native's body with `{ _ -> 1L }`. So it is neither the ABI nor the native. Cause is below the IR. CANDIDATE, not yet a conclusion (2026-11-05): the `13 GCs vs 3` is the exact signature of the per-op floor entry below — a native call behind `CallNativeScalar`'s `@TruffleBoundary` allocates ~60 bytes/iter while a call that avoids it allocates nothing. Neither op is registered in `TypedNativeHandlers` (there is no object-receiver shape), so that does not by itself separate these two; the thing to check first is whether one loop FUSES and the other does not, which is the regime confound that cost two withdrawn columns in this log. Then the graph dump |
| ~~a loop made dictionary-passing by its base case~~ | **CLOSED — 30 675 → 682 ps/iter (45x)**, `DictMethodCallIR` 3 → 0 | The entry call specialised all along; the SELF-CALL did not, so the specialized clone recursed into its GENERIC self and every iteration after the first was dict-passing. `deriveTransitiveTypeArgs` needs every typeParam concrete and a `having 'out` var is unreachable from the parameter type — the SEED path had filled those forever, the TRANSITIVE path never did |
| a loop with 4+ parameters | **2.4x** (584 vs 239 ps), no allocation — **CAUSE NOW UNKNOWN** | The old cause said "there is no 4-param branch". **BUILT IT AND IT WAS 1.46x SLOWER** (855 against 584), so the missing tier was never the cause: a 4-param loop already falls to the direct-while path, which emits straight-line bytecode and beats a fused operation that has to decode a 36-bit config per call. Reverted. And the EXISTING 3P tier measures ~neutral on `via_3p` — 12 ms against 13 with it switched off, the OFF readings tighter — so the fused tail tiers above 2 params may be redundant rather than load-bearing. What the remaining 2.4x IS, is now unknown |
| `map::insert`, new map per iteration | 20.4x its own control, 31 GCs (was 35.8x / 47) | persistent map nodes, which it must allocate; the argument record came off it with the scalar-ABI port. Floor unknown but no longer dominated by the ABI |
| string interpolation | 1.8x the concat it desugars to (18 571 vs 10 084 ps) | the one row where a desugaring costs more than its target. Cause not isolated |
| an `Option` returned by a native | +2.5 ns, +10 GCs over the `Bool`-returning lookup | `VariantMatchFolder` folds a syntactic `MakeVariantIR`; a native's return is not one, so the immediately-destructuring match cannot see through it |
| record update, record outlives the expression | 18.1x (14 517 ps), 24 GCs | no record pass can erase a record that outlives its expression — but this is close to a bare native call, so the split is not yet made |
| ~~HOF-reached pipeline~~ | **CLOSED — AT FLOOR.** 86 ps/elem against kernel 1's 91 | `fusegaps` 16 re-measured. The 460x was fixed earlier ON THIS BRANCH and this row was never struck; the narrative below and the "Landed" table both said so while this table did not |
| **a call through a closure / function parameter** | **~8-11x** a 0.25 ns control: generic 2.65 ns, non-generic 2.35, `iter.fold` with an unreducible folder 1.95 (min of 4, control 5 ms in all four) | **THREE ROWS COLLAPSED INTO THIS ONE, and the old cause is WRONG.** They claimed 190x / 30x / 13x and named the dict wrapper. The wrapper is GONE: generic against non-generic was 57 vs 4 ns, a 14x spread, and is now 2.65 vs 2.35 — **13%**. So what remains is one cost shared by every closure-calling route, and it is the indirect call plus argument plumbing, NOT mono. UNMEASURED: what the floor for an indirect call even is |
| **`ScalarCall` vs an inlined loop** | **15%**, paid by six collections on every element of every fold | MEASURED on one native, same kernel: 8 089 hand-rolled against 9 275, control 383 → 366. NOT escape analysis — `@CompilerDirectives.ValueType` changed nothing (9 323 vs 9 275). `call2` is already small with final fields, which leaves the branch on `scalar` and the call-site inlining decision: graph questions |
| `MEZZE_MONO_STRIP=1` | **crashes the compiler** | liveness scan counts a capture as dead. Fix written, unapplied |
| HOF call-site seeding | zero specialisations | `MonoDriver` seed gate |
| ~~a MONOMORPHIC function refused by mono~~ | **CLOSED as a defect; worth ~0 on its own** | `collectTypeVarsInOrder` walks a constraint's type args, so an obligation about variables the signature never names became a phantom type PARAMETER. Root cause was upstream: generalizing a do-block `let` quantifies its vars and the obligation recorded against them rides out onto the enclosing signature. seeds 170 → 188. **It moved the 324x by ZERO** — recorded because a correctly-diagnosed fix that shifts its target by nothing is the useful kind of result |
| `to_seq` | ~4 500 ps/elem | materialising |
| List ops vs Seq | 3-5x | storage |
| record built once, fields read in loop | 2.2x | captured-record field reads |
| binding read twice | ~3.5x | copy propagation refuses two reads |
| binding in a cold branch | ~2.9x | two tail calls → tier declines |
| partial spread | unmeasured | runtime merge; blocked on field order = Shape |
| general-path floor | ~237 ps/iter | paid by every tier-1-inexpressible loop. It does NOT allocate per iteration — `allocsurface` kernel 10 reads 3 young collections against the control's 2 |
| general-loop tier | 7x | IS the SIMD width; needs a stage program in FLP |
| uniformly-OBJECT local/param slots | boxes | `localSlots` discards each `StmtIR.Let`'s kind |
| `Object[] args` callback ABI | boxes twice per element | `SeqNatives.map` boxes on read AND on write |

Every row in the first four lines is one subsystem. **Mono and HOF dispatch are
a single work item**, taking `mezze.mono.effort = fast | full` as ONE budget on
ONE code path — not two paths, because this session already produced a
build-only-shaped bug: a specialisation whose self-call still targeted the
generic hash.

### DIAGNOSED 2026-11-04: mono refuses a function whose type is already concrete

Found while writing `bench/cases/effectsurface`, from the trace and not a
timing. `MEZZE_TYPECHECK_DEBUG_CATEGORIES=Monomorphization`:

    byHash-fail callee=k_tier
      typeParams=['n23, 'o23, 'p23, 'q23, 'r23, 's23, 't23]
      scheme   = ({n: Int} -> Int)
      fn.type  = ({n: Int} -> Int)
      arg.type = {n: Int}

**The type has no variables at all.** `k_tier` is monomorphic. Yet `typeParams`
lists SEVEN variables, none of which occur in the type — they are constraint
vars from the `NumAdd`/`NumSub` chains inside the BODY. `deriveTypeArgs` unifies
`{n: Int} -> Int` against `{n: Int} -> Int`, which binds nothing, so all seven
stay unresolved, the all-concrete gate fails, and the call is not seeded.

THE CONSEQUENCE IS TRANSITIVE, which is why it matters. `MonoDriver`'s own doc
says a generic decl's self-calls are skipped deliberately because "their
concrete callers will reach them transitively through the worklist" — so when
the concrete caller is itself refused, the loop inside it is never specialised
either. `go_t` is declared with TWO parameters and compiles with FOUR: the
extras are `__dict_` params, it allocates per iteration, and it declines the
fused tier at `3P:update-rhs`.

A VARIABLE THAT OCCURS ONLY INSIDE THE BODY IS NOT A TYPE PARAMETER. It is an
internal unknown the body's own inference already settled. `freeVars =
collectTypeVarsInOrder(type)` collects it because that walk descends into a
function type's `abilityConstraints.typeArgs`, and the same over-collection put
the associated-type name `out` into `typeParams` in the 249x case below.

TWO FIXES, SMALLEST FIRST:

1. **In the seed gate**, require only the type args that actually OCCUR in
   `scheme.type` to be concrete; leave the rest to `resolveAbilityConstraints`
   and `fillHavingOutTypeArgs`. Roughly five lines, strictly widens seeding.
2. **At the source**, stop `freeVars` collecting a var that appears nowhere in
   the type. Correct, and the shared root of both failures — but `typeParams`
   drives instantiation and annotation validation too, so the blast radius is
   wide.

Queued rather than fixed: mono is a parked work item, and the payoff needs a
quiet machine to measure. The diagnosis is load-independent, which is why it
could be done now.

### UNKNOWN — never measured

**Effects / continuations.** Handler install and uninstall · handler-chain depth
· innermost vs outermost perform · scoped performers · the `abort` path ·
**real continuation capture** (resume stored, deferred, or invoked twice — the
~0.38 ns above is the fall-through single-return path ONLY) · a loop that
installs a handler per iteration, which invalidates the cached path each time ·
**effects with threads**: once a second thread installs handlers the
`singleThreaded` assumption is invalidated FOR THE LIFE OF THE PROCESS and every
op takes the re-resolving path.

**Async / Loom.** spawn · join · channels · STM · atoms · parallel map ·
cancellation · structured scope.

**Strings.** concat · interpolate · split · format · compare · length · slice ·
builder.

**Collections.** Map · Set · SortedMap · SortedSet · Deque · NonEmptyList, every
op; and `sort`, `group_by`, `zip`, `flat_map`, `to_seq`/`to_list`.

**Scalars.** Float · Frac · Nat · Char · Bytes — arithmetic, conversion,
comparison.

**Pattern matching.** seq patterns · record patterns · string-interpolation
patterns · guards · nesting · many-constructor dispatch.

**Records, remaining.** `RecordUpdateIR` · records as collection elements ·
records across a non-lambda boundary.

**Other.** codec encode/decode · polyglot crossing · failure and exception paths
· comptime · startup time · memory footprint.

### CLOSED

| cell | why |
|---|---|
| a `perform` inside a tier-1 loop | that tier runs the loop in Java; it cannot call a guest handler |
| loading the runtime into the CLI's JVM | `ComptimeHost`: keeping Truffle out of that process is what keeps `mez` starting quickly |

### Re-measure before trusting

Every number in ABOVE FLOOR drawn from `dispatch_probe` and the HOF cases was
recorded in an earlier round and NOT re-verified here. Two harnesses disagreed
**9x** on `seq::fold` in this session, so a recorded number is a hypothesis
until re-measured on the common harness.

### The loop, per cell

measure → **dump the IR and name the refusal** → fix → A/B with a kill switch on
ONE build → gate the suites separately → commit with the number AND the refusals
→ ratchet → update this table. Re-rank only when a measurement invalidates the
order.

**Termination:** zero UNKNOWN rows; every cell AT FLOOR, or ABOVE FLOOR with a
cause being worked, or CLOSED.

---

## Status at a glance

Every number measured on this branch, from a `mez build` JAR on GraalVM CE
25.0.2. The fusion figures are ps/elem at n=2e7, min of five timed calls after
five warmups, ONE KERNEL PER PROCESS — see the methodology corrections below
for why that last condition is not optional.

### Landed, with the gain

| what | before | after |
|---|---|---|
| tail loop, 1 op | 12 ns/iter | **0.2** |
| tail loop, 4 ops | 12.8 ns/iter | **1.05** (Java 1.06 — parity) |
| tail loop, 3 params | 25–34 ns/iter | **1.05** |
| per-iteration allocation, numeric loop | ~100 B | **0** |
| user HOF, generic callback | 57 ns/iter | **5–6** |
| dot-chained pipeline | 40 600 ps/elem | **75** (540x) |
| `enumerate` + map | 124 247 ps/elem | **556** (223x) |
| compound fold | 14 025 ps/elem | **1 448** (9.7x) |
| pipeline split across a call | never fused | fused |
| pipeline behind a higher-order parameter | 41 600 ps/elem | **94** (440x) |
| `seq` map+filter | 229 ps/elem | **92** |

The fusion floor is ~85 ps/elem — about 0.25 cycles, i.e. vectorised — and
map, filter, map+filter, count(pred) and take+filter are all at it.

### FIVE SILENT WRONG ANSWERS, found and fixed

All in machinery that exists only to make things fast, every one of them
returning a plausible number rather than failing:

1. The lazy `iter` walker ignored stage order — `map(*10).filter(<40)` summed
   100 where 60 is right.
2. The `count` sink appended its predicate to a reverse-ordered list — 5
   instead of 4.
3. `composeThroughFusedCallee` spliced two stage lists the wrong way round.
4. `peekThroughMonoWrapper` relocated a lambda's capture into a frame where
   that slot meant something else.
5. `FlpSimd` took a `filterFirst` flag and never read it, in four loops —
   wrong ONLY above `SIMD_THRESHOLD`, which is why every small test passed.

### HOW FAR RECORD ERASURE ACTUALLY GOES

Worth stating precisely, because §1's title ("into an arithmetic call site") is
narrower than the principle and the two get conflated:

- **At a direct CALL SITE it is general, and predates this branch.** When the
  callee's parameter names are known and the call's fields match them,
  `TypedASTCompiler` emits positional arguments and the record is gone —
  for any type, not just numbers. A tiny pure callee is spliced outright by
  `tryInlineDirectCall` and the call disappears with it.
- **§1 is the arithmetic-shaped REPAIR for that**, not the mechanism. Under
  `mez build` the native registry is empty at lowering time, so native arith
  calls kept their record all the way to emission; it is now collapsed there,
  but only for a native with a two-operand `inlineOp` — add/sub/mul/div/mod and
  the comparisons. The 50x and the zero allocation are numeric-loop results.
- **NOT erased:** a record-ABI call that falls back (callee not statically
  known, a SPREAD `..x`, or names that do not match); a record used as a VALUE
  — constructed, stored, returned, kept — which is a real `MezzeRecord`
  allocation that nothing here touched; `enumerate`'s `{i, val}` outside a
  fused pipeline; and field access on a real record.

So the outstanding half of "records are syntax and must disappear" is records
as VALUES, which is also still unmeasured — see the surface table.

### What is missing from what landed

Chosen limits, recorded at the time, not discovered later:

- **Capture materialisation takes only pure, cheap values.** An effectful
  argument declines, because `FusedChainIR` keeps the original call as its
  fallback and would evaluate it twice. Substituting the fallback's arguments
  too would lift that.
- **The compound-fold tree refuses CAPTURES**, so a modulo — whose operand
  needs a dictionary — still declines. The one-level shape classifier does
  handle a captured operand; only the deeper tree does not.
- **Compound fold and `enumerate` both land in the general-loop tier**, not the
  85 ps floor, because `FusedLongPipeline` encodes one op per stage and cannot
  hold a tree, and declines an Enumerate stage outright.
- **`enumerate` resolves `.val`/`.i` only in the left operand of an inline
  arithmetic MAP.** A predicate over the record declines, and so does the
  record reaching the sink — which is correct for `to_seq`, whose result IS the
  records.
- **The inliner's closure-capture gate is untouched on purpose.** Its three
  motivating suites pass without it, which suggests the `saltedLambdaHash` fix
  in the same commit was the real cause — but that is unproven, and a guard
  added for a measured failure does not come out on a hunch.
- **`countParamReads` still bails** on `PerformOpIR` (90 occurrences in one
  build), `FusedChainIR` (80), `HandleIR` (51), `DictMethodCallIR` (24) and
  `MatchIR` (13). Each one disqualifies a whole function from inlining.
- **Two bounds were picked, not measured:** `MAX_PEEK_HOPS = 4` and
  `FOLD_TREE_MAX_DEPTH = 8`.

### Ranked next

1. **7x general-loop tier** — map+map, filter+filter, filter+take, take_while,
   `enumerate`, and what is left of the compound fold. It is the SIMD WIDTH, so
   it needs a stage PROGRAM in FLP, not overhead shaving.

   TWO ROWS CAME OFF WITHOUT TOUCHING FLP. `take` before a filter was already a
   source clamp (91 ps), and `drop` applied straight to a range is now folded
   into the range's START — `range{start,end}` yields `start + i`, so skipping k
   is exactly `range{start + k, end}`, and an over-drop needs no clamp because
   the count going negative is already treated as empty. 658 -> **84 ps/elem**.
   Only the FIRST-APPLIED stage qualifies: a drop below a filter counts
   SURVIVORS, so over 0..9 keeping odds then dropping two leaves 5,7,9 while
   moving the start first leaves 3,5,7,9 — a wrong answer, not a slow one.
   `map+map` and `filter+filter` have no equivalent escape: `x*3` then `x+1` is
   genuinely two ops, and two filters are genuinely two comparisons.
2. **`to_seq` at 50x** (4 541 ps) — the one shape that did not move.
3. **Records as values — 12x to 40x, MEASURED.** See the section below. This is
   now the largest measured gap on the branch and the remaining half of the
   erasure principle: a record carried, returned or spread costs 12-40x the
   same values passed positionally, and a record that never escapes its
   iteration still costs 22x.
4. The frame-materialisation bailout, the empty native registry under
   `mez build`, tail-loop tier 1 declining compound updates, and `%`/NumMod
   class-param resolution (same root as the fold-modulo decline).

---

## Fixed

### 1. Records survived lowering into every arithmetic call site — 50× on tail loops

**Symptom.** An annotation-free numeric tail loop ran at 12 ns/iter and
allocated ~100 bytes per iteration. The same loop *with* a type annotation ran
at 0.25 ns/iter and allocated nothing. 48×, for a type the checker already knew.

**Root cause.** `acc + n` reached bytecode emission as:

```
CallNativeIR(__native_int_add,
  arg = MakeRecordIR([l = ReadLocal(kind=LONG), r = ReadLocal(kind=LONG)]),
  args = [], inlineOp = null, src = fileIndex=50)
```

A record is Mezze's *syntax*, for the typechecker. By emission it should be
positional operands. It was not — and could not be, because:

```
[inliner] inline() entered, 3913 functions; registrySize=0 int_add=NULL
```

**The NativeSignatures registry is EMPTY under `mez build`** — the interpreter
module is `compileOnly` in the CLI, so nothing registers `__native_int_add`.
Every compile-time collapse bails at `signatureOf(name) ?: return node`. The
node therefore arrived from the stdlib snapshot (`int.mz`'s `NumAdd` body) with
`inlineOp = null` and `args = []`.

Two costs, and the second is the larger:

1. A `MezzeRecord` + its `Object[]` + boxed `Long`s per iteration. Confirmed by
   young-GC scaling (linear in iterations) and by a JFR allocation profile
   whose top three classes were exactly `java.lang.Long`, `MezzeRecord`,
   `java.lang.Object[]`.
2. **`FusedLongTailLoop` silently refused the function.** That tier runs an
   entire tail loop in Java with no per-iteration BDSL dispatch; it reads
   operands from `args`, found none, and declined. All three self-tail tiers
   refuse silently, so the only symptom was "programs are slow".

**Fix**, both in the interpreter, where the registry *is* loaded:

- `fltlExtractOp` / `fltlExtractChild` read operands out of the record by
  **declared parameter name** (not field position — records are named, so
  `{ r = .., l = .. }` must still bind correctly, or a subtract silently
  computes backwards).
- A **generic record-ABI collapse at emission**, driven by the signature
  registry, replacing the previous pattern of one hand-written fast path per
  native (`__native_string_concat` was the only one). Fires only when written
  field order matches signature order, because an inline op evaluates
  left-then-right while a record evaluates its fields as written, and an operand
  can be a call.

**Measured, N = 20,000,000:**

| kernel | before | after | Java |
|---|---|---|---|
| `sum_tr` (tail, 1 add/iter) | 238 ms · 12 ns/iter | **5 ms · ~0.2 ns/iter** | 0.05 (folded) |
| `arith` (tail, 4 ops/iter) | 256 ms · 12.8 ns/iter | **21 ms · 1.05 ns/iter** | 1.06 |
| `sum_many` (100k calls × 100) | ~130 ms | **4 ms** | — |
| `fib(30)` (non-tail recursion) | 3 ms · 1.1 ns/call | 3 ms · 1.1 ns/call | 1.06 |
| young GCs @20M / @40M | 46 / 92 | **1 / 1** | — |

So: **~50× on tail loops, ~12× on expression-heavy loops, Java parity on both,
and per-iteration allocation eliminated** (GC count now flat with size).

`fib` was already at parity and did not move — which is what proved calls and
arithmetic were never the problem. The gap was entirely the record.

**Tests.** `interpreter/.../bytecode/NativeRecordOperandTest` — 5 cases on the
operand extraction (name-not-position binding, missing/extra/duplicate fields
declined, no-inline-op declined, order gate). **Verified: 3 fail without the
fix.**

### 2. The fused tail-loop tier stopped at two parameters — 25× on a third

**Symptom.** A 3-param tail loop doing ordinary direct arithmetic — no closure,
no generics, nothing exotic — cost 25–34 ns/iter against 0.45 for the identical
2-param loop. Confirmed as the arity and nothing else by `MEZZE_TRACE_SELFTAIL`:

```
[selftail]  params=2 tier=FUSED
[selftail]  params=3 tier=not-fused
```

**Cause.** `FusedLongTailLoop` existed at arity 1 and 2 only, with the arity
baked into each. Anything wider fell to the general BDSL loop and paid
per-iteration dispatch.

**Fix.** `FusedLongTailLoop3P` plus its recogniser, modelled on the 2-param
pair.

**Why another hand-written arity rather than one variadic operation.**
`@Variadic Object[]` allocates a fresh array per execution — this file's own
record operation says so — and an `Object[]` would re-box the longs the
operation exists to keep unboxed. Specialising the arity is the same reason
`CallDirect1/2/3` are separate. So this is three copies by design, not by
neglect, and a fourth is a mechanical addition when something needs it.

**One real difference at this arity.** The 2-param config packs a single
`pNRhsIsOtherP` *flag*, which works only because with two params "the other
one" is unambiguous. With three it must NAME the source, so 3P packs two bits
per update (0 = literal, else param index + 1). Had that stayed a flag,
`acc = acc + k` and `acc = acc + n` would pack identically and the loop would
quietly compute the wrong sum.

**Measured, N = 20,000,000:** `via_3p` 503 ms → **21 ms**, 25 → **1.05
ns/iter**, checksum verified (n × k).

**Tests.** `FusedTailLoop3PTest` — 7 cases, each pinning one packed field by
making a wrong value produce a different answer (rhs source naming, which param
the condition reads, result index, identity updates, the old-value rule, a
literal-on-the-left condition, multiplication). **Verified: a single wrong shift
in the packing fails 6 of the 7.** They are correctness tests for a new code
path, not failure-without-the-fix tests — the old path computed the same
answers, just slowly — so the bit-flip check is what establishes they cover it.

### 3. Silent refusal made observable

`MEZZE_TRACE_SELFTAIL=1` now reports which self-tail tier each function took.
Added because the above regression was invisible by construction: three tiers,
all declining without a word. A refusal is now a fact you can read.

### 4. Pipeline stages ran out of order — two WRONG ANSWERS, not slow ones

Chasing fusion turned up two correctness bugs in the machinery that exists
purely to make pipelines fast. Both produced a wrong number with no crash and
no diagnostic.

**The lazy `iter` walker ignored stage order.** `IterNatives.foldRange` peels a
map and a filter sitting above a range into one loop — that peel is what keeps a
lazy pipeline from materialising intermediates. It recorded WHICH stages were
present but not which the user wrote last, then always ran the predicate on the
RAW element. Its own comment said so: "filter first (raw element), then map" —
right for `filter(p).map(f)`, silently wrong for the commoner `map(f).filter(p)`.
Over 0..4, `map(*10).filter(<40)` kept everything and summed 100 where the
answer is 60. Eager `seq` was always correct, so the two forms of the same
program disagreed. Fixed by carrying a `filterAfterMap` flag through the peel.

**The `count` sink appended its predicate to an outer-to-inner list.** A
`FusedChainIR`'s stages run outer-to-inner — `stages[0]` is applied LAST — and
`count` has no filter stage of its own, so the canonicalizer adds its predicate
to the peeled stages. It APPENDED, making the predicate the innermost stage, so
a predicate written for mapped values was tested against the source:
`range{0,5}.map{*10}.count{<40}` answered 5, not 4, because every raw element is
under 40. `composeThroughFusedCallee` spliced two stage lists the same way round
and is fixed with it (inert today — the callees that reach it are stage-free
wrappers like `seq::sum`).

Guarded four ways, one per route to the same answer:
`FusedChainStageOrderLoweringTest` asserts the ORDER in the lowered IR,
`FusedChainStageOrderTest` the values through the fused loop,
`IterStageOrderTest` the lazy walker, `DotChainedPipelineTest` the unfused dot
chain. Each case is chosen so the two orders DISAGREE — most map/filter pairs
give the same answer either way, which is how both bugs survived.

**This invalidated one of my own numbers**, which has now been re-measured on a
correct program — see "Measured: what fusion is worth" below. The old
dot-chain figure was taken on a pipeline whose filter kept every element, so its
checksum was wrong and it folded 3× the intended number of elements.

### 5. A dot-chained pipeline never fused at all — one line, and a soundness hole under it

`.map{}.filter{}.fold{}` ran completely unfused while the free-function
spelling of the same program fused. The cause was one entry missing from a
whitelist.

`TrivialWrapperInliner.countParamReads` walks a candidate wrapper's body
counting parameter reads, and bailed on `MakeLambdaIR` — by a wide margin the
commonest shape it met, 263 occurrences in one build against 90 for the next.
Bailing disqualifies the whole function, and the functions in question are the
mono-specialised `Iter` impl forwarders every dot-chained stage goes through.
`ChainCanonicalizer` then saw opaque calls where it looks for the underlying
natives and declined, so nothing fused.

What made it fixable is what mono does to those forwarders: it bakes the user's
stage lambda in as a CLOSED constant and leaves the callback parameter unused.
Measured on the IR: `captures=[]` on every one, pointing at `{x} -> MUL`,
`{x} -> __native_int_lt`, `{acc,x} -> ADD`. A closed lambda reads nothing from
the frame it was built in, so relocating it is a no-op and the forwarder is one
substitution away from the shape that already fuses. `is MakeLambdaIR ->
node.captures.isEmpty()` — accept the closed ones, keep refusing the rest,
because `CaptureIR` holds a frame SLOT INDEX and a caller's lambda-literal
argument has no slot to point at.

**It then crashed, and the crash was a real bug it had exposed.**
`ChainCanonicalizer.peekThroughMonoWrapper` substitutes a call's arguments into
its callee's body; it rewrote a field whose value IS a parameter read and copied
every other field VERBATIM. So a `MakeLambdaIR` argument travelled into the
caller still addressing the callee's slots. With dot-chain fusion producing
chains inside functions that take parameters, `go = { k, lim, bump } -> …` called
from a `main` with an EMPTY frame landed a chain whose stages captured slots 0
and 1 — an out-of-bounds read at emit.

A crash was the lucky outcome. **This also explains a wrong number I had
blamed on stage order**: the `chain` bench's `freefn` predicate is
`{ x } -> x < n`, which CAPTURES `n`, and relocated into another frame it
compared each element against whatever sat in that slot — so the filter kept
everything and the checksum was `599999970000000` instead of
`66666663333333`. Removing the guard fails the free-function case in
`FusedChainCaptureFrameTest` too, so this was reachable on any capturing
pipeline whose caller has a different frame; dot-chain fusion only made it loud.

Fixed by re-expressing each capture against the caller via the
`remapLambdaCaptures` that already existed for the sibling path, and refusing
the move when it cannot be expressed — which costs a fusion opportunity, never
an answer. `go` itself still fuses; only the move into `main` is declined.

Guarded by `FusedChainCaptureFrameTest`, which asserts the property every
relocation path must preserve — no fused chain may capture a slot outside its
own function's frame — over whole programs rather than one case, since the
paths are several and each could lose captures differently.

### 6. A pipeline split across a call now fuses too — captures get a slot

After §5, three shapes remained apart. A pipeline written as a dot chain fused;
one whose stages sat in a callee and whose sink sat in the caller did NOT, and
measured on the lowered IR it produced no fused chain ANYWHERE:

    let stage = { k } -> iter::map { it = xs, f = { x } -> x * k }   # stages here
    let main  = {} -> iter::fold { it = stage { k = 2 }, … }          # sink here

The identical program with a non-capturing stage (`x * 2`) fused. The capture
was the only difference, so this was not a per-call cost — it was the whole
fusion, element-at-a-time, decided by whether a lambda mentioned a parameter.

WHY A CAPTURE BLOCKED IT. `CaptureIR` holds a frame SLOT INDEX, not a value, so
moving a lambda into a caller means re-expressing every capture against the
caller's frame. That works when the caller holds the value in a slot of its own
and is impossible when it passed a literal — there is no slot to point at — so
the move was refused, and with it the fusion.

THREE CHANGES, each needed by the next:

1. **Bind the value, then point at it.** A `FrameExtender` appends a local to
   the function being rewritten, binds the captured value there, and the
   capture names that slot: the let-binding form of inlining. The binding is
   emitted around the chain rather than hoisted to the function's entry, so an
   argument is still evaluated where it was written and not on a path that never
   runs. Restricted to values that are pure and cheap, because a `FusedChainIR`
   keeps the original call as its `fallback` and that call still contains the
   expression — a literal evaluated twice is free, a call is not.

2. **Look through a CHAIN of wrappers, not one.** A user function forwarding to
   the stdlib function whose body is the native is two hops. Stopping at one
   left the source `Opaque`.

3. **Substitute structurally, don't refuse at depth.** A nested stage call
   (`filter { it = map { … }, p = … }`) carries parameter reads and lambdas
   below the top level. Refusing those declined exactly the pipelines worth
   fusing. The walk leans on `recurseChildren`, an exhaustive `when` over the
   sealed node hierarchy, so a new node kind cannot escape it silently.

RESULT, measured on the lowered IR:

| shape | before | after |
|---|---|---|
| spanning, capturing stage | nothing fused | `main` fused + 1 binding |
| spanning, closed stage | `main` fused | unchanged |
| dot-chained, capturing stages | `go` only | `go` AND `main` fused + 2 bindings |

The dot-chained row is the one §5 left as "declined": the chain now relocates
into its caller instead of costing a call there.

GUARDED BY `SpanningPipelineFusionTest` (7 value cases) and two cases in
`FusedChainCaptureFrameTest`. Pointing a capture at the wrong slot does not
crash, it silently multiplies by whatever lives there, so every value case pins
an answer a misread slot would change — several pass two captured values of
different magnitude so a swap is visible, and one captures a value the caller
holds in a slot while another captures a literal, which take different paths
through the remap. The two IR cases assert fusion HAPPENS (`chains > 0`) and
that no capture escapes its frame; both fail with the peel retry removed.

Both IR tests also had to stop matching on `body as? FusedChainIR`: a relocated
chain is the RESULT of the binding block, so that pattern is null for it and the
tests would have quietly stopped checking the case relocation introduced.

### 7. The VECTORISED path ignored stage order — a wrong answer only at scale

With the dot chain fusing, `bench/cases/chain` was rebuilt and still reported
`sum=599999970000000` where the answer is `66666663333333` — the checksum you get
if the filter kept every element. I had blamed this on §4's stage order and then
on §5's captures. It was neither.

HOW IT WAS CORNERED. `bench/cases/capchain` runs the same shapes at n=5 with
LITERAL thresholds and all five lines were correct, so order was fine through
`mez build`. A new `bench/cases/capthresh` runs `chain`'s kernel shrunk to n=5
with the threshold CAPTURED, and all five lines were correct too, so the capture
was fine. That left size. Sweeping `chain`:

| n | sum | correct? |
|---|---|---|
| 5 | 3 | yes |
| 1 000 | 1 498 500 | NO — equals `3·Σ(0..999)`, everything passed |
| 100 000 | 14 999 850 000 | NO |
| 20 000 000 | 599 999 970 000 000 | NO |

Correct at 5, wrong from 1000 up, and every wrong answer exactly "the filter
kept everything". A size-dependent switch, which named the suspect: SIMD.

THE BUG. `FlpSimd` has two vector pipelines — one generating a range into lanes,
one loading a materialised `long[]` — and both take a `filterFirst` flag and
never read it. The flag appeared exactly twice in the file, in the two
signatures, and nowhere in either body. Each loop computed its mask from the RAW
lane values and only then applied the map: right for `filter(p).map(f)`, wrong
for `map(f).filter(p)`. Both scalar tails had the same inversion, so all four
loops were affected. `SIMD_THRESHOLD` is `VL * 4`, which is why every small test
passed and the benchmark did not.

After the fix, every size agrees and the two spellings agree with each other:

| n | sum | correct |
|---|---|---|
| 5 | 3 | yes |
| 1 000 | 166 833 | yes |
| 100 000 | 1 666 683 333 | yes |
| 20 000 000 | 66 666 663 333 333 | yes |

THE LESSON, which cost three wrong hypotheses: **a fast path that engages on
SIZE needs a test sized past its trigger.** Every stage-order test written for
§4 and §6 stayed small enough to miss this entirely, and the shapes they cover
are exactly the ones this broke. `SimdStageOrderTest` is sized past
`SIMD_THRESHOLD` on purpose, covers both pipelines (`iter::range` generates
lanes, `seq::range` materialises an array — separate loops, same defect), and
includes a length that is not a whole number of vectors so the scalar tail runs.
Five of its seven cases fail with the fix removed, with the predicted values:
1498500 for "everything passed", 1000 for the count sink, 2340 just past the
threshold.

That is three places that decided stage order independently — the lazy walker,
the `count` sink's hand-built list, and now two vector loops — and each was
wrong in its own way while the other two were right.

---

## Measured and corrected — claims in the tree that were wrong

### Boxing elimination: worthless for a loop with no locals, 3.8x for one with them

`@GenerateBytecode(boxingEliminationTypes = {})` in `MezzeBytecodeRootNode`
carries a long note about an "infinite compile→deopt storm" with "recursive call
patterns (fib)" requiring an upstream Truffle fix.

- ~~The storm blocks re-enabling it.~~ **Stale.** Flipping it to
  `{long, double, boolean}` on Truffle 25.0.3 produces no storm: `fib(30)` stays
  at 3 ms across warmed runs, no `Addr 0x0`.
- ~~It is "THE setting that makes BDSL beat AST perf on numeric loops".~~
  **Not measurable here.** With it on, a tail loop is 13 ns/iter against 12 with
  it off — inside noise. The docstring also notes the quickenings need per-op
  `@ForceQuickening`, which is why the flag alone does nothing.

Left OFF, with the measurements recorded in place. The real cost was never this
flag.

### The documented tail-loop figure does not reproduce

The generated launcher's own comment cites "12 ms → 139 ms for a 20M-iteration
tail loop", i.e. 0.6 ns/iter. Before the fix above, the same kernel measured
19.3 ns/iter with startup cancelled on the same binary, and the repo's own
`bench/cases/tail_loop_test` agreed (~14.5 ns/iter). **After the fix, 0.2
ns/iter** — better than the documented figure, so the number was probably
real once and regressed when records started surviving lowering.

### A test that could not fail

The first regression test asserted allocation scaling end-to-end. It passed
**identically with the fix disabled**, because a test JVM loads the
NativeSignatures registry and the bug only exists when the registry is empty.
Kept (the invariant is worth holding) with its docs rewritten to say plainly
what it does not cover. This is why "verify the break" is a rule.

---

### FIXED: every store into a BDSL local boxed — 3.8x on any loop carrying a `let`

`boxingEliminationTypes` was measured twice on this branch and rejected twice.
Both readings were right about what they measured and wrong as a conclusion,
because both measured a loop whose values never leave the operand stack — a
tail loop with NO locals, where the flag cannot help and does not.

A loop whose body BINDS anything is a different shape. Every store into an
Object-typed BDSL local boxes, so the same arithmetic costs 11x once it goes
through two lets:

| kernel (n=2e7, min of three) | ps/iter |
|---|---|
| tail loop, values straight into the tail call | 242 |
| the SAME arithmetic through `let lo = i - 1` and `let add = i` | **2 665** |
| …with `boxingEliminationTypes = {long, double, boolean}` | **705** |

3.8x, on what is simply "a loop with a local in it".

MEASURED NEUTRAL ON PIPELINES, which is why this took three attempts to find.
`bench/cases/fusegaps`, off vs on: map+filter 92/92, map 82/87, filter 101/87,
map+map 641/640-675, filter+filter 570/523, drop+map 84/75, count 75/77,
compound fold 877/782, via-a-function-value 94/98. One sample put map+map at
882 and three more put it back at 640-675 — an outlier of exactly the kind this
log has already been caught by twice.

### TWO THINGS MEASURED AND REVERTED on the way to that

Both looked like the obvious fix for records-as-values and both did nothing,
which is how the real cause was cornered.

**An unboxed record layout.** Every record is all-object storage, so two Ints
are two boxes in an `Object[]`; `MezzeRecord` has carried `longFields` and a
typed `FieldLocation` all along. Using them gained nothing and made a spread 34%
worse — because the boxes are not created by the record. The operand stack is
boxed, so the values arrive at construction already boxed whatever the record
does with them; a typed layout only adds a `long[]` and an unbox loop.

That also settles an arity question. A fixed-arity ladder of operations was
written first on the theory that `@Variadic` — an `Object[]` — would box
operands that `long v0, long v1` parameters would not. Wrong for the same
reason: the stack is boxed either way, so primitive parameters only move the
unboxing into generated code. The operation was rewritten GENERIC in the field
count, and making it generic is what showed the approach flat.

**Scalar replacement of a non-escaping record.** LANDED — see above. Written,
measured flat and reverted TWICE before the order above was known, and the
second reversion was on an A/B that tested only the shapes it cannot reach.
What the two reversions did establish is real: with Object-typed locals it
trades one allocation for two, and it sees nothing at all unless the record's
producer is inlined. A LOCAL helper's producer is now inlined too, once the
call to it is devirtualised — see the record section below.

---

## Measured: where dispatch actually costs

`bench/cases/dispatch_probe` runs five routes to the SAME arithmetic, so the
only difference between them is the dispatch mechanism. N = 20,000,000,
in-process, warmed.

| route | params | tier | ns/iter |
|---|---|---|---|
| `direct` — monomorphic tail loop | 2 | FUSED | 0.45 |
| `via_fn` — add behind a named function | 2 | FUSED | 0.30 |
| **`via_ability`** — ability method, one impl | 2 | FUSED | **0.30** |
| **`via_3p`** — direct arithmetic, one extra param | 3 | not-fused | **34** |
| `via_hof` — add arrives as a closure parameter | 3 | not-fused | 80 |

### CORRECTION: most of the "closure cost" was ARITY, not the closure

Reported first as "a closure parameter costs 59 ns/iter, ~130×". That
attribution was wrong, and the control that caught it is `via_3p`: the same
loop with three parameters and DIRECT arithmetic, no closure anywhere.

**Three parameters alone cost 34 ns/iter**, because `FusedLongTailLoop` accepts
only one or two and declines everything wider — confirmed directly with
`MEZZE_TRACE_SELFTAIL=1`:

```
[selftail]  params=2 tier=FUSED
[selftail]  params=3 tier=not-fused
```

So of `via_hof`'s 80 ns, roughly 34 is "this loop has three parameters" and
roughly 46 is the closure call itself. Two separate problems, and the cheaper
one to fix is the arity limit — it is a bound in one predicate, not a new
calling convention.

The lesson is the one that keeps recurring here: a single number compares two
programs that differ in more than one way. The control is what makes it a
measurement.

Two further results, and the first is good news.

### Ability dispatch with a statically-known impl is ALREADY free

A user ability with a single impl costs the same as a plain function call. The
dictionary is not constructed and nothing is dispatched at runtime — the
compiler resolved it. So "make dictionary passing static" is, for this shape,
already done. Where a dictionary still costs is not here; it is the closure
case below, and the polymorphic-callback case the mono notes describe.

### A closure passed as a parameter costs ~59 ns/iter

This is the shape of EVERY `map`/`filter`/`fold` callback, so it is the single
most valuable open item. JFR on that route: `Object[]` (867 samples, now the
top class), `MezzeRecord` (340), `Long` (217).

Mechanism, read out of the tree rather than guessed:

- Lowering DOES take the scalar path. `emitCallIndirect` emits
  `CallIndirectScalarIR` for any field arity ≥ 1, resolving caller field names
  to callee parameter positions at inline-cache time. (Its doc comment still
  claims "only 1-field … wider arities defer to the record path" — **that
  comment is stale**, the code below it says otherwise.)
- The runtime node `CallIndirectScalar2` has a proper identity-guarded inline
  cache with an inlined `DirectCallNode`.
- But its specialisation signature is `Object arg1, Object arg2`. The operands
  are Object-typed, so two `Long`s box on every call, and
  `callNode.call(arg1, arg2)` allocates the varargs array. That is the 59 ns.
- `via_hof` also has 3 parameters, and `FusedLongTailLoop` only accepts 1 or 2,
  so it cannot fuse its way out either.

So the same root cause as §1 — a value that should have been positional and
unboxed is materialised — but now at the closure-call boundary, where the fix
is a typed calling convention rather than a record collapse.

---

## Measured: the dictionary wrapper is the cost — 52 of 56 ns

Reported first as "a closure parameter costs 56 ns/iter". Attributing it needed
the call shape held fixed while only the callee's genericity varied:

| route | callee | ns/iter |
|---|---|---|
| `via_fn` | named, direct call | 0.30 |
| `via_hof_nodict` | closure param, callee NOT generic | **4** |
| `via_hof` | closure param, callee generic | **57** |

`via_hof`'s callee is `add2 = { a, b } -> a + b`, generic over `NumAdd`, and it
is reached through **`dict_wrapper_add2`** — observed by probing which root the
call actually lands on, not inferred:

```
23 x root=LazyStubRootNode    name=lambda$stub
 2 x root=TrampolineRootNode  name=dict_wrapper_add2
```

So the closure call MACHINERY costs ~4 ns and the **dictionary wrapper ~52** —
14x on this shape — paid even though the call site is monomorphic `Int` and the
compiler had everything needed to resolve the impl. Consistent with what was
already known: `MonoDriver` seeds ZERO specialisations for HOF callsites, so the
callback's body never resolves `+` statically.

### FIXED: specialise a HOF on a NAMED callback — 9.5x

The 52 ns above is gone. Mono's closure-arg specialisation only harvested a
LAMBDA LITERAL:

```kotlin
if (value !is TypedLambda) continue
```

so `fold { f = {a,b} -> a+b }` specialised and `fold { f = add2 }` did not. The
documented reason — non-lambdas "can't be specialized without escape analysis" —
holds for a general variable but NOT for a `BindingRef.ByHash`, which names a
top-level function statically and whose hash IS the key the specialisation uses.
Accepting that one case closes the whole chain: HOF specialises -> callback type
becomes concrete -> referenced function monomorphises -> no dictionary, no
wrapper.

| route | before | after |
|---|---|---|
| `via_hof` | 1145 ms · 57 ns/iter | **131 ms · 6 ns/iter** |
| `dict_wrapper_add2` | compiled | **not compiled at all** |

Now level with `via_hof_nodict` (4 ns), i.e. the non-generic floor. Cost: +3
specialisations and +5-8% compile time — against +46 and +21% for the broad
value-reference attempt that bought nothing, which is the difference between a
surgical fix and a scattergun one.

Tests: `ClosureArgRefSpecializationTest`, 6 cases, coverage proven by injecting
a wrong-body splice (3 of 6 failed). Gates: interpreter 7311/0, typechecker
4747/0.

### Four attempts that measured nothing and were REVERTED

Recorded so they are not re-attempted as if new. Both closed genuine gaps;
neither was on the real path.

1. **`canBypassTarget` accepting a raw BDSL root.** The scalar positional path
   is gated on the callee being a `TrampolineRootNode`, and a trampoline is only
   attached when a body HAS tail calls — so a plain `{ a, b } -> a + b` could
   never be answered yes, and fell to the record-building specialisation.
   Extending it changed nothing, because the callee's root is a
   `LazyStubRootNode` wrapping a dict wrapper and neither arm applies.
2. **Giving `CallLocalIR` the scalar treatment.** It emitted
   `beginCallIndirect` unconditionally, so a closure held in a local always
   built a record while `CallIndirectIR` had the scalar path. **UNSOUND, and the
   suite caught it:**

   ```
   CallLocal on a lambda in a local slot yields expected result
     -> RuntimeException: Expected record for field 'x', got: Long
   ```

   The scalar node's record fallback is a `@Cached` specialisation, so on the
   UNCACHED interpreter the raw value reaches a callee that reads its argument
   as a record. That is exactly why the original code deferred to the record
   path, and a standing reminder that "the fast node handles it at run time"
   holds only on the cached path.

Reverted both rather than debugged: no measured benefit, and in one case a real
correctness break.

### THIRD attempt at the wrapper machinery, also zero — the HOP is the cost

After the two reverts above, a third: making the dict wrapper CLOSURE-FREE.
When every resolved dict is a constant `ReadGlobalFnByHashIR`, it can be
inlined into the wrapper's body instead of captured — which is exactly what
`refToDictedOp` in the same file already does — removing the closure
allocation, the `__wrap_*` locals and Let statements, and a capture read per
call.

It fired (confirmed: the compiled root became `dict_wrapper_const_add2`) and
measured NOTHING, in both shapes that could show it:

- `dispatch_probe`, where the reference is hoisted out of the loop: 59 ns/iter,
  unchanged.
- `wrapalloc`, written specifically so the generic reference is evaluated INSIDE
  the loop: per-iteration allocation stayed LINEAR (26/51 young GCs across
  5M/10M, against 27/52 for baseline).

A single run looked like a 16% win (48 vs 57 ns/iter) until the baseline was run
five times and ranged 48-56 on its own. Reverted.

**The conclusion, now evidenced three independent ways:** the dict wrapper's
cost is the extra CALL LAYER, not the machinery around it. Captures, the bypass
guard, and the closure allocation have each been removed or extended with no
measurable effect. Only removing the wrapper itself — monomorphising the generic
callee so no adapter is needed — will move the 52 ns. Do not spend another pass
shaving the wrapper; specialise the callee.

### FOURTH attempt: mono does run — the blocker is that the site type is a VARIABLE

The recorded claim that `MonoDriver` "produces ZERO specialisations" is **STALE**.
With `MEZZE_TYPECHECK_DEBUG=true MEZZE_TYPECHECK_DEBUG_CATEGORIES=Monomorphization`:

```
[mono] seeds=204(generic=204) specs=125 fallbacks=0 siteHits=168
[mono]   byHash visited=212 seeded=202 (fnType=202 argType=0 fail=10)
[mono]   method visited=110 seeded=2   (rcvVarType=3 noImpl=105 unifyFail=0)
```

So direct calls specialise well (202/212). The METHOD path mostly fails
(`noImpl=105`), which is its own open question.

**What was tried.** `collectSeedsFromExpr` only seeds `if (expr is TypedCall)`,
so a generic function used as a VALUE — `use_it { g = add2 }` — was never
seeded at all. Added a `TypedVar` branch deriving type args from the site type.
It worked: `valueRef visited=224 seeded=202`, specs 125 -> 171.

**And it bought nothing, because the site type is not concrete:**

```
valueRef-visit name=add2 typeParams=['f13, 'g13, out]
  siteType=({a: ''f13#2136, b: ''g13#2137} -> 'out#2138)
```

`via_hof = { n, acc, f }` is annotation-free, so its callback parameter's type
is a VARIABLE. `add2`'s instantiation at the reference site unifies with that
variable and stays generic, so there is nothing concrete to specialise on. A/B
on the same binary, toggled by env var:

| | build | kernels |
|---|---|---|
| valueRef seeding ON | 3968 ms | identical |
| OFF | 3290 ms | identical |

**+21% compile time, zero runtime benefit.** Reverted.

**So the blocker is upstream of mono's seeding.** To specialise the callback,
the HOF ITSELF has to be specialised on its closure argument, which makes `f`'s
type concrete inside the specialised body. That is the #424 closure-arg
machinery (`SpecRequest.closureArgs`, `Monomorphizer.closuresEnabled`, on by
default) — and it does not fire for these call sites. **That is the next thing
to look at, and it is the first candidate that is actually upstream of the
problem rather than beside it.**

### A measurement error worth not repeating

One round of "still 57 ns, the fix did nothing" was measured against a STALE
jar. `mez build` bundles the interpreter, so rebuilding the CLI is not enough —
the benchmark app must be rebuilt too, or the change is simply not in the binary
under test. Every measurement after a compiler change now begins with a fresh
`mez build`.

A second one, from the stage-order work: **in-process tests and `mez build` take
different fusion routes.** Every interpreter unit test printed the right value
for `map(f).filter(p)` while the built jar printed a wrong one, because
in-process the emitter declines and falls back to the lazy walker. Worse, the
producer looked like the bug — five consumers read stages in application order
— so I reversed `peelSource`. `BytecodeCompiler.emitCanonicalIterChain` already
does a bare `Collections.reverse` with no comment on it, so that double-reversed
and turned every correct in-process value wrong. Reproduce the `mez build` route
by lowering in the TYPECHECKER with no interpreter on the classpath, and assert
the order in the IR rather than inferring it from a value.

---

## Open — ranked by measured or expected payoff

1. ~~`FusedLongTailLoop` accepts only 1–2 parameters.~~ **FIXED** — see
   "The fused tail-loop tier stopped at two parameters" above. Arity 4+ still
   falls off the same cliff and is a mechanical addition when something needs
   it; nothing measured yet asks for it.
1. **Ability-method specialisation fails on 105 of 110 sites.**
   `method visited=110 seeded=2 (rcvVarType=3 noImpl=105 unifyFail=0)`. The
   receiver type is CONCRETE and still matches no candidate impl via
   `typeMatchesTarget`, with more than one candidate so the single-candidate
   fallbacks do not apply. A matching problem, not missing information.
   Ability methods are what dot-chaining lowers to, so this is the likely reason
   `ChainCanonicalizer` refuses a dot-chained pipeline (2.8x measured) — one fix,
   two wins. NEEDS a diagnostic on that branch first: guessing at
   `typeMatchesTarget` risks binding the WRONG impl, which is a wrong-answer bug
   rather than a slow one.

2. ~~Static dictionary resolution — 52 ns/iter.~~ **FIXED** — see above.
   A generic callee reached through `dict_wrapper_<name>` costs 57 ns/iter
   where the same closure call to a NON-generic callee costs 4. The call site
   is monomorphic `Int`, so the impl is known at compile time and the wrapper
   should not exist. This is monomorphisation work rather than a patch:
   `MonoDriver` seeds zero specialisations for HOF callsites (see
   `docs/perf/407-mono-fusion-investigation.md`).
   *Supersedes this item's previous wording, which blamed a typed calling
   convention. Measured, the boxing is ~4 ns of it and the wrapper is ~52 —
   and ability dispatch with a statically-known impl is already free at
   0.30 ns/iter, so the dictionary cost that remains is this one.*

3. ~~**Dot-chaining refuses fusion.**~~ **FIXED** — see "A dot-chained pipeline
   now fuses" and "Measured: what fusion is worth". The cause was
   `countParamReads` bailing on `MakeLambdaIR`, which disqualified every
   mono-specialised `Iter` impl forwarder a dotted stage goes through. The
   dot-chained and free-function spellings now measure the same.
4. **Tier 1 declines compound updates.** `arith` is at Java parity but still
   runs the BDSL loop, because `FusedLongTailLoop` only accepts single-op
   parameter updates (`left` must be the param, `right` a literal or another
   param). `acc + n * 3 / 2 % 1000` is declined. Extending tier 1 to compound
   expressions is the remaining headroom there.
5. **`seq` chains do not fuse at all.** `seq` dies with `Java heap space` at
   1e8 where `iter` streams to 4e8. Real fusion would be O(1) memory. Switching
   the filter operator only moved the OOM (peak ~1.5n vs ~2n elements).
6. **`%` / NumMod class-param resolution is documented broken** and blocks
   fusion outright (`bench/cases/filter_map_fold` uses `<` instead and says so).
7. **A frame-materialization bailout on every run**, present with the boxing
   flag either way: `opt failed MezzeRootNode[__entry__] Reason:
   SourceStackTraceBailout: Object of type FrameWithoutBoxing should not be
   materialized (must not pass virtual object into an invoke that cannot be
   inlined)`. A materialized frame cannot have its slots virtualized.
8. **Populate the registry in the compile path.** The deeper fix behind §1: with
   `NativeSignatures` available under `mez build`, the compile-time collapse,
   the canonicalizer and the emitter would all reason about natives instead of
   each working around the gap. Blast radius: a dependency change (interpreter
   is `compileOnly` in the CLI).

---

## Measured: what fusion is worth

`bench/cases/chain`, `iter::range{0,n}.map{x*3}.filter{x<n}.fold{+}` both ways
round, on GraalVM CE 25.0.2 from a `mez build` JAR, three runs per size. The
app warms up twice per form and times the third call, so JVM startup is outside
the measurement. Every checksum below was verified by hand against
`3·Σ(0..(n−1)/3)` — the figures this replaces were not, which is how a wrong
filter rode along in them for so long.

**Slope**, `(t(2N) − t(N)) / N`:

| build | dotted | free-function |
|---|---|---|
| canonicalizer OFF (`MEZZE_CHAIN_CANONICALIZE=0`) | 40.6 ns/elem | 40.6 ns/elem |
| canonicalizer ON | 0.075 ns/elem | 0.075 ns/elem |

Raw times behind the slopes, fused: 3 ms at 2e7, 5 ms at 4e7, 17 ms at 2e8,
32 ms at 4e8. Unfused: 399 ms at 1e7, 805 ms at 2e7.

**~540× for the dot-chained form**, which is what the dot-chain fix bought: it
was in the first row with the pass ON before, and is in the second now. The
free-function form already fused and did not move. 0.075 ns/elem is ~0.25 cycles
per element, which is what a vectorised masked-add sum should cost and is
consistent with the SIMD path being the one that runs.

TWO MEASUREMENT TRAPS HIT HERE, both worth not repeating:

- **`mez build` caches in `.mez`.** Setting `MEZZE_CHAIN_CANONICALIZE=0` and
  rebuilding changed nothing — same times, same binary — because the build was
  reused. Deleting `.build` alone is not enough; the unfused baseline only
  appeared after `rm -rf .build .mez`. A flag that silently does nothing looks
  exactly like a flag that proves your hypothesis wrong.
- **The bench's own `ns/elem` column is integer division.** At 2e7 elements and
  4 ms it prints `0`, which reads like a dead measurement and is really 0.2.
  The slope has to come from the millisecond figures at sizes where they are
  large enough to divide.

---

## Measured: which pipeline shapes fall off the fast path

One shape at the floor is not an answer. `bench/cases/fusegaps` runs the shapes
a real program writes through one harness so the cliffs rank themselves.
n = 2e7, min of five timed calls after five warmups, ONE KERNEL PER PROCESS,
every checksum printed and hand-checkable.

| shape | ps/elem | x floor |
|---|---|---|
| map | 86 | 1.0 |
| count(pred) | 79 | 0.9 |
| filter | 88 | 1.0 |
| take+filter | 91 | 1.0 |
| map+filter | 93 | 1.1 |
| any(pred) — short-circuits, not comparable | 190 | — |
| seq (array source) map+filter | 229 | 2.6 |
| filter+filter | 530 | **6.0** |
| take_while+map | 575 | **6.5** |
| map+map | 631 | **7.2** |
| filter+take | 666 | **7.6** |
| drop+map | 671 | **7.6** |
| to_seq (materialises) | 4 394 | **50** |
| compound fold (`acc + x*2 - 1`) | 14 025 -> **1 448** | 159 -> **16** |
| enumerate+map | 124 247 -> **556** | 1412 -> **6.3** |

THE THREE GAPS, in payoff order:

1. ~~**`enumerate` is not fusable at all — 1412x.**~~ **FIXED — 124247 -> 556
   ps/elem, 223x.** There was no `StageIR.Enumerate` and no mention of
   enumerate anywhere in `ChainCanonicalizer`, so a chain containing
   `.enumerate{}` could not be peeled: the source classified as Opaque, nothing
   fused, and a `{i, val}` RECORD was allocated per element.

   THE RECORD IS NOW GONE, which is the records-are-syntax principle actually
   carried out. The stage carries nothing: the loop keeps a counter, the element
   stays where it is, and a downstream `x.val` resolves to the element while
   `x.i` resolves to the counter. Two locals rather than one, because the
   element needs ITS index while the counter moves on. The increment sits at the
   stage's own position, so it numbers exactly the elements that REACH the
   enumerate — a filter above it drops elements that have already been numbered,
   which is what `enumerate` means, and a filter BELOW it numbers only the
   survivors.

   WHERE IT DECLINES. The record is live from the enumerate until a MAP consumes
   it; a filter or take passes it through. If it is still live at a predicate or
   at the sink — whose classifiers do not resolve its fields — the chain
   declines and the original record-building pipeline runs, correct and slow.
   A BARE read of the parameter also declines, because the record does not exist
   for a bare read to denote; handing out the raw element in its place would be
   a wrong answer rather than a slow one. `to_seq` of an enumerate is exactly
   the case that must decline, since the records ARE the result.

   Still on the table: a predicate over the record (needs the same field
   resolution a mapper now has), and the vectorised path, which declines an
   Enumerate stage — so this lands in the general-loop tier at 556 rather than
   the 89 ps floor. Gap 3 covers that tier.
2. ~~**A compound fold body — 159x.**~~ **FIXED — 14025 -> 1448 ps/elem, 9.7x.**
   `classifyFolderBody` matched `acc OP x` plus one element transform, which is
   two of the three nodes in `acc + x * 2 - 1`; a refused folder declines the
   WHOLE chain, so the map before it stopped fusing too. Such a body needs no
   shape: it is a pure arithmetic tree over the folder's two parameters and
   integer literals, so the loop emits it as written with the parameter reads
   rewritten to the accumulator and element locals. Every operator is the same
   overflow-checked operation the unfused folder would run, so the inline form
   computes exactly what calling it computes. `map+filter` (91) and `map+map`
   (641) were unmoved. It now sits in the general-loop tier, so gap 3 below is
   what remains of it.

   ONE LIMITATION, recorded rather than hidden: a MODULO in a fold body still
   declines. Its operand needs a dictionary, which makes the folder CAPTURE, and
   the tree path refuses captures — a capture addresses the lambda's own capture
   array and needs lifting to the enclosing frame first. The one-level shape
   classifier does support a captured operand, so `acc + x * k` is unaffected;
   only a DEEPER body with a capture declines. `CompoundFoldFusionTest` asserts
   that case's VALUE and says why it is not asserting fusion.
3. **A SECOND stage of any kind — 6-7.6x, and it is the SIMD WIDTH.** One map
   or one filter is free; adding either costs 7x, because
   `tryBuildFusedLongPipeline` accepts at most one of each and everything else
   falls to the general loop — as do `filter+take` (needs a counter, not a
   source clamp), `take_while` and `drop`.

   NOT an overhead problem. 625 ps/elem is about 2 cycles for a six-operation
   loop, which means Graal compiles the general loop WELL; the fast path is at
   86 ps because it VECTORISES, 4-8 elements per iteration. The 7x is the vector
   width. Closing it means extending the vectorised path to a multi-stage
   pipeline — a stage PROGRAM rather than FLP's one-map-one-filter config — not
   shaving overhead off the scalar loop.

   MEASURED AND REJECTED: `boxingEliminationTypes = {long, double, boolean}`
   does nothing for this tier. Min of 15 timings per kernel, off vs on:
   map+map 629/630, drop+map 625/635, filter+filter 498/540, map+filter 84/90,
   map 85/74, compound fold 1399/1040. One kernel gains 26%, two regress ~8%,
   the tier does not move — so the flag stays off, as the comment in
   `MezzeBytecodeRootNode` already argued from the tail-loop evidence. An
   earlier run of this same experiment, before one-kernel-per-process, "showed"
   two 17x wins; that was the compiler lottery.

### FIXED: a pipeline behind a higher-order parameter — was 460x, now at the floor

| kernel (n=2e7, min of five) | was | now |
|---|---|---|
| 01 called directly, inferred | 90 | 92 |
| 16 reached through a function VALUE, inferred | 41 600 | **94** |
| 17 reached through a LAMBDA argument, inferred | 43 000 | **93** |
| 19 own single-use HOF, inferred | 71 000 | **101** |
| 18 reached through a function value, ANNOTATED | 99 | 84 |

**THE CAUSE, and it was one missing shape.** The registry that says
`__native_int_lt` IS `LT` belongs to the interpreter, so under `mez build` the
compiler cannot collapse the call and a predicate reaches the emitter as
`__native_int_lt { l = x, r = <captured n> }` wherever monomorphisation did not
inline the impl. `classifyFolderBody` already recognised that form — there is a
test for it — but the UNARY classifiers did not, so the predicate was
unclassifiable, and an unclassifiable stage declines the WHOLE chain. The
pipeline then ran the lazy walker: `IterNatives.foldRange`, caught
OSR-compiling in the slow run under `-XX:+PrintCompilation` and absent from the
fast one.

Both unary classifiers now normalise a native arith/compare body to the inline
op it denotes before matching shapes, so fusion no longer depends on whether
mono fired. The order guard matters and is tested: a record evaluates its
fields in the order WRITTEN while an InlineBinaryOp evaluates left then right,
so the record form collapses only when the written order already matches the
signature's — otherwise two evaluations would swap.

**IT WAS BROADER THAN THE HOF CASE.** Two other shapes met the same
unclassifiable predicate and moved with it: `seq` map+filter 229 -> **92**, now
at the floor, and the compound fold 1448 -> **877**. Nothing else moved outside
noise.

HOW THE DIAGNOSIS WENT, since three of the four steps were wrong turns:

- A CONFOUND I nearly shipped as the finding. Kernels 16 and 17 share one HOF
  with two callbacks, while the annotated 18 has its own HOF used once — so
  "annotated" and "one callback" were not separated. Kernel 19 holds the
  callback count at one with types inferred: still 71 000. The annotation was
  the variable after all.
- THE IR WAS A RED HERRING. Lowered in the typechecker — the `mez build` route
  — the chain is `FUSED` in every shape, and the INFERRED version fuses MORE
  than the annotated one (which simply calls `k`). Fusion in the IR says
  nothing about what the emitter then accepts.
- SO WAS THE EMIT LOG. `MEZZE_CHAIN_CANON_EMIT_DEBUG=1` prints identical
  emissions for the fast and slow kernels, because both kernels live in one
  binary and the whole program is compiled either way. It cannot say which copy
  RUNS.
- WHAT ACTUALLY FOUND IT: `-XX:+PrintCompilation`, which showed the slow kernel
  OSR-compiling `IterNatives::foldRange` — the lazy walker — and the fast one
  never touching it. Then the lowered IR of the two versions side by side, where
  the inferred one carries one extra lambda the annotated one does not:
  `params=[x] caps=1 -> CallNative`.

ONE MORE OF MY OWN MEASUREMENTS WAS WRONG on the way: the first reading sorted
on the wrong field to pick a minimum, and a later one took the third of five
timings instead of the least. The figures here come from extracting the number
and sorting it numerically.

### Two methodology corrections, both of which produced published-looking numbers

**A higher-order harness destroys what it measures.** The first version of this
bench passed each kernel to a `bench { name, n, f }`. Taking a pipeline function
as a VALUE blocks its monomorphisation — the reference site carries a type
variable — so every kernel measured its generic, unfused copy: `map+filter` read
32 258 ps/elem through the harness against 93 called directly. A 350x
measurement artifact, and also a real cliff worth its own entry: a function used
as a value loses fusion.

**Fifteen kernels in one JVM measure a compiler-thread lottery, not shapes.**
With two warmups each, three consecutive runs of the SAME binary gave
`03 filter` 215, 7097 and 7474 ps/elem, and `10 count` 5867, 248, 5254. Every
kernel lands in a fast or a slow mode depending on whether its compiled code
happened to be ready by its timed call — bimodal, up to 35x apart. An earlier
draft of this table reported the 6-7x tier as 15-22x and `count` as 31x purely
from that noise, and a boxing-elimination experiment "showed" two 17x wins and
six regressions that were all noise. One kernel per process, five warmups, five
timings, take the minimum.

---

## Measured: what a record costs when it is a VALUE

`bench/cases/recordcost`, n=2e7, min of three timed calls after three warmups,
one kernel per process. All five kernels compute the SAME checksum
(`200000010000000`), so a kernel that were fast because it dropped work would
show up as a different number rather than a better time.

| kernel | ps/iter | x control |
|---|---|---|
| 1 two values carried POSITIONALLY (control) | 237 | 1 |
| 3 record built ONCE, fields read per iteration | 564 | **2.4** |
| 2 record carried as loop STATE, one built per iteration | 2 844 | **12** |
| 4 record RETURNED per iteration, born and dies inside it | 5 146 | **22** |
| 5 record SPREAD update `{...st, i = ...}` per iteration | 9 558 | **40** |

**This is the larger prize, and bigger than the pipeline tier it was weighed
against.** Erasure at a direct call site is general and lands these same two
values at 237 ps when they travel as arguments; the moment the record is a
VALUE — carried, returned, updated — it costs 12x to 40x. Kernel 4 is the one
that should not be possible: the record is created and discarded inside a single
iteration, never escapes, and still costs 22x, which says these records are not
reaching Graal in a form its escape analysis can virtualise.

Kernel 5 is the floor on what call-site erasure can ever reach, because a spread
is one of the three shapes that falls back to the record ABI by construction.

### FIXED: a record that never escapes its binding is not built — 7x

A/B on one CLI with the pass's kill switch, a loop whose module-level helper
returns a two-field record, n=2e7:

| | ps/iter | young GCs |
|---|---|---|
| without the pass | 5 026 | 33 |
| with it | **713** | **3** |
| the same loop with no record at all | 229 | 2 |

Three young collections against the control's two: nothing is allocated per
iteration, so the record really is gone. A JFR profile of the slow form names
what goes away — `java.lang.Long` 211 samples, `Object[]` 86, `MezzeRecord` 61,
the 1:1:2 a two-field all-object record predicts.

THREE CHANGES IN THIS ORDER, and none of them pays without the ones before it:

1. `boxingEliminationTypes` on, so a store into a local is free. Before it this
   pass traded one allocation for two and measured flat.
2. The producer INLINED, so the record's construction and its uses sit in one
   function. A module-level helper is inlined by `tryInlineDirectCall`.
3. This pass, which replaces the binding with one local per field.

THE REFUSALS MATTER MORE THAN THE REWRITE. A bare use of the binding, a lambda
capturing it, a read of a field it does not declare, a spread, a duplicate
name — each disqualifies it, because in those cases the record is a value in
its own right. And the ENTRY BLOCK is never touched: a module-level `let` lives
there and its binding is read from other functions BY HASH rather than through
the block the use check scans, so rewriting one removed a record something else
still read and `BasicLanguageFeaturesTest > test basic records` failed with
"null cannot be cast to MezzeRecord". The gate caught that; four cases in
`RecordScalarizationTest` pin it and the other refusals.

### TWO CORRECTIONS TO WHAT THIS LOG SAID EARLIER

Both were mine, both were measurement errors with the same root, and the
striking-through is the point of this log.

**"A local helper is never inlined — 8.8x" was wrong.** That 8.8x was this pass
being present in one CLI build, not the top-level-versus-local distinction. With
the pass off the two forms measure 5 026 and 5 513 ps — no real difference. A
a local helper really was not inlined, which is now fixed on its own terms
below and worth 7.3x — but it was not what that 8.8x measured.

**"The scalarizer is useless" was wrong.** It was A/B'd on the two kernels whose
helper is LOCAL — the shapes it cannot reach, since the record is built inside
the helper and returned — and never on the one whose helper is top-level, which
is the shape it fixes. It is 7x there.

THE ROOT OF BOTH: the pass was edited out of the source but the CLI was NOT
rebuilt before measuring, so a "clean tree" reading was really the pass still
running. Every A/B since uses one CLI and the pass's own kill switch at build
time, which cannot drift that way.

### FIXED: the same record behind a LOCAL helper — 7.3x, and the abstraction is now free

The open item above. A/B on one CLI, `MEZZE_DISABLE_DEVIRT` read by
`mez build`, n=2e7, min of 5, one kernel per process:

| kernel | off | on | | young GCs off → on |
|---|---|---|---|---|
| 1 positional (control, no record) | 230 | 224 | — | 2 → 2 |
| **4 record from a LOCAL helper** | **5 207** | **715** | **7.3x** | **36 → 3** |
| 6 the same loop hand-inlined, no record | 687 | 707 | — | 3 → 3 |
| 7 the same helper at MODULE level | 623 | 625 | — | 3 → 3 |

THE POINT IS KERNEL 4 AGAINST KERNEL 6, not the speedup. 715 against 707: a
helper that builds a record now costs what writing its two fields out by hand
costs. The abstraction is free, which is the property worth having — the 7.3x
is just how far it was from free. Three young collections against the control's
two says nothing is allocated per iteration. The control moving 230 → 224 says
the machine was honest while this was measured, and the checksums are identical
across all four.

WHAT WAS ACTUALLY WRONG. A `let`-bound lambda was called through its SLOT:
`CallIndirectScalarIR(ReadCapture(k), …)`, a dispatch on a function value. Being
indirect, nothing downstream would look at it — not `tryInlineDirectCall`, so
the record stayed inside the helper and was RETURNED, which put it out of reach
of the scalarizer, which is why that pass measured as nothing on this shape.
A capture-free lambda is the same function at every call through its name, so
the call can be direct, and then the existing machinery does the rest:

    devirtualise → inline → scalarize → no allocation

Each step is worth nothing without the next. That is why this looked like three
failed attempts rather than one unfinished chain.

THE CAPTURED CASE IS THE WHOLE JOB. A helper is almost never called from the
frame that bound it — `go` captures `split` — so inside the loop the name is a
capture read, not a local slot. A version that knew only the current frame's
slots devirtualised nothing in any realistic shape. The map is therefore carried
across the closure boundary by capture INDEX, built from the parent's locals and
the parent's own capture map, so it chains to any nesting depth.

KEYED BY SLOT, NOT BY NAME, which is what makes it exact rather than heuristic:
every `let` mints a fresh slot, so a shadowing binding cannot alias an older
entry and nothing needs saving per block. Shadowing in ONE block turned out to
be impossible anyway — Mezze rejects a second `let f` outright.

AND THE INLINER HAD TO LEARN THE GENERIC SHAPE. `i - 1` inside a local helper
is not arithmetic in the IR: let-generalisation gives the body ONE compilation
for all types, so it lowers to `i.sub { r = 1 }` — a call through a field read —
and `isInlinable` refused exactly that. Accepting it is what makes inlining
monomorphise: spliced into a caller whose receiver is a known Int, the op the
callee could not resolve resolves. The generic body becomes concrete code at the
call site, with no annotation anywhere.

A DRIFT HAZARD CLOSED ON THE WAY. Three `when`s had to agree on the node set —
`isInlinable` (what is safe), `substituteArgs` (what gets rewritten) and
`countNodes` (what the budget sees). A shape the first allowed and the second
missed would keep the CALLEE's param reads, bare slot indices, in the CALLER's
frame: a wrong value, not a crash, surfacing nowhere near the cause. The latter
two are now structural walks over `transformChildren`, which `IRWalkTest` pins
to reach every child slot, so only the whitelist decides anything.

GUARDED BY `LocalHelperDevirtualizationTest` (4 lowering cases) and
`LocalHelperCallShapesTest` (8 behavioural ones). With the kill switch on,
exactly the two devirtualisation cases fail and the refusal and control cases
still pass. The behavioural file exists because a lowering test cannot see what
breaks here: a direct call commits to arity and parameter order, so partial
application, a spread argument, the helper handed around as a value, a capturing
helper and an inner block's shadow all have to keep working, and the IR looks
reasonable either way. Two of those cases failed on first run — both my own test
sources, one of which showed that the language forbids the rebinding I was
testing for.

NOT COUNTED BY `ReadFieldIR`. An ability op on a receiver lowers to a field read
too, so counting those as record reads mixes the two completely, and `acc + p.add`
makes the name `add` ambiguous with the op of the same name. The tests count
records built and reads of field names only the record uses.

### FIXED: a `let` in a loop body cost 3x — and it was not the binding

The question above, measured. `bench/cases/letcost`, A/B on one CLI with
`MEZZE_LET_PROPAGATE=0` read by `mez build`, n=2e7, min of 5:

| kernel | off | on | | young GCs off → on |
|---|---|---|---|---|
| 1 no binding (control) | 222 | 226 | — | 2 → 2 |
| 2 ONE binding | 638 | **224** | 2.85x | 3 → 2 |
| 3 two bindings | 677 | **220** | 3.08x | 3 → 2 |
| 4 four bindings | 711 | **223** | 3.19x | 3 → 2 |
| 5 one binding read TWICE | 773 | 786 | — | 3 → 2 |
| 7 binding in a cold branch | 642 | 634 | — | 3 → 3 |

and on `recordcost`, where kernel 6 is the same loop with the record's helper
hand-inlined: 687 → **227**, 3.03x, against its own control at 224.

ONE, TWO AND FOUR BINDINGS NOW COST WHAT NONE COST: 224, 220, 223 against the
control's 226. The `off` column is what says why — 638 for the first binding,
then 677 and 711 for two and four. A 415 ps STEP for writing any binding at
all, then ~37 ps for each one after it. A per-binding cost cannot look like
that. It was never the binding.

IT WAS A TIER DECLINING. Three tiers compile a self-tail loop, and the fastest —
`FusedLongTailLoop`, which runs the whole loop in Java with no per-iteration
bytecode dispatch — matches on the loop branch BEING the self tail call:

    if (elseBr instanceof CallDirectIR ec && ec.isTailCall() …)

A `do` block makes that branch a `BlockIR` whose RESULT is the call, so the
match fails, the tier declines silently, and the loop drops to the general path.
Writing the step as a named binding cost 3x for no reason visible in the program.

THE TWO BODIES ARE THE SAME IR. Dumped side by side, the fused loop and the
declined one differ in exactly the block:

    elseBr = CallDirect(self, [SUB(i,1), acc.add{r=i}])                 FUSED
    elseBr = Block([Let(nxt, SUB(i,1)), Let(cur, i)],
                   CallDirect(self, [nxt, acc.add{r=cur}]))             declined

Substituting the bindings produces the first form node for node, which is why
the fix is a substitution rather than a new tier. And the emptied block has to
COLLAPSE: an empty `BlockIR` around the call is still a `BlockIR` and still
declines, so substituting and leaving the wrapper would have measured as
nothing. Its test therefore asserts the SHAPE the tier tests for, not a count of
bindings — a count would have passed with the wrapper still in the way.

### THE SCOPE RESTRICTION IS A BUG FIX, NOT A PRECAUTION — the debugger caught it

Run over every block in the program, the pass broke `DapEndToEndTest`, and
correctly. A breakpoint set on a `let` line stopped on the line AFTER it
("expected: <2> but was: <5>") because there was no longer any code on that
line, and a conditional breakpoint over a local found no local to read. Three
tests, all real.

The fix is not to weaken the pass but to put it where it pays: the tail-call
branch of a self-tail loop, which is the only place a block costs anything. An
ordinary binding elsewhere was being removed for NO GAIN — outside such a loop
there is no tier to qualify for — so the DAP failures were pure cost. Inside
one there is nothing to lose, because that tier compiles the loop to Java with
no per-iteration bytecode and a breakpoint in its body could never have stopped
there anyway. Narrowing it kept every number above unchanged.

WORTH GENERALISING FROM: "this optimisation is sound" and "this optimisation is
worth doing here" are different questions, and only the second one was actually
in doubt. The debugger is the part of the system that can tell the difference,
and it is the only test that did.

### WHAT REFUSING LEAVES, and it is now the largest measured gap in the log

The refusals are honest in the table rather than argued: a binding read twice
(786 ps) would duplicate work, and a binding holding a CALL (kernels 4 and 7 of
the record section, 640 and 623 ps) cannot move because nothing in the IR says
whether the callee has effects.

Then kernel 6 of `letcost` — a binding holding a call too big to inline —
measured **161 285 ps/iter** against the control's 222, with ~400 young
collections. 700x, and copy propagation moves it by 1.01x, so it is not the
binding and not the block.

`MEZZE_DUMP_FN` says what it is. The helper is

    let ident = { i } -> i + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0

and it lowers to EIGHT dictionary parameters and eight `DictMethodCallIR`, each
allocating a three-field record and reading a global by hash:

    ident params=[i, __dict_add@c13, __dict_add@f13, … , __dict_add@z12]
    body=DictMethodCall(dictSlot=7, {it=DictMethodCall(dictSlot=6, …), r=0,
                                     __dict_lt=ReadGlobalFnByHash(…)})

Zero `InlineBinaryOpIR`. Annotated `{ i: Int } -> Int`, the same function is
inlined into its caller as eight `InlineBinaryOpIR` and four native calls — no
dictionaries, no allocation. **An annotation is worth 700x here**, which is the
one thing this effort is not allowed to leave standing.

IT IS NOT A DICTIONARY BUG. `mez check` prints the inferred signature:

    inferred : { i: 'a } with NumAdd Int 'a having 'out = 'c,
                             NumAdd Int 'c having 'out = 'd, … -> 'b

and `inferred { i = 1.5 }` type-checks. The function really is polymorphic,
because every numeric ability is heterogeneous —
`ability NumAdd 'r for 'l having 'out` in `stdlib/std/abilities/numeric.mz` —
so `i + 0` does NOT pin `i`: each `+` threads a fresh `'out` into the next
operand position. Eight operators, eight genuinely distinct constraints, eight
dictionaries. The constraints then leak outward: `main` picks up twelve of them.

WHERE IT STOPS, read straight off `mez check`, which prints every inferred
signature. This is the probe to start any further work from:

    one          : { i: 'a } with NumAdd Int 'a having 'out = 'b -> 'b
    two          : { i: 'a } with NumAdd Int 'a having 'out = 'c,
                               NumAdd Int 'c having 'out = 'b -> 'b
    from_literal : {} -> Int
    main         : {} with NumAdd Int 'a having 'out = Int -> {}

THREE FACTS IN FOUR LINES:

- **ONE operator is already enough.** `one` is generic too, so a chain is not
  required and the count of operators is only a multiplier on the cost. (An
  earlier note in this log said a single operator "always worked" — that was
  wrong, and it was wrong for a bad reason: a one-operator helper is small
  enough to be INLINED, so no function is emitted and nothing shows up in a
  dump. Absence from a dump is not evidence of a concrete type.)
- **`from_literal` collapses to `Int`.** `1 + 0 + 0 + 0` resolves completely,
  so the machinery that threads one operator's `out` into the next operand DOES
  work. What it needs is a concrete starting receiver. The chain is not the
  problem; the unpinned parameter is.
- **`main` keeps a constraint it should not.** Every call passes `i = 1`, yet
  `main` still carries `NumAdd Int 'a having 'out = Int` with the OPERAND
  unresolved. A concrete call site is not collapsing its callee's constraint.

### FIXED: the chain is monomorphised — 249x, and the annotation is worth nothing again

`bench/cases/letcost` kernel 6, same CLI, n=2e7, min of 3:

| | ps/iter | young GCs |
|---|---|---|
| before | 161 285 | 370 |
| after | **647** | **3** |
| control (kernel 1), either way | 220 | 2 |

249x, identical checksums, and the control says the machine was honest. 647 is
the ordinary "binding holds a call" level from the table above, so the
dictionary cost is entirely gone and what is left is the tier decline already
documented. The annotated form is no longer faster than the inferred one, which
was the whole point.

HOW IT WAS FOUND, since the first two guesses were wrong and the trace was
right there. `MEZZE_TYPECHECK_DEBUG=true` with
`MEZZE_TYPECHECK_DEBUG_CATEGORIES=Monomorphization` prints a per-run summary and
a refusal line per call:

    [mono] byHash visited=135 seeded=130 (fnType=130 argType=0 fail=5)
    [mono-trace] byHash-fail callee=chain
      typeParams=['y12, out, 'b13, 'e13, 'h13, 'k13, 'n13, 'q13, 't13]
      scheme=({i: ''y12#2038} -> 'out#2068)
      fn.type=({i: Int} -> Int)

NINE type params, of which the scheme TYPE mentions two. Unifying the surface
type against the call site binds those two; the other seven exist only inside
the constraints, and `fillHavingOutTypeArgs` is the function whose whole job is
to fill them from each resolved impl's associated `out`.

IT FILLED THE WRONG ONE. The target was chosen as "the first unresolved param
that is not among this constraint's own args" — and in the list above that is
`out`, the function's RETURN. So constraint #1's output was assigned to the
return instead of to `'b13`; `'b13` stayed a variable; every later constraint
was skipped for having a non-concrete argument; the all-concrete gate failed.
Accidentally correct for ONE constraint, wrong for a chain of any length.

THE FIX READS EACH CONSTRAINT'S OWN BINDING. A type-level `AbilityConstraint`
carries `associatedTypeBindings` — the `having 'out = 'c` map whose value is the
variable that constraint's output IS — so there is nothing to guess. The
typedast `TypedAbilityConstraint` the function used instead does NOT carry them,
which is why it was guessing at all; taking the constraints off
`scheme.type.abilityConstraints` is the enabling change. And it iterates to a
fixpoint, because one constraint's `out` is the next one's operand.

Afterwards: `byHash … seeded=131 (fnType=130 argType=1 fail=4)`, `chain` gone
from the refusal list, specialisations 104 → 106, and `chain` no longer compiled
at all — the specialised copy inlines into its caller as eight
`__native_int_add`.

TWO WRONG TURNS WORTH KEEPING, both of which looked like the answer:

- **The single pass.** `fillHavingOutTypeArgs` genuinely did not feed its own
  fills forward, which cannot chain one `out` into the next operand. Fixing only
  that changed the lowering NOT AT ALL, because the mis-targeting above defeats
  it: the one fill it does make goes to the wrong variable. It was reverted
  rather than kept as a plausible-looking no-op, and the log said so before this
  entry corrected it. Both halves were needed; only one was visible.
- **`method-noImpl field=add receiver=Int`**, which appears 90 times in the
  trace and looks exactly like the bug. It is not: the comment at that site says
  a receiver whose impl is CONCRETE has no entry among the generic candidates
  and lands there legitimately, and that reading it as a gap "cost one wrong
  hypothesis already". It nearly cost a second.

### FIXED: fold the desugared operator before judging a binding — kernel 4 reaches the control

`recordcost`, same A/B (`MEZZE_LET_PROPAGATE=0` vs on), n=2e7, min of 5:

| kernel | off | on | |
|---|---|---|---|
| 1 positional (control) | 233 | 225 | — |
| **4 record from a helper** | 688 | **221** | 3.11x |
| 6 the same, hand-inlined | 693 | **218** | 3.18x |
| 7 helper at MODULE level | 623 | 631 | — |

KERNEL 4 IS NOW AT THE CONTROL: 221 against 225. A loop whose helper returns a
record — 5 207 ps/iter when this effort started — costs what the same loop
written with no record and no helper costs. The same A/B before this change gave
kernel 4 694 → 642, a 1.08x, so the delta is this fold and nothing else.

`x + y` desugars to `x.add { r = y }` and STAYS a call through a field read
whenever the receiver's type was unknown when the body was compiled — which for
a `let`-bound helper is always, since let-generalisation gives the body one
compilation for all types. A binding holding one is a binding holding a CALL as
far as copy propagation can tell, so the block survived and the tier declined.
The bytecode compiler already folds that shape at emit time, so folding it
earlier produces nothing new; it just happens early enough to matter. Chain:
fold → substitute → block empties → tier fires.

ONE TABLE. `inlineOpFor` was a switch over operation names in the bytecode
compiler and now delegates to `InlineOpMapping` in `ast`, whose binary half is
DERIVED from `OperatorMapping.BINARY_OPERATORS`. Two copies would have drifted
silently — a new operator would stop folding and the only symptom would be
slower programs.

### FIXED: a record that is only a parameter's fields is not built — 12.3x

The kernel ranked next below, done. A/B on one CLI with
`MEZZE_PARAM_SCALARIZE=0` read by `mez build`, n=2e7, min of 5:

| kernel | off | on | | young GCs |
|---|---|---|---|---|
| 1 positional (control) | 238 | 231 | — | 2 → 2 |
| **2 record as LOOP STATE** | **2 766** | **225** | **12.29x** | **26 → 2** |

AT THE CONTROL: 225 against 231, with two young collections against the
control's two, so nothing is allocated per iteration. Kernels 3-7 unchanged
within noise, checksums identical throughout.

    let go = { st } ->
      if st.i <= 0 then st.acc
      else go { st = { i = st.i - 1, acc = st.acc + st.i } }

One record per iteration, built by the caller and destructured by the callee —
the same function. `RecordScalarizer` could not reach it: that pass replaces a
`let` whose VALUE is a record, and here the record arrives as a PARAMETER. Same
idea one level out: expand the parameter into one per field, rewrite every
caller to pass them positionally. `MEZZE_TRACE_SELFTAIL` then goes
`params=1 tier=not-fused` → `params=2 tier=FUSED`, because two parameters make
the branch a self tail call over positional arithmetic, which is what
`FusedLongTailLoop` matches on. The allocation is most of the 12x; the tier is
the rest.

CHANGING AN ARITY IS ONLY SOUND IF EVERY CALLER CHANGES WITH IT, so this pass
takes the whole function map and refuses any candidate whose references it
cannot classify: lambdas only (a top-level declaration may be invoked from
outside the map — an entry point, a `pub` function called by name — where no
rewrite can follow); every mention of the hash classified, with a global read
or partial application refusing; a closure only ever `let`-bound to a slot
whose every read is a call this pass rewrites; and an exact field set at every
call site.

### THE REFUSAL THAT HAD TO BE RELAXED, and why it is not a loophole

Every recursive local lambda CAPTURES THE SLOT IT IS STORED IN:

    Let(slot=1, MakeLambdaIR(go, captures=[CaptureIR(slot=1)]))

so that it can call itself through its own value. A capture reads the slot, so
"this slot is only ever called" refused EVERY recursive loop — the entire point
of the pass. The first version of this pass therefore fired on nothing, and the
dump is what said why.

That capture is dead by construction whenever the self-calls are direct: the
slot has not been written yet when the `MakeLambdaIR` is evaluated, which is
exactly why a recursive let's self-call is emitted as a direct call in the first
place. So the pass allows precisely that case — the candidate's OWN closure,
whose body never reads the capture index it occupies — and still refuses a
different lambda capturing the slot, or this one actually reading the capture.

Worth keeping as a fact about the IR: **a recursive local lambda always carries
a self-capture, and it is dead whenever its self-calls are direct.** Any
analysis that treats "captured" as "escapes" will refuse every recursive loop
in the language.

### RANKED NEXT: the record kernel nothing in this effort moved

Every kernel on the committed build, same A/B, n=2e7, min of 3 — so the two that
did not move are now the largest measured gaps here:

| kernel | ps/iter | young GCs | vs control |
|---|---|---|---|
| 1 positional (control) | 231 | 2 | — |
| 2 record as loop state | 225 | 2 | at the control |
| 4 record from a helper | 221 | 2 | at the control |
| 6 hand-inlined | 220 | 2 | at the control |
| 3 record built once, fields read | 532 | 4 | 2.3x |
| 7 helper at module level | 629 | 3 | 2.7x — native metadata, below |
| **5 record SPREAD per iteration** | **9 377** | **44** | **40x** |

Four of seven are now AT the control. What is left is three different things:

**Kernel 5 — DONE, ~40x.** It was not a floor. A spread update per iteration
lowers to a `__spread__` field carrying the whole source record, which the
builder merges with the explicit fields at run time.

| | off | on | | young GCs |
|---|---|---|---|---|
| 1 positional (control) | 399 | 454 | — | 2 → 2 |
| 2 record state | 3 752 | 326 | 11.5x | 21 → 2 |
| **5 record SPREAD** | **13 955** | **278** | **50x** | **35 → 2** |

MEASURED UNDER CONTENTION, and the control says so — 399 and 454 against its
true ~230, differing between columns — so the absolutes are inflated and the
ratios approximate. The GC counts carry the result: allocation does not care
about CPU contention, and 35 → 2 is the per-iteration allocation gone. Against
the 9 377 ps measured on a quiet machine, with kernel 2's post-fix 225 as the
floor, the clean figure is ~40x.

THE TYPE SYSTEM CANNOT AUTHORISE THIS, which is what makes it interesting. The
parameter's row is OPEN:

    [spread] explicit=[i, acc] pure=true type=RecordType closed=false fields=[i, acc]

The type permits more fields than it names, so dropping the spread on its
authority would be wrong — and annotating does not help, because the type then
arrives as an unresolved `TypeApplication(St)`.

[ParamScalarizer] can authorise it, because it already requires EVERY call site
to pass a record whose explicit field set equals exactly the fields read. By
induction over those sites the parameter only ever holds a record with exactly
those fields: the outer call supplies them, and the self call supplies them plus
a spread of a value that already satisfied the same property. **The
whole-program check closes the row by PROOF, which is stronger than what the
type says.** A spread with any field left over still refuses, and so does a
spread of any record the pass knows nothing about.

TRIED AND REVERTED FIRST: dropping a dead spread at record construction in the
typechecker, keyed on the source type's field set. `MEZZE_TRACE_SPREAD` showed
it fired on NOTHING — inferred cases are open-row, annotated ones are
unresolved aliases, and every spread in the stdlib is PARTIAL (one field
overridden out of six, or out of eighteen). It went out rather than staying as a
plausible-looking no-op.

**STILL OPEN, and now the measured-nothing caveat applies: the PARTIAL spread.**
`{ ...req, chunks = x }` over a CLOSED eighteen-field type is the common stdlib
shape, and expanding it into explicit field reads would replace a runtime merge
with reads. What stops it is field ORDER: a record's field order is its Shape,
so expanding at the spread's position reorders the remaining fields and a loop
alternating two shapes is worse than the merge. Doing it properly means emitting
in the source's declared order, which is only observationally safe when every
explicit value is side-effect-free. No measured case needs it yet, which is
exactly why it is written down rather than built.

**Kernel 3, 2.3x.** A record built ONCE outside the loop with its fields read
per iteration. Nothing allocates per iteration (4 collections), so this is the
field-read path itself, not allocation — a different question from everything
above, and the smallest of the three.

**Kernel 7, 2.7x**, which is the native-metadata item below and not about
records at all.

### FIXED: the build writes the native signatures down — 2.9x, and kernel 7 reaches the control

A/B on one CLI with `MEZZE_NATIVE_SIGS=0`, n=2e7, min of 5:

| kernel | off | on | | young GCs |
|---|---|---|---|---|
| 1 positional (control) | 229 | 242 | — | 2 → 2 |
| **7 helper at module level** | **671** | **229** | **2.93x** | 3 → 2 |
| 3 record built once, read | 536 | 536 | — | 3 → 3 |

At the control, and `letcost` is neutral (k3 234/230 against a 260/242
control). Its loop body is now three `InlineBinaryOpIR` with no native call and
no record, where it had `CallNativeIR(__native_int_subtract,
arg=MakeRecordIR(…), isPure=false, inlineOp=null)` plus a surviving binding. The
whole chain fires at once: metadata → collapse to arithmetic → recomputable →
the binding substitutes → the block empties → `tier=FUSED`.

THE FIX IS DATA, not a dependency. `StdlibPrecompiler` already runs with
`:interpreter` on its class path and already loads the declarations — its
comment says why — so it now also writes them out:

    __native_int_add	true	ADD	LONG	l:LONG,r:LONG
    __native_int_subtract	true	SUB	LONG	l:LONG,r:LONG

128 lines into `META-INF/mezze/native-signatures.tsv`, read by
`NativeSignatures` when no bootstrap hook is installed. Data crosses the
boundary Truffle must not, so `ComptimeHost`'s rule — "keeping Truffle out of
its own process is what keeps `mez` starting quickly" — stays intact, and
`mez dev`'s subprocess split stops mattering.

`register` now accepts an IDENTICAL re-registration, because a test JVM or the
interpreter itself has both the resource and the declarations; a CONFLICTING one
still throws, since two disagreeing descriptions of one native's ABI would
mis-order its arguments. The gradle task tracks `:interpreter` on its classpath,
so editing a declaration regenerates the table rather than leaving a stale one —
a wrong param order being worse than no metadata at all. Absent or malformed is
not an error: the behaviour that was there before, slower and correct.

### WHAT THE DIAGNOSIS WAS, kept because the first two attempts were wrong

Kernel 7 did not move, and it is not the pass. Its helper is top-level, so its
parameter type IS pinned and the operator resolves all the way to a native:

    Let(__rec_p_lo, CallNativeIR(name=__native_int_subtract,
                                arg=MakeRecordIR({l = i, r = 1}),
                                args=[], argKinds=[],
                                isPure=false, inlineOp=null))

while the declaration in `IntOps.kt` reads
`NativeOp("__native_int_subtract", …, pure = true, inlineOp = InlineOp.SUB)`.
Three things are missing from that node, and all three are declared:

- `inlineOp`, so the peephole cannot collapse it to `InlineBinaryOpIR` — no JIT
  fast path, and no fused tier for the loop around it.
- `pure`, so nothing downstream may move or elide it.
- `params`, so `args`/`argKinds` are EMPTY and the call goes out on the legacy
  record ABI — a record built per call.

WHY. `NativeSignatures` lives in `ast` and is populated by interpreter
class-load. The hook that forces those declarations to load, `NativeBootstrap`,
installs itself into `NativeSignatures` from its OWN static initialiser — so
until something touches that class the registry is empty, and `signatureOf`
latches `bootstrapped = true` after running a null hook, so it stays empty for
the rest of the process. `MezzeLanguage` calls the bootstrap when the Truffle
language initialises, which is after user code has been lowered.
`StdlibPrecompiler` calls it for its own run — so the STDLIB was compiled with
the metadata and user code never was. Nothing in `mez` itself called it.

This is the "`mez build` EMPTY native registry" note from the earlier
`list_fold_routes` work, now with the mechanism and the three consequences
named rather than just the symptom.

AND IT IS NOT A ONE-LINE FIX, which is worth saying because the paragraph above
reads like one. Calling the bootstrap from `mez`'s own `main` fails outright:

    WARNING: NativeBootstrap not reachable:
      com.mezze.interpreter.nodes.builtin.dsl.NativeBootstrap

The interpreter is not on the CLI's classpath AT ALL. `:cli` keeps `:interpreter`
off its compile classpath on purpose (#220), and at runtime the interpreter lives
in a separate slim `mezze-runtime.jar` that `ComptimeHost` loads into a child
`URLClassLoader` only when a macro is actually called — deliberately lazy. Worse,
`mez dev` runs the program in a SUBPROCESS, so the JVM that lowers the code and
the JVM that has the natives are never the same one; and `mez build` SERIALISES
the lowered IR into the output jar, so whatever metadata was missing at lowering
is missing permanently.

So the metadata has to reach the compiling JVM without the interpreter. Three
ways, and the choice is a design decision rather than a performance one:

1. **Load the runtime jar into the CLI's JVM before compiling.** RULED OUT, and
   not on a guess about the cost: `ComptimeHost`'s own header states the rule —
   "`:interpreter` is deliberately off the CLI's compile class path … keeping
   Truffle out of its own process is what keeps `mez` starting quickly". The
   mechanism exists there and is deliberately LAZY, reached only when a macro is
   actually called. Doing it on every compile is exactly what that design
   forbids.
2. **Declare the metadata in `ast`** — where it is needed at compile time — and
   have the interpreter's `NativeOp(...)` ASSERT at registration that its
   handler matches the declared signature. One source of truth for the metadata
   and a loud check that the handler agrees, which is what the current
   same-site declaration was protecting. Mechanical across the six `*Ops.kt`
   files and the DSL.
3. **Generate the signatures as a resource** in `ast` at build time from the
   interpreter declarations. Keeps one declaration site, adds a build step.

(2) is the one that matches the registry's own stated design — "the signature
registry lives in `ast` … only carries IR-shape info, no executable code" — and
with (1) ruled out it is also the only one that respects the CLI's rule about
never loading Truffle into its own process. (3) respects it too, at the cost of
a build step.

AND NOT DONE, deliberately: making `+`, `-` and `%` homogeneous again. The
recorded design decision says "leave NumAdd/NumSub/NumMod homogeneous
(heterogeneous + is usually a bug worth rejecting)" with heterogeneity intended
for `*` and `/` only, so the shipped `ability NumAdd 'r for 'l having 'out` is
broader than the decision behind it. That changes what programs type-check, so
it is not a performance decision to make unilaterally — and after this fix it
is no longer a performance decision at all.

SCOPE LIMIT WORTH KNOWING: this pass and the record scalarizer are wired into
the user-code pipeline only. `StdlibPrecompiler` runs MutEraser,
TrivialWrapperInliner and ChainCanonicalizer — not these two — so a stdlib loop
carrying a binding still declines the tier.

### MEASURED AND REJECTED: an unboxed record layout

The obvious reading of the table above is that the cost is BOXING — every record
is all-object storage, so an `{ i, acc }` of two Ints holds two `Long` boxes in
an `Object[]` and unboxes on every read. `MezzeRecord` has had `longFields`
and a typed `FieldLocation` all along, and `MakeRecord`'s own comment says a
later phase can use them. So: use them.

It does not pay. True minimums, before and after a typed all-long layout:

| kernel | all-object | all-long |
|---|---|---|
| 1 positional | 237 | 239 |
| 2 record state | 2 844 | 2 875 |
| 3 field read | 564 | 546 |
| 4 record return | 5 146 | 5 320 |
| 5 record spread | 9 558 | **12 842** |

Nothing gained and the spread 34% worse, so it is reverted. **The reason is
that the boxes are not created by the record.** With
`boxingEliminationTypes = {}` the BDSL operand stack is boxed, so the values
arrive at record construction already boxed whatever the record then does with
them: a typed layout only adds a `long[]` allocation and an unbox loop, and the
spread path clones one more array.

That also kills the arity question. A fixed-arity ladder of operations was
written first, on the theory that `@Variadic` — which is an `Object[]` — would
box operands that `long v0, long v1` parameters would not. That is wrong for the
same reason: the stack is boxed either way, so declaring primitive parameters
only moves the unboxing into generated code. The operation was rewritten
GENERIC in the field count, which is what showed the whole approach flat.

SO THE 12-40x IS NOT BOXING INSIDE THE RECORD. It is the allocation itself, a
boxed operand stack, and escape analysis not firing on a record that never
escapes its iteration. The routes that remain are therefore: do not build the
record at all for a non-escaping one with a statically known shape — scalar
replacement, the "erase it" half of the principle — or make the operand stack
carry primitives, which `boxingEliminationTypes` was measured not to do for the
pipeline tier and would need its own investigation.

ONE LATENT BUG CAME OUT OF IT AND IS FIXED. `ReadFieldByName`'s hot path read
`objectFields` unconditionally while caching a location index resolved from the
shape, so NO record could use the LONG or DOUBLE layout the Shape machinery
supports — every such read hit a zero-length array and threw
ArrayIndexOutOfBoundsException. That is why `ShapeCache.getOrCreateSingleLong`
was dead code. Storage is part of the shape, so it is now cached beside the
location and the switch folds with it.

MEASUREMENT NOTE: at n=2e6 these numbers are warmup-dominated and reorder
themselves — kernel 3 reads 23 451 ps there against 564 at n=2e7. The size has
to be large enough that the loop is compiled, which is the same trap as the
compiler lottery recorded above.

---

## MEASURED AT LAST: the unmeasured surface — `bench/cases/coresurface`

Everything above was records, bindings, calls and loops. Three parts of the
language had never been benchmarked at all, so there was no way to rank them —
and the live risk was that a session spent on records had left something
STRUCTURAL costing 100x. n=2e7, min of 3, one kernel per process, every kernel
computing the SAME checksum as the control:

| kernel | ps/iter | young GCs | vs control |
|---|---|---|---|
| 1 positional control | 222 | 2 | — |
| 2 ONE EFFECT OPERATION per iteration | 1 119 | 5 | 5.0x — see the correction |
| 3 Option constructed + matched | 1 892 | **17** | 8.5x |
| 4 three-constructor variant matched | 1 940 | **17** | 8.7x |

**EFFECTS ARE NOT STRUCTURALLY EXPENSIVE, and that was the question worth
answering.** If an effect operation had needed a real continuation capture each
time, this is where it would have shown and no peephole would have fixed it.

**BUT "5.0x" WAS THE WRONG NUMBER, and the correction is below** — it mixed the
cost of an effect operation with the cost of the loop leaving the fused tier.
Kernel 5 was added to separate them, and the honest figure for an effect
operation is ~0.38 ns, not 1.1.

VERIFIED NOT FOLDED, because the checksum cannot tell: the operation returns 1
and is used as the loop's decrement, so a constant-folded `one` would compute
the same answer at the control's speed. `MEZZE_DUMP_FN` shows
`PerformOpIR(effectHash=…, operationName=one, …)` still in the loop body. The
measurement is of a real effect operation.

**THE VARIANT NUMBERS ARE AN ALLOCATION CLIFF**, and the same one as records.
Seventeen young collections against two, and the two variant kernels cost the
same (1 892 and 1 940) although one has two constructors and the other three —
so it is not match dispatch, it is the construction. The IR says exactly why:

    MatchIR(scrutinee = MakeVariantIR(tag=…, tagName=Some,
                                      payload=MakeRecordIR([val = i])),
            cases = [CaseIR(pattern=Variant(tag=…, fieldNames=[val],
                                            fieldSlots=[3]),
                            bindings=[BindingIR(slot=3, LONG)], body=…), …])

The variant is CONSTRUCTED AS THE SCRUTINEE OF THE MATCH THAT IMMEDIATELY
DESTRUCTURES IT. Per iteration that allocates a payload record, a box for the
Int inside it, and a wrapper carrying the tag — none of which outlives the
expression. Same shape as `let p = { … }` with every use a field read, one level
up, and the tags are known on both sides at compile time.

NEXT, and it composes with what is already here: fold a match whose scrutinee is
a `MakeVariantIR` of a known tag into the one case that matches, binding the
pattern's `fieldSlots` — which already exist — to the construction's field
values. That turns the loop branch into `BlockIR([Let(slot, value)], <tail
call>)`, which is precisely the shape [LetCopyPropagation] substitutes and
collapses, after which the tier fuses. The expected landing is the control.

### CORRECTION: "an effect operation costs 5x" mixed two costs — it is ~0.38 ns

The control above is on `FusedLongTailLoop`; the effect kernel is NOT.
`MEZZE_TRACE_SELFTAIL` says so — `tier-1 declined at line8599`, the check that
every parameter update is arithmetic over parameters. A `PerformOpIR` is not,
so the loop drops to the general path, and the 5x was the tier gap plus the
effect operation measured against a tier-1 control.

KERNEL 5 SEPARATES THEM: the same loop taken off tier 1 for a reason that has
nothing to do with effects — a decrement from a helper too big to inline, which
fails the same check — and returning 1, so the checksum is unchanged. n=2e7,
min of 3:

| kernel | ps/iter | young GCs | |
|---|---|---|---|
| 1 control, on tier 1 | 242 | 2 | — |
| 5 tier control, off tier 1 | 479 | 3 | **leaving tier 1: ~237 ps** |
| 2 effect operation, off tier 1 | 857 | 7 | **the operation: ~378 ps** |
| 3 Option match, folded back onto tier 1 | 235 | 2 | the variant fix holds |

So an effect operation costs **~0.38 ns** marginal, which matches the
implementation: `PerformD` caches the handler, the op's field index, its call
target and its closure values as compilation constants under a
handler-chain-stable assumption and calls through an inlined `DirectCallNode`.
There is no chain walk and no name lookup per call. The four extra young
collections across 2e7 iterations are the operation's argument record.

AND THE TIER GAP IS THE REAL FINDING: **~237 ps, about 2x, for any loop tier 1
cannot express.** That is not a cliff to fix in the way the others were — tier 1
runs the whole loop in Java with no per-iteration bytecode, so it can never call
a guest effect handler, and a `perform` in a loop simply cannot be on it. What
is worth attacking is the FLOOR of the general path, which every such loop pays:
effects, a call too big to inline, more than one tail call, a multi-way branch.

WHY THIS CORRECTION MATTERS MORE THAN THE NUMBER: a kernel measured against a
control on a DIFFERENT tier attributes the tier to whatever the kernel was
testing. Three of this log's earlier claims were measurement errors of their own
kinds; this is the fourth shape — a control that is not comparable. Any kernel
added here that leaves tier 1 needs a tier control beside it.

## FIXED: `size` was not a chain sink — 1503x on `map`, 36x on `filter`

A/B on one CLI with `MEZZE_SIZE_SINK=0`, `bench/cases/ds_matrix` at N=1e6,
min of 10, total ns:

| op | off | on | |
|---|---|---|---|
| `seq::fold` (control) | 382 453 | 351 125 | unchanged |
| **`seq::map` + size** | **9 247 376** | **6 151** | **1503x** |
| **`seq::filter` + size** | **7 567 659** | **208 724** | **36x** |
| `list::map` + size | 11 103 978 | 1 461 606 | 7.6x |
| `list::filter` + size | 9 194 919 | 1 722 776 | 5.3x |
| `list::count` (control) | 1 609 338 | 1 662 606 | unchanged |

`size` was not in the sink table, so a chain ending in it could not fuse and the
stage MATERIALISED a whole new collection to answer how MANY. Per element:
a box on read, a box written into an `Object[]`, then
`SeqStorageFactory.fromArray` scanning everything with three `instanceof` checks
each before unboxing into a typed array. Two allocations and two extra
traversals, discarded immediately.

A `Count` sink already existed — "increment per element" IS `size` for a chain —
so this is one name added to the recognition, not a new sink. With it the mapped
element is never used and partial evaluation reduces the map chain to the length
outright, which is where three orders of magnitude come from; `filter` cannot do
that (the predicate must run) and lands at 0.21 ns/elem.

THE REFUSAL IS THE CORRECTNESS OF IT. `size` on a materialised collection is
O(1); routing that through a counting sink would make it O(n) — a regression
dressed as an optimisation, which no value test would catch. The pass refuses
when the peeled chain has no stages, and `SizeChainSinkLoweringTest` pins it.

TWO TESTS, FOR TWO DIFFERENT RISKS. `ds_matrix` prints times and not values, so
a 1503x win there is equally consistent with returning nonsense:
`SizeChainSinkTest` checks nine shapes by value — size-preserving, filtering,
truncating, composed both ways, empty source, zero survivors, bare, and List.
Those pass with the sink DISABLED too, which is the rule for every perf change
here, so the discriminating test is the lowering one.

HOW IT WAS FOUND, because the route matters more than the number. Chasing a 2.2x
kernel led to `ds_matrix`, whose N was hardcoded at 10 000 with a note to raise
it "once List chunked backing lands" — and at 10 000 the medians run 2-10x the
mins, which is what an under-warmed measurement looks like. Parameterising N and
running at 1e6 made the outliers legible. The win was sitting in an existing
bench that nobody had run at a realistic size.

### Measured along the way, at N=1e6

| op | ns/elem |
|---|---|
| `range.fold` direct | 0.06-0.16 |
| `seq::sum` (native) | 0.36 |
| `seq::fold` | 0.37 |
| `seq::any` (miss) | 0.42 |
| `seq::count` | 0.10-0.74 (a 7.6x variance band — this harness is noisy for cheap ops) |
| `list::fold_left` | 1.32 |
| `list::count` | 1.6-3.7 |

Fold, sum and any are sub-nanosecond. The List equivalents run 3-5x their Seq
counterparts, which is storage and is its own ledger row. And the header's
recorded claim that `list::map`/`filter` were "100-644x slower than seq" is
STALE — they are comparable now.

## Surface still to cover

Not yet measured on this branch. Listed so the gaps are visible rather than
assumed fine.

| area | status |
|---|---|
| primitives & primitive ops | Int add/sub/mul/div/mod covered by §1; Float, Frac, Nat, Str, Char, Bytes unmeasured |
| function calls | direct and self-tail covered; closure calls, generic/dict calls, HOF callback-per-element unmeasured |
| dispatch | ability/impl dispatch is open item 1; variant tag dispatch unmeasured |
| recursion | non-tail at parity (`fib`); mutual recursion and deep non-tail unmeasured |
| records | field access, construction, spread/update unmeasured — and §1 shows records are where the cost hides |
| variants | construction, match, tag dispatch unmeasured (`getConstructorName` has a known AOT bug) |
| pattern matching | guards, nested, seq patterns unmeasured |
| data structures | Seq/List/Map/Set/Deque construction and access unmeasured |
| iteration | `iter` vs `seq` partly measured (open items 2 and 4) |
| effects | `perform`/handler dispatch, handler install cost, row depth unmeasured |
| continuations | capture/resume cost unmeasured |
| Loom / async | spawn, await, race, scope, cancel, channels unmeasured |
| strings | concat, interpolation, split, format unmeasured |
| codec | JSON encode/decode and the derived `Encode` walk unmeasured (known walker gap) |
| startup / warmup | ~800 ms JAR startup, 88 ms native; time-to-steady-state unmeasured |
| compile-time | `mez check`, incremental rebuild, LSP latency — tracked separately |

---

## The cost inventory: what a loop body actually costs

`bench/cases/allocsurface`, eleven kernels, N = 2e7, min of several runs with
one process per kernel, young collections from `-Xlog:gc`. Every checksum
verified at N = 1000 first; kernels 3-9 compute different checksums from each
other because the operations are not interchangeable, and each one's expected
value is stated beside it in the case.

**Built with the dev CLI** (`cli/build/install/mez/bin/mez`), which matters — see
the last subsection.

| kernel | young GCs | ps/iter | x off-tier control |
|---|---|---|---|
| 1 control — 2-param arithmetic, **FUSED tier** | **2** | **229** | 0.29x |
| 10 **arity control** — 4-param arithmetic, off that tier | **3** | **801** | 1.0 |
| 9 `map::size` — native call, Int return, no lookup | 19 | 7 818 | 9.8x |
| 3 `str::concat` | 19 | 10 084 | 12.6x |
| 8 `map::contains_key` — native lookup, Bool return | 21 | 13 359 | 16.7x |
| 2 record update `{ ...r, a = i }`, record outlives it | 24 | 14 517 | 18.1x |
| 7 `seq::get` — native lookup, Option return | 30 | 15 432 | 19.3x |
| 5 `map::get` — native lookup, Option return | 31 | 15 889 | 19.8x |
| 4 string interpolation | 34 | 18 571 | 23.2x |
| 11 **dict control** — kernel 10 plus two adds in the base case | 43 | 26 836 | 33.5x |
| 6 `map::insert`, new map per iteration | 47 | 31 118 | 38.8x |

### The one that reframes the rest: a native call costs ~7.6 ns

`map::size` takes a map built once outside the loop, performs no lookup, and
returns an `Int`. It costs 7 818 ps and 19 young collections against the off-tier
control's 801 and 3. **So calling any stdlib native in a loop costs about ten
times the entire loop it sits in**, before the native does anything.

That is the common factor under every row from 9 to 6, and it means those rows
are not mostly about maps, strings or records. Subtracting it:

- the `Option` costs ~2.5 ns and ~10 collections (`map::get` 15 889 against
  `contains_key` 13 359) — `VariantMatchFolder` folds a syntactic
  `MakeVariantIR`, and a native's return is not one, so the match that
  immediately destructures it cannot see through
- interpolation costs ~8.5 ns MORE than the concat it desugars to (18 571
  against 10 084), the one row where a desugaring is dearer than its target
- `map::insert` at 31 118 is a persistent map allocating nodes, as it must
- record update at 14 517 is close to a bare native call, so most of it may be
  the call rather than the record; not yet separated

### The arity cliff: 3.5x, and no allocation

Kernel 10 is four parameters of pure arithmetic over themselves.
`BytecodeCompiler` dispatches the fused tier only for `paramCount` 1, 2 and 3 —
there is no 4-param branch — so it compiles off the tier while differing from
kernel 1 in nothing else, which `MEZZE_TRACE_SELFTAIL` confirms at `params=4`.
801 ps against 229, and 3 young collections against 2.

So **an off-fused-tier loop does not allocate per iteration.** The mechanism that
looked obvious in the source — `TailCallDirectN` takes `@Variadic Object[]`, so
an array is materialised per execution and every `long` boxed into it, and
`TrampolineRootNode` stores it to a frame slot across the loop back-edge where
escape analysis cannot reach it — does not account for these numbers. A
mechanism found by reading code is a hypothesis.

The 3P emitter's own comment records the same cliff one arity down: a 3-param
loop cost 34 ns against 0.45 for the 2-param version, before the 3P tier existed.
Generalising past three is tractable — the 3P emitter's analysis is already
arity-generic, looping over `paramCount` and filling `updateOp[]` / `updateRhs[]`
/ `rhsSrc[]`; only the config packing and operand emission are fixed. Its stated
reason for not generalising (`@Variadic Object[]` allocates per execution and
re-boxes the longs) holds for a per-CALL operation but not for a per-LOOP one,
where one execution is the whole loop and an entry-time array is O(1) amortised.

### The dict control: 33x, from a branch that runs once

Kernel 11 is kernel 10 with `then acc` written `then acc + p + q` — two extra
adds in the base case, after the loop is over. Same four parameters, same
condition, same per-iteration updates. 26 836 ps against 801, and 43 collections
against 3.

`MEZZE_TRACE_SELFTAIL` reports `go_5 params=6` where the source declares four:
the extra adds raise more `NumAdd` constraints than monomorphisation discharges,
so the function stays dictionary-passing. Confirmed still present on this
branch's CLI — the `fillHavingOutTypeArgs` rewrite earlier in this effort does
not reach it. Same subsystem as the 190x and 460x rows, reached from a new
direction.

### Three ways this case was got wrong before it was got right

Worth keeping because each one produced a number that looked publishable.

1. **An under-controlled kernel.** The first kernel 10 WAS kernel 11 — its base
   case was written `acc + p + q` without a thought — so the arity control was
   secretly a dict control. Its 33 collections were read as "the off-tier loop
   allocates per iteration".

2. **Over-correcting from it.** That reading was then used to reclassify all
   eight rows above it as "mostly the loop", walking back three correct findings
   as unattributable. This is the worse error: it discarded eight consistent
   readings in favour of one new one. **A new control that contradicts many
   consistent rows is likelier to be broken than the rows are** — check the
   control first.

3. **Measuring a stale compiler.** Every number in the first two attempts was
   produced by typing `mez build` in the case directory, which runs
   `~/.mezze/bin/mez` — a native image three days old, without any of the passes
   this effort added. `recordcost` kernel 2 reads 3 378 ps built that way and
   449 ps built with the dev CLI, a 7x difference that is simply this session's
   `ParamScalarizer` missing. `bench/scripts/ab.sh` already forced the dev CLI
   and documented the hazard as its rule 3; the harness was right and was
   bypassed. It now stamps every build it makes and REFUSES to report on a
   `.build` it did not make, with `ab.sh build1 <case>` to make one in place.

Also recorded: GC counts at this N carry ~30 percent run-to-run spread (33 and
43 from the same binary), which the Method notes' "nearly deterministic"
overstates; and `ir.getName()` is empty for mono-specialised clones, so
`MEZZE_TRACE_SELFTAIL` cannot attribute a tier to a function once mono has
rewritten it — the arity of each kernel had to be read off a module-wide
histogram instead.

### Still open from this case

- Why a bare native call costs ~7.6 ns and 16 extra collections. This is now the
  largest unexplained cost in the inventory and it is paid by every stdlib call
  in every loop.
- `TailCallScalarException` documents typed `long[]` / `double[]` buckets "for
  fully-applied scalar self-recursive tail calls", is caught and unpacked by
  `MezzeFunctionRootNode`, and **is thrown from nowhere in main**. Dead code on
  the legacy AST root node. Whether the live path wants it is open rather than
  an assumed yes, now that the off-tier loop turns out not to allocate.
- Every tail-call operation is variadic (`TailCallDirectN`, `TailCallIndirect`,
  `TailCallByHash`) while non-tail calls have `CallDirect1/2/3` and
  `CallIndirectScalar1/2`. Measured impact: unknown, and smaller than assumed.

---

## The scalar native ABI, and the 91% of the stdlib that was not on it

### The experiment, and the claim it produced — WITHDRAWN

The original reading compared two kernels that call a native returning an `Int`
against a structure built once outside the loop:

| kernel | ps/iter | young GCs |
|---|---|---|
| 10 off-tier control | 611 | 3 |
| 12 `str::size` — scalar ABI | 577 | 3 |
| 9 `map::size` — record ABI | 7 586 | 19 |

and concluded "a native call on the scalar ABI is indistinguishable from the loop
around it; the record ABI costs **13x** that."

**That conclusion does not hold, and the 13x figure is withdrawn.** Two probes
killed it:

1. Replacing `map::size`'s handler with one that reads the receiver but calls
   nothing: **13 collections**, unchanged. Replacing it with `{ _ -> 1L }`,
   which touches nothing at all: **13 collections**, unchanged. So kernel 9's
   allocation is not in the native — not its body, not its receiver read, not
   the ABI machinery, which kernel 12 shares.
2. Kernel 12 originally passed `s = "a"`. Rebuilt to take a string derived from
   `argv` so nothing can be constant-folded, it reads **870 ps / 3 GC** — still
   beside the control.

So after porting `map::size` to the SAME ABI, the two kernels still differ:

| kernel | ps/iter | young GCs |
|---|---|---|
| 1 control | 226 | 2 |
| 10 tier control | 585 | 3 |
| 12 `str::size`, scalar ABI, opaque receiver | 870 | 3 |
| 9 `map::size`, scalar ABI | 3 796 | 13 |

**4.4x and 3-against-13 on the same ABI**, which is not something the ABI can
explain. And their lowered IR is the same shape — `MEZZE_DUMP_FN` prints 1908
characters for one and 1909 for the other, the same node sequence, one
`CallDirectIR` and one `CallNativeIR(isScalarABI=true)` each.

That is now a ledger row of its own: **two loops with identical lowered IR, both
on the scalar ABI, differ 4.4x and allocate 13 collections against 3, and the
gap survives replacing the native's body with a no-op.** The cause is below the
IR; settling it needs a compiled-graph dump on a quiet box, not more reading.

WHAT SURVIVES, and why it is the right claim. The per-op numbers below were taken
on the SAME kernel before and after its op was declared — no cross-kernel
inference — so they are unaffected:

### The scope

The signature table held **128** ops: float 39, int 27, string 24, nat 17,
char 14, bool 7. `NativeDispatch` held **1298** legacy handlers. Every map, seq,
list, set, sorted-map, sorted-set and deque operation was on the record path —
about 91% of the stdlib's natives, including every collection accessor any
program uses in a loop. What that costs is the 2-3.3x measured per op below, not
the withdrawn 13x.

### What was ported, and what it bought

`MapOps.kt` and `SeqOps.kt`: the accessors and persistent-update ops whose
handler is a straight call on evaluated arguments. One `NativeOp(...)`
declaration publishes both the signature (compiler side) and the handler
(interpreter side), so each port is a line over the existing implementation.

| op | record ABI | scalar ABI | |
|---|---|---|---|
| `map::contains_key` | 13 359 ps / 21 GC | 4 079 / 14 | **3.3x** |
| `map::size` | 7 586 / 19 | 3 465 / 12 | **2.2x** |
| `seq::get` | 15 432 / 30 | 7 231 / 23 | **2.1x** |
| `map::get` | 15 889 / 31 | 8 078 / 23 | **2.0x** |
| `map::insert` — control for the first round, ported in the second | 31 118 / 47 | 24 734 / 41 then **31 GC** | see below |
| `str::size` — already scalar, control | 577 / 3 | 632 / 3 | held |
| kernel 1 / kernel 10 controls | 229 / 611 | 217 / 691 | steady |

Both controls steady, both unported ops steady, four ported ops at 2.0-3.1x.

### Two things that make this land correctly rather than look like it did

**`:cli:compileStdlib` has to run first, and `installDist` does not run it.**
The declaration changes what `TypedASTCompiler` emits for `map::size`, whose
body lives in the stdlib and is inlined from the precompiled snapshot. The
build file says why `processResources` cannot depend on `compileStdlib` (it
would be circular), so after adding a declaration the snapshot must be
regenerated explicitly or the lowering silently stays on the record path — which
it did, on the first attempt, with the signature correctly present in the table
and `arg=MakeRecordIR` still in the dump.

**An op that is also a fusion sink must NOT be declared.**
`ChainCanonicalizer` recognises a fusable chain by matching `CallNativeIR` and
reading the source and lambda out of the argument record by field name, and
every one of those sites is guarded `&& !node.isScalarABI`. So declaring such an
op does not merely fail to help — the fuser refuses the call and the fusion is
lost with no diagnostic. `seq::map` + `size` is 1503x on that path.

The rule is mechanical: an op may be declared only if its bare name is absent
from `NativeHofNames`. That excludes `size`, `take`, `drop`, `first`, `last` and
every HOF. `SeqOpsFusionGuardTest` enforces it by walking
`NativeSignatures.knownNames()` against `NativeHofNames`' `OP_*` constants, and
it was verified to fail — naming `__native_seq_size (bare op \\`size\\`)` — with
that one declaration added back.

**The legacy accessors COERCE, and a port that only casts is wrong.**
`NativeDispatch.getSeq(r, name)` does not merely read a field and cast — it also
accepts a raw `Object[]` and wraps it with `MezzeSeq.fromArray`. `getLong`
accepts a `Double` that is an exact integer. Writing `args.obj(0) as MezzeSeq<*>`
in the port looked equivalent and was not: twenty codec and decode tests failed
with

    ClassCastException: [Ljava.lang.Object; cannot be cast to MezzeSeq
      at SeqOps.seq(SeqOps.kt:45) … at <mezze>.find_field_value

because `find_field_value` reaches `seq::get` with a raw array. The coercion is
part of the native's contract, so `NativeHelpers` now carries `asSeq`, `asMap`
and `asLong` as bit-for-bit copies of the originals and every `*Ops.kt` takes
its arguments through them — which is what that file's header already said it
was for. The `is Long` / `is MezzeSeq` test is one instanceof on a stable
profile, so partial evaluation folds it and fidelity is free.

The failure is worth keeping because of WHERE it surfaced: not at the
declaration, but deep inside the JSON decoder, in tests that have nothing to do
with this change. A ported native is only equivalent if its argument handling is
equivalent too.

### Second round: `map::insert`, and measuring it on a loaded box

`insert`, `remove`, `merge`, `keys`, `values` and `entries` followed. `empty` did
NOT: `NativeHofNames.OP_EMPTY` is in the fuser's vocabulary, so declaring
`__native_map_empty` would make `ChainCanonicalizer` refuse it —
`SeqOpsFusionGuardTest` caught that while the list was being written, which is
the test paying for itself on its first outing.

The box was at load 24-25 for this round, and the control said so: kernel 10 read
**1115 ps** against the 597-691 of the quiet runs, a 1.9x drift. By rule 4 that
makes every absolute number in the table unreadable. So the comparison is each
run normalised by ITS OWN control, plus the young-collection counts, which do not
care about load:

| | first round | second round |
|---|---|---|
| `map::insert`, young GCs | 41 | **31** |
| `map::insert` / its own control | 24 734 / 691 = 35.8x | 22 716 / 1115 = **20.4x** |
| `map::size`, young GCs | 12 | 13 — unchanged |
| `seq::get`, young GCs | 23 | 22 — unchanged |

So **1.75x on the ratio and a quarter of the allocation gone**, with the two
already-ported ops flat as controls. A persistent map must allocate nodes, so
`map::insert` will not reach the floor; what came off it was the argument record.

### Third round: list and set, and measuring a port with no "before"

`ListOps.kt` (12 ops) and `SetOps.kt` (7 ops) followed, taking the signature
table from 128 to **174**. `size` is absent from both — it is a fusion sink —
along with list's `last`, `take`, `drop`, `concat`, `empty` and set's `to_list`,
all of which are in `NativeHofNames`. The guard test accepted all nineteen.

**The problem: these kernels are new, so there is no "before" to compare to.**
`MEZZE_NATIVE_SIGS=0` cannot serve as the kill switch, because the stdlib
snapshot is precompiled with signatures on and `list::get`'s body is inlined
from it — turning the flag off at user-build time does not undo the snapshot.

Two things settle it instead. First, the lowering is DIRECTLY observable:

    CallNativeIR(name=__native_list_get,      arg=UnitLitIR    …)
    CallNativeIR(name=__native_set_contains,  arg=UnitLitIR    …)
    CallNativeIR(name=__native_list_from_seq, arg=MakeRecordIR …)
    CallNativeIR(name=__native_set_from_seq,  arg=MakeRecordIR …)

so the two hot ops moved and the two once-per-run constructors did not. Second,
each new op has a STRUCTURAL TWIN already measured on both ABIs, and the twins
were run in the SAME process under the SAME load:

| kernel | ps/iter | young GCs | the twin's unported reading |
|---|---|---|---|
| 1 control | 456 | 2 | — |
| 10 tier control | 984 | 2 | — |
| **14 `set::contains`** — new | **6 270** | **12** | — |
| 8 `map::contains_key` — twin, ported | 5 494 | 13 | 21 |
| **13 `list::get`** — new | **11 339** | **22** | — |
| 7 `seq::get` — twin, ported | 11 041 | 23 | 30 |

`list::get` is indistinguishable from the ported `seq::get` — 22 GCs against 23,
and within noise on time — where the unported `seq::get` read 30. `set::contains`
sits with the ported `map::contains_key` at 12 against 13, where the unported one
read 21. Both new ops are in the ported group and neither is near the unported
baseline.

That is weaker than a kill-switch A/B and it is recorded as such: it establishes
that the port took effect, not an independent per-op ratio.

**`getList` is the widest coercion of any accessor** — it accepts a `MezzeList`,
a raw `Object[]`, AND a Cons/Nil `MezzeVariant` which it walks into a list, via a
`@TruffleBoundary` helper. `NativeHelpers.asList` copies all three branches and
the annotation. After the seq port cost twenty codec tests to a plain cast, the
annotation was checked against the original rather than assumed.

Also worth keeping: `__native_list_get` declares its index field as `index` while
`__native_list_nth` declares `i`. A positional port that assumed one name for
both would not crash — it would miss the all-params-present check in
`TypedASTCompiler` and fall back to the record path **silently**, looking ported
while being slow.

### Still above floor

- **A scalar-ABI map op still costs 6x a scalar-ABI string op** (`map::size`
  3 465 ps against `str::size` 632) and still shows 12 young collections against
  3. `NativeDispatchScalarNode` allocates a `values` array and a
  `PositionalArgs` wrapper per call and relies on escape analysis to remove
  them; for the string op it evidently does and for the map op it evidently does
  not. `handler` is `@CompilationFinal` so the lambda should inline; the
  candidate blocker is `MezzeMap.size()` → `storage.size()` on the `MapStorage`
  INTERFACE, which cannot be devirtualised. Next experiment: check whether that
  site is polymorphic in the compiled graph before assuming it.
- **`map::insert` at 25 023 ps / 46 GCs** is the largest remaining row and is
  not a fuser op, so it can be declared. Same for `remove`, `empty`, `keys`,
  `values`, `entries`, `merge`.
- **list, set, sorted-map, sorted-set, deque** have had no module written at
  all.
- The HOF natives need `NativeOpWithNode` and an `IndirectCallNode`, and several
  are what the fuser consumes, so they are a separate piece of work with its own
  measurement.

---

## Fusion stopped at a `let`, and at one `let` inside a helper

Fusion is the core of the performance story — `seq::map`+`size` is 1503x on the
fused path, a dot chain 540x — so where it silently stops applying matters more
than how fast the fused path is. `ChainCanonicalizer`'s own header says refusal
is silent, and there is no refusal trace, so a gap is invisible until someone
reads the lowered IR.

`bench/cases/fusecross` is six spellings of ONE pipeline. Same stages, same
order, same checksum (14850); the only difference is how it is written. The
ground truth is whether a `FusedChainIR` appears in the lowered body, which is a
property of the compiled program and so survives a loaded box:

| spelling | before | after |
|---|---|---|
| 1 one dot chain | fused | fused |
| **2 every step named with `let`** | **fused=0**, residual `map,filter,fold` | **fused** |
| 3 split across a clean helper | fused | fused |
| **4 split, helper holds one `let`** | **fused=0**, residual `map,filter` | **fused** |
| 5 split, sink in the callee | fused | fused |
| 6 two forwarding hops | fused | fused |

**Naming the steps cost the entire pipeline.** And one named intermediate inside
a helper took its CALLER's whole pipeline down with it.

### One cause, two symptoms

`ChainCanonicalizer.peelSource` recognises a chain by walking an EXPRESSION
TREE. It has peels for map, filter, take, drop, enumerate and from_seq, and a
hop through a direct call — and no case for a `ReadLocalIR`. So a named step
makes the walk hit a slot read, classify the source as Opaque, and stop.

One layer up, `peekThroughMonoWrapper` refused a callee outright when it had
locals, so the same slot read inside a helper cost the caller its chain.

`ChainLetInliner` substitutes single-use chain-valued bindings, which closes
both: the pipeline becomes one expression again, and the helper's locals empty
so the cross-function peek stops refusing. `stages_clean` and `stages_local`,
which differ in source only by a `let`, now lower to IDENTICAL IR and
deduplicate by content hash — the two spellings are indistinguishable to the
compiler.

### Why it is a separate pass from `LetCopyPropagation`

That pass does the same shape of rewrite and deliberately refuses to move a
CALL, for a stated reason: nothing in the IR says whether the impl behind one
has effects, so moving it into a branch could change whether it runs at all. A
chain stage IS a call, so it needs its own argument, and has one:

- **the single read IS the consumer** — a binding read once moves to that one
  read, so the stage is built at the same point in the data flow
- **`iter` stages are lazy** — `iter::map` builds a descriptor and runs nothing
- **effectful callbacks are refused elsewhere** — `isEffectSafe` already keeps a
  performing lambda off the fused path
- and it may not move past anything that is not itself inert, the same rule and
  for the same reason as that pass

### Two things found while fixing it

**`peekThroughMonoWrapper` was testing the wrong property.** It refused on
`target.localSlots.isNotEmpty()` — what the frame DECLARES — while its own
comment states the requirement as "a slot index in the body is a parameter
index". The substituted slot stays declared on purpose (reclaiming it would
renumber every later slot), so the refusal still fired on a helper whose locals
were already gone. It now tests what the body READS. The test caught this: the
assertion was written as `localSlots.isEmpty()` and failed.

**A stage appears as a native only in a MONOMORPHISED clone.** The generic body
still calls `iter::map`; the clone has `__native_iter_map` inlined. Accepting
only the native made the pass work on the clone and silently skip the original,
so a helper mono declined to specialise would keep its binding and keep costing
its caller the pipeline — which ties this to the open `typeParams`
over-collection, since that is a reason mono declines. The predicate now sees
through a direct call to a chain op within two hops.

### The two fusion gaps, sized — 324x and 286x

`bench/cases/dotprobe` is one pipeline per spelling in a small module, built
because `fusecross` could not settle its own kernel 1: nine kernels, most folded
into `main` by the trivial-wrapper inliner, and `main` then holds a mixture of
fused and unfused chains that no per-function scan separates. It read 11 511
ps/elem for the dot form against kernel 3's 209, which would have meant the DOT
form — how pipelines are normally written — does not fuse.

It does. n = 2e6, identical checksums, four kernels in one module:

| kernel | ps/elem | vs floor |
|---|---|---|
| 1 dot form `xs.map{}.filter{}.fold{}` | **152** | at floor |
| 2 free-function form | **209** | at floor |
| 3 stage from a CAPTURING local helper | **49 280** | **324x** |
| 4 callee whose body is an `if` over two pipelines | **43 416** | **286x** |

So the dot form is at the floor and marginally faster than the free-function
spelling, and `fusecross`'s reading for it was an artefact of that case. Its IR
evidence stands — that is a compile-time property attributed to named functions —
but **its absolute numbers cannot rank anything**, and the two gaps it pointed at
are confirmed here on a second instrument at the same ordering.

### One root under both

`k_capturing`'s helper is a LOCAL lambda, so the call is a `CallIndirectIR` and
`peelSource` stops before `peekThroughMonoWrapper` is consulted at all;
`stages_cond` is a top-level function whose body is an `IfIR`, which that peek
refuses because it accepts a body that is a single call and nothing else. Both
reduce to the same thing: `TrivialWrapperInliner.countParamReads` returns false
on any body containing a `MakeLambdaIR` — "those make inlining unsafe at this
level" — and a staged pipeline helper always contains one. So no staged helper is
ever inlined, and the canonicalizer's narrow peek is the only route across a
function boundary.

The structural fix is to let that inliner take a body with lambdas, remapping
their `CaptureIR` slots into the caller's frame — which `FrameExtender` already
does for the peek path, so the machinery exists. 324x and 286x is what it is
worth.

### The capturing-helper gap: where the fix does NOT go

An attempt at this one was written and reverted, because it fired on nothing.
The reasoning is worth keeping, since it says where the fix belongs.

`LocalStageInliner` was to inline a call to a let-bound lambda whose body is a
chain stage, remapping `ReadCaptureIR(i)` to the caller slot behind capture `i`
and a nested `MakeLambdaIR`'s `CaptureIR(slot=j)` likewise — all of which is
decidable, because the binding names the target and the capture mapping in one
node and the frame is `[captures…, params…, locals…]`.

It was placed before `ChainCanonicalizer`, and a refusal trace
(`MEZZE_TRACE_LOCAL_STAGE=1`, named reasons, the same device as
`MEZZE_TRACE_WRAPPER`) showed why nothing happened — in four steps, each of which
corrected a guess:

1. `body-not-a-stage:CallDirectIR` — a stage is a `CallNativeIR` only in a
   MONOMORPHISED clone; the generic body still calls `iter::map`. Widened, the
   same correction [ChainLetInliner] needed.
2. Then nothing fired at all: the call is a `CallIndirectScalarIR` in the FINAL
   IR but the record-arg `CallIndirectIR` earlier, and only the first was
   handled. Both handled.
3. Then a per-function trace confirmed the block IS walked and DOES bind the
   lambda — `fn=k_capturing bodyKind=BlockIR lambdaLets=[scaled@2]`.
4. And yet the complete refusal list still held no entry for it, with every
   call-shaped node otherwise named.

So the call node this pass needs **does not exist at that point in the
pipeline**. It appears in the final IR and not before the canonicalizer, which
means it is produced after it — and a pre-canonicalizer pass can never see it.
The fix has to be in the canonicalizer's own walk: teach `peelSource` to resolve
a source that is a read of a slot holding a lambda, using the binding in the
enclosing block.

Reverted rather than kept: a pass that fires on nothing is not worth a pipeline
stage and a raised file-size bound, and "plausible" is not a measurement — the
same call made twice already in this effort.

### Still above floor

`peekThroughMonoWrapper` remains a special case rather than general inlining,
and still refuses:

- a callee with real CAPTURES (`captureSlots.isNotEmpty()`), which is any nested
  helper closing over a value
- a callee whose body is not a single call — an `if` choosing between two
  pipelines refuses, and that is an ordinary thing to write
- more than `MAX_PEEK_HOPS = 4` forwarding hops
- a callee whose body is a SCALAR-ABI native. Currently unreachable because
  `SeqOpsFusionGuardTest` forbids declaring a fuser op, but it is a latent
  coupling between the two efforts

The general form of the fix is to let `TrivialWrapperInliner` inline a
chain-returning helper — it currently refuses any body containing a
`MakeLambdaIR`, which is exactly what a staged pipeline has — rather than
special-casing the peek. Unmeasured; the kernels above are the instrument for
it.

---

## Which collections the fuser knows about at all

`ChainCanonicalizer` recognises a chain by collection PREFIX:
`NativeHofNames.isAnyElementCollection` covers seq, iter, list, set, sorted_set
and deque, and nothing else. Four more collections ship HOFs that look exactly
like the ones it fuses, and cannot:

| collection | HOFs it ships | in the fuser's vocabulary |
|---|---|---|
| map | 10 — fold filter filter_keys filter_values map_keys map_values any all count reduce | **no** |
| sorted_map | 9 — the same, less reduce | **no** |
| nel | 6 — fold_left fold_right map any all reduce | **no** |
| bytes | 2 — fold map | **no** |

`bench/cases/fuseall` folds n elements of each, n = 1e6, and states each
kernel's own checksum because they cannot all be equal — a `Bytes` element is
0-255 and a map folds entries rather than elements.

| kernel | ps/elem | vs seq | vs list |
|---|---|---|---|
| 1 seq, fused, counted range | **566** | — | — |
| 2 list, fused, walks cells | 1 815 | 3.2x | — |
| 3 nel, UNFUSED | 9 058 | 16x | **5.0x** |
| 4 bytes, UNFUSED | 35 173 | 62x | **19x** |
| 5 map, UNFUSED, folds ENTRIES | 73 873 | 130x | **41x** |

### Read these against the right baseline, and time only the fold

Two things had to be fixed before these numbers meant anything, and the first
version of this case got both wrong.

**Construction was inside the timed region.** Every kernel called `from_seq`
within `time_it` while kernel 1's range is lazy and materialises nothing, so the
readings were list 24x, nel 152x, bytes 237x — almost entirely the cost of
building the collection. Hoisting construction out moved them to 3.2x, 16x and
62x. An order of magnitude, from one line in the wrong place.

**The baseline depends on the storage.** A counted range is not the comparison
for a cons-cell walk. Kernel 1 is the fused floor; kernel 2 is the fused floor
for a structure that has to be walked, and a cons-like collection belongs
against kernel 2. That is what makes `nel` a 5x gap rather than a 16x one.

The corrected list number is also the instrument validating itself: 3.2x matches
the "List ops vs Seq, 3-5x, storage" row that has been in this ledger for
months, measured independently.

### What each one would actually need

Not the same work, and not all of it cheap:

- **nel** is element-shaped and cons-like, so it is the closest to free:
  `__native_nel_to_seq` already exists, and `promoteOpaqueSource` already
  synthesises exactly that kind of call for set, sorted_set and deque.
- **bytes** is element-shaped too, but the same route would copy a byte array
  into a seq of boxed `Int`s. `promoteOpaqueSource`'s `to_seq` trick is an O(n)
  materialisation — fine for a set, wrong for a packed array. Bytes wants a
  source that walks the array in place, which is a new `SourceIR` and emit
  support, not a new prefix.
- **map** and **sorted_map** fold ENTRIES: `map::fold`'s folder takes three
  arguments, `{ acc, k, v }`, where every sink the fuser has takes two. That is
  a design extension — though `StageIR.Enumerate` already produces a record per
  element, so the shape is not unprecedented.

So "add the prefixes" is right for nel, actively wrong for bytes, and not
applicable to map.

### Fusion was making set and deque SEVEN TIMES slower

The four collections above are absent from the fuser's vocabulary. Adding `nel`
the documented way — a prefix plus `promoteOpaqueSource`'s synthetic `to_seq`
call, exactly as set, sorted_set and deque are handled — measured **22x WORSE**
(9 058 -> 201 016 ps/elem) and was reverted. Which raised the obvious question
about the three collections already on that path.

A/B on one CLI with `MEZZE_CHAIN_CANONICALIZE=0`, so "off" is the direct walk and
"on" is the shipped compiler. Fold over 1e6 elements, identical checksums:

| kernel | fusion off | fusion on | |
|---|---|---|---|
| seq | 14 763 | **697** | 21x faster |
| list | 18 252 | **1 915** | 9.5x faster |
| deque | **10 955** | 71 508 | **6.5x SLOWER** |
| set | **26 776** | 188 633 | **7.0x SLOWER** |

**A shipped optimisation was a 7x de-optimisation for two collections.**

### Why, and why seq and list are different

The fused emit is driven by its source kind, and two kinds walk the structure
where it lies: `RawSeq` indexes, `RawList` walks cons cells. That is the whole
reason seq and list gain — they never copy. Set, sorted_set and deque have no
such walk, so they were promoted by MATERIALISING the entire collection into a
Seq first: O(n) allocation on every chain. With stages to fuse, the copy buys a
tight loop. For a bare fold it buys nothing and costs the copy.

The fix is the guard this file already applies to `size`, whose comment states
the principle: "size on a materialised collection is O(1), and routing it
through a counting sink would make it O(n) — a regression dressed as an
optimisation". Two of `promoteOpaqueSource`'s twelve call sites tested
`stages.isNotEmpty()` by hand; the rule now lives in the function.

| | before the guard | after | direct walk |
|---|---|---|---|
| deque | 71 508 | **14 041** | 10 955 |
| set | 188 633 | **19 826** | 26 776 |

seq and list are untouched. Set now beats its own unfused path.

The staged case is left promoting, because a bare fold is what the measurement
covers — whether copy-then-fuse beats a direct walk with two callbacks is a
separate question.

### What these collections actually need

Their own walk, not a conversion. Per storage: a `SourceIR` variant, the
canonicalizer producing it, and a BDSL loop operation that iterates that
storage — the third being the real work and the reason `to_seq` was used
instead. `bytes` is the most rewarding, a `byte[]` indexed walk with no boxing,
which should land near the seq floor from 35 173; `nel` the easiest, cons-like
and possibly able to reuse `RawList` as it stands.

### A refusal trace, and the wrong conclusion it corrected

`MEZZE_TRACE_FUSION=1` names the native and the node at the point where
`promoteOpaqueSource` cannot type a source — the place a pipeline silently
becomes element-at-a-time. Not at `SourceIR.Opaque`, which is NOT a refusal: a
seq, list, set, sorted_set or deque source is promoted from Opaque and fuses
fine, and tracing there reported 179 hits in one small program, nearly all of
them healthy. The refusal is reaching the `else`.

On `dotprobe` it prints, per build:

    6  native=__native_iter_to_seq  source=CallNativeIR:__native_string_split
    6  native=__native_iter_fold    source=ReadLocalIR:slot=0
    4  native=__native_iter_fold    source=CallDirectIR
    2  native=__native_iter_fold    source=CallIndirectScalarIR:through-a-slot

**The last line corrects a conclusion recorded one commit earlier.** That commit
said the `CallIndirectScalarIR` the capturing-helper gap needs "does not exist at
that point in the pipeline... it is produced after the canonicalizer". It is
there, and the canonicalizer sees it.

What actually happened is that the reverted pass bailed on an UNTRACED
early-out — `(node.function as? ReadLocalIR)?.slot ?: return null`, which fires
silently when the lambda is reached as a capture rather than a local. So the
refusal trace I had added to that pass, and trusted, had exits it did not cover,
and the gap in the instrument became a confident claim about the compiler. **A
refusal trace with untraced early-outs is worse than no trace**: it converts
"I do not know" into the wrong answer.

The fix for that gap therefore does belong in the canonicalizer, as recorded —
but because `peelSource` must resolve a call through a slot, not because the
node arrives too late.


---

## Dictionaries in dev, mono in build? No — one path, and here is the number

The proposal is reasonable on its face: monomorphisation is the slow part of a
build, so let `dev` keep dictionary-passing for a fast loop and let `build` pay
for full specialisation. It rests on an assumption nobody had measured.

MEASURED. `MEZZE_MONO_CLOSURES=0` is the only lever that separates part of mono,
so these are a LOWER BOUND on its cost, not the whole of it:

| job | closure mono on | off | cost |
|---|---|---|---|
| stdlib precompile, 78 modules — the largest compile here | 41 497 ms | 38 041 ms | **8.3%** |
| user app, no-change incremental rebuild | ~3 950 ms | — | — |

**The second row is the answer.** An incremental rebuild with no source change
takes about four seconds WITH mono on, and that time is fixed cost — JVM start,
snapshot hydration — not specialisation. So a dictionary dev mode would buy
roughly 8% on a full build and approximately NOTHING on the rebuilds a developer
actually waits for. The dev loop is not mono-bound; it is bound by a four-second
floor somewhere else, and that is where the effort belongs.

### And the semantics would diverge, not just the speed

The decisive argument is not the 8%. In Mezze, **mono is a prerequisite for
other optimisations, not a constant factor on top of them.** `dotprobe` k3 is the
proof: a stage lambda that stays dictionary-passing lowers `x * factor` to a read
of a dict field rather than arithmetic, `classifyUnaryLiteralLambda` cannot
classify it, and **the chain does not fuse** — 49 280 ps/elem against 152. The
190x row is the same thing (the dict wrapper is 52 of 57 ns) and so is the 460x
HOF row.

So a dict-passing `dev` would not be a slower build of the same program. It
would FUSE DIFFERENTLY. Which means a perf bug found in dev need not reproduce
in build; a fusion regression could ship because dev never exercised the fused
path; and `MEZZE_TRACE_FUSION` would print different refusals in the two modes,
so the diagnostics would be wrong in one of them.

This codebase has already shown twice, in one session, what an
under-exercised second path costs: `MEZZE_NATIVE_SIGS=0` silently did not
disable what it claimed, and set/deque fusion was a **7x pessimisation nobody had
noticed** because nothing compared fused against unfused. A dev/build semantic
split is that failure mode by construction.

### What to do about mono instead

Not architecture — three bugs, all small, all diagnosed:

1. **The seed gate refuses MONOMORPHIC functions.** `typeParams` over-collects
   constraint-only variables that occur nowhere in the type, so a function whose
   type is `({n: Int} -> Int)` — no variables at all — fails the all-concrete
   gate and is never seeded. Require only the type args that OCCUR in
   `scheme.type` to be concrete: ~5 lines, and it strictly widens seeding. This
   is the root of all four dict rows.
2. **HOF call-site seeding produces zero specialisations** — the 460x row.
3. **`MEZZE_MONO_STRIP` crashes the compiler**; the liveness scan counts a
   nested lambda's capture as dead. Fix written, unapplied.

And the knob is worth having, but as ONE BUDGET ON ONE PATH:
`mezze.mono.effort = fast | full` controlling how far mono chases specialisations
— depth, call-site breadth — never whether dictionaries exist. Same passes, same
semantics, different thoroughness. That is the only form of the idea that does
not create a second language.

If mono ever does become expensive at scale, the lever is incremental caching of
specialisations, keyed the way the rest of the pipeline already keys on content
hashes — not a different dev semantics.

---

## A constraint about nothing: the phantom type parameter that un-monomorphises a concrete function

Chasing the dict-passing gap from the mono side turned up a defect one level
up. `MonoDriver`'s trace said:

```
byHash-fail callee=k_capturing typeParams=['s30] scheme=({n: Int} -> Int)
```

A function whose type is `{n: Int} -> Int` — not a variable in sight — carrying
a type PARAMETER. Nothing at a call site can ever bind a variable the signature
does not mention, so mono's all-concrete seed gate rejects it forever.

The printer was hiding the reason: `detailed()` did not print a FunctionType's
ability constraints. With them shown:

```
scheme=({n: Int} -> Int) with <NumMul Int ''n11#2080 having out=''u10#2061>
```

`TypedAstBuilder` builds a decl's `typeParams` from `collectTypeVarsInOrder`,
which walks `abilityConstraints[].typeArgs` as well as the parameter and return
types. So an obligation about variables the signature never names becomes a
phantom type parameter, and a fully concrete function is classified as generic.

### Where it comes from

A do-block `let` re-solves its own statement (`solveDoBlockBindingLocally`).
Generalizing that binding quantifies its variables, and the obligation recorded
against them — recorded BEFORE generalization, in the enclosing collector —
survives, now written in variables that are quantified in the local's scheme and
therefore free at the enclosing level by construction. It rides out onto the
enclosing function's signature.

`let double = { a } -> a * 2` shows it in one line, because the obligation ends
up recorded TWICE:

```
with <NumMul Int ''e8#1914 having out='out#1916,   <- phantom, the local's quantified vars
      NumMul Int Int        having out=Int>        <- correct, the enclosing copy
```

A removal for exactly this existed and had a hole. It followed the local
substitution and required the landing variable to be one of the scheme's
`quantifiedVars`; but the local solve generally SOLVES the recorded variable
(`'f8` → the scheme's own `'e8`/`out`), so `apply` returned a non-variable, the
code bailed to "keep", and the phantom escaped. The scheme carrying the same
ability on the same variable IS the safety property — the obligation travels in
the scheme and every use re-demands it — so the match now runs on the variable
the obligation was recorded against.

### THE ANNOTATION IS THE TELL

The same program with `xs: Iter Int` written down had no phantom. Mezze's rule
is that no annotation should ever be needed and performance must never depend on
having written one, so a defect an annotation can substitute for is a defect in
inference. `DoBlockLetPhantomConstraintTest` keeps the annotated spelling as a
control beside the inferred one, plus an arithmetic-free control proving the
defect is about the OBLIGATION and not about do-block `let` in general.

### What it is worth — and what it is NOT

| | before | after |
|---|---|---|
| seeds (dotprobe) | 170 | **188** |
| byHash seeded / failed | 168 / 9 | **186 / 6** |
| concrete decls | 539 | **541** |
| `k_capturing` | generic, never seeded | **concrete** |

Gates: `:typechecker:test` 4804 passed 0 failed.

**It does NOT fix the 324x, and I expected it to.** `dotprobe` k3 measured
63 456 ps/elem against the dot form's 136 both before and after — no change.
Reported because a fix that moves the thing it was aimed at by nothing is the
most useful kind of result to write down.

The reason is in the same trace: the inner `map` is STILL typed over a free
`'s30`. Removing the phantom made `k_capturing` concrete, but its local `scaled`
remains a GENERALIZED local lambda — and that, not the phantom, is the 324x.

### The actual 324x, now located exactly

```
let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * factor }
```

`scaled` generalizes. Generalization is suppressed only for a NON-GROUND capture
(#15), and `factor : Int` is ground, so capturing it does not suppress — the
capture is not the trigger, and a non-capturing local step behaves identically.
What results is a local POLYMORPHIC function: its body is compiled once,
generically, with a dictionary; its single call site instantiates it at `Int`
without ever specialising the body. Mono cannot help, because a local binding is
not a decl and has no hash to key a `SpecRequest` on.

So the stage lambda stays dictionary-passing, the emitter cannot classify it,
the chain does not fuse, and the pipeline runs element-at-a-time.

Two ways to close it:

1. **Do not generalize a local lambda used at one type.** Keeps #76 (multi-type
   uses still generalize) and #15 (ground captures). The obstacle is ordering: at
   the `let`, the later statements that use it have not been generated yet.
2. **Specialise local polymorphic bindings in mono, in place.** When a local
   binding's value is a `TypedLambda` with type params and every use instantiates
   it at the same concrete args, substitute into its body and resolve its
   obligations — the same `TypeSubstitution` and `resolveAbilityConstraints` the
   decl path already uses. No lambda lifting, no closure conversion, captures
   untouched.

(2) reuses machinery that exists and does not fight the generation order, so it
is the one to build.

---

## 314x: the local lambda nobody could monomorphise

The largest measured gap on this branch, and the one four ledger rows were
really about. Naming a step in a pipeline cost two orders of magnitude.

```
let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * factor }
```

A do-block `let` bound to a lambda GENERALIZES (#76), and generalization is
suppressed only for a NON-GROUND capture (#15) — `factor : Int` is ground, so
capturing it does not suppress. The capture is not the trigger at all; the
non-capturing spelling behaves identically. What results is a local POLYMORPHIC
function, and the typed AST holds both halves of the problem at once:

```
LET  scaled  ({xs: Iter['h660]} -> Iter['o659])  with <NumMul Int 'h660 having out='o659>
CALL scaled  ({xs: Iter[Int]}   -> Iter[Int])    with <NumMul Int Int    having out=Int>
```

**The call site was always concrete and always named the impl.** Only the body
kept the variables — so `x * factor` lowered to a dictionary field read rather
than arithmetic, `classifyUnaryLiteralLambda` could not classify the stage, and
the chain did not fuse.

`Monomorphizer` could not reach it. It specializes DECLS, keyed by content hash,
and emits new ones; a local binding is not a decl, and the function around it is
already concrete so no specialization of it is ever produced. Hence
`LocalLambdaSpecializer`: substitute the call site's type arguments into the
binding's body and rewrite its constraint-driven method calls against the
resolved impls — the decl path's own two steps (`TypeSubstitution.applyExpr`,
`rewriteConstrainedMethodCalls`), reused, so there is one rule for discharging a
constraint and not two.

No lambda lifting and no closure conversion: the binding stays where it is, so
its captures are untouched. That is the reason to specialize in place.

### ORDERING IS HALF THE FIX

It runs BEFORE mono's seed walk, and seeding reads its output. Making the body
concrete is only half the job — the `iter::map` inside it must then be SEEDED at
the now-known element type, or it stays a generic call, never lowers to
`__native_iter_map`, and the chain is still unfusable. Measured: with the pass
after the seed walk, the mono trace was byte-identical to no pass at all.

| | k3 capturing | k1 inline control |
|---|---|---|
| pass disabled | **64 672 ps/elem** | 171 |
| pass enabled | **206 ps/elem** | 129 |

**314x.** Same kernel, same harness, the change's own switch, identical
checksums (5999997000000), control steady — so the table is readable. All four
`dotprobe` kernels now sit in a 149-245 ps band; the capturing case is no longer
an outlier. Mono counters: seeds 170 → 192, byHash 168/9 → 190/4, specs
119 → 120, and `map` left the failure list entirely.

Gates: `:typechecker:test` and `:interpreter:test` both green.

### WHAT I COULD NOT TEST, AND WHY THAT IS WORTH WRITING DOWN

Two harnesses look like they should catch this and neither does. I wrote the
first, watched it pass with the pass disabled, and nearly committed it as
evidence:

- **A typechecker lowering test is blind.** That path type-checks and lowers the
  stdlib FROM SOURCE; `mez build` serves `PrecompiledStdlib.preloweredFunctions`.
  On the from-source path the pipeline fuses either way. Even the strong
  assertion — source is `RangeSeq`, two or more stages, no residual natives —
  passed with the pass off.
- **`BytecodeCompiler.FUSED_EMITS` records nothing for an `iter` chain.** Not a
  weaker signal, no signal: measured empty for the inline spelling too, which
  does fuse.

So the perf claim rests on the switched measurement above, and the automated
tests assert what they can actually see: `LocalLambdaSpecializerTest` pins the
TRANSFORMATION (the binding loses its variables and its discharged obligation,
and a binding used at two types is left alone), and
`NamedPipelineStepFusionTest` RUNS the specialized code, because picking the
wrong impl is a wrong number rather than a type error.

This is the `project_aot_tag_dispatch` lesson again: a test on the from-source
path says nothing about the path that ships.

### A NOTE ON THE PHANTOM REMOVAL AND THE RULE THAT REFUSES THEM

`project_undetermined_codec_target_three_steps` records an existing rule: an
ability constraint on a type variable appearing in neither the parameters nor
the result of the solved signature is one no caller can discharge, and is
REFUSED — asked per-binding, never at emit. The phantom-constraint fix removes
constraints of exactly that shape, so it had to be checked that it did not turn
a refusal into a silent drop.

It does not, and the reason is the scope of the removal: it fires only where the
local's own SCHEME carries the same ability on the same variable, so the demand
still travels and every use still re-demands it. `UndeterminedClassArgTest` and
`UndeterminedCodecTargetTest` both pass 5/5 — the refusals still fire.

### STILL OPEN after this

`allocsurface` k11 is unchanged at ~30 675 ps/iter. It is a TOP-LEVEL 4-param
recursive function whose base case `acc + p + q` raises extra `NumAdd`
constraints, so it carries two dictionaries — a different route to a dictionary,
correctly untouched by a pass about local bindings. That is now the largest
remaining dict row, and it is the seed-gate question the phantom-constraint fix
was originally aimed at.

---

## 45x: a specialized recursive function that recursed into its generic self

`allocsurface` kernel 11 cost 30 675 ps/iter against kernel 10's 595. The two
differ by **one line of source**:

```mezze
let go_4 = { i, acc, p, q } ->          let go_5 = { i, acc, p, q } ->
  if i <= 0 then acc                      if i <= 0 then acc + p + q
  else go_4 { i = i - 1, … }              else go_5 { i = i - 1, … }
let k_tier = { n } -> go_4 { i = n, acc = 0, p = 0, q = 0 }
                                        let k_dict = { n } -> go_5 { i = n, acc = 0, p = 0, q = 0 }
```

The call site passes `n : Int` and three `Int` literals, so it should pin
everything and flow outward. It does — that was the surprise. Every stage
worked:

| stage | result |
|---|---|
| `fillHavingOutTypeArgs` closes the `having out` chain | `fromArg=[Int,Int,Int,'d45,'j43]` → `filled=[Int,Int,Int,Int,Int]` |
| seed created | `typeArgs=[Int,Int,Int,Int,Int]`, `impls=3` |
| specialization produced | `specs_for_go_5=1`, `fallbacks=0` |
| the compiler's rewrite gate fires | `rewrite=692510b7`, `nonLambdaUserLet=false` |

And the emitted program still contained `go_5` with
`params=[i, acc, p, q, __dict_add@j43, __dict_add@z44]`, 3 `DictMethodCallIR`,
self-recursing on its own generic hash. The spec hash appeared **0 times**.

### The self-call, not the entry call

Probing every call to `go_5` by CALLEE HASH rather than by loc gave it away:

```
app.mz:164:8   rewrite=NONE        <- the SELF-call, dictMethods=2
app.mz:165:23  rewrite=692510b7    <- the entry call
```

The entry call specialises; the self-call never does. So the specialized clone
calls the GENERIC `go_5`, and every iteration after the first runs on the
dictionary path. For a 20M-iteration loop that is the whole measurement.

`go_4` is the control that proves the mechanism — inside ITS specialized body
the self-call's callee is already its own spec hash:

```
135:8  callee=409717f4 dictMethods=-1   <- inside go_4's SPEC: already the spec
135:8  callee=6884262a dictMethods=1    <- inside go_4's generic
164:8  callee=c269962f dictMethods=2    <- go_5: STILL the generic, both times
```

### Root cause

`Monomorphizer.deriveTransitiveTypeArgs` → `buildArgsFromBindings` requires
EVERY typeParam to come out concrete, and `deriveTransitiveTypeArgs(...) ?: continue`
drops a null silently. `go_5`'s typeParams are five: the three parameter vars,
the result `'j619`, and `'p617` — the type of `acc + p` before `+ q`. **Neither
the result nor `'p617` occurs in the parameter type**, so unifying against the
call site cannot bind them, and the derivation returned null.

A `having 'out` var is like that by nature: it becomes concrete only once the
impl is resolved for the now-concrete receiver and class args. The SEED path has
completed these all along with `fillHavingOutTypeArgs`. **The transitive path had
no equivalent.** That asymmetry is the entire bug.

`go_4` escapes it because `then acc` makes the result var the accumulator var
itself and leaves no intermediate — all three of its typeParams are reachable
from the parameter type. One line of source decided whether five variables or
three needed resolving.

### Fix and result

Give the transitive path the same filler, supplied by `MonoDriver` as a callback
beside `resolveEvidence`, with the same all-concrete bar afterwards — so a
`having 'out` that genuinely cannot be resolved still declines and a polymorphic
recursion is left generic rather than specialized at a guess.

| | before | after |
|---|---|---|
| kernel 11 `acc + p + q` | **30 675 ps/iter** | **682** |
| kernel 10 `acc` (control) | 595 | 651-730 |
| kernel 1 (control) | 238 | 220-235 |
| `DictMethodCallIR` in the program | 3 | **0** |
| `__dict_add` reads | 2 | **0** |

**45x**, and kernel 11 lands exactly on kernel 10 — which is where it belongs,
since the extra adds run once. The generic `go_5` is gone from the emitted
program entirely. Identical checksums throughout.

### A note on choosing the observable

The first version of the test asserted `bySiteLoc` gained an entry for the
self-call line. It failed WITH the fix, because `bySiteLoc` is built from the
SEED list only — a transitive request never appears in it. The redirect happens
while the Monomorphizer CLONES the body (`done[key]` already holds the spec hash
at that point), so the observable is the specialized body's own self-reference.
`RecursiveSpecSelfCallTest` asserts that, and fails without the fix.

---

## 5.1x: the bytes fold was not slow because it was unfused

`fuseall` kernel 4 (a `bytes::fold`) cost 41 076 ps/elem against kernel 1's
385 — a fused `seq::fold`. The obvious reading is that `bytes` is not in the
fuser's vocabulary, so this is the cost of not fusing, and the fix is the
own-source-walk work. **The obvious reading was wrong**, and the A/B that says
so is one env var:

| at n=500k, min of 2 | ps/elem |
|---|---|
| seq fold, fusion ON | 910 |
| seq fold, `MEZZE_CHAIN_CANONICALIZE=0` | 3 704 |
| bytes fold | 38 171 |

**THAT MIDDLE ROW IS WITHDRAWN — see the correction at the end of this log.**
The canonicalizer is a LOWERING pass, so its env gate acts when lowering runs,
which under `mez build` is BUILD time; set at run time it changed nothing and
both rows were the same code. At n=500k the control alone swung 910 to 6 600, so
the "4x" was noise.

What survives is the comparison of the two natives, which needs no switch:

```java
// SeqNatives.foldTwoArg — scalar-ABI bypass, ONE args array for the whole loop
CallTarget innerTarget = ScalarBypass.tryResolve(folder);
if (innerTarget != null) { … args[accSlot] = acc; args[elemSlot] = seq.get(i); … }

// BytesNatives.fold — @TruffleBoundary, and per element:
acc = callNode.call(folder.getCallTarget(),
    prepareArgsTwo(folder, acc, (long)(bytes.get(i) & 0xFF)));
```

`prepareArgsTwo` allocated FOUR objects every iteration — two `Object[]`, the
field-name `String[]`, and a `MezzeRecord` — and `@TruffleBoundary` on the whole
method meant the loop never compiled and the callback was never inlined into it.
A boundary on a HOF's loop is precisely backwards; `SeqNatives.fold` has never
had one.

`MezzeBytes.length()` and `get()` were suspects first and are innocent: both are
plain field/array reads in both of the MezzeBytes classes.

### Result

| at n=2M, min of 3 | before | after |
|---|---|---|
| seq fused (CONTROL) | 385 | **383** |
| bytes fold | 41 076 | **8 089** |

**5.1x**, and the control came back within 0.5% of its pre-change value across
two different builds — which is what makes the row readable. Note the earlier
n=500k readings above are NOT comparable to these: at that size warmup
dominates and the control swung 910→6 600 across reps. Same kernel, same size,
enough reps, or the number is noise.

Bytes remains **21x** the fused seq fold (8 089 against 383). That part IS the
fusion-coverage gap, and the own-source-walk design still stands — but it was
never the whole story, and 10 of the 107x belonged to a native that allocated
four objects per byte.

`bytes::map` had the same shape and got the same treatment, and the dead
`prepareArgs`/`prepareArgsTwo` are deleted.

### One real bug found by writing the test

The two callbacks disagree: `std::collections::bytes` declares `fold`'s as
`{ acc, byte }` and `map`'s as `{ x }`. The first version of this change wrote
`byte` for both. The scalar bypass is POSITIONAL and cannot notice a wrong field
name, so that would have been invisible on every bypass-eligible call and failed
only elsewhere.

Verified rather than assumed: putting `byte` back makes both `map` tests fail
and leaves both `fold` tests passing — so `map` is not bypass-eligible here at
all, not merely when its callback captures, and its field name is load-bearing
today while the fold's is not yet. `BytesHofScalarBypassTest` covers each
operation plain and capturing so that stays true if the bypass's reach changes.

---

## Correction, and a 15% that belongs to every collection HOF

The entry above landed a hand-rolled scalar bypass in `BytesNatives` and claimed
41 076 → 8 089 ps/elem. Then I found `ScalarCall`, which already does exactly
that — resolves the bypass once, reuses one positional args array, and falls
back to a record from declared field names while prepending closure values,
which the hand-rolled code in these natives historically got wrong. It has
`prepare1/2/3`, and `nonemptylist`, `list` and `iter` all use it.

So the bytes natives now use it too: 39 fewer lines, one rule for how a native
calls a guest callback instead of two. I should have looked first —
`feedback_check_existing_helper_before_adding` says so.

**It costs 15%, measured, and the corrected number is smaller:**

| at n=2M, min of 5 | hand-rolled | `ScalarCall` |
|---|---|---|
| bytes fold | 8 089 | **9 275** |
| seq fused (CONTROL) | 383 | **366** |

The control came back FASTER, so the machine was not slower — the 1.15x is the
helper. `ScalarCall.call2` reads `scalar`, `args`, `slot0/slot1` off the object
per element where the inlined loop held them in locals.

Corrected headline: **41 076 → 9 275 ps/elem, 4.4x**, not 5.1x.

**Why keep the slower one.** The 15% is not a fact about bytes. If `ScalarCall`
costs 15% against an inlined loop, then seq, list, iter, nonemptylist and deque
are all paying it on every element of every fold — and that is a far larger
lever than clawing it back in one native, with the ugly alternative being five
more hand-rolled copies of a helper that exists. Taking the consistent path and
writing the cost down beats taking the fast path and hiding it.

**OPEN, and now the interesting question:** is `ScalarCall` scalar-replaced at
all? If the `ctx` object survives into the loop, every native HOF in the
language pays an object field read per element. The way to find out is a
compiled-graph dump of a seq fold, not another timing run — a 15% on one native
is within reach of noise, but the same 15% across every HOF is not.

**TRIED AND IT IS NOT THAT.** `@CompilerDirectives.ValueType` on `ScalarCall` —
the annotation that exists to tell the compiler an object is a value and help
escape analysis — changed nothing: bytes 9 323 ps/elem with it against 9 275
without, controls 387 and 366. So either EA already scalar-replaces `ctx` and
the 15% is elsewhere, or the annotation does not reach this shape. Recorded so
nobody spends the ten minutes again. `call2` is already small and its fields are
already final, which leaves the branch on `scalar` and the call-site inlining
decision — and those are graph questions, not source-reading ones.

Where the remaining collection time actually is, for whoever picks this up:
bytes is **23x** the FUSED seq fold (9 275 against ~400), and that gap is
fusion coverage, not callback overhead. `bytes`, `nel` and `map` are not in the
fuser's vocabulary, and adding them the naive way is a trap — `nel` went 22x
SLOWER that way, because `promoteOpaqueSource` synthesises an O(n) `to_seq`
copy per fold. The design that does not have that problem is a `SourceIR`
variant per storage with a BDSL loop that walks it in place.

---

## Bytes chain fusion: BUILT, MEASURED, REVERTED

This log listed own-source-walk fusion for bytes as the next piece, on the
strength of the bytes fold sitting 23x off the fused seq fold. I built it end to
end, measured it, and took it out. The gap is real; fusion is not the thing that
closes it.

### What was built

- `SourceIR.RawBytes(xs)` — a bytes source walked IN PLACE. Unlike Set,
  SortedSet and Deque, which reach the loop through a synthetic `to_seq` that
  copies every element on every fold, a byte array is already indexable.
- `SeqGet` (`SeqAccessNode`) and `SeqSize` gained a bytes specialization, with
  `& 0xFF` so the element arrives as the `Int` in 0-255 that
  `std::collections::bytes` declares.
- `__native_bytes_fold` as a sink, and `take`/`drop` as the only fusable stages.
- Kernel 8 in `fuseall`: a STAGED bytes chain, which is where fusion could pay.

### Two correctness traps on the way, both real

**`bytes::map` and `bytes::from_seq` TRUNCATE.** Both push every result through
`byteValue()`, so peeling either as an ordinary element stage would drop the
truncation and make a fused chain compute a DIFFERENT answer:
`bytes::map { f = { x } -> x * 2 }` over a byte of 200 is 144, not 400. Adding
bytes to `isAnyElementCollection` — the obvious move — enables exactly that, and
`ELEMENT_COLLECTION_PREFIXES` enables it again by another route through
`extractNativeOp`. Bytes therefore belongs in neither; it needs per-operation
admission, which is why `isPeelableStage` exists as ONE gate rather than the
question being asked in two places with two answers. It was asked in two places
first, and the staged chain silently did not fuse.

**The first kernel 8 could not fail.** It dropped ONE element — and element 0 of
`i % 256` is the byte 0, so the checksum was identical to kernel 4 whether the
drop happened or was ignored. Dropping three makes it 124 713 against 124 716.

### The measurement, and why it ends here

Same binary, same kernel, what I believed was fusion's own switch, min of 5 at
n=2M:

| | "fusion ON" | "fusion OFF" |
|---|---|---|
| kernel 4, bare fold | 8 229 | 13 183 |
| kernel 8, `drop` + fold | **13 492** | **11 256** |

**THIS WHOLE TABLE IS WITHDRAWN.** `MEZZE_CHAIN_CANONICALIZE=0` set at RUN time
does nothing: the canonicalizer is a lowering pass and `mez build` bakes the
lowered IR, so both columns executed identical code and every difference above
is noise — which the spread already said, that one configuration having given
6 633, 8 229, 9 679, 11 191, 12 662 and 39 899 on a single binary.

Rebuilt with the gate set at BUILD time, the generic-loop build's best (6 633)
was if anything slightly BETTER than the native fold's (9 275), not worse. So
the stated reason for reverting was wrong.

The CONCLUSION stands, for a better reason, and the entry after this one has it:
the generic loop was never the destination worth having. A primitive path that
deletes the callback is, and it measures 257.

The emitter said why before the stopwatch did — `emitted fused loop (Accumulate,
0 stages)`. For a BARE fold there is nothing to eliminate, so fusing only
replaces `BytesNatives.fold` — a tight loop on the scalar-ABI bypass — with the
generic BDSL loop, a boxed accumulator and a bounds-checked `SeqGet`. And WITH a
stage it still does not pay, because `bytes::take`/`drop` are a single
`System.arraycopy`: fusing them saves one memory-bandwidth-bound memcpy and
charges a slower per-element loop for the privilege.

**The criterion, which is the part worth keeping.** Adding a collection to the
fuser pays only when the fused loop is faster per element than that collection's
own natives, or when it removes per-element work. For bytes neither holds: the
natives are already tight and the stages are not per-element. `nonemptylist`
going 22x SLOWER was the same thing with a `to_seq` copy on top, and this is that
lesson without the copy to blame it on.

So the 23x against the fused seq fold is NOT fusion overhead — it is what a
boxed per-element guest callback costs against a SIMD-able primitive loop, and
closing it means attacking the callback, not the chain. `ScalarCall`'s 15%
(above) is the near end of that thread.

Reverted: all 14 compiler files. Kept: kernel 8, which is the instrument that
measured it, and this entry.

---

## 37x: delete the callback, do not fuse the chain

The entry above reverted bytes chain fusion and said the remaining 23x against
the fused seq fold was "a boxed per-element guest callback against a SIMD-able
primitive loop, and closing it means attacking the callback". This is that,
and the precedent was already in the tree: the fused seq fold is fast because
`BuiltinSumLongSeq` does not CALL the folder, it RECOGNISES it.

`BuiltinSumBytes` is its byte-array twin — a counted loop over `byte[]`
accumulating `data[i] & 0xFF` into a primitive long, no guest call, no boxing,
no record. Same guards as its seq peer: a zero long init, no stages, and a plain
ADD folder with no element transform.

A bytes source now reaches **that path or nothing**. The generic fused loop is
explicitly refused, which drops the chain to its fallback — the native fold.
That is the lesson of the previous entry kept, with the destination corrected.

| at n=2M, min of 5 | ps/elem |
|---|---|
| bytes fold, canonicalizer off AT BUILD | 9 590 |
| bytes fold, on | **257** |
| bytes `drop` + fold | 329 |
| seq fused (CONTROL) | 395 (was 383 — 3%) |

**37x**, identical checksums, control readable. Bytes now folds FASTER than the
fused seq fold, which is what a `byte[]` being 8x denser than a `long[]` buys.

### THE SWITCH ACTS AT BUILD TIME, AND I HAD BEEN SETTING IT AT RUN TIME

The whole reason the previous entry reasoned from a broken table.
`ChainCanonicalizer` is a LOWERING pass; `mez build` bakes the lowered IR, so
`MEZZE_CHAIN_CANONICALIZE=0` on the built binary changes NOTHING and both
columns execute the same code. The tell was in the data and I read it as load:
one configuration giving 6 633, 8 229, 9 679, 11 191, 12 662 and 39 899 ps/elem
is not a noisy machine, it is a switch that is not connected.

Two published numbers are withdrawn above: the seq fold's "fusion is worth ~4x"
and the staged bytes "13 492 ON against 11 256 OFF". Rebuilt properly, the
generic-loop build was if anything slightly FASTER than the native fold — so the
stated reason for reverting it was wrong, even though reverting it was right.

**The rule this leaves:** a switch that gates a COMPILE-TIME pass has to be set
on the BUILD, and the A/B is two builds. Setting it on the run gives a table
that reads like noise, which is exactly what it is.

Kernel 4 going 41 076 → 9 275 → 257 across this session is also a reminder that
"it is unfused" was never the diagnosis: 10x of it was a native allocating four
objects per element, and 37x more was a callback that did not need to be called
at all.

---

## 5.6x and 2.3x: price the callback before building anything for it

The collection ledger after the bytes work, at n=2M, min of 3:

| kernel | ps/elem | vs seq |
|---|---|---|
| bytes (primitive sum) | 257 | 0.66x |
| seq fused | 391 | — |
| list fused | 1 521 | 3.9x |
| nel | 8 858 | 23x |
| deque | 9 638 | 25x |
| set | 18 445 | 47x |

Set being worst, the obvious move was the bytes shape again: a `SourceIR`
variant, a bytecode operation, emitter plumbing. **Pricing it first changed the
plan and saved all of that.**

Each collection's `sum` computes the same number over the same collection with
the same walk, and WITHOUT calling a guest folder per element. So `fold` minus
`sum` is the callback, and it needs no compiler change to measure — the natives
already exist (`fuseall` kernels 10 and 11):

| | fold | `sum` | the callback |
|---|---|---|---|
| deque | 9 638 | **1 664** | 7 974 — **83%** |
| set | 17 376 | **7 270** | 10 106 — 58% |

About 8 ns per element, which is the same ~9 ns `bytes::fold` was paying before
`BuiltinSumBytes`. And because the destination is already in the stdlib, the fix
is a PEEPHOLE, not a fusion: `coll::fold { init = 0, f = { acc, x } -> acc + x }`
becomes `coll::sum { coll }`. No `SourceIR` variant, no bytecode operation, no
emitter change — `FoldToSumRewriter`, ~100 lines in `ast/truffleir/`.

| | before | after | the `sum` that predicted it |
|---|---|---|---|
| deque fold | 9 638 | **1 726** | 1 664 |
| set fold | 17 376 | **7 521** | 7 270 |

5.6x and 2.3x, each landing on its prediction — which is the evidence that the
CALLBACK was the cost and not the walk. Controls: seq 391 → 406, list 1 521 →
1 478, nel 8 858 untouched (it has no `sum` native to rewrite to). Checksums
identical throughout.

### The guard is the folder's SLOT KINDS, and it is a correctness guard

`sum`'s boxed path reads `Long`/`Integer` and SILENTLY DROPS anything else, so a
Float fold rewritten to `sum` returns 0 instead of the total — a wrong number,
not a type error. The element type is not on the IR node, but each lambda
parameter carries the slot kind of its solved type, so a folder over Ints has
two LONG parameters and one over Floats does not. Same evidence
`ListFoldElementKindFusionTest` uses to keep a Float fold off the long pipeline,
and the same principle: a SOURCE says nothing about what is in it.

Three of `FoldToSumLoweringTest`'s four cases are that trap and its siblings —
Float elements, a non-zero init (a total `sum` cannot express), a compound
folder `acc + x * x` (an element transform `sum` would ignore). The fourth is
verified to fail with the pass unhooked.

A LOWERING TEST IS HONEST HERE, where it was blind for chain fusion. That
blindness was the stdlib: a typechecker test lowers it from source while
`mez build` serves `PrecompiledStdlib`. This pass matches on the USER function's
own body, so the lowered shape here is the shape that ships.

### Two corrections to earlier readings in this log

`set::sum` at 7 270 is FASTER than routing a set through `to_seq` into the fused
seq fold (13 099, kernel 9). So "going through a seq is faster than folding the
set directly" was true only against the callback-paying fold, not against the
walk — the `to_seq` promotion is still a copy and still costs.

And the set walk is 7 270 ps/elem, not the ~13 100 kernel 9 implied: that kernel
pays a 2M-element seq allocation the direct walk does not, which is exactly the
kind of contamination that makes a two-kernel ratio unreadable.

`EmitFileSizeRatchetTest` caught the wiring at +12 lines. Trimmed to +7 by
leaving the reasoning in the pass that owns it, then raised the bound with that
reason — which is what the ratchet asks for.

---

## 7.3x: the missing `sum`

`nonemptylist` was the one element-based collection with no `sum`, so
`FoldToSumRewriter` had nothing to rewrite its fold to and it sat at 8 858
ps/elem — the only collection still paying the ~8 ns guest callback per element.
A missing stdlib function gets ADDED, not worked around, so `nel::sum` now
exists: an indexed loop reading `Long`/`Integer`, matching its deque and set
siblings, one `@TruffleBoundary` for the whole loop rather than one per element.

| at n=2M, min of 3 | before | after |
|---|---|---|
| nel `fold_left` | 8 858 | **1 206** |

**7.3x**, checksum identical, and it lands where the deque row did (1 670) —
which is what two array-backed collections walking without a callback should
cost.

### The collection ledger, after this session's three passes

| kernel | ps/elem | vs seq | how |
|---|---|---|---|
| bytes | 257 | 0.66x | `BuiltinSumBytes` — recognise the folder |
| seq fused | 372 | — | existing SIMD/long pipeline |
| nel | 1 206 | 3.2x | **new `sum` + rewrite** |
| list fused | 1 478 | 4.0x | existing FusedLongPipeline |
| deque | 1 670 | 4.5x | rewrite to existing `sum` |
| set | 7 447 | 20x | rewrite to existing `sum` |

Against where it started: nel 23x → 3.2x, deque 25x → 4.5x, set 47x → 20x,
bytes 107x → 0.66x.

**Set is now the outlier for a different reason.** Its remaining 7 447 ps/elem
is the WALK, not a callback — `set::sum` measured 7 270 before any of this, and
the rewrite delivered 7 447. Visiting one element of a persistent hash set
costs ~7 ns, which is scattered-memory territory: 2M boxed `Long`s plus trie
nodes is far past L3, so this is a layout problem, not a dispatch one. The
lever would be primitive-specialised storage, the way `LongSeqStorage` backs a
seq — a data-structure change, not a compiler one.

A control note worth keeping: the seq row read 512 once in the middle of this
and I resampled rather than report it — min of 5 came back 372, consistent with
the 383-391 from earlier in the session, while deque and set were steady across
the same window. One sample above the 1.15x bar is a sample, not a shift; the
check is whether the OTHER rows moved with it.

---

## Re-measuring beat acting: four rows claiming 190x, 460x, 30x and 13x

The ledger's own instruction is that a row whose cause was attributed to a
subsystem that has since changed is a memory, not a measurement. Mono was fixed
in three places this session, so the four rows resting on its dict wrapper were
marked UNVERIFIED and re-measured before anything was done about them. All four
were wrong, and one of them would have been actively wasted work.

### `dispatch_probe`, n=2e7, min of 4 — control 5 ms in all four runs

| route | the ledger claimed | measured |
|---|---|---|
| `direct` (control) | — | 5 ms = **0.25 ns/iter** |
| `via_hof` — closure param, generic | **190x**, 57 ns | 53 ms = **2.65 ns** (10.6x) |
| `via_hof_nodict` — non-generic | 13x, 4 ns | 47 ms = **2.35 ns** (9.4x) |
| `via_iterfold2` — unreducible folder | 30x, 9 ns | 39 ms = **1.95 ns** (7.8x) |
| `via_ability` | — | 6 ms = **0.30 ns** (at floor) |

**Three rows are one row, and the cause they named is gone.** They all blamed
the dictionary wrapper. Generic against non-generic used to be 57 ns against 4 —
a 14x spread that WAS the wrapper. It is now 2.65 against 2.35, **13%**. So the
wrapper has stopped being the cost from two directions at once, and what is left
is a single ~2-2.65 ns charge shared by every route that calls through a closure
— the indirect call and its argument plumbing, which is not mono's business.

### `fusegaps` 16, the 460x row

| kernel | was | now |
|---|---|---|
| 01 map+filter (control) | 90 | 91 |
| 16 reached through a function VALUE | 41 600 | **86** |

**At the fusion floor** (~85 ps/elem), level with the control, identical
checksums. And it was fixed EARLIER ON THIS BRANCH: the narrative section
"FIXED: a pipeline behind a higher-order parameter — was 460x, now at the floor"
records 41 600 → 94, and the "Landed" table says 440x. The ABOVE FLOOR table
just never had the row struck, so one document carried both the fix and the
claim it had not happened.

### What this cost, and what it saved

I had named the 460x as the largest remaining claimed gap and said re-measuring
it outranked acting on it. That was right by luck as much as judgement: acting
on it would have been optimising something already AT THE FLOOR, and the only
thing that distinguishes those two outcomes is which one you do first.

The rule the ledger now carries: **a stale row is worse than a missing one.** A
missing row reads as unknown, which is true. A stale row reads as measured,
which is false, and it sorts into the queue ahead of things that are real.

---

## 2.2x on the map fold, and the measurement was harder than the fix

The map fold was the largest collection gap and it paid THREE costs where every
other collection paid one:

- `MezzeMap`'s iterator does `new Entry<>(keys[index], values[index])` **per
  element** — an allocation inside the timed loop;
- the callback takes three arguments rather than two;
- the KEYS are read and boxed into that Entry even though a value sum never
  looks at them.

None is necessary: the map stores keys and values in PARALLEL ARRAYS, and
`MapStorage.forEach` is documented as walking them "without creating
intermediate arrays". So `map::sum_values` is a walk with no Entry and no guest
call.

**Named `sum_values`, not `sum`.** A map has two things to sum and that
ambiguity would be the whole bug — a `sum` that silently meant values would
answer a key sum with the wrong column. The rewriter enforces the same
distinction structurally: it redirects only a folder that reads the VALUE and
never the key, so `acc + k` and `acc + k + v` both stay folds. Three of the
lowering cases are that, and a fourth checks it BY VALUE, because getting it
wrong returns 6 instead of 60 rather than failing.

| at n=500k, min of 8 reps, two builds | ps/elem |
|---|---|
| `map::fold` — Entry + 3-arg callback | **33 593** |
| `map::sum_values` | **15 142** |

**2.2x**, identical checksums (124 999 750 000).

### Why this took four attempts to measure

Everything below n=2M was unreadable and n=2M never finished:

- **n=2M never completed** in 25 minutes. Not the fold — BUILDING a 2M-entry
  persistent map through a cons list dominates, superlinearly.
- **n=200k**: the seq control swung 1 386 → 6 849. Its timed region there is
  sub-millisecond, so the control measures startup, not the kernel.
- **n=500k, 3 reps**: the SAME build gave 49 404 and 118 529 — 2.4x. A map fold's
  wall time is GC, not work: a 500k-entry map is a lot of live heap and the old
  path allocated an Entry per element inside the timed region.
- **GC counts**, the load-independent evidence, barely moved: 10 → 8. Because
  the map CONSTRUCTION dominates total allocation, so the fold's contribution is
  2 of 10 and the signal is swamped.

What worked was **min of 8 reps on two builds** — the min is the
least-GC-interrupted run, and 8 reps is enough to find one. Note what that
means: my earlier 3-rep readings of 49 404 and 65 546 were both ~2x above the
true cost, so the "1.33x" I first computed from them was an artefact of too few
samples on a GC-dominated kernel.

### What is left, and it is the same answer as set

15 142 ps/elem is the WALK. A HAMT with boxed values, about twice set's 7 270,
which is what two scattered columns per element costs. Both are now LAYOUT
problems rather than dispatch ones, and both want the same thing:
primitive-specialised storage, the way `LongSeqStorage` backs a seq. That is a
data-structure change, and it is the largest remaining collection item.

---

## 1.5x on the set fold, and the branch I wrote, measured dead, and deleted

After `FoldToSumRewriter` took the guest callback off the set fold, its
remaining 7 447 ps/elem was the walk — and the walk was BOXING.
`LongHashSetStorage` already holds its elements in a `long[]`; every way OUT of
it hands back `Object`, so `set::sum` boxed two million longs in order to unbox
them one line later and add them.

Reading the array directly: **7 447 → 4 938 ps/elem**, min of 5, seq control
steady at 393. Checksums identical.

### The part worth keeping: I nearly claimed a win on a path that never ran

There are TWO long-backed set storages. `LongSetStorage` is dense and sorted;
`LongHashSetStorage` is a hash table with sentinel slots. I wrote the dense one
first — it is the cheaper scan, no sentinels to skip — measured 1.5x, and
assumed that was it.

Then I checked, by making the fast path return a sentinel value and watching the
kernel's checksum:

- poison the DENSE path → checksum unchanged. **It never fires.**
- poison the HASH path → checksum became the sentinel. **That is the live one.**

So the dense branch was dead for everything `set::from_seq` builds, and the 1.5x
belonged entirely to the hash path. The dense `sumLongs` and its branch are
DELETED — code with no test that fails without it does not belong in the tree,
and I could not write one because I could not construct a set that reaches that
storage.

**The technique generalises:** to find out whether a fast path runs, make it
return a WRONG ANSWER and look at the checksum. A timing difference cannot tell
you — 1.5x looked identical whether the path I thought I was measuring fired or
not.

### What is left in the set row

4 938 ps/elem is now an unboxed scan of a hash table with roughly 2x capacity
slack, so it touches far more memory than the set holds. A bandwidth estimate
for the live elements alone is ~1 600 ps, so there is still ~3x in it. The
experiment that would settle the floor is the same scan over a DENSE array of
the same element count — which is also the argument for why a sorted/dense
storage would be worth reaching for bulk-built sets, the thing
`LongSetStorage` exists for and apparently never gets used for.

---

## The 4-param tier: built, 1.46x SLOWER, reverted — and the cause was wrong

The ledger said a 4+ parameter loop costs 3.5x because `BytecodeCompiler`
dispatches the fused tail tier only for paramCount 1, 2 and 3, and noted
helpfully that the 3P analysis was already arity-generic so only the config
packing and operand emission were fixed. That was accurate about the code and
wrong about the cause.

I built it: `FusedLongTailLoop4P` (36-bit config, so a `long` constant operand —
four params need 3-bit rhs sources where three need 2-bit), `fltlPack4`,
`fltl4Rhs`, and the 3P emitter GENERALISED rather than copied, since everything
from the param-index resolver through the per-parameter update loop was already
written against `paramCount`.

It works. `MEZZE_TRACE_SELFTAIL` shows `params=4 tier=FUSED`, and the sibling
4-param function whose base case is `acc + p + q` correctly declines at
`4P:base-not-param`. Checksums identical.

| `allocsurface` k10, 4-param loop, min of 5 | ps/iter |
|---|---|
| 4P tier OFF — the direct-while path | **584** |
| 4P tier ON — the new operation | **855** |
| k1 control | 239 |

**1.46x SLOWER.** A 4-param loop was never falling to the slow general path — it
was already taking the "direct-while optimization" that sits immediately below
the tier dispatch, which emits straight-line bytecode with no dispatch at all.
The fused operation has to decode a packed config and run two `switch`es per
parameter per iteration; at four parameters that costs more than it saves.
Reverted in full.

### And the tier it was modelled on is ~neutral

If direct-while beats a fused op at four params, the obvious question is whether
it already matches at three. `dispatch_probe`'s `via_3p`, min of 3, control
steady at 5-6 ms:

| | min |
|---|---|
| 3P tier ON | 12 ms |
| 3P tier OFF | **13 ms** |

Equal within 8%, and the OFF readings are TIGHTER (13-15 against 12-23). So the
existing 3P tier is buying approximately nothing on this shape. The big
3-param win this log records (25-34 ns/iter → 1.05) is real — with 3P off,
`via_3p` still measures 0.65 ns/iter — but it is NOT attributable to the 3P
tier; the direct-while path now delivers it.

**Not removed.** One kernel is not grounds for deleting a tier that other
3-param shapes may still need, and `via_3p` is a single shape. It is recorded as
the next thing to settle, not a conclusion.

### What this says about reading a ledger row

The row named a cause that was true of the code — there really is no 4-param
branch — and false as an explanation. "The fast path is missing" and "the slow
path is what runs" are different claims, and only the second one makes a gap. I
checked which path ran for `set::sum` by poisoning it; I did not check it here
before building, and that is the whole cost of this detour.

The 2.4x gap is real and now has NO named cause, which is the honest state.

---

## Deque onto the scalar native ABI: 22 ops, 1.16-2.06x each

Mechanical, and the kind of row that stays open because nothing about it is
interesting until it is measured. `bench/cases/dequeabi` is new: five tail loops
calling ONE deque op per iteration, with kernel 1 the same loop and no call.

n=5e6, min of 3, TWO builds:

| | record | scalar | ratio |
|---|---|---|---|
| 1 control | 244 | 253 | 1.04x, so readable |
| `is_empty` | 8 648 | **4 336** | **1.99x** |
| `peek_front` | 12 861 | **8 778** | 1.47x |
| `push_back` | 52 241 | **30 990** | 1.69x |
| `rotate_left` | 56 506 | **48 787** | 1.16x |

**CORRECTED 2026-11-05: the "op alone" column is withdrawn.** It read each row
as `kernel − control` and called the difference the op. Kernel 1 qualifies for
`FusedLongTailLoop` — every parameter update is `param OP constant` — and every
op kernel does NOT, because a call sits on the right of its update and
`fltlExtractOp` finds no inline op there (`MEZZE_TRACE_SELFTAIL` confirms:
control `tier=FUSED`, the rest `tier=not-fused`). So the subtraction charged
each op for a LOOP REGIME change as well. The ratios above are untouched and
remain sound — each is one kernel against itself with only the ABI changed,
which is the same-kernel before/after this log asks for. On `mutabi`, where a
same-regime baseline was added to measure it, the regime is worth 433 ps
(225 fused against 658 un-fused), so the withdrawn column overstated each op by
about that much — around 5% at these magnitudes, and the conclusions do not
move.

The spread is widest for the NARROWEST op, which is the shape of the thing:
`rotate_left` allocates a whole new deque, so the argument record is a small
share of it; `is_empty` does almost nothing else, so the record is most of it.
That also bounds what the remaining ~1 100 legacy ops are worth — the 2.0-3.3x
this log quotes is the accessor case, not the average.

### Three traps, and the second one bit

**1. Never declare a fuser name.** `size`, `first`, `last`, `to_seq`, `to_list`,
`empty`, `take`, `drop`, `from_seq`, `from_list` and every sink are names
`ChainCanonicalizer` matches on; declaring one makes it refuse the call and
costs the fusion, which is worth far more than the ABI. `SeqOpsFusionGuardTest`
passed on all 22, and a dump confirms the split: `push_back`, `rotate_left`,
`reverse`, `is_empty`, `peek_front` SCALAR, `from_seq` and `size` still record.

**2. The declaration table is read at COMPILE time.** I disabled the
declarations, rebuilt only the CLI, and got
`no-handler:__native_deque_is_empty` — the stdlib snapshot still emitted the
scalar form while the runtime registry no longer had it. The A/B is two
`compileStdlib` runs, not two `installDist` runs. Same class of mistake as
setting `MEZZE_CHAIN_CANONICALIZE` on the run instead of the build, except this
one failed LOUDLY, which is the difference between losing a minute and
publishing a wrong number.

**3. The accessors coerce — except these.** `getSeq`/`getList` accept an
`Object[]`; `getDeque`, like `getSet`, casts or throws. `NativeHelpers.asDeque`
is a faithful copy of the strict one. Tightening a coercing accessor by accident
broke 20 codec tests earlier in this effort.

### Why the tests are value tests

The failure mode of a declaration is a wrong `Param` ORDER or `SlotKind`. The
ABI is positional, so swapping two arguments type-checks, compiles, runs and
returns a plausible wrong answer — nothing in the lowered shape looks different.
So `DequeScalarAbiTest` pairs every op against its opposite and checks a number
only the correct order produces: front against back, push_front against
push_back, rotate_left against rotate_right, `concat`'s two operands,
`slice`'s start before its end, `get`'s index. Writing them turned up that
`pop_front` returns `{ head, tail }` while `pop_back` returns `{ init, last }`,
which no amount of reading the declaration would have shown.

---

## Mut onto the scalar native ABI: 93 ops, 1.9-2.7x — and three wrong answers on the way

The mut family cost 8 000-17 000 ps/op where the loop around it cost 250.
`bench/cases/mutabi` is new and built like `dequeabi`: seven tail loops with ONE
mut op per iteration inside the `perform LocalMut`, kernel 1 the same loop with
no op in its body, so every other kernel minus kernel 1 is the op.

n=2e7, min of 3, TWO builds with `MutOps.MARKER` present and withheld (the
declaration table is read while code is COMPILED):

| | record | scalar | ratio |
|---|---|---|---|
| 1 control (fused) | 225 | 255 | — |
| `read_int` | 8 204 | **3 628** | **2.26x** |
| `incr` | 12 373 | **4 575** | **2.70x** |
| `seq::size` | 7 951 | **3 994** | 1.99x |
| `seq::get` | 15 358 | **7 110** | 2.16x |
| `string_char_at` | 17 246 | **9 109** | 1.89x |
| `string_len` | 8 170 | **3 791** | 2.16x |

**CORRECTED 2026-11-05: the "op alone" column is withdrawn**, for the reason
the deque entry above now records — kernel 1 is fused and the op kernels are
not, so subtracting it charged each op for the loop regime too. `mutabi` kernel
2 is new and is the baseline that was missing: the same loop with an ORDINARY
Mezze call on the right of its `acc` update, so it is declined by the fused
tier for exactly the reason the op kernels are and differs from them only in
what the call does.

| | ps/iter | GCs over 2e7 |
|---|---|---|
| 1 fused control | 225 | 2 |
| 2 same-regime baseline (ordinary call) | 658 | 3 |
| 3 `read_int` | 4 196 | 14 |
| 4 `incr` | 4 710 | 13 |
| 5 `seq::size` | 4 209 | — |
| 6 `seq::get` | 7 850 | — |
| 7 `string_char_at` | 9 692 | — |
| 8 `string_len` | 3 962 | 13 |

So the loop regime is 433 ps of it and the op is the other 3 300-9 000. The
ratios above stand; what changes is that the op is now measured against
something in its own regime.

Every checksum identical on both sides. Declared ops: 207 → 300, counted as lines of `native-signatures.tsv` (no header). `sort_by` is
the one mut op that cannot be declared — it takes a guest comparator and needs
the `IndirectCallNode` the scalar path does not pass. Everything here is
`pure = false`: `pure` licenses the compiler to DUPLICATE or ELIDE a call, which
is wrong for a mutable cell in both directions — a write obviously, but a read
too, since duplicating one across a write changes the answer.

### Wrong answer 1: mut already had a faster path than this, and it is dead

73 of mut's 94 natives have their own `@Operation` in `MezzeBytecodeRootNode` —
the `MutOp` / `NATIVE_NAME_TO_OP` table in `BytecodeCompiler`, returning unboxed
`long`/`double`/`boolean`. That is strictly PAST the scalar ABI, so the first
conclusion was that mut was the wrong target and porting it would be the
"never declare a fuser name" mistake in a new costume.

It is dead code, and was when written. Every tier is gated on
`CallDirectIR.isScalarABI()`, which `ParamScalarizer` sets for EXTRACTED LAMBDAS
only — by design, since changing a function's arity is sound only if every
caller is rewritten with it, and a top-level `pub` declaration may be called
from outside the map. A mut wrapper is exactly such a declaration.

Proved by killing the whole layer: all seven kernels moved under 4% with
identical checksums, `mut_loop` 119 → 122 ms, `qsort_mut` 85.0 → 86.2 ms.
Poisoning the tiers individually agrees — `MutReadInt` (tier 1, unboxed) and
`InlineMutOp1` (tier 2) both left the checksum untouched, while poisoning the
record handler changed it, so the call reaches the handler and nothing above it.

Widening the gate to accept a record-ABI call site does not help either, and
that was wrong answer 2: by the time the bytecode compiler runs there is no call
to the wrapper left to match. The dump shows a bare
`CallNativeIR(name = "__native_mut_int_read", arg = MakeRecordIR([n = …]))`
carrying the CALLER's source line — the wrapper is inlined, so the layer's input
shape is destroyed before it ever looks.

### Wrong answer 3: the win was credited to a CLI change that measures 1.00x

Declaring the 93 ops changed NOTHING at first — 8 065 ps, and the dump still
showed `arg=MakeRecordIR`. `:cli` keeps `:interpreter` off its compile classpath,
so the obvious reading was the one already written in
`TrivialWrapperInliner`: the `NativeSignatures` registry is empty under
`mez build`. Loading the bootstrap reflectively in `Main.kt` then made the dump
scalar and the bench 2.1x faster, which looked conclusive.

It was a coincidence. `mez` already reads the declarations back from
`META-INF/mezze/native-signatures.tsv`, which is exactly what that file is for.
A/B'd on one snapshot with two CLIs, the `Main.kt` change measures **1.00x** on
`mutabi`, **1.00x** on `recordcost` and identical on `tailonly` — and costs
**13 ms of startup on every invocation** (117 against 104 ms for `--version`),
which `mez check` pays in the edit loop. Reverted.

What actually moved between those two measurements was the build, which is the
real finding.

### The jar was always one build behind, and silently

`processResources` copies `generated/resources` into `build/resources/main`, and
it must run BEFORE `compileStdlib` — that task's classpath is
`sourceSets.main.runtimeClasspath`, which contains `processResources`'s output,
so Gradle orders it that way. The copy `jar` then packages is the one made
before `compileStdlib` wrote anything: the PREVIOUS build's.

That is not cosmetic, because `mez` reads the native declarations out of that
TSV. A stale one lowers every newly declared native on the record ABI, with
nothing failing.

MEASURED, with one `installDist` each way:

| | jar's TSV | tree implies |
|---|---|---|
| withhold `MutOps.MARKER` | 300 | 207 |
| restore `MutOps.MARKER` | 207 | 300 |

Exactly one build behind, in both directions. `compileStdlib` now overwrites the
four files it invalidates (`stdlib.sqlite`, the snapshot, its fingerprint, the
TSV) in a `doLast` — the same remedy the `test` task in that file already applies
to the snapshot, and it needs no reordering, because `jar` depends on
`compileStdlib`. After the fix the table reads 207/207 and 300/300.

This generalises the deque entry's trap #2. There it was "the A/B is two
`compileStdlib` runs, not two `installDist` runs", and it failed LOUDLY with
`no-handler:__native_deque_is_empty`. Here the same cause failed SILENTLY and
produced a wrong attribution instead. **After changing native declarations, check
the TSV inside the jar, not the line the build prints** — the build printed
"300 natives" while shipping 207.

### Why the tests are value tests, again

`MutScalarAbiTest` pairs every op against its opposite: write against read, incr
against decr, mul/div/mod applied in order, `compare_and_swap`'s `expected`
against its `new_val`, append against prepend, insert against delete, char_at
against set_char_at, push against pop, get against set, the two seq indices of
`swap`, set add against remove, map key against value, the two deque ends,
`bytes_fill` (value SECOND) against `bytes_fill_range` (value LAST), and
`bytes_copy`'s five parameters, where the buffers go dest-before-src while the
offsets go src-before-dest.

Verified by breaking it: three deliberate parameter swaps, two were caught
immediately and the third was NOT — `bytes_copy` was being called with
`src_offset = 1, dest_offset = 1`, the same value, so exchanging them was
invisible. With distinct offsets all three fail. A paired test still needs its
two arguments to DIFFER.

---

## The per-op floor is a `@TruffleBoundary` on every scalar native call

A scalar-ABI native costs ~3 500 ps for work that is one field load, against
658 ps for the same loop calling an ordinary Mezze function. That is the floor
under all 300 declared ops, and it now has a cause.

`bench/cases/mutabi`, n=2e7, min of 3, young GCs over the run:

| | ps/iter | GCs | what the update calls |
|---|---|---|---|
| 1 control | 225 | 2 | nothing — `acc + 1`, so the loop FUSES |
| 2 baseline | 658 | 3 | an ordinary Mezze function |
| 3 `read_int` | 4 196 | 14 | a native |
| 4 `incr` | 4 710 | 13 | a native |
| 8 `string_len` | 3 962 | 13 | a native |

An ordinary call allocates nothing measurable (3 GCs against the control's 2).
A native call allocates ~60 bytes per iteration. JFR names it: **1.2 GB of
`java.lang.Object[]`** attributed to
`MezzeBytecodeRootNodeGen$CachedBytecodeNode.continueAt` — 742 MB on the OSR
path and 460 MB on the regular one — with everything else in the profile being
class loading and CBOR snapshot decode at startup.

### The site

`MezzeBytecodeRootNode.CallNativeScalar`:

```java
@Specialization
@TruffleBoundary
public static Object doInvoke(NativeHandler handler, @Variadic Object[] args) {
    return handler.invoke(new PositionalArgs(args, null, null));
}
```

Three costs per call, and the first causes the others:

1. **`@TruffleBoundary`.** The handler is not inlined into the compiled loop,
   so nothing downstream of it can be escape-analysed.
2. **`@Variadic Object[]`.** BDSL packs the operands into an array per call.
   Behind a boundary it cannot be scalar-replaced.
3. **`new PositionalArgs(...)`** on top of that, plus a boxed return.

The boundary's justification is in its own comment: the handler lambda is
opaque to PE, and SVM's `TruffleFeature` blocklist follows reachability from it
into arbitrary JDK code, so native-image build bails. That part is real. What
is not is the next sentence — "Those are inherently allocating / heavier so the
boundary has no measurable impact". `mut_int_read` is `MutableInt.get()`, a
single field load, and it pays 3 500 ps and 60 bytes.

### What this invalidates

`NativeDispatchScalarNode` is a careful piece of work — `@CompilationFinal`
handler, `@ExplodeLoop` over the args, typed `executeLong`/`executeDouble` so
the primitive never boxes on the way in, and a comment reasoning about exactly
the scalar replacement that is wanted. **The BDSL path does not use it.** It
goes through `CallNativeScalar` instead. Replacing that node's `Object[]` with
fixed-field `Args1`/`Args2`/`Args3` carriers measured NOTHING — 14/13/23 GCs
before, 14/13/23 after, 3 674 ps against 3 628 — because the hot path never
touched the code being changed. Reverted. Optimising a node and never checking
that the hot path reaches it is the same error as A/B-ing the wrong build.

### The fix already exists, for primitives only

`TypedNativeHandlers` holds signature-specific shapes — `LL`, `DD`, `DB`, `LD`,
`DL`, `BB`, `LLL`, `DDD`, `DDB`, `BBB` — emitted as `CallNativeLL` and friends,
which have NO boundary, carry `@ForceQuickening`, take and return primitives,
and allocate nothing:

```java
@Specialization
@ForceQuickening
public static long doInvoke(TypedNativeHandlers.LL handler, long a) {
    return handler.invoke(a);
}
```

Every shape there is primitive-to-primitive. An op with an OBJECT argument has
no shape to match, so it falls to the boundary — and that is every collection op
and every mut op, which is most of the 300. The missing shapes are the
object-receiver ones: `OL` (`read_int`, `*_size`, `*_len`), `OB` (`is_empty`),
`OD` (`read_float`), `OO`, `OLO` (`incr`, `seq::get`, `char_at`), `OOB`
(`contains`).

DONE for 28 ops — see the entry below. Eight object-receiver shapes now carry
the mut cell and accessor family at 2.0-6.6x, with `read_int` and `incr` landing
at the cost of an ordinary Mezze call. A per-op `peSafe` flag on the SHARED
`NativeHandler` was tried first and fails the native-image build outright; the
shapes have to be narrow, and the bodies have to be registered by METHOD
REFERENCE rather than through a Kotlin function type, which boxes.

---

## Past the boundary: eight object-receiver typed shapes, 2.0-6.6x

The entry above named the per-op floor: `CallNativeScalar` is
`@TruffleBoundary` over a `@Variadic Object[]`, the boundary stops the handler
inlining, and behind it the array cannot be scalar-replaced, so every native
call allocates. This is the fix — 28 mut ops at 2.0-6.6x, the narrowest of them
landing at the cost of an ordinary Mezze call — and the FOUR ways of getting it
wrong, three of which measured well before failing.

The table is below, under the one that nearly stuck.

### The mechanism was already in the repo

`TypedNativeHandlers` holds signature-specific shapes — `LL`, `DD`, `LLL` and
the rest — emitted as `CallNativeLL` and friends, which have no boundary and
allocate nothing. Every one of them is primitive-to-primitive, so an op with an
OBJECT argument had no shape to match and fell to the boundary. That is every
collection op and every mut op: most of the 300. The eight shapes added here are
the object-receiver ones — `OL`, `OB`, `OD`, `OO`, `OLO`, `ODO`, `OBO`, `OOB`.

### Four wrong turns, each measured

**1. Fixed-field carriers in `NativeDispatchScalarNode`.** That node is built
for this — `@CompilationFinal` handler, `@ExplodeLoop` over the args, typed
`executeLong` so the primitive never boxes on the way in. Replacing its
`Object[]` with `Args1`/`Args2`/`Args3` measured NOTHING: 14/13/23 GCs before
and after, 3 674 ps against 3 628. **The BDSL hot path does not use that node**
— it goes through `CallNativeScalar`. Optimising a node without checking the
hot path reaches it is the same error as A/B-ing the wrong build.

**2. A `peSafe` flag on the shared `NativeHandler`.** One flag per op, a
boundary-free fixed-arity operation, defaults off. It measured beautifully —
`read_int` 6.4x, `incr` 6.1x, `seq::size` 2.4x, `string_len` 2.0x, with the
deliberately-unmarked `char_at` unmoved at 1.08x as a negative control — and
then failed `nativeCompile` with "Blocklisted methods are reachable for runtime
compilation". It failed with only 18 plain field-access cell ops marked, too.
The operand is the SHARED interface with 300 implementations, so making it
runtime-compilable makes all 300 bodies reachable; the traces are reflection
(`Class.getSimpleName` → `getDeclaringClass0`,
`Reifier.visitArrayTypeSignature` → `makeArrayType`,
`RuntimeMetadataDecoderImpl.decodeMemberName`), reached from error paths like
`NativeHelpers.asLong`'s, which formats `value.javaClass.name`. A narrow
interface is not a style choice: it is what bounds the reachable set. `LL` has
five implementations and all five are primitive arithmetic.

**3. My own string concat.** The narrow `OL` shape with ONE implementation also
failed the native build, and that one was self-inflicted: `MutOps.mismatch`
interpolates `"$op expects $type"`, and `StringConcatHelper.simpleConcat` is
blocklisted. Identical to the failure `MezzeGuestException.dividingByZero` had.
`@TruffleBoundary` on the thrower fixes it and costs nothing — it is the path
that is about to throw. **Any string building on an error path inside a PE'd
native op needs a boundary**, and that now applies to a second site, so it is a
rule rather than an anecdote.

### The regression was my own helper, not the receivers

Extending the same mechanism to 30 ops was first a REGRESSION of 13-22x, and
the cause was a convenience wrapper I had written for it. `nativeOL(name, p)
{ ... }` took the body as a Kotlin function type, and `(Any) -> Long` is
`Function1<Object, java.lang.Long>` — it BOXES on every call. Registering the
identical body as a METHOD REFERENCE adapts straight to the SAM with a
primitive return:

| `seq::size` | ps/iter |
|---|---|
| via the boundary | 3 926 |
| via the Kotlin-function-type helper | 51 492 |
| via a method reference | **1 774** |

**I PUBLISHED THE WRONG CAUSE FIRST, and it is worth recording what the wrong
one looked like**, because it looked good. The receiver classes do differ
exactly as a cause would: `MutableInt` carries 0 `@TruffleBoundary`
annotations while `MutableSeq`/`MutableString`/`MutableSet`/`MutableMap`/
`MutableDeque`/`MutableBytes` carry 12-18 each, and `MutableInt` was the one
op that stayed fast. A grep produced a clean table and a tidy story — "the
outer boundary was never the cost for these, moving it inside is worse" — and
the next step it implied was a data-structure job. All of it wrong:
`MutableSeq.size()`, `getSafe` and `setSafe` are plain field and array access,
and those boundaries sit on OTHER methods in the same files. The one op that
stayed fast was the one op I had registered by hand, with `::intRead`, before
writing the helper.

So: a correlation across six classes, a plausible mechanism, and the single
counter-example sitting in the diff. The tell was there — `intRead` was
registered differently from the other 30 — and the table was more persuasive
than the diff.

So with the ops registered properly, `bench/cases/mutabi` at n=2e7, min of 5:

| kernel | boundary | typed shape | ratio | GCs |
|---|---|---|---|---|
| 1 control (fused) | 225 | 243 | — | 2 |
| 2 ordinary Mezze call | 658 | 642 | — | 3 |
| 3 `read_int` | 4 196 | **640** | **6.6x** | 14 → 3 |
| 4 `incr` | 4 710 | **774** | **6.1x** | 13 → 3 |
| 5 `seq::size` | 4 209 | **1 774** | **2.4x** | → 3 |
| 6 `seq::get` | 7 850 | **5 921** | 1.3x | 19 |
| 7 `char_at` NOT ported | 9 692 | 8 906 | 1.09x | 26 |
| 8 `string_len` | 3 962 | **1 953** | **2.0x** | 13 → 3 |

`read_int` and `incr` land AT the ordinary-call baseline — a native call costs
what a Mezze call costs — with the allocation gone. `seq::get` gains least
because it allocates an `Option` per call, which is real work and not ABI; its
19 GCs are that variant. Kernel 7 is the negative control: `string_char_at` is
on no shape and does not move.

28 ops across 8 shapes: `OL`, `OB`, `OD`, `OO`, `OLO`, `ODO`, `OBO`, `OOB`.

### Where the shape stops paying: the callee has to be PE-transparent too

A second batch of 20 structural ops — `seq_pop`/`clear`/`reverse`/`push`/`fill`,
the four `deque` ends, `deque`/`bytes`/`set`/`map` `clear`, `bytes_get`,
`seq_remove` — was built, measured, and NOT landed. `bench/cases/mutabi` gained
kernels 9 and 10 for it, which stay as the evidence:

| | boundary | shape | ratio | GCs |
|---|---|---|---|---|
| 9 `deque::peek_front` | 6 787 | 5 866 | **1.14x** | 22 → 20 |
| 10 `bytes::get` | 7 252 | 5 438 | **1.32x** | 23 → 19 |

Against 2.0-6.6x for the first batch, and the reason is one grep: of those 20
callees only `MutableSeq.pop` and `MutableBytes.getSafe` are PE-reachable. The
other 18 carry `@TruffleBoundary` themselves, so the typed shape removes the
OUTER boundary and immediately hits an inner one. **The shape only pays where
the callee is PE-transparent**, which is why the int cell ops — `MutableInt` has
no boundaries at all — are the 6.6x and these are the 1.2x.

It also failed `nativeCompile`, and the obvious suspects all check out:
`MutableMap.clear`/`MutableSet.clear` are bounded, `MutableSeq.pop` and both
unbounded `MutableLongSeq` overrides are trivial array access. Bisecting it
costs ~2.5 minutes a build to buy 1.2x, so it is not worth finding yet. The
interpreter suite passed on the batch, so it is correct — just native-hostile
and nearly worthless.

AND THE OBVIOUS NEXT LEVER IS NOT AVAILABLE — checked before claiming it, and
the first version of this entry claimed it anyway. "Unbound the inner accessor,
then give it a shape" only works if the accessor can lose its boundary. It
cannot for most of these, because of what the classes WRAP:

| | backing field |
|---|---|
| `MutableSeq` | `Object[]` — a plain array |
| `MutableInt` / `MutableFloat` / `MutableBool` | a primitive field |
| `MutableDeque` | `java.util.Deque<Object>` |
| `MutableSet` | `java.util.Set<Object>` |
| `MutableMap` | `java.util.Map<Object, Object>` |

Three of them wrap a JDK collection INTERFACE, so `peekFront()` is
`data.peekFirst()`. Those boundaries are load bearing: without them
`ArrayDeque`/`HashMap`/`HashSet` internals become runtime-compilable, which is
precisely the surface the blocklist exists to stop — and is the likeliest
explanation for this batch failing `nativeCompile` at all. They stay.

Which leaves the mut family close to its floor for this technique, and says
where the remaining money is: the cell ops are AT the cost of a Mezze call and
done; `MutableSeq` is already open where it matters; and the `get`-family's
1.1-1.3x is bounded by the `MezzeVariant` it allocates per call, not by the ABI
— 19-23 young GCs per 2e7 is that `Option`. Removing THAT allocation is a
different piece of work (a raw-or-null return with the match fused against it,
which `tryEmitFusedMutGetMatch` already does for the dead `KnownMutOps` layer)
and it is the one worth doing next for these ops.

A smaller loose end: `CallNativeOL` carries no `@ForceQuickening`, because the
operand is already `Object` and a quickened variant collides with the base
("Multiple instructions declared with name
`c.CallNativeOLO$Invoke$Object$long`"). Quickening specialises boxed operand
access, which an `Object` operand does not have, so nothing is lost.

---

## Is `mut` faster than the immutable path? Collections yes, counters no — and the reason is the loop tier

The argument for mut being faster is sound. The `Mut` effect is ERASED —
`MutEraser` β-reduces `Mut.mutate { act = λ }` to the lambda's body at stdlib
precompile time, so nothing of it survives to runtime; it is a type-level fence
that stops mutable state mixing with anything else. And an in-place update
should beat allocating a new structure. So mut ought to compile to inline
updating code and win on time and memory both.

`bench/cases/mutvsimmut` is new and tests that in the two places it lands
differently: a scalar counter, where the immutable version is a parameter update
the compiler can fuse, and a collection, where the immutable version has to
allocate. n=2e6, min of 5, pairs read against each other:

| workload | immutable | mut | verdict |
|---|---|---|---|
| counter | 294 ps, 3 GCs | 842 ps, 3 GCs | mut **2.9x SLOWER** |
| seq element write | 21 141 ps, 13 GCs | **8 324 ps, 8 GCs** | mut **2.5x FASTER** |
| map put, same key | 10 515 ps, 10 GCs | 13 355 ps, 8 GCs | mut 1.3x slower |

**Confirmed where allocation dominates.** `seq::set` returns a new seq, so the
immutable loop allocates one per iteration; the in-place `set` does not, and is
2.5x faster with 8 young GCs against 13. That is the memory argument paying off
exactly as predicted.

**Violated for scalars, and NOT because of the mut op.** `incr` costs about 100
ps on top of the loop — `bench/cases/mutabi` puts it at 774 ps against a 642 ps
ordinary-Mezze-call baseline. What costs is the LOOP TIER. `acc = acc + 1` is a
parameter update that is an expression, so `FusedLongTailLoop` takes the whole
loop into Java with no per-iteration dispatch. A mut op is a STATEMENT, so the
recursing branch is a `BlockIR` whose result is the tail call, and BOTH the
fused tiers and the direct-while path below them require
`elseBr instanceof CallDirectIR`. So the mut loop falls to the flag-based
fallback, which the comment beside it prices at ~16 extra BDSL ops per
iteration. The immutable version gets a Java loop; the mut version gets
bytecode. Nothing about this is specific to mut — it is every loop with a
statement before its recursive call, which is how imperative code reads.

The map row is a weak test rather than a finding: inserting key 7 every
iteration keeps the persistent map at size 1, which is best-case sharing for the
immutable side. Mut still loses because `map_put` is one of the hashing ops
deliberately left on the boundary, and `MutableMap` wraps `java.util.Map` behind
a boundary that cannot be removed.

### The fix: a block-bodied recursing branch, 1.14-1.37x — after a wrong reading

Widening the direct-while path to accept a recursing branch that is a BLOCK
ending in the self tail call, with the statements emitted into the while body
ahead of the parameter update. MEASURED at n=2e7, min of 5:

| | flag fallback | direct-while | |
|---|---|---|---|
| mut counter | 784 | **572** | **1.37x** |
| mut seq write | 6 612 | **5 818** | 1.14x |
| mut map put | 13 355 | **11 700** | 1.14x |

Confirmed in the listing (`MEZZE_DUMP_BC`, new): the loop body goes from 27
instructions per iteration to 14. The fallback spends its extra ops on two
`_stl_cont` flag writes, a `_stl_res` store, a `dup` and a `merge.conditional`
— and it is a proper loop header either way, so this is op count, not OSR.

**THE FIRST READING SAID 1.34x SLOWER, AND IT WAS THE BENCHMARK'S FAULT.**
`mutvsimmut` defaulted to n=2e6, which at ~800 ps/iter is a **1.6 ms** run —
warmup, not steady state. At 2e7 and 1e8 the numbers agree within 5% and the
sign is the other way. I had already written the entry, the commit and the
"reverted as a mixed result" conclusion before dumping the bytecode; the dump
showed 20 instructions against 38, which is what made the measurement
impossible to believe. The default is now 2e7, with a comment saying why.

The lesson is not new, which is the annoying part: this log already says to
measure the SLOPE and to check a number stops moving when you raise n. A
default that is too small is a *result* that looks like a result.

### And a correctness bug the dump caught that no test did

A BDSL block discards a bare statement's value, so a statement returning a
`MezzeSuspend` must be intercepted by `CheckSuspendedThrow` before the drop or
the suspension is silently swallowed (#361). The first version emitted the
statement bare, leaving a plain `pop` where that check had been — visible as
instruction 9 in the dump, and caught by reading it. Nothing in the 7 463-test
suite failed. The emission now mirrors the ordinary block emitter.

I could not write a failing case for it either: the guard admits only native
calls, the suspending natives are the async ones, and **`Mut` is hermetic** —
the typechecker refuses `Mut` in scope with `Async` outright, so a mut loop
cannot contain a suspending statement by construction. The check earns its place
for the non-mut loops the path also admits. `BlockBodiedTailLoopTest` pins the
hermeticity instead, since that is what makes the mut case safe.

### Where mut stands against immutable now

| workload | immutable | mut | |
|---|---|---|---|
| counter | 224 ps, 2 GCs | 572 ps, 3 GCs | mut 2.6x slower |
| seq element write | 15 488 ps, 41 GCs | **5 818 ps, 18 GCs** | mut **2.7x faster** |
| map put, same key | 8 941 ps, 25 GCs | 11 700 ps, 21 GCs | mut 1.3x slower |

The counter gap closed from 2.9x to 2.6x, and reading it as "mut is slower" is
wrong. THE MUT OP IS ALREADY FREE: on `bench/cases/mutabi` a loop calling `incr`
is 577 ps against 653 for the same loop calling an ordinary Mezze function, and
`read_int` is 661 — i.e. a mut loop is AT the floor for a loop that contains a
call. The immutable counter is 219 ps because `acc = acc + 1` needs NO CALL, so
`FusedLongTailLoop` collapses the whole loop into one Java `while` with
primitives in registers. The comparison is call against no-call.

So closing it means INLINING the mut op into the loop body — `MutableInt.add` is
a field update and inlinable in principle — leaving no call for the fused tier to
refuse. Worth doing, and worth keeping in proportion: it only shows up when the
entire loop body is one cell bump. With real work in the body, in-place is
already 3.2x ahead (`qsort_mut` 32 ms against `qsort_pure` 104 ms at N=1e5, and
2x faster than `seq::sort`'s 65 ms).

`MEZZE_DUMP_BC=<substring>` is kept. It is the same class of tool as
`MEZZE_TRACE_SELFTAIL`, whose own comment explains that an invisible refusal is
how a 48x regression hid in plain sight; this one found a wrong number and a
dropped suspension in one sitting.

---

## Your own HOF costs 111x: an indirect call allocates ~950 bytes

The biggest wins in this log are all defunctionalisation of one kind or another
— chain fusion inlines a pipeline's stages instead of calling them (540x on a
dot chain), `LocalLambdaSpecializer` pins a polymorphic local lambda (314x),
`ScalarBypass` devirtualises a native HOF's callback. Every one of them keys off
the STDLIB vocabulary: `ChainCanonicalizer` matches `__native_seq_map`,
`__native_set_fold` and the rest BY NAME. A HOF the user writes has no name the
compiler knows.

`bench/cases/defunc` is new. Every kernel computes the same arithmetic,
`acc + (x * 2 + 1)`, reached a different way, so the rows are a ladder of
indirection. n=2e7, min of 5:

| | ps/iter | young GCs | vs inline |
|---|---|---|---|
| 1 inline | 776 | 3 | 1x |
| 2 named fn, called directly | 799 | 3 | **1.03x — free** |
| 3 own HOF, fn as a PARAMETER | **86 000** | **114** | **111x** |
| 4 own HOF, two callbacks | 95 963 | 95 | 124x |
| 5 closure capturing a variable | 18 886 | 39 | 24x |
| 6 `seq::fold` — the specialised path | 3 428 | 3 | 4.4x |

Checksums agree on 1, 2, 3, 5 and 6; kernel 4 halves n per callback by
construction and says so.

**Calling a named function directly is FREE.** Passing that same function to a
HOF you wrote is 111x, and writing your own `fold` is 25x slower than
`seq::fold` doing identical work. A closure is 4.6x FASTER than a top-level
function reference, which is the inversion that points at the cause.

### The bytecode says the loop is fine

`MEZZE_DUMP_BC` shows `apply_fold`'s loop compiled THREE times — once per call
site, so something is already specialising per site — and each copy is the same
direct-while loop as the inline kernel, 30 instructions against 32. The entire
difference is three of them:

```
13  load.argument index(1)      <- f, the function value
14  load.local i
15  c.CallIndirectScalar1       <- indirect call through it
```

So the loop is not the problem. One indirect call is.

### ~950 bytes per call, in four places

JFR over the run, ~19 GB of allocation for 2e7 iterations:

| | site |
|---|---|
| 9 119 MB | `TrampolineRootNode.unpackRecord` ← `normalizeArgs` ← `execute` |
| 4 252 MB | a `MezzeRecord` under `CallIndirect_Node.execute` |
| 3 141 MB | a `MezzeRecord` in `CallIndirectScalar1.doDirectRecord` |
| 2 936 MB | an `Object[]`, same frame |

(JFR names `MezzeFunction.hasPartialArgs()` on two of those. That method is
`partialArgs != null && !partialArgs.isEmpty()` and allocates nothing — it is
the nearest frame the compiler could attribute an inlined allocation to. Worth
saying, because taking it literally would send the next reader to the wrong
method.)

The name that matters is `doDirectRecord`: the SCALAR indirect call falls back
to building a record.

### The suspicious gate

`CallIndirectScalar1` has identity-guarded specializations —
`doStableNoClosure`, `doStable1Closure`, `doStable2Closures` — which call
`callNode.call(arg)` with flat positional arguments and allocate NOTHING. They
are guarded on `canBypass`, i.e. `canBypassTarget(cachedTarget)`, which requires
the callee's root to be a `TrampolineRootNode`.

And `BytecodeCompiler` says, of a function with no tail calls: *"No tail calls
in body — return raw BDSL root, skip trampoline overhead."*

So `step = { x } -> x * 2 + 1` has no trampoline, and that is exactly why
`canBypassTarget` refuses it and the call lands on `doDirectRecord`. **The
function being simpler is what makes calling it slower** — which also fits the
closure beating the top-level reference by 4.6x.

NEXT: that reads like a one-line gate fix, and it is not, which is why it is
written down rather than done in the same sitting. The fast specializations pass
FLAT positional arguments; the trampoline exists precisely to turn a record-form
call into flat args, and it also handles partial application and the dict
fallback (`parameterCount >= 2 && rawArgs[closureCount] instanceof MezzeRecord`
→ `checkPartialApplication`, and `hasDictParams` is already a guard on the
bypass). Admitting a raw BDSL root means knowing its calling convention for
certain and knowing which of those two jobs nobody is then doing. Getting that
wrong is an `UnsupportedSpecializationException` at best and a silently wrong
argument at worst, so it wants its own measurement and its own gate run.

---

## The 111x splits: an indirect call is 1.9x, carrying DICTIONARIES through a function value is 57x

The entry above measured a user-written HOF at 111x and named the indirect call.
That was half right. Two causes, and separating them took one more kernel.

`bench/cases/defunc` gained kernel 7: the same HOF with a callback whose type is
written down, so `x * 2 + 1` is concrete `Int` arithmetic instead of `NumMul` and
`NumAdd` on an inferred parameter. Identical loop, identical indirection,
identical arithmetic — the only difference is that this callback carries no
dictionaries. (An annotation used as an EXPERIMENT to separate two causes, not
as a fix. If one were needed to go fast, inference would be the bug.)

n=2e7, min of 5:

| | ps/iter | GCs | |
|---|---|---|---|
| 1 inline | 758 | 3 | |
| 7 own HOF, **dict-free** callback | **1 429** | 14 | the indirect call: **1.9x** |
| 3 own HOF, polymorphic callback | **80 873** | 96 | dictionaries: **57x on top** |
| 5 closure over a local `Int` | 2 071 | 5 | |
| 6 `seq::fold` | 1 366 | 3 | |

Checksums identical on all of them. **So an indirect call is 1.9x and dictionary
passing through a function value is 57x** — essentially the whole 111x.

### What the dictionaries cost, in instructions

`MEZZE_DUMP_BC=step` shows the callback is not reached directly at all. There is
a wrapper:

```
=== BC dict_wrapper_step params=[x] ===
  0  load.argument index(2)                      <- x
  1  load.argument index(0)                      <- __dict_add
  2  load.argument index(1)                      <- __dict_mul
  3  create.variadic count(3)                    <- an Object[]
  4  c.MakeRecord [x, __dict_add, __dict_mul]    <- a MezzeRecord
  5  c.CallDirect1 target(LazyStub[callee]) canBypass(Boolean false)
```

An `Object[]` and a three-field record per call, and `canBypass(false)` because
`hasDictParams` is an explicit guard on the bypass — a dict-carrying function is
deliberately excluded from the allocation-free path.

Called DIRECTLY the same function is free (763 ps, kernel 2): monomorphisation
specialises the call site and the dictionaries disappear. Passed as a VALUE,
mono cannot reach it — a function argument is not a type argument — so the
wrapper survives and every call pays for it.

This is what "defunctionalise" means here concretely, and it is the same gap the
`perf_obsession` note already pointed at ("static dict resolution, dict_wrapper,
needs mono"), now with a 57x A/B against an otherwise identical callback.

### The mechanism already exists; the CALL SITE is what fails

Worth stating precisely, because "specialise the HOF on its function argument"
is already implemented and the next person would otherwise build it twice.
`Monomorphizer.closureArgsAt` handles a named function reference, with a comment
naming this exact symptom: *"without this the HOF is not specialised on its
callback … lowering falls back to `dict_wrapper_<name>`. Measured, that wrapper
is 52 of the 57 ns a generic closure call costs."*

`MEZZE_TYPECHECK_DEBUG_CATEGORIES=Monomorphization` on the bench shows it firing:

```
emit spec name=step       typeArgs=[Int, Int, Int]  closureArgs=[]
emit spec name=apply_fold typeArgs=[Int]            closureArgs=[f]   (x2)
byHash-fail callee=apply_fold typeParams=['d17]
  scheme=({n: Int, f: ({x: Int} -> ''d17)} -> Int) with <NumAdd ''d17 Int having out=Int>
  arg.type={n: Int, f: ({x: ''d11} -> 'out)
            with <NumMul Int ''d11 having out=''g11, NumAdd Int ''g11 having out='out>}
```

So `step` IS monomorphised to Int, `apply_fold` IS specialised on `f` — and the
CALL SITES still fail. `apply_fold`'s single type param `'d17` is the callback's
return; at the site it binds to `'out`, which is not concrete, so the
all-concrete gate in `MonoDriver` refuses and the site keeps the generic callee.
Two specialised copies are emitted that nothing calls.

`'out` IS determined, though. The callee's scheme says `f: ({x: Int} -> …)`, so
unifying the argument against it gives `''d11 := Int`; then
`NumMul Int Int having out=''g11` gives `Int`, and `NumAdd Int Int having
out='out` gives `Int`. The chain is solvable — it is just not solved at the
point the gate is applied.

`fillHavingOutTypeArgs` cannot do it as written: it solves the callee's OWN
constraints FORWARD, concrete args to `out`. Here the callee's constraint is
`NumAdd 'd17 Int having out=Int`, which would have to be solved BACKWARD from
`out` to the operand — and an impl's `out` does not determine its inputs in
general, so that is not the route.

TWO CANDIDATE ROUTES, neither attempted here:

1. **Unify first, then solve forward.** Before the all-concrete gate, unify
   `expr.arg.type` against the callee's parameter type to bind the ARGUMENT's
   own vars from the callee's concrete expectations, then solve the ARGUMENT's
   constraint chain forward and derive `'d17` from it. This is the same shape as
   the transitive-self-call fix earlier on this branch (45x), one level out.
2. **Point the reference at the specialisation that already exists.** `step`'s
   concrete spec is emitted BEFORE `apply_fold` is seeded (the trace order
   above), so a reference whose target has a concrete specialisation could be
   rewritten to it, making the argument type concrete and the gate pass without
   touching the solver.

Both land in `MonoDriver`'s seeding path, which 4 818 typechecker tests ride on,
so each wants its own session and its own gate run rather than a guess at the
end of one.

### And a real fix landed on the way: the bypass was refusing a LAZY STUB

Independent of dictionaries, `CallIndirectScalar1`'s allocation-free
specializations were gated on the callee's root being a bypassable
`TrampolineRootNode`. On the FIRST call to a top-level function the root is a
`LazyStubRootNode` — the function is not compiled yet — and the decision is a
`@Cached` initialiser, so it is taken exactly then and the negative sticks for
the life of the call site. The function is compiled moments later and the call
site never notices.

Both gates now look through the stub to the target it will forward to, refusing
when that function carries closures (the stub prepends them per call and a
bypassing caller would not). Worth, at n=2e7 min of 5:

| | before | after | |
|---|---|---|---|
| closure callback | 18 886, 39 GCs | **2 208, 5 GCs** | **8.6x** |
| `seq::fold` | 3 428 | **1 285** | **2.7x** |

The `seq::fold` row is the one that matters: the most-used path in the language,
2.7x, from a fix with nothing fold-specific in it. A local closure was never a
stub — it is built eagerly — which is why a closure had been 4.6x FASTER than a
top-level function for the same work. Being simpler was what made it slower.

### Priced and not built: the per-function suspension wrapper

`Mut` is hermetic — the typechecker refuses it alongside `Async` — so a mut op
provably cannot suspend, and the `TryCatch(SuspendedInBlockException)` every
function carries plus the `CheckSuspendedThrow` after each native statement are
dead for mut code. Removing them properly needs a per-function "can suspend"
summary over the call graph, because `canYieldSuspended` is conservative for any
call. So the CEILING was measured first, by omitting the wrapper outright:

| | wrapped | unwrapped | |
|---|---|---|---|
| mut counter (`mutvsimmut`) | 652 | 553 | 1.18x |
| `read_int`, `incr`, `seq::size`, `string_len`, baseline (`mutabi`) | — | — | **1.00x** each |

Two equivalent mut-counter kernels disagree, so even the 1.18x is not solid.
The reason it is free: an exception handler that never fires is not per-call
work, and `CheckSuspendedThrow` is one cheap instruction. The invariant is real
and exploitable; what it would buy does not pay for the analysis. Not built.

---

## The 122x mandelbrot gap against Scala and Kotlin is DICTIONARY PASSING — 47.5x of it, verified

`mezze-benchmark/RESULTS.md` measures mandelbrot at 2 212.5 ns/px where eight of
ten languages land within 1.8x of each other, and states a hypothesis it marks
**Unverified**, with a note that the test "lives in the mezze repo, not here":

> the escape loop carries unresolved `NumMul 'd 'd having 'out = 'f`, and a
> `NumSub Float 'a having 'out = Float` leaks all the way out to `main`'s
> signature. If `Float * Float` is not monomorphising to a native float
> multiply, every operation stays a generic ability call and no amount of JIT
> fixes it.

`bench/cases/mandelannot` is that test. Two kernels, the same escape loop byte
for byte and the same `iter::range().fold()` grid walk the benchmark uses, with
one difference: kernel 2's parameters carry their types, so `zr * zr` is a
Float multiply instead of `NumMul 'a 'a having 'out = 'b`.

**The static half is confirmed verbatim** — `mez check` prints the leak the
hypothesis predicted, out to `main`:

```
grid_poly : { side: 'a, span: 'b } with NumMul 'a 'a having 'out = Int,
            NumDiv 'b Float having 'out = 'c, NumSub Float 'c having 'out = 'e, …
grid_mono : { side: Int, span: Float } -> Int
pub main  : {} with NumSub Float 'a having 'out = Float, … -> {}
```

**And the runtime half.** Slope method, `(t(2N)−t(N))/(Δpx)` at side 600→1200,
min of 3 — the in-run averages were still falling at side 600, which is the
symptom that calls for a slope:

| | slope ns/px | identical checksum |
|---|---|---|
| POLY, as the benchmark has it | **2 833** | 1970674 |
| MONO, same loop annotated | **59.7** | 1970674 |
| | **47.5x** | |

My POLY reading of 2 833 against that file's 2 212 on its own machine is a
calibration of ~1.28x, so the MONO kernel would read ~47 ns/px there. Placed in
its table:

| impl | ns/px | rel to scala |
|---|---|---|
| scala | 18.1 | 1.0x |
| kotlin | 22.2 | 1.2x |
| c | 22.9 | 1.3x |
| java | 22.9 | 1.3x |
| go | 23.9 | 1.3x |
| rust | 26.3 | 1.5x |
| node | 30.6 | 1.7x |
| roc | 33.4 | 1.8x |
| **mezze, dictionaries resolved** | **~47** | **~2.6x** |
| python | 1 436 | 79x |
| **mezze, as benchmarked** | **2 212** | **122x** |

So the answer to "how far off Kotlin and Scala are we" is two different
answers: **122x as shipped, ~2.6x once the dictionaries resolve.** The gap is
not the JIT, not Truffle, and not the language — the annotation proves the
information is present and simply not used. Which also explains that file's
other observation, that JIT-on against JIT-off is only 1.7x where a
well-specialising Truffle language gets 10-50x: there is nothing to specialise
when every float multiply is a dictionary call.

**THIS IS NOT A RECOMMENDATION TO ANNOTATE.** An annotation that changes
performance by 47x means inference is leaving 47x on the table; the annotation
is the instrument, not the fix. The fix is the one the entry above diagnoses —
`MonoDriver`'s all-concrete gate refusing a call site whose `having out` chain
is solvable but unsolved — and `bench/cases/defunc` already puts the same cause
at 57x for a callback passed as a value. Two benches, two kernels, one root
cause, 47-57x each.

One loose end found on the way: a plain tail-loop grid walk over the
un-annotated `pixel` would not compile at all — `[BDSL] compile failed for
lambda reason=unsupported (node=CallDirectIR:target-not-compiled)`. A shape that
type-checks and does not compile is its own bug; the bench uses the benchmark's
`iter` shape instead, and that is recorded rather than chased.

### What the fix is, located to the line

The whole chain hangs off one gate: `MonoDriver.collectSeedsFromExpr` admits a
call site only if EVERY derived type arg satisfies `isConcrete`. A probe printing
the derived args at that gate says, for this kernel:

```
GATE grid_poly: 'a45=Int 'b45=Float 'f45=Float 'c45=Float
                'g45=VAR  'd45=VAR  'e45=VAR(EffectRow())
GATE pixel_poly:  … all none
GATE escape_poly: … all none
```

`grid_poly` is the ROOT: four of its seven params are already concrete at
`main`'s call site, three are not, and nothing downstream can specialise until
it does — `pixel_poly` and `escape_poly` show `none`, i.e. no derived args at
all, because their sites live inside the unspecialised `grid_poly`. Fixing the
root cascades; fixing the leaves cannot.

**AND THE THREE-BLOCKER READING BELOW IS THE SYMPTOM, NOT THE SHAPE.** Probing
further — printing `grid_poly`'s constraints with their ability NAMES and its
surface type — gives this:

```
grid_poly surface = FunctionType(pure)
  CONS NumMul args=['a45, 'a45]   out={out=Int}
  CONS NumDiv args=['b45, Float]  out={out='f45}
  CONS NumSub args=[Float, 'f45]  out={out='c45}
  CONS NumSub args=[Float, 'g45]  out={out='c45}
  CONS NumMod args=['a45, Int]    out={}
  CONS NumDiv args=['a45, Int]    out={}
  CONS NumAdd args=['c45, Float]  out={}
  CONS NumAdd args=['d45, 'e45]   out={}
  PARAMS 'a45=Int 'b45=Float 'f45=Float 'g45=VAR 'c45=Float 'd45=VAR 'e45=ROW
```

`grid_poly` is **pure** — no effect row in its type — and its surface type is
`{ side, span } -> Int`. It has SEVEN type params. Two are `side` and `span`;
the other five appear NOWHERE in the surface type. They are `pixel_poly`'s and
`escape_poly`'s unresolved constraint residue, leaked outward because those
functions are themselves unspecialised.

That is the actual shape: **the gate demands concreteness for params that are
not reachable from the surface type at all.** `deriveTypeArgs` can only see the
surface type, so it cannot fill them; only the `having out` chain can, and when
the chain does not close (two of the five here) the site is refused — along with
everything downstream of it. A row is simply what an unfilled param reads as,
which is why `'e45` shows up as a ROW while also being a `NumAdd` operand on a
pure function. Reading that as "a row blocks the gate" is reading the
placeholder as the problem.

ATTEMPTED AND REVERTED: admitting a row-valued arg at the gate while omitting it
from `buildSubstitution`, so it could never reach a value slot. It is safe and
it does work — a probe confirms `'e45=ROW-ok` and the row stops blocking — and
it buys NOTHING: mandelbrot 2 918 against 2 833 ns/px, `defunc` kernel 3 81 284
against 80 873, both noise, because `'g45` and `'d45` still refuse the site.
`:typechecker:test` stayed 4 818/0. Reverted, because what it really amounts to
is partial specialisation arrived at by accident — admitting ONE kind of
unfilled param without deciding what to do about unfilled params in general.

So the question the next attempt has to answer first is not "how do we resolve
these two vars" but **"should a constraint-only param block specialisation at
all?"** If the answer is no, the gate becomes "every param reachable from the
surface type is concrete" and the specialised copy keeps a dictionary for
whichever ops stay unresolved while every determined op gets a concrete impl —
partial specialisation, decided deliberately. `buildArgsFromBindings` already
takes an `allowPartial` for exactly this shape, from the transitive-self-call
work earlier on this branch, so the precedent and the machinery exist.

The two residual vars, for whoever does it:

**1. An EFFECT ROW among the type params.** `'e45` is a row, `isConcrete`
answers false for a row, so the gate refuses. A row is never a value and never
needs a dictionary, so it should not block specialising the value params — but
the obvious fix is wrong, and `MonoTypeOps.isConcrete` carries the scar: someone
already made `EffectRow -> true` and a row reached `buildSubstitution` and was
substituted into a VALUE slot, surfacing as *"PrintType: cannot render a value
of non-concrete type EffectRow()"*. So it has to be a PARTIAL substitution —
specialise the value params, leave the row param generic — not a flip. There is
precedent in the same file: `buildArgsFromBindings` already takes an
`allowPartial` and inserts a fresh var for what it cannot fill.

**2. A `having out` chain needing BACKWARD resolution.** `'d45` appears as
`NumSub Float 'd having 'out = 'e` with `'e` already Float. Forward propagation
cannot help; it needs "which operand makes this impl produce Float", which is
only answerable when exactly ONE candidate impl matches — a single-candidate
search, guarded on uniqueness.

**3. A bare constraint with no `having out`.** `'g45` appears as `NumAdd 'f 'g`
with no output binding — the `acc + pixel {…}` inside the fold. Its type comes
from the fold's `init = 0` and the callback's return, i.e. from unification with
the context rather than from the constraint. Nothing in the filler looks there.

TRIED AND IT WAS NOT ENOUGH: feeding the filler the CALL SITE's constraints
instead of only the callee scheme's. The site's are strictly more instantiated —
at the `escape_poly` site the scheme says `NumMul ''s17 ''s17 having out=''k15`
while the site says `NumMul Float Float having out=''k15`, which resolves in one
impl lookup. Built it, measured it: 2 879 ns/px against 2 833, i.e. nothing,
because `grid_poly` upstream still fails on the three blockers above and the
cascade never starts. Reverted; recorded because it is the obvious first move
and it does not work on its own.

So "beat Scala" is a bounded piece of work with a known shape: partial
substitution past a row param, single-candidate backward resolution, and
context-derived binding for a bare constraint — in `MonoDriver`'s seeding path,
which 4 818 typechecker tests ride on. The payoff is 47.5x on this kernel and
57x on `bench/cases/defunc`, which would put mandelbrot at ~2.6x Scala rather
than 122x. It wants its own session; this one located it.

### 4x of it, landed: a known output determines the operands

**The three blockers above are a description of the SYMPTOM at the mono gate.
The defect is upstream of mono, in inference, and it is one rule.**

`escape_poly` has NINE type parameters. Five of them appear nowhere in its
surface type `{cr,ci,zr,zi,i} -> Int`, so no call site can ever instantiate
them and no dictionary for their obligations can ever arrive — they are the
types of `let zr2 = zr * zr` and its siblings. Printing its scheme says why they
survive generalisation:

```
escape_poly  params=['o15,'y17,'s17,'v17,'l15,'k15,'n15,'u17,'x17]  ret=Int
  NumMul ['s17,'s17] out='k15        zr * zr
  NumMul ['v17,'v17] out='l15        zi * zi
  NumAdd ['l15,'k15] out=Float       zr2 + zi2 > 4.0   ← the only concrete anchor
  NumSub ['l15,'k15] out='n15
  NumAdd ['o15,'n15] out='s17
  …
```

The comparison against `4.0` pins the third output to **Float**. The solver reads
an impl's `having 'out` off a resolved operand tuple and never the other way, so
a known output determined nothing. Read it backwards and the whole loop falls
out: one `NumAdd` impl takes two Floats and yields one, so `'l15` and `'k15` are
Float; the multiplies then have a known output AND a REPEATED operand variable,
where exactly one impl can agree with itself, so `'s17` and `'v17` are Float too.
NINE type parameters become TWO — `cr` and `ci`, which a caller really does
choose, because `zr2 - zi2 + cr` accepts an Int `cr` through `NumAdd Int for
Float`.

MEASURED, `bench/cases/mandelannot` kernel 1, slope over side 600 → 1200:
**2 833 → 704 ns/px, 4.0x**, same checksum. The annotated kernel beside it is
48.6, so the annotation gap went from 48x to 14.5x.

`:typechecker:test` 4 821/0, `:interpreter:test` 7 463/0 (11 skipped),
`:cli:nativeCompile`, `just native-test`.

#### What it took to make it sound, which is most of the work

The rule has two halves and the second one is where five separate tests drew the
line. Improvement by UNIQUENESS — one candidate impl, so the operands are not a
guess — is safe anywhere. Arithmetic is rarely unique: FIVE `NumAdd` impls yield
Float (`Float for Float`, `Int for Float`, `Nat for Float`, `Float for Int`,
`Float for Nat`), so the anchor above is ambiguous and needs a DEFAULT. The
default is homogeneous — prefer the candidate whose operands are all one type —
and the four conditions on it each come from a test that failed without them:

1. **The variable must not be a parameter or a result**, anybody's, at any depth.
   `tf = { xs } -> list::fold_left { xs = xs, init = 10, f = {a,x} -> a + x }`
   has `NumAdd 'x for Int having out = Int` with `'x` the ELEMENT type: three
   impls answer, and `ListFoldElementKindFusionTest` folds a Nat list and a
   record list through it precisely because `NumAdd Nat for Int` is real.
   Defaulting there is not inference filling a hole, it is inference taking a
   choice away. `scaled` in `LocalLambdaSpecializerTest` is a LOCAL let, so
   "parameter" cannot mean the enclosing declaration's parameters — asking only
   about the top-level signature narrowed `tf`, `scaled` and `sc2` all three.
2. **Asked through the SUBSTITUTION, in both directions.** A constraint names the
   operation's own freshly instantiated variables, unified with the caller's, and
   which one ends up as the class representative is however the unifier oriented
   the binding. An identity test said "not a parameter" about the parameter
   itself, froze `add_ten = { x } -> x + 10` to `{ x: Int } -> Int`, and made
   `f { x = 5.0 }` a type error — the exact unsoundness
   `HavingOutGeneralizationSoundnessTest` exists to catch.
3. **Only in the LATE phase.** `solveAbilityConstraints` runs BEFORE
   `solveMainWorklist`, so during the first pass `tf`'s surface is still
   `{ xs: 'xs } -> 'ret` and the element variable is nowhere in it. The deferred
   re-passes in `finalizeDeferredAbilities` already revisit exactly these
   constraints, so improvement is a late rule by construction: everything
   determinable without it already is.
4. **Only in a PER-BINDING solve.** The module-wide solve gets every binding's
   constraints merged, and a top-level `let` contributes no function type of its
   own to them — the lambda's type is the generator's RESULT, not a constraint.
   There a parameter is indistinguishable from an intermediate.

**And the evidence has to be an OUTPUT, not an operand.** This is the condition
that cost the other 10x, and it is worth writing down why it is not negotiable.
Letting a concrete OPERAND anchor the default takes the kernel to **68 ns/px** —
`grid_poly` down to a single type parameter, the un-annotated kernel level with
the annotated one — because it also collapses

```
NumDiv ['span, Float] out='a        a = px * 3.0 / span
NumSub [Float, 'a]    out='r        r = that - 2.0
```

where both `'a` and `'r` are intermediates. But `DoExpressionConstraintTest` has

```
let nested = {a, b} -> do
  let inner = do
    let temp = a + b
    temp * 2
  inner + 10
```

and `NumMul [Int, 'temp] out='inner` is **structurally identical** to the
`NumSub` above: one concrete operand, an intermediate receiver, an intermediate
output. Defaulting it decides `a` and `b` are Ints, and `nested { a = 1.0,
b = 2.0 }` — which runs as Float through `NumMul Int for Float` — becomes a type
error. There is no local difference between the two. The only difference is that
the mandelbrot program also CALLS these functions with concrete arguments, and
that is not a question a typechecker is asked. So 68 ns/px is not available from
inference, and the remaining 14.5x belongs to MONOMORPHISATION, which is
whole-program and does know every call site.

`bench/cases/mandelannot`'s own header asked for exactly this test and marked the
hypothesis Unverified. It is verified: the un-annotated loop was paying for
evidence it could never receive.

### The other 12x, in mono, where every caller is known: 2 833 -> 58 ns/px

What inference had to leave open, monomorphisation can close, because by then
there are no more callers to be wrong about. `grid` reaches the gate like this:

```
grid  { side, span } -> Int            params ['span,'u,'v,'w,'x]
  NumDiv ['span, Float] out='u         px * 3.0 / span, inherited from pixel
  NumSub [Float, 'u]    out='w
  NumSub [Float, 'v]    out='w         ← 'v only ever an OPERAND
  NumAdd ['x, Float]    out={}         ← inherited, its out already discharged
```

`main` pins `span`, and the forward filler resolves `'u` and `'w` off the impls'
`having out`. TWO of five it could not, for two different reasons: `'v` needs the
chain read BACKWARDS — which operand makes this impl produce Float — and `'x`
arrives with an EMPTY binding map, because the callee it came from had already
discharged its own output, so the forward filler `continue`d past it before
looking at the operands at all. Two params, and the whole site was refused.

Both fixed in `fillHavingOutTypeArgs`, in the same fixpoint loop, with the
backward attempt placed BEFORE the `associatedTypeBindings.isEmpty()` guard that
the forward direction needs and the backward one does not.

MEASURED, `bench/cases/mandelannot` kernel 1, slope over side 1200 -> 1800:

```
                             before      inference fix      + mono fix
  un-annotated (kernel 1)   2 833            704              58.0 ns/px
  annotated    (kernel 2)      —              —               64.2 ns/px
```

**48.8x, and the annotation gap is CLOSED** — the un-annotated loop is now
marginally faster than the same loop with its types written down, on identical
checksums (17 720 249 and 39 866 172). Against the benchmark repo's recorded
2 212.5 ns/px for mezze it is 38x, and against Scala's 18.1 it is 3.2x — which
is now a code-generation question, not an evidence-passing one, and the SAME
3.2x the annotated kernel has always had.

The licence for defaulting here is not the typechecker's. There the callers have
not arrived, and `nested` is indistinguishable from this chain. Here the request
IS the instantiation, the param appears nowhere in the callee's surface type, so
no site could ever name it — and the choice is self-consistent by construction,
because picking Float for the operands also picks Float's impls for the
operations that produce them, which produce Floats. Restricted to non-surface
params for the same reason as before: a surface param is the site's to supply,
and if the site did not supply it the gate should refuse the site rather than
invent one. `MonoInheritedResidueTest` carries both halves; with the fill
reverted the cascade assertion fails.

NOT the same cause, measured on the way past: `bench/cases/defunc` kernel 3 is
83 968 ps/iter against kernel 2's 794, unchanged by either fix. That 106x is a
user-written HOF reached through a function VALUE, which no type argument
determines — the next gap, and its own mechanism.

### 8.2x of that next gap: a function reference IS its specialisation

`let step = { x } -> x * 2 + 1` is un-annotated, so it is polymorphic over the
numeric type and carries `__dict_mul` and `__dict_add`. Passing it as a VALUE
cannot pass dictionaries alongside it, so the compiler built a closure —
`dict_wrapper_step` — capturing the two resolved operations and forwarding to the
generic function. Every call of the callback went through that closure.

But resolving a reference's dictionaries IS choosing an instantiation, and a
specialisation against those impls is the same function with the operations
compiled in, taking the same parameters. So the wrapper's last resort became:
reference the copy instead. The match is on OP HASHES — the specialisation
records the impls it was cut against, the wrapper holds the operations it would
have carried — so no type is re-derived and nothing is guessed, and it insists on
a unique match.

Half of it was missing and the measurement said so. A seed comes from a CALL, and
a function handed over as a value is not called at the site that hands it over, so
`step` had no specialisation to reference — in the bench it had one only because
kernel 2 calls `step` directly. A probe put it plainly: `SPECREF reached
generic=7eb612bd candidates=-1`, in a program whose only use of `step` is as an
argument. Mono now seeds a function-valued argument at the type the callee's own
signature declares for that parameter (`f: {x: Int} -> 'd`, with `'d` among the
args just derived), so the win no longer depends on an unrelated call site.

MEASURED, `bench/cases/defunc` at n=2e7, min of 3:

```
                        before     after
  2 named fn               794       779    (control: a direct call)
  3 own HOF             82 873    10 131    8.2x
  4 own HOF, 2 fns      99 764    49 299    2.0x
  7 own HOF, no dict    12 998    10 343    (control: callback annotated)
```

Kernel 3 lands on kernel 7 — the same HOF with the callback's type written down,
which the bench put there as the control for exactly this. So dictionary passing
through a function value is gone, and what is left is the indirection itself:
10 131 against 779 for calling the same function directly. That 13x is an
unspecialised HOF, a different mechanism, and the next gap.

Kernel 6 (`seq::fold`, the path that was already fused) read 4 715 before and
between 1 335 and 5 225 after, across runs — noise, and claimed as nothing.
`bench/cases/mandelannot` is unchanged at a 57.6 ns/px slope.

ATTEMPTED AND DELETED on the way: completing a derivation from the obligations the
CALL SITE's types carry. `apply_fold`'s one open param is its callback's result,
with `NumAdd 'd Int having out = Int`, and the argument's own `with <NumMul Int 'p
having out='g, NumAdd Int 'g having out='r>` resolves it forward with no guesswork
— `'p` is Int because the callee declares it, so `'g` is Int, so `'r` is Int, so
`'d` is Int. It works, it is pure resolution, and it is worth nothing: kernel 3
measured 12 008 with it and 12 523 without, mandelbrot 62 either way. Deleted
rather than kept on the grounds that it looks right.

`:typechecker:test` 4 823/0, `:interpreter:test` 7 467/0 (11 skipped),
`:cli:nativeCompile`, `just native-test`.

### Verified where it counts: the benchmark repo's own harness

The numbers above are this repo's benches. `mezze-benchmark` is the published
comparison, and it runs the same mandelbrot against nine other languages with a
checksum contract. Rebuilt with this compiler and run through its own harness
(`just bench-one mandelbrot`, reps=3, N=400 → 800):

```
  impl            ns/unit      rel   startup       t@N   kind
  kotlin           17.977     1.0x     63.9ms     73.8ms  loop
  c                24.302     1.4x      0.6ms      4.6ms  loop
  java             25.393     1.4x     48.1ms     55.1ms  lazy
  rust             27.143     1.5x      0.5ms      5.0ms  lazy
  roc              32.245     1.8x      0.4ms      6.5ms  eager-grid
  mezze           124.449     6.9x    791.4ms    993.3ms  lazy-grid
  python         1312.570    73.0x     11.6ms    222.8ms  loop
```

All ten agree on `mandelbrot(200) = 492855` and `mandelbrot(1000) = 12306668`.

**2 212.5 → 124.4 ns/unit: 17.8x on the published figure, and 123x off the leader
becomes 6.9x.**

AND THE HARNESS'S NUMBER IS NOT THE STEADY STATE. This file's `mandelannot`
measures the same loop at 58 ns/px over side 1200 → 1800. The harness slopes
400 → 800, which removes STARTUP but not WARMUP, and mezze's 791 ms startup
against kotlin's 64 ms says how much of its window is still the JIT coming up.
So the gap splits in two, and they are different problems:

- **steady-state codegen, ~3.2x** (58 against ~18). Not the self-tail-loop tier:
  `bench/cases/mandelannot` kernel 3 is the same loop folded to a single exit —
  the shape the direct-while tier accepts — and measures 62 ns/px, identical to
  the two that do not. `tier=not-fused` in `MEZZE_TRACE_SELFTAIL` is not evidence
  of a slow loop; the diagnostic prints before the direct-while attempt and
  reports only on the three fused-LONG tiers.
- **startup and warmup, ~12x** (791 ms against 64 ms), which in a one-second
  measurement window moves every mezze row in the table, not just this one.

### And the whole suite, not just the kernel that was being worked on

`just bench --reps 3`, every mezze app rebuilt with this compiler, against the
figures `mezze-benchmark/RESULTS.md` publishes:

| kernel | mezze published | mezze now | gain | kotlin now | gap |
|---|---|---|---|---|---|
| `map_filter_sum` seq | 64.869 | **10.753** | **6.0x** | 2.181 | 4.9x |
| `map_filter_sum` iter | 31.693 | **13.774** | **2.3x** | 2.181 | 6.3x |
| `mandelbrot` | 2 212.542 | **106.839** | **20.7x** | 33.630 | 3.2x |
| `sum_range` | — | 0.810 | — | 0.386 | 2.1x |

Relative to the fastest implementation of each kernel, mandelbrot goes from
**122.0x to 4.7x**. Checksums hold throughout — all ten implementations agree.

Two of these were never the target. `map_filter_sum` improved because the
collection pipeline hands a user callback to a stdlib HOF, which is the same
dictionary-through-a-function-value defect `defunc` kernel 3 isolates; the seq
form, which was the WORSE of the two at 64.869, is now the better at 10.753.

What is left, in size order, is no longer arithmetic: the collection pipeline at
4.9x, startup at ~12x, and steady-state float codegen at ~3.2x.

---

## Where the last 3.1x on mandelbrot is — and it is not arithmetic

`mezze-benchmark`'s mandelbrot is 69.1 ns/px against kotlin 22.3, c 22.4, java
23.6 and rust 26.9, measured in the same regime (N=1200 → 2400, every
implementation warm, checksums agreeing). 3.1x against the whole field, so not a
quirk of one comparison. Four hypotheses, three of them wrong, and the order they
fell in is the useful part.

**WRONG 1 — the self-tail-loop tier.** `MEZZE_TRACE_SELFTAIL` says `params=5
tier=not-fused`, and the three FUSED tiers are `FusedLongTailLoop{,1P,3P}` —
long only, nothing above three parameters. `bench/cases/mandelannot` kernel 3 is
the same loop folded to a single exit, which IS the shape the direct-while tier
accepts, at the cost of computing the squares twice: 62 ns/px, identical to the
two that get no tier. The diagnostic also prints BEFORE the direct-while attempt,
so `tier=not-fused` never meant what it looks like.

**WRONG 2 — boxing.** `project_boxing_elimination_blocked` says
`boxingEliminationTypes = {}` with a compile→deopt storm. That memory is two
months stale: the annotation reads `{long.class, double.class, boolean.class}`
today. And the loop allocates nothing — 2 young GCs for the Int loop against 3
for the Float loop over 2e7 iterations.

**WRONG 3 — the double specializations are not force-quickened.** Every
`Direct*Node` has `@ForceQuickening` on its long specialization and none on its
double one, which looks exactly like a missing unboxed instruction. Added it to
all ten; `bench/cases/floatloop` kernel 2 measured 831 ps/iter against 830
before. Reverted — correct-looking and worth nothing.

**WRONG 4, and this one was mine — the Int/Float gap is not a defect at all.**
`bench/cases/floatloop` holds shape and work constant and varies only the type:

```
  mezze   INT   acc + i     231 ps/iter      java  INT     50 ps/iter
  mezze   FLOAT acc + x     799 ps/iter      java  FLOAT  806 ps/iter
```

**The float loop is AT PARITY with Java — 799 against 806.** The 3.5x Int/Float
spread is the CPU: a double add is 3-4 cycle latency against an integer add's 1,
and a loop-carried accumulator is latency-bound. Java shows the same spread for
the same reason. (Java's 50 ps Int row is a folded closed form, not a loop — this
file's own method notes warn about exactly that, and I read the row anyway before
checking.)

### So the arithmetic is DONE, and here is the actual budget

Kernel 4 walks the identical pixel work with a tail loop instead of
`iter::range().fold()`:

```
  2  iter/fold grid      61 ns/px
  4  tail-loop grid      35 ns/px      ← same pixels, same escape loop
```

At ~12 escape iterations a pixel and the loop at Java parity, mandelbrot's 69
ns/px decomposes as:

| | ns/px | what it is |
|---|---|---|
| the escape loop | ~18 | at Java parity — nothing to win |
| `iter::range().fold()` | **26** | the lazy pipeline, per pixel |
| the rest | ~25 | `pixel` call, `escape` trampoline entry, two `float::from_int`, building a 5-field arg record |

Kotlin's 22.3 is about 18 ns of the same loop plus ~4 ns of overhead. **Every bit
of the remaining gap is call and iteration overhead, and none of it is
arithmetic.** The tail-loop grid at 35 ns/px is already 1.57x off kotlin rather
than 3.1x.

That also says the 26 ns is the same defect as `bench/cases/defunc`'s remaining
13x (10 131 ps/iter for a HOF call against 779 for a direct one) and the same one
behind `map_filter_sum` at 6.3x: **a callback reached through a higher-order
function.** Three kernels, three sizes, one mechanism — and `defunc` kernel 6
shows a TINY callback costs almost nothing, so what fails is a callback whose
body is too big to inline at the call site.

### And it was not the callback's size — it was a declined chain

**FIRST, A CORRECTION TO THE NUMBERS ABOVE.** They were taken with
`java -jar .build/*.jar`, which is NOT the program `mez build` produces. The
generated launcher adds `--add-modules=jdk.incubator.vector,jdk.internal.vm.ci`,
Serial GC, `NewRatio=1` and AppCDS; without the vector module a fused fold over a
flat arithmetic body dies outright with `NoClassDefFoundError:
jdk/incubator/vector/Vector`, and the grid-walk kernel read 21 ns/px through
`java -jar` against 10 through the launcher. Everything below is the launcher.
(The mandelbrot headline is unaffected — `mezze-benchmark`'s harness has always
run the launcher — and so is the float-parity result: 803 ps/iter through the
launcher against 799 through `java -jar`.)

`MEZZE_CHAIN_CANON_EMIT_DEBUG=1` on a built binary says it in one line:

```
  2 [chain-canon-emit] emitted FusedLongRangePipeline (Accumulate, 0 stages)
  6 [chain-canon-emit] reject: unclassifiable folder body
```

The chain DOES fuse — `FusedChainIR(source=RangeSeq(0, side*side), stages=[],
sink=Fold(0, MakeLambdaIR))` — and then the EMITTER declines it. Both of its Fold
modes put the folder's arithmetic into the loop: `Accumulate` wants `acc OP x`
with at most one element transform, `AccumulateTree` wants a pure arithmetic tree
over the two parameters with no captures and no locals. A folder that CALLS
anything satisfies neither, so the whole chain fell back to the native fold and
its element-at-a-time iterator protocol.

The fix is a third mode, `AccumulateCall`: build the closure ONCE in the loop
prologue, call it per element, thread the result. It accepts captures precisely
because the closure is built once — and a folder captures the moment its body
mentions anything from the enclosing scope, which mandelbrot's does (`side`,
`span`). `CallEach` had already settled this trade for the ForEach sink, and its
own note puts it plainly: the win is "going from not fused at all to fused with
one alloc per iter", with escape analysis often folding the record away. The loop
becomes a counted range walk with no iterator state and no Option per step.

MEASURED, `bench/cases/mandelannot` side 1800, min of 3, launcher:

```
  2  iter/fold grid       67 -> 45 ns/px      1.49x, checksum 39 866 172 both
  4  tail-loop grid       38 -> 36 ns/px      control
```

The gap between walking the grid lazily and walking it by hand fell from 29 ns a
pixel to **9** — and 9 ns is what the same walk costs with a folder the emitter
CAN inline, so what remains is the protocol floor rather than this defect.

And in `mezze-benchmark`'s own harness, same regime (N=1200 → 2400, every
implementation warm), **min of three independent runs at reps=5**:

```
  kotlin   21.225        c   21.713        mezze   27.692 ns/unit
```

**69.126 → 27.692, a 2.50x gain, and the gap to the fastest implementation goes
from 3.10x to 1.30x.** All ten implementations still agree on
`mandelbrot(200) = 492855` and `mandelbrot(1000) = 12306668`.

THE MIN OF THREE MATTERS AND I ALMOST SKIPPED IT. One earlier run of the same
binary read **17.591** — ahead of kotlin and c — and I wrote that down as a
result before re-running. Three runs put mezze at 27.7, 28.8 and 31.9 against
kotlin's 21.2-22.0, whose spread is 4-6%. Mandelbrot on this harness varies by
1.9x run to run for mezze; a single sample cannot tell 1.3x from 1.0x. Third time
this session (see the 381 ps tail-loop "regression", and the 74 ns/px mandelbrot
"regression" that was 60 on a re-run).

NEUTRAL ELSEWHERE, by the fix's own switch at `--scale 3`: `map_filter_sum`'s two
rows came out iter 12.739 / seq 10.931 with it on against 11.598 / 11.704 off,
and on a second pair 11.324 / 12.143 on against 11.858 / 11.109 off — **the signs
flip between pairs**, so no effect. `sum_range` reports UNMEASURABLE at this
scale either way.

---

## Startup: 820 ms, and where every millisecond of it goes

`MEZZE_TIMING=1` on a built app (the LAUNCHER, not `java -jar`), mandelbrot at
size 2 so the work is nil:

```
  [0ms]    main() entered
  [104ms]  embedded app detected (2201 fns)        CBOR decode of the program
  [405ms]  Engine built (JIT ON)                   Truffle + Graal coming up
  [451ms]  Context built
  [468ms]  source built — calling context.eval()
  [710ms]  context.eval() returned                 145ms compile + ~97 run
  [714ms]  context closed
```

Against kotlin's **78 ms** for `java -cp bench.jar BenchKt` on the same machine —
10.5x, and that is the question "why is kotlin's footprint so small". Measured
answer:

**213 ms of it is not ours.** A bare `Engine.newBuilder().build()` against the
same jar costs 213 ms, and adding every option this launcher sets changes it by
nothing — thresholds 211 ms, `compiler.InliningRecursionDepth` 213 ms, all of
them together 213 ms. That is GraalVM's Truffle runtime plus the JVMCI compiler
interface initialising, and kotlin never pays it because kotlin is not a language
runtime: it is bytecode the JVM already knows how to run. 6 089 classes load for
mezze.

The rest: ~104 ms decoding 2 201 `FunctionIR`s from CBOR, ~116 ms on the first
BDSL emission (8 roots — the first-touch cost of the generated bytecode
interpreter; everything else is already deferred to `LazyStubRootNode`), 46 ms for
the Context, ~90 ms of our own pre-engine flag and option setup.

### The launcher's settings are right, each one tested

| change | result | verdict |
|---|---|---|
| AppCDS (`-XX:SharedArchiveFile`), as shipped | 836 ms on, 1 096-1 167 off | **keep — saves ~260 ms** |
| JEP 483 AOT cache (`-XX:AOTCache`), JDK 25 | 796 ms against 817 | no — 2.5%, needs a two-phase build, and `--add-modules` blocks the full module graph it wants |
| drop `--add-modules` | 807 ms against 817 | no — costs nothing, and dropping it breaks SIMD |
| `MEZZE_JIT=0` | 789 ms against 836 | the JIT is ~30 ms of STARTUP, and 10-20x of throughput |
| raise thresholds to 200/1000/500 | below | **no — measured, declined** |

The threshold row deserves its own line because the code TOLD me to make that
change: the comment beside it said "if boxing elimination is re-enabled, raise to
200/1000/500", and boxing elimination has been re-enabled. `MEZZE_ENGINE_OPTS`
makes it a no-rebuild A/B. A block of runs said 34.0 → 25.9 ns/unit, a 1.31x win.
Interleaving the pairs instead — current, raised, current, raised — said
27.3/32.6, 14.4/38.0, 16.2/37.7, 33.3/27.1: **three of four favour the thresholds
as they are.** The block had measured the machine warming up; five consecutive
runs of the UNCHANGED setting read 32.8, 29.3, 25.4, 17.7, 16.7. The comment is
corrected in place with this measurement.

### And the finding that matters more than any of them

mezze and kotlin in the SAME harness invocation, four times, so drift cancels:

```
  mezze=20.461  kotlin=22.019
  mezze=27.168  kotlin=21.920
  mezze= 8.830  kotlin=22.106
  mezze=31.256  kotlin=22.143
```

**Kotlin holds to ±1%. Mezze spans 8.8 to 31.3 — 3.5x.** At `--scale 6`, where
the compute window dwarfs warmup, it settles:

```
  mezze 24.633 / 25.400 / 26.761      kotlin 21.756 / 21.951 / 21.967
```

±4%, per-run ratio **1.13-1.22x**. So the steady-state answer is that mandelbrot
is within ~15% of kotlin, and **what is left at the published scale is not
throughput, it is JIT WARMUP inside a one-second window** — 780 ms of startup
plus a few hundred of compilation against a t@N of ~1 070 ms. Every figure this
file reports for that kernel at `--scale 3` or below carries that spread,
including the earlier 69.1 and 27.7.

It also explains why the threshold experiment was so unstable: it moves warmup
around, which is the part of the window that varies.

**NOT FIXED, deliberately.** The one structural win left in the jar is overlapping
the 104 ms decode with the 213 ms engine build — they share no data, so the decode
is free on another thread. It needs `EmbeddedApp.present` to stop requiring the
decode, which means splitting the presence check from the deserialize and holding
a decoder behind the field. That object exists to be snapshotted by native-image's
`--initialize-at-build-time`, and putting a closure into the image heap to save
100 ms on the JVM path is the wrong trade. ~12% of startup to whoever finds a way
that leaves the contract alone.

## The native binary is not slow and it DOES jit — measured 2026-11-06

The question was "why is the native binary slow and why doesn't it jit". Both
halves were true once and neither is now; #402 landed in August 2026 and the
notes describing the old state are what keep the question alive.

```
  $ MEZZE_DIAG=1 ./mandelbrot-native 2
  [mezze] Truffle runtime: com.oracle.svm.truffle.api.SubstrateTruffleRuntime (GraalVM CE)
```

`SubstrateTruffleRuntime`, not `DefaultTruffleRuntime` — runtime compilation is
installed and the binary JITs. **Startup is 78 ms, which is kotlin's 78 ms to the
millisecond**, against the jar's 820.

Total wall time, mandelbrot, min of 2-3 per cell:

| N | mezze native | mezze jar | kotlin |
|---|---|---|---|
| 2400 | **306 ms** | 1 245 ms | 202 ms |
| 4800 | **780 ms** | 1 669 ms | 580 ms |

Slopes from those pairs (Δwork = 17 280 000 px): native 27.4, jar 24.5, kotlin
21.9 ns/px. Native is **4.1x faster than the jar in total time at N=2400 and 2.1x
at N=4800**, and 1.5x / 1.34x off kotlin rather than 6.2x / 2.9x.

### "Native starts fast but runs slow" — NOT TRUE HERE, and the 12% above was noise

That slope pair says native gives up 12% of throughput, which is the textbook
AOT trade, and I wrote it down as one. It does not survive a bigger window: at
N=2400/4800 the jar's 1 100 ms fixed cost is a third of t(N), so small jitter in
either endpoint moves the slope several ns. Measured again at N=6000 → 12000
(Δwork = 108 000 000 px, the JVM unambiguously at peak for seconds), interleaved,
three rounds:

```
            native                jar
  round 1   26.14 ns/px           25.80
  round 2   25.82                 27.05
  round 3   25.94                 27.61
  mean      25.97  (±0.6%)        26.82  (±3.4%)
```

**Level — and native is the steadier of the two.** Identical checksums
(12 306 668).

**Why the textbook rule does not apply.** Compile a normal Java application with
native-image and every method is AOT code with no profile feedback, which is
where 20-50% off peak comes from. A TRUFFLE LANGUAGE built with
`--macro:truffle-svm` is a different animal: the GUEST program is still compiled
AT RUNTIME, by Graal, through the same partial-evaluation pipeline as on the JVM.
What native-image compiles ahead of time is the interpreter and the runtime — the
HOST. For a compute kernel the hot loop is runtime-compiled machine code either
way, so there is nothing to give up.

That is exactly what #402 bought, and it is worth being precise about: before it,
the native binary had no runtime compiler at all and the textbook rule applied
with interest — 32 s against the jar's 1.13 s on hello_perf.

**Where the AOT penalty WOULD show** is a workload dominated by host-side code —
string formatting, Java-implemented collections, interop, I/O — because that code
IS ahead-of-time in the image. This kernel is float arithmetic in a guest loop,
which is the best case for native. One kernel is not a licence to claim parity
everywhere.

**And it is STABLE, which the jar is not.** Four paired runs:

```
  t2400=316 t4800=778      26.74 ns/px
  t2400=323 t4800=776      26.21
  t2400=338 t4800=765      24.71
  t2400=323 t4800=781      26.50
```

±4%. The jar's figure on the same kernel spans 8.8-31.3 ns/unit because its
measurement window is mostly JIT warmup; the native binary has almost none to do.

### Which means the published numbers measure the wrong artifact

`mezze-benchmark`'s harness runs `.build/<app>/<app>` — the JAR launcher. That is
the artifact carrying a 1 100 ms fixed cost and a 3.5x warmup spread, and it is
not what a user ships. The native binary starts in 78 ms, holds ±4%, and is 120 MB
self-contained against kotlin's small jar plus a JDK. Measuring both would say
something true; measuring only the jar understates the product and imports the
variance that makes every mezze row in that table unreadable at the default scale.

Not changed here — it is another repo's methodology, and the call is the user's.

## "Why can't we have both" — we do, and it is one artifact

The ask was a jar that starts like kotlin and a native binary that peaks higher
than the jar. The second half is already true and the first is not reachable; but
the thing behind the ask exists, measured today:

| artifact | startup | peak (Δwork=108M px, 3 rounds) | spread |
|---|---|---|---|
| **`--native -O2`** | **19 ms** | **25.84 ns/px** | **±0.6%** |
| `--native` (`-O1`) | 78 ms | 25.97 | ±0.6% |
| jar launcher | 820 ms | 26.82 | ±3.4% |
| kotlin | 78 ms | 21.9 | ±1% |

**`mez build --native -O2` starts in 19 ms — four times faster than kotlin — and
peaks slightly ahead of the jar, with the tightest spread of anything here.** Its
`MEZZE_TIMING` reads 0 ms for the decode, 0 ms for the Engine, 1 ms to Context:
the app and the engine are baked into the image heap, so there is nothing to
decode and nothing to initialise. Checksum 12 306 668, same as every other row.

mez's `-O1` is not native-image's `-O1` — it is a prebuilt `-O2` shell with the
app's IR appended as a footer and decoded at startup, which is what the 78 ms
mostly is. `-O2` is the full image with the app baked in.

### The jar cannot have kotlin's startup, and here is the floor

Of the 820 ms, **253 ms is gone before any of our code runs**: ~213 ms for
`Engine.build()` (Truffle runtime + the JVMCI compiler interface, unchanged by
every option we set — 211/213/213) and ~40 ms of JVM start. That is 3.2x kotlin's
total, and it is the price of being a guest language on a JVM that has to bring up
a language runtime first. Kotlin is bytecode the JVM already knows.

What IS available is parallelism: the 104 ms CBOR decode and the ~116 ms first
BDSL emission are both independent of the engine build, so up to ~220 ms could
hide behind the 213 ms. A realistic jar floor is **~550-620 ms**, not 78.

### And native peak cannot pull far ahead of the jar either

Both run the SAME Graal-compiled guest code. With `--macro:truffle-svm` the guest
program is compiled at runtime by Graal through the same partial-evaluation
pipeline; only the host is ahead-of-time. So native-vs-jar peak is a wash by
construction (25.84 against 26.82, and the jar's gap is mostly its own variance),
and the remaining **1.19x against kotlin is our code generation** — equally
present in both, and the thing actually worth working on.

So the answer is not two artifacts with complementary strengths. It is that
`--native -O2` already dominates on both axes, and the jar exists for build speed
and portability rather than for performance. Worth noting: `--native` defaults to
`-O1` for build time, so the 4x-better-startup artifact is the one users do not
get unless they ask. That is a product call, not a perf finding.

## Parallelising startup: the target is located, the box was not quiet enough

~168 ms of the jar's boot is **class initialisation that depends on nothing**, and
it sits on the critical path behind work it shares nothing with. Measured on a
quiet box, standalone, with no Engine and no app present:

```
  141 ms   Class.forName("…bytecode.MezzeBytecodeRootNodeGen")
   27 ms   Class.forName("…bytecode.BytecodeCompiler")
    2 ms   Class.forName("…store.StoreSerialization")
```

Both initialise cleanly with nothing else running, which is the structural point:
they need no Engine, no Context and no decoded app. Yet boot pays them inside the
first `eagerCompileReachable`, AFTER a 104 ms CBOR decode and a 213 ms
`Engine.build()` that neither depends on. On 16 cores they should disappear into
that 317 ms.

The change is ~15 lines: a daemon thread from `MezzeAppMain.main` that does those
two `Class.forName` calls. It is safe by construction — JVM class initialisation
is mutually exclusive per class, so a main thread arriving mid-init blocks and
then proceeds; there is no partially-initialised window. A throwing static
initialiser leaves its class erroneous either way, so the only difference is
`NoClassDefFoundError` instead of `ExceptionInInitializerError`.

**NOT LANDED, because the machine stopped being measurable.** The sequence, which
is the useful part:

1. Blocked measurement said 1 664 ms with the prewarm against 820 without —
   a catastrophic regression. **That was drift**: reverting gave 1 261-1 434 ms,
   so the baseline itself had moved 1.6x.
2. Gated it behind `MEZZE_PREWARM` and interleaved on ONE binary: off/on pairs of
   1558/1436, 1450/1472, 1597/1522, 1592/1424, 1724/1783 — three of five favour
   the prewarm, means 1 584 against 1 527, a 3.6% win. Too weak to land.
3. `/proc/loadavg` said **30.3 on 16 cores**, with several foreign JVMs at
   200-530%. Stopping our own Gradle daemons took it to 23 and it then climbed
   to 33.5. The phase marks confirmed it: the same `eager BDSL emitted` step read
   215 ms, 1 088 ms and 975 ms across three runs with the prewarm OFF.

Startup is almost entirely class loading, which is the measurement most sensitive
to a loaded box — more so than any throughput kernel here, because it is I/O and
metaspace rather than a hot loop. **This needs an idle machine, and then it is a
15-minute job**: apply the thread, interleave `MEZZE_PREWARM` on one binary, and
expect the `Engine built` mark to stay put while `eager BDSL emitted` shrinks.

It also explains why the whole shape is what it is, and why the native image wins
so completely: 6 089 classes load on the jar path, AppCDS saves 260 ms by
pre-parsing them, and `--native -O2` skips the entire phase (19 ms total, every
boot mark reading 0-1 ms).

## Method notes

- **Report a slope**, `(t(2N) − t(N)) / (work(2N) − work(N))`, so fixed startup
  cancels. `work` is not always the argument: a grid kernel's work is side²,
  and dividing by the argument once produced a slope of −20723 ns.
- **A non-positive slope is a dead measurement**, not a fast result.
- **Check exit codes.** An 851 ms figure once came from a run that died of
  `Java heap space` and exited early.
- **Allocation beats timing** as a regression assertion: nearly deterministic,
  and it fails by a wide margin rather than flaking.
- **Any kernel whose result has a closed form is not a loop benchmark** — LLVM
  and the JVM both fold `sum 0..n`.
- **Interleave an A/B, never block it.** Five consecutive runs of one unchanged
  setting read 32.8, 29.3, 25.4, 17.7, 16.7 ns/unit — the machine warms up across
  a session, so A-then-B hands the win to B. Interleaved pairs reversed a 1.31x
  "win" into a 3-of-4 loss.
- **Put the baseline in the SAME invocation.** mezze's mandelbrot figure spans
  8.8-31.3 ns/unit run to run while kotlin's holds to ±1%; only the per-run ratio
  against a reference measured beside it means anything, and only at a scale where
  the compute window dwarfs warmup (`--scale 6`: ±4%).
- **Run the LAUNCHER, not `java -jar`.** `mez build` writes
  `.build/<app>/<app>`, which adds `--add-modules=jdk.incubator.vector,…`, Serial
  GC and AppCDS. `java -jar` read 21 ns/px where the launcher read 10, and a fused
  fold over a flat arithmetic body dies outright with `NoClassDefFoundError:
  jdk/incubator/vector/Vector`.
