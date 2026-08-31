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
        constraintResults: Map<String, ConstraintResult>,
        currentDeclName: String? = null,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        // Restrict scrutinee-type inference to the CURRENT declaration's constraints.
        // Without this filter, `extractParamType` scans ALL decls' function types
        // and grabs the first that happens to have a parameter with the matching
        // name — silently picking up an unrelated decl's `var` param (e.g. `deref`'s
        // `var: TVar 'a` matches `unwrap_tmvar`'s `var: TMVar 'a`), so the wrong type
        // reaches exhaustiveness and demands wrong constructors.
        val scopedResults: Map<String, ConstraintResult> =
            if (currentDeclName != null && constraintResults.containsKey(currentDeclName)) {
                mapOf(currentDeclName to constraintResults.getValue(currentDeclName))
            } else {
                constraintResults
            }
        walkParsedExpr(expr) { node ->
            if (node is ParsedMatch) {
                val hasConstructorPattern = node.cases.any { case ->
                    case.patterns.any { it is ParsedConstructorPattern }
                }
                if (!hasConstructorPattern) {
                    val scrutineeType = inferParsedScrutineeType(node, substitution, scopedResults)
                    if (scrutineeType != null) {
                        val result = validateParsedExhaustiveness(node, scrutineeType)
                        if (result is ValidationResult.Failure) errors.addAll(result.errors)
                    }
                }
            }
        }
        return errors
    }

    // S5.1 (#364) BLOCKER: this re-inference could NOT be replaced by reading the
    // decided TypedMatch scrutinee type off the emitted TypedModule. Emit ERASES a
    // newtype scrutinee to its underlying primitive (`match Age.(42) is Age.(x) ->`
    // emits scrutinee type Int, not Age), so exhaustiveness reading the decided node
    // would hit the non-enumerable-primitive path and (wrongly) demand a wildcard —
    // the `Age.(x)` newtype pattern is not a catch-all, so it would raise a spurious
    // V0601. It also loses the alias-expanded field type for a field-access scrutinee
    // when the receiver's param type is an unexpanded record alias. The constraint-time
    // matchScrutineeTypes side-table records the UNERASED scrutinee type, which is what
    // source-level exhaustiveness needs — so it stays as the source. See report.
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
                                ctor.name to ctor.dataType?.let { TypeAnnotationConverter.convert(it, resolveTypeHash = { name -> registry.lookupType(name)?.let { d -> d.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashTypeDecl(d) } }) }
                            },
                            rowVar = if (definition.isOpen) RowVar.fresh() else null
                        )
                    }
                }
            }
        }

        // BUG D (#332): PRIMARY path — the scrutinee's SOLVED type, recorded at
        // constraint generation keyed by the scrutinee's Loc. This covers EVERY
        // scrutinee expression (local `let` binding, field access, call result,
        // parenthesized expr), not just a bare function parameter. The parameter
        // re-derivation below is a legacy fallback for the rare case where the
        // loc-keyed type is still an unresolved var (e.g. a fully generic
        // scrutinee), where surfacing nothing is correct anyway.
        val scrutineeLoc = matchExpr.scrutinee.loc
        for ((_, result) in constraintResults) {
            val recorded = result.matchScrutineeTypes[scrutineeLoc] ?: continue
            surfaceScrutineeType(substitution.apply(recorded))?.let { return it }
        }

        // Legacy fallback: recover the scrutinee type from the enclosing decl's
        // PARAMETER shape when the scrutinee is a bare parameter identifier. Kept
        // for the case where the loc-keyed record above is absent (defensive).
        val scrutinee = matchExpr.scrutinee
        if (scrutinee is ParsedIdentifier) {
            val varName = scrutinee.localName
            for ((_, result) in constraintResults) {
                val resolvedType = substitution.apply(result.type)
                val paramType = extractParamType(resolvedType, varName) ?: continue
                surfaceScrutineeType(substitution.apply(paramType))?.let { return it }
            }
        }

        return null
    }

    /**
     * Given a scrutinee's solved type, surface the finite/enumerable shape that
     * `validateParsedExhaustiveness` knows how to check — or null when the type
     * has no exhaustiveness obligation (a fully generic var, a function, etc.).
     * Shared by the loc-keyed primary path and the parameter fallback.
     */
    private fun surfaceScrutineeType(finalType: MezzeType): MezzeType? {
        val expanded = when (finalType) {
            is MezzeType.TypeApplication ->
                expandTypeApplicationToVariant(finalType, registry) ?: finalType
            else -> finalType
        }
        // A variant scrutinee — check every constructor is covered.
        if (expanded is MezzeType.VariantType) return expanded
        // #83: a non-enumerable primitive (Int, Str, …) — require a catch-all.
        if (isNonEnumerablePrimitive(expanded)) return expanded
        // #203: a seq/list — require empty + open-cons pair (or a catch-all);
        // a fixed-length pattern like `[a, b]` covers only length 2.
        if (expanded is MezzeType.SeqType || expanded is MezzeType.ListType) return expanded
        // #218: a record matched with record patterns — check its finite fields.
        if (expanded is MezzeType.RecordType) return expanded
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
            // #218: a record scrutinee is exhaustive iff a catch-all covers it, or its
            // finite (variant/Bool) fields are covered. Checked conservatively (see
            // recordExhaustivenessErrors) so a truly non-exhaustive record match — e.g.
            // `{ c = Red }` only, missing Green/Blue — is rejected like the equivalent
            // top-level `match c is Red -> …`.
            if (expandedType is MezzeType.RecordType) {
                return recordExhaustivenessErrors(matchExpr, expandedType)
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
                    is ParsedNewtypePattern -> {
                        // A newtype expanded to a synthetic single-ctor variant by
                        // expandTypeApplicationToVariant: the `Name.(inner)` pattern
                        // covers that lone constructor (whose synthetic name is the
                        // newtype's `constructor` field == typeName). Match on typeName
                        // — that's what the expander stores as the variant ctor.
                        if (case.guard == null && case.ifLetBindings.isEmpty()) coveredConstructors.add(pattern.typeName)
                    }
                    is ParsedWildcardPattern, is ParsedVariablePattern -> {
                        if (case.guard == null && case.ifLetBindings.isEmpty()) hasWildcard = true
                    }
                    is ParsedLiteralPattern -> {
                        if (case.guard == null && case.ifLetBindings.isEmpty()) {
                            val lit = pattern.value
                            if (lit is ParsedLiteralValue.BoolValue) coveredConstructors.add(if (lit.value) BuiltinTypes.TRUE else BuiltinTypes.FALSE)
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
        // Exact lengths covered by fixed-length arms (`[a, b]` -> 2). An open-cons
        // arm `[e1..em, ...rest]` covers every length >= m; `minOpenConsWidth` is
        // the smallest such m seen (Int.MAX_VALUE if there is no open-cons arm).
        val fixedLengths = mutableSetOf<Int>()
        var minOpenConsWidth = Int.MAX_VALUE

        for (case in matchExpr.cases) {
            val unguarded = case.guard == null && case.ifLetBindings.isEmpty()
            if (!unguarded) continue
            for (pattern in case.patterns) {
                when (pattern) {
                    is ParsedWildcardPattern, is ParsedVariablePattern -> hasCatchAll = true
                    is ParsedSeqPattern -> {
                        val width = pattern.elements.size
                        if (pattern.restPattern != null) {
                            // `[..._]` (width 0) is a catch-all; `[e1..em, ...rest]`
                            // covers all lengths >= m.
                            if (width == 0) hasCatchAll = true
                            else minOpenConsWidth = minOf(minOpenConsWidth, width)
                        } else {
                            fixedLengths.add(width) // `[]` -> 0, `[a]` -> 1, ...
                        }
                    }
                    else -> {}
                }
            }
        }

        // Totality on the length axis: a catch-all, OR an open-cons of width m
        // whose shorter lengths 0..m-1 are each covered by a fixed-length arm —
        // then fixed arms cover {0..m-1} and the open-cons covers {m, m+1, ...},
        // so every length is handled. This generalizes the common `[]` + `[x,
        // ...rest]` pair (m == 1) to `[]`,`[x]`,`[x,y]` + `[x,y,...rest]` (m == 3).
        val openConsCoversTail = minOpenConsWidth != Int.MAX_VALUE &&
            (0 until minOpenConsWidth).all { it in fixedLengths }
        val exhaustive = hasCatchAll || openConsCoversTail
        if (exhaustive) return ValidationResult.Success()

        val coversEmpty = 0 in fixedLengths
        // The non-empty case is total only when an open-cons closes its tail; a
        // bare pile of fixed lengths never covers arbitrarily long seqs.
        val coversAllNonEmpty = openConsCoversTail
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
     * #218: exhaustiveness for a RECORD scrutinee (`match r is { c = Red } -> …`).
     *
     * A record match is exhaustive iff:
     *  - (C) some unguarded arm is a catch-all — `_`, a variable, or a fully
     *    irrefutable record pattern (every listed entry irrefutable); OR
     *  - (S) partitioning the scrutinee's fields into FINITE (closed variant / Bool)
     *    and OPAQUE (everything else), every opaque field is irrefutably bound in
     *    every arm AND the finite fields are fully covered:
     *      · no finite fields                → total (all opaque irrefutable);
     *      · exactly ONE finite field        → its constructors covered across arms
     *        (via [missingInVariantField], so nested payloads recurse for free);
     *      · ≥2 finite fields (#220)         → every COMBINATION in the cartesian
     *        product of the fields' constructor sets is covered by some arm
     *        (see [recordProductExhaustivenessErrors]). This catches the product
     *        hole `{a=T,b=T}` + `{a=F,b=F}` misses `{a=T,b=F}` WITHOUT demanding a
     *        catch-all — a `_` is only required past [MAX_RECORD_PRODUCT] combos.
     *
     * Conservative throughout: a refutable payload under a constructor does NOT
     * count as covering that field-constructor, so we may ask for more arms but
     * never accept a hole. Guarded / if-let arms never count.
     */
    private fun recordExhaustivenessErrors(
        matchExpr: ParsedMatch,
        record: MezzeType.RecordType,
    ): ValidationResult {
        fun unguarded(case: ParsedMatchCase) = case.guard == null && case.ifLetBindings.isEmpty()

        // (C) A catch-all makes the match total regardless of field coverage.
        for (case in matchExpr.cases) {
            if (!unguarded(case)) continue
            for (pattern in case.patterns) {
                when (pattern) {
                    is ParsedWildcardPattern, is ParsedVariablePattern -> return ValidationResult.Success()
                    is ParsedRecordPattern -> if (pattern.isIrrefutable()) return ValidationResult.Success()
                    else -> {}
                }
            }
        }

        // Collect the unguarded top-level record-pattern arms (field -> sub-pattern).
        val armFieldPatterns = mutableListOf<Map<String, ParsedPattern?>>()
        for (case in matchExpr.cases) {
            if (!unguarded(case)) continue
            for (pattern in case.patterns) {
                if (pattern is ParsedRecordPattern) {
                    armFieldPatterns.add(pattern.entries.associate { it.name to it.pattern })
                }
            }
        }
        if (armFieldPatterns.isEmpty()) {
            // No catch-all and no usable (unguarded) record arm ⇒ non-exhaustive.
            return ValidationResult.Failure(listOf(ValidationError.NonExhaustiveRecordMatch(
                detail = "no arm covers every value; add a `_ ->` catch-all",
                loc = matchExpr.loc,
            )))
        }

        // A field is absent from an arm ⇒ irrefutable bind ⇒ substitute a wildcard.
        fun subFor(arm: Map<String, ParsedPattern?>, field: String): ParsedPattern? =
            if (arm.containsKey(field)) arm[field] else ParsedWildcardPattern(matchExpr.loc)

        val finiteFields = record.fields.filter { resolveClosedVariant(it.value) != null }
        val opaqueFields = record.fields.keys - finiteFields.keys

        // Opaque fields (Int/Str/nested record/open variant/…) can only be exhausted
        // by an irrefutable binding in EVERY arm.
        for (fieldName in opaqueFields) {
            for (arm in armFieldPatterns) {
                val sub = subFor(arm, fieldName)
                if (sub != null && !sub.isIrrefutable()) {
                    return ValidationResult.Failure(listOf(ValidationError.NonExhaustiveRecordMatch(
                        detail = "field `$fieldName` has no finite set of cases to enumerate; add a `_ ->` catch-all",
                        loc = matchExpr.loc,
                    )))
                }
            }
        }

        if (finiteFields.isEmpty()) return ValidationResult.Success()  // opaque fields all irrefutable ⇒ total

        if (finiteFields.size == 1) {
            // One axis: reuse the nested-variant machinery so a finite field whose
            // arms destructure a constructor payload recurses for free.
            val (name, type) = finiteFields.entries.single()
            val variant = resolveClosedVariant(type)!!
            val subs = armFieldPatterns.map { subFor(it, name) }
            val errs = missingInVariantField(variant, subs, matchExpr.loc, "{ $name = ", " }")
            return if (errs.isEmpty()) ValidationResult.Success() else ValidationResult.Failure(errs)
        }

        // ≥2 finite fields: the value space is the CARTESIAN PRODUCT of the fields'
        // constructor sets. Exhaustive iff every combination is covered by some
        // (unguarded) arm. An arm covers a combination iff, for EVERY finite field,
        // its sub-pattern is irrefutable OR a bare constructor pattern matching that
        // field's constructor in the combination. (A constructor pattern with a
        // refutable payload does NOT fully cover its field-constructor — conservative:
        // may ask for more arms, never accepts a hole.)
        return recordProductExhaustivenessErrors(matchExpr, finiteFields, armFieldPatterns, ::subFor)
    }

    /** Max product size we enumerate; beyond this a `_` catch-all is required so we
     *  never blow up on a pathological match (e.g. 6 Bool fields = 64, still fine;
     *  many wide variants could explode). */
    private val MAX_RECORD_PRODUCT = 256

    private fun recordProductExhaustivenessErrors(
        matchExpr: ParsedMatch,
        finiteFields: Map<String, MezzeType>,
        armFieldPatterns: List<Map<String, ParsedPattern?>>,
        subFor: (Map<String, ParsedPattern?>, String) -> ParsedPattern?,
    ): ValidationResult {
        // (fieldName, its constructor names), stable order for readable combos.
        val axes: List<Pair<String, List<String>>> = finiteFields.entries.map { (name, type) ->
            name to resolveClosedVariant(type)!!.constructors.keys.sorted()
        }
        val productSize = axes.fold(1L) { acc, (_, ctors) -> acc * ctors.size }
        if (productSize > MAX_RECORD_PRODUCT) {
            return ValidationResult.Failure(listOf(ValidationError.NonExhaustiveRecordMatch(
                detail = "too many field combinations ($productSize) to check exhaustively; add a `_ ->` catch-all",
                loc = matchExpr.loc,
            )))
        }

        // Does `arm` cover the combination `combo` (fieldName -> constructorName)?
        fun armCovers(arm: Map<String, ParsedPattern?>, combo: Map<String, String>): Boolean =
            axes.all { (field, _) ->
                when (val sub = subFor(arm, field)) {
                    null, is ParsedWildcardPattern, is ParsedVariablePattern -> true  // irrefutable
                    is ParsedConstructorPattern -> {
                        val payload = sub.payload
                        sub.constructor == combo[field] && (payload == null || payload.isIrrefutable())
                    }
                    else -> false  // literal/seq/etc — doesn't cover a variant constructor
                }
            }

        // Enumerate the product; collect combinations no arm covers.
        val missing = mutableListOf<String>()
        fun render(combo: Map<String, String>) =
            "{ " + axes.joinToString(", ") { (f, _) -> "$f = ${combo[f]}" } + " }"
        fun walk(i: Int, acc: Map<String, String>) {
            if (i == axes.size) {
                if (armFieldPatterns.none { armCovers(it, acc) }) missing.add(render(acc))
                return
            }
            val (field, ctors) = axes[i]
            for (c in ctors) walk(i + 1, acc + (field to c))
        }
        walk(0, emptyMap())

        return if (missing.isEmpty()) ValidationResult.Success()
        else ValidationResult.Failure(listOf(ValidationError.NonExhaustiveMatch(
            coveredConstructors = emptyList(),
            missingConstructors = missing,
            loc = matchExpr.loc,
        )))
    }

    /**
     * #203/#212: nested-variant exhaustiveness. The outer variant is fully
     * covered, but a constructor arm may destructure a payload field whose type is
     * a CLOSED variant, listing only some of its constructors — at ANY depth
     * (`Wrap { m = Mid { i = X } }` misses `Mid { i = Y }`). Conservative to avoid
     * any false reject: a field is flagged only when EVERY unguarded arm pins that
     * field to a concrete inner constructor and their union still misses one. A
     * wildcard/variable/whole-payload binding, a guard, or a scalar field treats
     * the field as covered.
     */
    private fun nestedVariantExhaustivenessErrors(
        matchExpr: ParsedMatch,
        outer: MezzeType.VariantType,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((ctorName, payloadType) in outer.constructors) {
            val payloadRecord = resolveRecord(payloadType) ?: continue

            // The sub-patterns bound to this outer constructor's record payload,
            // one map (field -> sub-pattern) per unguarded arm that matches it.
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

            fun subFor(arm: Map<String, ParsedPattern?>, field: String): ParsedPattern? =
                if (arm.containsKey(field)) arm[field] else ParsedWildcardPattern(matchExpr.loc)

            // #259: with ≥2 FINITE (closed-variant, incl. Bool) fields, checking each
            // field independently accepts a diagonal-only match (`Wrap { a = True, b =
            // True }` + `Wrap { a = False, b = False }` misses `{ True, False }`). Reuse
            // the top-level #220 cartesian-product routine, prefixing each missing combo
            // with the outer constructor so the message reads `Ctor { a = …, b = … }`.
            val finiteFields = payloadRecord.fields.filter { resolveClosedVariant(it.value) != null }
            if (finiteFields.size >= 2) {
                when (val res = recordProductExhaustivenessErrors(matchExpr, finiteFields, armFieldPatterns, ::subFor)) {
                    is ValidationResult.Failure -> res.errors.forEach { err ->
                        errors.add(
                            when (err) {
                                is ValidationError.NonExhaustiveMatch -> ValidationError.NonExhaustiveMatch(
                                    coveredConstructors = err.coveredConstructors,
                                    missingConstructors = err.missingConstructors.map { "$ctorName $it" },
                                    loc = err.loc,
                                )
                                else -> err
                            }
                        )
                    }
                    is ValidationResult.Success -> {}
                }
            }

            for ((fieldName, fieldType) in payloadRecord.fields) {
                // Collect each arm's sub-pattern for this field (a missing key is
                // an irrefutable bind → whole field covered).
                val subPatterns = armFieldPatterns.map { subFor(it, fieldName) }

                // B3: a nested NON-ENUMERABLE PRIMITIVE field (Int/Str/…) pinned to
                // literals across arms is only exhaustive with a catch-all sub-pattern
                // — mirror the top-level primitive rule (line ~205). Without this the
                // field was silently treated as covered (resolveClosedVariant returns
                // null for a primitive → the `?: continue` below skipped it), so
                // `Wrap { n = 0 } / Wrap { n = 1 }` type-checked then trapped at runtime.
                if (isNonEnumerablePrimitive(fieldType)) {
                    val hasCatchAll = subPatterns.any { it == null || isCatchAllPattern(it) }
                    if (!hasCatchAll) {
                        errors.add(ValidationError.NonExhaustiveMatch(
                            coveredConstructors = emptyList(),
                            missingConstructors = listOf(
                                "$ctorName { $fieldName = _ } (a ${prettyPrintType(fieldType)} field cannot be exhausted by listing values — add a wildcard/variable)"
                            ),
                            loc = matchExpr.loc
                        ))
                    }
                    continue
                }

                // H2: the field type is a bare, still-generic type variable (`'a`) —
                // it resolves to no concrete variant here. If an arm destructures it
                // with a REFUTABLE sub-pattern (a constructor pattern, e.g.
                // `Full { v = Full { … } }`), that arm assumes a specific shape of the
                // abstract `'a`. Whatever `'a` instantiates to (`Box Str` at a call
                // site), a value of a DIFFERENT shape is unmatched, so the match cannot
                // be proven exhaustive without a catch-all sub-pattern for this field.
                // Previously `resolveClosedVariant` returned null for `'a` and the
                // `?: continue` below SILENTLY skipped the field → the match type-checked
                // then trapped at runtime with "Non-exhaustive match: no variant tag
                // matched". (A bare/opaque field bind is irrefutable → still fine.)
                if (isGenericTypeVarField(fieldType)) {
                    val hasCatchAll = subPatterns.any { it == null || isCatchAllPattern(it) }
                    // Constructor sub-patterns applied to this generic field, grouped
                    // by the variant that OWNS the matched constructor. A single-
                    // constructor variant (`Pair 'a 'b = | Pair {…} |`) is fully
                    // covered by matching its one constructor, so it's fine. A multi-
                    // constructor variant (`Box = | Full, Empty |`) matched on only
                    // SOME of its constructors leaves the rest unmatched — and because
                    // the field is generic, no later arm can cover them, so require a
                    // catch-all sub-pattern.
                    if (!hasCatchAll) {
                        val byOwner = mutableMapOf<Set<String>, MutableSet<String>>()
                        for (sub in subPatterns) {
                            if (sub !is ParsedConstructorPattern) continue
                            val ownerCtors = ownerVariantConstructors(sub.constructor) ?: continue
                            byOwner.getOrPut(ownerCtors) { mutableSetOf() }.add(sub.constructor)
                        }
                        for ((ownerCtors, covered) in byOwner) {
                            val missingInner = ownerCtors - covered
                            if (missingInner.isNotEmpty()) {
                                errors.add(ValidationError.NonExhaustiveMatch(
                                    coveredConstructors = covered.toList(),
                                    missingConstructors = missingInner.map { "$ctorName { $fieldName = $it … } (or add a wildcard)" },
                                    loc = matchExpr.loc
                                ))
                            }
                        }
                    }
                    continue
                }

                // A single finite field is fully handled by the per-field recursion
                // below; ≥2 were already product-checked above, so skip them here to
                // avoid double-reporting (the product check subsumes per-field).
                val fieldVariant = resolveClosedVariant(fieldType) ?: continue
                if (finiteFields.size >= 2) continue
                errors.addAll(missingInVariantField(fieldVariant, subPatterns, matchExpr.loc, "$ctorName { $fieldName = ", " }"))
            }
        }
        return errors
    }

    /**
     * #212: given a CLOSED variant and the sub-patterns matched against it across
     * arms, return the non-exhaustive errors — recursing into an inner
     * constructor's OWN record payload when arms destructure it. `prefix`/`suffix`
     * frame the missing-case message with the enclosing path
     * (`Wrap { m = ` … ` }`). Conservative: any wildcard/variable/whole-payload
     * arm, or a scalar/opaque inner field, treats the level as covered.
     */
    private fun missingInVariantField(
        variant: MezzeType.VariantType,
        subPatterns: List<ParsedPattern?>,
        loc: Loc,
        prefix: String,
        suffix: String,
    ): List<ValidationError> {
        val allInner = variant.constructors.keys
        val coveredInner = mutableSetOf<String>()
        // For inner ctors that arms destructure with a record payload, the arms'
        // field patterns, so we can recurse one level deeper.
        val innerRecordArms = mutableMapOf<String, MutableList<Map<String, ParsedPattern?>>>()
        for (sub in subPatterns) {
            when (sub) {
                null, is ParsedWildcardPattern, is ParsedVariablePattern -> return emptyList()  // covers everything
                is ParsedConstructorPattern -> when (val p = sub.payload) {
                    null -> coveredInner.add(sub.constructor)                     // bare inner ctor `X`
                    is ParsedRecordPattern -> {
                        // `Mid { i = … }` — record which inner ctor + its field pats,
                        // but only mark it covered once we confirm its payload is total.
                        innerRecordArms.getOrPut(sub.constructor) { mutableListOf() }
                            .add(p.entries.associate { it.name to it.pattern })
                    }
                    else -> coveredInner.add(sub.constructor)                     // opaque payload bind
                }
                else -> return emptyList()                                       // literal/seq/etc — conservative
            }
        }

        val errors = mutableListOf<ValidationError>()
        // Recurse into each inner ctor destructured with a record: its own
        // closed-variant fields must be exhausted. If so, the inner ctor is
        // covered; if not, emit the deeper missing case.
        for ((innerCtor, arms) in innerRecordArms) {
            val innerPayload = resolveRecord(variant.constructors[innerCtor]) ?: run {
                coveredInner.add(innerCtor); return@run null
            } ?: continue
            var innerCovered = true
            val innerErrors = mutableListOf<ValidationError>()
            for ((fName, fType) in innerPayload.fields) {
                val fVariant = resolveClosedVariant(fType) ?: continue
                val fSubs = arms.map { a -> if (a.containsKey(fName)) a[fName] else ParsedWildcardPattern(loc) }
                val deeper = missingInVariantField(fVariant, fSubs, loc, "$prefix$innerCtor { $fName = ", " }$suffix")
                if (deeper.isNotEmpty()) { innerCovered = false; innerErrors.addAll(deeper) }
            }
            if (innerCovered) coveredInner.add(innerCtor) else errors.addAll(innerErrors)
        }

        val missing = allInner - coveredInner
        if (missing.isNotEmpty()) {
            errors.add(ValidationError.NonExhaustiveMatch(
                coveredConstructors = coveredInner.toList(),
                missingConstructors = missing.map { "$prefix$it$suffix" },
                loc = loc,
            ))
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
            // #218: Bool is the closed `True | False` variant (special-cased),
            // so a Bool field is finite and must have both True and False covered.
            is MezzeType.BoolType -> BuiltinTypes.getBoolType()
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

    /**
     * H2: is this field type a bare, still-generic type variable (`'a`) — i.e. a
     * VarType that names a type PARAMETER, not an unresolved declared type name?
     * Type parameters start with `'`; a VarType built from an unresolved decl name
     * (`inner: Inner`) does resolve to a declaration, so it is NOT generic.
     */
    private fun isGenericTypeVarField(type: MezzeType): Boolean =
        type is MezzeType.VarType &&
            type.variable.name.startsWith("'") &&
            variantFromDeclName(type.variable.name) == null

    /**
     * H2: the full constructor-name set of the CLOSED variant declaration that owns
     * `ctorName`, or null if it isn't a (closed) variant constructor. Used to decide
     * whether a constructor sub-pattern on a generic field is total (single-ctor
     * variant → yes) or refutable (multi-ctor, only some matched → no).
     */
    private fun ownerVariantConstructors(ctorName: String): Set<String>? {
        for ((_, typeDecl) in typeDeclarations) {
            val def = typeDecl.definition as? ParsedVariantDef ?: continue
            if (def.isOpen) continue
            val names = def.constructors.map { it.name }
            if (ctorName in names) return names.toSet()
        }
        return null
    }

    /** Build a closed VariantType for a declared variant type NAME, or null. */
    private fun variantFromDeclName(name: String): MezzeType.VariantType? {
        val def = typeDeclarations[name]?.definition as? ParsedVariantDef ?: return null
        if (def.isOpen) return null
        return MezzeType.VariantType(
            constructors = def.constructors.associate { it.name to it.dataType?.let { dt -> TypeAnnotationConverter.convert(dt, resolveTypeHash = { name -> registry.lookupType(name)?.let { d -> d.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashTypeDecl(d) } }) } },
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
                // #270: `case.patterns` are the OR-alternatives of one arm
                // (`R | G` → [R, G]). The arm is unreachable only when EVERY
                // alternative that has a duplicable key is already covered — a
                // fresh alternative (e.g. `B` in `G | B`) keeps the arm live.
                // Reporting per-alternative wrongly flagged a partially-overlapping
                // or-arm as unreachable.
                val keyed = case.patterns.mapNotNull { duplicableKey(it) }
                val armIsDuplicate = case.patterns.isNotEmpty() &&
                    keyed.size == case.patterns.size &&   // every alternative is a duplicable-key shape…
                    keyed.all { it in coveredKeys }        // …and all already covered.
                if (armIsDuplicate) {
                    errors.add(ValidationError.UnreachablePattern(
                        pattern = case.patterns.first(),
                        reason = "This case is already handled by an earlier arm; it will never execute.",
                        loc = case.loc,
                        removableArmLoc = case.loc
                    ))
                }
                // Record every alternative's key as covered for later arms, and
                // note exhaustion if any alternative is a catch-all.
                for (pattern in case.patterns) {
                    duplicableKey(pattern)?.let { coveredKeys.add(it) }
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
                            ctor.name to ctor.dataType?.let { TypeAnnotationConverter.convert(it, resolveTypeHash = { name -> registry.lookupType(name)?.let { d -> d.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashTypeDecl(d) } }) }
                        },
                        rowVar = if (definition.isOpen) RowVar.fresh() else null
                    )
                }
            }
        }
        return null
    }
}
