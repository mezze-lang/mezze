# Introspection — a language that can ask about itself

Working document, started 2026-10-11. Nothing here is built unless it says so.
Edit freely; this is where the idea is being worked out, not a spec.

Related: `../tooling-platform/thesis.md` (read + evaluate as the two primitives),
`../correctness/` (sample, trace, instrumentation, debugging), `../macros/`
(comptime, pattern macros).

---

## 1. The idea

Mezze should feel like a dynamic language — you can poke at a running program,
ask it what it is doing, change it, re-run part of it — **but every answer is
typed**, and the type system is what makes the poking safe.

Every capability on the roadmap is the same act, *code asking a question about
code*, at a different moment:

| when | the question | mechanism | status |
|---|---|---|---|
| parse time | turn this syntax into that syntax | macros (`!()`, pattern macros) | designed (`../macros/`) |
| compile time | compute this value now | `comptime` | built |
| compile time | what is in this function? what is the codebase? | the `Codebase` effect (§9) | designed; `branches` exists inside `mez sample` only |
| run time | what inputs does this take? | `Arbitrary` + `Sample` | built |
| run time | **what is it doing?** | observation — this document | partial (DAP engine, coverage collector) |
| edit time | what is the codebase? | store, signature index, eval, LSP | largely built |

Three things Mezze already has make this one design rather than six tools:

1. **Content hashes are identity.** Anything we learn about code — a trace, a
   coverage site, a profile, a recorded call — can be keyed by *what the code
   is*. Nothing is keyed by name, and an identity covers what a declaration's
   names resolve to, so an unchanged identity means unchanged code (§5.1).
2. **Effects are the only door to the world.** Pure code is deterministic given
   its inputs; everything nondeterministic crosses an effect operation. So the
   effect boundary is where observation, recording and replay naturally sit.
3. **Effects make introspection visible.** Code that observes or alters other
   code says so in its row. The observed code's row does not change.

### The keystone: one vocabulary

