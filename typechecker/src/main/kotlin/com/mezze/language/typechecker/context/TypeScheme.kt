package com.mezze.language.typechecker.context

import com.mezze.language.ast.AbilityConstraint
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.solving.Substitution

data class TypeScheme(
    val quantifiedVars: List<TypeVar>,
    val constraints: List<AbilityConstraint>,
    val type: MezzeType
) {
        val isMonomorphic: Boolean
        get() = quantifiedVars.isEmpty()

        fun instantiate(): InstantiationResult {
        if (isMonomorphic) {
            val monoConstraints = constraints.map { constraint ->
                FreshAbilityConstraint(
                    abilityName = constraint.abilityName,
                    abilityHash = constraint.abilityHash,
                    typeVar = TypeVar.freshWithName(constraint.typeParam),
                    typeArgs = constraint.typeArgs,
                    associatedTypeBindings = constraint.associatedTypeBindings,
                    concreteReceiver = constraint.concreteReceiver
                )
            }
            return InstantiationResult(
                type = type,
                substitution = emptyMap(),
                freshConstraints = monoConstraints
            )
        }

        // A constraint's `having` binding variable is freshened per instantiation
        // even though generalization does not quantify it.
        //
        // Without this, a declaration and every one of its call sites share ONE
        // variable, so the first call to discharge the obligation pins it for the
        // rest — and the rejection depends on source order:
        //
        //     let ignore = { x } -> 0
        //     let f = { a, b } -> ignore { x = a + b }
        //     let i = f { a = 1, b = 2 }        -- pins the shared binding to Int
        //     let g = f { a = 1.5, b = 2.5 }    -- "expected Float but got Int"
        //
        // `'out` there is swallowed by `ignore`'s parameter, so it reaches no
        // position a caller can see and nothing else separates the two sites.
        //
        // Freshening HERE rather than quantifying in `collectFreeTypeVars` is
        // deliberate and measured: quantifying changes the scheme's shape, which
        // mono and evidence selection read, and it produced a wrong answer
        // (`sum_to {n=5, acc=0}` followed by `3 + 4` evaluated to 15). This keeps
        // the shape identical and only stops the sharing.
        val havingVars = constraints.flatMap { c ->
            c.associatedTypeBindings.values.mapNotNull { (it as? MezzeType.VarType)?.variable }
        }.filterNot { quantifiedVars.contains(it) }.distinct()
        val substitution: Map<TypeVar, MezzeType.VarType> = quantifiedVars.associateWith {
            MezzeType.VarType(TypeVar.fresh())
        } + havingVars.associateWith {
            MezzeType.VarType(TypeVar.freshWithName(it.name))
        }

        val instantiatedType = applySubstitutionToType(type, substitution)

        // L-08/L-09: a scheme constraint names its type variable by SPELLING
        // (`AbilityConstraint.typeParam` is a String), and the map below resolves
        // that spelling to the fresh variable. Two quantified variables sharing a
        // base name would therefore have one silently overwrite the other, and the
        // constraint would be linked to whichever wrote last — the wrong variable,
        // producing the wrong impl at the call site with no diagnostic.
        //
        // MEASURED at zero occurrences across both suites, so this guard costs
        // nothing today. It exists because "never happens" and "cannot happen" are
        // different claims, and only the second one is safe to rely on silently.
        // The real fix is for the constraint to carry the variable's identity
        // rather than its name; until then, an ambiguity fails loudly.
        // Only constraints that must fall back to the NAME can be mislinked; one
        // carrying its variable is resolved by identity below and is immune. So
        // the guard asks about those, not about every constraint.
        if (constraints.any { it.typeVar == null }) {
            for ((base, vars) in quantifiedVars.groupBy { it.name.removePrefix("'") }) {
                if (vars.size > 1 && constraints.any { it.typeVar == null && it.typeParam.removePrefix("'") == base }) {
                    error(
                        "Cannot instantiate this type scheme: ${vars.size} distinct quantified type " +
                            "variables are all spelled '$base (ids ${vars.map { it.id }}), and an " +
                            "ability constraint refers to that spelling WITHOUT carrying its variable. " +
                            "Linking it would pick one of them arbitrarily. Whichever site built that " +
                            "constraint should pass `typeVar` — it has the variable in hand. This is a " +
                            "compiler bug rather than a problem with the program; please report the " +
                            "source that triggered it."
                    )
                }
            }
        }
        val nameToFreshVar = mutableMapOf<String, TypeVar>()
        for ((oldVar, newType) in substitution) {
            val baseName = oldVar.name.removePrefix("'")
            nameToFreshVar[baseName] = newType.variable
            nameToFreshVar["'$baseName"] = newType.variable
            nameToFreshVar[oldVar.name] = newType.variable
        }

        val freshConstraintsWithVars = constraints.mapNotNull { constraint ->
            // Resolve by IDENTITY. `substitution` is keyed by the TypeVar itself,
            // so a constraint that carries its variable needs no name lookup at
            // all — and cannot be mislinked when two quantified variables happen
            // to share a spelling.
            //
            // The name map below remains for constraints built before this field
            // existed, and for any path that still cannot supply the variable. It
            // is the thing that could mislink, which is why the guard above
            // refuses an ambiguous spelling outright rather than picking one.
            val freshTypeVar = constraint.typeVar?.let { (substitution[it] as? MezzeType.VarType)?.variable }
                ?: run {
                    val baseName = constraint.typeParam.removePrefix("'")
                    nameToFreshVar[baseName] ?: nameToFreshVar[constraint.typeParam]
                }
            val instantiatedTypeArgs = constraint.typeArgs.map { applySubstitutionToType(it, substitution) }
            // Freshen the associated-type binding values through the SAME
            // substitution as the type, so a binding like `'out = 'c` points at the
            // instance's fresh `'c` (the call's return var) — the load-bearing link
            // for resolving a called `+`-fn's result type.
            val instantiatedBindings = constraint.associatedTypeBindings
                .mapValues { (_, v) -> applySubstitutionToType(v, substitution) }
            val instantiatedReceiver = constraint.concreteReceiver?.let { applySubstitutionToType(it, substitution) }
            if (freshTypeVar != null) {
                FreshAbilityConstraint(
                    abilityName = constraint.abilityName,
                    abilityHash = constraint.abilityHash,
                    typeVar = freshTypeVar,
                    typeArgs = instantiatedTypeArgs,
                    associatedTypeBindings = instantiatedBindings,
                    concreteReceiver = instantiatedReceiver
                )
            } else {
                FreshAbilityConstraint(
                    abilityName = constraint.abilityName,
                    abilityHash = constraint.abilityHash,
                    typeVar = TypeVar.freshWithName(constraint.typeParam),
                    typeArgs = instantiatedTypeArgs,
                    associatedTypeBindings = instantiatedBindings,
                    concreteReceiver = instantiatedReceiver
                )
            }
        }

        return InstantiationResult(
            type = instantiatedType,
            substitution = substitution,
            freshConstraints = freshConstraintsWithVars
        )
    }

        data class InstantiationResult(
        val type: MezzeType,
        val substitution: Map<TypeVar, MezzeType>,
        val freshConstraints: List<FreshAbilityConstraint>
    )

        data class FreshAbilityConstraint(
        val abilityName: String,
        /** The ability's identity, carried from the scheme — see AbilityConstraint. */
        val abilityHash: com.mezze.language.typedast.Hash? = null,
        val typeVar: TypeVar,
        val typeArgs: List<MezzeType> = emptyList(),
        // Associated-type bindings freshened alongside typeVar/typeArgs. The bound
        // values are the callee's own (now instantiated) type vars — e.g. the
        // fresh return var for a `+`-using fn's `'out` — so the call site can emit
        // an AssociatedTypeResolution that pins them to the matched impl's output.
        val associatedTypeBindings: Map<String, MezzeType> = emptyMap(),
        // Concrete receiver when the ability's `for 'l` was concrete at
        // generalization (e.g. `NumAdd Int 'a` from `{x}->x+1`). The call site uses
        // this instead of `typeVar` for the receiver position so the obligation
        // fires (and rejects a bad receiver like Str) rather than resolving vacuously.
        val concreteReceiver: MezzeType? = null
    )

    companion object {
                fun mono(type: MezzeType): TypeScheme {
            return TypeScheme(
                quantifiedVars = emptyList(),
                constraints = emptyList(),
                type = type
            )
        }
    }
}

private fun applySubstitutionToType(
    type: MezzeType,
    substitution: Map<TypeVar, MezzeType>
): MezzeType {
    val applied = Substitution(substitution).apply(type)
    return clearAbilityConstraints(applied)
}

private fun clearAbilityConstraints(type: MezzeType): MezzeType = when (type) {
    is MezzeType.FunctionType -> MezzeType.FunctionType(
        paramType = clearAbilityConstraints(type.paramType),
        returnType = clearAbilityConstraints(type.returnType),
        effects = type.effects,
        abilityConstraints = emptyList()
    )
    is MezzeType.RecordType -> if (type.fields.values.any { it is MezzeType.FunctionType }) {
        MezzeType.RecordType(
            fields = type.fields.mapValues { (_, ft) -> clearAbilityConstraints(ft) },
            rowVar = type.rowVar, identity = type.identity
        )
    } else type
    is MezzeType.VariantType -> if (type.constructors.values.any { it is MezzeType.FunctionType }) {
        MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, dt) -> dt?.let { clearAbilityConstraints(it) } },
            rowVar = type.rowVar,
            typeArgs = type.typeArgs, identity = type.identity, ascriptionWall = type.ascriptionWall
        )
    } else type
    else -> type
}

