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
import com.mezze.language.typedast.Hash

object TypeAnnotationConverter {

    data class ConversionConfig(
        val resolveTypeHash: (String) -> Hash? = { null },
        val resolveAbilityHash: (String) -> Hash? = { null },
        val validateEffects: ((ParsedEffectRow) -> Unit)? = null,
        val resolveEffectHashes: (Set<String>) -> Map<String, Hash> = { emptyMap() },
        val resolveCollectionTypes: Boolean = true,
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
    ): MezzeType {
        fun recurse(ann: ParsedTypeAnnotation) = convertInternal(ann, config, typeVarCache, sharedRowVar, recursionGuard)

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
                        config.resolveTypeAlias(name)?.let { (aliased, _) ->
                            recursionGuard.add(name)
                            try {
                                return withAliasIdentity(recurse(aliased), name, config.resolveTypeHash(name), emptyList())
                            } finally { recursionGuard.remove(name) }
                        }
                    }
                    WellKnownTypeApp.of(name, emptyList(), config.resolveTypeHash(name))
                }
            }

            is ParsedFunctionType -> {
                val effectRow = annotation.effects?.let { parsedRow ->
                    config.validateEffects?.invoke(parsedRow)
                    val rowVar = if (parsedRow.isPolymorphic) {
                        sharedRowVar.getOrCreate()
                    } else null
                    val hashes = config.resolveEffectHashes(parsedRow.effects.toSet())
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
                                convertInternal(aliased, extendedConfig, typeVarCache, sharedRowVar, recursionGuard),
                                annotation.constructor, config.resolveTypeHash(annotation.constructor),
                                convertedArgs,
                            )
                        } finally { recursionGuard.remove(annotation.constructor) }
                    }
                }
                val args = annotation.args.map { recurse(it) }
                if (config.resolveCollectionTypes) {
                    resolveWellKnownTypeApp(annotation.constructor, args, config.resolveTypeHash(annotation.constructor))
                } else {
                    WellKnownTypeApp.of(
                        constructor = annotation.constructor,
                        args = args,
                        typeHash = config.resolveTypeHash(annotation.constructor)
                    )
                }
            }

            is ParsedSeqType -> com.mezze.language.ast.BuiltinTypes.seqApp(recurse(annotation.elementType))

            is ParsedEffectRow -> MezzeType.EffectRow(annotation.effects.toSet())

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

        fun convert(
        annotation: ParsedTypeAnnotation,
        faithful: Boolean = true,
        includeAbilityConstraints: Boolean = true,
        typeVarCache: MutableMap<String, TypeVar>? = null,
        resolveTypeAlias: (String) -> Pair<ParsedTypeAnnotation, List<String>>? = { null },
        recursionGuard: MutableSet<String> = mutableSetOf(),
        // Resolve a declared type's content hash so faithful TypeApplications carry
        // real nominal identity (Declared), never Named.
        resolveTypeHash: (String) -> com.mezze.language.typedast.Hash? = { null },
        // WHICH ability a `with <Name> 'a` names, in the MODULE being converted.
        //
        // The default is the generated stdlib table, which maps a NAME to a fixed
        // hash and cannot see a local declaration. A module that declares its own
        // `ability Ord` therefore had its annotation stamped with the STDLIB's Ord
        // while the inferred side carried the local one — one ability, two
        // identities, in a single comparison. A caller holding a registry should
        // pass it, so the local declaration shadows the prelude as it does
        // everywhere else.
        resolveAbilityHash: (String) -> com.mezze.language.typedast.Hash? =
            { WellKnownHashConstants.resolveAbilityHash(it) },
    ): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                if (annotation.name !in recursionGuard) {
                    resolveTypeAlias(annotation.name)?.let { (aliased, _) ->
                        recursionGuard.add(annotation.name)
                        try {
                            return convert(aliased, faithful, includeAbilityConstraints, typeVarCache, resolveTypeAlias, recursionGuard, resolveTypeHash, resolveAbilityHash)
                        } finally { recursionGuard.remove(annotation.name) }
                    }
                }
                if (faithful) {
                    MezzeType.VarType(
                        if (typeVarCache != null) {
                            typeVarCache.getOrPut(annotation.name) { TypeVar.freshWithName(annotation.name) }
                        } else {
                            TypeVar.freshWithName(annotation.name)
                        }
                    )
                } else {
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
            is ParsedFunctionType -> {
                val effectRow = annotation.effects?.let { row ->
                    val rowVar = if (row.isPolymorphic) RowVar.userOpen() else null
                    MezzeType.EffectRow.simple(row.effects.toSet(), rowVar, row.isPerformed)
                }
                val abilityConstraints = if (includeAbilityConstraints) {
                    annotation.abilityConstraints.map { c ->
                        MezzeType.FunctionAbilityConstraint(
                            abilityName = c.abilityName,
                            typeArgs = c.typeArgs.map { convert(it, faithful, includeAbilityConstraints, typeVarCache, resolveTypeAlias, recursionGuard, resolveTypeHash, resolveAbilityHash) },
                            abilityHash = resolveAbilityHash(c.abilityName)
                        )
                    }
                } else {
                    emptyList()
                }
                MezzeType.FunctionType(
                    paramType = convert(annotation.paramType, faithful, includeAbilityConstraints, typeVarCache, resolveTypeAlias, recursionGuard, resolveTypeHash, resolveAbilityHash),
                    returnType = convert(annotation.returnType, faithful, includeAbilityConstraints, typeVarCache, resolveTypeAlias, recursionGuard, resolveTypeHash, resolveAbilityHash),
                    effects = effectRow,
                    abilityConstraints = abilityConstraints
                )
            }
            is ParsedRecordType -> {
                MezzeType.RecordType(annotation.entries.associate { e ->
                    e.name to convert(e.type, faithful, includeAbilityConstraints, typeVarCache, resolveTypeAlias, recursionGuard, resolveTypeHash)
                })
            }
            is ParsedSeqType -> com.mezze.language.ast.BuiltinTypes.seqApp(convert(annotation.elementType, faithful, includeAbilityConstraints, typeVarCache, resolveTypeAlias, recursionGuard, resolveTypeHash))
            is ParsedVariantType -> {
                if (faithful) {
                    MezzeType.VariantType(
                        constructors = annotation.constructors.associate {
                            it.name to it.dataType?.let { dt -> convert(dt, faithful, includeAbilityConstraints, typeVarCache, resolveTypeAlias, recursionGuard, resolveTypeHash) }
                        },
                        rowVar = null
                    )
                } else {
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
            is ParsedTypeApp -> {
                // Parametric transparent alias with cycle-guard.
                if (annotation.constructor !in recursionGuard) {
                    resolveTypeAlias(annotation.constructor)?.let { (aliased, paramNames) ->
                        val subst = paramNames.zip(annotation.args).toMap()
                        val rewritten = substituteTypeVarsInAnnotation(aliased, subst)
                        recursionGuard.add(annotation.constructor)
                        try {
                            return convert(rewritten, faithful, includeAbilityConstraints, typeVarCache, resolveTypeAlias, recursionGuard, resolveTypeHash)
                        } finally { recursionGuard.remove(annotation.constructor) }
                    }
                }
                if (faithful) {
                    val ctorHash = resolveTypeHash(annotation.constructor)
                    // S3.1: identity via THE canonical factory. A name that resolves to
                    // no declaration/builtin becomes Anonymous — valid code never reaches
                    // that (unknown-type validation rejects it upstream).
                    val ctorIdentity = TypeIdentity.derive(annotation.constructor, ctorHash, allowBuiltin = true)
                    MezzeType.TypeApplication(
                        annotation.constructor,
                        annotation.args.map { convert(it, faithful, includeAbilityConstraints, typeVarCache, resolveTypeAlias, recursionGuard, resolveTypeHash, resolveAbilityHash) },
                        identity = ctorIdentity
                    )
                } else {
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
            is ParsedQualifiedType -> {
                MezzeType.VarType(TypeVar.fresh())
            }
        }
    }

    private fun resolveWellKnownTypeApp(constructor: String, args: List<MezzeType>, typeHash: Hash? = null): MezzeType {
        // B.1 — every builtin container converts to the SAME form as every other
        // applied type. It used to convert to a dedicated MezzeType node instead,
        // which is how each of these came to exist twice: once as its own node and
        // once as a TypeApplication carrying the same name. Consumers are
        // two-sided, but a PRODUCER picks one, and a pass handed the other
        // silently does nothing — measured three times independently
        // (containerElementType, harvestVarBindings, mono's elementVarName, the
        // last of which is why `List P == List P` compared structurally while
        // `[P] == [P]` did not).
        return WellKnownTypeApp.of(constructor, args, typeHash)
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
            is MezzeType.StringType, is MezzeType.BoolType,
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
            is MezzeType.StringType, is MezzeType.BoolType,
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
        is MezzeType.BoolType, is MezzeType.FracType,
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
    ): ParsedTypeAnnotation {
        if (subst.isEmpty()) return annotation
        fun rec(a: ParsedTypeAnnotation): ParsedTypeAnnotation = when (a) {
            is ParsedNamedType -> {
                // Type-var names come with or without the leading tick in
                // different code paths — check both.
                val stripped = a.name.removePrefix("'")
                subst[a.name] ?: subst[stripped] ?: subst["'$stripped"] ?: a
            }
            is ParsedTypeApp -> a.copy(args = a.args.map { rec(it) })
            is ParsedRecordType -> a.copy(entries = a.entries.map { it.copy(type = rec(it.type)) })
            is ParsedVariantType -> a.copy(constructors = a.constructors.map { c ->
                c.copy(dataType = c.dataType?.let { rec(it) })
            })
            is ParsedFunctionType -> a.copy(
                paramType = rec(a.paramType),
                returnType = rec(a.returnType),
            )
            is ParsedSeqType -> a.copy(elementType = rec(a.elementType))
            is ParsedEffectRow -> a
            is ParsedQualifiedType -> a.copy(args = a.args.map { rec(it) })
        }
        return rec(annotation)
    }

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
