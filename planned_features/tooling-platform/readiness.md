# Readiness — what to refine, in order

Not "what is unbuilt" — everything here is unbuilt. This ranks by **how much is
genuinely not understood**, because that is what refinement fixes. Work you can
already describe precisely does not need more design; it needs doing.

Four tiers, and only the first two are worth thinking about.

---

## Tier 1 — genuinely not understood

### 1. What a REPL *means* in this language

The least understood item by a distance, and it is not a plumbing problem.

`let x = …` typed at a prompt is a declaration. Declarations here have content
hashes and live in a store. So: does it get a hash? Does it persist? Is a
session a module? Does defining `x` twice create two declarations or shadow one?
What is in scope — the last saved state of the project, or something else?

**Unison hit exactly this and did not build a classical REPL**, choosing scratch
files instead, *because* content addressing makes "just redefine the var"
incoherent. Clojure's answer does not transfer: it redefines vars in a live
namespace, which a compiled, hashed, monomorphised language cannot do.

Second unknown underneath it: `getMonoSpecialization` exists in the store but I
found no **on-demand** specialisation in `PipelineRunner`. If specialisation is
ahead-of-time, a REPL calling a generic function at a type nothing else used may
have nothing to call.

**Refine by answering:** is the REPL a *scratch module* that is typechecked and
loaded as a unit (Unison-shaped), or an expression evaluator with no definition
capability (much smaller, and possibly enough for sample and inline values)?
The second is a real product and sidesteps all of the above. Decide which one is
being built before designing an `EVAL` message, because the message shape
differs.

### 2. The value representation — specifically, laziness

Everything renders through this, and the core question is unpicked.

A REBL browser navigates *into* a value. Two possible designs, and they are not
compatible:

- **Eager** — serialise the value and send it. Simple, stateless, and impossible
  for large or infinite structures.
- **Handles** — send a shallow view plus a reference; the client asks for
  children on demand. Handles mean **server-side state**, which means lifetime,
  GC, invalidation when the underlying code reloads, and what a handle means
  after the runtime restarts.

Sample's table wants eager. REBL wants handles. The LSP inline hint wants eager.
A doc browser over HTTP wants handles with a cache. Picking one late means
building two.

**Refine by answering:** is there a size threshold where the representation
switches, and if so, is the boundary visible to the client or hidden?

### 3. State coherence — which code does eval run against

Three sources of truth, and nothing reconciles them:

- the **editor buffer** — unsaved, what you are looking at
- the **store** — written with `persistToStore = true` on open/save, `false` on
  `didChange`
- the **runtime** — modules the daemon has `LOAD`ed

Click `[Sample]` mid-edit and you get a clean table for the **last saved
version** — the silent-wrongness shape again. Same for a REPL "in the current
code", REBL browsing a value from a stale definition, inline values beside a
line you just changed.

This is a **decision**, not a discovery, which is why it belongs in Tier 1: it is
unmade, everything depends on it, and it is cheap to settle and expensive to
retrofit.

**Refine by answering:** does eval mean *saved* code (honest, sometimes stale,
trivial to implement) or *buffer* code (requires shipping unsaved content and
compiling it)? Clojure sidesteps this because you explicitly evaluate a form and
therefore know what you sent. A CodeLens hides that choice — so if the answer is
"saved", the UI must say so.

---

## Tier 2 — shape known, one decision unmade

### 4. Multi-client semantics

Two clients attached to one runtime: who owns stdout? Whose `INTERRUPT` is it?
`StatusReply` carries `active_run_id`, implying **one active run**. Values are
immutable but handler state is not, so concurrent evaluators are not obviously
isolated.

**Refine by answering:** is a second client an *observer* (read-only, sees
output, cannot evaluate) or a *peer* (own runs, own output stream)? Observer is
much cheaper and covers the LSP-panel case.

### 5. The AST subset contract

Conceptually clear, genuinely undesigned, and **permanent once published**.
OCaml's `ppxlib` and Babel's parser adapters are two ecosystems paying this tax.

Nobody has determined whether macros and frontends need the *same* subset.
Macros construct expressions; frontends construct whole declarations including
doc comments and metadata. If they differ, that is two contracts, and saying so
early is much cheaper than discovering it later.

### 6. The changeset boundary

What is atomic? What happens on partial failure — all-or-nothing, or apply the
ten that typecheck? How does a declared changeset interact with the file watcher
that is *also* coalescing by timing? Those two mechanisms must not both act.

---

## Tier 3 — understood; needs work, not thought

7. **World/context refactor.** Thread a context through `ModuleTypeChecker` and
   `ModuleResolver` so caches key on `(world, module)`. The shape is fully
   describable today.
8. **Pipeline view.** *Upgraded from Tier 1 — my earlier assessment was too
   pessimistic.* `@GenerateBytecode(enableTagInstrumentation = true)` is set,
   annotated *"LSP / debugger"*. What is missing is granularity: only
   `StatementTag` is emitted, and only under `if (debug)`. There is **no
   `ExpressionTag`**. A chain is one statement with many intermediate
   expressions, so this needs expression-level tag emission, gated so it costs
   nothing in production. Known shape, real work, not a question of feasibility.
9. **Socket transport.** Port `MezzeDapServer`'s ServerSocket/accept/loop/stop.
10. **Signature index.** Normalise, index, unify against the query, rank.

## Tier 4 — just work

Sample stages 1–3, metadata query (JSON1, column already populated), MCP and
HTTP adapters, package provenance, pagination.

---

## Separate categories

**Blocked, not unclear.** Property testing needs comptime, which does not exist
outside `planned_features/macros/`. That is a known block on a large undesigned
thing — assess comptime on its own terms rather than as a sample dependency.

**Unmeasured, cheap to resolve.** Machine-first syntax. Render a real module in
a candidate compact encoding and count tokens. One afternoon, and it either
justifies the frontend machinery or it does not.

---

## If you refine three things

1. **REPL: scratch-module or expression-only.** Decides the `EVAL` message.
2. **Value representation: eager, handles, or a threshold.** Decides every surface.
3. **Eval target: saved or buffer.** Decides whether CodeLens sampling is honest.

All three are decisions rather than research, none needs code to settle, and
every Tier 3 and 4 item is easier once they are made.
