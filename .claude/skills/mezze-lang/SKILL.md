---
name: mezze-lang
description: Reference for the Mezze programming language syntax, type system, abilities, effects, and standard library. Use when writing, reading, reviewing, or debugging .mz files, or when answering questions about Mezze language features.
user-invocable: false
---

# Mezze Language Reference

Mezze is a statically-typed functional language on GraalVM/Truffle with Hindley-Milner inference, row polymorphism, algebraic effects, and ability-based (typeclass) polymorphism.

## Core Syntax Rules

### Comments
```mezze
# This is a comment. Only # comments exist. No // or /* */
```

### Literals
```mezze
42              # Int
3.14            # Float
"hello"         # Str
True            # Bool (uppercase)
False           # Bool (uppercase)
'c'             # Char
42n             # Nat (natural number suffix)
1//2            # Frac (fraction literal, NOT division)
[]              # empty Seq
[1, 2, 3]      # Seq (sequence/array)
{}              # unit value (empty record)
```

### Variable Declarations
```mezze
let x = 42
let name = "Alice"
let add = {x, y} -> x + y

# With type annotation
let x: Int = 42
let f: {x: Int, y: Int} -> Int = {x, y} -> x + y

# Public (exported)
pub let my_func = {x} -> x + 1

# Destructuring
let {name, age} = user
let [head, ...tail] = items
```

### Functions (Lambdas)

ALL functions take a record argument with named fields. No positional arguments.

```mezze
# Definition: {params} -> body
let square = {x} -> x * x
let add = {x, y} -> x + y
let greet = {name: Str} -> "Hello $(name)"

# Calling: always use named record arguments
square {x = 5}
add {x = 3, y = 5}
greet {name = "Alice"}

# Multi-line with do block
let process = {x, y} -> do
  let sum = x + y
  let doubled = sum * 2
  doubled    # last expression is return value
```

NEVER write: `f(x)`, `f x`, `f(1, 2)`. ALWAYS write: `f {x = 1}`, `f {x = 1, y = 2}`.

### Record Punning
When a variable name matches the field name, use shorthand:
```mezze
let name = "Alice"
let age = 30
let user = {name, age}          # same as {name = name, age = age}
f {name, age}                   # same as f {name = name, age = age}
```

### Records
```mezze
# Literal
let user = {name = "Alice", age = 30}

# Field access
user.name
user.age

# Spread/update (spread MUST come first)
let updated = {...user, age = 31}
let copy = {...user}

# Type annotation
type User = {name: Str, age: Int}
```

### Variants (Tagged Unions)
```mezze
# Closed variant (all constructors known)
type Color = | Red, Blue {val: Int}, Green |
type Option 'a = | Some {val: 'a}, None |
type Result 'a 'e = | Ok {val: 'a}, Err {error: 'e} |

# Open variant (extensible)
type LogLevel = | Error, Warn, Info, Debug, ... |

# Constructing
let color = Red
let x = Some {val = 42}
let result = Ok {val = "success"}
```

### Pattern Matching
```mezze
# match uses the `is` keyword, NOT { } or `with`
let describe = {color} -> match color is
  Red -> "red"
  Blue {val} -> "blue $(val)"
  Green -> "green"

# With guards
let classify = {n} -> match n is
  x if x > 0 -> "positive"
  x if x < 0 -> "negative"
  _ -> "zero"

# Destructuring records
let name = match user is
  {name, age} -> name

# Seq patterns
let result = match items is
  [] -> "empty"
  [only] -> "one item"
  [first, ...rest] -> "multiple items"

# String interpolation patterns
let parse = {s} -> match s is
  "hello $(name)" -> name
  _ -> "unknown"

# Wildcard makes variant open (no exhaustiveness error)
let handle = {event} -> match event is
  Click -> "clicked"
  _ -> "other"
```

### If-Then-Else (expressions, not statements)
```mezze
let max = {x, y} -> if x > y then x else y
```

### Do Blocks (sequencing)
```mezze
let process = {name} -> do
  let greeting = "Hello $(name)"
  Log.println {msg = greeting}
  greeting    # last expression is the return value
```

### String Interpolation
```mezze
# Uses $() NOT ${} or #{}
"Hello $(name)"
"Sum is $(x + y)"
"Result: $(int::to_str {n = x})"
```

### Pipe Operator `|>`

Pipes a value into a function call. The `@` placeholder marks where the piped value goes. **Exactly one `@` is required** — zero or more than one is a parse error.

```mezze
# Basic: value |> function {param = @}
5 |> square {x = @}                      # square {x = 5}

# Chaining pipes (left to right, reads naturally)
"hello"
  |> str::to_upper {s = @}
  |> str::repeat {s = @, n = 3}

# Building collections without dot methods
map::empty {}
  |> map::insert {m = @, k = "a", v = 1}
  |> map::insert {m = @, k = "b", v = 2}

# @ can appear in any argument position
10 |> int::clamp {n = @, min = 0, max = 5}   # clamp 10 to range [0,5] = 5

# Pipe vs dot chaining — prefer dot chaining when the type has methods
# PREFERRED:
items.filter {p = {x} -> x > 0}.map {f = {x} -> x * 2}
# ALSO OK (pipe style):
items |> seq::filter {xs = @, p = {x} -> x > 0}
      |> seq::map {xs = @, f = {x} -> x * 2}
```

**When to use pipe vs dot chaining:**
- **Dot chaining** — preferred when the type has inherent impl methods (most types do)
- **Pipe** — useful for building values through qualified module functions, or when there's no inherent impl

### Dot Chaining (method syntax)
```mezze
# receiver.method {args} — receiver becomes `it` parameter
"hello".to_upper {}
[1, 2, 3].map {f = {x} -> x * 2}.filter {p = {x} -> x > 2}
42.to_str {}
user.name     # field access (no braces needed)

# Multi-line chaining (newline before . continues the expression)
users
  .filter {p = {x} -> x.age > 18}
  .map {f = {x} -> x.name}
  .sort {}
```

