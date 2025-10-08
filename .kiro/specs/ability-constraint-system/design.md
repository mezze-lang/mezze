# Design Document

## Overview

This design document outlines the implementation of a comprehensive ability constraint system for the Funk type checker. The system will add type class-like constraints to the existing Hindley-Milner type inference, enabling proper polymorphic arithmetic and comparison operations while maintaining backward compatibility. The design integrates with the existing constraint solver and module resolution system to provide seamless qualified function calls with ability constraints.

## Architecture

### Core Components

The ability constraint system extends the existing type checker architecture with these key components:

1. **AbilityConstraint** - New constraint type for tracking ability requirements
2. **AbilityRegistry** - Central registry for built-in and user-defined abilities  
3. **ConstraintSolver Extensions** - Enhanced constraint solving with ability validation
4. **TypePrinter Extensions** - Display constraints in type signatures
5. **ModuleResolver Integration** - Qualified function calls with constraint propagation

### Integration Strategy

The design uses a **constraint-based approach** that integrates seamlessly with the existing type system:
- Ability constraints are generated during type inference
- Constraints are solved alongside existing unification constraints
- Module resolution propagates constraints through qualified calls
- Type display includes constraint information

## Components and Interfaces

### 1. Ability Constraint Representation

**Core Data Structures:**
```kotlin
// Enhanced constraint system
sealed class Constraint {
    data class Equality(val left: FunkType, val right: FunkType) : Constraint()
    data class Instance(val type: FunkType, val scheme: FunkType) : Constraint()
    
    // NEW: Ability constraints
    data class AbilityConstraint(
        val typeVar: TypeVar,
        val ability: String,
        val sourceLocation: SourceLocation? = null
    ) : Constraint()
}

// Ability definitions
data class Ability(
    val name: String,
    val operations: Map<String, FunkType>,
    val superAbilities: List<String> = emptyList()
)

// Built-in abilities
object BuiltinAbilities {
    val NumAdd = Ability("NumAdd", mapOf("+" to arithmeticOpType))
    val NumSub = Ability("NumSub", mapOf("-" to arithmeticOpType))
    val NumMul = Ability("NumMul", mapOf("*" to arithmeticOpType))
    val NumDiv = Ability("NumDiv", mapOf("/" to arithmeticOpType))
    val Eq = Ability("Eq", mapOf("==" to comparisonOpType, "!=" to comparisonOpType))
    val Ord = Ability("Ord", mapOf("<" to comparisonOpType, ">" to comparisonOpType), listOf("Eq"))
}
```

### 2. Enhanced Type System

**Type Representation with Constraints:**
```kotlin
// Enhanced FunkType with constraint tracking
sealed interface FunkType {
    // Existing types...
    
    // Enhanced function types with ability constraints
    data class FunctionType(
        val paramType: FunkType,
        val returnType: FunkType,
        val effects: EffectRow? = null,
        val abilityConstraints: List<AbilityConstraint> = emptyList()
    ) : FunkType
    
    // Constrained type variables
    data class ConstrainedVarType(
        val variable: TypeVar,
        val constraints: Set<String> = emptySet()
    ) : FunkType
}

// Type scheme with constraints for let-polymorphism
data class TypeScheme(
    val typeVars: Set<TypeVar>,
    val constraints: Set<AbilityConstraint>,
    val type: FunkType
) {
    fun instantiate(): Pair<FunkType, Set<AbilityConstraint>> {
        val substitution = typeVars.associateWith { TypeVar.fresh() }
        val instantiatedType = applySubstitution(type, substitution)
        val instantiatedConstraints = constraints.map { constraint ->
            AbilityConstraint(
                substitution[constraint.typeVar] ?: constraint.typeVar,
                constraint.ability,
                constraint.sourceLocation
            )
        }.toSet()
        return instantiatedType to instantiatedConstraints
    }
}
```

### 3. Ability Registry

