package com.funk.language.typechecker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class UnificationTest {
    
    @Test
    fun `unifies identical types`() {
        val type1 = Type.IntType
        val type2 = Type.IntType
        
        val result = Unification.unify(type1, type2)
        assertTrue(result.mapping.isEmpty())
    }
    
    @Test
    fun `unifies type variable with concrete type`() {
        val typeVar = TypeVar.fresh("a")
        val varType = Type.VarType(typeVar)
        val intType = Type.IntType
        
        val result = Unification.unify(varType, intType)
        assertEquals(intType, result.mapping[typeVar])
    }
    
    @Test
    fun `unifies concrete type with type variable`() {
        val typeVar = TypeVar.fresh("a")
        val varType = Type.VarType(typeVar)
        val stringType = Type.StringType
        
        val result = Unification.unify(stringType, varType)
        assertEquals(stringType, result.mapping[typeVar])
    }
    
    @Test
    fun `unifies closed records with same fields`() {
        val record1 = Type.RecordType(
            mapOf("x" to Type.IntType, "y" to Type.StringType)
        )
        val record2 = Type.RecordType(
            mapOf("x" to Type.IntType, "y" to Type.StringType)
        )
        
        val result = Unification.unify(record1, record2)
        assertTrue(result.mapping.isEmpty())
    }
    
    @Test
    fun `fails to unify closed records with different fields`() {
        val record1 = Type.RecordType(
            mapOf("x" to Type.IntType, "y" to Type.StringType)
        )
        val record2 = Type.RecordType(
            mapOf("x" to Type.IntType, "z" to Type.StringType)
        )
        
        assertThrows<TypeError.UnificationError> {
            Unification.unify(record1, record2)
        }
    }
    
    @Test
    fun `unifies function types with same signature`() {
        val func1 = Type.FunctionType(
            Type.RecordType(mapOf("x" to Type.IntType)),
            Type.StringType
        )
        val func2 = Type.FunctionType(
            Type.RecordType(mapOf("x" to Type.IntType)),
            Type.StringType
        )
        
        val result = Unification.unify(func1, func2)
        assertTrue(result.mapping.isEmpty())
    }
    
    @Test
    fun `fails to unify function types with different effects`() {
        val func1 = Type.FunctionType(
            Type.IntType,
            Type.StringType,
            setOf(Effect("IO"))
        )
        val func2 = Type.FunctionType(
            Type.IntType,
            Type.StringType,
            setOf(Effect("Network"))
        )
        
        assertThrows<TypeError.UnificationError> {
            Unification.unify(func1, func2)
        }
    }
    
    @Test
    fun `detects occurs check violation`() {
        val typeVar = TypeVar.fresh("a")
        val varType = Type.VarType(typeVar)
        val recursiveType = Type.RecordType(
            mapOf("self" to varType)
        )
        
        assertThrows<TypeError.OccursCheck> {
            Unification.unify(varType, recursiveType)
        }
    }
    
    @Test
    fun `unifies variants with same constructors`() {
        val variant1 = Type.VariantType(
            mapOf(
                "Ok" to Type.IntType,
                "Error" to Type.StringType
            )
        )
        val variant2 = Type.VariantType(
            mapOf(
                "Ok" to Type.IntType,
                "Error" to Type.StringType
            )
        )
        
        val result = Unification.unify(variant1, variant2)
        assertTrue(result.mapping.isEmpty())
    }
    
    @Test
    fun `unifies type constructors with same name and args`() {
        val tc1 = Type.TypeConstructor(
            "Maybe",
            listOf(Type.IntType)
        )
        val tc2 = Type.TypeConstructor(
            "Maybe", 
            listOf(Type.IntType)
        )
        
        val result = Unification.unify(tc1, tc2)
        assertTrue(result.mapping.isEmpty())
    }
    
    @Test
    fun `fails to unify type constructors with different names`() {
        val tc1 = Type.TypeConstructor("Maybe", listOf(Type.IntType))
        val tc2 = Type.TypeConstructor("List", listOf(Type.IntType))
        
        assertThrows<TypeError.UnificationError> {
            Unification.unify(tc1, tc2)
        }
    }
    
    @Test
    fun `substitution composition works correctly`() {
        val var1 = TypeVar.fresh("a")
        val var2 = TypeVar.fresh("b")
        
        val subst1 = Substitution.single(var1, Type.IntType)
        val subst2 = Substitution.single(var2, Type.VarType(var1))
        
        val composed = subst1.compose(subst2)
        
        assertEquals(Type.IntType, composed.apply(Type.VarType(var1)))
        assertEquals(Type.IntType, composed.apply(Type.VarType(var2)))
    }
    
    @Test
    fun `MGU finds most general unifier for constraints`() {
        val var1 = TypeVar.fresh("a")
        val var2 = TypeVar.fresh("b")
        
        val constraints = listOf(
            Constraint.Equality(Type.VarType(var1), Type.IntType),
            Constraint.Equality(Type.VarType(var2), Type.VarType(var1))
        )
        
        val mgu = MGU.mgu(constraints)
        
        assertEquals(Type.IntType, mgu.apply(Type.VarType(var1)))
        assertEquals(Type.IntType, mgu.apply(Type.VarType(var2)))
    }
}

