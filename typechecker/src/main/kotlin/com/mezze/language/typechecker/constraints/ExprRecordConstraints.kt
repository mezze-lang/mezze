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
    // #335: pass-through rows for spread sources whose type is still a VarType at
    // generation time (the common `{ r } -> { ...r, x = ... }` lambda-param case).
    // The source's fields aren't known yet, so the result record must stay OPEN on
    // this row var and the source must be constrained to carry the explicit
    // overrides plus this same tail — so `promote { r = base }.other_field` keeps
    // every unmentioned field of `base`. Deferred to the post-loop because the
    // explicit field entries can follow the spread entry.
    val varSpreadSources = mutableListOf<Pair<MezzeType, RowVar>>()

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

                val fieldType = fieldResult.type

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
                        // #335: the source is an unresolved record (e.g. a lambda
                        // param). Record a shared pass-through tail; the post-loop
                        // constrains the source to { overrides... | tail } and opens
                        // the result record on the SAME tail, so the source's
                        // unmentioned fields flow into the result type instead of
                        // being dropped.
                        varSpreadSources.add(spreadResult.type as MezzeType.VarType to RowVar.fresh())
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
        // A spread update is type-preserving: the override must keep each field's
        // type. For a NON-record field this is a plain equality (#75). For a
        // RECORD-typed field we cannot demand structural equality — a legitimate
        // nested update (`{ ...r, inner = { ...r.inner, y = 2 } }`) yields an inner
        // record whose row openness differs from the (closed) promised field type,
        // and the record-literal result is always closed, so an Equality would
        // spuriously report a missing/extra field. But fully skipping record fields
        // is a SOUNDNESS HOLE (H1): `{ ...r, inner = { x = "s" } }` where
        // r.inner.x : Int type-checked, then `s.inner.x + 1` crashed at runtime with
        // "Cannot add String and Long". So: open the promised field type on a fresh
        // row var (extra override fields are absorbed) and unify the override INTO
        // it. Any shared field whose type CHANGED (Int→Str) fails to unify and is
        // rejected; a wider/nested-update override still succeeds.
        val expected = if (spreadType is MezzeType.RecordType && spreadType.rowVar == null)
            spreadType.copy(rowVar = RowVar.fresh())
        else spreadType
        collector.constraints.add(
            Constraint.Equality(
                left = explicitType,
                right = expected,
                context = "Record update field '$fieldName' must keep its type",
                loc = expr.loc
            )
        )
    }

    // #335: for every spread source that was still a VarType, tie it to the result.
    // The source must be an open record carrying the explicit override fields plus
    // a fresh pass-through tail; the result record is opened on that same tail so
    // the source's other (unmentioned) fields become part of the result type.
    // Without this, `{ r } -> { ...r, x = 1 }` inferred `{ x: Int }` and dropped
    // every field of `r` — a later `.field` access then failed to type-check.
    val resultRowVar: RowVar? = varSpreadSources.lastOrNull()?.second
    for ((source, tail) in varSpreadSources) {
        collector.constraints.add(
            Constraint.Equality(
                left = source,
                right = MezzeType.RecordType(fields = explicitFieldTypes.toMap(), rowVar = tail),
                context = "Spread expression must be a record type",
                loc = expr.loc
            )
        )
        // #403: a spread source (`{ ...r, x = 1 }`) FORWARDS its whole row into the
        // result — `r`'s unmentioned fields flow through. That is genuine
        // row-polymorphism (carve-out #2), so the source's pass-through tail must
        // stay OPEN and survive the field-access-inferred-row close. Without this,
        // closing `r`'s tail would reject a caller passing a record wider than the
        // explicit override fields (FnReturnSpreadFieldTest). MustBeOpen pins the
        // resolved tail into mustBeOpenRowVars, exempting it from
        // closeGeneratedRowVars.
        collector.constraints.add(
            Constraint.MustBeOpen(
                type = source,
                context = "Spread source forwards its row; keep it open (#403)",
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
        // #335: open on the pass-through tail when a VarType spread contributed
        // fields we can't yet name; closed otherwise (the usual case).
        rowVar = resultRowVar
    )

    return collector.toResult(adoptRecordAlias(recordType, context))
}

/**
 * The constraint-generation entry to record-alias adoption: materializes the
 * in-scope CLOSED record aliases from this context's type declarations, then
 * delegates to THE canonical `adoptRecordAlias` rule (S3.1, in the ast module,
 * shared with emit). Adopting HERE (at constraint generation) rather than only at
 * emit makes the adopted `Declared` identity flow to dispatch and mono too, not just
 * display. See the ast-module `adoptRecordAlias` for the rule and its guards.
 */
internal fun ExpressionConstraintGenerator.adoptRecordAlias(
    record: MezzeType.RecordType,
    context: ConstraintContext
): MezzeType.RecordType {
    // S3.1: materialize the in-scope CLOSED record aliases this constraint context
    // knows about, then hand off to THE canonical adoption rule (shared with emit).
    // Only declarations with this record's field names can match, and converting
    // every in-scope record type's fields for every record literal was most of
    // what typing one allocated.
    if (record.rowVar != null || record.fields.isEmpty()) return record
    val names = record.fields.keys
    val aliases = context.typeDeclarations.entries.mapNotNull { (aliasName, typeDecl) ->
        val def = typeDecl.definition as? ParsedRecordDef ?: return@mapNotNull null
        if (def.isOpen) return@mapNotNull null
        if (def.fields.size != names.size || def.fields.any { it.name !in names }) return@mapNotNull null
        val hash = typeDecl.identity
        Triple(aliasName, hash, def.fields.associate { it.name to convertParsedFieldType(it.type) })
    }
    return com.mezze.language.ast.adoptRecordAlias(record, aliases)
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
        // A NAME is not an ability. `BuiltInAbilities` is a name SET, so a module
        // declaring its own `ability Default` was routed down the BUILT-IN path —
        // skipping the "Direct ability access is not allowed" rejection that every
        // other user ability gets, and then dispatching through the built-in's
        // notion of the method, which blew up at runtime with "Expected record for
        // field 'default', got: MezzeVariant". Take this path only when the name in
        // scope really IS the built-in ability: nothing here declares it, or what
        // does is that same declaration by content hash.
        val declaredHere = context.abilityDeclarations[receiverName]
        val receiverIsBuiltInAbility = BuiltInAbilities.isBuiltInAbility(receiverName) &&
            (declaredHere == null ||
                (declaredHere.identity) ==
                    com.mezze.language.typechecker.WellKnownHashConstants.resolveAbilityHash(receiverName))
        if (receiverIsBuiltInAbility) {
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
                    // As above: computed for the sentence, dropped for every tool.
                    fixHints = suggestion?.let { listOf(FixHint.Rename(expr.loc, listOf(it))) }.orEmpty(),
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

                // The candidate NAME, kept apart from the sentence about it — the
                // sentence is for a reader and the name is what a tool applies.
                val candidate = similarAbility ?: similarEffect ?: similarBuiltIn
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
                    // The RECEIVER's span, not the whole expression's: the name to
                    // replace is `Foo` in `Foo.bar`, and a fix spanning the entire
                    // member access would delete the method call along with it.
                    fixHints = candidate?.let {
                        listOf(FixHint.Rename(expr.receiver.loc, listOf(it)))
                    }.orEmpty(),
                )
            }
        }
    }

    val receiverResult = generate(expr.receiver, context)
    val fieldType = MezzeType.VarType(TypeVar.fresh())

    val receiverValueType = receiverResult.type

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
            methodCallee = context.memberAccessIsCallee,
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

    // Copied from the receiver rather than rebuilt field-by-field: every entry in
    // the old list was `receiverResult.<same field>`, so the list only decided
    // which of the receiver's tables survived — and the ones nobody typed did not.
    return ConstraintResult.operandOf(receiverResult).copy(
        type = resultType,
        constraints = constraints + receiverResult.constraints,
    )
}

