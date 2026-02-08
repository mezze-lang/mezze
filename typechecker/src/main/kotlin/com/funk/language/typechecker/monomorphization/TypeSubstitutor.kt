package com.funk.language.typechecker.monomorphization

import com.funk.language.ast.*

/**
 * Type substitutor - applies type substitutions to expressions.
 *
 * This walks the AST and replaces type variables in inferredType
 * fields with concrete types.
 *
 * Design:
 * - Pure functions (creates new AST, doesn't mutate!)
 * - Exhaustive pattern matching on all Expression types
 * - Applies substitution recursively to all sub-expressions
 * - Updates inferredType field on each expression
 */
class TypeSubstitutor(
    private val substitution: Map<TypeVar, FunkType>
) {
    /**
     * Apply substitution to an expression.
     *
     * Returns a new Expression with all type variables substituted.
     */
    fun apply(expr: Expression): Expression {
        // Apply substitution to this expression's type
        val substitutedType = expr.inferredType?.let { applyToType(it) }

        // Recursively apply to nested expressions
        return when (expr) {
            // Literals
            is IntLiteral -> expr.copy(inferredType = substitutedType)
            is NatLiteral -> expr.copy(inferredType = substitutedType)
            is FloatLiteral -> expr.copy(inferredType = substitutedType)
            is BooleanLiteral -> expr.copy(inferredType = substitutedType)
            is FractionLiteral -> expr.copy(inferredType = substitutedType)
            is StringLiteral -> applyToStringLiteral(expr, substitutedType)
            is ArrayLiteral -> applyToArrayLiteral(expr, substitutedType)

            // Variables
            is Identifier -> expr.copy(inferredType = substitutedType)
            is PlaceholderExpression -> expr.copy(inferredType = substitutedType)

            // Arrays
            is ArrayAccess -> expr.copy(
                array = apply(expr.array),
                index = apply(expr.index),
                inferredType = substitutedType
            )

            // Records
            is RecordLiteral -> applyToRecordLiteral(expr, substitutedType)
            is MemberAccess -> expr.copy(
                receiver = apply(expr.receiver),
                inferredType = substitutedType
            )
            is RecordUpdate -> applyToRecordUpdate(expr, substitutedType)
            is RecordUpdateExpression -> applyToRecordUpdateExpression(expr, substitutedType)

            // Variants
            is VariantConstruction -> expr.copy(
                value = expr.value?.let { apply(it) },
                inferredType = substitutedType
            )

            // Functions
            is LambdaExpression -> applyToLambda(expr, substitutedType)
            is FunctionCall -> applyToFunctionCall(expr, substitutedType)

            // Control flow
            is IfExpression -> expr.copy(
                condition = apply(expr.condition),
                thenBranch = apply(expr.thenBranch),
                elseBranch = apply(expr.elseBranch),
                inferredType = substitutedType
            )
            is MatchExpression -> applyToMatch(expr, substitutedType)

            // Blocks
            is DoExpression -> applyToDo(expr, substitutedType)

            // Effects
            is PerformExpression -> expr.copy(
                expression = apply(expr.expression),
                inferredType = substitutedType
            )
            is EffectCallExpression -> expr.copy(
                arguments = apply(expr.arguments),
                inferredType = substitutedType
            )

            // State machines
            is StateMachineExpression -> applyToStateMachine(expr, substitutedType)
        }
    }

    /**
     * Apply substitution to a type.
     *
     * Replaces type variables with their concrete types.
     */
    fun applyToType(type: FunkType): FunkType {
        return when (type) {
            is FunkType.VarType -> {
                // Replace type variable with concrete type
                substitution[type.variable] ?: type
            }

            is FunkType.FunctionType -> FunkType.FunctionType(
                paramType = applyToType(type.paramType),
                returnType = applyToType(type.returnType),
                effects = type.effects?.let { row ->
                    FunkType.EffectRow(row.effects, row.rowVar)
                },
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    FunkType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { applyToType(it) }
                    )
                }
            )

            is FunkType.RecordType -> FunkType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) -> applyToType(fieldType) },
                rowVar = type.rowVar
            )

            is FunkType.VariantType -> FunkType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyToType(it) }
                }
            )

            is FunkType.TypeApplication -> FunkType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applyToType(it) }
            )

            is FunkType.ListType -> FunkType.ListType(applyToType(type.elementType))
            is FunkType.ArrayType -> FunkType.ArrayType(applyToType(type.elementType))
            is FunkType.SetType -> FunkType.SetType(applyToType(type.elementType))
            is FunkType.SortedMapType -> FunkType.SortedMapType(
                keyType = applyToType(type.keyType),
                valueType = applyToType(type.valueType)
            )
            is FunkType.SortedSetType -> FunkType.SortedSetType(applyToType(type.elementType))
            is FunkType.MapType -> FunkType.MapType(
                keyType = applyToType(type.keyType),
                valueType = applyToType(type.valueType)
            )

            is FunkType.SuspendedType -> FunkType.SuspendedType(
                valueType = applyToType(type.valueType),
                effects = FunkType.EffectRow(type.effects.effects, type.effects.rowVar)
            )

            // Concrete types stay as-is
            is FunkType.IntType,
            is FunkType.FloatType,
            is FunkType.StringType,
            is FunkType.BooleanType,
            is FunkType.NatType,
            is FunkType.FracType,
            is FunkType.OrderingType,
            is FunkType.BytesType,
            is FunkType.ByteBufferType,
            is FunkType.CharType,
            is FunkType.EffectRow -> type
        }
    }

    // ========================================================================
    // HELPER METHODS FOR COMPLEX EXPRESSIONS
    // ========================================================================

    private fun applyToStringLiteral(expr: StringLiteral, substitutedType: FunkType?): StringLiteral {
        val updatedParts = expr.parts?.map { part ->
            when (part) {
                is StringPart -> part
                is ExpressionPart -> ExpressionPart(apply(part.expression))
            }
        }
        return expr.copy(parts = updatedParts, inferredType = substitutedType)
    }

    private fun applyToArrayLiteral(expr: ArrayLiteral, substitutedType: FunkType?): ArrayLiteral {
        val updatedElements = expr.elements.map { element ->
            when (element) {
                is ArrayRegularElement -> ArrayRegularElement(apply(element.expression))
                is ArraySpreadElement -> ArraySpreadElement(apply(element.expression))
            }
        }
        return expr.copy(elements = updatedElements, inferredType = substitutedType)
    }

    private fun applyToRecordLiteral(expr: RecordLiteral, substitutedType: FunkType?): RecordLiteral {
        val updatedEntries = expr.entries.map { entry ->
            when (entry) {
                is RecordFieldEntry -> RecordFieldEntry(
                    name = entry.name,
                    value = entry.value?.let { apply(it) }
                )
                is RecordSpreadEntry -> RecordSpreadEntry(
                    expression = apply(entry.expression)
                )
            }
        }
        return expr.copy(entries = updatedEntries, inferredType = substitutedType)
    }

    private fun applyToRecordUpdate(expr: RecordUpdate, substitutedType: FunkType?): RecordUpdate {
        val updatedRecord = apply(expr.record)
        val updatedUpdates = expr.updates.map { update ->
            RecordEntry(
                name = update.name,
                value = update.value?.let { apply(it) }
            )
        }
        return expr.copy(
            record = updatedRecord,
            updates = updatedUpdates,
            inferredType = substitutedType
        )
    }

    private fun applyToRecordUpdateExpression(
        expr: RecordUpdateExpression,
        substitutedType: FunkType?
    ): RecordUpdateExpression {
        val updatedBase = apply(expr.baseRecord)
        val updatedUpdates = expr.updates.map { update ->
            RecordUpdateEntry(
                fieldPath = update.fieldPath,
                value = apply(update.value)
            )
        }
        return expr.copy(
            baseRecord = updatedBase,
            updates = updatedUpdates,
            inferredType = substitutedType
        )
    }

    private fun applyToLambda(expr: LambdaExpression, substitutedType: FunkType?): LambdaExpression {
        // CRITICAL: Also substitute types in lambda parameters!
        val updatedParams = when (val params = expr.parameters) {
            is TypedParameters -> {
                val updatedRecordType = params.recordType.copy(
                    entries = params.recordType.entries.map { entry ->
                        val entryType = convertTypeAnnotationToFunkType(entry.type)
                        val substitutedEntryType = applyToType(entryType)
                        entry.copy(type = convertFunkTypeToAnnotation(substitutedEntryType))
                    }
                )
                TypedParameters(updatedRecordType)
            }
            is UntypedParameters -> params // No types to substitute
        }

        return expr.copy(
            parameters = updatedParams,
            body = apply(expr.body),
            inferredType = substitutedType
        )
    }

    private fun applyToFunctionCall(expr: FunctionCall, substitutedType: FunkType?): FunctionCall {
        return expr.copy(
            function = apply(expr.function),
            arguments = apply(expr.arguments),
            inferredType = substitutedType,
            instantiatedFunctionType = expr.instantiatedFunctionType?.let {
                applyToType(it) as? FunkType.FunctionType
            }
        )
    }

    private fun applyToMatch(expr: MatchExpression, substitutedType: FunkType?): MatchExpression {
        val updatedExpression = apply(expr.expression)
        val updatedCases = expr.cases.map { case ->
            MatchCase(
                patterns = case.patterns, // Patterns don't need substitution
                guard = case.guard?.let { apply(it) },
                expression = apply(case.expression)
            )
        }
        return expr.copy(
            expression = updatedExpression,
            cases = updatedCases,
            inferredType = substitutedType
        )
    }

    private fun applyToDo(expr: DoExpression, substitutedType: FunkType?): DoExpression {
        val updatedStatements = expr.statements.map { stmt ->
            when (stmt) {
                is LetStatement -> LetStatement(
                    pattern = stmt.pattern,
                    typeAnnotation = stmt.typeAnnotation,
                    expression = apply(stmt.expression)
                )
                is ExpressionStatement -> ExpressionStatement(
                    expression = apply(stmt.expression)
                )
            }
        }
        val updatedResult = apply(expr.result)
        return expr.copy(
            statements = updatedStatements,
            result = updatedResult,
            inferredType = substitutedType
        )
    }

    private fun applyToStateMachine(expr: StateMachineExpression, substitutedType: FunkType?): StateMachineExpression {
        val updatedStates = expr.states.map { state ->
            when (state) {
                is ComputeState -> ComputeState(
                    id = state.id,
                    computation = apply(state.computation),
                    nextStateId = state.nextStateId
                )
                is YieldState -> YieldState(
                    id = state.id,
                    valueExpression = apply(state.valueExpression),
                    capturedVariables = state.capturedVariables,
                    nextStateId = state.nextStateId
                )
                is CompleteState -> CompleteState(
                    id = state.id,
                    resultExpression = apply(state.resultExpression)
                )
            }
        }
        return expr.copy(states = updatedStates, inferredType = substitutedType)
    }

    // ========================================================================
    // TYPE ANNOTATION CONVERSION HELPERS
    // ========================================================================

    private fun convertTypeAnnotationToFunkType(annotation: TypeAnnotation): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> when (annotation.name) {
                "Int" -> FunkType.IntType
                "Float" -> FunkType.FloatType
                "String" -> FunkType.StringType
                "Bool" -> FunkType.VariantType(
                    constructors = mapOf("True" to null, "False" to null),
                    rowVar = null
                )
                "Nat" -> FunkType.NatType
                "Frac" -> FunkType.FracType
                "Ordering" -> FunkType.OrderingType
                else -> FunkType.VarType(TypeVar(annotation.name))
            }
            is RecordTypeAnnotation -> FunkType.RecordType(
                fields = annotation.entries.associate { entry ->
                    entry.name to convertTypeAnnotationToFunkType(entry.type)
                },
                rowVar = annotation.restField?.let { RowVar(it) }
            )
            is FunctionTypeAnnotation -> FunkType.FunctionType(
                paramType = convertTypeAnnotationToFunkType(annotation.paramType),
                returnType = convertTypeAnnotationToFunkType(annotation.returnType),
                effects = annotation.effects?.let { row ->
                    FunkType.EffectRow(row.effects.toSet(), if (row.isPolymorphic) RowVar("e") else null)
                },
                abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    FunkType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertTypeAnnotationToFunkType(it) }
                    )
                }
            )
            is ArrayTypeAnnotation -> FunkType.ArrayType(
                elementType = convertTypeAnnotationToFunkType(annotation.elementType)
            )
            is TypeApplicationAnnotation -> FunkType.TypeApplication(
                constructor = annotation.constructor,
                args = annotation.args.map { convertTypeAnnotationToFunkType(it) }
            )
            else -> FunkType.VarType(TypeVar.fresh())
        }
    }

    private fun convertFunkTypeToAnnotation(type: FunkType): TypeAnnotation {
        return when (type) {
            is FunkType.IntType -> IdentifierTypeAnnotation("Int")
            is FunkType.FloatType -> IdentifierTypeAnnotation("Float")
            is FunkType.StringType -> IdentifierTypeAnnotation("String")
            is FunkType.BooleanType -> IdentifierTypeAnnotation("Bool")
            is FunkType.NatType -> IdentifierTypeAnnotation("Nat")
            is FunkType.FracType -> IdentifierTypeAnnotation("Frac")
            is FunkType.OrderingType -> IdentifierTypeAnnotation("Ordering")
            is FunkType.BytesType -> IdentifierTypeAnnotation("Bytes")
            is FunkType.ByteBufferType -> IdentifierTypeAnnotation("ByteBuffer")
            is FunkType.CharType -> IdentifierTypeAnnotation("Char")
            is FunkType.VarType -> IdentifierTypeAnnotation(type.variable.name)
            is FunkType.RecordType -> RecordTypeAnnotation(
                entries = type.fields.map { (name, fieldType) ->
                    RecordTypeEntry(name, convertFunkTypeToAnnotation(fieldType))
                },
                restField = type.rowVar?.name
            )
            is FunkType.FunctionType -> FunctionTypeAnnotation(
                paramType = convertFunkTypeToAnnotation(type.paramType),
                returnType = convertFunkTypeToAnnotation(type.returnType),
                effects = type.effects?.let { row ->
                    EffectRowAnnotation(row.effects.toList(), row.isOpen)
                },
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertFunkTypeToAnnotation(it) }
                    )
                }
            )
            is FunkType.ArrayType -> ArrayTypeAnnotation(
                elementType = convertFunkTypeToAnnotation(type.elementType)
            )
            is FunkType.TypeApplication -> TypeApplicationAnnotation(
                constructor = type.constructor,
                args = type.args.map { convertFunkTypeToAnnotation(it) }
            )
            is FunkType.ListType -> TypeApplicationAnnotation(
                constructor = "List",
                args = listOf(convertFunkTypeToAnnotation(type.elementType))
            )
            is FunkType.SetType -> TypeApplicationAnnotation(
                constructor = "Set",
                args = listOf(convertFunkTypeToAnnotation(type.elementType))
            )
            is FunkType.SortedMapType -> TypeApplicationAnnotation(
                constructor = "SortedMap",
                args = listOf(
                    convertFunkTypeToAnnotation(type.keyType),
                    convertFunkTypeToAnnotation(type.valueType)
                )
            )
            is FunkType.SortedSetType -> TypeApplicationAnnotation(
                constructor = "SortedSet",
                args = listOf(convertFunkTypeToAnnotation(type.elementType))
            )
            is FunkType.MapType -> TypeApplicationAnnotation(
                constructor = "Map",
                args = listOf(
                    convertFunkTypeToAnnotation(type.keyType),
                    convertFunkTypeToAnnotation(type.valueType)
                )
            )
            is FunkType.SuspendedType -> convertFunkTypeToAnnotation(type.valueType)
            is FunkType.VariantType -> IdentifierTypeAnnotation("Variant")
            is FunkType.EffectRow -> throw IllegalArgumentException("Cannot convert EffectRow to TypeAnnotation")
        }
    }
}

