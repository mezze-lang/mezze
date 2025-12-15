# Funk Language Type System Capabilities

## 1. TYPE DECLARATIONS

### 1.1 Supported Type Declaration Forms

Funk supports three primary type declaration forms:

#### **Closed Variants (Fixed Constructors)**
```funk
type Shape = | Circle { radius: Float }, Rectangle { width: Float, height: Float } |
type DivResult = | DivOk { val: Float }, DivErr { err: String } |
type Status = | Active, Inactive |  # Unit constructors (no data)
```

#### **Open Variants (Extensible with Wildcard)**
```funk
type Message = | Text { content: String }, Number { value: Int }, ... |
type Status = | Active, Inactive, ... |
```
- Open variants allow pattern matching with wildcard `_` for unknown constructors
- Enables polymorphic behavior without requiring all cases to be defined

#### **Closed Records (Fixed Fields)**
```funk
# Implicit record type (inferred from literal)
let person = { name = "Alice", age = 30, email = "alice@example.com" }

# Explicit open record type annotation in functions
let greet = { person: { name: String, ... } } ->
    "Hello, $(person.name)!"
```

#### **Open Records (Extensible Fields)**
```funk
let get_name_and_age = { p: { name: String, age: Int, ... } } ->
    { name = p.name, age = p.age }
```
- Can accept records with additional fields beyond those specified
- Enables structural subtyping via row polymorphism

### 1.2 Type Aliases vs Newtypes
- **Type Aliases**: Currently supported through type application (e.g., `type Point = {x: Int, y: Int}`)
- **Newtypes**: NOT currently supported in explicit form, but can be encoded as single-constructor variants
  ```funk
  type UserId = | UserId { value: Int } |  # Acts like a newtype
  ```

### 1.3 Implicit Record Types
Records in Funk are often inferred from literals without explicit type declarations:
```funk
let address = {
    street = "123 Main St",
    city = "Springfield",
    zip = 12345
}
```

---

## 2. TYPE PARAMETERS (GENERICS)

### 2.1 Type Variables Syntax
- Syntax: `'a`, `'b`, `'c`, etc. (single quote prefix)
- Can declare multiple type parameters: `type Either 'a 'b = ...`
- Type variables in type annotations: `type Option 'a = | Some {value: 'a}, None |`

### 2.2 Generic Type Declarations
```funk
# Single parameter generic
type Option 'a = | Some {value: 'a}, None |

# Multi-parameter generics
type Either 'a 'b = | Left { val: 'a }, Right { val: 'b } |
type Result 'ok 'err = | Ok {value: 'ok}, Err {error: 'err} |
type Pair 'a 'b = | MkPair { first: 'a, second: 'b } |

# Three or more parameters supported
type Triple 'a 'b 'c = | MkTriple { first: 'a, second: 'b, third: 'c } |
```

### 2.3 Type Variable Scope
- Type variables are scoped to function definitions
- Fresh type variables generated per function instantiation for let-polymorphism
- Type variable generation: 'a' -> 'b' -> 'c' ... (lexicographic order)

### 2.4 Constraints/Bounds
Funk uses **Ability Constraints** instead of traditional type class bounds:

```funk
# Function with ability constraint
let display = { value: 'a } with Show 'a ->
    "Value: $(Show.show { val = value })"

# Ability with operations
ability Show 'a {
    show: { val: 'a } -> String
}

# Multiple constraints (implicit conjunction)
let sum_of_squares = { x: 'a, y: 'a } with NumOps 'a -> do
    let x_sq = NumOps.multiply { l = x, r = x }
    let y_sq = NumOps.multiply { l = y, r = y }
    NumOps.add { l = x_sq, r = y_sq }
```

### 2.5 Higher-Kinded Types (HKT)
- **NOT CURRENTLY SUPPORTED** in explicit form
- No direct support for type constructors as values
- Cannot express `Functor`, `Monad`, or similar HKT patterns
- Possible future enhancement noted in VSCode extension syntax file

---

## 3. OPAQUE TYPES

### 3.1 Visibility Modifiers
Funk supports public/private visibility at module level:

```funk
# Private type (default)
type Secret = { password: String }

# Public type (exported from module)
pub type Public = { value: String }
```

### 3.2 Type Implementation Hiding
- Types can be exported without exposing constructors (via selective import)
- Module system controls export of type constructors
- Example: `use math::geometry::{area, Shape as alias}`

