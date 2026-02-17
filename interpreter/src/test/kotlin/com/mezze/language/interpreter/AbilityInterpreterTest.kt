package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AbilityInterpreterTest {
    
    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()
    
    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        
        // Should not throw any exceptions
        assertDoesNotThrow {
            MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        }
    }
    
    @Test
    fun testImplAbilityDeclaration() {
        val source = """
            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }
            
            impl Encoder Int where {
              encode : {val: Int} -> String = {val} -> "number"
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        
        // Should not throw any exceptions
        assertDoesNotThrow {
            val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
            // The result should be a record representing the implementation
            assertTrue(result is MezzeRecord)
        }
    }
    
    @Test
    fun testMultipleAbilityImplementations() {
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
        val typeCheckResult = typeChecker.typeCheck(program)
        
        // Should not throw any exceptions
        assertDoesNotThrow {
            val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
            // The result should be the last implementation (String)
            assertTrue(result is MezzeRecord)
        }
    }
    
    @Test
    fun testAbilityWithMultipleOperations() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String,
              debug : {val: 'a} -> String
            }

            impl Show String where {
              show : {val: String} -> String = {val} -> val,
              debug : {val: String} -> String = {val} -> "\"$(val)\""
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        
        assertDoesNotThrow {
            val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
            assertTrue(result is MezzeRecord)
            
            val record = result as MezzeRecord
            assertTrue(record.fields.containsKey("show"))
            assertTrue(record.fields.containsKey("debug"))
        }
    }
    
    @Test
    fun testComplexAbilityExample() {
        val source = """
            use std::int

            ability Serializer 'a where {
              serialize : {val: 'a} -> String
            }

            impl Serializer Int where {
              serialize : {val: Int} -> String = {val} -> int::to_string {n = val}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        assertDoesNotThrow {
            val projectRoot = java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
            val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
            assertTrue(result is MezzeRecord)

            val record = result as MezzeRecord
            assertTrue(record.fields.containsKey("serialize"))

            // serialize operation should be a function
            val serializeField = record.fields["serialize"]
            // Accept either MezzeTruffleFunction (from FunctionNode.kt or MezzeValue.kt) or NativeMezzeFunction
            assertTrue(
                serializeField != null && (
                    serializeField::class.simpleName == "MezzeTruffleFunction" ||
                    serializeField is NativeMezzeFunction
                ),
                "Expected MezzeTruffleFunction or NativeMezzeFunction, got: ${serializeField?.javaClass?.simpleName}"
            )
        }
    }
    
    @Test
    fun testAbilityAndImplTogether() {
        val source = """
            use std::int

            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }

            impl Encoder Int where {
              encode : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let result = 42
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        // The result should be the last expression (42)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }
}
