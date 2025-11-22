# Abilities in Funk: Complete Guide

## What Are Abilities?

Abilities are Funk's implementation of **type classes** - a mechanism for ad-hoc polymorphism that allows defining operations that work across multiple types. They enable you to write generic functions that can operate on any type that implements a specific set of operations.

## Syntax

### Declaring an Ability

```funk
ability AbilityName 'a where {
  operation_name : {param: 'a} -> ReturnType
}
```

**Example - Show ability:**
```funk
ability Show 'a where {
  show : {val: 'a} -> String
}
```

### Multi-Parameter Abilities

```funk
ability Convert 'a 'b where {
  convert : {val: 'a} -> 'b
}
```

### Ability Dependencies (Superclasses)

Abilities can depend on other abilities using the `with` keyword:

```funk
ability Eq 'a where {
  equals : {left: 'a, right: 'a} -> Bool
}

ability Ord 'a with Eq 'a where {
  compare : {left: 'a, right: 'a} -> String
}
```

Multiple dependencies:
```funk
ability Serializable 'a with Show 'a, Eq 'a where {
  serialize : {val: 'a} -> String
}
```

### Implementing an Ability

```funk
impl AbilityName TargetType where {
  operation_name : {param: TargetType} -> ReturnType = {param} -> implementation
}
```

**Example:**
```funk
impl Show Int where {
  show : {val: Int} -> String = {val} -> int::to_string {n = val}
}
```

### Using Abilities

**Direct method call:**
```funk
let result = Show.show {val = 42}  // "42"
```

**In polymorphic functions:**
```funk
let printValue : {x: 'a} -> String with Show 'a = {x} ->
  Show.show {val = x}
```

## Built-in Abilities

### Numeric Operations
Available for `Int`, `Frac`, `Nat`:
- **NumAdd** - Addition (`+`)
- **NumSub** - Subtraction (`-`)
- **NumMul** - Multiplication (`*`)
- **NumDiv** - Division (`/`)
- **NumMod** - Modulo (`%`)

### Comparison Operations
- **Eq** - Equality (`==`, `!=`) - for Int, Frac, Nat, Bool, String
- **Ord** - Ordering (`<`, `>`, `<=`, `>=`) - for Int, Frac, Nat, String
  - Note: Ord depends on Eq

### Logical Operations
Available for `Bool`:
- **LogicalAnd** - `&&`
- **LogicalOr** - `||`
- **LogicalNot** - `!`

### String Operations
- **StringConcat** - Strings can be concatenated using string interpolation: `"$(a)$(b)"`

### Universal Operations
- **Inspect** - Available for ALL types (including type variables)
  - `to_str : {val: 'a} -> String`
  - Used for debugging and display

## Type Inference and Constraints

### Automatic Constraint Generation

When you use operators, the type checker automatically infers ability constraints:

```funk
let add = {x, y} -> x + y
// Inferred type: {x: 'a, y: 'a} -> 'a with NumAdd 'a
```

### Multiple Constraints

```funk
let addAndShow = {x, y} ->
  let result = x + y
  Inspect.to_str {val = result}

// Inferred type: {x: 'a, y: 'a} -> String with NumAdd 'a, Inspect 'a
```

### Explicit Constraints

You can explicitly specify constraints in function signatures:

```funk
let compare : {x: 'a, y: 'a} -> Bool with Ord 'a = {x, y} ->
  x < y
```

## Implementation Details

### How Abilities Work

1. **Ability Declarations** are stored as records of operation signatures
2. **Implementations** are stored with composite keys: `"AbilityName_TargetType"`
3. **Type checking** validates that implementations exist for concrete types
4. **Constraints** are collected during type inference and checked at call sites
5. **Runtime** retrieves the appropriate implementation from the context

### Resolution Order

1. **Parse time:** Ability structure captured in AST
2. **Type-check time:**
   - Abilities registered globally
   - Implementations validated and registered
   - Constraints collected from operators/method calls
3. **Call-site validation:**
   - For concrete types: implementation must exist
   - For polymorphic types: constraint is added
4. **Runtime:** Implementation looked up and executed

### Scoping Rules

- Abilities are declared at module level
- Implementations are module-level
- **Imported modules automatically bring their ability implementations**
- No shadowing or overriding of implementations

### Module Import Behavior

**How ability implementations are imported:**

```funk
// Module A: define type and ability impl
pub type Person = { name: String, age: Int }

pub ability Show 'a where {
    show : {val: 'a} -> String
}

pub impl Show Person where {
    show : {val: Person} -> String = {val} ->
        "$(val.name) ($(Inspect.to_str {val = val.age}))"
}

// Module B: use the type
use module_a::{Person, Show}

let p = {name = "Alice", age = 30}
let result = Show.show {val = p}  // impl Show Person is automatically available!
```

