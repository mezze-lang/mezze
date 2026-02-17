package com.mezze.language.ast

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TypeSchemeConstraintTest {
    
    @Test
    fun `test TypeScheme with no constraints`() {
        val typeVar = TypeVar("a")
        val scheme = TypeScheme(setOf(typeVar), MezzeType.VarType(typeVar))
        
        assertFalse(scheme.isMonomorphic)
        assertEquals(emptyList<AbilityConstraint>(), scheme.constraints)
    }
    
    @Test
    fun `test TypeScheme with constraints`() {
        val typeVar = TypeVar("a")
        val constraints = listOf(
            AbilityConstraint(abilityName = "NumAdd", typeParam = "a")
        )
        val scheme = TypeScheme(setOf(typeVar), MezzeType.VarType(typeVar), constraints)
        
        assertFalse(scheme.isMonomorphic)
        assertEquals(1, scheme.constraints.size)
        assertEquals("NumAdd", scheme.constraints[0].abilityName)
        assertEquals("a", scheme.constraints[0].typeParam)
    }
    
    @Test
    fun `test TypeScheme instantiation with constraints`() {
        val typeVar = TypeVar("a")
        val constraints = listOf(
            AbilityConstraint(abilityName = "NumAdd", typeParam = "a"),
            AbilityConstraint(abilityName = "Eq", typeParam = "a")
        )
        val scheme = TypeScheme(setOf(typeVar), MezzeType.VarType(typeVar), constraints)
        
        val instantiatedType = scheme.instantiate()
        
        // Type should be instantiated with fresh variable
        assertTrue(instantiatedType is MezzeType.VarType)
        val newTypeVar = (instantiatedType as MezzeType.VarType).variable
        assertNotEquals(typeVar.name, newTypeVar.name)
        
        // Constraints are stored in the scheme
        assertEquals(2, scheme.constraints.size)
        assertTrue(scheme.constraints.any { it.abilityName == "NumAdd" && it.typeParam == "a" })
        assertTrue(scheme.constraints.any { it.abilityName == "Eq" && it.typeParam == "a" })
    }
    
    @Test
    fun `test monomorphic TypeScheme instantiation`() {
        val scheme = TypeScheme(emptySet(), MezzeType.IntType, emptyList())
        
        assertTrue(scheme.isMonomorphic)
        
        val instantiatedType = scheme.instantiate()
        
        // Should return the same type for monomorphic schemes
        assertEquals(MezzeType.IntType, instantiatedType)
        assertEquals(emptyList<AbilityConstraint>(), scheme.constraints)
    }
    
    @Test
    fun `test TypeScheme generalization`() {
        val typeVarA = TypeVar("a")
        val typeVarB = TypeVar("b")
        val functionType = MezzeType.FunctionType(
            MezzeType.VarType(typeVarA),
            MezzeType.VarType(typeVarB)
        )
        val constraints = listOf(
            AbilityConstraint(abilityName = "NumAdd", typeParam = "a"),
            AbilityConstraint(abilityName = "Eq", typeParam = "b"),
            AbilityConstraint(abilityName = "Ord", typeParam = "c") // Should be filtered out
        )
        
        val scheme = TypeScheme.generalize(functionType, constraints)
        
        // Should quantify over both type variables
        assertEquals(2, scheme.quantifiedVars.size)
        assertTrue(scheme.quantifiedVars.contains(typeVarA))
        assertTrue(scheme.quantifiedVars.contains(typeVarB))
        
        // Should only include relevant constraints
        assertEquals(2, scheme.constraints.size)
        assertTrue(scheme.constraints.any { it.abilityName == "NumAdd" && it.typeParam == "a" })
        assertTrue(scheme.constraints.any { it.abilityName == "Eq" && it.typeParam == "b" })
        assertFalse(scheme.constraints.any { it.typeParam == "c" })
    }
    
    @Test
    fun `test constraint merging`() {
        val constraints1 = listOf(
            AbilityConstraint(abilityName = "NumAdd", typeParam = "a"),
            AbilityConstraint(abilityName = "Eq", typeParam = "b")
        )
        val constraints2 = listOf(
            AbilityConstraint(abilityName = "Ord", typeParam = "a"),
            AbilityConstraint(abilityName = "Eq", typeParam = "c")
        )
        
        val merged = mergeConstraints(constraints1, constraints2)
        
        // Should contain all unique constraint combinations
        assertEquals(4, merged.size)
        assertTrue(merged.any { it.abilityName == "NumAdd" && it.typeParam == "a" })
        assertTrue(merged.any { it.abilityName == "Ord" && it.typeParam == "a" })
        assertTrue(merged.any { it.abilityName == "Eq" && it.typeParam == "b" })
        assertTrue(merged.any { it.abilityName == "Eq" && it.typeParam == "c" })
    }
    
    @Test
    fun `test constraint merging with duplicates`() {
        val constraints1 = listOf(
            AbilityConstraint(abilityName = "NumAdd", typeParam = "a"),
            AbilityConstraint(abilityName = "Eq", typeParam = "a")
        )
        val constraints2 = listOf(
            AbilityConstraint(abilityName = "NumAdd", typeParam = "a"), // Duplicate
            AbilityConstraint(abilityName = "Ord", typeParam = "a")
        )
        
        val merged = mergeConstraints(constraints1, constraints2)
        
        // Should deduplicate constraints
        assertEquals(3, merged.size)
        assertTrue(merged.any { it.abilityName == "NumAdd" && it.typeParam == "a" })
        assertTrue(merged.any { it.abilityName == "Eq" && it.typeParam == "a" })
        assertTrue(merged.any { it.abilityName == "Ord" && it.typeParam == "a" })
    }
    
    @Test
    fun `test function type instantiation with constraints`() {
        val typeVarA = TypeVar("a")
        val typeVarB = TypeVar("b")
        val functionType = MezzeType.FunctionType(
            MezzeType.VarType(typeVarA),
            MezzeType.VarType(typeVarB)
        )
        val constraints = listOf(
            AbilityConstraint(abilityName = "NumAdd", typeParam = "a"),
            AbilityConstraint(abilityName = "Eq", typeParam = "b")
        )
        val scheme = TypeScheme(setOf(typeVarA, typeVarB), functionType, constraints)
        
        val instantiatedType = scheme.instantiate()
        
        // Should be a function type with fresh variables
        assertTrue(instantiatedType is MezzeType.FunctionType)
        val instFunctionType = instantiatedType as MezzeType.FunctionType
        
        assertTrue(instFunctionType.paramType is MezzeType.VarType)
        assertTrue(instFunctionType.returnType is MezzeType.VarType)
        
        val newParamVar = (instFunctionType.paramType as MezzeType.VarType).variable.name
        val newReturnVar = (instFunctionType.returnType as MezzeType.VarType).variable.name
        
        // Original variables should be different from new ones
        assertNotEquals(typeVarA.name, newParamVar)
        assertNotEquals(typeVarB.name, newReturnVar)
        
        // Constraints are stored in the scheme
        assertEquals(2, scheme.constraints.size)
        assertTrue(scheme.constraints.any { it.abilityName == "NumAdd" && it.typeParam == "a" })
        assertTrue(scheme.constraints.any { it.abilityName == "Eq" && it.typeParam == "b" })
    }
}