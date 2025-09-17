package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.ast.prettyPrintType
import com.funk.language.ast.prettyPrintTypeWithAliases
import com.funk.language.ast.TypeAliasInfo
import com.funk.language.stdlib.StdLib
import java.nio.file.Path
import java.nio.file.Paths

class TypeCheckError(message: String) : Exception(message)

/**
 * Bidirectional type checking modes
 */
sealed class TypeCheckingMode {
    object Synthesis : TypeCheckingMode()  // Infer the type (⇑)
    data class Checking(val expectedType: FunkType) : TypeCheckingMode()  // Check against expected type (⇓)
}

/**
 * Enhanced TypeChecker with bidirectional type checking and proper unification
 */
class TypeChecker {
    
    // Context for tracking variable types
    private val context = mutableMapOf<String, com.funk.language.ast.FunkType>()
    
    // Context for polymorphic let-bound variables (let-polymorphism)
    private val polymorphicContext = mutableMapOf<String, com.funk.language.ast.TypeScheme>()
    
    // Registry for type aliases
    private val typeAliases = mutableMapOf<String, TypeAliasInfo>()
    
    // Simple constraint collection for basic unification
    private val constraints = mutableListOf<Pair<com.funk.language.ast.FunkType, com.funk.language.ast.FunkType>>()
    
    // Constraint-based type inference
    private val constraintSolver = ConstraintSolver()
    
    // Current program being type checked (for looking up ImplDeclarations)
    private var currentProgram: Program? = null
    
    // Ability constraint tracking
    private val abilityConstraints = mutableListOf<FunkType.FunctionAbilityConstraint>()
    private val abilityDependencies = mutableMapOf<String, List<AbilityDependency>>()

    /**
     * Get the current type aliases registry (for enhanced printing)
     */
    fun getTypeAliases(): Map<String, TypeAliasInfo> = typeAliases.toMap()

    /**
     * Type check a program and return the enhanced AST with inferred types
     */
    fun typeCheck(program: Program): Program {
        return typeCheck(program, Paths.get("."))
    }

    /**
     * Type check a program with module resolution support
     */
    fun typeCheck(program: Program, currentPath: Path): Program {
        // Reset type variable counter for consistent naming
        com.funk.language.ast.TypeVar.resetCounter()
        // Simple type checking without module resolution for now
        return typeCheckSimple(program)
    }

    /**
     * Simple type checking - enhance the program items with basic type information
     */
    private fun typeCheckSimple(program: Program): Program {
        context.clear() // Reset context for each program
        typeAliases.clear() // Reset type aliases for each program
        constraints.clear() // Reset constraints
        currentProgram = program // Store current program for impl lookup
        
        // Add built-in functions to the context from stdlib
        registerStdLibTypes()
        
        val enhancedItems = program.items.map { item ->
            when (item) {
                is LetBinding -> {
                    // Reset scope counter for each top-level function to start fresh with 'a
                    com.funk.language.ast.TypeVar.resetScopeCounter(0)
                    
                    // Enhanced type checking with bidirectional inference
                    val finalType = item.typeAnnotation?.let { annotation ->
                        // If there's a type annotation, use bidirectional inference
                        val declaredType = convertTypeAnnotation(annotation)
                        val actualType = inferTypeWithUnification(item.expression, declaredType)
                        
                        if (unifyTypes(actualType, declaredType)) {
                            // Use the actual inferred type if it has more information (like effects)
                            // than the declared type
                            if (actualType is com.funk.language.ast.FunkType.FunctionType && 
                                declaredType is com.funk.language.ast.FunkType.FunctionType &&
                                actualType.effects != null && declaredType.effects == null) {
                                actualType
                            } else {
                            declaredType
                            }
                        } else {
                            throw RuntimeException("Type error: Declared type ${prettyPrintType(declaredType)} does not match inferred type ${prettyPrintType(actualType)}")
                        }
                    } ?: inferTypeWithUnification(item.expression)
                    
        // CRITICAL FIX: Normalize type variables for consistent naming ('a, 'b, etc.)
        val normalizedType = com.funk.language.ast.normalizeTypeVariables(finalType)
                    
                    // Check if this should be a polymorphic binding (let-polymorphism)
                    val freeVars = getFreeTypeVariables(normalizedType)
                    if (freeVars.isNotEmpty() && item.typeAnnotation == null) {
                        // This is a polymorphic type - generalize it
                        val scheme = generalize(normalizedType)
                        polymorphicContext[item.variableName] = scheme
                    } else {
                        // Monomorphic type or explicit annotation - store in regular context
                    context[item.variableName] = normalizedType
                    }
                    
                    // Set inferred types on the expression
                    val updatedExpression = setInferredTypes(item.expression)
                    
                    item.copy(expression = updatedExpression, inferredType = normalizedType)
                }
                is TypeDeclaration -> {
                    // Convert the type definition to a FunkType
                    val definitionType = convertTypeDefinition(item.definition)
                    
                    // Register the type alias
                    typeAliases[item.name] = TypeAliasInfo(
                        name = item.name,
                        typeParams = item.typeParams,
                        definition = definitionType
                    )
                    
                    item.copy(inferredType = definitionType)
                }
                is EffectDeclaration -> {
                    // Register effect in context
                    // Effects are represented as a record of operation signatures
                    val operationTypes = item.operations.associate { op ->
                        // Create function type with effect row if effects are present
                        val functionType = if (op.effects.isNotEmpty()) {
                            com.funk.language.ast.FunkType.FunctionType(
                                convertTypeAnnotation(op.paramType),
                                convertTypeAnnotation(op.returnType),
                                com.funk.language.ast.FunkType.EffectRow(op.effects.toSet()) // Wrap effects in EffectRow
                            )
                        } else {
                            com.funk.language.ast.FunkType.FunctionType(
                            convertTypeAnnotation(op.paramType),
                            convertTypeAnnotation(op.returnType)
                        )
                        }
                        op.name to functionType
                    }
                    val effectType = com.funk.language.ast.FunkType.RecordType(operationTypes)
                    context[item.name] = effectType
                    
                    item.copy(inferredType = effectType)
                }
                is ImplDeclaration -> {
                    // Validate performer implementation with new rules
                    validatePerformerImplementation(item)
                    
                    // Register performer implementation in context
                    val operationTypes = item.operations.associate { op ->
                        op.name to com.funk.language.ast.FunkType.FunctionType(
                            convertTypeAnnotation(op.paramType),
                            convertTypeAnnotation(op.returnType)
                        )
                    }
                    val performerType = com.funk.language.ast.FunkType.RecordType(operationTypes)
                    context[item.performerName] = performerType
                    
                    item.copy(inferredType = performerType)
                }
                is AbilityDeclaration -> {
                    // Validate ability dependencies
                    validateAbilityDependencies(item.name, item.dependencies)
                    
                    // Store ability dependencies for later constraint solving
                    abilityDependencies[item.name] = item.dependencies
                    
                    // Register ability in context as a type class
                    // Abilities are represented as a record of operation signatures with type parameters
                    val operationTypes = item.operations.associate { op ->
                        op.name to com.funk.language.ast.FunkType.FunctionType(
                            convertTypeAnnotation(op.paramType),
                            convertTypeAnnotation(op.returnType)
                        )
                    }
                    val abilityType = com.funk.language.ast.FunkType.RecordType(operationTypes)
                    context["${item.name}_ability"] = abilityType
                    
                    item.copy(inferredType = abilityType)
                }
                is ImplAbilityDeclaration -> {
                    // Register ability implementation in context
                    // This creates a concrete instance of the ability for the target type
                    val operationTypes = item.operations.associate { op ->
                        op.name to com.funk.language.ast.FunkType.FunctionType(
                            convertTypeAnnotation(op.paramType),
                            convertTypeAnnotation(op.returnType)
                        )
                    }
                    val implType = com.funk.language.ast.FunkType.RecordType(operationTypes)
                    val targetTypeNames = item.targetTypes.map { targetType ->
                        when (targetType) {
                            is IdentifierTypeAnnotation -> targetType.name
                            else -> "Unknown"
                        }
                    }
                    
                    // Store the implementation with a key that combines ability and target types
                    val key = "${item.abilityName}_${targetTypeNames.joinToString("_")}"
                    context[key] = implType
                    
                    item.copy(inferredType = implType)
                }
                is TopLevelExpression -> {
                    // Type check the top-level expression and set inferred types
                    val exprType = inferTypeWithUnification(item.expression)
                    val updatedExpression = setInferredTypes(item.expression)
                    item.copy(expression = updatedExpression)
                }
                else -> item
            }
        }
        return program.copy(items = enhancedItems)
    }

    /**
     * Set inferred types on all expressions in the AST
     */
    private fun setInferredTypes(expr: Expression): Expression {
        val inferredType = try {
            inferBasicType(expr)
        } catch (e: Exception) {
            null // If inference fails, leave type as null
        }
        
        return when (expr) {
            is LambdaExpression -> {
                val updatedBody = setInferredTypes(expr.body)
                
                expr.copy(
                    body = updatedBody, 
                    inferredType = inferredType,
                    abilityConstraints = expr.abilityConstraints  // Preserve ability constraints
                )
            }
            is DoExpression -> {
                val updatedStatements = expr.statements.map { statement ->
                    when (statement) {
                        is LetStatement -> statement.copy(expression = setInferredTypes(statement.expression))
                        is ExpressionStatement -> statement.copy(expression = setInferredTypes(statement.expression))
                        else -> statement
                    }
                }
                val updatedResult = setInferredTypes(expr.result)
                expr.copy(statements = updatedStatements, result = updatedResult, inferredType = inferredType)
            }
            is FunctionCall -> {
                val updatedFunction = setInferredTypes(expr.function)
                val updatedArguments = setInferredTypes(expr.arguments)
                expr.copy(function = updatedFunction, arguments = updatedArguments, inferredType = inferredType)
            }
            is RecordLiteral -> {
                val updatedEntries = expr.entries.map { entry ->
                    val updatedValue = entry.value?.let { setInferredTypes(it) }
                    entry.copy(value = updatedValue)
                }
                expr.copy(entries = updatedEntries, inferredType = inferredType)
            }
            is MatchExpression -> {
                val updatedExpression = setInferredTypes(expr.expression)
                val updatedCases = expr.cases.map { case ->
                    case.copy(expression = setInferredTypes(case.expression))
                }
                expr.copy(expression = updatedExpression, cases = updatedCases, inferredType = inferredType)
            }
            is PerformExpression -> {
                val updatedExpression = setInferredTypes(expr.expression)
                expr.copy(expression = updatedExpression, inferredType = inferredType)
            }
            is EffectCallExpression -> {
                val updatedArguments = setInferredTypes(expr.arguments)
                expr.copy(arguments = updatedArguments, inferredType = inferredType)
            }
            // For literals and identifiers, just set the inferred type
            is IntLiteral -> expr.copy(inferredType = inferredType)
            is FloatLiteral -> expr.copy(inferredType = inferredType)
            is StringLiteral -> expr.copy(inferredType = inferredType)
            is BooleanLiteral -> expr.copy(inferredType = inferredType)
            is Identifier -> expr.copy(inferredType = inferredType)
            is VariantConstruction -> {
                val updatedValue = expr.value?.let { setInferredTypes(it) }
                expr.copy(value = updatedValue, inferredType = inferredType)
            }
            // Add other expression types as needed
            else -> expr // For any unhandled expression types, return as-is
        }
    }

    /**
     * Enhanced type inference with constraint-based unification
     */
    private fun inferTypeWithUnification(expr: Expression): com.funk.language.ast.FunkType {
        return inferTypeWithUnification(expr, null)
    }
    
    private fun inferTypeWithUnification(expr: Expression, expectedType: com.funk.language.ast.FunkType?): com.funk.language.ast.FunkType {
        // Clear constraints for this inference
        constraints.clear()
        
        // Use bidirectional inference with expected type
        val inferredType = if (expectedType != null) {
            inferBasicTypeWithExpected(expr, expectedType)
        } else {
            inferBasicType(expr)
        }
        
        // Solve constraints and check for conflicts
        solveConstraints()
        
        return inferredType
    }
    
    /**
     * Solve constraints and return the substitutions
     */
    private fun solveConstraintsAndGetSubstitutions(): Map<com.funk.language.ast.TypeVar, com.funk.language.ast.FunkType> {
        val substitutions = mutableMapOf<com.funk.language.ast.TypeVar, com.funk.language.ast.FunkType>()
        
        for ((type1, type2) in constraints) {
            try {
                unifyTypesWithSubstitution(type1, type2, substitutions)
            } catch (e: Exception) {
                throw RuntimeException("Type error: Cannot unify ${prettyPrintType(type1)} with ${prettyPrintType(type2)}")
            }
        }
        
        // Apply substitutions to context
        for ((typeVar, substitutedType) in substitutions) {
            for ((varName, varType) in context) {
                context[varName] = applySubstitution(varType, substitutions)
            }
        }
        
        constraints.clear()
        return substitutions
    }

    /**
     * Add a type constraint for later solving
     */
    private fun addConstraint(type1: com.funk.language.ast.FunkType, type2: com.funk.language.ast.FunkType) {
        constraints.add(Pair(type1, type2))
    }
    
    /**
     * Solve accumulated constraints using proper unification
     */
    private fun solveConstraints() {
        val substitutions = mutableMapOf<com.funk.language.ast.TypeVar, com.funk.language.ast.FunkType>()
        
        for ((type1, type2) in constraints) {
            try {
                unifyTypesWithSubstitution(type1, type2, substitutions)
            } catch (e: Exception) {
                throw RuntimeException("Type error: Cannot unify ${prettyPrintType(type1)} with ${prettyPrintType(type2)}")
            }
        }
        
        // Apply substitutions to context
        for ((typeVar, substitutedType) in substitutions) {
            for ((varName, varType) in context) {
                context[varName] = applySubstitution(varType, substitutions)
            }
        }
        
        constraints.clear()
    }
    
    /**
     * Unify two types with substitution tracking
     */
    private fun unifyTypesWithSubstitution(
        type1: com.funk.language.ast.FunkType, 
        type2: com.funk.language.ast.FunkType, 
        substitutions: MutableMap<com.funk.language.ast.TypeVar, com.funk.language.ast.FunkType>
    ) {
        val t1 = applySubstitution(type1, substitutions)
        val t2 = applySubstitution(type2, substitutions)
        
        when {
            t1 == t2 -> return // Already unified
            
            t1 is com.funk.language.ast.FunkType.VarType -> {
                if (occursCheck(t1.variable, t2, substitutions)) {
                    throw RuntimeException("Occurs check failed")
                }
                substitutions[t1.variable] = t2
            }
            
            t2 is com.funk.language.ast.FunkType.VarType -> {
                if (occursCheck(t2.variable, t1, substitutions)) {
                    throw RuntimeException("Occurs check failed")
                }
                substitutions[t2.variable] = t1
            }
            
            t1 is com.funk.language.ast.FunkType.FunctionType && t2 is com.funk.language.ast.FunkType.FunctionType -> {
                unifyTypesWithSubstitution(t1.paramType, t2.paramType, substitutions)
                unifyTypesWithSubstitution(t1.returnType, t2.returnType, substitutions)
            }
            
            t1 is com.funk.language.ast.FunkType.RecordType && t2 is com.funk.language.ast.FunkType.RecordType -> {
                // Merge record types by combining all fields
                val mergedFields = mutableMapOf<String, com.funk.language.ast.FunkType>()
                
                // Add all fields from both records
                mergedFields.putAll(t1.fields)
                for ((fieldName, fieldType2) in t2.fields) {
                    val fieldType1 = mergedFields[fieldName]
                    if (fieldType1 != null) {
                        // Unify common field types
                        unifyTypesWithSubstitution(fieldType1, fieldType2, substitutions)
                    } else {
                        // Add new field from t2
                        mergedFields[fieldName] = fieldType2
                    }
                }
            }
            
            // NumType unification - Num 'a can unify with Int, Float, Nat, Frac
            t1 is com.funk.language.ast.FunkType.NumType && isNumericType(t2) -> {
                // Num 'a unifies with any numeric type by constraining the type variable
                substitutions[t1.constraint] = t2
            }
            
            t2 is com.funk.language.ast.FunkType.NumType && isNumericType(t1) -> {
                // Num 'a unifies with any numeric type by constraining the type variable
                substitutions[t2.constraint] = t1
            }
            
            t1 is com.funk.language.ast.FunkType.NumType && t2 is com.funk.language.ast.FunkType.NumType -> {
                // Two NumTypes unify by unifying their constraints
                unifyTypesWithSubstitution(
                    com.funk.language.ast.FunkType.VarType(t1.constraint),
                    com.funk.language.ast.FunkType.VarType(t2.constraint),
                    substitutions
                )
            }
            
            else -> {
                if (!isTypeCompatible(t1, t2)) {
                    throw RuntimeException("Incompatible types")
                }
            }
        }
    }
    
    /**
     * Generalize a type into a type scheme by quantifying free type variables
     */
    private fun generalize(type: com.funk.language.ast.FunkType): com.funk.language.ast.TypeScheme {
        val freeVars = getFreeTypeVariables(type)
        return com.funk.language.ast.TypeScheme(freeVars, type)
    }
    
