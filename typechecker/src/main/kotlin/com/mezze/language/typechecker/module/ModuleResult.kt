package com.mezze.language.typechecker.module

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.typechecker.ProgramIndex
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.solving.Substitution

/**
 * Result types and data structures for the module system.
 *
 * This file defines the core types used throughout the module system:
 * - LoadedModule: A module that has been loaded and parsed
 * - TypedModule: A module that has been type-checked
 * - ModuleImportContext: Context built from imports
 * - ImportedBinding: A single imported item
 *
 * ## Design Principles:
 * - Immutable data structures
 * - Sealed result types
 * - Explicit error handling
 * - No mutation!
 */

// ============================================================================
// MODULE LOADING RESULTS
// ============================================================================

/**
 * Result of loading a module.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class ModuleLoadResult {
    /**
     * Module loaded successfully.
     */
    data class Success(
        val module: LoadedModule
    ) : ModuleLoadResult()

    /**
     * Module loading failed.
     */
    data class Failure(
        val errors: List<ModuleError>
    ) : ModuleLoadResult()
}

/**
 * A module that has been loaded and parsed (but not yet type-checked).
 */
data class LoadedModule(
    val path: ModulePath,              // Structured module path
    val filePath: String,              // Physical file path
    @Deprecated("Use parsedModule instead — v1 Program kept for backward compat during migration")
    val program: Program,              // Parsed AST (v1 — kept for backward compat during migration)
    val exports: Map<String, ExportedItem>,  // Exported items (types may be incomplete)
    val dependencies: List<ModulePath>, // Module dependencies as structured paths
    val index: ProgramIndex = ProgramIndex.EMPTY,  // Pre-computed index for O(1) declaration lookups
    val sourceHash: String? = null,     // Content hash of source for cache validation
    /** V2 ParsedModule — the primary AST. Program field is derived from this. */
    val parsedModule: com.mezze.language.parsedast.ParsedModule? = null
) {
    /** Legacy accessor for module path as string. Use path.fullPath instead. */
    val modulePath: String get() = path.fullPath
}

/**
 * An exported item from a module.
 *
 * This represents items that can be imported from a module.
 */
data class ExportedItem(
    val name: String,                  // Export name
    val type: ExportedItemType,        // What kind of item (function, type, etc.)
    val mezzeType: MezzeType,          // Type of the item
    val originalName: String,          // Original name in module (before aliasing)
    val visibility: Visibility = Visibility.PUBLIC,
    val contentHash: String            // Content hash hex string for content-addressed lookup (REQUIRED)
)

/**
 * Types of items that can be exported from a module.
 */
enum class ExportedItemType {
    FUNCTION,     // Functions and bindings
    TYPE,         // Type declarations
    CONSTRUCTOR,  // Variant constructors
    ABILITY,      // Ability declarations
    EFFECT        // Effect declarations
}

/**
 * Module loading errors.
 */
sealed class ModuleError(override val message: String) : Exception(message) {
    data class ModuleNotFound(
        val modulePath: String,
        val searchPaths: List<String>
    ) : ModuleError("Module not found: $modulePath. Searched in: ${searchPaths.joinToString(", ")}")

    data class CircularDependency(
        val cycle: List<String>
    ) : ModuleError("Circular dependency detected: ${cycle.joinToString(" -> ")}")

    data class ParseError(
        val modulePath: String,
        val error: String
    ) : ModuleError("Failed to parse module $modulePath: $error")

    data class ImportError(
        val modulePath: String,
        val itemName: String,
        val reason: String
    ) : ModuleError("Cannot import '$itemName' from '$modulePath': $reason")

    data class ExportNotFound(
        val modulePath: String,
        val exportName: String,
        val sourceLocation: com.mezze.language.ast.SourceLocation? = null,
        val availableExports: List<String> = emptyList()
    ) : ModuleError("Export '$exportName' not found in module '$modulePath'")

