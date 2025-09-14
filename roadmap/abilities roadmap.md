# Funk Abilities System - Future Features Roadmap

## Current Implementation Status ✅

### What's Working Now
- **Ability Declaration**: `ability Name 'a 'b where { operations }`
- **Implementation**: `impl Name Type1 Type2 where { implementations }`
- **Multiple Type Parameters**: Full support for multi-parameter abilities
- **Type Safety**: Complete parsing, type checking, and runtime execution
- **Syntax Disambiguation**: Clear distinction between concrete types and type applications

### Current Example
```funk
ability Encoder 'a where {
  encode : {val: 'a} -> String
}

impl Encoder Int where {
  encode : {val: Int} -> String = {val} -> "INT:" ++ intToString {val = val}
}

impl Encoder String where {
  encode : {val: String} -> String = {val} -> "STR:" ++ val
}

# Usage: Abilities are declared and implemented, but no automatic resolution yet
let result = "Abilities work!"
```

## Future Enhancements 🚀

### Phase 1: Automatic Resolution
**Goal**: Compiler automatically selects the right implementation based on argument types.

```funk
# FUTURE: This would work automatically
let displayValue = {val} -> Encoder.encode {val = val}

let result1 = displayValue {val = 42}        # Compiler uses Encoder Int
let result2 = displayValue {val = "hello"}   # Compiler uses Encoder String

# The function would have inferred type:
# displayValue : {val: 'a} -> String with Encoder 'a
```

### Phase 2: Explicit Function Constraints with `with` Keyword
**Goal**: Functions can explicitly declare ability requirements.

```funk
# Single constraint
let genericDisplay: {val: 'a} -> String with Encoder 'a = {val} ->
  Encoder.encode {val = val}

# Multiple constraints with 'do' block
let compareAndShow: {left: 'a, right: 'a} -> String 
  with Eq 'a, Show 'a = {left, right} -> do
    let isEqual = Eq.equals {left = left, right = right}
    let leftStr = Show.show {val = left}
    let rightStr = Show.show {val = right}
    let equalStr = Show.show {val = isEqual}
    leftStr ++ " == " ++ rightStr ++ " is " ++ equalStr
```

### Phase 3: Ability Dependencies with `with` Keyword
**Goal**: Abilities can require other abilities as dependencies.

```funk
# Basic dependency: Ord requires Eq
ability Eq 'a where {
  equals : {left: 'a, right: 'a} -> Bool
}

ability Ord 'a with Eq 'a where {
  compare : {left: 'a, right: 'a} -> String
  lessThan : {left: 'a, right: 'a} -> Bool
}

# Complex ability hierarchies
ability Serializable 'a with Show 'a, Eq 'a where {
  serialize : {val: 'a} -> String
  deserialize : {str: String} -> 'a
}
```

## Type System Decidability Analysis 🔍

### All Proposed Features Maintain Decidability ✅

- **Phase 1 (Automatic Resolution)**: Decidable ✅
  - Similar to Haskell's type classes (proven decidable)
  - Finite search space, no overlapping instances

- **Phase 2 (Function Constraints)**: Decidable ✅
  - Explicit programmer annotations
  - Local constraint resolution

- **Phase 3 (Ability Dependencies)**: Decidable with Restrictions ✅
  - Acyclic dependencies enforced
  - Finite dependency chains
  - Stratified resolution

## Syntax Design with `with` Keyword 📝

### Function Constraints
```funk
# Single constraint
functionName : {params} -> ReturnType with AbilityName 'a

# Multiple constraints
functionName : {params} -> ReturnType with Ability1 'a, Ability2 'b
```

### Ability Dependencies
```funk
# Single dependency
ability ChildAbility 'a with ParentAbility 'a where { ... }

# Multiple dependencies
ability ComplexAbility 'a with Dep1 'a, Dep2 'a where { ... }
```

## Implementation Priority 🎯

### High Priority (Phase 1)
1. **Automatic Resolution**: Core functionality for usability
2. **Basic Constraint Solving**: Essential for type safety

### Medium Priority (Phase 2)
3. **Explicit Function Constraints**: Important for complex functions
4. **Simple Ability Dependencies**: Enables ability hierarchies

### Lower Priority (Phase 3)
5. **Advanced Constraint Inference**: Nice-to-have for ergonomics
6. **Complex Ability Relationships**: Advanced features

## Grammar Extensions Required 📋

### New Keywords
```antlr
WITH: 'with';
```

### Function Type Extensions
```antlr
functionType:
    recordType ARROW typeAnnotation effectRow? abilityConstraints?
    ;

abilityConstraints:
    WITH abilityConstraint (COMMA abilityConstraint)*
    ;

abilityConstraint:
    Identifier typeAnnotation*
    ;
```

### Ability Declaration Extensions
```antlr
abilityDeclaration:
    ABILITY Identifier TypeVariable+ abilityDependencies? WHERE LBRACE NEWLINE? (abilityOperation NEWLINE?)* RBRACE NEWLINE?
    ;

abilityDependencies:
    WITH abilityConstraint (COMMA abilityConstraint)*
    ;
```

## Examples Gallery 🎨

### Basic Pattern
```funk
ability Show 'a where {
  show : {val: 'a} -> String
}

let displayComparison: {x: 'a, y: 'a} -> String with Show 'a, Eq 'a = {x, y} -> do
  let xStr = Show.show {val = x}
  let yStr = Show.show {val = y}
  let equal = Eq.equals {left = x, right = y}
  let equalStr = if equal then "equal" else "not equal"
  xStr ++ " and " ++ yStr ++ " are " ++ equalStr
```

### Hierarchical Abilities
```funk
ability Eq 'a where {
  equals : {left: 'a, right: 'a} -> Bool
}

ability Ord 'a with Eq 'a where {
  compare : {left: 'a, right: 'a} -> String
}

ability Bounded 'a with Ord 'a where {
  minBound : {} -> 'a
  maxBound : {} -> 'a
}
```

## Conclusion 🎉

The abilities system provides a solid foundation for type classes in Funk. The proposed enhancements maintain **principal decidability** while adding significant expressive power. The `with` keyword provides a clean, consistent syntax for both function constraints and ability dependencies.

**Key Design Principles**:
- ✅ **Decidable**: All enhancements preserve type inference decidability
- ✅ **Coherent**: Unique instance resolution guaranteed
- ✅ **Explicit**: Complex constraints require programmer annotation
- ✅ **Extensible**: System can grow incrementally
- ✅ **Consistent**: Uniform `with` keyword usage