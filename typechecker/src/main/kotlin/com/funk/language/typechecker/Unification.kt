package com.funk.language.typechecker

/**
 * Type unification with row polymorphism support
 */

object Unification {
    
    /**
     * Unify two types, returning a substitution that makes them equal
     */
    fun unify(type1: FunkType, type2: FunkType): Substitution {
        return unifyTypes(type1, type2, Substitution.empty())
    }
    
    private fun unifyTypes(type1: FunkType, type2: FunkType, subst: Substitution): Substitution {
        val t1 = subst.apply(type1)
        val t2 = subst.apply(type2)
        
        if (t1 == t2) return subst
        
        return when {
            // Type variables
            t1 is FunkType.VarType -> unifyVariable(t1.variable, t2, subst)
            t2 is FunkType.VarType -> unifyVariable(t2.variable, t1, subst)
            
            // Records
            t1 is FunkType.RecordType && t2 is FunkType.RecordType -> unifyRecords(t1, t2, subst)
            
            // Variants
            t1 is FunkType.VariantType && t2 is FunkType.VariantType -> unifyVariants(t1, t2, subst)
            
            // Functions
            t1 is FunkType.FunctionType && t2 is FunkType.FunctionType -> unifyFunctions(t1, t2, subst)
            
            // Type constructors
            t1 is FunkType.TypeConstructor && t2 is FunkType.TypeConstructor -> 
                unifyTypeConstructors(t1, t2, subst)
            
            // Type aliases - expand and retry
            t1 is FunkType.TypeAlias -> unifyTypes(t1.type, t2, subst)
            t2 is FunkType.TypeAlias -> unifyTypes(t1, t2.type, subst)
            
            else -> throw StructuredTypeError.unificationError(t1, t2)
        }
    }
    
