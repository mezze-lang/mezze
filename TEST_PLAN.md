# Comprehensive Test Plan for Funk Type System

## A. Multiple Ability Constraints Tests

### TypeChecker Tests:

#### 1. **MultipleAbilityConstraintsTest.kt**
```kotlin
// Test: function with multiple constraints on same type variable
let compare_and_add = {x, y} -> if x > y then x + y else x - y
// Expected: {x: 'a, y: 'a} with Ord 'a, NumAdd 'a, NumSub 'a -> 'a

// Test: three or more abilities on same type variable
let complex_numeric = {a, b} -> (a + b) * (a / b) - (a % b)
// Expected: {a: 'a, b: 'a} with NumAdd 'a, NumMul 'a, NumDiv 'a, NumMod 'a -> 'a

// Test: multiple type variables with different ability sets
let mixed_abilities = {x, y, z} -> "$(x + y)$(z > y)"
// Expected: should fail - incompatible constraint combinations

// Test: ability constraint propagation through nested calls
let outer = {x, y} -> {
  let inner = x + y
  let compare = inner > x
  compare
}
// Expected: {x: 'a, y: 'a} with NumAdd 'a, Ord 'a -> Bool

// Test: partial application preserves multiple constraints
let multi_op = {x, y, z} -> (x + y) > z
// Expected: {x: 'a, y: 'a, z: 'a} with NumAdd 'a, Ord 'a -> Bool
let partially_applied = multi_op {x = 5}
// Expected: {y: Int, z: Int} -> Bool (constraints resolved to Int)

// Test: ability constraint unification
let use_both = {f, x} -> {
  let sum = x + x
  let cmp = x > sum
  f {val = x}
}
// Expected: {f: {val: 'a} -> 'b, x: 'a} with NumAdd 'a, Ord 'a -> 'b

// Test: contravariant ability constraints (should fail)
let impossible = {x} -> {
  let a = x + 1        // requires NumAdd
  let b = "$(x)test"  // uses string interpolation
  a
}
// Expected: Type error - Int doesn't have StringConcat

// Test: ability constraints with records containing functions
let with_func_field = {rec} -> rec.f {x = 5} + rec.y
// Expected: {rec: {f: {x: Int} -> 'a, y: 'a, ...}} with NumAdd 'a -> 'a
```

#### 2. **FixtureBasedTests - fixtures/valid/multiple_abilities.funk**
```funk
# Multiple abilities on same type variable
let sort_and_sum = {items, threshold} -> {
  let filtered = items > threshold    # Ord constraint
  filtered + threshold                # NumAdd constraint
}
# expected: sort_and_sum : {items: 'a, threshold: 'a} with Ord 'a, NumAdd 'a -> 'a

# Ability constraint from match expression
let match_with_ops = {x} -> match x is
  val when val > 10 -> val + 5
  val when val < 5 -> val - 2
  val -> val * 2
# expected: match_with_ops : {x: 'a} with Ord 'a, NumAdd 'a, NumSub 'a, NumMul 'a -> 'a

# Multiple abilities through composition
let compose_ops = {a, b, c} -> {
  let step1 = a + b       # NumAdd
  let step2 = step1 * c   # NumMul
  let step3 = step2 > a   # Ord
  if step3 then step2 else a
}
# expected: compose_ops : {a: 'a, b: 'a, c: 'a} with NumAdd 'a, NumMul 'a, Ord 'a -> 'a

result
```

### Interpreter Tests:

#### 3. **MultipleAbilityConstraintsInterpreterTest.kt**
```kotlin
// Test: execute function with multiple numeric abilities
"let f = {x, y} -> (x + y) * (x - y)\nf {x = 10, y = 3}"
// Expected: FunkInt(91)  // (10+3) * (10-3) = 13 * 7 = 91

// Test: comparison + arithmetic at runtime
"let f = {x, y} -> if x > y then x + y else x * y\nf {x = 5, y = 3}"
// Expected: FunkInt(8)  // 5 > 3 is true, so 5 + 3

// Test: multiple abilities with different types (Int, Float)
"let f = {x, y} -> (x + y) > (x * y)\nf {x = 2, y = 3}"
// Expected: FunkBool(false)  // 5 > 6 is false
```

