package com.funk.language.typechecker

import com.funk.language.parser.*

/**
 * Main type checker for Funk language with row polymorphism and effects
 */

class TypeChecker {
    
    /**
     * Type check a program and return the typed AST with inferred types
     */
    fun typeCheck(program: Program): TypedProgram {
        var context = TypeContext()
        val typedItems = mutableListOf<TypedItem>()
        
        for (item in program.items) {
            val typedItem = inferItem(item, context)
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
                is TypedEffectDeclaration -> {
                    // Update context with new effect binding
                    val newEnv = context.env.copy(effects = context.env.effects + (typedItem.name to typedItem.effect))
                    context.copy(env = newEnv)
                }
                is TypedModuleDeclaration -> {
                    // For now, modules don't affect the global context
                    // In a full implementation, this would handle module exports
                    context
                }

            }
        }
        
        return TypedProgram(typedItems)
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
            
            is EffectDeclaration -> {
                val effect = Effect(item.name)
                
                // Note: Effect environment update is handled in the main typeCheck method
                // For now, use dummy types until effect system is fully implemented
                val inputType = FunkType.VarType(TypeVar.fresh("effect_input"))
                val resultType = FunkType.VarType(TypeVar.fresh("effect_result"))
                
                TypedEffectDeclaration(
                    name = item.name,
                    inputType = inputType,
                    resultType = resultType,
                    effect = effect
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
                        is TypedEffectDeclaration -> {
                            val newEnv = moduleContext.env.copy(effects = moduleContext.env.effects + (typedStatement.name to typedStatement.effect))
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
            // For now, return a placeholder type - effects need special handling in function calls
            throw TypeError.UnboundVariable("Effect '${identifier.name}' cannot be used as a standalone expression")
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
        val argumentType = inferExpression(call.arguments, context)
        
        // Check that arguments match effect input type
        // For now, just return a fresh type variable for effect results
        // TODO: Implement proper effect type checking when effect system is expanded
        return FunkType.VarType(TypeVar.fresh("effect_result"))
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
                val effects = collectEffects(lambda.body)
                
                FunkType.FunctionType(paramType, bodyType, effects)
            }
            is UntypedParameters -> {
                // Create fresh type variables for untyped parameters (bidirectional inference)
                val paramTypes = parameters.names.associate { name ->
                    name to FunkType.VarType(TypeVar.fresh("param_$name"))
                }
                val paramType = FunkType.RecordType(paramTypes)
                
                // Create context with type variables - let match expressions do bidirectional inference
                val paramContext = bindUntypedParameters(parameters.names, paramTypes, context)
                val bodyType = inferExpression(lambda.body, paramContext)
                val effects = collectEffects(lambda.body)
                
                // After body inference, try to refine parameter types from usage
                val refinedParamTypes = refineParameterTypes(lambda.body, parameters.names, paramTypes)
                val refinedParamType = FunkType.RecordType(refinedParamTypes)
                
                FunkType.FunctionType(refinedParamType, bodyType, effects)
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
                // If parameters are used in arithmetic, infer as Int
                val leftExpr = expr.left
                val rightExpr = expr.right
                if (expr.operator in listOf("+", "-", "*", "/", "%")) {
                    if (leftExpr is Identifier && paramTypes.containsKey(leftExpr.name)) {
                        paramTypes[leftExpr.name] = FunkType.IntType
                    }
                    if (rightExpr is Identifier && paramTypes.containsKey(rightExpr.name)) {
                        paramTypes[rightExpr.name] = FunkType.IntType
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
            
            else -> { /* Other expression types */ }
        }
    }
    
    /**
     * Analyze lambda body to infer parameter types from usage patterns (legacy method)
     */
    private fun inferParameterTypesFromBody(body: Expression, paramNames: List<String>): Map<String, FunkType> {
        val paramTypes = mutableMapOf<String, FunkType>()
        
        // Initialize with fresh type variables
        for (name in paramNames) {
            paramTypes[name] = FunkType.VarType(TypeVar.fresh("param_$name"))
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
        
        Unification.unify(thenType, elseType)
        return thenType
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
        // Create new scope for the do block with fresh constraints
        var doContext = context.copy(constraints = mutableSetOf())
        
        // Process all statements (for side effects/bindings)
        for (statement in doExpr.statements) {
            doContext = inferStatementWithContext(statement, doContext)
        }
        
        // Return type of the final expression
        return inferExpression(doExpr.result, doContext)
    }
    
    private fun inferHandleExpression(handleExpr: HandleExpression, context: TypeContext): FunkType {
        // Simplified effect handling - would be more sophisticated in full implementation
        return inferExpression(handleExpr.expression, context)
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
                                            functionType.returnType,
                                            functionType.effects
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
        val recordType = inferExpression(access.receiver, context)
        
        return when (recordType) {
            is FunkType.RecordType -> {
                recordType.fields[access.member] 
                    ?: throw TypeError.UnboundVariable("Field '${access.member}' not found in record")
            }
            is FunkType.VarType -> {
                // Create a fresh type variable for the field type
                val fieldType = FunkType.VarType(TypeVar.fresh("field_${access.member}"))
                val rowVar = RowVar.fresh()
                val expectedRecordType = FunkType.RecordType(
                    mapOf(access.member to fieldType),
                    rowVar
                )
                Unification.unify(recordType, expectedRecordType)
                fieldType
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
                Unification.unify(leftType, FunkType.StringType)
                Unification.unify(rightType, FunkType.StringType)
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
        // Look up the variant constructor in the environment
        val scheme = context.env.lookup(constructor.constructor)
            ?: throw TypeError.UnboundVariable("Variant constructor '${constructor.constructor}' not found")
        
        val constructorType = instantiate(scheme)
        
        val value = constructor.value
        return if (value != null) {
            val valueType = inferExpression(value, context)
            // Apply the constructor to the value
            when (constructorType) {
                is FunkType.FunctionType -> {
                    Unification.unify(valueType, constructorType.paramType)
                    constructorType.returnType
                }
                else -> throw TypeError.UnificationError(constructorType, FunkType.FunctionType(valueType, FunkType.VarType(TypeVar.fresh())))
            }
        } else {
            constructorType
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
            is EffectDeclaration -> {
                // Effect declarations are handled at the module level
            }
            is AbilityDeclaration -> {
                // Ability declarations are handled at the module level
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
            is EffectDeclaration -> {
                // Effect declarations are handled at the module level
                context
            }
            is AbilityDeclaration -> {
                // Ability declarations are handled at the module level
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
            convertTypeAnnotation(annotation.returnType),
            annotation.effects.map { Effect(it) }.toSet()
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
            substituteInType(type.returnType, typeSubst, rowSubst),
            type.effects
        )
        else -> type
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

data class TypedEffectDeclaration(
    val name: String,
    val inputType: FunkType,
    val resultType: FunkType,
    val effect: Effect
) : TypedItem

data class TypedModuleDeclaration(
    val name: String,
    val statements: List<TypedItem>
) : TypedItem

data class TypedExpression(
    val expression: Expression,
    val type: FunkType
)