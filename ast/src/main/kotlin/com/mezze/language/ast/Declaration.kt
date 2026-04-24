package com.mezze.language.ast

import kotlinx.serialization.Serializable

/**
 * Type checking results, IDE support types, and shared constraint types.
 */


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


@Serializable
data class AbilityConstraint(
    val abilityName: String,
    val typeParam: String,
    val typeArgs: List<MezzeType> = emptyList()
)

/**
 * Display constraint for debugging and testing purposes.
 */
@Serializable
data class InspectAbilityConstraint(
    val typeParam: String,
    val abilityName: String
)