---

## B. Partial Application Edge Cases Tests

### TypeChecker Tests:

#### 4. **PartialApplicationEdgeCasesTest.kt**
```kotlin
// Test: partial application with all parameters applied
let add = {x, y} -> x + y
let fully_applied = add {x = 5, y = 10}
// Expected: Int (not a function)

// Test: partial application preserving open record type
let process_open = {x, y, ...rest} -> x + y
let partially_open = process_open {x = 5}
// Expected: {y: Int, ...} -> Int

// Test: multiple levels of partial application
let add4 = {a, b, c, d} -> a + b + c + d
let p1 = add4 {a = 1}
let p2 = p1 {b = 2}
let p3 = p2 {c = 3}
let result = p3 {d = 4}
// Expected: all intermediates have correct types

// Test: partial application with type variable specialization
let generic = {x, y} -> x
let specialized = generic {x = 5}
// Expected: {y: 'a} -> Int (x specialized to Int, y remains polymorphic)

// Test: partial application in different order
let f = {a, b, c} -> a + b + c
let p1 = f {c = 3}
let p2 = p1 {a = 1}
let result = p2 {b = 2}
// Expected: Int

// Test: partial application with abilities
let poly_add = {x, y} -> x + y
let with_int = poly_add {x = 5}
// Expected: {y: Int} -> Int
let result1 = with_int {y = 3}
let result2 = with_int {y = 7}
// Both should work

// Test: partial application doesn't lose ability constraints
let compare = {x, y, z} -> x > y && y > z
let p = compare {x = 10}
// Expected: {y: Int, z: Int} -> Bool (Ord constraint resolved to Int)

// Test: record spread in partial application
let f = {x, y, z} -> x + y + z
let partial = f {x = 1, y = 2}
// Expected: {z: Int} -> Int
```

#### 5. **Fixtures - fixtures/valid/partial_application_edge_cases.funk**
```funk
# Zero-parameter partial application (identity)
let f = {x, y} -> x + y
let same = f {}
# expected: same : {x: 'a, y: 'a} with NumAdd 'a -> 'a

# Nested partial application with different types
let combine = {a, b, c, d} -> "$(a)$(b + c)$(d)"
let with_str = combine {a = "Result: ", d = " done"}
# expected: with_str : {b: Int, c: Int} -> String
let result = with_str {b = 5, c = 10}
# expected: result : String

# Partial application overriding (should maintain first binding)
let f = {x, y} -> x + y
let p1 = f {x = 5}
let p2 = p1 {y = 10}
# expected: p2 : Int

# Recursive partial application
let fact_helper = {n, acc} -> if n <= 1 then acc else fact_helper {n = n - 1, acc = n * acc}
let fact = {n} -> fact_helper {n = n, acc = 1}
# expected: fact : {n: Int} -> Int

result
```

### Interpreter Tests:

#### 6. **PartialApplicationInterpreterTest.kt**
```kotlin
// Test: execute multi-level partial application
"""
let add4 = {a, b, c, d} -> a + b + c + d
let p1 = add4 {a = 1}
let p2 = p1 {b = 2}
let p3 = p2 {c = 3}
p3 {d = 4}
"""
// Expected: FunkInt(10)

// Test: partial application with mixed types
"""
let make_message = {num, str, flag} -> if flag then "$(str)$(num)" else "$(num)$(str)"
let with_num = make_message {num = "42"}
let with_str = with_num {str = " is the answer"}
with_str {flag = true}
"""
// Expected: FunkString(" is the answer42")

// Test: partial application in recursion
"""
let countdown = {n, acc} -> if n <= 0 then acc else countdown {n = n - 1, acc = n + acc}
let from_10 = countdown {n = 10}
from_10 {acc = 0}
"""
// Expected: FunkInt(55)  // sum from 1 to 10
```

