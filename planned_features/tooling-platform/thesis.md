# Two primitives, then get out of the way

Read this before the other documents here. They describe mechanics; this
describes what the mechanics are for.

## The claim

`mez sample`, a REPL, a REBL browser, LSP inline values, a doc site, and an LLM
that can search and evaluate Mezze are **not six features**. They are six
compositions of **two primitives**:

```
   READ the codebase        EVALUATE in the codebase
   (content-addressed       (wire protocol,
    store, queryable)        handlers explicit)
```

| surface | read | eval |
|---|---|---|
| doc browser | ● | ○ samples on demand |
| LLM search / MCP | ● | ○ "now sample it" |
| LSP hover, CodeLens, search | ● | |
| `mez sample` | ● which handlers are needed | ● run the cases |
| REPL | ● resolve names in scope | ● |
| REBL browser | ● metadata for render hints | ● |
| search by behaviour | ● narrow candidates | ● confirm them |

Nothing in that table needs a primitive the other rows do not. **That is the
whole argument for building the primitives first.** Build any row on its own
and it grows a private reader and a private evaluator, and the next row rebuilds
both.

## Why it becomes a platform rather than two APIs

The point is not that *we* build six things more cheaply. It is that once both
primitives are reachable over a protocol, **people who are not us build things
we did not think of**, in languages that are not Kotlin.

The prior art is unambiguous:

- **nREPL** — one evaluation protocol produced CIDER, Calva and Cursive. None
  was built by the protocol's author.
- **LSP** — one protocol produced support in every editor, without any editor
  team learning any compiler's internals.

Both succeeded for the same reason: a stable protocol is a smaller promise than
a library, and a much larger invitation.

## What makes it unusually strong *here*

Two properties Mezze already has, which most languages cannot offer a tool
author:

**1. The codebase is a database, not a pile of files.** `MezzeStore`'s design
notes say it plainly: *"Blobs are dumb storage keyed by content hash. Everything
else is an index"* and **"Names are metadata, not identity. A hash can have
zero, one, or many names."** A tool author does not parse Mezze to understand a
codebase — they query it. Identity survives renames, moves and reformatting,
which is what makes permalinks, cross-version search and cached-sample
invalidation all fall out for free rather than needing design.

**2. The types say what is safe to run.** Effects are in the signature, so a
tool can know *before executing* whether a function needs handlers. That is what
separates automated evaluation from recklessness: sample refuses rather than
returning a `MezzeSuspend`; search-by-behaviour runs only candidates with an
empty residual row; a doc site knows which functions it may sample unattended.
**No other language's tooling can do this**, and it is the reason
search-by-behaviour is possible here and nowhere else.

Content addressing plus effect types is the actual moat. Everything else in
these documents is plumbing.

## The minimal core

Four pieces. Nothing else is fundamental.

### 1. One daemon serving both primitives

Today `MezzeStore` is a **Kotlin interface** — the universal internal access
point, and JVM-only. A protocol is what makes it language-agnostic, and that is
the entire difference between "we have good internals" and "anyone can build
tooling."

Both primitives should be one process, because the interesting surfaces need
both at once (a doc page that can sample the function it is describing). Serving
them from two places makes every such surface a distributed-systems problem for
no benefit.

### 2. Two capabilities, separately grantable

Read and eval are not the same risk. Read is a query over a local database.
**Eval is arbitrary code execution.** A doc browser, a search UI and an LLM
index want read-only; a REPL wants both. They must be separate grants, with
read-only the default — not one blanket "connected" state.

This also means a read-only surface can be exposed to things you would never
hand an evaluator.

### 3. One value representation

The lingua franca of both primitives, and the piece most likely to be built
five times: a sample table row, a REPL print, a REBL node, an LSP inline hint,
and JSON for the web are one representation with five presentations. Design it
against REBL, the most demanding consumer. See `README.md` §B.

### 4. Discovery and versioning

Small, and skipped by everyone until it hurts:

- **Discovery** — a tool must find the running daemon without being told.
  A port file (`.mez/daemon.port`), like nREPL's `.nrepl-port` or Jupyter's
  connection file.
- **Versioning** — the wire protocol already has this discipline
  (`PROTOCOL_VERSION_MAJOR/MINOR`, capability negotiation, never-reuse on tag
  bytes). The store has `SCHEMA_VERSION = 10`, but that is an internal migration
  counter, **not a public contract**. Exposing read means the query surface
  needs its own versioned, negotiated contract — do not let it inherit the
  schema version by accident.

## Four verbs — two in, two out

`../frontends/` completes the set. The write path has two stages and so does the
read path:

| verb | mechanism | what it serves |
|---|---|---|
| **author** | anything → AST | macros, DSL templates, alternative syntax, LLM-efficient encoding, syntax versions |
| **apply** | a *declared* changeset lands coherently | agentic multi-file writes, batching, store consistency, one reload not twelve |
| **read** | content-addressed store, queryable | doc browser, LLM search, LSP, "which handlers does this need" |
| **evaluate** | wire protocol, handlers explicit | REPL, REBL, sampler, inline values, search-by-behaviour |

Plus one shared contract that is not a verb — **the value representation** —
which every surface renders and which is the piece most likely to be built five
times because it feels like a detail rather than a primitive.

### Why `apply`, and not "speculate"

An earlier draft called the agentic primitive *speculation* — check a
hypothetical codebase, keep or discard. That is a real capability but it is the
wrong centre of gravity. The thing an agent does constantly is not hypothesise;
it is **write a lot, fast, across many files**, and the system has to absorb that
without thrashing.

Batching already exists, and it is tuned for a human:

- `WireRunner` coalesces by draining whatever is already in the watch queue,
  then calls `invalidateChangedModules` once for the set.
- The LSP debounces analysis at `DEBOUNCE_MS = 250` (configurable 0–5000).

Both **infer the batch boundary from timing**. That is a proxy for intent, and
it is the right proxy for a typist, whose intent is unknowable. It is the wrong
one for an agent, which *knows* exactly where its changeset begins and ends and
has no way to say so. Twelve files written over two seconds become several
coalescing rounds — several store writes, several evictions, several reloads,
several rounds of diagnostics for one logical change.

So the primitive is: **an agent declares a changeset, the system applies it as a
unit** — parse and typecheck once, write the store once, reload the runtime
once, return one complete set of diagnostics.

Speculation then falls out as a *mode* of apply — apply without committing —
rather than needing to be its own concept. Which is the better factoring anyway:
dry-run is a flag on a write, not a separate subsystem.

`author` is the weakest name of the four and may not survive. What it means is
unambiguous — *anything that makes AST is a source of Mezze code* — and the
mechanism matters more than the label.

## Where the reduction stops

Four verbs is a satisfying picture, and satisfying pictures are worth
distrusting. Three honest limits:

**The verbs are interfaces, not one engine.** The temptation in a design like
this is to build a single component that does all four. Resist it: the value of
the framing is that surfaces compose *against* four contracts, not that one
implementation serves them.

**Interactive editing genuinely does not reduce to batch checking.** An LSP
serving a typist wants lowest-latency partial answers on one file; an agent
wants a complete verdict on twelve. Those are different objectives, and the
prior art is a warning — rust-analyzer and rustc stayed separate implementations
for real reasons, not from lack of ambition. Speculation should serve the agent
case without being asked to also become the editor's incremental path.

**Three of the four have a blocking dependency**, and naming them is more useful
than the diagram:

- *author* → a versioned AST subset that frontends and macros both target.
  OCaml's `ppxlib` and Babel's parser adapters are two ecosystems paying this
  tax already.
- *apply* → a changeset boundary an agent can declare, and the world/context
  refactor underneath it: `ModuleTypeChecker.globalCache` and `typeCheckingStack`
  are keyed by module path and assume one world, which blocks both concurrent
  apply and dry-run apply.
- *evaluate* → socket transport, an `EVAL` message, and the value contract.
- *read* → a signature index; the store holds no searchable type today.

Only *read*'s dependency is small. That ordering is the plan.

## A third primitive, once agents write the code

Read and evaluate are the right two for a human at a keyboard. An agent writes
hundreds of lines across many files at once, speculatively, and may discard the
lot — which needs a third: **speculate**. Check a codebase that does not exist
yet, get every diagnostic, keep or discard.

Content addressing should make that nearly free. Two process-global caches
(`ModuleTypeChecker.globalCache`, `typeCheckingStack`) make it impossible today,
because both are keyed by module path and assume one world. See `agentic.md` —
that refactor is the least glamorous item in these documents and probably the
highest-leverage one.

## The one discipline that matters

**Do not publish the contract until the internal consumers have shaped it.**

Expose both primitives from the daemon, build sample, the REPL and the LSP panel
against them, and let those three deform the design while deformation is still
free. Publish after. The wire protocol's reserved tag ranges mean waiting costs
nothing, and a contract frozen before its first real consumer is a contract
frozen wrong.

The failure mode to avoid is the opposite of the usual one: not "we never
exposed it", but "we exposed it, three tools depend on it, and it is the shape
we guessed in week one."
