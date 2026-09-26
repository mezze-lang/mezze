package com.mezze.language.typechecker.context

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as ParsedVisibility
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.ast.ScopedEffectConstants
import com.mezze.language.typechecker.util.AbilityImplFactory
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typechecker.validation.AbilityValidator
import com.mezze.language.typechecker.validation.DuplicateDeclarationValidator
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typedast.Hash

class ContextBuilder {
    private val typeVarCache = mutableMapOf<String, TypeVar>()

    private val sharedRowVar = TypeAnnotationConverter.SharedRowVar()

    private var currentEffectDecls: Map<String, ParsedEffectDecl> = emptyMap()
    private var currentAbilityDecls: Map<String, ParsedAbilityDecl> = emptyMap()
    private var currentTypeDecls: Map<String, ParsedTypeDecl> = emptyMap()

    data class BuildResult(
        val context: TypingContext,
        val registry: DeclarationRegistry,
        val performerToEffect: Map<String, String>,
        val performerToEffectHash: Map<Hash, Hash> = emptyMap(),
        val performerAddedEffects: Map<String, Set<String>> = emptyMap(),
        val implDeclarations: Map<String, ParsedPerformerDecl> = emptyMap(),
        val resolvedModule: ParsedModule? = null,
    )

    fun build(
        module: ParsedModule,
        parentContext: TypingContext = TypingContext.empty,
        importedImpls: Map<String, ParsedPerformerDecl> = emptyMap(),
        importedAbilities: Map<String, ParsedAbilityDecl> = emptyMap(),
        importedTypes: Map<String, ParsedTypeDecl> = emptyMap(),
        importedEffects: Map<String, ParsedEffectDecl> = emptyMap(),
        currentModulePath: String? = null
    ): BuildResult {
        val file = module.path

        val typeDecls = module.decls.filterIsInstance<ParsedTypeDecl>()
            // Stamp the decl content hash up front so every downstream
            // typeDecl.contentHash read carries a real hash (pure fn of the decl,
            // always computable). A hashless type decl loses its nominal identity
            // (Anonymous) under TypeIdentity, breaking the name-in-scope wall.
            .map { if (it.contentHash != null) it else it.copy(contentHash = ParsedDeclHasher.hashTypeDecl(it)) }
            .associateBy { it.name }
            .toMutableMap()

        addBuiltInParsedTypes(typeDecls)

        val abilityDecls = module.decls.filterIsInstance<ParsedAbilityDecl>()
            .associateBy { it.name }

        val effectDecls = module.decls.filterIsInstance<ParsedEffectDecl>()
            .associateBy { it.name }
            .mapValues { (_, decl) ->
                if (decl.contentHash != null) decl
                else {
                    val hash = ParsedDeclHasher.hashEffectDecl(decl)
                    if (hash != null) decl.copy(contentHash = hash) else decl
                }
            }

        val allAbilityNames = abilityDecls.keys + importedAbilities.keys +
            importedAbilities.values.map { it.name }.toSet()
        val allEffectNames = effectDecls.keys + importedEffects.keys +
            importedEffects.values.map { it.name }.toSet()

        val resolvedParsedPerformerDecls = mutableListOf<ParsedPerformerDecl>()
        val resolvedAbilityImplDecls = mutableListOf<ParsedImplAbilityDecl>()
        val convertedPerformerDecls = mutableMapOf<ParsedPerformerDecl, ParsedImplAbilityDecl>()

        for (impl in module.decls.filterIsInstance<ParsedImplAbilityDecl>()) {
            resolvedAbilityImplDecls.add(ensureContentHash(impl, file))
        }

        for (perfDecl in module.decls.filterIsInstance<ParsedPerformerDecl>()) {
            val refName = perfDecl.effect.localName

            if (ImplClassifier.isAbilityImpl(refName, allAbilityNames, allEffectNames)) {
                val converted = ImplClassifier.performerToAbilityImpl(
                    perfDecl = perfDecl,
                    forallVars = emptyList(),
                    stripEmptyRecordParams = true,
                    targetLoc = perfDecl.loc,
                )
                val withHash = ensureContentHash(converted, file)
                resolvedAbilityImplDecls.add(withHash)
                convertedPerformerDecls[perfDecl] = withHash
            } else {
                val perfWithHash = if (perfDecl.contentHash != null) perfDecl
                    else perfDecl.copy(contentHash = ParsedDeclHasher.hashPerformerDecl(perfDecl))
                resolvedParsedPerformerDecls.add(perfWithHash)
            }
        }

        val abilityImplDecls = resolvedAbilityImplDecls

        val allDeclsForValidation: List<ParsedDecl> = module.decls.filterIsInstance<ParsedTypeDecl>() +
            module.decls.filterIsInstance<ParsedAbilityDecl>() +
            module.decls.filterIsInstance<ParsedEffectDecl>() +
            resolvedParsedPerformerDecls +
            abilityImplDecls +
            module.decls.filterIsInstance<ParsedLetDecl>()
        DuplicateDeclarationValidator.validateDecls(allDeclsForValidation)

        val allEffectDecls = effectDecls + importedEffects
        currentEffectDecls = allEffectDecls
        currentAbilityDecls = abilityDecls + importedAbilities
        currentTypeDecls = typeDecls + importedTypes

        val localMapping = resolvedParsedPerformerDecls.associate { perf ->
            val effectDecl = allEffectDecls[perf.effect.localName]
            val effectName = effectDecl?.name ?: perf.effect.localName
            perf.performerName to effectName
        }
        val importedMapping = importedImpls.mapValues { (_, perf) ->
            val effectDecl = allEffectDecls[perf.effect.localName]
            effectDecl?.name ?: perf.effect.localName
        }
        val performerToEffectMap = importedMapping + localMapping

        val performerToEffectHashMap = buildMap<Hash, Hash> {
            for (perf in resolvedParsedPerformerDecls + importedImpls.values) {
                val perfHash = perf.contentHash ?: continue
                val effHash = allEffectDecls[perf.effect.localName]?.contentHash ?: continue
                put(perfHash, effHash)
            }
        }

        var ctx = parentContext

        for (typeDecl in typeDecls.values) {
            ctx = addParsedTypeConstructors(ctx, typeDecl)
        }

        for (abilityDecl in abilityDecls.values) {
            ctx = addAbilityMethods(ctx, abilityDecl)
        }
        for (abilityDecl in importedAbilities.values) {
            if (ctx.getAbilityOperationType(abilityDecl.name) == null) {
                ctx = addAbilityMethods(ctx, abilityDecl)
            }
        }
        // Seed the built-in abilities (NumAdd, Ord, Eq, …) with their REAL,
        // class-param-preserving method types so `2 + 3` (which desugars to
        // `2.add {r=3}` and never `use`s NumAdd) resolves through the same
        // class-param-aware ability path as a user ability — not the single-var
        // flattening in BuiltInAbilities.getAbilityMethodType. Lowest precedence:
        // only seed a name a local/imported decl hasn't already registered, so a
        // module that explicitly imports the ability always wins.
        for (abilityDecl in BuiltInAbilities.getRawAbilityDeclarations().values) {
            if (ctx.getAbilityOperationType(abilityDecl.name) == null) {
                ctx = addAbilityMethods(ctx, abilityDecl)
            }
        }

        for (effectDecl in effectDecls.values) {
            ctx = addEffectOperations(ctx, effectDecl)
        }

        for (perfDecl in resolvedParsedPerformerDecls) {
            ctx = addEffectPerformer(ctx, perfDecl, allEffectDecls)
        }
        for ((_, perfDecl) in importedImpls) {
            ctx = addEffectPerformer(ctx, perfDecl, allEffectDecls)
        }

        val allParsedAbilityDeclsForHash = abilityDecls + importedAbilities
        for (abilityImplDecl in abilityImplDecls) {
            ctx = addAbilityImpl(ctx, abilityImplDecl, allParsedAbilityDeclsForHash, importedAbilities, file)
        }

        // DERIVED instances — Rust `#[derive]` / Haskell `deriving`, written by the
        // compiler: every record / variant this module OWNS whose components all have
        // `Eq` gets a generated `impl Eq` (DerivedImplGenerator). It is the type's ONE
        // instance and is exported like any hand-written impl. Decided only now, with
        // the explicit impls already in `ctx`, so a newtype field's declared `Eq`
        // counts — derivation is conditional, and a shape holding a newtype with no
        // `Eq` simply gets no instance (`==` on it stays a type error).
        val derivedImplDecls = deriveAbilityImpls(
            module, typeDecls, abilityImplDecls, importedTypes,
            allParsedAbilityDeclsForHash, effectDecls, currentModulePath, ctx,
        )
        for (derivedDecl in derivedImplDecls) {
            resolvedAbilityImplDecls.add(derivedDecl)
            ctx = addAbilityImpl(ctx, derivedDecl, allParsedAbilityDeclsForHash, importedAbilities, file)
        }

        validateAbilities(
            module, abilityDecls, abilityImplDecls,
            typeDecls, importedTypes, importedAbilities,
            effectDecls, currentModulePath,
            context = ctx,
        )

        val performerAddedEffectsMap = (resolvedParsedPerformerDecls + importedImpls.values).associate { perf ->
            perf.performerName to perf.operations.flatMap { op ->
                op.effects?.effects ?: emptyList()
            }.toSet()
        }.filterValues { it.isNotEmpty() }

        val implDeclarationsMap: Map<String, ParsedPerformerDecl> = resolvedParsedPerformerDecls.associateBy { it.performerName } +
            importedImpls

        // The resolved module is what the constraint generator typechecks and what
        // importers index (ProgramIndex), so derived impls ride along in it.
        val resolvedMod = if (convertedPerformerDecls.isEmpty() && derivedImplDecls.isEmpty()) {
            module
        } else {
            val resolvedDecls = module.decls.map { decl ->
                if (decl is ParsedPerformerDecl) {
                    convertedPerformerDecls[decl] ?: decl
                } else {
                    decl
                }
            } + derivedImplDecls
            ParsedModule(module.path, resolvedDecls)
        }

        return BuildResult(
            context = ctx,
            registry = DeclarationRegistry.from(typeDecls, abilityDecls, effectDecls),
            performerToEffect = performerToEffectMap,
            performerToEffectHash = performerToEffectHashMap,
            performerAddedEffects = performerAddedEffectsMap,
            implDeclarations = implDeclarationsMap,
            resolvedModule = resolvedMod,
        )
    }

