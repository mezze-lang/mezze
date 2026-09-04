# Comptime and Macro Implementation Plan

Detailed analysis of complexity, edge cases, and implementation strategy.

**Session ID for continuation:** `aa22f0e`

**Related documents:**
- [MACRO_SYSTEM_V2.md](./MACRO_SYSTEM_V2.md) - Language specification
- [RISKS_AND_EDGE_CASES.md](./RISKS_AND_EDGE_CASES.md) - Potential bugs and mitigations

## Table of Contents

1. [Syntax Reference](#syntax-reference)
2. [Scenario Analysis](#scenario-analysis)
3. [Execution Model: In-Memory Evaluation](#execution-model-in-memory-evaluation)
4. [Processing Pipeline (Simplified)](#processing-pipeline-simplified)
5. [Dependency Graph and Cycle Detection](#dependency-graph-and-cycle-detection)
6. [Comptime Execution Environment](#comptime-execution-environment)
7. [Module System Interaction](#module-system-interaction)
8. [Decision Summary](#decision-summary)
9. [Caching Strategy](#caching-strategy)
10. [Implementation Phases (Revised)](#implementation-phases-revised)
11. [Design Inspiration: Crabtime](#design-inspiration-crabtime)
12. [Error Messages](#error-messages)

---

## Syntax Reference

| Syntax | Type | Meaning |
|--------|------|---------|
| `comptime {eval = {} -> expr}` | `{eval: {} -> 'a <*>} -> 'a` | Compile-time execution |
| `macro name: {tokens: Tokens} -> Tokens = ...` | Declaration | Define a macro |
| `name!(...)` | - | Call a macro |
| `` `(...) `` | `Tokens` | Quote - capture code as tokens |
| `~expr` | Inside quote only | Splice/Literal - type-directed (see below) |

### Unified `~` Operator (Simplification)

Instead of separate `~` (splice) and `^` (literal), we use `~` for both:

| Expression Type | `~expr` Behavior |
|-----------------|------------------|
| `Tokens` | Splice tokens into quote |
| `Int`, `String`, `Bool`, etc. | Convert to literal tokens |

```mezze
let tokens = `(1 + 2)
let name = "hello"
let count = 42

`(let ~(ident {name}) = ~tokens)     // ~tokens splices Tokens
`(let x = ~name)                      // ~name becomes "hello" literal
`(let y = ~count)                     // ~count becomes 42 literal
```

The typechecker knows the type, so it knows what to do. One less symbol to learn.

---

## Scenario Analysis

### Scenario 1: Self-Referencing Functions in Comptime

```mezze
let factorial = {n} -> if n <= 1 then 1 else n * factorial {n = n - 1}
let result = comptime {eval = {} -> factorial {n = 5}}
```

**Analysis:** This works because `factorial` is a pure recursive function with no forward references.

**Processing:**
1. Parse entire file
2. Type-check `factorial` (self-reference is allowed for recursive functions)
3. Check `factorial` is comptime-eligible (pure, no effects)
4. Execute `factorial {n = 5}` at compile time
5. Replace `result` with literal `120`

**Verdict: ALLOWED**

---

### Scenario 2: Forward References in Same File

```mezze
let a = comptime {eval = {} -> helper {x = 10}}
let helper = {x} -> x * 2
```

**Analysis:** When evaluating comptime for `a`, `helper` isn't defined yet.

**Options:**
- **Multi-pass:** Collect all declarations first, then evaluate in dependency order
- **Strict ordering:** Error - require definition before use
- **Lazy evaluation:** Allow forward refs to functions (not comptime values)

**Recommendation:** Allow forward references to **functions** but not to other **comptime values**.

```mezze
// ALLOWED: Forward reference to pure function
let a = comptime {eval = {} -> helper {x = 10}}
let helper = {x} -> x * 2

// ERROR: Forward reference to comptime value
let a = comptime {eval = {} -> b + 1}
let b = comptime {eval = {} -> 10}
```

**Verdict: ALLOWED with restrictions**

---

### Scenario 3: Mutual Recursion

```mezze
let is_even = {n} -> if n == 0 then true else is_odd {n = n - 1}
let is_odd = {n} -> if n == 0 then false else is_even {n = n - 1}
let result = comptime {eval = {} -> is_even {n = 10}}
```

**Analysis:** Both functions reference each other but neither is a comptime value itself.

**Processing:**
1. Detect mutual recursion group: `{is_even, is_odd}`
2. Type-check them together
3. Both are comptime-eligible (pure)
4. Execute comptime with both available

**Verdict: ALLOWED**

---

### Scenario 4: Comptime Inside Macro Definition

```mezze
macro gen: {tokens: Tokens} -> Tokens = {tokens} ->
    let config = comptime {eval = {} -> read_file {path = "config.json"}}
    `($(config.value))
```

**Analysis:** When does the comptime run?

**Answer:** At **macro definition time**, not expansion time.

```
Compile macro definition
  → See comptime block
  → Execute read_file NOW
  → config becomes a constant in macro body
  → Each expansion uses this constant
```

**Critical restriction:** Comptime in macro body **cannot reference macro arguments**.

```mezze
macro gen: {tokens: Tokens} -> Tokens = {tokens} ->
    let len = comptime {eval = {} -> tokens_length {tokens}}  // ERROR!
    // 'tokens' is not available at macro definition time
```

**Verdict: ALLOWED** - Comptime runs at definition time, cannot reference macro args

---

### Scenario 5: Macro Expansion Produces Comptime

```mezze
macro with_config: {tokens: Tokens} -> Tokens = {tokens} ->
    `(
        let cfg = comptime {eval = {} -> read_file {path = "config.json"}}
        ~tokens
    )

let main = {} -> with_config!(do_something {})
```

**Analysis:** The macro generates code containing a comptime block.

**Processing:**
1. Compile macro `with_config`
2. At call site, expand macro producing:
   ```mezze
   let cfg = comptime {eval = {} -> read_file {path = "config.json"}}
   do_something {}
   ```
3. Compile this generated code
4. Execute the comptime block
5. Continue compilation

**Verdict: ALLOWED** - Standard and useful pattern

---

### Scenario 6: Comptime Calls Macro

```mezze
macro helper: {tokens: Tokens} -> Tokens = {tokens} -> `(~tokens + 1)

let x = comptime {eval = {} ->
    helper!(42)
}
```

**Analysis:** The comptime block contains a macro call.

**Processing:**
1. Compile macro `helper` first
2. For comptime block in `x`:
   - See `helper!(42)`
   - Expand macro → `42 + 1`
   - Execute this code at compile time
   - Result: `43`

**Verdict: ALLOWED** - Macro must be compiled first

---

### Scenario 7: Macro Calls Macro

```mezze
macro inner: {tokens: Tokens} -> Tokens = {tokens} -> `(~tokens + 1)
macro outer: {tokens: Tokens} -> Tokens = {tokens} -> inner!(~tokens)
```

**Analysis:** Nested macro expansion.

**When `outer!(5)` is called:**
1. Expand `outer` with tokens = `5`
2. Body has `inner!(5)` (after splice)
3. Expand `inner` with tokens = `5`
4. Result: `5 + 1`

**Verdict: ALLOWED** - Standard nested expansion

---

### Scenario 8: Circular Macro/Comptime

```mezze
macro a: {tokens: Tokens} -> Tokens = {tokens} ->
    let x = comptime {eval = {} -> b!(tokens)}
    `(~x)

macro b: {tokens: Tokens} -> Tokens = {tokens} ->
    a!(tokens)
```

**Analysis:** This has TWO errors:

1. **Comptime references macro argument** - `tokens` not available at comptime
2. **Circular dependency** - `a` → comptime → `b` → `a`

**Cycle detection:**
```
Build dependency graph:
  a → comptime → b!(...)
  b → a!(...)

Detect cycle: a → b → a
```

**Verdict: ERROR** - Circular dependency (and comptime arg reference)

---

### Scenario 9: Comptime Referencing Imported Functions

```mezze
use std::string::{length}

let len = comptime {eval = {} -> length {s = "hello"}}
```

**Analysis:** Cross-module comptime usage.

**Processing:**
1. `std::string` module compiled first (module ordering)
2. `length` is comptime-eligible (pure function)
3. Comptime interpreter can call `length`

**Verdict: ALLOWED**

---

### Scenario 10: Comptime with Effects

```mezze
let config = comptime {eval = {} ->
    perform FileSystem in read_file {path = "config.json"}
}
```

**Analysis:** The core question - can comptime have effects?

**Decision:** Allow **all effects**. The signature is `{eval: {} -> 'a <*>} -> 'a` - the `<*>` means any effects.

The user is responsible for:
- Providing handlers for effects at compile time
- Accepting non-determinism if using network/time/random
- Accepting cache invalidation for effectful comptime

**Caching behavior by effect type:**

| Effect | Cache Behavior |
|--------|----------------|
| FileSystem (read) | Invalidate when file changes |
| FileSystem (write) | Allowed, no caching |
| Environment | Invalidate when env var changes |
| Network | No caching (non-deterministic) |
| Time | No caching (non-deterministic) |
| Random | No caching (non-deterministic) |

**Verdict: ALLOWED** - All effects permitted, caching is effect-aware

---

### Scenario 11: Comptime Result Used in Type Position

```mezze
let size = comptime {eval = {} -> 10}
type FixedArray = Seq size  // Can comptime be used in types?
```

**Analysis:** This is **dependent types** territory.

**Recommendation:** NOT ALLOWED initially. Keep types and values separate.

Comptime results are values, not types. For size-parameterized types, we would need const generics as a separate feature.

**Verdict: NOT ALLOWED (initially)**

---

### Scenario 12: Order of Evaluation Across Modules

```mezze
// module a.mz
pub let config = comptime {eval = {} -> read_file {path = "a.json"}}

// module b.mz
use a::{config}
let derived = comptime {eval = {} -> transform {config}}
```

**Analysis:** Standard module ordering handles this.

**Processing:**
1. Module dependency: `b` depends on `a`
2. Compile `a`: evaluate comptime, export `config = <value>`
3. Compile `b`: import `config` (already a value), evaluate comptime

**Circular module comptime** would be caught by existing circular import detection.

**Verdict: ALLOWED**

---

### Scenario 13: Comptime in Function Body

```mezze
let make_thing = {x} ->
    let base = comptime {eval = {} -> expensive_computation {}}
    base + x
```

**Analysis:** When does comptime run?

**Answer:** At **compile time, once**, when the function is compiled.

The function compiles to:
```mezze
let make_thing = {x} ->
    let base = 42  // assuming expensive_computation {} returned 42
    base + x
```

**Critical restriction:** Cannot reference function parameters.

```mezze
let make_thing = {x} ->
    let doubled = comptime {eval = {} -> x * 2}  // ERROR!
    // 'x' is runtime, not available at comptime
```

**Verdict: ALLOWED** - Cannot reference runtime values

---

## Execution Model: In-Memory Evaluation

Macros are Mezze functions (`{tokens: Tokens} -> Tokens`). To execute them at compile time, we use the same Truffle interpreter that runs the program - but during compilation, on-demand.

**Key insight from Crabtime (Rust):** Keep it simple. Don't pre-compile macros into stored CallTargets with complex lifecycle management. Just evaluate them inline when needed.

### How It Works

```
┌─────────────────────────────────────────────────────────────┐
│  When the typechecker encounters a macro call:              │
│                                                             │
│  1. Build minimal AST in memory:                            │
│     - Import statements the macro needs                     │
│     - The macro definition                                  │
│     - A binding that calls the macro with given tokens      │
│                                                             │
│  2. Pass to existing ASTToTruffleConverter                  │
│                                                             │
│  3. Execute via Truffle                                     │
│                                                             │
│  4. Extract result (Tokens), convert back to AST            │
│                                                             │
│  5. Continue typechecking with expanded AST                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Pseudocode

```kotlin
fun expandMacro(macroDef: MacroDefinition, inputTokens: Tokens, imports: List<Import>): AST {
    // Build minimal program to evaluate the macro
    val program = Program(
        items = imports + listOf(
            macroDef,
            LetBinding("__result",
                FunctionCall(macroDef.name, RecordLiteral(mapOf("tokens" to inputTokens)))
            )
        )
    )

    // Reuse existing infrastructure
    val converter = ASTToTruffleConverterV2(context, ...)
    val node = converter.convertProgram(program)

    // Execute
    val frame = createFrame(converter.frameDescriptor)
    node.execute(frame)

    // Get result and convert back to AST
    val resultTokens = frame.getValue(converter.getSlot("__result"))
    return tokensToAST(resultTokens)
}
```

### Why This Is Better

| Old Approach (Two-Stage) | New Approach (In-Memory) |
|--------------------------|--------------------------|
| Separate "compile macros" phase | No separate phase |
| Store CallTargets, manage lifecycle | No storage needed |
| Complex dependency tracking | Natural evaluation order |
| Special infrastructure | Reuses existing converter |
| Macro compilation errors reported separately | All errors in context |

### Key Benefits

**Simple:** No new infrastructure. We already have `ASTToTruffleConverter` and the interpreter.

**Natural:** When we see a macro call, we evaluate it. When we see comptime, we evaluate it. Same mechanism.

**IDE-Friendly:** The typechecker expands macros, so IDE gets full type information on expanded code.

**Cacheable:** Pure macros cached by input tokens. Effectful comptimes track dependencies and invalidate appropriately.

### Comptime in Macro Bodies

When a macro definition contains comptime:

```mezze
macro gen: {tokens: Tokens} -> Tokens = {tokens} ->
    let config = comptime {eval = {} -> read_file {path = "config.json"}}
    `($(config.value))
```

The comptime runs when we build the minimal program for the macro. The `config` value becomes a constant available in the macro body during expansion.

### Comptime in Macro Output

When a macro produces code containing comptime:

```mezze
macro with_config: {tokens: Tokens} -> Tokens = {tokens} ->
    `(
        let cfg = comptime {eval = {} -> read_file {path = "config.json"}}
        ~tokens
    )
```

The expanded code contains a comptime block. The typechecker will encounter it and evaluate it in the same way.

---

## Processing Pipeline (Simplified)

The in-memory evaluation approach simplifies the pipeline significantly. No separate "macro compilation" phase.

```
Source Code
    │
    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        PHASE 1: PARSING                             │
│  Parse all source files into AST                                    │
│  New syntax: macro definitions, macro calls, comptime, quote/splice │
│  Output: Map<ModulePath, AST>                                       │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   PHASE 2: MODULE ORDERING                          │
│  Build module dependency graph                                      │
│  Topologically sort modules                                         │
│  Detect circular imports                                            │
│  Output: List<ModulePath> in compilation order                      │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────┐
│           PHASE 3: TYPE CHECKING WITH INLINE EXPANSION              │
│                                                                     │
│  For each module in order:                                          │
│                                                                     │
│    When encountering macro call:                                    │
│      → Find macro definition (same file or imported)                │
│      → Build minimal in-memory program                              │
│      → Execute via Truffle interpreter                              │
│      → Convert result tokens back to AST                            │
│      → Continue typechecking with expanded AST                      │
│                                                                     │
│    When encountering comptime:                                      │
│      → Build minimal in-memory program                              │
│      → Execute via Truffle interpreter                              │
│      → Replace comptime with literal result                         │
│      → Continue typechecking                                        │
│                                                                     │
│  Output: Fully expanded and type-checked AST                        │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                PHASE 4: CODE GENERATION                             │
│  Generate Truffle nodes from final AST                              │
│  No macro calls or comptime blocks remain                           │
│  Output: Executable program                                         │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Simplifications

**No separate macro compilation phase:** Macros are evaluated on-demand when encountered.

**No stored CallTargets:** Each macro/comptime evaluation builds a fresh minimal program.

**Unified handling:** Both macro calls and comptime use the same mechanism (build AST, execute, get result).

**Natural ordering:** Process top-to-bottom. If a macro call references another macro, that macro must be defined above or imported.

### Recursion Handling

Macro expansion and comptime execution may produce more macros/comptime:

```
When expanding macro call:
    result = evaluate_macro(macro_def, input_tokens)
    expanded_ast = tokens_to_ast(result)

    # expanded_ast may contain more macro calls or comptime
    typecheck(expanded_ast)  # recursively handles them
```

With a recursion depth limit to catch infinite expansion (e.g., 100 levels).

---

## Dependency Graph and Cycle Detection

### Types of Dependencies

```
┌──────────────────────────────────────────────────────────────┐
│               DEPENDENCY TYPES                                │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  1. COMPTIME → FUNCTION                                      │
│     let x = comptime {eval = {} -> f {}}                     │
│     let f = {y} -> y * 2                                     │
│     Allowed: YES (f is comptime-eligible)                    │
│                                                              │
│  2. COMPTIME → COMPTIME                                      │
│     let x = comptime {eval = {} -> y + 1}                    │
│     let y = comptime {eval = {} -> 10}                       │
│     Allowed: YES (must evaluate y first)                     │
│     NOT allowed if forward reference                         │
│                                                              │
│  3. MACRO → MACRO                                            │
│     macro a: ... = inner!(...)                               │
│     macro inner: ... = ...                                   │
│     Allowed: YES (must compile inner first)                  │
│                                                              │
│  4. MACRO → COMPTIME                                         │
│     let cfg = comptime {eval = {} -> ...}                    │
│     macro m: ... = `(~cfg)                                   │
│     Allowed: YES (must evaluate cfg first)                   │
│                                                              │
│  5. COMPTIME → MACRO                                         │
│     macro m: ... = ...                                       │
│     let x = comptime {eval = {} -> m!(...)}                  │
│     Allowed: YES (must compile m first)                      │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Cycle Detection Algorithm

```
Algorithm: DetectComptimeCycles(declarations)

  Input: List of declarations with comptime and macro
  Output: Error if cycle, else execution order

  1. Build graph G where:
     - Nodes = comptime blocks ∪ macro definitions
     - Edges = "depends on" relationships

  2. For each comptime block C:
     - Find all identifiers referenced in C.body
     - For each identifier I:
       - If I is another comptime: add edge C → I
       - If I is a macro: add edge C → I
       - If I is a function: check comptime-eligibility
         - If not eligible: ERROR
         - If eligible: no edge (functions always available)

  3. For each macro M:
     - Find all macro calls in M.body (not in quotes)
     - For each macro call M'!: add edge M → M'
     - Find comptime blocks in M.body (not in quotes)
     - For each comptime C: add edge M → C

  4. Topological sort G:
     - If cycle: ERROR with cycle path
     - Else: return execution order
```

---

## Comptime Execution Environment

### Architecture (Simplified)

With the in-memory evaluation approach, we reuse the existing interpreter infrastructure:

```
┌────────────────────────────────────────────────────────────────┐
│                    INLINE EXECUTOR                              │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Input: AST expression + imports + available bindings          │
│                                                                 │
│  1. Build minimal Program AST:                                 │
│     ┌──────────────────────────────────────────────┐           │
│     │  use std::string::*   // from imports        │           │
│     │  let helper = ...     // from available      │           │
│     │  let __result = <the expression to eval>     │           │
│     └──────────────────────────────────────────────┘           │
│                              │                                  │
│                              ▼                                  │
│  2. Use existing ASTToTruffleConverterV2                       │
│     - Same converter used for normal compilation               │
│     - No special comptime infrastructure                       │
│                              │                                  │
│                              ▼                                  │
│  3. Execute via Truffle (same interpreter as runtime)          │
│     - All effects available (<*> in type signature)            │
│     - Effect handlers track dependencies for caching           │
│                              │                                  │
│                              ▼                                  │
│  4. Extract __result value from frame                          │
│     - Convert to AST literal                                   │
│     - Error if not representable (functions, etc.)             │
│                                                                 │
│  Output: AST Expression (literal)                              │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### Effect Handling

All effects are allowed in comptime (`<*>`). The handlers track what was accessed for caching:

| Effect | Behavior | Cache Impact |
|--------|----------|--------------|
| FileSystem read | Works normally | Track file hash |
| FileSystem write | Works normally | Mark as non-cacheable |
| Console print | Emits compiler note | No impact |
| Environment | Works normally | Track env var hash |
| Network | Works normally | Mark as non-cacheable |
| Time | Works normally | Mark as non-cacheable |

### Key Difference from Original Design

**Original:** Special "comptime interpreter" with restricted effect handlers.

**Revised:** Use the same interpreter as runtime. Effects are unrestricted, but we track dependencies for cache invalidation.

### Comptime-Representable Values

| Type | Representable? | Example |
|------|----------------|---------|
| Int, Nat, Float | YES | `42`, `3.14` |
| Bool | YES | `true`, `false` |
| String | YES | `"hello"` |
| Char | YES | `'a'` |
| Unit | YES | `{}` |
| Records | YES | `{ x = 1, y = 2 }` |
| Variants | YES | `Some { value = 42 }` |
| Seq | YES | `[1, 2, 3]` |
| List | YES | `1 :: 2 :: Nil` |
| Map | YES | `#{ "a" = 1 }` |
| Set | YES | `#{ 1, 2, 3 }` |
| Functions | NO | Cannot be literals |
| Continuations | NO | Runtime only |
| Effect handlers | NO | Runtime only |

---

## Module System Interaction

### Import Resolution for Comptime

```mezze
// std/math.mz
pub let PI = 3.14159265359

// std/config.mz
pub let DEFAULT_SIZE = comptime {eval = {} -> 100}

// user/app.mz
use std::math::{PI}
use std::config::{DEFAULT_SIZE}

let area = comptime {eval = {} -> PI * DEFAULT_SIZE * DEFAULT_SIZE}
```

**Processing:**
1. Module ordering: `std/math`, `std/config`, `user/app`
2. Compile `std/math`: `PI = 3.14159...` (literal)
3. Compile `std/config`: evaluate comptime, `DEFAULT_SIZE = 100`
4. Compile `user/app`: import both values, evaluate comptime

### Visibility and Comptime

```mezze
// module a.mz
let private_helper = {x} -> x * 2  // Not exported

pub let public_value = comptime {eval = {} -> private_helper {x = 5}}
// public_value = 10, that's what gets exported
// private_helper is NOT accessible to importers
```

Comptime evaluation happens at definition site, so it can use private helpers.

---

## Decision Summary

| Scenario | Verdict | Notes |
|----------|---------|-------|
| Self-referencing functions | ALLOWED | Standard recursion |
| Forward ref to functions | ALLOWED | Functions available before execution |
| Forward ref to comptime | ERROR | Need execution order |
| Mutual recursion in functions | ALLOWED | Both functions available |
| Comptime in macro body | ALLOWED | Runs at macro definition time |
| Comptime refs macro arg | ERROR | Arg not available at definition time |
| Macro produces comptime | ALLOWED | Standard pattern |
| Comptime calls macro | ALLOWED | Macro must be compiled first |
| Macro calls macro | ALLOWED | Nested expansion |
| Circular macro/comptime | ERROR | Cycle detection |
| Cross-module comptime | ALLOWED | Module ordering handles it |
| Effects in comptime | ALLOWED | All effects, cache invalidation per effect |
| Comptime in types | NOT ALLOWED | No dependent types (yet) |
| Comptime in function body | ALLOWED | Runs once at compile time |
| Comptime refs function param | ERROR | Param is runtime value |

---

## Caching Strategy

### Simple Rules

| Macro Type | Cache Key | Invalidation |
|------------|-----------|--------------|
| No comptime | `hash(macro_def + input_tokens)` | Never (pure) |
| Has comptime | `hash(macro_def + input_tokens + project_content_hash)` | Any .mz file changes |

### Why This Is Simple

**Pure macros (no comptime):** `debug!`, `list!`, `assert_eq!`
- Just token transformation, deterministic
- Cache forever
- Same input → same output, always

**Macros with comptime:** `sql!`, `routes!`
- Can do arbitrary effects (user-defined)
- We can't track what effects do
- **Solution:** Invalidate when ANY source file changes

### Project Content Hash

```
project_content_hash = hash(
    sorted([hash(file_content) for file in project/*.mz])
)
```

- Any .mz file saved → project hash changes → comptime caches miss
- Pure macro caches unaffected
- No dependency tracking needed
- Conservative but correct

### Storage

SQLite at `.mezze/cache/macros.sqlite`

```sql
CREATE TABLE macro_cache (
    cache_key TEXT PRIMARY KEY,
    result_tokens BLOB,
    has_comptime BOOLEAN,
    project_hash TEXT,  -- NULL for pure macros
    created_at INTEGER
);
```

### LSP Performance

- User types → no file save → cache valid → instant response
- User saves file → project hash changes → comptime re-runs once → cached again
- Pure macros always instant

### Why Not Track Dependencies?

Effects are user-defined and can do anything:
- Read files, network, environment, time, random
- Call other effects
- We can't know what they access

Tracking dependencies would require:
- Intercepting all effect handlers
- Complex invalidation logic
- Easy to get wrong (stale cache bugs)

"Invalidate on any source change" is:
- Simple to implement
- Always correct
- Fast enough (comptime runs once per save)

---

## Implementation Phases (Revised)

The in-memory evaluation approach simplifies implementation significantly.

### Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: Tokens Data Structure                             │
│  - Pure data, no dependencies                               │
│  - Lives in datastructures module                           │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Layer 2: Token <-> AST Conversion                          │
│  - Kotlin functions to convert                              │
│  - Lives in parser module                                   │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Layer 3: Inline Executor                                   │
│  - Single class that handles both macro and comptime        │
│  - Builds minimal AST, uses existing converter              │
│  - No stored CallTargets, no lifecycle management           │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Layer 4: Parser + Typechecker Integration                  │
│  - Parser changes for syntax                                │
│  - Typechecker calls executor when needed                   │
└─────────────────────────────────────────────────────────────┘
```

### Step 1: Tokens Type (isolated)

**New files:**
```
datastructures/src/main/java/com/mezze/datastructures/tokens/MezzeTokens.java
datastructures/src/main/java/com/mezze/datastructures/tokens/MezzeToken.java
```

**Content:**
- Token = Ident | Literal | Punct | Group
- Tokens = List<Token> with span info

**Effort:** 1-2 days

### Step 2: AST <-> Tokens Conversion (isolated)

**New files:**
```
parser/src/main/kotlin/com/mezze/language/parser/TokenConversion.kt
```

**Functions:**
- `astToTokens(expr: Expression): MezzeTokens`
- `tokensToAst(tokens: MezzeTokens): Expression`

**Testing:** Round-trip property tests

**Effort:** 2-3 days

### Step 3: Inline Executor (key simplification)

**New files:**
```
interpreter/src/main/kotlin/com/mezze/interpreter/comptime/InlineExecutor.kt
interpreter/src/main/kotlin/com/mezze/interpreter/comptime/ComptimeCache.kt
```

This is where the simplification pays off. **No separate MacroExecutor and ComptimeExecutor.** One unified component:

```kotlin
class InlineExecutor(private val context: TruffleLanguage.Env) {

    /**
     * Execute a comptime block and return the resulting value as AST literal.
     */
    fun executeComptime(
        comptimeExpr: ComptimeExpr,
        imports: List<Import>,
        availableBindings: Map<String, Expression>
    ): Expression {
        val program = buildMinimalProgram(comptimeExpr.eval, imports, availableBindings)
        val result = executeAndGetResult(program, "__comptime_result")
        return valueToLiteral(result)
    }

    /**
     * Expand a macro call and return the resulting tokens as AST.
     */
    fun expandMacro(
        macroDef: MacroDefinition,
        inputTokens: MezzeTokens,
        imports: List<Import>
    ): Expression {
        val program = buildMacroProgram(macroDef, inputTokens, imports)
        val resultTokens = executeAndGetResult(program, "__macro_result")
        return tokensToAst(resultTokens as MezzeTokens)
    }

    private fun buildMinimalProgram(...): Program { ... }
    private fun executeAndGetResult(program: Program, resultBinding: String): Any { ... }
    private fun valueToLiteral(value: Any): Expression { ... }
}
```

**Key insight:** We don't need to "compile macros" separately. When we see a macro call, we build a complete mini-program that defines the macro and calls it, execute that, and get the result.

**Effort:** 2-3 days (much simpler than the original 5-7 + 3-4 days)

### Step 4: Parser Changes

**Modified:**
```
parser/src/main/kotlin/com/mezze/language/parser/MezzeParser.kt
ast/src/main/kotlin/com/mezze/language/ast/Expression.kt
```

**New AST nodes:**
- `ComptimeExpr(eval: LambdaExpr)`
- `MacroDefinition(name, params, body)`
- `MacroCall(name, tokens: List<Token>)`
- `Quote(body: Expression)` - captures code as tokens
- `Splice(expr)` - type-directed: splices Tokens or converts value to literal

**Effort:** 2-3 days

### Step 5: Typechecker Integration

**Modified:**
```
typechecker/src/main/kotlin/com/mezze/language/typechecker/TypeChecker.kt
```

**Changes:**
- When visiting `MacroCall`: call InlineExecutor.expandMacro, then typecheck result
- When visiting `ComptimeExpr`: call InlineExecutor.executeComptime, replace with literal
- Track available macro definitions in scope
- Add span remapping for error messages in expanded code

**Effort:** 3-5 days

### Step 6: Caching (optional but recommended)

**New files:**
```
interpreter/src/main/kotlin/com/mezze/interpreter/comptime/ComptimeCache.kt
```

**Content:**
- Cache macro expansion results by: `hash(macro_def + input_tokens)`
- Cache comptime results with dependency tracking
- Store in `.mezze/cache/macros.sqlite`

**Effort:** 2-3 days

### Revised Effort Estimates (Phase 1 - No Reflection)

| Step | "Working" | "Great" (with proper errors) |
|------|-----------|------------------------------|
| 1. Tokens type (as Seq-compatible) | 1 day | 1-2 days |
| 2. AST <-> Tokens | 2 days | 2-3 days |
| 3. Inline executor | 2 days | 2-3 days |
| 4. Parser changes | 1-2 days | 2 days |
| 5. Typechecker integration | 2-3 days | 3-4 days |
| 6. SQLite caching | 1 day | 1-2 days |
| **Phase 1 Total** | **~1.5 weeks** | **~2 weeks** |

### Phase 2 - Add Reflection (Later)

| Step | Effort |
|------|--------|
| ReflectedType and related types | 2-3 days |
| reflect_type, reflect_ability, etc. | 3-4 days |
| Integration with comptime | 1-2 days |
| **Phase 2 Total** | **~1 week** |

### Simplifications Applied

1. **Unified `~` operator** - type-directed, no separate `^`
2. **Tokens works with Seq** - reuse existing operations
3. **Simple caching** - pure = forever, comptime = invalidate on any .mz change
4. **No reflection in Phase 1** - most macros don't need it

### Incremental Approach

**Week 1:**
- Tokens type with Seq compatibility (Step 1)
- AST <-> Tokens conversion (Step 2)
- Parser changes (Step 4)
- Inline executor (Step 3)

**Week 2:**
- Typechecker integration (Step 5)
- SQLite caching (Step 6)
- Polish and testing

**Phase 1 complete: macros + comptime working**

**Week 3+ (Phase 2, when needed):**
- Add reflection types
- Add reflect_* functions
- Enable derive-style macros

---

## Design Inspiration: Crabtime

The in-memory evaluation approach was inspired by [Crabtime](https://github.com/wdanilo/crabtime), a Rust crate for procedural macros.

### What Crabtime Does

Crabtime's approach for Rust:
1. Macro code is compiled as a **separate temp Rust project**
2. The compiled binary is **executed as a subprocess**
3. Communication happens via **stdout protocol**

This is simple and elegant for Rust because:
- Rust compilation is already slow, so subprocess overhead is acceptable
- Rust macros need access to the full Rust ecosystem
- Isolation prevents macros from corrupting compiler state

### Our Adaptation

We adapted the core insight (keep it simple) but not the mechanism:

| Crabtime (Rust) | Mezze |
|-----------------|-------|
| Separate project + subprocess | In-memory evaluation |
| External process isolation | Same process, fresh context |
| stdout protocol | Direct value passing |
| File-based caching | In-memory + SQLite cache |

**Why not subprocess for Mezze:**
- Our Truffle interpreter is fast to spin up
- We already have the interpreter in-process
- Subprocess overhead would be noticeable for many small macros
- In-memory is simpler for our use case

**What we kept from Crabtime:**
- No complex two-phase compilation
- Build minimal "program", execute it, get result
- Simple mental model: "macro is a function, call it"

---

## Error Messages

```
Error: Comptime block references undefined identifier 'helper'
  --> src/main.mz:3:35
   |
 3 | let a = comptime {eval = {} -> helper {x = 10}}
   |                                ^^^^^^
   |
Note: 'helper' is defined at line 5, but comptime blocks cannot
      reference forward-declared values.
Hint: Move the definition of 'helper' before this comptime block.


Error: Circular dependency in comptime evaluation
  --> src/main.mz:1:9
   |
 1 | let a = comptime {eval = {} -> b + 1}
   |         ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
   |
Note: Dependency cycle:
        a (comptime) at src/main.mz:1
     -> b (comptime) at src/main.mz:2
     -> a (comptime) at src/main.mz:1


Warning: Comptime uses non-deterministic effect 'Http'
  --> src/main.mz:5:25
   |
 5 | let data = comptime {eval = {} -> fetch_url {url = "..."}}
   |                                   ^^^^^^^^^^^^^^^^^^^^^^
   |
Note: Network effects make compilation non-reproducible.
      This comptime block will be re-executed on every build.
Hint: Consider reading from a local file instead, which allows
      caching based on file content hash.


Error: Cannot represent function value as comptime result
  --> src/main.mz:3:9
   |
 3 | let f = comptime {eval = {} -> {x} -> x + 1}
   |         ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
   |
Note: Comptime must produce a value that can be represented
      as a literal in the output code.
      Functions cannot be literals.
```