**Central Ability Management:**
```kotlin
class AbilityRegistry {
    private val abilities = mutableMapOf<String, Ability>()
    private val typeAbilities = mutableMapMap<FunkType, MutableSet<String>>()
    
    init {
        // Register built-in abilities
        registerBuiltinAbilities()
    }
    
    fun registerAbility(ability: Ability) {
        abilities[ability.name] = ability
    }
    
    fun hasAbility(type: FunkType, abilityName: String): Boolean {
        return when (type) {
            is FunkType.IntType -> abilityName in setOf("NumAdd", "NumSub", "NumMul", "NumDiv", "Eq", "Ord")
            is FunkType.FracType -> abilityName in setOf("NumAdd", "NumSub", "NumMul", "NumDiv", "Eq", "Ord")
            is FunkType.BooleanType -> abilityName in setOf("Eq", "LogicalAnd", "LogicalOr", "LogicalNot")
            is FunkType.StringType -> abilityName in setOf("Eq", "Ord", "StringConcat")
            is FunkType.VarType -> false // Type variables don't have abilities until constrained
            else -> typeAbilities[type]?.contains(abilityName) ?: false
        }
    }
    
    fun getRequiredAbilities(operation: String): Set<String> {
        return when (operation) {
            "+" -> setOf("NumAdd")
            "-" -> setOf("NumSub") 
            "*" -> setOf("NumMul")
            "/" -> setOf("NumDiv")
            "==" -> setOf("Eq")
            "!=" -> setOf("Eq")
            "<", ">", "<=", ">=" -> setOf("Ord")
            else -> emptySet()
        }
    }
}
```

### 4. Enhanced Constraint Solver

**Ability-Aware Constraint Solving:**
```kotlin
class EnhancedConstraintSolver(
    private val abilityRegistry: AbilityRegistry
) {
    
    fun solve(constraints: Set<Constraint>): Substitution {
        val (equalityConstraints, abilityConstraints) = constraints.partition { 
            it is Constraint.Equality || it is Constraint.Instance 
        }
        
        // First solve equality constraints using existing unification
        val substitution = solveEqualityConstraints(equalityConstraints)
        
        // Then validate ability constraints
        validateAbilityConstraints(abilityConstraints, substitution)
        
        return substitution
    }
    
    private fun validateAbilityConstraints(
        constraints: List<Constraint>, 
        substitution: Substitution
    ) {
        for (constraint in constraints) {
            if (constraint is Constraint.AbilityConstraint) {
                val resolvedType = substitution.apply(FunkType.VarType(constraint.typeVar))
                
                if (!abilityRegistry.hasAbility(resolvedType, constraint.ability)) {
                    throw TypeCheckError(
                        "Type '${prettyPrintType(resolvedType)}' does not implement ability '${constraint.ability}'",
                        constraint.sourceLocation
                    )
                }
            }
        }
    }
}
```

### 5. Type Inference Integration

**Operator Constraint Generation:**
```kotlin
// Enhanced binary operator type inference
private fun synthesizeBinaryOperatorType(expr: BinaryOperatorExpression): FunkType {
    val leftType = synthesizeType(expr.left)
    val rightType = synthesizeType(expr.right)
    
    // Unify operand types
    unify(leftType, rightType)
    
    // Generate ability constraints based on operator
    val requiredAbilities = abilityRegistry.getRequiredAbilities(expr.operator)
    
    when (val operandType = leftType) {
        is FunkType.VarType -> {
            // Add ability constraints for type variable
            for (ability in requiredAbilities) {
                constraints.add(Constraint.AbilityConstraint(
                    operandType.variable, 
                    ability, 
                    expr.sourceLocation
                ))
            }
        }
        else -> {
            // Validate concrete type has required abilities
            for (ability in requiredAbilities) {
                if (!abilityRegistry.hasAbility(operandType, ability)) {
                    throw TypeCheckError(
                        "Type '${prettyPrintType(operandType)}' does not support operation '${expr.operator}' (missing ability '$ability')",
                        expr.sourceLocation
                    )
                }
            }
        }
    }
    
    return when (expr.operator) {
        in setOf("==", "!=", "<", ">", "<=", ">=") -> FunkType.BooleanType
        else -> leftType
    }
}
```

