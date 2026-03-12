package com.mezze.language.typechecker.monomorphization

import com.mezze.language.ast.*

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
    private val substitution: Map<TypeVar, MezzeType>
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
            is SeqLiteral -> applyToSeqLiteral(expr, substitutedType)

            // Variables
            is Identifier -> expr.copy(inferredType = substitutedType)
            is PlaceholderExpression -> expr.copy(inferredType = substitutedType)
            is ParenthesizedExpression -> expr.copy(
                expression = apply(expr.expression),
                inferredType = substitutedType
            )

            // Seqs
            is SeqAccess -> expr.copy(
                seq = apply(expr.seq),
                index = apply(expr.index),
                inferredType = substitutedType
            )

            // Records
            is RecordLiteral -> applyToRecordLiteral(expr, substitutedType)
            is MemberAccess -> expr.copy(
                receiver = apply(expr.receiver),
                inferredType = substitutedType
            )

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
    fun applyToType(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                // Replace type variable with concrete type
                substitution[type.variable] ?: type
            }

            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = applyToType(type.paramType),
                returnType = applyToType(type.returnType),
                effects = type.effects?.let { row ->
                    MezzeType.EffectRow.simple(row.effects, row.rowVar)
                },
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { applyToType(it) }
                    )
                }
            )

            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) -> applyToType(fieldType) },
                rowVar = type.rowVar
            )

            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyToType(it) }
                },
                rowVar = type.rowVar,
                typeName = type.typeName
            )

            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applyToType(it) }
            )

            is MezzeType.ListType -> MezzeType.ListType(applyToType(type.elementType))
            is MezzeType.SeqType -> MezzeType.SeqType(applyToType(type.elementType))
            is MezzeType.SetType -> MezzeType.SetType(applyToType(type.elementType))
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = applyToType(type.keyType),
                valueType = applyToType(type.valueType)
            )
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(applyToType(type.elementType))
            is MezzeType.MapType -> MezzeType.MapType(
                keyType = applyToType(type.keyType),
                valueType = applyToType(type.valueType)
            )

            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                valueType = applyToType(type.valueType),
                effects = MezzeType.EffectRow.simple(type.effects.effects, type.effects.rowVar)
            )

            // Concrete types stay as-is
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType,
            is MezzeType.CharType,
            is MezzeType.EffectRow -> type
        }
    }

    // ========================================================================
    // HELPER METHODS FOR COMPLEX EXPRESSIONS
    // ========================================================================

    private fun applyToStringLiteral(expr: StringLiteral, substitutedType: MezzeType?): StringLiteral {
        val updatedParts = expr.parts?.map { part ->
            when (part) {
                is StringPart -> part
                is ExpressionPart -> ExpressionPart(apply(part.expression))
            }
        }
        return expr.copy(parts = updatedParts, inferredType = substitutedType)
    }

    private fun applyToSeqLiteral(expr: SeqLiteral, substitutedType: MezzeType?): SeqLiteral {
        val updatedElements = expr.elements.map { element ->
            when (element) {
                is SeqRegularElement -> SeqRegularElement(apply(element.expression))
                is SeqSpreadElement -> SeqSpreadElement(apply(element.expression))
            }
        }
        return expr.copy(elements = updatedElements, inferredType = substitutedType)
    }

    private fun applyToRecordLiteral(expr: RecordLiteral, substitutedType: MezzeType?): RecordLiteral {
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

    private fun applyToLambda(expr: LambdaExpression, substitutedType: MezzeType?): LambdaExpression {
        // CRITICAL: Also substitute types in lambda parameters!
        val updatedParams = when (val params = expr.parameters) {
            is TypedParameters -> {
                val updatedRecordType = params.recordType.copy(
                    entries = params.recordType.entries.map { entry ->
                        val entryType = convertTypeAnnotationToMezzeType(entry.type)
                        val substitutedEntryType = applyToType(entryType)
                        entry.copy(type = convertMezzeTypeToAnnotation(substitutedEntryType))
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

    private fun applyToFunctionCall(expr: FunctionCall, substitutedType: MezzeType?): FunctionCall {
        return expr.copy(
            function = apply(expr.function),
            arguments = apply(expr.arguments),
            inferredType = substitutedType,
            instantiatedFunctionType = expr.instantiatedFunctionType?.let {
                applyToType(it) as? MezzeType.FunctionType
            }
        )
    }

    private fun applyToMatch(expr: MatchExpression, substitutedType: MezzeType?): MatchExpression {
        val updatedExpression = apply(expr.expression)
        val updatedCases = expr.cases.map { case ->
            // Also apply to if-let bindings
            val updatedIfLetBindings = case.ifLetBindings.map { binding ->
                binding.copy(expression = apply(binding.expression))
            }
            MatchCase(
                patterns = case.patterns, // Patterns don't need substitution
                guard = case.guard?.let { apply(it) },
                expression = apply(case.expression),
                ifLetBindings = updatedIfLetBindings
            )
        }
        return expr.copy(
            expression = updatedExpression,
            cases = updatedCases,
            inferredType = substitutedType
        )
    }

    private fun applyToDo(expr: DoExpression, substitutedType: MezzeType?): DoExpression {
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

    private fun applyToStateMachine(expr: StateMachineExpression, substitutedType: MezzeType?): StateMachineExpression {
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

    private fun convertTypeAnnotationToMezzeType(annotation: TypeAnnotation): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> when (annotation.name) {
                "Int" -> MezzeType.IntType
                "Float" -> MezzeType.FloatType
                "String" -> MezzeType.StringType
                "Bool" -> MezzeType.VariantType(
                    constructors = mapOf("True" to null, "False" to null),
                    rowVar = null
                )
                "Nat" -> MezzeType.NatType
                "Frac" -> MezzeType.FracType
                "Ordering" -> MezzeType.OrderingType
                "Char" -> MezzeType.CharType
                "Bytes" -> MezzeType.BytesType
                "ByteBuffer" -> MezzeType.ByteBufferType
                else -> MezzeType.VarType(TypeVar(annotation.name))
            }
            is RecordTypeAnnotation -> MezzeType.RecordType(
                fields = annotation.entries.associate { entry ->
                    entry.name to convertTypeAnnotationToMezzeType(entry.type)
                },
                rowVar = annotation.restField?.let { RowVar(it) }
            )
            is FunctionTypeAnnotation -> MezzeType.FunctionType(
                paramType = convertTypeAnnotationToMezzeType(annotation.paramType),
                returnType = convertTypeAnnotationToMezzeType(annotation.returnType),
                effects = annotation.effects?.let { row ->
                    MezzeType.EffectRow.simple(row.effects.toSet(), if (row.isPolymorphic) RowVar("e") else null, row.isPerformed)
                },
                abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertTypeAnnotationToMezzeType(it) }
                    )
                }
            )
            is SeqTypeAnnotation -> MezzeType.SeqType(
                elementType = convertTypeAnnotationToMezzeType(annotation.elementType)
            )
            is TypeApplicationAnnotation -> MezzeType.TypeApplication(
                constructor = annotation.constructor,
                args = annotation.args.map { convertTypeAnnotationToMezzeType(it) }
            )
            else -> MezzeType.VarType(TypeVar.fresh())
        }
    }

    private fun convertMezzeTypeToAnnotation(type: MezzeType): TypeAnnotation {
        return when (type) {
            is MezzeType.IntType -> IdentifierTypeAnnotation("Int")
            is MezzeType.FloatType -> IdentifierTypeAnnotation("Float")
            is MezzeType.StringType -> IdentifierTypeAnnotation("String")
            is MezzeType.BoolType -> IdentifierTypeAnnotation("Bool")
            is MezzeType.NatType -> IdentifierTypeAnnotation("Nat")
            is MezzeType.FracType -> IdentifierTypeAnnotation("Frac")
            is MezzeType.OrderingType -> IdentifierTypeAnnotation("Ordering")
            is MezzeType.BytesType -> IdentifierTypeAnnotation("Bytes")
            is MezzeType.ByteBufferType -> IdentifierTypeAnnotation("ByteBuffer")
            is MezzeType.CharType -> IdentifierTypeAnnotation("Char")
            is MezzeType.VarType -> IdentifierTypeAnnotation(type.variable.name)
            is MezzeType.RecordType -> RecordTypeAnnotation(
                entries = type.fields.map { (name, fieldType) ->
                    RecordTypeEntry(name, convertMezzeTypeToAnnotation(fieldType))
                },
                restField = type.rowVar?.name
            )
            is MezzeType.FunctionType -> FunctionTypeAnnotation(
                paramType = convertMezzeTypeToAnnotation(type.paramType),
                returnType = convertMezzeTypeToAnnotation(type.returnType),
                effects = type.effects?.let { row ->
                    // Convert effect instances to effect annotations
                    val effectAnnotations = row.effectInstances.map { instance ->
                        EffectAnnotation(
                            name = instance.name,
                            associatedTypes = instance.associatedTypes.mapValues { (_, mezzeType) ->
                                convertMezzeTypeToAnnotation(mezzeType)
                            }
                        )
                    }
                    EffectRowAnnotation(
                        effects = row.effects.toList(),
                        effectAnnotations = effectAnnotations,
                        isPolymorphic = row.isOpen,
                        isPerformed = row.performed
                    )
                },
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertMezzeTypeToAnnotation(it) },
                        associatedTypeBindings = constraint.associatedTypeBindings.mapValues { (_, mezzeType) ->
                            convertMezzeTypeToAnnotation(mezzeType)
                        }
                    )
                }
            )
            is MezzeType.SeqType -> SeqTypeAnnotation(
                elementType = convertMezzeTypeToAnnotation(type.elementType)
            )
            is MezzeType.TypeApplication -> TypeApplicationAnnotation(
                constructor = type.constructor,
                args = type.args.map { convertMezzeTypeToAnnotation(it) }
            )
            is MezzeType.ListType -> TypeApplicationAnnotation(
                constructor = "List",
                args = listOf(convertMezzeTypeToAnnotation(type.elementType))
            )
            is MezzeType.SetType -> TypeApplicationAnnotation(
                constructor = "Set",
                args = listOf(convertMezzeTypeToAnnotation(type.elementType))
            )
            is MezzeType.SortedMapType -> TypeApplicationAnnotation(
                constructor = "SortedMap",
                args = listOf(
                    convertMezzeTypeToAnnotation(type.keyType),
                    convertMezzeTypeToAnnotation(type.valueType)
                )
            )
            is MezzeType.SortedSetType -> TypeApplicationAnnotation(
                constructor = "SortedSet",
                args = listOf(convertMezzeTypeToAnnotation(type.elementType))
            )
            is MezzeType.MapType -> TypeApplicationAnnotation(
                constructor = "Map",
                args = listOf(
                    convertMezzeTypeToAnnotation(type.keyType),
                    convertMezzeTypeToAnnotation(type.valueType)
                )
            )
            is MezzeType.SuspendedType -> convertMezzeTypeToAnnotation(type.valueType)
            is MezzeType.VariantType -> IdentifierTypeAnnotation("Variant")
            is MezzeType.EffectRow -> throw IllegalArgumentException("Cannot convert EffectRow to TypeAnnotation")
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

    fun collectFromType(type: MezzeType) {
        when (type) {
            is MezzeType.VarType -> collector.add(type.variable)
            is MezzeType.FunctionType -> {
                collectFromType(type.paramType)
                collectFromType(type.returnType)
                type.abilityConstraints.forEach { constraint ->
                    constraint.typeArgs.forEach { collectFromType(it) }
                }
            }
            is MezzeType.RecordType -> type.fields.values.forEach { collectFromType(it) }
            is MezzeType.VariantType -> type.constructors.values.forEach { it?.let { collectFromType(it) } }
            is MezzeType.TypeApplication -> type.args.forEach { collectFromType(it) }
            is MezzeType.ListType -> collectFromType(type.elementType)
            is MezzeType.SeqType -> collectFromType(type.elementType)
            is MezzeType.SetType -> collectFromType(type.elementType)
            is MezzeType.SortedMapType -> {
                collectFromType(type.keyType)
                collectFromType(type.valueType)
            }
            is MezzeType.SortedSetType -> collectFromType(type.elementType)
            is MezzeType.MapType -> {
                collectFromType(type.keyType)
                collectFromType(type.valueType)
            }
            is MezzeType.SuspendedType -> collectFromType(type.valueType)
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
            is SeqLiteral -> e.elements.forEach { element ->
                when (element) {
                    is SeqRegularElement -> collect(element.expression)
                    is SeqSpreadElement -> collect(element.expression)
                }
            }
            is SeqAccess -> {
                collect(e.seq)
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
