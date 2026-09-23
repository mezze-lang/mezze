# Mezze editor tooling

Everything that makes Mezze readable and navigable in an editor: one grammar,
one canonical set of tree-sitter queries, and the editor integrations generated
from them.

```
tooling/
├── check.sh                  # every gate below, one pass/fail
├── install-dev.sh            # rebuild `mez` + install the VS Code extension
├── tree-sitter-mezze/        # THE grammar. Hand-written grammar.js + scanner.c
│   ├── grammar.js
│   ├── src/scanner.c         # external scanner: INDENT/DEDENT, unterminated forms
│   ├── queries/*.scm         # CANONICAL queries — edit these
│   └── scripts/              # corpus, parity, containment, query validation
├── syntax-generators/        # canonical queries -> each editor's copy
│   ├── gen-queries.py        # + --check gate
│   └── check-zed-pin.sh      # shipped queries vs the grammar Zed will fetch
├── nvim-mezze/               # Neovim plugin (queries GENERATED)
├── zed-extension/            # Zed extension  (queries GENERATED)
└── vscode-extension/         # VS Code: TextMate grammar + LSP client + debugger
```

## How the pieces relate

`tree-sitter-mezze/queries/` is the single source of truth. Editors cannot point
at it — Zed and Neovim both have to *ship* their query files — so the copies are
structural, and `gen-queries.py` generates them:

| consumer | captures        | files                                             |
|----------|-----------------|---------------------------------------------------|
| `nvim`   | identity        | highlights, indents, folds, **locals, textobjects** |
| `zed`    | 5 renames       | highlights, indents, folds                        |

The canonical queries are written in **nvim-treesitter's** dialect
(`@variable.member`, `@type.interface`, `@number.float`), so Neovim's copy is an
identity copy and only Zed needs a rename table. Neovim consequently gets two
files Zed has no concept of: `locals.scm` (same-name highlighting, scope-aware
go-to-def) and `textobjects.scm` (`daf`/`vif` motions).

VS Code is the exception: it uses a hand-maintained **TextMate** grammar, not
tree-sitter, refined at runtime by LSP semantic tokens. Those tokens vanish the
moment a file stops parsing — which is most of the time while you type — so the
TextMate grammar carries real rules rather than deferring everything.

The **website** has its own regex highlighter (`website/src/lib/highlight.ts`)
and no LSP at all. That makes three independent highlighters; `check.sh` holds
them to one keyword list.

## Gates

```sh
tooling/check.sh          # all of it
tooling/check.sh -v       # with each gate's output
tooling/check.sh snippets # one gate, by substring
```

A gate whose tool is missing reports `SKIP` and is counted separately — a green
line never quietly means "did not check".

| gate | what it protects |
|------|------------------|
| corpus | the grammar parses its own test corpus |
| queries compile | every `.scm`, including the shipped copies, resolves against the grammar |
| parser parity | tree-sitter accepts what the real parser accepts (ratchet) |
| error containment | one typo cannot stop the rest of the file highlighting |
| copies match source | no editor's queries have drifted from canonical |
| grammar pin | Zed's shipped queries work against the grammar commit it fetches |
| wasm + lsp export | the Zed shim builds and exports `language-server-command` |
| textmate scopes | VS Code scopes, plus keyword sync across all three highlighters |
| snippets parse | every VS Code snippet inserts code that actually parses |
| typescript | the VS Code extension compiles |
| lua syntax | the Neovim plugin loads |
| highlighter | the website's regex highlighter |

Two of these overlap deliberately. `copies match source` proves a copy matches
canonical; `queries compile` proves the result still works. Neither implies the
other — a copy can faithfully mirror a source that is itself broken, which is
exactly how Zed shipped a query file that refused to compile and rendered Mezze
with no highlighting at all.

## Editing

- **Grammar or queries** → edit `tree-sitter-mezze/`, then
  `syntax-generators/gen-queries.py`, then `check.sh`.
- **A generated `.scm`** → don't. They carry a `GENERATED` header; the gate will
  catch you.
- **Grammar change that Zed must see** → push it, *then* bump
  `[grammars.mezze].commit`. A pin is a coordinate into the remote, so it is
  only ever correct for a pushed commit.
- **VS Code TextMate** → `vscode-extension/extension/syntaxes/`, with a scope
  assertion in `test/tmgrammar.test.js`. Multi-token rules are the dangerous
  kind — that is how `pub` once bled into the following keyword.

## Prerequisites

`tree-sitter` CLI and `python3` for the grammar; `node` for VS Code and the
website; `cargo` with the `wasm32-wasip1` target for the Zed shim (nixpkgs'
rustc knows the triple but ships no std for it, so use rustup); `nvim` and a C
compiler for the Neovim plugin.
