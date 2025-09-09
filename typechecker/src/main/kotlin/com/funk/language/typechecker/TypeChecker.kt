package com.funk.language.typechecker

import com.funk.language.parser.*
import java.nio.file.Path
import java.nio.file.Paths

class TypeCheckError(message: String) : Exception(message)

/**
 * Main type checker for Funk language with row polymorphism and effects
 */

class TypeChecker {
    
    /**
     * Type check a program and return the typed AST with inferred types
     */
    fun typeCheck(program: Program): TypedProgram {
        return typeCheck(program, Paths.get("."))
    }
    
    /**
     * Type check a program with module resolution support
     */
    fun typeCheck(program: Program, currentPath: Path): TypedProgram {
        // Simple type checking without module resolution
        return typeCheckSimple(program)
    }
    
    /**
     * Simple type checking without module resolution (legacy method)
     */
    private fun typeCheckSimple(program: Program): TypedProgram {
        var context = TypeContext()
        val typedItems = mutableListOf<TypedItem>()
        
        for (item in program.items) {
            val typedItem = inferItemWithConstraints(item, context)
            typedItems.add(typedItem)
            
            // Update context with new bindings for subsequent items
            context = when (typedItem) {
                is TypedLetBinding -> {
                    val scheme = generalize(context.env, typedItem.inferredType)
                    val newEnv = context.env.extend(typedItem.variableName, scheme)
                    context.copy(env = newEnv)
                }
                is TypedTypeDeclaration -> {
                    // Update context with type alias and variant constructors
                    var newEnv = context.env.copy(typeAliases = context.env.typeAliases + (typedItem.name to typedItem.type))
                    
                    // If it's a variant type, add constructors to the environment
                    if (typedItem.type is FunkType.VariantType) {
                        val variantType = typedItem.type as FunkType.VariantType
                        for ((constructorName, constructorDataType) in variantType.constructors) {
                            // Create a constructor function type
                            val constructorType = if (constructorDataType != null) {
                                // Constructor with data: DataType -> VariantType
                                FunkType.FunctionType(constructorDataType, typedItem.type)
                            } else {
                                // Constructor without data: just the variant type
                                typedItem.type
                            }
                            val scheme = TypeScheme.monomorphic(constructorType)
                            newEnv = newEnv.extend(constructorName, scheme)
                        }
                    }
                    
                    context.copy(env = newEnv)
                }
                is TypedModuleDeclaration -> {
                    // For now, modules don't affect the global context
                    // In a full implementation, this would handle module exports
                    context
                }

            }
        }
        
        // Post-process: rename type variables to clean names (a, b, c, etc.)
        val cleanedItems = typedItems.map { item ->
            when (item) {
                is TypedLetBinding -> {
                    val cleanedType = cleanupTypeVariableNames(item.inferredType)
                    item.copy(inferredType = cleanedType)
                }
                else -> item
            }
        }
        
        return TypedProgram(cleanedItems)
    }
    
    /**
     * Clean up type variable names by renaming them to a, b, c, etc.
     * This should be called after all unification is complete.
     */
    private fun cleanupTypeVariableNames(type: FunkType): FunkType {
        val renameMap = mutableMapOf<TypeVar, String>()
        var letterCounter = 0
        
        fun generateCleanName(): String {
            return if (letterCounter < 26) {
                ('a' + letterCounter).toString()
            } else {
                // For indices >= 26, use aa, ab, ac, etc.
                val firstLetter = 'a' + (letterCounter / 26 - 1)
                val secondLetter = 'a' + (letterCounter % 26)
                "$firstLetter$secondLetter"
            }
        }
        
        fun cleanupType(t: FunkType): FunkType {
            return when (t) {
                is FunkType.VarType -> {
                    val cleanName = renameMap.getOrPut(t.variable) {
                        val name = generateCleanName()
                        letterCounter++
                        name
                    }
                    FunkType.VarType(TypeVar(cleanName, t.variable.level, t.variable.id))
                }
                is FunkType.RecordType -> {
                    FunkType.RecordType(
                        t.fields.mapValues { cleanupType(it.value) },
                        t.rowVar
                    )
                }
                is FunkType.VariantType -> {
                    FunkType.VariantType(
                        t.constructors.mapValues { it.value?.let { cleanupType(it) } },
                        t.rowVar
                    )
                }
                is FunkType.FunctionType -> {
                    FunkType.FunctionType(
                        cleanupType(t.paramType),
                        cleanupType(t.returnType)
                    )
                }
                is FunkType.TypeConstructor -> {
                    FunkType.TypeConstructor(
                        t.name,
                        t.args.map { cleanupType(it) }
                    )
                }
                is FunkType.TypeAlias -> {
                    FunkType.TypeAlias(t.name, cleanupType(t.type))
                }
                else -> t // Primitive types remain unchanged
            }
        }
        
        return cleanupType(type)
    }
    
    private fun inferItem(item: ProgramItem, context: TypeContext): TypedItem {
        return when (item) {
            is LetBinding -> {
                val inferredType = inferExpression(item.expression, context)
                
                TypedLetBinding(
                    variableName = item.variableName,
                    declaredType = item.typeAnnotation?.let { convertTypeAnnotation(it) },
                    inferredType = inferredType,
                    expression = TypedExpression(item.expression, inferredType)
                )
            }
            
            is TypeDeclaration -> {
                val type = convertTypeDefinition(item.definition)
                
                // Note: Type environment update is handled in the main typeCheck method
                
                TypedTypeDeclaration(
                    name = item.name,
                    type = type,
                    hash = ContentHash.compute(type)
                )
            }
            
            
            is ModuleDeclaration -> {
                // For now, just process the module statements as regular items
                // In a full implementation, this would create a module scope
                val moduleItems = mutableListOf<TypedItem>()
                var moduleContext = context
                
                for (statement in item.statements) {
                    // Only process declarations within modules for now
                    if (statement !is Declaration) {
                        continue
                    }
                    val typedStatement = inferItem(statement as ProgramItem, moduleContext)
                    moduleItems.add(typedStatement)
                    
                    // Update context for subsequent statements
                    moduleContext = when (typedStatement) {
                        is TypedLetBinding -> {
                            val scheme = generalize(moduleContext.env, typedStatement.inferredType)
                            val newEnv = moduleContext.env.extend(typedStatement.variableName, scheme)
                            moduleContext.copy(env = newEnv)
                        }
                        else -> moduleContext
                    }
                }
                
                TypedModuleDeclaration(
                    name = item.name,
                    statements = moduleItems
                )
            }
            
            else -> throw TypeError.UnboundVariable("Unsupported top-level item: ${item::class.simpleName}")
        }
    }
    
    /**
     * Constraint-based item inference - collects constraints and solves them
     */
    private fun inferItemWithConstraints(item: ProgramItem, context: TypeContext): TypedItem {
        return when (item) {
            is LetBinding -> {
                // Use constraint-based inference for expressions
                val result = inferExpressionWithConstraints(item.expression, context)
                
                // Solve all constraints to get the final type
                val substitution = ConstraintSolver.solve(result.constraints)
                val finalType = substitution.apply(result.type)
                
                TypedLetBinding(
                    variableName = item.variableName,
                    declaredType = item.typeAnnotation?.let { convertTypeAnnotation(it) },
                    inferredType = finalType,
                    expression = TypedExpression(item.expression, finalType)
                )
            }
            
            is TypeDeclaration -> {
                val type = convertTypeDefinition(item.definition)
                
                TypedTypeDeclaration(
                    name = item.name,
                    type = type,
                    hash = ContentHash.compute(type)
                )
            }
            
            
            
            
            
            is ModuleDeclaration -> {
                // For now, modules are simplified
                TypedModuleDeclaration(
                    name = item.name,
                    statements = emptyList()
                )
            }
            
            is ModDeclaration -> {
                // Rust-style mod declaration - for now, just ignore
                TypedLetBinding(
                    variableName = "mod_${item.moduleName}",
                    declaredType = null,
                    inferredType = FunkType.VarType(TypeVar.fresh("unit")),
                    expression = TypedExpression(
                        Literal("mod"),
                        FunkType.StringType
                    )
                )
            }
            
            is AccessModuleDeclaration -> {
                // Rust-style access declaration - for now, just ignore
                TypedLetBinding(
                    variableName = "access_${item.modulePath.joinToString("_")}",
                    declaredType = null,
                    inferredType = FunkType.VarType(TypeVar.fresh("unit")),
                    expression = TypedExpression(
                        Literal("access"),
                        FunkType.StringType
                    )
                )
            }
            
            is ExpressionStatement -> {
                // Expression statements are not supported at the top level in this simplified version
                throw TypeError.UnboundVariable("Expression statements not supported at top level")
            }
            
            is ResumeStatement -> {
                // Resume statements are not supported at the top level
                throw TypeError.UnboundVariable("Resume statements not supported at top level")
            }
        }
    }
    
    private fun inferExpression(expr: Expression, context: TypeContext): FunkType {
        return when (expr) {
            is Literal<*> -> inferLiteral(expr)
            is Identifier -> inferIdentifier(expr, context)
            is LambdaExpression -> inferLambda(expr, context)
            is FunctionCall -> inferFunctionCall(expr, context)
            is RecordLiteral -> inferRecordLiteral(expr, context)
            is MemberAccess -> inferMemberAccess(expr, context)
            is RecordUpdate -> inferRecordUpdate(expr, context)
            is BinaryOperation -> inferBinaryOperation(expr, context)
            is VariantConstruction -> inferVariantConstruction(expr, context)
            is IfExpression -> inferIfExpression(expr, context)
            is MatchExpression -> inferMatchExpression(expr, context)
            is PipeExpression -> inferPipeExpression(expr, context)
            is DoExpression -> inferDoExpression(expr, context)
            is BlockExpression -> inferBlockExpression(expr, context)
            is HandleExpression -> inferHandleExpression(expr, context)
            is PlaceholderExpression -> {
                // Placeholder should only appear in pipe expressions and will be replaced during transformation
                throw TypeError.UnboundVariable("Placeholder @ can only be used in pipe expressions")
            }
            else -> throw TypeError.UnboundVariable("Unsupported expression: ${expr::class.simpleName}")
        }
    }
    
    /**
     * Constraint-based expression inference - the core of the new system
     */
    private fun inferExpressionWithConstraints(expr: Expression, context: TypeContext): InferenceResult {
        return when (expr) {
            is Literal<*> -> InferenceResultHelper.simple(inferLiteral(expr))
            is Identifier -> inferIdentifierWithConstraints(expr, context)
            is LambdaExpression -> inferLambdaWithConstraints(expr, context)
            is FunctionCall -> inferFunctionCallWithConstraints(expr, context)
            is RecordLiteral -> inferRecordLiteralWithConstraints(expr, context)
            is MemberAccess -> inferMemberAccessWithConstraints(expr, context)
            is RecordUpdate -> inferRecordUpdateWithConstraints(expr, context)
            is BinaryOperation -> inferBinaryOperationWithConstraints(expr, context)
            is VariantConstruction -> inferVariantConstructionWithConstraints(expr, context)
            is IfExpression -> inferIfExpressionWithConstraints(expr, context)
            is MatchExpression -> inferMatchExpressionWithConstraints(expr, context)
            is PipeExpression -> inferPipeExpressionWithConstraints(expr, context)
            is DoExpression -> inferDoExpressionWithConstraints(expr, context)
            is BlockExpression -> inferBlockExpressionWithConstraints(expr, context)
            is HandleExpression -> inferHandleExpressionWithConstraints(expr, context)
            is PlaceholderExpression -> {
                throw TypeError.UnboundVariable("Placeholder @ can only be used in pipe expressions")
            }
            else -> throw TypeError.UnboundVariable("Unsupported expression: ${expr::class.simpleName}")
        }
    }
    
