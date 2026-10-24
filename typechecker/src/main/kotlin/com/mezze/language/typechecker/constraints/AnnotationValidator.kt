package com.mezze.language.typechecker.constraints

import com.mezze.language.parsedast.diagnostics.DiagnosticHints
import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.solving.UnificationResult
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.parsedast.diagnostics.ErrorCode

internal class AnnotationValidator(
    private val registry: DeclarationRegistry,
) {
    fun validateAnnotationAgainstExpression(
        binding: ParsedLetDecl,
        annotatedType: MezzeType,
        annotation: ParsedTypeAnnotation,
        expression: ParsedExpr,
        inferredType: MezzeType
    ) {
        if (expression is ParsedRecordLit) {
            val resolvedRecordInfo = resolveToRecordType(annotatedType)
            if (resolvedRecordInfo != null) {
                val (annotatedFields, isOpen) = resolvedRecordInfo
                val literalFields = mutableSetOf<String>()

                for (entry in expression.entries) {
                    when (entry) {
                        is ParsedRecordEntry.Field -> literalFields.add(entry.name)
                        is ParsedRecordEntry.Spread -> {
                            return
                        }
                    }
                }

                if (!isOpen) {
                    val extraFields = literalFields - annotatedFields
                    if (extraFields.isNotEmpty()) {
                        throw TypeCheckError(
                            "Type annotation mismatch: record has extra field(s) ${extraFields.sorted().joinToString(", ")} " +
                            "not present in closed record type annotation { ${annotatedFields.sorted().joinToString(", ") { "$it: ..." }} }",
                            expression.loc,
                            code = ErrorCode.T0203_RECORD_EXTRA_FIELDS,
                        )
                    }
                }

                val missingFields = annotatedFields - literalFields
                if (missingFields.isNotEmpty()) {
                    throw TypeCheckError(
                        "Type annotation mismatch: record is missing field(s) ${missingFields.sorted().joinToString(", ")} " +
                        "required by ${if (isOpen) "open" else "closed"} record type { ${annotatedFields.sorted().joinToString(", ") { "$it: ..." }}${if (isOpen) ", ..." else ""} }",
                        expression.loc,
                        code = ErrorCode.T0202_RECORD_MISSING_FIELDS,
                    )
                }
            }
        }

        if (annotatedType is MezzeType.FunctionType && expression is ParsedLambda) {
            val annotatedParamType = annotatedType.paramType

            val expectedParamCount = when (annotatedParamType) {
                is MezzeType.RecordType -> annotatedParamType.fields.size
                else -> 1
            }

            val actualParamCount = when (val params = expression.params) {
                is ParsedLambdaParams.Untyped -> params.names.size
                is ParsedLambdaParams.Typed -> params.fields.size
            }

            if (expectedParamCount != actualParamCount) {
                throw TypeCheckError(
                    "Type annotation mismatch: function expects $expectedParamCount parameter(s) " +
                    "but lambda has $actualParamCount parameter(s)",
                    expression.loc,
                    code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                    hints = listOf(DiagnosticHints.ANNOTATION_VS_BODY),
                )
            }
        }
    }

    fun validatePolymorphismPreserved(
        annotationType: MezzeType,
        substitution: Substitution,
        varName: String,
        sourceLoc: Loc? = null,
        abilityConstrainedTypeVars: Set<TypeVar> = emptySet(),
        // B1 containment: type-var ids that are an ability's associated result
        // (`having 'out`) left UNRESOLVED by this binding's solve. An annotation var
        // that resolves to one of these is being satisfied by an associated type the
        // impl computes — NOT by the annotation var itself — so the annotation
        // over-claims (e.g. `f: {x:'a} -> 'a = {x} -> x + 1`: the result is
        // `out(NumAdd Int 'a)`, which is Int for `'a = Nat`, not `'a`). Reject unless
        // the annotation explicitly declares it via `having` (those are exempt through
        // `abilityConstrainedTypeVars`, checked before this).
        unresolvedAssociatedOutVarIds: Set<Int> = emptySet()
    ) {
        val annotationTypeVars = collectTypeVars(annotationType)

        for (typeVar in annotationTypeVars) {
            if (abilityConstrainedTypeVars.any {
                it.name.removePrefix("'") == typeVar.name.removePrefix("'") || it.id == typeVar.id
            }) {
                continue
            }

            val substituted = substitution.apply(MezzeType.VarType(typeVar))
            if (substituted is MezzeType.VarType) {
                if (substituted.variable.id in unresolvedAssociatedOutVarIds) {
                    throw TypeCheckError(
                        "Type annotation mismatch for '$varName': Annotation declares type variable '${typeVar.name}' " +
                        "but the implementation produces an associated result type (from a `having 'out` ability) that is " +
                        "not guaranteed to equal '${typeVar.name}'. The result depends on the ability impl, so it may differ " +
                        "from '${typeVar.name}' for some argument types.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("declare the result via `having 'out = ${typeVar.name}` if that is intended, or change the annotation/body to agree"),
                    )
                }
            } else {
                val substitutedTypeVars = collectTypeVars(substituted)
                if (substitutedTypeVars.isEmpty()) {
                    throw TypeCheckError(
                        "Type annotation mismatch for '$varName': Annotation declares type variable '${typeVar.name}' " +
                        "but implementation forces it to concrete type '${prettyPrintType(substituted)}'. " +
                        "The implementation must work for all types, not just specific ones.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf(DiagnosticHints.ANNOTATION_VS_BODY),
                    )
                }
            }
        }

        // Skolem distinctness: distinct annotation variables (`'a`, `'b`) must
        // stay distinct in the body. If two differently-named annotation vars
        // resolve to the SAME variable, the body conflated them — e.g.
        // `{ a: 'a, b: 'b } -> 'a` implemented as `{ a, b } -> b` really has type
        // `-> 'b`, not `-> 'a`. Treating each quantified var as a rigid skolem,
        // unifying two skolems is illegal; we detect it by two source names
        // collapsing onto one resolved var.
        //
        // Unlike the concrete-forcing check above, we do NOT skip ability-
        // constrained vars here: `{ a: 'a, b: 'b } with Eq 'a, Eq 'b -> Bool`
        // implemented as `a == b` conflates 'a and 'b (== compares two values of
        // the SAME type), and both resolve to the same var — that's the bug this
        // catches. A constraint on 'a is independent of a constraint on 'b; it
        // does not license unifying them.
        val nameToResolved = mutableMapOf<String, TypeVar>()
        for (typeVar in annotationTypeVars) {
            val sourceName = typeVar.name.removePrefix("'")
            if (sourceName.isEmpty()) continue
            val resolved = substitution.apply(MezzeType.VarType(typeVar))
            if (resolved !is MezzeType.VarType) continue  // concrete case handled above
            val resolvedVar = resolved.variable
            for ((otherName, otherResolved) in nameToResolved) {
                if (otherName != sourceName && otherResolved.id == resolvedVar.id) {
                    throw TypeCheckError(
                        "Type annotation mismatch for '$varName': Annotation declares distinct type " +
                        "variables '$otherName' and '$sourceName', but the implementation forces them " +
                        "to be the same type. Each must remain independent.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("give the two positions the same variable in the annotation if they must match, or change the body so they stay independent"),
                    )
                }
            }
            nameToResolved[sourceName] = resolvedVar
        }
    }

    /**
     * Does [type] contain a function whose own effect row is a BARE `<...>` — open
     * with no named effects, i.e. "any effects at all"?
     *
     * Used only to improve a diagnostic (see [validateEffectsNotOverpromised]).
     * Structural, so no diagnostic's wording can change which programs compile —
     * the mistake this file's sibling checks were burned by twice.
     *
     * The case set mirrors `TypePrinter.collectFreeTypeVars` deliberately rather
     * than inventing another partial walker: same five shapes, same `else -> false`.
     */
    private fun hasBareOpenRow(type: MezzeType): Boolean = when (type) {
        is MezzeType.FunctionType -> {
            val row = type.effects
            (row != null && row.isOpen && row.allEffectNames.isEmpty()) ||
                hasBareOpenRow(type.paramType) || hasBareOpenRow(type.returnType)
        }
        is MezzeType.RecordType -> type.fields.values.any { hasBareOpenRow(it) }
        is MezzeType.VariantType -> type.constructors.values.filterNotNull().any { hasBareOpenRow(it) }
        is MezzeType.TypeApplication -> type.args.any { hasBareOpenRow(it) }
        else -> false
    }

    fun validateEffectsNotOverpromised(
        annotationType: MezzeType,
        inferredType: MezzeType,
        varName: String,
        sourceLoc: Loc? = null
    ) {
        if (annotationType is MezzeType.FunctionType && inferredType is MezzeType.FunctionType) {
            val annotatedEffects = annotationType.effects?.allEffectNames ?: emptySet()
            val inferredEffects = inferredType.effects?.allEffectNames ?: emptySet()

            val isPolymorphic = annotationType.effects?.isOpen == true
            val overpromisedEffects = annotatedEffects - inferredEffects
            if (overpromisedEffects.isNotEmpty() && !isPolymorphic) {
                // A parameter whose own row is a BARE `<...>` is the common cause, and
                // "not used in the implementation" is the wrong story for it. Such a
                // parameter may perform ANY effects, so a body that only calls it
                // cannot be known to perform THIS one — the declaration would be a
                // lie, and the rejection is right. What the author needs is which
                // spelling to use instead, and the message said nothing about that.
                //
                // Both honest forms are measured working, and both work through a type
                // alias: name the effect on the parameter (`{} -> 'a <E, ...>`), which
                // guarantees it is performed; or let the row flow out to the result
                // (`-> 'r <...>`), which promises only what the callee promises.
                val bareOpenRowParam = hasBareOpenRow(annotationType.paramType)
                throw TypeCheckError(
                    "Type annotation mismatch for '$varName': Annotation declares effects ${overpromisedEffects.joinToString(", ")} " +
                    "which are not used in the implementation." +
                    if (bareOpenRowParam) {
                        " A parameter declared `<...>` may perform ANY effects, so calling it does not " +
                        "establish ${overpromisedEffects.joinToString(", ")} — nothing here performs that."
                    } else "",
                    sourceLoc,
                    code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                    hints = if (bareOpenRowParam) {
                        listOf(
                            "name the effect on the parameter's own row — `<${overpromisedEffects.joinToString(", ")}, ...>` — " +
                                "which says the callee DOES perform it",
                            "or let the row flow through instead of naming it here: declare the result `<...>`, " +
                                "promising only what the callee promises",
                        )
                    } else listOf(DiagnosticHints.ANNOTATION_VS_BODY),
                )
            }

            // #274-B: Under-promise — closed annotation missing effects
            // the body actually performs. `<...>` (open) absorbs extras
            // via row-var propagation, so this only fires for CLOSED
            // annotations.
            if (!isPolymorphic) {
                val underpromised = inferredEffects - annotatedEffects
                if (underpromised.isNotEmpty()) {
                    throw TypeCheckError(
                        "Type annotation for '$varName' doesn't match inferred type: " +
                        "annotation is missing effects ${underpromised.joinToString(", ")} " +
                        "performed by the body.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf(
                            "add the missing effects to the annotation, or use `<...>` to allow the body to perform additional effects",
                            "e.g. `<${(annotatedEffects + underpromised).joinToString(", ")}>`"
                        )
                    )
                }
            }

            // Under-promised `<*>`. A body that PERFORMED effects and handled
            // them itself is not the same as a body that performed none, and an
            // annotation must not be able to claim otherwise.
            //
            // The name-based checks above cannot see this: `performed` is a flag
            // BESIDE the effects set, not a member of it, so both sides compare
            // as the empty set and agree. `{} -> Int` written over a body that
            // discharged `Console` therefore passed silently — erasing the one
            // marker that says work happened in here, which is the marker a
            // caller has to read to know a dependency is doing something it is
            // handling out of sight.
            //
            // `*` is a MEMBER of the row, not a mode, so it composes with named
            // effects: `<Log, *>` reads "Log escapes, and something else was
            // handled in here". The check is therefore the same whether the
            // annotation names effects or not — an annotation that lists what
            // escapes but drops the `*` is hiding exactly as much as a pure one.
            if (!isPolymorphic &&
                inferredType.effects?.performed == true &&
                annotationType.effects?.performed != true
            ) {
                val suggested = (annotatedEffects.sorted() + "*").joinToString(", ")
                throw TypeCheckError(
                    "Type annotation for '$varName' is missing `*`: the body performs " +
                    "effects and handles them itself, and the annotation hides that. " +
                    "A caller cannot then tell it apart from a body that handled nothing.",
                    sourceLoc,
                    code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                    hints = listOf(
                        "add `*` to the annotation's effect row: `<$suggested>`",
                        "or drop the `perform` from the body if it genuinely handles nothing",
                    ),
                )
            }
        }
    }

    fun validateAbilityOnCorrectTypeVar(
        annotationType: MezzeType,
        inferredAbilityConstraints: List<Constraint.AbilityRequired>,
        varName: String,
        substitution: Substitution,
        sourceLoc: Loc? = null
    ) {
        if (annotationType !is MezzeType.FunctionType) return

        val annotatedAbilities = annotationType.abilityConstraints
        val annotationTypeVars = collectTypeVars(annotationType)

        val actualUsageConstraints = inferredAbilityConstraints.filter { constraint ->
            constraint.context == null || !constraint.context.startsWith("Type annotation on")
        }

        fun resolveToSameType(tv1: TypeVar, tv2: TypeVar): Boolean {
            if (tv1.id == tv2.id) return true
            val resolved1 = substitution.apply(MezzeType.VarType(tv1))
            val resolved2 = substitution.apply(MezzeType.VarType(tv2))
            if (resolved1 == resolved2 ||
                (resolved1 is MezzeType.VarType && resolved1.variable.id == tv2.id) ||
                (resolved2 is MezzeType.VarType && resolved2.variable.id == tv1.id)) return true
            if (resolved1 is MezzeType.VarType && resolved2 is MezzeType.VarType &&
                resolved1.variable.name == resolved2.variable.name) return true
            return false
        }

        for (usage in actualUsageConstraints) {
            if (WellKnownHashConstants.isInspect(usage.abilityHash)) continue

            // Deliberately BY NAME. Hash-keying here was tried and reverted: the
            // annotation's constraints and the inferred usages derive their hashes
            // by different routes, so the same ability can carry two, and keying on
            // them failed 10+ ability-annotation tests. This is an
            // annotation-vs-inference agreement check within ONE declaration, where
            // the name is unambiguous; cross-module identity is decided at impl
            // resolution, not here.
            val matchingAnnotatedList = annotatedAbilities.filter { a ->
                val ah = a.abilityHash; val uh = usage.abilityHash
                if (ah != null && uh != null) ah == uh else a.abilityName == usage.abilityName
            }

            if (matchingAnnotatedList.isNotEmpty()) {
                val resolvedUsageTypeVar = substitution.apply(MezzeType.VarType(usage.typeVar))
                val hasMatchingTypeVar = matchingAnnotatedList.any { matchingAnnotated ->
                    matchingAnnotated.typeArgs.any { annotatedTypeArg ->
                        when (annotatedTypeArg) {
                            is MezzeType.VarType ->
                                resolveToSameType(usage.typeVar, annotatedTypeArg.variable)
                            else -> sameDeclaredOrEqual(resolvedUsageTypeVar, substitution.apply(annotatedTypeArg))
                        }
                    }
                }

                if (!hasMatchingTypeVar) {
                    val declaredVars = matchingAnnotatedList.flatMap { it.typeArgs }
                        .filterIsInstance<MezzeType.VarType>()
                        .map { it.variable.name }
                        .distinct()
                    // The variable the body used is NOT one the annotation names
                    // — that is why this branch was reached — so there is nothing
                    // a reader could look up, and the solver's own name for it
                    // (`'o1002`) only asks them to account for something they
                    // never wrote. Describe its position instead of naming it.
                    throw TypeCheckError(
                        "Type annotation mismatch for '$varName': the annotation constrains " +
                        "`with ${usage.abilityName} ${declaredVars.joinToString()}`, but the body " +
                        "applies ${usage.abilityName} to a type the annotation does not name.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf(DiagnosticHints.ANNOTATION_VS_BODY),
                    )
                }
            } else {
                val isOnAnnotationTypeVar = annotationTypeVars.any { annotationVar ->
                    resolveToSameType(usage.typeVar, annotationVar)
                }
                if (isOnAnnotationTypeVar) {
                    throw TypeCheckError(
                        "Type annotation mismatch for '$varName': the body requires " +
                        "`${usage.abilityName} ${usage.typeVar.name}`, but the annotation does not " +
                        "declare that constraint.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf(DiagnosticHints.ANNOTATION_VS_BODY),
                    )
                }
            }
        }
    }

    /**
     * Validate the `having 'x = T` bindings a user wrote in a `with X 'a having
     * 'x = T` annotation, independently of whether the receiver stays generic:
     *
     *   R5 — every `having` key must be an associated type the ability actually
     *        declares (`ability X for 'a having 'x, 'y`). A `having 'nope` for an
     *        undeclared name is a hard error.
     *   R2 — when the ability resolves that associated type to a CONCRETE type
     *        (from the impl, carried in [resolvedAssociatedBindings]), the
     *        annotated `T` must equal it. A wrong `having 'x = Int` where the
     *        impl says `'x = Str` is a hard error — even on a generic function.
     *
     * @param annotationConstraints the parsed `with … having …` constraints.
     * @param resolvedAssociatedBindings solver output keyed (abilityName,
     *        primaryVar.id) → assocName(bare) → resolved type; used for R2.
     * @param inferredAbilityConstraints the body's inferred ability constraints,
     *        used to map an annotation's ability to the primary var whose
     *        resolved bindings we look up.
     */
    fun validateHavingAnnotations(
        annotationType: MezzeType,
        annotationConstraints: List<ParsedFnAbilityConstraint>,
        inferredAbilityConstraints: List<Constraint.AbilityRequired>,
        resolvedAssociatedBindings: Map<Pair<String, Int>, Map<String, MezzeType>>,
        implDeclaredHaving: Map<String, Map<String, Set<MezzeType>>>,
        varName: String,
        sourceLoc: Loc?,
    ) {
        for (constraint in annotationConstraints) {
            if (constraint.associatedTypeBindings.isEmpty()) continue
            val abilityName = constraint.abilityName
            val abilityDecl = registry.lookupAbility(abilityName) ?: continue

            // Declared associated-type names for this ability (normalized bare).
            val declaredAssoc = abilityDecl.associatedTypes.map { it.removePrefix("'") }.toSet()

            // The resolved bindings for whichever inferred constraint corresponds
            // to this annotation's ability. There may be several primary vars; a
            // concrete resolution for the assoc name is what we check R2 against.
            val resolvedForAbility: Map<String, MezzeType> =
                inferredAbilityConstraints
                    // Hash-first, for the same reason as above. The annotation side
                    // is PARSED and has only a name, so the declaration it resolves
                    // to supplies the identity to compare against.
                    .filter { ic ->
                        val ih = ic.abilityHash
                        val dh = abilityDecl.contentHash
                        if (ih != null && dh != null) ih == dh else ic.abilityName == abilityName
                    }
                    .fold(emptyMap()) { acc, ic ->
                        acc + (resolvedAssociatedBindings[abilityName to ic.typeVar.id] ?: emptyMap())
                    }

            for ((rawName, annotatedAnn) in constraint.associatedTypeBindings) {
                val name = rawName.removePrefix("'")

                // R5: unknown associated-type name.
                if (name !in declaredAssoc) {
                    val known = if (declaredAssoc.isEmpty()) "none"
                                else declaredAssoc.sorted().joinToString(", ") { "'$it" }
                    throw TypeCheckError(
                        "Type annotation for '$varName': ability '$abilityName' has no associated type '$rawName'. " +
                        "Declared associated types: $known.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf(
                            if (declaredAssoc.isEmpty())
                                "'$abilityName' declares no `having` types; drop the `having` clause"
                            else
                                "use one of the declared associated types: $known"
                        ),
                    )
                }

                // R2: the annotated value must be consistent with what the
                // ability actually binds `'name` to. Two signals:
                //  (a) a CONCRETE resolution from solving (concrete receiver), or
                //  (b) the concrete values the ability's impls DECLARE for 'name
                //      (works on a generic receiver, where (a) is still a var).
                val annotatedType = runCatching { publicAnnToMezze(annotatedAnn) }.getOrNull() ?: continue
                if (annotatedType is MezzeType.VarType) continue  // annotated a var — allowed
                // A polymorphic `having 'out = 'a` (the binding is a type-PARAMETER
                // name, e.g. `NumAdd 'a 'a having 'out = 'a`) just says "out unifies
                // with this param" — for each concrete instantiation of 'a the impl's
                // concrete `out` satisfies it. It must NOT be checked against the
                // impls' concrete declared outputs (Int|Float|…), which would reject
                // a legitimate same-type add annotation. Some paths convert a rigid
                // param to a non-VarType shape, so the VarType guard above misses it;
                // catch it by the rendered `'`-prefixed name here.
                if (prettyPrintType(annotatedType).startsWith("'")) continue

                // L-10: compare via Unification.unify, NOT rendered-name equality.
                // The name compare was introduced to dodge a false rejection where the
                // annotation's `Money` (a bare TypeApplication) and the solver's `Money`
                // (a VariantType) are the SAME type but `==`-unequal — unify resolves
                // that representational mismatch (via expandTypeApplicationToVariant)
                // internally, so it keeps that acceptance. But unify also keys nominal
                // sameness on TypeIdentity.Declared.hash (identityVerdict), so two
                // structurally-distinct types that merely PRINT identically (a shadowed
                // `Money` with a different hash) no longer pass. prettyPrintType stays
                // for the human-readable error messages only.
                val annotatedName = prettyPrintType(annotatedType)
                fun sameType(a: MezzeType, b: MezzeType): Boolean =
                    com.mezze.language.typechecker.solving.unify(a, b, registry) is UnificationResult.Success

                val resolved = resolvedForAbility[name]
                if (resolved != null && resolved !is MezzeType.VarType && !sameType(annotatedType, resolved)) {
                    throw TypeCheckError(
                        "Type annotation for '$varName': `having '$name = $annotatedName` " +
                        "but ability '$abilityName' resolves '$name to ${prettyPrintType(resolved)}.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("change the `having` binding to ${prettyPrintType(resolved)}, or drop it to let it be inferred"),
                    )
                }

                // (b) generic receiver: compare against the impls' declared values.
                val declared = implDeclaredHaving[abilityName]?.get(name)
                if (!declared.isNullOrEmpty() && declared.none { sameType(annotatedType, it) }) {
                    val expected = declared.map { prettyPrintType(it) }.sorted().joinToString(" | ")
                    throw TypeCheckError(
                        "Type annotation for '$varName': `having '$name = $annotatedName` " +
                        "but no impl of ability '$abilityName' declares '$name = $annotatedName (declared: $expected).",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("change the `having` binding to one of: $expected, or drop it to let it be inferred"),
                    )
                }
            }
        }
    }

    /**
     * Annotation→type conversion for concrete `having` values (R2).
     *
     * THE shared converter, after a local copy of it lived here. The copy existed
     * because a merge was tried, measured and reverted — but it was pointed at
     * the WRONG entry point. `TypeAnnotationConverter` has two: `convert(...)`,
     * whose `ParsedNamedType` arm turns EVERY non-primitive name into a `VarType`,
     * and `convertWith(config)`, which splits on [String.isTypeVarName] and builds
     * a nominal `TypeApplication` for an uppercase name.
     *
     * Through `convert`, a declared `Color` arrived as a variable, the caller's
     * `annotatedType is VarType -> continue` skipped the check, and
     * `having 'out = Color` against an impl declaring `Str` was silently accepted
     * — `AbilityHavingParityTest.wrong variant-typed having on a generic
     * annotation is a type error` is what caught it. `convertWith` was right all
     * along, and with it the whole suite passes.
     *
     * The two tests the earlier attempt was blocked on — `CrossModuleFromJsonWrapperTest`'s
     * one-level wrapper and `StoredConstraintResolutionTest`'s inferred binding —
     * now pass too. They were failing on the hash-stamping and
     * representation issues this branch has since closed, not on anything this
     * conversion does.
     *
     * Public so the constraint generator can precompute impl-declared bindings.
     */
    fun publicAnnToMezze(annotation: ParsedTypeAnnotation): MezzeType =
        TypeAnnotationConverter.convertWith(annotation, TypeAnnotationConverter.ConversionConfig())

    private fun collectTypeVars(type: MezzeType): Set<TypeVar> {
        val result = mutableSetOf<TypeVar>()
        collectTypeVariables(type, result)
        return result
    }

    /**
     * True when [a] and [b] denote the same declared named type OR are
     * structurally equal. Needed for the 2-slot ability check where the body's
     * primary type-var resolves to a fully-expanded shape (e.g. `JsonC`'s inner
     * `shared` field expanded to its record body) while the annotation carries
     * the same name with lazy `TypeApplication` inner fields — both are the
     * same `JsonC` semantically but structural `==` differs.
     */
    private fun sameDeclaredOrEqual(a: MezzeType, b: MezzeType): Boolean {
        if (a == b) return true
        val aId = declaredIdentity(a)
        val bId = declaredIdentity(b)
        return aId != null && bId != null && aId == bId
    }

    private fun declaredIdentity(type: MezzeType): TypeIdentity.Declared? = when (type) {
        is MezzeType.TypeApplication -> type.identity as? TypeIdentity.Declared
        is MezzeType.RecordType -> type.identity as? TypeIdentity.Declared
        is MezzeType.VariantType -> type.identity as? TypeIdentity.Declared
        else -> null
    }

    private fun containsConcreteTypes(type: MezzeType): Boolean = when (type) {
        is MezzeType.VarType -> false
        is MezzeType.VariantType -> type.constructors.isNotEmpty() || type.anyChild { containsConcreteTypes(it) }
        else -> type.anyChild { containsConcreteTypes(it) } || !type.anyChild { true }
    }

    private fun resolveToRecordType(type: MezzeType): Pair<Set<String>, Boolean>? {
        when (type) {
            is MezzeType.RecordType -> {
                return Pair(type.fields.keys, type.rowVar != null)
            }
            is MezzeType.TypeApplication -> {
                val typeDecl = registry.typeDeclarations[type.constructor] ?: return null
                val definition = typeDecl.definition
                if (definition is ParsedRecordDef) {
                    val fieldNames = definition.fields.map { it.name }.toSet()
                    return Pair(fieldNames, definition.isOpen)
                }
                return null
            }
            else -> return null
        }
    }
}
