# Web stack — correctness, then performance, then the untested ground

Ordered as the work should be done: **correctness first**, then the measured
performance gaps, then the parts of a web stack nobody has measured yet.

Every number here is from one box: 16 cores, loopback, `wrk -t8 -c256`, 5s warm
+ 10s measured, against `/work/200?mul=3` unless stated. Measure at SATURATION —
`-t4 -c64` leaves server and load generator both half idle, caps throughput by
concurrency÷latency rather than capacity, and flatters us (it showed 87% of Java
where saturation shows 74%). `req/s per core` is the honest figure, because no
server here saturates 16 cores while wrk shares the box.

Where the stack sits: Java 545.9k, **Mezze ~405k**, Node cluster ~340k. Past
Node; ~74% of Java, and 89% of Java's per-core efficiency at the floor.

---

## Part 1 — Correctness (do these first)

### W-C1 — CLOSED, NOT A BUG: an all-concrete obligation is self-discharging

`AbilityObligation.kt` raises a `with` obligation only when one of its type args
is still a type VARIABLE (`varIndex < 0 → return null`). That reads like BUG 25's
inversion — a call site that knows MORE being checked LESS — and it is not.

An all-concrete obligation discharges ITSELF. Concrete in the caller means
concrete in the callee, so if the callee's body uses the ability, that body
raises its own demand with the same concrete type and the impl is derived there;
if the body does not use it, there is nothing to discharge. Only a VARIABLE
obligation needs the caller to supply a dictionary, and that case has
`varIndex >= 0` and is raised normally.

Two things made this look like a bug, and both were misread:

 - The obvious repro does not reach the arm at all. Where a caller passes a
   concrete argument to `{ x: 'a } with Encode JsonC 'a`, `'a` is still a FRESH
   variable when the obligation is built — the argument binds it later by
   unification. Only an obligation written concretely in the SOURCE
   (`with Encode JsonC { a: Int }`) has no variable at any position.
 - A fix was implemented and measured to change NO observable behaviour. That
   was evidence it was not a bug; I read it as a missing test.

`ConcreteObligationTest` pins all four shapes so nobody "fixes" this later.

### W-C2 — 302 native-image blocklist violations (C5 remainder)

`just native-test` reports ~302 runtime-compilation blocklist violations, all
reaching the blocklist through Truffle's `Instruction.toString` → `Formatter` →
`Preconditions.outOfBoundsMessage`, or through `EmbeddedExec`.

GraalVM 25.0.3 has NO `-H:` option to ignore a blocklisted method, so the fix is
to route bounds failures through a `@TruffleBoundary` instead of suppressing the
report. `-H:+PrintRuntimeCompilationCallTree` names the sites.

**Done when** `just native-test` is clean, or every remaining violation is
individually explained.

### W-C3 — CLOSED, no code needed: build and dev never take a bare expression

Owner's decision, verified. `mez build` and `mez dev` take their entry from
`Mezze.toml` (`[[app]] entry = "main"`), and a bare top-level expression is
refused outright — `error[V0710]: A bare expression isn't valid at the top level
of a program`, with a hint to wrap it in `let main = {} -> …`. Effects never
come into it.

So the unchecked residual row only exists where bare expressions ARE allowed:
`MezzeTestHelper.run`, and a future REPL. Nothing to tighten in the shipping
paths; a REPL's policy is decided when there is a REPL.

Verified by `mez init` plus an `app.mz` holding exactly the sleeping snippet
from C1, built with `mez build`.

### W-C4 — CLOSED, resolved by construction: C3's correctness half

The claim was that `MezzeLanguage`'s CallTarget caches are never cleared on
`disposeContext`, so a target compiled against one Context's IR could be handed
to the next Context on the same Engine. `disposeContext` now clears
`importedCallTargetCache` and `userStubCache` — the two holding USER targets —
and the only survivor, `stdlibCallTargetCache`, is populated exclusively from
the stdlib snapshot with user hashes explicitly excluded, so it holds nothing
Context-specific.

No failing case was ever produced, and the one failure that resembled it was
the `PromiseNatives` statics, fixed separately. Details and the reopen
conditions are in `concurrency-and-context-bugs.md`.

