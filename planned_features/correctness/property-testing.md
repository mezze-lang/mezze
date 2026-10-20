# AST-Aware Property Testing Library

## Relationship to `mez sample`

`mez sample` is a built-in CLI/LSP tool for spot-checking. The property testing library is its programmable counterpart for serious verification, CI, and regression testing.

Both are AST-aware. Both generate smart inputs from type signatures and code analysis. The difference:

| | `mez sample` | Property testing library |
|---|---|---|
| **Who checks correctness?** | Developer reads output visually | Developer writes boolean properties |
| **How many inputs?** | 8-20, human-readable | 100-1000, automated |
| **When to use** | Quick spot-check during development | CI, regression suites, thorough verification |
| **Interactive** | Yes — more, focus, custom, shrink | No — batch, pass/fail |
| **AST access** | Direct — it's a compiler tool | Via comptime + reflection macros |

## Why a Library Can Be AST-Aware in Mezze

In most languages, property testing libraries (QuickCheck, Hypothesis, fast-check) can only see **type signatures**. They generate random values of the right type but are blind to the implementation — they don't know what comparisons, branches, or constants exist in the function under test.

Mezze has three features that change this:

1. **Macros** — functions that run at compile time, can take function references
2. **Comptime** — execute arbitrary Mezze code during compilation
3. **Reflection** — inspect types, and with AST reflection extensions, inspect function bodies

Together, a library macro can analyze a function's AST at compile time and generate a smart input generator — all in userland, no special compiler support beyond the reflection API.

## Required Reflection API Extensions

The existing reflection API provides type inspection (`type_info`, `fields_of`, `constructors_of`). For AST-aware testing, we need function body inspection:

```mezze
# In std/macro/reflection.mz (extensions)

# Get a function's body as an inspectable expression tree
func_body: {func: Function} -> Expression

# Extract branch conditions (if/else, guards)
branch_conditions: {expr: Expression} -> [{
  condition: Expression,
  args_involved: [Str],
  location: SourceInfo
}]

# Extract pattern match arms
match_arms: {expr: Expression} -> [{
  pattern: Pattern,
  guard: Option Expression,
  location: SourceInfo
}]

# Extract all literal values used in comparisons
comparisons_in: {expr: Expression} -> [{
  left: Expression,
  op: Str,
  right: Expression,
  location: SourceInfo
}]

# Extract all literal constants
literals_in: {expr: Expression} -> [{
  value: Literal,
  context: Str,
  location: SourceInfo
}]

# Extract cross-argument relationships
arg_relations: {expr: Expression} -> [{
  left_arg: Str,
  op: Str,
  right_arg: Str,
  location: SourceInfo
}]
```

These are compile-time intrinsics — the compiler resolves them during compilation and the results are embedded as data. No runtime reflection overhead.

## Library Design

### Basic Usage — Type-Driven (Like QuickCheck)

```mezze
use std::testing::property::{check, type_gen}

# Type-driven generator — blind to implementation
check {
  name = "reverse is involution",
  gen = type_gen {},
  prop = {xs: [Int]} -> xs.reverse {}.reverse {} == xs
}

check {
  name = "sort preserves length",
  gen = type_gen {},
  prop = {xs: [Int]} -> sort {xs}.length {} == xs.length {}
}
```

`type_gen` generates inputs based on parameter types only. Same as QuickCheck — edge values per type (0, 1, -1 for Int, empty/singleton for Seq, etc.) plus random values within bounds.

### AST-Aware Usage

```mezze
use std::testing::property::{check, ast_gen}

# AST-aware generator — reads the function's code
check {
  name = "clamp is bounded",
  gen = ast_gen {func = clamp},
  prop = {val: Int, min: Int, max: Int} ->
    let result = clamp {val, min, max}
    if min <= max then result >= min && result <= max
    else True
}

check {
  name = "categorize covers all grades",
  gen = ast_gen {func = categorize},
  prop = {score: Int} ->
    let result = categorize {score}
    ["excellent", "good", "pass", "fail"].contains {x = result}
}

check {
  name = "transfer preserves total",
  gen = ast_gen {func = transfer},
  prop = {from_balance: Int, to_balance: Int, amount: Int} ->
    match transfer {from_balance, to_balance, amount} is
      Ok {val} -> val.from + val.to == from_balance + to_balance
      Err _ -> True
}
```

`ast_gen {func = clamp}` is a macro that at compile time:
1. Gets `clamp`'s parameter types via `type_info`
2. Gets `clamp`'s body via `func_body`
3. Extracts comparisons: `val < min`, `val > max`
4. Extracts constants and cross-argument relationships
5. Generates a generator function that produces inputs targeting boundaries, covering branches, and testing argument relationships

### How `ast_gen` Works Internally

