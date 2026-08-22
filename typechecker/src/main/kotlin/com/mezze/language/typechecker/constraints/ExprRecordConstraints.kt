package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint

internal fun ExpressionConstraintGenerator.generateRecordLiteral(expr: ParsedRecordLit, context: ConstraintContext): ConstraintResult {

    val fields = linkedMapOf<String, MezzeType>()
    val collector = ConstraintCollector()

    val explicitFieldEntries = expr.entries.filterIsInstance<ParsedRecordEntry.Field>()
    val explicitFieldNames = explicitFieldEntries.map { it.name }.toSet()

    // #78: reject a field name that appears more than once (`{ a = 1, a = 2 }`).
    // Left unchecked, the map/set collapse silently keeps one value; with
    // conflicting types (`{ a = 1, a = "s" }`) the wrong-typed field becomes
    // readable — a soundness-adjacent hole. Report at the duplicate's location.
    val seenFieldNames = HashSet<String>()
    for (entry in explicitFieldEntries) {
        if (!seenFieldNames.add(entry.name)) {
            throw TypeCheckError(
                message = "Duplicate field '${entry.name}' in record literal. Each field may appear at most once.",
                loc = entry.loc,
                errorType = ErrorType.TYPE_MISMATCH,
                code = ErrorCode.T0304_DUPLICATE_RECORD_FIELD,
                fixHints = listOf(FixHint.RemoveRange(entry.loc, "Remove duplicate field `${entry.name}`")),
            )
        }
    }

    val explicitFieldTypes = mutableMapOf<String, MezzeType>()
    // #75: real field types contributed by a resolved spread source, keyed by
    // field name. Used by the post-loop to constrain override values to the
    // spread's field type (a spread update is type-preserving).
    val spreadFieldTypes = mutableMapOf<String, MezzeType>()

    val fieldContext = context.clearExpectedType()
    // Bidirectional: if we're checking this literal against a known record type,
    // push each field's expected type down into its value expression (so e.g. a
    // lambda field gets its param types top-down). Falls back to pure inference
    // per field when the expected type isn't a record. The post-hoc checks below
    // stay as the correctness backstop.
    val expectedFields = expectedRecordFields(context.expectedType, context)

    for (entry in expr.entries) {
        when (entry) {
            is ParsedRecordEntry.Field -> {
                val fieldValue = entry.value ?: ParsedIdentifier(localName = entry.name, loc = entry.loc)
                val fieldResult = generateWithExpected(fieldValue, expectedFields?.get(entry.name), fieldContext)

                val fieldType = when (val t = fieldResult.type) {
                    is MezzeType.SuspendType -> {
                        collector.effects.addAll(t.effects.effects)
                        collector.effectInstances.addAll(t.effects.effectInstances)
                        t.valueType
                    }
                    else -> t
                }

                explicitFieldTypes[entry.name] = fieldType
                collector.merge(fieldResult)
            }
            is ParsedRecordEntry.Spread -> {
                val spreadResult = generate(entry.expr, fieldContext)
                collector.merge(spreadResult)

                val resolvedSpreadType = when (val spreadType = spreadResult.type) {
                    is MezzeType.RecordType -> spreadType
                    is MezzeType.TypeApplication -> {
                        val typeDecl = context.typeDeclarations[spreadType.constructor]
                        if (typeDecl != null && typeDecl.definition is ParsedRecordDef) {
                            val recordDef = typeDecl.definition as ParsedRecordDef
                            val resolvedFields = recordDef.fields.associate { field ->
                                field.name to convertParsedFieldType(field.type)
                            }
                            MezzeType.RecordType(
                                fields = resolvedFields,
                                rowVar = if (recordDef.isOpen) RowVar("rest") else null
                            )
                        } else null
                    }
                    is MezzeType.VarType -> null
                    else -> null
                }

                when {
                    resolvedSpreadType != null -> {
                        for (fieldEntry in explicitFieldEntries) {
                            val fieldName = fieldEntry.name
                            if (fieldName !in resolvedSpreadType.fields) {
                                val available = resolvedSpreadType.fields.keys.toList()
                                throw TypeCheckError(
                                    message = "Cannot add new field '$fieldName' to record type. " +
                                        "Only existing fields can be overridden. " +
                                        "Available fields: ${available.joinToString(", ")}",
                                    loc = fieldEntry.loc,
                                    errorType = ErrorType.TYPE_MISMATCH,
                                    code = ErrorCode.T0203_RECORD_EXTRA_FIELDS,
                                    fixHints = listOf(
                                        FixHint.RemoveRange(fieldEntry.loc, "Remove field `$fieldName`")
                                    ),
                                )
                            }
                        }

                        for ((fieldName, fieldType) in resolvedSpreadType.fields) {
                            // #75: record the spread's real field type. The post-loop
                            // constrains any override value to it so a type-changing
                            // update (`{...base, x = "s"}` with base.x:Int) fails —
                            // a spread update is type-preserving (additions already
                            // rejected above). Deferred to the post-loop because the
                            // spread entry can precede the explicit field entries.
                            spreadFieldTypes[fieldName] = fieldType
                            fields[fieldName] = explicitFieldTypes[fieldName] ?: fieldType
                        }
                    }
                    spreadResult.type is MezzeType.VarType -> {
                        val spreadRecordType = MezzeType.RecordType(
                            fields = emptyMap(),
                            rowVar = RowVar.fresh()
                        )
                        collector.constraints.add(
                            Constraint.Equality(
                                left = spreadResult.type,
                                right = spreadRecordType,
                                context = "Spread expression must be a record type",
                                loc = entry.expr.loc
                            )
                        )
                    }
                    else -> {
                        throw TypeCheckError(
                            message = "Cannot spread non-record value of type ${prettyPrintType(spreadResult.type)}",
                            loc = entry.expr.loc,
                            errorType = ErrorType.TYPE_MISMATCH,
                            code = ErrorCode.V0708_SPREAD_ONLY_RECORD_FORBIDDEN,
                        )
                    }
                }
            }
        }
    }

    // #75: enforce type-preservation for spread overrides. Emitted here (not in
    // the loop) because the spread entry can precede the explicit field entries,
    // so explicitFieldTypes isn't complete until every entry is walked. Only
    // fields with a known spread type (the resolved-record branch) participate;
    // the open-var branch keeps its original permissive shape.
    for ((fieldName, explicitType) in explicitFieldTypes) {
        val spreadType = spreadFieldTypes[fieldName] ?: continue
        // Skip record-typed fields: an override there may legitimately be a
        // row-open record-update whose result type isn't structurally identical
        // to the (closed) spread field type, and the record-literal result is
        // always closed (rowVar=null), so a plain Equality would spuriously
        // report "missing <field>" on a nested update. The soundness hole this
        // guards is primitive-vs-primitive (Int overridden with Str), so
        // constraining non-record fields is enough to catch it.
        if (spreadType is MezzeType.RecordType || explicitType is MezzeType.RecordType) continue
        collector.constraints.add(
            Constraint.Equality(
                left = explicitType,
                right = spreadType,
                context = "Record update field '$fieldName' must keep its type",
                loc = expr.loc
            )
        )
    }

    for ((fieldName, fieldType) in explicitFieldTypes) {
        if (fieldName !in fields) {
            fields[fieldName] = fieldType
        }
    }

    val expectedType = context.expectedType
    if (expectedType != null && expectedType is MezzeType.TypeApplication) {
        val typeDecl = context.typeDeclarations[expectedType.constructor]
        if (typeDecl != null && typeDecl.definition is ParsedRecordDef) {
            val recordDef = typeDecl.definition as ParsedRecordDef
            val fields = recordDef.fields.associate { field ->
                field.name to convertParsedFieldType(field.type)
            }
            val resolvedType = MezzeType.RecordType(
                fields = fields,
                rowVar = if (recordDef.isOpen) RowVar("rest") else null
            )

            val allFieldNames = fields.keys

            val missingFields = resolvedType.fields.keys - allFieldNames
            if (missingFields.isNotEmpty()) {
                val missingList = missingFields.joinToString(", ")
                throw TypeCheckError(
                    message = "Record is missing ${if (missingFields.size == 1) "field" else "fields"}: $missingList. " +
                            "Expected fields: ${resolvedType.fields.keys.sorted().joinToString(", ")}",
                    loc = expr.loc,
                    errorType = ErrorType.MISSING_FIELD,
                    metadata = mapOf(
                        "missingFields" to missingFields.toList(),
                        "allFieldNames" to allFieldNames.toList()
                    ),
                    code = ErrorCode.T0202_RECORD_MISSING_FIELDS,
                )
            }

            if (resolvedType.rowVar == null) {
                val extraFields = allFieldNames - resolvedType.fields.keys
                if (extraFields.isNotEmpty()) {
                    val extraList = extraFields.joinToString(", ")
                    val expectedList = resolvedType.fields.keys.sorted().joinToString(", ")
                    throw TypeCheckError(
                        message = "Record has extra ${if (extraFields.size == 1) "field" else "fields"}: $extraList. " +
                                "Remove ${if (extraFields.size == 1) "it" else "them"}. Expected fields: $expectedList",
                        loc = expr.loc,
                        errorType = ErrorType.TYPE_MISMATCH,
                        metadata = mapOf(
                            "extraFields" to extraFields.toList(),
                            "expectedFields" to resolvedType.fields.keys.toList()
                        ),
                        code = ErrorCode.T0203_RECORD_EXTRA_FIELDS,
                    )
                }
            }
        }
    }

    val recordType = MezzeType.RecordType(
        fields = fields,
        rowVar = null
    )

    return collector.toResult(recordType)
}