All of it must speak one set of Mezze types — spans, declaration references,
branch sites, events (F-1, #133, sliced small). A macro receives them, `branches`
returns them, a trace contains them, the wire protocol serialises them. If each
tool invents its own, we build the same idea four times; we have already done
that with annotation walkers (six) and IR transformers (four).

Rule for every new capability: **a Mezze-level primitive with Mezze types
first; CLI, LSP and agent surfaces are built on it.**

---

## 2. Prior art — what to take, what not to

| system | what it is | take | leave |
|---|---|---|---|
| Python `sys.settrace` | per-frame callback on call/line/return/exception | the event vocabulary | global, slow everywhere once on; callback can mutate `f_locals` |
| Python `sys.monitoring` (PEP 669, 3.12) | per-tool IDs, events (`PY_START`, `PY_RETURN`, `LINE`, `BRANCH`, `CALL`, `RAISE`…), per-code-object local events | **returning `DISABLE` turns off that one location**; several tools coexist without clobbering each other | untyped event payloads |
| Ruby `TracePoint` | events `:call :return :line :raise :b_call :script_compiled`; `enable(target: method)` | **targeting one method**; a compile-time event (`:script_compiled`) | `tp.binding` + `eval` — arbitrary access into the observed frame |
| Ruby `Coverage` | line/branch/method coverage as a stdlib module | coverage is a library value, not a tool's private format | — |
| Erlang `dbg` / `recon_trace` | trace patterns + match specs in the VM, attachable to a live node | **live attach**; `recon_trace` **limits by default** (count/rate), so tracing production cannot take it down | match-spec mini-language |
| DTrace / USDT | static probe points, no-ops until enabled | **zero cost when off** | — |
| Elixir `:telemetry` | libraries emit named events, handlers attach | libraries declare their own observation points | events are named by lists of atoms |
| Clojure `tap>` | send any value to whoever is listening | the ergonomics of "just show me this" | untyped, global |
| rr / Replay.io | record nondeterminism, replay exactly, time travel | **record only the boundary** | needs an OS/browser-level boundary; Mezze has one in the language |
| Smalltalk / Pharo | inspect and redefine anything live | malleability as the default | no types; anything can break anything |
| Hazel | live programming with typed holes that evaluate | **incomplete programs run** | — |

The pattern: dynamic languages have the reach but untyped payloads and
unrestricted mutation; static languages have the types but stop at a debugger.
Mezze can have Python's reach with Ruby's targeting, Erlang's live attach and
safety limits, DTrace's cost, and rr's replay — **with a typed payload and a
row that says who is observing.**

---

## 3. Rules

1. **The observer pays.** Observation is an effect on the *observer's* row.
   The observed function's type, row and hash do not change. You cannot tell
   from inside a function that it is being watched.
2. **Read-only by type.** A probe receives immutable values and cannot perform
   effects into the observed code — its callback runs in the observer's row.
   There is no `f_locals` write and no `binding.eval`. Changing behaviour is a
   separate, explicit act (handler swap, hot swap — §4.10, §4.13).
3. **Zero cost when off.** Built on BDSL tag instrumentation (already enabled,
   `StatementTag` only today) and Truffle `Assumption`s, so an unobserved
   location compiles exactly as now. PEP 669's per-location `DISABLE` maps
   directly onto invalidating one assumption.
4. **Targets are references, not names.** `at = clamp` resolves to a hash at
   compile time. No string matching, ever.
5. **Bounded by default.** Every probe and recording has a count/size/time
   limit unless the caller raises it (Erlang's lesson; also the anti-hang
   invariant).
6. **Visibility holds.** A private declaration is not observable from outside
   its module, same as it is not callable.
7. **Results are Mezze data.** A trace is a `[Event]`, coverage is a
   `[BranchSite]`. Querying them is ordinary `Seq` code — no separate query
   language.

---

## 4. Ideas

Ordered roughly from "falls out of what exists" to "speculative". Each says why
Mezze in particular can do it.

### 4.1 Typed probes on a reference

```mezze
## sketch — syntax not final
let watched = perform Watch { limit = 100n } in do
  watch { at = clamp, run = { } -> serve {} }

## watched.calls : [Call { val: Int, min: Int, max: Int } Int]
```

The event type is derived from the target's signature by ordinary generics —
`watch: { at: 'i -> 'o <...>, run: ... } -> { val: 'r, calls: [Call 'i 'o] }`.
Ruby's `enable(target:)`, but the payload is typed and the target is a hash.
No reflection on values is needed; the runtime only needs a hook at the target's
entry and exit.

### 4.2 Recording the effect boundary = complete recording

Pure code is deterministic, so **a log of every effect operation and its
result is a complete recording of a run.** rr has to intercept syscalls and
signals; Mezze already has a typed boundary in the language.

```mezze
let tape = perform Record in do main {}     ## [EffectEvent]
perform Replay { tape } in do main {}       ## same run, no world access
```

Replay is a performer that answers each op from the tape and fails loudly on
divergence (a different op, or different arguments, than recorded). Time
travel then needs no snapshots: to reach step N, replay to step N.

Known holes to close before trusting it: natives that are nondeterministic
without an effect, parallel scheduling order, STM retries. Each is either an
effect already or must become one — finding them is itself a soundness audit.

### 4.3 Every observed call is a test

For a pure function, `(hash, input) -> output` *is* a regression test: no
setup, no mocks. For an effectful one, `(hash, input, tape slice)` is — and the
tape slice is exactly a scripted performer, which `mez sample` already
generates (`SampleScripts`).

So: watch production (bounded), keep the calls that failed or were unusual,
and each one is a test that reproduces it. Keyed by the *closure* hash (§5.1),
it is valid for exactly as long as neither the function nor anything it calls
changes.

### 4.4 Behaviour diff between two hashes

Replay a set of recorded inputs against the old hash and the new one; report
the inputs whose outputs differ. "Did my refactor change behaviour?" becomes a
query, not a test-writing exercise. Hashes exclude formatting and comments, so
a reformat is provably a no-op and needs no run at all. A rename is *not*:
Mezze hashes names as written on purpose (named arguments make them API — see
`ParsedDeclHasher`), so renaming a parameter is a real change and gets diffed.

### 4.5 Coverage as a join, not a tool

`branches { of = f }` (static: the sites that exist) joined with
`reached` (dynamic: the sites that ran) is coverage. Both are `[BranchSite]`
keyed by hash and span. A coverage report is twenty lines of library code, and
a coverage-guided fuzzer is a loop: sample, observe, keep inputs that reach new
sites, re-sample around them. The runtime half (collector, `branchHits` on the
wire) is written and uncommitted; it is blocked on the shared IR walker (#187).

### 4.6 Static × dynamic joins beyond coverage

The same join, other pairs:

- **declared row × performed ops** — "`fetch` declares `<Http, Log>`; in 40k
  calls it never performed `Log`." Dead effects, found by observation.
- **declared type × observed values** — "`n: Int` was never negative in 40k
  calls" → suggest `Nat`, or a refinement. Advisory only, never automatic.
- **observed distributions × `Arbitrary`** — production-shaped sampling: seed
  `CoveringSample` with the shapes real traffic actually takes, so samples look
  like real inputs and still cover every branch.

### 4.7 Observations survive edits

Traces, coverage and profiles keyed by closure hash (§5.1) live in the store.
Edit one function: its data and its callers' data are invalidated, everything
else's is still valid.
"Coverage for the whole project" is incremental for free, and a doc site can
show real observed calls next to a function's signature.

### 4.8 Typed holes that run

An unfinished function performs a `Hole` effect whose result type is the hole's
type. Handle it with a sampler and the program runs anyway; handle it with
`Watch` and you see what flowed *into* the hole. Write the call site first, run
it, look at the arguments the hole received, then write the body. Hazel's
idea, but the filler is the existing `Arbitrary` machinery and the hole is an
ordinary effect, so a program with holes is rejected at entry exactly like any
other undischarged effect.

### 4.9 Spans from handler extent

A `Span` effect, where the span's extent is the `perform`'s dynamic extent.
Nested performs are nested spans; an OpenTelemetry exporter is one handler; a
test that asserts timing is another. Context propagation across async is not a
separate mechanism — handler chains are captured into functions already
(`HandlerBoundFunction`, S-20), so a spawned task carries its span.

### 4.10 What-if execution

Replay a tape with **one** handler swapped: `Time` shifted forward a day, the
third `Http` call failing, `Random` fixed. Fault injection is a handler, and
replay holds everything else constant, so the difference in the output is
caused by exactly that change.

### 4.11 Redaction by type

Recording values from production records secrets. Make that a type question:
values of a `Secret` newtype (or any type implementing a `Redact` ability) are
recorded as their hash, never their content. The recorder decides by type, not
by field name, and a function's signature tells you in advance whether it can
leak.

### 4.12 Live attach

`mez dev` already holds a running program behind the wire protocol. Attach a
typed probe to it from another process, Erlang-style, with limits on by
default. The answer comes back as Mezze data in the vocabulary types, so an
agent attaching to a running service gets exactly what a library user would.

### 4.13 Hot swap by hash

Rebinding a name in a running dev process installs a new hash; calls already in
flight finish on the old one (Erlang's two-version rule, made trivial by
content addressing). Combined with 4.4: swap, replay the last N recorded
requests against both, show the diff before committing to the new version.

### 4.14 Observing compile time

Ruby's `:script_compiled` points at a real gap: macros and `comptime` run code
nobody can see. Expansion events (input syntax, output syntax, span provenance)
and comptime evaluation events, in the same `[Event]` vocabulary, make macro
debugging a trace query. This is the M-4 provenance work seen from the
observer's side.

### 4.15 Libraries declare their own probe points

Like `:telemetry` and USDT: a library marks named, typed observation points
that cost nothing unless watched. Unlike them, the point is a declaration with
a hash and a type, so a watcher is checked against it at compile time.

---

## 5. The codebase is a database

Most languages store programs as text files and derive everything else on
demand. Mezze stores declarations as content-addressed blobs with indexes over
them: dependency `edges` between hashes, a relational signature index
(`type_nodes`, `type_node_effects`, `type_node_constraints`), name → hash
history, ability impls. Blobs are append-only, so old versions never disappear.
That unlocks a second set of ideas, about code *across versions* and *across
the whole codebase*, not just one run.

### 5.1 Identity covers code; a behaviour key covers what it does

Done 2026-10-11 (`1d1f2a0e2`, `68c8b721f`): a declaration's identity is its
structure with every free name replaced by the identity of what it resolves to
(`DeclIdentity`); recursive declarations, and modules that import each other, are
hashed as groups; the typechecker's own bindings are checked against it. An
unchanged identity means unchanged code, transitively.

It does not yet mean unchanged *behaviour*. Two inputs sit outside it:

- **Impl selection.** `x.show {}` resolves to an impl during type checking. The
  identity covers which providers of `show` were in scope, not which one the
  solver picked — and the pick depends on the argument's type, which can change
  when nothing the caller names does (a coherent program has one impl per
  ability/type, so this is rare, but an orphan impl in another program differs).
- **Natives.** `__native_seq_size` is hashed by name; what it does lives in the
  runtime jar.

**Design (#198).** A behaviour key is computed AFTER type checking, from the
lowered IR rather than the source:

```
behaviour(f) = H( identity(f),
                  sorted impl hashes f's IR dispatches to (CallDirect targets
                    from DictMethodCall / SpecializedCall resolution),
                  sorted behaviour keys of every function f's IR calls,
                  native-set version, if f's closure reaches a native )
```

- It is a property of IR, so it is computed where IR is produced (the store
  populator), and stored beside the IR blob — not in `ParsedDecl`.
- Recursion: the IR call graph is grouped by SCC, exactly like identity.
- The native-set version is one hash over the runtime's native registry
  (names + implementation class versions), bumped by any runtime release.
- Nothing consumes it yet. The first consumer is the one that motivates it:
  cached test results (§5.2). Build it with that consumer, not before.

### 5.2 Results that are facts forever

`(closure hash, input hash) → output` for a pure function is true forever. So:

- a test whose closure did not change does not run — in CI, on another
  machine, next month. Test results are a shared cache, like Nix's binary
  cache, but at the granularity of a function.
- a doc example is `(closure hash, input)` with its output cached; it re-runs
  exactly when something it depends on changes, so **docs cannot go stale**.

### 5.3 Facts attached to hashes, including human ones

Tested, sampled, covered, benchmarked — and **reviewed**. Review approval
attaches to the closure hash. After a rebase, an approval whose hash is
unchanged still stands. If only a callee changed, the reviewer is shown the
behaviour diff (§4.4) of the function they approved, not a text diff of a
file they never opened.

### 5.4 Capability diff on a dependency upgrade

Effect rows are in the signature index. Upgrading a dependency is then a query
over two sets of hashes: *which of my entry points can now reach `Process`,
`Http` or `FileSystem` that could not before?* Supply-chain review stops being
"read the changelog" and becomes a diff of reachable effects, computed before
the upgrade runs anything.

### 5.5 Computed versions

Diff the public signatures (relational, so structurally, not as strings):
removed or narrowed means major, added means minor. Elm already enforces
that much. Then run the behaviour diff (§4.4) over sampled and recorded inputs:
a "patch" release whose outputs changed is flagged before publish. The version
number becomes a claim the tool checks.

### 5.6 Behavioural bisect without a checkout

A name's history is a list of hashes, all still in the store. Replay one input
— the one that is wrong now — against each, and report the first hash where
the output changed. `git bisect` rebuilds the world at each step; this only
loads blobs, and it narrows to the *declaration*, not the commit.

### 5.7 "This already exists"

Two hashes that agree on every sampled and recorded input are probably the same
function. Combined with signature search, that answers the question an agent
should ask before writing anything: *is there already a function with this
type that does this?* — with evidence, not a name match.

### 5.8 The program queries its own codebase at compile time

Designed in §9: the `Codebase` effect, answered from the store while compiling
and handled into `Comptime`. One correction to the first sketch of this idea:
"whole-program registration" (routes, plugins) is NOT the main use. Anything a
person chooses is better written as plain data. Queries are for facts only the
compiler knows (§9.6).

### 5.9 Typed code generation at run time — dropped

Decided 2026-10-16: reading and writing the codebase happens at compile time
only. After the build the code is frozen and content-addressed, so a run-time
answer would equal the compile-time one, and a run-time `Compile` effect would
bring the store and the compiler into every app. Code is generated at compile
time by preprocessors and macros (`../frontends/`), and values by `comptime`.

### 5.10 Traces that point at the code that ran

Because old hashes stay in the store, a trace recorded last month still
resolves: its spans point at the source of the hash that was running, not at
whatever the file says today. A production incident is debugged against the
code that produced it.

### 5.11 Ship closures, not packages

A dependency can be a function's closure rather than a package; a binary
contains exactly the closure of `main`; a remote worker receives a hash and
fetches only the blobs it lacks. This is already the direction of
`../distributed-compute/`; the closure hash (§5.1) is what makes the cache
on the far side trustworthy.

---

## 6. What exists today

- DAP engine: breakpoints resolve and suspend (`DebuggerBreakpointTest`);
  end-to-end blocked on GraalVM dap-tool in the wire embedding (#165).
- `StatementTag` instrumentation; no `ExpressionTag`.
- `Log` and `Time` effects in stdlib; no `Span`, `Watch`, `Record`.
- `mez sample`: branch analysis (`SampleBranches`), scripted performers
  (`SampleScripts`), `CoveringSample`.
- Coverage collector (`CoverageNatives`, `EvalResult.branchHits`) — uncommitted,
  blocked on #187.
- Handler chains captured into function values (S-20).

## 7. Suggested order

1. **#187** shared exhaustive IR walker — every probe placement needs it.
2. **F-1 slice** (#133): `Span`, `DeclRef`, `BranchSite`, `Event` as Mezze
   types. Only what observation needs.
3. **`branches` + `reached`** as primitives; rebuild `mez sample`'s coverage on
   them (4.5). First proof the vocabulary works.
4. **`Watch`** (4.1) with limits and per-location disable.
5. **`Record` / `Replay`** (4.2), plus the audit of nondeterminism that isn't
   an effect yet.
6. **Behaviour key** (5.1) — identity plus impl selection and native version;
   every "cached" idea in §4 and §5 waits on it.
7. Everything else builds on 3–6: calls-as-tests, behaviour diff, holes,
   spans, what-if, live attach, the §5 queries.

Codebase queries (§9) have their own task list, §9.9.

## 8. Open questions

- Does observing a private declaration from its own module's tests count as
  "inside the module"?
- Is a tape portable across hashes at all, or only replayable against the
  exact hash it was recorded on? (4.4 needs "same inputs to a different hash";
  4.2 needs "same hash".)
- How does `Record` interact with `Parallel` — record the schedule, or forbid
  recording inside it at first?
- Should `Watch` be able to target an effect *operation* (every `Http.get`),
  not just a function? Probably yes — it is the most useful target and the
  cheapest, since the handler dispatch is already a single point.
- Where do stored observations live: the project store, or a separate store
  that can be wiped without touching code?
- Is the behaviour key stored, or computed on demand? Stored makes every cache
  lookup one read; computed avoids a second hash that can drift.

---

## 9. Codebase queries — design (2026-10-18)

**The idea in one line: Mezze is programmable, authorable and queryable from
Mezze itself.**

| | mechanism | phase |
|---|---|---|
| programmable | `comptime { }` computes a value while compiling | after type checking (lowering) |
| authorable | preprocessors and `name!( … )` write syntax (`../frontends/`) | before type checking |
| queryable | the `Codebase` effect reads the typed store | wherever compile-time code runs |

The core keeps one job: deciding what is well-typed. Libraries add vocabulary;
they never add typing rules ("extensions may reject more, never accept more").

### 9.1 Two kinds of compile-time evaluation

| | folding: `comptime { }` | authoring: preprocessors, macros |
|---|---|---|
| changes | nothing about meaning; a value is computed early | what the program is |
| input → output | closed thunk → `Embeddable` value → IR literal | tokens → `Decl`/`Expr` |
| phase | after type checking | before type checking |
| runs in | `mez build` (CLI), `mez dev`/WASM (runtime); never `check`/LSP | `check`, LSP, `build` — always the compiler host |
| if it never runs | the `eval {}` fallback computes the same value at run time | no fallback |
| consent | `ComptimeEval` handler | listed in `Mezze.toml` |

`Codebase` needs the store, which exists only in the compiler host. So until
the interpreter runs inside `mez` (T6), a query works only where folding happens
in the CLI process: `mez build` on the JVM, and tests. Everywhere else it must
fail loudly. Queries have no run-time fallback.

### 9.2 Effects

```
Codebase.find { … }                   raises   <Codebase>
   │  perform EvalCodebase in …      consumes Codebase, raises <Comptime>
   ▼
<Comptime>
   │  perform ComptimeEval in …       evaluated while compiling
   ▼
plain data
```

```
let routes = {} -> perform ComptimeEval in perform EvalCodebase in Codebase.decls { module = "app::http" }
let routes = {} -> perform ComptimeEvalCodebase in Codebase.decls { module = "app::http" }   # helper
```

- `EvalCodebase` turns each `Codebase` op into `Comptime.codebase { run }` and
  leaves `<Comptime>` outward, so it composes with other compile-time work under
  one `perform ComptimeEval`. The handler cannot call `Comptime.comptime` itself, because the op's
  parameters are run-time values from its point of view (V0802).
- `ComptimeEvalCodebase` is a helper. It handles `Codebase` and installs
  `ComptimeEval` inside its own op bodies. That is legal under V0701, since it is
  a performer for `Codebase` only.
- `FixtureCodebase { … }` answers from given data, for tests.
- **Fold site:** the `perform … in` of a compile-time handler — `ComptimeEvalCodebase`,
  or `ComptimeEval` around `EvalCodebase`. Nothing wraps it in
  `Comptime.comptime`; the `perform` is the compile-time site. It is inside a
  function: a `perform` at the top level is refused (V0502). The whole expression is evaluated while compiling. It must be
  closed (a query that depends on a run-time value is a compile error naming
  that value), and its result must be `Embeddable`.
- Compile time only (§5.9). There is no run-time handler that reads the store.

### 9.3 Referring to things

| how | example | rename-safe | when |
|---|---|---|---|
| qualified name | `"app::model::Point"` | no | text search; stopgap |
| a function value | `Codebase.ref { of = get_user }` | yes — its hash is known at the fold site | first slice |
| a quote | `` Codebase.impls { for = `Point` } `` | yes — resolved where written | once quotes exist |

Every result carries a `DeclRef` (hash + display name), and a `DeclRef` can be
passed to the next query. A specialised (mono) copy maps back to its generic
hash.

### 9.4 API

| group | operations |
|---|---|
| look up | `ref { of }` · `get { ref }` · `resolve { name }` |
| definitions | `definition { of }`: record fields, variant constructors and payloads, newtype and wrapped type, alias target, type parameters · `signature { of }` · `ops { of }`: an ability's or effect's operations, class parameters, requirements, associated types |
| abilities | `impls { of = ability }` · `impls { for = type }` · `impl_for { ability, type }` · `abilities_of { type }`: written, generic or derived, using the checker's own matching and derivation |
| effects | `effects_of { of }` (residual) · `performers { of = effect }` |
| graph | `callers` · `callees` · `references` · `usages` · `reaches { from, to }` with the path · `closure` · `unused` |
| modules | `modules { prefix }` · `exports` · `imports` · `importers` |
| docs | `doc` · `loc` · `source` · `metadata` · module docs |
| search | `search { q = query {} … }`, a builder (below) |
| later | `generated_by` (provenance) |

`Query` is a plain record. Its inherent methods each return a modified copy:

```
Codebase.search { q = query {}
  .kind { is = Function } .public {} .in_module { prefix = "app::http" }
  .performs { effect = `Db` } .returns { type = `Response` }
  .order { by = Name } .limit { n = 100 } }
```

Filters: kind, name / prefix / fuzzy, text (FTS over names and docs), module
prefix, scope (project / deps / std), public, tagged, deprecated, has_doc,
performs, pure, requires, mentions, returns, takes, shape (type-directed, Hoogle
style), unused, order, limit. Results are always in a fixed order, so builds are
reproducible. A `Query` is data, so it is also the cache key.

### 9.5 Prior art

Lean 4 is the closest: metaprograms walk the whole environment, and `exact?`
searches it by type. Template Haskell has `reify` and `reifyInstances`; Scala 3
has `Quotes.reflect`; Zig has `@typeInfo`; Java has annotation processors
(`getElementsAnnotatedWith`). Smalltalk and Lisp images are queryable from the
language, but at run time, untyped and mutable. CodeQL, Glean and Hoogle are
external tools. The new combination here: type-directed search, effects,
abilities and the call graph over a content-addressed store, asked from the
language as an effect, cached and reproducible.

### 9.6 What it is for

**If you could write it down, write it as data.** Routes, flags and config are
choices, so they are lists. Queries are for facts only the compiler knows:

- **derive and codecs:** fields and constructors, and which abilities each field type has;
- **sampler and test generation:** `abilities_of`, effect ops (for mocks), typed bodies;
- **docs:** docs, signatures, usages, source;
- **embedded languages:** a schema type's fields;
- **lints and architecture rules:** the call graph and effects;
- **completeness checks** over chosen data ("every handler returning `Response` is in `routes`").

The main users are library and tool authors. The proof that it is enough: move
sample branch analysis (`SampleBranches.kt`) and one built-in derive into Mezze,
and delete the Kotlin.

### 9.7 Store additions

The abstraction is already complete for what exists: every query capability is
on `MezzeStore`. `SQLiteMezzeStore` adds only file handling and factories. Gaps:

| gap | today | addition |
|---|---|---|
| effect / ability lookups by hash | rows carry `effectHash` / `abilityHash`, but the lookup methods take names | hash-keyed methods; the name-keyed ones go once unused |
| impls for a type | impl → targets only | targets → impls |
| definitions of types, abilities, effects | signature rows only for `let`; the rest only in typed blobs | index them on the type-node tables; `getDefinition` |
| whole project | a build indexes only modules reachable from the entry | index every project module |
| source slice | line range plus a file read | stored slice (generated code has no file) |
| module docs, scope tier | — | stored |
| `getAllBlobHashes` | interface default `emptyList()`; in-memory never overrides it | abstract, implemented everywhere |
| expansions / provenance | — | `putExpansion` / `getExpansion` / `generatedBy` |

### 9.8 Layers

```
std::codebase (Mezze)      Codebase · Query · DeclInfo / Definition / ImplInfo
      │  EvalCodebase / ComptimeEvalCodebase
      ▼
Comptime.query             fold site: a closed `perform … in`
      │  native, through a small host interface (no global state)
      ▼
CodebaseQueries (Kotlin)   over :store plus the checker's impl matching;
      │                    TypeSearch, AbilitySearch, EffectReach, DocPages move here
      ▼
MezzeStore                 SQLite · in-memory · chained
```

### 9.9 Tasks

`[x]` done · `[~]` partly done · `[ ]` not started.

- [x] **T1 Store: hash-keyed lookups.** `findDeclsPerforming` and `findDeclsRequiring`, indexed. The name-keyed searches stay for what a person TYPES (`mez search`).
- [x] **T2 Store: impls for a type.** By receiver hash or by the type's declaration; plus `getAllAbilityImpls`.
- [x] **T3 Store: `getAllBlobHashes`.** Abstract; the in-memory store used to return nothing.
- [x] **T4 Store: definitions.** `DefinitionIndex` on `def_nodes`, written by the same walker as signatures.
- [x] **Source call graph.** Each usage records its enclosing declaration and whether it is a call. `edges` is built from lowered code, where an inlined helper has no callers.
- [x] **T5 Whole project.** `ProjectIndex` indexes every `src/` module on the FIRST question, so a build that asks nothing pays nothing. Declarations carry their origin (`MezzeStore.getModuleTier`). Module docs need a syntax first.
- [x] **T6 Interpreter inside `mez`.** The native image carries `:interpreter` and finds the evaluator as a service. `comptime` now folds in the native binary (it silently did not before), and a build with `comptime` blocks it cannot evaluate fails instead of shipping them unevaluated.
- [x] **T7 `CodebaseQueries`:** the `:codebase` module, over `:store` only.
- [x] **T8 One set of shapes:** `CodebaseShapeContractTest` checks every record and constructor the adapter produces against `std/codebase.mz`.
- [x] **T9** `Comptime.codebase { run }`; `CodebaseHost` in `:ast`; `__native_codebase_*` natives. `mez build`, `mez dev` and `mez exec` fold in the CLI, which holds the store.
- [x] **T10 What runs at build** (`BuildTimeUnits`). A `Comptime` request answered by `ComptimeEval` runs at build; the build evaluates the smallest CLOSED expression around it (no local from outside it, and not a function), under the user's own handlers between it and the answer.
  - `ComptimeEvalCodebase` is `ComptimeEval` around `EvalCodebase` for every operation, so it is the same rule; nothing is wrapped in `Comptime.comptime`, and no handler is inserted.
  - A chain of questions grows the unit outward until it closes; a question in a lambda grows to the call that consumes it; run-time work beside a question stays at run time.
  - `EvalCodebase` with no `ComptimeEval` leaves `<Comptime>` in the function's type — imported performers' added effects are read off their typed bodies, so it reaches `main`, where the entry rule refuses it.
  - The typed tree marks the unit (`TypedBuildTime`) without rewriting it; lowering turns it into a build site.
  - Reading a run-time value is a compile error naming it (V0802). Like any `perform`, a question is written inside a function (V0502).
- [x] **T11 `std::codebase`:** `Codebase`, `EvalCodebase`, `ComptimeEvalCodebase`, `FixtureCodebase`, the `DeclQuery` builder.
- [x] **T12 API:** resolve, info (with origin), decls, modules, signature, definition, source, impls_of, impls_for, effects_of, performing, requiring, performers_of, callers, callees, references, usages, `ref { of = fn }`, `abilities_of` (written, derived, generic).
- [x] **T13 Search:** kind, name, prefix, text, module, public, tagged, deprecated, documented, performs, pure, requires, mentions, shape (type-directed), unused, order, limit.
- [x] **T17 Code as written, with what the compiler made of it** (`std::syntax`).
  - `Codebase.syntax { decl }` / `module_syntax { module }`: the parser's tree — every expression, pattern, type annotation and declaration form, `use` lines and comments — with `facts` on each node: its type, what a name refers to, what a call or an operator reaches (its function, or the impl it dispatches to, by identity), and whether it runs at build. Joined by span; a node the compiler rewrote or never saw (a pipe, `@`, parentheses) says so.
  - Replaces the typed `body` view: one tree, two layers.
  - `Codebase.occurrences { kind, symbol }` and the `using` search filter: an index of every written node by kind and spelling.
  - `std::syntax` walks it in Mezze (`all_exprs`, `children`, `node_info`); a test holds the Mezze and compiler definitions to one answer.
- [ ] **T14 Quotes as references** (with `std::syntax`): building syntax and splicing it back — the preprocessor.
- [ ] **T15 Proofs:** sample branch analysis in Mezze (now possible through `syntax` and its facts); one derive in Mezze; delete the Kotlin.
- [x] **T16 Author-written messages:** `#{missing}` on abilities, `#{unhandled}` on effects, warnings for `#{deprecated}`.
  - Metadata reaches an importing module by identity: `ModuleImportContext.metadataByHash`, filled from each imported module's exports (re-exports included).
  - `#{deprecated}`: W0501 at every use — values, performers in `perform`, effect operations — imported or local; not inside a deprecated declaration. The editor strikes the use through.
  - `#{missing}` replaces the "implement: impl …" advice, also on the operator path. The checker's `when (abilityName)` advice for `Encode`/`Decode`/`Tryable` moved into the stdlib as metadata.
  - `#{unhandled}` is read by effect hash in the entry rule. The hard-coded "`Console` for Log, `SystemTime` for Time, `LoomAsync` for Async" list moved onto those effects.

A question is a `perform`, so it lives inside a function: a top-level `let x = perform … in …` is refused (V0502) like any other. Importing a module must not run its effects, and there is no exemption for compile-time ones.