---

## C. Pipe Operator Type Inference Edge Cases

### TypeChecker Tests:

#### 7. **PipeOperatorAdvancedTest.kt** (extend existing)
```kotlin
// Test: pipe with ability constraint propagation
let f = {x} -> x + x
let result = 5 |> f {x = @}
// Expected: Int with NumAdd constraint resolved

// Test: pipe chain with type transformations
let square = {x} -> x * x
let to_string = {x} -> "Result: $(x)"
let result = 5 |> square {x = @} |> to_string {x = @}
// Expected: String (string interpolation handles Int -> String conversion)

// Test: pipe with open records
let add_field = {rec, val} -> {x = rec.x + val, ...rec}
let result = {x = 5, y = 10} |> add_field {rec = @, val = 3}
// Expected: {x: Int, y: Int}

// Test: pipe with partial application creating closure
let make_adder = {x} -> {y} -> x + y
let adder = 5 |> make_adder {x = @}
let result = 3 |> adder {y = @}
// Expected: Int

// Test: multiple placeholders in same record
let combine = {x, y} -> x + y
let result = 5 |> combine {x = @, y = @}
// Expected: Int (both placeholders get value 5)

// Test: pipe with match expression
let classifier = {x} -> match x is
  v when v > 10 -> "big"
  v when v > 5 -> "medium"
  _ -> "small"
let result = 7 |> classifier {x = @}
// Expected: String

// Test: pipe with variant constructors
type Result = | Ok {val: Int}, Err {msg: String} |
let to_result = {x} -> if x > 0 then Ok {val = x} else Err {msg = "negative"}
let result = 5 |> to_result {x = @}
// Expected: Result

// Test: pipe operator precedence with arithmetic
let square = {x} -> x * x
let result = 2 + 3 |> square {x = @}
// Should parse as: (2 + 3) |> square, result = 25

// Test: nested pipe expressions
let f = {x} -> x + 1
let g = {y} -> y * 2
let h = {z} -> (z |> f {x = @}) |> g {y = @}
let result = 5 |> h {z = @}
// Expected: Int
```

#### 8. **Fixtures - fixtures/valid/pipe_operator_advanced.funk**
```funk
# Pipe with polymorphic function and constraint resolution
let double = {x} -> x + x
let int_result = 5 |> double {x = @}
# expected: int_result : Int

let float_result = 3.14 |> double {x = @}
# expected: float_result : Float

# Complex pipe chain with type inference
let increment = {x} -> x + 1
let square = {x} -> x * x
let negate = {x} -> 0 - x
let result = 5 |> increment {x = @} |> square {x = @} |> negate {x = @}
# expected: result : Int

# Pipe with open record transformation
let add_z = {rec} -> {z = 99, ...rec}
let record_result = {x = 1, y = 2} |> add_z {rec = @}
# expected: record_result : {z: Int, x: Int, y: Int}

result
```

### Interpreter Tests:

#### 9. **PipeOperatorInterpreterTest.kt**
```kotlin
// Test: complex pipe chain execution
"""
let inc = {x} -> x + 1
let square = {x} -> x * x
let half = {x} -> x / 2
5 |> inc {x = @} |> square {x = @} |> half {x = @}
"""
// Expected: FunkInt(18)  // (5+1)^2 / 2 = 36/2 = 18

// Test: pipe with record updates
"""
let add_field = {rec, val} -> {new = val, ...rec}
{x = 5} |> add_field {rec = @, val = 10}
"""
// Expected: FunkRecord with {new = 10, x = 5}

// Test: multiple placeholders runtime
"""
let add = {x, y} -> x + y
10 |> add {x = @, y = @}
"""
// Expected: FunkInt(20)
```