    /**
     * Thrown when trying to import an item that exists but is not public.
     */
    data class PrivateExport(
        val modulePath: String,
        val exportName: String,
        val sourceLocation: com.mezze.language.ast.SourceLocation? = null
    ) : ModuleError("'$exportName' exists in module '$modulePath' but is not public. Add 'pub' to export it.")

    data class InternalModuleAccess(
        val fromModule: String,
        val toModule: String,
        val sourceLocation: com.mezze.language.ast.SourceLocation? = null
    ) : ModuleError(
        if (fromModule.isEmpty())
            "Cannot access internal module '$toModule' from outside its package"
        else
            "Module '$fromModule' cannot access internal module '$toModule'"
    )
}

// ============================================================================
// MODULE TYPE CHECKING RESULTS
// ============================================================================

/**
 * Result of type-checking a module.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class ModuleTypeCheckResult {
    /**
     * Module type-checked successfully.
     */
    data class Success(
        val module: TypedModule
    ) : ModuleTypeCheckResult()

    /**
     * Module type-checking failed.
     */
    data class Failure(
        val errors: List<TypeCheckError>
    ) : ModuleTypeCheckResult()

    /**
     * Type checking error.
     */
    data class TypeCheckError(
        val modulePath: String,
        val phase: String,
        val message: String,
        val sourceLocation: SourceLocation? = null
    )
}

/**
 * A module that has been type-checked.
 */
data class TypedModule(
    val modulePath: String,                           // Module path
    @Deprecated("Use v2TypedModule instead — v1 Program kept for backward compat during migration")
    val program: Program,
    val exports: Map<String, ExportedItem>,           // Exports with complete types
    val context: TypingContext,                       // Module-specific typing context
    val substitution: Substitution,                   // Type substitution from inference
    val monomorphicInstances: Map<String, LetBinding>, // Specialized function instances
    val dependencyGraph: com.mezze.language.typechecker.dependencies.DependencyGraph =
        com.mezze.language.typechecker.dependencies.DependencyGraph.EMPTY,  // Dependencies between definitions
    /** V2 TypedModule emitted by TypedAstBuilder — used by monomorphizer */
    val v2TypedModule: com.mezze.language.typedast.TypedModule? = null,
    /** V2 ParsedModule — the primary AST representation. Use instead of program when possible. */
    val parsedModule: com.mezze.language.parsedast.ParsedModule? = null
)

// ============================================================================
// IMPORT RESOLUTION RESULTS
// ============================================================================

/**
 * Result of resolving imports.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class ImportResolutionResult {
    /**
     * Imports resolved successfully.
     */
    data class Success(
        val importContext: ModuleImportContext,
        val loadedModules: Map<String, TypedModule>
    ) : ImportResolutionResult()

    /**
     * Import resolution failed.
     */
    data class Failure(
        val errors: List<ModuleError>
    ) : ImportResolutionResult()
}

/**
 * Context built from processing imports.
 *
 * This contains all items imported from other modules,
 * ready to be merged with the current module's TypingContext.
 */
