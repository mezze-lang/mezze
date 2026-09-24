package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ArithmeticConstraintTest {
    
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser
    
    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }
    
    @Test
    fun `test arithmetic operations generate ability constraints`() {
        val code = "let add = {x, y} -> x + y"
        
        try {
            val parsedModule = parser.parseParsedModule(code, "test.mz")
            val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
            
            // The function should type check successfully
            assertNotNull(typedProgram)
            
            // Check that the add function has the expected polymorphic type
            val addType = typedProgram.getVariableType("add")
            assertNotNull(addType)

            // The type should be a function type
            assertTrue(addType is MezzeType.FunctionType)
            val functionType = addType as MezzeType.FunctionType
            
            // Parameter should be a record with x and y fields of the same type variable
            assertTrue(functionType.paramType is MezzeType.RecordType)
            val paramRecord = functionType.paramType as MezzeType.RecordType
            
            assertEquals(2, paramRecord.fields.size)
            assertTrue(paramRecord.fields.containsKey("x"))
            assertTrue(paramRecord.fields.containsKey("y"))

            // Under the class-parameter NumAdd ability, `+` is heterogeneous:
            //   {x:'l, y:'r} with NumAdd 'r 'l having 'out -> 'out
            // so x, y, and the return type are three DISTINCT type variables
            // (they are no longer forced to be the same type).
            val xType = paramRecord.fields["x"]
            val yType = paramRecord.fields["y"]
            assertTrue(xType is MezzeType.VarType)
            assertTrue(yType is MezzeType.VarType)
            assertNotEquals(xType, yType)

            // Return type is its own type variable ('out), distinct from x and y
            assertTrue(functionType.returnType is MezzeType.VarType)
            assertNotEquals(xType, functionType.returnType)
            assertNotEquals(yType, functionType.returnType)

        } catch (e: Exception) {
            fail("Type checking should succeed for valid arithmetic function: ${e.message}")
        }
    }
    
    @Test
    fun `test comparison operations generate ability constraints`() {
        val code = "let compare = {x, y} -> x == y"
        
        try {
            val parsedModule = parser.parseParsedModule(code, "test.mz")
            val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
            
            // The function should type check successfully
            assertNotNull(typedProgram)
            
            // Check that the compare function has the expected type
            val compareType = typedProgram.getVariableType("compare")
            assertNotNull(compareType)

            // The type should be a function type
            assertTrue(compareType is MezzeType.FunctionType)
            val functionType = compareType as MezzeType.FunctionType
            
            // Return type should be Boolean for comparison
            val boolType = PrimitiveTypes.BOOL_TYPE
        assertEquals(boolType, functionType.returnType)
            
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
        System.setProperty("mezze.typecheck.strict", "true")
        
        try {
            // This should fail because String doesn't have NumAdd ability
            assertThrows(TypeCheckError::class.java) {
                val parsedModule = parser.parseParsedModule(code, "test.mz")
                typeChecker.typeCheck(parsedModule, Paths.get("."))
            }
        } finally {
            // Reset the property
            System.clearProperty("mezze.typecheck.strict")
        }
    }
    
    @Test
    fun `test valid concrete type usage`() {
        val code = """
            let add = {x, y} -> x + y
            let result = add {x = 1, y = 2}
        """.trimIndent()
        
        try {
            val parsedModule = parser.parseParsedModule(code, "test.mz")
            val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
            
            // Should succeed because Int has NumAdd ability
            assertNotNull(typedProgram)
            
            // Check that result has a valid type (could be Int or polymorphic with NumAdd)
            val resultType = typedProgram.getVariableType("result")
            assertNotNull(resultType, "Result should have an inferred type")
            assertTrue(
                resultType is MezzeType.IntType || 
                (resultType is MezzeType.VarType) ||
                (resultType.toString().contains("NumAdd")),
                "Result type should be Int or polymorphic with NumAdd, but was: $resultType"
            )
            
        } catch (e: Exception) {
            fail("Type checking should succeed for valid Int arithmetic: ${e.message}")
        }
    }

    @Test
    fun `test Int and Float widen in function call`() {
        // Under the class-parameter NumAdd ability, `+` is heterogeneous:
        //   { x, y } -> x + y  infers  {x:'l, y:'r} with NumAdd 'r 'l having 'out -> 'out
        // so an unannotated add function accepts differently-typed operands and
        // widens to the result type. Int (5) + Float (8.6) -> Float.
        val code = """
            let my_func = { x, y } -> x + y
            let ans = my_func { x = 5, y = 8.6 }
        """.trimIndent()

        // This should now SUCCEED and infer `ans : Float` (widened from Int + Float).
        assertDoesNotThrow {
            val parsedModule = parser.parseParsedModule(code, "test.mz")
            val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

            val ansType = typedProgram.getVariableType("ans")
            assertNotNull(ansType, "ans should have an inferred type")
            assertTrue(
                ansType is MezzeType.FloatType,
                "Int + Float should widen to Float, but was: $ansType"
            )
        }
    }
}
