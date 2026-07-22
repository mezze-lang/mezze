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
  highlights.scm             — syntax colors (Zed-mapped captures)
  folds.scm, indents.scm     — folding + auto-indent
```

## Install as a DEV extension (local)

1. Install `mez` on your `PATH` (the extension resolves `mez` from the
   worktree, falling back to `mez`). Verify: `mez lsp --help` works.
2. **Make the grammar commit fetchable.** Zed builds the grammar from a git
   REMOTE by commit — it can't read your local-only branch. So push the branch
   that contains `tooling/tree-sitter-mezze`, then set `[grammars.mezze].commit`
   in `extension.toml` to that pushed hash (and `repository` to your fork if
   not `shriyansbhatnagar/funk`).
3. In Zed: command palette → `zed: install dev extension` → pick this
   `tooling/zed-extension` folder. Zed compiles `src/lib.rs` to WASM, fetches +
   builds the grammar, and installs.
4. Open a `.mz` file. Tree-sitter colors appear immediately; LSP features
   (diagnostics, hover, completion, code actions, semantic-token refinement)
   come online once `mez lsp` starts.

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
