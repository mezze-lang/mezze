package com.mezze.language.ast

import kotlinx.serialization.Serializable

/**
 * AST nodes for type checking results and shared declaration types.
 *
 * This file contains:
 * - TypeCheckResult: Result of type checking (uses v2 TypedModule)
 * - Comment, ResolvedImport, ImportKind: IDE/LSP support types
 * - AbilityConstraint, FunctionAbilityConstraint, InspectAbilityConstraint, AbilityDependency: Shared constraint types
 * - Visibility: pub/private visibility enum
 *
 * V1 stub types (Program, ProgramItem, LetBinding, EffectDeclaration, EffectOperation,
 * AbilityDeclaration, AbilityOperation, TopLevelExpression, UseDeclaration, PubUseDeclaration)
 * have been deleted. The v2 pipeline uses ParsedModule/TypedModule instead.
 */

// ============================================================================
// COMMENTS
// ============================================================================

/**
 * A comment in the source code, preserved for the formatter.
 *
 * @param text The full comment text including the '#' character
 * @param line The line number where the comment appears (1-indexed)
 * @param column The column number where the comment starts (1-indexed)
 * @param isTrailing True if the comment is on the same line as code (trailing comment),
 *                   false if it's on its own line (leading comment)
 */
@Serializable
data class Comment(
    val text: String,
    val line: Int,
    val column: Int,
    val isTrailing: Boolean = false
)

// ============================================================================
// RESOLVED IMPORTS
// ============================================================================

/**
 * A resolved import with type information, for IDE support.
 */
@Serializable
data class ResolvedImport(
    val localName: String,           // Name as used in the importing file
    val originalName: String,        // Name in the source module
    val modulePath: String,          // Source module path (e.g., "std::effects::log")
    val type: MezzeType,             // Resolved type of the import
    val kind: ImportKind             // What kind of thing was imported
)

@Serializable
enum class ImportKind {
    FUNCTION,   // A let binding / function
    TYPE,       // A type declaration
    EFFECT,     // An effect declaration
    ABILITY,    // An ability declaration
    PERFORMER   // An effect performer (impl)
}

// ============================================================================
// TYPE CHECK RESULT
// ============================================================================

@Serializable
data class TypeCheckResult(
    val resolvedImports: List<ResolvedImport> = emptyList(),
    val warnings: List<TypeCheckWarning> = emptyList(),
    val importedAbilities: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl> = emptyMap(),
    val importedEffects: Map<String, com.mezze.language.parsedast.ParsedEffectDecl> = emptyMap(),
    val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR> = emptyList(),
    val typedModule: com.mezze.language.typedast.TypedModule? = null,  // The real typechecker output
    /** Performer record info for effect handler registration: List of [performerHash, effectHash, op1Hash, op1Name, ...] */
    val performerRecords: List<Array<String>> = emptyList(),
    /** Loaded modules (from import resolution) — includes their v2 TypedModules for compilation */
    val loadedModules: Map<String, com.mezze.language.typedast.TypedModule> = emptyMap()
) {
    /** Get inferred type of a top-level binding by name */
    fun getVariableType(name: String): MezzeType? {
        return typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == name }
            ?.scheme?.type
    }
}

/**
 * A warning from type checking (not a fatal error).
 */
@Serializable
data class TypeCheckWarning(
    val message: String,
    val sourceLocation: SourceLocation? = null
)

// ============================================================================
// ABILITY CONSTRAINTS (shared between v1 and v2)
// ============================================================================

@Serializable
data class AbilityConstraint(
    val abilityName: String,
    val typeParam: String,
    val typeArgs: List<MezzeType> = emptyList()
)

/**
 * Ability constraint in function type signatures.
 * TypeAnnotation references replaced with String type representations.
 */
@Serializable
data class FunctionAbilityConstraint(
    val abilityName: String,
    val typeArgNames: List<String> = emptyList(),
    val associatedTypeBindingNames: Map<String, String> = emptyMap()
) {
    val hasAssociatedTypes: Boolean get() = associatedTypeBindingNames.isNotEmpty()
}

/**
 * Display constraint for debugging and testing purposes.
 */
@Serializable
data class InspectAbilityConstraint(
    val typeParam: String,
    val abilityName: String
)

@Serializable
data class AbilityDependency(
    val abilityName: String,
    val typeArgNames: List<String> = emptyList()
)