### Partial Application (Currying)

Provide some named arguments, get back a function expecting the rest. Works with **any** argument — not just the last one (unlike most languages).

```mezze
let add = {x, y} -> x + y

# Provide x, get a function expecting y
let add5 = add {x = 5}       # type: {y: Int} -> Int
add5 {y = 10}                 # 15

# Provide y instead — works just as well
let add_to_10 = add {y = 10}  # type: {x: Int} -> Int
add_to_10 {x = 3}             # 13

# Multi-argument partial application
let make_user = {name, age, role} -> {name, age, role}
let make_admin = make_user {role = "admin"}  # type: {name: Str, age: Int} -> ...
make_admin {name = "Alice", age = 30}

# Useful with higher-order functions
let multiply = {x, y} -> x * y
let double = multiply {y = 2}
[1, 2, 3].map {f = {x} -> double {x}}    # [2, 4, 6]

# Partial application creates a new function, NOT a call
let f = {a, b, c} -> a + b + c
let g = f {a = 1}             # g : {b: Int, c: Int} -> Int
let h = g {b = 2}             # h : {c: Int} -> Int
h {c = 3}                     # 6
```

### Newtype (zero-cost wrapper)
```mezze
newtype UserId = Int
newtype Email = Str
```

### Imports
```mezze
use std::int                              # import module
use std::collections::seq::{map, filter}  # import specific items
use std::effects::log::{Log, Console, println}
```

## Operators — COMPLETE LIST

### Binary Operators (14 total)
All operators desugar to ability method calls. `x + y` becomes `x.add {r = y}`.

| Operator | Ability | Method | Example |
|----------|---------|--------|---------|
| `+` | `NumAdd` | `add` | `x + y` = `x.add {r = y}` |
| `-` | `NumSub` | `sub` | `x - y` = `x.sub {r = y}` |
| `*` | `NumMul` | `mul` | `x * y` = `x.mul {r = y}` |
| `/` | `NumDiv` | `div` | `x / y` = `x.div {r = y}` |
| `%` | `NumMod` | `mod` | `x % y` = `x.mod {r = y}` |
| `==` | `Eq` | `eq` | `x == y` = `x.eq {r = y}` |
| `!=` | `Eq` | `neq` | `x != y` = `x.neq {r = y}` |
| `<` | `Ord` | `lt` | `x < y` = `x.lt {r = y}` |
| `<=` | `Ord` | `le` | `x <= y` = `x.le {r = y}` |
| `>` | `Ord` | `gt` | `x > y` = `x.gt {r = y}` |
| `>=` | `Ord` | `ge` | `x >= y` = `x.ge {r = y}` |
| `&&` | `LogicalAnd` | `and` | `x && y` = `x.and {r = y}` |
| `\|\|` | `LogicalOr` | `or` | `x \|\| y` = `x.or {r = y}` |
| `\|>` | (pipe) | n/a | `x \|> f {a = @}` |

### Unary Operators (2 total)
| Operator | Ability | Method | Example |
|----------|---------|--------|---------|
| `-` (prefix) | `NumSub` | `neg` | `-x` = `x.neg {}` |
| `!` | `LogicalNot` | `not` | `!x` = `x.not {}` |

### Operator Precedence (highest to lowest)
1. Unary: `-x`, `!x`
2. Multiplicative: `*`, `/`, `%`
3. Additive: `+`, `-`
4. Comparison: `==`, `!=`, `<`, `>`, `<=`, `>=`
5. Logical AND: `&&`
6. Logical OR: `||`
7. Pipe: `|>`

### NOT Valid Operators (will cause parse errors)
- `++` — does not exist. Use string interpolation: `"$(a)$(b)"`
- `--` — does not exist. Use `x - 1`
- `**` — does not exist. Use `math::pow {base = x, exp = n}` or `x.pow {exp = n}`
- `//` — NOT division. `1//2` creates a Frac literal
- `+=`, `-=`, `*=` — no mutation operators
- `?` — no optional chaining
- `??` — no null coalescing (use `Option` with `match` or `get_or_else`)
- `..` or `...` — not range operators (spread only in records/seqs)
- `::` — not cons operator, it's the module path separator
- `<>` — does not exist
- `^` — does not exist
- `&`, `|` — not bitwise operators (only `&&` and `||` for logical)
- `:=` — no assignment operator

### String Concatenation
There is NO string concatenation operator. Use string interpolation:
```mezze
# WRONG: "hello" ++ " world"
# WRONG: "hello" + " world"
# CORRECT:
let a = "hello"
let b = "world"
let result = "$(a) $(b)"
```

## Type System

### Built-in Types
`Bool`, `Int`, `Nat`, `Float`, `Frac`, `Str`, `Char`, `Bytes`

### Collection Types
- `['a]` — Seq (indexed sequence). Written with square brackets.
- `List 'a` — linked list
- `Map 'k 'v` — hash map
- `Set 'a` — hash set
- `Deque 'a` — double-ended queue

### Option and Result
```mezze
# Option 'a = | Some {val: 'a}, None |
let x = Some {val = 42}
let y = None

# Result 'a 'e = | Ok {val: 'a}, Err {error: 'e} |
let success = Ok {val = 42}
let failure = Err {error = "not found"}
```

### Type Variables
Use `'a`, `'b`, etc. — NOT `T`, `A`, `<T>`, or `a`.

### Record Types — Open vs Closed (Row Polymorphism)

**Closed records** have exactly the fields specified — no more, no less:
```mezze
type Point = {x: Int, y: Int}

let p: Point = {x = 1, y = 2}           # OK
let q: Point = {x = 1, y = 2, z = 3}    # ERROR — extra field z
```

