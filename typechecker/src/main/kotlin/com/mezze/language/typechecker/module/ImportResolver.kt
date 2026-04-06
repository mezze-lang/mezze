package com.mezze.language.typechecker.module

import com.mezze.language.ast.*

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
     * Resolve all imports in a program.
     *
     * This finds all UseDeclaration and PubUseDeclaration,
     * loads the referenced modules, and builds an import context.
     *
     * @param program The program to process
     * @return ImportResolutionResult (Success or Failure)
     */
    fun resolveImports(program: Program): ImportResolutionResult {
        val errors = mutableListOf<ModuleError>()
        var importContext = ModuleImportContext.empty()
        val loadedModules = mutableMapOf<String, TypedModule>()

        // Find all import declarations
        val useDeclarations = program.items.filterIsInstance<UseDeclaration>()
        val pubUseDeclarations = program.items.filterIsInstance<PubUseDeclaration>()

        // Process regular imports
        for (use in useDeclarations) {
            val result = processUseDeclaration(use, isPublic = false)
            when (result) {
                is ProcessResult.Success -> {
                    importContext = importContext.merge(result.context)
                    result.module?.let { loadedModules[it.modulePath] = it }
                }
                is ProcessResult.Failure -> {
                    errors.addAll(result.errors)
                }
            }
        }

        // Process re-exports
        for (pubUse in pubUseDeclarations) {
            val result = processUseDeclaration(
                UseDeclaration(pubUse.path, pubUse.alias),
                isPublic = true
            )
            when (result) {
                is ProcessResult.Success -> {
                    importContext = importContext.merge(result.context)
                    result.module?.let { loadedModules[it.modulePath] = it }
                }
                is ProcessResult.Failure -> {
                    errors.addAll(result.errors)
                }
            }
        }

        // Return result
        return if (errors.isEmpty()) {
            ImportResolutionResult.Success(
                importContext = importContext,
                loadedModules = loadedModules
            )
        } else {
            ImportResolutionResult.Failure(errors)
        }
    }

    /**
     * Resolve imports from a ParsedModule (v2 AST entry point).
     * Converts v2 use decls to v1 UseDeclaration and delegates.
     */
    fun resolveImports(module: com.mezze.language.parsedast.ParsedModule): ImportResolutionResult {
        val errors = mutableListOf<ModuleError>()
        var importContext = ModuleImportContext.empty()
        val loadedModules = mutableMapOf<String, TypedModule>()

        for (decl in module.decls) {
            val (use, isPublic) = when (decl) {
                is com.mezze.language.parsedast.ParsedUseDecl -> Pair(parsedUseToV1(decl), false)
                is com.mezze.language.parsedast.ParsedPubUseDecl -> Pair(parsedPubUseToV1(decl), true)
                else -> continue
            }
            val result = processUseDeclaration(use, isPublic)
            when (result) {
                is ProcessResult.Success -> {
                    importContext = importContext.merge(result.context)
                    result.module?.let { loadedModules[it.modulePath] = it }
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

    /** Convert v2 ParsedUseDecl to v1 UseDeclaration */
    private fun parsedUseToV1(decl: com.mezze.language.parsedast.ParsedUseDecl): UseDeclaration {
        return UseDeclaration(
            path = parsedUsePathToV1(decl.path),
            alias = decl.alias,
            sourceLocation = SourceLocation(decl.loc.startLine, decl.loc.startCol, decl.loc.file)
        )
    }

    /** Convert v2 ParsedPubUseDecl to v1 UseDeclaration */
    private fun parsedPubUseToV1(decl: com.mezze.language.parsedast.ParsedPubUseDecl): UseDeclaration {
        return UseDeclaration(
            path = parsedUsePathToV1(decl.path),
            alias = decl.alias,
            sourceLocation = SourceLocation(decl.loc.startLine, decl.loc.startCol, decl.loc.file)
        )
    }

    /** Convert v2 ParsedUsePath to v1 UsePath */
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
     * Process a single UseDeclaration.
     *
     * This loads the module and extracts the requested imports.
     */
    private fun processUseDeclaration(
        use: UseDeclaration,
        isPublic: Boolean
    ): ProcessResult {
        return when (val path = use.path) {
            is ModulePath -> {
                // Import all exports: use module
                processModulePath(path, use.alias, isPublic, use.sourceLocation)
            }
            is SelectivePath -> {
                // Import specific items: use module::{foo, bar}
                processSelectivePath(path, isPublic, use.sourceLocation)
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
        sourceLocation: com.mezze.language.ast.SourceLocation? = null
    ): ProcessResult {
        // Check for internal module access using structured path
        // Top-level imports (fromModule = null) cannot access internal modules
        if (!loader.getResolver().canAccessInternal(null, path)) {
            return ProcessResult.Failure(
                listOf(ModuleError.InternalModuleAccess("", path.fullPath, sourceLocation))
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
                val context = buildContextFromExports(
                    exports = exportsToUse,
                    modulePath = modulePath,
                    alias = qualifierName,
                    isQualified = true  // Module imports are always qualified
                )

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
        sourceLocation: com.mezze.language.ast.SourceLocation? = null
    ): ProcessResult {
        // Convert to structured ModulePath
        val structuredPath = ModulePath(path.module)

        // Check for internal module access using structured path
        // Top-level imports (fromModule = null) cannot access internal modules
        if (!loader.getResolver().canAccessInternal(null, structuredPath)) {
            return ProcessResult.Failure(
                listOf(ModuleError.InternalModuleAccess("", structuredPath.fullPath, sourceLocation))
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
                                sourceLocation = sourceLocation
                            ))
                        } else {
                            val availableExports = module.exports.keys.toList().sorted()
                            errors.add(ModuleError.ExportNotFound(
                                modulePath = modulePath,
                                exportName = item.name,
                                sourceLocation = sourceLocation,
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
        val abilities = mutableMapOf<String, com.mezze.language.parsedast.ParsedAbilityDecl>()
        val effects = mutableMapOf<String, com.mezze.language.parsedast.ParsedEffectDecl>()
        val impls = mutableMapOf<String, ImplDeclaration>()
        val abilityImpls = mutableMapOf<String, ImplAbilityDeclaration>()
        // Content-hash indexed maps for O(1) lookup
        val effectsByHash = mutableMapOf<String, com.mezze.language.parsedast.ParsedEffectDecl>()
        val abilitiesByHash = mutableMapOf<String, com.mezze.language.parsedast.ParsedAbilityDecl>()

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
            for ((performerName, impl) in module.index.implsByPerformer) {
                impls[performerName] = impl
                if (isQualified && alias != null) {
                    // Use BindingKey for qualified performer lookup
                    val qualifiedKey = BindingKey.qualified(alias, performerName)
                    impls[qualifiedKey.toKeyString()] = impl
                }
            }

            // Import all ability implementations — always keyed by content hash.
            // ProgramIndex.build computes hashes on-demand for unhashed impls.
            abilityImpls.putAll(module.index.abilityImplsByHash)
        }

        return ModuleImportContext(
            importedBindings = bindings,
            importedTypes = types,
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
        val abilities = mutableMapOf<String, com.mezze.language.parsedast.ParsedAbilityDecl>()
        val effects = mutableMapOf<String, com.mezze.language.parsedast.ParsedEffectDecl>()
        val impls = mutableMapOf<String, ImplDeclaration>()
        val abilityImpls = mutableMapOf<String, ImplAbilityDeclaration>()
        // Content-hash indexed maps for O(1) lookup
        val effectsByHash = mutableMapOf<String, com.mezze.language.parsedast.ParsedEffectDecl>()
        val abilitiesByHash = mutableMapOf<String, com.mezze.language.parsedast.ParsedAbilityDecl>()

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
                        }
                    }
                }
                ExportedItemType.TYPE -> {
                    // O(1) lookup via module index
                    val typeDecl = module?.index?.typeDeclarations?.get(item.name)
                    if (typeDecl != null) {
                        types[localName] = typeDecl
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

        // CRITICAL: Also extract ImplDeclarations for the imported items
        // These are needed to build the performerToEffect mapping
        // All lookups via O(1) index instead of O(N) filterIsInstance scans
        if (module != null) {
            val index = module.index

            // Extract impl declarations for imported effects
            // (Handlers are associated with effects, not exported separately)
            for (item in items) {
                // O(1) check if this is an effect import
                val effectDecl = index.effectDeclarations[item.name]

                if (effectDecl != null) {
                    // O(1) lookup of all impl declarations for this effect
                    val effectImpls = index.implsByEffect[effectDecl.name] ?: emptyList()

                    // Add each impl with a unique key (performerName)
                    for (impl in effectImpls) {
                        impls[impl.performerName] = impl
                    }
                }

                // O(1) check if this is a direct performer import (e.g., Console)
                val performerImpl = index.implsByPerformer[item.name]
                if (performerImpl != null && !impls.containsKey(item.name)) {
                    impls[item.name] = performerImpl
                }
            }

            // Extract ImplAbilityDeclarations for imported abilities
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
            return binding.visibility != Visibility.PUBLIC
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
}