---

## Part 2 — Performance (measured, ranked by size)

### W-P1 — FIXED: an ability op took the record ABI, so it could not skip the trampoline

The walker's gap is **15.5%** (not 20% — that figure came from a harness that
could not resolve it). It is now fully accounted for, and it is NOT about codecs
at all: **an ability-method call whose receiver is a compile-time constant folds
away; the same call on a non-constant receiver costs ~0.11% per call.** Across
the 101 per-node calls of a 20-record payload that is 11.6%.

This took seven hypotheses because the harness could not see a 2% effect. Fixing
the harness FIRST (`bench.sh`, `compare.sh` — interleaved A/B, medians, AppCDS
untouched) brought sample spread from 3-4% down to ~1% and the answer fell out
in six measurements.

#### The ladder, each step one change, interleaved A/B

| variant | req/s | delta |
|---|---|---|
| hand-written encode, two-pass, no calls | 276.8k | — |
| + 100 per-node calls to top-level fns | 279.9k | +1.1% (free) |
| + a non-constant `cfg` threaded through those calls | 276.1k | −0.7% (free) |
| + those calls as ABILITY IMPL OPS on a CONSTANT receiver | 276.9k | −0.7% (free) |
| + the same ability ops on a PARAMETER receiver | **243.9k** | **−11.6%** |
| the real derived walker | 233.7k | −4.2% |

So: per-node calls are free. Threading a non-constant value through them is free.
Ability dispatch is free WHEN THE RECEIVER IS CONSTANT. Only the combination —
ability op + non-constant receiver — costs, and it is 3/4 of the whole gap. The
last 4.2% is everything else in the generated body together (ctx, field
transform, the `{it, val, ctx}` shape, Tier-3 entry), each of which measured free
on its own.

#### What it is NOT (measured, do not re-derive)

Per-node calls; the 5-way field-transform match (+0.3%); intermediate records;
wide argument records and `ctx` (+1.4%); fusion differences (identical
`CHAIN_CANON_EMIT_DEBUG`); the `ToJson` blanket layer (+0.1%); the encode living
in its own function (−1.4%); dictionary indirection — that one WAS real and is
fixed (`config` was a bare type variable; it now reads through a type-annotated
local and resolves `opHash=DIRECT`), and fixing it changed throughput by nothing.

#### NARROWED to a minimal repro — `bench/cases/ability-captured-receiver`

Three programs, same answer, same work. The cost needs an ability op AND a
receiver the LAMBDA CAPTURES:

| program | the lambda calls | median |
|---|---|---|
| `lamplain` | a PLAIN function, receiver captured | 1045 ms |
| `lamc` | an ABILITY OP, receiver from a GLOBAL | 1078 ms |
| `lamp` | an ABILITY OP, receiver CAPTURED | **1181 ms** (+13%) |

**It does not reproduce without a lambda.** A tail-recursive loop calling the
same op on a parameter receiver is exactly as fast as on a global — measured with
one trivial op (1796-1895 vs 1822-1954 ms) and with four allocating ops
(2164-2278 vs 2133-2186 ms). So the earlier claim that this is a general
"ability op on a non-constant receiver" cost was WRONG, and is corrected here: it
takes the call being inside a lambda whose capture is the receiver.

#### Also ruled out: the lazy stub (eighth disproved cause)

`LazyStubRootNode` forwards through a `DirectCallNode`, costs an extra frame, and
`MEZZE_TRACE_COMPILATION` shows it compiling as its own target with
`Inlined 0Y 1N` — so it looked exactly like the answer. A caller-side bypass was
implemented (unwrap an inflated stub when the function captures nothing, the same
way `resolveScalarTarget` unwraps a trampoline), VERIFIED to fire — 17 unwraps,
all successful — and changed nothing. Reverted rather than kept.

#### CORRECTED 2026-10-18: it is ~2.1x, not 13% — the old percentages were startup-diluted

The three programs' RAW TIMINGS above were right. The PERCENTAGES derived from
them were not, because they are whole-process times and the loop was only ~15%
of the process. Re-measured at 100x the work (so startup is ~6% instead of ~85%),
built jars, 2-3 reps, spread under 2%:

