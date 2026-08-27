package com.mezze.language.parsedast

/**
 * Single source of truth for classifying `impl X for Y where { ... }` as either an
 * ability implementation or an effect handler.
 *
 * Ability impls and effect handlers share IDENTICAL syntax; nothing syntactic tells
 * them apart. The parser always parses `impl X for Y` as a [ParsedPerformerDecl]
 * (effect handler) via ordered choice, then a post-pass reclassifies the ones whose
 * `X` actually names a declared ABILITY (not an effect) into [ParsedImplAbilityDecl].
 *
 * That reclassification used to be duplicated in two places that drifted apart:
 *   1. the parser post-pass `resolveParsedImplDeclarations` (local decls only), and
 *   2. `ContextBuilder` (registry-complete: local + imported ability/effect names).
 * They disagreed on `forallVars` (`collectTypeVarNames` vs `emptyList`), which is
 * exactly what broke an earlier attempt to move this off the name-set. Both now
 * delegate here so the construction can never drift again.
 *
 * NOTE: this is name RESOLUTION, not name DISPATCH. At the point classification runs
 * there is no content hash yet (`ParsedPerformerDecl.contentHash` is populated by the
 * typechecker, after this). `impl X for Y` can only be disambiguated by "does `X`
 * bind to an ability declaration" — a name→declaration lookup, which is legitimate.
 * The two callers still supply their own name sets (parser: local; ContextBuilder:
 * local + imported); that scope difference is intentional, not the drift this fixes.
 */
object ImplClassifier {

    /**
     * True iff a performer's referenced name should be treated as an ABILITY impl
     * rather than an effect handler: it names a declared ability and is NOT also an
     * effect name (an effect name always wins — it stays a handler).
     */
    fun isAbilityImpl(refName: String, abilityNames: Set<String>, effectNames: Set<String>): Boolean =
        refName in abilityNames && refName !in effectNames

    /**
     * Build the [ParsedImplAbilityDecl] for a performer that was classified as an
     * ability impl. This is the ONE construction both callers share.
     *
     * @param forallVars the forall-introduced type vars for the impl. Supplied by the
     *   caller because the two historic call sites computed it differently; keeping it
     *   a parameter makes the extraction behaviour-preserving at each site. See #303.
     * @param stripEmptyRecordParams when true, an empty-record `paramType`/`returnType`
     *   (`{}`) on an op is treated as "unspecified" (null) so it is inferred from the
     *   ability declaration. ContextBuilder does this; the parser post-pass does not.
     * @param targetLoc location stamped on the synthesized target-type annotation. The
     *   two historic call sites used different locs (`effect.loc` vs the decl loc); it
     *   feeds the content hash, so it stays a parameter to keep extraction exact. #303.
     */
    fun performerToAbilityImpl(
        perfDecl: ParsedPerformerDecl,
        forallVars: List<String>,
        stripEmptyRecordParams: Boolean,
        targetLoc: Loc,
    ): ParsedImplAbilityDecl {
        fun normalizeParam(ann: ParsedTypeAnnotation?): ParsedTypeAnnotation? =
            if (stripEmptyRecordParams) ann?.takeUnless { it is ParsedRecordType && it.entries.isEmpty() }
            else ann

        return ParsedImplAbilityDecl(
            target = ParsedImplTarget.Named(perfDecl.effect),
            targetTypes = listOf(ParsedNamedType(perfDecl.performerName, targetLoc)),
            abilityTypeArgs = emptyList(),
            forallVars = forallVars,
            associatedTypeBindings = perfDecl.associatedTypeBindings,
            operations = perfDecl.operations.map { op ->
                ParsedImplOp(
                    name = op.name,
                    paramType = normalizeParam(op.paramType),
                    returnType = normalizeParam(op.returnType),
                    implementation = op.implementation,
                    abilityConstraints = emptyList(),
                    loc = op.loc
                )
            },
            loc = perfDecl.loc,
        )
    }
}
