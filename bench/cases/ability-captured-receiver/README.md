# An ability op on a CAPTURED receiver is ~13% slower than a plain function

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
