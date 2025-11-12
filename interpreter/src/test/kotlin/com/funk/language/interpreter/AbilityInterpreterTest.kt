package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AbilityInterpreterTest {
    
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    private val interpreter = FunkInterpreter()
    
    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
        // Should not throw any exceptions
        assertDoesNotThrow {
            interpreter.execute(typedProgram)
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
        val typedProgram = typeChecker.typeCheck(program)
        
        // Should not throw any exceptions
        assertDoesNotThrow {
            val result = interpreter.execute(typedProgram)
            // The result should be a record representing the implementation
            assertTrue(result is FunkRecord)
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
        val typedProgram = typeChecker.typeCheck(program)
        
        // Should not throw any exceptions
        assertDoesNotThrow {
            val result = interpreter.execute(typedProgram)
            // The result should be the last implementation (String)
            assertTrue(result is FunkRecord)
        }
    }
    
    @Test
    fun testAbilityWithMultipleOperations() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String
              debug : {val: 'a} -> String
            }
            
            impl Show String where {
              show : {val: String} -> String = {val} -> val
              debug : {val: String} -> String = {val} -> "\"" ++ val ++ "\""
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
        assertDoesNotThrow {
            val result = interpreter.execute(typedProgram)
            assertTrue(result is FunkRecord)
            
            val record = result as FunkRecord
            assertTrue(record.fields.containsKey("show"))
            assertTrue(record.fields.containsKey("debug"))
        }
    }
    
    @Test
    fun testComplexAbilityExample() {
        val source = """
            ability Serializer 'a where {
              serialize : {val: 'a} -> String
              deserialize : {str: String} -> 'a
            }
            
            impl Serializer Int where {
              serialize : {val: Int} -> String = {val} -> intToString {val = val}
              deserialize : {str: String} -> Int = {str} -> stringToInt {str = str}
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
        assertDoesNotThrow {
            val result = interpreter.execute(typedProgram)
            assertTrue(result is FunkRecord)
            
            val record = result as FunkRecord
            assertTrue(record.fields.containsKey("serialize"))
            assertTrue(record.fields.containsKey("deserialize"))
            
            // Both operations should be functions
            assertTrue(record.fields["serialize"] is SimpleFunkFunction)
            assertTrue(record.fields["deserialize"] is SimpleFunkFunction)
        }
    }
    
    @Test
    fun testAbilityAndImplTogether() {
        val source = """
            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }
            
            impl Encoder Int where {
              encode : {val: Int} -> String = {val} -> intToString {val = val}
            }
            
            let result = 42
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
        val result = interpreter.execute(typedProgram)
        
        // The result should be the last expression (42)
        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }
}
