# FIXED — an ability op took the record ABI, so it could not skip the trampoline

> **FIXED 2026-10-18.** `lamp` went 30.9s -> 14.9s at 100x work, landing exactly
> on `lamplain`: an ability op now costs what a plain call costs. `lamc` went
> 20.1s -> 2.3s. Cause and fix are at the bottom; the history below is kept
> because its two WRONG readings are the reusable lesson.

# (historical) An ability op on a CAPTURED receiver costs ~2.1x a plain function

> **CORRECTED 2026-10-18.** This file first said "~13%". The raw timings below
> were right; the PERCENTAGE was wrong, because `date`-around-the-process times
> are ~85% startup at `n = 400000` and the construct only affects the loop. At
> 100x the work the same three programs give **2.16x**, not 1.13x. The two rules
> that follow from that are at the bottom of this file, and they cost a day.

Three programs, same answer (`84000000`), same work, same allocation. Timed with
`date +%s%N` around the whole process, three runs each:

| program | what the lambda calls | median |
|---|---|---|
| `lamplain` | a PLAIN function, receiver captured | **1045 ms** |
| `lamc` | an ABILITY OP, receiver read from a GLOBAL | 1078 ms |
| `lamp` | an ABILITY OP, receiver CAPTURED | **1181 ms** |

So `lamp` costs 13% over `lamplain` — identical capture, identical call count,
identical bodies — and 10% over `lamc`, where only the receiver's source differs.

