package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant
import com.mezze.language.typechecker.solving.expandTypeApplicationToRecord
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
                    // #83: also surface a non-enumerable primitive scrutinee (Int,
                    // Str, ...) so validateParsedExhaustiveness can require a
                    // catch-all. Only for literal-only matches (this path already
                    // runs solely when there's no constructor pattern).
                    if (isNonEnumerablePrimitive(expanded)) return expanded
                    // #203: a seq/list scrutinee, so validateParsedExhaustiveness can
                    // require an empty + open-cons pair (or a catch-all). A fixed-length
                    // pattern like `[a, b]` covers only length 2 and is not total.
                    if (expanded is MezzeType.SeqType || expanded is MezzeType.ListType) return expanded
                }
            }
        }

        return null
    }

    private fun extractParamType(type: MezzeType, paramName: String): MezzeType? = when (type) {
        is MezzeType.FunctionType -> (type.paramType as? MezzeType.RecordType)?.fields?.get(paramName)
        else -> null
    }

    /** Primitive scalar types whose values can't be enumerated, so a match on
     *  them is only exhaustive with a catch-all (`_`/variable). Bool is NOT here
     *  — it's finite and handled by the variant path. */
    private fun isNonEnumerablePrimitive(type: MezzeType): Boolean = when (type) {
        is MezzeType.IntType, is MezzeType.NatType, is MezzeType.FloatType,
        is MezzeType.FracType, is MezzeType.StringType, is MezzeType.CharType -> true
        else -> false
    }

    /** A pattern that matches ANY value of a non-enumerable primitive — i.e. a
     *  catch-all. Wildcard `_` and a bare variable bind anything. A string
     *  interpolation pattern is a catch-all only when it's a *pure* whole-string
     *  capture (`"$(all)"` — one Capture, no Literal parts); once it has literal
     *  text (`"pre$(x)"`, `"$(a)-$(b)"`) it only matches some strings. */
    private fun isCatchAllPattern(pattern: ParsedPattern): Boolean = when (pattern) {
        is ParsedWildcardPattern, is ParsedVariablePattern -> true
        is ParsedStringInterpPattern ->
            pattern.parts.size == 1 && pattern.parts.single() is ParsedStringPatternPart.Capture
        else -> false
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

        // #83: a match on a NON-ENUMERABLE primitive (Int, Str, Float, ...) can
        // never be exhaustive by listing literals — there's no finite constructor
        // set — so it must have a catch-all (`_` or a variable arm). Without one,
        // a value not covered by the literals hits no branch at runtime. Same
        // exhaustiveness error as a variant missing a constructor.
        if (expandedType !is MezzeType.VariantType) {
            // #203: a seq/list match is exhaustive only if it covers every length.
            // The sound, conservative rule: a catch-all arm (`_`/variable/`[...all]`/
            // `[..._]`), OR an empty-arm (`[]`) together with an open-cons arm
            // (`[x, ...rest]`/`[x, ..._]`) that covers all lengths >= 1. A
            // fixed-length pattern (`[a]`, `[a, b]`) covers only that one length.
            if (expandedType is MezzeType.SeqType || expandedType is MezzeType.ListType) {
                return seqExhaustivenessErrors(matchExpr)
            }
            if (isNonEnumerablePrimitive(expandedType)) {
                val hasCatchAll = matchExpr.cases.any { case ->
                    case.guard == null && case.ifLetBindings.isEmpty() &&
                        case.patterns.any { isCatchAllPattern(it) }
                }
                if (!hasCatchAll) {
                    errors.add(ValidationError.NonExhaustiveMatch(
                        coveredConstructors = emptyList(),
                        missingConstructors = listOf("_ (a ${prettyPrintType(expandedType)} match cannot be exhaustive without a wildcard)"),
                        loc = matchExpr.loc
                    ))
                }
            }
            return if (errors.isEmpty()) ValidationResult.Success() else ValidationResult.Failure(errors)
        }

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
            } else if (uncoveredConstructors.isEmpty() && !hasWildcard) {
                // #203: the outer variant IS fully covered, but a covered
                // constructor's payload may destructure a NESTED variant field
                // non-exhaustively (`Wrap { inner = X }` covers X but not Y). The
                // check above only inspects outer constructors — recurse into
                // payloads.
                errors.addAll(nestedVariantExhaustivenessErrors(matchExpr, expandedType))
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

    /**
     * #203: seq/list length exhaustiveness. A seq value can have any length 0, 1,
     * 2, …, so a match is exhaustive only if every length is covered. This is the
     * same shape as nil/cons list exhaustiveness:
     *
     *   exhaustive  ⇔  a catch-all arm exists
     *                   OR ( an empty arm `[]`  AND  an open-cons arm `[x, ...rest]` )
     *
     * where a **catch-all** is `_`, a bare variable, or a whole-seq rest with no
     * fixed elements (`[...all]` / `[..._]`); an **open-cons** is ONE leading
     * element plus a rest (`[x, ...rest]`, `[Some x, ...tail]`), which covers every
     * length ≥ 1. A wider open-cons like `[a, b, ...rest]` covers only length ≥ 2
     * (leaving length 1 uncovered), and fixed-length patterns (`[a]`, `[a, b]`)
     * cover only that single length — so neither alone makes the match total.
     *
     * This check is deliberately about the LENGTH axis only. An open-cons arm
     * whose head is refutable (`[Some x, ...tail]`) still counts as covering the
     * "non-empty" length case — whether its *elements* match every value is a
     * separate, nested-pattern question we do NOT decide here (e.g. two arms
     * `[Some x, ...t]` + `[None, ...t]` are jointly total and must not be
     * rejected). Requiring an irrefutable head would false-reject that idiom, so
     * we don't. The trade: we may miss a hole that is purely about element
     * coverage inside an open-cons; we never invent a false length error.
     *
     * Only UNGUARDED arms count toward coverage — a guard or an if-let binding may
     * fail at runtime, so it can't be relied on to cover its length.
     */
    private fun seqExhaustivenessErrors(matchExpr: ParsedMatch): ValidationResult {
        var hasCatchAll = false
        var coversEmpty = false
        var coversAllNonEmpty = false

        for (case in matchExpr.cases) {
            val unguarded = case.guard == null && case.ifLetBindings.isEmpty()
            if (!unguarded) continue
            for (pattern in case.patterns) {
                when (pattern) {
                    is ParsedWildcardPattern, is ParsedVariablePattern -> hasCatchAll = true
                    is ParsedSeqPattern -> {
                        val hasRest = pattern.restPattern != null
                        when {
                            pattern.elements.isEmpty() && hasRest -> hasCatchAll = true      // [...all] / [..._]
                            pattern.elements.isEmpty() && !hasRest -> coversEmpty = true      // []
                            pattern.elements.size == 1 && hasRest -> coversAllNonEmpty = true // [x, ...rest], [Some x, ...t]
                            // A wider open-cons (`[a, b, ...rest]`, covers len>=2) or a
                            // fixed-length pattern (`[a]`, `[a, b]`) covers only some
                            // lengths — neither alone makes the non-empty case total.
                            else -> {}
                        }
                    }
                    else -> {}
                }
            }
        }

        val exhaustive = hasCatchAll || (coversEmpty && coversAllNonEmpty)
        if (exhaustive) return ValidationResult.Success()

        val missing = when {
            !coversEmpty && !coversAllNonEmpty ->
                "the empty seq `[]` and a non-empty seq `[x, ...rest]`"
            !coversEmpty -> "the empty seq `[]`"
            else -> "a non-empty seq `[x, ...rest]` (add `[x, ...rest]`, `[x, ..._]`, or `_`)"
        }
        return ValidationResult.Failure(listOf(ValidationError.NonExhaustiveMatch(
            coveredConstructors = emptyList(),
            missingConstructors = listOf(missing),
            loc = matchExpr.loc
        )))
    }

    /**
     * #203: nested-variant exhaustiveness. The outer variant is fully covered, but
     * a constructor arm may destructure a payload field whose type is a CLOSED
     * variant, listing only some of its constructors. Conservative to avoid ANY
     * false reject: a field is flagged only when EVERY unguarded arm for the outer
     * constructor pins that field to a concrete inner constructor and their union
     * still misses one. Any wildcard/variable/whole-payload binding, a guard, or a
     * deeper payload on the inner ctor treats the field as covered.
     */
    private fun nestedVariantExhaustivenessErrors(
        matchExpr: ParsedMatch,
        outer: MezzeType.VariantType,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((ctorName, payloadType) in outer.constructors) {
            val payloadRecord = resolveRecord(payloadType) ?: continue

            // Field-name -> list of sub-patterns bound across the unguarded arms
            // that match this outer constructor with a record payload.
            val armFieldPatterns = mutableListOf<Map<String, ParsedPattern?>>()
            var opaque = false  // an arm binds the whole payload (`Ctor x`) → all fields covered
            for (case in matchExpr.cases) {
                if (case.guard != null || case.ifLetBindings.isNotEmpty()) continue
                for (pattern in case.patterns) {
                    if (pattern !is ParsedConstructorPattern || pattern.constructor != ctorName) continue
                    when (val rec = pattern.payload) {
                        is ParsedRecordPattern -> armFieldPatterns.add(rec.entries.associate { it.name to it.pattern })
                        else -> opaque = true
                    }
                }
            }
            if (opaque || armFieldPatterns.isEmpty()) continue

            for ((fieldName, fieldType) in payloadRecord.fields) {
                val fieldVariant = resolveClosedVariant(fieldType) ?: continue
                val allInner = fieldVariant.constructors.keys
                val coveredInner = mutableSetOf<String>()
                var covered = false
                for (arm in armFieldPatterns) {
                    val sub = if (arm.containsKey(fieldName)) arm[fieldName] else { covered = true; null }
                    when (sub) {
                        null -> covered = true                                   // `{ inner }` pun binds it
                        is ParsedWildcardPattern, is ParsedVariablePattern -> covered = true
                        is ParsedConstructorPattern ->
                            if (sub.payload == null) coveredInner.add(sub.constructor) else covered = true
                        else -> covered = true                                   // literal/seq/etc — conservative
                    }
                }
                if (covered) continue
                val missing = allInner - coveredInner
                if (missing.isNotEmpty()) {
                    errors.add(ValidationError.NonExhaustiveMatch(
                        coveredConstructors = coveredInner.map { "$ctorName.$fieldName=$it" },
                        missingConstructors = missing.map { "$ctorName { $fieldName = $it }" },
                        loc = matchExpr.loc,
                    ))
                }
            }
        }
        return errors
    }

    private fun resolveRecord(type: MezzeType?): MezzeType.RecordType? = when (type) {
        is MezzeType.RecordType -> type
        is MezzeType.TypeApplication -> expandTypeApplicationToRecord(type, registry)
        else -> null
    }

    private fun resolveClosedVariant(type: MezzeType): MezzeType.VariantType? {
        val v = when (type) {
            is MezzeType.VariantType -> type
            is MezzeType.TypeApplication -> expandTypeApplicationToVariant(type, registry)
            // A field type built from a bare annotation (`inner: Inner`) via
            // TypeAnnotationConverter arrives as VarType(name = "Inner") — the type
            // NAME wasn't resolved to its declaration. Resolve it here by looking up
            // the matching variant decl so nested exhaustiveness can see its arms.
            is MezzeType.VarType -> variantFromDeclName(type.variable.name)
            else -> null
        } ?: return null
        return if (v.rowVar == null) v else null
    }

    /** Build a closed VariantType for a declared variant type NAME, or null. */
    private fun variantFromDeclName(name: String): MezzeType.VariantType? {
        val def = typeDeclarations[name]?.definition as? ParsedVariantDef ?: return null
        if (def.isOpen) return null
        return MezzeType.VariantType(
            constructors = def.constructors.associate { it.name to it.dataType?.let { dt -> TypeAnnotationConverter.convert(dt) } },
            rowVar = null,
        )
    }

    fun validateParsedUnreachablePatterns(matchExpr: ParsedMatch): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        var isExhausted = false
        // #80: literal values and bare constructor names already covered by an
        // earlier arm. A later arm matching one of these can never execute — it's
        // a duplicate/unreachable arm (almost always a typo), reported as an error
        // just like the "after a catch-all" case below.
        val coveredKeys = HashSet<String>()

        for (case in matchExpr.cases) {
            if (isExhausted) {
                errors.add(ValidationError.UnreachablePattern(
                    pattern = case.patterns.firstOrNull(),
                    reason = "Previous pattern already matches all cases",
                    loc = case.loc,
                    removableArmLoc = case.loc
                ))
                continue
            }
            // A guard/if-let arm may not fire even when its shape repeats, so it
            // neither closes off future arms nor counts as covering a key.
            if (case.guard == null && case.ifLetBindings.isEmpty()) {
                for (pattern in case.patterns) {
                    val key = duplicableKey(pattern)
                    if (key != null && !coveredKeys.add(key)) {
                        errors.add(ValidationError.UnreachablePattern(
                            pattern = pattern,
                            reason = "This case is already handled by an earlier arm; it will never execute.",
                            loc = case.loc,
                            removableArmLoc = case.loc
                        ))
                    }
                    if (pattern is ParsedWildcardPattern || pattern is ParsedVariablePattern) {
                        isExhausted = true
                    }
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.Success() else ValidationResult.Failure(errors)
    }

    /** A stable key for a pattern that, if it appears twice unguarded, makes the
     *  second arm unreachable: literal values and BARE constructor names. A
     *  constructor pattern WITH a payload is skipped — two `Some {…}` arms may
     *  destructure different sub-shapes, so repeating the name isn't necessarily a
     *  duplicate. Wildcards/variables are handled by the exhaustion flag. */
    private fun duplicableKey(pattern: ParsedPattern): String? = when (pattern) {
        is ParsedLiteralPattern -> when (val v = pattern.value) {
            is ParsedLiteralValue.IntValue -> "int:${v.value}"
            is ParsedLiteralValue.NatValue -> "nat:${v.value}"
            is ParsedLiteralValue.FloatValue -> "float:${v.value}"
            is ParsedLiteralValue.StringValue -> "str:${v.value}"
            is ParsedLiteralValue.BoolValue -> "bool:${v.value}"
        }
        is ParsedConstructorPattern -> if (pattern.payload == null) "ctor:${pattern.constructor}" else null
        else -> null
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
