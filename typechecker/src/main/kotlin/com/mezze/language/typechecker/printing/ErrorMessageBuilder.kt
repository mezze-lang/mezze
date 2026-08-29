package com.mezze.language.typechecker.printing

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.ast.prettyPrintTypePair
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ErrorContext
import com.mezze.language.typechecker.solving.UnificationResult

object ErrorMessageBuilder {

    fun generateBetterErrorMessage(
        constraint: Constraint.Equality,
        failure: UnificationResult.Failure
    ): String {
        val type1 = constraint.left
        val type2 = constraint.right
        val errorContext = constraint.errorContext
        val stringContext = constraint.context ?: ""

        when (errorContext) {
            is ErrorContext.FunctionArgument -> {
                val argName = errorContext.argumentName
                val funcName = errorContext.functionName

                // T0211: prefer the callback-param-names mismatch over the
                // generic field-type walk — the walk blames the wrong field
                // (usually `xs`) because it stops at the record whose key
                // set diverges (see findFieldTypeMismatch line 153). Users
                // need to see "your lambda's params are wrong", not
                // "field xs expects 'a but got Int". #58.
                findCallbackFieldNameMismatch(type1, type2)?.let { m ->
                    val callSite = if (funcName != null) " in call to `$funcName`" else ""
                    val pathStr = if (m.path.isEmpty()) "" else " at `${m.path}`"
                    val missing = (m.expectedFields - m.actualFields.toSet())
                        .map { "'$it'" }
                    val unexpected = (m.actualFields - m.expectedFields.toSet())
                        .map { "'$it'" }
                    val parts = mutableListOf<String>()
                    if (missing.isNotEmpty()) parts.add("missing ${missing.joinToString(", ")}")
                    if (unexpected.isNotEmpty()) parts.add("unexpected ${unexpected.joinToString(", ")}")
                    val diff = if (parts.isNotEmpty()) " — ${parts.joinToString("; ")}" else ""
                    return "Callback param names don't match$pathStr$callSite: " +
                        "expected `{ ${m.expectedFields.joinToString(", ")} }` " +
                        "but lambda destructures `{ ${m.actualFields.joinToString(", ")} }`$diff"
                }

                val fieldMismatch = findFieldTypeMismatch(type1, type2)
                if (fieldMismatch != null) {
                    val (path, actualLeaf, expectedLeaf) = fieldMismatch
                    val (expectedStr, actualStr) = prettyPrintTypePair(expectedLeaf, actualLeaf)
                    val callSite = when {
                        funcName != null -> " in call to `$funcName`"
                        else -> ""
                    }
                    return "Field `$path` expects $expectedStr but got $actualStr$callSite"
                }

                val (expected, actual) = prettyPrintTypePair(type2, type1)

                val header = if (argName != null && funcName != null) {
                    "Type mismatch for argument '$argName' in call to '$funcName': expected $expected but got $actual"
                } else if (argName != null) {
                    "Type mismatch for argument '$argName': expected $expected but got $actual"
                } else if (funcName != null) {
                    "Type mismatch in call to '$funcName': expected $expected but got $actual"
                } else {
                    "Function argument type mismatch: expected $expected but got $actual"
                }
                return appendStructuredReason(header, failure)
            }
            is ErrorContext.TypeAnnotation -> {
                val bindingName = errorContext.bindingName

                describeEffectRowDifference(type1, type2)?.let { reason ->
                    return "Type annotation mismatch for '$bindingName': $reason"
                }

                val (expected, actual) = prettyPrintTypePair(type2, type1)

                val collectionHint = seqListMismatchHint(expected = type2, actual = type1)
                return if (errorContext.isReturnType) {
                    "Return type mismatch in '$bindingName': declared as $expected but returns $actual$collectionHint"
                } else {
                    "Type annotation mismatch for '$bindingName': declared as $expected but expression has type $actual$collectionHint"
                }
            }
            is ErrorContext.FieldAccess -> {
                val fieldName = errorContext.fieldName
                val (expected, actual) = prettyPrintTypePair(type2, type1)

                return "Field '$fieldName' has type $actual but expected $expected"
            }
            is ErrorContext.PatternMatch -> {
                return generatePatternMatchError(type1, type2, errorContext, stringContext)
            }
            is ErrorContext.BinaryOperation -> {
                val op = errorContext.operator
                val side = if (errorContext.isLeftOperand) "left" else "right"
                val (expected, actual) = prettyPrintTypePair(type2, type1)

                return "Type mismatch in '$op' operator ($side operand): expected $expected but got $actual"
            }
            is ErrorContext.SeqElement -> {
                val (expected, actual) = prettyPrintTypePair(type2, type1)

                return "Seq element at index ${errorContext.elementIndex} has type $actual but seq expects $expected"
            }
            is ErrorContext.ReturnType -> {
                val funcName = errorContext.functionName
                val (expected, actual) = prettyPrintTypePair(type2, type1)

                return if (funcName != null) {
                    "Return type mismatch in function '$funcName': expected $expected but got $actual"
                } else {
                    "Return type mismatch: expected $expected but got $actual"
                }
            }
            is ErrorContext.StringInterpolation -> {
                val actual = prettyPrintType(type1)
                return "String interpolation requires String type, but expression has type $actual"
            }
            is ErrorContext.Simple -> {
            }
            null -> {
            }
        }

        return generateErrorFromStringContext(type1, type2, stringContext, failure)
    }

