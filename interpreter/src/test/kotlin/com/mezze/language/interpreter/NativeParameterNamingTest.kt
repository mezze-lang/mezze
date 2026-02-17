package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NativeParameterNamingTest {
    
    @Test
    fun testBinaryOperationsUseShortParameterNames() {
        val context = mutableMapOf<String, MezzeValue>()
        NativeFunctions.registerAll(context)
        
        // Test that __native_int_add uses 'l' and 'r' parameters
        val addFunction = context["__native_int_add"] as? NativeMezzeFunction
        assertNotNull(addFunction, "__native_int_add should be registered")
        
        // Create test record with new parameter names
        val testRecord = MezzeRecord(mapOf(
            "l" to MezzeInt(5),
            "r" to MezzeInt(3)
        ))
        
        // Should work with new parameter names
        val result = addFunction!!.implementation(listOf(testRecord))
        assertEquals(MezzeInt(8), result)
    }
    
    @Test
    fun testValueParametersUseValName() {
        val context = mutableMapOf<String, MezzeValue>()
        NativeFunctions.registerAll(context)

        // Test that __native_int_to_string uses 'n' parameter (standardized for numeric types)
        val toStringFunction = context["__native_int_to_string"] as? NativeMezzeFunction
        assertNotNull(toStringFunction, "__native_int_to_string should be registered")

        // Create test record with standardized parameter name
        val testRecord = MezzeRecord(mapOf(
            "n" to MezzeInt(42)
        ))

        // Should work with standardized parameter name
        val result = toStringFunction!!.implementation(listOf(testRecord))
        assertEquals(MezzeString("42"), result)
    }
    
    @Test
    fun testConsistentParameterNamingAcrossOperations() {
        val context = mutableMapOf<String, MezzeValue>()
        NativeFunctions.registerAll(context)
        
        // Test that all binary operations use consistent 'l' and 'r' parameters
        val binaryOps = listOf("__native_int_add", "__native_int_subtract", "__native_int_multiply")
        
        for (opName in binaryOps) {
            val opFunction = context[opName] as? NativeMezzeFunction
            assertNotNull(opFunction, "$opName should be registered")
            
            val testRecord = MezzeRecord(mapOf(
                "l" to MezzeInt(10),
                "r" to MezzeInt(2)
            ))
            
            // Should not throw an exception with consistent parameter names
            assertDoesNotThrow {
                opFunction!!.implementation(listOf(testRecord))
            }
        }
    }
}