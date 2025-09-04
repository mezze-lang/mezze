package com.funk.language.typechecker

import com.funk.language.parser.*

/**
 * Complete effect system with handlers, composition, and applicative effects
 */

// Enhanced Effect types
data class EffectType(
    val name: String,
    val inputType: FunkType,
    val resultType: FunkType,
    val hash: String? = null
) {
    override fun toString(): String = "$name: $inputType -> $resultType"
}

// Effect constraints and capabilities
sealed interface EffectConstraint {
    data class RequiresEffect(val effect: EffectType) : EffectConstraint
    data class ProvidesEffect(val effect: EffectType, val handler: FunkType) : EffectConstraint
    data class ComposesEffects(val effects: Set<EffectType>) : EffectConstraint
}

// Effect environment for tracking available effects
data class EffectEnvironment(
    val availableEffects: Map<String, EffectType> = emptyMap(),
    val handlers: Map<String, FunkType> = emptyMap(),
    val constraints: Set<EffectConstraint> = emptySet()
) {
    fun withEffect(effect: EffectType): EffectEnvironment =
        copy(availableEffects = availableEffects + (effect.name to effect))
    
    fun withHandler(effectName: String, handlerType: FunkType): EffectEnvironment =
        copy(handlers = handlers + (effectName to handlerType))
    
    fun withConstraint(constraint: EffectConstraint): EffectEnvironment =
        copy(constraints = constraints + constraint)
}

/**
 * Effect system type checker
 */
class EffectSystemChecker {
    
    fun inferEffects(expr: Expression, context: TypeContext): Pair<FunkType, Set<EffectType>> {
        return when (expr) {
            is HandleExpression -> inferHandleExpression(expr, context)
            is FunctionCall -> inferEffectfulCall(expr, context)
            is DoExpression -> inferEffectfulDo(expr, context)
            is IfExpression -> inferEffectfulIf(expr, context)
            is MatchExpression -> inferEffectfulMatch(expr, context)
            else -> {
                // Non-effectful expression
                val type = inferPureExpression(expr, context)
                type to emptySet()
            }
        }
    }
    
    private fun inferHandleExpression(
        handleExpr: HandleExpression, 
        context: TypeContext
    ): Pair<FunkType, Set<EffectType>> {
        // Infer the type and effects of the handled expression
        val (exprType, exprEffects) = inferEffects(handleExpr.expression, context)
        
        // Check handlers match the effects
        val handledEffects = mutableSetOf<EffectType>()
        val remainingEffects = mutableSetOf<EffectType>()
        
        for (effect in exprEffects) {
            val handler = handleExpr.cases.find { it.effectName == effect.name }
            if (handler != null) {
                // Verify handler type matches effect
                val expectedHandlerType = createHandlerType(effect, exprType)
                val actualHandlerType = inferExpression(handler.result, context)
                
                try {
                    Unification.unify(expectedHandlerType, actualHandlerType)
                    handledEffects.add(effect)
                } catch (e: StructuredTypeError) {
                    throw StructuredTypeError(
                        TypeErrorInfo(
                            TypeErrorKind.EffectMismatch(
                                listOf(effect.name),
                                listOf(actualHandlerType.prettyPrint()),
                                listOf("Handler type mismatch")
                            ),
                            null,
                            "Handler for effect '${effect.name}' has wrong type"
                        )
                    )
                }
            } else {
                remainingEffects.add(effect)
            }
        }
        
        // The result type and remaining unhandled effects
        return exprType to remainingEffects
    }
    
    private fun inferEffectfulCall(
        call: FunctionCall, 
        context: TypeContext
    ): Pair<FunkType, Set<EffectType>> {
        val funcType = inferExpression(call.function, context)
        val argsType = inferExpression(call.arguments, context)
        
        return when (funcType) {
            is FunkType.FunctionType -> {
                // Apply function and collect effects
                val resultType = applyFunction(funcType, argsType, context)
                val effects = funcType.effects.map { effect ->
                    context.effectEnv?.availableEffects?.get(effect.name) 
                        ?: createDefaultEffect(effect.name)
                }.toSet()
                
                resultType to effects
            }
            
            else -> {
                val resultType = applyFunction(
                    FunkType.FunctionType(argsType, FunkType.VarType(TypeVar.fresh())), 
                    argsType, 
                    context
                )
                resultType to emptySet()
            }
        }
    }
    
