package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.parsedast.ParsedVariantDef

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

    /**
     * Strip alias labels not in [inScope] from [type].
     * [typeDecls] is consulted to look up declarations for stripping decisions.
     */
    fun strip(
        type: MezzeType,
        inScope: Set<String>,
        typeDecls: Map<String, ParsedTypeDecl>
    ): MezzeType = stripImpl(type, inScope, typeDecls, mutableSetOf())

    private fun stripImpl(
        type: MezzeType,
        inScope: Set<String>,
        typeDecls: Map<String, ParsedTypeDecl>,
        recursionGuard: MutableSet<String>
    ): MezzeType = when (type) {
        is MezzeType.TypeApplication -> stripTypeApplication(type, inScope, typeDecls, recursionGuard)
        is MezzeType.VariantType -> stripVariantType(type, inScope, typeDecls, recursionGuard)
        is MezzeType.RecordType -> stripRecordType(type, inScope, typeDecls, recursionGuard)
        is MezzeType.FunctionType -> MezzeType.FunctionType(
            paramType = stripImpl(type.paramType, inScope, typeDecls, recursionGuard),
            returnType = stripImpl(type.returnType, inScope, typeDecls, recursionGuard),
            effects = type.effects?.let { stripEffectRow(it, inScope, typeDecls, recursionGuard) },
            abilityConstraints = stripAbilityConstraints(type.abilityConstraints, inScope, typeDecls, recursionGuard)
        )
        is MezzeType.SeqType -> MezzeType.SeqType(stripImpl(type.elementType, inScope, typeDecls, recursionGuard))
        is MezzeType.MapType -> MezzeType.MapType(
            keyType = stripImpl(type.keyType, inScope, typeDecls, recursionGuard),
            valueType = stripImpl(type.valueType, inScope, typeDecls, recursionGuard)
        )
        is MezzeType.SortedMapType -> MezzeType.SortedMapType(
            keyType = stripImpl(type.keyType, inScope, typeDecls, recursionGuard),
            valueType = stripImpl(type.valueType, inScope, typeDecls, recursionGuard)
        )
        is MezzeType.SortedSetType -> MezzeType.SortedSetType(
            stripImpl(type.elementType, inScope, typeDecls, recursionGuard)
        )
        is MezzeType.SuspendedType -> MezzeType.SuspendedType(
            valueType = stripImpl(type.valueType, inScope, typeDecls, recursionGuard),
            effects = stripEffectRow(type.effects, inScope, typeDecls, recursionGuard)
        )
        // Leaves: nothing to strip
        else -> type
    }

    private fun stripTypeApplication(
        type: MezzeType.TypeApplication,
        inScope: Set<String>,
        typeDecls: Map<String, ParsedTypeDecl>,
        recursionGuard: MutableSet<String>
    ): MezzeType {
        // Strip args first
        val strippedArgs = type.args.map { stripImpl(it, inScope, typeDecls, recursionGuard) }

        // If the alias name is in scope, retain the nominal application.
        // Built-ins (Option/Result/Seq/etc.) are always in scope by virtue of
        // the prelude — callers add `BuiltinTypes.allBuiltinTypeNames` to
        // the in-scope set before invoking the stripper.
        if (type.constructor in inScope) {
            return type.copy(args = strippedArgs)
        }

        // Look up the declaration. If unavailable (synthetic / type-var), leave alone.
        val decl = typeDecls[type.constructor]
            ?: com.mezze.language.typechecker.ModuleResolver.lookupTypeDeclaration(type.constructor)
            ?: return type.copy(args = strippedArgs)

        // Only variant aliases get stripped. Newtypes and records stay nominal.
        if (decl.definition !is ParsedVariantDef) {
            return type.copy(args = strippedArgs)
        }

        // Avoid infinite recursion on self-referential variant aliases.
        if (!recursionGuard.add(type.constructor)) {
            return type.copy(args = strippedArgs)
        }
        try {
            val expanded = expandTypeApplicationToVariant(type.copy(args = strippedArgs), typeDecls)
                ?: return type.copy(args = strippedArgs)
            // Drop the nominal label.
            return MezzeType.VariantType(
                constructors = expanded.constructors.mapValues { (_, payload) ->
                    payload?.let { stripImpl(it, inScope, typeDecls, recursionGuard) }
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
        typeDecls: Map<String, ParsedTypeDecl>,
        recursionGuard: MutableSet<String>
    ): MezzeType.VariantType {
        val newCtors = type.constructors.mapValues { (_, payload) ->
            payload?.let { stripImpl(it, inScope, typeDecls, recursionGuard) }
        }
        val name = type.typeName
        // Walls always keep their label and structure — these are intentional
        // ascription/inferred barriers, not user-visible aliases.
        // Built-ins are kept too — they're added to `inScope` by the caller.
        val keepName = type.nominalWall ||
            name == null ||
            name in inScope ||
            // Records / newtypes can also share a name with a VariantType representation;
            // only strip when the actual decl is a variant alias.
            (typeDecls[name]?.definition !is ParsedVariantDef &&
                com.mezze.language.typechecker.ModuleResolver.lookupTypeDeclaration(name)?.definition !is ParsedVariantDef)
        return if (keepName) {
            type.copy(constructors = newCtors)
        } else {
            type.copy(constructors = newCtors, typeName = null, typeHash = null)
        }
    }

    private fun stripEffectRow(
        effectRow: MezzeType.EffectRow,
        inScope: Set<String>,
        typeDecls: Map<String, ParsedTypeDecl>,
        recursionGuard: MutableSet<String>
    ): MezzeType.EffectRow {
        if (effectRow.effectInstances.isEmpty()) return effectRow
        val strippedInstances = effectRow.effectInstances.map { instance ->
            if (instance.associatedTypes.isEmpty()) instance
            else MezzeType.EffectInstance(
                name = instance.name,
                associatedTypes = instance.associatedTypes.mapValues { (_, ty) ->
                    stripImpl(ty, inScope, typeDecls, recursionGuard)
                }
            )
        }.toSet()
        return effectRow.copy(effectInstances = strippedInstances)
    }

    private fun stripAbilityConstraints(
        constraints: List<MezzeType.FunctionAbilityConstraint>,
        inScope: Set<String>,
        typeDecls: Map<String, ParsedTypeDecl>,
        recursionGuard: MutableSet<String>
    ): List<MezzeType.FunctionAbilityConstraint> {
        if (constraints.isEmpty()) return constraints
        return constraints.map { constraint ->
            val strippedArgs = constraint.typeArgs.map { stripImpl(it, inScope, typeDecls, recursionGuard) }
            val strippedBindings = constraint.associatedTypeBindings.mapValues { (_, ty) ->
                stripImpl(ty, inScope, typeDecls, recursionGuard)
            }
            if (strippedArgs === constraint.typeArgs && strippedBindings === constraint.associatedTypeBindings) constraint
            else constraint.copy(typeArgs = strippedArgs, associatedTypeBindings = strippedBindings)
        }
    }

    private fun stripRecordType(
        type: MezzeType.RecordType,
        inScope: Set<String>,
        typeDecls: Map<String, ParsedTypeDecl>,
        recursionGuard: MutableSet<String>
    ): MezzeType.RecordType {
        // Records stay nominal — never strip the typeName.
        val newFields = type.fields.mapValues { (_, fieldType) ->
            stripImpl(fieldType, inScope, typeDecls, recursionGuard)
        }
        return type.copy(fields = newFields)
    }
}
