# Design Document

## Overview

This design outlines the architectural changes needed to move core types, abilities, and operators from the Funk stdlib into the language runtime as built-in constructs. This follows the pattern established by languages like Haskell, OCaml, and F# where fundamental types and operations are language primitives rather than library code.

## Architecture

### Built-in Type System

The following types will be moved from stdlib modules into the language runtime:

#### Core Data Types
- `Option 'a` - Optional values with `Some { val: 'a }` and `None` constructors
- `Result 'e 'a` - Error handling with `Ok { val: 'a }` and `Err { err: 'e }` constructors  
- `List 'a` - Homogeneous lists with `[]` and `::` syntax
- `Map 'k 'v` - Key-value mappings with `{ key = val }` syntax
- `Set 'a` - Unique value collections

#### Primitive Types (already built-in)
- `Int` - Integers
- `Float` - Floating point numbers
- `String` - Text strings
- `Boolean` - True/false values
- `Nat` - Natural numbers

### Built-in Abilities

Core abilities will be implemented as language primitives:

#### Comparison Abilities
```funk
ability Eq 'a where {
    equals: { l: 'a, r: 'a } -> Boolean
}

ability Ord 'a where {
    compare: { l: 'a, r: 'a } -> Ordering,
    less_than: { l: 'a, r: 'a } -> Boolean,
    greater_than: { l: 'a, r: 'a } -> Boolean
}
```

#### Numeric Abilities
```funk
ability NumAdd 'a where {
    add: { l: 'a, r: 'a } -> 'a
}

ability NumSub 'a where {
    subtract: { l: 'a, r: 'a } -> 'a
}

ability NumMul 'a where {
    multiply: { l: 'a, r: 'a } -> 'a
}

ability NumDiv 'a where {
    divide: { l: 'a, r: 'a } -> 'a
}

ability NumMod 'a where {
    modulo: { l: 'a, r: 'a } -> 'a
}
```

#### Display Ability
```funk
ability Show 'a where {
    to_string: { val: 'a } -> String
}
```

### Built-in Operators

Operators will be parsed as language constructs and resolved through the ability system:

#### Arithmetic Operators
- `+` → `NumAdd.add { l = left, r = right }`
- `-` → `NumSub.subtract { l = left, r = right }`  
- `*` → `NumMul.multiply { l = left, r = right }`
- `/` → `NumDiv.divide { l = left, r = right }`
- `%` → `NumMod.modulo { l = left, r = right }`

#### Comparison Operators
- `==` → `Eq.equals { l = left, r = right }`
- `!=` → `not (Eq.equals { l = left, r = right })`
- `<` → `Ord.less_than { l = left, r = right }`
- `>` → `Ord.greater_than { l = left, r = right }`
- `<=` → `not (Ord.greater_than { l = left, r = right })`
- `>=` → `not (Ord.less_than { l = left, r = right })`

#### Boolean Operators
- `&&` → built-in short-circuiting and
- `||` → built-in short-circuiting or
- `!` → built-in boolean negation

## Components and Interfaces

### TypeChecker Integration

The type checker will be modified to:

1. **Preload Built-in Types**: Core types are available in the global scope without imports
2. **Operator Resolution**: Parse operators as special syntax and resolve through abilities
3. **Ability Resolution**: Built-in abilities are always available for constraint solving
4. **Automatic Implementations**: Primitive types have built-in ability implementations

### Parser Changes

The parser will be updated to:

1. **Operator Precedence**: Built-in operator precedence and associativity rules
2. **Special Syntax**: Recognize list literals `[1, 2, 3]` as `List` construction
3. **Map Syntax**: Recognize `{ key = val }` as `Map` construction when appropriate
4. **Option Syntax**: Continue supporting `Some { val = x }` and `None` syntax

### Runtime Implementation

The interpreter/compiler will:

1. **Native Representations**: Efficient native implementations for built-in types
2. **Operator Compilation**: Direct compilation of operators to native operations
3. **Ability Dispatch**: Optimized dispatch for built-in ability implementations
4. **Memory Layout**: Optimal memory representations for core data structures

### Module System Redesign

The stdlib will be restructured as:

#### Core Modules (Functions Only)
```
std/
├── list.funk      # Functions: map { xs, f }, filter { xs, p }, fold { xs, init, f }
├── option.funk    # Functions: map { opt, f }, flatMap { opt, f }, getOrElse { opt, val }
├── result.funk    # Functions: map { res, f }, mapErr { res, f }, andThen { res, f }
├── string.funk    # Functions: split { s, sep }, join { xs, sep }, trim { s }
├── map.funk       # Functions: insert { m, k, val }, lookup { m, k }, keys { m }
├── set.funk       # Functions: insert { s, x }, member { s, x }, union { l, r }
└── math.funk      # Functions: abs { x }, min { l, r }, max { l, r }
```