| program | receiver | callee | total | loop (less ~0.9s startup) | vs lamplain |
|---|---|---|---|---|---|
| `lamplain` | captured | plain fn | 14.8s | 13.9s | 1.00 |
| `lamc` | GLOBAL | ability op | 20.1-22.0s | 19.2-21.1s | **1.38-1.52** |
| `lamp` | captured | ability op | 30.9s | 30.0s | **2.16** |

Cross-checked on the `mez dev` path at 1x/10x/100x: the slope fit predicts every
measured point within 6%, and after subtracting that path's larger (~4.1s)
startup it agrees — lamp 2.08x, lamc 1.25x. Two paths, three work sizes.

So the honest figures: an ability op on a CAPTURED receiver costs **~2.1x** a
plain function with the same capture, the same argument record and the same call
count. On a CONSTANT (global) receiver the same op costs ~1.4x. The old "+13%"
and "ability dispatch is free when the receiver is constant" both understated it
by roughly 8x and 0.4x respectively.

The old claim's DIRECTION was right and should not be re-doubted: `lamc` < `lamp`.
A first re-measurement appeared to invert it, and that was an artifact —
see the trap below.

#### THE TRAP: `mez dev` runs the INTERPRETER by default

`RuntimeServer` sets `engine.Compilation` from `MEZZE_JIT`, which **defaults to
false**. So `mez dev` executes guest code in the Truffle AST interpreter unless
`MEZZE_JIT=1` is in the environment, and `mez doctor`'s "JIT enabled" refers to
the GraalVM being JIT-CAPABLE, not to compilation being on.

Measured interpreter-mode, the ordering INVERTS — `lamc` becomes the slowest of
the three (plain/captured 1.00, global/plain 1.35, ability/captured 1.46,
ability/global 1.82, all cleanly additive). That is a true fact about the
interpreter and tells you nothing about compiled code. Any benchmark run through
`mez dev` without `MEZZE_JIT=1` measures the interpreter.

#### NOT a rejected inline — the inlined code is just bigger

With the compilation trace (built jar + `MEZZE_TRACE_COMPILATION=1`), all three
programs reach `Inlined 9Y 0N` on their hot OSR compilation. **Nothing is being
refused.** What differs is the size of the result:

| program | the lambda's compiled unit | code size |
|---|---|---|
| `lamplain` | `LazyStub[lambda]` Inlined 5Y 0N | 2427 |
| `lamc` | `LazyStub[lambda]` Inlined 8Y 0N | 4854 |
| `lamp` | `LazyStub[lambda]` Inlined 8Y 0N | **6409** |

lamp's lambda compiles to 2.6x lamplain's code and 1.3x lamc's, which tracks the
runtime ratios. And lamp's hot OSR body is byte-identical in shape to
lamplain's (IR 521/1350, code 5248) — so the loop is not the difference; the
lambda is.

That reframes the question. It is NOT "why does Graal refuse to inline the
ability op" — it inlines it. It is **"what per-call work survives inlining on a
non-constant receiver that folds away on a constant one"**. A constant receiver
lets Graal fold the impl selection; a captured one leaves something behind,
even though the Mezze-level dispatch already resolved `opHash=DIRECT`. The
suspect is a guard or a field read on the receiver inside the inlined body.

#### Found while doing this: `mez dev --trace-jit` emits nothing

`mez --help` documents `--trace-jit` as "Trace Graal compilation + deopts to
stderr". It produces no output, with or without `MEZZE_JIT=1`, and neither does
`MEZZE_ENGINE_OPTS="engine.TraceCompilation=true"` on that path. The working
instrument is a BUILT JAR plus `MEZZE_TRACE_COMPILATION=1`, which goes through
`EmbeddedExec` instead of `RuntimeServer`. That is also what the original
measurements used, which is why their absolute times were ~5x smaller than
`mez dev`'s. Unfixed; it is the tool that would have shortened this.

#### THE CAUSE, and the fix — one unregistered map

