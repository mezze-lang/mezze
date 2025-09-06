package com.funk.language.typechecker

import com.funk.language.parser.*

/**
 * Principal type inference system ensuring decidable types and optional type annotations
 */

// Type inference result with principal type
data class InferenceResult(
    val type: FunkType,
    val constraints: Set<Constraint>,
    val substitution: Substitution = Substitution.empty()
) {
    fun withSubstitution(subst: Substitution): InferenceResult =
        copy(
            type = subst.apply(type),
            constraints = constraints.map { applySubstitutionToConstraint(it, subst) }.toSet(),
            substitution = substitution.compose(subst)
        )
}

// Type inference context with constraint collection
data class InferenceContext(
    val env: TypeEnvironment = TypeEnvironment(),
    val constraints: MutableSet<Constraint> = mutableSetOf(),
    val level: Int = 0,
    val typeVarSupply: TypeVarSupply = TypeVarSupply()
) {
    fun enterScope(): InferenceContext = copy(level = level + 1)
    fun freshTypeVar(prefix: String = "t"): TypeVar = typeVarSupply.fresh(prefix, level)
    fun freshRowVar(prefix: String = "r"): RowVar = RowVar.fresh(prefix)
}

// Type variable supply with levels for let-polymorphism
class TypeVarSupply(private var counter: Int = 0) {
    fun fresh(prefix: String = "t", level: Int = 0): TypeVar {
        val id = counter++
        return TypeVar("${prefix}${id}", level)
    }
}

/**
 * Complete principal type inference system
 */
class PrincipalTypeInference {
    
    /**
     * Infer the principal type of a program
     */
    fun inferProgram(program: Program): InferenceResult {
        val context = InferenceContext()
        var result = InferenceResult(FunkType.VarType(context.freshTypeVar("unit")), emptySet())
        
        for (item in program.items) {
            result = inferProgramItem(item, context)
        }
        
        // Solve all constraints
        val finalSubst = solveConstraints(context.constraints.toList())
        return result.withSubstitution(finalSubst)
    }
    
