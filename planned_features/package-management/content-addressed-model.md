# The content-addressed distribution model

The canonical model for how Mezze distributes, compiles, caches, and navigates
code. Everything else — `mez sync`, `mez pack`, `deps.sqlite`, the LSP — is a
consequence of the one invariant below. Status: **north-star design**; the current
`mez sync` (see [README](./README.md)) is a working file-based *foundation* of it.

---

## The invariant

> **Everything is a content-addressed definition. The store is a universal memo
> over a deterministic `compile(defHash, format)` function. Distribution merges
> hash-closures. A hash either resolves — use it — or is computed from its
> definition. It is never *mismatched*.**

Failure isn't *handled*; it's **structurally impossible**. There are no versions to
conflict, no compatibility checks to fail, and any compiled form is always
recomputable from the definition.

---

## Core concepts

### 1. The unit is the definition, not the package

A definition (`list::map`, `json::parse`) *is* its content, addressed by a hash
computed over its AST **as written** (Mezze already hashes this way — stable, no
normalization). `list::map` is `@H` forever. "Version 1.2.3" and "package json" are
**human-facing labels that point at a set of hashes** — not identities. Two
"versions" that differ are different hashes (different nodes); two that are
byte-identical are the *same* node, shared.

### 2. A node is `{ source-AST, type interface, compiled IR, edges }`

Under one hash, a node carries everything about a definition:
- **source-AST** — the hashed content (so source is never "missing"; see LSP below),
- **type interface** — its signature (what consumers typecheck against),
- **compiled IR** — memoized per target format (see §4),
- **edges** — the hashes it references (its deps, stdlib, builtins).

### 3. The store is one universal content-addressed memo

`~/.mezze/store/` is a **single global graph** of nodes keyed by hash — the memo
table over the compile function. It's shared by every project on the machine, so a
definition is fetched/compiled **once, ever**. A project's `.mez/` `src.sqlite` /
`stdlib.sqlite` / `deps.sqlite` are **ATTACHed *views*** into this one store —
filtered to what that project can see — not separate worlds.

### 4. Compilation is a pure, memoized function

`ir = compile(defHash, targetFormat)` is deterministic. Asking for a compiled form
is a memo lookup: **hit → use it; miss → compute it from the definition and store
it.** The definition (source-level, hash-stable) is the eternal seed; you can always
rebuild any compiled form, so you never depend on anyone else's compiled bytes being
"correct."

### 5. Every reference is by hash → three outcomes collapse to two

A compiled blob names its dependencies by hash. Using it is a closure lookup:
**every hash resolves → run it; a hash is missing → compute that node.** There is no
third outcome. A "mismatch" would just be a hash that isn't present — and a missing
hash is a *cache miss you fill deterministically*, never an error.

---

## Why it can never fail