### 3.3 Opaque Type Patterns
While true opaque types aren't explicitly supported, can be achieved via:
1. Newtype wrapper: `type UserId = | UserId { value: Int } |`
2. Module-level abstraction with selective exports
3. Abstract records in module interface

---

## 4. CURRENT TYPE SYSTEM FEATURES

### 4.1 Record Types

#### Closed Records
```funk
# Inferred type from literal
let person = { name = "Alice", age = 30 }

# Explicit type in annotations
let get_age = { p: { name: String, age: Int } } -> p.age
```

#### Open Records (Row Polymorphism)
```funk
# Accepts records with extra fields
let greet = { person: { name: String, ... } } ->
    "Hello, $(person.name)!"

# Preserves extra fields in updates
let get_common = { p: { name: String, age: Int, ... } } ->
    { name = p.name, age = p.age }
```

**Features:**
- Field access: `record.fieldName`
- Record update: `{ ...record, field = newValue }`
- Spread syntax: `{ ...otherRecord, field = value }`
- Pattern matching: `{ name, age, ... }`

### 4.2 Variant Types

#### Closed Variants
```funk
type Shape = | Circle { radius: Float }, Rectangle { width: Float, height: Float } |

let area = { shape: Shape } ->
    match shape is
      Circle { radius } -> 3.14159 * radius * radius
      Rectangle { width, height } -> width * height
```

#### Open Variants
```funk
type Message = | Text { content: String }, Number { value: Int }, ... |

let process = { msg: Message } ->
    match msg is
      Text { content } -> content
      Number { value } -> "number"
      _ -> "unknown"
```

**Features:**
- Unit constructors (no data): `type Color = | Red, Green, Blue |`
- Data constructors: `Circle { radius: Float }`
- Mix of both: `type Maybe2 'a 'b = | Nothing, JustFirst { first: 'a }, Both { first: 'a, second: 'b } |`
- Pattern matching with wildcard fallback
- Exhaustiveness checking (with warnings for non-exhaustive matches)

### 4.3 Function Types

#### Basic Function Types
```funk
# Single record parameter
let add = { x: Int, y: Int } -> x + y

# Type annotation
let multiply: { x: Int, y: Int } -> Int = { x, y } -> x * y
```

#### Higher-Order Functions
```funk
# Function as parameter
let apply_twice = { f: { x: Int } -> Int, x: Int } ->
    f { x = f { x = x } }

# Returning functions (currying)
let make_adder = { n: Int } ->
    { x: Int } -> x + n
```

#### Function Type Structure
All functions take a single **record type** as parameter (curried functions take record params)

```
ParamType: { field1: Type1, field2: Type2, ... }
ReturnType: Type
FunctionType: { field1: Type1, field2: Type2, ... } -> Type
```

### 4.4 Effect Types

#### Effect Declaration
```funk
effect Log having 'state, 'error {
    println: { msg: String } -> {},
    debug: { msg: String } -> {},
    info: { msg: String } -> {}
}
```

#### Effect Row Polymorphism
```funk
# Function with effects
let greet_with_log = { name: String } <Log> -> do
    Log.println { msg = "Starting..." }
    "Hello, $(name)!"

# Effect handlers
let main = {} <Log> ->
    perform Console in do
        Log.println { msg = "With effect!" }
```

#### Effect Features
- Effect rows: `<Effect1, Effect2, ...>` or `<Effect1, ...>` (open effect row)
- Handlers via `perform` expressions
- Suspended computations for unhandled effects: `Suspended 'a = Suspended { value: 'a, effects: EffectRow }`
- Multiple handlers per perform expression

### 4.5 Ability System (Type Classes)

#### Ability Declaration
```funk
ability Show 'a {
    show: { val: 'a } -> String
}

# With dependencies
ability Ord 'a with Eq 'a {
    compare: { l: 'a, r: 'a } -> Ordering,
    less_than: { l: 'a, r: 'a } -> Boolean
}

# Multiple parameters
ability Map 'a 'b {
    map: { f: { x: 'a } -> 'b, container: Container 'a } -> Container 'b
}
```

#### Ability Implementation
```funk
impl Show Int where {
    show = { val } -> int::to_string { val = val }
}

impl Show Bool where {
    show = { val } -> if val then "true" else "false"
}
```