**Key Rules:**
1. **Type imports are explicit**: `use module::{MyType}`
2. **Ability declaration imports are explicit**: `use module::{MyAbility}`
3. **Ability implementations are AUTOMATIC**: When you import a module, all its ability implementations come with it
4. **No explicit impl import syntax**: Implementations are part of the module context

**Comparison with Rust:**
- **Rust**: Implementations are globally available once a crate is compiled (trait must be in scope to use)
- **Funk**: Implementations are module-scoped and automatically imported with the module
- **Funk is stricter**: No orphan instances, implementations tied to modules

## Advanced Features

### Polymorphic Constraints in Match Expressions

```funk
let processValue : {x: 'a} -> String with NumAdd 'a, Inspect 'a = {x} ->
  let result = x + x
  Inspect.to_str {val = result}
```

### Constraint Propagation

Constraints propagate through function composition:

```funk
let double = {x} -> x + x  // with NumAdd 'a

let showDouble = {x} ->    // with NumAdd 'a, Inspect 'a
  Inspect.to_str {val = double {x = x}}
```

### Inspect is Special

The `Inspect` ability is available for **all types** (including type variables) to prevent exponential blowup in constraint solving:

```funk
let debug = {x} -> Inspect.to_str {val = x}
// Works for ANY type 'a
```

## Common Patterns

### Generic Equality Check

```funk
let isEqual : {x: 'a, y: 'a} -> Bool with Eq 'a = {x, y} ->
  x == y
```

### Generic Comparison

```funk
let max : {x: 'a, y: 'a} -> 'a with Ord 'a = {x, y} ->
  match x > y with
  | true -> x
  | false -> y
  end
```

### Type Conversion

```funk
ability ToString 'a where {
  to_string : {val: 'a} -> String
}

impl ToString Int where {
  to_string : {val: Int} -> String = {val} -> int::to_string {n = val}
}
```

## Error Cases and Validation

### Missing Implementation

```funk
ability Show 'a where { show : {val: 'a} -> String }

let test = Show.show {val = {x = 1, y = 2}}
// ERROR: No implementation found for ability 'Show' with type(s) [{x: Int, y: Int}]
```

### Incomplete Implementation

```funk
ability Example 'a where {
  op1 : {x: 'a} -> String
  op2 : {x: 'a} -> Int
}

impl Example Int where {
  op1 : {x: Int} -> String = {x} -> "test"
  // ERROR: Missing operation 'op2' in implementation
}
```

### Type Mismatch

```funk
ability Show 'a where { show : {val: 'a} -> String }

impl Show Int where {
  show : {val: Int} -> Int = {val} -> val
  // ERROR: Return type mismatch (expected String, got Int)
}
```

### Circular Dependencies

```funk
ability A 'a with B 'a where { ... }
ability B 'a with A 'a where { ... }
// ERROR: Circular dependency detected
```

## Abilities vs Effects

Abilities and effects are **separate systems** in Funk:

### Abilities (Type Classes)
- **Purpose:** Ad-hoc polymorphism over types
- **When resolved:** Compile-time (with runtime dispatch)
- **Syntax:** `ability`, `impl`, constraints with `with`
- **Example use case:** Generic operations like equality, arithmetic, serialization

### Effects (Algebraic Effects)
- **Purpose:** Managed side effects with custom handlers
- **When resolved:** Runtime via handlers/performers
- **Syntax:** `effect`, `perform`, `impl ... for Performer`
- **Example use case:** I/O, state, exceptions, async

**Key Difference:**
- Abilities: "What operations does this **type** support?"
- Effects: "What side effects can this **computation** perform?"

## Implementation Validation Rules

When implementing an ability, the following rules are enforced:

1. All declared operations must be implemented
2. No extra operations beyond those declared
3. Parameter types must match (after type substitution)
4. Return types must match (after type substitution)
5. Type parameter count must match
6. Implementation return types must be concrete (no free type variables)
7. Implementation parameter types must be concrete (no free type variables)
8. Implementation body must type-check correctly

## Best Practices

1. **Keep abilities focused** - Each ability should represent a single coherent concept
2. **Use descriptive operation names** - `show`, `compare`, `convert` are clear
3. **Leverage dependencies** - If Ord requires Eq, express that with `with Eq 'a`
4. **Provide implementations for common types** - Int, String, Bool, etc.
5. **Use Inspect for debugging** - It's universally available
6. **Let the type checker infer constraints** - Explicit constraints are rarely needed

## Examples

### Complete Working Example

