# The Mezze project model (where `.mez/` lives, and why)

One rule, the same as git and cargo: **the project is the nearest `Mezze.toml`
walking up from where you are.** Everything else follows from that.

## Layout

```
myapp/
  Mezze.toml          ← defines the project; its directory is the project root
  src/…               ← your code
  .mez/               ← per-project cache (store, parsed/typed/IR). Auto-created.
  .deps/mezze/…       ← resolved dependencies (symlinks / content-store links)
  Mezze.lock          ← the resolved dependency graph (commit this)
  .build/             ← build outputs (mez build)
```

`.gitignore` (written by `mez init`) excludes `.mez/`, `.deps/`, and `.build/` —
they are all rebuildable from `Mezze.toml` + `Mezze.lock` + source.

## Where does `.mez/` get created?

**Always at the project root — never in whatever subdirectory you ran the command
from.** `mez check` / `build` / `dev` / `sync` walk up from your current directory
to the nearest `Mezze.toml` and operate on that root. So:

```
cd myapp/src/deep/nested
mez check          # works — finds myapp/Mezze.toml, uses myapp/.mez/
```

produces exactly the same result as running it from `myapp/`. There is one `.mez/`
per project, at the root.

Override the cache location with `MEZZE_CACHE_DIR` (redirects the whole `.mez/`),
or the home/toolchain root with `MEZZE_HOME`.

## Nested `Mezze.toml`

The **nearest** one wins. A subdirectory with its own `Mezze.toml` is its own
independent project; walk-up stops there. `mez init` warns when you create a
project inside another so it's never a surprise:

```
⚠️  'inner' is nested inside the project at /…/myapp
    It is an independent project — the nearest Mezze.toml wins …
```

There is no implicit multi-package "workspace" — each `Mezze.toml` is one project.
Link one to another explicitly with a `[path-dependencies]` entry.

## No `Mezze.toml` at all

- `mez check <file.mz>` / `mez exec <file.mz>` — **single-file mode**: runs the
  file directly, no project needed. If the file happens to sit inside a project,
  it reuses that project's `.mez/`; otherwise the file's own directory is used
  (no project cache is forced into an unrelated tree).
- `mez check` / `build` / `dev` with no project — a clear error:
  `No Mezze.toml in <dir> or any parent. Run 'mez init' …`.

## Relation to the toolchain

Orthogonal. `Mezze.toml`'s optional `[toolchain] channel` pins *which* `mez`
version builds the project (resolved by the `~/.mezze/bin/mez` proxy); the project
model above decides *where* that `mez` reads and writes. A compiler-version change
invalidates `.mez/` automatically (the cache is stamped with the compiler
identity), so switching toolchains never reuses another compiler's cached IR.
