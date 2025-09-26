package com.funk.language.typechecker

import com.funk.language.ast.*

/**
 * Improved constraint solver with proper unification and substitution
 */
class ImprovedConstraintSolver {
    
    /**
     * Solve constraints and return substitutions that should be applied to the context
     */
    fun solveConstraints(
        constraints: List<Pair<FunkType, FunkType>>,
        context: MutableMap<String, FunkType>
    ): Map<TypeVar, FunkType> {
        val substitutions = mutableMapOf<TypeVar, FunkType>()
        
        for ((type1, type2) in constraints) {
            try {
                val newSubstitutions = unifyTypes(type1, type2, substitutions)
                substitutions.putAll(newSubstitutions)
            } catch (e: Exception) {
                throw TypeCheckError(
                    "Cannot unify ${prettyPrintType(type1)} with ${prettyPrintType(type2)}: ${e.message}",
                    ErrorType.CONSTRAINT_VIOLATION
                )
            }
        }
        
        // Apply substitutions back to context
        applySubstitutionsToContext(substitutions, context)
        
        return substitutions
    }
    
    /**
     * Unify two types and return new substitutions
     */
    private fun unifyTypes(
        type1: FunkType,
        type2: FunkType,
        existingSubstitutions: Map<TypeVar, FunkType>
    ): Map<TypeVar, FunkType> {
        val t1 = applySubstitution(type1, existingSubstitutions)
        val t2 = applySubstitution(type2, existingSubstitutions)
        
        return when {
            t1 == t2 -> emptyMap()
            
            // Type variable unification
            t1 is FunkType.VarType -> unifyVariable(t1.variable, t2, existingSubstitutions)
            t2 is FunkType.VarType -> unifyVariable(t2.variable, t1, existingSubstitutions)
            
            // NumType unification - CRITICAL FIX
            t1 is FunkType.NumType && isConcreteNumericType(t2) -> {
                mapOf(t1.constraint to t2)
            }
            t2 is FunkType.NumType && isConcreteNumericType(t1) -> {
                mapOf(t2.constraint to t1)
            }
            t1 is FunkType.NumType && t2 is FunkType.NumType -> {
                unifyVariable(t1.constraint, FunkType.VarType(t2.constraint), existingSubstitutions)
            }
            
            // Function type unification
            t1 is FunkType.FunctionType && t2 is FunkType.FunctionType -> {
                val paramSubs = unifyTypes(t1.paramType, t2.paramType, existingSubstitutions)
                val allSubs = existingSubstitutions + paramSubs
                val returnSubs = unifyTypes(
                    applySubstitution(t1.returnType, allSubs),
                    applySubstitution(t2.returnType, allSubs),
                    allSubs
                )
                paramSubs + returnSubs
            }
            
            // Record type unification
            t1 is FunkType.RecordType && t2 is FunkType.RecordType -> {
                unifyRecordTypes(t1, t2, existingSubstitutions)
            }
            
            // Variant type unification
            t1 is FunkType.VariantType && t2 is FunkType.VariantType -> {
                unifyVariantTypes(t1, t2, existingSubstitutions)
            }
            
            else -> throw IllegalArgumentException("Cannot unify $t1 with $t2")
        }
    }
    
    /**
     * Unify a type variable with a type
     */
    private fun unifyVariable(
        variable: TypeVar,
        type: FunkType,
        existingSubstitutions: Map<TypeVar, FunkType>
    ): Map<TypeVar, FunkType> {
        val substitutedType = applySubstitution(type, existingSubstitutions)
        
        return when (substitutedType) {
            is FunkType.VarType -> {
                if (variable == substitutedType.variable) {
                    emptyMap() // Same variable
                } else {
                    mapOf(variable to substitutedType)
                }
            }
            else -> {
                // Occurs check
                if (occursCheck(variable, substitutedType)) {
                    throw IllegalArgumentException("Occurs check failed: $variable occurs in $substitutedType")
                }
                mapOf(variable to substitutedType)
            }
        }
    }
    