internal fun ExpressionConstraintGenerator.memberAccessFieldLoc(expr: ParsedMemberAccess): Loc {
    val len = expr.member.length
    val startCol = (expr.loc.endCol - len + 1).coerceAtLeast(1)
    return Loc(
        file = expr.loc.file,
        startLine = expr.loc.endLine,
        startCol = startCol,
        endLine = expr.loc.endLine,
        endCol = expr.loc.endCol,
    )
}

internal fun ExpressionConstraintGenerator.generateMemberAccess(expr: ParsedMemberAccess, context: ConstraintContext): ConstraintResult {

    val receiverName = when (expr.receiver) {
        is ParsedIdentifier -> (expr.receiver as ParsedIdentifier).localName
        is ParsedVariantConstruct -> (expr.receiver as ParsedVariantConstruct).constructor
        else -> null
    }

    if (receiverName != null) {
        if (BuiltInAbilities.isBuiltInAbility(receiverName)) {
            val freshTypeVar = MezzeType.VarType(TypeVar.fresh())
            val methodType = BuiltInAbilities.getAbilityMethodType(
                receiverName,
                expr.member,
                freshTypeVar
            )

            if (methodType != null) {
                return ConstraintResult(
                    type = methodType,
                    constraints = emptyList(),
                    effects = emptySet(),
                    effectInstances = emptySet(),
                    abilityConstraints = listOf(Constraint.AbilityRequired(
                        typeVar = freshTypeVar.variable,
                        abilityName = receiverName,
                        abilityHash = context.abilityDeclarations[receiverName]?.contentHash,
                        typeArgs = emptyList(),
                        context = "Direct ability call: ${receiverName}.${expr.member}",
                        loc = expr.loc
                    )),
                    associatedTypeConstraints = emptyList(),
                    undefinedVariables = emptyList(),
                    usedVariables = emptySet()
                )
            } else {
                val availableMethods = BuiltInAbilities.getAvailableMethods(receiverName)
                val suggestion = findClosestMatch(expr.member, availableMethods.toSet())
                val didYouMean = if (suggestion != null) " Did you mean '$suggestion'?" else ""
                throw TypeCheckError(
                    message = "Method '${expr.member}' not found in ability '$receiverName'.$didYouMean Available methods: ${availableMethods.joinToString(", ")}",
                    loc = expr.loc,
                    errorType = ErrorType.MISSING_FIELD,
                    code = ErrorCode.T0206_METHOD_NOT_FOUND,
                )
            }
        }

        val abilityOperationType = context.typingContext.getAbilityOperationType(receiverName)
        if (abilityOperationType != null) {
            throw TypeCheckError(
                message = "Direct ability access '${receiverName}.${expr.member}' is not allowed. " +
                    "Use 'value.${expr.member} { args }' or '${receiverName}.${expr.member} { it = value, ... }' instead.",
                loc = expr.loc,
                errorType = ErrorType.MISSING_FIELD,
                code = ErrorCode.T0209_ABILITY_AS_VALUE,
            )
        }

        val effectOperationType = context.typingContext.getEffectOperationType(receiverName)
        if (effectOperationType != null) {
            val binding = TypingContext.Binding.Monomorphic(effectOperationType)
            val effectResult = tryGenerateEffectOperationCall(expr, receiverName, binding, context)
            if (effectResult != null) {
                return effectResult
            }
        }

        val effectName = context.performerToEffect[receiverName]
        if (effectName != null) {
            val effectOpsType = context.typingContext.getEffectOperationType(effectName)
            if (effectOpsType != null) {
                val effectBinding = TypingContext.Binding.Monomorphic(effectOpsType)
                val performerResult = tryGeneratePerformerMethodCall(expr, receiverName, effectName, effectBinding, context)
                if (performerResult != null) {
                    return performerResult
                }
            }
        }

        if (receiverName.first().isUpperCase()) {
            val contextLookup = context.typingContext.lookup(receiverName)
            if (contextLookup is TypingContext.LookupResult.NotFound) {
                val similarAbility = findClosestMatch(receiverName, context.abilityNames)
                val similarEffect = findClosestMatch(receiverName, context.effectNames)
                val builtInAbilities = BuiltInAbilities.getAllAbilityNames()
                val similarBuiltIn = findClosestMatch(receiverName, builtInAbilities)

                val suggestion = when {
                    similarAbility != null -> " Did you mean ability '$similarAbility'?"
                    similarEffect != null -> " Did you mean effect '$similarEffect'?"
                    similarBuiltIn != null -> " Did you mean built-in ability '$similarBuiltIn'?"
                    else -> ""
                }

                throw TypeCheckError(
                    message = "'$receiverName' is not defined. Cannot access method '${expr.member}' on undefined ability or effect.$suggestion",
                    loc = expr.loc,
                    errorType = ErrorType.UNDEFINED_VARIABLE,
                    code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                )
            }
        }
    }

    val receiverResult = generate(expr.receiver, context)
    val fieldType = MezzeType.VarType(TypeVar.fresh())

    val receiverValueType = when (val t = receiverResult.type) {
        is MezzeType.SuspendType -> t.valueType
        else -> t
    }

    // #277: validate a field access whenever the receiver's type is ALREADY a
    // concrete record (a RecordType, or a TypeApplication of a declared record) —
    // i.e. its shape is known, so an undeclared field is a definite error. Keyed
    // on the receiver's resolved TYPE, not on a binding-name set: `let r = {}` /
    // `r.a` and `let r = {b=1}` / `r.a` are rejected because `r`'s type is a
    // concrete record lacking the field, regardless of how `r` was bound. An
    // unannotated param `{r} -> r.a` is NOT validated here — its receiver type is
    // still a VarType (the shape is being DISCOVERED by this very access), so the
    // HasField constraint grows the row instead.
    val receiverIsConcreteRecord = when (val t = receiverResult.type) {
        is MezzeType.RecordType -> true
        is MezzeType.TypeApplication ->
            (context.typeDeclarations[t.constructor]?.definition as? ParsedRecordDef) != null
        else -> false
    }
    val shouldValidate = when (expr.receiver) {
        is ParsedIdentifier -> receiverIsConcreteRecord
        is ParsedMemberAccess -> receiverResult.type is MezzeType.RecordType
        else -> false
    }

    if (shouldValidate) {
        val (fieldNames, isOpen) = when (val t = receiverResult.type) {
            is MezzeType.RecordType -> t.fields.keys to (t.rowVar != null)
            is MezzeType.TypeApplication -> {
                context.typeDeclarations[t.constructor]?.let { typeDecl ->
                    when (val def = typeDecl.definition) {
                        is ParsedRecordDef -> def.fields.map { it.name }.toSet() to def.isOpen
                        else -> null
                    }
                } ?: (emptySet<String>() to false)
            }
            else -> emptySet<String>() to false
        }

        // #277: `shouldValidate` guarantees the receiver is a CONCRETE record, so
        // `fieldNames` is its real field set — an EMPTY set means a genuinely
        // empty record (`{}`), where every access is undeclared. Don't short-
        // circuit on emptiness (the old `fieldNames.isNotEmpty()` gate let `{}`.a
        // and unknown fields on empty records slip through).
        if (expr.member !in fieldNames) {
            val suggestion = findClosestMatch(expr.member, fieldNames)
            val didYouMean = if (suggestion != null) " Did you mean '$suggestion'?" else ""
            val memberLoc = memberAccessFieldLoc(expr)
            val renameHints: List<FixHint> =
                if (suggestion != null) listOf(
                    FixHint.Rename(memberLoc, listOf(suggestion))
                ) else emptyList()

            if (!isOpen) {
                throw TypeCheckError(
                    message = "Field '${expr.member}' not found in record.$didYouMean Available fields: ${fieldNames.joinToString()}",
                    loc = expr.loc,
                    errorType = ErrorType.MISSING_FIELD,
                    metadata = mapOf(
                        "field" to expr.member,
                        "availableFields" to fieldNames.toList()
                    ),
                    code = ErrorCode.T0205_FIELD_NOT_FOUND,
                    fixHints = renameHints,
                )
            } else {
                throw TypeCheckError(
                    message = "Field '${expr.member}' not found in open record.$didYouMean Explicitly declared fields: ${fieldNames.joinToString()}",
                    loc = expr.loc,
                    errorType = ErrorType.MISSING_FIELD,
                    metadata = mapOf(
                        "field" to expr.member,
                        "availableFields" to fieldNames.toList()
                    ),
                    code = ErrorCode.T0303_OPEN_RECORD_FIELD_MISSING,
                    fixHints = renameHints,
                )
            }
        }
    }

    val constraints = mutableListOf<Constraint>()
    constraints.addAll(receiverResult.constraints)

    val isStrictFieldCheck = isRecordLiteralAccess(expr.receiver, context)

    constraints.add(
        Constraint.HasField(
            recordType = receiverValueType,
            fieldName = expr.member,
            fieldType = fieldType,
            context = "Field access ${expr.member}",
            loc = expr.loc,
            strictFieldCheck = isStrictFieldCheck,
        )
    )

    // Effect soundness: when the receiver is an already-concrete record and the
    // accessed field is a FUNCTION type, surface that concrete function type
    // (carrying its effect row) as the access result — not the fresh `fieldType`
    // VarType. Otherwise, calling `(r.g) {}` sees an unresolved VarType callee, so
    // the call handler's `is FunctionType` effect-harvest is skipped and the
    // field function's effects are LAUNDERED (a pure-typed caller could perform
    // them untracked). The HasField constraint still unifies fieldType with the
    // field, so this only *refines* the reported type; it never loosens it.
    val concreteFieldFn = (receiverValueType as? MezzeType.RecordType)
        ?.fields?.get(expr.member) as? MezzeType.FunctionType
    val resultType: MezzeType = if (concreteFieldFn != null && concreteFieldFn.effects != null) {
        concreteFieldFn
    } else {
        fieldType
    }

    return ConstraintResult(
        type = resultType,
        constraints = constraints + receiverResult.constraints,
        effects = receiverResult.effects,
        effectInstances = receiverResult.effectInstances,
        performed = receiverResult.performed,
        abilityConstraints = receiverResult.abilityConstraints,
        associatedTypeConstraints = receiverResult.associatedTypeConstraints,
        undefinedVariables = receiverResult.undefinedVariables,
        usedVariables = receiverResult.usedVariables,
        referencedBindings = receiverResult.referencedBindings,
        methodCallResolutions = receiverResult.methodCallResolutions,
        callReturnTypes = receiverResult.callReturnTypes
    )
}

