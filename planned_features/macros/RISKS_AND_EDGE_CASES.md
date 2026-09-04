# Macro and Comptime: Risks and Edge Cases

This document catalogs potential bugs, edge cases, and risks for the macro/comptime implementation.

**Session ID for continuation:** `aa22f0e`

---

## Table of Contents

1. [Parsing Edge Cases](#category-1-parsing-edge-cases)
2. [Token <-> AST Round-Trip Bugs](#category-2-token---ast-round-trip-bugs)
3. [Comptime Execution Bugs](#category-3-comptime-execution-bugs)
4. [Macro Execution Bugs](#category-4-macro-execution-bugs)
5. [Ordering Bugs](#category-5-ordering-bugs)
6. [Type System Bugs](#category-6-type-system-bugs)
7. [Caching Bugs](#category-7-caching-bugs)
8. [Error Message Bugs](#category-8-error-message-bugs)
9. [Mitigation Strategies](#mitigation-strategies)
10. [Priority Concerns](#priority-concerns)

---

## Category 1: Parsing Edge Cases

### Nested delimiters in macro calls

```mezze
sql!(SELECT * FROM users WHERE data = '{"name": "test"}')
//                                    ^--- JSON with braces inside string
// Parser needs to track: are we in a string? nested parens?
```

The parser must correctly handle:
- Strings containing delimiter characters
- Nested parentheses, braces, brackets
- Escape sequences within strings

### Unbalanced delimiters

```mezze
list!(1, foo(2, 3)  // missing closing paren
// Where does the macro call end?
```

Need clear error message pointing to the opening `!(`.

### Quote inside quote

```mezze
macro m: {tokens: Tokens} -> Tokens = {tokens} ->
    `(let x = `(~tokens))  // nested quote - what does ~tokens mean here?
```

Questions:
- Does `~` refer to inner or outer quote scope?
- How do we track quote nesting depth?

### Splice outside quote

```mezze
let x = ~tokens  // error, but parser might not catch it
```

This should be a parse error, not a type error.

### Macro call that looks like function call

```mezze
let foo! = 42  // is this valid?
foo!(x)        // macro call or something else?
```

Need clear grammar rules for what `!` means.

---

## Category 2: Token <-> AST Round-Trip Bugs

### Information loss - precedence

```mezze
// Original
let x = 1 + 2 * 3

// As tokens: [let, x, =, 1, +, 2, *, 3]
// Back to AST: how do we know precedence?
// We need to re-parse, not just convert
```

**Solution:** Tokens must be re-parsed through the real parser, not directly converted.

### Comments and whitespace

```mezze
sql!(
    SELECT *
    FROM users  -- get all users
    WHERE id = $(id)
)
// Do comments survive tokenization?
// Does the macro see them?
```

Decision needed:
- Strip comments before macro sees them?
- Preserve for macros that care (documentation generators)?

### String escapes

```mezze
sql!(SELECT * FROM users WHERE name = 'O\'Brien')
// Token stores: O'Brien or O\'Brien?
// When we splice back, do we re-escape?
```

Must be consistent: store raw, escape on output.

### Interpolation in strings

```mezze
sql!(SELECT * FROM users WHERE name = "$(name)")
// Is $(name) part of the string literal token?
// Or separate Splice token?
```

Need clear rules for how string interpolation tokenizes.

### Unicode and special characters

```mezze
sql!(SELECT * FROM users WHERE emoji = '😀')
// Token spans: byte offsets or character offsets?
// What about multi-codepoint graphemes?
```

---

## Category 3: Comptime Execution Bugs

### Infinite loop

```mezze
let x = comptime {eval = {} ->
    let loop = {} -> loop {}
    loop {}
}
// Hangs the compiler
```

**Mitigation:** Timeout for comptime execution (e.g., 30 seconds default, configurable).

### Stack overflow

```mezze
let x = comptime {eval = {} ->
    let f = {n} -> f {n = n + 1}
    f {n = 0}
}
// Crashes the compiler
```

**Mitigation:** Stack depth limit, catch and report as compile error.

### Resource leaks

```mezze
let x = comptime {eval = {} ->
    let file = open_file {path = "big.bin"}
    // no close, comptime fails midway
    crash {msg = "oops"}
}
// File handle leaked during compilation
```

**Mitigation:** Run comptime in isolated context with cleanup on failure.

### Effects that modify state

```mezze
let x = comptime {eval = {} ->
    perform FileSystem in write_file {path = "generated.mz", content = "..."}
}
// Comptime writes files during compilation
// What if compilation fails later? File already written.
// What about caching - rerun writes again?
```

Questions:
- Allow write effects?
- Rollback on failure?
- How does this interact with caching?

### Comptime reads file being compiled

```mezze
// In file foo.mz:
let x = comptime {eval = {} ->
    perform FileSystem in read_file {path = "foo.mz"}
}
// Reads itself - is this okay?
// What if macro modifies and writes it back?
```

### Non-deterministic effects

```mezze
let x = comptime {eval = {} ->
    perform Time in now {}
}
// Different value each compilation
// Breaks reproducible builds
// Breaks caching
```

**Mitigation:** Warn or error on non-deterministic effects, or mark cache as non-cacheable.

### Effect handler not available

```mezze
let x = comptime {eval = {} ->
    perform MyCustomEffect in do_thing {}
}
// Who provides MyCustomEffect at compile time?
// Error: no handler for MyCustomEffect in comptime context
```

Need clear error message about which effects are available at compile time.

---

## Category 4: Macro Execution Bugs

### Macro returns invalid tokens

```mezze
macro bad: {tokens: Tokens} -> Tokens = {tokens} ->
    // Returns tokens that don't form valid Mezze syntax
    concat {a = `(let x =), b = `(if)}  // "let x = if" - invalid
```

**Mitigation:** Validate/parse returned tokens before splicing into AST.

### Macro returns tokens with wrong spans

```mezze
macro m: {tokens: Tokens} -> Tokens = {tokens} ->
    // Manually constructs tokens with bogus spans
    Token.Ident {name = "x", span = {file = "wrong.mz", line = 9999}}
```

Errors will point to wrong locations. Hard to prevent entirely.

### Macro diverges based on input

```mezze
macro weird: {tokens: Tokens} -> Tokens = {tokens} ->
    if is_empty {tokens} then
        loop {}  // infinite loop for empty input
    else
        tokens

weird!()  // hangs compiler
```

**Mitigation:** Same timeout as comptime.

### Macro with side effects

```mezze
macro count: {tokens: Tokens} -> Tokens = {tokens} ->
    let n = comptime {eval = {} ->
        let c = read_file {path = "counter.txt"} |> parse_int
        write_file {path = "counter.txt", content = "$(c + 1)"}
        c
    }
    `(~n)
// Cached or not? Counter increments how many times?
```

The comptime inside the macro runs at macro definition time. But if we cache the macro... confusion.

### Macro calls itself

```mezze
macro recursive: {tokens: Tokens} -> Tokens = {tokens} ->
    recursive!(~tokens)  // infinite expansion
```

**Mitigation:** Expansion depth limit.

### Macro generates macro definition

```mezze
macro meta: {tokens: Tokens} -> Tokens = {tokens} ->
    `(macro generated: {tokens: Tokens} -> Tokens = {tokens} -> tokens)

meta!()  // creates a new macro... is this allowed?
```

Decision needed: can macros generate macro definitions?

---

## Category 5: Ordering Bugs

### Macro uses function defined later

```mezze
macro m: {tokens: Tokens} -> Tokens = {tokens} ->
    let result = helper {tokens}  // helper defined below
    `(~result)

let helper = {tokens: Tokens} -> Tokens = ...

m!(foo)  // does this work?
```

In Stage 1 (macro compilation), is `helper` available?

### Circular macro through comptime

```mezze
macro a: {tokens: Tokens} -> Tokens = {tokens} ->
    comptime {eval = {} -> b!(tokens)}

macro b: {tokens: Tokens} -> Tokens = {tokens} ->
    a!(tokens)
```

Need cycle detection across macro + comptime boundaries.

### Comptime depends on macro expansion

```mezze
let x = comptime {eval = {} -> list!(1, 2, 3) |> length}
// comptime body contains macro call
// need to expand macro before executing comptime
```

Order: expand macros in comptime body, then execute comptime.

### Macro defined in terms of comptime that uses the macro

```mezze
let config = comptime {eval = {} -> m!(get_config)}

macro m: {tokens: Tokens} -> Tokens = {tokens} ->
    `(~config)  // uses config which uses m!
```

Circular dependency - need to detect and error.

### Import order affects macro availability

```mezze
// a.mz
use b::{helper_macro}
macro m: {tokens: Tokens} -> Tokens = {tokens} -> helper_macro!(~tokens)

// b.mz
use a::{m}  // circular import
macro helper_macro: {tokens: Tokens} -> Tokens = {tokens} -> m!(~tokens)
```

Should be caught by existing circular import detection.

---

## Category 6: Type System Bugs

### Macro output has different type than expected

```mezze
let x: Int = sql!(SELECT name FROM users)
// sql! returns Seq {name: String}
// Type error - but where does it point?
```

Error should point to `sql!(...)` call, not generated code.

### Generic macros

```mezze
macro identity: {tokens: Tokens} -> Tokens = {tokens} -> tokens

let x: Int = identity!(42)
let y: String = identity!("hello")
// The macro is the same, but output types differ
// This should work
```

Macros are untyped token transformers - type checking happens after expansion.

### Comptime result type inference

```mezze
let x = comptime {eval = {} ->
    if some_condition {} then 42 else "hello"
}
// What's the type of x?
// Runtime: could be Int or String
// Comptime: we execute it, we know which one
```

The type is whatever the actual result is. But what if condition depends on environment?

### Comptime returns value that can't be a literal

```mezze
let f = comptime {eval = {} ->
    {x} -> x + 1  // returns a function
}
// Can't represent function as AST literal
```

**Mitigation:** Error - comptime must return representable value.

### Type of Tokens

```mezze
macro m: {tokens: Tokens} -> Tokens = {tokens} ->
    let x: Int = tokens  // type error
```

`Tokens` is its own type, not convertible to other types.

---

## Category 7: Caching Bugs

### Cache key collision

```mezze
// File A
sql!(SELECT * FROM users)

// File B
sql!(SELECT * FROM users)

// Same input tokens, but different schema files imported?
// Cache needs to include all dependencies in key
```

Cache key must include:
- Macro name
- Input tokens
- All comptime effect dependencies (files read, env vars, etc.)

### Stale cache after git checkout

```
Developer A: schema v1, compiles, caches
Developer B: git pull (gets A's cache + new schema v2)
B compiles: cache hit, but wrong schema!
```

**Mitigation:** Include file content hashes, not just timestamps.

### Comptime modifies its own dependency

```mezze
let x = comptime {eval = {} ->
    let config = read_file {path = "config.json"}
    write_file {path = "config.json", content = "{}"}  // modifies it!
    config
}
// First compile: reads original, writes empty
// Second compile: reads empty (cache invalid? or stale?)
// Cache invalidation loop?
```

**Mitigation:** Disallow writing to files that were read? Or just accept non-determinism.

### Parallel compilation cache races

```
Thread 1: compiling module A, starts comptime
Thread 2: compiling module B, same comptime, starts
Thread 1: finishes, writes cache
Thread 2: finishes, overwrites cache
```

**Mitigation:** Atomic cache writes, or lock per cache key.

### Cache versioning

```
Compiler v1.0: caches macro results
Upgrade to v1.1: macro semantics changed
Old cache still present, wrong results
```

**Mitigation:** Include compiler version in cache key.

### Cache corruption

```
Power failure mid-write to cache database
Next compile: reads corrupted entry
Crash or wrong results
```

**Mitigation:** Atomic writes, checksums, graceful recovery.

---

## Category 8: Error Message Bugs

### Error in generated code points nowhere useful

```mezze
// sql! generates:
//   let __query = prepare {sql = "..."}
//   let __result = execute {query = __query, params = {id}}
//   __result
//
// If "id" is not in scope:
// Bad: "undefined variable 'id' at <generated>:2:52"
// Good: "undefined variable 'id' in sql!() call at user.mz:10"
```

Need span remapping from generated code back to macro call.

### Span corruption through token manipulation

```mezze
macro m: {tokens: Tokens} -> Tokens = {tokens} ->
    let a = first {tokens}
    let b = rest {tokens}
    concat {a = b, b = a}  // swapped order

m!(x + y)
// Tokens for x now have span for y's position
// Error says "x" but highlights "y"
```

Hard to prevent - macro authors can create invalid spans.

### Nested macro error stack

```mezze
macro outer: {tokens: Tokens} -> Tokens = {tokens} ->
    inner!(~tokens)

macro inner: {tokens: Tokens} -> Tokens = {tokens} ->
    crash {msg = "bad input"}

outer!(foo)
// Stack: outer -> inner -> crash
// Need to show all three locations
```

Error should show macro expansion stack, not just final location.

### Error in quote vs splice

```mezze
macro m: {tokens: Tokens} -> Tokens = {tokens} ->
    `(let x = ~(undefined_function {tokens}))
//            ^--- error here

// Error: "undefined_function not in scope"
// Which line? The macro definition or the call site?
```

Error is in macro definition - should point there.

### Compile error vs macro error

```mezze
macro validate: {tokens: Tokens} -> Tokens = {tokens} ->
    if not (is_valid {tokens}) then
        compile_error {msg = "Invalid syntax", span = span_of {tokens}}
    else
        tokens

validate!(bad stuff here)
```

The `compile_error` is intentional - should show nicely, not as "macro crashed".

### Error message from macro is unhelpful

```mezze
macro m: {tokens: Tokens} -> Tokens = {tokens} ->
    crash {msg = "error"}  // no context

m!(complex expression here)
// User sees: "error"
// No idea what went wrong
```

Macro authors need to write good errors. We can't force this.

---

## Mitigation Strategies

| Risk | Mitigation |
|------|------------|
| Parsing edge cases | Extensive test suite, fuzzing, handle all delimiter types |
| Round-trip bugs | Property-based testing: `parse(unparse(tokens)) == original` |
| Infinite loops | Timeout for comptime/macro execution (configurable) |
| Stack overflow | Stack depth limit, catch and report as compile error |
| Resource leaks | Run comptime in isolated context with cleanup on failure |
| Invalid macro output | Parse/validate returned tokens before splicing |
| Ordering bugs | Explicit dependency graph, cycle detection, clear errors |
| Type errors in generated code | Span remapping table from generated to source |
| Cache key collision | Include all dependencies in key (files, env, compiler version) |
| Cache corruption | Atomic writes, checksums, graceful recovery |
| Stale cache | Content hashes not timestamps, compiler version in key |
| Bad error messages | Show macro expansion stack, remap spans, test extensively |

---

## Priority Concerns

### Most Worried About

1. **Span tracking** - If we lose spans anywhere in the pipeline, error messages become useless. This must be correct from day one.

2. **Token round-tripping** - Subtle bugs in tokenize/parse cycle can cause hard-to-debug issues. Need extensive property-based testing.

3. **Ordering and dependencies** - Complex interactions between macros, comptime, and regular code. Need formal model of what depends on what.

4. **Cache correctness** - Cache bugs are silent - wrong results with no error. Conservative invalidation is safer than aggressive caching.

### Less Worried About

1. **Performance** - Can optimize later, correctness first.

2. **Macro authors writing bad errors** - Not our bug, but we should provide good tools.

3. **Exotic edge cases** - Handle common cases well, exotic cases can error.

---

## Testing Strategy

### Unit Tests

- Every token type serializes/deserializes correctly
- Spans preserved through all operations
- Each edge case above has a dedicated test

### Property-Based Tests

- `parse(tokenize(code)) == parse(code)` for random valid code
- `unparse(parse(code))` produces equivalent code
- Macro expansion is deterministic (same input = same output)

### Integration Tests

- Each example in this document becomes a test case
- Error message tests - verify exact error output
- Cache invalidation tests - modify files, verify recompilation

### Fuzzing

- Random token sequences into macros
- Random code into comptime
- Look for crashes, hangs, panics

---

## Open Questions (Updated)

| Question | Decision | Rationale |
|----------|----------|-----------|
| Can macros generate macro definitions? | TBD | Adds complexity, defer to v2 |
| Should we allow write effects in comptime? | YES | Type says `<*>`, user responsible |
| How do we handle non-deterministic effects in cache? | Mark as non-cacheable | Time/Random/Network invalidate cache |
| What's the timeout for macro/comptime execution? | 30 seconds default | Configurable via compiler flag |
| How deep can macro expansion nest? | 100 levels | Catch infinite expansion |
| Should comments be visible to macros? | TBD | Probably strip, but TBD |

---

## Simplifications from In-Memory Approach

The revised "in-memory evaluation" approach (see COMPTIME_MACRO_IMPLEMENTATION_PLAN.md) reduces some risks:

| Risk | Original Concern | New Status |
|------|-----------------|------------|
| CallTarget lifecycle | Managing stored macros | Eliminated - no stored CallTargets |
| Two-phase coordination | Stage 1 vs Stage 2 | Eliminated - single phase |
| Macro compilation errors | Separate from usage | Eliminated - errors in context |
| Stale CallTargets | Cache invalidation | Simplified - just AST caching |

---

## Session Reference

This document was created during planning session `aa22f0e`.

To continue this work:
```
Resume agent aa22f0e to continue macro/comptime implementation planning
```
