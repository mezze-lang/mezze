# Mezze — Zed extension

Native Zed support for Mezze:

- **Tree-sitter syntax highlighting** — the same `grammar.js` + `highlights.scm`
  used by Neovim/Helix (source of truth: `../tree-sitter-mezze`). Query captures
  are mapped to Zed's convention (`@property`, `@comment.doc`, `@type`, …).
- **The `mez` language server** (`mez lsp`) — diagnostics, hover, completion,
  go-to-definition, code actions, and **semantic tokens** (params vs. locals vs.
  functions, record field names as `property`, concrete type names as `type`) —
  the resolution-based coloring the grammar can't do on its own.

So Zed gets the ideal stack: tree-sitter base + LSP semantic overlay. No
TextMate (that's a VS Code fallback only).

## Layout

```
extension.toml               — manifest (language server + grammar)
Cargo.toml, src/lib.rs       — Rust/WASM shim: launches `mez lsp` over stdio
languages/mezze/
  config.toml                — language config (comments, brackets, suffix .mz)
  highlights.scm             — GENERATED, syntax colors (Zed-mapped captures)
  folds.scm, indents.scm     — GENERATED, folding + auto-indent
```

The three `.scm` files are generated from `../tree-sitter-mezze/queries/` by
`../syntax-generators/gen-queries.py` — edit the source, not these. Zed
extensions have to ship their own query files, so the copy is unavoidable;
maintaining it by hand was not. It had drifted onto a `"!"` token the grammar
does not have, and tree-sitter rejects a query that names a missing node by
refusing the WHOLE file — Zed was highlighting Mezze not at all.

Two checks keep that from recurring, and both are needed — matching the source
would not catch a fault the source also has:

```
tooling/syntax-generators/gen-queries.py --check       # copies match their source
tooling/tree-sitter-mezze/scripts/check-queries.py .   # every .scm still compiles
```

## Install as a DEV extension (local)

1. Install `mez` on your `PATH` (the extension resolves `mez` from the
   worktree, falling back to `mez`). Verify: `mez lsp --help` works.
2. **The grammar commit must be fetchable** — Zed builds the grammar from a git
   REMOTE by commit and cannot read a local-only branch. This is already
   pinned to a pushed commit on `mezze-lang/mezze`, so there is nothing to do
   unless you changed the grammar: then push it and bump
   `[grammars.mezze].commit` to the pushed hash, in that order. Verify with
   `tooling/syntax-generators/check-zed-pin.sh` — a pin that predates a grammar
   change makes the shipped queries name a node the fetched parser lacks, which
   costs the whole query file.
3. In Zed: command palette → `zed: install dev extension` → pick this
   `tooling/zed-extension` folder. Zed compiles `src/lib.rs` to WASM, fetches +
   builds the grammar, and installs.
4. Open a `.mz` file. Tree-sitter colors appear immediately; LSP features
   (diagnostics, hover, completion, code actions, semantic-token refinement)
   come online once `mez lsp` starts.

### If the LSP doesn't start (macOS PATH gotcha)

The extension finds the server via `worktree.which("mez")` (a PATH lookup). On
macOS, a Zed launched from the **Dock/Finder does NOT inherit your shell PATH**,
so `mez` may be found in a terminal but not by that Zed instance. If you get
tree-sitter colors but no diagnostics/hover, do ONE of:

- **Launch Zed from a terminal:** `cd <a mezze project> && zed .` — it inherits
  your PATH. Simplest.
- **Add `~/.mezze/bin` to your login PATH** (e.g. in `~/.zprofile`, or
  `sudo launchctl config user path …`), then relaunch Zed.
- **Point Zed at `mez` explicitly** in settings (`zed: open settings`):
  ```json
  "lsp": { "mezze": { "binary": { "path": "/Users/YOU/.mezze/bin/mez" } } }
  ```
  This takes priority over the PATH lookup. It was documented here long before
  it worked — the shim read only PATH, so the documented escape hatch did
  nothing for the very case it documents. `binary.arguments` is also honoured
  and REPLACES the default `lsp` argument, for pointing at a wrapper script.

Check `zed: open log` for the resolved command / server output if unsure.

## Rebuild after changes

- Changed `src/lib.rs`, `config.toml`, or a query → re-run `zed: install dev
  extension` on this folder.
- Changed the grammar (`../tree-sitter-mezze/grammar.js`) → push the new commit,
  bump `[grammars.mezze].commit`, then reinstall so Zed rebuilds the WASM parser.

## Publishing (later)

For the Zed extension registry, the grammar must live in its own git repo
(Zed fetches grammars by URL + commit). Split `tooling/tree-sitter-mezze` into a
standalone `tree-sitter-mezze` repo, then set `[grammars.mezze]` `repository` +
`commit` to it. The query files here stay in the extension.
