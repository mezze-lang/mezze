package com.mezze.language.ast

import kotlinx.serialization.Serializable

/**
 * Type checking results, IDE support types, and shared constraint types.
 */


// TypeCheckResult, TypeCheckWarning, ResolvedImport, ImportKind moved to
// typechecker module: com.mezze.language.typechecker.TypeCheckOutput.kt


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

