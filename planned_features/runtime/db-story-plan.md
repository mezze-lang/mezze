# The database story

Nothing exists yet: no `std::effects::db`, no driver, no row mapping.
(`store-sqlite` is the COMPILER's own code store — `com.mezze.language.*` — and is
not a user-facing database.) So this is a clean slate, and it should be built the
way the HTTP story was, because that came out well and the pieces compose.

**Why this is next.** Every performance number in `web-stack-backlog.md` is for a
handler that touches no data. Real applications spend most of a request waiting on
a database, which means two things: the remaining single-digit percentages in that
backlog matter less than they look, and nothing here has been measured at all.

---

## What the HTTP story got right, and should be copied

1. **The effect has FEW operations, and routing is not one of them.** `HttpServer`
   has exactly one op, `serve`; a route table is a pure VALUE that folds into one
   handler. So the effect never grows an operation per feature. A query should be
   a value too, and `Db` should not grow an op per query shape.
2. **A plain function is the test seam.** A handler is callable with no server, so
   most tests need no socket. A query must be inspectable and buildable with no
   database, so most tests need no database.
3. **Failure is a value.** Everything fallible returns `Result`, and
   `json::to_bytes` taught the cost of the alternative: it swallowed errors into
   an empty answer and a handler replied 200 with a zero-byte body.
4. **Performers own their resources and register for dispose.** `HelidonHttpServer`
   binds its socket and `context.registerForDispose` closes it, because a guest
   that never calls `stop` must not leave the port held. A pool is the same
   problem, and this session fixed ELEVEN leaks of exactly that shape.
5. **`iter` is the lazy structure.** A result set is the canonical lazy sequence.
6. **The codec is already the record ⇄ wire-shape machine.** `QueryC` maps a record
   to query parameters; a row is another wire shape. Do not write a second mapper.

---

## The shape

### W-D1 — the `Db` effect and a `MockDb` performer

```mezze
## A value, not a string: params are bound, never interpolated, so a query
## cannot be assembled into an injection.
pub type DbValue = | DbNull, DbInt { val: Int }, DbStr { val: Str },
                     DbFloat { val: Float }, DbBool { val: Bool }, DbBytes { val: Bytes } |

pub type Query = { sql: Str, params: [DbValue] }

pub type DbError = | Unavailable { msg: Str }, Rejected { sql: Str, msg: Str },
                     Conflict { msg: Str }, Timeout { ms: Int }, Decode { msg: Str } |

effect Db where {
  run:   { q: Query } -> Result Rows DbError <Db>,
  apply: { q: Query } -> Result { changed: Int } DbError <Db>,
  tx:    { body: {} -> Result 'a DbError <...> } -> Result 'a DbError <Db>,
}
```

Three operations, chosen the way `serve` was: `run` reads, `apply` writes, `tx`
scopes. Everything else — building a query, mapping a row, retrying — is a pure
value or a plain function over one.

`MockDb` answers from a table supplied at construction, so a route's data path is
testable with no driver and no file. `tx` under `MockDb` runs the body and
discards nothing; a rollback is a value the test can assert on.

### W-D2 — rows ⇄ records through the codec, NOT a second mapper

A row is a wire shape, so it is a `Format` with Tier-2 impls, exactly as `JsonC`
and `QueryC` are:

```mezze
let users: Result [User] DbError = q.run {}.and_then { f = { rows } -> rows.decode { config = row_c } }
```

This is the one design decision most likely to be got wrong by writing a bespoke
mapper. Two lessons apply directly:

- Key the impls on the WIRE SHAPE, not on type names
  (`codec_sequence_mapping`).
- Keep `'out = Rows`, i.e. build the intermediate and convert once. Streaming
  straight to bytes was MEASURED SLOWER than building a tree in the JSON work
  (199.6k vs 233.4k, and a writer-closure variant 149.2k), because the expensive
  part is per-node work in guest code, not the intermediate.

### W-D3 — streaming large result sets with `iter`

`run` materialises, which is right for the common small result. A large one must
not:

```mezze
stream: { q: Query } -> Result (Iter Row) DbError <Db>
```

`Iter` is the lazy structure and its stages allocate only descriptors, so a fold
over ten million rows holds one row at a time. This is also the first thing in
the stdlib that will make `iter`'s laziness load-bearing rather than tidy.

### W-D4 — a connection pool that cannot outlive its context

A performer owns its pool. Non-negotiable, from this session's leak sweep:

- `registerForDispose`, so a guest that never closes is still cleaned up.
- No static state. Eleven leaks were static fields holding per-context values;
  `MezzeContext.nativeState` is the seam, and it must be called from a
  `@TruffleBoundary` because a `ConcurrentHashMap` call is SVM-blocklisted.