`TypedASTCompiler.populateHashToParamNames` walked only `TypedLetDecl`s whose
value is a lambda. **Ability-impl OPERATIONS were never registered**, so
`paramNamesForHash(opHash)` answered null for every one of them, `emitCallDirect`
could not prove full application, and every ability-op call fell back to the
RECORD ABI.

That is not a missed micro-optimisation, because the record ABI is not optional:
unpacking the argument record into flat parameter slots is what the callee's
trampoline is FOR (`TrampolineRootNode.normalizeArgs`), and only a scalar call
may bypass it (`MezzeBytecodeRootNode.resolveScalarTarget`,
`needsTrampoline = hasTail || paramCount > 0`). So an ability op paid a
`LazyStub` + `Trampoline` hop per call — 229 IR nodes for a body of
`B1 { v = v }` — where the same function called as a plain `let` paid none.

The fix registers each impl op's `computeOpHash(impl.hash, opName)` against
`lambda.params + sorted dict params`, which is exactly `compileFunctionBody`'s
own `allParamNames`. Non-lambda op bodies are excluded, the same way #389
excludes non-lambda lets: such a body is a thunk, and calling its hash with a
fresh arg record would discard accumulated partial args.

#### What the inlining tree showed, and why the earlier read was wrong

The old note said the op "does not inline". It does. All three programs reach
`Inlined 9Y 0N` on their hot OSR compilation — **nothing was ever refused.**
What differed was the compiled SIZE, because the trampoline hop survived
inlining:

    before    lamplain  LazyStub[lambda] 5Y 0N  code 2427   (no TrampolineRootNode[e_a] at all)
              lamc      LazyStub[lambda] 8Y 0N  code 4854
              lamp      LazyStub[lambda] 8Y 0N  code 6409   (+ TrampolineRootNode[e_a], 229 nodes)

    after     lamp's lambda tree is byte-identical in shape to lamplain's
              (IR 354, only `unbox` inlined) and no TrampolineRootNode[e_a]
              appears in any of the three.

#### Measured, built jars, 100x work, 3 reps, spread under 1.3%

| program | receiver | callee | before | after | speedup |
|---|---|---|---|---|---|
| `lamplain` | captured | plain fn | 14.8s | 14.9s | 1.00 (control, unchanged) |
| `lamp` | captured | ability op | 30.9s | **14.9s** | **2.07x** |
| `lamc` | GLOBAL | ability op | 20.1-22.0s | **2.3s** | **~8.7x** |

`lamp` now lands exactly on the plain-function control: an ability op costs what
a plain call costs. `lamc` goes further than parity because the scalar ABI lets
Graal fold the constant-receiver case away almost entirely.

#### The codec walk — the gap this was found through

`bench/cases/codec-encode-walker` (committed, because the HTTP harness these
numbers used to come from was never in the tree, which is how a 2.1x effect
stayed filed as 13%). A/B/A on the same build, 3 reps, same answer either side:

| | whole process | loop (less ~0.9s jar startup) |
|---|---|---|
| before | 4368 ms | 3463 ms |
| after | **3302 ms** | **2397 ms** |
| restored | 3263-3332 ms | — |

**The derived encode walk is 1.44x faster — 31% off the loop, 24% off the whole
process.** That is the 15.5% HTTP-route gap's dominant term; the route does more
than encode, so the whole-request gain will be smaller and is not yet measured
(see below).

#### The DECODE side: ~12% — `bench/cases/codec-decode`

Encode was the half that got fixed and measured. Decode gets the scalar ABI by
construction (the fix registers EVERY impl op), and here is the number. Built
jars, two work sizes, slope-fitted:

|  | 400k | 4M | slope/400k |
|---|---|---|---|
| hand | 1094 ms | 1760 ms | 74.0 ms |
| derived | 1194 ms | 1938 ms | **82.7 ms** |

**~12% behind hand-written** — the same ballpark as encode's ~6%.

**The first version of this measurement said 2x and was WRONG.** The hand half
pulled ONE field and returned an `Int` while the derived decoder built a whole
`Row` — both fields, a `Result` each, the error paths. Almost the entire "2x"
was that work difference. The halves now do the same thing and the gap is 12%.

Startup: the derived program's intercept is ~91 ms higher, the derivation's
classload/compile cost. Paid once by a server.

