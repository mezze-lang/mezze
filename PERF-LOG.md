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

### Boxing elimination is NOT the blocker the comment claims

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
