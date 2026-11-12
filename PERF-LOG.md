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

### LEDGER UPDATE 2026-11-06 — what moved, and the honest shape of what is left

Nine commits of dictionary and fusion work. Rows that changed STATE, each with
the evidence rather than the intention:

| row | was | now |
|---|---|---|
| unresolved `having out` dictionaries (mandelbrot) | ABOVE FLOOR, 47.5x | **CLOSED.** 2 212.5 → 25.84 ns/unit published figure; 1.13-1.22x kotlin at a scale where warmup does not dominate |
| a polymorphic fn passed as a VALUE to a HOF | ABOVE FLOOR, 57x | **ABOVE FLOOR, 13x.** The DICTIONARY half is closed (82 873 → 10 131 ps/iter, 8.2x); what is left is the indirect call itself, 10 131 against 779 for calling the same function directly |
| HOF call-site seeding | zero specialisations | **PARTIAL.** A function passed as an ARGUMENT is now seeded at the type the callee's signature declares for that parameter. A function reached any other way still is not |
| a fold whose folder calls anything | not a row — the chain was silently declined | **CLOSED.** `AccumulateCall`: 67 → 45 ns/px on the grid kernel; the lazy-vs-handwritten gap fell from 29 ns/px to 9 |
| **Float arithmetic** | UNKNOWN | **AT FLOOR.** 803 ps/iter against JAVA's 806 on the identical two-parameter loop — parity. The 3.5x Int/Float spread is the CPU's FP dependency latency, which Java shows identically |
| native binary JIT | believed interpreter-only (a 64-day-old note) | **AT FLOOR.** `SubstrateTruffleRuntime` installed; peak 25.84 against the jar's 26.82, and STEADIER (±0.6% vs ±3.4%) |
| **startup** | UNKNOWN | **MEASURED, three artifacts.** `--native -O2` 19 ms · `--native` (-O1 shell+append) 78 ms · jar 820 ms, of which ~253 ms is irreducible (213 Engine.build + 40 JVM). Jar floor ~550-620 ms with the parallelism below |
| **a loop whose arithmetic involves an EFFECT OP result** | not a row | **ABOVE FLOOR, cause named.** `effectsurface`'s `go_a/d/n/o/r` still compile as `[i, acc, __dict_add]` — five of 48 dumped functions. `go_a = { i, acc } -> … { i = i - one_aborted {}, acc = acc + i }`: the chain bottoms out in an effect op's return, so it never gets the concrete anchor the new improvement rule needs. The effects × numerics intersection, and the last live instance of the "mono refuses a concrete function" family |
| startup parallelism | not a row | **LOCATED, NOT LANDED.** ~168 ms of class init (`MezzeBytecodeRootNodeGen` 141, `BytecodeCompiler` 27) that needs no Engine, Context or app, sitting behind 317 ms of work it shares nothing with. 15 lines; needs an idle box to validate |

**And the two measurement rules that cost the most this session**, because they
invalidate rows rather than add them: *interleave an A/B, never block it* (the
machine warms across a session: five runs of ONE unchanged setting read 32.8,
29.3, 25.4, 17.7, 16.7 ns/unit), and *run `.build/<app>/<app>`, not
`java -jar`* (different flags, 2x different numbers, and a crash on one shape).
Three single-sample claims were published and retracted inside this session.

**The shape of what is left, counted rather than felt.** Of the areas a language
has to be fast at, this is where the evidence stands:

- **AT FLOOR with a test**: records, variants, `let`s, tail loops, direct calls,
  single-impl ability methods, `seq` fold/sum/any, `bytes`, Int and now Float
  arithmetic, the fused pipeline, the native binary.
- **ABOVE FLOOR with a named cause**: indirect calls (~8-13x), `map`/`set` walks
  (a LAYOUT problem — primitive-specialised storage is the lever), `List` against
  `Seq` (3-5x), string interpolation (1.8x), the record-ABI natives still
  unported, the effect-op arithmetic row above.
- **UNKNOWN — never measured, and this is the biggest category**: all of Loom
  and async (spawn, join, channels, STM, atoms, parallel map, cancellation,
  structured scope) · real continuation capture (the 0.38 ns figure is the
  single-return fall-through ONLY) · handler install/uninstall, chain depth,
  scoped performers, the abort path, handlers in a loop, effects once a second
  thread invalidates `singleThreaded` · all of strings · pattern matching in
  every form · Frac, Nat, Char · most ops on Map/Set/SortedMap/SortedSet/Deque/
  NonEmptyList · codec · polyglot crossing · failure paths · comptime.

That last bullet is the answer to "where are we overall": **the measured surface
is fast, and the unmeasured surface is most of the concurrency and effects
story.** Not one of the Loom or continuation rows has a number against it, and
the ledger's own rule is that an UNKNOWN row is where wins hide — the 1503x
`size` win was sitting in an existing bench nobody had run at a realistic N.

### MONO — status, counted 2026-11-06

The question "is mono done?" has a measurable answer, and it is a COMPILE-TIME
fact so a loaded machine cannot spoil it: **how many compiled functions still
carry dictionary parameters.** A `__dict_` param is mono having failed to reach
that function.

Across every built bench, `MEZZE_DUMP_FN='*'`, counting `params=[…__dict…]`:

```
  bench             total   dict
  allocsurface         62      0
  coresurface          37      0
  defunc               51      2
  dispatch_probe       67      1
  dotprobe             28      0
  effectsurface        48      5
  floatloop            30      0
  fuseall              40      0
  fusecross            31      0
  fusegaps             67      0
  letcost              45      0
  mandelannot          46      0        ← was the 122x case
  mutabi               57      0
  mutvsimmut           41      0
  qsort_pure           25      0
  recordcost           45      0
  ---------------------------------
  TOTAL               720      8   (1%)
```

**Fourteen of sixteen benches are at zero**, and `mandelannot` — the kernel this
session opened at 122x off kotlin because of dictionary passing — is one of them.

**All eight named, and they are THREE causes, only two of which are defects:**

| function | cause | defect? |
|---|---|---|
| ~~`apply_fold [n, f, __dict_add]`~~ | **GONE** (full mono, 4c5edeb33). It was never "present and uncalled": `main` and `k_hof2` both emitted `CallDirectIR` to it, which the hash probe showed and this row got wrong | was **yes** |
| `step_alt [x, __dict_add, __dict_mul]` | the only carrier left. Never called DIRECTLY anywhere — only passed as a value — so no call site exists to specialise it from, and the dict goes to a wrapper instead | **no**, and this one is sound: a spec needs a site |
| `add2 [a, b, __dict_add]` | generic original in `dispatch_probe` | **no** |
| ~~`go_a` `go_d` `go_n` `go_o` `go_r`~~ | **CLOSED** — see below | fixed |

**CLOSED, and it was not what the row said.** The cause was not "an effect op's
associated type is not a concrete anchor", and it had nothing to do with literal
defaulting — the first diagnosis was reasoned from a mono-time probe instead of
asking the typechecker, which answers plainly:

```
  lit_int : Int          lit_nat : Nat          lit_float : Float
```

A bare `0` IS an Int. The real difference shows in two loops identical but for
where the subtrahend comes from:

```
  plain = { i, acc } -> … plain { i = i - 1,      acc = acc + i }
  viaop = { i, acc } -> … viaop { i = i - one {}, acc = acc + i }

  plain : { i: Int, acc: 'a } with NumAdd Int 'a having 'out = 'a -> 'a
  viaop : { i: Int, acc: 'a } with NumAdd Int 'a having 'out = out -> 'a <*>
```

`'out = 'a` against a FREE variable named `out`, tied to nothing. And the cause
was written down in `ConstraintGenerator` already: the recursive self-reference
equality ran for **pure bodies only**, because equating the whole arrow equates
the effect ROW, and `<*>` then reports "declared `<*>` … but this body performs
none" (`RecursiveArbitraryDerivationTest`). That comment called the rest "the
narrower follow-up, and it needs the row question answered first".

The row question's answer is to leave the row out of the equation: an effectful
recursive binding now gets the same equality with `effects = null` on the right,
so param, return and every ability-constraint variable unify while the rows stay
with the machinery that owns them. `viaop` reports `having 'out = 'a` and keeps
its `<*>`.

RECOUNTED from clean caches across the same 16 benches: **2 dictionary-carrying
functions out of 293**. I wrote here that neither was a defect — that
`apply_fold` and `step_alt` were generic originals kept beside their
specializations. **That was wrong, and asserted without checking.** Printing
each function's OWN hash beside its params settles it:

```
  apply_fold hash=6f0d48f2 params=[n, f, __dict_add]
  step_alt   hash=e351b6ea params=[x, __dict_add, __dict_mul]
```

and then grepping for calls to those hashes:

```
  6f0d48f2  <- CallDirectIR from main, and TWICE from k_hof2 (under one ADD)
  e351b6ea  <- MakeLambdaIR only, through dict_wrapper_step_alt
```

`k_hof2` and `k_hof` CALL the generic. Both remaining carriers are one cause,
and it is the annotation rule failing:

| the callback at the site | `apply_fold` specialised? |
|---|---|
| inline lambda (`k_closure`) | yes, dict-free (`3f76640a`) |
| **annotated** top-level fn (`step_mono`) | yes, dict-free (`6865275d`) |
| ~~un-annotated top-level fn~~ | **yes since 4c5edeb33** — four dict-free `[n, f]` specialisations. The row below is what the gap WAS |

Kernels 3 and 7 differ ONLY by an annotation and only the annotated one
monomorphises. `step` escaped a dictionary by luck: `k_named` calls it
directly, and that direct call seeded a spec. `step_alt` is never called
directly, only passed, so nothing seeded it and it needs a wrapper.

The dictionaries themselves are RIGHT, and type-driven — the same two-type
probe shows `at_int` passing Int's `add#b015652c` and `at_float` passing
Float's `add#60ed70d3`. So this residue is a missed specialisation, not
unsoundness. A dictionary is the FALLBACK representation; every one left in
compiled IR is a function mono did not reach. `effectsurface` went 5 to 0. (Totals moved 720 to 293
because more is specialised, so fewer functions are compiled separately at all.)
Checksums hold: effectsurface 200000010000000 on kernels 1/5/6, defunc
400000040000000 on 3 and 6, mandelannot 4431371.

`:typechecker:test` 4 826/0, `:interpreter:test` 7 471/0 (11 skipped).

**What is fixed, with the measurement:** transitive self-call 45x · local lambda
specialisation 314x · phantom constraint typeParams (0x, recorded because a
correct fix that moves nothing is a result) · having-out dictionaries 47.5x →
mandelbrot closed · a function passed as an ARGUMENT now seeded at the type its
callee declares · a reference to a generic function now resolves to its
specialisation rather than a dictionary-carrying wrapper, 8.2x.

**What is still open in mono, ranked:**

1. **`MEZZE_MONO_STRIP=1`: the liveness scan is FIXED and the pass still cannot
   be enabled.** Three layered defects, peeled one at a time against
   `bench/cases/defunc` from a clean cache:
   - *the capture LIST was invisible.* A nested lambda records what it closes
     over in `captures: List<TypedCapture>`, a structural field no walk over
     expressions reaches — and after Piece 3 splices, that list is the ONLY
     record left, because nothing in the body mentions the parameter any more.
     A/B: remove the read, the crash returns; restore it, the build succeeds.
   - *it compared NAMES.* `TypedCapture` carries `source: BindingRef`, so a
     capture already says which binding it came from. Keyed on identity now, and
     the shadowing case is pinned: an inner lambda with its own parameter of the
     same name must NOT keep the outer one alive, or the strip silently never
     fires.
   - *a parameter is addressed TWO ways.* `Param(i)` directly, and from inside a
     `do` block as a FRAME SLOT, because the layout is
     `[captures…, params…, locals…]`. MEASURED: `apply_fold = { n, f } -> do {
     let go = …f… }` gives `go`'s captures as `[go<-Local(1,2), f<-Local(1,1)]`
     — `f` is params[1] and arrives as `Local(1,1)`. Mapping only `Param(i)`
     brought the crash back on a DIFFERENT function, which is how this was found.

   **What is left is structural and is what the emitter's own error always
   said**: "Dropping it would shift every later capture's slot." With the scan
   correct, the build succeeds and the RUN fails —
   `FrameSlotTypeException: Frame slot kind Object expected, but got Illegal at
   frame slot index 4`. Removing a parameter from the middle of that layout
   shifts every later slot and nothing renumbers the `Local(depth, index)`
   references. So the flag stays off, now for a single named reason.

   **And measure the payoff BEFORE building the renumbering.** The pass removes
   dead closure parameters from specializations — one fewer argument per call —
   and nobody has ever priced it. `bench/cases/defunc` kernel 3 is the shape that
   would benefit and reads 7 947 ps/iter with the pass off; the renumbering is
   real work and should not be done on the assumption that it is worth something.

   Three tests pin the scan (`ClosureParamLivenessTest`), built by hand because
   the shape that matters is one the typechecker never emits — written from
   source it passed with AND without the fix, verified, so it pinned nothing.
3. **HOF seeding is partial** — a function passed as an ARGUMENT is seeded; a
   function reached any other way (stored in a record, returned, read from a
   collection) is not.
4. **the bimorphic call site** — two callbacks through one HOF costs 5x one
   callback even with both specialised. Dispatch, not mono, but it is what caps
   the HOF story.

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
| ~~a SECOND `map` in an iter pipeline~~ | **CLOSED — 564 → 106 ps/elem, 5.3x**, in two steps: the extras table (1.33x) then teaching the VECTOR loops to apply it (**4.14x**; 4.27x again at n=6e7). Two stages now sit at 106 against the one-stage control's 75 — the vectorised floor | `bench/cases/iterstage`, a pure stage-count ladder. `tryBuildFusedLongPipeline` refused the fast path on a second map (`if (mapOp >= 0) return null`); maps two and later now ride an `extraOps` int, 5 bits a stage, literals in a parallel `long[]`, and `FlpSimd` applies them LANEWISE. Capped at TWO extras by measurement (four total stages read 0.57x). **THE DECOMPOSITION WAS THE WHOLE JOB.** Two probes: hardcoding the extra operand as a literal changed nothing (417 → 429), and disabling the vector path moved the ONE-STAGE row 75 → **292** ps. So the "floor" was never the scalar loop — it was `FlpSimd`, whose two pipelines took ONE map POSITIONALLY. The 7.7x was ~3.9x vectorisation, 1.3x generic-vs-FLP loop, 1.5x the stage. Predicted ~5.6x before building it; got 5.3x. **WHY A RUNTIME SWITCH IS FINE IN ONE LOOP AND NOT THE OTHER:** the PE'd scalar loop pays a `flpOp` dispatch PER ELEMENT (array elements are not PE constants — see [[constop_array]]); `FlpSimd` is `@TruffleBoundary`, nothing folds there anyway, and `simdOp`'s switch already ran once per VECTOR iteration, so a per-stage switch is divided by the vector length. **FOUR SITES, NOT TWO:** each pipeline has a vector loop AND a scalar tail for `n % VL`; a tail missing the extras is right only when n is a multiple of the vector width. Guard = MultiMapStageFusionTest (13), whose nine original cases run FIVE elements and so could not reach SIMD at all |
| **`.map(f).fold(acc + x * k)` — ran UNFUSED** | **145.9x** (13 863 → 95 ps/elem; 151.1x again at n=6e7), young GC **80 → 4** (237 → 4 at 6e7). Both in-case controls at exactly 1.0x | `bench/cases/foldshape`. A body of the form `acc OP f(x)` is split into a fold op plus an ELEMENT TRANSFORM, which took the single map slot. With an explicit map already there it hit `if (mapOp >= 0) return null`, and that did not merely decline the fast path — the chain then met the blanket "compound folder needs the map stage an explicit map already holds" refusal and fell back to the REAL folder, unfused. It now appends as the LAST map stage via the extras table, which is where a sink-side transform belongs; no algebra involved. **THE CLIFF POINTED THE WRONG WAY, which is why it survived a whole ranking.** The two controls are the SAME pipeline with a DEEPER body — not the `acc OP f(x)` shape, so no transform is extracted and they take the generic loop's inline tree at 724 ps. A ONE-op transform was **19x slower than a TWO-op one**. The `compound fold body` row on `fusegaps` reads 8.6x; the shallower spelling of it was 167x off the floor and was not in that case at all. **A row ranking only ranks the shapes someone thought to write down.** **WHAT IS BLOCKED:** `acc + x*2 - 1` parses as `(acc + x*2) - 1`, so extracting a transform needs reassociation across the accumulator — and `Int` overflow RAISES, so that changes WHICH operation overflows. Those keep the tree, by design. `acc + (x*2 - 1)`, the parenthesised form, is a pure two-op element CHAIN needing no algebra, still measures 724, and is the honest next step for this row |
| ~~a variant BOUND TO A NAME before being matched~~ | **CLOSED — 5.9x one field, 10.4x two**, 4 075 → 690 and 6 940 → 667 ps/iter, young GC **41 and 82 → 1** | `bench/cases/recfields`. `VariantMatchFolder` elides a variant built as the scrutinee of the match that destructures it, and it ran BEFORE `LetCopyPropagation` — deliberately, since its output is ordinary `let`s the passes between them handle. So giving the scrutinee a NAME hid the construction from it, because propagation is what makes them adjacent: the same loop cost 6.4x more for a formatting choice. Fixed by letting a CONSTRUCTION join the recomputable set (sound only because that pass requires EXACTLY ONE READ — moving an allocation cannot duplicate it, it has no effect to reorder, and one read cannot compare the value with another instance of itself) and running the folder a SECOND time afterwards. **PLAIN RECORDS WERE ALREADY FREE**, which is why this looked done: a one-field record built and read per iteration measures 220 ps against a 223 ps control with ONE collection, because escape analysis deletes a record that never escapes. So [[rec_boxed]]'s "fix `MakeRecord` construction first" is not the lever it reads as — VARIANTS were, at 17x the equivalent record, and no row ranking said so |
| ~~an `if` between the construction and the match~~ | **CLOSED — 2.8x**, 2 975 → 1 068 ps/iter, young GC **25 → 1**, against a 785 ps ceiling carrying no variant at all | `match (if c then Some { val = i } else None) is …` is what every `Option`-returning helper produces and is not adjacent. The match now pushes into the branches and folds each, ONLY when every branch is itself a construction — which is what makes it free, since each distributed match then collapses to the ONE case body its tag selects, and if either side would decline nothing is rewritten. **THE CALL WAS NEVER THE OBSTACLE:** the function-call form and the inline-`if` form measured the SAME 2 985 and 2 975 before, so the plan to reach this by writing an inliner would have been work on the wrong half, and both shapes moved on one change |
| ~~`.map(f).fold(acc + x * k)` ran UNFUSED~~ | **CLOSED — 145.9x** (13 863 → 95 ps/elem; 151.1x again at n=6e7), young GC **80 → 4**, both in-case controls at exactly 1.0x | `bench/cases/foldshape`. A body of the form `acc OP f(x)` splits into a fold op plus an ELEMENT TRANSFORM, which took the single map slot; with an explicit map already there it hit `if (mapOp >= 0) return null`, and that did not merely decline the fast path — the chain then met a blanket refusal and fell back to the REAL folder, unfused. **THE CLIFF POINTED THE WRONG WAY, which is why it survived a whole ranking:** the same pipeline with a DEEPER body is not the `acc OP f(x)` shape at all, so it takes the generic loop's inline tree at 724 ps — a ONE-op transform was 19x slower than a TWO-op one. The `compound fold body` row reads 8.6x; the shallower spelling of it was 167x off the floor and was not in the case. See [[ranking_blind]]. **BLOCKED:** `acc + x*2 - 1` parses as `(acc + x*2) - 1`, so extracting a transform needs reassociation across the accumulator, and `Int` overflow RAISES — that changes WHICH operation overflows, so those keep the tree |
| ~~string interpolation~~ | **CLOSED on allocation, 1.43x on time** — 15 287 → 10 658 ps/iter, young GC **38 → 17**, which is exactly what `str::concat` costs (9 908 / 17) | The row said "1.8x the concat it desugars to — cause not isolated". Two causes. The size estimate budgeted **16 characters per interpolated slot** regardless of content, so a two-character result asked for seventeen; and the operation receives its values through a `@Variadic Object[]` the DSL allocates every execution. Interpolation needs `Str`, so the value is almost always already a String and the exact length is knowable: now `String.concat` for one literal and one value, an exactly-sized builder when all values are Strings, the old `String.valueOf` slack only when one is not — plus `InterpolateString1`, a fixed-arity operation the emitter picks when there is exactly ONE interpolated expression. `String.concat` and NOT `+`, whose `StringConcatHelper` is on native-image's runtime-compilation blocklist and has broken `mez build --native` twice from PE'd code |
| **`list` walk** | **4.2x** `seq` (1 187 against 201 ps/elem, control 286) — was recorded as 6.2x. A forward fold also COPIED the list first: **1.26x** where that copy is reachable (7 209 → 5 706, converged at two n) | `bench/cases/collfold` and `bench/cases/listwalk`. `ListNatives.foldTwoArg` opened with `list.toArray()` — a full O(n) copy and a second pass over every cell before one element was folded — because two of its four callers need random access (`fold_right` runs backwards, `scan` writes by index). A forward fold with no scan now walks the cells. **The affected row is a list of RECORDS**, and the first measurement of this showed no movement at all because a list of INTS never reaches that function: `FusedLongPipeline(List)` takes it and walks `MezzeLongCons` cells unboxed. What is LEFT is the cons-cell POINTER CHASE inside FLP's own list loop — unboxed reads, one allocation for the whole fold, and still 4.2x, because every cell is a separate object and the walk has no locality. Lever: a chunked backing, which is a representation change |
| **the collection walk ranking** | one element, every collection, ONE control, ONE process: seq **0.7x**, sorted_set 1.6x, deque 3.2x, list 4.2x, **set 16.3x**, **sorted_map 40.5x**, **map (values) 67.5x** | `bench/cases/collfold`, each holding the same n Ints, each fold summing them, checksums identical. ARRAY-BACKED WALKS ARE FAST AND TRIE-BACKED ONES ARE NOT — seq is BELOW a loop with no collection in it because that fold vectorises, while `set` and `map` are hash-array-mapped tries. **THE INSTRUMENT HAD TO REPEAT THE FOLD INSIDE THE TIMED REGION**, and that is the difference between this table and a wrong one: the collection cannot simply grow, because building a sorted map of 2e6 entries takes MINUTES (every ordered operation allocates a comparator closure and calls back into guest code per comparison, so the build is O(n log n) GUEST calls), but one fold of 2e5 elements is ~2ms against a warm-up window near a second. Measured that way the table read seq 1 111 and list 2 900, and the arithmetic CONTROL moved 1.7x between two builds of IDENTICAL source. **`sorted_map` IS NOW CLOSED at 10.4x** — see the row below; it had no `sum` for `FoldToSumRewriter` to aim at, so its fold kept a per-element guest call. **CORRECTION TO AN EARLIER NOTE HERE:** its 43 young collections were NOT the build, they were the FOLD — the count drops to 4 when only the fold changes, because the callback returns a BOXED accumulator per element. That is also why removing `rawEntries()`' per-element `RawEntry` moved the count by nothing and misread as "escape analysis already deleted it": the wrapper is real and is noise beside the call (1.05x). The GC column still includes the build, so it is only the walk's where the build is cheap — but on this row the fold dominated it |
| ~~`sorted_map::fold`~~ | **CLOSED — 10.4x** (9 711 → 932 ps/elem at n=5e5, all three controls within 1%; 21.1x at n=2e5), young GC **107 → 8** | `bench/cases/collfold`. `FoldToSumRewriter` rewrites a summing fold into the collection's own value sum, which removes the per-element GUEST CALL — and a SortedMap had no `sum_values` to be rewritten to. Same gap `nel` had, and the fix is the same: add the sum, and let the existing rule take its target as a parameter so `map` and `sorted_map` share it. **HOW IT WAS FOUND, because the ranking pointed elsewhere.** `sorted_set::fold` is 571 ps and `sorted_map::fold` 9 711 on the SAME sorted array walked the same way, so the folder's ARITY looked like the difference. It is not: a probe summing the values in Java with the callback SKIPPED measures **633 ps/elem**, so 95% was the guest call and 5% the walk; and a probe on `ScalarCall.prepare3` showed that callback was already on the allocation-free scalar path, ruling out argument packing too. The only real difference was that a sorted SET had a `sum` for the pass to find. The rule still refuses a folder that reads the KEY and a non-zero `init`, each pinned by its ANSWER in SortedMapSumValuesTest (6 and 66 and 160, against the 60 a wrong rewrite would give) |
| the OTHER fusion shapes — **and moving them into FLP is NOT the cure** | two filters 6.2x, take_while 6.1x, take-after-filter 6.2x, enumerate 6.1x on `fusegaps` | **MEASURED AND REVERTED.** Built the filter analogue of the extra-map table (ops packed in a PE-constant int, two literals a stage, plus fixed children so the first extra filter can take a RUNTIME rhs — which `fusegaps` kernel 5 needs, since it compares against a captured `n`). Two filters went 521 → **9 544** ps/elem with 89 young collections against 1, because `simdKeepExtraFilters` builds a `VectorMask` per stage per vector iteration and C2 does not scalar-replace them; with SIMD declined it still went 521 → **1 484**, 2.8x WORSE. **The extra-MAP table won 4.14x only because it reached the VECTOR path** — FLP's SCALAR loop is ~292 ps for one stage against the generic loop's ~520 for two, which is not a 5x. So "move this shape into FLP" is a win only when the shape then VECTORISES, and `take_while` is a sequential early exit that cannot. The ceiling check for this was one cheap question — is FLP-scalar faster than the generic loop for two filters? — and I skipped it |
| **`iter::unfold` — the UNIVERSAL constructor** | **~700x a `range` source** for identical arithmetic: 54 193 vs 76 ps/elem at n=1e7 (713x), 72 372 vs 104 at 2e6 (696x). CONVERGED, same checksum both arms | `bench/cases/iterremit`. **THE LARGEST MEASURED GAP ON THIS BRANCH.** `unfold` is the documented entry point for user-defined iteration — trees, cursors, paginated APIs — and the only way a user type becomes an `Iter`. `iter.mz` claims such sources "fuse at JIT time through Truffle PE — same driver loop, same combinator surface"; **measured, they do not**: `__native_iter_unfold` builds an opaque source, `ChainCanonicalizer` refuses Opaque as a fold source, and the chain falls to the lazy walker with a guest `Step` variant allocated per element (GC 77 against 2). **LOCALISED:** with NO stages at all `unfold` still costs **39 597 ps/elem** (gc 69) against 53 895 with map+fold — **73% of it is the source**, before a stage runs; the map stage adds ~14 ns. The mechanism is the per-element allocation the GC count pins: the user step function builds a `Yield { val, next }` variant AND its payload record every step, and the walker destructures them immediately — but across a Java/guest boundary, where Truffle cannot inline the step call and escape-analyse the variant away. **AND THE CEILING SAYS FUSION IS WORTH ONLY 6.5x OF IT.** A kernel needing no compiler change — the same step function with the driver loop written BY HAND in guest code, which is the shape fusion produces — reads **8 372 ps/elem** and allocates AS MUCH AS THE WALKER (83 collections against 77). So the `Yield` variant is NOT elided even with construction and match both in guest code: `VariantMatchFolder` needs them ADJACENT and the construction sits inside `step`, behind a call. The ~700x splits into ~6.5x of walker dispatch overhead (fusion, or a leaner `walkUnfold`) and ~112x of `Step`-protocol allocation that needs the step function INLINED into its consumer — an inlining problem, not a canonicalizer one. Measured before building the source kind, which would have landed 6.5x and left the headline unchanged. **MECHANISM, and it is by design:** `walkUnfold`, `walkElements`, `foldWalk` and `fold` are ALL `@TruffleBoundary`, so the entire non-fused iter walk runs OUTSIDE partial evaluation — no guest callback is inlined, nothing is escape-analysed, and the consumers are Java lambdas capturing `acc[0]` arrays which allocate in their own right. That IS the 6.5x, and the boundary is deliberate (see [[iter_effect]]: Iter across the boundary is what makes effects work in a walk), so fusion is the way out of it rather than deleting the annotation. Everything optimised on this branch was RANGE-sourced and Int — which is the one shape `FusedLongPipeline` accepts, and also the shape `iter.mz`'s own doc says NOT to use `iter` for, since Seq and List have their own fused HOFs. So the fast path and the stated remit barely overlap. The reference row's spread is 3.49x (76 ps × 1e7 is under a millisecond, below warm-up) so its absolute figure is soft — the RATIO is ~700x either way |
| **`iter` over an element FLP cannot read** | **2.8-3.7x** the same fold written without `iter` (23 271 vs 8 305 ps/elem at n=1e7; 24 003 vs 6 436 at 2e6) | `FusedLongPipeline`'s loop reads every element as `((Number) raw).longValue()`, so a record or a string cannot enter it at all and the chain takes the lazy walker. A record-element `map` + `fold` through `iter` costs ~3x `seq::fold` doing the same work directly. Note also that the CONTROL is 8 305 ps against the Int reference's 76 — records are ~100x Ints before `iter` is involved at all, which is [[rec_boxed]] and a separate row. The honest summary: `iter`'s remit is cross-collection pipelines, UNFUSED collections and user types, and on all three it is currently the slow route || the OTHER four shapes off the fast path | **6.2-6.8x** on `fusegaps`: two filters 6.2x, take-after-filter 6.8x, take_while 6.4x, enumerate 6.6x | Same cause and same cure as the map and transform rows above — each is a stage `tryBuildFusedLongPipeline` has no slot for, and each therefore also loses the vector path. Two filters want a second filter slot exactly as maps wanted a second map slot; take-after-filter is an explicit `if (seenFilter) return null`. **`take after filter` read 873 in one single-arm ranking and 574 in the next** — both cross-build, neither an A/B, so this row needs its own before it is believed |
| **`iter::to_seq`** | **1.72x** at n=5e7 (1 240 → 723 ps/elem, GC 10 → 8); **5.85x** at n=2e7 (4 396 → 752, GC 20 → 10). **DOES NOT CONVERGE — 1.72x is the figure.** Still ~9x the 84 ps floor | THE RESULT ARRAY WAS COPIED TWICE. The sink sizes its buffer to the SOURCE (it cannot know how many survive the stages), then did `Arrays.copyOf` down to `count` and handed THAT to `MezzeSeq.fromLongs`, which `clone()`s defensively — three arrays for one result. `LongSeqStorage.length` is already allowed to sit below `data.length` (the spare-capacity append path relies on it), so `ofPrefix` takes the buffer as a PREFIX with a fresh owner token: the slack becomes O(1) append room instead of waste. **WHY THE RATIO MOVES, and it is not noise:** per INVOCATION the new arm is 36ms at 5e7 and 15ms at 2e7 — scaling with n — while head is 62ms and 88ms, costing MORE at the smaller size because it is dominated by the allocation it no longer makes. A row whose cost is O(n) ALLOCATION cannot share an `n` with rows that allocate nothing ([[row_sizing]]); n=1e6 is below warm-up, where the CONTROLS move 1.38-5.13x. What is certain without timing is structural: three arrays became one. **A BUG ALMOST FILED AND WRONG:** `ParallelNatives` takes `n = data.length` after `getArray()`, which would make `parallel::product` return 0 over a padded seq (a sum would be unaffected) — but `getArray()` calls `trimmedData()`, which copies when oversized, so every such caller is already safe. The cost therefore MOVES for a consumer reaching for the raw array, the same trade `appendLong` has always made; `seq::size` here does not. Guards = LongSeqStoragePrefixTest (7, the mechanism) AND MaterializedSeqPrefixTest (5, through the language). Both were needed: making `ofPrefix` ignore its length fails 6 of 7 storage tests while `FusedPipelineElementTypeTest` and `IterableImplIntegrationTest` stay GREEN — nothing asserted the ELEMENTS of a filtered materialise. No assertion is a SUM, which trailing zeros are invisible to. **STILL OPEN on this row:** `iter` has only `to_seq`/`to_list` against SEVEN types implementing `Iterable` into it, so `iter → set`/`map`/`deque`/`sorted_*` still materialises an intermediate seq — the thing [[iter_is_lazy]] says `iter` exists to avoid |
| ~~an `Option` returned by a native~~ | **CLOSED for every keyed read — `map` 1.93x, `seq` 1.9x, `list` 2.15x, `bytes` 2.0x; young GC 72 → 1 at n=6e7** | `VariantMatchFolder` folds a syntactic `MakeVariantIR` and a native's return is not one, so an immediately-destructuring match could not see through it. Now the MATCH emitter recognises the four `__native_*_get` ops and emits a raw read plus a null test — null is available as the presence signal because Mezze has no null value, so no container can hold one, and `bytes::get` boxes its byte into a `Long` so even a byte of zero returns non-null. The machinery already existed and was DEAD: `tryEmitFusedMutGetMatch` gates on `CallDirectIR.isScalarABI()`, and by the time the bytecode compiler runs the stdlib wrapper has been INLINED, so no such call survives to match — keying on the NATIVE's name is what survives inlining. `list` is the one row not purely about allocation: the fused read walks the cons cells ONCE where `ListNatives.get` bounds-checks with `size()` — itself a walk — and then walks again in `get(i)`. **TWO MISTAKES WORTH MORE THAN THE ROW.** (1) The first version took the container as its CANONICAL CLASS, which cost 40 interpreter tests: a native does not receive a container, it receives something `NativeDispatch` COERCES — a seq is a `MezzeSeq` OR a bare `Object[]`, a list has a `Cons`/`Nil` form as well. A fused read narrower than the native it replaces is not an optimisation. Unrecognised representations now delegate to the SAME coercion (`coerceSeq`/`coerceList`/`coerceBytes`), so there is one answer to "what counts as a seq". None of the 40 mention `seq::get`: the site is `find_field_value` in `std::codec::json`, reading a `JObject`'s `fields`, and every targeted test used a seq LITERAL — already wrapped, hence blind. (2) The first numbers (2.06x/2.71x/2.36x, GC 21→3) were measured with `MEZZE_JAVA_OPTS=-Xlog:gc`, and **that variable REPLACES the launcher's whole `DEFAULT_OPTS`** rather than adding to it — silently dropping SerialGC, the CDS archive, `-Xmx4g` and `--add-modules=jdk.incubator.vector`. See the `java -jar` entry: same trap, different door |
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
- **An experimental compiler flag used to POISON the build cache.** `mez build`
  invalidated on sources, missing blobs and stdlib drift, and not on the
  compiler's own configuration — so a build with `MEZZE_MONO_STRIP=1` wrote its
  IR into `.mez` and every later build, without the flag, got a cache hit and ran
  it. `bench/cases/defunc` kernel 6 answered `sum=<function:>` long after the flag
  left the command line. FIXED: `BuildCache` now keys on a digest of the eight
  env flags that change lowered IR, trace-only flags deliberately excluded so a
  diagnostic run keeps its cache. Still: when an A/B needs two builds, `rm -rf
  .mez .build` between them costs nothing and removes a whole class of doubt.
- **Run the LAUNCHER, not `java -jar`.** `mez build` writes
  `.build/<app>/<app>`, which adds `--add-modules=jdk.incubator.vector,…`, Serial
  GC and AppCDS. `java -jar` read 21 ns/px where the launcher read 10, and a fused
  fold over a flat arithmetic body dies outright with `NoClassDefFoundError:
  jdk/incubator/vector/Vector`.


## A probe that changes a format breaks the greps that read it

`MEZZE_DUMP_FN` now prints `hash=<8 hex>` between the name and the params,
because the question "is this the generic or its specialization?" cannot be
answered from a name — two functions in one dump share the name `apply_fold`
and differ only in whether a `__dict_add` is on the end. Finding which ones
are CALLED needs the hash as well, matched against `targetHash=`.

The cost: every saved recount script matching `dump-fn\] [^ ]* params=` now
matches NOTHING, silently, and reads as "zero dictionary-carrying functions".
A grep over this dump must tolerate the hash field.

`MEZZE_DUMP_FN` also dumps a function that `supports()` REJECTED, as
`[dump-fn-rejected]`. The probe lives inside `BytecodeCompiler`, which never
runs for a rejected function — exactly the case where the body is what you
need, since the rejection names a node type and, for a dangling callee, a
hash, and finding which call that is takes the tree.

## The HOF's callback-result var is ONE var for EVERY call site

This is the cause of the two remaining dictionary carriers AND of a crash, and
it is a missing INSTANTIATION rather than a missing improvement rule.

Probing dictionary resolution at the two call sites of one un-annotated HOF —
`apply { n, f, zero }` used once with an Int accumulator and once with a Float
one — prints the callee's `NumAdd` constraint as

```
  typeArgs=[VarType(out, id=1983), Float]   bindings=[]
  typeArgs=[VarType(out, id=1983), Int]     bindings=[]
```

Position 0 is the operand, which here is the CALLBACK'S RESULT TYPE, and it is
`id=1983` at BOTH sites.

**CORRECTION, same session.** I read that as "the callee's constraint vars are
not freshened per call site" and wrote it into a commit. Wrong twice over. The
operand is the callback's RESULT, which is Int at BOTH sites — only the
accumulator differs — so one var serving both is no contradiction. And the
SOLVER does hold per-site vars and does resolve them; tracing the two sites:

```
  solveAssociatedTypeResolution: NumAdd primaryTypeVar='j13#2069 resolved=Int
    matchingImpl: NumAdd {'out = Float}   <- float.mz:10, NumAdd Int for Float
```

the RIGHT heterogeneous impl, chosen correctly, once the tuple fix above is in.

The gap is that LOWERING never sees that answer: the solver resolved var 2069,
lowering reads var 1983. `resolveIdentifierType` builds an emitted node's type
from `constraintResults[name].type` — the declaration's SCHEME — so the operand
is the scheme's own var, shared by every site, and `substitution.apply` has
nothing to say about it. The per-site types the solver worked out are never
carried into the emitted AST. `harvestVarBindings` is not at fault and does
descend into function-typed record fields; there is nothing to bind because the
ARGUMENT's recorded type is `step`'s scheme too.

So the next move is NOT a solver instantiation fix. Either emit carries the
solver's per-site types, or the operand is re-derived where it is needed by
forward-solving the ARGUMENT's own chain against the callee's parameter
(`f: {x: Int} -> 'a` pins `x := Int`, then `NumMul Int Int -> Int` and
`NumAdd Int Int -> Int` give the result). A homogeneous DEFAULT is NOT an
option: it answers Float at the Float-accumulator site where the true operand
is Int, which miscompiles silently.

What that costs, both halves of it:

  - **Lowering** sees only the receiver, so it picks the impl by receiver alone.
    For a homogeneous add that is right by luck; for `Float + Int` it hands
    Float's `impl NumAdd Float for Float` a Long and dies with
    `CallNativeDDD [FloatOps$$Lambda, 0.5, 21]`.
  - **Mono** cannot derive the HOF's type args, so `apply_fold` compiles as
    `[n, f, __dict_add]`. Kernel 3 and kernel 7 of `bench/cases/defunc` differ
    ONLY in whether the callback carries an annotation, and only the annotated
    one monomorphises.

MEASURED AND REVERTED: making the dictionary lookup tuple-aware. The module
already has `resolveOpFromObligation`, which matches a tuple position by
position and whose own doc makes exactly the right critique of receiver-only
lookup. Feeding it the callee's constraint tuple fires at some sites and
changes NOTHING at this one, because the tuple is not concrete. Shipping an
inert path that no test can fail without is worse than not shipping it.

`bench/cases/hetero` records the measurement.

## The call site's own solved types reach dictionary resolution

`resolveIdentifierType` builds an emitted node's type from
`constraintResults[name].type`, the DECLARATION'S SCHEME. So a reference to a
polymorphic function shows the same scheme variables at EVERY call site and the
final substitution says nothing about them, while the solver did instantiate and
resolve them per site:

```
  solveAssociatedTypeResolution: NumAdd primaryTypeVar='j13#2069 resolved=Int
    matchingImpl: NumAdd {'out = Float}      <- impl NumAdd Int for Float
```

the right heterogeneous impl. That answer never reached lowering, which resolved
the dictionary from the only position it could see, the RECEIVER — right by luck
for a homogeneous add, and for a Float accumulator `impl NumAdd Float for Float`
handed the callback's Long: `CallNativeDDD [FloatOps$$Lambda, 0.5, 21]`.

`callFunctionTypes` records the per-site arrow and reaches `TypedASTCompiler` as
`callSiteArrows`, beside the `monoCallSiteRewrites` and `decodeOpBySite` maps
that already work that way. The arrows carry no ability constraints — those live
in the collector's list — so what they are worth is their RESOLVED PARAMETER
TYPES:

```
  scheme    { n: Int, f: { x: Int } -> 'a, zero: 'b }
  per-site  { n: Int, f: { x: Int } -> Int, zero: Float }
  harvested 'a = Int, 'b = Float        -> the tuple [Int, Float] is whole
```

and with a whole tuple `resolveOpFromObligation` — already in that file, already
documenting why receiver-only lookup is wrong — picks the right impl.

### Where I put it first, and what that cost

Stamping the arrow over `fn.type` in `CallEmit` ALSO eliminated the dictionary
parameter, and the IR numbers were real: defunc's carriers went 2 to 1,
`apply_fold`'s `__dict_add` disappeared and it gained two more dict-free
specializations. It cost seven interpreter tests, every one of them the
receiver-less dictionary family:

```
  no dictionary was supplied for ability operation `default` … for a
  receiver-less operation that means resolving it from the type the call PRODUCES
```

because that type is read by far more than dictionary resolution, and replacing
a scheme with a concrete arrow changes who is judged to need a dictionary at
all. So the shipped change is a side table that alters one decision instead of
everyone's view — and the dictionary PARAMETER therefore stays. What is in it is
now the right operation. Removing it is mono's job, and mono reads that same
node type, so it waits until mono can be handed the answer another way.

### Three claims I made here and had to withdraw

1. "The constraint vars are not freshened per call site." They are. The solver
   holds per-site vars and resolves them; EMIT does not carry them.
2. "Kernel 3 went 10 131 to 1 488 ps/iter — 6.8x, the annotation gap is closed."
   A fresh min-of-8 against a figure measured under other conditions. The real
   A/B for the stamped version, min of 6 interleaved: 1 391 against 1 483, with
   the floor moving the same 6% and kernel 7 moving 6% the OTHER way. Noise. The
   annotation gap was already closed by the earlier call-site-specialization fix.
   The SHIPPED version does not touch the hot path at all.
3. "defunc carriers 2 to 1." True of the stamp, which did not ship.

### Neither gated suite can see this

- `:interpreter:test` goes through `MezzeTestHelper`, the INTERPRETER path, which
  ran every one of these programs correctly WITH THE BUG IN. Two runtime-value
  tests were written — one with literal inputs, one seeding from an effect to
  defeat comptime folding — and neither moved.
- `:typechecker:test` can reach `loweredFunctions`, but that is not the BUILD's
  lowering: mono is seeded differently there and `apply` keeps its `__dict_add`
  with the fix in place. A test written against it failed in BOTH directions and
  was deleted rather than weakened.

So the guard is `scripts/hof-dict-e2e.sh`, in the style of
`scripts/aot-parity-e2e.sh` and for the same reason. It asserts the values and
says in its own text what it does not assert. Measured to discriminate: without
the fix `hofpoly` dies with the exception above.

## Full mono: LANDED — kernel 3 is 1.78x and now beats the annotated version

Wiring mono to the same per-site arrow the dictionary path now uses —
`MonoDriver.run(callSiteArrows = …)`, preferred over `fn.type` in
`deriveTypeArgs`, because the arrow differs from it ONLY where `fn.type` is
still open — does produce what full mono is supposed to:

```
  hofpoly   apply -> TWO dict-free specs [n, f, zero]; __dict_add gone
  defunc    apply_fold's dict gone, 4 dict-free [n, f] specs
            dictionary carriers 2 -> 1 (only step_alt, never called directly)
```

and the values are right everywhere, including the heterogeneous accumulator.

**It costs nothing at compile time.** Measured interleaved, with `mez check` as
a built-in control: check runs `checkParsedModule` and never lowers or runs
mono, so whatever spread IT shows is the noise floor.

| | mono on | mono off |
|---|---|---|
| defunc `check` | 1754 | 1782 |
| defunc `build` | 5804 | 6139 |
| mandelannot `check` | 1719 | 1789 |
| mandelannot `build` | 5726 | 5725 |
| functions compiled (defunc) | 54 | 53 |
| jar | identical | identical |

Recording the arrow is free too, isolated with its own toggle: ≤1.1% on medians
across three benches with the sign flipping. The stdlib precompile is below
resolution — two runs disagreed in SIGN (+9.1% then −2.1% on min) against a 21%
within-config spread, so no number is quoted.

Structurally the cost is one map insert per call expression, one `putAll` per
merge (the same pattern `callReturnTypes` already pays, and the only part that
could go superlinear on a deep tree), and one `substitution.apply` plus a
`holdsTypeVar` walk per recorded arrow per module.

### MEASURED, interleaved, with the inline floor as the control

```
  kernel                      FULL       OFF    delta
  1 inline (CONTROL)           785       798    -1.6%
  2 named fn, direct           755       758    -0.4%
  3 own HOF, un-annotated      913      1628   -43.9%
  7 own HOF, ANNOTATED        1434      1346    +6.5%
```

Kernel 1 cannot be touched by this change and moved 1.6%, so the noise floor is
about 2% and kernel 3's **1.78x** is far outside it. At 913 ps kernel 3 now
BEATS kernel 7 — the identical callback with its type written down — and sits
1.16x off the inline floor where it used to sit 2.07x. The annotation gap is
closed in the direction that matters. Checksums identical, carriers 2 to 1.

Kernel 7 reads 6.5% slower, three times the floor: small, real enough to write
down, not explained.

AND THE FIRST VERSION OF THIS MEASUREMENT WAS GARBAGE. It took `head -1` of the
ps/iter lines, which is the COLD reading — the bench prints each kernel twice for
exactly that reason — and reported the control kernel moving 9.4% and kernel 2
moving 141%. The control is what caught it.

### The four failures it took to get here, all caused by the mono consumer

Isolated by disabling just that one argument, after which all four passed.

**1. FIXED, and it was mine: the per-site arrow loses NOMINAL IDENTITY.** Three
shapes, one error class:

```
  Field not found: speak           DualAbilityParamTest     (two Speak constraints, one per param)
  Field not found: conv            AliasImplTargetTest
  Field not found: into_response   HttpIntoResponseTest
```

I had written that the arrow "differs from `fn.type` only where `fn.type` is
still open". It also differs in IDENTITY: a record literal reaching a generic
parameter is EXPANDED and anonymous in the arrow, where `fn.type` carries the
nominal `Dog`/`Cat`. `resolveAbilityConstraints` matches an impl target by
nominal identity, so preferring the arrow wholesale dropped the impl ref — and a
dropped ref is exactly what that function's own comment warns about: "the
`x.total{}` method call then falls back to a raw field read → runtime 'Field not
found'".

The fix is a PER-POSITION merge, not a preference: `fn.type` decides every
position it already knows and the arrow fills only the ones it left open. On
`both = { a, b } -> "$(a.speak {}) | $(b.speak {})"` at (Dog, Cat) the first
constraint resolved and inlined to "woof" while the second degraded to a field
read, which is what two same-named constraints at different types look like
when one side of the merge is missing.

**2. FIXED: a receiver-less op has no dict slot inside a spec**, so
`abilityOpDictSlot` THREW during LOWERING on a program that type-checked:

```
  'mk7' takes no receiver, so the type it produces is what chooses the implementation
```

Resolved from the spec's own `specTypeArgs`, ability-filtered, else from the
produced type when that type IS the class type. Better code than the slot was,
too: a static reference instead of a dictionary read. **DO NOT search the produced type's nested parts** —
that picks whichever impl claims any part first and the order is incidental; it
produced the same `Field not found` errors in another form. With the narrow
version all seven of `ReceiverlessOpFunctionReturnTest` pass, including "an
unpinned receiver-less op is still refused", so an honest refusal stays one.

The class type can be NESTED (`mk7: {} -> ({} -> 'a)` at `'a = P` produces
`({} -> P)`, which no impl targets), and comparisons must go through
`resolveOpHashFromConcreteTypes` rather than structural equality, because the
impl targets nominal `P` while the produced type carries the expanded
`{ x: Int }`.

**3. RETARGETED, on evidence: a fold reached as a VALUE.**
`ListFoldElementKindFusionTest` wants `IsLongBacked?FusedLongPipeline:FusedLoop`
and full mono emits `[FusedLoop, FusedLoop, FusedLoop]`. The specs ARE dict-free
(checked through `loweredFunctions`), but the fold spec keeps `f` as a
PARAMETER, so the fuser cannot prove the element is LONG. The BUILT artifact is
unaffected — that whole program inlines to 8 functions with no fold left — so
this is the interpreter path.

The test's premise was "reached UNSPECIALISED", and full mono specialises it, so
there is no generic body left to guard. What the spec holds instead, read off
`loweredFunctions`, is the whole chain inlined into `apply`'s spec —
`FusedChainIR(RawList(xs), sink=Fold(init=LongLitIR(10), f=MakeLambdaIR(…)))`,
a concrete folder and a LONG init, strictly more specialised than the indirect
fold it replaced. Measured on the shipping path, interleaved, folding 2e6
elements through that exact shape: **1 034 ms against 1 039 ms, identical
checksums, 10 compiled functions against 15.** Neutral on time, smaller.

So the test now asserts the value and that the chain still fuses, with the
measurement and the open item written into it: the fuser does not prove the
ELEMENT kind is LONG for a fold inlined into a spec, so it emits the generic
loop rather than `FusedLongPipeline`; the direct-call sibling test still covers
that. The interpreter path could not be A/B'd at all — `mez dev` does not exit —
so no claim is made about it.

Two hypotheses died on the way: closure splicing is NOT off (the comment above
`closuresEnabled` is stale; the code is `getenv(…) != "0"`, i.e. on), and the
specs are NOT dict-carrying.

### Method notes from this attempt

- A `--tests` filter with the WRONG PACKAGE matches nothing and the task still
  reports BUILD SUCCESSFUL, which read as "fixed". Three of these classes live
  in `regression/`, `stdlib/` and `bytecode/`; I claimed 3 of 4 fixed off a run
  that had executed one of them.
- `mez dev` does not exit, so it cannot be timed in a loop. Four interleaved
  reps at 600s each produced nothing before being killed.
- A benchmark that prints each kernel twice prints the COLD one first. Taking
  `head -1` is how the control kernel came out 9.4% apart.
- Block-wise cost runs gave a 1.7x "difference" on `mez check`, which the mono
  consumer cannot affect at all. Interleave, and keep a quantity in the
  measurement that the change provably cannot touch.

## I picked a target off a warmup measurement, and the retraction is the finding

Asked for the next big item I surveyed `bench/cases/ds_matrix`, the Phase G0
baseline, and picked seq's `map`/`filter`: 61-151 ns/elem against a 5-6 ns/elem
fold floor, with List doing the same staged job 2.6-5.5x faster — a same-regime
comparison, which is what the rules ask for.

**All of it was JIT warmup.** The same operations, measured at n=2e6 with the
minimum of 12 timed passes, in PICOseconds per element:

```
  seq count         16 ps/elem        seq filter+size   269
  seq map+size      32                seq sum           327
  seq map+fold     253                seq fold          328
```

0.25-0.33 ns/elem is roughly a `long[]` read plus an add on this machine, and
`count`/`map+size` are faster still because length is invariant under `map`, so
they fold to O(1). There is no 10-20x gap in the seq pipeline. The ds_matrix
figures were inflated about 500x.

What makes this worse than a wrong guess: **ds_matrix's own header says the
default is under-warmed** — "At 10 000 the median runs 2-10x the min, which is
what an under-warmed measurement looks like" — and I ran the default anyway. The
medians in my own first reading were 4x the minima, which is the same signal.

### The instrument is the blocker, not any one operation

Raising ds_matrix to N=200 000 does NOT fix it: medians stay 2-3x the minima, so
it is still short of steady state, and it then disagrees with the 2e6 harness by
**560x on the same operation** (`seq::count` at 8.94 ns/elem against 0.016). At
least one of the two is measuring something other than the operation — a
candidate is the double-indirect `thunk {}` through `report` -> `time_k_go`,
against one level in the other harness.

So there is currently NO trustworthy per-element instrument for the collection
surface, and "the largest measured gap" cannot be answered from one that reports
500x off. That instrument is the next piece of work: one harness, big N, min of
many passes, reporting ps/elem so sub-nanosecond costs are visible at all, and
agreeing with a second implementation before any target is picked off it.

Three stale numbers this corrected along the way, all from memory rather than
measurement:
  - `list::map`/`filter` "100-644x slower than seq" — they are 2.6-5.5x FASTER
    at N=10k and both are sub-nanosecond in steady state.
  - "20M Int tail loop ~21 ns/iter" — defunc kernel 1 is a tail loop at 785
    ps/iter.
  - seq being the fast fused structure and List the slow one — at N=10k List
    wins map/filter, and in steady state the distinction vanishes.

### Annotations, separately: nothing left to gain

Both dedicated A/B benches now say an annotation buys nothing or costs a little.
`bench/cases/mandelannot`, min of 6 interleaved: un-annotated 309 ns/px against
annotated 343, identical checksums. `bench/cases/defunc` after full mono:
kernel 3 (inferred) 913 ps/iter against kernel 7 (annotated) 1 434.

### LEDGER UPDATE 2026-11-06 (later) — mono closes, and one row is the instrument itself

| row | was | now |
|---|---|---|
| a polymorphic fn passed as a VALUE to a HOF | ABOVE FLOOR, 13x | **AT FLOOR, 1.16x.** 913 ps/iter against the inline floor's 785, and it now BEATS the ANNOTATED version (1 434). Interleaved, min across 12 samples per cell, with kernel 1 as a control that moved 1.6% |
| HOF call-site seeding | PARTIAL — only an argument whose type the signature declares | **CLOSED.** `MonoDriver` reads the per-site arrows, so the callback's RESULT type no longer blocks derivation. defunc's dictionary carriers 2 → 1 |
| the last dictionary carrier | 2 functions, verdict "neither is a defect" | **1 function, and the old verdict was wrong.** `apply_fold` WAS called by `main` and twice by `k_hof2`; `step_alt` is the sound one — never called directly, so no site exists to specialise from |
| heterogeneous `+` reached through a dictionary | not a row | **CORRECT, was WRONG ANSWER territory.** Lowering picked the impl by RECEIVER alone, so `Float + Int` ran Float's homogeneous `add` on a Long and died in `CallNativeDDD`. Right by luck whenever the operands agreed |
| a recursive local lambda capturing a dictionary | not a row | **FIXED.** Its hash was predicted one way and emitted another, so it called itself at a target that resolved nowhere — a well-typed program that would not run |
| **the seq fused pipeline** (`map`/`filter`/`fold`/`count`/`sum`) | implicitly a target, "61-151 ns/elem" | **ABOVE FLOOR, 2-6x — and NOT the 10-20x I picked.** `count` 16 ps/elem and `map+size` 32 fold to O(1) (`map` preserves length). The rest, swept over array sizes so cache cannot explain it — 390KB / 1.5MB / 7.8MB / 31MB:<br>`fold` 491 / 249 / 393 / 325 · `sum` 397 / 360 / 374 / 337 · `filter+size` 194 / 186 / 193 / 436.<br>**FLAT in N**, so it is compute, not bandwidth (16MB at 327 ps/elem would have been ~24 GB/s and looked bandwidth-bound; the 390KB case settles it). Against the declared ~85 ps/elem floor that is 2-6x. **The ordering names the cause**: `filter+size` is CHEAPER than `sum` and `fold` while doing more work, because the SIMD gate admits SUM/COUNT/REDUCE — a count sink vectorises, `seq::sum` is routed to `BuiltinSumLongSeq` OUTSIDE the FLP, and a `+`-folder fold is an `Accumulate` sink the gate excludes. A sum IS `fold(+, 0)`, so that exclusion is artificial |
| **the per-element instrument** | not a row | **ABOVE FLOOR, cause named.** `ds_matrix` at its default N=10 000 measures JIT WARMUP: its figures are ~500x the steady-state cost. At N=200 000 the medians are still 2-3x the minima, and it then disagrees with a 2e6 harness by **560x on the same operation** (`seq::count` 8.94 ns/elem against 0.016). A candidate is the double-indirect `thunk {}` through `report` → `time_k_go`. This row BLOCKS the ranking: "largest measured gap" is unanswerable for the collection surface until one harness is trusted |
| an annotation buying anything | the open question behind three benches | **CLOSED, no gain anywhere measurable.** `mandelannot` 309 ns/px inferred against 343 annotated, min of 6 interleaved, identical checksums; `defunc` 913 against 1 434. Inference leaves nothing on the table |

**The symmetry worth keeping.** This ledger exists because "the 1503x `size` win
was sitting in an EXISTING bench that nobody had run at a realistic N". Today I
ran an existing bench at an UNREALISTIC N and picked a 10-20x target that did
not exist. The same instrument blindness in both directions, and in both cases
the bench's own numbers said so — a median 2-10x its own minimum is the tell,
and ds_matrix's header spells it out.

### The next target, derived this time instead of guessed

`seq::sum` and `seq::fold(+, 0)` over a long-backed seq **never reach the
vectorised pipeline at all.** Probing the SIMD gate directly:

```
  k=4 filter+size  ->  [simd] gate fromArray=true avail=true n=200000 thr=32 sink=1
                       [simd] TAKEN                                   ~190 ps/elem
  k=1 fold         ->  (the gate is never reached)                    ~250-490
  k=6 sum          ->  (the gate is never reached)                    ~340-400
```

`BytecodeCompiler` routes `rawXsExpr != null && isSumSink && !hasStages &&
longZeroInit` to `BuiltinSumLongSeq`, i.e. `SeqSumLongScalarNode` — the name is
accurate, it is a scalar counted loop — so the sum bypasses
`FusedLongPipeline` and with it `FlpSimd`. The COUNT sink, which does reach the
pipeline, vectorises and comes out ~2x faster **while doing strictly more work**
(it carries a filter).

So an optimisation has become a pessimisation, and the node says why it was
written that way: "the scalar loop wins on SVM/AOT because native-image has no
LongVector intrinsic". That reason is still valid for AOT and NOT for the JVM,
which is where these numbers were taken — and `FlpSimd.AVAILABLE` already gates
itself off on SVM, so the two-path shape is established.

A vectorised peer already exists and is not on this path:
`SeqReduceLongSIMDNode`, specialised by size with a `LongVector` body at n >= 64.

**The work**: route sum / `fold(+)` over long storage to the vector path when it
is available, keep the scalar loop on SVM, and hold both with the AOT parity
script as well as the two suites — a change that is fast on the JVM and slower
on native would be a regression this ledger's own floors would not catch,
because they are measured on the jar.

**TRIED, MEASURED, REVERTED — and the measurement is the point.** Routing the
scalar node to the existing vector peer behind a `FlpSimd`-style probe works and
is never slower. Interleaved in ONE binary, 8 reps, min per cell:

```
  op                          SIMD   SCALAR   change
  seq sum                      122      133    1.09x
  seq fold (+)                 117      132    1.13x
  seq filter+size (CONTROL)    156      131    0.84x   <- the control MOVED 16%
```

The control is an op this change cannot touch, and it moved 16% in the WRONG
direction, which swamps the 1.09-1.13x. So the gain is not measurable here, and
an unmeasurable change that adds a SIMD path plus a crash-surface guard is not
worth shipping. The node's own reasoning survives intact: on this machine the
scalar loop is already within ~10% of the vector path.

**And this retracts the "2-6x above floor" number above.** Those readings came
from comparing across BUILDS. Within one binary everything in this family sits
at 117-164 ps/elem, i.e. ~1.4-1.9x the declared ~85 ps/elem floor, not 2-6x.
The wiring gap is still real and still worth recording — `mapToSimdOpLong` has
no callers, so #417's "a fold and a sum run the exact same vectorized code" is
not what happens — but closing it buys ~1.1x, not 2x.

**The rule this day earned.** Every number I produced today by comparing across
builds or session windows was wrong: the 10-20x seq target, the 2-6x floor gap,
"sum 360 -> 137". The only ones that held were interleaved, same-binary, with a
quantity the change cannot affect carried alongside. A ledger floor measured any
other way is a guess with a number on it.

## The instrument, fixed — and it names List, which reverses my own correction

`ds_matrix` now reports every row as **ps/elem plus a ratio to a control
measured in that same process** — a counted loop over the same arithmetic with
no collection in it — and it flags its own bad measurements: `UNDER-WARMED` on a
row whose median exceeds twice its minimum, and a louder line when the CONTROL
itself is unstable, in which case no ratio below it means anything. Ratios are
what survive between two builds on a drifting machine; absolute nanoseconds are
what produced every wrong number in this session.

`scripts/ds-matrix.sh` runs it ONE ROW PER PROCESS, which is not tidiness. All
twelve rows in one process build a 1M cons list and a 1M seq and then time
against them, so the collector runs while the timer does. Same binary, same N:

```
  seq::filter+size   4 852 ps/elem all-in-one   ->   718  one-per-process
  seq::count         4 465                      ->   781
```

a 6x measurement artefact, larger than most gaps anyone would chase. In
one-per-process mode no row flags at all.

### What it says, N=1e6

```
  range.fold direct      136 ps/elem   x0.49 floor
  seq::sum               347           x1.37
  seq::fold              387           x0.92
  seq::map+size          390           x1.56
  seq::any (miss)        486           x1.64
  seq::filter+size       718           x2.59
  seq::count             781           x2.84
  list::count          1 819           x4.05
  list::fold_left      1 511           x5.01
  list::any            1 665           x5.26
  list::map+size       1 667           x5.31
  list::filter+size    1 963           x6.32
```

**LIST is the gap**: 4-6.3x the floor against seq's 0.9-2.8x, and
`list::fold_left` 1 511 against `seq::fold` 387 is **3.9x on the same
operation** — same N, same regime, one structure against another.

### And that reverses a correction I made four hours ago

This file's header claimed `list::map`/`filter` were "100-644x slower than seq
counterparts". From the N=10 000 numbers I wrote that down as stale and
"corrected" it to *List is FASTER*. With an instrument that works, List is
2-5x SLOWER. The header's DIRECTION was right all along; only its magnitude was
wrong. I replaced a stale number with a wrong one, which is worse, and did it
while explicitly complaining about stale numbers.

So the next target is List's per-element cost, and the instrument that picks it
is now the one being committed rather than the one that produced 500x phantoms.

## List's 4-6x is the cons cell, and the obvious fix is not a fix

Two attempts on the List rows, both reverted, and together they say where the
cost actually lives.

**1. `list::fold_left` does not pay for the copy it looks like it pays for.**
`ListNatives.foldTwoArg` opens with `Object[] elements = list.toArray()` — an
O(n) allocation that walks every cell before the fold begins — and the stdlib
doc advertises it ("delegates to native O(n) toArray() loop"). The fused
pipeline's own `doList` records measuring that copy at ~12ms against ~6ms for
walking the cells on 1M elements. So: walk the cells, keep the array only for
`fold_right` and a non-zero `startIndex`, which are the shapes that need random
access.

Measured on the matrix: `list::fold_left` 1511 -> 1512 ps/elem. NOTHING. Because
an Int list's fold FUSES, and the native is only the chain's fallback — so the
hot path never reaches the code I changed.

**2. On the path that does reach it, the copy WINS.** The fallback runs when the
long pipeline's guard fails, so a FLOAT list (MezzeDoubleCons cells) routes to
the native for real. Interleaved in one binary, four pairs:

```
  walk  2336  2485  2293  2522   min 2293 ps/elem
  copy  2680  2058  2182  2439   min 2058
```

The copy is not slower. In hindsight it is obvious: `toArray()` pays ONE
pointer-chasing pass and the fold then scans contiguous memory, while walking
cells chases pointers throughout the fold — and with an indirect call per
element dominating, the allocation is noise. The original code was right and its
comment ("avoids recursive dispatch overhead") was describing a real effect.

**So the 3.9x is the cons cell itself.** `list::fold_left` 1512 against
`seq::fold` 387 is pointer-chasing against a contiguous `long[]` scan, and it
shows up equally in the FUSED walk and the native. No amount of tidying either
one closes it. The lever is the storage: chunked backing (#483), which makes the
walk mostly sequential. That is the Phase G ticket this matrix was built to
gate, and it is now measured rather than assumed.

Worth saying plainly: removing an O(n) allocation is the kind of change that
looks unarguable on paper, and it was the second today whose sign measurement
reversed. An algorithmic improvement is still a hypothesis until a control says
otherwise.

### LEDGER UPDATE 2026-11-06 (later still) — the two UNKNOWN rows, measured: strings and pattern matching

These were the rows this ledger says hide wins — "UNKNOWN — never measured. A
row, not an absence." Before today the string row was ONE operation
(`str::size`) and pattern matching had no bench at all, so neither could be
ranked against anything. `bench/cases/surface` fills both, and
`scripts/surface.sh` runs it one row per process at two sizes each.

**Every number below converged.** Each row was measured at its own n and again
at 4x n, and the pairs agree within 5%; no row flagged `UNDER-WARMED`. The n is
per-row on purpose — these rows span 416 ps to 310 000 ps per operation, a 750x
spread, and what decides whether a reading is warm is the WORK PER PASS, n x
cost. At n=200 000 `str::size` reads 1 749 ps/op against 416 converged, 4.2x of
pure warmup, while `str::split` at that same n reads 314 391 against 312 909 —
already converged, because one op does 750x more work. No single n suits both.
The control keeps its own n (4e8) for every row; both sides are per-op costs, so
they need no common n.

Control = a counted loop plus an add, in the same process, ~214 ps/op.

#### Strings — a 64-char Latin1 string, 12 space-separated words

| row | ps/op | x control |
|---|---|---|
| `str::size` | 416 | 2.0x |
| `str::contains` (miss, so a full scan) | 5 060 | 24x |
| `str` equality (differ in the LAST char) | 5 700 | 27x |
| `int::to_str` | 8 150 | 39x |
| `str::concat` (65 chars out) | 10 065 | 48x |
| `int::to_str` + `concat` | 16 805 | 80x |
| **`str::to_upper`** | **54 940** | **262x** |
| **`str::split`** (12 parts) | **310 260** | **1 480x** |

`int::to_str + concat` lands within 4% of the sum of its two parts, which is the
internal consistency check on the harness.

**`str::split` compiles a regular expression on every call, and the cause is a
correctness fix.** `NativeDispatch.java:540`:

```java
String[] parts = s.split(java.util.regex.Pattern.quote(delimiter));
```

`Pattern.quote` is there so a delimiter like `.` or `|` splits literally, which
is right. But `Pattern.quote(" ")` is `\Q \E` — five characters — and
`String.split`'s fast path only fires for a single non-meta character (or a
two-char escape). So the quoting defeats the fast path and every call falls
through to `Pattern.compile`. 310 ns to cut a 64-char string into 12 pieces is
26 ns per piece, against 10 ns to allocate a 65-char string. The fix keeps the
literal semantics and drops the regex entirely: split on `indexOf`, which is
correct for every delimiter including the metacharacters `Pattern.quote` was
added for.

**`str::to_upper` is `String.toUpperCase()` with the default locale**
(`MezzeBytecodeRootNode.java:463`, `PrimitiveNatives.java:363`) — a char-at-a-
time conversion, 858 ps/char. `str::concat` allocates and copies the same 64
bytes at 154 ps/char. So upper-casing costs 5.6x what copying the same bytes
costs, for a transformation that on Latin1 ASCII is a byte-range compare and a
constant subtract.

#### Pattern matching — four rows that differ by ONE thing each

`Shape = | Circle { r: Int }, Square { side: Int }, Tri { b: Int, h: Int }, |`.
All four rows run the same inline 3-way selector, so a subtraction between any
two of them is the one thing that differs.

| row | ps/op | the difference it names |
|---|---|---|
| 3-way selector + accumulate (BASELINE) | 1 152 | — |
| record alloc, 1 field | 4 158 | **record allocation = 3 006** |
| variant alloc, never matched | 4 753 | **variant allocation = 3 601** |
| variant match, never allocated | 3 446 | **match = 2 294** |
| variant build + match | 8 480 | the sum of the parts would be 7 047 |
| build + match with a guard on arm 1 | 9 358 | **guard = 878** |
| `if let` on `Option` (half the iterations are `None`) | 4 612 | — |

**The first version of these rows was wrong and the correction is the method.**
The baseline did ONE `% 3` while the match row did TWO, and some rows called a
`mk {}` helper while others inlined the construction. So the "match cost" I
subtracted carried a whole extra integer modulo plus a call: it read 2 677 ps
and it is 2 294. The selector is now written out inline in all four rows rather
than factored into a helper, because a shared helper is what let them drift.

**The variant REPRESENTATION is not the cost, and I expected it to be.**
`MezzeVariant` carries ten instance fields whatever its shape — `registry`,
`tag`, `payloadType`, `payload` (`Object[]`), `longValue`, `doubleValue`,
`singleValue`, `payloadLength`, `fusedFieldName`, and a volatile
`materializedRecord` — so `Circle { r = 5 }` allocates about 64 bytes to carry
12, and the obvious lever is shape-specific subclasses. The record control says
no: a 1-field record costs 3 006 ps against the variant's 3 601. Twice the
object for 20% more time, so the cost is PER-ALLOCATION, not per-byte, and
halving the object would buy ~0.6 ns of 3.6. The match side is equally not at
fault — the lowered IR is already a tag switch with an unboxed binding,
`Variant(tag=701133322, tagName=Circle, fieldNames=[r], fieldSlots=[7])` with
`BindingIR(slot=7, kind=LONG)`, so there is no name comparison and no boxing to
remove.

**ABOVE FLOOR, and here is the named cause: a variant that CANNOT escape still
pays for its allocation.** In the build+match row `v` is constructed, matched,
and dead before the next iteration — it never leaves the loop body, which is
exactly the shape partial escape analysis exists to eliminate. If it were
eliminated, build+match would cost about what match-alone costs, 3 446. It costs
8 480, which is alloc (3 601) plus match (2 294) plus the baseline plus 20%.
The allocation is paid in full.

That is the single most load-bearing number here, because it is not about
variants. `Option` and `Result` ARE variants, every fallible call in the
language returns one, and essentially all of them are matched immediately and
discarded. A 3.6 ns tax on a value whose lifetime is three instructions is paid
on every one.

#### What this reshuffles

| row | was | now |
|---|---|---|
| strings | **UNKNOWN** — one operation measured | **ABOVE FLOOR, two causes named.** `str::split` at 1 480x control compiles a regex per call because `Pattern.quote` defeats `String.split`'s fast path; `str::to_upper` at 262x converts char-at-a-time, 5.6x the cost of copying the same bytes |
| pattern matching | **UNKNOWN** — no bench existed | **ABOVE FLOOR, cause named.** Not the representation and not the match: allocation is not eliminated for a variant that cannot escape. match 2.3 ns, allocate 3.6 ns, and the 3.6 is paid even when the value is dead on the next line |
| the variant layout | not a row | **CLOSED as a target.** 64 bytes for 12 of payload is real, and worth ~0.6 ns of 3.6 — a 1-field record costs 3.0. Reached by a different route than predicted: the cost is per-allocation |

#### The next target, derived

`str::split`. It is the largest ratio on the board at 1 480x the control, the
cause is identified rather than guessed, and the fix removes a regex compile
from a function whose contract is a literal delimiter — so it is faster AND more
honest about what it does. `indexOf`-based splitting is correct for every
delimiter, including the metacharacters `Pattern.quote` was introduced to
protect.

Then the escape-analysis row, which is bigger in aggregate and much harder: it
needs to be established WHY a non-escaping `MezzeVariant` is not scalar-replaced
(the volatile `materializedRecord` field and any match-side read that could
materialize a record are the first two things to rule out).

### LEDGER UPDATE 2026-11-06 — `str::split` lands 1.88x, and the board now says one word

The target the previous entry derived, taken and measured. `str::split` on a
64-char string with 12 parts: **313 154 -> 166 789 ps/op, 1.88x**, and as a
ratio to the in-process control **1 480x -> 798x**.

**The fourteen untouched rows are the proof it is real.** Same script, same
machine, every row at its own converged n, run before and after. Ratios to the
in-process control:

```
  row                        before    after    moved
  str::size                     195      199    +2.0%
  str::concat                 4 833    4 819    -0.3%
  str::contains               2 385    2 394    +0.4%
  str::to_upper              26 068   26 144    +0.3%
  int::to_str + concat        8 000    7 834    -2.0%
  int::to_str                 3 759    3 823    +1.7%
  str equality                2 743    2 717    -1.0%
  choose-ctor arith only        545      558    +2.4%
  variant build+match         4 088    4 088     0.0%
  build+match w/ guard        4 454    4 474    +0.4%
  if-let on Option            2 210    2 316    +4.8%
  variant alloc, no match     2 247    2 315    +3.0%
  variant match, no alloc     1 663    1 637    -1.6%
  record alloc, 1 field       1 969    1 943    -1.3%
  ---------------------------------------------------
  str::split                150 554   79 803    1.89x
```

Fourteen rows inside ±5%, one row at 1.89x. This is the shape of evidence the
SIMD attempt did not have, where the control moved 16% in the wrong direction
and swamped a claimed 1.09-1.13x.

**The change.** `s.split(Pattern.quote(delimiter))` became a literal `indexOf`
walk. The quoting was correct and had to stay correct — `std::fs` splits paths
on `"."`, which as a regex matches every character — but `Pattern.quote(" ")` is
`\Q \E`, five characters, and `String.split`'s fast path only fires for a single
non-meta character or a two-char escape. So the quoting defeated the fast path
and every call compiled a `Pattern`. `indexOf` is literal without escaping, so
the fix is faster AND a more direct statement of the contract.

Observable behaviour is unchanged, and three parts of it are not obvious:
trailing empty parts are dropped (`",,"` is ZERO parts), a leading one is kept,
and an empty input is one empty part rather than none. All thirteen cases are
pinned by `StringSplitSemanticsTest`, whose expectations were read off the OLD
implementation before it was touched rather than from the documentation.

**That test passes before AND after, deliberately, and that is the right shape
here.** The standing rule is that a test must fail without its fix; this change
is a performance change whose whole point is that nothing observable moves, so a
test that failed beforehand would mean the change broke something. What was
verified instead is that the test is not vacuous — the implementation was broken
two ways and the suite caught each:

```
  trailing-empty removal disabled -> 2 FAILED  (the ",," and "a,b," cases)
  delimiter no longer literal     -> 1 FAILED  (the "." case)
```

**A second implementation was deleted, not kept.** `NativeHelpers.stringSplit`
(Kotlin) had no callers and did not even agree with the live one: Kotlin's
`split` KEEPS trailing empty strings, so the two natives behind one stdlib
function answered differently. Gone.

Gate: `:interpreter:test` 7 493 tests, 0 failures (re-executed, 9m55s);
`:typechecker:test` 4 826, 0 failures (UP-TO-DATE — no typechecker input
changed).

#### Where the remaining 167 ns goes, and why I am not chasing it next

Splitting 64 chars into 12 parts now costs 14 ns a part. Accounting for it:
12 `substring` calls allocate **24 objects** — a `String` plus its `byte[]`
each — and the measured cost of a small allocation on this board is ~3 ns, so
the allocations alone are ~70 ns. Add the `ArrayList` growth and the copy into
the final `Object[]` and ~167 ns is roughly the allocation floor for returning
twelve separate strings. Removing the `ArrayList` double-copy is maybe 9% of
one row, estimated not measured, and is recorded here rather than done.

#### The board, read as a whole, now says one word

| row | ps/op | what dominates it |
|---|---|---|
| `str::split` (12 parts) | 166 789 | 24 allocations |
| `str::to_upper` | 55 165 | char-at-a-time, then 2 allocations |
| `int::to_str` + `concat` | 16 688 | 4 allocations |
| `str::concat` | 10 072 | 2 allocations (String + byte[]) + copy |
| `variant build + match` | 8 544 | **1 allocation**, not eliminated |
| `int::to_str` | 7 992 | 2 allocations |
| `variant alloc, no match` | 4 793 | 1 allocation |
| `record alloc, 1 field` | 4 081 | 1 allocation |
| `variant match, no alloc` | 3 471 | no allocation |
| `choose-ctor arith only` | 1 168 | no allocation |
| `str::size` | 418 | no allocation |

Sorted by cost, this table sorts by ALLOCATION COUNT. The three rows that
allocate nothing are the three cheapest; every expensive row is expensive in
proportion to how many objects it makes. A small allocation costs ~3 ns here
whether it is 32 bytes or 64 — twice the object for 20% more time — so the cost
is per-allocation, not per-byte, and it is not something a better layout fixes.

**NEXT TARGET, and it is the same one from two independent directions:
allocation.** The sharpest statement of it is still the pattern-matching row,
because there the allocation is provably unnecessary — a variant constructed,
matched, and dead before the next iteration is exactly what partial escape
analysis exists to remove, and it is not removed: build+match costs 8 544
against match-alone's 3 471. `Option` and `Result` ARE variants and nearly
every one of them is built, matched and discarded, so this is a ~3.6 ns tax on
every fallible call in the language.

First two things to rule out, in order: the volatile `materializedRecord` field
on `MezzeVariant` (a volatile member is a known obstruction to scalar
replacement), and any match-side read that can call `getPayloadValue()`, which
materializes a `MezzeRecord` and would make the variant escape by itself.

### LEDGER UPDATE 2026-11-06 — the allocation row has a cause, and it is that EVERY record field is boxed

The previous entry said the next target was allocation, and named two things to
rule out: the volatile `materializedRecord` field, and a match-side read that
could materialize a record. Both are ruled out. The cause is elsewhere and it is
much more specific.

#### What the instrument actually says: count the allocations, not the nanoseconds

Young-GC pauses, Serial GC, same heap config, n=1e7, reproducible across runs
(two runs each, ±3):

```
  row                                        GC pauses
  choose-ctor arith only (no allocation)             3
  variant match, no alloc (pre-built)                3
  variant alloc, no match      (Shape)              38
  variant build + match        (Shape)              55
  1-field-only alloc, no match (Shape1)             33
  1-field-only build + match   (Shape1)             39
```

`Shape` has a two-field constructor (`Tri { b: Int, h: Int }`); `Shape1` is the
same three-way choice with every constructor single-field. The rows are
otherwise identical, so the comparison is the multi-field payload and nothing
else: **matching a freshly built two-field variant costs 11 extra young GCs and
2.4 ns an iteration** against the single-field type.

A pre-built variant matched in a loop allocates NOTHING (3 pauses, the harness
floor) — which is why the earlier "match 2.3 ns" row looked clean. It is clean;
the allocation is on the CONSTRUCTION side.

#### Why: a two-field variant constructor allocates FIVE objects

`Tri { b = i, h = i }` lowers to a `MakeRecord` for the payload followed by
`MakeVariant`, and `MakeRecord` is this (`MezzeBytecodeRootNode.java:941`):

```java
return new MezzeRecord(cachedShape, EMPTY_LONGS, EMPTY_DOUBLES, values);
```

with `cachedShape` from `ShapeCache.getOrCreateAllObjects(fieldNames)`. So:

```
  MezzeVariant                                     1
  MezzeRecord (the payload wrapper)                2
  Object[2] (the @Variadic framework array)        3
  Long box for `b`                                 4
  Long box for `h`                                 5
```

against `Circle { r = i }`, which is ONE object — the fused single-field
factory stores the raw long in the variant's typed slot.

**`MezzeRecord` has had unboxed `long[]` / `double[]` storage all along, and the
bytecode compiler never populates it.** The comment above `MakeRecord` says so
in as many words: *"Phase 2 keeps the AST-side all-object storage strategy. A
later phase can specialize on shape stability for unboxed long/double
storage."* That later phase did not happen, so every `Int` field of every
record in the language is a boxed `Long`.

This is the ledger's own floor rule coming due: *"A cell is AT FLOOR only if it
is also BOX-FREE. Unboxed is a column, not a nice-to-have: a pass that emits
`SlotKind.OBJECT` without looking up the type is a defect, not a conservative
default."*

#### And it corrects the previous entry's headline

The previous entry concluded "the cost is per-allocation, not per-byte, and it
is not something a better layout fixes". The first half holds; the conclusion
does not. Divide the measured costs by the allocation COUNT:

```
  record { r = i }      2 968 ps / 3 allocations   = ~1.0 ns each
  variant (2/3 fused, 1/3 five-object)
                        3 601 ps / 2.33 average    = ~1.5 ns each
```

~1-1.5 ns per small allocation is an ordinary TLAB bump. **Allocation is not
anomalously slow here — there are simply far more allocations than the source
asks for.** So the lever is not making allocation cheaper, it is allocating
one object where the language currently allocates five. That is a layout
question after all, and the previous entry's "CLOSED as a target" on the variant
layout row was reached from the wrong arithmetic.

#### A read-side fix was written, measured, and REVERTED — because it was dead

`ReadVariantField` gained typed `doRecordLong` / `doRecordDouble`
specializations reading the payload record's `long[]` slot directly behind a
shape-identity guard, in the same shape as `SpreadRecord.doCachedShape`. It
compiles, and it never fires: the guard asks for a field in LONG storage, and no
record ever has one, because construction always builds an all-object shape.
GC pauses were unchanged on the row it targeted (55 -> 54). Reverted rather
than committed — a specialization whose guard can never pass is dead code with a
per-read guard cost.

The same trap already has a note in this tree, one layer down:
`ReadFieldOrMethod.doRecordCached` reads `objectFields` no more, and its comment
records that reading it unconditionally *"meant no record could use the unboxed
LONG layout the Shape machinery has always supported ... That is why
`ShapeCache.getOrCreateSingleLong` was dead code."* That read-side fix landed.
`getOrCreateSingleLong` is STILL dead — now because of the write side.

**And the measurements across that build pair are not usable.** Row 17, which
the change cannot touch, moved +43% in ratio-to-control between the two builds.
That is the cross-build trap this ledger has now been caught by three times; the
GC counts above are what the conclusions rest on, because a count of pauses is
not a timing.

#### NEXT: populate typed storage at construction. The risk surface, mapped

The enabling slice is `MakeSingleFieldRecord`, because the Bytecode DSL **can**
specialize on a primitive operand there — it takes one operand, not a
`@Variadic Object[]`, so a `long value` specialization receives the value
unboxed. `MakeRecord`'s variadic array is already `Object[]` by the time the
operation runs, so the multi-field case needs per-signature operations and is a
second step.

Checked before touching anything:

- **No single-field reader uses the direct path.** 29 direct `getObjectField(…)`
  calls exist outside `MezzeRecord`, across three files, and NONE is
  `getObjectField(0)`.
- **Equality and hashing are storage-agnostic** — both iterate field NAMES
  through `getField(i)`, so `{r=1}` in LONG storage equals `{r=1}` in OBJECT
  storage.
- **Every derived shape is all-object.** Both `computeResultShape` overloads
  (spread at :1058, update at :1151) call `getOrCreateAllObjects`, and spread
  reads its source through `getField`, so a typed source cannot produce a record
  that claims typed storage over an empty array.
- **The consumers are already guarded for it.** `BuiltinUnaryNodes.negateLong`
  reads `getLongField(N_INDEX)` behind `isLongRecord(args)`
  (`getLongFieldCount() >= 1`) — written for typed storage and currently never
  firing. Typed construction turns those guards live.
- **One hazard, and it needs a test.** `MezzeRecord.withField` on LONG storage
  does an unchecked `newLongs[loc.index] = (long) value`. A field whose type
  changes would be a ClassCastException rather than a type error. Every path
  that could do it goes through spread/update, which produce all-object results
  — so it looks unreachable, and "looks unreachable" is what a test is for.

Expected on the measured rows: `{ r = i }` goes from 3 allocations to 2, and
`Tri { b = i, h = i }` from 5 to 3 once the multi-field step follows.

### CORRECTION 2026-11-06 — BUG 36 is WITHDRAWN. `to_str` is not an ability, so that structural type is right

Filed earlier today off these same bench rows, and wrong. `let show = { x } ->
x.to_str {}` inferring `{ x: { to_str: {} -> 'a } } -> 'a` is **the most general
type expressible**, not a wrong branch:

`to_str` is an INHERENT IMPL METHOD (`stdlib/std/int.mz:179`, `impl Int where {
to_str = … }`), one per type. There is no `ability ToStr`, and an inherent impl
is not something a signature can abstract over — so for an unknown `x` the only
thing the type system can say is "has a `to_str` member", and a structural
record is the only form that says it. That `Int` cannot then satisfy it is not
inference failing; it is inherent impls not being abstractable, which is what
abilities are for.

The abstraction exists and works. `ability Inspect for 'a where { to_dbg: { it:
'a } -> Str }`, auto-derived for all types, and `to_dbg` IS an ability op:

```
  let show = { x } -> x.to_dbg {}
  show { x = 5 }  ->  5        show { x = "hi" }  ->  "hi"        show { x = 2.5 }  ->  2.5
```

One definition, three types, no annotation, verified running. The V0408 on
`(n / d * 100).to_str {}` is correct for the same reason — no `NumMul` impl
produces a record.

**The one real defect found here is a COMMENT.**
`stdlib/std/abilities/inspect.mz:2` says *"Used for string interpolation:
`"value is $(x)"` calls `x.to_dbg {}`"*. It does not — interpolation requires
`Str`, and `"int=$(n)"` on an `Int` is `T0201: String interpolation requires
String type`. Whether that comment is stale or describes intent never
implemented is the author's call, so it is left alone and raised instead. It is
also what misled the bench comment, which blamed the non-bug for what is simply
how interpolation works; that comment is corrected.

**Why this is in the ledger rather than quietly deleted.** The rule this day
already earned was about measurement — every number produced by comparing across
builds was wrong. This is the same error in the type system: I read an inferred
type as a defect because it was inconvenient for the program I was writing, and
never checked whether the member I wanted to abstract over was an ability. The
first question about an unwanted inferred type is whether it is the most general
one the system can express, and only then whether inference erred.

### LEDGER UPDATE 2026-11-06 — records store numbers UNBOXED, and the arithmetic predicted the measurement

The target the previous entry derived, taken in two steps. Both are measured by
YOUNG-GC PAUSE COUNT, which is the right instrument for an allocation question
and — unlike a timing — survives a rebuild. n=1e7, Serial GC, same heap, two
runs each, and every "before" was taken by guarding the new specialization OFF
in the same tree rather than by reading an older build.

#### Step 1 — `MakeSingleFieldRecord` takes a primitive operand

```
  row 15  record alloc, 1 field   `{ r = i }`      37  ->  31, 30     19% less
  row 13  the same loop building VARIANTS          40  ->  39, 39     unchanged (control)
```

The control is unchanged because a single-field variant payload takes the fused
factory and never builds a record at all.

#### Step 2 — `MakeRecord2`, a fixed-arity two-field operation

```
  row 18  record alloc, 2 fields  `{ a = i, b = i }`   42, 43  ->  33, 32   24% less
  row 15  record alloc, 1 field              (control)  29, 29  ->  31, 30   unchanged
  row 13  the same record INSIDE a variant    39, 38  ->  38           unchanged
```

**The arithmetic predicted it to within 1%**, which is the part worth keeping:

```
  before:  MezzeRecord 40B + Object[2] 24B + Long 16B + Long 16B  =  96B
  after:   MezzeRecord 40B + long[2] 32B                          =  72B
  predicted 72/96 = 0.75        measured 32.5/42.5 = 0.76
```

#### Why a NEW operation instead of fixing `MakeRecord`

`MakeRecord` is variadic, and a `@Variadic Object[]` is already populated with
BOXED values by the time the operation body runs — no work inside it can undo
that. A FIXED-ARITY operation takes each operand as its own child and the
Bytecode DSL hands primitives over unboxed, the same mechanism `AddLong` uses,
with `@ForceQuickening` so the interpreter rewrites to the long-only bytecode
after warmup. So arity 2 gets its own operation and the variadic one stays for
arity 3+.

Mixed types (one `Int`, one `Str`) fall to the object layout rather than
multiplying specializations — three storage kinds across two fields is nine
combinations, and the mixed ones are both rarer and cheaper to leave boxed.

#### What this turned from dead to live

`ShapeCache.getOrCreateSingleLong` had no callers at all. The note in
`ReadFieldOrMethod.doRecordCached` already recorded it being dead once before,
for the opposite reason — the read side used to read `objectFields`
unconditionally. That half was fixed earlier; this is the other half, and the
two together mean a record's typed storage is now both written and read.
`BuiltinUnaryNodes.negateLong`, which reads `getLongField` behind
`isLongRecord(args)` and had never fired, is live too.

`RecordUnboxedStorageTest`, 11 tests, and they discriminate — guarding `doLong`
off fails "an Int field is stored unboxed in the long array", guarding
`doLongLong` off fails the two-field pair. They also pin the two things a second
layout could have broken: a site that sees an `Int` and then a `Str` settles on
the object layout and keeps working, and records with equal names and values
compare equal across layouts (`equals` goes by field NAME through `getField`).

#### Row 13 is the honest disappointment, and it names the next target

A two-field record inside a variant shows NO measurable change, because the
variant itself swamps it. `Tri { b = i, h = i }` is now three objects instead of
five, but:

```
  MezzeVariant  ~64B      <- ten fields, whatever the shape
  MezzeRecord    40B
  long[2]        32B
```

The variant is the biggest single object in the chain and carries slots for
every payload shape at once — `registry`, `tag`, `payloadType`, `payload`
(`Object[]`), `longValue`, `doubleValue`, `singleValue`, `payloadLength`,
`fusedFieldName`, and a volatile `materializedRecord` — to hold a tag and two
longs. An earlier entry called that layout row CLOSED as a target on the
grounds that cost is per-allocation rather than per-byte; the pause counts here
say otherwise, because a pause count IS bytes.

**NEXT, in order of measured payoff:**

1. **Small records as ONE object, not two.** `{ a = i, b = i }` is still a
   `MezzeRecord` plus a `long[2]` that spends a 16-byte header on 16 bytes of
   payload. Inline fields on a small-arity record would make it a single
   ~32-40B object — roughly another halving on rows 15 and 18, and the same
   saving again inside every variant payload.
2. **The `MezzeVariant` layout**, now that row 13 shows it dominating its own
   chain. `MezzeRecord` is `final` with only 9 external construction sites, and
   `MezzeVariant` is `final` too, so both are tractable; the readers all go
   through accessors that already switch on the shape's storage kind.
3. Arity 3+ records, which still take the variadic boxed path. Worth doing after
   (1), because (1) changes what the fixed-arity operations should construct.

### LEDGER UPDATE 2026-11-06 — `MezzeVariant` 64 bytes -> 48, and row 13 stops being the disappointment

The previous entry named this target: *"the `MezzeVariant` layout, now that row
13 shows it dominating its own chain"*. Taken, and the two fields that went are
the ones no variant could ever use at the same time.

```
  long longValue;      \__ a variant is exactly ONE payload type, and
  double doubleValue;  /   payloadType says which — so the second
                           eight-byte slot was unusable by construction.

  int payloadLength;       every construction route set this to exactly what
                           payloadType implies: 0 for NONE, 1 for a single
                           payload, payload.length for MULTI. Four bytes of
                           restatement, now derived.
```

12 bytes off a 60-byte field set, so the object drops a padding step:
**64 bytes -> 48, 25% of every variant the language allocates.**
`payloadType` is `@CompilationFinal`, so the derived `getPayloadLength()`
switch folds away under partial evaluation and costs nothing compiled.

#### Measured, young-GC pauses, n=1e7, two runs each

```
  row                                           before   after    change
  17  variant alloc, all ctors single-field         34   28, 27    -19%
  13  variant alloc, one ctor has 2 fields          38   34, 34    -11%
  10  variant build + match (Shape)                 58   53, 54     -8%
  16  variant build + match (Shape1)                39   36, 37     -7%
  ---- controls: rows that allocate NO variant ----
  15  record alloc, 1 field                         31   30, 29   unchanged
  18  record alloc, 2 fields                        33   32, 33   unchanged
```

Row 17 is the clean signal: one fused single-field variant per iteration, so
the variant is the only object allocated. Against the ~3-pause harness floor
that is 31 -> 24.5 of variant-attributable pauses, 21%, against 25% predicted
from the object size. The two record rows are the controls and they do not
move — they allocate no variants at all.

**Row 17's 19% is the number that matters most**, because `Option` and `Result`
ARE single-field variants and nearly every fallible call in the language
returns one.

#### The risk this took, and the test that covers it

A `Float` payload now lives as raw bits in the shared slot.
`Double.doubleToRawLongBits` is the correct conversion and
`Double.doubleToLongBits` is NOT — the latter canonicalizes every NaN to one
bit pattern, so a signalling NaN would not survive a round-trip. Negative zero
is the other trap: `-0.0 == 0.0` is true, so an equality assertion would pass
even with the sign bit dropped.

`MezzeVariantPayloadSlotTest`, 9 tests, compares BITS for both cases. It
discriminates: switching the two call sites to `doubleToLongBits` fails
`aNonCanonicalNaNSurvivesTheRoundTrip` and nothing else, which is exactly the
test earning its place.

#### Where the three allocation steps have got to

```
                                    objects   bytes
  `{ r = i }`        before            3        ~72      box + Object[1] + record
                     now               2         56      long[1] + record
  `{ a = i, b = i }` before            4         96      2 boxes + Object[2] + record
                     now               2         72      long[2] + record
  `Circle { r = i }` before            1         64      the variant alone
                     now               1         48
  `Tri { b, h }`     before            5        ~160
                     now               3        ~120
```

**NEXT, and it is the same lever a third time: the ARRAY is now the overhead.**
`{ r = i }` is a 32-byte record plus a `long[1]` that spends a 16-byte header
on 8 bytes of payload. Inline fields on a small-arity record would make it one
~32-byte object — roughly another halving on rows 15 and 18, and the same
saving again inside every multi-field variant payload. `MezzeRecord` is `final`
with only 9 external construction sites and every reader goes through an
accessor that already switches on the shape's storage kind, so the surface is
tractable; `MezzeVariant` has 876 external references but they are nearly all
to the TYPE, and this entry's change proves the layout is private enough to
move underneath them.

### LEDGER UPDATE 2026-11-07 — the string ratios were not comparable, and `to_upper` is 25x `concat` PER CHARACTER

#### The instrument was wrong, and it is wrong in a way this ledger warned about

Every string row above is a ratio to a control that performs ONE add. That is
fine for `str::size`, which is O(1) and reads 2x. It is meaningless for
`str::to_upper`, which reads 262x — because it transforms 64 characters, and
the control transforms nothing. Comparing an O(1) row and an O(n) row on the
same ratio axis is the same category error as comparing two kernels in
different warmup regimes, and it is why `str::split` at "1 480x" sounded like
the end of the world and `to_upper` at "262x" sounded like a footnote.

The fix is the one this repo already has a rule for: **measure the slope.** Rows
19-22 run `to_upper` and `concat` over a 15-character and a 131-character
string, so the fixed cost cancels and what is left is cost per character.

```
  op         15 chars   131 chars    ps/CHAR   fixed
  to_upper     16 253     108 195       793    4.4 ns
  concat        8 576      12 290        32    8.1 ns
```

**`to_upper` costs 25x what `concat` costs per character** — 2.6 cycles a
character against 0.1 — and `concat` is doing strictly more in the fixed part
(it allocates). So the per-character transformation is the whole story, and the
262x headline was mostly string length.

That also corrects a guess made an hour earlier in this session: I had reasoned
that `to_upper` must be near its floor because the JDK already works on
`byte[]` in a single pass. `concat`'s 32 ps/char says byte-level work on this
machine runs an order of magnitude faster than that.

#### TRIED, MEASURED WORSE, REVERTED — and the wrong inference is the lesson

The attempt: guard-scan for a non-ASCII character, then
`getBytes(ISO_8859_1)` -> a `byte[]` loop doing `c >= 'a' && c <= 'z' ? c - 32
: c` -> `new String(bytes, ISO_8859_1)`, with the JDK kept for anything
non-ASCII and for the `Locale.ROOT` fallback.

```
  row                      before    with the change    controls
  to_upper  15 chars       16 253       22 400  +38%
  to_upper 131 chars      108 195      120 483  +11%
  concat    15 chars        8 576        8 608          unchanged
  concat   131 chars       12 290       12 387          unchanged
  str::contains             5 068        5 076          unchanged
```

Slope went 793 -> 846 ps/char and the fixed cost went 4.4 -> 9.7 ns. Reverted,
and the revert re-measured at 16 439 / 107 229, back where it started.

**The inference that was wrong.** I read `concat`'s 32 ps/char as "byte-level
work reaches ~10 bytes/cycle here, so a byte loop will too". It does not.
`concat`'s rate comes from an INTRINSIC array copy — `System.arraycopy` /
`StringConcatHelper`, which compile to a vectorized block move. A hand-written
loop with a CONDITIONAL STORE is not that shape, and C2 did not vectorize it.
On top of that the round trip added two allocations, and the guard scan's early
exit (`return` on the first non-ASCII char) is itself an unvectorizable loop.

So: a measured rate from an intrinsic is not evidence about what a loop will
achieve. Same family as the error the SIMD attempt made and the error the
cross-build comparisons made — reasoning from a number taken under different
conditions than the one being predicted.

#### Where the row lands

| row | state |
|---|---|
| `str::to_upper` / `to_lower` | **ABOVE FLOOR, 25x `concat` per character, cause named.** The JDK converts one character at a time through a `CharacterDataLatin1` lookup plus an encodability check. Real, and worth ~25x if it could be vectorized |
| the Java-level route to closing it | **CLOSED, with evidence.** An `ISO_8859_1` byte round-trip measures WORSE (above). A vectorized conversion needs the string's own `byte[]`, which Java does not expose, so closing this needs a Graal/Truffle intrinsic rather than guest-side or native-Java code |
| the string ratio axis | **FIXED.** Rows 19-22 give slope + intercept. Every O(n) string row should be read that way, and the ratios to a one-add control should not be compared against O(1) rows |

#### What this does NOT change

`str::split`'s 1.88x stands — that was a regex compile per call, measured with
fourteen untouched control rows, and it is a fixed cost removed rather than a
per-character rate. The allocation work stands too: four commits, each with a
control the change could not affect.

**NEXT is back to allocation**, which remains the largest closable gap:
`{ r = i }` is a 32-byte record plus a `long[1]` spending a 16-byte header on 8
bytes of payload. Inline fields on a small-arity record collapse that to one
object — and unlike the `to_upper` attempt, the mechanism is an object layout
rather than a hoped-for vectorization, which is the kind of change that has
actually paid this session.

### LEDGER UPDATE 2026-11-07 — where the allocation thread stops, and why each remaining byte is expensive

Four allocation commits landed. This entry records the floor each one reached
and, for the two obvious "next steps", the evidence that they are worth less
than they look. Both are CLOSED with a reason rather than left as inviting
TODOs.

#### The instrument validated itself

The pause-count model can be checked against the object sizes it is being used
to infer, and it agrees. Row 17 allocates one single-field variant per
iteration, 1e7 iterations x 17 passes = 1.7e8 variants:

```
  before   1.7e8 x 64B = 10.88 GB   over (34 - 3) = 31 pauses   ->  351 MB/pause
  after    1.7e8 x 48B =  8.16 GB   over (27 - 3) = 24 pauses   ->  340 MB/pause
```

Same young-gen size per pause on both sides, which is what makes the 64 -> 48
claim a measurement rather than an arithmetic hope.

#### CLOSED: `MezzeVariant` stops at 48 bytes

48 is already 8-aligned, so the next saving has to be EIGHT bytes, not four —
dropping any single 4-byte field changes nothing after padding. The four
candidates, and what each costs:

| field | bytes | verdict |
|---|---|---|
| `registry` | 4 | Read in exactly ONE place, `registry.getConstructorName(tag)`, and the global registry knows every tag the compiler emits. Removable — but 4 bytes alone buys nothing |
| `materializedRecord` | 4 | **MUST STAY.** It caches the wrapper record `getPayloadValue()` builds for a fused variant, and that has 19 callers of which several are hot — `MapNatives:403` is `map::get`'s result unwrap. Dropping the cache allocates a `MezzeRecord` per `map::get`, trading 4 bytes per variant for an allocation per lookup |
| `fusedFieldName` | 4 | Derivable from the tag, but only via a tag -> field-name side table the COMPILER would have to populate. New machinery for 4 bytes |
| `payload` + `singleValue` merge | 4 | Doable; loses `@CompilationFinal(dimensions = 1)` on the array |

So the only route to 40 bytes is `fusedFieldName` + the payload merge together,
one of which needs new compiler machinery, and the route to 32 needs the cache
that `map::get` depends on. Not worth it at this exchange rate.

#### CLOSED: the record class hierarchy is worth less than the bench row suggests

The plan was an abstract `MezzeRecord` with a no-array subclass: `{ r = i }`
would go from 56 bytes in two objects (a 32-byte record plus a `long[1]`
spending a 16-byte header on 8 bytes of payload) to 24 in one. 43% fewer bytes,
and nothing worse for large records since they keep the array-backed subclass.

**Row 9 is why that is narrower than it looks.** It is a two-argument
self-recursive call, 1e7 iterations, and it costs **3 GC pauses** — the
harness floor. Argument records are already bypassed on the hot path, so the
records this refactor would shrink are the explicit `{ … }` literals in user
code, not every function call. Against that: `MezzeRecord` is `final` today
with `@ExportLibrary(InteropLibrary.class)` and four `@ExportMessage` methods,
and the split makes ~10 accessors virtual and has to settle what `withField`
means when a one-long record is updated with a non-long value — a SHAPE change
that `withField` cannot currently express, since it keeps `shape`.

A large, interop-touching core-type refactor for a narrower win than the bench
row implies. Recorded, with the design settled, for when explicit record
literals are shown to matter — not taken on the strength of a row that
over-represents them.

#### Where the four commits left things

```
                                    objects   bytes    (was)
  `{ r = i }`                          2        56       3 / ~72
  `{ a = i, b = i }`                   2        72       4 /   96
  `Circle { r = i }`                   1        48       1 /   64
  `Tri { b = i, h = i }`               3       ~120      5 / ~160
```

| row | state |
|---|---|
| single-field record storage | **AT FLOOR for this layout.** Unboxed; the residue is the `long[1]` header, and see above |
| two-field record storage | **AT FLOOR for this layout.** Unboxed, measured 24% with the arithmetic predicting 25% |
| `MezzeVariant` size | **AT FLOOR at 48 bytes**, next step costs 8 coupled bytes — see above |
| argument records | **CLOSED — already bypassed.** Row 9, a two-arg self-call, allocates nothing |
| `str::to_upper` per-character cost | **ABOVE FLOOR, 25x `concat`**, Java-level route CLOSED with a measured regression. Needs a Graal intrinsic |
| arity 3+ records | **ABOVE FLOOR.** Still the variadic boxed path; a fixed-arity operation per arity would fix it, and the shape of that change is `MakeRecord2` repeated |

**The one that is still open and cheap is the last row.** `MakeRecord3` and
`MakeRecord4` are `MakeRecord2` again — the same three specializations, the
same `ShapeCache.getOrCreateAllLong`, the same emitter branch. Arity 3-4
records are common enough in real code (a point, a span, an HTTP response) and
every one of them currently boxes every numeric field. That is the next move:
known mechanism, known measurement method, no new design.

#### Taken immediately: arity 3 and 4, 18% each

The row above called `MakeRecord3` / `MakeRecord4` "the next move: known
mechanism, known measurement method, no new design". Done, and it is exactly
`MakeRecord2` twice more — three specializations each, the same
`ShapeCache.getOrCreateAllLong`, two more emitter branches.

```
  row                              typed OFF    typed ON    change
  23  record alloc, 3 fields        45, 42       35, 36      -18%
  24  record alloc, 4 fields        45, 46       39, 36      -18%
  15  record alloc, 1 field         29, 30       31, 29      unchanged (control)
  18  record alloc, 2 fields        33, 33       33, 34      unchanged (control)
```

The two controls are rows whose arity routes to a different operation, so they
pin that nothing moved underneath.

**The arithmetic does NOT pin this one down, and the bench is why.** Predicted
bytes for three `Int` fields:

```
  before   record 32 + Object[3] 32 + three Long boxes 48  = 112B   -> 36% saving
  before   record 32 + Object[3] 32 + ONE Long box 16      =  80B   -> 10% saving
  after    record 32 + long[3] 40                          =  72B
```

Two predictions, because `l_rec3` stores the SAME value in every field
(`{ a = i, b = i, c = i }`) and Graal may common up the three identical
`Long.valueOf(i)` calls into one box. Measured 18% sits between 10% and 36%, so
the model is consistent but cannot be checked sharply. Distinct values per
field would settle it, and that is a bench fix worth making before quoting a
precise figure for this row. Recorded rather than rounded to whichever
prediction flattered the change.

**The boundary is now five fields**, and it is pinned by a test
(`five fields still use object storage, which is the documented boundary`) so
that it reads as a decision rather than as an oversight. Arity 5+ still takes
the variadic path and still boxes; extending further is the same mechanism
again, with diminishing frequency in real code.

**A note on the method, from two self-inflicted errors in this step.** The
script that disabled the typed path to take the "before" reading matched the
3-arity signature as a PREFIX of the 4-arity one, twice — once in each
direction. Both times the `assert s.count(old) == 1` fired and nothing was
patched, which is the only reason the first "before" numbers (identical to the
after, because nothing had changed) were recognised as bogus instead of
published as a null result. Patterns for these A/B scripts have to be anchored
on something that follows the varying part.

### LEDGER UPDATE 2026-11-07 — THE EFFECTS SURFACE HAS NUMBERS, and `resume` costs 11x the implicit form

This is the row the ledger called its own biggest gap: *"the measured surface is
fast, and the unmeasured surface is most of the concurrency and effects story.
Not one of the Loom or continuation rows has a number against it."* The effects
half now does.

`bench/cases/effectsurface` already existed — written to fill exactly these
cells, and never run. Seven kernels, one per process, n=2e7, and **every kernel
returns the same checksum (200 000 010 000 000)**, so a kernel that got fast by
dropping work would read as a different number. Minima across nine readings
each, because three passes of warmup at this n is marginal and the spread shows
it (kernel 3 ranged 827 to 5 804 — the minimum is the estimator, the spread is
the warning).

```
  kernel                              min ps/iter    minus tier control
  1  control (fused tail-loop tier)         241
  2  tier control (off that tier)           452       <- the subtraction base
  3  one op, handler installed ONCE         827              375
  7  chain of FOUR handlers                 910              458
  4  explicit `resume { val = 1 }`        4 692            4 240
  5  handler INSTALLED PER ITERATION     90 837           90 385
  6  ABORT per iteration                334 273          333 821
```

Kernel 2 exists because a `perform` leaves the fused tail-loop tier, so
measuring against kernel 1 would charge the tier gap (211 ps) to effects. That
is the bench's own design and it is right.

#### Four findings, in order of size

**1. An operation reaching an installed handler costs 375 ps, and handler DEPTH
is nearly free.** Four handlers deep costs 458 ps against one handler's 375 —
83 ps for three extra levels. So reaching a handler is a cached constant, not a
walk, which is what `PerformD`'s handler-chain-stable assumption claims and now
has a number behind it. **AT FLOOR**, and the ledger's old lone effect figure
(~0.38 ns for the fall-through path) is consistent with it.

**2. Explicit `resume { val = 1 }` costs 4 240 ps — ELEVEN TIMES the implicit
bare-value form's 375.** The bench predicted they would be equal, in writing:
*"PerformD's comment says resume is fall-through, so this should cost what Ones
costs; if it does not, the two forms compile differently and that is worth
knowing."* They do compile differently, and in nine readings kernel 4 never once
dipped into kernel 3's range.

The cause, read off the code rather than guessed:

  - `EffectBinding.doCachedResumeAbort` resolves the NAME `resume` to a
    singleton `MezzeFunction` that PE constant-folds, so READING `resume` is
    free — that part works as advertised.
  - the COST is the call. `resume { val = 1 }` builds a `{ val = 1 }` argument
    record and makes an indirect call to that singleton, whose entire body is
    `return arg.val` (`YieldDispatch`: *"resume is fall-through: calling
    resume {val = X} returns X"*).
  - a record allocation measures ~3 ns on this machine and an indirect call
    ~1 ns, which accounts for the 4.2 ns almost exactly.

So the explicit form allocates an object and performs a call in order to return
a value the implicit form returns directly. **ABOVE FLOOR, cause named**, and
the fix has a shape: a tail-position `resume { val = e }` in a handler body
should lower to `e` under the same handler-stable assumption
`doCachedResumeAbort` already guards on, with the record-and-call kept as the
fallback for dispatch mode. Tail position only — fall-through returns `arg.val`,
so a non-tail `resume` whose result is then used further is NOT the same program.

**3. Installing a handler per iteration costs 90 ns — 240x an already-installed
op.** The ledger had `perform` in a tier-1 loop marked CLOSED ("that tier runs
the loop in Java; it cannot call a guest handler") but no number for the cost of
the install itself. 90 ns per install is the number, and it says plainly that a
`perform` inside a loop is a different order of magnitude from a `perform`
outside one. Whether 90 ns is near ITS floor is a separate question and still
UNKNOWN — it invalidates the chain-stable assumption on every push and pop, so
it is paying deoptimisation, not just bookkeeping.

**4. Abort costs 333 821 ps — 334 ns, 3.7x the install it sits on top of.** That
is an exception throw (`AbortPerformException`) per iteration, and the figure is
what it costs. Whether an abort in a loop is a shape worth optimising is a
design question; the number is recorded so it can be answered.

#### What is still UNKNOWN in this family

The bench covers the handler surface. It does NOT cover: real continuation
capture (resume stored, deferred, or invoked twice — every number above is the
fall-through single-return path), effects once a second thread invalidates
`singleThreaded` for the life of the process, scoped performers, or any of
Loom — spawn, join, channels, STM, atoms, parallel map, cancellation,
structured scope. That remains the largest UNKNOWN block in this ledger.

#### An instrument note

`effectsurface` does 3 warmup passes then reports 3 readings, and at n=2e7 with
a ~1 ns kernel that is ~20 ms a pass — short enough that the spread between
readings reached 7x on kernel 3. The minima are stable across processes and are
what is quoted, but the bench should take a min-of-k internally the way
`bench/cases/surface` and `ds_matrix` now do. Recorded rather than silently
relied upon.

#### `resume` FIXED, 3.7x — and the record claim in the entry above was wrong

The entry above named explicit `resume` as the finding and guessed the cost as
"~3 ns for the record plus ~1 ns for the call". **Dumping the IR corrected the
first half before anything was built**, and the correction is worth more than
the guess was:

```
  body = CallIndirectScalarIR(
           function = EffectBindingIR(name=resume),
           args = [LongLitIR(1)],
           argFieldNames = [val],
           isTailCall = false)
```

Scalar ABI, `argFieldNames=[val]` — **there is no `MakeRecord` in the IR at
all.** So allocation could not be the explanation as stated.

What it actually is, with a kernel added to prove it rather than argue it.
Kernel 8 gives the handler a body that is a DIRECT call to a top-level function
returning its argument — same shape as resume, different call kind:

```
  kernel 3  handler is a bare value `1`               840 ps/iter
  kernel 8  handler is a DIRECT call returning arg    894      (+54 — free)
  kernel 4  handler is `resume { val = 1 }`         4 091    (+3 251)
```

Kernel 8 is what rules out "calling a function is expensive": Graal inlines a
direct call of that shape for 54 ps. The 3 251 ps belongs to this callee
specifically, and `CallIndirectScalar1`'s own class doc says why:

> *"When the callee is NOT a BDSL function (AST function, effect handler, etc.),
> falls back to wrapping the arg in a single-field record so the callee's
> existing normalizeArgs path works correctly."*

The fall-through resume singleton (`YieldDispatch`) is a plain `MezzeRootNode`
whose body is `rec.getField("val")` over a **record** argument.
`canBypassTarget` refuses it — correctly, that bypass is for trampoline-wrapped
BDSL roots. So a scalar call site allocated a record to hand to a callee whose
only job was to take the field straight back out, then made an un-inlined
indirect call to do it. The record is real; it is just produced by the call
glue, not by an IR node. Both halves of the original guess were present, and the
reasoning behind them was not.

**The fix.** `CallIndirectScalar1.doFallThroughResume` returns the argument and
makes no call. The guard is IDENTITY against the singleton, whose behaviour is
fixed at construction, so no handler-chain assumption is needed — unlike
`EffectBinding.doCachedResumeAbort`, which must guard `handlerStable` because it
is choosing WHICH function `resume` names. Once a function instance is in hand
that choice is already made: an unstable chain would have handed over the
dispatch fiber's resume and this guard would simply not match. A second guard
pins `fieldName == "val"`, since `arg` is the `val` field's value only because
the call site says so. Both guards are over constant/cached values and fold away.

```
  kernel                              before      after      controls
  4  explicit `resume { val = 1 }`     4 091      1 115       3.7x
  3  implicit bare value                 840        789       unchanged
  8  direct call returning arg           894        802       unchanged
```

Against the tier control, `resume` went from 3 639 ps over to 663 — from
**eleven times** the implicit form to under two. All eight kernels still return
the identical checksum 200 000 010 000 000.

**The test lesson is the part to keep.** `ResumeFallThroughSemanticsTest`
started as ten single-shot cases, all passing. Breaking the fast path to return
a wrong constant failed **one of the ten** — the nine others never reached the
specialization at all, because a handler that runs once takes the uncached path.
Nine tests passing because the code under test never executed. Five WARM
variants were added, each running its case in a loop so the specialization is
engaged; the same break now fails six of fifteen, including both record-payload
cases. The nesting trap (`resume { val = { val = 42 } }`, where returning
`arg.val` instead of `arg` unwraps one level too far) is only actually tested on
the warm path.

**Still open in this family**, unchanged: the 90 ns per-iteration handler
install, whose floor is unknown because it invalidates the chain-stable
assumption on every push and pop; the 334 ns abort; real continuation capture;
effects once a second thread invalidates `singleThreaded`; and all of Loom.

### LEDGER UPDATE 2026-11-07 — ASYNC HAS ITS FIRST NUMBERS, and the harness's own flag had a blind spot

The last block this ledger described as never measured: *"UNKNOWN — never
measured, and this is the biggest category: all of Loom and async (spawn, join,
channels, STM, atoms, parallel map, cancellation, structured scope)."* No bench
existed, so `bench/cases/asyncsurface` is new — same method as
`bench/cases/surface`: per-operation cost, a ratio to an in-process control,
one row per process, min over k passes, and every row returns a checksum so a
row that skipped the work reads differently.

Each row installs `LoomAsync` inside its own thunk rather than around the
harness. That keeps the thunks PURE, so `report` needs no effect-row
annotation, and the install is once per pass over n operations.

```
  row                        ps/op          n       checksum
  1  spawn + await        12 535 516 / 12 953 296   1600   ok (two runs)
  4  scoped, EMPTY         7 309 335               800    ok
  5  scoped + one task    18 490 170               800    ok
  3  join of two tasks    NOT CONVERGED — 53 395 630 at n=100, 24 695 723 at n=300
```

So, as first figures: **a spawn + await round trip costs ~12.7 µs**, an **empty
structured-concurrency scope costs 7.3 µs**, and a task inside a scope costs
**18.5 - 7.3 = 11.2 µs** on top of the empty scope — which cross-checks row 1's
12.7 µs by an independent route, and is the main reason to believe either.

**The ratio to the in-process control is deliberately not quoted.** Against a
control that does one add these are millions of percent, which is the same
category error the string rows taught: an O(1) control cannot scale a thread.
The meaningful baseline is the same round trip with no language on top, so it
was measured rather than assumed — plain Java, same machine, same n, same
min-over-k-after-warmup estimator:

```
  Java  Thread.ofVirtual() start + join, one per iteration   4 684 202 ps/op
  Java  newVirtualThreadPerTaskExecutor() open + close,
        nothing submitted                                      264 429 ps/op
```

Against those:

```
  spawn + await        Mezze 12.7 µs   Java 4.68 µs    2.7x
  scoped, EMPTY        Mezze  7.3 µs   Java 0.26 µs     28x
```

**So spawn + await at 2.7x the raw JVM round trip is respectable** — about 8 µs
of language on top of a 4.7 µs thread — and it is NOT where the problem is. The
outlier is the empty scope: **28x, for a construct that runs no user code at
all**, and 7.3 µs is more than half the cost of an entire spawn-and-await round
trip.

One caveat on that 28x, stated because it flatters the finding:
`newVirtualThreadPerTaskExecutor()` opened and closed with nothing submitted is
not a like-for-like `scoped` — Mezze's version also builds a `Scope` value, wires
cancellation, and carries a join policy. 0.26 µs is a floor-style reference, not
an equivalent construct, so the honest claim is "an order of magnitude and more
above the JVM primitive it is built on", not "28x avoidable".

#### I claimed a cliff. There is no cliff.

While sizing the rows I found n=800 completing in seconds and n=1600 exceeding
540 s, and wrote that down as a cliff between 800 and 1600 tasks in one handler
extent — a 3000x blowup. **Then the same n=1600 completed in under 150 s on
retry, with the correct checksum.** It is wall-clock VARIANCE, not a cliff: the
same row and the same n range over at least 4x, and a later repeat again
exceeded 600 s. The variance is real and worth its own row; the cliff was not.

#### The harness's flag had a blind spot, and this is the durable part

`spawn + await` at n=1600 reports 12.5 µs/op and **flags nothing**, out of a
process that runs for 150 seconds. The measured work is ~200 ms. Everything else
is the three WARMUP passes — which are executed, and which neither the minimum
nor the median can see, because both are computed over the MEASURED passes
only. So the under-warmed check, which has caught real problems all session,
could not see a row that spent 99% of its wall clock warming up.

`min_of` now also returns the slowest warmup pass, and a row whose warmup cost
more than 10x its minimum prints `WARMUP-DOMINATED` with the multiple. The
numbers above are unchanged by this — a minimum over converged passes is still
the right estimator — but the flag no longer claims a row is healthy when its
wall clock says otherwise.

The same blind spot exists in `bench/cases/surface` and `ds_matrix`, which share
this `min_of` shape. It did not mislead there, because those rows are
nanoseconds and their warmup is milliseconds; it is a latent hole in both and
worth closing the same way.

**And the new flag does not close the blind spot that matters most, which was
checked rather than assumed.** At n=30 `spawn + await` reads 157 954 500 ps/op —
12x the converged figure — and **neither flag fires**. Correctly, too: both are
SPREAD tests, and at n=30 every pass is uniformly slow, so the median is within
2x the minimum and the warmup is within 10x it. A within-run spread test can
only see warmup it has escaped from.

So for rows at this cost the only trustworthy method is the one the string rows
already forced: **measure at two sizes of n and require the per-op figure to
converge.** That is what the numbers above rest on — n=800 and n=1600 agreeing
at 10-12.5 µs, n=1600 twice at 12.5 and 12.95, and the empty scope reading
8.7 µs at n=200 against 7.3 at n=800. The flags are a backstop against one
failure mode; convergence across n is the test.

#### What this leaves

| row | state |
|---|---|
| spawn + await | **MEASURED, ~12.7 µs = 2.7x the JVM round trip (4.68 µs).** Not the problem |
| structured scope, empty | **ABOVE FLOOR, and the ranked target: 7.3 µs against the JVM primitive's 0.26 µs.** Runs no user code; costs more than half a whole round trip |
| a task inside a scope | **MEASURED, 11.2 µs** over the empty scope, cross-checking spawn + await |
| join of two tasks | **NOT CONVERGED.** Falls from 53 µs at n=100 to 25 µs at n=300; n=800 timed out. Needs the warmup fixed first |
| async wall-clock variance | **NEW ROW, ABOVE FLOOR, cause unknown.** The same row at the same n ranges 4x+ in wall clock |
| channels · STM · atoms · par_map · cancellation | **still UNKNOWN** |

**NEXT**: the empty `scoped`. The baseline is measured, the ranking is settled,
and it is the one row doing no user work for its 7.3 µs. After that the
wall-clock variance, then `join` once the warmup flag has been acted on.

The baseline program is `VThreadBaseline.java` in this session's scratchpad
rather than the repo — it is a measurement instrument for one row, not
something to maintain. It is reproduced in the commit message so the figures
can be re-derived.

#### The empty scope's 7.3 µs: cause named, and it is an existing ledger row

Read the path rather than guessed at it, and the things that looked expensive
are not:

  - `HandlerChainRegistry.runDetached(body)` is `return body.get()` — a no-op
    wrapper.
  - `MezzeScope.awaitAll()` over zero children is an empty loop.
  - `NativeDispatch` caches its handler **at construction**, so there is no
    per-call `HashMap`-by-string lookup. (Worth stating because that is exactly
    the shape the no-name-dispatch rule exists for, and here it is already
    right.)

What is left accounts for it structurally:

```java
  @TruffleBoundary
  public static Object scoped(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
```

and the only registration is in the record-ABI map:

```java
  HANDLERS.put("__native_async_scoped", (r, c) -> AsyncNatives.scoped(r, c, null));
```

So **every `scoped` call crosses a `@TruffleBoundary`, and the guest callback is
then invoked by `IndirectCallNode` from INSIDE that boundary.** Nothing in the
scope setup is partial-evaluated, and the callback cannot be inlined into the
caller's compiled code either — it is reached through an un-inlined indirect
call behind a boundary. On top of that each call allocates a `MezzeScope`, a
`{ sc }` record, an argument array and a `Supplier` lambda, does three
`ThreadLocal` operations, and looks up the context with a NULL node
(`MezzeContext.get(null)`), which cannot use a node-cached reference.

That is the same cause as the `resume` row fixed earlier today — an un-inlined
indirect call to a callee the compiler could have folded — and it is already a
row in this ledger's ABOVE FLOOR list: *"the record-ABI natives still
unported"*. `__native_async_scoped` has no scalar/BDSL path at all.

**The fix shape** is a port to the scalar/BDSL ABI as an operation, so the
callback becomes a direct inlinable call the way `MakeRecord2` and the fused
chain operations are. It is not a mechanical port: `scoped` is a control-flow
native with a try/finally, parent-scope linkage through a `ThreadLocal`, and
four distinct exception paths that each cancel children. Sized and ranked, not
started.

**One number that should go with it when it is done**, from the same session:
`Async.spawn` + `await` is 12.7 µs against the JVM's 4.68 µs, i.e. ~8 µs of
language overhead, and an empty `scoped` is 7.3 µs of that same kind of
overhead with no thread involved at all. If the boundary-plus-indirect-call
shape is what costs ~7 µs here, it is likely a large part of the ~8 µs there
too — `Async.spawn` is registered the same way — confirmed by inspection:
`HANDLERS.put("__native_async_spawn", (r, c) -> AsyncNatives.spawn(r, c, null))`,
record-ABI only, boundary lambda, null node, no scalar path. That is still a
prediction about the SPLIT of the 8 µs, recorded so it can be checked rather
than assumed.

### RETRACTION 2026-11-07 — the empty scope is ~62 ns, not 7.3 µs. The "28x" finding does not exist

Two entries above are wrong, and the commit titled *"the EMPTY scope is 28x"* is
wrong in its title. The empty `scoped` row was measured at n=800 only. Measured
where it actually converges:

```
  scoped, EMPTY     n=800       7 309 335 ps/op      <- what was published
                    n=5 000       296 178
                    n=20 000       66 696
                    n=80 000       61 861            <- converged, 7% apart
```

**~62 ns, not 7 309 ns. The published figure was inflated 118x by warmup.**

Everything built on it falls with it:

| claim | status |
|---|---|
| "empty scope 7.3 µs" | **WRONG.** ~62 ns |
| "28x the JVM's 0.26 µs" | **WRONG, and backwards.** 62 ns against `newVirtualThreadPerTaskExecutor()` open+close at 264 ns makes Mezze's empty scope ~4x FASTER than the JVM construct — with the caveat already noted, that they are not equivalent constructs |
| "the ranked target: runs no user code for its 7.3 µs" | **WITHDRAWN.** There is no target here |
| "cause named: `@TruffleBoundary` + `IndirectCallNode`, the unported record ABI" | **WITHDRAWN.** It was a causal story for a number that did not exist — and separately refuted by measurement, below |
| "NEXT: port `scoped` to the scalar ABI" | **WITHDRAWN.** Do not do this on the strength of that entry |

#### The refutation came from testing the cause instead of assuming it

Before porting concurrency code I built a probe — `__native_glue_probe`, a
record-ABI native with the *same shape* as `__native_async_scoped`
(`@TruffleBoundary`, resolve the callback, invoke it through an
`IndirectCallNode`) and nothing else in it. Two hypotheses died:

```
  native glue only (pure decl)        n=20 000  37 692 ps/op   n=80 000  35 801
  the same native, `<Async, ...>` row n=20 000  44 785         n=80 000  41 272
  scoped, EMPTY                       n=20 000  66 696         n=80 000  61 861
```

- **The glue is ~36 ns**, not microseconds. The boundary-plus-indirect-call
  story was false by a factor of 200.
- **The effect row costs nothing** — the same native declared `<Async, ...>` and
  called inside a handler extent measures the same as the pure declaration.
- `scoped`'s own bookkeeping is **62 − 36 = ~26 ns** over the glue, which is
  what four small allocations, a context lookup, three `ThreadLocal` operations
  and a lambda should cost. The original enumeration was right; the number it
  was trying to explain was not.

So the probe did its job twice over: it refuted the mechanism, and chasing the
residue refuted the measurement.

#### How this happened, which is the part worth keeping

Rows 1 and 5 contain a real thread, so they cannot be run millions of times —
n=800 to 1600 is all they allow in reasonable wall clock. Row 4 was sized to
match them. **But the empty scope contains no thread**, so it can run a hundred
times more iterations, and nobody asked it to.

The aggravating detail: the commit immediately before this one records the rule
that would have caught it — *"the only trustworthy method is to measure at two
sizes of n and require the per-op figure to converge... the flags are a backstop
against one failure mode; convergence across n is the test."* That was written,
committed, and then not applied to row 4 in the same session. A rule recorded is
not a rule followed.

The `WARMUP-DOMINATED` flag added in that same commit **does** fire on all three
larger readings (40x, 81x, 27x). At n=800 it did not — that is the third blind
spot also documented there: when every pass is uniformly slow no spread test can
see it. So the instrument was honest at every step and the error was entirely in
how one row was sized.

#### What survives

```
  native glue (boundary + IndirectCallNode + record)   ~36 ns    converged
  scoped, EMPTY                                        ~62 ns    converged
  scoped's own bookkeeping over the glue               ~26 ns
  Java newVirtualThreadPerTaskExecutor open+close      264 ns    reference
```

`spawn + await` is **better supported than row 4 was, but not confirmed.** It
has two sizes that agree — 10.1 µs at n=800 and 12.5/12.95 µs at n=1600 — and
the figure moved UP with n, which is the opposite of what warmup inflation
produces. That is weak evidence, not the 118x collapse row 4 suffered. Pushing
it further is bounded by physics rather than patience: every operation is a real
thread, so n=5000 means 50 000 thread round trips per row and the warmup passes
alone exceed a 900 s budget. `join` at n=5000 timed out for the same reason.

So the honest state of the thread-bearing rows: **~10-13 µs for spawn + await,
from two agreeing sizes, with no third available at this cost.** `scoped + one
task` (n=800) and `join` (unconverged) stay UNVERIFIED. The JVM baseline
(`Thread.ofVirtual()` start+join, 4.68 µs) stands — same min-over-k, and a plain
Java loop with no warmup cliff of this shape — so spawn + await at roughly 2-3x
the JVM round trip is the surviving claim, and it was never the alarming one.

#### Loom's `StructuredTaskScope` as a replacement for our `scoped` — measured, and the answer is no

Asked directly, so measured directly rather than reasoned about. JDK 25
(GraalVM CE 25.0.2), same machine, same min-over-k-after-warmup estimator as
every row above, n=20 000 so both sides are converged:

```
  StructuredTaskScope.open() + join(), nothing forked      112 727 ps/op   113 ns
  StructuredTaskScope.open() + fork(() -> 1L) + join()   3 582 961 ps/op  3.58 µs

  --- the references already in this ledger ---
  Mezze  scoped, EMPTY                                                      ~62 ns
  Java   newVirtualThreadPerTaskExecutor() open + close                     264 ns
  Java   Thread.ofVirtual() start + join                                   4.68 µs
```

**For the scope itself, adopting it would be a REGRESSION.** Ours is ~62 ns
against `StructuredTaskScope`'s 113 — we are already 1.8x faster than the JDK's
structured-concurrency construct and 4x faster than the executor-based one.
There is no performance case for replacing `AsyncNatives.scoped`.

**The one number that IS interesting is on the other path.** `StructuredTaskScope`
runs scope-plus-one-task in 3.58 µs — *faster than raw* `Thread.ofVirtual()`
start+join at 4.68 µs, so its fork/join route is better than the primitive it
is built on. Against Mezze's `spawn + await` at ~10-13 µs that suggests roughly
3x available. But that is the SPAWN/AWAIT path, not `scoped`, and our 10-13 µs
is the weakly-supported figure from two agreeing sizes that could not be pushed
further. **Do not act on that gap until spawn + await has had the convergence
treatment** — that is precisely the step whose absence produced the retraction
above.

#### The blocker is practical, and it was confirmed rather than assumed

Reflection said the class was present and carried no preview annotation:

```
  FOUND   java.util.concurrent.StructuredTaskScope
          annotated-preview=false  methods=8
```

`javac` disagreed:

```
  error: StructuredTaskScope is a preview API and is disabled by default.
    (use --enable-preview to enable preview APIs)
```

So the annotation check was a FALSE NEGATIVE and only trying to compile it
settled the question — the same shape of error as trusting a benchmark's default
`n`. It needs `--enable-preview` at compile AND run time, and preview class
files are pinned to the exact JDK that produced them. For a runtime that ships
a jar and a native image (`project_mez_distribution_pipeline`) that is a
distribution constraint, not a detail, and preview APIs under native-image are
further risk on top of the SVM blocklist issues this repo already tracks.

#### And a semantics mismatch that matters more than the flags

`StructuredTaskScope` requires `fork` on the scope-owning thread and `join()`
before `close()`. Our `scoped` hands a `Scope` VALUE to guest code, which can
spawn from it (`Async.scope_spawn`) and is not constrained to the owning thread.
Adopting STS would narrow that contract and could break guest programs that
rely on the looser model.

#### Verdict

**No, not now, and not for the scope's cost.** Revisit when it leaves preview,
at which point the argument stops being "go faster" — it cannot be — and
becomes "delete concurrency code we should not own". That argument has some
force: `AsyncNatives.scoped` hand-rolls parent/child linkage for transitive
cancellation (#252), cancel-all on four exception paths, and await-all on exit,
and this file already has a history of cancellation and per-thread-state bugs.
Recorded so the comparison does not have to be re-derived.

### LEDGER UPDATE 2026-11-07 — EIGHT handler installs permanently cost the whole process 31x on every effect op

The biggest ratio left on the board was "install a handler per iteration,
90 µs, 240x". Its attributed cause was *"it invalidates the chain-stable
assumption on every push and pop, so it is paying deoptimisation"*. **That cause
was wrong, and what is actually happening is worse and much more general.**

`StabilityAssumption` gives up for good:

```java
  static final int CHURN_LIMIT = 8;
  synchronized Assumption get() {
      if (unstable) return Assumption.NEVER_VALID;   // forever
      ...
  }
  synchronized void invalidate() {
      ...
      if (++churns >= CHURN_LIMIT) unstable = true;
  }
```

So there is no ongoing churn to pay — after eight cycles the assumption is
simply dead. And `HANDLER_CHAIN_STABLE` is a `private static final`:
**process-global.** `resetStabilityForTests` is the only way back and its own
javadoc says what it is for — *"For tests that exercise the cached path: the
assumptions are process-global, and earlier tests in the same JVM can have
pushed an effect past its churn limit."* It has no callers in `src/main`.

#### Measured, and it is 31x

Kernel 9 is new: churn the assumption past its limit (32 install/uninstall
pairs, four times the limit), then measure exactly what kernel 3 measures — one
op per iteration, handler installed ONCE outside the loop.

```
  kernel 3  one op, pristine process         826 ps/iter   (min of 9)
  kernel 9  the SAME op, after 32 installs 25 118 ps/iter   (min of 6)
```

**31x, permanently, for the life of the process.** Kernel 9's readings are
tight — 25 118 to 26 368, an 8% spread with no warmup outliers — so that is a
stable steady state, not a measurement artefact. Any server installing a handler
per request trips this on its eighth request, and every effect operation it
performs afterwards runs 31x slower.

#### The churn limit earns its place, which is why this is a trade and not a bug

A/B with `CHURN_LIMIT` raised to 1e9 on one build, three processes per kernel:

```
  kernel                        limit=8    limit disabled
  3  one op, pristine               826         897        unchanged (control)
  9  the same op after churn     25 384         948        27x FASTER
  5  install per iteration       85 627     208 362        2.4x SLOWER
```

So the limit is doing real work: without it, a loop that installs a handler
every iteration deoptimises compiled code forever and gets 2.4x worse. The
design is right about the loop and wrong about the aftermath.

#### ATTEMPTED FIX, MEASURED, REVERTED

A 50 ms cool-off: keep the limit, but forgive churn once it stops — stamping
`lastChangeNanos` on EVERY change (including while backed off) so a churning
loop keeps refreshing it and never recovers mid-loop, while a quiet process
does.

```
  kernel                     limit=8    cool-off     verdict
  3  one op, pristine            826         837     unchanged
  9  the same op after churn  25 384      25 355     NO RECOVERY
  5  install per iteration    85 627     145 460     1.7x WORSE
```

**Why kernel 9 did not recover, which is the part worth keeping.** `get()` is
called at SPECIALIZATION time, from `@Cached("getHandlerStableAssumption()")` —
not per operation. Once `EffectBinding` has specialized to `doGeneric` because
`get()` returned `NEVER_VALID`, the DSL never calls `get()` again: `doGeneric`
carries no guard, matches forever, and nothing re-asks. **A cool-off on the
assumption object cannot help a node that has already stopped consulting it.**
That is a fact about the Truffle specialization lifecycle, and it rules out the
entire class of "make the assumption recover" fixes.

Why kernel 5 got 1.7x worse is NOT explained. The only change on that path is
two `System.nanoTime()` calls per iteration, ~50 ns against an 85 µs iteration,
which cannot account for 60 µs. Possibly inlining or the `synchronized` block;
recorded as unexplained rather than guessed at. Reverted, and the revert
re-measured at 863 / 25 118 / 85 930 — back to baseline.

#### The ranked next step, and it connects to work that landed today

A real fix has to make the uncached specialization notice recovery and
`transferToInterpreterAndInvalidate()` to re-specialize — more invasive than a
cool-off, and it reintroduces the deopt the limit exists to prevent.

**The better angle is to stop `EffectBinding` needing the GLOBAL assumption at
all.** Its comment explains why it uses the global one: *"resume/abort
singletons depend on the entire chain being empty, not just one effect's
handler."* But `CallIndirectScalar1.doFallThroughResume`, landed earlier today,
shows the call site can be guarded on IDENTITY with **no assumption** — once a
function instance is in hand the choice is already made. If `EffectBinding`
could resolve `resume`/`abort` the same way, churning one effect's handler would
cost that effect its per-effect assumption and nothing else, and the
process-global cliff would disappear. That is the shape to try next, and the
31x above is what it is worth.

#### FIXED, 2.6x — one redundant `assumptions` clause was making the tiers collapse instead of degrade

The entry above ranked "give `EffectBinding` an assumption that tracks what it
actually needs" as the next step, worth the 31x. **Kernel 10 re-ranked it before
any of that was built**, which is what it was added for: churn a DIFFERENT
effect and then perform, so the global assumption dies while the performed
effect's per-effect assumption stays intact.

```
  kernel 3   one op, pristine process               857 ps/iter
  kernel 10  one op, after churning ANOTHER effect  987        <- global assumption DEAD
  kernel 9   one op, after churning the SAME effect 24 795     <- per-effect assumption dead
```

So the global assumption's death costs ~15%, and the entire 31x is the
PER-EFFECT one. The `EffectBinding` angle was worth a seventh of what the
previous entry claimed for it. Measured before built.

#### The actual cause: a redundant assumption on a tier that already verifies

`PerformD` has three tiers, and the middle one carried both a guard and an
assumption:

```java
  @Specialization(assumptions = "effectStable",
                  guards = {"cachedHandler != null", "cachedTarget != null",
                            "resolveHandler(effectHash) == cachedHandler"},   // re-resolves and COMPARES
                  excludeForUncached = true)
  public static Object doCachedShared(...)
```

That guard re-resolves the installed handler and compares it on **every call** —
strictly stronger than an assumption that it has not changed. The assumption was
redundant for correctness, and its only effect was to remove this tier at
exactly the moment it becomes useful: `effectStable` dies after eight churns,
and both cached tiers vanished together, so every later perform of that effect
fell to `doGeneric`.

Dropping `assumptions = "effectStable"` from that one specialization makes the
tiers DEGRADE instead of collapsing:

- `doCachedSingleThreaded` — assumption only, no check. Every single-threaded
  program with a stable handler, and it must stay free.
- `doCachedShared` — cache plus a per-call probe and reference compare. Now
  reached when a second thread installs handlers **or** when the effect's
  handler has churned past the limit.
- `doGeneric` — only when the handler genuinely is not the cached one.

#### Measured on a quiet machine, interleaved, minima of nine

```
  kernel                                  before     after    verdict
  9  op after churning the SAME effect   24 795     9 521     2.6x
  5  install a handler per iteration     85 930    64 741     1.33x
  3  op, pristine                 (ctrl)    826       815     flat
  10 op after churning another   (ctrl)      987       837     flat
  7  chain of four handlers      (ctrl)      910       925     flat
```

Three controls flat, two affected rows improved. Kernel 5 improves as a
by-product: after its own churn, the performs inside it now take the verify tier
rather than the generic path.

**An earlier reading of this same change was void and discarded.** A run taken
at load average 23 — thirteen stray gradle processes — had every row including
all three controls 2-4x worse than baseline, kernel 5 worse than before the
change. The controls moving is what said so; the numbers above were retaken
after `./gradlew --stop` and waiting for load below 4.

#### Two test-method corrections, both caught rather than shipped

`EffectHandlerChurnCorrectnessTest` churns an effect past the limit and then
alternates two handlers that answer differently — the shape that would read a
stale cache if the surviving tier's guard were not load-bearing.

1. **The first version ran 32 iterations and passed with the guard REMOVED.** It
   never reached the tier at all: `excludeForUncached = true` means the uncached
   path skips these specializations, and a short program never specializes. The
   identical trap that let nine of ten `ResumeFallThroughSemanticsTest` cases
   pass earlier the same day. Loops raised to 2000.

2. **Claiming non-vacuity by removing the guard was still wrong**, and remains
   wrong at 2000 iterations. A SENTINEL settled coverage instead: making
   `doCachedShared` return a fixed value fails two of the five tests, so those
   two provably execute the changed tier. Removing the guard does not fail them
   because without it the DSL has nothing to select a cached instance on — the
   initializer stops being cached at all, and the result is
   correct-but-pointless rather than stale. The guard picks the right cached
   instance; the DSL degrades safely without it.

#### What is left on this row

The verify tier costs ~8.7 ns over the no-check tier (9 521 against 815), which
is a `ThreadLocal` probe plus `resolveHandler` walking the chain for the
innermost handler of that effect. That is the remaining gap between a churned
and a pristine program, and it is 2.6x better than the generic path it replaces
rather than closed. Narrowing it means making `resolveHandler` cheaper — a
per-effect slot rather than a scan — which is a separate change with its own
measurement.

### LEDGER UPDATE 2026-11-07 — MAP HAS ITS FIRST NUMBERS, and a 338x cliff when the base map is shared

PERF-LOG listed under UNKNOWN: *"Collections. Map · Set · SortedMap · SortedSet
· Deque · NonEmptyList, every op"*. `ds_matrix` covers seq and List; nothing
covered Map, which is in every program. `bench/cases/mapsurface` is new, with
the method the retraction above enforced: per-operation, a ratio to an
in-process control, one row per process, min over 9 passes after 4 warmups, a
checksum per row, **and every figure taken at two sizes of n and required to
converge**.

#### The lookup surface, converged at n=1e6 and n=4e6, 1 000 keys

```
  row                       n=1e6      n=4e6     taken as      x control
  map::get   (hit)          12 504     12 357     12.4 ns          5 800
  map::get   (miss)          8 145      8 978      8.6 ns          3 950
  map::contains_key          7 380      8 192      7.8 ns          3 660
  map::size  (O(1))          4 519      5 045      4.8 ns          2 100
  set::contains              6 494      7 308      6.9 ns          3 010
```

**The first thing to read here is `map::size`.** It returns a stored count — one
field load — and it costs **4.8 ns**. That is 22x the control, and 11x
`str::size`'s 416 ps, which is a Bytecode-DSL operation rather than a record-ABI
native. So ~4.8 ns is the per-call floor under EVERY map operation before any
hashing happens, and `map::get`'s 12.4 ns is only 2.6x that floor. The hash
probe is the smaller half of a map lookup.

That is the ledger's existing ABOVE FLOOR row — *"the record-ABI natives still
unported"* — with a number on it at last, and Map is the most valuable instance.

#### The insert surface, and a claim I had to correct before publishing it

`LongHashMapStorage.putLong` mutates its two arrays IN PLACE when this storage
is the owner, and CLONES both when it is not:

```java
  if (isOwner()) { workKeys = keys;         workValues = values; }
  else           { workKeys = keys.clone(); workValues = values.clone(); }
```

So there are two insert costs, and which one you get depends on whether anybody
else still holds the map:

```
  keys        insert along a CHAIN      insert from a SHARED base
              (rebind `m`; O(1))        (keep `m`; O(size))
    250            12 365 ps                    278 611 ps
  1 000            16 901                     1 305 284
  4 000            16 091                     5 407 531      <- 338x
```

**Building a map is O(1) per insert and NOT quadratic.** I measured the shared
row first, saw 1.11/1.31/1.35 ns per entry across the key sweep, and wrote down
"building an N-entry map is O(N²)". That was wrong: building rebinds `m`, which
is the owner path, and it is flat. Reading `putLong` is what corrected it,
before it was published.

What IS real is the cliff: **338x at 4 000 keys, and unbounded in map size, the
moment the base map is still referenced** — which is precisely the situation a
persistent map exists to support. `let m2 = map::insert { m = m, … }` while `m`
stays live is ordinary functional code, and it copies the whole table.

#### The Set comparison is STRUCK, not merely caveated

The set rows looked like the clean counter-example — flat and cheap whether
chained or branching, suggesting Map alone had the cliff. They measure nothing
of the kind. `LongHashSetStorage.addLong` opens with:

```java
  if (containsLongInternal(elem)) return this;
```

and the bench inserts elements the set ALREADY CONTAINS. So those rows are a
contains plus an early return, never an insert, while `map::insert` on a present
key must still update the value. The two are not comparable and the conclusion
"Set has no cliff" has no evidence behind it. The rows are relabelled
`set::insert PRESENT-key` so they cannot be misread again.

Whether Set would show the same cliff on genuinely new elements is **UNKNOWN**
and needs a row that does not change the set's size while inserting — which the
present design cannot express without more care.

#### Ranked

| row | state |
|---|---|
| the ~4.8 ns record-ABI floor under every map op | **ABOVE FLOOR, cause named.** `map::size` is one field load for 4.8 ns against `str::size`'s 416 ps as a BDSL operation. Porting the map natives to the scalar ABI is the lever, and it lifts every row at once |
| `map::insert` from a shared base | **ABOVE FLOOR, 338x at 4 000 keys, cause read from the code.** The owner optimisation does not share structure, so a branch copies the table. A structure that shares (HAMT or similar) is the real fix and is a datastructures change, not a tuning one |
| `map::insert` along a chain | **AT FLOOR-ish, O(1), ~16 ns**, sitting on the 4.8 ns native floor |
| `map::get` / `contains_key` / `set::contains` | **ABOVE FLOOR by the native floor**, 7.8-12.4 ns of which ~4.8 is call overhead |
| Set insert on NEW elements | **UNKNOWN** — the rows that looked like it measured an early return |
| SortedMap · SortedSet · Deque · NonEmptyList | **still UNKNOWN** |

#### `map::size` 4 022 -> 429 ps, 9.4x — parity with `str::size`, in two steps, and the first step looked like a failure

The entry above ranked the ~4.8 ns floor under every map operation as the next
target and attributed it to "the record-ABI natives still unported". **That
attribution was wrong, and checking it was the whole of the work.**

`__native_map_size` and `__native_string_size` are registered as IDENTICAL
scalar `NativeOp`s — one `OBJECT` param, `LONG` return, `pure = true` — and
NEITHER `MapNatives.size` nor `MezzeMap.size()` crosses a `@TruffleBoundary`.
The ABI is the same. What `str::size` has and `map::size` lacked is a dedicated
PE-transparent operation that `BytecodeCompiler` emits by name:

```java
  @Operation
  public static final class InlineStringSize {
      @Specialization
      public static long doSize(String s) { return (long) s.length(); }
  }
```

Four lines. `InlineMapSize` is its peer, with a `MezzeMap` and a `MezzeSet`
specialization, emitted at the three sites that already special-case
`__native_string_size` (the `{ m }` record form needed its own scan — the
existing single-field block looks for a field named `s`, which is the string
natives' parameter name).

#### Measured by INTERLEAVING TWO STANDALONE JARS, because the machine was not quiet

Another worktree was running its test suite — load 28 at the worst, top
processes `worktrees/type-soundness` gradle workers, not mine. A first reading
taken then showed every row 2-4x worse INCLUDING `map::get`, which cannot touch
this code path; that reading was discarded. Waiting for the load to clear was
not working, so the A/B was interleaved instead: `mez build` produces a
standalone jar, so each variant was kept as its own `.build-X` directory and the
two were run alternately. Drift then hits both equally, which is the rule this
ledger already had for kernels and applies just as well to builds.

```
  step                                     ps/op    control    verdict
  B  baseline                               4 022    208-219
  C  + InlineMapSize                        1 749    215-221    2.3x
  D  + cached count in MezzeMap               429    211-215    3.9x more
                                                                9.4x total
  str::size, for scale                        416              parity
```

Controls matched across every pair. `map::get` 13 026 against 12 793 and
`map::contains_key` 7 636 against 7 450 — flat within 2.5%, as they must be,
since neither reads the size. Checksum `sum=2000000000` unchanged. The ratio to
the in-process control went from **x2008 to x214**.

#### THE FIRST STEP WAS MEASURED AS A NULL RESULT AND NEARLY DISCARDED

Caching the count in `MezzeMap` was tried FIRST, on the hypothesis that
`storage.size()` could not devirtualise through a ten-implementation interface.
Interleaved against the unmodified build it read **3 992 against 4 024 — 0.8%**,
with controls matching. Refuted, and reverted.

Then `InlineMapSize` removed the generic native dispatch, and the same cached
count was worth **3.9x**. It was not a null result; it was masked. 4 ns of
dispatch sat in front of a ~1.2 ns interface call, and no A/B could see past it.

**The rule that earns: a null A/B is only valid in the regime it was measured
in.** Removing a dominant cost can make a previously-null change significant,
so a change refuted "on top of" something slow is worth re-testing once the slow
thing is gone. Recorded because the cached count was within one command of being
thrown away for good.

#### Correctness

Caching a count is sound only because every `MapStorage.size()` returns a stored
field, a constant, or `keys.length` over a **final** array — checked across all
ten implementations, with `SmallFlatMapStorage` and `LongKeyMapStorage` the two
`keys.length` cases and both `private final`.

`MapSizeInlineTest`, 11 tests, walking the storage progression
(Empty -> SingleEntry -> SmallFlat -> LongHash at 0/1/2/8/64 entries), insert of
a new and an existing key, remove present and absent, `Str` keys for a different
storage, `is_empty` riding on size, the Set path, a warm loop, and the
stale-count case — a base map kept live while a derived one is built, each
reporting its own count. **Coverage evidenced by a sentinel**: with
`InlineMapSize.doMap` returning a fixed value, 10 of the 11 fail, the eleventh
being the Set case that goes through `doSet`.

#### What is left on the map rows

`map::get` at 12.4 ns and `contains_key` at 7.8 ns still go through the generic
native path, and the ~4 ns this change removed from `size` is presumably in both
of them too. They cannot take the same fix as-is — they are two-argument and
have to hash the key — but the shape is now known to be worth ~4 ns, which
makes a typed two-argument inline operation for the lookups the next thing on
this row rather than a guess.

#### The map LOOKUPS, 1.22-1.46x — and the ordering lesson repaid in both directions

The entry above left `map::get` at 12.4 ns and `contains_key` at 7.8 ns, noting
that "the ~4 ns this change removed from `size` is presumably in both of them
too". It is. `InlineMapContainsKey` and `InlineMapGet` are the two-operand peers
of `InlineMapSize`, emitted at the record form and in the existing arity-2
block.

```
  row                          before      after    verdict
  map::get   (hit)            13 253     10 827     1.22x
  map::get   (miss)            8 388      6 545     1.28x
  map::contains_key            7 550      5 166     1.46x
  set::contains                6 473      4 783     1.35x
  map::size          (control)   447        479     flat
```

Interleaved against a saved baseline jar, controls matching at 207-234
throughout.

`get` gains least and that is expected rather than disappointing: it also
allocates a `MezzeVariant` for the `Some`, which cannot go away while the
signature returns `Option v`, and on a miss it probes twice — `map.get` then
`map.containsKey` — to tell a stored value from an absent key.

#### THE SAME ORDERING MISTAKE, MADE AGAIN, AND THEN USED PROPERLY

The first move on these rows was to drop the `@TruffleBoundary` from
`MapNatives.containsKey`, reasoning from the fact that `size`/`isEmpty` carry
none and are far cheaper. Interleaved: **7 605 against 7 444 — nothing.** The
boundary was not the cost, because the generic dispatch in front of it was,
which is precisely what had happened to the cached count on `size` one entry
earlier.

So the rule got applied in the other direction too. With the dispatch removed by
these operations, the boundary removal was **re-tested on top**: 5 189 against
5 151, **0.7%**. So it is genuinely not the cost even in the regime where it
could have been, and the boundary stays — dropping one for no measured gain only
adds SVM risk for nothing.

That is the complete form of the lesson, and worth stating once:

- a null result measured *behind* a dominant cost is **masked, not refuted** —
  `cachedSize` was 0.8% before `InlineMapSize` and 3.9x after;
- a null result measured *after* the dominant cost is gone is **refuted** — the
  boundary was 0.7% with the dispatch out, and that is the end of it.

The only way to tell the two apart is to re-run the experiment in the second
regime, which costs one build.

#### Coverage, and why it took two sentinels to state honestly

`MapLookupInlineTest`, 9 tests: present and absent keys, `get` returning the
right value rather than a neighbour's, the storage progression at 0/1/2/8/64
entries both for hits and for misses, `Str` keys for a different storage, the
Set path, and a warm loop.

A sentinel returning `None`/`false` failed only **5 of 9** — because those are
the expected answers in the negative tests, so the sentinel agreed with them by
accident. Inverting it to `Some(777)`/`true` failed **7**. The union covers
**8 of 9**; the ninth is the Set case, which goes through the `doSet`
specialization that was never sentineled. Reporting the first number alone would
have understated the coverage and reporting it as "all 9" would have overstated
it.

#### Where the map surface stands

```
  op                   before today    now      total
  map::size                  4 022     429      9.4x
  map::contains_key          7 550   5 166      1.46x
  set::contains              6 473   4 783      1.35x
  map::get (hit)            13 253  10 827      1.22x
  map::get (miss)            8 388   6 545      1.28x
  map::insert (chain)       16 091  16 091      untouched, O(1)
  map::insert (shared base)  5.41 µs at 4k keys untouched, O(size)
```

**NEXT on this row: the shared-base insert cliff**, which is now the largest
thing left on the map surface by a wide margin — 5.41 µs at 4 000 keys against
16 ns along the owner chain, and unbounded in map size. That is a
datastructures change (a structure that shares, rather than an owner token that
copies on branch), not a dispatch one, so none of today's levers apply to it.

#### The inserts finish the family — 1.20x and 1.12x, and one of them nearly failed its own bar

`InlineMapInsert` (three operands) and `InlineSetInsert` (two) were the last
members of the map surface still on the generic native path — a scalar
`NativeOp` registration with no `BytecodeCompiler` special case, the exact
position `contains_key` was in.

```
  row                                 before      after    verdict
  map::insert, owner chain           16 189     13 535     1.20x
  set::insert, owner chain            7 072      6 292     1.12x
  map::insert, SHARED base          1.43 µs    1.46 µs     unchanged
  map::contains_key       (control)   5 197      5 436     flat
  map::size               (control)     710        683     flat
```

**The shared-base row not moving is the prediction holding, not a
disappointment.** Its cost is `LongHashMapStorage.putLong` cloning both arrays
when `ownerToken[0] != this`, and no amount of dispatch removal reaches a copy.

#### The first baseline was wrong, and the control said so

The first interleave compared against a jar saved before the LOOKUPS commit, so
`contains_key` read 5 430 against 7 996 — a difference that belonged to the
previous change, not this one, which also meant the run had no valid control.
Both files were restored from HEAD, a proper baseline jar built, and the
measurement redone. The only reason this was caught is that the control moved
when it had no business moving.

#### `set::insert` nearly failed its own bar

Two interleaved pairs read 1.05x, which is inside what this bench resolves, and
the standing rule from the reverted SIMD attempt is that an unmeasurable change
which adds a code path is not worth shipping. Five pairs settled it:

```
  ins   6 706  6 292  6 548  6 451  6 661
  head  7 072  7 327  7 223  7 277  7 352
```

Every `ins` reading below every `head` reading, no overlap. That is a
separation, not a mean difference inside the noise, so 1.12x is real and the
operation stays. Worth recording as the shape of evidence that turns a
borderline number into a claim: not a tighter average, but two sets that do not
intersect.

#### Coverage, stated as the sentinel actually supports

`MapInsertInlineTest`, 9 tests: insert-then-get round trip, the value not being
usable as a key (which a swapped key/value operand order would break), replacing
an existing key, the storage progression at 64 entries, the owner/branch split
with a base kept live, two derived maps not seeing each other, `Str` keys, the
Set path, and a warm loop.

A sentinel making both inserts return the container unchanged fails **7 of 9**.
The two it does not fail are NEGATIVE assertions — "base does not contain 3",
"the value is not usable as a key" — which an insert that did nothing also
satisfies. That is a property of those assertions, not a coverage gap, and the
other seven prove the operations execute.

#### The map surface, complete for dispatch

```
  op                    before    now     total
  map::size              4 022     429     9.4x
  map::contains_key      7 550   5 166     1.46x
  set::contains          6 473   4 783     1.35x
  map::get (miss)        8 388   6 545     1.28x
  map::get (hit)        13 253  10 827     1.22x
  map::insert (chain)   16 189  13 535     1.20x
  set::insert (chain)    7 072   6 292     1.12x
  map::insert (shared)  5.41 µs at 4k keys — UNTOUCHED
```

Every map and set operation the bench covers is now off the generic dispatch
path, and the remaining gap on this surface is a single row: **the shared-base
insert cliff**, 5.41 µs at 4 000 keys against 16 ns along the owner chain, and
unbounded in map size. It is a datastructures change — a structure that shares,
rather than an owner token that copies on branch — and none of the seven wins
above are a precedent for it.

### LEDGER UPDATE 2026-11-07 — the churned effect tier, 2.53x, and a boundary that is load-bearing in the GOOD direction

The entry above closed with its own next step: *"The verify tier costs ~8.7 ns
over the no-check tier (9 521 against 815) ... Narrowing it means making
`resolveHandler` cheaper — a per-effect slot rather than a scan, which is a
separate change with its own measurement."* This is that measurement, and the
first two guesses about where the 8.7 ns lives were both wrong.

Why this row matters more than its position in the table suggests:
`StabilityAssumption.CHURN_LIMIT` is 8 and the back-off is permanent, so a
process that installs more than eight handlers over its whole life — which is
every real program — spends the rest of its life in this tier. It is not an
edge case; it is the steady state.

#### Guess one: the boundary. REFUTED, and expensively so

`PerformD.doCachedShared` calls `resolveHandler(effectHash)` **in its guard**,
on every op, and that method is `@TruffleBoundary`. A boundary crossing per
effect operation is a plausible ~8 ns, so the first experiment removed it from
`resolveHandler` and from `peekHandlerForEffect` underneath.

```
  kernel 9, op after churn    head  9 769      no boundary  56 862     5.8x WORSE
```

Not a null result — a **5.8x regression**. The annotation was not a cost being
paid, it was a cost being avoided: it keeps a `ThreadLocal` probe, a stack scan
and `Hash.equals` out of the compiled graph at every perform site. Removing it
hands all of that to the host inliner at each one.

That is a third category to go beside the two the previous entry recorded. A
null result behind a dominant cost is masked; a null result after the dominant
cost is gone is refuted; and **a boundary can be load-bearing in the direction
you did not test** — the experiment that removes one has to be able to come
back worse, not just flat.

#### Guess two: bound the prize before building anything

With the boundary staying, the remaining candidates are all inside the method:
`STATE.get()`, the walk down the handler stack, and `Hash.equals` — which
matters more than it looks, because `Hash` is a data class wrapping a 128-char
hex string. Its `equals` fast-rejects on a precomputed int, but on a MATCH —
the case the hot path always takes — it falls through to a full `String.equals`.

Rather than guess which, a measurement-only probe replaced the whole body with
a static one-entry identity memo (unsound: it ignores the chain ever changing,
and was never going to be committed):

```
  kernel 9    real body  9 768      body replaced by a memo  1 723
```

So ~8 of the ~9 ns is the BODY and under 1 ns is the crossing plus the guard
structure. One build, and it turned "make `resolveHandler` cheaper" from a
direction into a number.

#### The sound form of the probe

A one-entry memo on `EffectState` — per-thread, so no cross-thread hazard —
keyed on `(effect hash IDENTITY, depth, generation)`.

The key is sound because **every site that changes chain contents also changes
depth, generation or both.** That was audited rather than assumed: every write
to `handlers[]`/`effectHashes[]` is paired with a depth change, and the two
depth changes that skip the generation bump (`clearHandlerChain`,
`popHandlerChain`) both move depth. It is the same invariant
`snapshotHandlerChainEntries` immediately above already relies on, which is why
it was the natural key to reach for.

Identity rather than equality on the hash is deliberate: a different-but-equal
`Hash` object misses and rescans, which is slower and never wrong. A null
result is memoised too — "no handler in scope" is the answer the guard needs
most cheaply when it is about to fall through to the generic path.

```
  kernel                                  before     after    verdict
  9  op after churning the SAME effect    9 719     3 841     2.53x
  3  op, pristine                 (ctrl)    820       849     flat
  7  chain of four handlers       (ctrl)     945       877     flat
  10 op after churning another    (ctrl)     893       839     flat
  5  install a handler per iteration     51 724    53 985     4.4% WORSE
```

Checksums identical (`sum=2000001000000`), so the memo is not answering faster
by answering differently.

#### Kernel 5 is the MISS control, and its 4.4% is real

Kernel 5 installs a handler every iteration, so the generation always differs
and the memo **can never hit**: it pays the four field writes and the key
comparison, and gets nothing back. That makes it the miss-path control this
change needed, already in the case — no new kernel required.

It was tempting to file 4.4% as noise, which is what the first six-reading run
would have supported. Twelve readings in each of two separate runs agree on
both sign and size (5.0% then 4.4%), so it is a real cost and is recorded as
the price of the row rather than hidden inside it: **2.53x on the steady state
of every long-lived process, against 4.4% on a loop that installs a handler
every iteration.** 4.4% of 51.7 ns is ~2.3 ns, which is more than four field
writes should cost, so part of it is the extra branch and part is presumably
`EffectState` growing; that split was not measured and is not claimed.

#### A control scare that was undersampling, and the rule it produces

The first run of this A/B read kernel 3 at **+16%** and kernel 7 at **+24%** —
two controls moving, which by the standing rule voids the table. The obvious
story was available and wrong: the memo adds four fields to `EffectState`, so
"cache layout perturbation on the pristine path" would have explained it
nicely.

Twelve readings each instead of six:

```
  kernel 3   +16%  ->  +3.5%
  kernel 7   +24%  ->   -7%      (now FASTER, opposite sign)
```

Both inside noise, and kernel 7 changed sign — which is what undersampling
looks like, not what a layout effect looks like. These controls run at ~820
ps/iter; a 15% difference there is 120 picoseconds, and six process-level
readings cannot resolve it. **The rule: the number of readings a row needs
scales with how small the row is.** The map rows at 5-16 ns were settled by
five interleaved pairs; sub-nanosecond controls need twelve, and the previous
entry's "min of nine" was already near the floor for them.

#### Coverage, stated as the sentinel supports it

`HandlerMemoCorrectnessTest`, 7 tests. The shape that would break is shadowing:
an inner handler for the same effect pushes, is memoised, pops, and the outer
perform must re-resolve rather than read the inner answer back. Three tests put
an outer operation, an inner one and a second outer one in a single extent;
one of them warm, because the compiled path is where the memo is supposed to
pay. The rest cover sequential extents with different handlers, two effects
alternating (which a one-entry memo thrashes — correctness must not depend on
it hitting), a chain of four effects, and re-entering one handler repeatedly.

A sentinel dropping `depth` and `generation` from the key — a memo that always
hits once warm — fails **4 of 7**. The three it does not fail install exactly
ONE handler for the effect they query, so a stale entry holds the right answer
anyway. That count was guessed as five before it was run, and the comment in
the test file now states four.

Unlike `EffectHandlerChurnCorrectnessTest` these need no 2000-iteration loops:
the memo is read by `YieldDispatch` on the generic path as well as by the cached
tiers' guard, so a short program reaches it.

#### What is left on this row, ranked — and why the previous entry's ruling-out needs amending

3 841 against a pristine 820 is still **4.7x**, and there are two levers of
very different size.

**First, and worth the whole 4.7x: let a quiet program go BACK to the free
tier.** The entry above ruled out the entire class of "make the assumption
recover" on the grounds that `get()` is called at specialization time only, so
a node that has stopped consulting an assumption cannot notice it coming back.
That reasoning was about `EffectBinding.doGeneric`. For `PerformD` the
conclusion still holds but **the cause is now different, and it is a cause this
very row created**: `StabilityAssumption.reset()` already exists ("start over,
as if never churned", no callers in `src/main`) and it invalidates the cyclic
assumption first precisely so that anything holding it is knocked out. But
`doCachedShared` deliberately holds NO assumption any more — that is what made
it survive churn and win 2.6x — so `reset()` cannot dislodge it, it keeps
matching, and the DSL never re-specializes to the tier that needs no check.
**A working cheaper-but-not-cheapest tier is now the thing blocking recovery.**

The shape that fixes it: give `doCachedShared` a SECOND assumption that churn
does not invalidate and recovery does. Then `reset()` drops the churned tier,
the DSL re-specializes, `getEffectStableAssumption` hands out a fresh valid
assumption, and dispatch lands back in `doCachedSingleThreaded` at 820.

And the trigger is already in place as of this entry: **the memo is a
quiescence detector.** A memo HIT means depth and generation have not moved, so
N consecutive hits for an effect is proof the chain has been stable for N
operations. Counting them costs a field increment on per-thread state already
behind the boundary, and the mechanism is self-limiting — a kernel-5-style loop
that installs every iteration never hits, so the counter never arms and there
is no tier thrash to pay for.

**Second, worth about 1.9x: drop the `ThreadLocal` probe.** The probe used
STATIC fields and read 1 723 where the sound version reads per-thread state via
`STATE.get()`, so roughly 2 of the remaining 3 ns is the probe. The lever is
per-thread state without one — a Truffle `ContextThreadLocal`, or an immutable
single-entry memo published in a static and validated with
`Thread.currentThread()` (an intrinsic). The latter needs the memo object to be
immutable with final fields, because a torn pair of statics across two threads
would compare one thread's depth against another's state; that in turn means an
allocation per MISS, which is why it must be measured against kernel 5 and not
only kernel 9.

Neither is a precedent for the other, and the first should go first: it ends
the row rather than narrowing it.

### LEDGER UPDATE 2026-11-07 — a quiet effect gets its free tier back, 3.32x, and a ruled-out class reopened because its CAUSE had changed

The entry above ranked this first and predicted it was worth the whole
remaining 4.7x. It is worth 3.32x of it, which takes the churned effect
operation from 3 814 ps to **1 149** against a pristine 814 — and the whole row
today from 9 719 to 1 149, **8.5x**, or 21.9x against the 25 118 it read before
`doCachedShared` stopped carrying `effectStable`.

```
  kernel                                  before     after    verdict
  9  op after churning the SAME effect    3 814     1 149     3.32x
  3  op, pristine                 (ctrl)     816       814     flat
  10 op after churning another    (ctrl)     807       830     flat
  5  install a handler per iteration     55 218    55 900     flat
```

Fifteen readings per row for the sub-nanosecond controls, interleaved, against a
baseline jar rebuilt from the memo commit rather than reused — the previous
entry's wrong-baseline mistake was one commit old at the time.

#### A ruled-out class, reopened, because the cause it named had stopped being the binding one

The entry before this one closed the door firmly: *"A cool-off on the assumption
object cannot help a node that has already stopped consulting it. That is a fact
about the Truffle specialization lifecycle, and it rules out the entire class of
'make the assumption recover' fixes."*

The fact is true and the ruling-out was still wrong, for a reason worth keeping:
**the cause it named was no longer the binding constraint, and the fix on this
very row is what changed that.** `StabilityAssumption.reset()` already existed
("start over, as if never churned", no callers in `src/main`), and it already
invalidated the cyclic assumption FIRST precisely so that anything holding it
would be knocked out. What broke was subtler: `doCachedShared` had deliberately
stopped carrying any assumption — that is exactly what let it survive churn and
won 2.6x — so a reset had nothing to dislodge. The tier kept matching, the DSL
never re-entered its slow path, and `get()` was never re-asked.

**A working cheaper-but-not-cheapest tier was itself the thing blocking
recovery.** That is not a fact about Truffle's lifecycle; it is a consequence of
a local design choice, and it was reversible.

The lesson is about ruling-out, not about effects: *a class is only closed as
tightly as the cause named in the closing argument, and a later fix can
invalidate that cause without anyone revisiting the conclusion.* This one sat
closed for exactly one commit.

#### The fix, in two parts, and ATTRIBUTION BY MEASUREMENT not argument

1. `doCachedShared` carries `churnedTier`, a second per-effect assumption that
   churn never invalidates and `reset()` does. Dropping that tier is what forces
   the re-specialization that re-asks for a fresh `effectStable`.
2. The trigger is the memo landed one commit ago: a memo HIT proves depth and
   generation have not moved, so a run of `RECOVERY_HITS` (65 536) hits proves
   the chain has been stable for that many operations. One increment on
   per-thread state already behind a boundary.

The claim in part 1 is the whole argument of this entry, so it was measured
rather than asserted. A third build kept the trigger and removed ONLY the
assumption clause:

```
  kernel 9    memo baseline        3 849
              trigger ONLY         4 585     WORSE
              trigger + gate       1 149     3.32x
```

**Trigger-only is worse than doing nothing.** Without an assumption to
invalidate, `reset()` dislodges no dispatch and the deoptimization it performs is
pure cost. So part 1 is not an optimisation of part 2, it is the thing that
makes part 2 mean anything — and this also retroactively explains the shape of
the cool-off attempt two entries ago, which measured no recovery AND a
regression. It was trigger-only.

#### Self-limiting by construction, which is what makes it safe

The reason the churn limit exists is that invalidating assumptions in a loop
deoptimises compiled code forever — without the limit, kernel 5 is 2.4x worse.
A recovery mechanism could easily reintroduce exactly that by oscillating.
This one cannot, and not because a threshold was tuned: a loop that installs a
handler every iteration moves the generation every iteration, so the memo never
hits, the counter never arms, and no reset ever happens. Kernel 5 flat at
55 218 / 55 900 is that prediction holding, and
`a loop that installs every iteration never arms recovery` is the test for it.

#### Coverage, and the part these tests DO NOT cover

`EffectChurnRecoveryTest`, 4 tests: a quiet effect recovers after churning past
the limit; a 100-operation quiet phase does NOT arm recovery; a loop installing
every iteration never arms it; and the answers stay right across the transition
including when a different handler is installed afterwards.

The loops are long for two separate reasons, both previously learned:
`invalidate()` returns early unless the assumption was actually handed out, and
it is handed out at SPECIALIZATION time — so a program that never warms up never
increments the churn counter and never gives up at all. The churn phase has to
be warm before it can churn, and the stable phase has to exceed the threshold.

A sentinel disabling the trigger fails **1 of 4** — the load-bearing one. The
other three are negative or correctness assertions that a disabled trigger also
satisfies, correctly so.

**And these tests do not cover part 1 at all.** They assert on
`givenUpCountForTests()`, which part 2 alone is enough to change: with the
assumption clause removed the reset still happens, the count still drops, and
all four still pass — while dispatch stays on the churned tier and the program
is 4 585 instead of 1 149. Recovery is invisible from inside the language; it
changes which tier runs, never an answer. So part 1's evidence is the
attribution table above and nothing else, and that is stated here rather than
left to look like test coverage it does not have.

#### The control-undersampling rule, confirmed a second time the same day

Nine readings put kernels 3 and 10 at **+18% and +17%** — two controls moving,
which voids the table. Fifteen readings: 816/814 and 807/830, both flat. This is
the second time today the same thing happened at the same magnitude on the same
rows, which promotes it from an observation to a property of this bench: **rows
at ~800 ps need fifteen readings; nine is not enough to resolve 15% there, and
the first run will offer you a plausible mechanism for the artefact.** Last time
the available story was cache layout; this time it would have been the extra
assumption check.

#### What is left on this row

1 149 against 814 is **1.41x**, and it is not fully attributed. Kernel 9 calls
its churn-then-run body six times, so each call re-churns and must re-earn its
65 536 hits before recovering; that plus the churn phase itself is the obvious
candidate but was not separated, and is not claimed. The other known lever is
the one this entry displaced: the memo's hit path still pays a `ThreadLocal`
probe, worth about 1.9x on the tier when it is being used, which now matters
only for the window before recovery and for programs that genuinely churn
forever. `PerformMutMutateAct` has the same `doCachedShared` shape and has NOT
been given the gate — deliberately, so that one measured change covers one
dispatch site; it is the cheapest remaining extension and needs its own number.

### LEDGER UPDATE 2026-11-07 — the ORDERED collections are 29-60x the hash ones, and a bounded 10-13x of it is one method

PERF-LOG has listed under UNKNOWN: *"Collections. Map · Set · SortedMap ·
SortedSet · Deque · NonEmptyList, every op"*. `mapsurface` closed Map and Set
and seven dispatch wins came straight out of it. `bench/cases/dsmore` is new
and closes SortedMap, SortedSet and Deque, with the same method: per
operation, a ratio to an in-process control, one row per process, min over 9
passes after 4 warmups, a checksum per row, **and every figure taken at two
sizes of n and required to converge.**

```
  row                            n=small    n=large    converged
  sorted_map::get (hit)          317 092    318 895    317 000
  sorted_map::contains_key       308 033    309 087    308 500
  sorted_map::insert (chain)     583 382    571 670    577 000
  sorted_map::size (NO cmp)        8 931      8 937      8 934
  sorted_set::contains           330 946    309 494    ~310 000
  sorted_set::add (chain)        303 742    309 527    307 000
  deque::size (NO cmp)             9 007      8 914      8 960
  deque::push_back (chain)        35 894     12 061    NOT CONVERGED
  deque::first                    12 300     12 252     12 276
  deque::get by index            220 170    223 304    222 000
```

`deque::push_back` is struck rather than reported: the row GROWS the deque by
n elements, so changing n changes the subject and the two readings are not
measuring the same operation. It needs a fixed-size formulation.

**NonEmptyList is still UNKNOWN**, deliberately: its only constructor is
`from_list: { xs } -> Option (NonEmptyList 'a)` and there is no NEL to return
in the `None` branch, so a row cannot be written without inventing one.

#### Against the hash equivalents, with the regime proved rather than assumed

Comparing two benches is the mistake the cross-kernel rule exists to catch, so
`mapsurface` was REBUILT with the same compiler and its rows retaken in the
same machine state. Both benches report the same control floor — 208-228 ps/op
in their own processes — which is the regime evidence.

```
  operation          hash      ordered     ratio
  get (hit)        10 640      317 000      29.8x
  contains_key      5 172      308 500      59.6x
  size                429        8 934      20.8x
```

#### `size` carries no comparator, which splits the cost in two

Every ordered operation is written like this:

```
  pub let get: { m: SortedMap 'k 'v, k: 'k } with Ord 'k -> Option 'v = { m, k } ->
    get_with_cmp { m = m, k = k, cmp = { a, b } -> if a < b then 0 - 1 else ... }
```

so each call allocates a comparator closure and the native calls back into
guest code once per comparison. `size` and `is_empty` have no `with Ord` and no
closure — which makes them the control for exactly that cost, and they still
read **20.8x** the hash `map::size`. So there are two independent costs:

- **a base cost of ~9 ns on every ordered and Deque operation**, with no
  comparator anywhere near it. These natives are registered in the record-ABI
  `HANDLERS` map — a **string-keyed HashMap lookup per call** — and 179 natives
  across these four modules have ZERO `BytecodeCompiler` special cases. That is
  the exact position `map::size` was in at 4 022 ps before its inline operation
  took it to 429.
- **~300 ns on top of that for the ordered lookups**, which is the comparator.

#### The comparator, and the prize bounded by measurement

`ElementComparator.compare` is `@TruffleBoundary` and, PER COMPARISON:

```java
Object[] closureValues = cmpFn.getClosureValues();
Object[] args = new Object[closureValues.length + 1];   // allocation
System.arraycopy(...);
args[closureValues.length] = MezzeRecord.wrapAB(a, b);  // allocation
Object result = cmpFn.getCallTarget().call(args);       // UNCACHED call
```

Two allocations and an uncached `CallTarget.call` — no `DirectCallNode`, no
inline cache, no splitting — about ten times per lookup on a 1 000-key map.

Guessing a cause has been refuted twice today, so this was bounded rather than
argued. An **unsound measurement-only probe** returned `Long.compare` directly
whenever both operands were Long, skipping the guest callback entirely (wrong
for any key type whose `Ord` is not canonical ascending; never committed):

```
  row                        before    callback skipped    bound
  sorted_map::get (hit)     317 000              30 000    10.6x
  sorted_map::contains_key  308 500              24 500    12.6x
  sorted_set::contains      310 000              24 400    12.7x
  sorted_map::insert chain  577 000             633 000    NO GAIN
```

Both probe columns converged across two sizes of n.

**`insert` not moving is the most useful number there.** It says the comparator
is the LOOKUPS' cost and not insert's, so `sorted_map::insert` at 43x the hash
insert is a separate problem — persistent path copying, or the
`map.withComparator(ElementComparator.of(cmpFn))` that every one of these
natives performs on entry — and the 10-13x lever does not touch it.

#### Why the fix is not the probe, and what it has to be instead

The probe is unsound because the comparator IS the user's `Ord` instance: a
newtype over Int with a reversed `Ord` has Long values at runtime, and a native
Long compare would silently ignore the ordering the program asked for.

It also cannot be fixed by making the callback faster in place. These natives
run as HOST code behind a boundary, so a guest callback from inside them can
never be inlined by Graal however it is called — ten callbacks per lookup will
always cost ten full Truffle calls. Caching a `DirectCallNode` and dropping the
two allocations is worth some of the 10x, not most of it.

So the win requires the comparisons not to call guest code at all, which means
the ordered structures must be able to obtain a NATIVE comparator for key types
whose `Ord` is the canonical one — decided from the `Ord` impl's identity,
which is available, and never from its name. That is a design change to how
ordered collections acquire their ordering, not a patch to `ElementComparator`,
and it is ranked first on this surface at 10-13x on every ordered lookup.

Second on the surface, and contained: the ~9 ns base cost. `sorted_map::size`,
`deque::size`, `deque::first`, `is_empty` and their peers are string-keyed
`HANDLERS` entries with no special case, which is precisely the lever that paid
1.2-9.4x seven times on `mapsurface`. It needs no design decision, only the
same work.

#### A correction to the entry above: the churn cliff is a SINGLE-THREADED-process phenomenon

`EffectChurnRecoveryTest` passed its gate and then failed the NEXT one, with
nothing about it changed. The only difference was ten unrelated tests added
elsewhere, which moved the execution order — so the earlier green was luck, and
the real behaviour is order-dependent through process-global state.

The cause is worth more than the test fix. An effect only gives up when
`StabilityAssumption.invalidate()` counts, and that returns early unless the
assumption was **handed out**. For an ordinary effect the only thing that ever
asks for it is `PerformD.doCachedSingleThreaded`'s `@Cached` initializer — and
that specialization also carries `singleThreaded`, a plain `Assumption`
invalidated FOREVER the first time a second thread touches handler state, with
no reset.

So in any process that has ever handled effects on two threads, `effectStable`
is never handed out, churn never counts, and **no effect can ever give up.**

That reframes the whole row rather than just the test:

- **a single-threaded process** is the one that hits the cliff, and it is the
  one the recovery work above returns to the free tier (3.32x, 1 149 against
  814);
- **a process that has ever used two threads for effects** is already on the
  verify tier permanently and can never leave it — for that one the
  handler-lookup memo is the whole of the win (2.53x), and recovery has nothing
  to recover from.

Both halves of today's effects work were needed, and they serve different
processes. Neither entry said so because neither knew it.

#### What that costs the tests, stated rather than papered over

Three of the four recovery tests now guard on the premise — they run the churn
phase alone, check it registered, and `assumeTrue` on that. Run in a clean JVM
all four execute and pass; in the full suite they are SKIPPED, because the
premise genuinely does not hold there.

That means **the gate does not verify the recovery mechanism**; it was verified
by running the class alone, which is how the 4/4 pass and the trigger sentinel's
1-of-4 were established. The positive test would otherwise have been worse than
useless: its assertion is "no effect is given up", which is exactly what a JVM
with no single-threaded effects satisfies for free — it would have passed
VACUOUSLY and looked like coverage.

This is the same class as the recorded `MezzeTestHelper`-static-typechecker
finding, and the same remedy: run the suspect ALONE. Making it gate-verifiable
would mean a reset for `SINGLE_THREADED_EFFECTS` or a forked JVM for this class,
and adding a reset for a `final` assumption purely so a test can see it is how
real invalidation bugs get masked.

**The codebase had already hit this and said so.** Listing the gate's skips to
check which were the new ones turned up, beside the three recovery tests,
`ConcurrentHandlerIsolationTest`'s *"a single-threaded program keeps the
single-threaded-effects assumption"* — skipping itself, in the same run, for
exactly the same reason. So the conclusion above is not an inference from one
afternoon's failure; it is a property of this process that an existing test
already documents by guarding against it.

#### The ordered containers' BASE cost, 20.5x — and `sorted_map::size` reaches parity with the hash one

The entry above ranked two levers on this surface and this is the second, the
contained one: `sorted_map::size`, `sorted_set::size`, `deque::size` and
`deque::first` were string-keyed `HANDLERS` entries with no `BytecodeCompiler`
special case. `InlineMapSize` gains three specializations and
`InlineDequeFirst` is new.

```
  row                            before     after    verdict
  sorted_map::size                8 540       416     20.5x
  deque::size                     8 865       420     21.1x
  deque::first                   12 343     5 478      2.25x
  sorted_map::get      (control) 308 200   311 896     flat
  sorted_map::contains (control) 306 855   306 626     flat
  sorted_map::insert   (control) 563 046   570 635     flat
  deque::get by index  (control) 218 442   219 823     flat
```

**`sorted_map::size` at 416 is parity with the hash `map::size` at 429**, which
is the right endpoint: both return a stored count, and the 20x was never
anything but the dispatch in front of it.

`deque::first` gains 2.25x rather than 20x, and that is expected: it also
allocates a `MezzeVariant` for the `Some`, which cannot go away while the
signature returns `Option a`. The same ceiling `map::get` ran into.

**The four controls stayed on the HANDLERS path deliberately** — they take a
comparator, so they are three-operand records this change does not touch. An
earlier note in this session predicted they would gain ~3% from the base cost
coming off; that was wrong, because the base cost only comes off an operation
that gets a special case, and these did not get one. Flat is the control
holding, not a missed win.

#### Coverage

`OrderedSizeInlineTest`, 10 tests. `InlineMapSize` now carries FIVE
specializations — map, set, sorted map, sorted set, deque — selected on the
receiver's runtime class, so a receiver reaching the wrong one would report
another container's size. One test puts three container kinds of different
sizes in one program with weights 1/10/100 so that any wrong answer changes the
total; the rest cover distinct-key counting, the growth progression at
0/1/2/64, both deque ends, shrinking after a pop, `first` against `push_front`
and `push_back`, `first` on empty, and a warm loop.

TWO sentinels, reported separately rather than combined:

- the three new size specializations returning 0 fails **7 of 10** — exactly
  the size tests;
- `deque::first` returning `last` instead fails **3** — the two ordering tests
  and the warm loop.

Union **9 of 10**. The tenth is `deque first on an empty deque is None`, which
both sentinels satisfy by accident because an empty container answers None
under either wrongness. That is a property of the assertion, not a gap.

#### Where this surface stands

```
  op                        before      now     total
  sorted_map::size           8 934      416     20.5x
  deque::size                8 960      420     21.1x
  deque::first              12 276    5 478      2.25x
  sorted_map::get          317 000  317 000     UNTOUCHED
  sorted_map::contains_key 308 500  308 500     UNTOUCHED
  sorted_set::contains     310 000  310 000     UNTOUCHED
  sorted_map::insert chain 577 000  577 000     UNTOUCHED
  deque::get by index      222 000  222 000     UNTOUCHED
```

So the comparator-free operations are done and everything that takes an `Ord`
is exactly where it was. **The ranked next item is unchanged and is the big
one**: the ordered lookups at 29-60x the hash equivalents, of which a
probe-bounded 10-13x is `ElementComparator.compare` calling back into guest
code once per comparison. It needs the ordered structures to obtain a native
comparator for key types whose `Ord` is canonical, decided from the impl's
identity and never its name — a design change through the monomorphiser, not a
patch. After that, `sorted_map::insert` at 43x the hash insert is its own
problem, and the probe proved the comparator is NOT its cause.

### LEDGER UPDATE 2026-11-07 — the ordered lookups, 18-20x, by moving the SEARCH out of the native

The two entries above ranked this first and bounded it with an unsound probe at
10-13x. It lands at **18.2x and 20.5x** — PAST the bound, which needs
explaining, and the explanation is the useful part.

```
  row                            before     after    verdict
  sorted_map::get (hit)         312 000    17 177     18.2x
  sorted_map::contains_key      308 500    15 030     20.5x
  sorted_set::contains (ctrl)   314 182   310 283     flat — STILL NATIVE
  sorted_map::insert   (ctrl)   583 610   588 176     flat
  deque::get by index  (ctrl)   219 110   219 360     flat
```

Both moved rows converged at two sizes of n (17 201/17 153 and 15 159/14 902).
`sorted_set::contains` is the control that matters most: the SAME operation on
the same kind of structure, left on the native search, and it did not move.

#### Why it beats the probe it was supposed to be bounded by

The probe skipped the guest callback by comparing Longs natively — but it was
still a HOST-code binary search, just with a cheap comparator. This change
moves the search itself into guest Mezze, where the whole loop compiles:
probes, comparisons, index arithmetic and recursion all become one compiled
guest method. The probe measured "the native search without callbacks"; this
measures "no native search at all".

`MezzeSortedMap` is a **sorted array**, so a lookup was always a binary search.
The 300 ns was never the comparator but WHERE the search ran: in host code
behind a `@TruffleBoundary`, every comparison had to call back out into guest
code, and Graal cannot inline a guest function into host code however the call
is made. Ten comparisons meant ten full Truffle calls plus two allocations
each.

It also removed two allocations per lookup that had nothing to do with
comparing: the native installed a fresh comparator on the map first, and
`withComparator` allocates a whole new map whenever the comparator differs —
which it always did, being built fresh at every call.

**The indexed probes had to be inline operations, as part of the design rather
than on top of it.** The search makes ~log2(n) probes per lookup and a probe
left on the string-keyed `HANDLERS` path costs ~9 ns, which would have exceeded
the 300 ns being removed.

#### PASSING THE COMPARISON AS AN ARGUMENT COST 45% OF THE WIN

The first working version took `lt` as a parameter so the closure could be
built where `Ord` was in scope. It measured **10.1x / 11.2x** — exactly the
probe's bound and no better. Removing that parameter, so the body calls `<`
directly, took the same rows to **18.2x / 20.5x**.

So nearly half the available win was in the difference between a comparison
reached through a function-typed parameter and one written inline. A closure
argument is a value the compiler must still get through; `<` in the body is a
call site it can see. Worth remembering the next time a helper is given a
callback "so it stays generic".

#### THE TYPECHECKER REFUSED THIS THREE TIMES, AND EACH REFUSAL TAUGHT SOMETHING

**One — a `do`-block `let` detaches the obligation.** The obvious spelling
binds the probed key first:

```
  let mk = key_at { m = m, i = mid }
  if mk < k then ... else if k < mk then ...
```

and the annotation is then refused: *"the annotation constrains `with Ord 'k`,
but the body applies Ord to a type the annotation does not name"*. A `do`-block
`let` GENERALIZES its binding, so `mk` becomes polymorphic and each use
instantiates it fresh; the `Ord` the comparison raises lands on that fresh
variable. Pinned down by reducing it: the same comparison typechecks fine
without the intermediate binding. So the search is written with `key_at` called
inside the comparison and `(lo + hi) / 2` spelled out rather than bound.

**Two — dropping the annotations is worse, and a gate said so.** Unannotated,
the module compiles and inference produces exactly the right type: checked
through a caller, `get` infers `m: SortedMap 'c Int, … Ord 'c`, identical to
the rejected annotation. But `ElaborationInvariantGateTest` then reports
`UNBOUND_TYPE_VAR let:get` — *"a node reached the end of elaboration with an
open type, so something downstream is guessing"*. The stray variable survives
to the end of elaboration. **That line was NOT added to the baseline.** The
gate is right: inferring the correct type and leaving a variable unbound are
different things, and only the first is safe.

**Three — an unannotated recursive helper returns a numeric VARIABLE.** With
`find_index` unannotated (annotated and recursive is refused by obstacle one),
its result is a numeric type variable rather than `Int`. `get` pins it by
accident, because the helper it feeds declares `idx: Int`; `contains_key` wrote
`find_index { … } >= 0` and the `>=` raised an Ord obligation on the unpinned
variable, which its annotation could not name. Fixed by pinning it explicitly.

The final shape is therefore: `find_index` unannotated, `get` and
`contains_key` annotated as before, and two small helpers whose only job is to
force `Int`. Every one of those decisions is a typechecker defect worked
around, documented in place, and none of them changes a public type.

#### Coverage

`SortedMapGuestSearchTest`, 10 tests. A hand-written binary search is wrong at
the edges if it is wrong anywhere: the empty map, one entry, keys
below/above/between stored keys, the first and last stored key where `lo` and
`hi` collapse, insertion over an existing key, `Str` keys, every key of a
64-entry map looked up individually so no index arithmetic can be off by one
anywhere in the range, and a warm loop. Two earn their place beyond that:

- **a user-defined DESCENDING `Ord`**, which pins the comparison DIRECTION. A
  reversed comparison still terminates and still finds keys under a symmetric
  ordering, so only a key type whose order is not the natural one tells them
  apart. It also demonstrates that this is not a primitive-only special case —
  a user comparator inlines like any other, which the unsound probe could never
  have offered.
- **the persistence guarantee**: `keyAt` revokes ownership exactly as the old
  native lookup did, because the caller has now observed the contents and a
  later insert must copy rather than mutate underneath them. The test reads the
  base map and THEN derives from it, the order that would corrupt the base if
  the revoke were dropped.

Two sentinels, both re-run against the final formulation: `find_index` never
finding fails **9 of 10**; the comparison direction reversed fails **8**, with
the empty map and the single-entry map surviving — the first because no
comparison happens, the second because `mid` lands on the key, so direction
cannot matter. Union **9 of 10**.

#### Old code deleted

`__native_sorted_map_get` and `__native_sorted_map_contains_key` are gone from
`NativeDispatch`, and `SortedMapNatives.containsKey` with them.
`SortedMapNatives.get` stays because `__native_sorted_map_update` still calls
it internally with a null comparator.

#### Where the ordered surface stands

```
  op                        before      now     total
  sorted_map::contains_key 308 500   15 030     20.5x
  sorted_map::size           8 934      416     20.5x
  deque::size                8 960      420     21.1x
  sorted_map::get          312 000   17 177     18.2x
  deque::first              12 276    5 478      2.25x
  sorted_set::contains     310 000  310 000     next — same fix, now a port
  sorted_map::insert chain 577 000  577 000     untouched
  deque::get by index      222 000  222 000     untouched
```

`sorted_map::get` is now 1.6x the hash `map::get` rather than 30x, which is
about what an ordered structure should cost. **Next: `sorted_set::contains`**,
the same operation over the same sorted array — a port, not a design, and the
patch for it is already written. Then `sorted_map::insert` at 43x the hash
insert, where the probe already proved the comparator is NOT the cause, so it
is path copying and a datastructures question. `deque::get by index` at 222 ns
is likely O(n) by construction rather than dispatch and needs its
representation checked before anything is claimed.

#### `sorted_set::contains`, 20.6x — the port, and the controls that proved the baseline

The entry above called this "a port, not a design", and it was: the same
`element_at` probe, the same do-block-free search, the same `Int` pin, the same
two deletions. It built and typechecked first time, which is the useful
confirmation that the three typechecker obstacles recorded above are understood
rather than merely worked around once.

```
  row                              before     after    verdict
  sorted_set::contains            309 600    15 060     20.6x
  sorted_map::get      (control)   17 190    17 187     exactly flat
  sorted_map::contains (control)   15 058    15 085     flat
  sorted_set::add      (control)  307 609   305 908     flat — still native
```

Converged at two sizes of n (15 120/14 995).

**The first measurement of this row was taken against the wrong baseline and
the controls are what caught it** — again. The saved baseline jar predated the
`sorted_map` commit by one, so it read 306 245 → 15 054 for the set while the
two `sorted_map` rows showed the *previous* commit's win rather than nothing.
Rebuilt from current HEAD, those two rows read 17 190/17 187 and 15 058/15 085
— identical in both columns, which is what a correct baseline looks like. The
set figure barely moved (20.3x → 20.6x), so the conclusion survived; the point
is that it was not known to be safe until the controls said so.

`sorted_set::add` still carries the old comparator-closure path and did not
move, which bounds the change to exactly the operation claimed.

#### Coverage

`SortedSetGuestSearchTest`, 9 tests: every element of a 64-element set tested
individually together with the 64 absent values just above the range (64 hits,
0 false positives, in one number), the empty set, one element, boundaries and
gaps, `Str` elements, a user-defined descending `Ord` to pin the direction, a
warm loop, and one test that puts a sorted MAP and a sorted SET in the same
program — `InlineSortedElementAt` reads the Set's `elements` while
`InlineSortedKeyAt` reads the Map's `keys`, so a receiver reaching the wrong
specialization would probe the wrong array, and the contents are chosen to
disagree so a crossed probe cannot accidentally be right.

Sentinels, as measured rather than as predicted: never finding fails **8 of 9**;
the direction reversed fails **6**. The three that survive reversal are the
empty set (no comparison happens), the single-element set (`mid` lands on the
element, so direction cannot matter) and the map/set test, whose set holds two
elements that happen to resolve the same way reversed. Union **8 of 9**. The
test file states these numbers; it originally guessed 7 and 5.

#### The ordered surface, as it now stands

```
  op                        before      now     total
  deque::size                8 960      420     21.1x
  sorted_map::contains_key 308 500   15 030     20.5x
  sorted_set::contains     309 600   15 060     20.6x
  sorted_map::size           8 934      416     20.5x
  sorted_map::get          312 000   17 177     18.2x
  deque::first              12 276    5 478      2.25x
  sorted_set::add          307 000  305 908     untouched
  sorted_map::insert chain 577 000  588 176     untouched
  deque::get by index      222 000  219 360     untouched
```

Every ordered LOOKUP is done. Against the hash equivalents the ratios went from
29-60x to 1.6-3.2x, which is about what an ordered structure should cost.

**What is left here, and it is no longer dispatch.** The three untouched rows
are all writes or indexing:

- `sorted_set::add` and `sorted_map::insert` still build a comparator closure
  per call and still call back into guest code per comparison — the same
  disease the lookups had, and the same cure applies in principle. But an
  insert also has to SHIFT the array, so the comparison is a smaller share of
  it: the probe two entries ago skipped every callback and `insert` did not
  move at all. Writing the search in guest code and then calling a native
  insert-at-index is the shape, and it needs its own measurement before it is
  worth claiming.
- `deque::get by index` at 222 ns is most likely O(n) by construction rather
  than dispatch; its representation needs reading before anything is said
  about it.

### LEDGER UPDATE 2026-11-07 — the write rows, measured by SLOPE: one of them found a soundness bug (BUG 38)

The entry above left three untouched rows and said their nature had to be
established before anything was claimed about them. Measuring each at 250,
1 000 and 4 000 keys settles all three, relabels two of them, and turned up a
silent wrong-value bug in the hash Map.

```
  row                        250 keys   1 000    4 000   per 4x    shape
  sorted_map::insert chain    339 715  605 126  2 171 071  3.59x   LINEAR
  sorted_set::add chain       235 989  308 860    390 235  1.26x   LOG
  deque::get by index          71 933  221 294    839 410  3.79x   LINEAR
```

#### Two rows were mislabelled, and the slope is what said so

Both write rows use `(i % keys) + 1` over a structure built with `1..keys`, so
**every operation is a REPLACE, not an insertion** — the same trap `mapsurface`
fell into with `set::insert`, caught there and not here. The labels now say
PRESENT.

It matters beyond honesty, because the two shapes then disagree for a reason:

- `sorted_set::add` of an element already present returns the set unchanged, so
  that row times the SEARCH and nothing else — hence logarithmic, and hence a
  candidate for the same guest-search treatment the lookups got.
- `sorted_map::insert` of a key already present is LINEAR, which is absurd for
  an operation that changes one value. That is the thread that led to BUG 38.

`deque::get by index` is O(n) by construction — 3.79x per 4x size. Not
dispatch, not a comparator: the representation. Nothing on this surface fixes
it.

#### The replace path, and an in-place fast path that measured 5.63x and was WRONG

`MezzeSortedMap.putObject`'s update-existing branch clones BOTH arrays
unconditionally, even when the map owns them — and its comment claims it clones
only values, which it does not. Meanwhile the new-key paths beside it have
owner fast paths that mutate in place.

Giving the replace the same fast path required a second fix first, and the
reason is worth keeping: every mutating native begins with
`map = map.withComparator(ElementComparator.of(cmpFn))`, a fresh comparator
never equals the stored one, and `withComparator` handed back a map with a NULL
owner token. **So the owner fast paths in `putObject` were unreachable from
guest code entirely** — dead for every insert any Mezze program has ever done.
Making `withComparator` MOVE the token, and making the copying branch claim
ownership of the arrays it had just allocated, made them reachable.

The first attempt measured nothing at all, which was the clue: the copy handed
back a non-owner, so a chain could never re-acquire ownership and the fast path
never fired once. With all three in place:

```
  keys    before        after     verdict
   250   339 715      240 986      1.41x
  1 000  605 126      317 309      1.91x
  4 000  2 171 071    385 300      5.63x
```

and the slope went from 3.59x per 4x size to **1.21-1.32x** — linear to
logarithmic, with the advantage unbounded in map size.

**It was reverted, because it is unsound.** An append writes OUTSIDE the base
map's visible extent, so a base that is still referenced cannot see it. A
replace writes INSIDE it. The test written to check exactly that failed with
`expected: <10> but was: <20>`: deriving a replace corrupted the map it was
derived from.

#### What that test then found in the Map that ships — BUG 38

The same test against the hash `Map` **fails**, with no change of mine involved:

```mezze
let base = fill { i = 64, m = map::empty {} }
let derived = map::insert { m = base, k = 30, v = 777 }
map::get { m = base, k = 30 }      ## prints 777, must print 300
```

`LongHashMapStorage`'s replace branch writes `workValues[idx]` while it owns the
arrays and hands the token on, so the base observes a value the program never
wrote to it. Filed as **BUG 38**, with the design's own comment quoted: every
clause of the ownership contract is about the old map MUTATING afterwards —
"it will clone" on its next write — and the case it never considers is the old
map being READ after the transfer. `getLong` does revoke on read, but the
corruption happened at the derivation.

So the token establishes "only one map may mutate these arrays". What a
persistent map needs is "no map may observe a mutation it did not make".

**AND IT MEANS A NUMBER IN THIS LEDGER RESTS ON IT.** `mapsurface` row 8, the
chain of replaces at 13 535 ps, is that fast *because* of the in-place write.
The three-line correct fix was written, verified to close the bug, and then
MEASURED rather than estimated:

```
  map::insert chain, keys already present   250 keys    1 000     4 000
    HEAD, in-place (unsound)                  13 128   13 702    16 326   O(1)
    clone on replace (correct)               391 765  1 420 019  5 795 685  O(n)
    cost of correctness                        29.8x    103.6x     355x
```

Thirty times at 250 keys, a hundred at a thousand, three hundred and fifty at
four thousand, and unbounded beyond. An earlier line in this entry guessed
50-100x; the real shape is that the ratio GROWS, because one side is O(1) and
the other O(n), so no single multiplier describes it. A sharing structure (HAMT, path-copying) gives a
correct O(log n) replace instead, and closes the shared-base insert cliff
(5.41 µs at 4 000 keys, unbounded) at the same time, because both are the same
missing property. **That is now the ranked next item, and the bug makes it
urgent rather than merely valuable.**

#### The method lesson, which cost nothing this time only by luck

The first version of that soundness test used a ONE-ENTRY base map and PASSED.
A one-entry Map is a `SingleEntry` storage that allocates on every put, so it
cannot mutate anything in place: the test could not reach the code it was
written to interrogate, and said "correct". Raising it to 64 entries — enough
to cross into the array storage — turned the same assertion red.

**A soundness test at a size that cannot reach the code under test proves
nothing, and reads exactly like a test that proves something.** The
storage-progression habit already in use for performance rows (0/1/2/8/64) is
not a thoroughness flourish; for structures that change representation with
size, it is the difference between a test and a decoration.

### LEDGER UPDATE 2026-11-07 — BUG 38's blast radius, and the cheap fix family killed by a GC count

Looking at BUG 38 properly made it bigger and then closed off every cheap fix.

#### The filing was wrong about the scope

It said inserting a NEW key was safe even with an in-place write, "because the
base's own `size` bounds what it can see". That is true of a sorted ARRAY. It is
false for a hash table: `getLong` probes the shared `keys` array by hash until
it meets `EMPTY_KEY`, and an in-place insert writes into the first empty slot of
exactly that probe sequence, so the base's probe walks straight onto it.

```
  derivation from a 64-entry base       base observes it?
  replace an existing key               YES
  insert a NEW key                      YES
  remove a key                          no
  size after a derived insert           no   (still 64)
```

So the base ends up SELF-INCONSISTENT — `size` 64 while `contains_key` says a
65th key is present. That is worse than one wrong value: the structure's own
invariants stop holding.

Eight storages carry the pattern — `{Long,String,Double,Object}HashMapStorage`
and the four Set equivalents. `Empty`/`SingleEntry`/`SmallFlat` allocate per put
and are correct, **which is why every small-size test of this passes
vacuously**, and two such tests were already in the tree asserting the right
thing at a size that cannot reach the code that gets it wrong.

Removal is safe for a reason worth recording: `removeLong` revokes ownership and
then clones unconditionally. **The two mutators disagree about the contract, and
the correct one is the one that does not take the fast path.**

#### The diff chain: correct, and dead

A persistent array without rerooting — each superseded storage records the one
slot the derivation overwrote plus a link to its successor, and a stale storage
reconstructs its own view once, cached. Implemented for the Long map and set.
**All twelve soundness tests pass with nothing disabled**, so the tests are
known satisfiable and not merely asserting a wish. Reads barely notice:
`map::get` +2%, `map::size` flat, `contains_key` +5.6%.

Then the write path:

```
  map::insert chain, 1 000 keys, 2M ops      HEAD       diff chain
    time                                   13 702 ps    66 722 ps   4.9x WORSE
    young collections                            18           24
    heap after a young collection             19 MB     2 571 MB
    young pause                             0.7-0.8 ms   500-590 ms
```

Nineteen megabytes surviving against two and a half gigabytes. **The base
retains the entire chain**, because the link points FORWARD: keep any old
version and you keep every version derived after it. The 4.9x is GC, not the
volatile write — and **the GC COUNT is what said so**. A timing-only reading of
this would have sent me hunting the volatile store, which is the plausible
suspect and the wrong one.

The leak is not an artefact of this encoding. Letting the derived version mutate
shared arrays obliges the old version to be able to reconstruct, and that needs
either forward links (retains successors, as measured) or an undo log
(unbounded, and retained by the CURRENT version, since that is the one
appending to it). Notifying every live old version instead is O(chain) per
write. **So the entire family is dead** — which is the useful part of the
result, because every cheap-looking fix belongs to it.

One candidate remains: **a HAMT**. No version references another, nothing to
undo, thread-safe by immutability, O(log32 n) everywhere. It also closes the
shared-base insert cliff (5.41 µs at 4 000 keys, unbounded), because that is the
same missing property.

#### What this cost and what it bought

Nothing shipped. What is recorded instead: the true blast radius, the fact that
the tests are satisfiable, a measured reason to stop looking for a cheap fix,
and the knowledge that `map::insert`'s 13.5 ns is only available to a structure
that mutates — so a HAMT's insert will be slower than it, and that is the
correct trade rather than a regression to apologise for.

### LEDGER UPDATE 2026-11-07 — the Map and Set become PERSISTENT: BUG 38 closed, and the 338x cliff with it

`LongHashMapStorage` and `LongHashSetStorage` are deleted. In their place
`LongTrieMapStorage` and `LongTrieSetStorage`: hash-array-mapped tries with
structural sharing, every field final, nothing ever overwritten.

This is the fix the previous entry argued was the only one left, after the
diff-chain family was measured and discarded.

#### What it cost and what it bought

```
  row (1 000 keys)                  table      trie     verdict
  map::get   (hit)                 10 640    13 460     1.27x slower
  map::get   (miss)                 6 545     8 223     1.26x slower
  map::contains_key                 5 172     7 545     1.46x slower
  map::size                           473       489     flat
  map::insert chain (replace)      13 702    44 704     3.26x slower
  set::contains                     4 783     4 765     flat
  set::insert PRESENT-key           7 072     8 171     1.16x slower
  set::add PRESENT, chain           6 292     6 286     flat

  map::insert from a SHARED base    250 keys    1 000      4 000
    table                           278 611  1 305 284  5 407 531
    trie                             32 251     44 816     63 164
    verdict                            8.6x        29x      85.6x FASTER
```

The shared-base slope went from linear — 4.7x then 4.1x per 4x the keys — to
**1.39x and 1.41x**, which is log32 doing what log32 does.

#### The number that says the structure is right

The trie's shared-base insert is **44 816** and its own chain insert is
**44 704**. They are the same operation now, because a trie has no notion of
owning its storage. The table they replace had a **95x spread** between those
two rows at 1 000 keys: it was fast when the program threw the base away, and
wrong when it kept it. A persistent map should cost the same either way, and
now it does.

That reframes the 3.26x on the chain insert. It is not a regression from 13 702
to 44 704 — it is the disappearance of a number that was only ever available to
a structure that mutates. The honest comparison for "insert into a map" is
44 704 against the old pair of 13 702-when-lucky and 1 305 284-when-not.

#### BUG 38, closed for Long keys

All twelve tests in `PersistentReplaceSoundnessTest` pass with **nothing
disabled** — the Map replace, the Map new-key insert and the Set new-key insert
that were `@Disabled` against this bug, plus the nine that were already
passing.

#### The trie earns trust from a differential, not from cases

`LongTrieMapStorageTest`, 18 tests. The load-bearing one is **random inserts,
replaces and removes against a `java.util.HashMap` kept in step** — four seeds,
8 000 operations each, the whole contents re-verified every 500 operations, and
a version snapshotted at operation 4 000 and re-checked at the end to prove it
never drifted. A new data structure earns trust by agreeing with one that is
already trusted; the named cases are there so that when the differential says
"seed 42, operation 4 071" something also says which shape broke.

The named cases cover the depths deliberately — 1, 2, 8, 31, 32, 33, 64, 1 000
and 5 000 entries, because 5 bits per level means a node spills at 32 and a bug
in the data/node split or the bitmap indexing appears exactly there.

#### Two things that fell out for free

**No collision nodes.** `mix` is the murmur3 64-bit finalizer, which is a
BIJECTION: distinct keys cannot share a full hash, so two keys travel together
only while their hashes agree and must part within 64 bits. Depth is bounded at
13 and the "same hash, different key" case a general HAMT needs collision nodes
for cannot arise. The branch that would handle it throws instead of looping.

**The sentinels are gone.** The table reserved `Long.MIN_VALUE` and
`MIN_VALUE+1` as empty/deleted markers, so a program using one as a key had its
whole map promoted to boxed storage. A trie stores no markers, so `isStorable`
is unconditionally true and that promotion no longer exists. There is a test
pinning it, because it is the kind of property that would be lost silently.

#### What is NOT fixed

BUG 38 affects eight storages. This closes the two for `long` keys —
the common case, and the one every `Int`-keyed map and set uses. Still open:
`StringHashMapStorage`, `DoubleHashMapStorage`, `HashMapStorage` and the three
Set equivalents, all of which keep the in-place write and therefore the bug.
Their class comments now say so. Porting them is mechanical — the trie's shape
transfers — but a general-key trie needs collision nodes, because `hashCode` is
not a bijection, so it is not a copy-paste and it is not claimed as done.

#### BUG 38 closed for the REST of the key types — and a hole that routing alone left

The entry above closed the bug for `long` keys and listed six storages still
carrying it. `TrieMapStorage` and `TrieSetStorage` are the general-key
counterparts, and every remaining key kind now reaches one.

Collision nodes are needed here and were not in the Long version: that one
hashes through a bijection, so distinct keys provably cannot share a full hash.
This one hashes `hashCode()` into 32 bits, which is not injective — two unequal
keys genuinely can agree on all of it and must then share a node.

#### THE HOLE: rerouting the promotion is not rerouting every constructor

Routing `SmallFlatMapStorage`/`SmallFlatSetStorage` to the tries looked like the
whole job. It was not. The TRANSFORMS — `map`, `filter`, `flatMap`,
`mapValues` — do not promote: they collect into a `java.util.HashMap`/`HashSet`
and then call `HashMapStorage.fromMap` / `HashSetStorage.fromCollection` to
build the result in one pass. Those bulk constructors were untouched, so **a
mapped Map or Set still carried BUG 38** while every promotion path was fixed.

A test found that, not an argument. `BulkBuildSoundnessTest` builds through
`map`/`mapValues` and then derives:

```
  before: aBulkBuiltSetIsUnchangedByADerivation   FAILED (base acquired the element)
          twoDerivationsFromOneBulkBuiltBase      FAILED
  after:  all four pass
```

Worth keeping as a shape: when a structure has more than one way in, fixing the
one you were thinking about leaves the others exactly as broken, and the
"routed everything" claim reads identically either way. `fromMap` and
`fromCollection` now have persistent counterparts — O(n log32 n) where the
tables were O(n), paid once per transform.

#### What the old storages are still reachable through, and why that is fine

`HashMapStorage`, `HashSetStorage` and the String/Double variants are no longer
reachable as the storage of a persistent Map or Set. What still uses them is the
explicit `copyToMutable()` / `mutAdd` / `mutRemove` API — opt-in mutability, the
same pattern `MezzeSeq`, `MezzeBytes` and the sorted structures have, with no
callers in the interpreter. That is not BUG 38: the bug was a PERSISTENT value
changing underneath a program that never asked for mutation. They are therefore
kept rather than deleted, and the two `long` storages that had no such excuse
were deleted in the previous commit.

#### Double keys now BOX, and that is the deliberate trade

The specialised table stored Doubles as raw bits in a `long[]`. The trie boxes
them. Semantics are unaffected and that is tested rather than asserted:
`Double.equals` and `doubleToLongBits` agree on the only two cases that could
differ — every NaN folds to one key, and `-0.0` stays distinct from `0.0` —
and `DoubleStorageSmokeTest` now pins both. A Double-specialised trie would
have avoided the boxing; Float-keyed maps are rare enough that a fifth trie was
not worth carrying, and the choice is recorded here rather than left to be
discovered.

#### What is MEASURED and what is only TESTED

Stated plainly because the two are not the same:

- **`long` keys: measured.** Every row in the table above — reads 1.26-1.46x
  slower, replace chain 3.26x slower, shared-base insert 8.6-85.6x faster, sets
  flat.
- **String, Double and generic keys: tested, NOT measured.** 14 trie tests
  including forced collisions and a differential against `java.util.HashMap`
  with colliders in the key mix, plus the bulk-path and promotion tests. No
  benchmark covers a `Str`-keyed Map, so the cost of replacing the
  String-specialised table — which read a cached `String.hashCode` and called
  `String.equals` directly — is UNKNOWN. The trie reads the same cached
  hashCode and `Objects.equals` devirtualizes on a String receiver, so there is
  reason to expect it to be close; reason to expect is not a number, and
  `mapsurface` uses Int keys throughout.

That is the next thing to do on this surface: a `Str`-keyed row, so the claim
stops resting on an argument.

#### Cleanup: the open-addressed storages and the dead MUT layer, deleted — 2 300 lines

With every Map and Set on a persistent trie, the tables they replaced had one
thing keeping them alive, and it turned out to be nothing.

**The mutable-builder API had no callers at all.** `MezzeMap.copyToMutable`,
`mutableWithCapacity`, `mutPut`, `mutRemove` and `MezzeSet`'s equivalents —
documented "for LocalMut effect", "used by `to_mut`" — are referenced only by
each other. Nothing in the interpreter, the stdlib natives or the tests calls
them. That is the layer PERF-LOG already records as dead ("73 MUT_* ops NEVER
fire; delete it"), and it was the last thing holding `HashMapStorage` and
`HashSetStorage` in the tree.

Deleted, in one pass:

```
  six storage classes      HashMapStorage, StringHashMapStorage,
                           DoubleHashMapStorage and the three Set equivalents
  the MUT layer            copyToMutable / mutableWithCapacity / mutPut /
                           mutAdd / mutRemove / mutClear on Map and Set
  four type predicates     allDoubleKeys, allStringKeys, allDoubleElements,
                           allStringElements — nothing routed to those
                           storages any more, so nothing asked
  two vacuous predicates   isStorable, which returned an unconditional true
                           once the sentinels were gone; the callers now test
                           `instanceof Long` and say why that is the whole
                           condition
```

**2 318 deletions against 93 insertions.** The sealed `MapStorage` hierarchy
went from nine implementations to six, and `SetStorage` the same.

#### Two small things that were wrong rather than merely redundant

**Four copies of the hash functions.** Each trie carried its own `mix`/`hashOf`.
They are now `util/TrieHash.mix64` and `mix32`, in one place with the reason
written down: `mix64` is a BIJECTION, and that is not a performance detail —
it is what lets the `long`-keyed tries omit collision nodes entirely. Changing
it to a non-bijection would make them silently WRONG rather than slow, which is
exactly the kind of fact that should not be sitting in four copies waiting to
drift.

**`MezzeMap.getLong` was boxing.** Its fast path tested only
`LongKeyMapStorage`, so once the open-addressed table became
`LongTrieMapStorage` the method documented as "unboxed get for long keys" fell
through to `get(Long.valueOf(key))` for every Long-keyed map above SmallFlat —
the sizes where it matters. It now covers the trie. Found by reading the
references while deleting, not by a measurement, and it is a correctness-of-
intent fix rather than a perf claim: no number is attached to it.

#### Stale comments repointed rather than left

Four structures (`MezzeSortedMap`, `MezzeSortedSet`, `MezzeBitSet`,
`MezzeDeque`) documented their ownership flag as "see
HashMapStorage.ownershipRevoked for rationale" — a class that no longer exists.
Those comments now carry the rationale themselves and point at BUG 38 for why
the family that hosted it is gone. `PrimitiveNatives` and two tests had the
same kind of dangling reference.

**Those four structures still use owner tokens**, which is worth noting rather
than burying in a comment edit: they were not part of BUG 38 as measured —
`MezzeSortedMap`'s replace path was tested and passes, because installing a
comparator dropped the token and made its in-place paths unreachable — but
`MezzeDeque` and `MezzeBitSet` have not been probed for the same class of
defect. That is an open question, not a known bug, and it is recorded here so
it stays one rather than becoming an assumption.

#### The Str-keyed rows, which the trie commits owed — the cost is the SAME as for Int keys

Two entries ago this was stated as the debt: *"no benchmark covers a `Str`-keyed
Map, so the cost of replacing the String-specialised table is UNKNOWN …
reason to expect is not a number."* These are the numbers. `mapsurface` rows
10-13, interleaved against a CLI built from `31e6fc8dd` — the commit where the
`long` tries had landed and `StringHashMapStorage` was still there.

```
  row (1 000 keys)             table      trie     ratio
  STR key fetch   (control)   10 221    10 256     1.00x
  str map::get (hit)          21 599    24 381     1.13x
  str map::contains_key       14 162    17 043     1.20x
  str map::insert chain       23 450    53 315     2.27x
```

Converged at n=2M and n=4M, and the control is flat to 0.3% — which matters
more here than usual, because the control is most of the measurement.

#### A `Str` key cannot be built in the timed loop, so the control is load-bearing

`int::to_str` plus a concat per iteration would cost several times the map
operation. So the keys are built once into a seq and indexed, which puts a
`seq::get` and an `Option` match in every iteration. Row 10 is exactly that and
nothing else, so the map operation is rows 11-13 MINUS row 10:

```
  operation isolated          table      trie     ratio
  str map::get (hit)          11 378    14 125     1.24x
  str map::contains_key        3 941     6 787     1.72x
  str map::insert chain       13 229    43 059     3.26x
```

**The subtraction is reliable for two of those three and not for the middle
one.** `contains_key`'s operation is ~3.9 µs against a ~10.2 µs control, so the
control is 72% of what is measured and its noise is amplified in the remainder —
across the two sizes of n the isolated ratio swung 1.27x to 1.72x while the RAW
ratio stayed 1.10-1.20x. The raw ratios are the stable statement; the isolated
ones are the right shape with a wide error bar on the smallest row. Recorded
that way rather than picking the flattering figure.

#### What it settles

The trie costs the same for `Str` keys as for `Int` keys:

```
  operation            Int keys   Str keys
  get (hit)              1.27x      1.13-1.24x
  contains_key           1.46x      1.20-1.72x
  insert chain (replace) 3.26x      3.26x
```

So replacing the String-specialised table — which read a cached
`String.hashCode` and called `String.equals` directly — cost what replacing the
`long` table cost, and nothing extra for being general. The earlier guess that
it "should be close" was right, and is now a measurement.

The shared-base side is NOT measured for `Str` keys and does not need to be: the
trie has no owner concept at all, so an insert from a base somebody else holds
executes exactly the same code as any other insert. That is structural rather
than empirical, and it is the reason the `Int` row collapsed from a 95x spread
to none.

#### The other owner-token structures, probed: the Deque is SOUND, the BitSet is BUG 39

The cleanup entry above noted that `MezzeDeque` and `MezzeBitSet` still carry
owner tokens and had never been probed for the defect BUG 38 was, and recorded
that as an open question rather than an assumption. It is answered.

```
  MezzeDeque   5 cases   all pass
  MezzeBitSet  3 cases   2 FAIL  -> BUG 39
```

#### Why one is sound and the other is not, which is the part worth keeping

Not care — **addressability**.

- A deque's reads are bounded by an explicit `[head, tail)` window.
  `pushBack` writes at `tail`, `pushFront` at `head - 1`: both OUTSIDE the
  base's window, which the base has no way to address. Its pops write nothing
  at all — they narrow the window, share the buffer, and revoke ownership so no
  later in-place write can reach the shared array.
- A bitset has no window. `contains(i)` reads `words[i >>> 6]` for any `i`, and
  `add` does `words[w] |= mask` in place while it owns the array. There is
  nowhere unobservable to write.

That is the same line that separated the hash map's in-place insert (unsafe —
the probe follows the hash onto the written slot) from a sorted array's append
(safe — the search never looks past `length`). **The question to ask of an
in-place write is not "do I own this?" but "can anyone else address what I am
about to change?"** The token answers the first and the structures differ on the
second.

This also means the prediction was right for once before the test ran, and it
was still written above the test rather than instead of it — reasoning of
exactly this kind had been wrong twice earlier in the day.

#### The one-line fix, correct and reverted again

Removing the bitset's in-place branch makes both failing cases pass, and then
`:datastructures:test` dies with **Java heap space** before finishing the
benchmarks: `DatastructuresBenchmark` builds a bitset by repeated `add` inside
its timing loop, and every add now allocates. A words array is one long per 64
bits, so cloning looked cheap, and at benchmark scale it is not.

BUG 38 in miniature, for the third time today: the cheap fix is correct, costs
more than it looks, and the real fix is a structure that shares. Filed rather
than forced, because **no Mezze program can reach `MezzeBitSet`** — it is
referenced only by its own test and two benchmarks, with no native, no stdlib
module and no interpreter construction. Recorded with the deletion option
named, since an unwired structure carrying a soundness defect is a candidate
for removal rather than repair, and that is the maintainer's call.

#### `deque::get` was O(n) for an O(1) structure — 24.5x, and the slope is gone

`bench/cases/dsmore` recorded this row as "most likely O(n) by construction
rather than dispatch; its representation needs reading before anything is said
about it." The representation was fine. The native was walking an iterator.

```java
    int i = 0;
    for (Object elem : deque) {
        if (i == index) return MezzeVariant.some(elem);
        i++;
    }
```

A Deque is a ring buffer, and `MezzeDeque.get(int)` — in the same class, right
above the push paths — is one bounds check and
`buffer[(head + index) & mask]`. The native never needed to iterate.

```
  row                            before     after    verdict
  deque::get by index           222 457     9 070     24.5x
  deque::size        (control)      439       452     flat
  deque::first       (control)    5 307     5 306     flat
  sorted_map::size   (control)      445       433     flat
```

Interleaved against a HEAD baseline, three controls flat — two of them other
Deque operations, which bounds the change to exactly the one claimed.
Checksums identical (`sum=100100000`).

And the slope, which is the real result:

```
  elements          250     1 000     4 000
  iterating      71 933   221 294   839 410    linear
  indexed         8 150    10 797    11 076    flat
  verdict           8.8x     20.5x     75.8x
```

It was linear and is now constant, so the win grows without bound in the size
of the deque. The remaining ~10 ns is the `MezzeVariant` allocated for the
`Some`, the same floor `map::get` and `deque::first` sit on.

Every other iterating native in that file is correct to iterate — `sum`,
`product`, `unique`, `minBy` and the rest need every element. This one was the
only operation whose answer depends on one position.

#### The sentinel earned its keep by disagreeing with the obvious test

`DequeIndexedGetTest`, 7 tests. Walking an iterator cannot compute a wrong
index; `(head + index) & mask` can — but only once the buffer has WRAPPED, when
`head` sits near the end of the array and the mask has to fold the sum back to
the front. A deque that has only ever been pushed to the back has `head == 0`,
where a broken index calculation looks perfectly correct.

A sentinel dropping `head` from the calculation fails **3 of 7** — and they are
exactly the three that move it: after pops advance `head`, after `push_front`
moves it backwards, and the base-versus-popped-view pair. The four that pass
include a sweep over EVERY index of a 64-element deque and a 2 000-iteration
warm loop, which look like thorough coverage and prove nothing at all about the
arithmetic.

Worth keeping as the shape: **a full sweep over every index of a structure in
its default state can be no evidence whatsoever about index arithmetic.** The
state has to be disturbed first, and here that means moving `head` before
reading.

#### `sorted_set::add` of an element already there — 20.7x, because it IS the search

The guest-side search closed `sorted_set::contains` at 20.6x and left `add` on
the native path at 307 000 ps. But adding an element that is already present
returns the set unchanged, so that operation is a SEARCH and nothing else — the
same search `contains` had just made 20x cheaper, still being done the old way
one function below it.

`add` now asks `find_index` first and returns the set untouched when the
element is there, never reaching the native.

```
  row                                  before     after    verdict
  ss::add PRESENT, chain              306 932    14 786     20.7x
  sorted_set::contains     (control)   14 764    15 070     flat
  sorted_map::insert chain (control)  579 662   571 720     flat
```

Converged at n=1M and n=2M. **14 786 against `contains`'s 15 070 is the result
rather than a coincidence**: the two operations now cost the same because they
do the same work, which is what says the right thing is being skipped.

#### What the absent path pays

When the element is NOT present the native still runs and still searches, so
that path now searches twice — about 15 ns on top of a ~300 ns insertion that
has to shift the array anyway. Removing the second search needs an
insert-at-index native, which is a separate change with its own measurement and
is not pretended to be done here.

#### Coverage

Three tests added to `SortedSetGuestSearchTest`. The risk a short-circuit
creates is answering "present" for something absent and silently dropping the
insertion, so they check SIZE as well as membership on both sides of the
branch, and one interleaves present and absent adds so a branch inverted the
wrong way round shows up as a wrong size rather than only a wrong answer.

#### `sorted_map::insert` of a key already there — 1.40x, and the keys never needed copying

The largest measured gap left on the board, and the first question was not how
to make it faster but what it was bound BY. 16 KB of array per operation at
1 000 keys says allocation, and the way to settle that is to count collections
rather than nanoseconds:

```
  row                                 ps/op   young collections
  sm::insert PRESENT, chain         660 680                 264
  sorted_map::size      (control)       442                   3
```

Same process shape, same n, same control loop. 88x the collections — the row is
allocation bound, so the fix has to remove allocation and nothing else matters.

It was allocating twice as much as the operation needs. A replace changes no
KEY, and the code said so and then did the opposite:

```java
if (idx >= 0) {
    // Update existing — only clone values array
    Object[] newValues = Arrays.copyOf(values, length);
    newValues[idx] = value;
    return new MezzeSortedMap<>(Arrays.copyOf(keys, length), newValues, comparator);
}
```

The keys array is now shared instead.

```
  row                                base       fix    verdict
  sm::insert PRESENT, chain        576 183   410 571     1.40x
  sorted_map::get (hit)  (control)  16 895    17 113      flat
  sorted_map::contains_key (ctrl)   15 053    14 756      flat
  sorted_map::size       (control)      459       448      flat
  sorted_set::contains   (control)  14 870    15 224      flat
  ss::add PRESENT, chain (control)  14 828    14 790      flat
```

1.38x at n=1M over 9 readings, 1.41x at n=2M, checksums scaling with n. And the
allocation evidence, which is the claim that survives a busy machine: **271
young collections against 157**.

#### The win grows with the map, because it is half of an O(n)

```
  keys        base        fix   ratio
  250      339 027    291 291   1.16x
  1000     569 707    406 434   1.40x
  4000   2 141 225  1 249 656   1.71x
```

That shape is the result. Removing one of two O(n) copies can do no better than
2x and approaches it only once the copies dominate the fixed cost of the search
and the three small objects per call. **Still linear** — a persistent replace
must copy the values array, so this changes the constant and not the shape, and
closing the shape needs an ordered structure with O(log n) update rather than a
sorted array. Not pretended to be done here.

#### A control that moved, and was not a regression

`deque::push_back chain` read 0.80x, and held 0.82x at nine readings — which is
where the rule about re-reading stops helping, because the number was
reproducible. The question is not "is the reading stable" but "stable against
WHAT", so: two builds from byte-identical source.

```
  row                                fix      fix2   ratio
  deque::push_back chain           9 639    11 058   0.87x
  sm::insert PRESENT, chain      407 380   406 262   1.00x
```

13% apart with nothing changed. **`deque::push_back` is not a usable control at
this precision**: it grows a ring buffer to 1 000 000 elements per pass, so it
is dominated by heap layout, which moves between builds. `sm::insert` is 1.00x
across the same pair, which is what lets the 1.40x stand.

Worth keeping as the method: when a control moves, the next measurement is a
SELF-A/B, not more readings of the same pair. More readings tell you the gap is
real; only identical source tells you whether the gap is about the code.

#### And the replace path was not safe to share from, which is BUG 40

`putObject` had a second in-place path beside the append, taking the same owner
token for a middle insert and shifting `[insertPos, length)` up by one. The
append is sound by ADDRESSABILITY — index `length`, outside the `[0, length)`
every holder of the array reads — and the shift is inside it, so the base saw
its own insert:

```java
base.put(5, "v5");   // result DISCARDED
base.keys();         // [0,2,4,5,6,8,…,18] — 5 appeared, 20 fell off the window
```

Pre-existing, nothing to do with sharing, and guest-unreachable because
`SortedMapNatives` installs a comparator first and `withComparator` yields a map
with no token. Deleted rather than guarded, with the addressability argument
written above the path that stays; after which the keys share needs no freeze,
because the only remaining in-place write cannot be addressed by anyone sharing
the array. Filed as BUG 40 FIXED. The dead MUT layer on both ordered classes
went with it — eight methods, no callers, all in-place writers inside the read
window.

Gate: `:interpreter:test` 7 635 tests 0 failures 14 skipped; `:typechecker:test`
4 826 tests 0 failures; `:datastructures:test` 749 tests 0 failures 2 skipped.

#### `insert` was still paying for the search its own lookups stopped paying — 2.30x

`get` and `contains_key` moved their binary search into guest code and got 10-20x
for it, because the native search ran the COMPARISON in host code and so called
back into guest code once per comparison. `insert` kept calling that native, and
the stdlib note above `find_index` had the number sitting in it the whole time:
**~30 ns a comparison, ~300 ns a lookup**, against ~15 ns for the entire guest
search. At 1 000 keys a 412 000 ps insert was mostly that search, with the array
copy the smaller half.

So `find_index` now returns what a Java binary search returns — the index when
the key is present, `-(insertPos + 1)` when it is not — and two natives take the
position instead of computing it. Neither takes a comparator, because neither
compares.

```
  row                              HEAD   guest-search   verdict
  sm::insert PRESENT, base      411 568        178 767     2.30x
  ss::add ABSENT, base          473 370        238 005     1.99x
  sm::insert PRESENT, chain     405 848        269 475     1.51x
  sm::insert ABSENT, base       919 696        665 820     1.38x
  ss::add PRESENT, base          15 308         17 009     0.90x
  ss::add PRESENT, chain         14 682         15 226     0.96x
  sorted_map::get    (control)   16 988         17 062      flat
  sorted_map::contains_key (ctl) 14 869         14 622      flat
  sorted_set::contains (control) 14 848         15 072      flat
  sorted_map::size   (control)      670            701      flat
```

Converged at n=300k and n=600k with 11 readings, checksums scaling with n. And
the allocation evidence, which is the part that survives a loaded machine —
the removed search allocated a record and an args array PER COMPARISON, so
roughly twenty objects an insert went with it:

```
  row                        HEAD young   new young
  sm::insert PRESENT, base          60          39
  sm::insert ABSENT, base          124          97
  ss::add ABSENT, base              70          52
  ss::add PRESENT, base              5           7
```

#### The absent paths win less, and that says what is left

PRESENT 2.30x against ABSENT 1.38x, because they were never the same operation:
a replace copies one array, an insertion copies two at 1.5x capacity. Taking
~300 ns of search off both leaves the replace nearly free of everything but its
copy and the insertion still dominated by its two. **The insert row is now
copy-bound, which it was not before** — which is the state in which the
remaining question is structural rather than a constant.

The chain form wins less than the base form for the same reason in a different
place: 1.51x against 2.30x, because a chained replace feeds each result to the
next, so every iteration copies into a FRESH values array instead of re-copying
one the cache already holds.

#### The regression is real, is 1.7 ns, and was bought for 235 ns

`ss::add` of an element already present got **10% slower**, consistently across
two sizes of n and 11 readings. It is not noise and it is not mysterious: the
present branch used to be `if found_at { idx = … } then s`, a one-field call
record, and is now `add_at_slot { s, x, idx }`, a three-field one. Record fields
are boxed, so `x` costs an extra boxed `Int` on a path that does not use it —
and the GC count says exactly that, 5 young collections against 7, rather than
leaving it inferred.

Keeping the present path at its old cost needs the index in two places, which
needs a `do`-block binding, which detaches the `Ord` (the note above
`find_index` explains why). The alternative is searching twice on the absent
path, which is the 15 ns this change removed. So: +1.7 ns on a present add
against -235 ns on an absent one, taken deliberately.

#### `ss::add ABSENT` existed as a gap, not as a row

Rows 11-14 of `bench/cases/dsmore` are new, and they had to be, because row 3
chains PRESENT keys only — it times a replace, and nothing measured an
insertion. Chaining cannot measure one: a chain of absent inserts GROWS the
container, so the cost moves during the loop. All four insert into the same base
and drop the result, so the size never changes and the two rows differ in one
thing.

The old `add` note had already promised this measurement — "removing the second
search needs an insert-at-index native, which is a separate change with its own
measurement". This is it.

Gate: `:interpreter:test` 7 649 tests 0 failures 14 skipped; `:typechecker:test` 4 826 tests 0 failures;
`:datastructures:test` 749 tests 0 failures 2 skipped.

#### Reading revoked the right to append, so Mezze never once appended — 215x

The previous entry left `insert` copy-bound and said the rest was structural.
It was not. There was still a fast path in the class, unreachable for a reason
that had stopped being true.

`putObject` can append without copying when the key lands at the END of the
array: the write is at index `length`, and every map holding those arrays reads
`[0, length)`, so the slot belongs to nobody. That path is guarded by
`isOwner()`, and `isOwner()` was false as soon as anything had READ the map —
`keyAt`, `valueAt`, `get`, `containsKey` all called `revokeOwnership()`.

And the guest-side search reads the map before inserting into it. **So the
append could not fire on a single insert Mezze ever made.** The revocation was
written for the middle-insert path that shifted `[insertPos, length)` in place;
that write really was inside the reader's window, the revocation was the only
thing between it and BUG 38, and it did not even work — a map still saw its own
insert, which is BUG 40. Deleting that path removed the write. This removes the
guard that had been failing to contain it.

```
  n         ASCENDING build            DESCENDING build (control)
            before      after  ratio   before      after  ratio
  10 000   3 002 369   96 796   31.0x  3 031 396  2 971 826  1.02x
  20 000   5 731 662   54 115  105.9x  5 814 722  5 765 581  1.01x
  40 000  11 228 349   52 280  214.8x 11 314 117 11 215 870  1.01x
```

Per-insert cost was DOUBLING with n and is now flat: O(n) per insert became
O(1) amortized, so the win grows without bound. 57 young collections against 6
at n=20 000 says the same thing in the measure that survives a loaded machine.

**The control is the same operation with one thing changed.** A descending build
does the identical work in the identical loop and lands every key at position 0,
which cannot be appended — and it does not move. Nothing else distinguishes the
two rows, so nothing else can explain the 215x.

#### And every ordered lookup got 1.24x, which was not the point

```
  row                          before    after   verdict
  sorted_set::contains         14 486   11 673     1.24x
  ss::add PRESENT, base        17 018   13 601     1.25x
  sorted_map::contains_key     15 013   11 793     1.27x
  sorted_map::get (hit)        16 941   13 897     1.22x
  sm::insert ABSENT, base     676 996  669 284      flat
  sm::insert PRESENT, base    183 263  180 518      flat
  sorted_map::size (control)      482      443      flat
```

Converged at n=300k and n=600k. `revokeOwnership` was idempotent, but idempotent
meant a field load and a branch on EVERY PROBE of the search — ten of them a
lookup. The GC count is unchanged at 5 against 5, which is the useful part: this
win is not allocation, so the two mechanisms in this change are separable and
each is attributed by its own evidence.

It also more than repays the regression the previous entry recorded:
`ss::add PRESENT` was 15 308 before that change and 17 018 after it, and is
13 601 now.

#### What is actually load-bearing now

One sentence, because it is the whole safety argument and it is no longer
written as a flag: **the only in-place write in either class is at index
`length`, and no map sharing the array can address that slot, because a map
sharing it was derived earlier and so has a length no greater than the owner's —
ownership only ever moves forward.** Anything added that writes inside
`[0, length)` breaks this and cannot be repaired by revoking on reads. That was
tried; it is BUG 40.

Two sentinels, because the argument deserves them. `isOwner()` returning false
fails exactly the two tests that assert the arrays came back SHARED — proof the
others would have passed through the copy path without touching the thing they
name. The append writing at `length - 1` instead of `length` fails 9 of 14,
including a chain of 64 maps read back after all 64 appends.

Gate: `:interpreter:test` 7 649 tests 0 failures 14 skipped; `:typechecker:test` 4 826 tests 0 failures;
`:datastructures:test` 753 tests 0 failures 2 skipped.

#### `remove` was the last one still searching in a native — 19.9x, twice

Six ordered operations search. Five of them had been moved into guest code;
`remove` had not, and it is the one where the win was most predictable, because
removing a key that is NOT there is a pure SEARCH — the container comes back
unchanged, so that case is the search and nothing else. That is the exact shape
`sorted_set::add` of a present element had, and that one was 20.7x.

```
  row                            HEAD   guest-search   verdict
  sm::remove ABSENT, base     291 052         14 667    19.84x
  ss::remove ABSENT, base     291 045         14 693    19.81x
  ss::remove PRESENT, base    410 904        164 415     2.50x
  sm::remove PRESENT, base    687 838        420 106     1.64x
  sorted_map::get  (control)   13 877         14 004      flat
  sorted_map::contains_key     12 120         12 145      flat
  sorted_set::contains (ctl)   11 713         11 964      flat
  sm::insert PRESENT   (ctl)  182 722        184 628      flat
  ss::add PRESENT      (ctl)   14 289         14 095      flat
```

Converged at n=300k and n=600k, five controls flat, checksums scaling with n.

**Two independent operations landing on 19.8x is the result**, not the
individual numbers: the map's remove and the set's remove share nothing but the
mechanism being removed, so agreeing to two significant figures says the ~300 ns
really was the native comparator callback and not something either structure was
doing. And they land at 14 667 and 14 693 against `contains`'s 11 964 — an
absent remove now costs what a search costs, because that is all it is.

```
  row                        HEAD young   new young
  sm::remove ABSENT, base           31           7
  ss::remove ABSENT, base           30           7
  ss::remove PRESENT, base          56          39
  sm::remove PRESENT, base          89          71
```

#### The ordered API is now consistent, and the remaining gap is one thing

`get`, `contains_key`, `contains`, `insert`, `add` and `remove` all search in
guest code. `insert_with_cmp`, `add_with_cmp` and `remove_with_cmp` are deleted,
along with four native registrations and two native methods.

Against the hash map in the same regime, at 1 000 keys: get 13 899 against
12 839 (1.08x), contains 11 964 against 6 570 (1.82x), and **insert of an absent
key ~669 000 against 45 266 (14.8x)**. Lookups are at or near parity; the one
gap left is insertion and removal at an arbitrary position, and it is now purely
the array copy — every comparison-shaped cost has been taken out of it.

`coresurface` says there is nothing comparable elsewhere in the language: one
effect operation is 393 ps net of the tier control, and option and variant
matching are at parity with the counted loop (226 and 231 against 222). So the
next move on this is an ordered structure with O(log n) update rather than
another constant, which is a larger piece of work than anything above.

Gate: `:interpreter:test` 7 656 tests 0 failures 14 skipped; `:typechecker:test`
4 826 tests 0 failures; `:datastructures:test` 753 tests 0 failures 2 skipped.

#### The slices were still searching in a native — 70x — and one of them was a regression of mine

Nine ordered operations were left: `floor_key`, `ceiling_key`, `range`,
`head_map`, `tail_map`, `sub_map` on the Map, and `range`, `head_set`,
`tail_set` on the Set. They take NO comparator argument and searched with the
one STORED on the map, so they were both the slowest things left in the module
and — as it turned out — broken.

```
  row                              HEAD   guest-search   verdict
  sorted_map::floor_key         952 277         13 597    70.0x
  sorted_map::ceiling_key       871 239         13 229    65.9x
  sorted_map::head_map        8 198 126        192 207    42.7x
  sorted_map::take half       7 688 192        179 618    42.8x
  sorted_map::get  (control)     13 934         14 068     flat
  sorted_map::contains_key       12 833         13 265     flat
  sm::insert PRESENT   (ctl)    176 189        185 019     flat
  sm::remove ABSENT    (ctl)     15 520         16 478     flat
```

Converged at n=100k and n=200k. `floor_key` and `ceiling_key` are pure
searches, so they land where every other pure search landed: 13 597 against
`contains_key`'s 13 265.

#### The regression, and what hid it

`floor_key` on a map with a user `Ord` threw `ClassCastException`. The
comparator those nine searched with was never set deliberately — the old
`insert` went through a native that called
`withComparator(ElementComparator.of(cmpFn))` first, and the map it returned
carried the result. Moving `insert`'s search into guest code stopped that
happening, the arrays stayed correctly ordered because the guest search is what
orders them, and the field was simply never written again.

Nothing in 7 649 tests touched it, for a reason worth keeping: the slice tests
used `Int` keys, where the host's ordering and the program's AGREE, and the
custom-`Ord` tests used `get`/`insert`/`contains_key`, which had already stopped
needing the stored comparator. **The case that distinguishes them is the product
of two test dimensions that each existed separately.** Filed as BUG 41, and
verified as a regression rather than argued to be one: the new test file passes
against the runtime three commits back and fails six ways against the one that
introduced it.

The fix is not to reinstate the install. A slice is INDEX ARITHMETIC over a
search the guest already does, so nothing needs storing at all — which is why
this entry is a perf win and a correctness fix at once.

#### `take` was in the same defect, and was not the shape I first said

`take` and `drop` rebuilt their result one `putObject` at a time, paying a
comparator search per entry — so a map keyed by a record threw from `take`
however it was reached, and the Set's version rebuilt through
`MezzeSortedSet.empty()` with NO comparator, losing the ordering it had been
sliced from.

I called that O(n²). It is not, and the slope says so:

```
  keys           HEAD          new   ratio
  250       1 596 407       66 880   23.9x
  1000      7 663 727      195 190   39.3x
  4000     31 706 782    1 061 386   29.9x

  growth 250->4000 (16x the keys):  HEAD 19.9x   new 15.9x
```

The rebuild inserts in ASCENDING order, so every one of its inserts takes the
append fast path — which the previous entry had just made reachable. That makes
it O(n log n) rather than O(n²), and 19.9x against a linear 16x is exactly the
mild super-linearity that predicts. Both are now one slicing native: two array
copies of an already-ordered run, and not one comparison. The new row is
allocation bound rather than copy bound — 1.06 µs for 4 000 slots is 264 ps a
slot, far above memcpy — so what remains is the result it has to allocate.

Worth keeping: **a slope is also how you find out your own account of a
mechanism was wrong.** The ratio was there to be read either way; the shape is
what said the rebuild had already been half-fixed by something else.

Gate: `:interpreter:test` 7 663 tests 0 failures 14 skipped; `:typechecker:test` 4 826 tests 0 failures;
`:datastructures:test` 753 tests 0 failures 2 skipped.

#### The Phase G gate had no checksum, and two of my own readings of it were wrong

No perf win in this entry. `bench/cases/ds_matrix` is the regression gate for the
Phase G storage and Iter work, and it was not measuring what its rows said. I
went to it looking for the next gap, drew two conclusions from it, and both were
artifacts of the instrument.

**It never printed a checksum.** `time_one` did `let r = thunk {}` and returned
only the duration, so `r` was dead and the row was DELETABLE — and was being
deleted:

```
  seq::map+size, five runs at n=2e6, ratio to the control, x/100:
     1    10     1    74     1
```

A ratio of 1/100 is the row costing a hundredth of a counted loop, which no
allocating map does. `size(map(xs, f))` is provably `size(xs)` for a
loop-invariant `xs`, so with nothing consuming it there is nothing to compute.
Every sibling case states the rule in its header — "EVERY ROW CHECKSUMS: a
kernel that got fast by not doing the work shows up as a different number
instead of a better one" — and the one that is a gate was the one without it.
Fixed: the value is threaded out and printed, and the sums are now right
(`sum=2000000` for a 2e6 map+size, `1000000` for an even filter,
`1999999000000` for a sum).

**Both wrong readings came from running all rows in one process.** `sel = 0`
does that, and the file already warned that absolute numbers do not compare
across builds — but not that they do not compare across ROWS of one run, which
is what I did:

```
                       all rows, one process    one row per process
  list::map+size                     1 776                   3 790
  seq::count                           153                   3 646
```

So I read a 3.0x gap between `seq::map+size` and `list::map+size` that is
actually parity, and read `seq::count` as FASTER than the control — for a scan
with a guest predicate per element — when it is 23x it. The banner now says so
on the way past.

#### What the instrument says once it is honest

n=2e6, one row per process, checksums verified:

```
  op              seq   x/100    list   x/100    list/seq
  fold            347     130   1 704     611       4.9x
  sum (native)    335     137       —       —          —
  filter+size     161      62   1 802     597      11.2x
  any (miss)      401     154   1 724     639       4.3x
  count           160      56   1 778     646      11.1x
  range.fold       96      38       —       —          —
```

**Every List row is 1 700-1 800 ps/elem whatever the operation**, and that
uniformity is the finding: the cost is the cons-cell TRAVERSAL, not the op. It
also confirms the older note that had `list::fold_left` at 3.9x `seq::fold` —
it reads 4.9x here — and says the fix is List's representation rather than any
individual function.

`seq::map+size` reads 20 ps/elem and is flagged UNDER-WARMED, which is correct
and worth keeping: the chain FUSES `size(map(xs, f))` into a counting loop with
no allocation, so the row's `(alloc)` label is a leftover from before fusion
covered it. It is not measuring an allocation and should not be read as one.

#### Three items are left, and all three are structures rather than constants

- **List is cons cells** — 4.9-11.2x Seq, uniform across operations, so it wants
  a chunked backing like Seq's.
- **Ordered insert at an arbitrary position** — 14.8x the hash map's at 1 000
  keys and diverging (9.3x / 9.0x / 20.8x / 40.9x at 250 / 1k / 4k / 16k), now
  purely the array copy. Wants an ordered tree with guest-driven descent.
- **`coresurface` has nothing comparable** — one effect operation is 393 ps net
  of the tier control, and option and variant matching are at parity with a
  counted loop (226 and 231 against 222).

#### What the List gap actually is: the WALK, and the callback is free

The missing control. `seq` had a native-sum row and `list` did not, so nothing
separated the two things a List row pays — walking the cons cells, and calling
back into guest code per element. `list::sum` walks and takes no callback, so it
is the walk alone. n=2e6, one row per process, min of five, spreads 1.04-1.37x:

```
  row                         ps/elem  callback   all five
  list::sum     (native)        1 731        no   1858 1896 1889 1731 1801
  list::fold_left (sum)         1 661       yes   1696 1661 1676 1725 1707
  list::count                   1 736       yes   1783 1736 1737 1899 2161
  seq::sum      (native)          281        no    289  386  281  329  302
  seq::fold     (sum)             274       yes    280  278  274  313  281
```

**The callback is free on both structures.** `seq::fold` 274 against
`seq::sum` 281, and `list::fold_left` 1 661 against `list::sum` 1 731 — the
version WITH a guest call per element is marginally cheaper than the one
without, on both. So none of the 6x is the boundary, the closure, or the
dictionary, and the whole of it is the traversal: 1 731 against 281 is **6.2x
for walking 2 000 000 cons cells instead of 2 000 000 array slots**.

That corrects the standing note, which had this as `list::fold_left` being 3.9x
`seq::fold` and guessed at chunked backing without separating the two costs. The
ratio was right; the attribution was unmeasured.

#### And 6.2x is about what a cons cell has to cost

A `MezzeLongCons` is a header, an unboxed long and a tail reference — call it 32
bytes against the 8 an element of a `LongSeqStorage` occupies. So the walk moves
4x the memory, and every step's address DEPENDS on the previous load, which an
array index does not: the hardware can prefetch an array and can only speculate
a pointer chain. 4x the traffic and no vectorisation lands near 6x without
anything being wrong.

So this is not a defect to fix but a REPRESENTATION to decide — and the
representation is PURELY INTERNAL, which is the opposite of what I first wrote
here. `[x, ...rest]` is a SEQ pattern and does not apply to a List:

```
  match someList is
    [] -> …
    [x, ...rest] -> …
  # error[T0201]: Field `xs` expects ['a] but got List Int
```

A List is taken apart by `list::uncons`, which returns
`Option { head: 'a, tail: List 'a }` — a FUNCTION, not a pattern. So cons/nil is
not language-visible at all, and nothing about changing the backing touches
syntax or pattern exhaustiveness. The only contract is the complexity of the
function surface: `uncons` has to hand back a head and a tail `List` (a
chunk-and-offset view, O(1)), and `cons` has to stay cheap, which is the one
thing a cons list is actually good at and the thing a chunked structure needs a
front-buffer strategy for.

**That makes this more tractable than the entry first claimed, not less.** The
wrong half was mine: the same `expects ['a] but got List Int` error had appeared
earlier the same day in a test of mine, where I charged it to the test and did
not read what it said about patterns. Heads are already unboxed per kind
(`MezzeLongCons`, `MezzeDoubleCons`, `MezzeCons`), so that half is done.

Recorded rather than attempted, with the control row left in `ds_matrix` so the
next reading of it does not have to re-derive the attribution.

#### The string-concat boundary costs 2x, is not free, and cannot be removed

The first measurement of the string surface. `bench/cases/surface`, one row per
process, min of 3-5, spreads 1.02-1.09x:

```
  row                        min ps   x/100 control
  str::split    (alloc)     166 746          65 390
  str::to_upper (alloc)      54 473          21 702
  int::to_str+concat         15 611           6 614
  str::concat   (alloc)      10 152           3 788
  str equality                5 815           2 271
  str::contains (scan)        4 963           2 042
  str::size                     885             375
```

**The useful number is not in that table.** `concat`'s own slope rows separate
the fixed cost from the per-byte cost, and the fit is exact: 8 442 ps at 15
chars and 12 354 at 131 gives `7 937 + 33.7/char`, which predicts 12 352 against
a measured 12 354. Two picoseconds. So **creating a string costs ~7.9 ns before
the first byte is copied**, while copying runs at 33.7 ps a character — and
`str::size`, through the same native path, is 0.885 ns, so it is not call
overhead. `str::split` is 13 of those fixed costs in a trench coat: 167 ns for a
64-character, 13-piece split is 12.8 ns a piece.

#### Where the 7.9 ns goes

`DirectAddNode.addStrings` carries a `@TruffleBoundary` and the comment above it
said "string concat is inherently allocating so the boundary is free". It is
not. With that annotation as the ONLY difference between two builds:

```
  row                     boundary   no boundary    ratio
  concat    15 chars          8 662         4 323    2.00x
  concat   131 chars         12 159         6 536    1.86x
  str::concat   (alloc)      10 268         5 329    1.93x
  str::size     (control)       895           890    1.01x

  fixed per concat            8 210         4 037
  per character                30.1          19.1
```

~4.2 ns of the fixed cost is the boundary, which is the same per-op floor
measured elsewhere, and it also costs 11 ps a character because the copy cannot
be optimised in context.

#### And it has to stay — for a reason the old comment did not give

The comment blamed `StringConcatHelper.simpleConcat`, which is true of
`left + right` and is not the whole story. `String.concat` is no escape: it has
delegated to the same helper since JDK 21. Copying through a `char[]` avoids
both and STILL fails the image build, and the trace says why:

```
  Blocklisted method java.util.concurrent.ConcurrentHashMap.get(Object)
  trace:  … jdk.internal.util.Preconditions.outOfBoundsMessage
          … Preconditions$4.apply … List.of … Collection.toArray
```

`getChars` bounds-checks, and the FAILURE path builds its message through
machinery that is blocklisted. Without a boundary that error path becomes
runtime-compilable. **Every bounds-checked String accessor has that shape**, so
the PE-safe formulations all allocate more than the 4.2 ns they save — which is
the same rule already recorded for error paths inside PE'd native ops, arriving
from a third direction.

Verified in BOTH directions rather than assumed: with the boundary
`mez build --native` succeeds in 1m28s and produces a 120 MB binary; without it
the analysis phase fails in 57s. The comment now carries the numbers and the
real blocker, and says to re-run that check before touching it.

**So the 2x on every string concatenation and interpolation is real and is not
available.** Recorded rather than left for someone to re-derive: the measurement
took one probe build, and the reason it cannot be taken took two more.

#### An ASCII fast path for case conversion: tried, measured, REVERTED

`str::to_upper` runs 782 ps a character against `str::concat`'s 33.7 ps for
allocating and copying the same bytes — 23x, when both allocate and only one
transforms. In ASCII exactly 'a'-'z' have an uppercase mapping and none of them
expand, so `c - 32` over that range looked like it had to beat a table lookup.

It did beat it, and lost anyway.

```
  row                      HEAD   ascii path    ratio
  to_upper  15 chars     16 417       19 196    0.86x
  to_upper 131 chars    108 122       79 558    1.36x
  str::to_upper          54 674       42 456    1.29x
  concat   (control)     10 223       10 337     flat
  str::size (control)       937          868     flat
  str::contains (ctl)     5 504        5 355     flat

  per character             790.6        520.4   1.52x
```

1.52x on the per-character term and **0.86x on short strings**, which are the
common case. Decomposing the two rows says exactly where it went: the FIXED cost
went from 4 567 to 11 396 ps. `toCharArray` inflates a Latin1 string to UTF-16
and `new String(char[])` compresses it back, so the fast path bought two compares
per character and paid two extra passes and an allocation for them.

#### The useful part is why 23x was never going to come from this

**Both versions are SCALAR.** 790 ps a character is about 2.4 cycles — a
per-character loop, which is what a table lookup costs. 520 ps is a cheaper
per-character loop. Neither is vectorised, and neither can be while the loop
carries a data-dependent bail for the first non-ASCII character.

`concat`'s 26-34 ps a character is not a cheaper per-character operation — it is
**no per-character operation at all**: a bulk copy the JIT vectorises. So the
23x is the distance between a vectorised copy and a scalar transform, and
closing it needs the transform vectorised — an intrinsic, or the Vector API that
this build already loads for `SeqSumLongSIMDNode` — not a cheaper comparison.
That is a different and much larger change, and it now has a number attached to
what it would be worth.

Reverted, with one thing kept: `AsciiCaseConversionTest`, 6 tests over 18
inputs, differential against the JDK's own `Locale.ROOT` answer. It guards BUG
37's invariant — that `str::to_upper` is a language primitive and must not read
the host locale — and it is the harness a future attempt has to survive. A
sentinel removing the non-ASCII deferral failed 4 of the 6, `ß`→`SS` among them,
which is the case that makes an arithmetic fast path return a string of the
wrong length.

#### `bytes::to_hex` formatted one byte at a time — 56.7x

`Bytes` had ZERO benchmark coverage, which is the worst place for it: the type
sits on the codec, IO and HTTP paths, where a per-element loop is the normal
shape, so a per-byte cost multiplies. `bench/cases/bytesurface` is the first
measurement of it — twelve rows, one row per process, every row checksummed.

The first run answered the question on its own:

```
  row                        ps/op   per byte (1 024-byte buffer)
  bytes::to_base64         225 082            220
  bytes::to_hex         51 142 999         49 944      <-- 227x base64
  bytes::fold              152 866            149
  bytes::map            26 109 334         25 497      <-- 171x fold
  bytes::to_seq          2 556 210          2 496
  bytes::index_of MISS     219 030            214
  bytes::concat self        87 482             43
  bytes::slice half         37 791             74
  bytes::get by index         7 695             —
  bytes::size                 6 658             —
```

**Hex being 227x base64 is the whole diagnosis**, and the two methods are
adjacent in the same file. base64 hands the buffer to the JDK's bulk encoder.
Hex did this:

```java
for (int i = 0; i < bytes.length(); i++) {
    sb.append(String.format("%02x", bytes.get(i) & 0xFF));
}
```

`String.format` PER BYTE — parse the format string, allocate a `Formatter`, box
the int, 1 024 times a call. A nibble table replaces it.

```
  row                        HEAD      table     verdict
  bytes::to_hex          51 198 035    903 080     56.7x
  bytes::to_base64 (ctl)    175 049    158 646      flat
  bytes::size      (ctl)      6 585      6 681      flat
  bytes::get       (ctl)      8 073      7 873      flat
  bytes::fold      (ctl)    152 670    158 349      flat
  bytes::to_seq    (ctl)  2 602 322  2 530 706      flat
```

49 998 → 882 ps a byte, checksums identical. `from_hex` had the same shape —
`hex.substring(i*2, i*2+2)` and `Integer.parseInt` per byte — and got the same
treatment; it also stops accepting the SIGN `parseInt` allowed, so `"-1"` and
`"+f"` are `None` now rather than decoding to `0xFF` and `0x0F`.

Both fixed in TWO places: three unrelated classes are named `MezzeBytes` and the
guest's `bytes::*` natives build the one nested in `BytesNatives`, so the
identical bug lived in `datastructures`'s copy as well.

#### A hypothesis of mine, wrong, and a number I should not have written

The comment first said the fix was "worth 1.9x on top of the table itself: 882
ps a byte to 458". **That number was invented before measuring.** Removed, then
measured.

The idea was that `new String(char[])` must scan and compress UTF-16 down to
Latin1, so building a `byte[]` and handing it over as ISO-8859-1 should skip a
pass. INTERLEAVED, seven readings a side with no overlap:

```
  to_hex    char[] 927 240     byte[] 1 001 812     0.93x
  char[]:  931516 951048 927240 935841 949804 958984 931928
  byte[]:  1060579 1020801 1034206 1051738 1072274 1023954 1001812
```

**8% WORSE.** The all-ASCII `char[]` constructor takes a better path than the
explicit-charset one. Worth recording for the same reason the `to_upper`
attempt was: an extra pass that looks obviously wasteful can be cheaper than the
thing that avoids it.

It was also measured across two SEPARATE runs first, where it looked worse by a
different amount and `to_base64` had drifted 25% between them — more than the
difference being asked about, so that reading meant nothing. Same rule as the
self-A/B: the question is never "is the reading stable" but "stable against
what".

#### The sentinel corrected one of the tests

Swapping the two nibbles in the encoder fails 2 of the 6 tests — but NOT the
round trip over every byte value, and the reason is worth keeping: **swapping
nibbles is a BIJECTION on 0..255, so the sum over the whole range is invariant
under it.** A property that ranges over every input can still be blind to a
permutation of those inputs. The asymmetric spot check (`0x01 0xf0 0xa5 0x05
0x80 0xff`, chosen so no byte has equal nibbles) is what catches it, and the
test now says so.

#### Still open on this bench

`bytes::map` is 25 497 ps a byte against `bytes::fold`'s 149 — 171x for the same
per-byte guest callback. `BytesNatives.map`'s own comment already records that it
"turns out not to take that path", meaning the scalar bypass `fold` uses; the
callback goes through the record path instead. Next.

Also unexplained and smaller: `to_seq` at 2 496 ps a byte (boxing each byte into
a Seq element) and `index_of` at 214 ps a byte for a byte-at-a-time scan that
could vectorise.

Gate: `:interpreter:test` 7 675 tests 0 failures 14 skipped; `:typechecker:test`
4 826 tests 0 failures; `:datastructures:test` 753 tests 0 failures 2 skipped.

#### `bytes::map` ran its callback through the record path — 4.83x, and it was a stale cache

`bytesurface` had `bytes::map` at 25 497 ps a byte against `bytes::fold`'s 149 —
171x for what looks like the same per-element guest callback. Two causes, and
only one of them is a defect.

**The fused half is correct and stays.** `NativeHofNames` deliberately keeps
bytes out of the element-stage vocabulary: `bytes::map`'s callback result
truncates to 8 bits, so peeling it as an ordinary stage would make a fused chain
compute a different answer — `x * 2` over a byte of 200 is 144, not 400. Bytes
participates only as a SOURCE under a fold sink, which is why `fold` is fused
and `map` is not. Nothing to fix there.

**The other half was a cache populated too early.** A probe in
`ScalarCall.prepare1`'s fallback reported

```
  PROBE prepare1 NO BYPASS: partial=false root=LazyStubRootNode   <- 650 of 650 calls
```

`ScalarBypass.tryResolve` requires the callback's root to be a
`TrampolineRootNode`, and a function that has not been compiled yet presents a
`LazyStubRootNode` instead. So every `bytes::map` call took the record path and
allocated a `MezzeRecord` plus an `Object[]` per element.

**This exact bug was found and fixed once already**, in
`CallIndirectScalar1/2`, where it cost every indirect call site in the language
its allocation-free path — 8.6x on a closure callback, 2.7x on `seq::fold`. The
HOF-callback gate is separate code and never got the fix. Applying the same
`peekForwardTargetNoClosures`:

```
  row                            HEAD         peek    verdict
  bytes::map, len 512       11 543 131    2 313 142     4.99x
  bytes::map, len 1024      23 052 255    4 774 974     4.83x
  bytes::fold    (control)     155 191      158 797      flat
  bytes::size    (control)       6 814        6 479      flat
  list::count    (control)       1 592        1 615      flat
  list::fold_left(control)       1 460        1 433      flat

  young collections                118           25
```

Converged across both lengths, per-byte cost stable (22 512 and 22 545 before,
4 663 and 4 518 after), checksums identical. The peek refuses a function
carrying closures — the stub prepends them per call and a bypassing caller would
not — so the change is strictly additive.

#### A control that read 0.79x, and was not one

`seq::count` read 130 → 164 in the five-rep pass, a 21% regression on a core
operation. At nine reps it is **1.00x** — 138 against 138, with per-side spreads
of 1.43x and 1.96x. Noise, and it would have shipped as a false regression claim
had the pass stopped at five. Same shape as the `deque::push_back` episode, with
the opposite conclusion, which is the point of re-reading rather than guessing
which way it will go.

#### I removed test coverage and the sentinel caught it

Making the bypass work meant `bytes::map` went POSITIONAL, and a positional path
cannot see a field NAME. So putting the known-bad `byte` back in place of `x` in
`BytesNatives.map` made **every test in `BytesHofScalarBypassTest` pass** —
including the one written to catch exactly that mistake after it had once
shipped. The fix had silently disarmed its own regression test.

Probed for a callback that still reaches the record path:

```
  inline lambda  { x } -> x + 1   ->  BYPASSES   (what the bench measures)
  top-level fn   plus_one         ->  RECORD PATH (trampoline, 0 closures,
                                      refused by an inner-target check)
```

So a top-level function passed as a value carries the field-name coverage now,
and a case for it is the only test that fails when the name is wrong. Worth
keeping as the shape: **a performance fix can disarm the test that guards its
own correctness, and nothing about the fix looks like it touches testing.** The
only way that surfaced was re-running the sentinel the old doc described.

Two stale claims in that file corrected while there: "bytes is still 21x the
FUSED seq fold — `bytes` is not in the fuser's vocabulary" is no longer true of
`fold` (155 ps a byte against `seq::fold`'s 280, so FASTER), and "`map` is not
bypass-eligible here at all" is no longer true of an inline lambda.

Gate: `:interpreter:test` 7 676 tests 0 failures 14 skipped; `:typechecker:test`
4 826 tests 0 failures; `:datastructures:test` 753 tests 0 failures 2 skipped.

#### `bytes::to_seq` boxed a Long per byte — 6.63x

The last of the three bytes defects, and the plainest: converting a `Bytes` to a
`[Int]` filled an `Object[]` with `(long) (bytes.get(i) & 0xFF)`, boxing a
`Long` per element, and then handed it to `SeqStorageFactory.fromArray`, which
has to GUESS the storage back — three `instanceof` an element to decide it is
all `Long`, a second array, and an unboxing pass. For what is a widening copy.

```
  row                      len         HEAD       long[]     verdict
  bytes::to_seq           1024      2 526 132      380 769     6.63x
  bytes::to_seq            512        985 782      202 737     4.86x
  bytes::to_hex (control) 1024        899 964      911 774      flat
  bytes::fold   (control) 1024        155 185      156 589      flat
  bytes::size   (control) 1024          6 810        6 669      flat
  bytes::get    (control) 1024          7 946        7 697      flat

  young collections                        52           24
```

**The two ratios differ because HEAD is not linear.** Its per-byte cost rises
from 1 925 to 2 467 ps as the buffer doubles — more boxed `Long`s, more
collection — while the new path is flat at 372 to 396. So the win grows with the
buffer rather than being a constant 6.63x, and quoting one number without the
other would have hidden that.

`MezzeSeq.fromLongs` would have CLONED the array, so the storage is built
directly. Sound because the array is allocated in the native and never escapes:
the clone exists to stop a caller mutating the sequence it handed over, and
there is no such caller.

#### A round trip is blind to any error its inverse undoes

The sentinel corrected the test's own note. Dropping the `& 0xFF` — the whole
correctness of the conversion, since a Java `byte` is signed and `0xFF` reads as
-1 — fails TWO of the five tests, not the four first written there.

The survivor is the interesting one. The ROUND TRIP through `from_seq` passes,
because `from_seq` truncates with `byteValue()`: -1 goes back to `0xFF` and the
trip is the identity whether or not the element was sign-extended on the way
out.

**That is the same blind spot the hex codec's whole-range sum has under a nibble
swap** — swapping nibbles is a bijection on 0..255, so the sum over every byte
value cannot see it. Twice in one day, in two different files, the test that
looked strongest was the one blind to the bug it was aimed at. What sees these
is reading the VALUES: `0x00 0x7f 0x80 0xff` sums to 510 unsigned and -2
sign-extended; `0x01 0xf0 0xa5` distinguishes a nibble swap because no byte in
it has equal nibbles.

Worth stating as a rule: **a round-trip or whole-range property is invariant
under any error the inverse undoes symmetrically, and under any permutation of
the input set.** Those are exactly the errors an encoder/decoder pair is likely
to have.

#### Bytes, after three fixes

```
  op                    before        after    note
  to_hex            49 944/byte      882/byte  String.format per byte
  to_seq             2 467/byte      372/byte  a boxed Long per byte
  map               25 497/byte    ~4 600/byte callback never bypassed
  fold                  —            149/byte  fused; faster than seq::fold's 280
```

Still open and measured: `to_hex` is 5.7x `to_base64` per byte (base64 reaches
an intrinsified JDK encoder), `index_of` scans a byte at a time at 214 ps a
byte where it could vectorise, and `map` remains ~30x `fold` — which is CORRECT,
not a defect: `NativeHofNames` keeps bytes out of the element-stage vocabulary
because `map`'s result truncates to 8 bits, so fusing it would make `x * 2` over
a byte of 200 give 400 instead of 144. Closing that needs a truncating stage
kind.

Gate: `:interpreter:test` 7 681 tests 0 failures 14 skipped; `:typechecker:test`
4 826 tests 0 failures; `:datastructures:test` 753 tests 0 failures 2 skipped.

#### Every `get` in the language allocates an Option — 1.93x on the first one

`bench/cases/allocsurface` asks only "does it allocate", by counting young
collections, and running it one kernel per process at n=2e7 sorted fifteen
kernels into two groups with nothing in between:

```
  allocates nothing (3 collections)   allocates (17-30)
    map has            6 111            map get          16 303
    set has            6 986            seq get          15 482
    map size           1 172            list get         16 154
    str size           1 060            bytes get        17 864
    dict               1 248            record update    23 987
    tier control       6 180            str interp       21 055
                                        str concat       13 487
                                        map insert       17 285
```

**Every keyed read that returns `Option` allocates; its `has` sibling does
not.** And the attribution is exact, because `allocsurface` has the controls for
it: `map size` is a native call with NO lookup (1 172 ps, 3 collections),
`map has` is the SAME lookup returning a Bool and sits on the tier control
(6 111 against 6 180, 3 collections), and `map get` is that lookup returning an
`Option`. So the lookup allocates nothing and costs ~5 ns, and the ~7-10 ns plus
fourteen extra collections between `has` and `get` are the `Option` alone —
**twice the cost of the lookup it wraps.**

The `match` is immediately on the result, so the variant never escapes.

```
  kernel                 HEAD        fused     verdict   young
  5 map get            11 009        5 701       1.93x   17 -> 3
  8 map has (control)   5 921        6 144        flat    3 -> 3
  10 tier (control)     1 055        1 017        flat    3 -> 3
  7 seq get             14 123       14 584       flat   22 -> 21
  13 list get           16 483       16 026       flat   21 -> 21
  15 bytes get          14 767       15 097       flat   21 -> 21
```

Seven readings a side, spreads 1.39x and 1.38x. **The collection count going to
3 is the result** — the same floor as `has`, `size` and the tier control — and
`get` is now 1.5 ns from `has` instead of 6.8.

#### The machinery already existed and was dead

`BytecodeCompiler.tryEmitFusedMutGetMatch` does exactly this for mut sequences,
is fully written and documented, and never fires: it gates on
`CallDirectIR.isScalarABI()`, which is set for extracted lambdas only, and by
the time the bytecode compiler runs the stdlib wrapper has been inlined and no
such call survives to match. Keying on the NATIVE's name is what survives
inlining — the same reason the `__native_sorted_map_key_at` fast path fires.

#### Three mistakes of mine, each caught by a sentinel rather than by thinking

**The first draft rejected `isScalarABI()`** — a condition copied from the
record-ABI fast paths above without checking that it applied. The sentinel then
failed exactly ONE of six tests, because the fusion fired in straight-line test
code and not in a loop, which is where the cost is. A probe printing the
scrutinee settled it in one run: `CallNativeIR name=__native_map_get
scalar=true`, and `__native_seq_get`, `__native_list_get` and
`__native_bytes_get` beside it, all scalar. After the fix the sentinel fails
THREE of six, and the survivors are the right three: two expect the `None`
branch either way, and the guarded one is refused and falls back.

**"The fusion does not fire in allocsurface" was a conclusion from nothing.**
`bench/scripts/ab.sh` redirects the build's stderr to `/dev/null`, so the probe
printed into a void and I read the silence as an answer.

**And a number written before it was measured**, for the third time today: the
test doc said the sentinel fails "four of the six". It fails three. All three
invented numbers are out.

#### A control that moved and should not be quoted at all

`map size` read 3.03x, and nothing in this change touches it. At seven readings
it is 1.09x with a **5.29x spread** — a 1.2 ns row whose noise is five times the
effect anyone would claim from it. `tier control` is the same: 1.04x, spread
4.60x. Those two are allocation controls, not timing controls, and the
collection count is the only column of theirs worth reading.

#### What is left

`seq get`, `list get` and `bytes get` are untouched and still allocate: a byte
or an index needs a presence signal, and `null` is available for all three —
Mezze has no null value, so no container can hold one, and `bytes::get` boxes
its byte into a `Long` before returning it. One operation each, doing its own
bounds check, extends the same pattern.

Gate: `:interpreter:test` 7 687 tests 0 failures 14 skipped; `:typechecker:test`
4 826 tests 0 failures; `:datastructures:test` 753 tests 0 failures 2 skipped.

### LEDGER UPDATE 2026-11-09 — `acc + i + i` is 177x `acc + i`, and it is under every collection row on this bench

I set out to take the largest measured gap on the collections surface — the map
and set keyed reads at 40-80x the counted-loop control — and the instrument
answered a different question than the one I asked. The gap is not in the data
structures. It is in the arithmetic of the loop that calls them, and it is one
of the largest single numbers this ledger has recorded.

#### The measurement

`bench/cases/allocsurface`, one kernel per process, n=1e7 and n=2e7, min of 2-3
per size, converged across both, young collections beside each. Checksums are
printed by every kernel and are identical between every pair compared here.

| kernel | body of the accumulator update | ps/iter | young |
|---|---|---|---|
| k1  | `acc + i`, 2 params                 |    223 |  2 |
| k12 | `acc + str::size { s }`             |  4 178 |  3 |
| k22 | `acc + (str::size { s } + i)`       |  3 915 |  3 |
| k20 | `acc + str::size { s } + i`         | 41 886 | 65 |
| k16 | `acc + str::size { s } + str::size { s }` | 42 230 | 64 |
| k21 | `acc + i + k`                       | 41 065 | 86 |
| k24 | `acc + i + i`, 2 params             | 41 885 |  — |
| k25 | `acc + i + k`, `k` seeded from argv | 40 964 |  — |
| k23 | `acc + i + k`, parameters annotated |    651 |  3 |

Read the pairs:

- **k12 → k20 → k16.** One more ADD costs 10x; one more CALL costs nothing. The
  trigger is the arithmetic.
- **k21 against k22.** The same operations with the tree nested the other way.
  `acc + (x + y)` is 3 915 ps and `acc + x + y` is 41 065. Left-nesting on the
  accumulator is the whole difference, and k21 needs no call at all to show it.
- **k24.** Two parameters, two adds, and the only operands are `acc` and `i` —
  and `i` traces to `argv`, so nothing here is ungrounded. Still 41 885 against
  k1's 223. **177x for one extra `+` on the same two variables.**
- **k25.** Seeding the third parameter from `argv` instead of the literal `1`
  changes nothing, which retires the "a numeric literal is `Num 'a` and does
  not ground the loop" hypothesis.
- **k23 against k21.** The same expression with `{ i: Int, acc: Int, k: Int }`
  written out: 651 ps against 41 065. **61x for an annotation**, and no
  annotation should ever be needed.

The control is flat at 223-255 ps across every run above.

#### What is actually happening

`MEZZE_TRACE_COMPILATION` on the two arms, same program, same process shape:

| kernel | `TrampolineRootNode[add]` compilations |
|---|---|
| k21 (inferred)  | 36, one of them a BAILOUT |
| k23 (annotated) | 0 |

So in the inferred arm the outer `+` is dispatched to the **`Num.add` ability
method and called as a real function per iteration**. The bailout reads

    Object of type FrameWithoutBoxing should not be materialized
    (must not pass virtual object into an invoke that cannot be inlined)

— the non-inlinable ability call forces the frame to materialise — and the
enclosing loop is OSR-recompiled ten times. That is the 41 ns and the 86 young
collections. The annotated arm reaches `DirectAdd` and never compiles `add` at
all.

#### Where it comes from, localised

`TypedASTCompiler` emits `DictMethodCallIR` for an ability operation whenever
the enclosing function has a `__dict_<method>` parameter — i.e. whenever the
function came out of the solver dictionary-passing over `Num`. And **nothing
ever folds a `DictMethodCallIR` into an inline arithmetic op.** The two places
that fold ability operations — `InlineOpMapping.foldAbilityOp` and
`BytecodeCompiler.tryFoldInlineOp` — both match
`CallIndirectIR(ReadField(x, "add"), MakeRecord{r = y})` and only that. They
cannot be widened unguarded either: `DirectAddNode`'s `@Fallback` throws a type
error, so folding a genuinely polymorphic `+` over a user `Num` type would turn
a working program into a crash.

So the fix belongs upstream of the fold: `acc + i` leaves the solver with a
ground type and `acc + i + i` does not, in the same function, with the same
operands. Three candidates, in order of how right they are:

1. **The solver grounds the nested application.** A `Num 'a` operator whose
   operands are ground has a ground result; propagating that would remove the
   dictionary from the function entirely. This is the actual fix.
2. **Monomorphise at the ground call site.** `MonoDriver` already specialises
   dictionary-passing HOFs (see the full-mono entry above); a self-recursive
   numeric loop called at `Int` is the same shape and is not being specialised.
3. **Fold `DictMethodCallIR` when the receiver is statically primitive.** Local
   and cheap, but it needs exactly the type the solver did not produce, so it is
   not independent of (1).

#### Two things this is NOT, both pinned by tests

**NOT the self-tail tier.** There are three — the fused Java loop, direct-while
and the flag fallback — and they are worth two orders of magnitude between them,
so the tier was the first place I looked. `TailLoopTierProbeTest` asserts that
k12's shape, k21's shape and k23's shape all land on the SAME tier
(`direct-while`). All three tiers now report on the `FUSED_EMITS` seam, because
all three compute the same answer and losing one is otherwise silent.

**NOT a stale project cache.** `.mz/`, `.mez/`, `target/` and `.build/` were all
deleted and the bench rebuilt from scratch; k21 read 40 783 and k23 651 on the
clean build.

#### And it explains the collection rows, which is what I was measuring

`bench/cases/mapsurface` builds every row from a FOUR-parameter loop with a
compound update, and its `map::contains_key` reads **51 ns at ONE key and 67 ns
at 16 000 keys** — converged across two n. A single-entry map's probe is one
`Objects.equals`. So ~50 ns of every row in that file is this cliff, the HAMT
walk is a few ns on top, and the rows are not comparable with `allocsurface`'s.
Any attempt to shave nanoseconds off a trie underneath a 50 ns loop is
unmeasurable by construction.

#### Two null results from that attempt, recorded so they are not re-run

Both were tried, measured interleaved against a build from identical source,
and reverted.

**The long-keyed walk run inside compiled code**, guarded on the storage so the
receiver is concrete and the walk is allocation-free arithmetic:

| row | base | with the fast path | |
|---|---|---|---|
| map has (1-entry)        | 12 077 ps | 12 334 | 0.98x |
| set has (1-entry)        | 13 116    | 12 408 | 1.06x |
| map get (1-entry)        | 16 895    | 17 148 | 0.99x |
| map::contains_key (1k trie) | 56 356 | 55 869 | 1.01x |
| set::contains (1k trie)  | 54 318    | 54 719 | 0.99x |

**The seq/list/bytes indexed reads**, where a recognised container's read is a
bounds test and one array load:

| row | base | boundary removed | |
|---|---|---|---|
| seq get   | 16 992 ps | 17 152 | 0.99x |
| list get  | 16 435    | 16 830 | 0.98x |
| bytes get | 17 480    | 17 681 | 0.99x |

Read both as "below this instrument's noise" rather than as zeros: in the same
run `str size`, a row nothing touched, read 532 ps in one arm and 2 994 in the
other, and across four builds of this session it read 505, 532, 2 994 and 4 079.
**`str::size` is not a floor and must not be quoted as one.** The only stable
reference on this bench is the counted loop at 223-255 ps.

The `InlineMapContainsKey` note asking for the boundary to be re-tested "on top
of" the dispatch removal has been answered and now carries the table.

#### Three corrections to this ledger and to the bench's own comments

- **"Kernel 9 minus kernel 12 is the cost of the argument record"** — struck.
  That rested on `__native_map_size` being undeclared; it is declared now (the
  table holds 300 signatures) and the row did not move, while `seq::size`, still
  undeclared, sits on top of it. The record ABI is not what separates
  `str::size` from the collections.
- **Genericity is not the cause either.** `bytes::size` takes a concrete `Bytes`
  and is 8 072 ps; `map::size` is generic and 9 049; `seq::size` is generic and
  9 142. A concrete receiver buys nothing here.
- **`MezzeSet.containsLong` is DEAD.** Its missing `LongTrieSetStorage` branch
  was found while testing the fast path above, and nothing calls the method —
  a long-keyed set reaches its unboxed probe through `storage.contains`. Named
  as such in place rather than "fixed" in code with no callers.

#### Instruments repaired along the way

`MEZZE_DUMP_BC` and `MEZZE_DUMP_FN` matched on `ir.getName()`, and a top-level
`let go = { i, acc } -> …` reaches the compiler with NO name — which is most
user code. `MEZZE_DUMP_BC=go_z` printed nothing for a `go_z` that was in the
file, and `MEZZE_DUMP_FN=*`, documented as dumping everything, was gated behind
`getName() != null`. `*` now means every function and an unnamed one prints its
hash; that is what made the two IR trees readable.

Still broken, and worth knowing before trusting a probe: **neither dump fires
for a `mez build` app**, although `MEZZE_BDSL_STATS` reports 8 compiles in the
same run and every compile path routes through the method that holds them. The
IR comparisons above were therefore taken in a test JVM, which lowers both
shapes to `InlineBinaryOpIR` — so the test JVM does NOT reproduce the dictionary
lowering, and anyone fixing this needs the built-app probe working first.

### RETRACTION 2026-11-09 — the 177x does not exist. `mez` on PATH embeds an EIGHT-DAY-OLD interpreter into every bench binary

The entry immediately above is wrong, and the reason it is wrong invalidates
more than itself.

`mez` on `PATH` is `~/.mezze/bin/mez`, and `~/.mezze/settings.toml` reads

    default = "nightly-202611010925"

so `mez build` embeds the runtime from that toolchain — **8 days old** — into the
app jar, whatever the working tree contains. `MEZZE_HOME=` does not change it:
that routes the CLI (the lowering) to the dev classes, and `.build/.ab-cli`
duly recorded today's `ast-nightly-202611091246.jar`, which is exactly why this
went unnoticed. The lowering was fresh and the runtime was from November 1st.

PROVEN BY UNZIPPING THE APP JAR, not inferred. `BytecodeCompiler.class` from a
`mez build` jar contains no `MEZZE_DUMP_FN` at all — a probe that has been in
that file for weeks. Built with `cli/build/install/mez/bin/mez` instead, the
same jar contains it. That is also why every `MEZZE_DUMP_BC` /
`MEZZE_TRACE_SELFTAIL` invocation against a built app printed nothing while
`MEZZE_BDSL_STATS` worked: the stats counter lives in `MezzeLanguage`, which the
old runtime also has, and the probes did not exist in the old `BytecodeCompiler`.

#### What that costs

**The 177x is gone.** Same kernels, same n, binary built by the dev launcher:

| kernel | body | stale runtime | dev runtime |
|---|---|---|---|
| k1  | `acc + i`              |    223 ps |   226 |
| k21 | `acc + i + k`          | 41 065    |   786 |
| k22 | `acc + (f(s) + i)`     |  3 915    |   724 |
| k23 | `acc + i + k`, annotated |   651   |   813 |
| k24 | `acc + i + i`          | 41 885    |   778 |

786 against 813 — the annotation is worth NOTHING, the nesting is worth
NOTHING, and there is no cliff. `TrampolineRootNode[add]`, the ability call the
compilation trace showed 36 times, is a property of the November-1 interpreter.
Everything in the previous entry that rests on those two ratios is struck: the
cliff, the "61x for an annotation", the `DictMethodCallIR` localisation, and the
claim that ~50 ns of every `mapsurface` row is loop overhead.

**And the two null results in that entry are not null — they were never
measured.** The map/set keyed-read fast paths and the seq/list/bytes indexed
reads are interpreter-side changes, so the "with the fast path" arm of each A/B
contained the November-1 interpreter in both arms. Two builds from identical
source would have given the same answer. Those tables say nothing and are
withdrawn; the changes themselves stay reverted, now for want of evidence
either way rather than for want of an effect.

#### The surface, measured properly

Binary built with `cli/build/install/mez/bin/mez`, `bench/cases/allocsurface`,
one kernel per process, n=1e7 and n=2e7, min of 6 at the larger size, converged.

| row | ps/iter | x control | young |
|---|---|---|---|
| counted loop (control)   |   226 |  1.0 |  2 |
| 4-param loop (tier ctl)  |   684 |  3.0 |  3 |
| `str::size`              |   609 |  2.7 |  3 |
| `map::size`              |   726 |  3.2 |  3 |
| `acc + i + k`            |   786 |  3.5 |  — |
| `map::contains_key`      | 2 183 |  9.7 |  — |
| `set::contains`          | 2 207 |  9.8 |  — |
| `map::get`               | 2 919 | 12.9 |  — |
| `list::get`              | 2 980 | 13.2 |  — |
| `bytes::get`             | 3 220 | 14.2 |  — |
| `seq::get`               | 3 729 | 16.5 |  — |
| **`seq::size`**          | **8 522** | **37.7** | **19** |
| **`bytes::size`**        | **8 893** | **39.3** | **19** |

`map::size` is 726 ps and 3 young collections — on `str::size`, at the floor.
The "`str::size` 416 ps against `map::size` 4 519, eleven times for the same
shape of work" puzzle this ledger has carried **does not exist either**; it was
the same stale runtime.

#### Which leaves one gap, and it has a name

`seq::size` and `bytes::size` are **37-39x the control and allocate 19 young
collections against `map::size`'s 3**, and the line between them is exactly the
native-signature table:

    declared (scalar ABI):    __native_map_size          726 ps /  3
    NOT declared (record ABI): __native_seq_size        8 522    / 19
                               __native_bytes_size      8 893    / 19

`__native_seq_size`, `__native_bytes_size`, `__native_list_size`,
`__native_set_size` and `__native_deque_size` are absent from
`META-INF/mezze/native-signatures.tsv`, so each call builds a `MezzeRecord` and
looks its one field up by name. That is the mechanism `MapOps.kt`'s header
documents at 2.0-3.3x per op when it was done for the map, and here it is 12x
in time and 6.3x in allocation. **Declaring them is the next move**, and it is
the first number in this session that is both large and certainly real.

#### The method rule this cost

**Never benchmark an interpreter change through `mez` on `PATH`.** Use
`cli/build/install/mez/bin/mez` to build the bench, and verify once per session
by unzipping the app jar and grepping `BytecodeCompiler.class` for a string only
the current tree has. `MEZZE_HOME=` is not sufficient and `.ab-cli` is
actively misleading, because both report on the CLI and the stale part is the
runtime.

### LEDGER UPDATE 2026-11-09 (later) — the collection surface, measured on a binary that contains the interpreter under test

Everything below is measured on a bench built by `cli/build/install/mez/bin/mez`
and verified by unzipping the app jar, for the reason the retraction above
gives. Three changes landed, all converged across n=1e7 and n=2e7, one kernel
per process, with the counted loop flat at 218-254 ps throughout.

#### `size` — 14.1x on Bytes, 3.8x on Seq

The emitter's `InlineMapSize` cases were keyed on the PARAMETER NAME: one scan
for `m`, one for `s`, one for `d`. These eight natives declare six different
names for the same thing, so seq, list and bytes were in none of them and every
`size` call went through the generic native. Keyed on the native's own name
instead — the operation dispatches on the container's TYPE, so what the
parameter is called never mattered — and the operation widened from five
receiver shapes to nine.

| row | before | after | young |
|---|---|---|---|
| `bytes::size` | 8 893 ps | **630** | 19 -> 3 |
| `seq::size` | 8 522 | **2 217** | 19 -> 3 |
| `map::size` (reference) | | 672 | 3 |
| `str::size` (reference) | | 577 | 3 |
| counted loop (control) | | 234 | 3 |

The young-collection count falls to the control's, so the per-call
`MezzeRecord` is gone rather than cheaper.

**AND ONLY `bytes::size` MAY BE DECLARED, which a guard test is the reason
for.** Declaring all five was tried first and `SeqOpsFusionGuardTest` refused
it: `size` is in `ChainCanonicalizer`'s vocabulary, every one of its match sites
is guarded `&& !node.isScalarABI`, and `seq::map` + `size` is 1503x on the fused
path. Not theoretical — the full suite with those four declared failed
`IterMapElementKindTest > a range mapped to Str collects into a list of Str`, so
the lost fusion changed an ANSWER. `bytes` is safe because the canonicalizer
contains no reference to `__native_bytes_` at all. Declaring bought 2.2x on top
of the operation for bytes; for seq it measured 2 182 declared against 2 217
not, so dropping the four cost ~2%.

#### `is_empty` — 5.22x on Bytes, 1.42x on Seq

All eight were DECLARED and none had an operation: one step further along the
same half-finished road, with the per-call record already gone and the generic
scalar native's boundary still there. `InlineContainerIsEmpty` is the Bool peer
of `InlineMapSize` over the same nine shapes.

| row | base | with the operation | | young |
|---|---|---|---|---|
| `bytes::is_empty` | 3 569 ps | **684** | 5.22x | 13 -> 3 |
| `seq::is_empty` | 3 525 | **2 489** | 1.42x | 12 -> 3 |
| counted loop (control) | 220 | 226 | 0.97x | |
| `map::size` (control) | 622 | 711 | 0.87x | |

Read the second control as a caution: a row nothing touched moved 0.87x, so
this instrument's floor was ~15% that day. `is_empty` is NOT in the fuser's
vocabulary, so unlike `size` it carries no fusion risk for any container.

And `map::is_empty` / `set::is_empty` never reach their is_empty native at all —
the stdlib writes them `size { … } == 0`, so they ride the SIZE operation and
were already fixed by the previous change. The first version of the test assumed
otherwise and the seam said so.

#### The indexed reads — ONE of the three, and the other two are a regression

Removing the `@TruffleBoundary` so a recognised container's read is a bounds
test and one array load looks free for all three. It is not:

| row | boundary | inline | |
|---|---|---|---|
| `bytes::get` | 3 184 ps | **2 092** | 1.52x |
| `seq::get` | 3 716 | 4 781 | **0.78x** |
| `list::get` | 3 024 | 4 250 | **0.71x** |

Confirmed three-way with seq and list put back, so the regressions are shown to
RETURN rather than assumed to: `bytes::get` 3 210 -> 2 230 (1.44x), `seq::get`
3 824 -> 3 650 (1.05x), `list::get` 2 995 -> 3 051 (0.98x), control 1.01x.

A recognised `MezzeBytes` read is `data[i]` on a final `byte[]` — nothing
polymorphic, nothing to box. `MezzeSeq.get` is a polymorphic `SeqStorage` call
that boxes, and a list read is a loop over sealed-interface calls; inlining
either grows the caller's graph for a read that does not get cheaper. **A
boundary can be load-bearing in the GOOD direction**, and this is the second
instance in this ledger.

`IndexedReadBoundaryTest` asserts the ANNOTATIONS by reflection, because all
three shapes compute the same answers either way: a value test is green
whichever side of the split a read is on, and finishing the job on the other two
would lose 1.3-1.4x silently.

#### Where the surface stands now

| row | ps/iter | x control |
|---|---|---|
| counted loop | 226 | 1.0 |
| `str::size` | 577 | 2.6 |
| `map::size` | 672 | 3.0 |
| `bytes::size` | 630 | 2.8 |
| `bytes::is_empty` | 684 | 3.0 |
| `map::contains_key` | 2 183 | 9.7 |
| `set::contains` | 2 207 | 9.8 |
| `seq::size` | 2 217 | 9.8 |
| `bytes::get` | 2 230 | 9.9 |
| `map::get` | 2 919 | 12.9 |
| `list::get` | 2 995 | 13.3 |
| `seq::get` | 3 650 | 16.1 |

Everything that reads a stored COUNT is now at 2.6-3.0x. Everything that reads
an ELEMENT or probes a KEY is at 9.7-16.1x, and that band is what is left.

#### What the band is, as far as it is known

- **The keyed probes.** `map::contains_key` is 2 183 ps against `map::size`'s
  672 on a ONE-ENTRY map, where the probe is a single `Objects.equals`. The
  1 500 ps between them is the `MapStorage` interface call, which is
  megamorphic process-wide. The long-keyed-storage fast path that would make
  that receiver concrete was tried and measured null — but it guarded on the
  TRIE storages and `allocsurface`'s map is a `SingleEntryMapStorage`, so it
  never applied to the kernel it was measured on. That is a second reason the
  null result says nothing, independent of the stale runtime, and the retest
  needs the guard widened to the small storages or a bench row with 1 000 keys.
- **`seq::size` at 9.8x** where `bytes::size` is 2.8x, with the same operation
  and the same emit path: `MezzeSeq.size()` goes through the `SeqStorage`
  interface where `MezzeBytes.length()` reads a final field. The same
  interface-call cost as the keyed probes, on a row with nothing else in it —
  which makes `seq::size` the cleanest available probe of it.
- **180 collection natives are still undeclared**: sorted 97, iter 32, nel 30,
  buffer 21, none of them with a `*Ops.kt` file. `ChainCanonicalizer` mentions
  neither `__native_nel_` nor `__native_sorted_map_`, so those two families
  carry no fusion risk; `sorted_set` and `iter` do, op by op.

#### `MezzeSeq.get` — the same move as `size`, and it measures NULL

`size` became 3.29x by stopping an interface call, so naming the storage type in
`get` the same way looked obviously right. It is not:

| row | delegating | chained | |
|---|---|---|---|
| **`seq::get`** (target) | 3 690 ps | 3 550 | **1.04x** |
| `seq::size` (control) | 665 | 622 | 1.07x |
| `bytes::size` (control) | 609 | 629 | 0.97x |
| `map::size` (control) | 544 | 632 | **0.86x** |
| counted loop (control) | 230 | 224 | 1.03x |

n=2e7, min of 3, interleaved, both arms built by the dev launcher with the
extracted `MezzeSeq.class` confirmed to differ. **The target moved 1.04x while
an untouched row moved 0.86x**, so the effect is inside the control spread and
this instrument cannot see it. Reverted; the numbers are on
`MezzeSeq.get` so the next person does not re-run them.

WHY IT DIFFERS FROM `size`, which is the part worth keeping. `size` stopped
calling anything at all — it became a field read. `get` still calls, just on a
concrete receiver; and `InlineSeqGetRaw` keeps its `@TruffleBoundary` because
removing it measured 0.78x, so `get` runs where HotSpot compiles it rather than
where partial evaluation does, and HotSpot's inline cache already covered a site
that is monomorphic within any one compiled method. **"Devirtualise the storage
call" is not a general win — it paid where it removed the call, not where it
narrowed one.**

So `seq::get` at 16x the control is still open, and the remaining band is
unchanged: the element reads and keyed probes at 9.7-16.1x, whose cost is NOT
simply the interface call.

#### The ceiling for an element read says the ROUTE is not the cost

Two fixes for `seq::get` were spent — removing `InlineSeqGetRaw`'s boundary
measured 0.78x and naming the storage type inside `MezzeSeq.get` measured null —
so instead of a third guess, kernel 28 measures what reading one element costs
through a COMPLETELY DIFFERENT route: a seq PATTERN, which reaches the element
via `SeqSizeEquals` + `SeqGetAt`, both boundary-free, with a compile-time index
and no `Option` anywhere.

| row | ps/iter (min of 6) | x control |
|---|---|---|
| counted loop | 220 | 1.0 |
| `seq::size` | 694 | 3.2 |
| `bytes::get` | 2 206 | 10.0 |
| **`seq::get`** | **3 526** | **16.0** |
| **seq pattern `[a, b] -> a`** | **3 947** | **17.9** |

**The boundary-free, Option-free route is no faster — it is slightly slower.**
So the ~3.5 ns is not the boundary, not the `Option`, and not the generic native
dispatch: all three are absent from kernel 28 and the cost is unchanged. That
retires route-shaving for this row, which is where the last two attempts went.

The box was loaded for this run — medians are 2-3x the mins across every row —
so the MIN is the statistic, and it is cross-checked: `bytes::get` 2 206 against
2 230 measured earlier, `seq::size` 694 against 665, `seq::get` 3 526 against
3 650. The three agreeing is what makes the fourth readable.

#### What is left in it, and the codebase already named it

`bytes::get` is 2 206 and `seq::get` 3 526 on element reads that are otherwise
the same shape, so ~1 300 ps separates a `byte[]` read from a seq read. The seq
in the kernel is a LAZY range, so `LongSeqStorage.getLong` is a null check and
two arithmetic ops — not 1.3 ns of work — and `storage.get` boxing its `long`
into a `Long` to satisfy an `Object` return is the one step neither route avoids.

`InlineSeqGetRaw`'s own comment names the missing move: *"the MUT version of
this pattern could split into a bounds check and a read because both were free…
that is only worth it when the read can come back UNBOXED, which is a further
step these do not take."* The mut path already has `MutInBounds` + `MutReadLong`
for exactly this. An unboxed immutable read — a bounds test plus a `long`-typed
operation, emitted when the `Some` branch binds a long — is the remaining
candidate, and it is the only one the two spent attempts did not touch.

#### The 2x2 finishes: `seq::get` is 1.21x, and NEITHER half was a fix

Two separate negative results were sitting in this ledger — removing
`InlineSeqGetRaw`'s boundary measured 0.78x, and naming the storage type inside
`MezzeSeq.get` measured null — and the kernel-28 ceiling said the route was not
the cost. What none of that had tried was BOTH AT ONCE, which is the only cell
of the 2x2 that was never measured:

| `InlineSeqGetRaw` boundary | `MezzeSeq.get` | `seq::get` | |
|---|---|---|---|
| kept | delegating | 3 561 ps | baseline |
| kept | concrete | 3 550 | null |
| REMOVED | delegating | 4 781 | **0.78x worse** |
| REMOVED | concrete | **2 949** | **1.21x** |

With the boundary gone and the call still an interface call over four storages,
partial evaluation inlines a megamorphic call and the caller's graph grows for a
read that did not get cheaper. With both gone there is one concrete array read
to inline — the shape `bytes::get` always had, which is exactly why the boundary
*alone* was 1.44x there and 0.78x here.

Converged across two sizes, min of 8 per arm, interleaved, both arms built by
the dev launcher with the extracted classes confirmed to differ:

| row | n=2e7 | n=1e7 |
|---|---|---|
| **`seq::get`** | **1.21x** | **1.18x** |
| **`seq::get`, materialised twin** | **1.18x** | **1.11x** |
| counted loop (control) | 1.01x | 1.00x |
| `bytes::get` (control) | 1.10x | 0.99x |
| `seq::size` (control) | 1.04x | 0.94x |

The materialised twin is kernel 29 — the same read on a seq LITERAL instead of a
lazy range — and it is a second independent row, not a repeat.

**AND THE NOISE NEARLY HID IT.** `bytes::get` is BIMODAL across runs (~1 200 or
~2 200 ps, a compilation lottery), so at three reps it moved 1.80x on its own
while nothing touched it, and the first reading of this experiment was
unusable. Min-of-8 plus the second size is what made 1.2x readable. A row that
swings 1.8x untouched cannot adjudicate a 1.2x claim.

#### What this says about method, which outlasts the row

A null result on A and a regression on B do not mean A+B is worthless. Both of
those readings were correct and both were published here as closed; the
combination reverses the sign. The reason is mechanical and was visible in the
earlier note — "inlining either grows the caller's graph for a read that does
not get cheaper" — which is a statement about the PAIR, and I had written it
while testing only the halves. **Where a fix removes a barrier that was hiding a
second cost, test the pair before recording the half as closed.**

List keeps its boundary: removing it alone measured 0.71x and the paired
experiment has NOT been run for it, because a list read is a loop over
sealed-interface calls rather than one indexed load, so the shape that rescued
seq may not exist there.

Where the band stands: `seq::get` 2 949 (13.6x the control, from 16.4x),
`list::get` ~3 000, `map::get` 2 919, `map::contains_key` 2 183,
`set::contains` 2 207, `bytes::get` ~1 200. The map and set probes are untouched
by any of this and are now the largest rows left.

### LEDGER UPDATE 2026-11-11 — the keyed probes, re-measured on a correct build, and an INVERSION in the promotion curve

`bench/cases/mapsurface` had only ever been measured on the November-1 runtime,
where every row read 51-67 ns flat and I concluded "the probe barely depends on
the structure". **That conclusion is struck.** Rebuilt with the dev launcher,
n=1e7, no WARMUP flags:

| keys | storage | `contains_key` | `get` |
|---|---|---|---|
| 2 | SmallFlat | 4 131 ps | 5 239 |
| 4 | SmallFlat | 5 714 | 6 718 |
| 7 | SmallFlat | 8 710 | 9 929 |
| **8** | SmallFlat (full) | **9 876** | **10 701** |
| **9** | long trie | **6 005** | **6 992** |
| 16 | long trie | 5 986 | 6 914 |
| 1 000 | long trie | 7 409 | 9 308 |
| 16 000 | long trie | 15 249 | 17 387 |

The probe depends on the structure a great deal: the flat scan is LINEAR at
~820 ps per entry while the trie is flat to a thousand keys. And the curve had
an inversion in it — **an 8-key map probed 1.65x slower than the 9-key map it
promotes to**, so the crossover sat at four or five keys and not at
`MAX_SIZE = 8`.

#### 820 ps to compare two numbers is a megamorphic `equals`

`SmallFlatMapStorage` keeps `Object[] keys`, so
`Objects.equals(keys[i], searchKey)` is a VIRTUAL `equals` on a receiver whose
type varies across the process — megamorphic, once per entry, eight times per
probe. Testing the search key's type ONCE and then comparing primitives:

| keys | `contains_key` | | `get` | |
|---|---|---|---|---|
| 2 | 4 103 -> 3 095 | **1.33x** | | 1.12x |
| 4 | 5 804 -> 3 779 | **1.54x** | | **1.44x** |
| **8** | 9 765 -> 4 783 | **2.04x** | | **1.81x** |
| 9 (trie, control) | | 1.05x | | 0.99x |
| 16 (trie, control) | | 1.00x | | 1.01x |

The gain scales with the entry count, which is what removing a PER-ENTRY cost
predicts and a fixed cost does not; the trie rows this cannot touch are flat;
checksums agree in every row. **The inversion is gone** — 8 keys now probes at
4 783 against the trie's ~5 900, so the promotion threshold finally sits the
right way round and `MAX_SIZE` needs no change.

This is the third row today whose cost was a megamorphic call —
`MezzeSeq.size` (3.29x), `seq::get` (1.21x, paired), and now the small-map scan
(2.04x). The general path is kept verbatim for every other key type, so a
Str-keyed or Double-keyed small map behaves exactly as before.

COVERAGE, and the break is verified rather than assumed. The plausible wrong
implementation is `((Number) keys[i]).longValue() == s`, which would make a
`Double` key of 1.0 answer for a `Long` key of 1. Injected, it fails two of the
six tests — the targeted case and the `HashMap` differential over deliberately
mixed key types — which is what the differential is there for.

#### What is still open

- **`map::get` and `set::contains` above the small sizes.** The trie is flat to
  a thousand keys and then climbs (7 409 at 1 000, 15 249 at 16 000) — cache
  behaviour, not algorithm, and untouched.
- **`list::get`.** 0.71x on boundary removal alone; the PAIRED experiment that
  rescued `seq::get` has not been run for it.
- **180 collection natives undeclared**: sorted 97, iter 32, nel 30, buffer 21,
  none with a `*Ops.kt`. `ChainCanonicalizer` mentions neither `__native_nel_`
  nor `__native_sorted_map_`, so those two families carry no fusion risk;
  `sorted_set` and `iter` need checking op by op.
- **Structural, characterised but not attempted**: the HAMT's unboxed trie
  values, List's chunked backing, `unfold`'s cross-function variant elision,
  ordered insert at an arbitrary position (wants a tree), BitSet unwired.
- **The dump probes still do not fire for a `mez build` app**, so IR and
  bytecode listings are only available in a test JVM.

#### …and the SAME change on the ONE-element storages is a REGRESSION

The obvious next step after 2.04x at eight keys was the one-element storages,
which is what `allocsurface`'s `map has`, `map get` and `set has` rows probe.
It measures worse:

| row | `Objects.equals` | type-tested | |
|---|---|---|---|
| `map has` (target) | 2 192 ps | 2 425 | **0.90x** |
| `map get` (target) | 2 836 | 3 091 | **0.92x** |
| `set has` (target) | 2 180 | 2 403 | **0.91x** |
| `seq get` (control) | 2 922 | 2 866 | 1.02x |
| `seq size` (control) | 646 | 595 | 1.09x |
| counted loop (control) | 225 | 234 | 0.96x |

n=2e7, min of 8 per arm, interleaved, both arms built by the dev launcher with
the extracted class confirmed to differ. Three independent target rows moved
together to 0.90-0.92x while three controls sat at 0.96-1.09x, so this is a
regression and not noise. Reverted.

WHY IT DIFFERS FROM THE EIGHT-ENTRY SCAN. `SingleEntryMapStorage.key` is a
single `@CompilationFinal` field, so partial evaluation already knows its TYPE
and devirtualises the `equals` completely — there is nothing megamorphic left to
remove, and an `instanceof` plus two `longValue()` calls on top is pure added
work. **A megamorphic call is worth removing; a call PE has already
devirtualised is not** — and "is it megamorphic" is a question about the SITE,
not about the method.

AND ONE OF THE THREE WAS DEAD CODE. `SmallFlatSetStorage` is unreachable for
long elements: `SingleElemSetStorage.add` sends two Longs to `LongSetStorage`,
which keeps a primitive `long[]` and binary searches it. A long fast path was
added there and measured before that was checked. Reachability first, then the
measurement.

So the band's one-element rows — `map has` 2 192, `set has` 2 180, `map get`
2 836 — are NOT a megamorphic-equals problem and remain open with their cause
unidentified. The three storage sizes now read: one entry ~2 200 (open), two to
eight entries 3 095-4 783 after the flat-scan fix, nine and above ~5 900 flat to
a thousand keys.

#### The same pair on LIST: 4.19x, and it is the largest of the three

`list::get` was the last untested cell of the family. Its boundary alone had
measured 0.71x and I had written "a list read is a loop over sealed-interface
calls rather than one indexed load, so the shape that rescued seq may not exist
there" — which was a reason to test it, not a reason to stop.

Removing the boundary AND naming the three cons shapes, so `head` and `tail` are
field reads on a concrete receiver:

| row | base | paired | |
|---|---|---|---|
| **`list::get`** | 3 056 ps | **730** | **4.19x** |
| `seq::get` (control) | 2 907 | 2 937 | 0.99x |
| `bytes::get` (control) | 1 280 | 1 133 | 1.13x |
| counted loop (control) | 239 | 219 | 1.09x |

Converged — 4.19x at n=2e7 and 3.65x at n=1e7, min of 8 per arm, interleaved,
both arms built by the dev launcher with the extracted class confirmed to
differ, checksums equal. **`list::get` now reads BELOW `bytes::get` and close to
the count-read floor**, from 13.3x the control to 3.3x.

A cons cell's head is a FIELD once the receiver is concrete, and a
sealed-interface call is not — the same statement as seq's, and the reason the
boundary alone hurt in both cases: inlining a megamorphic call grows the
caller's graph for a read that did not get cheaper.

The walk's fallback is the interface call rather than `null`. `MezzeList` is
sealed, so the three cons shapes plus `MezzeNil` are all of it and the fallback
is unreachable today — but writing `null` would mean a fourth cons shape makes
`list::get` answer `None` for an element that is present. **A fast path may be
slower than the general one, never different from it.**

#### Where the indexed reads stand, and what the band is now

| row | ps/iter | x control | |
|---|---|---|---|
| counted loop | 219 | 1.0 | |
| `map::size` | 672 | 3.0 | |
| **`list::get`** | **730** | **3.3** | was 13.3 |
| `seq::size` | 595 | 2.7 | |
| `bytes::get` | ~1 200 | 5.4 | |
| `map has` | 2 192 | 10.0 | open |
| `set has` | 2 180 | 9.9 | open |
| `map::get` | 2 836 | 12.9 | open |
| `seq::get` | 2 937 | 13.4 | |

The element reads are no longer the band — `list::get` is at 3.3x and
`bytes::get` at 5.4x. What is left is the KEYED probes on one-entry containers
(`map has` 2 192, `set has` 2 180, `map::get` 2 836), whose cause is now known
NOT to be a megamorphic `equals`, and `seq::get` at 13.4x, where the pair has
already been spent and `bytes::get` at 1 200 shows 2.4x of headroom remains.

#### The keyed probes: a CPU profile, then the pair — set 1.2-1.3x, map NOT demonstrated, and the wide version is 40x WORSE

Three guesses at the one-entry probe had failed (the Option, the route, the
megamorphic `equals`), so this one started with a profile instead. The built
binary does not ship `cpusampler`, so JFR execution samples:

```
69  MapNatives.containsKey(MezzeMap, Object)     <- @TruffleBoundary
49  InlineMapContainsKey.doMap(MezzeMap, Object)
```

118 of ~130 steady-state samples in two frames, with `MezzeMap` and
`SingleEntryMapStorage` inlined INTO the boundary-compiled unit. So the ~2 000 ps
is the boundary — and the earlier "boundary re-tested on top of the dispatch
removal, still null" test had never made the storage receiver concrete, which is
the configuration that measured 0.78x on seq and 0.71x on list.

**THE WIDE VERSION IS A DISASTER, and it is the useful half of this entry.**
Naming SIX storage implementations so every branch has a concrete receiver:

| row | base | six branches | |
|---|---|---|---|
| `set has` | 2 099 ps | 86 399 | **0.02x** |
| `map has` | 2 167 | 87 033 | **0.02x** |
| `map get` | 2 791 | 133 636 | **0.02x** |
| controls | | | 0.99-1.03x |

40-48x worse on all three at once, checksums still correct. Each branch is a
whole hash-map probe — one an `@ExplodeLoop` 8-way scan, another a general HAMT
walk over `hashCode`/`equals` — so partial evaluation inlined six map
implementations into the caller. On seq each branch was ONE ARRAY LOAD and on
list ONE FIELD READ. **The concrete-receiver chain pays only where each branch
is a constant number of field reads**, and that is the rule the three results
together give, which none of them gives alone.

Narrowed to the constant-time storages only — one element, or none — with
everything else kept behind the boundary:

| run | `set has` | `map has` | `map get` | controls |
|---|---|---|---|---|
| n=2e7, 8 reps | **1.34x** | 1.29x | 1.08x | 0.92-1.01x |
| n=1e7, 14 reps | **1.33x** | 1.13x | 1.01x | 0.99-1.09x |
| n=1e7, 14 reps, map half reverted | **1.22x** | 1.02x | 0.98x | 0.90-1.13x |

`set::contains` is the largest mover in all three runs. `map has` does not
converge — 1.29x then 1.13x against a 1.09x control ceiling — and `map::get` is
null, so **the map half is not shipped**. An unproven type test is exactly what
cost 10% on the one-element storages earlier today.

Stated honestly: `set::contains` ~1.2-1.3x, not a sharper figure, because
`bytes::get` is bimodal and moves 1.09-1.19x untouched on this bench. Three runs
agreeing on the direction and the ranking is what carries it, not any one ratio.

COVERAGE. SetDirectProbeTest, 6 tests, including one that asserts ONLY the
constant-time storages take the direct probe — widening that set is what
measured 0.02x and nothing else would catch it — and a differential against
`HashSet` over 150 trials of mixed element types.

#### Deque had never been fused at all — 2.50x and 2.75x, the last unmeasured read

`dsmore` on a correct build handed me two rows nobody had ever looked at:
`deque::first` 5 449 ps and `deque::get by index` 8 557 — **the slowest element
reads in the language**, 7x and 12x `list::get`'s 730. The cause was not a slow
deque. The fused `match … is Some/None` elision covers map, seq, list and bytes
and simply never covered deque, so both still allocated a `MezzeVariant` per
call and crossed a `@TruffleBoundary` to do it.

`MezzeDeque` is final and its `get` is `buffer[(head + index) & mask]` — one
array load behind some arithmetic. By the CONSTANT-TIME-BRANCH rule from the
keyed-probe entry above, that is the shape that pays:

| row | base | fused | n=1e7 | n=3e6 |
|---|---|---|---|---|
| `deque::first` | 5 449 ps | **2 177** | 2.50x | 2.81x |
| `deque::get by index` | 8 557 | **3 114** | 2.75x | 2.92x |
| `deque::size` (control) | 414 | 424 | 0.98x | 1.03x |
| `sorted_map::insert` (control) | 184 351 | 183 363 | 1.01x | 1.02x |

Converged across both n, deque's own `size` flat beside the two movers,
checksums equal, both arms unzip-verified to differ.

`deque::first` takes ONE argument and the fused emitter took exactly two, so
the generalisation was part of the work: the match shape, the local and the
`IsNotNull` test are identical, only the key operand is absent. The emitter now
accepts a one-operand member of the family, which is what any future `first`/
`last`/`peek` will need.

WHERE THE BAND STANDS NOW. bytes 1 200 (bimodal), list 730, seq ~2 000,
deque 2 177 / 3 114. Deque was outside the band by an order of magnitude and is
now inside it; the remaining spread is the ring arithmetic and the seq storage
indirection, not a boundary.

COVERAGE. `DequeFusedReadTest`, 6 tests, aimed at the RING BUFFER, because that
is what can actually break: a deque whose contents have wrapped has
`head != 0`, so a fast path that only ever saw `head == 0` passes a naive test
and is wrong in service. Two tests push at the front first and then index.
One is a warm loop — the fused read is a compiled path, and a straight-line
test exercises only its first execution.

#### Installing a handler: 1.20x, and the profile named the wrong frame

`install/iter` 54 ns and `abort/iter` 130 ns were the two largest measured gaps
in the system — 322x and 672x the counted loop. A CPU profile put **307 of ~700
steady-state samples in `ConcurrentHashMap.get`**, which is
`EFFECT_ASSUMPTIONS.get(effectHash)` running on every push AND every pop, keyed
on a 64-byte `Hash`. At any one push site the effect's `Hash` is the same
OBJECT every time, so a one-element identity memo on the per-thread
`EffectState` removes the lookup; passing the state the caller already holds
drops a `ThreadLocal.get` per push and pop with it.

**It is worth 1.06x.** A frame that is 44% of a loop is worth 1.8x when you
remove it. So the attribution was wrong, and the most likely reason is that the
sampler credited to the map the time actually spent in the monitor-enter beside
it — which is where the cost turned out to be.

`StabilityAssumption.invalidate()` was `synchronized`, and it runs on every push
and every pop for the per-effect assumption AND the process-global one: **four
monitor-enters an iteration, on two objects shared by every thread in the
process**, with biased locking long gone from the JVM. Past `CHURN_LIMIT` every
one of them is a guaranteed no-op — `get()` returns `NEVER_VALID`, so nobody
can hold a valid assumption and there is nothing to invalidate. The lock was
being taken in order to discover that there was nothing to do.

| row | base | memo | | pair | |
|---|---|---|---|---|---|
| `5 install/iter` | 54 044 ps | 50 910 | 1.06x | **45 038** | 1.20x |
| `6 abort/iter` | 130 075 | 122 448 | 1.06x | **110 747** | 1.17x |
| `3 op, 1 install` (control) | 457 | 457 | 1.00x | 454 | 1.01x |
| `1 control` | 208 | 205 | 1.01x | 207 | 1.00x |

Three arms interleaved in one invocation, min of 4 processes x min of 3 reports,
every arm unzip-verified to differ. Converged at n=5e6 (1.20x / 1.18x) and
n=1.5e7 (1.20x / 1.17x).

THE CONTROLS HAD TO BE RESIZED BEFORE THEY SAID ANYTHING, which is the
row-sizing rule biting again. At the targets' n of 5e6, row 3 is ~2 ns an op =
10 ms of work, and it read **0.81x** with a spread of 2 569 to 10 464 ps. At
2e8 it reads 1.01x. A control measured below its own cost reports the machine
and would have sunk a real 1.20x.

WHY A LOCK-FREE READ OF A GUARDED FIELD IS SOUND HERE, since it is not in
general. `gaveUpFast` is MONOTONIC: once set, `get()` returns `NEVER_VALID`
forever, so a reader that sees it true is right that invalidating is nothing,
and a reader that sees a STALE false takes the monitor and does exactly what it
did before. Only `reset()` clears it, under the monitor.

COVERAGE, AND THE REASON IT IS AT THE REGISTRY AND NOT IN THE LANGUAGE. Both
fast paths can only fail one way: by silently keeping a stale handler. And the
guest language cannot observe either, because `effectStable` guards cached Mut
and Async dispatch and an ordinary `perform … in` loop never builds one — I
wrote four alternating-handler programs, broke each fix in turn, and **all four
kept passing**. A test that cannot fail is not coverage. So the two
load-bearing tests drive `HandlerChainRegistry` directly, and each fails
without its fix:

  - the memo stores only a NON-NULL result, because an effect HAS no entry
    until a perform site specializes (they are created lazily by
    `@Cached("getEffectStableAssumption(…)")`). Caching the absence makes every
    later push for that effect take the memo and skip invalidating the
    assumption created in between.
  - `reset()` clears the mirror, or an effect that recovers never invalidates
    again for the life of the process.

The four guest-level tests stay, as the correctness-of-answers half: alternating
handlers straight-line and compiled, alternating after churn has given up, and
two effects alternating so the identity memo misses every time and has to fall
back rather than be wrong.

WHAT IS STILL OPEN HERE. 45 ns against a 0.2 ns loop is still 220x. Both
`pushSimple` and `popSimple` are `@TruffleBoundary` — they have to be, because
they open with `STATE.get()` on a plain `ThreadLocal` — so a compiled loop
cannot inline either and pays two boundary crossings an iteration. A Truffle
`ContextThreadLocal` is partial-evaluation friendly where a plain one is not,
and that is the next thing to measure.

#### The handler a content hash names cannot change — install/iter 1.61x more

With the monitor gone, a fresh profile of the FIXED build named a frame nothing
had looked at: `LookupParameterizedHandlerByHash.doLookup`, 137 of 476 samples,
with `MezzeContext.lookupHandler` (54) and `getCachedStdlibCallTarget` (68)
beneath it. The surviving `ConcurrentHashMap.get` samples (84) belong to THOSE
two, not to the map I had just removed. `perform Ones {} in …` re-resolved its
handler record on every install.

A branch counter settled what the resolution actually does: **`via factory=0,
via ctx=1`**. The stdlib-factory branch never answers for this shape — it costs
one map probe and is discarded — and the answer comes from
`MezzeContext.lookupHandler`. So the per-install cost is two
`ConcurrentHashMap` probes whose keys are 64-character hex strings, which means
a hit runs `String.equals` over all 64 characters, plus two context lookups.

WHY THE RECORD IS CACHEABLE, which is the whole of the correctness argument.
`handlersByHash` has exactly two writers: module load puts a `PerformerInfo`
under the hash, and `lookupHandler` upgrades that entry in place to the inflated
record the first time it is asked. Nothing removes an entry, nothing replaces a
record, and the key is the content hash of the performer's structure — so a
re-registration under the same hash cannot mean anything different. Once a hash
resolves to a `MezzeRecord`, that record IS the answer for the life of the
context. That branch also ignores `params`, which is why a cache of it may.

The factory branch is NOT cacheable and is excluded by construction: it CALLS a
guest factory with `params`, and a parameterized performer — `impl Sample for
Recording { seed: Int, key: Int, size: Nat }` in std::property is a real one —
needs a fresh handler per install.

| row | baseline | per-site | n=1.5e7 | n=5e6 |
|---|---|---|---|---|
| `5 install/iter` | 45 277 ps | **28 103** | 1.61x | 1.61x |
| `11 install/iter, no params` | 37 165 | **27 843** | 1.33x | 1.32x |
| `6 abort/iter` | 107 389 | **85 630** | 1.25x | 1.29x |
| `3 op, 1 install` (control) | 454 | 458 | 0.99x | 1.04x |
| `1 control` | 209 | 208 | 1.00x | 1.02x |

**The sound fix beat the ceiling probe**, 1.61x against 1.47x, and that is the
useful part of the method. The probe short-circuited `doLookup` behind a static
one-element cache — it still had to cross the `@TruffleBoundary` and read a
static field. The real specialization is a node field partial evaluation folds.
A hand-written probe measures the shape you wrote, not the shape the compiler
can produce, so a ceiling built from one is a FLOOR when the optimisation
changes what the compiler can see.

ROW 11 IS NEW AND IT EXISTS BECAUSE THE RANKING WAS BLIND. Every impl in this
bench is declared `for Ones {}` — with a parameter list, even an empty one — so
every row went through the parameterized operation. A performer declared
`for OnesBare`, with no list at all, emits a DIFFERENT operation,
`LookupHandlerByHash`, which no row reached and which paid the same per-install
probe. It was also cheaper to begin with (37 165 against 45 277: one lookup
instead of two), and both forms now land at ~28 000.

Cumulatively on this row, with the memo and the monitor: 54 044 → 28 103, 1.92x.

NO GUARD ON THE HASH, deliberately. It is a `@ConstantOperand`, so a site
carries exactly one hash and a per-site cache can never be asked about a
second; I wrote the guard first, then deleted it, because a guard against a
variation that cannot occur reads as though it can. The CONTEXT guard is
load-bearing and stays: Mezze is `ContextPolicy.SHARED`, this AST is shared
between contexts of one engine, and a record resolved in one must never be
handed to another.

COVERAGE, AND WHAT IT HONESTLY DOES AND DOES NOT PIN. Five tests assert answers
across the two branches: a parameterized performer with a different parameter
every install (straight-line and compiled), two impls of one effect at two
sites, a cacheable and a non-cacheable site in one loop, and a plain handler
installed 5 000 times. Making the cache cross-site — a static one-element memo
instead of a node field, which is the realistic mistake — fails
`two impls of one effect at two sites` with 2 150 against 3 000.

What I could NOT demonstrate, stated rather than implied: removing the guard
that excludes the factory branch breaks nothing I can observe, including the 26
property and sampling tests, because a user-defined parameterized impl has no
context record either, so the cache simply never engages for it. Both that
guard and the context guard are structural conservatism. Said plainly so no one
later reads a passing suite as proof they are unnecessary.

THE GATE CAUGHT WHAT MY OWN TESTS COULD NOT, and it is the best evidence in
this entry. The full suite failed with **"A guard was assumed idempotent, but
returned a different value for a consecutive execution"** out of
`EffectPicIntegrationTest` — which runs two contexts over one shared AST. The
Truffle DSL asserts that a guard returns the same value on consecutive
executions, and a guard that reads the CURRENT context does not.

Two things follow. First, the fix is to declare the honest contract —
`@NonIdempotent` on the context accessor — and not to weaken the guard; it
costs nothing, re-measured at 1.61x / 1.34x / 1.30x after the annotation.
Second, and more useful: I had written in the test file that the context guard
was justified "structurally, not by a test", because two contexts resolve the
same hash to structurally identical records and answers cannot tell a leak from
correct behaviour. The suite then proved the two-context case is REACHED, by
failing on it. The guard is not theoretical, and the thing that established
that was a gate on code I had already measured and believed finished.

#### The OTHER early return in `invalidate()` — 1.43x, and two negatives worth more

With the per-site handler record landed, install was 28 421 ps and the profile's
top frame was `ThreadLocal.get()` at 286 of ~2 600 samples, paid TWICE an
iteration because push and pop each look the state up. That looked like the
answer. It was not, and getting there cost two negatives that are worth more
than the win.

**PROBE: THE THREADLOCAL IS 1.31x AND THE BOUNDARY IS FREE.** Replacing the
lookup in the two hot operations with a static field — unshippable, it is the
cross-context leak this codebase has been bitten by — took install to 21 639.
Also dropping `@TruffleBoundary` from `pushSimple`/`popSimple` measured 21 439:
**the same number**. The boundary those two carry costs nothing; the lookup it
forces is the whole of it. That is worth knowing before anyone ports 26 call
sites to a `ContextThreadLocal` for the boundary's sake.

**NEGATIVE: GIVING A HOT STATELESS OPERATION A `@Cached` FIELD COST 3.8x.** A
node cannot hold a static `EffectState` — nodes are shared across threads and
that state is per-thread — but it can hold one and prove ownership, so
`EffectState` got a final `owner` and the two operations got a specialization
guarded on `state.owner == currentThread()`: a field read and a thread compare
in place of the lookup.

| row | before | with the cached state | |
|---|---|---|---|
| `5 install/iter` | 28 421 ps | 106 879 | **0.27x** |
| `11 install/iter, no params` | 27 727 | 96 875 | **0.29x** |

Checksums equal, and a counter proved the fast path was the one running:
**cached=11 999 985, slow=20**. So this is not a guard that failed to match —
it is what making a hot stateless Bytecode DSL operation STATEFUL costs. A
stateless `@Operation` needs no node instance; adding one `@Cached` field takes
it off that path, and the 7 ns lookup it saved was dwarfed by what it paid.
**Reverted.** The rule generalises: in the Bytecode DSL, the cheapest operation
is one with no state at all, and an inline cache is not free the way it is in a
plain Truffle node.

**THE WIN WAS THE MONITOR AGAIN, AND IT BEAT THE THREADLOCAL CEILING.**
`StabilityAssumption.invalidate()` has two early returns: one for an effect that
has given up (made lock-free in the previous commit) and one for an assumption
NOBODY CURRENTLY HOLDS. The second still took the monitor to discover there was
nothing to do, and a profile still had 124 of ~2 600 install samples inside
`invalidateLocked`. Reading that flag without the monitor:

| row | base | lock-free | n=1.5e7 | n=5e6 |
|---|---|---|---|---|
| `5 install/iter` | 28 338 ps | **19 781** | 1.43x | 1.42x |
| `11 install/iter, no params` | 28 343 | **19 710** | 1.44x | 1.42x |
| `6 abort/iter` | 86 404 | 87 588 | 0.99x | 0.98x |
| `9 op AFTER churn` | 1 540 | 1 461 | 1.05x | 1.02x |
| `3 op, 1 install` (control) | 465 | 458 | 1.02x | 1.00x |
| `1 control` | 206 | 205 | 1.00x | 1.03x |

19 781 is BELOW the 21 639 the ThreadLocal probe reached, so the monitor was
still the larger of the two — on 124 samples against 286. **A sampling profile
ranked them backwards**, which is the second time in this session it has done so
on this exact loop and both times in the same direction: time parked in a
monitor-enter gets credited to whatever frame sits beside it.

`abort/iter` does not move, 0.98-0.99x, and is reported rather than quietly
dropped: its 87 ns is dominated by throwing `AbortPerformException`, so an
install that got 1.43x cheaper is a small part of it. That is also the next
thing to look at on this surface.

WHY A LOCK-FREE READ IS NO WEAKER THAN THE MONITOR, which is the only claim that
matters: the monitor never ordered an arbitrary `get()` on one thread against an
`invalidate()` on another, it only made them mutually exclusive. A `get()` that
lands first is seen by a later volatile read exactly as it was seen under the
lock, and a `get()` that lands concurrently was already missable. When the flag
reads true the monitor is still taken, so the composite action — test, clear,
invalidate, count the churn — stays atomic.

COVERAGE. `a second hand-out is invalidated as well as the first`, because the
flag is CLEARED by each invalidation and so must be re-armed by each hand-out:
one round trip passes even when `get()` arms it only once, which is exactly the
bug to fear. Arming it once fails that test and `a reset clears the lock-free
mirror` with it. Cumulatively on install: 54 044 → 19 781, **2.73x**, with
`install/iter` and the parameterless form now within 70 ps of each other.

#### A string search per parameter per call — 8.73x, after four wrong answers

`bench/cases/ability_dispatch` reports a plain top-level function call at 12-17
ns against an ability method at 2-3 ns, stable over five runs: the simplest call
in the language, five times the cost of a dispatched one. Its own header says
not to quote its numbers — one run per row, integer nanoseconds — so the first
job was to put the same comparison on `coresurface`, which reports picoseconds,
one kernel per process, beside controls with known values.

**AT PS RESOLUTION THE GAP IS NOT THERE.** Direct 621, closure 626, ability 617,
ability-through-a-constraint 621 — four spellings of a call, all the same number
within noise, against a 208 ps control loop. `ability_dispatch`'s 5x was an
artifact of integer division and a single run. **That bench's warning about
itself is correct and now has a measured replacement.**

**WHAT WAS THERE INSTEAD WAS 39x.** The constraint-carrying call measured 25 321
ps when the loop around it is written as `let go = … ` inside a `do` block —
which is how every loop in Mezze is written — and 621 ps when the identical loop
is written at top level. Same call, same dictionary, same checksum.

FOUR HYPOTHESES, EACH KILLED BY A MEASUREMENT RATHER THAN BY THOUGHT. Recording
them because each was plausible and three of them had supporting evidence in
this ledger already:

1. **The lazy-stub trampoline** — a top-level function's root is a
   `LazyStubRootNode` and this ledger has three separate entries about call
   sites that cached a decision against it. A counter in the stub's `execute`
   said **34 calls** in a run of four million. Not it, and the probe cost two
   minutes against a fix that would have cost an afternoon.
2. **A missing annotation.** Added one to the slow kernel: 25 514 against
   25 489. The annotation changes nothing, which is the answer this project
   wants — an annotation that fixes a performance problem means inference is
   the bug, and here inference was not the bug either.
3. **Do-block generalization**, via `LocalLambdaSpecializer` declining
   `isRecursive`. Allowed recursion — the self-call needs no repointing, it
   resolves by name to the binding being specialized in place — and the pass
   then specialized 8 bindings. **The row did not move.**
4. **The specializer's unpinned-variable guard.** The generic type named
   `['o50, 'w50, 's50]` and unification pinned only two: `'s50` is the
   intermediate `having out=` of `acc + f{…} - 1`, which appears nowhere in the
   function type and so cannot be pinned by unifying types — though `NumAdd Int
   Int` determines it. Relaxed the guard to ignore constraint-only variables:
   **the row still did not move.** So the dictionary was never the cost.

**THEN THE PROFILE, which named it in one line.** 1 422 of ~5 500 samples in
`TrampolineRootNode.execute`, 752 in `checkPartialApplication`, and 404 each in
`MezzeRecord.getFieldIndex(String)` and `Shape.findFieldIndex` with 229 more in
`findFieldIndexSmall`. On every call, that root asks whether the incoming record
is a PARTIAL APPLICATION by looking up each parameter name in the record — **a
string search per parameter per call, behind a `@TruffleBoundary`**.

The question depends only on whether the record HAS a field per parameter, which
is a property of its field NAMES alone — which is exactly what a `Shape` is. So
remember the shape that answered "complete":

| row | base | shape-keyed | n=1e8 | n=2e7 |
|---|---|---|---|---|
| `9 dict call` (do-block loop) | 25 321 ps | **2 899** | 8.73x | 8.19x |
| `11 dict call + annotation` | 25 106 | **2 917** | 8.61x | 8.38x |
| `10 dict, top-level loop` | 621 | 620 | 1.00x | 1.00x |
| `8 ability call` | 617 | 619 | 1.00x | 1.04x |
| `6 direct call` | 620 | 623 | 1.00x | 0.98x |
| `7 closure call` | 626 | 619 | 1.01x | 1.11x |
| `5 tier control` | 419 | 419 | 1.00x | 1.01x |
| `2 effect op` | 458 | 465 | 0.98x | 1.23x |
| `1 control` | 208 | 209 | 1.00x | 1.04x |

Every control is 1.00x at a size that matches its own cost; the 2e7 column's
1.11x and 1.23x are rows getting 13 ms of work, which is the row-sizing rule
again and why the 1e8 column is the one to read.

ONLY THE COMPLETE ANSWER IS CACHED, and that is a correctness condition rather
than a tuning choice: a partial application allocates a `MezzeFunction` holding
THAT record's supplied arguments, so remembering it would hand a later caller
another caller's arguments. Caching it fails `two partial applications of one
function keep their own arguments`, which is the test for exactly that.

A PRE-EXISTING DEFECT FELL OUT OF WRITING THE TESTS, and it is not this
change's: **a partial application called from inside another function does not
complete.** It returns a function, and the arithmetic around it fails with
"Cannot add Long and MezzeFunction". Minimal shape, with recursion ruled out:

```
let add = { a, b } -> a + b
let add1 = add { a = 1 }
let call = { x } -> add1 { b = x }
call { x = 1 }
```

The same call at top level works. It fails identically with the shape cache
reverted, which is how I know whose it is. Pinned as a disabled test next to
the cache, with its minimal shape, so it starts passing the day the capture
path merges the stored arguments before the completeness check instead of
after.

#### The same change across the other surfaces: a second 4.19x, and two non-regressions

Every call with a record argument and two or more parameters reaches
`TrampolineRootNode`, so the shape cache had to be swept across the surfaces it
could touch — for a second win and, more importantly, for a regression.

| row | base | new | | |
|---|---|---|---|---|
| `defunc 4 own HOF 2 fns`, n=5e7 | 39 835 ps | **9 517** | **4.19x** | 6 reps |
| `defunc 4`, n=2e7 | 45 130 | **14 575** | 3.10x | 6 reps |
| `defunc 3 own HOF` | 696 | 697 | 1.00x | control |
| `defunc 6 stdlib fold` | 700 | 689 | 1.02x | |
| `defunc 1/2/5/7` | | | 0.98-1.01x | |
| `dsmore 9/10 deque reads` | 2 139 / 3 166 | 2 104 / 3 126 | 1.02x / 1.01x | |
| `dsmore 12 sorted_map` | 181 455 | 184 668 | 0.98x | |
| `effectsurface 3/5/6` | | | 1.02-1.10x | |

A user-written higher-order function called with TWO different function
arguments is the second-largest beneficiary in the language so far, and it is
the same cause: a megamorphic callback site pays the partial-application check
on every call.

**THE FIRST PASS REPORTED THREE REGRESSIONS THAT DO NOT EXIST**, and the reason
is the row-sizing rule yet again. `defunc` rows 3, 5 and 6 cost ~700-1 000 ps,
so at n=2e7 they get 14-26 ms of work and read 0.60x, 0.73x and 0.79x. At
n=2e8 they read 1.00x, 1.00x and 1.02x. Row 6's 0.85x survived one more round
and still was not real: six interleaved reps put base at 700-943 and new at
689-928, so the 0.85x was a single low base sample. **A ratio whose two
distributions overlap is not a result**, and three of them in one table is a
sizing problem rather than three regressions.

#### `iter::count` was 88x `iter::sum` for doing less — a constant declared with no users

`bench/cases/iter_perf_compare` had been built but never run. Run, over 1e7:

| row | ps/elem |
|---|---|
| `reduce` | 70 |
| `sum` | 84 |
| `filter+fold` | 129 |
| `filter+map+fold` | 171 |
| `any` / `all` / `find` | ~410-434 |
| `filter+to_seq` | 1 733 |
| **`count`** | **7 430** |

`count` is 88x `sum` while doing strictly LESS per element — `sum` walks the
same source and also unboxes and adds. And the two natives are structurally
identical: `IterNatives.countAll` and `IterNatives.sum` are both one
`walkElements` with a one-line lambda.

So the gap was never in the native. `sum` FUSES and `count` did not, and the
reason is as small as it gets: **`NativeHofNames.OP_COUNT_ALL` was declared and
had no users.** The constant existed, nothing referenced it, and so the one
iter sink that asks only "how many" was the only one left out —
`size`-over-a-chain fused, predicate `count` fused, `any`/`all`/`find` fused,
and `SinkIR.Count` was already built and emittable.

| row | base | fused | |
|---|---|---|---|
| `count(0..10M)` | 67 461 165 ns | **876 363** | **76.98x** |
| `sum` | 805 162 | 736 085 | 1.09x |
| `filter+fold` | 779 916 | 795 183 | 0.98x |
| `filter+map+fold` | 1 664 343 | 1 669 773 | 1.00x |
| `find` / `any` / `all` | | | 1.03x / 0.98x / 1.00x |
| `filter+to_seq` | 16 977 377 | 16 952 842 | 1.00x |
| `seq_sum` / `seq_fold` (controls) | 525 197 / 536 798 | 524 545 / 528 713 | 1.00x / 1.02x |

`count` lands at 88 ps/elem, level with `sum`'s 74.

ONE DIFFERENCE FROM THE `size` CASE, AND IT IS THE WHOLE CORRECTNESS OF IT.
`size` over a chain refuses a STAGELESS chain on purpose: a materialised
collection knows its size in O(1), and routing that through a counting sink
would make an O(1) question O(n). An `Iter` has no such answer — `count` walks
whatever is behind it either way — so for this sink the stageless case is
exactly the one worth fusing. Opaque sources are refused for the reason
`iter_sum` refuses them: a value typed `Iter` could be an iterator over
anything, and the pass cannot see what.

TWO ROWS MOVED THAT I AM NOT CLAIMING. `reduce` reads 1.61x and
`seq_filter+fold` 1.64x, and both run AFTER `count` in a harness that puts
every row in ONE process. In the base arm they follow 67 ms of megamorphic
descriptor walking; in the fused arm they follow a counting loop. That is
cross-row contamination, not a result — the same reason the method requires
interleaving rather than blocking. The honest reading of this table is one
number: `count`, 76.98x.

COVERAGE, AND WHY IT IS VALUE TESTS. `BytecodeCompiler.FUSED_EMITS` records
nothing for an `iter` chain and a typechecker lowering test lowers the stdlib
FROM SOURCE, where the pipeline fuses either way — so neither can tell the
fused path from the native one, which is already in this ledger as the
blindness that nearly let the local-lambda work ship unproven. The evidence for
the change is the measurement above on a real `mez build`.

What the seven tests DO catch is the defect this sink is prone to: counting the
wrong POPULATION. Stages run outer-to-inner, and getting that order wrong makes
a stage filter the SOURCE instead of the chain. Reversing the stage list fails
`count after a map then a filter sees the mapped values` with **expected 4 but
was 5** — the identical symptom the predicate-`count` branch records in its own
comment, from when that order was wrong there.

AND THE AUDIT THAT FOUND IT APPLIES TO SEVEN MORE NAMES. `OP_COUNT_ALL` was not
alone: `OP_FLAT_MAP`, `OP_ZIP`, `OP_CHAIN`, `OP_ONCE`, `OP_EMPTY`, `OP_UNFOLD`
and `OP_SUM_VALUES` are also declared in `NativeHofNames` and never referenced
by `ChainCanonicalizer`. `flat_map`, `zip` and `chain` are stages a real
pipeline uses, and no row in any bench exercises them — which is how `count`
stayed hidden too. Next.

#### A folder that reads a field of its element refused the whole chain — 37.22x

Chasing why `zip`, `enumerate`, `chain` and `flat_map` all measured 190x to
1 036x the fused rows turned up something much more general than four missing
stages.

`ChainCanonicalizer.isPureArithTree` admitted two leaves — a local and a
literal. A FIELD READ was not one of them, so `{ acc, x } -> acc + x.v` was
refused; and the comment on that predicate's own Pattern 1c says what a refused
folder does: **it declines the WHOLE chain.** Any pipeline whose elements are
RECORDS therefore ran element-at-a-time the moment the folder read a field
instead of the whole element. Records are what `zip` produces, what `enumerate`
produces, and what every row of real data is.

ISOLATED WITH NEITHER `zip` NOR `enumerate` INVOLVED, a range mapped to
`{ v = x }` over 1e7, and the only difference between the two rows is WHERE the
field is read:

| row | ps/elem |
|---|---|
| field read in the FOLDER | **15 744** |
| field read in a MAP stage, bare `acc + x` folder | **403** |

39x for moving a field access from one lambda to another. A field read is one
load, exactly like the `ReadLocalIR` sitting beside it in that grammar; the only
reason it was absent is that nobody had written it down.

| row | base | fused | n=1e7, 6 processes |
|---|---|---|---|
| `record, field in FOLDER` | 151 707 470 ns | **4 075 491** | **37.22x** |
| `record, field in MAP` (same work) | 4 093 103 | 4 055 644 | 1.01x |
| `filter+fold` | 975 593 | 1 056 792 | 0.92x |

The target's distributions do not overlap at all — 151.7-152.7 ms against
4.08-4.34 — and the two record rows now cost the SAME, which is what a fixed
cliff looks like rather than a shifted one.

BOTH GRAMMARS, AND THE SECOND ONE IS WHY THE FIRST TEST OF THE EMIT PASSED
WRONGLY. There are two predicates: `ChainCanonicalizer.isPureArithTree` decides
whether to fuse at all, and `BytecodeCompiler.isPureArithFoldTree` decides
whether the emitter inlines the folder's body as a TREE. With only the first
extended, the chain fused and the emitter fell back to
`FusedSinkMode.AccumulateCall` — the folder called once per element — which is
already 36.57x and within 2% of the tree. **So the emit half was unreachable,
and breaking it on purpose changed nothing**: I swapped the accumulator and
element locals in the new leaf and all six tests still passed. Extending the
emit predicate made the path live, the same break then failed four of the six,
and the loop's per-element call became a field load: 4 075 491 against
4 168 111.

That is the useful part. A test that cannot fail is worthless, and the way to
find out is to break the thing on purpose — twice, if the first break says
nothing.

WHAT THIS DOES NOT FIX. `zip` (1 031x), `enumerate` (1 036x), `flat_map` (480x)
and `chain` (190x) are unchanged by it: `zip`, `chain` and `flat_map` have no
`StageIR` at all, so a chain containing one cannot fuse whatever its folder
looks like, and `enumerate` has a stage and a peel and still does not fuse in
any of the four shapes measured. Those remain open, with rows now in the bench
for each.

AND THE HARNESS GOT A ROW SELECTOR, because without one it could not answer
this question. Every row ran in ONE process, so when the target row's cost fell
143 ms, three rows that run after it read 0.82-0.93x — they inherit a different
JIT and GC state. The same thing happened in the other direction when `count`
was fixed. `bench_iter_perf_compare <row>` now runs a single row, as every other
bench here does. With one kernel per process the seq controls read 0.88x, 1.07x,
1.28x and 1.04x across eight interleaved processes — wandering in both
directions with overlapping distributions, which is what noise looks like and
what a single blocked run dressed up as a regression.

#### `enumerate` consumed by its fold — 61x, and the record was never the problem

`iter::enumerate` has a `StageIR.Enumerate`, a `tryPeelIterEnumerate`, and a
comment on that peel claiming 124 247 ps/elem down to 93. It measured
**75 049 ps/elem against `sum`'s 72 — 1 036x** — and in four shapes, not one:
the sink reading `.val`, the sink reading `.i`, a map between the enumerate and
the fold, and `.i` alone. So the peel's claim holds for some chain, and not for
an iter chain with a fold sink.

The emitter says why in one line of its own debug output: **"enumerate record
reaches the sink"**. It walks the stages in application order, tracks whether
the `{i, val}` record is still live, and declines the chain if anything still
live arrives at a sink — because the sink classifiers do not read records.

That is the ordinary way to use an enumerate. And the record is NEVER BUILT:
`x.val` is the element, which the loop already holds in its element local, and
`x.i` is the index, which it already holds in the local the Enumerate stage
maintains. A `MapInline` with `leftIsIndex` has resolved them that way all
along; the fold was simply not allowed to.

| row | base | fused | n=1e7, 4 processes |
|---|---|---|---|
| `enumerate+fold`, reads `.val` | 386 456 952 ns | **6 326 505** | **61.09x** |
| `enumerate+fold`, reads `.i` | 387 972 920 | **6 416 391** | **60.47x** |
| `enumerate+map+fold` | 488 767 951 | 491 752 817 | 0.99x |
| `record, field in FOLDER` | 4 070 300 | 4 140 879 | 0.98x |
| `sum` / `filter+fold` / `count` | | | 1.05x / 1.66x / 0.91x |

Distributions do not overlap: 386-835 ms against 6.3-7.1 ms. The 1.66x on
`filter+fold` is one slow base sample in four — the same row reads 0.98-1.00x
everywhere else in this ledger.

TWO CONDITIONS, AND BOTH ARE CORRECTNESS RATHER THAN TUNING. The gate now
admits a live record at the sink only when the sink is a fold whose folder is
the TREE form, and only when the folder reads nothing off the element but `i`
and `val`. The tree form is required because `AccumulateCall` hands the folder
the raw element — a folder expecting a record would read fields off a Long. The
field-name restriction is required because any other name means the element is
not the record this analysis believes it is.

THE TESTS ARE BUILT AROUND ONE MISTAKE: resolving a field NAME to the wrong
local. Every case uses a range whose `start` is not 0, so the index and the
value DIFFER — over `0..n` they are equal and a test cannot tell them apart,
which is exactly the test that passes while the two are swapped. Swapping them
fails four of seven, with the values exchanged (60 for 10, 10 for 70). The
three that survive are the ones summing BOTH fields, which is the
symmetric-error blindness this ledger already records for round trips, and the
reason the asymmetric cases are there.

`enumerate+map+fold` stays at 1 036x and is NOT fixed by this: it declines
earlier, in the map classifier, because `{x} -> x.val + x.i` is not a shape
`classifyUnaryLiteralLambda` can read. Still open, with a row for it.

#### A fold over a chain is two folds — 46.72x, with no new stage

`iter::chain` cannot have a `StageIR` and never will: a stage sees one element
at a time and a chain is two SOURCES. So a chain anywhere in a pipeline left it
unfused — 13 777 ps an element against 72 for `sum`, 190x — and the obvious fix
is a second source in the fused loop, which is a feature.

It needs no feature. A LEFT fold over `a ++ b` is a fold over `b` seeded with
the fold over `a`: same elements, same order, `f` applied the same number of
times, `init` evaluated once. So `fold(chain(a, b), init, f)` is rewritten to
`fold(b, fold(a, init, f), f)` and both halves go back through the walk as
ordinary chains, which it already knows how to fuse.

There was a precedent sitting next to it: `tryDistributeOverIf` pushes a chain
into the branches of an `if` the same way — rewrite the IR, feed it back, let
the existing machinery do the work.

| row | base | rewritten | n=1e7, 4 processes |
|---|---|---|---|
| `chain+fold` | 244 476 053 ns | **5 232 364** | **46.72x** |
| `flat_map+fold` | 348 658 417 | 354 316 857 | 0.98x |
| `enumerate+fold` | 6 343 005 | 6 358 965 | 1.00x |
| `zip+fold` | 623 105 815 | 440 059 816 | 1.42x |
| `sum` / `count` / `record field in FOLDER` | | | 0.99x / 1.00x / 1.01x |

Distributions: 244-385 ms against 5.23-5.54. `zip`'s 1.42x has base 623-800
against new 440-783 — overlapping, and nothing in this change touches it.

THE GUARD IS ABOUT EVALUATION ORDER, NOT THE FOLDER. The rewrite moves WHEN the
second source EXPRESSION is evaluated: `chain { a, b }` builds both descriptors
before folding anything, and afterwards `b` is built only once the fold of `a`
has finished. For a range that is nothing, but an arbitrary expression could
perform an effect and this pass has no effect analysis to lean on —
`isEffectSafe` is a stub that returns `true`. So both operands must be built
from a whitelist of iterator constructors and stages over locals, captures,
globals and literals. **A lambda is exempt**, which is worth stating because it
looks like the risky case: this rewrite cannot change how often or in what order
a stage's callback runs, only when the descriptor holding it is allocated.

The first version of the whitelist left out `ReadGlobalByHashIR` and declined
the bench's own row, where `b = iter::range { start = half, end = n }` reads a
top-level `let n = 10000000`. `a` was safe and `b` was not, both being
`__native_iter_range` — which is what the probe printed, and what sent me
looking at the operands instead of the shape.

THE TESTS PIN ORDER, because this is a semantic rewrite and can be WRONG where a
fusion gate can only be slow. A commutative folder cannot tell the halves apart
— `sum` gives the same answer either way — so the order tests fold with
`acc * 10 + x`, whose result is the digits in the order they were visited.
Swapping the halves fails four of eight: 1234 becomes 3412, 12345 becomes 45312,
and the four sum-shaped tests pass throughout, which is exactly why they are not
enough on their own.

#### A map stage that is an EXPRESSION — 121.60x, and the 4.4x next to it is SIMD

`classifyUnaryLiteralLambda` reads one shape: `x <op> rhs`. A mapper with two
operations is not that shape, so the stage became a `MapCall` and the loop
called the lambda per element. Two rows, differing only in the number of
operations in the mapper:

| row | ps/elem |
|---|---|
| `map { f = {x} -> x * 2 }` | 132 |
| `map { f = {x} -> x * 2 + 1 }` | 576 |

4.4x for one more arithmetic operation. A body that is a pure arithmetic tree is
something the loop can emit as written — exactly what `AccumulateTree` does for
a folder — so `ClassifiedStage.MapTree` does that, reusing the folder tree's
emitter and its `.i`/`.val` resolution.

| row | base | tree | n=1e7, 4 processes |
|---|---|---|---|
| `enumerate+map+fold` | 500 105 932 ns | **4 112 778** | **121.60x** |
| `map 1 op + fold` | 1 220 532 | 1 253 413 | 0.97x |
| `map 2 ops + fold` | 5 565 888 | 5 719 776 | 0.97x |
| `filter+map+fold` | 1 535 520 | 1 447 921 | 1.06x |
| `enumerate+fold` / `sum` / `chain+fold` | | | 0.99x / 0.97x / 1.03x |

**THE ROW IT WAS BUILT FOR IS NOT THE ROW THAT MOTIVATED IT, and that is the
finding.** `enumerate+map+fold` could not fuse AT ALL — a call form must never
be handed the `{i, val}` record, which is never built, so the chain declined
outright: 121.60x. The two-operation mapper over a range, which is what sent me
looking, **did not move at all**: 0.97x.

So the 4.4x between a one- and a two-operation mapper is NOT the per-element
call. A tree emit measures the same as the call. It is VECTORISATION — a
one-operation `MapInline` reaches a loop the compiler vectorises and everything
else runs scalar, which this ledger already records for FLP (75 ps with SIMD,
292 without).

**AND MEASURING IT THE WRONG WAY ROUND PROVED IT FROM THE OTHER SIDE.** The first
version tried the tree FIRST, which took `{x} -> x * 2` off the inline path:
4 327 913 ns against 1 316 990, **3.3x worse**, and `filter+map+fold` went with
it at 5 682 232 against 1 664 343. So the tree is tried only where the
alternative is a call — a fallback, not a preference — and a one-operation
mapper keeps the vectorised path. A new tier that is better than ONE of the
paths it sits between is a regression if it is reached before the other.

Captures are refused, for the reason `isPureArithFoldTree` refuses them: a
`ReadCaptureIR` addresses the lambda's capture array and emitting it in the
loop's frame needs the capture lifted, which the one-operation matcher has
machinery for and a tree does not. A capturing mapper keeps the path it had.

Coverage: eight value tests, with the enumerate cases built so the index and the
value DIFFER. Passing the wrong record flag into the tree emitter fails two of
them with "Expected record for field 'val', got: Long" — the field read landing
on a raw element, which is the one way this is silently wrong.

#### The async surface is JDK-bound: Loom's own floor is 5.3 µs, Mezze is 1.9x it

`asyncsurface` had been built and never swept. Its rows report against a counted
loop, which makes them look like this:

```
  spawn + await        : 10 212 547 ps/op   x3314172/100 floor
```

Three million times the control. That ratio is arithmetically true and tells you
nothing: it compares creating a thread to adding two numbers. **The question is
what Loom costs**, and that needed a reference outside Mezze.

Plain Java, same JDK (GraalVM CE 25.0.2), same machine, same shape — create a
virtual thread, run a trivial body, wait for its result:

| | ps/op |
|---|---|
| `Thread.ofVirtual().start()` + `join()` | 5 324 047 - 5 999 554 |
| `newVirtualThreadPerTaskExecutor().submit()` + `get()` | 3 694 968 - 4 866 857 |
| **Mezze `Async.spawn` + `await`** | **10 212 547 - 11 165 827** |

So **Loom itself is 5.3 µs and Mezze is 1.9x that** — not a cliff of the kind
this session has been finding, and the headline ratio against a counted loop was
hiding a 5.3 µs floor that no amount of work on Mezze can move. The executor
variant being 30% cheaper than thread-per-task is the one hint of headroom, and
`Async.spawn` is thread-per-task: `env.newTruffleThreadBuilder(…).virtual(true)
.build()` then `start()`, once per spawn. A guest thread MUST be registered with
the polyglot context, which is why it cannot simply be `Thread.ofVirtual()`, and
amortising that would mean a pool of long-lived pre-registered Truffle threads —
a design change with consequences for thread locals, handler chains and
cancellation, not a patch.

Where the remaining ~5 µs goes, from reading the spawn path rather than
measuring it: the thread registration above, `snapshotHandlerChainEntries` (an
array copy of the chain per spawn so the task can find the handlers in scope at
the spawn site), `lookupScopeVar`, and a `MezzePromise` plus semaphore.

TWO THINGS ABOUT THE HARNESS, both of which cost time before any number
appeared. With no row argument (`sel = 0`) it prints the header and the control
and **no rows at all** — the five `report` calls produce nothing, and the same
invocation with an explicit row works, so the all-rows path is broken rather
than slow. And row 1 at n=2000 did not finish inside 400 s while n=800 finished
in under a second at 11 µs an op; the per-op cost FALLS with n (36.7 µs at 100,
12.9 at 200, 10.2 at 400, 11.2 at 800), so that is warmup amortising and not a
leak, which makes the n=2000 behaviour an anomaly rather than a slope.

Recorded rather than chased: this surface costs minutes a measurement, its floor
is two thirds JDK, and the iter surface in the same session had cliffs of 37x to
122x against a floor of its own making.

#### Two polymorphic callbacks in one program cost 11.6x — and adding a row to the bench moved the cliff

`bench/cases/defunc` row 4 ("own HOF 2 fns") is 10 279 ps an iteration against
row 3's 886 for the SAME higher-order function with one callback. Counters in
every call operation say what the difference is, and it is not a cache tier:

| row | indirect | scalar1 | scalar2 | direct1 |
|---|---|---|---|---|
| 3, one callback | 0 | 0 | 0 | 0 |
| 4, two callbacks | 2 999 970 | 0 | 0 | 1 499 985 |

**With one callback the HOF is specialised and inlined away to nothing** — no
call of any kind survives. With two, three million generic record-ABI indirect
calls appear, one per element. The mono trace agrees: both of row 4's references
resolve `inst=null`, with a site type carrying an undetermined `'out#2028`, so
the sites call the generic `apply_fold` and the callback arrives as a runtime
value.

THE BISECTION, each step its own build:

| shape | ps/iter |
|---|---|
| one call, one callback (row 3) | 886 |
| one call, annotated callback (row 7) | 913 |
| one call in OPERAND position (`… + 0`) | 935 |
| one call passing `n / 2` | 993 |
| **two calls, the SAME callback** | **652** |
| two calls, two callbacks (row 4) | 10 279 |
| two calls, two callbacks, **a HOF copy each** | 9 718 |
| two calls, two ANNOTATED callbacks | 1 146 |

So it is not the call count, not operand position, not the divisor, and not the
call site being bimorphic — a HOF copy per callback is just as slow. What it
takes is **two distinct UNANNOTATED callbacks in the program**: each is
polymorphic over its numeric type and carries `__dict_mul`/`__dict_add`, and
with two of them present, sites that each see only one lose the specialisation
anyway.

Ruled out on the way: the two callbacks in that row have IDENTICAL bodies
(`x * 2 + 1`), and Mezze identity is content-addressed, so they may be one
declaration. Giving the second a different body (`x * 3 + 1`) and interleaving
the two bench sources measured **1.03x** — identity is not the mechanism.

**AND THE MEASUREMENT MOVED WHEN I ADDED A ROW TO MEASURE IT WITH.** Adding
`step_mono2` (a second annotated callback) and its kernel took row 4 from
~10 300 ps to **2 102** in the same runtime. Nothing about row 4 changed. So
whether a site gets specialised depends on what else the MODULE contains, which
is consistent with `inst=null` being a seeding outcome rather than a property of
the site — and it is why these diagnostic rows are NOT being committed: a row
added to study this cliff perturbs it. Row 4 as committed reproduces at 10 279
and 10 669 across two runs.

Where a fix would go: `MonoDriver.fillHavingOutTypeArgs` already resolves a
`having out` from the matching impl's `associatedTypeBindings` and its comment
records 700x for doing so. The same treatment for a reference whose site type
carries an undetermined output var is the shape of the fix, and the same
unresolved-output pattern blocked `LocalLambdaSpecializer` earlier in this
session (`['o50, 'w50, 's50]`, with only `'s50` left unpinned). Two separate
cliffs now point at one missing determination.

#### Operands DO determine the output — and neither cliff moved

Two cliffs pointed at one missing determination, so I made it.
`AbilityChecker`'s improvement phase computes open OPERANDS from a known output,
and returned before it could do the reverse:

```kotlin
val open = resolved.indices.filter { resolved[it] is MezzeType.VarType }
if (open.isEmpty()) return null      // no open operand, no work
```

That skipped the one case where the operands ARE the evidence —
`NumAdd Int Int having out = 's50`, both operands concrete, one impl matching
them, `out` left a variable forever. Everything needed was already in the
function: it computes what each candidate impl would pin an open output to. The
fix is to compute the outputs BEFORE the early return and take it only when
both are empty.

**It fires**: 22 `NumMul Int Int -> Int` and 8 `NumAdd Int Int -> Int` in one
`defunc` build, outputs that were previously left open. The typechecker suite
passes with it.

**And neither cliff moved.** `defunc` row 4 reads 9 657 ps against 10 279
before; `coresurface` row 9 reads 2 999 against 2 995. So the undetermined
output was NOT what made `MonoDriver` resolve those references with `inst=null`,
and it was not what made `LocalLambdaSpecializer` decline — I re-applied the
recursion relaxation on top of it to check, and that moved nothing either.

REVERTED, both of them. A solver change that makes inference strictly more
determined is defensible on its own terms, but it changes inferred types across
every program, the risk it carries is the kind that surfaces at RUNTIME rather
than in the typechecker suite, and it bought nothing measurable. The two cliffs
stay open with their causes narrowed by one hypothesis each:

  - the HOF cliff is not the undetermined `out`; `inst=null` has another cause,
    and the cliff is module-sensitive (adding an unrelated declaration took it
    from ~10 300 ps to 2 102).
  - `LocalLambdaSpecializer` declines for something other than the unpinned
    `having out` variable, since pinning it changed nothing.

Written down because the next person to look at `inst=null` will have the same
idea, and this is two hours of it already spent.

#### Two operations are two maps — 5.58x, and the tier below it was the wrong answer

The entry above measured a two-operation mapper at 576 ps an element against
132 for a one-operation one and concluded the difference is VECTORISATION, since
emitting the body as a tree changed nothing. That conclusion was right and the
fix it suggested — a wider kernel — was not needed.

**FLP already takes several map stages.** `FLP_MAX_EXTRA_MAPS` is 2, packed into
an extras table as `(op, literal)` pairs, because a chain of two `map` calls had
to fuse. So a two-operation mapper does not need a wider kernel; it needs to be
two stages instead of one. `{x} -> x * 2 + 1` IS `map(x * 2).map(+ 1)`.

No lambda is minted for it — `ClassifiedStage.MapInline` carries an op and a
literal rather than a function, so the pair is constructed directly. The folder
path already does the same decomposition for `acc + x * x`, which its own
comment calls "map(f) + fold(OP)".

| row | base | decomposed | |
|---|---|---|---|
| `map 2 ops + fold` | 5 779 843 ns | **1 035 553** | **5.58x** |
| `map 1 op + fold` | 1 228 408 | 1 249 156 | 0.98x |
| `enumerate+map+fold` | 4 188 038 | 4 280 117 | 0.98x |
| `filter+map+fold` | 1 531 355 | 1 184 387 | 1.29x (one low sample) |
| `count` | 1 234 098 | 769 074 | 1.60x (one low sample) |
| `sum` | 672 516 | 687 945 | 0.98x |

5.58x on the min and about 3.4x on the typical value: the base is 5.78-5.96 ms
across four processes and the new arm 1.04-1.76 ms, so no overlap either way.
**The decomposed two-operation mapper now beats the one-operation row** (1 035
553 against 1 249 156), which is what being on the same vector kernel with one
more packed op looks like.

Rows 3 and 4 each read a win from a SINGLE low sample with their other three
inside the base's range — not results, and listed so the table is not read as
four wins.

LEFT-LEANING AND LITERAL-ONLY, deliberately: the parameter must be the LEFT
operand at each level, because `MapInline` means `x <op> lit` and `lit - x` is
not that, and a dynamic operand cannot ride in the extras table at all since the
emitter's child list is fixed. Anything else falls through to the tree tier,
which is correct and merely scalar. An enumerate record is refused outright —
these stages are arithmetic on a long, and the `{i, val}` resolution lives in
the tree emitter.

THE TESTS ARE ABOUT INVERSION, because a decomposition can silently reorder and
most inputs will not show it. `x * 2 + 1` reads the same either way for a lot of
numbers; `(x - 3) * 2` and `x * 2 - 3` do not. Swapping the two emitted stages
fails five tests with exactly the inverted values — 14 becomes 17, 18 becomes
15, 9 becomes 15 — and the commutative cases keep passing, which is why the
non-commutative ones are there.

#### Where every swept surface stands at the end of this session

One row per process, min of the reports each row prints, sizes matched to each
row's own cost. The iter rows are nanoseconds for 1e7 elements; divide by 10 for
ps per element.

**iter** — `sum` is the floor at 65 ps an element.

| row | ns / 1e7 | ps/elem | vs `sum` | |
|---|---|---|---|---|
| `sum` | 654 211 | 65 | 1.0x | |
| `reduce` | 890 876 | 89 | 1.4x | |
| `count` | 1 196 277 | 120 | 1.8x | **was 7 430** |
| `filter+fold` | 1 313 111 | 131 | 2.0x | |
| `map 1 op` | 1 326 175 | 133 | 2.0x | |
| `map 2 ops` | 1 641 434 | 164 | 2.5x | **was 578** |
| `filter+map+fold` | 1 687 217 | 169 | 2.6x | |
| `enumerate+map` | 4 212 155 | 421 | 6.5x | **was ~50 000** |
| `record field in FOLDER` | 4 196 105 | 420 | 6.4x | **was 15 170** |
| `any` / `find` / `all` | ~4 200 000 | ~420 | 6.5x | |
| `chain+fold` | 5 536 467 | 554 | 8.5x | **was 13 777** |
| `enumerate+fold` | 6 539 238 | 654 | 10x | **was 75 049** |
| `filter+to_seq` | 17 193 521 | 1 719 | 26x | materialisation |
| `flat_map+fold` | 417 133 027 | 41 713 | **637x** | OPEN |
| `zip+fold` | 732 837 787 | 73 284 | **1 120x** | OPEN |

Everything on this surface is now between 1.0x and 10x of the floor except
three rows. `filter+to_seq` writes 5M longs into a growing builder, so 1.7 ns an
element is work rather than a cliff. The other two are features, not fixes:

  - **`zip` needs two cursors in one loop.** A stage sees one element at a time
    and a zip has two sources. The bench row zips two RANGES, which could be
    rewritten (zipping a 0-based range with anything IS `enumerate`, which now
    fuses), but that serves the synthetic row and not the real shape, which is
    two collections. Left alone deliberately.
  - **`flat_map` needs a nested loop.** `fold(flat_map(src,f), init, g)` is
    `fold(src, init, {acc,x} -> fold(f(x), acc, g))`, which is the same trick
    the chain rewrite used — except it needs a NEW lambda minted and registered
    rather than existing nodes reshuffled, so it is not a reshuffle.

**coresurface** — a counted loop is 211 ps.

| row | ps/iter | |
|---|---|---|
| control / option match / variant match | 211 / 214 / 214 | at the floor |
| tier control | 416 | |
| effect op | 541 | |
| direct call / closure call / ability call | 625 / 630 / 628 | all the same |
| dict call, top-level loop | 622 | |
| **dict call, do-block loop** | **3 004** | 4.8x, OPEN — was 25 321 |

The four spellings of a call are within 1% of each other, which is the answer to
the question `ability_dispatch` was built to ask and got wrong by 5x.

**effectsurface** — a counted loop is 233 ps.

| row | ps/iter | |
|---|---|---|
| op, one install | 1 165 | |
| op AFTER churn | 1 888 | |
| install/iter | 20 217 | **was 54 044** |
| install/iter, no params | 20 213 | **was 37 056** |
| abort/iter | 86 050 | OPEN, diffuse |

`abort` is the largest single number left in the system and it has no cliff in
it: its profile is spread across the churned perform tier re-resolving per call,
`ThreadLocal.get`, and the unwind, with the exception itself nowhere — it is
already stackless.

**dsmore** — `deque::size` is 430 ps.

| row | ps/op | |
|---|---|---|
| `deque::first` | 2 496 | was 5 449 |
| `deque::get by index` | 3 308 | was 8 557 |
| `sorted_map::insert` | 186 859 | O(log n) over 1 000 keys |

**async** is JDK-bound: Loom's own floor is 5.3 µs a spawn and Mezze is 1.9x it.

WHAT IS OPEN, ranked: `zip` 1 120x and `flat_map` 637x (both features),
`abort/iter` 370x (diffuse), `filter+to_seq` 26x (work, not a cliff), the
do-block dict call 4.8x, and the HOF-with-two-polymorphic-callbacks cliff at
11.6x whose cause survived five hypotheses and is module-sensitive.

#### The surfaces nobody had swept: maps are 30-220x, and `bytes::map` is 30x `bytes::fold`

Six cases had never been run in this effort. Two of them hold the biggest
remaining cliffs in the language, and one does not build.

**mapsurface**, 1 000 keys, against a 207 ps counted loop:

| row | ps/op | vs control |
|---|---|---|
| `map::size` | 427 | 2x |
| `map::get` (miss) | 5 794 | 28x |
| `set::contains` | 6 811 | 33x |
| `map::contains_key` | 7 536 | 36x |
| `map::get` (hit) | 9 529 | 46x |
| `str map::contains_key` | 10 062 | 49x |
| `set::ins PRESENT, chain` | 10 652 | 51x |
| `str map::get` (hit) | 11 825 | 57x |
| **`set::insert` PRESENT key** | **12 764** | **62x** |
| `str map::insert chain` | 43 050 | 208x |
| **`map::insert` (persist)** | **45 272** | **219x** |
| `map::insert` (chain) | 46 149 | 223x |

A persistent HAMT insert copies a path, so tens of nanoseconds is work rather
than a cliff. `set::insert` with an ALREADY-PRESENT key is the interesting one:
it should be a lookup that returns the same set, and it costs 1.9x the
`set::contains` that is the lookup alone.

**bytesurface**, 1 024-byte buffers, per BYTE:

| row | ps/op | ps/byte |
|---|---|---|
| `bytes::fold` | 151 819 | 148 |
| `bytes::index_of` MISS | 221 767 | 216 |
| `bytes::to_base64` | 247 996 | 242 |
| `bytes::to_seq` | 376 639 | 368 |
| `bytes::to_hex` | 923 145 | 901 |
| **`bytes::map`** | **4 591 494** | **4 484** |

**`bytes::map` is 30x `bytes::fold` on the same bytes, with a SIMPLER
callback** — fold's is `{acc, byte} -> acc + byte` and map's is the identity
`{x} -> x`.

It is not the record path, which is what the comment on `BytesNatives.map` still
says: a counter in `ScalarCall.prepare1` reports **scalar=30 331, record=0**, so
the lazy-stub peek fixed that and the comment is out of date. The profile names
the real cost — `BytesNatives.map` 5 159 samples, `ScalarCall.call1` 4 289, and
under them `OptimizedIndirectCallNode.call`, `callDirect`, `profiledPERoot`,
`profileArguments`, `areArgumentTypesValid`. **An uninlined indirect call per
byte, with argument profiling**, at about 4 ns each.

`bytes::fold` does not pay it because it FUSES: `__native_bytes_fold` is in the
canonicalizer's fold vocabulary, so the native is never called. The emitter's
bytes support reaches exactly one shape — its own comment says "the emitter
refuses any OTHER bytes shape, so a chain that cannot reach that path falls back
to the native fold" — and `size(map(b))` is not that shape. So the fix is to
widen the bytes fused shapes, which is a feature rather than a patch, and it is
recorded here rather than attempted at the end of a long session.

Also found: **`bench/cases/list_ops_compare` does not build** (a type error,
`T0201`), and `collfold`, `str_bench` and `recordcost` print only their first
row with no argument — the same all-rows gap `asyncsurface` has.

#### Correction: `set::insert` of a present key is not 1.9x a lookup — the rows differ

The entry above read `set::insert` PRESENT-key at 12 764 ps against
`set::contains` at 6 811 and called it "1.9x for work that amounts to none".
**That comparison is wrong.** `l_set_insert` does `set::insert` AND `set::size`
per iteration; `l_set_contains` does the lookup alone. The checksums say so
plainly — 1 000 000 000 against 1 000 000 — and I read the ratio before the
kernels.

The comparable row is `set::ins PRESENT, chain`, which inserts and does nothing
else: 10 274 ps against `set::contains`'s 6 985, so **1.47x** for the insert
over the lookup, not 1.9x, and part of that is returning a set at all.

I also fixed the thing the wrong reading suggested, because it is true
independently: `MezzeSet.add` allocated a new wrapper even when the storage
returned ITSELF, which every storage does for an element it already holds
(`LongSetStorage.addLong` says "Already present" in as many words). Returning
`this` saves that allocation and gives callers reference equality as a
did-anything-change test.

**It measures 3%**: 12 385 against 12 764 on the present-key row and 10 274
against 10 652 on the chain row, both inside the noise of a row that swings
further between runs. So it is REVERTED, on the same rule that reverted the
solver change earlier today — a change with no measured effect does not ship,
however tidy. The wrapper allocation was never the cost; what is left of the
1.47x is the insert itself.

## COVERAGE: 19% of the collection surface has ever been measured

Everything above is a finding. None of it answers "what is left", and after 111
landed optimisations that question needs an answer that is not a memory of what
felt done. `scripts/collection-coverage.py` derives one from source:

| module | ops | benched | cover |
|---|---|---|---|
| seq | 124 | 18 | 15% |
| list | 104 | 16 | 15% |
| deque | 73 | 11 | 15% |
| map | 58 | 8 | 14% |
| set | 52 | 8 | 15% |
| sorted_set | 46 | 7 | 15% |
| sorted_map | 38 | 12 | 32% |
| iter | 31 | 16 | 52% |
| nonemptylist | 29 | 2 | 7% |
| bytes | 25 | 15 | 60% |
| buffer | 9 | 0 | 0% |
| **TOTAL** | **589** | **113** | **19%** |

**The denominator is the stdlib's own `pub let` list and the numerator is every
`module::op` call site under `bench/cases`.** Both are derived, so the matrix
cannot drift from the code the way a hand-kept checklist does — which matters,
because a hand-kept list of what we have optimised is exactly the thing that
cannot tell us what it is missing. `OP_COUNT_ALL` was declared and referenced by
nothing, and `iter::count` ran 88x `iter::sum` for doing less, and nothing said
so because no row asked.

### What "done" means for an operation

1. A bench row NAMES it — not a row that happens to exercise it. The
   `sum(0..10M)` row calls `iter::fold`, so `iter::sum` is unmeasured and the
   matrix says so.
2. Its cost is stated against ITS OWN structure's floor (`seq::size` for seq,
   `deque::size` for deque), not against a counted loop, which is how
   `asyncsurface` reported "3 314 172/100 floor" for a thread creation.
3. It sits in that band, or the gap has a NAMED cause that is work rather than a
   defect — a persistent HAMT insert copying a path, `filter+to_seq` writing 5M
   longs.

By that standard the measured 19% is largely done — the surfaces swept in this
effort now sit between 1.0x and 10x of their floors with six named exceptions —
and the other 81% is not "probably fine", it is UNKNOWN. Every large win on this
branch came from the first row anyone wrote for an operation: `to_hex` 56.7x
"found by the type's first benchmark", `size`-as-a-sink 1503x, `count` 77x.

### The order to close it

By unbenchmarked count, weighted by how ordinary the operation is:

1. **map / set, 50 and 44 unmeasured** — `filter`, `map_values`, `merge`,
   `remove`, `keys`, `values`, `to_seq`, `is_empty`, `update`, `partition`. Every
   one of these is everyday code and none has a number.
2. **seq / list, 106 and 88 unmeasured** — the two biggest surfaces, and the
   ones most likely to hide another `size`-as-a-sink.
3. **iter, 15 unmeasured** — `take`, `drop`, `take_while`, `for_each`,
   `to_list`, and `sum` itself. The smallest list and the one where this
   session proved cliffs hide, since `zip` and `flat_map` are known 637-1120x
   from the rows that DO exist.
4. **deque / sorted_set / sorted_map, 62 / 39 / 26** — the ordered containers
   were 29-60x the hash ones and got ten commits; the rest of their surface is
   unmeasured.
5. **nonemptylist 27, buffer 9** — 7% and 0%, and `buffer` has never been run at
   all.

A pass over one module means: add a row per operation at a size matched to its
own cost, run it, and fix what sits outside the band. That is how `count`,
`to_hex` and `size` were found, and it is repeatable rather than lucky.

### Pass 1 of closing it: map 8/58 -> 20/58, and the twelve rows found a family

Twelve of map's fifty unmeasured operations, with the two groups kept apart
because they cannot share an n: per-key rows at 1e6 ops, whole-map rows at
2 000 ops over 1 000 keys.

PER-KEY, against `map::get` (hit) at 9 529 ps and `map::size` at 427:

| row | ps/op | |
|---|---|---|
| `map::is_empty` | 699 | it is `size == 0`, and it costs that |
| `map::get_or` (hit) | 7 658 | **cheaper than `map::get`**, which allocates the Option |
| `map::update` | 56 345 | |
| `map::remove` PRESENT | 61 912 | **more than `map::insert`'s 45 272** |

Two findings in four rows. `get_or` beating `get` is the Option allocation
showing up from the other side — the same 1.93x this ledger already records for
eliminating it in the fused `match get is Some/None`, visible here as a plain
operation that never builds one. And a delete costing 1.4x an insert is worth a
look: both copy a path, but only remove can have to collapse nodes.

WHOLE-MAP, per ENTRY (the harness reports per op; divide by the 1 000 keys):

| row | ps/entry | against the fused iter floor of 65-170 ps/element |
|---|---|---|
| `map::contains_value` | 5 841 | no hash to use, so this is the honest scan cost |
| `map::values` | 5 900 | |
| `map::keys` | 6 834 | |
| `map::to_seq` | 20 872 | builds a `{k, v}` RECORD per entry |
| **`map::count`** | **18 292** | **152x `iter::count`'s 120 ps for the same question** |
| `map::filter` | 36 379 | |
| `map::map_values` | 59 438 | |
| `map::merge` (self) | 80 626 | 1 000 inserts, so ~80 ps an insert is fine |

**`map::count`, `map::filter` and `map::map_values` are one family**: a guest
callback per entry through a native, with nothing fusing it. `bytes::map` is the
same shape at 4 484 ps a byte, and these are 4-13x worse than that — the
difference being that a map's callback takes `{k, v}`, so there is a RECORD to
build per entry as well as the uninlined indirect call. `map::count` asking the
same question as `iter::count` for 152x the price is the sharpest statement of
it available.

That is the first pass doing exactly what it was for: twelve rows, four surprises,
and one named family worth more than the eleven rows around it. Coverage 19% ->
21%.

### The index lives in `perf/`

This file is the narrative — what was tried, what the cause turned out to be,
what was reverted. It is the wrong shape for answering "is `map::filter` done".
`perf/` answers that:

```
perf/README.md                  the dashboard: coverage + area status
perf/coverage.tsv               THE ONLY HAND-EDITED FILE: one line per measurement
perf/datastructures/<mod>.md    every operation, measured or not  [generated]
perf/areas/<area>.md            calls, dispatch, effects, continuation, loom,
                                fusion, records, strings, startup  [prose]
scripts/perf-status.py          regenerates the generated pages
```

The data-structure pages are generated from the stdlib's `pub let` list and the
bench sources, so they cannot drift from the code. Costs are stated per ELEMENT
against the structure's own floor — `map::count` is 42.8x `map::size`, not the
"42839x" a per-op figure against a point-operation floor would read, which is
the `asyncsurface` mistake in miniature.

A measurement is added by appending to `coverage.tsv` and re-running the script.
A number that MOVED is a second line with a later date, never an edit, so a
regression is visible in the file rather than only in a memory of it.

#### Polyglot, measured for the first time on this branch — and a 187 ns gap closed

`bench/cases/polyglot_interop` did not build: its `mezze-js` dependency was
declared as `github:mezze-lang/mezze-js@0.5.1` and absent. Placing the local
0.5.1 package at `.deps/mezze/mezze_js-0.5.1/` and running `mez sync` (two
GraalJS maven coordinates) makes all three polyglot cases run.

ONE CROSSING, 4M iterations, best of 5, against the numbers in the bench's own
header from 2026-09-22:

| row | then | now |
|---|---|---|
| control (no guest) | 252 ns | **16 ns** |
| read, matched spelling | 424 ns | **165 ns** |
| read, dot-chain | 509 ns | **161 ns** |
| **chain − matched** | **+85 ns** | **−4 ns** |
| index | 544 ns | **173 ns** |
| invoke | 855 ns | **262 ns** |

**The dot-chain penalty is gone.** It is recorded in memory as "the polyglot
dot-chain costs 187 ns more than the matched spelling" and it motivated a
numbered task series; the two spellings now agree to within noise. The second
step of that chain is an ability method on `Result`, and an ability call now
costs what a direct call costs — so this was collected by the dispatch and
monomorphisation work rather than aimed at. A crossing is ~149 ns net of the
control.

FINE CROSSING IS THE TRAP, and it is the one number here worth acting on:

| `polyglot_array_read`, n=1 000 | per-element | bulk | |
|---|---|---|---|
| FOREIGN | 77 us | 12 us | **6.4x** |
| NUMERIC (`at` + `int`) | 142 us | `to_ints` 22 us | **6.5x** |

Reading a guest array one element at a time is ~6.5x reading it in bulk, at
every size tested. `polyglot_seq_crossing` pushes a seq INTO the guest via
source text at 48 us per crossing at n=1 000 — about 48 ns an element, which is
what building JS source costs.

None of this is optimised; it is the baseline that did not exist. And three
cases needing `.deps` wiring by hand means no automated sweep has ever included
them.

### The coverage number was wrong: 1 433 operations, not 589 — and 10%, not 21%

The matrix above globbed `stdlib/std/collections/*.mz`. **`str` is not in that
directory.** Neither is `mutation` with its 123 operations, `datetime` with 66,
`http` with 69, `async` with 69, or any other effect module. Asked why strings
were missing, the answer was a hole in the tooling, not a decision — and
counting the whole stdlib changes the picture by a factor of three:

| group | ops | benched | numbered | cover |
|---|---|---|---|---|
| Data structures (collections + `str` + `char`) | 635 | 134 | 48 | 21% |
| **Effects** | **526** | **0** | **0** | **0%** |
| Numbers and math | 102 | 4 | 0 | 4% |
| Core types (`option`, `result`, `datetime`) | 98 | 2 | 0 | 2% |
| Abilities, meta and tooling | 44 | 0 | 0 | 0% |
| Codecs | 28 | 0 | 0 | 0% |
| **TOTAL** | **1 433** | **140** | **48** | **10%** |

Groups are now derived from the module PATH, so a module cannot be missed by
omission again — a new stdlib file lands in a group by construction.

**`str` has 32 operations, 9 with a bench row, and NONE with a number.** The
string work on this branch is real (`split` 1.88x, interpolation allocating
exactly what it needs) but it was measured as ratios in ad-hoc benches, so not
one `str` operation has a cost recorded against it. `str_bench` prints a single
`str_size` line. Nothing measures `concat`, `slice`, `index_of`, `replace`,
`trim`, `pad`, `chars`, `split` itself, or any codec path.

**Effects reads 0% and is not zero**, which the README now says under the table:
an effect operation is invoked through an ability call (`Console.println {}`) or
a `perform X in` block, and the scan counts `module::op` only. Install is 2.73x
better on this branch and `abort` is 86 050 ps. But the 0% is still a real gap
in the sense that matters: **no row NAMES an effect operation**, which is exactly
why `iter`-with-effects, the async primitives (`channel`, `agent`, `atom`,
`semaphore`, `mutex`, `latch`, `waitgroup` — 69 operations between them), STM's
34 and filesystem's 19 have no numbers at all.

78 module pages now exist under `perf/{datastructures,core,numbers,effects,codecs,meta}/`,
one per stdlib module, each listing every operation as measured or UNMEASURED.

#### Macros exist, and their compile-time cost collapsed without anyone aiming at it

Asked whether Mezze even has macros: it does, they are on main (`b0a5aa3e9`,
`9c0f86daf`), `MacroExpansionTest` is 21 tests and passes, and `name!( … )`
parses through `macroCallAfter` into a `ParsedMacroCall` that `MacroExpander`
expands from `ModuleResolver.stampGroup` BEFORE identities are taken — so a
declaration's hash covers its expansion.

`bench/scripts/macros.sh` already existed and measures what `mez check` pays for
N macro calls against the same code written by hand, as a slope and a fixed cost
rather than one diluted total. Its first reading is in the commit that added it.

| | 2026-10-22 | now |
|---|---|---|
| per call | **2.2 ms** | **0.01 ms** |
| fixed | **5.8 s** | **−1 ms** |

| calls | with macros | written by hand |
|---|---|---|
| 10 | 418 ms | 419 ms |
| 200 | 425 ms | 464 ms |
| 1 000 | 449 ms | 445 ms |

**A thousand macro calls are indistinguishable from writing the code out**, and
the 5.8 s fixed cost — "most of it loading `std::syntax`'s fully expanded types
and starting the interpreter" — is gone. The −1 ms is the macro column coming
out faster than the hand column at the smallest N, which is noise and the honest
way to say there is no measurable fixed cost left.

Nobody aimed at this. It is the second thing on this branch found to have fixed
itself as a side effect, the first being the polyglot dot-chain penalty closing
because an ability call now costs a direct call. Both were found by running a
bench that already existed and had never been re-run.

What is still unpriced: the expansion CACHE is keyed on (macro decl hash, input
text), so the 0.01 ms slope is a cache HIT and a cold miss per distinct input is
not separated; editor latency while typing is not measured at all; and
`std::syntax`'s 29 operations, `std::codebase`'s 2 and `std::comptime`'s 1 have
no per-operation rows, though `SyntaxRoundTripTest` covers 86 files and 2 542
declarations for correctness.

#### The map walk allocated an Entry per element — 1.26x, and the callback is the rest

First pass of the systematic order, which put map/set at the front with 50 and
44 unmeasured operations. The twelve new map rows had already named a family:
`map::count` at 18 292 ps an entry against `iter::count`'s 120 for the same
question, with `filter` 36 379 and `map_values` 59 438 beside it.

`MapNatives` already used `ScalarCall`, so the callback was not the whole story.
`sumValues` says the rest in its own comment: **`MezzeMap`'s iterator does
`new Entry<>(keys[index], values[index])` for every element**, so a walk over a
1 000-key map allocates a thousand two-field objects before any predicate runs.
`sumValues` avoids the walk entirely; the five operations that take a guest
predicate could not.

They do not need an Entry. `keys()` and `values()` hand back the two arrays —
one clone each per CALL rather than per element — and a `ScalarCall` takes the
key and the value positionally anyway, so the Entry existed only to be taken
apart again.

| row | base | entry-free | |
|---|---|---|---|
| `map::count` | 17 062 551 ps | **13 504 864** | **1.26x** |
| `map::contains_value` (no callback) | 5 535 475 | 5 646 184 | 0.98x |
| `map::filter` (walks AND rebuilds) | 36 446 498 | 36 182 173 | 1.01x |
| `map::keys` (materialises) | 7 177 563 | 7 284 690 | 0.99x |

The three controls are the point: none goes through the five operations that
changed — `count`, `any`, `all`, `none` and `find` — and all three stayed put.

**The remaining 13.5 ns an entry is the guest call**, and it is where the rest of
the 152x lives. A fused `iter` chain reaches 120 ps an element because the
predicate is INLINED into the loop; a map's whole-map operation calls through an
`IndirectCallNode` per entry. Map is not in the chain vocabulary at all
(`isAnyElementCollection` is seq, iter, list, set, sorted_set and deque), and it
cannot simply be added: a map's element is a PAIR and the fused loop carries one
value. That is the same shape as `zip`, so it is a feature rather than a fix.

COVERAGE. The pairing is now tested, which it was not. Indexing two arrays in
step is right only while `keys()` and `values()` traverse identically, and they
are built by SEPARATE walks — `TrieMapStorage.collect(root, out, idx,
true/false)`, `LongTrieMapStorage.collectKeys`/`collectValues`, and the flat
storages skipping their EMPTY slots in both. The iterator this replaces read the
same two arrays the same way, so nothing new is relied on; but nothing tested it
either, and a slip would hand a predicate one key with another key's value.

Every predicate in `MapWalkPairingTest` couples the key to the value
(`v == k * 10`), at one entry, three, eight (the flat storage boundary) and a
hundred. Slipping the value index by one fails three of them with 100, 3 and 8
all becoming 0 — and the single-entry case correctly still passes, because with
one element the slip is a no-op, which is why the sizes are there.

#### Nothing bulk-built a map — 2.07x on merge, 1.84x on map_values, 1.49x on filter

The map walk's Entry went last time and the remaining 13.5 ns an entry was the
guest call. That is true of `count`, which answers a question. The three rows
that BUILD something were a different number: `map_values` 59 438 ps an entry,
`merge` 80 626, `filter` 36 379, against `map::insert`'s 45 272 for ONE
insert. Each of those rows was paying about one insert per entry, and that is
exactly what they were doing:

```java
for (MezzeMap.Entry entry : map) {
    result = result.put(entry.key(), newValue);   // a persistent put, per entry
}
```

A persistent put allocates a node at EVERY LEVEL of the path it touches, plus a
new key/value array pair at the level that gains the entry. That is what
structural sharing buys and it is the right trade for an update, where the base
survives. In a rebuild the base is dead on the next iteration, so every node on
every path is garbage before anything reads it.

**And the "bulk" helpers were not bulk.** `MezzeMap.filter` and `mapValues`
said *"Optimized: uses mutable builder for batch operations"*, filled a
`LinkedHashMap` "to preserve insertion order" — a trie has no insertion order —
and handed it to `TrieMapStorage.fromMap`, whose own comment calls itself a
one-pass build:

```java
public static MapStorage fromMap(java.util.Map<?, ?> entries) {
    MapStorage out = new TrieMapStorage();
    for (var e : entries.entrySet()) out = out.put(e.getKey(), e.getValue());
    return out;                                   // N persistent puts
}
```

So the detour cost an extra hash table and still paid the per-entry put. Three
layers of comments claiming a bulk build and no bulk build anywhere.

THE CEILING, measured before writing the builder. Arm A is N persistent puts,
arm B is `java.util.HashMap` — in-place, no sharing, no per-level nodes, and so
a bound rather than a target:

| n | persistent put | HashMap | bound |
|---|---|---|---|
| 100 | 53.4 ns/entry | 7.0 | 7.6x |
| 1 000 | 76.3 | 10.4 | 7.3x |
| 4 000 | 86.0 | 8.1 | 10.6x |

Worth building. A builder node indexes its 32 slots DIRECTLY instead of keeping
them compact in bitmap order, so adding an entry is two array stores and a
bitmap OR — nothing shifts, nothing is copied, no node is allocated. `build()`
then walks the result once and compacts each node into the shape the readers
expect, so a node is allocated ONCE instead of once per entry that passes
through it. The width is transient and dies with the builder.

| | put | builder | HashMap | |
|---|---|---|---|---|
| n=1 000, Long keys | 103.9 ns/entry | **32.2** | 15.9 | **3.22x** |
| n=4 000, Long keys | 89.3 | **28.0** | 7.6 | **3.19x** |
| n=1 000, String keys | 75.2 | **22.1** | 7.2 | **3.40x** |

Both tries needed one. The long-keyed trie cannot collide — `mix64` is a
bijection — so its builder is the simple shape. The general trie's `mix32` is
not, so two distinct keys can agree on all 32 bits: when that happens the slot
stops being a builder node and holds an immutable `Collision` instead, and later
keys reaching it go through the ordinary persistent insert. A collision subtree
is small and rare and did not deserve a second mutable shape.

THE LADDER IS WHY THIS LIVES ON `MezzeMap` AND NOT ON A STORAGE. A map's
storage is chosen by size and by key type — single entry, then flat to eight,
then the UNBOXED long-keyed trie when every key is a `Long`, the general trie
otherwise. A builder that always made a general trie would have been faster
than one path and slower than another, which is the regression shape MapTree
already demonstrated. `MezzeMap.fromEntries` picks the tier the ladder would
pick and only uses a builder where the ladder ends in a trie; below nine
entries it puts one at a time, because there the flat storage is the faster
answer anyway. A test pins the storage CLASS, not just the contents.

INTERLEAVED A/B, `mapsurface`, 1 000 keys:

| row | base | bulk build | |
|---|---|---|---|
| `map::merge` | 82 446 211 ps | **39 819 894** | **2.07x** |
| `map::map_values` | 59 158 323 | **32 231 186** | **1.84x** |
| `map::filter` | 37 002 353 | **24 772 526** | **1.49x** |
| `map::count` (walks, builds nothing) | 13 911 742 | 13 981 719 | 0.99x |
| `map::keys` (materialises) | 7 093 834 | 7 384 152 | 0.96x |
| `map::insert` (ONE persistent put) | 46 776 | 46 075 | 1.01x |
| `map::size` (reads a field) | 460 | 460 | 1.00x |

Checksums equal on every row. `filter` gains least because it keeps half, so
half its row was never the build; `merge` gains most because the deleted
`LinkedHashMap` detour was on top of its puts.

**THE LAST TWO CONTROLS ALMOST FAILED THE TABLE.** At the rebuild rows' n=2 000
they read 0.84x and 0.58x, which by rule 4 makes everything above them
unreadable. They were undersized, not moved: one field read and one put are
single-digit and double-digit NANOSECONDS, and a row of 2 000 of them finishes
before the loop is warm — `map::size` came out at 3 486 ps for the whole row.
Re-run at 2 000 000 and 200 000 iterations they are 460 vs 460 and 46 776 vs
46 075. Size each row by its own cost, every time.

SIXTEEN OPERATIONS, ONE CAUSE. `filter`, `map_values`, `map_keys`,
`filter_keys`, `filter_values`, `invert`, `select_keys`, `group_by_value`,
`zip`, `diff`, `intersect`, `symmetric_diff`, `from_seq`, `from_list`,
`from_keys` and `put_all` all wrote the same per-entry put, and all sixteen now
collect and build once. Three of them are benched; the other thirteen are in
the unmeasured 50 and will carry the same change when rows reach them.

DELETED, not bypassed: `TrieMapStorage.fromMap`, `MezzeMap.fromMutableMap` and
`MezzeMap.fromMutableLinkedMap`, with the two `LinkedHashMap` detours that fed
them.

A DEFECT FELL OUT OF IT. `put_all` went through that `HashMap` and then built a
GENERAL trie, so merging two long-keyed maps silently gave up the unboxed
storage — every later lookup on a merged map boxed its key for no reason.
`putAllKeepsTheLongKeyedStorage` pins it.

COVERAGE. `MapBulkBuildTest` compares the builder against the put path
differentially — contents, size, keys held, keys NOT held — across the ladder's
boundaries (1, 8, 9, 32, 1 000), Long and String and mixed keys, duplicates in
the flat storage and in a trie, colliders alone and among ordinary keys, and 480
random rounds. Reversing the long builder's compaction order fails five tests
and leaves the String, collider and mixed-type ones green, which is the right
discrimination.

**One break said nothing, and that was worth knowing.** Passing the wrong shift
when handing a key to a frozen collision subtree leaves every test green. The
branch is not dead — dropping its size bookkeeping instead fails four tests —
the shift is simply UNOBSERVABLE: a `Collision` is made only once the 32 bits
are spent, so every key that can reach one shares its full hash, and
`insertIntoCollision` never reads the shift. The code now says so.

#### A fused stage re-emitted a capture in the wrong frame — my own regression, and it answered 0

Found while building the set's first rebuild bench rows: `mapsurface` row 26,
`set::filter` keeping half a 1 000-element set, reported **`sum=0`**. The row
was not slow, it was WRONG, and the map's row 22 — the same predicate shape
over a map — reported the correct 1 000 000 in the same binary.

The shape matters, and only one of these is wrong:

| predicate | answer |
|---|---|
| `val > half` — a bare capture | correct |
| `val > pre` — the arithmetic done outside the lambda | correct |
| `val * 2 / 2 > 100` — arithmetic on the PARAMETER | correct |
| `val > half + 1` — arithmetic on the CAPTURE | **wrong** |

`MEZZE_CHAIN_CANONICALIZE=0` made all four correct, so it was the fused path;
`git log -L` on the grammar names `f0236e4f8`, 2026-11-03, branch-local.
**This was mine**, from the compound-fold work, not something inherited.

THE CAUSE. `BytecodeCompiler.isParamFree` decided whether a stage's right-hand
side may be emitted inline in the fused loop, and it asked whether the tree
mentions THE CALLBACK'S OWN PARAMETER:

```java
if (node instanceof ReadLocalIR rl) return rl.getSlot() != paramSlot;
if (node instanceof ReadCaptureIR) return true;
```

Both of those are FRAME-RELATIVE. The node comes from the callback's body, where
index 0 is the callback's first capture; the fused loop runs in the ENCLOSING
frame, where index 0 is whatever that function put there. For a one-argument
native HOF callback that slot holds the ARGUMENT RECORD — which is why the
failure has two faces. With arithmetic around the capture it threw `Cannot add
MezzeRecord and Long`, a message pointing at `DirectAddNode` and saying nothing
about frames. Without it, the comparison just answered wrongly and `set::filter`
returned an empty set.

"Does not mention the parameter" is not the question. "Means the same thing in
the other frame" is, and a literal or a global does while a local does not.

THE FIX, in two parts, either of which restores correctness.

`liftCaptureRhs` already rewrote a BARE capture to the enclosing slot it was
captured from; it now walks THROUGH arithmetic, so `half + 1` is lifted rather
than passed over. That is the part that keeps these shapes FAST.

`isParamFree` now refuses `ReadLocalIR` and `ReadCaptureIR` outright, so a
frame-relative read the lift cannot express declines the fusion instead of
answering wrongly. That is the part that makes the next shape of this kind slow
rather than silent — and there will be one, because the matcher accepts whole
subtrees.

`MEZZE_CHAIN_CANON_EMIT_DEBUG` reports no rejections for the probe shapes, so
they still fuse; row 26 now checksums 1 000 000.

Six call sites share that one gate — three in the predicate matcher and three
in the unary/map matcher — so `set::map { f = {x} -> x * scale + 1 }` with a
captured `scale` was wrong in the same way and is pinned too.

ALSO REMOVED: a duplicated `is ReadFieldIR` branch in `ChainCanonicalizer`, from
the same earlier commit, whose second copy was unreachable.

AND A COMMENT THAT WAS FALSE. The Kotlin `isPureArithTree` says its grammar and
the emitter's agree; it accepts any `ReadLocalIR` as a leaf, including a
capture, while `classifyFolderTree` declines captures outright. The disagreement
is benign — the emitter decides and `FusedChainIR.fallback` runs the real folder
— but the comment claimed a guarantee that is not there.

COVERAGE. `CapturedOperandFusionTest` is ten tests asserting VALUES, because a
fusion bug that declines to fuse is not a failure while one that returns 0 is.
Restoring both halves of the old behaviour fails SEVEN of them. The three that
stay green reach the right answer another way and are there to keep it: the bare
capture, the literal composite, and the fold — whose `classifyBinaryFolder`
lifts its own element rhs and declines when it cannot, so that path was never
wrong.

#### The set had the same hole as the map — 2.88x on union, and two rows that measured nothing

`TrieSetStorage.fromCollection` was `for (e : items) out = out.add(e)` — N
persistent adds — and both `MezzeSet.fromMutableSet` helpers called it while
their comments said "Builds the trie in one pass rather than adding one element
at a time". Ten operations wrote the per-element add directly. The map's
builder, ported with the values dropped.

ONE MORE TIER THAN THE MAP. A set's storage ladder is single, then
`SmallFlatSetStorage` to 16, then `LongSetStorage` to 32 for all-Long elements,
then the long trie or the general one. `MezzeSet.fromElements` adds one at a
time below the WIDER of the two flat maxima — which is the ladder's own answer
there and cheap at that size — and builds above it.
`fromElementsPicksTheSameTierAsAdds` pins the storage CLASS at 1, 5, 16, 17, 32,
33, 50 and 1 000 for both element types rather than trusting that arithmetic.

**THE SET HAD ONLY TWO OF ITS 46 OPERATIONS MEASURED**, so this needed eight new
`mapsurface` rows before it could be read at all: filter, map, union,
intersection, difference, symmetric_difference, from_seq, and to_seq as the
control. n=10 000, where rows 26 and 27 stop reporting WARMUP-DOMINATED and
agree with n=20 000 to within 2%.

TWO OF THOSE ROWS MEASURED NOTHING, and the first table hid it:

| row | base | bulk build | |
|---|---|---|---|
| `set::filter` as first written | 4 342 634 ps | 4 391 501 | **0.99x** |
| `set::map` as first written | 4 339 175 | 4 384 113 | **0.99x** |

Five rows moved and these two did not, which looked like a property of filter and
map. It was a property of the ROWS. `set::size { s = set::filter { … } }` is a
FUSED chain: the predicate runs in a loop that counts and no set is ever built.
The arithmetic says so without any instrumentation — one `set::insert` is 13.8
ns, so the 500 persistent adds the base arm was supposed to be doing would be
6.9 ms on their own, and the whole row read 4.34. Neither arm was building
anything.

A `set::insert` on the result is not a chain sink, so the set has to exist. The
rows now read 16.5 and 24.7 ms and the checksum rose from 500 to 501 per
iteration, which is the insert showing up where it should.

| row | base | bulk build | |
|---|---|---|---|
| `set::union` | 87 734 431 ps | **30 464 935** | **2.88x** |
| `set::from_seq` | 33 233 536 | **13 955 363** | **2.38x** |
| `set::map` | 46 950 893 | **24 720 299** | **1.90x** |
| `set::symmetric_difference` | 51 163 684 | **30 165 541** | **1.70x** |
| `set::filter` | 25 899 302 | **16 686 747** | **1.55x** |
| `set::difference` | 21 587 559 | **14 099 220** | **1.53x** |
| `set::intersection` | 21 428 796 | **14 066 322** | **1.52x** |
| `set::to_seq` (walks, builds nothing) | 4 269 068 | 4 288 408 | 1.00x |
| `set::contains` | 6 955 | 6 788 | 1.02x |
| `set::insert` (one persistent add) | 12 861 | 12 841 | 1.00x |

Checksums equal on every row. `union` gains most because it also loses a
`java.util.HashSet` detour on top of its adds; `filter` and the two
set-difference rows gain least because they keep or drop only half the elements,
so half their cost was never the build.

`set::insert` first read **1.14x** in the main table, which is a control moving
14% and by rule 4 would make everything above it unreadable. Six interleaved
passes at its own n settle it at 12 861 against 12 841: the base's minimum had
landed on a slow pass. A control that moves gets re-measured, not explained.

COVERAGE. `SetBulkBuildTest` mirrors the map's — differential against the add
path across the ladder's boundaries, both element types, repeats flat and in a
trie, colliders alone and among ordinary elements, and 480 random rounds with
the long-only rounds kept separate because one String element takes the whole
set off the unboxed tier. Reversing the long builder's compaction order fails
three and leaves the String, collider and mixed-type tests green.

**One break exposed a hole in my own tests.** Comparing elements with `==`
instead of `equals` in the general builder failed only the random rounds — every
hand-written String case happened to reuse one instance per value, and the
all-Long cases go to the long builder where elements are compared as primitives.
`equalButDistinctInstancesCollapse` now holds that case by name: three `new
String` instances of each of a hundred values, which must collapse to a hundred.

`BulkBuildSoundnessTest` already existed for the Map and Set bulk paths, and it
asks exactly the question an in-place builder raises — once `build()` has handed
the structure over, is it immutable like any other? A builder that leaked a node
or froze one by aliasing its slot arrays would answer no. It passes, and it now
covers both UNBOXED builders as well, which it did not: a String element would
have taken every one of its cases off that tier.

DELETED: `TrieSetStorage.fromCollection`, `MezzeSet.fromMutableSet` and
`fromMutableSetGeneric`, with the two `java.util.HashSet` detours.

NOT CONVERTED, on purpose: `set::insert_all` and `map::merge_with` both start
from an EXISTING structure and add a handful of elements, so a bulk rebuild
would walk the whole base to save those few adds.

#### The unfused iter fold materialised the whole iterator — 2.70x on zip, and it broke laziness

`iter::zip` was the worst per-element number in the whole collection table:
73 284 ps an element against `iter::map`'s 133, and against 13 505 for a map
walk that calls a guest predicate per entry and fuses nothing. A 550x spread
between two members of the same module is not a tier gap, it is a clue.

`IterNatives.foldViaRecursion` says what it is doing:

```java
// Materialize into an ArrayList and fold — the correctness path
// isn't asked to be fast, only to work. Fusion is the fast path's job.
java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
collect(node, buf, callNode);
```

The first half is fair. **The second half is the bug**: for a shape the emitter
REFUSES there is no fast path, so the correctness path is the only path, and at
`iter_perf_compare`'s n it buffered TEN MILLION elements and held every one of
them until the fold ended.

It also contradicted the rule `iter` exists for — *"a pipeline expressed with
`iter` never materialises what it is about to discard"* — which names this very
shape, a `zip` whose only consumer is a `fold`, as the thing to use `iter` FOR.

THE FIX IS NOT NEW MACHINERY. `walkElements` already pushes elements one at a
time, already handles every descriptor kind, and already stops early when the
consumer says so; `collect` was literally `walkElements(node, out::add,
callNode)`. Folding inside the walk visits the same elements in the same order
with nothing buffered, and `collect` is deleted.

**The buffer bought nothing here, which took reading two callers to establish.**
`collectToArray` materialises ON PURPOSE — behind its boundary, so that its
caller's loop runs outside one and the JIT can inline the callback. This path
has no such caller: `fold` is itself `@TruffleBoundary`, so the fold loop was
already inside the boundary either way.

| row | base | streaming | |
|---|---|---|---|
| `zip+fold` | 640 147 309 ns | **236 909 960** | **2.70x** |
| `flat_map+fold` | 321 657 864 | **139 023 588** | **2.31x** |
| `chain+fold` | 5 348 397 | 5 318 072 | 1.01x |
| `enumerate+fold` | 6 448 559 | 6 569 502 | 0.98x |
| `sum` (control) | 696 971 | 696 921 | 1.00x |
| `filter+fold` (control) | 1 291 193 | 1 334 512 | 0.97x |
| `count` (control) | 1 295 491 | 1 233 126 | 1.05x |

n=10M, min of three interleaved passes.

**ZIP AND FLAT_MAP ARE THE ONLY SHAPES THAT ARRIVE HERE**, which the table says
and the code does not. `chain` and `enumerate` are both in `foldWalk`'s list of
recursion kinds, and both are flat — because their chains FUSE at compile time
and this native is never called. Reading the kind list would have predicted four
movers; measuring found two.

THE BASE ARM'S VARIANCE IS THE OTHER HALF OF THE RESULT. Five passes of
`zip+fold` read 603, 1283, 833, 1330 and 742 ms; the streaming arm read 252 to
261. A 2.2x swing within one arm is what retaining ten million elements does to
a collector, and it is why the min-of-k is the only honest statistic here —
the averages were 958 ms against 254, which flatters the fix to 3.8x.

After it, `zip` is 23 691 ps an element and `flat_map` 13 902 — no longer
outliers but ordinary unfused per-element costs, beside a map walk's 13 505.
What remains in them is the pair record, the boxing and the per-element guest
call, and removing those means fusing, which for `zip` means a fused loop that
carries TWO values.

COVERAGE. `LazyIterFoldTest` pins the observable consequence rather than the
speed: materialising means every element is produced BEFORE the folder sees any,
streaming means they interleave. The source side (a `flat_map` function) and the
folder each append to an atom, so the log reads `sfsfsf` when streaming and
`sssfff` when buffered — restoring the buffer fails exactly those two tests with
that exact diff, and leaves the seven value tests green, because materialising
never changed an answer. The value tests are there anyway: both zip components,
zip stopping at the shorter side, flat_map's inner elements, a non-commutative
folder over `chain` where the order IS the answer, `enumerate` threading `.i`
and `.val`, and a Str accumulator so nothing can quietly sum past it.

#### The Entry was not the cost — the two arrays were. 1.33x on map::count, and a correction

Earlier today the whole-map walk was changed from the `Entry` iterator to
`keys()` and `values()` indexed in step, worth 1.26x on `map::count`, with the
reasoning that the Entry allocation per element was the cost. That reasoning was
right about the direction and wrong about the cause, and the ledger entry
carries the correction rather than replacing it.

Three ways to walk a 1 000-entry long-keyed map, same checksum:

| | ns/entry |
|---|---|
| `keys()` + `values()` indexed in step | 7.16 |
| the `Entry` iterator it replaced | 7.25 |
| `storage.forEach` — one walk, no arrays | **1.97** |

**3.63x, and the Entry iterator was within 1% of the arrays that replaced it.**
Neither shape was the walk. Both were the two n-element arrays and the trie
being traversed TWICE to fill them — `TrieMapStorage.collect` once per side.
The Entry was a red herring that happened to sit next to the real cost. 3.54x at
n=4 000, 2.56x for String keys.

`MapStorage.forEach(BiConsumer)` was already there, already overridden by both
tries, and its own javadoc already said "more efficient than extracting keys()
and values() separately". Nothing in `MapNatives` called it.

`any`, `all`, `none` and `find` answer before the end, so they needed a walk
that STOPS. `MapStorage.forEachUntil(BiPredicate)` is the sibling: false ends
the walk, and the two tries override it with a recursive walk that returns
early, while the default pays for the two arrays exactly as before — the
storages that take it hold at most 32 entries, where the arrays are the cheap
answer anyway.

Interleaved A/B, `mapsurface`, 1 000 keys:

| row | base | one walk | |
|---|---|---|---|
| `map::count` | 13 814 776 ps | **10 354 699** | **1.33x** |
| `map::filter` | 24 802 174 | **22 292 413** | **1.11x** |
| `map::map_values` | 31 880 438 | 31 287 624 | 1.02x |
| `map::contains_value` (walks, not rewired) | 6 555 944 | 6 388 075 | 1.03x |
| `map::keys` (materialises on purpose) | 6 733 150 | 6 938 833 | 0.97x |
| `map::values` (same) | 5 932 959 | 6 076 537 | 0.98x |
| `map::insert` | 46 091 | 46 983 | 0.98x |
| `map::size` | 212 | 209 | 1.01x |

Checksums equal on every row. `count` gains most because it is the row with no
build to dilute it: of its 13.8 ns an entry, about 7 was the arrays and 4.5 the
guest call.

**`map_values` gains almost nothing, and the reason is worth writing down.** It
needs a key array to hand to `fromEntries`, so where it used to take two input
arrays and allocate one output, it now walks once and allocates two outputs —
three arrays down to two. The build still dominates it at ~22.6 ns an entry.
Removing the last two would mean building INTO the builder during the walk,
which is sound for `map_values` specifically because it preserves the key set
and therefore the storage tier; `filter` cannot borrow that argument, since a
filter down to five entries belongs in the flat storage, not a trie.

`map::size` read **0.83x** at n=2 000 000 and 1.01x at n=4 000 000. That is the
third time today a control at a few hundred picoseconds has been undersized by
the row next to it, and the third time the answer was to size it by its own cost
rather than to explain the number.

COVERAGE. `MapWalkPairingTest` grew rather than being replaced: the pairing
tests it already had cover the new mechanism unchanged, because a `forEach` that
pairs a key with another key's value fails them exactly as a slipped array index
did. Four tests are new, counting predicate CALLS in an atom, because a
predicate's false now has to stop a RECURSIVE walk where it used to `return` out
of a flat loop — `any` and `find` over an always-true predicate and `all` over
an always-false one must call it exactly ONCE on a hundred-entry map, and a
never-matching `any` must still see all hundred.

Ignoring the stop signal fails FIVE tests, and two of them are correctness, not
counting: `any` answers false and `find` answers None, because both now derive
their answer from whether the walk stopped. The short-circuit is load-bearing
for the result, not only for the speed.

#### The rest of the Entry walks — 1.14x on map::to_seq, and zero left

The one-walk change converted the nine operations that take a guest predicate.
Nine more Entry-iterator walks were still there, in the operations that do not:
`entries`, `to_seq`, `to_list`, `fold`, `equals`, `group_by_value`, `flat_map`,
`merge_with` and `partition`. Same cause, same fix, and `MapNatives` now has
NONE.

| row | base | one walk | |
|---|---|---|---|
| `map::to_seq` | 17 715 535 ps | **15 474 505** | **1.14x** |
| `map::count` (converted earlier, in both arms) | 10 359 688 | 10 377 786 | 1.00x |
| `map::filter` (same) | 22 078 224 | 21 969 138 | 1.00x |
| `map::keys` | 7 050 134 | 6 869 542 | 1.03x |
| `map::size` | 433 | 445 | 0.97x |

`to_seq` is the only one of the nine with a bench row, so it is the only one
with a number; the other eight carry the same change unpriced. It gains less
than `count`'s 1.33x because it allocates a `{k, v}` record per entry either
way — the Entry it no longer builds was one of two allocations per element, not
the only one.

**`equals` now stops at the first unequal pair**, which it did before by
`return`ing out of a flat loop and now does by the predicate ending the walk.

**`partition` carried BOTH defects** and was missed by the bulk-build sweep
because it returns a RECORD of two maps rather than a map, so it did not match
the shape that sweep looked for: it walked the Entry iterator and built both
results with a persistent put per entry. It now walks once and builds each side
with `fromEntries`. No row measures it.

`merge_with` keeps its put per entry on purpose, and the comment now says why:
it starts from an existing `map1` and adds `map2`'s entries, so a bulk rebuild
would walk the whole base to save however few puts `map2` asks for. The same
argument as `set::insert_all`.
