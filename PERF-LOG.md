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
