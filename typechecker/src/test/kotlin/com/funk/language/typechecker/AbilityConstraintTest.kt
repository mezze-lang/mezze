package com.funk.language.typechecker

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar
import com.funk.language.ast.SourceLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class AbilityConstraintTest {
    
    private lateinit var abilityRegistry: AbilityRegistry
    private lateinit var constraintSolver: ConstraintSolver
    
    @BeforeEach
    fun setUp() {
        abilityRegistry = AbilityRegistry()
        constraintSolver = ConstraintSolver(abilityRegistry)
    }
    
    @Test
    fun `test ability constraint creation`() {
        val typeVar = TypeVar("'a")
        val location = SourceLocation(1, 10, "test.funk")
        val constraint = Constraint.AbilityConstraint(typeVar, "NumAdd", location)
        
        assertEquals(typeVar, constraint.typeVar)
        assertEquals("NumAdd", constraint.ability)
        assertEquals(location, constraint.sourceLocation)
        assertEquals("'a : NumAdd", constraint.toString())
    }
    
    @Test
    fun `test ability registry has built-in abilities`() {
        // Test Int type abilities
        assertTrue(abilityRegistry.hasAbility(FunkType.IntType, "NumAdd"))
        assertTrue(abilityRegistry.hasAbility(FunkType.IntType, "NumSub"))
        assertTrue(abilityRegistry.hasAbility(FunkType.IntType, "NumMul"))
        assertTrue(abilityRegistry.hasAbility(FunkType.IntType, "NumDiv"))
        assertTrue(abilityRegistry.hasAbility(FunkType.IntType, "Eq"))
        assertTrue(abilityRegistry.hasAbility(FunkType.IntType, "Ord"))
        
        // Test Frac type abilities
        assertTrue(abilityRegistry.hasAbility(FunkType.FracType, "NumAdd"))
        assertTrue(abilityRegistry.hasAbility(FunkType.FracType, "Eq"))
        
        // Test Bool type abilities
        assertTrue(abilityRegistry.hasAbility(FunkType.BooleanType, "Eq"))
        assertTrue(abilityRegistry.hasAbility(FunkType.BooleanType, "LogicalAnd"))
        assertFalse(abilityRegistry.hasAbility(FunkType.BooleanType, "NumAdd"))
        
        // Test String type abilities
        assertTrue(abilityRegistry.hasAbility(FunkType.StringType, "Eq"))
        assertTrue(abilityRegistry.hasAbility(FunkType.StringType, "Ord"))
        assertTrue(abilityRegistry.hasAbility(FunkType.StringType, "StringConcat"))
        assertFalse(abilityRegistry.hasAbility(FunkType.StringType, "NumAdd"))
    }
    
    @Test
    fun `test get required abilities for operations`() {
        assertEquals(setOf("NumAdd"), abilityRegistry.getRequiredAbilities("+"))
        assertEquals(setOf("NumSub"), abilityRegistry.getRequiredAbilities("-"))
        assertEquals(setOf("NumMul"), abilityRegistry.getRequiredAbilities("*"))
        assertEquals(setOf("NumDiv"), abilityRegistry.getRequiredAbilities("/"))
        assertEquals(setOf("Eq"), abilityRegistry.getRequiredAbilities("=="))
        assertEquals(setOf("Eq"), abilityRegistry.getRequiredAbilities("!="))
        assertEquals(setOf("Ord"), abilityRegistry.getRequiredAbilities("<"))
        assertEquals(setOf("Ord"), abilityRegistry.getRequiredAbilities(">"))
        assertEquals(emptySet<String>(), abilityRegistry.getRequiredAbilities("unknown"))
    }
    
    @Test
    fun `test constraint context`() {
        val context = ConstraintContext()
        val typeVar = TypeVar("'a")
        val location = SourceLocation(1, 10, "test.funk")
        
        // Add ability constraint
        context.addAbilityConstraint(typeVar, "NumAdd", location)
        
        // Check constraint was added
        assertEquals(1, context.abilityConstraints.size)
        val constraint = context.abilityConstraints.first()
        assertEquals(typeVar, constraint.typeVar)
        assertEquals("NumAdd", constraint.ability)
        assertEquals(location, constraint.sourceLocation)
        
        // Test getAllConstraints
        assertEquals(1, context.getAllConstraints().size)
        assertTrue(context.getAllConstraints().contains(constraint))
        
        // Test clear
        context.clear()
        assertEquals(0, context.getAllConstraints().size)
    }
    
    @Test
    fun `test ability constraint validation with valid type`() {
        val typeVar = TypeVar("'a")
        val constraint = Constraint.AbilityConstraint(typeVar, "NumAdd")
        val equalityConstraint = Constraint.Equality(FunkType.VarType(typeVar), FunkType.IntType)
        
        // Should not throw - Int has NumAdd ability
        assertDoesNotThrow {
            constraintSolver.solveWithAbilities(setOf(constraint, equalityConstraint))
        }
    }
    
    @Test
    fun `test ability constraint validation with invalid type`() {
        val typeVar = TypeVar("'a")
        val constraint = Constraint.AbilityConstraint(typeVar, "NumAdd")
        val equalityConstraint = Constraint.Equality(FunkType.VarType(typeVar), FunkType.BooleanType)
        
        // Should throw - Bool does not have NumAdd ability
        assertThrows(AbilityConstraintError::class.java) {
            constraintSolver.solveWithAbilities(setOf(constraint, equalityConstraint))
        }
    }
    
    @Test
    fun `test ability registration`() {
        val customAbility = Ability(
            name = "CustomAbility",
            operations = mapOf("customOp" to FunkType.IntType)
        )
        
        abilityRegistry.registerAbility(customAbility)
        
        // Custom ability should not be available for built-in types yet
        assertFalse(abilityRegistry.hasAbility(FunkType.IntType, "CustomAbility"))
    }
}