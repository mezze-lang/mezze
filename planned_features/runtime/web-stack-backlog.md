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

### W-P1 — the JSON derivation walker, ~20%

The biggest remaining guest-side cost, on the shape most web handlers have.
Fair comparison (both build the records first, then encode in a second pass):

| | req/s | per core |
|---|---|---|
| derived walker (`rows.to_json {}`) | 234.1k | 25.9k |
| hand-written encode, same two passes | 281.8k | 34.1k |

**Five causes DISPROVED — do not re-derive them.** Each was built as a server
variant and measured:

1. Per-node calls are FREE (101 calls/request: 292.1k vs 293.7k for direct
   construction). Truffle inlines them.
2. The field-transform match is FREE — two field reads plus a 5-way `match
   config.shared.field_transform` per field (80/request) measured 241.2k vs
   238.7k when replaced by the literal.
3. The intermediate records are not it — the fair two-pass comparison above
   still shows the gap.
4. Wide argument records and `ctx` threading are FREE — giving hand-written
   calls the walker's exact `{ it, val, ctx }` shape measured 285.6k.
5. Not a fusion difference — `MEZZE_CHAIN_CANON_EMIT_DEBUG` output is IDENTICAL
   for the walker and the hand-written version.

And three REPRESENTATION changes were measured and all lost (949-byte payload):
tree + one native serialize **235.8k**, bytes concatenated per node 199.6k, a
writer closure per node 149.2k (p99 44.8ms). So the gap is NOT the data shape.

**The lead was TESTED and is a SIXTH disproved cause.** It was real: inside the
generated body `config` was typed as a bare type VARIABLE, not the concrete
`JsonC`, so every `config.encode_*` went through DICTIONARY indirection — about
101 indirect, non-inlinable calls per request. The reason is that an impl's
`abilityTypeArgs` do not substitute into the op's declared parameter types when
its body is typed (annotating the op's `paramType` does not help either; that
field is validated against the ability's declared signature rather than used to
type the body).

Fixed in `CodecGenerator` by reading the config through a type-ANNOTATED local,
so the whole walk resolves statically — verified: `receiver=TypeApplication(JsonC)
opHash=DIRECT` for `encode_int`/`encode_str`/`encode_record`/`encode_sequence`,
where it had been `VarType(config) → NULL(dict path)`.

**It changed throughput by nothing.** 238.7k vs 234-236k before; per core 26.2k
vs 25.9-26.1k. Kept for the structural reason (a generated body that cannot see
its own config's type is a latent dictionary hazard, and static resolution is the
direction the rest of the compiler goes), NOT as a performance fix.

**So the 20% is still unexplained after six hypotheses.** Every individual layer
that was removed — the calls, the dispatch, the field transform, the argument
width, the intermediate, the representation — turned out to be free. The
remaining shape of the thing is that no SINGLE layer accounts for it, which
points at the inlining budget across the whole chain (handler → to_json → encode
→ generated impl → seq_map lambda → encode_record → encode_str). Testing that
needs a harness that holds the AppCDS archive constant: dropping
`-XX:SharedArchiveFile` alone moved this benchmark 238.7k → 196.3k, which is
larger than the effect being measured, and is what made a first attempt
unreadable.

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