**Open records** accept extra fields beyond what's specified (using `...`):
```mezze
# This function works on ANY record that has a `name` field
let get_name = {user: {name: Str, ...}} -> user.name

get_name {user = {name = "Alice", age = 30}}              # OK — age is ignored
get_name {user = {name = "Bob", age = 25, role = "dev"}}  # OK — extra fields fine
get_name {user = {name = "Carol"}}                        # OK — exact match also fine
```

**Open records in function parameters** — use `...` when you don't care about extra fields:
```mezze
# Accepts any record with at least x and y
let distance = {point: {x: Float, y: Float, ...}} ->
  (point.x * point.x + point.y * point.y).sqrt {}

# Works with 2D point, 3D point, or anything with x and y
distance {point = {x = 3.0, y = 4.0}}                    # 5.0
distance {point = {x = 3.0, y = 4.0, z = 5.0}}           # 5.0 — z ignored
```

**Record pattern matching** — destructuring can also be open or closed:
```mezze
# Closed pattern — must match ALL fields
let {x, y} = point              # ERROR if point has more fields than x, y

# Open pattern — extract some fields, ignore rest
let {name, ...} = user           # OK — only binds name, ignores other fields
```

### Variant Types — Open vs Closed

**Closed variants** list ALL possible constructors — pattern matching must be exhaustive:
```mezze
type Color = | Red, Green, Blue |

# Must handle ALL cases — missing one is a type error
let describe = {c: Color} -> match c is
  Red -> "red"
  Green -> "green"
  Blue -> "blue"
  # No wildcard needed — all constructors covered
```

**Open variants** can have additional constructors beyond those listed:
```mezze
type LogLevel = | Error, Warn, Info, Debug, ... |

# Wildcard `_` is REQUIRED — there could be other constructors
let is_severe = {level: LogLevel} -> match level is
  Error -> True
  Warn -> True
  _ -> False      # handles Info, Debug, and any future levels
```

**Pattern matching determines openness:**
- **No wildcard** in match → variant is treated as closed, all constructors must be covered
- **Wildcard `_`** at top level → variant is treated as open, other constructors allowed
```mezze
# Closed — type error if you miss a case
let handle = {event} -> match event is
  Click {x, y} -> "clicked at $(x), $(y)"
  Hover {x, y} -> "hovering at $(x), $(y)"
  # Every constructor must be listed

# Open — wildcard catches unknown constructors
let handle = {event} -> match event is
  Click {x, y} -> "clicked"
  _ -> "other event"     # makes the variant open
```

**Returning different constructors from match creates variant unions:**
```mezze
# The return type is the union: | Dog {breed: Str}, Cat {name: Str} |
let to_pet = {color} -> match color is
  Red -> Dog {breed = "Retriever"}
  Blue -> Cat {name = "Whiskers"}
```

### Type Aliases Preserve Open/Closed

Type aliases remember whether they were declared open or closed. This matters for what you can pass and how you pattern match.

**Closed variant alias — exhaustive matching required:**
```mezze
type Color = | Red, Green, Blue |

# MUST cover all cases — Color is closed
let name = {c: Color} -> match c is
  Red -> "red"
  Green -> "green"
  Blue -> "blue"

# This is a type error — extra field:
# let c: Color = Purple  # ERROR — Purple not in Color
```

**Open variant alias — wildcard OK, extensible:**
```mezze
type LogLevel = | Error, Warn, Info, Debug, ... |

# Wildcard handles unknown levels — LogLevel is open
let is_severe = {level: LogLevel} -> match level is
  Error -> True
  Warn -> True
  _ -> False

# Can create values with constructors not in the original declaration
let level: LogLevel = Trace    # OK — LogLevel is open
```

**Closed record alias — exact fields required:**
```mezze
type Point = {x: Int, y: Int}

let p: Point = {x = 1, y = 2}               # OK
let q: Point = {x = 1, y = 2, z = 3}        # ERROR — extra field z not allowed

# Function taking Point only accepts {x, y}, nothing more
let distance = {p: Point} -> p.x * p.x + p.y * p.y
distance {p = {x = 3, y = 4}}               # OK
distance {p = {x = 3, y = 4, z = 5}}        # ERROR
```

**Open record alias — extra fields allowed:**
```mezze
type HasName = {name: Str, ...}

let greet = {user: HasName} -> "Hello $(user.name)"

greet {user = {name = "Alice"}}                         # OK
greet {user = {name = "Alice", age = 30}}               # OK — extra field fine
greet {user = {name = "Alice", age = 30, role = "dev"}} # OK — any extra fields
```

**Summary:**

| Declaration | Open/Closed | Extra fields/constructors | Pattern matching |
|-------------|-------------|--------------------------|------------------|
| `type T = \| A, B \|` | Closed variant | Rejected | Must cover all cases |
| `type T = \| A, B, ... \|` | Open variant | Allowed | Wildcard OK |
| `type T = {x: Int, y: Int}` | Closed record | Rejected | Must match all fields |
| `type T = {x: Int, y: Int, ...}` | Open record | Allowed | Can ignore extra fields |

### Function Types
```mezze
{x: Int} -> Str                       # pure function
{x: Int} -> Str <Log>                 # function with Log effect
{x: 'a, y: 'a} -> 'a with NumAdd 'a  # with ability constraint
{point: {x: Int, y: Int, ...}} -> Int # open record parameter
```

### Type Application
```mezze
Option Int
Result Str Error
Map Str Int
List (Option Int)     # parentheses for nested application
```

## Collections

All collections are **immutable** by default. Operations return new collections.

### Seq `['a]` — the default collection (indexed, like an array)
Prefer Seq for most use cases. Written with square brackets. Backed by optimized native arrays.