```funk
// Define ability
ability Show 'a where {
  show : {val: 'a} -> String
}

// Implement for Int
impl Show Int where {
  show : {val: Int} -> String = {val} -> int::to_string {n = val}
}

// Implement for Bool
impl Show Bool where {
  show : {val: Bool} -> String = {val} ->
    match val with
    | true -> "true"
    | false -> "false"
    end
}

// Use in polymorphic function
let display : {x: 'a} -> String with Show 'a = {x} ->
  Show.show {val = x}

// Call with different types
let result1 = display {x = 42}        // "42"
let result2 = display {x = true}      // "true"
```

### Multi-Parameter Example

```funk
ability Convert 'a 'b where {
  convert : {val: 'a} -> 'b
}

impl Convert Int String where {
  convert : {val: Int} -> String = {val} -> int::to_string {n = val}
}

impl Convert String Int where {
  convert : {val: String} -> Int = {val} -> string::to_int {s = val}
}

let transform : {x: 'a} -> 'b with Convert 'a 'b = {x} ->
  Convert.convert {val = x}
```

## Detailed Comparison with Other Languages

### Comparison with Haskell Type Classes

**Similarities:**
1. **Core concept is identical** - Both enable ad-hoc polymorphism through type classes
2. **Constraint syntax** - Funk's `with Eq 'a` is equivalent to Haskell's `Eq a =>`
3. **Superclass constraints** - `ability Ord 'a with Eq 'a` mirrors Haskell's `class Eq a => Ord a`
4. **Multi-parameter support** - Funk's `ability Convert 'a 'b` is like Haskell's `MultiParamTypeClasses` extension
5. **Instance checking** - Both validate implementations exist at call sites
6. **Full type inference** - Both support complete type inference with constraint propagation

**Differences:**
1. **Syntax** - Funk uses `ability`/`impl` vs Haskell's `class`/`instance`
2. **Method call syntax** - Funk uses **explicit** `Show.show {val = x}` vs Haskell's **automatic** `show x`
   - Funk's approach makes it clear which ability is being used
   - No ambiguity about where methods come from
3. **No overlapping instances** - Funk has stricter instance uniqueness (one implementation per type)
4. **Inspect is universal** - Funk made `Inspect` available for ALL types to avoid constraint explosion
   - Haskell's `Show` requires explicit instances for every type
   - Pragmatic choice for better ergonomics
5. **No orphan instances** - Implementations must be with the type or ability definition
   - Prevents confusion about which implementation is in scope
6. **No default methods** - Haskell allows default implementations in type classes; Funk requires all operations
7. **Simpler module system** - No qualified imports or hiding; abilities are global
8. **No higher-kinded types** - Funk doesn't support `Functor`, `Monad`, etc. (see below)

### Comparison with Rust Traits

**Similarities:**
1. **Core concept** - Both provide trait-based polymorphism
2. **Implementation blocks** - Funk's `impl AbilityName Type` mirrors Rust's `impl Trait for Type`
3. **Associated operations** - Both define methods that types must implement
4. **Static dispatch preference** - Both resolve at compile-time when types are known
5. **Constraint syntax** - Similar to Rust's `where T: Trait` bounds
6. **Multi-parameter traits** - Both support multiple type parameters

**Differences:**
1. **No associated types** - Funk doesn't have Rust's associated types (like `type Item` in `Iterator`)
   - Funk uses multi-parameter abilities instead (e.g., `ability Iterator 'iter 'item`)
   - Less ergonomic but maintains type inference
2. **Method call syntax** - Funk's `Ability.method {val = x}` vs Rust's `x.method()` or `Trait::method(&x)`
   - Rust allows method-call syntax (automatic dispatch)
   - Funk requires explicit ability namespace
3. **Different abstraction levels** - Rust is systems programming; Funk is high-level
   - Rust: zero-cost abstractions, explicit memory management
   - Funk: Truffle interpreter, GC, focus on clarity over performance
4. **No default implementations** - Rust allows default method bodies; Funk requires full implementation
   - **This is intentional** - Funk prioritizes explicitness over convenience
   - Avoids confusion about where implementations come from
5. **Simpler coherence rules** - Rust has complex orphan rules with blanket impls; Funk is stricter and simpler
6. **No specialization** - Rust has (unstable) specialization for overlapping impls; Funk has one impl per type
7. **Full type inference** - Funk infers types completely; Rust often requires type annotations
8. **No lifetimes** - Funk doesn't have ownership/borrowing, so no lifetime parameters in abilities
9. **No const generics** - Rust can have `trait Foo<const N: usize>`; Funk only has type parameters
10. **No macros/derive** - Rust has powerful macro system; Funk avoids metaprogramming (for now)

### Comparison with Swift Protocols

**Similarities:**
1. **Protocol-oriented polymorphism** - Both enable generic programming through protocols/abilities
2. **Associated operations** - Both define required methods
3. **Protocol constraints** - Similar constraint syntax on generic functions
4. **Extension-like implementations** - Both allow implementing for existing types

**Differences:**
1. **Method call syntax** - Funk uses `Ability.method {val = x}` vs Swift's `value.method()`
2. **No associated types** - Swift has powerful associated types; Funk doesn't
3. **No protocol extensions** - Swift allows default implementations via extensions; Funk doesn't
4. **No Self type** - Swift protocols can reference `Self`; Funk uses explicit type parameters
5. **No class/struct distinction** - Swift has reference/value type differences; Funk is simpler
6. **Full type inference** - Funk has complete inference; Swift often needs annotations
7. **No protocol composition at type level** - Swift has `Codable & Equatable`; Funk uses dependencies
8. **No existential types** - Swift has `any Protocol`; Funk doesn't support type erasure
9. **Simpler generics** - Swift has complex generic system; Funk is more minimal

### Higher-Kinded Polymorphism

**Does Funk support higher-kinded types?**

**NO** - Funk does NOT support higher-kinded polymorphism.

**What this means:**
- Type parameters are always of kind `*` (concrete types like `Int`, `String`, `{x: Int}`)
- You CANNOT abstract over type constructors like `List`, `Option`, `Result`
- You CANNOT write generic abstractions like `Functor`, `Monad`, `Applicative`

**Example of what you CANNOT do:**
```funk
// This is NOT possible in Funk:
ability Functor 'f where {
  map : {container: 'f 'a, fn: 'a -> 'b} -> 'f 'b
}