This is the minimal form of the JSON walker's gap (`planned_features/runtime/
web-stack-backlog.md`, W-P1). The derived codec walk calls `config.encode_*` on a
receiver that the `seq::map` lambda captures, 101 times per request, and that is
11.6% of a 15.5% gap on the HTTP work route.

## It does NOT reproduce without a lambda

A tail-recursive loop calling the same op on a parameter receiver is exactly as
fast as on a global — measured twice, with one trivial op and with four
allocating ops:

    one trivial op       const 1796-1895 ms   param 1822-1954 ms
    four allocating ops  const 2164-2278 ms   param 2133-2186 ms

So this is not "ability dispatch is slow", and not "a non-constant receiver is
slow". It needs the call to be inside a lambda whose capture is the receiver.

## Ruled out, with evidence

- **The IR is identical** except the receiver read: `[it:ReadCaptureIR, v:…]`
  versus `[it:ReadGlobalByHashIR, v:…]`, both a `CallDirect` with a two-field
  record. Probed in `TypedASTCompiler`.
- **Dispatch resolves statically in both**: `opHash=DIRECT` for a constant, a
  parameter AND a captured receiver. Probed.
- **Not the lazy stub.** `LazyStubRootNode` forwards through a `DirectCallNode`,
  costs an extra frame, and `MEZZE_TRACE_COMPILATION` shows it compiling as its
  own target with `Inlined 0Y 1N`. A caller-side bypass was implemented — unwrap
  an inflated stub when the function captures nothing, the same way the
  trampoline bypass works — and VERIFIED to fire (17 unwraps, all successful).
  It changed nothing. Reverted.
- Not the field-transform match, the `ctx` threading, the argument-record width,
  the per-node call itself, the intermediate collection, the `ToJson` layer, or
  the encode living in its own function. Each measured separately; see W-P1.

## What to try next

This needs Graal-level inspection, not more Mezze-program bisection — the IR is
the same, so the difference is in what the compiler does with it. Dump the
compilation for `lamp` and `lamplain` (`--engine.TraceCompilationDetails`, IGV)
and compare what happens to the two call sites. The one difference left between
them is the CALLEE's kind: an ability-impl operation versus a top-level `let`.

## Running them

    cd lamp && mez build && ./.build/lamp


## How to measure this correctly (both rules were broken here)

**1. `mez dev` runs the INTERPRETER unless `MEZZE_JIT=1`.** `RuntimeServer` sets
`engine.Compilation` from `MEZZE_JIT`, which defaults to FALSE. `mez doctor`
saying "JIT enabled" means the GraalVM is JIT-CAPABLE, not that compilation is
on. Measured in interpreter mode these three programs come out in a DIFFERENT
ORDER (`lamc` slowest, not `lamp`), which is a true fact about the interpreter
and says nothing about compiled code.

**2. Make the loop dominate, then fit a slope.** A whole-process time is
`startup + k * work`. Run the same program at two work sizes and the slope `k`
is the only part the construct affects; the intercept comes out as the startup
and is a free check — here all four programs agreed to within 6% on it, which is
what says the model holds. At `n = 400000` the loop is ~15% of the process, so a
2.1x difference in it shows up as 13%.

Corrected table — built jars, `n = 40000000`, 2-3 reps, spread under 2%:

| program | receiver | callee | total | loop | vs lamplain |
|---|---|---|---|---|---|
| `lamplain` | captured | plain fn | 14.8s | 13.9s | 1.00 |
| `lamc` | GLOBAL | ability op | 20.1-22.0s | 19.2-21.1s | 1.38-1.52 |
| `lamp` | captured | ability op | 30.9s | 30.0s | **2.16** |

## What the compilation trace says: the inline HAPPENS

Built jar plus `MEZZE_TRACE_COMPILATION=1` (this works; `mez dev --trace-jit`
emits nothing, with or without `MEZZE_JIT=1`). All three reach
`Inlined 9Y 0N` on the hot OSR compilation — **no call is refused**. What differs
is how big the inlined result is:

    lamplain   LazyStub[lambda]  Inlined 5Y 0N   code 2427
    lamc       LazyStub[lambda]  Inlined 8Y 0N   code 4854
    lamp       LazyStub[lambda]  Inlined 8Y 0N   code 6409

lamp's hot OSR body is identical in shape to lamplain's (IR 521/1350, code
5248), so the loop is not where they differ — the lambda is.

So the open question is NOT "why is the ability op not inlined". It is **what
per-call work survives inlining on a non-constant receiver and folds away on a
constant one**, given that Mezze-level dispatch already resolves `opHash=DIRECT`
for both. A guard or a field read on the receiver inside the inlined body is the
suspect.

## A fourth program worth having: `plainglobal`

A plain function with a GLOBAL receiver (no ability, no capture) is the control
`lamc` was being used as, and it is not interchangeable: with JIT on, Graal
eliminates its loop entirely (slope ~17ms against lamplain's 161ms). That is
useful evidence — it shows how hard Graal folds a constant receiver — but it
means `lamc` carries a per-element global read that `lamplain` does not, so the
pair never isolated the ability op on its own. In interpreter mode that read
costs +36% and is cleanly separable; under JIT it is folded. Not committed here,
since under JIT it measures nothing; reconstruct it from this paragraph if the
interpreter-mode decomposition is ever wanted.


## The cause: an unregistered map, not a Graal mystery

`TypedASTCompiler.populateHashToParamNames` registered param names for
`TypedLetDecl` lambdas only. **Ability-impl operations were never registered**,
so `paramNamesForHash(opHash)` was null, `emitCallDirect` could not prove full
application, and every ability-op call took the RECORD ABI.

The record ABI is not a free choice. Unpacking the argument record into flat
parameter slots is what the callee's trampoline is FOR
(`TrampolineRootNode.normalizeArgs`), and only a scalar call may bypass it
(`MezzeBytecodeRootNode.resolveScalarTarget`). So an ability op paid a
`LazyStub` + `Trampoline` hop per call — 229 IR nodes for a body of
`B1 { v = v }` — that the same function called as a plain `let` did not.

The fix registers `computeOpHash(impl.hash, opName)` against
`lambda.params + sorted dict params`, matching `compileFunctionBody`'s own
`allParamNames` exactly. Get that order wrong and scalar calls hand the callee
transposed arguments, which is a WRONG ANSWER, not a crash — hence
`AbilityOpScalarArgOrderTest` (6 of its 7 cases fail if the order is reversed).

## The inlining tree: nothing was ever refused

The old claim was that the op "does not inline". It does. All three programs
reach `Inlined 9Y 0N` on the hot OSR compilation. What differed was the SIZE,
because the trampoline hop survived inlining:

    before   lamplain  LazyStub[lambda] 5Y 0N  code 2427   (no TrampolineRootNode[e_a])
             lamc      LazyStub[lambda] 8Y 0N  code 4854
             lamp      LazyStub[lambda] 8Y 0N  code 6409   (+ TrampolineRootNode[e_a])

    after    lamp's tree is identical in shape to lamplain's (IR 354, only
             `unbox` inlined), and TrampolineRootNode[e_a] is gone from all three.

So `Inlined NY 0N` — a COUNT — declared three programs equivalent while one ran
2.1x slower. The tree is the instrument; the count is not.

## After

| program | receiver | callee | before | after | speedup |
|---|---|---|---|---|---|
| `lamplain` | captured | plain fn | 14.8s | 14.9s | 1.00 (control) |
| `lamp` | captured | ability op | 30.9s | **14.9s** | **2.07x** |
| `lamc` | GLOBAL | ability op | 20.1-22.0s | **2.3s** | **~8.7x** |

The real target, the derived codec walk, is in
`bench/cases/codec-encode-walker`: **1.44x faster** on the loop.

## Getting the tree

    mez build && MEZZE_TRACE_INLINING=1 ./.build/<app>

`MEZZE_TRACE_INLINING=1` used to emit nothing: it set `TraceCompilationDetails`
(the counts) rather than `TraceInlining` (the tree), and the log-level gate only
listed `MEZZE_TRACE_COMPILATION`, so even once the option was right the output
was swallowed. Both are fixed. `mez dev --trace-jit` still emits nothing — use
the built jar.