    private fun inferLiteral(literal: Literal<*>): FunkType = when (literal.value) {
        is Int -> FunkType.IntType
        is Float -> FunkType.FloatType
        is String -> FunkType.StringType
        is Boolean -> FunkType.BooleanType
        else -> throw TypeError.UnboundVariable("Unsupported literal type: ${literal.value?.let { it::class.simpleName }}")
    }

    private fun inferIdentifier(identifier: Identifier, context: TypeContext): FunkType {
        // First check if it's a variable
        val scheme = context.env.lookup(identifier.name)
        if (scheme != null) {
            return instantiate(scheme)
        }
        
        // Then check if it's an effect
        val effect = context.env.lookupEffect(identifier.name)
        if (effect != null) {
            // Effects are spiritually like records - they can be referenced as identifiers
            // For now, we'll represent them as a special effect type
            // In a full implementation, this would be a proper effect type
            return FunkType.VarType(TypeVar.fresh()) // Placeholder for effect type
        }
        
        // Check if it's an uppercase identifier (potential variant constructor)
        if (identifier.name.isNotEmpty() && identifier.name[0].isUpperCase()) {
            // Create a closed variant type with this constructor
            // It will be merged with other constructors during unification
            val constructorType: FunkType? = null // No data constructor for simple variants
            val variantType = FunkType.VariantType(
                constructors = mapOf(identifier.name to constructorType),
                rowVar = null // Start closed - will be opened if needed during unification
            )
            return variantType
        }
        
        throw StructuredTypeError.unboundVariable(
            identifier.name,
            context.env.bindings.keys.toList(),
            context = ErrorContext(
                function = context.currentFunction,
                expressionType = "variable reference",
                variableName = identifier.name
            )
        )
    }

    private fun inferEffectInvocation(call: FunctionCall, effect: Effect, context: TypeContext): FunkType {
        // Look up the effect's function type
        val effectScheme = context.env.lookup(effect.name)
        if (effectScheme == null) {
            throw TypeError.UnboundVariable("Effect ${effect.name} not found in environment")
        }
        
        val effectType = instantiate(effectScheme)
        if (effectType !is FunkType.FunctionType) {
            throw TypeError.UnboundVariable("${effect.name} is not a function type")
        }
        
        // Check that the arguments match the effect's input type
        val argumentType = inferExpression(call.arguments, context)
        try {
            Unification.unify(argumentType, effectType.paramType)
        } catch (e: Exception) {
            throw TypeError.UnboundVariable("Effect ${effect.name} expects ${effectType.paramType} but got $argumentType")
        }
        
        // Return the effect's result type
        return effectType.returnType
    }
    
    private fun inferLambda(lambda: LambdaExpression, context: TypeContext): FunkType {
        val parameters = lambda.parameters
        return when (parameters) {
            is TypedParameters -> {
                val paramType = convertRecordType(parameters.recordType)
                
                // Create new context with parameters bound
                val paramContext = bindRecordParameters(parameters.recordType, context)
                
                val bodyType = inferExpression(lambda.body, paramContext)
                
                // Collect effects from body
                FunkType.FunctionType(paramType, bodyType)
            }
            is UntypedParameters -> {
                // Create fresh type variables for untyped parameters (bidirectional inference)
                val paramTypes = parameters.names.associate { name ->
                    name to FunkType.VarType(TypeVar.fresh())
                }
                val paramType = FunkType.RecordType(paramTypes)
                
                // Create context with type variables - let match expressions do bidirectional inference
                val paramContext = bindUntypedParameters(parameters.names, paramTypes, context)
                
                // Store the original parameter type variables for substitution tracking
                val originalParamVars = paramTypes.values.toSet()
                
                val bodyType = inferExpression(lambda.body, paramContext)
                
                // After body inference, try to refine parameter types from usage
                val refinedParamTypes = refineParameterTypes(lambda.body, parameters.names, paramTypes)
                val refinedParamType = FunkType.RecordType(refinedParamTypes)
                
                FunkType.FunctionType(refinedParamType, bodyType)
            }
        }
    }
    
    /**
     * Refine parameter types after body inference (post-processing)
     */
    private fun refineParameterTypes(
        body: Expression, 
        paramNames: List<String>, 
        originalTypes: Map<String, FunkType>
    ): Map<String, FunkType> {
        // Try to extract the refined types from the body analysis
        val refinedTypes = mutableMapOf<String, FunkType>()
        
        // Initialize with original types
        for ((name, type) in originalTypes) {
            refinedTypes[name] = type
        }
        
        // Analyze the body to extract inferred types from match expressions
        extractInferredTypesFromBody(body, refinedTypes)
        
        return refinedTypes
    }
    
    /**
     * Extract inferred types from match expressions in the body
     */
    private fun extractInferredTypesFromBody(expr: Expression, paramTypes: MutableMap<String, FunkType>) {
        when (expr) {
            is MatchExpression -> {
                // If we're matching on a parameter, try to extract its inferred type
                val matchedExpr = expr.expression
                if (matchedExpr is Identifier && paramTypes.containsKey(matchedExpr.name)) {
                    // Infer the variant type from the patterns
                    val constructors = mutableMapOf<String, FunkType?>()
                    var hasWildcard = false
                    
                    for (case in expr.cases) {
                        when (val pattern = case.pattern) {
                            is ConstructorPattern -> {
                                constructors[pattern.constructor] = null
                            }
                            is WildcardPattern -> {
                                hasWildcard = true
                            }
                            is VariablePattern -> {
                                hasWildcard = true
                            }
                            else -> { /* Other patterns */ }
                        }
                    }
                    
                    if (constructors.isNotEmpty()) {
                        val rowVar = if (hasWildcard) RowVar.fresh("variant") else null
                        val variantType = FunkType.VariantType(constructors, rowVar)
                        paramTypes[matchedExpr.name] = variantType
                    }
                }
                
                // Recursively analyze case expressions
                for (case in expr.cases) {
                    extractInferredTypesFromBody(case.expression, paramTypes)
                }
            }
            
            is BinaryOperation -> {
                // If parameters are used in arithmetic or comparison with numbers, infer as Int
                val leftExpr = expr.left
                val rightExpr = expr.right
                
                // Arithmetic operations
                if (expr.operator in listOf("+", "-", "*", "/", "%")) {
                    // Handle direct parameter usage like param + 1
                    if (leftExpr is Identifier && paramTypes.containsKey(leftExpr.name)) {
                        paramTypes[leftExpr.name] = FunkType.IntType
                    }
                    if (rightExpr is Identifier && paramTypes.containsKey(rightExpr.name)) {
                        paramTypes[rightExpr.name] = FunkType.IntType
                    }
                    
                    // Handle member access expressions like person.age + 1
                    if (leftExpr is MemberAccess && leftExpr.receiver is Identifier) {
                        val receiver = leftExpr.receiver as Identifier
                        if (paramTypes.containsKey(receiver.name)) {
                            val paramName = receiver.name
                            val fieldName = leftExpr.member
                            val currentType = paramTypes[paramName]
                            if (currentType is FunkType.VarType) {
                                // Create record type with Int field
                                val recordType = FunkType.RecordType(
                                    mapOf(fieldName to FunkType.IntType),
                                    rowVar = null
                                )
                                paramTypes[paramName] = recordType
                            } else if (currentType is FunkType.RecordType) {
                                // Update existing record type with Int field
                                val updatedFields = currentType.fields + (fieldName to FunkType.IntType)
                                paramTypes[paramName] = FunkType.RecordType(updatedFields, currentType.rowVar)
                            }
                        }
                    }
                    if (rightExpr is MemberAccess && rightExpr.receiver is Identifier) {
                        val receiver = rightExpr.receiver as Identifier
                        if (paramTypes.containsKey(receiver.name)) {
                            val paramName = receiver.name
                            val fieldName = rightExpr.member
                            val currentType = paramTypes[paramName]
                            if (currentType is FunkType.VarType) {
                                // Create record type with Int field
                                val recordType = FunkType.RecordType(
                                    mapOf(fieldName to FunkType.IntType),
                                    rowVar = null
                                )
                                paramTypes[paramName] = recordType
                            } else if (currentType is FunkType.RecordType) {
                                // Update existing record type with Int field
                                val updatedFields = currentType.fields + (fieldName to FunkType.IntType)
                                paramTypes[paramName] = FunkType.RecordType(updatedFields, currentType.rowVar)
                            }
                        }
                    }
                }
                
                // Comparison operations - infer types from literals
                if (expr.operator in listOf(">", "<", ">=", "<=", "==", "!=")) {
                    // If comparing parameter with integer literal, infer parameter as Int
                    if (leftExpr is Identifier && paramTypes.containsKey(leftExpr.name) && 
                        rightExpr is Literal<*> && rightExpr.value is Int) {
                        paramTypes[leftExpr.name] = FunkType.IntType
                    }
                    if (rightExpr is Identifier && paramTypes.containsKey(rightExpr.name) && 
                        leftExpr is Literal<*> && leftExpr.value is Int) {
                        paramTypes[rightExpr.name] = FunkType.IntType
                    }
                    
                    // If comparing parameter with float literal, infer parameter as Float
                    if (leftExpr is Identifier && paramTypes.containsKey(leftExpr.name) && 
                        rightExpr is Literal<*> && rightExpr.value is Float) {
                        paramTypes[leftExpr.name] = FunkType.FloatType
                    }
                    if (rightExpr is Identifier && paramTypes.containsKey(rightExpr.name) && 
                        leftExpr is Literal<*> && leftExpr.value is Float) {
                        paramTypes[rightExpr.name] = FunkType.FloatType
                    }
                    
                    // If comparing parameter with string literal, infer parameter as String
                    if (leftExpr is Identifier && paramTypes.containsKey(leftExpr.name) && 
                        rightExpr is Literal<*> && rightExpr.value is String) {
                        paramTypes[leftExpr.name] = FunkType.StringType
                    }
                    if (rightExpr is Identifier && paramTypes.containsKey(rightExpr.name) && 
                        leftExpr is Literal<*> && leftExpr.value is String) {
                        paramTypes[rightExpr.name] = FunkType.StringType
                    }
                }
                
                // String concatenation - infer both operands as String
                if (expr.operator == "++") {
                    // Handle member access expressions like a.name ++ "hello"
                    if (leftExpr is MemberAccess && leftExpr.receiver is Identifier) {
                        val receiver = leftExpr.receiver as Identifier
                        if (paramTypes.containsKey(receiver.name)) {
                            val paramName = receiver.name
                            val fieldName = leftExpr.member
                            val currentType = paramTypes[paramName]
                            if (currentType is FunkType.VarType) {
                                // Create record type with String field
                                val recordType = FunkType.RecordType(
                                    mapOf(fieldName to FunkType.StringType),
                                    rowVar = null
                                )
                                paramTypes[paramName] = recordType
                            } else if (currentType is FunkType.RecordType) {
                                // Update existing record type with String field
                                val updatedFields = currentType.fields + (fieldName to FunkType.StringType)
                                paramTypes[paramName] = FunkType.RecordType(updatedFields, currentType.rowVar)
                            }
                        }
                    }
                    
                    // Handle direct parameter usage like param ++ "hello"
                    if (leftExpr is Identifier && paramTypes.containsKey(leftExpr.name)) {
                        paramTypes[leftExpr.name] = FunkType.StringType
                    }
                    if (rightExpr is Identifier && paramTypes.containsKey(rightExpr.name)) {
                        paramTypes[rightExpr.name] = FunkType.StringType
                    }
                }
                
                // Recursively analyze operands
                extractInferredTypesFromBody(expr.left, paramTypes)
                extractInferredTypesFromBody(expr.right, paramTypes)
            }
            
            is IfExpression -> {
                extractInferredTypesFromBody(expr.condition, paramTypes)
                extractInferredTypesFromBody(expr.thenBranch, paramTypes)
                extractInferredTypesFromBody(expr.elseBranch, paramTypes)
            }
            
            is FunctionCall -> {
                extractInferredTypesFromBody(expr.function, paramTypes)
                extractInferredTypesFromBody(expr.arguments, paramTypes)
            }
            
            is RecordLiteral -> {
                for (entry in expr.entries) {
                    val entryValue = entry.value
                    if (entryValue != null) {
                        extractInferredTypesFromBody(entryValue, paramTypes)
                    }
                }
            }
            
            is DoExpression -> {
                extractInferredTypesFromBody(expr.result, paramTypes)
            }
            
            is MemberAccess -> {
                // Only process member access in post-processing if we can infer concrete types
                // Don't create new generic type variables that would override initial inference
                // The concrete type inference is handled by the binary operation cases above
                
                // Recursively analyze the receiver
                extractInferredTypesFromBody(expr.receiver, paramTypes)
            }
            
            else -> { /* Other expression types */ }
        }
    }
    
