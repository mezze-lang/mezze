package com.mezze.language.typechecker.phase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ErrorContext
import com.mezze.language.typechecker.solving.ConstraintSolver

internal fun classifyConstraintError(
    error: ConstraintSolver.ConstraintError
): TypeChecker.TypeCheckError {
    val constraint = error.constraint
    val ctx = (constraint as? Constraint.Equality)?.errorContext

    val annotationHint = listOf(
        "either change the annotation to match the body, or change the body to produce the annotated type"
    )

    // T0211 detection runs FIRST — it inspects the constraint's types
    // directly so it fires regardless of ErrorContext. The T0201-favouring
    // branches below never see the callback-mismatch shape once this
    // catches it. #58.
    val t0211 = detectCallbackParamMismatch(constraint)
    val (code, hints) = t0211 ?: when (ctx) {
        is ErrorContext.TypeAnnotation ->
            ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH to annotationHint
        is ErrorContext.ReturnType ->
            ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH to annotationHint
        is ErrorContext.FunctionArgument ->
            ErrorCode.T0201_TYPE_MISMATCH to emptyList()
        is ErrorContext.BinaryOperation ->
            ErrorCode.T0201_TYPE_MISMATCH to listOf(
                "the operator expects both operands to have the same type"
            )
        is ErrorContext.FieldAccess ->
            ErrorCode.T0205_FIELD_NOT_FOUND to emptyList()
        is ErrorContext.PatternMatch ->
            ErrorCode.T0201_TYPE_MISMATCH to listOf(
                "the pattern's type must match the scrutinee's type"
            )
        is ErrorContext.SeqElement ->
            ErrorCode.T0201_TYPE_MISMATCH to listOf(
                "all seq elements must have the same type"
            )
        is ErrorContext.StringInterpolation ->
            ErrorCode.T0201_TYPE_MISMATCH to listOf(
                "interpolated expressions must produce a `Str` (use `.to_str {}` or `.to_dbg_str {}`)"
            )
        is ErrorContext.Simple,
        null -> classifyByConstraintAndMessage(constraint, error.message)
    }

    val solverFixHints: List<FixHint> = buildList {
        if (ctx is ErrorContext.TypeAnnotation || ctx is ErrorContext.ReturnType) {
            val loc = error.constraint.loc
            if (loc != null) {
                effectRowExtraEffects(constraint).forEach { effect ->
                    add(FixHint.AddEffectToAnnotation(loc, effect))
                }
            }
        }
        // Wrap-in-Ok/Some: the value's type matches the INNER type of an
        // expected Result/Option — lift it into the wrapper. Offered on any
        // mismatch context (annotation, return, argument), keyed off the
        // Equality's two sides.
        wrapConstructorHint(constraint)?.let { add(it) }

        // String interpolation needs a `Str` — offer to append `.to_str {}` /
        // `.to_dbg_str {}` to the interpolated expression. The diagnostic's own
        // hint text already says this; this makes it a one-click fix.
        if (ctx is ErrorContext.StringInterpolation) {
            val loc = error.constraint.loc
            if (loc != null) {
                add(FixHint.WrapInMethodCall(loc, listOf("to_str", "to_dbg_str")))
            }
        }
    }

    return TypeChecker.TypeCheckError(
        phase = "Constraint Solving",
        message = error.message,
        loc = error.constraint.loc,
        code = code,
        hints = hints,
        fixHints = solverFixHints,
    )
}

private fun classifyByConstraintAndMessage(
    constraint: Constraint,
    msg: String
): Pair<ErrorCode, List<String>> = when {
    msg.startsWith("Infinite type: ") ->
        ErrorCode.T0207_INFINITE_TYPE to listOf(
            "this usually means a recursive let-binding is missing a type annotation"
        )
    constraint is Constraint.HasField && msg.contains("does not have field") ->
        ErrorCode.T0205_FIELD_NOT_FOUND to emptyList()
    constraint is Constraint.HasField && msg.contains("type mismatch") ->
        ErrorCode.T0201_TYPE_MISMATCH to emptyList()
    constraint is Constraint.HasConstructor && msg.contains("does not have constructor") ->
        ErrorCode.V0704_UNKNOWN_CONSTRUCTOR to emptyList()
    constraint is Constraint.HasConstructor && msg.contains("payload type mismatch") ->
        ErrorCode.T0201_TYPE_MISMATCH to emptyList()
    constraint is Constraint.HasConstructor && msg.contains("does not take a payload") ->
        ErrorCode.V0604_CONSTRUCTOR_NO_PAYLOAD to emptyList()
    constraint is Constraint.HasConstructor && msg.contains("must be destructured") ->
        ErrorCode.T0204_VARIANT_CONSTRUCTOR_ARITY to emptyList()
    constraint is Constraint.AbilityRequired ->
        ErrorCode.V0408_NO_IMPLEMENTATION to emptyList()
    constraint is Constraint.RecordPatternExhaustive ->
        ErrorCode.V0602_NON_EXHAUSTIVE_RECORD_PATTERN to emptyList()
    constraint is Constraint.Subsumes ->
        ErrorCode.T0201_TYPE_MISMATCH to emptyList()
    // #160: inherent method dispatch couldn't resolve (no matching candidate for
    // the solved receiver type, or the receiver never became concrete).
    constraint is Constraint.InherentMethodResolve ->
        ErrorCode.T0206_METHOD_NOT_FOUND to emptyList()
    msg.startsWith("Field '") && msg.contains("type mismatch") ->
        ErrorCode.T0201_TYPE_MISMATCH to emptyList()
    msg.contains("Cannot access field") ->
        ErrorCode.T0205_FIELD_NOT_FOUND to emptyList()
    msg.contains("Cannot use constructor") ->
        ErrorCode.T0201_TYPE_MISMATCH to emptyList()
    else -> ErrorCode.UNCATEGORIZED to emptyList()
}

