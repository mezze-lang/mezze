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
    val exports: Map<String, ExportedItem>,  // Exported items (types may be incomplete)
    val dependencies: List<ModulePath>, // Module dependencies as structured paths
    val index: ProgramIndex = ProgramIndex.EMPTY,  // Pre-computed index for O(1) declaration lookups
    val sourceHash: com.mezze.language.typedast.Hash? = null,     // Content hash of source for cache validation
    /** V2 ParsedModule — the primary AST. */
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
    val contentHash: com.mezze.language.typedast.Hash  // Content hash for content-addressed lookup (REQUIRED)
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
 *
 * Every subtype IS the discriminator — no string parsing needed to recover
 * the [ErrorCode]. Subtypes override [code] (and [hints] where applicable).
 */
sealed class ModuleError(override val message: String) : Exception(message) {
    /** Stable error code; UNCATEGORIZED until migrated. */
    open val code: com.mezze.language.parsedast.diagnostics.ErrorCode =
        com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED

    /** Rust-style `= hint:` lines rendered under the snippet. */
    open val hints: List<String> = emptyList()

    /** Structured one-click fixes — LSP code actions read this; CLI ignores. */
    open val fixHints: List<com.mezze.language.parsedast.diagnostics.FixHint> = emptyList()

    data class ModuleNotFound(
        val modulePath: String,
        val searchPaths: List<String>
    ) : ModuleError("Module not found: $modulePath. Searched in: ${searchPaths.joinToString(", ")}") {
        override val code = com.mezze.language.parsedast.diagnostics.ErrorCode.M0801_MODULE_NOT_FOUND
        override val hints: List<String> = listOf(
            "check the module path spelling and ensure the file exists"
        )
    }

    data class CircularDependency(
        val cycle: List<String>
    ) : ModuleError("Circular dependency detected: ${cycle.joinToString(" -> ")}") {
        override val code = com.mezze.language.parsedast.diagnostics.ErrorCode.M0802_CIRCULAR_DEPENDENCY
        override val hints: List<String> = listOf(
            "break the cycle by removing one of the imports or extracting shared code to a third module"
        )
    }

    data class ParseError(
        val modulePath: String,
        val error: String
    ) : ModuleError("Failed to parse module $modulePath: $error") {
        override val code = com.mezze.language.parsedast.diagnostics.ErrorCode.M0804_MODULE_HAS_ERRORS
        override val hints: List<String> = listOf(
            "fix the parse errors in $modulePath first"
        )
    }

    data class ImportError(
        val modulePath: String,
        val itemName: String,
        val reason: String
    ) : ModuleError("Cannot import '$itemName' from '$modulePath': $reason") {
        override val code = com.mezze.language.parsedast.diagnostics.ErrorCode.M0804_MODULE_HAS_ERRORS
        override val hints: List<String> = listOf(
            "fix the underlying error in $modulePath, then re-import"
        )
    }

    data class ExportNotFound(
        val modulePath: String,
        val exportName: String,
        val loc: com.mezze.language.parsedast.Loc? = null,
        val availableExports: List<String> = emptyList()
    ) : ModuleError("Export '$exportName' not found in module '$modulePath'") {
        override val code = com.mezze.language.parsedast.diagnostics.ErrorCode.M0803_NOT_EXPORTED
        override val hints: List<String>
            get() = buildList {
                val closest = com.mezze.language.parsedast.diagnostics.LevenshteinSuggest
                    .closest(exportName, availableExports)
                if (closest != null) {
                    add("did you mean `$closest`?")
                }
                add("check that the symbol is declared `pub` in $modulePath")
            }
        override val fixHints: List<com.mezze.language.parsedast.diagnostics.FixHint>
            get() {
                if (loc == null || availableExports.isEmpty()) return emptyList()
                val close = com.mezze.language.parsedast.diagnostics
                    .LevenshteinSuggest.closestN(exportName, availableExports, 3)
                return if (close.isNotEmpty())
                    listOf(com.mezze.language.parsedast.diagnostics.FixHint.Rename(loc, close))
                else emptyList()
            }
    }

    /**
     * Thrown when trying to import an item that exists but is not public.
     */
    data class PrivateExport(
        val modulePath: String,
        val exportName: String,
        val loc: com.mezze.language.parsedast.Loc? = null
    ) : ModuleError("'$exportName' exists in module '$modulePath' but is not public. Add 'pub' to export it.") {
        override val code = com.mezze.language.parsedast.diagnostics.ErrorCode.M0803_NOT_EXPORTED
        override val hints: List<String> = listOf(
            "add `pub` before the declaration in the source module"
        )
        override val fixHints: List<com.mezze.language.parsedast.diagnostics.FixHint>
            get() = if (loc != null)
                listOf(com.mezze.language.parsedast.diagnostics.FixHint.AddPub(loc, modulePath, exportName))
            else emptyList()
    }

    // TODO: no dedicated M code for internal module access yet — leaving UNCATEGORIZED
    // until an M0805 (or similar) is allocated in ErrorCode.kt.
    data class InternalModuleAccess(
        val fromModule: String,
        val toModule: String,
        val loc: com.mezze.language.parsedast.Loc? = null
    ) : ModuleError(
        if (fromModule.isEmpty())
            "Cannot access internal module '$toModule' from outside its package"
        else
            "Module '$fromModule' cannot access internal module '$toModule'"
    ) {
        override val hints: List<String> = listOf(
            "internal modules can only be used by sibling modules inside the same package"
        )
    }
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
        val loc: com.mezze.language.parsedast.Loc? = null
    )
}