---

## D. Open Variants in Match Functions

### TypeChecker Tests:

#### 10. **OpenVariantMatchTest.kt**
```kotlin
// Test: open variant in function parameter
type Color = | Red, Blue, Green, ... |
let process = {color: Color} -> match color is
  Red -> "red"
  Blue -> "blue"
  _ -> "other"
// Expected: {color: Color} -> String

// Test: open variant with data constructors
type Shape = | Circle {radius: Int}, Square {side: Int}, ... |
let area = {shape: Shape} -> match shape is
  Circle {radius} -> radius * radius * 3
  Square {side} -> side * side
  _ -> 0
// Expected: {shape: Shape} -> Int

// Test: nested open variants
type Inner = | A, B, ... |
type Outer = | X {val: Inner}, Y, ... |
let process = {o: Outer} -> match o is
  X {val} -> match val is
    A -> 1
    _ -> 2
  _ -> 0
// Expected: {o: Outer} -> Int

// Test: open variant exhaustiveness checking
type Status = | Active, Inactive, ... |
let check = {s: Status} -> match s is
  Active -> true
  Inactive -> false
// Expected: Type error - non-exhaustive (no wildcard)

// Test: function returning open variant
type Option 'a = | Some {val: 'a}, None, ... |
let safe_div = {x, y} -> if y == 0 then None else Some {val = x / y}
// Expected: {x: Int, y: Int} -> Option Int
```

#### 11. **Fixtures - fixtures/valid/open_variant_functions.funk**
```funk
# Open variant as function parameter
type Animal = | Dog {name: String}, Cat {name: String}, ... |

let greet = {animal: Animal} -> match animal is
  Dog {name} -> "Woof, $(name)"
  Cat {name} -> "Meow, $(name)"
  _ -> "Hello, creature"
# expected: greet : {animal: Animal} -> String

# Open variant with type parameters
type Container 'a = | Box {val: 'a}, Bag {items: ['a]}, ... |

let get_count = {c: Container Int} -> match c is
  Box {val} -> 1
  Bag {items} -> items
  _ -> 0
# expected: get_count : {c: Container Int} -> Int

# Pattern matching preserving polymorphism
type Maybe 'a = | Just {val: 'a}, Nothing, ... |

let map_maybe = {m: Maybe 'a, f: {'a} -> 'b} -> match m is
  Just {val} -> Just {val = f {val}}
  Nothing -> Nothing
  _ -> Nothing
# expected: map_maybe : {m: Maybe 'a, f: {'a} -> 'b} -> Maybe 'b

result
```

### Interpreter Tests:

#### 12. **OpenVariantMatchInterpreterTest.kt**
```kotlin
// Test: execute open variant match with wildcard
"""
type Status = | Active, Inactive, ... |
let to_num = {s: Status} -> match s is
  Active -> 1
  Inactive -> 0
  _ -> -1
to_num Active
"""
// Expected: FunkInt(1)

// Test: open variant with data
"""
type Result = | Ok {val: Int}, Err {msg: String}, ... |
let get_value = {r: Result} -> match r is
  Ok {val} -> val
  _ -> -1
get_value (Ok {val = 42})
"""
// Expected: FunkInt(42)

// Test: nested open variant matching
"""
type Inner = | A, B, ... |
type Outer = | X {val: Inner}, Y, ... |
let process = {o: Outer} -> match o is
  X {val} -> match val is
    A -> 1
    B -> 2
    _ -> 3
  Y -> 4
  _ -> 5
process (X {val = A})
"""
// Expected: FunkInt(1)
```

---

## E. Open Records as Function Arguments

### TypeChecker Tests:

