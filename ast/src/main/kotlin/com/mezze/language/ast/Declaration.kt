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
    /**
     * WHICH ability, not just what it is called.
     *
     * Generalization used to keep only the name, so an imported function's
     * obligation arrived at the call site nameless-but-for-its-spelling and was
     * re-resolved in the CALLER's ability table. Two modules each declaring
     * `ability Show for 'a` then made `show_b::Show` answer a demand for
     * `show_a::Show` (BUG 24). The `FunctionAbilityConstraint` this is built from
     * already knows; it just was not carried.
     */
    val abilityHash: com.mezze.language.typedast.Hash? = null,
    val typeParam: String,
    val typeArgs: List<MezzeType> = emptyList(),
    // Associated-type bindings (`having 'out = 'c`). Carried through
    // generalization so a call site can resolve the associated type against the
    // matched impl — e.g. calling `{x,y}->x+y` must pin its `'out` return to the
    // widened numeric type. The bound values are the function's OWN type vars
    // (e.g. its return var), so they freshen alongside `typeParam`/`typeArgs`
    // during TypeScheme.instantiate().
    val associatedTypeBindings: Map<String, MezzeType> = emptyMap(),
    // Concrete receiver type when the ability's `for 'l` position resolved to a
    // concrete type at generalization time (e.g. `{x}->x+1` yields `NumAdd Int 'a`
    // where the class-arg `Int` comes from the literal). `typeParam` can only hold
    // a var NAME, so a concrete receiver would otherwise degrade to a free var and
    // the call-site obligation would never fire — laundering `f {x = "s"}` past the
    // missing `NumAdd Int Str` impl. Null when the receiver is a genuine type var.
    val concreteReceiver: MezzeType? = null
)

/**
 * Display constraint for debugging and testing purposes.
 */
@Serializable
data class InspectAbilityConstraint(
    val typeParam: String,
    val abilityName: String
)