#### Built-in Abilities
- `Eq`: Equality comparison
- `Ord`: Ordering/comparison
- `NumAdd`, `NumSub`, `NumMul`, `NumDiv`, `NumMod`: Numeric operations
- `Inspect`: String representation

#### Automatic Implementations
Built-in types have automatic ability implementations:
- Primitives (Int, Float, String, Boolean, Nat, Frac) implement Eq, Ord, NumAdd, etc.
- Container types (List, Option, Result, Map, Set) implement abilities if element types do
- Record and variant types can implement custom abilities

---

## 5. TYPE CHECKER AST/REPRESENTATION

### 5.1 FunkType Sealed Class Hierarchy

The type system is represented via `sealed interface FunkType`:

```
Primitive Types:
  - IntType
  - FloatType
  - StringType
  - BooleanType
  - NatType (Natural numbers)
  - FracType (Fractions/Rationals)
  - OrderingType

Structured Types:
  - RecordType(fields: Map<String, FunkType>, rowVar: RowVar?)
  - VariantType(constructors: Map<String, FunkType?>, rowVar: RowVar?)
  - ListType(elementType: FunkType)
  - MapType(keyType: FunkType, valueType: FunkType)
  - SetType(elementType: FunkType)
  - ArrayListType(elementType: FunkType)  // Java ArrayList compatibility
  - ArrayType(elementType: FunkType)      // Native arrays

Generic Types:
  - VarType(variable: TypeVar)            // Type variables ('a, 'b)
  - TypeApplication(constructor, args)   // Applied generics (Option Int, Result String Error)

Function Types:
  - FunctionType(paramType, returnType, effects?, abilityConstraints?)
  
Effect/Ability:
  - EffectRow(effects: Set<String>, rowVar: RowVar?)
  - SuspendedType(valueType: FunkType, effects: EffectRow)
  - FunctionAbilityConstraint(abilityName, typeArgs)
```

### 5.2 Type Variables (TypeVar)
```kotlin
data class TypeVar(val name: String, val level: Int = 0)
```
- Supports fresh variable generation with automatic naming
- Level tracking for generalization/instantiation
- Names: 'a', 'b', 'c', ... or custom names via `freshNamed()`

### 5.3 Row Variables (RowVar)
```kotlin
data class RowVar(val name: String)
```
- Used for open record/variant/effect polymorphism
- Enables structural subtyping via row polymorphism
- Fresh generation: `row0`, `row1`, etc.

### 5.4 Type Schemes (Let-Polymorphism)
```kotlin
data class TypeScheme(
    val quantifiedVars: Set<TypeVar>,
    val type: FunkType,
    val constraints: List<AbilityConstraint> = emptyList()
)
```
- Represents universally quantified types: `∀α₁,α₂,...αₙ. τ`
- Supports ability constraints on quantified variables
- Instantiation creates fresh type variables with constraint transfer

### 5.5 Type Aliases Handling
- Registry: `typeAliases: MutableMap<String, TypeAliasInfo>`
- Currently minimal support - mainly record aliases
- Type alias information preserved for pretty-printing
- Aliases expanded during type checking (no transparent aliases yet)

---

## 6. GAPS AND LIMITATIONS

### 6.1 Not Currently Supported

1. **Higher-Kinded Types (HKT)**
   - No Functor, Monad, etc.
   - Cannot parameterize over type constructors
   - Noted in VSCode extension as future work

2. **Explicit Opaque Types**
   - Must use newtype pattern or module-level abstraction
   - No `opaque type` keyword

3. **Phantom Types**
   - No built-in phantom type support
   - Could be encoded manually via type variables

4. **GADTs (Generalized Algebraic Data Types)**
   - Type refinement in pattern matching limited
   - No dependent type features

5. **Associated Types** 
   - Abilities can have associated types but limited support
   - Not fully integrated with type checking

6. **Type Families**
   - No support for type-level computation
   - No functional dependencies

7. **Rank-N Polymorphism**
   - Only rank-1 (prenex) polymorphism supported
   - Cannot express `forall 'a. (forall 'b. f 'a 'b) -> result`

8. **Existential Types**
   - No explicit existential quantification
   - Cannot hide type parameters in return types

### 6.2 Partial/Limited Support

1. **Type Aliases**
   - Basic aliases work but limited to simple cases
   - No recursive type aliases
   - No transparent definition of new types

