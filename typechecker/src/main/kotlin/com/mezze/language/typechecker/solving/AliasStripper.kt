package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.parsedast.ParsedVariantDef
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.context.DeclarationRegistry

/**
 * Strip variant-alias labels from a type when the alias name isn't in scope
 * at the current use site (#148).
 *
 * Background
 * ----------
 * A `pub type` variant alias is just a label that bundles arms together. In
 * Mezze's model, the alias name only acts as a nominal barrier in the module
 * that declares it OR in modules that explicitly `use foo::{Alias}`. Other
 * callers — which see the type only because they imported some function
 * returning it — should treat the value structurally (as its arm set).
 *
 * What this function does
 * -----------------------
 * Given a [MezzeType] and an [inScope] set (locally-declared type names plus
 * explicit `use foo::{TypeName}` imports plus built-ins), walks the type
 * recursively. For every label found on a `TypeApplication`, `VariantType`, or
 * `RecordType` that isn't in `inScope`:
 *   - If the alias is a `ParsedVariantDef`, expand it to its underlying
 *     `VariantType` with `typeName = null` and `typeHash = null`. The arms
 *     remain (recursively stripped); only the nominal label is dropped.
 *   - If the alias is a `ParsedNewtypeDef`, leave the `TypeApplication` alone.
 *     Newtypes are intentionally nominal — they hide implementation behind a
 *     constructor; stripping their label would erase the boundary they exist
 *     to draw.
 *   - If the alias is a `ParsedRecordDef`, also leave alone (records are
 *     nominal too).
 *
 * What this function does NOT do
 * -------------------------------
 * - Does not touch built-in type applications (Option, Result, Seq, Map, etc.) —
 *   those are universally in scope via the prelude.
 * - Does not modify the original [MezzeType] (returns a new value).
 * - Does not consult any thread-local state — pure transformation.
 *
 * Intended call site
 * ------------------
 * Called from `TypeChecker.typeCheck` after import resolution, before constraint
 * generation. The result is applied to each imported binding's type so that the
 * value flowing into the current module has no nominal identity for aliases
 * the user didn't import. Downstream unification then unifies arms structurally
 * without hitting nominal walls.
 */
object AliasStripper {

        fun strip(
        type: MezzeType,
        inScope: Set<String>,
        registry: DeclarationRegistry
    ): MezzeType = stripImpl(type, inScope, registry, mutableSetOf())

    private fun stripImpl(
        type: MezzeType,
        inScope: Set<String>,
        registry: DeclarationRegistry,
        recursionGuard: MutableSet<String>
    ): MezzeType = when (type) {
        is MezzeType.TypeApplication -> stripTypeApplication(type, inScope, registry, recursionGuard)
        is MezzeType.VariantType -> stripVariantType(type, inScope, registry, recursionGuard)
        is MezzeType.RecordType -> stripRecordType(type, inScope, registry, recursionGuard)
        is MezzeType.FunctionType -> MezzeType.FunctionType(
            paramType = stripImpl(type.paramType, inScope, registry, recursionGuard),
            returnType = stripImpl(type.returnType, inScope, registry, recursionGuard),
            effects = type.effects?.let { stripEffectRow(it, inScope, registry, recursionGuard) },
            abilityConstraints = stripAbilityConstraints(type.abilityConstraints, inScope, registry, recursionGuard)
        )
        is MezzeType.SeqType -> MezzeType.SeqType(stripImpl(type.elementType, inScope, registry, recursionGuard))
        is MezzeType.MapType -> MezzeType.MapType(
            keyType = stripImpl(type.keyType, inScope, registry, recursionGuard),
            valueType = stripImpl(type.valueType, inScope, registry, recursionGuard)
        )
        is MezzeType.SortedMapType -> MezzeType.SortedMapType(
            keyType = stripImpl(type.keyType, inScope, registry, recursionGuard),
            valueType = stripImpl(type.valueType, inScope, registry, recursionGuard)
        )
        is MezzeType.SortedSetType -> MezzeType.SortedSetType(
            stripImpl(type.elementType, inScope, registry, recursionGuard)
        )
        is MezzeType.ListType -> MezzeType.ListType(stripImpl(type.elementType, inScope, registry, recursionGuard))
        is MezzeType.SetType -> MezzeType.SetType(stripImpl(type.elementType, inScope, registry, recursionGuard))
        is MezzeType.SuspendType -> MezzeType.SuspendType(
            valueType = stripImpl(type.valueType, inScope, registry, recursionGuard),
            effects = stripEffectRow(type.effects, inScope, registry, recursionGuard)
        )
        is MezzeType.NewtypeType -> type.copy(
            wrappedType = stripImpl(type.wrappedType, inScope, registry, recursionGuard)
        )
        is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType,
        is MezzeType.StringType, is MezzeType.BoolType, is MezzeType.NatType,
        is MezzeType.FracType, is MezzeType.BytesType,
        is MezzeType.ByteBufferType, is MezzeType.CharType, is MezzeType.EffectRow -> type
    }