impl Functor List where {
  map = ...
}

impl Functor Option where {
  map = ...
}
```

**What you CAN do instead:**
```funk
// Separate abilities for each container type
ability ListOps 'a where {
  map : {list: List 'a, fn: 'a -> 'b} -> List 'b
}

ability OptionOps 'a where {
  map : {opt: Option 'a, fn: 'a -> 'b} -> Option 'b
}
```

**Does this break full type inference?**

**NO!** - Funk maintains complete type inference WITHOUT higher-kinded types because:

1. **All type parameters are kind `*`** - Keeps unification simple and decidable
2. **Hindley-Milner basis** - The type system is based on HM which has complete inference for rank-1 polymorphism
3. **Constraint solving is decidable** - No higher-order unification problems
4. **No annotations required** - All the test code shows extensive polymorphism with zero type annotations

**Why this design choice?**

This is **deliberate** - Funk prioritizes:
1. ✅ **Complete type inference** without any annotations
2. ✅ **Simple, understandable type errors**
3. ✅ **Decidable type checking** - Always terminates
4. ✅ **Practical polymorphism** for 95% of use cases
5. ✅ **Lower learning curve** - Easier to understand than Haskell's kind system

**Trade-offs:**
- ❌ Can't write generic container operations (map, filter, fold over all containers)
- ❌ Can't abstract over monads/functors/applicatives
- ❌ More repetitive code for similar operations on different containers
- ✅ But you get perfect type inference and simpler mental model

### Ability Lookup and Scoping Resolution

**How are abilities looked up and resolved?**

#### Scoping Rules

**1. Ability Declarations**
- Stored globally in `abilityDeclarationMap: MutableMap<String, AbilityDeclaration>`
- Key: Simple ability name (e.g., `"Show"`, `"Eq"`, `"Ord"`)
- Scope: Module-level, imported with modules

**2. Ability Implementations**
- Stored in execution context with composite keys
- Key format: `"AbilityName_TargetType"`
  - Example: `"Show_Int"`, `"Eq_String"`
- For multi-parameter abilities: `"AbilityName_Type1_Type2"`
  - Example: `"Convert_Int_String"`
- Scope: Module-level, imported with modules

**3. Resolution Order (Step-by-Step)**

**Phase 1: Type Checking (Compile Time)**
```
1. Parse ability declarations → Register in abilityDeclarationMap
2. Parse implementations → Validate against declarations
3. Register implementations with composite keys in context
4. When encountering Ability.method call:
   a. Look up ability in abilityDeclarationMap
   b. Extract method signature from ability record
   c. Return polymorphic method type
5. When type checking the call:
   a. Unify argument types with method signature
   b. If all types concrete → check implementation exists
   c. If types polymorphic → add ability constraint to type variable
```

**Phase 2: Constraint Solving**
```
1. Collect all ability constraints: Set<AbilityConstraint(typeVar, abilityName)>
2. For each constraint:
   a. Check if type variable has been unified to concrete type
   b. If unified → verify implementation exists for that type
   c. If still polymorphic → propagate constraint to caller
3. Check ability dependencies (superclasses):
   a. If Ord 'a required, ensure Eq 'a also satisfied
   b. Compute transitive closure of dependencies
   c. Validate all dependencies have implementations