```mezze
use std::collections::seq

# Literal syntax
let nums = [1, 2, 3, 4, 5]
let empty = []

# Prefer dot chaining
nums.map {f = {x} -> x * 2}             # [2, 4, 6, 8, 10]
nums.filter {p = {x} -> x > 2}          # [3, 4, 5]
nums.fold {init = 0, f = {acc, x} -> acc + x}  # 15

# Chaining (preferred style)
[1, 2, 3, 4, 5]
  .filter {p = {x} -> x % 2 == 0}
  .map {f = {x} -> x * 10}              # [20, 40]

# Access (dot chaining)
nums.get {i = 0}                         # Some {val = 1}
nums.head {}                             # Some {val = 1}
nums.last {}                             # Some {val = 5}
nums.tail {}                             # [2, 3, 4, 5]
nums.length {}                           # 5

# Modification (returns new Seq)
nums.push {x = 6}                        # [1, 2, 3, 4, 5, 6]
nums.push_front {x = 0}                  # [0, 1, 2, 3, 4, 5]
nums.concat {ys = [6, 7]}                # [1, 2, 3, 4, 5, 6, 7]
nums.set {i = 0, x = 99}                 # Some {val = [99, 2, 3, 4, 5]}

# Slicing
nums.take {n = 3}                        # [1, 2, 3]
nums.drop {n = 2}                        # [3, 4, 5]
nums.slice {start = 1, end = 3}          # [2, 3]

# Search
nums.find {p = {x} -> x > 3}            # Some {val = 4}
nums.contains {x = 3}                   # True
nums.any {p = {x} -> x > 4}             # True
nums.all {p = {x} -> x > 0}             # True

# Pattern matching on Seq
match items is
  [] -> "empty"
  [only] -> "one: $(only)"
  [first, second, ...rest] -> "first: $(first), rest has more"
```

### List `List 'a` — immutable linked list
Use when you need efficient prepend and recursive head/tail processing.

```mezze
use std::collections::list

let xs = list::cons {head = 1, tail = list::cons {head = 2, tail = list::empty {}}}

# Prefer dot chaining
xs.map {f = {x} -> x * 2}
xs.filter {p = {x} -> x > 1}
xs.fold_left {init = 0, f = {acc, x} -> acc + x}

# Deconstruction
xs.head {}                 # Some {val = 1}
xs.tail {}                 # Some {val = List(2)}
xs.uncons {}               # Some {head = 1, tail = List(2)}
xs.length {}               # 2
```

### Map `Map 'k 'v` — immutable hash map
```mezze
use std::collections::map

# Build with pipe (map has no literal syntax)
let m = map::empty {}
  |> map::insert {m = @, k = "alice", v = 100}
  |> map::insert {m = @, k = "bob", v = 200}

# Prefer dot chaining for queries
m.get {k = "alice"}                        # Some {val = 100}
m.contains_key {k = "bob"}                 # True
m.size {}                                  # 2
m.keys {}                                  # List of keys
m.values {}                                # List of values

# Higher-order (dot chaining)
m.map_values {f = {val} -> val * 2}
m.filter {p = {k, v} -> v > 150}
m.fold {init = 0, f = {acc, k, v} -> acc + v}

# Merge
m.merge {m2 = other_map}
m.merge_with {m2 = other, f = {l, r} -> l + r}  # resolve conflicts
```

### Set `Set 'a` — immutable hash set
```mezze
use std::collections::set

# Build with pipe (set has no literal syntax)
let s = set::empty {}
  |> set::insert {s = @, x = 1}
  |> set::insert {s = @, x = 2}
  |> set::insert {s = @, x = 3}

# Prefer dot chaining
s.contains {x = 2}                        # True
s.size {}                                  # 3

# Set operations (dot chaining)
a.union {s2 = b}
a.intersection {s2 = b}
a.difference {s2 = b}
a.is_subset {s2 = b}

# Conversion
set::from_seq {xs = [1, 2, 2, 3]}         # Set {1, 2, 3}
s.to_seq {}                                # [1, 2, 3]
```

### Which Collection to Use

| Need | Use | Why |
|------|-----|-----|
| General purpose, indexed access | `['a]` (Seq) | Default choice. Optimized native arrays. |
| Prepend-heavy, recursive processing | `List 'a` | O(1) cons, natural pattern matching |
| Key-value lookup | `Map 'k 'v` | O(1) amortized get/insert |
| Unique elements, membership test | `Set 'a` | O(1) contains, set operations |
| Sorted key-value | `SortedMap 'k 'v` | Ordered iteration |
| Sorted unique elements | `SortedSet 'a` | Ordered iteration |
| Double-ended queue | `Deque 'a` | Efficient push/pop both ends |
| Non-empty guarantee | `NonEmptyList 'a` | Head always exists, no Option needed |

## Abilities (Typeclasses)

### Declaring
```mezze
ability Name for 'a where {
  method1: {it: 'a, param: Type} -> ReturnType,
  method2: {it: 'a} -> ReturnType
}
```
`it` is the explicit receiver parameter. Always present in methods.

### Implementing (Named — for a specific ability)
```mezze
impl Eq for MyType where {
  eq = {it, r} -> ...,
  neq = {it, r} -> ...
}

# Polymorphic impl
impl Eq 'a for Option 'a where {
  eq = {it, r} -> ...,
  neq = {it, r} -> ...
}
```

### Implementing (Inherent — methods on a type, no ability)
```mezze
impl Int where {
  to_str = {it} -> __native_int_to_str {n = it},
  abs = {it} -> __native_int_abs {n = it},
  is_even = {it} -> (it % 2) == 0
}
```

### Standard Abilities (auto-available in prelude)
- `NumAdd`, `NumSub`, `NumMul`, `NumDiv`, `NumMod` — arithmetic
- `NumZero`, `NumOne` — numeric identities
- `Eq` — equality (`eq`, `neq`)
- `Ord` — ordering (`lt`, `le`, `gt`, `ge`)
- `LogicalAnd`, `LogicalOr`, `LogicalNot` — boolean logic
- `Into 'b` — infallible conversion
- `TryInto 'b 'err` — fallible conversion (returns `Result`)
- `Default` — default value construction
- `Inspect` — auto-derived for ALL types, used in string interpolation

