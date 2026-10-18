# An ability op on a CAPTURED receiver costs ~2.1x a plain function

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
