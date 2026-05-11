# tree-sitter-mezze

Tree-sitter grammar for the [Mezze](https://github.com/shriyansbhatnagar/funk)
language. Powers syntax highlighting in Neovim, Helix, Zed, Emacs, and replaces
the legacy TextMate grammar in the VSCode extension.

## Status

**Scaffold only.** The grammar in `grammar.js` is a stub that accepts a stream
of identifiers — enough to verify the build pipeline works. Real grammar rules
land in [#315](https://github.com/shriyansbhatnagar/funk/issues/315). See
`memory/project_treesitter_plan.md` for the full scope.

## Source of truth

The grammar mirrors the hand-written parser at:

- `parser/src/main/kotlin/com/mezze/language/parser/internal/Lexer.kt` — tokens
- `parser/src/main/kotlin/com/mezze/language/parser/internal/ExpressionParsers.kt` — expressions
- `parser/src/main/kotlin/com/mezze/language/parser/internal/DeclarationParsers.kt` — top-level decls

Tests under `parser/src/test/kotlin/com/mezze/language/parser/` are the real
specification — every `.mz` source string that parses there must also parse
here.

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


## Queries

| File                        | Purpose                                                            |
|-----------------------------|--------------------------------------------------------------------|
| `queries/highlights.scm`    | Syntax highlighting (keywords, types, strings, etc.)               |
| `queries/indents.scm`       | Auto-indent on Enter / paste                                       |
| `queries/folds.scm`         | Fold ranges                                                        |
| `queries/textobjects.scm`   | Vim/Helix `daf`, `vif`, etc.                                       |
| `queries/locals.scm`        | Same-name highlighting + scope-aware go-to-def                     |

## Editor integration

- **VSCode** — wired through `tooling/vscode-extension/` (replaces the broken
  TextMate grammar). See #322.
- **Neovim / Helix / Zed / Emacs** — see #323.

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
