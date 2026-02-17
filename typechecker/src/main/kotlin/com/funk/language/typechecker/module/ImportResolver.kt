package com.funk.language.typechecker.module

import com.funk.language.ast.*

/**
 * Import resolver - resolves imports and builds module import context.
 *
 * This processes UseDeclaration and PubUseDeclaration to build a
 * ModuleImportContext containing all imported items.
 *
 * ## Supported Import Patterns:
 *
 * **Module Import** (imports all public exports):
 * ```funk
 * use std::collections::array
 * ```
 *
 * **Selective Import** (imports specific items):
 * ```funk
 * use std::collections::array::{map, filter, fold}
 * ```
 *
 * **Aliased Import**:
 * ```funk
 * use std::collections::array as arr
 * ```
 *
 * **Re-export** (import and re-export):
 * ```funk
 * pub use std::collections::array::{map, filter}
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
                processModulePath(path, use.alias, isPublic)
            }
            is SelectivePath -> {
                // Import specific items: use module::{foo, bar}
                processSelectivePath(path, isPublic)
            }
        }
    }

    /**
     * Process a module path import (imports all exports).
     *
     * Example: use std::collections::array
     *
     * Module imports use the last segment as the qualifier.
     * e.g., `use std::math::core` makes items available as `core::pi`
     */
    private fun processModulePath(
        path: ModulePath,
        alias: String?,
        isPublic: Boolean
    ): ProcessResult {
        val modulePath = path.segments.joinToString("::")

        // Check for internal module access
        // Top-level imports (fromModule = "") cannot access internal modules
        if (!loader.getResolver().canAccessInternal("", modulePath)) {
            return ProcessResult.Failure(
                listOf(ModuleError.InternalModuleAccess("", modulePath))
            )
        }

        // Load the module
        val loadResult = loader.loadModule(modulePath)

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
                // otherwise fall back to module.exports (has placeholder types)
                val exportsToUse = typedModule?.exports ?: module.exports

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
     * Example: use std::collections::array::{map, filter, fold}
     */
    private fun processSelectivePath(
        path: SelectivePath,
        isPublic: Boolean
    ): ProcessResult {
        val modulePath = path.module.joinToString("::")

        // Check for internal module access
        // Top-level imports (fromModule = "") cannot access internal modules
        if (!loader.getResolver().canAccessInternal("", modulePath)) {
            return ProcessResult.Failure(
                listOf(ModuleError.InternalModuleAccess("", modulePath))
            )
        }

        // Load the module
        val loadResult = loader.loadModule(modulePath)

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
                // otherwise fall back to module.exports (has placeholder types)
                val exportsToUse = typedModule?.exports ?: module.exports

                // Extract specific items
                val context = buildContextFromSelectiveImports(
                    exports = exportsToUse,
                    modulePath = modulePath,
                    items = path.items
                )

                // Check if all requested items were found
                val missingItems = path.items.mapNotNull { item ->
                    if (!module.exports.containsKey(item.name)) {
                        item.name
                    } else null
                }

                if (missingItems.isNotEmpty()) {
                    return ProcessResult.Failure(
                        missingItems.map { itemName ->
                            ModuleError.ExportNotFound(
                                modulePath = modulePath,
                                exportName = itemName
                            )
                        }
                    )
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
        val types = mutableMapOf<String, TypeDeclaration>()
        val abilities = mutableMapOf<String, AbilityDeclaration>()
        val effects = mutableMapOf<String, EffectDeclaration>()
        val impls = mutableMapOf<String, ImplDeclaration>()
        val abilityImpls = mutableMapOf<String, ImplAbilityDeclaration>()

        // Get the loaded module to access its declarations
        val module = loader.getModule(modulePath)

        for ((name, export) in exports) {
            // Determine the local name (use alias if provided)
            val localName = if (isQualified && alias != null) {
                "$alias::$name"
            } else {
                name
            }

            // Add to appropriate category
            when (export.type) {
                ExportedItemType.FUNCTION -> {
                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.funkType,
                        isQualified = isQualified
                    )
                }
                ExportedItemType.TYPE -> {
                    // CRITICAL FIX: Extract actual TypeDeclaration from the module
                    // This allows TypeApplication expansion (e.g., Continuation field access)
                    val typeDecl = module?.program?.items
                        ?.filterIsInstance<TypeDeclaration>()
                        ?.find { it.name == export.originalName }

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
                        type = export.funkType,
                        isQualified = isQualified
                    )
                }
                ExportedItemType.CONSTRUCTOR -> {
                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.funkType,
                        isQualified = isQualified
                    )
                }
                ExportedItemType.ABILITY -> {
                    // CRITICAL: Extract actual AbilityDeclaration from the module
                    // This allows ability lookup by qualified name (e.g., codec::EncodeFormat)
                    val abilityDecl = module?.program?.items
                        ?.filterIsInstance<AbilityDeclaration>()
                        ?.find { it.name == export.originalName }

                    if (abilityDecl != null) {
                        // Store under the qualified name for lookup
                        abilities[localName] = abilityDecl
                        // Also store under original name for unqualified lookup
                        abilities[export.originalName] = abilityDecl
                    }

                    // Also add as a binding for name resolution
                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.funkType,
                        isQualified = isQualified
                    )
                }
                ExportedItemType.EFFECT -> {
                    // CRITICAL: Extract actual EffectDeclaration from the module
                    val effectDecl = module?.program?.items
                        ?.filterIsInstance<EffectDeclaration>()
                        ?.find { it.name == export.originalName }

                    if (effectDecl != null) {
                        // Store under the qualified name for lookup
                        effects[localName] = effectDecl
                        // Also store under original name for unqualified lookup
                        effects[export.originalName] = effectDecl
                    }

                    // Also add as a binding for name resolution
                    bindings[localName] = ImportedBinding(
                        name = localName,
                        originalName = export.originalName,
                        modulePath = modulePath,
                        type = export.funkType,
                        isQualified = isQualified
                    )
                }
            }
        }

        // CRITICAL: Extract ALL impl declarations and ability impls from the module
        // These are always imported along with their effects/abilities
        if (module != null) {
            // Extract all impl declarations (effect handlers)
            val allImpls = module.program.items.filterIsInstance<ImplDeclaration>()
            for (impl in allImpls) {
                val implKey = impl.performerName
                impls[implKey] = impl
            }

            // Extract all ability implementations - key by content hash
            val allAbilityImpls = module.program.items.filterIsInstance<ImplAbilityDeclaration>()
            for (abilityImpl in allAbilityImpls) {
                val implKey = abilityImpl.contentHash
                    ?: throw IllegalStateException(
                        "Content hash not computed for imported impl: ${abilityImpl.abilityName} ${abilityImpl.targetTypes}"
                    )
                abilityImpls[implKey] = abilityImpl
            }
        }

        return ModuleImportContext(
            importedBindings = bindings,
            importedTypes = types,
            importedAbilities = abilities,
            importedEffects = effects,
            importedImpls = impls,
            importedAbilityImpls = abilityImpls
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
        val types = mutableMapOf<String, TypeDeclaration>()
        val abilities = mutableMapOf<String, AbilityDeclaration>()
        val effects = mutableMapOf<String, EffectDeclaration>()
        val impls = mutableMapOf<String, ImplDeclaration>()
        val abilityImpls = mutableMapOf<String, ImplAbilityDeclaration>()

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
                        type = export.funkType,
                        isQualified = false  // Selective imports are unqualified
                    )
                }
                ExportedItemType.TYPE -> {
                    // Extract TypeDeclaration from module program
                    val typeDecl = module?.program?.items
                        ?.filterIsInstance<TypeDeclaration>()
                        ?.find { it.name == item.name }
                    if (typeDecl != null) {
                        types[localName] = typeDecl
                    }
                }
                ExportedItemType.ABILITY -> {
                    // Extract AbilityDeclaration from module program
                    val abilityDecl = module?.program?.items
                        ?.filterIsInstance<AbilityDeclaration>()
                        ?.find { it.name == item.name }
                    if (abilityDecl != null) {
                        abilities[localName] = abilityDecl
                    }
                }
                ExportedItemType.EFFECT -> {
                    // Extract EffectDeclaration from module program
                    val effectDecl = module?.program?.items
                        ?.filterIsInstance<EffectDeclaration>()
                        ?.find { it.name == item.name }
                    if (effectDecl != null) {
                        effects[localName] = effectDecl
                    }
                }
            }
        }

        // CRITICAL: Also extract ImplDeclarations for the imported items
        // These are needed to build the performerToEffect mapping
        if (module != null) {
            // Extract ALL impl declarations for imported effects
            // (Handlers are associated with effects, not exported separately)
            for (item in items) {
                // Check if this is an effect import
                val effectDecl = module.program.items
                    .filterIsInstance<EffectDeclaration>()
                    .find { it.name == item.name }

                if (effectDecl != null) {
                    // Find all impl declarations for this effect
                    val effectImpls = module.program.items
                        .filterIsInstance<ImplDeclaration>()
                        .filter { it.effectName == effectDecl.name }

                    // Add each impl with a unique key (performerName)
                    for (impl in effectImpls) {
                        val implKey = impl.performerName  // Use performer name as key
                        impls[implKey] = impl
                    }
                }
            }

            // Extract ImplAbilityDeclarations for imported abilities
            for (item in items) {
                // Check if this is an ability import
                val abilityDecl = module.program.items
                    .filterIsInstance<AbilityDeclaration>()
                    .find { it.name == item.name }

                if (abilityDecl != null) {
                    // Find all impl declarations for this ability
                    val abilityImplsList = module.program.items
                        .filterIsInstance<ImplAbilityDeclaration>()
                        .filter { it.abilityName == abilityDecl.name }

                    // Add each impl keyed by content hash
                    for (abilityImpl in abilityImplsList) {
                        val implKey = abilityImpl.contentHash
                            ?: throw IllegalStateException(
                                "Content hash not computed for imported impl: ${abilityImpl.abilityName} ${abilityImpl.targetTypes}"
                            )
                        abilityImpls[implKey] = abilityImpl
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
            importedAbilityImpls = abilityImpls
        )
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