    /**
     * Infer the type of a field from its usage context
     */
    private fun inferFieldTypeFromContext(memberAccess: MemberAccess, paramTypes: Map<String, FunkType>): FunkType {
        // For now, return a fresh type variable for the field
        // In a more sophisticated implementation, we would analyze the parent expression
        // to infer the field type (e.g., from string concatenation, arithmetic, etc.)
        return FunkType.VarType(TypeVar.fresh())
    }
    
    /**
     * Analyze lambda body to infer parameter types from usage patterns (legacy method)
     */
    private fun inferParameterTypesFromBody(body: Expression, paramNames: List<String>): Map<String, FunkType> {
        val paramTypes = mutableMapOf<String, FunkType>()
        
        // Initialize with fresh type variables
        for (name in paramNames) {
            paramTypes[name] = FunkType.VarType(TypeVar.fresh())
        }
        
        // Analyze the body to infer more specific types
        analyzeExpressionForParameterTypes(body, paramTypes)
        
        return paramTypes
    }
    
    /**
     * Recursively analyze expression to infer parameter types from usage
     */
    private fun analyzeExpressionForParameterTypes(expr: Expression, paramTypes: MutableMap<String, FunkType>) {
        when (expr) {
            is MatchExpression -> {
                // If we're matching on a parameter, infer its type from the patterns
                val exprValue = expr.expression
                if (exprValue is Identifier) {
                    val varName = exprValue.name
                    if (paramTypes.containsKey(varName)) {
                        val constructors = mutableMapOf<String, FunkType?>()
                        var hasWildcard = false
                        
                        for (case in expr.cases) {
                            when (val pattern = case.pattern) {
                                is ConstructorPattern -> {
                                    constructors[pattern.constructor] = null // Simple constructor
                                }
                                is WildcardPattern -> {
                                    hasWildcard = true
                                }
                                is VariablePattern -> {
                                    hasWildcard = true // Variable patterns act like wildcards
                                }
                                else -> { /* Other patterns don't affect variant inference */ }
                            }
                        }
                        
                        if (constructors.isNotEmpty()) {
                            // Create variant type - open if wildcard present, closed otherwise
                            val rowVar = if (hasWildcard) RowVar.fresh("variant") else null
                            val variantType = FunkType.VariantType(constructors, rowVar)
                            paramTypes[varName] = variantType
                        }
                    }
                }
                
                // Recursively analyze case expressions
                for (case in expr.cases) {
                    analyzeExpressionForParameterTypes(case.expression, paramTypes)
                }
            }
            
            is BinaryOperation -> {
                // If parameters are used in arithmetic operations, infer as Int
                if (expr.operator in listOf("+", "-", "*", "/", "%")) {
                    val leftExpr = expr.left
                    val rightExpr = expr.right
                    if (leftExpr is Identifier && paramTypes.containsKey(leftExpr.name)) {
                        paramTypes[leftExpr.name] = FunkType.IntType
                    }
                    if (rightExpr is Identifier && paramTypes.containsKey(rightExpr.name)) {
                        paramTypes[rightExpr.name] = FunkType.IntType
                    }
                }
                
                // Recursively analyze operands
                analyzeExpressionForParameterTypes(expr.left, paramTypes)
                analyzeExpressionForParameterTypes(expr.right, paramTypes)
            }
            
            is FunctionCall -> {
                analyzeExpressionForParameterTypes(expr.function, paramTypes)
                analyzeExpressionForParameterTypes(expr.arguments, paramTypes)
            }
            
            is IfExpression -> {
                analyzeExpressionForParameterTypes(expr.condition, paramTypes)
                analyzeExpressionForParameterTypes(expr.thenBranch, paramTypes)
                analyzeExpressionForParameterTypes(expr.elseBranch, paramTypes)
            }
            
            is RecordLiteral -> {
                for (entry in expr.entries) {
                    val entryValue = entry.value
                    if (entryValue != null) {
                        analyzeExpressionForParameterTypes(entryValue, paramTypes)
                    }
                }
            }
            
            is DoExpression -> {
                analyzeExpressionForParameterTypes(expr.result, paramTypes)
            }
            
            else -> {
                // For other expression types, no specific parameter type inference
            }
        }
    }
    
    private fun inferIfExpression(ifExpr: IfExpression, context: TypeContext): FunkType {
        val conditionType = inferExpression(ifExpr.condition, context)
        Unification.unify(conditionType, FunkType.BooleanType)
        
        val thenType = inferExpression(ifExpr.thenBranch, context)
        val elseType = inferExpression(ifExpr.elseBranch, context)
        
        // Special handling for variant types - create closed variants when possible
        val resultType = when {
            thenType is FunkType.VariantType && elseType is FunkType.VariantType -> {
                // Both branches are variants - merge into a closed variant
                mergeVariantTypes(thenType, elseType)
            }
            thenType is FunkType.VariantType -> {
                // Then branch is variant, else is something else - try to unify
                try {
                    Unification.unify(thenType, elseType)
                    thenType
                } catch (e: Exception) {
                    // If unification fails, return the variant type
                    thenType
                }
            }
            elseType is FunkType.VariantType -> {
                // Else branch is variant, then is something else - try to unify  
                try {
                    Unification.unify(thenType, elseType)
                    elseType
                } catch (e: Exception) {
                    // If unification fails, return the variant type
                    elseType
                }
            }
            else -> {
                Unification.unify(thenType, elseType)
                thenType
            }
        }
        
        return resultType
    }
    
    private fun mergeVariantTypes(type1: FunkType.VariantType, type2: FunkType.VariantType): FunkType.VariantType {
        // Merge constructors from both variants
        val mergedConstructors = type1.constructors + type2.constructors
        
        // Always create closed variants by default unless explicitly asked otherwise
        // This represents a complete finite set of constructors
        return FunkType.VariantType(mergedConstructors, rowVar = null) // Always closed by default
    }
    
    private fun areSimpleConstructorVariants(variant: FunkType.VariantType): Boolean {
        // Check if all constructors are simple (no data)
        return variant.constructors.values.all { it == null }
    }
    
    private fun inferMatchExpression(matchExpr: MatchExpression, context: TypeContext): FunkType {
        // Get the expression type first
        val exprType = inferExpression(matchExpr.expression, context)
        
        // Determine if this is an explicitly typed variant or needs pattern inference
        val finalType = when (exprType) {
            is FunkType.VariantType -> {
                // Already a variant type (explicitly typed) - use it directly
                exprType
            }
            is FunkType.VarType -> {
                // Type variable - infer from patterns (bidirectional)
                val patternType = inferPatternTypeFromCases(matchExpr.cases)
                try {
                    Unification.unify(exprType, patternType)
                    patternType
                } catch (e: Exception) {
                    patternType // Use pattern type if unification fails
                }
            }
            else -> {
                // Other types - no exhaustiveness checking needed
                exprType
            }
        }
        
        // Check exhaustiveness based on type source
        if (finalType is FunkType.VariantType) {
            if (exprType is FunkType.VarType) {
                // Pattern-inferred variant - use stricter checking
                checkMatchExhaustivenessForInferred(matchExpr, finalType, context)
            } else {
                // Explicitly typed variant - use traditional checking
                checkMatchExhaustivenessForExplicit(matchExpr, finalType, context)
            }
        }
        
        // Infer result type from first case (all cases should have same type)
        if (matchExpr.cases.isNotEmpty()) {
            val firstCaseType = inferExpression(matchExpr.cases.first().expression, context)
            
            // Check that all cases have the same return type
            for (case in matchExpr.cases.drop(1)) {
                val caseType = inferExpression(case.expression, context)
                Unification.unify(firstCaseType, caseType)
            }
            
            return firstCaseType
        }
        
        return FunkType.VarType(TypeVar.fresh("match_result"))
    }
    
    /**
     * Infer the type that the match expression expects based on the patterns
     */
    private fun inferPatternTypeFromCases(cases: List<MatchCase>): FunkType {
        val constructors = mutableMapOf<String, FunkType?>()
        var hasWildcard = false
        
        for (case in cases) {
            when (val pattern = case.pattern) {
                is ConstructorPattern -> {
                    constructors[pattern.constructor] = null // Simple constructor for now
                }
                is WildcardPattern -> {
                    hasWildcard = true
                }
                is VariablePattern -> {
                    hasWildcard = true // Variable patterns act like wildcards
                }
                else -> {
                    // Other patterns don't contribute to variant type inference
                }
            }
        }
        
        return if (constructors.isNotEmpty()) {
            // Create variant type - open if wildcard present, closed otherwise
            val rowVar = if (hasWildcard) RowVar.fresh("variant") else null
            FunkType.VariantType(constructors, rowVar)
        } else {
            // If no constructors found, return a fresh type variable
            FunkType.VarType(TypeVar.fresh("pattern"))
        }
    }
    