    /**
     * The derived `Eq` instances this module owns — one generated impl per record /
     * variant that (a) is DECLARED here or is a builtin synthetic variant whose home
     * module this is (Option → std::option, Result → std::result), so the instance
     * exists exactly once program-wide; (b) is not a primitive (`Bool` is a native
     * BoolType with its own impl); (c) has no hand-written `Eq` impl here (that one
     * is rejected as a duplicate by ImplValidator — one error, not two); and (d)
     * structurally derives `Eq` by the solver's own predicate, with every explicit
     * impl already in [ctx] so a newtype component's declared `Eq` counts.
     * Needs `Eq` nameable in this module (its decl in scope), as a source impl would.
     */
    /**
     * The type NAMES this module already writes an `<ability>` impl for.
     *
     * Only used to stay OUT of the way: where a module writes its own instance the
     * compiler generates none, so the duplicate is reported once, by the validator,
     * rather than being manufactured here and then rejected.
     */
    private fun explicitImplTargetNames(
        explicitImpls: List<ParsedImplAbilityDecl>,
        abilityName: String,
    ): Set<String> = explicitImpls
        .filter { it.abilityName == abilityName }
        .mapNotNull { impl ->
            when (val t = impl.targetTypes.lastOrNull()) {
                is ParsedNamedType -> t.name
                is ParsedTypeApp -> t.constructor
                else -> null
            }
        }
        .toSet()

