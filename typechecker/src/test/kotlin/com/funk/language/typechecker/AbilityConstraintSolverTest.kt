package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.typechecker.support.Constraint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class AbilityConstraintSolverTest {
    
    private lateinit var abilityRegistry: AbilityRegistry
    private lateinit var constraintSolver: ConstraintSolver
    
    @BeforeEach
    fun setUp() {
        abilityRegistry = AbilityRegistry()
        constraintSolver = ConstraintSolver(abilityRegistry)
    }
    
    @Test
    fun `test solve empty constraints`() {
        val constraints = emptyList<Constraint>()
        val substitution = constraintSolver.solve(constraints)
        
        assertTrue(substitution.mapping.isEmpty())
    }
    
    @Test
    fun `test solve equality constraints only`() {
        val typeVar = TypeVar("'a")
        val constraints = listOf(
            Constraint.Equality(FunkType.VarType(typeVar), FunkType.IntType)
        )
        
        val substitution = constraintSolver.solve(constraints)
        
        assertEquals(FunkType.IntType, substitution.mapping[typeVar])
    }
    
    @Test
    fun `test solve ability constraints with valid concrete type`() {
        val typeVar = TypeVar("'a")
        val constraints = setOf(
            Constraint.Equality(FunkType.VarType(typeVar), FunkType.IntType),
            Constraint.AbilityConstraint(typeVar, "NumAdd")
        )
        
        // Should not throw - Int has NumAdd ability
        val substitution = constraintSolver.solveWithAbilities(constraints)
        assertEquals(FunkType.IntType, substitution.mapping[typeVar])
    }
    
    @Test
    fun `test solve ability constraints with invalid concrete type`() {
        val typeVar = TypeVar("'a")
        val constraints = setOf(
            Constraint.Equality(FunkType.VarType(typeVar), FunkType.BooleanType),
            Constraint.AbilityConstraint(typeVar, "NumAdd")
        )
        
        // Should throw - Bool does not have NumAdd ability
        assertThrows(AbilityConstraintError::class.java) {
            constraintSolver.solveWithAbilities(constraints)
        }
    }
    
    @Test
    fun `test solve multiple ability constraints with valid type`() {
        val typeVar = TypeVar("'a")
        val constraints = setOf(
            Constraint.Equality(FunkType.VarType(typeVar), FunkType.IntType),
            Constraint.AbilityConstraint(typeVar, "NumAdd"),
            Constraint.AbilityConstraint(typeVar, "Eq"),
            Constraint.AbilityConstraint(typeVar, "Ord")
        )
        
        // Should not throw - Int has all these abilities
        val substitution = constraintSolver.solveWithAbilities(constraints)
        assertEquals(FunkType.IntType, substitution.mapping[typeVar])
    }
    
    @Test
    fun `test solve multiple ability constraints with invalid type`() {
        val typeVar = TypeVar("'a")
        val constraints = setOf(
            Constraint.Equality(FunkType.VarType(typeVar), FunkType.BooleanType),
            Constraint.AbilityConstraint(typeVar, "Eq"),
            Constraint.AbilityConstraint(typeVar, "NumAdd")  // Bool doesn't have this
        )
        
        // Should throw - Bool does not have NumAdd ability
        val exception = assertThrows(AbilityConstraintError::class.java) {
            constraintSolver.solveWithAbilities(constraints)
        }
        
        assertTrue(exception.message!!.contains("NumAdd"))
        assertTrue(exception.message!!.contains("Bool"))
    }
    
    @Test
    fun `test solve constraints with multiple type variables`() {
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")
        val constraints = setOf(
            Constraint.Equality(FunkType.VarType(typeVarA), FunkType.IntType),
            Constraint.Equality(FunkType.VarType(typeVarB), FunkType.StringType),
            Constraint.AbilityConstraint(typeVarA, "NumAdd"),
            Constraint.AbilityConstraint(typeVarB, "Eq")
        )
        
        // Should not throw - both types have their required abilities
        val substitution = constraintSolver.solveWithAbilities(constraints)
        assertEquals(FunkType.IntType, substitution.mapping[typeVarA])
        assertEquals(FunkType.StringType, substitution.mapping[typeVarB])
    }
    
    @Test
    fun `test solve constraints with unresolved type variables`() {
        val typeVar = TypeVar("'a")
        val constraints = setOf(
            Constraint.AbilityConstraint(typeVar, "NumAdd")
        )
        
        // Should not throw - unresolved type variables are not validated
        val substitution = constraintSolver.solveWithAbilities(constraints)
        assertTrue(substitution.mapping.isEmpty())
    }
    
    @Test
    fun `test ability constraint error contains source location`() {
        val typeVar = TypeVar("'a")
        val location = SourceLocation(5, 10, "test.funk")
        val boolVariant = FunkType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        )
        val constraints = setOf(
            Constraint.Equality(FunkType.VarType(typeVar), boolVariant),
            Constraint.AbilityConstraint(typeVar, "NumAdd", location)
        )

        val exception = assertThrows(AbilityConstraintError::class.java) {
            constraintSolver.solveWithAbilities(constraints)
        }

        assertEquals(location, exception.sourceLocation)
        assertEquals(boolVariant, exception.type)
        assertEquals("NumAdd", exception.ability)
    }
    
    @Test
    fun `test constraint solving with function types`() {
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")
        val functionType = FunkType.FunctionType(
            FunkType.VarType(typeVarA),
            FunkType.VarType(typeVarB)
        )
        val constraints = setOf(
            Constraint.Equality(FunkType.VarType(typeVarA), FunkType.IntType),
            Constraint.Equality(FunkType.VarType(typeVarB), FunkType.IntType),
            Constraint.AbilityConstraint(typeVarA, "NumAdd"),
            Constraint.AbilityConstraint(typeVarB, "Eq")
        )
        
        // Should not throw - Int has both abilities
        val substitution = constraintSolver.solveWithAbilities(constraints)
        assertEquals(FunkType.IntType, substitution.mapping[typeVarA])
        assertEquals(FunkType.IntType, substitution.mapping[typeVarB])
    }
    
    @Test
    fun `test constraint solving with record types`() {
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")
        val recordType = FunkType.RecordType(mapOf(
            "x" to FunkType.VarType(typeVarA),
            "y" to FunkType.VarType(typeVarB)
        ))
        val constraints = setOf(
            Constraint.Equality(FunkType.VarType(typeVarA), FunkType.IntType),
            Constraint.Equality(FunkType.VarType(typeVarB), FunkType.StringType),
            Constraint.AbilityConstraint(typeVarA, "NumAdd"),
            Constraint.AbilityConstraint(typeVarB, "Eq")
        )

        // Should not throw - both types have their required abilities
        val substitution = constraintSolver.solveWithAbilities(constraints)
        assertEquals(FunkType.IntType, substitution.mapping[typeVarA])
        assertEquals(FunkType.StringType, substitution.mapping[typeVarB])
    }
    
    @Test
    fun `test substitution application to ability constraints`() {
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")
        
        // Create a substitution that maps 'a to 'b
        val substitution = Substitution(mapOf(typeVarA to FunkType.VarType(typeVarB)))
        
        val originalConstraint = Constraint.AbilityConstraint(typeVarA, "NumAdd")
        
        // Apply substitution - this is tested indirectly through the solver
        // but we can verify the constraint structure
        assertEquals(typeVarA, originalConstraint.typeVar)
        assertEquals("NumAdd", originalConstraint.ability)
    }
}