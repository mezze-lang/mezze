# Metadata — what is built, and what is missing

`#{ }` metadata is **not half-built**. The storage path is complete and one
field works end to end. What it never became is a *query* feature or a
*program-visible* one, which is exactly what the platform in `README.md` needs
from it.

## Built

| layer | state |
|---|---|
| syntax | `#{ key: value }`, JSON-compatible values, nesting, bare-word flags (`#{ deprecated, since: "0.5.0" }`) |
| placement | Roc-style contiguous block, blank line and `#` comment both reset |
| AST | `ParsedMetadata` on ~8 declaration kinds |
| hash | **excluded** from the content hash — implemented, not just documented |
| store write | `StorePopulator` → `putDecl(metadata = serializeMetadata(...))` |
| schema | `decls.metadata TEXT` alongside `decls.doc TEXT` |
| store read | `DeclRow.metadata`, returned by all four decl SELECTs |
| formatter | preserved by `PrettyPrinter` |
| LSP hover | shown by `HoverInfo` next to the doc comment |
| **`deprecated`** | **fully realised** — the declaration is struck through (`deprecationDiagnostics()`), and every USE warns W0501 in `mez check`/`build` and the editor (`DeprecationLinter`, by identity, across modules); `since` and `note` fold into the message |
| **`missing`** | on an ability: the author's advice in the "does not implement" error, replacing the impl skeleton |
| **`unhandled`** | on an effect: the author's advice when the entry leaks it — which performer to wrap it in |

Hash exclusion is worth calling out as a design win: a rendering hint can never
invalidate a cache or trigger recompilation, and metadata can never become
load-bearing for semantics.

## Missing

### 1. Cannot query BY metadata — small, and blocking

Every SELECT keys on `module_path`, `decl_kind`, `hash` or `decl_name`. There
is no `WHERE json_extract(metadata, '$.tags') = …` anywhere.

The design document says the column exists *"for fast querying without
deserialization"* — and then nothing ever uses it as a search key. You can ask
"what metadata does `sort` have?" but not "what in this codebase is tagged
`collections`?" or "what is deprecated?"

`sqlite-jdbc 3.51.2` ships **JSON1**, and the column is already populated, so
this is one method on `MezzeStore`, one implementation, and a CLI surface
(`mez index` is the natural home). Cheapest item in the whole platform.

### 2. Only one field has behaviour

`deprecated` acts; `since` and `note` are decoration on its message. Everything
else is inert by design — *"custom fields are allowed — the compiler ignores
unknown fields."* That is the right default, but it means a custom field today
is write-only. Each new standard field is a constant plus its consumer.

### 3. Not visible to Mezze code — and something already depends on it

There is no reflection or comptime access. `comptime` appears **zero times**
outside `planned_features/macros/`.

This is not academic: `../correctness/property-testing.md` is built entirely on
`ast_gen` using "comptime + reflection macros" to analyse the function under
test. **That design depends on a capability that does not exist**, so property
testing cannot be built until macros are. Worth knowing before anyone picks it
up expecting a userland library.

### 4. Stops at parse time

Metadata never reaches the typed AST, the IR, or the runtime. A value in a REBL
browser carries none.

The way round it is provenance rather than attachment — the tool knows *what it
evaluated*, so it looks the declaration up and renders accordingly. That works
today. See `README.md` §B.

### 5. Declaration-level only — a real language change

One `#{ }` block per declaration is the documented rule, and `TypeAnnotation.kt`
has no metadata on fields. Marking *one field* of a record as a duration or a
secret needs syntax, parser, AST, and a hash-exclusion decision. Do not fold
this into tooling work; it is a language change and should be judged as one.

## Order

1. **(1) query by metadata** — cheap, unblocks doc browser + renderer hints
2. **(2) more standard fields** — as each earns its consumer
3. **(4) provenance lookup** — no language change, covers the common cases
4. **(3) reflection** — only as part of macros
5. **(5) field-level** — only if provenance proves insufficient
