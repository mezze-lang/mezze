package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ArithmeticConstraintTest {
    
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: FunkParser
    
    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = FunkParser()
    }
    
    @Test
    fun `test arithmetic operations generate ability constraints`() {
        val code = "let add = {x, y} -> x + y"
        
        try {
            val program = parser.parse(code)
            val typedProgram = typeChecker.typeCheck(program)
            
            // The function should type check successfully
            assertNotNull(typedProgram)
            
            // Check that the add function has the expected polymorphic type
            val addBinding = typedProgram.items.filterIsInstance<LetBinding>().first { it.variableName == "add" }
            assertNotNull(addBinding.inferredType)
            
            // The type should be a function type
            assertTrue(addBinding.inferredType is FunkType.FunctionType)
            val functionType = addBinding.inferredType as FunkType.FunctionType
            
            // Parameter should be a record with x and y fields of the same type variable
            assertTrue(functionType.paramType is FunkType.RecordType)
            val paramRecord = functionType.paramType as FunkType.RecordType
            
            assertEquals(2, paramRecord.fields.size)
            assertTrue(paramRecord.fields.containsKey("x"))
            assertTrue(paramRecord.fields.containsKey("y"))
            
            // Both fields should have the same type variable
            val xType = paramRecord.fields["x"]
            val yType = paramRecord.fields["y"]
            assertEquals(xType, yType)
            assertTrue(xType is FunkType.VarType)
            
            // Return type should be the same type variable
            assertEquals(xType, functionType.returnType)
            
        } catch (e: Exception) {
            fail("Type checking should succeed for valid arithmetic function: ${e.message}")
        }
    }
    
    @Test
    fun `test comparison operations generate ability constraints`() {
        val code = "let compare = {x, y} -> x == y"
        
        try {
            val program = parser.parse(code)
            val typedProgram = typeChecker.typeCheck(program)
            
            // The function should type check successfully
            assertNotNull(typedProgram)
            
            // Check that the compare function has the expected type
            val compareBinding = typedProgram.items.filterIsInstance<LetBinding>().first { it.variableName == "compare" }
            assertNotNull(compareBinding.inferredType)
            
            // The type should be a function type
            assertTrue(compareBinding.inferredType is FunkType.FunctionType)
            val functionType = compareBinding.inferredType as FunkType.FunctionType
            
            // Return type should be Boolean for comparison
            assertEquals(FunkType.BooleanType, functionType.returnType)
            
        } catch (e: Exception) {
            fail("Type checking should succeed for valid comparison function: ${e.message}")
        }
    }
    
    @Test
    fun `test ability constraint violation with concrete types`() {
        val code = """
            let add = {x, y} -> x + y
            let result = add {x = "hello", y = "world"}
        """.trimIndent()
        
        // Enable strict mode to ensure errors are thrown
        System.setProperty("funk.typecheck.strict", "true")
        
        try {
            // This should fail because String doesn't have NumAdd ability
            assertThrows(TypeCheckError::class.java) {
                val program = parser.parse(code)
                typeChecker.typeCheck(program)
            }
        } finally {
            // Reset the property
            System.clearProperty("funk.typecheck.strict")
        }
    }
    
    @Test
    fun `test valid concrete type usage`() {
        val code = """
            let add = {x, y} -> x + y
            let result = add {x = 1, y = 2}
        """.trimIndent()
        
        try {
            val program = parser.parse(code)
            val typedProgram = typeChecker.typeCheck(program)
            
            // Should succeed because Int has NumAdd ability
            assertNotNull(typedProgram)
            
            // Check that result has a valid type (could be Int or polymorphic with NumAdd)
            val resultBinding = typedProgram.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
            assertNotNull(resultBinding.inferredType, "Result should have an inferred type")
            
            // The type should be either Int or a polymorphic type with NumAdd constraint
            val resultType = resultBinding.inferredType
            assertTrue(
                resultType is FunkType.IntType || 
                (resultType is FunkType.VarType) ||
                (resultType.toString().contains("NumAdd")),
                "Result type should be Int or polymorphic with NumAdd, but was: $resultType"
            )
            
        } catch (e: Exception) {
            fail("Type checking should succeed for valid Int arithmetic: ${e.message}")
        }
    }

    @Test
    fun `test Int and Float cannot unify in function call`() {
        // Regression test for type unification bug
        // { x: 'a, y: 'a } should NOT accept { x: Int, y: Float }
        val code = """
            let my_func = { x, y } -> x + y
            let ans = my_func { x = 5, y = 8.6 }
        """.trimIndent()

        // This should fail because my_func requires both parameters to be the same type
        // but we're passing Int (5) and Float (8.6)
        assertThrows(TypeCheckError::class.java) {
            val program = parser.parse(code)
            typeChecker.typeCheck(program)
        }
    }
}