## Effects

### Declaring
```mezze
effect Log where {
  log: {level: LogLevel, msg: Str} -> {} <Log>
}

# With associated types
effect Exception having 'error where {
  throw: {error: 'error} -> 'a <Exception>
}
```

### Implementing (Handler/Performer)
```mezze
# Simple handler
impl Log for Console where {
  log = {level, msg} -> __native_console_print {msg = format {level, msg}}
}

# Parameterized handler
impl Log for ConsoleWith {formatter: {level: LogLevel, msg: Str} -> Str} where {
  log = {level, msg} -> __native_console_print {msg = formatter {level, msg}}
}

# Scoped handler (with continuations)
impl Exception for ExceptionToResult having 'error = 'e where {
  initialize_scope = {} -> {cont = continuation::new {}},
  finalize_scope = {} -> scope.cont.reset {},
  throw = {error} -> scope.cont.suspend_with {value = Err {error}}
}
```

### Using Effects (perform)
```mezze
# Single handler
perform Console in do
  Log.println {msg = "Hello"}

# Multiple handlers
perform Console, ExceptionToResult in do
  let data = risky_operation {}
  Log.println {msg = "Got: $(data)"}

# Parameterized handler
perform ConsoleWith {formatter = color_formatter} in
  Log.info {msg = "colored output"}
```

### Effect Annotations in Types
```mezze
{x: Int} -> Int              # pure (no effects)
{x: Int} -> Int <Log>        # uses Log effect
{x: Int} -> Int <Log, Mut>   # uses Log and Mut effects
{x: Int} -> Int <...>        # polymorphic effects
```

### Writing Custom Effects

**Step 1: Declare the effect** — define what operations it has:
```mezze
effect Counter where {
  increment: {} -> {} <Counter>,
  get_count: {} -> Int <Counter>
}
```

**Step 2: Write a simple handler** — implement the operations:
```mezze
# Simple (stateless) handler — each operation is independent
impl Counter for InMemoryCounter where {
  increment = {} -> ...,
  get_count = {} -> ...
}
```

But most custom effects need **state** across operations (e.g., a counter needs to track its value). That's what scoped effects are for.

**Step 3: Scoped handler (with state)** — use `initialize_scope` and `finalize_scope`:
```mezze
use std::control::continuation
use std::control::continuation::{Continuation}
use std::effects::async::atom::{Atom, atom, deref, swap}

impl Counter for AtomicCounter where {
  # Called ONCE when entering `perform AtomicCounter in ...`
  # Returns the scope state — available as `scope` in all operations
  initialize_scope = {} -> {count = atom {value = 0}},

  # Called ONCE when exiting the perform block (cleanup)
  finalize_scope = {} -> {},

  # Operations can access `scope` to read/write shared state
  increment = {} -> swap {atom = scope.count, f = {current} -> current + 1},
  get_count = {} -> deref {atom = scope.count}
}
```

**How scoped effects work:**
- `initialize_scope = {} -> state` — runs before the body, returns a value that becomes `scope`
- `finalize_scope = {} -> ...` — runs after the body (cleanup), can access `scope`. Use for resource cleanup: closing DB pools, flushing buffers, releasing connections, etc.
- All operations can access `scope` — the state returned by `initialize_scope`
- The `scope` variable is magically available in operation bodies

**Resource management example** — initialize_scope acquires, finalize_scope releases:
```mezze
effect Database where {
  query: {sql: Str} -> ['row] <Database>,
  execute: {sql: Str} -> Int <Database>
}

impl Database for PostgresDB {connection_string: Str} where {
  # Acquire connection pool on entry
  initialize_scope = {} -> {pool = __native_db_pool_create {url = connection_string}},
  # Release pool on exit (always runs, even on error)
  finalize_scope = {} -> __native_db_pool_close {pool = scope.pool},
  query = {sql} -> __native_db_query {pool = scope.pool, sql = sql},
  execute = {sql} -> __native_db_execute {pool = scope.pool, sql = sql}
}

# Usage — pool is automatically cleaned up
perform PostgresDB {connection_string = "postgres://localhost/mydb"} in do
  let users = Database.query {sql = "SELECT * FROM users"}
  users
```

**For control flow effects** (early return, exceptions, generators) — use continuations in the scope:
```mezze
use std::control::continuation

# Pattern: continuation-based control flow handler
impl MyEffect for MyHandler having 'result = 'r where {
  initialize_scope = {} -> {cont = continuation::new {}},
  finalize_scope = {} -> scope.cont.reset {},       # ALWAYS reset on exit
  my_operation = {val} -> scope.cont.suspend_with {value = val}  # escape with value
}
```

The key continuation methods:
- `scope.cont.suspend_with {value = x}` — escape the perform block, return `x` as the result
- `scope.cont.suspend_and_resume {value = x}` — yield `x` but continue (for generators)
- `scope.cont.abort {}` — abort the computation entirely
- `scope.cont.reset {}` — cleanup, MUST call in `finalize_scope`
- `scope.cont.take_collected {}` — get all values from `suspend_and_resume` calls (for Collect)

**Simple handler (no state, no scope):**
If your effect operations are pure and independent, skip `initialize_scope`/`finalize_scope`:
```mezze
effect Greet where {
  hello: {name: Str} -> Str <Greet>
}

impl Greet for FormalGreet where {
  hello = {name} -> "Good day, $(name)"
}

impl Greet for CasualGreet where {
  hello = {name} -> "Hey $(name)!"
}

# Usage — swap handler without changing code
perform FormalGreet in Greet.hello {name = "Alice"}   # "Good day, Alice"
perform CasualGreet in Greet.hello {name = "Alice"}   # "Hey Alice!"
```