```

**Phase 3: Runtime (Interpreter)**
```
1. Ability declarations create placeholder records
2. Implementations create actual function records
3. On Ability.method call:
   a. Determine concrete type of argument at runtime
   b. Construct lookup key: "AbilityName_ConcreteType"
   c. Retrieve implementation record from context
   d. Extract method function from record
   e. Apply function to argument
   f. Return result
```

**Module Import Resolution**

When you import a module:
```funk
import MyModule
```

The following are brought into scope:
1. All ability declarations from that module
2. All ability implementations from that module
3. All functions, values, types, effects, etc.

All imported items are merged into the current context.

**Key Scoping Rules:**

1. **No shadowing** - If two modules both define `impl Show Int`, it's a compile error
2. **Global namespace** - Abilities are not namespaced by module path
3. **Explicit imports required** - Must import modules to get their implementations
4. **Deterministic lookup** - Composite key ensures exactly one implementation per type
5. **No dynamic dispatch overhead** - Implementation resolved statically when types are known

**Implementation Lookup Algorithm:**
```
lookupAbilityImplementation(abilityName: String, types: List<FunkType>):
  1. Extract concrete type names from types
  2. Construct key: "${abilityName}_${types.joinToString("_")}"
  3. Lookup in context[key]
  4. If found → return implementation record
  5. If not found → throw type error with clear message
```

**Constraint Validation Algorithm:**
```
validateConstraints(constraints: Set<AbilityConstraint>, substitutions: Map<TypeVar, FunkType>):
  1. For each constraint AbilityConstraint(typeVar, abilityName):
     a. Apply substitutions to get concrete type (if any)
     b. If still polymorphic → propagate to caller's constraints
     c. If concrete → verify lookupAbilityImplementation succeeds
     d. Check ability dependencies recursively
     e. Ensure transitive closure of dependencies satisfied
