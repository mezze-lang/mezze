package com.mezze.language.typechecker.module

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility
import com.mezze.language.typechecker.ProgramIndex
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedModule as TypedAstModule
import com.mezze.language.typechecker.util.parsedUsePathToCore

class ImportResolver(
    private val loader: ModuleLoader,
    private val typeChecker: ModuleTypeChecker? = null
) {
    fun resolveImports(module: ParsedModule): ImportResolutionResult {
        val errors = mutableListOf<ModuleError>()
        var importContext = ModuleImportContext.empty()
        val loadedModules = mutableMapOf<ModulePath, TypedModuleResult>()

        for (decl in module.decls) {
            val (usePath, alias, isPublic, loc) = when (decl) {
                is ParsedUseDecl -> UseInfo(
                    parsedUsePathToCore(decl.path), decl.alias, false,
                    decl.loc
                )
                is ParsedPubUseDecl -> UseInfo(
                    parsedUsePathToCore(decl.path), decl.alias, true,
                    decl.loc
                )
                else -> continue
            }
            val result = processUseDeclaration(usePath, alias, isPublic, loc)
            when (result) {
                is ProcessResult.Success -> {
                    importContext = importContext.merge(result.context)
                    result.modules.forEach { loadedModules[ModulePath.parse(it.modulePath)] = it }
                }
                is ProcessResult.Failure -> errors.addAll(result.errors)
            }
        }

        return if (errors.isEmpty()) {
            ImportResolutionResult.Success(importContext, loadedModules)
        } else {
            ImportResolutionResult.Failure(errors)
        }
    }

    private data class UseInfo(
        val path: UsePath,
        val alias: String?,
        val isPublic: Boolean,
        val loc: Loc?
    )

    private fun processUseDeclaration(
        usePath: UsePath,
        alias: String?,
        isPublic: Boolean,
        sourceLoc: Loc?
    ): ProcessResult {
        return when (usePath) {
            is ModulePath -> {
                processModulePath(usePath, alias, isPublic, sourceLoc)
            }
            is SelectivePath -> {
                processSelectivePath(usePath, isPublic, sourceLoc)
            }
        }
    }

    private fun processModulePath(
        path: ModulePath,
        alias: String?,
        isPublic: Boolean,
        sourceLoc: Loc? = null
    ): ProcessResult {
        if (!loader.getResolver().canAccessInternal(null, path)) {
            return ProcessResult.Failure(
                listOf(ModuleError.InternalModuleAccess("", path.fullPath, sourceLoc))
            )
        }

        val loadResult = loader.loadModule(path)
        val modulePath = path.fullPath

        return when (loadResult) {
            is ModuleLoadResult.Success -> {
                val module = loadResult.module

                val typedModule = typeChecker?.let { checker ->
                    val typeCheckResult = checker.typeCheckModule(module)
                    when (typeCheckResult) {
                        is ModuleTypeCheckResult.Success -> typeCheckResult.module
                        is ModuleTypeCheckResult.Failure -> {
                            return ProcessResult.Failure(
                                typeCheckResult.errors.map { error ->
                                    ModuleError.ImportError(
                                        modulePath = modulePath,
                                        itemName = "*",
                                        reason = error.message
                                    )
                                }
                            )
                        }
                    }
                }

                val qualifierName = alias ?: path.segments.lastOrNull()

                val ownExports = typedModule?.exports?.takeIf { it.isNotEmpty() } ?: module.exports
                // What the module re-exports is part of its surface: `use mid` then
                // `mid::greet` must see what `use mid::{ greet }` sees. The module's
                // OWN exports win on a name clash — a module that declares `greet`
                // and also re-exports one means its own.
                val reexports = reexportedExports(module, mutableSetOf())
                    .mapValues { (_, v) -> v.second }
                val exportsToUse = reexports + ownExports

                var context = buildContextFromExports(
                    exports = exportsToUse,
                    modulePath = modulePath,
                    alias = qualifierName,
                    isQualified = true
                )

                // `xs.length {}` render as `'a`. (See issue #213.)
                // Named `typedAst` rather than `module`: a LoadedModule called
                // `module` is already in scope here, and the two are different
                // things — the loaded module and the typed AST inside its result.
                // Kept with a cached module's interface; harvested from the typed
                // AST otherwise. Either way the same facts.
                val ops = typedModule?.inherentOps
                    ?: typedModule?.typedModule?.let { harvestInherentImplOps(it, exportsToUse) }
                if (ops != null) {
                    val opTypes = ops.returnTypes
                    if (opTypes.isNotEmpty()) {
                        context = context.copy(inherentImplOpReturnTypes = opTypes)
                    }
                    // Same harvest, but for param-record types (#230). Without
                    // this, `nums.all { p = 5 }` typechecks because `p` was
                    // registered as a free TypeVar — anything unifies.
                    val opParamTypes = ops.paramTypes
                    if (opParamTypes.isNotEmpty()) {
                        context = context.copy(inherentImplOpParamTypes = opParamTypes)
                    }
                    // #181: harvest effect rows so impl methods whose
                    // bodies perform effects keep that effect-row across
                    // the module boundary.
                    val opEffects = ops.effects
                    if (opEffects.isNotEmpty()) {
                        context = context.copy(inherentImplOpEffects = opEffects)
                    }
                    // Harvest inferred ability constraints (`with FromJson 'a Str`,
                    // etc.) so a `.decode {}`-style target-directed op re-surfaces
                    // them at the call site (else the decode target never pins).
                    val opConstraints = ops.constraints
                    if (opConstraints.isNotEmpty()) {
                        context = context.copy(inherentImplOpConstraints = opConstraints)
                    }
                }

                ProcessResult.Success(
                    context = context,
                    module = typedModule
                )
            }
            is ModuleLoadResult.Failure -> {
                ProcessResult.Failure(loadResult.errors)
            }
        }
    }

    private fun processSelectivePath(
        path: SelectivePath,
        isPublic: Boolean,
        sourceLoc: Loc? = null
    ): ProcessResult {
        val structuredPath = ModulePath(path.module)

        if (!loader.getResolver().canAccessInternal(null, structuredPath)) {
            return ProcessResult.Failure(
                listOf(ModuleError.InternalModuleAccess("", structuredPath.fullPath, sourceLoc))
            )
        }

        val loadResult = loader.loadModule(structuredPath)
        val modulePath = structuredPath.fullPath

        return when (loadResult) {
            is ModuleLoadResult.Success -> {
                val module = loadResult.module

                val typedModule = typeChecker?.let { checker ->
                    val typeCheckResult = checker.typeCheckModule(module)
                    when (typeCheckResult) {
                        is ModuleTypeCheckResult.Success -> typeCheckResult.module
                        is ModuleTypeCheckResult.Failure -> {
                            return ProcessResult.Failure(
                                typeCheckResult.errors.map { error ->
                                    ModuleError.ImportError(
                                        modulePath = modulePath,
                                        itemName = path.items.joinToString(", ") { it.name },
                                        reason = error.message
                                    )
                                }
                            )
                        }
                    }
                }

                val exportsToUse = typedModule?.exports?.takeIf { it.isNotEmpty() } ?: module.exports

                var context = buildContextFromSelectiveImports(
                    exports = exportsToUse,
                    modulePath = modulePath,
                    items = path.items
                )

                // dispatch produces a bare TypeVar. (Same fix as #213, extended
                // to selective imports for #175.)
                // Kept with a cached module's interface; harvested from the typed
                // AST otherwise. Either way the same facts.
                val ops = typedModule?.inherentOps
                    ?: typedModule?.typedModule?.let { harvestInherentImplOps(it, exportsToUse) }
                if (ops != null) {
                    val opTypes = ops.returnTypes
                    if (opTypes.isNotEmpty()) {
                        context = context.copy(inherentImplOpReturnTypes = opTypes)
                    }
                    val opParamTypes = ops.paramTypes
                    if (opParamTypes.isNotEmpty()) {
                        context = context.copy(inherentImplOpParamTypes = opParamTypes)
                    }
                    // #181: harvest effect rows (selective-import path).
                    val opEffects = ops.effects
                    if (opEffects.isNotEmpty()) {
                        context = context.copy(inherentImplOpEffects = opEffects)
                    }
                    val opConstraints = ops.constraints
                    if (opConstraints.isNotEmpty()) {
                        context = context.copy(inherentImplOpConstraints = opConstraints)
                    }
                }

                val errors = mutableListOf<ModuleError>()

                // #228: items this module re-exports via `pub use` are not in its own
                // `exports` map (that map holds only its OWN declarations). Resolve them
                // transitively and fold them into the import context so a downstream
                // `use mid::{ greet }` sees `greet` that `mid` re-exports from `inner`.
                val reexportedBindings = mutableMapOf<String, ImportedBinding>()
                // #275: a re-exported ABILITY/EFFECT needs more than a binding — its
                // DECL (+ ops, + impls) must reach the consumer's registries, else
                // `Ability.method` / `Effect.op` fails ("Cannot access field … on
                // variant type"). We read them from the ORIGIN module's index (the
                // intermediary that re-exports it doesn't declare it).
                val reexportedAbilities = mutableMapOf<String, ParsedAbilityDecl>()
                val reexportedEffects = mutableMapOf<String, ParsedEffectDecl>()
                // A re-exported TYPE needs its DECL too, for the same reason an
                // ability does: a binding alone gives the consumer a name with no
                // declaration behind it, so `pub use inner::{ Color }` then
                // `use mid::{ Color }` failed with "unknown type 'Color'". Its
                // module's inherent impls come along, so `b.bump {}` on a
                // re-exported type dispatches instead of reading a record field.
                val reexportedTypes = mutableMapOf<String, ParsedTypeDecl>()
                val reexportedInherentImpls = mutableMapOf<Hash, ParsedImplAbilityDecl>()
                val reexportedAbilitiesByHash = mutableMapOf<Hash, ParsedAbilityDecl>()
                val reexportedEffectsByHash = mutableMapOf<Hash, ParsedEffectDecl>()
                val reexportedMetadata = mutableMapOf<Hash, com.mezze.language.parsedast.ParsedMetadata>()
                val reexportedAbilityImpls = mutableMapOf<Hash, ParsedImplAbilityDecl>()
                // A PERFORMER is not an export. `ExportedItemType` has no PERFORMER
                // case, so a performer never appears in `module.exports` and the
                // export-keyed re-export lookup below cannot see one. It reaches a
                // DIRECT importer through a separate channel (`importedImpls`,
                // built from `index.performersByName`), and that channel had no
                // re-export equivalent — so a facade carried the effect and
                // silently dropped its performers.
                val reexportedPerformers = mutableMapOf<String, ParsedPerformerDecl>()

                for (item in path.items) {
                    if (!module.exports.containsKey(item.name)) {
                        val reexport = resolvePubUseReexport(module, item.name, mutableSetOf())
                        if (reexport != null) {
                            val (originModulePath, export) = reexport
                            val localName = item.alias ?: item.name
                            reexportedBindings[localName] = ImportedBinding(
                                name = localName,
                                originalName = export.originalName,
                                modulePath = originModulePath,
                                type = export.mezzeType,
                                isQualified = false,
                                contentHash = export.contentHash
                            )
                            // #275: fold the re-exported ability/effect decl (and, for
                            // an ability, every impl the origin knows) into the context
                            // so its operations dispatch downstream.
                            val originModule = loader.getModule(originModulePath)
                            originModule?.let { exportMetadata(it)[export.contentHash] }
                                ?.let { reexportedMetadata[export.contentHash] = it }
                            // A PERFORMER is exported as `ExportedItemType.FUNCTION`
                            // (there is no PERFORMER kind), so the `when` below cannot
                            // recognise one and dropped it through `else -> {}`. The
                            // consequence was silent and far away: a facade carried the
                            // effect, lost its performers, and `perform X` then failed
                            // at the consumer with "the body performs <E> but the
                            // annotation doesn't allow it" — pointing at the annotation
                            // rather than at the missing performer.
                            //
                            // Ask the ORIGIN whether the name is a performer instead of
                            // asking the export what kind it is.
                            originModule?.index?.performersByName?.get(export.originalName)?.let { perf ->
                                reexportedPerformers[localName] = perf
                                // Register the performer's EFFECT by hash, not by name,
                                // so `perform X { field = … }` can type-check its config
                                // record while the effect stays un-referenceable without
                                // its own import. Same reason the direct path gives:
                                // without it the config subtree is skipped, no codec is
                                // derived, and it fails at runtime with
                                // `Field not found: encode`.
                                originModule.index.effectDeclarations[perf.effect.localName]?.let { eff ->
                                    eff.contentHash?.let { h ->
                                        if (h !in reexportedEffectsByHash) reexportedEffectsByHash[h] = eff
                                    }
                                }
                            }
                            when (export.type) {
                                ExportedItemType.ABILITY -> {
                                    originModule?.index?.abilityDeclarations?.get(export.originalName)?.let { ab ->
                                        reexportedAbilities[localName] = ab
                                        ab.contentHash?.let { reexportedAbilitiesByHash[it] = ab }
                                    }
                                    // Impls of a re-exported ability are keyed by hash,
                                    // not name — carry them all so use-site dispatch finds them.
                                    originModule?.index?.abilityImplsByHash?.let { reexportedAbilityImpls.putAll(it) }
                                }
                                ExportedItemType.EFFECT -> {
                                    originModule?.index?.effectDeclarations?.get(export.originalName)?.let { eff ->
                                        reexportedEffects[localName] = eff
                                        eff.contentHash?.let { reexportedEffectsByHash[it] = eff }
                                        // Importing an effect carries every performer OF
                                        // that effect — the same rule the direct path
                                        // applies via `index.performersByEffect`. Without
                                        // it `perform SomePerformer` through a facade does
                                        // not discharge, and the failure surfaces far away
                                        // as "the body performs <E> but the annotation
                                        // doesn't allow it".
                                        for (perf in originModule.index.performersByEffect[eff.name] ?: emptyList()) {
                                            reexportedPerformers[perf.performerName] = perf
                                        }
                                    }
                                }
                                // A CONSTRUCTOR export is the same case: a variant whose
                                // constructor shares the type's name (`type Box = | Box
                                // { n: Int } |`) exports as CONSTRUCTOR, not TYPE, and
                                // without its decl the consumer's `b: Box` resolved to
                                // the payload record — `b.bump {}` then read a field
                                // instead of dispatching ("Available fields: 'n'"). The
                                // direct import path already handles both the same way.
                                ExportedItemType.TYPE, ExportedItemType.CONSTRUCTOR -> {
                                    originModule?.index?.typeDeclarations?.get(export.originalName)?.let { td ->
                                        reexportedTypes[localName] = td
                                    }
                                    // An inherent `impl <Type> where { … }` lives in the
                                    // ORIGIN module and is keyed by hash, so carry them
                                    // all — the same rule the ability arm uses.
                                    originModule?.index?.abilityImplsByHash?.let { reexportedInherentImpls.putAll(it) }
                                }
                                else -> {}
                            }
                            continue
                        }

                        val existsButPrivate = checkIfItemExistsButPrivate(module, item.name)

                        if (existsButPrivate) {
                            errors.add(ModuleError.PrivateExport(
                                modulePath = modulePath,
                                exportName = item.name,
                                loc = sourceLoc
                            ))
                        } else {
                            val availableExports = module.exports.keys.toList().sorted()
                            errors.add(ModuleError.ExportNotFound(
                                modulePath = modulePath,
                                exportName = item.name,
                                loc = sourceLoc,
                                availableExports = availableExports
                            ))
                        }
                    }
                }

                if (errors.isNotEmpty()) {
                    return ProcessResult.Failure(errors)
                }

                if (reexportedBindings.isNotEmpty() || reexportedAbilities.isNotEmpty() ||
                    reexportedEffects.isNotEmpty() || reexportedTypes.isNotEmpty() ||
                    reexportedPerformers.isNotEmpty()
                ) {
                    context = context.merge(
                        ModuleImportContext.empty().copy(
                            importedBindings = reexportedBindings,
                            importedTypes = reexportedTypes,
                            // A type named in a `use … { … }` group is EXPLICIT, and
                            // a re-exported one is no less so — it must act as the
                            // same nominal wall (#148) as a directly imported one.
                            explicitlyImportedTypeNames = reexportedTypes.keys,
                            importedAbilities = reexportedAbilities,
                            importedEffects = reexportedEffects,
                            importedAbilityImpls = reexportedAbilityImpls + reexportedInherentImpls,
                            abilitiesByHash = reexportedAbilitiesByHash,
                            effectsByHash = reexportedEffectsByHash,
                            importedImpls = reexportedPerformers,
                            metadataByHash = reexportedMetadata,
                        )
                    )
                }

                ProcessResult.Success(
                    context = context,
                    module = typedModule
                )
            }
            is ModuleLoadResult.Failure -> {
                // #340: `use std::collections::{ map, seq }` names a directory-only
                // path — `std::collections` has no module file, it is a folder of
                // submodules — so the load fails with a bare "Module not found".
                // Every grouped item is a submodule here, and the group means the
                // same as one `use` per submodule.
                val submodulePaths = path.items.associateWith { ModulePath(path.module + it.name) }
                    .filterValues { loader.loadModule(it) is ModuleLoadResult.Success }
                if (submodulePaths.size == path.items.size && path.items.isNotEmpty()) {
                    return processSubmoduleGroup(submodulePaths, isPublic, sourceLoc)
                }
                // A MIXED group: the path has no module file, so the items that are
                // not submodules cannot resolve to anything. Name them — the group
                // is not the problem, those items are.
                if (submodulePaths.isNotEmpty()) {
                    val unknown = path.items.filter { it !in submodulePaths }.joinToString(", ") { it.name }
                    return ProcessResult.Failure(listOf(ModuleError.ImportError(
                        modulePath = modulePath,
                        itemName = unknown,
                        reason = "'$modulePath' is a folder of submodules, not a module — it declares " +
                            "nothing itself, so it cannot export $unknown. The other names in this " +
                            "group are submodules and resolve; these do not."
                    )))
                }
                ProcessResult.Failure(loadResult.errors)
            }
        }
    }

    /**
     * `use a::{ b, c }` where b and c are SUBMODULES.
     *
     * Exactly `use a::b` + `use a::c`: each is imported QUALIFIED under its own
     * last segment (or its alias), so the group is a spelling, not a second
     * meaning. Each submodule is processed by the same [processModulePath] a
     * single-line `use` goes through, and the contexts merge in written order.
     *
     * This used to be an error that told the reader to split the group by hand.
     */
    private fun processSubmoduleGroup(
        submodulePaths: Map<ImportItem, ModulePath>,
        isPublic: Boolean,
        sourceLoc: Loc?,
    ): ProcessResult {
        var merged = ModuleImportContext.empty()
        val loaded = mutableListOf<TypedModuleResult>()
        val errors = mutableListOf<ModuleError>()
        for ((item, submodule) in submodulePaths) {
            when (val r = processModulePath(submodule, item.alias, isPublic, sourceLoc)) {
                is ProcessResult.Success -> {
                    merged = merged.merge(r.context)
                    loaded.addAll(r.modules)
                }
                is ProcessResult.Failure -> errors.addAll(r.errors)
            }
        }
        return if (errors.isEmpty()) ProcessResult.Success(merged, loaded)
        else ProcessResult.Failure(errors)
    }

    private fun buildContextFromExports(
        exports: Map<String, ExportedItem>,
        modulePath: String,
        alias: String?,
        isQualified: Boolean
    ): ModuleImportContext {
        val bindings = mutableMapOf<String, ImportedBinding>()
        val types = mutableMapOf<String, ParsedTypeDecl>()
        // Used by the structural-alias check (#148) to decide whether a type name
        // acts as a nominal wall.
        val explicitTypeNames = mutableSetOf<String>()
        val abilities = mutableMapOf<String, ParsedAbilityDecl>()
        val effects = mutableMapOf<String, ParsedEffectDecl>()
        val impls = mutableMapOf<String, ParsedPerformerDecl>()
        val abilityImpls = mutableMapOf<Hash, ParsedImplAbilityDecl>()
        val effectsByHash = mutableMapOf<Hash, ParsedEffectDecl>()
        val abilitiesByHash = mutableMapOf<Hash, ParsedAbilityDecl>()

        val module = loader.getModule(modulePath)

        for ((name, export) in exports) {
            val bindingKey = if (isQualified && alias != null) {
                BindingKey.qualified(alias, name)
            } else {
                BindingKey.unqualified(name)
            }
            val localName = bindingKey.toKeyString()

            when (export.type) {
                ExportedItemType.FUNCTION -> {
                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.mezzeType,
                        isQualified = isQualified,
                        contentHash = export.contentHash
                    )
                }
                ExportedItemType.TYPE -> {
                    val typeDecl = module?.index?.typeDeclarations?.get(export.originalName)

                    if (typeDecl != null) {
                        types[export.originalName] = typeDecl
                        // This is an explicit `use foo::{TypeName}` — record it for #148.
                        explicitTypeNames.add(export.originalName)
                    }

                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.mezzeType,
                        isQualified = isQualified,
                        contentHash = export.contentHash
                    )
                }
                ExportedItemType.CONSTRUCTOR -> {
                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.mezzeType,
                        isQualified = isQualified,
                        contentHash = export.contentHash
                    )
                    val typeDecl = module?.index?.typeDeclarations?.get(export.originalName)
                    if (typeDecl != null) {
                        types[export.originalName] = typeDecl
                        // constructor brings the type name into scope as well (#148).
                        explicitTypeNames.add(export.originalName)
                    }
                }
                ExportedItemType.ABILITY -> {
                    val abilityDecl = module?.index?.abilityDeclarations?.get(export.originalName)

                    if (abilityDecl != null) {
                        abilities[localName] = abilityDecl
                        abilityDecl.contentHash?.let { hash ->
                            abilitiesByHash[hash] = abilityDecl
                        }
                    }

                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.mezzeType,
                        isQualified = isQualified,
                        contentHash = export.contentHash
                    )
                }
                ExportedItemType.EFFECT -> {
                    val effectDecl = module?.index?.effectDeclarations?.get(export.originalName)

                    if (effectDecl != null) {
                        effects[localName] = effectDecl
                        effectDecl.contentHash?.let { hash ->
                            effectsByHash[hash] = effectDecl
                        }
                    }

                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.mezzeType,
                        isQualified = isQualified,
                        contentHash = export.contentHash
                    )
                }
            }
        }

        // #183 follow-up: also collect from inherent impl method types. When the
        // user imports `client` from std::effects::http but doesn't explicitly
        // import `Request`, a chain like `client {}.get { path }.headers` needs
        // `Request` in scope so HasField can expand the TypeApplication.
        // Without this, the solver reports "Cannot expand type application
        // Request to check field 'headers'".
        if (module != null) {
            val referencedTypes = mutableSetOf<String>()
            for ((_, binding) in bindings) {
                collectTypeApplicationConstructors(binding.type, referencedTypes)
            }
            for ((_, implDecl) in module.index.abilityImplsByHash) {
                for (targetAnn in implDecl.targetTypes) {
                    collectTypeAnnotationConstructors(targetAnn, referencedTypes)
                }
                for (op in implDecl.operations) {
                    op.paramType?.let { collectTypeAnnotationConstructors(it, referencedTypes) }
                    op.returnType?.let { collectTypeAnnotationConstructors(it, referencedTypes) }
                }
            }
            for (typeName in referencedTypes) {
                if (typeName !in types) {
                    module.index.typeDeclarations[typeName]?.let { typeDecl ->
                        types[typeName] = typeDecl
                    }
                }
            }
        }

        if (module != null) {
            for ((performerName, perf) in module.index.performersByName) {
                impls[performerName] = perf
                if (isQualified && alias != null) {
                    val qualifiedKey = BindingKey.qualified(alias, performerName)
                    impls[qualifiedKey.toKeyString()] = perf
                }
            }

            abilityImpls.putAll(module.index.abilityImplsByHash)
        }

        return ModuleImportContext(
            importedBindings = bindings,
            importedTypes = types,
            explicitlyImportedTypeNames = explicitTypeNames,
            importedAbilities = abilities,
            importedEffects = effects,
            importedImpls = impls,
            importedAbilityImpls = abilityImpls,
            effectsByHash = effectsByHash,
            abilitiesByHash = abilitiesByHash,
            abilityDeclHashByImpl = module?.let { abilityIdentityForImpls(it.index) } ?: emptyMap(),
            metadataByHash = module?.let { exportMetadata(it) } ?: emptyMap(),
        )
    }

    /**
     * The `#{ … }` metadata of everything [module] exports, by the exported
     * declaration's identity. Read off the module's own parsed declarations, where
     * the author wrote it.
     */
    private fun exportMetadata(module: LoadedModule): Map<Hash, com.mezze.language.parsedast.ParsedMetadata> {
        val index = module.index
        return buildMap {
            for (export in module.exports.values) {
                val name = export.originalName
                val metadata = when (export.type) {
                    ExportedItemType.FUNCTION ->
                        index.letBindings[name]?.metadata ?: index.performersByName[name]?.metadata
                    ExportedItemType.TYPE, ExportedItemType.CONSTRUCTOR -> index.typeDeclarations[name]?.metadata
                    ExportedItemType.ABILITY -> index.abilityDeclarations[name]?.metadata
                    ExportedItemType.EFFECT -> index.effectDeclarations[name]?.metadata
                } ?: continue
                put(export.contentHash, metadata)
            }
        }
    }

    private fun buildContextFromSelectiveImports(
        exports: Map<String, ExportedItem>,
        modulePath: String,
        items: List<ImportItem>
    ): ModuleImportContext {
        val bindings = mutableMapOf<String, ImportedBinding>()
        val types = mutableMapOf<String, ParsedTypeDecl>()
        // Used by the structural-alias check (#148) to decide whether a type name
        // acts as a nominal wall.
        val explicitTypeNames = mutableSetOf<String>()
        val abilities = mutableMapOf<String, ParsedAbilityDecl>()
        val effects = mutableMapOf<String, ParsedEffectDecl>()
        val impls = mutableMapOf<String, ParsedPerformerDecl>()
        val abilityImpls = mutableMapOf<Hash, ParsedImplAbilityDecl>()
        val effectsByHash = mutableMapOf<Hash, ParsedEffectDecl>()
        val abilitiesByHash = mutableMapOf<Hash, ParsedAbilityDecl>()

        val module = loader.getModule(modulePath)

        for (item in items) {
            val export = exports[item.name] ?: continue

            val localName = item.alias ?: item.name

            when (export.type) {
                ExportedItemType.FUNCTION, ExportedItemType.CONSTRUCTOR -> {
                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.mezzeType,
                        isQualified = false,
                        contentHash = export.contentHash
                    )
                    if (export.type == ExportedItemType.CONSTRUCTOR) {
                        val typeDecl = module?.index?.typeDeclarations?.get(item.name)
                        if (typeDecl != null) {
                            types[localName] = typeDecl
                            // newtype-style constructor brings type name into scope (#148)
                            explicitTypeNames.add(localName)
                        }
                    }
                }
                ExportedItemType.TYPE -> {
                    val typeDecl = module?.index?.typeDeclarations?.get(item.name)
                    if (typeDecl != null) {
                        types[localName] = typeDecl
                        // Explicit `use foo::{TypeName}` (#148)
                        explicitTypeNames.add(localName)
                    }
                }
                ExportedItemType.ABILITY -> {
                    val abilityDecl = module?.index?.abilityDeclarations?.get(item.name)
                    if (abilityDecl != null) {
                        abilities[localName] = abilityDecl
                        abilityDecl.contentHash?.let { hash ->
                            abilitiesByHash[hash] = abilityDecl
                        }
                    }
                }
                ExportedItemType.EFFECT -> {
                    val effectDecl = module?.index?.effectDeclarations?.get(item.name)
                    if (effectDecl != null) {
                        effects[localName] = effectDecl
                        effectDecl.contentHash?.let { hash ->
                            effectsByHash[hash] = effectDecl
                        }
                    }
                }
            }
        }

        if (module != null) {
            val index = module.index

            for (item in items) {
                val effectDecl = index.effectDeclarations[item.name]

                if (effectDecl != null) {
                    val effectPerformers = index.performersByEffect[effectDecl.name] ?: emptyList()

                    for (perf in effectPerformers) {
                        impls[perf.performerName] = perf
                    }
                }

                val performerImpl = index.performersByName[item.name]
                if (performerImpl != null && !impls.containsKey(item.name)) {
                    impls[item.name] = performerImpl
                    // A performer is `impl SomeEffect for X`, and registering its
                    // PerformerTypeInfo (which is what lets a `perform X { … }`
                    // handler-config record be type-checked) requires the EFFECT
                    // decl to be in scope. But the user only imported the
                    // performer name — not the effect — so pull the effect decl
                    // out of the same module and register it by hash (not by
                    // name, so it stays un-referenceable without its own import).
                    // Without this, `perform X { field = … }` silently skips the
                    // whole config subtree: an ability obligation inside it (a
                    // `.encode {}` / `.to_json {}`) is never generated, so no
                    // codec is derived and it fails at runtime with
                    // `Field not found: encode`.
                    index.effectDeclarations[performerImpl.effect.localName]?.let { effectDecl ->
                        effectDecl.contentHash?.let { hash ->
                            if (hash !in effectsByHash) effectsByHash[hash] = effectDecl
                        }
                    }
                }
            }

            for (item in items) {
                val abilityDecl = index.abilityDeclarations[item.name]

                if (abilityDecl != null) {
                    val abilityImplsList = index.abilityImplsByAbility[abilityDecl.name] ?: emptyList()

                    for (abilityImpl in abilityImplsList) {
                        val implKey = abilityImpl.contentHash
                            ?: throw IllegalStateException(
                                "Content hash not computed for imported impl: ${abilityImpl.abilityName ?: "inherent"} ${abilityImpl.targetTypes}"
                            )
                        abilityImpls[implKey] = abilityImpl
                    }
                }
            }

            abilityImpls.putAll(index.abilityImplsByHash)
            // …and the impls of whatever this module re-exports. See
            // reexportedAbilityImpls for why a `pub use` has to carry them.
            module?.let { abilityImpls.putAll(reexportedAbilityImpls(it, mutableSetOf())) }

            // #183 follow-up: also collect from inherent impl op annotations
            // (param + return + impl target) so chain expressions like
            // `client {}.get { path }.headers` can expand `Request` even
            // when the user didn't explicitly import it.
            val referencedTypes = mutableSetOf<String>()
            for ((_, binding) in bindings) {
                collectTypeApplicationConstructors(binding.type, referencedTypes)
            }
            for ((_, implDecl) in index.abilityImplsByHash) {
                for (targetAnn in implDecl.targetTypes) {
                    collectTypeAnnotationConstructors(targetAnn, referencedTypes)
                }
                for (op in implDecl.operations) {
                    op.paramType?.let { collectTypeAnnotationConstructors(it, referencedTypes) }
                    op.returnType?.let { collectTypeAnnotationConstructors(it, referencedTypes) }
                }
            }
            for (typeName in referencedTypes) {
                if (typeName !in types) {
                    index.typeDeclarations[typeName]?.let { typeDecl ->
                        types[typeName] = typeDecl
                    }
                }
            }
        }

        return ModuleImportContext(
            importedBindings = bindings,
            importedTypes = types,
            explicitlyImportedTypeNames = explicitTypeNames,
            importedAbilities = abilities,
            importedEffects = effects,
            importedImpls = impls,
            importedAbilityImpls = abilityImpls,
            effectsByHash = effectsByHash,
            abilitiesByHash = abilitiesByHash,
            abilityDeclHashByImpl = module?.let { abilityIdentityForImpls(it.index) } ?: emptyMap(),
            metadataByHash = module?.let { exportMetadata(it) } ?: emptyMap(),
        )
    }

    /**
     * #228: resolve an item that `module` re-exports via `pub use`. A module's own
     * `exports` map holds only its declarations, so `pub use inner::{ greet }` leaves
     * `greet` invisible to importers. This walks `module`'s `pub use` decls: for each
     * that could supply [wantedName] (a selective `pub use src::{ … greet … }`, or a
     * bare `pub use src` glob), it loads `src`, resolves the item there (recursing so
     * re-export chains work), and returns the (origin module path, export). `visited`
     * breaks `pub use` cycles. Returns null if no re-export provides the name.
     */
    private fun resolvePubUseReexport(
        module: LoadedModule,
        wantedName: String,
        visited: MutableSet<String>,
    ): Pair<String, ExportedItem>? = reexportedExports(module, visited)[wantedName]

    /**
     * Everything [module] re-exports via `pub use`, as localName -> (origin module
     * path, the origin's export).
     *
     * One walk for both questions asked of a re-export: "does this module supply
     * NAME?" (a selective `use mid::{ greet }`) and "what does this module supply?"
     * (a qualified `use mid` that then writes `mid::greet`). The second used to have
     * no answer at all — `processModulePath` built its namespace from the module's
     * OWN exports, so `mid::greet` was an undefined variable while
     * `use mid::{ greet }` resolved. A re-export is part of a module's surface in
     * both spellings or in neither.
     *
     * A selective `pub use src::{ a, b as c }` supplies `a` and `c`; a glob
     * `pub use src` supplies every one of src's exports under its own name. Chains
     * are followed, and [visited] breaks `pub use` cycles.
     */
    private fun reexportedExports(
        module: LoadedModule,
        visited: MutableSet<String>,
    ): Map<String, Pair<String, ExportedItem>> {
        if (!visited.add(module.modulePath)) return emptyMap()
        val out = mutableMapOf<String, Pair<String, ExportedItem>>()
        for (pubUse in module.index.pubUseDeclarations) {
            val srcSegments = when (val p = pubUse.path) {
                is ParsedUsePath.Selective -> p.modulePath.segments
                is ParsedUsePath.Module -> p.path.segments
            }
            val srcModule = when (val r = loader.loadModule(ModulePath(srcSegments))) {
                is ModuleLoadResult.Success -> r.module
                is ModuleLoadResult.Failure -> continue
            }
            // What the source supplies, its own exports first and then whatever it
            // re-exports in turn.
            val fromSource: Map<String, Pair<String, ExportedItem>> =
                srcModule.exports.mapValues { (_, e) -> srcModule.modulePath to e } +
                    reexportedExports(srcModule, visited)

            when (val p = pubUse.path) {
                is ParsedUsePath.Selective ->
                    for (item in p.items) {
                        fromSource[item.name]?.let { out[item.alias ?: item.name] = it }
                    }
                is ParsedUsePath.Module -> out.putAll(fromSource)
            }
        }
        return out
    }

    /**
     * The ability impls of every module [module] re-exports, transitively.
     *
     * A plain `use some::module::{ anything }` carries that module's OWN impls
     * wholesale, and it has to: an impl is not a named item anyone can ask for.
     * It is the reason a constraint can be discharged, and a constraint is
     * discharged at the CALL SITE — so the caller is who needs it.
     *
     * A `pub use`d impl travelled with nothing. The re-export path carries
     * impls only for items the consumer happens to NAME, so a facade module
     * could re-export an ability and still leave its consumers unable to use
     * it. `http.mz` does exactly that with `ToQuery` for `with_query`, and
     * `use std::effects::http::{ request }` then failed with "`ToQuery` isn't
     * imported here" — the module had said "get it from me" and could not
     * deliver.
     *
     * A module that writes `pub use` is asking for its source's surface to be
     * part of its own, and the instances are part of that surface. [visited]
     * breaks `pub use` cycles, keyed apart from the name walk's own marks.
     */
    private fun reexportedAbilityImpls(
        module: LoadedModule,
        visited: MutableSet<String>,
    ): Map<Hash, ParsedImplAbilityDecl> {
        if (!visited.add("impls:${module.modulePath}")) return emptyMap()
        val out = mutableMapOf<Hash, ParsedImplAbilityDecl>()
        for (pubUse in module.index.pubUseDeclarations) {
            val srcSegments = when (val p = pubUse.path) {
                is ParsedUsePath.Selective -> p.modulePath.segments
                is ParsedUsePath.Module -> p.path.segments
            }
            val srcModule = when (val r = loader.loadModule(ModulePath(srcSegments))) {
                is ModuleLoadResult.Success -> r.module
                is ModuleLoadResult.Failure -> continue
            }
            out.putAll(srcModule.index.abilityImplsByHash)
            out.putAll(reexportedAbilityImpls(srcModule, visited))
        }
        return out
    }

    private fun checkIfItemExistsButPrivate(module: LoadedModule, itemName: String): Boolean {
        val index = module.index

        index.letBindings[itemName]?.let { binding ->
            return binding.visibility != Visibility.PUBLIC
        }

        index.typeDeclarations[itemName]?.let { typeDecl ->
            return typeDecl.visibility != Visibility.PUBLIC
        }

        if (index.abilityDeclarations.containsKey(itemName)) {
            return false
        }

        if (index.effectDeclarations.containsKey(itemName)) {
            return false
        }

        return false
    }

    private fun collectTypeApplicationConstructors(type: MezzeType?, result: MutableSet<String>) {
        when (type) {
            null -> {}
            is MezzeType.TypeApplication -> {
                result.add(type.constructor)
                type.args.forEach { collectTypeApplicationConstructors(it, result) }
            }
            is MezzeType.FunctionType -> {
                collectTypeApplicationConstructors(type.paramType, result)
                collectTypeApplicationConstructors(type.returnType, result)
            }
            is MezzeType.RecordType -> {
                type.fields.values.forEach { collectTypeApplicationConstructors(it, result) }
            }
            is MezzeType.VariantType -> {
                type.constructors.values.forEach { collectTypeApplicationConstructors(it, result) }
            }
            is MezzeType.EffectRow -> {
            }
            is MezzeType.NewtypeType -> {
                collectTypeApplicationConstructors(type.wrappedType, result)
            }
            is MezzeType.VarType,
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.FracType,
            is MezzeType.StringType,
            is MezzeType.BytesType,
            is MezzeType.CharType -> {}
        }
    }

    private fun collectTypeAnnotationConstructors(
        ann: ParsedTypeAnnotation?,
        result: MutableSet<String>
    ) {
        when (ann) {
            null -> {}
            is ParsedNamedType -> {
                if (ann.name.isNotEmpty() && ann.name[0].isUpperCase()) result.add(ann.name)
            }
            is ParsedQualifiedType -> {
                result.add(ann.typeName)
                ann.args.forEach { collectTypeAnnotationConstructors(it, result) }
            }
            is ParsedTypeApp -> {
                if (ann.constructor.isNotEmpty() && ann.constructor[0].isUpperCase()) result.add(ann.constructor)
                ann.args.forEach { collectTypeAnnotationConstructors(it, result) }
            }
            is ParsedRecordType -> {
                ann.entries.forEach { collectTypeAnnotationConstructors(it.type, result) }
            }
            is ParsedVariantType -> {
                ann.constructors.forEach { collectTypeAnnotationConstructors(it.dataType, result) }
            }
            is ParsedFunctionType -> {
                collectTypeAnnotationConstructors(ann.paramType, result)
                collectTypeAnnotationConstructors(ann.returnType, result)
            }
            is ParsedSeqType -> {
                collectTypeAnnotationConstructors(ann.elementType, result)
            }
        }
    }

    private sealed class ProcessResult {
        data class Success(
            val context: ModuleImportContext,
            /**
             * Every module this `use` loaded. A list because one `use` can load
             * several: `use a::{ b, c }` where b and c are SUBMODULES is the same
             * import as two `use` lines, so it loads two modules.
             */
            val modules: List<TypedModuleResult> = emptyList()
        ) : ProcessResult() {
            constructor(context: ModuleImportContext, module: TypedModuleResult?) :
                this(context, listOfNotNull(module))
        }

        data class Failure(
            val errors: List<ModuleError>
        ) : ProcessResult()
    }

}

/**
 * Which ability each of a module's impls implements, keyed by the impl's own
 * content hash.
 *
 * Resolved HERE because this is the last point where the answer is unambiguous:
 * inside one module `impl Show for B` can only mean that module's `Show`.
 * Downstream, every module's impls are merged into one flat map and the name
 * means nothing.
 *
 * ONLY the stamped hash. Recomputing one for an unstamped declaration was tried
 * and reverted: the recomputed value does not always equal what the registry
 * stamped, and an impl tagged with a hash nobody else agrees on stops matching
 * its own ability (`impl Default for Int` and `for Bool` vanished that way). An
 * impl whose module merely IMPORTED the ability is left untagged, which is what
 * it had before.
 */
private fun abilityIdentityForImpls(index: ProgramIndex): Map<Hash, Hash> = buildMap {
    for ((implKey, implDecl) in index.abilityImplsByHash) {
        if (implDecl.isInherent) continue
        val name = implDecl.abilityName ?: continue
        val declHash = index.abilityDeclarations[name]?.contentHash ?: continue
        put(implKey, declHash)
    }
}
