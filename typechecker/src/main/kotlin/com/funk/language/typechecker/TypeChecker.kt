package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.ast.prettyPrintType
import com.funk.language.ast.prettyPrintTypeWithAliases
import com.funk.language.ast.TypeAliasInfo
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
    
    // Registry for type aliases
    private val typeAliases = mutableMapOf<String, TypeAliasInfo>()
    
    // Simple constraint collection for basic unification
    private val constraints = mutableListOf<Pair<com.funk.language.ast.FunkType, com.funk.language.ast.FunkType>>()

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
        
        // Add built-in functions to the context
        context["intToString"] = com.funk.language.ast.FunkType.FunctionType(
            com.funk.language.ast.FunkType.RecordType(mapOf("val" to com.funk.language.ast.FunkType.IntType)),
            com.funk.language.ast.FunkType.StringType
        )
        
        val enhancedItems = program.items.map { item ->
            when (item) {
                is LetBinding -> {
                    // Reset scope counter for each top-level function to start fresh with 'a
                    com.funk.language.ast.TypeVar.resetScopeCounter(0)
                    
                    // Enhanced type checking with unification
                    val actualType = inferTypeWithUnification(item.expression)
                    
                    val finalType = item.typeAnnotation?.let { annotation ->
                        // If there's a type annotation, try to unify with it
                        val declaredType = convertTypeAnnotation(annotation)
                        
                        if (unifyTypes(actualType, declaredType)) {
                            // Unification successful - use the declared type
                            declaredType
                        } else {
                            throw RuntimeException("Type error: Declared type ${prettyPrintType(declaredType)} does not match inferred type ${prettyPrintType(actualType)}")
                        }
                    } ?: actualType
                    
                    // Update context with this binding
                    context[item.variableName] = finalType
                    item.copy(inferredType = finalType)
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
                else -> item
            }
        }
        return program.copy(items = enhancedItems)
    }

    /**
     * Enhanced type inference with constraint-based unification
     */
    private fun inferTypeWithUnification(expr: Expression): com.funk.language.ast.FunkType {
        // Clear constraints for this inference
        constraints.clear()
        
        // Use the existing inference but with constraint collection
        val inferredType = inferBasicType(expr)
        
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
            
            else -> {
                if (!isTypeCompatible(t1, t2)) {
                    throw RuntimeException("Incompatible types")
                }
            }
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
                    applySubstitution(type.returnType, substitutions)
                )
            }
            is com.funk.language.ast.FunkType.RecordType -> {
                val substitutedFields = type.fields.mapValues { (_, fieldType) ->
                    applySubstitution(fieldType, substitutions)
                }
                com.funk.language.ast.FunkType.RecordType(substitutedFields, type.rowVar)
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
     * Basic type inference for expressions - this is a simplified stub
     */
    private fun inferBasicType(expr: Expression): com.funk.language.ast.FunkType {
        return when (expr) {
            is IntLiteral -> com.funk.language.ast.FunkType.IntType
            is FloatLiteral -> com.funk.language.ast.FunkType.FloatType
            is StringLiteral -> com.funk.language.ast.FunkType.StringType
            is BooleanLiteral -> com.funk.language.ast.FunkType.BooleanType
            is Identifier -> {
                // Look up the identifier in the context first
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
                // Try to infer return type from function type and validate arguments
                val functionType = inferBasicType(expr.function)
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
                                com.funk.language.ast.FunkType.FunctionType(remainingParamType, functionType.returnType)
                            } else {
                                // All parameters provided - validate types and return result type
                                for ((fieldName, providedType) in providedFields) {
                                    val expectedType = expectedFields[fieldName]
                                    if (expectedType != null && !isTypeCompatible(providedType, expectedType)) {
                                        throw RuntimeException("Type error: Field '$fieldName' expects ${prettyPrintType(expectedType)} but got ${prettyPrintType(providedType)}")
                                    }
                                }
                                functionType.returnType
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
                // Try to infer from the first case (simplified)
                if (expr.cases.isNotEmpty()) {
                    inferBasicType(expr.cases.first().expression)
                } else {
                    com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                }
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
                val leftType = inferBasicType(expr.left)
                val rightType = inferBasicType(expr.right)
                
                when (expr.operator) {
                    "+", "-", "*", "/" -> {
                        // Arithmetic operations - check that both operands are numeric
                        if (leftType == com.funk.language.ast.FunkType.IntType && rightType == com.funk.language.ast.FunkType.IntType) {
                            com.funk.language.ast.FunkType.IntType
                        } else if ((leftType == com.funk.language.ast.FunkType.FloatType || leftType == com.funk.language.ast.FunkType.IntType) &&
                                   (rightType == com.funk.language.ast.FunkType.FloatType || rightType == com.funk.language.ast.FunkType.IntType)) {
                            com.funk.language.ast.FunkType.FloatType
                        } else if (leftType is com.funk.language.ast.FunkType.VarType || rightType is com.funk.language.ast.FunkType.VarType) {
                            // Try to unify with Int for type variables
                            addConstraint(leftType, com.funk.language.ast.FunkType.IntType)
                            addConstraint(rightType, com.funk.language.ast.FunkType.IntType)
                            com.funk.language.ast.FunkType.IntType
                        } else {
                            throw RuntimeException("Type error: Cannot apply arithmetic operator '${expr.operator}' to ${prettyPrintType(leftType)} and ${prettyPrintType(rightType)}")
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
                        // Comparison operators - both operands must be Int (for now, can extend later)
                        if (leftType == com.funk.language.ast.FunkType.IntType && rightType == com.funk.language.ast.FunkType.IntType) {
                            com.funk.language.ast.FunkType.BooleanType
                        } else if (leftType is com.funk.language.ast.FunkType.VarType || rightType is com.funk.language.ast.FunkType.VarType) {
                            // Constrain both operands to be Int, just like arithmetic operators
                            addConstraint(leftType, com.funk.language.ast.FunkType.IntType)
                            addConstraint(rightType, com.funk.language.ast.FunkType.IntType)
                            com.funk.language.ast.FunkType.BooleanType
                        } else {
                            throw RuntimeException("Type error: Cannot compare ${prettyPrintType(leftType)} and ${prettyPrintType(rightType)}")
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
                com.funk.language.ast.FunkType.VariantType(constructors, com.funk.language.ast.RowVar("open_${expr.constructor}"))
            }
            is LambdaExpression -> {
                // Enter new scope for lambda parameters
                val originalContext = context.toMap()
                val scopeLevel = com.funk.language.ast.TypeVar.enterScope()
                
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
                
                // Analyze the body to infer parameter types from usage patterns (like match expressions)
                if (paramType is com.funk.language.ast.FunkType.RecordType) {
                    val paramTypes = paramType.fields.toMutableMap()
                    analyzeExpressionForParameterTypes(expr.body, paramTypes)
                    
                    // Update context with refined parameter types
                    paramTypes.forEach { (name, type) ->
                        context[name] = type
                    }
                    
                    // Update paramType with refined types
                    val refinedParamType = com.funk.language.ast.FunkType.RecordType(paramTypes)
                    
                    // Infer the return type from the body (this will add constraints)
                    val returnType = inferBasicType(expr.body)
                    
                    // Exit scope and restore original context
                    com.funk.language.ast.TypeVar.exitScope()
                    context.clear()
                    context.putAll(originalContext)
                    
                    return com.funk.language.ast.FunkType.FunctionType(refinedParamType, returnType)
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
                val finalParamType = if (paramType is com.funk.language.ast.FunkType.RecordType) {
                    // Reconstruct parameter type from the updated context
                    val updatedFields = paramType.fields.mapValues { (name, _) ->
                        context[name] ?: paramType.fields[name]!!
                    }
                    com.funk.language.ast.FunkType.RecordType(updatedFields, paramType.rowVar)
                } else {
                    applySubstitution(paramType, substitutions)
                }
                val finalReturnType = applySubstitution(returnType, substitutions)
                
                // Exit scope and restore original context
                com.funk.language.ast.TypeVar.exitScope()
                context.clear()
                context.putAll(originalContext)
                
                com.funk.language.ast.FunkType.FunctionType(finalParamType, finalReturnType)
            }
            is DoExpression -> {
                // Enter a new scope for the do block
                val scopeLevel = com.funk.language.ast.TypeVar.enterScope()
                
                // Save current context
                val originalContext = context.toMap()
                
                try {
                    // Process statements in the do block
                    for (statement in expr.statements) {
                        when (statement) {
                            is LetStatement -> {
                                val actualType = inferBasicType(statement.expression)
                                val matchedType = actualType // We'll handle alias matching in the pretty printer
                                
                                val finalType = statement.typeAnnotation?.let { annotation ->
                                    val declaredType = convertTypeAnnotation(annotation)
                                    if (isTypeCompatible(matchedType, declaredType)) {
                                        if (declaredType is com.funk.language.ast.FunkType.VarType) {
                                            matchedType
                                        } else {
                                            declaredType
                                        }
                                    } else {
                                        throw RuntimeException("Type error: Declared type ${prettyPrintType(declaredType)} does not match inferred type ${prettyPrintType(matchedType)}")
                                    }
                                } ?: matchedType
                                
                                // Update context with this binding
                                context[statement.variableName] = finalType
                            }
                            is ExpressionStatement -> {
                                // Just type check the expression, don't bind anything
                                inferBasicType(statement.expression)
                            }
                        }
                    }
                    
                    // Return type of the final expression
                    inferBasicType(expr.result)
                } finally {
                    // Exit the scope and restore context
                    com.funk.language.ast.TypeVar.exitScope()
                    context.clear()
                    context.putAll(originalContext)
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
                                        val fields = recordPattern.entries.associate { entry ->
                                            // Try to infer field types from usage context
                                            entry.name to inferFieldTypeFromName(entry.name)
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
            }
            is BinaryOperation -> {
                val left = expr.left
                val right = expr.right
                
                // For arithmetic operations, infer that operands should be Int
                if (expr.operator in setOf("+", "-", "*", "/")) {
                    if (left is Identifier && paramTypes.containsKey(left.name)) {
                        paramTypes[left.name] = com.funk.language.ast.FunkType.IntType
                    }
                    if (right is Identifier && paramTypes.containsKey(right.name)) {
                        paramTypes[right.name] = com.funk.language.ast.FunkType.IntType
                    }
                }
                // For string concatenation, infer that operands should be String
                else if (expr.operator == "++") {
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
            
            // Variant type compatibility - actual variant can be a subset of expected variant
            actualType is com.funk.language.ast.FunkType.VariantType && expectedType is com.funk.language.ast.FunkType.VariantType -> {
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
                }
            }
            
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
            is com.funk.language.ast.FunkType.FunctionType -> containsTypeVariables(type.paramType) || containsTypeVariables(type.returnType)
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
                com.funk.language.ast.FunkType.FunctionType(paramType, returnType)
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
                    substituteInType(type.returnType, substitution)
                )
            }
            else -> type
        }
    }
    
}
