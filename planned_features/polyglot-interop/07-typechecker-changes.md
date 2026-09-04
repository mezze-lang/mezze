# Typechecker Changes

This document covers the typechecker modifications needed to support polyglot interop.

## New Types

### Foreign Type

The `Foreign` type is an opaque wrapper for values from other languages. It cannot be pattern matched or destructured - only converted using `foreign::to_*` functions.

```kotlin
// typechecker/src/main/kotlin/com/mezze/language/ast/MezzeType.kt

sealed class MezzeType {
    // ... existing types ...

    /**
     * Opaque type for foreign values.
     * Cannot be unified with other types except through explicit conversion.
     */
    object ForeignType : MezzeType() {
        override fun toString() = "Foreign"
    }

    /**
     * Polyglot context type.
     * Parameterized by language for type safety.
     */
    data class ContextType(val language: String) : MezzeType() {
        override fun toString() = "Context<$language>"
    }
}
```

### Type Rules for Foreign

```
┌─────────────────────────────────────────────────────────────┐
│ Foreign Type Rules                                          │
├─────────────────────────────────────────────────────────────┤
│ 1. Foreign is opaque - no structural access                 │
│    ❌ foreign.field  (use foreign::get instead)             │
│    ❌ match foreign is ...  (cannot pattern match)          │
│                                                             │
│ 2. Foreign does not unify with concrete types               │
│    ❌ let x: Int = some_foreign_value                       │
│    ✅ let x: Int = foreign::to_int { val = f }             │
│                                                             │
│ 3. Foreign can be passed to foreign:: functions             │
│    ✅ foreign::get { obj = f, member = "name" }            │
│    ✅ foreign::call { obj = f, method = "foo", args = [] } │
│                                                             │
│ 4. Any Mezze value can become Foreign via foreign::from      │
│    ✅ foreign::from { val = 42 }  → Foreign                │
│    ✅ foreign::from { val = [1,2,3] }  → Foreign           │
└─────────────────────────────────────────────────────────────┘
```

## Effect Tracking

### Polyglot Effects

Each language interop is tracked as an effect:

```kotlin
// Effect names for polyglot
val PYTHON_EFFECT = "Python"
val JAVASCRIPT_EFFECT = "JavaScript"
val JAVA_EFFECT = "Java"
val R_EFFECT = "R"
val RUBY_EFFECT = "Ruby"
```

### Effect Propagation

Functions calling polyglot operations must declare the effect:

```mezze
# Effect declared in signature
let process: { data: [Float] } -> Result NDArray InteropError <Python> = { data } ->
    Python.eval { code = "..." }

# Effect propagates through calls
let main: {} -> Result Float InteropError <Python> = {} ->
    process { data = [1.0, 2.0, 3.0] }
```

### Perform Block Handling

When typechecking `perform Handler in expr`:
1. The handler provides the effect
2. The expression is checked with that effect available
3. The result type is the expression's type minus the handled effect

```kotlin
// typechecker/src/main/kotlin/com/mezze/language/typechecker/constraints/ExpressionConstraints.kt

fun generatePerformConstraints(perform: PerformExpression, ctx: ConstraintContext): ConstraintResult {
    // 1. Get handler type and extract effect it provides
    val handlerType = inferHandlerType(perform.handler)
    val providedEffect = extractProvidedEffect(handlerType)

    // 2. Check body with effect available
    val bodyCtx = ctx.withEffect(providedEffect)
    val bodyResult = generateConstraints(perform.body, bodyCtx)

    // 3. Result type has effect removed
    val resultType = bodyResult.type.withoutEffect(providedEffect)

    return ConstraintResult(resultType, bodyResult.constraints)
}
```

## Handler Type Checking

### Handler Parameters

Handlers can take parameters (like `context: Context`):

```mezze
perform GraalPy { context = ctx } in
    Python.eval { code = "..." }
```

Typechecker must verify:
1. `ctx` has type `Context` (or `Context<Python>` if parameterized)
2. Handler `GraalPy` accepts these parameters
3. Optional parameters (`timeout`, `memory`) have correct types

```kotlin
fun checkHandlerParams(handler: HandlerExpression, params: Map<String, Expression>): List<TypeError> {
    val errors = mutableListOf<TypeError>()

    // Get expected parameters from handler impl declaration
    val handlerDecl = lookupHandler(handler.name)
    val expectedParams = handlerDecl.parameters

    // Check each provided parameter
    for ((name, expr) in params) {
        val expectedType = expectedParams[name]
        if (expectedType == null) {
            errors.add(UnknownHandlerParameter(handler.name, name))
            continue
        }

        val actualType = inferType(expr)
        if (!unifies(actualType, expectedType)) {
            errors.add(TypeMismatch(expected = expectedType, actual = actualType))
        }
    }

    // Check required parameters are provided
    for ((name, type) in expectedParams) {
        if (name !in params && !type.isOptional) {
            errors.add(MissingHandlerParameter(handler.name, name, type))
        }
    }

    return errors
}
```

## InteropError Type

The `InteropError` variant type is defined in stdlib but registered in typechecker:

```kotlin
// Register InteropError as a known variant type
val INTEROP_ERROR_TYPE = MezzeType.VariantType(
    name = "InteropError",
    cases = listOf(
        VariantCase("LanguageNotAvailable", mapOf("language" to StringType, "reason" to StringType)),
        VariantCase("RuntimeError", mapOf("language" to StringType, "message" to StringType, "stack" to StringType)),
        VariantCase("ConversionError", mapOf("from_type" to StringType, "to_type" to StringType, "message" to StringType)),
        VariantCase("MemberNotFound", mapOf("object" to ForeignType, "member" to StringType)),
        VariantCase("InvocationError", mapOf("object" to ForeignType, "method" to StringType, "message" to StringType)),
        VariantCase("Timeout", mapOf("operation" to StringType, "limit_ms" to IntType)),
        VariantCase("MemoryExceeded", mapOf("operation" to StringType, "limit_mb" to IntType)),
        VariantCase("InvalidContext", mapOf("reason" to StringType))
    )
)
```

## Native Function Types

Register types for all `__native_*` functions:

```kotlin
// typechecker/src/main/kotlin/com/mezze/language/typechecker/builtins/PolyglotBuiltins.kt

object PolyglotBuiltins {

    val builtins = mapOf(
        // Foreign operations
        "__native_foreign_from" to FunctionType(
            params = mapOf("val" to TypeVariable("a")),
            returnType = ForeignType
        ),
        "__native_foreign_to_int" to FunctionType(
            params = mapOf("val" to ForeignType),
            returnType = ResultType(IntType, INTEROP_ERROR_TYPE)
        ),
        "__native_foreign_to_float" to FunctionType(
            params = mapOf("val" to ForeignType),
            returnType = ResultType(FloatType, INTEROP_ERROR_TYPE)
        ),
        "__native_foreign_to_string" to FunctionType(
            params = mapOf("val" to ForeignType),
            returnType = ResultType(StringType, INTEROP_ERROR_TYPE)
        ),
        "__native_foreign_get" to FunctionType(
            params = mapOf("obj" to ForeignType, "member" to StringType),
            returnType = ResultType(ForeignType, INTEROP_ERROR_TYPE)
        ),
        "__native_foreign_call" to FunctionType(
            params = mapOf("obj" to ForeignType, "method" to StringType, "args" to ArrayType(ForeignType)),
            returnType = ResultType(ForeignType, INTEROP_ERROR_TYPE)
        ),
        "__native_foreign_invoke" to FunctionType(
            params = mapOf("func" to ForeignType, "args" to ArrayType(ForeignType)),
            returnType = ResultType(ForeignType, INTEROP_ERROR_TYPE)
        ),

        // Python context
        "__native_python_create_context" to FunctionType(
            params = emptyMap(),
            returnType = ContextType("Python")
        ),
        "__native_python_close_context" to FunctionType(
            params = mapOf("context" to ContextType("Python")),
            returnType = UnitType
        ),
        "__native_python_eval" to FunctionType(
            params = mapOf("context" to ContextType("Python"), "code" to StringType),
            returnType = ResultType(ForeignType, INTEROP_ERROR_TYPE),
            effects = setOf(PYTHON_EFFECT)
        ),
        // ... similar for other languages
    )
}
```

## Wrapper Library Type Inference

When using wrapper libraries like `mezze-numpy`:

```mezze
# mezze-numpy declares:
pub let array: { data: [Float] } -> Result NDArray InteropError <Python>

# User writes:
let arr = numpy::array { data = [1.0, 2.0, 3.0] }
# Inferred type: Result NDArray InteropError
# Inferred effect: <Python>
```

The typechecker:
1. Looks up `numpy::array` type from imported module
2. Unifies argument types
3. Propagates effect `<Python>` to enclosing scope

## Error Messages

### Foreign Type Errors

```
Error: Cannot access member 'name' on Foreign type
  --> src/app.mz:10:5
   |
10 |     foreign_obj.name
   |     ^^^^^^^^^^^^^^^^
   |
   = Foreign values are opaque. Use foreign::get { obj = ..., member = "name" } instead.
```

### Missing Effect

```
Error: Function uses Python effect but doesn't declare it
  --> src/app.mz:15:1
   |
15 | let process = { data } ->
   | ^^^^^^^^^^^^^^^^^^^^^^^^^
   |     Python.eval { code = "..." }
   |     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ Python effect used here
   |
   = Add <Python> to function signature:
     let process: { data: [Float] } -> Result Foreign InteropError <Python> = ...
```

### Handler Parameter Error

```
Error: Missing required handler parameter 'context'
  --> src/app.mz:20:5
   |
20 |     perform GraalPy in
   |     ^^^^^^^^^^^^^^^^^^
   |
   = GraalPy requires a context parameter:
     perform GraalPy { context = ctx } in ...
```

### Context Type Mismatch

```
Error: Context type mismatch
  --> src/app.mz:25:25
   |
25 |     perform GraalPy { context = js_ctx } in
   |                       ^^^^^^^^^^^^^^^^^
   |
   = Expected: Context<Python>
     Found: Context<JavaScript>
```

## Implementation Checklist

- [ ] Add `ForeignType` to MezzeType sealed class
- [ ] Add `ContextType` with language parameter
- [ ] Register polyglot effects (Python, JavaScript, etc.)
- [ ] Type `__native_*` functions in builtins
- [ ] Handle `perform Handler { params } in expr` with parameter checking
- [ ] Add effect propagation for polyglot operations
- [ ] Implement Foreign type checking rules (no field access, no pattern match)
- [ ] Add InteropError variant type
- [ ] Improve error messages for polyglot-specific errors
