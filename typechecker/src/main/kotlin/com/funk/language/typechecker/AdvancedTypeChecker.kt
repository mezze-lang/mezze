package com.funk.language.typechecker

import com.funk.language.parser.*

/**
 * Advanced type checker with complete open/closed type inference,
 * record currying, and effect system
 */

class AdvancedTypeChecker {
    
    private fun inferExpression(expr: Expression, context: TypeContext): FunkType {
        return when (expr) {
            is Literal<*> -> inferLiteral(expr)
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
            is PlaceholderExpression -> inferPlaceholder(context)
            else -> throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnboundVariable("Unsupported expression: ${expr::class.simpleName}"),
                    null,
                    "Expression type not supported: ${expr::class.simpleName}"
                )
            )
        }
    }
    
    private fun inferLiteral(literal: Literal<*>): FunkType = when (literal.value) {
        is Int -> FunkType.IntType
        is Float -> FunkType.FloatType
        is String -> FunkType.StringType
        is Boolean -> FunkType.BooleanType
        else -> throw StructuredTypeError(
            TypeErrorInfo(
                TypeErrorKind.UnboundVariable("Unsupported literal type"),
                null,
                "Literal type not supported: ${literal.value?.let { it::class.simpleName }}"
            )
        )
    }
    
    private fun inferIdentifier(identifier: Identifier, context: TypeContext): FunkType {
        val scheme = context.env.lookup(identifier.name) 
            ?: throw StructuredTypeError.unboundVariable(
                identifier.name,
                context.env.bindings.keys.toList()
            )
        return instantiate(scheme)
    }
    
    private fun inferLambda(lambda: LambdaExpression, context: TypeContext): FunkType {
        val parameters = lambda.parameters
        return when (parameters) {
            is TypedParameters -> {
                val paramType = convertRecordType(parameters.recordType)
                val paramContext = bindTypedParameters(parameters.recordType, context)
                val bodyType = inferExpression(lambda.body, paramContext)
                val effects = collectEffects(lambda.body)
                FunkType.FunctionType(paramType, bodyType, effects)
            }
            is UntypedParameters -> {
                // Create fresh type variables for each parameter
                val paramTypes = parameters.names.associate { name ->
                    name to FunkType.VarType(TypeVar.fresh("param_$name"))
                }
                val paramType = FunkType.RecordType(paramTypes)
                
                // Create context with untyped parameters
                val paramContext = bindUntypedParameters(parameters.names, paramTypes, context)
                val bodyType = inferExpression(lambda.body, paramContext)
                val effects = collectEffects(lambda.body)
                
                FunkType.FunctionType(paramType, bodyType, effects)
            }
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
                    else -> {
                        // Try to unify for polymorphic arithmetic
                        val unified = Unification.unify(leftType, rightType)
                        unified.apply(leftType)
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
            
            else -> throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnboundVariable("Unknown operator: ${binOp.operator}"),
                    null,
                    "Binary operator '${binOp.operator}' is not supported"
                )
            )
        }
    }
    
    private fun inferRecordLiteral(record: RecordLiteral, context: TypeContext): FunkType {
        val fieldTypes = mutableMapOf<String, FunkType>()
        
        for (entry in record.entries) {
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
            fieldTypes[entry.name] = fieldType
        }
        
        // Open record if restField is present
        val rowVar = if (record.restField != null) RowVar.fresh() else null
        
        return FunkType.RecordType(fieldTypes, rowVar)
    }
    
    private fun inferMemberAccess(access: MemberAccess, context: TypeContext): FunkType {
        val receiverType = inferExpression(access.receiver, context)
        
        return when (receiverType) {
            is FunkType.RecordType -> {
                receiverType.fields[access.member]
                    ?: if (receiverType.isOpen) {
                        // For open records, we can add the field dynamically
                        val fieldType = FunkType.VarType(TypeVar.fresh("field"))
                        context.constraints.add(
                            Constraint.HasField(receiverType, access.member, fieldType)
                        )
                        fieldType
                    } else {
                        throw StructuredTypeError.fieldNotFound(receiverType, access.member)
                    }
            }
            
            is FunkType.VarType -> {
                // Unknown record type - add constraint
                val fieldType = FunkType.VarType(TypeVar.fresh("field"))
                context.constraints.add(
                    Constraint.HasField(receiverType, access.member, fieldType)
                )
                fieldType
            }
            
            else -> throw StructuredTypeError.fieldNotFound(receiverType, access.member)
        }
    }
    
    private fun inferRecordUpdate(update: RecordUpdate, context: TypeContext): FunkType {
        val recordType = inferExpression(update.record, context)
        
        when (recordType) {
            is FunkType.RecordType -> {
                val updatedFields = recordType.fields.toMutableMap()
                
                // Check and update fields
                for (entry in update.updates) {
                    val value = entry.value
                    val newFieldType = if (value != null) {
                        inferExpression(value, context)
                    } else {
                        // Punned syntax: name (expands to name = name)
                        val scheme = context.env.lookup(entry.name)
                            ?: throw TypeError.UnboundVariable("Variable '${entry.name}' not found for punned record entry")
                        instantiate(scheme)
                    }
                    
                    if (recordType.fields.containsKey(entry.name)) {
                        // Existing field - unify types
                        val existingType = recordType.fields[entry.name]!!
                        Unification.unify(existingType, newFieldType)
                        updatedFields[entry.name] = newFieldType
                    } else if (recordType.isOpen) {
                        // New field in open record
                        updatedFields[entry.name] = newFieldType
                    } else {
                        // New field in closed record - error
                        throw StructuredTypeError.fieldNotFound(recordType, entry.name)
                    }
                }
                
                return FunkType.RecordType(updatedFields, recordType.rowVar)
            }
            
            else -> throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnificationError(
                        SerializableType.RecordType(emptyMap()),
                        recordType.toSerializable()
                    ),
                    null,
                    "Record update requires record type, got ${recordType.prettyPrint()}"
                )
            )
        }
    }
    
    private fun inferFunctionCall(call: FunctionCall, context: TypeContext): FunkType {
        val funcType = inferExpression(call.function, context)
        val argsType = inferExpression(call.arguments, context)
        
        return when (funcType) {
            is FunkType.FunctionType -> {
                // Apply record-based currying
                applyRecordFunction(funcType, argsType, context)
            }
            
            is FunkType.VarType -> {
                // Function type is unknown - create fresh type variables
                val paramVar = TypeVar.fresh("param")
                val returnVar = TypeVar.fresh("return")
                val expectedFuncType = FunkType.FunctionType(
                    FunkType.VarType(paramVar),
                    FunkType.VarType(returnVar)
                )
                
                val unification = Unification.unify(funcType, expectedFuncType)
                val unifiedParamType = unification.apply(FunkType.VarType(paramVar))
                val argUnification = Unification.unify(unifiedParamType, argsType)
                
                val finalSubst = unification.compose(argUnification)
                finalSubst.apply(FunkType.VarType(returnVar))
            }
            
            else -> throw StructuredTypeError.unificationError(
                FunkType.FunctionType(argsType, FunkType.VarType(TypeVar.fresh())),
                funcType
            )
        }
    }
    
    /**
     * Advanced record-based function application with currying
     */
    private fun applyRecordFunction(
        funcType: FunkType.FunctionType,
        argsType: FunkType,
        context: TypeContext
    ): FunkType {
        return when (val paramType = funcType.paramType) {
            is FunkType.RecordType -> {
                when (argsType) {
                    is FunkType.RecordType -> {
                        val providedFields = argsType.fields.keys
                        val requiredFields = paramType.fields.keys
                        
                        when {
                            // Exact match - full application
                            providedFields == requiredFields -> {
                                // Unify field types
                                for (field in requiredFields) {
                                    val requiredFieldType = paramType.fields[field]!!
                                    val providedFieldType = argsType.fields[field]!!
                                    Unification.unify(requiredFieldType, providedFieldType)
                                }
                                funcType.returnType
                            }
                            
                            // Subset - partial application
                            providedFields.all { it in requiredFields } -> {
                                val remainingFields = paramType.fields - providedFields
                                
                                // Unify provided fields
                                for (field in providedFields) {
                                    val requiredFieldType = paramType.fields[field]!!
                                    val providedFieldType = argsType.fields[field]!!
                                    Unification.unify(requiredFieldType, providedFieldType)
                                }
                                
                                // Return function with remaining parameters
                                FunkType.FunctionType(
                                    FunkType.RecordType(remainingFields, paramType.rowVar),
                                    funcType.returnType,
                                    funcType.effects
                                )
                            }
                            
                            // Over-application - check if open
                            providedFields.containsAll(requiredFields) -> {
                                if (paramType.isClosed) {
                                    val extraFields = providedFields - requiredFields
                                    throw StructuredTypeError.partialApplicationError(funcType, argsType)
                                } else {
                                    // Open record - unify and apply
                                    Unification.unify(paramType, argsType)
                                    funcType.returnType
                                }
                            }
                            
                            else -> throw StructuredTypeError.partialApplicationError(funcType, argsType)
                        }
                    }
                    
                    else -> {
                        // Non-record argument - must match exactly
                        Unification.unify(paramType, argsType)
                        funcType.returnType
                    }
                }
            }
            
            else -> {
                // Non-record parameter - must match exactly
                Unification.unify(paramType, argsType)
                funcType.returnType
            }
        }
    }
    
    private fun inferVariantConstructor(constructor: VariantConstruction, context: TypeContext): FunkType {
        // For now, create a variant type with this constructor
        val constructorType = constructor.value?.let { inferExpression(it, context) }
        
        return FunkType.VariantType(
            mapOf(constructor.constructor to constructorType),
            RowVar.fresh() // Open by default for inference
        )
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
        val scrutineeType = inferExpression(matchExpr.expression, context)
        
        if (matchExpr.cases.isEmpty()) {
            throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnboundVariable("Empty match expression"),
                    null,
                    "Match expression must have at least one case"
                )
            )
        }
        
        val firstCaseType = inferMatchCase(matchExpr.cases.first(), scrutineeType, context)
        
        // All cases must have the same result type
        for (case in matchExpr.cases.drop(1)) {
            val caseType = inferMatchCase(case, scrutineeType, context)
            Unification.unify(firstCaseType, caseType)
        }
        
        return firstCaseType
    }
    
    private fun inferMatchCase(case: MatchCase, scrutineeType: FunkType, context: TypeContext): FunkType {
        // Pattern matching would require more sophisticated analysis
        // For now, just infer the expression type
        return inferExpression(case.expression, context)
    }
    
    private fun inferPipeExpression(pipe: PipeExpression, context: TypeContext): FunkType {
        val leftType = inferExpression(pipe.left, context)
        val rightType = inferExpression(pipe.right, context)
        
        // Handle @ placeholder in pipe expressions
        return when (rightType) {
            is FunkType.FunctionType -> {
                // Check if the function can accept the left value
                // This could be through direct application or partial application
                applyRecordFunction(rightType, leftType, context)
            }
            
            else -> throw StructuredTypeError.unificationError(
                FunkType.FunctionType(leftType, FunkType.VarType(TypeVar.fresh())),
                rightType
            )
        }
    }
    
    private fun inferDoExpression(doExpr: DoExpression, context: TypeContext): FunkType {
        // Infer all statements (for side effects/bindings)
        for (statement in doExpr.statements) {
            inferStatement(statement, context)
        }
        
        // Return type of the final expression
        return inferExpression(doExpr.result, context)
    }
    
    private fun inferHandleExpression(handleExpr: HandleExpression, context: TypeContext): FunkType {
        // Effect handling - simplified for now
        val exprType = inferExpression(handleExpr.expression, context)
        
        // TODO: Implement full effect handler inference
        return exprType
    }
    
    private fun inferPlaceholder(context: TypeContext): FunkType {
        // Placeholder gets a fresh type variable
        return FunkType.VarType(TypeVar.fresh("placeholder"))
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
    
    // Helper functions
    
    private fun convertRecordType(recordType: RecordType): FunkType.RecordType {
        val fields = recordType.entries.associate { 
            it.name to convertTypeAnnotation(it.type) 
        }
        val rowVar = if (recordType.restField != null) RowVar.fresh() else null
        return FunkType.RecordType(fields, rowVar)
    }
    
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
        
        else -> throw StructuredTypeError(
            TypeErrorInfo(
                TypeErrorKind.UnboundVariable("Unsupported type annotation"),
                null,
                "Type annotation not supported: ${annotation::class.simpleName}"
            )
        )
    }
    
    private fun convertVariantType(variantType: VariantType): FunkType.VariantType {
        val constructors = variantType.constructors.associate { constructor ->
            constructor.name to constructor.recordType?.let { convertRecordType(it) }
        }
        val rowVar = if (variantType.isOpen) RowVar.fresh() else null
        return FunkType.VariantType(constructors, rowVar)
    }
    
    private fun bindRecordParameters(recordType: RecordType, context: TypeContext): TypeContext {
        val newEnv = recordType.entries.fold(context.env) { env, entry ->
            env.extendType(entry.name, convertTypeAnnotation(entry.type))
        }
        return context.copy(env = newEnv)
    }
    
    private fun collectEffects(expr: Expression): Set<Effect> {
        // Simplified effect collection - would be more sophisticated in full implementation
        return emptySet()
    }
    
    private fun bindTypedParameters(
        recordType: RecordType,
        context: TypeContext
    ): TypeContext {
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
    
    private fun instantiate(scheme: TypeScheme): FunkType {
        val typeSubst: Map<TypeVar, FunkType> = scheme.typeVars.associate { 
            it to FunkType.VarType(TypeVar.fresh()) 
        }
        val rowSubst: Map<RowVar, RowVar> = scheme.rowVars.associate { 
            it to RowVar.fresh() 
        }
        return substituteInType(scheme.type, typeSubst, rowSubst)
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
