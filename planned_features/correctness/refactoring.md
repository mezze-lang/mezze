# Refactoring Tools

## Why Mezze's Architecture Makes Refactoring Easy

Most languages identify definitions by **name and path**. Rename a function, and every import breaks. Move a file, and every reference breaks. This makes refactoring tools fragile — they have to find and update every textual reference.

Mezze identifies definitions by **content hash**. A function's identity is its hash, computed from its content. Names are metadata attached to hashes. This means:

- **Rename** — change the name metadata, hash stays the same, no references break
- **Move** — change the file, module path updates, but hash-based references are stable
- **Extract function** — new function gets a new hash, call site references it by hash
- **Dead code** — no incoming hash edges = unused, safe to delete

The `MezzeStore` tracks everything by hash:
- `putReference(targetHash, filePath, line, col)` — records where each hash is used
- `getReferences(hash)` — find all usages
- `addEdge(fromHash, toHash, kind)` — dependency graph
- `getTransitiveClosure(entryHash)` — reachability analysis

And every AST node has precise source location (`Loc` with startLine, startCol, endLine, endCol), so the tools know exactly what text to modify.

## Supported Refactoring Operations

### Rename

The simplest refactoring, and the safest in Mezze.

**How it works:**
1. User puts cursor on a name, triggers rename
2. LSP looks up the name → hash
3. `getReferences(hash)` returns all locations where this hash is used
4. Each location has precise Loc — replace the name text at each location
5. Update the export table if it's a pub declaration
6. Update import aliases if renamed at the definition site

**Why it's safe:** Hash doesn't change. Cross-module references work by hash. Only the display name changes. Even if the rename is interrupted halfway, the code still compiles — names are metadata.

**Scope:**
- Local variables — rename within the function
- Module-level functions — rename across all importing modules
- Type names — rename type and all constructor references
- Ability/effect names — rename across impls and perform blocks
- Record fields — rename field name across all record literals and accesses

### Extract Function

Select an expression, pull it into a new function, replace with a call.

**How it works:**
1. User selects an expression (LSP knows the Loc boundaries)
2. Analyze the selection for free variables — which bindings from the outer scope does it reference?
3. These become the new function's parameters (named fields, naturally)
4. Infer the return type from the expression's type (already computed by typechecker)
5. Infer effects from the expression's effect annotations
6. Create new function declaration with a fresh hash
7. Replace the selected expression with a call to the new function

**Example:**
```mezze
# Before
pub let process = {users: [User]} -> [Str]
  users
    .filter {p = {u} -> u.age > 18 && u.is_active}
    .map {f = {u} -> u.name}

# Select: u.age > 18 && u.is_active
# Extract as: is_eligible

# After
let is_eligible = {u: User} -> Bool
  u.age > 18 && u.is_active

pub let process = {users: [User]} -> [Str]
  users
    .filter {p = {u} -> is_eligible {u}}
    .map {f = {u} -> u.name}
```

**Named fields make this clean:** The extracted function's parameters are named after the bindings they capture. `u` in the outer scope becomes `{u: User}` in the parameter. No positional argument confusion.

**Effects propagate:** If the selected expression has effects, the extracted function's type includes those effects automatically.

### Inline Function

The inverse of extract — replace a function call with the function's body.

**How it works:**
1. User puts cursor on a function call
2. LSP looks up the function by its call hash
3. Retrieve the function body
4. Substitute parameters with the call-site arguments
5. Replace the call with the substituted body

**Example:**
```mezze
# Before
let double = {x: Int} -> x * 2
let result = double {x = 5}

# Inline double

# After
let result = 5 * 2
```

**Safety check:** Don't inline if the function is called from multiple modules and the user only wants to inline at one site. The hash-based reference system makes it easy to check how many call sites exist.

### Move Declaration

Move a function or type to a different module.

**How it works:**
1. Remove declaration from source module
2. Add declaration to target module
3. Hash stays the same (content didn't change)
4. Update import paths in modules that reference it
5. Add re-export from old module if backward compatibility needed

**Why hash helps:** The declaration's hash doesn't change when it moves. Any module that references it by hash still works. Only the import path (`use old::module::func` → `use new::module::func`) needs updating.

### Dead Code Detection

Find definitions that are never referenced.

**How it works:**
1. For each definition hash in the module, check `getReferences(hash)`
2. If no references exist (and it's not a `pub` export), it's dead
3. For pub exports, check if any importing module actually uses it

**Transitive dead code:** Use `getTransitiveClosure` from entry points. Anything not in the closure is unreachable.

```
$ mez dead-code module

  Unused definitions:
    line 12: let helper = ...        (0 references)
    line 45: type OldConfig = ...    (0 references)
    line 78: let debug_dump = ...    (0 references, not exported)
```

### Change Signature

Add, remove, or reorder function parameters.

**Why named fields make this unique:** In most languages, changing parameter order breaks all call sites. In Mezze, **parameter order doesn't matter** — calls use named fields.

```mezze
# Original
let connect = {host: Str, port: Int} -> Connection

# Add timeout parameter with default
let connect = {host: Str, port: Int, timeout: Int} -> Connection

# All existing call sites still work:
connect {host = "localhost", port = 8080}  # timeout needs a default or this errors
```

Adding a parameter only breaks call sites if there's no default. Removing a parameter is flagged at all call sites by the typechecker. Reordering never breaks anything.

**What the tool does:**
1. Update the function signature
2. For added parameters without defaults — find all call sites, prompt for the value
3. For removed parameters — find call sites that provide it, remove the field
4. For renamed parameters — update all call sites (like rename, but for a field name)

## LSP Integration

All refactoring operations are triggered through standard LSP operations:

- **Rename** — `textDocument/rename`
- **Extract function** — `textDocument/codeAction` on a selection
- **Inline** — `textDocument/codeAction` on a call
- **Move** — `textDocument/codeAction` on a declaration
- **Dead code** — `textDocument/diagnostic` (highlight unused code)
- **Change signature** — custom code action

The LSP server already has access to the typechecker (for types, hashes, references) and MezzeStore (for cross-module lookups). The refactoring operations query these for hash-based reference resolution.

## Connection to Correctness Tooling

Refactoring and correctness tools work together:

- **After rename/move** — run `mez sample` on affected functions to verify behavior didn't change
- **After extract function** — sample the extracted function independently to verify it's correct in isolation
- **After inline** — diff the behavior (same inputs, same outputs?) to verify the inlining preserved semantics
- **Dead code** — use `mez sample --cover` data to confirm code is actually unreachable at runtime, not just statically