**Effects with associated types** — when the effect needs a user-specified type:
```mezze
# 'error is an associated type — each handler binds it to a concrete type
effect Validate having 'error where {
  fail: {error: 'error} -> 'a <Validate>
}

impl Validate for StringValidator having 'error = Str where {
  initialize_scope = {} -> {cont = continuation::new {}},
  finalize_scope = {} -> scope.cont.reset {},
  fail = {error} -> scope.cont.suspend_with {value = Err {error = error}}
}
```

### Key Rules for Effects

1. **Every effectful operation MUST be inside a `perform` block** — forgetting `perform` is a type error
2. **Effects propagate** — if you call a function that uses `<Log>`, your function also has `<Log>` unless you handle it with `perform`
3. **Effect operations are called as `EffectName.operation {args}`** — e.g., `Log.log {level = Info, msg = "hi"}`
4. **Multiple effects need multiple handlers** — `perform Console, ExceptionToResult in body`
5. **Top-level code must handle all effects** — unhandled effects at top level are type errors

### Common Effect Patterns

```mezze
# Logging
use std::effects::log::{Log, Console, println, info, debug}

perform Console in do
  println {msg = "Hello"}
  info {msg = "Processing..."}
  debug {msg = "Debug details"}

# Exception handling → Result
use std::effects::exception::{Exception, ExceptionToResult, throw, unwrap}

let result = perform ExceptionToResult in do
  let x = some_operation {}
  if x < 0 then Exception.throw {error = "negative value"}
  else x + 1
# result is Result Int Str

# Exception handling → Option (discard error details)
use std::effects::exception::{Exception, ExceptionToOption}

let maybe = perform ExceptionToOption in do
  risky_operation {}
# maybe is Option ReturnType

# Combining effects
perform Console, ExceptionToResult in do
  Log.println {msg = "Starting..."}
  let data = unwrap {result = parse_data {input = raw}}
  Log.println {msg = "Got: $(data)"}
  data

# Filesystem
use std::effects::filesystem::{FileSystem, LocalDisk, read_file, write_file}

perform LocalDisk in do
  let content = read_file {path = "input.txt"}
  write_file {path = "output.txt", content = content}

# Random
use std::effects::random::{Random, SeededRandom, random_int}

perform SeededRandom {seed = 42} in do
  let n = random_int {min = 1, max = 100}
  n
```

### Available Effect Handlers

| Effect | Handlers | Use Case |
|--------|----------|----------|
| `Log` | `Console`, `ConsoleWith {formatter}`, `QuietConsole` | Logging/printing |
| `Exception` | `ExceptionToResult`, `ExceptionToOption`, `AbortException`, `IgnoreException`, `CrashException` | Error handling |
| `Mut` | `LocalMut`, `DebugLocalMut` | Safe local mutation |
| `FileSystem` | `LocalDisk`, `MemoryFS` | File I/O |
| `Time` | `SystemTime`, `MockTime {fixed_time}` | Time operations |
| `Random` | `SeededRandom {seed}` | Random number generation |
| `Concurrency` | `Loom` | Simple spawn/await concurrency |
| `Async` | `LoomAsync`, `LoomAsyncWith {max_concurrent}`, `OSThreadAsync`, `CoopAsync` | Full async with race/all/timeout/cancel/scoped |
| `Parallel` | `ForkJoinParallel`, `VirtualParallel`, `Sequential` | Data parallelism over collections |
| `STM` | `OptimisticSTM`, `SequentialSTM` | Software transactional memory |
| `Channel` | `BlockingChannel` | Typed channels between threads |

### Mutation Effect (RARELY USE — only for explicit performance-critical algorithms)

**DO NOT use Mut by default.** Always prefer pure functional style: recursion, map/filter/fold, immutable data. Only use `Mut` when the user explicitly asks for imperative-style optimization of a specific algorithm that genuinely benefits from in-place mutation (e.g., quicksort, in-place graph algorithms, performance-critical inner loops).

The `Mut` effect is hermetic: you CANNOT use Log, Exception, or any other effect inside a `perform LocalMut in` block.

```mezze
use std::effects::mutation::{Mut, LocalMut, seq_to_mut, seq_to_immut, while, for_range, for_range1}

# Example: in-place sort (only when user asks for mutable perf optimization)
let sorted = perform LocalMut in do
  let arr = seq_to_mut {seq = [3, 1, 4, 1, 5]}
  sort {seq = arr}
  seq_to_immut {seq = arr}
```

### Concurrency (Mezze's strength — use liberally)

Mezze has first-class concurrency built on Project Loom virtual threads. Prefer these over manual threading.

**Simple spawn/await (Concurrency effect):**
```mezze
use std::effects::concurrency::{Concurrency, Loom, all}

perform Loom in do
  # Spawn a task on a virtual thread
  let promise = Concurrency.spawn {computation = {} -> expensive_work {}}
  # Do other work while it runs
  let other = compute_something {}
  # Await the result
  let result = Concurrency.await {promise = promise}
  {result, other}

# Run multiple tasks concurrently
perform Loom in do
  let results = all {computations = [
    {} -> fetch_from_db {id = 1},
    {} -> fetch_from_db {id = 2},
    {} -> fetch_from_db {id = 3}
  ]}
  results
```

**Full Async (Async effect — race, timeout, structured concurrency):**
```mezze
use std::effects::async::{Async, LoomAsync, Promise}

perform LoomAsync in do
  # Spawn
  let p1 = Async.spawn {f = {} -> fetch_url {url = "http://a.com"}}
  let p2 = Async.spawn {f = {} -> fetch_url {url = "http://b.com"}}

  # Wait for all
  let results = Async.all {ps = [p1, p2]}

  # Race — first to complete wins
  let fastest = Async.race {ps = [p1, p2]}

  # Timeout
  let maybe = Async.timeout {ms = 5000, p = p1}  # Option

  # Join — run two things concurrently, get both results
  let both = Async.join {l = {} -> task_a {}, r = {} -> task_b {}}
  # both = {l: ResultA, r: ResultB}

  # Structured concurrency — all children complete before scope exits
  Async.scoped {f = {sc} -> do
    let p1 = scope_spawn {sc = sc, f = {} -> task1 {}}
    let p2 = scope_spawn {sc = sc, f = {} -> task2 {}}
    Async.await {p = p1} + Async.await {p = p2}
  }
```

