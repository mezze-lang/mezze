package com.mezze.language.typechecker.util

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.parsedast.diagnostics.LevenshteinSuggest
import com.mezze.language.store.DeclKind
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typedast.Hash

object TypeAnnotationConverter {

    data class ConversionConfig(
        val resolveTypeHash: (String) -> Hash? = { null },
        val resolveAbilityHash: (String) -> Hash? = { null },
        val validateEffects: ((ParsedEffectRow) -> Unit)? = null,
        val resolveEffectHashes: (Set<String>) -> Map<String, Hash> = { emptyMap() },
        val honorOpenRowVars: Boolean = false,
        val inlineVariantNaming: Boolean = false,
        val typeParamSubst: Map<String, MezzeType>? = null,
        val convertEffectAnnotations: Boolean = false,
        val stripTickFromTypeVars: Boolean = false,
        /**
         * Transparent-alias resolver. Given a type name, returns the aliased
         * `ParsedTypeAnnotation` PLUS the alias's declared type parameter
         * names iff the name refers to a `type X 'a ... = ...` declaration
         * (NOT a `newtype`, NOT a record/variant alias). The converter
         * inlines the aliased annotation at every use site — with type-var
         * substitution when the alias was declared with parameters — so
         * `MyInt` and `Int`, or `Wrap Int` and `Seq Int`, unify without the
         * solver ever seeing the alias label. Return `null` for anything
         * that isn't a transparent alias.
         */
        val resolveTypeAlias: (String) -> Pair<ParsedTypeAnnotation, List<String>>? = { null },
        /**
         * Associated-type parameter names an effect declares (e.g. `Exception`
         * declares `['error]`). Used to synthesize a fresh-var EffectInstance
         * for an effect written WITHOUT a `having` clause: omitting `having` is
         * allowed by design, but the associated type must still be inferred, not
         * left unresolved. A row that carries no instance can never receive the
         * body's solved `'error` (unifyEffectRows only unifies associated types
         * on effects instanced on BOTH sides), so the declared `<Exception>`
         * would silently drop it and callers would see `Result Int 'a`. Seeding
         * a `having 'error = 'fresh` slot lets unification thread the body's
         * concrete type through. Empty list → no associated types → no seed.
         */
        val associatedTypesForEffect: (String) -> List<String> = { emptyList() },
    )

    fun convertWith(
        annotation: ParsedTypeAnnotation,
        config: ConversionConfig,
        typeVarCache: MutableMap<String, TypeVar> = mutableMapOf(),
        sharedRowVar: SharedRowVar = SharedRowVar()
    ): MezzeType {
        return convertInternal(annotation, config, typeVarCache, sharedRowVar, mutableSetOf())
    }

    /**
     * The config for any caller holding a [DeclarationRegistry] — which is what
     * "convert this annotation, resolving names against the module I am checking"
     * means.
     *
     * Six call sites had hand-written the same `resolveTypeHash` lambda, and they
     * had drifted: some resolved via `typeDeclarations`, some via `lookupType`,
     * some only consulted the precomputed table, and the ones that could not
     * find a hash silently produced a NULL one — which
     * is not a missing optimisation but weaker type identity, the exact failure
     * where two same-named types from different modules stop being distinguishable.
     *
     * Both hash lookups below are kept because each covers a case the other
     * misses, not as a fallback for failure: `lookupType` sees the decl (and can
     * hash it on demand) for a type declared in this module, while
     * `typeHashFor`'s table also carries types known only by hash. A name that is
     * neither is a builtin or a primitive, which legitimately has no decl hash.
     */
    fun registryConfig(
        registry: DeclarationRegistry,
        typeParamSubst: Map<String, MezzeType>? = null,
        stripTickFromTypeVars: Boolean = false,
    ): ConversionConfig = ConversionConfig(
        resolveTypeHash = { name ->
            registry.lookupType(name)?.let { d -> d.identity }
                ?: registry.typeHashFor(name)
        },
        resolveAbilityHash = { name ->
            registry.lookupAbility(name)?.contentHash ?: registry.resolveAbilityHash(name)
        },
        resolveEffectHashes = { names ->
            names.mapNotNull { name -> registry.resolveEffectHash(name)?.let { name to it } }.toMap()
        },
        // Both ON, because both were being dropped. An open record is a DIFFERENT
        // type from a closed one, and a `having` clause is part of what an effect
        // row says — converting neither is what let them go missing silently.
        honorOpenRowVars = true,
        convertEffectAnnotations = true,
        typeParamSubst = typeParamSubst,
        stripTickFromTypeVars = stripTickFromTypeVars,
        // Deliberately NO `resolveTypeAlias`. Inlining a transparent alias is for
        // the paths that want `MyInt` and `Int` to UNIFY; an impl is attached to a
        // declared type, so the ability and emit paths must see the type as
        // written or the impl stops being findable. Enabling it here selected the
        // wrong impl for container types — `boxEq`/`pairEq` compared unequal,
        // a decode returned a default instead of its value, and a map decode
        // crashed on the wrong callee. A caller that wants inlining passes its own.
        associatedTypesForEffect = { name ->
            registry.lookupEffect(name)?.associatedTypes ?: emptyList()
        },
    )

