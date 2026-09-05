# Package management

How a Mezze project declares, resolves, and uses dependencies. Status: **in
progress** (branch `feat/package-management`). Local-first — GitHub releases are a
later convenience, not the focus.

## Goal

- **Registry-less** — a dependency is content, identified by hash. No central index
  to host, no tokens to steal, no name-squatting.
- **Local-first** — you can develop across multiple local packages with **no
  network** (`path` deps), and everything works offline once cached.
- **Content-addressed, deduplicated** — deps live once in a **system-wide store**,
  referenced by every project that needs them.
- **Supply-chain-safe by construction** — hash-pinned, no install-time code, deps'
  capabilities visible in their effect types.

## The model

### Manifest — `Mezze.toml [dependencies]`

A dependency value is one of:

```toml
[dependencies]
# local path (P1 — the local-first core)
utils = { path = "../utils" }

# URL pinned by content hash (P2 — no registry)
json  = "https://github.com/mezze-lang/json/releases/download/v1.2.3/json.tar.gz#sha256:abc…"

# (later) git
http  = { git = "https://github.com/mezze-lang/http", rev = "…" }
```

Today only the `url#sha256` string form exists; `{ path = … }` is the first thing
we add.

### Two-tier store

```
~/.mezze/store/<hash>/          # SYSTEM-WIDE content cache — one copy per hash, ever
  src/…                         #   the package's .mz source (read-only, for cmd-click)
  <compiled defs, by hash>

<project>/.mez/
  src.sqlite                    # your code            (ATTACH main, RW)
  stdlib.sqlite                 # bundled stdlib       (ATTACH stdlib, RO)
  deps.sqlite                   # resolved deps        (ATTACH deps, RO)  ← hydrated from the global store
Mezze.lock                      # name → resolved content hash (full transitive graph)
```

The three `.sqlite` files ATTACH into **one connection**, so the compiler sees a
single hash-keyed codebase (`main` + `stdlib` + `deps`). Package management is just:
**fill `deps.sqlite` with the resolved dependency definitions.**

### `mez sync` flow

1. Parse `Mezze.toml` deps (path / url#sha256).
2. Resolve each to a **content hash**: `path` → hash the source tree; `url` → the
   pinned sha256.
3. Ensure it's in `~/.mezze/store/<hash>/` — copy (path) or fetch+verify (url); skip
   if present.
4. **Compile** the package source into `deps.sqlite` under its module namespace (the
   piece #108 is missing — reuse the stdlib-precompile path that fills
   `stdlib.sqlite`).
5. Write `Mezze.lock` (name → hash, whole transitive graph).

`mez check` / `dev` / `build` then resolve `use <dep>::…` across the ATTACHed
`deps` db like any other module — no link step.

### Source code & go-to-definition

The global store keeps each package's **`.mz` source, read-only**. `cmd-click` /
LSP go-to-def resolves a symbol → its package hash → opens the pinned source
snapshot (same pattern the toolchain already uses for `~/.mezze/stdlib`).

## Supply-chain safety (Shai-Hulud class)

- **Exact hash pins, no floating semver** → a compromised upstream can't push a
  malicious "patch"; sha256 mismatch is refused.
- **No install scripts / no code at fetch time** → `sync` only fetches → verifies →
  compiles-to-store; it never executes package code.
- **No registry** → nothing central to hijack or typosquat.
- **Lockfile pins the whole transitive graph** → any change is a visible diff.
- **Effects are typed** → a dep can't hide network/IO; a newly-malicious version
  changes its effect signature → changes its hash → surfaces in review.

## Vendoring

Not the default — hash-lock + global cache already give reproducibility, tamper
detection, and offline. Keep `mez vendor` as an opt-in that materializes deps into
the project (air-gapped / audit / URL-death insurance); vendored bytes are provably
identical to the lock.

## Phasing (local-first)

- **P1 — local `path` deps, end to end. ✅ DONE.** `[path-dependencies]` in
  `Mezze.toml` (`name = "../path"`) → `mez sync` symlinks each into
  `.deps/mezze/<name>-<version>/` (live edits — no copy), where the **file-based**
  module resolver already looks, so `use <dep>::…` typechecks + runs with **zero
  compiler changes**. Key realization: module resolution is file-based
  (`ModuleResolver.resolveModuleFile`), so `deps.sqlite` is only a parse/typecheck
  *cache* — populating it is a P2/P3 optimization, **not** a prerequisite.
  - **Transitive deps ✅** — one `mez sync` at the top walks the whole graph.
    **Resolution roots at each package's own dir** (not the top project), so `sync`
    links each dep into *its declarer's* `.deps/mezze/` recursively
    (`app`→`utils`→`core` links `utils` into `app/.deps` and `core` into
    `utils/.deps`). Diamonds dedup, cycles terminate. This is a *nested* deps model
    (each package sees its own `.deps`), not npm-style flat hoisting.
- **P2 — url#sha256 fetch → global store.** Wire the existing downloader into the
  `~/.mezze/store/<hash>` cache; compile from there into `deps.sqlite`.
- **P3 — transitive deps + lockfile graph.** Resolve deps-of-deps; lock the full
  graph; detect/handle hash conflicts (content-addressing makes most of these
  non-conflicts).
- **P4 — polish.** `mez add`, global dedup + `mez toolchain/store gc`, `mez vendor`.
- **Later (not focus).** GitHub-release tarballs + `versions.json` as one convenient
  source of `url#sha256` deps.

See also: `planned_features/distribution/README.md` (toolchain install; GraalVM is a
user-installed core requirement).