**Two measurement traps, both hit here first and both now in the file's own
comments.** A `decode` of a loop-invariant value is hoisted out of the loop, so
the walk must depend on the counter. And copying a bench project to make a
second work size brings its `.build/` along — `mez build` no-ops and both
"sizes" run the SAME binary, so every slope reads as zero. That produced three
rounds of a confident, wrong "the loop is hoisted" conclusion before the answer
column, unchanged when 10x the work should have scaled it, gave it away. Copy
`Mezze.toml` and `src/` only.

#### The whole request: +16.0%

`bench/cases/http-work-route` — rebuilt and COMMITTED, with `ab.sh` beside it.
Two jars of one app, one per compiler, interleaved A B A B, `wrk -t8 -c256`,
5s warm + 10s measured:

| round | before | after |
|---|---|---|
| 1 | 218,630 | 254,268 |
| 2 | 218,759 | 252,444 |
| 3 | 215,794 | 249,924 |
| 4 | 207,420 | 251,518 |
| **median** | **217,212** | **251,981** |

**+16.0% on the whole request**, and the two ranges do not overlap at all
(before 207-219k, after 250-254k) — every round shows it.

The static control behaved as it should: `/health`, which has no codec, gave
447,735 -> 431,859, i.e. **no gain** (-3.5%). That control is NOISY — its `after`
samples spread 406-455k, 12% — so its median difference means nothing on its
own; what matters is that it shows no IMPROVEMENT where none is possible, which
is what says the +16% is the codec path and not a general shift.

**These absolute numbers are NOT comparable to the ~405k figures elsewhere in
this file.** This is a reconstructed route — 20 records of 4 fields, fully
encoded — not the original program, which was never committed. Only the A/B
delta transfers.

The old 15.5% walker gap is consistent with this: the walk itself got 1.44x
faster, the request does more than encode, and the request-level gain is +16%.

#### Where to look next

The IR is IDENTICAL except the receiver read (`ReadCaptureIR` vs
`ReadGlobalByHashIR`), both a `CallDirect` with the same two-field record, and
dispatch resolves `opHash=DIRECT` for constant, parameter and captured receivers
alike — all probed. So the difference is not in what Mezze emits; it is in what
Graal does with it.

That makes the next step Graal-level, not Mezze-level: dump both compilations
(`--engine.TraceCompilationDetails`, IGV) and compare the two call sites. The one
remaining difference between `lamp` and `lamplain` is the CALLEE's kind — an
ability-impl operation versus a top-level `let`. Bisecting Mezze programs has
reached its limit; eight hypotheses in, the instrument needs to change.

**This is a LANGUAGE-WIDE finding, not a codec one.** Any hot loop calling
ability methods on a non-constant receiver pays it — which is most generic code.
At ~2.1x rather than 13%, it is also much the largest item in this document:
bigger than the router, the header map and the per-request overhead combined.

### W-P2 — CLOSED: ~4% spread thin, and the 11.7% figure was partly drift

Measured by gating the dispatch into cumulative stages (permit → context
enter/leave → guest Request/Response) and running each on the no-router floor
server, `-t8 -c256`:

| stage | req/s |
|---|---|
| 1. Helidon write only — no permit, no context, no guest | 526.8k, 509.7k |
| 2. + take/release the in-flight permit | 519.6k |
| 3. + `TruffleContext.enter`/`leave` per request | 511.0k |
| 4. + build the guest `Request`, call the handler, decode the `Response` | 498.7k, 495.5k |

**The per-stage deltas are NOT resolvable.** Repeating stage 1 gave 526.8k and
509.7k — a 3.3% spread, LARGER than the 1.4% and 1.7% steps it was supposed to
separate. Only the total survives: everything Mezze does per request beyond the
Helidon write costs about **4%** (≈518k → ≈497k).

**And W-P2's own 11.7% was overstated.** It came from Java-direct 545.9k against
Mezze-no-router 482.3k measured in different sessions; today the same floor
server measures 495-499k. So part of that gap was cross-session drift and part is
a Helidon-side difference between `JavaSrv`'s routing and ours — not request or
response construction.

