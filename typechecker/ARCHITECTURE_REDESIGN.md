# TypeChecker Architecture Redesign Plan

## Current State: Spaghetti Code

- **18,231 lines** in one file (reduced to 14,343 with extensions)
- **217 "CRITICAL FIX" comments** - bandaid solutions
- **1,074-line function** `typeCheckSimple` - does everything
- **203 functions** - calling each other in complex ways
- **Mutable state everywhere** - bugs and complexity
- **Mixed concerns** - inference + validation + solving all tangled

## Target Architecture: Clean Separation

### Principles

1. **Immutable Data** - no shared mutable state
2. **Pure Functions** - predictable, testable
3. **Clear Phases** - separate constraint generation, solving, validation
4. **Single Responsibility** - each file does one thing
5. **No Bandaids** - proper design, not "CRITICAL FIX"

### New Structure

```
typechecker/
├── TypeChecker.kt (500 lines) - Orchestrator
├── context/
│   ├── TypingContext.kt (200 lines)
│   └── ContextBuilder.kt (800 lines)
├── constraints/
│   ├── Constraint.kt (150 lines)
│   ├── ConstraintGenerator.kt (500 lines)
│   ├── ExpressionConstraints.kt (1,500 lines)
│   ├── PatternConstraints.kt (500 lines)
│   ├── AbilityConstraints.kt (300 lines)
│   └── EffectConstraints.kt (200 lines)
├── solver/
│   ├── ConstraintSolver.kt (800 lines)
│   └── Unification.kt (200 lines)
├── validation/
│   ├── TypeValidator.kt (600 lines)
│   ├── AbilityValidator.kt (500 lines)
│   ├── EffectValidator.kt (400 lines)
│   └── ExhaustivenessValidator.kt (200 lines)
└── substitution/
    └── SubstitutionApplier.kt (400 lines)
```

**Result: 20 files, largest 1,500 lines, total ~8,000 lines**

## Migration Steps

### Step 1: Create Immutable Context (Week 1)

**Goal:** Replace mutable state with immutable context

```kotlin
// OLD (mutable!)
class TypeChecker {
    private val context = mutableMapOf<String, FunkType>()
    private val constraints = mutableListOf<Pair<FunkType, FunkType>>()
    
    fun inferBasicType(expr: Expression): FunkType {
        context[name] = type  // MUTATION!
        constraints.add(...)  // MUTATION!
    }
}

// NEW (immutable!)
data class TypingContext(
    val bindings: Map<String, TypeScheme>,
    val parent: TypingContext? = null
) {
    fun extend(name: String, scheme: TypeScheme) = 
        TypingContext(bindings + (name to scheme), this)
    
    fun lookup(name: String): TypeScheme? = 
        bindings[name] ?: parent?.lookup(name)
}

data class ConstraintResult(
    val type: FunkType,
    val constraints: List<Constraint>
)
```

**Files to create:**
- `context/TypingContext.kt`
- `context/TypeScheme.kt`

**Tests:** Context lookup, extension, immutability

---

### Step 2: Extract Constraint Generation (Week 2-3)

**Goal:** Separate constraint generation from solving

```kotlin
// OLD (mixed!)
fun inferBasicType(expr: Expression): FunkType {
    val t1 = inferBasicType(left)     // Generate
    val t2 = inferBasicType(right)    // Generate  
    constraints.add(t1 to expectedT)  // Generate
    solveConstraints()                // SOLVE (mixed!)
    return applySubstitution(t1)      // Apply
}

// NEW (separated!)
fun generateConstraints(expr: Expression, ctx: TypingContext): ConstraintResult {
    return when (expr) {
        is IntLiteral -> ConstraintResult(IntType, emptyList())
        is BinaryOp -> {
            val left = generateConstraints(expr.left, ctx)
            val right = generateConstraints(expr.right, ctx)
            val result = freshTypeVar()
            ConstraintResult(
                type = result,
                constraints = left.constraints + right.constraints + 
                    listOf(Equality(left.type, right.type))
            )
        }
    }
}
```

**Files to create:**
- `constraints/Constraint.kt`
- `constraints/ConstraintGenerator.kt`
- `constraints/ExpressionConstraints.kt`
- `constraints/PatternConstraints.kt`

**Tests:** Each expression type generates correct constraints

---

### Step 3: Clean Up Constraint Solving (Week 4)

**Goal:** Make solver pure, no side effects

```kotlin
// OLD (mutates!)
fun solveConstraints() {
    for ((t1, t2) in constraints) {
        val unified = unify(t1, t2)
        accumulatedSubstitutions.putAll(unified)  // MUTATE!
    }
}

// NEW (pure!)
fun solveConstraints(constraints: List<Constraint>): Substitution {
    return constraints.fold(Substitution.empty) { subst, constraint ->
        when (constraint) {
            is Equality -> {
                val t1 = subst.apply(constraint.t1)
                val t2 = subst.apply(constraint.t2)
                subst.compose(unify(t1, t2))
            }
        }
    }
}

data class Substitution(val mappings: Map<TypeVar, FunkType>) {
    fun apply(type: FunkType): FunkType = ...
    fun compose(other: Substitution): Substitution = ...
    
    companion object {
        val empty = Substitution(emptyMap())
    }
}
```

**Files to create:**
- `solver/ConstraintSolver.kt`
- `solver/Unification.kt`
- `solver/Substitution.kt`

**Tests:** Unification algorithm, substitution composition

---

### Step 4: Extract Validation (Week 5)

