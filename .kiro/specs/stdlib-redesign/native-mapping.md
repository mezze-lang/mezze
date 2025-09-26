# Native Function Mapping

This document maps existing native functions to the new ability-based system.

## Current Native Functions (to be replaced)

### Arithmetic Operations
```kotlin
// Current: TruffleNumericImplementations.kt
add_int, add_float, add_frac, add_nat
subtract_int, subtract_float, subtract_frac, subtract_nat  
multiply_int, multiply_float, multiply_frac, multiply_nat
divide_int, divide_float, divide_frac, divide_nat
```

### Comparison Operations
```kotlin
// Current: TruffleNumericImplementations.kt
equal_int, equal_float, equal_frac, equal_nat
lessThan_int, lessThan_float, lessThan_frac, lessThan_nat
greaterThan_int, greaterThan_float, greaterThan_frac, greaterThan_nat
```

### I/O Operations
```kotlin
// Current: TruffleIOImplementations.kt
print, debug, write_file, read_file
```

### String Operations
```kotlin
// Current: TruffleStringImplementations.kt
concat, intToString, floatToString, etc.
```

### Conversion Operations
```kotlin
// Current: TruffleNumericImplementations.kt + TruffleConversionImplementations.kt
intToFloat, intToFrac, intToNat
floatToInt, floatToFrac
fracToInt, fracToFloat
natToInt, natToFloat, natToFrac
```

### ArrayList Operations
```kotlin
// Current: TruffleNativeImplementations.kt
arrayLength, arrayGet, arraySet, arrayAdd, arrayRemove, arrayConcat, arrayIsEmpty
```

## New Ability-Based Mapping

### Core Abilities (Always Available)

#### Numeric Abilities
```funk
# std/core/abilities.funk
ability NumericAdd 'a { add: { left: 'a, right: 'a } -> 'a }
ability NumericSub 'a { subtract: { left: 'a, right: 'a } -> 'a }
ability NumericMul 'a { multiply: { left: 'a, right: 'a } -> 'a }
ability NumericDiv 'a { divide: { left: 'a, right: 'a } -> 'a }
ability NumericMod 'a { modulo: { left: 'a, right: 'a } -> 'a }

ability Eq 'a { equals: { left: 'a, right: 'a } -> Boolean }
ability Ord 'a with { Eq 'a } { 
    compare: { left: 'a, right: 'a } -> Ordering,
    less_than: { left: 'a, right: 'a } -> Boolean,
    greater_than: { left: 'a, right: 'a } -> Boolean
}
ability Show 'a { to_string: { value: 'a } -> String }
```

#### Primitive Implementations
```funk
# std/core/primitives.funk

# Int implementations
impl NumericAdd Int { add = { left, right } -> add_int { left = left, right = right } }
impl NumericSub Int { subtract = { left, right } -> subtract_int { left = left, right = right } }
impl NumericMul Int { multiply = { left, right } -> multiply_int { left = left, right = right } }
impl NumericDiv Int { divide = { left, right } -> divide_int { left = left, right = right } }
impl NumericMod Int { modulo = { left, right } -> modulo_int { left = left, right = right } }

impl Eq Int { equals = { left, right } -> equal_int { left = left, right = right } }
impl Ord Int { 
    compare = { left, right } -> compare_int { left = left, right = right },
    less_than = { left, right } -> lessThan_int { left = left, right = right },
    greater_than = { left, right } -> greaterThan_int { left = left, right = right }
}
impl Show Int { to_string = { value } -> intToString { val = value } }

# Similar for Float, Frac, Nat, String, Boolean
```

#### Infix Operators
```funk
# std/core/operators.funk
pub let (+) = { left, right } where { NumericAdd 'a } -> NumericAdd.add { left = left, right = right }
pub let (-) = { left, right } where { NumericSub 'a } -> NumericSub.subtract { left = left, right = right }
pub let (*) = { left, right } where { NumericMul 'a } -> NumericMul.multiply { left = left, right = right }
pub let (/) = { left, right } where { NumericDiv 'a } -> NumericDiv.divide { left = left, right = right }
pub let (%) = { left, right } where { NumericMod 'a } -> NumericMod.modulo { left = left, right = right }

pub let (==) = { left, right } where { Eq 'a } -> Eq.equals { left = left, right = right }
pub let (!=) = { left, right } where { Eq 'a } -> not (Eq.equals { left = left, right = right })
pub let (<) = { left, right } where { Ord 'a } -> Ord.less_than { left = left, right = right }
pub let (>) = { left, right } where { Ord 'a } -> Ord.greater_than { left = left, right = right }
pub let (<=) = { left, right } where { Ord 'a } -> not (Ord.greater_than { left = left, right = right })
pub let (>=) = { left, right } where { Ord 'a } -> not (Ord.less_than { left = left, right = right })

# Special case: Nat subtraction returns Int
pub let (-) = { left: Nat, right: Nat } -> subtract_nat { left = left, right = right }
```

### Standard Library Modules (Import Required)

