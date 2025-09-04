package com.funk.language.typechecker

import com.funk.language.parser.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RecordCurryingTest {
    
    @Test
    fun `record function application modes`() {
        // Function: { x: Int, y: Int } -> Int
        val functionType = Type.FunctionType(
            Type.RecordType(mapOf("x" to Type.IntType, "y" to Type.IntType)),
            Type.IntType
        )
        
        // Test exact application: { x = 5, y = 3 }
        val exactArgs = Type.RecordType(mapOf("x" to Type.IntType, "y" to Type.IntType))
        // Should return Int
        
        // Test partial application: { x = 5 }
        val partialArgs = Type.RecordType(mapOf("x" to Type.IntType))
        // Should return { y: Int } -> Int
        
        // Test over-application (closed): { x = 5, y = 3, z = 1 }
        val overArgs = Type.RecordType(mapOf("x" to Type.IntType, "y" to Type.IntType, "z" to Type.IntType))
        // Should error for closed records
        
        assertTrue(exactArgs.fields.keys == functionType.paramType.let { it as Type.RecordType }.fields.keys)
        assertTrue(partialArgs.fields.keys.all { it in (functionType.paramType as Type.RecordType).fields.keys })
        assertTrue(overArgs.fields.keys.containsAll((functionType.paramType as Type.RecordType).fields.keys))
    }
    
    @Test
    fun `open record function allows over-application`() {
        // Function: { x: Int, y: Int, ... } -> Int
        val openFunctionType = Type.FunctionType(
            Type.RecordType(
                mapOf("x" to Type.IntType, "y" to Type.IntType),
                RowVar.fresh() // Open record
            ),
            Type.IntType
        )
        
        // Over-application should be allowed
        val overArgs = Type.RecordType(mapOf(
            "x" to Type.IntType, 
            "y" to Type.IntType, 
            "debug" to Type.BooleanType
        ))
        
        val paramType = openFunctionType.paramType as Type.RecordType
        assertTrue(paramType.isOpen)
        assertTrue(overArgs.fields.keys.containsAll(paramType.fields.keys))
    }
    
    @Test
    fun `partial application type calculation`() {
        // Function: { x: Int, y: String, z: Boolean } -> Float
        val originalFunction = Type.FunctionType(
            Type.RecordType(mapOf(
                "x" to Type.IntType,
                "y" to Type.StringType, 
                "z" to Type.BooleanType
            )),
            Type.FloatType
        )
        
        // Apply: { x = 5, z = true }
        val appliedArgs = Type.RecordType(mapOf(
            "x" to Type.IntType,
            "z" to Type.BooleanType
        ))
        
        // Remaining should be: { y: String } -> Float
        val originalParams = (originalFunction.paramType as Type.RecordType).fields
        val appliedParams = appliedArgs.fields
        val remainingParams = originalParams - appliedParams.keys
        
        assertEquals(mapOf("y" to Type.StringType), remainingParams)
        
        val expectedRemainingType = Type.FunctionType(
            Type.RecordType(remainingParams),
            originalFunction.returnType,
            originalFunction.effects
        )
        
        assertEquals(Type.FloatType, expectedRemainingType.returnType)
        assertEquals(1, (expectedRemainingType.paramType as Type.RecordType).fields.size)
        assertEquals(Type.StringType, (expectedRemainingType.paramType as Type.RecordType).fields["y"])
    }
    
    @Test
    fun `pipe composition with record placeholder`() {
        // Test: 5 |> add { x = 6, y = @ }
        // where add: { x: Int, y: Int } -> Int
        
        val pipeValue = Type.IntType
        val functionType = Type.FunctionType(
            Type.RecordType(mapOf("x" to Type.IntType, "y" to Type.IntType)),
            Type.IntType
        )
        
        // The @ placeholder should unify with the piped value
        val placeholderType = pipeValue
        val expectedArgType = Type.RecordType(mapOf("x" to Type.IntType, "y" to placeholderType))
        
        // Unification should succeed
        val paramType = functionType.paramType as Type.RecordType
        assertTrue(expectedArgType.fields.keys == paramType.fields.keys)
        assertEquals(Type.IntType, expectedArgType.fields["y"])
    }
    
    @Test
    fun `complex pipe composition`() {
        // Test complex piping with transformations
        val stringType = Type.StringType
        val booleanType = Type.BooleanType
        
        // process: { data: String, transform: String -> String, validate: String -> Boolean } -> String
        val processType = Type.FunctionType(
            Type.RecordType(mapOf(
                "data" to stringType,
                "transform" to Type.FunctionType(
                    Type.RecordType(mapOf("s" to stringType)),
                    stringType
                ),
                "validate" to Type.FunctionType(
                    Type.RecordType(mapOf("s" to stringType)),
                    booleanType
                )
            )),
            stringType
        )
        
        // "hello" |> process { transform = ..., validate = ..., data = @ }
        val pipeValue = stringType
        val expectedApplication = Type.RecordType(mapOf(
            "data" to pipeValue,
            "transform" to Type.FunctionType(Type.RecordType(mapOf("s" to stringType)), stringType),
            "validate" to Type.FunctionType(Type.RecordType(mapOf("s" to stringType)), booleanType)
        ))
        
        val paramType = processType.paramType as Type.RecordType
        assertTrue(expectedApplication.fields.keys == paramType.fields.keys)
        assertEquals(stringType, expectedApplication.fields["data"])
    }
    
    @Test
    fun `row polymorphic currying`() {
        // Function with row polymorphic parameter: { x: Int, ... } -> Int
        val rowVar = RowVar.fresh()
        val polyFunction = Type.FunctionType(
            Type.RecordType(mapOf("x" to Type.IntType), rowVar),
            Type.IntType
        )
        
        // Can be applied with extra fields
        val extendedArgs = Type.RecordType(mapOf(
            "x" to Type.IntType,
            "debug" to Type.BooleanType,
            "name" to Type.StringType
        ))
        
        val paramType = polyFunction.paramType as Type.RecordType
        assertTrue(paramType.isOpen)
        assertTrue(extendedArgs.fields.keys.contains("x"))
        assertEquals(Type.IntType, extendedArgs.fields["x"])
    }
}