**Goal:** Validate after inference, not during

```kotlin
// OLD (mixed!)
fun inferBasicType(expr: Expression): FunkType {
    val type = ...
    validateAbilityConstraints(...)  // MIXED!
    validateEffects(...)             // MIXED!
    checkExhaustiveness(...)         // MIXED!
    return type
}

// NEW (separated!)
fun typeCheck(program: Program): TypeCheckResult {
    val ctx = buildContext(program)
    val (types, constraints) = generateConstraints(program, ctx)
    val substitution = solveConstraints(constraints)
    
    // Validate AFTER inference
    val validator = Validator(ctx, substitution)
    validator.validateTypes(program)
    validator.validateAbilities(program)
    validator.validateEffects(program)
    validator.validateExhaustiveness(program)
    
    return applySubstitutions(program, substitution)
}
```

**Files to create:**
- `validation/Validator.kt`
- `validation/TypeValidator.kt`
- `validation/AbilityValidator.kt`
- `validation/EffectValidator.kt`
- `validation/ExhaustivenessValidator.kt`

**Tests:** Each validator independently

---

### Step 5: Bidirectional Typing (Week 6)

**Goal:** Separate `infer` and `check` modes

```kotlin
// Inference mode: figure out what type an expression has
fun infer(expr: Expression, ctx: TypingContext): ConstraintResult

// Checking mode: verify expression has expected type  
fun check(expr: Expression, expected: FunkType, ctx: TypingContext): ConstraintResult

// Use appropriately
when (expr) {
    is LambdaExpression -> {
        if (expectedType is FunctionType) {
            // Check mode: we know the type
            check(expr.body, expectedType.returnType, ctx)
        } else {
            // Infer mode: figure it out
            infer(expr, ctx)
        }
    }
}
```

**Files to create:**
- `constraints/InferMode.kt`
- `constraints/CheckMode.kt`

**Tests:** Bidirectional inference for lambdas, records, variants

---

### Step 6: Remove "CRITICAL FIX" Bandaids (Week 7)

**Goal:** Proper design for special cases

**Built-in Abilities:**
```kotlin
// OLD (bandaid)
// CRITICAL FIX: Skip Inspect - it's implicit
if (constraint.abilityName == "Inspect") continue

// NEW (proper design)
object BuiltInAbilities {
    val INSPECT = Ability("Inspect", universal = true)
    val EQUALITY = Ability("Eq", derivable = true)
    
    fun isUniversal(name: String) = builtIns[name]?.universal == true
}
```

**Type Application Expansion:**
```kotlin
// OLD (bandaid)
// CRITICAL FIX: Expand TypeApplication to underlying type
val underlying = when (type) {
    is TypeApplication -> expandTypeApplication(type)
    else -> type
}

// NEW (proper design)
sealed class FunkType {
    // Type applications are first-class, not special-cased
    fun normalize(): FunkType = ...
    fun substitute(subst: Substitution): FunkType = ...
}
```

**Files to refactor:**
- Remove 217 "CRITICAL FIX" comments
- Replace with proper abstractions

---

### Step 7: New Orchestrator (Week 8)

**Goal:** Clean TypeChecker.kt orchestrator (500 lines)

```kotlin
class TypeChecker(
    private val contextBuilder: ContextBuilder,
    private val constraintGenerator: ConstraintGenerator,
    private val solver: ConstraintSolver,
    private val validator: Validator,
    private val applier: SubstitutionApplier
) {
    fun typeCheck(program: Program): TypeCheckResult {
        // Phase 1: Build context
        val context = contextBuilder.build(program)
        
        // Phase 2: Generate constraints
        val (types, constraints) = constraintGenerator.generate(program, context)
        
        // Phase 3: Solve constraints
        val substitution = solver.solve(constraints)
        
        // Phase 4: Validate
        validator.validate(program, context, substitution)
        
        // Phase 5: Apply substitutions
        return applier.apply(program, substitution)
    }
}
```

**Files to create:**
- New `TypeChecker.kt` (500 lines)

**Delete:**
- Old `TypeChecker.kt` (14,343 lines)

---

## Benefits

### Performance

- **Faster:** Constraint solving in one pass, not mixed with generation
- **Less redundant work:** Clear phases avoid re-computation
- **Parallelizable:** Constraint generation per-expression could be parallel

### Correctness

- **Fewer bugs:** Immutable data eliminates state-related bugs
- **More testable:** Each phase tested independently
- **Clearer invariants:** Data structures enforce correctness

### Maintainability

- **Easier to understand:** 1,500 line files vs 18,000
- **Easier to modify:** Changes are localized  
- **Easier to debug:** Clear data flow
- **No bandaids:** Proper solutions, not "CRITICAL FIX"

## Timeline

- **Week 1:** Immutable context
- **Week 2-3:** Constraint generation extraction
- **Week 4:** Constraint solving cleanup
- **Week 5:** Validation extraction
- **Week 6:** Bidirectional typing separation
- **Week 7:** Remove "CRITICAL FIX" bandaids
- **Week 8:** New orchestrator, delete old code

**Total: 8 weeks to clean architecture**

## Success Criteria

- ✅ All 1,778 tests pass
- ✅ No "CRITICAL FIX" comments
- ✅ All files under 1,600 lines
- ✅ Largest file: 1,500 lines (down from 18,000!)
- ✅ Clear separation of concerns
- ✅ Immutable data structures
- ✅ No shared mutable state
- ✅ Performance equal or better