#### 13. **OpenRecordFunctionArgumentsTest.kt**
```kotlin
// Test: open record parameter
let extract_x = {rec, ...} -> rec.x
// Expected: {rec: {x: 'a, ...}, ...} -> 'a

// Test: open record with known fields
let process = {rec: {x: Int, y: Int, ...}} -> rec.x + rec.y
let result = process {x = 5, y = 10, z = 20}
// Expected: Int

// Test: open record parameter in partial application
let add_fields = {rec, ...} -> rec.a + rec.b
let partial = add_fields {rec = {a = 5, b = 10, c = 15}}
// Expected: Int

// Test: open record update preserving extra fields
let update_x = {rec: {x: Int, ...}, new_x: Int} -> {x = new_x, ...rec}
let result = update_x {rec = {x = 1, y = 2, z = 3}, new_x = 99}
// Expected: {x: Int, y: Int, z: Int, ...}

// Test: function accepting both open and closed records
let get_x_closed = {rec: {x: Int}} -> rec.x
let get_x_open = {rec: {x: Int, ...}} -> rec.x
// Both should work but with different type signatures

// Test: open record in higher-order function
let apply_to_field = {rec: {x: 'a, ...}, f: {'a} -> 'b} -> {x = f rec.x, ...rec}
// Expected: {rec: {x: 'a, ...}, f: {'a} -> 'b} -> {x: 'b, ...}

// Test: constraining open record fields
let sum_numeric = {rec: {x: 'a, y: 'a, ...}} with NumAdd 'a -> rec.x + rec.y
// Expected: {rec: {x: 'a, y: 'a, ...}} with NumAdd 'a -> 'a

// Test: open record with nested structure
let extract_nested = {rec: {inner: {value: Int, ...}, ...}} -> rec.inner.value
// Expected: {rec: {inner: {value: Int, ...}, ...}} -> Int
```

#### 14. **Fixtures - fixtures/valid/open_record_arguments.funk**
```funk
# Basic open record parameter
let get_name = {person: {name: String, ...}} -> person.name
let result1 = get_name {name = "Alice", age = 30, city = "NYC"}
# expected: result1 : String

# Open record with computation
let distance = {point: {x: Int, y: Int, ...}} -> {
  let dx = point.x * point.x
  let dy = point.y * point.y
  dx + dy
}
let result2 = distance {x = 3, y = 4, label = "P1"}
# expected: result2 : Int

# Open record update function
let increment_x = {rec: {x: Int, ...}} -> {x = rec.x + 1, ...rec}
let result3 = increment_x {x = 5, y = 10}
# expected: result3 : {x: Int, y: Int}

# Polymorphic open record function
let get_first_field = {rec: {first: 'a, ...}} -> rec.first
let result4 = get_first_field {first = 42, second = "hello"}
# expected: result4 : Int

# Multiple open record parameters
let merge_x_y = {r1: {x: Int, ...}, r2: {y: Int, ...}} -> r1.x + r2.y
let result5 = merge_x_y {r1 = {x = 10, a = 1}, r2 = {y = 20, b = 2}}
# expected: result5 : Int

result1
```

### Interpreter Tests:

#### 15. **OpenRecordArgumentsInterpreterTest.kt**
```kotlin
// Test: execute function with open record parameter
"""
let get_x = {rec: {x: Int, ...}} -> rec.x
get_x {x = 42, y = 100, z = "extra"}
"""
// Expected: FunkInt(42)

// Test: open record update execution
"""
let update = {rec: {x: Int, ...}, val: Int} -> {x = val, ...rec}
update {rec = {x = 1, y = 2, z = 3}, val = 99}
"""
// Expected: FunkRecord({x = 99, y = 2, z = 3})

// Test: open record with nested access
"""
let get_nested = {rec: {inner: {value: Int, ...}, ...}} -> rec.inner.value
get_nested {inner = {value = 42, extra = true}, outer = "field"}
"""
// Expected: FunkInt(42)

// Test: polymorphic open record
"""
let first = {rec: {a: 'a, ...}} -> rec.a
first {a = "hello", b = 123}
"""
// Expected: FunkString("hello")
```

---

## F. General Type Inference Edge Cases