```mezze
macro ast_gen: {tokens: TokenStream} -> Generator = {tokens} ->
  let func_ref = parse_function_ref {tokens}

  comptime {eval = {} -> do
    # Get type information
    let type = type_info {for_type = func_ref}
    let params = fields_of {type = type.params}

    # Get AST information
    let body = func_body {func = func_ref}
    let conditions = branch_conditions {expr = body}
    let comparisons = comparisons_in {expr = body}
    let consts = literals_in {expr = body}
    let relations = arg_relations {expr = body}
    let arms = match_arms {expr = body}

    # Build generator that combines:
    # 1. Type-driven edges (from params)
    # 2. Boundary values (from comparisons — e.g., 89,90,91 for >= 90)
    # 3. Branch-covering values (from conditions)
    # 4. Constants used in code (from consts)
    # 5. Cross-argument relationships (from relations)
    # 6. Variant constructor coverage (from arms)
    build_smart_generator {params, conditions, comparisons, consts, relations, arms}
  }
```

The `build_smart_generator` function (also in the library) takes the analysis results and produces a generator function that knows where the interesting inputs are. This is all regular Mezze code running at compile time — no special compiler magic beyond the reflection API.

## Generator Tiers

The `ast_gen` generator produces inputs in the same three tiers as `mez sample`:

### Tier 1: Type-driven edges + code-driven boundaries

Always included. Combines:
- Type edges: 0, 1, -1 for Int; "", "a" for Str; None/Some for Option; etc.
- Code boundaries: for `score >= 90`, generate 89 and 90. For `amount > from_balance`, generate equal and unequal pairs.
- Variant coverage: for each match arm, generate a matching constructor.

### Tier 2: Pairwise combinations

After edges, ensure every edge value of argument x appears with every edge value of argument y at least once.

### Tier 3: Bounded random

Fill remaining budget with random values in reasonable ranges.

## Shrinking

When a property fails, the library shrinks the input to find the minimal failing case:

```mezze
check {
  name = "dedup removes duplicates",
  gen = ast_gen {func = dedup},
  prop = {xs: [Int]} ->
    let result = dedup {xs}
    result.length {} == set::from_seq {xs}.size {}
}

# Output:
# FAILED: dedup removes duplicates
#   Original failing input: {xs = [3, -7, 12, 0, -2, 8, 3]}
#   Shrunk to: {xs = [0, 0]}
#   Property returned False
```

Shrinking is automatic — no developer interaction. The library progressively simplifies the input while the property still fails.

Shrinking strategies per type:
- `Int` — try 0, then halve toward 0
- `Str` — try "", then remove characters
- `[T]` — try [], then remove elements, then shrink elements
- `Option T` — try None, then shrink inner value
- Record — shrink each field independently
- Variant — try simpler constructors, then shrink fields

## Configuration

```mezze
use std::testing::property::{check, ast_gen, Config}

check {
  name = "sort is stable",
  gen = ast_gen {func = sort},
  config = Config {
    cases = 500,          # number of test cases (default 100)
    max_shrinks = 50,     # max shrink attempts (default 100)
    seed = 42,            # reproducible (default random)
  },
  prop = {xs: [Int]} -> ...
}
```

## Coverage Reporting

The library tracks which branches in the function under test were exercised:

```mezze
check {
  name = "categorize all paths",
  gen = ast_gen {func = categorize},
  coverage = True,
  prop = {score: Int} ->
    ["excellent", "good", "pass", "fail"].contains {x = categorize {score}}
}

# Output:
# PASSED: categorize all paths (100 cases)
#   Coverage:
#     line 2: score >= 90  — True: 12 cases, False: 88 cases
#     line 3: score >= 70  — True: 23 cases, False: 65 cases
#     line 4: score >= 50  — True: 31 cases, False: 34 cases
#   All 4 branches covered ✓
```

This is possible because `ast_gen` knows the branches (from compile-time AST analysis) and can instrument the generator to track which branches each input exercises.

## Effectful Functions

Same pattern as `mez sample` — the developer provides handlers:

```mezze
check {
  name = "parse_config handles missing files",
  gen = ast_gen {func = parse_config},
  perform = [MemoryFileSystem, ExceptionToResult],
  prop = {path: Str} ->
    match parse_config {path} is
      Ok _ -> True
      Err _ -> True    # both paths are valid, just no crash
}
```

The `perform` field specifies which handlers to wrap the function call with.

## What Makes This Different From Existing Libraries

Every existing property testing library is blind to the implementation:

| Library | Type-aware | Code-aware | AST analysis |
|---|---|---|---|
| QuickCheck (Haskell) | Yes | No | No |
| Hypothesis (Python) | Yes | No | No |
| fast-check (JS) | Yes | No | No |
| PropEr (Erlang) | Yes | No | No |
| **Mezze property** | **Yes** | **Yes** | **Yes — via comptime + reflection** |

The combination of Mezze's macro system, comptime execution, and reflection API makes it possible for a **userland library** to do what normally requires compiler internals. No special compiler support needed beyond the reflection primitives.

## Relationship to Other Correctness Tools

```
Developer spot-check:     mez sample (built-in tool, visual, interactive)
                              ↓ "looks right, let me verify more thoroughly"
Automated verification:   std::testing::property (library, boolean properties, CI)
                              ↓ "property fails, here's the minimal case"
Deep debugging:           mez replay / trace / live edit (built-in tools)
```

Sample is for quick understanding. Property testing is for thorough verification. Both use the same AST-aware input generation — one as a built-in tool, the other as a library powered by macros and reflection.
