package com.mezze.language.typechecker.module

import com.mezze.language.ast.*
import com.mezze.language.parsedast.Loc


/**
 * Import resolver - resolves imports and builds module import context.
 *
 * This processes UseDeclaration and PubUseDeclaration to build a
 * ModuleImportContext containing all imported items.
 *
 * ## Supported Import Patterns:
 *
 * **Module Import** (imports all public exports):
 * ```mezze
 * use std::collections::seq
 * ```
 *
 * **Selective Import** (imports specific items):
 * ```mezze
 * use std::collections::seq::{map, filter, fold}
 * ```
 *
 * **Aliased Import**:
 * ```mezze
 * use std::collections::seq as arr
 * ```
 *
 * **Re-export** (import and re-export):
 * ```mezze
 * pub use std::collections::seq::{map, filter}
 * ```
 *
 * ## Design:
 * - Pure functions (no mutation!)
 * - Immutable contexts
 * - Explicit error handling
 * - Prevents transitive import pollution
 *
 * ## Usage:
 * ```kotlin
 * val resolver = ImportResolver(moduleLoader)
 * val result = resolver.resolveImports(program, baseContext)
 *
 * when (result) {
 *     is ImportResolutionResult.Success -> {
 *         val context = result.importContext
 *         val modules = result.loadedModules
 *         // Use the import context
 *     }
 *     is ImportResolutionResult.Failure -> {
 *         for (error in result.errors) {
 *             println(error.message)
 *         }
 *     }
 * }
 * ```
 */
