# Ability Constraint System - Developer Guide

This guide is for developers working on the Funk language implementation, specifically the ability constraint system.

## Architecture Overview

The ability constraint system is implemented across several key components:

```
ast/
├── FunkAST.kt                    # Core AST types with constraint support
└── TypePrinter.kt               # Type display with constraints

typechecker/
├── TypeChecker.kt               # Main type checker with constraint generation
├── ConstraintSolver.kt          # Constraint solving and ability validation
├── AbilityRegistry.kt           # Built-in ability definitions
└── ErrorPrettyPrinter.kt        # Constraint error reporting
```

## Core Data Structures

### Constraint Types

```kotlin
sealed class Constraint {
    data class Equality(val left: FunkType, val right: FunkType) : Constraint()
    data class AbilityConstraint(val typeVar: TypeVar, val ability: String) : Constraint()
}
```

### Ability Definitions

```kotlin
data class Ability(
    val name: String,
    val operations: Set<String>,
    val superAbilities: Set<String> = emptySet()
)
```

### Type Schemes with Constraints

```kotlin
data class TypeScheme(
    val typeVars: Set<TypeVar>,
    val constraints: Set<Constraint>,
    val type: FunkType
)
```

## Key Components

### 1. AbilityRegistry

Manages built-in and user-defined abilities:

```kotlin
class AbilityRegistry {
    fun hasAbility(type: FunkType, ability: String): Boolean
    fun getRequiredAbility(operator: String): String?
    fun registerAbility(ability: Ability)
    fun getAbilitiesForType(type: FunkType): Set<String>
}
```

**Built-in Abilities:**
- `NumAdd`, `NumSub`, `NumMul`, `NumDiv`, `NumMod` for arithmetic
- `Eq` for equality comparison
- `Ord` for ordering comparison  
- `LogicalAnd`, `LogicalOr`, `LogicalNot` for boolean operations
- `StringConcat` for string concatenation

### 2. Constraint Generation

The `TypeChecker` generates ability constraints during type inference:

```kotlin
private fun synthesizeBinaryOperatorType(
    operator: String,
    left: Expression,
    right: Expression,
    context: TypeContext
): Pair<FunkType, Set<Constraint>> {
    // Generate constraints for binary operations
    val requiredAbility = abilityRegistry.getRequiredAbility(operator)
    if (requiredAbility != null) {
        // Add ability constraint for the operand types
        constraints.add(Constraint.AbilityConstraint(typeVar, requiredAbility))
    }
}
```

### 3. Constraint Solving

The `ConstraintSolver` validates ability constraints:

```kotlin
class ConstraintSolver(private val abilityRegistry: AbilityRegistry) {
    fun solveWithAbilities(constraints: Set<Constraint>): Substitution {
        // 1. Solve equality constraints first
        val substitution = solveEqualityConstraints(equalityConstraints)
        
        // 2. Validate ability constraints
        validateAbilityConstraints(abilityConstraints, substitution)
        
        return substitution
    }
}
```

### 4. Type Display

The `TypePrinter` shows constraint information:

```kotlin
fun printTypeWithConstraints(typeScheme: TypeScheme): String {
    val typeStr = printType(typeScheme.type)
    val constraintStr = formatConstraints(typeScheme.constraints)
    return if (constraintStr.isNotEmpty()) {
        "$typeStr where $constraintStr"
    } else {
        typeStr
    }
}
```

## Adding New Abilities

### 1. Define the Ability

```kotlin
// In AbilityRegistry.kt
private fun registerBuiltinAbilities() {
    // Add your new ability
    registerAbility(Ability(
        name = "MyNewAbility",
        operations = setOf("myOp"),
        superAbilities = emptySet()
    ))
}
```

### 2. Map Operations to Abilities

```kotlin
private fun getRequiredAbility(operator: String): String? {
    return when (operator) {
        "myOp" -> "MyNewAbility"
        // ... existing mappings
        else -> null
    }
}
```

### 3. Register Type Support

```kotlin
private fun getAbilitiesForType(type: FunkType): Set<String> {
    return when (type) {
        is FunkType.MyCustomType -> setOf("MyNewAbility")
        // ... existing type mappings
        else -> emptySet()
    }
}
```

### 4. Add Constraint Generation

```kotlin
// In TypeChecker.kt - if needed for new syntax
private fun synthesizeMyOperatorType(
    operand: Expression,
    context: TypeContext
): Pair<FunkType, Set<Constraint>> {
    val (operandType, operandConstraints) = synthesize(operand, context)
    val constraints = mutableSetOf<Constraint>()
    constraints.addAll(operandConstraints)
    
    // Add ability constraint
    if (operandType is FunkType.VarType) {
        constraints.add(Constraint.AbilityConstraint(operandType.typeVar, "MyNewAbility"))
    }
    
    return Pair(resultType, constraints)
}
```

## Testing Guidelines

### 1. Unit Tests

Test each component in isolation:

```kotlin
@Test
fun `test ability constraint generation`() {
    val code = "let add = {x, y} -> x + y"
    val program = parser.parse(code)
    val (typedProgram, constraints) = typeChecker.inferWithConstraints(program)
    
    // Verify NumAdd constraint was generated
    assertTrue(constraints.any { 
        it is Constraint.AbilityConstraint && it.ability == "NumAdd" 
    })
}
```

### 2. Integration Tests

Test the full constraint system:

```kotlin
@Test
fun `test polymorphic function with constraints`() {
    val code = """
        let add = {x, y} -> x + y
        let result = add {x = 1, y = 2}
    """
    
    val program = parser.parse(code)
    val typedProgram = typeChecker.typeCheck(program)
    
    // Should succeed - Int has NumAdd
    assertNotNull(typedProgram)
}
```

### 3. Error Tests

Test constraint violation detection:

```kotlin
@Test
fun `test constraint violation error`() {
    val code = "let result = true + false"
    
    val exception = assertThrows(TypeCheckError::class.java) {
        val program = parser.parse(code)
        typeChecker.typeCheck(program)
    }
    
    assertTrue(exception.message!!.contains("NumAdd"))
}
```

## Performance Considerations

### 1. Constraint Generation

- Generate constraints lazily during type inference
- Avoid redundant constraint creation
- Use efficient data structures (Sets for constraint collections)

### 2. Constraint Solving

- Solve equality constraints before ability constraints
- Early termination for unsolvable constraints
- Cache ability lookups for concrete types

### 3. Memory Usage

- Constraints are lightweight (just type variables + ability names)
- Clean up temporary constraints after solving
- Reuse constraint objects where possible

## Debugging Tips

### 1. Enable Constraint Logging

```kotlin
// In TypeChecker.kt
private val debugConstraints = System.getProperty("funk.debug.constraints") == "true"

private fun logConstraint(constraint: Constraint) {
    if (debugConstraints) {
        println("Generated constraint: $constraint")
    }
}
```

### 2. Inspect Type Schemes

```kotlin
// Print type schemes with constraints
fun debugTypeScheme(name: String, scheme: TypeScheme) {
    println("$name: ${typePrinter.printTypeWithConstraints(scheme)}")
}
```

### 3. Trace Constraint Solving

```kotlin
// In ConstraintSolver.kt
private fun traceConstraintSolving(constraints: Set<Constraint>) {
    constraints.forEach { constraint ->
        when (constraint) {
            is Constraint.AbilityConstraint -> {
                val hasAbility = abilityRegistry.hasAbility(
                    resolvedType, constraint.ability
                )
                println("Checking ${constraint.typeVar} : ${constraint.ability} -> $hasAbility")
            }
        }
    }
}
```

## Common Issues and Solutions

### 1. Missing Ability Registration

**Problem**: Type checker reports missing ability for a type that should support it.

**Solution**: Check `AbilityRegistry.getAbilitiesForType()` includes the ability for your type.

### 2. Constraint Not Generated

**Problem**: Expected ability constraint not generated during type inference.

**Solution**: Verify `getRequiredAbility()` maps your operator to the correct ability.

### 3. Constraint Solving Fails

**Problem**: Valid constraints are rejected during solving.

**Solution**: Check constraint solving order - equality constraints must be solved first.

### 4. Poor Error Messages

**Problem**: Constraint violation errors are unclear or unhelpful.

**Solution**: Enhance `ErrorPrettyPrinter.formatAbilityError()` with better messaging.

## Code Style Guidelines

### 1. Naming Conventions

- Abilities: PascalCase (`NumAdd`, `StringConcat`)
- Constraint variables: camelCase (`abilityConstraints`, `typeVar`)
- Methods: camelCase (`hasAbility`, `generateConstraints`)

### 2. Error Handling

```kotlin
// Good: Specific error types
throw TypeCheckError("Type '${type}' does not support ability '${ability}'", location)

// Bad: Generic exceptions
throw Exception("Constraint error")
```

### 3. Documentation

```kotlin
/**
 * Generates ability constraints for binary operations.
 * 
 * @param operator The binary operator (e.g., "+", "==")
 * @param leftType Type of the left operand
 * @param rightType Type of the right operand
 * @return Set of ability constraints required for the operation
 */
fun generateBinaryConstraints(
    operator: String, 
    leftType: FunkType, 
    rightType: FunkType
): Set<Constraint>
```

## Future Enhancements

### 1. Custom User Abilities

Allow users to define their own abilities:

```funk
ability Drawable {
    draw: {self} -> String
}

type Point = {x: Int, y: Int} with Drawable
```

### 2. Conditional Abilities

Abilities that depend on type parameters:

```funk
ability Container[T] where T : Eq {
    contains: {self, item: T} -> Bool
}
```

### 3. Ability Inference

Automatically infer abilities for user-defined types:

```funk
type MyNumber = {value: Int}
// Automatically gets NumAdd if it implements + operator
```

This developer guide should help you understand and extend the ability constraint system effectively. For specific implementation details, refer to the source code and test suites.