#### Removed Modules
- `std/core/abilities.funk` → moved to language runtime
- `std/core/operators.funk` → moved to language runtime  
- `std/core/primitives.funk` → moved to language runtime
- `std/types/*.funk` → type definitions moved to runtime

## Data Models

### Built-in Type Definitions

```funk
# These become language built-ins, not stdlib definitions

type Option 'a = | Some { val: 'a }, None |

type Result 'e 'a = | Ok { val: 'a }, Err { err: 'e } |

type List 'a = | Nil, Cons { head: 'a, tail: List 'a } |

type Map 'k 'v = # Internal representation

type Set 'a = # Internal representation

type Ordering = | LT, EQ, GT |
```

### Native Function Implementation Updates

All `__native_*` functions will be updated to use consistent parameter names:

```funk
# Current inconsistent naming:
__native_int_add { left = l, right = r }
__native_string_equals { left = s1, right = s2 }
__native_arraylist_get { arr = list, index = idx }

# New consistent short naming:
__native_int_add { l = l, r = r }
__native_string_equals { l = l, r = r }
__native_list_get { xs = xs, i = i }
__native_list_map { xs = xs, f = f }
__native_option_map { opt = opt, f = f }
__native_list_filter { xs = xs, p = p }
__native_list_fold { xs = xs, init = init, f = f }
```

### Parameter Naming Conventions

- **Binary operations**: `l` (left), `r` (right)
- **Values**: `val` instead of `value`
- **Collections**: `xs` (list), `m` (map), `s` (set) - short mathematical notation
- **Functions**: `f` instead of `func` or `function`
- **Predicates**: `p` instead of `pred` or `predicate`
- **Indices**: `i` instead of `idx` or `index`
- **Keys**: `k` instead of `key`
- **Items/Elements**: `x` instead of `item` or `element`
- **Accumulators**: `acc` (keep as is, already short)
- **Initial values**: `init` instead of `initial`
- **Counts**: `n` instead of `count`
- **Strings**: `s` instead of `str`

### Automatic Implementations

Built-in types will have automatic ability implementations:

```funk
# These implementations are built into the runtime

impl Eq Int where { ... }
impl Ord Int where { ... }
impl Show Int where { ... }
impl NumAdd Int where { ... }
# ... etc for all primitive types

impl Eq 'a => Eq (Option 'a) where { ... }
impl Ord 'a => Ord (Option 'a) where { ... }
impl Show 'a => Show (Option 'a) where { ... }

impl Eq 'a => Eq (List 'a) where { ... }
impl Ord 'a => Ord (List 'a) where { ... }
impl Show 'a => Show (List 'a) where { ... }
```

## Error Handling

### Type Error Messages

Error messages will distinguish between built-in and stdlib features:

```
Error: Unknown function 'map'
Help: Did you mean to import 'std/list' for list operations?
Note: 'List' is a built-in type, but 'map' is a stdlib function

Error: Unknown type 'HashMap'  
Help: Use the built-in 'Map' type, or import a specific map implementation
Note: 'Map' is built-in, 'HashMap' would be a stdlib module
```

### Migration Warnings

During the transition, provide helpful migration messages:

```
Warning: 'std/core/option' is deprecated
Help: Option is now a built-in type, remove this import
Note: Option functions are available in 'std/option'
```

## Testing Strategy

### Built-in Type Tests

1. **Type Resolution**: Verify built-in types are available without imports
2. **Constructor Tests**: Test `Some { val = x }`, `None`, `Ok { val = x }`, etc.
3. **Operator Tests**: Verify all operators work with appropriate types
4. **Ability Tests**: Test automatic ability implementations

### Stdlib Function Tests

1. **Import Tests**: Verify stdlib functions require proper imports
2. **Function Tests**: Test that stdlib functions work on built-in types
3. **Qualified Call Tests**: Test `list::map`, `option::flatMap`, etc.
4. **Integration Tests**: Test combinations of built-ins and stdlib functions

### Performance Tests

1. **Startup Time**: Measure program startup without module loading overhead
2. **Operator Performance**: Benchmark built-in operators vs function calls
3. **Type Checking Speed**: Measure type resolution for built-in vs stdlib types
4. **Memory Usage**: Verify efficient representations for built-in types

### Migration Tests

1. **Backward Compatibility**: Ensure existing syntax continues to work
2. **Import Removal**: Test that removing core imports doesn't break code
3. **Error Messages**: Verify helpful error messages during migration
4. **IDE Integration**: Test that IDE features work with new architecture