So there is no lever here worth invasive work: no single layer reaches 3%, and
the three candidates (permit, context crossing, guest record building) are each
at or below this harness's noise floor. Hoisting `enter` to once per connection —
which thread-per-connection would permit — is bounded above by about 1.7% and is
not worth the risk.

**If this is ever reopened,** the harness needs to be fixed first: hold
`-XX:SharedArchiveFile` constant (dropping it alone moved a benchmark 238.7k →
196.3k) and take medians of several runs, because single runs vary by 3-4% —
which is the size of the entire effect being hunted.

### W-P3 — CLOSED as a documented trade-off: the p99 cost of dropping the queue

Removing the queue took p99 from 3.8ms to 5.1ms while the mean went 333k → 391k.
It moves monotonically with the `workers` permit count (16 → 395k at p99 4.6ms;
32 → 392k at 4.8ms; 64 → 391k at 5.1ms; 128 → 401k at 6.3ms), so a narrower
bound buys tail and a wider one buys throughput.

The default stays WIDE and is not changed: a handler that performs a request of
its own holds its permit while it waits, so a bound near the core count would
starve an IO-bound route — a worse failure than a 0.3ms tail. The whole table and
that reasoning now sit on `DEFAULT_WORKERS`, where someone choosing a
`ServerConfig` will find them.

### W-P4 — CLOSED: ~2%, not 5.5%, and the fix is disproportionate

Re-measured with the interleaved harness (the 5.5% was a single run):

| headers on the request | cost of building the guest map |
|---|---|
| ~7 | +1.6% |
| ~14 | +1.9% |

So it is about 2% for a realistic request, and it scales with header count rather
than being free below some threshold. Recovering it needs a LAZY `MezzeMap`
(`MapStorage` is the seam) in a module everything depends on — disproportionate
for 2%. Also measured and NOT it: building the guest map directly instead of via
an intermediate `HashMap` (320.1k vs 317.7k, same build).

### W-P5 — LANDED: a static route matches by comparison, 5.3% -> 2.3%

The router's overhead over no router at all, same harness either side:

    before   no-router 484.7k   routed 458.9k   -5.3%
    after    no-router 492.2k   routed 480.7k   -2.3%

Routed throughput 458.9k -> 480.7k, +4.8%. The param route is unaffected
(417.7k), because a pattern that binds still takes the segment walk.

A pattern with no `:name` now carries its slash-trimmed form and matches by ONE
STRING COMPARISON — no `str::split`, no `filter`, no zip, no parameter map.

Why the earlier attempt failed, which is the useful part: it skipped only the zip
and the map for a static route while STILL SPLITTING the path, and measured as
noise (457.5k -> 457.3k). The split is the expensive half, so skipping the fold
alone bought nothing. Avoiding the split is what pays.

A `//` in the path falls back to the segment walk, because `segments` drops empty
pieces and so `//health` must match `/health` where a comparison would not.
`HttpRouterTest` pins both halves of that branch and the root route.

### W-P6 — ~2.3x, and the slim runtime jar nearly DOUBLED native throughput

**Re-measured 2026-10-18 after the distribution fix, interleaved in one session,
15s warm + 10s measured, `bench/cases/http-work-route`:**

| | req/s | vs JVM |
|---|---|---|
| JVM (jar) | 251.9k | 1.00 |
| native, heap tuned | **108.4k** | **2.3x behind** |

Six readings, 105-110k, in a tight cluster. The JVM side has four, 250-254k.

**The "~2x" originally recorded here was RIGHT. The 4.5x I replaced it with is
WITHDRAWN — and I cannot say why it was wrong.** It came from a 55.8k reading on
a binary built before the distribution fix. I checked the two obvious
explanations and neither holds:

- **Not the input jar.** The app jar went 106MB -> 20MB, but the BINARY barely
  moved: 109MB before, 108MB after. If the image contents are the same size,
  "fewer classes in the image" cannot buy 2x.
- **Not warmup.** The old run warmed 5s and the new one 15s, so this looked
  likely. Measured directly on the current binary: 5s gives 106.8k and 109.6k,
  15s gives 104.0k and 110.3k. No effect.
