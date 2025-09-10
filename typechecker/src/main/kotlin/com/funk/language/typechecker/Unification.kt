package com.funk.language.typechecker

import com.funk.language.ast.FunkType

/**
 * Simplified unification stub
 */
object Unification {
    
    fun unify(type1: FunkType, type2: FunkType): Boolean {
        // Simplified unification - just check if types are the same
        return type1 == type2
    }
    
    fun occurs(typeVar: com.funk.language.ast.TypeVar, type: FunkType): Boolean {
        // Simplified occurs check
        return when (type) {
            is FunkType.VarType -> type.variable == typeVar
            else -> false
        }
    }
}