**Data Parallelism (Parallel effect — parallel map/filter/reduce over collections):**
```mezze
use std::effects::parallel::{Parallel, ForkJoinParallel, VirtualParallel}

# ForkJoinParallel — best for CPU-bound work (work-stealing)
perform ForkJoinParallel in do
  let results = Parallel.map {xs = large_list, f = {x} -> cpu_intensive {x = x}}
  let total = Parallel.reduce {xs = results, f = {l, r} -> l + r}
  let filtered = Parallel.filter {xs = data, f = {x} -> x > threshold}

  # Run two independent computations in parallel
  let {l, r} = Parallel.both {
    l = {} -> process_a {},
    r = {} -> process_b {}
  }
  {results, total, filtered, l, r}
```

**Software Transactional Memory (STM — composable atomic transactions):**
```mezze
use std::effects::stm::{STM, OptimisticSTM, TVar, tvar}

# Create transactional variables (outside perform)
let account_a = tvar {init = 1000}
let account_b = tvar {init = 500}

# Atomic transfer — reads and writes are all-or-nothing
perform OptimisticSTM in do
  let balance_a = STM.read {var = account_a}
  let balance_b = STM.read {var = account_b}
  STM.write {var = account_a, val = balance_a - 100}
  STM.write {var = account_b, val = balance_b + 100}

# Retry — block until condition is met
perform OptimisticSTM in do
  let balance = STM.read {var = account_a}
  STM.check {cond = balance >= 100}    # retries if false
  STM.write {var = account_a, val = balance - 100}

# OrElse — try alternatives
perform OptimisticSTM in do
  STM.or_else {
    primary = {} -> do
      let b = STM.read {var = account_a}
      STM.check {cond = b >= 100}
      STM.write {var = account_a, val = b - 100},
    fallback = {} -> do
      let b = STM.read {var = account_b}
      STM.check {cond = b >= 100}
      STM.write {var = account_b, val = b - 100}
  }
```

**Channels (typed communication between threads):**
```mezze
use std::effects::async::channel::{Chan, Channel, BlockingChannel, chan, chan_bounded}

# Create channels (outside perform)
let ch = chan {}                    # unbounded
let bounded_ch = chan_bounded {cap = 10}  # bounded

perform BlockingChannel in do
  # Send and receive (dot chaining)
  ch.send {value = 42}
  let received = ch.recv {}

  # Non-blocking variants
  let sent = ch.try_send {value = 99}    # returns Bool
  let maybe = ch.try_recv {}             # returns Option

  # Select from multiple channels
  let {index, value} = Channel.recv_any {channels = [ch1, ch2, ch3]}

  ch.close {}
```

**Atoms (lock-free atomic references, no effect needed):**
```mezze
use std::effects::async::atom::{Atom, atom, deref, swap}

let counter = atom {value = 0}
swap {atom = counter, f = {current} -> current + 1}    # atomic increment
let current = deref {atom = counter}                    # non-blocking read
counter.swap {f = {current} -> current + 10}            # dot syntax
```

**Agents (async queued state updates, no effect needed):**
```mezze
use std::effects::async::agent::{Agent, agent, send, deref_agent, await_agent}

let stats = agent {value = {count = 0, total = 0}}
send {agent = stats, f = {current} -> {...current, count = current.count + 1}}
send {agent = stats, f = {current} -> {...current, total = current.total + 42}}
await_agent {agent = stats}                  # wait for pending actions
let final = deref_agent {agent = stats}      # read result
```

### Control Flow Effects (built on continuations — never use continuations directly)

Mezze has one-shot delimited continuations (`std::control::continuation`) as a low-level primitive. **NEVER use continuations directly.** Instead, use the effect handlers that are built on top of them. These provide safe, scoped control flow without exposing continuation machinery.

**Early Return:**
```mezze
use std::effects::early_return::{Return, EarlyReturn, return, return_if, return_none}

# Early exit from a computation
let result = perform EarlyReturn in do
  let x = compute {}
  if x < 0 then return {val = 0}    # exit immediately with 0
  let y = more_work {x = x}
  return_if {condition = y > 100, val = 100}  # conditional early exit
  y * 2    # normal return

# Unwrap Option or return default
let value = perform EarlyReturn in do
  let a = return_none {option = lookup {key = "x"}, default = 0}
  let b = return_none {option = lookup {key = "y"}, default = 0}
  a + b
```

**Exception (recap — also continuation-based):**
```mezze
use std::effects::exception::{Exception, ExceptionToResult, throw, unwrap}

let result = perform ExceptionToResult in do
  let data = unwrap {result = parse {input = raw}}   # throws on Err
  if data.valid then data.value
  else Exception.throw {error = "invalid data"}
# result : Result ValueType Str
```

**Generator (yield values, collect into Seq):**
```mezze
use std::effects::generator::{Generator, Collect, yield}

let values = perform Collect in do
  yield {value = 1}
  yield {value = 2}
  yield {value = 3}
# values = [1, 2, 3]

# Generate fibonacci numbers
let fibs = perform Collect in do
  let go = {a, b, n} -> if n == 0 then {}
    else do
      yield {value = a}
      go {a = b, b = a + b, n = n - 1}
  go {a = 0, b = 1, n = 10}
# fibs = [0, 1, 1, 2, 3, 5, 8, 13, 21, 34]
```

**Key principle:** All of these use `initialize_scope`/`finalize_scope` with continuations internally. The user never sees continuations. The effect system provides the safe abstraction. If writing a new control flow effect handler, use `std::control::continuation::new {}` in `initialize_scope` and `scope.cont.reset {}` in `finalize_scope`.