```

## Language Comparison Summary

| Feature | Haskell | Rust | Swift | Funk |
|---------|---------|------|-------|------|
| **Core mechanism** | Type classes | Traits | Protocols | Abilities |
| **Syntax (declaration)** | `class` | `trait` | `protocol` | `ability` |
| **Syntax (implementation)** | `instance` | `impl Trait for Type` | `extension Type: Protocol` | `impl Ability Type` |
| **Method call syntax** | Automatic dispatch | Method/UFCS | Method | **Explicit namespace** |
| **Higher-kinded types** | ✅ Yes | ❌ No | ❌ No | ❌ No |
| **Associated types** | ✅ Yes | ✅ Yes | ✅ Yes | ❌ No |
| **Default implementations** | ✅ Yes | ✅ Yes | ✅ Yes | ❌ No |
| **Superclass/supertrait** | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes (`with`) |
| **Multi-parameter** | ✅ Extension | ✅ Yes | ❌ No | ✅ Yes |
| **Full type inference** | ✅ Yes | ⚠️ Partial | ⚠️ Partial | ✅ Yes |
| **Overlapping instances** | ⚠️ With extensions | ⚠️ With specialization | ✅ Yes | ❌ No |
| **Orphan instances** | ⚠️ Allowed | ⚠️ With orphan rules | ✅ Allowed | ❌ Forbidden |
| **Coherence guarantee** | ✅ Yes | ✅ Yes | ⚠️ Runtime | ✅ Yes |
| **Type erasure** | ❌ No | ⚠️ `dyn Trait` | ✅ `any Protocol` | ❌ No |

## Critical Analysis: Strengths and Weaknesses

### Strengths of Funk's Ability System

1. **✅ Explicit method calls** (`Show.show {val = x}`)
   - No ambiguity about which ability is being used
   - Easy to understand where methods come from
   - Great for teaching and learning
   - Better IDE support potential

2. **✅ Complete type inference**
   - Zero type annotations required in most code
   - Constraint inference from operators
   - Simpler than Rust, on par with Haskell

3. **✅ Simple coherence rules**
   - One implementation per type, period
   - No orphan instance confusion
   - Deterministic behavior
   - Easy to reason about

4. **✅ Universal Inspect**
   - Pragmatic solution to constraint explosion
   - Every value can be inspected/debugged
   - Better developer experience

5. **✅ Clean separation from effects**
   - Type-level polymorphism (abilities) separate from effect handling
   - Clear mental model

6. **✅ Decidable type checking**
   - Always terminates
   - No infinite loops in constraint solving
   - Predictable compile times

### Weaknesses and Missing Features

1. **❌ No higher-kinded types**
   - **Impact:** Cannot abstract over type constructors
   - **Missing:** Generic `map`, `flatMap`, `traverse` for all containers
   - **Workaround:** Duplicate code for each container type
   - **Fix difficulty:** Hard - would require significant type system changes

2. **⚠️ No default method implementations (intentional design choice)**
   - **Impact:** Must implement every operation explicitly
   - **Example:** Must implement both `==` and `!=` separately
   - **Rationale:** Explicitness over magic - you know exactly what code runs
   - **Note:** This is a feature, not a bug - avoids hidden complexity

3. **⚠️ No associated types (multi-parameter abilities instead)**
   - **Impact:** Less ergonomic syntax, but functionally equivalent
   - **Example:** `ability Iterator 'iter 'item` instead of `trait Iterator { type Item }`
   - **Trade-off:** Multi-parameter abilities maintain full type inference
   - **Note:** This is a deliberate trade-off for inference

4. **❌ No orphan instances**
   - **Impact:** Can't implement external abilities for external types
   - **Example:** Can't add `Show` for third-party library type in your code
   - **Workaround:** Wrapper types (newtype pattern)
   - **Fix difficulty:** Easy - but may break coherence guarantees

5. **⚠️ No specialization/overlapping instances (intentional)**
   - **Impact:** One implementation per type, no performance optimizations via specialization
   - **Rationale:** Funk is high-level/interpreted - performance is a non-goal
   - **Note:** Simplicity and coherence are more important than optimization
   - **Trade-off:** Clearer semantics, simpler implementation

6. **❌ Global namespace for abilities**
   - **Impact:** Naming conflicts across modules
   - **Example:** Two libraries both define `Serializable` ability
   - **Workaround:** Unique naming conventions
   - **Fix difficulty:** Medium - module system changes

7. **⚠️ Explicit method syntax can be verbose**
   - **Impact:** More typing required than automatic dispatch
   - **Example:** `Show.show {val = x}` vs Haskell's `show x`
   - **Trade-off:** Clarity vs brevity
   - **Fix:** Could add automatic dispatch as sugar (but loses clarity)

8. **❌ No constraint kinds/constraint synonyms**
   - **Impact:** Can't group commonly-used constraint sets
   - **Example:** Can't define `Serializable = Show + Eq + Ord`
   - **Workaround:** Use ability dependencies or list all constraints
   - **Fix difficulty:** Medium - syntax extension

### What Can Be Improved?

#### High-Priority Improvements

1. **Add constraint synonyms**
   ```funk
   constraint Serializable 'a = Show 'a, Eq 'a, Ord 'a

   let process : {x: 'a} -> String with Serializable 'a = ...
   ```
   **Why:** Better ergonomics for common constraint combinations

2. **Module-qualified abilities**
   ```funk
   import MyLib qualified

   let x = MyLib.Show.show {val = 42}
   ```
   **Why:** Avoids naming conflicts, supports library ecosystem

3. **Better error messages**
   ```
   ERROR: No implementation found for ability 'Show' with type '{x: Int, y: Int}'

   HINT: You can define one with:

   impl Show {x: Int, y: Int} where {
     show : {val: {x: Int, y: Int}} -> String = {val} ->
       "Record {x = $(Inspect.to_str {val = val.x}), y = $(Inspect.to_str {val = val.y})}"
   }
   ```
   **Why:** Better developer experience, easier learning curve

#### Medium-Priority Improvements

4. **Controlled orphan instances**
   ```funk
   // Explicit opt-in for orphan instances
   impl orphan Show ThirdPartyType where {
     ...
   }
   ```
   **Why:** Better library interop
   **Caveat:** Must maintain coherence

5. **Operator overloading sugar**
   ```funk
   // Automatically desugar to ability calls
   x + y  →  NumAdd.add {left = x, right = y}
   x == y  →  Eq.equals {left = x, right = y}
   ```
   Currently operators use built-in dispatch; making it ability-based would be cleaner.

#### Low-Priority / Future Research

6. **Higher-kinded types (major undertaking)**
   - Would require kind system, kind inference, higher-order unification
   - **Would break complete type inference** - conflicts with core design goal
   - Significant complexity increase
   - **Not recommended** - goes against Funk's philosophy

7. **Metaprogramming/Derivation (maybe in future)**
    ```funk
    type Point = {x: Int, y: Int} deriving (Show, Eq, Ord)
    ```
    **Note:** Macros are intentionally avoided for now to keep language simple
    **Maybe later:** If done carefully, could reduce boilerplate without compromising clarity

### Overall Assessment

**Is Funk's ability system better or worse?**

**It's different, with clear trade-offs:**

**Compared to Haskell:**
- ➖ Less expressive (no HKT, no associated types)
- ➕ Simpler to learn and understand
- ➕ Explicit method calls (clarity over brevity)
- ➕ No default methods (explicitness over magic)
- ➕ Universal Inspect (pragmatic)
- ➕ Stricter coherence (less confusion)

**Compared to Rust:**
- ➖ Not suitable for systems programming (different goals)
- ➕ Complete type inference (huge win for rapid development)
- ➕ Simpler coherence rules (easier to reason about)
- ➕ No lifetime complexity (simpler mental model)
- ➕ No performance complexity (focus on clarity)
- ≈ Multi-parameter abilities replace associated types

**Compared to Swift:**
- ➖ No protocol extensions/defaults
- ➕ Complete type inference
- ➕ Better constraint system
- ➕ Stricter coherence
- ➕ Simpler generics

**Overall:** Funk's ability system is **deliberately simpler** than Haskell/Rust/Swift. It makes **principled trade-offs**:
- **Sacrifices:** Some expressiveness, some convenience features
- **Gains:** Complete type inference, simpler mental model, explicit semantics
- **Philosophy:** Clarity and simplicity over maximum expressiveness
- **Target:** Teaching, research, high-level scripting (not systems programming)

**Recommended next steps:**
1. Constraint synonyms (quality of life improvement)
2. Module-qualified abilities (ecosystem support)
3. Better error messages with hints (developer experience)
4. Controlled orphan instances (library interop, if coherence maintained)

## Ability Implementation Import: Funk vs Rust

One critical difference between Funk and Rust is **how ability/trait implementations are imported**.

### Funk's Approach: Module-Scoped Auto-Import

**Implementations automatically come with module imports:**

```funk
// Module A
pub type Person = { name: String }
pub ability Show 'a where { show : {val: 'a} -> String }
pub impl Show Person where { show = {val} -> val.name }