    /**
     * Check exhaustiveness for explicitly typed variants (traditional checking)
     */
    private fun checkMatchExhaustivenessForExplicit(
        matchExpr: MatchExpression, 
        variantType: FunkType.VariantType, 
        context: TypeContext
    ) {
        val expectedConstructors = variantType.constructors.keys
        val matchedConstructors = mutableSetOf<String>()
        var hasWildcard = false
        var hasVariable = false
        
        // Analyze patterns and validate single wildcard/variable rule
        for (case in matchExpr.cases) {
            when (val pattern = case.pattern) {
                is ConstructorPattern -> {
                    matchedConstructors.add(pattern.constructor)
                }
                is WildcardPattern -> {
                    if (hasVariable) {
                        throw IllegalArgumentException("Cannot have both wildcard (_) and variable patterns in the same match expression")
                    }
                    hasWildcard = true
                }
                is VariablePattern -> {
                    if (hasWildcard) {
                        throw IllegalArgumentException("Cannot have both wildcard (_) and variable patterns in the same match expression")
                    }
                    hasVariable = true // Variable patterns act like wildcards
                }
                else -> {
                    // Other patterns (like RecordPattern) don't affect exhaustiveness
                }
            }
        }
        
        val missingConstructors = expectedConstructors - matchedConstructors
        val isClosed = variantType.rowVar == null // Closed if no row variable
        
        if (isClosed) {
            // For closed variants, check exhaustiveness and unreachable patterns
            if (!(hasWildcard || hasVariable) && missingConstructors.isNotEmpty()) {
                // Missing cases - exhaustiveness error
                throw IllegalArgumentException("Match expression is not exhaustive. Missing cases: ${missingConstructors.joinToString(", ")}")
            } else if ((hasWildcard || hasVariable) && missingConstructors.isEmpty()) {
                // All constructors covered + wildcard/variable = unreachable pattern
                val patternType = if (hasWildcard) "wildcard (_)" else "variable"
                throw IllegalArgumentException("Unreachable $patternType pattern. All constructors (${expectedConstructors.joinToString(", ")}) are already covered.")
            }
        }
        // For open variants, wildcards/variables are generally allowed
    }
    
    /**
     * Check exhaustiveness for pattern-inferred variants (based on wildcard presence)
     */
    private fun checkMatchExhaustivenessForInferred(
        matchExpr: MatchExpression, 
        variantType: FunkType.VariantType, 
        context: TypeContext
    ) {
        var hasWildcard = false
        var hasVariable = false
        
        // Analyze patterns and validate single wildcard/variable rule
        for (case in matchExpr.cases) {
            when (val pattern = case.pattern) {
                is WildcardPattern -> {
                    if (hasVariable) {
                        throw IllegalArgumentException("Cannot have both wildcard (_) and variable patterns in the same match expression")
                    }
                    hasWildcard = true
                }
                is VariablePattern -> {
                    if (hasWildcard) {
                        throw IllegalArgumentException("Cannot have both wildcard (_) and variable patterns in the same match expression")
                    }
                    hasVariable = true // Variable patterns act like wildcards
                }
                else -> { /* Constructor patterns are fine */ }
            }
        }
        
        // For pattern-inferred variants:
        // - No wildcard = closed variant = exhaustive by definition (all listed constructors must be covered)
        // - With wildcard = open variant = handles unknown constructors
        // This is the correct behavior - no additional checking needed for inferred variants
    }
    
    /**
     * Check match expression for exhaustiveness and unreachable patterns (legacy method)
     */
    private fun checkMatchExhaustiveness(
        matchExpr: MatchExpression, 
        exprType: FunkType, 
        context: TypeContext
    ) {
        when (exprType) {
            is FunkType.VariantType -> {
                val expectedConstructors = exprType.constructors.keys
                val matchedConstructors = mutableSetOf<String>()
                var hasWildcard = false
                var hasVariable = false
                
                // Analyze patterns and validate single wildcard/variable rule
                for (case in matchExpr.cases) {
                    when (val pattern = case.pattern) {
                        is ConstructorPattern -> {
                            matchedConstructors.add(pattern.constructor)
                        }
                        is WildcardPattern -> {
                            if (hasVariable) {
                                throw IllegalArgumentException("Cannot have both wildcard (_) and variable patterns in the same match expression")
                            }
                            hasWildcard = true
                        }
                        is VariablePattern -> {
                            if (hasWildcard) {
                                throw IllegalArgumentException("Cannot have both wildcard (_) and variable patterns in the same match expression")
                            }
                            hasVariable = true // Variable patterns act like wildcards
                        }
                        else -> {
                            // Other patterns (like RecordPattern) don't affect exhaustiveness
                        }
                    }
                }
                
                val missingConstructors = expectedConstructors - matchedConstructors
                val isClosed = exprType.rowVar == null // Closed if no row variable
                
                if (isClosed) {
                    // For closed variants, check exhaustiveness and unreachable patterns
                    if (!(hasWildcard || hasVariable) && missingConstructors.isNotEmpty()) {
                        // Missing cases - exhaustiveness error
                        throw IllegalArgumentException("Match expression is not exhaustive. Missing cases: ${missingConstructors.joinToString(", ")}")
                    } else if ((hasWildcard || hasVariable) && missingConstructors.isEmpty()) {
                        // All constructors covered + wildcard/variable = unreachable pattern
                        val patternType = if (hasWildcard) "wildcard" else "variable"
                        throw IllegalArgumentException("Unreachable $patternType pattern. All constructors (${expectedConstructors.joinToString(", ")}) are already covered.")
                    }
                } else {
                    // For open variants, check if we have useful patterns
                    if ((hasWildcard || hasVariable) && matchedConstructors.isEmpty()) {
                        // Only wildcard/variable with no constructors is suspicious but allowed
                    }
                    // Open variants are generally more permissive
                }
                
                // SPECIAL CASE: For anonymous variants inferred purely from patterns (no explicit type),
                // we should require a wildcard unless we're sure it's complete
                if (isAnonymousInferredVariant(exprType, matchExpr)) {
                    if (!(hasWildcard || hasVariable)) {
                        throw IllegalArgumentException("Match expression on inferred variant type may not be exhaustive. Consider adding a wildcard pattern (_) to handle unknown constructors, or provide an explicit type annotation.")
                    }
                }
            }
            is FunkType.TypeAlias -> {
                // Recursively check the aliased type
                checkMatchExhaustiveness(matchExpr, exprType.type, context)
            }
            else -> {
                // For non-variant types, no exhaustiveness checking needed
            }
        }
    }
    
    /**
     * Check if this variant type was inferred from match patterns rather than explicitly declared
     */
    private fun isAnonymousInferredVariant(variantType: FunkType.VariantType, matchExpr: MatchExpression): Boolean {
        // Check if we're matching on an identifier that could be an untyped parameter
        val matchedExpr = matchExpr.expression
        if (matchedExpr !is Identifier) {
            return false // Not matching on a simple variable
        }
        
        // Heuristic: If the variant type has exactly the same constructors as the match patterns,
        // and no more, it's likely inferred from the patterns rather than being an explicit type
        val patternConstructors = mutableSetOf<String>()
        for (case in matchExpr.cases) {
            when (val pattern = case.pattern) {
                is ConstructorPattern -> {
                    patternConstructors.add(pattern.constructor)
                }
                else -> { /* Non-constructor patterns don't count */ }
            }
        }
        
        // Additional check: if the variant is closed (rowVar == null) and has exactly the pattern constructors,
        // it's likely inferred rather than explicit (explicit types usually have more context)
        val isExactMatch = variantType.constructors.keys == patternConstructors && patternConstructors.isNotEmpty()
        val isClosed = variantType.rowVar == null
        
        // If it's closed and exactly matches patterns, it's probably inferred
        // If it's open, it was probably inferred with a wildcard
        return isExactMatch && isClosed
    }
    
    
    private fun inferMatchCase(case: MatchCase, scrutineeType: FunkType, context: TypeContext): FunkType {
        // Pattern matching would require more sophisticated analysis
        // For now, just infer the expression type
        return inferExpression(case.expression, context)
    }
    
    private fun inferPipeExpression(pipe: PipeExpression, context: TypeContext): FunkType {
        val leftType = inferExpression(pipe.left, context)
        val rightType = inferExpression(pipe.right, context)
        
        // Right side should be a function that can accept left as argument
        return when (rightType) {
            is FunkType.FunctionType -> {
                Unification.unify(rightType.paramType, leftType)
                rightType.returnType
            }
            else -> throw TypeError.UnificationError(rightType, FunkType.FunctionType(leftType, FunkType.VarType(TypeVar.fresh())))
        }
    }
    
    private fun inferDoExpression(doExpr: DoExpression, context: TypeContext): FunkType {
        // Process statements in sequence, updating context
        var currentContext = context
        
        for (statement in doExpr.statements) {
            when (statement) {
                is LetBinding -> {
                    // Infer the let binding expression
                    val exprType = inferExpression(statement.expression, currentContext)
                    
                    // Add the variable to the context for subsequent statements
                    val scheme = TypeScheme.monomorphic(exprType)
                    val newEnv = currentContext.env.extend(statement.variableName, scheme)
                    currentContext = currentContext.copy(env = newEnv)
                }
                is ExpressionStatement -> {
                    // Expression statements don't bind variables
                    inferExpression(statement.expression, currentContext)
                }
                else -> {
                    // Other statement types - process with current context
                    // This is a simplified approach
                }
            }
        }
        
        // Return type of the final expression with updated context
        return inferExpression(doExpr.result, currentContext)
    }
    
    private fun inferHandleExpression(handleExpr: HandleExpression, context: TypeContext): FunkType {
        // Infer the type of the handled expression
        val exprType = inferExpression(handleExpr.expression, context)
        
        // For each handle case, check that the effect pattern matches an available effect
        for (case in handleExpr.cases) {
            val effectName = case.effectName
            
            // Look up the effect in the environment
            val effectScheme = context.env.lookup(effectName)
            if (effectScheme == null) {
                throw TypeError.UnboundVariable("Unknown effect: $effectName")
            }
            
            // The effect should be a function type
            val effectType = instantiate(effectScheme)
            if (effectType !is FunkType.FunctionType) {
                throw TypeError.UnboundVariable("$effectName is not an effect")
            }
            
            // Check that the handler result type matches the effect result type
            val handlerType = inferExpression(case.result, context)
            try {
                Unification.unify(handlerType, effectType.returnType)
            } catch (e: Exception) {
                throw TypeError.UnboundVariable("Handler result type $handlerType does not match effect result type ${effectType.returnType}")
            }
        }
        
        // The handle expression has the same type as the handled expression
        return exprType
    }
    
