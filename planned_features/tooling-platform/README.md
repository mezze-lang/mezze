# The tooling platform — one core, many surfaces

> **Read `thesis.md` first.** It argues why these are two primitives rather than
> six features, and what makes that unusually strong in Mezze specifically.
> This document is the mechanics: what exists, what is missing, in what order.

`mez sample`, a REPL, a REBL-style value browser, LSP inline values, and a doc
browser that an LLM can search are not five projects. They are five surfaces
over **three shared pieces**, and building them in the wrong order means
building the shared pieces three times.

```
        sample     REPL     REBL     LSP panel     doc browser / LLM search
           \         \       |         /                    /
            \         \      |        /                    /
   ──────────┴─────────┴─────┴───────┴────────────────────┴──────────
     A. EVALUATION            B. VALUE SHAPE          C. QUERY INDEX
     wire protocol,           one representation      SQLite, searchable
     many transports          + renderer              by more than name
```

Everything below is verified against the tree, not assumed. Where something
does not exist, it says so.

---

## A. Evaluation — the wire protocol, exposed

### What exists

`wire-protocol/` is a real protocol, not an internal convention:

- versioned on the wire — `PROTOCOL_VERSION_MAJOR/MINOR` (1.0) in HELLO/WELCOME
- capability negotiation — `core`, `interrupt`, `stdin`, `stderr_frames`,
  `password`, `argv`, `cwd`, `env`, `structured_errors`, `trace_jit`
- one-byte framed message types in reserved ranges, with a stability rule:
  published tag bytes MUST NOT be reused, deprecated ones stay reserved
- its own Gradle module, so anything can depend on it without the interpreter

And it already anticipated this work. From `WireProtocol.kt`:

> Ranges `0x20–0x2F` and `0x70–0x7F` are intentionally unused — reserved for
> if/when **multi-client attach** or a **REPL evaluation channel** ever gets a
> real design driver.

Sample, the LSP panel and a REPL are three drivers arriving together.

### What blocks it

**1. Transport is stdio.** `RuntimeServer.main` reads `System.in` and writes
`FileDescriptor.out`. The runtime is therefore a *child* of whoever spawns it —
a tool can spawn its own, but cannot attach to a running `mez dev`. One pipe
pair means one client by construction. This is also why there is no auth: the
OS provides it for parent/child.

The precedent for fixing it is already in the same package.
`MezzeDapServer.serve()` does `ServerSocket(port)`, accepts one client, runs a
message loop, and has a clean `stop()`. Socket transport is a port of a
known-good local pattern, not new ground.

**2. There is no EVAL message.** `LOAD`/`EVICT` are module-granularity — "here
is a module, run it" — with stdio as the output channel. "Evaluate this
expression in that module's scope, give me back a value" is a different
interaction: request/response over values, not a program with a console.

**3. Multi-client is semantics, not plumbing.** Two clients attached: who owns
stdout? If the LSP evaluates while your program runs, whose INTERRUPT is it?
The protocol today assumes one client owns the session, which is exactly why
`MezzeDapServer` accepts exactly one. Decide this before exposing, not after.

### Security posture — decide it up front

A socket that evaluates arbitrary code is remote code execution. nREPL has a
real history of CVEs from unauthenticated servers on public interfaces, and
that is the closest prior art to what this becomes.

- **loopback only** by default, never `0.0.0.0`
- **token in the handshake** — HELLO already carries capabilities; auth belongs
  there
- **opt-in flag** on `mez dev`, never on by default
- a printed URL/token like Jupyter, not a well-known port

### Transports

The frame codec is transport-agnostic; only `RuntimeServer`'s IO setup is not.
Ordered by what each unlocks:

| transport | unlocks | cost |
|---|---|---|
| stdio (today) | sample spawning its own runtime | none |
| socket | LSP attach, REPL, multi-client | moderate — DAP pattern exists |
| HTTP/SSE | web doc browser, LLM clients | wrapper over the same frames |

HTTP matters for the doc browser and for an LLM client: neither can speak a
binary framed protocol conveniently, and both want request/response rather than
a session. Treat it as an adapter over the same message set, not a second
protocol.

### Publish later, not now

"Fully expose" conflates two things. Exposing it **from the dev server** is
right. Committing to it as a **public contract** should wait until sample, the
REPL and the LSP have exercised it — publishing freezes a design none of its
consumers have used yet. The reserved ranges mean `EVAL` can be added later
without breaking anything, so there is no cost to waiting.

---

## B. Value shape — the piece everyone forgets