### TypeChecker Tests:

#### 16. **TypeInferenceEdgeCasesTest.kt**
```kotlin
// Test: inference with circular references
let rec1 = {f} -> f {val = rec2}
let rec2 = {val} -> val.f
// Expected: mutual recursion type inference

// Test: inference through conditional branches
let conditional = {x, flag} -> if flag then x + 1 else x - 1
// Expected: {x: Int, flag: Bool} -> Int (not polymorphic, resolved to Int)

// Test: inference with record literals
let f = {x} -> {result = x + 1, original = x}
// Expected: {x: Int} -> {result: Int, original: Int}

// Test: inference preserving constraints through let bindings
let process = {x} -> {
  let doubled = x + x
  let compared = doubled > x
  compared
}
// Expected: {x: 'a} with NumAdd 'a, Ord 'a -> Bool

// Test: bidirectional type inference
let apply = {f, x} -> f x
let add_one = {y} -> y + 1
let result = apply {f = add_one, x = 5}
// Expected: Int

// Test: inference with nested lambdas
let make_adder = {x} -> {y} -> {z} -> x + y + z
// Expected: {x: 'a} with NumAdd 'a -> {y: 'a} -> {z: 'a} -> 'a

// Test: inference failure with ambiguous constraints
let ambiguous = {x, y} -> {
  let a = x + 1
  let b = "$(y)text"
  {a, b}
}
// Expected: {x: Int, y: String} -> {a: Int, b: String}
```

#### 17. **Fixtures - fixtures/inference/advanced_inference.funk**
```funk
# Inference through nested structures
let nested = {x} -> {
  let inner = {y} -> x + y
  inner
}
# expected: nested : {x: 'a} with NumAdd 'a -> {y: 'a} -> 'a

# Inference with record construction
let make_point = {x, y} -> {
  let distance = x * x + y * y
  {x, y, distance}
}
# expected: make_point : {x: 'a, y: 'a} with NumMul 'a, NumAdd 'a -> {x: 'a, y: 'a, distance: 'a}

# Inference preserving independence
let separate = {a, b, c} -> {
  let sum = a + b
  {sum, other = c}
}
# expected: separate : {a: 'a, b: 'a, c: 'b} with NumAdd 'a -> {sum: 'a, other: 'b}

result
```

---

## Summary of Test Files to Create:

### TypeChecker Tests (Kotlin):
1. `MultipleAbilityConstraintsTest.kt` - ~15 test methods
2. `PartialApplicationEdgeCasesTest.kt` - ~12 test methods
3. `PipeOperatorAdvancedTest.kt` (extend existing) - ~10 new test methods
4. `OpenVariantMatchTest.kt` - ~8 test methods
5. `OpenRecordFunctionArgumentsTest.kt` - ~10 test methods
6. `TypeInferenceEdgeCasesTest.kt` - ~12 test methods

### TypeChecker Fixtures:
7. `fixtures/valid/multiple_abilities.funk`
8. `fixtures/valid/partial_application_edge_cases.funk`
9. `fixtures/valid/pipe_operator_advanced.funk`
10. `fixtures/valid/open_variant_functions.funk`
11. `fixtures/valid/open_record_arguments.funk`
12. `fixtures/inference/advanced_inference.funk`

### Interpreter Tests (Kotlin):
13. `MultipleAbilityConstraintsInterpreterTest.kt` - ~8 test methods
14. `PartialApplicationInterpreterTest.kt` - ~6 test methods
15. `PipeOperatorInterpreterTest.kt` (extend existing) - ~6 new test methods
16. `OpenVariantMatchInterpreterTest.kt` - ~6 test methods
17. `OpenRecordArgumentsInterpreterTest.kt` - ~8 test methods

### Total:
- **~97 new test methods** across TypeChecker
- **~34 new test methods** across Interpreter
- **6 fixture files** with comprehensive examples
- **131 total new test cases**