    private fun inferFunctionCall(call: FunctionCall, context: TypeContext): FunkType {
        // Check if this is an effect invocation
        val function = call.function
        if (function is Identifier) {
            val effect = context.env.lookupEffect(function.name)
            if (effect != null) {
                return inferEffectInvocation(call, effect, context)
            }
        }
        
        val functionType = inferExpression(call.function, context)
        val argumentType = inferExpression(call.arguments, context)
        
        return when (functionType) {
            is FunkType.FunctionType -> {
                // Check if arguments match function parameter type
                when (argumentType) {
                    is FunkType.RecordType -> {
                        when (functionType.paramType) {
                            is FunkType.RecordType -> {
                                // Check if all required fields are provided
                                val providedFields = argumentType.fields.keys
                                val requiredFields = functionType.paramType.fields.keys
                                
                                if (requiredFields.all { it in providedFields }) {
                                    // Full application - return function result type
                                    functionType.returnType
                                } else if (providedFields.all { it in requiredFields }) {
                                    // Partial application - return new function with remaining parameters
                                    val remainingFields = functionType.paramType.fields.filterKeys { it !in providedFields }
                                    if (remainingFields.isEmpty()) {
                                        functionType.returnType
                                    } else {
                                        FunkType.FunctionType(
                                            FunkType.RecordType(remainingFields),
                                            functionType.returnType
                                        )
                                    }
                                } else {
                                    throw TypeError.UnificationError(argumentType, functionType.paramType)
                                }
                            }
                            else -> {
                                Unification.unify(argumentType, functionType.paramType)
                                functionType.returnType
                            }
                        }
                    }
                    else -> {
                        Unification.unify(argumentType, functionType.paramType)
                        functionType.returnType
                    }
                }
            }
            else -> throw TypeError.UnificationError(functionType, FunkType.FunctionType(argumentType, FunkType.VarType(TypeVar.fresh())))
        }
    }
    
    private fun inferRecordLiteral(record: RecordLiteral, context: TypeContext): FunkType {
        val fields = record.entries.associate { entry ->
            val value = entry.value
            val fieldType = if (value != null) {
                // Full syntax: name = value
                inferExpression(value, context)
            } else {
                // Punned syntax: name (expands to name = name)
                val scheme = context.env.lookup(entry.name)
                    ?: throw TypeError.UnboundVariable("Variable '${entry.name}' not found for punned record entry")
                instantiate(scheme)
            }
            entry.name to fieldType
        }
        return FunkType.RecordType(fields)
    }
    
    private fun inferMemberAccess(access: MemberAccess, context: TypeContext): FunkType {
        // Check if this is a qualified effect call (e.g., Logging.log)
        val receiver = access.receiver
        if (receiver is Identifier) {
            val receiverName = receiver.name
            val effect = context.env.lookupEffect(receiverName)
            if (effect != null) {
                // Look up the qualified effect operation (e.g., "Logging.log")
                val qualifiedName = "${receiverName}.${access.member}"
                val methodScheme = context.env.lookup(qualifiedName)
                if (methodScheme != null) {
                    return instantiate(methodScheme)
                } else {
                    throw TypeError.UnboundVariable("Effect operation '${access.member}' not found in effect '${receiverName}'")
                }
            }
        }
        
        // Otherwise, treat as regular record field access
        val recordType = inferExpression(access.receiver, context)
        
        return when (recordType) {
            is FunkType.RecordType -> {
                recordType.fields[access.member] 
                    ?: throw TypeError.UnboundVariable("Field '${access.member}' not found in record")
            }
            is FunkType.VarType -> {
                // Create a fresh type variable for the field type
                val fieldType = FunkType.VarType(TypeVar.fresh())
                // Create a closed record type by default (no rowVar)
                val expectedRecordType = FunkType.RecordType(
                    mapOf(access.member to fieldType),
                    rowVar = null // Closed record by default
                )
                val substitution = Unification.unify(recordType, expectedRecordType)
                // Apply the substitution to the field type and return it
                // This ensures the return type is the same as the field type
                val resultType = substitution.apply(fieldType)
                resultType
            }
            else -> throw TypeError.UnificationError(recordType, FunkType.RecordType(mapOf(access.member to FunkType.VarType(TypeVar.fresh()))))
        }
    }
    
    private fun inferRecordUpdate(update: RecordUpdate, context: TypeContext): FunkType {
        val recordType = inferExpression(update.record, context)
        val updateFields = update.updates.associate { entry ->
            val value = entry.value
            val fieldType = if (value != null) {
                inferExpression(value, context)
            } else {
                // Punned syntax: name (expands to name = name)
                val scheme = context.env.lookup(entry.name)
                    ?: throw TypeError.UnboundVariable("Variable '${entry.name}' not found for punned record entry")
                instantiate(scheme)
            }
            entry.name to fieldType
        }
        
        return when (recordType) {
            is FunkType.RecordType -> {
                // Merge existing fields with updates
                val newFields = recordType.fields + updateFields
                FunkType.RecordType(newFields, recordType.rowVar)
            }
            else -> throw TypeError.UnificationError(recordType, FunkType.RecordType(updateFields))
        }
    }
    
    private fun inferBinaryOperation(binOp: BinaryOperation, context: TypeContext): FunkType {
        val leftType = inferExpression(binOp.left, context)
        val rightType = inferExpression(binOp.right, context)
        
        return when (binOp.operator) {
            "+", "-", "*", "/" -> {
                // Arithmetic operations
                when {
                    leftType == FunkType.IntType && rightType == FunkType.IntType -> FunkType.IntType
                    leftType == FunkType.FloatType && rightType == FunkType.FloatType -> FunkType.FloatType
                    leftType == FunkType.IntType && rightType == FunkType.FloatType -> FunkType.FloatType
                    leftType == FunkType.FloatType && rightType == FunkType.IntType -> FunkType.FloatType
                    leftType == FunkType.StringType && rightType == FunkType.StringType && binOp.operator == "+" -> FunkType.StringType
                    else -> {
                        // Try to unify with numeric types
                        try {
                            Unification.unify(leftType, FunkType.IntType)
                            Unification.unify(rightType, FunkType.IntType)
                            FunkType.IntType
                        } catch (e: TypeError.UnificationError) {
                            try {
                                Unification.unify(leftType, FunkType.FloatType)
                                Unification.unify(rightType, FunkType.FloatType)
                                FunkType.FloatType
                            } catch (e2: TypeError.UnificationError) {
                                throw TypeError.UnificationError(leftType, rightType)
                            }
                        }
                    }
                }
            }
            "++" -> {
                // String concatenation
                val leftSubst = Unification.unify(leftType, FunkType.StringType)
                val rightSubst = Unification.unify(rightType, FunkType.StringType)
                // Apply substitutions (though they don't affect the return type in this case)
                FunkType.StringType
            }
            "==", "!=", "<", ">", "<=", ">=" -> {
                // Comparison operations
                Unification.unify(leftType, rightType)
                FunkType.BooleanType
            }
            "&&", "||" -> {
                // Logical operations
                Unification.unify(leftType, FunkType.BooleanType)
                Unification.unify(rightType, FunkType.BooleanType)
                FunkType.BooleanType
            }
            else -> throw TypeError.UnboundVariable("Unsupported binary operator: ${binOp.operator}")
        }
    }
    
    private fun inferVariantConstruction(constructor: VariantConstruction, context: TypeContext): FunkType {
        val value = constructor.value
        
        if (value != null) {
            // Variant constructor with data: Ok { value = 42 }
            val valueType = inferExpression(value, context)
            
            // Create a variant type with this constructor and its data type
            val constructors = mapOf(constructor.constructor to valueType)
            return FunkType.VariantType(constructors, rowVar = null) // Closed variant
        } else {
            // Simple variant constructor: Ok (no data)
            // This should be handled by inferIdentifier, but just in case
            val constructors = mapOf(constructor.constructor to null)
            return FunkType.VariantType(constructors, rowVar = null) // Closed variant
        }
    }
    
    private fun inferBlockExpression(block: BlockExpression, context: TypeContext): FunkType {
        // Create new scope for the block
        val blockContext = context.copy(env = context.env.copy())
        
        // Process all statements (for side effects/bindings)
        for (statement in block.statements) {
            inferStatement(statement, blockContext)
        }
        
        // Return type of the final expression
        return inferExpression(block.result, blockContext)
    }
    
    private fun inferStatement(stmt: Statement, context: TypeContext) {
        when (stmt) {
            is LetBinding -> {
                val type = inferExpression(stmt.expression, context)
                val scheme = TypeScheme.monomorphic(type)
                context.env.extend(stmt.variableName, scheme)
            }
            is ExpressionStatement -> {
                inferExpression(stmt.expression, context)
            }
            is ResumeStatement -> {
                inferExpression(stmt.expression, context)
            }
            is TypeDeclaration -> {
                // Type declarations are handled at the module level
            }
        }
    }
    
    private fun inferStatementWithContext(stmt: Statement, context: TypeContext): TypeContext {
        return when (stmt) {
            is LetBinding -> {
                val type = inferExpression(stmt.expression, context)
                val scheme = TypeScheme.monomorphic(type)
                val newEnv = context.env.extend(stmt.variableName, scheme)
                context.copy(env = newEnv)
            }
            is ExpressionStatement -> {
                inferExpression(stmt.expression, context)
                context
            }
            is ResumeStatement -> {
                inferExpression(stmt.expression, context)
                context
            }
            is TypeDeclaration -> {
                // Type declarations are handled at the module level
                context
            }
            else -> {
                // Other statements don't affect typing
                context
            }
        }
    }
    
    // Helper functions
    
    private fun convertTypeAnnotation(annotation: TypeAnnotation): FunkType = when (annotation) {
        is SimpleType -> when (annotation.name) {
            "Int" -> FunkType.IntType
            "Float" -> FunkType.FloatType
            "String" -> FunkType.StringType
            "Boolean" -> FunkType.BooleanType
            else -> FunkType.VarType(TypeVar(annotation.name))
        }
        
        is RecordType -> convertRecordType(annotation)
        is VariantType -> convertVariantType(annotation)
        is FunctionType -> FunkType.FunctionType(
            convertTypeAnnotation(annotation.paramType),
            convertTypeAnnotation(annotation.returnType)
        )
        
        else -> throw TypeError.UnboundVariable("Unsupported type annotation: ${annotation::class.simpleName}")
    }
    
    private fun convertRecordType(recordType: RecordType): FunkType.RecordType {
        val fields = recordType.entries.associate { 
            it.name to convertTypeAnnotation(it.type) 
        }
        val rowVar = if (recordType.restField != null) RowVar.fresh() else null
        return FunkType.RecordType(fields, rowVar)
    }
    
    private fun convertVariantType(variantType: VariantType): FunkType.VariantType {
        val constructors = variantType.constructors.associate { constructor ->
            constructor.name to constructor.recordType?.let { convertRecordType(it) }
        }
        val rowVar = if (variantType.isOpen) RowVar.fresh() else null
        return FunkType.VariantType(constructors, rowVar)
    }
    
    private fun convertTypeDefinition(definition: TypeDefinition): FunkType = when (definition) {
        is RecordType -> convertRecordType(definition)
        is VariantType -> convertVariantType(definition)
        else -> throw TypeError.UnboundVariable("Unsupported type definition: ${definition::class.simpleName}")
    }
    
    private fun bindRecordParameters(recordType: RecordType, context: TypeContext): TypeContext {
        val newEnv = recordType.entries.fold(context.env) { env, entry ->
            env.extendType(entry.name, convertTypeAnnotation(entry.type))
        }
        return context.copy(env = newEnv)
    }
    
    private fun bindUntypedParameters(
        names: List<String>,
        paramTypes: Map<String, FunkType>,
        context: TypeContext
    ): TypeContext {
        val newEnv = names.fold(context.env) { env, name ->
            env.extendType(name, paramTypes[name]!!)
        }
        return context.copy(env = newEnv)
    }
    
    private fun collectEffects(expr: Expression): Set<Effect> {
        // Simplified effect collection - would be more sophisticated in full implementation
        return emptySet()
    }
    
