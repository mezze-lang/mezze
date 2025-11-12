package com.funk.language.typechecker

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class AbilityRegistryTest {
    
    private lateinit var abilityRegistry: AbilityRegistry
    
    @BeforeEach
    fun setUp() {
        abilityRegistry = AbilityRegistry()
    }
    
    @Test
    fun `test Int type has all numeric abilities`() {
        val intType = FunkType.IntType
        
        // Int should have all numeric abilities
        assertTrue(abilityRegistry.hasAbility(intType, "NumAdd"))
        assertTrue(abilityRegistry.hasAbility(intType, "NumSub"))
        assertTrue(abilityRegistry.hasAbility(intType, "NumMul"))
        assertTrue(abilityRegistry.hasAbility(intType, "NumDiv"))
        assertTrue(abilityRegistry.hasAbility(intType, "NumMod"))
        
        // Int should have comparison abilities
        assertTrue(abilityRegistry.hasAbility(intType, "Eq"))
        assertTrue(abilityRegistry.hasAbility(intType, "Ord"))
        
        // Int should not have logical abilities
        assertFalse(abilityRegistry.hasAbility(intType, "LogicalAnd"))
        assertFalse(abilityRegistry.hasAbility(intType, "LogicalOr"))
        assertFalse(abilityRegistry.hasAbility(intType, "LogicalNot"))
    }
    
    @Test
    fun `test Frac type has all numeric abilities`() {
        val fracType = FunkType.FracType
        
        // Frac should have all numeric abilities
        assertTrue(abilityRegistry.hasAbility(fracType, "NumAdd"))
        assertTrue(abilityRegistry.hasAbility(fracType, "NumSub"))
        assertTrue(abilityRegistry.hasAbility(fracType, "NumMul"))
        assertTrue(abilityRegistry.hasAbility(fracType, "NumDiv"))
        assertTrue(abilityRegistry.hasAbility(fracType, "NumMod"))
        
        // Frac should have comparison abilities
        assertTrue(abilityRegistry.hasAbility(fracType, "Eq"))
        assertTrue(abilityRegistry.hasAbility(fracType, "Ord"))
    }
    
    @Test
    fun `test Nat type has all numeric abilities`() {
        val natType = FunkType.NatType
        
        // Nat should have all numeric abilities
        assertTrue(abilityRegistry.hasAbility(natType, "NumAdd"))
        assertTrue(abilityRegistry.hasAbility(natType, "NumSub"))
        assertTrue(abilityRegistry.hasAbility(natType, "NumMul"))
        assertTrue(abilityRegistry.hasAbility(natType, "NumDiv"))
        assertTrue(abilityRegistry.hasAbility(natType, "NumMod"))
        
        // Nat should have comparison abilities
        assertTrue(abilityRegistry.hasAbility(natType, "Eq"))
        assertTrue(abilityRegistry.hasAbility(natType, "Ord"))
    }
    
    @Test
    fun `test Boolean type has logical and equality abilities`() {
        val boolType = FunkType.BooleanType
        
        // Bool should have logical abilities
        assertTrue(abilityRegistry.hasAbility(boolType, "LogicalAnd"))
        assertTrue(abilityRegistry.hasAbility(boolType, "LogicalOr"))
        assertTrue(abilityRegistry.hasAbility(boolType, "LogicalNot"))
        
        // Bool should have equality ability
        assertTrue(abilityRegistry.hasAbility(boolType, "Eq"))
        
        // Bool should not have numeric abilities
        assertFalse(abilityRegistry.hasAbility(boolType, "NumAdd"))
        assertFalse(abilityRegistry.hasAbility(boolType, "NumSub"))
        assertFalse(abilityRegistry.hasAbility(boolType, "NumMul"))
        assertFalse(abilityRegistry.hasAbility(boolType, "NumDiv"))
        
        // Bool should not have ordering ability
        assertFalse(abilityRegistry.hasAbility(boolType, "Ord"))
    }
    
    @Test
    fun `test String type has comparison and concat abilities`() {
        val stringType = FunkType.StringType
        
        // String should have comparison abilities
        assertTrue(abilityRegistry.hasAbility(stringType, "Eq"))
        assertTrue(abilityRegistry.hasAbility(stringType, "Ord"))
        
        // String should have concatenation ability
        assertTrue(abilityRegistry.hasAbility(stringType, "StringConcat"))
        
        // String should not have numeric abilities
        assertFalse(abilityRegistry.hasAbility(stringType, "NumAdd"))
        assertFalse(abilityRegistry.hasAbility(stringType, "NumSub"))
        assertFalse(abilityRegistry.hasAbility(stringType, "NumMul"))
        assertFalse(abilityRegistry.hasAbility(stringType, "NumDiv"))
        
        // String should not have logical abilities
        assertFalse(abilityRegistry.hasAbility(stringType, "LogicalAnd"))
        assertFalse(abilityRegistry.hasAbility(stringType, "LogicalOr"))
        assertFalse(abilityRegistry.hasAbility(stringType, "LogicalNot"))
    }
    
    @Test
    fun `test type variables have no abilities`() {
        val typeVar = FunkType.VarType(TypeVar("'a"))
        
        // Type variables should not have any abilities until constrained
        assertFalse(abilityRegistry.hasAbility(typeVar, "NumAdd"))
        assertFalse(abilityRegistry.hasAbility(typeVar, "Eq"))
        assertFalse(abilityRegistry.hasAbility(typeVar, "Ord"))
        assertFalse(abilityRegistry.hasAbility(typeVar, "LogicalAnd"))
    }
    
    @Test
    fun `test getRequiredAbilities for arithmetic operators`() {
        assertEquals(setOf("NumAdd"), abilityRegistry.getRequiredAbilities("+"))
        assertEquals(setOf("NumSub"), abilityRegistry.getRequiredAbilities("-"))
        assertEquals(setOf("NumMul"), abilityRegistry.getRequiredAbilities("*"))
        assertEquals(setOf("NumDiv"), abilityRegistry.getRequiredAbilities("/"))
        assertEquals(setOf("NumMod"), abilityRegistry.getRequiredAbilities("%"))
    }
    
    @Test
    fun `test getRequiredAbilities for comparison operators`() {
        assertEquals(setOf("Eq"), abilityRegistry.getRequiredAbilities("=="))
        assertEquals(setOf("Eq"), abilityRegistry.getRequiredAbilities("!="))
        assertEquals(setOf("Ord"), abilityRegistry.getRequiredAbilities("<"))
        assertEquals(setOf("Ord"), abilityRegistry.getRequiredAbilities(">"))
        assertEquals(setOf("Ord"), abilityRegistry.getRequiredAbilities("<="))
        assertEquals(setOf("Ord"), abilityRegistry.getRequiredAbilities(">="))
    }
    
    @Test
    fun `test getRequiredAbilities for logical operators`() {
        assertEquals(setOf("LogicalAnd"), abilityRegistry.getRequiredAbilities("&&"))
        assertEquals(setOf("LogicalOr"), abilityRegistry.getRequiredAbilities("||"))
        assertEquals(setOf("LogicalNot"), abilityRegistry.getRequiredAbilities("!"))
    }
    
    @Test
    fun `test getRequiredAbilities for string operators`() {
        assertEquals(setOf("StringConcat"), abilityRegistry.getRequiredAbilities("++"))
    }
    
    @Test
    fun `test getRequiredAbilities for unknown operators`() {
        assertEquals(emptySet<String>(), abilityRegistry.getRequiredAbilities("unknown"))
        assertEquals(emptySet<String>(), abilityRegistry.getRequiredAbilities("@"))
        assertEquals(emptySet<String>(), abilityRegistry.getRequiredAbilities("??"))
    }
    
    @Test
    fun `test ability registration works`() {
        // Create a custom ability
        val customAbility = Ability(
            name = "CustomAbility",
            operations = mapOf("custom_op" to FunkType.FunctionType(
                FunkType.VarType(TypeVar("'a")),
                FunkType.VarType(TypeVar("'a"))
            ))
        )
        
        // Register the custom ability
        abilityRegistry.registerAbility(customAbility)
        
        // The ability should be registered (though we can't directly test it without exposing the abilities map)
        // We can test that unknown types don't have it
        val unknownType = FunkType.FloatType
        assertFalse(abilityRegistry.hasAbility(unknownType, "CustomAbility"))
    }
    
    @Test
    fun `test comprehensive ability coverage`() {
        // Test that all expected built-in abilities are properly mapped
        val numericTypes = listOf(FunkType.IntType, FunkType.FracType, FunkType.NatType)
        val numericAbilities = listOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "Eq", "Ord")
        
        for (type in numericTypes) {
            for (ability in numericAbilities) {
                assertTrue(
                    abilityRegistry.hasAbility(type, ability),
                    "Type $type should have ability $ability"
                )
            }
        }
        
        // Test Boolean abilities
        val booleanAbilities = listOf("Eq", "LogicalAnd", "LogicalOr", "LogicalNot")
        for (ability in booleanAbilities) {
            assertTrue(
                abilityRegistry.hasAbility(FunkType.BooleanType, ability),
                "Boolean should have ability $ability"
            )
        }
        
        // Test String abilities
        val stringAbilities = listOf("Eq", "Ord", "StringConcat")
        for (ability in stringAbilities) {
            assertTrue(
                abilityRegistry.hasAbility(FunkType.StringType, ability),
                "String should have ability $ability"
            )
        }
    }
}