    private fun unifyVariable(variable: TypeVar, type: FunkType, subst: Substitution): Substitution {
        val resolvedType = subst.apply(type)
        
        // Occurs check
        if (occursCheck(variable, resolvedType)) {
            throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.OccursCheck(variable.name, resolvedType.toSerializable()),
                    null,
                    "Occurs check failed: ${variable.name} occurs in ${resolvedType.prettyPrint()}"
                )
            )
        }
        
        return subst.extend(variable, resolvedType)
    }
    
    private fun occursCheck(variable: TypeVar, type: FunkType): Boolean = when (type) {
        is FunkType.VarType -> variable == type.variable
        is FunkType.RecordType -> type.fields.values.any { occursCheck(variable, it) }
        is FunkType.VariantType -> type.constructors.values.any { 
            it != null && occursCheck(variable, it) 
        }
        is FunkType.FunctionType -> 
            occursCheck(variable, type.paramType) || occursCheck(variable, type.returnType)
        is FunkType.TypeConstructor -> type.args.any { occursCheck(variable, it) }
        is FunkType.TypeAlias -> occursCheck(variable, type.type)
        else -> false
    }
    
    private fun unifyRecords(
        record1: FunkType.RecordType, 
        record2: FunkType.RecordType, 
        subst: Substitution
    ): Substitution {
        return when {
            // Both closed - must have exact same fields
            record1.isClosed && record2.isClosed -> {
                if (record1.fields.keys != record2.fields.keys) {
                    throw StructuredTypeError.unificationError(record1, record2)
                }
                
                var currentSubst = subst
                for (field in record1.fields.keys) {
                    val type1 = record1.fields[field]!!
                    val type2 = record2.fields[field]!!
                    currentSubst = unifyTypes(type1, type2, currentSubst)
                }
                currentSubst
            }
            
            // One closed, one open - closed must be subset of open
            record1.isClosed && record2.isOpen -> unifyClosedWithOpen(record1, record2, subst)
            record1.isOpen && record2.isClosed -> unifyClosedWithOpen(record2, record1, subst)
            
            // Both open - unify common fields and merge row variables
            record1.isOpen && record2.isOpen -> unifyOpenRecords(record1, record2, subst)
            
            else -> throw StructuredTypeError.unificationError(record1, record2)
        }
    }
    
    private fun unifyClosedWithOpen(
        closed: FunkType.RecordType,
        open: FunkType.RecordType, 
        subst: Substitution
    ): Substitution {
        // Check that all fields in open are present in closed
        for ((field, openType) in open.fields) {
            val closedType = closed.fields[field] 
                ?: throw StructuredTypeError.unificationError(closed, open)
            
            // Unify the field types
            unifyTypes(openType, closedType, subst)
        }
        
        // The row variable in open gets unified with the remaining fields from closed
        val remainingFields = closed.fields - open.fields.keys
        return unifyRowWithFields(open.rowVar!!, remainingFields, subst)
    }
    
    private fun unifyOpenRecords(
        record1: FunkType.RecordType,
        record2: FunkType.RecordType,
        subst: Substitution
    ): Substitution {
        val commonFields = record1.fields.keys intersect record2.fields.keys
        var currentSubst = subst
        
        // Unify common fields
        for (field in commonFields) {
            val type1 = record1.fields[field]!!
            val type2 = record2.fields[field]!!
            currentSubst = unifyTypes(type1, type2, currentSubst)
        }
        
        // Handle row variables
        val fields1Only = record1.fields - commonFields
        val fields2Only = record2.fields - commonFields
        
        // Each row variable gets the fields unique to the other record
        currentSubst = unifyRowWithFields(record1.rowVar!!, fields2Only, currentSubst)
        currentSubst = unifyRowWithFields(record2.rowVar!!, fields1Only, currentSubst)
        
        return currentSubst
    }
    
    private fun unifyRowWithFields(
        rowVar: RowVar, 
        fields: Map<String, FunkType>, 
        subst: Substitution
    ): Substitution {
        // For now, we'll represent this as a constraint
        // In a full implementation, we'd have a more sophisticated row constraint solver
        return subst
    }
    
    private fun unifyVariants(
        variant1: FunkType.VariantType,
        variant2: FunkType.VariantType, 
        subst: Substitution
    ): Substitution {
        // Similar logic to records but for variant constructors
        return when {
            variant1.isClosed && variant2.isClosed -> {
                if (variant1.constructors.keys != variant2.constructors.keys) {
                    throw StructuredTypeError.unificationError(variant1, variant2)
                }
                
                var currentSubst = subst
                for (constructor in variant1.constructors.keys) {
                    val type1 = variant1.constructors[constructor]
                    val type2 = variant2.constructors[constructor]
                    
                    when {
                        type1 == null && type2 == null -> continue
                        type1 != null && type2 != null -> 
                            currentSubst = unifyTypes(type1, type2, currentSubst)
                        else -> throw StructuredTypeError.unificationError(variant1, variant2)
                    }
                }
                currentSubst
            }
            
            variant1.isClosed && variant2.isOpen -> unifyClosedWithOpenVariant(variant1, variant2, subst)
            variant1.isOpen && variant2.isClosed -> unifyClosedWithOpenVariant(variant2, variant1, subst)
            variant1.isOpen && variant2.isOpen -> unifyOpenVariants(variant1, variant2, subst)
            
            else -> throw StructuredTypeError.unificationError(variant1, variant2)
        }
    }
    
    private fun unifyClosedWithOpenVariant(
        closed: FunkType.VariantType,
        open: FunkType.VariantType,
        subst: Substitution
    ): Substitution {
        // Check that all constructors in open are present in closed
        for ((constructor, openType) in open.constructors) {
            if (!closed.constructors.containsKey(constructor)) {
                throw StructuredTypeError.unificationError(closed, open)
            }
            
            val closedType = closed.constructors[constructor]
            when {
                openType == null && closedType == null -> continue
                openType != null && closedType != null -> 
                    unifyTypes(openType, closedType, subst)
                else -> throw StructuredTypeError.unificationError(closed, open)
            }
        }
        
        return subst
    }
    
    private fun unifyOpenVariants(
        variant1: FunkType.VariantType,
        variant2: FunkType.VariantType,
        subst: Substitution
    ): Substitution {
        val commonConstructors = variant1.constructors.keys intersect variant2.constructors.keys
        var currentSubst = subst
        
        // Unify common constructors
        for (constructor in commonConstructors) {
            val type1 = variant1.constructors[constructor]
            val type2 = variant2.constructors[constructor]
            
            when {
                type1 == null && type2 == null -> continue
                type1 != null && type2 != null -> 
                    currentSubst = unifyTypes(type1, type2, currentSubst)
                else -> throw StructuredTypeError.unificationError(variant1, variant2)
            }
        }
        
        return currentSubst
    }
    
    private fun unifyFunctions(
        func1: FunkType.FunctionType,
        func2: FunkType.FunctionType,
        subst: Substitution
    ): Substitution {
        var currentSubst = unifyTypes(func1.paramType, func2.paramType, subst)
        currentSubst = unifyTypes(func1.returnType, func2.returnType, currentSubst)
        
        // Effects must match exactly for now (could be made more flexible)
        if (func1.effects != func2.effects) {
            throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.EffectMismatch(
                        func1.effects.map { it.name },
                        func2.effects.map { it.name }
                    ),
                    null,
                    "Effect mismatch in function types"
                )
            )
        }
        
        return currentSubst
    }
    
    private fun unifyTypeConstructors(
        tc1: FunkType.TypeConstructor,
        tc2: FunkType.TypeConstructor,
        subst: Substitution
    ): Substitution {
        if (tc1.name != tc2.name || tc1.args.size != tc2.args.size) {
            throw StructuredTypeError.unificationError(tc1, tc2)
        }
        
        var currentSubst = subst
        for (i in tc1.args.indices) {
            currentSubst = unifyTypes(tc1.args[i], tc2.args[i], currentSubst)
        }
        
        return currentSubst
    }
}

/**
 * Most General Unifier - finds the most general substitution
 */
object MGU {
    fun mgu(constraints: List<Constraint.Equality>): Substitution {
        var subst = Substitution.empty()
        
        for (constraint in constraints) {
            val newSubst = Unification.unify(
                subst.apply(constraint.type1),
                subst.apply(constraint.type2)
            )
            subst = subst.compose(newSubst)
        }
        
        return subst
    }
}

/**
 * Row constraint solver for advanced row polymorphism
 */
object RowSolver {
    
    fun solveRowConstraints(constraints: Set<RowConstraint>): Substitution {
        // Simplified row constraint solving
        // In a full implementation, this would be more sophisticated
        return Substitution.empty()
    }
    
    fun checkRowConstraint(constraint: RowConstraint, type: FunkType): Boolean {
        return when (constraint) {
            is RowConstraint.HasField -> {
                when (type) {
                    is FunkType.RecordType -> type.fields.containsKey(constraint.field)
                    else -> false
                }
            }
            is RowConstraint.LacksField -> {
                when (type) {
                    is FunkType.RecordType -> !type.fields.containsKey(constraint.field)
                    else -> true
                }
            }
            is RowConstraint.HasConstructor -> {
                when (type) {
                    is FunkType.VariantType -> type.constructors.containsKey(constraint.constructor)
                    else -> false
                }
            }
            is RowConstraint.LacksConstructor -> {
                when (type) {
                    is FunkType.VariantType -> !type.constructors.containsKey(constraint.constructor)
                    else -> true
                }
            }
        }
    }
}