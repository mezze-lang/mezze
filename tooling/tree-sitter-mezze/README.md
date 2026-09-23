# tree-sitter-mezze

Tree-sitter grammar for the [Mezze](https://github.com/mezze-lang/mezze)
language. Powers syntax highlighting in Neovim, Helix, Zed, Emacs, and replaces
the legacy TextMate grammar in the VSCode extension.

## Status

**Working grammar.** `grammar.js` parses the full language (let/type/newtype,
lambdas, records + spread, pipes with `@`, `do`, `match`, `perform` in both
forms, abilities/effects/impls, open record types, and more). Coverage is kept
honest by the shared corpus + parity gate described below. The remaining work
is long-tail constructs and highlight-query polish, not core parsing.

## Source of truth + sync harness

The hand-written parser is the source of truth:

- `parser/src/main/kotlin/com/mezze/language/parser/internal/Lexer.kt` — tokens
- `parser/src/main/kotlin/com/mezze/language/parser/internal/ExpressionParsers.kt` — expressions
- `parser/src/main/kotlin/com/mezze/language/parser/internal/DeclarationParsers.kt` — top-level decls

Sync is enforced by a **shared corpus** both sides read
(`fixtures/parser-corpus/*.mz`, one snippet per `# ===` block):

- **Parser side** — `SharedCorpusParityTest` (`:parser:test`) asserts the real
  `MezzeParser` accepts every snippet. A snippet only belongs in the corpus if
  the parser parses it, so this is the authoritative "is it valid Mezze" oracle.
- **Grammar side** — `scripts/parity.sh` runs the SAME snippets through
  `tree-sitter parse` and fails on any `ERROR`/`MISSING`. Both green ⇒ the
  grammar and the parser agree.
- **Corpus snapshot** — `scripts/gen-corpus.sh` snapshots the fixtures into a
  single `test/corpus/from-fixtures.txt` (golden tree shapes) so
  `tree-sitter test` also guards structure. Regenerate + review the diff after
  grammar changes; never hand-edit the generated file.

`.github/workflows/tree-sitter-parity.yml` runs both sides in CI.

To add coverage: add a `# ===` block to a fixture file. If the parser test
rejects it, the snippet isn't valid Mezze; if only the parity script rejects
it, the grammar has a gap — fix `grammar.js`, then `npm run gen-corpus`.

The TextMate grammar at `tooling/vscode-extension/extension/syntaxes/mezze.tmLanguage.json`
is **broken** (e.g. `pub` recolors the following keyword) and must not be used
as a reference.

## Build

The tree-sitter CLI is provided by the repo's `devenv.nix` — there is no
separate npm install step. From any shell inside `devenv shell`:

```sh
ts-build            # tree-sitter generate && tree-sitter build
ts-test             # tree-sitter generate && tree-sitter test
```

Or directly from `tooling/tree-sitter-mezze/`:

```sh
tree-sitter generate
tree-sitter test
tree-sitter parse FILE.mz
```

## Testing / sync

```sh
npm run check        # generate + corpus tests + parity gate (the CI gate)
npm run parity       # just the parser-corpus parity check (no ERROR nodes)
npm run gen-corpus   # regenerate test/corpus/from-fixtures.txt from fixtures
npm run test         # tree-sitter's own corpus tests
```

See "Source of truth + sync harness" above for how the parser and grammar are
kept in agreement.


## Queries

| File                        | Purpose                                                            |
|-----------------------------|--------------------------------------------------------------------|
| `queries/highlights.scm`    | Syntax highlighting (keywords, types, strings, etc.)               |
| `queries/indents.scm`       | Auto-indent on Enter / paste                                       |
| `queries/folds.scm`         | Fold ranges                                                        |
| `queries/textobjects.scm`   | Vim/Helix `daf`, `vif`, etc.                                       |
| `queries/locals.scm`        | Same-name highlighting + scope-aware go-to-def                     |

## Editor integration

The five query files here (`queries/*.scm`) are validated against the grammar
by the CI gate, so they load cleanly in any tree-sitter host.

### Neovim (nvim-treesitter)

Register this grammar and point it at the local checkout:

```lua
local parser_config = require('nvim-treesitter.parsers').get_parser_configs()
parser_config.mezze = {
  install_info = {
    url = '~/Projects/funk/tooling/tree-sitter-mezze', -- or the git URL
    files = { 'src/parser.c' },
    branch = 'main',
  },
  filetype = 'mezze',
}
vim.filetype.add({ extension = { mz = 'mezze' } })
```

Then `:TSInstall mezze` and copy `queries/*.scm` into
`~/.config/nvim/queries/mezze/`. `:TSPlaygroundToggle` shows the live tree.

### Helix

Add a `[[language]]` + `[[grammar]]` entry pointing at this dir in
`languages.toml`, then `hx --grammar build` and drop `queries/*.scm` under
`runtime/queries/mezze/`.

### VSCode

VSCode does **not** consume tree-sitter grammars natively. In the Mezze
extension, syntax colour comes from the LSP's **semantic tokens** (richer than
any TextMate/tree-sitter grammar because it's type-aware), with the TextMate
grammar as a plain-text fallback. Shipping a wasm tree-sitter build to fully
retire the TextMate grammar is tracked in #322 and is out of scope here.

### Zed / Emacs

Both consume tree-sitter natively; see #323. The grammar + queries in this dir
are what they'll point at.

## Highlight scope conventions

- All keywords (`let`, `type`, `if`, `match`, `do`, `where`, `for`, `with`,
  `having`, `perform`, …) → `@keyword`. They never blend into adjacent text.
- `pub` is its own node (`visibility_modifier`) and gets `@keyword.modifier`.
  Critically: `pub let f = …` keeps `let` the same colour as a bare `let f = …`.
- `effect`/`ability` declaration *headers* use `@keyword` for the keyword and
  `@type.interface` for the name — both at the declaration site and at use
  sites (e.g. `with Eq 'a`, `perform Log.info {…}`).
- Concrete types and type aliases get `@type`. Type variables (`'a`) get
  `@type.parameter`. Variant constructors (`Some`, `None`) get `@constructor`.
- Metadata records `#{…}` get `@attribute` — they're compiler-meaningful, so
  they should look louder than `# line comments` (`@comment`) but distinct
  from regular records.
