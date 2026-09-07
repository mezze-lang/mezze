package com.mezze.language.typechecker.module

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility
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
                    result.module?.let { loadedModules[ModulePath.parse(it.modulePath)] = it }
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

                val exportsToUse = typedModule?.exports?.takeIf { it.isNotEmpty() } ?: module.exports

                var context = buildContextFromExports(
                    exports = exportsToUse,
                    modulePath = modulePath,
                    alias = qualifierName,
                    isQualified = true
                )

                // `xs.length {}` render as `'a`. (See issue #213.)
                val v2Module = typedModule?.typedModule
                if (v2Module != null) {
                    val opTypes = harvestInherentImplOpReturnTypes(v2Module, exportsToUse)
                    if (opTypes.isNotEmpty()) {
                        context = context.copy(inherentImplOpReturnTypes = opTypes)
                    }
                    // Same harvest, but for param-record types (#230). Without
                    // this, `nums.all { p = 5 }` typechecks because `p` was
                    // registered as a free TypeVar — anything unifies.
                    val opParamTypes = harvestInherentImplOpParamTypes(v2Module, exportsToUse)
                    if (opParamTypes.isNotEmpty()) {
                        context = context.copy(inherentImplOpParamTypes = opParamTypes)
                    }
                    // #181: harvest effect rows so impl methods whose
                    // bodies perform effects keep that effect-row across
                    // the module boundary.
                    val opEffects = harvestInherentImplOpEffects(v2Module)
                    if (opEffects.isNotEmpty()) {
                        context = context.copy(inherentImplOpEffects = opEffects)
                    }
                    // Harvest inferred ability constraints (`with FromJson 'a Str`,
                    // etc.) so a `.decode {}`-style target-directed op re-surfaces
                    // them at the call site (else the decode target never pins).
                    val opConstraints = harvestInherentImplOpConstraints(v2Module)
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
                val v2ModuleSel = typedModule?.typedModule
                if (v2ModuleSel != null) {
                    val opTypes = harvestInherentImplOpReturnTypes(v2ModuleSel, exportsToUse)
                    if (opTypes.isNotEmpty()) {
                        context = context.copy(inherentImplOpReturnTypes = opTypes)
                    }
                    val opParamTypes = harvestInherentImplOpParamTypes(v2ModuleSel, exportsToUse)
                    if (opParamTypes.isNotEmpty()) {
                        context = context.copy(inherentImplOpParamTypes = opParamTypes)
                    }
                    // #181: harvest effect rows (selective-import path).
                    val opEffects = harvestInherentImplOpEffects(v2ModuleSel)
                    if (opEffects.isNotEmpty()) {
                        context = context.copy(inherentImplOpEffects = opEffects)
                    }
                    val opConstraints = harvestInherentImplOpConstraints(v2ModuleSel)
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
                val reexportedAbilitiesByHash = mutableMapOf<Hash, ParsedAbilityDecl>()
                val reexportedEffectsByHash = mutableMapOf<Hash, ParsedEffectDecl>()
                val reexportedAbilityImpls = mutableMapOf<Hash, ParsedImplAbilityDecl>()

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
                                    }
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

                if (reexportedBindings.isNotEmpty() || reexportedAbilities.isNotEmpty() || reexportedEffects.isNotEmpty()) {
                    context = context.merge(
                        ModuleImportContext.empty().copy(
                            importedBindings = reexportedBindings,
                            importedAbilities = reexportedAbilities,
                            importedEffects = reexportedEffects,
                            importedAbilityImpls = reexportedAbilityImpls,
                            abilitiesByHash = reexportedAbilitiesByHash,
                            effectsByHash = reexportedEffectsByHash,
                        )
                    )
                }

                ProcessResult.Success(
                    context = context,
                    module = typedModule
                )
            }
            is ModuleLoadResult.Failure -> {
                // #340: `use std::collections::{ map, seq }` names a directory-only path
                // (`std::collections` has no module file — it's a folder of submodules),
                // so the load fails with a bare "Module not found". If the grouped items
                // are actually SUBMODULES of that path, say so and show the real fix
                // instead of the generic error. (Grouped submodule imports aren't yet
                // supported — one `use` per submodule — but the diagnostic should teach
                // that rather than leave a newcomer guessing.)
                val submoduleItems = path.items.filter { item ->
                    loader.loadModule(ModulePath(path.module + item.name)) is ModuleLoadResult.Success
                }
                if (submoduleItems.isNotEmpty()) {
                    val names = submoduleItems.joinToString(", ") { it.name }
                    val perLine = submoduleItems.joinToString("\n   ") { "use ${modulePath}::${it.name}" }
                    val one = submoduleItems.size == 1
                    return ProcessResult.Failure(listOf(ModuleError.ImportError(
                        modulePath = modulePath,
                        itemName = names,
                        reason = "$names ${if (one) "is a submodule" else "are submodules"} of '$modulePath', " +
                            "not ${if (one) "an item" else "items"} it exports. A `{ … }` group selects " +
                            "declarations FROM one module; it can't group submodules. Import each on its own line:" +
                            "\n   $perLine"
                    )))
                }
                ProcessResult.Failure(loadResult.errors)
            }
        }
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
            abilitiesByHash = abilitiesByHash
        )
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
            abilitiesByHash = abilitiesByHash
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
    ): Pair<String, ExportedItem>? {
        if (!visited.add(module.modulePath)) return null

        for (pubUse in module.index.pubUseDeclarations) {
            // What does this `pub use` bring into scope, and under what local name?
            // For a Selective import the local name is the item's alias-or-name; the
            // ORIGINAL name in the source module is item.name. A bare Module glob
            // re-exports every source export under its own name.
            val (srcSegments, originalName) = when (val p = pubUse.path) {
                is ParsedUsePath.Selective -> {
                    val match = p.items.firstOrNull { (it.alias ?: it.name) == wantedName }
                        ?: continue
                    p.modulePath.segments to match.name
                }
                is ParsedUsePath.Module -> p.path.segments to wantedName
            }

            val srcModule = when (val r = loader.loadModule(ModulePath(srcSegments))) {
                is ModuleLoadResult.Success -> r.module
                is ModuleLoadResult.Failure -> continue
            }

            srcModule.exports[originalName]?.let { return srcModule.modulePath to it }
            // Chain: the source itself may re-export the item.
            resolvePubUseReexport(srcModule, originalName, visited)?.let { return it }
        }
        return null
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
            is MezzeType.ParametricBuiltin -> {
                type.typeArgs.forEach { collectTypeApplicationConstructors(it, result) }
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
            is MezzeType.BoolType,
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
            val module: TypedModuleResult? = null
        ) : ProcessResult()

        data class Failure(
            val errors: List<ModuleError>
        ) : ProcessResult()
    }

    private fun harvestInherentImplOpReturnTypes(
        v2Module: TypedAstModule,
        exports: Map<String, ExportedItem>
    ): Map<Hash, Map<String, MezzeType>> {
        val result = mutableMapOf<Hash, Map<String, MezzeType>>()
        for (impl in v2Module.abilityImpls) {
            if (impl.abilityHash != Hash.ZERO) continue
            val opMap = mutableMapOf<String, MezzeType>()
            for ((opName, opExpr) in impl.operations) {
                // helped for thin-wrapper impls that #215 strips, but
                // silently corrupted Stage B's chainable HTTP: `Client.get`
                // returns a Request record, while the exported `pub let get`
                // returns `Result Response HttpError`. Conflating them
                // typed `c.get { path }` as `Result …`, and the follow-up
                // `.send {}` reported "Result vs Request". Effects have the
                // same warning (#181); apply it to return types too.
                val bodyReturnType: MezzeType =
                    if (opExpr is TypedLambda) {
                        opExpr.body.type
                    } else when (val t = opExpr.type) {
                        is MezzeType.FunctionType -> t.returnType
                        else -> t
                    }

                // #215 case where TypedAstBuilder dropped polymorphic args
                // and the export is the only place the full signature
                // survives. Concrete record / variant / nominal body types
                // always win.
                val exportedFn = exports[opName]
                val exportFnType = exportedFn?.mezzeType as? MezzeType.FunctionType
                val exportReturnType = exportFnType?.returnType

                // The impl body's return may be an unpinned var (a thin forwarder
                // like `get_or = {it,def} -> get_or{result=it,def=def}`
                // whose forwarded-call return the impl's own checking never tied back
                // to `def`). The export carries the real signature, e.g.
                // `{result: Result 'a 'e, def: 'a} -> 'a`, where the return shares `'a`
                // with a PARAM. Params are harvested separately with their OWN var
                // instances, so lifting the export's return verbatim leaves it
                // disconnected — `top.get_or{def=..}` then records `'a` instead of
                // the arg's type. Re-tie the return to the impl's OWN param var by
                // matching the export param field whose type is the export's return.
                val remappedReturn: MezzeType? =
                    if (bodyReturnType is MezzeType.VarType && exportFnType != null)
                        remapExportReturnToImplParamVar(exportFnType, opExpr)
                    else null

                val rawReturnType = remappedReturn
                    ?: if (bodyReturnType is MezzeType.VarType && exportReturnType != null) exportReturnType
                    else bodyReturnType

                // A remapped return IS a real impl-param var (its name may be plain,
                // e.g. `a`, not `'a`) — it is deliberately shared, not a placeholder.
                // Only the NON-remapped fallback needs the placeholder guard, which
                // drops the internal `unknown`-style vars TypedAstBuilder leaves on
                // bodies it couldn't pin.
                if (remappedReturn == null) {
                    val isPlaceholder = rawReturnType is MezzeType.VarType &&
                        !rawReturnType.variable.name.startsWith("'")
                    if (isPlaceholder) continue
                }

                opMap[opName] = rawReturnType
            }
            if (opMap.isNotEmpty()) {
                result[impl.hash] = opMap
            }
        }
        return result
    }

    /**
     * When an imported inherent method forwards to a same-named free function
     * (`get_or = {it,def} -> get_or{result=it,def=def}`), its body
     * return is an unpinned var and we fall back to the EXPORT's declared return.
     * That return often shares a type var with one of the export's PARAMS
     * (`{…, def: 'a} -> 'a`). Because params are harvested into a separate map
     * with their own var instances, the export's return var is disconnected from
     * the impl's param var — so the call-site records `'a` instead of the pinned
     * argument type. This re-ties them: find the export param field whose type IS
     * the export's return var, then return the SAME field's var off the impl's
     * own params. Returns null when the return isn't a bare var shared with a
     * param (nothing to re-tie — caller keeps the export return as-is).
     */
    private fun remapExportReturnToImplParamVar(
        exportFnType: MezzeType.FunctionType,
        opExpr: TypedExpr,
    ): MezzeType? {
        val exportRetVar = (exportFnType.returnType as? MezzeType.VarType)?.variable ?: return null
        val exportParams = (exportFnType.paramType as? MezzeType.RecordType)?.fields ?: return null
        // The export param field carrying the return's var (e.g. `def: 'a`).
        val sharedField = exportParams.entries.firstOrNull { (_, ty) ->
            (ty as? MezzeType.VarType)?.variable == exportRetVar
        }?.key ?: return null
        // Read that field off the impl lambda's own params to get the impl's var.
        val implParams = (opExpr as? TypedLambda)?.params ?: return null
        return implParams.firstOrNull { it.name == sharedField }?.type
    }

    /**
     * #181: harvest the EFFECT ROW of each inherent impl op. Reads the
     * lambda's FunctionType.effects directly — the body's effects were
     * baked in by ConstraintGenerator (#180) and preserved on
     * TypedLambda.type by TypedAstBuilder. Without this harvest, the
     * importer reconstructs a pure FunctionType in
     * registerImportedInherentImpl line 1120, and the call site
     * `r.send {}` looks effect-free across the module boundary.
     *
     * NOTE: We do NOT consult `exports[opName]` here. The same-named
     * free function may share a return-type shape with the impl op but
     * may perform completely different effects — conflating them
     * across two impls with shared op names (e.g. Request.with_auth
     * and Client.with_auth) is a bug. Effects come ONLY from the
     * impl's own TypedLambda.
     */
    private fun harvestInherentImplOpEffects(
        v2Module: TypedAstModule
    ): Map<Hash, Map<String, MezzeType.EffectRow>> {
        val result = mutableMapOf<Hash, Map<String, MezzeType.EffectRow>>()
        for (impl in v2Module.abilityImpls) {
            if (impl.abilityHash != Hash.ZERO) continue
            val opMap = mutableMapOf<String, MezzeType.EffectRow>()
            for ((opName, opExpr) in impl.operations) {
                val effects = (opExpr.type as? MezzeType.FunctionType)?.effects
                if (effects != null) {
                    opMap[opName] = effects
                }
            }
            if (opMap.isNotEmpty()) {
                result[impl.hash] = opMap
            }
        }
        return result
    }

    /**
     * Harvest the inferred ability constraints on each inherent impl op (e.g.
     * `decode = { it } -> decode_result { resp = it }` inherits `with FromJson
     * 'a Str` from decode_result). These live on the op's post-solve
     * FunctionType.abilityConstraints and reference the SAME type vars as the
     * op's return type (the lambda's body type IS its return), so re-attaching
     * them to the registered method type keeps the constraint tied to the
     * return var — pinning the return at the call site then pins the codec
     * target and the derivation fires. Only the auto-derived inherent impls
     * (abilityHash == ZERO) are inherent methods.
     */
    private fun harvestInherentImplOpConstraints(
        v2Module: TypedAstModule
    ): Map<Hash, Map<String, List<MezzeType.FunctionAbilityConstraint>>> {
        val result = mutableMapOf<Hash, Map<String, List<MezzeType.FunctionAbilityConstraint>>>()
        for (impl in v2Module.abilityImpls) {
            if (impl.abilityHash != Hash.ZERO) continue
            val opMap = mutableMapOf<String, List<MezzeType.FunctionAbilityConstraint>>()
            for ((opName, opExpr) in impl.operations) {
                val constraints = (opExpr.type as? MezzeType.FunctionType)?.abilityConstraints
                if (!constraints.isNullOrEmpty()) opMap[opName] = constraints
            }
            if (opMap.isNotEmpty()) result[impl.hash] = opMap
        }
        return result
    }

    /**
     * Issue #230: harvest the post-solve PARAM-record type for each inherent
     * impl operation. The op's param record carries fully-typed fields by the
     * time the home module finished checking — including callback fields like
     * `p: { x: 'a } -> Bool`. Importer registration (without this harvest)
     * substitutes a fresh TypeVar for each field, so callers can pass any
     * value where a function was expected.
     *
     * Same shape rules as harvestInherentImplOpReturnTypes — including
     * preferring the exported binding's signature over the impl op's body
     * when both are available (the export carries the un-stripped
     * polymorphic record; #215 may have stripped TypeApplication args from
     * the body's stored type).
     */
    private fun harvestInherentImplOpParamTypes(
        v2Module: TypedAstModule,
        exports: Map<String, ExportedItem>
    ): Map<Hash, Map<String, MezzeType>> {
        val result = mutableMapOf<Hash, Map<String, MezzeType>>()
        for (impl in v2Module.abilityImpls) {
            if (impl.abilityHash != Hash.ZERO) continue
            val opMap = mutableMapOf<String, MezzeType>()
            for ((opName, opExpr) in impl.operations) {
                val rawParamType: MezzeType =
                    if (opExpr is TypedLambda) {
                        MezzeType.RecordType(
                            fields = opExpr.params.associate { it.name to it.type },
                            rowVar = null
                        )
                    } else when (val t = opExpr.type) {
                        is MezzeType.FunctionType -> t.paramType
                        else -> continue
                    }

                val record = rawParamType as? MezzeType.RecordType
                if (record != null) {
                    val anyInformative = record.fields.values.any { v ->
                        v !is MezzeType.VarType ||
                            v.variable.name.startsWith("'")
                    }
                    if (!anyInformative) continue
                }

                opMap[opName] = rawParamType
            }
            if (opMap.isNotEmpty()) {
                result[impl.hash] = opMap
            }
        }
        return result
    }
}