// Module B
use module_a::{Person, Show}
// The impl Show Person is AUTOMATICALLY imported with the module!

let p = {name = "Alice"}
Show.show {val = p}  // Works!
```

**How it works:**
1. Import the type: `use module::{Person}` (explicit)
2. Import the ability: `use module::{Show}` (explicit)
3. Implementations: **Automatic** - they come with the module

**Storage:** Implementations stored as `"Show_Person"` in module context

**Scope:** Module-scoped - implementations live in modules

### Rust's Approach: Global Implementation Discovery

**Implementations are globally available once compiled:**

```rust
// Crate A
pub struct Person { name: String }
pub trait Show { fn show(&self) -> String; }
impl Show for Person { fn show(&self) -> String { self.name.clone() } }

// Crate B
use crate_a::Person;
use crate_a::Show;  // Trait must be in scope

let p = Person { name: "Alice".to_string() };
p.show();  // The impl is GLOBALLY available!
```

**How it works:**
1. Import the type: `use crate::Person` (explicit)
2. Import the trait: `use crate::Show` (explicit, required for method calls)
3. Implementations: **Global** - automatically found by compiler

**Scope:** Globally scoped - implementations are program-wide

**Coherence:** Strict orphan rule prevents conflicts

### Detailed Comparison

| Aspect | Funk | Rust |
|--------|------|------|
| **Implementation scope** | Module-scoped | **Globally scoped** |
| **Import behavior** | Auto-import with module | **Global discovery** |
| **Trait/ability import** | Explicit: `use mod::{Show}` | Explicit: `use crate::Show` |
| **Implementation import** | Automatic with module | N/A (global) |
| **Orphan instances** | **Forbidden** (strict) | Controlled (orphan rule) |
| **Coherence check** | Module-level | **Program-wide** |
| **Conflict resolution** | Module import conflicts | Global uniqueness |
| **Where impls live** | In module context | In compiled binary |

### Example: The Orphan Instance Problem

**Rust allows (with restrictions):**
```rust
// Your crate
use external_crate::ThirdPartyType;
use std::fmt::Display;

// You CAN'T do this (neither type nor trait is local):
impl Display for ThirdPartyType { ... }  // ERROR

// But you CAN do this (type is local):
struct MyWrapper(ThirdPartyType);
impl Display for MyWrapper { ... }  // OK - newtype pattern
```

**Funk forbids orphan instances entirely:**
```funk
// Your module
use external_module::{ThirdPartyType}
use std::{Show}

// You CAN'T do this (forbidden):
impl Show ThirdPartyType where { ... }  // ERROR: orphan instance