    private fun appendStructuredReason(header: String, failure: UnificationResult.Failure): String {
        val t1 = failure.type1 ?: return header
        val t2 = failure.type2 ?: return header

        findFieldTypeMismatch(t1, t2)?.let { (path, actualLeaf, expectedLeaf) ->
            val (expectedStr, actualStr) = prettyPrintTypePair(expectedLeaf, actualLeaf)
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
     * Descends into a nested type looking for a {@code FunctionType} where
     * both sides' parameter types are records with different field-name
     * sets. Returns the first such site plus its path. When found, this
     * indicates the user-supplied lambda destructures record fields that
     * don't match the caller's record shape — Mezze lambda params ARE
     * record destructuring by field name.
     *
     * <p>Example: fold's arg type is
     * {@code { xs: [Int], init: Int, f: { acc: Int, i: Int } -> Int }}
     * (from the user's lambda), vs expected
     * {@code { xs: ['a], init: 'b, f: { acc: 'b, x: 'a } -> 'b }}
     * (from fold's signature). Walker descends into the outer record,
     * then into the `f` field (both are FunctionTypes), then into their
     * paramType records — sees {@code {acc, i}} vs {@code {acc, x}},
     * returns the mismatch. Path is {@code "f"}.
     */
    private data class CallbackFieldNameMismatch(
        val path: String,
        val expectedFields: List<String>,
        val actualFields: List<String>,
    )

    private fun findCallbackFieldNameMismatch(
        actual: MezzeType,
        expected: MezzeType,
        path: String = "",
    ): CallbackFieldNameMismatch? {
        if (actual is MezzeType.VarType || expected is MezzeType.VarType) return null

        if (actual is MezzeType.FunctionType && expected is MezzeType.FunctionType) {
            // Both sides are functions — inspect their param types. If both
            // are closed records with different fields, that's the mismatch.
            val aParam = actual.paramType
            val eParam = expected.paramType
            if (aParam is MezzeType.RecordType && eParam is MezzeType.RecordType &&
                aParam.rowVar == null && eParam.rowVar == null &&
                aParam.fields.keys != eParam.fields.keys
            ) {
                return CallbackFieldNameMismatch(
                    path = path,
                    expectedFields = eParam.fields.keys.sorted(),
                    actualFields = aParam.fields.keys.sorted(),
                )
            }
            // Otherwise recurse into param and return types looking for a
            // nested callback with a name mismatch.
            findCallbackFieldNameMismatch(aParam, eParam, path)?.let { return it }
            findCallbackFieldNameMismatch(actual.returnType, expected.returnType, path)?.let { return it }
            return null
        }
        if (actual is MezzeType.RecordType && expected is MezzeType.RecordType) {
            // Only descend into shared fields; a mismatch on non-shared
            // fields at THIS level is not a callback mismatch (that's a
            // record-shape mismatch handled elsewhere).
            val sharedFields = actual.fields.keys intersect expected.fields.keys
            for (fieldName in sharedFields) {
                val childPath = if (path.isEmpty()) fieldName else "$path.$fieldName"
                findCallbackFieldNameMismatch(
                    actual.fields[fieldName]!!,
                    expected.fields[fieldName]!!,
                    childPath
                )?.let { return it }
            }
        }
        return null
    }

    private fun findFieldTypeMismatch(
        actual: MezzeType,
        expected: MezzeType,
        path: String = "",
    ): Triple<String, MezzeType, MezzeType>? {
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
                findFieldTypeMismatch(a, e, childPath)?.let { return it }
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

    private fun findClosedRecordMismatch(
        actual: MezzeType,
        expected: MezzeType,
    ): Pair<MezzeType.RecordType, MezzeType.RecordType>? {
        if (actual is MezzeType.RecordType && expected is MezzeType.RecordType) {
            if (actual.rowVar == null && expected.rowVar == null && actual.fields.keys != expected.fields.keys) {
                return expected to actual
            }
            if (actual.fields.keys == expected.fields.keys) {
                for (fieldName in actual.fields.keys) {
                    val nested = findClosedRecordMismatch(actual.fields[fieldName]!!, expected.fields[fieldName]!!)
                    if (nested != null) return nested
                }
            }
            return null
        }
        if (actual is MezzeType.FunctionType && expected is MezzeType.FunctionType) {
            findClosedRecordMismatch(actual.paramType, expected.paramType)?.let { return it }
            findClosedRecordMismatch(actual.returnType, expected.returnType)?.let { return it }
            return null
        }
        return null
    }

    // `Seq` (written `['a]`, produced by the `[...]` literal) and `List 'a` are
    // distinct data structures — an array-like sequence vs a cons-list — with no
    // implicit coercion. Mixing them is a common mistake because `[...]` looks
    // list-shaped, so when the mismatch is exactly Seq-vs-List we append a hint
    // pointing at the explicit conversion.
    private fun seqListMismatchHint(expected: MezzeType, actual: MezzeType): String {
        fun isSeq(t: MezzeType) = t is MezzeType.SeqType
        fun isList(t: MezzeType) = t is MezzeType.TypeApplication && t.constructor == "List"
        return when {
            // wrote a `[...]` sequence where a List was expected
            isList(expected) && isSeq(actual) ->
                "\n  hint: `[...]` builds a Seq (`['a]`), not a List. " +
                "Convert with `list::from_seq { xs = [...] }`, or annotate the type as `['a]`."
            // expected a sequence but produced a List
            isSeq(expected) && isList(actual) ->
                "\n  hint: this is a List, not a Seq (`['a]`). " +
                "Convert with `list::to_seq { xs = ... }`, or annotate the type as `List 'a`."
            else -> ""
        }
    }

    private fun describeEffectRowDifference(actual: MezzeType, expected: MezzeType): String? {
        if (actual !is MezzeType.FunctionType || expected !is MezzeType.FunctionType) return null

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

    private fun generateErrorFromStringContext(
        type1: MezzeType,
        type2: MezzeType,
        stringContext: String,
        failure: UnificationResult.Failure
    ): String {
        if (stringContext.contains("Literal pattern")) {
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

        if (stringContext.contains("Seq pattern")) {
            if (type1 is MezzeType.ListType || type2 is MezzeType.ListType) {
                return "Cannot use seq pattern syntax [a, b, ...] with List type. " +
                    "Use constructor patterns 'Cons { head, tail }' and 'Nil' instead."
            }
        }

        if (stringContext.isNotEmpty()) {
            val (expected, actual) = prettyPrintTypePair(type2, type1)

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

    private fun generatePatternMatchError(
        type1: MezzeType,
        type2: MezzeType,
        context: ErrorContext.PatternMatch,
        stringContext: String
    ): String {
        val (expected, actual) = prettyPrintTypePair(type2, type1)

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

    private fun extractBindingName(context: String): String? {
        val prefix = "Type annotation on "
        return if (context.startsWith(prefix)) {
            context.removePrefix(prefix).trim()
        } else null
    }

    private fun extractFieldName(context: String): String? {
        val prefix = "Field access "
        return if (context.startsWith(prefix)) {
            context.removePrefix(prefix).trim()
        } else null
    }
}