    private fun inferEffectfulDo(
        doExpr: DoExpression, 
        context: TypeContext
    ): Pair<FunkType, Set<EffectType>> {
        val allEffects = mutableSetOf<EffectType>()
        
        // Collect effects from all statements
        for (statement in doExpr.statements) {
            val (_, stmtEffects) = when (statement) {
                is LetBinding -> {
                    val (type, effects) = inferEffects(statement.expression, context)
                    context.env.extend(statement.variableName, TypeScheme.monomorphic(type))
                    type to effects
                }
                is ExpressionStatement -> inferEffects(statement.expression, context)
                else -> FunkType.VarType(TypeVar.fresh()) to emptySet<EffectType>()
            }
            allEffects.addAll(stmtEffects)
        }
        
        // Infer result expression
        val (resultType, resultEffects) = inferEffects(doExpr.result, context)
        allEffects.addAll(resultEffects)
        
        return resultType to allEffects
    }
    
    private fun inferEffectfulIf(
        ifExpr: IfExpression, 
        context: TypeContext
    ): Pair<FunkType, Set<EffectType>> {
        val (condType, condEffects) = inferEffects(ifExpr.condition, context)
        Unification.unify(condType, FunkType.BooleanType)
        
        val (thenType, thenEffects) = inferEffects(ifExpr.thenBranch, context)
        val (elseType, elseEffects) = inferEffects(ifExpr.elseBranch, context)
        
        Unification.unify(thenType, elseType)
        
        // Union of all effects
        val allEffects = condEffects + thenEffects + elseEffects
        
        return thenType to allEffects
    }
    
    private fun inferEffectfulMatch(
        matchExpr: MatchExpression, 
        context: TypeContext
    ): Pair<FunkType, Set<EffectType>> {
        val (scrutineeType, scrutineeEffects) = inferEffects(matchExpr.expression, context)
        
        if (matchExpr.cases.isEmpty()) {
            throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnboundVariable("Empty match expression"),
                    null,
                    "Match expression must have at least one case"
                )
            )
        }
        
        val (firstCaseType, firstCaseEffects) = inferMatchCaseEffects(
            matchExpr.cases.first(), 
            scrutineeType, 
            context
        )
        
        val allEffects = mutableSetOf<EffectType>()
        allEffects.addAll(scrutineeEffects)
        allEffects.addAll(firstCaseEffects)
        
        // All cases must have the same result type
        for (case in matchExpr.cases.drop(1)) {
            val (caseType, caseEffects) = inferMatchCaseEffects(case, scrutineeType, context)
            Unification.unify(firstCaseType, caseType)
            allEffects.addAll(caseEffects)
        }
        
        return firstCaseType to allEffects
    }
    
    private fun inferMatchCaseEffects(
        case: MatchCase, 
        scrutineeType: FunkType, 
        context: TypeContext
    ): Pair<FunkType, Set<EffectType>> {
        // TODO: Implement pattern matching with effect inference
        return inferEffects(case.expression, context)
    }
    
    // Helper functions
    
    private fun createHandlerType(effect: EffectType, continuationType: FunkType): FunkType {
        // Handler type: InputType -> (ResultType -> ContinuationType) -> ContinuationType
        val resumeType = FunkType.FunctionType(
            effect.resultType,
            continuationType
        )
        
        return FunkType.FunctionType(
            effect.inputType,
            FunkType.FunctionType(resumeType, continuationType)
        )
    }
    
    private fun createDefaultEffect(name: String): EffectType {
        return EffectType(
            name,
            FunkType.VarType(TypeVar.fresh("input")),
            FunkType.VarType(TypeVar.fresh("result"))
        )
    }
    
    private fun applyFunction(
        funcType: FunkType.FunctionType,
        argsType: FunkType,
        context: TypeContext
    ): FunkType {
        // Simplified function application - would use the advanced record currying
        Unification.unify(funcType.paramType, argsType)
        return funcType.returnType
    }
    
    private fun inferExpression(expr: Expression, context: TypeContext): FunkType {
        // Delegate to main type checker for non-effectful expressions
        return inferPureExpression(expr, context)
    }
    
    private fun inferPureExpression(expr: Expression, context: TypeContext): FunkType {
        // This would delegate to the main type checker
        // For now, simplified implementation
        return when (expr) {
            is Literal<*> -> when (expr.value) {
                is Int -> FunkType.IntType
                is Float -> FunkType.FloatType
                is String -> FunkType.StringType
                is Boolean -> FunkType.BooleanType
                else -> FunkType.VarType(TypeVar.fresh())
            }
            is Identifier -> {
                context.env.lookup(expr.name)?.type ?: FunkType.VarType(TypeVar.fresh())
            }
            else -> FunkType.VarType(TypeVar.fresh())
        }
    }
}

