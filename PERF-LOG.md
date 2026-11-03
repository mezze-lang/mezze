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

### Two speculative fixes that measured nothing and were REVERTED

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

---

## Open — ranked by measured or expected payoff

1. ~~`FusedLongTailLoop` accepts only 1–2 parameters.~~ **FIXED** — see
   "The fused tail-loop tier stopped at two parameters" above. Arity 4+ still
   falls off the same cliff and is a mechanical addition when something needs
   it; nothing measured yet asks for it.
2. **Static dictionary resolution — 52 ns/iter, the largest measured gap.**
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

3. **Dot-chaining refuses fusion — 2.8× measured.** `ChainCanonicalizer` rewrites
   `iter` chains into `FusedChainIR`, is on by default, and **refuses the
   dot-chained form**: `.map{}.filter{}.sum{}` runs at 35.1 ns/elem with the
   pass on and 29.9 with it off (identical — it never fired), while the
   free-function form `iter::map/filter/sum` runs at 12.5–14.2 ns/elem and does
   fuse. Dot-chaining dispatches through the `Iter` ability impl, and the pass
   runs post-`TrivialWrapperInliner`, so unlinlined ability dispatch leaves
   opaque calls where a peelable chain should be. This conflicts with
   dot-chaining as the idiomatic form; fix the compiler, not the style.
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
