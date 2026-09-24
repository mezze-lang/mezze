# Metadata System

## Syntax

Metadata uses `#{ }` — the `#` comment character followed by record literal syntax with spaces:

```mezze
#{ deprecated: True, since: "0.5.0", replacement: "new_func" }
pub let old_func = {x: Int} -> x
```

Three levels of `#`:
- `#` — regular comment (preserved for formatter, never attaches semantically)
- `##` — doc comment (structured doc string, shown in LSP hover and docs)
- `#{ }` — metadata (structured key-value data for tools, compiler, and macros)

## Values

Metadata values are JSON-compatible literals. If a value can't be parsed as one of these, it's treated as a raw string (metadata never causes parse errors):

| Type | Example | JSON serialization |
|---|---|---|
| Bool | `True`, `False` | `true`, `false` |
| Int | `42`, `-1` | `42`, `-1` |
| Float | `3.14` | `3.14` |
| String | `"hello"` | `"hello"` |
| List | `[1, 2, 3]` | `[1, 2, 3]` |
| Record | `{timeout: 5000}` | `{"timeout": 5000}` |
| Bare word | `experimental` | `"experimental"` (treated as string) |

Lists and records can nest recursively — any value type inside any container:

```mezze
#{ config: {retries: 3, timeout: 5000}, tags: ["math", "pure"] }
pub let compute = {x: Int} -> x * 2
```

A key without a colon is treated as a boolean flag (value = True):

```mezze
#{ deprecated, since: "0.5.0" }
# equivalent to: #{ deprecated: True, since: "0.5.0" }
```

## What Metadata Is NOT

Metadata is information **about** code, not code itself. It does not:
- Affect the content hash of a function or type
- Change runtime behavior
- Generate code (that's macros)
- Replace the type system

Metadata never errors — if the `#{ }` content is malformed, it's treated as a regular comment.

## Placement Rules (Roc-Style)

### The contiguous annotation block

A declaration can have an optional annotation block directly above it:

```
[## doc comment lines]    <- zero or more, contiguous
[#{ metadata }]           <- zero or one
declaration               <- must immediately follow
```

### Strict rules

1. `##` doc lines first, then `#{ }` metadata, then declaration
2. No other order is valid — `##` after `#{ }` causes a reset
3. Exactly **one** `#{ }` block per declaration
4. A blank line **resets** everything above it (doc and metadata lost)
5. A `#` regular comment **resets** everything above it
6. Only the last unbroken contiguous block attaches to the declaration

### Valid examples

```mezze
## Sorts a sequence in ascending order.
## Uses stable merge sort.
#{ since: "0.1.0", tags: ["collections"] }
pub let sort = {xs: ['a]} -> ['a] with Ord 'a
  ...
```

```mezze
## Just a doc comment, no metadata.
pub let add = {x: Int, y: Int} -> Int
  x + y
```

```mezze
#{ deprecated: True, replacement: "new_func" }
pub let old_func = {x: Int} -> x
```

### Reset examples

```mezze
## This doc is LOST

pub let func = {x: Int} -> x       # blank line reset — no doc, no metadata
```

```mezze
## This doc is LOST
# regular comment resets
## This doc attaches
pub let func = {x: Int} -> x       # only "This doc attaches" survives
```

```mezze
#{ deprecated: True }
# regular comment resets
pub let func = {x: Int} -> x       # no metadata
```

## Hash Exclusion

Neither `##` doc comments nor `#{ }` metadata affect the content hash:

```
Hash = hash(parameters + body + type constraints)

NOT included in hash:
  - comments (#)
  - doc comments (##)
  - metadata (#{ })
  - whitespace / formatting
  - file path / module location
```

## Storage

Doc comments and metadata are stored in two places:

1. **`parsed_modules` blob** — the full ParsedModule serialization includes doc/metadata on each ParsedDecl (source of truth)
2. **`decls` table columns** — `doc TEXT` and `metadata TEXT (JSON)` for fast querying without deserialization

The `decls` table columns are an index — a denormalized copy for fast LSP hover and query access.

## Standard Fields

Some fields have built-in meaning. The compiler ignores unknown fields — libraries can define their own.

| Field | Type | Used by | Meaning |
|---|---|---|---|
| `deprecated` | Bool | Compiler, LSP | Warn at call sites, strikethrough |
| `since` | Str | Docs | Version when added/deprecated |
| `replacement` | Str | Compiler | Suggest in deprecation warning |
| `inline` | Bool | Compiler | Hint for inlining |
| `test` | Bool | Test runner | Mark as test function |
| `bench` | Bool | Bench runner | Mark as benchmark |
| `tags` | [Str] | Search, docs | Categorization |
| `stability` | Str | Compiler | "experimental", "stable" — warn on experimental use |
| `no_warn` | Str | Compiler | Suppress specific warnings |

## Accessing Metadata

### At compile time via reflection

```mezze
comptime {eval = {} -> do
  let meta = metadata {for_decl = old_func}
  # meta = {deprecated: True, since: "0.5.0", replacement: "new_func"}
}
```

### From the store (LSP, tools)

```sql
SELECT doc, metadata FROM decls WHERE decl_name = 'sort'
SELECT * FROM decls WHERE json_extract(metadata, '$.deprecated') = 'true'
```

## Implementation Status

### Done
- `ParsedMetadata` and `ParsedMetadataValue` types in AST
- `docComment` and `metadata` fields on all `ParsedDecl` types
- `docComment` and `metadata` fields on `ParsedRecordFieldDef` and `ParsedVariantCtorDef` (ready for future parser support)
- MetadataParser for `#{ }` content (lenient — never errors)
- Roc-style annotation collection in module parser
- `doc` and `metadata` columns on `decls` table
- StorePopulator writes doc/metadata to `decls` table
- Formatter outputs `#{ key: value }` format
- Comprehensive parser tests (46 tests)

### Pending
- Type member annotation parsing (fields, constructors) — requires `sepEndByWithNewlines` changes
- LSP hover shows doc comments
- LSP deprecation warnings from metadata
- Compiler warnings at deprecated call sites