// You MUST do this:
type MyWrapper = {value: ThirdPartyType}
impl Show MyWrapper where { ... }  // OK - newtype pattern
```

### Which Approach is Better?

**Rust's global approach:**
- ✅ More flexible for library composition
- ✅ Implementations "just work" across crates
- ✅ Better for large ecosystems
- ❌ Complex coherence rules
- ❌ Orphan rule can be confusing
- ❌ Global conflicts possible

**Funk's module-scoped approach:**
- ✅ Simpler mental model
- ✅ Clearer where implementations come from
- ✅ No global coherence complexity
- ✅ Easier to teach
- ❌ Less flexible for libraries
- ❌ No orphan instances at all
- ❌ More restrictive

**Recommendation for Funk:** Keep the current module-scoped design. It aligns with Funk's philosophy of simplicity and explicitness over maximum flexibility.

## Funk vs Rust: The Real Comparison

Since Rust is often seen as the "gold standard" for trait systems, here's a direct comparison:

### Where Funk WINS Over Rust

1. **🏆 Complete Type Inference**
   - **Funk:** Write `let f = {x, y} -> x + y` - everything inferred
   - **Rust:** Write `fn f<T: Add<Output=T>>(x: T, y: T) -> T { x + y }` - annotations everywhere
   - **Impact:** Funk is 10x faster for prototyping and exploratory coding

2. **🏆 No Lifetime Hell**
   - **Funk:** No lifetimes, ever. Just focus on your algorithm.
   - **Rust:** Lifetime parameters in traits can get extremely complex
   - **Impact:** Funk's learning curve is WAY gentler

3. **🏆 Simpler Coherence**
   - **Funk:** One impl per type. Crystal clear.
   - **Rust:** Orphan rules, blanket impls, specialization complexity
   - **Impact:** You spend less time fighting the compiler

4. **🏆 Universal Inspect**
   - **Funk:** Every type can be inspected/debugged automatically
   - **Rust:** Must `#[derive(Debug)]` or implement manually
   - **Impact:** Better debugging out of the box

### Where Rust WINS Over Funk

1. **🏆 Production Ready**
   - **Rust:** Zero-cost abstractions, systems programming, production deployments
   - **Funk:** Truffle interpreter, GC overhead, research/teaching tool
   - **Impact:** You can't ship Funk code to production (yet)

2. **🏆 Rich Ecosystem**
   - **Rust:** `derive` macros, proc macros, extensive trait library
   - **Funk:** Intentionally minimal, no macros (for now)
   - **Impact:** Rust reduces boilerplate significantly

3. **🏆 Method Call Syntax**
   - **Rust:** `value.method()` - ergonomic and familiar
   - **Funk:** `Ability.method {val = value}` - explicit but verbose
   - **Impact:** Rust code is less typing

### The Key Insight

**Rust and Funk are solving DIFFERENT problems:**

| Dimension | Rust | Funk |
|-----------|------|------|
| **Goal** | Production systems programming | Teaching & research |
| **Performance** | Zero-cost abstractions | Clarity over speed |
| **Type system** | Powerful, complex | Simple, complete inference |
| **Learning curve** | Steep (ownership + traits + lifetimes) | Gentle (just types + abilities) |
| **Philosophy** | Maximum expressiveness, optimal performance | Maximum clarity, complete inference |

### The Honest Truth

**For real software:** Use Rust. It's objectively better for production.

**For learning type systems:** Use Funk. It's objectively better for understanding.

**For research:** Both are valuable - they explore different design points.

### What Makes Funk Interesting Despite Being "Less Powerful"

1. **Proves full inference is achievable** - Many think you need annotations for type classes
2. **Shows explicitness has value** - `Show.show` is clearer than automatic dispatch for learners
3. **Demonstrates principled trade-offs** - Multi-param abilities instead of associated types
4. **Values simplicity** - No defaults, no macros, no magic = easier to understand
5. **Targets different audience** - Students, researchers, rapid prototypers

### Should You Add Rust Features to Funk?

**NO - Don't try to beat Rust at its own game:**
- ❌ Default implementations would add hidden complexity
- ❌ Derive macros would add metaprogramming complexity
- ❌ Associated types might break complete inference
- ❌ Performance optimizations miss the point

**YES - Add features that align with Funk's philosophy:**
- ✅ Constraint synonyms (reduce verbosity without magic)
- ✅ Better error messages (improve teaching experience)
- ✅ Module-qualified abilities (support library ecosystem)

**MAYBE - Research carefully:**
- ⚠️ Controlled orphan instances (if coherence maintained)
- ⚠️ Simple derivation (if no macros needed)

### Bottom Line

Funk isn't competing with Rust. They have **complementary goals**:

- **Rust:** "How can we make the most powerful, performant trait system?"
- **Funk:** "How can we make the simplest type class system with complete inference?"

Both questions are worth answering. Funk proves there's a viable design point that prioritizes **simplicity and inference** over **expressiveness and performance**. That's valuable for education and research, even if it's not production-ready.

## Summary

Funk's ability system provides:
- **Type-safe polymorphism** through type classes
- **Automatic constraint inference** from operator usage
- **Compile-time validation** of implementations
- **Dependency tracking** for ability hierarchies
- **Clean separation** from the effect system
- **Universal Inspect** for debugging without constraint burden
- **Complete type inference** without annotations
- **Explicit method calls** for clarity

Abilities allow writing generic, reusable code while maintaining full type safety and inference.