    /**
     * Infer type of a program item
     */
    fun inferProgramItem(item: ProgramItem, context: InferenceContext): InferenceResult {
        return when (item) {
            is LetBinding -> inferLetBinding(item, context)
            is TypeDeclaration -> inferTypeDeclaration(item, context)
            is EffectDeclaration -> inferEffectDeclaration(item, context)
            is ModuleDeclaration -> inferModuleDeclaration(item, context)
            else -> throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnboundVariable("Unsupported program item"),
                    null,
                    "Program item type not supported: ${item::class.simpleName}"
                )
            )
        }
    }
    
    /**
     * Infer type of an expression with principal type guarantee
     */
    fun inferExpression(expr: Expression, context: InferenceContext): InferenceResult {
        return when (expr) {
            is Literal<*> -> inferLiteral(expr, context)
            is Identifier -> inferIdentifier(expr, context)
            is LambdaExpression -> inferLambda(expr, context)
            is BinaryOperation -> inferBinaryOperation(expr, context)
            is RecordLiteral -> inferRecordLiteral(expr, context)
            is MemberAccess -> inferMemberAccess(expr, context)
            is RecordUpdate -> inferRecordUpdate(expr, context)
            is FunctionCall -> inferFunctionCall(expr, context)
            is VariantConstruction -> inferVariantConstructor(expr, context)
            is IfExpression -> inferIfExpression(expr, context)
            is MatchExpression -> inferMatchExpression(expr, context)
            is PipeExpression -> inferPipeExpression(expr, context)
            is DoExpression -> inferDoExpression(expr, context)
            is HandleExpression -> inferHandleExpression(expr, context)
            is PlaceholderExpression -> inferPlaceholder(expr, context)
            else -> throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnboundVariable("Unsupported expression"),
                    null,
                    "Expression type not supported: ${expr::class.simpleName}"
                )
            )
        }
    }
    
    // Literal inference - always decidable
    private fun inferLiteral(literal: Literal<*>, context: InferenceContext): InferenceResult {
        val type = when (literal.value) {
            is Int -> FunkType.IntType
            is Float -> FunkType.FloatType
            is String -> FunkType.StringType
            is Boolean -> FunkType.BooleanType
            else -> throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnboundVariable("Unsupported literal"),
                    null,
                    "Literal type not supported: ${literal.value?.let { it::class.simpleName }}"
                )
            )
        }
        return InferenceResult(type, emptySet())
    }
    
    // Identifier inference with instantiation
    private fun inferIdentifier(identifier: Identifier, context: InferenceContext): InferenceResult {
        val scheme = context.env.lookup(identifier.name)
            ?: throw StructuredTypeError.unboundVariable(
                identifier.name,
                context.env.bindings.keys.toList()
            )
        
        val instantiated = instantiate(scheme, context)
        return InferenceResult(instantiated, emptySet())
    }
    
    // Lambda inference with principal type
    private fun inferLambda(lambda: LambdaExpression, context: InferenceContext): InferenceResult {
        val newContext = context.enterScope()
        
        val parameters = lambda.parameters
        return when (parameters) {
            is TypedParameters -> {
                // Typed parameters - use declared types
                val paramType = inferRecordType(parameters.recordType, newContext)
                val paramContext = bindTypedRecordParameters(parameters.recordType, paramType, newContext)
                val bodyResult = inferExpression(lambda.body, paramContext)
                
                val funcType = FunkType.FunctionType(paramType, bodyResult.type, emptySet())
                InferenceResult(funcType, bodyResult.constraints)
            }
            is UntypedParameters -> {
                // Untyped parameters - create fresh type variables
                val paramTypes = parameters.names.associate { name ->
                    name to FunkType.VarType(context.freshTypeVar("param_$name"))
                }
                val paramType = FunkType.RecordType(paramTypes)
                
                // Bind parameters with fresh type variables
                val paramContext = bindUntypedParameters(parameters.names, paramTypes, newContext)
                val bodyResult = inferExpression(lambda.body, paramContext)
                
                val funcType = FunkType.FunctionType(paramType, bodyResult.type, emptySet())
                InferenceResult(funcType, bodyResult.constraints)
            }
        }
    }
    
    // Binary operation inference
    private fun inferBinaryOperation(binOp: BinaryOperation, context: InferenceContext): InferenceResult {
        val leftResult = inferExpression(binOp.left, context)
        val rightResult = inferExpression(binOp.right, context)
        
        val resultType = when (binOp.operator) {
            "+", "-", "*", "/" -> {
                // Arithmetic - both operands must be numeric
                val numericType = context.freshTypeVar("num")
                context.constraints.add(Constraint.Equality(leftResult.type, FunkType.VarType(numericType)))
                context.constraints.add(Constraint.Equality(rightResult.type, FunkType.VarType(numericType)))
                FunkType.VarType(numericType)
            }
            
            "++" -> {
                // String concatenation
                context.constraints.add(Constraint.Equality(leftResult.type, FunkType.StringType))
                context.constraints.add(Constraint.Equality(rightResult.type, FunkType.StringType))
                FunkType.StringType
            }
            
            "==", "!=", "<", ">", "<=", ">=" -> {
                // Comparison - operands must be same type
                context.constraints.add(Constraint.Equality(leftResult.type, rightResult.type))
                FunkType.BooleanType
            }
            
            "&&", "||" -> {
                // Logical - both must be boolean
                context.constraints.add(Constraint.Equality(leftResult.type, FunkType.BooleanType))
                context.constraints.add(Constraint.Equality(rightResult.type, FunkType.BooleanType))
                FunkType.BooleanType
            }
            
            else -> {
                val resultVar = context.freshTypeVar("binop")
                FunkType.VarType(resultVar)
            }
        }
        
        return InferenceResult(
            resultType,
            leftResult.constraints + rightResult.constraints + context.constraints
        )
    }
    
    // Record literal inference with row polymorphism
    private fun inferRecordLiteral(record: RecordLiteral, context: InferenceContext): InferenceResult {
        val fieldResults = mutableMapOf<String, InferenceResult>()
        val allConstraints = mutableSetOf<Constraint>()
        
        // Infer each field
        for (entry in record.entries) {
            val value = entry.value
            val fieldResult = if (value != null) {
                inferExpression(value, context)
            } else {
                // Punned syntax: name (expands to name = name)
                val scheme = context.env.lookup(entry.name)
                    ?: throw TypeError.UnboundVariable("Variable '${entry.name}' not found for punned record entry")
                val instantiatedType = instantiate(scheme, context)
                InferenceResult(instantiatedType, emptySet())
            }
            fieldResults[entry.name] = fieldResult
            allConstraints.addAll(fieldResult.constraints)
        }
        
        val fieldTypes = fieldResults.mapValues { it.value.type }
        val rowVar = if (record.restField != null) context.freshRowVar() else null
        
        val recordType = FunkType.RecordType(fieldTypes, rowVar)
        
        return InferenceResult(recordType, allConstraints)
    }
    
    // Member access with row constraints
    private fun inferMemberAccess(access: MemberAccess, context: InferenceContext): InferenceResult {
        val receiverResult = inferExpression(access.receiver, context)
        val fieldType = FunkType.VarType(context.freshTypeVar("field"))
        
        // Add constraint that receiver has the field
        val constraint = Constraint.HasField(receiverResult.type, access.member, fieldType)
        context.constraints.add(constraint)
        
        return InferenceResult(
            fieldType,
            receiverResult.constraints + setOf(constraint)
        )
    }
    
    // Record update inference
    private fun inferRecordUpdate(update: RecordUpdate, context: InferenceContext): InferenceResult {
        val recordResult = inferExpression(update.record, context)
        val updateResults = mutableMapOf<String, InferenceResult>()
        val allConstraints = mutableSetOf<Constraint>()
        
        allConstraints.addAll(recordResult.constraints)
        
        // Infer update fields
        for (entry in update.updates) {
            val value = entry.value
            val updateResult = if (value != null) {
                inferExpression(value, context)
            } else {
                // Punned syntax: name (expands to name = name)
                val scheme = context.env.lookup(entry.name)
                    ?: throw TypeError.UnboundVariable("Variable '${entry.name}' not found for punned record entry")
                val instantiatedType = instantiate(scheme, context)
                InferenceResult(instantiatedType, emptySet())
            }
            updateResults[entry.name] = updateResult
            allConstraints.addAll(updateResult.constraints)
        }
        
        // Result type is same as input record type (structural sharing)
        return InferenceResult(recordResult.type, allConstraints)
    }
    
    // Function call with record-based currying
    private fun inferFunctionCall(call: FunctionCall, context: InferenceContext): InferenceResult {
        val funcResult = inferExpression(call.function, context)
        val argsResult = inferExpression(call.arguments, context)
        
        val returnType = FunkType.VarType(context.freshTypeVar("return"))
        val expectedFuncType = FunkType.FunctionType(argsResult.type, returnType)
        
        // Unify function type
        val unifyConstraint = Constraint.Equality(funcResult.type, expectedFuncType)
        context.constraints.add(unifyConstraint)
        
        return InferenceResult(
            returnType,
            funcResult.constraints + argsResult.constraints + setOf(unifyConstraint)
        )
    }
    
    // Variant constructor inference
    private fun inferVariantConstructor(constructor: VariantConstruction, context: InferenceContext): InferenceResult {
        val constructorType = constructor.value?.let { 
            inferExpression(it, context) 
        } ?: InferenceResult(FunkType.VarType(context.freshTypeVar("unit")), emptySet())
        
        // Create open variant type for inference
        val variantType = FunkType.VariantType(
            mapOf(constructor.constructor to constructorType.type),
            context.freshRowVar()
        )
        
        return InferenceResult(variantType, constructorType.constraints)
    }
    
    // If expression inference
    private fun inferIfExpression(ifExpr: IfExpression, context: InferenceContext): InferenceResult {
        val condResult = inferExpression(ifExpr.condition, context)
        val thenResult = inferExpression(ifExpr.thenBranch, context)
        val elseResult = inferExpression(ifExpr.elseBranch, context)
        
        // Condition must be boolean
        val condConstraint = Constraint.Equality(condResult.type, FunkType.BooleanType)
        
        // Both branches must have same type
        val branchConstraint = Constraint.Equality(thenResult.type, elseResult.type)
        
        context.constraints.addAll(listOf(condConstraint, branchConstraint))
        
        return InferenceResult(
            thenResult.type,
            condResult.constraints + thenResult.constraints + elseResult.constraints + 
            setOf(condConstraint, branchConstraint)
        )
    }
    
    // Match expression inference with exhaustiveness
    private fun inferMatchExpression(match: MatchExpression, context: InferenceContext): InferenceResult {
        val scrutineeResult = inferExpression(match.expression, context)
        
        if (match.cases.isEmpty()) {
            throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnboundVariable("Empty match"),
                    null,
                    "Match expression must have at least one case"
                )
            )
        }
        
        val caseResults = match.cases.map { case ->
            // TODO: Implement pattern matching type inference
            inferExpression(case.expression, context)
        }
        
        val firstCaseType = caseResults.first().type
        val allConstraints = mutableSetOf<Constraint>()
        allConstraints.addAll(scrutineeResult.constraints)
        
        // All cases must have same result type
        for ((index, caseResult) in caseResults.withIndex()) {
            allConstraints.addAll(caseResult.constraints)
            if (index > 0) {
                val constraint = Constraint.Equality(firstCaseType, caseResult.type)
                allConstraints.add(constraint)
                context.constraints.add(constraint)
            }
        }
        
        return InferenceResult(firstCaseType, allConstraints)
    }
    
    // Pipe expression inference
    private fun inferPipeExpression(pipe: PipeExpression, context: InferenceContext): InferenceResult {
        val leftResult = inferExpression(pipe.left, context)
        val rightResult = inferExpression(pipe.right, context)
        
        val returnType = FunkType.VarType(context.freshTypeVar("pipe"))
        val expectedFuncType = FunkType.FunctionType(leftResult.type, returnType)
        
        val constraint = Constraint.Equality(rightResult.type, expectedFuncType)
        context.constraints.add(constraint)
        
        return InferenceResult(
            returnType,
            leftResult.constraints + rightResult.constraints + setOf(constraint)
        )
    }
    
    // Do expression inference
    private fun inferDoExpression(doExpr: DoExpression, context: InferenceContext): InferenceResult {
        val newContext = context.enterScope()
        val allConstraints = mutableSetOf<Constraint>()
        
        // Process statements
        for (statement in doExpr.statements) {
            when (statement) {
                is LetBinding -> {
                    val exprResult = inferExpression(statement.expression, newContext)
                    val scheme = generalize(newContext.env, exprResult.type, newContext.level)
                    newContext.env.extend(statement.variableName, scheme)
                    allConstraints.addAll(exprResult.constraints)
                }
                is ExpressionStatement -> {
                    val result = inferExpression(statement.expression, newContext)
                    allConstraints.addAll(result.constraints)
                }
                is ResumeStatement -> {
                    val result = inferExpression(statement.expression, newContext)
                    allConstraints.addAll(result.constraints)
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
        
        // Infer result expression
        val resultResult = inferExpression(doExpr.result, newContext)
        allConstraints.addAll(resultResult.constraints)
        
        return InferenceResult(resultResult.type, allConstraints)
    }
    
    // Handle expression inference (simplified)
    private fun inferHandleExpression(handle: HandleExpression, context: InferenceContext): InferenceResult {
        val exprResult = inferExpression(handle.expression, context)
        // TODO: Implement full effect handler inference
        return exprResult
    }
    
    // Placeholder inference
    private fun inferPlaceholder(placeholder: PlaceholderExpression, context: InferenceContext): InferenceResult {
        val placeholderType = FunkType.VarType(context.freshTypeVar("placeholder"))
        return InferenceResult(placeholderType, emptySet())
    }
    
    // Let binding inference with generalization
    private fun inferLetBinding(letBinding: LetBinding, context: InferenceContext): InferenceResult {
        val exprResult = inferExpression(letBinding.expression, context)
        
        // Check against declared type if present
        letBinding.typeAnnotation?.let { annotation ->
            val declaredType = convertTypeAnnotation(annotation, context)
            val constraint = Constraint.Equality(exprResult.type, declaredType)
            context.constraints.add(constraint)
        }
        
        // Generalize and add to environment
        val scheme = generalize(context.env, exprResult.type, context.level)
        context.env.extend(letBinding.variableName, scheme)
        
        return InferenceResult(exprResult.type, exprResult.constraints)
    }
    
    // Type declaration inference
    private fun inferTypeDeclaration(typeDecl: TypeDeclaration, context: InferenceContext): InferenceResult {
        val type = convertTypeDefinition(typeDecl.definition, context)
        context.env.copy(typeAliases = context.env.typeAliases + (typeDecl.name to type))
        
        return InferenceResult(FunkType.VarType(context.freshTypeVar("unit")), emptySet())
    }
    
    // Effect declaration inference
    private fun inferEffectDeclaration(effectDecl: EffectDeclaration, context: InferenceContext): InferenceResult {
        val inputType = inferRecordType(effectDecl.inputType, context)
        val resultType = inferVariantType(effectDecl.resultType, context)
        val effect = Effect(effectDecl.name)
        
        context.env.copy(effects = context.env.effects + (effectDecl.name to effect))
        
        return InferenceResult(FunkType.VarType(context.freshTypeVar("unit")), emptySet())
    }
    
    // Module declaration inference
    private fun inferModuleDeclaration(moduleDecl: ModuleDeclaration, context: InferenceContext): InferenceResult {
        val newContext = context.enterScope()
        val allConstraints = mutableSetOf<Constraint>()
        
        for (statement in moduleDecl.statements) {
            when (statement) {
                is LetBinding -> {
                    val result = inferProgramItem(statement, newContext)
                    allConstraints.addAll(result.constraints)
                }
                is TypeDeclaration -> {
                    val result = inferProgramItem(statement, newContext)
                    allConstraints.addAll(result.constraints)
                }
                is EffectDeclaration -> {
                    val result = inferProgramItem(statement, newContext)
                    allConstraints.addAll(result.constraints)
                }
                is AbilityDeclaration -> {
                    // Handle ability declarations
                }
                is ExposeDeclaration -> {
                    // Handle expose declarations
                }
                is AccessDeclaration -> {
                    // Handle access declarations
                }
                is ModDeclaration -> {
                    // Handle mod declarations
                }
                is AccessModuleDeclaration -> {
                    // Handle access declarations
                }
            }
        }
        
        return InferenceResult(FunkType.VarType(context.freshTypeVar("unit")), allConstraints)
    }
    
    // Helper functions
    
    private fun inferRecordType(recordType: RecordType, context: InferenceContext): FunkType.RecordType {
        val fields = recordType.entries.associate { entry ->
            entry.name to convertTypeAnnotation(entry.type, context)
        }
        val rowVar = if (recordType.restField != null) context.freshRowVar() else null
        return FunkType.RecordType(fields, rowVar)
    }
    
    private fun inferVariantType(variantType: VariantType, context: InferenceContext): FunkType.VariantType {
        val constructors = variantType.constructors.associate { constructor ->
            constructor.name to constructor.recordType?.let { inferRecordType(it, context) }
        }
        val rowVar = if (variantType.isOpen) context.freshRowVar() else null
        return FunkType.VariantType(constructors, rowVar)
    }
    
    private fun convertTypeAnnotation(annotation: TypeAnnotation, context: InferenceContext): FunkType {
        return when (annotation) {
            is SimpleType -> when (annotation.name) {
                "Int" -> FunkType.IntType
                "Float" -> FunkType.FloatType
                "String" -> FunkType.StringType
                "Boolean" -> FunkType.BooleanType
                else -> FunkType.VarType(TypeVar(annotation.name))
            }
            is RecordType -> inferRecordType(annotation, context)
            is VariantType -> inferVariantType(annotation, context)
            else -> FunkType.VarType(context.freshTypeVar("unknown"))
        }
    }
    
    private fun convertTypeDefinition(definition: TypeDefinition, context: InferenceContext): FunkType {
        return when (definition) {
            is RecordType -> inferRecordType(definition, context)
            is VariantType -> inferVariantType(definition, context)
            else -> FunkType.VarType(context.freshTypeVar("unknown"))
        }
    }
    
    private fun bindTypedRecordParameters(
        recordType: RecordType,
        inferredType: FunkType.RecordType,
        context: InferenceContext
    ): InferenceContext {
        val newEnv = recordType.entries.zip(inferredType.fields.entries).fold(context.env) { env, (entry, typeEntry) ->
            env.extendType(entry.name, typeEntry.value)
        }
        return context.copy(env = newEnv)
    }
    
    private fun bindUntypedParameters(
        names: List<String>,
        paramTypes: Map<String, FunkType>,
        context: InferenceContext
    ): InferenceContext {
        val newEnv = names.fold(context.env) { env, name ->
            env.extendType(name, paramTypes[name]!!)
        }
        return context.copy(env = newEnv)
    }
    
    private fun instantiate(scheme: TypeScheme, context: InferenceContext): FunkType {
        val typeSubst: Map<TypeVar, FunkType> = scheme.typeVars.associate { 
            it to FunkType.VarType(context.freshTypeVar(it.name)) 
        }
        val rowSubst: Map<RowVar, RowVar> = scheme.rowVars.associate { 
            it to context.freshRowVar(it.name) 
        }
        return substituteInType(scheme.type, typeSubst, rowSubst)
    }
    
    private fun generalize(env: TypeEnvironment, type: FunkType, level: Int): TypeScheme {
        val freeVars = freeTypeVars(type).filter { it.level > level }.toSet()
        val freeRowVars = freeRowVars(type)
        
        return TypeScheme(freeVars, freeRowVars, emptySet(), type)
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
    
    /**
     * Constraint solver using unification
     */
    private fun solveConstraints(constraints: List<Constraint>): Substitution {
        var subst = Substitution.empty()
        
        for (constraint in constraints) {
            when (constraint) {
                is Constraint.Equality -> {
                    val newSubst = Unification.unify(
                        subst.apply(constraint.type1),
                        subst.apply(constraint.type2)
                    )
                    subst = subst.compose(newSubst)
                }
                
                is Constraint.HasField -> {
                    // Simplified field constraint solving
                    // Would be more sophisticated in full implementation
                }
                
                else -> {
                    // Handle other constraint types
                }
            }
        }
        
        return subst
    }
}

// Helper function to apply substitution to constraints
private fun applySubstitutionToConstraint(constraint: Constraint, subst: Substitution): Constraint {
    return when (constraint) {
        is Constraint.Equality -> Constraint.Equality(
            subst.apply(constraint.type1),
            subst.apply(constraint.type2)
        )
        is Constraint.HasField -> Constraint.HasField(
            subst.apply(constraint.recordType),
            constraint.field,
            subst.apply(constraint.fieldType)
        )
        else -> constraint
    }
}
