package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class AbilityTypeCheckerTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }
    
    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            ability Encoder for 'a where {
              encode : {val: 'a} -> Str
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(1, typedProgram.items.size)
        val ability = typedProgram.items[0] as AbilityDeclaration
        assertNotNull(ability.inferredType)
        assertTrue(ability.inferredType is MezzeType.RecordType)
        
        val recordType = ability.inferredType as MezzeType.RecordType
        assertTrue(recordType.fields.containsKey("encode"))
        assertTrue(recordType.fields["encode"] is MezzeType.FunctionType)
    }
    
    @Test
    fun testImplAbilityDeclaration() {
        val source = """
            use std::int

            ability Encoder for 'a where {
              encode : {val: 'a} -> Str
            }

            impl Encoder for Int where {
              encode : {val: Int} -> Str = {val} -> int::to_str {n = val}
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(3, typedProgram.items.size) // use statement + ability + impl

        val ability = typedProgram.items[1] as AbilityDeclaration
        assertNotNull(ability.inferredType)

        val impl = typedProgram.items[2] as ImplAbilityDeclaration
        assertNotNull(impl.inferredType)
        assertTrue(impl.inferredType is MezzeType.RecordType)
        
        val recordType = impl.inferredType as MezzeType.RecordType
        assertTrue(recordType.fields.containsKey("encode"))
    }
    
    @Test
    fun testMultipleAbilityOperations() {
        val source = """
            ability Show for 'a where {
              show : {val: 'a} -> Str,
              debug : {val: 'a} -> Str
            }

            impl Show for Str where {
              show : {val: Str} -> Str = {val} -> val,
              debug : {val: Str} -> Str = {val} -> "\"$(val)\""
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val ability = typedProgram.items[0] as AbilityDeclaration
        val abilityType = ability.inferredType as MezzeType.RecordType
        assertEquals(2, abilityType.fields.size)
        assertTrue(abilityType.fields.containsKey("show"))
        assertTrue(abilityType.fields.containsKey("debug"))
        
        val impl = typedProgram.items[1] as ImplAbilityDeclaration
        val implType = impl.inferredType as MezzeType.RecordType
        assertEquals(2, implType.fields.size)
        assertTrue(implType.fields.containsKey("show"))
        assertTrue(implType.fields.containsKey("debug"))
    }
    
    @Test
    fun testAbilityWithComplexTypes() {
        val source = """
            ability Serializer for 'a where {
              serialize : {val: 'a} -> Str,
              deserialize : {str: Str} -> 'a
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val ability = typedProgram.items[0] as AbilityDeclaration
        val abilityType = ability.inferredType as MezzeType.RecordType
        
        // Check serialize operation
        val serializeType = abilityType.fields["serialize"] as MezzeType.FunctionType
        assertTrue(serializeType.returnType is MezzeType.StringType)
        
        // Check deserialize operation
        val deserializeType = abilityType.fields["deserialize"] as MezzeType.FunctionType
        assertTrue(deserializeType.returnType is MezzeType.VarType)
    }
    
    @Test
    fun testAbilityDoesNotThrowErrors() {
        val source = """
            ability Encoder for 'a where {
              encode : {val: 'a} -> Str
            }
            
            impl Encoder for Int where {
              encode : {val: Int} -> Str = {val} -> "number"
            }
            
            impl Encoder for Str where {
              encode : {val: Str} -> Str = {val} -> val
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // Should not throw any exceptions
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }
}