2. **Ability Associated Types**
   - Declared but minimal integration
   - Not fully leveraged in type checking

3. **Effect Polymorphism**
   - Open effect rows supported via `<Effect1, ...>`
   - Limited effect type composition

---

## 7. BUILT-IN TYPES AND TYPE CONSTRUCTORS

### 7.1 Primitive Types
```
Int         - 64-bit signed integers
Float       - IEEE 754 double precision
String      - Unicode strings with interpolation
Boolean     - true/false
Nat         - Natural numbers (non-negative)
Frac        - Rational numbers (numerator/denominator)
```

### 7.2 Core Generic Types
```
Option 'a = | Some {value: 'a}, None |
Result 'v 'e = | Ok {value: 'v}, Err {error: 'e} |
List 'a     - Linked list representation
Map 'k 'v   - HashMap-backed dictionary
Set 'a      - HashSet-backed unique collection
```

### 7.3 Special Types
```
Ordering = | LT, EQ, GT |          - For comparison operations
Suspended 'a = Suspended {value: 'a, effects: EffectRow}  - Unhandled effects
```

---

## 8. EXAMPLES FROM THE CODEBASE

### Example 1: Multi-Parameter Generic Type
```funk
type Either 'a 'b = | Left {val: 'a}, Right {val: 'b} |
type Pair 'a 'b = | MkPair { first: 'a, second: 'b } |
type Result 'ok 'err = | Ok {value: 'ok}, Err {error: 'err} |
```

### Example 2: Record Polymorphism
```funk
let get_name_and_age = { p: { name: String, age: Int, ... } } ->
    { name = p.name, age = p.age }

# Works with:
let person1 = { name = "Charlie", age = 35, city = "NYC" }
let person2 = { name = "Diana", age = 28, country = "USA", zip = 10001 }
```

### Example 3: Ability Constraints
```funk
ability Show 'a {
    show: { val: 'a } -> String
}

let display = { value: 'a } with Show 'a ->
    "Value: $(Show.show { val = value })"

impl Show Int where {
    show = { val } -> int::to_string { val = val }
}
```

### Example 4: Effect Types
```funk
let greet_with_log = { name: String } <Log> -> do
    Log.info { msg = "Starting greeting..." }
    let message = "Hello, $(name)!"
    Log.println { msg = message }

let main = {} ->
    perform Console in do
        greet_with_log { name = "Funk" }
```

---

## 9. SUMMARY TABLE

| Feature | Status | Notes |
|---------|--------|-------|
| Primitive Types | Full | Int, Float, String, Boolean, Nat, Frac |
| Records (Closed) | Full | Fixed field sets with type inference |
| Records (Open) | Full | Row polymorphism via `...` syntax |
| Variants (Closed) | Full | Fixed constructor sets |
| Variants (Open) | Full | Extensible with wildcard matching |
| Generic Types | Full | Multi-parameter generics with 'a, 'b, etc. |
| Type Parameters | Full | Up to any number, no constraints on arity |
| Type Constraints | Partial | Ability constraints work well |
| Type Aliases | Limited | Basic support, not recursive |
| Opaque Types | No | Can encode via newtypes |
| Phantom Types | No | Could encode manually |
| Function Types | Full | Record parameters, higher-order support |
| Effect Types | Full | Effect rows, polymorphic effects |
| Abilities (Type Classes) | Full | Custom abilities with implementations |
| HKT | No | Not supported |
| GADTs | No | Not supported |
| Rank-N Polymorphism | No | Only rank-1 |
| Existential Types | No | Not supported |
| Type Families | No | Not supported |

---

## 10. IMPLEMENTATION NOTES FROM CODEBASE

### Type Representation
- All types unified via sealed `FunkType` interface
- Parser AST produces `TypeAnnotation` hierarchy
- TypeChecker converts to `FunkType` during type checking

### Type Inference
- Bidirectional type checking enabled
- Usage constraints for inference propagation
- Constraint solver for unification
- Let-polymorphism with type schemes

### Module System
- Type visibility via `pub`/private modifiers
- Selective imports preserve type abstraction
- Module-qualified names: `module::function`
- Cross-module type preservation

### Error Handling
- Rich error types: TypeMismatch, UndefinedVariable, MissingField, etc.
- Source location tracking for error reporting
- Constraint violation detection and reporting