#### Effects
```funk
# std/effects/console.funk
effect Console {
    print: { msg: String } -> {} <Console>,
    debug: { msg: String } -> {} <Console>
}

impl Console for ConsoleHandler {
    print = { msg } -> print { msg = msg },
    debug = { msg } -> debug { msg = msg }
}

# std/effects/filesystem.funk  
effect FileSystem {
    read_file: { path: String } -> String <FileSystem>,
    write_file: { path: String, content: String } -> {} <FileSystem>
}

impl FileSystem for FileSystemHandler {
    read_file = { path } -> read_file { path = path },
    write_file = { path, content } -> write_file { path = path, content = content }
}
```

#### Collections
```funk
# std/collections/arraylist.funk
ability ArrayListOps 'a {
    length: { arr: ArrayList 'a } -> Int,
    get: { arr: ArrayList 'a, index: Int } -> 'a,
    set: { arr: ArrayList 'a, index: Int, value: 'a } -> ArrayList 'a,
    add: { arr: ArrayList 'a, value: 'a } -> ArrayList 'a,
    remove: { arr: ArrayList 'a, index: Int } -> ArrayList 'a,
    concat: { arr1: ArrayList 'a, arr2: ArrayList 'a } -> ArrayList 'a,
    is_empty: { arr: ArrayList 'a } -> Boolean
}

impl ArrayListOps 'a {
    length = { arr } -> arrayLength { arr = arr },
    get = { arr, index } -> arrayGet { arr = arr, index = index },
    set = { arr, index, value } -> arraySet { arr = arr, index = index, value = value },
    add = { arr, value } -> arrayAdd { arr = arr, value = value },
    remove = { arr, index } -> arrayRemove { arr = arr, index = index },
    concat = { arr1, arr2 } -> arrayConcat { arr1 = arr1, arr2 = arr2 },
    is_empty = { arr } -> arrayIsEmpty { arr = arr }
}
```

#### String Operations
```funk
# std/string/core.funk
ability StringOps {
    concat: { left: String, right: String } -> String,
    length: { str: String } -> Int,
    substring: { str: String, start: Int, end: Int } -> String
}

impl StringOps {
    concat = { left, right } -> concat { left = left, right = right },
    length = { str } -> stringLength { str = str },
    substring = { str, start, end } -> stringSubstring { str = str, start = start, end = end }
}
```

#### Conversions
```funk
# std/conversion/numeric.funk
ability NumericConversion {
    int_to_float: { val: Int } -> Float,
    int_to_frac: { val: Int } -> Frac,
    int_to_nat: { val: Int } -> Nat,
    float_to_int: { val: Float } -> Int,
    float_to_frac: { val: Float } -> Frac,
    frac_to_int: { val: Frac } -> Int,
    frac_to_float: { val: Frac } -> Float,
    nat_to_int: { val: Nat } -> Int,
    nat_to_float: { val: Nat } -> Float,
    nat_to_frac: { val: Nat } -> Frac
}

impl NumericConversion {
    int_to_float = { val } -> intToFloat { val = val },
    int_to_frac = { val } -> intToFrac { val = val },
    int_to_nat = { val } -> intToNat { val = val },
    float_to_int = { val } -> floatToInt { val = val },
    float_to_frac = { val } -> floatToFrac { val = val },
    frac_to_int = { val } -> fracToInt { val = val },
    frac_to_float = { val } -> fracToFloat { val = val },
    nat_to_int = { val } -> natToInt { val = val },
    nat_to_float = { val } -> natToFloat { val = val },
    nat_to_frac = { val } -> natToFrac { val = val }
}
```

## Implementation Strategy

### Phase 1: Core Abilities
1. Keep all existing native functions in TruffleNumericImplementations, etc.
2. Create new ability definitions that wrap existing natives
3. Update TypeChecker to auto-import core abilities
4. Update parser to desugar operators to ability calls

### Phase 2: Standard Library Effects
1. Create effect definitions that wrap existing I/O natives
2. Ensure no direct function exposure - only through effects
3. Add proper error handling and security

### Phase 3: Collections & Utilities
1. Create ability-based wrappers for ArrayList operations
2. Add string manipulation abilities
3. Add conversion abilities

### Phase 4: Migration & Cleanup
1. Update all existing code to use new abilities
2. Remove old direct native function usage
3. Add comprehensive testing
4. Update documentation

## Usage Examples

### Before (Current)
```funk
let x = 5
let y = 3
let sum = add_int { left = x, right = y }    # Direct native call
print { msg = "Result: " + intToString { val = sum } }  # Direct function
```

### After (New Ability System)
```funk
let x = 5
let y = 3
let sum = x + y                              # Uses NumericAdd ability automatically

# I/O through effects
perform Console.print { msg = "Result: " + Show.to_string { value = sum } }
```

### Custom Types
```funk
type Point = { x: Int, y: Int }

impl NumericAdd Point {
    add = { left, right } -> Point { 
        x = left.x + right.x, 
        y = left.y + right.y 
    }
}

let p1 = Point { x = 1, y = 2 }
let p2 = Point { x = 3, y = 4 }
let p3 = p1 + p2                            # Works automatically!
```

This mapping ensures we maintain all existing functionality while providing a clean, extensible ability-based interface.