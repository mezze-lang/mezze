package com.funk.language.typechecker

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class UnificationAbilityIntegrationTest {
    
    private lateinit var abilityRegistry: AbilityRegistry
    private lateinit var constraintSolver: ConstraintSolver
    
    @BeforeEach
    fun setUp() {
        abilityRegistry = AbilityRegistry()
        constraintSolver = ConstraintSolver(abilityRegistry)
    }
    
    @Test
    fun `test unification with ability constraints succeeds for compatible types`() {
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")
        
        val constraints = setOf(
            // Unify 'a with 'b
            Constraint.Equality(FunkType.VarType(typeVarA), FunkType.VarType(typeVarB)),
            // Then unify 'b with Int
            Constraint.Equality(FunkType.VarType(typeVarB), FunkType.IntType),
            // Require that 'a has NumAdd ability
            Constraint.AbilityConstraint(typeVarA, "NumAdd")
        )
        
        // Should succeed because Int has NumAdd ability
        val substitution = constraintSolver.solveWithAbilities(constraints)
        
        // Both type variables should be resolved to Int
        assertEquals(FunkType.IntType, substitution.apply(FunkType.VarType(typeVarA)))
        assertEquals(FunkType.IntType, substitution.apply(FunkType.VarType(typeVarB)))
    }
    
    @Test
    fun `test unification with ability constraints fails for incompatible types`() {
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")
        
        val constraints = setOf(
            // Unify 'a with 'b
            Constraint.Equality(FunkType.VarType(typeVarA), FunkType.VarType(typeVarB)),
            // Then unify 'b with Bool
            Constraint.Equality(FunkType.VarType(typeVarB), FunkType.BooleanType),
            // Require that 'a has NumAdd ability (Bool doesn't have this)
            Constraint.AbilityConstraint(typeVarA, "NumAdd")
        )
        
        // Should fail because Bool doesn't have NumAdd ability
        assertThrows(AbilityConstraintError::class.java) {
            constraintSolver.solveWithAbilities(constraints)
        }
    }
    
    @Test
    fun `test complex unification with multiple ability constraints`() {
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")
        val typeVarC = TypeVar("'c")
        
        val constraints = setOf(
            // Create a function type: 'a -> 'b
            Constraint.Equality(
                FunkType.VarType(typeVarC), 
                FunkType.FunctionType(FunkType.VarType(typeVarA), FunkType.VarType(typeVarB))
            ),
            // Unify 'a with Int
            Constraint.Equality(FunkType.VarType(typeVarA), FunkType.IntType),
            // Unify 'b with String
            Constraint.Equality(FunkType.VarType(typeVarB), FunkType.StringType),
            // Require abilities
            Constraint.AbilityConstraint(typeVarA, "NumAdd"),
            Constraint.AbilityConstraint(typeVarB, "Eq")
        )
        
        // Should succeed because Int has NumAdd and String has Eq
        val substitution = constraintSolver.solveWithAbilities(constraints)
        
        assertEquals(FunkType.IntType, substitution.apply(FunkType.VarType(typeVarA)))
        assertEquals(FunkType.StringType, substitution.apply(FunkType.VarType(typeVarB)))
        
        val resolvedFunctionType = substitution.apply(FunkType.VarType(typeVarC))
        assertTrue(resolvedFunctionType is FunkType.FunctionType)
        val functionType = resolvedFunctionType as FunkType.FunctionType
        assertEquals(FunkType.IntType, functionType.paramType)
        assertEquals(FunkType.StringType, functionType.returnType)
    }
    
    @Test
    fun `test record type unification with ability constraints`() {
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")

        val recordType1 = FunkType.RecordType(mapOf(
            "x" to FunkType.VarType(typeVarA),
            "y" to FunkType.VarType(typeVarB)
        ))

        val recordType2 = FunkType.RecordType(mapOf(
            "x" to FunkType.IntType,
            "y" to FunkType.StringType
        ))

        val constraints = setOf(
            // Unify the record types
            Constraint.Equality(recordType1, recordType2),
            // Require abilities for the type variables
            Constraint.AbilityConstraint(typeVarA, "NumAdd"),
            Constraint.AbilityConstraint(typeVarB, "Eq")
        )

        // Should succeed because Int has NumAdd and String has Eq
        val substitution = constraintSolver.solveWithAbilities(constraints)

        assertEquals(FunkType.IntType, substitution.apply(FunkType.VarType(typeVarA)))
        assertEquals(FunkType.StringType, substitution.apply(FunkType.VarType(typeVarB)))
    }
    
    @Test
    fun `test substitution composition with ability constraints`() {
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")
        val typeVarC = TypeVar("'c")
        
        val constraints = setOf(
            // Chain of unifications: 'a -> 'b -> 'c -> Int
            Constraint.Equality(FunkType.VarType(typeVarA), FunkType.VarType(typeVarB)),
            Constraint.Equality(FunkType.VarType(typeVarB), FunkType.VarType(typeVarC)),
            Constraint.Equality(FunkType.VarType(typeVarC), FunkType.IntType),
            // All should have the same abilities
            Constraint.AbilityConstraint(typeVarA, "NumAdd"),
            Constraint.AbilityConstraint(typeVarB, "Eq"),
            Constraint.AbilityConstraint(typeVarC, "Ord")
        )
        
        // Should succeed because Int has all these abilities
        val substitution = constraintSolver.solveWithAbilities(constraints)
        
        // All type variables should resolve to Int
        assertEquals(FunkType.IntType, substitution.apply(FunkType.VarType(typeVarA)))
        assertEquals(FunkType.IntType, substitution.apply(FunkType.VarType(typeVarB)))
        assertEquals(FunkType.IntType, substitution.apply(FunkType.VarType(typeVarC)))
    }
    
    @Test
    fun `test early ability constraint failure`() {
        val typeVarA = TypeVar("'a")
        
        val constraints = setOf(
            // Directly unify with Bool
            Constraint.Equality(FunkType.VarType(typeVarA), FunkType.BooleanType),
            // Require numeric ability (Bool doesn't have this)
            Constraint.AbilityConstraint(typeVarA, "NumAdd")
        )
        
        val exception = assertThrows(AbilityConstraintError::class.java) {
            constraintSolver.solveWithAbilities(constraints)
        }
        
        assertTrue(exception.message!!.contains("Bool"))
        assertTrue(exception.message!!.contains("NumAdd"))
        assertEquals(FunkType.BooleanType, exception.type)
        assertEquals("NumAdd", exception.ability)
    }
}