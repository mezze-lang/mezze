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

### W-P1 — CAUSE FOUND: an ability op on a NON-CONSTANT receiver does not inline

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

#### Where to look next

Both call shapes emit the SAME IR node: `emitCallDirect(opHash, MakeRecordIR(…))`
for the ability op, and a plain `CallDirect` for a top-level function. The
typechecker resolves both to `opHash=DIRECT` for a constant AND a parameter
receiver — verified by probe. So the difference is in the CALLEE's compiled form,
not the call site: an impl op's target is apparently not inlined through the way
a plain `let` function's is, and a constant receiver hides that by folding the
call earlier.

Next step is Truffle-level, not language-level: dump the compilation
(`--engine.TraceInlining`, `--engine.TraceCompilation`) for the two shapes and
find why a `CallDirect` to an impl op is not inlined with a non-constant
receiver. Suspect a lazy stub between the caller and the op body
(cf. `LazyStubRootNode`).

**This is a LANGUAGE-WIDE finding, not a codec one.** Any hot loop calling
ability methods on a non-constant receiver pays it — which is most generic code.

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

### W-P4 — headers, 5.5% (only with real headers)

14 realistic headers: 310.9k with the guest map built vs 329.0k skipped. At
wrk's default 4 headers it is FREE, which is why a default benchmark hides it.
The cost is the n persistent `MezzeMap.put`s plus `toLowerCase`, so recovering
it needs a LAZY `MezzeMap` — invasive in a module everything depends on.
MEASURED AND NOT IT: building the guest map directly instead of via an
intermediate `HashMap` (320.1k vs 317.7k, same build).

### W-P5 — the router's remaining ~5%

482.3k no-router → 457.5k routed, after the pattern hoist took 426.3k → 457.5k.
What is left is `path_of`, `segments`, and the parameter map.
MEASURED AND NOT IT: precomputing whether a pattern binds any `:name` so a
static route skips the zip/fold/map (457.5k → 457.3k; the JIT already removes
it).

### W-P6 — native is ~2× behind the JVM

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