### When to Use Which Concurrency Primitive

| Need | Use | Why |
|------|-----|-----|
| Run tasks concurrently, await results | `Concurrency` / `Async` with `Loom`/`LoomAsync` | Virtual threads, scales to millions |
| Parallel map/filter/reduce over data | `Parallel` with `ForkJoinParallel` | Work-stealing, CPU-bound |
| Shared mutable state with transactions | `STM` with `OptimisticSTM` | Composable, no deadlocks |
| Communication between tasks | `Channel` with `BlockingChannel` | Typed, blocking send/recv |
| Simple atomic counter/state | `Atom` (no effect) | Lock-free CAS, synchronous |
| Fire-and-forget state updates | `Agent` (no effect) | Async, queued, sequential |
| Structured concurrency (child lifetime) | `Async.scoped` | Children auto-cancelled on failure |

## Standard Library Quick Reference

### Imports for Common Tasks

```mezze
# Logging
use std::effects::log::{Log, Console, println, info, debug, error, warn}

# Exceptions
use std::effects::exception::{Exception, ExceptionToResult, throw, unwrap}

# Mutation
use std::effects::mutation::{Mut, LocalMut, seq_to_mut, seq_to_immut, while, for_range, for_range1}

# Collections
use std::collections::seq          # seq::map, seq::filter, seq::fold, etc.
use std::collections::list         # list::map, list::filter, etc.
use std::collections::map          # map::insert, map::get, etc.
use std::collections::set          # set::insert, set::contains, etc.

# Option/Result utilities
use std::option::{map, flat_map, get_or_else, is_some, is_none}
use std::result::{map, flat_map, is_ok, is_err}

# Math
use std::math::core::{min, max, pow, sqrt}

# String utilities
use std::str

# Filesystem
use std::effects::filesystem::{FileSystem, LocalDisk, read_file, write_file}

# Time
use std::effects::time::{Time, SystemTime, now, sleep}

# Random
use std::effects::random::{Random, SeededRandom, random_int, random_float}

# HTTP
use std::effects::http

# Async
use std::effects::async
use std::effects::concurrency::{Concurrency, Loom, spawn, await}
```

### Prefer Dot Chaining Over Function Calls

When a type has an inherent impl or ability method, ALWAYS prefer dot-chaining style over qualified function calls. Dot chaining is idiomatic Mezze.

```mezze
# PREFERRED — dot chaining
42.to_str {}
42.abs {}
"hello".to_upper {}
items.map {f = {x} -> x * 2}.filter {p = {x} -> x > 0}
opt.get_or_else {def = 0}
result.map {f = {val} -> val + 1}

# AVOID — qualified function calls when dot chaining is available
int::to_str {n = 42}
int::abs {n = 42}
str::to_upper {s = "hello"}
seq::filter {xs = seq::map {xs = items, f = {x} -> x * 2}, p = {x} -> x > 0}
option::get_or_else {opt = opt, def = 0}
```

Dot chaining reads left-to-right, avoids deep nesting, and is the idiomatic style. Use qualified calls (`module::func {args}`) only when there is no inherent impl method available.

### Method Chaining on Common Types

```mezze
# Int methods
42.to_str {}          42.abs {}           42.is_even {}
42.pow {exp = 3}      42.min {r = 10}     42.max {r = 100}
42.to_float {}        42.to_nat {}        42.gcd {r = 18}

# Str methods
"hello".length {}     "hello".to_upper {}  "hello".contains {substring = "ell"}
"hello".split {sep = "l"}                  "hello".trim {}

# Option methods
opt.map {f = {val} -> val + 1}
opt.flat_map {f = {val} -> Some {val = val}}
opt.get_or_else {def = 0}
opt.is_some {}        opt.is_none {}
opt.filter {p = {val} -> val > 0}
opt.to_result {error = "was None"}

# Result methods
result.map {f = {val} -> val + 1}
result.map_err {f = {error} -> "wrapped: $(error)"}
result.flat_map {f = {val} -> Ok {val = val}}
result.is_ok {}       result.is_err {}

# Seq methods — prefer dot chaining
[1, 2, 3]
  .map {f = {x} -> x * 2}
  .filter {p = {x} -> x > 2}
  .fold {init = 0, f = {acc, x} -> acc + x}

# List methods
my_list.map {f = {x} -> x + 1}
my_list.filter {p = {x} -> x > 0}
my_list.head {}       my_list.tail {}
```

## Program Entry Point

No explicit `main` required. Top-level code is wrapped into `__entry__`:

```mezze
# Simple program — just write top-level code
use std::effects::log::{Log, Console, println}

perform Console in do
  println {msg = "Hello, world!"}
```

Or use an explicit `pub let main`:
```mezze
pub let main = {} -> perform Console in do
  println {msg = "Hello, world!"}
```

## Common Mistakes to AVOID

1. `f(x)` or `f x` — MUST use `f {x = value}`
2. `++` for string concat — use `"$(a)$(b)"` interpolation
3. `//` for comments — use `#`
4. `${}` in strings — use `$()`
5. `return x` — no return keyword, last expression IS the value
6. `match x {` — use `match x is`
7. `let f = (x) -> ...` — use `let f = {x} -> ...`
8. Type vars `T` or `<T>` — use `'a`, `'b`
9. `null` or `undefined` — use `Option` with `Some`/`None`
10. `x++` or `x--` — no increment/decrement
11. `x += 1` — no mutation operators
12. `"hello" + "world"` — `+` is numeric only, use interpolation
13. `Array<Int>` — use `[Int]` for sequences
14. `impl Trait for Type { }` — use `impl Ability for Type where { }`
15. Forgetting `pub` on exports
16. Forgetting `perform` before effectful code
17. Using `->` without `{}`: `x -> x + 1` instead of `{x} -> x + 1`
18. `1 / 2` is integer division. `1//2` is a Frac literal.