/**
 * Collect all TypeVar instances from an expression.
 *
 * This is critical for building the substitution map - we need the actual
 * TypeVar object instances, not just the names.
 */
fun collectTypeVarsFromExpression(expr: Expression): Set<TypeVar> {
    val collector = mutableSetOf<TypeVar>()

    fun collectFromType(type: FunkType) {
        when (type) {
            is FunkType.VarType -> collector.add(type.variable)
            is FunkType.FunctionType -> {
                collectFromType(type.paramType)
                collectFromType(type.returnType)
                type.abilityConstraints.forEach { constraint ->
                    constraint.typeArgs.forEach { collectFromType(it) }
                }
            }
            is FunkType.RecordType -> type.fields.values.forEach { collectFromType(it) }
            is FunkType.VariantType -> type.constructors.values.forEach { it?.let { collectFromType(it) } }
            is FunkType.TypeApplication -> type.args.forEach { collectFromType(it) }
            is FunkType.ListType -> collectFromType(type.elementType)
            is FunkType.ArrayType -> collectFromType(type.elementType)
            is FunkType.SetType -> collectFromType(type.elementType)
            is FunkType.SortedMapType -> {
                collectFromType(type.keyType)
                collectFromType(type.valueType)
            }
            is FunkType.SortedSetType -> collectFromType(type.elementType)
            is FunkType.MapType -> {
                collectFromType(type.keyType)
                collectFromType(type.valueType)
            }
            is FunkType.SuspendedType -> collectFromType(type.valueType)
            else -> {} // Concrete types have no type variables
        }
    }

    fun collect(e: Expression) {
        e.inferredType?.let { collectFromType(it) }

        when (e) {
            is FunctionCall -> {
                collect(e.function)
                collect(e.arguments)
                e.instantiatedFunctionType?.let { collectFromType(it) }
            }
            is LambdaExpression -> collect(e.body)
            is DoExpression -> {
                e.statements.forEach { stmt ->
                    when (stmt) {
                        is LetStatement -> collect(stmt.expression)
                        is ExpressionStatement -> collect(stmt.expression)
                    }
                }
                collect(e.result)
            }
            is IfExpression -> {
                collect(e.condition)
                collect(e.thenBranch)
                collect(e.elseBranch)
            }
            is MatchExpression -> {
                collect(e.expression)
                e.cases.forEach { case ->
                    collect(case.expression)
                    case.guard?.let { collect(it) }
                }
            }
            is RecordLiteral -> {
                e.entries.forEach { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.value?.let { collect(it) }
                        is RecordSpreadEntry -> collect(entry.expression)
                    }
                }
            }
            is MemberAccess -> collect(e.receiver)
            is VariantConstruction -> e.value?.let { collect(it) }
            is RecordUpdate -> {
                collect(e.record)
                e.updates.forEach { update -> update.value?.let { collect(it) } }
            }
            is RecordUpdateExpression -> {
                collect(e.baseRecord)
                e.updates.forEach { collect(it.value) }
            }
            is ArrayLiteral -> e.elements.forEach { element ->
                when (element) {
                    is ArrayRegularElement -> collect(element.expression)
                    is ArraySpreadElement -> collect(element.expression)
                }
            }
            is ArrayAccess -> {
                collect(e.array)
                collect(e.index)
            }
            is StringLiteral -> {
                e.parts?.forEach { part ->
                    if (part is ExpressionPart) collect(part.expression)
                }
            }
            is PerformExpression -> collect(e.expression)
            is EffectCallExpression -> collect(e.arguments)
            is StateMachineExpression -> {
                e.states.forEach { state ->
                    when (state) {
                        is ComputeState -> collect(state.computation)
                        is YieldState -> collect(state.valueExpression)
                        is CompleteState -> collect(state.resultExpression)
                    }
                }
            }
            else -> {} // Literals and identifiers
        }
    }

    collect(expr)
    return collector
}