class ImportResolver(
    private val loader: ModuleLoader,
    private val typeChecker: ModuleTypeChecker? = null  // Optional, for type-checking imports
) {
    /**
     * Resolve imports from a ParsedModule (v2 AST entry point).
     * Converts v2 use path to UsePath and processes each import.
     */
    fun resolveImports(module: com.mezze.language.parsedast.ParsedModule): ImportResolutionResult {
        val errors = mutableListOf<ModuleError>()
        var importContext = ModuleImportContext.empty()
        val loadedModules = mutableMapOf<com.mezze.language.ast.ModulePath, TypedModule>()

        for (decl in module.decls) {
            val (usePath, alias, isPublic, loc) = when (decl) {
                is com.mezze.language.parsedast.ParsedUseDecl -> UseInfo(
                    parsedUsePathToV1(decl.path), decl.alias, false,
                    decl.loc
                )
                is com.mezze.language.parsedast.ParsedPubUseDecl -> UseInfo(
                    parsedUsePathToV1(decl.path), decl.alias, true,
                    decl.loc
                )
                else -> continue
            }
            val result = processUseDeclaration(usePath, alias, isPublic, loc)
            when (result) {
                is ProcessResult.Success -> {
                    importContext = importContext.merge(result.context)
                    result.module?.let { loadedModules[com.mezze.language.ast.ModulePath.parse(it.modulePath)] = it }
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

    /** Helper data class for destructuring use declarations. */
    private data class UseInfo(
        val path: UsePath,
        val alias: String?,
        val isPublic: Boolean,
        val loc: Loc?
    )

    /** Convert v2 ParsedUsePath to UsePath */
    private fun parsedUsePathToV1(path: com.mezze.language.parsedast.ParsedUsePath): UsePath {
        return when (path) {
            is com.mezze.language.parsedast.ParsedUsePath.Module ->
                ModulePath(path.path.segments)
            is com.mezze.language.parsedast.ParsedUsePath.Selective ->
                SelectivePath(
                    module = path.modulePath.segments,
                    items = path.items.map { item ->
                        ImportItem(name = item.name, alias = item.alias)
                    }
                )
        }
    }

    /**
     * Process a single use declaration.
     *
     * This loads the module and extracts the requested imports.
     */
    private fun processUseDeclaration(
        usePath: UsePath,
        alias: String?,
        isPublic: Boolean,
        sourceLoc: Loc?
    ): ProcessResult {
        return when (usePath) {
            is ModulePath -> {
                // Import all exports: use module
                processModulePath(usePath, alias, isPublic, sourceLoc)
            }
            is SelectivePath -> {
                // Import specific items: use module::{foo, bar}
                processSelectivePath(usePath, isPublic, sourceLoc)
            }
        }
    }

    /**
     * Process a module path import (imports all exports).
     *
     * Example: use std::collections::seq
     *
     * Module imports use the last segment as the qualifier.
     * e.g., `use std::math::core` makes items available as `core::pi`
     */
    private fun processModulePath(
        path: ModulePath,
        alias: String?,
        isPublic: Boolean,
        sourceLoc: Loc? = null
    ): ProcessResult {
        // Check for internal module access using structured path
        // Top-level imports (fromModule = null) cannot access internal modules
        if (!loader.getResolver().canAccessInternal(null, path)) {
            return ProcessResult.Failure(
                listOf(ModuleError.InternalModuleAccess("", path.fullPath, sourceLoc))
            )
        }

        // Load the module using structured path
        val loadResult = loader.loadModule(path)
        val modulePath = path.fullPath  // Keep string for error messages

        return when (loadResult) {
            is ModuleLoadResult.Success -> {
                val module = loadResult.module

                // Type-check the module if type checker is available
                val typedModule = typeChecker?.let { checker ->
                    val typeCheckResult = checker.typeCheckModule(module)
                    when (typeCheckResult) {
                        is ModuleTypeCheckResult.Success -> typeCheckResult.module
                        is ModuleTypeCheckResult.Failure -> {
                            // Type checking failed, return errors
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

                // Module imports are ALWAYS qualified with the last segment
                // e.g., `use std::math::core` -> items accessible as `core::pi`
                // If an alias is provided, use that instead
                val qualifierName = alias ?: path.segments.lastOrNull()

                // CRITICAL FIX: Use exports from typedModule if available (has real inferred types),
                // otherwise fall back to module.exports (has placeholder types).
                // Preloaded modules may have empty exports — use module.exports in that case.
                val exportsToUse = typedModule?.exports?.takeIf { it.isNotEmpty() } ?: module.exports

                // Build import context from all exports
                var context = buildContextFromExports(
                    exports = exportsToUse,
                    modulePath = modulePath,
                    alias = qualifierName,
                    isQualified = true  // Module imports are always qualified
                )

                // Harvest inferred return types for inherent-impl operations
                // from the home module's TypedAbilityImpl. Without this, the
                // importer rebuilds InherentMethodInfo with fresh TypeVars and
                // the body's solved return type is lost — calls like
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

    /**
     * Process a selective path import (imports specific items).
     *
     * Example: use std::collections::seq::{map, filter, fold}
     */
    private fun processSelectivePath(
        path: SelectivePath,
        isPublic: Boolean,
        sourceLoc: Loc? = null
    ): ProcessResult {
        // Convert to structured ModulePath
        val structuredPath = ModulePath(path.module)

        // Check for internal module access using structured path
        // Top-level imports (fromModule = null) cannot access internal modules
        if (!loader.getResolver().canAccessInternal(null, structuredPath)) {
            return ProcessResult.Failure(
                listOf(ModuleError.InternalModuleAccess("", structuredPath.fullPath, sourceLoc))
            )
        }

        // Load the module using structured path
        val loadResult = loader.loadModule(structuredPath)
        val modulePath = structuredPath.fullPath  // Keep string for error messages

        return when (loadResult) {
            is ModuleLoadResult.Success -> {
                val module = loadResult.module

                // Type-check the module if type checker is available
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

                // CRITICAL FIX: Use exports from typedModule if available (has real inferred types),
                // otherwise fall back to module.exports (has placeholder types).
                // Preloaded modules may have empty exports — use module.exports in that case.
                val exportsToUse = typedModule?.exports?.takeIf { it.isNotEmpty() } ?: module.exports

                // Extract specific items
                val context = buildContextFromSelectiveImports(
                    exports = exportsToUse,
                    modulePath = modulePath,
                    items = path.items
                )

                // Check if all requested items were found
                // Also check if items exist but are private (not exported)
                val errors = mutableListOf<ModuleError>()

                for (item in path.items) {
                    if (!module.exports.containsKey(item.name)) {
                        // Check if the item exists in the module but isn't public
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

    /**
     * Build import context from all exports.
     *
     * Used for module path imports (use module).
     */
    private fun buildContextFromExports(
        exports: Map<String, ExportedItem>,
        modulePath: String,
        alias: String?,
        isQualified: Boolean
    ): ModuleImportContext {
        val bindings = mutableMapOf<String, ImportedBinding>()
        val types = mutableMapOf<String, com.mezze.language.parsedast.ParsedTypeDecl>()
        // Subset of `types` keys that the USER explicitly listed in a `use` clause
        // (vs. auto-imported because some imported binding referenced them).
        // Used by the structural-alias check (#148) to decide whether a type name
        // acts as a nominal wall.
        val explicitTypeNames = mutableSetOf<String>()
        val abilities = mutableMapOf<String, com.mezze.language.parsedast.ParsedAbilityDecl>()
        val effects = mutableMapOf<String, com.mezze.language.parsedast.ParsedEffectDecl>()
        val impls = mutableMapOf<String, com.mezze.language.parsedast.ParsedPerformerDecl>()
        val abilityImpls = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.parsedast.ParsedImplAbilityDecl>()
        // Content-hash indexed maps for O(1) lookup
        val effectsByHash = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.parsedast.ParsedEffectDecl>()
        val abilitiesByHash = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.parsedast.ParsedAbilityDecl>()

        // Get the loaded module to access its declarations
        val module = loader.getModule(modulePath)

        for ((name, export) in exports) {
            // Determine the local name using BindingKey (use alias if provided for qualified imports)
            val bindingKey = if (isQualified && alias != null) {
                BindingKey.qualified(alias, name)
            } else {
                BindingKey.unqualified(name)
            }
            val localName = bindingKey.toKeyString()

            // Add to appropriate category
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
                    // O(1) lookup via module index (replaces O(N) filterIsInstance().find())
                    val typeDecl = module?.index?.typeDeclarations?.get(export.originalName)

                    if (typeDecl != null) {
                        // CRITICAL: Store under ORIGINAL name (not qualified name)!
                        // TypeApplication.constructor contains the unqualified type name (e.g., "Continuation")
                        // so the lookup in expandTypeApplicationToRecord must find it by that name.
                        types[export.originalName] = typeDecl
                        // This is an explicit `use foo::{TypeName}` — record it for #148.
                        explicitTypeNames.add(export.originalName)
                    }

                    // Also add as a binding for name resolution
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
                    // CRITICAL: When a CONSTRUCTOR shares a name with a type declaration
                    // (e.g., type Person = | Person { ... } |), also import the type declaration.
                    // Without this, ability impl matching fails because the type isn't in
                    // allTypeDeclarations and typeMatchesAnnotation can't resolve it.
                    val typeDecl = module?.index?.typeDeclarations?.get(export.originalName)
                    if (typeDecl != null) {
                        types[export.originalName] = typeDecl
                        // newtype-style constructors share the type name; importing the
                        // constructor brings the type name into scope as well (#148).
                        explicitTypeNames.add(export.originalName)
                    }
                }
                ExportedItemType.ABILITY -> {
                    // O(1) lookup via module index (replaces O(N) filterIsInstance().find())
                    val abilityDecl = module?.index?.abilityDeclarations?.get(export.originalName)

                    if (abilityDecl != null) {
                        // Store under the qualified name for lookup
                        abilities[localName] = abilityDecl
                        // Also store by content hash for O(1) lookup without string parsing
                        abilityDecl.contentHash?.let { hash ->
                            abilitiesByHash[hash] = abilityDecl
                        }
                    }

                    // Also add as a binding for name resolution
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
                    // O(1) lookup via module index (replaces O(N) filterIsInstance().find())
                    val effectDecl = module?.index?.effectDeclarations?.get(export.originalName)

                    if (effectDecl != null) {
                        // Store under the qualified name for lookup
                        effects[localName] = effectDecl
                        // Also store by content hash for O(1) lookup without string parsing
                        effectDecl.contentHash?.let { hash ->
                            effectsByHash[hash] = effectDecl
                        }
                    }

                    // Also add as a binding for name resolution
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

        // CRITICAL: Auto-import type declarations for types referenced by imported values.
        // When a user imports `default_http_config` which has type `HttpConfig`, they need
        // the HttpConfig type declaration available for spread operations and type inference.
        // This extracts TypeApplication constructors from all imported binding types.
        if (module != null) {
            val referencedTypes = mutableSetOf<String>()
            for ((_, binding) in bindings) {
                collectTypeApplicationConstructors(binding.type, referencedTypes)
            }
            for (typeName in referencedTypes) {
                if (typeName !in types) {
                    module.index.typeDeclarations[typeName]?.let { typeDecl ->
                        types[typeName] = typeDecl
                    }
                }
            }
        }

        // CRITICAL: Extract ALL impl declarations and ability impls from the module
        // These are always imported along with their effects/abilities
        // O(1) via index instead of O(N) filterIsInstance scans
        if (module != null) {
            // Use index for O(1) access to all impl declarations (effect handlers)
            // For module imports (isQualified=true), also add under qualified names
            // so that `perform log::Console` resolves correctly
            for ((performerName, perf) in module.index.performersByName) {
                impls[performerName] = perf
                if (isQualified && alias != null) {
                    // Use BindingKey for qualified performer lookup
                    val qualifiedKey = BindingKey.qualified(alias, performerName)
                    impls[qualifiedKey.toKeyString()] = perf
                }
            }

            // Import all ability implementations — always keyed by content hash.
            // ProgramIndex.build computes hashes on-demand for unhashed impls.
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

    /**
     * Build import context from selective imports.
     *
     * Used for selective path imports (use module::{foo, bar}).
     */
    private fun buildContextFromSelectiveImports(
        exports: Map<String, ExportedItem>,
        modulePath: String,
        items: List<ImportItem>
    ): ModuleImportContext {
        val bindings = mutableMapOf<String, ImportedBinding>()
        val types = mutableMapOf<String, com.mezze.language.parsedast.ParsedTypeDecl>()
        // Subset of `types` keys that the USER explicitly listed in a `use` clause
        // (vs. auto-imported because some imported binding referenced them).
        // Used by the structural-alias check (#148) to decide whether a type name
        // acts as a nominal wall.
        val explicitTypeNames = mutableSetOf<String>()
        val abilities = mutableMapOf<String, com.mezze.language.parsedast.ParsedAbilityDecl>()
        val effects = mutableMapOf<String, com.mezze.language.parsedast.ParsedEffectDecl>()
        val impls = mutableMapOf<String, com.mezze.language.parsedast.ParsedPerformerDecl>()
        val abilityImpls = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.parsedast.ParsedImplAbilityDecl>()
        // Content-hash indexed maps for O(1) lookup
        val effectsByHash = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.parsedast.ParsedEffectDecl>()
        val abilitiesByHash = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.parsedast.ParsedAbilityDecl>()

        // Get the loaded module to access its declarations
        val module = loader.getModule(modulePath)

        for (item in items) {
            val export = exports[item.name] ?: continue

            // Determine the local name (use alias if provided)
            val localName = item.alias ?: item.name

            // Add to appropriate category based on export type
            when (export.type) {
                ExportedItemType.FUNCTION, ExportedItemType.CONSTRUCTOR -> {
                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.mezzeType,
                        isQualified = false,  // Selective imports are unqualified
                        contentHash = export.contentHash
                    )
                    // CRITICAL: When a CONSTRUCTOR shares a name with a type declaration
                    // (e.g., type Person = | Person { ... } |), also import the type declaration.
                    // Without this, ability impl matching fails because the type isn't in
                    // allTypeDeclarations and typeMatchesAnnotation can't resolve it.
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
                    // O(1) lookup via module index
                    val typeDecl = module?.index?.typeDeclarations?.get(item.name)
                    if (typeDecl != null) {
                        types[localName] = typeDecl
                        // Explicit `use foo::{TypeName}` (#148)
                        explicitTypeNames.add(localName)
                    }
                    // Note: Types are NOT added to bindings because they don't have runtime values.
                    // Type imports are compile-time only (used by typechecker, not interpreter).
                    // If we added them to bindings, the interpreter would try to look up a
                    // non-existent value by content hash.
                }
                ExportedItemType.ABILITY -> {
                    // O(1) lookup via module index
                    val abilityDecl = module?.index?.abilityDeclarations?.get(item.name)
                    if (abilityDecl != null) {
                        abilities[localName] = abilityDecl
                        // Also store by content hash for O(1) lookup
                        abilityDecl.contentHash?.let { hash ->
                            abilitiesByHash[hash] = abilityDecl
                        }
                    }
                    // Note: Abilities are NOT added to bindings because they don't have runtime values.
                    // Ability imports are compile-time only (used by typechecker, not interpreter).
                }
                ExportedItemType.EFFECT -> {
                    // O(1) lookup via module index
                    val effectDecl = module?.index?.effectDeclarations?.get(item.name)
                    if (effectDecl != null) {
                        effects[localName] = effectDecl
                        // Also store by content hash for O(1) lookup
                        effectDecl.contentHash?.let { hash ->
                            effectsByHash[hash] = effectDecl
                        }
                    }
                    // Note: Effects are NOT added to bindings because they don't have runtime values.
                    // Effect imports are compile-time only (used by typechecker, not interpreter).
                }
            }
        }

        // CRITICAL: Also extract performer and ability impl declarations for the imported items
        // These are needed to build the performerToEffect mapping
        // All lookups via O(1) index instead of O(N) filterIsInstance scans
        if (module != null) {
            val index = module.index

            // Extract performer declarations for imported effects
            // (Handlers are associated with effects, not exported separately)
            for (item in items) {
                // O(1) check if this is an effect import
                val effectDecl = index.effectDeclarations[item.name]

                if (effectDecl != null) {
                    // O(1) lookup of all performer declarations for this effect
                    val effectPerformers = index.performersByEffect[effectDecl.name] ?: emptyList()

                    // Add each performer with a unique key (performerName)
                    for (perf in effectPerformers) {
                        impls[perf.performerName] = perf
                    }
                }

                // O(1) check if this is a direct performer import (e.g., Console)
                val performerImpl = index.performersByName[item.name]
                if (performerImpl != null && !impls.containsKey(item.name)) {
                    impls[item.name] = performerImpl
                }
            }

            // Extract ability impl declarations for imported abilities
            for (item in items) {
                // O(1) check if this is an ability import
                val abilityDecl = index.abilityDeclarations[item.name]

                if (abilityDecl != null) {
                    // O(1) lookup of all impl declarations for this ability
                    val abilityImplsList = index.abilityImplsByAbility[abilityDecl.name] ?: emptyList()

                    // Add each impl keyed by content hash
                    for (abilityImpl in abilityImplsList) {
                        val implKey = abilityImpl.contentHash
                            ?: throw IllegalStateException(
                                "Content hash not computed for imported impl: ${abilityImpl.abilityName ?: "inherent"} ${abilityImpl.targetTypes}"
                            )
                        abilityImpls[implKey] = abilityImpl
                    }
                }
            }

            // ALSO import all ability impls from the module, even if the ability
            // is defined elsewhere. This allows impls to be defined in separate
            // modules from their abilities (e.g., Show in abilities/show.mz,
            // impl Show for Int in impls/show_impls.mz).
            // O(1) access via index instead of filterIsInstance scan
            abilityImpls.putAll(index.abilityImplsByHash)

            // CRITICAL: Auto-import type declarations for types referenced by imported values.
            // When a user imports `default_http_config` which has type `HttpConfig`, they need
            // the HttpConfig type declaration available for spread operations and type inference.
            val referencedTypes = mutableSetOf<String>()
            for ((_, binding) in bindings) {
                collectTypeApplicationConstructors(binding.type, referencedTypes)
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

    // ========================================================================
    // HELPER FUNCTIONS
    // ========================================================================

    /**
     * Check if an item exists in the module but is not public (not exported).
     *
     * This helps provide better error messages when a user tries to import
     * something that exists but hasn't been marked with `pub`.
     */
    private fun checkIfItemExistsButPrivate(module: LoadedModule, itemName: String): Boolean {
        val index = module.index

        // Check let bindings (functions/values)
        index.letBindings[itemName]?.let { binding ->
            // Found it - it exists but wasn't exported (otherwise it would be in exports)
            return binding.visibility != com.mezze.language.parsedast.Visibility.PUBLIC
        }

        // Check type declarations
        index.typeDeclarations[itemName]?.let { typeDecl ->
            return typeDecl.visibility != com.mezze.language.parsedast.Visibility.PUBLIC
        }

        // Check ability declarations (abilities are always public, so if it's here but not exported, something is wrong)
        if (index.abilityDeclarations.containsKey(itemName)) {
            return false  // Abilities are always public
        }

        // Check effect declarations (effects are always public)
        if (index.effectDeclarations.containsKey(itemName)) {
            return false  // Effects are always public
        }

        // Item not found at all
        return false
    }

    /**
     * Recursively collect all TypeApplication constructor names from a MezzeType.
     *
     * This is used to auto-import type declarations for types referenced by imported values.
     * For example, if a value has type `Result HttpConfig Error`, we collect "Result", "HttpConfig", "Error".
     */
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
            is MezzeType.SeqType -> {
                collectTypeApplicationConstructors(type.elementType, result)
            }
            is MezzeType.ListType -> {
                collectTypeApplicationConstructors(type.elementType, result)
            }
            is MezzeType.SetType -> {
                collectTypeApplicationConstructors(type.elementType, result)
            }
            is MezzeType.MapType -> {
                collectTypeApplicationConstructors(type.keyType, result)
                collectTypeApplicationConstructors(type.valueType, result)
            }
            is MezzeType.SortedSetType -> {
                collectTypeApplicationConstructors(type.elementType, result)
            }
            is MezzeType.SortedMapType -> {
                collectTypeApplicationConstructors(type.keyType, result)
                collectTypeApplicationConstructors(type.valueType, result)
            }
            is MezzeType.SuspendedType -> {
                collectTypeApplicationConstructors(type.valueType, result)
            }
            is MezzeType.EffectRow -> {
                // Effect rows don't contain type applications
            }
            is MezzeType.NewtypeType -> {
                collectTypeApplicationConstructors(type.wrappedType, result)
            }
            // Simple types that don't contain other types
            is MezzeType.VarType,
            is MezzeType.IntType,
            is MezzeType.NatType,
            is MezzeType.FloatType,
            is MezzeType.FracType,
            is MezzeType.BoolType,
            is MezzeType.StringType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType,
            is MezzeType.CharType,
            is MezzeType.OrderingType -> {}
        }
    }

    // ========================================================================
    // INTERNAL RESULT TYPE
    // ========================================================================

    /**
     * Internal result type for processing a single import.
     */
    private sealed class ProcessResult {
        data class Success(
            val context: ModuleImportContext,
            val module: TypedModule? = null
        ) : ProcessResult()

        data class Failure(
            val errors: List<ModuleError>
        ) : ProcessResult()
    }

    /**
     * Pull (implContentHash → opName → returnType) out of a typechecked
     * module, restricted to inherent impls (those whose abilityHash is
     * Hash.ZERO — see TypedAstBuilder.buildAbilityImpl). The returned map
     * lets ContextBuilder reuse the home module's solved return type when
     * registering the impl across the module boundary, instead of giving
     * each operation a fresh unbound TypeVar.
     */
    private fun harvestInherentImplOpReturnTypes(
        v2Module: com.mezze.language.typedast.TypedModule,
        exports: Map<String, ExportedItem>
    ): Map<com.mezze.language.typedast.Hash, Map<String, com.mezze.language.ast.MezzeType>> {
        val result = mutableMapOf<com.mezze.language.typedast.Hash, Map<String, com.mezze.language.ast.MezzeType>>()
        for (impl in v2Module.abilityImpls) {
            // Inherent impls use abilityHash = Hash.ZERO.
            if (impl.abilityHash != com.mezze.language.typedast.Hash.ZERO) continue
            val opMap = mutableMapOf<String, com.mezze.language.ast.MezzeType>()
            for ((opName, opExpr) in impl.operations) {
                // Stdlib impl operations are typically thin wrappers around a
                // same-named top-level let (e.g. `length = { it } -> length {
                // xs = it }`). The exported binding's MezzeType carries the
                // full polymorphic signature (incl. TypeApplication args),
                // while the impl body's stored type may have args stripped at
                // TypedAstBuilder time (see issue #215). When the op name
                // matches an exported function, prefer the export's return
                // type as the source of truth.
                val exportedFn = exports[opName]
                val exportType = exportedFn?.mezzeType
                val exportReturnType = if (exportType is com.mezze.language.ast.MezzeType.FunctionType) {
                    exportType.returnType
                } else null

                // Otherwise fall back to the impl op's body type.
                val bodyReturnType: com.mezze.language.ast.MezzeType =
                    if (opExpr is com.mezze.language.typedast.TypedLambda) {
                        opExpr.body.type
                    } else when (val t = opExpr.type) {
                        is com.mezze.language.ast.MezzeType.FunctionType -> t.returnType
                        else -> t
                    }

                val rawReturnType = exportReturnType ?: bodyReturnType

                // Skip placeholder TypeVars that are not informative (e.g. "ret",
                // "op_*"). Only keep concrete types or quote-prefixed type vars
                // that have actual meaning (like 'a in a generic impl).
                val isPlaceholder = rawReturnType is com.mezze.language.ast.MezzeType.VarType &&
                    !rawReturnType.variable.name.startsWith("'")
                if (isPlaceholder) continue

                opMap[opName] = rawReturnType
            }
            if (opMap.isNotEmpty()) {
                result[impl.hash] = opMap
            }
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
        v2Module: com.mezze.language.typedast.TypedModule,
        exports: Map<String, ExportedItem>
    ): Map<com.mezze.language.typedast.Hash, Map<String, com.mezze.language.ast.MezzeType>> {
        val result = mutableMapOf<com.mezze.language.typedast.Hash, Map<String, com.mezze.language.ast.MezzeType>>()
        for (impl in v2Module.abilityImpls) {
            // Inherent impls use abilityHash = Hash.ZERO.
            if (impl.abilityHash != com.mezze.language.typedast.Hash.ZERO) continue
            val opMap = mutableMapOf<String, com.mezze.language.ast.MezzeType>()
            for ((opName, opExpr) in impl.operations) {
                // Use the impl op's body shape — its params are the
                // ones the call-site sees.
                val rawParamType: com.mezze.language.ast.MezzeType =
                    if (opExpr is com.mezze.language.typedast.TypedLambda) {
                        com.mezze.language.ast.MezzeType.RecordType(
                            fields = opExpr.params.associate { it.name to it.type },
                            rowVar = null
                        )
                    } else when (val t = opExpr.type) {
                        is com.mezze.language.ast.MezzeType.FunctionType -> t.paramType
                        else -> continue
                    }

                // Skip when the harvested record is *all* uninformative
                // TypeVars — gives the importer nothing better than what it
                // would synthesize itself, and risks crashing the alignment
                // pass with cross-instance var identity.
                val record = rawParamType as? com.mezze.language.ast.MezzeType.RecordType
                if (record != null) {
                    val anyInformative = record.fields.values.any { v ->
                        v !is com.mezze.language.ast.MezzeType.VarType ||
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