- Bound concurrency and refuse past it, with a visible error rather than an
  unbounded queue — `DEFAULT_WORKERS` and the 503 path are the precedent.
- Blocking a virtual thread is FINE and is the design: handlers now run on
  Helidon's connection threads, and a blocking driver call parks that thread
  without consuming a carrier.

### W-D5 — the first real driver — **LANDED**

SQLite over JDBC, raw SQL with bound params, as recommended. `SqliteDb { path }`
is a performer; `DbNatives` is the driver. 28 tests in `SqliteDbTest`, all
against `:memory:` or a `@TempDir` file, so the whole surface is proven against
a real database with no fixture and no server.

**What the implementation decided, and why.**

- **The connection is not a guest value.** `initialize_scope` opens it,
  `finalize_scope` closes it, `registerForDispose` catches the rest. The natives
  take no `db` argument, so there is no way for a guest to hold a connection past
  its scope — which it could not detect.
- **One connection per scope, shared by every thread in it.** A connection per
  thread was the first design and it was WRONG for the case the effect exists
  for. A handler runs on a thread the server owns; `:memory:` is per connection,
  so a handler opening its own queried a different, empty database and answered
  **no rows with no error at all**. Measured, not reasoned: a probe returned
  `db error` before the fix and `count=2` after.
- **A thread that entered no scope uses the one open scope.** This is what makes
  `perform SqliteDb in perform HelidonHttpServer in …` work, since a Helidon
  thread inherits the handler CHAIN but not the thread-local state the chain was
  built beside. With SEVERAL open it is told so instead of given one — there is
  no database the operation could mean, and picking the earliest would make a
  route read from whichever scope happened to open first.
- **A transaction holds the scope's lock from begin to commit.** Otherwise a
  concurrent query reads rows that may be rolled back. A `ReentrantLock` gives
  all three things needed at once: cross-thread exclusion, same-thread
  reentrancy (a query inside a `tx`, and a nested `tx`, are not self-deadlocks),
  and a bounded wait — a wait that runs out answers `Timeout`, so a stuck
  transaction cannot pin a thread pool. Nesting is read off the lock's HOLD
  COUNT, not the connection's autocommit flag: the flag is shared, so it told a
  merely-concurrent second thread it was nested.
- **A nested `tx` joins the outer one** rather than opening a SAVEPOINT. The
  promise is that a body commits together or not at all, and an inner rollback
  would leave the outer transaction half applied.
- **`DbError` is chosen by what the caller can DO**, not by the driver's error
  code: `Conflict` for SQLite 5/6 and SQLSTATE 40 (retry the transaction),
  `Unavailable` for 08 and an unopenable file (retry the connection), `Rejected`
  for everything else (fix the query), `Timeout` for the lock wait. A code is a
  fact about SQLite; a `DbError` is advice to the program.
- **A path that cannot be opened** leaves no connection open and the first
  operation answers `Unavailable`. `initialize_scope` has nowhere to put a
  failure, and crashing at `perform` is worse. This is the one place the design
  pays for the connection not being a value.

**Every behavioural claim was perturbed.** Removing the rollback, letting an
inner `tx` commit, making the scope stack answer the outermost, dropping the
live-scope fallback, and reading nesting off autocommit each broke exactly the
tests that assert them and nothing else.

**What serializing costs** is unmeasured and is W-D6's job. The fix for it is
W-D4's pool, not a connection per thread — that is the thing that was already
tried and found to answer from the wrong database.

**Native image:** still untested. `org.xerial:sqlite-jdbc` needs reflection and
ships a native library per platform, so expect the same kind of configuration
Helidon needed. `just native-test` is red for unrelated reasons (W-C2).

### W-D6 — measure it, with the harness that works

`bench/compare.sh` (interleaved A/B, medians) exists because single runs on this
box vary 3-4%. For the database the numbers that matter are different from the
HTTP ones:

- queries per second at a fixed pool size, against throughput of the route
- latency ADDED over a no-data route, at p50 and p99
- behaviour when the pool is exhausted (the 503-equivalent)
- how a transaction's scope interacts with the per-connection handler thread

A route that does one indexed lookup is the benchmark to build first, because it
is the shape most applications have and nothing in this repo measures it.

---

## Order

W-D1 (effect + MockDb) ✅ → W-D2 (codec row mapping) ✅ → W-D5 (one real
driver) ✅ → W-D4 (pool) → W-D6 (measure) → W-D3 (streaming).

W-D3 last deliberately: streaming is the feature most likely to be designed
speculatively, and it should be built once a real driver and real measurements
say what it has to do.
