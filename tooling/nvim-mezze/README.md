# Mezze — Neovim

Tree-sitter highlighting plus the `mez` language server.

Neovim gets **more** than the other editors here, for a structural reason: the
canonical queries in `../tree-sitter-mezze/queries/` are written in
nvim-treesitter's capture dialect (`@variable.member`, `@type.interface`,
`@number.float`). Zed is the consumer that needs a rename table; for Neovim the
generated copy is an identity copy. Two of these files Zed does not load at all:

| file              | what it buys you                               | Zed |
|-------------------|------------------------------------------------|-----|
| `highlights.scm`  | syntax colouring                               | yes |
| `indents.scm`     | auto-indent (Mezze is indentation-significant) | yes |
| `textobjects.scm` | `daf` / `vif` / `]f` motions                   | yes |
| `folds.scm`       | folding                                        | no — Zed folds from indents |
| `locals.scm`      | same-name highlighting, scope-aware go-to-def  | no  |

## Install

The parser is **generated**, not committed — `src/parser.c` is gitignored in the
grammar directory (only the hand-written `scanner.c` is tracked). Build it once:

```sh
tooling/nvim-mezze/build.sh          # needs the tree-sitter CLI and a C compiler
```

That writes `parser/mezze.so` into this plugin. Neovim loads it straight off the
runtimepath, so **nvim-treesitter is not required** for the parser. Then point
your plugin manager at this directory:

```lua
-- lazy.nvim
{ dir = "/path/to/mezze/tooling/nvim-mezze", config = function()
    require("mezze").setup()
  end }
```

`mez` must be on `PATH` for the language server, or pass one:

```lua
require("mezze").setup({ cmd = { "/home/you/.mezze/bin/mez", "lsp" } })
```

Check it with `:checkhealth mezze` — it reports the grammar directory, whether
the tree-sitter CLI is present, whether `mez` resolves, and whether the parser
actually loaded. A missing piece there is the difference between coloured text
and a working language integration.

## Options

| option         | default            | meaning                                     |
|----------------|--------------------|---------------------------------------------|
| `treesitter`   | `true`             | register the parser with nvim-treesitter    |
| `lsp`          | `true`             | start `mez lsp` for Mezze buffers           |
| `cmd`          | `{"mez","lsp"}`    | language-server command                     |
| `root_markers` | `{"Mezze.toml"}`   | what marks a project root                   |

Set `treesitter = false` if you built `parser/mezze.so` with `build.sh` and do
not want the nvim-treesitter registration at all — that is the lighter setup and
the one the build script is designed for.

LSP wiring uses `vim.lsp.config`/`vim.lsp.enable` on Neovim 0.11+, and falls back
to `lspconfig` on older versions.

## The queries are generated

Do not edit `queries/mezze/*.scm`. Edit `../tree-sitter-mezze/queries/` and run:

```sh
tooling/syntax-generators/gen-queries.py            # regenerate
tooling/syntax-generators/gen-queries.py --check    # gate: copies match source
```

Verified end to end on Neovim 0.12: parser loads, all five queries load, the
server attaches and reports cross-module type errors.
