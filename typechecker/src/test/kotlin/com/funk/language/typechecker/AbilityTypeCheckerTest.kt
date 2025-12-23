package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class AbilityTypeCheckerTest {

    private val parser = FunkParser()
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
            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(1, typedProgram.items.size)
        val ability = typedProgram.items[0] as AbilityDeclaration
        assertNotNull(ability.inferredType)
        assertTrue(ability.inferredType is FunkType.RecordType)
        
        val recordType = ability.inferredType as FunkType.RecordType
        assertTrue(recordType.fields.containsKey("encode"))
        assertTrue(recordType.fields["encode"] is FunkType.FunctionType)
    }
    
    @Test
    fun testImplAbilityDeclaration() {
        val source = """
            use std::int

            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }

            impl Encoder Int where {
              encode : {val: Int} -> String = {val} -> int::to_string {n = val}
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(3, typedProgram.items.size) // use statement + ability + impl

        val ability = typedProgram.items[1] as AbilityDeclaration
        assertNotNull(ability.inferredType)

        val impl = typedProgram.items[2] as ImplAbilityDeclaration
        assertNotNull(impl.inferredType)
        assertTrue(impl.inferredType is FunkType.RecordType)
        
        val recordType = impl.inferredType as FunkType.RecordType
        assertTrue(recordType.fields.containsKey("encode"))
    }
    
    @Test
    fun testMultipleAbilityOperations() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String
              debug : {val: 'a} -> String
            }
            
            impl Show String where {
              show : {val: String} -> String = {val} -> val
              debug : {val: String} -> String = {val} -> "\"$(val)\""
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val ability = typedProgram.items[0] as AbilityDeclaration
        val abilityType = ability.inferredType as FunkType.RecordType
        assertEquals(2, abilityType.fields.size)
        assertTrue(abilityType.fields.containsKey("show"))
        assertTrue(abilityType.fields.containsKey("debug"))
        
        val impl = typedProgram.items[1] as ImplAbilityDeclaration
        val implType = impl.inferredType as FunkType.RecordType
        assertEquals(2, implType.fields.size)
        assertTrue(implType.fields.containsKey("show"))
        assertTrue(implType.fields.containsKey("debug"))
    }
    
    @Test
    fun testAbilityWithComplexTypes() {
        val source = """
            ability Serializer 'a where {
              serialize : {val: 'a} -> String
              deserialize : {str: String} -> 'a
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val ability = typedProgram.items[0] as AbilityDeclaration
        val abilityType = ability.inferredType as FunkType.RecordType
        
        // Check serialize operation
        val serializeType = abilityType.fields["serialize"] as FunkType.FunctionType
        assertTrue(serializeType.returnType is FunkType.StringType)
        
        // Check deserialize operation
        val deserializeType = abilityType.fields["deserialize"] as FunkType.FunctionType
        assertTrue(deserializeType.returnType is FunkType.VarType)
    }
    
    @Test
    fun testAbilityDoesNotThrowErrors() {
        val source = """
            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }
            
            impl Encoder Int where {
              encode : {val: Int} -> String = {val} -> "number"
            }
            
            impl Encoder String where {
              encode : {val: String} -> String = {val} -> val
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // Should not throw any exceptions
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }
}
