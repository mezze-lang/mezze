package com.mezze.language.typechecker.phase

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ErrorContext
import com.mezze.language.typechecker.solving.ConstraintSolver

/**
 * Classifies raw solver [ConstraintSolver.ConstraintError] into
 * [TypeChecker.TypeCheckError] with stable error codes and hints.
 *
 * Phase A2 entry point for the new diagnostic shape -- Phase B will grow the
 * match arms until every error has a real code.
 *
 * Sites NOT yet classified fall through with `code = UNCATEGORIZED`, so output
 * stays identical to before until each kind is migrated.
 */
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
        null -> {
            val msg = error.message
            when {
                msg.startsWith("Infinite type: ") ->
                    ErrorCode.T0207_INFINITE_TYPE to listOf(
                        "this usually means a recursive let-binding is missing a type annotation"
                    )
                msg.startsWith("Field '") && msg.contains("type mismatch") ->
                    ErrorCode.T0201_TYPE_MISMATCH to emptyList<String>()
                else -> ErrorCode.UNCATEGORIZED to emptyList()
            }
        }
    }

    // When the failure is a pure effect-row mismatch on a TypeAnnotation /
    // ReturnType constraint, mine the constraint's actual/expected effect
    // rows to emit structured `AddEffectToAnnotation` fixes.
    val solverFixHints: List<com.mezze.language.parsedast.diagnostics.FixHint> =
        if (ctx is ErrorContext.TypeAnnotation ||
            ctx is ErrorContext.ReturnType) {
            effectRowExtraEffects(constraint).let { extras ->
                if (extras.isNotEmpty() && error.constraint.loc != null) {
                    extras.map { effect ->
                        com.mezze.language.parsedast.diagnostics.FixHint
                            .AddEffectToAnnotation(error.constraint.loc!!, effect)
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

/**
 * Returns the effects that exist in the constraint's actual (left) side
 * but not in the expected (right) side, when both are function types that
 * only differ in their effect rows. Empty when the constraint is some
 * other shape -- caller falls back to other fix kinds.
 */
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