/**
 * A module that has been type-checked.
 */
data class TypedModule(
    val modulePath: String,                           // Module path
    val exports: Map<String, ExportedItem>,           // Exports with complete types
    val context: TypingContext,                       // Module-specific typing context
    val substitution: Substitution,                   // Type substitution from inference
    val dependencyGraph: com.mezze.language.typechecker.dependencies.DependencyGraph =
        com.mezze.language.typechecker.dependencies.DependencyGraph.EMPTY,  // Dependencies between definitions
    /** V2 TypedModule emitted by TypedAstBuilder — used by monomorphizer */
    val typedModule: com.mezze.language.typedast.TypedModule? = null,
    /** V2 ParsedModule — the primary AST representation. */
    val parsedModule: com.mezze.language.parsedast.ParsedModule? = null,
    /** Extracted type context: name → inferred MezzeType. Built once at type-check time. */
    val typeContext: Map<String, MezzeType> = emptyMap(),
    /** Source file path (carried through from LoadedModule) so the persistent
     *  typed_modules cache can key by (modulePath, sourceHash). null for
     *  in-memory / scratch modules. */
    val filePath: String? = null,
    /** SHA-256 of the source bytes when this module was typechecked. */
    val sourceHash: com.mezze.language.typedast.Hash? = null,
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
        val loadedModules: Map<ModulePath, TypedModule>
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
@kotlinx.serialization.Serializable
data class ModuleImportContext(
    val importedBindings: Map<String, ImportedBinding>,      // Imported functions/values (keyed by name)
    val importedTypes: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>,         // Imported type declarations (keyed by name)
    val importedAbilities: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl>,  // Imported abilities (keyed by name)
    val importedEffects: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,     // Imported effects (keyed by name)
    val importedImpls: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl>,         // Imported effect implementations (keyed by performer name)
    val importedAbilityImpls: Map<com.mezze.language.typedast.Hash, com.mezze.language.parsedast.ParsedImplAbilityDecl>,  // Imported ability implementations (keyed by content hash)
    // Content-hash indexed maps for O(1) lookup without string parsing
    val effectsByHash: Map<com.mezze.language.typedast.Hash, com.mezze.language.parsedast.ParsedEffectDecl> = emptyMap(),    // Effects keyed by content hash
    val abilitiesByHash: Map<com.mezze.language.typedast.Hash, com.mezze.language.parsedast.ParsedAbilityDecl> = emptyMap(),  // Abilities keyed by content hash
    // Pre-inferred return types for inherent impl operations, harvested from the
    // home module's TypedAbilityImpl. Keyed by impl content hash, then op name.
    // ContextBuilder uses these instead of fresh TypeVars when registering an
    // imported inherent impl whose operation has no return-type annotation.
    val inherentImplOpReturnTypes: Map<com.mezze.language.typedast.Hash, Map<String, com.mezze.language.ast.MezzeType>> = emptyMap(),
    // Pre-inferred PARAM-record types for inherent impl operations (issue #230).
    // Keyed by impl content hash → op name → MezzeType (a RecordType of fields).
    // Without this, callers like `nums.all {p = 5}` typecheck because the impl
    // op registered a fresh TypeVar for `p` instead of the solver-discovered
    // `{x: 'a} -> Bool`. Harvested same way as return types.
    val inherentImplOpParamTypes: Map<com.mezze.language.typedast.Hash, Map<String, com.mezze.language.ast.MezzeType>> = emptyMap()
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
                abilitiesByHash = emptyMap(),
                inherentImplOpReturnTypes = emptyMap(),
                inherentImplOpParamTypes = emptyMap()
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
            abilitiesByHash = abilitiesByHash + other.abilitiesByHash,
            inherentImplOpReturnTypes = inherentImplOpReturnTypes + other.inherentImplOpReturnTypes,
            inherentImplOpParamTypes = inherentImplOpParamTypes + other.inherentImplOpParamTypes
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
    fun getEffectByHash(contentHash: com.mezze.language.typedast.Hash): com.mezze.language.parsedast.ParsedEffectDecl? = effectsByHash[contentHash]

    /**
     * Look up an ability by its content hash.
     * This is the preferred lookup method - O(1) with no string parsing.
     */
    fun getAbilityByHash(contentHash: com.mezze.language.typedast.Hash): com.mezze.language.parsedast.ParsedAbilityDecl? = abilitiesByHash[contentHash]
}

/**
 * An imported binding (function, value, etc.).
 */
@kotlinx.serialization.Serializable
data class ImportedBinding(
    val name: String,                  // Local name (may be aliased)
    val originalName: String,          // Original name in source module
    val modulePath: String,            // Source module path
    val type: MezzeType,               // Type of the binding
    val isQualified: Boolean = false,  // true for qualified imports (module::name)
    val loc: com.mezze.language.parsedast.Loc? = null,
    val contentHash: com.mezze.language.typedast.Hash  // Content hash for content-addressed lookup (REQUIRED)
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