    private fun stripTypeApplication(
        type: MezzeType.TypeApplication,
        inScope: Set<String>,
        registry: DeclarationRegistry,
        recursionGuard: MutableSet<String>
    ): MezzeType {
        val strippedArgs = type.args.map { stripImpl(it, inScope, registry, recursionGuard) }

        if (type.constructor in inScope) {
            return type.copy(args = strippedArgs)
        }

        val decl = registry.lookupType(type.constructor)
            ?: ModuleResolver.lookupTypeDeclaration(type.constructor)
            ?: return type.copy(args = strippedArgs)

        if (decl.definition !is ParsedVariantDef) {
            return type.copy(args = strippedArgs)
        }

        if (!recursionGuard.add(type.constructor)) {
            return type.copy(args = strippedArgs)
        }
        try {
            val expanded = expandTypeApplicationToVariant(type.copy(args = strippedArgs), registry)
                ?: return type.copy(args = strippedArgs)
            return MezzeType.VariantType(
                constructors = expanded.constructors.mapValues { (_, payload) ->
                    payload?.let { stripImpl(it, inScope, registry, recursionGuard) }
                },
                rowVar = expanded.rowVar,
                typeName = null,
                typeHash = null
            )
        } finally {
            recursionGuard.remove(type.constructor)
        }
    }

    private fun stripVariantType(
        type: MezzeType.VariantType,
        inScope: Set<String>,
        registry: DeclarationRegistry,
        recursionGuard: MutableSet<String>
    ): MezzeType.VariantType {
        val newCtors = type.constructors.mapValues { (_, payload) ->
            payload?.let { stripImpl(it, inScope, registry, recursionGuard) }
        }
        val name = type.typeName
        val keepName = type.nominalWall ||
            name == null ||
            name in inScope ||
            (registry.lookupType(name)?.definition !is ParsedVariantDef &&
                ModuleResolver.lookupTypeDeclaration(name)?.definition !is ParsedVariantDef)
        return if (keepName) {
            type.copy(constructors = newCtors)
        } else {
            type.copy(constructors = newCtors, typeName = null, typeHash = null)
        }
    }

    private fun stripEffectRow(
        effectRow: MezzeType.EffectRow,
        inScope: Set<String>,
        registry: DeclarationRegistry,
        recursionGuard: MutableSet<String>
    ): MezzeType.EffectRow {
        if (effectRow.effectInstances.isEmpty()) return effectRow
        val strippedInstances = effectRow.effectInstances.map { instance ->
            if (instance.associatedTypes.isEmpty()) instance
            else MezzeType.EffectInstance(
                name = instance.name,
                associatedTypes = instance.associatedTypes.mapValues { (_, ty) ->
                    stripImpl(ty, inScope, registry, recursionGuard)
                }
            )
        }.toSet()
        return effectRow.copy(effectInstances = strippedInstances)
    }

    private fun stripAbilityConstraints(
        constraints: List<MezzeType.FunctionAbilityConstraint>,
        inScope: Set<String>,
        registry: DeclarationRegistry,
        recursionGuard: MutableSet<String>
    ): List<MezzeType.FunctionAbilityConstraint> {
        if (constraints.isEmpty()) return constraints
        return constraints.map { constraint ->
            val strippedArgs = constraint.typeArgs.map { stripImpl(it, inScope, registry, recursionGuard) }
            val strippedBindings = constraint.associatedTypeBindings.mapValues { (_, ty) ->
                stripImpl(ty, inScope, registry, recursionGuard)
            }
            if (strippedArgs === constraint.typeArgs && strippedBindings === constraint.associatedTypeBindings) constraint
            else constraint.copy(typeArgs = strippedArgs, associatedTypeBindings = strippedBindings)
        }
    }

    private fun stripRecordType(
        type: MezzeType.RecordType,
        inScope: Set<String>,
        registry: DeclarationRegistry,
        recursionGuard: MutableSet<String>
    ): MezzeType.RecordType {
        val newFields = type.fields.mapValues { (_, fieldType) ->
            stripImpl(fieldType, inScope, registry, recursionGuard)
        }
        return type.copy(fields = newFields)
    }
}