    /**
     * Convert the annotations that make up ONE signature, sharing a single
     * variable scope across them.
     *
     * [convertWith] defaults to a FRESH `typeVarCache` per call, which is right
     * for two unrelated annotations and wrong for two halves of the same one. A
     * parameter list is the second case: the `'a` in `{ x: 'a, y: 'a }` is ONE
     * variable, and converting each field separately made it two — so the checker
     * never required `x` and `y` to agree, and
     *
     *     let same = { x: 'a, y: 'a } -> x
     *     same { x = 1, y = "two" }
     *
     * typechecked and ran. The same signature written as a full arrow
     * (`let same: { x: 'a, y: 'a } -> 'a = …`) was correctly rejected, because
     * that path converts the whole arrow in one scope.
     *
     * Three sites built parameter records the split way — constraint generation
     * for a lambda, the binding-level generator, and the typing context — which
     * is why this lives here rather than being written a fourth time.
     */
    fun <K> convertSignature(
        entries: List<Pair<K, ParsedTypeAnnotation>>,
        config: ConversionConfig,
    ): List<Pair<K, MezzeType>> {
        val typeVarCache = mutableMapOf<String, TypeVar>()
        val sharedRowVar = SharedRowVar()
        return entries.map { (key, annotation) ->
            key to convertInternal(annotation, config, typeVarCache, sharedRowVar, mutableSetOf())
        }
    }

