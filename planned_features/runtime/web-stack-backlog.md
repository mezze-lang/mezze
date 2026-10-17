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

### W-C1 — `abilityObligationAtCallSite` drops an all-concrete obligation

`AbilityObligation.kt`:

```kotlin
val varIndex = constraint.typeArgs.indexOfFirst { it is MezzeType.VarType }
if (varIndex < 0) return null          // every type arg concrete → DROPPED
```

An obligation is raised only when one of its type args is still a type
VARIABLE. When the call site already knows every type, the obligation is thrown
away — so a call site that knows MORE gets checked LESS. That is the same
inversion as BUG 25, one layer up.

Suspected repro — a user function forwarding a codec obligation, called with a
concrete type:

```mezze
let enc: { x: 'a } -> Json with Encode JsonC 'a = { x } -> x.encode { config = json_default }
let main = {} -> enc { x = { a = 1 } }    ## 'a is concrete HERE
```

If the obligation is dropped, nothing derives `Encode JsonC { a: Int }` and the
compiler then asks for a dictionary nobody generated.

**Status.** The fix is known and was already written once: bind a fresh variable
to the head arg, emit `Equality(fresh, head)`, raise the obligation on the fresh
variable — the solver then walks the same path it walks when the type is not yet
known. It compiled cleanly, changed no observable behaviour, and was REVERTED
rather than left in the tree unproven. It needs a test that fails without it.

**Done when** a test demonstrates the hole, the fresh-var fix closes it, and
both gates pass.

### W-C2 — 302 native-image blocklist violations (C5 remainder)

`just native-test` reports ~302 runtime-compilation blocklist violations, all
reaching the blocklist through Truffle's `Instruction.toString` → `Formatter` →
`Preconditions.outOfBoundsMessage`, or through `EmbeddedExec`.

GraalVM 25.0.3 has NO `-H:` option to ignore a blocklisted method, so the fix is
to route bounds failures through a `@TruffleBoundary` instead of suppressing the
report. `-H:+PrintRuntimeCompilationCallTree` names the sites.

**Done when** `just native-test` is clean, or every remaining violation is
individually explained.

### W-C3 — a bare top-level expression's residual effects are unchecked (C1 remainder)

A LANGUAGE DECISION, not a patch: what should a module's trailing expression do
with an effect row it never discharges? Options are in
`concurrency-and-context-bugs.md` (option 4 was the preferred one). Needs the
owner's call before any code.

### W-C4 — C3's correctness half is unproven

Carried in `concurrency-and-context-bugs.md`. Either demonstrate the failure it
describes or close the ticket as unreproducible. Do not leave it asserted.

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

**The one untested lead:** whether `config.encode_*` in the generated impl body
resolves to a DIRECT call or goes through dictionary indirection. A
record-of-functions probe meant to test this returned an empty body and was
abandoned. Check the emitted IR for `CallDirect` vs `CallIndirect`.

### W-P2 — request/response construction, 11.7%

Java-with-our-handoff 444.7k → Mezze no-router 482.3k is the only step left at
the floor: building the guest `Request` record and decoding the `Response`.
Not yet broken down further.

### W-P3 — the p99 regression from deleting the handoff

Removing the queue took p99 from **3.8ms to 5.1ms** while the mean improved
hugely (333k → 391k) — a real regression, left standing. It moves with the
`workers` permit count: 16 permits measured 395k req/s at p99 4.6ms, 128
permits 401k at 6.3ms. Either pick a better default or explain the trade-off
where the bound is set.

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
