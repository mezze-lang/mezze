package com.mezze.language.typechecker.phase

import com.mezze.language.ast.MezzeType
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

    val (code, hints) = when (ctx) {
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

    val solverFixHints: List<FixHint> =
        if (ctx is ErrorContext.TypeAnnotation ||
            ctx is ErrorContext.ReturnType) {
            effectRowExtraEffects(constraint).let { extras ->
                if (extras.isNotEmpty() && error.constraint.loc != null) {
                    extras.map { effect ->
                        FixHint.AddEffectToAnnotation(error.constraint.loc!!, effect)
                    }
                } else emptyList()
            }
        } else emptyList()

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
    msg.startsWith("Field '") && msg.contains("type mismatch") ->
        ErrorCode.T0201_TYPE_MISMATCH to emptyList()
    msg.contains("Cannot access field") ->
        ErrorCode.T0205_FIELD_NOT_FOUND to emptyList()
    msg.contains("Cannot use constructor") ->
        ErrorCode.T0201_TYPE_MISMATCH to emptyList()
    else -> ErrorCode.UNCATEGORIZED to emptyList()
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
