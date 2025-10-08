# TypeChecker Codebase Analysis

**Date**: 2025-10-11
**Scope**: Analysis of the Funk typechecker implementation for code quality, architecture, and potential improvements

---

## Overview

### Codebase Statistics

```
TypeChecker.kt:        7,557 lines (main implementation)
ConstraintSolver.kt:     959 lines (constraint solving)
ModuleResolver.kt:       537 lines (module system)
NativeFunctionTypes.kt:  410 lines (native type registry)
ErrorPrettyPrinter.kt:   206 lines (error formatting)
SpreadSyntaxError.kt:     59 lines (spread error handling)
ConstraintErrors.kt:      48 lines (constraint errors)

Total: ~9,776 lines
```

### Complexity Metrics
- **Functions**: 132 total (18 public, 114 private)
- **When expressions**: 139 (heavy pattern matching)
- **TODOs**: 14 items

---

## Architectural Assessment

### ✅ Strengths

#### 1. Clear Separation of Concerns
- **TypeChecker.kt**: Core type inference and checking
- **ConstraintSolver.kt**: Constraint-based type inference
- **ModuleResolver.kt**: Module loading and caching
- **NativeFunctionTypes.kt**: Native function type registry
- **ErrorPrettyPrinter.kt**: User-facing error messages

**Rating**: 9/10 - Well-organized module structure

#### 2. Comprehensive Pattern Matching
- Extensive use of sealed classes and `when` expressions
- Type-safe AST traversal
- Handles all expression types systematically

**Rating**: 9/10 - Idiomatic Kotlin

#### 3. Effect System Implementation
- Algebraic effects with effect rows
- Effect collection through AST traversal
- Handler validation and checking

**Rating**: 8/10 - Solid foundation, some edge cases

#### 4. Ability System (Typeclasses)
- Support for NumAdd, Eq, Ord, etc.
- Constraint-based resolution
- Impl declaration handling

**Rating**: 8/10 - Good design, needs more polish

### ⚠️ Areas of Concern

#### 1. **CRITICAL**: Module System Type Preservation
**Severity**: HIGH | **Impact**: Effects and imports broken

**Issue**: Modules lose type information on import
- `extractExports` gets null types from parsed modules
- Context propagation unclear across module boundaries
- Circular dependency avoidance prevents early type-checking