    private fun deriveAbilityImpls(
        module: ParsedModule,
        typeDecls: Map<String, ParsedTypeDecl>,
        explicitImpls: List<ParsedImplAbilityDecl>,
        importedTypes: Map<String, ParsedTypeDecl>,
        allAbilityDecls: Map<String, ParsedAbilityDecl>,
        effectDecls: Map<String, ParsedEffectDecl>,
        currentModulePath: String?,
        ctx: TypingContext,
    ): List<ParsedImplAbilityDecl> {
        val eqDecl = allAbilityDecls["Eq"] ?: return emptyList()
        val eqHash = eqDecl.contentHash ?: ParsedDeclHasher.hashAbilityDecl(eqDecl)
        if (!WellKnownHashConstants.isEq(eqHash)) return emptyList()

        val declaredHere = module.decls.filterIsInstance<ParsedTypeDecl>().map { it.name }.toSet()
        val homeHere = currentModulePath?.let { BuiltinTypeRegistry.getLocalTypesForModule(ModulePath.parse(it)) } ?: emptySet()
        val explicitEqTargets = explicitImplTargetNames(explicitImpls, "Eq")
        val registry = DeclarationRegistry.from(typeDecls + importedTypes, allAbilityDecls, effectDecls)
        val checker = com.mezze.language.typechecker.solving.AbilityChecker(ctx, registry)

        // A PRIMITIVE has no `ParsedTypeDecl` to walk, so it is generated from its
        // home module rather than from the decl list — `Int` in `std::int`, `Str` in
        // `std::str`, exactly where a hand-written impl was allowed to live. The
        // instance has to exist for the same reason every other one does: a generic
        // position needs an `Eq Str` DICT to pass, and derivability alone is not one.
        // `Eq` and `Hash` are generated together, because they are one decision: a
        // hash container buckets by `hash` and compares by `eq`, and a derived
        // equality paired with a hand-written hash is the pairing the coherence rule
        // rejects for every other type.
        // Each generated impl needs its ability NAMEABLE here, exactly as a source
        // impl would — the `Eq` guard at the top of this function is the same rule.
        // `Hash` is checked separately because a module can perfectly well import
        // one and not the other, and generating an impl of an ability the module
        // cannot name fails later with "Ability 'Hash' is not defined or not
        // imported", pointing at an impl the author never wrote.
        val hashDecl = allAbilityDecls["Hash"]
        val hashNameable = hashDecl != null && WellKnownHashConstants.isHash(
            hashDecl.contentHash ?: ParsedDeclHasher.hashAbilityDecl(hashDecl)
        )
        val explicitHashTargets = explicitImplTargetNames(explicitImpls, "Hash")
        val primitiveImpls = com.mezze.language.typechecker.derive.DerivedImplGenerator.primitiveEqNames
            .filter { it in homeHere }
            .flatMap { name ->
                listOfNotNull(
                    if (name in explicitEqTargets) null
                    else com.mezze.language.typechecker.derive.DerivedImplGenerator.primitiveEqImpl(name),
                    if (!hashNameable || name in explicitHashTargets) null
                    else com.mezze.language.typechecker.derive.DerivedImplGenerator.primitiveHashImpl(name),
                )
            }
            .map { it.copy(contentHash = ParsedDeclHasher.hashImplAbilityDecl(it)) }

        return primitiveImpls + typeDecls.values.mapNotNull { decl ->
            if (decl.name !in declaredHere && decl.name !in homeHere) return@mapNotNull null
            if (PrimitiveTypes.fromName(decl.name) != null) return@mapNotNull null
            if (decl.name in explicitEqTargets) return@mapNotNull null
            val generated = com.mezze.language.typechecker.derive.DerivedImplGenerator.eqImpl(decl)
                ?: return@mapNotNull null
            val receiver = MezzeType.TypeApplication(
                decl.name,
                generated.forallVars.map { MezzeType.VarType(TypeVar.freshWithName(it)) },
            )
            if (!checker.canDeriveAbility("Eq", receiver, eqHash)) return@mapNotNull null
            generated.copy(contentHash = ParsedDeclHasher.hashImplAbilityDecl(generated))
        }
    }

    private fun validateAbilities(
        module: ParsedModule,
        abilityDecls: Map<String, ParsedAbilityDecl>,
        abilityImplDecls: List<ParsedImplAbilityDecl>,
        typeDecls: Map<String, ParsedTypeDecl>,
        importedTypes: Map<String, ParsedTypeDecl>,
        importedAbilities: Map<String, ParsedAbilityDecl>,
        effectDecls: Map<String, ParsedEffectDecl>,
        currentModulePath: String?,
        // Fully-assembled context (local + prelude/imported impls). Used by F4 to see
        // inherent methods across modules when checking ability-impl shadowing.
        context: TypingContext,
    ) {
        val allParsedAbilityDecls = abilityDecls + importedAbilities
        val allParsedTypeDecls = typeDecls + importedTypes

        val localValidationRegistry = DeclarationRegistry.from(allParsedTypeDecls, abilityDecls, effectDecls)
        AbilityValidator.validateNameCollisions(localValidationRegistry)

        for (abilityDecl in abilityDecls.values) {
            AbilityValidator.validateAbilityDeclaration(abilityDecl)
        }

        val implValidationRegistry = DeclarationRegistry.from(allParsedTypeDecls, allParsedAbilityDecls, effectDecls)
        for (abilityImplDecl in abilityImplDecls) {
            AbilityValidator.validateAbilityImplementation(
                impl = abilityImplDecl,
                registry = implValidationRegistry,
                context = context,
            )
        }

        val localAbilityNames = abilityDecls.keys
        val declaredTypeNames = module.decls.filterIsInstance<ParsedTypeDecl>().map { it.name }.toSet()
        val homeTypeNames = if (currentModulePath != null) {
            BuiltinTypeRegistry.getLocalTypesForModule(ModulePath.parse(currentModulePath))
        } else emptySet()
        val localTypeNames = declaredTypeNames + homeTypeNames
        val importedAbilityNames = importedAbilities.keys
        val importedTypeNames = importedTypes.keys

        for (abilityImplDecl in abilityImplDecls) {
            AbilityValidator.validateOrphanRules(
                impl = abilityImplDecl,
                localAbilityNames = localAbilityNames,
                localTypeNames = localTypeNames,
                importedAbilityNames = importedAbilityNames,
                importedTypeNames = importedTypeNames
            )
        }
    }

    fun addParsedTypeConstructors(ctx: TypingContext, typeDecl: ParsedTypeDecl, isImported: Boolean = false): TypingContext {
        var result = ctx
        when (val definition = typeDecl.definition) {
            is ParsedVariantDef -> {
                for (constructor in definition.constructors) {
                    val constructorType = buildParsedConstructorType(typeDecl, constructor)
                    val typeVars = mutableSetOf<TypeVar>()
                    collectTypeVariables(constructorType, typeVars)
                    val scheme = TypeScheme(typeVars.toList(), emptyList(), constructorType)
                    result = result.extendPoly(constructor.name, scheme)
                }
            }
            is ParsedRecordDef -> { }
            is ParsedNewtypeDef -> {
                if (isImported) return result
                typeVarCache.clear()
                val wrappedMezzeType = convertParsedTypeAnnotationInternal(definition.wrappedType)
                val typeArgs = typeDecl.typeParams.map { param ->
                    MezzeType.VarType(typeVarCache.getOrPut(param) { TypeVar.freshWithName(param) })
                }
                val newtypeResult = WellKnownTypeApp.of(
                    typeDecl.name, typeArgs,
                    typeHash = typeDecl.contentHash ?: ParsedDeclHasher.hashTypeDecl(typeDecl)
                )
                val constructorType = MezzeType.FunctionType(paramType = wrappedMezzeType, returnType = newtypeResult)
                val typeVars = mutableSetOf<TypeVar>()
                collectTypeVariables(constructorType, typeVars)
                result = result.extendPoly(definition.constructor, TypeScheme(typeVars.toList(), emptyList(), constructorType))
            }
            is ParsedTypeAliasDef -> { /* transparent alias — no constructor to bind */ }
        }
        return result
    }