The hardest part of an EVAL channel is not transport. It is deciding **what
comes back**. A `MezzeValue` crossing a wire needs a representation, and that
same representation is:

- a row in sample's table
- what a REPL prints
- what REBL lets you *browse* rather than print
- an LSP inline hint next to a `|>` chain
- JSON for a web browser or an LLM

Get it wrong and there are five renderers. Get it right and there is one, with
five presentations. **Nothing exists for this today** — it is the genuinely new
component, more than `InputGenerator` is.

REBL is the demanding consumer and should drive the design: Read-Eval-**Browse**-
Loop, where a result is navigable rather than printed, because printing a large
nested value is useless and browsing it is how you understand it.

Mezze suits this better than Clojure did: **named fields** mean a browser can
label every column without guessing, **closed variants** mean it can show which
constructor and what the alternatives were, **immutability** means a browsed
value cannot change under you, and **effect annotations** mean it knows whether
re-evaluating a node is safe.

### Metadata as the hint layer

Rendering hints — this is milliseconds, this is a secret, render this as a
table — want to come from `#{ }` metadata. See `metadata-gaps.md`: metadata is
declaration-level and stops at parse time, so a runtime value carries none.

The way round it is **provenance, not attachment**: the tool always knows *what
it evaluated*, so it can look up that declaration's metadata and render
accordingly. That works today and needs no language change. Field-level hints
do need one.

---

## C. Query index — searchable by more than name

### What exists (a lot)

`MezzeStore` is already a code-intelligence database:

- names — `searchDeclsByName`, `searchExportsByName`, `getModuleExports`
- structure — `getDeclsForModule`, `getDeclsByKind`, `getAllModules`
- **graph** — `getEdgesFrom`, `getEdgesTo`, `getTransitiveClosure`
- **usages** — `getUsages` (call sites), `getImportersOf`
- **abilities** — `getAbilityImpls`, `getAbilityImplForType`
- **effects** — `getEffectPerformers`
- docs + metadata per decl — `DeclRow.doc`, `DeclRow.metadata`

### What is missing

**1. No type signature is stored.** `NameRow` is `(module, name, hash)`. The
`decls` table has no type column. Types exist only inside the CBOR typed-module
blob, reachable via `getTypedModule` — i.e. by deserializing a whole module.

So **signature search is impossible today**, and that is the centre of the doc
browser: find by exact type, by partial type, by "returns `Option Int`", by
"takes a `[a]`". Hoogle-style search needs a **signature index** — a normalized,
searchable rendering of each declaration's type, written at the same time as
`putDecl`. This is the single highest-value addition in this document.

**2. Effects and abilities are reachable only by hash.**
`getEffectPerformers(effectHash)` answers "who performs this effect" but there
is no "find every function whose row includes `FileSystem`". For search, the
effect row wants to be indexed alongside the signature.

**3. Metadata is not a search key.** All SELECTs key on path/kind/hash/name.
`sqlite-jdbc 3.51.2` ships JSON1, so `json_extract(metadata, '$.tags')` works
with no schema change — the column is already there and already populated. See
`metadata-gaps.md`.

### Why this substrate serves the LLM case

An LLM asking "what converts a `[Str]` to a `Map`?" wants signature search;
"what can throw?" wants effect search; "what is deprecated?" wants metadata
search. All three are the same index, and all three are also what a human doc
browser needs. Build one, get both — and then "now generate samples for that
function" is just handing the result to surface A.

---

## Order of work

The dependencies run C → B → A, even though A is the most visible.

1. **Signature + effect index** (C). Self-contained, no protocol involved,
   immediately useful to LSP search and a doc browser. Unblocks everything that
   "finds" things.
2. **Metadata as a query key** (C). Small — JSON1, column already populated.
3. **Value representation** (B). Design against REBL's needs, since it is the
   most demanding consumer. Sample's table is then a presentation of it.
4. **Socket transport + auth** (A). Port the `MezzeDapServer` pattern; decide
   multi-client ownership semantics.
5. **EVAL message** (A). Needs 3 to be settled first — this is the reason to do
   B before A.
6. **Surfaces** — sample, REPL, REBL, LSP panel, doc browser — each of which is
   now small, because the hard parts are behind them.

The temptation is to start at 6 with sample, since it is the most concrete.
Doing so builds a private input generator, a private renderer and a private
execution path, and then all three get rewritten. Stage 1 of
`../correctness/implementation-plan.md` (refuse correctly) is safe to do at any
time, because it is pure typechecker work and touches none of this.