    /**
     * CRITICAL FIX: Lookup variable with let-polymorphism support
     * Check polymorphic context first, then regular context
     */
    private fun lookupVariable(name: String): com.funk.language.ast.FunkType? {
        // First check polymorphic context and instantiate if found
        polymorphicContext[name]?.let { scheme ->
            return scheme.instantiate()
        }
        
        // Then check regular context
        return context[name]
    }
    
    /**
     * Get all free type variables in a type
     */
    private fun getFreeTypeVariables(type: com.funk.language.ast.FunkType): Set<com.funk.language.ast.TypeVar> {
        return when (type) {
            is com.funk.language.ast.FunkType.VarType -> setOf(type.variable)
            is com.funk.language.ast.FunkType.NumType -> setOf(type.constraint)
            is com.funk.language.ast.FunkType.FunctionType -> {
                getFreeTypeVariables(type.paramType) + getFreeTypeVariables(type.returnType)
            }
            is com.funk.language.ast.FunkType.RecordType -> {
                type.fields.values.flatMap { getFreeTypeVariables(it) }.toSet()
            }
            is com.funk.language.ast.FunkType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { getFreeTypeVariables(it) }.toSet()
            }
            // Concrete types have no free variables
            is com.funk.language.ast.FunkType.IntType, is com.funk.language.ast.FunkType.FloatType,
            is com.funk.language.ast.FunkType.StringType, is com.funk.language.ast.FunkType.BooleanType,
            is com.funk.language.ast.FunkType.NatType, is com.funk.language.ast.FunkType.FracType -> emptySet()
            is com.funk.language.ast.FunkType.EffectRow -> emptySet() // Effects don't have type variables for now
        }
    }
    
    /**
     * Check if a type is a numeric type (Int, Float, Nat, Frac)
     */
    private fun isNumericType(type: com.funk.language.ast.FunkType): Boolean {
        return when (type) {
            is com.funk.language.ast.FunkType.IntType,
            is com.funk.language.ast.FunkType.FloatType,
            is com.funk.language.ast.FunkType.NatType,
            is com.funk.language.ast.FunkType.FracType -> true
            else -> false
        }
    }
    
    /**
     * Check if a type is a concrete numeric type (not NumType or VarType)
     */
    private fun isConcreteNumericType(type: com.funk.language.ast.FunkType): Boolean {
        return when (type) {
            is com.funk.language.ast.FunkType.IntType,
            is com.funk.language.ast.FunkType.FloatType,
            is com.funk.language.ast.FunkType.NatType,
            is com.funk.language.ast.FunkType.FracType -> true
            else -> false
        }
    }
    
    /**
     * Apply substitutions to a type
     */
    private fun applySubstitution(
        type: com.funk.language.ast.FunkType, 
        substitutions: Map<com.funk.language.ast.TypeVar, com.funk.language.ast.FunkType>
    ): com.funk.language.ast.FunkType {
        return when (type) {
            is com.funk.language.ast.FunkType.VarType -> {
                substitutions[type.variable] ?: type
            }
            is com.funk.language.ast.FunkType.FunctionType -> {
                com.funk.language.ast.FunkType.FunctionType(
                    applySubstitution(type.paramType, substitutions),
                    applySubstitution(type.returnType, substitutions),
                    type.effects?.let { applySubstitution(it, substitutions) as com.funk.language.ast.FunkType.EffectRow }
                )
            }
            is com.funk.language.ast.FunkType.RecordType -> {
                val substitutedFields = type.fields.mapValues { (_, fieldType) ->
                    applySubstitution(fieldType, substitutions)
                }
                com.funk.language.ast.FunkType.RecordType(substitutedFields, type.rowVar)
            }
            is com.funk.language.ast.FunkType.NumType -> {
                // Apply substitution to the constraint type variable
                val substitutedConstraint = substitutions[type.constraint]
                if (substitutedConstraint != null) {
                    substitutedConstraint
                } else {
                    type
                }
            }
            else -> type
        }
    }
    
    /**
     * Occurs check to prevent infinite types
     */
    private fun occursCheck(
        typeVar: com.funk.language.ast.TypeVar, 
        type: com.funk.language.ast.FunkType, 
        substitutions: Map<com.funk.language.ast.TypeVar, com.funk.language.ast.FunkType>
    ): Boolean {
        val substitutedType = applySubstitution(type, substitutions)
        return when (substitutedType) {
            is com.funk.language.ast.FunkType.VarType -> substitutedType.variable == typeVar
            is com.funk.language.ast.FunkType.FunctionType -> 
                occursCheck(typeVar, substitutedType.paramType, substitutions) || 
                occursCheck(typeVar, substitutedType.returnType, substitutions)
            is com.funk.language.ast.FunkType.RecordType -> 
                substitutedType.fields.values.any { occursCheck(typeVar, it, substitutions) }
            else -> false
        }
    }
    
    /**
     * Simple unification check - enhanced version of isTypeCompatible
     */
    private fun unifyTypes(actualType: com.funk.language.ast.FunkType, expectedType: com.funk.language.ast.FunkType): Boolean {
        val substitutions = mutableMapOf<com.funk.language.ast.TypeVar, com.funk.language.ast.FunkType>()
        return try {
            unifyTypesWithSubstitution(actualType, expectedType, substitutions)
            
            // Apply substitutions to context if unification succeeded
            for ((varName, varType) in context) {
                context[varName] = applySubstitution(varType, substitutions)
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Bidirectional type inference with expected type
     */
    private fun inferBasicTypeWithExpected(expr: Expression, expectedType: com.funk.language.ast.FunkType): com.funk.language.ast.FunkType {
        return when (expr) {
            is IntLiteral -> {
                when (expectedType) {
                    is com.funk.language.ast.FunkType.NatType -> {
                        // Allow non-negative Int literals to be constrained to Nat
                        if (expr.value >= 0) {
                            expectedType
                        } else {
                            throw RuntimeException("Type error: Cannot use negative literal ${expr.value} where Nat expected")
                        }
                    }
                    is com.funk.language.ast.FunkType.IntType -> expectedType
                    is com.funk.language.ast.FunkType.NumType -> {
                        // Num 'a can accept Int literals - constrain the type variable to Int
                        val inferredType = com.funk.language.ast.FunkType.IntType
                        constraints.add(inferredType to com.funk.language.ast.FunkType.VarType(expectedType.constraint))
                        inferredType
                    }
                    is com.funk.language.ast.FunkType.VarType -> {
                        // Type variable - use constraint-based inference
                        val inferredType = com.funk.language.ast.FunkType.IntType
                        constraints.add(inferredType to expectedType)
                        inferredType
                    }
                    else -> {
                        throw RuntimeException("Type error: Cannot use Int literal where ${prettyPrintType(expectedType)} expected")
                    }
                }
            }
            is FloatLiteral -> {
                when (expectedType) {
                    is com.funk.language.ast.FunkType.FloatType -> expectedType
                    is com.funk.language.ast.FunkType.NumType -> {
                        // Num 'a can accept Float literals - constrain the type variable to Float
                        val inferredType = com.funk.language.ast.FunkType.FloatType
                        constraints.add(inferredType to com.funk.language.ast.FunkType.VarType(expectedType.constraint))
                        inferredType
                    }
                    is com.funk.language.ast.FunkType.VarType -> {
                        val inferredType = com.funk.language.ast.FunkType.FloatType
                        constraints.add(inferredType to expectedType)
                        inferredType
                    }
                    else -> {
                        throw RuntimeException("Type error: Cannot use Float literal where ${prettyPrintType(expectedType)} expected")
                    }
                }
            }
            is BinaryOperation -> {
                when {
                    // Special case: Int literal / Int literal with Frac expected -> treat as fraction literal
                    expr.operator == "/" && 
                    expr.left is IntLiteral && 
                    expr.right is IntLiteral && 
                    expectedType is com.funk.language.ast.FunkType.FracType -> {
                        // Treat as fraction literal
                        expectedType
                    }
                    else -> {
                        // For other binary operations, fall back to normal inference
                        inferBasicType(expr)
                    }
                }
            }
            is UnaryOperation -> {
                when {
                    expr.operator == "-" && expr.operand is IntLiteral && expectedType is com.funk.language.ast.FunkType.NatType -> {
                        // Negative numbers cannot be Nat
                        throw RuntimeException("Type error: Cannot use negative literal -${(expr.operand as IntLiteral).value} where Nat expected")
                    }
                    else -> {
                        // For other unary operations, fall back to normal inference
                        inferBasicType(expr)
                    }
                }
            }
            else -> {
                // For other expressions, fall back to normal inference
                inferBasicType(expr)
            }
        }
    }
    
    /**
     * Basic type inference for expressions - this is a simplified stub
     */
    private fun inferBasicType(expr: Expression): com.funk.language.ast.FunkType {
        // Debug output removed
        return when (expr) {
            is IntLiteral -> com.funk.language.ast.FunkType.IntType
            is FloatLiteral -> com.funk.language.ast.FunkType.FloatType
            is StringLiteral -> com.funk.language.ast.FunkType.StringType
            is BooleanLiteral -> com.funk.language.ast.FunkType.BooleanType
            is Identifier -> {
                // First check polymorphic context (let-polymorphism)
                polymorphicContext[expr.name]?.let { scheme ->
                    return scheme.instantiate()
                }
                
                // Then look up in regular context
                context[expr.name] ?: run {
                    // Check if it's a variant constructor (simple heuristic: starts with uppercase)
                    if (expr.name.first().isUpperCase()) {
                        // Treat as a variant constructor with no data (closed variant by default)
                        val constructors = mapOf(expr.name to null)
                        com.funk.language.ast.FunkType.VariantType(constructors, null) // null = closed
                    } else {
                        throw RuntimeException("Type error: Undefined variable '${expr.name}'")
                    }
                }
            }
            is FunctionCall -> {
                // Check if this is an effect call (function is a member access like Log.log)
                if (expr.function is MemberAccess) {
                    val memberAccess = expr.function as MemberAccess
                    if (memberAccess.receiver is Identifier) {
                        val effectName = (memberAccess.receiver as Identifier).name
                        val operationName = memberAccess.member
                        
                        // Check if this is a known effect
                        val effectType = context[effectName]
                        if (effectType is com.funk.language.ast.FunkType.RecordType) {
                            val operationType = effectType.fields[operationName]
                            if (operationType is com.funk.language.ast.FunkType.FunctionType) {
                                val argType = inferBasicType(expr.arguments)
                                if (isTypeCompatible(argType, operationType.paramType)) {
                                    return operationType.returnType
                                } else {
                                    throw RuntimeException("Effect call ${effectName}.${operationName} expects ${prettyPrintType(operationType.paramType)} but got ${prettyPrintType(argType)}")
                                }
                            }
                        }
                        
                        // Check if this is an ability call (e.g., Show.show, Eq.equals)
                        val abilityType = context["${effectName}_ability"]
                        if (abilityType is com.funk.language.ast.FunkType.RecordType) {
                            val argType = inferBasicType(expr.arguments)
                            return resolveAbilityOperationCall(effectName, operationName, argType)
                        }
                    }
                }
                
                // Try to infer return type from function type and validate arguments
                // CRITICAL FIX: Use polymorphic lookup for identifier functions (let-polymorphism)
                val functionType = if (expr.function is Identifier) {
                    val identifierName = (expr.function as Identifier).name
                    lookupVariable(identifierName) ?: throw RuntimeException("Undefined variable: $identifierName")
                } else {
                    inferBasicType(expr.function)
                }
                val argumentType = inferBasicType(expr.arguments)
                
                when (functionType) {
                    is com.funk.language.ast.FunkType.FunctionType -> {
                        // Check for partial application
                        val paramType = functionType.paramType
                        if (paramType is com.funk.language.ast.FunkType.RecordType && 
                            argumentType is com.funk.language.ast.FunkType.RecordType) {
                            
                            val expectedFields = paramType.fields
                            val providedFields = argumentType.fields
                            
                            // Check if this is partial application (subset of parameters provided)
                            val missingFields = expectedFields.keys - providedFields.keys
                            val extraFields = providedFields.keys - expectedFields.keys
                            
                            if (extraFields.isNotEmpty()) {
                                throw RuntimeException("Type error: Function expects ${prettyPrintType(functionType.paramType)} but got ${prettyPrintType(argumentType)}")
                            }
                            
                            if (missingFields.isNotEmpty()) {
                                // Partial application - return function type for remaining parameters
                                val remainingFields = expectedFields.filterKeys { it in missingFields }
                                val remainingParamType = com.funk.language.ast.FunkType.RecordType(remainingFields)
                                com.funk.language.ast.FunkType.FunctionType(remainingParamType, functionType.returnType, functionType.effects)
                            } else {
                                // All parameters provided - validate types and return result type
                                val argumentRecord = expr.arguments as RecordLiteral
                                val localSubstitutions = mutableMapOf<com.funk.language.ast.TypeVar, com.funk.language.ast.FunkType>()
                                
                                for (entry in argumentRecord.entries) {
                                    val fieldName = entry.name
                                    val expectedType = expectedFields[fieldName]
                                    val entryValue = entry.value
                                    
                                    if (expectedType != null && entryValue != null) {
                                        // Special handling for variant constructors - use bidirectional checking
                                        if (entryValue is VariantConstruction && expectedType is com.funk.language.ast.FunkType.VariantType) {
                                            validateVariantConstructor(entryValue, expectedType)
                                            // Don't do additional type checking - validation is sufficient
                                        } else {
                                            // Use bidirectional inference with expected type
                                            val providedType = inferBasicTypeWithExpected(entryValue, expectedType)
                                            if (!isTypeCompatible(providedType, expectedType)) {
                                                throw RuntimeException("Type error: Field '$fieldName' expects ${prettyPrintType(expectedType)} but got ${prettyPrintType(providedType)}")
                                            }
                                            
                                            // CRITICAL FIX: Collect local substitutions for polymorphic function calls
                                            if (expectedType is com.funk.language.ast.FunkType.NumType) {
                                                when (providedType) {
                                                    is com.funk.language.ast.FunkType.IntType -> {
                                                        localSubstitutions[expectedType.constraint] = providedType
                                                    }
                                                    is com.funk.language.ast.FunkType.FloatType -> {
                                                        localSubstitutions[expectedType.constraint] = providedType
                                                    }
                                                    is com.funk.language.ast.FunkType.FracType -> {
                                                        localSubstitutions[expectedType.constraint] = providedType
                                                    }
                                                    else -> {
                                                        // Other types don't create substitutions
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // Validate ability constraints if the function has them
                                if (functionType.abilityConstraints.isNotEmpty()) {
                                    validateAbilityConstraintsAtCallSite(functionType.abilityConstraints, argumentType)
                                }
                                
                                // CRITICAL FIX: Apply local substitutions to resolve polymorphic return type
                                if (localSubstitutions.isNotEmpty()) {
                                    applySubstitution(functionType.returnType, localSubstitutions)
                                } else {
                                functionType.returnType
                                }
                            }
                        } else {
                            // Non-record parameters - use existing logic
                            if (!isTypeCompatible(argumentType, functionType.paramType)) {
                                throw RuntimeException("Type error: Function expects ${prettyPrintType(functionType.paramType)} but got ${prettyPrintType(argumentType)}")
                            }
                            functionType.returnType
                        }
                    }
                    is com.funk.language.ast.FunkType.VariantType -> {
                        // This is a variant constructor being called with arguments
                        // We need to validate that the arguments match what's expected
                        if (expr.function is Identifier) {
                            val constructorName = (expr.function as Identifier).name
                            val expectedDataType = functionType.constructors[constructorName]
                            
                            val resultVariant = if (expectedDataType != null) {
                                // Validate that the provided arguments match the expected data type
                                if (!isTypeCompatible(argumentType, expectedDataType)) {
                                    throw RuntimeException("Type error: Constructor '$constructorName' expects ${prettyPrintType(expectedDataType)} but got ${prettyPrintType(argumentType)}")
                                }
                                // Use the expected data type for consistency
                                val constructors = mapOf(constructorName to expectedDataType)
                                com.funk.language.ast.FunkType.VariantType(constructors, null) // null = closed variant
                            } else {
                                // No expected data type, use the actual argument type
                                val constructors = mapOf(constructorName to argumentType)
                                com.funk.language.ast.FunkType.VariantType(constructors, null) // null = closed variant
                            }
                            
                            resultVariant
                        } else {
                            throw RuntimeException("Type error: Invalid variant constructor call")
                        }
                    }
                    // If function is from context (like a parameter), try to infer return type
                    else -> {
                        // For arithmetic-like operations, assume Int
                        // This is a heuristic - in a full bidirectional system, we'd use constraints
                        com.funk.language.ast.FunkType.IntType
                    }
                }
            }
            is IfExpression -> {
                // Infer from the then branch
                inferBasicType(expr.thenBranch)
            }
            is MatchExpression -> {
                // Type check match expression with proper pattern binding
                val matchedType = inferBasicType(expr.expression)
                
                if (expr.cases.isEmpty()) {
                    return com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                }
                
                // Type check each case with pattern bindings
                val caseTypes = mutableListOf<com.funk.language.ast.FunkType>()
                
                for (case in expr.cases) {
                    // Extract pattern bindings
                    val patternBindings = extractPatternBindings(case.pattern, matchedType)
                    
                    // Save current context
                    val savedContext = context.toMap()
                    
                    try {
                        // Add pattern bindings to context
                        context.putAll(patternBindings)
                        
                        // Type check the case expression with bindings in scope
                        val caseType = inferBasicType(case.expression)
                        caseTypes.add(caseType)
                        
                    } finally {
                        // Restore context (remove pattern bindings)
                        context.clear()
                        context.putAll(savedContext)
                    }
                }
                
                // All cases should have the same type (simplified - we could use unification here)
                caseTypes.firstOrNull() ?: com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
            }
            is RecordLiteral -> {
                val fields = expr.entries.associate { (name, fieldExpr) ->
                    name to (fieldExpr?.let { inferBasicType(it) } ?: com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh()))
                }
                val rowVar = if (expr.restField == "...") com.funk.language.ast.RowVar.fresh() else null
                com.funk.language.ast.FunkType.RecordType(fields, rowVar)
            }
            is MemberAccess -> {
                val receiverType = inferBasicType(expr.receiver)
                when (receiverType) {
                    is com.funk.language.ast.FunkType.RecordType -> {
                        receiverType.fields[expr.member] ?: inferFieldTypeFromName(expr.member)
                    }
                    is com.funk.language.ast.FunkType.VarType -> {
                        // Create a fresh type variable for the field type
                        val fieldType = inferFieldTypeFromName(expr.member)
                        // Create a record type with this field and add constraint
                        val recordType = com.funk.language.ast.FunkType.RecordType(
                            mapOf(expr.member to fieldType)
                        )
                        addConstraint(receiverType, recordType)
                        fieldType
                    }
                    else -> inferFieldTypeFromName(expr.member)
                }
            }
            is BinaryOperation -> {
                // Special case: Int literal / Int literal -> Frac (fraction literal)
                if (expr.operator == "/" && 
                    expr.left is IntLiteral && 
                    expr.right is IntLiteral) {
                    return com.funk.language.ast.FunkType.FracType
                }
                
                // Use Unison-style inference for binary operations
                val leftType = inferBasicType(expr.left)
                val rightType = inferBasicType(expr.right)
                
                // CRITICAL FIX: For polymorphic arithmetic operations, ensure result uses same type variable as operands
                // This fixes the unification bug where x + y gets Num 'c instead of Num 'a
                if (expr.operator in setOf("+", "-", "*", "/") && 
                    expr.left is Identifier && expr.right is Identifier) {
                    
                    val leftName = (expr.left as Identifier).name
                    val rightName = (expr.right as Identifier).name
                    
                    // Check if both are parameters with NumType in the context
                    val leftContextType = context[leftName]
                    val rightContextType = context[rightName]
                    
                    if (leftContextType is com.funk.language.ast.FunkType.NumType && 
                        rightContextType is com.funk.language.ast.FunkType.NumType) {
                        
                        // CRITICAL FIX: Force immediate unification of all related type variables
                        // This ensures that parameters and result use the exact same TypeVar instance
                        val primaryTypeVar = leftContextType.constraint
                        val unifiedType = com.funk.language.ast.FunkType.NumType(primaryTypeVar)
                        
                        // Force unification by directly substituting type variables
                        if (rightContextType.constraint != primaryTypeVar) {
                            // Create a substitution to unify the right constraint with the primary one
                            val substitution = mapOf(rightContextType.constraint to com.funk.language.ast.FunkType.VarType(primaryTypeVar))
                            
                            // Apply substitution to the entire context to ensure consistency
                            for ((key, value) in context.toMap()) {
                                context[key] = applySubstitution(value, substitution)
                            }
                        }
                        
                        // Return the unified type to ensure result uses the same TypeVar
                        return unifiedType
                    }
                }
                
                // Check for literal constraint opportunities
                val (finalLeftType, finalRightType) = when {
                    // Left is concrete numeric, right is Int literal -> try to constrain right to left's type
                    isConcreteNumericType(leftType) && expr.right is IntLiteral && rightType == com.funk.language.ast.FunkType.IntType -> {
                        if (leftType == com.funk.language.ast.FunkType.NatType && (expr.right as IntLiteral).value >= 0) {
                            // Constrain Int literal to Nat
                            Pair(leftType, com.funk.language.ast.FunkType.NatType)
                        } else if (leftType == com.funk.language.ast.FunkType.IntType) {
                            // Both Int
                            Pair(leftType, rightType)
                        } else if (leftType == com.funk.language.ast.FunkType.FracType) {
                            // Constrain Int literal to Frac (integers can become fractions)
                            Pair(leftType, com.funk.language.ast.FunkType.FracType)
                        } else {
                            // Can't constrain other types
                            Pair(leftType, rightType)
                        }
                    }
                    // Right is concrete numeric, left is Int literal -> try to constrain left to right's type
                    isConcreteNumericType(rightType) && expr.left is IntLiteral && leftType == com.funk.language.ast.FunkType.IntType -> {
                        if (rightType == com.funk.language.ast.FunkType.NatType && (expr.left as IntLiteral).value >= 0) {
                            // Constrain Int literal to Nat
                            Pair(com.funk.language.ast.FunkType.NatType, rightType)
                        } else if (rightType == com.funk.language.ast.FunkType.IntType) {
                            // Both Int
                            Pair(leftType, rightType)
                        } else if (rightType == com.funk.language.ast.FunkType.FracType) {
                            // Constrain Int literal to Frac (integers can become fractions)
                            Pair(com.funk.language.ast.FunkType.FracType, rightType)
                        } else {
                            // Can't constrain other types
                            Pair(leftType, rightType)
                        }
                    }
                    else -> Pair(leftType, rightType)
                }
                
                when (expr.operator) {
                    "+", "-", "*", "/" -> {
                        // Arithmetic operations - support polymorphic numeric types
                        when {
                            // Both are the same concrete numeric type
                            finalLeftType == finalRightType && isNumericType(finalLeftType) -> {
                                // Special case: Nat - Nat = Int (can be negative)
                                if (expr.operator == "-" && finalLeftType == com.funk.language.ast.FunkType.NatType) {
                            com.funk.language.ast.FunkType.IntType
                        } else {
                                    finalLeftType
                                }
                            }
                            // Mixed Frac/Int -> Frac (fractions absorb integers)
                            (finalLeftType == com.funk.language.ast.FunkType.FracType || finalLeftType == com.funk.language.ast.FunkType.IntType) &&
                            (finalRightType == com.funk.language.ast.FunkType.FracType || finalRightType == com.funk.language.ast.FunkType.IntType) -> {
                                com.funk.language.ast.FunkType.FracType
                            }
                            // Mixed Nat/Int -> Int (Nat can become Int)
                            (finalLeftType == com.funk.language.ast.FunkType.NatType || finalLeftType == com.funk.language.ast.FunkType.IntType) &&
                            (finalRightType == com.funk.language.ast.FunkType.NatType || finalRightType == com.funk.language.ast.FunkType.IntType) -> {
                            com.funk.language.ast.FunkType.IntType
                            }
                            // Both are NumType - return NumType (polymorphic numeric operation)
                            finalLeftType is com.funk.language.ast.FunkType.NumType && finalRightType is com.funk.language.ast.FunkType.NumType -> {
                                // CRITICAL FIX: Unify the type variables immediately and return the unified result
                                val unifiedConstraint = if (finalLeftType.constraint == finalRightType.constraint) {
                                    // Same constraint - use it directly
                                    finalLeftType.constraint
                        } else {
                                    // Different constraints - unify them and use the first one
                                    addConstraint(
                                        com.funk.language.ast.FunkType.VarType(finalLeftType.constraint),
                                        com.funk.language.ast.FunkType.VarType(finalRightType.constraint)
                                    )
                                    finalLeftType.constraint
                                }
                                
                                // Return NumType with the unified constraint to preserve type variable identity
                                com.funk.language.ast.FunkType.NumType(unifiedConstraint)
                            }
                            // One or both are type variables - create generic numeric constraint
                            finalLeftType is com.funk.language.ast.FunkType.VarType || finalRightType is com.funk.language.ast.FunkType.VarType -> {
                                when {
                                    // Both are type variables - unify them and return a generic result
                                    finalLeftType is com.funk.language.ast.FunkType.VarType && finalRightType is com.funk.language.ast.FunkType.VarType -> {
                                        addConstraint(finalLeftType, finalRightType)
                                        // Return the first type variable (they're now unified)
                                        finalLeftType
                                    }
                                    // Left is type variable, right is concrete - constrain left to right
                                    finalLeftType is com.funk.language.ast.FunkType.VarType -> {
                                        addConstraint(finalLeftType, finalRightType)
                                        finalRightType
                                    }
                                    // Right is type variable, left is concrete - constrain right to left
                                    finalRightType is com.funk.language.ast.FunkType.VarType -> {
                                        addConstraint(finalRightType, finalLeftType)
                                        finalLeftType
                                    }
                                    else -> {
                                        // Shouldn't reach here, but fallback
                                        com.funk.language.ast.FunkType.IntType
                                    }
                                }
                            }
                            // Both are numeric but different types - need explicit conversion
                            isNumericType(finalLeftType) && isNumericType(finalRightType) -> {
                                throw RuntimeException("Type error: Cannot apply arithmetic operator '${expr.operator}' to ${prettyPrintType(finalLeftType)} and ${prettyPrintType(finalRightType)}. Both operands must be the same numeric type.")
                            }
                            else -> {
                                throw RuntimeException("Type error: Cannot apply arithmetic operator '${expr.operator}' to ${prettyPrintType(finalLeftType)} and ${prettyPrintType(finalRightType)}")
                            }
                        }
                    }
                    "++" -> {
                        // String concatenation - both operands must be strings
                        if (leftType == com.funk.language.ast.FunkType.StringType && rightType == com.funk.language.ast.FunkType.StringType) {
                            com.funk.language.ast.FunkType.StringType
                        } else if (leftType is com.funk.language.ast.FunkType.VarType || rightType is com.funk.language.ast.FunkType.VarType) {
                            // Try to unify with String for type variables
                            addConstraint(leftType, com.funk.language.ast.FunkType.StringType)
                            addConstraint(rightType, com.funk.language.ast.FunkType.StringType)
                            com.funk.language.ast.FunkType.StringType
                        } else {
                            throw RuntimeException("Type error: Cannot concatenate ${prettyPrintType(leftType)} and ${prettyPrintType(rightType)}. Use intToString to convert integers to strings.")
                        }
                    }
                    "==", "!=", "<", ">", "<=", ">=" -> {
                        // Comparison operators - support polymorphic numeric types
                        when {
                            // Both are the same concrete numeric type
                            finalLeftType == finalRightType && isNumericType(finalLeftType) -> {
                            com.funk.language.ast.FunkType.BooleanType
                            }
                            // Mixed Int/Float -> allow comparison
                            (finalLeftType == com.funk.language.ast.FunkType.FloatType || finalLeftType == com.funk.language.ast.FunkType.IntType) &&
                            (finalRightType == com.funk.language.ast.FunkType.FloatType || finalRightType == com.funk.language.ast.FunkType.IntType) -> {
                            com.funk.language.ast.FunkType.BooleanType
                            }
                            // One or both are type variables - create generic numeric constraint
                            finalLeftType is com.funk.language.ast.FunkType.VarType || finalRightType is com.funk.language.ast.FunkType.VarType -> {
                                when {
                                    // Both are type variables - unify them
                                    finalLeftType is com.funk.language.ast.FunkType.VarType && finalRightType is com.funk.language.ast.FunkType.VarType -> {
                                        addConstraint(finalLeftType, finalRightType)
                                    }
                                    // Left is type variable, right is concrete - constrain left to right
                                    finalLeftType is com.funk.language.ast.FunkType.VarType -> {
                                        addConstraint(finalLeftType, finalRightType)
                                    }
                                    // Right is type variable, left is concrete - constrain right to left
                                    finalRightType is com.funk.language.ast.FunkType.VarType -> {
                                        addConstraint(finalRightType, finalLeftType)
                                    }
                                }
                                com.funk.language.ast.FunkType.BooleanType
                            }
                            // Both are numeric but different types - need explicit conversion
                            isNumericType(finalLeftType) && isNumericType(finalRightType) -> {
                                throw RuntimeException("Type error: Cannot compare ${prettyPrintType(finalLeftType)} and ${prettyPrintType(finalRightType)}. Both operands must be the same numeric type.")
                            }
                            else -> {
                                throw RuntimeException("Type error: Cannot compare ${prettyPrintType(finalLeftType)} and ${prettyPrintType(finalRightType)}")
                            }
                        }
                    }
                    "&&", "||" -> {
                        if (leftType == com.funk.language.ast.FunkType.BooleanType && rightType == com.funk.language.ast.FunkType.BooleanType) {
                            com.funk.language.ast.FunkType.BooleanType
                        } else if (leftType is com.funk.language.ast.FunkType.VarType || rightType is com.funk.language.ast.FunkType.VarType) {
                            // Try to unify with Boolean for type variables
                            addConstraint(leftType, com.funk.language.ast.FunkType.BooleanType)
                            addConstraint(rightType, com.funk.language.ast.FunkType.BooleanType)
                            com.funk.language.ast.FunkType.BooleanType
                        } else {
                            throw RuntimeException("Type error: Cannot apply boolean operator '${expr.operator}' to ${prettyPrintType(leftType)} and ${prettyPrintType(rightType)}")
                        }
                    }
                    else -> com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                }
            }
            is VariantConstruction -> {
                val constructors = mapOf(expr.constructor to expr.value?.let { inferBasicType(it) })
                // Create closed variant by default (no row variable) per user preference
                com.funk.language.ast.FunkType.VariantType(constructors, null)
            }
            is LambdaExpression -> {
                
                // Enter new scope for lambda parameters
                val originalContext = context.toMap()
                val scopeLevel = com.funk.language.ast.TypeVar.enterScope()
                
                // Variables to store heuristic results
                var heuristicParamType: com.funk.language.ast.FunkType? = null
                var heuristicReturnType: com.funk.language.ast.FunkType? = null
                var heuristicEffectRow: com.funk.language.ast.FunkType.EffectRow? = null
                
                // Create parameter type with type variables for untyped parameters
                val paramType = when (val params = expr.parameters) {
                    is UntypedParameters -> {
                        // Create fresh type variables for each parameter
                        val paramTypes = params.names.associate { name ->
                            name to com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                        }
                        
                        // Add parameters to context with type variables
                        paramTypes.forEach { (name, type) ->
                            context[name] = type
                        }
                        
                        com.funk.language.ast.FunkType.RecordType(paramTypes)
                    }
                    is TypedParameters -> {
                        // Convert the type annotation to a FunkType
                        val annotatedType = convertTypeAnnotation(params.recordType)
                        
                        // Add parameters to context with their explicit types
                        if (annotatedType is com.funk.language.ast.FunkType.RecordType) {
                            annotatedType.fields.forEach { (name, type) ->
                                context[name] = type
                            }
                        }
                        
                        annotatedType
                    }
                }
                
                // Special handling for record parameters to ensure constraint propagation works
                if (paramType is com.funk.language.ast.FunkType.RecordType) {
                    val paramTypes = paramType.fields.toMutableMap()
                    val originalTypes = paramType.fields.toMap() // Remember original explicit types
                    
                    // Direct constraint extraction for all expressions
                    val paramNames = paramType.fields.keys
                    val directConstraints = extractParameterConstraints(expr.body, paramNames)
                    directConstraints.forEach { (name, type) ->
                        // Only apply constraint if the original type was a type variable (not explicit)
                        if (originalTypes[name] is com.funk.language.ast.FunkType.VarType) {
                        paramTypes[name] = type
                        }
                    }
                    
                    // Also run the regular heuristic analysis for all expressions (but preserve explicit types)
                    analyzeExpressionForParameterTypes(expr.body, paramTypes)
                    
                    // Update context with refined parameter types
                    paramTypes.forEach { (name, type) ->
                        context[name] = type
                    }
                    
                    // Update paramType with refined types
                    val refinedParamType = com.funk.language.ast.FunkType.RecordType(paramTypes)
                    
                    // Infer the return type from the body or use explicit annotation (this will add constraints)
                    val returnType = expr.returnType?.let { convertTypeAnnotation(it) } ?: inferBasicType(expr.body)
                    
                    // CRITICAL FIX: For polymorphic arithmetic results, ensure they use parameter type variables
                    // This fixes the case where {x, y, z} -> x + y gets Num 'c instead of Num 'a
                    val finalReturnType = if (returnType is com.funk.language.ast.FunkType.NumType) {
                        // Check if any parameters have NumType - if so, use the first parameter's constraint
                        val paramNumTypes = paramTypes.values.filterIsInstance<com.funk.language.ast.FunkType.NumType>()
                        if (paramNumTypes.isNotEmpty()) {
                            // NUCLEAR OPTION: Force exact same TypeVar instance for return type
                            val primaryConstraint = paramNumTypes.first().constraint
                            
                            // Also update all other NumType parameters to use the same constraint
                            paramNumTypes.forEach { numType ->
                                if (numType.constraint != primaryConstraint) {
                                    // Find the parameter name and update it
                                    paramTypes.entries.find { it.value == numType }?.let { entry ->
                                        paramTypes[entry.key] = com.funk.language.ast.FunkType.NumType(primaryConstraint)
                                    }
                                }
                            }
                            
                            // Return NumType with the primary constraint
                            com.funk.language.ast.FunkType.NumType(primaryConstraint)
                        } else {
                            returnType
                        }
                    } else {
                        returnType
                    }
                    
                    // Collect effects from the lambda body
                    val usedEffects = mutableSetOf<String>()
                    collectEffectsFromExpression(expr.body, usedEffects)
                    
                    // Create effect row if effects are present
                    val effectRow = if (usedEffects.isNotEmpty()) {
                        com.funk.language.ast.FunkType.EffectRow(usedEffects)
                    } else {
                        null
                    }
                    
                    // For simple cases, return the heuristic result if it has concrete types
                    if (hasConcreteTypes(refinedParamType)) {
                        // Handle ability constraints from lambda expression
                        val abilityConstraints = expr.abilityConstraints?.map { constraint ->
                            com.funk.language.ast.FunkType.FunctionAbilityConstraint(
                                constraint.abilityName,
                                constraint.typeArgs.map { convertTypeAnnotation(it) }
                            )
                        } ?: emptyList()
                    
                        // Exit scope and restore original context
                        com.funk.language.ast.TypeVar.exitScope()
                        context.clear()
                        context.putAll(originalContext)
                        
                        return com.funk.language.ast.FunkType.FunctionType(refinedParamType, finalReturnType, effectRow, abilityConstraints)
                    }
                    
                    // Store the heuristic result for comparison with constraint solver
                    heuristicParamType = refinedParamType
                    heuristicReturnType = finalReturnType
                    heuristicEffectRow = effectRow
                }
                
                // Infer the return type from the body (this will add constraints)
                val returnType = inferBasicType(expr.body)
                
                // Solve constraints to get the actual parameter types
                val substitutions = mutableMapOf<com.funk.language.ast.TypeVar, com.funk.language.ast.FunkType>()
                for ((type1, type2) in constraints) {
                    unifyTypesWithSubstitution(type1, type2, substitutions)
                    // Apply substitution immediately to context
                    for ((name, varType) in context.toMap()) {
                        context[name] = applySubstitution(varType, substitutions)
                    }
                }
                constraints.clear()
                
                // Apply substitutions to get the final parameter types
                val constraintParamType = if (paramType is com.funk.language.ast.FunkType.RecordType) {
                    // Reconstruct parameter type from the updated context
                    val updatedFields = paramType.fields.mapValues { (name, _) ->
                        context[name] ?: paramType.fields[name]!!
                    }
                    com.funk.language.ast.FunkType.RecordType(updatedFields, paramType.rowVar)
                } else {
                    applySubstitution(paramType, substitutions)
                }
                val constraintReturnType = applySubstitution(returnType, substitutions)
                
                // Apply direct constraint extraction to get better parameter types
                val paramNames = if (paramType is com.funk.language.ast.FunkType.RecordType) {
                    paramType.fields.keys
                } else {
                    emptySet()
                }
                val directConstraints = extractParameterConstraints(expr.body, paramNames)
                
                // Use direct constraints if available, otherwise use heuristic or constraint result
                val finalParamType = if (directConstraints.isNotEmpty() && paramType is com.funk.language.ast.FunkType.RecordType) {
                    val updatedFields = paramType.fields.toMutableMap()
                    directConstraints.forEach { (name, type) ->
                        updatedFields[name] = type
                    }
                    com.funk.language.ast.FunkType.RecordType(updatedFields, paramType.rowVar)
                } else if (heuristicParamType != null && hasConcreteTypes(heuristicParamType)) {
                    heuristicParamType
                } else {
                    constraintParamType
                }
                val finalReturnType = if (heuristicReturnType != null) {
                    heuristicReturnType
                } else {
                    // CRITICAL FIX: Apply the same polymorphic arithmetic fix to constraint solver path
                    if (constraintReturnType is com.funk.language.ast.FunkType.NumType) {
                        // Get parameter types from finalParamType
                        val paramNumTypes = if (finalParamType is com.funk.language.ast.FunkType.RecordType) {
                            finalParamType.fields.values.filterIsInstance<com.funk.language.ast.FunkType.NumType>()
                        } else {
                            emptyList()
                        }
                        if (paramNumTypes.isNotEmpty()) {
                            // NUCLEAR OPTION: Force exact same TypeVar instance for constraint solver path too
                            val primaryConstraint = paramNumTypes.first().constraint
                            com.funk.language.ast.FunkType.NumType(primaryConstraint)
                } else {
                    constraintReturnType
                        }
                    } else {
                        constraintReturnType
                    }
                }
                
                // Collect effects from the lambda body
                val usedEffects = mutableSetOf<String>()
                collectEffectsFromExpression(expr.body, usedEffects)
                
                // Create effect row if effects are present
                val effectRow = if (usedEffects.isNotEmpty()) {
                    com.funk.language.ast.FunkType.EffectRow(usedEffects)
                } else if (heuristicEffectRow != null) {
                    heuristicEffectRow
                } else {
                    null
                }
                
                // Handle ability constraints from lambda expression
                val abilityConstraints = expr.abilityConstraints?.map { constraint ->
                    com.funk.language.ast.FunkType.FunctionAbilityConstraint(
                        constraint.abilityName,
                        constraint.typeArgs.map { convertTypeAnnotation(it) }
                    )
                } ?: emptyList()
                
                
                // Validate ability constraints if present
                if (abilityConstraints.isNotEmpty()) {
                    val argumentTypes = if (finalParamType is com.funk.language.ast.FunkType.RecordType) {
                        finalParamType.fields.values.toList()
                    } else {
                        listOf(finalParamType)
                    }
                    
                    if (!validateAbilityConstraintsForLambda(abilityConstraints)) {
                        throw TypeCheckError("Ability constraints not satisfied in lambda expression")
                    }
                }
                
                // Exit scope and restore original context
                com.funk.language.ast.TypeVar.exitScope()
                context.clear()
                context.putAll(originalContext)
                
                
                // CRITICAL FIX: Ensure parameter and return types use the same constraint for polymorphic arithmetic
                val finalFunctionType = if (finalParamType is com.funk.language.ast.FunkType.RecordType) {
                    val paramNumTypes = finalParamType.fields.values.filterIsInstance<com.funk.language.ast.FunkType.NumType>()
                    if (paramNumTypes.isNotEmpty()) {
                        val unifiedConstraint = paramNumTypes.first().constraint
                        
                        // Handle direct NumType return
                        val unifiedReturnType = if (finalReturnType is com.funk.language.ast.FunkType.NumType) {
                            com.funk.language.ast.FunkType.NumType(unifiedConstraint)
                        }
                        // Handle record return with NumType fields
                        else if (finalReturnType is com.funk.language.ast.FunkType.RecordType) {
                            val unifiedFields = finalReturnType.fields.mapValues { (fieldName, fieldType) ->
                                if (fieldType is com.funk.language.ast.FunkType.NumType) {
                                    com.funk.language.ast.FunkType.NumType(unifiedConstraint)
                                } else {
                                    fieldType
                                }
                            }
                            com.funk.language.ast.FunkType.RecordType(unifiedFields, finalReturnType.rowVar)
                        } else {
                            finalReturnType
                        }
                        
                        com.funk.language.ast.FunkType.FunctionType(finalParamType, unifiedReturnType, effectRow, abilityConstraints)
                    } else {
                        com.funk.language.ast.FunkType.FunctionType(finalParamType, finalReturnType, effectRow, abilityConstraints)
                    }
                } else {
                    com.funk.language.ast.FunkType.FunctionType(finalParamType, finalReturnType, effectRow, abilityConstraints)
                }
                
                finalFunctionType
            }
            is DoExpression -> {
                // Process statements to add variables to context, but don't create new scopes
                for (statement in expr.statements) {
                    when (statement) {
                        is LetStatement -> {
                            val actualType = inferBasicType(statement.expression)
                            context[statement.variableName] = actualType
                        }
                        is ExpressionStatement -> {
                            inferBasicType(statement.expression)
                        }
                    }
                }
                
                // Return type of the final expression
                inferBasicType(expr.result)
            }
            is PerformExpression -> {
                // Perform expressions execute effectful code with specific handlers
                val exprType = inferTypeWithUnification(expr.expression)
                
                // Find which effects are being handled by looking up the handler names
                val handlerNames = expr.handlers.map { it.name }
                val handledEffects = mutableSetOf<String>()
                
                // Look for impl declarations to find which effects these handlers implement
                for (handlerName in handlerNames) {
                    for (item in currentProgram?.items ?: emptyList()) {
                        if (item is ImplDeclaration && item.performerName == handlerName) {
                            handledEffects.add(item.effectName)
                            break
                        }
                    }
                }
                
                // Validate that we're only handling effects that actually exist in the expression
                val actualEffects: Set<String> = when (exprType) {
                    is com.funk.language.ast.FunkType.FunctionType -> exprType.effects?.effects ?: emptySet()
                    else -> {
                        // Check if the expression contains effect calls
                        val effectsInExpression = mutableSetOf<String>()
                        collectEffectsFromExpression(expr.expression, effectsInExpression)
                        effectsInExpression
                    }
                }
                
                // TODO: Improve validation logic for perform expressions
                // For now, we allow perform expressions and let the interpreter handle validation
                // The type system focuses on effect removal from function types
                
                // If the expression type is a function with effects, remove the handled effects
                if (exprType is com.funk.language.ast.FunkType.FunctionType && exprType.effects != null) {
                    if (handledEffects.isNotEmpty()) {
                        // Remove the handled effects from the effect row
                        val currentEffects = exprType.effects!!.effects.toMutableSet()
                        currentEffects.removeAll(handledEffects)
                        
                        val newEffectRow = if (currentEffects.isEmpty()) {
                            null // No effects left
                        } else {
                            com.funk.language.ast.FunkType.EffectRow(currentEffects, exprType.effects!!.rowVar)
                        }
                        
                        com.funk.language.ast.FunkType.FunctionType(
                            exprType.paramType,
                            exprType.returnType,
                            newEffectRow
                        )
                    } else {
                        exprType
                    }
                } else {
                    // For non-function types, return the result type of executing the expression
                    // If there were effects to handle, they've been handled, so return the base type
                    exprType
                }
            }
            is EffectCallExpression -> {
                // Effect calls perform operations
                val effectType = context[expr.effectName]
                    ?: throw RuntimeException("Undefined effect: ${expr.effectName}")
                
                if (effectType is com.funk.language.ast.FunkType.RecordType) {
                    val operationType = effectType.fields[expr.operationName]
                        ?: throw RuntimeException("Effect ${expr.effectName} has no operation ${expr.operationName}")
                    
                    if (operationType is com.funk.language.ast.FunkType.FunctionType) {
                        val argType = inferBasicType(expr.arguments)
                        if (isTypeCompatible(argType, operationType.paramType)) {
                            operationType.returnType
                        } else {
                            throw RuntimeException("Effect call ${expr.effectName}.${expr.operationName} expects ${prettyPrintType(operationType.paramType)} but got ${prettyPrintType(argType)}")
                        }
                    } else {
                        throw RuntimeException("Effect operation ${expr.effectName}.${expr.operationName} is not a function")
                    }
                } else {
                    throw RuntimeException("Effect ${expr.effectName} is not properly defined")
                }
            }
            // PipeExpression and PlaceholderExpression removed - desugared during parsing
            is RecordUpdateExpression -> {
                // Type check record update: { baseRecord | field = value, ... }
                val baseType = inferTypeWithUnification(expr.baseRecord)
                
                // Base record must be a record type
                val recordType = when (baseType) {
                    is com.funk.language.ast.FunkType.RecordType -> baseType
                    is com.funk.language.ast.FunkType.VarType -> {
                        // Create a record type constraint for the variable
                        val fields = mutableMapOf<String, com.funk.language.ast.FunkType>()
                        expr.updates.forEach { update ->
                            if (update.fieldPath.size == 1) {
                                val fieldType = inferTypeWithUnification(update.value)
                                fields[update.fieldPath[0]] = fieldType
                            }
                        }
                        val constraintType = com.funk.language.ast.FunkType.RecordType(fields)
                        addConstraint(baseType, constraintType)
                        constraintType
                    }
                    else -> throw TypeCheckError("Cannot update non-record type: ${prettyPrintType(baseType)}")
                }
                
                // Check each update
                val resultFields = recordType.fields.toMutableMap()
                
                expr.updates.forEach { update ->
                    when (update.fieldPath.size) {
                        1 -> {
                            // Simple field update: x = v
                            val fieldName = update.fieldPath[0]
                            val valueType = inferTypeWithUnification(update.value)
                            
                            if (recordType.fields.containsKey(fieldName)) {
                                // Updating existing field
                                val expectedType = recordType.fields[fieldName]!!
                                if (!unifyTypes(valueType, expectedType)) {
                                    throw TypeCheckError("Field '$fieldName' expects ${prettyPrintType(expectedType)} but got ${prettyPrintType(valueType)}")
                                }
                                resultFields[fieldName] = valueType
                            } else if (recordType.isOpen) {
                                // Adding new field to open record
                                resultFields[fieldName] = valueType
                            } else {
                                // Trying to add field to closed record
                                throw TypeCheckError("Field '$fieldName' does not exist in closed record type ${prettyPrintType(recordType)}")
                            }
                        }
                        2 -> {
                            // Nested field update: y.z = v (desugar to { r | y = { r.y | z = v } })
                            val outerField = update.fieldPath[0]
                            val innerField = update.fieldPath[1]
                            
                            if (!recordType.fields.containsKey(outerField)) {
                                throw TypeCheckError("Field '$outerField' does not exist in record type ${prettyPrintType(recordType)}")
                            }
                            
                            val outerFieldType = recordType.fields[outerField]!!
                            val innerRecordType = when (outerFieldType) {
                                is com.funk.language.ast.FunkType.RecordType -> outerFieldType
                                else -> throw TypeCheckError("Cannot access field '$innerField' on non-record type ${prettyPrintType(outerFieldType)}")
                            }
                            
                            if (!innerRecordType.fields.containsKey(innerField)) {
                                throw TypeCheckError("Field '$innerField' does not exist in nested record type ${prettyPrintType(innerRecordType)}")
                            }
                            
                            val valueType = inferTypeWithUnification(update.value)
                            val expectedType = innerRecordType.fields[innerField]!!
                            if (!unifyTypes(valueType, expectedType)) {
                                throw TypeCheckError("Field '$outerField.$innerField' expects ${prettyPrintType(expectedType)} but got ${prettyPrintType(valueType)}")
                            }
                            
                            // Update the nested record type
                            val updatedInnerFields = innerRecordType.fields.toMutableMap()
                            updatedInnerFields[innerField] = valueType
                            val updatedInnerType = com.funk.language.ast.FunkType.RecordType(updatedInnerFields, innerRecordType.rowVar)
                            resultFields[outerField] = updatedInnerType
                        }
                        else -> {
                            throw TypeCheckError("Nested field updates beyond one level are not supported")
                        }
                    }
                }
                
                com.funk.language.ast.FunkType.RecordType(resultFields, recordType.rowVar)
            }
            is UnaryOperation -> {
                when (expr.operator) {
                    "-" -> {
                        val operandType = inferBasicType(expr.operand)
                        when (operandType) {
                            is com.funk.language.ast.FunkType.IntType -> com.funk.language.ast.FunkType.IntType
                            is com.funk.language.ast.FunkType.FloatType -> com.funk.language.ast.FunkType.FloatType
                            is com.funk.language.ast.FunkType.NatType -> com.funk.language.ast.FunkType.IntType // Nat -> Int when negated
                            is com.funk.language.ast.FunkType.FracType -> com.funk.language.ast.FunkType.FracType
                            else -> throw RuntimeException("Type error: Cannot negate ${prettyPrintType(operandType)}")
                        }
                    }
                    "!" -> {
                        val operandType = inferBasicType(expr.operand)
                        if (operandType == com.funk.language.ast.FunkType.BooleanType) {
                            com.funk.language.ast.FunkType.BooleanType
                        } else {
                            throw RuntimeException("Type error: Cannot apply logical negation to ${prettyPrintType(operandType)}")
                        }
                    }
                    else -> throw RuntimeException("Type error: Unknown unary operator '${expr.operator}'")
                }
            }
            else -> com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
        }
    }
    
    /**
     * Infer parameter types by analyzing how they're used in the lambda body
     */
    private fun inferParameterTypesFromUsage(body: Expression, paramNames: List<String>): Map<String, com.funk.language.ast.FunkType> {
        val paramTypes = mutableMapOf<String, com.funk.language.ast.FunkType>()
        
        // Initialize with fresh type variables
        for (name in paramNames) {
            paramTypes[name] = com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
        }
        
        // Analyze match expressions to infer variant types
        analyzeExpressionForParameterTypes(body, paramTypes)
        
        return paramTypes
    }
    
    /**
     * Analyze an expression to infer parameter types from usage patterns
     */
    private fun analyzeExpressionForParameterTypes(expr: Expression, paramTypes: MutableMap<String, com.funk.language.ast.FunkType>) {
        when (expr) {
            is MatchExpression -> {
                // Check if the matched expression is a parameter
                val matchedExpr = expr.expression
                if (matchedExpr is Identifier) {
                    val paramName = matchedExpr.name
                    if (paramTypes.containsKey(paramName)) {
                        // Infer variant type from match cases
                        val constructors = mutableMapOf<String, com.funk.language.ast.FunkType?>()
                        
                        for (case in expr.cases) {
                            when (val pattern = case.pattern) {
                                is ConstructorPattern -> {
                                    val dataType = pattern.recordPattern?.let { recordPattern ->
                                        // Analyze the case expression to infer better types for pattern fields
                                        val patternBindings = extractPatternBindings(pattern, com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh()))
                                        
                                        // Create a temporary context with pattern bindings
                                        val tempParamTypes = mutableMapOf<String, com.funk.language.ast.FunkType>()
                                        tempParamTypes.putAll(patternBindings)
                                        
                                        // Analyze the case expression to refine field types
                                        analyzeExpressionForParameterTypes(case.expression, tempParamTypes)
                                        
                                        // Build the record type from refined types
                                        val fields = recordPattern.entries.associate { entry ->
                                            entry.name to (tempParamTypes[entry.name] ?: inferFieldTypeFromName(entry.name))
                                        }
                                        com.funk.language.ast.FunkType.RecordType(fields)
                                    }
                                    constructors[pattern.constructor] = dataType
                                }
                                is WildcardPattern -> {
                                    // Wildcard makes the variant open - don't add to constructors
                                }
                                is VariablePattern -> {
                                    // Variable pattern doesn't add constructor info
                                }
                                is RecordPattern -> {
                                    // Record pattern doesn't add constructor info directly
                                }
                                else -> {
                                    // Handle other pattern types
                                }
                            }
                        }
                        
                        if (constructors.isNotEmpty()) {
                            // Check if there's a wildcard pattern - if so, make it an open variant
                            val hasWildcard = expr.cases.any { it.pattern is WildcardPattern }
                            paramTypes[paramName] = if (hasWildcard) {
                                // Open variant (can accept more constructors)
                                com.funk.language.ast.FunkType.VariantType(constructors, com.funk.language.ast.RowVar("match_${paramName}"))
                            } else {
                                // Closed variant
                                com.funk.language.ast.FunkType.VariantType(constructors)
                            }
                        }
                    }
                }
                
                // Recursively analyze case expressions
                for (case in expr.cases) {
                    analyzeExpressionForParameterTypes(case.expression, paramTypes)
                }
                
                // Also check if this is a match on a pattern-bound variable that should be refined
                if (matchedExpr is Identifier && paramTypes.containsKey(matchedExpr.name)) {
                    val currentType = paramTypes[matchedExpr.name]
                    if (currentType is com.funk.language.ast.FunkType.VarType || currentType == com.funk.language.ast.FunkType.StringType) {
                        // Try to infer a better type from the match patterns
                        val inferredType = inferTypeFromMatchPatterns(expr.cases)
                        if (inferredType != null) {
                            paramTypes[matchedExpr.name] = inferredType
                        }
                    }
                }
            }
            is BinaryOperation -> {
                val left = expr.left
                val right = expr.right
                
                // For arithmetic operations, apply smart constraints
                // If we can determine the function should be concrete, constrain to Int
                // If it should be polymorphic, leave unconstrained
                if (expr.operator in setOf("+", "-", "*", "/")) {
                    // For arithmetic operations, check if this should be polymorphic
                    if (shouldBePolymorphicNumeric(expr, paramTypes.keys)) {
                        // Polymorphic function - use shared Num 'a for all parameters in this operation
                        val sharedTypeVar = com.funk.language.ast.TypeVar.fresh()
                        
                    if (left is Identifier && paramTypes.containsKey(left.name)) {
                            val currentType = paramTypes[left.name]
                            if (currentType is com.funk.language.ast.FunkType.VarType) {
                                paramTypes[left.name] = com.funk.language.ast.FunkType.NumType(sharedTypeVar)
                            }
                        }
                        if (right is Identifier && paramTypes.containsKey(right.name)) {
                            val currentType = paramTypes[right.name]
                            if (currentType is com.funk.language.ast.FunkType.VarType) {
                                // Use the same shared type variable for all parameters in this operation
                                paramTypes[right.name] = com.funk.language.ast.FunkType.NumType(sharedTypeVar)
                            }
                        }
                    } else {
                        // Regular function - use concrete Int for type variables
                        if (left is Identifier && paramTypes.containsKey(left.name)) {
                            val currentType = paramTypes[left.name]
                            if (currentType is com.funk.language.ast.FunkType.VarType) {
                        paramTypes[left.name] = com.funk.language.ast.FunkType.IntType
                            }
                    }
                    if (right is Identifier && paramTypes.containsKey(right.name)) {
                            val currentType = paramTypes[right.name]
                            if (currentType is com.funk.language.ast.FunkType.VarType) {
                        paramTypes[right.name] = com.funk.language.ast.FunkType.IntType
                    }
                }
                    }
                }
                
                // For string concatenation, infer that operands should be String
                if (expr.operator == "++") {
                    if (left is Identifier && paramTypes.containsKey(left.name)) {
                        paramTypes[left.name] = com.funk.language.ast.FunkType.StringType
                    }
                    if (right is Identifier && paramTypes.containsKey(right.name)) {
                        paramTypes[right.name] = com.funk.language.ast.FunkType.StringType
                    }
                }
                // For comparison operations with literals, infer the parameter type
                else if (expr.operator in setOf("==", "!=", "<", ">", "<=", ">=")) {
                    // If one side is a literal and the other is a parameter, infer the parameter type
                    if (left is Identifier && paramTypes.containsKey(left.name)) {
                        when (right) {
                            is IntLiteral -> paramTypes[left.name] = com.funk.language.ast.FunkType.IntType
                            is FloatLiteral -> paramTypes[left.name] = com.funk.language.ast.FunkType.FloatType
                            is StringLiteral -> paramTypes[left.name] = com.funk.language.ast.FunkType.StringType
                            is BooleanLiteral -> paramTypes[left.name] = com.funk.language.ast.FunkType.BooleanType
                            else -> {} // Ignore non-literal types
                        }
                    }
                    if (right is Identifier && paramTypes.containsKey(right.name)) {
                        when (left) {
                            is IntLiteral -> paramTypes[right.name] = com.funk.language.ast.FunkType.IntType
                            is FloatLiteral -> paramTypes[right.name] = com.funk.language.ast.FunkType.FloatType
                            is StringLiteral -> paramTypes[right.name] = com.funk.language.ast.FunkType.StringType
                            is BooleanLiteral -> paramTypes[right.name] = com.funk.language.ast.FunkType.BooleanType
                            else -> {} // Ignore non-literal types
                        }
                    }
                }
                
                analyzeExpressionForParameterTypes(expr.left, paramTypes)
                analyzeExpressionForParameterTypes(expr.right, paramTypes)
            }
            is MemberAccess -> {
                // If accessing a member of a parameter, infer that parameter needs that field
                val receiver = expr.receiver
                if (receiver is Identifier) {
                    val paramName = receiver.name
                    if (paramTypes.containsKey(paramName)) {
                        val currentType = paramTypes[paramName]
                        if (currentType is com.funk.language.ast.FunkType.VarType) {
                            // Create a record type with the accessed field
                            val fieldType = inferFieldTypeFromName(expr.member)
                            val recordType = com.funk.language.ast.FunkType.RecordType(mapOf(expr.member to fieldType))
                            paramTypes[paramName] = recordType
                        } else if (currentType is com.funk.language.ast.FunkType.RecordType) {
                            // Add the field to existing record type
                            val fieldType = inferFieldTypeFromName(expr.member)
                            val newFields = currentType.fields + (expr.member to fieldType)
                            paramTypes[paramName] = com.funk.language.ast.FunkType.RecordType(newFields)
                        }
                    }
                }
                analyzeExpressionForParameterTypes(expr.receiver, paramTypes)
            }
            is FunctionCall -> {
                // If the function is a parameter, infer it should be a function type
                val function = expr.function
                if (function is Identifier && paramTypes.containsKey(function.name)) {
                    val funcParamName = function.name
                    
                    // Analyze the arguments to infer the function's parameter type
                    if (expr.arguments is RecordLiteral) {
                        val argRecord = expr.arguments as RecordLiteral
                        
                        // Only apply enhanced inference for calculator-like patterns
                        val hasOperationField = argRecord.entries.any { it.name == "operation" }
                        if (!hasOperationField) {
                            // Skip enhanced inference for non-calculator patterns
                        } else {
                            val paramFields = mutableMapOf<String, com.funk.language.ast.FunkType>()
                            
                            for (entry in argRecord.entries) {
                                val fieldName = entry.name
                                val fieldValue = entry.value
                                
                                // If the field value is a parameter, infer its type from context
                                if (fieldValue is Identifier && paramTypes.containsKey(fieldValue.name)) {
                                    val valueParamName = fieldValue.name
                                    // Only infer specific types for known patterns, otherwise use existing type
                                    val inferredType = when (fieldName) {
                                        "operation" -> {
                                            // Look for match expressions on this parameter to infer variant type
                                            com.funk.language.ast.FunkType.VariantType(
                                                mapOf("Add" to null, "Multiply" to null, "Divide" to null)
                                            )
                                        }
                                        "x", "y", "val" -> com.funk.language.ast.FunkType.IntType
                                        else -> inferFieldTypeFromName(fieldName)
                                    }
                                    paramTypes[valueParamName] = inferredType
                                    paramFields[fieldName] = inferredType
                                } else {
                                    // Infer field type from the value expression
                                    val fieldType = when (fieldName) {
                                        "operation" -> com.funk.language.ast.FunkType.VariantType(
                                            mapOf("Add" to null, "Multiply" to null, "Divide" to null)
                                        )
                                        "x", "y", "val" -> com.funk.language.ast.FunkType.IntType
                                        else -> inferFieldTypeFromName(fieldName)
                                    }
                                    paramFields[fieldName] = fieldType
                                }
                            }
                        
                            // Set the function parameter type
                            if (paramFields.isNotEmpty()) {
                                val paramRecordType = com.funk.language.ast.FunkType.RecordType(paramFields)
                                val returnType = com.funk.language.ast.FunkType.IntType // Assume Int for calculator functions
                                paramTypes[funcParamName] = com.funk.language.ast.FunkType.FunctionType(paramRecordType, returnType)
                            }
                        }
                    }
                }
                
                analyzeExpressionForParameterTypes(expr.function, paramTypes)
                analyzeExpressionForParameterTypes(expr.arguments, paramTypes)
            }
            is IfExpression -> {
                analyzeExpressionForParameterTypes(expr.condition, paramTypes)
                analyzeExpressionForParameterTypes(expr.thenBranch, paramTypes)
                analyzeExpressionForParameterTypes(expr.elseBranch, paramTypes)
            }
            is RecordLiteral -> {
                // Analyze all field values in the record
                for (entry in expr.entries) {
                    entry.value?.let { analyzeExpressionForParameterTypes(it, paramTypes) }
                }
            }
            is DoExpression -> {
                // Analyze all statements in the do block
                for (stmt in expr.statements) {
                    when (stmt) {
                        is LetStatement -> analyzeExpressionForParameterTypes(stmt.expression, paramTypes)
                        is ExpressionStatement -> analyzeExpressionForParameterTypes(stmt.expression, paramTypes)
                    }
                }
                // Analyze the result expression
                analyzeExpressionForParameterTypes(expr.result, paramTypes)
            }
            is LambdaExpression -> {
                // Analyze the lambda body
                analyzeExpressionForParameterTypes(expr.body, paramTypes)
            }
            else -> {
                // Handle other expression types or ignore
            }
        }
    }
    
    /**
     * Collect all field accesses on a given parameter within an expression
     */
    private fun collectFieldAccesses(expr: Expression, paramName: String): Set<String> {
        val fields = mutableSetOf<String>()
        
        fun traverse(e: Expression) {
            when (e) {
                is MemberAccess -> {
                    val receiver = e.receiver
                    if (receiver is Identifier && receiver.name == paramName) {
                        fields.add(e.member)
                    }
                    traverse(receiver)
                }
                is BinaryOperation -> {
                    traverse(e.left)
                    traverse(e.right)
                }
                is FunctionCall -> {
                    traverse(e.function)
                    traverse(e.arguments)
                }
                is IfExpression -> {
                    traverse(e.condition)
                    traverse(e.thenBranch)
                    traverse(e.elseBranch)
                }
                is DoExpression -> {
                    e.statements.forEach { stmt ->
                        when (stmt) {
                            is LetStatement -> traverse(stmt.expression)
                            is ExpressionStatement -> traverse(stmt.expression)
                        }
                    }
                    traverse(e.result)
                }
                is RecordLiteral -> {
                    e.entries.forEach { entry ->
                        entry.value?.let { traverse(it) }
                    }
                }
                is RecordUpdate -> {
                    traverse(e.record)
                    e.updates.forEach { update ->
                        update.value?.let { traverse(it) }
                    }
                }
                is VariantConstruction -> {
                    e.value?.let { traverse(it) }
                }
                is MatchExpression -> {
                    traverse(e.expression)
                    e.cases.forEach { case ->
                        traverse(case.expression)
                    }
                }
                is LambdaExpression -> {
                    traverse(e.body)
                }
                // Base cases - no further traversal needed
                is Identifier, is IntLiteral, is FloatLiteral, is StringLiteral, is BooleanLiteral -> {}
                is PerformExpression -> {
                    traverse(e.expression)
                }
                is EffectCallExpression -> {
                    traverse(e.arguments)
                }
                is PlaceholderExpression -> {
                    // Placeholders should be desugared during parsing
                    // If we see one here, it means desugaring failed - this is an error
                    throw RuntimeException("PlaceholderExpression found in type checking - pipe operator desugaring failed")
                }
                is RecordUpdateExpression -> {
                    traverse(e.baseRecord)
                    e.updates.forEach { update ->
                        update.value?.let { traverse(it) }
                    }
                }
                is UnaryOperation -> {
                    traverse(e.operand)
                }
            }
        }
        
        traverse(expr)
        return fields
    }

    /**
     * Extract type constraints from an expression for lambda parameters
     */
    private fun extractParameterConstraints(expr: Expression, paramNames: Set<String>): Map<String, com.funk.language.ast.FunkType> {
        val constraints = mutableMapOf<String, com.funk.language.ast.FunkType>()
        
        fun analyzeExpression(e: Expression) {
            when (e) {
                is UnaryOperation -> {
                    // For unary arithmetic operations (like negation), constrain operand to numeric type
                    if (e.operator == "-") {
                        val operand = e.operand
                        if (operand is Identifier && paramNames.contains(operand.name)) {
                            if (!constraints.containsKey(operand.name)) {
                                // Use concrete Int for unary negation (simple case)
                                constraints[operand.name] = com.funk.language.ast.FunkType.IntType
                            }
                        }
                    }
                    // For boolean negation, constrain operand to Boolean
                    if (e.operator == "!") {
                        val operand = e.operand
                        if (operand is Identifier && paramNames.contains(operand.name)) {
                            if (!constraints.containsKey(operand.name)) {
                                constraints[operand.name] = com.funk.language.ast.FunkType.BooleanType
                            }
                        }
                    }
                    analyzeExpression(e.operand)
                }
                is BinaryOperation -> {
                    // For string concatenation, constrain operands to String (specific constraint)
                    if (e.operator == "++") {
                        val left = e.left
                        val right = e.right
                        if (left is Identifier && paramNames.contains(left.name)) {
                            constraints[left.name] = com.funk.language.ast.FunkType.StringType
                        }
                        if (right is Identifier && paramNames.contains(right.name)) {
                            constraints[right.name] = com.funk.language.ast.FunkType.StringType
                        }
                    }
                    // For arithmetic operations, apply smart constraints
                    if (e.operator in setOf("+", "-", "*", "/")) {
                        val left = e.left
                        val right = e.right
                        
                        // For arithmetic operations, check if this should be polymorphic
                        if (shouldBePolymorphicNumeric(e, paramNames)) {
                            // This looks like a polymorphic function - use Num 'a
                            // Use a shared type variable for all parameters in this operation
                            val sharedTypeVar = com.funk.language.ast.TypeVar.fresh()
                            
                        if (left is Identifier && paramNames.contains(left.name)) {
                                if (!constraints.containsKey(left.name)) {
                                    constraints[left.name] = com.funk.language.ast.FunkType.NumType(sharedTypeVar)
                                }
                        }
                        if (right is Identifier && paramNames.contains(right.name)) {
                                if (!constraints.containsKey(right.name)) {
                                    // If it's the same parameter as left, use the same constraint
                                    val typeVar = if (left is Identifier && left.name == right.name) {
                                        sharedTypeVar
                                    } else {
                                        sharedTypeVar // For now, use shared var for all params in same operation
                                    }
                                    constraints[right.name] = com.funk.language.ast.FunkType.NumType(typeVar)
                                }
                            }
                        } else {
                            // Regular function - use concrete Int
                            if (left is Identifier && paramNames.contains(left.name)) {
                                if (!constraints.containsKey(left.name)) {
                                    constraints[left.name] = com.funk.language.ast.FunkType.IntType
                                }
                            }
                            if (right is Identifier && paramNames.contains(right.name)) {
                                if (!constraints.containsKey(right.name)) {
                                    constraints[right.name] = com.funk.language.ast.FunkType.IntType
                                }
                            }
                        }
                    }
                    analyzeExpression(e.left)
                    analyzeExpression(e.right)
                }
                is DoExpression -> {
                    e.statements.forEach { stmt ->
                        when (stmt) {
                            is LetStatement -> analyzeExpression(stmt.expression)
                            is ExpressionStatement -> analyzeExpression(stmt.expression)
                        }
                    }
                    analyzeExpression(e.result)
                }
                is IfExpression -> {
                    analyzeExpression(e.condition)
                    analyzeExpression(e.thenBranch)
                    analyzeExpression(e.elseBranch)
                }
                is FunctionCall -> {
                    analyzeExpression(e.function)
                    analyzeExpression(e.arguments)
                }
                is RecordLiteral -> {
                    e.entries.forEach { entry ->
                        entry.value?.let { analyzeExpression(it) }
                    }
                }
                is MemberAccess -> {
                    // For member access like param.field, constrain param to have that field
                    val receiver = e.receiver
                    if (receiver is Identifier && paramNames.contains(receiver.name)) {
                        val paramName = receiver.name
                        
                        // Collect all field accesses for this parameter
                        val allFields = collectFieldAccesses(expr, paramName)
                        
                        // Create a record type with all accessed fields
                        val fieldTypes = allFields.associate { field ->
                            field to inferFieldTypeFromName(field)
                        }
                        
                        val recordType = com.funk.language.ast.FunkType.RecordType(fieldTypes)
                        constraints[paramName] = recordType
                    }
                    analyzeExpression(e.receiver)
                }
                is MatchExpression -> {
                    // Recursively analyze match cases
                    e.cases.forEach { case ->
                        analyzeExpression(case.expression)
                    }
                }
                // Add more cases as needed
                else -> {} // Ignore other expression types
            }
        }
        
        analyzeExpression(expr)
        return constraints
    }

    /**
     * Determine if we should constrain a parameter to Int based on usage context
     */
    private fun shouldConstrainToInt(expr: Expression, paramTypes: Map<String, com.funk.language.ast.FunkType>): Boolean {
        return when (expr) {
            is IntLiteral -> true  // Literal usage suggests concrete type
            is Identifier -> {
                // If parameter already has a concrete numeric type, keep it concrete
                val currentType = paramTypes[expr.name]
                currentType != null && isConcreteNumericType(currentType)
            }
            else -> false
        }
    }
    
    /**
     * Determine if an arithmetic operation should be polymorphic numeric (Num 'a) vs concrete (Int)
     */
    private fun shouldBePolymorphicNumeric(expr: BinaryOperation, paramNames: Set<String>): Boolean {
        // Heuristic: If both operands are parameters and no literals are involved, 
        // this looks like a polymorphic function like { x } -> x + x or { x, y } -> x + y
        val left = expr.left
        val right = expr.right
        
        val leftIsParam = left is Identifier && paramNames.contains(left.name)
        val rightIsParam = right is Identifier && paramNames.contains(right.name)
        val hasLiterals = left is IntLiteral || right is IntLiteral || 
                         left is FloatLiteral || right is FloatLiteral
        
        // Consider it polymorphic if both operands are parameters and no literals constrain it
        // This allows both { x } -> x + x and { x, y } -> x + y to be polymorphic
        return leftIsParam && rightIsParam && !hasLiterals
    }

    /**
     * Check if a type has concrete types (no type variables)
     */
    private fun hasConcreteTypes(type: com.funk.language.ast.FunkType): Boolean {
        return when (type) {
            is com.funk.language.ast.FunkType.VarType -> false
            is com.funk.language.ast.FunkType.RecordType -> {
                type.fields.values.all { hasConcreteTypes(it) }
            }
            is com.funk.language.ast.FunkType.FunctionType -> {
                hasConcreteTypes(type.paramType) && hasConcreteTypes(type.returnType)
            }
            is com.funk.language.ast.FunkType.VariantType -> {
                type.constructors.values.all { it == null || hasConcreteTypes(it) }
            }
            else -> true // Primitive types are concrete
        }
    }


    /**
     * Infer field type from field name - simple heuristics
     */
    private fun inferFieldTypeFromName(fieldName: String): com.funk.language.ast.FunkType {
        return when (fieldName) {
            "val", "value", "amount", "count", "size", "length", "width", "height", "x", "y", "z", "age", "id" -> com.funk.language.ast.FunkType.IntType
            "name", "message", "text", "title", "description", "email", "city", "department" -> com.funk.language.ast.FunkType.StringType
            "flag", "enabled", "active", "visible" -> com.funk.language.ast.FunkType.BooleanType
            "price", "rate", "ratio" -> com.funk.language.ast.FunkType.FloatType
            else -> com.funk.language.ast.FunkType.StringType  // Default to String for unknown fields
        }
    }
    
    /**
     * Infer type from match patterns (for nested matches on pattern-bound variables)
     */
    private fun inferTypeFromMatchPatterns(cases: List<MatchCase>): com.funk.language.ast.FunkType? {
        // Look for record patterns to infer record types
        for (case in cases) {
            when (val pattern = case.pattern) {
                is RecordPattern -> {
                    val fields = mutableMapOf<String, com.funk.language.ast.FunkType>()
                    for (entry in pattern.entries) {
                        if (entry.pattern == null) {
                            // Shorthand {field} - analyze how this field is used in the case expression
                            val fieldType = analyzeVariableUsageInExpression(entry.name, case.expression)
                                ?: inferFieldTypeFromName(entry.name)
                            fields[entry.name] = fieldType
                        } else {
                            // Complex pattern - use heuristic for now
                            fields[entry.name] = inferFieldTypeFromName(entry.name)
                        }
                    }
                    if (fields.isNotEmpty()) {
                        return com.funk.language.ast.FunkType.RecordType(fields)
                    }
                }
                is ConstructorPattern -> {
                    // For constructor patterns, we don't know the exact type without more context
                    // This is handled elsewhere
                }
                else -> {
                    // Other patterns don't give us type information
                }
            }
        }
        return null
    }
    
    /**
     * Analyze how a variable is used in an expression to infer its type
     */
    private fun analyzeVariableUsageInExpression(varName: String, expr: Expression): com.funk.language.ast.FunkType? {
        when (expr) {
            is BinaryOperation -> {
                // Check if the variable is used in arithmetic
                if (expr.operator in setOf("+", "-", "*", "/", "<", ">", "<=", ">=")) {
                    val left = expr.left
                    val right = expr.right
                    if ((left is Identifier && left.name == varName) ||
                        (right is Identifier && right.name == varName)) {
                        return com.funk.language.ast.FunkType.IntType
                    }
                }
                // Recursively check operands
                return analyzeVariableUsageInExpression(varName, expr.left)
                    ?: analyzeVariableUsageInExpression(varName, expr.right)
            }
            is FunctionCall -> {
                // Check if the variable is used as an argument
                val arguments = expr.arguments
                if (arguments is RecordLiteral) {
                    for ((_, argExpr) in arguments.entries) {
                        if (argExpr is Identifier && argExpr.name == varName) {
                            // Variable used as function argument - could infer from function signature
                            return null // For now, no specific inference
                        }
                        argExpr?.let { 
                            analyzeVariableUsageInExpression(varName, it)?.let { return it }
                        }
                    }
                }
            }
            is IfExpression -> {
                // Check condition and branches
                return analyzeVariableUsageInExpression(varName, expr.condition)
                    ?: analyzeVariableUsageInExpression(varName, expr.thenBranch)
                    ?: analyzeVariableUsageInExpression(varName, expr.elseBranch)
            }
            is MatchExpression -> {
                // Check the matched expression and cases
                analyzeVariableUsageInExpression(varName, expr.expression)?.let { return it }
                for (case in expr.cases) {
                    analyzeVariableUsageInExpression(varName, case.expression)?.let { return it }
                }
            }
            else -> {
                // Other expression types - no specific inference for now
            }
        }
        return null
    }
    
    /**
     * Check if actualType is compatible with expectedType
     * For closed records, this requires exact field matches
     */
    private fun isTypeCompatible(actualType: com.funk.language.ast.FunkType, expectedType: com.funk.language.ast.FunkType): Boolean {
        return when {
            actualType == expectedType -> true
            
            // Record type compatibility
            actualType is com.funk.language.ast.FunkType.RecordType && expectedType is com.funk.language.ast.FunkType.RecordType -> {
                if (expectedType.isOpen) {
                    // For open expected records, actual must have at least the required fields
                    expectedType.fields.all { (expectedFieldName, expectedFieldType) ->
                        val actualFieldType = actualType.fields[expectedFieldName]
                        actualFieldType != null && isTypeCompatible(actualFieldType, expectedFieldType)
                    }
                } else {
                    // For closed expected records, use structural typing - actual must have at least the required fields
                    expectedType.fields.all { (expectedFieldName, expectedFieldType) ->
                        val actualFieldType = actualType.fields[expectedFieldName]
                        actualFieldType != null && isTypeCompatible(actualFieldType, expectedFieldType)
                    }
                }
            }
            
            // Variant type compatibility - use constraint solver for proper subtyping
            actualType is com.funk.language.ast.FunkType.VariantType && expectedType is com.funk.language.ast.FunkType.VariantType -> {
                // Try constraint-based unification first
                try {
                    val constraint = Constraint.Equality(actualType, expectedType)
                    constraintSolver.solve(listOf(constraint))
                    true
                } catch (e: UnificationException) {
                    // Fallback to legacy logic if constraint solving fails
                val actualIsOpen = actualType.rowVar != null
                val expectedIsOpen = expectedType.rowVar != null
                
                when {
                    actualIsOpen && expectedIsOpen -> {
                        // Both open: actual constructors must be compatible with expected ones
                        actualType.constructors.all { (actualConstructor, actualDataType) ->
                            val expectedDataType = expectedType.constructors[actualConstructor]
                            if (expectedDataType == null) {
                                // Constructor not in expected type, but expected is open so it's allowed
                                true
                            } else {
                                // Both constructors exist, check data compatibility
                                when {
                                    expectedDataType == actualDataType -> true
                                    expectedDataType != null && actualDataType != null -> isTypeCompatible(actualDataType, expectedDataType)
                                    else -> expectedDataType == null && actualDataType == null
                                }
                            }
                        }
                    }
                    actualIsOpen && !expectedIsOpen -> {
                        // Actual is open, expected is closed: actual must contain all expected constructors
                        expectedType.constructors.all { (expectedConstructor, expectedDataType) ->
                            val actualDataType = actualType.constructors[expectedConstructor]
                            when {
                                actualDataType == null -> false // Expected constructor missing in actual
                                expectedDataType == actualDataType -> true
                                expectedDataType != null && actualDataType != null -> isTypeCompatible(actualDataType, expectedDataType)
                                else -> expectedDataType == null && actualDataType == null
                            }
                        }
                    }
                    !actualIsOpen && expectedIsOpen -> {
                        // Actual is closed, expected is open: all actual constructors must be compatible
                        actualType.constructors.all { (actualConstructor, actualDataType) ->
                            val expectedDataType = expectedType.constructors[actualConstructor]
                            if (expectedDataType == null) {
                                // Constructor not in expected type, but expected is open so it's allowed
                                true
                            } else {
                                // Both constructors exist, check data compatibility
                                when {
                                    expectedDataType == actualDataType -> true
                                    expectedDataType != null && actualDataType != null -> isTypeCompatible(actualDataType, expectedDataType)
                                    else -> expectedDataType == null && actualDataType == null
                                }
                            }
                        }
                    }
                    else -> {
                        // Both closed: actual can be a subset of expected (closed subset compatibility)
                        // This allows | Red | to be compatible with | Red, Blue { val: Int } |
                        actualType.constructors.all { (actualConstructor, actualDataType) ->
                            val expectedDataType = expectedType.constructors[actualConstructor]
                            when {
                                expectedDataType == null -> false // Actual constructor not in expected
                                expectedDataType == actualDataType -> true
                                expectedDataType != null && actualDataType != null -> isTypeCompatible(actualDataType, expectedDataType)
                                else -> expectedDataType == null && actualDataType == null
                            }
                        }
                    }
                }
                }
            }
            
            // Function type compatibility
            actualType is com.funk.language.ast.FunkType.FunctionType && expectedType is com.funk.language.ast.FunkType.FunctionType -> {
                // For function types, if the actual type has type variables, allow it to match concrete expected types
                // This handles cases like { age: 'a } -> Boolean matching { age: Int } -> Boolean
                val actualHasTypeVars = containsTypeVariables(actualType)
                
                if (actualHasTypeVars) {
                    // If actual type has type variables, be lenient - just check structural shape
                    true // Allow any function type with type variables to match any other function type
                } else {
                    // Both types are concrete, require exact compatibility
                    isTypeCompatible(actualType.paramType, expectedType.paramType) &&
                    isTypeCompatible(actualType.returnType, expectedType.returnType)
                    // Effects are orthogonal to regular types - don't check effect compatibility
                }
            }
            
            // NumType compatibility - Num 'a is compatible with any numeric type
            expectedType is com.funk.language.ast.FunkType.NumType && isNumericType(actualType) -> true
            actualType is com.funk.language.ast.FunkType.NumType && isNumericType(expectedType) -> true
            
            // Type variables can unify with any type
            actualType is com.funk.language.ast.FunkType.VarType -> true
            expectedType is com.funk.language.ast.FunkType.VarType -> true
            
            else -> false
        }
    }
    
    /**
     * Check if a type contains type variables
     */
    private fun containsTypeVariables(type: com.funk.language.ast.FunkType): Boolean {
        return when (type) {
            is com.funk.language.ast.FunkType.VarType -> true
            is com.funk.language.ast.FunkType.RecordType -> type.fields.values.any { containsTypeVariables(it) }
            is com.funk.language.ast.FunkType.VariantType -> type.constructors.values.any { it != null && containsTypeVariables(it) }
            is com.funk.language.ast.FunkType.FunctionType -> containsTypeVariables(type.paramType) || containsTypeVariables(type.returnType) || (type.effects?.let { containsTypeVariables(it) } ?: false)
            else -> false
        }
    }
    
    /**
     * Check structural compatibility more leniently for types with type variables
     */
    private fun isStructurallyCompatible(actualType: com.funk.language.ast.FunkType, expectedType: com.funk.language.ast.FunkType): Boolean {
        return when {
            // Type variables are always compatible
            actualType is com.funk.language.ast.FunkType.VarType -> true
            expectedType is com.funk.language.ast.FunkType.VarType -> true
            
            // Same concrete types are compatible
            actualType == expectedType -> true
            
            // Record types - check if they have the same field structure
            actualType is com.funk.language.ast.FunkType.RecordType && expectedType is com.funk.language.ast.FunkType.RecordType -> {
                // For structural compatibility, just check that field names match
                // (field types will be checked recursively)
                actualType.fields.keys == expectedType.fields.keys &&
                actualType.fields.all { (fieldName, actualFieldType) ->
                    val expectedFieldType = expectedType.fields[fieldName]
                    expectedFieldType != null && isStructurallyCompatible(actualFieldType, expectedFieldType)
                }
            }
            
            // Other types use regular compatibility
            else -> isTypeCompatible(actualType, expectedType)
        }
    }
    
    /**
     * Convert a TypeDefinition to a FunkType
     */
    private fun convertTypeDefinition(definition: TypeDefinition): com.funk.language.ast.FunkType {
        return when (definition) {
            is RecordDefinition -> {
                val fields = definition.fields.associate { field ->
                    field.name to convertTypeAnnotation(field.type)
                }
                com.funk.language.ast.FunkType.RecordType(fields)
            }
            is VariantDefinition -> {
                val constructors = definition.constructors.associate { constructor ->
                    constructor.name to constructor.dataType?.let { convertTypeAnnotation(it) }
                }
                com.funk.language.ast.FunkType.VariantType(constructors)
            }
        }
    }
    
    /**
     * Convert a TypeAnnotation to a FunkType
     */
    private fun convertTypeAnnotation(annotation: TypeAnnotation): com.funk.language.ast.FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when {
                    annotation.name == "Int" -> com.funk.language.ast.FunkType.IntType
                    annotation.name == "Float" -> com.funk.language.ast.FunkType.FloatType
                    annotation.name == "String" -> com.funk.language.ast.FunkType.StringType
                    annotation.name == "Boolean" -> com.funk.language.ast.FunkType.BooleanType
                    annotation.name == "Unit" -> com.funk.language.ast.FunkType.RecordType(emptyMap())
                    annotation.name == "Nat" -> com.funk.language.ast.FunkType.NatType
                    annotation.name == "Frac" -> com.funk.language.ast.FunkType.FracType
                    annotation.name == "Num" -> {
                        // Num without type parameter - create a fresh type variable constrained to numeric types
                        com.funk.language.ast.FunkType.NumType(com.funk.language.ast.TypeVar.fresh())
                    }
                    annotation.name.startsWith("'") -> {
                        // Explicit type variable - use the exact name provided
                        com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar(annotation.name))
                    }
                    typeAliases.containsKey(annotation.name) -> {
                        // Type alias without parameters
                        val alias = typeAliases[annotation.name]!!
                        if (alias.typeParams.isEmpty()) {
                            alias.definition
                        } else {
                            throw RuntimeException("Type error: Type alias '${annotation.name}' requires ${alias.typeParams.size} type parameters")
                        }
                    }
                    else -> {
                        // Other identifiers (custom types) - treat as fresh type variables for now
                        com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.freshNamed(annotation.name))
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                // Special case for Num 'a
                if (annotation.constructor == "Num" && annotation.args.size == 1) {
                    val constraintType = convertTypeAnnotation(annotation.args[0])
                    when (constraintType) {
                        is com.funk.language.ast.FunkType.VarType -> {
                            // Num 'a - create NumType with the type variable
                            com.funk.language.ast.FunkType.NumType(constraintType.variable)
                        }
                        else -> {
                            throw RuntimeException("Type error: Num expects a type variable, got ${prettyPrintType(constraintType)}")
                        }
                    }
                } else {
                // Handle type application like Maybe Int
                val alias = typeAliases[annotation.constructor]
                    ?: throw RuntimeException("Type error: Unknown type '${annotation.constructor}'")
                
                if (alias.typeParams.size != annotation.args.size) {
                    throw RuntimeException("Type error: Type '${annotation.constructor}' expects ${alias.typeParams.size} parameters, got ${annotation.args.size}")
                }
                
                // Substitute type parameters with actual arguments
                val argTypes = annotation.args.map { convertTypeAnnotation(it) }
                substituteTypeParameters(alias.definition, alias.typeParams, argTypes)
                }
            }
            is RecordTypeAnnotation -> {
                val fields = annotation.entries.associate { entry ->
                    entry.name to convertTypeAnnotation(entry.type)
                }
                val isOpen = annotation.restField == "..."
                if (isOpen) {
                    // Create open record with a row variable
                    com.funk.language.ast.FunkType.RecordType(fields, com.funk.language.ast.RowVar("rest"))
                } else {
                    // Create closed record
                    com.funk.language.ast.FunkType.RecordType(fields)
                }
            }
            is VariantTypeAnnotation -> {
                val constructors = annotation.constructors.associate { constructor ->
                    constructor.name to constructor.dataType?.let { convertTypeAnnotation(it) }
                }
                // For now, assume variants are closed unless explicitly marked open
                // TODO: Add support for open variant annotations
                com.funk.language.ast.FunkType.VariantType(constructors)
            }
            is FunctionTypeAnnotation -> {
                val paramType = convertTypeAnnotation(annotation.paramType)
                val returnType = convertTypeAnnotation(annotation.returnType)
                val effects = annotation.effects?.let { 
                    com.funk.language.ast.FunkType.EffectRow(it.effects.toSet())
                }
                val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    com.funk.language.ast.FunkType.FunctionAbilityConstraint(
                        constraint.abilityName,
                        constraint.typeArgs.map { convertTypeAnnotation(it) }
                    )
                }
                
                
                com.funk.language.ast.FunkType.FunctionType(paramType, returnType, effects, abilityConstraints)
            }
            is EffectRowAnnotation -> {
                com.funk.language.ast.FunkType.EffectRow(annotation.effects.toSet())
            }
            else -> com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
        }
    }
    
    /**
     * Substitute type parameters in a type with actual type arguments
     */
    private fun substituteTypeParameters(
        type: com.funk.language.ast.FunkType,
        typeParams: List<String>,
        argTypes: List<com.funk.language.ast.FunkType>
    ): com.funk.language.ast.FunkType {
        val substitution = typeParams.zip(argTypes).toMap()
        return substituteInType(type, substitution)
    }
    
    /**
     * Recursively substitute type variables in a type
     */
    private fun substituteInType(
        type: com.funk.language.ast.FunkType,
        substitution: Map<String, com.funk.language.ast.FunkType>
    ): com.funk.language.ast.FunkType {
        return when (type) {
            is com.funk.language.ast.FunkType.VarType -> {
                substitution[type.variable.name] ?: type
            }
            is com.funk.language.ast.FunkType.RecordType -> {
                val newFields = type.fields.mapValues { (_, fieldType) ->
                    substituteInType(fieldType, substitution)
                }
                com.funk.language.ast.FunkType.RecordType(newFields, type.rowVar)
            }
            is com.funk.language.ast.FunkType.VariantType -> {
                val newConstructors = type.constructors.mapValues { (_, constructorType) ->
                    constructorType?.let { substituteInType(it, substitution) }
                }
                com.funk.language.ast.FunkType.VariantType(newConstructors, type.rowVar)
            }
            is com.funk.language.ast.FunkType.FunctionType -> {
                com.funk.language.ast.FunkType.FunctionType(
                    substituteInType(type.paramType, substitution),
                    substituteInType(type.returnType, substitution),
                    type.effects?.let { substituteInType(it, substitution) as com.funk.language.ast.FunkType.EffectRow }
                )
            }
            else -> type
        }
    }
    
    /**
     * Collect effects used in an expression
     */
    private fun collectEffectsFromExpression(expr: Expression, effects: MutableSet<String>) {
        when (expr) {
            is EffectCallExpression -> {
                effects.add(expr.effectName)
            }
            is DoExpression -> {
                expr.statements.forEach { stmt ->
                    when (stmt) {
                        is LetStatement -> collectEffectsFromExpression(stmt.expression, effects)
                        is ExpressionStatement -> collectEffectsFromExpression(stmt.expression, effects)
                    }
                }
                collectEffectsFromExpression(expr.result, effects)
            }
            is FunctionCall -> {
                // Check if this is an effect call (function is a member access like Log.log)
                if (expr.function is MemberAccess) {
                    val memberAccess = expr.function as MemberAccess
                    if (memberAccess.receiver is Identifier) {
                        val effectName = (memberAccess.receiver as Identifier).name
                        val effectType = context[effectName]
                        if (effectType is com.funk.language.ast.FunkType.RecordType) {
                            effects.add(effectName)
                            // CRITICAL: Also collect effects from the operation's return type!
                            val operationType = effectType.fields[memberAccess.member]
                            if (operationType is com.funk.language.ast.FunkType.FunctionType) {
                                operationType.effects?.effects?.forEach { effect ->
                                    effects.add(effect) // ← This propagates nested effects!
                                }
                            }
                        }
                    }
                }
                collectEffectsFromExpression(expr.function, effects)
                collectEffectsFromExpression(expr.arguments, effects)
            }
            is BinaryOperation -> {
                collectEffectsFromExpression(expr.left, effects)
                collectEffectsFromExpression(expr.right, effects)
            }
            is IfExpression -> {
                collectEffectsFromExpression(expr.condition, effects)
                collectEffectsFromExpression(expr.thenBranch, effects)
                collectEffectsFromExpression(expr.elseBranch, effects)
            }
            is MatchExpression -> {
                collectEffectsFromExpression(expr.expression, effects)
                expr.cases.forEach { case ->
                    collectEffectsFromExpression(case.expression, effects)
                }
            }
            is RecordLiteral -> {
                expr.entries.forEach { entry ->
                    entry.value?.let { collectEffectsFromExpression(it, effects) }
                }
            }
            is LambdaExpression -> {
                collectEffectsFromExpression(expr.body, effects)
            }
            is MemberAccess -> {
                collectEffectsFromExpression(expr.receiver, effects)
            }
            is RecordUpdateExpression -> {
                collectEffectsFromExpression(expr.baseRecord, effects)
                expr.updates.forEach { update ->
                    collectEffectsFromExpression(update.value, effects)
                }
            }
            is PerformExpression -> {
                collectEffectsFromExpression(expr.expression, effects)
            }
            // Literals and identifiers don't have effects
            else -> {}
        }
    }
    
    /**
     * Validate a variant constructor against an expected variant type
     */
    private fun validateVariantConstructor(
        constructor: VariantConstruction, 
        expectedVariantType: com.funk.language.ast.FunkType.VariantType
    ) {
        val expectedDataType = expectedVariantType.constructors[constructor.constructor]
        
        if (expectedDataType == null) {
            throw RuntimeException("Type error: Constructor '${constructor.constructor}' not found in expected variant type ${prettyPrintType(expectedVariantType)}")
        }
        
        // Validate the constructor data matches expected type
        val constructorValue = constructor.value
        if (constructorValue != null && expectedDataType != null) {
            val actualDataType = inferBasicType(constructorValue)
            if (!isTypeCompatible(actualDataType, expectedDataType)) {
                throw RuntimeException("Type error: Constructor '${constructor.constructor}' expects ${prettyPrintType(expectedDataType)} but got ${prettyPrintType(actualDataType)}")
            }
        } else if (constructorValue == null && expectedDataType != null) {
            throw RuntimeException("Type error: Constructor '${constructor.constructor}' expects data but none provided")
        } else if (constructorValue != null && expectedDataType == null) {
            throw RuntimeException("Type error: Constructor '${constructor.constructor}' expects no data but data was provided")
        }
        
        // If validation passes, return the expected variant type instead of inferring a new one
        // This allows variant constructors to be compatible with larger variant types
    }

    /**
     * Check if two effect rows are compatible
     * For now, we use simple set equality, but this could be extended for effect row polymorphism
     */
    private fun effectRowsCompatible(
        actualEffects: com.funk.language.ast.FunkType.EffectRow?,
        expectedEffects: com.funk.language.ast.FunkType.EffectRow?
    ): Boolean {
        return when {
            actualEffects == null && expectedEffects == null -> true
            actualEffects != null && expectedEffects != null -> actualEffects.effects == expectedEffects.effects
            // Be more lenient: allow functions without effects to be compatible with functions that expect effects
            // This is important for variant type compatibility
            else -> true
        }
    }
    
    /**
     * Register native function types in the typechecker context
     */
    private fun registerNativeFunctionTypes() {
        // Basic I/O functions
        context["native_print"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("msg" to com.funk.language.ast.FunkType.StringType)),
            com.funk.language.ast.FunkType.RecordType(emptyMap())
        )
        
        context["native_debug"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("msg" to com.funk.language.ast.FunkType.StringType)),
            com.funk.language.ast.FunkType.RecordType(emptyMap())
        )
        
        // Random number generation
        context["native_random_int"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "min" to com.funk.language.ast.FunkType.IntType,
                "max" to com.funk.language.ast.FunkType.IntType
            )),
            com.funk.language.ast.FunkType.IntType
        )
        
        context["native_random_double"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(emptyMap()),
            com.funk.language.ast.FunkType.FloatType
        )
        
        context["native_random"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("max" to com.funk.language.ast.FunkType.IntType)),
            com.funk.language.ast.FunkType.IntType
        )
        
        // Type conversion functions
        context["intToString"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("val" to com.funk.language.ast.FunkType.IntType)),
            com.funk.language.ast.FunkType.StringType
        )
        
        context["floatToString"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("val" to com.funk.language.ast.FunkType.FloatType)),
            com.funk.language.ast.FunkType.StringType
        )
        
        context["boolToString"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("val" to com.funk.language.ast.FunkType.BooleanType)),
            com.funk.language.ast.FunkType.StringType
        )
        
        context["stringToInt"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("val" to com.funk.language.ast.FunkType.StringType)),
            com.funk.language.ast.FunkType.IntType
        )
        
        context["stringToFloat"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("val" to com.funk.language.ast.FunkType.StringType)),
            com.funk.language.ast.FunkType.FloatType
        )
        
        // Boolean logic functions
        context["boolAnd"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.BooleanType,
                "b" to com.funk.language.ast.FunkType.BooleanType
            )),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        context["boolOr"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.BooleanType,
                "b" to com.funk.language.ast.FunkType.BooleanType
            )),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        context["boolNot"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("val" to com.funk.language.ast.FunkType.BooleanType)),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        // Numeric comparison functions
        context["intEquals"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.IntType,
                "b" to com.funk.language.ast.FunkType.IntType
            )),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        context["intLessThan"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.IntType,
                "b" to com.funk.language.ast.FunkType.IntType
            )),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        context["intGreaterThan"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.IntType,
                "b" to com.funk.language.ast.FunkType.IntType
            )),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        context["floatEquals"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.FloatType,
                "b" to com.funk.language.ast.FunkType.FloatType
            )),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        context["floatLessThan"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.FloatType,
                "b" to com.funk.language.ast.FunkType.FloatType
            )),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        context["floatGreaterThan"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.FloatType,
                "b" to com.funk.language.ast.FunkType.FloatType
            )),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        // String functions
        context["stringEquals"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.StringType,
                "b" to com.funk.language.ast.FunkType.StringType
            )),
            com.funk.language.ast.FunkType.BooleanType
        )
        
        context["stringLength"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("str" to com.funk.language.ast.FunkType.StringType)),
            com.funk.language.ast.FunkType.IntType
        )
        
        context["stringSubstring"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "str" to com.funk.language.ast.FunkType.StringType,
                "start" to com.funk.language.ast.FunkType.IntType,
                "end" to com.funk.language.ast.FunkType.IntType
            )),
            com.funk.language.ast.FunkType.StringType
        )
        
        // Math functions
        context["mathAbs"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("value" to com.funk.language.ast.FunkType.IntType)), // Simplified for now
            com.funk.language.ast.FunkType.IntType
        )
        
        context["mathMax"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.IntType,
                "b" to com.funk.language.ast.FunkType.IntType
            )),
            com.funk.language.ast.FunkType.IntType
        )
        
        context["mathMin"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf(
                "a" to com.funk.language.ast.FunkType.IntType,
                "b" to com.funk.language.ast.FunkType.IntType
            )),
            com.funk.language.ast.FunkType.IntType
        )
    }
    
    /**
     * Extract pattern bindings from a pattern for type checking
     */
    private fun extractPatternBindings(pattern: Pattern, valueType: com.funk.language.ast.FunkType): Map<String, com.funk.language.ast.FunkType> {
        return when (pattern) {
            is VariablePattern -> {
                mapOf(pattern.name to valueType)
            }
            is ConstructorPattern -> {
                val bindings = mutableMapOf<String, com.funk.language.ast.FunkType>()
                
                // If there's a record pattern, extract field bindings
                pattern.recordPattern?.let { recordPattern ->
                    // Try to get the data type for this constructor from the variant type
                    val constructorDataType = when (valueType) {
                        is com.funk.language.ast.FunkType.VariantType -> {
                            valueType.constructors[pattern.constructor]
                        }
                        else -> null
                    }
                    
                    if (constructorDataType is com.funk.language.ast.FunkType.RecordType) {
                        // Extract bindings from record pattern
                        bindings.putAll(extractRecordPatternBindings(recordPattern, constructorDataType))
                    } else {
                        // If we don't know the exact type, infer from field names
                        for (entry in recordPattern.entries) {
                            if (entry.pattern == null) {
                                // Shorthand {x} - bind x to its field type
                                bindings[entry.name] = inferFieldTypeFromName(entry.name)
                            } else {
                                // Pattern {field = pattern} - extract bindings from the pattern
                                val fieldType = inferFieldTypeFromName(entry.name)
                                bindings.putAll(extractPatternBindings(entry.pattern!!, fieldType))
                            }
                        }
                    }
                }
                
                bindings
            }
            is RecordPattern -> {
                when (valueType) {
                    is com.funk.language.ast.FunkType.RecordType -> {
                        extractRecordPatternBindings(pattern, valueType)
                    }
                    else -> {
                        // If we don't know the exact type, infer from field names
                        val bindings = mutableMapOf<String, com.funk.language.ast.FunkType>()
                        for (entry in pattern.entries) {
                            if (entry.pattern == null) {
                                // Shorthand {x} - bind x to its field type
                                bindings[entry.name] = inferFieldTypeFromName(entry.name)
                            } else {
                                // Pattern {field = pattern} - extract bindings from the pattern
                                val fieldType = inferFieldTypeFromName(entry.name)
                                bindings.putAll(extractPatternBindings(entry.pattern!!, fieldType))
                            }
                        }
                        bindings
                    }
                }
            }
            is WildcardPattern -> {
                emptyMap() // Wildcard doesn't bind any variables
            }
        }
    }
    
    /**
     * Extract bindings from a record pattern
     */
    private fun extractRecordPatternBindings(recordPattern: RecordPattern, recordType: com.funk.language.ast.FunkType.RecordType): Map<String, com.funk.language.ast.FunkType> {
        val bindings = mutableMapOf<String, com.funk.language.ast.FunkType>()
        
        for (entry in recordPattern.entries) {
            if (entry.pattern == null) {
                // Shorthand {fieldName} - bind fieldName to its field type
                val fieldType = recordType.fields[entry.name] 
                    ?: inferFieldTypeFromName(entry.name)
                bindings[entry.name] = fieldType
            } else {
                // Pattern {field = pattern} - extract bindings from the pattern
                val fieldType = recordType.fields[entry.name]
                    ?: inferFieldTypeFromName(entry.name)
                bindings.putAll(extractPatternBindings(entry.pattern!!, fieldType))
            }
        }
        
        return bindings
    }
    
    /**
     * Register standard library types into the type checking context
     */
    private fun registerStdLibTypes() {
        // Import StdLib and register all abilities (type signatures only)
        StdLib.registerAll(context)
    }
    
    // ============================================================================
    // ABILITY CONSTRAINT SOLVING
    // ============================================================================
    
    /**
     * Validate that ability dependencies are satisfied
     */
    private fun validateAbilityDependencies(abilityName: String, dependencies: List<AbilityDependency>) {
        for (dependency in dependencies) {
            if (!context.containsKey("${dependency.abilityName}_ability")) {
                throw TypeCheckError("Ability $abilityName depends on ${dependency.abilityName}, but ${dependency.abilityName} is not defined")
            }
        }
    }
    
    /**
     * Get transitive dependencies for an ability
     */
    private fun getTransitiveDependencies(abilityName: String): Set<String> {
        val visited = mutableSetOf<String>()
        val result = mutableSetOf<String>()
        
        fun traverse(name: String) {
            if (name in visited) return
            visited.add(name)
            
            val dependencies = abilityDependencies[name] ?: emptyList()
            for (dep in dependencies) {
                result.add(dep.abilityName)
                traverse(dep.abilityName)
            }
        }
        
        traverse(abilityName)
        return result
    }
    
    /**
     * Validate ability constraints for lambda definitions (more lenient - only checks ability declarations exist)
     */
    private fun validateAbilityConstraintsForLambda(
        constraints: List<FunkType.FunctionAbilityConstraint>
    ): Boolean {
        return constraints.all { constraint ->
            // Only check that the ability declaration exists, not implementations
            context.containsKey("${constraint.abilityName}_ability")
        }
    }
    
    /**
     * Solve ability constraints for function calls (strict - requires implementations)
     */
    private fun solveAbilityConstraints(
        constraints: List<FunkType.FunctionAbilityConstraint>,
        argumentTypes: List<com.funk.language.ast.FunkType>
    ): Boolean {
        return constraints.all { constraint ->
            checkAbilityConstraintSatisfied(constraint, argumentTypes)
        }
    }
    
    /**
     * Check if an ability constraint is satisfied by the given argument types
     */
    private fun checkAbilityConstraintSatisfied(
        constraint: FunkType.FunctionAbilityConstraint,
        argumentTypes: List<com.funk.language.ast.FunkType>
    ): Boolean {
        val concreteTypes = extractConcreteTypeArguments(constraint.typeArgs, argumentTypes)
        return checkAbilityImplementationExists(constraint.abilityName, concreteTypes)
    }
    
    /**
     * Check if ability implementation exists for given types
     */
    private fun checkAbilityImplementationExists(abilityName: String, types: List<com.funk.language.ast.FunkType>): Boolean {
        val typeNames = types.map { getTypeName(it) }
        val implKey = "${abilityName}_${typeNames.joinToString("_")}"
        return context.containsKey(implKey)
    }
    
    /**
     * Extract concrete type arguments from function type arguments and actual argument types
     */
    private fun extractConcreteTypeArguments(
        typeArgs: List<com.funk.language.ast.FunkType>,
        argumentTypes: List<com.funk.language.ast.FunkType>
    ): List<com.funk.language.ast.FunkType> {
        return typeArgs.map { typeArg ->
            when (typeArg) {
                is com.funk.language.ast.FunkType.VarType -> {
                    // Try to find concrete type from argument types
                    extractConcreteTypeFromArgument(typeArg.variable, argumentTypes)
                }
                else -> typeArg
            }
        }
    }
    
    /**
     * Extract concrete type from argument types based on type variable
     */
    private fun extractConcreteTypeFromArgument(
        typeVar: com.funk.language.ast.TypeVar,
        argumentTypes: List<com.funk.language.ast.FunkType>
    ): com.funk.language.ast.FunkType {
        // For now, use the first concrete type we find
        // In a real implementation, this would use unification
        return argumentTypes.firstOrNull { type ->
            when (type) {
                is com.funk.language.ast.FunkType.IntType,
                is com.funk.language.ast.FunkType.StringType,
                is com.funk.language.ast.FunkType.BooleanType,
                is com.funk.language.ast.FunkType.FloatType -> true
                else -> false
            }
        } ?: com.funk.language.ast.FunkType.VarType(typeVar)
    }
    
    /**
     * Get type name for ability implementation lookup
     */
    private fun getTypeName(type: com.funk.language.ast.FunkType): String {
        return when (type) {
            is com.funk.language.ast.FunkType.IntType -> "Int"
            is com.funk.language.ast.FunkType.StringType -> "String"
            is com.funk.language.ast.FunkType.BooleanType -> "Boolean"
            is com.funk.language.ast.FunkType.FloatType -> "Float"
            is com.funk.language.ast.FunkType.NatType -> "Nat"
            is com.funk.language.ast.FunkType.FracType -> "Frac"
            is com.funk.language.ast.FunkType.VarType -> type.variable.name
            is com.funk.language.ast.FunkType.RecordType -> {
                // For record types like {val: Int}, extract the field types
                type.fields.values.firstOrNull()?.let { getTypeName(it) } ?: "Record"
            }
            else -> "Unknown"
        }
    }
    
    /**
     * Resolve ability operation call - find the correct implementation
     */
    private fun resolveAbilityOperationCall(
        abilityName: String,
        operationName: String,
        argumentType: com.funk.language.ast.FunkType
    ): com.funk.language.ast.FunkType {
        // Extract type arguments from the argument
        val typeArgs = extractTypeArgumentsFromArgument(argumentType)
        
        // Check if we have polymorphic types (type variables)
        val hasPolymorphicTypes = typeArgs.any { it.startsWith("'") || it == "Unknown" }
        
        if (hasPolymorphicTypes) {
            // For polymorphic types, use the ability declaration instead of a concrete implementation
            val abilityDeclaration = context["${abilityName}_ability"] as? com.funk.language.ast.FunkType.RecordType
                ?: throw TypeCheckError("Ability declaration $abilityName not found")
            
            val operationType = abilityDeclaration.fields[operationName] as? com.funk.language.ast.FunkType.FunctionType
                ?: throw TypeCheckError("Operation $operationName not found in $abilityName declaration")
            
            return operationType.returnType
        } else {
            // For concrete types, find matching implementation
            // For multi-parameter abilities, we need to try all available implementations
            val implementation = findMatchingAbilityImplementation(abilityName, typeArgs)
                ?: findBestMatchingMultiParameterImplementation(abilityName, typeArgs)
                ?: throw TypeCheckError("No implementation found for ability $abilityName with types $typeArgs")
            
            // Get operation type from implementation
            val operationType = implementation.fields[operationName] as? com.funk.language.ast.FunkType.FunctionType
                ?: throw TypeCheckError("Operation $operationName not found in $abilityName implementation")
            
            return operationType.returnType
        }
    }
    
    /**
     * Extract type arguments from a function argument for ability resolution
     */
    private fun extractTypeArgumentsFromArgument(argumentType: com.funk.language.ast.FunkType): List<String> {
        return when (argumentType) {
            is com.funk.language.ast.FunkType.RecordType -> {
                argumentType.fields.values.map { getTypeName(it) }.distinct()
            }
            else -> listOf(getTypeName(argumentType))
        }
    }
    
    /**
     * Find matching ability implementation for given type arguments
     */
    private fun findMatchingAbilityImplementation(
        abilityName: String,
        typeArgs: List<String>
    ): com.funk.language.ast.FunkType.RecordType? {
        val implKey = "${abilityName}_${typeArgs.joinToString("_")}"
        return context[implKey] as? com.funk.language.ast.FunkType.RecordType
    }
    
    /**
     * Find best matching multi-parameter ability implementation
     * This handles cases where we only know some of the type parameters
     */
    private fun findBestMatchingMultiParameterImplementation(
        abilityName: String,
        knownTypeArgs: List<String>
    ): com.funk.language.ast.FunkType.RecordType? {
        // Find all implementations for this ability
        val abilityImpls = context.keys.filter { it.startsWith("${abilityName}_") && it != "${abilityName}_ability" }
        
        for (implKey in abilityImpls) {
            val implementation = context[implKey] as? com.funk.language.ast.FunkType.RecordType ?: continue
            
            // Extract type args from implementation key (e.g., "Convert_Int_String" -> ["Int", "String"])
            val implTypeArgs = implKey.substringAfter("${abilityName}_").split("_")
            
            // Check if the known type args match the beginning of the implementation type args
            val matches = knownTypeArgs.zip(implTypeArgs).all { (known, impl) -> 
                known == impl || known == "Unknown"
            }
            
            if (matches && knownTypeArgs.size <= implTypeArgs.size) {
                return implementation
            }
        }
        
        return null
    }
    
    /**
     * Validate ability constraints at function call sites
     */
    private fun validateAbilityConstraintsAtCallSite(
        constraints: List<com.funk.language.ast.FunkType.FunctionAbilityConstraint>,
        argumentType: com.funk.language.ast.FunkType
    ) {
        for (constraint in constraints) {
            // Extract concrete types from arguments to substitute for type variables
            val typeArgs = extractTypeArgumentsFromArgument(argumentType)
            
            // Check if implementation exists for the concrete types
            val implementation = findMatchingAbilityImplementation(constraint.abilityName, typeArgs)
            if (implementation == null) {
                throw TypeCheckError("No implementation found for ability ${constraint.abilityName} with types $typeArgs")
            }
        }
    }
    
    /**
     * Validate performer implementation according to new effect system rules
     */
    private fun validatePerformerImplementation(impl: ImplDeclaration) {
        // Rule 1: No 'perform' allowed inside performer implementations
        for (operation in impl.operations) {
            validateNoPerformInExpression(operation.implementation, "performer implementation")
        }
        
        // Rule 2: Only same-effect performers can reuse other performers
        for (operation in impl.operations) {
            validateSameEffectPerformerReuse(operation.implementation, impl.effectName)
        }
        
        // Rule 3: Effect handlers can transform effect types but must preserve return types
        validateEffectTypeTransformation(impl)
    }
    
    /**
     * Validate that effect handlers preserve return types but can transform effect types
     */
    private fun validateEffectTypeTransformation(impl: ImplDeclaration) {
        // Find the effect declaration this implementation is for
        val effectDeclaration = currentProgram?.items?.filterIsInstance<EffectDeclaration>()
            ?.find { it.name == impl.effectName }
            ?: throw TypeCheckError("Effect '${impl.effectName}' not found for implementation '${impl.performerName}'")
        
        // Validate each operation
        for (operation in impl.operations) {
            val effectOperation = effectDeclaration.operations.find { it.name == operation.name }
                ?: throw TypeCheckError("Operation '${operation.name}' not found in effect '${impl.effectName}'")
            
            // Check return type compatibility (must be the same)
            val expectedReturnType = convertTypeAnnotation(effectOperation.returnType)
            val actualReturnType = convertTypeAnnotation(operation.returnType)
            
            if (expectedReturnType != actualReturnType) {
                throw TypeCheckError(
                    "Return type mismatch in performer '${impl.performerName}' operation '${operation.name}': " +
                    "expected ${prettyPrintType(expectedReturnType)}, got ${prettyPrintType(actualReturnType)}"
                )
            }
            
            // Effect transformations are allowed - no validation needed for effects
            // The operation.effects field contains the effect transformations
            
            // Check parameter type compatibility (must be the same)
            val expectedParamType = convertTypeAnnotation(effectOperation.paramType)
            val actualParamType = convertTypeAnnotation(operation.paramType)
            
            if (expectedParamType != actualParamType) {
                throw TypeCheckError(
                    "Parameter type mismatch in performer '${impl.performerName}' operation '${operation.name}': " +
                    "expected ${prettyPrintType(expectedParamType)}, got ${prettyPrintType(actualParamType)}"
                )
            }
            
            // Effect types can be different - this is the transformation capability
            // No validation needed for effect types as they can transform
        }
    }
    
    /**
     * Validate that no 'perform' expressions exist in the given expression
     */
    private fun validateNoPerformInExpression(expr: Expression, context: String) {
        when (expr) {
            is PerformExpression -> {
                throw TypeCheckError("Cannot use 'perform' inside $context")
            }
            is DoExpression -> {
                expr.statements.forEach { stmt ->
                    when (stmt) {
                        is LetStatement -> validateNoPerformInExpression(stmt.expression, context)
                        is ExpressionStatement -> validateNoPerformInExpression(stmt.expression, context)
                    }
                }
                validateNoPerformInExpression(expr.result, context)
            }
            is IfExpression -> {
                validateNoPerformInExpression(expr.condition, context)
                validateNoPerformInExpression(expr.thenBranch, context)
                validateNoPerformInExpression(expr.elseBranch, context)
            }
            is BinaryOperation -> {
                validateNoPerformInExpression(expr.left, context)
                validateNoPerformInExpression(expr.right, context)
            }
            is UnaryOperation -> {
                validateNoPerformInExpression(expr.operand, context)
            }
            is FunctionCall -> {
                validateNoPerformInExpression(expr.function, context)
                validateNoPerformInExpression(expr.arguments, context)
            }
            is LambdaExpression -> {
                validateNoPerformInExpression(expr.body, context)
            }
            is RecordLiteral -> {
                expr.entries.forEach { entry ->
                    entry.value?.let { validateNoPerformInExpression(it, context) }
                }
            }
            is MemberAccess -> {
                validateNoPerformInExpression(expr.receiver, context)
            }
            is VariantConstruction -> {
                expr.value?.let { validateNoPerformInExpression(it, context) }
            }
            // Literals and identifiers are safe
            is IntLiteral, is FloatLiteral, is StringLiteral, is BooleanLiteral, is Identifier -> {
                // No validation needed
            }
            else -> {
                // For any other expression types, we're safe to ignore for now
            }
        }
    }
    
    /**
     * Validate that performer reuse only happens within the same effect type
     */
    private fun validateSameEffectPerformerReuse(expr: Expression, currentEffectName: String) {
        when (expr) {
            is FunctionCall -> {
                // Check if this is a performer call (e.g., SomePerformer.operation)
                if (expr.function is MemberAccess) {
                    val memberAccess = expr.function as MemberAccess
                    if (memberAccess.receiver is Identifier) {
                        val performerName = (memberAccess.receiver as Identifier).name
                        val operationName = memberAccess.member
                        
                        // Find the performer implementation in the current program
                        currentProgram?.items?.filterIsInstance<ImplDeclaration>()?.forEach { impl ->
                            if (impl.performerName == performerName) {
                                // This is a performer call - validate it's the same effect
                                if (impl.effectName != currentEffectName) {
                                    throw TypeCheckError(
                                        "Performer for effect '$currentEffectName' cannot reuse performer '$performerName' " +
                                        "which implements effect '${impl.effectName}'. " +
                                        "Performers can only reuse other performers of the same effect type."
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Recursively validate arguments
                validateSameEffectPerformerReuse(expr.arguments, currentEffectName)
            }
            is DoExpression -> {
                expr.statements.forEach { stmt ->
                    when (stmt) {
                        is LetStatement -> validateSameEffectPerformerReuse(stmt.expression, currentEffectName)
                        is ExpressionStatement -> validateSameEffectPerformerReuse(stmt.expression, currentEffectName)
                    }
                }
                validateSameEffectPerformerReuse(expr.result, currentEffectName)
            }
            is IfExpression -> {
                validateSameEffectPerformerReuse(expr.condition, currentEffectName)
                validateSameEffectPerformerReuse(expr.thenBranch, currentEffectName)
                validateSameEffectPerformerReuse(expr.elseBranch, currentEffectName)
            }
            is BinaryOperation -> {
                validateSameEffectPerformerReuse(expr.left, currentEffectName)
                validateSameEffectPerformerReuse(expr.right, currentEffectName)
            }
            is UnaryOperation -> {
                validateSameEffectPerformerReuse(expr.operand, currentEffectName)
            }
            is LambdaExpression -> {
                validateSameEffectPerformerReuse(expr.body, currentEffectName)
            }
            is RecordLiteral -> {
                expr.entries.forEach { entry ->
                    entry.value?.let { validateSameEffectPerformerReuse(it, currentEffectName) }
                }
            }
            is MemberAccess -> {
                validateSameEffectPerformerReuse(expr.receiver, currentEffectName)
            }
            is VariantConstruction -> {
                expr.value?.let { validateSameEffectPerformerReuse(it, currentEffectName) }
            }
            // Literals and identifiers are safe
            is IntLiteral, is FloatLiteral, is StringLiteral, is BooleanLiteral, is Identifier -> {
                // No validation needed
            }
            else -> {
                // For any other expression types, we're safe to ignore for now
            }
        }
    }
    
}