    private fun buildParsedConstructorType(typeDecl: ParsedTypeDecl, constructor: ParsedVariantCtorDef): MezzeType {
        typeVarCache.clear()
        val resultType = if (typeDecl.typeParams.isEmpty()) {
            when (val def = typeDecl.definition) {
                is ParsedNewtypeDef -> WellKnownTypeApp.of(
                    typeDecl.name, emptyList(),
                    typeHash = typeDecl.contentHash ?: ParsedDeclHasher.hashTypeDecl(typeDecl)
                )
                else -> {
                    val constructors = when (def) {
                        is ParsedVariantDef -> def.constructors.associate { ctor ->
                            ctor.name to ctor.dataType?.let { convertParsedTypeAnnotationInternal(it) }
                        }
                        is ParsedRecordDef -> emptyMap()
                        is ParsedNewtypeDef -> emptyMap()
                        is ParsedTypeAliasDef -> emptyMap()
                    }
                    MezzeType.VariantType(
                        constructors = constructors, rowVar = null,
                        // A declared variant always has a content hash — it is a pure
                        // function of the decl (ParsedDeclHasher), so compute it rather
                        // than falling back to a hashless identity. S3.1: via the factory.
                        identity = TypeIdentity.declared(
                            typeDecl.name,
                            typeDecl.contentHash ?: ParsedDeclHasher.hashTypeDecl(typeDecl)
                        )
                    )
                }
            }
        } else {
            WellKnownTypeApp.of(
                constructor = typeDecl.name,
                args = typeDecl.typeParams.map { param ->
                    MezzeType.VarType(typeVarCache.getOrPut(param) { TypeVar.freshWithName(param) })
                },
                typeHash = typeDecl.contentHash ?: ParsedDeclHasher.hashTypeDecl(typeDecl)
            )
        }
        val ctorType = when (val dataType = constructor.dataType) {
            null -> resultType
            else -> {
                val paramType = convertParsedTypeAnnotationInternal(dataType)
                MezzeType.FunctionType(
                    paramType = paramType,
                    returnType = resultType
                )
            }
        }
        return ctorType
    }

