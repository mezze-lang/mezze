# Funk Type System - Quick Reference

## Type Declaration Syntax

### Closed Variant (Fixed constructors)
```funk
type Shape = | Circle { radius: Float }, Rectangle { width: Float, height: Float } |
```

### Open Variant (Extensible)
```funk
type Message = | Text { content: String }, ... |
```

### Record Type (Closed - inferred from literal)
```funk
let person = { name = "Alice", age = 30 }
```

### Record Type (Open - with row polymorphism)
```funk
let greet = { p: { name: String, ... } } -> "Hello, $(p.name)!"
```

## Type Parameters

### Single Parameter
```funk
type Option 'a = | Some {value: 'a}, None |
```

### Multiple Parameters
```funk
type Either 'a 'b = | Left {val: 'a}, Right {val: 'b} |
type Triple 'a 'b 'c = | MkTriple { first: 'a, second: 'b, third: 'c } |
```

## Function Types

### Basic Function
```funk
let add = { x: Int, y: Int } -> x + y

# With explicit type annotation
let multiply: { x: Int, y: Int } -> Int = { x, y } -> x * y
```

### Higher-Order Functions
```funk
# Function as parameter
let apply_twice = { f: { x: Int } -> Int, x: Int } ->
    f { x = f { x = x } }

# Returning a function
let make_adder = { n: Int } -> { x: Int } -> x + n
```

## Ability System (Type Classes)

### Ability Declaration
```funk
ability Show 'a {
    show: { val: 'a } -> String
}

# With dependency
ability Ord 'a with Eq 'a {
    compare: { l: 'a, r: 'a } -> Ordering
}
```

### Ability Implementation
```funk
impl Show Int where {
    show = { val } -> int::to_string { val = val }
}
```

### Using Ability Constraints
```funk
let display = { value: 'a } with Show 'a ->
    "Value: $(Show.show { val = value })"
```

## Effect Types

### Effect Declaration
```funk
effect Log having 'state, 'error {
    println: { msg: String } -> {},
    debug: { msg: String } -> {}
}
```

### Function with Effect
```funk
let greet_with_log = { name: String } <Log> -> do
    Log.println { msg = "Hello, $(name)!" }
```

### Effect Handler
```funk
let main = {} ->
    perform Console in do
        Log.println { msg = "With effect!" }
```

## Pattern Matching

### Closed Variant Match
```funk
match shape is
  Circle { radius } -> 3.14159 * radius * radius
  Rectangle { width, height } -> width * height
```

### Open Variant Match (with wildcard)
```funk
match msg is
  Text { content } -> content
  Number { value } -> "number"
  _ -> "unknown"
```

### Record Pattern
```funk
let extract = { p: { name: String, age: Int, ... } } ->
    { name = p.name, age = p.age }
```

## Built-in Types

### Primitives
```
Int, Float, String, Boolean, Nat, Frac
```

### Generic Types
```
Option 'a
Result 'v 'e
List 'a
Map 'k 'v
Set 'a
Ordering  (LT | EQ | GT)
```

## Type System Capabilities

| Feature | Supported | Example |
|---------|-----------|---------|
| Multi-param generics | Yes | `type Pair 'a 'b = ...` |
| Row polymorphism | Yes | `{ field: Type, ... }` |
| Ability constraints | Yes | `with Show 'a` |
| Open variants | Yes | `\| Constr, ... \|` |
| Effect types | Yes | `<Effect1, Effect2>` |
| Let-polymorphism | Yes | Type schemes with constraints |
| Type inference | Yes | Bidirectional checking |
| Module visibility | Yes | `pub type`, `type` |

## NOT Supported

- Higher-Kinded Types (HKT/Functor/Monad)
- Explicit opaque types
- Phantom types
- GADTs
- Rank-N polymorphism
- Existential types
- Type families / functional dependencies

## Type Representation (Internal)

```kotlin
sealed interface FunkType

// Primitives
object IntType, FloatType, StringType, BooleanType, NatType, FracType, OrderingType

// Structured
data class RecordType(fields: Map<String, FunkType>, rowVar: RowVar?)
data class VariantType(constructors: Map<String, FunkType?>, rowVar: RowVar?)
data class ListType(elementType: FunkType)
data class MapType(keyType: FunkType, valueType: FunkType)
data class SetType(elementType: FunkType)

// Generic
data class VarType(variable: TypeVar)                    // 'a, 'b
data class TypeApplication(constructor: String, args: List<FunkType>)

// Functions & Effects
data class FunctionType(paramType, returnType, effects?, constraints?)
data class EffectRow(effects: Set<String>, rowVar: RowVar?)
data class SuspendedType(valueType: FunkType, effects: EffectRow)

// Constraints
data class FunctionAbilityConstraint(abilityName: String, typeArgs: List<FunkType>)
```

## Key Files

- **Type Representation**: `/ast/src/main/kotlin/com/funk/language/ast/FunkAST.kt`
- **Type Checker**: `/typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt`
- **Basic Types**: `/typechecker/src/main/kotlin/com/funk/language/typechecker/types/BasicTypes.kt`
- **Parser**: `/parser/src/main/kotlin/com/funk/language/parser/FunkParser.kt`

## Visibility & Module System

```funk
# Private type (default, not exported)
type Secret = { value: String }

# Public type (exported from module)
pub type Public = { value: String }

# Import specific items
use module::path::{item1, item2 as alias}

# Module-qualified usage
result = std::int::add { l = 1, r = 2 }
```
