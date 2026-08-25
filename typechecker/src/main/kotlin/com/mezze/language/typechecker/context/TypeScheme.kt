package com.mezze.language.typechecker.context

import com.mezze.language.ast.AbilityConstraint
import com.mezze.language.ast.MezzeType
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

        val substitution = quantifiedVars.associateWith {
            MezzeType.VarType(TypeVar.fresh())
        }

        val instantiatedType = applySubstitutionToType(type, substitution)

        val nameToFreshVar = mutableMapOf<String, TypeVar>()
        for ((oldVar, newType) in substitution) {
            val baseName = oldVar.name.removePrefix("'")
            nameToFreshVar[baseName] = newType.variable
            nameToFreshVar["'$baseName"] = newType.variable
            nameToFreshVar[oldVar.name] = newType.variable
        }

        val freshConstraintsWithVars = constraints.mapNotNull { constraint ->
            val baseName = constraint.typeParam.removePrefix("'")
            val freshTypeVar = nameToFreshVar[baseName] ?: nameToFreshVar[constraint.typeParam]
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
                    typeVar = freshTypeVar,
                    typeArgs = instantiatedTypeArgs,
                    associatedTypeBindings = instantiatedBindings,
                    concreteReceiver = instantiatedReceiver
                )
            } else {
                FreshAbilityConstraint(
                    abilityName = constraint.abilityName,
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
            rowVar = type.rowVar, typeName = type.typeName, typeHash = type.typeHash
        )
    } else type
    is MezzeType.VariantType -> if (type.constructors.values.any { it is MezzeType.FunctionType }) {
        MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, dt) -> dt?.let { clearAbilityConstraints(it) } },
            rowVar = type.rowVar, typeName = type.typeName,
            typeArgs = type.typeArgs, typeHash = type.typeHash, ascriptionWall = type.ascriptionWall
        )
    } else type
    else -> type
}

