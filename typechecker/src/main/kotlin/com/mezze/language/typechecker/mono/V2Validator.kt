package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.SourceLocation
import com.mezze.language.monoast.*
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.TypeCheckError

/**
 * V2 pattern validator — checks pattern exhaustiveness and unreachable patterns
 * on the MonoAST where types are concrete (after monomorphization).
 *
 * Checks pattern exhaustiveness and unreachable patterns on the MonoAST
 * where types are concrete (after monomorphization).
 */
class V2Validator(
    private val typeDeclarations: Map<String, com.mezze.language.ast.TypeDeclaration> = emptyMap()
) {
    private val errors = mutableListOf<TypeCheckError>()

    fun validate(module: MonoModule) {
        for (fn in module.functions) {
            validateExpr(fn.body)
        }
        if (errors.isNotEmpty()) {
            throw errors.first()
        }
    }

    private fun validateExpr(expr: MonoExpr) {
        when (expr) {
            is MonoMatch -> {
                validateMatch(expr)
                validateExpr(expr.scrutinee)
                for (case in expr.cases) {
                    case.guard?.let { validateExpr(it) }
                    validateExpr(case.body)
                }
            }
            is MonoBlock -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is MonoStatement.Let -> validateExpr(stmt.value)
                        is MonoStatement.Expr -> validateExpr(stmt.expr)
                    }
                }
                validateExpr(expr.result)
            }
            is MonoLambda -> validateExpr(expr.body)
            is MonoCall -> {
                validateExpr(expr.function)
                validateExpr(expr.arg)
            }
            is MonoIf -> {
                validateExpr(expr.condition)
                validateExpr(expr.thenBranch)
                validateExpr(expr.elseBranch)
            }
            is MonoRecord -> {
                for (entry in expr.entries) {
                    when (entry) {
                        is MonoRecordEntry.Field -> validateExpr(entry.value)
                        is MonoRecordEntry.Spread -> validateExpr(entry.expr)
                    }
                }
            }
            is MonoRecordUpdate -> {
                validateExpr(expr.record)
                for (entry in expr.updates) {
                    when (entry) {
                        is MonoRecordEntry.Field -> validateExpr(entry.value)
                        is MonoRecordEntry.Spread -> validateExpr(entry.expr)
                    }
                }
            }
            is MonoFieldAccess -> validateExpr(expr.record)
            is MonoVariantConstruct -> expr.payload?.let { validateExpr(it) }
            is MonoPerform -> {
                for (h in expr.handlers) { h.params?.let { validateExpr(it) } }
                validateExpr(expr.body)
            }
            is MonoPerformOp -> validateExpr(expr.arg)
            is MonoSeq -> expr.elements.forEach { elem ->
                when (elem) {
                    is MonoSeqElement.Regular -> validateExpr(elem.expr)
                    is MonoSeqElement.Spread -> validateExpr(elem.expr)
                }
            }
            is MonoStringLit -> expr.parts.forEach { part ->
                if (part is MonoStringPart.Interpolation) validateExpr(part.expr)
            }
            // Leaf nodes
            is MonoVar, is MonoIntLit, is MonoNatLit, is MonoFloatLit,
            is MonoFracLit, is MonoBoolLit, is MonoCharLit -> {}
        }
    }

    private fun validateMatch(match: MonoMatch) {
        val scrutineeType = match.scrutinee.type

        // Resolve scrutinee type to a VariantType for exhaustiveness checking
        val variantType = when (scrutineeType) {
            is MezzeType.BoolType -> MezzeType.VariantType(
                constructors = mapOf("True" to null, "False" to null),
                rowVar = null
            )
            is MezzeType.VariantType -> scrutineeType
            is MezzeType.TypeApplication -> {
                // Expand TypeApplication to VariantType using type declarations
                val typeDecl = typeDeclarations[scrutineeType.constructor]
                val definition = typeDecl?.definition
                if (definition is com.mezze.language.ast.VariantDefinition) {
                    MezzeType.VariantType(
                        constructors = definition.constructors.associate { ctor ->
                            ctor.name to ctor.dataType?.let { MezzeType.VarType(com.mezze.language.ast.TypeVar("payload")) }
                        },
                        rowVar = if (definition.isOpen) com.mezze.language.ast.RowVar("_open") else null
                    )
                } else {
                    return // Not a variant type — skip
                }
            }
            is MezzeType.VarType -> return // Unresolved type variable — skip
            else -> return // Non-variant — skip exhaustiveness
        }

        val isOpen = variantType.rowVar != null
        val allConstructors = variantType.constructors.keys

        val coveredConstructors = mutableSetOf<String>()
        var hasWildcard = false
        var hasGuardedWildcard = false

        for (case in match.cases) {
            when (val pattern = case.pattern) {
                is MonoVariantPattern -> {
                    if (case.guard == null) coveredConstructors.add(pattern.tag)
                }
                is MonoWildcardPattern, is MonoVarPattern -> {
                    if (case.guard != null) hasGuardedWildcard = true
                    else hasWildcard = true
                }
                is MonoLiteralPattern -> {
                    val value = pattern.value
                    if (value is MonoBoolLit && case.guard == null) {
                        coveredConstructors.add(if (value.value) "True" else "False")
                    }
                }
                else -> {}
            }
        }

        // Non-exhaustive match
        if (!hasWildcard && !isOpen) {
            val missing = allConstructors - coveredConstructors
            if (missing.isNotEmpty()) {
                errors.add(TypeCheckError(
                    "Non-exhaustive match. Missing case(s): ${missing.joinToString(", ")}",
                    match.loc.toSL()
                ))
            }
        }

        if (isOpen && !hasWildcard && !hasGuardedWildcard) {
            errors.add(TypeCheckError(
                "Non-exhaustive match. Missing case(s): _ (wildcard required for open variant)",
                match.loc.toSL()
            ))
        }

        if (!hasWildcard && coveredConstructors.isEmpty() && hasGuardedWildcard) {
            errors.add(TypeCheckError(
                "Non-exhaustive match. Missing case(s): all patterns have guards, add a wildcard pattern",
                match.loc.toSL()
            ))
        }

        // Guards on ALL constructors without wildcard
        if (!hasWildcard && !isOpen && coveredConstructors.size < allConstructors.size) {
            // Some constructors covered only with guards — check if those are actually covered
            val guardedOnly = mutableSetOf<String>()
            for (case in match.cases) {
                val pat = case.pattern
                if (pat is MonoVariantPattern && case.guard != null) {
                    if (pat.tag !in coveredConstructors) {
                        guardedOnly.add(pat.tag)
                    }
                }
            }
            if (guardedOnly.isNotEmpty()) {
                val stillMissing = allConstructors - coveredConstructors - guardedOnly
                // If only guarded constructors cover the gap, still non-exhaustive
                if (stillMissing.isEmpty() && guardedOnly.isNotEmpty()) {
                    errors.add(TypeCheckError(
                        "Non-exhaustive match. Missing case(s): ${guardedOnly.joinToString(", ")} (only covered with guards)",
                        match.loc.toSL()
                    ))
                }
            }
        }

        // Unreachable patterns
        validateUnreachable(match, allConstructors, isOpen)
    }

    private fun validateUnreachable(match: MonoMatch, allConstructors: Set<String>, isOpen: Boolean) {
        val seenConstructors = mutableSetOf<String>()
        var wildcardSeen = false

        for (case in match.cases) {
            if (wildcardSeen && case.guard == null) {
                errors.add(TypeCheckError(
                    "Unreachable pattern: wildcard pattern above already matches all values",
                    case.loc.toSL()
                ))
                continue
            }

            when (val pattern = case.pattern) {
                is MonoVariantPattern -> {
                    // A variant pattern with a specific payload (record/literal) only partially
                    // covers the constructor — a subsequent pattern for the same tag is reachable.
                    val isFullCoverage = pattern.payload == null ||
                        pattern.payload is MonoWildcardPattern || pattern.payload is MonoVarPattern
                    if (case.guard == null && isFullCoverage && pattern.tag in seenConstructors) {
                        errors.add(TypeCheckError(
                            "Unreachable pattern: constructor '${pattern.tag}' already matched above",
                            case.loc.toSL()
                        ))
                    }
                    if (case.guard == null && isFullCoverage) seenConstructors.add(pattern.tag)
                }
                is MonoWildcardPattern, is MonoVarPattern -> {
                    if (case.guard == null) {
                        if (!isOpen && seenConstructors.containsAll(allConstructors) && allConstructors.isNotEmpty()) {
                            errors.add(TypeCheckError(
                                "Unreachable pattern: all constructors of closed variant already matched (${allConstructors.joinToString(", ")})",
                                case.loc.toSL()
                            ))
                        }
                        wildcardSeen = true
                    }
                }
                is MonoLiteralPattern -> {
                    val value = pattern.value
                    if (value is MonoBoolLit && case.guard == null) {
                        seenConstructors.add(if (value.value) "True" else "False")
                    }
                }
                else -> {}
            }
        }
    }

    private fun Loc.toSL(): SourceLocation? {
        return if (file == "<unknown>" && startLine == 1 && startCol == 1) null
        else SourceLocation(startLine, startCol, file)
    }
}