### 6. Module Resolution Integration

**Qualified Function Calls with Constraints:**
```kotlin
// Enhanced module resolution for qualified calls
private fun resolveQualifiedCall(
    moduleName: String, 
    functionName: String, 
    argumentType: FunkType
): FunkType {
    val module = moduleResolver.resolveModule(moduleName)
    val exportedFunction = module.exports[functionName] 
        ?: throw TypeCheckError("Function '$functionName' not found in module '$moduleName'")
    
    // Instantiate polymorphic function with fresh type variables
    val (functionType, constraints) = when (exportedFunction.funkType) {
        is TypeScheme -> exportedFunction.funkType.instantiate()
        else -> exportedFunction.funkType to emptySet<AbilityConstraint>()
    }
    
    // Add ability constraints to current context
    this.constraints.addAll(constraints.map { it as Constraint })
    
    // Validate argument type and return function result type
    if (functionType is FunkType.FunctionType) {
        unify(argumentType, functionType.paramType)
        return functionType.returnType
    } else {
        throw TypeCheckError("'$functionName' is not a function")
    }
}

// Example: int::to_string resolution
private fun handleQualifiedCall(expr: QualifiedCall): FunkType {
    // For int::to_string {value = sum}
    // 1. Resolve int::to_string -> {value: Int} -> String
    // 2. Infer argument type and unify with parameter
    // 3. Return String type
    
    val argumentType = synthesizeType(expr.arguments)
    return resolveQualifiedCall(expr.moduleName, expr.functionName, argumentType)
}
```

## Data Models

### Constraint System Extensions

```kotlin
// Enhanced constraint representation
sealed class Constraint {
    data class Equality(val left: FunkType, val right: FunkType) : Constraint()
    data class Instance(val type: FunkType, val scheme: TypeScheme) : Constraint()
    
    // NEW: Ability constraints
    data class AbilityConstraint(
        val typeVar: TypeVar,
        val ability: String,
        val sourceLocation: SourceLocation? = null
    ) : Constraint() {
        override fun toString(): String = "${typeVar.name} : $ability"
    }
}

// Constraint context for tracking during inference
data class ConstraintContext(
    val equalityConstraints: MutableSet<Constraint.Equality> = mutableSetOf(),
    val abilityConstraints: MutableSet<Constraint.AbilityConstraint> = mutableSetOf(),
    val instanceConstraints: MutableSet<Constraint.Instance> = mutableSetOf()
) {
    fun addAbilityConstraint(typeVar: TypeVar, ability: String, location: SourceLocation? = null) {
        abilityConstraints.add(Constraint.AbilityConstraint(typeVar, ability, location))
    }
    
    fun getAllConstraints(): Set<Constraint> {
        return equalityConstraints + abilityConstraints + instanceConstraints
    }
}
```

### Type Display Extensions

```kotlin
// Enhanced type printer with constraint display
object TypePrinter {
    fun printTypeWithConstraints(
        type: FunkType, 
        constraints: Set<AbilityConstraint>
    ): String {
        val baseType = prettyPrintType(type)
        
        if (constraints.isEmpty()) {
            return baseType
        }
        
        val constraintsByVar = constraints.groupBy { it.typeVar }
        val constraintStrings = constraintsByVar.map { (typeVar, constraints) ->
            val abilities = constraints.map { it.ability }.sorted().joinToString(", ")
            "${typeVar.name} with $abilities"
        }
        
        return if (constraintStrings.isNotEmpty()) {
            "$baseType with ${constraintStrings.joinToString(", ")}"
        } else {
            baseType
        }
    }
}

// Example outputs:
// {x: 'a, y: 'a} -> 'a with NumAdd 'a
// {x: 'a, y: 'a} -> Bool with Eq 'a  
// {x: 'a, y: 'a} -> 'a with NumAdd 'a, Eq 'a
```

## Error Handling

### Ability Constraint Errors