**Location**:
- [ModuleResolver.kt:223](typechecker/src/main/kotlin/com/funk/language/typechecker/ModuleResolver.kt#L223): "Don't type-check during module resolution"
- [ModuleResolver.kt:356-394](typechecker/src/main/kotlin/com/funk/language/typechecker/ModuleResolver.kt#L356-L394): `extractExports` fallback logic

**Impact**:
- All module imports return `'a` type
- Effects are lost on import
- Blocks proper use of stdlib

**Fix Priority**: P0 - Critical blocker

**Recommendation**: See TYPE_SYSTEM_INVESTIGATION.md for detailed fix plan

---

#### 2. TypeChecker.kt Size (7,557 lines)
**Severity**: MEDIUM | **Impact**: Maintainability

**Issue**: Single file contains too much logic
- Type inference for all expression types
- Module loading and import processing
- Constraint solving coordination
- Effect collection and validation
- Pattern match exhaustiveness
- Ability constraint resolution

**Suggested Refactoring**:

```
typechecker/
  ├── TypeChecker.kt           (500 lines - coordinator)
  ├── inference/
  │   ├── ExpressionInference.kt    (function calls, binops, etc.)
  │   ├── LambdaInference.kt        (lambda and function types)
  │   ├── PatternInference.kt       (match expressions)
  │   └── RecordInference.kt        (records and variants)
  ├── effects/
  │   ├── EffectCollector.kt        (collect effects from AST)
  │   ├── EffectValidator.kt        (validate handlers)
  │   └── PerformValidator.kt       (perform expression checking)
  ├── abilities/
  │   ├── AbilityResolver.kt        (resolve ability constraints)
  │   └── ImplValidator.kt          (validate impl declarations)
  ├── modules/
  │   ├── ModuleResolver.kt         (existing)
  │   ├── ImportProcessor.kt        (extract from TypeChecker)
  │   └── ExportExtractor.kt        (fix export logic)
  └── ConstraintSolver.kt      (existing)
```

**Benefits**:
- Easier to navigate and understand
- Clearer responsibilities
- Better testability (can test components independently)
- Easier to parallelize work

**Effort**: 2-3 weeks | **Risk**: Medium (requires careful extraction)

---

#### 3. Context Management Complexity
**Severity**: MEDIUM | **Impact**: Type inference bugs

**Issue**: Multiple mutable maps tracking state
```kotlin
private val context = mutableMapOf<String, FunkType>()
private val polymorphicContext = mutableMapOf<String, FunkScheme>()
private val typeAliases = mutableMapOf<String, TypeAliasInfo>()
private val constraints = mutableListOf<Pair<FunkType, FunkType>>()
private var currentLambdaContext: LambdaExpression? = null
private var currentDoContext: DoExpression? = null
private var currentProgram: Program? = null
```

**Problems**:
- State cleared at various points (`context.clear()` at line 233)
- Hard to reason about what context is active when
- Context copying/restoration logic scattered
- Module type-checking has different context than parent

**Example Issues**:
- After `typeCheckSimple()` returns, context may not contain expected types
- Cross-module type lookups fail because context doesn't persist
- Hard to debug "why doesn't this variable have a type?"

**Recommendation**: Consider immutable context with explicit scoping
```kotlin
data class TypeContext(
    val bindings: Map<String, FunkType>,
    val polymorphic: Map<String, FunkScheme>,
    val typeAliases: Map<String, TypeAliasInfo>,
    val parent: TypeContext? = null
) {
    fun withBinding(name: String, type: FunkType): TypeContext =
        copy(bindings = bindings + (name to type))

    fun lookup(name: String): FunkType? =
        bindings[name] ?: parent?.lookup(name)
}
```

**Effort**: 1-2 weeks | **Risk**: High (touches all inference code)

---

#### 4. Disabled Constraint Solving
**Severity**: MEDIUM | **Impact**: Type inference quality

**Location**: [TypeChecker.kt:595](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L595)
```kotlin
// TODO: Fix constraint solving - temporarily disabled
```

**Issue**: Constraint solving is explicitly disabled with a TODO
- May cause suboptimal type inference
- Might miss type errors that constraints would catch
- Suggests constraint solver had issues

**Recommendation**:
1. Investigate why it was disabled
2. Check git history for the disabling commit
3. Either fix the issues or document why it's unnecessary
4. Remove the TODO if it's intentional

---

#### 5. Pattern Match Exhaustiveness TODOs
**Severity**: LOW | **Impact**: Type safety

**Locations**:
- Line 3097: "TODO: Make this more precise by looking at the specific function being called"
- Line 3154: "TODO: Improve validation logic for perform expressions"

**Issue**: Some validation is incomplete or imprecise

**Recommendation**: Low priority but worth addressing for completeness

---

#### 6. Effect Impl Collection Missing
**Severity**: MEDIUM | **Impact**: Effect system completeness

**Location**: [TypeChecker.kt:5403](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L5403)
```kotlin
// TODO: Add effects from impl declarations that handle this effect
// This is a more complex fix that requires understanding the ImplDeclaration structure
```

**Issue**: When collecting effects, impl declarations aren't checked
- Might miss effects from polymorphic handlers
- Could lead to incomplete effect tracking

**Example**:
```funk
impl Log for MyType where {
    info: { msg: String } -> {} = { msg } ->
        OtherEffect.log { msg = msg }  // This OtherEffect might not be tracked
}
```

**Recommendation**: Implement proper impl declaration effect collection

---

#### 7. Module Metadata TODOs
**Severity**: LOW | **Impact**: Future features

**Locations**: Lines 7233, 7246, 7263, 7279

**Issue**: Several TODOs about storing module metadata
- Current module name determination
- Storing public use declarations
- Storing ability implementations
- Cross-module discoverability

**Recommendation**: Design module metadata system for future extensibility

---

## Specific Code Smells

### 1. Long Functions

Several functions exceed 200 lines:
- `inferBasicType()` - Handles all expression types (likely 500+ lines)
- `typeCheckSimple()` - Main type checking entry point (200+ lines)
- Lambda inference code (300+ lines spread across multiple cases)

**Recommendation**: Extract to smaller, focused functions

### 2. Deeply Nested When Expressions

Pattern matching on AST types leads to deep nesting:
```kotlin
when (expr) {
    is LambdaExpression -> {
        when (val params = expr.parameters) {
            is UntypedParameters -> {
                // ... 50 lines
            }
            is TypedParameters -> {
                // ... 30 lines
            }
        }
        // ... more nesting
    }
    // ... many more cases
}
```

**Recommendation**: Extract cases to separate functions

### 3. Mutable State in Recursion

The type inference uses mutable context while recursing:
```kotlin
private fun inferBasicType(expr: Expression): FunkType {
    // Modifies context
    context[varName] = type
    // Recurses
    val resultType = inferBasicType(subExpr)
    // Context state may have changed
}
```

**Recommendation**: Consider functional style with explicit context passing

### 4. Magic Strings

Several places use string literals for special handling:
```kotlin
val key = "${item.abilityName}_${targetTypeNames.joinToString("_")}"
context[key] = implType
```

**Recommendation**: Use sealed classes or enums for keys

### 5. Exception-Based Control Flow

Several places catch exceptions for control flow:
```kotlin
try {
    val module = resolver.resolveModule(moduleName)
    // ...
} catch (e: Exception) {
    // Continue if a module can't be loaded
}
```

**Recommendation**: Use Result types or nullable returns

---

## Performance Considerations

### 1. Module Re-Parsing
**Issue**: Modules might be parsed multiple times if cache isn't hit
**Impact**: Slow compilation for large projects
**Recommendation**: Ensure cache works correctly across all scenarios

### 2. Type Variable Fresh Generation
**Issue**: Many calls to `TypeVar.fresh()` create new variables
**Impact**: Large constraint sets, slower solving
**Recommendation**: Reuse type variables where possible

### 3. Constraint List Growth
**Issue**: Constraints accumulate in a mutable list
**Impact**: O(n²) growth for large programs
**Recommendation**: Consider more efficient constraint representation

### 4. Repeated AST Traversals
**Issue**: Multiple passes over AST (collect effects, set types, validate, etc.)
**Impact**: Could be slow for large files
**Recommendation**: Consider single-pass or two-pass approach

---

## Testing Gaps

### Areas Needing Tests

1. **Module Import Type Preservation**
   - Test that imported functions have correct types
   - Test that imported functions have correct effects
   - Test circular dependencies
   - Test nested imports

2. **Context Propagation**
   - Test that context is preserved correctly across scopes
   - Test that polymorphic context works
   - Test that module contexts don't interfere

3. **Effect Collection Edge Cases**
   - Test effects in nested lambdas
   - Test effects in match branches
   - Test effects from impl declarations
   - Test effect polymorphism

4. **Constraint Solving**
   - Test why it's disabled
   - Test that it works when enabled
   - Test edge cases that might have caused issues

5. **Error Messages**
   - Test that all error paths produce helpful messages
   - Test error message quality
   - Test suggestions and hints

---

## Recommended Improvements Priority

### P0 - Critical (Fix Now)
1. **Module Type Preservation** - See TYPE_SYSTEM_INVESTIGATION.md
   - Blocks effect system
   - Blocks stdlib usage
   - Blocks real-world use

### P1 - High (Next Quarter)
2. **Context Management Refactoring**
   - Make context management explicit and traceable
   - Fix root cause of type loss issues
   - Improve debugging experience

3. **TypeChecker.kt Splitting**
   - Improve maintainability
   - Enable parallel development
   - Make testing easier

### P2 - Medium (Within 6 Months)
4. **Fix Disabled Constraint Solving**
   - Understand why it was disabled
   - Fix or remove properly

5. **Effect Impl Collection**
   - Complete effect tracking system
   - Handle polymorphic effect handlers

6. **Comprehensive Test Suite**
   - Cover all critical paths
   - Add regression tests for bugs
   - Property-based testing

### P3 - Low (Nice to Have)
7. **Performance Optimization**
   - Profile and optimize hot paths
   - Reduce allocations
   - Cache aggressively

8. **Module Metadata System**
   - Design for extensibility
   - Enable cross-module analysis
   - Support IDE features

9. **Code Quality Cleanup**
   - Reduce function length
   - Extract nested logic
   - Remove magic strings
   - Better error handling

---

## Architecture Recommendations

### 1. Introduce a Type Context Type
Instead of multiple mutable maps, use an immutable context structure:
```kotlin
data class TypeContext(
    val bindings: Map<String, FunkType>,
    val schemes: Map<String, FunkScheme>,
    val typeAliases: Map<String, TypeAliasInfo>,
    val parent: TypeContext? = null
) {
    fun lookup(name: String): FunkType? =
        bindings[name] ?: parent?.lookup(name)

    fun withBinding(name: String, type: FunkType): TypeContext =
        copy(bindings = bindings + (name to type))

    fun enterScope(): TypeContext =
        TypeContext(emptyMap(), emptyMap(), emptyMap(), parent = this)
}
```

### 2. Make Type Inference Functions Pure
```kotlin
// Before (mutates context)
private fun inferBasicType(expr: Expression): FunkType

// After (explicit context)
private fun inferBasicType(
    expr: Expression,
    context: TypeContext
): Pair<FunkType, TypeContext>
```

### 3. Separate Module Type-Checking from Parsing
```kotlin
class ModuleLoader {
    fun parse(path: String): UntypedModule
}

class ModuleTypeChecker {
    fun typeCheck(module: UntypedModule, context: TypeContext): TypedModule
}

class ModuleCache {
    private val cache = mutableMapOf<String, TypedModule>()

    fun getOrLoad(
        path: String,
        loader: ModuleLoader,
        checker: ModuleTypeChecker,
        context: TypeContext
    ): TypedModule
}
```

### 4. Introduce Result Types for Error Handling
```kotlin
sealed class TypeCheckResult<T> {
    data class Success<T>(val value: T): TypeCheckResult<T>()
    data class Error<T>(val errors: List<TypeError>): TypeCheckResult<T>()
}

fun typeCheck(program: Program): TypeCheckResult<Program>
```

### 5. Effect System Architecture
```kotlin
// Separate effect concerns
interface EffectCollector {
    fun collect(expr: Expression): Set<String>
}

interface EffectValidator {
    fun validate(function: FunkType, declaredEffects: EffectRow?): List<TypeError>
}

interface EffectHandler {
    fun findHandlers(effect: String, context: TypeContext): List<ImplDeclaration>
}
```

---

## Documentation Recommendations

### 1. Add Architecture Documentation
Create `docs/architecture/` with:
- `type-inference.md` - How type inference works
- `effect-system.md` - Effect tracking and validation
- `module-system.md` - Module resolution and caching
- `constraint-solving.md` - Constraint-based inference

### 2. Add Inline Documentation
For complex functions, add:
```kotlin
/**
 * Infer the type of a lambda expression.
 *
 * Algorithm:
 * 1. Create fresh type variables for untyped parameters
 * 2. Add parameters to context
 * 3. Infer body type
 * 4. Collect effects from body
 * 5. Create function type with effects
 *
 * Special cases:
 * - Recursive functions: add placeholder to context first
 * - Explicit annotations: validate against inferred type
 * - Polymorphic functions: generalize free type variables
 */
private fun inferLambdaType(expr: LambdaExpression, context: TypeContext): FunkType
```

### 3. Add Examples
For each major feature, add examples:
```kotlin
/**
 * Example:
 * ```funk
 * let map = {f, list} -> match list is
 *     [] -> []
 *     [x, ...xs] -> [f {value = x}, ...map {f = f, list = xs}]
 * ```
 * Infers: { f: 'a -> 'b, list: ['a] } -> ['b] with Eq 'a
 */
```

---

## Conclusion

### Overall Assessment

**Code Quality**: 7/10
- Well-structured overall
- Some complexity issues
- Needs refactoring for maintainability

**Correctness**: 8/10
- Core type inference works well
- Effect system foundation is solid
- Module type preservation is broken (critical)

**Performance**: 7/10
- Acceptable for current scale
- May need optimization for larger projects
- Caching strategy needs improvement

**Maintainability**: 6/10
- Large files make changes risky
- Mutable state complicates reasoning
- Good separation at module level

### Key Takeaways

1. **Module type preservation is the highest priority fix** - blocks real usage
2. **Context management needs architectural attention** - root cause of issues
3. **Size reduction through refactoring** - improve long-term maintainability
4. **Testing gaps need filling** - prevent regressions

### Next Steps

1. **Immediate**: Fix module type preservation (see TYPE_SYSTEM_INVESTIGATION.md)
2. **Short-term**: Add tests for current functionality
3. **Medium-term**: Refactor TypeChecker.kt into smaller modules
4. **Long-term**: Redesign context management for clarity

---

## Metrics Summary

```
Lines of Code:        ~9,776
Functions:            132 (18 public, 114 private)
TODOs:                14
Critical Issues:      1 (module type preservation)
High Priority Items:  2 (context management, code splitting)
Medium Priority:      4
Low Priority:         3

Estimated Tech Debt: ~6-8 weeks of focused work
```

---

*This analysis was performed on 2025-10-11 as part of a comprehensive type system investigation.*
