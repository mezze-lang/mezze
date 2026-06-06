package com.mezze.language.typechecker.abilities

import com.mezze.language.ast.BuiltinAbilities as AstAbilities
import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant

/**
 * Shared utility for checking built-in ability implementations.
 *
 * This consolidates the logic that was duplicated in TypeValidator and ConstraintSolver.
 * Handles checking whether a type has a built-in implementation for a given ability.
 */
object AbilityImplementationChecker {

    /**
     * Check if a type has a built-in implementation for the given ability.
     *
     * @param abilityName The name of the ability to check
     * @param type The type to check
     * @param typeDeclarations Type declarations for expanding TypeApplication to VariantType
     * @param expandTypeApplication Function to expand TypeApplication to VariantType
     * @return true if the type has a built-in implementation for the ability
     */
    fun hasBuiltInImplementation(
        abilityName: String,
        type: MezzeType,
        registry: DeclarationRegistry
    ): Boolean {
        // Unwrap SuspendedType to check the inner value type
        var unwrappedType = if (type is MezzeType.SuspendedType) type.valueType else type

        // Expand TypeApplication to VariantType if possible
        // This allows deriving Eq/Ord for named variant types like Option, Result, etc.
        if (unwrappedType is MezzeType.TypeApplication) {
            val expanded = expandTypeApplicationToVariant(unwrappedType, registry)
            if (expanded != null) {
                unwrappedType = expanded
            }
        }

        return when (abilityName) {
            // Numeric abilities that work with Nat
            AstAbilities.NUM_ADD, AstAbilities.NUM_MUL, AstAbilities.NUM_DIV, AstAbilities.NUM_MOD -> {
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.NatType ||
                unwrappedType is MezzeType.FracType
            }

            // NumSub (includes neg) doesn't work with Nat (result can be negative)
            AstAbilities.NUM_SUB -> {
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.FracType
            }

            // Equality - implemented for most primitive types and derivable for records/variants/lists
            AstAbilities.EQ -> hasEqImplementation(unwrappedType, registry)

            // Ordering - implemented for comparable types and derivable for records/variants/lists
            AstAbilities.ORD -> hasOrdImplementation(unwrappedType, registry)

            // Inspect - built-in ability for displaying values (to_str)
            // CRITICAL: Inspect is universally available for ALL types
            AstAbilities.INSPECT -> true

            // Logical operations - only for Bool
            AstAbilities.LOGICAL_AND, AstAbilities.LOGICAL_OR, AstAbilities.LOGICAL_NOT -> {
                unwrappedType is MezzeType.BoolType || BuiltinTypes.isBoolVariant(unwrappedType)
            }

            else -> false
        }
    }

    private fun hasEqImplementation(
        type: MezzeType,
        registry: DeclarationRegistry
    ): Boolean {
        return type is MezzeType.IntType ||
            type is MezzeType.FloatType ||
            type is MezzeType.StringType ||
            type is MezzeType.BoolType ||
            type is MezzeType.NatType ||
            type is MezzeType.FracType ||
            type is MezzeType.OrderingType ||
            type is MezzeType.BytesType ||
            type is MezzeType.ByteBufferType ||
            // Type variables are assumed to have Eq - they're constrained at the call site
            type is MezzeType.VarType ||
            BuiltinTypes.isBoolVariant(type) ||
            // Derive Eq for records if all fields have Eq
            (type is MezzeType.RecordType && type.fields.values.all {
                hasBuiltInImplementation(AstAbilities.EQ, it, registry)
            }) ||
            // Derive Eq for variants if all constructor payloads have Eq (or no payload)
            (type is MezzeType.VariantType && type.constructors.values.all {
                it == null || hasBuiltInImplementation(AstAbilities.EQ, it, registry)
            }) ||
            // Derive Eq for lists if element type has Eq
            (type is MezzeType.ListType &&
                hasBuiltInImplementation(AstAbilities.EQ, type.elementType, registry)) ||
            // Derive Eq for arrays if element type has Eq
            (type is MezzeType.SeqType &&
                hasBuiltInImplementation(AstAbilities.EQ, type.elementType, registry))
    }

    private fun hasOrdImplementation(
        type: MezzeType,
        registry: DeclarationRegistry
    ): Boolean {
        return type is MezzeType.IntType ||
            type is MezzeType.FloatType ||
            type is MezzeType.StringType ||
            type is MezzeType.NatType ||
            type is MezzeType.FracType ||
            // Type variables are assumed to have Ord - they're constrained at the call site
            type is MezzeType.VarType ||
            // Derive Ord for records if all fields have Ord
            (type is MezzeType.RecordType && type.fields.values.all {
                hasBuiltInImplementation(AstAbilities.ORD, it, registry)
            }) ||
            // Derive Ord for variants if all constructor payloads have Ord (or no payload)
            (type is MezzeType.VariantType && type.constructors.values.all {
                it == null || hasBuiltInImplementation(AstAbilities.ORD, it, registry)
            }) ||
            // Derive Ord for lists if element type has Ord
            (type is MezzeType.ListType &&
                hasBuiltInImplementation(AstAbilities.ORD, type.elementType, registry)) ||
            // Derive Ord for arrays if element type has Ord
            (type is MezzeType.SeqType &&
                hasBuiltInImplementation(AstAbilities.ORD, type.elementType, registry))
    }
}