    private fun generalize(env: TypeEnvironment, type: FunkType): TypeScheme {
        // Find free type variables not bound in environment
        val freeVars = freeTypeVars(type) - freeTypeVarsInEnv(env)
        val freeRowVars = freeRowVars(type) - freeRowVarsInEnv(env)
        
        return TypeScheme(freeVars, freeRowVars, emptySet(), type)
    }
    
    private fun instantiate(scheme: TypeScheme): FunkType {
        val typeSubst: Map<TypeVar, FunkType> = scheme.typeVars.associate { 
            it to FunkType.VarType(TypeVar.fresh()) 
        }
        val rowSubst: Map<RowVar, RowVar> = scheme.rowVars.associate { 
            it to RowVar.fresh() 
        }
        return substituteInType(scheme.type, typeSubst, rowSubst)
    }
    
    private fun freeTypeVars(type: FunkType): Set<TypeVar> = when (type) {
        is FunkType.VarType -> setOf(type.variable)
        is FunkType.RecordType -> type.fields.values.flatMap { freeTypeVars(it) }.toSet()
        is FunkType.VariantType -> type.constructors.values.filterNotNull().flatMap { freeTypeVars(it) }.toSet()
        is FunkType.FunctionType -> freeTypeVars(type.paramType) + freeTypeVars(type.returnType)
        is FunkType.TypeConstructor -> type.args.flatMap { freeTypeVars(it) }.toSet()
        is FunkType.TypeAlias -> freeTypeVars(type.type)
        else -> emptySet()
    }
    
    private fun freeRowVars(type: FunkType): Set<RowVar> = when (type) {
        is FunkType.RecordType -> if (type.rowVar != null) setOf(type.rowVar) else emptySet()
        is FunkType.VariantType -> if (type.rowVar != null) setOf(type.rowVar) else emptySet()
        is FunkType.FunctionType -> freeRowVars(type.paramType) + freeRowVars(type.returnType)
        else -> emptySet()
    }
    
    private fun freeTypeVarsInEnv(env: TypeEnvironment): Set<TypeVar> {
        return env.bindings.values.flatMap { scheme ->
            freeTypeVars(scheme.type) - scheme.typeVars
        }.toSet()
    }
    
    private fun freeRowVarsInEnv(env: TypeEnvironment): Set<RowVar> {
        return env.bindings.values.flatMap { scheme ->
            freeRowVars(scheme.type) - scheme.rowVars
        }.toSet()
    }
    
    private fun substituteInType(
        type: FunkType, 
        typeSubst: Map<TypeVar, FunkType>, 
        rowSubst: Map<RowVar, RowVar>
    ): FunkType = when (type) {
        is FunkType.VarType -> typeSubst[type.variable] ?: type
        is FunkType.RecordType -> FunkType.RecordType(
            type.fields.mapValues { substituteInType(it.value, typeSubst, rowSubst) },
            type.rowVar?.let { rowSubst[it] ?: it }
        )
        is FunkType.VariantType -> FunkType.VariantType(
            type.constructors.mapValues { it.value?.let { substituteInType(it, typeSubst, rowSubst) } },
            type.rowVar?.let { rowSubst[it] ?: it }
        )
        is FunkType.FunctionType -> FunkType.FunctionType(
            substituteInType(type.paramType, typeSubst, rowSubst),
            substituteInType(type.returnType, typeSubst, rowSubst)
        )
        else -> type
    }
    
    // ========================================
    // CONSTRAINT-BASED INFERENCE METHODS
    // ========================================
    
    private fun inferIdentifierWithConstraints(identifier: Identifier, context: TypeContext): InferenceResult {
        return context.env.lookup(identifier.name)?.let { scheme ->
            InferenceResultHelper.simple(instantiate(scheme))
        } ?: run {
            // Check for implicit variant constructor
            if (identifier.name.first().isUpperCase()) {
                // Register the variant in the global registry
                context.registerVariant(identifier.name, null)
                
                // Return the global variant type (which includes this constructor)
                val globalVariantType = context.getGlobalVariantType()
                InferenceResultHelper.simple(globalVariantType)
            } else {
                throw TypeError.UnboundVariable("Unbound variable: ${identifier.name}")
            }
        }
    }
    
    private fun inferLambdaWithConstraints(lambda: LambdaExpression, context: TypeContext): InferenceResult {
        val parameters = lambda.parameters
        return when (parameters) {
            is TypedParameters -> {
                val paramType = convertRecordType(parameters.recordType)
                val paramContext = bindRecordParameters(parameters.recordType, context)
                val bodyResult = inferExpressionWithConstraints(lambda.body, paramContext)
                val funcType = FunkType.FunctionType(paramType, bodyResult.type)
                InferenceResult(funcType, bodyResult.constraints)
            }
            is UntypedParameters -> {
                // Create fresh type variables for parameters
                val paramTypes = parameters.names.associate { name ->
                    name to FunkType.VarType(TypeVar.fresh())
                }
                val paramType = FunkType.RecordType(paramTypes)
                
                // Create context with type variables
                val paramContext = bindUntypedParameters(parameters.names, paramTypes, context)
                
                // Infer body with constraints
                val bodyResult = inferExpressionWithConstraints(lambda.body, paramContext)
                
                // Create function type
                val funcType = FunkType.FunctionType(paramType, bodyResult.type)
                
                // Return result with all constraints from body
                InferenceResult(funcType, bodyResult.constraints)
            }
        }
    }
    
    private fun inferMemberAccessWithConstraints(access: MemberAccess, context: TypeContext): InferenceResult {
        // Check if this is a qualified effect call (e.g., Logging.log)
        val receiver = access.receiver
        if (receiver is Identifier) {
            val receiverName = receiver.name
            val effect = context.env.lookupEffect(receiverName)
            if (effect != null) {
                // Look up the qualified effect operation (e.g., "Logging.log")
                val qualifiedName = "${receiverName}.${access.member}"
                val methodScheme = context.env.lookup(qualifiedName)
                if (methodScheme != null) {
                    val methodType = instantiate(methodScheme)
                    return InferenceResult(methodType, emptySet())
                } else {
                    throw TypeError.UnboundVariable("Effect operation '${access.member}' not found in effect '${receiverName}'")
                }
            }
        }
        
        // Otherwise, use constraint-based member access for record fields
        // Infer the receiver type
        val receiverResult = inferExpressionWithConstraints(access.receiver, context)
        
        // Create a fresh type variable for the field type
        val fieldType = FunkType.VarType(TypeVar.fresh())
        
        // Create a constraint that the receiver must have this field
        val fieldConstraint = Constraint.HasField(receiverResult.type, access.member, fieldType)
        
        // Return the field type with the constraint
        return InferenceResult(fieldType, receiverResult.constraints + fieldConstraint)
    }
    
    private fun inferBinaryOperationWithConstraints(binOp: BinaryOperation, context: TypeContext): InferenceResult {
        val leftResult = inferExpressionWithConstraints(binOp.left, context)
        val rightResult = inferExpressionWithConstraints(binOp.right, context)
        
        val allConstraints = leftResult.constraints + rightResult.constraints
        
        return when (binOp.operator) {
            "+", "-", "*", "/", "%" -> {
                // Arithmetic operations: both operands must be Int, result is Int
                val leftConstraint = Constraint.Equality(leftResult.type, FunkType.IntType)
                val rightConstraint = Constraint.Equality(rightResult.type, FunkType.IntType)
                InferenceResult(FunkType.IntType, allConstraints + leftConstraint + rightConstraint)
            }
            "++" -> {
                // String concatenation: both operands must be String, result is String
                val leftConstraint = Constraint.Equality(leftResult.type, FunkType.StringType)
                val rightConstraint = Constraint.Equality(rightResult.type, FunkType.StringType)
                InferenceResult(FunkType.StringType, allConstraints + leftConstraint + rightConstraint)
            }
            "==", "!=" -> {
                // Equality: both operands must have same type, result is Boolean
                val equalityConstraint = Constraint.Equality(leftResult.type, rightResult.type)
                InferenceResult(FunkType.BooleanType, allConstraints + equalityConstraint)
            }
            "<", "<=", ">", ">=" -> {
                // Comparison: both operands must be Int, result is Boolean
                val leftConstraint = Constraint.Equality(leftResult.type, FunkType.IntType)
                val rightConstraint = Constraint.Equality(rightResult.type, FunkType.IntType)
                InferenceResult(FunkType.BooleanType, allConstraints + leftConstraint + rightConstraint)
            }
            "&&", "||" -> {
                // Logical operations: both operands must be Boolean, result is Boolean
                val leftConstraint = Constraint.Equality(leftResult.type, FunkType.BooleanType)
                val rightConstraint = Constraint.Equality(rightResult.type, FunkType.BooleanType)
                InferenceResult(FunkType.BooleanType, allConstraints + leftConstraint + rightConstraint)
            }
            else -> throw TypeError.UnboundVariable("Unsupported binary operator: ${binOp.operator}")
        }
    }
    
    private fun inferFunctionCallWithConstraints(call: FunctionCall, context: TypeContext): InferenceResult {
        // Check if this is an effect invocation
        val function = call.function
        if (function is Identifier) {
            val effect = context.env.lookupEffect(function.name)
            if (effect != null) {
                return inferEffectInvocationWithConstraints(call, effect, context)
            }
        }
        
        val funcResult = inferExpressionWithConstraints(call.function, context)
        val argResult = inferExpressionWithConstraints(call.arguments, context)
        
        // Create fresh type variable for return type
        val returnType = FunkType.VarType(TypeVar.fresh())
        
        // Use partial application constraint instead of strict equality
        val partialAppConstraint = Constraint.PartialApplication(funcResult.type, argResult.type, returnType)
        
        val allConstraints = funcResult.constraints + argResult.constraints + partialAppConstraint
        return InferenceResult(returnType, allConstraints)
    }
    
    private fun inferEffectInvocationWithConstraints(call: FunctionCall, effect: Effect, context: TypeContext): InferenceResult {
        // Look up the effect's function type
        val effectScheme = context.env.lookup(effect.name)
        if (effectScheme == null) {
            throw TypeError.UnboundVariable("Effect ${effect.name} not found in environment")
        }
        
        val effectType = instantiate(effectScheme)
        if (effectType !is FunkType.FunctionType) {
            throw TypeError.UnboundVariable("${effect.name} is not a function type")
        }
        
        // Infer the arguments with constraints
        val argResult = inferExpressionWithConstraints(call.arguments, context)
        
        // Create constraint that arguments match effect input type
        val argConstraint = Constraint.Equality(argResult.type, effectType.paramType)
        
        val allConstraints = argResult.constraints + argConstraint
        return InferenceResult(effectType.returnType, allConstraints)
    }
    
    private fun inferRecordLiteralWithConstraints(record: RecordLiteral, context: TypeContext): InferenceResult {
        val fieldResults = mutableMapOf<String, InferenceResult>()
        var allConstraints = emptySet<Constraint>()
        
        for (entry in record.entries) {
            val valueResult = if (entry.value != null) {
                inferExpressionWithConstraints(entry.value as Expression, context)
            } else {
                // Punned field - look up the identifier
                inferExpressionWithConstraints(Identifier(entry.name), context)
            }
            fieldResults[entry.name] = valueResult
            allConstraints += valueResult.constraints
        }
        
        val fieldTypes = fieldResults.mapValues { it.value.type }
        val recordType = FunkType.RecordType(fieldTypes, rowVar = null) // Closed by default
        
        return InferenceResult(recordType, allConstraints)
    }
    