```kotlin
sealed class AbilityError(message: String, location: SourceLocation?) : TypeCheckError(message, location) {
    
    class MissingAbility(
        val type: FunkType,
        val ability: String,
        val operation: String,
        location: SourceLocation?
    ) : AbilityError(
        "Type '${prettyPrintType(type)}' does not implement ability '$ability' required for operation '$operation'",
        location
    )
    
    class ConflictingConstraints(
        val typeVar: TypeVar,
        val conflictingAbilities: Set<String>,
        location: SourceLocation?
    ) : AbilityError(
        "Type variable '${typeVar.name}' has conflicting ability requirements: ${conflictingAbilities.joinToString(", ")}",
        location
    )
    
    class UnresolvableConstraint(
        val constraint: AbilityConstraint,
        val reason: String,
        location: SourceLocation?
    ) : AbilityError(
        "Cannot resolve ability constraint '${constraint}': $reason",
        location
    )
}
```

### Error Recovery Strategy

1. **Constraint Relaxation** - When ability constraints fail, continue with warning
2. **Partial Solutions** - Provide best-effort type inference with constraint violations noted
3. **Suggestion Generation** - Suggest missing ability implementations or type annotations

## Testing Strategy

### Test Categories

1. **Unit Tests** - Individual ability constraint operations
2. **Integration Tests** - Full type checking with abilities and modules
3. **Regression Tests** - Ensure existing code continues to work
4. **Performance Tests** - Validate constraint solving performance

### Key Test Scenarios

```funk
// Basic ability constraint inference
let add = {x, y} -> x + y
// Should infer: {x: 'a, y: 'a} -> 'a with NumAdd 'a

// Complex expression with module resolution  
let noiseAdd = {x, y} -> do
    let sum = x + y
    int::to_string {value = sum}
// Should infer: {x: Int, y: Int} -> String

// Mixed constraints
let compareAndAdd = {x, y} -> if x == y then x + y else x
// Should infer: {x: 'a, y: 'a} -> 'a with NumAdd 'a, Eq 'a

// Error cases
let badAdd = {x, y} -> x + y
let result = badAdd {x = "hello", y = "world"}  
// Should error: String does not implement NumAdd
```

## Implementation Phases

### Phase 1: Core Ability System
- Implement AbilityConstraint and AbilityRegistry
- Add basic constraint generation for arithmetic operators
- Integrate with existing constraint solver

### Phase 2: Type Display and Error Reporting
- Enhance TypePrinter to show constraints
- Implement comprehensive error messages for ability violations
- Add constraint validation in constraint solver

### Phase 3: Module Integration
- Integrate ability constraints with module resolution
- Support qualified function calls with constraint propagation
- Handle complex expressions like the noiseAdd example

### Phase 4: Advanced Features and Optimization
- Add user-defined abilities
- Optimize constraint solving performance
- Implement constraint inference for higher-order functions

## Performance Considerations

### Constraint Solving Complexity
- **Target**: Maintain polynomial time complexity
- **Strategy**: Incremental constraint solving with early termination
- **Optimization**: Constraint dependency analysis to avoid redundant work

### Memory Usage
- **Constraint Storage**: Use efficient data structures for constraint sets
- **Type Variable Management**: Reuse type variables where possible
- **Caching**: Cache ability lookups for concrete types

## Migration Strategy

### Backward Compatibility
- Existing polymorphic functions automatically get inferred constraints
- Concrete type operations continue to work without changes
- Gradual adoption of explicit constraint syntax

### Breaking Changes
- Enhanced error messages may reveal previously hidden type errors
- More precise type inference may require some type annotations
- Module resolution improvements may affect import behavior

## Quality Assurance

### Validation Criteria
- All existing tests continue to pass
- New ability constraint features work as specified
- Performance remains within acceptable bounds (< 20% slowdown)
- Error messages are clear and actionable

### Code Quality Metrics
- Test coverage > 90% for new ability constraint code
- Integration test coverage for module resolution scenarios
- Performance benchmarks for constraint solving
- Documentation completeness for new APIs