- **Conflicts are impossible.** The diamond problem dissolves: two deps referencing
  different content of `X` are different hashes — both present, no clash; same
  content → shared. (Unison's core result.)
- **"Compatibility" is not a question.** You never ask "is this the right version" —
  you ask "do I have these hashes," which is exact.
- **Recovery is always possible.** Worst case is a memo miss → recompute from the
  definition. Nothing is ever unrecoverable or "wrong."

---

## Distribution — get it from anywhere

A **package artifact = a hash-closure**: the set of nodes for the definitions it
exports, plus their transitive edges. Concretely, a packed layout:

```
<pkg>/
  Mezze.toml
  src/**.mz              source (always present — canonical + fallback)
  .mezze-pkg/
    ir-version           the IR/format stamp (a runtime contract number)
    nodes.sqlite         content-addressed nodes: source-AST + interface + compiled IR + edges
```

- **GitHub repo / git ref / source tarball** → source only → `sync` **computes** the
  nodes once (your compiler, your rules) and memoizes them in the store.
- **GitHub Release / web URL** carrying a packed artifact → `sync` **merges** its
  nodes into the store (verify by hash; dedup is automatic — you already share most
  stdlib/common nodes, so you fetch almost nothing).
- Either way you end up with the same nodes in the store, keyed by hash.

### "Ships compiled or not" is memo-hit vs memo-miss

Not a check to pass — just: does the artifact carry `compile(defHash, myFormat)`?
- **Coarse guard:** `ir-version` in the runtime's readable window → the bytes are
  deserializable at all; else ignore the compiled blobs.
- **Exact guard (the real one):** the compiled node's **edge hashes all resolve in
  your store**. A node compiled against `std::string::concat@abc` only "fits" if your
  stdlib has `concat@abc`; if you upgraded and it's now `@def`, the edge dangles →
  **recompute from source** (which re-lowers against `@def`). No fuzzy version
  matching — a set of hash lookups.

---

## Source & the LSP — cmd-click still works (better)

Because **source-AST is part of every node** (it's what the hash is over), source
ships with the definitions and can't go missing.

- **Go-to-definition:** symbol → its definition hash → open the stored source for
  that hash (read-only, pinned). Identical to how stdlib go-to-def already works.
- **Provably correct:** the source you read is byte-for-byte the content that
  produced the linked compiled def — you can never be in the npm situation of the
  editor showing a different version than what's resolved.
- **Immutable snapshot:** read-only, keyed by hash — no accidental drift.
- **Never breaks:** even a source-less artifact can *reconstruct* faithful source
  from the AST via the pretty-printer (the Unison move).
- **Hover / find-references:** the interface lives in the node (exact signatures);
  references are hash edges (more precise than name search).

---

## Supply-chain safety (falls out for free)

- **Hash-pinned end to end** — a node is its content; a tampered upstream is a
  *different hash*, so it simply isn't the node you asked for.
- **No install-time code** — `sync` merges/compiles nodes; it never executes package
  code.
- **No registry to hijack** — nodes come by hash from anywhere; identity is the hash,
  not a name on a server.
- **Effect-typed capabilities** — a node that does IO says so in its interface; a
  "malicious update" is a new hash with a changed effect signature, visible in review.
- **The lockfile is the resolved hash-closure** — reproducible by construction.

---

## Human labels over hashes

People pick deps by name + a version constraint in `Mezze.toml`; that resolves to a
concrete **set of hashes** recorded in `Mezze.lock`. Names/semver are ergonomics for
*choosing* which hashes; the machine only ever deals in hashes. This is what lets
"upgrade a dep" mean "point the label at a new hash-set" with zero conflict risk.

---

## What exists today vs. the gap

**Already true (the hard prerequisites):**
- Definitions are content-hashed, stably (`#316–#319`).
- A content-addressed SQLite store with `src`/`stdlib`/`deps` ATTACHed as one
  hash-keyed codebase; the stdlib is *precompiled* into `stdlib.sqlite` (the exact
  memo pattern, `StdlibPrecompiler`).
- `mez sync` resolves the dependency graph (local + remote, transitive), verifies
  hashes, and records the full graph in `Mezze.lock`.

**The gap to the model:**
1. **Global universal store** — lift the memo from per-project `.mez/` to a shared
   `~/.mezze/store` keyed by hash; per-project dbs become views/merges of it.
2. **Node = `{source-AST, interface, compiled IR, edges}`** — persist the compiled IR
   + interface alongside source, keyed by `(defHash, ir-format)`.
3. **`mez pack`** — emit a package as its hash-closure (`.mezze-pkg/nodes.sqlite` +
   `ir-version` + source).
4. **`sync` = merge-or-compute** — import an artifact's nodes when present + fitting,
   else compile from source once; either way memoize in the global store and expose
   via the project's `deps.sqlite` view.
5. **`ir-version` contract** — declare the runtime's readable window so the coarse
   guard has a number to compare.

**Build order:** (1) global store + node shape → (2) `sync` compiles-and-memoizes
into it, `deps.sqlite` becomes a view → (3) `mez pack` emits artifacts → (4) `sync`
imports packed artifacts (merge fast-path) → (5) cross-machine sharing / prebuilt
caches.

Each step is a strict superset of the working file-based `sync` — nothing already
shipped is thrown away; it becomes the source-compute fallback of the memo.