    // Placeholder implementations for other methods (can be implemented as needed)
    private fun inferRecordUpdateWithConstraints(update: RecordUpdate, context: TypeContext): InferenceResult {
        // Infer the base record type with constraints
        val recordResult = inferExpressionWithConstraints(update.record, context)
        var allConstraints = recordResult.constraints
        
        // Infer types of update values with constraints
        val updateTypes = mutableMapOf<String, FunkType>()
        for (entry in update.updates) {
            if (entry.value != null) {
                val valueResult = inferExpressionWithConstraints(entry.value!!, context)
                updateTypes[entry.name] = valueResult.type
                allConstraints += valueResult.constraints
            } else {
                // Punned field - look up in context
                val scheme = context.env.lookup(entry.name)
                    ?: throw RuntimeException("Unbound variable: ${entry.name}")
                updateTypes[entry.name] = scheme.type
            }
        }
        
        // Create constraints for the record to have the updated fields
        val fieldConstraints = updateTypes.map { (name, type) ->
            Constraint.HasField(recordResult.type, name, type)
        }
        
        // The result type is the same as the input record type (records are immutable)
        return InferenceResult(recordResult.type, allConstraints + fieldConstraints)
    }
    
    private fun inferVariantConstructionWithConstraints(construction: VariantConstruction, context: TypeContext): InferenceResult {
        val value = construction.value
        if (value != null) {
            // Variant with data - infer the value type with constraints
            val valueResult = inferExpressionWithConstraints(value, context)
            
            // Register the variant in the global registry
            context.registerVariant(construction.constructor, valueResult.type)
            
            // Return the global variant type (which includes this constructor)
            val globalVariantType = context.getGlobalVariantType()
            return InferenceResult(globalVariantType, valueResult.constraints)
        } else {
            // Simple variant constructor with no data
            // Register the variant in the global registry
            context.registerVariant(construction.constructor, null)
            
            // Return the global variant type (which includes this constructor)
            val globalVariantType = context.getGlobalVariantType()
            return InferenceResult(globalVariantType, emptySet())
        }
    }
    
    private fun inferIfExpressionWithConstraints(ifExpr: IfExpression, context: TypeContext): InferenceResult {
        // Infer condition with constraints
        val conditionResult = inferExpressionWithConstraints(ifExpr.condition, context)
        
        // Infer then and else branches with constraints
        val thenResult = inferExpressionWithConstraints(ifExpr.thenBranch, context)
        val elseResult = inferExpressionWithConstraints(ifExpr.elseBranch, context)
        
        // Create constraint for condition
        val conditionConstraint = Constraint.Equality(conditionResult.type, FunkType.BooleanType)
        
        // Handle branch type unification - special case for variants
        val (resultType, branchConstraints) = when {
            thenResult.type is FunkType.VariantType && elseResult.type is FunkType.VariantType -> {
                // Both branches are variants - merge them directly
                val mergedType = mergeVariantTypes(thenResult.type, elseResult.type)
                Pair(mergedType, emptySet<Constraint>())
            }
            else -> {
                // Regular unification constraint
                val branchConstraint = Constraint.Equality(thenResult.type, elseResult.type)
                Pair(thenResult.type, setOf(branchConstraint))
            }
        }
        
        // Collect all constraints
        val allConstraints = conditionResult.constraints + 
                           thenResult.constraints + 
                           elseResult.constraints + 
                           conditionConstraint + 
                           branchConstraints
        
        return InferenceResult(resultType, allConstraints)
    }
    
    private fun inferMatchExpressionWithConstraints(matchExpr: MatchExpression, context: TypeContext): InferenceResult {
        // Infer the expression being matched with constraints
        val exprResult = inferExpressionWithConstraints(matchExpr.expression, context)
        var allConstraints = exprResult.constraints
        
        // Collect all constructor constraints to build the variant type
        val constructorConstraints = mutableSetOf<Constraint.HasConstructor>()
        val hasWildcard = matchExpr.cases.any { it.pattern is WildcardPattern }
        
        // Infer each case with constraints, including pattern variable binding
        val caseTypes = mutableListOf<FunkType>()
        for (case in matchExpr.cases) {
            // Extract pattern variables and create extended context
            val (patternConstraints, extendedContext) = inferPatternWithConstraints(
                case.pattern, 
                exprResult.type, 
                context
            )
            
            // Collect constructor constraints for variant type building, but don't add them to allConstraints yet
            // We'll create a unified variant type instead
            constructorConstraints += patternConstraints.filterIsInstance<Constraint.HasConstructor>()
            
            // Add non-constructor constraints (like field constraints from record patterns)
            allConstraints += patternConstraints.filterNot { it is Constraint.HasConstructor }
            
            // Infer case expression with extended context (includes pattern variables)
            val caseResult = inferExpressionWithConstraints(case.expression, extendedContext)
            caseTypes.add(caseResult.type)
            allConstraints += caseResult.constraints
        }
        
        // Use the global variant type that includes all registered constructors
        if (constructorConstraints.isNotEmpty()) {
            // If there's a wildcard pattern, mark variants as open
            if (hasWildcard) {
                context.markVariantsAsOpen()
            }
            
            // The global variant type already includes all the constructors we've registered
            val globalVariantType = context.getGlobalVariantType()
            
            // Add constraint that matched expression has the global variant type
            val variantConstraint = Constraint.Equality(exprResult.type, globalVariantType)
            allConstraints += variantConstraint
        }
        
        // All cases must have the same type
        val resultType = caseTypes.firstOrNull() ?: FunkType.VarType(TypeVar.fresh())
        val unificationConstraints = caseTypes.drop(1).map { caseType ->
            Constraint.Equality(resultType, caseType)
        }
        
        return InferenceResult(resultType, allConstraints + unificationConstraints)
    }
    
    /**
     * Infer pattern constraints and extract pattern variables
     * Returns (constraints, extended context with pattern variables)
     */
    private fun inferPatternWithConstraints(
        pattern: Pattern, 
        matchedType: FunkType, 
        context: TypeContext
    ): Pair<Set<Constraint>, TypeContext> {
        return when (pattern) {
            is WildcardPattern -> {
                // Wildcard matches anything, no constraints or variables
                Pair(emptySet(), context)
            }
            
            is VariablePattern -> {
                // Variable pattern binds the matched type to the variable
                val scheme = TypeScheme.monomorphic(matchedType)
                val extendedEnv = context.env.extend(pattern.name, scheme)
                val extendedContext = context.copy(env = extendedEnv)
                Pair(emptySet(), extendedContext)
            }
            
            is ConstructorPattern -> {
                // Constructor pattern: Red {val} -> constraint that matchedType has Red constructor
                val constraints = mutableSetOf<Constraint>()
                var extendedContext = context
                
                if (pattern.recordPattern != null) {
                    // Constructor with data: Red {val}
                    val recordPattern = pattern.recordPattern!!
                    val (recordConstraints, recordContext, fieldTypes) = inferRecordPatternWithConstraints(
                        recordPattern, 
                        context
                    )
                    constraints += recordConstraints
                    extendedContext = recordContext
                    
                    // Create constraint that matchedType has this constructor with the record type
                    // Use the actual field types from pattern inference
                    val recordType = FunkType.RecordType(fieldTypes, rowVar = null)
                    
                    // Register the variant in the global registry
                    context.registerVariant(pattern.constructor, recordType)
                    
                    val constructorConstraint = Constraint.HasConstructor(
                        matchedType, 
                        pattern.constructor, 
                        recordType
                    )
                    constraints += constructorConstraint
                } else {
                    // Simple constructor: Red
                    // Register the variant in the global registry
                    context.registerVariant(pattern.constructor, null)
                    
                    val constructorConstraint = Constraint.HasConstructor(
                        matchedType, 
                        pattern.constructor, 
                        null
                    )
                    constraints += constructorConstraint
                }
                
                Pair(constraints, extendedContext)
            }
            
            is RecordPattern -> {
                // Direct record pattern matching
                val (constraints, context, _) = inferRecordPatternWithConstraints(pattern, context)
                Pair(constraints, context)
            }
            
            else -> {
                // Unknown pattern type
                Pair(emptySet(), context)
            }
        }
    }
    
    /**
     * Infer constraints for record patterns and extract variables
     * Returns (constraints, extended context, field types map)
     */
    private fun inferRecordPatternWithConstraints(
        recordPattern: RecordPattern, 
        context: TypeContext
    ): Triple<Set<Constraint>, TypeContext, Map<String, FunkType>> {
        var extendedContext = context
        val constraints = mutableSetOf<Constraint>()
        val fieldTypes = mutableMapOf<String, FunkType>()
        
        for (entry in recordPattern.entries) {
            if (entry.pattern == null) {
                // Simple field binding: {val} -> bind val to a fresh type variable
                val fieldType = FunkType.VarType(TypeVar.fresh())
                fieldTypes[entry.name] = fieldType
                val scheme = TypeScheme.monomorphic(fieldType)
                val extendedEnv = extendedContext.env.extend(entry.name, scheme)
                extendedContext = extendedContext.copy(env = extendedEnv)
            } else {
                // Nested pattern: {val = SomePattern} -> recursively infer
                val fieldType = FunkType.VarType(TypeVar.fresh())
                fieldTypes[entry.name] = fieldType
                val nestedPattern = entry.pattern!!
                val (nestedConstraints, nestedContext) = inferPatternWithConstraints(
                    nestedPattern, 
                    fieldType, 
                    extendedContext
                )
                constraints += nestedConstraints
                extendedContext = nestedContext
            }
        }
        
        return Triple(constraints, extendedContext, fieldTypes)
    }
    
    /**
     * Create a record type from a record pattern for constraint generation
     */
    private fun createRecordTypeFromPattern(recordPattern: RecordPattern, context: TypeContext): FunkType {
        val fields = mutableMapOf<String, FunkType>()
        
        for (entry in recordPattern.entries) {
            if (entry.pattern == null) {
                // Simple field: {val} -> create fresh type variable
                fields[entry.name] = FunkType.VarType(TypeVar.fresh())
            } else {
                // Nested pattern: would need recursive type creation
                // For now, use fresh type variable
                fields[entry.name] = FunkType.VarType(TypeVar.fresh())
            }
        }
        
        return FunkType.RecordType(fields, rowVar = null) // Closed record
    }
    
    private fun inferPipeExpressionWithConstraints(pipe: PipeExpression, context: TypeContext): InferenceResult {
        // Infer left side with constraints
        val leftResult = inferExpressionWithConstraints(pipe.left, context)
        
        // Transform right side by replacing @ placeholders with left value
        val transformedRight = transformPipeExpression(pipe.right, pipe.left)
        
        // Infer the transformed right side
        val rightResult = inferExpressionWithConstraints(transformedRight, context)
        
        // The result type is the type of the transformed right side
        return InferenceResult(rightResult.type, leftResult.constraints + rightResult.constraints)
    }
    