/**
 * Effect composition and unification
 */
object EffectComposition {
    
    /**
     * Compose effects applicatively - all effects are collected
     */
    fun composeEffects(effects1: Set<EffectType>, effects2: Set<EffectType>): Set<EffectType> {
        return effects1 + effects2
    }
    
    /**
     * Unify effect sets - check compatibility
     */
    fun unifyEffects(
        expected: Set<EffectType>, 
        actual: Set<EffectType>
    ): Result<Map<String, EffectType>> {
        val unified = mutableMapOf<String, EffectType>()
        
        // Check that all expected effects are provided
        for (expectedEffect in expected) {
            val actualEffect = actual.find { it.name == expectedEffect.name }
            if (actualEffect != null) {
                // Unify effect types
                try {
                    Unification.unify(expectedEffect.inputType, actualEffect.inputType)
                    Unification.unify(expectedEffect.resultType, actualEffect.resultType)
                    unified[expectedEffect.name] = expectedEffect
                } catch (e: StructuredTypeError) {
                    return Result.failure(e)
                }
            } else {
                return Result.failure(
                    StructuredTypeError(
                        TypeErrorInfo(
                            TypeErrorKind.EffectNotFound(
                                expectedEffect.name,
                                actual.map { it.name }
                            ),
                            null,
                            "Required effect '${expectedEffect.name}' not found"
                        )
                    )
                )
            }
        }
        
        return Result.success(unified)
    }
    
    /**
     * Check if effect set is a subset of another (for effect polymorphism)
     */
    fun isEffectSubset(subset: Set<EffectType>, superset: Set<EffectType>): Boolean {
        return subset.all { subEffect ->
            superset.any { superEffect ->
                subEffect.name == superEffect.name &&
                isEffectCompatible(subEffect, superEffect)
            }
        }
    }
    
    private fun isEffectCompatible(effect1: EffectType, effect2: EffectType): Boolean {
        return try {
            Unification.unify(effect1.inputType, effect2.inputType)
            Unification.unify(effect1.resultType, effect2.resultType)
            true
        } catch (e: StructuredTypeError) {
            false
        }
    }
}

/**
 * Effect handler validation
 */
object EffectHandlerValidator {
    
    fun validateHandler(
        effect: EffectType,
        handler: Expression,
        continuationType: FunkType,
        context: TypeContext
    ): Result<FunkType> {
        // The handler should have type: InputType -> (ResultType -> ContinuationType) -> ContinuationType
        val expectedHandlerType = FunkType.FunctionType(
            effect.inputType,
            FunkType.FunctionType(
                FunkType.FunctionType(effect.resultType, continuationType),
                continuationType
            )
        )
        
        return try {
            val actualHandlerType = inferExpression(handler, context)
            Unification.unify(expectedHandlerType, actualHandlerType)
            Result.success(actualHandlerType)
        } catch (e: StructuredTypeError) {
            Result.failure(e)
        }
    }
    
    private fun inferExpression(expr: Expression, context: TypeContext): FunkType {
        // Simplified - would use main type checker
        return FunkType.VarType(TypeVar.fresh())
    }
}

// Extension to TypeContext for effect environment
val TypeContext.effectEnv: EffectEnvironment?
    get() = null // Would be implemented with proper context extension