- **Not build contention either**, which is the usual suspect on this box — the
  native-image build that was later caught at 836% CPU ran at 16:07, and those
  readings were taken at 15:43.

So the honest record is: the current, clean, warmup-insensitive figure is
**~2.3x**, and the 55.8k is an unexplained outlier that should not be quoted.
Two same-size binaries differing 2x with no identified cause is itself worth
knowing — if native throughput is ever measured again and comes out near 50k,
this is the thing to chase rather than assume.

**Binary SIZE is ~108MB** for the web-server program, against a 20MB app jar.
The provider strip did not change it (109 -> 108MB), which is the expected
result and a useful confirmation: the providers were never getting into the
image, only into the input. The 108MB is our own closure — the Mezze
interpreter, the Truffle runtime, Helidon — not the application. Shrinking it is
untouched ground.

**PE is confirmed ACTIVE in native binaries**, which had been an open question.
On a tight guest loop (4M iterations) native finishes FASTER in total than the
jar — 1491ms against 1756ms — because it skips ~1s of JVM startup; subtract
startup and the native loop is ~2x slower, not the 10x+ an interpreter would
give. Startup is where native wins outright: ~40ms against ~1s.

**Heap tuning still matters** (SVM takes the sizes only in bytes):

    ./app-native -XX:MaxHeapSize=4294967296 -XX:MinHeapSize=1073741824 \
                 -XX:MaxNewSize=1073741824

**What remains is distribution, not code.** PGO does not exist in GraalVM CE —
`--expert-options-all` lists no PGO or ProfileGuided option, it is an Oracle
GraalVM feature. CE has Serial GC only. And Truffle-on-SVM has no host C2 for
the interpreter itself. None of that is addressable from this repo, so the
remaining 2.3x is a property of the distribution.

**Measurement traps hit here, both now avoidable.** The two binaries share a
name, so `pkill -f http_work_route` killed the server the NEXT round had just
started — that produced one `NORESULT` and one half-rate reading that looked
like a 2x JVM regression. Kill by an ANCHORED pattern. And if a stale server
still holds the port, the new one fails to bind and `wrk` silently measures the
OLD process; absolute numbers from different sessions are not comparable, so
interleave and quote the ratio.

### W-P6 (superseded) — native is ~2× behind the JVM### W-P6 (superseded) — native is ~2× behind the JVM

195.3k tuned vs ~405k on the JVM. Two causes:

1. GraalVM **CE has only Serial and Epsilon GC** — `--gc=g1` is Oracle
   GraalVM. Default heap sizing costs **8.5%**: 363 pauses/1065ms in a 10s run,
   76 of them FULL GCs; tuned → 83 pauses/895ms, 180.1k → 195.3k.
2. The rest is Truffle-on-SVM with no C2 — at the floor with ZERO guest
   computation, native was 232k against the jar's 316k.

SVM uses `-XX:MaxHeapSize` / `-XX:MaxNewSize` / `-XX:MinHeapSize`. **`-Xmx` and
`-Xmn` are SILENTLY IGNORED** (cost two "no change" readings before I noticed).
`MinHeapSize` is the load-bearing one and is absolute, so it is not safe to bake
into `native-image-flags.txt`. PGO is untried.

---

## Part 3 — Untested ground (after the above)

Nothing below has been measured, so nothing about it should be claimed.

- **Database / IO path.** Never benchmarked, and it dominates most real web
  apps. The first thing to measure once the above is done.
- **TLS.** Never benchmarked; it changes the CPU profile substantially.
- **Memory per connection** under load.
- **A standard benchmark** (TechEmpower-shaped) so the numbers compare to other
  languages' published figures instead of only to this harness.

---

## The pattern worth remembering

Nine allocation-removals were attempted this session and **all measured neutral
or negative**: the router pattern re-split (at `-c64`), the no-binding fast
path, the intermediate header map, per-node calls, the field-transform match,
wide argument records, lazy `iter` staging, bytes-per-node encoding, and the
writer-closure encoder. The JIT already eliminates them.

The only two changes that paid were STRUCTURAL: deleting a per-request thread
handoff (+18.5%), and moving per-request work to startup (+7.3%). Prefer
removing a layer over removing an allocation.