    /**
     * Unify record types
     */
    private fun unifyRecordTypes(
        record1: FunkType.RecordType,
        record2: FunkType.RecordType,
        existingSubstitutions: Map<TypeVar, FunkType>
    ): Map<TypeVar, FunkType> {
        // Handle extensible records - allow one record to be a subset of another
        val commonFields = record1.fields.keys.intersect(record2.fields.keys)
        
        // If one record has fields that the other doesn't, check if they have row variables
        val record1ExtraFields = record1.fields.keys - record2.fields.keys
        val record2ExtraFields = record2.fields.keys - record1.fields.keys
        
        // For member access constraints, we often need to merge record types
        // Instead of requiring exact matches, merge the fields from both records
        // This handles cases like: point.x creates {x: T1}, point.y creates {y: T2}
        // We want to merge them into {x: T1, y: T2}
        
        // Only fail if we have conflicting field types for the same field name
        // (This is handled in the field unification loop below)
        
        var allSubstitutions = existingSubstitutions
        val newSubstitutions = mutableMapOf<TypeVar, FunkType>()
        
        // Unify common fields
        for (fieldName in commonFields) {
            val fieldType1 = record1.fields[fieldName]!!
            val fieldType2 = record2.fields[fieldName]!!
            val fieldSubs = unifyTypes(
                applySubstitution(fieldType1, allSubstitutions),
                applySubstitution(fieldType2, allSubstitutions),
                allSubstitutions
            )
            newSubstitutions.putAll(fieldSubs)
            allSubstitutions = allSubstitutions + fieldSubs
        }
        
        // For extensible record unification, we don't need to do anything special
        // with the extra fields - they can coexist in the unified type
        // The constraint solver will handle creating the appropriate merged type
        
        return newSubstitutions
    }
    
    /**
     * Unify variant types
     */
    private fun unifyVariantTypes(
        variant1: FunkType.VariantType,
        variant2: FunkType.VariantType,
        existingSubstitutions: Map<TypeVar, FunkType>
    ): Map<TypeVar, FunkType> {
        // For now, require exact constructor match
        if (variant1.constructors.keys != variant2.constructors.keys) {
            throw IllegalArgumentException("Variant types have different constructors")
        }
        
        var allSubstitutions = existingSubstitutions
        val newSubstitutions = mutableMapOf<TypeVar, FunkType>()
        
        for ((constructorName, dataType1) in variant1.constructors) {
            val dataType2 = variant2.constructors[constructorName]
            
            if (dataType1 != null && dataType2 != null) {
                val constructorSubs = unifyTypes(
                    applySubstitution(dataType1, allSubstitutions),
                    applySubstitution(dataType2, allSubstitutions),
                    allSubstitutions
                )
                newSubstitutions.putAll(constructorSubs)
                allSubstitutions = allSubstitutions + constructorSubs
            } else if (dataType1 != dataType2) {
                throw IllegalArgumentException("Constructor $constructorName has mismatched data types")
            }
        }
        
        return newSubstitutions
    }
    
    /**
     * Apply substitution to a type
     */
    private fun applySubstitution(
        type: FunkType,
        substitutions: Map<TypeVar, FunkType>
    ): FunkType {
        return when (type) {
            is FunkType.VarType -> substitutions[type.variable] ?: type
            is FunkType.NumType -> substitutions[type.constraint]?.let { 
                if (isConcreteNumericType(it)) it else type 
            } ?: type
            is FunkType.FunctionType -> FunkType.FunctionType(
                applySubstitution(type.paramType, substitutions),
                applySubstitution(type.returnType, substitutions),
                type.effects,
                type.abilityConstraints
            )
            is FunkType.RecordType -> FunkType.RecordType(
                type.fields.mapValues { (_, fieldType) ->
                    applySubstitution(fieldType, substitutions)
                },
                type.rowVar
            )
            is FunkType.VariantType -> FunkType.VariantType(
                type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applySubstitution(it, substitutions) }
                },
                type.rowVar
            )
            else -> type
        }
    }
    
    /**
     * Apply substitutions back to the type context
     */
    private fun applySubstitutionsToContext(
        substitutions: Map<TypeVar, FunkType>,
        context: MutableMap<String, FunkType>
    ) {
        for ((varName, varType) in context.toMap()) {
            context[varName] = applySubstitution(varType, substitutions)
        }
    }
    
    /**
     * Occurs check to prevent infinite types
     */
    private fun occursCheck(variable: TypeVar, type: FunkType): Boolean {
        return when (type) {
            is FunkType.VarType -> variable == type.variable
            is FunkType.NumType -> variable == type.constraint
            is FunkType.FunctionType -> 
                occursCheck(variable, type.paramType) || occursCheck(variable, type.returnType)
            is FunkType.RecordType -> 
                type.fields.values.any { occursCheck(variable, it) }
            is FunkType.VariantType -> 
                type.constructors.values.any { it != null && occursCheck(variable, it) }
            else -> false
        }
    }
    
    /**
     * Check if a type is a concrete numeric type
     */
    private fun isConcreteNumericType(type: FunkType): Boolean {
        return when (type) {
            is FunkType.IntType, is FunkType.FloatType, 
            is FunkType.NatType, is FunkType.FracType -> true
            else -> false
        }
    }
}