data class ModuleImportContext(
    val importedBindings: Map<String, ImportedBinding>,      // Imported functions/values (keyed by name)
    val importedTypes: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>,         // Imported type declarations (keyed by name)
    val importedAbilities: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl>,  // Imported abilities (keyed by name)
    val importedEffects: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,     // Imported effects (keyed by name)
    val importedImpls: Map<String, ImplDeclaration>,         // Imported effect implementations (keyed by name)
    val importedAbilityImpls: Map<String, ImplAbilityDeclaration>,  // Imported ability implementations (keyed by name)
    // Content-hash indexed maps for O(1) lookup without string parsing
    val effectsByHash: Map<String, com.mezze.language.parsedast.ParsedEffectDecl> = emptyMap(),    // Effects keyed by content hash
    val abilitiesByHash: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl> = emptyMap()  // Abilities keyed by content hash
) {
    companion object {
        /**
         * Create an empty import context.
         */
        fun empty(): ModuleImportContext {
            return ModuleImportContext(
                importedBindings = emptyMap(),
                importedTypes = emptyMap(),
                importedAbilities = emptyMap(),
                importedEffects = emptyMap(),
                importedImpls = emptyMap(),
                importedAbilityImpls = emptyMap(),
                effectsByHash = emptyMap(),
                abilitiesByHash = emptyMap()
            )
        }
    }

    /**
     * Merge two import contexts.
     */
    fun merge(other: ModuleImportContext): ModuleImportContext {
        return ModuleImportContext(
            importedBindings = importedBindings + other.importedBindings,
            importedTypes = importedTypes + other.importedTypes,
            importedAbilities = importedAbilities + other.importedAbilities,
            importedEffects = importedEffects + other.importedEffects,
            importedImpls = importedImpls + other.importedImpls,
            importedAbilityImpls = importedAbilityImpls + other.importedAbilityImpls,
            effectsByHash = effectsByHash + other.effectsByHash,
            abilitiesByHash = abilitiesByHash + other.abilitiesByHash
        )
    }

    /**
     * Check if context is empty.
     */
    fun isEmpty(): Boolean {
        return importedBindings.isEmpty() &&
               importedTypes.isEmpty() &&
               importedAbilities.isEmpty() &&
               importedEffects.isEmpty() &&
               importedImpls.isEmpty() &&
               importedAbilityImpls.isEmpty()
    }

    /**
     * Look up an effect by its content hash.
     * This is the preferred lookup method - O(1) with no string parsing.
     */
    fun getEffectByHash(contentHash: String): com.mezze.language.parsedast.ParsedEffectDecl? = effectsByHash[contentHash]

    /**
     * Look up an ability by its content hash.
     * This is the preferred lookup method - O(1) with no string parsing.
     */
    fun getAbilityByHash(contentHash: String): com.mezze.language.parsedast.ParsedAbilityDecl? = abilitiesByHash[contentHash]
}

/**
 * An imported binding (function, value, etc.).
 */
data class ImportedBinding(
    val name: String,                  // Local name (may be aliased)
    val originalName: String,          // Original name in source module
    val modulePath: String,            // Source module path
    val type: MezzeType,               // Type of the binding
    val isQualified: Boolean = false,  // true for qualified imports (module::name)
    val sourceLocation: SourceLocation? = null,
    val contentHash: String            // Content hash hex string for content-addressed lookup (REQUIRED)
)

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Format a module load result for display.
 */
fun formatModuleLoadResult(result: ModuleLoadResult): String {
    return when (result) {
        is ModuleLoadResult.Success -> buildString {
            appendLine("✓ Module loaded: ${result.module.modulePath}")
            appendLine("  File: ${result.module.filePath}")
            appendLine("  Exports: ${result.module.exports.size} item(s)")
            appendLine("  Dependencies: ${result.module.dependencies.size}")
        }
        is ModuleLoadResult.Failure -> buildString {
            appendLine("✗ Module loading failed with ${result.errors.size} error(s):")
            for ((index, error) in result.errors.withIndex()) {
                appendLine("${index + 1}. ${error.message}")
            }
        }
    }
}

/**
 * Format an import resolution result for display.
 */
fun formatImportResolutionResult(result: ImportResolutionResult): String {
    return when (result) {
        is ImportResolutionResult.Success -> buildString {
            appendLine("✓ Imports resolved successfully")
            appendLine("  Loaded modules: ${result.loadedModules.size}")
            appendLine("  Imported bindings: ${result.importContext.importedBindings.size}")
            appendLine("  Imported types: ${result.importContext.importedTypes.size}")
            appendLine("  Imported abilities: ${result.importContext.importedAbilities.size}")
            appendLine("  Imported effects: ${result.importContext.importedEffects.size}")
        }
        is ImportResolutionResult.Failure -> buildString {
            appendLine("✗ Import resolution failed with ${result.errors.size} error(s):")
            for ((index, error) in result.errors.withIndex()) {
                appendLine("${index + 1}. ${error.message}")
            }
        }
    }
}