    /**
     * Transform pipe expressions by replacing @ placeholders with the piped value
     */
    private fun transformPipeExpression(expression: Expression, pipeValue: Expression): Expression {
        return when (expression) {
            is PlaceholderExpression -> pipeValue
            is FunctionCall -> FunctionCall(
                transformPipeExpression(expression.function, pipeValue),
                transformPipeExpression(expression.arguments, pipeValue)
            )
            is RecordLiteral -> RecordLiteral(
                expression.entries.map { entry ->
                    RecordEntry(
                        entry.name,
                        entry.value?.let { transformPipeExpression(it, pipeValue) }
                    )
                },
                expression.restField
            )
            is BinaryOperation -> BinaryOperation(
                transformPipeExpression(expression.left, pipeValue),
                expression.operator,
                transformPipeExpression(expression.right, pipeValue)
            )
            is IfExpression -> IfExpression(
                transformPipeExpression(expression.condition, pipeValue),
                transformPipeExpression(expression.thenBranch, pipeValue),
                transformPipeExpression(expression.elseBranch, pipeValue)
            )
            is LambdaExpression -> LambdaExpression(
                expression.parameters,
                transformPipeExpression(expression.body, pipeValue)
            )
            is MemberAccess -> MemberAccess(
                transformPipeExpression(expression.receiver, pipeValue),
                expression.member
            )
            is RecordUpdate -> RecordUpdate(
                transformPipeExpression(expression.record, pipeValue),
                expression.updates.map { entry ->
                    RecordEntry(
                        entry.name,
                        entry.value?.let { transformPipeExpression(it, pipeValue) }
                    )
                }
            )
            is VariantConstruction -> VariantConstruction(
                expression.constructor,
                expression.value?.let { transformPipeExpression(it, pipeValue) }
            )
            is MatchExpression -> MatchExpression(
                transformPipeExpression(expression.expression, pipeValue),
                expression.cases.map { case ->
                    MatchCase(
                        case.pattern,
                        transformPipeExpression(case.expression, pipeValue)
                    )
                }
            )
            is DoExpression -> DoExpression(
                expression.statements.map { stmt ->
                    when (stmt) {
                        is LetBinding -> LetBinding(
                            stmt.variableName,
                            stmt.typeAnnotation,
                            transformPipeExpression(stmt.expression, pipeValue)
                        )
                        is ExpressionStatement -> ExpressionStatement(
                            transformPipeExpression(stmt.expression, pipeValue)
                        )
                        else -> stmt
                    }
                },
                transformPipeExpression(expression.result, pipeValue)
            )
            is BlockExpression -> BlockExpression(
                expression.statements.map { stmt ->
                    when (stmt) {
                        is LetBinding -> LetBinding(
                            stmt.variableName,
                            stmt.typeAnnotation,
                            transformPipeExpression(stmt.expression, pipeValue)
                        )
                        is ExpressionStatement -> ExpressionStatement(
                            transformPipeExpression(stmt.expression, pipeValue)
                        )
                        else -> stmt
                    }
                },
                transformPipeExpression(expression.result, pipeValue)
            )
            // For all other expressions (literals, identifiers, etc.), return as-is
            else -> expression
        }
    }

    private fun inferDoExpressionWithConstraints(doExpr: DoExpression, context: TypeContext): InferenceResult {
        // Process statements in sequence, updating context
        var currentContext = context
        var allConstraints = emptySet<Constraint>()
        
        for (statement in doExpr.statements) {
            when (statement) {
                is LetBinding -> {
                    // Infer the let binding expression with constraints
                    val exprResult = inferExpressionWithConstraints(statement.expression, currentContext)
                    allConstraints += exprResult.constraints
                    
                    // Add the variable to the context for subsequent statements
                    val scheme = TypeScheme.monomorphic(exprResult.type)
                    val newEnv = currentContext.env.extend(statement.variableName, scheme)
                    currentContext = currentContext.copy(env = newEnv)
                }
                is ExpressionStatement -> {
                    // Expression statements contribute constraints but don't bind variables
                    val exprResult = inferExpressionWithConstraints(statement.expression, currentContext)
                    allConstraints += exprResult.constraints
                }
                else -> {
                    // Other statement types (TypeDeclaration, EffectDeclaration, etc.) 
                    // are not typically used within do/block expressions
                    // For now, ignore them
                }
            }
        }
        
        // Infer the result expression with constraints
        val resultResult = inferExpressionWithConstraints(doExpr.result, currentContext)
        
        return InferenceResult(resultResult.type, allConstraints + resultResult.constraints)
    }
    
    private fun inferBlockExpressionWithConstraints(block: BlockExpression, context: TypeContext): InferenceResult {
        // Process statements in sequence, updating context
        var currentContext = context
        var allConstraints = emptySet<Constraint>()
        
        for (statement in block.statements) {
            when (statement) {
                is LetBinding -> {
                    // Infer the let binding expression with constraints
                    val exprResult = inferExpressionWithConstraints(statement.expression, currentContext)
                    allConstraints += exprResult.constraints
                    
                    // Add the variable to the context for subsequent statements
                    val scheme = TypeScheme.monomorphic(exprResult.type)
                    val newEnv = currentContext.env.extend(statement.variableName, scheme)
                    currentContext = currentContext.copy(env = newEnv)
                }
                is ExpressionStatement -> {
                    // Expression statements contribute constraints but don't bind variables
                    val exprResult = inferExpressionWithConstraints(statement.expression, currentContext)
                    allConstraints += exprResult.constraints
                }
                else -> {
                    // Other statement types (TypeDeclaration, EffectDeclaration, etc.) 
                    // are not typically used within do/block expressions
                    // For now, ignore them
                }
            }
        }
        
        // Infer the result expression with constraints
        val resultResult = inferExpressionWithConstraints(block.result, currentContext)
        
        return InferenceResult(resultResult.type, allConstraints + resultResult.constraints)
    }
    
    private fun inferHandleExpressionWithConstraints(handle: HandleExpression, context: TypeContext): InferenceResult {
        // Infer the expression being handled with constraints
        val exprResult = inferExpressionWithConstraints(handle.expression, context)
        var allConstraints = exprResult.constraints
        
        // Infer each handle case with constraints
        val caseTypes = mutableListOf<FunkType>()
        for (case in handle.cases) {
            // For now, we don't add effect pattern constraints to the context
            // This would require more sophisticated effect type inference
            val caseResult = inferExpressionWithConstraints(case.result, context)
            caseTypes.add(caseResult.type)
            allConstraints += caseResult.constraints
        }
        
        // All cases must have the same type as the handled expression
        val resultType = exprResult.type
        val unificationConstraints = caseTypes.map { caseType ->
            Constraint.Equality(resultType, caseType)
        }
        
        return InferenceResult(resultType, allConstraints + unificationConstraints)
    }
    
    
    // Helper methods for ability type checking
    private fun convertFunctionType(functionType: com.funk.language.parser.FunctionType): FunkType.FunctionType {
        val paramType = convertTypeAnnotation(functionType.paramType)
        val returnType = convertTypeAnnotation(functionType.returnType)
        
        return FunkType.FunctionType(
            paramType = paramType,
            returnType = returnType
        )
    }
    
    private fun resolveType(typeName: String, context: TypeContext): FunkType {
        return when (typeName) {
            "Int" -> FunkType.IntType
            "String" -> FunkType.StringType
            "Float" -> FunkType.FloatType
            "Boolean" -> FunkType.BooleanType
            else -> context.env.lookupType(typeName) ?: throw TypeCheckError("Unknown type: $typeName")
        }
    }
    
    private fun substituteAbilityType(
        signature: FunkType.FunctionType,
        typeParams: List<String>,
        concreteType: FunkType
    ): FunkType.FunctionType {
        if (typeParams.isEmpty()) return signature
        
        // Create substitution map (for now, assume single type param)
        val substitution = if (typeParams.size == 1) {
            mapOf(typeParams[0] to concreteType)
        } else {
            // For multiple type params, this needs more sophisticated handling
            mapOf(typeParams[0] to concreteType)
        }
        
        return FunkType.FunctionType(
            paramType = substituteType(signature.paramType, substitution),
            returnType = substituteType(signature.returnType, substitution)
        )
    }
    
    private fun substituteType(type: FunkType, substitution: Map<String, FunkType>): FunkType {
        return when (type) {
            is FunkType.VarType -> {
                substitution[type.variable.name] ?: type
            }
            is FunkType.RecordType -> {
                val newFields = type.fields.mapValues { (_, fieldType) ->
                    substituteType(fieldType, substitution)
                }
                FunkType.RecordType(newFields, type.rowVar)
            }
            is FunkType.VariantType -> {
                val newConstructors = type.constructors.mapValues { (_, constructorType) ->
                    constructorType?.let { substituteType(it, substitution) }
                }
                FunkType.VariantType(newConstructors, type.rowVar)
            }
            is FunkType.FunctionType -> {
                FunkType.FunctionType(
                    paramType = substituteType(type.paramType, substitution),
                    returnType = substituteType(type.returnType, substitution)
                )
            }
            is FunkType.TypeConstructor -> {
                // Check if this is a type parameter reference
                if (type.args.isEmpty() && substitution.containsKey(type.name)) {
                    substitution[type.name]!!
                } else {
                    val newArgs = type.args.map { substituteType(it, substitution) }
                    FunkType.TypeConstructor(type.name, newArgs)
                }
            }
            // For primitive types and others, return as-is
            else -> type
        }
    }
    
    private fun unify(type1: FunkType, type2: FunkType, context: TypeContext) {
        when {
            type1 == type2 -> return // Same types unify
            
            type1 is FunkType.VarType && type2 is FunkType.VarType -> {
                // Unify type variables by name
                if (type1.variable.name == type2.variable.name) return
            }
            
            type1 is FunkType.VarType -> {
                // Type variable can unify with any type
                return
            }
            
            type2 is FunkType.VarType -> {
                // Type variable can unify with any type
                return
            }
            
            type1 is FunkType.FunctionType && type2 is FunkType.FunctionType -> {
                unify(type1.paramType, type2.paramType, context)
                unify(type1.returnType, type2.returnType, context)
                return
            }
            
            type1 is FunkType.RecordType && type2 is FunkType.RecordType -> {
                // Check that all fields in type1 exist in type2 with compatible types
                for ((fieldName, fieldType1) in type1.fields) {
                    val fieldType2 = type2.fields[fieldName]
                        ?: throw TypeCheckError("Field $fieldName not found in record type")
                    unify(fieldType1, fieldType2, context)
                }
                return
            }
            
            else -> {
                throw TypeCheckError("Cannot unify types: $type1 and $type2")
            }
        }
    }
}

// Typed AST nodes
data class TypedProgram(val items: List<TypedItem>)

sealed interface TypedItem

data class TypedLetBinding(
    val variableName: String,
    val declaredType: FunkType?,
    val inferredType: FunkType,
    val expression: TypedExpression
) : TypedItem

data class TypedTypeDeclaration(
    val name: String,
    val type: FunkType,
    val hash: ContentHash
) : TypedItem



data class TypedModuleDeclaration(
    val name: String,
    val statements: List<TypedItem>
) : TypedItem

data class TypedExpression(
    val expression: Expression,
    val type: FunkType
)