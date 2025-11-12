package com.funk.language.typechecker

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class BuiltinAbilityIntegrationTest {
    
    private lateinit var abilityRegistry: AbilityRegistry
    private lateinit var constraintSolver: ConstraintSolver
    
    @BeforeEach
    fun setUp() {
        abilityRegistry = AbilityRegistry()
        constraintSolver = ConstraintSolver(abilityRegistry)
    }
    
    @Test
    fun `test constraint solver uses ability registry`() {
        // Verify that the constraint solver has access to the ability registry
        val registry = constraintSolver.getAbilityRegistry()
        assertNotNull(registry)
        
        // Test that the registry has the expected abilities
        assertTrue(registry.hasAbility(FunkType.IntType, "NumAdd"))
        assertTrue(registry.hasAbility(FunkType.StringType, "Eq"))
        assertTrue(registry.hasAbility(FunkType.BooleanType, "LogicalAnd"))
    }
    
    @Test
    fun `test ability constraint creation`() {
        val typeVar = TypeVar("'a")
        val constraint = Constraint.AbilityConstraint(typeVar, "NumAdd")
        
        assertEquals(typeVar, constraint.typeVar)
        assertEquals("NumAdd", constraint.ability)
        assertEquals("'a : NumAdd", constraint.toString())
    }
    
    @Test
    fun `test ability constraint with source location`() {
        val typeVar = TypeVar("'a")
        val location = SourceLocation(1, 5, "test.funk")
        val constraint = Constraint.AbilityConstraint(typeVar, "Eq", location)
        
        assertEquals(typeVar, constraint.typeVar)
        assertEquals("Eq", constraint.ability)
        assertEquals(location, constraint.sourceLocation)
    }
    
    @Test
    fun `test constraint context operations`() {
        val context = ConstraintContext()
        val typeVar = TypeVar("'a")
        
        // Initially empty
        assertTrue(context.getAllConstraints().isEmpty())
        
        // Add an ability constraint
        context.addAbilityConstraint(typeVar, "NumAdd")
        
        val constraints = context.getAllConstraints()
        assertEquals(1, constraints.size)
        
        val constraint = constraints.first() as Constraint.AbilityConstraint
        assertEquals(typeVar, constraint.typeVar)
        assertEquals("NumAdd", constraint.ability)
    }
    
    @Test
    fun `test constraint context clear`() {
        val context = ConstraintContext()
        val typeVar = TypeVar("'a")
        
        // Add constraints
        context.addAbilityConstraint(typeVar, "NumAdd")
        context.addAbilityConstraint(typeVar, "Eq")
        
        assertEquals(2, context.getAllConstraints().size)
        
        // Clear and verify
        context.clear()
        assertTrue(context.getAllConstraints().isEmpty())
    }
    
    @Test
    fun `test multiple constraint types in context`() {
        val context = ConstraintContext()
        val typeVar1 = TypeVar("'a")
        val typeVar2 = TypeVar("'b")
        
        // Add different types of constraints
        context.addAbilityConstraint(typeVar1, "NumAdd")
        context.equalityConstraints.add(
            Constraint.Equality(FunkType.VarType(typeVar1), FunkType.IntType)
        )
        context.instanceConstraints.add(
            Constraint.Instance(FunkType.VarType(typeVar2), FunkType.StringType)
        )
        
        val allConstraints = context.getAllConstraints()
        assertEquals(3, allConstraints.size)
        
        // Verify we have one of each type
        val abilityConstraints = allConstraints.filterIsInstance<Constraint.AbilityConstraint>()
        val equalityConstraints = allConstraints.filterIsInstance<Constraint.Equality>()
        val instanceConstraints = allConstraints.filterIsInstance<Constraint.Instance>()
        
        assertEquals(1, abilityConstraints.size)
        assertEquals(1, equalityConstraints.size)
        assertEquals(1, instanceConstraints.size)
    }
}