    private fun convertInternal(
        annotation: ParsedTypeAnnotation,
        config: ConversionConfig,
        typeVarCache: MutableMap<String, TypeVar>,
        sharedRowVar: SharedRowVar,
        recursionGuard: MutableSet<String>,
        /**
         * Each alias's expansion, converted once per conversion and shared. The
         * cycle guard alone stops a name repeating on ONE path, so a family of
         * mutually recursive aliases — `std::syntax`'s `Expr`, `Arm`, `Pattern`,
         * `TypeExpr`… — was expanded once per PATH: exponential, enough to run a
         * test out of memory. Only an expansion with no variable in it is shared;
         * one with a type or row variable is converted afresh each time, as each
         * occurrence must have its own.
         */
        aliasMemo: AliasMemo = AliasMemo(),
    ): MezzeType {
        fun recurse(ann: ParsedTypeAnnotation) = convertInternal(ann, config, typeVarCache, sharedRowVar, recursionGuard, aliasMemo)

        return when (annotation) {
            is ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                val name = annotation.name
                if (name.isTypeVarName()) {
                    val varName = if (config.stripTickFromTypeVars) name.removePrefix("'") else name
                    config.typeParamSubst?.let { subst ->
                        subst[varName] ?: subst[name.removePrefix("'")] ?: subst["'" + name.removePrefix("'")]
                    }?.let { return it }
                    val typeVar = typeVarCache.getOrPut(varName) { TypeVar.freshWithName(varName) }
                    MezzeType.VarType(typeVar)
                } else {
                    // Transparent alias inlining. Recursive aliases fall
                    // through to labeled TypeApp so the recursion terminates.
                    // Stamp the alias's own nominal identity (name + hash) on
                    // the resolved shape so unification and error messages can
                    // key on the decl's identity. `ascriptionWall` is CLEARED:
                    // the wall is an author-written ascription concern, not a
                    // decl one.
                    if (name !in recursionGuard) {
                        aliasMemo.expansions[name]?.let { return it }
                        config.resolveTypeAlias(name)?.let { (aliased, _) ->
                            recursionGuard.add(name)
                            try {
                                // Displayed by its declared name: a quote's hidden import
                                // (`~Expr`) is a scoping device, never what a type is called.
                                return withAliasIdentity(recurse(aliased), com.mezze.language.parsedast.QuoteTypes.declaredName(name), config.resolveTypeHash(name), emptyList())
                                    .also { if (aliasMemo.isGround(it)) aliasMemo.expansions[name] = it }
                            } finally { recursionGuard.remove(name) }
                        }
                    }
                    WellKnownTypeApp.of(name, emptyList(), config.resolveTypeHash(name))
                }
            }

            is ParsedFunctionType -> {
                val effectRow = annotation.effects?.let { parsedRow ->
                    // A row stamped with what its names meant where it was written
                    // (a type declaration, possibly imported) is resolved there, not
                    // here. Only the names it does not cover are this scope's to check.
                    val stamped = parsedRow.resolved.orEmpty()
                    config.validateEffects?.invoke(parsedRow.copy(
                        effects = parsedRow.effects.filter { it !in stamped },
                        effectAnnotations = parsedRow.effectAnnotations.filter { it.name !in stamped },
                    ))
                    val rowVar = if (parsedRow.isPolymorphic) {
                        sharedRowVar.getOrCreate()
                    } else null
                    val hashes = config.resolveEffectHashes(parsedRow.effects.filter { it !in stamped }.toSet()) +
                        stamped.filterKeys { it in parsedRow.effects }
                    if (config.convertEffectAnnotations) {
                        // Explicit `having` clauses first.
                        val annotated = parsedRow.effectAnnotations.map { ea ->
                            MezzeType.EffectInstance(
                                name = ea.name,
                                associatedTypes = ea.associatedTypes.mapValues { (_, ta) -> recurse(ta) }
                            )
                        }
                        val explicitlyInstanced = annotated.map { it.name }.toSet()
                        // Effects written WITHOUT `having` that nonetheless DECLARE
                        // associated types: seed a fresh var per associated type so
                        // the body's solved type (e.g. `Str` from a `throw`) can be
                        // unified in and threaded to callers. Omission is allowed;
                        // under-determination is not. Without this seed the declared
                        // row carries no instance, unifyEffectRows finds no common
                        // instanced effect, and the associated type leaks as `'a`.
                        val synthesized = parsedRow.effects
                            .filter { it !in explicitlyInstanced }
                            .mapNotNull { name ->
                                val assocNames = config.associatedTypesForEffect(name)
                                if (assocNames.isEmpty()) null
                                else MezzeType.EffectInstance(
                                    name = name,
                                    associatedTypes = assocNames.associate { assoc ->
                                        val normalized = assoc.removePrefix("'")
                                        normalized to MezzeType.VarType(TypeVar.fresh())
                                    }
                                )
                            }
                        val effectInstances = (annotated + synthesized).toSet()
                        if (effectInstances.isEmpty()) {
                            MezzeType.EffectRow.simple(parsedRow.effects.toSet(), rowVar, parsedRow.isPerformed, hashes)
                        } else {
                            MezzeType.EffectRow(
                                effects = parsedRow.effects.toSet(),
                                effectInstances = effectInstances,
                                rowVar = rowVar,
                                performed = parsedRow.isPerformed,
                                effectHashes = hashes
                            )
                        }
                    } else {
                        MezzeType.EffectRow.simple(parsedRow.effects.toSet(), rowVar, parsedRow.isPerformed, hashes)
                    }
                }
                val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { recurse(it) },
                        associatedTypeBindings = constraint.associatedTypeBindings.mapValues { (_, ta) -> recurse(ta) },
                        abilityHash = config.resolveAbilityHash(constraint.abilityName)
                    )
                }
                val convertedParam = recurse(annotation.paramType)
                val convertedReturn = recurse(annotation.returnType)
                // Row subtraction on nested callback effect rows:
                // Effects declared on a callback's row that DO NOT appear
                // on this function's return row are handled by this
                // function. Mark them on the callback's row so unification
                // absorbs them without propagating through the shared row
                // var. Example: `spawn : {f: {} -> 'a <Exception, ...>} ->
                // Promise 'a <Async, ...>` — Exception is on the callback
                // row but not the return, so it's caught by spawn.
                val outerReturnEffectNames = effectRow?.allEffectNames ?: emptySet()
                val paramWithCaughtStamped = stampCaughtOnCallbackRows(convertedParam, outerReturnEffectNames)
                MezzeType.FunctionType(
                    paramType = paramWithCaughtStamped,
                    returnType = convertedReturn,
                    effects = effectRow,
                    abilityConstraints = abilityConstraints
                )
            }

            is ParsedRecordType -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { e -> e.name to recurse(e.type) },
                    rowVar = if (config.honorOpenRowVars && annotation.isOpen) RowVar.userOpen() else null
                )
            }

            is ParsedVariantType -> {
                // An author-written closed inline variant (`{c: |Red, Green|}`)
                // is a hard wall — reject foreign ctors regardless of scope.
                // That's what `ascriptionWall=true` tracks. Author-written OPEN
                // variants (`|Red, Green, ...|`) are structural, no wall.
                // `typeName` stays null: anonymous inline variants have no
                // nominal identity to display; the printer renders their
                // arm-set literally.
                val isClosedInlineWall = config.inlineVariantNaming && !annotation.isOpen
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { recurse(it) }
                    },
                    rowVar = if (config.honorOpenRowVars && annotation.isOpen) RowVar.userOpen() else null,
                    ascriptionWall = isClosedInlineWall,
                )
            }

            is ParsedTypeApp -> {
                // Parametric transparent alias with cycle-guard.
                if (annotation.constructor !in recursionGuard) {
                    config.resolveTypeAlias(annotation.constructor)?.let { (aliased, paramNames) ->
                        val convertedArgs = annotation.args.map { recurse(it) }
                        val substituted = paramNames.zip(convertedArgs).toMap()
                        val extendedSubst = (config.typeParamSubst ?: emptyMap()) + substituted +
                            substituted.mapKeys { "'" + it.key.removePrefix("'") }
                        val extendedConfig = config.copy(typeParamSubst = extendedSubst)
                        recursionGuard.add(annotation.constructor)
                        try {
                            return withAliasIdentity(
                                convertInternal(aliased, extendedConfig, typeVarCache, sharedRowVar, recursionGuard, aliasMemo),
                                annotation.constructor, config.resolveTypeHash(annotation.constructor),
                                convertedArgs,
                            )
                        } finally { recursionGuard.remove(annotation.constructor) }
                    }
                }
                // B.1 — every builtin container converts to the SAME form as every
                // other applied type, through THE canonical factory. Each used to
                // convert to a dedicated MezzeType node as well, so a producer
                // picked one form and a pass handed the other silently did nothing
                // — measured three times independently (containerElementType,
                // harvestVarBindings, mono's elementVarName, the last of which is
                // why `List P == List P` compared structurally while `[P] == [P]`
                // did not).
                //
                // A `resolveCollectionTypes` flag used to select between two
                // branches here. After that unification the branches were
                // identical, so the flag read as a decision while changing
                // nothing; one caller set it false believing it did.
                WellKnownTypeApp.of(
                    constructor = annotation.constructor,
                    args = annotation.args.map { recurse(it) },
                    typeHash = config.resolveTypeHash(annotation.constructor),
                )
            }

            is ParsedSeqType -> com.mezze.language.ast.BuiltinTypes.seqApp(recurse(annotation.elementType))

            is ParsedQualifiedType -> {
                val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
                WellKnownTypeApp.of(
                    constructor = qualifiedName,
                    args = annotation.args.map { recurse(it) },
                    typeHash = config.resolveTypeHash(qualifiedName)
                )
            }
        }
    }

    /**
     * The convenience form: convert one annotation, resolving names with the
     * lambdas the caller has to hand.
     *
     * This WAS a second full walker living beside [convertInternal] in this same
     * object, and the two had diverged in ways that made the choice between them
     * matter without saying so:
     *
     *  - `ParsedQualifiedType` — `a::b::T` — became `VarType(fresh())`. A module-
     *    qualified type converted to a variable that unifies with ANYTHING, so
     *    every check reading it was vacuous.
     *  - Records were always CLOSED and variants always `rowVar = null`,
     *    discarding author-written `...`; [convertInternal] honours it.
     *  - Effect rows were built with a new row var each, rather than the ONE
     *    shared row var a signature's rows must share.
     *  - `faithful = false` additionally turned variants and applied types into
     *    fresh variables. One caller used it — the module EXPORT path, whose
     *    whole job is telling other modules what a signature is.
     *  - A null `typeVarCache` (the default, so 25 of 31 call sites) made every
     *    OCCURRENCE of a name its own variable, so `{x: 'a} -> 'a` converted to
     *    `{x: 'b} -> 'c` and stopped constraining anything. That is this
     *    codebase's most-repeated bug, found five times before this one.
     *
     * None of that was a decision anyone made; it was a copy that fell behind.
     * There is now ONE walker, and this is a thin adapter onto it.
     */
    fun convert(
        annotation: ParsedTypeAnnotation,
        // Pass a cache to SHARE variables across several annotations of one
        // signature; the default shares within this call, which is the minimum
        // required for a single annotation to mean what it says.
        typeVarCache: MutableMap<String, TypeVar>? = null,
        resolveTypeAlias: (String) -> Pair<ParsedTypeAnnotation, List<String>>? = { null },
        resolveTypeHash: (String) -> Hash? = { null },
        resolveAbilityHash: (String) -> Hash? = { WellKnownHashConstants.resolveAbilityHash(it) },
    ): MezzeType = convertWith(
        annotation,
        ConversionConfig(
            resolveTypeHash = resolveTypeHash,
            resolveAbilityHash = resolveAbilityHash,
            resolveTypeAlias = resolveTypeAlias,
            honorOpenRowVars = true,
            convertEffectAnnotations = true,
        ),
        typeVarCache = typeVarCache ?: mutableMapOf(),
    )

    /** See `aliasMemo` on [convertInternal]. */
    class AliasMemo {
        val expansions = HashMap<String, MezzeType>()
        private val ground = java.util.IdentityHashMap<MezzeType, Boolean>()

        /** No type variable and no open row anywhere in [t]; memoized by object, as [t] shares subtrees. */
        fun isGround(t: MezzeType): Boolean = ground[t] ?: run {
            val own = when (t) {
                is MezzeType.VarType -> false
                is MezzeType.RecordType -> t.rowVar == null
                is MezzeType.VariantType -> t.rowVar == null
                is MezzeType.FunctionType -> t.effects?.rowVar == null
                is MezzeType.EffectRow -> t.rowVar == null
                else -> true
            }
            (own && !t.anyChild { !isGround(it) }).also { ground[t] = it }
        }
    }

    class SharedRowVar {
        private var rowVar: RowVar? = null
        // #274-B: user-written `<...>` must survive closeGeneratedRowVars
        // so callers of an annotated binding see the polymorphic
        // signature. Use userOpen() rather than fresh().
        fun getOrCreate(): RowVar = rowVar ?: RowVar.userOpen().also { rowVar = it }
        fun reset() { rowVar = null }
    }

    fun validateUnknownEffects(
        effectRow: ParsedEffectRow,
        knownEffects: Set<String>,
    ) {
        ContextBuilder.validateHermeticEffects(effectRow.allEffectNames)
        val unknownEffects = (effectRow.effects + effectRow.effectAnnotations.map { it.name })
            .filter { it !in knownEffects }
        if (unknownEffects.isEmpty()) return

        val name = unknownEffects.first()
        val suggestion = LevenshteinSuggest.closest(name, knownEffects)
        val didYouMean = suggestion?.let { ". Did you mean `$it`?" } ?: ""

        // Catch STAYS: we are already building the "effect not defined" diagnostic,
        // and a store failure while looking for `use` suggestions must not replace
        // it. Empty means "no module to suggest", which is a real answer.
        val candidateModules: List<String> = try {
            val store = PrecompiledStdlib.getStore()
            store?.getDeclsByKind(DeclKind.EFFECT)
                ?.filter { it.declName == name }
                ?.map { it.modulePath }
                ?.distinct()
                ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val hints = if (candidateModules.isNotEmpty()) {
            candidateModules.take(3).map { "import it: `use ${it}::{$name}`" }
        } else listOf(
            "declare `effect $name { ... }` in this module, or",
            "import it: `use some_module::$name`",
        )

        val fixHints = buildList {
            if (suggestion != null) {
                add(FixHint.Rename(effectRow.loc, listOf(suggestion)))
            }
            if (candidateModules.isNotEmpty()) {
                add(FixHint.Import(effectRow.loc, name, candidateModules))
            }
        }

        throw TypeCheckError(
            "Effect `$name` is not defined or not imported$didYouMean",
            effectRow.loc,
            code = ErrorCode.V0505_EFFECT_NOT_DEFINED,
            hints = hints,
            fixHints = fixHints,
        )
    }

    fun stampCaughtOnCallbackRowsPublic(
        type: MezzeType,
        outerReturnEffectNames: Set<String>
    ): MezzeType = stampCaughtOnCallbackRows(type, outerReturnEffectNames)

    /**
     * Collect ALL `caughtByAncestor` effect names accumulated on any
     * EffectRow reachable from `type` (used to pick up the union of
     * caught effects from all nested callback rows).
     */
    fun collectCaughtByAncestorFromType(type: MezzeType): Set<String> {
        val out = HashSet<String>()
        collectCaughtByAncestorInto(type, out)
        return out
    }

    /**
     * Union every `caughtByAncestor` set reachable from [type] into [into].
     * Shared single source of truth — [com.mezze.language.typechecker.constraints]
     * calls this too (was a byte-identical copy). A pure MezzeType walk: it reads
     * only EffectRow/FunctionType-effects `caughtByAncestor`, so it belongs with the
     * other effect-row helpers here rather than being duplicated per caller.
     */
    internal fun collectCaughtByAncestorInto(type: MezzeType, into: MutableSet<String>) {
        when (type) {
            is MezzeType.FunctionType -> {
                type.effects?.let { into.addAll(it.caughtByAncestor) }
                collectCaughtByAncestorInto(type.paramType, into)
                collectCaughtByAncestorInto(type.returnType, into)
            }
            is MezzeType.RecordType -> type.fields.values.forEach { collectCaughtByAncestorInto(it, into) }
            is MezzeType.VariantType -> type.constructors.values.forEach { it?.let { collectCaughtByAncestorInto(it, into) } }
            is MezzeType.TypeApplication -> type.args.forEach { collectCaughtByAncestorInto(it, into) }
            is MezzeType.NewtypeType -> collectCaughtByAncestorInto(type.wrappedType, into)
            is MezzeType.EffectRow -> into.addAll(type.caughtByAncestor)
            is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.FracType, is MezzeType.BytesType,
            is MezzeType.CharType -> {}
        }
    }

    /**
     * Stamp `caughtByAncestor` on ALL EffectRows reachable from `type`.
     * Used on the return type of an op to propagate caught info to any
     * nested effect rows (e.g., when the return is a function type with
     * its own effect row that shares the outer row var).
     */
    fun stampCaughtOnAllEffectRowsPublic(type: MezzeType, caught: Set<String>): MezzeType =
        stampCaughtOnAllEffectRows(type, caught)

    private fun stampCaughtOnAllEffectRows(type: MezzeType, caught: Set<String>): MezzeType {
        if (caught.isEmpty()) return type
        return when (type) {
            is MezzeType.FunctionType -> type.copy(
                paramType = stampCaughtOnAllEffectRows(type.paramType, caught),
                returnType = stampCaughtOnAllEffectRows(type.returnType, caught),
                effects = type.effects?.copy(caughtByAncestor = type.effects!!.caughtByAncestor + caught)
            )
            is MezzeType.RecordType -> type.copy(
                fields = type.fields.mapValues { (_, v) -> stampCaughtOnAllEffectRows(v, caught) }
            )
            is MezzeType.VariantType -> type.copy(
                constructors = type.constructors.mapValues { (_, v) -> v?.let { stampCaughtOnAllEffectRows(it, caught) } }
            )
            is MezzeType.TypeApplication -> type.copy(args = type.args.map { stampCaughtOnAllEffectRows(it, caught) })
            is MezzeType.NewtypeType -> type.copy(wrappedType = stampCaughtOnAllEffectRows(type.wrappedType, caught))
            is MezzeType.EffectRow -> type.copy(caughtByAncestor = type.caughtByAncestor + caught)
            is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.FracType, is MezzeType.BytesType,
            is MezzeType.CharType -> type
        }
    }

    /**
     * Walk a MezzeType looking for nested FunctionTypes and stamp their
     * effect rows with `caughtByAncestor` = (callback effects − outer
     * return effects). This encodes "effects declared on the callback
     * but not on the return are handled here" so unification can absorb
     * them without propagating through the shared row var.
     */
    private fun stampCaughtOnCallbackRows(
        type: MezzeType,
        outerReturnEffectNames: Set<String>
    ): MezzeType = when (type) {
        is MezzeType.FunctionType -> {
            val cbEffects = type.effects
            val newCbEffects = if (cbEffects != null) {
                val extras = cbEffects.allEffectNames - outerReturnEffectNames
                if (extras.isNotEmpty()) {
                    cbEffects.copy(caughtByAncestor = cbEffects.caughtByAncestor + extras)
                } else cbEffects
            } else null
            // Recurse into this nested function's own params and returns
            // in case there are further nested callbacks.
            val newReturnEffectNames = newCbEffects?.allEffectNames ?: emptySet()
            type.copy(
                paramType = stampCaughtOnCallbackRows(type.paramType, newReturnEffectNames),
                returnType = stampCaughtOnCallbackRows(type.returnType, outerReturnEffectNames),
                effects = newCbEffects
            )
        }
        is MezzeType.RecordType -> type.copy(
            fields = type.fields.mapValues { (_, v) -> stampCaughtOnCallbackRows(v, outerReturnEffectNames) }
        )
        is MezzeType.VariantType -> type.copy(
            constructors = type.constructors.mapValues { (_, v) -> v?.let { stampCaughtOnCallbackRows(it, outerReturnEffectNames) } }
        )
        is MezzeType.TypeApplication -> type.copy(
            args = type.args.map { stampCaughtOnCallbackRows(it, outerReturnEffectNames) }
        )
        is MezzeType.NewtypeType -> type.copy(wrappedType = stampCaughtOnCallbackRows(type.wrappedType, outerReturnEffectNames))
        is MezzeType.EffectRow, is MezzeType.VarType,
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.FracType,
        is MezzeType.BytesType,
        is MezzeType.CharType -> type
    }

    /**
     * Substitute type variables in a ParsedTypeAnnotation. Used by the
     * parametric-alias path in [convert]: given `type Wrap 'a = Seq 'a`
     * used as `Wrap Int`, we rewrite the aliased annotation `Seq 'a` to
     * `Seq Int` by substituting `'a → Int`, then recurse into the plain
     * converter. Keeps the substitution parsed-AST-only so downstream
     * conversion semantics are unaffected.
     */
    private fun substituteTypeVarsInAnnotation(
        annotation: ParsedTypeAnnotation,
        subst: Map<String, ParsedTypeAnnotation>
    ): ParsedTypeAnnotation = annotation.substituteTypeVarNames(subst)

    /**
     * When `type X = <shape>` is inlined at a use site, stamp X's own nominal
     * identity (name + content hash + type-arg vector for parametric aliases)
     * on the resolved variant/record. `ascriptionWall` is CLEARED — the wall
     * is an author-written-ascription concern, not something a decl brings.
     * Non-shape carriers (Int, Function, TypeApplication, Seq, …) pass
     * through unchanged: they either don't need labeling (primitives) or
     * already carry their own identity slot.
     */
    private fun withAliasIdentity(
        resolved: MezzeType,
        aliasName: String,
        aliasHash: Hash?,
        typeArgs: List<MezzeType>,
    ): MezzeType {
        val aliasIdentity = run {
            val hash = aliasHash ?: (resolved as? MezzeType.VariantType)?.identity
                ?.let { it as? TypeIdentity.Declared }?.hash
                ?: ((resolved as? MezzeType.RecordType)?.identity as? TypeIdentity.Declared)?.hash
            TypeIdentity.derive(aliasName, hash, allowBuiltin = false)
        }
        return when (resolved) {
            is MezzeType.VariantType -> resolved.copy(
                identity = aliasIdentity,
                typeArgs = typeArgs,
                ascriptionWall = false,
            )
            is MezzeType.RecordType -> resolved.copy(
                identity = aliasIdentity,
            )
            else -> resolved
        }
    }
}
