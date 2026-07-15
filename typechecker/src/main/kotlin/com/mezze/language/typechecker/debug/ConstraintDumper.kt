package com.mezze.language.typechecker.debug

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.constraints.ErrorContext

object ConstraintDumper {

    private val enabled: Boolean
        get() = System.getProperty("mezze.dump.constraints", "false") == "true"

    fun dumpGeneratedConstraints(
        constraintResults: Map<String, ConstraintResult>,
    ) {
        if (!enabled) return

        println("=== GENERATED CONSTRAINTS ===")
        for ((binding, result) in constraintResults) {
            println("  binding: $binding")
            println("    type: ${prettyPrintType(result.type)}")
            for (constraint in result.constraints) {
                println("    ${formatConstraint(constraint)}")
            }
        }
        println("=== END GENERATED CONSTRAINTS ===")
    }

    fun dumpSolverInput(constraints: List<Constraint>) {
        if (!enabled) return

        println("=== SOLVER INPUT (${constraints.size} constraints) ===")
        for ((i, constraint) in constraints.withIndex()) {
            println("  [$i] ${formatConstraint(constraint)}")
        }
        println("=== END SOLVER INPUT ===")
    }

    fun dumpSolverResult(substitution: Substitution) {
        if (!enabled) return

        println("=== SOLVER RESULT (${substitution.mappings.size} mappings) ===")
        for ((typeVar, type) in substitution.mappings) {
            println("  $typeVar → ${prettyPrintType(type)}")
        }
        println("=== END SOLVER RESULT ===")
    }

    private fun formatLoc(constraint: Constraint): String =
        constraint.loc?.let { "${it.file}:${it.startLine}" } ?: "?"

    private fun formatConstraint(constraint: Constraint): String = when (constraint) {
        is Constraint.Equality -> {
            val ctx = formatErrorContext(constraint.errorContext)
            "Equality(${formatLoc(constraint)}): ${prettyPrintType(constraint.left)} ~ ${prettyPrintType(constraint.right)}$ctx"
        }
        is Constraint.HasField ->
            "HasField(${formatLoc(constraint)}): ${prettyPrintType(constraint.recordType)}.${constraint.fieldName} : ${prettyPrintType(constraint.fieldType)}"
        is Constraint.HasConstructor ->
            "HasConstructor(${formatLoc(constraint)}): ${constraint.constructorName} of ${prettyPrintType(constraint.variantType)}"
        is Constraint.Subsumes ->
            "Subsumes(${formatLoc(constraint)}): ${prettyPrintType(constraint.specific)} <: ${prettyPrintType(constraint.general)}"
        is Constraint.AbilityRequired ->
            "AbilityRequired(${formatLoc(constraint)}): ${constraint.typeVar} : ${constraint.abilityName}"
        is Constraint.AssociatedTypeResolution ->
            "AssociatedType(${formatLoc(constraint)}): ${constraint.abilityName} for ${constraint.primaryTypeVar} -> ${constraint.associatedTypeVars}"
        is Constraint.MustBeOpen ->
            "MustBeOpen(${formatLoc(constraint)}): ${prettyPrintType(constraint.type)}"
        is Constraint.RecordPatternExhaustive ->
            "RecordPatternExhaustive(${formatLoc(constraint)}): ${prettyPrintType(constraint.recordType)}"
    }

    private fun formatErrorContext(ctx: ErrorContext?): String = when (ctx) {
        null -> ""
        is ErrorContext.Simple -> " [${ctx.message}]"
        is ErrorContext.FunctionArgument -> " [arg '${ctx.argumentName}' in ${ctx.functionName}]"
        is ErrorContext.TypeAnnotation -> " [annotation for '${ctx.bindingName}']"
        is ErrorContext.FieldAccess -> " [field '${ctx.fieldName}']"
        is ErrorContext.PatternMatch -> " [pattern: ${ctx.patternDescription}]"
        is ErrorContext.BinaryOperation -> " [${ctx.operator} ${if (ctx.isLeftOperand) "lhs" else "rhs"}]"
        is ErrorContext.SeqElement -> " [seq element ${ctx.elementIndex}]"
        is ErrorContext.ReturnType -> " [return of ${ctx.functionName}]"
        is ErrorContext.StringInterpolation -> " [interpolation ${ctx.expressionIndex}]"
    }
}
