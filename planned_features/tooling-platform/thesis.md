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

## The fourth: produce

`../frontends/` completes the set, and completes a symmetry. Read, evaluate and
speculate are all ways of *consuming* a codebase. Custom parsers are the
**input** side: anything that produces Mezze AST is a valid source of Mezze code.

Four verbs, and every tool in these documents is a composition of them:

| verb | mechanism | what it serves |
|---|---|---|
| **produce** | anything → AST | macros, DSL templates, alternative syntax, LLM-efficient encoding, syntax versions |
| **read** | content-addressed store, queryable | doc browser, LLM search, LSP, "which handlers does this need" |
| **evaluate** | wire protocol, handlers explicit | REPL, REBL, sampler, inline values, search-by-behaviour |
| **speculate** | explicit checker world | agentic changesets, concurrency, variant exploration |

Plus one shared contract that is not a verb — **the value representation** — which
every surface renders and which is the piece most likely to be built five times
because it feels like a detail rather than a primitive.

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

- *produce* → a versioned AST subset that frontends and macros both target.
  OCaml's `ppxlib` and Babel's parser adapters are two ecosystems paying this
  tax already.
- *speculate* → the world/context refactor. `ModuleTypeChecker.globalCache` and
  `typeCheckingStack` are keyed by module path and assume one world.
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
