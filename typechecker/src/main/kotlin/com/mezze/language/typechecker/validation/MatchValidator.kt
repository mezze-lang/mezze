package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant
import com.mezze.language.typechecker.util.TypeAnnotationConverter

class MatchValidator(
    private val typeDeclarations: Map<String, ParsedTypeDecl>,
    private val registry: DeclarationRegistry,
) {

    fun validateParsedMatchesInExpr(
        expr: ParsedExpr,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        walkParsedExpr(expr) { node ->
            if (node is ParsedMatch) {
                val hasConstructorPattern = node.cases.any { case ->
                    case.patterns.any { it is ParsedConstructorPattern }
                }
                if (!hasConstructorPattern) {
                    val scrutineeType = inferParsedScrutineeType(node, substitution, constraintResults)
                    if (scrutineeType != null) {
                        val result = validateParsedExhaustiveness(node, scrutineeType)
                        if (result is ValidationResult.Failure) errors.addAll(result.errors)
                    }
                }
            }
        }
        return errors
    }

    private fun inferParsedScrutineeType(
        matchExpr: ParsedMatch,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): MezzeType? {
        val constructorNames = mutableSetOf<String>()
        for (case in matchExpr.cases) {
            for (pattern in case.patterns) {
                if (pattern is ParsedConstructorPattern) constructorNames.add(pattern.constructor)
            }
        }

        if (constructorNames.isNotEmpty()) {
            for ((_, typeDecl) in typeDeclarations) {
                val definition = typeDecl.definition
                if (definition is ParsedVariantDef) {
                    val variantConstructors = definition.constructors.map { it.name }.toSet()
                    if (constructorNames.all { it in variantConstructors }) {
                        return MezzeType.VariantType(
                            constructors = definition.constructors.associate { ctor ->
                                ctor.name to ctor.dataType?.let { TypeAnnotationConverter.convert(it) }
                            },
                            rowVar = if (definition.isOpen) RowVar.fresh() else null
                        )
                    }
                }
            }
        }

        val scrutinee = matchExpr.scrutinee
        if (scrutinee is ParsedIdentifier) {
            val varName = scrutinee.localName
            for ((_, result) in constraintResults) {
                val resolvedType = substitution.apply(result.type)
                val paramType = extractParamType(resolvedType, varName)
                if (paramType != null) {
                    val finalType = substitution.apply(paramType)
                    val expanded = when (finalType) {
                        is MezzeType.TypeApplication ->
                            expandTypeApplicationToVariant(finalType, registry) ?: finalType
                        else -> finalType
                    }
                    if (expanded is MezzeType.VariantType) return expanded
                }
            }
        }

        return null
    }

    private fun extractParamType(type: MezzeType, paramName: String): MezzeType? = when (type) {
        is MezzeType.FunctionType -> (type.paramType as? MezzeType.RecordType)?.fields?.get(paramName)
        else -> null
    }

    private fun walkParsedExpr(expr: ParsedExpr, visitor: (ParsedExpr) -> Unit) {
        visitor(expr)
        when (expr) {
            is ParsedLambda -> walkParsedExpr(expr.body, visitor)
            is ParsedFunctionCall -> { walkParsedExpr(expr.function, visitor); walkParsedExpr(expr.arguments, visitor) }
            is ParsedMatch -> {
                walkParsedExpr(expr.scrutinee, visitor)
                for (case in expr.cases) {
                    case.guard?.let { walkParsedExpr(it, visitor) }
                    walkParsedExpr(case.body, visitor)
                }
            }
            is ParsedIf -> { walkParsedExpr(expr.condition, visitor); walkParsedExpr(expr.thenBranch, visitor); walkParsedExpr(expr.elseBranch, visitor) }
            is ParsedDo -> {
                for (stmt in expr.statements) when (stmt) {
                    is ParsedStatement.Let -> walkParsedExpr(stmt.value, visitor)
                    is ParsedStatement.Expr -> walkParsedExpr(stmt.expr, visitor)
                    is ParsedStatement.Bind -> walkParsedExpr(stmt.expr, visitor)
                }
                walkParsedExpr(expr.result, visitor)
            }
            is ParsedRecordLit -> for (entry in expr.entries) when (entry) {
                is ParsedRecordEntry.Field -> entry.value?.let { walkParsedExpr(it, visitor) }
                is ParsedRecordEntry.Spread -> walkParsedExpr(entry.expr, visitor)
            }
            is ParsedVariantConstruct -> expr.value?.let { walkParsedExpr(it, visitor) }
            is ParsedMemberAccess -> walkParsedExpr(expr.receiver, visitor)
            is ParsedSeqLit -> for (elem in expr.elements) when (elem) {
                is ParsedSeqElement.Regular -> walkParsedExpr(elem.expr, visitor)
                is ParsedSeqElement.Spread -> walkParsedExpr(elem.expr, visitor)
            }
            is ParsedPerform -> walkParsedExpr(expr.body, visitor)
            is ParsedPipe -> { walkParsedExpr(expr.left, visitor); walkParsedExpr(expr.right, visitor) }
            is ParsedStringLit -> for (part in expr.parts) if (part is ParsedStringPart.Interpolation) walkParsedExpr(part.expr, visitor)
            is ParsedParens -> walkParsedExpr(expr.expr, visitor)
            else -> {}
        }
    }

    fun validateParsedExhaustiveness(
        matchExpr: ParsedMatch,
        scrutineeType: MezzeType
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        val expandedType = when (scrutineeType) {
            is MezzeType.TypeApplication -> expandTypeApplicationToVariant(scrutineeType, registry) ?: scrutineeType
            is MezzeType.BoolType -> MezzeType.VariantType(
                constructors = mapOf(BuiltinTypes.TRUE to null, BuiltinTypes.FALSE to null), rowVar = null
            )
            else -> scrutineeType
        }

        if (expandedType !is MezzeType.VariantType) return ValidationResult.Success()

        val isOpenVariant = expandedType.rowVar != null
        val allConstructors = expandedType.constructors.keys
        val coveredConstructors = mutableSetOf<String>()
        var hasWildcard = false

        for (case in matchExpr.cases) {
            for (pattern in case.patterns) {
                when (pattern) {
                    is ParsedConstructorPattern -> {
                        if (case.guard == null && case.ifLetBindings.isEmpty()) coveredConstructors.add(pattern.constructor)
                        if (expandedType.constructors[pattern.constructor] == null && pattern.payload != null) {
                            errors.add(ValidationError.UnitVariantPayloadMismatch(
                                constructorName = pattern.constructor, variantType = expandedType, loc = case.body.loc
                            ))
                        }
                    }
                    is ParsedWildcardPattern, is ParsedVariablePattern -> {
                        if (case.guard == null && case.ifLetBindings.isEmpty()) hasWildcard = true
                    }
                    is ParsedLiteralPattern -> {
                        if (case.guard == null && case.ifLetBindings.isEmpty()) {
                            val lit = pattern.value
                            if (lit is ParsedLiteralValue.BoolValue) coveredConstructors.add(if (lit.value) "True" else "False")
                        }
                    }
                    else -> {}
                }
            }
        }

        val uncoveredConstructors = allConstructors - coveredConstructors

        if (isOpenVariant) {
            if (!hasWildcard) {
                errors.add(ValidationError.NonExhaustiveMatch(
                    coveredConstructors = coveredConstructors.toList(),
                    missingConstructors = listOf("_ (wildcard required for open variant)"),
                    loc = matchExpr.loc
                ))
            }
        } else {
            if (uncoveredConstructors.isNotEmpty() && !hasWildcard) {
                errors.add(ValidationError.NonExhaustiveMatch(
                    coveredConstructors = coveredConstructors.toList(),
                    missingConstructors = uncoveredConstructors.toList(),
                    loc = matchExpr.loc
                ))
            }
            if (hasWildcard && uncoveredConstructors.isEmpty()) {
                for (case in matchExpr.cases) {
                    for (pattern in case.patterns) {
                        if ((pattern is ParsedWildcardPattern || pattern is ParsedVariablePattern) &&
                            case.guard == null && case.ifLetBindings.isEmpty()) {
                            errors.add(ValidationError.UnreachablePattern(
                                reason = "All constructors of this closed variant are already matched: " +
                                    "${allConstructors.sorted().joinToString(", ")}. This pattern will never execute.",
                                loc = case.body.loc
                            ))
                        }
                    }
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.Success() else ValidationResult.Failure(errors)
    }

    fun validateParsedUnreachablePatterns(matchExpr: ParsedMatch): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        var isExhausted = false

        for (case in matchExpr.cases) {
            if (isExhausted) {
                errors.add(ValidationError.UnreachablePattern(
                    pattern = case.patterns.firstOrNull(),
                    reason = "Previous pattern already matches all cases",
                    loc = case.loc
                ))
                continue
            }
            if (case.guard == null && case.ifLetBindings.isEmpty()) {
                for (pattern in case.patterns) {
                    if (pattern is ParsedWildcardPattern || pattern is ParsedVariablePattern) {
                        isExhausted = true
                        break
                    }
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.Success() else ValidationResult.Failure(errors)
    }

    fun inferParsedScrutineeTypeFromPatterns(
        matchExpr: ParsedMatch,
        typeDecls: Map<String, ParsedTypeDecl> = typeDeclarations
    ): MezzeType? {
        val constructorNames = mutableSetOf<String>()
        for (case in matchExpr.cases) {
            for (pattern in case.patterns) {
                if (pattern is ParsedConstructorPattern) constructorNames.add(pattern.constructor)
            }
        }
        if (constructorNames.isEmpty()) return null

        for ((_, typeDecl) in typeDecls) {
            val definition = typeDecl.definition
            if (definition is ParsedVariantDef) {
                val variantConstructors = definition.constructors.map { it.name }.toSet()
                if (constructorNames.all { it in variantConstructors }) {
                    return MezzeType.VariantType(
                        constructors = definition.constructors.associate { ctor ->
                            ctor.name to ctor.dataType?.let { TypeAnnotationConverter.convert(it) }
                        },
                        rowVar = if (definition.isOpen) RowVar.fresh() else null
                    )
                }
            }
        }
        return null
    }
}
