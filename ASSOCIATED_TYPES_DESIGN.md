# Associated Types Design for Funk

## Status: Future Development

This document outlines the design for adding associated types to Funk's ability system. This feature is planned for future implementation.

## Motivation

Currently, Funk cannot express abstractions like `Iterator` that work across different container types while maintaining full type inference. Associated types solve this without requiring higher-kinded types (HKT).

### The Problem

Without associated types, we cannot write:
```funk
# Generic iterator that works on List, Array, Generator, etc.
ability Iterator 'self where {
  next : {iter: 'self} -> (Option ???)  # What type goes here?
}
```

The "element type" varies by container:
- `List Int` yields `Int`
- `Array String` yields `String`
- `Generator` yields whatever it was set up to yield

### The Solution: Associated Types

Associated types let the implementation specify related types:
```funk
ability Iterator 'self types Item where {
  next : {iter: 'self} -> (Option Item)
}

impl Iterator (List 'a) types Item = 'a where {
  next = {iter} -> list::head { xs = iter }
}
```

## Proposed Syntax

### Option A: `types` keyword (Recommended)

```
ability <Name> <TypeParams> [types <AssocTypes>] [with <Constraints>] where { <Operations> }

impl <Name> <ConcreteTypes> [types <AssocBindings>] [with <Constraints>] where { <Implementations> }
```

### Option B: `assoc` keyword

```
ability <Name> <TypeParams> [assoc <AssocTypes>] [with <Constraints>] where { <Operations> }

impl <Name> <ConcreteTypes> [assoc <AssocBindings>] [with <Constraints>] where { <Implementations> }
```

## Design Decisions

### Why Not Inside `{ }`?

The `{ }` block in abilities/effects is record-like - it contains operations (functions). Mixing type declarations there would break the mental model:
- `Ability.operation` accesses a function
- Types are not functions

Associated types belong in the "header" area, not the operation block.

### Why Not `[]` or `<>`?

- `[]` is used for list types
- `<>` is used for effect types

These would create parsing ambiguity.

### Why Not Implicit Inference?

We considered inferring associated types from "extra type variables":
```funk
ability Iterator 'self where {
  next : {iter: 'self} -> (Option 'item)  # 'item inferred as associated
}
```

This was rejected as "too much magic" - Funk values explicitness.

## Complete Examples

### Simple Associated Type

```funk
ability Iterator 'self types Item where {
  next : {iter: 'self} -> (Option Item)
  has_next : {iter: 'self} -> Bool
}

impl Iterator (List 'a) types Item = 'a where {
  next = {iter} -> list::head { xs = iter }
  has_next = {iter} -> list::is_empty { xs = iter } == false
}

impl Iterator (Array 'a) types Item = 'a where {
  next = {iter} -> array::head { xs = iter }
  has_next = {iter} -> array::is_empty { xs = iter } == false
}
```

### With Ability Dependencies

```funk
ability Ord 'a with Eq 'a where {
  compare : {left: 'a, right: 'a} -> Int
}

ability Sortable 'self types Item with Ord Item where {
  sort : {col: 'self} -> 'self
}

impl Sortable (List 'a) types Item = 'a with Ord 'a where {
  sort = {col} -> list::sort { xs = col }
}
```

### Multiple Associated Types

```funk
ability Index 'self types Key, Value where {
  get : {container: 'self, key: Key} -> (Option Value)
  set : {container: 'self, key: Key, value: Value} -> 'self
}

impl Index (Map 'k 'v) types Key = 'k, Value = 'v where {
  get = {container, key} -> map::get { m = container, k = key }
  set = {container, key, value} -> map::insert { m = container, k = key, v = value }
}
```

### Collection Hierarchy

```funk
ability Collection 'self types Element where {
  is_empty : {col: 'self} -> Bool
  size : {col: 'self} -> Int
}

ability Iterable 'self types Item with Collection 'self where {
  iter : {col: 'self} -> (Iterator 'self)
}

impl Collection (List 'a) types Element = 'a where {
  is_empty = {col} -> list::is_empty { xs = col }
  size = {col} -> list::length { xs = col }
}

impl Collection (Array 'a) types Element = 'a where {
  is_empty = {col} -> array::is_empty { xs = col }
  size = {col} -> array::length { xs = col }
}
```

## Type Inference

Associated types maintain full type inference:

```funk
let xs : (List Int) = list::range { start = 0, end = 10 }

# Type checker:
# 1. xs : (List Int)
# 2. Look up impl Iterator (List 'a) - find with 'a = Int
# 3. Look up types Item = 'a = Int
# 4. Return type is (Option Int)

let first = Iterator.next { iter = xs }  # Inferred: (Option Int)
```

No annotations needed.

## Enforcement

If an impl forgets to specify an associated type:

```funk
impl Iterator (List 'a) where {   # Missing: types Item = 'a
  next = {iter} -> list::head { xs = iter }
}
```

Error:
```
Error: Missing associated type 'Item' in impl Iterator (List 'a)
       Required associated types: Item
```

Same as forgetting to implement an operation.

## Comparison with Other Languages

| Language | Mechanism | Syntax |
|----------|-----------|--------|
| Rust | Associated types | `type Item;` in trait, `type Item = T;` in impl |
| Swift | Associated types | `associatedtype Item` in protocol |
| Haskell | Functional dependencies | `class C a b | a -> b` |
| Funk | Associated types | `types Item` in ability, `types Item = T` in impl |

## Implementation Requirements

### Parser Changes
1. Parse `types <Name>, <Name>` after type parameters
2. Parse `types <Name> = <Type>` in impl

### AST Changes
1. Add `associatedTypes: List<String>` to `AbilityDeclaration`
2. Add `associatedTypeBindings: Map<String, FunkType>` to `AbilityImplementation`

### TypeChecker Changes
1. Track associated types per ability declaration
2. Resolve associated type references in operation signatures
3. Validate all associated types are provided in impl
4. Use associated type bindings during type unification

### Interpreter Changes
1. Store associated type bindings with impl
2. Look up associated types when resolving ability calls

## Alternatives Considered

### Functional Dependencies (Haskell-style)

```funk
ability Iterator 'iter 'item with FunDep 'iter 'item where {
  next : {iter: 'iter} -> (Option 'item)
}
```

Rejected because:
- `FunDep` isn't a real ability (no operations)
- Overloads `with` to mean two different things
- Less intuitive than associated types

### Multi-Parameter Only

```funk
ability Iterator 'self 'item where {
  next : {iter: 'self} -> (Option 'item)
}
```

Works but:
- Requires functional dependencies for inference
- Less ergonomic syntax
- Caller must specify both type parameters

## Future Work

1. **Default associated types**: `types Item = Int` as default
2. **Associated type bounds**: `types Item with Eq Item`
3. **Associated types in effects**: Possibly, but lower priority

## Summary

Associated types provide a clean way to express type relationships in abilities without HKT, maintaining full type inference and Funk's philosophy of explicitness.

Recommended syntax: `types` keyword

```funk
ability Iterator 'self types Item where {
  next : {iter: 'self} -> (Option Item)
}

impl Iterator (List 'a) types Item = 'a where {
  next = {iter} -> list::head { xs = iter }
}
```
