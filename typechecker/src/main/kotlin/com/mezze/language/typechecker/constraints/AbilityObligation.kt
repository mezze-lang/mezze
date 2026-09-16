package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.Hash

/**
 * Turning a called function's WRITTEN ability obligation (`with Decode JsonC 'a`)
 * into a call-site [Constraint.AbilityRequired] the solver will actually check.
 *
 * Three sites did this independently — the plain-call path, the inherent-method
 * path and the ability-method path — and all three read the obligation the same
 * wrong way:
 *
 *     val head = constraint.typeArgs.firstOrNull()
 *     if (head is MezzeType.VarType) { …emit… }      // else: SILENTLY DROPPED
 *
 * That assumes the variable an obligation is *about* is always its FIRST type
 * argument. It holds for `Ord 'a` and for `FromJson 'a for 'in`, which is why the
 * shape survived. It does not hold for the codec abilities: `Decode 'config 'a
 * for 'in` puts the config first, so `with Decode JsonC 'a` has a CONCRETE head
 * and the whole obligation was dropped — no `Decode` constraint ever reached the
 * caller's solve. (Measured: the only `Decode` constraint the solver ever saw
 * came from `from_json`'s own declaration, in a solve DISJOINT from the caller's,
 * where `'a` is correctly universally quantified and pins nothing.)
 *
 * The consequence was not a type error but a missing one: the decode target was
 * never constrained, so it had to be recovered downstream by reading the field
 * accesses off the decoded value — which is what `DecodeTargetResolver` existed
 * to do, and why two `from_json` calls of different shapes in one scope could not
 * be told apart.
 *
 * Scan for the variable at ANY position instead, and the obligation lands in the
 * caller's solve carrying that call's own target var. The `HasField` constraints
 * the usage already generates pin it there, and the two halves that were always
 * present — `finalizeDeferredAbilities` running post-substitution, and
 * `checkAbilityImplementation` synthesising a codec impl for concrete arguments —
 * discharge it with no further machinery.
 */
internal fun abilityObligationAtCallSite(
    constraint: MezzeType.FunctionAbilityConstraint,
    abilityHash: Hash?,
    context: String,
    loc: Loc?,
    freshen: (TypeVar) -> TypeVar = { it },
): Constraint.AbilityRequired? {
    val varIndex = constraint.typeArgs.indexOfFirst { it is MezzeType.VarType }
    if (varIndex < 0) return null
    val primary = (constraint.typeArgs[varIndex] as MezzeType.VarType).variable

    return Constraint.AbilityRequired(
        typeVar = freshen(primary),
        abilityName = constraint.abilityName,
        abilityHash = abilityHash,
        // POSITION IS THE MEANING here, and `AbilityRequired` splits the tuple
        // into `typeVar` + `typeArgs` without recording where the split happened.
        // `AbilityChecker.trackAbilityConstraint` rebuilds the full positional
        // tuple by prepending `typeVar` when `typeArgs` is short of the ability's
        // declared arity — which reconstructs the original list exactly when the
        // variable was at the head, and would put it at the WRONG SLOT otherwise
        // (`Decode JsonC 'a` becoming `Decode 'a JsonC`, matching no impl). So
        // hand over the whole tuple when the variable sits anywhere but the head:
        // it already fills the arity, and the tracker passes it through untouched.
        typeArgs = if (varIndex == 0) constraint.typeArgs.drop(1) else constraint.typeArgs,
        context = context,
        loc = loc,
    )
}
