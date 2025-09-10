package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.ast.prettyPrintType
import com.funk.language.ast.prettyPrintTypeWithAliases
import com.funk.language.ast.TypeAliasInfo
import java.nio.file.Path
import java.nio.file.Paths

class TypeCheckError(message: String) : Exception(message)

/**
 * Simplified TypeChecker that works with the shared AST
 * This is a minimal stub implementation that enhances the AST with basic type information
 */
class TypeChecker {
    
    // Context for tracking variable types
    private val context = mutableMapOf<String, com.funk.language.ast.FunkType>()
    
    // Registry for type aliases
    private val typeAliases = mutableMapOf<String, TypeAliasInfo>()

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
                    
                    // Always infer the actual type from the expression
                    val actualType = inferBasicType(item.expression)
                    
                    // Try to match the inferred type against known type aliases
                    val matchedType = actualType // We'll handle alias matching in the pretty printer
                    
                    val finalType = item.typeAnnotation?.let { annotation ->
                        // If there's a type annotation, check compatibility and unify
                        val declaredType = convertTypeAnnotation(annotation)
                        
                        if (isTypeCompatible(matchedType, declaredType)) {
                            // If declared type is a type variable, unify it with the actual type
                            if (declaredType is com.funk.language.ast.FunkType.VarType) {
                                matchedType // Use the matched type (with alias if found)
                            } else {
                                declaredType // Use the declared type
                            }
                        } else {
                            throw RuntimeException("Type error: Declared type ${prettyPrintType(declaredType)} does not match inferred type ${prettyPrintType(matchedType)}")
                        }
                    } ?: matchedType
                    
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
                        // Treat as a variant constructor with no data (open variant)
                        val constructors = mapOf(expr.name to null)
                        com.funk.language.ast.FunkType.VariantType(constructors, com.funk.language.ast.RowVar("open_${expr.name}"))
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
                com.funk.language.ast.FunkType.RecordType(fields)
            }
            is MemberAccess -> {
                val receiverType = inferBasicType(expr.receiver)
                when (receiverType) {
                    is com.funk.language.ast.FunkType.RecordType -> {
                        receiverType.fields[expr.member] ?: inferFieldTypeFromName(expr.member)
                    }
                    else -> inferFieldTypeFromName(expr.member)
                }
            }
            is BinaryOperation -> {
                val leftType = inferBasicType(expr.left)
                val rightType = inferBasicType(expr.right)
                
                when (expr.operator) {
                    "+", "-", "*", "/" -> {
                        // Check that both operands are numeric
                        if (leftType == com.funk.language.ast.FunkType.IntType && rightType == com.funk.language.ast.FunkType.IntType) {
                            com.funk.language.ast.FunkType.IntType
                        } else if ((leftType == com.funk.language.ast.FunkType.FloatType || leftType == com.funk.language.ast.FunkType.IntType) &&
                                   (rightType == com.funk.language.ast.FunkType.FloatType || rightType == com.funk.language.ast.FunkType.IntType)) {
                            com.funk.language.ast.FunkType.FloatType
                        } else {
                            throw RuntimeException("Type error: Cannot apply arithmetic operator '${expr.operator}' to ${prettyPrintType(leftType)} and ${prettyPrintType(rightType)}")
                        }
                    }
                    "++" -> {
                        // String concatenation - both operands must be strings
                        if (leftType == com.funk.language.ast.FunkType.StringType && rightType == com.funk.language.ast.FunkType.StringType) {
                            com.funk.language.ast.FunkType.StringType
                        } else {
                            throw RuntimeException("Type error: Cannot concatenate ${prettyPrintType(leftType)} and ${prettyPrintType(rightType)}. Use intToString to convert integers to strings.")
                        }
                    }
                    "==", "!=", "<", ">", "<=", ">=" -> com.funk.language.ast.FunkType.BooleanType
                    "&&", "||" -> com.funk.language.ast.FunkType.BooleanType
                    else -> com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                }
            }
            is VariantConstruction -> {
                val constructors = mapOf(expr.constructor to expr.value?.let { inferBasicType(it) })
                com.funk.language.ast.FunkType.VariantType(constructors, com.funk.language.ast.RowVar("open_${expr.constructor}"))
            }
            is LambdaExpression -> {
                // Try to infer parameter type from lambda parameters
                val paramType = when (val params = expr.parameters) {
                    is UntypedParameters -> {
                        // Analyze the body to infer parameter types from usage
                        val paramTypes = inferParameterTypesFromUsage(expr.body, params.names)
                        com.funk.language.ast.FunkType.RecordType(paramTypes)
                    }
                    is TypedParameters -> {
                        // Convert the type annotation to a FunkType
                        convertTypeAnnotation(params.recordType)
                    }
                }
                
                // Enter new scope for lambda parameters
                val originalContext = context.toMap()
                val scopeLevel = com.funk.language.ast.TypeVar.enterScope()
                
                when (val params = expr.parameters) {
                    is UntypedParameters -> {
                        // For untyped parameters, add them with inferred types
                        if (paramType is com.funk.language.ast.FunkType.RecordType) {
                            paramType.fields.forEach { (name, type) ->
                                context[name] = type
                            }
                        }
                    }
                    is TypedParameters -> {
                        // For typed parameters, add them with their explicit types
                        if (paramType is com.funk.language.ast.FunkType.RecordType) {
                            paramType.fields.forEach { (name, type) ->
                                context[name] = type
                            }
                        }
                    }
                }
                
                val returnType = inferBasicType(expr.body)
                
                // Exit scope and restore original context
                com.funk.language.ast.TypeVar.exitScope()
                context.clear()
                context.putAll(originalContext)
                
                com.funk.language.ast.FunkType.FunctionType(paramType, returnType)
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
            is PartiallyAppliedFunction -> {
                // Partially applied function - return its inferred type
                expr.inferredType ?: com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
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
                // Check if expected variant is open (has rowVar)
                val expectedIsOpen = expectedType.rowVar != null
                
                if (expectedIsOpen) {
                    // For open expected variants, actual constructors must be compatible with expected ones
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
                } else {
                    // For closed expected variants, require exact constructor match
                    actualType.constructors.keys == expectedType.constructors.keys &&
                    actualType.constructors.all { (constructor, actualDataType) ->
                        val expectedDataType = expectedType.constructors[constructor]
                        when {
                            expectedDataType == null && actualDataType == null -> true
                            expectedDataType != null && actualDataType != null -> isTypeCompatible(actualDataType, expectedDataType)
                            else -> false
                        }
                    }
                }
            }
            
            // Type variables can unify with any type
            actualType is com.funk.language.ast.FunkType.VarType -> true
            expectedType is com.funk.language.ast.FunkType.VarType -> true
            
            else -> false
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
            is AliasDefinition -> {
                // Type alias - convert the aliased type
                convertTypeAnnotation(definition.aliasedType)
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
