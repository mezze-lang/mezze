package com.mezze.language.typechecker.debug

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.constraints.ErrorContext

object ConstraintDumper {

    private val enabled: Boolean
        get() = System.getProperty("mezze.dump.constraints", "false") == "true"

    /** True when dumping is on, for callers that would otherwise do work for it. */
    val isEnabled: Boolean get() = enabled

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
            // Ability obligations are constraints too, and they were missing
            // here — so a dump could not answer "what did this binding demand?",
            // which is most of what one asks a dump.
            for (constraint in result.abilityConstraints) {
                println("    ${formatConstraint(constraint)}")
            }
            for (constraint in result.associatedTypeConstraints) {
                println("    ${formatConstraint(constraint)}")
            }
        }
        println("=== END GENERATED CONSTRAINTS ===")
    }

    /**
     * What ONE binding's own solve concluded, before anything is propagated.
     *
     * The global substitution ([dumpSolverResult]) is the merge of every
     * binding's answers, and a question like "did THIS binding's solve link the
     * annotation's variable to the body's?" cannot be read out of it. Answering
     * that meant hand-editing `ConstraintGenerator` to add a println, twice in
     * one session; this is that println, kept.
     */
    fun dumpPerBindingSolve(binding: String, substitution: Substitution) {
        if (!enabled) return
        println("=== PER-BINDING SOLVE: $binding (${substitution.mappings.size} mappings) ===")
        for ((typeVar, type) in substitution.mappings) {
            println("  ${typeVar.name}#${typeVar.id} → ${prettyPrintType(type)}")
        }
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
        is Constraint.InherentMethodResolve ->
            "InherentMethodResolve(${formatLoc(constraint)}): ${prettyPrintType(constraint.receiverType)}.${constraint.methodName} -> ${prettyPrintType(constraint.resultType)} [${constraint.candidates.joinToString(", ") { it.targetTypeName }}]"
        is Constraint.DefaultAssociatedType ->
            "DefaultAssociatedType(${formatLoc(constraint)}): ${prettyPrintType(constraint.bindingType)} ?~ ${prettyPrintType(constraint.shape)}"
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
