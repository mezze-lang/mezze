package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.SpreadSyntaxError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.parsedast.diagnostics.ErrorCode

/**
 * Record literal and member-access constraint generators.
 *
 * Extension functions on [ExpressionConstraintGenerator].
 */

// ========================================================================
// RECORDS
// ========================================================================

internal fun ExpressionConstraintGenerator.generateRecordLiteral(expr: ParsedRecordLit, context: ConstraintContext): ConstraintResult {
    // {field1 = expr1, field2 = expr2, ...}
    // or {...spread, field1 = expr1, ...}

    val fields = linkedMapOf<String, MezzeType>()  // Preserve insertion order
    val collector = ConstraintCollector()

    // Pre-pass: collect ALL explicit field names upfront
    // This is needed because spread validation needs to know all field names,
    // even if they appear after the spread in source order
    val explicitFieldNames = expr.entries
        .filterIsInstance<ParsedRecordEntry.Field>()
        .map { it.name }
        .toSet()

    // First pass: collect explicit field types
    val explicitFieldTypes = mutableMapOf<String, MezzeType>()

    // IMPORTANT: Clear expected type when generating field values
    // The expected type is for the record as a whole, not individual field values.
    // Without this, a type annotation like `{} -> Continuation` would propagate
    // the `Continuation` expected type to all field value expressions (including lambdas).
    val fieldContext = context.clearExpectedType()

    for (entry in expr.entries) {
        when (entry) {
            is ParsedRecordEntry.Field -> {
                val fieldValue = entry.value ?: ParsedIdentifier(localName = entry.name, loc = entry.loc)
                val fieldResult = generate(fieldValue, fieldContext)

                // If the field value is a SuspendedType (effectful value), unwrap it:
                // - Use the inner valueType for the field type (effects are evaluated, not stored)
                // - Propagate the effects upward to the record expression
                // IMPORTANT: Propagate BOTH effects AND effectInstances from the SuspendedType
                val fieldType = when (val t = fieldResult.type) {
                    is MezzeType.SuspendedType -> {
                        collector.effects.addAll(t.effects.effects)  // Propagate simple effects from SuspendedType
                        collector.effectInstances.addAll(t.effects.effectInstances)  // Propagate effect instances (with associated types)
                        t.valueType  // Use the unwrapped value type
                    }
                    else -> t
                }

                explicitFieldTypes[entry.name] = fieldType
                collector.merge(fieldResult)
            }
            is ParsedRecordEntry.Spread -> {
                // Spread: {...baseRecord}
                // Process the spread expression and extract its fields
                val spreadResult = generate(entry.expr, fieldContext)
                collector.merge(spreadResult)

                // Extract fields from the spread type and merge into our record
                // The spread expression must be a record type (or unify with one via constraints)
                // Also handle type aliases (TypeApplication) by resolving to underlying RecordType
                val resolvedSpreadType = when (val spreadType = spreadResult.type) {
                    is MezzeType.RecordType -> spreadType
                    is MezzeType.TypeApplication -> {
                        // Resolve type alias to underlying record type
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
                    is MezzeType.VarType -> null  // Type not yet known
                    else -> null
                }

                when {
                    resolvedSpreadType != null -> {
                        // VALIDATION: Check that all explicit fields exist in the spread record
                        // Mezze spread semantics: override only, no field addition
                        for (fieldName in explicitFieldNames) {
                            if (fieldName !in resolvedSpreadType.fields) {
                                throw SpreadSyntaxError.FieldAdditionNotAllowed(
                                    fieldName,
                                    resolvedSpreadType.fields.keys.toList()
                                )
                            }
                        }

                        // Add all fields from the spread record in their original order
                        // Explicit fields will override values but we use spread order as base
                        for ((fieldName, fieldType) in resolvedSpreadType.fields) {
                            // Use explicit type if available, otherwise spread type
                            fields[fieldName] = explicitFieldTypes[fieldName] ?: fieldType
                        }
                    }
                    spreadResult.type is MezzeType.VarType -> {
                        // Spread type is not yet known — constrain it to be a record (open)
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
                        // Spread expression is not a record - throw an error
                        throw SpreadSyntaxError.NonRecordSpread(prettyPrintType(spreadResult.type))
                    }
                }
            }
        }
    }

    // Second pass: add any explicit fields that weren't in a spread (new fields)
    // Note: if there was a spread, all fields should already be in 'fields' map
    // and we validated that explicit fields exist in the spread record
    for ((fieldName, fieldType) in explicitFieldTypes) {
        if (fieldName !in fields) {
            fields[fieldName] = fieldType
        }
    }

    // VALIDATION: If expected type is present AND is a type alias, validate closed record constraints
    // This handles type annotations like: let x: ClosedPerson = { ... }
    // where ClosedPerson is a type alias for a closed record
    // IMPORTANT: Only validate TypeApplications (user-defined type aliases), NOT plain RecordTypes
    // (plain RecordTypes come from nested records and should be validated by constraint solving instead)
    val expectedType = context.expectedType
    if (expectedType != null && expectedType is MezzeType.TypeApplication) {
        // Look up type declaration and resolve to RecordType
        val typeDecl = context.typeDeclarations[expectedType.constructor]
        if (typeDecl != null && typeDecl.definition is ParsedRecordDef) {
            val recordDef = typeDecl.definition as ParsedRecordDef
            // Convert ParsedRecordDef to RecordType (for validation purposes only)
            val fields = recordDef.fields.associate { field ->
                field.name to convertParsedFieldType(field.type)
            }
            val resolvedType = MezzeType.RecordType(
                fields = fields,
                rowVar = if (recordDef.isOpen) RowVar("rest") else null
            )

            // Validate record literal fields against the type declaration
            val allFieldNames = fields.keys

            // Check for missing declared fields (required for BOTH open and closed records)
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

            // Check for extra fields (only for CLOSED records — open records allow extras)
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
        // ParsedRecordLit has no isOpen field — open record syntax not captured at parse level
        rowVar = null
    )

    return collector.toResult(recordType)
}

/**
 * Compute the Loc pointing only at the `.member` portion of a
 * member-access expression, so a Rename FixHint can replace just the
 * field name. `expr.loc` covers the whole `receiver.member` span,
 * which would replace the receiver too. We derive the member span
 * by walking back `member.length` columns from the expression's end.
 */
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
    // receiver.member

    // Special case: Check if this is an ability method access
    // Example: Show.show, NumAdd.add, Eq.eq, etc.

    // Get receiver name (works for both ParsedIdentifier and ParsedVariantConstruct)
    val receiverName = when (expr.receiver) {
        is ParsedIdentifier -> (expr.receiver as ParsedIdentifier).localName
        is ParsedVariantConstruct -> (expr.receiver as ParsedVariantConstruct).constructor
        else -> null
    }

    if (receiverName != null) {
        // Direct Ability.method access — return the FULL method type (with `it` param).
        // Example: Eq.eq returns {it: 'a, r: 'a} -> Bool
        // Check both: built-in abilities AND user-defined/stdlib abilities (which may
        // shadow the built-in due to transitive collection from stdlib modules).
        if (BuiltInAbilities.isBuiltInAbility(receiverName)) {
            val freshTypeVar = MezzeType.VarType(TypeVar.fresh())
            val methodType = BuiltInAbilities.getAbilityMethodType(
                receiverName,
                expr.member,
                freshTypeVar
            )

            if (methodType != null) {
                // Return the full method type for direct calls: Ability.method {it = x, r = y}
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
                // Method not found on built-in ability - throw error with suggestion
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

        // Check if this is a user-defined ability (using dedicated map)
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

        // Check if this is an effect (using dedicated map)
        val effectOperationType = context.typingContext.getEffectOperationType(receiverName)
        if (effectOperationType != null) {
            val binding = TypingContext.Binding.Monomorphic(effectOperationType)
            // Try effect operation first (if it produces suspended type)
            val effectResult = tryGenerateEffectOperationCall(expr, receiverName, binding, context)
            if (effectResult != null) {
                return effectResult
            }
        }

        // Check if this is a performer method call (e.g., BasicLogger.info)
        // Performers implement effects, so we need to look up the effect and its operations
        val effectName = context.performerToEffect[receiverName]
        if (effectName != null) {
            // This is a performer - look up the effect operations using dedicated map
            val effectOpsType = context.typingContext.getEffectOperationType(effectName)
            if (effectOpsType != null) {
                val effectBinding = TypingContext.Binding.Monomorphic(effectOpsType)
                // Generate performer method call - similar to effect operation but bound to specific performer
                val performerResult = tryGeneratePerformerMethodCall(expr, receiverName, effectName, effectBinding, context)
                if (performerResult != null) {
                    return performerResult
                }
            }
        }

        // CRITICAL FIX: If receiver name starts with uppercase but wasn't recognized as
        // a built-in ability, user-defined ability, effect, or performer, it's an error.
        // This catches undefined ability/effect usage like Show.show when Show is not defined.
        if (receiverName.first().isUpperCase()) {
            // Check if it's in the typing context (could be a type or variant constructor with methods)
            val contextLookup = context.typingContext.lookup(receiverName)
            if (contextLookup is TypingContext.LookupResult.NotFound) {
                // Not found anywhere - this is likely an undefined ability or effect
                // Check what it might be and give a helpful error
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

    // Normal case: receiver must be record with 'member' field
    val receiverResult = generate(expr.receiver, context)
    val fieldType = MezzeType.VarType(TypeVar.fresh())

    // Unwrap SuspendedType from receiver — effects are tracked
    // separately and propagated to the outer ConstraintResult below.
    // Without this, the HasField constraint binds against the
    // SuspendedType wrapper rather than the inner record, and the
    // field type drifts to a wrong shape.
    val receiverValueType = when (val t = receiverResult.type) {
        is MezzeType.SuspendedType -> t.valueType
        else -> t
    }

    // CRITICAL: Validate field access on record literals (matches OldTypeChecker.kt:6260-6310)
    // Check if field exists for:
    // 1. Identifiers bound to record literals (e.g., let x = {a = 1}; x.b)
    // 2. Nested field access where intermediate type is a known RecordType (e.g., data.point.z)

    // First, determine if we should validate field existence
    val shouldValidate = if (expr.receiver is ParsedIdentifier) {
        // For identifiers, only validate if bound to a record literal
        val rcvr = expr.receiver as ParsedIdentifier
        val rcvrKey = BindingKey(qualifier = rcvr.qualifier?.segments?.lastOrNull(), localName = rcvr.localName).toKeyString()
        rcvrKey in context.recordLiteralBindings
    } else if (expr.receiver is ParsedMemberAccess) {
        // For nested access, validate if the receiver type is a known RecordType
        // This handles cases like data.point.z where point has type {x: Int, y: Int}
        receiverResult.type is MezzeType.RecordType
    } else {
        false
    }

    if (shouldValidate) {
        // CRITICAL: Resolve TypeApplication to get the actual fields
        val (fieldNames, isOpen) = when (val t = receiverResult.type) {
            is MezzeType.RecordType -> t.fields.keys to (t.rowVar != null)
            is MezzeType.TypeApplication -> {
                // Look up the type alias definition to get its fields
                context.typeDeclarations[t.constructor]?.let { typeDecl ->
                    when (val def = typeDecl.definition) {
                        is ParsedRecordDef -> def.fields.map { it.name }.toSet() to def.isOpen
                        else -> null
                    }
                } ?: (emptySet<String>() to false)
            }
            else -> emptySet<String>() to false
        }

        // Check if field exists
        if (fieldNames.isNotEmpty() && !fieldNames.contains(expr.member)) {
            // Field doesn't exist - check if record is closed or open
            val suggestion = findClosestMatch(expr.member, fieldNames)
            val didYouMean = if (suggestion != null) " Did you mean '$suggestion'?" else ""
            // Build a Rename FixHint pointing at the misspelled field's
            // loc so the LSP can offer a one-click "Replace with `x`"
            // quick fix in addition to the "Did you mean" hint in the
            // message text. Pin to the member name only (expr.loc covers
            // the whole `receiver.member` span which is too wide).
            val memberLoc = memberAccessFieldLoc(expr)
            val renameHints: List<com.mezze.language.parsedast.diagnostics.FixHint> =
                if (suggestion != null) listOf(
                    com.mezze.language.parsedast.diagnostics.FixHint.Rename(memberLoc, listOf(suggestion))
                ) else emptyList()

            if (!isOpen) {
                // Closed record - field must exist, throw error
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
                // Open record with explicit literal - still fail
                // OldTypeChecker validates this too (line 6285-6300)
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

    // Determine if this is a strict field check (record literal context)
    // For strict checks, field accumulation is disallowed - the field must exist exactly
    // This applies to:
    // 1. Direct access on a record literal binding: data.x where data = {...}
    // 2. Nested access on a record literal binding: data.point.z where data = {point = {...}, ...}
    val isStrictFieldCheck = isRecordLiteralAccess(expr.receiver, context)

    // Add HasField constraint — use the unwrapped value type so the
    // solver matches the field against the underlying record, not the
    // SuspendedType wrapper.
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

    // Propagate the receiver's effects to the outer ConstraintResult.
    // Without this, `(Foo.run {}).a` loses the <Foo> effect entirely
    // (the receiver's ConstraintResult carries it, but the member
    // access used to drop it on the floor).
    return ConstraintResult(
        type = fieldType,
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

/**
 * Strip the receiver parameter from a method's function type.
 *
 * Convention: the first field of an ability method's param record is the receiver.
 * - `show: {val: 'a} -> Str` → `{} -> Str`
 * - `eq: {l: 'a, r: 'a} -> Bool` → `{r: 'a} -> Bool`
 * - `and: {l: Bool, r: Bool} -> Bool` → `{r: Bool} -> Bool`
 */