/**
 * When a mismatch is "value of type T vs. expected Result T E / Option T",
 * offer to wrap the value in the matching constructor. We test both
 * orientations of the Equality (either side may be the expected wrapper) and
 * require the other side to structurally equal the wrapper's inner value type.
 * Returns null when neither side is an Option/Result wrapper around the other.
 */
private fun wrapConstructorHint(constraint: Constraint): FixHint? {
    val eq = constraint as? Constraint.Equality ?: return null
    val loc = eq.loc ?: return null

    // The wrapper's (constructor, innerValueType). Handles both shapes Mezze
    // uses for Result/Option: an annotated `TypeApplication("Result", [v, e])`
    // and an inferred `VariantType` with Ok/Err (or Some/None) constructors.
    fun wrapperOf(t: MezzeType): Pair<String, MezzeType>? = when (t) {
        is MezzeType.TypeApplication -> when (t.constructor) {
            BuiltinTypes.RESULT -> t.args.getOrNull(0)?.let { BuiltinTypes.OK to it }
            BuiltinTypes.OPTION -> t.args.getOrNull(0)?.let { BuiltinTypes.SOME to it }
            else -> null
        }
        is MezzeType.VariantType -> {
            BuiltinTypes.getResultTypes(t)?.let { (v, _) -> BuiltinTypes.OK to v }
                ?: BuiltinTypes.getOptionValueType(t)?.let { BuiltinTypes.SOME to it }
        }
        else -> null
    }

    fun tryWrap(wrapper: MezzeType, inner: MezzeType): FixHint? {
        val (ctor, valueType) = wrapperOf(wrapper) ?: return null
        return if (valueType == inner) FixHint.WrapInConstructor(loc, ctor) else null
    }

    return tryWrap(eq.right, eq.left) ?: tryWrap(eq.left, eq.right)
}

private fun effectRowExtraEffects(
    constraint: Constraint
): List<String> {
    val eq = constraint as? Constraint.Equality ?: return emptyList()
    val actual = eq.left as? MezzeType.FunctionType ?: return emptyList()
    val expected = eq.right as? MezzeType.FunctionType ?: return emptyList()
    if (actual.paramType != expected.paramType) return emptyList()
    if (actual.returnType != expected.returnType) return emptyList()
    val actualEffects = actual.effects?.effects ?: emptySet()
    val expectedEffects = expected.effects?.effects ?: emptySet()
    return (actualEffects - expectedEffects).sorted()
}

/**
 * Detect the "callback lambda has wrong param names" shape anywhere in
 * the constraint's types: descends into nested records and function
 * types until it finds a FunctionType whose paramType is a record whose
 * field-name set doesn't match the expected side.
 *
 * <p>Example: fold's arg is {@code { xs: ['a], init: 'b, f: { acc: 'b, x: 'a } -> 'b }}.
 * User wrote {@code f = { acc, i } -> ...}, so actual arg is
 * {@code { xs: [Int], init: Int, f: { acc: ?, i: ? } -> ? }}. Top-level
 * constraint compares the two arg records; both have the same outer
 * field names ({xs, init, f}). We descend into `f`, see two functions
 * whose param records have field sets {acc, i} vs {acc, x}, and flag
 * T0211 pointing at the callback rather than the misleading downstream
 * `xs` field mismatch T0201.
 */
private fun detectCallbackParamMismatch(
    constraint: Constraint
): Pair<ErrorCode, List<String>>? {
    val eq = constraint as? Constraint.Equality ?: return null
    val mismatch = findNestedCallbackMismatch(eq.left, eq.right) ?: return null
    val expectedList = mismatch.expected.joinToString(", ")
    val actualList = mismatch.actual.joinToString(", ")
    return ErrorCode.T0211_CALLBACK_PARAM_NAMES_MISMATCH to listOf(
        "the callback expects parameters `{ $expectedList }` " +
            "but the lambda destructures `{ $actualList }`",
        "Mezze lambda params ARE record destructuring — the names must match " +
            "the field names the caller passes. Rename to `{ $expectedList } -> ...`."
    )
}

private data class NestedCallbackMismatch(
    val expected: List<String>,
    val actual: List<String>,
)

private fun findNestedCallbackMismatch(
    actual: MezzeType,
    expected: MezzeType,
): NestedCallbackMismatch? {
    if (actual is MezzeType.VarType || expected is MezzeType.VarType) return null

    if (actual is MezzeType.FunctionType && expected is MezzeType.FunctionType) {
        val aParam = actual.paramType
        val eParam = expected.paramType
        if (aParam is MezzeType.RecordType && eParam is MezzeType.RecordType &&
            aParam.rowVar == null && eParam.rowVar == null &&
            aParam.fields.keys != eParam.fields.keys
        ) {
            return NestedCallbackMismatch(
                expected = eParam.fields.keys.sorted(),
                actual = aParam.fields.keys.sorted(),
            )
        }
        findNestedCallbackMismatch(aParam, eParam)?.let { return it }
        findNestedCallbackMismatch(actual.returnType, expected.returnType)?.let { return it }
        return null
    }
    if (actual is MezzeType.RecordType && expected is MezzeType.RecordType) {
        val shared = actual.fields.keys intersect expected.fields.keys
        for (fieldName in shared) {
            findNestedCallbackMismatch(
                actual.fields[fieldName]!!,
                expected.fields[fieldName]!!,
            )?.let { return it }
        }
    }
    return null
}