    private fun addAbilityMethods(ctx: TypingContext, abilityDecl: ParsedAbilityDecl): TypingContext {
        typeVarCache.clear()
        val abilityRecordType = MezzeType.RecordType(
            fields = abilityDecl.operations.associate { op ->
                val constraints = op.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertParsedTypeAnnotationInternal(it) },
                        abilityHash = currentAbilityDecls[constraint.abilityName]?.contentHash
                    )
                }
                val opEffects = op.effects?.let { effectRow ->
                    val hashes = effectRow.effects.mapNotNull { name ->
                        currentEffectDecls[name]?.contentHash?.let { name to it }
                    }.toMap()
                    MezzeType.EffectRow.simple(
                        effectRow.effects.toSet(), rowVar = null,
                        performed = effectRow.isPerformed, effectHashes = hashes
                    )
                }
                // Row subtraction on nested callback rows: any effect
                // declared on a callback's row that ISN'T declared on the
                // enclosing op's return row is HANDLED by the op (e.g.,
                // `spawn : {f: {} -> 'a <Exception, ...>} -> Promise 'a
                // <Async, ...>` — Exception is caught by spawn).
                val outerReturnEffectNames = opEffects?.allEffectNames ?: emptySet()
                val rawConvertedParam = convertParsedTypeAnnotationInternal(op.paramType)
                val convertedParam = TypeAnnotationConverter.stampCaughtOnCallbackRowsPublic(
                    rawConvertedParam,
                    outerReturnEffectNames
                )
                op.name to MezzeType.FunctionType(
                    paramType = convertedParam,
                    returnType = convertParsedTypeAnnotationInternal(op.returnType),
                    effects = opEffects, abilityConstraints = constraints
                )
            }
        )
        return ctx.extendWithAbilityOperationType(abilityDecl.name, abilityRecordType, abilityDecl.contentHash)
    }

    private fun addEffectOperations(ctx: TypingContext, effectDecl: ParsedEffectDecl): TypingContext {
        typeVarCache.clear()
        val effectRecordType = MezzeType.RecordType(
            fields = effectDecl.operations.associate { op ->
                // Row subtraction: compute callback rows' extras that are
                // NOT on this op's outer return row — they're "handled"
                // by this op. Stamp caughtByAncestor on all nested
                // callback rows to encode this.
                val outerReturnEffectNames = op.effects.toSet()
                val rawConvertedParam = convertParsedTypeAnnotationInternal(op.paramType)
                val convertedParam = TypeAnnotationConverter.stampCaughtOnCallbackRowsPublic(
                    rawConvertedParam,
                    outerReturnEffectNames
                )
                // Collect caught effects from all nested callback rows —
                // these are what this op catches. Stamp caughtByAncestor
                // on any FunctionType's effect row in the return type
                // (needed when the return is itself a function returning
                // a Promise or similar with its own effect row).
                val caughtEffects = TypeAnnotationConverter.collectCaughtByAncestorFromType(convertedParam)
                val rawConvertedReturn = convertParsedTypeAnnotationInternal(op.returnType)
                val convertedReturn = TypeAnnotationConverter.stampCaughtOnAllEffectRowsPublic(
                    rawConvertedReturn, caughtEffects
                )
                // An op's `with` clause becomes a constraint on its type, exactly
                // as for an ability method (see addAbilityMethods) and an ordinary
                // function. Omitting it made the clause parse-and-vanish: the call
                // site emits `AbilityRequired` from the callee type's constraints,
                // so an empty list there means the obligation is never checked.
                val opAbilityConstraints = op.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertParsedTypeAnnotationInternal(it) },
                        abilityHash = currentAbilityDecls[constraint.abilityName]?.contentHash
                    )
                }
                op.name to MezzeType.FunctionType(
                    paramType = convertedParam,
                    returnType = convertedReturn,
                    effects = null,
                    abilityConstraints = opAbilityConstraints
                )
            }
        )
        return ctx.extendWithEffectOperationType(effectDecl.name, effectRecordType, effectDecl.contentHash)
    }

    private fun extractSuspensionTypeV2(
        implDecl: ParsedPerformerDecl,
        associatedTypes: Map<String, MezzeType>
    ): MezzeType? {
        for (op in implDecl.operations) {
            val impl = op.implementation
            if (impl !is ParsedLambda) continue
            val suspendWithValue = findSuspendWithValueParsed(impl.body)
            if (suspendWithValue != null) {
                return inferParsedPayloadType(suspendWithValue, associatedTypes)
            }
        }
        return null
    }

    private fun findSuspendWithValueParsed(expr: ParsedExpr): ParsedExpr? {
        return when (expr) {
            is ParsedFunctionCall -> {
                val callee = expr.function
                if (callee is ParsedIdentifier
                    && EffectBindingKind.fromName(callee.localName) == EffectBindingKind.ABORT
                ) {
                    val v = extractRecordField(expr.arguments, ScopedEffectConstants.VAL_FIELD)
                    if (v != null) return v
                }
                findSuspendWithValueParsed(expr.arguments)
            }
            is ParsedDo -> {
                findSuspendWithValueParsed(expr.result) ?: run {
                    for (stmt in expr.statements.reversed()) {
                        when (stmt) {
                            is ParsedStatement.Expr -> {
                                val found = findSuspendWithValueParsed(stmt.expr)
                                if (found != null) return found
                            }
                            is ParsedStatement.Let -> {
                                val found = findSuspendWithValueParsed(stmt.value)
                                if (found != null) return found
                            }
                        }
                    }
                    null
                }
            }
            is ParsedIf -> {
                findSuspendWithValueParsed(expr.thenBranch) ?: findSuspendWithValueParsed(expr.elseBranch)
            }
            is ParsedMatch -> {
                for (case in expr.cases) {
                    val found = findSuspendWithValueParsed(case.body)
                    if (found != null) return found
                }
                null
            }
            is ParsedLambda -> findSuspendWithValueParsed(expr.body)
            else -> null
        }
    }

    private fun extractRecordField(args: ParsedExpr, fieldName: String): ParsedExpr? {
        if (args !is ParsedRecordLit) return null
        for (entry in args.entries) {
            if (entry is ParsedRecordEntry.Field && entry.name == fieldName) {
                return entry.value
            }
        }
        return null
    }

    private fun inferParsedPayloadType(expr: ParsedExpr, associatedTypes: Map<String, MezzeType>): MezzeType =
        when (expr) {
            is ParsedVariantConstruct -> {
                val payloadType = expr.value?.let { inferParsedPayloadType(it, associatedTypes) }
                when (expr.constructor) {
                    BuiltinTypes.ERR, BuiltinTypes.OK -> {
                        val errorType = associatedTypes["error"] ?: MezzeType.VarType(TypeVar.fresh())
                        WellKnownTypeApp.of(
                            constructor = BuiltinTypes.RESULT,
                            args = listOf(MezzeType.VarType(TypeVar.fresh()), errorType)
                        )
                    }
                    BuiltinTypes.NONE -> WellKnownTypeApp.of(
                        constructor = BuiltinTypes.OPTION,
                        args = listOf(MezzeType.VarType(TypeVar.fresh()))
                    )
                    BuiltinTypes.SOME -> WellKnownTypeApp.of(
                        constructor = BuiltinTypes.OPTION,
                        args = listOf(payloadType ?: MezzeType.VarType(TypeVar.fresh()))
                    )
                    else -> MezzeType.VariantType(
                        constructors = mapOf(expr.constructor to payloadType),
                        rowVar = RowVar.fresh()
                    )
                }
            }
            is ParsedRecordLit -> {
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in expr.entries) {
                    if (entry is ParsedRecordEntry.Field) {
                        fields[entry.name] = entry.value?.let { inferParsedPayloadType(it, associatedTypes) }
                            ?: associatedTypes[entry.name]
                            ?: MezzeType.VarType(TypeVar.fresh())
                    }
                }
                MezzeType.RecordType(fields)
            }
            is ParsedIdentifier -> associatedTypes[expr.localName] ?: MezzeType.VarType(TypeVar.fresh())
            else -> MezzeType.VarType(TypeVar.fresh())
        }

    private fun addEffectPerformer(
        ctx: TypingContext,
        implDecl: ParsedPerformerDecl,
        effectDecls: Map<String, ParsedEffectDecl>
    ): TypingContext {
        val implWithHash = if (implDecl.contentHash != null) implDecl else {
            implDecl.copy(contentHash = ParsedDeclHasher.hashPerformerDecl(implDecl))
        }
        @Suppress("NAME_SHADOWING")
        val implDecl = implWithHash

        val effectDecl = effectDecls[implDecl.effect.localName] ?: return ctx
        val hasAssocTypes = effectDecl.associatedTypes.isNotEmpty()
        if (!hasAssocTypes && implDecl.params.isEmpty()) return ctx

        // Unconditionally — this declaration gets its own variable scope.
        //
        // Gating the clear on `hasAssocTypes` left the other live path (no
        // associated types, but parameters below to convert) reusing whatever the
        // PREVIOUS declaration left in the cache, so this performer's `'a` could
        // silently BE that declaration's `'a`. That is the mirror of splitting one
        // written variable in two: here two unrelated ones are fused. The early
        // returns above already cover the case where there is nothing to convert.
        typeVarCache.clear()

        val contentHash = implDecl.contentHash
            ?: error("Content hash not computed for impl: ${implDecl.performerName}. " +
                "Ensure computeDeclarationHashes was called before ContextBuilder.")

        // #22: `Polyglot` is THE foreign boundary — its associated type `'f` is a
        // guest-language handle. Mint its binding as a FOREIGN-KINDED var: still
        // flexible (it unifies with each provider's `newtype <Lang>Foreign = Foreign`,
        // so chains infer with no annotations), but gated in unification against
        // STRUCTURAL targets so a provider op declared `-> Result XForeign` cannot
        // launder the bare foreign into a `Result` (which crashed `match … is Ok/Err`
        // at runtime). The handler's own `having 'f = 'g` binding is abstract, so
        // there is no structural signal to key on — the effect name is.
        val isForeignEffect = implDecl.effect.localName == "Polyglot"
        val performerType = if (hasAssocTypes) {
            val bindingFields = implDecl.associatedTypeBindings.mapValues { (_, ann) ->
                convertParsedTypeAnnotationInternal(ann)
            }
            MezzeType.RecordType(
                fields = effectDecl.associatedTypes.associate { assocType ->
                    val varName = assocType.removePrefix("'")
                    val binding = if (isForeignEffect) MezzeType.VarType(TypeVar.foreignKinded())
                        else bindingFields[assocType] ?: bindingFields["'$assocType"] ?: MezzeType.VarType(TypeVar.fresh())
                    varName to binding
                }
            )
        } else MezzeType.RecordType(emptyMap())

        val performerFieldsType = if (implDecl.params.isNotEmpty()) {
            MezzeType.RecordType(fields = implDecl.params.associate { p ->
                p.name to convertParsedTypeAnnotationInternal(p.type)
            })
        } else null

        val associatedTypesMap = if (hasAssocTypes) performerType.fields else emptyMap()
        val suspensionType = extractSuspensionTypeV2(implDecl, associatedTypesMap)

        val performerInfo = TypingContext.PerformerTypeInfo(
            performerName = implDecl.performerName,
            effectName = implDecl.effect.localName,
            associatedTypes = performerType,
            performerFields = performerFieldsType,
            suspensionType = suspensionType,
            contentHash = contentHash
        )
        return ctx.extendWithPerformerTypeInfo(contentHash, performerInfo)
    }

    private fun addAbilityImpl(
        ctx: TypingContext,
        implDecl: ParsedImplAbilityDecl,
        allAbilityDecls: Map<String, ParsedAbilityDecl> = emptyMap(),
        importedAbilities: Map<String, ParsedAbilityDecl> = emptyMap(),
        file: String = "<module>"
    ): TypingContext {
        val targetTypeNames = implDecl.targetTypes.map { getTypeName(it) }

        val implKey = implDecl.contentHash
            ?: throw IllegalStateException(
                "Content hash not computed for impl: ${implDecl.abilityName ?: "inherent"} ${implDecl.targetTypes}. " +
                "Ensure computeDeclarationHashes was called before ContextBuilder."
            )

        if (implDecl.isInherent) {
            return addAnonymousImpl(ctx, implDecl, targetTypeNames, implKey, file)
        }

        val abilityDeclHash = allAbilityDecls[implDecl.abilityName!!]?.contentHash
            ?: importedAbilities[implDecl.abilityName!!]?.contentHash
        val implInfo = AbilityImplFactory.build(
            implDecl, abilityDeclHash
        )

        return ctx.extendWithImpl(implKey, implInfo)
    }

    private fun addAnonymousImpl(
        ctx: TypingContext,
        implDecl: ParsedImplAbilityDecl,
        targetTypeNames: List<String>,
        implKey: Hash,
        @Suppress("UNUSED_PARAMETER") file: String = "<module>"
    ): TypingContext {
        val targetTypeName = targetTypeNames.firstOrNull() ?: return ctx
        // The hash that dispatch keys on: the target type's DECLARED-identity hash.
        // A locally-declared nominal target (`impl JvmForeign`) is keyed by its
        // own decl hash — the AUTHORITATIVE source, kept FIRST so this change is
        // behaviour-neutral for every existing simple-nominal impl. Only when the
        // name isn't a local decl — a TYPE-APPLICATION target like
        // `impl Result JvmForeign InteropError`, whose mangled `targetTypeName`
        // (`Result_JvmForeign_InteropError`) is never a decl key — do we fall back
        // to the CONSTRUCTOR's decl hash (`Result`), the same quantity the receiver
        // produces via declaredHashOf. That fallback is what makes an
        // `impl Result …` block selectable for a `Result …` receiver (previously the
        // target hash came out null and dispatch picked the wrong candidate). See
        // [implTargetDeclaredHash].
        val targetTypeHash = currentTypeDecls[targetTypeName]?.contentHash
            ?: implTargetDeclaredHash(implDecl)

        var result = ctx
        for (op in implDecl.operations) {
            val paramType = op.paramType?.let { convertParsedTypeAnnotation(it) }
                ?: inferParamTypeFromLambda(op.implementation) { parsedType, name ->
                    val isSynthetic = (parsedType as? ParsedNamedType)
                        ?.let { it.name.removePrefix("'") == name } ?: false
                    if (isSynthetic) MezzeType.VarType(TypeVar.fresh())
                    else convertParsedTypeAnnotation(parsedType)
                }
            val returnType = op.returnType?.let { convertParsedTypeAnnotation(it) }
                ?: MezzeType.VarType(TypeVar.fresh())

            result = result.extendWithAnonymousMethod(TypingContext.InherentMethodInfo(
                targetTypeName = targetTypeName, methodName = op.name,
                methodType = MezzeType.FunctionType(paramType = paramType, returnType = returnType),
                implContentHash = implKey,
                // Content hash of the TARGET type. Populates the hash index so member
                // dispatch can disambiguate two same-named types from different modules
                // (name stays the fallback for builtins with no decl hash). Additive —
                // the name-keyed maps are unchanged. project_classparam_runtime_dispatch_tuple.
                targetTypeHash = targetTypeHash,
                implStartLine = implDecl.loc.startLine, implStartCol = implDecl.loc.startCol,
                implSourceFile = implDecl.loc.file
            ))
        }

        result = result.extendWithImpl(implKey, AbilityImplFactory.build(implDecl, abilityDeclHash = null))
        return result
    }

    private fun inferParamTypeFromLambda(
        implementation: ParsedExpr,
        // The DEFAULT shares one variable scope across the whole parameter list —
        // see TypeAnnotationConverter.convertSignature. Per-field conversion split
        // the `'a` of `{ x: 'a, y: 'a }` into two variables. (A caller that passes
        // its own converter owns its scoping; both current overrides resolve each
        // field against the ability's declared op type, so they have no shared
        // variable to lose.)
        convertField: (ParsedTypeAnnotation, String) -> MezzeType = { ann, _ ->
            convertParsedTypeAnnotationInternal(ann)
        }
    ): MezzeType {
        val lambda = implementation as? ParsedLambda
            ?: return MezzeType.RecordType(fields = emptyMap(), rowVar = null)
        return when (val params = lambda.params) {
            is ParsedLambdaParams.Typed -> MezzeType.RecordType(
                fields = run {
                    typeVarCache.clear()
                    sharedRowVar.reset()
                    params.fields.associate { it.name to convertField(it.type, it.name) }
                },
                rowVar = null
            )
            is ParsedLambdaParams.Untyped -> MezzeType.RecordType(
                fields = if (params.names.isEmpty()) emptyMap()
                         else params.names.associateWith { MezzeType.VarType(TypeVar.fresh()) },
                rowVar = null
            )
        }
    }

    fun registerImportedInherentImpl(
        ctx: TypingContext,
        implDecl: ParsedImplAbilityDecl,
        implKey: Hash,
        @Suppress("UNUSED_PARAMETER") file: String = "<import>",
        opReturnTypes: Map<String, MezzeType> = emptyMap(),
        opParamTypes: Map<String, MezzeType> = emptyMap(),
        opEffects: Map<String, MezzeType.EffectRow> = emptyMap(),
        opConstraints: Map<String, List<MezzeType.FunctionAbilityConstraint>> = emptyMap()
    ): TypingContext {
        val targetTypeName = implDecl.targetTypes.map { getTypeName(it) }.firstOrNull() ?: return ctx

        val implTargetType = implDecl.targetTypes.firstOrNull()?.let { convertParsedTypeAnnotation(it) }
        // Constructor-level declared hash (see the identical note + rationale in
        // addAnonymousImpl/implTargetDeclaredHash). Local decl hash first
        // (authoritative, behaviour-neutral); fall back to the constructor's
        // declared hash ONLY for a genuine type application with args
        // (`impl Result <T> <E>`) — never a bare nominal, whose cross-module hash
        // could differ from the receiver's and break name-fallback dispatch.
        val targetTypeHash = currentTypeDecls[targetTypeName]?.contentHash
            ?: implTargetType?.takeIf { it is MezzeType.TypeApplication && it.args.isNotEmpty() }
                ?.let { com.mezze.language.typechecker.util.declaredHashOf(it) }
        val targetTypeVarsByName = mutableMapOf<String, TypeVar>()
        if (implTargetType != null) collectVarTypesByName(implTargetType, targetTypeVarsByName)
        val targetVarsInOrder = if (implTargetType != null) collectVarTypesInOrder(implTargetType) else emptyList()

        var result = ctx
        for (op in implDecl.operations) {
            val paramType = opParamTypes[op.name]
                ?: op.paramType?.let { convertParsedTypeAnnotation(it) }
                ?: inferParamTypeFromLambda(op.implementation) { ann, name ->
                    if (name == "it" && implTargetType != null) implTargetType
                    else convertParsedTypeAnnotation(ann)
                }
            val rawReturnType = op.returnType?.let { convertParsedTypeAnnotation(it) }
                ?: opReturnTypes[op.name] ?: MezzeType.VarType(TypeVar.fresh())

            // Align the param record and the return TOGETHER, as one FunctionType,
            // through a SINGLE alignToTarget pass. Aligning them separately runs two
            // independent var-remaps, so a type var shared between a param and the
            // return (e.g. `get_or: {…, def: 'a} -> 'a`) is re-split into two
            // distinct instances — and `top.get_or{def=..}` then records `'a`
            // instead of the pinned argument type. One pass preserves the sharing.
            // Inferred ability constraints (`with FromJson 'a Str`, etc.) harvested
            // from the imported op's post-solve type. Included IN the FunctionType so
            // alignToTarget's var-remap keeps them consistent with param/return; the
            // constraint's data var already equals the return var (the lambda body IS
            // the return), so pinning the return at the call site pins the codec target.
            val opConstraintsForOp = opConstraints[op.name].orEmpty()
            val methodType: MezzeType.FunctionType =
                if (targetVarsInOrder.isNotEmpty() && paramType is MezzeType.RecordType) {
                    val alignedWhole = alignToTarget(
                        MezzeType.FunctionType(paramType = paramType, returnType = rawReturnType, effects = opEffects[op.name], abilityConstraints = opConstraintsForOp),
                        targetVarsInOrder, targetTypeVarsByName
                    )
                    alignedWhole as? MezzeType.FunctionType
                        ?: MezzeType.FunctionType(paramType = paramType, returnType = rawReturnType, effects = opEffects[op.name], abilityConstraints = opConstraintsForOp)
                } else {
                    MezzeType.FunctionType(paramType = paramType, returnType = rawReturnType, effects = opEffects[op.name], abilityConstraints = opConstraintsForOp)
                }

            result = result.extendWithAnonymousMethod(TypingContext.InherentMethodInfo(
                targetTypeName = targetTypeName, methodName = op.name,
                methodType = methodType,
                implContentHash = implKey,
                // See addAnonymousImpl — populate the target type's content hash for
                // hash-preferring dispatch (currentTypeDecls covers imported types).
                targetTypeHash = targetTypeHash,
                implStartLine = implDecl.loc.startLine, implStartCol = implDecl.loc.startCol,
                implSourceFile = implDecl.loc.file, isImported = true
            ))
        }

        result = result.extendWithImpl(implKey, AbilityImplFactory.build(implDecl, abilityDeclHash = null))
        return result
    }

    private fun alignToTarget(type: MezzeType, targetVarsInOrder: List<TypeVar>, targetVarsByName: Map<String, TypeVar>): MezzeType {
        if (targetVarsInOrder.isEmpty()) return type
        val byName = alignVarTypesByName(type, targetVarsByName)
        val freeAfterName = collectVarTypesInOrder(byName)
        if (freeAfterName.size == targetVarsInOrder.size &&
            freeAfterName.zip(targetVarsInOrder).any { (a, b) -> a != b }) {
            return alignVarTypesByMapping(byName, freeAfterName.zip(targetVarsInOrder).toMap())
        }
        return byName
    }

    private fun getTypeName(annotation: ParsedTypeAnnotation): String =
        AbilityImplFactory.typeNameOf(annotation)

    /**
     * The CONSTRUCTOR-level declared hash of a TYPE-APPLICATION impl target —
     * ONLY for a genuine application with type args (`impl Result JvmForeign
     * InteropError`, `impl Option 'a`), never for a bare nominal target
     * (`impl Client`, `impl JvmForeign`). This is a NARROW fallback used only when
     * `currentTypeDecls[mangledName]` misses — which it always does for the mangled
     * `Result_JvmForeign_InteropError` form. It reads the constructor's declared
     * hash (`Result`) via [declaredHashOf], the same STABLE quantity a `Result …`
     * receiver produces at the dispatch site, so an `impl Result <T> <E>` block
     * becomes selectable for a `Result <T> <E>` receiver.
     *
     * Restricting to `args.isNotEmpty()` is load-bearing: a bare nominal target's
     * cross-module conversion can mint a hash that DIFFERS from the receiver's
     * (module-local decl hashes need not agree across the import boundary), so
     * supplying it would turn a working name-match (null hash) into a hash-veto
     * MISMATCH — the #-regression on cross-module `impl Client`/`impl Req` chains.
     * A type application's constructor is a nominal DECLARATION whose hash is
     * stable wherever the constructor is declared (stdlib `Result`/`Option`), so
     * only that case is safe here. Bare nominals return null → the existing
     * name-fallback path (unchanged from before this fix).
     */
    private fun implTargetDeclaredHash(implDecl: ParsedImplAbilityDecl): Hash? =
        implDecl.targetTypes.firstOrNull()
            ?.let { convertParsedTypeAnnotation(it) }
            ?.let { converted ->
                if (converted is MezzeType.TypeApplication && converted.args.isNotEmpty())
                    // BUG 1: prefer the APPLIED hash, so `impl Result PyForeign IErr`
                    // and `impl Result JsForeign IErr` file under DIFFERENT keys
                    // instead of both landing on `Result`'s constructor hash, where
                    // the second silently overwrote the first. A generic
                    // `impl Result 'a 'e` has no concrete arg, mints no applied hash,
                    // and keeps the constructor hash — the receiver side tries the
                    // applied hash first and falls back, so the generic impl stays
                    // reachable exactly as before.
                    com.mezze.language.typechecker.util.appliedHashOf(converted)
                        ?: com.mezze.language.typechecker.util.declaredHashOf(converted)
                else null
            }

    private fun buildConversionConfig() = TypeAnnotationConverter.ConversionConfig(
        resolveTypeHash = { currentTypeDecls[it]?.let { d -> d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d) } },
        resolveAbilityHash = { currentAbilityDecls[it]?.contentHash },
        validateEffects = { effectRow ->
            TypeAnnotationConverter.validateUnknownEffects(
                effectRow, currentEffectDecls.keys
            )
        },
        resolveEffectHashes = { names ->
            names.mapNotNull { name ->
                currentEffectDecls[name]?.contentHash?.let { name to it }
            }.toMap()
        },
        honorOpenRowVars = true,
        stripTickFromTypeVars = true,
        resolveTypeAlias = { name ->
            // Transparent alias inlining for `type X = <anything>`. Fires for
            // every RHS shape of a `type` decl. `newtype` stays labelled.
            currentTypeDecls[name]?.let { decl ->
                aliasedAnnotationForTypeDecl(decl)?.let { ann -> ann to decl.typeParams }
            }
        },
    )

    fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        typeVarCache.clear()
        sharedRowVar.reset()
        return TypeAnnotationConverter.convertWith(
            annotation, buildConversionConfig(), typeVarCache, sharedRowVar
        )
    }

    fun convertParsedTypeAnnotationInternal(annotation: ParsedTypeAnnotation): MezzeType {
        return TypeAnnotationConverter.convertWith(
            annotation, buildConversionConfig(), typeVarCache, sharedRowVar
        )
    }

    private fun addBuiltInParsedTypes(typeDecls: MutableMap<String, ParsedTypeDecl>) {
        val loc = Loc(file = "<builtin>", startLine = 0, startCol = 0, endLine = 0, endCol = 0)
        fun payloadField(fieldName: String, typeParam: String) =
            ParsedRecordType(listOf(ParsedRecordTypeEntry(fieldName, ParsedNamedType(typeParam, loc), loc)), false, loc)
        fun syntheticVariant(name: String, typeParams: List<String>, ctors: List<ParsedVariantCtorDef>) {
            if (!typeDecls.containsKey(name)) {
                typeDecls[name] = ParsedTypeDecl(name, typeParams,
                    ParsedVariantDef(ctors, isOpen = false, loc = loc),
                    visibility = ParsedVisibility.PUBLIC, loc = loc)
            }
        }
        // Bool's declaration is what makes `True` and `False` RESOLVE — as
        // constructors, in an expression and in a pattern. It is not what decides
        // Bool's type: an annotation `Bool` is answered by `PrimitiveTypes.fromName`
        // with the canonical `BOOL_TYPE`, never by expanding this declaration.
        syntheticVariant(PrimitiveTypes.BOOL, emptyList(), listOf(
            ParsedVariantCtorDef(BuiltinTypes.TRUE, null, loc = loc),
            ParsedVariantCtorDef(BuiltinTypes.FALSE, null, loc = loc)))
        syntheticVariant(BuiltinTypes.OPTION, listOf("a"), listOf(
            ParsedVariantCtorDef(BuiltinTypes.SOME, payloadField(BuiltinTypes.VAL_FIELD, "a"), loc = loc),
            ParsedVariantCtorDef(BuiltinTypes.NONE, null, loc = loc)))
        syntheticVariant(BuiltinTypes.RESULT, listOf("a", "e"), listOf(
            ParsedVariantCtorDef(BuiltinTypes.OK, payloadField(BuiltinTypes.VAL_FIELD, "a"), loc = loc),
            ParsedVariantCtorDef(BuiltinTypes.ERR, payloadField(BuiltinTypes.ERROR_FIELD, "e"), loc = loc)))
    }

    private fun ensureContentHash(decl: ParsedImplAbilityDecl, @Suppress("UNUSED_PARAMETER") file: String): ParsedImplAbilityDecl {
        if (decl.contentHash != null) return decl
        return decl.copy(contentHash = ParsedDeclHasher.hashImplAbilityDecl(decl))
    }

    companion object {
        /**
         * `type X = <shape>` → parsed annotation for the RHS shape.
         * Newtypes and built-ins return null.
         */
        fun aliasedAnnotationForTypeDecl(decl: ParsedTypeDecl): ParsedTypeAnnotation? {
            if (decl.loc.file == "<builtin>") return null
            return when (val def = decl.definition) {
                is ParsedTypeAliasDef -> def.aliasedType
                is ParsedRecordDef -> ParsedRecordType(
                    entries = def.fields.map { f -> ParsedRecordTypeEntry(f.name, f.type, f.loc) },
                    isOpen = def.isOpen,
                    loc = def.loc,
                )
                is ParsedVariantDef -> ParsedVariantType(
                    constructors = def.constructors.map { c -> ParsedVariantTypeCtor(c.name, c.dataType, c.loc) },
                    isOpen = def.isOpen,
                    loc = def.loc,
                )
                is ParsedNewtypeDef -> null
            }
        }

        fun validateHermeticEffects(
            effects: Set<String>,
            effectHashes: Map<String, Hash> = emptyMap(),
            sourceLoc: Loc? = null
        ) {
            fun resolveHash(name: String): Hash? = effectHashes[name] ?: WellKnownHashConstants.resolveHash(name)

            val hermeticEffect = effects.firstOrNull { name ->
                WellKnownHashConstants.isMut(resolveHash(name))
            }
            if (hermeticEffect != null && effects.size > 1) {
                val otherEffects = effects.filter { name ->
                    !WellKnownHashConstants.isMut(resolveHash(name))
                }.joinToString(", ")
                throw TypeCheckError(
                    "$hermeticEffect effect is hermetic: cannot coexist with other effects ($otherEffects). " +
                        "$hermeticEffect must be the only effect in scope to prevent data races. " +
                        "Either handle the other effects before entering the $hermeticEffect scope, " +
                        "or restructure the code to separate $hermeticEffect from other effectful operations.",
                    sourceLoc,
                    code = ErrorCode.V0504_HERMETIC_EFFECT_CONFLICT,
                )
            }
        }
    }

    private fun collectVarTypesInOrder(type: MezzeType): List<TypeVar> {
        return collectTypeVarsInOrder(type, sortFields = false).distinct()
    }

    private fun mapVarTypes(type: MezzeType, transform: (TypeVar) -> MezzeType?): MezzeType {
        if (type is MezzeType.VarType) return transform(type.variable) ?: type
        return type.transformChildren { mapVarTypes(it, transform) }
    }

    private fun alignVarTypesByMapping(
        type: MezzeType,
        mapping: Map<TypeVar, TypeVar>
    ): MezzeType = mapVarTypes(type) { v -> mapping[v]?.let { MezzeType.VarType(it) } }

    private fun collectVarTypesByName(
        type: MezzeType,
        out: MutableMap<String, TypeVar>
    ) {
        for (tv in collectTypeVarsInOrder(type, sortFields = false)) {
            out.putIfAbsent(tv.name, tv)
        }
    }

    private fun alignVarTypesByName(
        type: MezzeType,
        byName: Map<String, TypeVar>
    ): MezzeType = mapVarTypes(type) { v -> byName[v.name]?.let { MezzeType.VarType(it) } }
}
