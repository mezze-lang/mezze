package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ErrorContext
import com.mezze.language.typechecker.printing.TypeDiffPrinter

/**
 * Pure formatting helpers that turn a failed [Constraint.Equality] +
 * [UnificationResult.Failure] pair into a human-readable error string.
 *
 * Extracted from [ConstraintSolver] so the solver stays focused on
 * unification logic. Every function here is stateless — it only reads
 * the constraint, the failure, and the types involved.
 */
object ErrorMessageBuilder {

    /**
     * Top-level entry point: choose the best error message for a failed
     * equality constraint.
     */
    fun generateBetterErrorMessage(
        constraint: Constraint.Equality,
        failure: UnificationResult.Failure
    ): String {
        val type1 = constraint.left
        val type2 = constraint.right
        val errorContext = constraint.errorContext
        val stringContext = constraint.context ?: ""

        // Handle structured error context
        when (errorContext) {
            is ErrorContext.FunctionArgument -> {
                val argName = errorContext.argumentName
                val funcName = errorContext.functionName

                // Prefer a focused field-level message when we can pinpoint
                // a single field whose leaf type disagrees. Skips the noisy
                // full function-type rendering (and any fresh tyvar names
                // like `'m296`) for the common case.
                val fieldMismatch = findFieldTypeMismatch(type1, type2)
                if (fieldMismatch != null) {
                    val (path, actualLeaf, expectedLeaf) = fieldMismatch
                    val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
                    val expectedStr = TypeDiffPrinter.formatType(expectedLeaf, 0, rename)
                    val actualStr = TypeDiffPrinter.formatType(actualLeaf, 0, rename)
                    val callSite = when {
                        funcName != null -> " in call to `$funcName`"
                        else -> ""
                    }
                    return "Field `$path` expects $expectedStr but got $actualStr$callSite"
                }

                val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
                val expected = TypeDiffPrinter.formatType(type2, 0, rename)
                val actual = TypeDiffPrinter.formatType(type1, 0, rename)

                val header = if (argName != null && funcName != null) {
                    "Type mismatch for argument '$argName' in call to '$funcName': expected $expected but got $actual"
                } else if (argName != null) {
                    "Type mismatch for argument '$argName': expected $expected but got $actual"
                } else if (funcName != null) {
                    "Type mismatch in call to '$funcName': expected $expected but got $actual"
                } else {
                    "Function argument type mismatch: expected $expected but got $actual"
                }
                // Append the unifier's structured reason when it carries
                // user-friendly info (e.g. "record fields don't match:
                // missing 'x'; unexpected 'y'"). Without this, downstream
                // sees the full record types in the header but loses the
                // exact "missing/unexpected" wording that pinpoints the fix.
                return appendStructuredReason(header, failure)
            }
            is ErrorContext.TypeAnnotation -> {
                val bindingName = errorContext.bindingName

                // If the only difference is the effect row, give a much
                // more focused message — users hit this constantly when
                // a function body performs an effect they forgot to
                // declare on the annotation. Dumping both full function
                // types side-by-side just makes them squint to spot
                // the `<Log>` vs `<*>` difference.
                describeEffectRowDifference(type1, type2)?.let { reason ->
                    return "Type annotation mismatch for '$bindingName': $reason"
                }

                val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
                val expected = TypeDiffPrinter.formatType(type2, 0, rename)
                val actual = TypeDiffPrinter.formatType(type1, 0, rename)

                return if (errorContext.isReturnType) {
                    "Return type mismatch in '$bindingName': declared as $expected but returns $actual"
                } else {
                    "Type annotation mismatch for '$bindingName': declared as $expected but expression has type $actual"
                }
            }
            is ErrorContext.FieldAccess -> {
                val fieldName = errorContext.fieldName
                val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
                val expected = TypeDiffPrinter.formatType(type2, 0, rename)
                val actual = TypeDiffPrinter.formatType(type1, 0, rename)

                return "Field '$fieldName' has type $actual but expected $expected"
            }
            is ErrorContext.PatternMatch -> {
                return generatePatternMatchError(type1, type2, errorContext, stringContext)
            }
            is ErrorContext.BinaryOperation -> {
                val op = errorContext.operator
                val side = if (errorContext.isLeftOperand) "left" else "right"
                val expected = TypeDiffPrinter.formatType(type2)
                val actual = TypeDiffPrinter.formatType(type1)

                return "Type mismatch in '$op' operator ($side operand): expected $expected but got $actual"
            }
            is ErrorContext.SeqElement -> {
                val expected = TypeDiffPrinter.formatType(type2)
                val actual = TypeDiffPrinter.formatType(type1)

                return "Seq element at index ${errorContext.elementIndex} has type $actual but seq expects $expected"
            }
            is ErrorContext.ReturnType -> {
                val funcName = errorContext.functionName
                val expected = TypeDiffPrinter.formatType(type2)
                val actual = TypeDiffPrinter.formatType(type1)

                return if (funcName != null) {
                    "Return type mismatch in function '$funcName': expected $expected but got $actual"
                } else {
                    "Return type mismatch: expected $expected but got $actual"
                }
            }
            is ErrorContext.StringInterpolation -> {
                val actual = TypeDiffPrinter.formatType(type1)
                return "String interpolation requires String type, but expression has type $actual"
            }
            is ErrorContext.Simple -> {
                // Fall through to string-based handling
            }
            null -> {
                // Fall through to string-based handling
            }
        }

        // Backwards-compatible string-based context parsing
        return generateErrorFromStringContext(type1, type2, stringContext, failure)
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Append a structured "missing/unexpected" reason to a solver header
     * when the unifier failed on a closed-record field-set mismatch
     * (possibly nested inside function/record wrappers).
     *
     * Discriminates structurally on the failed types -- NOT on message
     * prefixes. Walks `failure.type1` and `failure.type2` in lockstep,
     * descending through matching function/record wrappers, until it
     * finds the deepest pair of closed records with mismatched field
     * sets. Returns the diff (missing on the expected side, extra on
     * the actual side) as actionable wording.
     *
     * Falls back to the bare header when no such mismatch is found --
     * the header's expected/actual type rendering already says enough
     * for primitive or compatible-shape mismatches.
     */
    private fun appendStructuredReason(header: String, failure: UnificationResult.Failure): String {
        val t1 = failure.type1 ?: return header
        val t2 = failure.type2 ?: return header

        // Prefer the finest-grained reason: a single field whose type
        // disagrees between actual and expected. Falls back to the
        // field-set diff for missing/unexpected fields.
        findFieldTypeMismatch(t1, t2)?.let { (path, actualLeaf, expectedLeaf) ->
            val rename = com.mezze.language.typechecker.printing.TypeVarRenamer()
            val expectedStr = TypeDiffPrinter.formatType(expectedLeaf, 0, rename)
            val actualStr = TypeDiffPrinter.formatType(actualLeaf, 0, rename)
            return "$header — field `$path` expects $expectedStr but got $actualStr"
        }
        val mismatch = findClosedRecordMismatch(t1, t2) ?: return header
        val (expected, actual) = mismatch
        val missing = (expected.fields.keys - actual.fields.keys).map { "'$it'" }
        val extra = (actual.fields.keys - expected.fields.keys).map { "'$it'" }
        val parts = mutableListOf<String>()
        if (missing.isNotEmpty()) parts.add("missing ${missing.joinToString(", ")}")
        if (extra.isNotEmpty()) parts.add("unexpected ${extra.joinToString(", ")}")
        return if (parts.isEmpty()) header else "$header — ${parts.joinToString("; ")}"
    }

    /**
     * Walk two types in lockstep, looking for the deepest record field
     * whose leaf types concretely disagree (e.g. `Int` vs `Str`).
     *
     * Returns (fieldPath, actualLeaf, expectedLeaf) on hit, or null when
     * the disagreement isn't pinpointable to a single field (e.g. tyvars
     * involved, or shapes differ above the field level -- which the
     * existing [findClosedRecordMismatch] already handles).
     *
     * Descends through matching function wrappers (param and return type)
     * and matching record wrappers (only when the field sets are identical,
     * so we're past the field-set-diff case).
     */
    private fun findFieldTypeMismatch(
        actual: MezzeType,
        expected: MezzeType,
        path: String = "",
    ): Triple<String, MezzeType, MezzeType>? {
        // Skip when either side is a tyvar -- we want concrete disagreement.
        if (actual is MezzeType.VarType || expected is MezzeType.VarType) return null

        if (actual is MezzeType.FunctionType && expected is MezzeType.FunctionType) {
            findFieldTypeMismatch(actual.paramType, expected.paramType, path)?.let { return it }
            findFieldTypeMismatch(actual.returnType, expected.returnType, path)?.let { return it }
            return null
        }
        if (actual is MezzeType.RecordType && expected is MezzeType.RecordType) {
            if (actual.fields.keys != expected.fields.keys) return null  // field-set diff handled elsewhere
            for (fieldName in actual.fields.keys) {
                val childPath = if (path.isEmpty()) fieldName else "$path.$fieldName"
                val a = actual.fields[fieldName]!!
                val e = expected.fields[fieldName]!!
                // Deeper hit takes priority -- keep walking even if the
                // current pair already disagrees, so we report the most
                // specific path.
                findFieldTypeMismatch(a, e, childPath)?.let { return it }
                // No deeper record mismatch; if this leaf disagrees and
                // neither side is a tyvar, report it.
                if (a !is MezzeType.RecordType && a !is MezzeType.FunctionType &&
                    e !is MezzeType.RecordType && e !is MezzeType.FunctionType &&
                    a !is MezzeType.VarType && e !is MezzeType.VarType &&
                    a != e
                ) {
                    return Triple(childPath, a, e)
                }
            }
        }
        return null
    }

    /**
     * Walk two types in lockstep looking for the deepest pair of closed
     * records with mismatched field sets. Returns (expected, actual) or
     * null when the shapes don't reveal a record-field mismatch.
     *
     * Convention: `actual` came from the user (the "got" side), `expected`
     * came from the signature (the "wanted" side). Matches the way the
     * solver labels type1=actual / type2=expected at its call sites.
     */
    private fun findClosedRecordMismatch(
        actual: MezzeType,
        expected: MezzeType,
    ): Pair<MezzeType.RecordType, MezzeType.RecordType>? {
        // Both records, both closed, different field sets -- found it.
        if (actual is MezzeType.RecordType && expected is MezzeType.RecordType) {
            if (actual.rowVar == null && expected.rowVar == null && actual.fields.keys != expected.fields.keys) {
                return expected to actual
            }
            // Same field set -- descend into matching fields looking for a deeper one.
            if (actual.fields.keys == expected.fields.keys) {
                for (fieldName in actual.fields.keys) {
                    val nested = findClosedRecordMismatch(actual.fields[fieldName]!!, expected.fields[fieldName]!!)
                    if (nested != null) return nested
                }
            }
            return null
        }
        // Both functions -- descend into params (most common path), then return type.
        if (actual is MezzeType.FunctionType && expected is MezzeType.FunctionType) {
            findClosedRecordMismatch(actual.paramType, expected.paramType)?.let { return it }
            findClosedRecordMismatch(actual.returnType, expected.returnType)?.let { return it }
            return null
        }
        return null
    }

    /**
     * If [actual] and [expected] are both function types and differ ONLY
     * in their effect rows (same param, same return, no other ability
     * differences), describe the effect diff in actionable English.
     * Returns null when the difference is structural elsewhere.
     *
     * Typical hit: annotation says `{x} -> Str <*>` but body has type
     * `{x} -> Str <Log>` -- we want to tell the user "effect `Log` is
     * performed but not declared (or not handled)" instead of dumping
     * both full function types and making them squint at the row.
     */
    private fun describeEffectRowDifference(actual: MezzeType, expected: MezzeType): String? {
        if (actual !is MezzeType.FunctionType || expected !is MezzeType.FunctionType) return null

        // Param + return types must be structurally identical for this
        // to be a pure effect-row mismatch.
        if (actual.paramType != expected.paramType) return null
        if (actual.returnType != expected.returnType) return null
        if (actual.abilityConstraints != expected.abilityConstraints) return null

        val actualEffects = actual.effects?.effects ?: emptySet()
        val expectedEffects = expected.effects?.effects ?: emptySet()
        if (actualEffects == expectedEffects) return null  // identical rows; some other field differs

        val extra = (actualEffects - expectedEffects).sorted()    // body performs, annotation missed
        val missing = (expectedEffects - actualEffects).sorted()  // annotation promises, body doesn't deliver

        return when {
            extra.isNotEmpty() && missing.isEmpty() -> {
                val names = extra.joinToString(", ") { "<$it>" }
                if (extra.size == 1) {
                    "the body performs effect $names but the annotation doesn't allow it. " +
                    "Either add $names to the return type (e.g. `... -> Str $names`), " +
                    "or wrap the call in a `perform <handler> in do ... end` block."
                } else {
                    "the body performs effects $names but the annotation doesn't allow them. " +
                    "Either declare them on the return type, or install handlers."
                }
            }
            missing.isNotEmpty() && extra.isEmpty() -> {
                val names = missing.joinToString(", ") { "<$it>" }
                "the annotation promises effects $names but the body never performs them. " +
                "Drop the unused effects from the annotation."
            }
            else -> {
                val extraTxt = if (extra.isNotEmpty()) "body performs ${extra.joinToString(", ") { "<$it>" }}" else null
                val missingTxt = if (missing.isNotEmpty()) "annotation expects ${missing.joinToString(", ") { "<$it>" }}" else null
                "effect rows don't match — ${listOfNotNull(missingTxt, extraTxt).joinToString("; ")}."
            }
        }
    }

    /**
     * Generate error message from string-based context (backwards compatibility).
     */
    private fun generateErrorFromStringContext(
        type1: MezzeType,
        type2: MezzeType,
        stringContext: String,
        failure: UnificationResult.Failure
    ): String {
        // Detect literal vs variant mismatch for better error message
        if (stringContext.contains("Literal pattern")) {
            // Check if one side is a variant type
            val variantType = when {
                type1 is MezzeType.VariantType -> type1
                type2 is MezzeType.VariantType -> type2
                else -> null
            }

            if (variantType != null) {
                val firstConstructor = variantType.constructors.keys.firstOrNull() ?: "ConstructorName"
                val sortedConstructors = variantType.constructors.keys.sorted()
                return "Cannot match variant type [${sortedConstructors.joinToString(", ")}] with a literal pattern. " +
                    "Use a constructor pattern like '$firstConstructor' instead."
            }

            // Check for TypeApplication (named type like Option, Result)
            val typeApp = when {
                type1 is MezzeType.TypeApplication -> type1
                type2 is MezzeType.TypeApplication -> type2
                else -> null
            }

            if (typeApp != null) {
                return "Cannot match type '${typeApp.constructor}' with a literal pattern. " +
                    "Use a constructor pattern instead."
            }
        }

        // Check for List vs Seq confusion
        if (stringContext.contains("Seq pattern")) {
            if (type1 is MezzeType.ListType || type2 is MezzeType.ListType) {
                return "Cannot use seq pattern syntax [a, b, ...] with List type. " +
                    "Use constructor patterns 'Cons { head, tail }' and 'Nil' instead."
            }
        }

        // Improve generic messages by extracting context info
        if (stringContext.isNotEmpty()) {
            val expected = TypeDiffPrinter.formatType(type2)
            val actual = TypeDiffPrinter.formatType(type1)

            // Extract useful context information
            return when {
                stringContext.contains("Function application argument") -> {
                    "Function argument type mismatch: expected $expected but got $actual"
                }
                stringContext.contains("Type annotation on") -> {
                    val bindingName = extractBindingName(stringContext)
                    if (bindingName != null) {
                        "Type annotation mismatch for '$bindingName': declared as $expected but expression has type $actual"
                    } else {
                        failure.message
                    }
                }
                stringContext.contains("Field access") -> {
                    val fieldName = extractFieldName(stringContext)
                    if (fieldName != null) {
                        "Field '$fieldName' type mismatch: expected $expected but got $actual"
                    } else {
                        failure.message
                    }
                }
                else -> failure.message
            }
        }

        return failure.message
    }

    /**
     * Generate pattern match error message.
     */
    private fun generatePatternMatchError(
        type1: MezzeType,
        type2: MezzeType,
        context: ErrorContext.PatternMatch,
        stringContext: String
    ): String {
        val expected = TypeDiffPrinter.formatType(type2)
        val actual = TypeDiffPrinter.formatType(type1)

        // Check for variant type matching
        val variantType = when {
            type1 is MezzeType.VariantType -> type1
            type2 is MezzeType.VariantType -> type2
            else -> null
        }

        if (variantType != null && context.constructorName != null) {
            return "Pattern '${context.constructorName}' expects $expected but scrutinee has type $actual"
        }

        return "Pattern match type mismatch: expected $expected but got $actual"
    }

    /**
     * Extract binding name from context string like "Type annotation on myFunc"
     */
    private fun extractBindingName(context: String): String? {
        val prefix = "Type annotation on "
        return if (context.startsWith(prefix)) {
            context.removePrefix(prefix).trim()
        } else null
    }

    /**
     * Extract field name from context string like "Field access fieldName"
     */
    private fun extractFieldName(context: String): String? {
        val prefix = "Field access "
        return if (context.startsWith(prefix)) {
            context.removePrefix(prefix).trim()
        } else null
    }
}
