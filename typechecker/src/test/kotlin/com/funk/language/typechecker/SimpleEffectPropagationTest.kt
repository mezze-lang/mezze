package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SimpleEffectPropagationTest {
    
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    
    @Test
    fun testSimpleEffectDeclaration() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
        // Just check that it parses and type checks successfully
        assertNotNull(typedProgram)
        assertEquals(1, typedProgram.items.size)
    }
    
    @Test
    fun testBasicEffectWithReturnEffect() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
            }
            
            effect Database {
              query : {sql: String} -> String <Log>
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
        // Check that both effects are declared
        assertNotNull(typedProgram)
        assertEquals(2, typedProgram.items.size)
        
        // Check the Database effect has the correct type
        val databaseEffect = typedProgram.items[1] as EffectDeclaration
        assertEquals("Database", databaseEffect.name)
        
        // Check that the query operation has Log in its effect row
        val databaseType = databaseEffect.inferredType as FunkType.RecordType
        val queryType = databaseType.fields["query"] as FunkType.FunctionType
        assertNotNull(queryType.effects)
        assertTrue(queryType.effects!!.effects.contains("Log"))
    }
    
    @Test
    fun testDebugProgramStructure() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
            }
            
            let testFunction : {x: Int} -> Int = {x} -> x
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
        println("Program items:")
        typedProgram.items.forEachIndexed { index, item ->
            println("  [$index] ${item::class.simpleName}: ${when(item) {
                is EffectDeclaration -> item.name
                is LetBinding -> item.variableName
                else -> item.toString()
            }}")
        }
        
        // Find the function
        val functionDecl = typedProgram.items.find { 
            it is LetBinding && it.variableName == "testFunction" 
        }
        
        assertNotNull(functionDecl, "Should find testFunction")
        assertTrue(functionDecl is LetBinding)
        
        val letBinding = functionDecl as LetBinding
        assertNotNull(letBinding.inferredType)
        assertTrue(letBinding.inferredType is FunkType.FunctionType)
    }
}
